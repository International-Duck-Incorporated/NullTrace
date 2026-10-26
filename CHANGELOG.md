# Changelog

All notable changes to NullTrace are documented here.

## [Unreleased]

### Fixed
- Account tokens could not be decrypted: the vault encoded with the URL-safe base64 alphabet and
  decoded with the standard one, which throws for any value containing `-` or `_` (about 98 % of
  ciphertexts). Every restart silently marked saved accounts invalid and asked for a fresh login.
  Decoding now accepts both alphabets. Found by the new `tests/vault` harness, which pins an
  envelope that actually contains a URL-safe character.
- Resource-pack local-address blocking switched itself off while connected to a server on a
  private address: a LAN server could point the client at `127.0.0.1:<port>` and the guard
  short-circuited. Loopback, link-local, multicast and reserved targets are now blocked
  regardless of where the server lives; RFC 1918 / ULA / CGNAT targets stay reachable when the
  server shares that network.
- A single malformed required resource-pack push consumed the one-shot "ask before applying"
  prompt, so every later pack was stripped without asking.
- The ViaFabricPlus identity payload (`vv:mod_details`, `vv:app_details`, `vv:proxy_details`) was
  blocked only by the Netty pipeline handler. Both outbound filter paths now share one policy
  (`ChannelDecision`), and a handler that cannot be installed raises a visible warning instead of
  a debug line.
- A proxy change did not reach the update and integrity checks until the game restarted: both
  cached their own `HttpClient`. `HttpClients` now owns a single client and drops it when the
  proxy configuration changes; account requests use it too, so the Direct / Launcher / Selected
  mode applies to them.
- `ResourcePackGuard.clearCacheFolder` leaked a directory handle per call (`Files.walk` was never
  closed) — on Windows, locks on the files it was trying to delete.
- Account tokens are now bound to their account with AES-GCM additional authenticated data, so a
  token blob moved onto another account entry fails to decrypt instead of logging in as the wrong
  account. `v1:` values remain readable and are rewritten on the next save.

### Changed
- Every settings toggle wrote the configuration file and rebuilt the mod dependency closure on
  the render thread (the config screen calls `save()` from ~30 places). Writes are now deferred
  to once per client tick, the closure is rebuilt only when whitelist membership changed, and
  shutdown flushes synchronously.
- `ConnectionContext.currentExposure` was populated with a raw channel count that ignored the
  active policy and was never read; removed.

### Added
- `tests/vault` — JDK-only harness for the token vault (26 checks) and a `vault_harness` CI job.
- `ChannelDecisionTest` — the outbound payload policy matrix, including the Via identity block.
- `tools/ci/` checks that run without Gradle: `check_metadata.py` (version, tag, changelog, target and README-table agreement), `check_sources.py` (mixin config against source for every template variant, entrypoints, credential scan), `inspect_jar.py` (18 jar checks), `gen_sums.py` (checksums, `SHA256SUMS`, `release-manifest.json`), `lint_gitlab_ci.py` (CI structure, `rules: if` simulation, shell syntax) and `selftest.py` (assertions that each check fails when it should).
- `tools/ci/preflight.sh` runs the whole cheap gate locally, optionally including a build, jar audit and checksums.
- CI publishes a `release-manifest.json` recording the commit, pipeline, per-file hashes and the Minecraft/Java range of each artifact.

### Changed
- CI is staged fail-fast: a Python-only `preflight` stage blocks every Gradle job, the four-target matrix is limited to `main` and release tags, merge requests and feature branches build one target, Gradle caches are keyed per target, and superseded pipelines are cancelled.
- Test jobs publish JUnit XML; the dependency report is produced by the 26.3 build instead of a separate Gradle start in a security stage; `publish` is serialised with `resource_group`.
- A release now requires the build matrix, both test matrices, the proxy harness, the jar audit and the preflight tag check; previously `publish` waited only for the build artifacts.

### Fixed
- SHA-256 sidecars embedded the `release/` directory (`sha256sum release/x.jar > release/x.jar.sha256`), so `sha256sum -c` failed for anyone who downloaded the pair. They are now written with bare file names and LF endings and verified from inside the release directory.
- `SHA256SUMS` and the provenance manifest were promised by the release notes but produced by no job.
- Release API failures were ignored (`|| true`), so a release could be created without assets while the pipeline stayed green.
- Feature branches and merge requests produced no pipeline at all.

## [2.1.1] - 2026-10-17

