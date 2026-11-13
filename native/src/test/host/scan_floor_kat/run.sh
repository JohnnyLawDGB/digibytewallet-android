#!/usr/bin/env bash
# Host KAT runner for BRScanFloor.h (the compact-filter scan-floor rules, from
# HistoryRebuildOnUpgrade.kt, WalletManager.kt and CfRestorePreflight.kt) and BRChainRebuild.h (the
# order of a rebuild from chain, from WalletManager.rebuildFromChainRescan / ...From).
#
# Also BRSavedTransactions.h's BRSavedTransactionsFloorMinima (HistoryRebuildOnUpgrade.summarize).
# Includes the bridge's checkpoint_time.h, which the core's lookup is compared against on the real
# checkpoint tables, and reads WalletManager.kt, HistoryRebuildOnUpgrade.kt, ReconcileScreen.kt and
# SyncService.kt from this checkout to hold the rebuild and reload order to the order of the calls.
# Every step, FLOOR_FROM_CACHE included, has its call in Android's source (its token is in the main
# file). Built with -Wall -Wextra -Werror; only -Wno-unused-parameter, for BRChainParams.h.
#
#   RED    one comparison arm per seam. Each arm's RED cases MUST fail and every other case pass:
#            -DSCAN_FLOOR_RAISE_RED       a floor that is raised instead of lowered
#            -DSCAN_FLOOR_ZERO_RED        a birth of 0 written as the floor (genesis)
#            -DSCAN_FLOOR_CHECKPOINT_RED  the floor time from the checkpoint at or ABOVE the height
#            -DSCAN_FLOOR_STEP_RED        the sync start applies an anchor above the record's height
#            -DSCAN_FLOOR_PENDING_RED     a discarded cache's pending flag OR'd in, not set
#            -DSCAN_FLOOR_RESTORE_RED     the restore creation time raised to the floor time
#            -DSAVED_TRANSACTIONS_FLOOR_SENTINEL_RED  the unconfirmed sentinel read as a block height
#            -DCHAIN_REBUILD_ORDER_RED    the rerun armed after the clears
#            -DCHAIN_REBUILD_FLOOR_RED    the cache's floor taken after the cache is cleared
#   GREEN  the shipped headers: every case passes, ALL PASS, exit 0.
#   VECTORS the core's vectors/scan-floor/vectors.txt must be its generator's current output, and
#          every line must agree with the KAT's model of the Kotlin.
# Every arm under ASan + UBSan; a sanitizer report fails the gate.
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${HOST_CLANG:-${CC:-clang}}"
VECTORS="$CORE_DIR/vectors/scan-floor/vectors.txt"
WM="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt"
HR="$REPO_ROOT/core/src/main/java/app/aroundtheblock/wallet/core/sync/HistoryRebuildOnUpgrade.kt"
RS="$REPO_ROOT/app/src/main/java/app/aroundtheblock/wallet/ui/settings/ReconcileScreen.kt"
SS="$REPO_ROOT/app/src/main/java/app/aroundtheblock/wallet/service/SyncService.kt"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

ALL_CASES=(kt_summarize_empty_and_zero_count kt_summarize_one_real_record
           kt_summarize_mixed_heights_take_the_minimum_and_ignore_the_sentinel kt_summarize_all_unconfirmed_gives_no_height_but_a_time
           kt_summarize_unconfirmed_time_below_lowers_only_the_time kt_summarize_truncated_mid_record_keeps_whole_records
           kt_summarize_size_past_the_end_stops_without_a_hint kt_summarize_count_above_the_cap_gives_no_hint
           kt_summarize_never_fails_on_arbitrary_bytes
           kt_birth_real_checkpoint_height_persisted kt_birth_zero_never_persisted kt_birth_negative_not_persisted
           kt_birth_smallest_positive_persisted kt_run_floor_from_the_discarded_cache kt_run_interrupted_keeps_the_first_hint
           kt_run_never_raises_the_floor kt_run_lowers_an_existing_hint kt_lowerFloorTo_sets_hint_time_and_pending
           kt_lowerFloorTo_never_raises_an_existing_floor kt_lowerFloorTo_ignores_a_non_positive_height
           kt_markNotNeeded_forgets_the_floor_only_when_asked kt_clearFloorHint_forgets_only_the_height
           kt_run_floor_time_reaches_the_checkpoint_at_or_below_the_hint kt_run_floor_time_is_the_lower_of_record_and_checkpoint
           kt_run_lookup_that_throws_falls_back_to_the_record_time kt_run_asks_the_lookup_for_the_final_hint
           kt_run_only_unconfirmed_records_marks_the_floor_pending kt_run_floor_height_marks_pending_and_no_floor_does_not
           kt_pending_marker_goes_with_the_hint_and_a_different_wallet kt_armRerun_keeps_the_floors
           kt_step_no_hint_does_nothing kt_step_wallet_not_loaded_keeps_the_hint kt_step_anchor_above_the_hint_keeps_it
           kt_step_anchor_at_or_below_lowers_a_higher_floor kt_step_no_persisted_floor_persists_the_anchor
           kt_step_floor_at_or_below_the_anchor_stays kt_step_floor_between_anchor_and_hint_goes_to_the_anchor
           kt_step_no_hint_but_pending_lowers_to_the_anchor kt_step_no_hint_nothing_pending_does_nothing
           kt_step_pending_floor_waits_for_the_wallet kt_step_genesis_anchor_is_a_valid_anchor
           kt_step_result_feeds_the_birth_choice_at_or_below_the_hint kt_creationTimeForRestore_lowers_to_the_floor_time_only
           kt_rebuild_arms_the_rerun_and_keeps_the_floor_time kt_rebuild_still_forgets_recorded_sends
           kt_rebuild_deletes_the_ledger_and_the_band kt_rebuild_clears_every_derived_store
           edge_lower_bounds edge_birth_null_out_and_huge edge_checkpoint_lookup_bounds edge_lowerTo_lookup_miss_keeps_the_time
           edge_cache_clears_a_pending_flag_with_no_floor edge_step_never_writes_zero_or_raises edge_restore_time_bounds
           edge_rebuild_names_and_bounds edge_rebuild_invariants edge_failure_rules edge_reload_steps
           model_agrees_floor_rules model_agrees_minima model_agrees_checkpoint_time_on_the_real_tables
           prop_lowered_floor_time_anchors_at_or_below_the_height prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor
           order_matches_android_source order_reload_matches_android_source order_rebuild_has_no_early_exit
           vectors_match_the_model)

