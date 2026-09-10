<h1 align="center">Solidus Governance</h1>

<p align="center"><strong>Turn your Minecraft economy from free-for-all into a state with laws</strong><br>
Automatic rules, progressive taxes, transfer limits, full audit trails, and disaster recovery for the Solidus ecosystem.</p>

<p align="center">
  <a href="https://github.com/MOHD-Gs15/Solidus-Governance/actions/workflows/test.yml"><img src="https://github.com/MOHD-Gs15/Solidus-Governance/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
  <a href="https://github.com/MOHD-Gs15/Solidus-Governance/actions/workflows/codeql.yml"><img src="https://github.com/MOHD-Gs15/Solidus-Governance/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <img src="https://img.shields.io/badge/version-2.1.1-blue" alt="Version 2.1.1">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-brightgreen" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT License"></a>
  <a href="https://github.com/MOHD-Gs15"><img src="https://img.shields.io/badge/mod%20by-MOHD--Gs-6f42c1" alt="Mod by MOHD-Gs"></a>
</p>

**Solidus Governance** is a server-side Fabric mod for Minecraft 26.1.2 that gives administrators real control over a server running the [Solidus](https://github.com/MOHD-Gs15/solidus-core) economy mod. Want an automatic tax on the wealthiest players? A daily transfer cap that stops money laundering before it starts? An emergency lockdown you can trigger with one confirmed command? Backups that rewind the economy to seconds ago? Governance does all of that — and its automation agents keep enforcing your rules even while you're offline. Everything is stored locally in its own SQLite databases, every decision is written to an audit log, and the whole mod is configured from one properties file with no code changes. Free and open source under the MIT license.

## Why server owners pick Solidus Governance

- **Rules instead of firefighting** — define a policy once and the engine applies it to every transaction, every day.
- **Recover from disasters** — backups, snapshots, and rollback mean a bad exploit costs you minutes, not your economy.
- **Try before you decide** — simulate a new tax or policy against real server data in dry-run before it goes live.
- **Stay informed** — every lockdown, intervention, tax, and recovery event can be pushed to its own Discord webhook.

## Features

- **Interventions** — freeze a suspect's balance or claw back funds instantly, and lift the freeze when the case is settled.
- **Progressive taxes** — the rate rises with the balance, with a public treasury managing the tax shares.
- **Transaction limits** — per-transfer ceilings, daily caps on transfers and bids: money laundering hits a wall.
- **Rule engine** — build conditions-and-actions rules in game ("if a player does X, notify, warn, and apply a policy"); rules persist and can be toggled.
- **Economic automation** — self-running systems: anti-inflation, wealth cap, automatic freezing of dangerous accounts, and a full server lockdown (with an explicit `confirm` step so it can never fire by accident).
- **Complete audit trail** — a permanent economy event log with browsing and search, exportable to CSV for any spreadsheet.
- **Backup & recovery** — scheduled backups, periodic snapshots, and restore/rollback that return the economy to any prior moment.
- **Decision simulation** — test a policy or tax against the server's real data in dry-run mode and inspect projected impact insights before enforcing anything.
- **Player profiles** — financial history, behavior fingerprint, and suspect tagging for follow-up.
- **Discord alerts** — one webhook per category (lockdown, intervention, taxes, limits, recovery…) with rate adaptation so your channel never floods.
- **License-ready premium gating** — built-in license verification and re-checking structure.
- **Everything switchable** — every module is a key in a single config file; nothing needs a rebuild to disable.

## Commands

Root command `/governance`, with the `/gov` shortcut (GAMEMASTERS-level permission; some branches require full admin). Running the root command with no branch prints the current governance status.

| Branch | What it does |
|--------|--------------|
| `/governance intervention` | Admin interventions: add a financial intervention, freeze and unfreeze accounts |
| `/governance tax` | Progressive taxes and the public treasury |
| `/governance limits` | View and adjust transfer and bid limits |
| `/governance policy` | Economy policies and their prices (`info` per policy) |
| `/governance rules` | Rule engine: create a rule, add conditions and actions, enable, disable, test |
| `/governance automation` | Self-running systems: anti-inflation, wealth cap, full lockdown (`lockdown` asks for confirmation) |
| `/governance audit` | Audit log: browse, search, latest events, CSV export |
| `/governance recovery` | Backups and recovery: `backup`, `snapshot`, `restore`, `rollback` |
| `/governance simulation` | Simulate decisions before enforcing them, with impact insights |
| `/governance event` | Browse recorded economy events (`history`, `timeline`) |
| `/governance profile` | Player profile: financial record, fingerprint, suspect tagging |
| `/governance discord` | Configure alert webhooks |
| `/governance license` | Premium-feature license status |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server (Minecraft 26.1.2).
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the [Solidus Core](https://github.com/MOHD-Gs15/solidus-core) mod into `mods` — Governance is an add-on to Core, not a replacement.
3. Drop `solidus-governance-2.1.1.jar` into `mods`.
4. Start the server — the internal databases (audit log, policies, limits) are created automatically.

> Server-side only: players see it working without installing anything, and it integrates with the Solidus economy through the official bridge without conflicting with the other family mods.

## Quick configuration

After first start you'll find `config/solidus-governance/governance.properties` — plain, documented keys:

| Key | Purpose |
|-----|---------|
| `limits.transfer.max` / `limits.transfer.daily-max` | Ceiling per transfer and per day |
| `limits.auction.daily-max` | Daily bid cap |
| `tax` *(via `/governance tax`)* | Tax brackets and treasury shares |
| `automation.enabled` | Master switch for all self-running systems |
| `automation.wealth-cap.amount` | The wealth level that triggers automation actions |
| `recovery.backup.enabled` + `recovery.backup.auto-interval-hours` | Automatic scheduled backups |
| `recovery.snapshot.auto-enabled` | Periodic economy snapshots |
| `audit.enabled` + `audit.retention-days` | Audit log and its retention window |
| `discord.enabled` + `discord.webhook.*` | Alert webhooks per category |
| `policies.enabled` / `rules.enabled` / `events.enabled` | Master switches per module |

## For advanced users

**Architecture.** A single `GovernanceEngine` coordinates twelve independent modules (audit, rules, policies, taxes, limits, interventions, recovery, simulation, and more). Each internal database is its own SQLite file in the config folder, and every write passes through the `AuditLogger` layer, so every decision leaves a documented trace. Policies are enforced at economy checkpoints emitted by Solidus Core itself — Governance never mutates economy behavior outside the policy interface.

**Recovery.** Snapshot ordering is preserved through sort-safe names, and the rollback tool selects the correct restore point automatically from the requested time frame — covered by dedicated tests (rollback point selection, snapshot name sanitization, backup integrity).

**Automation.** The automation engine periodically reads the economy indicators and decides: warn, intervene, or emergency-lockdown. Lockdown requires an explicit `confirm` and is announced to its own Discord channel.

**Testing.** 13 test classes (81 test methods) run on every push, including the recovery-selection and taxation-progression suites. Build locally with JDK 25: `./gradlew build`.

## Documentation

| Document | Contents |
|----------|----------|
| [docs/BACKUP_RECOVERY.md](docs/BACKUP_RECOVERY.md) | Step-by-step guide to backups, snapshots, restore, and rollback |
| [VERSIONING.md](VERSIONING.md) | Version policy and 2.1.x family compatibility |
| [SECURITY.md](SECURITY.md) | Security reporting policy |
| [PROVENANCE.md](PROVENANCE.md) | Code origin and provenance |

## The Solidus family

| Mod | What it adds | Repository |
|-----|--------------|------------|
| **Solidus Core** | The economy engine itself | [MOHD-Gs15/solidus-core](https://github.com/MOHD-Gs15/solidus-core) |
| **Solidus Analytics** | Monitoring, dashboards, fraud detection | [MOHD-Gs15/solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) |
| **Solidus Governance** (this repo) | Taxes, limits, policies, audits, recovery | [MOHD-Gs15/Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) |
| **Solidus Enforcer** | Bounties, hunter licenses, anti-exploit enforcement | [MOHD-Gs15/Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) |

## License & credits

- **Mod by [MOHD-Gs](https://github.com/MOHD-Gs15)**
- Licensed under the [MIT License](LICENSE) — free to use, modify, and ship with your server.
