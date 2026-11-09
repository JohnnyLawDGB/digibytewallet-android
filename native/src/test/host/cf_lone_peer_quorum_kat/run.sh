#!/usr/bin/env bash
# Host KAT runner: G5-QUORUM part 3 -- no lone-peer re-anchor above the top compiled checkpoint,
# except the user's own node in exclusive mode. See cf_lone_peer_quorum_kat_main.c.
#
# Rig: main.c #include-s BRPeerManager.c (the cf_checkpoint_veto_kat idiom), so it is not passed
# as a separate unit below. ASan, 64- and 32-bit.
#
# ==== RED-BEFORE-GREEN GATE ====
#   RED   -DCF_LONE_PEER_REANCHOR_UNFIXED restores the earlier single-peer hatch (the earlier hatch
#         re-anchors onto the lone peer's chain, and its next diverging batch is then refused).
#   RED   -DCF_CKPT_G5R2_UNFIXED restores the f795f60 hatch: corroboration by exact address, and
#         a refused round re-asked every round.
#   Each red build must exit nonzero and its FAIL lines must equal its WANT_* list below exactly.
#   GREEN every check passes, exit 0, no sanitizer report.
#   GUARD own-node exclusive and the distinct-host corroboration pass in every arm.
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

for seam in CF_LONE_PEER_REANCHOR_UNFIXED CF_CKPT_G5R2_UNFIXED; do
    if ! grep -q "$seam" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILURE: BRPeerManager.c has no $seam seam; -D would be inert."; exit 1
    fi
done

WANT_LONE="$(sort <<'LINES'
FAIL: lone: a lone peer above the compiled table never re-anchors the chain
FAIL: lone: every refused round from the threshold on is recorded as a stall
FAIL: lone: the peer is not banned for it either
FAIL: not-exclusive: a pinned but non-exclusive lone peer does not re-anchor
FAIL: split: lone peers that disagree with each other do not re-anchor
FAIL: one-host: two ports of one address do not corroborate a lone divergence
FAIL: netgroup: two addresses in one network group do not corroborate a lone divergence
FAIL: backoff: a refused round holds the cfheaders driver on the contested batch, into the future
LINES
)"
WANT_G5R2="$(sort <<'LINES'
FAIL: netgroup: two addresses in one network group do not corroborate a lone divergence
FAIL: backoff: a refused round holds the cfheaders driver on the contested batch, into the future
LINES
)"

FAIL=0
run_bits() {
    local bits="$1"
    echo "=== ${bits}-bit build ==="
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."; FAIL=1; return
    fi
    local flag want label rc got
    for label in lone g5r2; do
        if [ "$label" = lone ]; then flag=CF_LONE_PEER_REANCHOR_UNFIXED; want="$WANT_LONE"
        else flag=CF_CKPT_G5R2_UNFIXED; want="$WANT_G5R2"; fi
        if ! build "$BUILD_DIR/$label${bits}" "$bits" "-D$flag"; then
            echo "GATE FAILURE: ${bits}-bit $flag arm did not compile (a build error is not evidence)."; FAIL=1; continue
        fi
        "$BUILD_DIR/$label${bits}" > "$BUILD_DIR/$label${bits}.log" 2>&1; rc=$?
        got="$(grep '^FAIL: ' "$BUILD_DIR/$label${bits}.log" | sort)"
        if [ "$rc" -ne 0 ] && [ "$got" = "$want" ]; then
            echo "  [$bits $label] -D$flag fails exactly its $(grep -c . <<<"$want") check(s)"
        else
            echo "  [$bits $label] GATE FAILURE (rc=$rc): expected exactly:"; sed 's/^/      | /' <<<"$want"
            echo "      saw:"; sed 's/^/      | /' <<<"$got"; FAIL=1
        fi
        if grep -Eq "$SAN_RE" "$BUILD_DIR/$label${bits}.log"; then echo "  [$bits $label] GATE FAILURE: sanitizer report"; FAIL=1; fi
    done

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
echo "cf_lone_peer_quorum_kat: PASS (64- and 32-bit; both red arms fail exactly their cases, green arm clean)"
