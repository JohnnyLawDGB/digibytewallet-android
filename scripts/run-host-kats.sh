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

if ! command -v clang >/dev/null 2>&1; then
    echo "error: clang is required (the KATs build with -fsanitize=address)" >&2
    exit 1
fi

pass=0; fail=0; norunner=0
failed_kats=""
norunner_kats=""
start=$SECONDS

for dir in "$HOST_DIR"/*/; do
    name="$(basename "$dir")"
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
echo "host KATs: $pass passed, $fail failed, $norunner without a runner  ($((SECONDS - start))s)"
[ -n "$failed_kats" ]   && echo "failed:  $failed_kats"
[ -n "$norunner_kats" ] && echo "no runner:$norunner_kats"

[ "$fail" -eq 0 ] && [ "$norunner" -eq 0 ] && exit 0
exit 1
