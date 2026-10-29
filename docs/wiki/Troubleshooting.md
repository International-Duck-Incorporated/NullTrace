# Troubleshooting

Most of what looks like a bug here is a documented trade-off. This page lists the ones people hit
first, then the real failure modes and how to read them.

## 2.2.0 on Minecraft 26.2 cannot ping or join any server (`nulltrace$logged is null`)

**Fixed in 2.2.1 — update.** If your log shows

```
java.lang.NullPointerException: Cannot invoke "java.util.concurrent.atomic.AtomicBoolean.compareAndSet(...)"
  because "this.nulltrace$logged" is null
	at net.minecraft.network.Connection.handler$...$nulltrace$onSend(Connection.java)
	at net.minecraft.network.Connection.send(Connection.java)
```

for every server-list ping and every connection attempt, you are on `nulltrace-26.2+v2.2.0.jar`.
That build throws inside `Connection.send` before writing anything, so nothing can connect. It is
specific to the **26.2** artifact; the 1.21.11 jar from the same release is not affected.

Install `nulltrace-26.2+v2.2.1.jar` (or newer) and the pings and connections work again.
Root cause: a mixin field initialiser that Mixin merged on 1.21.11 but not on 26.2
(`docs/reviews/postmortem-2026-10-29-26.2-npe.md` in the repository).

## A mod feature stopped working after enabling spoofing

Expected. **Spoof as Vanilla** blocks mod channels, so any mod that talks to the server (voice chat,
server-side map sharing, chat bridges) loses that channel. Two options:

1. Switch the whitelist to **CUSTOM** and tick the mods you need.
2. Leave spoofing on and accept the breakage for that server.

Run `/nulltrace channels` (needs **Debug Command**) to see exactly which channel belongs to which
mod, then whitelist the owner rather than guessing.

## `/nulltrace info` or `/nulltrace channels` does nothing

They require **Misc → Debug Command**. Brigadier hides a gated subcommand from a source that does not
satisfy the gate, so an ungated invocation gives no output and no error — which is why the help text
now marks both with *(require Debug Command)*. `/nulltrace audit` and `/nulltrace profile` are always
available.

## The audit shows `Client brand: not sent (singleplayer)`

Correct, and it is not a leak. On an integrated server (singleplayer, or hosting a LAN world) no
client-brand packet exists, so there is nothing to block and nothing to count. The same line used to
read `EXPOSED`, which described a leak that cannot happen and inflated *Remaining exposure* by one.

## Protections seem off while hosting a LAN world

They partially are. Translation-probe and keybind protection short-circuit when
`hasSingleplayerServer()` is true, which is also true for the **host** of an "Open to LAN" world.
There is no remote server to leak to in that case, so it is intentional — but it makes a LAN session
a bad way to test whether those protections work. Test on a real remote server.

## Translation/keybind probes show 0 in the audit

Only meaningful on a connection where something actually probed. A quiet server produces no probes;
`Total translation keys` in `/nulltrace info` is the size of the tracked set, not a count of attacks.

## Jar integrity warnings

The check compares your jar against the GitLab release checksums and **skips** verification when no
matching release/checksum exists — that is not a verdict that the jar is safe. It is advisory by
construction:

- the warning is dismissible (`tamperWarningDismissed`), and
- anyone who can replace your jar can also remove the check.

If you did not build the jar yourself, check the SHA-256 against the release page by hand. The
release ships `SHA256SUMS`, per-file `.sha256` files and a `release-manifest.json`.

## A warning says pack-cache isolation did not run

```
[NullTrace] Pack-cache isolation is enabled but its injection never ran - server packs are sharing
one cache path (no per-account isolation on this Minecraft version).
```

The injection behind per-account pack caches is the one tolerant (`require = 0`) mixin in the mod: it
targets a Minecraft lambda that a version bump can rename. Rather than silently going back to a shared
cache path, NullTrace checks 30 seconds after the first server-pack push and says so here, in the log
and in `/nulltrace audit` as a `WARN`. The fix upstream is a mixin target update; the workaround for
you is to be aware the isolation is not active on that build, or to disable
**Isolate Pack Cache** to silence the warning.

## `meteorFix` and Meteor Client

With `meteorFix` on (default), NullTrace cancels a small set of Meteor Client's mixins, because
Meteor's own key-resolution handling defeats the keybind protection. It is documented in
`MeteorMixinCanceller`, but if you are debugging Meteor behaviour, turn the option off first.

## The proxy does not hide my IP from the server

By design and documented: the proxy setting covers **NullTrace's own HTTP traffic** — update checks,
checksum lookup, account and session requests — the same surface the vanilla launcher proxy covers.
The game connection is not routed through it, so the server still sees your real address. Proxying
gameplay needs Netty handlers in the connect path and is on the roadmap
(`docs/design/proxy-management.md`).

## Chat messages are rejected

You have chat signing set to **OFF**. Servers that enforce secure profiles reject unsigned chat. This
is not something NullTrace can fix from the client side.

## Everything is broken after a Minecraft update

Check the log for `NullTrace initialized`, mixin errors, and the isolation warning above. A Minecraft
or Fabric update can move a target the mod injects into; the mod fails loudly rather than crashing
where it can. Report it with your log and the exact versions — `/nulltrace audit` output is useful
because it says which surfaces resolved.

## Reporting a bug

Include:

- NullTrace version, Minecraft version, Fabric Loader version, target (`1.21.11`, `26.1`, `26.2`, `26.3`)
- The relevant part of `logs/latest.log`
- `/nulltrace audit` output for the connection where it happens
- Whether the server is a real remote server (not singleplayer or a LAN world), because several
  protections intentionally stand down otherwise

Never paste session tokens, account details or the contents of `nulltrace-accounts.json` /
`nulltrace-account.key`. If you need to share a config, redact `serverProfiles` and account entries.
