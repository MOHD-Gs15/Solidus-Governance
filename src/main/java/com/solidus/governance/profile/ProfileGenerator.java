package com.solidus.governance.profile;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.profile.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ProfileGenerator {
    private final GovernanceEngine engine;

    public ProfileGenerator(GovernanceEngine engine) {
        this.engine = engine;
    }

    public CompletableFuture<PlayerProfile> generateProfile(UUID playerUuid, String playerName) {
        PlayerProfile profile = new PlayerProfile(playerUuid, playerName);
        return ((CompletableFuture)((CompletableFuture)((CompletableFuture)this.getBalance(playerUuid, playerName).thenCompose(balance -> {
            profile.setBalance((double)balance);
            return this.getRank(playerUuid, playerName, profile);
        })).thenCompose(v -> {
            profile.setFrozen(this.engine.getAccountFreezer().isFrozen(playerUuid));
            profile.setSuspicious(this.engine.getInterventionManager().isMarkedSuspicious(playerUuid));
            if (profile.isSuspicious()) {
                profile.setSuspiciousReason(this.engine.getInterventionManager().getSuspiciousReason(playerUuid));
            }
            return this.computeWeeklyStats(playerUuid, profile);
        })).thenApply(v -> {
            List<String> flags = this.generateFlags(profile);
            for (String flag : flags) {
                profile.addFlag(flag);
            }
            SolidusGovernanceMod.LOGGER.debug("Generated economy profile for {} (rank #{}, flags: {})", new Object[]{playerName, profile.getRank(), flags.size()});
            return profile;
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("Failed to generate profile for {}", (Object)playerName, ex);
            return profile;
        });
    }

    private CompletableFuture<Double> getBalance(UUID playerUuid, String playerName) {
        return SolidusIntegration.getBalance(playerUuid, playerName).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.warn("Failed to get balance for {}: {}", (Object)playerName, (Object)((Throwable)ex).getMessage());
            return 0.0;
        });
    }

    private CompletableFuture<Void> getRank(UUID playerUuid, String playerName, PlayerProfile profile) {
        return ((CompletableFuture)SolidusIntegration.getTopBalances(100000).thenAccept(balances -> {
            profile.setTotalPlayers(balances.size());
            for (SolidusIntegration.BalanceEntry entry : balances) {
                if (!entry.playerName().equalsIgnoreCase(playerName)) continue;
                profile.setRank(entry.rank());
                return;
            }
            profile.setRank(balances.size() + 1);
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.warn("Failed to get rank for {}: {}", (Object)playerName, (Object)((Throwable)ex).getMessage());
            profile.setRank(0);
            profile.setTotalPlayers(0);
            return null;
        });
    }

    private CompletableFuture<Void> computeWeeklyStats(UUID playerUuid, PlayerProfile profile) {
        long sevenDaysAgo = System.currentTimeMillis() - 604800000L;
        return CompletableFuture.runAsync(() -> {
            AuditDatabase.PlayerStats stats = this.engine.getAuditDatabase().getPlayerStats(playerUuid, sevenDaysAgo);
            if (stats != null) {
                profile.setIncome7d(stats.totalIncome());
                profile.setExpenses7d(stats.totalExpenses());
                profile.setTaxPaid7d(stats.taxPaid());
                profile.setTransactionCount7d(stats.transactionCount());
                double netChange = stats.totalIncome() - stats.totalExpenses();
                profile.setNetChange7d(netChange);
                double balance = profile.getBalance();
                if (balance > 0.0) {
                    profile.setNetChangePercent7d(netChange / balance * 100.0);
                } else {
                    profile.setNetChangePercent7d(0.0);
                }
            }
        });
    }

    private List<String> generateFlags(PlayerProfile profile) {
        double net24h;
        double gainPercent;
        int txCount24h;
        ArrayList<String> flags = new ArrayList<String>();
        long oneDayAgo = System.currentTimeMillis() - 86400000L;
        AuditDatabase.PlayerStats stats24h = this.engine.getAuditDatabase().getPlayerStats(profile.getPlayerUuid(), oneDayAgo);
        double balance = profile.getBalance();
        double total24h = stats24h != null ? stats24h.totalIncome() + stats24h.totalExpenses() : 0.0;
        int n = txCount24h = stats24h != null ? stats24h.transactionCount() : 0;
        if (balance > 0.0 && total24h > balance * 0.5) {
            String volumeStr = total24h >= 1000.0 ? String.format("%.0fK", total24h / 1000.0) : String.format("%.0f", total24h);
            flags.add("High daily transfer volume: " + volumeStr);
        }
        if (stats24h != null && balance > 0.0 && (gainPercent = (net24h = stats24h.totalIncome() - stats24h.totalExpenses()) / balance * 100.0) > 30.0) {
            flags.add("Rapid wealth gain: +" + String.format("%.0f%%", gainPercent) + " in 24h");
        }
        if (stats24h != null && balance > 0.0) {
            double net24h2 = stats24h.totalIncome() - stats24h.totalExpenses();
            double lossPercent = Math.abs(net24h2) / balance * 100.0;
            if (net24h2 < 0.0 && lossPercent > 50.0) {
                flags.add("Rapid wealth loss: -" + String.format("%.0f%%", lossPercent) + " in 24h");
            }
        }
        if (txCount24h > 100) {
            flags.add("High transaction frequency: " + txCount24h + " in 24h");
        }
        long sevenDaysAgo = System.currentTimeMillis() - 604800000L;
        Long firstEntryTime = this.engine.getAuditDatabase().getFirstAuditTimestamp(profile.getPlayerUuid());
        if (firstEntryTime != null && firstEntryTime > sevenDaysAgo) {
            flags.add("New account");
        }
        if (profile.isFrozen()) {
            flags.add("Frozen account");
        }
        if (profile.isSuspicious()) {
            flags.add("Suspicious account");
        }
        return flags;
    }
}
