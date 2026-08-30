# Security policy

## High-impact operations

Solidus Governance can change balances, freeze accounts, lock trading, apply taxation, execute automation rules, create snapshots, and perform rollback operations. Test all mutation paths on a disposable server before production use, and keep automatic taxation, automation, and rollback disabled until the exact Solidus Core version has been verified.

## Secrets

Never commit `license.key`, the license signing PRIVATE key, Discord webhook URLs, databases, runtime logs, or server configuration. The private signing key belongs in a private operator-only service and must never be embedded in the public mod JAR or repository.

Licenses use the SA2 format: `SA2-<base64(payload)>-<base64(Ed25519 signature)>` with payload `2|<licensee>|<expiry ISO-8601>|<fingerprint|ANY>`. Verification is asymmetric: servers only need the PUBLIC Ed25519 key via `SOLIDUS_LICENSE_PUBLIC_KEY` (base64 X.509). The private key is used exclusively by `tools/LicenseIssuer.java`; legacy SA1 keys (client-held HMAC secret, forgeable by design) are rejected outright.

If a credential is exposed, revoke it immediately and issue a replacement with the smallest possible scope.

## Integration safety

Governance currently contains a compatibility bridge for recovered Solidus Core installations. Prefer a versioned Core API over reflection and direct access to private storage. If the bridge cannot confirm a balance mutation, the operation must be treated as failed and must not be marked successful in the audit log.
