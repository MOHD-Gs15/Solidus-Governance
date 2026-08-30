package com.solidus.governance.taxation;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.util.ArrayList;
import java.util.Collections;
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

    public TaxEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
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

    public CompletableFuture<Double> collectTaxAsync(UUID playerUuid, String playerName, String taxType, double taxAmount) {
        if (!Double.isFinite(taxAmount) || taxAmount <= 0.0 || this.engine == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        return SolidusIntegration.getBalance(playerUuid, playerName)
            .thenCompose(balanceBeforeValue -> {
                double before = balanceBeforeValue != null && Double.isFinite(balanceBeforeValue) && balanceBeforeValue >= 0.0 ? balanceBeforeValue : 0.0;
                return SolidusIntegration.subtractBalance(playerUuid, playerName, taxAmount)
                    .thenCompose(newBalance -> {
                        if (newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                            return CompletableFuture.completedFuture(0.0);
                        }
                        String treasuryUuid = this.engine.getConfig().getString("taxation.treasury.account", "");
                        CompletableFuture<Void> treasuryTransfer = CompletableFuture.completedFuture(null);
                        if (!treasuryUuid.isBlank()) {
                            try {
                                UUID treasury = UUID.fromString(treasuryUuid);
                                treasuryTransfer = SolidusIntegration.addBalance(treasury, "Treasury", taxAmount)
                                    .thenAccept(result -> {
                                        MinecraftServer treasuryServer = this.getServer();
                                        if (this.engine != null && treasuryServer != null) {
                                            treasuryServer.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(null, "System", "DEPOSIT", taxAmount));
                                        }
                                    });
                            } catch (IllegalArgumentException ex) {
                                SolidusGovernanceMod.LOGGER.warn("Invalid treasury UUID in governance config: {}", treasuryUuid);
                            }
                        }
                        return treasuryTransfer.thenApply(ignored -> {
                            MinecraftServer server = this.getServer();
                            if (this.engine != null && server != null) {
                                server.execute(() -> this.engine.getAuditLogger().logTaxCollection(playerUuid, playerName, taxType, taxAmount, before, newBalance));
                            }
                            if (taxAmount >= 10000.0) {
                                this.sendDiscordAlert("TAXATION", "Large Tax Collection", "Type: " + taxType + ", Amount: " + String.format("%.2f", taxAmount) + ", Player: " + playerName);
                            }
                            return taxAmount;
                        });
                    });
            })
            .exceptionally(ex -> {
                SolidusGovernanceMod.LOGGER.error("Failed to collect tax from {}", playerName, ex);
                return 0.0;
            });
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
        SolidusIntegration.getTopBalances(1000).thenCompose(balances -> {
            ArrayList<CompletableFuture<Void>> decayFutures = new ArrayList<>();
            for (SolidusIntegration.BalanceEntry entry : balances) {
                CompletableFuture<Void> chain = SolidusIntegration.getBalance(null, entry.playerName())
                    .thenCompose(balanceBefore -> {
                        if (balanceBefore == null || !Double.isFinite(balanceBefore) || balanceBefore <= threshold) {
                            return CompletableFuture.completedFuture(null);
                        }
                        double decay = TaxEngine.roundTax((balanceBefore - threshold) * rate);
                        if (!Double.isFinite(decay) || decay <= 0.0) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return SolidusIntegration.subtractBalance(null, entry.playerName(), decay).thenAccept(newBalance -> {
                            if (newBalance != null && Double.isFinite(newBalance) && newBalance >= 0.0) {
                                UUID resolvedUuid = SolidusIntegration.resolvePlayerUuid(entry.playerName());
                                MinecraftServer server = this.getServer();
                                if (this.engine != null && server != null) {
                                    server.execute(() -> this.engine.getAuditLogger().logWealthDecay(resolvedUuid, entry.playerName(), decay, balanceBefore, newBalance));
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
