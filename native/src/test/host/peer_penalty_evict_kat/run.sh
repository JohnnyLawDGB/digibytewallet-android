#!/usr/bin/env bash
# Host KAT runner for the PEER PENALTY EVICTION POLICY fix.
#
# WHAT IT PROVES. The penalty table was 32 entries with `idx = penaltyCount % PEER_PENALTY_MAX`
# — evict the OLDEST INSERT, unconditionally. A live 10-minute "doesn't support SPV mode" ban
# was therefore routinely discarded to make room for an unrelated 30-second redial cooldown; the
# un-banned peer reconnected, was rejected, and was re-penalised, evicting someone else in turn.
# Measured on a Note 8 during a deep restore: 41 distinct non-SPV peers producing 3,520
# disconnects in about ONE MINUTE (~86 redials each), consuming every connection slot.
#
# The 30s redial cooldown added the same day made it strictly worse — it inserts on EVERY clean
# disconnect, so ordinary churn pumped the eviction rate and flushed the bans that mattered.
#
# The pre-existing peer_penalty_kat covers only BRPeerPenaltyContains (the pure lookup helper)
# and has no red arm; it never touched the insert/evict policy, which is how this survived.
#
# peer_penalty_evict_kat_main.c #include-s BRPeerManager.c directly to reach the file-static
# _penalizeFor and the opaque BRPeerManagerStruct. BRPeerManager.c is therefore deliberately NOT
# ALSO passed as a separate compilation unit — every symbol would be defined twice.
#
# NO CONSTANT SCALING: runs at the real PEER_PENALTY_MAX / PEER_PENALTY_SECONDS /
# PEER_REDIAL_COOLDOWN_SECONDS. (A -D could not override them anyway — BRPeerManager.c declares
# them with a plain #define, which WINS over the command line while -w hides the redefinition
# warning. That cost an afternoon on cf_prune_amortize_kat; do not repeat it.)
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Built TWICE:
#   * UNFIXED (-DPENALTY_EVICT_UNFIXED): restores the oldest-insert ring. MUST FAIL, and must
#     fail on the SPECIFIC "BANS HELD" assertion — a killed, crashed or timed-out arm also
#     exits nonzero and would otherwise fake a red arm (that happened during development).
#   * FIXED (no flag): evict-by-expiry. MUST PASS.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

# PRODUCTION-VALUE FLOOR. The gate fills the table and churns 2x its size, so it self-scales to
# whatever PEER_PENALTY_MAX is compiled in and would keep passing at 32 — the size that produced
# the measured storm. Pin a floor so a future shrink cannot pass silently.
PROD_MAX="$(grep -oE '^#define PEER_PENALTY_MAX +[0-9]+' "$CORE_DIR/BRPeerManager.c" | grep -oE '[0-9]+$')"
if [ -z "$PROD_MAX" ] || [ "$PROD_MAX" -lt 128 ]; then
    echo "GATE FAILURE: production PEER_PENALTY_MAX is '${PROD_MAX:-unset}', expected >= 128."
    echo "41 distinct junk peers were observed in ONE minute on a single device; a table smaller"
    echo "than the real working set churns its own entries no matter how good the evict policy is."
    exit 1
fi
echo "production PEER_PENALTY_MAX = $PROD_MAX (>= 128 required)"

build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_penalty_evict_kat_main.c" \
        "$CORE_DIR/BRPeer.c" \
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

echo "=== building UNFIXED (red arm: oldest-insert ring restored) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPENALTY_EVICT_UNFIXED; then
    echo "BUILD FAILURE (unfixed arm) — gate cannot run"; exit 1
fi
echo "=== building FIXED ==="
if ! build "$BUILD_DIR/kat_fixed"; then
    echo "BUILD FAILURE (fixed arm)"; exit 1
fi

echo
echo "---- RED ARM: must FAIL, and for the RIGHT REASON ----"
RED_OUT="$BUILD_DIR/red.out"
if "$BUILD_DIR/kat_unfixed" > "$RED_OUT" 2>&1; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build PASSED. The eviction gate is not exercising the"
    echo "defect — a short cooldown evicting a live ban went undetected."
    exit 1
fi
if ! grep -q "\[FAIL\] BANS HELD:" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build exited nonzero WITHOUT failing the BANS HELD"
    echo "assertion. It was killed, crashed, or died in setup — that is not a red arm."
    exit 1
fi
grep -E "long bans survived|\[FAIL\] BANS HELD:" "$RED_OUT" | sed 's/^/  red: /'
echo "(red arm failed the BANS HELD assertion, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "peer_penalty_evict_kat: RED-BEFORE-GREEN OK"
exit 0
