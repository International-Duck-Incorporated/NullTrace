# NullTrace source review — 2026-10-28

Scope: `main` @ `95cca89` ("release: 2.2.0"), 98 Java files / ~18 500 lines, plus
`.gitlab-ci.yml`, `tools/ci/` and `tests/`. Read: the privacy policy layer, the outbound
payload filter, the resource-pack pipeline (HTTP guard, strip, cache isolation), the
translation/keybind probe protection, the account vault + account switching, the proxy layer,
`JarIntegrityChecker`, `UpdateChecker`, and the CI release path.

Method: static read with a grep-driven hunt for the usual suspects (threading, unbounded caches,
resource handles, check-then-use, swallowed exceptions, fail-open paths), cross-checked against
the README's claims and against your own two review documents
(`docs/reviews/src-review-2026-10-26.md` and `…-fixes.md`). Where a claim was testable without a
Minecraft client, I ran it — see "What I could and could not verify".

**Build and tests:** run afterwards, on Temurin 21.0.13 + 25.0.5, on the merged `main`
(`a10711e`). All four targets compile and test green — 424 unit tests (106 × 4), 18/18 jar-audit
checks per jar, checksums and release consistency verified, plus the `tests/vault` (26/26) and
`tests/proxy` (54/54) harnesses. Numbers in §"Build and test verification" at the end.

**Still not done:** a runtime test inside Minecraft. No client ran on any target, so mixin binding,
account switching, pack strip and chat signing are compile-verified only. Everything below is
either a static read with `file:line` evidence or a result from a harness I ran against the real
source files.

---

## Verdict

**Better than most mods, and the previous review's P1s are genuinely closed.** The architecture
holds up: policy in a Minecraft-free common source set, immutable `PrivacyPolicy` snapshots,
bounded caches everywhere I looked, atomic config writes, one outbound-filter decision shared by
both injection points, and comments that explain *why* a workaround exists. The CI now proves the
artifacts it publishes are the artifacts it tested.

What's left is a different class of defect from last time: not one broken protection, but
**several silent-failure paths** — places where a privacy feature turns itself off, or keys the
wrong data, without telling the user. For a tool whose entire value proposition is "the decision
is inspectable", silent fallback is the expensive kind of bug.

| # | Finding | Severity | Status |
| --- | --- | --- | --- |
| 1 | Per-server profile key is `hostname/resolvedIP`, so a DNS change silently drops the user's STRICT/MAXIMUM_PRIVACY profile | **P1** | verified by running the parse against real `InetSocketAddress` output |
| 2 | Account switching mutates live Minecraft client state from a ForkJoinPool thread | **P1** | static, `file:line` |
| 3 | `AccountManager.save()` is unsynchronised and every writer targets the same temp path | **P2** | static; found 2 line references |
| 4 | Per-account pack-cache isolation is the only `require = 0` injection — it can vanish without a log line | **P2** | static |
| 5 | `serverPackTranslations` keeps an uncapped copy of everything a hostile pack's lang files contain | **P2** | static |
| 6 | Vault key file is created world-readable for an instant, then `chmod`ed; the Windows fallback is a no-op | **P3** | static |
| 7 | Local-address guard: a private-IP server may still point the client at CGNAT/benchmark ranges; trailing-dot hosts fail open | **P3** | harness run, matrix below |
| 8 | `ProxyTester` matches `" 200"`, so `HTTP/1.1 2000 …` passes; the probe also volunteers `User-Agent: NullTrace/<version>` | **P3** | static |
| 9 | Small things: jar-integrity warning is dismissible and the check is removable by the tamperer; `KeybindContentsMixin` protection is off for LAN hosts; `MeteorMixinCanceller` cancels other mods' mixins | **P3** | static |

