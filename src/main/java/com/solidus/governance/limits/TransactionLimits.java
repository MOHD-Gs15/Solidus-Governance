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
        if (dailyMax >= 0.0) {
            DailyUsage usage = this.getOrCreateUsage(player);
            double newTotal = usage.transferTotal + amount;
            if (newTotal > dailyMax) {
                this.auditLimitExceeded(player, "DAILY_TRANSFER_LIMIT", "attempted=" + amount + ";current_total=" + usage.transferTotal + ";daily_max=" + dailyMax);
                return false;
            }
        }
        return true;
    }

    public boolean checkAuctionLimit(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return true;
        }
        int dailyMax = this.config.getInt("limits.auction.daily-max", -1);
        if (dailyMax >= 0) {
            DailyUsage usage = this.getOrCreateUsage(player);
            if (usage.auctionCount >= dailyMax) {
                this.auditLimitExceeded(player, "DAILY_AUCTION_LIMIT", "current_count=" + usage.auctionCount + ";daily_max=" + dailyMax);
                return false;
            }
        }
        return true;
    }

    public void recordTransfer(UUID player, double amount) {
        if (!this.engine.isPremiumEnabled()) {
            return;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        usage.transferTotal += amount;
        this.persistUsage(player, usage);
        SolidusGovernanceMod.LOGGER.debug("Recorded transfer of {} for {}. Daily total: {}", new Object[]{amount, player, usage.transferTotal});
    }

    public void recordAuctionListing(UUID player) {
        if (!this.engine.isPremiumEnabled()) {
            return;
        }
        DailyUsage usage = this.getOrCreateUsage(player);
        ++usage.auctionCount;
        this.persistUsage(player, usage);
        SolidusGovernanceMod.LOGGER.debug("Recorded auction listing for {}. Daily count: {}", (Object)player, (Object)usage.auctionCount);
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
        int dailyMax = this.config.getInt("limits.auction.daily-max", -1);
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
