# Development

## Layout

| Path | What it is |
| --- | --- |
| `src/main/java` | Common source set: privacy policy, audit ledger, address guard, profiles — **no Minecraft types**, so it is unit-testable |
| `src/client/java` | Client source set: mixins, config GUI, commands, vault, proxy, pack pipeline |
| `src/test/java` | JUnit tests for the common layer |
| `tests/` | Standalone JDK-only harnesses plus Python CI checks and fixtures |
| `tools/ci/` | Preflight checks, jar audit, release verification |
| `docs/` | Reviews, design notes, devops runbook, screenshots, wiki sources |
| `versions/` | Stonecutter-generated per-version projects (do not edit by hand) |

Multi-version support is Stonecutter. One source tree, four targets — `1.21.11`, `26.1`, `26.2`,
`26.3` — with version-specific code behind `//? if >=x {` comments.

## Build

```bash
# JDK 21 for 1.21.11, JDK 25 for the 26.x targets
./gradlew :1.21.11:build          # single target
./gradlew buildAll                # every supported target
```

Artifacts land in `versions/<target>/build/libs/`.

## Test

```bash
./gradlew :1.21.11:test :26.3:test       # 113 tests per target
python3 -m unittest discover -s tests    # adversarial server fixtures
python3 tools/ci/check_sources.py        # entrypoints, mixin wiring, credential scan
python3 tools/ci/check_metadata.py       # release metadata, CHANGELOG gate
python3 tools/ci/lint_gitlab_ci.py --scenario all
python3 tools/ci/selftest.py             # the checks check themselves
```

**Standalone harnesses** (JDK only, no Minecraft, no Gradle — these run in CI too):

| Harness | Covers | Current size |
| --- | --- | --- |
| `tests/proxy` | endpoint parsing, list semantics, config round-trip, live probes against fake HTTP CONNECT / SOCKS5 endpoints, CONNECT status-line parsing | 65 checks |
| `tests/vault` | base64 round-trip for both alphabets, AES-GCM AAD binding, tamper rejection, v1 migration, corrupt-key regeneration | 26 checks |

```bash
# proxy harness
javac -encoding UTF-8 -d /tmp/proxy-harness -cp gson.jar \
  tests/proxy/stub/net/nulltrace/mod/NullTrace.java \
  src/client/java/net/nulltrace/mod/lang/NullTraceStrings.java \
  src/client/java/net/nulltrace/mod/proxy/*.java \
  tests/proxy/FakeProxyServers.java tests/proxy/ProxyProbeHarness.java
java -cp gson.jar:/tmp/proxy-harness ProxyProbeHarness

# vault harness
javac -encoding UTF-8 -d /tmp/vault-harness \
  tests/vault/stub/net/fabricmc/loader/api/FabricLoader.java \
  tests/vault/stub/net/nulltrace/mod/NullTrace.java \
  src/client/java/net/nulltrace/mod/accounts/AccountTokenVault.java \
  tests/vault/bridge/net/nulltrace/mod/accounts/AccountTokenVaultAccess.java \
  tests/vault/VaultHarness.java
java -cp /tmp/vault-harness VaultHarness
```

## CI

`.gitlab-ci.yml`, described operator-side in `docs/devops/runbook.md`:

| Stage | Jobs |
| --- | --- |
| validate | `preflight` — pure Python, runs in seconds, nothing else starts until it passes |
| build | `build_matrix` (4 targets on `main`/tags), `build_smoke` (one target on branches) |
| test | `policy_tests_matrix` (1.21.11 + 26.3), `proxy_probe_harness`, `vault_harness` |
| audit | `jar_audit` — jar contents, checksums, release manifest self-consistency |
| release | `publish` (tags only, gated on builds + tests + audit), `pages` (manual) |

The release job refuses to publish if any artifact's `fabric.mod.json` version differs from the tag,
and the published jar is the artifact that was tested, not a rebuild.

## Review documents

Each review is a file with `file:line` evidence, and fixes get their own companion:

| Document | Content |
| --- | --- |
| `docs/reviews/src-review-2026-10-26.md` | First full source review |
| `docs/reviews/src-review-2026-10-26-fixes.md` | What was changed after it |
| `docs/reviews/src-review-2026-10-28.md` | Second review: 2 P1, 2 P2, 5 P3, plus harness results |
| `docs/reviews/src-review-2026-10-28-fixes.md` | This pass: what was fixed, what was deliberately left |
| `docs/reviews/runtime-test-2026-10-28.md` | First in-client runtime test: verified behaviours and gaps |
| `docs/reviews/mod-review-2026-10-17.md` | Mod-content review |

**Open by design:** `SessionAccount.login()` still replaces `User`, `UserApiService`,
`ProfileKeyPairManager` and `PlayerSocialManager` from a `ForkJoinPool` thread (finding 2 of the
2026-10-28 review). The token work belongs off-thread; the client-state swap does not. It needs the
screens, `AccountManager` and `SessionAccount` changed together and can only be proven against a real
Microsoft account, so it is written up rather than half-applied.

## Conventions

- Commit attribution: `0xff <0xffshell@duck.com>`.
- Privacy decisions go through `PrivacyPolicy` / `PrivacyAudit` — never log a decision ad hoc.
- Anything in `src/main` must stay free of Minecraft types; that is what makes it testable.
- A protection that can silently stop working needs a liveness signal (see `PackCacheIsolation`).