build() {
    local out="$1"; shift
    "$CC" -Wall -Wextra -Werror -Wno-unused-parameter -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/scan_floor_kat_main.c" -o "$out"
}

for pair in BRScanFloor.h:SCAN_FLOOR_RAISE_RED BRScanFloor.h:SCAN_FLOOR_ZERO_RED BRScanFloor.h:SCAN_FLOOR_CHECKPOINT_RED \
            BRScanFloor.h:SCAN_FLOOR_STEP_RED BRScanFloor.h:SCAN_FLOOR_PENDING_RED BRScanFloor.h:SCAN_FLOOR_RESTORE_RED \
            BRSavedTransactions.h:SAVED_TRANSACTIONS_FLOOR_SENTINEL_RED \
            BRChainRebuild.h:CHAIN_REBUILD_ORDER_RED BRChainRebuild.h:CHAIN_REBUILD_FLOOR_RED; do
    hdr="${pair%%:*}"; seam="${pair#*:}"
    if ! grep -q "$seam" "$CORE_DIR/$hdr" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/$hdr missing or has no $seam seam; -D would be inert."
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
    out="$("$BUILD_DIR/$seam" "$VECTORS" "$WM" "$HR" "$RS" "$SS" 2>&1)"; rc=$?
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

red_arm SCAN_FLOOR_RAISE_RED kt_run_never_raises_the_floor kt_run_lowers_an_existing_hint \
    kt_lowerFloorTo_never_raises_an_existing_floor kt_run_floor_time_reaches_the_checkpoint_at_or_below_the_hint \
    kt_run_floor_time_is_the_lower_of_record_and_checkpoint kt_run_asks_the_lookup_for_the_final_hint \
    kt_rebuild_arms_the_rerun_and_keeps_the_floor_time edge_lower_bounds model_agrees_floor_rules \
    prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor
red_arm SCAN_FLOOR_ZERO_RED kt_birth_zero_never_persisted edge_birth_null_out_and_huge model_agrees_floor_rules
red_arm SCAN_FLOOR_CHECKPOINT_RED edge_checkpoint_lookup_bounds edge_lowerTo_lookup_miss_keeps_the_time \
    model_agrees_floor_rules model_agrees_checkpoint_time_on_the_real_tables prop_lowered_floor_time_anchors_at_or_below_the_height
red_arm SCAN_FLOOR_STEP_RED kt_step_anchor_above_the_hint_keeps_it edge_step_never_writes_zero_or_raises model_agrees_floor_rules
red_arm SCAN_FLOOR_PENDING_RED edge_cache_clears_a_pending_flag_with_no_floor model_agrees_floor_rules
red_arm SCAN_FLOOR_RESTORE_RED kt_creationTimeForRestore_lowers_to_the_floor_time_only edge_restore_time_bounds model_agrees_floor_rules
red_arm SAVED_TRANSACTIONS_FLOOR_SENTINEL_RED kt_summarize_all_unconfirmed_gives_no_height_but_a_time model_agrees_minima
red_arm CHAIN_REBUILD_ORDER_RED kt_rebuild_arms_the_rerun_and_keeps_the_floor_time edge_rebuild_invariants edge_failure_rules \
    order_matches_android_source vectors_match_the_model
red_arm CHAIN_REBUILD_FLOOR_RED kt_rebuild_arms_the_rerun_and_keeps_the_floor_time edge_failure_rules \
    order_matches_android_source prop_rebuild_reaches_a_cached_tx_below_the_birth_anchor vectors_match_the_model

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }
out="$("$BUILD_DIR/green" "$VECTORS" "$WM" "$HR" "$RS" "$SS" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep -E "^FAIL:|^  " | sed 's/^/      /'; FAIL=1
fi
<<<"$out" grep "^  " | sed 's/^/  [green]/'
for c in "${ALL_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

echo "--- VECTORS: the committed file is the generator's current output ---"
if ! "$CC" -Wall -Wextra -Werror -Wno-unused-parameter -I "$CORE_DIR" "$CORE_DIR/vectors/scan-floor/generate.c" -o "$BUILD_DIR/gen"; then
    echo "  [vectors] GATE FAILURE: the generator did not compile"; FAIL=1
elif ! "$BUILD_DIR/gen" | cmp -s - "$VECTORS"; then
    echo "  [vectors] GATE FAILURE: $VECTORS is not what generate.c writes; regenerate it (see its header)"; FAIL=1
else
    echo "  [vectors] current"
fi

[ $FAIL -eq 0 ] && echo "scan_floor_kat: GATE HELD (each RED arm failed its cases; GREEN matches the Kotlin model)"
exit $FAIL
