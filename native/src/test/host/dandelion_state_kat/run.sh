#!/usr/bin/env bash
# Host KAT runner for bridge/dandelion_state.h (B234), plus a source check that jni_peer.c
# remembers the Dandelion setting and capable peers whether or not a peer manager exists, and
# replays them onto every manager startSync creates.
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same reasons as bip340_kat/run.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    -I "$BRIDGE_DIR" \
    "$SCRIPT_DIR/dandelion_state_kat_main.c" \
    -o "$BUILD_DIR/dandelion_state_kat"

"$BUILD_DIR/dandelion_state_kat"

# Wiring: the header is only useful if the bridge feeds it and replays it.
PEER="$BRIDGE_DIR/jni_peer.c"
fail=0
wire() { if grep -Eq "$1" "$PEER"; then echo "ok: $2"; else echo "FAIL: $2"; fail=1; fi; }
wire 'dandelion_state_set_enabled\(&g_dandelion,' "setDandelionEnabled remembers the setting"
wire 'dandelion_state_add\(&g_dandelion,' "addDandelionPeer remembers the address"
# The replay must directly follow the BIP158 re-apply in startSync's creation path.
if grep -A1 -E '^\s*_applyPendingBip158State\(\);' "$PEER" | grep -Eq '^\s*_applyDandelionState\(\);'; then
    echo "ok: startSync replays the Dandelion state onto the manager it creates"
else
    echo "FAIL: startSync replays the Dandelion state onto the manager it creates"; fail=1
fi
# Neither setter may drop the value when no manager exists yet (the B234 shape).
if grep -A4 -E 'NativeBridge_(setDandelionEnabled|addDandelionPeer)\(' "$PEER" | grep -Eq 'if \(!g_peerManager( \|\| !ipStr)?\) *\{?\s*(return|LOGI)'; then
    echo "FAIL: a Dandelion setter still returns before remembering when there is no peer manager"; fail=1
else
    echo "ok: the Dandelion setters remember before they need a peer manager"
fi
exit $fail
