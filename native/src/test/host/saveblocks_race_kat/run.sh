#!/usr/bin/env bash
# Self-validating red-before-green KAT for the saveBlocks lock-release-then-use
# race (docs/superpowers/plans/2026-07-26-saveblocks-lockrelease-race.md, Task 1).
#
# Drives the REAL BRMerkleBlockSerialize in a bounded 2-thread ASan race that
# mirrors _peerRelayedBlock's save-dispatch pattern (see the _main.c header). The
# script builds BOTH code shapes and REQUIRES:
#   * SAVEBLOCKS_FIXED=0 (unlock-before-serialize) -> ASan heap-use-after-free (RED)
#   * SAVEBLOCKS_FIXED=1 (serialize-under-lock)     -> clean exit 0 (GREEN)
# The KAT FAILS if the unfixed pattern does NOT fault — a gate that can't detect
# the bug is worthless (the red-before-green heuristic, enforced structurally).
#
# ASan (not TSan): the defect is a use-after-free/overrun on freed merkle-block
# fields, which ASan catches directly and deterministically; the run is BOUNDED
# (fixed ITERS) so it can never hang. Compiler: clang, -include stdint.h (same
# crypto/odocrypt.h reason as the sibling KATs). run-host-kats.sh gates on exit 0.
set -uo pipefail   # deliberately NOT -e: we must capture the unfixed run's non-zero (RED) exit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() { # $1=SAVEBLOCKS_FIXED  $2=output
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g \
        -DSAVEBLOCKS_FIXED="$1" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/saveblocks_race_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$2"
}

# symbolize=0: the slow per-invocation llvm-symbolizer otherwise stalls the UAF
# report for tens of seconds; we only need to DETECT the fault ("heap-use-after-free"
# prints without symbolization), not name the frames. abort_on_error kills fast.
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

echo "=== red-before-green [1/2]: UNFIXED pattern must FAULT ==="
if ! build 0 "$BUILD_DIR/unfixed"; then echo "FAIL: unfixed build error"; exit 1; fi
"$BUILD_DIR/unfixed" > "$BUILD_DIR/unfixed.out" 2>&1; RED_EXIT=$?
if [ "$RED_EXIT" -eq 0 ]; then
    echo "FAIL: unfixed pattern did NOT fault (exit 0) — the gate cannot detect the bug = worthless."
    tail -3 "$BUILD_DIR/unfixed.out"; exit 1
fi
if grep -qiE "heap-use-after-free|heap-buffer-overflow|AddressSanitizer" "$BUILD_DIR/unfixed.out"; then
    echo "RED confirmed: unfixed pattern faults (exit $RED_EXIT):"
    grep -iE "ERROR: AddressSanitizer|heap-use-after-free|#0 |BRMerkleBlockSerialize" "$BUILD_DIR/unfixed.out" | head -4
else
    echo "FAIL: unfixed exited $RED_EXIT but not via an ASan memory error"; tail -5 "$BUILD_DIR/unfixed.out"; exit 1
fi

echo "=== red-before-green [2/2]: FIXED pattern must be CLEAN ==="
if ! build 1 "$BUILD_DIR/fixed"; then echo "FAIL: fixed build error"; exit 1; fi
"$BUILD_DIR/fixed" > "$BUILD_DIR/fixed.out" 2>&1; GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "FAIL: fixed pattern faulted (exit $GREEN_EXIT) — serialize-under-lock should be race-free."
    tail -5 "$BUILD_DIR/fixed.out"; exit 1
fi
grep -q "no fault" "$BUILD_DIR/fixed.out" || { echo "FAIL: fixed run didn't complete cleanly"; tail -5 "$BUILD_DIR/fixed.out"; exit 1; }
echo "GREEN confirmed: $(cat "$BUILD_DIR/fixed.out")"

echo "saveblocks_race_kat: PASS (RED on unfixed pattern, GREEN on fixed pattern)"
exit 0
