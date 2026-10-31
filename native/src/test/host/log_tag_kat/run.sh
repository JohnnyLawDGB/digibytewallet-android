#!/usr/bin/env bash
#
# log_tag_kat — transaction log tags, and no txid prefix in a native log line (4.1.3, review R7).
#   KAT   BRLogTxTag / BRLogTxTagHex against independent HMAC-SHA256 known answers (the ones LogTagTest uses).
#   GATE  no peer_log / LOG* line in the core or the bridge prints a transaction hash or txid by a prefix
#         (%.Ns with u256hex / log_u256_hex_encode of a tx hash, or with a txid string): they use BR_LOG_TX /
#         BRLogTxTagHex. A red arm here is the gate itself, shown failing on a probe.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
FAIL=0
shopt -s nullglob; SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob

gate() {   # dir... -> offending lines
    grep -nE '(peer_log|_peer_log|LOG[DIWE])\(.*%\.[0-9]+s' "$@" 2>/dev/null |
        grep -E 'txid|txHash|TxHash|tx->txHash|u256hex\(|log_u256_hex_encode\(' |
        grep -vE 'BR_LOG_TX|BRLogTxTag' || true
}
HITS="$(gate "$CORE_DIR"/BR*.c "$BRIDGE_DIR"/*.c)"
if [ -n "$HITS" ]; then echo "GATE FAILURE: a log line names a transaction by a prefix:"; echo "$HITS" | head; FAIL=1
else echo "  [gate] no native log line prints a txid prefix"; fi
PROBE="$BUILD_DIR/probe.c"
printf 'void f(void){ peer_log(peer, "relayed tx: %%.12s", u256hex(tx->txHash)); }\n' > "$PROBE"
[ -n "$(gate "$PROBE")" ] && echo "  [gate] the gate catches a probe line" || { echo "GATE FAILURE: the gate missed a probe line"; FAIL=1; }

clang -w -include stdint.h -g -fsanitize=address -I "$CORE_DIR" "$SCRIPT_DIR/log_tag_kat_main.c" "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" -o "$BUILD_DIR/kat" || { echo "GATE FAILURE: did not build"; exit 1; }
OUT="$("$BUILD_DIR/kat" 2>&1)"; RC=$?
<<<"$OUT" grep -E '^(PASS|FAIL)' | sed 's/^/  /'
[ "$RC" -eq 0 ] || FAIL=1
[ "$FAIL" -eq 0 ] && { echo "PASS: log_tag_kat"; exit 0; }
echo "FAIL: log_tag_kat"; exit 1
