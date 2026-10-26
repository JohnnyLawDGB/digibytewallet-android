#!/usr/bin/env bash
# Host KAT runner for BRSyncCompletion.h -- when the wallet may count itself synced (from
# SyncService's keepalive loop and onSyncComplete, and CfRestorePreflight's shouldMarkSynced).
#
# Header-only: includes BRSyncCompletion.h and links nothing but libc.
#
#   RED    -DSYNC_COMPLETION_RED takes the header tip alone, ignoring the filter scan (the
#          2026-08-02 Note 8 bug). Every RED case MUST fail; every GUARD case pass.
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

RED_CASES=(headers_at_tip_scan_behind_is_not_synced unknown_scan_frontier_is_never_caught_up scan_slack_is_inclusive
           scan_slack_is_from_the_estimate callback_scan_behind_is_ignored callback_unknown_scan_is_ignored)
GUARD_CASES=(three_real_tip_polls_mark_synced header_slack_is_inclusive below_the_newest_checkpoint_is_not_the_tip
             one_poll_off_the_tip_starts_again a_failed_checkpoint_counts_again unknown_heights_never_mark
             callback_marks_at_the_scanned_tip callback_below_the_checkpoint_is_ignored
             callback_height_zero_passes_as_android commit_defers_only_for_an_undurable_pending_ledger)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/sync_completion_kat_main.c" -o "$out"
}

if ! grep -q "SYNC_COMPLETION_RED" "$CORE_DIR/BRSyncCompletion.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRSyncCompletion.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DSYNC_COMPLETION_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DSYNC_COMPLETION_RED): every RED case MUST fail, every GUARD case pass ---"
out="$("$BUILD_DIR/red" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [red] GATE FAILURE: sanitizer report"; FAIL=1; }
[ $rc -eq 0 ] && { echo "  [red] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
for c in "${RED_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [red $c] failed, as it must"
    else echo "  [red $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
done
for c in "${GUARD_CASES[@]}"; do
    if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [red $c] GUARD held"
    else echo "  [red $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${RED_CASES[@]}" "${GUARD_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "sync_completion_kat: GATE HELD (RED marked synced at the header tip; GREEN waits for the scan)"
exit $FAIL
