# Fixes for the 2026-10-28 review — the small findings

Companion to `docs/reviews/src-review-2026-10-28.md`. That review left nine findings open; this
document records what was changed for findings 4, 5, 7, 8 and the four user-facing problems the
runtime test (`docs/reviews/runtime-test-2026-10-28.md`) turned up in the command output.

**Deliberately not in this pass:** findings 2 (`SessionAccount.login()` replacing `User`,
`UserApiService`, `ProfileKeyPairManager` and `PlayerSocialManager` from a `ForkJoinPool` thread)
and 9 (the "small things" list is documentation, and is done in the README and wiki rather than in
code). Finding 2 is a P1 and is *not* a small change: it needs the token work split from the client
state swap, which means touching `NullTraceConfigScreen`, `AddAccountScreen`, `AccountManager.logout`
and `SessionAccount` together, and it can only be proven against a real Microsoft account. Doing it
half-way is worse than leaving it written down. It stays the top item in `docs/ROADMAP.md`.

## Summary

| # | Finding | Change | Evidence |
| --- | --- | --- | --- |
| 4 | Pack-cache isolation can disappear silently (P2) | `PackCacheIsolation` liveness probe + WARN | `PackCacheIsolation.java`, `DownloadQueueMixin`, `PackStripHandler.onPackPush` |
| 5 | `serverPackTranslations` uncapped (P2) | 200 000-entry cap, drop-not-evict, one warning | `ModRegistry.recordServerPackTranslation` |
| 7 | Local guard fails open on a trailing root dot (P3) | strip trailing dots before resolution + doc table | `LocalAddressUtil.isBlockedServerPackTarget`, 4 new assertions |
| 8 | Proxy probe accepts `2000`, volunteers its version (P3) | exact status parse, no `User-Agent` | `ProxyTester.isConnectAccepted`, 11 new harness checks |
| — | `Client brand: EXPOSED` in singleplayer | `not sent (singleplayer)`, not counted | `NullTraceCommand.showAudit` |
| — | Help lists commands that are gated | `(require Debug Command)` suffix, `profile` added, alignment | `NullTraceCommand.showHelp` |
| — | Audit tail repeats one decision | fold runs, `(xN)` | `RepeatedDecisions` + 6 unit tests |
| — | `Server: local:e` in singleplayer | `(integrated server)` suffix | `NullTraceCommand.showAudit` |

## 4 — Proving the pack-cache isolation is actually running

`DownloadQueueMixin` must stay `require = 0`: it targets a lambda inside `DownloadQueue`, and a
version bump that renames it should degrade one feature, not crash the client. The problem was that
degrading produced no signal at all — the option stayed on, the README kept promising per-account
isolation, and the shared cache path quietly came back.

`PackCacheIsolation` closes that loop with the cheapest mechanism that cannot itself be silently
skipped:

* the mixin calls `recordIsolation()` every time it actually rewrites a path — a counter, not a log
  line, so a busy session does not spam;
* `PackStripHandler.onPackPush` (before its own feature early-returns, so it fires regardless of
  how stripping is configured) calls `noteServerPackPush()`, which schedules exactly one check
  30 s later;
* the check reads the counter. Zero hits with the option on means the injection never bound: one
  `LOGGER.warn` and one `PrivacyAction.WARN` decision on `RESOURCE_PACK`, which is what
  `/nulltrace audit` already knows how to display. Same shape as the `RegistrationPayload`
  reflection fix from the previous review.

The 30 s grace period is not arbitrary: the push, the download and a proxied connection can be
spread out, and checking synchronously would report a false negative on every session.

## 5 — Bounding server-pack lang retention

`recordServerPackTranslation` wrote straight into a `ConcurrentHashMap` with no ceiling while the
size is chosen by the server (it ships `assets/minecraft/lang/*.json`). The map is also a duplicate
of vanilla's — vanilla keeps its own copy — so a 1 M-entry file cost roughly 60 MB for the session,
for a pack the user may refuse seconds later.

The cap is 200 000 entries (~12 MB), far above any real pack: vanilla `en_us.json` is ~7 400 keys
and the largest translation packs are in the tens of thousands. Past the cap, **new keys are
dropped, not evicted**. Eviction would have been the wrong choice — a pack could then push its own
entries out again and re-open the fallback path that `TranslatableContentsMixin` closes. Dropping
keeps every decision stable: a recorded key is still answerable at block time, an unrecorded one is
treated exactly like a key the pack never sent. One warning per session, reset on disconnect so the
next occurrence is news again.

