# Solidus Governance 2.1.1 — Reliability & Stability Audit Report

**Repository:** https://github.com/MOHD-Gs15/Solidus-Governance
**Audited commit (baseline):** `25d4152` (`chore(ci): SA2-027 supply-chain hardening`)
**Audit scope:** Reliability and stability, with the same dimensions applied to solidus-core: client–server conflicts, accounting/financial integrity, data duplication, data disappearance, hangs and deadlocks.
**Method:** Full read of every production source file (~12.5k LOC across 37 files), cross-checked against the Solidus-core 2.2.6 audit (enforcement bridge contract, `SolidusAPI` semantics, storage thread model), plus a dedicated pass over the 2,192-line command layer. All fixes verified with `compileJava` and the full test suite.
**Result:** **14 findings fixed** (4 carried over from an interrupted reliability round: GOV‑01..GOV‑04; 10 new: GOV‑05..GOV‑14), 5 lower-severity items documented as accepted/documented (GOV‑15..GOV‑19). No CRITICAL money-loss/duplication defect was found in this round — the codebase already carries the A/B/C/D hardening from earlier audit rounds, and that hardening was re-verified during this pass.

---

## 1. Context and architecture recap

Solidus Governance is a server-side Fabric mod that governs a server running Solidus-core's economy: progressive taxes with a persistent tax-debt ledger, transaction/bid daily limits, freezes, an emergency trading lock, rules/policies/events, backups/snapshots/rollbacks, simulation, profiles, Discord alerts, and an Ed25519 license gate.

Two integration surfaces dominate its reliability profile:

1. **CoreHookBridge** — a reflective `Proxy` registered as a `SolidusTransactionHook` on Core 2.1.0+. Veto hooks (`allowXxx`) run **synchronously inside Core's money-movement paths** and are fail-closed by default; notification hooks (`afterXxx`) run post-settlement and fail open.
2. **SolidusIntegration** — method-handle bridge to `SolidusAPI` (`getBalanceOffline`, `addBalanceOffline`, `subtractBalanceOffline`, `getTopBalances`, `getEconomyStats`) plus a reflective `setBalance` into Core's storage.

Thread model per module: five small SQLite databases (audit, limits, tax ledger, events, rules) each serialize **writes** on a dedicated single-thread executor while **reads run on the calling thread** against a shared `Connection`; async work rides `CompletableFuture` chains on the common pool or dedicated executors; command feedback is marshalled back through `server.execute(...)`.

The prior audit rounds (A‑*, B‑*, C‑*, D‑* references in the code) were verified during this pass — the fixes are present, coherent, and covered by tests. This round's findings are numbered **GOV‑01…** to distinguish them from the earlier rounds.

---

## 2. Findings (fixed)

### GOV-01 — `ConcurrentModificationException` between event creation and the expiration sweep (HIGH, carry-over)

**Where:** `events/EventManager.java` (create/cancel/tick paths), `events/EconomyEvent.java`.

**Defect:** The `activeEvents` map and `allEvents` list were mutated under `createLock` only, while `tickExpirations()` (invoked from the server tick thread every minute), `getAllEvents()` (command thread) and `loadFromDatabase()` iterated the same collections under **their own** monitors. An admin creating an event while the tick sweep expired another one threw `ConcurrentModificationException` directly into the server thread. Worse, the corruption window could leave the config modifications applied but the event unregistered — a stranded 2× shop multiplier or 0% tax day.

**Fix (this round):** Both collections are now mutated under their own monitors as well (`createLock` stays outermost; no other path takes a collection lock and then `createLock`, so no lock-order inversion is possible). `EconomyEvent.active` is now `volatile` — it is flipped from both the server-tick expiration sweep and the async cancel/create completions.

---

### GOV-02 — Bulk interventions resolved accounts BY NAME (HIGH, carry-over)

**Where:** `intervention/InterventionManager.java` — `bulkMultiply`, `bulkSetAll`.

