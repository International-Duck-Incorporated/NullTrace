#!/usr/bin/env python3
"""Audit built NullTrace jars with the standard library only.

This replaces the shell block that used to live in the ``security_audit`` job:
that version needed Gradle and network access to run, and it only checked that a
refmap named in a mixin config was also present in the jar. Everything below is a
plain zip read, so it runs in a ``python:3.12-alpine`` job in a few seconds.

Checks cover the things that break users at startup rather than at build time:
unexpanded ``${templates}``, a version that disagrees with the tag, a Mixin
config that names a class the jar does not contain, entrypoints that do not
exist, JiJ jars that will not load, and class files compiled for a Java release
that does not match the ``depends.java`` the mod declares.

Usage::

    python3 tools/ci/inspect_jar.py release/*.jar
    python3 tools/ci/inspect_jar.py --version 2.1.1 --strict release/*.jar
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import struct
import sys
import zipfile

import ntci

# class file major version -> Java release
CLASS_MAJOR_TO_JAVA = {52: 8, 55: 11, 61: 17, 62: 18, 63: 19, 64: 20, 65: 21, 66: 22, 67: 23, 68: 24, 69: 25}

CREDENTIAL_PATTERNS = [
    ("Minecraft/Microsoft refresh token", re.compile(r"M\.C5\d{1,3}_BAY\.[A-Za-z0-9._-]{20,}")),
    ("JSON Web Token", re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("private key block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
]

NAME_PATTERN = re.compile(r"^nulltrace-(?P<suffix>.+?)\+v(?P<version>\d+\.\d+\.\d+)\.jar$")


def class_major(data: bytes) -> int | None:
    if data[:4] != b"\xca\xfe\xba\xbe" or len(data) < 8:
        return None
    return struct.unpack(">H", data[6:8])[0]


def audit(jar_path, expected_version: str | None, strict: bool, targets: dict[str, dict[str, str]]) -> ntci.Report:
    report = ntci.Report(f"Jar audit: {jar_path.name}")
    name_match = NAME_PATTERN.match(jar_path.name)
    target_props = None
    if name_match:
        suffix = name_match.group("suffix")
        for props in targets.values():
            if props.get("version_suffix") == suffix:
                target_props = props
                break

    try:
        archive = zipfile.ZipFile(jar_path)
    except (zipfile.BadZipFile, OSError) as error:
        report.failed("JAR001", f"not a readable jar: {error}")
        report.render()
        return report
    names = archive.namelist()

    # JAR001 - readable, non-empty, free of duplicate entries
    report.passed("JAR001", f"{len(names)} entries, {jar_path.stat().st_size / 1024:.0f} KiB")
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        report.failed("JAR002", "duplicate zip entries", ", ".join(duplicates[:5]))
    else:
        report.passed("JAR002", "no duplicate entries")

    # JAR003 - fabric.mod.json present, templated, parseable
    if "fabric.mod.json" not in names:
        report.failed("JAR003", "fabric.mod.json is missing")
        report.render()
        return report
    raw_manifest = archive.read("fabric.mod.json").decode("utf-8", "replace")
    unexpanded = sorted(set(re.findall(r"\$\{[a-z_]+\}", raw_manifest)))
    if unexpanded:
        report.failed(
            "JAR004",
            f"unexpanded template variables in fabric.mod.json: {unexpanded}",
            "ProcessResources did not run, so Loader will reject or mis-handle the metadata.",
        )
    else:
        report.passed("JAR004", "no unexpanded template variables")
    try:
        manifest = json.loads(raw_manifest)
    except json.JSONDecodeError as error:
        report.failed("JAR003", f"fabric.mod.json is not valid JSON: {error}")
        report.render()
        return report
    report.passed("JAR003", "fabric.mod.json parses")

    # JAR005 - identity and version agree with the file name (and the tag)
    if manifest.get("id") != "nulltrace":
        report.failed("JAR005", f"mod id is {manifest.get('id')!r}, expected 'nulltrace'")
    else:
        report.passed("JAR005", "mod id is nulltrace")

    version = manifest.get("version", "")
    problems = []
    if name_match and version != name_match.group("version"):
        problems.append(f"file name says {name_match.group('version')}")
    if expected_version and version != expected_version:
        problems.append(f"--version says {expected_version}")
    if problems:
        report.failed("JAR006", f"fabric.mod.json version {version!r} disagrees: {', '.join(problems)}")
    elif re.fullmatch(r"\d+\.\d+\.\d+", version):
        report.passed("JAR006", f"version {version}")
    else:
        report.failed("JAR007", f"version {version!r} is not X.Y.Z")
    if name_match is None:
        report.failed(
            "JAR008",
            f"file name {jar_path.name!r} is not nulltrace-<suffix>+v<version>.jar",
            "The updater matches asset names against the running Minecraft version.",
        )

    # JAR009 - declared Minecraft range matches the target it was built for
    declared_mc = manifest.get("depends", {}).get("minecraft", "")
    if target_props:
        expected_range = target_props.get("minecraft_version_range", "")
        if expected_range and declared_mc != expected_range:
            report.failed("JAR009", f"depends.minecraft is {declared_mc!r}, target declares {expected_range!r}")
        else:
            report.passed("JAR009", f"minecraft {declared_mc} ({target_props.get('minecraft_version')})")
    elif declared_mc:
        report.warned("JAR009", f"no target matched this file name; depends.minecraft is {declared_mc!r}")

    # JAR010 - class files were compiled for the Java release the mod declares
    majors: dict[int, int] = {}
    for name in names:
        if name.endswith(".class"):
            major = class_major(archive.read(name))
            if major:
                majors[major] = majors.get(major, 0) + 1
    declared_java = manifest.get("depends", {}).get("java", "")
    declared_major = re.search(r"(\d+)", declared_java)
    if not majors:
        report.failed("JAR010", "no class files in the jar")
    elif declared_major:
        wanted = int(declared_major.group(1))
        actual = {CLASS_MAJOR_TO_JAVA.get(major, major) for major in majors}
        if actual == {wanted}:
            report.passed("JAR010", f"{sum(majors.values())} classes compiled for Java {wanted}")
        else:
            report.failed(
                "JAR010",
                f"class files target Java {sorted(actual)} but depends.java is {declared_java!r}",
                "Mismatched bytecode and metadata produce 'Unsupported class file version' at startup.",
            )
    else:
        report.warned("JAR011", f"depends.java missing; class file majors {sorted(majors)}")

    # JAR012 - entrypoint classes are actually in the jar
    missing_entrypoints = []
    for kind, entries in manifest.get("entrypoints", {}).items():
        for entry in entries:
            if entry.replace(".", "/") + ".class" not in names:
                missing_entrypoints.append(f"{kind}:{entry}")
    if missing_entrypoints:
        report.failed("JAR012", "entrypoint classes missing from jar", ", ".join(missing_entrypoints))
    else:
        report.passed("JAR012", "all entrypoint classes present")

    # JAR013/14 - mixin configs: parseable, complete, and no dead mixins
    for mixin in manifest.get("mixins", []):
        config_name = mixin["config"]
        if config_name not in names:
            report.failed("JAR013", f"mixin config {config_name} is not in the jar")
            continue
        try:
            config = json.loads(archive.read(config_name))
        except json.JSONDecodeError as error:
            report.failed("JAR013", f"{config_name} is not valid JSON: {error}")
            continue
        report.passed("JAR013", f"{config_name} parses")
        refmap = config.get("refmap")
        if refmap and refmap not in names:
            report.failed(
                "JAR014",
                f"{config_name} declares refmap {refmap!r} but the jar has no such file",
                "Mixin logs 'Reference map could not be read' on every startup.",
            )
        elif refmap:
            report.passed("JAR014", f"declared refmap {refmap} present")
        else:
            report.passed("JAR014", "no refmap declared (unobfuscated target)")

        package = config.get("package", "").replace(".", "/") + "/"
        declared = set(config.get("client", [])) | set(config.get("server", [])) | set(config.get("mixins", []))
        shipped = {
            name[len(package) : -len(".class")].split("$")[0]
            for name in names
            if name.startswith(package) and name.endswith(".class")
        }
        absent = sorted(declared - shipped)
        if absent:
            noun = "mixin" if len(absent) == 1 else "mixins"
            report.failed(
                "JAR015",
                f"{len(absent)} declared {noun} have no class in the jar",
                ", ".join(absent) + " -> Mixin aborts startup",
            )
        else:
            report.passed("JAR015", f"{len(declared)} declared mixin entries all ship")
        dead = sorted(shipped - declared)
        if dead:
            (report.failed if strict else report.warned)(
                "JAR016",
                f"{len(dead)} mixin classes ship but are never registered",
                ", ".join(dead) + " -> the protection they implement cannot run",
            )
        else:
            report.passed("JAR016", "no unregistered mixin classes")

    # JAR017 - jar-in-jar dependencies load
    nested = [name for name in names if name.startswith("META-INF/jars/") and name.endswith(".jar")]
    outer_id = manifest.get("id")
    inner_ids: list[str] = []
    broken: list[str] = []
    for name in nested:
        try:
            inner = zipfile.ZipFile(_BytesReader(archive.read(name)))
        except (zipfile.BadZipFile, OSError):
            broken.append(name)
            continue
        if "fabric.mod.json" not in inner.namelist():
            broken.append(f"{name} (no fabric.mod.json)")
            continue
        try:
            inner_ids.append(json.loads(inner.read("fabric.mod.json")).get("id", "?"))
        except json.JSONDecodeError:
            broken.append(f"{name} (bad metadata)")
    if broken:
        report.failed("JAR017", "unreadable nested jars", ", ".join(broken))
    elif inner_ids and (outer_id in inner_ids or len(set(inner_ids)) != len(inner_ids)):
        report.failed("JAR018", f"nested mod ids collide: {inner_ids} (outer id {outer_id})")
    else:
        report.passed("JAR017", f"{len(nested)} nested jar(s): {', '.join(inner_ids) or 'none'}")

    # JAR019 - license ships with the jar
    if any(name.startswith("LICENSE") for name in names):
        report.passed("JAR019", "LICENSE present")
    else:
        report.failed("JAR019", "LICENSE is missing from the jar", "GPL-3.0 requires the license to travel with binaries.")

    # JAR020 - JSON resources parse (lang files are shipped UI strings)
    broken_json = []
    for name in names:
        if name.endswith(".json") and not name.startswith("META-INF/"):
            try:
                json.loads(archive.read(name))
            except json.JSONDecodeError as error:
                broken_json.append(f"{name}: {error}")
    if broken_json:
        report.failed("JAR020", "invalid JSON resources", "; ".join(broken_json[:3]))
    else:
        report.passed("JAR020", f"{sum(1 for n in names if n.endswith('.json'))} JSON resources parse")

    # JAR021 - nothing that only belongs in a development build
    shipped_sources = [name for name in names if name.endswith((".java", ".kt"))]
    if shipped_sources:
        report.failed("JAR021", "source files inside the jar", ", ".join(shipped_sources[:3]))
    elif name_match and re.search(r"-(dev|sources)$", name_match.group("suffix")):
        report.failed("JAR021", "development/source artifact was published instead of the mod jar")
    else:
        report.passed("JAR021", "release jar contains no sources or dev artifacts")

    # JAR022 - no credential-shaped strings in text resources
    hits = []
    for name in names:
        if name.endswith((".json", ".txt", ".properties", ".toml", ".yml", ".yaml")):
            try:
                content = archive.read(name).decode("utf-8", "replace")
            except OSError:
                continue
            for label, pattern in CREDENTIAL_PATTERNS:
                if pattern.search(content):
                    hits.append(f"{name} ({label})")
    if hits:
        report.failed("JAR022", "credential-shaped strings inside the jar", ", ".join(hits))
    else:
        report.passed("JAR022", "no credential-shaped strings in resources")

    report.render()
    return report


class _BytesReader:
    """Minimal seekable file-like wrapper so zipfile can read nested jars."""

    def __init__(self, data: bytes) -> None:
        self._data = data
        self._position = 0

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            chunk = self._data[self._position :]
            self._position = len(self._data)
            return chunk
        chunk = self._data[self._position : self._position + size]
        self._position += len(chunk)
        return chunk

    def seek(self, offset: int, whence: int = 0) -> int:
        if whence == 0:
            self._position = offset
        elif whence == 1:
            self._position += offset
        else:
            self._position = len(self._data) + offset
        return self._position

    def tell(self) -> int:
        return self._position

    def seekable(self) -> bool:
        return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jars", nargs="+", type=pathlib.Path)
    parser.add_argument("--version", help="release version every jar must report (e.g. 2.1.1)")
    parser.add_argument("--strict", action="store_true", help="treat unregistered mixins as failures")
    args = parser.parse_args()

    ntci.guard_environment()
    targets = ntci.version_targets()
    exit_code = 0
    for jar in args.jars:
        if not jar.is_file():
            print(f"[FAIL] JAR000: {jar} does not exist", file=sys.stderr)
            exit_code = 1
            continue
        report = audit(jar, args.version, args.strict, targets)
        exit_code = max(exit_code, report.exit_code)
        print()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
