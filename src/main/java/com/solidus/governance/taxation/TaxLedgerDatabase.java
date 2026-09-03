package com.solidus.governance.taxation;

import com.solidus.governance.SolidusGovernanceMod;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TaxLedgerDatabase - persistent ledger of tax debts that could not be
 * collected at transaction time.
 *
 * <p>Before this ledger, a failed tax collection (insufficient funds at the
 * moment of the afterTransfer hook, transient database error, balance lookup
 * failure) was silently dropped forever - players could dodge transfer and
 * auction taxes by keeping their balance near zero at settlement time. Failed
 * collections are now parked here and retried by the periodic sweeper in
 * {@link TaxEngine#processPendingTaxes()} until they succeed or exhaust
 * {@link #MAX_ATTEMPTS}.</p>
 *
 * <p>Thread model mirrors {@code LimitsDatabase}: reads run on the calling
 * thread, writes are serialized on a dedicated single-thread executor. The
 * ledger database is separate from limits.db so a corrupt ledger can never
 * take down enforcement state.</p>
 */
public class TaxLedgerDatabase {
    private static final String DB_NAME = "tax_ledger.db";

    /** A pending (uncollected) tax debt. */
    public record PendingTax(long id, UUID playerUuid, String playerName,
                             String taxType, double amount, int attempts, long createdAt) {}

    private final String databaseUrl;
    /** Recreated on re-initialize (live restore calls shutdown() then initialize()). */
    private volatile ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public TaxLedgerDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve(DB_NAME).toString();
        this.executor = newExecutor();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-TaxLedger-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() {
        try {
            // A-1 fix: recreate the executor if a previous shutdown() terminated it.
            ExecutorService current = this.executor;
            if (current == null || current.isShutdown()) {
                this.executor = newExecutor();
            }
            this.connection = DriverManager.getConnection(this.databaseUrl);
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
            }
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_taxes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        tax_type TEXT NOT NULL,
                        amount REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_taxes_player ON pending_taxes(player_uuid)");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Tax ledger database initialized.");
        } catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize tax ledger database!", e);
        }
    }

    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to close tax ledger database", e);
            }
        }
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    /** Parks a tax debt for later retry. Fire-and-forget; never throws. */
    public void enqueuePendingTax(UUID playerUuid, String playerName, String taxType,
                                  double amount, String reason) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            String sql = """
                INSERT INTO pending_taxes (player_uuid, player_name, tax_type, amount, created_at, attempts, last_error)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """;
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, playerName != null ? playerName : "Unknown");
                ps.setString(3, taxType);
                ps.setDouble(4, amount);
                ps.setLong(5, System.currentTimeMillis());
                ps.setString(6, reason != null ? reason : "unknown");
                ps.executeUpdate();
                SolidusGovernanceMod.LOGGER.warn(
                    "Tax debt parked for retry: player={}, type={}, amount={} ({})",
                    playerUuid, taxType, amount, reason);
            } catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to enqueue pending tax for {}", playerUuid, e);
            }
        });
    }

    /**
     * Loads the oldest pending taxes that still have retries left.
     * Runs on the calling thread (small table, local SQLite).
     */
    public List<PendingTax> loadDuePendingTaxes(int limit, int maxAttempts) {
        List<PendingTax> result = new ArrayList<>();
        if (!this.initialized) {
            return result;
        }
        String sql = """
            SELECT id, player_uuid, player_name, tax_type, amount, attempts, created_at
            FROM pending_taxes
            WHERE attempts < ?
            ORDER BY created_at ASC
            LIMIT ?
            """;
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setInt(1, maxAttempts);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        result.add(new PendingTax(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getString("tax_type"),
                            rs.getDouble("amount"),
                            rs.getInt("attempts"),
                            rs.getLong("created_at")));
                    } catch (IllegalArgumentException badUuid) {
                        SolidusGovernanceMod.LOGGER.warn("Skipping pending tax #{} with invalid UUID", rs.getLong("id"));
                    }
                }
            }
        } catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load pending taxes", e);
        }
        return result;
    }

    /** Marks a pending tax as collected (removes it from the ledger). */
    public void markCollected(long id) {
        markCollectedNow(id);
    }

    /**
     * Synchronous completion mark (B-6 fix, audit round 3). The async
     * fire-and-forget DELETE left a crash window where the money had already
     * moved but the debt row survived, so the next sweep collected the SAME
     * tax twice. The caller invokes this from a completion callback that
     * already runs OFF the server thread, so a local sub-millisecond DELETE
     * is safe here. On failure the row is force-dropped via the attempts
     * budget instead of being left re-collectable: the money is already
     * collected, so keeping the row would double-charge the player.
     */
    public void markCollectedNow(long id) {
        if (!this.initialized) {
            return;
        }
        try (PreparedStatement ps = this.connection.prepareStatement("DELETE FROM pending_taxes WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error(
                "Failed to mark pending tax #{} collected - force-dropping the row to prevent a double collection", id, e);
            // Conservative direction: the money has moved; a surviving row
            // would be re-collected. Drop it via the attempts budget instead.
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE pending_taxes SET attempts = ?, last_error = ? WHERE id = ?")) {
                ps.setInt(1, MAX_ATTEMPTS);
                ps.setString(2, "markCollected failed: " + e.getMessage());
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e2) {
                SolidusGovernanceMod.LOGGER.error(
                    "CRITICAL: could not drop pending tax #{} after a failed completion mark - MANUAL REVIEW needed to avoid a double collection", id, e2);
            }
        }
    }

    /** Records a failed retry attempt. Drops the entry once attempts are exhausted. */
    public void markAttempt(long id, String error) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                try (PreparedStatement ps = this.connection.prepareStatement(
                        "UPDATE pending_taxes SET attempts = attempts + 1, last_error = ? WHERE id = ?")) {
                    ps.setString(1, error != null ? error : "unknown");
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = this.connection.prepareStatement(
                        "DELETE FROM pending_taxes WHERE id = ? AND attempts >= ?")) {
                    ps.setLong(1, id);
                    ps.setInt(2, MAX_ATTEMPTS);
                    int dropped = ps.executeUpdate();
                    if (dropped > 0) {
                        SolidusGovernanceMod.LOGGER.error(
                            "Pending tax #{} dropped after {} failed attempts (last error: {})",
                            id, MAX_ATTEMPTS, error);
                    }
                }
            } catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to record attempt for pending tax #{}", id, e);
            }
        });
    }

    public int getPendingCount() {
        if (!this.initialized) {
            return 0;
        }
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pending_taxes")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to count pending taxes", e);
        }
        return 0;
    }

    /** Retry budget before a tax debt is abandoned (with an ERROR log). */
    public static final int MAX_ATTEMPTS = 5;
}
