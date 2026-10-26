# NullTrace source review — 2026-10-26

Scope: `src/` on `main` @ `7f2dde9` — 98 Java files, 17 248 lines
(15 277 client, 987 common, 984 test).

Method: static read of the protection, privacy, account, proxy and config layers
plus the mixins that carry them; grep-driven hunts for the usual suspects
(threading, unbounded caches, resource handles, reflection, swallowed exceptions,
check-then-use); cross-checked against the published README claims.

**Not done:** a compile or a runtime test. The 1.21.11 target needs a JDK 21
toolchain and 26.x needs JDK 25; this machine has JDK 11 and no network for the
Fabric stack. Every finding below is from reading the code, and each names the
file and line so it can be checked in a minute.

---

## Verdict

**Solid, above average, not finished.** The architecture is genuinely good — policy
logic in the common source set with no Minecraft imports, immutable snapshots,
bounded caches, atomic config writes, and mixin comments that explain *why* a
workaround exists instead of just *that* it exists. Someone has debugged this
codebase in anger.

What it needs is not a rewrite. It needs five fixes, one of which is a hole in a
protection the README advertises.

| # | Finding | Severity |
| --- | --- | --- |
| 1 | Local/private URL blocking switches itself off while connected to a LAN server | **P1** |
| 2 | One malformed pack push permanently consumes the ASK consent prompt | **P1** |
| 3 | Every settings toggle rebuilds the dependency closure and rewrites the config | **P1** |
| 4 | Two divergent copies of the outbound channel filter | **P1** |
| 5 | `Files.walk` stream leak in the cache-clearing routine | **P2** |
| 6 | Proxy change never reaches the update/integrity HTTP clients | **P2** |
| 7 | `RegistrationPayload` rebuilt by reflection; failure silently drops all mod channels | **P2** |
| 8 | Token vault has no AAD binding | **P2** |
| 9 | `save()` is the only unsynchronised method on a lock-heavy class | **P2** |
| 10 | Smaller things (dead code, unused exposure field, misleading comment) | **P3** |

---

## 1. Local-address blocking is disabled on LAN/private servers · P1

`HttpUtilMixin` guards every hop of a resource-pack download with the same
condition (`HttpUtilMixin.java:85`, `:119`, `:149`, `:176`):

```java
if (!LocalAddressUtil.isLocalAddress(LocalAddressUtil.serverAddress)
        && LocalAddressUtil.isLocalAddress(instance.getURL().getHost())) { … block … }
```

`LocalAddressUtil.serverAddress` is the address of the **game server**
(`ClientConnectionMixin.java:62`). So when the player is connected to a server
whose IP is private — a LAN world, a friend's `192.168.x.x`, any host on the same
private range — the first conjunct is false and **all four checks are skipped**.
The client will then happily fetch `http://127.0.0.1:<port>/` on the instruction
of that server, and the status code / latency difference is readable by it.

That is the exact shape of the finding the earlier review flagged for the
redirect-limit path (`"never reproduce vanilla's raw Socket side effect"` — a good
comment, and the code matches it). The same reasoning was not applied to the
guard as a whole.

The README says: *"Resource-pack destinations — Block requests and redirects to
local/private network addresses."* On a private-IP server, that is not true.

**Fix.** Stop treating "server is private" as a blanket exemption. Loopback,
link-local and CGNAT should never be reachable from a server-directed URL. Keep
the exemption only for same-network peers:

```java
boolean serverIsPrivate = LocalAddressUtil.isLocalAddress(serverAddress);
boolean targetIsLoopbackish = LocalAddressUtil.isLoopbackOrLinkLocal(target);
if (targetIsLoopbackish || (!serverIsPrivate && isLocal(target))) block(...);
```

`LocalAddressUtil` already has the range predicates; it needs two narrower ones.

## 2. One malformed push burns the ASK consent prompt · P1

`PackStripHandler.onPackPush` (`PackStripHandler.java:71-75`):

```java
if (stripPack && required && mode == ASK
        && overlayShownThisSession.compareAndSet(false, true)) {
    if (!isHttpUrl(url)) return;   // ← flag already consumed
    PackStripOverlay.enqueue(id, required);
}
```

