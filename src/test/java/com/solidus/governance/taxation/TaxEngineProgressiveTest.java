package com.solidus.governance.taxation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the progressive tax brackets in {@link TaxEngine}.
 *
 * Covers the math that the CoreHookBridge now actually applies (previously
 * the brackets were configurable but no code path used them):
 * - Floor-bracket selection by balance (exact threshold, between thresholds,
 *   below all thresholds, empty registry)
 * - Post-settlement reconstruction: calculateProgressiveTransferTax must
 *   select the bracket using balanceAfter + amount, not balanceAfter alone
 * - 2-decimal tax rounding
 * - Registry invariants (unmodifiable view, remove, clear)
 */
@DisplayName("TaxEngine progressive brackets")
class TaxEngineProgressiveTest {

    private TaxEngine engine;

    @BeforeEach
    void setUp() {
        // GovernanceEngine is only needed for rate lookups and tax collection;
        // bracket math never touches it, so null is safe here.
        engine = new TaxEngine(null);
    }

    @AfterEach
    void tearDown() {
        engine.clearBrackets();
    }

    @Nested
    @DisplayName("calculateProgressiveTax (floor selection)")
    class FloorSelectionTest {

        @Test
        @DisplayName("empty bracket registry yields zero")
        void emptyRegistryYieldsZero() {
            assertEquals(0.0, engine.calculateProgressiveTax(5_000_000.0, 100.0));
        }

        @Test
        @DisplayName("balance below every threshold yields zero")
        void belowAllThresholdsYieldsZero() {
            engine.addBracket(1_000_000.0, 0.10);
            assertEquals(0.0, engine.calculateProgressiveTax(999_999.99, 500.0));
        }

        @Test
        @DisplayName("exact threshold selects that bracket")
        void exactThresholdSelectsBracket() {
            engine.addBracket(1_000_000.0, 0.10);
            assertEquals(100.0, engine.calculateProgressiveTax(1_000_000.0, 1_000.0));
        }

        @Test
        @DisplayName("balance between thresholds selects the floor bracket")
        void betweenThresholdsSelectsFloor() {
            engine.addBracket(100_000.0, 0.02);
            engine.addBracket(1_000_000.0, 0.10);
            engine.addBracket(10_000_000.0, 0.20);
            // 2,500,000 is >= 1,000,000 but < 10,000,000 -> 10%
            assertEquals(250.0, engine.calculateProgressiveTax(2_500_000.0, 2_500.0));
        }

        @Test
        @DisplayName("tax is rounded to two decimals")
        void taxRoundedToTwoDecimals() {
            engine.addBracket(0.0, 0.10);
            // 0.10 * 33.33 = 3.333 -> 3.33
            assertEquals(3.33, engine.calculateProgressiveTax(5.0, 33.33));
        }
    }

    @Nested
    @DisplayName("calculateProgressiveTransferTax (post-settlement reconstruction)")
    class TransferReconstructionTest {

        @Test
        @DisplayName("reconstructs pre-transfer balance for bracket selection")
        void usesReconstructedBalanceBefore() {
            engine.addBracket(1_000_000.0, 0.10);
            // Sender observed at 900,000 AFTER the transfer; the 200,000
            // transfer put them at 1,100,000 at transaction time -> 10% of
            // 200,000 = 20,000. Selecting on balanceAfter alone would
            // wrongly yield 0.
            assertEquals(20_000.0, engine.calculateProgressiveTransferTax(900_000.0, 200_000.0));
        }

        @Test
        @DisplayName("balance genuinely below the bracket stays untaxed")
        void belowBracketStaysUntaxed() {
            engine.addBracket(1_000_000.0, 0.10);
            // Even after adding the amount back, 500,000 + 100 < 1,000,000.
            assertEquals(0.0, engine.calculateProgressiveTransferTax(500_000.0, 100.0));
        }

        @Test
        @DisplayName("no brackets configured -> zero, never an exception")
        void noBracketsYieldsZero() {
            assertEquals(0.0, engine.calculateProgressiveTransferTax(900_000.0, 200.0));
        }

        @Test
        @DisplayName("unavailable or invalid inputs yield zero")
        void invalidInputsYieldZero() {
            engine.addBracket(0.0, 0.10);
            // -1.0 is the "balance unavailable" sentinel from SolidusIntegration
            assertEquals(0.0, engine.calculateProgressiveTransferTax(-1.0, 200.0));
            assertEquals(0.0, engine.calculateProgressiveTransferTax(Double.NaN, 200.0));
            assertEquals(0.0, engine.calculateProgressiveTransferTax(Double.POSITIVE_INFINITY, 200.0));
            assertEquals(0.0, engine.calculateProgressiveTransferTax(1_000.0, 0.0));
            assertEquals(0.0, engine.calculateProgressiveTransferTax(1_000.0, -50.0));
        }
    }

    @Nested
    @DisplayName("bracket registry invariants")
    class RegistryTest {

        @Test
        @DisplayName("getBrackets view is unmodifiable")
        void viewIsUnmodifiable() {
            engine.addBracket(100.0, 0.05);
            Map<Double, Double> view = engine.getBrackets();
            assertThrows(UnsupportedOperationException.class, () -> view.put(1.0, 0.5));
        }

        @Test
        @DisplayName("removeBracket and clearBrackets update the registry")
        void removeAndClearWork() {
            engine.addBracket(100.0, 0.05);
            engine.addBracket(200.0, 0.10);
            engine.removeBracket(100.0);
            assertEquals(1, engine.getBrackets().size());
            assertEquals(0.10, engine.getBrackets().get(200.0));
            engine.clearBrackets();
            assertTrue(engine.getBrackets().isEmpty());
        }

        @Test
        @DisplayName("re-adding an existing threshold replaces its rate")
        void sameThresholdReplacesRate() {
            engine.addBracket(100.0, 0.05);
            engine.addBracket(100.0, 0.08);
            assertEquals(1, engine.getBrackets().size());
            assertEquals(0.08, engine.getBrackets().get(100.0));
        }
    }
}
