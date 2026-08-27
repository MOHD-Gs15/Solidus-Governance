package com.solidus.governance.license;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.loader.api.FabricLoader;

public class LicenseVerifier {
    private static final String VERSION_PREFIX = "SA1";
    private static final String LICENSE_SECRET_ENV = "SOLIDUS_LICENSE_SECRET";
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
        }
        catch (Exception e) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "Failed to read license key: " + e.getMessage();
        }
    }

    public void verifyKey(String key) {
        try {
            String serverFp;
            byte[] signatureBytes;
            if (key == null || key.isBlank()) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Empty license key";
                return;
            }
            String[] parts = key.split("-");
            if (parts.length != 3 || !VERSION_PREFIX.equals(parts[0])) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid license key format";
                return;
            }
            byte[] payloadBytes = Base64.getDecoder().decode(parts[1]);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            if (!this.verifyHMAC(payload, signatureBytes = Base64.getDecoder().decode(parts[2]))) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid license signature";
                return;
            }
            String[] fields = payload.split("\\|");
            if (fields.length < 4) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid payload structure";
                return;
            }
            String version = fields[0];
            if (!VERSION_PREFIX.equals(version)) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Unsupported license version: " + version;
                return;
            }
            this.licenseeName = fields[1];
            try {
                this.expiryDate = LocalDate.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE);
            }
            catch (DateTimeParseException e) {
                this.state = VerificationState.INVALID;
                this.errorMessage = "Invalid expiry date in license";
                return;
            }
            if (this.expiryDate.isBefore(LocalDate.now())) {
                this.state = VerificationState.EXPIRED;
                this.errorMessage = "License expired on " + String.valueOf(this.expiryDate);
                return;
            }
            this.fingerprint = fields[3];
            if (!"ANY".equals(this.fingerprint) && !this.fingerprint.equals(serverFp = LicenseVerifier.computeServerFingerprint())) {
                this.state = VerificationState.FINGERPRINT_MISMATCH;
                this.errorMessage = "License bound to a different server";
                return;
            }
            this.state = VerificationState.VERIFIED;
            this.errorMessage = null;
        }
        catch (Exception e) {
            this.state = VerificationState.INVALID;
            this.errorMessage = "License verification error: " + e.getMessage();
        }
    }

    private boolean verifyHMAC(String payload, byte[] expectedSignature) {
        String secret = System.getenv(LICENSE_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            this.errorMessage = LICENSE_SECRET_ENV + " is not configured; premium verification is disabled.";
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return LicenseVerifier.constantTimeEquals(computed, expectedSignature);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; ++i) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    public static String computeServerFingerprint() {
        try {
            Object raw = "";
            try {
                raw = (String)raw + FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            }
            catch (Exception exception) {
                // empty catch block
            }
            raw = (String)raw + InetAddress.getLocalHost().getHostName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(((String)raw).getBytes(StandardCharsets.UTF_8));
            return LicenseVerifier.bytesToHex(hash).substring(0, 16).toUpperCase();
        }
        catch (Exception e) {
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


    public static enum VerificationState {
        UNVERIFIED,
        VERIFIED,
        EXPIRED,
        FINGERPRINT_MISMATCH,
        INVALID;

    }
}
