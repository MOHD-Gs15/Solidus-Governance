package com.solidus.governance.discord;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.discord.WebhookRateLimiter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public class DiscordWebhook {
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MS = 1000L;
    /** D-8 fix (audit round 3): without a connect/request timeout, one hung
     *  webhook endpoint blocked the rate limiter's only drain thread forever
     *  (all queued alerts silently rotted behind it). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
    private final WebhookRateLimiter rateLimiter;

    public DiscordWebhook(WebhookRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public CompletableFuture<Boolean> sendEmbed(String webhookUrl, String title, String description, int color) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            SolidusGovernanceMod.LOGGER.warn("DiscordWebhook: webhook URL is blank, skipping send");
            return CompletableFuture.completedFuture(false);
        }
        String payload = this.buildEmbedPayload(title, description, color);
        return this.rateLimiter.enqueue(() -> this.sendWithRetry(webhookUrl, payload));
    }

    public CompletableFuture<Boolean> sendAlert(String category, String title, String description, int color, String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            SolidusGovernanceMod.LOGGER.debug("DiscordWebhook: no webhook URL for category '{}', skipping", (Object)category);
            return CompletableFuture.completedFuture(false);
        }
        SolidusGovernanceMod.LOGGER.info("DiscordWebhook: sending {} alert: {}", (Object)category, (Object)title);
        return this.sendEmbed(webhookUrl, title, description, color);
    }

    private CompletableFuture<Boolean> sendWithRetry(String webhookUrl, String payload) {
        return ((CompletableFuture)this.sendHttpRequest(webhookUrl, payload).thenCompose(success -> {
            if (success.booleanValue()) {
                return CompletableFuture.completedFuture(true);
            }
            return this.retryChain(webhookUrl, payload, 1);
        })).exceptionally(ex -> {
            // SECURITY: mask the URL - the error log must not leak the webhook token.
            SolidusGovernanceMod.LOGGER.error("DiscordWebhook: all retries failed for {}", (Object)WebhookManager.maskUrl(webhookUrl), ex);
            return false;
        });
    }

    private CompletableFuture<Boolean> retryChain(String webhookUrl, String payload, int attempt) {
        if (attempt >= 3) {
            SolidusGovernanceMod.LOGGER.warn("DiscordWebhook: max retries ({}) reached, giving up", (Object)3);
            return CompletableFuture.completedFuture(false);
        }
        long delayMs = 1000L * (1L << attempt);
        SolidusGovernanceMod.LOGGER.warn("DiscordWebhook: retry {} in {}ms", (Object)(attempt + 1), (Object)delayMs);
        CompletableFuture<Boolean> delayed = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMs);
                ((CompletableFuture)this.sendHttpRequest(webhookUrl, payload).thenAccept(delayed::complete)).exceptionally(ex -> {
                    ((CompletableFuture)this.retryChain(webhookUrl, payload, attempt + 1).thenAccept(delayed::complete)).exceptionally(ex2 -> {
                        delayed.complete(false);
                        return null;
                    });
                    return null;
                });
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                delayed.complete(false);
            }
        });
        return delayed;
    }

    private CompletableFuture<Boolean> sendHttpRequest(String webhookUrl, String payload) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        return ((CompletableFuture)this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                SolidusGovernanceMod.LOGGER.debug("DiscordWebhook: message sent successfully (HTTP {})", (Object)response.statusCode());
                return true;
            }
            SolidusGovernanceMod.LOGGER.warn("DiscordWebhook: non-success response HTTP {}: {}", (Object)response.statusCode(), response.body());
            return false;
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("DiscordWebhook: HTTP request failed", ex);
            return false;
        });
    }

    private String buildEmbedPayload(String title, String description, int color) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return "{\"embeds\":[{\"title\":" + DiscordWebhook.escapeJson(title) + ",\"description\":" + DiscordWebhook.escapeJson(description) + ",\"color\":" + color + ",\"footer\":{\"text\":\"Solidus Governance\"},\"timestamp\":\"" + timestamp + "\"}]}";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // D-12 fix: control chars below 0x20 (raw 0x01, 0x1F, ... from
                    // crafted offline-mode usernames) break the JSON payload and
                    // kill the alert. Every one of them becomes a unicode escape sequence.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
