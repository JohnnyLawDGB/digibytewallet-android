#!/usr/bin/env bash
#
# server_tx_import_kat — the server-transaction import and confirmation, moved from the JNI bridge into the core
# (BRServerTxImport.h), give the bridge's answers and leave the wallet exactly as the bridge does.
# See the header of server_tx_import_kat_main.c for what is compared.
#
#   GREEN  the bridge's jni_transaction.c and the core's BRServerTxImport.h, side by side: every scenario passes,
#          under AddressSanitizer and UndefinedBehaviorSanitizer.
#   RED    -DKAT_RED_KEEP_UNPROVABLE (core side only): the import keeps a tx whose height is unprovable.
#          Exactly import_unprovable and note_lowest must fail; every other scenario must pass.
#   RED    -DKAT_RED_NO_DUP_CHECK (core side only): the wallet's existing record is not looked up.
#          Exactly the scenarios with an existing record must fail; every other scenario must pass.
#   SEAMS  both sides reach BRPeerManagerApplyServerHeight and BRTransactionIsSigned through recorders in the _main.c
#          (-D renames on the bridge's and the core's unit only), so a scenario can force an answer the shipped
#          manager or parser no longer gives (TAKEN; an unsigned parse) and every call is compared.
#   RED    -DKAT_RED_NO_FREE_REFUSED (core side only, Linux only: needs LeakSanitizer): a tx the wallet refused is not
#          freed. Every scenario passes; the leak checker must report a leak, and the green arm must be leak-clean.
#   GATES  the bridge functions this suite compiles are the ones that ship (they are compiled, not copied); the
#          header is included twice in one unit and compiles clean under -Wall -Wextra -Werror.
set -uo pipefail   # NOT -e: a comparison arm's non-zero exit is captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HOST_JNI="$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

gate_fail() { echo "GATE FAILURE: $*"; exit 1; }

[ -f "$CORE_DIR/BRServerTxImport.h" ] || gate_fail "$CORE_DIR has no BRServerTxImport.h (core pin too old? set CORE_DIR)"
for fn in NativeBridge_registerRawTransaction NativeBridge_confirmTransaction NativeBridge_takeUnprovableServerHeight; do
    grep -q "^$fn(" "$BRIDGE_DIR/jni_transaction.c" || gate_fail "jni_transaction.c has no $fn"
done

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

