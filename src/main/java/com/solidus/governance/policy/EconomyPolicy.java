package com.solidus.governance.policy;

import com.solidus.governance.GovernanceConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class EconomyPolicy {
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");
    public static final String AUTOSAVE_PREFIX = "_autosave_";
    public static final String[] CAPTURED_PREFIXES = new String[]{"taxation.", "automation.", "limits.", "recovery.", "intervention.", "audit."};
    public static final String DISCORD_PREFIX = "discord.";
    private static final String[] EXCLUDED_PREFIXES = new String[]{"events.", "policies."};
    private final String name;
    private final String displayName;
    private final String description;
    private final Map<String, String> configValues;
    private final long createdAt;
    private final String createdBy;

    public EconomyPolicy(String name, String displayName, String description, Map<String, String> configValues, long createdAt, String createdBy) {
        this.name = name;
        this.displayName = displayName;
        this.description = description != null ? description : "";
        this.configValues = Collections.unmodifiableMap(new LinkedHashMap<String, String>(configValues));
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public String getName() {
        return this.name;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getDescription() {
        return this.description;
    }

    public Map<String, String> getConfigValues() {
        return this.configValues;
    }

    public long getCreatedAt() {
        return this.createdAt;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith(AUTOSAVE_PREFIX)) {
            return true;
        }
        return NAME_PATTERN.matcher(name).matches();
    }

    public boolean isAutosave() {
        return this.name.startsWith(AUTOSAVE_PREFIX);
    }

    public boolean isAutosaveExpired() {
        if (!this.isAutosave()) {
            return false;
        }
        long sevenDaysMillis = 604800000L;
        return System.currentTimeMillis() - this.createdAt > sevenDaysMillis;
    }

    public Map<String, String> diff(GovernanceConfig currentConfig) {
        LinkedHashMap<String, String> diff = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : this.configValues.entrySet()) {
            String currentValue = currentConfig.getString(entry.getKey(), null);
            if (currentValue == null || currentValue.equals(entry.getValue())) continue;
            diff.put(entry.getKey(), entry.getValue());
        }
        return diff;
    }

    public static boolean shouldCaptureKey(String key, boolean includeDiscord) {
        if (key == null) {
            return false;
        }
        for (String excluded : EXCLUDED_PREFIXES) {
            if (!key.startsWith(excluded)) continue;
            return false;
        }
        if (key.contains(".db") || key.contains(".database") || key.contains(".path")) {
            return false;
        }
        if (key.startsWith(DISCORD_PREFIX)) {
            return includeDiscord;
        }
        for (String prefix : CAPTURED_PREFIXES) {
            if (!key.startsWith(prefix)) continue;
            return true;
        }
        return false;
    }

    public String toString() {
        return "EconomyPolicy{name='" + this.name + "', displayName='" + this.displayName + "', keys=" + this.configValues.size() + ", createdBy='" + this.createdBy + "'}";
    }
}