The one-shot `AtomicBoolean` is set **before** the URL is validated. A server can
push a single *required* pack with an unusable URL (vanilla rejects it with
`INVALID_URL`), and from then on no consent overlay is ever shown for the rest of
that session: the next, legitimate push is wrapped and stripped without asking.
The pack stays stripped (privacy-safe direction), but the player silently loses
the choice the mode exists to offer.

**Fix.** Validate first, then take the flag — and reset the flag on the reject
path if you want to keep the CAS:

```java
if (stripPack && required && mode == ASK && isHttpUrl(url)
        && overlayShownThisSession.compareAndSet(false, true)) {
    PackStripOverlay.enqueue(id, required);
}
```

## 3. A settings toggle costs a full dependency-closure rebuild and a config write · P1

`NullTraceConfig.save()` ends with:

```java
if (INSTANCE != null) {
    ModRegistry.rebuildDependencyClosure();
}
```

and `rebuildDependencyClosure()` (`ModRegistry.java`) walks the dependency graph
of every whitelist seed plus JIJ hosts, allocating two fresh `ConcurrentHashMap`s
per call. The configuration screen calls `config.save()` from **31** places
(`NullTraceConfigScreen.java:169, 248, 259, 263, 266, 269, 272, 291, 298, 318,
326, 346, 357, 366, 396, 412, 418, 423, 445, 450, …`) — i.e. on every toggle and
every cycle-button press, on the render thread.

Consequences on a 300-mod pack: a frame hitch per click, and one file write per
click (30+ writes while tabbing through the screen). The closure only matters for
whitelist membership, so most of those rebuilds cannot change its result.

**Fix.** Two cheap options, ideally both:

- `save()` marks a `dirty` flag; a client-tick hook flushes at most once every
  N seconds, and `rebuildDependencyClosure()` only when a whitelist-relevant field
  changed (whitelist mode, whitelisted mod set, dependency closure inputs).
- Serialise once per tick at most: `save()` → `requestSave()`.

## 4. The outbound filter exists twice, and the copies differ · P1

`ClientConnectionMixin` contains two implementations of the same decision:

- `NullTracePacketFilter.write` (`:111-238`) — a Netty outbound handler installed
  after `encoder` (`:99`);
- `handleOutgoingPacket` (`:259-429`) — the `send(Packet)` inject, which cancels.

They are ~110 and ~120 lines respectively, with the same vanilla/fabric/brand
logic written twice. They are **not** equivalent:

- the Via identity block (`vv:mod_details`, `:160-169`) exists **only** in the
  pipeline handler;
- the pipeline handler is installed opportunistically
  (`pipeline.addAfter("encoder", …)` inside a `try/catch` that logs and continues,
  `:96-105`). If another mod replaced or renamed the `encoder` handler, the
  install fails, the send-path filter keeps working — and Via identity payloads
  start leaking with no user-visible signal.

Same class of problem in the fabric-register rewrite: the pipeline path and the
send path each build a replacement payload.

**Fix.** One `OutboundPolicy.decide(namespace, path, payload) → ALLOW | DROP |
REWRITE(channels)` used by both call sites, and treat install failure as a
degraded-mode warning (`PrivacyLogger.alert`) rather than a debug log.

## 5. `Files.walk` is never closed in the cache clearer · P2

`ResourcePackGuard.clearCacheFolder` (`:113`):

```java
for (Path p : Files.walk(cachePath).sorted(Comparator.reverseOrder()).toList()) {
```

`Files.walk` opens directory handles and must be closed; `.toList()` terminates
the stream but does not close it. The result is a leaked handle per call — on
Windows, handles on the very files being deleted, which then fail with the
"I/O error" the loop already warns about. This is the one routine whose entire
job is deleting files.

**Fix.** `try (var paths = Files.walk(cachePath)) { … }`.

## 6. A proxy change never reaches two HTTP clients · P2

