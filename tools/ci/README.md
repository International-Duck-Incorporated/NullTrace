# tools/ci

Checks that run before, during and after a build. Standard library only, except
`lint_gitlab_ci.py`, which needs PyYAML (the `preflight` job installs `py3-yaml`
with `apk`).

| Script | Needs | What it protects |
| --- | --- | --- |
| `preflight.sh` | sh, python3 | runs everything below in the right order; `--build <target>` adds a real Gradle build, jar audit and checksums |
| `check_metadata.py` | python3 | tag ↔ `mod_version` ↔ `CHANGELOG.md`, `versions/` ↔ `settings.gradle.kts` ↔ README table, non-overlapping Minecraft ranges |
| `check_sources.py` | python3 | every mixin config entry has a source file (for each template variant), every entrypoint class exists, no credential-shaped strings |
| `inspect_jar.py` | python3 | 18 checks on a built jar: `${version}` expansion, version vs tag, refmap, mixin wiring, entrypoints, JiJ, bytecode vs `depends.java`, LICENSE, JSON, secrets |
| `gen_sums.py` | python3 | bare-name `.sha256` sidecars, `SHA256SUMS`, `release-manifest.json`, and `--verify` for all three |
| `lint_gitlab_ci.py` | python3, pyyaml | CI structure, `needs`/`stages`/`extends` resolution, `rules: if` simulation per scenario, `sh -n` on every script block |
| `selftest.py` | python3 | asserts the checks above fail when they should (27 cases) |
| `ntci.py` | — | shared helpers: version ranges, changelog, properties, result reporting |

Conventions:

- Exit code `0` means "no failures"; warnings never fail a job.
- `NT_REPO_ROOT` overrides the checkout the checks read (used by `selftest.py`).
- Check IDs (`META…`, `SRC…`, `JAR…`, `CI…`) are stable: quote them in issues.

```bash
./tools/ci/preflight.sh                 # what CI runs before spending a build minute
./tools/ci/selftest.py -v               # prove the checks still have teeth
python3 tools/ci/inspect_jar.py --help
```
