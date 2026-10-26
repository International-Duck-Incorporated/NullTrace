#!/usr/bin/env python3
"""Lint ``.gitlab-ci.yml`` and simulate which jobs a pipeline would run.

GitLab only tells you a pipeline is wrong when you push it. This does it locally:

  * structural checks: unknown stages, ``needs`` on jobs that do not exist (a
    ``config error`` for the whole pipeline), ``extends`` targets, matrix
    variables that are used but never defined, ``only``/``except`` mixed with
    ``rules``, script files that are referenced but missing
  * a small evaluator for ``rules: if`` expressions (``==``, ``!=``, ``=~``,
    ``!~``, ``&&``, ``||``, ``!``, parentheses, ``/regex/``) so you can see
    exactly which jobs run for a tag, ``main``, a feature branch or an MR
  * a cost summary: how many Gradle jobs each scenario pays for

Requires PyYAML (``pip install pyyaml`` / ``apk add py3-yaml``).

Usage::

    python3 tools/ci/lint_gitlab_ci.py
    python3 tools/ci/lint_gitlab_ci.py --scenario all
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field

import ntci

try:
    import yaml
except ImportError:  # pragma: no cover - guidance rather than a traceback
    print("error: PyYAML is required (pip install pyyaml, or apk add py3-yaml)", file=sys.stderr)
    raise SystemExit(2)

CI_FILE = ntci.ROOT / ".gitlab-ci.yml"

RESERVED = {
    "stages",
    "workflow",
    "variables",
    "cache",
    "default",
    "include",
    "image",
    "services",
    "before_script",
    "after_script",
}

SCENARIOS: dict[str, dict[str, str | None]] = {
    "tag": {
        "CI_PIPELINE_SOURCE": "push",
        "CI_COMMIT_TAG": "v2.1.1",
        "CI_COMMIT_BRANCH": None,
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "v2.1.1",
        "CI_COMMIT_REF_SLUG": "v2-1-1",
        "CI_OPEN_MERGE_REQUESTS": None,
    },
    "main": {
        "CI_PIPELINE_SOURCE": "push",
        "CI_COMMIT_TAG": None,
        "CI_COMMIT_BRANCH": "main",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "main",
        "CI_COMMIT_REF_SLUG": "main",
        "CI_OPEN_MERGE_REQUESTS": None,
    },
    "branch": {
        "CI_PIPELINE_SOURCE": "push",
        "CI_COMMIT_TAG": None,
        "CI_COMMIT_BRANCH": "fix/checksum-prefix",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "fix/checksum-prefix",
        "CI_COMMIT_REF_SLUG": "fix-checksum-prefix",
        "CI_OPEN_MERGE_REQUESTS": None,
    },
    "branch+mr": {
        "CI_PIPELINE_SOURCE": "push",
        "CI_COMMIT_TAG": None,
        "CI_COMMIT_BRANCH": "fix/checksum-prefix",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "fix/checksum-prefix",
        "CI_COMMIT_REF_SLUG": "fix-checksum-prefix",
        "CI_OPEN_MERGE_REQUESTS": "!12",
    },
    "merge_request": {
        "CI_PIPELINE_SOURCE": "merge_request_event",
        "CI_COMMIT_TAG": None,
        "CI_COMMIT_BRANCH": "fix/checksum-prefix",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "fix/checksum-prefix",
        "CI_COMMIT_REF_SLUG": "fix-checksum-prefix",
        "CI_OPEN_MERGE_REQUESTS": "!12",
        "CI_MERGE_REQUEST_IID": "12",
    },
    "schedule": {
        "CI_PIPELINE_SOURCE": "schedule",
        "CI_COMMIT_TAG": None,
        "CI_COMMIT_BRANCH": "main",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_NAME": "main",
        "CI_COMMIT_REF_SLUG": "main",
        "CI_OPEN_MERGE_REQUESTS": None,
    },
}


# ------------------------------------------------------------------ expressions


class ExpressionError(ValueError):
    pass


TOKEN_PATTERN = re.compile(
    r"""
    \s*(?:
        (?P<var>\$[A-Za-z_][A-Za-z0-9_]*)
      | (?P<string>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')
      | (?P<regex>/(?:[^/\\]|\\.)*/)
      | (?P<op>==|!=|=~|!~|&&|\|\||!|\(|\))
      | (?P<null>null)
      | (?P<word>[^\s()]+)
    )""",
    re.VERBOSE,
)


def tokenize(expression: str) -> list[tuple[str, str]]:
    tokens: list[tuple[str, str]] = []
    position = 0
    while position < len(expression):
        match = TOKEN_PATTERN.match(expression, position)
        if not match:
            raise ExpressionError(f"cannot tokenize {expression[position:]!r}")
        position = match.end()
        kind = match.lastgroup or ""
        tokens.append((kind, match.group(kind)))
    return tokens


@dataclass
class Evaluator:
    context: dict[str, str | None]

    def value(self, token: tuple[str, str]):
        kind, text = token
        if kind == "var":
            return self.context.get(text[1:])
        if kind == "string":
            return text[1:-1]
        if kind == "regex":
            return ("regex", text[1:-1])
        if kind == "null":
            return None
        if kind == "word":
            if text in ("true", "false"):
                return text
            raise ExpressionError(f"unsupported bare word {text!r} (quote it)")
        raise ExpressionError(f"unsupported token {text!r}")

    def truthy(self, value) -> bool:
        if isinstance(value, tuple):
            return True
        if value is None:
            return False
        return str(value).strip().lower() not in ("", "false")

    def compare(self, operator: str, left, right) -> bool:
        if operator in ("=~", "!~"):
            pattern = right[1] if isinstance(right, tuple) else str(right)
            matched = re.search(pattern, "" if left is None else str(left)) is not None
            return matched if operator == "=~" else not matched
        left_s = None if left is None else str(left)
        right_s = None if right is None else (right[1] if isinstance(right, tuple) else str(right))
        equal = left_s == right_s or (left_s in (None, "") and right_s in (None, ""))
        return equal if operator == "==" else not equal

    def evaluate(self, expression: str) -> bool:
        tokens = tokenize(expression)
        position = 0

        def peek():
            return tokens[position] if position < len(tokens) else (None, None)

        def parse_or():
            nonlocal position
            value = parse_and()
            while peek() == ("op", "||"):
                position += 1
                right = parse_and()
                value = value or right
            return value

        def parse_and():
            nonlocal position
            value = parse_unary()
            while peek() == ("op", "&&"):
                position += 1
                right = parse_unary()
                value = value and right
            return value

        def parse_unary():
            nonlocal position
            if peek() == ("op", "!"):
                position += 1
                return not parse_unary()
            return parse_comparison()

        def parse_comparison():
            nonlocal position
            left = parse_primary()
            token = peek()
            if token[0] == "op" and token[1] in ("==", "!=", "=~", "!~"):
                position += 1
                right = parse_primary()
                return self.compare(token[1], left, right)
            return self.truthy(left)

        def parse_primary():
            nonlocal position
            token = peek()
            if token == ("op", "("):
                position += 1
                value = parse_or()
                if peek() != ("op", ")"):
                    raise ExpressionError("missing closing parenthesis")
                position += 1
                return value
            if token[0] is None:
                raise ExpressionError("unexpected end of expression")
            position += 1
            return self.value(token)

        result = parse_or()
        if position != len(tokens):
            raise ExpressionError(f"trailing tokens: {tokens[position:]}")
        return bool(result)


# ------------------------------------------------------------------------- lint


@dataclass
class Job:
    name: str
    data: dict
    stage: str
    rules: list = field(default_factory=list)

    @property
    def is_gradle(self) -> bool:
        script = self.data.get("script") or []
        text = "\n".join(script if isinstance(script, list) else [str(script)])
        return "gradlew" in text

    def decision(self, context: dict[str, str | None]) -> str:
        rules = self.rules
        if not rules:
            return "run"
        evaluator = Evaluator(context)
        for rule in rules:
            if not isinstance(rule, dict):
                raise ExpressionError(f"{self.name}: rule is not a mapping")
            condition = rule.get("if")
            if condition is None:
                return rule.get("when", "run")
            if evaluator.evaluate(str(condition)):
                return rule.get("when", "on_success")
        return "skip"


def deep_merge(base: dict, override: dict) -> dict:
    """GitLab's `extends` merge: maps merge recursively, everything else replaces."""
    merged = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def resolve(name: str, config: dict, _seen: frozenset[str] = frozenset()) -> dict:
    """Return a job's data with `extends` chains already applied."""
    if name in _seen:
        raise ExpressionError(f"extends cycle around {name!r}")
    data = dict(config.get(name) or {})
    parents = data.pop("extends", None)
    if not parents:
        return data
    parents = [parents] if isinstance(parents, str) else list(parents)
    merged: dict = {}
    for parent in parents:
        parent_data = config.get(parent)
        if not isinstance(parent_data, dict):
            continue
        merged = deep_merge(merged, resolve(parent, config, _seen | {name}))
    return deep_merge(merged, data)


def lint(config: dict) -> ntci.Report:
    report = ntci.Report("GitLab CI lint")
    stages = config.get("stages") or []
    if not stages:
        report.failed("CI001", "no stages declared")
    else:
        report.passed("CI001", f"stages: {', '.join(map(str, stages))}")

    jobs: dict[str, Job] = {}
    for name, raw in config.items():
        if name in RESERVED or name.startswith(".") or not isinstance(raw, dict):
            continue
        data = resolve(name, config)
        stage = data.get("stage")
        if stage not in stages:
            report.failed(f"CI002/{name}", f"stage {stage!r} is not declared in stages")
        jobs[name] = Job(name=name, data=data, stage=stage or (stages[0] if stages else ""), rules=data.get("rules") or [])

    templates = {
        name: data
        for name, data in config.items()
        if isinstance(data, dict) and (name.startswith(".") or name in RESERVED)
    }

    for job in jobs.values():
        if "script" not in job.data and "trigger" not in job.data:
            report.failed(f"CI004/{job.name}", "job has no script")
        if "only" in job.data or "except" in job.data:
            if "rules" in job.data:
                report.failed(f"CI005/{job.name}", "uses both rules and only/except")
            else:
                report.warned(f"CI005/{job.name}", "uses the deprecated only/except syntax")
        needed = job.data.get("needs") or []
        for need in needed:
            target = need["job"] if isinstance(need, dict) else need
            if target not in jobs:
                report.failed(
                    f"CI006/{job.name}",
                    f"needs {target!r}, which is not a job",
                    "GitLab rejects the whole pipeline ('config contains unknown keys') when a need is unknown.",
                )
                continue
            if stages.index(jobs[target].stage) > stages.index(job.stage):
                report.failed(f"CI007/{job.name}", f"needs {target!r} from a later stage ({jobs[target].stage})")

    # GitLab expands variables in these fields, so an undeclared one is a typo
    # (shell locals inside `script` are not expandable and are not checked).
    for job in jobs.values():
        matrix = (job.data.get("parallel") or {}).get("matrix") or []
        matrix_vars: set[str] = set()
        for entry in matrix:
            if isinstance(entry, dict):
                matrix_vars.update(entry)
        declared = matrix_vars | set(config.get("variables") or {}) | set(job.data.get("variables") or {})
        expandable = {
            "image": job.data.get("image"),
            "resource_group": job.data.get("resource_group"),
            "cache": job.data.get("cache"),
            "artifacts": job.data.get("artifacts"),
            "environment": job.data.get("environment"),
            "needs": job.data.get("needs"),
        }
        text = "\n".join(str(value) for value in expandable.values() if value)
        for variable in set(re.findall(r"\$([A-Z_][A-Z0-9_]*)", text)):
            if variable.startswith(("CI_", "GIT_")) or variable in declared:
                continue
            report.warned(
                f"CI008/{job.name}",
                f"uses ${variable} in a GitLab-expanded field, but nothing defines it",
            )

    # script files that must exist in the repository
    for job in jobs.values():
        script = "\n".join(str(part) for part in (job.data.get("script") or []))
        for path in set(re.findall(r"tools/ci/[\w./-]+", script)):
            if not (ntci.ROOT / path.split()[0]).exists():
                report.failed(f"CI009/{job.name}", f"script references missing file {path}")

    # rules must be evaluable, and a job whose rules can only ever be 'never' is dead
    dead = []
    for job in jobs.values():
        for rule in job.rules:
            condition = rule.get("if") if isinstance(rule, dict) else None
            if not condition:
                continue
            try:
                Evaluator(SCENARIOS["main"]).evaluate(str(condition))
            except ExpressionError as error:
                report.failed(f"CI010/{job.name}", f"unparseable rule {condition!r}: {error}")
        if job.rules and all(isinstance(rule, dict) and rule.get("when") == "never" for rule in job.rules):
            dead.append(job.name)
    if dead:
        report.warned("CI011", f"jobs can never run: {', '.join(dead)}")
    else:
        report.passed("CI011", "no job is unconditionally disabled")

    # shell syntax: `sh -n` parses without executing, so a stray `fi` is caught here
    broken = []
    parsed = 0
    for job in jobs.values():
        for item in job.data.get("script") or []:
            if not isinstance(item, str):
                continue
            parsed += 1
            result = subprocess.run(["sh", "-n"], input=item, capture_output=True, text=True)
            if result.returncode != 0:
                detail = result.stderr.strip().splitlines()[-1] if result.stderr.strip() else "parse error"
                broken.append(f"{job.name}: {detail}")
    if broken:
        report.failed("CI014", "shell syntax errors in script blocks", "; ".join(broken[:5]))
    elif parsed:
        report.passed("CI014", f"{parsed} script block(s) parse as POSIX shell")

    # the workflow gate decides whether a pipeline exists at all
    workflow_rules = (config.get("workflow") or {}).get("rules") or []
    if not workflow_rules:
        report.warned("CI012", "no workflow rules: every push runs every job")
    else:
        report.passed("CI012", f"workflow gate has {len(workflow_rules)} rule(s)")

    if jobs:
        report.passed("CI013", f"{len(jobs)} jobs across {len({job.stage for job in jobs.values()})} stages")
    return report


def matrix_size(job_data: dict) -> int:
    """How many parallel jobs one YAML entry expands to."""
    matrix = (job_data.get("parallel") or {}).get("matrix") or []
    total = 0
    for entry in matrix:
        size = 1
        if isinstance(entry, dict):
            for value in entry.values():
                size *= len(value) if isinstance(value, list) else 1
        total += size
    return max(total, 1)


def simulate(config: dict, scenario: str) -> int:
    workflow_rules = (config.get("workflow") or {}).get("rules") or []
    context = SCENARIOS[scenario]
    gate = "run"
    if workflow_rules:
        evaluator = Evaluator(context)
        gate = "skip"
        for rule in workflow_rules:
            if not isinstance(rule, dict):
                continue
            condition = rule.get("if")
            if condition is None or evaluator.evaluate(str(condition)):
                gate = rule.get("when", "on_success")
                break

    print(f"\n=== scenario: {scenario} ===")
    if gate == "never":
        print("  pipeline is not created (workflow rules match 'never')")
        return 0

    jobs = []
    for name, raw in config.items():
        if name in RESERVED or name.startswith(".") or not isinstance(raw, dict):
            continue
        data = resolve(name, config)
        if "script" not in data:
            continue
        job = Job(name=name, data=data, stage=data.get("stage", ""), rules=data.get("rules") or [])
        try:
            decision = job.decision(context)
        except ExpressionError as error:
            print(f"  [ERROR] {name}: {error}")
            continue
        if decision == "skip" or decision == "never":
            continue
        jobs.append((job.stage, name, decision, job.is_gradle))

    stages = config.get("stages") or []
    jobs.sort(key=lambda item: (stages.index(item[0]) if item[0] in stages else 99, item[1]))
    gradle_jobs = 0
    total_instances = 0
    for stage, name, decision, is_gradle in jobs:
        label = "" if decision in ("run", "on_success") else f"  [{decision}]"
        instances = matrix_size(config.get(name) or {})
        total_instances += instances
        suffix = f"   x{instances}" if instances > 1 else ""
        print(f"  {stage:<10} {name}{label}{'   (gradle)' if is_gradle else ''}{suffix}")
        if is_gradle and decision in ("run", "on_success"):
            gradle_jobs += instances
    print(f"  -> {len(jobs)} job(s) ({total_instances} with matrix expansion), {gradle_jobs} of them run Gradle")
    return gradle_jobs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", default=str(CI_FILE))
    parser.add_argument("--scenario", default="all", help="tag|main|branch|branch+mr|merge_request|schedule|all")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    try:
        config = yaml.safe_load(ntci.read_text(args.file))
    except yaml.YAMLError as error:
        print(f"[FAIL] CI000: {args.file} is not valid YAML: {error}")
        return 1
    if not isinstance(config, dict):
        print(f"[FAIL] CI000: {args.file} is not a mapping")
        return 1

    report = lint(config)
    report.render(verbose=not args.quiet)

    scenarios = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    for scenario in scenarios:
        if scenario not in SCENARIOS:
            print(f"unknown scenario {scenario!r}", file=sys.stderr)
            return 2
        simulate(config, scenario)
    return report.exit_code


if __name__ == "__main__":
    sys.exit(main())
