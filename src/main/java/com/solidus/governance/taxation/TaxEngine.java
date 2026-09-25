package com.solidus.governance.taxation;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListMap;
import net.minecraft.server.MinecraftServer;

public class TaxEngine {
    private GovernanceEngine engine;
    private final NavigableMap<Double, Double> progressiveBrackets = new ConcurrentSkipListMap<Double, Double>();
    /** Persistent ledger of uncollectable tax debts (anti-avoidance). Wired via {@link #setLedger}. */
    private volatile TaxLedgerDatabase ledger;

    public TaxEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setLedger(TaxLedgerDatabase ledger) {
        this.ledger = ledger;
    }

    public void initialize() {
        SolidusGovernanceMod.LOGGER.info("Tax Engine initialized. Transfer: {}%, Auction: {}%, Shop: {}%", new Object[]{String.format("%.1f", this.getTransferTaxRate() * 100.0), String.format("%.1f", this.getAuctionTaxRate() * 100.0), String.format("%.1f", this.getShopTaxRate() * 100.0)});
    }

    public double calculateTransferTax(double amount) {
        return TaxEngine.roundTax(amount * this.getTransferTaxRate());
    }

    public double calculateAuctionTax(double amount) {
        return TaxEngine.roundTax(amount * this.getAuctionTaxRate());
    }

    public double calculateShopTax(double amount) {
        return TaxEngine.roundTax(amount * this.getShopTaxRate());
    }

    public double calculateProgressiveTax(double balance, double amount) {
        if (this.progressiveBrackets.isEmpty()) {
            return 0.0;
        }
        Map.Entry<Double, Double> bracket = this.progressiveBrackets.floorEntry(balance);
        if (bracket == null) {
            return 0.0;
        }
        return TaxEngine.roundTax(amount * bracket.getValue());
    }

