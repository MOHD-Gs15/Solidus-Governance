package com.solidus.governance.limits;

import com.solidus.governance.SolidusGovernanceMod;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LimitsDatabase {
    private static final String DB_NAME = "limits.db";
    private final String databaseUrl;
    private final ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public LimitsDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve(DB_NAME).toString();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Limits-DB");
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
                stmt.execute("    CREATE TABLE IF NOT EXISTS daily_limits (\n        player_uuid TEXT NOT NULL,\n        date TEXT NOT NULL,\n        transfer_total REAL NOT NULL DEFAULT 0,\n        auction_count INTEGER NOT NULL DEFAULT 0,\n        PRIMARY KEY (player_uuid, date)\n    )\n");
                stmt.execute("    CREATE INDEX IF NOT EXISTS idx_daily_limits_date\n    ON daily_limits(date)\n");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Limits database initialized.");
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize limits database!", (Throwable)e);
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
                SolidusGovernanceMod.LOGGER.error("Failed to close limits database", (Throwable)e);
            }
        }
    }

    public DailyUsage loadDailyUsage(UUID playerUuid, String date) {
        if (!this.initialized) {
            return null;
        }
        try {
            String sql = "    SELECT transfer_total, auction_count\n    FROM daily_limits\n    WHERE player_uuid = ? AND date = ?\n";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                ps.setString(2, date);
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return null;
                    DailyUsage dailyUsage = new DailyUsage(playerUuid, date, rs.getDouble("transfer_total"), rs.getInt("auction_count"));
                    return dailyUsage;
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load daily usage for {}", (Object)playerUuid, (Object)e);
        }
        return null;
    }

    public void saveDailyUsage(UUID playerUuid, String date, double transferTotal, int auctionCount) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO daily_limits (player_uuid, date, transfer_total, auction_count)\n    VALUES (?, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, date);
                    ps.setDouble(3, transferTotal);
                    ps.setInt(4, auctionCount);
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save daily usage for {}", (Object)playerUuid, (Object)e);
            }
        });
    }

    public void cleanupOldEntries() {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String cutoffDate = LocalDate.now().minusDays(7L).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String sql = "DELETE FROM daily_limits WHERE date < ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, cutoffDate);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        SolidusGovernanceMod.LOGGER.info("Cleaned up {} old daily_limits entries.", (Object)deleted);
                    }
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to cleanup old daily_limits entries", (Throwable)e);
            }
        });
    }

    public void resetPlayerDailyUsage(UUID playerUuid) {
        if (!this.initialized) {
            return;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        this.executor.submit(() -> {
            try {
                String sql = "DELETE FROM daily_limits WHERE player_uuid = ? AND date = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, today);
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to reset daily usage for {}", (Object)playerUuid, (Object)e);
            }
        });
    }

    public ExecutorService getExecutor() {
        return this.executor;
    }

    public record DailyUsage(UUID playerUuid, String date, double transferTotal, int auctionCount) {
    }
}
