package com.solidus.governance.simulation;

import com.solidus.api.LedgerWork;
import com.solidus.api.SolidusApi;
import com.solidus.api.SolidusApiAccess;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the 2.3.2 audit closure of the LAST reflective
 * Core-internal reach-in anywhere in the companion family:
 * {@code SimulationEngine.tryReflectionAccountCount()} walked
 * {@code SolidusAPI.getEconomyEngine()} -> {@code EconomyEngine.getStorage()}
 * -> {@code SQLiteStorage.getActiveAccountCount(int)} — methods that no
 * current Core version declares, so the path silently returned -1 on every
 * query and the simulation fell back to reading economy.db directly (dead
 * the moment Core runs its MySQL network mode).
 *
 * <p>The replacement runs the same bounded aggregate through the
 * solidus-api ledger seam ({@code withLedgerConnection}), which works on
 * BOTH storage backends. This test pins that wiring: a fake contract over
 * an in-memory SQLite ledger must feed the simulation's active-account
 * count, and a failing ledger must degrade to -1 (never throw, never hang).</p>
 */
@DisplayName("Simulation active-account count rides the solidus-api ledger seam (2.3.2)")
class SimulationEngineContractAccountCountTest {

    private static Connection ledger;

    @BeforeAll
    static void installFakeContract() throws Exception {
        ledger = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = ledger.createStatement()) {
            st.execute("CREATE TABLE player_balances ("
                + "uuid TEXT PRIMARY KEY, player_name TEXT, balance REAL, last_updated INTEGER)");
            // Two players touched inside the 30-day window, one stale player
            // outside it, one brand-new account.
            long now = System.currentTimeMillis();
            st.execute("INSERT INTO player_balances VALUES ('u1','Alice',100," + (now - 60_000L) + ")");
            st.execute("INSERT INTO player_balances VALUES ('u2','Bob',200," + (now - 5L * 86_400_000L) + ")");
            st.execute("INSERT INTO player_balances VALUES ('u3','Old',300," + (now - 40L * 86_400_000L) + ")");
            st.execute("INSERT INTO player_balances VALUES ('u4','New',10," + now + ")");
        }
        // Fake contract: only withLedgerConnection matters here. Reflection in
        // TESTS is fine — the audit forbids reflection into Core internals in
        // PRODUCTION companion code, not test doubles.
        SolidusApiAccess.install(fakeApi(work -> work.run(ledger)));
    }

    @AfterAll
    static void uninstallFakeContract() throws Exception {
        SolidusApiAccess.uninstall();
        if (ledger != null) {
            ledger.close();
        }
    }

    @Test
    @DisplayName("the 30-day active count flows through the contract seam")
    void activeAccountCountThroughContract() {
        SimulationEngine engine = new SimulationEngine(null, null);
        assertEquals(3, engine.forceRefreshAccountCount(),
            "Alice, Bob and New are inside the window; Old (40 days) is not");
    }

    @Test
    @DisplayName("a failing ledger degrades to -1 (fail-quiet, no throw)")
    void failingLedgerDegrades() {
        SolidusApiAccess.uninstall();
        SolidusApiAccess.install(fakeApi(work -> {
            throw new SQLException("ledger down");
        }));
        try {
            SimulationEngine engine = new SimulationEngine(null, null);
            int count = engine.forceRefreshAccountCount();
            assertTrue(count < 0,
                "a dead ledger must surface as a negative count (no server handle in unit tests "
                    + "-> the file fallback also returns -1), never as an exception");
        } finally {
            SolidusApiAccess.uninstall();
            SolidusApiAccess.install(fakeApi(work -> work.run(ledger)));
        }
    }

    /** Minimal contract double: withLedgerConnection delegates, the rest get type-safe defaults. */
    private static SolidusApi fakeApi(LedgerConnection connection) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("withLedgerConnection".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                LedgerWork<Object> work = (LedgerWork<Object>) args[0];
                return connection.open(work);
            }
            if ("getCoreVersion".equals(method.getName())) {
                return "2.3.2-test";
            }
            if ("isEngineReady".equals(method.getName()) || "isMysqlMode".equals(method.getName())) {
                return Boolean.TRUE;
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return Boolean.FALSE;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0.0;
            return null;
        };
        return (SolidusApi) Proxy.newProxyInstance(
            SolidusApi.class.getClassLoader(), new Class<?>[]{SolidusApi.class}, handler);
    }

    @FunctionalInterface
    private interface LedgerConnection {
        Object open(LedgerWork<?> work) throws SQLException;
    }
}
