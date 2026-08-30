package com.solidus.governance.license;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import net.fabricmc.loader.api.FabricLoader;

/**
 * LicenseVerifier - offline Ed25519 license verification (key format SA2).
 *
 * <p><b>Why SA2 exists:</b> SA1 keys were signed with HMAC-SHA256 using a
 * secret that had to be present on every customer server via the
 * {@code SOLIDUS_LICENSE_SECRET} environment variable. Because that secret
 * shipped to the very party being verified, any buyer could mint unlimited
 * valid keys offline. SA2 replaces the symmetric scheme with an asymmetric
 * Ed25519 signature: the private key never leaves the issuer's machine and
 * customers only receive the public verification key, which cannot sign.</p>
 *
 * <p><b>Key format:</b> {@code SA2-<base64(payload)>-<base64(Ed25519 signature)>}<br>
 * <b>Payload:</b> {@code 2|<licensee>|<expiry ISO-8601>|<fingerprint or ANY>}<br>
 * <b>Public key:</b> base64(X.509 SubjectPublicKeyInfo) provided via the
 * {@code SOLIDUS_LICENSE_PUBLIC_KEY} environment variable (or the
 * {@code solidus.license.publicKey} system property, mainly for tests).<br>
 * <b>Issuer tool:</b> {@code tools/LicenseIssuer.java} in the repository.</p>
 *
 * <p>SA1 keys are rejected outright: they were forgeable by design and there
 * is no safe way to honor them.</p>
 */
public class LicenseVerifier {
    public static final String KEY_PREFIX = "SA2";
    public static final int PAYLOAD_VERSION = 2;
    public static final String PUBLIC_KEY_ENV = "SOLIDUS_LICENSE_PUBLIC_KEY";
    public static final String PUBLIC_KEY_PROPERTY = "solidus.license.publicKey";

    private volatile VerificationState state = VerificationState.UNVERIFIED;
    private volatile String licenseeName;
    private volatile LocalDate expiryDate;
    private volatile String fingerprint;
    private volatile String errorMessage;

    public void verify(Path licenseKeyPath) {
        if (!Files.exists(licenseKeyPath, new LinkOption[0])) {
            this.state = VerificationState.UNVERIFIED;
            this.errorMessage = "License key file not found";
            return;
        }
        try {
            String key = Files.readString(licenseKeyPath).trim();
            this.verifyKey(key);
        } catch (Exception e) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "Failed to read license key: " + e.getMessage();
        }
    }

    public void verifyKey(String key) {
        try {
            if (key == null || key.isBlank()) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Empty license key";
                return;
            }
            if (!key.startsWith(KEY_PREFIX + "-")) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid license key format. Expected " + KEY_PREFIX + "-... "
                    + "(SA1 keys are no longer accepted - they were forgeable by design; request a new SA2 key)";
                return;
            }
            String body = key.substring(KEY_PREFIX.length() + 1);
            int lastDash = body.lastIndexOf('-');
            if (lastDash < 0) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid license key structure (missing signature separator)";
                return;
            }
            String payloadBase64 = body.substring(0, lastDash);
            String signatureBase64 = body.substring(lastDash + 1);
            String payload;
            byte[] signatureBytes;
            try {
                payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid key encoding (payload not valid Base64)";
                return;
            }
            try {
                signatureBytes = Base64.getDecoder().decode(signatureBase64);
            } catch (IllegalArgumentException e) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid key encoding (signature not valid Base64)";
                return;
            }
            if (!this.verifySignature(payload, signatureBytes)) {
                if (this.errorMessage == null) {
                    this.errorMessage = "Invalid license signature";
                }
                this.state = VerificationState.INVALID;
                return;
            }
            String[] fields = payload.split("\\|");
            if (fields.length != 4) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid payload structure (expected 4 fields, got " + fields.length + ")";
                return;
            }
            String version = fields[0];
            if (!String.valueOf(PAYLOAD_VERSION).equals(version)) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Unsupported license version: " + version;
                return;
            }
            this.licenseeName = fields[1];
            try {
                this.expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid expiry date in license";
                return;
            }
            if (this.expiryDate.isBefore(LocalDate.now())) {
                this.state = VerificationState.EXPIRED;
                this.errorMessage = "License expired on " + this.expiryDate;
                return;
            }
            this.fingerprint = fields[3];
            if (!"ANY".equals(this.fingerprint) && !this.fingerprint.equals(computeServerFingerprint())) {
                this.state = VerificationState.FINGERPRINT_MISMATCH;
                this.errorMessage = "License bound to a different server";
                return;
            }
            this.state = VerificationState.VERIFIED;
            this.errorMessage = null;
        } catch (Exception e) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "License verification error: " + e.getMessage();
        }
    }

    /**
     * Verifies the Ed25519 signature over the payload using the configured
     * public key. Fail-closed: a missing or malformed public key disables
     * premium verification entirely rather than trusting the key.
     */
    private boolean verifySignature(String payload, byte[] providedSignature) {
        byte[] publicKeyBytes = getPublicKeyBytes();
        if (publicKeyBytes == null) {
            this.errorMessage = PUBLIC_KEY_ENV + " is not configured "
                + "(base64 X.509 Ed25519 public key); premium verification is disabled.";
            return false;
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(providedSignature);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] getPublicKeyBytes() {
        String encoded = System.getProperty(PUBLIC_KEY_PROPERTY);
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getenv(PUBLIC_KEY_ENV);
        }
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String computeServerFingerprint() {
        try {
            String raw = "";
            try {
                raw = raw + FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            } catch (Exception ignored) {
                // FabricLoader unavailable (e.g. plain unit tests) - hash what we have.
            }
            raw = raw + InetAddress.getLocalHost().getHostName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16).toUpperCase();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public VerificationState getState() {
        return this.state;
    }

    public String getLicenseeName() {
        return this.licenseeName;
    }

    public LocalDate getExpiryDate() {
        return this.expiryDate;
    }

    public String getFingerprint() {
        return this.fingerprint;
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public boolean isPremiumEnabled() {
        return this.state == VerificationState.VERIFIED;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public enum VerificationState {
        UNVERIFIED,
        VERIFIED,
        EXPIRED,
        FINGERPRINT_MISMATCH,
        INVALID
    }
}
