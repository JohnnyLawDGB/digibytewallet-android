#!/usr/bin/env bash
# Host KAT runner for BROwnNode.h -- the own node's address, dgbnode:// link, IPv4 test, lookup
# rule and health (from CustomNode, OwnNodeUri, OwnNodeAddress and refreshOwnNodeHealth).
#
# Header-only: includes BROwnNode.h and links nothing but libc.
#
#   Four comparison arms, each with its own RED cases; in each arm its RED cases MUST fail and
#   every other case pass:
#     -DOWN_NODE_RED         ignores the Tor setting, so a host name is looked up beside Tor.
#     -DOWN_NODE_RED_HEALTH  status 1 reads connecting with no time limit ("Connecting..." forever).
#     -DOWN_NODE_RED_GAP     a gap between polls (a suspension), or a clock that went back, does not
#                            start a new run.
#     -DOWN_NODE_RED_PIN     the sync watchdogs pin over the own node.
#   GREEN  the shipped rules: every case passes, ALL PASS, exit 0. Every arm under ASan + UBSan.
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
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

ALL_CASES=(bare_host_gets_default_port host_with_port_parses blank_is_null empty_host_is_null bad_port_is_null
           out_of_range_port_is_null scheme_prefix_rejected ipv6_rejected uri_parses_scheme_host_port
           uri_defaults_port uri_captures_net_and_label uri_raw_host_port_falls_through uri_rejects_onion
           uri_rejects_bad_port uri_label_is_returned_whole uri_garbage_is_null uri_blank_is_null
           ipv4_used_as_given_tor_on_or_off tor_on_host_name_refused_not_looked_up tor_off_host_name_is_looked_up
           only_a_dotted_quad_is_an_address kotlin_trim_unicode_spaces port_as_kotlin_to_int_or_null
           typed_host_accepts_any_other_bytes host_longer_than_the_buffer_refused link_host_ascii_only
           url_decoder_quirks net_last_valid_wins label_last_wins_and_absence onion_case_insensitive_before_the_port
           health_mapping health_connecting_has_a_time_limit health_gap_edge_continues_the_run
           health_gap_starts_a_new_run health_clock_back_starts_a_new_run health_connection_ends_the_run health_fresh_track_and_no_track
           watchdog_pin_yields_to_own_node)
# arm macro -> its RED cases (space-separated)
ARMS=(OWN_NODE_RED OWN_NODE_RED_HEALTH OWN_NODE_RED_GAP OWN_NODE_RED_PIN)
red_cases_for() {
    case "$1" in
        OWN_NODE_RED)        echo "tor_on_host_name_refused_not_looked_up" ;;
        OWN_NODE_RED_HEALTH) echo "health_connecting_has_a_time_limit health_gap_edge_continues_the_run" ;;
        OWN_NODE_RED_GAP)    echo "health_gap_starts_a_new_run health_clock_back_starts_a_new_run" ;;
        OWN_NODE_RED_PIN)    echo "watchdog_pin_yields_to_own_node" ;;
    esac
}

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/own_node_kat_main.c" -o "$out"
}

for arm in "${ARMS[@]}"; do
    if ! grep -qw "$arm" "$CORE_DIR/BROwnNode.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BROwnNode.h missing or has no $arm seam; -D$arm would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }
for arm in "${ARMS[@]}"; do
    build "$BUILD_DIR/$arm" "-D$arm" || { echo "GATE FAILURE: comparison arm -D$arm did not compile."; exit 1; }
    reds=" $(red_cases_for "$arm") "
    echo "--- COMPARISON ARM (-D$arm): its RED cases MUST fail, every other case pass ---"
    out="$("$BUILD_DIR/$arm" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$arm] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [$arm] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in "${ALL_CASES[@]}"; do
        if [[ "$reds" == *" $c "* ]]; then
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$arm $c] failed, as it must"
            else echo "  [$arm $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi
        elif ! <<<"$out" grep -q "^RESULT $c pass$"; then
            echo "  [$arm $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1
        fi
    done
    echo "  [$arm] every other case held"
done

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in "${ALL_CASES[@]}"; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done

[ $FAIL -eq 0 ] && echo "own_node_kat: GATE HELD (each red arm failed exactly its cases: Tor lookup, connecting time limit, poll gap, watchdog pin; GREEN passes all)"
exit $FAIL
