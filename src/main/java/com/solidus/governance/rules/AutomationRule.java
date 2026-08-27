package com.solidus.governance.rules;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.rules.RuleContext;
import java.util.ArrayList;
import java.util.List;

public class AutomationRule {
    private final String name;
    private boolean enabled;
    private final List<RuleCondition> conditions;
    private final List<RuleAction> actions;
    private long cooldownMillis;
    private long lastTriggered;

    public AutomationRule(String name, boolean enabled, List<RuleCondition> conditions, List<RuleAction> actions, long cooldownMillis, long lastTriggered) {
        this.name = name;
        this.enabled = enabled;
        this.conditions = conditions != null ? new ArrayList<RuleCondition>(conditions) : new ArrayList();
        this.actions = actions != null ? new ArrayList<RuleAction>(actions) : new ArrayList();
        this.cooldownMillis = cooldownMillis;
        this.lastTriggered = lastTriggered;
    }

    public boolean isOffCooldown() {
        if (this.cooldownMillis <= 0L) {
            return true;
        }
        if (this.lastTriggered <= 0L) {
            return true;
        }
        return System.currentTimeMillis() - this.lastTriggered >= this.cooldownMillis;
    }

    public boolean evaluate(RuleContext ctx) {
        if (this.conditions.isEmpty()) {
            return false;
        }
        for (RuleCondition condition : this.conditions) {
            if (condition.test(ctx)) continue;
            return false;
        }
        return true;
    }

    public String getRemainingCooldownString() {
        if (this.isOffCooldown()) {
            return "Ready";
        }
        if (this.lastTriggered <= 0L) {
            return "Ready";
        }
        long remaining = this.cooldownMillis - (System.currentTimeMillis() - this.lastTriggered);
        if (remaining <= 0L) {
            return "Ready";
        }
        long hours = remaining / 3600000L;
        long minutes = remaining % 3600000L / 60000L;
        long seconds = remaining % 60000L / 1000L;
        if (hours > 0L) {
            return String.format("%dh %dm remaining", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format("%dm %ds remaining", minutes, seconds);
        }
        return String.format("%ds remaining", seconds);
    }

    public String getName() {
        return this.name;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<RuleCondition> getConditions() {
        return this.conditions;
    }

    public List<RuleAction> getActions() {
        return this.actions;
    }

    public long getCooldownMillis() {
        return this.cooldownMillis;
    }

    public void setCooldownMillis(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    public long getLastTriggered() {
        return this.lastTriggered;
    }

    public void setLastTriggered(long lastTriggered) {
        this.lastTriggered = lastTriggered;
    }

    public record RuleCondition(String type, double value) {
        public boolean test(RuleContext ctx) {
            return switch (this.type) {
                case "avg_balance_above" -> {
                    if (ctx.getAvgBalance() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "avg_balance_below" -> {
                    if (ctx.getAvgBalance() < this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "inflation_rate_above" -> {
                    if (ctx.getInflationRate() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "transaction_volume_24h_above" -> {
                    if (ctx.getTransactionVolume24h() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "transaction_volume_24h_below" -> {
                    if (ctx.getTransactionVolume24h() < this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "total_money_supply_above" -> {
                    if (ctx.getTotalMoneySupply() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "total_money_supply_below" -> {
                    if (ctx.getTotalMoneySupply() < this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "player_count_above" -> {
                    if ((double)ctx.getOnlinePlayerCount() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "player_count_below" -> {
                    if ((double)ctx.getOnlinePlayerCount() < this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "gini_coefficient_above" -> {
                    if (ctx.getGiniCoefficient() > this.value) {
                        yield true;
                    }
                    yield false;
                }
                case "gini_coefficient_below" -> {
                    if (ctx.getGiniCoefficient() < this.value) {
                        yield true;
                    }
                    yield false;
                }
                default -> {
                    SolidusGovernanceMod.LOGGER.warn("Unknown condition type: {}", (Object)this.type);
                    yield false;
                }
            };
        }
    }

    public record RuleAction(String type, String key, String value) {
    }
}
