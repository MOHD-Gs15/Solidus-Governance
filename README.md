# Solidus Governance

Server-side governance tools for Solidus on Minecraft Java 26.1.2. The mod provides audit trails, account interventions, transaction limits, taxation, recovery snapshots, rollback previews, policies, automation rules, simulations, and optional Discord notifications.

## Status

This repository is a clean source reconstruction of the recovered `solidus-governance` artifact. The recovered artifact was decompiled from a JAR, so the implementation is being rebuilt and verified rather than treated as authoritative original source. High-impact operations must be tested on a disposable server before production use.

## Compatibility

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.4 or newer |
| Fabric API | 0.155.2+26.1.2 |
| Fabric Loom | 1.16-SNAPSHOT (resolved to 1.16.3) |
| Java | 25 or newer |

## Safety model

Governance can change balances, freeze accounts, apply taxes, lock trading, execute policies, and perform rollback operations. Mutation commands are restricted to server administrators, while status and audit views use a lower operator permission. The mod should fail closed when Solidus Core is unavailable or when a balance mutation cannot be confirmed.

Automatic taxation, automation rules, emergency lockdown, simulation, Discord webhooks, and automatic recovery snapshots are disabled or conservative by default. Enable each feature only after reviewing its configuration and testing it against the exact Solidus Core version installed on the server.

## Build

```bash
./gradlew clean test
./gradlew build
```

The final JAR is written to `build/libs`. Runtime databases and configuration are created under `config/solidus-governance` and are intentionally ignored by Git.

## Integration

Governance discovers Solidus Core through an isolated compatibility bridge. The bridge currently supports a compatibility fallback for recovered installations, but a versioned Core API is preferred for future releases. Do not rely on direct private SQLite schema access when upgrading Core.

## Secrets

Discord webhook URLs, license keys, `SOLIDUS_LICENSE_SECRET`, databases, and logs must not be committed. The license signing secret belongs in a private operator-only service and must never be embedded in a public mod JAR.

## License

MIT. See `LICENSE`.
