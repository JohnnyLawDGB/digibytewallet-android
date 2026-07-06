// Host KAT for the runtime network toggle (BRSetNetwork / BRNetworkIsTestnet,
// BRNetwork.h/.c, Task 1 of the network-switch plan) as consumed by the
// address/key/difficulty call sites converted from compile-time
// `#if BITCOIN_TESTNET` to runtime checks in Task 2
// (.superpowers/sdd/task-2-brief.md): BRAddress.c/.h, BRKey.c,
// BRMerkleBlock.c.
//
// This is the crux correctness proof for the whole conversion: from ONE
// build (no BITCOIN_TESTNET compile flag needed/passed at all here), the
// SAME fixed key must encode as a mainnet "dgb1.../D..." address under
// BRSetNetwork(0) and a testnet "dgbt1.../<testnet-prefix>..." address under
// BRSetNetwork(1) -- and BRAddressIsValid must accept only the form that
// matches whatever network is currently selected, rejecting the other. If
// any converted call site missed the runtime check (still hardcoded to
// mainnet, or reads a stale value), this KAT fails.
//
// Compile approach: same as the other Taproot/DigiDollar host KATs (see
// e.g. taproot_addr_kat/run.sh) -- compiles the REAL, live submodule
// sources directly out of the tree, clang + `-include stdint.h` for the
// crypto/odocrypt.h reasons documented there. BRNetwork.c is linked in
// because BRAddress.c/BRKey.c now call BRNetworkIsTestnet().
//
// Exit code 0 = all checks passed, 1 = at least one check failed (or build
// error).

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "BRKey.h"
#include "BRAddress.h"
#include "BRNetwork.h"
#include "BRMerkleBlock.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) {
        printf("PASS: %s\n", desc);
    } else {
        printf("FAIL: %s\n", desc);
        g_failures++;
    }
}

// Arbitrary fixed secret (bytes 0x01..0x20) -- well within secp256k1's
// group order, no derivation needed; only its address ENCODING under each
// network setting is under test here, not key derivation itself (that's
// covered by the BIP32/BIP86 host KATs).
static const uint8_t SECRET_BYTES[32] = {
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
    0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
    0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
};

