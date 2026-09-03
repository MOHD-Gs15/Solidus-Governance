package com.solidus.governance.rules;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.rules.AutomationRule;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RuleDatabase {
    private static final String DB_NAME = "rules.db";
    private final String databaseUrl;
    /** Recreated on re-initialize (live restore calls shutdown() then initialize()). */
    private volatile ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean initialized = false;

    public RuleDatabase(Path configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir.resolve(DB_NAME).toString();
        this.executor = newExecutor();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Rules-DB");
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
                stmt.execute("    CREATE TABLE IF NOT EXISTS automation_rules (\n        name TEXT PRIMARY KEY NOT NULL,\n        enabled INTEGER NOT NULL,\n        conditions TEXT NOT NULL,\n        actions TEXT NOT NULL,\n        cooldown_millis INTEGER NOT NULL,\n        last_triggered INTEGER NOT NULL\n    )\n");
            }
            this.initialized = true;
            SolidusGovernanceMod.LOGGER.info("Rules database initialized.");
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to initialize rules database!", (Throwable)e);
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
                SolidusGovernanceMod.LOGGER.error("Failed to close rules database", (Throwable)e);
            }
        }
    }

    public void saveRule(AutomationRule rule) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "    INSERT OR REPLACE INTO automation_rules\n    (name, enabled, conditions, actions, cooldown_millis, last_triggered)\n    VALUES (?, ?, ?, ?, ?, ?)\n";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, rule.getName());
                    ps.setInt(2, rule.isEnabled() ? 1 : 0);
                    ps.setString(3, RuleDatabase.conditionsToJson(rule.getConditions()));
                    ps.setString(4, RuleDatabase.actionsToJson(rule.getActions()));
                    ps.setLong(5, rule.getCooldownMillis());
                    ps.setLong(6, rule.getLastTriggered());
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to save rule {}", (Object)rule.getName(), (Object)e);
            }
        });
    }

    public void deleteRule(String name) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "DELETE FROM automation_rules WHERE name = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to delete rule {}", (Object)name, (Object)e);
            }
        });
    }

    public void updateLastTriggered(String name, long timestamp) {
        if (!this.initialized) {
            return;
        }
        this.executor.submit(() -> {
            try {
                String sql = "UPDATE automation_rules SET last_triggered = ?, enabled = ? WHERE name = ?";
                String sqlUpdate = "UPDATE automation_rules SET last_triggered = ? WHERE name = ?";
                try (PreparedStatement ps = this.connection.prepareStatement(sqlUpdate);){
                    ps.setLong(1, timestamp);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
            }
            catch (SQLException e) {
                SolidusGovernanceMod.LOGGER.error("Failed to update last_triggered for rule {}", (Object)name, (Object)e);
            }
        });
    }

    public List<AutomationRule> loadAllRules() {
        if (!this.initialized) {
            return List.of();
        }
        ArrayList<AutomationRule> rules = new ArrayList<AutomationRule>();
        try {
            String sql = "SELECT * FROM automation_rules ORDER BY name ASC";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    try {
                        AutomationRule rule = this.ruleFromRs(rs);
                        rules.add(rule);
                    }
                    catch (Exception e) {
                        SolidusGovernanceMod.LOGGER.error("Failed to parse rule from database row", (Throwable)e);
                    }
                }
            }
        }
        catch (SQLException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to load rules", (Throwable)e);
        }
        return rules;
    }

    private AutomationRule ruleFromRs(ResultSet rs) throws SQLException {
        String name = rs.getString("name");
        boolean enabled = rs.getInt("enabled") == 1;
        String conditionsJson = rs.getString("conditions");
        String actionsJson = rs.getString("actions");
        long cooldownMillis = rs.getLong("cooldown_millis");
        long lastTriggered = rs.getLong("last_triggered");
        List<AutomationRule.RuleCondition> conditions = RuleDatabase.jsonToConditions(conditionsJson);
        List<AutomationRule.RuleAction> actions = RuleDatabase.jsonToActions(actionsJson);
        return new AutomationRule(name, enabled, conditions, actions, cooldownMillis, lastTriggered);
    }

    private static String conditionsToJson(List<AutomationRule.RuleCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < conditions.size(); ++i) {
            AutomationRule.RuleCondition c = conditions.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"type\":\"");
            sb.append(RuleDatabase.escapeJson(c.type()));
            sb.append("\",\"value\":");
            sb.append(c.value());
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String actionsToJson(List<AutomationRule.RuleAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < actions.size(); ++i) {
            AutomationRule.RuleAction a = actions.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"type\":\"");
            sb.append(RuleDatabase.escapeJson(a.type()));
            sb.append("\",\"key\":\"");
            sb.append(RuleDatabase.escapeJson(a.key() != null ? a.key() : ""));
            sb.append("\",\"value\":\"");
            sb.append(RuleDatabase.escapeJson(a.value() != null ? a.value() : ""));
            sb.append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<AutomationRule.RuleCondition> jsonToConditions(String json) {
        ArrayList<AutomationRule.RuleCondition> conditions = new ArrayList<AutomationRule.RuleCondition>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return conditions;
        }
        try {
            List<JsonProperty[]> objects = RuleDatabase.parseJsonArray(json);
            for (JsonProperty[] obj : objects) {
                String type = RuleDatabase.getPropertyValue(obj, "type");
                double value = RuleDatabase.getPropertyValueDouble(obj, "value", 0.0);
                if (type == null) continue;
                conditions.add(new AutomationRule.RuleCondition(type, value));
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.warn("Failed to parse conditions JSON: {}", (Object)json, (Object)e);
        }
        return conditions;
    }

    private static List<AutomationRule.RuleAction> jsonToActions(String json) {
        ArrayList<AutomationRule.RuleAction> actions = new ArrayList<AutomationRule.RuleAction>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return actions;
        }
        try {
            List<JsonProperty[]> objects = RuleDatabase.parseJsonArray(json);
            for (JsonProperty[] obj : objects) {
                String type = RuleDatabase.getPropertyValue(obj, "type");
                String key = RuleDatabase.getPropertyValue(obj, "key");
                String value = RuleDatabase.getPropertyValue(obj, "value");
                if (type == null) continue;
                actions.add(new AutomationRule.RuleAction(type, key, value));
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.warn("Failed to parse actions JSON: {}", (Object)json, (Object)e);
        }
        return actions;
    }

    private static List<JsonProperty[]> parseJsonArray(String json) {
        ArrayList<JsonProperty[]> results = new ArrayList<JsonProperty[]>();
        String content = json.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            return results;
        }
        if ((content = content.substring(1, content.length() - 1).trim()).isEmpty()) {
            return results;
        }
        List<String> objects = RuleDatabase.splitTopLevel(content, '{', '}');
        for (String obj : objects) {
            String objContent = obj.trim();
            if (objContent.startsWith("{")) {
                objContent = objContent.substring(1);
            }
            if (objContent.endsWith("}")) {
                objContent = objContent.substring(0, objContent.length() - 1);
            }
            if ((objContent = objContent.trim()).isEmpty()) continue;
            ArrayList<JsonProperty> props = new ArrayList<JsonProperty>();
            List<String> pairs = RuleDatabase.splitTopLevel(objContent, '\u0000', '\u0000');
            for (String pair : pairs) {
                int colonIdx = pair.indexOf(58);
                if (colonIdx < 0) continue;
                String key = RuleDatabase.stripQuotes(pair.substring(0, colonIdx).trim());
                String val = pair.substring(colonIdx + 1).trim();
                if (val.startsWith("\"")) {
                    val = RuleDatabase.stripQuotes(val);
                }
                props.add(new JsonProperty(key, val));
            }
            results.add(props.toArray(new JsonProperty[0]));
        }
        return results;
    }

    private static List<String> splitTopLevel(String content, char openBrace, char closeBrace) {
        String part;
        ArrayList<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuotes = false;
        boolean splittingObjects = openBrace != '\u0000';
        for (int i = 0; i < content.length(); ++i) {
            String part2;
            char c = content.charAt(i);
            if (c == '\"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                boolean bl = inQuotes = !inQuotes;
            }
            if (!inQuotes) {
                if (splittingObjects && c == openBrace && ++depth == 1) {
                    current = new StringBuilder();
                }
                if (splittingObjects && c == closeBrace) {
                    --depth;
                }
            }
            current.append(c);
            if (inQuotes || depth != 0) continue;
            if (splittingObjects) {
                if (c != closeBrace) continue;
                part2 = current.toString().trim();
                if (!part2.isEmpty()) {
                    parts.add(part2);
                }
                current = new StringBuilder();
                continue;
            }
            if (c != ',') continue;
            part2 = current.substring(0, current.length() - 1).trim();
            if (!part2.isEmpty()) {
                parts.add(part2);
            }
            current = new StringBuilder();
        }
        if (!splittingObjects && !(part = current.toString().trim()).isEmpty()) {
            parts.add(part);
        }
        return parts;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String getPropertyValue(JsonProperty[] props, String key) {
        for (JsonProperty prop : props) {
            if (!key.equals(prop.key())) continue;
            return prop.value();
        }
        return null;
    }

    private static double getPropertyValueDouble(JsonProperty[] props, String key, double defaultVal) {
        String val = RuleDatabase.getPropertyValue(props, key);
        if (val == null) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(val);
        }
        catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private record JsonProperty(String key, String value) {
    }
}
