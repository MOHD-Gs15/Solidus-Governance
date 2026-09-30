package com.solidus.governance.integration;

import com.solidus.api.SolidusApi;
import com.solidus.api.SolidusApiAccess;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.intervention.AccountFreezer;
import com.solidus.governance.intervention.InterventionManager;
import com.solidus.governance.limits.TransactionLimits;
import com.solidus.governance.taxation.TaxEngine;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * CoreHookBridge — registers Solidus Governance as a
 * {@code com.solidus.api.SolidusTransactionHook} on Solidus Core 2.3.0+.
 *
 * <p>This is the enforcement bridge that was previously missing: Core calls
 * the hook at every money-movement point, and Governance answers with its
 * real policies instead of merely tracking them:</p>
 *
 * <ul>
 *   <li><b>Vetoes (before money moves):</b> emergency trading lock,
 *       frozen accounts, daily transfer limits, daily auction listing
 *       limits.</li>
 *   <li><b>Observation (after settlement):</b> daily usage recording and
 *       transaction tax collection (transfer tax on the sender, progressive
 *       wealth-bracket tax on the sender when brackets are configured,
 *       auction tax on the seller at sale time, shop tax on the buyer).</li>
 * </ul>
 *
 * <p><b>2.3.0 (audit W-5): direct implementation.</b> This class used to
 * register a reflective {@link java.lang.reflect.Proxy} that implemented
 * Core's hook interface by name — zero compile dependency, zero type
 * safety, and every Core signature drift became a runtime "standalone
 * mode" fallback (no limits, no freezes, no taxes). Now Governance compiles
 * against the <b>solidus-api</b> contract jar and implements the hook
 * interface directly; a Core change that breaks the contract breaks this
 * build at compile time, and fabric.mod.json's
 * {@code "depends": { "solidus": ">=2.3.0 <3.0.0" }} makes the loader
 * reject incompatible Core versions before either mod initializes.</p>
 *
 * <p><b>Threading:</b> veto answers are in-memory reads (volatile flags,
 * concurrent maps) plus Governance's own small limits DB on first touch of
 * the day. Tax collection and usage persistence are dispatched without
 * blocking the caller.</p>
 *
 * <p><b>Enforcement matrix (when registered):</b></p>
 * <table border="1">
 *   <tr><th>Hook point</th><th>Vetoes</th><th>After settlement</th></tr>
 *   <tr><td>allowTransfer</td><td>trading lock, frozen sender, transfer limits, frozen receiver</td><td>transfer tax (sender) + progressive bracket tax (sender, if brackets configured)</td></tr>
 *   <tr><td>allowAuctionListing</td><td>trading lock, frozen seller, auction limit</td><td>recordAuctionListing</td></tr>
 *   <tr><td>allowAuctionPurchase</td><td>trading lock, frozen buyer</td><td>auction tax (seller, at sale)</td></tr>
 *   <tr><td>allowShopPurchase</td><td>trading lock, frozen buyer</td><td>shop tax (buyer)</td></tr>
 *   <tr><td>allowShopSell</td><td>trading lock, frozen seller</td><td>(none - shop payouts untaxed)</td></tr>
 * </table>
 *
 * <p>Tax collection honors the {@code taxation.enabled} master switch and
 * each component self-gates on premium (limits are premium; freezes, locks,
 * and taxes are free).</p>
 *
 * <p><b>Failure policy (fail-closed):</b> if a veto hook throws, the hook
 * answers with a generic denial instead of letting Core's fail-open dispatch
 * wave the transaction through - a governance component that cannot answer
 * must not silently lift freezes, trading locks, or daily limits. Configure
 * {@code enforcement.fail-closed=false} to deliberately restore fail-open
 * behavior. Post-settlement notification hooks ({@code afterXxx}) keep
 * failing open: recording/tax failures must never corrupt settled state.</p>
 *
 * @since 1.2.0 (direct implementation since 2.3.0)
 */
public final class CoreHookBridge {

    private static final String HOOK_NAME = "solidus-governance";
    private static final String GENERIC_DENY = "Transaction denied by server policy.";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile GovernanceHookHandler registeredHook;

    private CoreHookBridge() {
    }

