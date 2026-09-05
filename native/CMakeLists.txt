cmake_minimum_required(VERSION 3.18.1)

project("core-lib")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DANDROID_STL=gnustl_static -DANDROID_TOOLCHAIN=clang -fstack-protector-strong -fvisibility=hidden")

# Compact-filter diagnostics are COMPILED OUT by default and must stay that way in any
# shipped build — they log per KeepAlive tick and per cfilter arrival. The instrumentation
# itself is retained in the source (it is what located the 2026-08-02 sync defects); to
# re-enable, add the flag here:
#   -DCF_RECV_DIAG  [CF-RECV] every cfilter accounted against all six exits of
#                   _peerRelayedCFilter, plus `unaccounted` so an unknown drop path shows
#                   as a non-zero rather than as silence; and [CF-TIME] splitting the
#                   per-filter wall clock into lock wait / eval / idle.
#   -DCF_PIN_DIAG   [CF-PIN] why the height pinning the scan frontier was not re-requested,
#                   and [CF-ARR] arrival tagging within the pin's window.

add_library(
        core-lib
        SHARED

        # digibytewallet-core
        src/main/jni/digibytewallet-core/BRAddress.c
        src/main/jni/digibytewallet-core/BRAddress.h
        src/main/jni/digibytewallet-core/BRArray.h
        src/main/jni/digibytewallet-core/BRAssetData.c
        src/main/jni/digibytewallet-core/BRAssetData.h
        src/main/jni/digibytewallet-core/BRBase58.c
        src/main/jni/digibytewallet-core/BRBase58.h
        src/main/jni/digibytewallet-core/BRBech32.c
        src/main/jni/digibytewallet-core/BRBech32.h
        src/main/jni/digibytewallet-core/BRBIP32Sequence.c
        src/main/jni/digibytewallet-core/BRBIP32Sequence.h
        src/main/jni/digibytewallet-core/BRBIP38Key.c
        src/main/jni/digibytewallet-core/BRBIP38Key.h
        src/main/jni/digibytewallet-core/BRBIP39Mnemonic.c
        src/main/jni/digibytewallet-core/BRBIP39Mnemonic.h
        src/main/jni/digibytewallet-core/BRBIP39WordsEn.h
        src/main/jni/digibytewallet-core/BRChainParams.h
        src/main/jni/digibytewallet-core/BRCrypto.c
        src/main/jni/digibytewallet-core/BRCrypto.h
        src/main/jni/digibytewallet-core/BRDigiAsset.c
        src/main/jni/digibytewallet-core/BRDigiAsset.h
        src/main/jni/digibytewallet-core/BRDigiDollar.c
        src/main/jni/digibytewallet-core/BRDigiDollar.h
        src/main/jni/digibytewallet-core/BRNetwork.c
        src/main/jni/digibytewallet-core/BRNetwork.h
        src/main/jni/digibytewallet-core/BRGCSFilter.c
        src/main/jni/digibytewallet-core/BRGCSFilter.h
        src/main/jni/digibytewallet-core/BRCompactFilterChain.c
        src/main/jni/digibytewallet-core/BRCompactFilterChain.h
        src/main/jni/digibytewallet-core/BRWalletFilterElements.c
        src/main/jni/digibytewallet-core/BRWalletFilterElements.h
        src/main/jni/digibytewallet-core/BRCFScanLedger.c
        src/main/jni/digibytewallet-core/BRCFScanLedger.h
        src/main/jni/digibytewallet-core/BRInt.h
        src/main/jni/digibytewallet-core/BRKey.c
        src/main/jni/digibytewallet-core/BRKey.h
        src/main/jni/digibytewallet-core/BRMerkleBlock.c
        src/main/jni/digibytewallet-core/BRMerkleBlock.h
        src/main/jni/digibytewallet-core/BRPaymentProtocol.c
        src/main/jni/digibytewallet-core/BRPaymentProtocol.h
        src/main/jni/digibytewallet-core/BRPeer.c
        src/main/jni/digibytewallet-core/BRPeer.h
        src/main/jni/digibytewallet-core/BRPeerManager.c
        src/main/jni/digibytewallet-core/BRPeerManager.h
        src/main/jni/digibytewallet-core/BRPeerServices.h
        src/main/jni/digibytewallet-core/BRPeerPin.h
        src/main/jni/digibytewallet-core/BRPeerCFStatus.h
        src/main/jni/digibytewallet-core/BRSet.c
        src/main/jni/digibytewallet-core/BRSet.h
        src/main/jni/digibytewallet-core/BRTransaction.c
        src/main/jni/digibytewallet-core/BRTransaction.h
        src/main/jni/digibytewallet-core/BRWallet.c
        src/main/jni/digibytewallet-core/BRWallet.h

        # crypto algorithms
        src/main/jni/digibytewallet-core/crypto/sha256.h
        src/main/jni/digibytewallet-core/crypto/groestl.c
        src/main/jni/digibytewallet-core/crypto/groestl.h
        src/main/jni/digibytewallet-core/crypto/qubit.c
        src/main/jni/digibytewallet-core/crypto/qubit.h
        src/main/jni/digibytewallet-core/crypto/skein.c
        src/main/jni/digibytewallet-core/crypto/skein.h
        src/main/jni/digibytewallet-core/crypto/odocrypt.c
        src/main/jni/digibytewallet-core/crypto/odocrypt.h

        # sha3
        src/main/jni/digibytewallet-core/crypto/sha3/aes_helper.c
        src/main/jni/digibytewallet-core/crypto/sha3/blake.c
        src/main/jni/digibytewallet-core/crypto/sha3/bmw.c
        src/main/jni/digibytewallet-core/crypto/sha3/BRKeccak-800-SnP.c
        src/main/jni/digibytewallet-core/crypto/sha3/BRKeccak-800-SnP.h
        src/main/jni/digibytewallet-core/crypto/sha3/cubehash.c
        src/main/jni/digibytewallet-core/crypto/sha3/groestl.c
        src/main/jni/digibytewallet-core/crypto/sha3/hashblock.h
        src/main/jni/digibytewallet-core/crypto/sha3/keccak.c
        src/main/jni/digibytewallet-core/crypto/sha3/skein.c
        src/main/jni/digibytewallet-core/crypto/sha3/sph_types.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_blake.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_bmw.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_cubehash.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_groestl.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_keccak.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_skein.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_luffa.c
        src/main/jni/digibytewallet-core/crypto/sha3/sph_luffa.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_shavite.c
        src/main/jni/digibytewallet-core/crypto/sha3/sph_shavite.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_simd.c
        src/main/jni/digibytewallet-core/crypto/sha3/sph_simd.h
        src/main/jni/digibytewallet-core/crypto/sha3/sph_echo.c
        src/main/jni/digibytewallet-core/crypto/sha3/sph_echo.h

        # JNI bridge — clean bridge (Task 3)
        # NOTE: jni_test.c is deliberately NOT here — it is added below for Debug
        # builds only. See the target_sources() block after add_library().
        src/main/jni/bridge/jni_bridge.h
        src/main/jni/bridge/jni_wallet.c
        src/main/jni/bridge/jni_transaction.c
        src/main/jni/bridge/jni_peer.c
        src/main/jni/bridge/jni_asset.c
        src/main/jni/bridge/jni_wallet_sign.c
        src/main/jni/bridge/slip13.c
        src/main/jni/bridge/jni_transaction_persist.c
        src/main/jni/bridge/jni_derive.c
        src/main/jni/bridge/jni_asset_send.c
        src/main/jni/bridge/jni_cf_recovery_policy.c
        src/main/jni/bridge/jni_publish_outcome.c
        src/main/jni/bridge/jni_recreate_sequence.c
        src/main/jni/bridge/jni_peer_penalty_persist.c
        src/main/jni/bridge/jni_peer_canon.c
)

