#!/usr/bin/env bash
# Host KAT runner for the core's BRDigiDollarTransferLayout.h: the consensus-significant shape of a
# DigiDollar transfer -- version marker, OP_RETURN bytes, recipient script, cent bounds, fee floor. The
# header moved from Android's bridge (native/src/main/jni/bridge/digidollar_transfer_layout.h);
# digidollar_transfer_kat still runs that copy. Two mains here, one process each:
#   digidollar_transfer_layout_core_kat_main.c   digidollar_transfer_kat's vectors, value for value,
#                                                against a real mainnet transfer
#   digidollar_transfer_layout_builder_main.c    new: the header and the wallet's own builder
#                                                (BRWalletCreateDigiDollarTransfer, whose copies of the
#                                                same values are private to BRWallet.c) agree, by what
#                                                the builder builds and refuses
# Both in a 64-bit build (ASan + UBSan) and a 32-bit build (-m32, ASan), linked against the core: every
# check passes, the number of checks is the number CHECKS gives, and there is no sanitizer report. The
# mains run with detect_leaks=0; then once more on the 64-bit build under LeakSanitizer where the platform
# has one (said so, and skipped, where it does not -- Apple clang on macOS). Every run is time-limited.
#
# The header has no test seam, as the bridge's had none, so the comparison arm is made here: MUTANTS
# names one changed value each (a copy of the header, edited, placed ahead of the core on the include
# path), and each main must then fail exactly the number of checks its entry gives (64-bit build).
#
# Gates first: the header defines its three functions and reads no build-time macro at all (Swift imports
# it with flags of its own).
# Then:
#   PORTABILITY GATE  the header includes only libc and core headers, reads no clock, does no I/O and
#                     allocates nothing, and builds clean under -Wall -Wextra -Wpedantic -Werror at
#                     c99/c11/c17 (two TUs, the header included twice in the second); for
#                     iphoneos/iphonesimulator, and through Swift's C importer (swiftc -import-objc-header)
#                     on macOS -- its four constants included, each at the type Swift gives a literal
#                     macro -- where an Xcode SDK / swiftc is present (skipped, and said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the mutants' nonzero exits are inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
KAT=digidollar_transfer_layout_core_kat
NAME=BRDigiDollarTransferLayout
HEADER="$CORE_DIR/$NAME.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
LIMIT_S=60

# <main>:<checks it makes>
CHECKS=(
    digidollar_transfer_layout_core_kat_main.c:18
    digidollar_transfer_layout_builder_main.c:18
)
# <name>|<sed expression over the header>|<checks the vectors main then fails>|<checks the builder main then fails>
MUTANTS=(
    'version|s/0x02000770u/0x02000771u/|2|1'
    'op_return_type|s/out\[ol++\] = 0x01; out\[ol++\] = 0x02;/out[ol++] = 0x01; out[ol++] = 0x03;/|2|1'
    'op_return_change|s/if (ddChangeCents > 0) {/if (ddChangeCents > 100) {/|1|0'
    'recipient_opcode|s/out\[0\] = 0x51;/out[0] = 0x00;/|1|1'
    'min_cents|s/MIN_OUTPUT_CENTS 100LL/MIN_OUTPUT_CENTS 99LL/|1|1'
    'max_cents|s/MAX_OUTPUT_CENTS 10000000LL/MAX_OUTPUT_CENTS 10000001LL/|1|1'
    'fee_floor|s/MIN_FEE 10000000ULL/MIN_FEE 1000000ULL/|1|1'
)

# ---- the header is there, and has no seam --------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the DigiDollar transfer layout moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRDigiDollarRecipientScript BRDigiDollarOpReturnScript BRDigiDollarCentsInRange; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done

FAIL=0

shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")

objs() {   # <dir> <extra flags...>: compile UNITS into <dir>; fails on a build error
    local dir="$1"; shift
    mkdir -p "$dir"
    local u
    for u in "${UNITS[@]}"; do
        "$CC" -w -std=c99 -include stdint.h "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
            -c "$u" -o "$dir/$(basename "$(dirname "$u")")_$(basename "${u%.c}").o" || return 1
    done
}

# ---- PORTABILITY GATE ----------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | LC_ALL=C sort -u | tr '\n' ' ')"
want='"BRDigiDollar.h" <stddef.h> <stdint.h> <string.h> '
if [ "$includes" != "$want" ]; then
    echo "PORTABILITY GATE FAILED: the header includes $includes(want $want)"; FAIL=1
