package com.solidus.governance.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventValidationTest {

    @Test
    void parsesDurationsWithUnitsAndComposites() {
        assertEquals(60000L, EventManager.parseDuration("1m"));
        assertEquals(7200000L, EventManager.parseDuration("2h"));
        assertEquals(86400000L, EventManager.parseDuration("1d"));
        assertEquals(172800000L, EventManager.parseDuration("48h"));
        assertEquals(5400000L, EventManager.parseDuration("1h30m"));
    }

    @Test
    void rejectsInvalidDurations() {
        assertEquals(-1L, EventManager.parseDuration(null));
        assertEquals(-1L, EventManager.parseDuration(""));
        assertEquals(-1L, EventManager.parseDuration("abc"));
        assertEquals(-1L, EventManager.parseDuration("0m"));
        assertEquals(-1L, EventManager.parseDuration("m30"));
        assertEquals(-1L, EventManager.parseDuration("5x"));
    }

    @Test
    void normalizesEventTypes() {
        assertEquals("DOUBLE_SHOP", EventManager.normalizeEventType("double-shop"));
        assertEquals("TAX_HOLIDAY", EventManager.normalizeEventType("tax_holiday"));
        assertEquals("BONUS_CURRENCY", EventManager.normalizeEventType("BONUS_CURRENCY"));
        assertNull(EventManager.normalizeEventType("not-a-type"));
        assertNull(EventManager.normalizeEventType(null));
    }

    @Test
    void validatesModifierRange() {
        assertTrue(EventManager.isValidModifier(0.5));
        assertTrue(EventManager.isValidModifier(1.0));
        assertTrue(EventManager.isValidModifier(2.0));
        assertTrue(EventManager.isValidModifier(100.0));
        assertFalse(EventManager.isValidModifier(0.0));
        assertFalse(EventManager.isValidModifier(-2.0));
        assertFalse(EventManager.isValidModifier(100.5));
        assertFalse(EventManager.isValidModifier(Double.NaN));
        assertFalse(EventManager.isValidModifier(Double.POSITIVE_INFINITY));
    }
}
