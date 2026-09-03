#!/usr/bin/env bash
# Host KAT runner for BRCFRecoveryPolicy.h -- the compact-filter recovery
# decision table (which artifacts a recovery may destroy).
#
# Header-only: the KAT includes BRCFRecoveryPolicy.h from the core submodule and
# links nothing but libc. No BRPeerManager, no crypto chain, no -include
# stdint.h workaround -- none of the reasons the heavier KATs need those apply.
#
# Two builds, so the table is a real red-before-green gate rather than a test
# that would pass either way:
#
#   RED    -DCF_RECOVERY_DROPS_BOTH_UNFIXED restores the pre-fix watchdog shape
#          (delete the filter-header chain AND the scan ledger on every
#          recovery). That build MUST exit non-zero AND must fail specifically
#          at the wedged-keeps-ledger checkpoint -- that is the ~6-hour rescan
#          regression. A merely non-zero exit is not accepted on its own, since
#          that is also what a build error looks like.
#   GREEN  the production shape must run every check and exit 0.
#
# Exit code 0 = all checks passed, 1 = at least one check failed or a gate did
# not behave as required.
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
    "$CC" -w -std=c99 "$@" \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/cf_recovery_policy_kat_main.c" \
    -o "$out"
}

# -- RED: the pre-fix drop-everything shape must fail, at the right checkpoint --
build "$BUILD_DIR/cf_recovery_policy_kat_unfixed" -DCF_RECOVERY_DROPS_BOTH_UNFIXED
set +e
"$BUILD_DIR/cf_recovery_policy_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the CF_RECOVERY_DROPS_BOTH_UNFIXED build passed. The table is"
    echo "             not load-bearing -- the KAT would pass with or without it."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test1: wedged KEEPS the scan ledger" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the wedged-keeps-ledger"
    echo "             checkpoint -- so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: pre-fix shape dropped the scan ledger on a routine stall."

# -- GREEN: the production shape must pass every check ------------------------
build "$BUILD_DIR/cf_recovery_policy_kat"
"$BUILD_DIR/cf_recovery_policy_kat"
