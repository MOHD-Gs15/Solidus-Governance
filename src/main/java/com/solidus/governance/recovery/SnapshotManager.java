package com.solidus.governance.recovery;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.engine.GovernanceEngine;
import com.solidus.governance.integration.SolidusIntegration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;

public class SnapshotManager {
    private final Path snapshotDir;
    private GovernanceEngine engine;
    private volatile MinecraftServer server;

    public SnapshotManager(Path configDir, GovernanceEngine engine) {
        this.snapshotDir = configDir.resolve("snapshots");
        this.engine = engine;
    }

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void initialize() {
        try {
            Files.createDirectories(this.snapshotDir, new FileAttribute[0]);
            SolidusGovernanceMod.LOGGER.info("Snapshot Manager initialized. Directory: {}", (Object)this.snapshotDir);
        }
        catch (IOException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to create snapshot directory", (Throwable)e);
        }
    }

    public CompletableFuture<Path> createSnapshot(String name) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = name != null ? "snapshot-" + name + ".json" : "snapshot-" + timestamp + ".json";
        Path snapshotPath = this.snapshotDir.resolve(filename);
        return ((CompletableFuture)SolidusIntegration.getTopBalances(100000).thenApplyAsync(balances -> {
            try {
                StringBuilder json = new StringBuilder();
                json.append("{\n");
                json.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
                json.append("  \"created_at\": ").append(System.currentTimeMillis()).append(",\n");
                json.append("  \"balances\": [\n");
                for (int i = 0; i < balances.size(); ++i) {
                    SolidusIntegration.BalanceEntry entry = (SolidusIntegration.BalanceEntry)balances.get(i);
                    json.append("    {\"rank\": ").append(i + 1).append(", \"name\": \"").append(SnapshotManager.escapeJson(entry.playerName())).append("\"").append(", \"balance\": ").append(entry.balance()).append("}");
                    if (i < balances.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
                json.append("  ]\n");
                json.append("}\n");
                Files.writeString(snapshotPath, (CharSequence)json.toString(), new OpenOption[0]);
                int playerCount = balances.size();
                if (this.engine != null && this.server != null) {
                    this.server.execute(() -> {
                        this.engine.getAuditLogger().logSnapshot(null, "System", filename, playerCount);
                        this.cleanupOldSnapshots();
                    });
                }
                SolidusGovernanceMod.LOGGER.info("Economy snapshot created: {} ({} players)", (Object)filename, (Object)playerCount);
                this.sendDiscordAlert("RECOVERY", "Economy Snapshot Created", "Snapshot: " + filename + " (" + playerCount + " players)");
                return snapshotPath;
            }
            catch (Exception e) {
                SolidusGovernanceMod.LOGGER.error("Failed to create economy snapshot", (Throwable)e);
                return null;
            }
        })).exceptionally(ex -> {
            SolidusGovernanceMod.LOGGER.error("Failed to create economy snapshot", ex);
            return null;
        });
    }

    public void autoSnapshot() {
        this.createSnapshot(null);
    }

    public List<String> listSnapshots() {
        try (Stream<Path> files = Files.list(this.snapshotDir)) {
            List<String> list = files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p, new LinkOption[0]).toMillis();
                }
                catch (IOException e) {
                    return 0L;
                }
            }).reversed()).map(p -> p.getFileName().toString()).toList();
            return list;
        }
        catch (IOException e) {
            return List.of();
        }
    }

    public Path getSnapshotPath(String name) {
        Path p = this.snapshotDir.resolve(name);
        return Files.exists(p, new LinkOption[0]) ? p : null;
    }

    private void cleanupOldSnapshots() {
        int retention = this.engine != null ? this.engine.getConfig().getInt("recovery.snapshot.retention", 28) : 28;
        try (Stream<Path> files = Files.list(this.snapshotDir);){
            List<Path> snapshots = files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).sorted(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p, new LinkOption[0]).toMillis();
                }
                catch (IOException e) {
                    return 0L;
                }
            }).reversed()).toList();
            if (snapshots.size() > retention) {
                for (int i = retention; i < snapshots.size(); ++i) {
                    Files.delete(snapshots.get(i));
                    SolidusGovernanceMod.LOGGER.debug("Deleted old snapshot: {}", (Object)snapshots.get(i).getFileName());
                }
            }
        }
        catch (IOException e) {
            SolidusGovernanceMod.LOGGER.error("Failed to cleanup old snapshots", (Throwable)e);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void sendDiscordAlert(String category, String title, String description) {
        if (this.engine != null && this.engine.getWebhookManager() != null) {
            this.engine.getWebhookManager().sendAlert(category, title, description);
        }
    }
}
