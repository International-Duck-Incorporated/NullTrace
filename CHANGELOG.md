# Changelog

All notable changes to NullTrace are documented here.

## [Unreleased]

### Added
- Added the central `ConnectionContext` / `PrivacyPolicy` / `AuditLedger` model with explainable `ALLOW`, `BLOCK`, `WARN`, and `LOG` decisions.
- Added `/nulltrace audit` and per-server `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY`, and `CUSTOM` profiles.
- Added a pre-connection privacy warning with Cancel and Connect Anyway actions.
- Added configurable client-information normalization for language, view distance, chat visibility, and particles.
- Added an adversarial probe fixture and policy regression tests.
- Added CI dependency reporting, JAR inspection, SHA-256 checksum generation, and checksum release assets.

### Security
- Removed the redirect-limit raw `Socket` side effect; rejected redirect hops are now checked and never opened.
- Added AES-GCM encrypted-at-rest storage for session and refresh tokens; exports are redacted and require re-authentication.
- Routed account HTTP requests through the same explicit Minecraft proxy setting as gameplay.

### Changed
- Updated the roadmap, README, and release pipeline to describe the GitLab repository and all four supported targets.

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
