#!/usr/bin/env bash
# Host KAT runner for BRWatchDescriptor (watch-only "cold account" descriptors).
#
# Same real-file compile approach as bip86_derivation_kat (see its run.sh for why every one of these
# translation units must be linked): the REAL submodule files, built with clang and -include stdint.h
# for the crypto/odocrypt.h reasons documented in bip340_kat/run.sh.
#
# Two builds: the fix (every check must pass) and the RED arm (-DWATCH_DESCRIPTOR_RED_CHAIN, the coin-type
# check removed), which must fail exactly the three wrong-chain cases and nothing else.
#
# Exit code 0 = fix green and red arm failing exactly its cases, 1 otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

build() {   # $1 = output name, rest = extra flags
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/watch_descriptor_kat_main.c" \
        "$CORE_DIR/BRWatchDescriptor.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -o "$BUILD_DIR/$out"
}

# WATCH_KAT_SANITIZE overrides the sanitizer list; set it empty only on a machine whose clang has no
# sanitizer runtime (the check itself is the same either way).
SAN="${WATCH_KAT_SANITIZE-address,undefined}"
if [ -n "$SAN" ]; then
    build watch_descriptor_kat -g -fsanitize="$SAN" -fno-omit-frame-pointer
else
    build watch_descriptor_kat -g
fi
build watch_descriptor_kat_red -DWATCH_DESCRIPTOR_RED_CHAIN

echo "== fix =="
"$BUILD_DIR/watch_descriptor_kat"

echo "== red arm (coin-type check removed): must fail exactly the wrong-chain cases =="
set +e
red_out="$("$BUILD_DIR/watch_descriptor_kat_red")"
red_rc=$?
set -e
fails="$(grep '^FAIL:' <<<"$red_out" || true)"
printf '%s\n' "$fails"
n_fail="$(grep -c . <<<"$fails" || true)"
n_chain="$(grep -c 'wrong_chain' <<<"$fails" || true)"
if [ "$red_rc" -ne 0 ] && [ "$n_fail" -eq 3 ] && [ "$n_chain" -eq 3 ]; then
    echo "red arm: failed exactly the 3 wrong-chain cases, as it must"
else
    echo "red arm: expected exactly 3 wrong-chain failures, got $n_fail (wrong-chain: $n_chain)"
    exit 1
fi
