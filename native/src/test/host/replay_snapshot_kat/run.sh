#!/usr/bin/env bash
# Host KAT runner: the replay harness (core replay/BRReplay.h, design in the core's docs/replay-harness.md).
# A recorded sync is served to the real core by a fake peer on 127.0.0.1 and must give exactly the snapshot
# committed beside it. See replay_snapshot_kat_main.c for the invariants and the fixtures.
#
# Compiles the REAL, live submodule core (every BR*.c the apps compile, plus replay/BRReplay.c) with
# AddressSanitizer, 64-bit and 32-bit, at the shipped header check levels (run-host-kats.sh's clang shim).
# The run uses real threads and a real socket: BRPeerManagerConnect, pinned to the replay server as an
# exclusive own node, the way both apps pair one. Nothing leaves 127.0.0.1.
#
# ARMS. Green is no -D at all. Each red arm breaks one thing; its cases MUST FAIL, and every other case
# MUST PASS in that arm (so the failure is the defect, not a broken build):
#   -DREPLAY_RED_NO_BLOCKS              the server withholds every block the wallet's filters matched: the
#                                       payments never arrive, the snapshot differs (and the run is unfinished)
#   -DREPLAY_TAP_RED                    the core's message tap is never called: nothing is recorded
#   -DWALLET_OPEN_SINGLE_TREE_UNFIXED   the iOS wallet recipe before 2026-10-10 (BIP84 alone, no Taproot or
#                                       legacy tree; plan D2): the replay misses the Taproot output in
#                                       test-abandon-23924600, the severity-1 divergence this harness exists
#                                       to catch. The BIP84-only fixture still matches.
# Every case, every arm, both word sizes: no sanitizer report. A build error is never evidence.
#
# Every arm builds with -DBR_PEER_MESSAGE_TAP, the core's test-only message tap (BRPeer.h). Before the
# arms, a gate checks the tap cannot ship: BRPeer.c built without the flag has no tap symbol, and the
# flag with NDEBUG does not compile.
#
# CORE_DIR may be set to run this against another core checkout. bash 3.2 compatible (no associative
# arrays), so it runs on a Mac too.
set -uo pipefail   # not -e: the red arms' nonzero exits must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
FIXTURES="$CORE_DIR/replay/fixtures"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"

ARMS="REPLAY_RED_NO_BLOCKS REPLAY_TAP_RED WALLET_OPEN_SINGLE_TREE_UNFIXED"
red_cases() {   # the cases that must fail in arm $1
    case "$1" in
        REPLAY_RED_NO_BLOCKS) echo "fixture_24460800_matches fixture_23924600_matches recording_round_trip" ;;
        REPLAY_TAP_RED) echo "recording_round_trip" ;;
        WALLET_OPEN_SINGLE_TREE_UNFIXED) echo "fixture_23924600_matches" ;;
    esac
}
CASES="fixture_24460800_matches fixture_23924600_matches recording_round_trip recording_rejects_malformed
       relay_attempts_counted unknown_wallet_refused"

if [ ! -f "$CORE_DIR/replay/BRReplay.c" ] || [ ! -f "$FIXTURES/test-abandon-24460800.rec" ]; then
    echo "GATE FAILURE: $CORE_DIR has no replay harness (replay/BRReplay.c and its fixtures)"; exit 1
fi

# ---- the seams are real (-D silently loses to a plain #define; -w hides the warning) -------------
seam() { if ! grep -q "$1" "$CORE_DIR/$2"; then echo "GATE FAILURE: $2 has no $1 seam; -D would be inert."; exit 1; fi; }
seam REPLAY_RED_NO_BLOCKS replay/BRReplay.c
seam REPLAY_TAP_RED BRPeer.c
seam WALLET_OPEN_SINGLE_TREE_UNFIXED BRWalletOpen.h