fi
# Every conditional names the include guard or C++: no build-time macro selects code.
conds="$(grep -E '^[[:space:]]*#[[:space:]]*(if|ifdef|ifndef|elif)([^A-Za-z0-9_]|$)' <<<"$CODE" | sed -E 's/^[[:space:]]*#[[:space:]]*(ifdef|ifndef|elif|if)//; s/[^A-Za-z0-9_]+/ /g; s/^ +//; s/ +$//' | LC_ALL=C sort -u | tr '\n' ' ')"
want_conds="${NAME}_h __cplusplus "
if [ "$conds" != "$want_conds" ]; then
    echo "PORTABILITY GATE FAILED: the header's conditionals read $conds(want $want_conds)"; FAIL=1
fi
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: the header calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRDigiDollarTransferLayout.h"
int strict_tu1(void);
int strict_tu1(void) { uint8_t key[32] = { 0 }, spk[34];
    return BRDigiDollarRecipientScript(key, spk) == 34 && spk[0] == 0x51 && spk[1] == 0x20; }
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRDigiDollarTransferLayout.h"
#include "BRDigiDollarTransferLayout.h"
int strict_tu1(void);
int main(void) { uint8_t marker[32];
    return (BRDigiDollarOpReturnScript(100, 0, marker, sizeof(marker)) == 8 &&
            BRDigiDollarCentsInRange(BR_DIGIDOLLAR_MIN_OUTPUT_CENTS) &&
            ! BRDigiDollarCentsInRange(BR_DIGIDOLLAR_MAX_OUTPUT_CENTS + 1) &&
            BR_DIGIDOLLAR_MIN_FEE == 10000000ULL && BR_DIGIDOLLAR_TX_VERSION_TRANSFER == 0x02000770u &&
            strict_tu1()) ? 0 : 1; }
EOF
strict_ok=1
if ! objs "$BUILD_DIR/strict_objs" -O0; then
    echo "PORTABILITY GATE FAILED: the core units did not compile"; FAIL=1; strict_ok=0
else
    for std in c99 c11 c17; do
        if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
                "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
           ! "$BUILD_DIR/strict_$std"; then
            echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
        fi
    done