# ── Test-only JNI, Debug builds ONLY ────────────────────────────────────────
# jni_test.c exports Java_io_digibyte_native_1core_PeerTest_testPeerDiscovery, whose
# only caller is native/src/androidTest/.../PeerTest.kt. That Java class is not in a
# shipped APK, so in a release build the symbol was an exported entry point nothing
# could reach — test code in a production binary. Found in the v4.0.58 security cycle
# (finding 5). It reads compiled-in constants only and touches no wallet state, so this
# is hygiene rather than a vulnerability, but shipping it buys nothing.
#
# Gated on CMAKE_BUILD_TYPE rather than deleted because the test still earns its keep:
# it checks the mainnet magic, port, DNS seed count and checkpoint count actually
# compiled in. androidTest runs against the debug variant, which keeps it working.
#
# Verify BOTH directions after changing this — a guard that silently excludes the file
# from every build looks identical to one that works:
#   debug   .so MUST export testPeerDiscovery (or PeerTest dies on UnsatisfiedLinkError)
#   release .so MUST NOT
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(core-lib PRIVATE src/main/jni/bridge/jni_test.c)
    message(STATUS "core-lib: including test-only jni_test.c (CMAKE_BUILD_TYPE=Debug)")
else()
    message(STATUS "core-lib: EXCLUDING test-only jni_test.c (CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE})")
endif()

include_directories(src/main/jni/digibytewallet-core/)
# secp256k1 — single canonical vendored copy (bitcoin-core/secp256k1 v0.4.1),
# amalgamation-#included by BRKey.c only. Carries modules ecdh + recovery +
# extrakeys + schnorrsig (Taproot/BIP340 prerequisite).
include_directories(src/main/jni/digibytewallet-core/secp256k1/include/)
include_directories(src/main/jni/digibytewallet-core/secp256k1/src/)
include_directories(src/main/jni/digibytewallet-core/secp256k1/)

target_link_libraries(
        core-lib
        android
        log
)

# 16 KB page-size compatibility (Android 15+ / newer flagships boot with 16 KB
# memory pages; e.g. Galaxy S25 Ultra on Android 16). Without 16 KB-aligned LOAD
# segments the dynamic linker rejects/mis-maps the lib on those devices, so the
# native SPV engine never starts and the wallet hangs at "Connecting" with 0
# peers. Force max-page-size=16384 so segments are aligned for both 4 KB and
# 16 KB devices. (NDK r28 defaults to this; we set it explicitly for older NDKs.)
target_link_options(core-lib PRIVATE "-Wl,-z,max-page-size=16384")

# AddressSanitizer must be passed at LINK time as well as compile time, or the runtime's
# interceptors are never pulled in and the resulting .so reports nothing while looking
# instrumented. Driven off the compile flags the gradle `-PasanNative=true` property injects,
# so there is one switch rather than two that can disagree.
if(CMAKE_C_FLAGS MATCHES "-fsanitize=address")
    message(STATUS "core-lib: AddressSanitizer ENABLED (opt-in build)")
    target_link_options(core-lib PRIVATE "-fsanitize=address")
endif()
