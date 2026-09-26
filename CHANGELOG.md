# Changelog

All notable changes to NullTrace are documented here.

## [Unreleased]

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
