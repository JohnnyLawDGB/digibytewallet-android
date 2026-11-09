#!/usr/bin/env bash
# Host KAT runner: G5-QUORUM part 3 -- no lone-peer re-anchor above the top compiled checkpoint,
# except the user's own node in exclusive mode. See cf_lone_peer_quorum_kat_main.c.
#
# Rig: main.c #include-s BRPeerManager.c (the cf_checkpoint_veto_kat idiom), so it is not passed
# as a separate unit below. ASan, 64- and 32-bit.
#
# ==== RED-BEFORE-GREEN GATE ====
#   RED   -DCF_LONE_PEER_REANCHOR_UNFIXED restores the earlier single-peer hatch. The build must
#         exit nonzero and its FAIL lines must be EXACTLY the five listed below.
#   GREEN every check passes, exit 0, no sanitizer report.
#   GUARD own-node exclusive and the corroborated divergence pass in both arms.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0 detect_leaks=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRPeer.c"
    "$CORE_DIR/BRWallet.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCompactFilterChain.c"
    "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRCFScanLedger.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_lone_peer_quorum_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

if ! grep -q 'CF_LONE_PEER_REANCHOR_UNFIXED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c has no CF_LONE_PEER_REANCHOR_UNFIXED seam; -D would be inert."; exit 1
fi

WANT_RED="$(sort <<'LINES'
FAIL: lone: a lone peer above the compiled table never re-anchors the chain
FAIL: lone: every refused round from the threshold on is recorded as a stall
FAIL: lone: the peer is not banned for it either
FAIL: not-exclusive: a pinned but non-exclusive lone peer does not re-anchor
FAIL: split: lone peers that disagree with each other do not re-anchor
FAIL: one-host: two ports of one address do not corroborate a lone divergence
LINES
)"

FAIL=0
run_bits() {
    local bits="$1"
    echo "=== ${bits}-bit build ==="
    if ! build "$BUILD_DIR/red${bits}" "$bits" -DCF_LONE_PEER_REANCHOR_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."; FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."; FAIL=1; return
    fi

    "$BUILD_DIR/red${bits}" > "$BUILD_DIR/red${bits}.log" 2>&1; local rc=$?
    local got; got="$(grep '^FAIL: ' "$BUILD_DIR/red${bits}.log" | sort)"
    if [ "$rc" -ne 0 ] && [ "$got" = "$WANT_RED" ]; then
        echo "  [$bits red] the earlier hatch fails exactly the lone / not-exclusive / split / one-host checks"
    else
        echo "  [$bits red] GATE FAILURE (rc=$rc): expected exactly:"; sed 's/^/      | /' <<<"$WANT_RED"
        echo "      saw:"; sed 's/^/      | /' <<<"$got"; FAIL=1
    fi
    if grep -Eq "$SAN_RE" "$BUILD_DIR/red${bits}.log"; then echo "  [$bits red] GATE FAILURE: sanitizer report"; FAIL=1; fi

    "$BUILD_DIR/green${bits}" > "$BUILD_DIR/green${bits}.log" 2>&1; rc=$?
    grep -E "^(PASS|FAIL|cf_lone_peer_quorum_kat)" "$BUILD_DIR/green${bits}.log" | sed 's/^/  /'
    if [ "$rc" -ne 0 ]; then echo "  [$bits green] FAILED (rc=$rc)"; FAIL=1; fi
    if grep -Eq "$SAN_RE" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$BUILD_DIR/green${bits}.log" | head -3; FAIL=1
    fi
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -ne 0 ]; then echo "cf_lone_peer_quorum_kat: FAILED"; exit 1; fi
echo "cf_lone_peer_quorum_kat: PASS (64- and 32-bit; red arm fails exactly its cases, green arm clean)"
