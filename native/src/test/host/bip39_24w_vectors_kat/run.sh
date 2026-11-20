#!/usr/bin/env bash
#
# bip39_24w_vectors_kat (BIP39-24W-VECTORS) -- a 24-word recovery phrase gives exactly the addresses an
# independent implementation computes, for every address type the wallet derives: the seven derivation
# profiles (BIP84, BIP86, legacy m/0' under both master keys, BIP44 coin 20 and coin 0, BIP49), for four
# published 24-word BIP39 test phrases, each without and with a passphrase, through the core, through
# Android's bridge (jni_derive.c with Kotlin's BUILT_INS) and through the wallet's own trees, with the
# wallet opened by the one recipe create/restore call (the core's BRWalletOpen.h). See bip39_24w_vectors_kat_main.c for the cases and generate.py for where vectors.txt comes from.
#
#   RED    -DBIP39_24W_TRUNCATE_RED (only the first 12 words reach the seed): every seed_, core_, bridge_
#          and wallet_ case MUST fail; the two GUARD cases pass.
#   RED    -DBIP39_24W_NO_PASSPHRASE_RED (the passphrase dropped): every case of an odd seed MUST fail,
#          every case of an even seed and the GUARD cases pass.
#   RED    -DDERIVATION_PROFILES_RED, -DRECOVERY_ENCODER_RED (the core's seams): core_s<s>_p5 MUST fail for
#          every seed, every other case pass.
#   RED    -DWALLET_OPEN_SINGLE_TREE_UNFIXED (the recipe's seam in BRWalletOpen.h: BIP84 alone is watched):
#          wallet_s<s> MUST fail for every seed, every other case pass.
#   GREEN  every case passes, ALL PASS, exit 0.
#   All six arms under ASan + UBSan (no recovery).
#   SELF-TEST  on copies of vectors.txt, the shipped arm: one changed address fails exactly its two cases;
#          a 24-word phrase with a bad checksum, and a 12-word phrase, fail phrases_24_words.
#   CROSS  the rows vectors.txt shares with the core's vectors/derivation/abandon-art*.txt (a second
#          independent generator) are the same addresses.
#   CURRENT  vectors.txt is what generate.py prints today (started first, checked last: about 90 s of
#          pure-Python secp256k1, alongside the builds).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule. The bridge
# (jni_derive.c) and Kotlin's table always come from this repo.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail
# Under pipefail nothing here pipes into a reader that can exit early: here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
PROFILES_KAT="$SCRIPT_DIR/../derivation_profiles_kat"     # its host JNI stand-ins and Kotlin table reader
KOTLIN="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/recovery/DerivationProfile.kt"
VECTORS="$SCRIPT_DIR/vectors.txt"
BUILD_DIR="$(mktemp -d)"
GEN_PID=""
trap '[ -n "$GEN_PID" ] && kill "$GEN_PID" 2>/dev/null; rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'

[ -s "$VECTORS" ] || { echo "GATE FAILURE: no vector file at $VECTORS"; exit 1; }
for f in BRDerivationProfiles.h BRRecoveryDerive.h BRWalletOpen.h vectors/recovery-derivation/independent.py \
         vectors/derivation/bip39-english.wordlist vectors/derivation/abandon-art.txt vectors/derivation/abandon-art-trezor.txt; do
    [ -s "$CORE_DIR/$f" ] || { echo "GATE FAILURE: $CORE_DIR/$f is missing"; exit 1; }
done

# CURRENT, started now: the generator runs while the arms build and run.
GEN_LIMIT_S="${BIP39_24W_GENERATE_LIMIT:-900}"
timeout -k 5 "$GEN_LIMIT_S" python3 -I "$SCRIPT_DIR/generate.py" "$CORE_DIR" > "$BUILD_DIR/generated.txt" 2> "$BUILD_DIR/generated.err" &
GEN_PID=$!

# SEAMS: a -D that names nothing would build the shipped code and prove nothing.
[ "$(grep -c '^#ifdef DERIVATION_PROFILES_RED$' "$CORE_DIR/BRDerivationProfiles.h")" -eq 1 ] ||
    gate_fail "DERIVATION_PROFILES_RED does not select exactly one block in BRDerivationProfiles.h"
