# Commands

Everything is under `/nulltrace`. Two subcommands are always available — `audit` and `profile` —
because privacy has to be inspectable without turning on diagnostics. `info` and `channels` need
**Misc → Debug Command**.

`/nulltrace` on its own prints the list, marking the two that are currently hidden:

```
NullTrace Commands

/nulltrace audit         - Show the current connection privacy audit
/nulltrace profile [mode] - Show or set this server's privacy profile
/nulltrace info [mod]    - Show tracked mods or details for a specific mod  (require Debug Command)
/nulltrace channels      - Show all tracked network channels  (require Debug Command)
```

![Command help with the audit output behind it](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/command-help.jpg)

## `/nulltrace audit`

The main window into the policy. It answers three questions: which surfaces are blocked, what the
client still exposes, and why each decision was made.

```
NullTrace AUDIT
Server: play.example.com
Profile: STANDARD
Client brand: BLOCKED
Mod channels: 9 blocked / 0 allowed
Known packs: 2 blocked / 0 allowed
Translation probes: 8118 blocked / 0 allowed
Resource-pack requests: 0 blocked / 0 allowed
Local requests: 0 blocked / 0 detected
Shader overrides stripped: 0
Remaining exposure: 0
Recorded decisions: 36 blocked, 0 allowed, 0 warnings
```

| Line | Meaning |
| --- | --- |
| `Server` | The host name the profile is keyed on. On a singleplayer world this reads `local:e (integrated server)` |
| `Profile` | `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY` or `CUSTOM` — see [Privacy model](Privacy-model) |
| `Client brand` | `BLOCKED` when a vanilla brand is reported, `EXPOSED` when your real one is sent, `not sent (singleplayer)` on an integrated server, where no brand packet exists at all |
| `Mod channels` | Blocked/allowed custom-payload channels |
| `Known packs` | The mod-list probe built on known pack ids |
| `Translation probes` | Keys a server asked about; blocked means it learned nothing about which mods you run |
| `Resource-pack requests` | Server-pack downloads evaluated by the local-URL guard |
| `Local requests` | Requests blocked or flagged because they pointed at a local address |
| `Remaining exposure` | Count of surfaces still exposed on this connection. `0` means everything above is blocked |
| `Recorded decisions` | Ledger totals for the session (blocked / allowed / warnings) |

Below the summary comes the decision tail — the newest decisions with their reason, e.g.
`CLIENT_BRAND: client brand (Blocked by the active NullTrace policy)`. Consecutive repeats are folded
into one line with a count, so one noisy surface cannot push everything else out of the window.

## `/nulltrace profile [mode]`

Without an argument it shows the server and the active profile. With one it switches the profile for
**this server only** and saves it immediately:

```
/nulltrace profile MAXIMUM_PRIVACY
  -> Profile for local:e set to MAXIMUM_PRIVACY
```

Valid modes: `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY`, `CUSTOM` (tab completion works).

![Profile switch to MAXIMUM_PRIVACY](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/profile-maximum-privacy.jpg)

## `/nulltrace channels` — Debug Command

Every tracked channel, grouped by the mod that registered it, with the whitelist verdict
(`✓` = whitelisted, `✗` = blocked). On a 53-mod install with spoofing on:

```
Fabric Screen Handler API (v1) (fabric-screen-handler-api-v1):
  ✗ fabric-screen-handler-api-v1:open_screen
Fabric API (fabric-api):
  ✗ fabric:extended_block_state_particle_effect_sync
  ✗ fabric:recipe_sync
  ✗ fabric:attachment_sync_v1
  ✗ fabric:accepted_attachments_v1
  ✗ fabric:registry/sync
  ✗ fabric:custom_ingredient_sync
c (c):
  ✗ c:version
  ✗ c:register

Total: 9 channels
✓ = whitelisted, ✗ = blocked
```

This is the command to run when a mod feature stops working with spoofing on: find the channel, see
which mod owns it, then either whitelist that mod (**Whitelist → CUSTOM**) or accept the breakage.

![Tracked channels and their owners](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/channels.jpg)

## `/nulltrace info [mod]` — Debug Command

Registry summary, or per-mod detail if you pass an id or display name:

```
NullTrace Mod Registry
Total mods tracked: 10
Vanilla translation keys: 7389
Server pack keys: 0
Total translation keys: 8118
Total keybinds: 60
Total mod shaders: 0
Total known packs: 2

Mods with trackable content:
Fabric Registry Sync (v0) (10 keys)
...
```

Sanity check: `Total translation keys` should equal the `Translation probes` count in the audit with
spoofing on — every mod key is intercepted. If it does not, protection is not fully applied on that
connection.

![Registry summary](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/audit-vanilla-spoof.jpg)
