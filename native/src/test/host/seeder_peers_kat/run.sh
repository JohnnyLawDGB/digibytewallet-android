#!/usr/bin/env bash
# Host KAT runner for BRSeederPeers.h -- which seeder peers are added, with which services,
# and when the list is fetched again (from Android's PeerServicesPolicy.kt, jni_peer.c and
# SeederRefreshPolicy.isStale, which now calls BRSeederShouldRefresh over JNI).
#
# Header-only: includes BRSeederPeers.h; BRPeerManagerAddPeer is a recording stub.
#
#   RED    two comparison arms, each failing only its own cases:
#          -DSEEDER_PEERS_RED    keeps the "0x" prefix and adds unknown peers with no services;
#          -DSEEDER_REFRESH_RED  lets an empty cache wait out the hour and refetches an hour-old
#                                list (the ">=" SyncWorker had before #187).
#          In each arm every one of its RED cases MUST fail and every other case pass.
#   GREEN  the shipped rule: every case passes, ALL PASS, exit 0. Every arm under ASan + UBSan.
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

PEERS_CASES="services_hex_parsed unknown_services_tagged_as_the_canon untagged_peer_added_filter_capable
             tagged_peer_keeps_its_services"
REFRESH_CASES="empty_cache_refreshes one_hour_old_is_kept"
GUARD_CASES="unreadable_services_are_unknown only_ipv4_literals_added refresh_interval_is_one_hour
             over_the_hour_refreshes never_fetched_refreshes fresh_list_kept differences_wrap_as_kotlin_long"
ALL_CASES="$PEERS_CASES $REFRESH_CASES $GUARD_CASES"

build() {
    local out="$1"; shift
    "$CC" -w -std=c99 -include stdint.h -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/seeder_peers_kat_main.c" -o "$out"
}

for seam in SEEDER_PEERS_RED SEEDER_REFRESH_RED; do
    if ! grep -q "$seam" "$CORE_DIR/BRSeederPeers.h" 2>/dev/null; then
        echo "GATE FAILURE: $CORE_DIR/BRSeederPeers.h missing or has no $seam seam; -D would be inert."
        exit 1
    fi
done

FAIL=0
build "$BUILD_DIR/red_peers" -DSEEDER_PEERS_RED || { echo "GATE FAILURE: SEEDER_PEERS_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/red_refresh" -DSEEDER_REFRESH_RED || { echo "GATE FAILURE: SEEDER_REFRESH_RED arm did not compile."; exit 1; }
build "$BUILD_DIR/green" || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

# comparison_arm <binary> <label> <cases that must fail>: every other case must pass.
comparison_arm() {
    local bin="$1" label="$2" red="$3" out rc c
    echo "--- COMPARISON ARM (-D$label): its RED cases MUST fail, every other case pass ---"
    out="$("$bin" 2>&1)"; rc=$?
    <<<"$out" grep -Eq "$SAN_RE" && { echo "  [$label] GATE FAILURE: sanitizer report"; FAIL=1; }
    [ $rc -eq 0 ] && { echo "  [$label] GATE FAILURE: the comparison arm exited 0; it proves nothing"; FAIL=1; }
    for c in $ALL_CASES; do
        case " $(echo $red) " in
        *" $c "*)
            if <<<"$out" grep -q "^RESULT $c fail$"; then echo "  [$label $c] failed, as it must"
            else echo "  [$label $c] GATE FAILURE: did not fail in the comparison arm"; FAIL=1; fi ;;
        *)
            if <<<"$out" grep -q "^RESULT $c pass$"; then echo "  [$label $c] GUARD held"
            else echo "  [$label $c] GATE FAILURE: GUARD case did not hold"; <<<"$out" grep "^FAIL: $c" | sed 's/^/      /'; FAIL=1; fi ;;
        esac
    done
}

comparison_arm "$BUILD_DIR/red_peers" SEEDER_PEERS_RED "$PEERS_CASES"
comparison_arm "$BUILD_DIR/red_refresh" SEEDER_REFRESH_RED "$REFRESH_CASES"

echo "--- SHIPPED ARM: every case MUST pass, ALL PASS, exit 0, no sanitizer report ---"
out="$("$BUILD_DIR/green" 2>&1)"; rc=$?
<<<"$out" grep -Eq "$SAN_RE" && { echo "  [green] GATE FAILURE: sanitizer report"; FAIL=1; }
if [ $rc -ne 0 ] || ! <<<"$out" grep -q "^ALL PASS$"; then
    echo "  [green] GATE FAILURE: shipped arm did not pass (rc=$rc)"; <<<"$out" grep "^FAIL:" | sed 's/^/      /'; FAIL=1
fi
for c in $ALL_CASES; do
    <<<"$out" grep -q "^RESULT $c pass$" && echo "  [green $c] passed" || { echo "  [green $c] GATE FAILURE: not passed"; FAIL=1; }
done
# A case the binary prints but this runner does not list would go unchecked.
for c in $(<<<"$out" sed -n 's/^RESULT \([^ ]*\) .*$/\1/p'); do
    case " $(echo $ALL_CASES) " in *" $c "*) ;; *) echo "  [green $c] GATE FAILURE: case not listed in run.sh"; FAIL=1 ;; esac
done

[ $FAIL -eq 0 ] && echo "seeder_peers_kat: GATE HELD (RED misread 0x44d, added untagged peers, and refreshed on the wrong lists; GREEN holds every rule)"
exit $FAIL