## 7 — Trailing root dot

The review's harness found `isBlockedServerPackTarget("203.0.113.9", "127.0.0.1.")` returning
`false`. It was fail-open *by design* ("an unresolvable host cannot be connected to, so there is
nothing to block"), and that reasoning holds for the JDK connect path — `InetAddress` refuses the
trailing dot, so the connect fails too. But it made the predicate's answer depend on the resolver's
spelling rules rather than on the address, which is one refactor away from an SSRF bypass.

Trailing dots are now stripped before resolution (`stripRootDot`), applied after bracket stripping
so `[::1].` is handled as well. Pinned by four assertions covering root-dot loopback, root-dot IPv6
loopback, root-dot cloud-metadata link-local, and a public host that must stay allowed.

The CGNAT/`198.18/15` asymmetry between `isNeverServerReachable` and `isLocalAddress` is unchanged —
it is deliberate — but it is now in the `isBlockedServerPackTarget` doc comment, spelled out, so the
next reader does not "fix" it.

## 8 — Proxy probe

```java
// before
boolean ok = statusLine.contains(" 200");       // HTTP/1.1 2000 … passes
+ "User-Agent: NullTrace/" + NullTrace.getVersion() + "\r\n"
```

`isConnectAccepted` parses the `HTTP/x.y` prefix and requires the code to be exactly `200`, followed
by end-of-line or the reason-phrase space. The probe also no longer sends a `User-Agent`: a
reachability probe has no reason to identify the client, and handing a third-party proxy
`NullTrace/2.2.0` is a poor look for a privacy tool. 11 new checks in `tests/proxy` — 10 on the
parser (`200`, bare `200`, `HTTP/1.0 200`, `2000`, `2001`, `1200`, a `200` inside a `407` reason
phrase, a non-HTTP banner, `null`) plus one live endpoint that answers `HTTP/1.1 2000 Not A Status`
and must be reported as failing. Harness total: 54 → 65.

## Runtime UX (from the in-client test)

**Brand in singleplayer.** `shouldSpoofBrand()` answers "would we block the brand", and the audit
printed that as if it were "is the brand leaking". On an integrated server nothing is sent, so
`EXPOSED` was wrong twice: it named a leak that cannot happen *and* added 1 to
`Remaining exposure` in the one session where the number should be at its lowest. The line now
reads `Client brand: not sent (singleplayer)` in dim text and is not counted; the server line reads
`Server: local:e (integrated server)` because `local:e` is a socket address, not a joinable host.

**Help vs. gate.** `info` and `channels` carry `.requires(isDebugCommand)`; Brigadier removes a
gated node from the tree for a source that does not satisfy it, so with Debug Command off the help
advertised two commands that produced no output and no error. The help now suffixes them with a
dim `(require Debug Command)` — and only when they are actually hidden — and lists `profile`, which
was always available and never mentioned. The four lines are column-aligned; previously the audit
line sat one space off the other two (`/nulltrace audit - ` vs `/nulltrace channels    - `).

**Repeated decisions.** One `CLIENT_BRAND` decision is recorded per evaluation, and the brand is
evaluated several times per connection, so the eight-line tail was usually three to eight copies of
the same entry. Unbroken runs now fold into one line with a count. The fold is in the common source
set (`RepeatedDecisions`, no Minecraft types) and unit-tested, including the case that matters for
correctness: two decisions with the same surface and subject but different reasons must not merge.

## Verification

| Check | Result |
| --- | --- |
| `./gradlew :1.21.11:test` (JDK 21.0.13) | 113 tests, 0 failures |
| `./gradlew :26.3:test` (JDK 25.0.5) | 113 tests, 0 failures |
| `compileClientJava` on all four targets | 1.21.11, 26.1, 26.2, 26.3 — clean (3 pre-existing mixin warnings unchanged) |
| `tests/proxy` harness | 65/65 (was 54) |
| `tests/vault` harness | 26/26 |
| `tools/ci/check_sources.py` | 14/14 |
| `tools/ci/check_metadata.py` | 21 checks, 0 failures |
| `tools/ci/lint_gitlab_ci.py --scenario all` | clean |
| `tools/ci/selftest.py` | 33/33 |
| `unittest discover -s tests` | OK (2 adversarial-server fixtures) |

Not verified: the runtime behaviour of the new audit lines and help text in a live client. They are
pure display changes over data the same command already computes, and the client was not re-launched
for this pass — the next runtime test should re-capture `docs/screenshots.html` from a session with
Debug Command **off** to show the gated help suffix.
