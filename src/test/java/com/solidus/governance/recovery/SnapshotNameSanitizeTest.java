package com.solidus.governance.recovery;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SnapshotNameSanitizeTest {

    @Test
    void acceptsPlainNames() {
        assertEquals("before-reset", SnapshotManager.sanitizeSnapshotName("before-reset"));
        assertEquals("Snap_2024.01.01", SnapshotManager.sanitizeSnapshotName("Snap_2024.01.01"));
        assertEquals("a", SnapshotManager.sanitizeSnapshotName("a"));
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        assertNull(SnapshotManager.sanitizeSnapshotName("../evil"));
        assertNull(SnapshotManager.sanitizeSnapshotName("..\\evil"));
        assertNull(SnapshotManager.sanitizeSnapshotName("a/b"));
        assertNull(SnapshotManager.sanitizeSnapshotName(".."));
        assertNull(SnapshotManager.sanitizeSnapshotName("."));
    }

    @Test
    void rejectsEmptyNullAndSpecialChars() {
        assertNull(SnapshotManager.sanitizeSnapshotName(null));
        assertNull(SnapshotManager.sanitizeSnapshotName(""));
        assertNull(SnapshotManager.sanitizeSnapshotName("has space"));
        assertNull(SnapshotManager.sanitizeSnapshotName("-startswithdash"));
        assertNull(SnapshotManager.sanitizeSnapshotName("quote\"name"));
    }

    @Test
    void rejectsNamesBeyond64Chars() {
        String longName = "a".repeat(65);
        assertNull(SnapshotManager.sanitizeSnapshotName(longName));
        assertEquals("a".repeat(64), SnapshotManager.sanitizeSnapshotName("a".repeat(64)));
    }
}