Fixes for 1, 3 and 6 are committed on this branch (`fix(account): …`, see §"Fixes on this
branch"). The vault change passes your own `tests/vault` harness, 26/26.

---

## 1. Per-server profile key contains the resolved IP · P1

`ClientPacketListenerMixin.java:113-121` builds the key that per-server privacy profiles are
stored under:

```java
String serverAddress = listener.getConnection().getRemoteAddress().toString();
if (serverAddress.startsWith("/")) serverAddress = serverAddress.substring(1);
int colonIndex = serverAddress.lastIndexOf(':');
if (colonIndex > 0) serverAddress = serverAddress.substring(0, colonIndex);
```

`InetSocketAddress#toString()` is `hostname/resolvedIp:port` when the address was created from a
host name. I ran it:

```
byName      : example.com/172.66.147.243:25565
byIp        : /93.184.215.14:25565
parsed      : 'example.com/172.66.147.243'
```

So the key is `example.com/172.66.147.243`, not `example.com`. Consequences, all silent:

* **The profile stops matching when the server's IP changes.** Any proxy front-end, SRV-record
  target, CDN or hosting migration changes it. `NullTraceConfig.getServerProfile(...)` then misses
  and returns a default `STANDARD` profile — a user who pinned `MAXIMUM_PRIVACY` for that server is
  back on the default set with no alert, no ledger entry and no UI hint.
* **Joining the same server by host name and by IP produces two profiles.** Whichever one the user
  configured, the other path is unprotected.
* The audit ledger and the config UI show `example.com/172.66.147.243` as "the server", which is
  also a small privacy smell in a screenshot-able diagnostic screen.

Fix (on this branch): use `InetSocketAddress#getHostString()`, which returns the
name the client actually connected with and never the resolved address, and keep the string-surgery
path only for non-`InetSocketAddress` fallbacks. IPv6 literals stop acquiring square brackets as a
side effect.

```java
if (address instanceof InetSocketAddress inet) {
    String host = inet.getHostString();
    return host == null ? "" : host;
}
```

Worth considering separately: if you *want* per-IP granularity (two servers behind one host name on
different ports), the key should be `host` plus the port explicitly, not a substring of a debug
`toString()`.

## 2. Account switching mutates live client state off the render thread · P1

`SessionAccount.login()` (`SessionAccount.java:283-401`) does its own token validation, then
replaces five pieces of `Minecraft` state:

```java
accessor.nulltrace$setUser(newUser);                              // :359
accessor.nulltrace$setUserApiService(userApiService);             // :370
accessor.nulltrace$setProfileKeyPairManager(profileKeyPairManager); // :378
accessor.nulltrace$setPlayerSocialManager(socialManager);         // :394
```

Every caller runs it on `ForkJoinPool.commonPool()`:

* `NullTraceConfigScreen.java:632-635` — `CompletableFuture.runAsync(() -> { account.login(); accountManager.setActiveAccountUuid(...); })`
* `AddAccountScreen.java:138-139` — same shape
* `AccountManager.logout()` → `originalAccount.login()`, also called from a `runAsync` at
  `NullTraceConfigScreen.java:619` and `:655`

Only the *UI refresh* hops back to the main thread (`Minecraft.getInstance().execute(...)`); the
state replacement does not. `User`, `UserApiService`, `ProfileKeyPairManager` and
`PlayerSocialManager` are render-thread-owned objects that other threads read every frame — the
social manager has its own executor, the key-pair manager is read by the chat-signing path. Swapping
them from a pool thread is a data race on live client state, and the failure mode is not a clean
exception: it's a stale key pair, chat signatures that fail, a friend list that never loads, or a
`ConcurrentModificationException` somewhere unrelated.

The network work genuinely belongs off-thread; the state swap does not. Suggested shape:

```java
CompletableFuture.runAsync(() -> account.authenticate())        // validate + refresh, no Minecraft access
    .thenAccept(ok -> Minecraft.getInstance().execute(() -> {   // swap on the render thread
        if (ok) account.applyToClient();
    }));
```

This also removes the reason `setActiveAccountUuid`/`save()` run on a pool thread (finding 3).

## 3. `AccountManager.save()` is the unsynchronised writer in a multi-thread design · P2

Your fixes document marks "`save()` unsynchronised" as fixed — and it is, in `NullTraceConfig`
(`flush()`, `saveNow()`, `writeConfig()` are all `synchronized`). The same pattern is still open in
`AccountManager`:

```java
public void save() {                                    // AccountManager.java:397 — no lock
    Path tempFile = ACCOUNTS_PATH.resolveSibling(
            ACCOUNTS_PATH.getFileName() + ".tmp");      // :410 — one fixed name for everyone
    Files.writeString(tempFile, GSON.toJson(json));     // :411 — truncate + write
    Files.move(tempFile, ACCOUNTS_PATH, ATOMIC_MOVE);   // :412
```

Callers on three different threads: render thread (`add` `:94`, `remove` `:113`,
`setActiveAccountUuid` `:147`), the single-thread refresh executor (`refreshAllAccounts` `:310`),
and the fork-join pool from finding 2. Two writers truncating and filling one temp path can
interleave into a document that is neither write; `ATOMIC_MOVE` then publishes it. The recovery path
for a JSON parse failure is "every saved account needs re-authentication" — exactly the outcome the
vault exists to prevent, and unrecoverable if the user no longer has the refresh token.

Fix (on this branch): `synchronized` on `save()`, plus a per-call temp file name so a reader that finds
a stray `.tmp` can tell it apart from a live write. (A `ReentrantLock` would also work; the point is
that the temp name must not be shared.)

## 4. The one injection that can disappear silently is the pack-cache isolation · P2

`nulltrace.client.mixins.json` sets `defaultRequire: 1`, and every injection I checked carries an
explicit `require = 1`. The single exception in the whole client source set is the feature that
isolates the resource-pack cache per account:

```java
@ModifyExpressionValue(
    method = {"lambda$runDownload$0", "method_55485"},   // DownloadQueueMixin.java:34-37
    at = @At(value = "INVOKE", target = "…Path;resolve(Ljava/lang/String;)…"),
    require = 0)
```

It has to be tolerant — the target is a lambda in a class you don't own, and a Fabric or Minecraft
bump renames it. But `require = 0` means the isolation can stop applying and the only signal is
nothing: the README still advertises "Isolate server-pack caches by account", the option stays "on",
and the server gets a shared cache path again. Same shape as the `RegistrationPayload` reflection
finding from last review, which you fixed by making the failure loud (`PrivacyAudit.record(... WARN)`).

Suggestion: keep `require = 0`, but assert the feature actually ran. Two cheap options:

* a `@Unique` static counter in the mixin incremented in `nulltrace$isolatePackPath`, checked once
  ~30 s after the first server pack download; zero → `PrivacyAudit.record(… WARN, "pack-cache
  isolation injection did not bind")` plus the existing degraded-feature toast; or
* a startup probe that greps the `DownloadQueue` class bytecode for the target method name and logs
  when it is absent (`tools/ci/inspect_jar.py` already does the equivalent for built jars, so the
  knowledge is in the repo).

## 5. `serverPackTranslations` is an uncapped copy of server-controlled strings · P2

`ClientLanguageMixin.java:129-141` records every key/value from any server-pack lang file:

```java
ModRegistry.recordServerPackTranslation(key, value);   // ModRegistry.java:322-330
```

with no size cap and no eviction — `recordServerPackTranslation` puts straight into a
`ConcurrentHashMap` and the only clear is `clearTranslationKeys()` on a language reload. The rest of
the codebase is careful about exactly this (`AuditLedger` 2048, `TrackPackDetector` 100/50,
`PrivacyLogger` 50), so this one stands out.

A pack may ship an arbitrarily large `assets/minecraft/lang/en_us.json`; vanilla keeps its own map,
so the marginal cost is a second map plus its table — roughly 50-60 bytes per entry, so a 1 M-entry
lang file costs ~60 MB that survives for the session. The values are echoed back to the server by
design (`getServerPackTranslation` in `TranslatableContentsMixin.nulltrace$getBlockedValue`), which
is a good behaviour; the retention just needs a ceiling. Cap it (e.g. 200 k entries, log-and-stop,
matching the `MAX_*` constants in `NullTraceConstants.Limits`), and prefer keeping keys the client
actually asked about over a wholesale copy.

## 6. Vault key is briefly world-readable; Windows fallback does nothing · P3

`AccountTokenVault.loadKey()` `:134-136`: the key file is created with the umask default and
*then* `restrict()`ed. Between the two calls it is commonly `0644`, i.e. the key that decrypts every
saved session token is readable by any other local process. `restrict()`'s fallback for non-POSIX
filesystems (`setReadable(false,false)` then `setReadable(true,true)`) is a no-op on Windows, so
there the key inherits the parent directory's ACL with no warning at all. The class comment honestly
says this is not protection against a local administrator — but "other local users on a shared
machine" is a weaker threat model than that, and worth closing.

Fix (on this branch): create with `CREATE_NEW` and `PosixFilePermissions.asFileAttribute(OWNER_READ|OWNER_WRITE)`
in one operation; keep the old path as the non-POSIX fallback. `CREATE_NEW` also makes two clients
starting at once resolve to one key instead of racing.

Note on the same file: `nulltrace-accounts.json` itself is still written with default permissions.
That's defensible (the tokens inside are ciphertext), but since you already `chmod` the key,
`0600` on the accounts file costs one line and removes the "ciphertext + missing key =
re-authentication" question from a shared-machine review.

## 7. Local-address guard: the LAN fix is correct, two edges remain · P3

`LocalAddressUtil.isBlockedServerPackTarget` is a real fix for last review's P1 and I tried to break
it. Results from running the actual class (JDK 11, real `InetAddress` resolution):

```
server 192.168.1.5 -> 127.0.0.1              blocked   OK
server 192.168.1.5 -> 169.254.169.254        blocked   OK   (cloud metadata service)
server 192.168.1.5 -> 192.168.1.9            allowed   OK   (same LAN, intended)
server public    -> 10.0.0.1                 blocked   OK
public server -> '127.1' / '2130706433' / '0x7f000001' / '017700000001'   all blocked   OK
public server -> '[::ffff:127.0.0.1]' / '[::1]' / 'localhost'             all blocked   OK
public server -> '127.0.0.1.' (trailing dot)  NOT blocked   <- fails open
server 192.168.1.5 -> 100.64.0.1 (CGNAT)      allowed   (isNeverServerReachable doesn't cover CGNAT)
```

* The trailing-dot case is fail-open by design (`isBlockedServerPackTarget` returns `false` for an
  unresolvable host, on the grounds that the connection fails anyway) — and it does: JDK's
  `InetAddress` refuses `127.0.0.1.`, so the subsequent connect fails too. Not exploitable in the
  JDK stack, but if the check is ever reused for a resolver that normalises trailing dots, it
  becomes an SSRF bypass. `normalizeHost()` already strips trailing dots; use it here.
* `isNeverServerReachable` deliberately omits CGNAT and `198.18/15`, so a server on `192.168.x.x`
  may point the client at `100.64.x.x`. Defensible (some CGNAT ranges are legitimately reachable on
  a LAN), but it is a documented difference between the two predicates and deserves a line in the
  doc comment's table so a future reader doesn't "fix" it by accident.

Everything else about the guard holds up: it checks *every* resolved address rather than the first,
it is applied to the first hop and to each redirect hop, the 305-proxy hop and the redirect-limit
path, and the redirect-limit comment explains the SSRF primitive it replaced.

## 8/9. Smaller things

* **Proxy probe** (`ProxyTester.java:76`): `statusLine.contains(" 200")` accepts `HTTP/1.1 2000 …`.
  Match the status code (`startsWith("HTTP/1.1 200 ")` or parse it). The probe also sends
  `User-Agent: NullTrace/<version>` (`:64`) — harmless, but it is a version fingerprint handed to a
  third-party proxy, which is a strange thing for this mod to volunteer.
* **Jar integrity** (`JarIntegrityChecker.java:138`): the checksum URL comes from the API response
  and is fetched with redirects enabled, so the trust anchor is "whatever gitlab.com said"; fine.
  The real limit is that the check is advisory — `tamperWarningDismissed` silences it, and an
  attacker who can modify the jar can delete the check. Worth saying so in the README next to the
  speed-bump language, since a user reading "verify jar integrity" may assume more.
* **LAN hosts get no probe protection**: `KeybindContentsMixin` and `TranslatableContentsMixin` both
  short-circuit on `Minecraft.getInstance().hasSingleplayerServer()`, which is also true while the
  player is *hosting* a LAN world. Intentional (no remote server to leak to), but it means "Open to
  LAN" quietly turns off translation/keybind protection for the host.
* **`MeteorMixinCanceller`** cancels mixins belonging to another mod (`meteorFix`, default on). It
  is documented in code, but a mod that silently disables parts of another mod is the kind of thing
  that should be one line in the README's compatibility section next to the declared conflicts.
* **`ClientInformationMixin`** hardcodes `chatColors = true` and `textFilteringEnabled = false`
  while normalising; the latter disables client-side text filtering regardless of the user's
  vanilla setting. Probably intended (or inherited), but it is a behaviour change the option's
  tooltip doesn't describe.

---

## What I could and could not verify

**Verified by running code** (standalone harnesses against the real source files):

| Thing | Result |
| --- | --- |
| `LocalAddressUtil.isBlockedServerPackTarget` matrix (LAN server, metadata IP, public server, alt IPv4/IPv6 literal spellings, CGNAT, unresolvable) | 14/16 as intended, 1 deliberate fail-open (trailing dot), 1 documented gap (CGNAT) |
| `InetSocketAddress#toString()` → parse, i.e. the actual profile key | confirmed `example.com/172.66.147.243` (finding 1) |
| `tests/vault` harness (26 checks) against the current vault | 26/26 pass |
| Same harness against the patched vault (§6) | 26/26 pass — and it *caught my first attempt*, which broke the "corrupt key file is regenerated" path; that is a good harness |
| Recompiled `AccountTokenVault` after the fix | `tests/vault` 26/26 — the harness caught a first attempt that broke corrupt-key regeneration |

**Verified by reading, no client needed:** the outbound payload policy is now genuinely single-source
(`ChannelDecision.decide` called from both the `Connection#send` inject and the Netty filter, with
the ViaFabricPlus identity block inside it); chat signing likewise (`ChatSigningPolicy`); the
per-account pack-cache path is keyed by account UUID; `LangOnlyPackResources` passes
`getRootResource`/metadata through and filters everything but `lang/`; caches are bounded
(`AuditLedger` 2048, `TrackPackDetector` 100/50, `PrivacyLogger` 50, `PacketContext` thread-local
and cleared in `finally`); the config writes are deferred, coalesced, `synchronized` and atomic; and
`HttpClients` is the only place a client is built, invalidated on proxy change — I found no
remaining bare `HttpClient`/`new URL(...).openConnection()` outside the pack-download path and the
user-initiated proxy probe.

**Could not verify:** anything that needs Minecraft running — mixin binding on all four targets,
the pack strip actually applying, chat-signing behaviour against an enforcing server, and whether
account switching on a pool thread crashes in practice. A four-target `./gradlew build` plus your
`tests/adversarial_server.py` against a 1.21.11 client would close most of that.

## No red flags

No `Runtime.exec`, `ProcessBuilder`, `ObjectInputStream`, custom class loaders, or dynamic code
loading. `Class.forName` appears twice, both on Fabric internals (`ModPackResourcesUtil`,
`ModIdResolver` pack inspection). Network destinations are the ones the README describes:
GitLab/CurseForge metadata, `api.minecraftservices.com` + Microsoft/Xbox auth for the account
feature, and gitlab.com release assets for the integrity check — all through the configured proxy,
none carrying credentials or identifiers beyond the version string. The CI publish job requires the
test matrix, the jar audit and the artifact provenance checks from the *same* pipeline, verifies
each jar's embedded version against the tag, and publishes bare-name checksums that are re-verified
with `sha256sum -c`. That is better than most projects of this size.

## Fixes landed

Landed on `main` as `a10711e` (this document is `d3eb6c3`), 3 files, 74 +/14 −:

1. `ClientPacketListenerMixin` — `getHostString()` server key + a `@Unique` helper with the
   reasoning in the javadoc (finding 1).
2. `AccountManager` — `synchronized save()` and a per-call temp file name (finding 3).
3. `AccountTokenVault` — exclusive create with owner-only permissions, non-POSIX fallback preserved,
   and the invalid-key branch now deletes the unusable file so regeneration still works (finding 6).

Findings 2, 4, 5 are design changes rather than one-line patches; the login refactor wants a real
client to test against, so the render-thread hop in §2 is written up rather than applied here.

**Compiled and tested.** `./gradlew :1.21.11:test :26.1:test :26.2:test :26.3:test` and
`buildAll` both succeed with these fixes in place, as do the four-target jar audit and the two
standalone harnesses — see the section below.

## Build and test verification

Run 2026-10-28 on Temurin 21.0.13+9 / 25.0.5+8, Gradle 9.6.1, Loom 1.17.11, against `main` @
`a10711e`.

| Step | Result |
| --- | --- |
| `:1.21.11:test :26.1:test :26.2:test :26.3:test` | BUILD SUCCESSFUL (3m 30s) — **424 tests, 0 failures, 0 skipped** |
| `buildAll` (4 targets) | BUILD SUCCESSFUL — 4 jars; bytecode major 65 (1.21.11) and 69 (26.x), as configured |
| `inspect_jar.py --version 2.2.0 --strict` | **18/18 checks × 4 jars** |
| `gen_sums.py --verify` + `sha256sum -c` + `verify_release.py` | 4/4 OK, **12/12 checks** |
| `tests/vault` | **26/26** |
| `tests/proxy` | **54/54** |
| `tools/ci/preflight.sh` | preflight OK (14 checks, 33/33 self-tests, fixtures) |

The count above is 106 tests per target; `src-review-2026-10-26-fixes.md` records 98, so the
suite has grown since that document was written.

Three warnings come from the Mixin AP on the obfuscated 1.21.11 target, and two are worth citing
against findings in this review:

* `DownloadQueueMixin.java:37: Unable to determine descriptor for @ModifyExpressionValue target
  method` — compile-time confirmation of finding 4: the pack-cache isolation target is a lambda
  and is not statically resolvable, which is why it is the one `require = 0` injection.
* `ComponentSerializationMixin.java:20: Unable to locate method mapping for
  @At(INVOKE) 'Codec;recursive(...)'` — the component-probe protection's only binding point
  targets a Mojang library method, so no refmap entry can be emitted. Library classes are not
  remapped, so this most likely binds fine, but it is the first thing to check in a client smoke
  test.
* `PackSelectionModelEntryBaseMixin.java:25` — target should be in `value`; cosmetic.

**What this still does not prove:** that any of it works inside a running client. Mixin binding for
the whole `nulltrace.client.mixins.json` list, account switching off the render thread (finding 2),
pack strip against a real server pack, and chat signing against an `enforce-secure-profile=true`
server all remain unverified at runtime.
