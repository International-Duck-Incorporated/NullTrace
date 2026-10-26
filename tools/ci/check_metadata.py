#!/usr/bin/env python3
"""Fast release-metadata consistency checks for NullTrace (no JDK, no Gradle).

Catches the class of mistake that currently only surfaces *after* a full Gradle
build, or worse, after a release has been published and the in-game updater will
not offer it:

  * a tag that disagrees with ``mod_version`` (the updater only prompts when the
    published version is strictly newer)
  * a ``versions/`` target that ``settings.gradle.kts`` never includes
  * a README support table that promises versions the build does not ship
  * a version bump with no ``CHANGELOG.md`` section

Usage::

    python3 tools/ci/check_metadata.py                 # branch / MR checks
    python3 tools/ci/check_metadata.py --tag v2.1.1    # release gate
"""

from __future__ import annotations

import argparse
import re
import sys

import ntci


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="release tag being built (e.g. v2.1.1)")
    parser.add_argument("--quiet", action="store_true", help="only show problems")
    args = parser.parse_args()

    ntci.guard_environment()
    report = ntci.Report("Release metadata")

    try:
        version = ntci.mod_version()
    except SystemExit as error:
        print(error, file=sys.stderr)
        return 1

    targets = ntci.version_targets()
    declared = ntci.settings_targets()
    readme = ntci.readme_targets()

    # META001 - mod_version is a plain semantic version
    if re.fullmatch(r"\d+\.\d+\.\d+", version):
        report.passed("META001", f"mod_version is {version}")
    else:
        report.failed(
            "META001",
            f"mod_version {version!r} is not X.Y.Z",
            "Tags, artifact names and the in-game updater all compare versions, "
            "so a suffix or extra segment will not round-trip.",
        )

    # META002 - versions/ directories match settings.gradle.kts
    if not targets:
        report.failed("META002", "no versions/*/gradle.properties found")
    elif sorted(targets) == sorted(declared):
        report.passed("META002", f"versions/ matches settings.gradle.kts: {', '.join(sorted(targets))}")
    else:
        report.failed(
            "META002",
            "versions/ and settings.gradle.kts disagree",
            f"on disk: {sorted(targets)} | settings: {sorted(declared)}",
        )

    # META003 - each target declares what the build script reads
    required = ("minecraft_version", "minecraft_version_range", "version_suffix", "loader_version")
    for name, props in sorted(targets.items()):
        missing = [key for key in required if not props.get(key)]
        if missing:
            report.failed(f"META003/{name}", f"missing properties: {', '.join(missing)}")
        else:
            report.passed(f"META003/{name}", f"{props['minecraft_version']} ({props['version_suffix']})")

    # META004 - the version a target builds against is inside its own range
    for name, props in sorted(targets.items()):
        built = props.get("minecraft_version", "")
        declared_range = props.get("minecraft_version_range", "")
        if not built or not declared_range:
            continue
        if ntci.satisfies(built, declared_range):
            report.passed(f"META004/{name}", f"{built} satisfies {declared_range}")
        else:
            report.failed(
                f"META004/{name}",
                f"{built} is outside its own declared range {declared_range}",
                "Fabric Loader will refuse to load the jar on the version it was built for.",
            )

    # META005 - declared ranges must not overlap (two jars claiming one version)
    names = sorted(targets)
    overlapping = []
    for index, left in enumerate(names):
        for right in names[index + 1 :]:
            left_range = targets[left].get("minecraft_version_range", "")
            right_range = targets[right].get("minecraft_version_range", "")
            if left_range and right_range and ntci.ranges_overlap(left_range, right_range):
                overlapping.append(f"{left} ({left_range}) vs {right} ({right_range})")
    if overlapping:
        report.failed("META005", "overlapping Minecraft ranges", "; ".join(overlapping))
    else:
        report.passed("META005", "no two targets claim the same Minecraft version")

    # META006 - version_suffix values are unique and filesystem safe
    suffixes = [props.get("version_suffix", "") for props in targets.values()]
    bad = [suffix for suffix in suffixes if not re.fullmatch(r"[0-9A-Za-z._\-]+", suffix or "")]
    if bad or len(set(suffixes)) != len(suffixes):
        report.failed(
            "META006",
            "version_suffix values must be unique and URL/artifact safe",
            f"{suffixes}",
        )
    else:
        report.passed("META006", f"artifact suffixes: {', '.join(suffixes)}")

    # META007 - README support table describes what the build actually ships
    if not readme:
        report.failed(
            "META007",
            "README has no 'Supported build targets' table",
            "Users pick a jar from this table; the release notes link to it.",
        )
    else:
        readme_names = [row["target"] for row in readme]
        if sorted(readme_names) != sorted(targets):
            report.failed(
                "META007a",
                "README target list does not match versions/",
                f"README: {readme_names} | versions/: {sorted(targets)}",
            )
        else:
            report.passed("META007a", f"README documents {len(readme_names)} targets")

        for row in readme:
            props = targets.get(row["target"])
            if not props:
                continue
            cell = row["minecraft"].replace("\u2013", "-").replace("\u2014", "-")
            built = props.get("minecraft_version", "")
            tokens = re.findall(r"\d+(?:\.\d+)+", cell)
            problems = []
            if built and built not in tokens:
                problems.append(f"built version {built} is not listed")
            declared_range = props.get("minecraft_version_range", "")
            outside = [t for t in tokens if declared_range and not ntci.satisfies(t, declared_range)]
            if outside:
                problems.append(f"{outside} outside {declared_range}")
            if not tokens:
                problems.append(f"no Minecraft version in {row['minecraft']!r}")
            if problems:
                report.failed(f"META007b/{row['target']}", "; ".join(problems))
            else:
                report.passed(f"META007b/{row['target']}", f"README says {row['minecraft']}")

            # Loader / API versions are what users are told to install next to it.
            if row["loader"] and props.get("loader_version") and row["loader"] != props["loader_version"]:
                report.failed(
                    f"META007c/{row['target']}",
                    f"README loader {row['loader']} != gradle.properties {props['loader_version']}",
                )
            if row["api"] and props.get("fabric_version") and row["api"] != props["fabric_version"]:
                report.failed(
                    f"META007d/{row['target']}",
                    f"README Fabric API {row['api']} != gradle.properties {props['fabric_version']}",
                )

    # META008 - fabric.mod.json stays templated (version and range are injected)
    manifest_path = ntci.ROOT / "src/main/resources/fabric.mod.json"
    templated = ntci.read_text(manifest_path) if manifest_path.is_file() else ""
    if '"version": "${version}"' in templated.replace("\t", " ") or "${version}" in templated:
        report.passed("META008", "fabric.mod.json injects ${version}")
    else:
        report.failed(
            "META008",
            "fabric.mod.json does not use ${version}",
            "A hardcoded version drifts from gradle.properties and publishes a jar "
            "the updater cannot offer.",
        )
    if "${minecraft_version_range}" in templated:
        report.passed("META009", "fabric.mod.json injects ${minecraft_version_range}")
    else:
        report.failed("META009", "fabric.mod.json does not use ${minecraft_version_range}")

    # META010 - CHANGELOG: a section for the current version, and an Unreleased home
    released = ntci.changelog_versions()
    if "## [Unreleased]" in ntci.read_text(ntci.ROOT / "CHANGELOG.md"):
        report.passed("META010", "CHANGELOG has an [Unreleased] section")
    else:
        report.warned("META010", "CHANGELOG has no [Unreleased] section")

    section = ntci.changelog_section(version)
    if section is None:
        report.failed(
            "META011",
            f"CHANGELOG.md has no '## [{version}]' section",
            "The release notes are generated from this file; an absent section means "
            "the bump is undocumented.",
        )
    elif not re.search(r"^### ", section, re.MULTILINE):
        report.warned(f"META011/{version}", "CHANGELOG section has no Added/Changed/Fixed headings")
    else:
        report.passed("META011", f"CHANGELOG documents {version}")

    if released and released[0] != version:
        if ntci.compare_versions(released[0], version) > 0:
            report.failed(
                "META012",
                f"CHANGELOG's newest entry is {released[0]}, ahead of mod_version {version}",
                "Either the bump or the changelog is wrong; published tags must move forward only.",
            )
        else:
            report.failed(
                "META012",
                f"CHANGELOG's newest entry is {released[0]}, behind mod_version {version}",
                "Unreleased work is missing its section.",
            )

    # META013 - release gate: tag, mod_version, changelog and artifacts must agree
    if args.tag:
        tag_version = args.tag[1:] if args.tag.startswith("v") else args.tag
        if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
            report.failed("META013", f"tag {args.tag!r} is not vX.Y.Z")
        elif tag_version != version:
            report.failed(
                "META013",
                f"tag {args.tag} does not match mod_version={version}",
                "Artifacts are named +v{mod_version}, so existing installs would never "
                "be offered the published build.",
            )
        else:
            report.passed("META013", f"tag {args.tag} matches mod_version")

        if section is None:
            report.failed("META014", f"refusing to tag {args.tag}: no changelog section")
        else:
            report.passed("META014", f"release notes available for {tag_version}")

    report.render(verbose=not args.quiet)
    return report.exit_code


if __name__ == "__main__":
    sys.exit(main())