**Defect:** Both bulk operations called `SolidusIntegration.setBalance(null, playerName, …)` even though Core ≥ 2.1.x leaderboard entries carry the account's real UUID. After a rename, or on offline-mode servers, a bulk multiply/set could **write the wrong player's balance** — an effectively irreversible admin intervention. The wealth-cap automaton and wealth decay had already been fixed UUID-first; the intervention path had been missed.

**Fix (this round):** `resolveBulkUuid(entry)` prefers the UUID supplied by Core, and only a genuinely old Core (no `uuid()` accessor) degrades to name resolution — with a loud warning naming the risk, matching the contract already enforced by `TaxEngine.applyWealthDecay` and `GovernanceAutomator.checkWealthCaps`.

---

### GOV-03 — 10,000-row synchronous audit read on the server thread (HIGH, carry-over)

**Where:** `recovery/RollbackEngine.java` — `rollbackTimeframe`, `dryRunRollbackTimeframe`.

**Defect:** Both entry points pulled the newest `ROLLBACK_TIMEFRAME_WINDOW` (10,000) audit rows **synchronously on the calling command/server thread**. On a large WAL database under write load, that `SELECT *` is a visible tick stall, repeatable by any admin.

**Fix (this round):** The row read now runs via `CompletableFuture.supplyAsync(...)`; the rollback lock (`tryBeginRollback`) is taken **before** the read so a queued second call still serializes, and the dry-run companion got the same treatment.

---

### GOV-04 — Shutdown flush could stall `SERVER_STOPPING` for up to ~8 minutes (HIGH, carry-over)

**Where:** `discord/WebhookRateLimiter.java` — `shutdown()`.

**Defect:** The earlier D‑8 fix bounded each queued message's flush wait to 10 seconds, but the bound was **per message**. With a full queue of 50 and a slow/hung webhook endpoint, `WebhookManager.shutdown()` (running during `SERVER_STOPPING`) could stall the server stop for up to ~8 minutes.

**Fix (this round):** The flush now has a **total budget**: 5 s per message, 30 s overall; anything undeliverable in that window is dropped and counted, with a summary log line (`N delivered, M dropped`). Nothing user-visible is lost silently — every drop is logged.

---

### GOV-05 — `/governance simulation refresh` ran a fresh JDBC `COUNT(*)` on the server thread (HIGH, new)

**Where:** `simulation/SimulationEngine.java` (`forceRefreshAccountCount`, `tryJdbcAccountCount`), `commands/GovernanceCommand.java` (`executeSimulationRefresh`).

**Defect:** The command called `forceRefreshAccountCount()` **directly on the server thread**. That method opens a brand-new JDBC connection to Core's `economy.db` and runs `SELECT COUNT(*) FROM player_balances WHERE last_updated > ?` — with a **full-table-scan fallback** (`SELECT COUNT(*) FROM player_balances`) when the date filter fails. On a large economy this is tens–hundreds of milliseconds of tick stall per invocation, repeatable by any admin, with no cooldown.

**Fix:** Added `forceRefreshAccountCountAsync()` (`CompletableFuture.supplyAsync`); the command now completes asynchronously with the same feedback marshalled through `server.execute(...)`, including a red failure line on exception. The periodic path (simulation loop thread) was already off-thread and is unchanged.

---

### GOV-06 — Anti-inflation automation: duplicate stats dispatch + permanently wedged in-flight flag (MEDIUM, new)

**Where:** `automation/GovernanceAutomator.java` — `checkAntiInflation`.

**Defect (three interacting problems):**
1. The `finally` block called `SolidusIntegration.getEconomyStats()` **a second time** merely to decide whether to clear the in-flight flag. On a modern Core that dispatches a **real second SQL aggregate query whose result is discarded** — double the load every 60 s.
2. The flag was released as soon as the stats future completed — **before** the legacy row-pull continuation (`getTopBalances(100000).thenAccept(...)`) finished, so two evaluations could overlap on old-Core builds (the B‑11 guard the comment claims was weakened in practice).
3. `getEconomyStats()` never actually returns `null`, so the `finally` guard was dead code — and the **real** hazard was left uncovered: a stats future that never completes (hung Core storage) wedged `antiInflationInFlight` **true forever**, silently disabling anti-inflation until restart.

