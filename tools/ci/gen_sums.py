#!/usr/bin/env python3
"""Generate (or verify) release checksums and a provenance manifest.

The old pipeline wrote checksums with ``sha256sum release/<jar>``, which embeds the
``release/`` directory in the file. Users who download both files and run
``sha256sum -c nulltrace-....jar.sha256`` in their downloads directory therefore
get ``FAILED open or read``. This writes bare file names and LF endings instead,
plus the ``SHA256SUMS`` bundle and ``release-manifest.json`` that the v2.1.0
release notes promised but nothing in the pipeline produced.

Usage::

    python3 tools/ci/gen_sums.py --release-dir release          # write
    python3 tools/ci/gen_sums.py --release-dir release --verify  # check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import sys

import ntci


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version_of_jar(path: pathlib.Path) -> str:
    name = path.name
    marker = "+v"
    if marker in name and name.endswith(".jar"):
        return name.rsplit(marker, 1)[1][: -len(".jar")]
    return ""


def collect(release_dir: pathlib.Path) -> list[pathlib.Path]:
    return sorted(path for path in release_dir.glob("*.jar") if path.is_file())


def write_sums(release_dir: pathlib.Path, jars: list[pathlib.Path]) -> list[pathlib.Path]:
    written = []
    for jar in jars:
        checksum = sha256_of(jar)
        sidecar = jar.with_name(jar.name + ".sha256")
        # bare file name, explicit LF: this is what `sha256sum -c` expects
        sidecar.write_bytes(f"{checksum}  {jar.name}\n".encode("utf-8"))
        written.append(sidecar)
        print(f"wrote {sidecar.name} ({checksum[:16]}...)")

    bundle = release_dir / "SHA256SUMS"
    lines = [f"{sha256_of(jar)}  {jar.name}\n" for jar in jars]
    bundle.write_bytes("".join(lines).encode("utf-8"))
    written.append(bundle)
    print(f"wrote SHA256SUMS with {len(lines)} entries")
    return written


def write_manifest(release_dir: pathlib.Path, jars: list[pathlib.Path], target_props: dict[str, dict[str, str]]) -> pathlib.Path:
    version = ntci.mod_version()
    artifacts = []
    for jar in jars:
        suffix = jar.name.split("+v")[0].removeprefix("nulltrace-")
        props = next((p for p in target_props.values() if p.get("version_suffix") == suffix), {})
        artifacts.append(
            {
                "file": jar.name,
                "sha256": sha256_of(jar),
                "size_bytes": jar.stat().st_size,
                "mod_version": version_of_jar(jar),
                "target": suffix,
                "minecraft_version": props.get("minecraft_version", ""),
                "minecraft_range": props.get("minecraft_version_range", ""),
                "java_version": ntci.expected_java_version(suffix, props),
            }
        )

    env = os.environ
    manifest = {
        "schema": 1,
        "project": "nulltrace",
        "mod_version": version,
        "tag": env.get("CI_COMMIT_TAG") or None,
        "source": {
            "repository": env.get("CI_PROJECT_URL") or "https://gitlab.com/international-duck-incorporated/NullTrace",
            "commit": env.get("CI_COMMIT_SHA") or ntci.git("rev-parse", "HEAD") or None,
            "ref": env.get("CI_COMMIT_REF_NAME") or ntci.git("rev-parse", "--abbrev-ref", "HEAD") or None,
        },
        "pipeline": {
            "id": env.get("CI_PIPELINE_ID") or None,
            "url": env.get("CI_PIPELINE_URL") or None,
            "job": env.get("CI_JOB_ID") or None,
        },
        "artifacts": artifacts,
        "notes": [
            "Checksums use bare file names so `sha256sum -c` works in a downloads directory.",
            "Artifacts are the final mod jars only: no -dev or -sources variants.",
        ],
    }
    path = release_dir / "release-manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path.name} ({len(artifacts)} artifacts, version {version})")
    return path


def verify(release_dir: pathlib.Path, jars: list[pathlib.Path]) -> int:
    problems: list[str] = []
    for jar in jars:
        sidecar = jar.with_name(jar.name + ".sha256")
        if not sidecar.is_file():
            problems.append(f"missing {sidecar.name}")
            continue
        declared = sidecar.read_bytes().decode("utf-8")
        if b"\r" in sidecar.read_bytes():
            problems.append(f"{sidecar.name} has CRLF line endings")
        parts = declared.split()
        if len(parts) < 2:
            problems.append(f"{sidecar.name} is malformed: {declared!r}")
            continue
        if pathlib.PurePath(parts[1]).name != jar.name or "/" in parts[1]:
            problems.append(f"{sidecar.name} references {parts[1]!r}, not the bare name {jar.name!r}")
        actual = sha256_of(jar)
        if parts[0] != actual:
            problems.append(f"{sidecar.name} says {parts[0][:16]}..., file is {actual[:16]}...")

    bundle = release_dir / "SHA256SUMS"
    if not bundle.is_file():
        problems.append("missing SHA256SUMS")
    else:
        listed = {
            line.split()[1]: line.split()[0]
            for line in bundle.read_text(encoding="utf-8").splitlines()
            if line.strip()
        }
        if set(listed) != {jar.name for jar in jars}:
            problems.append(f"SHA256SUMS lists {sorted(listed)}, jars are {sorted(jar.name for jar in jars)}")

    manifest_path = release_dir / "release-manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for artifact in manifest.get("artifacts", []):
            jar = release_dir / artifact["file"]
            if not jar.is_file() or sha256_of(jar) != artifact["sha256"]:
                problems.append(f"manifest entry for {artifact['file']} does not match the file")

    if problems:
        print("checksum verification FAILED:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"checksums verified for {len(jars)} jar(s)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", default="release", type=pathlib.Path)
    parser.add_argument("--verify", action="store_true", help="check existing files instead of writing")
    args = parser.parse_args()

    release_dir = args.release_dir
    if not release_dir.is_dir():
        print(f"error: {release_dir} does not exist", file=sys.stderr)
        return 1
    jars = collect(release_dir)
    if not jars:
        print(f"error: no jars in {release_dir}", file=sys.stderr)
        return 1

    if args.verify:
        return verify(release_dir, jars)

    ntci.guard_environment()
    written = write_sums(release_dir, jars)
    written.append(write_manifest(release_dir, jars, ntci.version_targets()))
    return verify(release_dir, jars)


if __name__ == "__main__":
    sys.exit(main())
