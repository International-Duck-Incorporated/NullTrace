# Installation

## Requirements

| Thing | Version |
| --- | --- |
| Minecraft (Fabric) | **1.21.11**, **26.1**, **26.2** or **26.3** |
| Fabric Loader | 0.16 or newer (built and tested against 0.19.3) |
| Fabric API | required |
| Java | 21 for 1.21.11, 25 for the 26.x targets — the same Java your Minecraft version needs |
| Mod Menu | optional, but it is the easiest way into the settings |

NullTrace is **client-side only**. It does not need to be on the server, and a server cannot tell
whether it is installed beyond the protections it changes.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Put `NullTrace-<version>-<mc>.jar` in your `mods/` folder together with
   [Fabric API](https://modrinth.com/mod/fabric-api).
3. Start the game. The title screen footer should list NullTrace in the mod count.

Get the jar from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/nulltrace) or
[GitLab Releases](https://gitlab.com/international-duck-incorporated/NullTrace/-/releases). Every
GitLab release ships `SHA256SUMS`, a per-file `.sha256` and a `release-manifest.json`:

```bash
sha256sum -c NullTrace-2.2.0-1.21.11.jar.sha256
```

## First launch

1. Open the **NullTrace** button on the multiplayer screen (or Mod Menu → NullTrace).
2. The defaults are already protective: per-account pack cache isolation, local-URL blocking,
   translation and keybind probe protection, telemetry off, cookies and transfers blocked.
3. **Spoof as Vanilla** is *off* by default and the whitelist sits in **AUTO**, so mods that talk to
   servers keep working. Turn spoofing on for a "says nothing" client, and expect some mod features
   and some servers to stop working when you do.
4. Join a server and run `/nulltrace audit` to see what is actually happening on that connection.

![Title screen with NullTrace loaded](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/title-screen.jpg)

## Updating

At startup the in-game updater asks **CurseForge** (via CFWidget) and **GitLab** in parallel, once
per session, and offers the newest compatible stable release. The **Download** button opens the
official download page in your browser — it never replaces your jar by itself. Both checks can be
switched off under **Advanced → Network**.

The separate integrity check compares your installed jar against the GitLab release checksums. It is
an **advisory speed bump**, not a guarantee: the warning is dismissible, and anyone who can modify
your jar can remove the check. See [Troubleshooting](Troubleshooting#jar-integrity-warnings).
