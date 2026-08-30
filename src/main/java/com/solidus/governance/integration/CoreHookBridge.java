package com.solidus.governance.integration;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.intervention.AccountFreezer;
import com.solidus.governance.intervention.InterventionManager;
import com.solidus.governance.limits.TransactionLimits;
import com.solidus.governance.taxation.TaxEngine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CoreHookBridge - Registers Solidus Governance as a
 * {@code com.solidus.api.SolidusTransactionHook} on Solidus Core 2.1.0+.
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
 * <p><b>Zero compile dependency:</b> the hook is a {@link Proxy} implementing
 * Core's hook interface by name; every Core type is resolved reflectively.
 * If Core is absent or older than 2.1.0, registration degrades gracefully
 * and Governance keeps operating in standalone DB mode.</p>
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
 * @since 1.2.0
 */
public final class CoreHookBridge {

    private static final String HOOK_NAME = "solidus-governance";
    private static final String GENERIC_DENY = "Transaction denied by server policy.";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile Object registeredProxy;

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
            SolidusGovernanceMod.LOGGER.info(
                "CoreHookBridge: Solidus Core not detected - limit/lock/tax enforcement stays in standalone mode.");
            return;
        }
        try {
            Class<?> hookInterface = Class.forName("com.solidus.api.SolidusTransactionHook");
            Object api = SolidusIntegration.getApi();
            if (api == null) {
                SolidusGovernanceMod.LOGGER.warn("CoreHookBridge: SolidusAPI instance unavailable - hook not registered.");
                return;
            }

            Object proxy = Proxy.newProxyInstance(
                hookInterface.getClassLoader(),
                new Class<?>[]{hookInterface},
                new GovernanceHookHandler(engine, hookInterface));

            Method register = api.getClass().getMethod("registerTransactionHook", hookInterface);
            Object result = register.invoke(api, proxy);
            if (Boolean.TRUE.equals(result)) {
                registeredProxy = proxy;
                REGISTERED.set(true);
                SolidusGovernanceMod.LOGGER.info(
                    "CoreHookBridge: enforcement hook registered with Solidus Core "
                        + "(trading lock, freezes, limits, taxes are now enforced inside Core flows).");
            } else {
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: Core rejected hook registration (duplicate name or old Core) - continuing without Core-side enforcement.");
            }
        } catch (Throwable t) {
            // Old Core (< 2.1.0) without the hook API lands here - not an error.
            SolidusGovernanceMod.LOGGER.info(
                "CoreHookBridge: Core hook API unavailable ({}). Upgrade Core to 2.1.0+ for inside-Core enforcement.",
                t.getClass().getSimpleName());
        }
    }

    /**
     * Unregisters the hook (server stopping). After this, Core flows run
     * unhooked until the next successful registration.
     */
    public static void unregister() {
        Object proxy = registeredProxy;
        if (proxy == null || !REGISTERED.getAndSet(false)) {
            registeredProxy = null;
            return;
        }
        try {
            Class<?> hookInterface = Class.forName("com.solidus.api.SolidusTransactionHook");
            Object api = SolidusIntegration.getApi();
            if (api != null) {
                api.getClass()
                    .getMethod("unregisterTransactionHook", hookInterface)
                    .invoke(api, proxy);
            }
            SolidusGovernanceMod.LOGGER.info("CoreHookBridge: enforcement hook unregistered.");
        } catch (Throwable t) {
            SolidusGovernanceMod.LOGGER.debug("CoreHookBridge: unregister failed ({}).", t.toString());
        } finally {
            registeredProxy = null;
        }
    }

    // -- The actual hook logic ------------------------------------------

    private static final class GovernanceHookHandler implements InvocationHandler {
        private final GovernanceEngine engine;
        private final Class<?> hookInterface;
        private final Object allowDecision;
        private final Method denyFactory;

        GovernanceHookHandler(GovernanceEngine engine, Class<?> hookInterface) throws Exception {
            this.engine = engine;
            this.hookInterface = hookInterface;
            Class<?> decisionClass = Class.forName("com.solidus.api.SolidusTransactionHook$Decision");
            this.allowDecision = decisionClass.getField("ALLOW").get(null);
            this.denyFactory = decisionClass.getMethod("deny", String.class);
        }

        private Object deny(String reason) throws Exception {
            return denyFactory.invoke(null, reason != null ? reason : GENERIC_DENY);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // Object methods
            switch (name) {
                case "toString":
                    return "SolidusTransactionHook(" + HOOK_NAME + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }

            try {
                switch (name) {
                    case "name":
                        return HOOK_NAME;

                    // ---- Veto hooks ----
                    case "allowTransfer": {
                        // (senderUuid, senderName, receiverUuid, receiverName, amount)
                        UUID sender = (UUID) args[0];
                        UUID receiver = (UUID) args[2];
                        double amount = (Double) args[4];
                        Object decision = vetoTrading(sender);
                        if (decision != null) return decision;
                        Object limit = vetoTransferLimit(sender, amount);
                        if (limit != null) return limit;
                        // A freeze is an asset freeze, not just a spending ban:
                        // a frozen account must not be able to receive funds.
                        Object receiverFrozen = vetoReceiverFrozen(receiver);
                        if (receiverFrozen != null) return receiverFrozen;
                        return allowDecision;
                    }
                    case "allowAuctionListing": {
                        // (sellerUuid, sellerName, price)
                        UUID seller = (UUID) args[0];
                        Object decision = vetoTrading(seller);
                        if (decision != null) return decision;
                        TransactionLimits limits = engine.getTransactionLimits();
                        if (limits != null && !limits.checkAuctionLimit(seller)) {
                            return deny("Daily auction listing limit reached.");
                        }
                        return allowDecision;
                    }
                    case "allowAuctionPurchase": {
                        // (buyerUuid, buyerName, price)
                        Object decision = vetoTrading((UUID) args[0]);
                        return decision != null ? decision : allowDecision;
                    }
                    case "allowShopPurchase": {
                        // (playerUuid, playerName, cost)
                        Object decision = vetoTrading((UUID) args[0]);
                        return decision != null ? decision : allowDecision;
                    }
                    case "allowShopSell": {
                        // (playerUuid, playerName)
                        Object decision = vetoTrading((UUID) args[0]);
                        return decision != null ? decision : allowDecision;
                    }

                    // ---- Notification hooks ----
                    case "afterTransfer": {
                        // (senderUuid, senderName, receiverUuid, receiverName, amount)
                        UUID sender = (UUID) args[0];
                        String senderName = (String) args[1];
                        double amount = (Double) args[4];
                        TransactionLimits limits = engine.getTransactionLimits();
                        if (limits != null) {
                            limits.recordTransfer(sender, amount);
                        }
                        collectTax("TRANSFER", sender, senderName,
                            engine.getTaxEngine() != null
                                ? engine.getTaxEngine().calculateTransferTax(amount) : 0.0);
                        collectProgressiveTransferTax(sender, senderName, amount);
                        return null;
                    }
                    case "afterAuctionListing": {
                        // (sellerUuid, sellerName, price, fee)
                        TransactionLimits limits = engine.getTransactionLimits();
                        if (limits != null) {
                            limits.recordAuctionListing((UUID) args[0]);
                        }
                        return null;
                    }
                    case "afterAuctionSale": {
                        // (sellerUuid, sellerName, buyerUuid, buyerName, price)
                        UUID seller = (UUID) args[0];
                        String sellerName = (String) args[1];
                        double price = (Double) args[4];
                        collectTax("AUCTION_SALE", seller, sellerName,
                            engine.getTaxEngine() != null
                                ? engine.getTaxEngine().calculateAuctionTax(price) : 0.0);
                        return null;
                    }
                    case "afterShopPurchase": {
                        // (playerUuid, playerName, cost)
                        UUID buyer = (UUID) args[0];
                        String buyerName = (String) args[1];
                        double cost = (Double) args[2];
                        collectTax("SHOP", buyer, buyerName,
                            engine.getTaxEngine() != null
                                ? engine.getTaxEngine().calculateShopTax(cost) : 0.0);
                        return null;
                    }
                    default:
                        // Unhandled interface method: generic defaults.
                        Class<?> rt = method.getReturnType();
                        if (hookInterface.isAssignableFrom(method.getDeclaringClass())) {
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0.0;
                            if (rt == long.class) return 0L;
                            return null;
                        }
                        return null;
                }
            } catch (Throwable t) {
                boolean isVeto = name.startsWith("allow");
                boolean failClosed = true;
                try {
                    failClosed = engine.getConfig().getBool("enforcement.fail-closed", true);
                } catch (Throwable ignored) {
                    // Config unavailable: keep the safe default (fail-closed).
                }
                if (isVeto && failClosed) {
                    // Fail CLOSED: an explicit denial is final for Core, so the
                    // economy stays protected even when Governance cannot answer.
                    SolidusGovernanceMod.LOGGER.error(
                        "CoreHookBridge: veto hook {} threw - failing CLOSED (enforcement.fail-closed=true). {}",
                        name, t.toString());
                    try {
                        return deny(GENERIC_DENY);
                    } catch (Throwable denyFailure) {
                        SolidusGovernanceMod.LOGGER.error(
                            "CoreHookBridge: constructing a denial failed: {}", denyFailure.toString());
                        return genericDefault(method);
                    }
                }
                // Notification hooks (afterXxx) and fail-closed-opted-out servers:
                // post-settlement recording/tax failures must never corrupt the
                // already-committed state, so these fail open.
                SolidusGovernanceMod.LOGGER.warn(
                    "CoreHookBridge: hook method {} threw - failing open. {}", name, t.toString());
                return genericDefault(method);
            }
        }

        /** Shared trading veto: emergency lock first, then per-account freeze. */
        private Object vetoTrading(UUID player) throws Exception {
            InterventionManager intervention = engine.getInterventionManager();
            if (intervention != null && intervention.isTradingLocked()) {
                String reason = intervention.getTradingLockReason();
                return deny("Trading is currently locked by administrators"
                    + (reason != null && !reason.isBlank() ? ": " + reason : "."));
            }
            AccountFreezer freezer = engine.getAccountFreezer();
            if (freezer != null && player != null && freezer.isFrozen(player)) {
                return deny("Your account is frozen by server administrators.");
            }
            return null;
        }

        /** Transfer-limit veto (TransactionLimits self-gates on premium). */
        private Object vetoTransferLimit(UUID sender, double amount) throws Exception {
            TransactionLimits limits = engine.getTransactionLimits();
            if (limits != null && !limits.checkTransferLimit(sender, amount)) {
                return deny("Transfer denied: daily transaction limit reached.");
            }
            return null;
        }

        /** Frozen receivers cannot receive funds (asset freeze, not just a spending ban). */
        private Object vetoReceiverFrozen(UUID receiver) throws Exception {
            AccountFreezer freezer = engine.getAccountFreezer();
            if (freezer != null && receiver != null && freezer.isFrozen(receiver)) {
                return deny("The receiving account is frozen by server administrators.");
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

        private Object genericDefault(Method method) {
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == double.class) return 0.0;
            if (rt == long.class) return 0L;
            return null;
        }
    }
}
