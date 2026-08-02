#!/usr/bin/env bash
# Host KAT runner for the _BRPeerManagerClearMemory PRUNE AMORTISATION fix.
#
# WHAT IT PROVES. Once CF_RETENTION_SPAN_MAX binds, the retention floor becomes
# `tip - SPAN` and rises by ONE per block-add, which defeats the existing O(1) no-op
# short-circuit (its memo is keyed `cfFloor <= clearMemNoopFloor`, and freeing a block
# resets that memo to 0 anyway). Every block-add then walked the entire resident set to
# free ONE block. On a Note 8 that was ~149,198 BRSetGet/header = 77 ms/header with one
# core pegged, and since the descent holds manager->lock it starved KeepAlive, so the
# residual re-request driver never ran and the scan frontier never advanced — a
# self-sustaining wedge that abandoned 18,549 heights.
#
# cf_prune_amortize_kat_main.c #include-s BRPeerManager.c directly to reach the file-static
# _BRPeerManagerClearMemory and the opaque BRPeerManagerStruct. BRPeerManager.c is therefore
# deliberately NOT ALSO passed as a separate compilation unit — every symbol would be
# defined twice and the link would fail.
#
# -DCF_PRUNE_INSTRUMENT compiles in two counters (descents, BRSetGet-in-descent) that exist
# ONLY for this gate. The assertion is on WORK, so the result is deterministic and does not
# depend on host speed.
#
# NO CONSTANT SCALING -- and that is deliberate. An earlier revision passed
# -DCF_RETENTION_SPAN_MAX=6000u -DCLEAR_MEM_PRUNE_STRIDE=64u and BOTH WERE SILENTLY IGNORED:
# BRPeerManager.h declares them with a plain #define, so the header's definition wins over the
# command line, and -w suppressed the redefinition warning. The run reported "harness-scaled"
# while executing at full production scale. Never assume a -D reached the code -- guard the
# define with #ifndef or give the harness its own name.
#
# So this gate runs at the REAL constants: a genuine ~152,000-block resident set and the real
# 2048 stride. Only the ITERATION COUNT differs per arm, via -DKAT_ADDS (a name defined in the
# KAT itself, not a production constant):
#   GREEN default STRIDE*4 = 8192 adds -> must be <= 6 descents. ~3 min under ASan.
#   RED   -DKAT_ADDS=64                -> unfixed does a FULL 150k walk per add, so 8192 would
#                                          be ~1.2e9 lookups (hours). 64 adds yields 64
#                                          descents against an allowance of 2: fails in seconds.
#
# ==== RED-BEFORE-GREEN GATE ==================================================
# Built TWICE:
#   * UNFIXED (-DPRUNE_STRIDE_UNFIXED): the amortisation guard is compiled OUT, so the
#     descent runs on every block-add. This build MUST FAIL. If it passes, the gate is
#     not actually testing the fix and must not be trusted.
#   * FIXED (no flag): the guard is live. This build MUST PASS.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../main/jni/digibytewallet-core" && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)

# PRODUCTION-VALUE FLOOR. The gate's arithmetic (allowed = ADDS/STRIDE + 2) self-scales to
# whatever stride is compiled in, so it would keep passing even if the stride were set to 1 --
# which would restore the per-block walk this KAT exists to prevent. Pin a floor explicitly.
PROD_STRIDE="$(grep -oE '^#define CLEAR_MEM_PRUNE_STRIDE [0-9]+' "$CORE_DIR/BRPeerManager.h" | grep -oE '[0-9]+$')"
if [ -z "$PROD_STRIDE" ] || [ "$PROD_STRIDE" -lt 512 ]; then
    echo "GATE FAILURE: production CLEAR_MEM_PRUNE_STRIDE is '${PROD_STRIDE:-unset}', expected >= 512."
    echo "A tiny stride re-introduces the per-block O(resident) walk this KAT exists to prevent."
    exit 1
fi
echo "production CLEAR_MEM_PRUNE_STRIDE = $PROD_STRIDE (>= 512 required)"

build() {
    local out="$1"; shift
    clang -w -include stdint.h \
        -DCF_PRUNE_INSTRUMENT \
        "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/cf_prune_amortize_kat_main.c" \
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

echo "=== building UNFIXED (red arm: amortisation compiled out) ==="
if ! build "$BUILD_DIR/kat_unfixed" -DPRUNE_STRIDE_UNFIXED -DKAT_ADDS=64u; then
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
    echo "GATE FAILURE: the UNFIXED build PASSED. The amortisation red-before-green gate is"
    echo "not exercising the defect — a per-block O(resident) descent went undetected."
    exit 1
fi
# A nonzero exit is NOT sufficient. During development this arm was killed by hand and the
# gate happily proceeded, reporting success — a kill, an ASan abort, a timeout or a segfault
# all exit nonzero and would fake a red arm. Require the SPECIFIC assertion to have failed.
if ! grep -q "\[FAIL\] AMORTISED:" "$RED_OUT"; then
    cat "$RED_OUT"
    echo
    echo "GATE FAILURE: the UNFIXED build exited nonzero WITHOUT failing the amortisation"
    echo "assertion. It was killed, crashed, or died in setup — that is not a red arm."
    exit 1
fi
grep -E "descents=|\[FAIL\] AMORTISED:" "$RED_OUT" | sed 's/^/  red: /'
echo "(red arm failed the amortisation assertion, as required)"

echo
echo "---- GREEN ARM: must PASS ----"
if ! "$BUILD_DIR/kat_fixed"; then
    echo "GATE FAILURE: the FIXED build did not pass."
    exit 1
fi

echo
echo "cf_prune_amortize_kat: RED-BEFORE-GREEN OK"
exit 0
