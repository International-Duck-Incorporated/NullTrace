# CI runbook

Operator notes for the NullTrace pipeline. For *why* the pipeline looks the way it
does, see [`ci-review-2026-10-26.md`](ci-review-2026-10-26.md).

- [What runs, and when](#what-runs-and-when)
- [Compute minutes](#compute-minutes)
- [Self-hosted runner](#self-hosted-runner)
- [Releasing](#releasing)
- [Verifying a download](#verifying-a-download)
- [When a job fails](#when-a-job-fails)
- [Pulling a bad release](#pulling-a-bad-release)
- [Local checks](#local-checks)

---

## What runs, and when

| Scenario | Jobs | Gradle jobs |
| --- | --- | --- |
| Tag `vX.Y.Z` | `preflight`, `build_matrix` ×4, `policy_tests_matrix` ×2, `proxy_probe_harness`, `jar_audit`, `publish` | 6 |
| Push to `main` | `preflight`, `build_matrix` ×4, `policy_tests_matrix` ×2, `proxy_probe_harness`, `jar_audit`, (`pages`, manual) | 6 |
| Merge request | `preflight`, `build_smoke`, `policy_tests_smoke`, `proxy_probe_harness`, `jar_audit` | 2 |
| Feature branch without an MR | same as merge request | 2 |

`preflight` is pure Python and runs first in every pipeline. It checks the CI
definition itself, release metadata, mixin wiring, the Python fixtures and the
self-tests of the checks. If it fails, nothing spends a build minute.

Jobs, and what actually produces a failure:

| Job | Tool | Catches |
| --- | --- | --- |
| `preflight` | `tools/ci/*.py` | tag/version/changelog drift, README promising versions the build does not ship, mixin entry without a source file, secrets, broken CI definition |
| `build_matrix` / `build_smoke` | `./gradlew :<target>:build` | compile and resource errors on that target |
| `policy_tests_*` | `./gradlew :<target>:test` | JUnit policy/update-metadata regressions (JUnit XML is attached to the job) |
| `proxy_probe_harness` | `tests/proxy/` | proxy CONNECT/SOCKS5 probing, no Minecraft needed |
| `jar_audit` | `tools/ci/inspect_jar.py`, `gen_sums.py` | unexpanded `${version}`, version ≠ tag, refmap declared but absent, mixin declared without a class, entrypoint missing, bytecode/`depends.java` mismatch, broken JiJ, missing LICENSE, credentials, bad JSON, and the checksum format |
| `publish` | `curl` | uploads only after everything above is green |

The two bullets worth remembering: **`jar_audit` fails a tag whose jars report a
different version than the tag**, and **`publish` will not start unless the test
jobs for that pipeline ran and passed**.

## Compute minutes

GitLab.com charges shared-runner time per job, and this project's full pipeline is
six Gradle jobs. A cold run downloads the whole Minecraft/Fabric dependency graph;
on the four-target matrix that is the monthly allowance of a free namespace in one
or two pushes.

Order of options when the quota is exhausted:

1. **Check the number first.** *Settings → Usage Quotas* on the namespace shows
   minutes used and the reset date. A pipeline whose every job says
   `No more compute minutes available` is a quota problem, not a code problem —
   no amount of YAML will fix it.
2. **Run a self-hosted runner** (next section). This is the durable answer for a
   project that builds four Minecraft targets.
3. **Push less.** `preflight` locally (`./tools/ci/preflight.sh`) before pushing,
   and push once instead of five times: `interruptible` + `auto_cancel` already
   kill superseded pipelines, but they still cost their first minute.
4. **Temporarily narrow the matrix.** Comment out targets under
   `build_matrix.parallel.matrix` while iterating; tags need the full set.

Branch and MR pipelines are already reduced to one target. Do not "fix" a
quota problem by moving the full matrix back onto every branch.

## Self-hosted runner

A laptop or home server with a JDK 25 and ~4 GB of free disk is enough. The
runner needs network access to Maven Central, `maven.fabricmc.net`,
`maven.kikugie.dev` and `maven.terraformersmc.com`.

1. Install a runner (or use an existing one) and register it against the project
   (*Settings → CI/CD → Runners → New project runner*). Tag it, e.g. `self-hosted`.
2. If the machine only has a JDK 25 and no container runtime, use the shell
   executor. It runs jobs directly on that machine, so the host must have:
   `git`, `curl`, `unzip`, `python3`, `sh`, JDK 25 on `PATH`, and `sha256sum`.
   The Python-only jobs (`preflight`, `jar_audit`) need `python3` and, for
   `preflight`, `pyyaml` (`pip install pyyaml`).
3. Uncomment the tag line in the `default:` block of `.gitlab-ci.yml`:

   ```yaml
   default:
     interruptible: true
     timeout: 45m
     retry: ...
     tags: [self-hosted]
   ```

   Until that line is present, jobs keep going to the shared runners. To run
   *only* on your runner, this is the only change needed.
4. For a Docker executor, the images are `eclipse-temurin:25-jdk`,
   `python:3.12-alpine` and `alpine:3.22`. Pre-pull them:
   `docker pull eclipse-temurin:25-jdk python:3.12-alpine alpine:3.22`.
5. Keep `concurrent = 1` (or 2) in `config.toml` unless the machine has the RAM:
   each Gradle build asks for 2 GB (`org.gradle.jvmargs=-Xmx2G`).

Caching: jobs cache `.gradle/caches` and `.gradle/wrapper` under a per-target key
(`gradle-$MC_VERSION`). With the shell executor the cache lives in the GitLab
cache directory configured in `config.toml`; a local `GRADLE_USER_HOME` also
helps, but do not point it at a directory a running daemon is using — the
pipeline runs `--no-daemon`.

## Releasing

Prerequisites: `gradle.properties` `mod_version` already equals the version you
are about to tag, and `CHANGELOG.md` has a `## [X.Y.Z]` section with at least one
`###` heading.

```bash
# 1. The exact checks CI will run, locally, before tagging.
./tools/ci/preflight.sh --tag v2.2.0

# 2. Optional but recommended: a real build + jar audit on one target.
./tools/ci/preflight.sh --build 26.3 --tag v2.2.0

# 3. Tag and push. The tag pipeline publishes; branches never do.
git tag -a v2.2.0 -m "NullTrace 2.1.1"
git push origin v2.2.0
```

What the tag pipeline does, in order: `preflight` re-checks the tag against
`mod_version` and the changelog, all four targets build, both ends of the range
run the JUnit suite, the proxy harness runs, `jar_audit` inspects and checksums the
jars, and only then does `publish` create the release and upload assets:

```
nulltrace-1.21.11+vX.Y.Z.jar        + .sha256
nulltrace-26.1-26.1.2+vX.Y.Z.jar    + .sha256
nulltrace-26.2+vX.Y.Z.jar           + .sha256
nulltrace-26.3+vX.Y.Z.jar           + .sha256
SHA256SUMS
release-manifest.json
```

Then:

1. Confirm the release page lists ten assets, not four.
2. Download one jar and run the verification in the next section.
3. CurseForge is uploaded by hand. The in-game updater reads CurseForge through
   the public CFWidget API, so the version string published there must match the
   tag exactly.
4. Update the marketing site (it currently hardcodes the version) or point it at
   `release-manifest.json`.

A `mod_version` that is ahead of the newest tag is normal on `main` — that is how
the next release is staged. It only becomes a problem when a tag disagrees with
it, which `preflight` fails.

## Verifying a download

```bash
# in the directory with the jar and its .sha256
sha256sum -c nulltrace-26.3+v2.2.0.jar.sha256

# or the whole set at once
sha256sum -c SHA256SUMS
```

The sidecar files use bare file names and LF endings precisely so this works from
any directory. `release-manifest.json` records the commit, pipeline, per-file
hashes, size and the Minecraft/Java versions each jar targets.

## When a job fails

| Symptom | Look at | Usual cause |
| --- | --- | --- |
| `No more compute minutes available`, no trace | namespace quota | see [Compute minutes](#compute-minutes) |
| `preflight` fails on `META013` | `gradle.properties` vs tag | tagged `v2.1.2` without bumping `mod_version` |
| `preflight` fails on `META007b` | `README.md` target table | version range added to `versions/` but not to the table |
| `preflight` fails on `SRC004` | `nulltrace.client.mixins.json` | mixin listed without a `.java`, or a rename |
| `jar_audit` fails on `JAR015` | mixin config vs built classes | Stonecutter-gated entry that the target does not compile |
| `jar_audit` fails on `JAR006` | `processResources` | version from gradle.properties disagrees with the tag |
| `jar_audit` fails on `JAR010` | `java_version` per target | class files built for 21 while the target declares 25 |
| `publish` fails HTTP 401/403 | project access token | `CI_JOB_TOKEN` cannot write packages/releases if those are restricted |
| `publish` says `already exists (409)` then continues | release/package already present | re-running a tag pipeline; assets are re-uploaded over the old ones |

Re-running: a failed `publish` can be retried from the pipeline page; the script
is idempotent (it treats an existing release or asset link as fine). To rebuild a
release from a fixed commit, delete the tag remotely, fix, and re-tag — never move
a published tag silently.

## Pulling a bad release

```bash
# delete one asset
curl --request DELETE --header "PRIVATE-TOKEN: <token>" \
  "https://gitlab.com/api/v4/projects/<id>/packages/generic/nulltrace/v2.2.0/nulltrace-26.3%2Bv2.2.0.jar"

# delete the release entry (the tag stays)
curl --request DELETE --header "PRIVATE-TOKEN: <token>" \
  "https://gitlab.com/api/v4/projects/<id>/releases/v2.2.0"
```

The updater never offers an older version, so after pulling a release you must
publish a *higher* version with the fix. Do not reuse a version number: installs
that already fetched the bad build will not be prompted again.

## Local checks

```bash
./tools/ci/preflight.sh                      # everything CI does before Gradle
./tools/ci/preflight.sh --tag v2.2.0         # add the release gate
./tools/ci/preflight.sh --build 26.3         # + build, inspect and checksum the jar

python3 tools/ci/check_metadata.py           # release/target metadata only
python3 tools/ci/check_sources.py            # mixin wiring, entrypoints, secrets
python3 tools/ci/inspect_jar.py release/*.jar
python3 tools/ci/gen_sums.py --release-dir release [--verify]
python3 tools/ci/lint_gitlab_ci.py --scenario all
python3 tools/ci/selftest.py                 # 27 assertions that the checks fire
```

`NT_REPO_ROOT=/path/to/checkout` points the checks at another tree, which is how
the self-tests exercise them against fixtures.