    /**
     * Idempotently registers the Governance hook with Solidus Core.
     * Safe to call at every server start; re-runs Core detection first so a
     * mod-initialization order race (Governance before Core) cannot leave
     * the bridge unregistered.
     *
     * @param engine the initialized Governance engine providing policies
     */
    public static void registerIfNeeded(GovernanceEngine engine) {
        if (engine == null) {
            return;
        }
        if (REGISTERED.get()) {
            return;
        }
        // Re-run Core detection: if Governance initialized before Core, the
        // first SolidusIntegration.initialize() saw a null API instance.
        if (!SolidusIntegration.isSolidusLoaded()) {
            SolidusIntegration.initialize();
        }
        if (!SolidusIntegration.isSolidusLoaded()) {
            SolidusGovernanceMod.LOGGER.error(
                "CoreHookBridge: Solidus Core contract unavailable - limit/lock/tax enforcement "
                    + "CANNOT run. Governance now hard-depends on Solidus Core (>=2.3.0 <3.0.0); "
                    + "the loader should have refused this combination. Verify the installed jars.");
            return;
        }
        try {
            SolidusApi api = SolidusApiAccess.get();
            if (api == null) {
                SolidusGovernanceMod.LOGGER.warn("CoreHookBridge: SolidusApi instance unavailable - hook not registered.");
                return;
            }

            GovernanceHookHandler hook = new GovernanceHookHandler(engine);
            if (api.registerTransactionHook(hook)) {
                registeredHook = hook;
                REGISTERED.set(true);
                SolidusGovernanceMod.LOGGER.info(
                    "CoreHookBridge: enforcement hook registered with Solidus Core {} "
                        + "(trading lock, freezes, limits, taxes are now enforced inside Core flows).",
                    api.getCoreVersion());
            } else {
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: Core rejected hook registration (duplicate name) - continuing without Core-side enforcement.");
            }
        } catch (Throwable t) {
            SolidusGovernanceMod.LOGGER.error(
                "CoreHookBridge: Core hook registration failed ({}). Enforcement cannot run - "
                    + "this is a build/loader inconsistency, see the stack trace.",
                t);
        }
    }

    /**
     * Unregisters the hook (server stopping). After this, Core flows run
     * unhooked until the next successful registration.
     */
    public static void unregister() {
        GovernanceHookHandler hook = registeredHook;
        if (hook == null || !REGISTERED.getAndSet(false)) {
            registeredHook = null;
            return;
        }
        try {
            SolidusApi api = SolidusApiAccess.get();
            if (api != null) {
                api.unregisterTransactionHook(hook);
            }
            SolidusGovernanceMod.LOGGER.info("CoreHookBridge: enforcement hook unregistered.");
        } catch (Throwable t) {
            SolidusGovernanceMod.LOGGER.debug("CoreHookBridge: unregister failed ({}).", t.toString());
        } finally {
            registeredHook = null;
        }
    }

    // -- The actual hook logic ------------------------------------------

    /**
     * Direct, compile-checked implementation of Core's hook interface
     * (2.3.0 — previously a reflective Proxy).
     */
    private static final class GovernanceHookHandler implements SolidusTransactionHook {
        private final GovernanceEngine engine;

        GovernanceHookHandler(GovernanceEngine engine) {
            this.engine = engine;
        }

        @Override
        public String name() {
            return HOOK_NAME;
        }

        // ---- Veto hooks (fail-closed on throw) ----

        @Override
        public Decision allowTransfer(UUID senderUuid, String senderName,
                                      UUID receiverUuid, String receiverName,
                                      double amount) {
            return vetoGuard("allowTransfer", () -> {
                Decision decision = vetoTrading(senderUuid);
                if (decision != null) return decision;
                Decision limit = vetoTransferLimit(senderUuid, amount);
                if (limit != null) return limit;
                // A freeze is an asset freeze, not just a spending ban:
                // a frozen account must not be able to receive funds.
                Decision receiverFrozen = vetoReceiverFrozen(receiverUuid);
                if (receiverFrozen != null) return receiverFrozen;
                return null;
            });
        }

