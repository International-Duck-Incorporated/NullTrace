# NullTrace Roadmap

NullTrace is a Minecraft client privacy layer focused on client identity protection, fingerprint detection, tracking defenses, resource-pack protection, and user-visible connection warnings.

## Phase 0 - Foundation

- Complete the OpSec to NullTrace rebrand.
- Rename Java packages to `net.nulltrace`.
- Rename resource namespaces to `nulltrace`.
- Set the Fabric mod ID to `nulltrace`.
- Replace old project URLs, commands, assets, and metadata.
- Preserve required upstream attribution and GPLv3 licensing.
- Clean the README and project documentation.

## Phase 1 - Build and Release Infrastructure

Supported Minecraft targets:

- 26.2
- 26.3

Toolchain:

- Java 25
- Stonecutter multi-version builds
- Ubuntu 24.04 GitHub Actions runners

Release behavior:

- Every successful `main` matrix build creates its own prerelease on GitHub.
- Nightly release tags are unique and include the Minecraft version, date, run number, and commit.
- The moving `nightly` tag always points at the latest main commit.
- A `v*` tag creates one stable GitHub Release containing the successful 26.2 and 26.3 artifacts.
- The moving `stable` tag points at the current stable release commit.
- A failed matrix entry must not prevent another successful nightly build from being released.

## Phase 2 - Privacy Core

Build a central connection model:

```text
ConnectionContext
├── server address
├── resolved addresses
├── protocol version
├── installed mods
├── exposed channels
├── exposed packs
├── exposed translations
├── client brand
├── detected probes
└── risk state
```

Build a central policy engine with `ALLOW`, `WARN`, `BLOCK`, and `LOG` outcomes.

## Phase 3 - Fingerprint Surface Engine

Track potentially observable client properties:

- Client brand
- Plugin/custom channels
- Known packs
- Translation keys
- Keybind registrations
- Resource-pack behavior
- Shader overrides
- Component resolution
- Protocol behavior
- Timing-sensitive behavior

Every observation should be attributable to a specific detection rule.

## Phase 4 - Connection Warning

Before connecting to a server, audit the current client state and display a warning when meaningful fingerprint exposure is detected.

Example flow:

```text
Join Server
    |
    v
NullTrace Audit
    |
    +-- Installed detectable clients
    +-- Exposed channels
    +-- Exposed packs
    +-- Translation surfaces
    +-- Other fingerprint signals
    |
    v
Privacy Warning
    |
    +-- Cancel
    +-- Connect Anyway
```

The warning should show the server, affected components, risk level, blocked probes, and remaining exposure.

## Phase 5 - Protection Improvements

Strengthen the inherited protections:

- Vanilla client identity profile
- Fabric identity profile
- Channel filtering
- Known-pack filtering
- Translation-probe protection
- Resource-pack sandboxing
- Private/local address protection
- Redirect validation
- Shader override protection
- Cache isolation
- Telemetry protection

## Phase 6 - Detection Intelligence

Correlate multiple weak signals into a single audit result.

Detection areas:

- Translation probes
- Channel probes
- Known-pack probes
- Resource-pack probes
- Component probes
- Protocol anomalies
- Repeated probing

## Phase 7 - Server Profiles

Per-server policies should support:

- Standard
- Strict
- Maximum Privacy
- Custom

Policies should control channels, packs, fingerprint warnings, compatibility exceptions, and audit behavior independently.

## Phase 8 - Audit and Transparency

Provide a user-readable audit interface and `/nulltrace audit` command.

Example:

```text
NULLTRACE AUDIT

Server: example.com

Client Brand          BLOCKED
Channels              12 blocked / 2 allowed
Known Packs           4 blocked
Translation Probes    8 blocked
Resource Requests     1 blocked
Local Requests        0
Shader Overrides      3 stripped

Current exposure: 2 surfaces
Risk: MEDIUM
```

Include persistent logs, server history, exportable reports, and reasons for every protection decision.

## Phase 9 - Adversarial Test Server

Maintain a dedicated test environment that exercises every supported fingerprint surface:

```text
NullTrace Test Server
├── brand probe
├── channel probe
├── translation probe
├── keybind probe
├── known-pack probe
├── resource-pack probe
├── redirect probe
├── component probe
└── combined fingerprint test
```

Every newly discovered fingerprint vector should become a regression test.

## Phase 10 - Compatibility

Replace broad all-or-nothing exceptions with capability-based compatibility rules.

Example:

```text
Mod X
├── network channel -> ALLOW
├── translation keys -> BLOCK
├── known packs -> BLOCK
└── shader overrides -> BLOCK
```

## Phase 11 - Supply Chain and Security

- Reproducible builds where practical
- SHA-256 release checksums
- Release signing where practical
- Dependency auditing
- License checks
- Generated JAR inspection
- CI checks for unexpected network behavior
- No NullTrace telemetry

## Phase 12 - 1.0 Milestone

NullTrace 1.0 should contain:

- Complete rebrand
- 26.2 support
- 26.3 support
- Working nightly releases
- Working stable releases
- Central detection and policy engine
- Connection warning system
- Fingerprint audit
- Migrated OpSec protections
- Regression test suite
