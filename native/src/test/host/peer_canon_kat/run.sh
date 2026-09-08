#!/usr/bin/env bash
# Host KAT runner for BRPeerCanon.h -- the hardcoded compact-filter peer canon,
# moved out of the Android-only jni_peer.c so iOS gets the same table.
#
# Header-only plus BRChainParams.h / BRPeer.h (also header-only from this KAT's
# point of view), so nothing beyond libc is linked. inet_pton is used only by
# the KAT itself, to cross-check the header's resolver-free parser.
#
#   RED    -DPEER_CANON_HOSTNAME_UNFIXED restores the pre-oracle-bootstrap shape
#          where the priority peer was the HOSTNAME digiscope.me, resolved
#          through DNS on the bootstrap path. That build MUST fail at test1's
#          literal check for mainnet entry 0 -- the property that keeps a
#          resolver off the sovereignty-critical path.
#   GREEN  the production table must pass every check.
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
    # -Wno-missing-braces: BRInt.h's compound literals trip it under gcc
    # (pre-existing core code, see peer_penalty_persist_kat/run.sh).
    # -Wno-gnu-folding-constant: BRPeer.h -> BRMerkleBlock.h -> crypto/odocrypt.h
    # sizes arrays with `const` ints, which clang -std=c99 folds only as an
    # extension. Pre-existing core code that this header must include for the
    # SERVICES_NODE_* bits (never redefine them). Suppressing exactly that class
    # keeps -Werror meaningful for everything else.
    "$CC" -std=c99 -Wall -Wextra -Werror -Wno-missing-braces -Wno-gnu-folding-constant "$@" \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/peer_canon_kat_main.c" \
    -o "$out"
}

build "$BUILD_DIR/peer_canon_kat_unfixed" -DPEER_CANON_HOSTNAME_UNFIXED
set +e
"$BUILD_DIR/peer_canon_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

if [ "$RED_STATUS" -eq 0 ]; then
    echo "GATE FAILED: a hostname in the canon passed. The literal-only property is"
    echo "             not load-bearing -- the KAT would pass either way."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test1: mainnet entry 0 (digiscope.me) is an IPv4 literal" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the pre-fix build failed, but not at the hostname checkpoint --"
    echo "             so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED gate OK: a hostname in the canon would have put DNS on the bootstrap path."

build "$BUILD_DIR/peer_canon_kat"
"$BUILD_DIR/peer_canon_kat"
