package com.solidus.governance.integration;

import com.solidus.governance.SolidusGovernanceMod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

public class SolidusIntegration {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
    private static volatile boolean solidusLoaded = false;
    private static volatile Object apiInstance = null;
    private static MethodHandle getBalanceOfflineHandle;
    private static MethodHandle addBalanceOfflineHandle;
    private static MethodHandle subtractBalanceOfflineHandle;
    private static MethodHandle getTopBalancesHandle;
    private static MethodHandle getEconomyEngineHandle;
    private static MethodHandle getStorageHandle;
    private static MethodHandle storageSetBalanceHandle;
    private static volatile MinecraftServer server;
    private static final ConcurrentHashMap<String, UUID> nameToUuidCache;

    private SolidusIntegration() {
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static void initialize() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("solidus")) {
                SolidusGovernanceMod.LOGGER.info("Solidus Core not detected. Governance will operate in standalone DB mode.");
                solidusLoaded = false;
                return;
            }
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            MethodHandle getInstanceHandle = LOOKUP.findStatic(apiClass, "getInstance", MethodType.methodType(apiClass));
            apiInstance = getInstanceHandle.invoke();
            if (apiInstance == null) {
                SolidusGovernanceMod.LOGGER.warn("SolidusAPI instance is null. Governance will operate in standalone DB mode.");
                solidusLoaded = false;
                return;
            }
            getBalanceOfflineHandle = LOOKUP.findVirtual(apiClass, "getBalanceOffline", MethodType.methodType(CompletableFuture.class, UUID.class, String.class));
            addBalanceOfflineHandle = LOOKUP.findVirtual(apiClass, "addBalanceOffline", MethodType.methodType(CompletableFuture.class, UUID.class, String.class, Double.TYPE));
            subtractBalanceOfflineHandle = LOOKUP.findVirtual(apiClass, "subtractBalanceOffline", MethodType.methodType(CompletableFuture.class, UUID.class, String.class, Double.TYPE));
            getTopBalancesHandle = LOOKUP.findVirtual(apiClass, "getTopBalances", MethodType.methodType(CompletableFuture.class, Integer.TYPE));
            getEconomyEngineHandle = LOOKUP.findVirtual(apiClass, "getEconomyEngine", MethodType.methodType(Class.forName("com.solidus.economy.EconomyEngine")));
            Class<?> economyEngineClass = Class.forName("com.solidus.economy.EconomyEngine");
            getStorageHandle = LOOKUP.findVirtual(economyEngineClass, "getStorage", MethodType.methodType(Class.forName("com.solidus.economy.SQLiteStorage")));
            Class<?> storageClass = Class.forName("com.solidus.economy.SQLiteStorage");
            storageSetBalanceHandle = LOOKUP.findVirtual(storageClass, "setBalance", MethodType.methodType(CompletableFuture.class, UUID.class, String.class, Double.TYPE));
            solidusLoaded = true;
            SolidusGovernanceMod.LOGGER.info("Solidus Core integration established. Governance has full API access.");
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.warn("Failed to integrate with Solidus Core: {}. Operating in standalone DB mode.", (Object)e.getMessage());
            solidusLoaded = false;
        }
    }

    public static boolean isSolidusLoaded() {
        return solidusLoaded;
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
            return (CompletableFuture<Double>) getBalanceOfflineHandle.invoke(apiInstance, effectiveUuid, playerName);
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
            return (CompletableFuture<Double>) addBalanceOfflineHandle.invoke(apiInstance, effectiveUuid, playerName, amount);
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
            return (CompletableFuture<Double>) subtractBalanceOfflineHandle.invoke(apiInstance, effectiveUuid, playerName, amount);
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to subtract balance for {}", (Object)effectiveUuid, (Object)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

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
            Object engine = getEconomyEngineHandle.invoke(apiInstance);
            if (engine == null) {
                return CompletableFuture.completedFuture(false);
            }
            Object storage = getStorageHandle.invoke(engine);
            if (storage == null) {
                return CompletableFuture.completedFuture(false);
            }
            return (CompletableFuture<Boolean>) storageSetBalanceHandle.invoke(storage, effectiveUuid, playerName, amount);
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
            CompletableFuture<?> rawFuture = (CompletableFuture<?>) getTopBalancesHandle.invoke(apiInstance, limit);
            return rawFuture.thenApply(rawObject -> {
                List<?> rawList = rawObject instanceof List<?> list ? list : List.of();
                ArrayList<BalanceEntry> result = new ArrayList<>(rawList.size());
                for (Object entry : rawList) {
                    try {
                        int rank = (Integer)entry.getClass().getMethod("rank", new Class[0]).invoke(entry, new Object[0]);
                        String name = (String)entry.getClass().getMethod("playerName", new Class[0]).invoke(entry, new Object[0]);
                        double balance = (Double)entry.getClass().getMethod("balance", new Class[0]).invoke(entry, new Object[0]);
                        result.add(new BalanceEntry(rank, name, balance));
                    }
                    catch (Exception e) {
                        SolidusGovernanceMod.LOGGER.debug("Failed to map BalanceEntry via reflection", (Throwable)e);
                    }
                }
                return result;
            });
        }
        catch (Throwable e) {
            SolidusGovernanceMod.LOGGER.error("Failed to get top balances", e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    static {
        nameToUuidCache = new ConcurrentHashMap();
    }

    public record BalanceEntry(int rank, String playerName, double balance) {
    }
}
