# NullTrace — codebase review, 2026-10-17

Scope: `main` @ `df02606` + the privacy-warning redesign patch. Read end-to-end:
the privacy core, the mixins that implement each protection, the config/account
layers, the CI pipeline, and the test fixtures.

Ordered by what I would fix first. Each item names the file, what breaks, and what
to do about it. "Shaper" items (the ones that would change what NullTrace *is*)
are marked 🧭.

---

## P0 — trust and correctness

### 1. The CI "adversarial fixture" tests nothing about NullTrace  — partly addressed

`tests/adversarial_server.py` serves a hardcoded JSON list of probe *names*
(`brand`, `channel`, `known-pack`, …) over HTTP, and `test_adversarial_server.py`
asserts that the list is what it is. No Minecraft protocol, no client, no NullTrace
code. The job is green regardless of whether every protection in the mod is
commented out.

That matters more than it looks: `README`/`ROADMAP` present this as the adversarial
regression fixture, so the strongest available signal about the mod's core promise
is currently decorative.

**Do:** make the fixture a real harness. Cheapest version that has teeth: a JUnit
test that drives the pure-logic seams — hand-built `FriendlyByteBuf` payloads through
the same filter functions the mixins call (`ClientSpoofer.shouldDropInboundChannel`,
the `minecraft:register` rewrite, `KnownPacksManagerMixin` filtering,
`TranslationProtectionHandler`) and asserts *blocked-or-normalized + a ledger entry
with an explicit reason*. A `PolicyRegressionTest` table-driven over
`(surface, server profile, setting) → expected PrivacyAction` is ~100 lines and would
have caught the `probes > 0` warning bug immediately. Keep the Python fixture for the
SSRF/redirect vectors only — there it is genuinely useful, because that code is
HTTP-level and can be exercised without Minecraft.

### 2. Update check and jar integrity bypass the player's proxy — and phone home on every launch  — addressed

`UpdateChecker` (`HttpClient.newBuilder()...`) and `JarIntegrityChecker` (same)
build their own `HttpClient` with **no proxy**, and `NullTraceClient.onInitializeClient`
calls both unconditionally at startup. Meanwhile the changelog is proud that account
HTTP was routed through `Minecraft#getProxy` — the same reasoning was not applied here.

Consequences: a player who set a proxy for privacy leaks their real IP to GitLab and
`api.cfwidget.com` on every launch, plus their mod version (and Guzzle-cached CF
metadata) — from a *privacy mod*. Two outbound requests also add a startup delay and
a failure mode (`UpdateScreen` on the multiplayer screen) for users on restricted
networks.

**Status:** done for (a) and (c). `HttpClients.outbound(...)` is the single proxy-aware client
factory, used by the update check, the integrity check and (through `Minecraft#getProxy()`) the
account code; both startup checks are switchable under Advanced → Network, and the footer no longer
claims "up to date" when the check is off. Still open: (b) — the checks remain on by default and no
first-run prompt exists, so a player who never opens the config still makes two requests. README
wording for the network disclosure is also still to do.

### 3. Jar integrity: the alarm is louder than the evidence  — addressed (wording)

`JarIntegrityChecker` computes the running jar's SHA-256 and compares it against the
`.sha256` asset attached to the matching GitLab release. The *strings* it then shows
say `TAMPER_MALICIOUS`, `TAMPER_COMPROMISED`, "the jar may be malicious", and offer
"download the official release / dismiss permanently".

Two problems.

* **False positives are near-certain.** The check compares bytes. Any user who
  repacks the jar (some launchers re-zip, some users merge jars by hand, anyone
  running a local build, anyone whose CurseForge artifact is not byte-identical to
  the GitLab asset — which is exactly the kind of drift the changelog's
  "curseforge fixes" commits imply) gets a red "your mod may be compromised"
  screen. That is the same wolf-crying failure the warning screen just had removed.
* **It cannot defend against the threat it names.** Anyone who can modify the local
  jar can delete the check; the expected digest is fetched over the same TLS path as
  the jar itself. It is a packaging-drift detector, not a security control.

**Status:** wording and the dev-version skip are done: the screen now reads "This jar is not the
official release build", says that repacked/branch/local builds explain it, and is skipped entirely
when the running version is not a release (`2.0.3-...`). Stale translations of the old alarming text
were dropped so the nine other locales fall back to the new English wording instead of claiming a
compromise. Still open: the "check again" button and signature verification.

