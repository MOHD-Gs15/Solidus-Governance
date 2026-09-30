package com.solidus.governance.integration;

import com.solidus.api.SolidusApi;
import com.solidus.api.SolidusApiAccess;
import com.solidus.governance.SolidusGovernanceMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

/**
 * SolidusIntegration — direct bridge to Solidus Core through the
 * <b>solidus-api</b> contract (family 2.3.0, audit W-5 fix).
 *
 * <p><b>What changed from 2.1.x:</b> this class used to hold a stack of
 * {@link java.lang.invoke.MethodHandle}s that reflectively reached into
 * Core's API class AND into Core internals
 * ({@code EconomyEngine.getStorage().setBalance(...)}). A Core refactor
 * broke those handles silently, and the fallback was "standalone DB mode"
 * — an economy with no limits, no freezes and no taxes while the owner
 * believed enforcement was active.</p>
 *
 * <p>Now Governance declares {@code "depends": { "solidus": ">=2.3.0 <3.0.0" }}
 * in its fabric.mod.json, so Fabric's loader itself refuses to load
 * Governance against a missing or incompatible Core — and this class makes
 * plain, compile-checked calls against the versioned contract jar
 * ({@code libs/solidus-api-<version>.jar}). If the contract changes shape,
 * Governance fails to COMPILE, not to enforce.</p>
 *
 * <p>The public surface of this class (method names, signatures and the
 * mirrored {@link BalanceEntry}/{@link EconomyStats} records) is unchanged
 * from 2.1.x, so the rest of Governance needs no changes.</p>
 */
public class SolidusIntegration {
    private static volatile boolean solidusLoaded = false;
    private static volatile SolidusApi apiInstance = null;
    private static volatile MinecraftServer server;
    private static final ConcurrentHashMap<String, UUID> nameToUuidCache = new ConcurrentHashMap<>();

    private SolidusIntegration() {
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static void initialize() {
        // Loader-level guarantee (fabric.mod.json depends): Core 2.3.0+ is on
        // the classpath. The remaining failure modes are initialization-order
        // (Core's entrypoint not yet run) — re-checked at every server start
        // by CoreHookBridge.registerIfNeeded, mirroring the 2.1.x behavior.
        try {
            if (!FabricLoader.getInstance().isModLoaded("solidus")) {
                // Unreachable with a correct fabric.mod.json; kept as a hard,
                // loud guard for misbuilt jars and dev-time misconfigurations.
                SolidusGovernanceMod.LOGGER.error(
                    "Solidus Core not detected despite the declared dependency - "
                        + "Governance cannot enforce. Check the jar layout!");
                solidusLoaded = false;
                return;
            }
            SolidusApi api = SolidusApiAccess.get();
            if (api == null) {
                SolidusGovernanceMod.LOGGER.warn(
                    "SolidusApi contract not yet installed (Core still initializing). "
                        + "CoreHookBridge will retry at server start.");
                solidusLoaded = false;
                return;
            }
            apiInstance = api;
            solidusLoaded = true;
            SolidusGovernanceMod.LOGGER.info(
                "Solidus Core integration established through the solidus-api contract (Core {}). "
                    + "Governance has full API access.",
                api.getCoreVersion());
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to integrate with Solidus Core: {}", e.toString(), e);
            solidusLoaded = false;
        }
    }

    public static boolean isSolidusLoaded() {
        return solidusLoaded;
    }

    /**
     * Returns the SolidusApi contract instance (may be null when Core is
     * absent or detection has not succeeded yet). Exposed for the
     * CoreHookBridge, which registers the enforcement hook directly.
     *
     * @since 2.3.0 (was Object — now the typed contract)
     */
    public static SolidusApi getApi() {
        return apiInstance;
    }

    public static UUID resolvePlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        UUID cached = nameToUuidCache.get(playerName);
        if (cached != null) {
            return cached;
        }
        if (server != null) {
            try {
                ServerPlayer onlinePlayer = server.getPlayerList().getPlayerByName(playerName);
                if (onlinePlayer != null) {
                    UUID uuid = onlinePlayer.getUUID();
                    nameToUuidCache.put(playerName, uuid);
                    return uuid;
                }
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.debug("Failed to lookup online player {} (possibly called from async thread)", (Object)playerName);
            }
            try {
                Optional result;
                UserNameToIdResolver nameToIdCache = server.services().nameToIdCache();
                if (nameToIdCache != null && (result = nameToIdCache.get(playerName)).isPresent()) {
                    UUID uuid = ((NameAndId)result.get()).id();
                    nameToUuidCache.put(playerName, uuid);
                    return uuid;
                }
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.debug("Failed to lookup UUID for player {} via profile cache", (Object)playerName, (Object)e);
            }
        }
        return null;
    }