# Every core unit the apps build: the top-level BR*.c (the iOS XCFramework's own selection, less the
# *Tests.c files) and the hash implementations.
UNITS=()
for f in "$CORE_DIR"/BR*.c "$CORE_DIR"/crypto/*.c "$CORE_DIR"/crypto/sha3/*.c; do
    case "$f" in *Tests.c) continue ;; esac
    UNITS+=("$f")
done

# build <out> <bits: 64|32> [extra -D flags...]
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DBR_PEER_MESSAGE_TAP: the test-only build of the core's message tap (BRPeer.h), for the round trip.
    "${CC:-clang}" -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer ${m[@]+"${m[@]}"} \
        -DBR_PEER_MESSAGE_TAP "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/replay" -I "$CORE_DIR/secp256k1/include" -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/replay_snapshot_kat_main.c" "$CORE_DIR/replay/BRReplay.c" "${UNITS[@]}" \
        -lm -lpthread -o "$out"
}

FAIL=0

# ---- the tap never ships ------------------------------------------------------------------------
# A core built as the apps build it (no BR_PEER_MESSAGE_TAP) has no tap code: no symbol for it in
# BRPeer.o, optimised or not. And the flag together with NDEBUG, which every release build defines,
# does not compile.
tap_gate() {
    local o="$BUILD_DIR/brpeer.o" opt syms
    for opt in -O0 -O2 "-O2 -DNDEBUG"; do
        # shellcheck disable=SC2086
        if ! "${CC:-clang}" -c -w -include stdint.h $opt -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
                "$CORE_DIR/BRPeer.c" -o "$o"; then
            echo "GATE FAILURE: BRPeer.c without the tap flag did not compile ($opt)"; FAIL=1; continue
        fi
        syms="$(nm "$o" | grep -i 'MessageTap\|TapMessage' || true)"
        if [ -n "$syms" ]; then echo "GATE FAILURE: a build without BR_PEER_MESSAGE_TAP ($opt) has tap symbols:"; echo "$syms"; FAIL=1
        else echo "  [tap] no tap symbol in BRPeer.o built without the flag ($opt)"; fi
    done
    local err
    err="$("${CC:-clang}" -c -w -include stdint.h -DBR_PEER_MESSAGE_TAP -DNDEBUG -I "$CORE_DIR" \
            -I "$CORE_DIR/secp256k1/include" "$CORE_DIR/BRPeer.c" -o "$o" 2>&1)" && {
        echo "GATE FAILURE: BR_PEER_MESSAGE_TAP with NDEBUG compiled"; FAIL=1; return; }
    if <<<"$err" grep -q "must not reach a release"; then echo "  [tap] the flag with NDEBUG is a compile error, as required"
    else echo "GATE FAILURE: the flag with NDEBUG failed to compile for another reason:"; <<<"$err" head -5; FAIL=1; fi
}
echo "--- the tap is test-only ---"
tap_gate

run_case() {   # run_case <bin> <case> -> OUT, RC
    local tmp; tmp="$(mktemp -d "$BUILD_DIR/case.XXXXXX")"
    OUT="$("$1" "$2" "$FIXTURES" "$tmp" 2>&1)"; RC=$?
}
san() { <<<"$OUT" grep -Eq "$SAN_RE"; }
is_red() { case " $(red_cases "$1") " in *" $2 "*) return 0 ;; esac; return 1; }

can_build_32() {
    printf '#include <stdint.h>\nint main(void){return 0;}\n' > "$BUILD_DIR/probe.c"
    "${CC:-clang}" -m32 -fsanitize=address "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe" >/dev/null 2>&1
}

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."; FAIL=1; return
    fi
    local known listed
    known="$("$BUILD_DIR/green${bits}" | sort)"
    listed="$(printf '%s\n' $CASES | sort)"
    if [ "$known" != "$listed" ]; then
        echo "GATE FAILURE: the case list in run.sh and the binary differ:"; diff <(echo "$known") <(echo "$listed"); FAIL=1
    fi

    echo "--- GREEN: every case PASSES, no sanitizer report ---"
    for c in $CASES; do
        run_case "$BUILD_DIR/green${bits}" "$c"
        if san; then echo "  [$bits green $c] GATE FAILURE: sanitizer report"; <<<"$OUT" grep -E -A12 "$SAN_RE" | head -30; FAIL=1
        elif [ $RC -eq 0 ] && <<<"$OUT" grep -q "RESULT $c PASS"; then echo "  [$bits green $c] PASS"
        else echo "  [$bits green $c] GATE FAILURE: did not pass (rc=$RC)"; <<<"$OUT" sed 's/^/      /'; FAIL=1; fi
    done

    for a in $ARMS; do
        if ! build "$BUILD_DIR/red_${a}_${bits}" "$bits" "-D$a"; then
            echo "GATE FAILURE: ${bits}-bit -D$a arm did not compile."; FAIL=1; continue
        fi
        echo "--- RED -D$a: $(red_cases "$a") MUST FAIL; every other case MUST PASS ---"
        for c in $CASES; do
            run_case "$BUILD_DIR/red_${a}_${bits}" "$c"
            if san; then echo "  [$bits $a $c] GATE FAILURE: sanitizer report"; <<<"$OUT" grep -E -A12 "$SAN_RE" | head -30; FAIL=1
            elif is_red "$a" "$c"; then
                if [ $RC -ne 0 ] && <<<"$OUT" grep -q "RESULT $c FAIL"; then
                    echo "  [$bits $a $c] failed, as required: $(<<<"$OUT" grep -m1 '  FAIL' | sed 's/^ *FAIL *//')"
                else echo "  [$bits $a $c] GATE FAILURE: passed with the defect in (rc=$RC) -- the check is not load-bearing"; FAIL=1; fi
            elif [ $RC -eq 0 ] && <<<"$OUT" grep -q "RESULT $c PASS"; then echo "  [$bits $a $c] holds"
            else echo "  [$bits $a $c] GATE FAILURE: failed in an arm that does not break it (rc=$RC)"; <<<"$OUT" grep FAIL | sed 's/^/      /'; FAIL=1; fi
        done
    done
}

run_bits 64
if can_build_32; then
    run_bits 32
elif [ -n "${CI:-}" ]; then
    echo "GATE FAILURE: no 32-bit toolchain in CI (install gcc-multilib)"; FAIL=1
else
    echo "(32-bit arm skipped: this host cannot build -m32 with AddressSanitizer)"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: replay_snapshot_kat (every red case failed in its arm, every other case passed; green all passed)"
    exit 0
fi
echo "FAIL: replay_snapshot_kat"
exit 1