`UpdateChecker.java:33-44` and `JarIntegrityChecker.java:31-41` each cache a
`HttpClient` in a `volatile` field built once, on first use, from
`HttpClients.outbound(...)`. The comment in `UpdateChecker` claims the lazy build
means *"a proxy change applies without restarting the game"* — but nothing ever
clears the field (`grep 'httpClient = null'` finds nothing in the tree). Switch
the proxy mode after startup and the update check plus the integrity check keep
using the old route for the rest of the session. For a player whose whole reason
for the proxy is not leaking their address to third parties, that is the bug the
2.1.0 changelog set out to remove.

**Fix.** Own the client in `HttpClients` with an explicit `reset()`; call it from
`NullTraceConfig.save()` when the proxy settings object changed (and from the
Proxy screen's apply action).

## 7. `RegistrationPayload` is rebuilt by reflection, and failure is silent-ish · P2

`ClientConnectionMixin.nulltrace$createRegistrationPayload` (`:576-609`) walks
`RegistrationPayload.class.getDeclaredConstructors()`, picks the two-argument one,
`setAccessible(true)`, and tries both parameter orders, swallowing both
`Exception`s before returning `null` with a warning.

Two problems:

1. **Fragility.** This is a Fabric *implementation* class; a signature change in
   any Fabric API bump silently disables channel filtering, and `setAccessible`
   into a future modularized class throws `InaccessibleObjectException`.
2. **Failure mode.** When it returns `null`, the caller drops the whole
   `minecraft:register` packet (`:204-209` in the pipeline path, `:464-468` in the
   send-path helper). Every mod channel disappears — voice chat, mod integration, all of
   it — and the user sees nothing.

**Fix.** Resolve the constructor once, cache it in a `static final` holder, and on
failure prefer *sending the original unfiltered payload* with an audit record
over dropping it. Fail-open with a visible warning beats a silent total loss of
mod networking; the audit ledger is exactly the place to record that decision.

## 8. Token vault: AES-GCM without AAD · P2

`AccountTokenVault.encrypt/decrypt` (`:37-67`) is otherwise careful — 32-byte key
in its own file, 12-byte random IV per value, 128-bit tag, refuses to fall back to
plaintext, owner-only permissions, no tokens in exports. The gap is that the
ciphertext is not bound to the account it belongs to. Anyone who can edit the
accounts JSON (sync folder, shared backup, malware with limited access) can swap
two accounts' `refreshToken` blobs and the decryption still succeeds; the client
then logs in as the wrong account.

**Fix.** `cipher.updateAAD(accountId.getBytes(UTF_8))` on both sides. One line
each, and a swap becomes an auth failure instead of a wrong-account login.

## 9. `save()` is unsynchronised on a class that locks everything else · P2

`NullTraceConfig` marks `getServerProfile`, `setProfileMode`, `getServerProfiles`,
`privacyPolicy` and `proxySettings` `synchronized` (`:309-367`), then leaves
`save()` (`:273`) iterating `serverProfiles` and reading `settings` without the
lock. Today all callers are on the render thread, so it works — but the moment a
background saver (or the tick-based debounce from finding 3) exists, that becomes
a `ConcurrentModificationException` into a `catch (IOException)` that will not
catch it. Also worth noting: the `IOException` catch only logs, so a failed write
loses the user's settings with one line in `latest.log`.

**Fix.** Synchronise the method on the same monitor, and surface a failure as a
toast/alert instead of a log line.

## 10. Smaller things · P3

| Where | What |
| --- | --- |
| `LocalAddressUtil.java:117-119` | `return a == 255 && b == 255 && c == 255 && d == 255;` is unreachable — `a >= 224` returned two lines earlier. The javadoc table above it promises ranges the code handles elsewhere (`isSiteLocalAddress`). |
| `PrivacyAudit.java:29` | `currentExposure(channels.size())` stores *every* channel of every mod, ignoring the active policy — and nothing reads `ConnectionContext.currentExposure()` (`grep '\.currentExposure()'` → no hits). Either delete the field or compute it from the effective policy; a misleading number waiting to be displayed is how the old `remainingExposure()` bug happened. |
| `PrivacyPolicy.java:33-34` | After blocking every surface in a loop, it re-sets `MOD_CHANNEL` to `BLOCK` under the comment *"The vanilla channel is not a mod fingerprint and must remain usable."* Dead assignment, wrong comment. Only the test calls `maximumPrivacyPolicy()`; the behaviour (block everything) is what `WarningAssessmentTest` asserts, so fix the comment. |
| `PackStripHandler.java:95-103` | `onPop(Optional.empty())` clears four sets and the shader tracker but not `overlayShownThisSession`; only `clearAll()` (disconnect) resets it. Inconsistent with the per-pack path 20 lines below. |
| `ClientConnectionMixin.java:81` | `private static final AtomicBoolean nulltrace$logged` — static state inside a mixin is per-classloader and shared by every `Connection`; harmless here, but statics in mixins bite in dev with two clients in one JVM. |
| `JarIntegrityChecker.java:287` | `bytesToHex` via `String.format("%02x", b)` per byte; `HexFormat.of().formatHex(digest)` is allocation-free on JDK 17+, and this runs over a whole jar on a background thread. |
| `JarIntegrityChecker.java:165` (checksum compare) | `MessageDigest.isEqual(expectedDigest.getBytes(), actualDigest.getBytes())` — correct, but both are already lowercase hex; comparing `String.equals` would be no worse and cheaper. Keep as is if you like the constant-time habit. |

---

## What is genuinely good

Not filler — these are the reasons the answer is "solid" and not "rewrite it":

- **Layering.** `PrivacyPolicy`, `ConnectionContext`, `AuditLedger`,
  `WarningAssessment`, `LocalAddressUtil`, `UpdateMetadata` live in `src/main`
  with no Minecraft imports, and have tests. The client bridge
  (`PrivacyAudit`, `ConnectionWarning`) only gathers inputs. This is why the
  policy rules can be changed with confidence.
- **Bounded everything.** `AuditLedger` caps its event window at 2 048 *and* keeps
  cumulative counters so totals don't freeze (with a comment explaining the bug it
  replaced). `TranslationProtectionHandler` caps dedupe at 500 and truncates
  alert values at 256 chars specifically so a poisoned pack can't flood chat.
  `TrackPackDetector` caps hashes/URLs and windows by time. `PacketContext` clears
  its ThreadLocals per decode.
