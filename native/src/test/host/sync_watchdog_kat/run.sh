#!/usr/bin/env bash
# Host KAT runner for BRSyncWatchdog.h -- the BIP158 and tip-stall watchdogs' decisions,
# carrying Android's Bip158WatchdogPolicyTest.kt case for case plus SyncService's tier order.
#
# Header-only: includes BRSyncWatchdog.h and links nothing but libc.
#
#   RED    -DSYNC_WATCHDOG_RED restores the two historical bugs: unbounded suppression
#          (fix wave C2) and a forward-only frontier tracker. Every RED case MUST fail; every
#          GUARD case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan + UBSan.
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
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|runtime error:'

RED_CASES=(loop_valve_holds_recovery_until_ceiling released_past_ceiling c2b_heal_rearms c2b_frozen_rearms c2b_tier2_rearms
           c2b_post_timeout_rearms c2d_corrupt_chain_heals c2_tier2_live_gate
           c2a_tier2_never_both regression_is_reinit latch_clears_on_reinit
           reinit_no_tier2_harassment)
GUARD_CASES=(zero_peers_reconnect_then_recreate_every_third a_peer_resets_the_streak
             tor_coming_up_defers_and_keeps_the_streak never_synced_with_peers_kicks_sync loop_healthy_exits loop_progressing_waits loop_catching_up_waits
             loop_frozen_cf_recovers_once_at_120s loop_reanchor_then_grace_then_bounded_rotating_heals
             loop_declined_reanchor_asked_again loop_never_synced_never_reanchors window_full_at_window synced_not_reanchored_reanchors never_synced_stays_on_filters
             fresh_reanchor_given_grace reanchor_never_rebuilt_stays
             grace_ignored_before_reanchor post_timeout_not_while_scan_advances
             post_timeout_suppressed_by_valve synced_at_tip_is_healthy
             riding_the_chain_is_healthy far_behind_not_healthy
             restore_tip_below_network_not_healthy gap_boundary_inclusive
             convoy_window_top_healthy frozen_cf_past_window_recovers
             frozen_cf_not_while_scan_advances frozen_cf_suppressed_by_valve
             frozen_cf_not_before_window frozen_cf_not_when_headers_idle
             frozen_cf_not_pre_cf_phase frozen_cf_once_per_session window_open_when_unarmed
             window_open_when_scan_above_tip nothing_pending_no_suppression
             suppressed_pending_within_ceiling ceiling_derived_from_rearm_budget
             window_from_the_caller tier1_scan_frozen_with_peers tier1_not_before_window
             tier1_not_zero_peers tier1_not_header_gated_scan_advancing
             tier1_not_scan_advancing_window_open residual_dead_branch_fires
             no_escalation_while_header_rekicks tier2_after_tier1_and_extra_window
             tier2_needs_peers tier2_not_while_scan_advances
             fast_fires_on_frozen_scan_needs_peers fast_not_when_gated_and_scanning
             heal_after_reanchor_grace_frozen heal_not_before_grace
             heal_not_before_frozen_window heal_not_before_reanchor_tried
             heal_not_while_headers_import heal_not_zero_peers heal_bounded_per_session
             heal_not_while_scan_advances frontier_forward_restarts_clock
             frontier_unchanged_holds_clock unarmed_reading_ignored
             frozen_frontier_keeps_clock_and_latch armed_scan_tip_advance_not_progress
             unarmed_scan_tip_is_authoritative arming_mid_session_no_false_stall
             c2c_tier1_never_suppressed c2c_fast_never_suppressed
             c2c_nondestructive_armed_mid_progress c2a_destructive_suppressed_while_progressing
             heal_cooldown_wider_than_threshold decide_tier2_first_resets_latch
             decide_tier2_throttled_then_tier1_then_fast decide_fast_throttled
             decide_tier1_latches_once decide_nothing_without_peers)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/sync_watchdog_kat_main.c" -o "$out"
}

if ! grep -q "SYNC_WATCHDOG_RED" "$CORE_DIR/BRSyncWatchdog.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRSyncWatchdog.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSYNC_WATCHDOG_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSYNC_WATCHDOG_RED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c fail$"; then echo "  [red $c] failed, as it must"
    else echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
done
for c in "${GUARD_CASES[@]}"; do
    if echo "$out" | grep -q "^RESULT $c pass$"; then echo "  [red $c] GUARD held"
    else echo "  [red $c] GATE FAILURE: GUARD case did not hold"; echo "$out" | grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
echo "$out" | grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! echo "$out" | grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; echo "$out" | grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    echo "$out" | grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "sync_watchdog_kat: GATE HELD (RED: unbounded suppression and a forward-only clock; GREEN matches Bip158WatchdogPolicy)"
exit $FAIL
