#!/usr/bin/env python3
"""Static checks that do not need a build: mixin wiring, entrypoints, secrets.

Everything here reads the source tree only, so it runs in a couple of seconds and
catches problems that otherwise cost a full Gradle build (or a user's startup log)
to discover:

  * a mixin config entry with no matching source file (Mixin hard-fails at boot)
  * a mixin class that ships but is never registered (silently dead protection)
  * a JSON template that only parses for one of the version targets
  * a fabric.mod.json entrypoint that names a class the source does not have
  * credential-shaped strings committed by accident

Usage::

    python3 tools/ci/check_sources.py
"""

from __future__ import annotations

import argparse
import json
import re
import sys

import ntci

CLIENT_JAVA = ntci.ROOT / "src/client/java"
MAIN_JAVA = ntci.ROOT / "src/main/java"
MIXIN_CONFIG = ntci.ROOT / "src/client/resources/nulltrace.client.mixins.json"
MOD_JSON = ntci.ROOT / "src/main/resources/fabric.mod.json"

# Templates build.gradle expands into the mixin config. Empty strings are the
# 26.x case (no refmap, no gated entries), so each variant must stay valid JSON.
TEMPLATE_VARIANTS = {
    "obfuscated (1.21.11)": {
        "mixin_refmap_entry": '\n\t"refmap": "client-nulltrace.refmap.json",',
        "known_packs_mixin_entry": '\n\t\t"KnownPacksManagerMixin",',
        "payload_type_registry_mixin_entry": (
            '\n\t\t"PayloadTypeRegistryImplMixin",\n\t\t"AbstractChanneledNetworkAddonMixin",'
        ),
        "mixin_compat_level": "JAVA_21",
        "version": "0.0.0",
        "minecraft_version_range": ">=1.21.11 <26.1",
        "java_version": "21",
    },
    "unobfuscated (26.x)": {
        "mixin_refmap_entry": "",
        "known_packs_mixin_entry": '\n\t\t"KnownPacksManagerMixin",',
        "payload_type_registry_mixin_entry": (
            '\n\t\t"PayloadTypeRegistryImplMixin",\n\t\t"AbstractChanneledNetworkAddonMixin",'
        ),
        "mixin_compat_level": "JAVA_25",
        "version": "0.0.0",
        "minecraft_version_range": ">=26.3 <26.4",
        "java_version": "25",
    },
    "minimal (gated entries empty)": {
        "mixin_refmap_entry": "",
        "known_packs_mixin_entry": "",
        "payload_type_registry_mixin_entry": "",
        "mixin_compat_level": "JAVA_21",
        "version": "0.0.0",
        "minecraft_version_range": ">=1.21.1",
        "java_version": "21",
    },
}

