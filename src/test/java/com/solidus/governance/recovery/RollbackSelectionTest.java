package com.solidus.governance.recovery;

import static org.junit.jupiter.api.Assertions.*;

import com.solidus.governance.audit.AuditDatabase.AuditEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RollbackSelectionTest {

    private static AuditEntry entry(int id, long timestamp, String category, String targetUuid, String targetName, String before, String after) {
        return new AuditEntry(id, timestamp, "admin-uuid", "Admin", "ADD_BALANCE", category, targetUuid, targetName, before, after, "change=1.0", 0);
    }

    @Test
    void selectsEarliestAffectedEntryInWindow() {
        String uuid = "00000000-0000-0000-0000-000000000001";
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(entry(3, 3000L, "INTERVENTION", uuid, "Alice", "300.0", "400.0"));
        entries.add(entry(1, 1000L, "INTERVENTION", uuid, "Alice", "100.0", "200.0"));
        entries.add(entry(2, 2000L, "INTERVENTION", uuid, "Alice", "200.0", "300.0"));
        AuditEntry selected = RollbackEngine.selectRestoreEntry(entries, 0L, null);
        assertNotNull(selected);
        assertEquals(1, selected.id);
        assertEquals("100.0", selected.beforeValue);
    }

    @Test
    void respectsWindowBounds() {
        String uuid = "00000000-0000-0000-0000-000000000001";
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(entry(1, 1000L, "INTERVENTION", uuid, "Alice", "100.0", "200.0"));
        entries.add(entry(2, 2000L, "INTERVENTION", uuid, "Alice", "200.0", "300.0"));
        entries.add(entry(3, 3000L, "INTERVENTION", uuid, "Alice", "300.0", "400.0"));
        AuditEntry selected = RollbackEngine.selectRestoreEntry(entries, 2000L, 3000L);
        assertNotNull(selected);
        assertEquals(2, selected.id);
    }

    @Test
    void skipsRecoveryAndUnparseableEntries() {
        String uuid = "00000000-0000-0000-0000-000000000001";
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(entry(1, 1000L, "INTERVENTION", uuid, "Alice", "FROZEN", "UNFROZEN"));
        entries.add(entry(2, 2000L, "RECOVERY", uuid, "Alice", "500.0", "600.0"));
        entries.add(entry(3, 3000L, "INTERVENTION", uuid, "Alice", "300.0", "400.0"));
        AuditEntry selected = RollbackEngine.selectRestoreEntry(entries, 0L, null);
        assertNotNull(selected);
        assertEquals(3, selected.id);
    }

    @Test
    void skipsInvalidTargetUuidAndNullFields() {
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(entry(1, 1000L, "INTERVENTION", "not-a-uuid", "Alice", "100.0", "200.0"));
        entries.add(entry(2, 2000L, "INTERVENTION", null, "Alice", "100.0", "200.0"));
        entries.add(entry(3, 3000L, "INTERVENTION", "00000000-0000-0000-0000-000000000002", "Alice", null, "200.0"));
        assertNull(RollbackEngine.selectRestoreEntry(entries, 0L, null));
        assertNull(RollbackEngine.selectRestoreEntry(null, 0L, null));
    }

    @Test
    void returnsNullWhenNothingMatchesWindow() {
        String uuid = "00000000-0000-0000-0000-000000000001";
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(entry(1, 1000L, "INTERVENTION", uuid, "Alice", "100.0", "200.0"));
        assertNull(RollbackEngine.selectRestoreEntry(entries, 2000L, null));
    }
}
