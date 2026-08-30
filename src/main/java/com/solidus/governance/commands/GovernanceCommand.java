package com.solidus.governance.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditCsvExporter;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.discord.WebhookManager;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.events.EconomyEvent;
import com.solidus.governance.events.EventManager;
import com.solidus.governance.license.LicenseVerifier;
import com.solidus.governance.limits.TransactionLimits;
import com.solidus.governance.policy.EconomyPolicy;
import com.solidus.governance.policy.PolicyManager;
import com.solidus.governance.rules.AutomationRule;
import com.solidus.governance.rules.RuleEngine;
import com.solidus.governance.simulation.SimulationEngine;
import com.solidus.governance.simulation.SimulationState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public class GovernanceCommand {
    private static final UUID CONSOLE_UUID = UUID.nameUUIDFromBytes("CONSOLE".getBytes());
    private static final List<String> VALID_CONDITION_TYPES = List.of("avg_balance_above", "avg_balance_below", "inflation_rate_above", "transaction_volume_24h_above", "transaction_volume_24h_below", "total_money_supply_above", "total_money_supply_below", "player_count_above", "player_count_below", "gini_coefficient_above", "gini_coefficient_below");
    private static final List<String> VALID_ACTION_TYPES = List.of("set_config", "enable_feature", "disable_feature", "activate_lockdown", "deactivate_lockdown", "increase_tax", "decrease_tax", "send_discord_alert");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GovernanceEngine engine) {
        dispatcher.register(Commands.literal("governance")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeStatus(context, engine))
            .then(Commands.literal("license")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .executes(context -> executeLicense(context, engine)))
            .then(Commands.literal("fingerprint")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .executes(context -> executeFingerprint(context)))
            .then(buildIntervention(engine))
            .then(buildTax(engine))
            .then(buildAudit(engine))
            .then(buildRecovery(engine))
            .then(buildAutomation(engine))
            .then(buildLimits(engine))
            .then(buildDiscord(engine))
            .then(buildEvent(engine))
            .then(buildProfile(engine))
            .then(buildPolicy(engine))
            .then(buildRules(engine))
            .then(buildSimulation(engine)));

        // /gov alias - redirects every sub-command to /governance
        dispatcher.register(Commands.literal("gov")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeStatus(context, engine))
            .redirect(dispatcher.getRoot().getChild("governance")));
    }

    // ===========================================================
    //  Command tree builders - one method per command family so
    //  each sub-tree stays readable and future sub-commands are a
    //  local edit instead of surgery on a single expression.
    //  Behavior is identical to the previous single-expression
    //  tree; the shape is pinned by CommandTreeShapeTest.
    // ===========================================================

    private static LiteralArgumentBuilder<CommandSourceStack> buildIntervention(GovernanceEngine engine) {
        return Commands.literal("intervention")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .then(Commands.literal("add")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> executeInterventionAdd(context, engine)))))
            .then(Commands.literal("remove")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> executeInterventionRemove(context, engine)))))
            .then(Commands.literal("set")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                        .executes(context -> executeInterventionSet(context, engine)))))
            .then(Commands.literal("freeze")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> executeFreeze(context, engine, 0))
                    .then(Commands.argument("duration", IntegerArgumentType.integer(0))
                        .executes(context -> executeFreeze(context, engine, IntegerArgumentType.getInteger(context, "duration"))))))
            .then(Commands.literal("unfreeze")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> executeUnfreeze(context, engine))))
            .then(Commands.literal("suspicious")
                .then(Commands.literal("list")
                    .executes(context -> executeSuspiciousList(context, engine)))
                .then(Commands.literal("mark")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("reason", MessageArgument.message())
                            .executes(context -> executeSuspiciousMark(context, engine)))))
                .then(Commands.literal("unmark")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> executeSuspiciousUnmark(context, engine)))))
            .then(Commands.literal("lock")
                .then(Commands.argument("reason", MessageArgument.message())
                    .executes(context -> executeLockTrading(context, engine, true))))
            .then(Commands.literal("unlock")
                .executes(context -> executeLockTrading(context, engine, false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTax(GovernanceEngine engine) {
        return Commands.literal("tax")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .then(Commands.literal("rates")
                .executes(context -> executeTaxRates(context, engine)))
            .then(Commands.literal("set")
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.0, 1.0))
                        .executes(context -> executeTaxSet(context, engine)))))
            .then(Commands.literal("brackets")
                .then(Commands.literal("list")
                    .executes(context -> executeBracketsList(context, engine)))
                .then(Commands.literal("add")
                    .then(Commands.argument("threshold", DoubleArgumentType.doubleArg(0.0))
                        .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.0, 1.0))
                            .executes(context -> executeBracketAdd(context, engine)))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("threshold", DoubleArgumentType.doubleArg(0.0))
                        .executes(context -> executeBracketRemove(context, engine)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAudit(GovernanceEngine engine) {
        return Commands.literal("audit")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .then(Commands.literal("export")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.literal("csv")
                    .executes(context -> executeAuditExport(context, engine, 7))
                    .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                        .executes(context -> executeAuditExport(context, engine, IntegerArgumentType.getInteger(context, "days"))))))
            .then(Commands.literal("recent")
                .executes(context -> executeAuditRecent(context, engine, 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                    .executes(context -> executeAuditRecent(context, engine, IntegerArgumentType.getInteger(context, "count")))))
            .then(Commands.literal("search")
                .then(Commands.literal("player")
                    .then(Commands.argument("query", EntityArgument.player())
                        .executes(context -> executeAuditSearch(context, engine, "player"))))
                .then(Commands.literal("category")
                    .then(Commands.argument("query", StringArgumentType.word())
                        .executes(context -> executeAuditSearch(context, engine, "category")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRecovery(GovernanceEngine engine) {
        return Commands.literal("recovery")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .then(Commands.literal("snapshot")
                .then(Commands.literal("create")
                    .executes(context -> executeSnapshotCreate(context, engine, null))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> executeSnapshotCreate(context, engine, StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("list")
                    .executes(context -> executeSnapshotList(context, engine))))
            .then(Commands.literal("rollback")
                .then(Commands.argument("auditId", IntegerArgumentType.integer(1))
                    .executes(context -> executeRollback(context, engine))))
            .then(Commands.literal("dryrun")
                .then(Commands.argument("auditId", IntegerArgumentType.integer(1))
                    .executes(context -> executeDryRun(context, engine))))
            .then(Commands.literal("timeline")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> executeTimeline(context, engine))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAutomation(GovernanceEngine engine) {
        return Commands.literal("automation")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .then(Commands.literal("status")
                .executes(context -> executeAutomationStatus(context, engine)))
            .then(Commands.literal("lockdown")
                .then(Commands.literal("activate")
                    .then(Commands.argument("reason", MessageArgument.message())
                        .executes(context -> executeLockdown(context, engine, true))))
                .then(Commands.literal("deactivate")
                    .executes(context -> executeLockdown(context, engine, false))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildLimits(GovernanceEngine engine) {
        return Commands.literal("limits")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeLimitsStatus(context, engine))
            .then(Commands.literal("set")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(-1.0))
                        .executes(context -> executeLimitsSet(context, engine)))))
            .then(Commands.literal("reset")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> executeLimitsReset(context, engine))))
            .then(Commands.literal("status")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> executeLimitsPlayerStatus(context, engine))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDiscord(GovernanceEngine engine) {
        return Commands.literal("discord")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeDiscordStatus(context, engine))
            .then(Commands.literal("set")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("category", StringArgumentType.word())
                    .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(context -> executeDiscordSet(context, engine)))))
            .then(Commands.literal("remove")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("category", StringArgumentType.word())
                    .executes(context -> executeDiscordRemove(context, engine))))
            .then(Commands.literal("test")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .executes(context -> executeDiscordTest(context, engine, null))
                .then(Commands.argument("category", StringArgumentType.word())
                    .executes(context -> executeDiscordTest(context, engine, StringArgumentType.getString(context, "category")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildEvent(GovernanceEngine engine) {
        return Commands.literal("event")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .then(Commands.literal("list")
                .executes(context -> executeEventList(context, engine)))
            .then(Commands.literal("create")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("type", StringArgumentType.word())
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> executeEventCreate(context, engine)))))
            .then(Commands.literal("cancel")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(context -> executeEventCancel(context, engine))))
            .then(Commands.literal("info")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(context -> executeEventInfo(context, engine))))
            .then(Commands.literal("history")
                .executes(context -> executeEventHistory(context, engine)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildProfile(GovernanceEngine engine) {
        return Commands.literal("profile")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(context -> executeProfile(context, engine)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPolicy(GovernanceEngine engine) {
        return Commands.literal("policy")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .then(Commands.literal("list")
                .executes(context -> executePolicyList(context, engine)))
            .then(Commands.literal("save")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executePolicySave(context, engine, StringArgumentType.getString(context, "name"), null, null))
                    .then(Commands.argument("displayName", StringArgumentType.word())
                        .executes(context -> executePolicySave(context, engine, StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "displayName"), null))
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                            .executes(context -> executePolicySave(context, engine, StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "displayName"), StringArgumentType.getString(context, "description")))))))
            .then(Commands.literal("load")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executePolicyLoad(context, engine))))
            .then(Commands.literal("preview")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executePolicyPreview(context, engine))))
            .then(Commands.literal("delete")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executePolicyDelete(context, engine))))
            .then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executePolicyInfo(context, engine))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRules(GovernanceEngine engine) {
        return Commands.literal("rules")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .then(Commands.literal("list")
                .executes(context -> executeRulesList(context, engine)))
            .then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executeRuleInfo(context, engine))))
            .then(Commands.literal("add")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("cooldown", StringArgumentType.word())
                        .executes(context -> executeRuleAdd(context, engine)))))
            .then(Commands.literal("enable")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executeRuleToggle(context, engine, true))))
            .then(Commands.literal("disable")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executeRuleToggle(context, engine, false))))
            .then(Commands.literal("delete")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> executeRuleDelete(context, engine))))
            .then(Commands.literal("add-condition")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("rule", StringArgumentType.word())
                    .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                            .executes(context -> executeRuleAddCondition(context, engine))))))
            .then(Commands.literal("add-action")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("rule", StringArgumentType.word())
                    .then(Commands.argument("type", StringArgumentType.word())
                        .executes(context -> executeRuleAddAction(context, engine, null, null))
                        .then(Commands.argument("key", StringArgumentType.word())
                            .executes(context -> executeRuleAddAction(context, engine, StringArgumentType.getString(context, "key"), null))
                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(context -> executeRuleAddAction(context, engine, StringArgumentType.getString(context, "key"), StringArgumentType.getString(context, "value"))))))))
            .then(Commands.literal("remove-condition")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("rule", StringArgumentType.word())
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(context -> executeRuleRemoveCondition(context, engine)))))
            .then(Commands.literal("remove-action")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("rule", StringArgumentType.word())
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(context -> executeRuleRemoveAction(context, engine)))))
            .then(Commands.literal("set-cooldown")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .then(Commands.argument("rule", StringArgumentType.word())
                    .then(Commands.argument("duration", StringArgumentType.word())
                        .executes(context -> executeRuleSetCooldown(context, engine)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSimulation(GovernanceEngine engine) {
        return Commands.literal("simulation")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .executes(context -> executeSimulationStatus(context, engine))
            .then(Commands.literal("true")
                .executes(context -> executeSimulationToggle(context, engine, true)))
            .then(Commands.literal("false")
                .executes(context -> executeSimulationToggle(context, engine, false)))
            .then(Commands.literal("insight")
                .executes(context -> executeSimulationInsight(context, engine)))
            .then(Commands.literal("refresh")
                .executes(context -> executeSimulationRefresh(context, engine)));
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Governance Status \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  License: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.isPremiumEnabled() ? "ACTIVE" : "FREE MODE", engine.isPremiumEnabled() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Intervention: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("intervention.enabled", true) ? "ENABLED" : "DISABLED", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Taxation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("taxation.enabled", false) ? "ENABLED" : "DISABLED", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Audit Logging: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("audit.enabled", true) ? "ENABLED" : "DISABLED", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Automation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("automation.enabled", false) ? "ENABLED" : "DISABLED", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Trading: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getInterventionManager().isTradingLocked() ? "LOCKED" : "OPEN", engine.getInterventionManager().isTradingLocked() ? ChatFormatting.RED : ChatFormatting.GREEN)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Frozen Accounts: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(engine.getAccountFreezer().getFrozenCount()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Emergency Lockdown: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getAutomator().isLockdownActive() ? "ACTIVE" : "INACTIVE", engine.getAutomator().isLockdownActive() ? ChatFormatting.RED : ChatFormatting.GREEN)));
        SimulationEngine simEngine = engine.getSimulationEngine();
        if (simEngine != null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(simEngine.isRunning() ? "RUNNING" : "STOPPED", simEngine.isRunning() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("DISABLED", ChatFormatting.DARK_GRAY)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeLicense(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        LicenseVerifier verifier = engine.getLicenseVerifier();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 License Status \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        if (verifier.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Status: VERIFIED", ChatFormatting.GREEN));
            if (verifier.getLicenseeName() != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Licensed to: " + verifier.getLicenseeName(), ChatFormatting.WHITE));
            }
            if (verifier.getExpiryDate() != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Expires: " + String.valueOf(verifier.getExpiryDate()), ChatFormatting.WHITE));
            }
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Status: " + verifier.getState().name(), ChatFormatting.RED));
            if (verifier.getErrorMessage() != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Error: " + verifier.getErrorMessage(), ChatFormatting.DARK_RED));
            }
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeFingerprint(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String fingerprint = LicenseVerifier.computeServerFingerprint();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("Server fingerprint: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(fingerprint, ChatFormatting.AQUA)));
        return 1;
    }

    private static int executeInterventionAdd(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        double amount = DoubleArgumentType.getDouble(context, (String)"amount");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getInterventionManager().addBalance(adminUuid, source.getTextName(), player.getUUID(), player.getName().getString(), amount).thenAccept(newBalance -> source.getServer().execute(() -> {
            if (newBalance >= 0.0) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Added " + amount + " to " + player.getName().getString() + ". New balance: " + String.format("%.2f", newBalance), ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to add balance. Account may be frozen.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeInterventionRemove(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        double amount = DoubleArgumentType.getDouble(context, (String)"amount");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getInterventionManager().removeBalance(adminUuid, source.getTextName(), player.getUUID(), player.getName().getString(), amount).thenAccept(newBalance -> source.getServer().execute(() -> {
            if (newBalance >= 0.0) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Removed " + amount + " from " + player.getName().getString() + ". New balance: " + String.format("%.2f", newBalance), ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to remove balance. Insufficient funds or account frozen.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeInterventionSet(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        double amount = DoubleArgumentType.getDouble(context, (String)"amount");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getInterventionManager().setBalance(adminUuid, source.getTextName(), player.getUUID(), player.getName().getString(), amount).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Set " + player.getName().getString() + "'s balance to " + String.format("%.2f", amount), ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to set balance.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeFreeze(CommandContext<CommandSourceStack> context, GovernanceEngine engine, int durationMinutes) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getAccountFreezer().freeze(player.getUUID(), "Admin freeze", adminUuid, durationMinutes);
        Object durationStr = durationMinutes > 0 ? durationMinutes + " minutes" : "permanent";
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Frozen " + player.getName().getString() + " (" + (String)durationStr + ")", ChatFormatting.YELLOW));
        return 1;
    }

    private static int executeUnfreeze(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getAccountFreezer().unfreeze(player.getUUID(), adminUuid);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unfrozen " + player.getName().getString(), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeSuspiciousList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        Map<UUID, String> accounts = engine.getInterventionManager().getSuspiciousAccounts();
        if (accounts.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No suspicious accounts marked.", ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Suspicious Accounts:", ChatFormatting.YELLOW));
            accounts.forEach((uuid, reason) -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    " + uuid.toString().substring(0, 8) + "... \u2014 " + reason, ChatFormatting.WHITE)));
        }
        return 1;
    }

    private static int executeSuspiciousMark(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        String reason = MessageArgument.getMessage(context, (String)"reason").getString();
        engine.getInterventionManager().markSuspicious(player.getUUID(), reason);
        GovernanceCommand.sendFeedback((CommandSourceStack)context.getSource(), (Component)GovernanceCommand.styled("  Marked " + player.getName().getString() + " as suspicious: " + reason, ChatFormatting.YELLOW));
        return 1;
    }

    private static int executeSuspiciousUnmark(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        engine.getInterventionManager().unmarkSuspicious(player.getUUID());
        GovernanceCommand.sendFeedback((CommandSourceStack)context.getSource(), (Component)GovernanceCommand.styled("  Removed suspicious marking from " + player.getName().getString(), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeLockTrading(CommandContext<CommandSourceStack> context, GovernanceEngine engine, boolean lock) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        if (lock) {
            String reason = MessageArgument.getMessage(context, (String)"reason").getString();
            engine.getInterventionManager().lockTrading(adminUuid, source.getTextName(), reason);
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Trading LOCKED: " + reason, ChatFormatting.RED));
        } else {
            engine.getInterventionManager().unlockTrading(adminUuid, source.getTextName());
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Trading UNLOCKED", ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int executeTaxRates(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Tax Rates \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transfer: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f%%", engine.getConfig().getDouble("taxation.transfer.rate", 0.0) * 100.0), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Auction: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f%%", engine.getConfig().getDouble("taxation.auction.rate", 0.05) * 100.0), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Shop: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f%%", engine.getConfig().getDouble("taxation.shop.rate", 0.0) * 100.0), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Wealth Decay: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("taxation.wealth-decay.enabled", false) ? "ON" : "OFF", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeTaxSet(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String configKey;
        String type = StringArgumentType.getString(context, (String)"type");
        double rate = DoubleArgumentType.getDouble(context, (String)"rate");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        switch (type.toLowerCase()) {
            case "transfer": {
                configKey = "taxation.transfer.rate";
                break;
            }
            case "auction": {
                configKey = "taxation.auction.rate";
                break;
            }
            case "shop": {
                configKey = "taxation.shop.rate";
                break;
            }
            default: {
                String string = configKey = null;
            }
        }
        if (configKey == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unknown tax type: " + type + ". Use: transfer, auction, shop", ChatFormatting.RED));
            return 0;
        }
        if (!Double.isFinite(rate) || rate < 0.0 || rate > 1.0) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid rate: " + rate + ". Must be between 0.0 and 1.0 (0% - 100%).", ChatFormatting.RED));
            return 0;
        }
        String beforeValue = engine.getConfig().getString(configKey, "");
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        engine.getConfig().set(configKey, String.valueOf(rate));
        engine.getAuditLogger().logConfigChange(adminUuid, source.getTextName(), configKey, beforeValue, String.valueOf(rate));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + type + " tax rate set to " + String.format("%.1f%%", rate * 100.0), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeBracketsList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        Map<Double, Double> brackets = engine.getTaxEngine().getBrackets();
        if (brackets.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No progressive tax brackets configured.", ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Progressive Tax Brackets:", ChatFormatting.YELLOW));
            brackets.forEach((threshold, rate) -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    Balance >= " + String.format("%.0f", threshold) + ": " + String.format("%.1f%%", rate * 100.0), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  (applied to transfers on the sender when taxation is enabled; audit type: PROGRESSIVE)", ChatFormatting.DARK_GRAY));
        }
        return 1;
    }

    private static int executeBracketAdd(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        double threshold = DoubleArgumentType.getDouble(context, (String)"threshold");
        double rate = DoubleArgumentType.getDouble(context, (String)"rate");
        if (!Double.isFinite(threshold) || threshold < 0.0 || !Double.isFinite(rate) || rate <= 0.0 || rate > 1.0) {
            GovernanceCommand.sendFeedback((CommandSourceStack)context.getSource(), (Component)GovernanceCommand.styled("  Invalid bracket: threshold must be >= 0 and rate must be between 0.0 (exclusive) and 1.0.", ChatFormatting.RED));
            return 0;
        }
        engine.getTaxEngine().addBracket(threshold, rate);
        GovernanceCommand.sendFeedback((CommandSourceStack)context.getSource(), (Component)GovernanceCommand.styled("  Added bracket: >= " + String.format("%.0f", threshold) + " at " + String.format("%.1f%%", rate * 100.0), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeBracketRemove(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        double threshold = DoubleArgumentType.getDouble(context, (String)"threshold");
        engine.getTaxEngine().removeBracket(threshold);
        GovernanceCommand.sendFeedback((CommandSourceStack)context.getSource(), (Component)GovernanceCommand.styled("  Removed bracket at threshold " + String.format("%.0f", threshold), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeAuditRecent(CommandContext<CommandSourceStack> context, GovernanceEngine engine, int count) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        List<AuditDatabase.AuditEntry> entries = engine.getAuditDatabase().getRecentAuditLogs(count);
        if (entries.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No audit entries found.", ChatFormatting.GRAY));
            return 1;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Recent Audit (" + entries.size() + ") \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        for (AuditDatabase.AuditEntry entry : entries) {
            String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.timestamp), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            ChatFormatting catColor = switch (entry.category) {
                case "INTERVENTION" -> ChatFormatting.RED;
                case "TAXATION" -> ChatFormatting.YELLOW;
                case "RECOVERY" -> ChatFormatting.AQUA;
                case "AUTOMATION" -> ChatFormatting.LIGHT_PURPLE;
                default -> ChatFormatting.WHITE;
            };
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  #" + entry.id + " ", ChatFormatting.DARK_GRAY).append((Component)GovernanceCommand.styled("[" + entry.category + "] ", catColor)).append((Component)GovernanceCommand.styled(entry.action + " ", ChatFormatting.WHITE)).append((Component)GovernanceCommand.styled(entry.targetName != null ? entry.targetName : "", ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled(" " + time, ChatFormatting.DARK_GRAY)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeAuditSearch(CommandContext<CommandSourceStack> context, GovernanceEngine engine, String searchType) throws CommandSyntaxException {
        List<AuditDatabase.AuditEntry> entries;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if ("player".equals(searchType)) {
            ServerPlayer player = EntityArgument.getPlayer(context, (String)"query");
            entries = engine.getAuditDatabase().searchByTarget(player.getUUID(), 20);
        } else {
            String category = StringArgumentType.getString(context, (String)"query");
            entries = engine.getAuditDatabase().searchByCategory(category, 20);
        }
        if (entries.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No matching audit entries found.", ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Found " + entries.size() + " entries:", ChatFormatting.WHITE));
            for (AuditDatabase.AuditEntry entry : entries) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  #" + entry.id + " " + entry.action + " \u2014 " + entry.targetName, ChatFormatting.GRAY));
            }
        }
        return 1;
    }

    /**
     * /governance audit export csv [days] - writes the audit trail for the
     * given window (default 7, max 365) to
     * <config dir>/solidus-governance/exports/audit_export_<stamp>.csv.
     * Synchronous by design like the other audit readers; the write itself
     * is a single bounded file capped at AuditDatabase.MAX_EXPORT_ROWS.
     */
    private static int executeAuditExport(CommandContext<CommandSourceStack> context, GovernanceEngine engine, int days) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        long sinceMs = System.currentTimeMillis() - (long)days * 24L * 60L * 60L * 1000L;
        List<AuditDatabase.AuditEntry> entries = engine.getAuditDatabase().getAuditLogsSince(sinceMs);
        if (entries.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No audit entries in the last " + days + " day(s) - nothing to export.", ChatFormatting.GRAY));
            return 1;
        }
        try {
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            Path dir = engine.getAuditDatabase().getExportsDir();
            Path file = dir.resolve("audit_export_" + stamp + ".csv");
            int n = 2;
            while (Files.exists(file) && n < 100) {
                file = dir.resolve("audit_export_" + stamp + "_" + n + ".csv");
                ++n;
            }
            AuditCsvExporter.writeCsvFile(entries, file);
            long sizeKb = Math.max(1L, Files.size(file) / 1024L);
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Exported " + entries.size() + " audit entries from the last " + days + " day(s) to ", ChatFormatting.GREEN).append((Component)GovernanceCommand.styled("solidus-governance/exports/" + file.getFileName().toString(), ChatFormatting.AQUA)).append((Component)GovernanceCommand.styled(" (" + sizeKb + " KB)", ChatFormatting.GRAY)));
        }
        catch (IOException e) {
            SolidusGovernanceMod.LOGGER.error("Audit CSV export failed", (Throwable)e);
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Export failed - check the server log for details.", ChatFormatting.RED));
        }
        return 1;
    }

    private static int executeSnapshotCreate(CommandContext<CommandSourceStack> context, GovernanceEngine engine, String name) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Creating economy snapshot...", ChatFormatting.YELLOW));
        ((CompletableFuture)engine.getSnapshotManager().createSnapshot(name).thenAccept(path -> source.getServer().execute(() -> {
            if (path != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Snapshot created: " + String.valueOf(path.getFileName()), ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to create snapshot. Check logs for details.", ChatFormatting.RED));
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to create snapshot: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executeSnapshotList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        List<String> snapshots = engine.getSnapshotManager().listSnapshots();
        if (snapshots.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No snapshots available.", ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Available Snapshots:", ChatFormatting.YELLOW));
            for (String snapshot : snapshots) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    " + snapshot, ChatFormatting.WHITE));
            }
        }
        return 1;
    }

    private static int executeRollback(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        int auditId = IntegerArgumentType.getInteger(context, (String)"auditId");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rolling back audit #" + auditId + "...", ChatFormatting.YELLOW));
        engine.getRollbackEngine().rollbackById(adminUuid, source.getTextName(), auditId).thenAccept(result -> source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + result, ChatFormatting.WHITE))));
        return 1;
    }

    private static int executeDryRun(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        int auditId = IntegerArgumentType.getInteger(context, (String)"auditId");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        engine.getRollbackEngine().dryRunRollback(null, auditId).thenAccept(result -> source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + result, ChatFormatting.YELLOW))));
        return 1;
    }

    private static int executeTimeline(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        List<AuditDatabase.AuditEntry> timeline = engine.getRollbackEngine().getTransactionTimeline(player.getUUID(), 15);
        if (timeline.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No transactions found for " + player.getName().getString(), ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Timeline for " + player.getName().getString() + ":", ChatFormatting.YELLOW));
            for (AuditDatabase.AuditEntry entry : timeline) {
                String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.timestamp), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                Object change = "";
                if (entry.beforeValue != null && entry.afterValue != null) {
                    try {
                        double before = Double.parseDouble(entry.beforeValue);
                        double after = Double.parseDouble(entry.afterValue);
                        double diff = after - before;
                        change = (diff >= 0.0 ? "+" : "") + String.format("%.2f", diff);
                    }
                    catch (NumberFormatException before) {
                        // empty catch block
                    }
                }
                ChatFormatting changeColor = ((String)change).startsWith("+") ? ChatFormatting.GREEN : (((String)change).startsWith("-") ? ChatFormatting.RED : ChatFormatting.GRAY);
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + time + " ", ChatFormatting.DARK_GRAY).append((Component)GovernanceCommand.styled(entry.action + " ", ChatFormatting.WHITE)).append((Component)GovernanceCommand.styled((String)change, changeColor)));
            }
        }
        return 1;
    }

    private static int executeAutomationStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Automation Status \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Automation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("automation.enabled", false) ? "ON" : "OFF", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Anti-Inflation: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("automation.anti-inflation.enabled", false) ? "ON" : "OFF", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Wealth Caps: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getConfig().getBool("automation.wealth-cap.enabled", false) ? "ON" : "OFF", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Emergency Lockdown: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(engine.getAutomator().isLockdownActive() ? "ACTIVE" : "INACTIVE", engine.getAutomator().isLockdownActive() ? ChatFormatting.RED : ChatFormatting.GREEN)));
        if (engine.getAutomator().isLockdownActive() && engine.getAutomator().getLockdownReason() != null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Lockdown Reason: " + engine.getAutomator().getLockdownReason(), ChatFormatting.RED));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeLockdown(CommandContext<CommandSourceStack> context, GovernanceEngine engine, boolean activate) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        if (activate) {
            String reason = MessageArgument.getMessage(context, (String)"reason").getString();
            engine.getAutomator().activateLockdown(adminUuid, source.getTextName(), reason);
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  EMERGENCY LOCKDOWN ACTIVATED: " + reason, ChatFormatting.RED));
        } else {
            engine.getAutomator().deactivateLockdown(adminUuid, source.getTextName());
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Emergency lockdown deactivated.", ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int executeLimitsStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        TransactionLimits limits = engine.getTransactionLimits();
        if (limits == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transaction Limits system not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transaction Limits require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        double dailyTransferMax = engine.getConfig().getDouble("limits.transfer.daily-max", -1.0);
        double transferMin = engine.getConfig().getDouble("limits.transfer.min", 0.0);
        double transferMax = engine.getConfig().getDouble("limits.transfer.max", -1.0);
        int dailyAuctionMax = engine.getConfig().getInt("limits.auction.daily-max", -1);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Transaction Limits \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Premium: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("REQUIRED", ChatFormatting.GREEN)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Transfer Limits \u2500\u2500", ChatFormatting.YELLOW));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Daily Max: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(dailyTransferMax < 0.0 ? "UNLIMITED" : String.format("%.2f", dailyTransferMax), dailyTransferMax < 0.0 ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Per-Transaction Min: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2f", transferMin), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Per-Transaction Max: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(transferMax < 0.0 ? "UNLIMITED" : String.format("%.2f", transferMax), transferMax < 0.0 ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Auction Limits \u2500\u2500", ChatFormatting.YELLOW));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Daily Max Listings: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(dailyAuctionMax < 0 ? "UNLIMITED" : String.valueOf(dailyAuctionMax), dailyAuctionMax < 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeLimitsSet(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String configKey;
        String type = StringArgumentType.getString(context, (String)"type");
        double value = DoubleArgumentType.getDouble(context, (String)"value");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        switch (type.toLowerCase()) {
            case "transfer-daily": {
                configKey = "limits.transfer.daily-max";
                break;
            }
            case "transfer-min": {
                configKey = "limits.transfer.min";
                break;
            }
            case "transfer-max": {
                configKey = "limits.transfer.max";
                break;
            }
            case "auction-daily": {
                configKey = "limits.auction.daily-max";
                break;
            }
            default: {
                String string = configKey = null;
            }
        }
        if (configKey == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unknown limit type: " + type, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid types: transfer-daily, transfer-min, transfer-max, auction-daily", ChatFormatting.GRAY));
            return 0;
        }
        String beforeValue = engine.getConfig().getString(configKey, "-1");
        engine.getConfig().set(configKey, String.valueOf(value));
        engine.getAuditLogger().logLimitConfigChange(adminUuid, source.getTextName(), type, beforeValue, String.valueOf(value));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Set " + type + " to " + (value < 0.0 ? "UNLIMITED" : String.format("%.2f", value)), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeLimitsReset(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        TransactionLimits limits = engine.getTransactionLimits();
        if (limits == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transaction Limits system not available.", ChatFormatting.RED));
            return 0;
        }
        limits.resetPlayerLimits(player.getUUID());
        engine.getAuditLogger().logLimitReset(adminUuid, source.getTextName(), player.getUUID());
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Reset daily limits for " + player.getName().getString(), ChatFormatting.GREEN));
        return 1;
    }

    private static int executeLimitsPlayerStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        TransactionLimits limits = engine.getTransactionLimits();
        if (limits == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transaction Limits system not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transaction Limits require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        TransactionLimits.DailyUsageView usage = limits.getPlayerUsage(player.getUUID());
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Limits: " + player.getName().getString() + " \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Date: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(usage.date(), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Transfer Usage \u2500\u2500", ChatFormatting.YELLOW));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Daily Total: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2f", usage.transferTotal()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Remaining: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(usage.remainingTransfer() == Double.MAX_VALUE ? "UNLIMITED" : String.format("%.2f", usage.remainingTransfer()), usage.remainingTransfer() == Double.MAX_VALUE ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Auction Usage \u2500\u2500", ChatFormatting.YELLOW));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Daily Listings: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(usage.auctionCount()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Remaining: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(usage.remainingAuctions() == Integer.MAX_VALUE ? "UNLIMITED" : String.valueOf(usage.remainingAuctions()), usage.remainingAuctions() == Integer.MAX_VALUE ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeDiscordStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        WebhookManager webhookManager = engine.getWebhookManager();
        if (webhookManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        boolean enabled = webhookManager.isDiscordEnabled();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Discord Webhooks \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Enabled: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(enabled ? "YES" : "NO", enabled ? ChatFormatting.GREEN : ChatFormatting.RED)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Queue Size: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(webhookManager.getQueueSize()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Webhook URLs \u2500\u2500", ChatFormatting.YELLOW));
        for (String category : WebhookManager.VALID_CATEGORIES) {
            String url = webhookManager.getWebhookUrl(category);
            String masked = GovernanceCommand.maskUrl(url);
            ChatFormatting urlColor = url.isBlank() ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN;
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + category + ": ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(masked, urlColor)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Alert Thresholds \u2500\u2500", ChatFormatting.YELLOW));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Intervention: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.0f", webhookManager.getInterventionThreshold()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeDiscordSet(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String category = StringArgumentType.getString(context, (String)"category").toLowerCase();
        String url = StringArgumentType.getString(context, (String)"url");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        WebhookManager webhookManager = engine.getWebhookManager();
        if (webhookManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        if (!WebhookManager.VALID_CATEGORIES.contains(category)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid category: " + category, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid categories: " + String.join((CharSequence)", ", WebhookManager.VALID_CATEGORIES), ChatFormatting.GRAY));
            return 0;
        }
        if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid webhook URL. Must start with https://discord.com/api/webhooks/ or https://discordapp.com/api/webhooks/", ChatFormatting.RED));
            return 0;
        }
        webhookManager.setWebhookUrl(category, url);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord webhook set for '" + category + "': " + GovernanceCommand.maskUrl(url), ChatFormatting.GREEN));
        if (!webhookManager.isDiscordEnabled()) {
            engine.getConfig().set("discord.enabled", "true");
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord webhooks auto-enabled.", ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int executeDiscordRemove(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String category = StringArgumentType.getString(context, (String)"category").toLowerCase();
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        WebhookManager webhookManager = engine.getWebhookManager();
        if (webhookManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        if (!WebhookManager.VALID_CATEGORIES.contains(category)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid category: " + category, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid categories: " + String.join((CharSequence)", ", WebhookManager.VALID_CATEGORIES), ChatFormatting.GRAY));
            return 0;
        }
        webhookManager.removeWebhookUrl(category);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord webhook removed for '" + category + "'", ChatFormatting.GREEN));
        return 1;
    }

    private static int executeDiscordTest(CommandContext<CommandSourceStack> context, GovernanceEngine engine, String category) throws CommandSyntaxException {
        String targetCategory;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        WebhookManager webhookManager = engine.getWebhookManager();
        if (webhookManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks not available.", ChatFormatting.RED));
            return 0;
        }
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord Webhooks require a premium license.", ChatFormatting.YELLOW));
            return 0;
        }
        if (!webhookManager.isDiscordEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Discord webhooks are not enabled. Set a webhook URL first.", ChatFormatting.RED));
            return 0;
        }
        String string = targetCategory = category != null ? category.toLowerCase() : "default";
        if (!WebhookManager.VALID_CATEGORIES.contains(targetCategory)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid category: " + targetCategory, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid categories: " + String.join((CharSequence)", ", WebhookManager.VALID_CATEGORIES), ChatFormatting.GRAY));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Sending test webhook to '" + targetCategory + "'...", ChatFormatting.YELLOW));
        ((CompletableFuture)webhookManager.sendAlert(targetCategory.toUpperCase(), "Test Alert", "This is a test message from Solidus Governance.").thenAccept(v -> source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Test webhook sent to '" + targetCategory + "'.", ChatFormatting.GREEN))))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to send test webhook: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return "(not set)";
        }
        int lastSlash = url.lastIndexOf(47);
        if (lastSlash > 0 && lastSlash < url.length() - 4) {
            return url.substring(0, lastSlash + 5) + "***";
        }
        return "***";
    }

    private static int executeEventList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        EventManager eventManager = engine.getEventManager();
        if (eventManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Events: Requires premium license", ChatFormatting.RED));
            return 0;
        }
        List<EconomyEvent> events = eventManager.getActiveEvents();
        if (events.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No active economy events.", ChatFormatting.GRAY));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Active Events (" + events.size() + ") \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
            for (EconomyEvent event : events) {
                ChatFormatting typeColor = switch (event.getType()) {
                    case "DOUBLE_SHOP" -> ChatFormatting.GREEN;
                    case "TAX_HOLIDAY" -> ChatFormatting.YELLOW;
                    case "INFLATION_SALE" -> ChatFormatting.AQUA;
                    case "BONUS_CURRENCY" -> ChatFormatting.LIGHT_PURPLE;
                    default -> ChatFormatting.WHITE;
                };
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + event.getId().substring(0, 8) + "... ", ChatFormatting.DARK_GRAY).append((Component)GovernanceCommand.styled("[" + event.getType() + "] ", typeColor)).append((Component)GovernanceCommand.styled(event.getName() + " ", ChatFormatting.WHITE)).append((Component)GovernanceCommand.styled("x" + event.getModifier() + " ", ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled(event.getRemainingDurationString(), ChatFormatting.YELLOW)));
            }
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        }
        return 1;
    }

    private static int executeEventCreate(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        double modifier;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        EventManager eventManager = engine.getEventManager();
        if (eventManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Events: Requires premium license", ChatFormatting.RED));
            return 0;
        }
        String type = StringArgumentType.getString(context, (String)"type");
        String nameAndArgs = StringArgumentType.getString(context, (String)"name");
        String[] parts = nameAndArgs.trim().split("\\s+");
        if (parts.length < 3) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Usage: /governance event create <type> <modifier> <duration> <name>", ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Example: /governance event create double-shop 2.0 48h \"Double Weekend\"", ChatFormatting.GRAY));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Types: double-shop, tax-holiday, inflation-sale, bonus-currency", ChatFormatting.GRAY));
            return 0;
        }
        try {
            modifier = Double.parseDouble(parts[0]);
        }
        catch (NumberFormatException e) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid modifier: " + parts[0] + ". Must be a number.", ChatFormatting.RED));
            return 0;
        }
        String duration = parts[1];
        long durationMillis = EventManager.parseDuration(duration);
        if (durationMillis <= 0L) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid duration: " + duration + ". Use format: 30m, 2h, 1d, 48h", ChatFormatting.RED));
            return 0;
        }
        String normalizedType = EventManager.normalizeEventType(type);
        if (normalizedType == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unknown event type: " + type, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Available types: double-shop, tax-holiday, inflation-sale, bonus-currency, custom", ChatFormatting.GRAY));
            return 0;
        }
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 2; i < parts.length; ++i) {
            if (i > 2) {
                nameBuilder.append(" ");
            }
            nameBuilder.append(parts[i]);
        }
        String name = nameBuilder.toString().replace("\"", "");
        if (name.isBlank()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Event name cannot be empty.", ChatFormatting.RED));
            return 0;
        }
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Creating economy event...", ChatFormatting.YELLOW));
        ((CompletableFuture)eventManager.createEvent(name, normalizedType, modifier, duration, adminUuid, source.getTextName()).thenAccept(event -> source.getServer().execute(() -> {
            if (event != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Event created: ", ChatFormatting.GREEN).append((Component)GovernanceCommand.styled(event.getName(), ChatFormatting.WHITE)).append((Component)GovernanceCommand.styled(" [" + event.getType() + " x" + event.getModifier() + "]", ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled(" (" + event.getTotalDurationString() + ")", ChatFormatting.YELLOW)));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Event ID: " + event.getId(), ChatFormatting.DARK_GRAY));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to create event. Check logs for details. (Premium required?)", ChatFormatting.RED));
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to create event: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executeEventCancel(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        EventManager eventManager = engine.getEventManager();
        if (eventManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Events: Requires premium license", ChatFormatting.RED));
            return 0;
        }
        String eventId = StringArgumentType.getString(context, (String)"id");
        eventManager.cancelEvent(eventId).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Event " + eventId.substring(0, Math.min(8, eventId.length())) + "... cancelled and reverted.", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to cancel event. Event not found or already inactive.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    /*
     * WARNING - void declaration
     */
    private static int executeEventInfo(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        EventManager eventManager = engine.getEventManager();
        if (eventManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Events: Requires premium license", ChatFormatting.RED));
            return 0;
        }
        String eventId = StringArgumentType.getString(context, (String)"id");
        EconomyEvent event = eventManager.getEvent(eventId);
        if (event == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Event not found: " + eventId, ChatFormatting.RED));
            return 0;
        }
        String startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getStartTime()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getEndTime()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String string = event.getType();
        int typeIndex = -1;
        switch (string) {
            case "DOUBLE_SHOP" -> typeIndex = 0;
            case "TAX_HOLIDAY" -> typeIndex = 1;
            case "INFLATION_SALE" -> typeIndex = 2;
            case "BONUS_CURRENCY" -> typeIndex = 3;
            default -> typeIndex = -1;
        }
        ChatFormatting typeColor = switch (typeIndex) {
            case 0 -> ChatFormatting.GREEN;
            case 1 -> ChatFormatting.YELLOW;
            case 2 -> ChatFormatting.AQUA;
            case 3 -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Event Details \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Name: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.getName(), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  ID: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.getId(), ChatFormatting.DARK_GRAY)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Type: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.getType(), typeColor)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Modifier: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("x" + event.getModifier(), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Status: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.isActive() ? "ACTIVE" : "INACTIVE", event.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Start: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(startTime, ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  End: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(endTime, ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Remaining: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.isActive() ? event.getRemainingDurationString() : "expired", ChatFormatting.YELLOW)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Created by: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(event.getCreatorName(), ChatFormatting.WHITE)));
        if (event.getOriginalValues() != null && !event.getOriginalValues().isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Modified Config:", ChatFormatting.GRAY));
            for (Map.Entry entry : event.getOriginalValues().entrySet()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    " + (String)entry.getKey() + " (was: " + (String)entry.getValue() + ")", ChatFormatting.DARK_GRAY));
            }
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeEventHistory(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        EventManager eventManager = engine.getEventManager();
        if (eventManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Events: Requires premium license", ChatFormatting.RED));
            return 0;
        }
        List<EconomyEvent> events = eventManager.getAllEvents();
        if (events.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No economy events found.", ChatFormatting.GRAY));
            return 1;
        }
        int count = Math.min(events.size(), 10);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Event History (last " + count + ") \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        for (int i = 0; i < count; ++i) {
            EconomyEvent event = events.get(i);
            String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getStartTime()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            ChatFormatting typeColor = switch (event.getType()) {
                case "DOUBLE_SHOP" -> ChatFormatting.GREEN;
                case "TAX_HOLIDAY" -> ChatFormatting.YELLOW;
                case "INFLATION_SALE" -> ChatFormatting.AQUA;
                case "BONUS_CURRENCY" -> ChatFormatting.LIGHT_PURPLE;
                default -> ChatFormatting.WHITE;
            };
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + event.getId().substring(0, 8) + "... ", ChatFormatting.DARK_GRAY).append((Component)GovernanceCommand.styled("[" + event.getType() + "] ", typeColor)).append((Component)GovernanceCommand.styled(event.getName() + " ", ChatFormatting.WHITE)).append((Component)GovernanceCommand.styled(event.isActive() ? "ACTIVE" : "EXPIRED", event.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED)).append((Component)GovernanceCommand.styled(" " + time, ChatFormatting.DARK_GRAY)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeProfile(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, (String)"player");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (engine.getProfileGenerator() == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Player profiles are not available.", ChatFormatting.RED));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Loading profile for " + player.getName().getString() + "...", ChatFormatting.YELLOW));
        ((CompletableFuture)engine.getProfileGenerator().generateProfile(player.getUUID(), player.getName().getString()).thenAccept(profile -> source.getServer().execute(() -> {
            boolean isPremium = engine.isPremiumEnabled();
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Economy Profile: " + player.getName().getString() + " \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Balance: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%,.2f", profile.getBalance()), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rank: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("#" + profile.getRank() + " of " + profile.getTotalPlayers(), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Account Status: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(profile.isFrozen() ? "FROZEN" : "ACTIVE", profile.isFrozen() ? ChatFormatting.RED : ChatFormatting.GREEN)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Suspicious: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(profile.isSuspicious() ? "YES" : "NO", profile.isSuspicious() ? ChatFormatting.RED : ChatFormatting.GREEN)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Frozen: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(profile.isFrozen() ? "YES" : "NO", profile.isFrozen() ? ChatFormatting.RED : ChatFormatting.GREEN)));
            if (isPremium) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Last 7 Days \u2500\u2500", ChatFormatting.YELLOW));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Income: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("+" + String.format("%,.2f", profile.getIncome7d()), ChatFormatting.GREEN)));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Expenses: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("-" + String.format("%,.2f", profile.getExpenses7d()), ChatFormatting.RED)));
                String netSign = profile.getNetChange7d() >= 0.0 ? "+" : "";
                ChatFormatting netColor = profile.getNetChange7d() >= 0.0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Net Change: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(netSign + String.format("%,.2f", profile.getNetChange7d()), netColor)).append((Component)GovernanceCommand.styled(" (" + String.format("%+.1f%%", profile.getNetChangePercent7d()) + ")", netColor)));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Tax Paid: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%,.2f", profile.getTaxPaid7d()), ChatFormatting.WHITE)));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Transactions: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(profile.getTransactionCount7d()), ChatFormatting.WHITE)));
                if (!profile.getFlags().isEmpty()) {
                    GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Flags \u2500\u2500", ChatFormatting.YELLOW));
                    for (String flag : profile.getFlags()) {
                        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u26a0 " + flag, ChatFormatting.YELLOW));
                    }
                }
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  \u2500\u2500 Weekly stats and flags require premium \u2500\u2500", ChatFormatting.DARK_GRAY));
            }
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to generate profile: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executePolicyList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        PolicyManager policyManager = engine.getPolicyManager();
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        List<EconomyPolicy> policies = policyManager.listPolicies();
        if (policies.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No economy policies saved.", ChatFormatting.GRAY));
            return 1;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Economy Policies (" + policies.size() + ") \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        for (EconomyPolicy policy : policies) {
            String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(policy.getCreatedAt()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            ChatFormatting nameColor = policy.isAutosave() ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE;
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + policy.getName(), nameColor).append((Component)GovernanceCommand.styled(" \u2014 " + policy.getDisplayName(), ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled(" (" + policy.getConfigValues().size() + " keys, " + time + ")", ChatFormatting.DARK_GRAY)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executePolicySave(CommandContext<CommandSourceStack> context, GovernanceEngine engine, String name, String displayName, String description) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        PolicyManager policyManager = engine.getPolicyManager();
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        if (!EconomyPolicy.isValidName(name)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid policy name: '" + name + "'. Use lowercase alphanumeric + hyphens, 2-32 chars.", ChatFormatting.RED));
            return 0;
        }
        if (name.startsWith("_autosave_")) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy names starting with '_autosave_' are reserved.", ChatFormatting.RED));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Saving policy '" + name + "'...", ChatFormatting.YELLOW));
        ((CompletableFuture)policyManager.savePolicy(name, displayName, description, adminUuid, source.getTextName()).thenAccept(policy -> source.getServer().execute(() -> {
            if (policy != null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' saved successfully (" + policy.getConfigValues().size() + " config keys captured).", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to save policy. Check logs for details.", ChatFormatting.RED));
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to save policy: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executePolicyLoad(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, (String)"name");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        UUID adminUuid = GovernanceCommand.resolveAdminUuid(source);
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        PolicyManager policyManager = engine.getPolicyManager();
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        EconomyPolicy policy = policyManager.getPolicy(name);
        if (policy == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' not found.", ChatFormatting.RED));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Loading policy '" + policy.getDisplayName() + "'...", ChatFormatting.YELLOW));
        ((CompletableFuture)policyManager.loadPolicy(name, adminUuid, source.getTextName()).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + policy.getDisplayName() + "' loaded successfully. An auto-save of previous config was created.", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to load policy. Check logs for details.", ChatFormatting.RED));
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to load policy: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executePolicyPreview(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, (String)"name");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        PolicyManager policyManager = engine.getPolicyManager();
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        EconomyPolicy policy = policyManager.getPolicy(name);
        if (policy == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' not found.", ChatFormatting.RED));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Previewing policy '" + policy.getDisplayName() + "'...", ChatFormatting.YELLOW));
        ((CompletableFuture)policyManager.previewPolicy(name).thenAccept(diff -> source.getServer().execute(() -> {
            if (diff == null) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' not found.", ChatFormatting.RED));
                return;
            }
            if (diff.isEmpty()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No changes \u2014 policy matches current config exactly.", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Changes that would be applied (" + diff.size() + " keys):", ChatFormatting.YELLOW));
                for (Map.Entry entry : diff.entrySet()) {
                    GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    " + (String)entry.getKey(), ChatFormatting.WHITE).append((Component)GovernanceCommand.styled(": " + ((String[])entry.getValue())[0], ChatFormatting.RED).append((Component)GovernanceCommand.styled(" \u2192 ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(((String[])entry.getValue())[1], ChatFormatting.GREEN)))));
                }
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to preview policy: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executePolicyDelete(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, (String)"name");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        PolicyManager policyManager = engine.getPolicyManager();
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        ((CompletableFuture)policyManager.deletePolicy(name).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' deleted.", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' not found.", ChatFormatting.RED));
            }
        }))).exceptionally(ex -> {
            source.getServer().execute(() -> GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to delete policy: " + ((Throwable)ex).getMessage(), ChatFormatting.RED)));
            return null;
        });
        return 1;
    }

    private static int executePolicyInfo(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        Map<String, String> diff;
        String name = StringArgumentType.getString(context, (String)"name");
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Economy Policies requires a premium license.", ChatFormatting.RED));
            return 0;
        }
        PolicyManager policyManager = engine.getPolicyManager();
        if (policyManager == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy system is not initialized.", ChatFormatting.RED));
            return 0;
        }
        EconomyPolicy policy = policyManager.getPolicy(name);
        if (policy == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Policy '" + name + "' not found.", ChatFormatting.RED));
            return 0;
        }
        String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(policy.getCreatedAt()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Policy Info \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Name: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(policy.getName(), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Display Name: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(policy.getDisplayName(), ChatFormatting.WHITE)));
        if (!policy.getDescription().isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Description: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(policy.getDescription(), ChatFormatting.WHITE)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Config Keys: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(policy.getConfigValues().size()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Created: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(time, ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Created By: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(policy.getCreatedBy(), ChatFormatting.WHITE)));
        if (policy.isAutosave()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Type: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("Auto-save" + (policy.isAutosaveExpired() ? " (EXPIRED)" : ""), ChatFormatting.YELLOW)));
        }
        if ((diff = policy.diff(engine.getConfig())).isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Diff: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled("Matches current config", ChatFormatting.GREEN)));
        } else {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Diff: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(diff.size() + " keys differ from current config", ChatFormatting.YELLOW)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeSimulationStatus(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        SimulationEngine simEngine = engine.getSimulationEngine();
        if (simEngine == null) {
            if (!engine.isPremiumEnabled()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: NOT AVAILABLE (requires premium license)", ChatFormatting.RED));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: STOPPED (use /governance simulation true to start)", ChatFormatting.GRAY));
            }
            return 0;
        }
        SimulationState state = simEngine.getState();
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Agent Simulation \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.LIGHT_PURPLE));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Status: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.running() ? "RUNNING" : "STOPPED", state.running() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        if (state.running()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Paused: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.paused() ? "YES (low TPS)" : "NO", state.paused() ? ChatFormatting.YELLOW : ChatFormatting.GREEN)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Throttle Level: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.throttlingLevel(), GovernanceCommand.colorForThrottle(state.throttlingLevel()))));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Total Ticks: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(state.totalTicks()), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Current Delay: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.currentDelayMs() + "ms", ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Avg Tick Time: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2fms", (double)state.avgTickNanos() / 1000000.0), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Last Tick Time: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2fms", (double)state.lastTickNanos() / 1000000.0), ChatFormatting.WHITE)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Estimated TPS: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f", state.estimatedTps()), state.estimatedTps() >= 19.0 ? ChatFormatting.GREEN : (state.estimatedTps() >= 15.0 ? ChatFormatting.YELLOW : ChatFormatting.RED))));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Adaptive Sample: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.adaptiveSampleSize() + " players", ChatFormatting.AQUA)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Active Accounts (30d): ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(state.activeAccountCount() >= 0 ? String.valueOf(state.activeAccountCount()) : "Unknown (using online-player estimate)", state.activeAccountCount() >= 0 ? ChatFormatting.AQUA : ChatFormatting.YELLOW)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    private static int executeSimulationToggle(CommandContext<CommandSourceStack> context, GovernanceEngine engine, boolean enable) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        SimulationEngine simEngine = engine.getSimulationEngine();
        if (simEngine == null) {
            if (!engine.isPremiumEnabled()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: NOT AVAILABLE (requires premium license)", ChatFormatting.RED));
                return 0;
            }
            simEngine = new SimulationEngine(engine.getConfig(), engine);
            engine.setSimulationEngine(simEngine);
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: Engine initialized", ChatFormatting.AQUA));
        }
        if (enable) {
            if (simEngine.isRunning()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation is already RUNNING", ChatFormatting.YELLOW));
                return 0;
            }
            boolean started = simEngine.start();
            if (started) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: STARTED", ChatFormatting.GREEN));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  The engine will adaptively throttle based on server performance.", ChatFormatting.GRAY));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to start Agent Simulation", ChatFormatting.RED));
            }
        } else {
            if (!simEngine.isRunning()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation is already STOPPED", ChatFormatting.YELLOW));
                return 0;
            }
            boolean stopped = simEngine.stop();
            if (stopped) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: STOPPED", ChatFormatting.RED));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to stop Agent Simulation", ChatFormatting.RED));
            }
        }
        return 1;
    }

    private static int executeSimulationInsight(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        SimulationEngine simEngine = engine.getSimulationEngine();
        if (simEngine == null) {
            if (!engine.isPremiumEnabled()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: NOT AVAILABLE (requires premium license)", ChatFormatting.RED));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation is not running. Use /governance simulation true first.", ChatFormatting.YELLOW));
            }
            return 0;
        }
        SimulationEngine.SimulationInsight insight = simEngine.getLatestInsight();
        if (insight == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No simulation data available yet. The engine needs more time to collect data.", ChatFormatting.YELLOW));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Simulation Insight \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.LIGHT_PURPLE));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Total Money Supply: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2f", insight.totalMoneySupply()), ChatFormatting.GOLD)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Average Balance: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.2f", insight.avgBalance()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Wealthiest Player: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(insight.wealthiestPlayer() + " (" + String.format("%.2f", insight.maxBalance()) + ")", ChatFormatting.GOLD)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Gini Coefficient: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.3f", insight.giniCoefficient()), insight.giniCoefficient() > 0.7 ? ChatFormatting.RED : (insight.giniCoefficient() > 0.5 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Supply Growth Rate: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f%%", insight.supplyGrowthRate()), Math.abs(insight.supplyGrowthRate()) > 10.0 ? ChatFormatting.RED : (Math.abs(insight.supplyGrowthRate()) > 5.0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Inflation Trend: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.format("%.1f%%", insight.inflationTrend()), insight.inflationTrend() > 10.0 ? ChatFormatting.RED : (insight.inflationTrend() > 5.0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE))));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Sampled Players: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(insight.sampledPlayers()), ChatFormatting.WHITE)));
        if (!insight.recommendations().isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("  Recommendations:", ChatFormatting.AQUA));
            for (String rec : insight.recommendations()) {
                ChatFormatting recColor = rec.startsWith("CRITICAL") ? ChatFormatting.RED : (rec.startsWith("WARNING") ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    - " + rec, recColor));
            }
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    private static int executeSimulationRefresh(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        SimulationEngine simEngine = engine.getSimulationEngine();
        if (simEngine == null) {
            if (!engine.isPremiumEnabled()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation: NOT AVAILABLE (requires premium license)", ChatFormatting.RED));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Agent Simulation is not initialized. Use /governance simulation true first.", ChatFormatting.YELLOW));
            }
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Refreshing active account count from database...", ChatFormatting.YELLOW));
        int count = simEngine.forceRefreshAccountCount();
        if (count > 0) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Active Accounts (30d): ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(String.valueOf(count), ChatFormatting.GREEN)));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Adaptive Sample Size: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(simEngine.getAdaptiveSampleSize() + " players", ChatFormatting.AQUA)));
            return 1;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to refresh account count from database.", ChatFormatting.RED));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  The engine will continue using the cached value or online-player estimate.", ChatFormatting.YELLOW));
        return 0;
    }

    private static ChatFormatting colorForThrottle(String level) {
        return switch (level) {
            case "FAST" -> ChatFormatting.GREEN;
            case "NORMAL" -> ChatFormatting.AQUA;
            case "SLOW" -> ChatFormatting.YELLOW;
            case "CRAWL" -> ChatFormatting.GOLD;
            case "PAUSED" -> ChatFormatting.RED;
            default -> ChatFormatting.WHITE;
        };
    }

    private static UUID resolveAdminUuid(CommandSourceStack source) {
        ServerPlayer admin = source.getPlayer();
        return admin != null ? admin.getUUID() : CONSOLE_UUID;
    }

    private static void sendFeedback(CommandSourceStack source, Component message) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            player.sendSystemMessage(message);
        }
        catch (CommandSyntaxException e) {
            source.sendSuccess(() -> message, false);
        }
    }

    private static MutableComponent styled(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(color);
    }

    private static MutableComponent styledBold(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(style -> style.withColor(color).withBold(Boolean.valueOf(true)));
    }

    private static int executeRulesList(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Conditional Automation Rules: NOT AVAILABLE (requires premium license)", ChatFormatting.RED));
            return 0;
        }
        List<AutomationRule> rules = ruleEngine.listRules();
        if (rules.isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  No automation rules configured.", ChatFormatting.GRAY));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Use /governance rules add <name> <cooldown> to create one.", ChatFormatting.GRAY));
            return 1;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Automation Rules (" + rules.size() + ") \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        for (AutomationRule rule : rules) {
            ChatFormatting statusColor = rule.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED;
            String status = rule.isEnabled() ? "ON" : "OFF";
            String cooldown = rule.getRemainingCooldownString();
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  " + rule.getName() + " ", ChatFormatting.WHITE).append((Component)GovernanceCommand.styled("[" + status + "] ", statusColor)).append((Component)GovernanceCommand.styled(rule.getConditions().size() + " conditions, ", ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled(rule.getActions().size() + " actions ", ChatFormatting.GRAY)).append((Component)GovernanceCommand.styled("| CD: " + cooldown, ChatFormatting.DARK_GRAY)));
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeRuleInfo(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        int i;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String name = StringArgumentType.getString(context, (String)"name");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(name);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' not found.", ChatFormatting.RED));
            return 0;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Rule: " + name + " \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Status: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(rule.isEnabled() ? "ENABLED" : "DISABLED", rule.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Cooldown: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(RuleEngine.formatDuration(rule.getCooldownMillis()), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Last Triggered: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(rule.getLastTriggered() > 0L ? LocalDateTime.ofInstant(Instant.ofEpochMilli(rule.getLastTriggered()), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "Never", ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Remaining CD: ", ChatFormatting.GRAY).append((Component)GovernanceCommand.styled(rule.getRemainingCooldownString(), ChatFormatting.WHITE)));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Conditions (" + rule.getConditions().size() + "):", ChatFormatting.YELLOW));
        if (rule.getConditions().isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    (none)", ChatFormatting.DARK_GRAY));
        } else {
            for (i = 0; i < rule.getConditions().size(); ++i) {
                AutomationRule.RuleCondition c = rule.getConditions().get(i);
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    [" + i + "] " + c.type() + " " + c.value(), ChatFormatting.WHITE));
            }
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Actions (" + rule.getActions().size() + "):", ChatFormatting.YELLOW));
        if (rule.getActions().isEmpty()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("    (none)", ChatFormatting.DARK_GRAY));
        } else {
            for (i = 0; i < rule.getActions().size(); ++i) {
                AutomationRule.RuleAction a = rule.getActions().get(i);
                String actionDesc = "    [" + i + "] " + a.type();
                if (a.key() != null && !a.key().isEmpty()) {
                    actionDesc = actionDesc + " key=" + a.key();
                }
                if (a.value() != null && !a.value().isEmpty()) {
                    actionDesc = actionDesc + " value=" + a.value();
                }
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled(actionDesc, ChatFormatting.WHITE));
            }
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeRuleAdd(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String name = StringArgumentType.getString(context, (String)"name");
        String cooldownStr = StringArgumentType.getString(context, (String)"cooldown");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        if (!RuleEngine.isValidRuleName(name)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid rule name: '" + name + "'. Must be 2-32 chars, lowercase alphanumeric + hyphens.", ChatFormatting.RED));
            return 0;
        }
        long cooldownMillis = RuleEngine.parseDuration(cooldownStr);
        AutomationRule rule = new AutomationRule(name, true, new ArrayList<AutomationRule.RuleCondition>(), new ArrayList<AutomationRule.RuleAction>(), cooldownMillis, 0L);
        ruleEngine.addRule(rule).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' created with cooldown " + RuleEngine.formatDuration(cooldownMillis), ChatFormatting.GREEN));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Add conditions: /governance rules add-condition " + name + " <type> <value>", ChatFormatting.GRAY));
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Add actions: /governance rules add-action " + name + " <type> [key] [value]", ChatFormatting.GRAY));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Failed to create rule '" + name + "'. Name may already exist.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeRuleToggle(CommandContext<CommandSourceStack> context, GovernanceEngine engine, boolean enable) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String name = StringArgumentType.getString(context, (String)"name");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        ruleEngine.toggleRule(name, enable).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' " + (enable ? "enabled" : "disabled") + ".", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' not found.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeRuleDelete(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String name = StringArgumentType.getString(context, (String)"name");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        ruleEngine.removeRule(name).thenAccept(success -> source.getServer().execute(() -> {
            if (success.booleanValue()) {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' deleted.", ChatFormatting.GREEN));
            } else {
                GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + name + "' not found.", ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int executeRuleAddCondition(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String ruleName = StringArgumentType.getString(context, (String)"rule");
        String type = StringArgumentType.getString(context, (String)"type");
        double value = DoubleArgumentType.getDouble(context, (String)"value");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(ruleName);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + ruleName + "' not found.", ChatFormatting.RED));
            return 0;
        }
        if (!VALID_CONDITION_TYPES.contains(type)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unknown condition type: " + type, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid types: " + String.join((CharSequence)", ", VALID_CONDITION_TYPES), ChatFormatting.GRAY));
            return 0;
        }
        rule.getConditions().add(new AutomationRule.RuleCondition(type, value));
        ruleEngine.saveRule(ruleName);
        engine.getAuditLogger().logAutomation("RULE_CONDITION_ADDED", "rule=" + ruleName + ";type=" + type + ";value=" + value);
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Added condition " + type + " " + value + " to rule '" + ruleName + "'", ChatFormatting.GREEN));
        return 1;
    }

    private static int executeRuleAddAction(CommandContext<CommandSourceStack> context, GovernanceEngine engine, String key, String value) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String ruleName = StringArgumentType.getString(context, (String)"rule");
        String type = StringArgumentType.getString(context, (String)"type");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(ruleName);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + ruleName + "' not found.", ChatFormatting.RED));
            return 0;
        }
        if (!VALID_ACTION_TYPES.contains(type)) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Unknown action type: " + type, ChatFormatting.RED));
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Valid types: " + String.join((CharSequence)", ", VALID_ACTION_TYPES), ChatFormatting.GRAY));
            return 0;
        }
        rule.getActions().add(new AutomationRule.RuleAction(type, key != null ? key : "", value != null ? value : ""));
        ruleEngine.saveRule(ruleName);
        engine.getAuditLogger().logAutomation("RULE_ACTION_ADDED", "rule=" + ruleName + ";type=" + type + ";key=" + key + ";value=" + value);
        Object actionDesc = type;
        if (key != null) {
            actionDesc = (String)actionDesc + " key=" + key;
        }
        if (value != null) {
            actionDesc = (String)actionDesc + " value=" + value;
        }
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Added action " + (String)actionDesc + " to rule '" + ruleName + "'", ChatFormatting.GREEN));
        return 1;
    }

    private static int executeRuleRemoveCondition(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String ruleName = StringArgumentType.getString(context, (String)"rule");
        int index = IntegerArgumentType.getInteger(context, (String)"index");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(ruleName);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + ruleName + "' not found.", ChatFormatting.RED));
            return 0;
        }
        if (index < 0 || index >= rule.getConditions().size()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid condition index: " + index + ". Rule has " + rule.getConditions().size() + " conditions.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule.RuleCondition removed = rule.getConditions().remove(index);
        ruleEngine.saveRule(ruleName);
        engine.getAuditLogger().logAutomation("RULE_CONDITION_REMOVED", "rule=" + ruleName + ";index=" + index + ";type=" + removed.type());
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Removed condition [" + index + "] " + removed.type() + " from rule '" + ruleName + "'", ChatFormatting.GREEN));
        return 1;
    }

    private static int executeRuleRemoveAction(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String ruleName = StringArgumentType.getString(context, (String)"rule");
        int index = IntegerArgumentType.getInteger(context, (String)"index");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(ruleName);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + ruleName + "' not found.", ChatFormatting.RED));
            return 0;
        }
        if (index < 0 || index >= rule.getActions().size()) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Invalid action index: " + index + ". Rule has " + rule.getActions().size() + " actions.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule.RuleAction removed = rule.getActions().remove(index);
        ruleEngine.saveRule(ruleName);
        engine.getAuditLogger().logAutomation("RULE_ACTION_REMOVED", "rule=" + ruleName + ";index=" + index + ";type=" + removed.type());
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Removed action [" + index + "] " + removed.type() + " from rule '" + ruleName + "'", ChatFormatting.GREEN));
        return 1;
    }

    private static int executeRuleSetCooldown(CommandContext<CommandSourceStack> context, GovernanceEngine engine) throws CommandSyntaxException {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String ruleName = StringArgumentType.getString(context, (String)"rule");
        String durationStr = StringArgumentType.getString(context, (String)"duration");
        RuleEngine ruleEngine = engine.getRuleEngine();
        if (ruleEngine == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule engine not available.", ChatFormatting.RED));
            return 0;
        }
        AutomationRule rule = ruleEngine.getRule(ruleName);
        if (rule == null) {
            GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Rule '" + ruleName + "' not found.", ChatFormatting.RED));
            return 0;
        }
        long cooldownMillis = RuleEngine.parseDuration(durationStr);
        rule.setCooldownMillis(cooldownMillis);
        ruleEngine.saveRule(ruleName);
        engine.getAuditLogger().logAutomation("RULE_COOLDOWN_SET", "rule=" + ruleName + ";cooldown=" + RuleEngine.formatDuration(cooldownMillis));
        GovernanceCommand.sendFeedback(source, (Component)GovernanceCommand.styled("  Cooldown for rule '" + ruleName + "' set to " + RuleEngine.formatDuration(cooldownMillis), ChatFormatting.GREEN));
        return 1;
    }
}
