#!/usr/bin/env python3
"""Self-tests for the CI tooling.

CI checks are only worth having if they fail when they should. This builds
synthetic jars and a synthetic repository, mutates them one defect at a time, and
asserts the expected check code fires. It runs in about a second and needs
nothing but the standard library.

Usage::

    python3 tools/ci/selftest.py
    python3 tools/ci/selftest.py -v      # list every case
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

TOOLS = pathlib.Path(__file__).resolve().parent
CLASS_MAJOR = {"21": 65, "25": 69}


def fake_class(java: str = "25") -> bytes:
    return b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, CLASS_MAJOR[java]) + b"\x00" * 8


def jar_entries(version: str = "2.1.1", mc_range: str = ">=26.3 <26.4", java: str = "25") -> dict[str, bytes]:
    manifest = {
        "schemaVersion": 1,
        "id": "nulltrace",
        "version": version,
        "name": "NullTrace",
        "entrypoints": {"client": ["net.nulltrace.mod.NullTraceClient"]},
        "mixins": [{"config": "nulltrace.client.mixins.json", "environment": "client"}],
        "depends": {"minecraft": mc_range, "java": f">={java}"},
    }
    mixin_config = {
        "required": True,
        "package": "net.nulltrace.mod.mixin.client",
        "compatibilityLevel": f"JAVA_{java}",
        "client": ["TitleScreenMixin"],
    }
    return {
        "fabric.mod.json": json.dumps(manifest, indent=2).encode(),
        "nulltrace.client.mixins.json": json.dumps(mixin_config, indent=2).encode(),
        "net/nulltrace/mod/NullTraceClient.class": fake_class(java),
        "net/nulltrace/mod/mixin/client/TitleScreenMixin.class": fake_class(java),
        "assets/nulltrace/nulltracelang/en_us.json": b'{"key": "value"}',
        "LICENSE_nulltrace-26.3": b"GPL-3.0-only\n",
    }


def write_jar(path: pathlib.Path, entries: dict[str, bytes]) -> pathlib.Path:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return path


def run(script: str, *args: str, cwd: pathlib.Path | None = None, env: dict | None = None) -> tuple[int, str]:
    return run_cmd([sys.executable, str(TOOLS / script), *args], cwd=cwd, env=env)


def run_cmd(command: list[str], cwd: pathlib.Path | None = None, env: dict | None = None) -> tuple[int, str]:
    environment = dict(os.environ)
    environment.update(env or {})
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        cwd=cwd or TOOLS.parents[1],
        env=environment,
    )
    return result.returncode, result.stdout + result.stderr


class SelfTest:
    def __init__(self) -> None:
        self.results: list[tuple[bool, str, str]] = []

    def check(self, name: str, expected_code: int, returncode: int, output: str, expect: str | None = None) -> None:
        ok = returncode == expected_code
        detail = f"exit {returncode} (expected {expected_code})"
        if ok and expect:
            ok = expect in output
            detail += f", looking for {expect!r}"
            if not ok:
                detail += " -> missing"
        self.results.append((ok, name, detail if not ok else ""))

    def summary(self) -> int:
        failures = [entry for entry in self.results if not entry[0]]
        for ok, name, detail in self.results:
            if not ok or VERBOSE:
                print(f"  {'ok  ' if ok else 'FAIL'} {name}{'' if ok else '  <- ' + detail}")
        print(f"\n{len(self.results) - len(failures)}/{len(self.results)} self-tests passed")
        return 1 if failures else 0


def jar_cases(tests: SelfTest, workdir: pathlib.Path) -> None:
    good = write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", jar_entries())

    code, out = run("inspect_jar.py", str(good))
    tests.check("clean jar passes", 0, code, out)

    code, out = run("inspect_jar.py", "--version", "2.1.1", str(good))
    tests.check("clean jar passes the tag gate", 0, code, out)

    code, out = run("inspect_jar.py", "--version", "9.9.9", str(good))
    tests.check("version disagrees with tag", 1, code, out, "JAR006")

    entries = jar_entries()
    del entries["net/nulltrace/mod/mixin/client/TitleScreenMixin.class"]
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("declared mixin has no class", 1, code, out, "JAR015")

    entries = jar_entries()
    config = json.loads(entries["nulltrace.client.mixins.json"])
    config["refmap"] = "client-nulltrace.refmap.json"
    entries["nulltrace.client.mixins.json"] = json.dumps(config).encode()
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("declared refmap missing", 1, code, out, "JAR014")

    entries = jar_entries()
    entries["fabric.mod.json"] = entries["fabric.mod.json"].replace(b'"version": "2.1.1"', b'"version": "${version}"')
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("unexpanded template variable", 1, code, out, "JAR004")

    entries = jar_entries()
    entries["net/nulltrace/mod/mixin/client/GhostMixin.class"] = fake_class()
    ghost = write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)
    code, out = run("inspect_jar.py", str(ghost))
    tests.check("unregistered mixin warns", 0, code, out, "JAR016")
    code, out = run("inspect_jar.py", "--strict", str(ghost))
    tests.check("unregistered mixin fails --strict", 1, code, out, "JAR016")

    entries = jar_entries()
    del entries["LICENSE_nulltrace-26.3"]
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("license missing", 1, code, out, "JAR019")

    entries = jar_entries(java="21")
    entries["fabric.mod.json"] = entries["fabric.mod.json"].replace(b'"java": ">=21"', b'"java": ">=25"')
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("bytecode/java metadata mismatch", 1, code, out, "JAR010")

    entries = jar_entries()
    entries["assets/nulltrace/nulltracelang/en_us.json"] = b"{not json"
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("broken JSON resource", 1, code, out, "JAR020")

    entries = jar_entries()
    entries["assets/nulltrace/nulltracelang/en_us.json"] = json.dumps(
        {"token": "M.C5" + "1_BAY." + "A" * 40}
    ).encode()
    code, out = run("inspect_jar.py", str(write_jar(workdir / "nulltrace-26.3+v2.1.1.jar", entries)))
    tests.check("credential-shaped string in resources", 1, code, out, "JAR022")

    renamed = write_jar(workdir / "NullTrace-26.3-2026.jar", jar_entries())
    code, out = run("inspect_jar.py", str(renamed))
    tests.check("unexpected artifact file name", 1, code, out, "JAR008")

    # checksums: bare names, LF, and the regression the old CI introduced
    release = workdir / "release"
    release.mkdir(exist_ok=True)
    shutil.copy(good, release / good.name)
    code, out = run("gen_sums.py", "--release-dir", str(release))
    tests.check("gen_sums writes checksums", 0, code, out, "checksums verified")
    sidecar = (release / (good.name + ".sha256")).read_bytes()
    tests.check("sidecar uses a bare file name", 0, int(b"release/" in sidecar), "")
    tests.check("sidecar uses LF endings", 0, int(b"\r" in sidecar), "")
    sibling = run_cmd(["sha256sum", "-c", good.name + ".sha256"], cwd=release)
    tests.check("sha256sum -c accepts the sidecar", 0, sibling[0], sibling[1])

    # the old behaviour must be rejected by --verify
    (release / (good.name + ".sha256")).write_bytes(
        f"{'0' * 64}  release/{good.name}\n".encode()
    )
    code, out = run("gen_sums.py", "--release-dir", str(release), "--verify")
    tests.check("verify rejects a release/-prefixed sidecar", 1, code, out, "bare name")


def fixture_repo(root: pathlib.Path, *, mod_version: str = "2.1.1", readme_mc: str = "26.3", changelog: str = "2.1.1") -> None:
    (root / "versions/26.3").mkdir(parents=True)
    (root / "versions/26.3/gradle.properties").write_text(
        "minecraft_version=26.3\nminecraft_version_range=>=26.3 <26.4\n"
        "version_suffix=26.3\nloader_version=0.19.5\nfabric_version=0.160.5+26.3\n"
    )
    (root / "gradle.properties").write_text(f"mod_version={mod_version}\n")
    (root / "settings.gradle.kts").write_text('versions("26.3")\n')
    (root / "README.md").write_text(
        "| Target | Minecraft versions | Java | Fabric Loader used to build | Fabric API used to build |\n"
        "| --- | --- | --- | --- | --- |\n"
        f"| `26.3` | {readme_mc} | 25 | 0.19.5 | 0.160.5+26.3 |\n"
    )
    (root / "CHANGELOG.md").write_text(
        f"# Changelog\n\n## [Unreleased]\n\n## [{changelog}] - 2026-10-17\n\n### Added\n- something\n"
    )
    (root / "src/main/resources").mkdir(parents=True)
    (root / "src/main/resources/fabric.mod.json").write_text(
        '{"version": "${version}", "depends": {"minecraft": "${minecraft_version_range}"}}\n'
    )


def metadata_cases(tests: SelfTest, workdir: pathlib.Path) -> None:
    def run_metadata(repo: pathlib.Path, *args: str) -> tuple[int, str]:
        return run("check_metadata.py", *args, env={"NT_REPO_ROOT": str(repo)})

    repo = workdir / "repo-ok"
    fixture_repo(repo)
    code, out = run_metadata(repo)
    tests.check("consistent fixture passes", 0, code, out)

    code, out = run_metadata(repo, "--tag", "v2.1.1")
    tests.check("matching tag passes", 0, code, out, "META013")

    code, out = run_metadata(repo, "--tag", "v2.1.2")
    tests.check("tag/mod_version mismatch fails", 1, code, out, "META013")

    drifted = workdir / "repo-readme"
    fixture_repo(drifted, readme_mc="26.1")
    code, out = run_metadata(drifted)
    tests.check("README advertises the wrong version", 1, code, out, "META007b")

    undocumented = workdir / "repo-changelog"
    fixture_repo(undocumented, mod_version="2.2.0", changelog="2.1.1")
    code, out = run_metadata(undocumented)
    tests.check("version bumped without a changelog section", 1, code, out, "META012")


def lint_case(tests: SelfTest) -> None:
    code, out = run("lint_gitlab_ci.py", "--scenario", "tag")
    tests.check("real .gitlab-ci.yml lints", 0, code, out, "scenario: tag")
    for scenario in ("main", "branch", "merge_request"):
        code, out = run("lint_gitlab_ci.py", "--scenario", scenario, "--quiet")
        tests.check(f"scenario {scenario} simulates", 0, code, out, "job(s)")


def main() -> int:
    global VERBOSE
    VERBOSE = "-v" in sys.argv
    tests = SelfTest()
    with tempfile.TemporaryDirectory() as directory:
        workdir = pathlib.Path(directory)
        jar_cases(tests, workdir)
        metadata_cases(tests, workdir / "meta")
        lint_case(tests)
    return tests.summary()


VERBOSE = False

if __name__ == "__main__":
    sys.exit(main())
