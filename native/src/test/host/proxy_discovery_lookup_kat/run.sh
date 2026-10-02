#!/usr/bin/env bash
# Host KAT: while a SOCKS proxy is set, peer discovery makes no local name lookup
# (see the header of proxy_discovery_lookup_kat_main.c).
#
# Compiles the REAL, live submodule BRPeer.c + BRPeerManager.c into the test (same shape and link
# line as download_peer_promote_kat/run.sh), under ASan, in a 64-bit and a 32-bit build.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of -DPROXY_DISCOVERY_LOOKUP_UNFIXED selects the comparison arm (discovery resolves the
# seed names whatever the proxy); the shipped arm is built with no -D at all.
#   proxy_mainnet, proxy_testnet  RED-THEN-GREEN (assertion): the comparison arm MUST report
#                                 "broken" (it resolved names with a proxy set); shipped "held".
#   direct_mainnet, direct_testnet GUARD: with no proxy every seed name is resolved, in both arms.
#   pinned_proxy                   GUARD: a pinned peer is the only peer, nothing resolved, both arms.
# Detecting a sanitizer report matches "ERROR: AddressSanitizer" or UBSan's "runtime error:".
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="detect_leaks=0 halt_on_error=1"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

# Seam self-check: a -D that names nothing silently builds the shipped path, so the comparison arm
# would run the shipped code and (wrongly) pass.
if ! grep -q 'PROXY_DISCOVERY_LOOKUP_UNFIXED' "$CORE_DIR/BRPeerManager.c"; then
    echo "GATE FAILURE: BRPeerManager.c has no PROXY_DISCOVERY_LOOKUP_UNFIXED seam; -D would be inert."
    exit 1
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1" bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h "${m[@]}" "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/proxy_discovery_lookup_kat_main.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" \
        "$CORE_DIR/BRGCSFilter.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread \
        -o "$out"
}

FAIL=0

# expect <bin> <scenario> <held|broken> <label>
expect() {
    local bin="$1" c="$2" want="$3" label="$4" out rc
    out="$("$bin" "$c" 2>&1)"; rc=$?
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$label $c] GATE FAILURE: sanitizer report (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -8; FAIL=1
    elif echo "$out" | grep -q "RESULT $c $want"; then
        echo "  [$label $c] $want, as required (rc=$rc)"
        [ "$want" = "broken" ] && echo "$out" | grep 'FAIL:' | sed 's/^/      /'
    else
        echo "  [$label $c] GATE FAILURE: expected $want (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -12; FAIL=1
    fi
}

run_bits() {
    local bits="$1"
    echo "=== ${bits}-bit build ==="
    if ! build "$BUILD_DIR/red${bits}" "$bits" -DPROXY_DISCOVERY_LOOKUP_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit shipped arm did not compile."
        FAIL=1; return
    fi

    echo "--- comparison arm (-DPROXY_DISCOVERY_LOOKUP_UNFIXED) ---"
    expect "$BUILD_DIR/red${bits}" proxy_mainnet broken "$bits red"
    expect "$BUILD_DIR/red${bits}" proxy_testnet broken "$bits red"
    expect "$BUILD_DIR/red${bits}" direct_mainnet held "$bits red (guard)"
    expect "$BUILD_DIR/red${bits}" direct_testnet held "$bits red (guard)"
    expect "$BUILD_DIR/red${bits}" pinned_proxy held "$bits red (guard)"

    echo "--- shipped arm ---"
    for c in proxy_mainnet proxy_testnet direct_mainnet direct_testnet pinned_proxy; do
        expect "$BUILD_DIR/green${bits}" "$c" held "$bits green"
    done
}

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: proxy_discovery_lookup_kat (comparison arm resolved names under a proxy; shipped arm did not; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: proxy_discovery_lookup_kat"
    exit 1
fi