**Fix:** The evaluation chain is now built as one future (stats → legacy row-pull fallback) and the flag is released exactly once, in the chain's `whenComplete`. Every future in the chain carries a timeout (`orTimeout(30s)` for the stats aggregate, `orTimeout(60s)` for the legacy row pull), so a hung Core can no longer wedge the automation — a timeout degrades to a skipped cycle with a WARN, never a stuck flag. The duplicate dispatch is gone. An exception before the chain is armed still releases the flag in `finally`.

---

### GOV-07 — Synchronous SQLite reads on the server thread across the command layer (MEDIUM, new)

**Where:** `commands/GovernanceCommand.java` (`executeAuditRecent`, `executeAuditSearch`, `executeTimeline`), `recovery/RollbackEngine.java` (`rollbackById`, `rollbackPlayer`, `dryRunRollback`, `dryRunRollbackPlayer`).

**Defect:** The GOV‑03 fix moved the timeframe rollback's 10k-row read off-thread — but the remaining read paths still executed SQLite `SELECT`s inline on the command (server) thread: `audit recent` (≤50 rows), `audit search` (20 rows), `recovery timeline` (15 rows), and the rollback-by-id / rollback-by-player **pre-reads** (single-row and 100-row respectively) that ran before the async boundary. Small row counts, but the reads contend on the **same single `Connection`** the audit writer thread uses — a pending write batch stalls them further, and it is the exact anti-pattern the codebase had already fixed elsewhere.

**Fix:** Every one of these reads now runs via `CompletableFuture.supplyAsync(...)`, with rendering and feedback marshalled back through `server.execute(...)`. `RollbackEngine` gained `applyRollbackById` / `applyRollbackPlayer` / `dryRunRollbackFromEntry` / `dryRunRollbackPlayerFromEntries` continuations so the reads, validation, and the serialized mutation chain stay in the correct order — including taking the rollback lock before the read where the original code did.

---

### GOV-08 — Async operations that reverted live economy config could fail silently (MEDIUM, new)

**Where:** `commands/GovernanceCommand.java` — `executeEventCancel`, `executeDryRun(Player|Timeframe)`, `executeRuleAdd/Toggle/Delete`.

**Defect:** `eventManager.cancelEvent(eventId).thenAccept(...)` had **no `.exceptionally`** — if the async body threw (DB/runtime error during `revertEventConfig` or `database.updateEvent`), the future completed exceptionally and the admin got **no feedback at all** on an operation that reverts live economy config. The three dry-run previews and the three rule-mutation commands had the same hole. Every comparable command (rollback, snapshot, backup, event create, discord test) already had the handler.

**Fix:** All seven call sites now carry `.exceptionally(...)` with a red failure line routed through `server.execute(...)`, matching the established pattern.

---

### GOV-09 — Partial player profiles rendered as confident real data (MEDIUM, new)

**Where:** `profile/ProfileGenerator.java`, `profile/PlayerProfile.java`, `commands/GovernanceCommand.java` (`executeProfile`).

**Defect:** The profile pipeline swallowed failures at three layers and returned a half-populated profile that rendered exactly like a successful one: the outer `exceptionally` returned the partial profile as success; a failed balance lookup produced `Balance: 0.00`; a failed leaderboard scan produced `Rank: #0 of 0`. For an accounting tool this is misleading data that invites wrong admin decisions (e.g., "topping up" a player whose balance is actually fine while Core is merely unreachable). Note also that `SolidusIntegration`'s documented sentinel for "unavailable" is `-1.0` — it flowed straight into `setBalance` and rendered as a real balance.

