package com.solidus.governance;

import com.solidus.governance.SolidusGovernanceMod;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

public class GovernanceConfig {
    private final Path configPath;
    private final Properties properties = new Properties();
    private final TreeMap<String, String> defaults = new TreeMap();

    public GovernanceConfig(Path configDir) {
        this.configPath = configDir.resolve("governance.properties");
        this.initializeDefaults();
    }

    private void initializeDefaults() {
        this.defaults.put("intervention.enabled", "true");
        this.defaults.put("taxation.enabled", "false");
        this.defaults.put("taxation.transfer.rate", "0.0");
        this.defaults.put("taxation.auction.rate", "0.05");
        this.defaults.put("taxation.shop.rate", "0.0");
        this.defaults.put("taxation.treasury.account", "");
        this.defaults.put("taxation.wealth-decay.enabled", "false");
        this.defaults.put("taxation.wealth-decay.rate", "0.001");
        this.defaults.put("taxation.wealth-decay.threshold", "1000000");
        this.defaults.put("audit.enabled", "true");
        this.defaults.put("audit.retention-days", "90");
        this.defaults.put("recovery.snapshot.retention", "28");
        this.defaults.put("recovery.snapshot.auto-enabled", "false");
        this.defaults.put("recovery.snapshot.auto-interval-hours", "6");
        this.defaults.put("automation.enabled", "false");
        this.defaults.put("automation.anti-inflation.enabled", "false");
        this.defaults.put("automation.anti-inflation.threshold", "15.0");
        this.defaults.put("automation.wealth-cap.enabled", "false");
        this.defaults.put("automation.wealth-cap.amount", "10000000");
        this.defaults.put("automation.auto-freeze.enabled", "false");
        this.defaults.put("automation.emergency-lockdown.enabled", "false");
        this.defaults.put("persistence.suspicious-accounts.enabled", "true");
        this.defaults.put("persistence.trading-lock.enabled", "true");
        this.defaults.put("limits.transfer.daily-max", "-1");
        this.defaults.put("limits.transfer.min", "0");
        this.defaults.put("limits.transfer.max", "-1");
        this.defaults.put("limits.auction.daily-max", "-1");
        this.defaults.put("discord.enabled", "false");
        this.defaults.put("discord.webhook.default", "");
        this.defaults.put("discord.webhook.lockdown", "");
        this.defaults.put("discord.webhook.intervention", "");
        this.defaults.put("discord.webhook.taxation", "");
        this.defaults.put("discord.webhook.automation", "");
        this.defaults.put("discord.webhook.limits", "");
        this.defaults.put("discord.webhook.recovery", "");
        this.defaults.put("discord.alert-threshold.intervention", "100000");
        this.defaults.put("events.enabled", "true");
        this.defaults.put("policies.enabled", "true");
        this.defaults.put("policies.include-discord", "false");
        this.defaults.put("rules.enabled", "true");
        this.defaults.put("simulation.enabled", "false");
        this.defaults.put("simulation.sample-size", "-1");
        this.defaults.put("simulation.sample-percentage", "0.15");
        this.defaults.put("simulation.sample-min", "20");
        this.defaults.put("simulation.sample-max", "500");
        this.defaults.put("simulation.active-accounts-refresh-hours", "3");
    }

    public synchronized void load() {
        this.properties.clear();
        for (Map.Entry<String, String> entry : this.defaults.entrySet()) {
            this.properties.setProperty(entry.getKey(), entry.getValue());
        }
        if (Files.exists(this.configPath, new LinkOption[0])) {
            try (BufferedReader reader = Files.newBufferedReader(this.configPath);){
                Properties saved = new Properties();
                saved.load(reader);
                for (String key : saved.stringPropertyNames()) {
                    this.properties.setProperty(key, saved.getProperty(key));
                }
                SolidusGovernanceMod.LOGGER.info("Governance config loaded from: {}", (Object)this.configPath);
            }
            catch (IOException e) {
                SolidusGovernanceMod.LOGGER.warn("Failed to load governance config, using defaults", (Throwable)e);
            }
        } else {
            this.save();
            SolidusGovernanceMod.LOGGER.info("Governance config created with defaults at: {}", (Object)this.configPath);
        }
        this.normalize();
    }

    private void normalize() {
        setDoubleRange("taxation.transfer.rate", 0.0, 1.0, 0.0);
        setDoubleRange("taxation.auction.rate", 0.0, 1.0, 0.05);
        setDoubleRange("taxation.shop.rate", 0.0, 1.0, 0.0);
        setDoubleRange("taxation.wealth-decay.rate", 0.0, 1.0, 0.001);
        setDoubleRange("automation.anti-inflation.threshold", 0.0, Double.MAX_VALUE, 15.0);
        setDoubleRange("automation.wealth-cap.amount", 0.0, Double.MAX_VALUE, 10000000.0);
        setDoubleRange("simulation.sample-percentage", 0.0, 1.0, 0.15);
        setIntMin("audit.retention-days", 1, 90);
        setIntMin("recovery.snapshot.retention", 1, 28);
        setIntMin("recovery.snapshot.auto-interval-hours", 1, 6);
        setIntMin("simulation.sample-min", 0, 20);
        setIntMin("simulation.sample-max", 1, 500);
        String[] webhookKeys = {"discord.webhook.default", "discord.webhook.lockdown", "discord.webhook.intervention", "discord.webhook.taxation", "discord.webhook.automation", "discord.webhook.limits", "discord.webhook.recovery"};
        boolean validWebhook = false;
        for (String key : webhookKeys) {
            String url = this.properties.getProperty(key, "").trim();
            if (!url.isEmpty() && isAllowedDiscordWebhook(url)) {
                validWebhook = true;
            } else if (!url.isEmpty()) {
                this.properties.setProperty(key, "");
                SolidusGovernanceMod.LOGGER.warn("Ignoring non-HTTPS Discord webhook configured at {}", key);
            }
        }
        if (!validWebhook) {
            this.properties.setProperty("discord.enabled", "false");
        }
    }

    private void setIntMin(String key, int min, int fallback) {
        int value = getInt(key, fallback);
        this.properties.setProperty(key, String.valueOf(Math.max(min, value)));
    }

    private void setDoubleRange(String key, double min, double max, double fallback) {
        double value = getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) value = fallback;
        this.properties.setProperty(key, String.valueOf(value));
    }

    private static boolean isAllowedDiscordWebhook(String url) {
        return url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/");
    }

    public synchronized void save() {
        try {
            Files.createDirectories(this.configPath.getParent(), new FileAttribute[0]);
            try (BufferedWriter writer = Files.newBufferedWriter(this.configPath, new OpenOption[0]);){
                this.properties.store(writer, "Solidus Governance Configuration");
            }
        }
        catch (IOException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to save governance config", (Throwable)e);
        }
    }

    public synchronized boolean getBool(String key, boolean defaultValue) {
        String value = this.properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public synchronized double getDouble(String key, double defaultValue) {
        String value = this.properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized int getInt(String key, int defaultValue) {
        String value = this.properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized String getString(String key, String defaultValue) {
        return this.properties.getProperty(key, defaultValue);
    }

    public synchronized void set(String key, String value) {
        this.properties.setProperty(key, value);
        this.save();
    }

    public synchronized Set<String> getAllKeys() {
        return Collections.unmodifiableSet(this.properties.stringPropertyNames());
    }
}
