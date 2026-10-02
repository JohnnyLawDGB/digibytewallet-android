#!/usr/bin/env bash
#
# Aggregate runner for the native host KATs in native/src/test/host/.
#
# These are ASan-instrumented known-answer tests compiled directly against the live C
# submodule sources — they catch memory-safety and protocol regressions that the JVM unit
# tests structurally cannot (NativeBridge's static initializer throws UnsatisfiedLinkError
# on a host JVM, so the native layer is unmockable there).
#
# Until this script existed there was no aggregate runner and no CI job, so KATs rotted
# silently: cf_confirm_kat had not BUILT since BRBloomFilter.c was deleted in the v4.0.0
# bloom excision, and cf_gate_kat had been RED against a stale pre-excision truth table.
# A test suite nothing runs is not a regression gate.
#
# Usage:
#   scripts/run-host-kats.sh              # run all KATs
#   scripts/run-host-kats.sh watched      # run only KATs whose name matches "watched"
#
# Every suite is built at the header check levels the app ships -- proof of work and difficulty
# target, both read from native/build.gradle.kts and printed in the header line -- unless the suite
# names that level itself.
#
# Exit code 0 = every KAT passed, 1 = at least one failed or has no runner.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOST_DIR="$REPO_ROOT/native/src/test/host"
FILTER="${1:-}"

if [ ! -d "$HOST_DIR" ]; then
    echo "error: $HOST_DIR not found" >&2
    exit 1
fi

# The KATs build with `clang -fsanitize=address`. Rather than insist that a `clang` already
# be on the PATH, find one that can actually do that (scripts/find-clang.sh explains why
# "exists" is not enough), export it for every run.sh below, and say which one it is — a
# sanitizer result means little without knowing what produced it.
# shellcheck source=scripts/find-clang.sh
. "$SCRIPT_DIR/find-clang.sh"
find_host_clang || exit 1

# THE SHIPPED LEVELS. Two header checks are compiled in at a level each (BRMerkleBlock.h):
# DGB_HEADER_POW_CHECK (proof of work, allowed algorithm by height) and DGB_HEADER_DIFF_CHECK (the
# MultiShield V4 difficulty target). The app build sets both in native/build.gradle.kts; the C header
# defaults each to 0. A suite that does not name a level would therefore be built at 0, a level no app
# build ships, and a sweep would test a different verdict from the one users run. Read each level the
# way the seam gates do (merkleblock_pow_kat, header_diff_v4_kat), and have the `clang` every run.sh
# calls append it, unless that call names that level itself (suites with their own 0/1/2 arms keep
# them; each flag is judged on its own). Through the same shim directory find-clang.sh uses for its
# compiler link; run.sh files that use "${CC:-clang}" resolve to it as well.
GRADLE_NATIVE="$REPO_ROOT/native/build.gradle.kts"
shipped_level() {   # $1 = macro name; prints its one level, or fails
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$GRADLE_NATIVE" 2>/dev/null | sort -u)"
    if [ "$(printf '%s' "$found" | grep -c .)" -ne 1 ]; then
        echo "error: expected exactly one -D$1=<level> in $GRADLE_NATIVE, found: ${found:-none}" >&2
        return 1
    fi
    printf '%s' "${found#D$1=}"
}
SHIPPED_POW_LEVEL="$(shipped_level DGB_HEADER_POW_CHECK)" || exit 1
SHIPPED_DIFF_LEVEL="$(shipped_level DGB_HEADER_DIFF_CHECK)" || exit 1
if [ -z "${HOST_CLANG_SHIM:-}" ]; then
    HOST_CLANG_SHIM="$(mktemp -d)"
    PATH="$HOST_CLANG_SHIM:$PATH"
    export PATH
fi
rm -f "$HOST_CLANG_SHIM/clang"
cat > "$HOST_CLANG_SHIM/clang" <<SHIM
#!/usr/bin/env bash
# run-host-kats.sh: build at the shipped header check levels unless the caller names a level.
pow=1; diff=1
for a in "\$@"; do
    case "\$a" in *DGB_HEADER_POW_CHECK*) pow=0 ;; esac
    case "\$a" in *DGB_HEADER_DIFF_CHECK*) diff=0 ;; esac
done
extra=()
[ \$pow -eq 1 ] && extra+=(-DDGB_HEADER_POW_CHECK=$SHIPPED_POW_LEVEL)
[ \$diff -eq 1 ] && extra+=(-DDGB_HEADER_DIFF_CHECK=$SHIPPED_DIFF_LEVEL)
exec "$HOST_CLANG" "\$@" "\${extra[@]}"
SHIM
chmod +x "$HOST_CLANG_SHIM/clang"
trap 'rm -rf "$HOST_CLANG_SHIM"' EXIT
if [ "$(command -v clang)" != "$HOST_CLANG_SHIM/clang" ]; then
    echo "error: the level shim is not the clang on the PATH ($(command -v clang))" >&2
    exit 1
fi
LEVELS="proof-of-work level $SHIPPED_POW_LEVEL, difficulty-target level $SHIPPED_DIFF_LEVEL"

echo "compiler: $HOST_CLANG ($("$HOST_CLANG" --version 2>/dev/null | head -1))"
echo "header checks: $LEVELS (shipped, from native/build.gradle.kts; a suite that names its own level keeps it)"
echo

pass=0; fail=0; norunner=0
failed_kats=""
norunner_kats=""
start=$SECONDS

for dir in "$HOST_DIR"/*/; do
    name="$(basename "$dir")"

    # Only *_kat directories. A tool once lived here and the runner executed it with no
    # arguments, so its usage message failed CI as though a known-answer test had regressed.
    # Filtering by name means anything dropped in here that is not a KAT is ignored rather than
    # run — while a real KAT missing its runner is still surfaced below.
    [[ "$name" != *_kat ]] && continue

    [ -n "$FILTER" ] && [[ "$name" != *"$FILTER"* ]] && continue

    if [ ! -f "$dir/run.sh" ]; then
        # A *_main.c with no runner can never execute — surface it rather than skipping
        # silently, which is how gcs_match_kat sat unrunnable.
        printf '%-32s %s\n' "$name" "NO RUNNER"
        norunner=$((norunner + 1)); norunner_kats="$norunner_kats $name"
        continue
    fi

    if out=$(bash "$dir/run.sh" 2>&1); then
        printf '%-32s %s\n' "$name" "PASS"
        pass=$((pass + 1))
    else
        printf '%-32s %s\n' "$name" "FAIL"
        echo "$out" | sed 's/^/    | /'
        fail=$((fail + 1)); failed_kats="$failed_kats $name"
    fi
done

echo
echo "host KATs: $pass passed, $fail failed, $norunner without a runner  ($((SECONDS - start))s, $LEVELS)"
[ -n "$failed_kats" ]   && echo "failed:  $failed_kats"
[ -n "$norunner_kats" ] && echo "no runner:$norunner_kats"

[ "$fail" -eq 0 ] && [ "$norunner" -eq 0 ] && exit 0
exit 1
