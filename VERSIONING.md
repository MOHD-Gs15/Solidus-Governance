# Versioning

The four Solidus ecosystem mods — **Core**, **Analytics**, **Governance**, and **Enforcer** —
share one **family version**. The family version is the integration contract: it tells a
server owner, at a glance, which releases are built and tested to work together.

| Bump | Meaning | Can you update one mod alone? |
|------|---------|-------------------------------|
| **Patch** `2.1.0 → 2.1.1` | Bug fixes and safe improvements. | Yes — any `2.1.x` works with any other `2.1.y`. |
| **Minor** `2.1.x → 2.2.0` | Breaking change: API, hook signature, config schema, or database layout. | No — the other mods must move to the `2.2` family in lockstep. |
| **Major** `2.x → 3.0.0` | Architectural reset of the ecosystem contract. | No — full coordinated release. |

Current family: **2.3.x** — the update-resilience / family-contract era. Companions compile
against the `solidus-api` contract jar (a Minecraft-free artifact nested inside Core) and
declare a loader-enforced `"solidus": ">=2.3.2 <3.0.0"` floor. Governance sits at **2.3.2**:
it migrated its integration layer and (in the 2.3.2 integration audit) its simulation's
active-account query onto the contract — the family's last reflective reach-in into Core
internals is gone.

Each mod's `fabric.mod.json` `depends"solidus"` entry declares the **minimum Core
version** it was integration-tested against (e.g. `">=2.3.2 <3.0.0"`); Fabric's loader
refuses incompatible combinations instead of degrading silently.

## Where the number lives

Each mod's version has exactly one source of truth — a single line in `gradle.properties`:

```properties
mod_version = 2.1.0
```

`fabric.mod.json` picks it up through the `${version}` expansion at build time — never
edit it by hand. The jar filename, the `Implementation-Version` manifest attribute, and
the version the Cloud agent reports in `health.meta` all flow from the same line.

## Versioned separately

Two cloud components keep their own version families, on purpose — they release on
their own cadence and must stay compatible across mod patch releases:

| Component | Version | Contract |
|-----------|---------|----------|
| Cloud relay (`cloud-relay/` in Solidus-analytics, Node.js) | `0.x` | see `cloud-relay/README.md` |
| Cloud wire protocol | `1.x` | see `docs/cloud/PROTOCOL.md` §14 (Versioning & Compatibility) |
