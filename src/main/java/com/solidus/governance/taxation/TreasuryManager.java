package com.solidus.governance.taxation;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class TreasuryManager {
    private GovernanceEngine engine;
    private volatile MinecraftServer server;

    public TreasuryManager(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public UUID getTreasuryUuid() {
        String uuidStr = this.engine.getConfig().getString("taxation.treasury.account", "");
        if (uuidStr.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuidStr);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    public CompletableFuture<Double> getTreasuryBalanceAsync() {
        UUID treasury = this.getTreasuryUuid();
        if (treasury == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        return ((CompletableFuture)SolidusIntegration.getBalance(treasury, "Treasury").thenApply(balance -> balance != null && balance >= 0.0 ? balance : 0.0)).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("Failed to get treasury balance", ex);
            return 0.0;
        });
    }

    public CompletableFuture<Boolean> burnFromTreasuryAsync(UUID adminUuid, String adminName, double amount) {
        UUID treasury = this.getTreasuryUuid();
        if (treasury == null) {
            return CompletableFuture.completedFuture(false);
        }
        return ((CompletableFuture)SolidusIntegration.subtractBalance(treasury, "Treasury", amount).thenApply(result -> {
            if (result != null && result >= 0.0) {
                if (this.engine != null && this.server != null) {
                    this.server.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(adminUuid, adminName, "BURN", amount));
                }
                return true;
            }
            return false;
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("Failed to burn from treasury", ex);
            return false;
        });
    }

    public CompletableFuture<Integer> redistributeToOnlineAsync(UUID adminUuid, String adminName, double totalAmount) {
        UUID treasury = this.getTreasuryUuid();
        if (treasury == null || this.server == null) {
            return CompletableFuture.completedFuture(0);
        }
        return this.getTreasuryBalanceAsync().thenCompose(treasuryBalance -> {
            if (treasuryBalance <= 0.0) {
                return CompletableFuture.completedFuture(0);
            }
            double distributeAmount = Math.min(totalAmount, treasuryBalance);
            CompletableFuture<RedistributionContext> contextFuture = new CompletableFuture<>();
            this.server.execute(() -> {
                try {
                    List players = this.server.getPlayerList().getPlayers();
                    int onlineCount = players.size();
                    if (onlineCount == 0) {
                        contextFuture.complete(null);
                        return;
                    }
                    double perPlayer = (double)Math.round(distributeAmount / (double)onlineCount * 100.0) / 100.0;
                    UUID[] playerUuids = new UUID[onlineCount];
                    String[] playerNames = new String[onlineCount];
                    for (int i = 0; i < onlineCount; ++i) {
                        playerUuids[i] = ((ServerPlayer)players.get(i)).getUUID();
                        playerNames[i] = ((ServerPlayer)players.get(i)).getName().getString();
                    }
                    contextFuture.complete(new RedistributionContext(distributeAmount, perPlayer, playerUuids, playerNames));
                }
                catch (Exception e) {
                    contextFuture.completeExceptionally(e);
                }
            });
            return contextFuture.thenCompose(ctx -> {
                if (ctx == null) {
                    return CompletableFuture.completedFuture(0);
                }
                return SolidusIntegration.subtractBalance(treasury, "Treasury", ctx.distributeAmount).thenCompose(treasuryResult -> {
                    if (treasuryResult == null || treasuryResult < 0.0) {
                        return CompletableFuture.completedFuture(0);
                    }
                    List<CompletableFuture<Integer>> distributionFutures = new ArrayList<>();
                    for (int i = 0; i < ctx.playerUuids.length; ++i) {
                        distributionFutures.add(SolidusIntegration.addBalance(ctx.playerUuids[i], ctx.playerNames[i], ctx.perPlayer).thenApply(result -> result != null && result >= 0.0 ? 1 : 0).exceptionally(ex -> 0));
                    }
                    return CompletableFuture.allOf(distributionFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
                        int distributed = 0;
                        for (CompletableFuture<Integer> future : distributionFutures) {
                            distributed += future.join();
                        }
                        if (this.engine != null && this.server != null) {
                            this.server.execute(() -> this.engine.getAuditLogger().logTreasuryOperation(adminUuid, adminName, "REDISTRIBUTE", ctx.distributeAmount));
                        }
                        SolidusGovernanceMod.LOGGER.info("Redistributed {} to {} online players ({} each)", new Object[]{String.format("%.2f", ctx.distributeAmount), distributed, String.format("%.2f", ctx.perPlayer)});
                        return distributed;
                    });
                });
            });
        });
    }

    public void setTreasuryAccount(UUID adminUuid, String adminName, UUID treasuryUuid) {
        this.engine.getConfig().set("taxation.treasury.account", treasuryUuid.toString());
        this.engine.getAuditLogger().logConfigChange(adminUuid, adminName, "taxation.treasury.account", "", treasuryUuid.toString());
        SolidusGovernanceMod.LOGGER.info("Treasury account set to: {}", (Object)treasuryUuid);
    }

    private record RedistributionContext(double distributeAmount, double perPlayer, UUID[] playerUuids, String[] playerNames) {
    }
}
