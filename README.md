<p align="center">
  <img src="docs/assets/nulltrace-logo.png" alt="NullTrace" width="15%">
</p>

<h1 align="center">NullTrace</h1>

<p align="center">
  A client-side Minecraft privacy and fingerprinting protection mod for Fabric.
</p>

<p align="center">
  <a href="https://modrinth.com/mod/nulltrace"><img alt="Modrinth" src="https://img.shields.io/badge/Modrinth-00AF5C?logo=modrinth&logoColor=white"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/nulltrace"><img alt="CurseForge" src="https://img.shields.io/badge/CurseForge-F16436?logo=curseforge&logoColor=white"></a>
  <a href="https://github.com/International-Duck-Incorporated/NullTrace/releases"><img alt="GitHub Releases" src="https://img.shields.io/github/v/release/International-Duck-Incorporated/NullTrace?display_name=tag&logo=github&logoColor=white"></a>
  <a href="https://github.com/International-Duck-Incorporated/NullTrace/actions"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/International-Duck-Incorporated/NullTrace/release.yml?branch=main&label=build&logo=github"></a>
  <a href="https://github.com/International-Duck-Incorporated/NullTrace/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
</p>

> [!IMPORTANT]
> Download NullTrace only from the official [Modrinth](https://modrinth.com/mod/nulltrace), [GitHub Releases](https://github.com/International-Duck-Incorporated/NullTrace/releases), or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/nulltrace) pages.
>
> Fake download pages and Discord servers have been used to distribute modified or trojanized builds. A file from another source should be treated as unofficial.

## Overview

Minecraft servers can learn more about a client than its username and IP address. Mods can expose network channels, known resource packs, translation keys, keybinds, client brands, resource-pack behavior, and other client-side state.

NullTrace is a client-side privacy layer designed to reduce those fingerprinting surfaces.

It does not make you anonymous. It does not hide your IP address or server-side activity, and it cannot prevent every possible form of fingerprinting. Its goal is to make common client-identification and tracking techniques harder to use.

## Features

| Area                     | Protection                                                                                            |
| ------------------------ | ----------------------------------------------------------------------------------------------------- |
| Client brand             | Optionally report a vanilla-like client brand instead of a Fabric brand                               |
| Network channels         | Conditionally filter mod channels registered with the server                                          |
| Known packs              | Filter mod-injected known-pack identifiers                                                            |
| Key resolution           | Protect against translation/keybind probing used to identify installed mods                           |
| Resource packs           | Isolate server-pack caches per account                                                                |
| Resource-pack URLs       | Block requests and redirects targeting local/private addresses                                        |
| Server pack requirements | Allow required packs to remain downloaded while giving the user control over whether they are applied |
| Mod shaders              | Strip server-pack shader overrides targeting installed, non-whitelisted mods                          |
| Chat signing             | Configure message-signing behavior independently of vanilla defaults                                  |
| Telemetry                | Block Minecraft telemetry sent to Mojang                                                              |
| Accounts                 | Switch between stored Minecraft sessions without restarting the client                                |
| Diagnostics              | Optional alerts, toast notifications, logging, and a debug command                                    |

## Protection Model

NullTrace focuses on client-side information that a server can query or influence.

### Client Brand Spoofing

Servers can inspect the client brand reported during connection. With **Spoof as Vanilla** enabled, NullTrace reports a vanilla-like brand and applies the corresponding protection rules for mod-exposed data.

When the whitelist is active in **AUTO** or **CUSTOM** mode, vanilla spoofing is disabled because explicitly allowing mod functionality means those mods may need to identify themselves.

### Channel Spoofing

Fabric mods can register custom network channels. A server can inspect those channels to determine which mods are present.

NullTrace can filter non-whitelisted mod channels before they are exposed to the server.

* **BLOCK ALL** - block all mod content
* **AUTO** - automatically allow mods that register network channels
* **CUSTOM** - manually choose which installed mods are allowed

### Known-Pack Filtering

Some mods expose pack identifiers through Minecraft's known-pack protocol. NullTrace filters mod-injected entries that are not permitted by the current whitelist.

Vanilla entries are retained.

### Key Resolution Protection

A server can send translatable components containing keys such as `key.attack` or mod-specific translation keys. The resolved result can reveal installed mods or configured keybinds.

NullTrace intercepts this resolution path and prevents untrusted server packets from using those keys as a mod fingerprint.

With **Fake Default Keybinds** enabled, vanilla keybinds resolve to their default values instead of the user's actual bindings.

### Resource-Pack Cache Isolation

Server resource packs are normally cached in a shared download location. A server can use pack behavior to associate otherwise separate Minecraft accounts or client instances.

NullTrace can place downloaded server packs into account-specific cache directories.

### Local URL Protection

A malicious resource-pack URL can redirect a Minecraft client toward local or private network addresses.

NullTrace checks resource-pack destinations and blocks requests that resolve to local/private addresses.

### Server Pack Control

Required server resource packs can normally be applied without giving the player a normal toggle in the resource-pack screen.

NullTrace keeps the pack available while allowing the player to control whether its content is actually applied.

Modes:

| Mode          | Behavior                                                                                   |
| ------------- | ------------------------------------------------------------------------------------------ |
| **MANUAL**    | Vanilla behavior on push; the user can later remove or re-enable the pack                  |
| **ASK**       | Required packs are stripped initially and a confirmation overlay asks whether to load them |
| **ALWAYS ON** | Server packs are stripped initially and can only be enabled manually                       |

The server pack language resources remain available so simple translation-key checks cannot rely solely on whether the full visual pack is currently active.

### Mod Shader Override Stripping

A server resource pack can contain files under another mod's asset namespace. For mods such as Meteor Client, that can replace bundled shaders or other client resources.

NullTrace removes shader overrides targeting installed, non-whitelisted mods while leaving the rest of the server pack intact.

Vanilla assets are not stripped.

### Chat Signing

NullTrace provides three signing modes:

| Mode     | Behavior                              |
| -------- | ------------------------------------- |
| **OFF**  | Remove chat signatures                |
| **AUTO** | Sign only when required               |
| **ON**   | Use normal Minecraft signing behavior |

### Telemetry

When enabled, NullTrace blocks Minecraft telemetry requests to Mojang. This does not change normal gameplay networking.

## Configuration

Open the **NullTrace** button in the multiplayer screen or open the configuration through [Mod Menu](https://modrinth.com/mod/modmenu).

<img width="1465" height="820" alt="NullTrace configuration screen" src="https://github.com/user-attachments/assets/c69a768b-60ac-4f78-9705-184f6c4e4495" />

Changes made while connected to a server may require reconnecting before every protection is active.

### Protection

| Setting                            | Purpose                                                                    |
| ---------------------------------- | -------------------------------------------------------------------------- |
| **Spoof as Vanilla**               | Enable vanilla-style client identity and strict filtering                  |
| **Isolate Pack Cache**             | Separate server-pack caches by account                                     |
| **Block Local Pack URLs**          | Prevent local/private resource-pack destinations                           |
| **Bypass Server Pack Requirement** | Configure MANUAL, ASK, or ALWAYS ON behavior                               |
| **Strip Mod Shader Overrides**     | Remove server shader overrides for protected mods                          |
| **Clear Cache**                    | Delete cached server resource packs                                        |
| **Key Resolution Spoofing**        | Enable key-resolution protection                                           |
| **Fake Default Keybinds**          | Return default vanilla keybind values                                      |
| **Meteor Fix**                     | Disable Meteor's conflicting key-resolution mixin when Meteor is installed |
| **Signing Mode**                   | Configure chat signing                                                     |
| **Disable Telemetry**              | Block Mojang telemetry                                                     |

### Whitelist

| Setting            | Purpose                               |
| ------------------ | ------------------------------------- |
| **Whitelist Mode** | BLOCK ALL, AUTO, or CUSTOM            |
| **Installed Mods** | Select individual mods in CUSTOM mode |

Whitelisting is necessary for mods that intentionally communicate with servers. Examples include voice-chat or map-related functionality.

<img width="853" height="478" alt="NullTrace whitelist settings" src="https://github.com/user-attachments/assets/6ae423de-dd98-47c1-a617-f6df747c9293" />

### Diagnostics

| Setting            | Purpose                                            |
| ------------------ | -------------------------------------------------- |
| **Show Alerts**    | Show tracking detections in chat                   |
| **Show Toasts**    | Show popup notifications                           |
| **Log Detections** | Write detections to the game log                   |
| **Debug Alerts**   | Report all probed keys, including unchanged values |
| **Debug Command**  | Enable `/nulltrace`                                |

### Accounts

The built-in account manager supports session-based login, account switching, token validation, import/export, and optional offline accounts.

Treat Minecraft session tokens as credentials. Never paste them into issues, screenshots, Discord messages, or other public locations.

## Debug Command

The `/nulltrace` command is disabled by default.

Enable **Miscellaneous -> Debug Command** first.

| Command                 | Description                                                          |
| ----------------------- | -------------------------------------------------------------------- |
| `/nulltrace`            | Show available commands                                              |
| `/nulltrace info`       | Show tracked mod information                                         |
| `/nulltrace info <mod>` | Inspect channels, keys, known packs, shaders, and other tracked data |
| `/nulltrace channels`   | List tracked network channels and whitelist status                   |

## Detections

When diagnostics are enabled, common alerts include:

* **Key Resolution Exploit Detected** - a server is probing translation or keybind resolution
* **Resource Pack Fingerprinting Detected** - a suspicious resource-pack destination was detected
* **Local URL Scan Detected** - a resource-pack request targeted a local/private address

## Compatibility

### Current Minecraft Targets

The current branch is maintained for the following Minecraft versions:

| Minecraft | Java | Fabric Loader | Fabric API   |
| --------- | ---- | ------------- | ------------ |
| **26.2**  | 25   | 0.19.5        | 0.160.0+26.2 |
| **26.3**  | 25   | 0.19.5        | 0.160.5+26.3 |

Minecraft 26.1 and older targets are not part of the current `versions/` tree on `main`.

### Mod Compatibility

NullTrace currently declares a conflict with:

* [ExploitPreventer](https://github.com/NikOverflow/ExploitPreventer)
* Ichor
* LabyMod
* AutismsexDefender

Do not install ExploitPreventer alongside NullTrace unless the project's compatibility rules change.

## Installation

### Modrinth

Download the build matching your Minecraft version from [Modrinth](https://modrinth.com/mod/nulltrace).

### GitHub

The [GitHub Releases](https://github.com/International-Duck-Incorporated/NullTrace/releases) page contains the generated JARs.

The `main` branch currently publishes automated pre-release/nightly builds for Minecraft 26.2 and 26.3.

### CurseForge

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/nulltrace) is also supported, but updates may lag behind the GitHub and Modrinth builds.

### Manual Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Install the matching [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the matching NullTrace JAR.
4. Put the JARs in your `.minecraft/mods` directory.
5. Start Minecraft.

Mod Menu is optional but provides a convenient configuration entry point.

## Building

NullTrace uses [Fabric Loom](https://github.com/FabricMC/fabric-loom) and [Stonecutter](https://stonecutter.kikugie.dev/) for multi-version builds.

### Requirements

* Java 25 for the current 26.2 and 26.3 targets
* Git
* No system Gradle installation is required; the repository includes the Gradle wrapper

### Clone

```bash
git clone https://github.com/International-Duck-Incorporated/NullTrace.git
cd NullTrace
```

### Build Minecraft 26.2

Linux/macOS:

```bash
./gradlew :26.2:build
```

Windows:

```powershell
.\gradlew.bat :26.2:build
```

### Build Minecraft 26.3

Linux/macOS:

```bash
./gradlew :26.3:build
```

Windows:

```powershell
.\gradlew.bat :26.3:build
```

Generated JARs are written below:

```text
versions/<minecraft-version>/build/libs/
```

Sources are generated alongside the normal JAR during the Gradle build.

## Development

NullTrace uses a shared source tree with version-specific Stonecutter projects under `versions/`.

The current repository layout is broadly:

```text
NullTrace/
├── .github/workflows/       # CI and release automation
├── docs/assets/             # Project artwork
├── src/main/                # Shared/common code
├── src/client/              # Client-only code and mixins
├── versions/
│   ├── 26.2/
│   └── 26.3/
├── build.gradle
├── gradle.properties
├── stonecutter.gradle
└── settings.gradle.kts
```

When changing Minecraft-specific code, test the affected version target rather than assuming the mappings and client internals are identical.

## Issues and Contributions

Use the [issue tracker](https://github.com/International-Duck-Incorporated/NullTrace/issues) for bug reports, reproducible compatibility problems, and feature requests.

Pull requests should:

* Keep version-specific changes isolated where possible.
* Avoid introducing server-side dependencies for a client-only feature.
* Preserve the existing GPL-3.0-only licensing.
* Include testing information for the Minecraft version(s) affected.

## References and Credits

NullTrace began as a fork of [OpSec](https://github.com/aurickk/OpSec) by aurickk.

Additional implementations and research are based on or informed by:

* [ExploitPreventer](https://github.com/NikOverflow/ExploitPreventer) - local URL blocking and key-resolution protection concepts
* [LiquidBounce](https://github.com/CCBlueX/LiquidBounce/blob/nextgen/src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/util/MixinDownloadQueue.java) - resource-pack cache isolation
* [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) - account/session handling and compatibility fixes
* [No Chat Reports](https://modrinth.com/mod/no-chat-reports) - chat-signing and telemetry-related functionality
* [No Prying Eyes](https://github.com/Daxanius/NoPryingEyes) - secure-chat enforcement research
* [MixinSquared](https://github.com/Bawnorton/MixinSquared) - mixin cancellation used by the Meteor compatibility fix
* [Fabric API](https://github.com/FabricMC/fabric-api) - Fabric client and networking integration
* [Stonecutter](https://stonecutter.kikugie.dev/) - multi-version project management

## License

NullTrace is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

See [LICENSE](LICENSE) for the complete license text.