**Remaining do:** consider verifying a detached signature whose public key is embedded in the code,
which at least forces an attacker to patch code rather than re-attach a checksum file.

### 4. Local-address guard: coverage gaps and a check-then-connect race  — gaps fixed, race open

`LocalAddressUtil.isLocalAddress` uses `isAnyLocalAddress || isLoopbackAddress ||
isSiteLocalAddress || isLinkLocalAddress`. Missing:

| Category | Range | Note |
| --- | --- | --- |
| IPv6 unique-local | `fc00::/7` | `isSiteLocalAddress()` only covers deprecated `fec0::/10` |
| CGNAT | `100.64.0.0/10` | carrier NAT, common on VPS/cloud networks |
| "This network" | `0.0.0.0/8` | `isAnyLocalAddress()` matches only the single address `0.0.0.0` |
| Multicast / reserved | `224.0.0.0/4`, `240.0.0.0/4` | `isMulticastAddress()` is not checked at all |
| IPv4-mapped IPv6 | `::ffff:127.0.0.1` | usually normalised by Java, but not guaranteed for literal forms |

Separately, `HttpUtilMixin` resolves the host, then lets `HttpURLConnection` resolve it
*again* when it connects. A DNS answer with a short TTL can therefore pass the check
as public and connect as loopback — the javadoc's claim that this "catches DNS
rebinding attacks" is stronger than the code. Note the blast radius is narrow: over
`https://` a private-IP target fails certificate validation anyway, so the exploitable
case is plain `http://` redirect hops — which is exactly where the fix is cheapest.

**Status:** every range in the table above is now rejected, the class moved to `src/main` so it is
unit-tested (`LocalAddressUtilTest`, literal addresses only, no DNS), and IPv4-mapped forms are
re-checked against the IPv4 ranges. The check-then-connect race is unchanged and now stated in the
class javadoc instead of being implied away.

**Remaining do:** for
`http://` URLs, resolve once and pin the connection to the checked `InetAddress`
(connect to the literal address with a manual `Host:` header) so check and connect
cannot disagree. At minimum, document the limitation in the class javadoc instead of
claiming protection that isn't there.

### 5. `remainingExposure()` counts events, not exposure  — addressed

`AuditLedger.remainingExposure()` returns the number of ALLOW/WARN/LOG **events** in
the bounded 2048-entry deque. A server that probes once per tick makes this number
climb to 2048 while nothing new is actually exposed, and the `/nulltrace audit` output
(`Remaining exposure: %d`) inherits that. The redesigned warning screen no longer uses
it (it derives visible areas from the policy), but the command still shows it.

**Status:** `AuditLedger.allowedSurfaceCount()` (distinct surfaces, cumulative) and
`allowedSurfaces()` were added; `/nulltrace audit` uses the former. `remainingExposure()` is
deprecated and documented as an event count, and — thanks to a test that failed while writing this —
`count()`/`counts()` are now cumulative rather than window-capped, which fixes a second instance of
the same class of bug.

### 6. Warning copy bug from the last patch (mine)

`PrivacyWarningScreen` prints "Client brand — **not sent**" whenever the brand policy
is BLOCK. But `ClientBrandRetrieverMixin` *replaces* the brand (`getEffectiveBrand()`
→ `"vanilla"`), so the server does receive a brand; it just isn't the real one. A
screen that under-reports what is sent is the same class of dishonesty the redesign
removed, in the opposite direction.

Fixed in this review's follow-up patch: `ConnectionWarning.Summary` now carries the
spoofed brand and the row says "reported as vanilla" (`§a`) instead of "not sent".
Worth auditing the other rows the same way — every "hidden" status should be read as
*"the server receives nothing useful"*, and should be verifiable in the code that
implements it. (`CLIENT_INFO → normalized`, `PACK_REQUESTS → filtered` and
`TRACKING → blocked` all check out. `TEXT_PROBES → blocked` is the weakest: the probe
still happens and the server still sees the *response*; consider "unresolvable".)

---

## P1 — effectiveness of the privacy goal

### 7. Fixed normalization constants build a NullTrace fingerprint 🧭

