package com.solidus.governance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GovernanceConfigTest {
    @Test
    void normalizesUnsafeRatesIntervalsAndWebhooks() throws Exception {
        Path dir = Files.createTempDirectory("solidus-governance-config");
        Path config = dir.resolve("governance.properties");
        Files.writeString(config, String.join(System.lineSeparator(),
            "taxation.transfer.rate=NaN",
            "taxation.auction.rate=2.0",
            "taxation.wealth-decay.rate=-1.0",
            "audit.retention-days=-20",
            "recovery.snapshot.retention=0",
            "simulation.sample-percentage=3.0",
            "discord.enabled=true",
            "discord.webhook.default=http://example.invalid/webhook"));

        GovernanceConfig governanceConfig = new GovernanceConfig(dir);
        governanceConfig.load();

        assertEquals(0.0, governanceConfig.getDouble("taxation.transfer.rate", -1.0));
        assertEquals(0.05, governanceConfig.getDouble("taxation.auction.rate", -1.0));
        assertEquals(0.001, governanceConfig.getDouble("taxation.wealth-decay.rate", -1.0));
        assertEquals(1, governanceConfig.getInt("audit.retention-days", -1));
        assertEquals(1, governanceConfig.getInt("recovery.snapshot.retention", -1));
        assertEquals(0.15, governanceConfig.getDouble("simulation.sample-percentage", -1.0));
        assertFalse(governanceConfig.getBool("discord.enabled", true));
        assertEquals("", governanceConfig.getString("discord.webhook.default", "missing"));
    }
}