**Fix:** `PlayerProfile` now carries a `volatile dataComplete` flag + reason. The balance sentinel (`< 0`) is detected and flagged instead of stored; the rank failure path flags the profile; the outer `exceptionally` marks it partial with the underlying message. `/governance profile` prints a red **`⚠ PARTIAL DATA: <reason> — figures below may be unreliable.`** banner before any numbers whenever the flag is set. `PlayerProfile.formatProfile()` renders the same marker.

---

### GOV-10 — `getPlayerStats` had no row limit — unbounded scan per profile (MEDIUM, new)

**Where:** `audit/AuditDatabase.java` — `getPlayerStats`.

**Defect:** The stats query (`SELECT … WHERE target_uuid = ? AND timestamp >= ? ORDER BY timestamp ASC`) had **no LIMIT**. `/governance profile` executes it twice (7-day and 24-hour windows) plus `getFirstAuditTimestamp`, all off-thread but unbounded: a bot-tier player with tens of thousands of audit rows inside a week produced an unbounded in-memory row stream per invocation, throttled only by the 30-second global cooldown.

**Fix:** Added `LIMIT ?` with a new `MAX_STATS_ROWS = 50_000` constant — far beyond any legitimate single-player window, and now bounded. Documented in the constant's Javadoc.

---

### GOV-11 — Rule engine dragged a 1,000-row SQLite read into the server tick in standalone mode (MEDIUM, new)

**Where:** `rules/RuleEngine.java` — `evaluateAll` / `computeContext` / `computeTransactionVolume24h`.

**Defect:** `evaluateAll()` runs from the server tick every 60 s when rules are enabled. `computeContext()` chains futures that are **already completed** when Core is absent (`completedFuture(null)`) or old — and a completed future executes its `thenCompose`/`thenApply` callback **synchronously on the calling thread**. In standalone mode (premium license, no Core — a documented, supported configuration) that meant `finishContext` → `computeTransactionVolume24h()` → `searchByCategory("TAXATION", 1000)` ran as a synchronous 1,000-row SQLite read **inside the server tick, every minute**.

**Fix:** `evaluateAll()` now starts with `CompletableFuture.supplyAsync(this::computeContext).thenCompose(f -> f)`, which guarantees the entire context computation — including the legacy fallback shapes — stays off the server thread no matter which Core configuration answers. The rest of the body (triggering, cooldowns, action dispatch through `srv.execute`) is unchanged.

---

### GOV-12 — Contradictory audit trail for refunded taxes (MEDIUM, new — accounting integrity)

**Where:** `taxation/TaxEngine.java` — `collectNow` (now split into `collectNow` + `settleTreasury`).

**Defect:** The D‑3/B‑4 fix made a failed treasury deposit refund the player and re-park the debt — but the surrounding chain then **still returned `taxAmount` and still wrote the success `TAX_COLLECTION(before, newBalance)` audit row**. Consequences: (a) the permanent audit trail claimed a tax was collected that had just been refunded — precisely the rows an admin reads during an incident review contradicted the `DEPOSIT_FAILED`/`REFUND` rows written a moment later; (b) in the retry sweeper (`processPendingTaxes`), the old debt row was marked *collected* while a duplicate debt row tracked the same tax; (c) `collectTaxAsync` saw `collected > 0` and skipped its own parking because the refund path had internally enqueued — correct net debt, but two code paths each assuming the other would fail. Additionally, an **invalid treasury UUID** silently burned the tax (only a WARN), a misconfiguration the admin can fix in seconds.

**Fix:** The treasury leg and the completion value are now one consistent decision (`settleTreasury`): returns `taxAmount` **only** when truly settled (treasury credited, or no treasury configured — intentional, documented burn); returns `0.0` when the deposit failed and the player was refunded, so **exactly one** caller (`collectTaxAsync` or the sweeper) re-parks the debt; if the refund itself fails, the amount is reported as taken (money did leave the player; CRITICAL limbo logs) so the debt can never be double-charged. No `TAX_COLLECTION` success row is written for a refunded tax. An invalid treasury UUID now takes the refund-and-retry path instead of burning player money.

---

### GOV-13 — Torn daily-usage persistence pair (LOW, new)

