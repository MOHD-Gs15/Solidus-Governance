package com.solidus.governance.simulation;

import com.solidus.governance.GovernanceConfig;
import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import com.solidus.governance.simulation.SimulationState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public class SimulationEngine {
    private static final long TICK_FAST_THRESHOLD = 1000000L;
    private static final long TICK_NORMAL_THRESHOLD = 2000000L;
    private static final long TICK_SLOW_THRESHOLD = 5000000L;
    private static final int MIN_DELAY_MS = 0;
    private static final int MAX_DELAY_MS = 500;
    private static final int DELAY_INCREMENT = 10;
    private static final int DELAY_DECREMENT = 5;
    private static final double TPS_CRITICAL = 10.0;
    private static final double TPS_SLOW = 15.0;
    private static final double TPS_HEALTHY = 19.0;
    private static final double TPS_IDEAL = 20.0;
    private static final int SAMPLE_MIN_DEFAULT = 20;
    private static final int SAMPLE_MAX_DEFAULT = 500;
    private static final double SAMPLE_PERCENTAGE_DEFAULT = 0.15;
    private static final int TICK_HISTORY_SIZE = 20;
    private final AtomicInteger adaptiveSampleSize = new AtomicInteger(20);
    private final AtomicInteger cachedActiveAccountCount = new AtomicInteger(-1);
    private final AtomicLong lastAccountCountRefreshMs = new AtomicLong(0L);
    private static final long ACCOUNT_COUNT_REFRESH_INTERVAL_MS = 10800000L;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong totalTicks = new AtomicLong(0L);
    private final AtomicInteger currentDelayMs = new AtomicInteger(50);
    private final AtomicLong avgTickNanos = new AtomicLong(0L);
    private final AtomicLong lastTickNanos = new AtomicLong(0L);
    private final AtomicLong lastEstimatedTps = new AtomicLong(Double.doubleToLongBits(20.0));
    private final AtomicLongArray tickHistory = new AtomicLongArray(20);
    private final AtomicInteger tickHistoryIndex = new AtomicInteger(0);
    private final GovernanceConfig config;
    private final GovernanceEngine engine;
    private volatile ExecutorService simulationExecutor;
    private volatile SimulationInsight latestInsight;

    public SimulationEngine(GovernanceConfig config, GovernanceEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    public boolean start() {
        if (this.running.getAndSet(true)) {
            return false;
        }
        this.paused.set(false);
        this.currentDelayMs.set(50);
        this.totalTicks.set(0L);
        this.tickHistoryIndex.set(0);
        for (int i = 0; i < 20; ++i) {
            this.tickHistory.set(i, 0L);
        }
        this.simulationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Simulation");
            t.setDaemon(true);
            t.setPriority(4);
            return t;
        });
        this.simulationExecutor.submit(this::simulationLoop);
        SolidusGovernanceMod.LOGGER.info("Simulation Engine: STARTED (adaptive throttling enabled)");
        return true;
    }

    public boolean stop() {
        if (!this.running.getAndSet(false)) {
            return false;
        }
        this.paused.set(false);
        if (this.simulationExecutor != null) {
            this.simulationExecutor.shutdown();
            try {
                if (!this.simulationExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    this.simulationExecutor.shutdownNow();
                    SolidusGovernanceMod.LOGGER.warn("Simulation Engine: Forced shutdown (tick took too long to complete)");
                }
            }
            catch (InterruptedException e) {
                this.simulationExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        SolidusGovernanceMod.LOGGER.info("Simulation Engine: STOPPED ({} ticks executed)", (Object)this.totalTicks.get());
        return true;
    }

    public void pause() {
        if (this.running.get() && !this.paused.getAndSet(true)) {
            SolidusGovernanceMod.LOGGER.info("Simulation Engine: PAUSED (server under stress)");
        }
    }

    public void resume() {
        if (this.paused.getAndSet(false)) {
            SolidusGovernanceMod.LOGGER.info("Simulation Engine: RESUMED");
        }
    }

    private void simulationLoop() {
        while (this.running.get()) {
            try {
                double tps = this.estimateServerTps();
                this.lastEstimatedTps.set(Double.doubleToLongBits(tps));
                if (tps < 10.0) {
                    if (!this.paused.get()) {
                        this.pause();
                    }
                    Thread.sleep(1000L);
                    continue;
                }
                if (tps < 15.0) {
                    int delay = this.currentDelayMs.get();
                    this.currentDelayMs.set(Math.min(500, delay + 50));
                } else if (this.paused.get() && tps >= 19.0) {
                    this.resume();
                }
                if (this.paused.get()) {
                    Thread.sleep(500L);
                    continue;
                }
                long startNanos = System.nanoTime();
                this.runOneTick();
                long elapsedNanos = System.nanoTime() - startNanos;
                this.lastTickNanos.set(elapsedNanos);
                this.totalTicks.incrementAndGet();
                int idx = Math.floorMod(this.tickHistoryIndex.getAndIncrement(), 20);
                this.tickHistory.set(idx, elapsedNanos);
                this.computeAverageTickTime();
                this.adjustThrottle(elapsedNanos, tps);
                int delay = this.currentDelayMs.get();
                if (delay <= 0) continue;
                Thread.sleep(delay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.error("Simulation Engine: Error in simulation tick", (Throwable)e);
                this.currentDelayMs.set(Math.min(500, this.currentDelayMs.get() + 20));
                try {
                    Thread.sleep(this.currentDelayMs.get());
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void runOneTick() {
        MinecraftServer server = SolidusIntegration.getServer();
        if (server == null) {
            return;
        }
        int sampleSize = this.computeAdaptiveSampleSize();
        ((CompletableFuture)SolidusIntegration.getTopBalances(sampleSize).thenAccept(balanceEntries -> {
            SimulationInsight insight;
            if (balanceEntries.isEmpty()) {
                return;
            }
            int sampleMin = this.config.getInt("simulation.sample-min", 20);
            if (balanceEntries.size() < sampleSize) {
                this.adaptiveSampleSize.set(Math.max(sampleMin, balanceEntries.size()));
            }
            double gini = this.computeGiniCoefficient((List<SolidusIntegration.BalanceEntry>)balanceEntries);
            double totalSupply = 0.0;
            double maxBalance = 0.0;
            String wealthiestPlayer = "N/A";
            ArrayList<Double> balances = new ArrayList<Double>();
            for (SolidusIntegration.BalanceEntry entry : balanceEntries) {
                totalSupply += entry.balance();
                balances.add(entry.balance());
                if (!(entry.balance() > maxBalance)) continue;
                maxBalance = entry.balance();
                wealthiestPlayer = entry.playerName();
            }
            double avgBalance = totalSupply / (double)balanceEntries.size();
            double supplyGrowthRate = 0.0;
            double inflationTrend = 0.0;
            if (this.latestInsight != null) {
                if (this.latestInsight.totalMoneySupply() > 0.0) {
                    supplyGrowthRate = (totalSupply - this.latestInsight.totalMoneySupply()) / this.latestInsight.totalMoneySupply() * 100.0;
                }
                inflationTrend = supplyGrowthRate;
            }
            List<String> recommendations = this.generateRecommendations(gini, supplyGrowthRate, maxBalance, avgBalance);
            this.latestInsight = insight = new SimulationInsight(System.currentTimeMillis(), totalSupply, avgBalance, maxBalance, wealthiestPlayer, gini, supplyGrowthRate, inflationTrend, balanceEntries.size(), recommendations);
            if (gini > 0.7) {
                SolidusGovernanceMod.LOGGER.warn("Simulation: HIGH wealth inequality detected (Gini={})", (Object)String.format("%.3f", gini));
            }
            if (Math.abs(inflationTrend) > 10.0) {
                SolidusGovernanceMod.LOGGER.warn("Simulation: Significant {} detected ({})", (Object)(inflationTrend > 0.0 ? "inflation" : "deflation"), (Object)String.format("%.1f%%", inflationTrend));
            }
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.debug("Simulation tick failed to fetch balance data: {}", (Object)((Throwable)ex).getMessage());
            return null;
        });
    }

    private double computeGiniCoefficient(List<SolidusIntegration.BalanceEntry> entries) {
        if (entries.size() < 2) {
            return 0.0;
        }
        ArrayList<Double> positiveBalances = new ArrayList<Double>();
        int debtCount = 0;
        double totalDebt = 0.0;
        for (SolidusIntegration.BalanceEntry entry : entries) {
            if (entry.balance() >= 0.0) {
                positiveBalances.add(entry.balance());
                continue;
            }
            ++debtCount;
            totalDebt += Math.abs(entry.balance());
        }
        if (debtCount > 0) {
            SolidusGovernanceMod.LOGGER.info("Simulation: {} account(s) in debt detected (total debt: {}). Gini computed on {} positive-balance accounts only.", new Object[]{debtCount, String.format("%.2f", totalDebt), positiveBalances.size()});
        }
        if (positiveBalances.size() < 2) {
            SolidusGovernanceMod.LOGGER.warn("Simulation: Fewer than 2 positive-balance accounts ({}). Gini coefficient is not meaningful. Returning 0.0.", (Object)positiveBalances.size());
            return 0.0;
        }
        double[] values = positiveBalances.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = values.length;
        double sumValues = 0.0;
        double weightedSum = 0.0;
        for (int i = 0; i < n; ++i) {
            sumValues += values[i];
            weightedSum += (double)(i + 1) * values[i];
        }
        if (sumValues <= 0.0) {
            return 0.0;
        }
        double gini = 2.0 * weightedSum / ((double)n * sumValues) - ((double)n + 1.0) / (double)n;
        return Math.max(0.0, Math.min(1.0, gini));
    }

    private int computeAdaptiveSampleSize() {
        MinecraftServer server = SolidusIntegration.getServer();
        int configSampleSize = this.config.getInt("simulation.sample-size", -1);
        if (configSampleSize > 0) {
            this.adaptiveSampleSize.set(configSampleSize);
            return configSampleSize;
        }
        if (server == null) {
            return this.adaptiveSampleSize.get();
        }
        try {
            int estimatedTotalPlayers;
            int sampleMin = this.config.getInt("simulation.sample-min", 20);
            int sampleMax = this.config.getInt("simulation.sample-max", 500);
            double samplePercentage = this.config.getDouble("simulation.sample-percentage", 0.15);
            int refreshHours = this.config.getInt("simulation.active-accounts-refresh-hours", 3);
            long refreshIntervalMs = (long)(refreshHours * 60 * 60) * 1000L;
            long now = System.currentTimeMillis();
            long lastRefresh = this.lastAccountCountRefreshMs.get();
            int cachedCount = this.cachedActiveAccountCount.get();
            if (cachedCount < 0 || now - lastRefresh > refreshIntervalMs) {
                int dbCount = this.queryActiveAccountCount();
                if (dbCount > 0) {
                    this.cachedActiveAccountCount.set(dbCount);
                    this.lastAccountCountRefreshMs.set(now);
                    estimatedTotalPlayers = dbCount;
                } else {
                    int onlinePlayers = server.getPlayerList().getPlayerCount();
                    estimatedTotalPlayers = Math.max(onlinePlayers * 3, sampleMin);
                }
            } else {
                estimatedTotalPlayers = cachedCount;
            }
            int sampleSize = (int)Math.ceil((double)estimatedTotalPlayers * samplePercentage);
            sampleSize = Math.max(sampleMin, Math.min(sampleMax, sampleSize));
            int current = this.adaptiveSampleSize.get();
            int delta = sampleSize - current;
            if (Math.abs(delta) > 50) {
                sampleSize = current + (int)Math.signum(delta) * 50;
            }
            this.adaptiveSampleSize.set(sampleSize);
            return sampleSize;
        }
        catch (Exception e) {
            return this.adaptiveSampleSize.get();
        }
    }

    private int queryActiveAccountCount() {
        try {
            int apiOpt;
            if (SolidusIntegration.isSolidusLoaded() && (apiOpt = this.tryReflectionAccountCount()) > 0) {
                return apiOpt;
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("Reflection-based account count query failed, trying JDBC fallback", (Throwable)e);
        }
        try {
            int jdbcCount = this.tryJdbcAccountCount();
            if (jdbcCount > 0) {
                return jdbcCount;
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("JDBC-based account count query failed", (Throwable)e);
        }
        return -1;
    }

    private int tryReflectionAccountCount() {
        try {
            Field apiField = SolidusIntegration.class.getDeclaredField("apiInstance");
            apiField.trySetAccessible();
            Object apiInst = apiField.get(null);
            if (apiInst == null) {
                return -1;
            }
            Method getEngineMethod = apiInst.getClass().getMethod("getEconomyEngine", new Class[0]);
            Object engine = getEngineMethod.invoke(apiInst, new Object[0]);
            if (engine == null) {
                return -1;
            }
            Method getStorageMethod = engine.getClass().getMethod("getStorage", new Class[0]);
            Object storage = getStorageMethod.invoke(engine, new Object[0]);
            if (storage == null) {
                return -1;
            }
            try {
                Method countMethod = storage.getClass().getMethod("getActiveAccountCount", Integer.TYPE);
                Object result = countMethod.invoke(storage, 30);
                if (result instanceof Number) {
                    return ((Number)result).intValue();
                }
            }
            catch (NoSuchMethodException e) {
                try {
                    Method countMethod = storage.getClass().getMethod("getAccountCount", new Class[0]);
                    Object result = countMethod.invoke(storage, new Object[0]);
                    if (result instanceof Number) {
                        return ((Number)result).intValue();
                    }
                }
                catch (NoSuchMethodException e2) {
                    SolidusGovernanceMod.LOGGER.debug("No account count method available on Solidus storage class");
                }
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("Reflection account count attempt failed", (Throwable)e);
        }
        return -1;
    }

    private int tryJdbcAccountCount() {
        Path dbPath;
        MinecraftServer server = SolidusIntegration.getServer();
        if (server == null) {
            return -1;
        }
        try {
            dbPath = server.getServerDirectory().normalize().resolve("solidus").resolve("economy.db");
            if (!Files.exists(dbPath, new LinkOption[0])) {
                String levelName = server.getWorldPath(LevelResource.ROOT).getFileName().toString();
                dbPath = server.getServerDirectory().normalize().resolve(levelName).resolve("solidus").resolve("economy.db");
            }
            if (!Files.exists(dbPath, new LinkOption[0])) {
                SolidusGovernanceMod.LOGGER.debug("Solidus economy database not found at expected paths");
                return -1;
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("Failed to resolve Solidus database path", (Throwable)e);
            return -1;
        }
        String jdbcUrl = "jdbc:sqlite:" + String.valueOf(dbPath.toAbsolutePath());
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();){
            int count;
            ResultSet rs;
            long thirtyDaysAgoMs = System.currentTimeMillis() - 2592000000L;
            String sql = "SELECT COUNT(*) FROM player_balances WHERE last_updated > ?";
            try (PreparedStatement ps = conn.prepareStatement(sql);){
                ps.setLong(1, thirtyDaysAgoMs);
                rs = ps.executeQuery();
                if (rs.next() && (count = rs.getInt(1)) > 0) {
                    SolidusGovernanceMod.LOGGER.info("Simulation: DB reports {} active economy accounts (last 30 days)", (Object)count);
                    int n = count;
                    return n;
                }
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.debug("last_updated column query failed, trying total count", (Throwable)e);
            }
            try {
                rs = stmt.executeQuery("SELECT COUNT(*) FROM player_balances");
                if (!rs.next()) return -1;
                count = rs.getInt(1);
                if (count <= 0) return -1;
                SolidusGovernanceMod.LOGGER.info("Simulation: DB reports {} total economy accounts (no date filter)", (Object)count);
                int n = count;
                return n;
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.debug("Total account count query also failed", (Throwable)e);
                return -1;
            }
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.debug("JDBC connection to Solidus economy database failed", (Throwable)e);
        }
        return -1;
    }

    private List<String> generateRecommendations(double gini, double supplyGrowthRate, double maxBalance, double avgBalance) {
        ArrayList<String> recs = new ArrayList<String>();
        if (gini > 0.8) {
            recs.add("CRITICAL: Extreme wealth inequality (Gini=" + String.format("%.2f", gini) + "). Consider progressive taxation or wealth cap.");
        } else if (gini > 0.6) {
            recs.add("WARNING: High wealth inequality (Gini=" + String.format("%.2f", gini) + "). Consider wealth redistribution or tax brackets.");
        }
        if (supplyGrowthRate > 15.0) {
            recs.add("CRITICAL: Rapid money supply growth (" + String.format("%.1f%%", supplyGrowthRate) + "). Enable anti-inflation or increase tax rates.");
        } else if (supplyGrowthRate > 5.0) {
            recs.add("WARNING: Moderate inflation detected (" + String.format("%.1f%%", supplyGrowthRate) + "). Monitor and consider mild taxation adjustments.");
        }
        if (supplyGrowthRate < -10.0) {
            recs.add("WARNING: Significant deflation (" + String.format("%.1f%%", supplyGrowthRate) + "). Consider injecting currency or reducing taxes.");
        }
        if (avgBalance > 0.0 && maxBalance / avgBalance > 100.0) {
            recs.add("INFO: Top player has " + String.format("%.0f", maxBalance / avgBalance) + "x the average balance. Wealth cap recommended.");
        }
        if (recs.isEmpty()) {
            recs.add("Economy appears healthy. No immediate action required.");
        }
        return recs;
    }

    private void adjustThrottle(long tickNanos, double tps) {
        int delay = this.currentDelayMs.get();
        if (tickNanos > 5000000L) {
            delay += 30;
        } else if (tickNanos > 2000000L) {
            delay += 10;
        } else if (tps < 15.0) {
            delay += 20;
        } else if (tickNanos < 1000000L && tps >= 19.0) {
            delay -= 5;
        }
        delay = Math.max(0, Math.min(500, delay));
        this.currentDelayMs.set(delay);
    }

    private void computeAverageTickTime() {
        long sum = 0L;
        int count = 0;
        for (int i = 0; i < 20; ++i) {
            long val = this.tickHistory.get(i);
            if (val <= 0L) continue;
            sum += val;
            ++count;
        }
        if (count > 0) {
            this.avgTickNanos.set(sum / (long)count);
        }
    }

    private double estimateServerTps() {
        MinecraftServer server = SolidusIntegration.getServer();
        if (server == null) {
            return 20.0;
        }
        try {
            long[] tickTimes = server.getTickTimesNanos();
            if (tickTimes == null || tickTimes.length == 0) {
                return 20.0;
            }
            long totalNanos = 0L;
            int count = 0;
            for (long tickTime : tickTimes) {
                if (tickTime <= 0L) continue;
                totalNanos += tickTime;
                ++count;
            }
            if (count == 0) {
                return 20.0;
            }
            double avgTickMs = (double)totalNanos / (double)count / 1000000.0;
            double tps = 1000.0 / avgTickMs;
            return Math.min(tps, 20.0);
        }
        catch (Exception e) {
            return 20.0;
        }
    }

    public boolean isRunning() {
        return this.running.get();
    }

    public boolean isPaused() {
        return this.paused.get();
    }

    public SimulationInsight getLatestInsight() {
        return this.latestInsight;
    }

    public SimulationState getState() {
        long avgNanos = this.avgTickNanos.get();
        return new SimulationState(this.running.get(), this.paused.get(), this.totalTicks.get(), this.currentDelayMs.get(), avgNanos, this.lastTickNanos.get(), Double.longBitsToDouble(this.lastEstimatedTps.get()), this.determineThrottleLevel(avgNanos, this.currentDelayMs.get()), this.adaptiveSampleSize.get(), this.cachedActiveAccountCount.get());
    }

    public int getAdaptiveSampleSize() {
        return this.adaptiveSampleSize.get();
    }

    public int forceRefreshAccountCount() {
        int count = this.queryActiveAccountCount();
        if (count > 0) {
            this.cachedActiveAccountCount.set(count);
            this.lastAccountCountRefreshMs.set(System.currentTimeMillis());
            SolidusGovernanceMod.LOGGER.info("Simulation: Force-refreshed active account count from DB: {}", (Object)count);
        } else {
            SolidusGovernanceMod.LOGGER.warn("Simulation: Force-refresh failed \u2014 DB query returned no results. Keeping cached value: {}", (Object)this.cachedActiveAccountCount.get());
        }
        return count;
    }

    private String determineThrottleLevel(long avgNanos, int delayMs) {
        if (this.paused.get()) {
            return "PAUSED";
        }
        if (delayMs >= 200) {
            return "CRAWL";
        }
        if (delayMs >= 50) {
            return "SLOW";
        }
        if (delayMs >= 10) {
            return "NORMAL";
        }
        return "FAST";
    }

    public record SimulationInsight(long timestamp, double totalMoneySupply, double avgBalance, double maxBalance, String wealthiestPlayer, double giniCoefficient, double supplyGrowthRate, double inflationTrend, int sampledPlayers, List<String> recommendations) {
    }
}