[ "$(grep -c '^#ifdef RECOVERY_ENCODER_RED$' "$CORE_DIR/BRRecoveryDerive.h")" -eq 1 ] ||
    gate_fail "RECOVERY_ENCODER_RED does not select exactly one block in BRRecoveryDerive.h"
[ "$(grep -c '^#ifdef WALLET_OPEN_SINGLE_TREE_UNFIXED$' "$CORE_DIR/BRWalletOpen.h")" -eq 1 ] ||
    gate_fail "WALLET_OPEN_SINGLE_TREE_UNFIXED does not select exactly one block in BRWalletOpen.h"
[ "$(grep -c '^#ifdef BIP39_24W_TRUNCATE_RED$' "$SCRIPT_DIR/bip39_24w_vectors_kat_main.c")" -eq 1 ] ||
    gate_fail "BIP39_24W_TRUNCATE_RED does not select exactly one block in the KAT"
[ "$(grep -c '^#ifdef BIP39_24W_NO_PASSPHRASE_RED$' "$SCRIPT_DIR/bip39_24w_vectors_kat_main.c")" -eq 1 ] ||
    gate_fail "BIP39_24W_NO_PASSPHRASE_RED does not select exactly one block in the KAT"

# Android's table, as C.
python3 -I "$PROFILES_KAT/kotlin_profiles.py" "$KOTLIN" > "$BUILD_DIR/kotlin_profiles.h" ||
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

