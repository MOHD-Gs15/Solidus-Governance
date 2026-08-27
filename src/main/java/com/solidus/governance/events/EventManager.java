package com.solidus.governance.events;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditLogger;
import com.solidus.governance.discord.WebhookManager;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.events.EconomyEvent;
import com.solidus.governance.events.EventDatabase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EventManager {
    private final Map<String, EconomyEvent> activeEvents = new LinkedHashMap<String, EconomyEvent>();
    private final List<EconomyEvent> allEvents = new ArrayList<EconomyEvent>();
    private final GovernanceConfig config;
    private final EventDatabase database;
    private final GovernanceEngine engine;

    public EventManager(GovernanceConfig config, EventDatabase database, GovernanceEngine engine) {
        this.config = config;
        this.database = database;
        this.engine = engine;
    }

    public CompletableFuture<EconomyEvent> createEvent(String name, String type, double modifier, String duration, UUID creatorUuid, String creatorName) {
        return CompletableFuture.supplyAsync(() -> {
            WebhookManager webhookManager;
            if (!this.config.getBool("events.enabled", true)) {
                SolidusGovernanceMod.LOGGER.warn("Event creation rejected: events are disabled in config");
                return null;
            }
            if (!this.engine.isPremiumEnabled()) {
                SolidusGovernanceMod.LOGGER.warn("Event creation rejected: premium license required");
                return null;
            }
            long durationMillis = EventManager.parseDuration(duration);
            if (durationMillis <= 0L) {
                SolidusGovernanceMod.LOGGER.warn("Event creation rejected: invalid duration '{}'", (Object)duration);
                return null;
            }
            String normalizedType = EventManager.normalizeEventType(type);
            if (normalizedType == null) {
                SolidusGovernanceMod.LOGGER.warn("Event creation rejected: unknown event type '{}'", (Object)type);
                return null;
            }
            String eventId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            long endTime = now + durationMillis;
            Map<String, String> originalValues = this.captureOriginalValues(normalizedType);
            EconomyEvent event = new EconomyEvent(eventId, name, normalizedType, modifier, now, endTime, creatorUuid.toString(), creatorName, originalValues, true);
            this.applyEventModifications(event);
            Object object = this.activeEvents;
            synchronized (object) {
                this.activeEvents.put(eventId, event);
            }
            object = this.allEvents;
            synchronized (object) {
                this.allEvents.add(0, event);
            }
            this.database.saveEvent(event);
            AuditLogger auditLogger = this.engine.getAuditLogger();
            if (auditLogger != null) {
                auditLogger.logConfigChange(creatorUuid, creatorName, "event." + normalizedType.toLowerCase(), originalValues.toString(), "modifier=" + modifier + ";duration=" + duration);
            }
            if ((webhookManager = this.engine.getWebhookManager()) != null) {
                webhookManager.sendAlert("AUTOMATION", "Economy Event Started: " + name, "Type: " + normalizedType + " | Modifier: " + modifier + " | Duration: " + event.getTotalDurationString() + " | Created by: " + creatorName);
            }
            SolidusGovernanceMod.LOGGER.info("Economy event created: {} ({} x{}, {})", new Object[]{name, normalizedType, modifier, duration});
            return event;
        });
    }

    public CompletableFuture<Boolean> cancelEvent(String eventId) {
        return CompletableFuture.supplyAsync(() -> {
            WebhookManager webhookManager;
            EconomyEvent event;
            Map<String, EconomyEvent> map = this.activeEvents;
            synchronized (map) {
                event = this.activeEvents.get(eventId);
            }
            if (event == null || !event.isActive()) {
                SolidusGovernanceMod.LOGGER.warn("Event cancellation failed: event {} not found or inactive", (Object)eventId);
                return false;
            }
            this.revertEventConfig(event);
            event.setActive(false);
            map = this.activeEvents;
            synchronized (map) {
                this.activeEvents.remove(eventId);
            }
            this.database.updateEvent(event);
            AuditLogger auditLogger = this.engine.getAuditLogger();
            if (auditLogger != null) {
                auditLogger.logConfigChange(null, "System", "event.cancel." + event.getType().toLowerCase(), "active=true", "active=false;reverted");
            }
            if ((webhookManager = this.engine.getWebhookManager()) != null) {
                webhookManager.sendAlert("AUTOMATION", "Economy Event Cancelled: " + event.getName(), "Type: " + event.getType() + " | Config reverted to original values");
            }
            SolidusGovernanceMod.LOGGER.info("Economy event cancelled: {} ({})", (Object)event.getName(), (Object)eventId);
            return true;
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void tickExpirations() {
        ArrayList<EconomyEvent> expired = new ArrayList<EconomyEvent>();
        Map<String, EconomyEvent> map = this.activeEvents;
        synchronized (map) {
            Iterator<EconomyEvent> iterator = this.activeEvents.values().iterator();
            while (iterator.hasNext()) {
                EconomyEvent event = iterator.next();
                if (!event.isExpired()) continue;
                expired.add(event);
                iterator.remove();
            }
        }
        for (EconomyEvent event : expired) {
            WebhookManager webhookManager;
            this.revertEventConfig(event);
            event.setActive(false);
            this.database.updateEvent(event);
            AuditLogger auditLogger = this.engine.getAuditLogger();
            if (auditLogger != null) {
                auditLogger.logConfigChange(null, "System", "event.expire." + event.getType().toLowerCase(), "active=true", "active=false;expired");
            }
            if ((webhookManager = this.engine.getWebhookManager()) != null) {
                webhookManager.sendAlert("AUTOMATION", "Economy Event Expired: " + event.getName(), "Type: " + event.getType() + " | Config reverted to original values");
            }
            SolidusGovernanceMod.LOGGER.info("Economy event expired: {} ({})", (Object)event.getName(), (Object)event.getId());
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<EconomyEvent> getActiveEvents() {
        Map<String, EconomyEvent> map = this.activeEvents;
        synchronized (map) {
            return List.copyOf(this.activeEvents.values());
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<EconomyEvent> getAllEvents() {
        List<EconomyEvent> list = this.allEvents;
        synchronized (list) {
            return List.copyOf(this.allEvents);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public EconomyEvent getEvent(String id) {
        Object object = this.activeEvents;
        synchronized (object) {
            EconomyEvent active = this.activeEvents.get(id);
            if (active != null) {
                return active;
            }
        }
        object = this.allEvents;
        synchronized (object) {
            for (EconomyEvent event : this.allEvents) {
                if (!event.getId().equals(id)) continue;
                return event;
            }
        }
        return null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void loadFromDatabase() {
        List<EconomyEvent> dbEvents = this.database.loadActiveEvents();
        long now = System.currentTimeMillis();
        for (EconomyEvent event : dbEvents) {
            Object object;
            if (event.isExpired()) {
                this.revertEventConfig(event);
                event.setActive(false);
                this.database.updateEvent(event);
                SolidusGovernanceMod.LOGGER.info("Reverted expired event on startup: {} ({})", (Object)event.getName(), (Object)event.getId());
            } else {
                this.applyEventModifications(event);
                object = this.activeEvents;
                synchronized (object) {
                    this.activeEvents.put(event.getId(), event);
                }
                SolidusGovernanceMod.LOGGER.info("Restored active event: {} ({}) \u2014 {} remaining", new Object[]{event.getName(), event.getId(), event.getRemainingDurationString()});
            }
            object = this.allEvents;
            synchronized (object) {
                this.allEvents.add(event);
            }
        }
        List<EconomyEvent> allDbEvents = this.database.loadAllEvents();
        List<EconomyEvent> list = this.allEvents;
        synchronized (list) {
            this.allEvents.clear();
            this.allEvents.addAll(allDbEvents);
        }
        SolidusGovernanceMod.LOGGER.info("Loaded {} active events from database.", (Object)this.activeEvents.size());
    }

    private Map<String, String> captureOriginalValues(String type) {
        LinkedHashMap<String, String> originals = new LinkedHashMap<String, String>();
        switch (type) {
            case "DOUBLE_SHOP": {
                originals.put("shop.sell-multiplier", this.config.getString("shop.sell-multiplier", "1.0"));
                break;
            }
            case "TAX_HOLIDAY": {
                originals.put("taxation.transfer.rate", this.config.getString("taxation.transfer.rate", "0.0"));
                originals.put("taxation.auction.rate", this.config.getString("taxation.auction.rate", "0.05"));
                originals.put("taxation.shop.rate", this.config.getString("taxation.shop.rate", "0.0"));
                break;
            }
            case "INFLATION_SALE": {
                originals.put("shop.buy-multiplier", this.config.getString("shop.buy-multiplier", "1.0"));
                break;
            }
            case "BONUS_CURRENCY": {
                originals.put("transfer.bonus-multiplier", this.config.getString("transfer.bonus-multiplier", "1.0"));
                break;
            }
            case "CUSTOM": {
                break;
            }
            default: {
                SolidusGovernanceMod.LOGGER.warn("Unknown event type for capturing values: {}", (Object)type);
            }
        }
        return originals;
    }

    private void applyEventModifications(EconomyEvent event) {
        switch (event.getType()) {
            case "DOUBLE_SHOP": {
                this.config.set("shop.sell-multiplier", String.valueOf(event.getModifier()));
                SolidusGovernanceMod.LOGGER.info("Applied DOUBLE_SHOP: shop.sell-multiplier = {}", (Object)event.getModifier());
                break;
            }
            case "TAX_HOLIDAY": {
                this.config.set("taxation.transfer.rate", "0.0");
                this.config.set("taxation.auction.rate", "0.0");
                this.config.set("taxation.shop.rate", "0.0");
                SolidusGovernanceMod.LOGGER.info("Applied TAX_HOLIDAY: all tax rates set to 0");
                break;
            }
            case "INFLATION_SALE": {
                this.config.set("shop.buy-multiplier", String.valueOf(event.getModifier()));
                SolidusGovernanceMod.LOGGER.info("Applied INFLATION_SALE: shop.buy-multiplier = {}", (Object)event.getModifier());
                break;
            }
            case "BONUS_CURRENCY": {
                this.config.set("transfer.bonus-multiplier", String.valueOf(event.getModifier()));
                SolidusGovernanceMod.LOGGER.info("Applied BONUS_CURRENCY: transfer.bonus-multiplier = {}", (Object)event.getModifier());
                break;
            }
            case "CUSTOM": {
                SolidusGovernanceMod.LOGGER.info("CUSTOM event type: not implemented in v1");
                break;
            }
            default: {
                SolidusGovernanceMod.LOGGER.warn("Unknown event type for applying modifications: {}", (Object)event.getType());
            }
        }
    }

    private void revertEventConfig(EconomyEvent event) {
        Map<String, String> originals = event.getOriginalValues();
        if (originals == null || originals.isEmpty()) {
            SolidusGovernanceMod.LOGGER.warn("No original values to revert for event {}", (Object)event.getId());
            return;
        }
        for (Map.Entry<String, String> entry : originals.entrySet()) {
            this.config.set(entry.getKey(), entry.getValue());
            SolidusGovernanceMod.LOGGER.info("Reverted config: {} = {}", (Object)entry.getKey(), (Object)entry.getValue());
        }
        SolidusGovernanceMod.LOGGER.info("Reverted all config changes for event: {} ({})", (Object)event.getName(), (Object)event.getId());
    }

    public static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) {
            return -1L;
        }
        long totalMillis = 0L;
        StringBuilder numberBuffer = new StringBuilder();
        for (int i = 0; i < duration.length(); ++i) {
            char c = duration.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                numberBuffer.append(c);
                continue;
            }
            if (Character.isLetter(c)) {
                double value;
                if (numberBuffer.isEmpty()) {
                    return -1L;
                }
                try {
                    value = Double.parseDouble(numberBuffer.toString());
                }
                catch (NumberFormatException e) {
                    return -1L;
                }
                numberBuffer.setLength(0);
                if ((totalMillis += (switch (Character.toLowerCase(c)) {
                    case 'm' -> (long)(value * 60000.0);
                    case 'h' -> (long)(value * 3600000.0);
                    case 'd' -> (long)(value * 8.64E7);
                    default -> -1L;
                })) >= 0L) continue;
                return -1L;
            }
            return -1L;
        }
        if (!numberBuffer.isEmpty()) {
            try {
                double value = Double.parseDouble(numberBuffer.toString());
                totalMillis += (long)(value * 60000.0);
            }
            catch (NumberFormatException e) {
                return -1L;
            }
        }
        return totalMillis > 0L ? totalMillis : -1L;
    }

    public static String normalizeEventType(String type) {
        String normalized;
        if (type == null) {
            return null;
        }
        return switch (normalized = type.toUpperCase().replace("-", "_")) {
            case "DOUBLE_SHOP" -> "DOUBLE_SHOP";
            case "TAX_HOLIDAY" -> "TAX_HOLIDAY";
            case "INFLATION_SALE" -> "INFLATION_SALE";
            case "BONUS_CURRENCY" -> "BONUS_CURRENCY";
            case "CUSTOM" -> "CUSTOM";
            default -> null;
        };
    }
}
