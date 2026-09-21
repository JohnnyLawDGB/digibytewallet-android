#!/usr/bin/env bash
# Host KAT runner: every declared count in a wire message is bounded by the bytes
# that remain, before anything is allocated.
#
# WHAT IT PROVES. Five peer-supplied messages each name a count that the parser
# turns into an allocation or a copy: a transaction's input/output count, a
# headers message's header count, a version message's user-agent length, a reject
# message's string length, and a compact-filter's element count. The bounded
# (green) parser rejects every one of them before sizing anything. The unbounded
# (red) parser does not reject; because each message sits in an exact-length
# buffer, AddressSanitizer trips when the parser acts on a count larger than the
# bytes present -- confirming the KAT exercises the code the bound governs.
#
# BUILDS. The KAT runs in BOTH a 64-bit and a 32-bit build. The transaction,
# headers, version and reject cases fault in either word size; the compact-filter
# case's size computation only wraps at 32 bits, so its red arm is required to
# fault in the 32-bit build and is expected to reject cleanly at 64 bits.
#
# WITNESS CASE. A segwit input's witness stack declares an item count that the
# parser walks. Its comparison arm's red evidence is not a sanitizer report but a
# call that DOES NOT RETURN (a count near the type maximum makes the walk take that
# many steps), so tx_witness is run under a time limit in both word sizes and
# "killed at the limit" is the expected result for that arm. The shipped arm
# rejects tx_witness cleanly and accepts the honest control tx_witness_ctl, whose
# witness bytes round-trip.
#
# MACRO CONVENTION. Presence: red is -DWIRE_COUNT_BOUNDS_UNFIXED, green is no -D
# at all, so a shipped build (which never defines the flag) always gets the
# bounded code. Detecting a sanitizer report matches the string
# "ERROR: AddressSanitizer" (or UBSan's "runtime error:"), never a subtype word.
set -uo pipefail   # not -e: the red arm's nonzero/aborting exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=10   # the witness comparison arm does not return; it is killed at this limit

# bounded <outfile> <cmd...>: exit code of the command, or 124 if it was killed at
# the limit. Used for the tx_witness comparison arm, whose walk does not return.
bounded() {
    local outfile="$1"; shift
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    if kill -0 "$watchdog" 2>/dev/null; then
        kill "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null; return $rc
    fi
    return 124
}

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/wire_count_bounds_kat_main.c" \
        "${UNITS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# ---- verify the seam is real ------------------------------------------------
# -D silently loses to a plain #define, and -w hides a redefinition warning, so
# confirm each edited source actually carries the presence seam before trusting
# the red arm.
for f in BRArray.h BRTransaction.c BRPeer.c BRGCSFilter.c BRMerkleBlock.c; do
    if ! grep -q 'WIRE_COUNT_BOUNDS_UNFIXED' "$CORE_DIR/$f"; then
        echo "GATE FAILURE: $f has no WIRE_COUNT_BOUNDS_UNFIXED seam; -D would be inert."
        exit 1
    fi
done
if ! grep -q 'ARRAY_INSERT_POLICY_UNFIXED' "$CORE_DIR/BRArray.h"; then
    echo "GATE FAILURE: BRArray.h has no ARRAY_INSERT_POLICY_UNFIXED seam; -D would be inert."
    exit 1
fi
if ! grep -q 'WIRE_STORE_CHECK_UNFIXED' "$CORE_DIR/BRTransaction.c"; then
    echo "GATE FAILURE: BRTransaction.c has no WIRE_STORE_CHECK_UNFIXED seam; -D would be inert."
    exit 1
fi
if ! grep -q 'WIRE_WITNESS_COUNT_UNFIXED' "$CORE_DIR/BRTransaction.c"; then
    echo "GATE FAILURE: BRTransaction.c has no WIRE_WITNESS_COUNT_UNFIXED seam; -D would be inert."
    exit 1
fi

FAIL=0

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DWIRE_COUNT_BOUNDS_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    # Cases whose red arm must fault in this word size.
    local red_cases=(tx headers version reject)
    [ "$bits" = "32" ] && red_cases+=(cf)

    echo "--- RED ARM (-DWIRE_COUNT_BOUNDS_UNFIXED): each case MUST fault ---"
    for c in "${red_cases[@]}"; do
        out="$("$BUILD_DIR/red${bits}" "$c" 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] tripped (rc=$rc): $(echo "$out" | grep -Eom1 "$SAN_RE")"
        else
            echo "  [$bits red $c] GATE FAILURE: no sanitizer report (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6
            FAIL=1
        fi
    done
    if [ "$bits" != "32" ]; then
        # cf's size computation only wraps at 32 bits; at 64 bits the red arm
        # must return cleanly (no fault) -- exercised for completeness.
        out="$("$BUILD_DIR/red${bits}" cf 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red cf] unexpected sanitizer report (rc=$rc)"; FAIL=1
        else
            echo "  [$bits red cf] no fault at 64 bits, as expected (rc=$rc)"
        fi
    fi

    echo "--- GREEN ARM (bounded): each case MUST be rejected, no sanitizer report ---"
    for c in tx headers version reject cf; do
        out="$("$BUILD_DIR/green${bits}" "$c" 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits green $c] GATE FAILURE: sanitizer report in the fixed arm (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6
            FAIL=1
        elif echo "$out" | grep -q "RESULT $c rejected"; then
            echo "  [$bits green $c] rejected cleanly (rc=$rc)"
        else
            echo "  [$bits green $c] GATE FAILURE: not rejected (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6
            FAIL=1
        fi
    done
}

