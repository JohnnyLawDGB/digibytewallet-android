#!/usr/bin/env bash
# Host KAT runner for BRPeerPenaltyRestore.h -- restoring a persisted peer-penalty set
# with lapsed entries dropped and the peer canon exempt.
#
# Header-only: includes BRPeerPenaltyRestore.h from the core and links nothing but libc
# (the manager wrapper is never called, so no BRPeerManager.c is needed).
#
#   RED    -DPEER_PENALTY_RESTORE_CANON_UNFIXED restores the blob as-is, as a platform
#          without this header would. Every RED case MUST fail; every GUARD case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(mainnet_canon_exempt mainnet_drop_count_reported testnet_canon_exempt)
GUARD_CASES=(ordinary_peer_kept lapsed_entry_dropped empty_blob_restores_nothing
             null_blob_restores_nothing short_blob_restores_nothing bounded_to_restore_max
             small_output_never_truncates)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_penalty_restore_kat_main.c" -o "$out"
}

if ! grep -q "PEER_PENALTY_RESTORE_CANON_UNFIXED" "$CORE_DIR/BRPeerPenaltyRestore.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRPeerPenaltyRestore.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DPEER_PENALTY_RESTORE_CANON_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DPEER_PENALTY_RESTORE_CANON_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c fail$"; then echo "  [red $c] failed, as it must"
    else echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
done
for c in "${GUARD_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c pass$"; then echo "  [red $c] GUARD held"
    else echo "  [red $c] GATE FAILURE: GUARD case did not hold"; echo "$out" | grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; echo "$out" | grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "peer_penalty_restore_kat: GATE HELD (RED restored the canon; GREEN exempted it)"
exit $FAIL
