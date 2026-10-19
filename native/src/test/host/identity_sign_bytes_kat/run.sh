#!/usr/bin/env bash
# Host KAT: a message is signed over the bytes the app sends for it (AND-009: a Hub post is signed over
# exactly what is sent; the same holds for a Digi-ID login).
#
# The app sends a message as the standard UTF-8 of its text. JNI's GetStringUTFChars gives native code
# modified UTF-8 instead (an emoji as two 3-byte halves, U+0000 as C0 80), so the bridge reads the message's
# UTF-16 code units and writes their UTF-8 itself (jni_wallet_sign.c, message_utf8). The per-site identity's
# derivation input, the callback URL, is read as before: it is not a message, and reading it differently
# could move a site to another identity.
#
# The _main.c compiles the bridge's OWN jni_wallet_sign.c and jni_wallet.c (with slip13.c and the core),
# over the stand-ins in host_jni/ for <jni.h> and <android/log.h>; its env's strings are UTF-16 and its
# GetStringUTFChars writes modified UTF-8 as the JNI specification defines it. Each signature is checked by
# recovering its key over the message format of the expected bytes.
#
# ==== RED-BEFORE-GREEN =======================================================
# PRESENCE of the macro selects the comparison arm (the shipped arm is built with no -D at all):
#   -DSIGN_MESSAGE_BYTES_UNFIXED   the message is read with GetStringUTFChars, as the bridge did before.
#   RED-THEN-GREEN (assertion)  emoji, nul, lone, identity -- the comparison arm signs other bytes than are
#                               sent, and fails exactly the "signature is over" check.
#   GUARD (every arm)           ascii, bmp -- modified UTF-8 is UTF-8 for these, so no arm can be red; the two
#                               arms' signatures are also held byte-identical (no login's signature changes).
# A comparison arm that passes a red scenario, or stops for any other reason, proves nothing and fails the
# gate. Every arm runs under a time limit. All arms are built 64- and 32-bit and must give the same verdicts;
# then every scenario runs on the 64-bit shipped arm under the leak checker and must be clean.
set -uo pipefail   # NOT -e: the comparison arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

gate_fail() { echo "GATE FAILED: $*"; exit 1; }