**Where:** `limits/TransactionLimits.java` — `persistUsage`.

**Defect:** The two `volatile` usage fields were read **outside** the per-usage monitor that reservations use. A concurrent increment landing between the two reads persisted a torn `(newTotal, oldCount)` pair to `limits.db`. Self-healing on the next persist, but the persisted state was momentarily wrong — and this is the state a restart would load.

**Fix:** Both fields are snapshotted under `synchronized (usage)` — the same critical section the check-and-reserve logic uses — before handing them to the writer.

---

### GOV-14 — Anti-inflation automation fought TAX_HOLIDAY events over `taxation.auction.rate` (LOW, new)

**Where:** `automation/GovernanceAutomator.java` — `evaluateAntiInflation`; interacts with `events/EventManager`.

**Defect:** A live `TAX_HOLIDAY` event forces all tax rates to `0.0`. If the anti-inflation automation was also enabled and the average balance sat above its threshold, it would **raise `taxation.auction.rate` mid-event** — lifting the announced holiday; and when the event later reverted config to its captured pre-event values, the automation's change was silently discarded (or the revert was silently overwritten, depending on ordering). Two independent writers, one key, no coordination.

**Fix:** `evaluateAntiInflation` now suspends itself (raise and decrease branches both) while any `TAX_HOLIDAY` event is active, with a debug log naming the blocking event. The check reads `getActiveEvents()` (a defensive copy), so it is race-free with the expiration sweep.

---

## 3. Findings (documented — accepted with rationale, no code change)

These were verified, judged lower-risk, and deliberately left as documented behavior. They are listed so the next maintainer sees them with a severity and a rationale instead of rediscovering them.

### GOV-15 — Shared SQLite `Connection` across reader threads and the writer executor (LOW)

`AuditDatabase`, `LimitsDatabase`, `TaxLedgerDatabase`, `EventDatabase`, `PolicyDatabase` all follow one pattern: writes serialized on a dedicated single-thread executor, reads executed on arbitrary calling threads, **one shared `Connection`**. sqlite-jdbc is built in serialized mode and WAL mode allows concurrent readers, so this works in practice; the risk is elevated `SQLITE_BUSY` under write bursts (reads fail, are caught, and return empty — the code treats that as "no data" rather than crashing). All hot-path reads have now been moved off the server thread (GOV‑05/07/11), which was the part that actually hurt ticks. A future hardening step would be a read-write connection pair or `busy_timeout` PRAGMA per connection.

### GOV-16 — Global cooldown statics shared by all admins (LOW)

`lastProfileCommandMs` / `lastAuditExportMs` in `GovernanceCommand` are class-level statics: one admin's export blocks **every other admin's** profile/export for 30 s. The check-then-set is not atomic, but Brigadier executes on the server thread so no race exists today. Per-source cooldown keys would be the fix if multi-admin servers report friction.

### GOV-17 — `audit recent` / `audit search` readable at GAMEMASTERS while `audit export` requires ADMINS (LOW)

The full admin action history (who intervened on whom, tax/rollback actions, target names) is readable by level-2 operators; only the CSV export is gated to full ADMINS. Likely unintended privilege breadth rather than a missing check — flagged for the maintainer to confirm intent.

### GOV-18 — `audit search player` requires the target to be online (LOW)

`EntityArgument.player()` forces an online target, yet `searchByTarget(UUID, int)` works for any UUID — the exact players an admin usually investigates (offline offenders) are unreachable via this branch. A UUID-or-name argument would fix it.

### GOV-19 — `PlayerProfile.formatProfile()/formatFreeProfile()` dead code (LOW)

`executeProfile` re-implements the rendering inline; the class formatters are unused and already drifting (rank handling differs). Cosmetic/maintainability only.

### Accepted-by-design (verified, no change needed)

