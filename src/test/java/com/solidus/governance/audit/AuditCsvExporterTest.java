package com.solidus.governance.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the /governance audit export csv backend:
 * - AuditDatabase.getAuditLogsSince window reads (real SQLite, newest first)
 * - Row cap keeps the newest entries
 * - AuditCsvExporter RFC 4180 building: header, 13 columns, escaping, nulls
 * - writeCsvFile round-trip (UTF-8, content matches buildCsv)
 */
@DisplayName("Audit CSV export (windowed reads + RFC 4180)")
class AuditCsvExporterTest {

    private AuditDatabase db;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        tempDir = dir;
        db = new AuditDatabase(dir);
        db.initialize();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    /** Seeds one audit row and waits for the async writer to finish. */
    private void seed(long timestamp, String action, String category, String details) throws Exception {
        db.logAudit(AuditDatabase.AuditEntry.create(
            timestamp, "admin-uuid", "Admin", action, category,
            null, "TargetPlayer", "10.0", "20.0", details, 0));
        // logAudit is async on the single-threaded executor; a completed
        // barrier task guarantees the insert has been committed.
        db.getExecutor().submit(() -> {}).get();
    }

    @Test
    @DisplayName("getAuditLogsSince returns only rows inside the window, newest first")
    void windowedRead() throws Exception {
        seed(1_000L, "intervention_add", "INTERVENTION", "old");
        seed(3_000L, "tax_set", "TAXATION", "new");
        seed(2_000L, "account_freeze", "RECOVERY", "middle");

        List<AuditDatabase.AuditEntry> entries = db.getAuditLogsSince(2_000L);
        assertEquals(2, entries.size());
        assertEquals("tax_set", entries.get(0).action);
        assertEquals("account_freeze", entries.get(1).action);
    }

    @Test
    @DisplayName("row cap keeps the NEWEST entries")
    void rowCapKeepsNewest() throws Exception {
        for (int i = 0; i < 5; i++) {
            seed(1_000L + i, "action_" + i, "INTERVENTION", "d" + i);
        }
        List<AuditDatabase.AuditEntry> entries = db.getAuditLogsSince(0L, 3);
        assertEquals(3, entries.size());
        assertEquals("action_4", entries.get(0).action);
        assertEquals("action_2", entries.get(2).action);
    }

    @Test
    @DisplayName("empty window returns an empty list (never an error)")
    void emptyWindow() {
        assertTrue(db.getAuditLogsSince(System.currentTimeMillis() + 1_000L).isEmpty());
    }

    @Test
    @DisplayName("buildCsv emits header + one row per entry with all 13 columns")
    void csvHeaderAndRow() {
        AuditDatabase.AuditEntry entry = new AuditDatabase.AuditEntry(
            7, 1_700_000_000_000L, "admin-uuid", "Admin",
            "intervention_add", "INTERVENTION", "target-uuid", "TargetPlayer",
            "10.0", "20.0", "payout", 0);

        String csv = AuditCsvExporter.buildCsv(List.of(entry));
        String[] lines = csv.split("\n", -1);
        assertEquals(3, lines.length);
        assertEquals("timestamp_ms,timestamp_utc,id,action,category,admin_uuid,admin_name,"
            + "target_uuid,target_name,before_value,after_value,details,rollback_of",
            lines[0]);

        String[] cols = lines[1].split(",", -1);
        assertEquals(13, cols.length);
        assertEquals("1700000000000", cols[0]);
        assertEquals("2023-11-14T22:13:20Z", cols[1]);
        assertEquals("7", cols[2]);
        assertEquals("intervention_add", cols[3]);
        assertEquals("INTERVENTION", cols[4]);
        assertEquals("admin-uuid", cols[5]);
        assertEquals("Admin", cols[6]);
        assertEquals("target-uuid", cols[7]);
        assertEquals("TargetPlayer", cols[8]);
        assertEquals("10.0", cols[9]);
        assertEquals("20.0", cols[10]);
        assertEquals("payout", cols[11]);
        assertEquals("0", cols[12]);
    }

    @Test
    @DisplayName("buildCsv escapes commas, quotes and line breaks (RFC 4180)")
    void csvEscaping() {
        AuditDatabase.AuditEntry entry = new AuditDatabase.AuditEntry(
            1, 1L, "a,b", "Admin \"A\"", "action", "CATEGORY",
            null, "Target, Jr", null, null, "line1\nline2", 0);

        String csv = AuditCsvExporter.buildCsv(List.of(entry));
        String[] lines = csv.split("\n", -1);
        assertEquals(4, lines.length, "newline inside a field must not split rows: " + csv);
        assertTrue(csv.contains("\"a,b\""), "comma field quoted");
        assertTrue(csv.contains("\"Admin \"\"A\"\"\""), "quotes doubled");
        assertTrue(csv.contains("\"Target, Jr\""), "target quoted");
        assertTrue(lines[2].endsWith("line2\",0"), "multi-line field quoted across lines (rollback_of follows), got: <" + lines[2] + ">");
    }

    @Test
    @DisplayName("csvEscape: plain unchanged, null empty, specials quoted")
    void csvEscapeBasics() {
        assertEquals("simple", AuditCsvExporter.csvEscape("simple"));
        assertEquals("", AuditCsvExporter.csvEscape(null));
        assertEquals("", AuditCsvExporter.csvEscape(""));
        assertEquals("\"a,b\"", AuditCsvExporter.csvEscape("a,b"));
        assertEquals("\"say \"\"x\"\"\"", AuditCsvExporter.csvEscape("say \"x\""));
        assertEquals("\"l1\nl2\"", AuditCsvExporter.csvEscape("l1\nl2"));
        assertEquals("\"cr\rhere\"", AuditCsvExporter.csvEscape("cr\rhere"));
    }

    @Test
    @DisplayName("writeCsvFile writes UTF-8 content matching buildCsv under exports dir")
    void writeCsvFileRoundTrip() throws Exception {
        AuditDatabase.AuditEntry entry = new AuditDatabase.AuditEntry(
            3, 1_700_000_000_000L, "admin-uuid", "Admin", "tax_set", "TAXATION",
            null, null, "0.05", "0.10", "rate change", 0);

        Path file = tempDir.resolve("exports").resolve("test_export.csv");
        AuditCsvExporter.writeCsvFile(List.of(entry), file);

        assertTrue(Files.exists(file.getParent()), "exports dir created");
        assertEquals(AuditCsvExporter.buildCsv(List.of(entry)), Files.readString(file));
    }

    @Test
    @DisplayName("getExportsDir lives under the config dir")
    void exportsDirLocation() {
        assertEquals(tempDir.resolve("exports"), db.getExportsDir());
    }
}