SECRET_PATTERNS = [
    ("Minecraft/Microsoft refresh token", re.compile(r"M\.C5\d{1,3}_BAY\.[A-Za-z0-9._-]{20,}")),
    ("JSON Web Token", re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("private key block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("GitLab personal token", re.compile(r"\bglpat-[A-Za-z0-9_-]{20,}\b")),
]


def render(template: str, variant: dict[str, str]) -> str:
    rendered = template
    for key, value in variant.items():
        rendered = rendered.replace("${" + key + "}", value)
    return rendered


def check_mixin_config(report: ntci.Report) -> None:
    if not MIXIN_CONFIG.is_file():
        report.failed("SRC001", f"missing mixin config {MIXIN_CONFIG.relative_to(ntci.ROOT)}")
        return

    template = ntci.read_text(MIXIN_CONFIG)
    left_overs = set(re.findall(r"\$\{([a-z_]+)\}", template))
    unknown = left_overs - set(TEMPLATE_VARIANTS["obfuscated (1.21.11)"])
    if unknown:
        report.failed(
            "SRC002",
            f"mixin config uses placeholders build.gradle does not expand: {sorted(unknown)}",
            "They would ship literally into the jar and Mixin would fail to parse the config.",
        )
    else:
        report.passed("SRC002", f"mixin config placeholders known: {sorted(left_overs)}")

    declared_entries: set[str] = set()
    for label, variant in TEMPLATE_VARIANTS.items():
        try:
            config = json.loads(render(template, variant))
        except json.JSONDecodeError as error:
            report.failed("SRC003", f"mixin config is not valid JSON for {label}: {error}")
            continue
        report.passed("SRC003", f"mixin config parses for {label}")
        entries = (
            list(config.get("client", []))
            + list(config.get("server", []))
            + list(config.get("mixins", []))
        )
        declared_entries.update(entries)
        package = config.get("package", "").replace(".", "/")
        missing = [
            entry
            for entry in entries
            if not (CLIENT_JAVA / package / f"{entry}.java").is_file()
        ]
        if missing:
            report.failed(
                "SRC004",
                f"{len(missing)} mixin entries have no source file ({label})",
                ", ".join(sorted(missing)),
            )
        else:
            report.passed("SRC004", f"{len(entries)} mixin entries resolve to source ({label})")

    # Every mixin source in the package should be registered in at least one variant.
    package = json.loads(render(template, TEMPLATE_VARIANTS["unobfuscated (26.x)"]))["package"]
    package_dir = CLIENT_JAVA / package.replace(".", "/")
    if package_dir.is_dir():
        sources = {
            path.stem
            for path in package_dir.glob("*.java")
        }
        unregistered = sorted(
            name
            for name in sources - declared_entries
            if "ntci: not-in-mixin-config" not in ntci.read_text(package_dir / f"{name}.java")
        )
        if unregistered:
            report.warned(
                "SRC005",
                f"{len(unregistered)} mixin source files are never registered",
                ", ".join(unregistered) + "  (annotate with '// ntci: not-in-mixin-config' if intentional)",
            )
        else:
            report.passed("SRC005", f"all {len(sources)} mixin sources are registered")


def check_entrypoints(report: ntci.Report) -> None:
    if not MOD_JSON.is_file():
        report.failed("SRC006", "src/main/resources/fabric.mod.json is missing")
        return
    text = ntci.read_text(MOD_JSON)
    try:
        manifest = json.loads(render(text, TEMPLATE_VARIANTS["unobfuscated (26.x)"]))
    except json.JSONDecodeError as error:
        report.failed("SRC006", f"fabric.mod.json is not valid JSON once templated: {error}")
        return
    report.passed("SRC006", "fabric.mod.json parses once templated")

    for kind, entries in manifest.get("entrypoints", {}).items():
        for entry in entries:
            class_path = entry.replace(".", "/") + ".java"
            if (CLIENT_JAVA / class_path).is_file() or (MAIN_JAVA / class_path).is_file():
                report.passed("SRC007", f"entrypoint {kind} -> {entry}")
            else:
                report.failed(
                    "SRC007",
                    f"entrypoint {kind} names {entry}, which has no source file",
                    "Fabric Loader throws at startup when an entrypoint class is absent.",
                )

    for config in manifest.get("mixins", []):
        path = ntci.ROOT / "src/client/resources" / config["config"]
        if path.is_file():
            report.passed("SRC008", f"mixin config {config['config']} exists")
        else:
            report.failed("SRC008", f"fabric.mod.json references missing mixin config {config['config']}")


def check_secrets(report: ntci.Report) -> None:
    findings: list[str] = []
    candidates = [
        *sorted((ntci.ROOT / "src").rglob("*.java")),
        *sorted((ntci.ROOT / "src").rglob("*.json")),
        *sorted((ntci.ROOT / "src").rglob("*.properties")),
        *(path for path in (ntci.ROOT / "build.gradle", ntci.ROOT / "gradle.properties", ntci.ROOT / ".gitlab-ci.yml") if path.is_file()),
        *sorted((ntci.ROOT / "tools").rglob("*.py")),
    ]
    for path in candidates:
        try:
            content = ntci.read_text(path)
        except (UnicodeDecodeError, OSError):
            continue
        for label, pattern in SECRET_PATTERNS:
            for match in pattern.finditer(content):
                line = content[: match.start()].count("\n") + 1
                findings.append(f"{path.relative_to(ntci.ROOT)}:{line} looks like a {label}")
    if findings:
        report.failed("SRC009", f"{len(findings)} credential-shaped string(s) in tracked sources", "; ".join(findings[:5]))
    else:
        report.passed("SRC009", f"no credential-shaped strings in {len(candidates)} source files")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only show problems")
    args = parser.parse_args()

    ntci.guard_environment()
    report = ntci.Report("Source tree consistency")
    check_mixin_config(report)
    check_entrypoints(report)
    check_secrets(report)
    report.render(verbose=not args.quiet)
    return report.exit_code


if __name__ == "__main__":
    sys.exit(main())
