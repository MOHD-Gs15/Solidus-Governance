package com.solidus.governance.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the SA2 (Ed25519) license scheme, round 3 contract:
 *
 * <ul>
 *   <li>The trust anchor is the EMBEDDED vendor key - the legacy
 *       SOLIDUS_LICENSE_PUBLIC_KEY env var / solidus.license.publicKey system
 *       property are IGNORED (setting them to an attacker key must not change
 *       the verification result).</li>
 *   <li>Format is SA2.&lt;payload&gt;.&lt;signature&gt; (base64url, 6-field payload)
 *       - legacy dash-format SA2- keys are rejected.</li>
 *   <li>The payload's product field must equal "governance-premium" exactly:
 *       a vendor-signed key for another Solidus product must NOT activate
 *       Governance premium.</li>
 *   <li>A missing/placeholder embedded key fails CLOSED.</li>
 * </ul>
 */
@DisplayName("LicenseVerifier (SA2. / Ed25519 / embedded key / product-bound)")
class LicenseVerifierTest {

    private static KeyPair keyPair;
    private static KeyPair attackerKeyPair;

    @BeforeAll
    static void setUpKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        attackerKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // Test-only injection point (never set by production code).
        LicenseVerifier.testPublicKeyB64 =
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @AfterAll
    static void tearDownKeys() {
        LicenseVerifier.testPublicKeyB64 = null;
        System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signer.sign());
    }

    private static String payloadB64(String payload) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** Issues a well-formed key for the given product (default: governance). */
    private static String issueKey(String licensee, String expiry, String fingerprint, String product) throws Exception {
        String nonce = "0123456789ABCDEF";
        String payload = "2|" + licensee + "|" + expiry + "|" + fingerprint + "|" + product + "|" + nonce;
        return "SA2." + payloadB64(payload) + "." + sign(keyPair, payload);
    }

    private static String issueGovernanceKey(String licensee, String expiry, String fingerprint) throws Exception {
        return issueKey(licensee, expiry, fingerprint, "governance-premium");
    }

    private static String futureDate() {
        return LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Test
    @DisplayName("a correctly signed SA2. key verifies (universal license)")
    void validKeyVerifies() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(issueGovernanceKey("Acme Server", futureDate(), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertTrue(verifier.isPremiumEnabled());
        assertEquals("Acme Server", verifier.getLicenseeName());
        assertNotNull(verifier.getExpiryDate());
        assertFalse(verifier.isPerpetual());
        assertTrue(verifier.getDaysRemaining() > 0);
    }

    @Test
    @DisplayName("a perpetual key verifies with no expiry date")
    void perpetualKeyVerifies() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(issueGovernanceKey("Lifetime Buyer", "PERPETUAL", "ANY"));

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertTrue(verifier.isPremiumEnabled());
        assertTrue(verifier.isPerpetual());
        assertNull(verifier.getExpiryDate());
        assertEquals(Long.MAX_VALUE, verifier.getDaysRemaining());
    }

    @Test
    @DisplayName("an expired key is detected")
    void expiredKeyDetected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(issueGovernanceKey("Acme Server",
            LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), "ANY"));

        assertEquals(LicenseVerifier.VerificationState.EXPIRED, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a key for a DIFFERENT product is rejected (product binding)")
    void crossProductKeyRejected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        // Vendor-signed and perfectly valid - but issued for analytics.
        verifier.verifyKey(issueKey("Acme Server", futureDate(), "ANY", "analytics-premium"));

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
        assertTrue(verifier.getErrorMessage().contains("analytics-premium"),
            "error should name the foreign product, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedSignatureRejected() throws Exception {
        String key = issueGovernanceKey("Acme Server", futureDate(), "ANY");
        String[] parts = key.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        sig[0] ^= 0x01; // flip one bit in the signature
        String forged = parts[0] + "." + parts[1] + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);

        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(forged);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("a tampered payload (signed content altered) is rejected")
    void tamperedPayloadRejected() throws Exception {
        String key = issueGovernanceKey("Acme Server", futureDate(), "ANY");
        String[] parts = key.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String upgraded = payload.replace("Acme Server", "Acme Server X");
        String forged = parts[0] + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(upgraded.getBytes(StandardCharsets.UTF_8))
            + "." + parts[2];

        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(forged);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
    }

    @Test
    @DisplayName("a key signed by an ATTACKER's key is rejected (embedded trust anchor)")
    void attackerSignedKeyRejected() throws Exception {
        String nonce = "0123456789ABCDEF";
        String payload = "2|Attacker|" + futureDate() + "|ANY|governance-premium|" + nonce;
        String forged = "SA2." + payloadB64(payload) + "." + sign(attackerKeyPair, payload);

        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(forged);

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("legacy SA1 keys are rejected outright")
    void sa1KeysRejected() {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey("SA1-eyJhIjoxfQ-someSignature");

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
        assertTrue(verifier.getErrorMessage().contains("no longer accepted"),
            "error should explain SA1 rejection, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("legacy SA2- dash-format keys are rejected (they were self-forgeable)")
    void legacyDashFormatRejected() {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey("SA2-c29saWR1cw-signaturevalue");

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
        assertTrue(verifier.getErrorMessage().contains("dash-format keys are retired"),
            "error should explain the dash-format retirement, got: " + verifier.getErrorMessage());
    }

    @Test
    @DisplayName("a server-bound key fails on a different server fingerprint")
    void fingerprintMismatchDetected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(issueGovernanceKey("Acme Server", futureDate(), "0123456789ABCDEF"));

        assertEquals(LicenseVerifier.VerificationState.FINGERPRINT_MISMATCH, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("an invalid fingerprint field is rejected (must be 16 hex or ANY)")
    void malformedFingerprintRejected() throws Exception {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey(issueGovernanceKey("Acme Server", futureDate(), "not-a-fingerprint"));

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
    }

    @Test
    @DisplayName("missing embedded key fails closed (premium disabled, no crash)")
    void missingPublicKeyFailsClosed() throws Exception {
        String savedKey = LicenseVerifier.testPublicKeyB64;
        try {
            LicenseVerifier.testPublicKeyB64 = null; // embedded placeholder takes over
            LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
            verifier.verifyKey(issueGovernanceKey("Acme Server", futureDate(), "ANY"));

            assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.getState());
            assertFalse(verifier.isPremiumEnabled());
            assertTrue(verifier.getErrorMessage().contains("No vendor public key is embedded"),
                "error should name the missing embedded key, got: " + verifier.getErrorMessage());
        } finally {
            LicenseVerifier.testPublicKeyB64 = savedKey;
        }
    }

    @Test
    @DisplayName("the legacy publicKey system property is IGNORED as a trust anchor")
    void legacyPropertyIgnored() throws Exception {
        try {
            // Point the legacy override at the ATTACKER's public key. The
            // embedded (test-injected) key must remain the only trust anchor,
            // so a legitimately vendor-signed key still verifies.
            System.setProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(attackerKeyPair.getPublic().getEncoded()));
            LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
            verifier.verifyKey(issueGovernanceKey("Acme Server", futureDate(), "ANY"));

            assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
            assertTrue(verifier.isPremiumEnabled());
        } finally {
            System.clearProperty(LicenseVerifier.PUBLIC_KEY_PROPERTY);
        }
    }

    @Test
    @DisplayName("an empty or blank key is rejected")
    void emptyKeyRejected() {
        LicenseVerifier verifier = new LicenseVerifier(Path.of("/nonexistent-license-test"));
        verifier.verifyKey("   ");

        assertNotEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.getState());
        assertFalse(verifier.isPremiumEnabled());
    }

    @Test
    @DisplayName("verification works through the license.key file path")
    void fileBasedVerification(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("license.key");
        Files.writeString(keyFile, "# comment line first\n"
            + issueGovernanceKey("FileTest Server", futureDate(), "ANY") + "\n");

        LicenseVerifier verifier = new LicenseVerifier(dir);
        LicenseVerifier.VerificationState state = verifier.initialize();

        assertEquals(LicenseVerifier.VerificationState.VERIFIED, state);
        assertEquals("FileTest Server", verifier.getLicenseeName());
    }

    @Test
    @DisplayName("initialize() fails cleanly when no license.key exists")
    void missingFileFailsClean(@TempDir Path dir) {
        LicenseVerifier verifier = new LicenseVerifier(dir);
        LicenseVerifier.VerificationState state = verifier.initialize();

        assertEquals(LicenseVerifier.VerificationState.INVALID, state);
        assertFalse(verifier.isPremiumEnabled());
        assertNotNull(verifier.getErrorMessage());
    }

    @Test
    @DisplayName("forceReverify() picks up a replaced key file without a restart")
    void reverifyPicksUpReplacement(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("license.key");
        Files.writeString(keyFile, issueGovernanceKey("First Owner", futureDate(), "ANY"));
        LicenseVerifier verifier = new LicenseVerifier(dir);
        assertEquals(LicenseVerifier.VerificationState.VERIFIED, verifier.initialize());

        // Swap in a key signed by the attacker: must flip to INVALID.
        String nonce = "0123456789ABCDEF";
        String payload = "2|Attacker|" + futureDate() + "|ANY|governance-premium|" + nonce;
        Files.writeString(keyFile, "SA2." + payloadB64(payload) + "." + sign(attackerKeyPair, payload));

        assertEquals(LicenseVerifier.VerificationState.INVALID, verifier.forceReverify());
        assertFalse(verifier.isPremiumEnabled());
    }
}
