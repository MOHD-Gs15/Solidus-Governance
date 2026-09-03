package com.solidus.governance.events;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.events.EconomyEvent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EventDatabase {
    private static final String DB_NAME = "events.db";
    private final String databaseUrl;
    /** Recreated on re-initialize (live restore calls shutdown() then initialize()). */
    private volatile ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public EventDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve(DB_NAME).toString();
        this.executor = newExecutor();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Events-DB");
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
            try (Statement stmt = this.connection.createStatement();){
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
            }
            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("    CREATE TABLE IF NOT EXISTS economy_events (\n        id TEXT PRIMARY KEY NOT NULL,\n        name TEXT NOT NULL,\n        type TEXT NOT NULL,\n        modifier REAL NOT NULL,\n        start_time INTEGER NOT NULL,\n        end_time INTEGER NOT NULL,\n        creator_uuid TEXT NOT NULL,\n        creator_name TEXT NOT NULL,\n        original_values TEXT NOT NULL,\n        active INTEGER NOT NULL DEFAULT 1\n    )\n");
                stmt.execute("    CREATE INDEX IF NOT EXISTS idx_events_active\n    ON economy_events(active)\n");
                stmt.execute("    CREATE INDEX IF NOT EXISTS idx_events_end_time\n    ON economy_events(end_time)\n");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Events database initialized.");
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize events database!", (Throwable)e);
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
                SolidusGovernanceMod.LOGGER.error("Failed to close events database", (Throwable)e);
            }
        }
    }

    public void saveEvent(EconomyEvent event) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO economy_events\n    (id, name, type, modifier, start_time, end_time,\n     creator_uuid, creator_name, original_values, active)\n    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, event.getId());
                    ps.setString(2, event.getName());
                    ps.setString(3, event.getType());
                    ps.setDouble(4, event.getModifier());
                    ps.setLong(5, event.getStartTime());
                    ps.setLong(6, event.getEndTime());
                    ps.setString(7, event.getCreatorUuid());
                    ps.setString(8, event.getCreatorName());
                    ps.setString(9, EventDatabase.mapToJson(event.getOriginalValues()));
                    ps.setInt(10, event.isActive() ? 1 : 0);
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save event {}", (Object)event.getId(), (Object)e);
            }
        });
    }

    public void updateEvent(EconomyEvent event) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    UPDATE economy_events\n    SET active = ?, original_values = ?\n    WHERE id = ?\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setInt(1, event.isActive() ? 1 : 0);
                    ps.setString(2, EventDatabase.mapToJson(event.getOriginalValues()));
                    ps.setString(3, event.getId());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to update event {}", (Object)event.getId(), (Object)e);
            }
        });
    }

    public List<EconomyEvent> loadActiveEvents() {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<EconomyEvent> events = new ArrayList<EconomyEvent>();
        try {
            String sql = "SELECT * FROM economy_events WHERE active = 1 ORDER BY end_time ASC";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    events.add(this.eventFromRs(rs));
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load active events", (Throwable)e);
        }
        return events;
    }

    public List<EconomyEvent> loadAllEvents() {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<EconomyEvent> events = new ArrayList<EconomyEvent>();
        try {
            String sql = "SELECT * FROM economy_events ORDER BY start_time DESC LIMIT 100";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    events.add(this.eventFromRs(rs));
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load all events", (Throwable)e);
        }
        return events;
    }

    public void deleteOldEvents(int daysOld) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                long cutoff = System.currentTimeMillis() - (long)daysOld * 86400000L;
                String sql = "DELETE FROM economy_events WHERE active = 0 AND end_time < ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setLong(1, cutoff);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        SolidusGovernanceMod.LOGGER.info("Cleaned up {} old economy events older than {} days.", (Object)deleted, (Object)daysOld);
                    }
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to cleanup old events", (Throwable)e);
            }
        });
    }

    private EconomyEvent eventFromRs(ResultSet rs) throws SQLException {
        String originalValuesJson = rs.getString("original_values");
        Map<String, String> originalValues = EventDatabase.jsonToMap(originalValuesJson);
        return new EconomyEvent(rs.getString("id"), rs.getString("name"), rs.getString("type"), rs.getDouble("modifier"), rs.getLong("start_time"), rs.getLong("end_time"), rs.getString("creator_uuid"), rs.getString("creator_name"), originalValues, rs.getInt("active") == 1);
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"");
            sb.append(EventDatabase.escapeJsonString(entry.getKey()));
            sb.append("\":\"");
            sb.append(EventDatabase.escapeJsonString(entry.getValue()));
            sb.append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static Map<String, String> jsonToMap(String json) {
        HashMap<String, String> map = new HashMap<String, String>();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return map;
        }
        try {
            String content = json.trim();
            if (content.startsWith("{")) {
                content = content.substring(1);
            }
            if (content.endsWith("}")) {
                content = content.substring(0, content.length() - 1);
            }
            if (content.isBlank()) {
                return map;
            }
            List<String> pairs = EventDatabase.splitJsonPairs(content);
            for (String pair : pairs) {
                int colonIdx = pair.indexOf(58);
                if (colonIdx < 0) continue;
                String key = pair.substring(0, colonIdx).trim();
                String value = pair.substring(colonIdx + 1).trim();
                key = EventDatabase.stripQuotes(key);
                value = EventDatabase.stripQuotes(value);
                key = EventDatabase.unescapeJsonString(key);
                value = EventDatabase.unescapeJsonString(value);
                map.put(key, value);
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.warn("Failed to parse original_values JSON: {}", (Object)json, (Object)e);
        }
        return map;
    }

    private static List<String> splitJsonPairs(String content) {
        ArrayList<String> pairs = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); ++i) {
            char c = content.charAt(i);
            if (c == '\"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                boolean bl = inQuotes = !inQuotes;
            }
            if (c == ',' && !inQuotes) {
                pairs.add(current.toString().trim());
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            pairs.add(current.toString().trim());
        }
        return pairs;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String escapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\t", "\t").replace("\\r", "\r").replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
