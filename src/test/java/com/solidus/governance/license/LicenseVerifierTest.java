package com.solidus.governance.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SA2 (Ed25519) license scheme. These lock in the R01 fix:
 * verification uses an asymmetric signature with a public key - no client
 * held secret can ever be used to mint valid keys again.
 */
@DisplayName("LicenseVerifier (SA2 / Ed25519)")
class LicenseVerifierTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void setUpKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @AfterAll
    static void tearDownKeys() {
        System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
    }

    private static String issueKey(String licensee, LocalDate expiry, String fingerprint) throws Exception {
        String payload = "2|" + licensee + "|" + expiry.format(DateTimeFormatter.ISO_LOCAL_DATE) + "|" + fingerprint;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return "SA2-"
            + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "-"
            + Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test
    @DisplayName("a correctly signed SA2 key verifies (universal license)")
    void validKeyVerifies() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey(issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertTrue(verifier.isPremiumEnabled());
        assertEquals("Acme Server", verifier.getLicenseeName());
        assertNotNull(verifier.getExpiryDate());
    }

    @Test
    @DisplayName("an expired key is detected")
    void expiredKeyDetected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey(issueKey("Acme Server", LocalDate.now().minusDays(1), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.EXPIRED, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedSignatureRejected() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY");
        String[] parts = key.split("-");
        byte[] sig = Base64.getDecoder().decode(parts[2]);
        sig[0] ^= 0x01; // flip one bit in the signature
        String forged = parts[0] + "-" + parts[1] + "-" + Base64.getEncoder().encodeToString(sig);

        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey(forged);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered payload is rejected")
    void tamperedPayloadRejected() throws Exception {
        String key = issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY");
        String[] parts = key.split("-");
        String payload = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String upgraded = payload.replace("Acme Server", "Acme Server X"); // alter signed content
        String forged = parts[0] + "-"
            + Base64.getEncoder().encodeToString(upgraded.getBytes(StandardCharsets.UTF_8))
            + "-" + parts[2];

        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey(forged);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
    }

    @Test
    @DisplayName("legacy SA1 keys are rejected outright")
    void sa1KeysRejected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey("SA1-eyJhIjoxfQ-someSignature");

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
        assertTrue(verifier.getErrorMessage().contains("no longer accepted"),
            "error should explain SA1 rejection, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("a server-bound key fails on a different server fingerprint")
    void fingerprintMismatchDetected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey(issueKey("Acme Server", LocalDate.now().plusDays(30), "0123456789ABCDEF"));

        assertEquals(LicenseVerifier.VerificationState.FINGERPRINT_MISMATCH, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("missing public key fails closed (premium disabled, no crash)")
    void missingPublicKeyFailsClosed() throws Exception {
        try {
            System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
            LicenseVerifier verifier = new LicenseVerifier();
            verifier.verifyKey(issueKey("Acme Server", LocalDate.now().plusDays(30), "ANY"));

            assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
            assertFalse(verifier.isPremiumEnabled());
            assertTrue(verifier.getErrorMessage().contains("SOLIDUS_LICENSE_PUBLIC_KEY"),
                "error should name the missing public key, got: " + verifier.getErrorMessage());
        } finally {
            System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        }
    }

    @Test
    @DisplayName("an empty or blank key is rejected")
    void emptyKeyRejected() {
        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verifyKey("   ");

        assertNotEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("verification works through the license.key file path")
    void fileBasedVerification() throws Exception {
        Path keyFile = Files.createTempFile("solidus-gov-license", ".key");
        Files.writeString(keyFile, issueKey("FileTest Server", LocalDate.now().plusDays(10), "ANY"));

        LicenseVerifier verifier = new LicenseVerifier();
        verifier.verify(keyFile);

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        Files.deleteIfExists(keyFile);
    }
}
