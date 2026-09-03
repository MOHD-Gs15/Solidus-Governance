# Backup & Recovery Runbook (Solidus Governance)

This module owns the only verified backup/restore workflow in the Solidus
ecosystem. It protects **every** SQLite database of the stack, including the
ones owned by other mods.

## Covered databases

| Database | Owner | Location |
|---|---|---|
| `governance.db` | Governance (audit + policy) | `config/solidus-governance/` |
| `limits.db` | Governance | `config/solidus-governance/` |
| `tax_ledger.db` | Governance | `config/solidus-governance/` |
| `events.db` | Governance | `config/solidus-governance/` |
| `rules.db` | Governance | `config/solidus-governance/` |
| `economy.db` | Solidus Core | `config/solidus/` |
| `auctions.db` | Solidus Core | `config/solidus/` |
| `analytics.db` | Solidus Analytics | `config/solidus-analytics/` |

Databases that do not exist (e.g. analytics not installed) are **skipped with
a reason**, never counted as failures.

## How backups work

Every run creates `config/solidus-governance/backups/backup-<timestamp>[-<label>]/`
containing:

- one `<db>.db` copy per existing source database, produced with SQLite
  `VACUUM INTO` — a consistent, compact snapshot that is safe while the
  server keeps writing (WAL-safe);
- a sidecar `<db>.json` with size, SHA-256 and integrity result (used by
  restore verification);
- a human-readable `manifest.json`.

Both verification gates must pass before a copy is considered valid:

1. `PRAGMA integrity_check` on the backup copy must return `ok`;
2. the SHA-256 in the sidecar must match the file (re-checked at restore time).

Retention: the newest `recovery.backup.retention` runs are kept (default 7).
The `backups/quarantine/` directory is never deleted by retention.

## Commands (permission: ADMINS)

```
/governance recovery backup create [label]
/governance recovery backup list [count]
/governance recovery backup status
/governance recovery backup restore <run> <database>          # preview only
/governance recovery backup restore <run> <database> confirm  # execute
```

Every backup and restore is written to the audit log (category `RECOVERY`)
and announced on the Discord `recovery` webhook when enabled.

## Automatic backups

Configured in `config/solidus-governance/governance.properties`:

```properties
recovery.backup.enabled=true
recovery.backup.auto-interval-hours=24
recovery.backup.retention=7
```

The schedule is measured from the newest existing backup manifest, so server
restarts never reset or skip the interval.

## Restore semantics

- **Governance-owned databases**: live restore. Connections are closed, the
  current file is moved to `backups/quarantine/<db>.<timestamp>`, the verified
  copy is placed, and the database re-initializes on the restored data — no
  restart needed.
- **Core/Analytics-owned databases** (`economy.db`, `auctions.db`,
  `analytics.db`): the mod cannot close their persistent connections, so the
  file is swapped on disk and becomes active **after the next server
  restart**. Writes between the swap and the restart continue into the
  quarantined file and are never lost.

The previous file is always quarantined before any swap; a failed restore
leaves the original recoverable from `backups/quarantine/`.

## Recommended recovery workflow (corruption / data-loss incident)

1. Stop the server (or at minimum freeze trading:
   `/governance intervention lock <reason>`).
2. Inspect candidates: `/governance recovery backup list 20`.
3. Preview: `/governance recovery backup restore backup-... economy.db`
   — read the plan; verification (SHA-256 + integrity) runs here.
4. Execute: append `confirm`.
5. Restart the server for core/analytics databases.
6. Verify data, then `/governance intervention unlock`.
7. Create a fresh backup: `/governance recovery backup create post-recovery`.

## Limits and notes

- Backups protect **economy data**, not world files or `shop.json` (use the
  server's regular world backups for those).
- SQLite `VACUUM INTO` requires SQLite 3.27+ (bundled with the mod).
- Restoring `economy.db` while players are online is safe but pointless until
  restart — schedule core-database restores for maintenance windows when
  possible.
- **WAL sidecars (audit round 3):** live SQLite databases keep recent writes in
  `<db>-wal` / `<db>-shm` sidecar files. A restore now quarantines those
  sidecars together with the main file; before this fix, a stale hot WAL left
  next to a freshly restored copy was REPLAYED onto it on the next open,
  silently resurrecting the post-backup transactions the restore was meant to
  remove (or corrupting the file).
- Live restores of Governance-owned databases re-initialize their write
  executors, so audit logging keeps working after `governance.db` or
  `limits.db` is restored in place (previously every later audit write hit a
  terminated executor and money mutations could land with no audit rows).
