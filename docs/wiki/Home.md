# NullTrace

**Client-side Minecraft privacy and fingerprinting protection for Fabric.**

NullTrace gives you control over what your Minecraft client tells a server. It removes common mod
fingerprints, limits tracking through server resource packs, and — the part that matters most —
makes every privacy decision **inspectable**: `/nulltrace audit` explains what was blocked, what was
allowed, and why.

It is a fork of [OpSec](https://github.com/aurickk/OpSec) by [aurickk](https://github.com/aurickk),
developed independently with its own protections and multi-version builds.

> [!IMPORTANT]
> Official downloads are on [CurseForge](https://www.curseforge.com/minecraft/mc-mods/nulltrace) and
> [GitLab Releases](https://gitlab.com/international-duck-incorporated/NullTrace/-/releases).
> Treat JARs from anywhere else as untrusted.

![NullTrace protection settings](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/config-protection.jpg)

## What it protects

| Surface | What NullTrace does |
| --- | --- |
| Client identity | Optionally reports a **vanilla** client brand and filters mod / client-identification payloads, including ViaVersion and ViaFabricPlus identity details |
| Mod channels | Blocks custom-payload channels registered by installed mods; a whitelist can allow specific mods |
| Known packs | Blocks the mod-list probe built on known resource-pack ids |
| Translation probes | Answers "do you have this translation key?" without revealing which mods you run |
| Keybind probes | Reports default keybinds instead of your real bindings |
| Resource packs | Per-account pack cache isolation, local-address URL blocking, and optional lang-only stripping of server packs |
| Client information | Normalizes locale, view distance, chat visibility, particles and skin parts |
| Server cookies / transfers | Blocks server-driven cookies and cross-server transfers |
| Telemetry | Stops Minecraft's own telemetry to Mojang |
| Shader overrides | Strips server-pack shaders aimed at installed, non-whitelisted mods |

## What it does **not** do

NullTrace does **not** make you anonymous or undetectable. It does not hide your IP address, your
account identity, your play style, or every protocol-level difference between your client and vanilla.
It is a privacy tool, not an anti-cheat bypass. Some servers and mods need custom channels, signed
chat, resource packs, cookies or transfers — blocking those can break features or prevent a
connection entirely. Use the whitelist and per-server profiles deliberately.

## Where to go next

| Page | What is in it |
| --- | --- |
| [Installation](Installation) | Requirements, install steps, first launch |
| [Configuration](Configuration) | Every tab and option, with screenshots |
| [Commands](Commands) | `/nulltrace audit`, `profile`, `info`, `channels` with real output |
| [Privacy model](Privacy-model) | Surfaces, profiles, whitelist modes, what a decision means |
| [Screenshots](Screenshots) | The full first-run set: title, config, world, commands |
| [Troubleshooting](Troubleshooting) | Known behaviours that look like bugs, and what to do |
| [Development](Development) | Build, test, harnesses, CI, review documents |

## Project

- Source, issues and releases: [gitlab.com/international-duck-incorporated/NullTrace](https://gitlab.com/international-duck-incorporated/NullTrace)
- License: GPL-3.0-only
- Current release: 2.2.0 (Minecraft 1.21.11, 26.1, 26.2, 26.3)
