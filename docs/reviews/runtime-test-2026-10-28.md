# NullTrace runtime test — 2026-10-28

First in-client run of the 2.2.0 tree (`main` @ `a10711e` + the review fixes) on 1.21.11.
Everything below happened on this machine; log excerpts are verbatim.

The question was whether the protections actually bind and behave at runtime. They do.

## Environment

| Item | Value |
| --- | --- |
| Host | Debian 13, 2 vCPU, ~1.9 GB RAM + 3 GB swap, **no GPU** |
| JDK | Temurin **21.0.13+9** (the 1.21.11 target toolchains to 21) |
| Gradle / Loom | 9.6.1 / 1.17.11, `:1.21.11:runClient` |
| Graphics | Xvfb 854×480 + Mesa **llvmpipe 25.0.7** (software), GL 4.5 core |
| Client | Minecraft **1.21.11**, Fabric Loader 0.19.3, **53 mods** |
| Heap | 900 MB for the game JVM, 512 MB for the Gradle launcher |

Optimisation mods loaded alongside (all 1.21.11 builds): Sodium 0.8.15-beta.1,
Lithium 0.21.4, FerriteCore 8.2.0, ImmediatelyFast 1.14.3, EntityCulling 1.11.3,
Krypton 0.2.10, C2ME 0.4.0-alpha.28. Vanilla settings pushed to the floor:
render distance **2**, simulation 5, fast graphics, no AO, no clouds, no entity
shadows, particles minimal, mipmaps 0, 20 fps cap.

## What ran, in order

1. Boot to title screen — NullTrace's own init line, no mixin errors.
2. Multiplayer screen — the **NullTrace** button renders next to "Play Multiplayer".
3. Config GUI — all five tabs, every toggle present and correctly initialised.
4. Singleplayer world created and entered (`nt-test`), survival, render distance 2.
5. `/nulltrace`, `/nulltrace audit`, `/nulltrace channels`, `/nulltrace info`,
   `/nulltrace profile`, `/nulltrace profile MAXIMUM_PRIVACY` — all executed in-world.
6. Second run with **Spoof as Vanilla = ON** and **Debug Command = ON**, same world.

Screenshots are in `assets/runtime-2026-10-28/`.

## Results

### It runs, and stays up

```
[Render thread/INFO] (nulltrace) NullTrace v2.2.0 - Privacy protection for Minecraft
[Render thread/INFO] (nulltrace) Protecting against: TrackPack, Key Resolution Exploit, Client Fingerprinting
[Render thread/INFO] (nulltrace) NullTrace client protection initialized
```

Two full sessions (title → config GUI → world → commands), no mixin failures, **no crash
reports**, and nothing in the log from NullTrace above DEBUG. The only errors are headless
artefacts: no audio device (`Failed to open OpenAL device`), no narrator native
(`libflite.so`), `X11: Standard cursor shape unavailable`. Steady-state RSS was ~980 MB with
Sodium + C2ME on llvmpipe, which is why the swap mattered.

### The audit ledger is real, and the modes differ

Default config (AUTO whitelist, no spoofing), in a singleplayer world:

```
Server: local:e    Profile: STANDARD    Client brand: EXPOSED
Mod channels: 0 blocked / 9 allowed      Known packs: 2 blocked / 0 allowed
Translation probes: 8118 blocked / 0 allowed
Remaining exposure: 11
```

Same world, `spoofAsVanilla = true`:

```
Client brand: BLOCKED
Mod channels: 9 blocked / 0 allowed      Remaining exposure: 0
```

`/nulltrace channels` shows the filter resolving channels to their owning mods and marking
each one:

```
Fabric Screen Handler API (v1) (fabric-screen-handler-api-v1):
  ✗ fabric-screen-handler-api-v1:open_screen
Fabric API (fabric-api):
  ✗ fabric:extended_block_state_particle_effect_sync
  ✗ fabric:recipe_sync   ✗ fabric:attachment_sync_v1
  ✗ fabric:accepted_attachments_v1   ✗ fabric:registry/sync
  ✗ fabric:custom_ingredient_sync
c (c):
  ✗ c:version   ✗ c:register
Total: 9 channels      ✓ = whitelisted, ✗ = blocked
```

`/nulltrace info` reports the registry it built: **10 mods tracked, 7389 vanilla
translation keys, 8118 total, 60 keybinds, 2 known packs** — and `8118 blocked` above is
exactly that total, i.e. in vanilla mode every non-vanilla-key lookup is being intercepted.
That is the key-resolution protection doing what the README claims.

### Profile switching works end to end

```
/nulltrace profile MAXIMUM_PRIVACY
  -> Profile for local:e set to MAXIMUM_PRIVACY
/nulltrace audit
  -> Profile: MAXIMUM_PRIVACY ... 36 blocked, 0 allowed
```

and it persists as a per-server entry keyed on the server identity:

```json
"serverProfiles": { "local:e": { "mode": "MAXIMUM_PRIVACY", ... } }
```

(`local:e` is the integrated-server pseudo-address, so the singleplayer case is degenerate —
it does not exercise the host-key fix on a real server, see "not covered" below.)

### Two small things worth fixing

1. **The help text advertises commands that are hidden.** `/nulltrace info` and
   `/nulltrace channels` require `debugCommand`, but `showHelp` lists them unconditionally, so
   with the default config the user is told about two commands that answer with
   `Incorrect argument for command at position 10`. Either mark them `(debug)` in the help
   text or drop them from it. (The failure is graceful, at least — no exception, just a
   Brigadier syntax error shown in chat.)
2. **`Client brand: EXPOSED` on a local world** is technically true but reads badly in a
   screenshot — a server can't see anything in singleplayer. Consider rendering the audit as
   "singleplayer — nothing sent" when `hasSingleplayerServer()`.

### Not covered by this run

* **The per-server profile key fix has no runtime coverage yet.** `local:e` is not a host
  name, so `getHostString()` was never compared against a real DNS name. That needs a
  dedicated server joined by hostname *and* by IP.
* **Resource-pack paths** (local-URL blocking, per-account cache isolation, pack strip) never
  fired: an integrated server pushes no packs, hence `Resource-pack requests: 0`. The
  `require = 0` `DownloadQueueMixin` warning from the build is still unproven at runtime.
  This is the next thing to test — a real Fabric server handing the client a pack, plus one
  redirecting to `http://127.0.0.1:<port>/`.
* **Chat signing** — no signed-chat server, and no account (the client ran as the offline
  dev profile `Player233`/`Player991`).
* **Account switching** (review finding 2) — not exercised at all.
* **`ComponentSerializationMixin`** binds without complaint (no mixin error at startup), but
  I did not verify that component-probe tagging actually happens; that needs a server sending
  a translatable component containing a mod key.

## Reproducing

```bash
# JDKs: Temurin 21 (/opt/jdk) — 25 only needed for the 26.x targets
export GRADLE_USER_HOME=/var/tmp/gradle-home JAVA_HOME=/opt/jdk/jdk-21.0.13+9
Xvfb :99 -screen 0 854x480x24 & export DISPLAY=:99
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./gradlew :1.21.11:runClient
```

Extra mods go in `versions/1.21.11/run/mods`; `run/options.txt` and
`run/config/nulltrace.json` were pre-seeded for the low-end settings and the two configs
tested here. Note that only `/home/user` survives between sessions on this box — the JDKs,
Gradle caches and Xvfb all have to be reinstalled.
