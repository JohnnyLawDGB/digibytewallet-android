#!/usr/bin/env bash
# Host KAT runner for BRCFAbandonmentRecord.h -- the abandoned compact-filter band's record and
# the poll that keeps it (from CfAbandonmentStore and WalletViewModel's poll).
#
# Header-only: includes BRCFAbandonmentRecord.h (and BRCFAbandonment.h) and links nothing but libc.
#
#   RED    -DCF_ABANDONMENT_RECORD_RED recovers on phase 2 without phase 1 (the R1 bug: a
#          reconnect at the band's top cleared a band the scan never covered). Every RED case
#          MUST fail; every GUARD case pass.
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

RED_CASES=(frontier_never_seen_inside_is_not_recovery zero_frontier_is_not_an_observation
           new_abandonment_resets_the_witness one_height_band_needs_the_witness
           poll_reinit_at_the_top_is_not_recovery)
GUARD_CASES=(no_band_nothing_to_surface surfaces_until_recovered abandonment_after_recovery_resurfaces
             re_polling_same_watermark_does_not_unrecover scan_passed_band_after_reinit_recovers
             in_band_observation_persists_across_phases watermark_cleared_scan_not_past_the_band
             watermark_still_standing_is_never_recovery scan_coverage_noop_without_an_unrecovered_band
             clear_forgets_the_band poll_captures_the_low_hint_while_pending poll_without_a_hint_marks_low_unknown
             poll_rereading_a_standing_watermark_changes_nothing poll_two_phase_heal poll_ledger_proves_coverage
             poll_unknown_low_uses_the_ledger_start poll_a_ledger_with_a_hole_is_not_proof
             poll_failed_reads_are_not_evidence poll_failed_frontier_read_keeps_the_hint
             partial_retirement_is_neither_witness_nor_recovery mark_recovered_twice_reports_no_change)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/cf_abandonment_record_kat_main.c" -o "$out"
}

if ! grep -q "CF_ABANDONMENT_RECORD_RED" "$CORE_DIR/BRCFAbandonmentRecord.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRCFAbandonmentRecord.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DCF_ABANDONMENT_RECORD_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DCF_ABANDONMENT_RECORD_RED): every RED case MUST fail, every GUARD case pass ---"
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

[ $FAIL -eq 0 ] && echo "cf_abandonment_record_kat: GATE HELD (RED cleared a band it never saw scanned; GREEN needs the in-band witness first)"
exit $FAIL
