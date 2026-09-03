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
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

public class RollbackEngine {
    private GovernanceEngine engine;

    /**
     * Concurrency lock (R07): only one rollback may run at a time. Rollbacks
     * overwrite balances from historical audit snapshots; two interleaved
     * rollbacks could apply stale restore points on top of each other and
     * finish in an inconsistent state. Dry runs are read-only and excluded.
     */
    private final AtomicBoolean rollbackInProgress = new AtomicBoolean(false);

    private static final String BUSY_MESSAGE =
        "A rollback operation is already in progress - wait for it to finish "
            + "(use /governance dryrun to preview safely in the meantime).";

    /** A-3: bounded search windows, disclosed in user-facing messages. */
    static final int ROLLBACK_PLAYER_WINDOW = 100;
    static final int ROLLBACK_TIMEFRAME_WINDOW = 10_000;

    public RollbackEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    private boolean tryBeginRollback() {
        return this.rollbackInProgress.compareAndSet(false, true);
    }

    private void endRollback() {
        this.rollbackInProgress.set(false);
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
        // A-8 fix (audit round 3): CONFIG/LIMITS audit rows carry before/after
        // values but NO target UUID; UUID.fromString(null) here was an uncaught
        // NPE that killed the command with a stack trace. Fail with a clear
        // message instead.
        if (entry.targetUuid == null) {
            return CompletableFuture.completedFuture("Audit entry #" + auditId
                + " has no target player (" + entry.category + " entry) - only balance mutations can be rolled back.");
        }
        try {
            double before = Double.parseDouble(entry.beforeValue);
            UUID targetUuid = UUID.fromString(entry.targetUuid);
            if (!tryBeginRollback()) {
                return CompletableFuture.completedFuture(BUSY_MESSAGE);
            }
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
            }).exceptionally(ex -> "Failed to roll back audit #" + auditId + ": " + ((Throwable)ex).getMessage())
            .whenComplete((ignored, throwable) -> endRollback());
        }
        catch (NumberFormatException e) {
            return CompletableFuture.completedFuture("Failed to roll back audit #" + auditId + ": invalid before/after values.");
        }
        catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture("Failed to roll back audit #" + auditId + ": invalid target UUID.");
        }
    }

    public CompletableFuture<String> rollbackPlayer(UUID adminUuid, String adminName, UUID targetUuid, String targetName, long fromTimestamp) {
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().searchByTarget(targetUuid, ROLLBACK_PLAYER_WINDOW);
        AuditDatabase.AuditEntry restoreEntry = RollbackEngine.selectRestoreEntry(entries, fromTimestamp, null);
        if (restoreEntry == null) {
            // A-3 fix (audit round 3): the bounded window (newest 100 rows for
            // this target) used to report "no actions" without disclosing the
            // bound - a window older than the newest slice looked like "nothing
            // happened". The message now states exactly what was searched.
            return CompletableFuture.completedFuture("No actions to roll back for " + targetName
                + " within the newest " + ROLLBACK_PLAYER_WINDOW + " audit rows for this player."
                + " If the incident is older, narrow the timeframe or export the audit log to locate it.");
        }
        double before;
        try {
            before = Double.parseDouble(restoreEntry.beforeValue);
        }
        catch (NumberFormatException e) {
            return CompletableFuture.completedFuture("Failed to roll back player " + targetName + ": invalid before-value in audit #" + restoreEntry.id + ".");
        }
        if (!tryBeginRollback()) {
            return CompletableFuture.completedFuture(BUSY_MESSAGE);
        }
        return SolidusIntegration.setBalance(targetUuid, targetName, before).thenApply(result -> {
            if (result != null && result.booleanValue()) {
                MinecraftServer srv = SolidusIntegration.getServer();
                if (this.engine != null && srv != null) {
                    srv.execute(() -> this.engine.getAuditLogger().logRollback(adminUuid, adminName, targetUuid, targetName, "PLAYER", restoreEntry.id));
                }
                return "Rolled back 1 action for player " + targetName + ": balance restored to " + before
                + " (state before audit #" + restoreEntry.id
                + ", the earliest affected action in the newest " + ROLLBACK_PLAYER_WINDOW + " rows for this player).";
            }
            return "Failed to roll back player " + targetName + ": setBalance returned false.";
        }).exceptionally(ex -> "Failed to roll back player " + targetName + ": " + ((Throwable)ex).getMessage())
        .whenComplete((ignored, throwable) -> endRollback());
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
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().getRecentAuditLogs(ROLLBACK_TIMEFRAME_WINDOW);
        LinkedHashMap<UUID, AuditDatabase.AuditEntry> restorePerPlayer =
            RollbackEngine.selectTimeframeRestorePoints(entries, fromTimestamp, toTimestamp);
        if (restorePerPlayer.isEmpty()) {
            // A-3 fix: disclose the bounded search window instead of implying
            // nothing happened anywhere.
            return CompletableFuture.completedFuture("No actions to roll back in the specified timeframe"
                + " (searched the newest " + ROLLBACK_TIMEFRAME_WINDOW + " audit rows)."
                + " If the incident is older than that window, narrow the timeframe or export the audit log.");
        }
        if (!tryBeginRollback()) {
            return CompletableFuture.completedFuture(BUSY_MESSAGE);
        }
        ArrayList<CompletableFuture<Boolean>> rollbackFutures = new ArrayList<CompletableFuture<Boolean>>();
        for (Map.Entry<UUID, AuditDatabase.AuditEntry> restore : restorePerPlayer.entrySet()) {
            UUID targetUuid = restore.getKey();
            AuditDatabase.AuditEntry entry = restore.getValue();
            double before;
            try {
                before = Double.parseDouble(entry.beforeValue);
            }
            catch (NumberFormatException e) {
                continue;
            }
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
        int[] rolledBack = new int[]{0};
        return CompletableFuture.allOf(rollbackFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
            for (CompletableFuture<Boolean> future : rollbackFutures) {
                if (!Boolean.TRUE.equals(future.join())) continue;
                rolledBack[0] = rolledBack[0] + 1;
            }
            return "Rolled back " + rolledBack[0] + " unique players in timeframe (balance restored to the state before their earliest affected action;"
                + " searched the newest " + ROLLBACK_TIMEFRAME_WINDOW + " audit rows).";
        }).whenComplete((ignored, throwable) -> endRollback());
    }

    /** Shared selection logic for timeframe rollbacks and their dry-run preview. */
    static LinkedHashMap<UUID, AuditDatabase.AuditEntry> selectTimeframeRestorePoints(
            List<AuditDatabase.AuditEntry> entries, long fromTimestamp, long toTimestamp) {
        LinkedHashMap<UUID, AuditDatabase.AuditEntry> restorePerPlayer = new LinkedHashMap<UUID, AuditDatabase.AuditEntry>();
        if (entries == null) {
            return restorePerPlayer;
        }
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
        return restorePerPlayer;
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

    /**
     * Dry-run preview for {@link #rollbackPlayer}: shows the computed restore
     * point and the balance delta WITHOUT touching any balance. Read-only, so
     * it does not take the rollback lock.
     */
    public CompletableFuture<String> dryRunRollbackPlayer(UUID targetUuid, String targetName, long fromTimestamp) {
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().searchByTarget(targetUuid, ROLLBACK_PLAYER_WINDOW);
        AuditDatabase.AuditEntry restoreEntry = RollbackEngine.selectRestoreEntry(entries, fromTimestamp, null);
        if (restoreEntry == null) {
            return CompletableFuture.completedFuture(
                "[DRY RUN] No reversible actions found for " + targetName + " in the window - nothing would change.");
        }
        double restoreTo;
        try {
            restoreTo = Double.parseDouble(restoreEntry.beforeValue);
        }
        catch (NumberFormatException e) {
            return CompletableFuture.completedFuture(
                "[DRY RUN] Earliest affected action #" + restoreEntry.id + " has an invalid before-value - the rollback would be skipped.");
        }
        return SolidusIntegration.getBalance(targetUuid, targetName).thenApply(current -> {
            StringBuilder sb = new StringBuilder();
            sb.append("[DRY RUN] Player rollback preview for ").append(targetName).append(":\n");
            sb.append("  Restore point: audit #").append(restoreEntry.id).append("\n");
            sb.append("  Would restore balance to: ").append(String.format("%.2f", restoreTo)).append("\n");
            if (current != null && Double.isFinite(current)) {
                sb.append("  Current balance: ").append(String.format("%.2f", current)).append("\n");
                sb.append("  Change if executed: ").append(String.format("%+.2f", restoreTo - current)).append("\n");
            } else {
                sb.append("  Current balance: unavailable\n");
            }
            sb.append("  No changes were applied.");
            return sb.toString();
        }).exceptionally(ex ->
            "[DRY RUN] Player rollback preview for " + targetName + ": would restore balance to "
                + String.format("%.2f", restoreTo)
                + " (current balance unavailable: " + ex + "). No changes were applied.");
    }

    /**
     * Dry-run preview for {@link #rollbackTimeframe}: lists each affected
     * player with the balance they would be restored to and the delta, WITHOUT
     * touching any balance. Read-only, so it does not take the rollback lock.
     */
    public CompletableFuture<String> dryRunRollbackTimeframe(long fromTimestamp, long toTimestamp) {
        List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().getRecentAuditLogs(ROLLBACK_TIMEFRAME_WINDOW);
        LinkedHashMap<UUID, AuditDatabase.AuditEntry> restorePerPlayer =
            RollbackEngine.selectTimeframeRestorePoints(entries, fromTimestamp, toTimestamp);
        if (restorePerPlayer.isEmpty()) {
            return CompletableFuture.completedFuture(
                "[DRY RUN] No reversible actions in the timeframe - nothing would change.");
        }
        ArrayList<CompletableFuture<String>> lines = new ArrayList<CompletableFuture<String>>();
        int[] index = new int[]{0};
        for (Map.Entry<UUID, AuditDatabase.AuditEntry> restore : restorePerPlayer.entrySet()) {
            UUID targetUuid = restore.getKey();
            AuditDatabase.AuditEntry entry = restore.getValue();
            final int playerNumber = ++index[0];
            double restoreTo;
            try {
                restoreTo = Double.parseDouble(entry.beforeValue);
            }
            catch (NumberFormatException e) {
                continue;
            }
            lines.add(SolidusIntegration.getBalance(targetUuid, entry.targetName).thenApply(current -> {
                String line = "  " + playerNumber + ". " + entry.targetName
                    + ": restore to " + String.format("%.2f", restoreTo);
                if (current != null && Double.isFinite(current)) {
                    line += " (current " + String.format("%.2f", current)
                        + ", change " + String.format("%+.2f", restoreTo - current) + ")";
                }
                return line;
            }).exceptionally(ex ->
                "  " + playerNumber + ". " + entry.targetName
                    + ": restore to " + String.format("%.2f", restoreTo) + " (current balance unavailable)"));
        }
        return CompletableFuture.allOf(lines.toArray(new CompletableFuture[0])).thenApply(v -> {
            StringBuilder sb = new StringBuilder();
            sb.append("[DRY RUN] Timeframe rollback preview: ").append(lines.size())
                .append(" player(s) would be restored. No changes were applied.\n");
            for (CompletableFuture<String> line : lines) {
                sb.append(line.join()).append("\n");
            }
            return sb.toString().trim();
        });
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