    public static void clearUuidCache() {
        nameToUuidCache.clear();
    }

    public static CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
        UUID effectiveUuid;
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        UUID uUID = effectiveUuid = uuid != null ? uuid : SolidusIntegration.resolvePlayerUuid(playerName);
        if (effectiveUuid == null) {
            SolidusGovernanceMod.LOGGER.warn("Cannot get balance: unable to resolve UUID for player '{}'", (Object)playerName);
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            return apiInstance.getBalance(effectiveUuid, playerName);
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get balance for {}", (Object)effectiveUuid, (Object)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
        UUID effectiveUuid;
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        UUID uUID = effectiveUuid = uuid != null ? uuid : SolidusIntegration.resolvePlayerUuid(playerName);
        if (effectiveUuid == null) {
            SolidusGovernanceMod.LOGGER.warn("Cannot add balance: unable to resolve UUID for player '{}'", (Object)playerName);
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            return apiInstance.addBalance(effectiveUuid, playerName, amount);
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to add balance for {}", (Object)effectiveUuid, (Object)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
        UUID effectiveUuid;
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        UUID uUID = effectiveUuid = uuid != null ? uuid : SolidusIntegration.resolvePlayerUuid(playerName);
        if (effectiveUuid == null) {
            SolidusGovernanceMod.LOGGER.warn("Cannot subtract balance: unable to resolve UUID for player '{}'", (Object)playerName);
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            return apiInstance.subtractBalance(effectiveUuid, playerName, amount);
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to subtract balance for {}", (Object)effectiveUuid, (Object)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    /**
     * Administrative balance overwrite (rollback restores, corrections).
     * Used to reach into Core's SQLiteStorage internal via MethodHandles —
     * now a first-class, ledger-journaled API operation with an audit
     * reason (audit W-5).
     */
    public static CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount) {
        UUID effectiveUuid;
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID uUID = effectiveUuid = uuid != null ? uuid : SolidusIntegration.resolvePlayerUuid(playerName);
        if (effectiveUuid == null) {
            SolidusGovernanceMod.LOGGER.warn("Cannot set balance: unable to resolve UUID for player '{}'", (Object)playerName);
            return CompletableFuture.completedFuture(false);
        }
        try {
            return apiInstance.setBalance(effectiveUuid, playerName, amount,
                "governance restore/correction via solidus-api");
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to set balance for {}", (Object)effectiveUuid, (Object)e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public static CompletableFuture<List<BalanceEntry>> getTopBalances(int limit) {
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            return apiInstance.getTopBalances(limit).thenApply(entries -> {
                ArrayList<BalanceEntry> result = new ArrayList<>(entries.size());
                for (com.solidus.api.BalanceEntry entry : entries) {
                    result.add(new BalanceEntry(entry.uuid(), entry.rank(),
                        entry.playerName(), entry.balance()));
                }
                return (List<BalanceEntry>)result;
            });
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get top balances", e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /**
     * Economy-wide aggregates computed inside Core with one SQL query (R28):
     * count, mean balance, money supply, and the Gini coefficient - without
     * materializing any balance rows. Returns null (inside a completed
     * future) when Core is absent, signaling callers to fall back to the
     * legacy {@link #getTopBalances(int)} row pull.
     */
    public static CompletableFuture<EconomyStats> getEconomyStats() {
        if (!solidusLoaded || apiInstance == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return apiInstance.getEconomyStats().thenApply(stats -> stats == null
                ? null
                : new EconomyStats(stats.playerCount(), stats.avgBalance(),
                    stats.totalSupply(), stats.giniCoefficient()));
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get economy stats", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Mirror of the solidus-api EconomyStats contract record. */
    public record EconomyStats(int playerCount, double avgBalance, double totalSupply, double giniCoefficient) {
    }

    public record BalanceEntry(UUID uuid, int rank, String playerName, double balance) {
    }
}
