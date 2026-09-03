# Security policy

## High-impact operations

Solidus Governance can change balances, freeze accounts, lock trading, apply taxation, execute automation rules, create snapshots, and perform rollback operations. Test all mutation paths on a disposable server before production use, and keep automatic taxation, automation, and rollback disabled until the exact Solidus Core version has been verified.

## Secrets

Never commit `license.key`, the license signing PRIVATE key, Discord webhook URLs, databases, runtime logs, or server configuration. The private signing key belongs in a private operator-only service and must never be embedded in the public mod JAR or repository.

Licenses use the SA2 format: `SA2.<base64url(payload)>.<base64url(Ed25519 signature)>` with the 6-field payload `2|<customer>|<expiry YYYY-MM-DD or PERPETUAL>|<fingerprint 16-hex or ANY>|<product>|<nonce 16-hex>`. The product field must equal `governance-premium` exactly — keys issued for another Solidus product do not activate this mod.

**Trust anchor (audit round 3):** the PUBLIC Ed25519 verification key is EMBEDDED in the mod JAR (`SA2_PUBLIC_KEY_B64` in `license/LicenseVerifier.java`, filled before a release build via `java tools/LicenseIssuer.java generate`). The legacy `SOLIDUS_LICENSE_PUBLIC_KEY` environment variable and `solidus.license.publicKey` system property are **ignored**: they are customer-settable, and a customer-settable verification key would let any buyer self-sign licenses (the SA1 flaw, reintroduced). The private key is used exclusively by `tools/LicenseIssuer.java` on an offline machine. Legacy SA1 keys (client-held HMAC secret, forgeable by design) and legacy dash-format `SA2-` keys (issued under the customer-settable-key workflow) are rejected outright. While the embedded key is still the placeholder, every SA2 key fails **closed** — premium simply stays disabled.

If a credential is exposed, revoke it immediately and issue a replacement with the smallest possible scope.

## Integration safety

Governance currently contains a compatibility bridge for recovered Solidus Core installations. Prefer a versioned Core API over reflection and direct access to private storage. If the bridge cannot confirm a balance mutation, the operation must be treated as failed and must not be marked successful in the audit log. Failed treasury deposits after a collected tax are refunded to the player and re-parked as pending debt (never silently destroyed with a success audit row). Auction proceeds that settle onto a frozen seller are escrowed into the treasury with a full audit trail (`FROZEN_PROCEEDS_ESCROW`).

Rule-engine `set_config`/`enable_feature`/`disable_feature` actions are confined to an economy-tuning allowlist; enforcement switches (`enforcement.*`, `taxation.treasury.account`, `audit.*`, `discord.webhook.*`) cannot be flipped by a rule.