# Seam self-checks: a -D that names nothing builds the shipped path, and the comparison arm would (wrongly)
# be the shipped code. The seam selects read_message/release_message once; both entry points read their
# message through it, and the message is read with GetStringUTFChars nowhere else.
SIGN_C="$BRIDGE_DIR/jni_wallet_sign.c"
[ "$(grep -c "#ifdef SIGN_MESSAGE_BYTES_UNFIXED" "$SIGN_C")" -eq 1 ] ||
    gate_fail "SIGN_MESSAGE_BYTES_UNFIXED does not select exactly one block in jni_wallet_sign.c -- the -D would select nothing."
[ "$(grep -c "read_message(env, message, &msgLen)" "$SIGN_C")" -eq 2 ] ||
    gate_fail "signMessage and signIdentityMessage must each read their message through read_message."
[ "$(grep -c "GetStringUTFChars(env, message," "$SIGN_C")" -eq 1 ] ||
    gate_fail "a message is read with GetStringUTFChars outside the comparison arm's read_message."

# $1 out, rest: extra flags. KAT_BITS=32 builds -m32 (needs gcc-multilib).
build() {
    local out="$1"; shift
    local m=()
    [ "${KAT_BITS:-64}" = "32" ] && m=(-m32)
    clang -w -include stdint.h ${m[@]+"${m[@]}"} "$@" \
        -fsanitize=address -fno-omit-frame-pointer -g \
        -I "$SCRIPT_DIR/host_jni" \
        -I "$BRIDGE_DIR" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/identity_sign_bytes_kat_main.c" \
        "$BRIDGE_DIR/jni_wallet_sign.c" \
        "$BRIDGE_DIR/jni_transaction_persist.c" \
        "$BRIDGE_DIR/jni_wallet.c" \
        "$BRIDGE_DIR/slip13.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
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

echo "=== building arms (the bridge and the wallet under ASan: comparison + shipped, 64- and 32-bit) ==="
for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    KAT_BITS=$bits build "$BUILD_DIR/unfixed$sfx" -DSIGN_MESSAGE_BYTES_UNFIXED ||
        gate_fail "the ${bits}-bit comparison arm did not build (gcc-multilib for 32-bit?)"
    KAT_BITS=$bits build "$BUILD_DIR/shipped$sfx" || gate_fail "the ${bits}-bit shipped arm did not build"
done

# One scenario in one arm, under a time limit; its output goes to a file named for it.
bounded() {   # $1 binary, $2 scenario, $3 output file, $4 leak checking (0/1)
    ASAN_OPTIONS="detect_leaks=$4:symbolize=0:abort_on_error=0" timeout 120 "$1" "$2" > "$3" 2>&1
}

RED="emoji nul lone identity"
GUARD="ascii bmp"
fails=0
for bits in 64 32; do
    sfx=""; [ "$bits" = "32" ] && sfx="32"
    for s in $RED; do
        bounded "$BUILD_DIR/shipped$sfx" "$s" "$BUILD_DIR/$s.shipped$sfx.txt" 0; rc=$?
        if [ $rc -eq 0 ]; then echo "  [$bits] $s shipped: GREEN"
        else echo "  [$bits] $s shipped: FAILED (exit $rc)"; sed 's/^/      | /' "$BUILD_DIR/$s.shipped$sfx.txt"; fails=$((fails + 1)); fi

        bounded "$BUILD_DIR/unfixed$sfx" "$s" "$BUILD_DIR/$s.unfixed$sfx.txt" 0; rc=$?
        red_lines="$(grep -c "FAIL: the .*signature is over" "$BUILD_DIR/$s.unfixed$sfx.txt")"
        all_fails="$(grep -c "FAIL:" "$BUILD_DIR/$s.unfixed$sfx.txt")"
        other_fails=$((all_fails - red_lines))
        if [ $rc -eq 1 ] && [ "$red_lines" -eq 1 ] && [ "$other_fails" -eq 0 ]; then
            echo "  [$bits] $s comparison arm: RED (signs other bytes than are sent)"
        else
            echo "  [$bits] $s comparison arm: NOT RED as expected (exit $rc, $red_lines signature check(s) red, $other_fails other)"
            sed 's/^/      | /' "$BUILD_DIR/$s.unfixed$sfx.txt"; fails=$((fails + 1))
        fi
    done
    for s in $GUARD; do
        for arm in shipped unfixed; do
            bounded "$BUILD_DIR/$arm$sfx" "$s" "$BUILD_DIR/$s.$arm$sfx.txt" 0; rc=$?
            if [ $rc -eq 0 ]; then echo "  [$bits] $s $arm: GREEN (GUARD)"
            else echo "  [$bits] $s $arm: FAILED (exit $rc)"; sed 's/^/      | /' "$BUILD_DIR/$s.$arm$sfx.txt"; fails=$((fails + 1)); fi
        done
        a="$(grep "^SIGNATURE " "$BUILD_DIR/$s.shipped$sfx.txt")"
        b="$(grep "^SIGNATURE " "$BUILD_DIR/$s.unfixed$sfx.txt")"
        if [ -n "$a" ] && [ "$a" = "$b" ]; then echo "  [$bits] $s: the two arms' signatures are byte-identical"
        else echo "  [$bits] $s: the arms' signatures differ: '$a' / '$b'"; fails=$((fails + 1)); fi
    done
done

# The two widths agree on the signature of the GUARD messages (deterministic signing).
for s in $GUARD; do
    a="$(grep "^SIGNATURE " "$BUILD_DIR/$s.shipped.txt")"; b="$(grep "^SIGNATURE " "$BUILD_DIR/$s.shipped32.txt")"
    if [ -n "$a" ] && [ "$a" = "$b" ]; then echo "  $s: 64- and 32-bit sign the same"
    else echo "  $s: 64- and 32-bit signatures differ"; fails=$((fails + 1)); fi
done

echo "=== the shipped arm under the leak checker (64-bit) ==="
for s in $RED $GUARD; do
    bounded "$BUILD_DIR/shipped" "$s" "$BUILD_DIR/$s.leaks.txt" 1; rc=$?
    if [ $rc -eq 0 ] && ! grep -q "LeakSanitizer" "$BUILD_DIR/$s.leaks.txt"; then echo "  $s: clean"
    else echo "  $s: NOT clean (exit $rc)"; sed 's/^/      | /' "$BUILD_DIR/$s.leaks.txt"; fails=$((fails + 1)); fi
done

if [ "$fails" -ne 0 ]; then echo "identity_sign_bytes_kat: $fails failure(s)"; exit 1; fi
echo "identity_sign_bytes_kat: all arms as expected"
