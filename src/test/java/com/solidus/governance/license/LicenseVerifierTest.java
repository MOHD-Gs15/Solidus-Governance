package com.solidus.governance.license;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LicenseVerifierTest {
    @Test
    void premiumRemainsDisabledWithoutValidExternalSecret() throws Exception {
        Path key = Files.createTempFile("solidus-license", ".key");
        Files.writeString(key, "SA1-invalid-payload-signature");

        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verify(key);

        assertFalse(verifier.isPremiumEnabled());
        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
    }
}
