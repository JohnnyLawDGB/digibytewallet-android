cmake_minimum_required(VERSION 3.18.1)

project("core-lib")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DANDROID_STL=gnustl_static -DANDROID_TOOLCHAIN=clang -fstack-protector-strong -fvisibility=hidden")

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
        src/main/jni/digibytewallet-core/BRBloomFilter.c
        src/main/jni/digibytewallet-core/BRBloomFilter.h
        src/main/jni/digibytewallet-core/BRChainParams.h
        src/main/jni/digibytewallet-core/BRCrypto.c
        src/main/jni/digibytewallet-core/BRCrypto.h
        src/main/jni/digibytewallet-core/BRDigiAsset.c
        src/main/jni/digibytewallet-core/BRDigiAsset.h
        src/main/jni/digibytewallet-core/BRGCSFilter.c
        src/main/jni/digibytewallet-core/BRGCSFilter.h
        src/main/jni/digibytewallet-core/BRCompactFilterChain.c
        src/main/jni/digibytewallet-core/BRCompactFilterChain.h
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
        src/main/jni/bridge/jni_test.c
        src/main/jni/bridge/jni_bridge.h
        src/main/jni/bridge/jni_wallet.c
        src/main/jni/bridge/jni_transaction.c
        src/main/jni/bridge/jni_peer.c
        src/main/jni/bridge/jni_asset.c
        src/main/jni/bridge/jni_wallet_sign.c
        src/main/jni/bridge/jni_transaction_persist.c
        src/main/jni/bridge/jni_derive.c
        src/main/jni/bridge/jni_asset_send.c
        # Old bridge files retained for reference but NOT compiled:
        # src/main/jni/bridge/core.c
        # src/main/jni/bridge/wallet.c
        # src/main/jni/bridge/PeerManager.c
        # src/main/jni/bridge/JNIKey.c
        # src/main/jni/bridge/JNIBase58.c
        # src/main/jni/bridge/JNIBIP32Sequence.c
)

include_directories(src/main/jni/digibytewallet-core/)
include_directories(src/main/secp/secp256k1/include/)
include_directories(src/main/secp/secp256k1/src/)
include_directories(src/main/secp/secp256k1/)
include_directories(src/main/secp/)

target_link_libraries(
        core-lib
        android
        log
)
