#!/usr/bin/env bash
#
# legacy_sweep_kat — the Recover-funds sweep signer, moved from the JNI bridge into the core (BRLegacySweep.h), signs
# byte for byte what the bridge signs. See the header of legacy_sweep_kat_main.c for the scenarios.
#
#   GREEN  the bridge's jni_derive.c (NativeBridge_buildAndSignLegacySweep) and the core's BRLegacySweep.h side by side:
#          every scenario passes under AddressSanitizer and UndefinedBehaviorSanitizer (64-bit).
#   RED    one arm per fee rule and one for the key wipe (BR_LEGACY_SWEEP_RED_*=1, on the core's unit only). Each must
#          fail exactly its scenarios and pass every other one, with no sanitizer report.
#   GATES  the bridge file compiled is the shipping one (copied byte for byte beside CORE_DIR, so its
#          "../digibytewallet-core/" includes resolve to the core under test); the header is included twice in one unit
#          and compiles clean under -Wall -Wextra -Wpedantic -Werror.
# Leaks: the green arm runs under LeakSanitizer where it exists (Linux CI); every red arm runs with it off (a red arm
# is judged by its scenarios, and none is built to leak).
# KAT_FUZZ=<n> sets the fuzzed sweeps of the green arm (default 2000), KAT_FUZZ_RED those of each red arm (default 600).
set -uo pipefail   # NOT -e: a comparison arm's non-zero exit is captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
CORE_DIR="$(cd "$CORE_DIR" 2>/dev/null && pwd)" || { echo "GATE FAILURE: no core directory"; exit 1; }
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

[ -f "$CORE_DIR/BRLegacySweep.h" ] || gate_fail "$CORE_DIR has no BRLegacySweep.h (core pin too old? set CORE_DIR)"
grep -q "^NativeBridge_buildAndSignLegacySweep(" "$BRIDGE_DIR/jni_derive.c" || gate_fail "jni_derive.c has no NativeBridge_buildAndSignLegacySweep"

# The bridge beside the core under test: jni_derive.c includes "../digibytewallet-core/...", which must be CORE_DIR.
mkdir -p "$BUILD_DIR/jni"
cp -R "$BRIDGE_DIR" "$BUILD_DIR/jni/bridge"
ln -s "$CORE_DIR" "$BUILD_DIR/jni/digibytewallet-core"
cmp -s "$BRIDGE_DIR/jni_derive.c" "$BUILD_DIR/jni/bridge/jni_derive.c" || gate_fail "the copied jni_derive.c is not the shipping file"

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

