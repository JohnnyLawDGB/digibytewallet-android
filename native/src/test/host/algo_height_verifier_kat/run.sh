#!/usr/bin/env bash
# Host KAT runner: a relayed header is accepted only if it names a proof-of-work algorithm the chain
# allows at the header's height (_BRPeerManagerVerifyBlock, BRChainParamsAlgoAllowed).
#
# The _main.c #includes BRPeerManager.c and drives the REAL _peerRelayedBlock with synthetic headers on
# every algorithm boundary of both chains (see its header comment). Three arms, selected by the level
# macro DGB_HEADER_POW_CHECK the shipped code is built with:
#   level 2 (enforce)              every case gets the level-2 verdict (groestl at 23,808,000 rejected, at
#                                  23,807,995 accepted; testnet groestl at 500 accepted, odo at 519 accepted,
#                                  groestl at 501 rejected; unknown bits rejected everywhere); a rejection
#                                  leaves the tip in place and counts the peer as misbehaving
#   level 1 (observe)                every case accepted; the log carries one "algo-by-height" line per case
#                                  that level 2 rejects
#   level 0 (COMPARISON / GUARD)   every case accepted and no "algo-by-height" line at all: the earlier
#                                  verdicts. REQUIRED, as the red evidence that the arm sees what the
#                                  check governs.
# Each arm compiles BRPeerManager.c under ASan, 64-bit and 32-bit; allow a few minutes on a busy host.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# BRPeerManager.c is not in this list: the _main.c #includes it to reach the static function.
UNITS=(
    "$CORE_DIR/BRPeer.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRWalletFilterElements.c" "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() { # $1=level $2=bits $3=output
    local m=(); [ "$2" = "32" ] && m=(-m32)
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g "${m[@]}" \
        -DDGB_HEADER_POW_CHECK="$1" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/algo_height_verifier_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$3"
}

for f in BRPeerManager.c BRChainParams.h; do
    if ! grep -q 'BRChainParamsAlgoAllowed' "$CORE_DIR/$f"; then
        echo "GATE FAILURE: $f does not carry BRChainParamsAlgoAllowed; the verifier check is absent."; exit 1
    fi
done
if ! grep -q 'DGB_HEADER_POW_CHECK' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c has no DGB_HEADER_POW_CHECK seam; -D would be inert."; exit 1
fi

FAIL=0
run_arm() { # $1=level $2=bits
    local lvl="$1" bits="$2" bin="$BUILD_DIR/l${1}_$2" out rc logged want
    if ! build "$lvl" "$bits" "$bin"; then echo "GATE FAILURE: level-$lvl ${bits}-bit arm did not build."; FAIL=1; return; fi
    "$bin" > "$bin.out" 2>&1; rc=$?
    logged="$(grep -c '^  log: .*algo-by-height' "$bin.out")"
    rejections="$(grep -o 'level2-rejections=[0-9]*' "$bin.out" | head -1 | cut -d= -f2)"
    if grep -Eq "$SAN_RE" "$bin.out"; then
        echo "  [L$lvl $bits] GATE FAILURE: sanitizer report (rc=$rc)"; grep -E "$SAN_RE" "$bin.out" | head -2 | sed 's/^/      /'; FAIL=1; return
    fi
    case "$lvl" in
        2) want="verdicts-as-level-2"; [ "$logged" -ge "${rejections:-1}" ] || { echo "  [L2 $bits] GATE FAILURE: only $logged algo-by-height lines for $rejections rejections"; FAIL=1; } ;;
        1) want="all-accepted"; [ "$logged" -eq "${rejections:-0}" ] || { echo "  [L1 $bits] GATE FAILURE: $logged algo-by-height lines, expected exactly $rejections (one per would-be rejection)"; FAIL=1; } ;;
        0) want="all-accepted"; [ "$logged" -eq 0 ] || { echo "  [L0 $bits] GATE FAILURE: $logged algo-by-height lines in the comparison arm"; FAIL=1; } ;;
    esac
    if grep -q "RESULT $want" "$bin.out"; then
        echo "  [L$lvl $bits] $want; $logged algo-by-height line(s); $(grep -c '^CASE' "$bin.out") cases (rc=$rc)"
    else
        echo "  [L$lvl $bits] GATE FAILURE: expected '$want' (rc=$rc): $(grep -o 'RESULT .*' "$bin.out" | head -1)"
        grep -E '^CASE|shape:' "$bin.out" | awk '$3 != $5 || /shape:/' | head -12 | sed 's/^/      /'; FAIL=1
    fi
}

for bits in 64 32; do
    echo "=== ${bits}-bit ==="
    echo "--- level 2 (enforce): the level-2 verdicts ---";            run_arm 2 "$bits"
    echo "--- level 1 (observe): all accepted, one log line per would-be rejection ---"; run_arm 1 "$bits"
    echo "--- level 0 (COMPARISON ARM / GUARD): all accepted, nothing logged ---";     run_arm 0 "$bits"
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: algo_height_verifier_kat (level 2 rejects by height, level 1 logs only, level 0 reproduces the earlier verdicts; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: algo_height_verifier_kat"; exit 1
fi
