package com.solidus.governance.rules;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RuleEngineDurationTest {

    @Test
    void parsesDurations() {
        assertEquals(90000L, RuleEngine.parseDuration("90s"));
        assertEquals(300000L, RuleEngine.parseDuration("5m"));
        assertEquals(7200000L, RuleEngine.parseDuration("2h"));
        assertEquals(86400000L, RuleEngine.parseDuration("1d"));
        assertEquals(500L, RuleEngine.parseDuration("500"));
    }

    @Test
    void clampsInvalidAndNegativeDurationsToZero() {
        assertEquals(0L, RuleEngine.parseDuration(null));
        assertEquals(0L, RuleEngine.parseDuration(""));
        assertEquals(0L, RuleEngine.parseDuration("abc"));
        assertEquals(0L, RuleEngine.parseDuration("-5m"));
        assertEquals(0L, RuleEngine.parseDuration("-100"));
    }

    @Test
    void formatsDurations() {
        assertEquals("none", RuleEngine.formatDuration(0L));
        assertEquals("1m 30s", RuleEngine.formatDuration(90000L));
        assertEquals("2h 0m", RuleEngine.formatDuration(7200000L));
        assertEquals("1d 0h", RuleEngine.formatDuration(86400000L));
    }

    @Test
    void validatesRuleNames() {
        assertTrue(RuleEngine.isValidRuleName("rule-a"));
        assertTrue(RuleEngine.isValidRuleName("good-rule-2"));
        assertFalse(RuleEngine.isValidRuleName("Rule"));
        assertFalse(RuleEngine.isValidRuleName("a"));
        assertFalse(RuleEngine.isValidRuleName("-bad"));
        assertFalse(RuleEngine.isValidRuleName(null));
        assertFalse(RuleEngine.isValidRuleName("this-rule-name-is-far-too-long-for-validation"));
    }
}
