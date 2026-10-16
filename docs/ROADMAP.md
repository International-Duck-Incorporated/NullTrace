# NullTrace Roadmap

NullTrace is a client-side Minecraft privacy layer focused on client identity protection, fingerprint detection, tracking defenses, resource-pack protection, and user-visible connection warnings.

This document describes the current GitLab repository, not the historical GitHub Actions plan.

## Current release targets

- Minecraft 1.21.11
- Minecraft 26.1
- Minecraft 26.2
- Minecraft 26.3
- Java 21 for obfuscated targets and Java 25 for 26.x targets
- Stonecutter multi-version builds
- GitLab CI and GitLab Releases

## Implemented foundation

- NullTrace package/resource rebrand and GPLv3 attribution
- Client brand normalization and mod-channel filtering
- Known-pack, translation, keybind, resource-pack, shader, telemetry, cookie, and transfer protections
- Per-hop local/private-address checks for resource-pack redirects
- Safe redirect-limit handling: no raw socket is opened for the rejected next hop
- Configurable client-information normalization
- Proxy management on the **Advanced** tab: `Direct` / `Launcher` / `Selected` modes with a saved endpoint list and a reachability probe. `Launcher` uses `Minecraft#getProxy`, `Direct` uses `Proxy.NO_PROXY`. Scope is honest — it covers NullTrace's own HTTP traffic, not the game-server connection
- Encrypted-at-rest account tokens using AES-GCM and owner-only key-file permissions where supported
- Token-redacting account export
- Central `ConnectionContext`, `PrivacyPolicy`, `PrivacyDecision`, `AuditLedger`, and server-profile model
- `/nulltrace audit` and `/nulltrace profile`
- Pre-connection privacy warning that rows each surface as shown/hidden, with **Connect** / **Protect & connect**, a details panel, and a per-server mute
- GitLab CI build, JUnit policy tests across two Minecraft targets, proxy harness, adversarial fixture, JAR inspection (including a refmap-vs-jar consistency guard), and checksum stages
- No offline-account path: only authenticated Microsoft sessions can be switched in

## Privacy architecture

All new protections should feed the following model rather than inventing another local counter:

```text
ConnectionContext
├── server address
├── protocol version
├── installed mods
├── exposed channels
├── exposed packs
├── exposed translations
├── resource-pack requests
├── detected probes
└── current exposure
          ↓
     PrivacyPolicy
          ↓
 ALLOW / BLOCK / WARN / LOG
          ↓
      AuditLedger
```

Every decision should include a surface, subject, action, and human-readable reason.

## Next engineering work

### Adversarial integration server

The repository contains a deterministic probe fixture under `tests/`. The next step is running it against a real headless Minecraft client in CI. The required probe matrix is:

- Brand
- Custom channel
- Known pack
- Translation
- Keybind
- Resource pack
- Redirect/SSRF
- Component
- Cookie/transfer
- Combined fingerprint

The regression contract is:

```text
Without NullTrace -> probe succeeds
With NullTrace    -> probe is blocked, normalized, or explicitly reported
```

### Policy and profiles

- Add server-profile editing by hostname before first connection.
- Allow custom per-surface overrides and selected channel exceptions.
- Persist connection audit history and offer a redacted JSON export.
- Show profile provenance and the rule that caused every decision.

### Supply-chain hardening

- Pin and verify dependency metadata.
- Add license allow/deny checks for generated JARs.
- Publish signed checksums when signing credentials are available.
- Compare generated JAR contents against an allowlisted package/resource manifest.
- Run the adversarial Minecraft integration job on protected GitLab runners.

### Compatibility

- Keep every mixin version-gated and compile all four targets on every change.
- Prefer capability-specific profile overrides to all-or-nothing compatibility exceptions.
- Add a regression test for every newly discovered fingerprint vector.

## Release pipeline

```text
build matrix
   ↓
unit / policy tests
   ↓
adversarial fixture tests
   ↓
dependency + license checks
   ↓
JAR inspection
   ↓
SHA-256 checksums
   ↓
GitLab Release assets
```

Stable releases are created from `v*` tags. Main-branch artifacts remain CI artifacts until the project’s GitLab release policy promotes them.
