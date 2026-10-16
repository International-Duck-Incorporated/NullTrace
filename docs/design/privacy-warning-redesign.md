# Pre-connection privacy warning — redesign

Status: proposal + implemented first pass on `main` (see [What changed in the repo](#what-changed-in-the-repo)).
Companion mockup: [`privacy-warning-mockup.html`](privacy-warning-mockup.html).

The goal in one sentence: **turn the screen from an alarm into a receipt plus a choice.**
The player should learn what this server gets, see that nothing has been sent yet, and
fix it or not — in one click either way.

---

## 1. What the current screen does wrong

Evidence: the screenshot (`play.enchantedmc.com:25565`, 9 mods, 0 channels exposed,
8250 probes, remaining exposure 0) together with
[`PrivacyWarningScreen`](../../src/client/java/net/nulltrace/mod/privacy/PrivacyWarningScreen.java)
and [`ConnectionWarning`](../../src/client/java/net/nulltrace/mod/privacy/ConnectionWarning.java).

1. **The headline is an incident report.** "Privacy exposure detected" reads like a
   breach. The actual state on that screenshot is the good state.
2. **The screen contradicts itself.** "0 network channels will be exposed" and
   "Remaining exposure: 0" are printed on a screen that blocks the connection and
   offers "Connect Anyway". `shouldWarn()` fires on `probes > 0` — an internal counter
   (translation keys + known-pack strings) — not on anything reaching the server. So
   the mod interrupts players while telling them nothing is wrong.
3. **Good news and bad news look identical.** "0 … exposed" and "8250 … tracked" have
   the same colour, weight and position. There is no verdict anywhere on the screen.
4. **8250 is not information.** It is `getTranslationKeyCount() + getKnownPackCount()`.
   No player can act on it; it only produces "wait, what?!"
5. **"Connect Anyway" is framing, not a label.** "Anyway" tells the player that joining
   is the risky choice, while being the only reasonable button.
6. **"9 installed mods are potentially identifiable"** uses `getAllMods().size()` — that
   counts every installed mod, not the ones that leak. A false alarm by construction.
7. **No way out, no explanation, no memory.** No link to settings, no "why", no
   per-server "don't ask again", and `onClose()` is not overridden, so Escape does not
   take the player back where they came from. The whole screen is hardcoded English, so
   none of the ten bundled locales reach the one screen where comprehension matters most
   (the project already solved this properly with `NullTraceLang` + `nulltracelang/*.json`).

The cost of 1–4 is not cosmetic: a screen that cries wolf trains players to click
through it. The warning then fails precisely on the server that does fingerprint them.

---

## 2. Design rules for the redesign

| Rule | Consequence |
| --- | --- |
| **Verdict first** | One sentence, colour-coded, top-left. Yellow = actionable, green = clear, red stays reserved for the jar-integrity screen. |
| **Rows, not counters** | Every row answers "does this server receive it?" in words (`not sent`, `blocked`, `0 of 12 sent`). Exact counts move to *Details*. |
| **The screen is a view of the policy** | Row states come from `ServerProfile.policy(...)` / `PrivacyPolicy.actions()`, never from separate ad-hoc counters, so the consent screen and enforcement can never disagree. |
| **Never interrupt with good news** | Zero visible surfaces → no screen (default). `WarningMode.ALWAYS` exists for users who want a receipt every time. |
| **Two equal exits** | `Connect` and `Protect & connect` are both one click. The remediation applies the strict profile for that server and is recorded in the audit ledger. |
| **Nothing is a dead end** | `Privacy settings` (opened on this server's profile), `Show details`, and a per-server `Warn here: ON/OFF` toggle live on the screen itself. |
| **Say it out loud** | "Nothing has been sent yet." The single most panic-reducing line available. |
| **Escape = Back** | `onClose()` returns to the parent screen; Escape never drops the player somewhere unexpected. |

---

## 3. Screen anatomy

```text
This server can identify parts of your client        <- verdict (yellow/green)
Server: play.enchantedmc.com:25565                   <- what you are joining
Nothing has been sent yet. NullTrace can block these before you join.

Client brand                        sent             <- red  = the server gets it
Mod network channels                7 of 12 sent     <- red
Mod pack identifiers                not sent         <- green = blocked for you
Server text & key probes            blocked          <- green
Client info (language, view, chat)  normalized       <- green
Resource pack requests              filtered         <- green
Cookies & server transfers          blocked          <- green

[ Back ]  [ Connect ]  [ Protect & connect ]          <- one click, either way
[ Privacy settings ] [ Show details ] [ Warn here: ON ]
```

* `Show details` reveals the counters the old screen led with (mods installed, channels
  sent *n* of *m*, text keys tracked, keybinds protected, pack identifiers, shaders,
  remaining exposure) plus `Full log: /nulltrace audit`.
* Rows are grouped from `ConnectionWarning.Area` — one user-facing idea per row, one or
  more `PrivacySurface`s behind it, declaration order = display order.
* Severity: any visible **identity** area (brand, channels, pack identifiers, text probes,
  client info) → `EXPOSED`; only tracking-shaped areas (pack requests, cookies, transfers)
  → `NOTICE`; nothing → `OK`.

---

## 4. Copy changes

| Today | Proposed | Why |
| --- | --- | --- |
| Privacy exposure detected | This server can identify parts of your client | Same facts, no incident framing |
| 9 installed mods are potentially identifiable | Details → Mods installed: 9 | The number was never "identifiable" |
| 0 network channels will be exposed | Mod network channels — 0 of 12 sent | Honest framing, green when good |
| 8250 fingerprint surfaces are tracked | Server text & key probes — blocked | State instead of counter |
| Remaining exposure: 0 | (screen not shown) | Don't interrupt players to say nothing is wrong |
| Cancel | Back | Nothing was cancelled |
| Connect Anyway | Connect / Protect & connect | Stop implying that playing is wrong |

All strings live in `NullTraceStrings` + `assets/nulltrace/nulltracelang/en_us.json`
(`nulltrace.warning.*`), so they follow the existing private-lang path: resource packs
cannot rewrite them, and the other locales fall back to `en_us` until translated.

---

## 5. Behaviour and logic changes

| Change | Detail |
| --- | --- |
| `shouldWarn()` | Now `!muted && (alwaysWarn || severity != OK)`. A clean join with exposure 0 no longer opens the screen. |
| Severity | Derived per area from the effective policy, not from raw counters. |
| Per-server mute | New `SpoofSettings.mutedWarningServers` (host-only keys, normalized like server profiles). Muting **only** skips the prompt — policy evaluation, alerts and the ledger keep running, and the config file keeps recording. |
| `WarningMode` | New setting: `EXPOSURE_ONLY` (default) / `ALWAYS`. |
| `Protect & connect` | Writes `ServerProfile.Mode.STRICT` for the target server only (`NullTraceConfig.setProfileMode(server, mode)`, a new method that does not touch `currentServer`), records a `PROTOCOL_BEHAVIOR` entry in the audit ledger, then connects. |
| Settings button | Points the profile editor at this server (`setCurrentServer(serverKey)`) before opening the config screen on the Protection tab, then returns here. |
| `onClose()` | Overridden → back to the parent screen. Escape is now safe. |
| Layout | Content is vertically centred above a reserved button block; button rows size themselves from their labels and tighten padding on cramped GUI scales; the settings button drops first on very narrow screens. |
| Text rendering | Still `StringWidget` only — no `GuiGraphics` drawing — so nothing changes for the 1.21.9+ stratum pipeline the other screens already avoid. |

### Deliberately not done

* **No panel/box drawn behind the content.** It would look better (see the mockup), but
  it needs raw `GuiGraphics` calls; the repo currently avoids those on purpose. Worth
  doing later behind a version guard if you want it.
* **No `PrivacyAction.APPLY`.** "Protect & connect" records its profile change as
  `PROTOCOL_BEHAVIOR` + `BLOCK`, which is close enough but not a perfect fit — a dedicated
  action (or a `SERVER_PROFILE` surface) would be cleaner.
* **No base-policy split.** `NullTraceConfig.privacyPolicy()` already merges the profile of
  *the current* server; the warning merges the target server's profile on top. If the two
  ever differ, the base policy can carry a stale profile for a moment. A
  `basePrivacyPolicy()` without the merge would remove that wart.
* **Other locales not translated yet** — `en_us` only, by design of the fallback.

---

## 6. What changed in the repo

| File | Change |
| --- | --- |
| `src/client/java/net/nulltrace/mod/privacy/ConnectionWarning.java` | Rewritten: `Severity`, `Area`, policy-derived `Summary`, `serverKey()`, per-server mute, new `shouldWarn()`. |
| `src/client/java/net/nulltrace/mod/privacy/PrivacyWarningScreen.java` | Rewritten: verdict-first layout, row table, details panel, remediation button, settings/details/mute row, `onClose()`. |
| `src/client/java/net/nulltrace/mod/lang/NullTraceStrings.java` | ~45 new `WARNING_*` keys. |
| `src/main/resources/assets/nulltrace/nulltracelang/en_us.json` | All new strings, §-formatted like the config screen. |
| `src/client/java/net/nulltrace/mod/config/SpoofSettings.java` | `WarningMode`, `mutedWarningServers` (+ accessors, `toJson`, `fromJson`). |
| `src/client/java/net/nulltrace/mod/config/NullTraceConfig.java` | `setProfileMode(String server, ServerProfile.Mode mode)`. |

`ConnectScreenMixin` needed no change: it calls
`ConnectionWarning.summarize(...)` → `summary.shouldWarn()` and constructs the screen,
and both signatures still hold (the screen also has an extra overload that carries the
expanded-details flag).

**Verification status:** the Java was syntax-checked with a standalone compiler only
(no Minecraft/Fabric artifacts and no JDK 25 in the environment where this was written),
so run `./gradlew :26.2:build` (or your CI pipeline) before shipping. Keep the
`//? if >=26.2` guards as-is: `setScreenAndShow`, `setScreen` and the `TransferState`
import are already handled that way.

---

## 7. Follow-ups worth doing

1. Wire `WarningMode` into the config screen (Protection tab → "Privacy & Security",
   next to Server Profile) with `cycleBuilder`, and add tooltips.
2. Translate `nulltrace.warning.*` into the nine bundled locales — this is the screen
   where "I don't read English" costs the most.
3. Optional: draw a subtle panel behind the content on versions where `fill` is safe.
4. Optional: a `ConnectScreenMixin` shortcut for `ALWAYS`-mode receipt: if the player is
   already whitelisted/vanilla-spoofed and exposure is 0, offer a toast instead of a screen.
5. Add `PrivacyWarningScreen` copy strings to `CHANGELOG.md` under the next version —
   the wording change is user-visible.