`NormalizedLanguage.EN_US` + `NormalizedViewDistance.FIXED_8` + `NormalizedChatVisibility.FULL`
+ normalized particles means every NullTrace user on a given server reports the *same*
client-information packet. On a server with two of your users, "en_us, view 8, chat
full, particles all" is a set — and the set is NullTrace. The `RANDOM` language option
helps, but only for locale.

**Do:** replace constants with **plausible buckets**: keep the player's real values
where they carry no information (nobody identifies you by view distance 12 vs 16), and
only coarsen what is identifying; where a value must be replaced, choose a
per-account-stable pseudo-random value from a realistic distribution (seeded by a
per-profile secret, not by UUID — otherwise it is a stable identifier again). Then
document the trade-off honestly: k-anonymity within a modelled population, not
"normalization".

### 8. Policy evaluation allocates and synchronises on the packet path

`PrivacyAudit.evaluate()` → `NullTraceConfig.privacyPolicy()` is `synchronized` and
builds a fresh `PrivacyPolicy` (EnumMap + `Set.copyOf`) on **every call** — and the
callers are per-packet hooks: `ClientBrandRetrieverMixin`, `ClientInformationMixin`,
`PrivacyTransportMixin` (cookies + transfers), plus the command and warning paths.
`AuditLedger` then takes a lock per recorded decision on the same netty thread.

On a busy server the transport hooks run often, and the monitor is shared with the
render thread (which re-saves config and rebuilds on every UI toggle).

**Do:** keep one immutable `PrivacyPolicy` in a `volatile` field, rebuild it on config
save / profile change / server switch, and hand the netty threads that reference. It is
a small change and removes both the allocation and the cross-thread contention.
`AuditLedger` is already bounded and correct — leave it, but consider a per-connection
`LongAdder`-style counter for the hot counts.

### 9. There is no end-to-end check that the protections actually applied

Every protection is enforced by a separate mixin with its own conditions
(`ClientSpoofer` mode, whitelist mode, per-server profile, `EXPLOIT_PREVENTER_LOADED`).
Nothing verifies the resulting behaviour — e.g. "after join, no mod-channel payload was
emitted". So a regression in any one mixin is invisible until someone probes it.

**Do:** add a **join-time self-check** that inspects what was actually sent during
login/configuration (the payloads already flow through `ClientConnectionMixin`): count
outbound custom payloads, remember which channels were allowed, compare with the
policy's intent, and surface a single summary — in the audit screen and as a toast
only when they disagree. This is also the feature that makes the whitelist tab
meaningful: "policy says allow, wire shows allow".

### 10. Fidelity gaps worth modelling, in rough value order

1. **Resource-pack *timing*.** Cache isolation stops cross-account pack sharing, but
   the server still sees *when* a pack is fetched. A pack pushed 8 minutes into a
   session lands differently from one on the login screen; that timing is a linking
   signal the current model does not represent (no `PACK_TIMING`-style surface).
2. **`ClientInformation` sub-fields.** Locale/view/chat/particles are covered; the
   *skin parts* bitmask and (version-dependent) main-hand field are not.
3. **Payload shape.** Absence of `minecraft:brand` on a "vanilla" client, or an
   extra `custom_payload` at a version where vanilla sends none, is a cheap
   discriminator. A "would a vanilla client have sent this?" baseline check is the
   natural extension of item 9.
4. **Mod-list transmission via other channels.** The whitelist covers Fabric channels;
   a mod that sends its identification over a *vanilla* channel (or via chat/book text)
   is invisible to it. Worth a README sentence rather than code.

---

## P2 — architecture and maintainability

### 11. God classes

`ModRegistry` ~1050 lines (mod indexing + translation keys + keybinds + known packs +
shaders + whitelist + dependency closure), `NullTraceConfigScreen` ~1700 lines
(four tabs + widget factory + scroll management), `NullTraceConfig` ~520. They are
internally coherent, but every new protection edits one of them, and the mixins reach
into them for everything.

**Do:** split `ModRegistry` into `ModIndex` (identity/containers), `TranslationIndex`,
`KnownPackIndex`, `ShaderIndex`, `Whitelist`, keeping `ModRegistry` as the façade so
mixins don't change. Split the config screen per tab (`ProtectionTab`, `WhitelistTab`,
`MiscTab`, `AccountsTab`) — the tab bodies are already separate builder methods, so
this is mostly mechanical.

### 12. Test coverage is far below the risk profile