        @Override
        public Decision allowAuctionListing(UUID sellerUuid, String sellerName, double price) {
            return vetoGuard("allowAuctionListing", () -> {
                Decision decision = vetoTrading(sellerUuid);
                if (decision != null) return decision;
                TransactionLimits limits = engine.getTransactionLimits();
                if (limits != null && !limits.checkAuctionLimit(sellerUuid)) {
                    return Decision.deny("Daily auction listing limit reached.");
                }
                return null;
            });
        }

        @Override
        public Decision allowAuctionPurchase(UUID buyerUuid, String buyerName, double price) {
            return vetoGuard("allowAuctionPurchase", () -> vetoTrading(buyerUuid));
        }

        @Override
        public Decision allowShopPurchase(UUID playerUuid, String playerName, double cost) {
            return vetoGuard("allowShopPurchase", () -> vetoTrading(playerUuid));
        }

        @Override
        public Decision allowShopSell(UUID playerUuid, String playerName) {
            return vetoGuard("allowShopSell", () -> vetoTrading(playerUuid));
        }

        // ---- Notification hooks (fail-open on throw) ----

        @Override
        public void afterTransfer(UUID senderUuid, String senderName,
                                  UUID receiverUuid, String receiverName,
                                  double amount) {
            notifyGuard("afterTransfer", () -> {
                TransactionLimits limits = engine.getTransactionLimits();
                if (limits != null) {
                    limits.recordTransfer(senderUuid, amount);
                }
                collectTax("TRANSFER", senderUuid, senderName,
                    engine.getTaxEngine() != null
                        ? engine.getTaxEngine().calculateTransferTax(amount) : 0.0);
                collectProgressiveTransferTax(senderUuid, senderName, amount);
            });
        }

        @Override
        public void afterAuctionListing(UUID sellerUuid, String sellerName, double price, double fee) {
            notifyGuard("afterAuctionListing", () -> {
                TransactionLimits limits = engine.getTransactionLimits();
                if (limits != null) {
                    limits.recordAuctionListing(sellerUuid);
                }
            });
        }

        @Override
        public void afterAuctionSale(UUID sellerUuid, String sellerName,
                                     UUID buyerUuid, String buyerName,
                                     double price) {
            notifyGuard("afterAuctionSale", () -> {
                // B-2 fix (audit round 3): Core's settlement moves the buyer's
                // money straight into the seller's balance WITHOUT consulting
                // allowTransfer (the auction flow deliberately skips the generic
                // transfer hooks), and allowAuctionPurchase only vetoes the BUYER.
                // A frozen seller's pre-existing listings therefore kept funneling
                // money into a frozen ("asset-frozen") account - laundering into
                // untouchable escrow. CoreHookBridge's own freeze contract states
                // "a frozen account must not be able to receive funds", so the
                // proceeds of a settlement onto a frozen seller are clawed back
                // into the treasury here, with a full audit trail. New listings
                // are already blocked by the allowAuctionListing freeze veto;
                // auction tax on the frozen seller is skipped (their proceeds are
                // escrowed - charging tax out of pre-existing funds would
                // double-punish the freeze).
                AccountFreezer freezer = engine.getAccountFreezer();
                if (freezer != null && sellerUuid != null && freezer.isFrozen(sellerUuid)) {
                    escrowFrozenAuctionProceeds(sellerUuid, sellerName, price);
                    return;
                }

                collectTax("AUCTION_SALE", sellerUuid, sellerName,
                    engine.getTaxEngine() != null
                        ? engine.getTaxEngine().calculateAuctionTax(price) : 0.0);
            });
        }

        @Override
        public void afterShopPurchase(UUID playerUuid, String playerName, double cost) {
            notifyGuard("afterShopPurchase", () ->
                collectTax("SHOP", playerUuid, playerName,
                    engine.getTaxEngine() != null
                        ? engine.getTaxEngine().calculateShopTax(cost) : 0.0));
        }

        // ---- Guard wrappers (the 2.1.x fail-closed contract, kept) ----

