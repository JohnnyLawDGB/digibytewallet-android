#!/usr/bin/env bash
# Host KAT runner for BRRecreateSequence.h -- the mid-session peer-manager
# recreate ordering.
#
# Header-only: includes the header from the core submodule, links only libc.
#
#   RED    -DRECREATE_RELOAD_AFTER_REBUILD_UNFIXED restores the pre-fix ordering,
#          with the near-tip reload moved AFTER the rebuild that consumes it --
#          the shape that floored a Note 8's scan from 24,052,509 to 22,650,000
#          and cost ~6 hours. That build MUST fail, and specifically at test1 and
#          test2, not merely somewhere.
#   GREEN  the production ordering must pass every check.
#
# Exit 0 = all checks passed; 1 = a check failed or a gate misbehaved.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
command -v "$CC" >/dev/null 2>&1 || CC=cc

build() {
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Wpedantic -Werror "$@" \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/recreate_sequence_kat_main.c" \
    -o "$out"
}

build "$BUILD_DIR/recreate_sequence_kat_unfixed" -DRECREATE_RELOAD_AFTER_REBUILD_UNFIXED
set +e
"$BUILD_DIR/recreate_sequence_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the pre-fix ordering passed. The order assertions are not"
    echo "             load-bearing -- the KAT would pass either way."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test1: the five steps are in the fixed order" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the ordering checkpoint."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test2: reload happens BEFORE the rebuild that consumes it" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build did not fail the reload-before-rebuild"
    echo "             assertion, which IS the defect. test2 is not testing it."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix ordering reloaded the near-tip window after the rebuild consumed it."

build "$BUILD_DIR/recreate_sequence_kat"
"$BUILD_DIR/recreate_sequence_kat"
