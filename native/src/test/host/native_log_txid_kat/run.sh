#!/usr/bin/env bash
#
# native_log_txid_kat — no bridge log line prints a full txid (4.1.2 row 7, K1).
#
# LOGD/LOGI/LOGW/LOGE are __android_log_print in every build, release included, so a txid printed whole
# ties the device's logcat to the user's on-chain transactions. The bridge prints the first 12 characters
# (%.12s), as the Kotlin side does (LogRedactionSourceGateTest). This gate reads every bridge source: a log
# call whose format names a txid or hash must not format it with a bare %s.
#   RED   the committed 4.1.1-line jni_transaction.c (checked by the gate itself below, when git is present).
#   GREEN no such line.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
check() {   # $1: directory -> prints offending lines: a txid formatted whole (txid=%s, dropped %s, %s is still held)
    grep -nE 'LOG[DIWE]\(.*(txid=%s|dropped %s|%s is still held)' "$1"/*.c || true
}
BAD="$(check "$BRIDGE_DIR")"
if [ -n "$BAD" ]; then echo "GATE FAILURE: a bridge log line prints a full txid:"; echo "$BAD"; echo "FAIL: native_log_txid_kat"; exit 1; fi
# The core's own log lines (peer_log / _peer_log, in every build): a txid argument is printed as %.12s.
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
CORE_BAD="$(python3 - "$CORE_DIR" <<'PY'
import re, sys, os
bad = []
for f in ("BRPeer.c", "BRPeerManager.c"):
    src = open(os.path.join(sys.argv[1], f)).read()
    for m in re.finditer(r'\b_?peer_log\((.*?)\);', src, re.S):
        call = " ".join(m.group(1).split())
        if re.search(r'(u256hex|log_u256_hex_encode)\((tx->txHash|txHash)\)', call) and re.search(r'(tx|txid)[^"]*?%s', call):
            bad.append(f + ": " + call[:120])
print("\n".join(bad))
PY
)"
if [ -n "$CORE_BAD" ]; then echo "GATE FAILURE: a core log line prints a full txid:"; echo "$CORE_BAD"; echo "FAIL: native_log_txid_kat"; exit 1; fi

# The gate must see the shape it is written for.
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
printf '%s\n' '    LOGD("publishTransaction: submitted txid=%s", txidHex);' > "$T/probe.c"
[ -n "$(check "$T")" ] || { echo "GATE FAILURE: the gate does not see a full-txid log line"; exit 1; }
if git -C "$REPO_ROOT" rev-parse --verify -q v4.1.1 >/dev/null 2>&1 || git -C "$REPO_ROOT" rev-parse --verify -q 8c2a63ce8 >/dev/null 2>&1; then
    REF=$(git -C "$REPO_ROOT" rev-parse --verify -q v4.1.1 || echo 8c2a63ce8)
    mkdir -p "$T/old"; git -C "$REPO_ROOT" show "$REF:native/src/main/jni/bridge/jni_transaction.c" > "$T/old/jni_transaction.c"
    [ -n "$(check "$T/old")" ] || { echo "GATE FAILURE: the gate finds nothing in the 4.1.1 bridge, which printed full txids"; exit 1; }
fi
echo "PASS: native_log_txid_kat (no bridge log line prints a full txid; the gate sees the 4.1.1 shape)"
