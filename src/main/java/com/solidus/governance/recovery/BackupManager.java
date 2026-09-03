package com.solidus.governance.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.solidus.governance.engine.GovernanceEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence-first backup and restore for every SQLite database in the Solidus
 * ecosystem. Backups are produced with SQLite's {@code VACUUM INTO}, which
 * yields a fully consistent, compact copy of a database even while other
 * connections are actively writing to it (WAL-safe by design).
 *
 * <p><b>Databases covered</b> (resolved relative to this mod's config dir,
 * which lives at {@code <server>/config/solidus-governance}):</p>
 * <ul>
 *   <li>Governance-owned: {@code governance.db}, {@code limits.db},
 *       {@code tax_ledger.db}, {@code events.db}, {@code rules.db}</li>
 *   <li>Core-owned (sibling {@code config/solidus}): {@code economy.db},
 *       {@code auctions.db}</li>
 *   <li>Analytics-owned (sibling {@code config/solidus-analytics}):
 *       {@code analytics.db}</li>
 * </ul>
 *
 * <p><b>Each backup run</b> creates {@code backups/backup-<timestamp>[-name]/}
 * containing one verified {@code .db} copy per existing source database, a
 * sidecar {@code <db>.json} per file (machine-readable, used by restore),
 * and a human-readable {@code manifest.json}.</p>
 *
 * <p><b>Integrity</b>: every copy is verified twice — {@code PRAGMA
 * integrity_check} must report {@code ok} on the backup file, and the SHA-256
 * recorded in the sidecar is re-verified before any restore.</p>
 *
 * <p><b>Restore semantics</b>: for governance-owned databases the live
 * connections are closed via the {@link GovernanceDbController}, the current
 * file is moved to {@code backups/quarantine/}, the verified copy is placed,
 * and the controller re-initializes the database so the restored data is live
 * without a server restart. For core- and analytics-owned databases the
 * mod cannot close their persistent connections, so the file is swapped on
 * disk and becomes active after the next server restart (POSIX semantics
 * keep the running process on the old inode until then). Writes made between
 * the swap and the restart are captured in the quarantined file and are
 * therefore never lost.</p>
 *
 * <p>This class is intentionally free of Minecraft imports so it can be
 * exercised by plain JUnit tests against real SQLite files.</p>
 */
public final class BackupManager {

    public static final Logger LOGGER = LoggerFactory.getLogger("solidus-governance");

    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern RUN_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern CREATED_AT_PATTERN = Pattern.compile("\"createdAt\"\\s*:\\s*(\\d+)");
    private static final String MANIFEST_FILE = "manifest.json";

    /** Which mod owns a database file (determines restore strategy). */
    public enum Owner { GOVERNANCE, CORE, ANALYTICS }

    /** A known database: file name + owner. Source path resolved at runtime. */
    public record DbDescriptor(String fileName, Owner owner) {}

    /** Per-file result of a backup run. */
    public record FileResult(String database, Owner owner, Path source, Path backupFile,
                             long sizeBytes, String sha256, String integrity,
                             long durationMs, String error) {
        public boolean ok() { return error == null; }
    }

