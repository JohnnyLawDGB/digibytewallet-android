#!/usr/bin/env bash
# Host KAT runner for the CF-confirmation driver's manager-side handler,
# _peerRelayedBlockTxns (BRPeerManager.c) -- Task 3 of the
# compact-filter-confirmation-driver plan (.superpowers/sdd/task-3-brief.md).
# Proves Task 1's BRPeer.c relayedBlockTxns callback + Task 2's manager-side
# _peerRelayedBlockTxns handler actually stamp a wallet tx's blockHeight when
# a CF-downloaded full block's txs are delivered.
#
# cf_confirm_kat_main.c #include-s BRPeerManager.c directly (to reach the
# file-static _peerRelayedBlockTxns and the otherwise-opaque
# BRPeerManagerStruct/BRPeerCallbackInfo definitions), same pattern
# bip341_signtx_kat/digidollar_send_kat use for BRTransaction.c's file-static
# _BRTransactionTaprootSighash -- see that run.sh's comments for the full
# rationale. BRPeerManager.c is therefore deliberately NOT also passed as a
# separate compilation unit below -- otherwise every symbol it defines would
# be defined twice and the link would fail.
#
# Every other BRPeerManager.c dependency IS compiled as a separate unit and
# linked in: BRPeer.c (relayedBlockTxns callback plumbing, connection/message
# handling), BRWallet.c (real BRWallet backing the confirmed tx),
# BRTransaction.c (BRRand plus every BRTransaction* symbol the KAT's payTx/
# finalizeTxHash helpers and BRWallet.c/BRPeer.c call), BRSet.c
# (manager->blocks / wallet->allTx hash sets), BRMerkleBlock.c (the dummy
# block + BRMerkleBlockHash/Eq), BRCompactFilterChain.c,
# BRGCSFilter.c, BRWalletFilterElements.c, BRCFScanLedger.c (BIP158 machinery
# BRPeerManager.c references even though this KAT never exercises it -- the
# CF scan-ledger's BRCFScanLedgerInit is called from BRPeerManager.c), BRNetwork.c
# (BRNetworkIsTestnet, called from BRAddress.c/BRKey.c call sites), plus the
# whole address/key/crypto chain those pull in (BRAddress, BRKey, BRBase58,
# BRBech32, BRCrypto, BRBIP32Sequence, BRBIP39Mnemonic, BRDigiAsset,
# BRDigiDollar, and crypto/* down through groestl/skein/qubit/odocrypt and
# the X11/sph_* helpers under crypto/sha3/).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh (file-scope `const static int`
# array bounds GCC rejects; missing <stdint.h> include).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error -- e.g. relayedBlockTxns/_peerRelayedBlockTxns not wired yet, the RED
# state before Tasks 1-2 landed).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# Two builds, so test4's remote-DoS guard is a real red-before-green gate rather than
# a test that would pass either way:
#
#   RED    -DRELAYEDBLOCKTXNS_MAINCHAIN_NULLGUARD_UNFIXED restores the pre-fix
#          `if (! BRMerkleBlockEq(b2, b))` shape in _peerRelayedBlockTxns. That build
#          MUST die on a SIGNAL (SIGSEGV reading 0x0). A merely non-zero exit is NOT
#          accepted -- that is also what a build/link failure looks like, and a gate
#          that can go red on a compile error proves nothing.
#   GREEN  the production shape must run every check and exit 0.
build() {
    local out="$1"; shift
    clang -w -include stdint.h "$@" \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/cf_confirm_kat_main.c" \
    "$CORE_DIR/BRPeer.c" \
    "$CORE_DIR/BRWallet.c" \
    "$CORE_DIR/BRTransaction.c" \
    "$CORE_DIR/BRMerkleBlock.c" \
    "$CORE_DIR/BRCompactFilterChain.c" \
    "$CORE_DIR/BRGCSFilter.c" \
    "$CORE_DIR/BRWalletFilterElements.c" \
    "$CORE_DIR/BRCFScanLedger.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRDigiDollar.c" \
    "$CORE_DIR/BRDigiAsset.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRSet.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRBIP39Mnemonic.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -lm -lpthread \
    -o "$out"
}

# ── RED: the pre-fix shape must crash on a signal ─────────────────────────────
build "$BUILD_DIR/cf_confirm_kat_unfixed" -g -DRELAYEDBLOCKTXNS_MAINCHAIN_NULLGUARD_UNFIXED

set +e
"$BUILD_DIR/cf_confirm_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_STATUS=$?
set -e

# 128+N == died on signal N. 139 == SIGSEGV.
if [ "$RED_STATUS" -lt 129 ]; then
    echo "GATE FAILED: the RELAYEDBLOCKTXNS_MAINCHAIN_NULLGUARD_UNFIXED build exited $RED_STATUS"
    echo "             without dying on a signal. The gate cannot prove the guard is"
    echo "             load-bearing, so test4 is not a regression test. Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
# The crash must happen at test4, i.e. AFTER tests 1-3 have printed their PASS lines.
# Crashing earlier would mean the harness broke, not that the guard is load-bearing.
if ! grep -q "test4: lastBlock is above the stub" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build died on signal $((RED_STATUS - 128)) but before"
    echo "             reaching test4's call -- so the crash is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if grep -q "test4: unprovable main chain is a no-op" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the UNFIXED build survived test4's call. The -D flag is not"
    echo "             reaching the guard, so RED is not actually the pre-fix shape."
    exit 1
