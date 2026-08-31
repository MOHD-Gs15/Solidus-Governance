package com.solidus.governance.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the economy.db search order used by SimulationEngine's JDBC
 * account-count fallback.
 *
 * <p>Regression guard: the fallback used to look only in
 * {@code <server dir>/solidus/} and {@code <level>/solidus/} - layouts that
 * no current Core version produces - so the JDBC path silently never found
 * the database. Core 2.x stores economy.db in
 * {@code <server dir>/config/solidus/}, which must be the first candidate.</p>
 */
class SimulationEngineDbPathTest {

    @TempDir
    Path serverDir;

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    @Test
    void prefersCurrentConfigSolidusLayout() throws Exception {
        Path expected = serverDir.resolve("config").resolve("solidus").resolve("economy.db");
        touch(expected);
        assertEquals(expected, SimulationEngine.resolveEconomyDbPath(serverDir, "world"));
    }

    @Test
    void fallsBackToLegacyServerRootLayout() throws Exception {
        Path legacy = serverDir.resolve("solidus").resolve("economy.db");
        touch(legacy);
        assertEquals(legacy, SimulationEngine.resolveEconomyDbPath(serverDir, "world"));
    }

    @Test
    void fallsBackToLegacyLevelLayout() throws Exception {
        Path legacy = serverDir.resolve("world").resolve("solidus").resolve("economy.db");
        touch(legacy);
        assertEquals(legacy, SimulationEngine.resolveEconomyDbPath(serverDir, "world"));
    }

    @Test
    void configLayoutWinsOverLegacyLayouts() throws Exception {
        Path config = serverDir.resolve("config").resolve("solidus").resolve("economy.db");
        touch(config);
        touch(serverDir.resolve("solidus").resolve("economy.db"));
        touch(serverDir.resolve("world").resolve("solidus").resolve("economy.db"));
        assertEquals(config, SimulationEngine.resolveEconomyDbPath(serverDir, "world"));
    }

    @Test
    void returnsNullWhenNoCandidateExists() {
        assertNull(SimulationEngine.resolveEconomyDbPath(serverDir, "world"));
    }

    @Test
    void handlesMissingLevelName() {
        assertNull(SimulationEngine.resolveEconomyDbPath(serverDir, null));
        assertNull(SimulationEngine.resolveEconomyDbPath(serverDir, ""));
    }
}
