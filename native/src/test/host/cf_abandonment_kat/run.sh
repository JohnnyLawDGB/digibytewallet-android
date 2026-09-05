#!/usr/bin/env bash
# Host KAT runner for BRCFAbandonment.h -- the abandoned compact-filter band's
# three decisions (fold / retired / coverage proven), cross-checked against a
# ledger driven by the real BRCFScanLedger.c.
#
# The KAT main compiles under the strict policy-header flags. BRCFScanLedger.c
# is a pre-existing core module and is compiled the way its own KAT compiles
# it (-w, ASan), then linked in.
#
#   RED    -DCF_ABANDONMENT_START_UNQUALIFIED_UNFIXED drops the ledger-start
#          qualifier from the coverage claim: "scannedThrough passed the band"
#          without "and contiguity started at or below it". That build MUST
#          fail at test3's ledger-started-above-the-band check -- the false
#          "all clear" over heights the ledger never looked at.
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

# The real ledger module, once, the way cf_scan_ledger_kat builds it.
"$CC" -w -std=c99 -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" -c "$CORE_DIR/BRCFScanLedger.c" -o "$BUILD_DIR/BRCFScanLedger.o"

build() {
    local out="$1"; shift
    # -Wno-missing-braces: BRInt.h's compound literals trip it under gcc
    # (pre-existing core code, see peer_penalty_persist_kat/run.sh).
    "$CC" -std=c99 -Wall -Wextra -Werror -Wno-missing-braces "$@" \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/cf_abandonment_kat_main.c" "$BUILD_DIR/BRCFScanLedger.o" \
    -o "$out"
}

build "$BUILD_DIR/cf_abandonment_kat_unfixed" -DCF_ABANDONMENT_START_UNQUALIFIED_UNFIXED
set +e
"$BUILD_DIR/cf_abandonment_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the start-unqualified coverage claim passed. The ledger-start"
    echo "             qualifier is not load-bearing -- the KAT would pass either way."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test3: a ledger started above the band proves nothing about it" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the ledger-start checkpoint --"
    echo "             so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: an unqualified contiguity claim would have cleared a band the ledger never scanned."

build "$BUILD_DIR/cf_abandonment_kat"
"$BUILD_DIR/cf_abandonment_kat"