- **Minute-cadence small reads on the tick thread:** `AccountFreezer.checkExpirations()` (tiny freezes table) and `BackupManager.maybeAutoBackup()`'s manifest peek (one small file, hourly) remain synchronous on the tick — both are indexed/paged and bounded; moving them is not worth the complexity today.
- **Event expiry writes config on the tick thread:** `tickExpirations` → `config.set` (atomic 0600 write) happens once per event expiry, not per tick. Rare by construction; acceptable.
- **Veto-path first-touch limit read:** the daily-limits lazy load (`loadDailyUsage`, single PK row) on the hook thread is documented in `CoreHookBridge`'s threading notes and matches Core's expectations.
- **Wealth decay burns rather than treasuries:** documented deflationary behavior, logged with before/after balances.
- **`processPendingTaxes` crash window:** `markCollectedNow` (B‑6) is synchronous in the completion callback; the residual crash-between-subtract-and-DELETE window re-charges at most once and is logged by the ledger's attempt budget. Closing it fully requires Core-side transactional collection.

---

## 4. Verification

| Check | Result |
|---|---|
| `./gradlew compileJava` (JDK 25) | **BUILD SUCCESSFUL** (clean; only pre-existing "unchecked operations" notes) |
| `./gradlew test` | **BUILD SUCCESSFUL — 81 tests, 0 failures, 0 errors, 0 skipped** (15 suites: config, audit CSV, audit reopen, command tree shape, events, license, limits parsing, backup/restore, rollback selection, snapshot names, rule durations, simulation DB path, tax progressive ×3) |
| Behavior deltas | All intentional and listed per-finding above; no API signature removed (`forceRefreshAccountCount` kept, async variant added; `evaluateAntiInflationFromRows` replaced by an async equivalent visible only inside its own class) |

## 5. Files changed

```
 src/main/java/com/solidus/governance/audit/AuditDatabase.java      |  +13 (GOV-10)
 src/main/java/com/solidus/governance/automation/GovernanceAutomator.java | +94 (GOV-06, GOV-14)
 src/main/java/com/solidus/governance/commands/GovernanceCommand.java | +206 (GOV-05/07/08/09)
 src/main/java/com/solidus/governance/discord/WebhookRateLimiter.java | +27 (GOV-04)
 src/main/java/com/solidus/governance/events/EconomyEvent.java       |   +4 (GOV-01)
 src/main/java/com/solidus/governance/events/EventManager.java       |  +29 (GOV-01)
 src/main/java/com/solidus/governance/intervention/InterventionManager.java | +25 (GOV-02)
 src/main/java/com/solidus/governance/limits/TransactionLimits.java  |  +12 (GOV-13)
 src/main/java/com/solidus/governance/profile/PlayerProfile.java     |  +20 (GOV-09)
 src/main/java/com/solidus/governance/profile/ProfileGenerator.java  |  +22 (GOV-09)
 src/main/java/com/solidus/governance/recovery/RollbackEngine.java   |  +71 (GOV-03, GOV-07)
 src/main/java/com/solidus/governance/rules/RuleEngine.java          |  +46 (GOV-11)
 src/main/java/com/solidus/governance/simulation/SimulationEngine.java | +14 (GOV-05)
 src/main/java/com/solidus/governance/taxation/TaxEngine.java        | +191 (GOV-12)
 14 files changed, 564 insertions(+), 210 deletions(-)
```

## 6. Recommendations (beyond this round)

1. **Per-connection `busy_timeout`** on the five SQLite databases (GOV‑15) — one PRAGMA each, removes the residual `SQLITE_BUSY` class entirely.
2. **Per-source cooldowns** for profile/export (GOV‑16) if multi-admin servers report the shared 30 s window.
3. **Offline-player audit search** (GOV‑18): accept a name that resolves via `SolidusIntegration.resolvePlayerUuid` with a UUID fallback argument.
4. **Delete or wire the dead `PlayerProfile` formatters** (GOV-19) so rendering lives in one place.
5. **Consider `getActiveAccountCount` plumbing into `SolidusAPI`** so the simulation refresh never needs its own JDBC connection to Core's database at all.
6. **Core-side transactional tax collection** would close the last crash-window in the pending-tax retry loop (see "Accepted-by-design").
