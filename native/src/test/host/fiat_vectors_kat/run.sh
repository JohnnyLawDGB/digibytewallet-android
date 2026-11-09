#!/usr/bin/env bash
# Host KAT for the shared BRFiatAmount.h vectors (vectors.txt beside this file), which Android's
# UsdCentsVectorsTest holds UsdCents to on the host JVM.
#
#   VECTORS GATE  vectors.txt is exactly what generate.c writes from the pinned header: the file
#                 the JVM test reads is current.
#   RED           generate.c built with -DFIAT_VIA_DOUBLE_UNFIXED (the naive double conversion,
#                 rounded half up) MUST write different c4s/s4c/preview/dgbtext answers, and the
#                 same rate/parse/format answers: the vectors can tell the exact conversion from
#                 the naive one, so a mirror that converts through a double cannot pass them.
#   CORPUS        the prices core #60's review named are present (2^-24, 2^-44, 2e23, 5e-324).
#
# The rate is the one floating-point step, so the generator is also built and compared under
# -ffp-contract=off -O2: optimisation must not change a single answer.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
GEN="$SCRIPT_DIR/generate.c"
VEC="$SCRIPT_DIR/vectors.txt"
FAIL=0

[ -f "$GEN" ] && [ -f "$VEC" ] || { echo "GATE FAILURE: generate.c or vectors.txt missing"; exit 1; }

build() {   # <out> [flags...]
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Werror "$@" -I "$CORE_DIR" "$GEN" -lm -o "$out"
}

build "$BUILD_DIR/gen" -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
    || { echo "GATE FAILURE: generator did not compile"; exit 1; }
build "$BUILD_DIR/gen_o2" -O2 -ffp-contract=off || { echo "GATE FAILURE: -O2 generator did not compile"; exit 1; }
build "$BUILD_DIR/gen_red" -DFIAT_VIA_DOUBLE_UNFIXED || { echo "GATE FAILURE: RED generator did not compile"; exit 1; }

if ! "$BUILD_DIR/gen" > "$BUILD_DIR/vectors.txt" 2> "$BUILD_DIR/gen.err"; then
    echo "GATE FAILURE: generator failed"; head -5 "$BUILD_DIR/gen.err"; FAIL=1
fi
grep -Eq 'ERROR: AddressSanitizer|runtime error:' "$BUILD_DIR/gen.err" && { echo "GATE FAILURE: sanitizer report"; FAIL=1; }

if cmp -s "$BUILD_DIR/vectors.txt" "$VEC"; then
    echo "  [vectors] current ($(grep -vc '^#' "$VEC") vectors)"
else
    echo "  [vectors] GATE FAILURE: vectors.txt is not what generate.c writes from this header; regenerate it"
    diff "$VEC" "$BUILD_DIR/vectors.txt" | head -10 | sed 's/^/      /'
    FAIL=1
fi

"$BUILD_DIR/gen_o2" > "$BUILD_DIR/vectors_o2.txt"
cmp -s "$BUILD_DIR/vectors_o2.txt" "$VEC" && echo "  [-O2] same answers" \
    || { echo "  [-O2] GATE FAILURE: optimisation changed an answer"; FAIL=1; }

"$BUILD_DIR/gen_red" > "$BUILD_DIR/vectors_red.txt" 2>/dev/null
for kind in c4s s4c preview dgbtext; do
    n="$(diff <(grep "^$kind " "$VEC") <(grep "^$kind " "$BUILD_DIR/vectors_red.txt") | grep -c '^>')"
    [ "$n" -gt 0 ] && echo "  [red $kind] $n answers differ, as they must" \
        || { echo "  [red $kind] GATE FAILURE: the naive conversion writes the same answers"; FAIL=1; }
done
for kind in rate parse format; do
    cmp -s <(grep "^$kind " "$VEC") <(grep "^$kind " "$BUILD_DIR/vectors_red.txt") && echo "  [red $kind] GUARD held" \
        || { echo "  [red $kind] GATE FAILURE: the RED seam changed $kind, which it does not touch"; FAIL=1; }
done

# 2^-24, 2^-44, 2e23, 5e-324: their bits as the file writes them.
for bits in 3e70000000000000 3d30000000000000 44c52d02c7e14af6 0000000000000001; do
    grep -q "^rate $bits " "$VEC" && echo "  [corpus] rate $bits present" \
        || { echo "  [corpus] GATE FAILURE: no rate vector for $bits"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "fiat_vectors_kat: GATE HELD (vectors current; the naive conversion fails them)"
exit $FAIL
