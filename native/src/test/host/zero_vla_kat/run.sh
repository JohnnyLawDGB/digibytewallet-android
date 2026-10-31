#!/usr/bin/env bash
# zero_vla_kat — no zero-length VLA (4.1.2 row 12, N9). See zero_vla_kat_main.c.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
FAIL=0
[ "$(grep -c ZERO_VLA_UNFIXED "$CORE_DIR/BRAddress.c")" -ge 3 ] && [ "$(grep -c ZERO_VLA_UNFIXED "$CORE_DIR/BRBIP39Mnemonic.c")" -ge 1 ] ||
    { echo "GATE FAILURE: a ZERO_VLA_UNFIXED seam is missing; the red arm would be inert"; exit 1; }
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -g -fsanitize=vla-bound,undefined -fno-sanitize-recover=all \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$SCRIPT_DIR/zero_vla_kat_main.c" \
        "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
build "$BUILD_DIR/red" -DZERO_VLA_UNFIXED || { echo "GATE FAILURE: red did not build"; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: green did not build"; exit 1; }
# The census (4.1.3 row 4, replacing the list of known sites): clang -Wvla names every variable-length array in the
# core; each one sizes itself with BR_VLA_LEN or is on vla_census_allow.txt with the reason its bound is positive.
grep -q '^#define BR_VLA_LEN(n) ((n) > 0 ? (n) : 1)$' "$CORE_DIR/BRInt.h" || { echo "GATE FAILURE: BR_VLA_LEN is missing from BRInt.h"; FAIL=1; }
ALLOW="$SCRIPT_DIR/vla_census_allow.txt"
CENSUS="$BUILD_DIR/census.txt"; : > "$CENSUS"
for f in $(cd "$CORE_DIR" && ls *.c | grep -vE '^test\.c$|Tests?\.c$'); do
    clang -fsyntax-only -Wno-everything -Wvla -include stdint.h -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" "$CORE_DIR/$f" 2>&1 |
        grep -E "^[^:]+\.c:[0-9]+:[0-9]+: warning: variable length array" | cut -d: -f1,2 >> "$CENSUS" || true
done
SEEN=0; USED="$BUILD_DIR/used.txt"; : > "$USED"
while IFS=: read -r path line; do
    f="$(basename "$path")"; text="$(sed -n "${line}p" "$CORE_DIR/$f" | sed 's/^[[:space:]]*//')"
    SEEN=$((SEEN + 1))
    case "$text" in *BR_VLA_LEN*) continue ;; esac
    if grep -qF "$f|$text|" "$ALLOW"; then echo "$f|$text|" >> "$USED"
    else echo "GATE FAILURE: $f:$line sizes a VLA without BR_VLA_LEN and is not on the allowlist: $text"; FAIL=1; fi
done < <(sort -u "$CENSUS")
[ "$SEEN" -ge 50 ] || { echo "GATE FAILURE: the census saw only $SEEN VLAs; clang -Wvla is not reporting (re-anchor)"; FAIL=1; }
while IFS= read -r entry; do
    case "$entry" in '#'*|'') continue ;; esac
    key="${entry%|*}|"
    grep -qF "$key" "$USED" || { echo "GATE FAILURE: stale allowlist entry (matches no VLA): $key"; FAIL=1; }
done < "$ALLOW"
echo "  [census] $SEEN VLAs in the core: each sized by BR_VLA_LEN or allowlisted with its reason"
for arm in pkh spk sig phrase verify wallet unconfirmed sign signinput walletsign; do
    OUT="$(timeout 30 "$BUILD_DIR/red" "$arm" 2>&1)"; RC=$?
    if [ "$RC" -ne 0 ] && <<<"$OUT" grep -q "variable length array bound evaluates to non-positive value 0"; then
        echo "  [red]   $arm: zero-length VLA reported, as required"
    else echo "GATE FAILURE: red arm $arm reported no zero-length VLA (rc=$RC)"; <<<"$OUT" tail -2; FAIL=1; fi
    OUT="$(timeout 30 "$BUILD_DIR/green" "$arm" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -q "runtime error"; then echo "  [green] $(<<<"$OUT" tail -1)"
    else echo "GATE FAILURE: green arm $arm (rc=$RC)"; <<<"$OUT" tail -2; FAIL=1; fi
done
[ "$FAIL" -eq 0 ] && { echo "PASS: zero_vla_kat (10 arms red-then-green under UBSan, whole-core VLA census)"; exit 0; }
echo "FAIL: zero_vla_kat"; exit 1
