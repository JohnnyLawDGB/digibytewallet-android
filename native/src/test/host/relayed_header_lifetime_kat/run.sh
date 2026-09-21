#!/usr/bin/env bash
# Host KAT: nothing read from a relayed header outlives the lock that protects it.
#
# _peerRelayedBlock (BRPeerManager.c) copies what it needs from the relayed header into
# locals while manager->lock is held, and does not dereference the header after the unlock.
# This gate proves it two ways (see the _main.c header for the detail):
#
#   1. BEHAVIOUR. The _main.c #includes BRPeerManager.c and drives the REAL _peerRelayedBlock
#      from two threads delivering the same header, with a deterministic rendezvous in place
#      of timing. It requires:
#        * RELAYED_HEADER_LIFETIME_UNFIXED=1 (reference arm: the earlier form of the gate)
#            -> reported by AddressSanitizer
#        * RELAYED_HEADER_LIFETIME_UNFIXED=0 (fixed arm)
#            -> every round completes, exit 0, no sanitizer report
#      A gate whose reference arm is not reported proves nothing, so that is a failure here.
#
#   2. SOURCE. Between the last MGR_UNLOCK(manager) of _peerRelayedBlock and its closing
#      self-call, the live BRPeerManager.c has no dereference of `block` outside the
#      reference arm. The scanner first shows it is not blind: with the reference arm's
#      lines left in, it must find one.
#
# ASan, leak detection off (this gate is about a lifetime, not about ownership at exit).
# Value-macro convention: -D...=1 / -D...=0, tested with #if.
# Each arm compiles BRPeerManager.c under ASan; allow about a minute per arm on a busy host.
set -uo pipefail   # NOT -e: the reference arm's non-zero exit must be captured

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# BRPeerManager.c is not in this list: the _main.c #includes it to reach the static function.
UNITS=(
    "$CORE_DIR/BRPeer.c" "$CORE_DIR/BRWallet.c" "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRMerkleBlock.c" "$CORE_DIR/BRCompactFilterChain.c" "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRWalletFilterElements.c" "$CORE_DIR/BRCFScanLedger.c" "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c" "$CORE_DIR/BRDigiAsset.c" "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRAddress.c" "$CORE_DIR/BRSet.c" "$CORE_DIR/BRBase58.c" "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c" "$CORE_DIR/BRBIP32Sequence.c" "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() { # $1=RELAYED_HEADER_LIFETIME_UNFIXED  $2=output
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g \
        -DRELAYED_HEADER_LIFETIME_UNFIXED="$1" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/relayed_header_lifetime_kat_main.c" "${UNITS[@]}" \
        -lm -lpthread -o "$2"
}

