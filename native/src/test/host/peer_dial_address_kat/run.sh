#!/usr/bin/env bash
# Host KAT runner for BRPeerDialAddress.h -- the socket address the core dials for a peer,
# so an IPv4 peer is reachable on an IPv6-only (DNS64/NAT64) network.
#
# Header-only: includes BRPeerDialAddress.h with its resolver replaced by a stub that
# simulates the networks, and links nothing but libc. A source gate also checks that
# BRPeer.c dials through the header (a header nothing calls would pass vacuously).
#
#   RED    -DPEER_DIAL_ADDRESS_NAT64_UNFIXED dials the IPv4 address with no resolver, as the
#          core did before 2026-10. Every RED case MUST fail; every GUARD case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Both arms under ASan.
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

RED_CASES=(nat64_dials_synthesized_ipv6 nat64_dial_carries_the_peers_port
           resolver_asked_for_the_literal resolver_answer_freed)
GUARD_CASES=(ipv4_network_dials_the_same_address resolver_failure_dials_ipv4
             unusable_answers_dial_ipv4 native_ipv6_peer_dialled_directly ipv4_mapped_detection)

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -D_DEFAULT_SOURCE -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/peer_dial_address_kat_main.c" -o "$out"
}

if ! grep -q "PEER_DIAL_ADDRESS_NAT64_UNFIXED" "$CORE_DIR/BRPeerDialAddress.h" 2>/dev/null; then
    echo "GATE FAILURE: $CORE_DIR/BRPeerDialAddress.h missing or has no RED seam; -D would be inert."
    exit 1
fi
# Source gate: the peer's direct connect must dial through the header.
if ! grep -q '#include "BRPeerDialAddress.h"' "$CORE_DIR/BRPeer.c" || ! grep -q "BRPeerDialAddress(peer->address" "$CORE_DIR/BRPeer.c"; then
    echo "GATE FAILURE: BRPeer.c does not dial through BRPeerDialAddress.h."
    exit 1
fi

FAIL=0
build "$BUILD_DIR/red" -DPEER_DIAL_ADDRESS_NAT64_UNFIXED || { echo "GATE FAILURE: comparison arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

echo "--- COMPARISON ARM (-DPEER_DIAL_ADDRESS_NAT64_UNFIXED): every RED case MUST fail, every GUARD case pass ---"
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

[ $FAIL -eq 0 ] && echo "peer_dial_address_kat: GATE HELD (RED dialled IPv4 on a NAT64 network; GREEN dialled the synthesized IPv6)"
exit $FAIL
