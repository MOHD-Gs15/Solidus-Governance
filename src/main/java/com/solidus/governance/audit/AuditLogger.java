package com.solidus.governance.audit;

import com.solidus.governance.audit.AuditDatabase;
import java.util.UUID;

public class AuditLogger {
    private final AuditDatabase database;

    public AuditLogger(AuditDatabase database) {
        this.database = database;
    }

    /** Audit entry for a backup run (success or partial). */
    public void logBackup(String action, String details) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), "SYSTEM", "BackupManager", action, "RECOVERY", null, null, null, null, details, 0);
        this.database.logAudit(entry);
    }

    /** Audit entry for a restore operation (preview-less confirmed swap). */
    public void logRestore(UUID adminUuid, String adminName, String details) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName != null ? adminName : "BackupManager", "RESTORE", "RECOVERY", null, null, null, null, details, 0);
        this.database.logAudit(entry);
    }

    public void logBalanceChange(UUID adminUuid, String adminName, UUID targetUuid, String targetName, String action, double beforeBalance, double afterBalance, double changeAmount) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, action, "INTERVENTION", targetUuid != null ? targetUuid.toString() : null, targetName, String.valueOf(beforeBalance), String.valueOf(afterBalance), "change=" + changeAmount, 0);
        this.database.logAudit(entry);
    }

    public void logFreeze(UUID adminUuid, String adminName, UUID targetUuid, String targetName, String reason, long duration) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "FREEZE_ACCOUNT", "INTERVENTION", targetUuid != null ? targetUuid.toString() : null, targetName, "UNFROZEN", "FROZEN", "reason=" + reason + ";duration=" + duration, 0);
        this.database.logAudit(entry);
    }

    public void logUnfreeze(UUID adminUuid, String adminName, UUID targetUuid, String targetName) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "UNFREEZE_ACCOUNT", "INTERVENTION", targetUuid != null ? targetUuid.toString() : null, targetName, "FROZEN", "UNFROZEN", null, 0);
        this.database.logAudit(entry);
    }

    public void logBulkOperation(UUID adminUuid, String adminName, String operation, int affectedCount, String details) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "BULK_" + operation, "INTERVENTION", null, null, null, null, "affected=" + affectedCount + ";" + details, 0);
        this.database.logAudit(entry);
    }

    public void logTaxCollection(UUID targetUuid, String targetName, String taxType, double amount, double balanceBefore, double balanceAfter) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), "SYSTEM", "System", "TAX_" + taxType.toUpperCase(), "TAXATION", targetUuid != null ? targetUuid.toString() : null, targetName, String.valueOf(balanceBefore), String.valueOf(balanceAfter), "tax_amount=" + amount, 0);
        this.database.logAudit(entry);
    }

    public void logWealthDecay(UUID targetUuid, String targetName, double decayAmount, double balanceBefore, double balanceAfter) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), "SYSTEM", "System", "WEALTH_DECAY", "TAXATION", targetUuid != null ? targetUuid.toString() : null, targetName, String.valueOf(balanceBefore), String.valueOf(balanceAfter), "decay_amount=" + decayAmount, 0);
        this.database.logAudit(entry);
    }

    public void logTreasuryOperation(UUID adminUuid, String adminName, String operation, double amount) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "TREASURY_" + operation.toUpperCase(), "TAXATION", null, null, null, null, "amount=" + amount, 0);
        this.database.logAudit(entry);
    }

    public void logSnapshot(UUID adminUuid, String adminName, String snapshotName, int playerCount) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "CREATE_SNAPSHOT", "RECOVERY", null, null, null, null, "snapshot=" + snapshotName + ";players=" + playerCount, 0);
        this.database.logAudit(entry);
    }

    public void logRollback(UUID adminUuid, String adminName, UUID targetUuid, String targetName, String rollbackType, int originalAuditId) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "ROLLBACK_" + rollbackType.toUpperCase(), "RECOVERY", targetUuid != null ? targetUuid.toString() : null, targetName, null, null, "original_audit_id=" + originalAuditId, originalAuditId);
        this.database.logAudit(entry);
    }

    public void logAutomation(String action, String details) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), "SYSTEM", "Governance Automator", action, "AUTOMATION", null, null, null, null, details, 0);
        this.database.logAudit(entry);
    }

    public void logConfigChange(UUID adminUuid, String adminName, String key, String beforeValue, String afterValue) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "CONFIG_CHANGE", "CONFIG", null, null, beforeValue, afterValue, "key=" + key, 0);
        this.database.logAudit(entry);
    }

    public void logLimitExceeded(UUID playerUuid, String action, String details) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), "SYSTEM", "Transaction Limits", action, "LIMITS", playerUuid != null ? playerUuid.toString() : null, null, null, null, details, 0);
        this.database.logAudit(entry);
    }

    public void logLimitConfigChange(UUID adminUuid, String adminName, String limitType, String beforeValue, String afterValue) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "LIMIT_CONFIG_CHANGE", "LIMITS", null, null, beforeValue, afterValue, "limit_type=" + limitType, 0);
        this.database.logAudit(entry);
    }

    public void logLimitReset(UUID adminUuid, String adminName, UUID targetUuid) {
        AuditDatabase.AuditEntry entry = AuditDatabase.AuditEntry.create(System.currentTimeMillis(), adminUuid != null ? adminUuid.toString() : "SYSTEM", adminName, "LIMIT_RESET", "LIMITS", targetUuid != null ? targetUuid.toString() : null, null, null, null, "Manual limit reset by admin", 0);
        this.database.logAudit(entry);
    }
}
