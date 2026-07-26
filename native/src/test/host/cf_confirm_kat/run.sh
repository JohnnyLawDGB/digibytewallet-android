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

clang -w -include stdint.h \
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
    -o "$BUILD_DIR/cf_confirm_kat"

"$BUILD_DIR/cf_confirm_kat"
