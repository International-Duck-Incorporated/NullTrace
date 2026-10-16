# Proxy management

Status: implemented (Advanced tab + `ProxyScreen`). See `CHANGELOG.md` [Unreleased].

## What the user asked for, and what this is

> "in its place, expand the proxy functionality, where the user can paste a proxy in a box,
> or a list, let the user select etc. but put it under an advanced settings or settings tab."

This replaces the old single "Play through proxy" boolean with a real list: paste an
endpoint, keep several, pick one, reorder them, probe the selected one, all on a new
**Advanced** tab.

## Scope — what a client proxy actually affects

This is the part that has to be honest, because the old option's tooltip was not.

| Traffic | Goes through the selected proxy? |
| --- | --- |
| Mojang/Microsoft auth, skin & cape fetches, resource-pack downloads, telemetry | **Yes** — everything Minecraft routes through `Minecraft#getProxy()` |
| NullTrace's own update check, jar-integrity check, account HTTP | **Yes** — the account client already used `getProxy()`, and it is now proxy-aware by construction |
| The multiplayer game connection | **No.** Vanilla never consults the proxy for it. Nothing client-side can hide the player's IP from a server; that needs a Netty proxy handler in the connect pipeline (see Follow-ups). |

The Advanced tab therefore states the scope in plain text under the mode selector rather
than letting players assume it hides their IP.

## Modes

`NullTraceConfig.proxySettings()` holds the configuration; `MinecraftMixin.getProxy()`
resolves it:

| Mode | `getProxy()` returns | Meaning |
| --- | --- | --- |
| `DIRECT` (default) | `Proxy.NO_PROXY` | Ignore any launcher-supplied proxy. Preserves the previous default (`useProxy=false`). |
| `LAUNCHER` | untouched | Use whatever the launcher configured. This is what the old `useProxy=true` meant. |
| `SELECTED` | the selected entry's `Proxy`, or `NO_PROXY` when that entry is gone | Fail closed: a deleted/hand-edited selection degrades to a direct connection, never to a phantom endpoint. |

Legacy configs are migrated on load: `settings.useProxy == true` → `LAUNCHER`,
otherwise → `DIRECT`. The deprecated boolean is still read, never written.

## The list

* **Paste and add.** One `EditBox` accepts `host:port` (treated as SOCKS5), `socks5://host:port`,
  `http://host:port`, `https://host:port` (both HTTP), and `[2001:db8::1]:1080`.
  Bare `host:port` defaulting to SOCKS5 is stated in the input hint, not left implicit.
* **Select.** Each row has `Use` / `In use`; the selected row is green. Adding an endpoint
  also switches the mode to `SELECTED` and says so in the status line, so the common
  "add then connect" flow is one step rather than two silent ones.
* **Order, remove, page.** `↑ ↓ ×` per row, five rows per page with paging controls.
* **Test.** `Test` probes the selected endpoint off-thread:
  * HTTP: establishes a real `CONNECT www.minecraft.net:443` tunnel and requires a `200`.
  * SOCKS: performs a SOCKS5 greeting (`0x05 0x01 0x00`) and requires no-auth acceptance.
  * Either way it reports latency or the exact failure (e.g. "authentication required").
  * It proves *reachability and protocol compatibility*, which is what the game needs. It
    does not claim the proxy is trustworthy or fast.
* **No credentials.** A pasted `user:pass@host:port` is **rejected** with an explanation
  rather than stored and ignored: Java's HTTP stack cannot be given proxy credentials for
  connections Minecraft opens itself (the game installs a no-op `Authenticator` on each
  connection), and SOCKS auth needs a global `Authenticator` or a custom handler. Storing a
  password that silently does nothing would be worse than refusing it.

Storage lives in `ProxySettings` (see the class javadoc) and is serialized as a top-level
`"proxy"` object in the config file — outside `SpoofSettings` on purpose, so the Reset
button cannot wipe a hand-pasted list.

## Files

| File | Role |
| --- | --- |
| `proxy/ProxyEntry.java` | Immutable endpoint, parser, `java.net.Proxy` conversion, JSON |
| `proxy/ProxySettings.java` | Mode, list, selection, ordering, persistence, `activeProxy()` |
| `proxy/ProxyTester.java` | HTTP `CONNECT` / SOCKS5 greeting probes |
| `config/ProxyScreen.java` | Advanced tab's manage screen |
| `config/NullTraceConfigScreen.java` | New Advanced tab, tab index constants |
| `mixin/client/MinecraftMixin.java` | `getProxy()` resolution |
| `config/NullTraceConfig.java` | `proxySettings()` + load/save + legacy migration |

## Verification

`tests/proxy/` ships a standalone harness that exercises the parser, list semantics, JSON
round-trip and the probes against real sockets (a fake HTTP `CONNECT` proxy and a fake
SOCKS5 server), including the failure paths:

```bash
javac -encoding UTF-8 -d /tmp/proxy-harness -cp gson.jar \
  tests/proxy/stub/net/nulltrace/mod/NullTrace.java \
  src/client/java/net/nulltrace/mod/lang/NullTraceStrings.java \
  src/client/java/net/nulltrace/mod/proxy/ProxyEntry.java \
  src/client/java/net/nulltrace/mod/proxy/ProxySettings.java \
  src/client/java/net/nulltrace/mod/proxy/ProxyTester.java \
  tests/proxy/FakeProxyServers.java \
  tests/proxy/ProxyProbeHarness.java
java -cp gson.jar:/tmp/proxy-harness ProxyProbeHarness
```

54 checks pass — no ports to pass in, because the harness starts its own endpoints
(`tests/proxy/README.md`). This is the kind of fixture
`docs/reviews/mod-review-2026-10-17.md` (item 1) asked for — the older Python
"adversarial fixture" does not touch NullTrace code at all.

It is wired into CI as `proxy_probe_harness` (a `javac` + `java` step; the fake endpoints are Java,
so the job needs no Python). `policy_tests` now runs the JUnit suite on both 1.21.11 and 26.3 instead
of 26.3 only.

## Follow-ups

1. **Game-traffic proxy (the one that would matter).** Inject
   `io.netty.handler.proxy.Socks5ProxyHandler` / `HttpProxyHandler` into the channel
   pipeline in the `Connection` connect path so the multiplayer connection itself goes
   through the selected endpoint. That is the only way to hide a player's IP from a server,
   and it is a real feature (Netty handlers, version-specific pipeline points, failure
   handling for proxies that refuse the target) rather than a config flag — deliberately not
   smuggled into this patch.
2. **Credential support**, once (1) exists: both paths then need an explicit
   `Proxy-Authorization` / SOCKS username-password exchange that NullTrace controls, which
   is the only place credentials can actually be applied.
3. **Per-server proxy override.** `ServerProfile` already models per-server policy; a
   "route this server through X" field is the natural extension once (1) exists.
4. **Rotate/leak note:** if a proxy is combined with the pack-cache isolation, the proxy's
   exit IP becomes another per-connection signal — worth a line in the audit screen when a
   proxy is active.
