package com.solidus.governance.automation;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.intervention.InterventionManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

public class GovernanceAutomator {
    private GovernanceEngine engine;
    private volatile boolean lockdownActive = false;
    private volatile String lockdownReason = null;
    private volatile boolean autoFreezeEnabled = false;
    /** Timestamp (ms) of the last anti-inflation tax raise - enforces the cooldown. */
    private volatile long lastAntiInflationRaiseMs = 0L;
    /** B-11 fix (audit round 3): evaluations are dispatched asynchronously
     *  (stats future callbacks), so two overlapping 60s ticks could both pass
     *  the cooldown check before either updated the timestamp. The in-flight
     *  guard serializes evaluations; the cooldown then holds. */
    private final AtomicBoolean antiInflationInFlight = new AtomicBoolean(false);
    /** C-10 fix: the decrease branch used to rewrite config + disk every 60s
     *  with no cooldown of its own; any rate change now shares one. */
    private volatile long lastAntiInflationChangeMs = 0L;

    public GovernanceAutomator(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void initialize() {
        GovernanceConfig config = this.engine.getConfig();
        // C-9 fix (audit round 3): the lockdown flag was memory-only while the
        // underlying trading lock persists in the database. After a restart the
        // economy was still locked but /governance status reported "Lockdown:
        // INACTIVE" - and deactivateLockdown would have left the persisted lock
        // dangling. Derive the flag from the persisted state: a trading lock whose
        // reason carries the EMERGENCY marker is a lockdown.
        InterventionManager intervention = this.engine.getInterventionManager();
        if (intervention != null && intervention.isTradingLocked()) {
            String lockReason = intervention.getTradingLockReason();
            if (lockReason != null && lockReason.startsWith("EMERGENCY: ")) {
                this.lockdownActive = true;
                this.lockdownReason = lockReason.substring("EMERGENCY: ".length());
                SolidusGovernanceMod.LOGGER.warn(
                    "Governance Automator: persisted EMERGENCY lockdown restored ({}).", this.lockdownReason);
            }
        }
        if (config.getBool("automation.enabled", false)) {
            this.autoFreezeEnabled = config.getBool("automation.auto-freeze.enabled", false);
            SolidusGovernanceMod.LOGGER.info("Governance Automator initialized. Automation ENABLED.");
            SolidusGovernanceMod.LOGGER.info("  Anti-inflation: {}", (Object)(config.getBool("automation.anti-inflation.enabled", false) ? "ON" : "OFF"));
            SolidusGovernanceMod.LOGGER.info("  Wealth caps: {}", (Object)(config.getBool("automation.wealth-cap.enabled", false) ? "ON" : "OFF"));
            SolidusGovernanceMod.LOGGER.info("  Emergency lockdown: {}", (Object)(config.getBool("automation.emergency-lockdown.enabled", false) ? "ON" : "OFF"));
            SolidusGovernanceMod.LOGGER.info("  Auto-freeze: {}", (Object)(this.autoFreezeEnabled ? "ON" : "OFF"));
        } else {
            SolidusGovernanceMod.LOGGER.info("Governance Automator initialized. Automation DISABLED.");
        }
    }

    public void onPeriodicCheck() {
        if (!this.engine.isPremiumEnabled()) {
            return;
        }
        if (!this.engine.getConfig().getBool("automation.enabled", false)) {
            return;
        }
        this.checkAntiInflation();
        this.checkWealthCaps();
    }

    private void checkAntiInflation() {
        if (!this.engine.getConfig().getBool("automation.anti-inflation.enabled", false)) {
            return;
        }
        if (!this.antiInflationInFlight.compareAndSet(false, true)) {
            // B-11: an evaluation from a previous tick is still running (slow stats
            // lookup); skip rather than stack a second one on top.
            return;
        }
        try {
            double threshold = this.engine.getConfig().getDouble("automation.anti-inflation.threshold", 15.0);
            double currentAuctionRate = this.engine.getConfig().getDouble("taxation.auction.rate", 0.05);
            double maxRate = 0.25;
            // R28: prefer Core's single-query aggregates; only an old Core without
            // the API falls back to the legacy getTopBalances(100000) row pull.
            CompletableFuture<SolidusIntegration.EconomyStats> statsFuture = SolidusIntegration.getEconomyStats();
            if (statsFuture != null) {
                statsFuture
                    .whenComplete((stats, statsEx) -> {
                        try {
                            if (statsEx != null || stats == null) {
                                this.evaluateAntiInflationFromRows(threshold, currentAuctionRate, maxRate);
                            } else {
                                this.evaluateAntiInflation(stats.avgBalance(), threshold, currentAuctionRate, maxRate);
                            }
                        } finally {
                            this.antiInflationInFlight.set(false);
                        }
                    });
                return;
            }
            this.evaluateAntiInflationFromRows(threshold, currentAuctionRate, maxRate);
        } finally {
            // Synchronous fallback path finished inline; async paths clear the
            // flag in their whenComplete above.
            if (SolidusIntegration.getEconomyStats() == null) {
                this.antiInflationInFlight.set(false);
            }
        }
    }

    /** Legacy path: row pull to compute the average (old Core builds). */
    private void evaluateAntiInflationFromRows(double threshold, double currentAuctionRate, double maxRate) {
        SolidusIntegration.getTopBalances(100000).thenAccept(balances -> {
            double totalSupply = 0.0;
            for (SolidusIntegration.BalanceEntry entry : balances) {
                totalSupply += entry.balance();
            }
            double avgBalance = balances.isEmpty() ? 0.0 : totalSupply / (double)balances.size();
            this.evaluateAntiInflation(avgBalance, threshold, currentAuctionRate, maxRate);
        });
    }

    private void evaluateAntiInflation(double avgBalance, double threshold, double currentAuctionRate, double maxRate) {
        if (avgBalance > threshold && currentAuctionRate < maxRate) {
            // COOLDOWN FIX: the periodic check runs every ~60s, and every
            // above-threshold check used to raise the auction rate by 0.01
            // AND synchronously rewrite the config file - climbing to the
            // 0.25 cap in ~20 minutes and hammering the disk along the way,
            // with no administrator-visible breathing room. Raises are now
            // separated by a configurable cooldown
            // (automation.anti-inflation.cooldown-minutes, default 60).
            long cooldownMs = this.engine.getConfig().getInt("automation.anti-inflation.cooldown-minutes", 60) * 60_000L;
            long now = System.currentTimeMillis();
            if (cooldownMs > 0 && now - this.lastAntiInflationRaiseMs < cooldownMs) {
                SolidusGovernanceMod.LOGGER.debug(
                    "Anti-inflation: raise skipped (cooldown) - avg {} above threshold {}",
                    String.format("%.2f", avgBalance), String.format("%.2f", threshold));
                return;
            }
            this.lastAntiInflationRaiseMs = now;
            this.lastAntiInflationChangeMs = now;
            double newRate = Math.min(currentAuctionRate + 0.01, maxRate);
            this.engine.getConfig().set("taxation.auction.rate", String.valueOf(newRate));
            this.engine.getAuditLogger().logAutomation("ANTI_INFLATION_TAX_INCREASE", "avg_balance=" + String.format("%.2f", avgBalance) + ";threshold=" + threshold + ";old_rate=" + String.format("%.3f", currentAuctionRate) + ";new_rate=" + String.format("%.3f", newRate));
            this.sendDiscordAlert("AUTOMATION", "Anti-Inflation: Tax Rate Increased", "Auction tax increased from " + String.format("%.1f%%", currentAuctionRate * 100.0) + " to " + String.format("%.1f%%", newRate * 100.0) + " (avg balance: " + String.format("%.2f", avgBalance) + ")");
            SolidusGovernanceMod.LOGGER.info("Anti-inflation: Auction tax increased from {}% to {}% (avg balance: {})", new Object[]{String.format("%.1f", currentAuctionRate * 100.0), String.format("%.1f", newRate * 100.0), String.format("%.2f", avgBalance)});
        } else if (avgBalance < threshold * 0.8 && currentAuctionRate > 0.05) {
            // C-10 fix (audit round 3): the decrease branch had no cooldown - it
            // rewrote the config and disk every 60s tick. Any rate change now
            // shares the same cooldown as a raise.
            long cooldownMs = this.engine.getConfig().getInt("automation.anti-inflation.cooldown-minutes", 60) * 60_000L;
            long now = System.currentTimeMillis();
            if (cooldownMs > 0 && now - this.lastAntiInflationChangeMs < cooldownMs) {
                SolidusGovernanceMod.LOGGER.debug("Anti-inflation: decrease skipped (cooldown)");
                return;
            }
            this.lastAntiInflationChangeMs = now;
            double newRate = Math.max(currentAuctionRate - 0.005, 0.05);
            this.engine.getConfig().set("taxation.auction.rate", String.valueOf(newRate));
            this.engine.getAuditLogger().logAutomation("ANTI_INFLATION_TAX_DECREASE", "avg_balance=" + String.format("%.2f", avgBalance) + ";old_rate=" + String.format("%.3f", currentAuctionRate) + ";new_rate=" + String.format("%.3f", newRate));
            this.sendDiscordAlert("AUTOMATION", "Anti-Inflation: Tax Rate Decreased", "Auction tax decreased from " + String.format("%.1f%%", currentAuctionRate * 100.0) + " to " + String.format("%.1f%%", newRate * 100.0) + " (avg balance: " + String.format("%.2f", avgBalance) + ")");
        } else {
            SolidusGovernanceMod.LOGGER.debug("Anti-inflation monitoring: avg_balance={}, threshold={} (no action)", new Object[]{String.format("%.2f", avgBalance), String.valueOf(threshold)});
        }
    }

    private void checkWealthCaps() {
        if (!this.engine.getConfig().getBool("automation.wealth-cap.enabled", false)) {
            return;
        }
        double maxBalance = this.engine.getConfig().getDouble("automation.wealth-cap.amount", 1.0E7);
        // C-7 fix (audit round 3): the treasury is an ordinary Core account, so
        // the cap used to drain the collected tax revenue back down to the cap
        // every hour - silently destroying exactly the money taxation had
        // gathered. The configured treasury account is now exempt.
        String treasuryUuidRaw = this.engine.getConfig().getString("taxation.treasury.account", "");
        UUID treasuryUuid = null;
        if (!treasuryUuidRaw.isBlank()) {
            try {
                treasuryUuid = UUID.fromString(treasuryUuidRaw);
            } catch (IllegalArgumentException ignored) {
                // Already warned about in the tax engine.
            }
        }
        final UUID treasuryUuidFinal = treasuryUuid;
        SolidusIntegration.getTopBalances(100).thenCompose(balances -> {
            ArrayList<CompletionStage> capFutures = new ArrayList<CompletionStage>();
            for (SolidusIntegration.BalanceEntry entry : balances) {
                if (!(entry.balance() > maxBalance)) continue;
                if (entry.uuid() != null && entry.uuid().equals(treasuryUuidFinal)) {
                    continue; // never cap the tax treasury
                }
                double excess = entry.balance() - maxBalance;
                // UUID-FIRST FIX: the cap used to call setBalance(null, playerName, ...)
                // and let Core resolve the account BY NAME - on offline-mode servers
                // or after a rename that can cap the WRONG player's balance (an
                // irreversible intervention). Core >= 2.1.x leaderboard entries now
                // carry the account's real UUID; only a genuinely old Core (no uuid
                // accessor) degrades to name resolution, with a loud warning.
                if (entry.uuid() == null) {
                    SolidusGovernanceMod.LOGGER.warn(
                        "Wealth cap: Core did not supply a UUID for '{}' - resolving by NAME (risky on offline-mode servers; upgrade Core to 2.1.x+)",
                        entry.playerName());
                }
                CompletionStage capChain = ((CompletableFuture)SolidusIntegration.setBalance(entry.uuid(), entry.playerName(), maxBalance).thenAccept(result -> {
                    MinecraftServer srv = SolidusIntegration.getServer();
                    if (result != null && result.booleanValue()) {
                        if (this.engine != null && srv != null) {
                            srv.execute(() -> this.engine.getAuditLogger().logAutomation("WEALTH_CAP_ENFORCED", "player=" + entry.playerName() + ";balance=" + entry.balance() + ";cap=" + maxBalance + ";excess_removed=" + String.format("%.2f", excess)));
                        }
                        this.sendDiscordAlert("AUTOMATION", "Wealth Cap Enforced", "Player " + entry.playerName() + " capped from " + String.format("%.2f", entry.balance()) + " to " + String.format("%.2f", maxBalance) + " (excess: " + String.format("%.2f", excess) + " removed)");
                        SolidusGovernanceMod.LOGGER.info("Wealth cap enforced: {} had {} (excess: {} removed)", new Object[]{entry.playerName(), String.format("%.2f", entry.balance()), String.format("%.2f", excess)});
                    } else if (this.engine != null && srv != null) {
                        srv.execute(() -> this.engine.getAuditLogger().logAutomation("WEALTH_CAP_FAILED", "player=" + entry.playerName() + ";reason=setBalance_failed"));
                    }
                })).exceptionally(ex -> {
                    SolidusGovernanceMod.LOGGER.debug("Wealth cap enforcement failed for {}", (Object)entry.playerName());
                    return null;
                });
                capFutures.add(capChain);
            }
            if (capFutures.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(capFutures.toArray(new CompletableFuture[0]));
        });
    }

    public void activateLockdown(UUID adminUuid, String adminName, String reason) {
        this.lockdownActive = true;
        this.lockdownReason = reason;
        this.engine.getInterventionManager().lockTrading(adminUuid, adminName, "EMERGENCY: " + reason);
        this.engine.getAuditLogger().logAutomation("EMERGENCY_LOCKDOWN_ACTIVATED", "reason=" + reason + ";admin=" + adminName);
        this.sendDiscordAlert("LOCKDOWN", "Emergency Lockdown Activated", "Lockdown activated by " + adminName + ": " + reason);
        SolidusGovernanceMod.LOGGER.warn("EMERGENCY ECONOMY LOCKDOWN ACTIVATED: {}", (Object)reason);
    }

    public void deactivateLockdown(UUID adminUuid, String adminName) {
        this.lockdownActive = false;
        this.lockdownReason = null;
        this.engine.getInterventionManager().unlockTrading(adminUuid, adminName);
        this.engine.getAuditLogger().logAutomation("EMERGENCY_LOCKDOWN_DEACTIVATED", "admin=" + adminName);
        this.sendDiscordAlert("LOCKDOWN", "Emergency Lockdown Deactivated", "Lockdown deactivated by " + adminName);
        SolidusGovernanceMod.LOGGER.info("Emergency economy lockdown deactivated.");
    }

    public boolean isLockdownActive() {
        return this.lockdownActive;
    }

    public String getLockdownReason() {
        return this.lockdownReason;
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
