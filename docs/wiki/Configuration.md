# Configuration

Open the **NullTrace** button on the multiplayer screen, or go through **Mod Menu → NullTrace**.
Five tabs: **Protection**, **Whitelist**, **Misc**, **Advanced**, **Accounts**. Some changes need a
reconnect before every protection is active — the screen says so where it matters.

![Protection tab](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/config-protection.jpg)

## Protection

| Option | Default | What it does |
| --- | --- | --- |
| **Spoof as Vanilla** | off | Reports the vanilla client brand and blocks mod payloads. Turning it on forces the whitelist into **Block all**. |
| **Isolate Pack Cache** | on | Stores server resource packs per account, so two accounts on one install cannot be linked by a shared cache. |
| **Block Local URLs** | on | Refuses server-pack URLs that point at loopback, link-local, metadata or your own LAN. |
| **Strip Mod Shaders** | on | Removes server-pack shader overrides aimed at installed, non-whitelisted mods. Vanilla assets are untouched. |
| **Translation Protection** | on | Answers translation-key probes without revealing which mods are installed. |
| **Fake Default Keybinds** | on | Reports vanilla keybind defaults instead of your real bindings. |
| **Normalize Client Information** | on | Normalizes locale, view distance, chat visibility, particles and skin parts. |
| **Block Server Cookies** | on | Blocks server-driven cookies (some proxy networks rely on them). |
| **Block Server Transfers** | on | Blocks servers moving you to another host without asking. |
| **Disable Telemetry** | on | Stops Minecraft's own telemetry to Mojang. Gameplay traffic is unaffected. |
| **Bypass Server Pack Requirement** | MANUAL | Controls whether a pushed server pack starts stripped — see below. |

### Bypass Server Pack Requirement

| Mode | Behaviour |
| --- | --- |
| **MANUAL** | Packs apply normally when pushed; you can disable or re-enable them in the resource-pack screen |
| **ASK** | Visual/audio content is stripped at first, then a prompt asks whether to load it |
| **ALWAYS ON** | Visual/audio content is stripped without asking; you can still enable it manually |

Language files stay loaded even when a pack's visual/audio content is stripped, so a simple
"can you translate this?" check cannot tell whether the pack is fully active. That is deliberate:
it closes the cheap fingerprint without making the pack useless for non-Latin locales.

## Whitelist

Three modes, and they are not the same thing as the brand spoof:

| Mode | Behaviour |
| --- | --- |
| **Block all** (`OFF`) | No mod content is exposed | 
| **AUTO** | Every mod that registers a network channel is allowed automatically |
| **CUSTOM** | Only the mods you select are allowed |

Turning **Spoof as Vanilla** on switches to **Block all** and remembers what you had, so switching
back later restores your list instead of guessing.

Whitelisting is for features that genuinely need the server — voice chat is the classic example. An
allowed mod *does* identify itself; a vanilla-looking brand plus an allowed mod channel is not
anonymity.

![Whitelist: block all](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/config-whitelist-blockall.png)
![Whitelist: AUTO](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/config-whitelist-auto.png)
![Whitelist: CUSTOM](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/config-whitelist-custom.png)

## Misc — signing, diagnostics, alerts

- **Chat Signing** — **SIGN** keeps normal Minecraft chat signing; **OFF** removes signatures.
  Servers that enforce secure profiles will reject unsigned chat. This is a real trade-off, not a
  bug: NullTrace never silently downgrades your chat.
- **Show Alerts / Show Toasts / Log Detections** — how tracking detections are reported.
- **Debug Alerts** — also report probes for keys that did not change.
- **Debug Command** — unlocks `/nulltrace info` and `/nulltrace channels`. `/nulltrace audit` and
  `/nulltrace profile` are always available, because privacy must be inspectable without turning on
  diagnostics.
- **Server Privacy Profile** — `STANDARD`, `STRICT`, `MAXIMUM_PRIVACY` or `CUSTOM`, stored per server.

![Diagnostics and debug settings](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/config-misc.png)

## Advanced — proxies and network checks

- **Proxy mode** — **Direct**, **Launcher** (use the launcher's proxy), or **Selected** (one entry
  from your own list). Configs from older versions migrate automatically.
- **Manage proxies…** — paste `host:port` (or `socks5://host:port`), select, reorder, remove, and
  **probe** the endpoint to see whether it actually answers before you rely on it.
- **Check for Updates**, **Verify JAR Integrity** — each can be switched off; with both off, nothing
  is requested from either service.

> [!WARNING]
> The proxy covers **NullTrace's own HTTP traffic** (release checks, checksum lookup, account/session
> requests) — the same surface the vanilla launcher proxy covers. **The game connection itself is not
> proxied**: a server still sees your real IP address.

## Accounts

Session-based login, account switching, token validation, and import/export. Only authenticated
Microsoft accounts work — NullTrace cannot create an offline identity, and an offline entry left over
from an older version is dropped when the config loads.

Session and refresh tokens live in a separate AES-GCM-encrypted vault with its own key file. On
POSIX filesystems that key file is created `0600` in a single `CREATE_NEW` operation (no window where
it is world-readable); where file permissions are not POSIX, the key falls back to the platform's
default protection — the class comment says so plainly. Exports omit bearer tokens and need
re-authentication on the destination install.

Treat session tokens as credentials. Never paste them into issues, screenshots or chat.

![Account manager](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/config-accounts.png)

## Where the config lives

```
.minecraft/config/nulltrace.json          settings + per-server profiles
.minecraft/config/nulltrace-accounts.json account list (session tokens are ciphertext, v2 format)
.minecraft/config/nulltrace-account.key   the AES key for those ciphertexts (0600 on POSIX)
```

Both JSON files are written atomically (temp file + `ATOMIC_MOVE`), so a crash mid-write cannot
leave a half-written config behind.

Per-server profiles are keyed by the **host name you connected with** — not by the resolved IP — so a
DNS change or a CDN front-end cannot silently drop you back to `STANDARD`. Joining a server by name
and by IP is still two different profiles.