    /** Aggregate result of a backup run. */
    public record BackupResult(Path runDir, List<FileResult> files, List<String> skipped,
                               List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /** Result of a restore operation (or preview when {@code confirm} is false). */
    public record RestoreResult(boolean success, boolean preview, boolean restartRequired,
                                List<String> lines) {}

    /**
     * Callbacks that close/re-open governance-owned database connections so a
     * restore can swap the underlying file while the server keeps running.
     * Implemented by the mod bootstrap, which owns the database instances.
     */
    public interface GovernanceDbController {
        void shutdownGovernanceDb(String fileName);
        void reinitializeGovernanceDb(String fileName);
    }

    /** Known databases, in backup order (owned first, then external). */
    private static final Map<String, DbDescriptor> KNOWN_DBS = buildKnownDbs();

    private static Map<String, DbDescriptor> buildKnownDbs() {
        Map<String, DbDescriptor> map = new LinkedHashMap<>();
        map.put("governance.db", new DbDescriptor("governance.db", Owner.GOVERNANCE));
        map.put("limits.db", new DbDescriptor("limits.db", Owner.GOVERNANCE));
        map.put("tax_ledger.db", new DbDescriptor("tax_ledger.db", Owner.GOVERNANCE));
        map.put("events.db", new DbDescriptor("events.db", Owner.GOVERNANCE));
        map.put("rules.db", new DbDescriptor("rules.db", Owner.GOVERNANCE));
        map.put("economy.db", new DbDescriptor("economy.db", Owner.CORE));
        map.put("auctions.db", new DbDescriptor("auctions.db", Owner.CORE));
        map.put("analytics.db", new DbDescriptor("analytics.db", Owner.ANALYTICS));
        return map;
    }

    private final Path configDir;
    private final Path backupRoot;
    private final ExecutorService executor;
    private final AtomicBoolean runGate = new AtomicBoolean(false);

    private volatile GovernanceEngine engine;
    private volatile GovernanceDbController dbController;
    /** Package-private hook for tests: forces a specific retention count. */
    volatile int retentionOverride = -1;
    private volatile boolean initialized = false;

    public BackupManager(Path configDir) {
        this.configDir = configDir;
        this.backupRoot = configDir.resolve("backups");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Backup-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ init

    public void initialize() {
        try {
            Files.createDirectories(backupRoot);
            Files.createDirectories(backupRoot.resolve("quarantine"));
            this.initialized = true;
            LOGGER.info("Backup manager initialized. Directory: {}", backupRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to create backup directories", e);
        }
    }

    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
        }
    }

    public void setEngine(GovernanceEngine engine) { this.engine = engine; }
    public void setDbController(GovernanceDbController controller) { this.dbController = controller; }

    public Path getBackupRoot() { return backupRoot; }

    /** Resolved source path for a known database, or null if unknown. */
    public Path sourcePathOf(String fileName) {
        DbDescriptor d = KNOWN_DBS.get(fileName);
        if (d == null) return null;
        return switch (d.owner()) {
            case GOVERNANCE -> configDir.resolve(fileName);
            case CORE -> configDir.getParent().resolve("solidus").resolve(fileName);
            case ANALYTICS -> configDir.getParent().resolve("solidus-analytics").resolve(fileName);
        };
    }

    // --------------------------------------------------------------- backup

    /**
     * Runs a full backup of every existing known database.
     * @param runName optional human label (sanitized) appended to the folder name
     */
    public CompletableFuture<BackupResult> createBackup(String runName) {
        final String sanitized;
        if (runName != null && !runName.isBlank()) {
            if (RUN_NAME_PATTERN.matcher(runName).matches()) {
                sanitized = "-" + runName;
            } else {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Invalid backup name: '" + runName
                                + "'. Use letters, digits, dot, hyphen, underscore (max 64 chars)."));
            }
        } else {
            sanitized = "";
        }
        if (!runGate.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A backup is already in progress."));
        }
        CompletableFuture<BackupResult> future = CompletableFuture.supplyAsync(() -> runBackup(sanitized), executor);
        return future.whenComplete((r, ex) -> runGate.set(false));
    }

    private BackupResult runBackup(String sanitizedSuffix) {
        long start = System.currentTimeMillis();
        Path runDir = backupRoot.resolve("backup-" + RUN_TS.format(LocalDateTime.now()) + sanitizedSuffix);
        List<FileResult> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(runDir);
            for (DbDescriptor d : KNOWN_DBS.values()) {
                Path source = sourcePathOf(d.fileName());
                if (source == null || !Files.exists(source)) {
                    skipped.add(d.fileName() + " (not present)");
                    continue;
                }
                files.add(backupOne(d, source, runDir));
            }
            for (FileResult f : files) {
                if (!f.ok()) errors.add(f.database() + ": " + f.error());
            }
            writeSidecarsAndManifest(runDir, files, skipped, sanitizedSuffix);
            int retention = retentionFromConfig();
            int removed = cleanupOldBackups(retention);
            long ms = System.currentTimeMillis() - start;
            LOGGER.info("Backup run completed in {} ms: {} ({}, {} ok, {} skipped, retention removed {})",
                    ms, runDir.getFileName(), errors.isEmpty() ? "ALL VERIFIED" : "WITH ERRORS",
                    files.stream().filter(FileResult::ok).count(), skipped.size(), removed);
            sendDiscordAlert(errors.isEmpty() ? "RECOVERY" : "RECOVERY",
                    errors.isEmpty() ? "Economy Backup Completed" : "Economy Backup Completed With Errors",
                    "Run: " + runDir.getFileName()
                            + " | Files: " + files.stream().filter(FileResult::ok).count() + "/" + files.size()
                            + " | Skipped: " + skipped.size()
                            + (errors.isEmpty() ? "" : " | Errors: " + String.join("; ", errors)));
            logAudit("BACKUP", "run=" + runDir.getFileName() + ";files=" + files.size()
                    + ";ok=" + files.stream().filter(FileResult::ok).count() + ";skipped=" + skipped.size()
                    + ";errors=" + errors.size() + ";durationMs=" + ms);
        } catch (Exception e) {
            errors.add("run aborted: " + e.getMessage());
            LOGGER.error("Backup run failed", e);
        }
        return new BackupResult(runDir, files, skipped, errors);
    }

    private FileResult backupOne(DbDescriptor d, Path source, Path runDir) {
        long fileStart = System.currentTimeMillis();
        Path target = runDir.resolve(d.fileName());
        try {
            // VACUUM INTO requires the target file NOT to exist.
            String targetLiteral = target.toString().replace("'", "''");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source);
                 Statement st = conn.createStatement()) {
                st.execute("VACUUM INTO '" + targetLiteral + "'");
            }
            String integrity = integrityCheck(target);
            if (!"ok".equals(integrity)) {
                return new FileResult(d.fileName(), d.owner(), source, target,
                        Files.size(target), null, integrity,
                        System.currentTimeMillis() - fileStart,
                        "integrity_check on backup returned: " + integrity);
            }
            String sha = sha256(target);
            return new FileResult(d.fileName(), d.owner(), source, target,
                    Files.size(target), sha, integrity,
                    System.currentTimeMillis() - fileStart, null);
        } catch (Exception e) {
            LOGGER.error("Backup of {} failed", d.fileName(), e);
            return new FileResult(d.fileName(), d.owner(), source, target,
                    safeSize(target), null, "unknown",
                    System.currentTimeMillis() - fileStart, e.getMessage());
        }
    }

    private void writeSidecarsAndManifest(Path runDir, List<FileResult> files,
                                          List<String> skipped, String label) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"createdAt\": ").append(System.currentTimeMillis()).append(",\n");
        sb.append("  \"label\": \"").append(label.isEmpty() ? "" : label.substring(1)).append("\",\n");
        sb.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            FileResult f = files.get(i);
            sb.append("    {\"database\": \"").append(f.database()).append("\", \"owner\": \"")
              .append(f.owner()).append("\", \"file\": \"").append(f.backupFile().getFileName())
              .append("\", \"sizeBytes\": ").append(f.sizeBytes())
              .append(", \"sha256\": \"").append(f.sha256() == null ? "" : f.sha256())
              .append("\", \"integrity\": \"").append(f.integrity())
              .append("\", \"durationMs\": ").append(f.durationMs())
              .append(", \"ok\": ").append(f.ok()).append("}");
            if (i < files.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  \"skipped\": [");
        for (int i = 0; i < skipped.size(); i++) {
            sb.append("\"").append(skipped.get(i)).append("\"");
            if (i < skipped.size() - 1) sb.append(", ");
        }
        sb.append("]\n}\n");
        Files.writeString(runDir.resolve(MANIFEST_FILE), sb.toString(), StandardCharsets.UTF_8);

        // Machine-readable sidecar per backed-up file (used by restore).
        for (FileResult f : files) {
            if (!f.ok()) continue;
            String sidecar = "{\n"
                    + "  \"database\": \"" + f.database() + "\",\n"
                    + "  \"owner\": \"" + f.owner() + "\",\n"
                    + "  \"file\": \"" + f.backupFile().getFileName() + "\",\n"
                    + "  \"sizeBytes\": " + f.sizeBytes() + ",\n"
                    + "  \"sha256\": \"" + f.sha256() + "\",\n"
                    + "  \"integrity\": \"" + f.integrity() + "\",\n"
                    + "  \"createdAt\": " + System.currentTimeMillis() + "\n"
                    + "}\n";
            Files.writeString(runDir.resolve(f.database() + ".json"), sidecar, StandardCharsets.UTF_8);
        }
    }

    private int retentionFromConfig() {
        if (this.retentionOverride > 0) return this.retentionOverride;
        GovernanceEngine eng = this.engine;
        if (eng == null) return 7;
        try {
            return Math.max(1, eng.getConfig().getInt("recovery.backup.retention", 7));
        } catch (Exception e) {
            return 7;
        }
    }

    private int cleanupOldBackups(int retention) throws IOException {
        List<Path> runs = listRunDirs();
        int removed = 0;
        for (int i = retention; i < runs.size(); i++) {
            deleteRecursively(runs.get(i));
            removed++;
        }
        return removed;
    }

    private List<Path> listRunDirs() throws IOException {
        List<Path> runs = new ArrayList<>();
        if (!Files.isDirectory(backupRoot)) return runs;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupRoot, "backup-*")) {
            for (Path p : ds) {
                // A-4: do not follow a symlinked run directory into its target.
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) runs.add(p);
            }
        }
        // Names embed yyyyMMdd-HHmmss-SSS: lexicographic order == chronological order.
        runs.sort(Comparator.reverseOrder());
        return runs;
    }

    // ------------------------------------------------------------ auto run

    /**
     * Called periodically (hourly tick) by GovernanceEngine. Runs a backup
     * when {@code recovery.backup.enabled} is on and the configured interval
     * has elapsed since the newest existing backup (measured from the
     * manifests, so restarts do not reset the schedule).
     */
    public void maybeAutoBackup() {
        if (!this.initialized) return;
        boolean enabled = true;
        long intervalHours = 24;
        GovernanceEngine eng = this.engine;
        if (eng != null) {
            try {
                enabled = eng.getConfig().getBool("recovery.backup.enabled", true);
                intervalHours = Math.max(1, eng.getConfig().getInt("recovery.backup.auto-interval-hours", 24));
            } catch (Exception e) {
                // Config unavailable: keep safe defaults (enabled, 24h).
            }
        }
        if (!enabled) return;
        long latest = latestBackupCreatedAt();
        long now = System.currentTimeMillis();
        if (latest != 0L && now - latest < intervalHours * 3600000L) return;
        createBackup(null).whenComplete((r, ex) -> {
            if (ex != null) {
                LOGGER.warn("Automatic backup skipped: {}", ex.getMessage());
            }
        });
    }

    private long latestBackupCreatedAt() {
        try {
            for (Path run : listRunDirs()) {
                Path manifest = run.resolve(MANIFEST_FILE);
                if (!Files.exists(manifest)) continue;
                Matcher m = CREATED_AT_PATTERN.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
                if (m.find()) return Long.parseLong(m.group(1));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to inspect existing backups for auto-schedule", e);
        }
        return 0L;
    }

    // --------------------------------------------------------------- restore

    /**
     * Validates and (optionally) executes a restore of one database from one
     * backup run. Without {@code confirm} this only validates and returns the
     * plan — safe to run at any time.
     */
    public CompletableFuture<RestoreResult> restore(String runName, String dbName, boolean confirm) {
        return CompletableFuture.supplyAsync(() -> restoreNow(runName, dbName, confirm), executor);
    }

    private RestoreResult restoreNow(String runName, String dbName, boolean confirm) {
        List<String> lines = new ArrayList<>();
        if (runName == null || !runName.startsWith("backup-") || !RUN_NAME_PATTERN.matcher(runName).matches()) {
            lines.add("Invalid backup run id: '" + runName + "'. Use one from 'backup list'.");
            return new RestoreResult(false, false, false, lines);
        }
        DbDescriptor descriptor = KNOWN_DBS.get(dbName);
        if (descriptor == null) {
            lines.add("Unknown database '" + dbName + "'. Known: " + String.join(", ", KNOWN_DBS.keySet()));
            return new RestoreResult(false, false, false, lines);
        }
        Path runDir = backupRoot.resolve(runName).normalize();
        if (!runDir.startsWith(backupRoot) || !Files.isDirectory(runDir)) {
            lines.add("Backup run not found: " + runName);
            return new RestoreResult(false, false, false, lines);
        }
        Path sidecar = runDir.resolve(dbName + ".json");
        Path backupFile = runDir.resolve(dbName);
        if (!Files.exists(sidecar) || !Files.exists(backupFile)) {
            lines.add("Run " + runName + " has no verified copy of " + dbName + ".");
            return new RestoreResult(false, false, false, lines);
        }
        Map<String, String> meta;
        try {
            meta = parseSidecar(Files.readString(sidecar, StandardCharsets.UTF_8));
        } catch (IOException e) {
            lines.add("Failed to read sidecar manifest: " + e.getMessage());
            return new RestoreResult(false, false, false, lines);
        }
        String recordedSha = meta.getOrDefault("sha256", "");
        if (recordedSha.isEmpty()) {
            lines.add("Sidecar manifest has no SHA-256; refusing to restore from unverified copy.");
            return new RestoreResult(false, false, false, lines);
        }
        String actualSha;
        String integrity;
        try {
            actualSha = sha256(backupFile);
            integrity = integrityCheck(backupFile);
        } catch (Exception e) {
            lines.add("Failed to verify backup copy: " + e.getMessage());
            return new RestoreResult(false, false, false, lines);
        }
        if (!recordedSha.equals(actualSha)) {
            lines.add("SHA-256 mismatch for " + dbName + " (expected " + recordedSha + ", got " + actualSha + ").");
            lines.add("The backup copy is corrupted or tampered with. Restore refused.");
            return new RestoreResult(false, false, false, lines);
        }
        if (!"ok".equals(integrity)) {
            lines.add("integrity_check on the backup copy returned: " + integrity + ". Restore refused.");
            return new RestoreResult(false, false, false, lines);
        }
        Path target = sourcePathOf(dbName);
        if (target == null) {
            lines.add("Cannot resolve target path for " + dbName + ".");
            return new RestoreResult(false, false, false, lines);
        }
        lines.add("Verified: " + dbName + " @ " + runName
                + " (sha256 " + actualSha.substring(0, 12) + "..., integrity ok)");
        lines.add("Target:   " + target);
        if (!confirm) {
            lines.add("This is a PREVIEW. Re-run with 'confirm' to execute the restore.");
            return new RestoreResult(true, true, descriptor.owner() != Owner.GOVERNANCE, lines);
        }
        boolean restartRequired = descriptor.owner() != Owner.GOVERNANCE;
        try {
            GovernanceDbController controller = this.dbController;
            if (descriptor.owner() == Owner.GOVERNANCE && controller != null) {
                controller.shutdownGovernanceDb(dbName);
            }
            Path quarantine = backupRoot.resolve("quarantine");
            Files.createDirectories(quarantine);
            Path quarantined = quarantine.resolve(dbName + "." + RUN_TS.format(LocalDateTime.now()));
            if (Files.exists(target)) {
                moveAtomically(target, quarantined);
                lines.add("Quarantined previous file: " + quarantined.getFileName());
            }
            // A-2 fix (audit round 3): SQLite WAL databases keep recent writes in
            // <db>-wal / <db>-shm sidecar files. Quarantining ONLY the main file
            // left a stale hot WAL next to the freshly restored copy; on the next
            // open, SQLite REPLAYED that WAL onto the restored point-in-time file -
            // silently resurrecting exactly the post-backup transactions the
            // restore was meant to remove (or corrupting the file). The sidecars
            // are quarantined together with the main file so the restored copy
            // starts from a clean, checkpointed state.
            for (String suffix : new String[]{"-wal", "-shm"}) {
                Path walSidecar = target.resolveSibling(dbName + suffix);
                if (Files.exists(walSidecar)) {
                    Path sidecarQuarantine = quarantine.resolve(dbName + suffix + "." + RUN_TS.format(LocalDateTime.now()));
                    try {
                        moveAtomically(walSidecar, sidecarQuarantine);
                        lines.add("Quarantined stale WAL sidecar: " + sidecarQuarantine.getFileName());
                    } catch (Exception sidecarEx) {
                        // A sidecar that cannot be moved must NOT survive next to a
                        // restored file - deleting it is safe (WAL is transient by
                        // design) and better than letting it replay onto the restore.
                        try {
                            Files.deleteIfExists(walSidecar);
                            lines.add("Deleted stale WAL sidecar: " + dbName + suffix);
                        } catch (Exception delEx) {
                            lines.add("WARNING: could not quarantine or delete stale sidecar " + dbName + suffix
                                + " (" + delEx.getMessage() + ") - the restored file may replay stale WAL writes.");
                        }
                    }
                }
            }
            Files.copy(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
            if (descriptor.owner() == Owner.GOVERNANCE && controller != null) {
                controller.reinitializeGovernanceDb(dbName);
                lines.add("Governance database re-initialized on the restored copy.");
            } else {
                lines.add("NOTE: " + descriptor.owner() + " owns this database; the restored file");
                lines.add("becomes active after the next server restart. Writes made until");
                lines.add("then continue into the quarantined file (nothing is lost).");
            }
        } catch (Exception e) {
            LOGGER.error("Restore of {} from {} failed", dbName, runName, e);
            lines.add("Restore FAILED: " + e.getMessage());
            lines.add("The quarantined original (if any) is still in backups/quarantine/.");
            return new RestoreResult(false, false, restartRequired, lines);
        }
        sendDiscordAlert("RECOVERY", "Database Restored",
                "DB: " + dbName + " | From: " + runName + (restartRequired ? " | Restart required" : " | Live"));
        logAudit("RESTORE", "db=" + dbName + ";run=" + runName
                + ";owner=" + descriptor.owner() + ";restartRequired=" + restartRequired);
        lines.add("Restore complete for " + dbName + ".");
        return new RestoreResult(true, false, restartRequired, lines);
    }

    private static Map<String, String> parseSidecar(String json) {
        Map<String, String> out = new TreeMap<>();
        for (String key : new String[]{"database", "owner", "file", "sha256", "integrity"}) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            if (m.find()) out.put(key, m.group(1));
        }
        Matcher m = Pattern.compile("\"sizeBytes\"\\s*:\\s*(\\d+)").matcher(json);
        if (m.find()) out.put("sizeBytes", m.group(1));
        return out;
    }

    // ------------------------------------------------------------ inspection

    /** Human-readable summary of the most recent backup runs. */
    public List<String> listBackups(int limit) {
        List<String> lines = new ArrayList<>();
        try {
            List<Path> runs = listRunDirs();
            if (runs.isEmpty()) {
                lines.add("No backups yet. Create one with '/governance recovery backup create'.");
                return lines;
            }
            int count = Math.min(Math.max(1, limit), 50);
            for (Path run : runs.subList(0, Math.min(count, runs.size()))) {
                String created = describeRunAge(run);
                long total = 0;
                int files = 0;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(run, "*.db")) {
                    for (Path p : ds) {
                        files++;
                        total += safeSize(p);
                    }
                }
                lines.add(String.format("  %s  %s  files=%d  %s",
                        run.getFileName(), created, files, formatBytes(total)));
            }
            if (runs.size() > count) {
                lines.add("  ... and " + (runs.size() - count) + " older runs.");
            }
        } catch (Exception e) {
            lines.add("Failed to list backups: " + e.getMessage());
        }
        return lines;
    }

    /** Configuration + inventory summary for 'backup status'. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        GovernanceEngine eng = this.engine;
        boolean enabled = false;
        int interval = 24;
        int retention = 7;
        if (eng != null) {
            try {
                enabled = eng.getConfig().getBool("recovery.backup.enabled", true);
                interval = eng.getConfig().getInt("recovery.backup.auto-interval-hours", 24);
                retention = eng.getConfig().getInt("recovery.backup.retention", 7);
            } catch (Exception ignored) { }
        }
        lines.add("Enabled: " + enabled + "  |  Interval: every " + interval + "h  |  Retention: last " + retention + " runs");
        lines.add("Directory: " + backupRoot);
        try {
            long latest = latestBackupCreatedAt();
            lines.add("Latest run: " + (latest == 0L ? "none yet" : formatTs(latest)));
        } catch (Exception ignored) { }
        lines.add("Monitored databases:");
        for (DbDescriptor d : KNOWN_DBS.values()) {
            Path src = sourcePathOf(d.fileName());
            boolean exists = src != null && Files.exists(src);
            lines.add(String.format("  %-14s %-10s %s", d.fileName(), d.owner(),
                    exists ? ("present (" + formatBytes(safeSize(src)) + ")") : "absent"));
        }
        return lines;
    }

    // ---------------------------------------------------------------- helpers

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /** Runs PRAGMA integrity_check and returns the first result row. */
    static String integrityCheck(Path dbFile) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            return rs.next() ? rs.getString(1) : "no-result";
        } catch (SQLException e) {
            return "exception: " + e.getMessage();
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed: " + e.getMessage(), e);
        }
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        // A-4 fix (audit round 3): directory tests and the stream below used to
        // FOLLOW symlinks, so a local attacker could point backups/backup-x at
        // any directory and the retention sweep would delete the TARGET tree's
        // contents. NOFOLLOW keeps the walk inside the real backup tree; a
        // symlinked run directory is deleted as a link, never walked into.
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) deleteRecursively(p);
                else Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
    }

    private String describeRunAge(Path run) {
        Matcher m = CREATED_AT_PATTERN.matcher("");
        try {
            Path manifest = run.resolve(MANIFEST_FILE);
            if (Files.exists(manifest)) {
                m = CREATED_AT_PATTERN.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
                if (m.find()) return formatTs(Long.parseLong(m.group(1)));
            }
        } catch (Exception ignored) { }
        return "(no manifest)";
    }

    private static String formatTs(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void sendDiscordAlert(String category, String title, String description) {
        GovernanceEngine eng = this.engine;
        if (eng != null && eng.getWebhookManager() != null) {
            eng.getWebhookManager().sendAlert(category, title, description);
        }
    }

    private void logAudit(String action, String details) {
        GovernanceEngine eng = this.engine;
        if (eng != null && eng.getAuditLogger() != null) {
            eng.getAuditLogger().logBackup(action, details);
        }
    }
}
