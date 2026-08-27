package com.solidus.governance.policy;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.discord.WebhookManager;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.policy.EconomyPolicy;
import com.solidus.governance.policy.PolicyDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PolicyManager {
    private final Map<String, EconomyPolicy> policies = new ConcurrentHashMap<String, EconomyPolicy>();
    private final PolicyDatabase database;
    private final GovernanceEngine engine;

    public PolicyManager(PolicyDatabase database, GovernanceEngine engine) {
        this.database = database;
        this.engine = engine;
    }

    public CompletableFuture<EconomyPolicy> savePolicy(String name, String displayName, String description, UUID creatorUuid, String creatorName) {
        return CompletableFuture.supplyAsync(() -> {
            if (!this.engine.isPremiumEnabled()) {
                SolidusGovernanceMod.LOGGER.warn("Policy save rejected: premium license required.");
                return null;
            }
            if (!EconomyPolicy.isValidName(name)) {
                SolidusGovernanceMod.LOGGER.warn("Policy save rejected: invalid name '{}'. Must be lowercase alphanumeric + hyphens, 2-32 chars.", (Object)name);
                return null;
            }
            if (!this.engine.getConfig().getBool("policies.enabled", true)) {
                SolidusGovernanceMod.LOGGER.warn("Policy save rejected: policies system is disabled in config.");
                return null;
            }
            GovernanceConfig config = this.engine.getConfig();
            boolean includeDiscord = config.getBool("policies.include-discord", false);
            Map<String, String> capturedValues = this.captureConfigValues(config, includeDiscord);
            EconomyPolicy policy = new EconomyPolicy(name, displayName != null ? displayName : name, description, capturedValues, System.currentTimeMillis(), creatorName);
            this.policies.put(name, policy);
            this.database.savePolicy(policy);
            this.engine.getAuditLogger().logConfigChange(creatorUuid, creatorName, "POLICY_SAVE:" + name, "", capturedValues.size() + " keys captured");
            SolidusGovernanceMod.LOGGER.info("Policy '{}' saved by {} ({} config keys captured).", new Object[]{name, creatorName, capturedValues.size()});
            return policy;
        });
    }

    public CompletableFuture<Boolean> loadPolicy(String name, UUID adminUuid, String adminName) {
        return CompletableFuture.supplyAsync(() -> {
            if (!this.engine.isPremiumEnabled()) {
                SolidusGovernanceMod.LOGGER.warn("Policy load rejected: premium license required.");
                return false;
            }
            EconomyPolicy policy = this.policies.get(name);
            if (policy == null) {
                SolidusGovernanceMod.LOGGER.warn("Policy load rejected: policy '{}' not found.", (Object)name);
                return false;
            }
            if (!this.engine.getConfig().getBool("policies.enabled", true)) {
                SolidusGovernanceMod.LOGGER.warn("Policy load rejected: policies system is disabled in config.");
                return false;
            }
            String autosaveName = "_autosave_before_" + name + "_" + System.currentTimeMillis();
            boolean includeDiscord = this.engine.getConfig().getBool("policies.include-discord", false);
            Map<String, String> currentValues = this.captureConfigValues(this.engine.getConfig(), includeDiscord);
            EconomyPolicy autosave = new EconomyPolicy(autosaveName, "Auto-save before loading '" + policy.getDisplayName() + "'", "Automatic snapshot created before policy load", currentValues, System.currentTimeMillis(), adminName);
            this.policies.put(autosaveName, autosave);
            this.database.savePolicy(autosave);
            SolidusGovernanceMod.LOGGER.info("Auto-saved current config as '{}' before loading policy '{}'.", (Object)autosaveName, (Object)name);
            GovernanceConfig config = this.engine.getConfig();
            LinkedHashMap<String, String> beforeValues = new LinkedHashMap<String, String>();
            LinkedHashMap<String, String> afterValues = new LinkedHashMap<String, String>();
            int appliedCount = 0;
            for (Map.Entry<String, String> entry : policy.getConfigValues().entrySet()) {
                String currentValue = config.getString(entry.getKey(), null);
                if (currentValue == null || currentValue.equals(entry.getValue())) continue;
                beforeValues.put(entry.getKey(), currentValue);
                afterValues.put(entry.getKey(), entry.getValue());
                config.set(entry.getKey(), entry.getValue());
                ++appliedCount;
            }
            this.engine.getAuditLogger().logConfigChange(adminUuid, adminName, "POLICY_LOAD:" + name, beforeValues.size() + " keys changed", afterValues.size() + " keys applied");
            SolidusGovernanceMod.LOGGER.info("Policy '{}' loaded by {}. {} config keys applied.", new Object[]{name, adminName, appliedCount});
            WebhookManager webhookManager = this.engine.getWebhookManager();
            if (webhookManager != null && this.engine.isPremiumEnabled()) {
                String description = "Policy **" + policy.getDisplayName() + "** loaded by " + adminName + ". " + appliedCount + " config keys changed.";
                webhookManager.sendAlert("AUTOMATION", "Economy Policy Loaded", description);
            }
            return true;
        });
    }

    public CompletableFuture<Map<String, String[]>> previewPolicy(String name) {
        return CompletableFuture.supplyAsync(() -> {
            if (!this.engine.isPremiumEnabled()) {
                SolidusGovernanceMod.LOGGER.warn("Policy preview rejected: premium license required.");
                return null;
            }
            EconomyPolicy policy = this.policies.get(name);
            if (policy == null) {
                return null;
            }
            GovernanceConfig config = this.engine.getConfig();
            LinkedHashMap<String, String[]> diff = new LinkedHashMap<String, String[]>();
            for (Map.Entry<String, String> entry : policy.getConfigValues().entrySet()) {
                String currentValue = config.getString(entry.getKey(), null);
                if (currentValue == null || currentValue.equals(entry.getValue())) continue;
                diff.put(entry.getKey(), new String[]{currentValue, entry.getValue()});
            }
            return diff;
        });
    }

    public CompletableFuture<Boolean> deletePolicy(String name) {
        return CompletableFuture.supplyAsync(() -> {
            if (!this.engine.isPremiumEnabled()) {
                SolidusGovernanceMod.LOGGER.warn("Policy delete rejected: premium license required.");
                return false;
            }
            EconomyPolicy removed = this.policies.remove(name);
            if (removed == null) {
                return false;
            }
            this.database.deletePolicy(name);
            SolidusGovernanceMod.LOGGER.info("Policy '{}' deleted.", (Object)name);
            return true;
        });
    }

    public List<EconomyPolicy> listPolicies() {
        return this.policies.values().stream().sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt())).toList();
    }

    public EconomyPolicy getPolicy(String name) {
        return this.policies.get(name);
    }

    public void loadFromDatabase() {
        this.policies.clear();
        List<EconomyPolicy> loaded = this.database.loadAllPolicies();
        for (EconomyPolicy policy : loaded) {
            this.policies.put(policy.getName(), policy);
        }
        int cleaned = this.database.cleanupExpiredAutosaves();
        if (cleaned > 0) {
            this.policies.entrySet().removeIf(entry -> ((EconomyPolicy)entry.getValue()).isAutosave() && ((EconomyPolicy)entry.getValue()).isAutosaveExpired());
            SolidusGovernanceMod.LOGGER.info("Cleaned up {} expired auto-save policies.", (Object)cleaned);
        }
        SolidusGovernanceMod.LOGGER.info("Loaded {} policies from database ({} auto-saves).", (Object)this.policies.size(), (Object)this.policies.values().stream().filter(EconomyPolicy::isAutosave).count());
    }

    private Map<String, String> captureConfigValues(GovernanceConfig config, boolean includeDiscord) {
        LinkedHashMap<String, String> captured = new LinkedHashMap<String, String>();
        for (String key : config.getAllKeys()) {
            if (!EconomyPolicy.shouldCaptureKey(key, includeDiscord)) continue;
            captured.put(key, config.getString(key, ""));
        }
        return captured;
    }
}