Two JUnit files, ~290 lines, for ~15k lines of Java touching packet paths. Nothing
tests: `ConnectionWarning` severity/mute/`shouldWarn`, whitelist resolution
(`isWhitelistedChannel`, dependency closure), `KnownPacksManager` filtering,
pack-cache keying per account, the `minecraft:register` rewrite, `LocalAddressUtil`,
`AccountTokenVault` (crypto!).

The last one is the most uncomfortable: the AES-GCM vault is untestable because the
key path comes from a static `FabricLoader` call. Extract the key path/`SecureRandom`
as parameters (package-private constructor) and add tests for round-trip, wrong-key
failure, IV uniqueness, and corrupt-input handling.

Also: CI runs `:26.3:test` only, so test-code breakage on the other three targets is
invisible; `SpoofSettings.fromJson` has a dozen legacy migration branches (including
`packStripMode` OFF/ON, `signingMode` ON_DEMAND) that are perfect unit-test material
and are currently untested.

### 13. Minor but real

* **`AccountTokenVault` writes the key before restricting permissions** — the file
  exists with the process umask for a moment. Create it with
  `PosixFilePermissions.asFileAttribute(...)` instead, then re-apply `restrict()`.
* A wrong-length key file is silently replaced, destroying every saved token. Log at
  warn level *and* move the bad file aside (`nulltrace-account.key.bad`) so a user can
  recover manually.
* **Thread visibility:** `SpoofSettings` fields are plain non-volatile booleans written
  from the render thread and read from netty threads (`isBlockServerCookies()`,
  `isFabricMode()`, …). `PrivacyPolicy` being immutable papers over most of it, but the
  settings reads in the mixins do not. The cached-policy fix (item 8) should include
  making the settings snapshot immutable.
* **Dead version gates:** `HttpUtilMixin`, `ChannelRegistrationMixin` and others carry
  `//? if <1.20.2` stub branches (some with `@Mixin(PacketUtils.class)` no-op bodies),
  but `versions/` only builds 1.21.11+ and `README` says older targets are gone. Either
  dead code or an untested path — delete, or restore the targets to CI. The stubs
  compile-succeed without proving anything, which is worse than a hard failure.

---

## P3 — what I would add next 🧭

In the order I would build them:

1. **Audit viewer screen + export.** The ledger already records surface/subject/action/
   reason; `/nulltrace audit` dumps it to chat. A scrollable screen with a filter and
   "copy report" (or export to file) makes the privacy model *legible*, which is the
   product's whole thesis. Add "explain this" click-through from an alert toast into the
   matching ledger entries.
2. **Per-server profile manager.** `ServerProfile`/`getServerProfiles()` exist, but the
   UI only edits the *current* server through a cycle button. A list (add/rename/copy/
   import/export/wildcard hosts like `*.example.com`) is what people expect from a mod
   whose model is per-server policy. It also fixes the papercut behind item 6 of the
   redesign doc (settings opened from the warning screen must target the right server).
3. **"Something I blocked broke my join" recovery.** Detect a disconnect within ~15s of
   joining with a non-standard profile, correlate with the ledger, and offer: *"NullTrace
   blocked 3 mod channels before this server kicked you. Allow them for this server and
   retry?"* This is the single highest-value UX feature left: it converts the strictest
   setting from a support burden into a button.
4. **Chat-signing UX.** `SigningMode.OFF` is a real privacy win and a real breakage.
   Give it the treatment the pack prompt got: explain at join time, per-server override,
   and a ledger line, instead of a docs sentence.
5. **Per-server connection proxy (SOCKS5).** The README is honest that NullTrace does not
   hide your IP — but that is the largest remaining identifier, and the per-server profile
   model is the natural place for "route this server through SOCKS5". Large, but it is
   the only item on this list that changes the threat model rather than sharpening it.
6. **First-run onboarding + i18n.** One screen: what the mod does, the three whitelist
   modes, and a "show me" that opens the warning preview. Translate the
   `nulltrace.warning.*` keys into the nine bundled locales — the highest-stakes screen is
   currently English-only, which the new copy makes cheap to fix.
7. **Sign releases.** GPG-sign tags/artifacts and publish the public key in the README;
   it is the only part of the distribution chain that the in-jar integrity check cannot
   ever provide.

---

