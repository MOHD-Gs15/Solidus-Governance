package com.solidus.governance.recovery;

import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;

public class RollbackEngine {
    private GovernanceEngine engine;

    public RollbackEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public CompletableFuture<String> rollbackById(UUID adminUuid, String adminName, int auditId) {
        AuditDatabase.AuditEntry entry = this.engine.getAuditDatabase().getAuditEntryById(auditId);
        if (entry == null) {
            return CompletableFuture.completedFuture("Audit entry #" + auditId + " not found.");
        }
        if (entry.beforeValue == null || entry.afterValue == null) {
            return CompletableFuture.completedFuture("Audit entry #" + auditId + " has no reversible before/after values.");
        }
        if ("RECOVERY".equals(entry.category)) {
            return CompletableFuture.completedFuture("Cannot roll back a recovery operation (prevents rollback loops).");
        }
        try {
            double before = Double.parseDouble(entry.beforeValue);
            UUID targetUuid = UUID.fromString(entry.targetUuid);
            return SolidusIntegration.setBalance(targetUuid, entry.targetName, before).thenApply(result -> {
                if (result != null && result.booleanValue()) {
                    MinecraftServer srv = SolidusIntegration.getServer();
                    if (this.engine != null && srv != null) {
                        srv.execute(() -> {
                            this.engine.getAuditLogger().logRollback(adminUuid, adminName, targetUuid, entry.targetName, "BY_ID", auditId);
                            this.engine.getAuditDatabase().createRollbackChain(0, auditId, adminUuid);
                        });
                    }
                    this.sendDiscordAlert("RECOVERY", "Rollback Executed", "Audit #" + auditId + " rolled back: " + entry.targetName + " balance restored to " + before);
                    return "Rolled back audit #" + auditId + ": " + entry.targetName + " balance restored to " + before;
                }
                return "Failed to roll back audit #" + auditId + ": setBalance returned false.";
            }).exceptionally(ex -> "Failed to roll back audit #" + auditId + ": " + ((Throwable)ex).getMessage());
        }
        catch (NumberFormatException e) {
            return CompletableFuture.completedFuture("Failed to roll back audit #" + auditId + ": invalid before/after values.");
        }
        catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture("Failed to roll back audit #" + auditId + ": invalid target UUID.");
        }
    }

    public CompletableFuture<String> rollbackPlayer(UUID adminUuid, String adminName, UUID targetUuid, String targetName, long fromTimestamp) {
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().searchByTarget(targetUuid, 100);
        AuditDatabase.AuditEntry restoreEntry = RollbackEngine.selectRestoreEntry(entries, fromTimestamp, null);
        if (restoreEntry == null) {
            return CompletableFuture.completedFuture("No actions to roll back for player " + targetName);
        }
        double before = Double.parseDouble(restoreEntry.beforeValue);
        return SolidusIntegration.setBalance(targetUuid, targetName, before).thenApply(result -> {
            if (result != null && result.booleanValue()) {
                MinecraftServer srv = SolidusIntegration.getServer();
                if (this.engine != null && srv != null) {
                    srv.execute(() -> this.engine.getAuditLogger().logRollback(adminUuid, adminName, targetUuid, targetName, "PLAYER", restoreEntry.id));
                }
                return "Rolled back 1 action for player " + targetName + ": balance restored to " + before + " (state before audit #" + restoreEntry.id + ", the earliest affected action in the window).";
            }
            return "Failed to roll back player " + targetName + ": setBalance returned false.";
        }).exceptionally(ex -> "Failed to roll back player " + targetName + ": " + ((Throwable)ex).getMessage());
    }

    static AuditDatabase.AuditEntry selectRestoreEntry(List<AuditDatabase.AuditEntry> entries, long fromTimestamp, Long toTimestamp) {
        AuditDatabase.AuditEntry earliest = null;
        if (entries == null) {
            return null;
        }
        for (AuditDatabase.AuditEntry entry : entries) {
            if (entry.timestamp < fromTimestamp) continue;
            if (toTimestamp != null && entry.timestamp > toTimestamp) continue;
            if ("RECOVERY".equals(entry.category)) continue;
            if (entry.targetUuid == null || entry.beforeValue == null) continue;
            try {
                UUID.fromString(entry.targetUuid);
                Double.parseDouble(entry.beforeValue);
            }
            catch (Exception e) {
                continue;
            }
            if (earliest == null || entry.timestamp < earliest.timestamp) {
                earliest = entry;
            }
        }
        return earliest;
    }

    public CompletableFuture<String> rollbackTimeframe(UUID adminUuid, String adminName, long fromTimestamp, long toTimestamp) {
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().getRecentAuditLogs(10000);
        LinkedHashMap<UUID, AuditDatabase.AuditEntry> restorePerPlayer = new LinkedHashMap<UUID, AuditDatabase.AuditEntry>();
        for (AuditDatabase.AuditEntry entry : entries) {
            if (entry.timestamp < fromTimestamp || entry.timestamp > toTimestamp) continue;
            if ("RECOVERY".equals(entry.category) || entry.targetUuid == null || entry.beforeValue == null) continue;
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(entry.targetUuid);
                Double.parseDouble(entry.beforeValue);
            }
            catch (Exception e) {
                continue;
            }
            AuditDatabase.AuditEntry current = restorePerPlayer.get(targetUuid);
            if (current == null || entry.timestamp < current.timestamp) {
                restorePerPlayer.put(targetUuid, entry);
            }
        }
        if (restorePerPlayer.isEmpty()) {
            return CompletableFuture.completedFuture("No actions to roll back in the specified timeframe.");
        }
        ArrayList<CompletableFuture<Boolean>> rollbackFutures = new ArrayList<CompletableFuture<Boolean>>();
        int[] rolledBack = new int[]{0};
        for (Map.Entry<UUID, AuditDatabase.AuditEntry> restore : restorePerPlayer.entrySet()) {
            UUID targetUuid = restore.getKey();
            AuditDatabase.AuditEntry entry = restore.getValue();
            double before = Double.parseDouble(entry.beforeValue);
            CompletableFuture<Boolean> chain = SolidusIntegration.setBalance(targetUuid, entry.targetName, before).thenApply(result -> {
                if (result != null && result.booleanValue()) {
                    MinecraftServer srv = SolidusIntegration.getServer();
                    if (this.engine != null && srv != null) {
                        srv.execute(() -> this.engine.getAuditLogger().logRollback(adminUuid, adminName, targetUuid, entry.targetName, "TIMEFRAME", entry.id));
                    }
                    return true;
                }
                return false;
            }).exceptionally(ex -> false);
            rollbackFutures.add(chain);
        }
        return CompletableFuture.allOf(rollbackFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
            for (CompletableFuture<Boolean> future : rollbackFutures) {
                if (!Boolean.TRUE.equals(future.join())) continue;
                rolledBack[0] = rolledBack[0] + 1;
            }
            return "Rolled back " + rolledBack[0] + " unique players in timeframe (balance restored to the state before their earliest affected action).";
        });
    }

    public CompletableFuture<String> dryRunRollback(UUID adminUuid, int auditId) {
        AuditDatabase.AuditEntry entry = this.engine.getAuditDatabase().getAuditEntryById(auditId);
        if (entry == null) {
            return CompletableFuture.completedFuture("Audit entry #" + auditId + " not found.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[DRY RUN] Rollback preview for audit #").append(auditId).append(":\n");
        sb.append("  Action: ").append(entry.action).append("\n");
        sb.append("  Target: ").append(entry.targetName).append("\n");
        sb.append("  Before: ").append(entry.beforeValue).append("\n");
        sb.append("  After: ").append(entry.afterValue).append("\n");
        if (entry.beforeValue != null && entry.targetUuid != null) {
            try {
                UUID targetUuid = UUID.fromString(entry.targetUuid);
                return SolidusIntegration.getBalance(targetUuid, entry.targetName).thenApply(current -> {
                    sb.append("  Current balance: ").append(String.format("%.2f", current)).append("\n");
                    try {
                        double before = Double.parseDouble(entry.beforeValue);
                        sb.append("  Would restore balance to: ").append(entry.beforeValue);
                        sb.append(" (change: ").append(String.format("%+.2f", before - current)).append(")");
                    }
                    catch (NumberFormatException e) {
                        sb.append("  Would restore balance to: ").append(entry.beforeValue);
                    }
                    return sb.toString();
                }).exceptionally(ex -> {
                    sb.append("  Would restore balance to: ").append(entry.beforeValue);
                    return sb.toString();
                });
            }
            catch (Exception e) {
                sb.append("  Would restore balance to: ").append(entry.beforeValue);
            }
        }
        return CompletableFuture.completedFuture(sb.toString());
    }

    public List<AuditDatabase.AuditEntry> getTransactionTimeline(UUID targetUuid, int limit) {
        return this.engine.getAuditDatabase().searchByTarget(targetUuid, limit);
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
