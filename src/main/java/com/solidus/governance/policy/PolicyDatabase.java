package com.solidus.governance.policy;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.policy.EconomyPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PolicyDatabase {
    private final String databaseUrl;
    private final ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public PolicyDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve("governance.db").toString();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Governance-PolicyDB");
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
                stmt.execute("    CREATE TABLE IF NOT EXISTS economy_policies (\n        name TEXT PRIMARY KEY,\n        display_name TEXT,\n        description TEXT,\n        config_values TEXT,\n        created_at INTEGER,\n        created_by TEXT\n    )\n");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_policies_created_at ON economy_policies(created_at)");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Policy database initialized.");
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize policy database!", (Throwable)e);
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
                SolidusGovernanceMod.LOGGER.error("Failed to close policy database", (Throwable)e);
            }
        }
    }

    public void savePolicy(EconomyPolicy policy) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO economy_policies (name, display_name, description, config_values, created_at, created_by)\n    VALUES (?, ?, ?, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, policy.getName());
                    ps.setString(2, policy.getDisplayName());
                    ps.setString(3, policy.getDescription());
                    ps.setString(4, PolicyDatabase.serializeConfigValues(policy.getConfigValues()));
                    ps.setLong(5, policy.getCreatedAt());
                    ps.setString(6, policy.getCreatedBy());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save policy '{}'", (Object)policy.getName(), (Object)e);
            }
        });
    }

    public void deletePolicy(String name) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "DELETE FROM economy_policies WHERE name = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, name);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        SolidusGovernanceMod.LOGGER.info("Policy '{}' deleted from database.", (Object)name);
                    }
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to delete policy '{}'", (Object)name, (Object)e);
            }
        });
    }

    public List<EconomyPolicy> loadAllPolicies() {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<EconomyPolicy> policies = new ArrayList<EconomyPolicy>();
        try {
            String sql = "SELECT * FROM economy_policies ORDER BY created_at DESC";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    policies.add(this.policyFromRs(rs));
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load policies from database", (Throwable)e);
        }
        return policies;
    }

    public boolean policyExists(String name) {
        if (!this.initialized) {
            return false;
        }
        try {
            String sql = "SELECT 1 FROM economy_policies WHERE name = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to check if policy '{}' exists", (Object)name, (Object)e);
            return false;
        }
    }

    public int cleanupExpiredAutosaves() {
        if (!this.initialized) {
            return 0;
        }
        long sevenDaysAgo = System.currentTimeMillis() - 604800000L;
        String sql = "DELETE FROM economy_policies WHERE name LIKE ? AND created_at < ?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setString(1, "_autosave_%");
            ps.setLong(2, sevenDaysAgo);
            return ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to cleanup expired autosave policies", (Throwable)e);
            return 0;
        }
    }

    private EconomyPolicy policyFromRs(ResultSet rs) throws SQLException {
        String name = rs.getString("name");
        String displayName = rs.getString("display_name");
        String description = rs.getString("description");
        String configValuesJson = rs.getString("config_values");
        long createdAt = rs.getLong("created_at");
        String createdBy = rs.getString("created_by");
        Map<String, String> configValues = PolicyDatabase.deserializeConfigValues(configValuesJson);
        return new EconomyPolicy(name, displayName, description, configValues, createdAt, createdBy);
    }

    static String serializeConfigValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"");
            sb.append(PolicyDatabase.escapeJson(entry.getKey()));
            sb.append("\":\"");
            sb.append(PolicyDatabase.escapeJson(entry.getValue()));
            sb.append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    static Map<String, String> deserializeConfigValues(String json) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        if (json == null || json.isBlank() || json.equals("{}")) {
            return result;
        }
        String content = json.trim();
        if (content.startsWith("{")) {
            content = content.substring(1);
        }
        if (content.endsWith("}")) {
            content = content.substring(0, content.length() - 1);
        }
        for (int i = 0; i < content.length(); ++i) {
            while (i < content.length() && content.charAt(i) != '\"') {
                ++i;
            }
            if (i >= content.length()) break;
            ++i;
            StringBuilder key = new StringBuilder();
            while (i < content.length() && content.charAt(i) != '\"') {
                if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                    key.append(PolicyDatabase.unescapeChar(content.charAt(++i)));
                } else {
                    key.append(content.charAt(i));
                }
                ++i;
            }
            ++i;
            while (i < content.length() && content.charAt(i) != ':') {
                ++i;
            }
            ++i;
            while (i < content.length() && content.charAt(i) != '\"') {
                ++i;
            }
            if (i >= content.length()) break;
            ++i;
            StringBuilder value = new StringBuilder();
            while (i < content.length() && content.charAt(i) != '\"') {
                if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                    value.append(PolicyDatabase.unescapeChar(content.charAt(++i)));
                } else {
                    value.append(content.charAt(i));
                }
                ++i;
            }
            result.put(key.toString(), value.toString());
        }
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static char unescapeChar(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '\\' -> '\\';
            case '\"' -> '\"';
            default -> c;
        };
    }
}
