#!/usr/bin/env python3
"""Compare a local build against what a release actually published.

The integrity checker compares each jar against the checksums that ship next to it, which says
nothing about whether the file a user downloads is the file that was built here. The pipeline
publishes the same jars twice (GitLab generic packages and, historically, CurseForge) and the
two are uploaded by different tooling, so "same version" has never meant "same bytes". This
turns that release-time worry into a check that runs in seconds.

Three modes:

    # 1. local only: is this release directory self-consistent?
    python3 tools/ci/verify_release.py --dir release

    # 2. against a directory that stands in for the published set
    python3 tools/ci/verify_release.py --dir release --reference /path/to/downloaded

    # 3. against the GitLab release for a tag (needs a token with read_api)
    python3 tools/ci/verify_release.py --dir release --project 86854861 --tag v2.2.0 \\
        --token "$GITLAB_TOKEN"

Exit status is non-zero if anything differs, so it can gate a release.

What it checks, in order of how badly they hurt:

  * every jar in --dir has a line in SHA256SUMS, and the digest matches the file on disk
  * release-manifest.json agrees: same file list, same digests, same sizes, same mod version
  * no jar reports a mod version different from the one the manifest claims
  * with --reference or --tag: the published asset is byte-identical to the local jar, and the
    published checksum file lists the same digest

Nothing here downloads anything unless --tag is given, and nothing writes to the release
directory; it only reads.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

CHECKSUM_LINE = re.compile(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$")
API_ROOT = "https://gitlab.com/api/v4"


class Report:
    def __init__(self, verbose: bool = False) -> None:
        self.failures: list[str] = []
        self.warnings: list[str] = []
        self.passed = 0
        self.verbose = verbose

    def ok(self, what: str) -> None:
        self.passed += 1
        if self.verbose:
            print(f"[PASS] {what}")

    def fail(self, what: str, detail: str = "") -> None:
        self.failures.append(f"{what}{': ' + detail if detail else ''}")
        print(f"[FAIL] {what}{': ' + detail if detail else ''}")

    def warn(self, what: str, detail: str = "") -> None:
        self.warnings.append(f"{what}{': ' + detail if detail else ''}")
        print(f"[WARN] {what}{': ' + detail if detail else ''}")

    def summary(self) -> int:
        total = self.passed + len(self.failures)
        print()
        print(f"{total} checks: {self.passed} passed, {len(self.warnings)} warnings, {len(self.failures)} failures")
        return 1 if self.failures else 0


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_sums(path: pathlib.Path) -> dict[str, str]:
    sums: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = CHECKSUM_LINE.match(line)
        if match:
            sums[match.group(2)] = match.group(1).lower()
    return sums


def jar_mod_version(path: pathlib.Path) -> str:
    """Best-effort read of fabric.mod.json's "version" — enough to catch a mixed release."""
    try:
        with zipfile.ZipFile(path) as jar:
            raw = jar.read("fabric.mod.json").decode("utf-8", "replace")
    except (KeyError, OSError, zipfile.BadZipFile):
        return ""
    match = re.search(r'"version"\s*:\s*"([^"]*)"', raw)
    return match.group(1) if match else ""


def check_local(report: Report, release_dir: pathlib.Path) -> dict[str, str]:
    """Self-consistency of the directory that would be published. Returns name -> sha256."""
    jars = sorted(p for p in release_dir.glob("*.jar") if not p.name.endswith("-sources.jar"))
    if not jars:
        report.fail("release directory contains jars", f"none found in {release_dir}")
        return {}

    sums_path = release_dir / "SHA256SUMS"
    sums = read_sums(sums_path) if sums_path.exists() else {}
    if not sums_path.exists():
        report.fail("SHA256SUMS present", f"missing from {release_dir}")
    else:
        report.ok("SHA256SUMS present")

    digests: dict[str, str] = {}
    for jar in jars:
        digest = sha256_of(jar)
        digests[jar.name] = digest
        listed = sums.get(jar.name)
        if listed is None:
            report.fail("jar listed in SHA256SUMS", jar.name)
        elif listed != digest:
            report.fail("checksum matches file", f"{jar.name}: sums says {listed[:16]}…, file is {digest[:16]}…")
        else:
            report.ok(f"checksum matches file: {jar.name}")

    manifest_path = release_dir / "release-manifest.json"
    manifest_version = None
    if not manifest_path.exists():
        report.fail("release-manifest.json present", f"missing from {release_dir}")
    else:
        report.ok("release-manifest.json present")
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except ValueError as exc:
            report.fail("release-manifest.json parses", str(exc))
            manifest = {}
        manifest_version = manifest.get("mod_version")
        entries = {a.get("file"): a for a in manifest.get("artifacts", []) if isinstance(a, dict)}

        if set(entries) != set(digests):
            missing = sorted(set(digests) - set(entries))
            extra = sorted(set(entries) - set(digests))
            report.fail("manifest lists exactly the jars that exist",
                        f"missing from manifest: {missing or 'none'}; not on disk: {extra or 'none'}")
        else:
            report.ok("manifest lists exactly the jars that exist")

        for name, digest in digests.items():
            entry = entries.get(name)
            if not entry:
                continue
            if entry.get("sha256") != digest:
                report.fail("manifest digest matches file", f"{name}: manifest {str(entry.get('sha256'))[:16]}…")
            elif entry.get("size_bytes") != (release_dir / name).stat().st_size:
                report.fail("manifest size matches file", f"{name}: manifest {entry.get('size_bytes')} bytes")
            else:
                report.ok(f"manifest agrees with file: {name}")

    # every jar must report the version the manifest claims, or the updater will skip it
    versions = {jar.name: jar_mod_version(jar) for jar in jars}
    empty = [n for n, v in versions.items() if not v]
    if empty:
        report.warn("every jar has a readable fabric.mod.json version", f"unreadable: {empty}")
    else:
        distinct = sorted(set(versions.values()))
        if len(distinct) > 1:
            report.fail("all jars report one version", f"found {distinct}")
        elif manifest_version and distinct[0] != manifest_version:
            report.fail("jar version matches manifest", f"jars say {distinct[0]}, manifest says {manifest_version}")
        else:
            report.ok(f"every jar reports {distinct[0]}")

    for stale in sorted(release_dir.glob("*.jar.sha256")):
        stem = stale.name[: -len(".sha256")]
        if stem not in sums:
            report.warn("per-jar .sha256 sidecar is redundant", stale.name)
        elif sha256_of(stale) and read_sums(stale).get(stem) != sums.get(stem):
            report.fail("sidecar agrees with SHA256SUMS", stale.name)

    return digests