- **Writes that survive a crash.** `NullTraceConfig.save()` writes a `.tmp` and
  `ATOMIC_MOVE`s it; a half-written config is not possible.
- **Credentials handled like credentials.** Separate encrypted vault, refuses
  plaintext on crypto failure, redacts on export, no tokens in logs.
- **Security comments that show the reasoning.** The redirect-limit comment
  (`HttpUtilMixin.java:108-111`) explaining *why* the code refuses to reproduce
  vanilla's raw `Socket` probe is the best comment in the tree. Same for
  `isDecodingPayload` vs `isProcessingPacket` in `PayloadTypeRegistryImplMixin`
  — that distinction is subtle and is written down with the failure it prevents.
- **Version gating is disciplined.** Every cross-version branch is a Stonecutter
  `//?` block with a one-line reason; no reflection-by-string for Minecraft
  internals except where the class genuinely isn't in the mappings
  (`method_15303`), and that one is documented.

## Suggested order

1. Fix #1 and #2 — both are small, and #1 contradicts a README claim.
2. Fix #3 — it is the one thing users will *feel* while turning settings on.
3. Consolidate #4, then make the pipeline-install failure loud.
4. #5, #6, #9 are each a few lines.
5. #7 and #8 are judgement calls: the reflection fallback wants a policy decision
   (fail-open + audit vs. drop), and the vault wants an AAD field on the envelope,
   which is a small format bump (`v2:`).

## Verification I could not do

- Compile all four targets, and confirm the `>=26.3` branches
  (`DownloadedPackSourceMixin`, `ServerboundChatPacketMixin`) actually target
  existing methods — those two are commented-out Stonecutter branches, so a typo
  there only shows up in the 26.3 build.
- Exercise the LAN guard (#1) against a local server with a pack that points at
  `http://127.0.0.1:<port>/` — the code path is clear, but a runtime test would
  make it a regression test rather than a review note.
- Confirm the CurseForge jar is byte-identical to the GitLab asset, since
  `JarIntegrityChecker` only knows about GitLab. If CurseForge ever rebuilds or
  repackages, every CurseForge user gets "this jar is not the official release
  build". Worth one check before the next release.
