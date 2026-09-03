package com.solidus.governance.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * RFC 4180-style CSV serialization for {@link AuditDatabase.AuditEntry}.
 *
 * <p>Mirrors the CSV contract used by Core's {@code /transactions export}:
 * fields containing commas, quotes, CR, or LF are quoted with doubled inner
 * quotes; null fields export empty; amounts-free (audit values are free-form
 * strings). Timestamps export twice - sortable epoch millis and a
 * human-readable ISO-8601 UTC column.</p>
 *
 * <p>Kept separate from {@link AuditDatabase} so the database class stays
 * storage-only and the escaping contract is directly unit-testable without
 * touching SQLite.</p>
 */
public final class AuditCsvExporter {

    /** Exported column order - append-only contract for downstream tools. */
    private static final String CSV_HEADER =
        "timestamp_ms,timestamp_utc,id,action,category,admin_uuid,admin_name,"
        + "target_uuid,target_name,before_value,after_value,details,rollback_of";

    private static final DateTimeFormatter CSV_UTC_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private AuditCsvExporter() {} // static utility

    /**
     * Builds a CSV document (header always present) from audit entries,
     * preserving the caller's order.
     */
    public static String buildCsv(List<AuditDatabase.AuditEntry> entries) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (AuditDatabase.AuditEntry e : entries) {
            sb.append(e.timestamp).append(',')
                .append(CSV_UTC_FORMAT.format(Instant.ofEpochMilli(e.timestamp))).append(',')
                .append(e.id).append(',')
                .append(csvEscape(e.action)).append(',')
                .append(csvEscape(e.category)).append(',')
                .append(csvEscape(e.adminUuid)).append(',')
                .append(csvEscape(e.adminName)).append(',')
                .append(csvEscape(e.targetUuid)).append(',')
                .append(csvEscape(e.targetName)).append(',')
                .append(csvEscape(e.beforeValue)).append(',')
                .append(csvEscape(e.afterValue)).append(',')
                .append(csvEscape(e.details)).append(',')
                .append(e.rollbackOf).append('\n');
        }
        return sb.toString();
    }

    /**
     * Writes {@link #buildCsv(List)} to a file as UTF-8, creating parent
     * directories as needed.
     *
     * @throws IOException if directories cannot be created or the file
     *                     cannot be written
     */
    public static void writeCsvFile(List<AuditDatabase.AuditEntry> entries, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, buildCsv(entries), StandardCharsets.UTF_8);
    }

    /**
     * RFC 4180 field escaping: quotes a field when it contains a comma,
     * quote, CR, or LF, and doubles any inner quotes. Null becomes empty.
     *
     * <p>A-5 fix (audit round 3, mirrors Core 2.1.3): a field that BEGINS with
     * a spreadsheet formula character ({@code = + - @}) or a control char would
     * execute as a formula when an admin opens the export in Excel/LibreOffice
     * - rule names, policy values, config strings and offline-mode player names
     * are all attacker-influenced. Formula-prefixed fields are neutralized with
     * a leading apostrophe (the spreadsheet treats the cell as text).</p>
     */
    static String csvEscape(String field) {
        if (field == null) return "";
        boolean needsQuoting = field.indexOf(',') >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
        boolean formulaPrefix = !field.isEmpty() && isFormulaPrefix(field.charAt(0));
        if (!needsQuoting && !formulaPrefix) return field;
        String escaped = formulaPrefix ? "'" + field : field;
        if (!needsQuoting) return escaped;
        return '"' + escaped.replace("\"", "\"\"") + '"';
    }

    /** Characters that start a spreadsheet formula when placed at cell start. */
    private static boolean isFormulaPrefix(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }
}