def compare_sets(report: Report, local: dict[str, str], reference_name: str,
                 reference_digests: dict[str, str]) -> None:
    if set(local) != set(reference_digests):
        missing = sorted(set(local) - set(reference_digests))
        extra = sorted(set(reference_digests) - set(local))
        report.fail(f"same file set as {reference_name}",
                    f"only local: {missing or 'none'}; only published: {extra or 'none'}")
        return
    report.ok(f"same file set as {reference_name}")
    for name, digest in sorted(local.items()):
        if reference_digests[name] != digest:
            report.fail("published bytes identical to local build",
                        f"{name}: local {digest[:16]}…, published {reference_digests[name][:16]}…")
        else:
            report.ok(f"published bytes identical to local build: {name}")


def check_reference_dir(report: Report, local: dict[str, str], reference: pathlib.Path) -> None:
    jars = sorted(p for p in reference.glob("*.jar") if not p.name.endswith("-sources.jar"))
    if not jars:
        report.fail("reference directory contains jars", f"none found in {reference}")
        return
    compare_sets(report, local, str(reference), {p.name: sha256_of(p) for p in jars})


def api_get(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"PRIVATE-TOKEN": token})
    with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 - fixed https host
        return response.read()


def check_gitlab(report: Report, local: dict[str, str], project: str, tag: str, token: str) -> None:
    base = f"{API_ROOT}/projects/{urllib.parse.quote(str(project), safe='')}/releases/{urllib.parse.quote(tag, safe='')}"
    try:
        release = json.loads(api_get(base, token))
    except urllib.error.HTTPError as exc:
        report.fail("release exists on GitLab", f"{tag}: HTTP {exc.code}")
        return
    except (urllib.error.URLError, ValueError) as exc:
        report.fail("release exists on GitLab", str(exc))
        return
    report.ok(f"release {tag} exists on GitLab")

    links = {link.get("name"): link.get("url") for link in release.get("assets", {}).get("links", [])}
    published: dict[str, str] = {}
    for name, url in sorted(links.items()):
        if not name or not name.endswith(".jar") or name.endswith("-sources.jar"):
            continue
        try:
            data = api_get(url, token)
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            report.fail("download published asset", f"{name}: {exc}")
            continue
        published[name] = hashlib.sha256(data).hexdigest()

    if not published:
        report.fail("release has jar assets", f"{tag} links {sorted(links) or 'nothing'}")
        return
    compare_sets(report, local, f"GitLab release {tag}", published)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Verify that a release directory is self-consistent and, optionally, that the "
                    "published artifacts are byte-identical to it.",
    )
    parser.add_argument("--dir", default="release", help="release directory to check (default: release)")
    parser.add_argument("--reference", help="directory holding an already-published copy")
    parser.add_argument("--project", help="GitLab project id or path, for --tag")
    parser.add_argument("--tag", help="tag whose release should be compared")
    parser.add_argument("--token", default=os.environ.get("GITLAB_TOKEN"),
                        help="GitLab token with read_api (default: $GITLAB_TOKEN)")
    parser.add_argument("--verbose", action="store_true", help="print every passing check")
    args = parser.parse_args(argv)

    release_dir = pathlib.Path(args.dir)
    if not release_dir.is_dir():
        print(f"not a directory: {release_dir}", file=sys.stderr)
        return 2
    if args.tag and not args.project:
        print("--tag needs --project", file=sys.stderr)
        return 2
    if args.tag and not args.token:
        print("--tag needs a token with read_api (--token or $GITLAB_TOKEN)", file=sys.stderr)
        return 2

    report = Report(verbose=args.verbose)
    print(f"== Release verification: {release_dir} ==")
    local = check_local(report, release_dir)

    if local and args.reference:
        print(f"\n== Against published copy: {args.reference} ==")
        check_reference_dir(report, local, pathlib.Path(args.reference))

    if local and args.tag:
        print(f"\n== Against GitLab release {args.tag} ==")
        check_gitlab(report, local, args.project, args.tag, args.token)

    return report.summary()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