## Distribution / legal risk  — addressed

`CrackedAccount` + `AddCrackedAccountScreen` let the client swap its `User` for an
offline identity with an empty token and a username-derived UUID, i.e. join offline-mode
servers as any name. The multi-account switcher for real Microsoft accounts is
legitimate; the offline path is the kind of feature CurseForge moderation treats as
piracy-adjacent, and it also allows impersonation on offline servers.

**Status: removed.** Both files are deleted, `Account.isCracked()` is gone from the model,
the Accounts tab no longer offers it, the four account strings are dropped from `en_us`,
and a stored `"type": "cracked"` entry from an older config is discarded on load rather
than resurrected (see `CHANGELOG.md` → Security). In its place the Advanced tab gained
proxy management — see `docs/design/proxy-management.md`. One cleanup left: the nine
translated locale files still contain the four now-unused offline-account keys.

---

## Build verification round — 2026-10-17 (continuation)

Everything below was executed against the released source, not reasoned about:
Gradle 9.6.1, Temurin JDK 25, `--no-daemon`, all four Stonecutter targets.

| Check | Result |
| --- | --- |
| `:1.21.11:build`, `:26.1:build`, `:26.2:build`, `:26.3:build` | all **BUILD SUCCESSFUL** |
| JUnit suite, per target | **85 tests, 0 failures, 0 skipped** (AuditLedger 8, LocalAddressUtil 7, PolicyRegression 10, PrivacyPolicy 3, ServerProfileJson 7, UpdateMetadata 41, WarningAssessment 9) |
| Release jars | 536–546 KB each, 156 classes, no bundled Minecraft classes |
| `proxy_probe_harness` (run verbatim from `.gitlab-ci.yml`) | **ALL 54 CHECKS PASSED** |
| `adversarial_fixture` (`python -m unittest discover -s tests`) | passes |
| `security_audit` releases checks (fabric.mod.json, LICENSE, no token resources, sha256) | passes for all four jars |

Findings that only exist because the build was actually run:

1. **Packaging: dangling refmap declaration on every 26.x build (fixed).** The shared
   `nulltrace.client.mixins.json` declared `"refmap": "client-nulltrace.refmap.json"`.
   Loom only generates a refmap for obfuscated targets, so 26.1/26.2/26.3 shipped the
   declaration without the file. Mixin then logs, on every user's startup,
   `Reference map 'client-nulltrace.refmap.json' for nulltrace.client.mixins.json could not
   be read. If this is a development environment you can ignore this message … this may be a
   bug` — on the mod whose whole pitch is "nothing hidden". The key is now templated
   (`${mixin_refmap_entry}`): obfuscated targets keep the declaration and ship the 18 KB
   refmap, unobfuscated targets carry no refmap key at all. `security_audit` gained a
   permanent guard (a declared refmap that the jar does not contain fails the pipeline), and
   the guard was negative-tested: re-injecting the declaration into a built 26.3 jar makes it
   exit 1 with a precise message.
2. **CI: the harness job could not have run (fixed).** `proxy_probe_harness` fetched gson
   with `curl` in an `eclipse-temurin:25-jdk` job; that image ships no curl/wget/apt (only the
   alpine `publish` job adds curl). The harness would have failed before compiling anything.
   It now fetches the jar with the JDK itself via single-file source execution, verified to
   produce byte-identical bytes (sha256 `57928d6e…`) and run green as extracted from the YAML.
3. **Remaining from this round:** the DNS resolve/connect race in the local-address guard is
   still only documented, not closed (see item 4), and no end-to-end in-game run has been
   performed here — the client was never launched, so mixin application, the warning screen and
   the proxy screens are verified by compilation, jar inspection and unit tests only.

---

## Suggested sequencing

| Order | Work | Why first |
| --- | --- | --- |
| 1 | Items 1–6 (P0) | Trust: the tests, the phone-home, the integrity alarm, the guard coverage, the counts, the copy |
| 2 | Items 11–13 (P2) as they are touched | Cheap while the P0 edits are fresh; the `SpoofSettings` migration tests pay for themselves immediately |
| 3 | Item 7 + 8 (P1) | The two that make the privacy claim actually hold on a busy server |
| 4 | Item 9, then P3 items 1–3 | Self-check, then the UI that makes the model legible |
| 5 | P3 4–7, legal item | Roadmap |
