# Privacy model

NullTrace is built around one idea: a privacy tool you cannot inspect is a promise, not a feature.
So the policy is a small, immutable object, every decision is recorded in a bounded ledger, and
`/nulltrace audit` shows you the result for the connection you are on.

## Surfaces

A **surface** is a channel through which a server can learn something about your client. The list is
finite on purpose — it gives every protection a shared vocabulary and keeps audit output stable:

| Surface | What a server can learn |
| --- | --- |
| `CLIENT_BRAND` | Brand string — the easiest way to spot a modded client |
| `MOD_CHANNEL` | Custom-payload channel registrations; a mod list in disguise |
| `KNOWN_PACK` | The mod-list probe built on known resource-pack ids |
| `TRANSLATION_PROBE` | "Do you have key X?" — reveals installed mods without any channel |
| `KEYBIND_PROBE` | Bindings that only exist in certain mods |
| `RESOURCE_PACK` | Server-pushed packs: cache paths, local URLs, shaders |
| `LOCAL_REQUEST` | Pack URLs pointed at loopback / link-local / your LAN |
| `SHADER_OVERRIDE` | Shader overrides targeted at modded renderers |
| `COMPONENT_PROBE` | Serialized component tricks used to fingerprint the client |
| `CLIENT_INFORMATION` | Locale, view distance, chat visibility, particles, skin parts |
| `SERVER_COOKIE` | Server-driven cookies |
| `SERVER_TRANSFER` | Cross-server transfers |
| `PROTOCOL_BEHAVIOR` | Timing and ordering differences vs. vanilla |

Each surface resolves to one **action** per connection:

| Action | Meaning in the audit |
| --- | --- |
| `BLOCK` | The feature is refused; the server learns nothing |
| `ALLOW` | The feature passes through; the surface is counted in **Remaining exposure** |
| `WARN` | Allowed, but recorded because it can identify the client |
| `LOG` | Recorded for audit; no blocking rule is active |

A decision carries the surface, the subject (channel id, translation key, host…), the action, and a
human-readable reason — that is exactly the line the audit prints.

## Profiles

Profiles are per **server**, keyed by the host name you connected with (not the resolved IP, so DNS
changes cannot silently downgrade you). `STANDARD` means "use the global settings"; the others
override them:

| Profile | Behaviour |
| --- | --- |
| `STANDARD` | Whatever the Protection tab says |
| `STRICT` | Blocks brand, channels, known packs, translation and keybind probes, local requests, shaders, component probes, client information, cookies, transfers, protocol differences. Resource packs are allowed but recorded (`WARN`) |
| `MAXIMUM_PRIVACY` | Blocks **every** surface. This is the "we will not speak first" profile: vanilla traffic still works, everything optional is refused |
| `CUSTOM` | Starts from `LOG` for every surface, then applies your per-surface choices (normalize client info, block local requests, cookies, transfers) plus your allowed-channel list |

Because profiles are stored per server, you can run `MAXIMUM_PRIVACY` on a public server and leave a
small private server on `STANDARD` where your voice-chat mod needs its channel.

## Whitelist modes

The whitelist decides which **mods** may speak, and it is independent of the profile:

| Mode | Behaviour |
| --- | --- |
| Block all (`OFF`) | Nothing mod-related is exposed. Implied by **Spoof as Vanilla** |
| `AUTO` | Mods that register a network channel are allowed automatically |
| `CUSTOM` | Only the mods you tick are allowed |

Allowed mods identify themselves. A vanilla brand plus a working voice-chat channel is still a
fingerprint — a narrower one, but a fingerprint.

## The ledger

Every decision lands in a bounded, thread-safe ledger (2048 events, cumulative counters that do not
depend on that window). It backs `Remaining exposure`, the `Recorded decisions` totals, and the
decision tail. The tail folds consecutive identical decisions into one line with a count, so a
surface that is evaluated a dozen times per connection cannot hide everything else.

## What "protected" means here

- Protections apply per **connection**. Changing a setting mid-session may need a reconnect before
  every surface honours it; the config screen says so.
- **Singleplayer is not a privacy boundary.** No packet leaves your machine, so NullTrace does not
  pretend a brand was exposed there. It also means a singleplayer session is not a good way to test
  whether the protections work.
- **Hosting a LAN world disables probe protection for you** (translation/keybind), because there is
  no remote server to leak to. It is on the [Troubleshooting](Troubleshooting) page too, because it
  surprises people who test that way.
- **The proxy setting does not cover the game connection.** It covers NullTrace's own HTTP traffic
  (update checks, checksum lookup, account requests). A server still sees your IP.

## Verification

The policy layer is Minecraft-free, so it is unit-tested rather than only observed: 113 tests per
build target cover policy resolution, per-server profiles, channel decisions, chat signing, the
audit ledger, warning assessment, update metadata, and the address guard.
