#!/usr/bin/env bash
# Host KAT runner for BRCFRestorePreflight.h -- the cold-start decision (resume from the saved chain
# state, or rebuild from the wallet's birth) and the filter-fetch arming height, from
# CfRestorePreflight.kt.
#
# Links BRCFScanLedger.c (the ledger parser the property cases compare against); BRSavedBlocks.h's
# block parser is stubbed in the main file, so no block is ever parsed.
#
#   RED    one comparison arm per seam. Each arm's RED cases MUST fail and every other case pass:
#            -DCF_RESTORE_PREFLIGHT_RED   no preflight at all (always resume)
#            -DCF_RESTORE_FRONTIER_RED    the ledger reader without its frontier check
#            -DCF_RESTORE_TRUNCATION_RED  the window reader reading a truncated window short
#            -DCF_RESTORE_ARM_RED         the stored floor wins even after sync
#   GREEN  the shipped header: every case passes, ALL PASS, exit 0.
#   VECTORS the core's vectors/cf-restore-preflight/vectors.txt (which Android's host-JVM
#          CfRestorePreflightVectorsTest holds the Kotlin to) must be its generator's current
#          output, and every line must agree with the KAT's model of the Kotlin.
# Every arm under ASan + UBSan; a sanitizer report fails the gate.
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
VECTORS="$CORE_DIR/vectors/cf-restore-preflight/vectors.txt"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

ALL_CASES=(kt_healthy_frontier_keeps_window kt_abandoned_covered_resumes kt_abandoned_not_covered_rebuilds
           kt_frontier_below_floor_rebuilds kt_ledger_without_blocks_rebuilds kt_synced_without_ledger_rescans
           kt_fresh_without_ledger_starts kt_invalid_inputs_fail_safe kt_synced_without_tx_checkpoint_rebuilds
           kt_synced_resume_ignores_stale_deep_birth kt_unfinished_restore_keeps_floor
           edge_inconsistent_frontier_is_unreadable edge_ledger_versions_and_strides edge_ledger_entry_caps
           edge_ledger_truncation_is_unreadable edge_window_truncation_is_unreadable edge_window_bounds
           edge_empty_stored_copies_are_unreadable edge_floor_boundary edge_frontier_does_not_wrap
           edge_arm_heights edge_reset_names edge_window_cap_is_the_parsers
           model_agrees_preflight model_agrees_arm
           parsers_load_every_valid_ledger parsers_walk_every_valid_window vectors_match_the_model)

build() {
    local out="$1"; shift
    "$CC" -w -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" \
        "$SCRIPT_DIR/cf_restore_preflight_kat_main.c" "$CORE_DIR/BRCFScanLedger.c" -o "$out"
}

for seam in CF_RESTORE_PREFLIGHT_RED CF_RESTORE_FRONTIER_RED CF_RESTORE_TRUNCATION_RED CF_RESTORE_ARM_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRCFRestorePreflight.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRCFRestorePreflight.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0

# red_arm <seam> <red case>... : the seam's cases fail, every other case passes.
red_arm() {
    local seam="$1"; shift
    local reds=" $* " out rc c
    build "$BUILD_DIR/$seam" "-D$seam" || { echo "GATE FAILURE: the $seam arm did not compile."; FAIL=1; return; }
    echo "--- COMPARISON ARM (-D$seam): its RED cases MUST fail, every other case pass ---"
    out="$("$BUILD_DIR/$seam" "$VECTORS" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$seam] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [$seam] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in "${ALL_CASES[@]}"; do
        case "$reds" in
            *" $c "*)
                if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$seam $c] failed, as it must"
                else echo "  [$seam $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi ;;
            *)
                if ! <<<"$out" grep -q "^RESULT $c pass$"; then
                    echo "  [$seam $c] GATE FAILURE: GUARD case did not hold"; FAIL=1
                fi ;;
        esac
    done
}

red_arm CF_RESTORE_PREFLIGHT_RED kt_abandoned_not_covered_rebuilds kt_frontier_below_floor_rebuilds \
    kt_ledger_without_blocks_rebuilds kt_synced_without_ledger_rescans kt_invalid_inputs_fail_safe \
    kt_synced_without_tx_checkpoint_rebuilds edge_empty_stored_copies_are_unreadable edge_floor_boundary \
    model_agrees_preflight
red_arm CF_RESTORE_FRONTIER_RED edge_inconsistent_frontier_is_unreadable model_agrees_preflight
red_arm CF_RESTORE_TRUNCATION_RED kt_invalid_inputs_fail_safe edge_window_truncation_is_unreadable \
    edge_window_bounds model_agrees_preflight parsers_walk_every_valid_window
red_arm CF_RESTORE_ARM_RED kt_synced_resume_ignores_stale_deep_birth edge_arm_heights model_agrees_arm

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }
out="$("$BUILD_DIR/green" "$VECTORS" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
<<<"$out" grep "^  " | sed 's/^/  [green]/'
for c in "${ALL_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

echo "--- VECTORS: the committed file is the generator's current output ---"
if ! "$CC" -w -I "$CORE_DIR" "$CORE_DIR/vectors/cf-restore-preflight/generate.c" -o "$BUILD_DIR/gen"; then
    echo "  [vectors] GATE FAILURE: the generator did not compile"; FAIL=1
elif ! "$BUILD_DIR/gen" | cmp -s - "$VECTORS"; then
    echo "  [vectors] GATE FAILURE: $VECTORS is not what generate.c writes; regenerate it (see its header)"; FAIL=1
else
    echo "  [vectors] current"
fi

[ $FAIL -eq 0 ] && echo "cf_restore_preflight_kat: GATE HELD (each RED arm failed its cases; GREEN matches the Kotlin model)"
exit $FAIL
