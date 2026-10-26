"""Shared helpers for the NullTrace CI checks.

Standard library only, and deliberately dependency-free: every job that imports
this runs in a stock ``python:3.12-alpine`` image, so the checks cost seconds and
do not need a JDK, Gradle, or a warm dependency cache.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys

# tools/ci/ntci.py -> repository root. NT_REPO_ROOT lets the self-tests point the
# checks at a fixture tree instead of the real checkout.
ROOT = pathlib.Path(
    os.environ.get("NT_REPO_ROOT") or pathlib.Path(__file__).resolve().parents[2]
).resolve()

PASS = "PASS"
WARN = "WARN"
FAIL = "FAIL"

_ORDER = {PASS: 0, WARN: 1, FAIL: 2}


class Report:
    """Collects check results and decides the process exit code."""

    def __init__(self, title: str) -> None:
        self.title = title
        self.entries: list[tuple[str, str, str, str]] = []  # level, check id, message, detail
        self.checks = 0

    def _add(self, level: str, check: str, message: str, detail: str = "") -> None:
        self.checks += 1
        self.entries.append((level, check, message, detail))

    def passed(self, check: str, message: str, detail: str = "") -> None:
        self._add(PASS, check, message, detail)

    def warned(self, check: str, message: str, detail: str = "") -> None:
        self._add(WARN, check, message, detail)

    def failed(self, check: str, message: str, detail: str = "") -> None:
        self._add(FAIL, check, message, detail)

    @property
    def exit_code(self) -> int:
        return 1 if any(level == FAIL for level, *_ in self.entries) else 0

    def counts(self, level: str) -> int:
        return sum(1 for entry in self.entries if entry[0] == level)

    def render(self, verbose: bool = True) -> None:
        print(f"== {self.title} ==")
        for level, check, message, detail in self.entries:
            if not verbose and level == PASS:
                continue
            if detail:
                print(f"[{level}] {check}: {message}\n         {detail}")
            else:
                print(f"[{level}] {check}: {message}")
        total = self.checks
        print(
            f"\n{total} checks: {self.counts(PASS)} passed, "
            f"{self.counts(WARN)} warnings, {self.counts(FAIL)} failures"
        )

    def emit_gitlab_section(self) -> None:
        """Emit a collapsible section so long output stays readable in GitLab."""
        if not _is_gitlab():
            return
        print("[section_start:ci_checks:CI checks]", flush=True)


def _is_gitlab() -> bool:
    import os

    return bool(os.environ.get("GITLAB_CI"))


def guard_environment() -> None:
    """Keep the log tidy and warn early when run outside the repo."""
    if not (ROOT / "gradle.properties").is_file():
        print(
            f"warning: {ROOT} does not look like the NullTrace repository "
            "(gradle.properties is missing)",
            file=sys.stderr,
        )


# --------------------------------------------------------------------------- io


def read_text(path: pathlib.Path | str) -> str:
    return pathlib.Path(path).read_text(encoding="utf-8")


def load_properties(path: pathlib.Path | str) -> dict[str, str]:
    """Parse a Gradle ``.properties`` file (key=value, ``#`` comments)."""
    props: dict[str, str] = {}
    for raw in read_text(path).splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def load_json(path: pathlib.Path | str) -> dict:
    return json.loads(read_text(path))


def git(*args: str) -> str:
    """Run a git command in the repo, returning stdout ('' when unavailable)."""
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


# ------------------------------------------------------------------ versions


def version_key(value: str) -> tuple[int, ...]:
    """Sortable key for versions like ``26.1.2``; non-numeric parts become 0."""
    parts = [int(p) if p.isdigit() else 0 for p in re.split(r"[.\-+]", value) if p]
    return tuple(parts) or (0,)


def compare_versions(left: str, right: str) -> int:
    a, b = version_key(left), version_key(right)
    width = max(len(a), len(b))
    a += (0,) * (width - len(a))
    b += (0,) * (width - len(b))
    return (a > b) - (a < b)


def parse_range(expression: str) -> list[tuple[str, str]]:
    """Parse ``>=26.1 <26.2`` into ``[('>=', '26.1'), ('<', '26.2')]``."""
    constraints: list[tuple[str, str]] = []
    for token in expression.split():
        match = re.fullmatch(r"(>=|<=|>|<|=)?([0-9][\w.\-+]*)", token)
        if not match:
            raise ValueError(f"unsupported version range token: {token!r}")
        operator = match.group(1) or "="
        constraints.append((operator, match.group(2)))
    return constraints


def satisfies(version: str, expression: str) -> bool:
    for operator, bound in parse_range(expression):
        result = compare_versions(version, bound)
        if operator == ">=" and result < 0:
            return False
        if operator == ">" and result <= 0:
            return False
        if operator == "<=" and result > 0:
            return False
        if operator == "<" and result >= 0:
            return False
        if operator == "=" and result != 0:
            return False
    return True


def ranges_overlap(left: str, right: str) -> bool:
    """True when two ``>=x <y`` style ranges share any version."""
    def bounds(expr: str) -> tuple[tuple[int, ...] | None, tuple[int, ...] | None]:
        low = high = None
        for operator, bound in parse_range(expr):
            if operator in (">=", ">"):
                low = version_key(bound)
            elif operator in ("<", "<="):
                high = version_key(bound)
            elif operator == "=":
                low = high = version_key(bound)
        return low, high

    low_a, high_a = bounds(left)
    low_b, high_b = bounds(right)
    if low_a is not None and high_b is not None and (low_a > high_b or low_a == high_b):
        return False
    if low_b is not None and high_a is not None and (low_b > high_a or low_b == high_a):
        return False
    return True


# ------------------------------------------------------------- repo metadata


def gradle_properties() -> dict[str, str]:
    return load_properties(ROOT / "gradle.properties")


def mod_version() -> str:
    version = gradle_properties().get("mod_version", "").strip()
    if not version:
        raise SystemExit("could not read mod_version from gradle.properties")
    return version


def version_targets() -> dict[str, dict[str, str]]:
    """Every ``versions/<target>/gradle.properties`` keyed by target name."""
    targets: dict[str, dict[str, str]] = {}
    for directory in sorted((ROOT / "versions").iterdir()):
        props = directory / "gradle.properties"
        if directory.is_dir() and props.is_file():
            targets[directory.name] = load_properties(props)
    return targets


def settings_targets() -> list[str]:
    """Target list from ``settings.gradle.kts`` (``versions("a", "b")``)."""
    text = read_text(ROOT / "settings.gradle.kts")
    match = re.search(r"versions\((.*?)\)", text, re.DOTALL)
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


def changelog_versions() -> list[str]:
    """Released versions from CHANGELOG.md headings, newest first."""
    versions: list[str] = []
    for line in read_text(ROOT / "CHANGELOG.md").splitlines():
        match = re.match(r"^##\s+\[([^\]]+)\]", line)
        if match and match.group(1).lower() != "unreleased":
            if re.fullmatch(r"\d+\.\d+\.\d+", match.group(1)):
                versions.append(match.group(1))
    return versions


def changelog_section(version: str) -> str | None:
    """Body of ``## [version]``, or None when the section is absent."""
    lines = read_text(ROOT / "CHANGELOG.md").splitlines()
    start = None
    for index, line in enumerate(lines):
        match = re.match(r"^##\s+\[([^\]]+)\]", line)
        if not match:
            continue
        if start is not None:
            return "\n".join(lines[start:index]).strip()
        if match.group(1) == version:
            start = index + 1
    return "\n".join(lines[start:]).strip() if start is not None else None


def readme_targets() -> list[dict[str, str]]:
    """Rows of the README "Supported build targets" table."""
    rows: list[dict[str, str]] = []
    in_table = False
    for line in read_text(ROOT / "README.md").splitlines():
        if line.startswith("| Target |"):
            in_table = True
            continue
        if in_table:
            if not line.startswith("|"):
                break
            cells = [cell.strip().strip("`") for cell in line.strip().strip("|").split("|")]
            if len(cells) < 3 or set(cells[0]) <= {"-", " "}:
                continue
            rows.append(
                {
                    "target": cells[0],
                    "minecraft": cells[1],
                    "java": cells[2],
                    "loader": cells[3] if len(cells) > 3 else "",
                    "api": cells[4] if len(cells) > 4 else "",
                }
            )
    return rows


def expected_java_version(target: str, props: dict[str, str]) -> int:
    """Mirror build.gradle: 25 for unobfuscated (26.x) targets, else 21."""
    if "java_version" in props:
        return int(props["java_version"])
    return 25 if props.get("minecraft_version", "").startswith("26") else 21
