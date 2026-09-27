#!/usr/bin/env bash
# Host KAT runner: second-source corroboration of the filter-header chain
# (observe-only). See cf_corroboration_kat_main.c for the invariant.
#
# Rig: main.c #include-s BOTH BRPeer.c and BRPeerManager.c (the
# cf_checkpoint_quorum_kat idiom), so neither is passed as a separate unit below.
# The two request senders the connect hook calls are intercepted by a per-TU
# rename inside main.c (no linker flags needed).
#
# ==== RED-BEFORE-GREEN GATE ====
#   RED   -DCF_CORROBORATION_UNFIXED restores the pre-corroboration shape in
#         BRPeerManager.c (handler logs only; the connect hook sends no
#         getcfcheckpt). The build must exit nonzero AND fail at the connect
#         and agree checks named below -- proof the seam reaches both sites.
#   GREEN the production shape must pass every check, exit 0, and print
#         exactly ONE "cf-corroborate: height <h> MISMATCH" line for the one
#         mismatching reply in test_disagree plus the one in test_same_address.
#   GUARD test_never, test_same_address, test_bounded, test_request_gated_at_reply_cap:
#         same outcome in both arms (the fields stay put / no request either way);
#         they pin the bounds, not the fix.
# Both word sizes are built and run (ASan, -m32 loop as wire_count_bounds_kat).
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

UNITS=(
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
    # -DDEBUG: on the host peer_log is compiled out without it (BRPeer.h); the green
    # arm asserts on the MISMATCH and request log lines.
    clang -w -include stdint.h -g -DDEBUG -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_corroboration_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

# The seam must exist at BOTH sites or the -D would be inert.
if [ "$(grep -c 'CF_CORROBORATION_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 2 ]; then
    echo "GATE FAILURE: BRPeerManager.c has fewer than two CF_CORROBORATION_UNFIXED seams; -D would be inert."
    exit 1
fi

FAIL=0

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DCF_CORROBORATION_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    echo "--- RED ARM (-DCF_CORROBORATION_UNFIXED): must fail the connect + agree + disagree checks ---"
    "$BUILD_DIR/red${bits}" > "$BUILD_DIR/red${bits}.log" 2>&1; local rc=$?
    if [ "$rc" -eq 0 ]; then
        echo "  [$bits red] GATE FAILURE: the pre-corroboration build exited 0"
        sed 's/^/      | /' "$BUILD_DIR/red${bits}.log" | grep -E "^      \| (PASS|FAIL)" | head -20
        FAIL=1
    fi
    for want in \
        "FAIL: connect: exactly one getcfcheckpt is sent on a filter-capable connect" \
        "FAIL: agree: cfCorroboratedThrough advances to the highest matched 1000-multiple above the compiled table" \
        "FAIL: disagree: cfCheckptDisagreeCount increments once for the mismatching reply"; do
        if grep -qF "$want" "$BUILD_DIR/red${bits}.log"; then
            echo "  [$bits red] required failure seen: ${want#FAIL: }"
        else
            echo "  [$bits red] GATE FAILURE: expected line missing: $want"
            FAIL=1
        fi
    done
    if grep -Eq "$SAN_RE" "$BUILD_DIR/red${bits}.log"; then
        echo "  [$bits red] GATE FAILURE: sanitizer report in the red arm"; FAIL=1
    fi
    echo "  [$bits red] GUARD cases (same outcome in both arms): never / same-address / bounded / request_gated_at_reply_cap"

    echo "--- GREEN ARM: every check must pass, one MISMATCH line per mismatching reply ---"
    "$BUILD_DIR/green${bits}" > "$BUILD_DIR/green${bits}.log" 2>&1; rc=$?
    grep -E "^(PASS|FAIL|test_|cf_corroboration_kat)" "$BUILD_DIR/green${bits}.log" | sed 's/^/  /'
    if [ "$rc" -ne 0 ]; then
        echo "  [$bits green] FAILED (rc=$rc)"; FAIL=1
    fi
    if grep -Eq "$SAN_RE" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$BUILD_DIR/green${bits}.log" | head -3; FAIL=1
    fi
    local mm; mm="$(grep -c 'cf-corroborate: height [0-9]* MISMATCH' "$BUILD_DIR/green${bits}.log")"
    if [ "$mm" -eq 2 ]; then
        echo "  [$bits green] exactly one MISMATCH log line per mismatching reply (2 replies, $mm lines)"
    else
        echo "  [$bits green] GATE FAILURE: expected 2 MISMATCH log lines (one per mismatching reply), saw $mm"; FAIL=1
    fi
    if grep -q "calling getcfcheckpt" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] the real sender was reached (request logged)"
    else
        echo "  [$bits green] GATE FAILURE: the real getcfcheckpt sender never logged a request"; FAIL=1
    fi
}

run_bits 64
run_bits 32

# Optional: keep the raw per-arm logs (KAT_KEEP_LOGS=<dir>) for an evidence record.
if [ -n "${KAT_KEEP_LOGS:-}" ]; then
    mkdir -p "$KAT_KEEP_LOGS" && cp "$BUILD_DIR"/red64.log "$BUILD_DIR"/green64.log "$BUILD_DIR"/red32.log "$BUILD_DIR"/green32.log "$KAT_KEEP_LOGS"/ 2>/dev/null || true
fi

echo
if [ "$FAIL" -ne 0 ]; then
    echo "cf_corroboration_kat: FAILED"
    exit 1
fi
echo "cf_corroboration_kat: PASS (64- and 32-bit; red arm refused, green arm clean)"
