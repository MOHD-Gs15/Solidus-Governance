# Solidus Governance — Server-Side Minecraft Fabric Mod

[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![Server-Side](https://img.shields.io/badge/Server_Side-Only-brightgreen.svg)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Type](https://img.shields.io/badge/Type-Economy_Governance-8B5CF6.svg)]()

**Economy governance layer for Solidus Core — immutable audit trails, balance interventions, account freezes, transaction limits, taxation, emergency lockdown, economy events, policies, automation rules, and point-in-time recovery. No client mods required.**

Immutable audit trails · Deterministic recovery · Zero client installation · Minecraft 26.1.x Ready

[Features](#-features) · [Recovery](#-recovery) · [Automation](#-automation) · [Quick Start](#-quick-start) · [Commands](#-commands) · [Configuration](#-configuration) · [Architecture](#-architecture) · [FAQ](#-faq)

---

<!-- Schema.org Structured Data for Search Engines
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "Solidus Governance",
  "applicationCategory": "GameModification",
  "operatingSystem": "Minecraft 26.1.x",
  "programmingLanguage": "Java 25",
  "runtimePlatform": "Fabric Loader 0.19.4+",
  "license": "MIT",
  "description": "Server-side economy governance layer for Solidus Core: immutable audit logging, balance interventions, account freezes, transaction limits, taxation, emergency lockdown, economy events, policies, automation rules, and point-in-time rollback recovery. No client mods required.",
  "author": { "@type": "Person", "name": "MOHD_Gs", "url": "https://github.com/mohd-gs" },
  "url": "https://github.com/mohd-gs/Solidus-Governance",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
-->

## Why Solidus Governance?

Solidus Core gives your server an economy — but an economy without oversight drifts. Wealth concentrates, dupes go unnoticed, admins make balance changes nobody can trace, and the only recovery tool is a backup from last night. Solidus Governance is the control plane on top: every administrative mutation is written to a WAL-journaled audit database with before/after values, every high-risk operation has a dry-run preview, and every balance change can be reversed to a deterministic, documented state.

Governance is built for operators who need to *prove* what happened, not just remember it. Rollback operations are excluded from being rolled back themselves (no loops), snapshots are sanitized and retained on a configurable schedule, and the emergency lockdown path freezes trading state in the database so it survives restarts.

### Highlights

* **Fully server-side architecture** — works with any vanilla client, zero client installation
* **Immutable audit trail** — every mutation recorded with before/after values, categories, and rollback chains
* **Balance interventions** — add, remove, and set balances with freeze checks and Discord threshold alerts
* **Account freezes** — permanent or timed, persisted across restarts, auto-expired hourly
* **Point-in-time recovery** — JSON economy snapshots on a schedule (default every 6 hours, minimum 1), dry-run rollback previews, deterministic player/timeframe restore
* **Taxation engine** — transfer/auction/shop rates, treasury account, progressive brackets, hourly wealth decay
* **Automation** — anti-inflation tax adjuster, wealth caps, one-command emergency lockdown
* **Economy events** — DOUBLE_SHOP, TAX_HOLIDAY, INFLATION_SALE, BONUS_CURRENCY with auto-revert
* **Policies, rules, and simulation** *(premium)* — config presets, condition/action rules, adaptive TPS-aware agent simulation
* **Discord webhooks** *(premium)* — 7 routed categories, HTTPS allowlist, rate-limited delivery with retry backoff

---

## Solidus Ecosystem

Solidus Governance is the administration layer of the **Solidus Economy Ecosystem** — a suite of server-side Fabric mods that work together to create a complete, balanced economy for Minecraft servers.

| Module | License | Description |
|--------|---------|-------------|
| [solidus-core](https://github.com/mohd-gs/solidus-core) | MIT | Economy engine, server shop, auction house |
| [solidus-analytics](https://github.com/mohd-gs/solidus-analytics) | MIT | Economy intelligence dashboard, inflation tracking, fraud detection, live web dashboard (AES-256-GCM encrypted) |
| [Solidus-Enforcer](https://github.com/mohd-gs/Solidus-Enforcer) | MIT | Bounty hunting, hunter license system, alliance rewards, autonomous anti-monopoly bounties |
| **Solidus-Governance** | MIT | **Economy administration, audit logging, taxation, recovery** (this repo) |
| [solidus-territory](https://github.com/mohd-gs/solidus-territory) | MIT | Polygon-based land claiming, rent system, territory trading, visual particle borders |

Governance integrates with Solidus Core through a **reflection-based bridge** — zero compile dependency, automatic activation when Core is present, graceful degradation to standalone mode when absent.

> **Repository status**: this codebase is a verified reconstruction of the recovered `solidus-governance` artifact (originally decompiled from a JAR). Every subsystem is being rebuilt, tested, and documented rather than treated as authoritative source. High-impact operations must be tested on a disposable server before production use.

---

## Features

### Audit Trail

Every mutation Governance performs is written to `governance.db` (WAL-mode SQLite) on a dedicated worker thread — intervention balance changes, freezes, tax collections, snapshot creation, rollbacks, automation actions, and config changes all land in one queryable log.

* Before/after values for every balance change, with the exact delta
* Categories: `INTERVENTION`, `TAXATION`, `RECOVERY`, `AUTOMATION`, `LIMITS`, `CONFIG`
* Rollback chain records — every recovery links back to the audit entry that caused it
* Search by target player, admin, or category from the in-game command
* Configurable retention (`audit.retention-days`, default 90) with automatic cleanup

### Interventions & Freezing

* `/governance intervention add|remove|set` — validated balance mutations with full audit entries
* Frozen accounts are refused new interventions and marked in player profiles
* Timed freezes auto-expire (checked every 60 seconds); permanent freezes persist until unfrozen
* Suspicious-account marking with reasons, persisted across restarts
* Trading lock state persisted in the database — survives restarts

### Transaction Limits

Config-driven per-player transfer and auction limits with daily usage persistence:

* Per-transaction minimum and maximum transfer amounts
* Daily transfer total and daily auction listing counts
* Usage stored in `limits.db` keyed by player + date; automatic 7-day cleanup
* Per-player admin reset, audited
* Every rejected transaction is logged to the audit trail and (optionally) Discord

> **Enforcement scope (Governance 1.2.0 + Core 2.1.0+)**: Governance registers a transaction hook with Core at server start, so the emergency trading lock and frozen accounts are **always enforced inside Core's `/pay`, auction, and shop flows** — a denied transaction never moves money, and its reason is shown to the player. Daily limit enforcement inside Core requires a premium license (in free mode limit checks pass through while usage stays tracked and audited). Taxes are collected on settled transfers (sender), auction sales (seller), and shop purchases (buyer) whenever `taxation.enabled=true`. On older Core versions (< 2.1.0) or standalone mode, Governance degrades gracefully to tracking, status views, and violation logging only.

### Taxation

* Transfer, auction, and shop tax rates (validated to 0–100%, every change audited)
* Treasury account — collected taxes are deposited into a configured UUID
* Progressive tax brackets (`/governance tax brackets add`) — applied to transfers on the sender when `taxation.enabled` is on: the bracket is selected by the sender's pre-transfer balance floor and collected under its own audit type `PROGRESSIVE`
* **Wealth decay** — hourly decay of balances above a configurable threshold, computed from the live balance at execution time and fully audited

### Recovery

* JSON economy snapshots — full ranked balance list, timestamped, stored under `config/solidus-governance/snapshots/`
* Automatic snapshots on a configurable interval (default every 6 hours, minimum 1) with retention-based cleanup (default 28)
* Snapshot names are strictly validated — path traversal is rejected before any file is written
* **Rollback by audit ID** — restore a single audited balance change, with dry-run preview showing the exact diff against the current balance
* **Rollback by player / timeframe** — deterministic restore to the *earliest affected action's* before-state in the window, one write per player, never a race
* Recovery operations are themselves audited under the `RECOVERY` category and are excluded from further rollbacks — no rollback loops

### Automation

* **Anti-inflation** — when average balance exceeds a threshold, auction tax rises by steps toward a ceiling; when it falls below 80% of the threshold, the rate relaxes back. Only real adjustments are audited — monitoring checks never spam the audit log
* **Wealth caps** — balances above a configured cap are clamped, with the excess removal audited and announced
* **Emergency lockdown** — one command activates lockdown + trading lock, alerts Discord, and persists state

### Economy Events

Time-limited config modifiers with guaranteed revert — on expiry, on cancellation, and on restart:

* `DOUBLE_SHOP` — shop sell multiplier
* `TAX_HOLIDAY` — all tax rates to zero
* `INFLATION_SALE` — shop buy multiplier
* `BONUS_CURRENCY` — transfer bonus multiplier
* Modifiers are validated (finite, positive, ≤ 100) and same-type events cannot overlap — the captured original values are always restored exactly

### Policies

Snapshot the entire governance config as a named preset, then load it later — an auto-save of the previous config is created before every load:

* Key filtering — only economy-relevant keys are captured; Discord URLs require an explicit opt-in
* Preview the exact key-by-key diff before applying
* Auto-saves expire after 7 days and are cleaned up on startup

### Conditional Rules *(premium)*

* 11 condition types — average balance, inflation rate, 24h transaction volume, money supply, player count, Gini coefficient (above/below each)
* 8 action types — `set_config`, `enable/disable_feature`, `activate/deactivate_lockdown`, `increase/decrease_tax`, `send_discord_alert`
* Per-rule cooldowns with persistence; last-triggered timestamps survive restarts
* Rules and the built-in automator share the same 60-second evaluation pulse

### Agent Simulation *(premium)*

An observational engine that samples the economy on a background thread and reports what it finds — it never mutates balances:

* Adaptive sample sizing (20–500 players, percentage-based, refreshed from the database every 3 hours)
* TPS-aware throttling: slows down under load, pauses below 10 TPS, resumes at 19+
* Gini coefficient, supply growth rate, inflation trend, and actionable recommendations
* `/governance simulation insight` — the full report in-game

### Discord Webhooks *(premium)*

* 7 routed categories: `lockdown`, `intervention`, `taxation`, `automation`, `limits`, `recovery`, `default`
* HTTPS allowlist — only `discord.com`/`discordapp.com` webhook URLs are accepted, and Discord force-disables when none is valid
* 5-second rate limiting with a 50-message bounded queue and exponential-backoff retries
* Daily governance summary on UTC date rollover

### Premium vs. Free

| Free | Premium (license key required) |
| --- | --- |
| Audit trail, interventions, freezes, suspicious accounts | Transaction limits (free mode: tracked + logged only; enforced inside Core with a license) |
| Snapshots, rollback, dry-run, timeline | Discord webhooks (all categories) |
| Taxation config, wealth decay, progressive brackets | Economy events |
| Anti-inflation, wealth caps, emergency lockdown | Policies, conditional rules, agent simulation |
| Player economy profiles (balance, rank, status) | Weekly stats and flags in profiles |

See [SECURITY.md](SECURITY.md) for the secrets policy — license keys, webhook URLs, and `SOLIDUS_LICENSE_SECRET` never belong in Git.

---

## Quick Start

### Installation

> **Requirements:** Minecraft 26.1.x · Java 25 · Fabric Loader 0.19.4+ · Fabric API 0.155.2+ · Solidus Core (recommended)

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
3. Install [Solidus Core](https://github.com/mohd-gs/solidus-core/releases) — Governance reads and mutates its economy through the API bridge
4. Download the latest Solidus Governance release from [Releases](https://github.com/mohd-gs/Solidus-Governance/releases)
5. Place the `.jar` file into your server's `mods/` folder
6. Start the server — configuration is generated at `config/solidus-governance/governance.properties`

**For premium features:** place your license key in `config/solidus-governance/license.key` (single line) before starting the server, and set the `SOLIDUS_LICENSE_SECRET` environment variable on the server process.

**No client installation required.** Everything runs server-side; players notice nothing except fairer moderation.

### First-Time Setup

```
/governance                              ← Governance status overview
/governance license                      ← License verification state
/governance fingerprint                  ← Server fingerprint for license binding
/governance tax rates                    ← Current tax configuration
/governance audit recent 10              ← Last 10 audited actions
/governance audit export csv 30          ← Export the audit trail to CSV
/governance snapshot create baseline     ← Name a recovery snapshot now
```

### Enabling High-Impact Features

Everything destructive is **off by default**. Enable features one at a time and test each against your exact Core version on a disposable server first:

1. **Taxation:** `taxation.enabled=true`, set rates, optionally configure `taxation.treasury.account`
2. **Wealth decay:** `taxation.wealth-decay.enabled=true` (hourly, above threshold)
3. **Automation:** `automation.enabled=true`, then enable only the sub-features you need
4. **Limits / Discord / Events / Policies / Rules / Simulation:** premium license required — see the table above

---

## Commands

Root command: `/governance` (alias `/gov`). Status views are available to game masters; mutations require admins. All access checks are enforced server-side.

| Command | Description |
| --- | --- |
| `/governance` | Governance status overview |
| `/governance license` · `fingerprint` | License state and server fingerprint |
| `/governance intervention add\|remove\|set <player> <amount>` | Balance interventions (audited) |
| `/governance freeze\|unfreeze <player> [minutes]` | Account freeze (omit minutes = permanent) |
| `/governance suspicious list\|mark\|unmark` | Suspicious account registry |
| `/governance lock\|unlock trading <reason>` | Persisted trading lock state |
| `/governance tax rates` · `tax set <type> <rate>` | View/adjust transfer, auction, shop rates |
| `/governance tax brackets list\|add\|remove` | Progressive tax brackets |
| `/governance audit recent [n]` · `audit search <query>` | Audit trail queries |
| `/governance audit export csv [days]` | Export the audit trail to RFC 4180 CSV (default 7 days, max 365) → `config/solidus-governance/exports/` |
| `/governance recovery snapshot create\|list` | Economy snapshots |
| `/governance rollback <auditId>` · `dryrun <auditId>` | Execute or preview a rollback |
| `/governance timeline <player>` | Recent balance timeline with deltas |
| `/governance automation status` · `lockdown activate\|deactivate` | Automation state, emergency lockdown |
| `/governance limits status\|set\|reset\|player` | Transaction limits *(premium)* |
| `/governance discord status\|set\|remove\|test` | Webhook routing *(premium)* |
| `/governance event list\|create\|cancel\|info\|history` | Economy events *(premium)* |
| `/governance policy list\|save\|load\|preview\|delete\|info` | Config presets *(premium)* |
| `/governance rules list\|info\|add\|enable\|disable\|...` | Conditional rules *(premium)* |
| `/governance simulation status\|true\|false\|insight\|refresh` | Agent simulation *(premium)* |
| `/governance profile <player>` | Economy profile with flags |

---

## Configuration

Governance generates configuration automatically on first run. Every mutation command writes the file immediately; all values are validated and clamped on load.

**Location:** `config/solidus-governance/governance.properties`

```properties
# Taxation (rates validated 0.0 - 1.0)
taxation.enabled=false
taxation.transfer.rate=0.0
taxation.auction.rate=0.05
taxation.shop.rate=0.0
taxation.treasury.account=
taxation.wealth-decay.enabled=false
taxation.wealth-decay.rate=0.001
taxation.wealth-decay.threshold=1000000

# Audit and recovery
audit.enabled=true
audit.retention-days=90
recovery.snapshot.retention=28
recovery.snapshot.auto-enabled=false
recovery.snapshot.auto-interval-hours=6

# Automation
automation.enabled=false
automation.anti-inflation.enabled=false
automation.anti-inflation.threshold=15.0
automation.wealth-cap.enabled=false
automation.wealth-cap.amount=10000000
automation.auto-freeze.enabled=false
automation.emergency-lockdown.enabled=false

# Limits (premium)
limits.transfer.daily-max=-1
limits.transfer.min=0
limits.transfer.max=-1
limits.auction.daily-max=-1

# Discord (premium)
discord.enabled=false
discord.webhook.default=
discord.alert-threshold.intervention=100000
```

Validation on load: tax and decay rates are clamped to `0.0–1.0`, retention values to their documented minimums, webhook URLs must be HTTPS Discord webhooks (otherwise they are discarded with a warning), and `discord.enabled` is force-disabled when no valid webhook remains.

---

## Compatibility

| Component | Requirement | Notes |
| --- | --- | --- |
| Minecraft | 26.1.2 | Mojang Official Mappings |
| Loader | Fabric 0.19.4+ | Server-side only |
| Fabric API | 0.155.2+26.1.2 | Required |
| Java | 25 | Required |
| Solidus Core | 2.x (recommended) | Optional at boot; required for balance operations |
| Client | Any (vanilla or modded) | No client installation needed |
| Database | SQLite (bundled) | WAL journaling; dedicated single-thread workers |
| Side | Server only | Zero client-side dependencies |

---

## Architecture

```
com.solidus.governance/
├── SolidusGovernanceMod.java   — Entry point, lifecycle wiring, tick scheduler
├── GovernanceConfig.java       — Validated properties, normalized on load
├── engine/
│   └── GovernanceEngine.java   — Central coordinator, 60s pulse + hourly pulse
├── audit/
│   ├── AuditDatabase.java      — governance.db: audit_log, freezes, rollback chain
│   └── AuditLogger.java        — Typed audit entry builders per subsystem
├── intervention/
│   ├── InterventionManager.java— Balance ops, suspicious accounts, trading lock
│   └── AccountFreezer.java     — Freeze cache, expiry sweeps
├── limits/
│   ├── TransactionLimits.java  — Check/record API, daily usage map
│   └── LimitsDatabase.java     — limits.db: per-player daily usage
├── taxation/
│   ├── TaxEngine.java          — Rates, progressive brackets, wealth decay
│   └── TreasuryManager.java    — Treasury balance, burn, redistribution
├── recovery/
│   ├── SnapshotManager.java    — Sanitized JSON snapshots, retention, auto-interval
│   └── RollbackEngine.java     — Deterministic restore, dry-run, timeline
├── automation/
│   └── GovernanceAutomator.java— Anti-inflation, wealth caps, lockdown state
├── events/
│   ├── EventManager.java       — Event lifecycle, apply/revert config
│   ├── EventDatabase.java      — events.db: event persistence
│   └── EconomyEvent.java       — Event model, duration math
├── policy/
│   ├── PolicyManager.java      — Save/load/preview with autosave
│   ├── PolicyDatabase.java     — policies.db: stored presets
│   └── EconomyPolicy.java      — Key filtering, autosave expiry
├── rules/
│   ├── RuleEngine.java         — Evaluation pulse, action executor
│   ├── RuleDatabase.java       — rules.db: JSON-serialized rules
│   └── AutomationRule.java     — Conditions, actions, cooldowns
├── simulation/
│   ├── SimulationEngine.java   — Adaptive sampling, TPS throttle, insights
│   └── SimulationState.java    — Immutable status snapshot
├── discord/
│   ├── WebhookManager.java     — Category routing, daily summary
│   ├── DiscordWebhook.java     — Embed delivery with retry backoff
│   └── WebhookRateLimiter.java — 5s rate limit, bounded queue
├── license/
│   └── LicenseVerifier.java    — SA1 HMAC keys, server fingerprint
├── profile/
│   ├── ProfileGenerator.java   — Balance, rank, weekly stats, flags
│   └── PlayerProfile.java      — Profile model and formatters
├── integration/
│   ├── SolidusIntegration.java — Reflection bridge to Solidus Core
│   └── CoreHookBridge.java — Registers the 1.2.0 enforcement hook with Core 2.1.0+ (vetoes + taxes)
└── commands/
    └── GovernanceCommand.java  — /governance command tree
```

### Key Design Decisions

1. **Audit-first mutations** — every balance change, freeze, tax, and config change is written to the audit database with before/after values before anything else can act on the result. The audit trail is the product: queries, timelines, rollbacks, and Discord alerts are all projections of it.

2. **Deterministic recovery** — player and timeframe rollbacks restore the *earliest affected action's* before-state with exactly one balance write per player. Concurrent unordered `setBalance` races are a bug class, not a strategy. Recovery operations are tagged `RECOVERY` and excluded from future rollbacks, so recovery can never loop.

3. **Fail-closed balance operations** — every mutation goes through the Core API bridge and is confirmed by a non-negative resulting balance. If the bridge cannot confirm, the operation reports failure and is not recorded as success in the audit log.

4. **Fail-safe persistence** — all databases (audit, limits, events, policies, rules) run on dedicated single-thread worker daemons with WAL journaling; shutdown drains pending writes before closing. Freeze state and trading locks live in SQLite, so a restart never silently lifts a moderator's decision.

5. **Validated configuration surface** — tax rates, brackets, event modifiers, snapshot names, and webhook URLs are validated at their entry points, not just at load time. An admin typo sets nothing; a path traversal writes nothing.

6. **One evaluation pulse** — automator, rule engine, freeze expirations, and event expirations all run from the same 60-second tick, and heavy work (balance fetches, tax math) runs off-thread. The server tick handler itself is constant-time.

---

## FAQ

### Does this require Solidus Core?

**No — but it is limited without it.** Governance boots in standalone mode when Core is absent, but every balance operation depends on Core's API bridge. With Core installed, integration activates automatically through reflection.

### Does this require client mods?

**No.** Governance is entirely server-side. All output uses vanilla chat components.

### Is the audit trail really immutable?

Governance never rewrites audit rows — corrections happen as *new* audited entries, and the rollback chain table links recoveries to the entries that caused them. The only deletion is the retention cleanup of rows older than `audit.retention-days`.

### Can rollback make things worse?

Every rollback can be previewed with `dryrun` first, shows the exact before/after and the delta against the live balance, restores the earliest affected state deterministically, and is itself audited. Recovery entries are excluded from future rollbacks — loops are structurally impossible.

### Which features need a license key?

Transaction limits, Discord webhooks, economy events, policies, conditional rules, and agent simulation. Audit trail, interventions, freezes, snapshots, rollback, taxation, wealth decay, automation, and lockdown are free and always available.

### Does freezing an account block Core's `/pay`?

**Yes** (Governance 1.2.0 with Core 2.1.0+). Governance registers a transaction hook with Core at server start; Core consults it before every `/pay`, auction listing/purchase, and shop transaction, so frozen accounts, the emergency trading lock, and daily limits are enforced at the source — a denial aborts the transaction before any balance changes, with the reason shown to the player. Taxes on transfers, auction sales, and shop purchases are collected automatically after a transaction settles. On Core versions without the hook API, Governance falls back to standalone mode: freezes still apply to Governance-side operations, and limits remain tracked and logged but not enforced inside Core.

### Where is my data stored?

Everything lives in `config/solidus-governance/`: `governance.db`, `limits.db`, `events.db`, `policies.db`, `rules.db` (all WAL-mode SQLite), `governance.properties`, `license.key` (you provide), and `snapshots/`.

### What happens if the server crashes mid-operation?

All writes go through WAL-journaled SQLite on single-thread workers — committed audit entries and balance mutations survive a hard crash. Balance operations that could not be confirmed by Core are treated as failed, never half-recorded.

### How much does it affect server performance?

Practically nothing on the tick thread: the tick handler does constant-time pulse checks, all SQLite I/O runs on dedicated workers, and economy fetches happen off-thread. The simulation engine additionally self-throttles and pauses below 10 TPS.

---

## Download

| Platform | Link |
| --- | --- |
| GitHub Releases | [Latest Release](https://github.com/mohd-gs/Solidus-Governance/releases) |
| Modrinth | [MOHD_Gs on Modrinth](https://modrinth.com/user/MOHD_Gs) |

---

## Contributing

Contributions are welcome.

* Report issues via [GitHub Issues](https://github.com/mohd-gs/Solidus-Governance/issues)
* Suggest features or improvements
* Submit pull requests

See [SECURITY.md](SECURITY.md) for the security policy and secrets handling before submitting any configuration or log excerpts.

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details. Core governance features are 100% free; premium features (limits, Discord, events, policies, rules, simulation) require a license key.

---

## Keywords

`minecraft governance mod` · `minecraft economy admin` · `minecraft fabric mod` · `minecraft audit log` · `minecraft economy rollback` · `minecraft taxation plugin` · `minecraft account freeze` · `server-side minecraft mod` · `minecraft economy recovery` · `minecraft transaction limits` · `solidus governance` · `minecraft economy moderation`

---

Built by [MOHD_Gs](https://github.com/mohd-gs) · [Email](mailto:mohdmxmxm@gmail.com) · Discord: **mohd_gs** · Part of the [Solidus Economy Ecosystem](https://github.com/mohd-gs)
