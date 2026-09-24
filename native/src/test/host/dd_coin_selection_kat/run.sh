#!/usr/bin/env bash
# Host KAT runner: the DigiDollar transfer builder tries another coin before it refuses, and every
# transfer the base builder accepts is reproduced byte-for-byte.
#
# METHOD. The main #includes the LIVE BRWallet.c (so the current builder's struct/statics/macros are
# in this translation unit) and, beside it, base_builder.c -- the transfer function copied verbatim
# from the base core commit 5b45756 and renamed BRWalletCreateDigiDollarTransfer_base. The KAT calls
# both over a generated corpus and asserts byte-identical output wherever the base builds. Because
# BRWallet.c is #included, it is NOT on the compiler source line below (it would define its symbols
# twice) -- the same include-the-.c pattern as digidollar_send_kat.
#
# RED / GREEN. This is a single-arm assertion KAT: the fix is a direct edit of the function, not a
# -D seam. On the unmodified tree the current builder == the base builder, so the "builds from
# another coin" assertions FAIL (recorded red). With the fix they pass and the differential holds.
#
# BASE-DRIFT GATE. If the base object is reachable in this checkout, run.sh regenerates the base
# function from `git show 5b45756:BRWallet.c` -- locating it BY PATTERN, from its signature to its
# closing brace, so no line number is baked in -- and fails if the committed base_builder.c has
# drifted, so the differential is always against the real base. Where the object is not present (an
# exported tree) the gate prints "SKIPPED" and the committed base_builder.c, verified byte-identical
# to the base function at authoring time, is used as-is.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

BASE_COMMIT=5b45756

# ---- base-drift gate (best effort) ------------------------------------------------------------
if git -C "$CORE_DIR" cat-file -e "$BASE_COMMIT:BRWallet.c" 2>/dev/null; then
    # The range is derived by PATTERN -- the function signature to its closing brace at column 0 --
    # not by line number, so the gate keeps working wherever the function sits in the base file.
    git -C "$CORE_DIR" show "$BASE_COMMIT:BRWallet.c" \
        | awk '/^BRTransaction \*BRWalletCreateDigiDollarTransfer\(/{p=1} p{print} p&&/^}/{exit}' \
        | sed '1s/BRWalletCreateDigiDollarTransfer/BRWalletCreateDigiDollarTransfer_base/' \
        > "$BUILD_DIR/base_gen.c"
    if [ ! -s "$BUILD_DIR/base_gen.c" ]; then
        echo "GATE FAILURE: could not locate the base transfer builder in ${BASE_COMMIT}:BRWallet.c"
        exit 1
    fi
    awk '/^BRTransaction \*BRWalletCreateDigiDollarTransfer_base\(/{p=1} p' \
        "$SCRIPT_DIR/base_builder.c" > "$BUILD_DIR/base_committed.c"
    if diff -u "$BUILD_DIR/base_committed.c" "$BUILD_DIR/base_gen.c" > "$BUILD_DIR/base.diff"; then
        echo "base-drift gate: committed base_builder.c matches ${BASE_COMMIT}:BRWallet.c"
    else
        echo "GATE FAILURE: base_builder.c has drifted from ${BASE_COMMIT}:BRWallet.c"
        sed 's/^/    /' "$BUILD_DIR/base.diff" | head -40
        exit 1
    fi
else
    # Distinctive marker: the gate did NOT run, so a runner that requires it can grep for this.
    echo "base-drift gate: SKIPPED -- ${BASE_COMMIT}:BRWallet.c not reachable in this checkout;"
    echo "                using the committed base_builder.c (verified at authoring time)."
fi

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
    -I "$SCRIPT_DIR" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/dd_coin_selection_kat_main.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lpthread -lm \
    -o "$BUILD_DIR/dd_coin_selection_kat" || { echo "FAIL: build error"; exit 1; }

# detect_leaks on: the build paths free their heap work arrays on every return.
ASAN_OPTIONS="abort_on_error=1 detect_leaks=1" "$BUILD_DIR/dd_coin_selection_kat"
