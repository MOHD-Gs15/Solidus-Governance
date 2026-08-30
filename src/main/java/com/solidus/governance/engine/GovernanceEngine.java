package com.solidus.governance.engine;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.audit.AuditLogger;
import com.solidus.governance.automation.GovernanceAutomator;
import com.solidus.governance.discord.WebhookManager;
import com.solidus.governance.events.EventManager;
import com.solidus.governance.intervention.AccountFreezer;
import com.solidus.governance.intervention.InterventionManager;
import com.solidus.governance.license.LicenseVerifier;
import com.solidus.governance.limits.TransactionLimits;
import com.solidus.governance.policy.PolicyManager;
import com.solidus.governance.profile.ProfileGenerator;
import com.solidus.governance.recovery.RollbackEngine;
import com.solidus.governance.recovery.SnapshotManager;
import com.solidus.governance.rules.RuleEngine;
import com.solidus.governance.simulation.SimulationEngine;
import com.solidus.governance.taxation.TaxEngine;
import com.solidus.governance.taxation.TreasuryManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import net.minecraft.server.MinecraftServer;

public class GovernanceEngine {
    private final GovernanceConfig config;
    private final AuditDatabase auditDatabase;
    private final AuditLogger auditLogger;
    private final LicenseVerifier licenseVerifier;
    private final AccountFreezer accountFreezer;
    private final InterventionManager interventionManager;
    private final TaxEngine taxEngine;
    private final TreasuryManager treasuryManager;
    private final RollbackEngine rollbackEngine;
    private final SnapshotManager snapshotManager;
    private final GovernanceAutomator automator;
    private TransactionLimits transactionLimits;
    private WebhookManager webhookManager;
    private EventManager eventManager;
    private PolicyManager policyManager;
    private ProfileGenerator profileGenerator;
    private RuleEngine ruleEngine;
    private SimulationEngine simulationEngine;
    private long tickCounter = 0L;
    private String lastKnownDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

    public GovernanceEngine(GovernanceConfig config, AuditDatabase auditDatabase, AuditLogger auditLogger, LicenseVerifier licenseVerifier, AccountFreezer accountFreezer, InterventionManager interventionManager, TaxEngine taxEngine, TreasuryManager treasuryManager, RollbackEngine rollbackEngine, SnapshotManager snapshotManager, GovernanceAutomator automator) {
        this.config = config;
        this.auditDatabase = auditDatabase;
        this.auditLogger = auditLogger;
        this.licenseVerifier = licenseVerifier;
        this.accountFreezer = accountFreezer;
        this.interventionManager = interventionManager;
        this.taxEngine = taxEngine;
        this.treasuryManager = treasuryManager;
        this.rollbackEngine = rollbackEngine;
        this.snapshotManager = snapshotManager;
        this.automator = automator;
    }

    public void setTransactionLimits(TransactionLimits transactionLimits) {
        this.transactionLimits = transactionLimits;
    }

    public void onTick(MinecraftServer server) {
        ++this.tickCounter;
        if (this.tickCounter % 1200L == 0L) {
            String today;
            this.accountFreezer.checkExpirations();
            if (this.licenseVerifier.isPremiumEnabled()) {
                if (this.ruleEngine != null && this.config.getBool("rules.enabled", true) && this.ruleEngine.getEnabledRuleCount() > 0) {
                    this.ruleEngine.evaluateAll();
                } else {
                    this.automator.onPeriodicCheck();
                }
            }
            if (this.eventManager != null && this.isPremiumEnabled()) {
                this.eventManager.tickExpirations();
            }
            if (!(today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).equals(this.lastKnownDate)) {
                SolidusGovernanceMod.LOGGER.info("Date change detected: {} \u2192 {}. Triggering daily limit reset.", (Object)this.lastKnownDate, (Object)today);
                this.lastKnownDate = today;
                if (this.transactionLimits != null) {
                    this.transactionLimits.resetDailyLimits();
                }
                if (this.webhookManager != null && this.isPremiumEnabled()) {
                    this.webhookManager.sendDailySummary(this);
                }
            }
        }
        if (this.tickCounter % 72000L == 0L) {
            if (this.config.getBool("taxation.enabled", false)) {
                this.taxEngine.applyWealthDecay();
                // Retry parked tax debts hourly (anti-avoidance ledger).
                this.taxEngine.processPendingTaxes();
            }
            if (this.config.getBool("recovery.snapshot.auto-enabled", false)) {
                this.snapshotManager.autoSnapshot();
            }
        }
    }

    public void shutdown() {
        SolidusGovernanceMod.LOGGER.info("Governance Engine shutting down...");
        this.interventionManager.persistState();
        this.auditDatabase.shutdown();
        if (this.webhookManager != null) {
            this.webhookManager.shutdown();
        }
        if (this.simulationEngine != null && this.simulationEngine.isRunning()) {
            this.simulationEngine.stop();
        }
        SolidusGovernanceMod.LOGGER.info("Governance Engine shut down complete.");
    }

    public GovernanceConfig getConfig() {
        return this.config;
    }

    public AuditDatabase getAuditDatabase() {
        return this.auditDatabase;
    }

    public AuditLogger getAuditLogger() {
        return this.auditLogger;
    }

    public LicenseVerifier getLicenseVerifier() {
        return this.licenseVerifier;
    }

    public boolean isPremiumEnabled() {
        return this.licenseVerifier.isPremiumEnabled();
    }

    public AccountFreezer getAccountFreezer() {
        return this.accountFreezer;
    }

    public InterventionManager getInterventionManager() {
        return this.interventionManager;
    }

    public TaxEngine getTaxEngine() {
        return this.taxEngine;
    }

    public TreasuryManager getTreasuryManager() {
        return this.treasuryManager;
    }

    public RollbackEngine getRollbackEngine() {
        return this.rollbackEngine;
    }

    public SnapshotManager getSnapshotManager() {
        return this.snapshotManager;
    }

    public GovernanceAutomator getAutomator() {
        return this.automator;
    }

    public TransactionLimits getTransactionLimits() {
        return this.transactionLimits;
    }

    public void setEventManager(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    public EventManager getEventManager() {
        return this.eventManager;
    }

    public void setWebhookManager(WebhookManager webhookManager) {
        this.webhookManager = webhookManager;
    }

    public WebhookManager getWebhookManager() {
        return this.webhookManager;
    }

    public void setPolicyManager(PolicyManager policyManager) {
        this.policyManager = policyManager;
    }

    public PolicyManager getPolicyManager() {
        return this.policyManager;
    }

    public void setProfileGenerator(ProfileGenerator profileGenerator) {
        this.profileGenerator = profileGenerator;
    }

    public ProfileGenerator getProfileGenerator() {
        return this.profileGenerator;
    }

    public void setRuleEngine(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public RuleEngine getRuleEngine() {
        return this.ruleEngine;
    }

    public void setSimulationEngine(SimulationEngine simulationEngine) {
        this.simulationEngine = simulationEngine;
    }

    public SimulationEngine getSimulationEngine() {
        return this.simulationEngine;
    }
}
