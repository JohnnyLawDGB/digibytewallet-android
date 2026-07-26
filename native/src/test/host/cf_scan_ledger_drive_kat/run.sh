#!/usr/bin/env bash
# Host KAT runner for the CF scan-ledger's residual peek/commit re-request
# DRIVER (BRPeerManager.c) -- Phase 2 Task 4 of the compact-filter scan-ledger
# plan (.superpowers/sdd/2026-07-26-cf-scan-ledger-phase2-rerequest-driver/).
# This task is the harness SKELETON + a build/teardown smoke test only; the
# real driver assertions (does the driver actually re-issue getcfilters/
# getdata for holes the ledger reports, and credit the buffered drain once
# BRPeerManagerFree(m) later runs LSan-clean) land in Task 5.
#
# Same #include-a-.c-for-statics pattern as cf_confirm_kat/bip341_signtx_kat/
# digidollar_send_kat: cf_scan_ledger_drive_kat_main.c #include-s
# BRPeerManager.c directly to reach the file-static re-request driver plumbing
# and the otherwise-opaque BRPeerManagerStruct/BRPeerCallbackInfo definitions.
# BRPeerManager.c is therefore deliberately NOT ALSO passed as a separate
# compilation unit below -- every symbol it defines would otherwise be defined
# twice and the link would fail.
#
# NEW for this KAT vs cf_confirm_kat: a linker `--wrap` send-capture seam.
# The driver under test calls out through BRPeer.c's public send functions
# (BRPeerSendGetCFilters / BRPeerSendGetdataBlocks) and status/socket queries
# (BRPeerConnectStatus / BRPeerIsSocketOpen) -- real BRPeer.c is still linked
# in (so every other BRPeer symbol the manager needs is real), but these four
# calls are intercepted at link time and redirected to the __wrap_ shims in
# cf_scan_ledger_drive_kat_main.c, which record what the driver sent instead
# of touching a real socket. This requires GNU ld's `--wrap`; host CI is
# Linux/clang, so this is fine here (it is NOT portable to a Darwin/ld64 host).
#
# ASan is compiled in WITH LeakSanitizer left live (no detect_leaks=0
# override, unlike the sibling KATs that disable it) -- the scan-ledger's
# buffered raw filter bytes (BRCFScanLedger's filter-byte buffer, Phase 2
# Task 2) are exactly the kind of allocation a silent leak would hide. Every
# KAT case must therefore end by calling BRPeerManagerFree(m) (which now also
# calls BRCFScanLedgerFree(&manager->cfLedger)) so LSan can prove the buffer
# and the manager are both actually freed.
#
# Copy of cf_confirm_kat's FULL unit list, verbatim -- an abbreviated list
# link-under-resolves because `#include "BRPeerManager.c"` pulls in the
# wallet-match+getdata path the driver's buffered-drain credit exercises
# (BRWallet.c, BRWalletFilterElements.c, BRTransaction.c, BRDigiDollar.c, the
# whole address/key/crypto chain, etc.) -- see that run.sh's comments for the
# full per-file rationale. BRPeerManager.c stays OUT of this list (see above).
#
# Compiler: clang, NOT gcc, and `-include stdint.h` -- same crypto/odocrypt.h
# reasons documented in bip340_kat/run.sh (file-scope `const static int`
# array bounds GCC rejects; missing <stdint.h> include).
#
# Exit code 0 = all checks passed, 1 = at least one check failed (or build
# error).
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
    -DCF_LEDGER_DRIVE_REREQUEST=1 \
    -fsanitize=address -fno-omit-frame-pointer -g \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/cf_scan_ledger_drive_kat_main.c" \
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
    -Wl,--wrap=BRPeerConnectStatus \
    -Wl,--wrap=BRPeerIsSocketOpen \
    -Wl,--wrap=BRPeerSendGetCFilters \
    -Wl,--wrap=BRPeerSendGetdataBlocks \
    -lm -lpthread \
    -o "$BUILD_DIR/cf_scan_ledger_drive_kat"

"$BUILD_DIR/cf_scan_ledger_drive_kat"
