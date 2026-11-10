#!/usr/bin/env bash
#
# derivation_profiles_kat — the core's Recover funds derivation (BRDerivationProfiles.h, BRRecoveryDerive.h)
# is Android's: the same table as DerivationProfile.kt BUILT_INS, and the same bytes as the bridge's own
# jni_derive.c (mnemonicToSeed, deriveAddresses) for every profile, seed and format, over the whole starting
# window. See derivation_profiles_kat_main.c for the cases.
#
#   RED    -DDERIVATION_PROFILES_RED (the BIP49 profile written as P2WPKH): profile_5 MUST fail, every
#          other case pass.
#   RED    -DRECOVERY_SEED_NUL_RED (a passphrase with a NUL accepted): seed_nul_refused MUST fail, every
#          other case pass.
#   GREEN  every case passes, ALL PASS, exit 0.
#   All three arms under ASan + UBSan (no recovery), 64-bit.
#   CURRENT  the core's vectors/recovery-derivation/vectors.txt is what its generate.c prints today.
#   SEAMS    each RED macro selects exactly one block in the core headers.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule. The bridge
# (jni_derive.c) always comes from this repo.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
KOTLIN="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/recovery/DerivationProfile.kt"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'

for f in BRDerivationProfiles.h BRRecoveryDerive.h vectors/recovery-derivation/generate.c vectors/recovery-derivation/vectors.txt; do
    [ -s "$CORE_DIR/$f" ] || { echo "GATE FAILURE: $CORE_DIR/$f is missing (core older than the derivation push-down?)"; exit 1; }
done
VECTOR_FILES=("$CORE_DIR/vectors/derivation/abandon-about.txt" "$CORE_DIR/vectors/derivation/abandon-about-trezor.txt")

# SEAMS: a -D that names nothing would build the shipped table and prove nothing.
[ "$(grep -c '^#ifdef DERIVATION_PROFILES_RED$' "$CORE_DIR/BRDerivationProfiles.h")" -eq 1 ] ||
    gate_fail "DERIVATION_PROFILES_RED does not select exactly one block in BRDerivationProfiles.h"
[ "$(grep -c '^#ifndef RECOVERY_SEED_NUL_RED$' "$CORE_DIR/BRRecoveryDerive.h")" -eq 1 ] ||
    gate_fail "RECOVERY_SEED_NUL_RED does not select exactly one block in BRRecoveryDerive.h"

# Android's table, as C.
python3 -I "$SCRIPT_DIR/kotlin_profiles.py" "$KOTLIN" > "$BUILD_DIR/kotlin_profiles.h" ||
    { echo "GATE FAILURE: could not read BUILT_INS from $KOTLIN"; exit 1; }

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob
UNITS=(
    "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRWallet.c"
    "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() {
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$BUILD_DIR" -I "$SCRIPT_DIR/host_jni" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/derivation_profiles_kat_main.c" "$BRIDGE_DIR/jni_derive.c" "${UNITS[@]}" \
        -lm -lpthread -o "$out"
}

ALL_CASES=(profile_count hard_bit seed_match seed_bounds seed_nul_refused phrase_with_nul
           profile_0 profile_1 profile_2 profile_3 profile_4 profile_5 profile_6
           all_formats derive_refusals slot_positions independent_vectors caller_seed_untouched)

# $1 arm name, $2 binary, $3 the one case that must fail ("" = none).
check_arm() {
    local arm="$1" bin="$2" red="$3" out rc c
    out="$(timeout -k 5 300 "$bin" "${VECTOR_FILES[@]}" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { gate_fail "[$arm] sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; }
    for c in "${ALL_CASES[@]}"; do
        if [ "$c" = "$red" ]; then
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$arm $c] failed, as it must"
            else gate_fail "[$arm $c] did not fail in the comparison arm"; fi
        elif <<<"$out" grep -q "^RESULT $c pass$"; then :
        else gate_fail "[$arm $c] did not pass"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; fi
    done
    if [ -z "$red" ]; then
        if [ $rc -eq 0 ] && <<<"$out" grep -q "^ALL PASS$"; then echo "  [$arm] ALL PASS (${#ALL_CASES[@]} cases)"
        else gate_fail "[$arm] shipped arm did not pass (rc=$rc)"; fi
        <<<"$out" grep "independent addresses checked" | sed 's/^/  /'
    else
        [ $rc -ne 0 ] || gate_fail "[$arm] the comparison arm exited 0; it proves nothing"
        <<<"$out" grep "^FAIL: $red" | head -2 | sed 's/^/      /'
    fi
}

build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile"; exit 1; }
build "$BUILD_DIR/profiles_red" -DDERIVATION_PROFILES_RED || { echo "GATE FAILURE: DERIVATION_PROFILES_RED arm did not compile"; exit 1; }
build "$BUILD_DIR/seed_red" -DRECOVERY_SEED_NUL_RED || { echo "GATE FAILURE: RECOVERY_SEED_NUL_RED arm did not compile"; exit 1; }

echo "--- COMPARISON ARM -DDERIVATION_PROFILES_RED: only profile_5 may fail ---"
check_arm profiles_red "$BUILD_DIR/profiles_red" profile_5
echo "--- COMPARISON ARM -DRECOVERY_SEED_NUL_RED: only seed_nul_refused may fail ---"
check_arm seed_red "$BUILD_DIR/seed_red" seed_nul_refused
echo "--- SHIPPED ARM: every case passes ---"
check_arm green "$BUILD_DIR/green" ""

echo "--- CURRENT: vectors/recovery-derivation/vectors.txt is what generate.c prints ---"
if "$CC" -w -include stdint.h -fsanitize=address,undefined -fno-sanitize-recover=undefined \
       -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
       "$CORE_DIR/vectors/recovery-derivation/generate.c" "${UNITS[@]}" -lm -lpthread -o "$BUILD_DIR/gen"; then
    if timeout -k 5 120 "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt" &&
       cmp -s "$BUILD_DIR/vectors.txt" "$CORE_DIR/vectors/recovery-derivation/vectors.txt"; then
        echo "  [current] $(grep -c '^addr ' "$BUILD_DIR/vectors.txt") addresses, $(grep -c '^profile ' "$BUILD_DIR/vectors.txt") profiles: up to date"
    else
        gate_fail "vectors/recovery-derivation/vectors.txt is not what generate.c prints"
        diff "$CORE_DIR/vectors/recovery-derivation/vectors.txt" "$BUILD_DIR/vectors.txt" | head -5
    fi
else gate_fail "generate.c did not compile"; fi

if [ $FAIL -eq 0 ]; then
    echo "PASS: derivation_profiles_kat (core table = Kotlin BUILT_INS; core = bridge for every profile, seed and format; two RED arms fail only their case; ASan+UBSan; vectors current)"
    exit 0
fi
echo "FAIL: derivation_profiles_kat"
exit 1