SAN=(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g)
INC=(-I "$HOST_JNI" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include")

# The header on its own terms: clean under -Wall -Wextra -Werror, included twice.
printf '#include "BRServerTxImport.h"\n#include "BRServerTxImport.h"\nint main(void){BRServerTxUnprovableNote n={0};return (int)BRServerTxUnprovableNoteTake(&n);}\n' \
    > "$BUILD_DIR/hdr.c"
# The other core headers are not -Wextra clean, so they come in as system headers; this one is checked from a copy.
mkdir -p "$BUILD_DIR/hdr" && cp "$CORE_DIR/BRServerTxImport.h" "$BUILD_DIR/hdr/"
clang -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -include stdint.h -I "$BUILD_DIR/hdr" -isystem "$CORE_DIR" \
    -fsyntax-only "$BUILD_DIR/hdr.c" \
    || gate_fail "BRServerTxImport.h does not compile clean under -Wall -Wextra -Werror"

# $1 out, rest: extra flags for the core side.
build() {
    local out="$1"; shift
    local d="$BUILD_DIR/$(basename "$out").o"
    mkdir -p "$d"
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" \
        -DBRPeerConnectStatus=__wrap_BRPeerConnectStatus \
        -DBRPeerIsSocketOpen=__wrap_BRPeerIsSocketOpen \
        -DBRPeerSendGetCFilters=__wrap_BRPeerSendGetCFilters \
        -DBRPeerSendGetdataBlocks=__wrap_BRPeerSendGetdataBlocks \
        -c "$SCRIPT_DIR/server_tx_import_kat_main.c" -o "$d/main.o" || return 1
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -DBRPeerManagerApplyServerHeight=kat_apply -DBRTransactionIsSigned=kat_is_signed "$@" \
        -c "$SCRIPT_DIR/server_tx_import_kat_core.c" -o "$d/core.o" || return 1
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" -DBRPeerManagerApplyServerHeight=kat_apply -DBRTransactionIsSigned=kat_is_signed \
        -c "$BRIDGE_DIR/jni_transaction.c" -o "$d/bridge.o" || return 1
    clang -w -include stdint.h "${SAN[@]}" "${INC[@]}" \
        "$d/main.o" "$d/core.o" "$d/bridge.o" \
        "$CORE_DIR/BRPeer.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
# Leaks are checked only in the leak pass below. Every other arm runs with the leak checker off, whatever the caller's
# environment sets (CI exports LSAN_OPTIONS=detect_leaks=1): KAT_RED_NO_DUP_CHECK leaks by construction (a record it
# re-registers is neither kept nor freed), and a leak there is not the failure it must show. Every other sanitizer
# report still counts as a fault in every arm.
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0"
export LSAN_OPTIONS="detect_leaks=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
FAIL=0

run_arm() {   # $1 bin -> sets OUT, RC
    OUT="$(timeout 120 "$1" 2>&1)"; RC=$?
    [ -n "${KAT_LOG_DIR:-}" ] && printf '%s\n' "$OUT" > "$KAT_LOG_DIR/$(basename "$1").log"
}

ALL_SCENARIOS=""
# ── GREEN ────────────────────────────────────────────────────────────────────────────────────────────────────────
build "$BUILD_DIR/green" || gate_fail "the green arm did not build"
run_arm "$BUILD_DIR/green"
if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -Eq "$SAN_RE" && <<<"$OUT" grep -q "^PASS: server_tx_import_kat" \
   && ! <<<"$OUT" grep -q "^SCENARIO-FAIL"; then
    N="$(<<<"$OUT" grep -c '^SCENARIO-PASS')"
    echo "  [green] $N scenarios: the bridge and the core agree on every one"
    ALL_SCENARIOS="$(<<<"$OUT" sed -n 's/^SCENARIO-PASS: //p' | sort)"
else
    echo "GATE FAILURE: the green arm (rc=$RC)"; <<<"$OUT" grep -E "FAIL|$SAN_RE" | head -20; FAIL=1
fi

# red <macro> <space-separated scenarios that must fail>
red() {
    local macro="$1" want
    want="$(tr ' ' '\n' <<<"$2" | sed '/^$/d' | sort)"
    build "$BUILD_DIR/red_$macro" "-D$macro" || { echo "GATE FAILURE: the $macro arm did not build"; FAIL=1; return; }
    run_arm "$BUILD_DIR/red_$macro"
    local failed
    failed="$(<<<"$OUT" sed -n 's/^SCENARIO-FAIL: //p' | sort)"
    if <<<"$OUT" grep -Eq "$SAN_RE"; then
        echo "GATE FAILURE: the $macro arm faulted (a fault is not the failure it must show)"; <<<"$OUT" grep -Em3 "$SAN_RE"; FAIL=1
    elif <<<"$OUT" grep -q "FAIL: setup"; then
        echo "GATE FAILURE: the $macro arm failed in setup"; <<<"$OUT" grep "FAIL: setup" | head -3; FAIL=1
    elif [ "$RC" -ne 1 ] || [ "$failed" != "$want" ]; then
        echo "GATE FAILURE: the $macro arm (rc=$RC) failed [$(echo $failed)], required exactly [$(echo $want)]"; FAIL=1
    else
        echo "  [red $macro] failed exactly: $(echo $failed)"
        <<<"$OUT" grep -E '^   FAIL:' | sort -u | head -6 | sed 's/^   /      /'
    fi
}

red KAT_RED_KEEP_UNPROVABLE "import_unprovable note_lowest"
red KAT_RED_NO_DUP_CHECK "import_not_wallet_again dup_confirmed dup_confirmed_deep dup_pending_rechecking dup_pending_unprovable dup_pending_no_manager dup_pending_scripted_taken confirm_rechecking confirm_above_tip confirm_unprovable confirm_foreign_pending confirm_timestamp_wide confirm_scripted_taken"

# ── LEAKS (where LeakSanitizer exists: Linux CI; not on macOS) ─────────────────────────────────────────────────────
# Every path frees what it parsed or hands it to the wallet: the bridge and the core alike.
printf '#include <stdlib.h>\nint main(void){return 0;}\n' > "$BUILD_DIR/probe.c"
if clang -fsanitize=address "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe" 2>/dev/null &&
   ASAN_OPTIONS="detect_leaks=1 abort_on_error=0" LSAN_OPTIONS="detect_leaks=1" "$BUILD_DIR/probe" >/dev/null 2>&1; then
    OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=1" LSAN_OPTIONS="detect_leaks=1" timeout 120 "$BUILD_DIR/green" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -Eq "$SAN_RE"; then
        echo "  [leaks] the green arm is leak-clean"
    else
        echo "GATE FAILURE: the green arm under the leak checker (rc=$RC)"; <<<"$OUT" grep -E -A8 "$SAN_RE" | head -24; FAIL=1
    fi
    build "$BUILD_DIR/red_KAT_RED_NO_FREE_REFUSED" -DKAT_RED_NO_FREE_REFUSED ||
        { echo "GATE FAILURE: the KAT_RED_NO_FREE_REFUSED arm did not build"; FAIL=1; }
    if [ -x "$BUILD_DIR/red_KAT_RED_NO_FREE_REFUSED" ]; then
        OUT="$(ASAN_OPTIONS="halt_on_error=1 abort_on_error=0 detect_leaks=1" LSAN_OPTIONS="detect_leaks=1" timeout 120 "$BUILD_DIR/red_KAT_RED_NO_FREE_REFUSED" 2>&1)"; RC=$?
        if [ "$RC" -ne 0 ] && <<<"$OUT" grep -q "ERROR: LeakSanitizer" \
           && ! <<<"$OUT" grep -Eq "ERROR: AddressSanitizer|runtime error:" \
           && <<<"$OUT" grep -q "^PASS: server_tx_import_kat" && ! <<<"$OUT" grep -q "^SCENARIO-FAIL"; then
            echo "  [red KAT_RED_NO_FREE_REFUSED] every scenario passes and the leak checker reports the unfreed tx"
        else
            echo "GATE FAILURE: the KAT_RED_NO_FREE_REFUSED arm (rc=$RC) did not show exactly a leak"
            <<<"$OUT" grep -E "SCENARIO-FAIL|$SAN_RE" | head -8; FAIL=1
        fi
    fi
else
    echo "  [leaks] skipped: LeakSanitizer is not available here (the green leak pass and KAT_RED_NO_FREE_REFUSED run on Linux)"
fi

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: server_tx_import_kat (bridge and core agree on every scenario; two comparison arms red on exactly theirs; ASan+UBSan)"
    exit 0
fi
echo "FAIL: server_tx_import_kat"
exit 1
