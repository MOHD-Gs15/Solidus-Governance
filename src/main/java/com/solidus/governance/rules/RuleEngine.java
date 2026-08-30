package com.solidus.governance.rules;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.rules.AutomationRule;
import com.solidus.governance.rules.RuleContext;
import com.solidus.governance.rules.RuleDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public class RuleEngine {
    private final GovernanceEngine engine;
    private final RuleDatabase database;
    private final Map<String, AutomationRule> rules = new ConcurrentHashMap<String, AutomationRule>();

    public RuleEngine(GovernanceEngine engine, RuleDatabase database) {
        this.engine = engine;
        this.database = database;
    }

    public CompletableFuture<Boolean> addRule(AutomationRule rule) {
        return CompletableFuture.supplyAsync(() -> {
            if (!RuleEngine.isValidRuleName(rule.getName())) {
                SolidusGovernanceMod.LOGGER.warn("Invalid rule name: '{}'. Must be 2-32 chars, lowercase alphanumeric + hyphens.", (Object)rule.getName());
                return false;
            }
            if (this.rules.containsKey(rule.getName())) {
                SolidusGovernanceMod.LOGGER.warn("Rule '{}' already exists.", (Object)rule.getName());
                return false;
            }
            this.rules.put(rule.getName(), rule);
            this.database.saveRule(rule);
            SolidusGovernanceMod.LOGGER.info("Rule '{}' added (enabled={}, {} conditions, {} actions, cooldown={}ms)", new Object[]{rule.getName(), rule.isEnabled(), rule.getConditions().size(), rule.getActions().size(), rule.getCooldownMillis()});
            return true;
        });
    }

    public CompletableFuture<Boolean> removeRule(String name) {
        return CompletableFuture.supplyAsync(() -> {
            AutomationRule removed = this.rules.remove(name);
            if (removed == null) {
                SolidusGovernanceMod.LOGGER.warn("Rule '{}' not found for removal.", (Object)name);
                return false;
            }
            this.database.deleteRule(name);
            SolidusGovernanceMod.LOGGER.info("Rule '{}' removed.", (Object)name);
            return true;
        });
    }

    public CompletableFuture<Boolean> toggleRule(String name, boolean enabled) {
        return CompletableFuture.supplyAsync(() -> {
            AutomationRule rule = this.rules.get(name);
            if (rule == null) {
                SolidusGovernanceMod.LOGGER.warn("Rule '{}' not found for toggle.", (Object)name);
                return false;
            }
            rule.setEnabled(enabled);
            this.database.saveRule(rule);
            SolidusGovernanceMod.LOGGER.info("Rule '{}' {}.", (Object)name, (Object)(enabled ? "enabled" : "disabled"));
            return true;
        });
    }

    public List<AutomationRule> listRules() {
        return Collections.unmodifiableList(new ArrayList<AutomationRule>(this.rules.values()));
    }

    public AutomationRule getRule(String name) {
        return this.rules.get(name);
    }

    public void saveRule(String name) {
        AutomationRule rule = this.rules.get(name);
        if (rule != null) {
            this.database.saveRule(rule);
        }
    }

    public int getRuleCount() {
        return this.rules.size();
    }

    public int getEnabledRuleCount() {
        return (int)this.rules.values().stream().filter(AutomationRule::isEnabled).count();
    }

    public void loadFromDatabase() {
        List<AutomationRule> loaded = this.database.loadAllRules();
        this.rules.clear();
        for (AutomationRule rule : loaded) {
            this.rules.put(rule.getName(), rule);
        }
        SolidusGovernanceMod.LOGGER.info("Loaded {} automation rules from database.", (Object)loaded.size());
    }

    public CompletableFuture<Void> evaluateAll() {
        return ((CompletableFuture)this.computeContext().thenAccept(ctx -> {
            for (AutomationRule rule : this.rules.values()) {
                if (!rule.isEnabled() || !rule.isOffCooldown() || !rule.evaluate((RuleContext)ctx)) continue;
                SolidusGovernanceMod.LOGGER.info("Rule '{}' triggered! Executing {} actions...", (Object)rule.getName(), (Object)rule.getActions().size());
                this.executeActions(rule);
                rule.setLastTriggered(System.currentTimeMillis());
                this.database.updateLastTriggered(rule.getName(), rule.getLastTriggered());
                this.engine.getAuditLogger().logAutomation("RULE_TRIGGERED", "rule=" + rule.getName() + ";actions=" + rule.getActions().size() + ";conditions_met=" + rule.getConditions().size());
                this.sendDiscordAlert("AUTOMATION", "Rule Triggered: " + rule.getName(), "Rule '" + rule.getName() + "' triggered with " + rule.getActions().size() + " actions. Cooldown: " + rule.getRemainingCooldownString());
            }
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("Error during rule evaluation", ex);
            return null;
        });
    }

    private CompletableFuture<RuleContext> computeContext() {
        return SolidusIntegration.getTopBalances(100000).thenApply(balances -> {
            double totalMoneySupply = 0.0;
            for (SolidusIntegration.BalanceEntry entry : balances) {
                totalMoneySupply += entry.balance();
            }
            double avgBalance = balances.isEmpty() ? 0.0 : totalMoneySupply / (double)balances.size();
            double giniCoefficient = this.computeGini((List<SolidusIntegration.BalanceEntry>)balances);
            int onlinePlayerCount = 0;
            MinecraftServer srv = SolidusIntegration.getServer();
            if (srv != null) {
                onlinePlayerCount = srv.getPlayerList().getPlayerCount();
            }
            double transactionVolume24h = this.computeTransactionVolume24h();
            double inflationRate = this.estimateInflationRate(avgBalance);
            return new RuleContext(avgBalance, totalMoneySupply, onlinePlayerCount, giniCoefficient, transactionVolume24h, inflationRate);
        });
    }

    private double computeGini(List<SolidusIntegration.BalanceEntry> balances) {
        if (balances.size() <= 1) {
            return 0.0;
        }
        double[] sorted = balances.stream().mapToDouble(SolidusIntegration.BalanceEntry::balance).sorted().toArray();
        int n = sorted.length;
        double sumValues = 0.0;
        double weightedSum = 0.0;
        for (int i = 0; i < n; ++i) {
            sumValues += sorted[i];
            weightedSum += (double)(i + 1) * sorted[i];
        }
        if (sumValues <= 0.0) {
            return 0.0;
        }
        double gini = 2.0 * weightedSum / ((double)n * sumValues) - ((double)n + 1.0) / (double)n;
        return Math.max(0.0, Math.min(1.0, gini));
    }

    private double computeTransactionVolume24h() {
        try {
            long twentyFourHoursAgo = System.currentTimeMillis() - 86400000L;
            List<AuditDatabase.AuditEntry> entries = this.engine.getAuditDatabase().searchByCategory("TAXATION", 1000);
            double volume = 0.0;
            for (AuditDatabase.AuditEntry entry : entries) {
                String[] parts;
                if (entry.timestamp < twentyFourHoursAgo || entry.details == null) continue;
                for (String part : parts = entry.details.split(";")) {
                    if (!(part = part.trim()).startsWith("tax_amount=")) continue;
                    try {
                        volume += Double.parseDouble(part.substring("tax_amount=".length()));
                    }
                    catch (NumberFormatException numberFormatException) {
                        // empty catch block
                    }
                }
            }
            double taxRate = this.engine.getConfig().getDouble("taxation.auction.rate", 0.05);
            if (taxRate > 0.0) {
                return volume / taxRate;
            }
            return volume;
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("Failed to compute transaction volume", (Throwable)e);
            return 0.0;
        }
    }

    private double estimateInflationRate(double avgBalance) {
        double threshold = this.engine.getConfig().getDouble("automation.anti-inflation.threshold", 15.0);
        if (threshold <= 0.0) {
            return 0.0;
        }
        if (avgBalance <= threshold) {
            return 0.0;
        }
        double ratio = avgBalance / threshold;
        return Math.min((ratio - 1.0) / ratio, 1.0);
    }

    private void executeActions(AutomationRule rule) {
        MinecraftServer srv = SolidusIntegration.getServer();
        for (AutomationRule.RuleAction action : rule.getActions()) {
            try {
                this.executeAction(action, srv);
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.error("Failed to execute action '{}' for rule '{}'", new Object[]{action.type(), rule.getName(), e});
            }
        }
    }

    private void executeAction(AutomationRule.RuleAction action, MinecraftServer srv) {
        switch (action.type()) {
            case "set_config": {
                if (srv != null) {
                    srv.execute(() -> {
                        String beforeValue = this.engine.getConfig().getString(action.key(), "");
                        this.engine.getConfig().set(action.key(), action.value());
                        this.engine.getAuditLogger().logConfigChange(null, "RuleEngine", action.key(), beforeValue, action.value());
                    });
                }
                SolidusGovernanceMod.LOGGER.info("  Action: set_config {} = {}", (Object)action.key(), (Object)action.value());
                break;
            }
            case "enable_feature": {
                if (srv != null) {
                    srv.execute(() -> {
                        String beforeValue = this.engine.getConfig().getString(action.key(), "");
                        this.engine.getConfig().set(action.key(), "true");
                        this.engine.getAuditLogger().logConfigChange(null, "RuleEngine", action.key(), beforeValue, "true");
                    });
                }
                SolidusGovernanceMod.LOGGER.info("  Action: enable_feature {}", (Object)action.key());
                break;
            }
            case "disable_feature": {
                if (srv != null) {
                    srv.execute(() -> {
                        String beforeValue = this.engine.getConfig().getString(action.key(), "");
                        this.engine.getConfig().set(action.key(), "false");
                        this.engine.getAuditLogger().logConfigChange(null, "RuleEngine", action.key(), beforeValue, "false");
                    });
                }
                SolidusGovernanceMod.LOGGER.info("  Action: disable_feature {}", (Object)action.key());
                break;
            }
            case "activate_lockdown": {
                if (srv != null) {
                    String reason = action.key() != null ? action.key() : "Automated lockdown by rule engine";
                    srv.execute(() -> this.engine.getAutomator().activateLockdown(null, "RuleEngine", reason));
                }
                SolidusGovernanceMod.LOGGER.info("  Action: activate_lockdown ({})", (Object)action.key());
                break;
            }
            case "deactivate_lockdown": {
                if (srv != null) {
                    srv.execute(() -> this.engine.getAutomator().deactivateLockdown(null, "RuleEngine"));
                }
                SolidusGovernanceMod.LOGGER.info("  Action: deactivate_lockdown");
                break;
            }
            case "increase_tax": {
                String taxConfigKey;
                if (srv != null && (taxConfigKey = this.resolveTaxConfigKey(action.key())) != null) {
                    srv.execute(() -> {
                        double currentRate = this.engine.getConfig().getDouble(taxConfigKey, 0.0);
                        double increase = this.parseDouble(action.value(), 0.01);
                        double newRate = Math.min(currentRate + increase, 1.0);
                        this.engine.getConfig().set(taxConfigKey, String.valueOf(newRate));
                        this.engine.getAuditLogger().logConfigChange(null, "RuleEngine", taxConfigKey, String.valueOf(currentRate), String.valueOf(newRate));
                    });
                }
                SolidusGovernanceMod.LOGGER.info("  Action: increase_tax {} by {}", (Object)action.key(), (Object)action.value());
                break;
            }
            case "decrease_tax": {
                String taxConfigKey;
                if (srv != null && (taxConfigKey = this.resolveTaxConfigKey(action.key())) != null) {
                    srv.execute(() -> {
                        double currentRate = this.engine.getConfig().getDouble(taxConfigKey, 0.0);
                        double decrease = this.parseDouble(action.value(), 0.005);
                        double newRate = Math.max(currentRate - decrease, 0.0);
                        this.engine.getConfig().set(taxConfigKey, String.valueOf(newRate));
                        this.engine.getAuditLogger().logConfigChange(null, "RuleEngine", taxConfigKey, String.valueOf(currentRate), String.valueOf(newRate));
                    });
                }
                SolidusGovernanceMod.LOGGER.info("  Action: decrease_tax {} by {}", (Object)action.key(), (Object)action.value());
                break;
            }
            case "send_discord_alert": {
                String category = action.key() != null ? action.key() : "AUTOMATION";
                String message = action.value() != null ? action.value() : "Rule engine alert";
                this.sendDiscordAlert(category, "Rule Engine Alert", message);
                SolidusGovernanceMod.LOGGER.info("  Action: send_discord_alert [{}] {}", (Object)category, (Object)message);
                break;
            }
            default: {
                SolidusGovernanceMod.LOGGER.warn("Unknown action type: {}", (Object)action.type());
            }
        }
    }

    private String resolveTaxConfigKey(String taxType) {
        if (taxType == null) {
            return null;
        }
        return switch (taxType.toLowerCase()) {
            case "transfer" -> "taxation.transfer.rate";
            case "auction" -> "taxation.auction.rate";
            case "shop" -> "taxation.shop.rate";
            default -> null;
        };
    }

    private double parseDouble(String value, double defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public static boolean isValidRuleName(String name) {
        if (name == null) {
            return false;
        }
        return name.matches("^[a-z0-9][a-z0-9-]{1,31}$");
    }

    public static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) {
            return 0L;
        }
        try {
            String trimmed = duration.trim().toLowerCase();
            if (trimmed.endsWith("s")) {
                return Math.max(0L, (long)(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1000.0));
            }
            if (trimmed.endsWith("m")) {
                return Math.max(0L, (long)(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 60000.0));
            }
            if (trimmed.endsWith("h")) {
                return Math.max(0L, (long)(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 3600000.0));
            }
            if (trimmed.endsWith("d")) {
                return Math.max(0L, (long)(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 8.64E7));
            }
            return Math.max(0L, Long.parseLong(trimmed));
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "none";
        }
        long days = millis / 86400000L;
        long hours = millis % 86400000L / 3600000L;
        long minutes = millis % 3600000L / 60000L;
        long seconds = millis % 60000L / 1000L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
