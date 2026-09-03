package com.solidus.governance.limits;

import com.solidus.governance.GovernanceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B-5 regression test (audit round 3): the limit-set command historically
 * stored auction-daily as a DOUBLE ("10.0"), and GovernanceConfig.getInt's
 * Integer.parseInt threw on it, silently returning the -1 default - i.e.
 * every value set through the command DISABLED the daily auction limit
 * while the admin read "Set auction-daily to 10.00". The reader now
 * tolerates the legacy double form; the writer stores whole numbers.
 */
@DisplayName("TransactionLimits auction daily-max parsing (B-5)")
class TransactionLimitsParseTest {

    private GovernanceConfig configWith(String value, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("governance.properties");
        Files.writeString(file, "limits.auction.daily-max=" + value + "\n");
        GovernanceConfig config = new GovernanceConfig(dir);
        config.load();
        return config;
    }

    @Test
    @DisplayName("plain integer values parse directly")
    void plainInteger(@TempDir Path dir) throws Exception {
        assertEquals(10, TransactionLimits.readAuctionDailyMax(configWith("10", dir)));
        assertEquals(-1, TransactionLimits.readAuctionDailyMax(configWith("-1", dir)));
        assertEquals(0, TransactionLimits.readAuctionDailyMax(configWith("0", dir)));
    }

    @Test
    @DisplayName("legacy double form ('10.0' written by old builds) parses as 10, not unlimited")
    void legacyDoubleForm(@TempDir Path dir) throws Exception {
        assertEquals(10, TransactionLimits.readAuctionDailyMax(configWith("10.0", dir)),
            "the legacy double form must not silently disable the limit");
        assertEquals(25, TransactionLimits.readAuctionDailyMax(configWith("25.0", dir)));
    }

    @Test
    @DisplayName("garbage falls back to -1 (unlimited) instead of throwing")
    void garbageFallsBack(@TempDir Path dir) throws Exception {
        assertEquals(-1, TransactionLimits.readAuctionDailyMax(configWith("banana", dir)));
        assertEquals(-1, TransactionLimits.readAuctionDailyMax(configWith("", dir)));
        assertEquals(-1, TransactionLimits.readAuctionDailyMax(configWith("NaN", dir)));
        assertEquals(-1, TransactionLimits.readAuctionDailyMax(configWith("Infinity", dir)));
    }
}
