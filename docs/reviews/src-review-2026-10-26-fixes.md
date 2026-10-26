# Source review — fixes applied, 2026-10-26

Follow-up to [`src-review-2026-10-26.md`](src-review-2026-10-26.md). Every finding in that
review is either fixed or explicitly deferred, and everything here was compiled and run —
unlike the review, which was a static read.

## How it was verified

| Step | Command | Result |
| --- | --- | --- |
| JDKs | Temurin 21.0.13 + 25.0.5 | installed, both used |
| Build, all four targets | `./gradlew :1.21.11:build :26.1:build :26.2:build :26.3:build` | **4/4 BUILD SUCCESSFUL** |
| Unit tests, all four targets | `./gradlew <target>:test` | **392 tests, 0 failures** (98 per target) |
| Jar audit, built artifacts | `tools/ci/inspect_jar.py --version 2.1.1 --strict` | **18/18 checks × 4 jars** |
| Checksums | `tools/ci/gen_sums.py` then `sha256sum -c SHA256SUMS` | 4/4 **OK** from a user-style directory |
| Token vault | `tests/vault` harness (new) | **26/26 checks** |
| Proxy probing | `tests/proxy` harness (existing) | **54/54 checks** |
| CI definition | `tools/ci/lint_gitlab_ci.py` | 5 checks, tag/main/branch/MR all simulate |

The one thing a build cannot prove is runtime behaviour inside Minecraft: there is no client
here to connect to a server. Everything testable without a game client — pure policy, the
vault, the HTTP plumbing, jar packaging — is covered above.

## A bug the harness found that the review did not

While writing the vault test for the AAD change, the round-trip failed for the *original* code.
`AccountTokenVault` encoded with `Base64.getUrlEncoder()` and decoded with `Base64.getDecoder()`.
The URL-safe alphabet uses `-` and `_` where the standard one uses `+` and `/`, and the standard
decoder throws on both — 98.5 % of 1 000 random 100-byte ciphertexts contain one of them.

The same harness run against `git 94b772b`:

```
original code: 0/20 round-trips OK, 20/20 lost
```

So the encrypted-at-rest account storage added in the unreleased 2.1.1 never round-tripped:
every restart marked saved accounts invalid and asked for a fresh Microsoft login. It would have
shipped with the next tag. Fixed with a decoder that accepts both alphabets, plus a regression
check that pins an envelope containing a URL-safe character.

This is the argument for the harness existing at all: the class is 110 lines of security-relevant
code that had no test, and its happy path was broken.

## Status of each finding

| # | Finding | Status |
| --- | --- | --- |
| 1 | Local-address blocking off on LAN servers | **Fixed** — `isNeverServerReachable` / `isBlockedServerPackTarget`, 4 guard sites, 4 new tests |
| 2 | Malformed push burns the ASK prompt | **Fixed** — URL validated before the one-shot CAS; flag also reset on pop-all |
| 3 | Every toggle rebuilds the closure and rewrites the file | **Fixed** — `save()` marks pending, `flush()` per tick, closure rebuilt only on whitelist change, `saveNow()` on shutdown |
| 4 | Two divergent outbound filters | **Fixed** — one `ChannelDecision` (MC-free, 9 tests) used by both paths; install failure now warns |
| 5 | `Files.walk` leak | **Fixed** — try-with-resources |
| 6 | Proxy change never reaches the HTTP clients | **Fixed** — `HttpClients` owns the client + `reset()`; account requests use it too |
| 7 | `RegistrationPayload` reflection, silent drop | **Fixed** — constructor resolved once and cached; on failure the original payload is forwarded and the fallback is recorded as a `WARN` in the audit ledger |
| 8 | Vault has no AAD | **Fixed** — `v2:` envelopes bind the account identity; `v1:` still readable |
| 9 | `save()` unsynchronised | **Fixed** — writes go through one synchronized method |
| 10 | Smaller items | **Fixed** except as noted: unreachable branch removed, `currentExposure` deleted, `PrivacyPolicy` comment corrected, `onPop` resets the overlay flag, `HexFormat` for digests, mixin `AtomicBoolean` un-static'd |

Deferred, with reasons:

- **Message-signing chain semantics** (`sendChat` still advances the local chain with a `null`
  signature). Changing this alters wire behaviour against 1.19.1–1.19.2 servers that still
  enforce signed chat; it needs a decision and a test against a real server, not a drive-by fix.
- **CurseForge vs GitLab byte identity** for the integrity checker — needs the two published
  artifacts compared, which is a release-time check, not a code change.

## Files touched

```
src/main/java/net/nulltrace/mod/privacy/ChannelDecision.java        new, pure policy
src/main/java/net/nulltrace/mod/privacy/ConnectionContext.java      currentExposure removed
src/main/java/net/nulltrace/mod/privacy/PrivacyPolicy.java          comment corrected
src/main/java/net/nulltrace/mod/util/LocalAddressUtil.java          target policy + tests
src/client/java/net/nulltrace/mod/accounts/AccountTokenVault.java   AAD + base64 fix
src/client/java/net/nulltrace/mod/accounts/SessionAccount.java      binding + migration
src/client/java/net/nulltrace/mod/config/NullTraceConfig.java       deferred save, sync write
src/client/java/net/nulltrace/mod/config/JarIntegrityChecker.java   HexFormat, shared client
src/client/java/net/nulltrace/mod/config/UpdateChecker.java         shared client
src/client/java/net/nulltrace/mod/mixin/client/ClientConnectionMixin.java  one policy, cached ctor
src/client/java/net/nulltrace/mod/mixin/client/HttpUtilMixin.java   guard sites
src/client/java/net/nulltrace/mod/protection/PackStripHandler.java  prompt ordering
src/client/java/net/nulltrace/mod/protection/ResourcePackGuard.java stream closed
src/client/java/net/nulltrace/mod/util/HttpClients.java             cache + reset()
src/client/java/net/nulltrace/mod/NullTraceClient.java              tick flush + shutdown save
src/test/java/net/nulltrace/mod/privacy/ChannelDecisionTest.java    new, 9 tests
src/test/java/net/nulltrace/mod/util/LocalAddressUtilTest.java      4 new tests
tests/vault/**                                                      new harness + stubs + bridge
.gitlab-ci.yml                                                      vault_harness job
CHANGELOG.md                                                        entries for all of the above
```