        /**
         * Wraps one veto computation in the historic failure policy: a
         * throwing veto fails CLOSED (generic denial) when
         * {@code enforcement.fail-closed=true} (default), and fails open
         * only when the admin explicitly opted out.
         */
        private Decision vetoGuard(String hookName, Supplier<Decision> body) {
            try {
                Decision decision = body.get();
                return decision != null ? decision : Decision.ALLOW;
            } catch (Throwable t) {
                boolean failClosed = true;
                try {
                    failClosed = engine.getConfig().getBool("enforcement.fail-closed", true);
                } catch (Throwable ignored) {
                    // Config unavailable: keep the safe default (fail-closed).
                }
                if (failClosed) {
                    // Fail CLOSED: an explicit denial is final for Core, so the
                    // economy stays protected even when Governance cannot answer.
                    SolidusGovernanceMod.LOGGER.error(
                        "CoreHookBridge: veto hook {} threw - failing CLOSED (enforcement.fail-closed=true). {}",
                        hookName, t.toString());
                    return Decision.deny(GENERIC_DENY);
                }
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: veto hook {} threw - failing open (enforcement.fail-closed=false). {}",
                    hookName, t.toString());
                return Decision.ALLOW;
            }
        }

        /**
         * Wraps one post-settlement notification in the historic failure
         * policy: recording/tax failures must never corrupt settled state,
         * so they fail open (logged).
         */
        private void notifyGuard(String hookName, Runnable body) {
            try {
                body.run();
            } catch (Throwable t) {
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: hook method {} threw - failing open. {}", hookName, t.toString());
            }
        }

        /**
         * Claw back auction proceeds that landed on a FROZEN seller (B-2, audit
         * round 3) and escrow them in the treasury. Money-conserving, fully
         * audited (before/after recorded), and reversible by an admin: unfreeze
         * the account and re-credit from the treasury using the audit row.
         */
        private void escrowFrozenAuctionProceeds(UUID seller, String sellerName, double price) {
            SolidusGovernanceMod.LOGGER.warn(
                "CoreHookBridge: auction proceeds of {} landed on frozen account '{}' ({}), escrowing them into the treasury",
                price, sellerName, seller);
            try {
                SolidusIntegration.getBalance(seller, sellerName)
                    .thenCompose(before -> SolidusIntegration.subtractBalance(seller, sellerName, price)
                        .thenCompose(afterBalance -> {
                            boolean clawedBack = afterBalance != null && Double.isFinite(afterBalance) && afterBalance >= 0.0;
                            MinecraftServer srv = SolidusIntegration.getServer();
                            if (engine != null && srv != null) {
                                srv.execute(() -> engine.getAuditLogger().logBalanceChange(
                                    null, "System", seller, sellerName, "FROZEN_PROCEEDS_ESCROW",
                                    before != null ? before : -1.0,
                                    afterBalance != null ? afterBalance : -1.0,
                                    -price));
                            }
                            if (!clawedBack) {
                                SolidusGovernanceMod.LOGGER.error(
                                    "CoreHookBridge: could not claw back {} from frozen seller {} (balance {})",
                                    price, sellerName, afterBalance);
                                return CompletableFuture.completedFuture(null);
                            }
                            // Escrow into the treasury when one is configured.
                            String treasuryUuid = engine.getConfig().getString("taxation.treasury.account", "");
                            if (treasuryUuid.isBlank()) {
                                SolidusGovernanceMod.LOGGER.warn(
                                    "CoreHookBridge: no treasury configured - frozen-seller proceeds of {} were removed from {} and burned. Configure taxation.treasury.account to escrow them instead.",
                                    price, sellerName);
                                return CompletableFuture.completedFuture(null);
                            }
                            try {
                                UUID treasury = UUID.fromString(treasuryUuid);
                                return SolidusIntegration.addBalance(treasury, "Treasury", price)
                                    .thenAccept(deposit -> {
                                        MinecraftServer srv2 = SolidusIntegration.getServer();
                                        if (engine != null && srv2 != null) {
                                            srv2.execute(() -> engine.getAuditLogger().logTreasuryOperation(
                                                null, "System", "FROZEN_PROCEEDS_ESCROW", price));
                                        }
                                    });
                            } catch (IllegalArgumentException badTreasury) {
                                SolidusGovernanceMod.LOGGER.warn(
                                    "CoreHookBridge: invalid treasury UUID '{}' - escrow skipped", treasuryUuid);
                                return CompletableFuture.completedFuture(null);
                            }
                        }))
                    .exceptionally(ex -> {
                        SolidusGovernanceMod.LOGGER.error(
                            "CoreHookBridge: frozen-proceeds escrow failed for {} ({})", sellerName, ex.toString());
                        return null;
                    });
            } catch (Throwable t) {
                SolidusGovernanceMod.LOGGER.error(
                    "CoreHookBridge: frozen-proceeds escrow dispatch failed for {}", sellerName, t);
            }
        }

