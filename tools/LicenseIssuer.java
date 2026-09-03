import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Solidus SA2 license issuer (Ed25519) for Solidus Governance.
 *
 * <p>This tool is the ONLY place where the signing private key is ever used.
 * Run it on a trusted, offline-capable machine - never on customer servers.
 * Customer servers receive only the <b>embedded</b> public key inside the
 * mod JAR and can verify licenses but can never mint them.</p>
 *
 * <p>The issued key format matches the Governance verifier exactly:
 * {@code SA2.<base64url(payload)>.<base64url(signature)>} with the 6-field
 * payload {@code 2|<customer>|<expiry|PERPETUAL>|<fingerprint|ANY>|<product>|<nonce>}.
 * The product defaults to {@code governance-premium}; keys issued for other
 * products are rejected by this mod.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   # 1. Generate a keypair (do this ONCE, keep the private key offline):
 *   java tools/LicenseIssuer.java generate
 *
 *   # 2. Embed the PUBLIC key into the mod (developer step, see SECURITY.md):
 *   #      put it into SA2_PUBLIC_KEY_B64 in license/LicenseVerifier.java
 *
 *   # 3. Get the target server's fingerprint (from that server's
 *   #    /governance fingerprint output):
 *   java tools/LicenseIssuer.java fingerprint &lt;server-game-dir&gt;
 *
 *   # 4. Issue a license:
 *   java tools/LicenseIssuer.java issue &lt;privateKeyB64&gt; &lt;licensee&gt; &lt;expiry|PERPETUAL&gt; &lt;fingerprint|ANY&gt; [product]
 *
 *   # 5. Verify a key against the public key before shipping it:
 *   java tools/LicenseIssuer.java verify &lt;publicKeyB64&gt; &lt;licenseKey&gt;
 *
 *   # 6. Put the printed SA2 key into config/solidus-governance/license.key
 *   #    on the customer server. No environment variables are needed (the
 *   #    SOLIDUS_LICENSE_PUBLIC_KEY env var is ignored by the mod).
 * </pre>
 *
 * <p>Requires Java 15+ (Ed25519). No external dependencies.</p>
 */
public class LicenseIssuer {

    private static final String DEFAULT_PRODUCT = "governance-premium";
    private static final String PERPETUAL = "PERPETUAL";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "generate".equals(args[0])) {
            generate();
            return;
        }
        if ((args.length == 5 || args.length == 6) && "issue".equals(args[0])) {
            issue(args[1], args[2], args[3], args[4], args.length == 6 ? args[5] : DEFAULT_PRODUCT);
            return;
        }
        if (args.length == 3 && "verify".equals(args[0])) {
            verify(args[1], args[2]);
            return;
        }
        if (args.length == 2 && "fingerprint".equals(args[0])) {
            fingerprint(args[1]);
            return;
        }
        System.err.println("Usage:");
        System.err.println("  java tools/LicenseIssuer.java generate");
        System.err.println("  java tools/LicenseIssuer.java fingerprint <server-game-dir>");
        System.err.println("  java tools/LicenseIssuer.java issue <privateKeyB64> <licensee> <expiry ISO-8601|PERPETUAL> <fingerprint|ANY> [product]");
        System.err.println("  java tools/LicenseIssuer.java verify <publicKeyB64> <licenseKey>");
        System.exit(2);
    }

    private static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        System.out.println("# PUBLIC key - embed this in the mod JAR (SA2_PUBLIC_KEY_B64):");
        System.out.println(publicKey);
        System.out.println();
        System.out.println("# PRIVATE key - KEEP OFFLINE, never share, never put on a server:");
        System.out.println(privateKey);
    }

    private static void issue(String privateKeyB64, String licensee, String expiry, String fingerprint, String product) throws Exception {
        byte[] privateKeyBytes;
        try {
            privateKeyBytes = Base64.getDecoder().decode(privateKeyB64.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: private key is not valid base64");
            System.exit(2);
            return;
        }
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

        if (licensee == null || licensee.isBlank() || licensee.contains("|")) {
            System.err.println("ERROR: licensee must be non-empty and must not contain '|'");
            System.exit(2);
            return;
        }
        String expiryField = PERPETUAL.equalsIgnoreCase(expiry) ? PERPETUAL : expiry;
        if (!PERPETUAL.equals(expiryField)) {
            try {
                LocalDate.parse(expiryField, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                System.err.println("ERROR: expiry must be YYYY-MM-DD or " + PERPETUAL);
                System.exit(2);
                return;
            }
        }
        String fingerprintField = "ANY".equalsIgnoreCase(fingerprint) ? "ANY" : fingerprint.toUpperCase(Locale.ROOT);
        if (!"ANY".equals(fingerprintField) && !fingerprintField.matches("[0-9A-F]{16}")) {
            System.err.println("ERROR: fingerprint must be 16 uppercase hex chars or ANY");
            System.exit(2);
            return;
        }
        if (product == null || product.isBlank() || product.contains("|")) {
            System.err.println("ERROR: product must be non-empty and must not contain '|'");
            System.exit(2);
            return;
        }
        String nonce = randomNonce16();

        String payload = "2|" + licensee + "|" + expiryField + "|" + fingerprintField + "|" + product + "|" + nonce;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        String key = "SA2."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        System.out.println(key);
        System.err.println("# licensee=" + licensee + " expiry=" + expiryField
            + " fingerprint=" + fingerprintField + " product=" + product + " nonce=" + nonce);
    }

    private static void verify(String publicKeyB64, String licenseKey) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyB64.trim());
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            String key = licenseKey.trim();
            if (!key.startsWith("SA2.")) {
                System.out.println("INVALID FORMAT (expected SA2.<payload>.<signature>)");
                System.exit(1);
                return;
            }
            String body = key.substring(4);
            int firstDot = body.indexOf('.');
            int lastDot = body.lastIndexOf('.');
            if (firstDot < 0 || firstDot != lastDot) {
                System.out.println("INVALID STRUCTURE (expected exactly two '.' separators)");
                System.exit(1);
                return;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(body.substring(0, firstDot));
            byte[] signature = Base64.getUrlDecoder().decode(body.substring(firstDot + 1));
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payloadBytes);
            if (!verifier.verify(signature)) {
                System.out.println("SIGNATURE INVALID");
                System.exit(1);
                return;
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 6) {
                System.out.println("PAYLOAD INVALID (expected 6 fields, got " + fields.length + ")");
                System.exit(1);
                return;
            }
            System.out.println("VALID");
            System.out.println("  customer   : " + fields[1]);
            System.out.println("  expiry     : " + fields[2]);
            System.out.println("  fingerprint: " + fields[3]);
            System.out.println("  product    : " + fields[4]);
            System.out.println("  nonce      : " + fields[5]);
            if (!DEFAULT_PRODUCT.equals(fields[4])) {
                System.out.println("  NOTE: product is not '" + DEFAULT_PRODUCT + "' - this key will NOT activate Governance premium.");
            }
        } catch (Exception e) {
            System.out.println("VERIFY ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String randomNonce16() {
        byte[] buf = new byte[8];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : buf) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Computes the server fingerprint exactly like LicenseVerifier inside the
     * mods: sha256(gameDirAbsolutePath + hostname), first 16 hex chars upper.
     */
    private static void fingerprint(String gameDir) throws Exception {
        String raw = (gameDir == null ? "" : gameDir)
            + java.net.InetAddress.getLocalHost().getHostName();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        System.out.println(sb.substring(0, 16).toUpperCase());
    }
}
