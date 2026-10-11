#!/usr/bin/env bash
# Host KAT runner for BRSyncFrontier.h -- the CF-gated sync frontier (Connecting / Syncing /
# Synced / Failed) every sync-status surface shares (from core.model.deriveSyncFrontier).
#
# Header-only: includes BRSyncFrontier.h and links nothing but libc.
#
#   RED    -DSYNC_FRONTIER_RED ignores the filter scan (header-only: the drift that let a screen
#          say "Synced" while the scan lagged). Every RED case MUST fail; every GUARD case pass.
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

RED_CASES=(cf_lagging_while_header_complete cf_behind_threshold_strict huge_target_cf_lag_still_syncing
           scan_frontier_governs_a_deep_restore)
GUARD_CASES=(no_peers_is_connecting failed_is_failed_even_with_peers header_behind_cf_not_started
             caught_up_is_synced cf_not_started_header_complete_is_synced external_tip_over_stale_peer_target
             huge_target_after_synced_is_synced huge_target_not_synced_this_session plausible_lead_after_synced
             lead_bound_strict huge_target_requires_complete huge_target_abandoned_band_holds host_states
             session_latches_after session_header_max_never_drops estimate_zero_uses_header blocks_remaining
             extremes_wrap_like_kotlin)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/sync_frontier_kat_main.c" -o "$out"
}

if ! grep -q "SYNC_FRONTIER_RED" "$CORE_DIR/BRSyncFrontier.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRSyncFrontier.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSYNC_FRONTIER_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSYNC_FRONTIER_RED): every RED case MUST fail, every GUARD case pass ---"
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

[ $FAIL -eq 0 ] && echo "sync_frontier_kat: GATE HELD (RED said Synced over a lagging filter scan; GREEN holds Syncing until the scan is within 100 blocks)"
exit $FAIL
