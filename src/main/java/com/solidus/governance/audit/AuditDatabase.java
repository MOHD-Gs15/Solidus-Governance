package com.solidus.governance.audit;

import com.solidus.governance.SolidusGovernanceMod;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AuditDatabase {
    private static final String DB_NAME = "governance.db";

    /**
     * Maximum number of rows a single audit export may return. Exports are
     * rare admin operations; the cap (newest rows win) keeps them bounded.
     */
    public static final int MAX_EXPORT_ROWS = 200_000;

    private final String databaseUrl;
    private final Path configDir;
    private final ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public AuditDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve(DB_NAME).toString();
        this.configDir = configDir;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Governance-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() {
        try {
            this.connection = DriverManager.getConnection(this.databaseUrl);
            try (Statement stmt = this.connection.createStatement();){
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
            }
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("    CREATE TABLE IF NOT EXISTS audit_log (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        timestamp INTEGER NOT NULL,\n        admin_uuid TEXT NOT NULL,\n        admin_name TEXT NOT NULL,\n        action TEXT NOT NULL,\n        category TEXT NOT NULL,\n        target_uuid TEXT,\n        target_name TEXT,\n        before_value TEXT,\n        after_value TEXT,\n        details TEXT,\n        rollback_of INTEGER,\n        CONSTRAINT fk_rollback FOREIGN KEY (rollback_of) REFERENCES audit_log(id)\n    )\n");
                stmt.execute("    CREATE TABLE IF NOT EXISTS rollback_chain (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        rollback_audit_id INTEGER NOT NULL,\n        original_audit_id INTEGER NOT NULL,\n        timestamp INTEGER NOT NULL,\n        admin_uuid TEXT NOT NULL,\n        status TEXT NOT NULL DEFAULT 'PENDING',\n        CONSTRAINT fk_rollback_audit FOREIGN KEY (rollback_audit_id) REFERENCES audit_log(id),\n        CONSTRAINT fk_original_audit FOREIGN KEY (original_audit_id) REFERENCES audit_log(id)\n    )\n");
                stmt.execute("    CREATE TABLE IF NOT EXISTS account_freezes (\n        uuid TEXT PRIMARY KEY NOT NULL,\n        reason TEXT NOT NULL,\n        frozen_by TEXT NOT NULL,\n        frozen_at INTEGER NOT NULL,\n        expires_at INTEGER,\n        active INTEGER NOT NULL DEFAULT 1\n    )\n");
                stmt.execute("    CREATE TABLE IF NOT EXISTS suspicious_accounts (\n        uuid TEXT PRIMARY KEY NOT NULL,\n        reason TEXT NOT NULL,\n        marked_at INTEGER NOT NULL\n    )\n");
                stmt.execute("    CREATE TABLE IF NOT EXISTS trading_lock_state (\n        id INTEGER PRIMARY KEY CHECK (id = 1),\n        locked INTEGER NOT NULL DEFAULT 0,\n        reason TEXT,\n        updated_at INTEGER NOT NULL\n    )\n");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_admin ON audit_log(admin_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_log(target_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_category ON audit_log(category)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action)");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Governance audit database initialized.");
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize governance database!", (Throwable)e);
        }
    }

    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (this.connection != null) {
            try {
                this.connection.close();
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to close governance database", (Throwable)e);
            }
        }
    }

    public void logAudit(AuditEntry entry) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT INTO audit_log (timestamp, admin_uuid, admin_name, action, category,\n        target_uuid, target_name, before_value, after_value, details, rollback_of)\n    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setLong(1, entry.timestamp);
                    ps.setString(2, entry.adminUuid);
                    ps.setString(3, entry.adminName);
                    ps.setString(4, entry.action);
                    ps.setString(5, entry.category);
                    ps.setString(6, entry.targetUuid);
                    ps.setString(7, entry.targetName);
                    ps.setString(8, entry.beforeValue);
                    ps.setString(9, entry.afterValue);
                    ps.setString(10, entry.details);
                    if (entry.rollbackOf > 0) {
                        ps.setInt(11, entry.rollbackOf);
                    } else {
                        ps.setNull(11, 4);
                    }
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to log audit entry", (Throwable)e);
            }
        });
    }

    public List<AuditEntry> getRecentAuditLogs(int limit) {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AuditEntry> entries = new ArrayList<AuditEntry>();
        try {
            String sql = "SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(this.auditEntryFromRs(rs));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to query audit logs", (Throwable)e);
        }
        return entries;
    }

    /**
     * Returns audit entries within a time window, newest first. Used by
     * {@code /governance audit export csv [days]} to serialize the trail
     * for external bookkeeping. Capped at {@link #MAX_EXPORT_ROWS}
     * (newest rows win) so an export can never exhaust server memory.
     *
     * @param sinceEpochMs Inclusive lower bound on row timestamps (millis)
     * @return matching entries, newest first (empty if not initialized)
     */
    public List<AuditEntry> getAuditLogsSince(long sinceEpochMs) {
        return this.getAuditLogsSince(sinceEpochMs, MAX_EXPORT_ROWS);
    }

    /** Windowed read with an explicit row cap. */
    public List<AuditEntry> getAuditLogsSince(long sinceEpochMs, int maxRows) {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AuditEntry> entries = new ArrayList<AuditEntry>();
        try {
            String sql = "SELECT * FROM audit_log WHERE timestamp >= ? ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setLong(1, sinceEpochMs);
                ps.setInt(2, Math.max(0, maxRows));
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(this.auditEntryFromRs(rs));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to query audit logs since " + sinceEpochMs, (Throwable)e);
        }
        return entries;
    }

    /**
     * Directory where CSV exports are written:
     * {@code <config dir>/solidus-governance/exports/} (created lazily by
     * the exporter, not here).
     */
    public Path getExportsDir() {
        return this.configDir.resolve("exports");
    }

    public List<AuditEntry> searchByTarget(UUID targetUuid, int limit) {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AuditEntry> entries = new ArrayList<AuditEntry>();
        try {
            String sql = "SELECT * FROM audit_log WHERE target_uuid = ? ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(this.auditEntryFromRs(rs));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to search audit logs", (Throwable)e);
        }
        return entries;
    }

    public List<AuditEntry> searchByAdmin(UUID adminUuid, int limit) {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AuditEntry> entries = new ArrayList<AuditEntry>();
        try {
            String sql = "SELECT * FROM audit_log WHERE admin_uuid = ? ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, adminUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(this.auditEntryFromRs(rs));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to search audit logs by admin", (Throwable)e);
        }
        return entries;
    }

    public List<AuditEntry> searchByCategory(String category, int limit) {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AuditEntry> entries = new ArrayList<AuditEntry>();
        try {
            String sql = "SELECT * FROM audit_log WHERE category = ? ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, category.toUpperCase());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(this.auditEntryFromRs(rs));
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to search audit logs by category", (Throwable)e);
        }
        return entries;
    }

    public AuditEntry getAuditEntryById(int id) {
        if (!this.initialized) {
            return null;
        }
        try {
            String sql = "SELECT * FROM audit_log WHERE id = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return null;
                    AuditEntry auditEntry = this.auditEntryFromRs(rs);
                    return auditEntry;
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get audit entry {}", (Object)id, (Object)e);
        }
        return null;
    }

    public void createRollbackChain(int rollbackAuditId, int originalAuditId, UUID adminUuid) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT INTO rollback_chain (rollback_audit_id, original_audit_id, timestamp, admin_uuid, status)\n    VALUES (?, ?, ?, ?, 'COMPLETED')\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setInt(1, rollbackAuditId);
                    ps.setInt(2, originalAuditId);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, adminUuid.toString());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to create rollback chain", (Throwable)e);
            }
        });
    }

    public void recordFreeze(UUID uuid, String reason, UUID frozenBy, Long expiresAt) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO account_freezes (uuid, reason, frozen_by, frozen_at, expires_at, active)\n    VALUES (?, ?, ?, ?, ?, 1)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, uuid.toString());
                    ps.setString(2, reason);
                    ps.setString(3, frozenBy.toString());
                    ps.setLong(4, System.currentTimeMillis());
                    if (expiresAt != null) {
                        ps.setLong(5, expiresAt);
                    } else {
                        ps.setNull(5, 4);
                    }
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to record account freeze", (Throwable)e);
            }
        });
    }

    public void unfreezeAccount(UUID uuid) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "UPDATE account_freezes SET active = 0 WHERE uuid = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to unfreeze account", (Throwable)e);
            }
        });
    }

    public boolean isAccountFrozen(UUID uuid) {
        if (!this.initialized) {
            return false;
        }
        try {
            String sql = "SELECT active, expires_at FROM account_freezes WHERE uuid = ? ORDER BY frozen_at DESC LIMIT 1";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return false;
                    boolean active = rs.getInt("active") == 1;
                    long expiresAt = rs.getLong("expires_at");
                    if (rs.wasNull()) {
                        boolean bl = active;
                        return bl;
                    }
                    if (System.currentTimeMillis() > expiresAt) {
                        boolean bl = false;
                        return bl;
                    }
                    boolean bl = active;
                    return bl;
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to check freeze status", (Throwable)e);
        }
        return false;
    }

    public List<FreezeRecord> getActiveFreezes() {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<FreezeRecord> freezes = new ArrayList<FreezeRecord>();
        try {
            String sql = "SELECT * FROM account_freezes WHERE active = 1";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    freezes.add(new FreezeRecord(UUID.fromString(rs.getString("uuid")), rs.getString("reason"), UUID.fromString(rs.getString("frozen_by")), rs.getLong("frozen_at"), rs.getLong("expires_at")));
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get active freezes", (Throwable)e);
        }
        return freezes;
    }

    public void cleanupOldEntries(int retentionDays) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                long cutoff = System.currentTimeMillis() - (long)retentionDays * 86400000L;
                String sql = "DELETE FROM audit_log WHERE timestamp < ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setLong(1, cutoff);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        SolidusGovernanceMod.LOGGER.info("Cleaned up {} audit entries older than {} days.", (Object)deleted, (Object)retentionDays);
                    }
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to cleanup old audit entries", (Throwable)e);
            }
        });
    }

    private AuditEntry auditEntryFromRs(ResultSet rs) throws SQLException {
        return new AuditEntry(rs.getInt("id"), rs.getLong("timestamp"), rs.getString("admin_uuid"), rs.getString("admin_name"), rs.getString("action"), rs.getString("category"), rs.getString("target_uuid"), rs.getString("target_name"), rs.getString("before_value"), rs.getString("after_value"), rs.getString("details"), rs.getInt("rollback_of"));
    }

    public ExecutorService getExecutor() {
        return this.executor;
    }

    public void saveSuspiciousAccount(UUID uuid, String reason) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO suspicious_accounts (uuid, reason, marked_at)\n    VALUES (?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, uuid.toString());
                    ps.setString(2, reason);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save suspicious account", (Throwable)e);
            }
        });
    }

    public void removeSuspiciousAccount(UUID uuid) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "DELETE FROM suspicious_accounts WHERE uuid = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to remove suspicious account", (Throwable)e);
            }
        });
    }

    public Map<UUID, String> loadSuspiciousAccounts() {
        if (!this.initialized) {
            return Map.of();
        }
        HashMap<UUID, String> result = new HashMap<UUID, String>();
        try {
            String sql = "SELECT uuid, reason FROM suspicious_accounts";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String reason = rs.getString("reason");
                    result.put(uuid, reason);
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load suspicious accounts", (Throwable)e);
        }
        return result;
    }

    public void saveTradingLockState(boolean locked, String reason) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO trading_lock_state (id, locked, reason, updated_at)\n    VALUES (1, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setInt(1, locked ? 1 : 0);
                    ps.setString(2, reason);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save trading lock state", (Throwable)e);
            }
        });
    }

    public TradingLockState loadTradingLockState() {
        if (!this.initialized) {
            return null;
        }
        try {
            String sql = "SELECT locked, reason FROM trading_lock_state WHERE id = 1";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                if (!rs.next()) return null;
                boolean locked = rs.getInt("locked") == 1;
                String reason = rs.getString("reason");
                TradingLockState tradingLockState = new TradingLockState(locked, reason);
                return tradingLockState;
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load trading lock state", (Throwable)e);
        }
        return null;
    }

    public PlayerStats getPlayerStats(UUID playerUuid, long fromTimestamp) {
        if (!this.initialized) {
            return null;
        }
        double totalIncome = 0.0;
        double totalExpenses = 0.0;
        double taxPaid = 0.0;
        int transactionCount = 0;
        try {
            String sql = "    SELECT action, category, before_value, after_value, details\n    FROM audit_log\n    WHERE target_uuid = ? AND timestamp >= ?\n    ORDER BY timestamp ASC\n";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                ps.setLong(2, fromTimestamp);
                try (ResultSet rs = ps.executeQuery();){
                    block16: while (rs.next()) {
                        String details;
                        String category;
                        block25: {
                            String action = rs.getString("action");
                            category = rs.getString("category");
                            String beforeValue = rs.getString("before_value");
                            String afterValue = rs.getString("after_value");
                            details = rs.getString("details");
                            ++transactionCount;
                            if (beforeValue != null && afterValue != null) {
                                try {
                                    double before = Double.parseDouble(beforeValue);
                                    double after = Double.parseDouble(afterValue);
                                    double change = after - before;
                                    if (change > 0.0) {
                                        totalIncome += change;
                                        break block25;
                                    }
                                    if (change < 0.0) {
                                        totalExpenses += Math.abs(change);
                                    }
                                }
                                catch (NumberFormatException before) {
                                    // empty catch block
                                }
                            }
                        }
                        if (!"TAXATION".equals(category) || details == null) continue;
                        try {
                            String[] parts;
                            String[] stringArray = parts = details.split(";");
                            int n = stringArray.length;
                            int n2 = 0;
                            while (true) {
                                if (n2 >= n) continue block16;
                                String part = stringArray[n2];
                                if ((part = part.trim()).startsWith("tax_amount=")) {
                                    taxPaid += Double.parseDouble(part.substring("tax_amount=".length()));
                                }
                                ++n2;
                            }
                        }
                        catch (NumberFormatException numberFormatException) {
                        }
                    }
                    return new PlayerStats(totalIncome, totalExpenses, taxPaid, transactionCount);
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get player stats for {}", (Object)playerUuid, (Object)e);
            return null;
        }
    }

    public Long getFirstAuditTimestamp(UUID playerUuid) {
        if (!this.initialized) {
            return null;
        }
        try {
            String sql = "SELECT MIN(timestamp) AS first_ts FROM audit_log WHERE target_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return null;
                    long ts = rs.getLong("first_ts");
                    if (rs.wasNull()) return null;
                    Long l = ts;
                    return l;
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get first audit timestamp for {}", (Object)playerUuid, (Object)e);
        }
        return null;
    }

    public static class AuditEntry {
        public final int id;
        public final long timestamp;
        public final String adminUuid;
        public final String adminName;
        public final String action;
        public final String category;
        public final String targetUuid;
        public final String targetName;
        public final String beforeValue;
        public final String afterValue;
        public final String details;
        public final int rollbackOf;

        public AuditEntry(int id, long timestamp, String adminUuid, String adminName, String action, String category, String targetUuid, String targetName, String beforeValue, String afterValue, String details, int rollbackOf) {
            this.id = id;
            this.timestamp = timestamp;
            this.adminUuid = adminUuid;
            this.adminName = adminName;
            this.action = action;
            this.category = category;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.beforeValue = beforeValue;
            this.afterValue = afterValue;
            this.details = details;
            this.rollbackOf = rollbackOf;
        }

        public static AuditEntry create(long timestamp, String adminUuid, String adminName, String action, String category, String targetUuid, String targetName, String beforeValue, String afterValue, String details, int rollbackOf) {
            return new AuditEntry(0, timestamp, adminUuid, adminName, action, category, targetUuid, targetName, beforeValue, afterValue, details, rollbackOf);
        }
    }

    public static class FreezeRecord {
        public final UUID uuid;
        public final String reason;
        public final UUID frozenBy;
        public final long frozenAt;
        public final long expiresAt;

        public FreezeRecord(UUID uuid, String reason, UUID frozenBy, long frozenAt, long expiresAt) {
            this.uuid = uuid;
            this.reason = reason;
            this.frozenBy = frozenBy;
            this.frozenAt = frozenAt;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return this.expiresAt > 0L && System.currentTimeMillis() > this.expiresAt;
        }

        public boolean isPermanent() {
            return this.expiresAt <= 0L;
        }
    }

    public record TradingLockState(boolean locked, String reason) {
    }

    public record PlayerStats(double totalIncome, double totalExpenses, double taxPaid, int transactionCount) {
    }
}