fi
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRDigiDollarTransferLayout.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let version: UInt32 = BR_DIGIDOLLAR_TX_VERSION_TRANSFER
let floor: UInt64 = BR_DIGIDOLLAR_MIN_FEE
let least: Int64 = BR_DIGIDOLLAR_MIN_OUTPUT_CENTS
let most: Int64 = BR_DIGIDOLLAR_MAX_OUTPUT_CENTS
let key = [UInt8](repeating: 1, count: 32)
var spk = [UInt8](repeating: 0, count: 34)
let spkLen: Int = BRDigiDollarRecipientScript(key, &spk)
var marker = [UInt8](repeating: 0, count: 32)
let markerLen: Int = BRDigiDollarOpReturnScript(least, 0, &marker, marker.count)
let inRange: Int32 = BRDigiDollarCentsInRange(most)
print(version, floor, least, most, spkLen, markerLen, inRange)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    mkdir -p "$BUILD_DIR/swift_inc/crypto"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"; cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" -Xcc -I"$CORE_DIR/secp256k1/include" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && [ $FAIL -eq 0 ] && echo "portability gate: libc + core headers, no clock/I/O/allocation, no build-time macro; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
SAN64=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
SAN32=(-m32 -g -fsanitize=address -fno-omit-frame-pointer)
build() {   # <out> <64|32> <main file> <flags...>
    local out="$1" bits="$2" main="$3"; shift 3
    local san=("${SAN64[@]}"); [ "$bits" = 32 ] && san=("${SAN32[@]}")
    "$CC" -w -std=c99 -include stdint.h "${san[@]}" "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/$main" "$BUILD_DIR"/san_objs"$bits"/*.o -lm -lpthread -o "$out"
}

# bounded <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit. The watchdog
# leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was stopped (the race fixed in
# base58_input_bound_kat, #211).
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    # The watchdog inherits this script's EXIT trap; a plain kill would run it and remove $BUILD_DIR under
    # the other runs. Drop the trap inside; stop it with a trap-free signal.
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

show() { sed -n "1,${2:-8}p" "$1" | sed 's/^/      /'; }   # first lines of a file, indented

for bits in 64 32; do
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    san=("${SAN64[@]}"); [ "$bits" = 32 ] && san=("${SAN32[@]}")
    if ! objs "$BUILD_DIR/san_objs$bits" "${san[@]}" 2>"$BUILD_DIR/objs$bits.err"; then
        echo "GATE FAILURE: the core units did not compile for the ${bits}-bit build (a build error is not evidence)."
        show "$BUILD_DIR/objs$bits.err" 3; FAIL=1; continue
    fi
    n=0
    for entry in "${CHECKS[@]}"; do
        main="${entry%%:*}"; want="${entry##*:}"; n=$((n + 1)); bin="$BUILD_DIR/green$bits.$n"; out="$bin.out"
        if ! build "$bin" "$bits" "$main" 2>"$BUILD_DIR/build$bits.err"; then
            echo "GATE FAILURE: ${bits}-bit $main did not compile (a build error is not evidence)."
            show "$BUILD_DIR/build$bits.err" 3; FAIL=1; continue
        fi
        bounded "$out" "$bin"; rc=$?
        got="$(grep -c '^  ok   ' "$out")"
        if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" "$out" && grep -q '^PASSED (0 failures)$' "$out" && [ "$got" = "$want" ]; then
            echo "  [green$bits $main] passed ($got checks)"
        else
            echo "  [green$bits $main] GATE FAILURE (rc=$rc, $got of $want checks passed)"
            grep -E '^  FAIL|runtime error|ERROR:' "$out" | sed -n '1,8p' | sed 's/^/      /'; FAIL=1
        fi
    done

    [ "$bits" = 64 ] || continue
    echo "--- MUTANTS: one value of the header changed, each main fails exactly its checks ---"
    for mutant in "${MUTANTS[@]}"; do
        IFS='|' read -r name expr want_vec want_bld <<<"$mutant"
        mdir="$BUILD_DIR/mutant_$name"; mkdir -p "$mdir"
        sed -e "$expr" "$HEADER" > "$mdir/$NAME.h"
        if cmp -s "$HEADER" "$mdir/$NAME.h"; then
            echo "  [mutant $name] GATE FAILURE: the edit changed nothing; the header no longer reads as it did"; FAIL=1; continue
        fi
        n=0
        for entry in "${CHECKS[@]}"; do
            main="${entry%%:*}"; total="${entry##*:}"; n=$((n + 1)); bin="$mdir/red.$n"; out="$bin.out"
            want="$want_vec"; [ $n -eq 2 ] && want="$want_bld"
            if ! build "$bin" 64 "$main" -I "$mdir" 2>"$BUILD_DIR/build64.err"; then
                echo "  [mutant $name, $main] GATE FAILURE: did not compile (a build error is not evidence)."
                show "$BUILD_DIR/build64.err" 3; FAIL=1; continue
            fi
            bounded "$out" "$bin"; rc=$?
            failed="$(grep -c '^  FAIL ' "$out")"; passed="$(grep -c '^  ok   ' "$out")"
            if grep -Eq "$SAN_RE" "$out" || [ $rc -eq 124 ]; then
                echo "  [mutant $name, $main] GATE FAILURE: sanitizer report or no return (rc=$rc)"; show "$out"; FAIL=1
            elif [ "$failed" != "$want" ] || [ $((failed + passed)) -ne "$total" ]; then
                echo "  [mutant $name, $main] GATE FAILURE: $failed checks failed and $passed passed (want $want failed of $total)"
                grep '^  FAIL ' "$out" | sed -n '1,8p' | sed 's/^/      /'; FAIL=1
            elif [ "$want" = 0 ]; then
                [ $rc -eq 0 ] && echo "  [mutant $name, $main] unaffected, as expected" ||
                    { echo "  [mutant $name, $main] GATE FAILURE: rc=$rc with no failed check"; FAIL=1; }
            elif [ $rc -ne 0 ] && grep -q '^FAILED (' "$out"; then
                first="$(grep -m1 '^  FAIL ' "$out")"
                echo "  [mutant $name, $main] failed $failed, as it must: ${first:7:110}"
            else
                echo "  [mutant $name, $main] GATE FAILURE: checks failed but the main did not (rc=$rc)"; FAIL=1
            fi
        done
    done
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
if [ ! -x "$BUILD_DIR/green64.1" ] || [ ! -x "$BUILD_DIR/green64.2" ]; then
    leak_note="not run: no 64-bit build"
else
    leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green64.1" 2>&1)"
    if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
        leak_note="both mains clean"
        echo "--- LEAK CHECK: both mains on the 64-bit build under detect_leaks=1 ---"
        for n in 1 2; do
            out="$BUILD_DIR/leak.$n.out"
            ASAN_OPTIONS="$ASAN_LEAK" bounded "$out" "$BUILD_DIR/green64.$n"; rc=$?
            if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" "$out" || ! grep -q '^PASSED (0 failures)$' "$out"; then
                echo "  [leak main $n] GATE FAILURE (rc=$rc)"; show "$out" 6; FAIL=1
                leak_note="FAILED"
            fi
        done
    fi
fi
echo "leak check: $leak_note"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: $KAT (${CHECKS[0]##*:} vector checks, ${CHECKS[1]##*:} builder checks, ${#MUTANTS[@]} mutants each failing exactly its checks; 64-bit ASan + UBSan and 32-bit ASan; leak check $leak_note; portability)"
    exit 0
fi
echo "FAIL: $KAT"
exit 1
