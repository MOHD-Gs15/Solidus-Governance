# Security policy

## High-impact operations

Solidus Governance can change balances, freeze accounts, lock trading, apply taxation, execute automation rules, create snapshots, and perform rollback operations. Test all mutation paths on a disposable server before production use, and keep automatic taxation, automation, and rollback disabled until the exact Solidus Core version has been verified.

## Secrets

Never commit `license.key`, `SOLIDUS_LICENSE_SECRET`, Discord webhook URLs, databases, runtime logs, or server configuration. The license signing secret belongs in a private operator-only service and must never be embedded in the public mod JAR or repository.

If a credential is exposed, revoke it immediately and issue a replacement with the smallest possible scope.

## Integration safety

Governance currently contains a compatibility bridge for recovered Solidus Core installations. Prefer a versioned Core API over reflection and direct access to private storage. If the bridge cannot confirm a balance mutation, the operation must be treated as failed and must not be marked successful in the audit log.
