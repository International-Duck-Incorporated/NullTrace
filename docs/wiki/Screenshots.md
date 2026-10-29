# Screenshots

Every frame from the first in-client runtime test of 2.2.0: Minecraft **1.21.11**, Fabric Loader
0.19.3, **53 mods** including Sodium, Lithium, C2ME, Krypton, FerriteCore, ImmediatelyFast and
EntityCulling, on a machine with **no GPU** (Xvfb + Mesa llvmpipe) and render distance 2.

The full write-up, including what the run did *not* cover, is in
[`docs/reviews/runtime-test-2026-10-28.md`](https://gitlab.com/international-duck-incorporated/NullTrace/-/blob/main/docs/reviews/runtime-test-2026-10-28.md).
An HTML gallery of the same set is published from `docs/screenshots.html` by the Pages job.

## Launch

![Title screen](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/title-screen.jpg)

The footer reads `Minecraft 1.21.11/Fabric (53 mods)`; the log carried
`NullTrace v2.2.0 — Privacy protection for Minecraft` before this frame.

![Multiplayer screen with the NullTrace button](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/multiplayer-button.jpg)

The mod's entry point sits top-left, next to **Play Multiplayer**.

![Protection tab](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/config-protection.jpg)

Every toggle renders and reflects the persisted config.

## World

![World creation](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/world-creation.jpg)
![World list](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/world-list.jpg)
![In game at render distance 2](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/in-game.jpg)

Joined as `Player233`, `Changing view distance to 2`. No crash, no mixin failure, no crash-report
directory in either run.

## Commands

![Command help](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/command-help.jpg)

This frame is why the help text was rewritten: the audit line sits a column off, `profile` is missing,
and `info`/`channels` are listed even when Debug Command is off and they are hidden from the source.

![Audit with the whitelist in AUTO](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/audit-whitelist-auto.jpg)

Whitelist `AUTO`, no spoofing: brand exposed, 9 mod channels allowed, but every non-vanilla
translation probe already blocked.

![Audit with spoofing on](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/audit-vanilla-spoof.jpg)

Same world, `spoofAsVanilla = true`: brand blocked, 9/0 channels, **remaining exposure 0**.

![Channel owners](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/channels.jpg)

Each channel resolved to the mod that registered it, with the whitelist verdict.

![Profile switch](https://gitlab.com/international-duck-incorporated/NullTrace/-/raw/main/docs/assets/screenshots/runtime/profile-maximum-privacy.jpg)

`/nulltrace profile MAXIMUM_PRIVACY` mid-session: blocked decisions go 6 → 36 and the entry is
written to `nulltrace.json` under `serverProfiles`.