    /**
     * Progressive transfer tax with post-settlement balance reconstruction.
     *
     * <p>Bridge notification hooks ({@code afterTransfer}) observe the sender's
     * balance <b>after</b> the transfer has settled, while brackets are meant
     * to classify the sender's wealth <b>at transaction time</b>. The
     * pre-transfer balance is therefore reconstructed as
     * {@code balanceBefore = balanceAfter + transferAmount} and bracket
     * selection uses that value, so a large payout cannot drop the sender
     * into a lower bracket before their own tax is computed.</p>
     *
     * <p>No-op (0.0) when no brackets are configured.</p>
     *
     * @param balanceAfter   the sender's observed balance after the transfer settled
     * @param transferAmount the transferred amount
     * @return the progressive tax due on the transfer, rounded to 2 decimals
     */
    public double calculateProgressiveTransferTax(double balanceAfter, double transferAmount) {
        if (this.progressiveBrackets.isEmpty()) {
            return 0.0;
        }
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0.0 || !Double.isFinite(transferAmount) || transferAmount <= 0.0) {
            return 0.0;
        }
        return this.calculateProgressiveTax(balanceAfter + transferAmount, transferAmount);
    }

    /**
     * Collects a tax, parking it as persistent debt when collection fails.
     *
     * <p>Anti-avoidance fix: the previous version silently dropped the tax
     * when the player could not afford it at settlement time (or the balance
     * lookup failed), so keeping a near-zero balance dodged every tax. Failed
     * collections are now recorded in {@link TaxLedgerDatabase} and retried by
     * {@link #processPendingTaxes()} until they succeed or exhaust the retry
     * budget.</p>
     */
    public CompletableFuture<Double> collectTaxAsync(UUID playerUuid, String playerName, String taxType, double taxAmount) {
        if (!Double.isFinite(taxAmount) || taxAmount <= 0.0 || this.engine == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        return collectNow(playerUuid, playerName, taxType, taxAmount)
            .thenCompose(collected -> {
                if (collected > 0.0) {
                    return CompletableFuture.completedFuture(collected);
                }
                enqueuePendingTax(playerUuid, playerName, taxType, taxAmount,
                    "collection failed at transaction time");
                return CompletableFuture.completedFuture(0.0);
            })
            .exceptionally(ex -> {
                SolidusGovernanceMod.LOGGER.error("Failed to collect tax from {}", playerName, ex);
                enqueuePendingTax(playerUuid, playerName, taxType, taxAmount,
                    "exception: " + ex);
                return 0.0;
            });
    }

    /**
     * One collection attempt with no failure handling beyond status reporting:
     * returns the collected amount, or 0.0 when the balance lookup failed, the
     * player cannot afford the tax, or the subtraction was rejected.
     */
    private CompletableFuture<Double> collectNow(UUID playerUuid, String playerName, String taxType, double taxAmount) {
        return SolidusIntegration.getBalance(playerUuid, playerName)
            .thenCompose(balanceBeforeValue -> {
                double before = balanceBeforeValue != null && Double.isFinite(balanceBeforeValue) && balanceBeforeValue >= 0.0 ? balanceBeforeValue : 0.0;
                return SolidusIntegration.subtractBalance(playerUuid, playerName, taxAmount)
                    .thenCompose(newBalance -> {
                        if (newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                            return CompletableFuture.completedFuture(0.0);
                        }
                        return this.settleTreasury(playerUuid, playerName, taxType, taxAmount, before, newBalance);
                    });
            });
    }

    /**
     * GOV-12 fix (reliability round): the treasury leg and the completion
     * value of a tax collection are now one consistent decision.
     *
     * <p>Historically the deposit-failure path (D-3/B-4) refunded the player
     * and re-parked the debt, but the surrounding chain STILL returned
     * {@code taxAmount} and STILL wrote the success
     * {@code logTaxCollection(before, newBalance)} audit row - so for a tax
     * that was actually refunded, the permanent audit trail claimed a
     * collection, and the retry sweeper marked the old debt row "collected"
     * while a duplicate debt row tracked the same tax. No money was lost, but
     * the books contradicted themselves exactly where admins look during an
     * incident review.</p>
     *
     * <p>New contract: returns {@code taxAmount} only when the tax is truly
     * settled - treasury credited, or no treasury configured (the revenue is
     * then intentionally burned, documented behavior). Returns {@code 0.0}
     * when the deposit failed and the player was refunded, so the caller
     * ({@link #collectTaxAsync} or the retry sweeper) re-parks the debt
     * exactly once. If the refund itself fails, the money already left the
     * player; the amount is reported as taken (with CRITICAL limbo logs) so
     * the debt can never be double-charged.</p>
     */
    private CompletableFuture<Double> settleTreasury(UUID playerUuid, String playerName, String taxType, double taxAmount, double before, double newBalance) {
        String treasuryUuid = this.engine.getConfig().getString("taxation.treasury.account", "");
        CompletableFuture<Boolean> depositOk;
        if (treasuryUuid.isBlank()) {
            // No treasury configured: tax revenue is intentionally burned
            // (documented behavior) - the collection itself succeeded.
            depositOk = CompletableFuture.completedFuture(Boolean.TRUE);
        } else {
            try {
                UUID treasury = UUID.fromString(treasuryUuid);
                // D-3/B-4 fix (audit round 3): the deposit result used to be
                // IGNORED while a TREASURY_DEPOSIT audit row was written
                // unconditionally - a failed deposit silently destroyed the
                // player's money while the audit trail claimed success (exactly
                // what SECURITY.md forbids). The deposit is now checked; on
                // failure the debit is compensated back to the player and the
                // debt is re-parked for retry.
                depositOk = SolidusIntegration.addBalance(treasury, "Treasury", taxAmount)
                    .thenApply(depositResult -> depositResult != null && depositResult >= 0.0)
                    .exceptionally(depositEx -> {
                        SolidusGovernanceMod.LOGGER.error(
                            "Treasury deposit threw for {} ({})", playerName, depositEx.toString());
                        return Boolean.FALSE;
                    });
            } catch (IllegalArgumentException ex) {
                SolidusGovernanceMod.LOGGER.warn("Invalid treasury UUID in governance config: {}", treasuryUuid);
                // Deposit impossible - treat exactly like a failed deposit:
                // refund the player and re-park the debt rather than burning
                // the money on a misconfiguration the admin can fix.
                depositOk = CompletableFuture.completedFuture(Boolean.FALSE);
            }
        }
        return depositOk.thenCompose(ok -> {
            MinecraftServer server = this.getServer();
            if (Boolean.TRUE.equals(ok)) {
                if (this.engine != null && server != null) {
                    server.execute(() -> {
                        if (!treasuryUuid.isBlank()) {
                            this.engine.getAuditLogger().logTreasuryOperation(null, "System", "DEPOSIT", taxAmount);
                        }
                        this.engine.getAuditLogger().logTaxCollection(playerUuid, playerName, taxType, taxAmount, before, newBalance);
                    });
                }
                if (taxAmount >= 10000.0) {
                    this.sendDiscordAlert("TAXATION", "Large Tax Collection", "Type: " + taxType + ", Amount: " + String.format("%.2f", taxAmount) + ", Player: " + playerName);
                }
                return CompletableFuture.completedFuture(taxAmount);
            }
            // GOV-12 (reliability round): treasury deposit failed. The player
            // is refunded here and the CALLER re-parks the debt exactly once
            // (collectTaxAsync parks a fresh row; the retry sweeper keeps the
            // existing one). No TAX_COLLECTION success row is written for a
            // refunded tax.
            SolidusGovernanceMod.LOGGER.error(
                "Treasury deposit of {} for {} ({} tax) FAILED - refunding the player and re-parking the debt",
                taxAmount, playerName, taxType);
            if (this.engine != null && server != null) {
                server.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(null, "System", "DEPOSIT_FAILED", taxAmount));
            }
            this.sendDiscordAlert("TAXATION", "Treasury Deposit Failed",
                "A " + taxType + " tax of " + String.format("%.2f", taxAmount) + " from " + playerName
                    + " was collected but the treasury credit failed. The player was refunded and the debt is re-parked.");
            return SolidusIntegration.addBalance(playerUuid, playerName, taxAmount)
                .thenApply(refundResult -> {
                    MinecraftServer refundServer = this.getServer();
                    if (refundResult == null || refundResult < 0.0) {
                        SolidusGovernanceMod.LOGGER.error(
                            "CRITICAL: refund of {} to {} ALSO failed after a failed treasury deposit - the amount is in limbo and needs manual reconciliation",
                            taxAmount, playerName);
                        if (this.engine != null && refundServer != null) {
                            refundServer.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(null, "System", "REFUND_FAILED", taxAmount));
                        }
                        // The debit could not be compensated - treat the tax as
                        // taken (it was) so the debt is never re-charged.
                        return taxAmount;
                    }
                    if (this.engine != null && refundServer != null) {
                        refundServer.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(null, "System", "REFUND", taxAmount));
                    }
                    // Player is whole again - the debt is still owed and the
                    // caller re-parks it exactly once.
                    return 0.0;
                })
                .exceptionally(refundEx -> {
                    SolidusGovernanceMod.LOGGER.error(
                        "Refund after failed treasury deposit threw for {} - treating the tax as taken (manual reconciliation may be needed)",
                        playerName, refundEx);
                    return taxAmount;
                });
        });
    }

    /**
     * Periodic sweeper for parked tax debts (called from GovernanceEngine.onTick).
     * Retries the oldest pending taxes; successful collections leave the
     * ledger, failures consume one of {@link TaxLedgerDatabase#MAX_ATTEMPTS}
     * attempts before the debt is dropped with an ERROR log.
     */
    public void processPendingTaxes() {
        if (this.ledger == null || !this.ledger.isInitialized() || this.engine == null) {
            return;
        }
        if (!this.engine.getConfig().getBool("taxation.enabled", false)) {
            return;
        }
        List<TaxLedgerDatabase.PendingTax> due = this.ledger.loadDuePendingTaxes(25, TaxLedgerDatabase.MAX_ATTEMPTS);
        for (TaxLedgerDatabase.PendingTax tax : due) {
            collectNow(tax.playerUuid(), tax.playerName(), tax.taxType(), tax.amount())
                .thenAccept(collected -> {
                    if (collected > 0.0) {
                        // B-6 fix (audit round 3): markCollected used to be an async
                        // fire-and-forget DELETE. A crash or SQL failure in that window
                        // left the debt row behind AFTER the money had moved, so the next
                        // sweep collected the SAME tax again. The completion mark is now
                        // SYNCHRONOUS in this callback (the callback already runs off the
                        // server thread), and a failed mark force-drops the row (the money
                        // is collected; keeping the row would double-charge) with an ERROR log.
                        this.ledger.markCollectedNow(tax.id());
                        SolidusGovernanceMod.LOGGER.info("Pending tax #{} collected on retry ({} for {})",
                            tax.id(), tax.amount(), tax.playerName());
                    } else {
                        this.ledger.markAttempt(tax.id(), "retry failed");
                    }
                })
                .exceptionally(ex -> {
                    this.ledger.markAttempt(tax.id(), ex.toString());
                    return null;
                });
        }
    }

    private void enqueuePendingTax(UUID playerUuid, String playerName, String taxType, double taxAmount, String reason) {
        if (this.ledger != null && this.ledger.isInitialized()) {
            this.ledger.enqueuePendingTax(playerUuid, playerName, taxType, taxAmount, reason);
        } else {
            SolidusGovernanceMod.LOGGER.warn(
                "Tax of {} could not be collected from {} and no tax ledger is wired - tax lost! ({})",
                taxAmount, playerName, reason);
        }
    }

    public void applyWealthDecay() {
        if (this.engine == null || !this.engine.getConfig().getBool("taxation.wealth-decay.enabled", false)) {
            return;
        }
        double rate = this.engine.getConfig().getDouble("taxation.wealth-decay.rate", 0.001);
        double threshold = this.engine.getConfig().getDouble("taxation.wealth-decay.threshold", 1000000.0);
        if (!Double.isFinite(rate) || rate <= 0.0 || !Double.isFinite(threshold) || threshold < 0.0) {
            return;
        }
        // B-7 fix (audit round 3): the decay used to (a) resolve accounts BY NAME
        // (wrong-account decay after a rename or on offline-mode servers - the same
        // bug the wealth-cap automaton already fixed UUID-first), (b) decay the
        // TREASURY account itself (draining collected tax revenue), and (c) mutate
        // FROZEN accounts (an asset freeze must lock the balance in place).
        String treasuryUuidRaw = this.engine.getConfig().getString("taxation.treasury.account", "");
        UUID treasuryUuid = null;
        if (!treasuryUuidRaw.isBlank()) {
            try {
                treasuryUuid = UUID.fromString(treasuryUuidRaw);
            } catch (IllegalArgumentException ignored) {
                // Already warned about in collectNow; skip here.
            }
        }
        final UUID treasury = treasuryUuid;
        SolidusIntegration.getTopBalances(1000).thenCompose(balances -> {
            ArrayList<CompletableFuture<Void>> decayFutures = new ArrayList<>();
            for (SolidusIntegration.BalanceEntry entry : balances) {
                // UUID-FIRST: skip entries whose account cannot be identified.
                UUID entryUuid = entry.uuid();
                if (entryUuid == null) {
                    entryUuid = SolidusIntegration.resolvePlayerUuid(entry.playerName());
                }
                if (entryUuid == null) {
                    SolidusGovernanceMod.LOGGER.warn(
                        "Wealth decay: could not resolve a UUID for '{}' - skipping (name-based decay can hit the wrong account after renames)",
                        entry.playerName());
                    continue;
                }
                if (entryUuid.equals(treasury)) {
                    continue; // never decay the tax treasury
                }
                if (this.engine.getAccountFreezer() != null && this.engine.getAccountFreezer().isFrozen(entryUuid)) {
                    continue; // a frozen balance is locked in place
                }
                final UUID decayUuid = entryUuid;
                CompletableFuture<Void> chain = SolidusIntegration.getBalance(decayUuid, entry.playerName())
                    .thenCompose(balanceBefore -> {
                        if (balanceBefore == null || !Double.isFinite(balanceBefore) || balanceBefore <= threshold) {
                            return CompletableFuture.completedFuture(null);
                        }
                        double decay = TaxEngine.roundTax((balanceBefore - threshold) * rate);
                        if (!Double.isFinite(decay) || decay <= 0.0) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return SolidusIntegration.subtractBalance(decayUuid, entry.playerName(), decay).thenAccept(newBalance -> {
                            if (newBalance != null && Double.isFinite(newBalance) && newBalance >= 0.0) {
                                MinecraftServer server = this.getServer();
                                if (this.engine != null && server != null) {
                                    server.execute(() -> this.engine.getAuditLogger().logWealthDecay(decayUuid, entry.playerName(), decay, balanceBefore, newBalance));
                                }
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        SolidusGovernanceMod.LOGGER.debug("Wealth decay failed for {}", entry.playerName(), ex);
                        return null;
                    });
                decayFutures.add(chain);
            }
            if (decayFutures.isEmpty()) return CompletableFuture.completedFuture(0);
            return CompletableFuture.allOf(decayFutures.toArray(new CompletableFuture[0])).thenApply(ignored -> decayFutures.size());
        }).thenAccept(decayed -> {
            if (decayed > 0) SolidusGovernanceMod.LOGGER.info("Wealth decay applied to {} players.", decayed);
        });
    }

    public void addBracket(double threshold, double rate) {
        this.progressiveBrackets.put(threshold, rate);
    }

    public void removeBracket(double threshold) {
        this.progressiveBrackets.remove(threshold);
    }

    public Map<Double, Double> getBrackets() {
        return Collections.unmodifiableMap(this.progressiveBrackets);
    }

    public void clearBrackets() {
        this.progressiveBrackets.clear();
    }

    private double getTransferTaxRate() {
        return this.engine.getConfig().getDouble("taxation.transfer.rate", 0.0);
    }

    private double getAuctionTaxRate() {
        return this.engine.getConfig().getDouble("taxation.auction.rate", 0.05);
    }

    private double getShopTaxRate() {
        return this.engine.getConfig().getDouble("taxation.shop.rate", 0.0);
    }

    private static double roundTax(double value) {
        return (double)Math.round(value * 100.0) / 100.0;
    }

    private MinecraftServer getServer() {
        return SolidusIntegration.getServer();
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