# The core units and the bridge are compiled once; each arm compiles only the KAT's own translation unit,
# the one place the five macros are read (the core's three seams are in header-only code that no .c file of
# the core or the bridge includes -- checked here, since an arm built this way would otherwise be half RED).
SEAM_USERS="$(grep -lE 'BRRecoveryDerive\.h|BRDerivationProfiles\.h|BRRecoveryScan\.h|BRDigiId\.h|BRWalletOpen\.h' "$BRIDGE_DIR/jni_derive.c" "${UNITS[@]}" || true)"
[ -z "$SEAM_USERS" ] || gate_fail "a unit compiled once includes a header with a seam: $SEAM_USERS"
# -O1: unoptimised, the core's secp256k1 under two sanitizers makes each of the eight runs take about 25 s.
CFLAGS=(-w -include stdint.h -g -O1 -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer
        -I "$BUILD_DIR" -I "$PROFILES_KAT/host_jni" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")
mkdir "$BUILD_DIR/obj"
n=0
for unit in "$BRIDGE_DIR/jni_derive.c" "${UNITS[@]}"; do   # numbered: crypto/ and crypto/sha3/ share file names
    n=$((n + 1))
    "$CC" "${CFLAGS[@]}" -c "$unit" -o "$BUILD_DIR/obj/$n.o" ||
        { echo "GATE FAILURE: $unit did not compile"; exit 1; }
done

build() {
    local out="$1"; shift
    "$CC" "${CFLAGS[@]}" "$@" "$SCRIPT_DIR/bip39_24w_vectors_kat_main.c" "$BUILD_DIR"/obj/*.o -lm -lpthread -o "$out"
}

# The case list, and the cases each arm must fail.
SEEDS=(0 1 2 3 4 5 6 7)
PROFILES=(0 1 2 3 4 5 6)
GUARD_CASES=(vectors_parsed phrases_24_words)
ALL_CASES=("${GUARD_CASES[@]}")
RED_ALL=""; RED_ODD=""; RED_P5=""; RED_WALLET=""
for s in "${SEEDS[@]}"; do
    mine=("seed_$s")
    for p in "${PROFILES[@]}"; do mine+=("core_s${s}_p$p"); done
    for p in "${PROFILES[@]}"; do mine+=("bridge_s${s}_p$p"); done
    mine+=("wallet_s$s")
    ALL_CASES+=("${mine[@]}")
    RED_ALL+=" ${mine[*]}"
    [ $((s % 2)) -eq 1 ] && RED_ODD+=" ${mine[*]}"
    RED_P5+=" core_s${s}_p5"
    RED_WALLET+=" wallet_s$s"
done

ARM_LIMIT_S="${BIP39_24W_ARM_LIMIT:-600}"   # an arm takes seconds; the limit only bounds a hang

# $1 arm name, $2 binary, $3 the vector file, $4 the cases that must fail, space-separated ("" = none).
check_arm() {
    local arm="$1" bin="$2" file="$3" red="$4" out rc c nred=0 npass=0
    out="$(timeout -k 5 "$ARM_LIMIT_S" "$bin" "$file" 2>&1)"; rc=$?
    if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then gate_fail "[$arm] stopped at the ${ARM_LIMIT_S} s limit (rc=$rc)"; fi
    <<<"$out" grep -Eq "$SAN_RE" && { gate_fail "[$arm] sanitizer report"; <<<"$out" grep -E "$SAN_RE" | head -3; }
    [ "$(<<<"$out" grep -c '^RESULT ')" -eq "${#ALL_CASES[@]}" ] ||
        gate_fail "[$arm] $(<<<"$out" grep -c '^RESULT ') cases reported, expected ${#ALL_CASES[@]}"
    for c in "${ALL_CASES[@]}"; do
        if [[ " $red " == *" $c "* ]]; then
            if <<<"$out" grep -q "^RESULT $c fail$"; then nred=$((nred + 1))
            else gate_fail "[$arm $c] did not fail in the comparison arm"; fi
        elif <<<"$out" grep -q "^RESULT $c pass$"; then npass=$((npass + 1))
        else gate_fail "[$arm $c] did not pass"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; fi
    done
    if [ -z "$red" ]; then
        if [ $rc -eq 0 ] && <<<"$out" grep -q "^ALL PASS$"; then echo "  [$arm] ALL PASS ($npass cases)"
        else gate_fail "[$arm] shipped arm did not pass (rc=$rc)"; fi
        <<<"$out" grep "address rows" | sed 's/^/  /'
    else
        [ $rc -ne 0 ] || gate_fail "[$arm] the comparison arm exited 0; it proves nothing"
        echo "  [$arm] $nred cases failed, as they must; $npass passed"
        <<<"$out" grep "^FAIL: " | head -2 | sed 's/^/      /'
    fi
}

build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile"; exit 1; }
build "$BUILD_DIR/truncate_red" -DBIP39_24W_TRUNCATE_RED || { echo "GATE FAILURE: BIP39_24W_TRUNCATE_RED arm did not compile"; exit 1; }
build "$BUILD_DIR/pass_red" -DBIP39_24W_NO_PASSPHRASE_RED || { echo "GATE FAILURE: BIP39_24W_NO_PASSPHRASE_RED arm did not compile"; exit 1; }
build "$BUILD_DIR/profiles_red" -DDERIVATION_PROFILES_RED || { echo "GATE FAILURE: DERIVATION_PROFILES_RED arm did not compile"; exit 1; }
build "$BUILD_DIR/encoder_red" -DRECOVERY_ENCODER_RED || { echo "GATE FAILURE: RECOVERY_ENCODER_RED arm did not compile"; exit 1; }
build "$BUILD_DIR/recipe_red" -DWALLET_OPEN_SINGLE_TREE_UNFIXED || { echo "GATE FAILURE: WALLET_OPEN_SINGLE_TREE_UNFIXED arm did not compile"; exit 1; }

echo "--- COMPARISON ARM -DBIP39_24W_TRUNCATE_RED: every case but the two GUARDS must fail ---"
check_arm truncate_red "$BUILD_DIR/truncate_red" "$VECTORS" "$RED_ALL"
echo "--- COMPARISON ARM -DBIP39_24W_NO_PASSPHRASE_RED: only the passphrase seeds (odd) may fail ---"
check_arm pass_red "$BUILD_DIR/pass_red" "$VECTORS" "$RED_ODD"
echo "--- COMPARISON ARM -DDERIVATION_PROFILES_RED: only core_s<s>_p5 may fail ---"
check_arm profiles_red "$BUILD_DIR/profiles_red" "$VECTORS" "$RED_P5"
echo "--- COMPARISON ARM -DRECOVERY_ENCODER_RED: only core_s<s>_p5 may fail ---"
check_arm encoder_red "$BUILD_DIR/encoder_red" "$VECTORS" "$RED_P5"
echo "--- COMPARISON ARM -DWALLET_OPEN_SINGLE_TREE_UNFIXED: only wallet_s<s> may fail ---"
check_arm recipe_red "$BUILD_DIR/recipe_red" "$VECTORS" "$RED_WALLET"
echo "--- SHIPPED ARM: every case passes ---"
check_arm green "$BUILD_DIR/green" "$VECTORS" ""

echo "--- SELF-TEST: the shipped arm on changed copies of vectors.txt ---"
# One address replaced by its neighbour's (seed 0, BIP44, receive 0 takes receive 1's): its two cases only.
other="$(awk '$1 == "addr" && $2 == 0 && $3 == 3 && $4 == 0 && $5 == 0 && $6 == 1 { print $7 }' "$VECTORS")"
awk -v o="$other" '$1 == "addr" && $2 == 0 && $3 == 3 && $4 == 0 && $5 == 0 && $6 == 0 { $7 = o } { print }' "$VECTORS" > "$BUILD_DIR/one-address.txt"
if [ -z "$other" ] || cmp -s "$VECTORS" "$BUILD_DIR/one-address.txt"; then gate_fail "[self-test] could not change an address"
else check_arm one_address "$BUILD_DIR/green" "$BUILD_DIR/one-address.txt" " core_s0_p3 bridge_s0_p3"; fi
# guard_bites <name> <file>: the shipped arm fails phrases_24_words on it.
guard_bites() {
    local out rc
    if cmp -s "$VECTORS" "$2"; then gate_fail "[self-test $1] the copy is unchanged"; return; fi
    out="$(timeout -k 5 "$ARM_LIMIT_S" "$BUILD_DIR/green" "$2" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && gate_fail "[self-test $1] sanitizer report"
    if [ $rc -ne 0 ] && <<<"$out" grep -q "^RESULT phrases_24_words fail$"; then echo "  [self-test $1] phrases_24_words failed, as it must"
    else gate_fail "[self-test $1] phrases_24_words did not fail"; fi
}
sed '/^phrase 0 /s/ art$/ about/' "$VECTORS" > "$BUILD_DIR/bad-checksum.txt"
guard_bites "24 words, bad checksum" "$BUILD_DIR/bad-checksum.txt"
sed 's/^phrase 0 .*/phrase 0 abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about/' \
    "$VECTORS" > "$BUILD_DIR/twelve-words.txt"
guard_bites "a valid 12-word phrase" "$BUILD_DIR/twelve-words.txt"

echo "--- CROSS: the rows shared with the core's vectors/derivation/abandon-art*.txt ---"
cross() {   # $1 seed index here, $2 the core's file
    local n=0 miss=0 tree fmt chain index addr line
    while read -r tree fmt chain index addr; do
        [ "$index" -lt 10 ] || continue
        case "$tree $fmt" in
            "bip84 p2wpkh") line="addr $1 0 1 $chain $index $addr" ;;
            "bip86 p2tr")   line="addr $1 6 3 $chain $index $addr" ;;
            "legacy p2pkh") line="addr $1 1 0 $chain $index $addr" ;;
            *) continue ;;
        esac
        n=$((n + 1))
        grep -Fxq -- "$line" "$VECTORS" || miss=$((miss + 1))
    done < <(grep -v '^#' "$2")
    if [ $n -eq 60 ] && [ $miss -eq 0 ]; then echo "  [cross] $(basename "$2"): $n shared addresses agree"
    else gate_fail "[cross] $(basename "$2"): $n shared rows, $miss not in vectors.txt"; fi
}
cross 0 "$CORE_DIR/vectors/derivation/abandon-art.txt"
cross 1 "$CORE_DIR/vectors/derivation/abandon-art-trezor.txt"

echo "--- CURRENT: vectors.txt is what generate.py prints ---"
wait "$GEN_PID"; gen_rc=$?; GEN_PID=""
if [ $gen_rc -ne 0 ]; then gate_fail "generate.py did not finish (rc=$gen_rc)"; head -3 "$BUILD_DIR/generated.err"
elif cmp -s "$BUILD_DIR/generated.txt" "$VECTORS"; then
    echo "  [current] $(grep -c '^addr ' "$VECTORS") addresses, $(grep -c '^phrase ' "$VECTORS") seeds: up to date"
else gate_fail "vectors.txt is not what generate.py prints"; diff "$VECTORS" "$BUILD_DIR/generated.txt" | head -5; fi

if [ $FAIL -eq 0 ]; then
    echo "PASS: bip39_24w_vectors_kat (24-word phrases, without and with a passphrase: core, bridge and wallet trees give the independent addresses for every profile, the wallet opened by the shared recipe; five RED arms fail only their cases; ASan+UBSan; vectors current)"
    exit 0
fi
echo "FAIL: bip39_24w_vectors_kat"
exit 1
