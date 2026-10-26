#!/usr/bin/env bash
#
# wallet_read_guard_kat — no JNI entry point reads g_wallet while a create or restore frees it (4.1.2 row 6, N7).
# See the header of wallet_read_guard_kat_main.c.
#
#   RED    -DWALLET_READ_GUARD_UNFIXED under ThreadSanitizer: a race report (or a fault).
#   GREEN  ThreadSanitizer clean, the run finishes.
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
export TSAN_OPTIONS="halt_on_error=0 exitcode=66 report_signal_unsafe=0"

build "$BUILD_DIR/red" -DWALLET_READ_GUARD_UNFIXED || { echo "GATE FAILURE: the red arm did not compile"; exit 1; }
RED_OUT="$(timeout -k 5 300 "$BUILD_DIR/red" 2>&1)"; RED_RC=$?
if [ "$RED_RC" -ne 0 ] && <<<"$RED_OUT" grep -qE "WARNING: ThreadSanitizer: (data race|heap-use-after-free)|SEGV"; then
    echo "  [red] $(<<<"$RED_OUT" grep -m1 -oE 'ThreadSanitizer: [a-z-]+( [a-z-]+)?|SEGV'), as required"
else
    echo "GATE FAILURE: the red arm showed no race (rc=$RED_RC)"; <<<"$RED_OUT" tail -3; FAIL=1
fi
build "$BUILD_DIR/green" || { echo "GATE FAILURE: the green arm did not compile"; exit 1; }
GREEN_OUT="$(timeout -k 5 300 "$BUILD_DIR/green" 2>&1)"; GREEN_RC=$?
if [ "$GREEN_RC" -eq 0 ] && ! <<<"$GREEN_OUT" grep -q "ThreadSanitizer" && <<<"$GREEN_OUT" grep -q "swaps against"; then
    echo "  [green] TSan clean: $(<<<"$GREEN_OUT" grep -o '[0-9]* swaps against .*')"
else
    echo "GATE FAILURE: the green arm (rc=$GREEN_RC)"; <<<"$GREEN_OUT" grep -m3 -E "ThreadSanitizer|FAIL" ; FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then echo "PASS: wallet_read_guard_kat (red-then-green under TSan, source gates)"; exit 0; fi
echo "FAIL: wallet_read_guard_kat"; exit 1
