#!/usr/bin/env bash
#
# wallet_read_guard_kat — no JNI entry point reads g_wallet while a create or restore frees it (4.1.2 row 6, N7).
# See the header of wallet_read_guard_kat_main.c.
#
#   RED    -DWALLET_READ_GUARD_UNFIXED under ThreadSanitizer, `interleave <reader>`, one process per reader
#          (TSan reports a word once per process): a data race inside the step of EVERY reader, on every
#          run (KAT-RACE: the steps are ordered by relaxed atomics, not by timing).
#   GREEN  both phases (interleave + stress) ThreadSanitizer clean, the run finishes.
#   RUNS=N runs each arm N times from one build (default 1); every run must meet its arm's expectation.
#   Every arm runs under bounded() (lesson: an ASan/TSan report can wedge instead of exiting); a run
#   stopped at the limit -- by bounded() or by the binary's own alarm just inside it -- fails with its own
#   wedge message, whether or not it printed a report first.
#   The red arm counts only a race on g_wallet whose racing access is the reader's own frame.
#   GATES  every NativeBridge_* that names g_wallet takes PEER_GUARD() or WALLET_READ_GUARD(); both frees of
#          g_wallet sit inside the write side; no WALLET_READ_GUARD reader takes PEER_GUARD.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
gate_fail() { echo "GATE FAILURE: $*"; FAIL=1; }

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

GATE_OUT="$(python3 - "$BRIDGE_DIR" <<'PY'
import re, glob, sys, os
d = sys.argv[1]
src = {os.path.basename(f): open(f).read() for f in glob.glob(os.path.join(d, '*.c'))}
def bodies(s):
    for m in re.finditer(r'\n(?:static\s+)?[\w\s\*]+?\b(\w+)\(([^;{]*?)\)\s*\{', s):
        b = s.index('{', m.end() - 1); depth = 0; i = b
        while True:
            if s[i] == '{': depth += 1
            elif s[i] == '}':
                depth -= 1
                if depth == 0: break
            i += 1
        yield m.group(1), s[b:i]
allb = {}
for f, s in src.items():
    for n, b in bodies(s): allb[n] = b
bad = [n for n, b in allb.items() if n.startswith('NativeBridge_') and 'g_wallet' in b
       and 'PEER_GUARD()' not in b and 'WALLET_READ_GUARD()' not in b]
print('UNGUARDED', ' '.join(sorted(bad)))
guarded = {n for n, b in allb.items() if 'PEER_GUARD()' in b}
changed = True
while changed:
    changed = False
    for n, b in allb.items():
        if n not in guarded and any(re.search(r'\b' + g + r'\(', b) for g in guarded if not g.startswith('NativeBridge_')):
            guarded.add(n); changed = True
nest = [n for n, b in allb.items() if 'WALLET_READ_GUARD()' in b and n in guarded]
print('NESTED', ' '.join(sorted(nest)))
w = src['jni_wallet.c']
frees = [m.start() for m in re.finditer(r'BRWalletFree\(g_wallet\)', w)]
inside = 0
for at in frees:
    before = w[:at]
    if before.rfind('pthread_rwlock_wrlock(&g_walletRwLock)') > before.rfind('pthread_rwlock_unlock(&g_walletRwLock)'): inside += 1
print('FREES', len(frees), inside)
PY
)"
UNG="$(<<<"$GATE_OUT" sed -n 's/^UNGUARDED //p')"; NEST="$(<<<"$GATE_OUT" sed -n 's/^NESTED //p')"
read -r NFREE NIN <<<"$(<<<"$GATE_OUT" sed -n 's/^FREES //p')"
[ -z "$UNG" ] || gate_fail "JNI entry points read g_wallet with no guard: $UNG"
[ -z "$NEST" ] || gate_fail "a WALLET_READ_GUARD reader reaches PEER_GUARD (deadlock against the swap): $NEST"
[ "${NFREE:-0}" -ge 2 ] && [ "$NFREE" = "$NIN" ] || gate_fail "g_wallet is freed $NFREE times, $NIN of them inside the write side"

build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" -fsanitize=thread -fno-omit-frame-pointer -g -O1 \
        -I "$SCRIPT_DIR/../identity_sign_bytes_kat/host_jni" -I "$BRIDGE_DIR" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/wallet_read_guard_kat_main.c" \
        "$BRIDGE_DIR/jni_wallet_sign.c" "$BRIDGE_DIR/jni_transaction_persist.c" "$BRIDGE_DIR/jni_wallet.c" \
        "$BRIDGE_DIR/coin_reservation.c" "$BRIDGE_DIR/slip13.c" \
        "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c" "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c" "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c" "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" "${SHA3_SRCS[@]}" -lm -lpthread -o "$out"
}
# suppress_equal_addresses=0: every reader's race is on the same word (g_wallet); TSan's default prints
# only the first of them, and the red arm must see all four.
export TSAN_OPTIONS="halt_on_error=0 exitcode=66 report_signal_unsafe=0 suppress_equal_addresses=0"
RUNS="${RUNS:-1}"
LIMIT_S="${LIMIT_S:-300}"
# The binary's own in-process alarm fires a margin INSIDE this watchdog, so it always goes first and a
# raised LIMIT_S raises it too. On firing it prints ALARM_MARK and exits 142; both are reported as a wedge.
export WALLET_READ_GUARD_ALARM_S=$(( LIMIT_S > 30 ? LIMIT_S - 15 : (LIMIT_S > 1 ? LIMIT_S - 1 : 1) ))
ALARM_MARK="[watchdog] stopped by the in-process alarm"
READERS=(getBalance getReceiveAddress getTransactionCount getTransactionDetails)

