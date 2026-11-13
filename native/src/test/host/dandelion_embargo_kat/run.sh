#!/usr/bin/env bash
# Host KAT runner for BRDandelionEmbargo.h -- the Dandelion++ embargo: the 10-30 s window and its
# delay from a 53-bit draw (bit-exact with DandelionBroadcastPolicy.kt's double arithmetic), the
# fluff decision at the deadline on whichever manager is current, the re-arm wait, which
# embargoes are pending, and the stranded-send sweep's spare of a pending one.
#
# Header-only: includes BRDandelionEmbargo.h and links nothing but libc. The delay is checked
# against vectors/dandelion-embargo/vectors.txt in the core (Android's arithmetic, computed by
# its generate.py, not by the header); when python3 is present the file is regenerated and must
# be identical.
#
#   RED    three comparison arms, each failing only its own cases:
#          -DDANDELION_EMBARGO_DELAY_RED    the exact integer quotient, 1 ms short of Android where
#                                           the double's rounding carries the product up (Android's
#                                           own test input 0.999 is such a draw: 29980, not 29979);
#          -DDANDELION_EMBARGO_REARM_RED    the re-armed wait is not capped at the window;
#          -DDANDELION_EMBARGO_PENDING_RED  an embargo written down by an earlier process is
#                                           still pending.
#          In each arm every one of its RED cases MUST fail and every other case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Every arm under ASan + UBSan.
#   BUILD  the header is clean under -Wall -Wextra -Wpedantic -Werror in c99, c11 and c17, as
#          C++17, and in two linked translation units.
#
# 64-bit hosts only (the KAT's own double oracle assumes binary64 arithmetic, not x87).
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
VECTORS="$CORE_DIR/vectors/dandelion-embargo/vectors.txt"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
command -v "$CC" >/dev/null 2>&1 || CC=cc
CXX="${CXX:-clang++}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

DELAY_CASES="delay_kotlin_test_inputs delay_rounds_up_vectors delay_every_boundary_matches_double"
REARM_CASES="rearm_clock_set_back_is_capped"
PENDING_CASES="pending_earlier_process_is_not"
GUARD_CASES="constants_are_androids delay_edges delay_boundary_and_random_vectors
             delay_random_draws_match_double draw_is_top_53_bits stem_rule fluff_rule
             deadline_step_on_the_stem_manager deadline_step_without_a_manager deadline_step_after_a_teardown
             deadline_ms rearm_wait pending_in_this_process sweep_rule"
ALL_CASES="$DELAY_CASES $REARM_CASES $PENDING_CASES $GUARD_CASES"

[ -f "$CORE_DIR/BRDandelionEmbargo.h" ] || { echo "GATE FAILURE: $CORE_DIR/BRDandelionEmbargo.h missing"; exit 1; }
[ -s "$VECTORS" ] || { echo "GATE FAILURE: $VECTORS missing"; exit 1; }
for seam in DANDELION_EMBARGO_DELAY_RED DANDELION_EMBARGO_REARM_RED DANDELION_EMBARGO_PENDING_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRDandelionEmbargo.h"; then
        echo "GATE FAILURE: BRDandelionEmbargo.h has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0

# The vectors are current: regenerated from Android's arithmetic, byte for byte.
if command -v python3 >/dev/null 2>&1; then
    if python3 -I "$CORE_DIR/vectors/dandelion-embargo/generate.py" | cmp -s - "$VECTORS"; then
        echo "--- vectors.txt is what generate.py writes ---"
    else
        echo "GATE FAILURE: vectors.txt differs from generate.py's output"; FAIL=1
    fi
else
    echo "SKIP vectors regeneration: no python3"
fi

# The header builds clean everywhere it is used.
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRDandelionEmbargo.h"
int64_t dandelion_embargo_tu2(uint64_t d) { return BRDandelionEmbargoDelayMs(d); }
EOF
cat > "$BUILD_DIR/tu1.c" <<'EOF'
#include "BRDandelionEmbargo.h"
#include "BRDandelionEmbargo.h"
int64_t dandelion_embargo_tu2(uint64_t d);
int main(void) { return dandelion_embargo_tu2(0) == BRDandelionEmbargoDelayMs(0) ? 0 : 1; }
EOF
for std in c99 c11 c17; do
    if "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -I "$CORE_DIR" "$BUILD_DIR/tu1.c" "$BUILD_DIR/tu2.c" -o "$BUILD_DIR/tu_$std" \
        && "$BUILD_DIR/tu_$std"; then echo "--- header clean and linked twice under $std ---"
    else echo "GATE FAILURE: header not clean under $std"; FAIL=1; fi
done
if command -v "$CXX" >/dev/null 2>&1; then
    if "$CXX" -x c++ -std=c++17 -Wall -Wextra -Wpedantic -Werror -fsyntax-only -I "$CORE_DIR" "$BUILD_DIR/tu1.c"; then
        echo "--- header clean as C++17 ---"
    else echo "GATE FAILURE: header not clean as C++17"; FAIL=1; fi
fi

build() {
    local out="$1"; shift
    "$CC" -std=c11 -Wall -Wextra -Werror -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fno-omit-frame-pointer "$@" -I "$CORE_DIR" "$SCRIPT_DIR/dandelion_embargo_kat_main.c" -o "$out"
}

build "$BUILD_DIR/red_delay" -DDANDELION_EMBARGO_DELAY_RED || { echo "GATE FAILURE: DELAY_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_rearm" -DDANDELION_EMBARGO_REARM_RED || { echo "GATE FAILURE: REARM_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_pending" -DDANDELION_EMBARGO_PENDING_RED || { echo "GATE FAILURE: PENDING_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

# comparison_arm <binary> <label> <cases that must fail>: every other case must pass.
comparison_arm() {
    local bin="$1" label="$2" red="$3" out rc c
    echo "--- COMPARISON ARM (-D$label): its RED cases MUST fail, every other case pass ---"
    out="$("$bin" "$VECTORS" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$label] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [$label] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in $ALL_CASES; do
        case " $(echo $red) " in
        *" $c "*)
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$label $c] failed, as it must"
            else echo "  [$label $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi ;;
        *)
            if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [$label $c] GUARD held"
            else echo "  [$label $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi ;;
        esac
    done
}

comparison_arm "$BUILD_DIR/red_delay" DANDELION_EMBARGO_DELAY_RED "$DELAY_CASES"
comparison_arm "$BUILD_DIR/red_rearm" DANDELION_EMBARGO_REARM_RED "$REARM_CASES"
comparison_arm "$BUILD_DIR/red_pending" DANDELION_EMBARGO_PENDING_RED "$PENDING_CASES"

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" "$VECTORS" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
<<<"$out" grep -E "^read |boundary draws checked" | sed 's/^/  /'
for c in $ALL_CASES; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done
# A case the binary prints but this runner does not list would go unchecked.
for c in $(<<<"$out" sed -n 's/^RESULT \([^ ]*\) .*$/\1/p'); do
    case " $(echo $ALL_CASES) " in *" $c "*) ;; *) echo "  [green $c] GATE FAILURE: case not listed in run.sh"; FAIL=1 ;; esac
done

[ $FAIL -eq 0 ] && echo "dandelion_embargo_kat: GATE HELD (RED took the plain quotient, waited past the window and kept an earlier process's embargo; GREEN holds every rule)"
exit $FAIL
