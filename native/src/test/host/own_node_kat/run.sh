#!/usr/bin/env bash
# Host KAT runner for BROwnNode.h -- the own node's address, dgbnode:// link, IPv4 test, lookup
# rule and health (from CustomNode, OwnNodeUri, OwnNodeAddress and refreshOwnNodeHealth).
#
# Header-only: includes BROwnNode.h and links nothing but libc.
#
#   RED    -DOWN_NODE_RED ignores the Tor setting, so a host name is looked up beside Tor. Every
#          RED case MUST fail; every GUARD case pass.
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

RED_CASES=(tor_on_host_name_refused_not_looked_up)
GUARD_CASES=(bare_host_gets_default_port host_with_port_parses blank_is_null empty_host_is_null bad_port_is_null
             out_of_range_port_is_null scheme_prefix_rejected ipv6_rejected uri_parses_scheme_host_port
             uri_defaults_port uri_captures_net_and_label uri_raw_host_port_falls_through uri_rejects_onion
             uri_rejects_bad_port uri_label_is_returned_whole uri_garbage_is_null uri_blank_is_null
             ipv4_used_as_given_tor_on_or_off tor_off_host_name_is_looked_up only_a_dotted_quad_is_an_address
             kotlin_trim_unicode_spaces port_as_kotlin_to_int_or_null typed_host_accepts_any_other_bytes
             host_longer_than_the_buffer_refused link_host_ascii_only url_decoder_quirks net_last_valid_wins
             label_last_wins_and_absence onion_case_insensitive_before_the_port health_mapping)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/own_node_kat_main.c" -o "$out"
}

if ! grep -q "OWN_NODE_RED" "$CORE_DIR/BROwnNode.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BROwnNode.h missing or has no RED seam; -D would be inert."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DOWN_NODE_RED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DOWN_NODE_RED): every RED case MUST fail, every GUARD case pass ---"
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

[ $FAIL -eq 0 ] && echo "own_node_kat: GATE HELD (RED looked a host name up beside Tor; GREEN refuses it while Tor is on)"
exit $FAIL
