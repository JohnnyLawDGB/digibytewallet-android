#!/usr/bin/env bash
# msg_len_kat -- MSG-LEN: a message signed by signMessage is encoded with a full compact size (or refused);
# here, encoded. See the _main.c for the cases.
#   RED    -DMSG_LEN_UNFIXED=1: the encoder before this item. Cases A-B must FAIL on their named checks
#          (A on the three lengths in 0x10000..0xffffffff and on 2^32); every guard (C) must still pass.
#   GREEN  every check passes; no sanitizer report. 64- and 32-bit.
# Then a SOURCE GATE over the real jni_wallet_sign.c: it builds what it signs with signed_message_payload and
# keeps no encoder of its own. MSG_LEN_GATE_FILE=<path> points the gate at another copy.
# MACRO CONVENTION: VALUE of -DMSG_LEN_UNFIXED.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
HEADER="$BRIDGE_DIR/signed_message.h"
JNI="${MSG_LEN_GATE_FILE:-$BRIDGE_DIR/jni_wallet_sign.c}"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: (AddressSanitizer|LeakSanitizer)|runtime error:'

[ -f "$HEADER" ] || { echo "GATE FAILURE: $HEADER not found"; exit 1; }
grep -Eq '^[[:space:]]*#[[:space:]]*if[[:space:]]+MSG_LEN_UNFIXED' "$HEADER" \
    || { echo "GATE FAILURE: the header does not test the VALUE of MSG_LEN_UNFIXED"; exit 1; }

build() {   # <out> <unfixed> <bits>
    local m=(); [ "$3" = 32 ] && m=(-m32)
    clang -w -g "${m[@]}" -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer \
        -DMSG_LEN_UNFIXED="$2" -I "$BRIDGE_DIR" "$SCRIPT_DIR/msg_len_kat_main.c" -o "$1"
}
FAIL=0
RED_MUST_FAIL=(
    "^FAIL: A: length 65536 is not a full compact size"
    "^FAIL: A: length 70000 is not a full compact size"
    "^FAIL: A: length 4294967295 is not a full compact size"
    "^FAIL: A: length 4294967296 is not a full compact size"
    "^FAIL: B: a 70,000-byte message's payload does not read back to it"
)
for bits in 64 32; do
    build "$BUILD_DIR/red$bits" 1 "$bits" || { echo "GATE FAILURE: red$bits did not build (not evidence)"; FAIL=1; continue; }
    OUT="$(timeout -k 5 60 "$BUILD_DIR/red$bits" 2>&1)"; RC=$?
    ok=1
    [ "$RC" -ne 0 ] && [ "$RC" -ne 124 ] || ok=0
    <<<"$OUT" grep -qE "$SAN_RE" && ok=0
    for want in "${RED_MUST_FAIL[@]}"; do <<<"$OUT" grep -q "$want" || { echo "  [red$bits] did not fail: $want"; ok=0; }; done
    EXTRA="$(<<<"$OUT" grep "^FAIL" | grep -vE "^FAIL: (A|B): ")"
    [ -z "$EXTRA" ] || { echo "  [red$bits] an unexpected check failed: $EXTRA"; ok=0; }
    if [ "$ok" -eq 1 ]; then echo "  [red$bits] ${#RED_MUST_FAIL[@]} named checks failed, as required; guards held"
    else echo "GATE FAILURE: red$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head; FAIL=1; fi

    build "$BUILD_DIR/green$bits" 0 "$bits" || { echo "GATE FAILURE: green$bits did not build"; FAIL=1; continue; }
    OUT="$(timeout -k 5 60 "$BUILD_DIR/green$bits" 2>&1)"; RC=$?
    if [ "$RC" -eq 0 ] && ! <<<"$OUT" grep -qE "$SAN_RE" && <<<"$OUT" grep -q "^PASS: msg_len_kat"; then
        echo "  [green$bits] all $(<<<"$OUT" grep -c '^PASS:') checks pass"
    else echo "GATE FAILURE: green$bits (rc=$RC)"; <<<"$OUT" grep -E "FAIL|ERROR|runtime" | head; FAIL=1; fi
done

gate_fail=0
say() { echo "  gate: $*"; gate_fail=1; }
[ -f "$JNI" ] || { echo "GATE FAILURE: $JNI not found -- the gate would observe nothing"; exit 1; }
STRIPPED="$(sed -e 's@//.*$@@' "$JNI" | awk 'BEGIN{c=0} { line=$0; out="";
    while (length(line)) {
        if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
        else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
               out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
    print out }')"
grep -Eq '#[[:space:]]*include[[:space:]]+"signed_message\.h"' <<<"$STRIPPED" || say "jni_wallet_sign.c does not include signed_message.h"
[ "$(grep -cE 'signed_message_payload[[:space:]]*\(' <<<"$STRIPPED")" -eq 1 ] || say "the signing core does not build its payload with signed_message_payload exactly once"
grep -Eq 'write_varint|0xfd' <<<"$STRIPPED" && say "jni_wallet_sign.c keeps a length encoder of its own"
grep -Eq 'Signed Message' <<<"$STRIPPED" && say "jni_wallet_sign.c spells the message header itself"
if [ "$gate_fail" -eq 0 ]; then echo "  gate: jni_wallet_sign.c signs the payload signed_message_payload builds, and encodes nothing itself"
else echo "SOURCE GATE FAILED"; FAIL=1; fi
[ "$FAIL" -eq 0 ] && { echo "PASS: msg_len_kat (A-B red-then-green; C guards; 64/32-bit, ASan+UBSan; source gate)"; exit 0; }
echo "FAIL: msg_len_kat"; exit 1