# ---- the store holds what the loop fills ---------------------------------------
# Same sources, built with the grow hook (see the main's header comment). Red is
# -DWIRE_STORE_CHECK_UNFIXED: the parser fills a store that did not grow, and the
# sanitizer must say so. Green rejects both messages cleanly, and ACCEPTS the control
# message, which differs only in that the hook is idle.
run_store() {
    local bits="$1"
    local hook=(-DKAT_GROW_HOOK -Drealloc=kat_realloc)
    echo "--- ${bits}-bit: store check (grow hook) ---"
    if ! build "$BUILD_DIR/sred${bits}" "$bits" "${hook[@]}" -DWIRE_STORE_CHECK_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit store red arm did not compile."; FAIL=1; return
    fi
    if ! build "$BUILD_DIR/sgreen${bits}" "$bits" "${hook[@]}"; then
        echo "GATE FAILURE: ${bits}-bit store green arm did not compile."; FAIL=1; return
    fi
    for c in tx_store_in tx_store_out; do
        out="$("$BUILD_DIR/sred${bits}" "$c" 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] tripped (rc=$rc): $(echo "$out" | grep -Eom1 "$SAN_RE")"
        else
            echo "  [$bits red $c] GATE FAILURE: no sanitizer report (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        fi
        out="$("$BUILD_DIR/sgreen${bits}" "$c" 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits green $c] GATE FAILURE: sanitizer report in the fixed arm (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        elif echo "$out" | grep -q "RESULT $c rejected"; then
            echo "  [$bits green $c] rejected cleanly (rc=$rc)"
        else
            echo "  [$bits green $c] GATE FAILURE: not rejected (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        fi
    done
    out="$("$BUILD_DIR/sgreen${bits}" tx_store_ctl 2>&1)"; rc=$?
    if echo "$out" | grep -q "RESULT tx_store_ctl accepted" && ! echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$bits green tx_store_ctl] accepted with the hook idle, as it must be (rc=$rc)"
    else
        echo "  [$bits green tx_store_ctl] GATE FAILURE: the control message was not accepted (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -6; FAIL=1
    fi

    # The inserting macros. The comparison arm is -DARRAY_INSERT_POLICY_UNFIXED: the earlier
    # form of the two macros over the same policy grow.
    echo "--- ${bits}-bit: inserting macros (grow hook) ---"
    if ! build "$BUILD_DIR/ired${bits}" "$bits" "${hook[@]}" -DARRAY_INSERT_POLICY_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit insert red arm did not compile."; FAIL=1; return
    fi
    for c in array_insert_full array_insert_array_full; do
        out="$("$BUILD_DIR/ired${bits}" "$c" 2>&1)"; rc=$?
        if echo "$out" | grep -Eq "$SAN_RE"; then
            echo "  [$bits red $c] tripped (rc=$rc): $(echo "$out" | grep -Eom1 "$SAN_RE")"
        else
            echo "  [$bits red $c] GATE FAILURE: no sanitizer report (rc=$rc)"
            echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        fi
        out="$("$BUILD_DIR/sgreen${bits}" "$c" 2>&1)"; rc=$?
        if ! echo "$out" | grep -Eq "$SAN_RE" && echo "$out" | grep -q "RESULT $c rejected"; then
            echo "  [$bits green $c] store and count left exactly as they were (rc=$rc)"
        else
            echo "  [$bits green $c] GATE FAILURE (rc=$rc)"; echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        fi
    done
    for arm in ired sgreen; do   # GUARD: the known answers hold in both forms
        out="$("$BUILD_DIR/${arm}${bits}" array_insert_known 2>&1)"; rc=$?
        if ! echo "$out" | grep -Eq "$SAN_RE" && echo "$out" | grep -q "RESULT array_insert_known accepted"; then
            echo "  [$bits $arm array_insert_known] every known answer held (guard)"
        else
            echo "  [$bits $arm array_insert_known] GATE FAILURE (rc=$rc)"; echo "$out" | sed 's/^/      /' | head -6; FAIL=1
        fi
    done
}