Version note: an earlier build was published as 2.1.0 before the proxy manager, the redesigned
connection warning and the test suite landed. 2.1.1 supersedes it, and installations on 2.1.0
are offered 2.1.1 normally — the updater only prompts when the published version is newer.

### Added
- Added unit tests for the warning decision (`WarningAssessment`), the per-server policy profiles, the audit ledger and the local-address guard, plus a standalone proxy harness (54 expectations) that runs in CI. The previous "adversarial fixture" asserted only that a hardcoded list of probe names was stable and never executed NullTrace code.
- Added proxy management on a new **Advanced** config tab: paste an endpoint, keep a list, select the active one, reorder or remove entries, and probe the selected endpoint for reachability (`docs/design/proxy-management.md`).
- Added the central `ConnectionContext` / `PrivacyPolicy` / `AuditLedger` model with explainable `ALLOW`, `BLOCK`, `WARN`, and `LOG` decisions.
- Added `/nulltrace audit` and per-server `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY`, and `CUSTOM` profiles.
- Added a pre-connection privacy warning. It now reports each surface as shown/hidden in plain language, offers `Connect` and `Protect & connect`, a details panel with the raw counters, a settings shortcut, and a per-server warning mute (see `docs/design/privacy-warning-redesign.md`).
- Added configurable client-information normalization for language, view distance, chat visibility, and particles.
- Added an adversarial probe fixture and policy regression tests.
- Added CI dependency reporting, JAR inspection, SHA-256 checksum generation, and checksum release assets.
- Added CurseForge update checks through the public CFWidget metadata API, alongside GitLab, with compatible stable Fabric release selection and independent source failure handling.
- Added regression tests for release metadata, Minecraft-version filtering, and semantic version comparison.
- Release publishing refuses a tag that disagrees with `mod_version` in `gradle.properties` (a mismatch makes the release invisible to the updater, which only offers strictly newer versions), warns when `CHANGELOG.md` has no section for the tag, and asserts that every shipped jar reports the tag's version before uploading.
- Release inspection (`security_audit`) now fails the pipeline when a mixin config names a refmap file the jar does not contain — the packaging drift that made the 26.x builds log a Mixin warning on every startup.

### Security
- Removed the offline ("cracked") account path. The account switcher now only swaps in authenticated Microsoft sessions, and stored entries written by older versions with `type: "cracked"` are dropped instead of restored. Do not publish JARs built from older revisions for CurseForge or any other distribution.
- Removed the redirect-limit raw `Socket` side effect; rejected redirect hops are now checked and never opened.
- Added AES-GCM encrypted-at-rest storage for session and refresh tokens; exports are redacted and require re-authentication.
- Routed account HTTP requests through the same explicit Minecraft proxy setting as gameplay.

### Changed
- The startup update check and jar-integrity check now use the configured proxy instead of a direct connection, and both can be switched off (Advanced → Network). Previously a player who routed the game through a proxy still had their real address sent to GitLab and the CurseForge metadata API on every launch.
- The jar-integrity screen no longer claims the client may be "compromised". It reports packaging drift in plain language ("this jar is not the official release build"), explains what that usually means, and is skipped automatically for non-release versions.
- The pre-connection warning's decision logic moved into a Minecraft-free class so it is covered by unit tests rather than only reachable from a running client.
- Replaced the "Play through proxy" boolean with a tri-state proxy mode (`Direct` / `Launcher` / `Selected`). Configurations written by older versions migrate automatically (`true` → `Launcher`), and the proxy list lives outside `SpoofSettings` so Reset cannot delete saved endpoints.
- Updated the roadmap and release pipeline to describe the GitLab repository and all four supported targets; the roadmap's warning-screen and proxy entries now match the shipped behaviour.
- The README documents the **Advanced** tab (proxy modes, endpoint list and tester, update/integrity toggles) and states explicitly what the proxy does not cover: the game-server connection. The line claiming the update checks bypass the proxy, and the offer of offline accounts, are both gone.
- Rewrote the README with official CurseForge and GitLab downloads, current configuration behavior, build/test instructions, and update-check network disclosure.
- The pre-connection warning now derives its rows from the effective per-server `PrivacyPolicy` instead of raw counters, so the screen can no longer disagree with what the protections actually do.

