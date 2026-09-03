package com.solidus.governance.limits;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditLogger;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.limits.LimitsDatabase;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public class TransactionLimits {
    private final GovernanceConfig config;
    private final LimitsDatabase database;
    private final GovernanceEngine engine;
    private final ConcurrentHashMap<String, DailyUsage> dailyUsageMap = new ConcurrentHashMap();
    private volatile String currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

    public TransactionLimits(GovernanceConfig config, LimitsDatabase database, GovernanceEngine engine) {
        this.config = config;
        this.database = database;
        this.engine = engine;
    }

    /**
     * Atomically checks AND reserves the transfer against the daily limit.
     *
     * <p>TOCTOU fix: the old flow checked the limit in {@code allowTransfer}
     * and recorded usage later in {@code afterTransfer} - two separate
     * critical sections with the whole settlement window in between, so two
     * concurrent transfers could both pass the check and blow past the daily
     * cap. The check and the usage increment now happen inside ONE
     * {@code synchronized (usage)} block, and {@link #recordTransfer} no
     * longer adds the amount again (the reservation already counted it).</p>
     *
     * <p>Trade-off: if Core aborts the transfer after this veto (e.g.
     * insufficient funds), the reserved amount stays counted for the day -
     * limits can only tighten, never loosen. That is the safe direction
     * for the economy.</p>
     */
    public boolean checkTransferLimit(UUID player, double amount) {
        if (!this.engine.isPremiumEnabled()) {
            return true;
        }
        double minAmount = this.config.getDouble("limits.transfer.min", 0.0);
        if (amount < minAmount) {
            this.auditLimitExceeded(player, "TRANSFER_BELOW_MIN", "amount=" + amount + ";min=" + minAmount);
            return false;
        }
        double maxAmount = this.config.getDouble("limits.transfer.max", -1.0);
        if (maxAmount > 0.0 && amount > maxAmount) {
            this.auditLimitExceeded(player, "TRANSFER_ABOVE_MAX", "amount=" + amount + ";max=" + maxAmount);
            return false;
        }
        double dailyMax = this.config.getDouble("limits.transfer.daily-max", -1.0);
        if (dailyMax < 0.0) {
            return true;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        boolean allowed;
        synchronized (usage) {
            double newTotal = usage.transferTotal + amount;
            allowed = newTotal <= dailyMax;
            if (allowed) {
                usage.transferTotal = newTotal; // atomic check-and-reserve
            }
        }
        if (!allowed) {
            this.auditLimitExceeded(player, "DAILY_TRANSFER_LIMIT", "attempted=" + amount + ";current_total=" + usage.transferTotal + ";daily_max=" + dailyMax);
            return false;
        }
        this.persistUsage(player, usage);
        return true;
    }

    /**
     * Atomically checks AND reserves one auction listing slot.
     *
     * <p>TOCTOU fix, same shape as {@link #checkTransferLimit}: the increment
     * happens in the same critical section as the check, so concurrent
     * listings cannot both slip past the daily cap.</p>
     */
    public boolean checkAuctionLimit(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return true;
        }
        int dailyMax = TransactionLimits.readAuctionDailyMax(this.config);
        if (dailyMax < 0) {
            return true;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        boolean allowed;
        synchronized (usage) {
            allowed = usage.auctionCount < dailyMax;
            if (allowed) {
                ++usage.auctionCount; // atomic check-and-reserve
            }
        }
        if (!allowed) {
            this.auditLimitExceeded(player, "DAILY_AUCTION_LIMIT", "current_count=" + usage.auctionCount + ";daily_max=" + dailyMax);
            return false;
        }
        this.persistUsage(player, usage);
        return true;
    }

    /**
     * Kept for hook-API compatibility. Usage is now reserved atomically inside
     * {@link #checkTransferLimit} at veto time, so this post-settlement
     * notification must NOT add the amount again - doing so would double-count
     * every transfer.
     */
    public void recordTransfer(UUID player, double amount) {
        if (!this.engine.isPremiumEnabled()) {
            return;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        SolidusGovernanceMod.LOGGER.debug("Transfer of {} for {} confirmed (already reserved at veto time). Daily total: {}", new Object[]{amount, player, usage.transferTotal});
    }

    /**
     * Kept for hook-API compatibility - see {@link #recordTransfer}. The
     * listing slot was already reserved atomically at veto time.
     */
    public void recordAuctionListing(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        SolidusGovernanceMod.LOGGER.debug("Auction listing for {} confirmed (already reserved at veto time). Daily count: {}", (Object)player, (Object)usage.auctionCount);
    }

    /**
     * B-5 fix (audit round 3): the limit-set command historically stored the
     * auction daily max as a DOUBLE ("10.0"), and getInt's Integer.parseInt
     * then threw inside GovernanceConfig, silently returning the -1 default -
     * disabling the limit while the admin believed it was set. New writes are
     * whole numbers; this reader additionally tolerates legacy "10.0" values
     * from older configs by rounding the double form.
     */
    static int readAuctionDailyMax(GovernanceConfig config) {
        String raw = config.getString("limits.auction.daily-max", "-1");
        if (raw != null && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException notInt) {
                try {
                    double asDouble = Double.parseDouble(raw.trim());
                    if (Double.isFinite(asDouble)) {
                        return (int) Math.floor(asDouble);
                    }
                } catch (NumberFormatException notDouble) {
                    // fall through to the default below
                }
            }
        }
        return -1;
    }

    public void resetDailyLimits() {
        SolidusGovernanceMod.LOGGER.info("Resetting daily transaction limits...");
        this.dailyUsageMap.clear();
        this.currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        this.database.cleanupOldEntries();
        SolidusGovernanceMod.LOGGER.info("Daily transaction limits reset complete. New date: {}", (Object)this.currentDate);
    }

    public double getRemainingTransferLimit(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return Double.MAX_VALUE;
        }
        double dailyMax = this.config.getDouble("limits.transfer.daily-max", -1.0);
        if (dailyMax < 0.0) {
            return Double.MAX_VALUE;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        double remaining = dailyMax - usage.transferTotal;
        return Math.max(0.0, remaining);
    }

    public int getRemainingAuctionLimit(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return Integer.MAX_VALUE;
        }
        int dailyMax = TransactionLimits.readAuctionDailyMax(this.config);
        if (dailyMax < 0) {
            return Integer.MAX_VALUE;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        return Math.max(0, dailyMax - usage.auctionCount);
    }

    public void resetPlayerLimits(UUID player) {
        String key = this.usageKey(player);
        this.dailyUsageMap.remove(key);
        this.database.resetPlayerDailyUsage(player);
        SolidusGovernanceMod.LOGGER.info("Reset daily limits for player {}", (Object)player);
    }

    public DailyUsageView getPlayerUsage(UUID player) {
        DailyUsage usage = this.getOrCreateUsage(player);
        return new DailyUsageView(player, this.currentDate, usage.transferTotal, usage.auctionCount, this.getRemainingTransferLimit(player), this.getRemainingAuctionLimit(player));
    }

    private DailyUsage getOrCreateUsage(UUID player) {
        String key = this.usageKey(player);
        return this.dailyUsageMap.computeIfAbsent(key, k -> {
            LimitsDatabase.DailyUsage dbUsage = this.database.loadDailyUsage(player, this.currentDate);
            if (dbUsage != null) {
                return new DailyUsage(dbUsage.transferTotal(), dbUsage.auctionCount());
            }
            return new DailyUsage(0.0, 0);
        });
    }

    private void persistUsage(UUID player, DailyUsage usage) {
        this.database.saveDailyUsage(player, this.currentDate, usage.transferTotal, usage.auctionCount);
    }

    private String usageKey(UUID player) {
        return player.toString() + ":" + this.currentDate;
    }

    private void auditLimitExceeded(UUID player, String action, String details) {
        MinecraftServer server = SolidusIntegration.getServer();
        if (server != null) {
            server.execute(() -> {
                AuditLogger auditLogger = this.engine.getAuditLogger();
                if (auditLogger != null) {
                    auditLogger.logLimitExceeded(player, action, details);
                }
            });
        }
        SolidusGovernanceMod.LOGGER.warn("Transaction limit exceeded: player={}, action={}, details={}", new Object[]{player, action, details});
        this.sendDiscordAlert("LIMITS", "Transaction Limit Exceeded", "Player " + String.valueOf(player) + ": " + action + " \u2014 " + details);
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }

    private static class DailyUsage {
        volatile double transferTotal;
        volatile int auctionCount;

        DailyUsage(double transferTotal, int auctionCount) {
            this.transferTotal = transferTotal;
            this.auctionCount = auctionCount;
        }
    }

    public record DailyUsageView(UUID playerUuid, String date, double transferTotal, int auctionCount, double remainingTransfer, int remainingAuctions) {
    }
}
