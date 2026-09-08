#!/usr/bin/env bash
# Host KAT runner for BRAssetQuantity.h -- how many DigiAsset token units land
# on one transaction output.
#
# Header-only under test: libc only, no core .c files. Two RED gates, both of
# which must fail at their own named checkpoint, so a build that fails for some
# unrelated reason cannot be mistaken for the gate firing.
#
#   RED 1  -DASSET_QUANTITY_RANGE_DROPPED_UNFIXED drops range instructions
#          entirely -- the shipped pre-fix shape, under which every range
#          receive counted 0. MUST fail at test3.
#   RED 2  -DASSET_QUANTITY_OVERFLOW_UNGUARDED removes the overflow checks from
#          the assigned-units arithmetic, which is what the Kotlin mirror still
#          does. The sum wraps negative and the remainder becomes a credit for
#          units that do not exist. MUST fail at test11.
#   GREEN  the production shape must pass every check.
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
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_quantity_kat_main.c" \
        -o "$out"
}

# $1 = -D flag, $2 = expected failing check text, $3 = what the gate proves
gate() {
    local flag="$1" expect="$2" what="$3"
    build "$BUILD_DIR/red" "-D$flag"
    set +e
    "$BUILD_DIR/red" > "$BUILD_DIR/red.log" 2>&1
    local status=$?
    set -e
    if [ "$status" -eq 0 ]; then
        echo "GATE FAILED ($flag): the pre-fix shape passed, so the fix is not"
        echo "             load-bearing -- the KAT would pass either way."
        sed 's/^/             | /' "$BUILD_DIR/red.log"
        exit 1
    fi
    if ! grep -q "FAIL: $expect" "$BUILD_DIR/red.log"; then
        echo "GATE FAILED ($flag): the pre-fix build failed, but not at the expected"
        echo "             checkpoint -- so the failure is not the defect under test."
        sed 's/^/             | /' "$BUILD_DIR/red.log"
        exit 1
    fi
    echo "RED gate OK: $what"
}

gate ASSET_QUANTITY_RANGE_DROPPED_UNFIXED \
     "test3: a range instruction credits every output in 0..outputIndex" \
     "dropping range would have counted a real range receive as 0."
gate ASSET_QUANTITY_OVERFLOW_UNGUARDED \
     "test11: a crafted instruction pair cannot fabricate a remainder" \
     "unguarded arithmetic would have credited units that do not exist."

build "$BUILD_DIR/asset_quantity_kat"
"$BUILD_DIR/asset_quantity_kat"
