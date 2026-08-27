package com.solidus.governance.discord;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.discord.DiscordWebhook;
import com.solidus.governance.discord.WebhookRateLimiter;
import com.solidus.governance.engine.GovernanceEngine;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class WebhookManager {
    public static final int COLOR_LOCKDOWN = 0xFF0000;
    public static final int COLOR_INTERVENTION = 16747520;
    public static final int COLOR_TAXATION = 16766720;
    public static final int COLOR_AUTOMATION = 9133302;
    public static final int COLOR_LIMITS = 3447003;
    public static final int COLOR_RECOVERY = 65535;
    public static final int COLOR_DAILY_SUMMARY = 65280;
    public static final Set<String> VALID_CATEGORIES = Set.of("lockdown", "intervention", "taxation", "automation", "limits", "recovery", "default");
    private static final Map<String, String> CATEGORY_CONFIG_KEYS = Map.of("lockdown", "discord.webhook.lockdown", "intervention", "discord.webhook.intervention", "taxation", "discord.webhook.taxation", "automation", "discord.webhook.automation", "limits", "discord.webhook.limits", "recovery", "discord.webhook.recovery", "default", "discord.webhook.default");
    private final GovernanceConfig config;
    private final DiscordWebhook webhook;
    private final WebhookRateLimiter rateLimiter;

    public WebhookManager(GovernanceConfig config) {
        this.config = config;
        this.rateLimiter = new WebhookRateLimiter();
        this.webhook = new DiscordWebhook(this.rateLimiter);
        SolidusGovernanceMod.LOGGER.info("WebhookManager initialized.");
    }

    public String getWebhookUrl(String category) {
        String configKey = CATEGORY_CONFIG_KEYS.getOrDefault(category.toLowerCase(), "discord.webhook.default");
        String url = this.config.getString(configKey, "");
        if (url.isBlank() && !category.equalsIgnoreCase("default")) {
            url = this.config.getString("discord.webhook.default", "");
        }
        return url;
    }

    public void setWebhookUrl(String category, String url) {
        String configKey = CATEGORY_CONFIG_KEYS.getOrDefault(category.toLowerCase(), "discord.webhook.default");
        this.config.set(configKey, url != null ? url : "");
        SolidusGovernanceMod.LOGGER.info("Discord webhook URL set for category '{}': {}", (Object)category, (Object)url);
    }

    public void removeWebhookUrl(String category) {
        String configKey = CATEGORY_CONFIG_KEYS.getOrDefault(category.toLowerCase(), "discord.webhook.default");
        this.config.set(configKey, "");
        SolidusGovernanceMod.LOGGER.info("Discord webhook URL removed for category '{}'", (Object)category);
    }

    public CompletableFuture<Void> sendAlert(String category, String title, String description) {
        if (!this.isDiscordEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        String webhookUrl = this.getWebhookUrl(category);
        int color = WebhookManager.getColorForCategory(category);
        return ((CompletableFuture)this.webhook.sendAlert(category, title, description, color, webhookUrl).thenAccept(success -> {
            if (!success.booleanValue()) {
                SolidusGovernanceMod.LOGGER.debug("WebhookManager: alert not sent for category '{}'", (Object)category);
            }
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("WebhookManager: failed to send {} alert", (Object)category, ex);
            return null;
        });
    }

    public CompletableFuture<Void> sendDailySummary(GovernanceEngine engine) {
        if (!this.isDiscordEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder description = new StringBuilder();
        description.append("**Daily Governance Report \u2014 ").append(date).append("**\n\n");
        description.append("**System Status:**\n");
        description.append("\u2022 Intervention: ").append(engine.getConfig().getBool("intervention.enabled", true) ? "ON" : "OFF").append("\n");
        description.append("\u2022 Taxation: ").append(engine.getConfig().getBool("taxation.enabled", false) ? "ON" : "OFF").append("\n");
        description.append("\u2022 Automation: ").append(engine.getConfig().getBool("automation.enabled", false) ? "ON" : "OFF").append("\n");
        description.append("\u2022 Trading: ").append(engine.getInterventionManager().isTradingLocked() ? "LOCKED" : "OPEN").append("\n");
        description.append("\u2022 Lockdown: ").append(engine.getAutomator().isLockdownActive() ? "ACTIVE" : "INACTIVE").append("\n\n");
        description.append("**Statistics:**\n");
        description.append("\u2022 Frozen Accounts: ").append(engine.getAccountFreezer().getFrozenCount()).append("\n");
        description.append("\u2022 Suspicious Accounts: ").append(engine.getInterventionManager().getSuspiciousAccounts().size()).append("\n");
        description.append("\n**Tax Rates:**\n");
        description.append("\u2022 Transfer: ").append(String.format("%.1f%%", engine.getConfig().getDouble("taxation.transfer.rate", 0.0) * 100.0)).append("\n");
        description.append("\u2022 Auction: ").append(String.format("%.1f%%", engine.getConfig().getDouble("taxation.auction.rate", 0.05) * 100.0)).append("\n");
        description.append("\u2022 Shop: ").append(String.format("%.1f%%", engine.getConfig().getDouble("taxation.shop.rate", 0.0) * 100.0)).append("\n");
        return this.sendAlert("DAILY_SUMMARY", "Daily Governance Summary", description.toString());
    }

    public static int getColorForCategory(String category) {
        return switch (category.toUpperCase()) {
            case "LOCKDOWN" -> 0xFF0000;
            case "INTERVENTION" -> 16747520;
            case "TAXATION" -> 16766720;
            case "AUTOMATION" -> 9133302;
            case "LIMITS" -> 3447003;
            case "RECOVERY" -> 65535;
            case "DAILY_SUMMARY" -> 65280;
            default -> 0xFF0000;
        };
    }

    public boolean isDiscordEnabled() {
        return this.config.getBool("discord.enabled", false);
    }

    public double getInterventionThreshold() {
        return this.config.getDouble("discord.alert-threshold.intervention", 100000.0);
    }

    public int getQueueSize() {
        return this.rateLimiter.getQueueSize();
    }

    public void shutdown() {
        SolidusGovernanceMod.LOGGER.info("WebhookManager shutting down...");
        this.rateLimiter.shutdown();
        SolidusGovernanceMod.LOGGER.info("WebhookManager shut down complete.");
    }
}