# The watchdog leaves <outfile>.stopped BEFORE it kills, and that file alone says the run was stopped
# (the same helper as dd_fee_profiles_kat / base58_input_bound_kat, #211).
bounded() {   # <outfile> <cmd...>: the command's exit code, or 124 if it was killed at the limit
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

# The races on g_wallet that reader <name> itself made inside its interleave step (from its marker to the
# next marker). A report counts only if its block says `Location is global 'g_wallet'` AND its first #0
# frame -- the racing access -- is NativeBridge_<name>: an unrelated race inside the step (a static cache
# in a helper, say) must not stand in for the one this arm exists to prove.
step_races() {   # <outfile> <reader>
    awk -v m="   [step] $2 after a swap" -v fn="NativeBridge_$2" -v loc="Location is global 'g_wallet'" '
        index($0, "   [step] ") == 1 || index($0, "   [interleave] ") == 1 { on = ($0 == m); rep = 0; next }
        on && /WARNING: ThreadSanitizer: data race/ { rep = 1; first = ""; glob = 0; next }
        rep && first == "" && $1 == "#0" { first = $2 }
        rep && index($0, loc) { glob = 1 }
        rep && index($0, "SUMMARY: ThreadSanitizer: data race") == 1 { if (glob && first == fn) n++; rep = 0 }
        END { print n + 0 }' "$1"
}

# Was the run stopped rather than finished: by the script's watchdog (rc 124) or by the binary's own alarm
# (its marker, or rc 142 = SIGALRM if the handler never ran)? Sets STOPPED_BY to say which.
stopped_by() {   # <rc> <outfile>
    if [ "$1" -eq 124 ]; then STOPPED_BY="stopped by the watchdog at ${LIMIT_S}s"; return 0; fi
    if [ "$1" -eq 142 ] || grep -qF "$ALARM_MARK" "$2"; then
        STOPPED_BY="stopped by the in-process alarm at ${WALLET_READ_GUARD_ALARM_S}s, rc=$1"; return 0
    fi
    return 1
}

red_ok=0; green_ok=0
build "$BUILD_DIR/red" -DWALLET_READ_GUARD_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
for ((r = 1; r <= RUNS; r++)); do
    missing=""
    for f in "${READERS[@]}"; do
        out="$BUILD_DIR/red.$r.$f.out"
        bounded "$out" "$BUILD_DIR/red" interleave "$f"; rc=$?
        if stopped_by "$rc" "$out"; then
            if grep -q "WARNING: ThreadSanitizer" "$out"; then
                echo "GATE FAILURE: red run $r ($f) printed a report, then wedged ($STOPPED_BY)"
            else
                echo "GATE FAILURE: red run $r ($f) wedged with NO report ($STOPPED_BY)"
            fi
            tail -3 "$out"; missing="$missing $f"; continue
        fi
        if [ "$rc" -ne 66 ] || [ "$(step_races "$out" "$f")" -lt 1 ] \
           || ! grep -q "\[interleave\] 1 swap-then-read steps finished" "$out"; then
            echo "GATE FAILURE: red run $r (rc=$rc) showed no race in the step of $f"; tail -3 "$out"
            missing="$missing $f"
        fi
    done
    if [ -z "$missing" ]; then red_ok=$((red_ok + 1)); else FAIL=1; fi
done
echo "  [red] $red_ok/$RUNS runs: a TSan data race in every reader's step (${READERS[*]}), as required"

build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
for ((r = 1; r <= RUNS; r++)); do
    out="$BUILD_DIR/green.$r.out"
    bounded "$out" "$BUILD_DIR/green" all; rc=$?
    if [ "$rc" -eq 0 ] && ! grep -q "ThreadSanitizer" "$out" && grep -q "swap-then-read steps finished" "$out" \
       && grep -q "swaps against" "$out"; then
        green_ok=$((green_ok + 1))
    else
        stopped_by "$rc" "$out" && echo "GATE FAILURE: green run $r wedged ($STOPPED_BY)"
        echo "GATE FAILURE: green run $r (rc=$rc)"; grep -m3 -E "ThreadSanitizer|FAIL" "$out"; FAIL=1
    fi
done
echo "  [green] $green_ok/$RUNS runs TSan clean: $(grep -ho '[0-9]* swaps against .*' "$BUILD_DIR"/green.*.out | head -1)"

if [ "$FAIL" -eq 0 ]; then echo "PASS: wallet_read_guard_kat (red-then-green under TSan, source gates)"; exit 0; fi
echo "FAIL: wallet_read_guard_kat"; exit 1