# ---- 2. SOURCE GATE (cheap, so it runs first) -------------------------------------------
# Prints the code lines (comments removed) between the last MGR_UNLOCK(manager) of
# _peerRelayedBlock and its closing self-call. $2=1 leaves the reference arm's lines out.
tail_after_unlock() {
    awk -v skipref="$2" '
        function strip(s,    out, i, c2) {            # remove /* */ and // comments
            out = ""
            for (i = 1; i <= length(s); i++) {
                c2 = substr(s, i, 2)
                if (incomment) { if (c2 == "*/") { incomment = 0; i++ } ; continue }
                if (c2 == "/*") { incomment = 1; i++; continue }
                if (c2 == "//") break
                out = out substr(s, i, 1)
            }
            return out
        }
        /^static void _peerRelayedBlock\(void \*info, BRMerkleBlock \*block\)$/ { infn = 1 }
        ! infn { next }
        {
            line = strip($0)
            if (line ~ /MGR_UNLOCK\(manager\);/) { buf = ""; seen = 1; next }   # keep only what follows the LAST one
            if (! seen) next
            if (line ~ /^#if .*RELAYED_HEADER_LIFETIME_UNFIXED/) { inref = 1; next }
            if (inref && line ~ /^#else/) { inref = 0; next }
            if (line ~ /if \(next\) _peerRelayedBlock\(info, next\);/) { printf "%s", buf; found = 1; exit }
            if (! (skipref == 1 && inref)) buf = buf line "\n"
        }
        END { if (! found) exit 2 }
    ' "$1"
}
DEREF='(^|[^A-Za-z0-9_])block->'

echo "=== source gate: no dereference of the relayed header after the unlock ==="
PM="$CORE_DIR/BRPeerManager.c"
if ! tail_after_unlock "$PM" 0 > "$BUILD_DIR/tail_all.txt"; then
    echo "FAIL: could not find the end of _peerRelayedBlock (last MGR_UNLOCK .. closing self-call) in $PM"; exit 1
fi
if ! grep -Eq "$DEREF" "$BUILD_DIR/tail_all.txt"; then
    echo "FAIL: scanner self-check — with the reference arm's lines left in, a dereference must be found."
    echo "      The scanner no longer sees the pattern it exists for."; exit 1
fi
tail_after_unlock "$PM" 1 > "$BUILD_DIR/tail_live.txt"
if grep -En "$DEREF" "$BUILD_DIR/tail_live.txt"; then
    echo "FAIL: _peerRelayedBlock dereferences \`block\` after its last MGR_UNLOCK (lines above)."
    echo "      Copy the value into a local while manager->lock is held and use the local."; exit 1
fi
echo "source gate OK ($(wc -l < "$BUILD_DIR/tail_live.txt") code lines after the unlock; scanner self-check passed)"

# ---- 1. BEHAVIOUR GATE --------------------------------------------------------------------
# symbolize=0 for speed: the gate detects the report, it does not name its frames.
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

echo "=== reference arm [1/2]: must be reported by AddressSanitizer ==="
if ! build 1 "$BUILD_DIR/reference"; then echo "FAIL: reference arm did not build"; exit 1; fi
"$BUILD_DIR/reference" > "$BUILD_DIR/reference.out" 2>&1; REF_EXIT=$?
if [ "$REF_EXIT" -eq 0 ]; then
    echo "FAIL: the reference arm exited 0 — this gate is not exercising the invariant it exists for."
    tail -3 "$BUILD_DIR/reference.out"; exit 1
fi
if grep -q "ERROR: AddressSanitizer" "$BUILD_DIR/reference.out"; then
    echo "reference arm reported (exit $REF_EXIT):"
    grep -E "ERROR: AddressSanitizer|^(READ|WRITE) of size" "$BUILD_DIR/reference.out" | head -2 | sed 's/^/  ref: /'
else
    echo "FAIL: the reference arm exited $REF_EXIT without an AddressSanitizer report — stopped in setup, not by the gate."
    tail -5 "$BUILD_DIR/reference.out"; exit 1
fi

echo "=== fixed arm [2/2]: must be clean ==="
if ! build 0 "$BUILD_DIR/fixed"; then echo "FAIL: fixed arm did not build"; exit 1; fi
"$BUILD_DIR/fixed" > "$BUILD_DIR/fixed.out" 2>&1; FIXED_EXIT=$?
if [ "$FIXED_EXIT" -ne 0 ]; then
    echo "FAIL: the fixed arm exited $FIXED_EXIT."
    grep -vE "^BITCOIN_TESTNET|^Starting sync" "$BUILD_DIR/fixed.out" | tail -8; exit 1
fi
if ! grep -q "no sanitizer report" "$BUILD_DIR/fixed.out"; then
    echo "FAIL: the fixed arm did not run to completion"; tail -5 "$BUILD_DIR/fixed.out"; exit 1
fi
grep -E "^\s+\[(PASS|FAIL)\]|relayed_header_lifetime_kat:" "$BUILD_DIR/fixed.out" | sed 's/^/  fixed: /'

echo "relayed_header_lifetime_kat: PASS (reference arm reported, fixed arm clean, source gate clean)"
exit 0