int main(void)
{
    // BRNetworkIsTestnet() must default to mainnet (0) before anyone calls
    // BRSetNetwork -- this is the whole point of the default: existing
    // mainnet builds/behavior must be unchanged if BRSetNetwork is never
    // invoked at all.
    check(BRNetworkIsTestnet() == 0, "BRNetworkIsTestnet() defaults to 0 (mainnet) before any BRSetNetwork call");

    UInt256 secret;
    memcpy(secret.u8, SECRET_BYTES, 32);

    BRKey key;
    memset(&key, 0, sizeof(key));
    check(BRKeySetSecret(&key, &secret, 1) != 0, "BRKeySetSecret succeeds (compressed)");

    // ---------------------------------------------------------------
    // Mainnet: BRSetNetwork(0)
    // ---------------------------------------------------------------
    BRSetNetwork(0);
    check(BRNetworkIsTestnet() == 0, "BRNetworkIsTestnet() == 0 after BRSetNetwork(0)");

    char legacyMain[36];
    memset(legacyMain, 0, sizeof(legacyMain));
    size_t nLegacyMain = BRKeyAddress(&key, legacyMain, sizeof(legacyMain));
    check(nLegacyMain > 0, "BRKeyAddress (mainnet) succeeds");
    check(legacyMain[0] == 'D', "mainnet legacy address starts with 'D' (DIGIBYTE_PUBKEY_LEGACY == 30)");
    check(BRAddressIsValid(legacyMain) != 0, "mainnet legacy address validates under mainnet");

    char segwitMain[91];
    memset(segwitMain, 0, sizeof(segwitMain));
    size_t nSegwitMain = BRKeySegwitAddress(&key, segwitMain, sizeof(segwitMain), 0);
    check(nSegwitMain > 0, "BRKeySegwitAddress (mainnet) succeeds");
    check(strncmp(segwitMain, "dgb1", 4) == 0, "mainnet segwit address has the dgb1... prefix");
    check(BRAddressIsValid(segwitMain) != 0, "mainnet segwit address validates under mainnet");

    // decode round-trip through BRAddressScriptPubKey / BRAddressFromScriptPubKey (mainnet)
    uint8_t segwitScriptMain[64];
    size_t segwitScriptMainLen = BRAddressScriptPubKey(segwitScriptMain, sizeof(segwitScriptMain), segwitMain);
    check(segwitScriptMainLen > 0, "BRAddressScriptPubKey decodes the mainnet dgb1 address");
    char segwitRoundTripMain[91];
    memset(segwitRoundTripMain, 0, sizeof(segwitRoundTripMain));
    size_t segwitRTMainLen = BRAddressFromScriptPubKey(segwitRoundTripMain, sizeof(segwitRoundTripMain),
                                                        segwitScriptMain, segwitScriptMainLen);
    check(segwitRTMainLen == nSegwitMain && strcmp(segwitRoundTripMain, segwitMain) == 0,
          "mainnet dgb1 address round-trips through scriptPubKey encode/decode");

    uint8_t legacyScriptMain[25];
    size_t legacyScriptMainLen = BRAddressScriptPubKey(legacyScriptMain, sizeof(legacyScriptMain), legacyMain);
    check(legacyScriptMainLen == 25, "BRAddressScriptPubKey decodes the mainnet legacy address to a 25-byte P2PKH script");
    char legacyRoundTripMain[36];
    memset(legacyRoundTripMain, 0, sizeof(legacyRoundTripMain));
    size_t legacyRTMainLen = BRAddressFromScriptPubKey(legacyRoundTripMain, sizeof(legacyRoundTripMain),
                                                        legacyScriptMain, legacyScriptMainLen);
    check(legacyRTMainLen == nLegacyMain && strcmp(legacyRoundTripMain, legacyMain) == 0,
          "mainnet legacy address round-trips through scriptPubKey encode/decode");

    char wifMain[53];
    memset(wifMain, 0, sizeof(wifMain));
    size_t wifMainLen = BRKeyPrivKey(&key, wifMain, sizeof(wifMain));
    check(wifMainLen > 0, "BRKeyPrivKey (mainnet WIF) succeeds");
    check(BRPrivKeyIsValid(wifMain) != 0, "mainnet WIF validates as a private key under mainnet");

    // ---------------------------------------------------------------
    // Testnet: BRSetNetwork(1) -- SAME key, SAME process/build.
    // ---------------------------------------------------------------
    BRSetNetwork(1);
    check(BRNetworkIsTestnet() == 1, "BRNetworkIsTestnet() == 1 after BRSetNetwork(1)");

    char legacyTest[36];
    memset(legacyTest, 0, sizeof(legacyTest));
    size_t nLegacyTest = BRKeyAddress(&key, legacyTest, sizeof(legacyTest));
    check(nLegacyTest > 0, "BRKeyAddress (testnet) succeeds");
    check(strcmp(legacyTest, legacyMain) != 0, "testnet legacy address differs from mainnet legacy address (same key)");
    check(BRAddressIsValid(legacyTest) != 0, "testnet legacy address validates under testnet");
    check(BRAddressIsValid(legacyMain) == 0, "mainnet legacy address is REJECTED while network == testnet");

    char segwitTest[91];
    memset(segwitTest, 0, sizeof(segwitTest));
    size_t nSegwitTest = BRKeySegwitAddress(&key, segwitTest, sizeof(segwitTest), 0);
    check(nSegwitTest > 0, "BRKeySegwitAddress (testnet) succeeds");
    check(strncmp(segwitTest, "dgbt1", 5) == 0, "testnet segwit address has the dgbt1... prefix");
    check(BRAddressIsValid(segwitTest) != 0, "testnet segwit address validates under testnet");
    check(BRAddressIsValid(segwitMain) == 0, "mainnet dgb1 address is REJECTED while network == testnet (HRP mismatch)");

    // decode round-trip through BRAddressScriptPubKey / BRAddressFromScriptPubKey (testnet)
    uint8_t segwitScriptTest[64];
    size_t segwitScriptTestLen = BRAddressScriptPubKey(segwitScriptTest, sizeof(segwitScriptTest), segwitTest);
    check(segwitScriptTestLen > 0, "BRAddressScriptPubKey decodes the testnet dgbt1 address");
    char segwitRoundTripTest[91];
    memset(segwitRoundTripTest, 0, sizeof(segwitRoundTripTest));
    size_t segwitRTTestLen = BRAddressFromScriptPubKey(segwitRoundTripTest, sizeof(segwitRoundTripTest),
                                                        segwitScriptTest, segwitScriptTestLen);
    check(segwitRTTestLen == nSegwitTest && strcmp(segwitRoundTripTest, segwitTest) == 0,
          "testnet dgbt1 address round-trips through scriptPubKey encode/decode");

    // cross-check: the mainnet dgb1 address must fail to decode as a scriptPubKey while network == testnet
    size_t crossDecode = BRAddressScriptPubKey(NULL, 0, segwitMain);
    check(crossDecode == 0, "BRAddressScriptPubKey rejects the mainnet dgb1 address while network == testnet");

    char wifTest[53];
    memset(wifTest, 0, sizeof(wifTest));
    size_t wifTestLen = BRKeyPrivKey(&key, wifTest, sizeof(wifTest));
    check(wifTestLen > 0, "BRKeyPrivKey (testnet WIF) succeeds");
    check(strcmp(wifTest, wifMain) != 0, "testnet WIF differs from mainnet WIF (same key)");
    check(BRPrivKeyIsValid(wifTest) != 0, "testnet WIF validates as a private key under testnet");
    check(BRPrivKeyIsValid(wifMain) == 0, "mainnet WIF is REJECTED while network == testnet");

    BRKey key2;
    memset(&key2, 0, sizeof(key2));
    check(BRKeySetPrivKey(&key2, wifTest) != 0, "BRKeySetPrivKey parses the testnet WIF back into a key (testnet mode)");
    check(UInt256Eq(key2.secret, key.secret) != 0, "round-tripped testnet WIF secret matches the original secret");

    // ---------------------------------------------------------------
    // Back to mainnet -- verify the testnet-only forms are now rejected
    // (proves the check is live/reactive, not latched from the first call).
    // ---------------------------------------------------------------
    BRSetNetwork(0);
    check(BRNetworkIsTestnet() == 0, "BRNetworkIsTestnet() == 0 after switching back to mainnet");
    check(BRAddressIsValid(legacyTest) == 0, "testnet legacy address is REJECTED under mainnet");
    check(BRAddressIsValid(segwitTest) == 0, "testnet dgbt1 address is REJECTED under mainnet (HRP mismatch)");
    check(BRPrivKeyIsValid(wifTest) == 0, "testnet WIF is REJECTED under mainnet");
    check(BRAddressIsValid(legacyMain) != 0, "mainnet legacy address re-validates under mainnet");
    check(BRAddressIsValid(segwitMain) != 0, "mainnet dgb1 address re-validates under mainnet");
    check(BRPrivKeyIsValid(wifMain) != 0, "mainnet WIF re-validates under mainnet");

    // ---------------------------------------------------------------
    // BRMerkleBlockVerifyDifficulty's testnet skip (BRMerkleBlock.c ~387).
    // NOTE: the difficulty-recompute body below that `#if` was already fully
    // commented out (dead code, "TODO: fix difficulty target check for
    // Digibyte (Multishield)") before this conversion, so on THIS build
    // mainnet and testnet both currently fall through to the same bare
    // `return r`. This check therefore isn't a differential proof (there's
    // no live mainnet-only behavior left to differ from) -- it's a
    // regression/no-crash check that the runtime-gated early return didn't
    // break the block-linkage result on either path.
    // ---------------------------------------------------------------
    {
        BRMerkleBlock *previous = BRMerkleBlockNew();
        BRMerkleBlock *block = BRMerkleBlockNew();
        memset(previous->blockHash.u8, 0xAA, sizeof(previous->blockHash.u8));
        previous->height = 100;
        block->prevBlock = previous->blockHash;
        memset(block->blockHash.u8, 0xBB, sizeof(block->blockHash.u8));
        block->height = 101;

        BRSetNetwork(0);
        int rMain = BRMerkleBlockVerifyDifficulty(block, previous, 0);
        BRSetNetwork(1);
        int rTest = BRMerkleBlockVerifyDifficulty(block, previous, 0);
        BRSetNetwork(0);

        check(rMain == 1, "BRMerkleBlockVerifyDifficulty: valid prevBlock/height link returns 1 on mainnet");
        check(rTest == 1, "BRMerkleBlockVerifyDifficulty: valid prevBlock/height link returns 1 on testnet (early-return path)");

        BRMerkleBlockFree(block);
        BRMerkleBlockFree(previous);
    }

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
