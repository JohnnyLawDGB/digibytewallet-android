#!/usr/bin/env bash
# Host KAT runner for BRPeerPenaltyPersist.h -- the peer-penalty persistence
# decision, and its cross-check against BRPeerPenalty.h's real serializer.
#
# Header-only plus BRPeerPenalty.h (also header-only), so nothing beyond libc is
# linked. BRInt.h comes in via BRPeerPenalty.h for UInt128.
#
#   RED    -DPEER_PENALTY_NULL_IS_EMPTY_UNFIXED restores the pre-fix shape where
#          a NULL blob was treated as "empty" and cleared the stored set. That
#          build MUST fail at test1's NULL check -- the case that silently
#          discards a wallet's banked penalties.
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
    # -Wno-missing-braces: BRInt.h's UInt128Set/UInt160Set/UInt256Set compound
    # literals trip -Wmissing-braces under gcc. That is PRE-EXISTING core code,
    # not this KAT's, and the other host KATs never see it because they include
    # only new header-only policy files. Suppressing exactly that one class keeps
    # -Werror meaningful for everything else rather than dropping it wholesale.
    "$CC" -std=c99 -Wall -Wextra -Werror -Wno-missing-braces "$@" \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/peer_penalty_persist_kat_main.c" \
    -o "$out"
}

build "$BUILD_DIR/peer_penalty_persist_kat_unfixed" -DPEER_PENALTY_NULL_IS_EMPTY_UNFIXED
set +e
"$BUILD_DIR/peer_penalty_persist_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the null-is-empty shape passed. The Keep/Clear distinction is"
    echo "             not load-bearing -- the KAT would pass either way."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test1: a NULL blob is Keep, not Clear" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the NULL checkpoint --"
    echo "             so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: treating a failed probe as 'empty' would have cleared banked penalties."

build "$BUILD_DIR/peer_penalty_persist_kat"
"$BUILD_DIR/peer_penalty_persist_kat"
