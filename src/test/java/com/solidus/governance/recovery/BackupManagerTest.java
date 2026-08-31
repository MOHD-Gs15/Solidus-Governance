package com.solidus.governance.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BackupManager}: verified VACUUM INTO backups, manifest +
 * sidecar consistency, corruption refusal, restore execution with a fake
 * governance DB controller, retention cleanup, and input validation.
 *
 * Drives real SQLite databases laid out exactly like production:
 * {@code config/solidus-governance}, {@code config/solidus},
 * {@code config/solidus-analytics}.
 */
@DisplayName("Backup & restore workflow")
class BackupManagerTest {

    @TempDir
    Path tempDir;

    private Path configDir;
    private Path coreDir;
    private BackupManager backupManager;
    private final List<String> shutdownCalls = new CopyOnWriteArrayList<>();
    private final List<String> reinitCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        configDir = tempDir.resolve("config").resolve("solidus-governance");
        coreDir = tempDir.resolve("config").resolve("solidus");
        Files.createDirectories(configDir);
        Files.createDirectories(coreDir);
        Files.createDirectories(tempDir.resolve("config").resolve("solidus-analytics"));

        // governance.db with two audit rows
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + configDir.resolve("governance.db"));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY, note TEXT)");
            st.execute("INSERT INTO audit_log(note) VALUES ('row-a'), ('row-b')");
        }
        // limits.db present but empty-ish
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + configDir.resolve("limits.db"));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE markers (k TEXT PRIMARY KEY)");
        }
        // economy.db owned by core (sibling dir)
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + coreDir.resolve("economy.db"));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, balance REAL NOT NULL)");
            st.execute("INSERT INTO player_balances VALUES ('uuid-1', 100.0), ('uuid-2', 250.5)");
        }
        // analytics.db intentionally absent -> must be skipped, not failed

        backupManager = new BackupManager(configDir);
        backupManager.initialize();
        backupManager.setDbController(new BackupManager.GovernanceDbController() {
            @Override
            public void shutdownGovernanceDb(String fileName) { shutdownCalls.add(fileName); }

            @Override
            public void reinitializeGovernanceDb(String fileName) { reinitCalls.add(fileName); }
        });
    }

    private BackupManager.BackupResult runBackup(String label) throws Exception {
        return backupManager.createBackup(label).get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("backup creates verified copies with manifest and sidecars")
    void backupCreatesVerifiedCopies() throws Exception {
        BackupManager.BackupResult result = runBackup("unittest");

        assertTrue(result.ok(), "expected no errors, got: " + result.errors());
        assertTrue(Files.isDirectory(result.runDir()));
        assertTrue(Files.exists(result.runDir().resolve("manifest.json")), "manifest.json missing");
        assertTrue(Files.exists(result.runDir().resolve("governance.db.json")), "sidecar missing");

        BackupManager.FileResult gov = fileResult(result, "governance.db");
        assertNotNull(gov);
        assertTrue(gov.ok());
        assertEquals("ok", gov.integrity());
        assertEquals(BackupManager.sha256(gov.backupFile()), gov.sha256());
        assertEquals(BackupManager.Owner.GOVERNANCE, gov.owner());

        // VACUUM INTO copy must be a queryable, complete database.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + gov.backupFile());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }

        // Core-owned database copied from the sibling directory.
        BackupManager.FileResult economy = fileResult(result, "economy.db");
        assertNotNull(economy);
        assertTrue(economy.ok());
        assertEquals(BackupManager.Owner.CORE, economy.owner());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + economy.backupFile());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT balance FROM player_balances WHERE uuid='uuid-2'")) {
            assertTrue(rs.next());
            assertEquals(250.5, rs.getDouble(1), 1e-9);
        }

        // Absent analytics.db skipped with reason, not failed.
        assertTrue(result.skipped().stream().anyMatch(s -> s.startsWith("analytics.db")),
                "analytics.db should be skipped as absent");
    }

    @Test
    @DisplayName("invalid backup name is rejected")
    void invalidNameRejected() {
        CompletableFuture<BackupManager.BackupResult> future = backupManager.createBackup("../evil");
        assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("restore refuses corrupted backup copy (sha mismatch)")
    void restoreRefusesCorruptedCopy() throws Exception {
        BackupManager.BackupResult result = runBackup("corrupt");
        assertTrue(result.ok());
        BackupManager.FileResult limits = fileResult(result, "limits.db");
        assertNotNull(limits);

        // Flip bytes in the backup copy -> sha mismatch vs sidecar.
        Path backupFile = limits.backupFile();
        byte[] bytes = Files.readAllBytes(backupFile);
        bytes[20] ^= 0xFF;
        bytes[21] ^= 0xFF;
        Files.write(backupFile, bytes);

        BackupManager.RestoreResult restore =
                backupManager.restore(result.runDir().getFileName().toString(), "limits.db", false)
                        .get(30, TimeUnit.SECONDS);
        assertFalse(restore.success());
        assertTrue(restore.lines().stream().anyMatch(l -> l.contains("SHA-256 mismatch")),
                "expected sha mismatch refusal, got: " + restore.lines());
    }

    @Test
    @DisplayName("restore preview validates then confirmed restore swaps file and restarts governance DB")
    void restorePreviewThenConfirm() throws Exception {
        BackupManager.BackupResult result = runBackup("pre-rest");
        assertTrue(result.ok());
        String runName = result.runDir().getFileName().toString();

        // Preview: no mutation, no controller calls.
        BackupManager.RestoreResult preview =
                backupManager.restore(runName, "governance.db", false).get(30, TimeUnit.SECONDS);
        assertTrue(preview.success());
        assertTrue(preview.preview());
        assertFalse(preview.restartRequired());
        assertTrue(shutdownCalls.isEmpty());
        assertTrue(reinitCalls.isEmpty());
        assertEquals(2, countSourceAuditRows(), "preview must not touch the live file");

        // Mutate the live DB after the backup so restored content differs.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + configDir.resolve("governance.db"));
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit_log(note) VALUES ('row-c'), ('row-d'), ('row-e')");
        }
        assertEquals(5, countSourceAuditRows());

        BackupManager.RestoreResult confirmed =
                backupManager.restore(runName, "governance.db", true).get(30, TimeUnit.SECONDS);
        assertTrue(confirmed.success(), "restore failed: " + confirmed.lines());
        assertFalse(confirmed.restartRequired());
        assertTrue(shutdownCalls.contains("governance.db"), "controller shutdown not called");
        assertTrue(reinitCalls.contains("governance.db"), "controller reinit not called");
        assertEquals(2, countSourceAuditRows(), "restored file should contain the backup's 2 rows");
        assertTrue(Files.exists(backupManager.getBackupRoot().resolve("quarantine"))
                && listQuarantine().stream().anyMatch(p -> p.getFileName().toString().startsWith("governance.db.")),
                "previous file must be quarantined");
    }

    @Test
    @DisplayName("restore validates run id and database name")
    void restoreValidation() throws Exception {
        BackupManager.BackupResult result = runBackup("valid");
        assertTrue(result.ok());
        String runName = result.runDir().getFileName().toString();

        BackupManager.RestoreResult badRun =
                backupManager.restore("../escape", "economy.db", false).get(30, TimeUnit.SECONDS);
        assertFalse(badRun.success());

        BackupManager.RestoreResult badDb =
                backupManager.restore(runName, "nope.db", false).get(30, TimeUnit.SECONDS);
        assertFalse(badDb.success());
        assertTrue(badDb.lines().stream().anyMatch(l -> l.contains("Unknown database")));

        BackupManager.RestoreResult missingRun =
                backupManager.restore("backup-does-not-exist", "economy.db", false).get(30, TimeUnit.SECONDS);
        assertFalse(missingRun.success());
    }

    @Test
    @DisplayName("retention deletes old runs and never touches quarantine")
    void retentionCleanup() throws Exception {
        backupManager.retentionOverride = 2;
        runBackup("r1");
        runBackup("r2");
        runBackup("r3");
        BackupManager.BackupResult fourth = runBackup("r4");
        assertTrue(fourth.ok());

        List<Path> runs = listRunDirs();
        assertEquals(2, runs.size(), "retention must keep only the newest 2 runs");
        // Newest two survive (r3, r4 labels are inside their names).
        assertTrue(runs.stream().anyMatch(p -> p.getFileName().toString().endsWith("r3")));
        assertTrue(runs.stream().anyMatch(p -> p.getFileName().toString().endsWith("r4")));
    }

    @Test
    @DisplayName("auto backup trigger respects interval and disabled flag")
    void autoBackupSchedule() throws Exception {
        // No engine -> defaults to enabled with 24h interval; first call with no
        // backups must schedule a run.
        backupManager.maybeAutoBackup();
        // Wait briefly for the async run.
        for (int i = 0; i < 50 && listRunDirs().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertEquals(1, listRunDirs().size(), "first auto trigger should create exactly one run");
        // A second immediate call must NOT create another run (interval far in the future).
        backupManager.maybeAutoBackup();
        Thread.sleep(500);
        assertEquals(1, listRunDirs().size(), "second immediate trigger must be suppressed by interval");
    }

    // ------------------------------------------------------------ helpers

    private BackupManager.FileResult fileResult(BackupManager.BackupResult result, String db) {
        return result.files().stream().filter(f -> f.database().equals(db)).findFirst().orElse(null);
    }

    private int countSourceAuditRows() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + configDir.resolve("governance.db"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private List<Path> listRunDirs() throws Exception {
        return BackupManagerTest.listDirsMatching(backupManager.getBackupRoot(), "backup-*");
    }

    private List<Path> listQuarantine() throws Exception {
        return BackupManagerTest.listDirsMatching(backupManager.getBackupRoot().resolve("quarantine"), "*");
    }

    private static List<Path> listDirsMatching(Path dir, String glob) throws Exception {
        List<Path> out = new CopyOnWriteArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) out.add(p);
        }
        return out;
    }
}