# ---- the per-input witness count is bounded by the bytes that remain -----------
# The comparison arm is -DWIRE_WITNESS_COUNT_UNFIXED and reproduces the original
# unbounded walk; its red evidence is that tx_witness DOES NOT RETURN, so it is run
# under bounded() and "killed at the limit" is its expected result. The shipped
# (green) arm rejects tx_witness cleanly and ACCEPTS the honest control, whose
# witness bytes round-trip. run_bits must run first for this word size so the green
# arm ($BUILD_DIR/green${bits}) exists.
run_witness() {
    local bits="$1"
    echo "--- ${bits}-bit: per-input witness count ---"
    if ! build "$BUILD_DIR/wred${bits}" "$bits" -DWIRE_WITNESS_COUNT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit witness red arm did not compile."; FAIL=1; return
    fi

    # COMPARISON ARM: the walk must not return; killed at the limit.
    bounded "$BUILD_DIR/wred${bits}.tx_witness.out" "$BUILD_DIR/wred${bits}" tx_witness; local rc=$?
    if [ $rc -eq 124 ]; then
        echo "  [$bits red tx_witness] did not return; killed at the limit (rc=124) -- the KAT sees what the bound governs"
    else
        echo "  [$bits red tx_witness] GATE FAILURE: returned (rc=$rc); this arm cannot see what the bound governs"
        head -6 "$BUILD_DIR/wred${bits}.tx_witness.out" 2>/dev/null | sed 's/^/      /'; FAIL=1
    fi
    # The comparison arm must still ACCEPT the honest control (a well-formed witness
    # walk terminates with or without the bound), confirming the arm is not simply broken.
    out="$("$BUILD_DIR/wred${bits}" tx_witness_ctl 2>&1)"; rc=$?
    if ! echo "$out" | grep -Eq "$SAN_RE" && echo "$out" | grep -q "RESULT tx_witness_ctl accepted"; then
        echo "  [$bits red tx_witness_ctl] the honest witness is accepted here too (rc=$rc)"
    else
        echo "  [$bits red tx_witness_ctl] GATE FAILURE: the honest control was not accepted (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -6; FAIL=1
    fi

    # SHIPPED ARM: reject the over-count cleanly, accept and round-trip the control.
    out="$("$BUILD_DIR/green${bits}" tx_witness 2>&1)"; rc=$?
    if echo "$out" | grep -Eq "$SAN_RE"; then
        echo "  [$bits green tx_witness] GATE FAILURE: sanitizer report in the fixed arm (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -6; FAIL=1
    elif echo "$out" | grep -q "RESULT tx_witness rejected"; then
        echo "  [$bits green tx_witness] rejected cleanly (rc=$rc)"
    else
        echo "  [$bits green tx_witness] GATE FAILURE: not rejected (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -6; FAIL=1
    fi
    out="$("$BUILD_DIR/green${bits}" tx_witness_ctl 2>&1)"; rc=$?
    if ! echo "$out" | grep -Eq "$SAN_RE" && echo "$out" | grep -q "RESULT tx_witness_ctl accepted"; then
        echo "  [$bits green tx_witness_ctl] accepted; the witness bytes round-trip (rc=$rc)"
    else
        echo "  [$bits green tx_witness_ctl] GATE FAILURE: the honest control was not accepted or did not round-trip (rc=$rc)"
        echo "$out" | sed 's/^/      /' | head -6; FAIL=1
    fi
}

run_bits 64
run_store 64
run_witness 64
run_bits 32
run_store 32
run_witness 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: wire_count_bounds_kat (red tripped, green clean, store check held; 64-bit and 32-bit)"
    exit 0
else
    echo "FAIL: wire_count_bounds_kat"
    exit 1
fi