SAN=(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g)
INC=(-I "$HOST_JNI" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")

# The header on its own terms: clean under -Wall -Wextra -Wpedantic -Werror, included twice. The other core headers are
# not -Wextra clean, so they come in as system headers; this one is checked from a copy.
mkdir -p "$BUILD_DIR/hdr" && cp "$CORE_DIR/BRLegacySweep.h" "$BUILD_DIR/hdr/"
printf '#include "BRLegacySweep.h"\n#include "BRLegacySweep.h"\nint main(void){BRTransaction *t; uint8_t s[64]={0}; BRLegacySweepInput i={0};\nreturn (int)BRLegacySweepBuildAndSign(&t,s,64,"Bitcoin seed",NULL,0,&i,1,"x",0,NULL);}\n' > "$BUILD_DIR/hdr.c"
for std in c99 c11 c17; do
    clang -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" \
        -isystem "$CORE_DIR/secp256k1/include" -fsyntax-only "$BUILD_DIR/hdr.c" \
        || gate_fail "BRLegacySweep.h does not compile clean under -std=$std -Wall -Wextra -Wpedantic -Werror"
done

LIBS=("$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c"
      "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c"
      "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c"
      "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
      "${SHA3_SRCS[@]}")

mkdir -p "$BUILD_DIR/lib"
LIBO=()
for src in "${LIBS[@]}"; do
    o="$BUILD_DIR/lib/${#LIBO[@]}_$(basename "$src" .c).o"   # numbered: crypto/ and crypto/sha3/ share basenames
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -c "$src" -o "$o" || gate_fail "$src did not compile"
    LIBO+=("$o")
done
clang -w -include stdint.h -include stdio.h "${SAN[@]}" "${INC[@]}" -I "$BUILD_DIR/jni/bridge" \
    -c "$BUILD_DIR/jni/bridge/jni_derive.c" -o "$BUILD_DIR/bridge.o" || gate_fail "jni_derive.c did not compile"
clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -c "$SCRIPT_DIR/legacy_sweep_kat_main.c" -o "$BUILD_DIR/main.o" \
    || gate_fail "legacy_sweep_kat_main.c did not compile"

# $1 out, rest: extra flags for the core side.
build() {
    local out="$1"; shift
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -Dcalloc=kat_calloc -Dmalloc=kat_malloc -Dfree=kat_free "$@" \
        -c "$SCRIPT_DIR/legacy_sweep_kat_core.c" -o "$out.core.o" || return 1
    clang "${SAN[@]}" "$BUILD_DIR/main.o" "$out.core.o" "$BUILD_DIR/bridge.o" "${LIBO[@]}" -lm -lpthread -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 detect_stack_use_after_return=0"
export LSAN_OPTIONS="detect_leaks=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
FAIL=0

run_arm() {   # $1 bin -> sets OUT, RC
    OUT="$(KAT_FUZZ="${2:-${KAT_FUZZ:-2000}}" timeout 600 "$1" 2>&1)"; RC=$?
    [ -n "${KAT_LOG_DIR:-}" ] && printf '%s\n' "$OUT" > "$KAT_LOG_DIR/$(basename "$1").log"
}

# ── GREEN ──────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || gate_fail "the green arm did not build"
run_arm "$BUILD_DIR/green"
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "^PASS: legacy_sweep_kat" \
   && ! <<<"$OUT" grep -q "^SCENARIO-FAIL"; then
    N="$(<<<"$OUT" grep -c '^SCENARIO-PASS')"
    echo "  [green] $N scenarios: the bridge and the core sign the same bytes on every one"
    <<<"$OUT" grep -E '^   (fuzz|stack_probe):' | sed 's/^   /      /'
else
    echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|$SAN_RE" | head -20; FAIL=1
fi

# red <macro> <space-separated scenarios that must fail>
red() {
    local macro="$1" want
    want="$(tr ' ' '\n' <<<"$2" | sed '/^$/d' | sort)"
    build "$BUILD_DIR/red_$macro" "-D$macro=1" || { echo "GATE FAILURE: the $macro arm did not build"; FAIL=1; return; }
    run_arm "$BUILD_DIR/red_$macro" "${KAT_FUZZ_RED:-600}"
    local failed
    failed="$(<<<"$OUT" sed -n 's/^SCENARIO-FAIL: //p' | sort)"
    if <<<"$OUT" grep -Eq "$SAN_RE"; then
        echo "GATE FAILURE: the $macro arm faulted (a fault is not the failure it must show)"; <<<"$OUT" grep -Em3 "$SAN_RE"; FAIL=1
    elif [ "$RC" -ne 1 ] || [ "$failed" != "$want" ]; then
        echo "GATE FAILURE: the $macro arm (rc=$RC) failed [$(echo $failed)], required exactly [$(echo $want)]"; FAIL=1
    else
        echo "  [red $macro] failed exactly: $(echo $failed)"
    fi
}

red BR_LEGACY_SWEEP_RED_SIZE \
    "boundary_dust_above boundary_dust_at boundary_dust_below boundary_ratio_above boundary_ratio_at boundary_ratio_single_exempt boundary_size fee_rate_large fuzz gate_refusal_is_bridge_side pinned_amount_guard_normal pinned_amount_guard_over_reported pinned_amount_guard_under_reported pinned_bip49_p2sh_p2wpkh pinned_foreign_taproot pinned_segwit_p2wpkh pinned_signedtx_p2pkh proof refuse_destination refuse_short_seed_signs refuse_wraparound"
red BR_LEGACY_SWEEP_RED_FLOOR \
    "boundary_floor_dust boundary_floor_edge boundary_floor_signs boundary_floor_zero_rate boundary_ratio_floor fuzz"
red BR_LEGACY_SWEEP_RED_RATIO \
    "boundary_ratio_at boundary_ratio_below boundary_ratio_floor fuzz pinned_amount_guard_under_reported refuse_wraparound"
red BR_LEGACY_SWEEP_RED_RATIO_STRICT \
    "boundary_ratio_at boundary_ratio_floor fuzz"
red BR_LEGACY_SWEEP_RED_RATIO_SINGLE \
    "boundary_dust_above boundary_dust_at boundary_dust_below boundary_floor_dust boundary_floor_signs boundary_floor_zero_rate boundary_ratio_single_exempt fee_rate_large fuzz pinned_foreign_taproot"
red BR_LEGACY_SWEEP_RED_DUST \
    "boundary_dust_at boundary_floor_dust fuzz"
red BR_LEGACY_SWEEP_RED_NO_WIPE "keys_wiped"

# ── LEAKS (where LeakSanitizer exists: Linux CI; not on macOS) ───────────────────────────────────────────────────────
printf 'int main(void){return 0;}\n' > "$BUILD_DIR/probe.c"
if clang -fsanitize=address "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe" 2>/dev/null &&
   ( ASAN_OPTIONS="detect_leaks=1" LSAN_OPTIONS="detect_leaks=1" "$BUILD_DIR/probe" ) >/dev/null 2>&1; then
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=1 detect_stack_use_after_return=0" LSAN_OPTIONS="detect_leaks=1" \
           KAT_FUZZ=300 timeout 600 "$BUILD_DIR/green" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -Eq "$SAN_RE"; then
        echo "  [leaks] the green arm is leak-clean"
    else
        echo "GATE FAILURE: the green arm under the leak checker (rc=$RC)"; <<<"$OUT" grep -E -A8 "$SAN_RE" | head -24; FAIL=1
    fi
else
    echo "  [leaks] skipped: LeakSanitizer is not available here (the green leak pass runs on Linux)"
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: legacy_sweep_kat (bridge and core sign the same bytes on every scenario; seven comparison arms red on exactly theirs; ASan+UBSan)"
    exit 0
fi
echo "FAIL: legacy_sweep_kat"
exit 1
