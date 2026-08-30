package com.solidus.governance.intervention;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public class InterventionManager {
    private GovernanceEngine engine;
    private final Map<UUID, String> suspiciousAccounts = new ConcurrentHashMap<UUID, String>();
    private volatile boolean tradingLocked = false;
    private volatile String tradingLockReason = null;

    public InterventionManager(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void initialize() {
        this.loadSuspiciousAccounts();
        this.loadTradingLockState();
        SolidusGovernanceMod.LOGGER.info("Intervention Manager initialized. Suspicious accounts: {}, Trading locked: {}", (Object)this.suspiciousAccounts.size(), (Object)this.tradingLocked);
    }

    public CompletableFuture<Double> addBalance(UUID adminUuid, String adminName, UUID targetUuid, String targetName, double amount) {
        if (this.engine.getAccountFreezer().isFrozen(targetUuid)) {
            return CompletableFuture.completedFuture(-1.0);
        }
        return SolidusIntegration.getBalance(targetUuid, targetName).thenCompose(currentBalance -> {
            if (currentBalance < 0.0) {
                return CompletableFuture.completedFuture(-1.0);
            }
            return SolidusIntegration.addBalance(targetUuid, targetName, amount).thenApply(newBalance -> {
                if (newBalance >= 0.0) {
                    this.engine.getAuditLogger().logBalanceChange(adminUuid, adminName, targetUuid, targetName, "ADD_BALANCE", (double)currentBalance, (double)newBalance, amount);
                    double threshold = this.engine.getConfig().getDouble("discord.alert-threshold.intervention", 100000.0);
                    if (amount > threshold) {
                        this.sendDiscordAlert("INTERVENTION", "Large Balance Intervention", "Admin " + adminName + " added " + String.format("%.2f", amount) + " to " + targetName + " (new balance: " + String.format("%.2f", newBalance) + ")");
                    }
                }
                return newBalance;
            });
        });
    }

    public CompletableFuture<Double> removeBalance(UUID adminUuid, String adminName, UUID targetUuid, String targetName, double amount) {
        if (this.engine.getAccountFreezer().isFrozen(targetUuid)) {
            return CompletableFuture.completedFuture(-1.0);
        }
        return SolidusIntegration.getBalance(targetUuid, targetName).thenCompose(currentBalance -> {
            if (currentBalance < 0.0) {
                return CompletableFuture.completedFuture(-1.0);
            }
            return SolidusIntegration.subtractBalance(targetUuid, targetName, amount).thenApply(newBalance -> {
                if (newBalance >= 0.0) {
                    this.engine.getAuditLogger().logBalanceChange(adminUuid, adminName, targetUuid, targetName, "REMOVE_BALANCE", (double)currentBalance, (double)newBalance, -amount);
                }
                return newBalance;
            });
        });
    }

    public CompletableFuture<Boolean> setBalance(UUID adminUuid, String adminName, UUID targetUuid, String targetName, double amount) {
        return SolidusIntegration.getBalance(targetUuid, targetName).thenCompose(currentBalance -> {
            if (currentBalance < 0.0) {
                return CompletableFuture.completedFuture(false);
            }
            return SolidusIntegration.setBalance(targetUuid, targetName, amount).thenApply(success -> {
                if (success.booleanValue()) {
                    this.engine.getAuditLogger().logBalanceChange(adminUuid, adminName, targetUuid, targetName, "SET_BALANCE", (double)currentBalance, amount, amount - currentBalance);
                }
                return success;
            });
        });
    }

    public CompletableFuture<Integer> bulkMultiply(UUID adminUuid, String adminName, double multiplier, double minBalance) {
        return SolidusIntegration.getTopBalances(100000).thenCompose(balances -> {
            ArrayList<CompletableFuture<Boolean>> setFutures = new ArrayList<CompletableFuture<Boolean>>();
            for (SolidusIntegration.BalanceEntry entry : balances) {
                if (entry.balance() <= minBalance) continue;
                double newBalance = (double)Math.round(entry.balance() * multiplier * 100.0) / 100.0;
                if (newBalance < minBalance) {
                    newBalance = minBalance;
                }
                setFutures.add(SolidusIntegration.setBalance(null, entry.playerName(), newBalance).exceptionally(ex -> false));
            }
            if (setFutures.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            return CompletableFuture.allOf(setFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
                int affected = 0;
                for (CompletableFuture<Boolean> future : setFutures) {
                    Boolean result = future.join();
                    if (result == null || !result.booleanValue()) continue;
                    ++affected;
                }
                int finalAffected = affected;
                MinecraftServer srv = SolidusIntegration.getServer();
                if (this.engine != null && srv != null) {
                    srv.execute(() -> this.engine.getAuditLogger().logBulkOperation(adminUuid, adminName, "MULTIPLY", finalAffected, "multiplier=" + multiplier + ";min_balance=" + minBalance));
                }
                return affected;
            });
        });
    }

    public CompletableFuture<Integer> bulkSetAll(UUID adminUuid, String adminName, double amount) {
        return SolidusIntegration.getTopBalances(100000).thenCompose(balances -> {
            ArrayList<CompletableFuture<Boolean>> setFutures = new ArrayList<CompletableFuture<Boolean>>();
            for (SolidusIntegration.BalanceEntry entry : balances) {
                setFutures.add(SolidusIntegration.setBalance(null, entry.playerName(), amount).exceptionally(ex -> false));
            }
            if (setFutures.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            return CompletableFuture.allOf(setFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
                int affected = 0;
                for (CompletableFuture<Boolean> future : setFutures) {
                    Boolean result = future.join();
                    if (result == null || !result.booleanValue()) continue;
                    ++affected;
                }
                int finalAffected = affected;
                MinecraftServer srv = SolidusIntegration.getServer();
                if (this.engine != null && srv != null) {
                    srv.execute(() -> this.engine.getAuditLogger().logBulkOperation(adminUuid, adminName, "SET_ALL", finalAffected, "amount=" + amount));
                }
                return affected;
            });
        });
    }

    public CompletableFuture<Boolean> forceReverse(UUID adminUuid, String adminName, int auditId) {
        AuditDatabase.AuditEntry entry = this.engine.getAuditDatabase().getAuditEntryById(auditId);
        if (entry == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            double before = Double.parseDouble(entry.beforeValue);
            if (entry.targetUuid == null) {
                return CompletableFuture.completedFuture(false);
            }
            UUID targetUuid = UUID.fromString(entry.targetUuid);
            return ((CompletableFuture)SolidusIntegration.setBalance(targetUuid, entry.targetName, before).thenApply(result -> {
                if (result != null && result.booleanValue()) {
                    MinecraftServer srv = SolidusIntegration.getServer();
                    if (this.engine != null && srv != null) {
                        srv.execute(() -> this.engine.getAuditLogger().logRollback(adminUuid, adminName, targetUuid, entry.targetName, "TRANSACTION_REVERSAL", auditId));
                    }
                    return true;
                }
                return false;
            })).exceptionally(ex -> false);
        }
        catch (NumberFormatException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public void markSuspicious(UUID targetUuid, String reason) {
        this.suspiciousAccounts.put(targetUuid, reason);
        this.persistSuspiciousAccount(targetUuid, reason);
    }

    public void unmarkSuspicious(UUID targetUuid) {
        this.suspiciousAccounts.remove(targetUuid);
        this.removeSuspiciousAccount(targetUuid);
    }

    public boolean isMarkedSuspicious(UUID uuid) {
        return this.suspiciousAccounts.containsKey(uuid);
    }

    public String getSuspiciousReason(UUID uuid) {
        return this.suspiciousAccounts.get(uuid);
    }

    public Map<UUID, String> getSuspiciousAccounts() {
        return Map.copyOf(this.suspiciousAccounts);
    }

    public void lockTrading(UUID adminUuid, String adminName, String reason) {
        this.tradingLocked = true;
        this.tradingLockReason = reason;
        this.persistTradingLock(true, reason);
        this.engine.getAuditLogger().logBulkOperation(adminUuid, adminName, "LOCK_TRADING", 0, "reason=" + reason);
    }

    public void unlockTrading(UUID adminUuid, String adminName) {
        this.tradingLocked = false;
        this.tradingLockReason = null;
        this.persistTradingLock(false, null);
        this.engine.getAuditLogger().logBulkOperation(adminUuid, adminName, "UNLOCK_TRADING", 0, null);
    }

    public boolean isTradingLocked() {
        return this.tradingLocked;
    }

    public String getTradingLockReason() {
        return this.tradingLockReason;
    }

    public void persistState() {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        this.persistTradingLock(this.tradingLocked, this.tradingLockReason);
        for (Map.Entry<UUID, String> entry : this.suspiciousAccounts.entrySet()) {
            this.persistSuspiciousAccount(entry.getKey(), entry.getValue());
        }
    }

    private void loadSuspiciousAccounts() {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        Map<UUID, String> loaded = this.engine.getAuditDatabase().loadSuspiciousAccounts();
        this.suspiciousAccounts.putAll(loaded);
    }

    private void loadTradingLockState() {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        AuditDatabase.TradingLockState state = this.engine.getAuditDatabase().loadTradingLockState();
        if (state != null) {
            this.tradingLocked = state.locked();
            this.tradingLockReason = state.reason();
        }
    }

    private void persistSuspiciousAccount(UUID uuid, String reason) {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        this.engine.getAuditDatabase().saveSuspiciousAccount(uuid, reason);
    }

    private void removeSuspiciousAccount(UUID uuid) {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        this.engine.getAuditDatabase().removeSuspiciousAccount(uuid);
    }

    private void persistTradingLock(boolean locked, String reason) {
        if (this.engine == null || this.engine.getAuditDatabase() == null) {
            return;
        }
        this.engine.getAuditDatabase().saveTradingLockState(locked, reason);
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
