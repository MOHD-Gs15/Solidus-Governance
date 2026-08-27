package com.solidus.governance.events;

import java.util.Map;

public class EconomyEvent {
    private final String id;
    private final String name;
    private final String type;
    private final double modifier;
    private final long startTime;
    private final long endTime;
    private final String creatorUuid;
    private final String creatorName;
    private Map<String, String> originalValues;
    private boolean active;

    public EconomyEvent(String id, String name, String type, double modifier, long startTime, long endTime, String creatorUuid, String creatorName, Map<String, String> originalValues, boolean active) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.modifier = modifier;
        this.startTime = startTime;
        this.endTime = endTime;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName;
        this.originalValues = originalValues;
        this.active = active;
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getType() {
        return this.type;
    }

    public double getModifier() {
        return this.modifier;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public long getEndTime() {
        return this.endTime;
    }

    public String getCreatorUuid() {
        return this.creatorUuid;
    }

    public String getCreatorName() {
        return this.creatorName;
    }

    public Map<String, String> getOriginalValues() {
        return this.originalValues;
    }

    public boolean isActive() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setOriginalValues(Map<String, String> originalValues) {
        this.originalValues = originalValues;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= this.endTime;
    }

    public long getRemainingMillis() {
        long remaining = this.endTime - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public String getRemainingDurationString() {
        long remaining = this.getRemainingMillis();
        if (remaining <= 0L) {
            return "expired";
        }
        long days = remaining / 86400000L;
        long hours = (remaining %= 86400000L) / 3600000L;
        long minutes = (remaining %= 3600000L) / 60000L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append("d ");
        }
        if (hours > 0L) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0L) {
            sb.append(minutes).append("m");
        }
        return sb.toString().trim();
    }

    public String getTotalDurationString() {
        long duration = this.endTime - this.startTime;
        long days = duration / 86400000L;
        long hours = (duration %= 86400000L) / 3600000L;
        long minutes = (duration %= 3600000L) / 60000L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append("d ");
        }
        if (hours > 0L) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0L) {
            sb.append(minutes).append("m");
        }
        return sb.toString().trim();
    }

    public String toString() {
        return "EconomyEvent{id='" + this.id + "', name='" + this.name + "', type='" + this.type + "', modifier=" + this.modifier + ", active=" + this.active + "}";
    }
}