        /** Shared trading veto: emergency lock first, then per-account freeze. */
        private Decision vetoTrading(UUID player) {
            InterventionManager intervention = engine.getInterventionManager();
            if (intervention != null && intervention.isTradingLocked()) {
                String reason = intervention.getTradingLockReason();
                return Decision.deny("Trading is currently locked by administrators"
                    + (reason != null && !reason.isBlank() ? ": " + reason : "."));
            }
            AccountFreezer freezer = engine.getAccountFreezer();
            if (freezer != null && player != null && freezer.isFrozen(player)) {
                return Decision.deny("Your account is frozen by server administrators.");
            }
            return null;
        }

        /** Transfer-limit veto (TransactionLimits self-gates on premium). */
        private Decision vetoTransferLimit(UUID sender, double amount) {
            TransactionLimits limits = engine.getTransactionLimits();
            if (limits != null && !limits.checkTransferLimit(sender, amount)) {
                return Decision.deny("Transfer denied: daily transaction limit reached.");
            }
            return null;
        }

        /** Frozen receivers cannot receive funds (asset freeze, not just a spending ban). */
        private Decision vetoReceiverFrozen(UUID receiver) {
            AccountFreezer freezer = engine.getAccountFreezer();
            if (freezer != null && receiver != null && freezer.isFrozen(receiver)) {
                return Decision.deny("The receiving account is frozen by server administrators.");
            }
            return null;
        }

        /**
         * Fire-and-forget tax collection. Honors the taxation.enabled master
         * switch; TaxEngine.collectTaxAsync is fully async, self-auditing,
         * and degrades to a no-op when the player cannot afford the tax.
         */
        private void collectTax(String type, UUID player, String playerName, double taxAmount) {
            if (taxAmount <= 0.0) {
                return;
            }
            if (!engine.getConfig().getBool("taxation.enabled", false)) {
                return;
            }
            try {
                engine.getTaxEngine().collectTaxAsync(player, playerName, type, taxAmount);
            } catch (Throwable t) {
                SolidusGovernanceMod.LOGGER.warn("CoreHookBridge: tax collection dispatch failed: {}", t.toString());
            }
        }

        /**
         * Progressive wealth-bracket tax on transfers (wired in 1.2.1 -
         * previously {@code /governance tax brackets add} stored brackets
         * that no code path ever applied).
         *
         * <p>Dispatch is fully async: the sender's post-settlement balance is
         * fetched via SolidusIntegration, the pre-transfer balance is
         * reconstructed inside
         * {@link TaxEngine#calculateProgressiveTransferTax}, and the result is
         * collected under its own audit type {@code PROGRESSIVE} so admins can
         * distinguish bracket revenue from the flat transfer rate. No-op when
         * no brackets are configured; {@code collectTax} still honors the
         * {@code taxation.enabled} master switch.</p>
         */
        private void collectProgressiveTransferTax(UUID sender, String senderName, double amount) {
            TaxEngine taxEngine = engine.getTaxEngine();
            if (taxEngine == null || taxEngine.getBrackets().isEmpty()) {
                return;
            }
            try {
                SolidusIntegration.getBalance(sender, senderName)
                    .thenAccept(balanceAfter -> {
                        // -1.0 / null means the balance is unavailable (Core
                        // absent or lookup failed) - skip rather than guess.
                        if (balanceAfter == null || !Double.isFinite(balanceAfter) || balanceAfter < 0.0) {
                            return;
                        }
                        double progressive = taxEngine.calculateProgressiveTransferTax(balanceAfter, amount);
                        collectTax("PROGRESSIVE", sender, senderName, progressive);
                    })
                    .exceptionally(ex -> {
                        SolidusGovernanceMod.LOGGER.debug(
                            "CoreHookBridge: progressive tax balance lookup failed for {}: {}",
                            senderName, ex.toString());
                        return null;
                    });
            } catch (Throwable t) {
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: progressive tax dispatch failed: {}", t.toString());
            }
        }
    }
}
