#!/bin/sh
# Local preflight: run the checks that CI runs before it spends a build minute.
#
# Usage:
#   tools/ci/preflight.sh                     # metadata, CI lint, sources, fixtures, self-tests
#   tools/ci/preflight.sh --tag v2.1.1        # ... plus the release gate for that tag
#   tools/ci/preflight.sh --build 26.3        # ... plus a real Gradle build and jar audit
#   tools/ci/preflight.sh --build all         # build every supported target
#
# Everything except --build needs nothing but python3 and sh. Use it before
# pushing so a typo costs a second instead of a pipeline.

set -u

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_ROOT" || exit 1

PYTHON=${PYTHON:-python3}
TAG=""
BUILD_TARGET=""

if [ -t 1 ]; then
    GREEN=$(printf '\033[32m'); RED=$(printf '\033[31m'); BOLD=$(printf '\033[1m'); RESET=$(printf '\033[0m')
else
    GREEN=""; RED=""; BOLD=""; RESET=""
fi

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG=${2:-}; shift 2 ;;
        --tag=*) TAG=${1#--tag=}; shift ;;
        --build)
            # optional target argument
            case "${2:-}" in
                ""|--*) BUILD_TARGET="26.3"; shift ;;
                *) BUILD_TARGET=$2; shift 2 ;;
            esac ;;
        --build=*) BUILD_TARGET=${1#--build=}; shift ;;
        -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

status=0
step() {
    printf '\n%s==> %s%s\n' "$BOLD" "$*" "$RESET"
}
run() {
    if "$@"; then
        printf '%s    passed%s\n' "$GREEN" "$RESET"
    else
        printf '%s    FAILED: %s%s\n' "$RED" "$*" "$RESET"
        status=1
    fi
}

if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "error: $PYTHON not found (Python 3 is required)" >&2
    exit 2
fi

step "CI definition"
run "$PYTHON" tools/ci/lint_gitlab_ci.py --quiet

step "Release metadata"
if [ -n "$TAG" ]; then
    run "$PYTHON" tools/ci/check_metadata.py --tag "$TAG"
else
    run "$PYTHON" tools/ci/check_metadata.py
fi

step "Source tree"
run "$PYTHON" tools/ci/check_sources.py

step "Python fixtures"
run "$PYTHON" -m unittest discover -s tests -p 'test_*.py'

step "Tooling self-tests"
run "$PYTHON" tools/ci/selftest.py

if [ -n "$BUILD_TARGET" ]; then
    if [ "$BUILD_TARGET" = "all" ]; then
        TARGETS=$("$PYTHON" -c 'import sys; sys.path.insert(0, "tools/ci"); import ntci; print(" ".join(sorted(ntci.version_targets())))')
        run ./gradlew buildAll
    else
        TARGETS=$BUILD_TARGET
        run ./gradlew ":$BUILD_TARGET:build"
    fi

    if [ "$status" -eq 0 ]; then
        step "Jar audit"
        RELEASE_DIR=$(mktemp -d)
        trap 'rm -rf "$RELEASE_DIR"' EXIT
        for target in $TARGETS; do
            find "versions/$target/build/libs" -maxdepth 1 -type f -name '*.jar' \
                ! -name '*-sources.jar' ! -name '*-dev.jar' -exec cp {} "$RELEASE_DIR/" \;
        done
        run "$PYTHON" tools/ci/inspect_jar.py --strict "$RELEASE_DIR"/*.jar
        run "$PYTHON" tools/ci/gen_sums.py --release-dir "$RELEASE_DIR"
        # Same checks the release gate runs: checksums, manifest and jar versions all agreeing.
        run "$PYTHON" tools/ci/verify_release.py --dir "$RELEASE_DIR"
        echo "    artifacts and checksums in $RELEASE_DIR (removed on exit)"
    fi
fi

printf '\n'
if [ "$status" -eq 0 ]; then
    printf '%spreflight OK%s\n' "$GREEN" "$RESET"
else
    printf '%spreflight FAILED%s\n' "$RED" "$RESET"
fi
exit "$status"
