# Changelog

All notable changes to NullTrace are documented here.

## [Unreleased]

### Added
- Added proxy management on a new **Advanced** config tab: paste an endpoint, keep a list, select the active one, reorder or remove entries, and probe the selected endpoint for reachability (`docs/design/proxy-management.md`).
- Added the central `ConnectionContext` / `PrivacyPolicy` / `AuditLedger` model with explainable `ALLOW`, `BLOCK`, `WARN`, and `LOG` decisions.
- Added `/nulltrace audit` and per-server `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY`, and `CUSTOM` profiles.
- Added a pre-connection privacy warning. It now reports each surface as shown/hidden in plain language, offers `Connect` and `Protect & connect`, a details panel with the raw counters, a settings shortcut, and a per-server warning mute (see `docs/design/privacy-warning-redesign.md`).
- Added configurable client-information normalization for language, view distance, chat visibility, and particles.
- Added an adversarial probe fixture and policy regression tests.
- Added CI dependency reporting, JAR inspection, SHA-256 checksum generation, and checksum release assets.
- Added CurseForge update checks through the public CFWidget metadata API, alongside GitLab, with compatible stable Fabric release selection and independent source failure handling.
- Added regression tests for release metadata, Minecraft-version filtering, and semantic version comparison.

### Security
- Removed the offline ("cracked") account path. The account switcher now only swaps in authenticated Microsoft sessions, and stored entries written by older versions with `type: "cracked"` are dropped instead of restored. Do not publish JARs built from older revisions for CurseForge or any other distribution.
- Removed the redirect-limit raw `Socket` side effect; rejected redirect hops are now checked and never opened.
- Added AES-GCM encrypted-at-rest storage for session and refresh tokens; exports are redacted and require re-authentication.
- Routed account HTTP requests through the same explicit Minecraft proxy setting as gameplay.

### Changed
- Replaced the "Play through proxy" boolean with a tri-state proxy mode (`Direct` / `Launcher` / `Selected`). Configurations written by older versions migrate automatically (`true` → `Launcher`), and the proxy list lives outside `SpoofSettings` so Reset cannot delete saved endpoints.
- Updated the roadmap and release pipeline to describe the GitLab repository and all four supported targets.
- Rewrote the README with official CurseForge and GitLab downloads, current configuration behavior, build/test instructions, and update-check network disclosure.
- The pre-connection warning now derives its rows from the effective per-server `PrivacyPolicy` instead of raw counters, so the screen can no longer disagree with what the protections actually do.

### Fixed
- Prevented older published releases from triggering update notifications or marking the configuration version label as outdated.
- The pre-connection warning no longer interrupts a join that has no remaining exposure (`WarningMode.EXPOSURE_ONLY`, the new default; `WarningMode.ALWAYS` restores the always-show behaviour for users who want a receipt every time).
- The pre-connection warning no longer claims installed mods are "potentially identifiable" when it only counted installed mods, and no longer presents reassuring numbers ("0 exposed", "remaining exposure: 0") in the same voice as actual exposure.
- The pre-connection warning resolves its strings through the private `NullTraceLang` table, so the other bundled locales are used and server resource packs cannot rewrite the text.
- Escape on the pre-connection warning now returns to the server list instead of dropping the player on an unrelated screen.

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