### Fixed
- The 26.x builds no longer declare a Mixin refmap that the jar does not contain. Minecraft 26.1+ ships unobfuscated, so Loom generates no refmap for those targets and the leftover declaration made Mixin log `Reference map 'client-nulltrace.refmap.json' for nulltrace.client.mixins.json could not be read ... this may be a bug` on every 26.x startup. In obfuscated targets (1.21.11) the declaration is kept and the refmap is still shipped.
- The `proxy_probe_harness` CI job fetched its gson dependency with `curl`, which the `eclipse-temurin` image does not provide, so the job could not have passed in the pipeline even though the harness itself is green. The jar is now fetched with the JDK itself (single-file source execution), with no extra packages.
- Whitelist channel entries are trimmed before being stored, so an id pasted with surrounding whitespace no longer silently fails to match at runtime.
- The audit ledger's counters (`/nulltrace audit`) are cumulative instead of being read out of the bounded 2048-event window, so a flooding server no longer makes every reported number freeze at 2048. `remainingExposure()` now counts allowed events and is documented as such; `allowedSurfaceCount()` reports distinct exposed surfaces.
- The local/private address guard now also rejects IPv6 unique-local (`fc00::/7`), CGNAT (`100.64.0.0/10`), `0.0.0.0/8`, multicast/reserved space and IPv4-mapped IPv6 forms such as `::ffff:127.0.0.1`.
- Prevented older published releases from triggering update notifications or marking the configuration version label as outdated.
- The pre-connection warning no longer interrupts a join that has no remaining exposure (`WarningMode.EXPOSURE_ONLY`, the new default; `WarningMode.ALWAYS` restores the always-show behaviour for users who want a receipt every time).
- The pre-connection warning no longer claims installed mods are "potentially identifiable" when it only counted installed mods, and no longer presents reassuring numbers ("0 exposed", "remaining exposure: 0") in the same voice as actual exposure.
- The pre-connection warning resolves its strings through the private `NullTraceLang` table, so the other bundled locales are used and server resource packs cannot rewrite the text.
- Escape on the pre-connection warning now returns to the server list instead of dropping the player on an unrelated screen.
- Updated the project version metadata to 2.1.1.

## [2.0.2] - 2026-09-26

### Added
- Added explicit filtering for ViaVersion/ViaFabricPlus identity payloads.
- Added resource-pack request identity-header scrubbing.
- Added client-information normalization for common fingerprinting fields.
- Added configurable blocking for server cookies and server transfers.

### Fixed
- Corrected repository metadata and documentation links after the GitLab migration.
- Updated the project version metadata to 2.0.2.

## [2.0.1] - 2026-09-26

### Changed
- Migrated the official project repository from GitHub to GitLab.
- Updated NullTrace-owned documentation and repository links to GitLab.
- Added the project logo to the repository assets.

### Fixed
- Fixed a version-related issue after the 2.0.0 release.
- Removed the obsolete GitHub build badge from the README.

## [2.0.0] - 2026-09-21

### Added
- Stable NullTrace 2.0.0 release.
- Minecraft 1.21.11 support.
- Minecraft 26.1 support.
- Minecraft 26.2 support.
- Minecraft 26.3 support.
- Multi-version build support using Stonecutter.
- Client-side protections for mod-channel fingerprinting.
- Known-pack filtering.
- Translation/key-resolution protection.
- Resource-pack cache isolation.
- Local/private resource-pack URL protection.
- Server resource-pack control modes.
- Server-pack shader override stripping.
- Configurable chat-signing behavior.
- Minecraft telemetry blocking.
- Account/session management.
- Configurable diagnostics and debug alerts.
- Optional `/nulltrace` debug command.
- Integrity checking and update/version checking.
- Expanded configuration UI and documentation.
- In-game configuration screenshots.

### Changed
- Reworked the project branding under the NullTrace name.
- Expanded README documentation and installation instructions.
- Improved protection configuration and whitelist handling.
- Updated the build toolchain and CI/release automation.

### Fixed
- Fixed packet-context and packet-origin handling across Minecraft versions.
- Fixed several channel tracking and fingerprinting edge cases.
- Fixed resource-packet alert thread handling.
- Fixed configuration menu state handling.
- Improved detection alert deduplication.
- Fixed version-range handling for supported Minecraft targets.

## [Previous Development]

NullTrace originated as a fork of OpSec by aurickk. Earlier development focused on client-side privacy, anti-fingerprinting, resource-pack isolation, network-channel filtering, key-resolution protection, telemetry handling, and related compatibility work.

See the Git history for the complete development record.
