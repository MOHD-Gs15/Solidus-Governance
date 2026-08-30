package com.solidus.governance;

import com.mojang.brigadier.CommandDispatcher;
import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.audit.AuditLogger;
import com.solidus.governance.automation.GovernanceAutomator;
import com.solidus.governance.commands.GovernanceCommand;
import com.solidus.governance.discord.WebhookManager;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.events.EventDatabase;
import com.solidus.governance.events.EventManager;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.intervention.AccountFreezer;
import com.solidus.governance.intervention.InterventionManager;
import com.solidus.governance.license.LicenseVerifier;
import com.solidus.governance.limits.LimitsDatabase;
import com.solidus.governance.limits.TransactionLimits;
import com.solidus.governance.policy.PolicyDatabase;
import com.solidus.governance.policy.PolicyManager;
import com.solidus.governance.profile.ProfileGenerator;
import com.solidus.governance.recovery.RollbackEngine;
import com.solidus.governance.recovery.SnapshotManager;
import com.solidus.governance.rules.RuleDatabase;
import com.solidus.governance.rules.RuleEngine;
import com.solidus.governance.simulation.SimulationEngine;
import com.solidus.governance.taxation.TaxEngine;
import com.solidus.governance.taxation.TreasuryManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolidusGovernanceMod
implements DedicatedServerModInitializer {
    public static final String MOD_ID = "solidus-governance";
    public static final String VERSION = "1.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"solidus-governance");
    private GovernanceEngine engine;
    private LimitsDatabase limitsDatabase;
    private EventDatabase eventDatabase;
    private PolicyDatabase policyDatabase;
    private RuleDatabase ruleDatabase;

    public void onInitializeServer() {
        LOGGER.info("Solidus Governance v{} initializing...", (Object)VERSION);
        SolidusIntegration.initialize();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            Files.createDirectories(configDir, new FileAttribute[0]);
        }
        catch (IOException e) {
            LOGGER.error("Failed to create config directory", (Throwable)e);
        }
        GovernanceConfig config = new GovernanceConfig(configDir);
        config.load();
        AuditDatabase auditDatabase = new AuditDatabase(configDir);
        auditDatabase.initialize();
        AuditLogger auditLogger = new AuditLogger(auditDatabase);
        LicenseVerifier licenseVerifier = new LicenseVerifier();
        Path licensePath = configDir.resolve("license.key");
        licenseVerifier.verify(licensePath);
        AccountFreezer accountFreezer = new AccountFreezer();
        InterventionManager interventionManager = new InterventionManager(null);
        TaxEngine taxEngine = new TaxEngine(null);
        TreasuryManager treasuryManager = new TreasuryManager(null);
        RollbackEngine rollbackEngine = new RollbackEngine(null);
        SnapshotManager snapshotManager = new SnapshotManager(configDir, null);
        GovernanceAutomator automator = new GovernanceAutomator(null);
        this.limitsDatabase = new LimitsDatabase(configDir);
        this.limitsDatabase.initialize();
        this.engine = new GovernanceEngine(config, auditDatabase, auditLogger, licenseVerifier, accountFreezer, interventionManager, taxEngine, treasuryManager, rollbackEngine, snapshotManager, automator);
        TransactionLimits transactionLimits = new TransactionLimits(config, this.limitsDatabase, this.engine);
        this.engine.setTransactionLimits(transactionLimits);
        accountFreezer.setEngine(this.engine);
        interventionManager.setEngine(this.engine);
        taxEngine.setEngine(this.engine);
        treasuryManager.setEngine(this.engine);
        rollbackEngine.setEngine(this.engine);
        snapshotManager.setEngine(this.engine);
        automator.setEngine(this.engine);
        WebhookManager webhookManager = new WebhookManager(config);
        this.engine.setWebhookManager(webhookManager);
        if (webhookManager.isDiscordEnabled() && licenseVerifier.isPremiumEnabled()) {
            LOGGER.info("Discord Webhooks: ENABLED");
        } else {
            LOGGER.info("Discord Webhooks: DISABLED{}", (Object)(licenseVerifier.isPremiumEnabled() ? " (not enabled in config)" : " (requires premium license)"));
        }
        if (licenseVerifier.isPremiumEnabled() && config.getBool("events.enabled", true)) {
            this.eventDatabase = new EventDatabase(configDir);
            this.eventDatabase.initialize();
            EventManager eventManager = new EventManager(config, this.eventDatabase, this.engine);
            this.engine.setEventManager(eventManager);
            eventManager.loadFromDatabase();
            LOGGER.info("Economy Events: ENABLED ({} active events)", (Object)eventManager.getActiveEvents().size());
        } else {
            LOGGER.info("Economy Events: DISABLED{}", (Object)(licenseVerifier.isPremiumEnabled() ? " (disabled in config)" : " (requires premium license)"));
        }
        if (licenseVerifier.isPremiumEnabled() && config.getBool("policies.enabled", true)) {
            this.policyDatabase = new PolicyDatabase(configDir);
            this.policyDatabase.initialize();
            PolicyManager policyManager = new PolicyManager(this.policyDatabase, this.engine);
            this.engine.setPolicyManager(policyManager);
            policyManager.loadFromDatabase();
            LOGGER.info("Economy Policies: ENABLED ({} policies loaded)", (Object)policyManager.listPolicies().size());
        } else {
            LOGGER.info("Economy Policies: DISABLED{}", (Object)(licenseVerifier.isPremiumEnabled() ? " (disabled in config)" : " (requires premium license)"));
        }
        ProfileGenerator profileGenerator = new ProfileGenerator(this.engine);
        this.engine.setProfileGenerator(profileGenerator);
        LOGGER.info("Player Economy Profiles: ENABLED");
        if (licenseVerifier.isPremiumEnabled() && config.getBool("rules.enabled", true)) {
            this.ruleDatabase = new RuleDatabase(configDir);
            this.ruleDatabase.initialize();
            RuleEngine ruleEngine = new RuleEngine(this.engine, this.ruleDatabase);
            this.engine.setRuleEngine(ruleEngine);
            ruleEngine.loadFromDatabase();
            LOGGER.info("Conditional Automation Rules: ENABLED ({} rules, {} enabled)", (Object)ruleEngine.getRuleCount(), (Object)ruleEngine.getEnabledRuleCount());
        } else {
            LOGGER.info("Conditional Automation Rules: DISABLED{}", (Object)(licenseVerifier.isPremiumEnabled() ? " (disabled in config)" : " (requires premium license)"));
        }
        if (licenseVerifier.isPremiumEnabled() && config.getBool("simulation.enabled", false)) {
            SimulationEngine simulationEngine = new SimulationEngine(config, this.engine);
            this.engine.setSimulationEngine(simulationEngine);
            LOGGER.info("Agent Simulation: CONFIGURED (will start when server is ready)");
        } else {
            LOGGER.info("Agent Simulation: DISABLED{}", (Object)(licenseVerifier.isPremiumEnabled() ? " (disabled in config)" : " (requires premium license)"));
        }
        accountFreezer.initialize();
        interventionManager.initialize();
        taxEngine.initialize();
        automator.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> GovernanceCommand.register((CommandDispatcher<CommandSourceStack>)dispatcher, this.engine));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> LOGGER.info("Solidus Governance: Server starting..."));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            treasuryManager.setServer(server);
            snapshotManager.setServer(server);
            SolidusIntegration.setServer(server);
            SimulationEngine simEngine = this.engine.getSimulationEngine();
            if (simEngine != null && config.getBool("simulation.enabled", false)) {
                simEngine.start();
            }
            LOGGER.info("Solidus Governance: Server started. Premium: {}", (Object)(licenseVerifier.isPremiumEnabled() ? "ACTIVE" : "FREE MODE"));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Solidus Governance: Server stopping. Shutting down...");
            if (this.engine != null) {
                this.engine.shutdown();
            }
            if (this.limitsDatabase != null) {
                this.limitsDatabase.shutdown();
            }
            if (this.eventDatabase != null) {
                this.eventDatabase.shutdown();
            }
            if (this.policyDatabase != null) {
                this.policyDatabase.shutdown();
            }
            if (this.ruleDatabase != null) {
                this.ruleDatabase.shutdown();
            }
            SolidusIntegration.clearUuidCache();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.engine != null) {
                this.engine.onTick(server);
            }
        });
        LOGGER.info("Solidus Governance initialized successfully. Premium: {}", (Object)(licenseVerifier.isPremiumEnabled() ? "ACTIVE" : "FREE MODE"));
    }
}