fi
echo "RED gate OK: pre-fix shape died on signal $((RED_STATUS - 128)) at test4's call."

# ── RED 2: the naive element cache must fail test6 BY ASSERTION ───────────────
# CF_ELEMS_CACHE_NOINVALIDATE caches the wallet's BIP 158 element set once and never
# invalidates it. An address derived after the first filter is then never matched — a
# silent missed receive. This arm must FAIL (exit 1 with a FAIL line), and must NOT die on
# a signal: the production defect is silence, so a crash here would be proving something
# else.
build "$BUILD_DIR/cf_confirm_kat_stalecache" -DCF_ELEMS_CACHE_NOINVALIDATE

set +e
"$BUILD_DIR/cf_confirm_kat_stalecache" > "$BUILD_DIR/red2.log" 2>&1
RED2_STATUS=$?
set -e

if [ "$RED2_STATUS" -ge 129 ]; then
    echo "GATE FAILED: the CF_ELEMS_CACHE_NOINVALIDATE build died on signal $((RED2_STATUS - 128))."
    echo "             It must fail by ASSERTION, not by crashing."
    sed 's/^/             | /' "$BUILD_DIR/red2.log"
    exit 1
fi
if [ "$RED2_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the CF_ELEMS_CACHE_NOINVALIDATE build PASSED. A cache that never"
    echo "             invalidates would ship undetected, so test6 is not a real gate."
    exit 1
fi
if ! grep -q "FAIL: test6: an address added AFTER the cache was built IS matched" "$BUILD_DIR/red2.log"; then
    echo "GATE FAILED: the stale-cache build failed, but NOT on test6's staleness assertion,"
    echo "             so the failure is not the defect under test."
    sed 's/^/             | /' "$BUILD_DIR/red2.log"
    exit 1
fi
# The stale cache must still pass the controls — otherwise test6 would go red for a
# trivial reason (e.g. an empty element set) rather than for staleness specifically.
if ! grep -q "PASS: test6: positive control -- the pre-existing address is still matched" "$BUILD_DIR/red2.log"; then
    echo "GATE FAILED: the stale-cache build also lost the pre-existing address, so test6's"
    echo "             red is not isolated to staleness."
    sed 's/^/             | /' "$BUILD_DIR/red2.log"
    exit 1
fi
echo "RED gate OK: stale element cache fails test6 by assertion, controls still pass."

# ── RED 3: a COUNT-ONLY cache key must fail test7 ─────────────────────────────
# This is the rule an adversarial review refuted. Keying only on the address count misses a
# network switch: the elements re-encode while addrGen and addrCount both stay identical, so
# the cache serves a permanently wrong element set. Must fail by ASSERTION on test7 while
# test6 (the append case, which a count DOES catch) still passes — that pairing is what
# proves test7 is isolated to the network term.
build "$BUILD_DIR/cf_confirm_kat_countonly" -DCF_ELEMS_CACHE_COUNT_ONLY

set +e
"$BUILD_DIR/cf_confirm_kat_countonly" > "$BUILD_DIR/red3.log" 2>&1
RED3_STATUS=$?
set -e

if [ "$RED3_STATUS" -ge 129 ]; then
    echo "GATE FAILED: the CF_ELEMS_CACHE_COUNT_ONLY build died on signal $((RED3_STATUS - 128));"
    echo "             it must fail by ASSERTION."
    sed 's/^/             | /' "$BUILD_DIR/red3.log"
    exit 1
fi
if [ "$RED3_STATUS" -eq 0 ]; then
    echo "GATE FAILED: the CF_ELEMS_CACHE_COUNT_ONLY build PASSED, so test7 does not actually"
    echo "             gate the network term of the cache key."
    exit 1
fi
if ! grep -q "FAIL: test7: the element set was rebuilt for the new network" "$BUILD_DIR/red3.log"; then
    echo "GATE FAILED: the count-only build failed, but NOT on test7's network assertion."
    sed 's/^/             | /' "$BUILD_DIR/red3.log"
    exit 1
fi
if ! grep -q "PASS: test6: an address added AFTER the cache was built IS matched" "$BUILD_DIR/red3.log"; then
    echo "GATE FAILED: the count-only build also failed test6, so test7's red is not isolated"
    echo "             to the network term."
    sed 's/^/             | /' "$BUILD_DIR/red3.log"
    exit 1
fi
if ! grep -q "PASS: test7: the network switch leaves addrGen AND addrCount unchanged" "$BUILD_DIR/red3.log"; then
    echo "GATE FAILED: the network switch DID move addrGen/addrCount, so test7 no longer"
    echo "             proves a count-based key is insufficient."
    sed 's/^/             | /' "$BUILD_DIR/red3.log"
    exit 1
fi
echo "RED gate OK: count-only key fails test7 (network term) while test6 still passes."

# ── GREEN: the production shape must pass every check ─────────────────────────
build "$BUILD_DIR/cf_confirm_kat"

"$BUILD_DIR/cf_confirm_kat"
