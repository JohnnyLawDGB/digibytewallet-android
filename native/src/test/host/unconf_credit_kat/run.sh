#!/usr/bin/env bash
# unconf_credit_kat — an unconfirmed receive no peer relays does not stay credited forever (4.1.2 row 32). See the _main.c.
#   RED    -DUNCONF_CREDIT_UNFIXED: any receive is the wallet's stake. Case A must FAIL there.
#   RED2   -DUNCONF_DEPENDANT_UNFIXED: a receive the wallet's own send spends is no stake. Case B must FAIL there.
#   GREEN  every check passes; no AddressSanitizer report.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
[ "$(grep -c UNCONF_CREDIT_UNFIXED "$CORE_DIR/BRPeerManager.c")" -ge 1 ] || { echo "GATE FAILURE: the seam is missing"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" -I "$CORE_DIR/secp256k1/include" -I "$SCRIPT_DIR/../tipsweep_token_stake_kat" \
        "$SCRIPT_DIR/unconf_credit_kat_main.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c" "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0"
FAIL=0
build "$BUILD_DIR/red" -DUNCONF_CREDIT_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
OUT="$(timeout -k 5 300 "$BUILD_DIR/red" 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -q "ERROR: AddressSanitizer" && <<<"$OUT" grep -q "^FAIL: A: the unrelayed receive is still credited" \
   && ! <<<"$OUT" grep -q "FAIL: setup\|FAIL: control\|FAIL: session\|FAIL: relaunch"; then echo "  [red] case A failed, as required: $(<<<"$OUT" grep -m1 '^FAIL: A:')"
else echo "GATE FAILURE: the red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -5; FAIL=1; fi
[ "$(grep -c UNCONF_DEPENDANT_UNFIXED "$CORE_DIR/BRPeerManager.c")" -ge 1 ] || { echo "GATE FAILURE: the UNCONF_DEPENDANT_UNFIXED seam is missing"; exit 1; }
build "$BUILD_DIR/red2" -DUNCONF_DEPENDANT_UNFIXED || { echo "GATE FAILURE: red2 did not build"; exit 1; }
OUT="$(timeout -k 5 300 "$BUILD_DIR/red2" 2>&1)"; RC=$?
if [ "$RC" -ne 0 ] && ! <<<"$OUT" grep -q "ERROR: AddressSanitizer" && <<<"$OUT" grep -q "^FAIL: B: the wallet's own send was released" \
   && ! <<<"$OUT" grep -q "FAIL: setup\|FAIL: control\|FAIL: session\|FAIL: relaunch\|^FAIL: A:"; then echo "  [red2] case B failed, as required: $(<<<"$OUT" grep -m1 '^FAIL: B:')"
else echo "GATE FAILURE: the second red arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -5; FAIL=1; fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
OUT="$(timeout -k 5 300 "$BUILD_DIR/green" 2>&1)"; RC=$?
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -q "ERROR: AddressSanitizer" && <<<"$OUT" grep -q "^PASS: unconf_credit_kat"; then echo "  [green] all checks pass"
else echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR" | head -5; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: unconf_credit_kat (A and B red-then-green; ASan)"; exit 0; }
echo "FAIL: unconf_credit_kat"; exit 1
