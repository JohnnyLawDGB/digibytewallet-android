// Host KAT for the BIP-86 private-key derivation twins (BRBIP32PrivKeyBIP86,
// BRBIP32PrivKeyListBIP86), added to BRBIP32Sequence.c/BRBIP32Sequence.h in
// Taproot Sign-Task 1 (.superpowers/sdd/task-1-brief.md).
//
// BRBIP32PrivKeyBIP86 / BRBIP32PrivKeyListBIP86 are verbatim clones of
// BRBIP32PrivKeyBIP84 / BRBIP32PrivKeyListBIP84 with only the hardened
// purpose level changed (84 -> 86), so they derive m/86'/20'/0'/chain/index
// instead of m/84'/20'/0'/chain/index -- same "Bitcoin seed" HMAC key, same
// DGB_COIN_TYPE (20), same account 0.
//
// FIXED SEED: the well-known BIP-32 spec test-vector-1 seed
// ("000102030405060708090a0b0c0d0e0f", 16 raw bytes -- NOT a BIP-39 mnemonic).
// Same seed Plan-2 Task 1's bip86_derivation_kat used, so the reference
// pubkeys/addresses below are the SAME independently-computed values already
// pinned and reviewed in that task's report (.superpowers/sdd/p2-task-1-report.md)
// -- reused here rather than recomputed, since this task tests a different
// (private-key) code path against the SAME public-key reference.
//
// This KAT exercises, for i in {0, 1, 2}:
//   (a) PRIV/PUB CONSISTENCY: BRKeyPubKey(BRBIP32PrivKeyBIP86(seed, 0, i))
//       equals BRBIP32PubKey(BRBIP32MasterPubKeyBIP86(seed), 0, i) -- the
//       private derivation path and the public derivation path must produce
//       the identical compressed pubkey for the same m/86'/20'/0'/0/i.
//   (b) REFERENCE MATCH: that same pubkey matches the independently-computed
//       (Python, bip_utils + from-scratch CKDpriv cross-check) reference
//       value from Plan-2 Task 1.
//   (c) ADDRESS MATCH: BRKeyTaprootAddress of the derived privkey equals the
//       pinned dgb1p... address from Plan-2 Task 1 (the receive path pins
//       this same address from the public-key-only derivation, so this
//       proves the private key the signer will use is the key that actually
//       controls funds sent to that address).
//   (d) BATCH CONSISTENCY: BRBIP32PrivKeyListBIP86 with indexes {0,1,2}
//       produces the SAME secrets as three individual BRBIP32PrivKeyBIP86
//       calls (chain=0).
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRBIP32Sequence.h"
#include "BRKey.h"
#include "BRInt.h"

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

static uint8_t hexval(char c)
{
    if (c >= '0' && c <= '9') return (uint8_t)(c - '0');
    if (c >= 'a' && c <= 'f') return (uint8_t)(c - 'a' + 10);
    if (c >= 'A' && c <= 'F') return (uint8_t)(c - 'A' + 10);
    fprintf(stderr, "bad hex char: %c\n", c);
    exit(2);
}

// decodes exactly n bytes (2*n hex chars) from hex into out
static void hex2bin(const char *hex, uint8_t *out, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        out[i] = (uint8_t)((hexval(hex[2*i]) << 4) | hexval(hex[2*i + 1]));
    }
}

// BIP-32 spec test-vector-1 seed (16 raw bytes) -- see file header. Same seed
// Plan-2 Task 1's bip86_derivation_kat used.
static const char *SEED_HEX = "000102030405060708090a0b0c0d0e0f";

int main(void)
{
    uint8_t seed[16];
    hex2bin(SEED_HEX, seed, sizeof(seed));

    // Same independently-computed reference values as Plan-2 Task 1's
    // bip86_derivation_kat (m/86'/20'/0'/0/i) -- see that KAT's file header
    // and task report for full provenance (bip_utils + from-scratch CKDpriv
    // cross-check for the pubkey, from-scratch ecdsa-package BIP-341
    // taptweak + BIP-350 bech32m for the address).
    static const char *expected_pubkey_hex[3] = {
        "02d5e0879430e60b846958cbb075bc6e4b72bb9551d0f02dc15f6f21b2eca662d0",
        "02be431cc9db9d3a70d6536537c01eb9852664c310620c8258a9501a4d79463173",
        "02d5578f2049ae7f94b99ac5768209e271fb970a606bac44e907d7e9b94c888031",
    };
    static const char *expected_addr[3] = {
        "dgb1puwzphv7v604kgghtdwr4er5hxve3jek7euuwccearupqd083lfks732l8r",
        "dgb1p3um7etxmfk4n4tlqrd7ufw3yac436k4kdss6ynkzhhvswhm75lzq47syhs",
        "dgb1p8hcx94qpx6t9p57lxt0u7ce5cygkhsa8lnx2wnecc3knas6r7gnqd45wrl",
    };

    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    UInt256 individual_secret[3];

    for (uint32_t i = 0; i < 3; i++) {
        char label[160];

        // --- private path ---
        BRKey key;
        memset(&key, 0, sizeof(key));
        BRBIP32PrivKeyBIP86(&key, seed, sizeof(seed), 0, i);
        individual_secret[i] = key.secret;

        uint8_t privPubKey[33];
        size_t privPubKeyLen = BRKeyPubKey(&key, privPubKey, sizeof(privPubKey));
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRKeyPubKey(BRBIP32PrivKeyBIP86(...)) returns 33 bytes", i);
        check(privPubKeyLen == 33, label);

        // --- public path ---
        uint8_t pubPathPubKey[33];
        size_t pubPathPubKeyLen = BRBIP32PubKey(pubPathPubKey, sizeof(pubPathPubKey), mpk, 0, i);
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRBIP32PubKey(BRBIP32MasterPubKeyBIP86(...)) returns 33 bytes", i);
        check(pubPathPubKeyLen == 33, label);

        // --- (a) priv/pub consistency ---
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: private-path pubkey == public-path pubkey (priv/pub consistency)", i);
        check(privPubKeyLen == 33 && pubPathPubKeyLen == 33 &&
              memcmp(privPubKey, pubPathPubKey, 33) == 0, label);

        // --- (b) reference match ---
        uint8_t expectedPubKey[33];
        hex2bin(expected_pubkey_hex[i], expectedPubKey, 33);
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: private-path pubkey matches independently-computed reference", i);
        check(memcmp(privPubKey, expectedPubKey, 33) == 0, label);

        // --- (c) address match ---
        char addr[91];
        memset(addr, 0, sizeof(addr));
        size_t addrLen = BRKeyTaprootAddress(&key, addr, sizeof(addr));
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRKeyTaprootAddress(privkey) return value == strlen(expected) + 1", i);
        check(addrLen == strlen(expected_addr[i]) + 1, label);
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRKeyTaprootAddress(privkey) matches the pinned dgb1p... receive address", i);
        check(strcmp(addr, expected_addr[i]) == 0, label);
    }

    // --- (d) batch consistency: BRBIP32PrivKeyListBIP86 vs individual calls ---
    uint32_t indexes[3] = { 0, 1, 2 };
    BRKey batchKeys[3];
    memset(batchKeys, 0, sizeof(batchKeys));
    BRBIP32PrivKeyListBIP86(batchKeys, 3, seed, sizeof(seed), 0, indexes);

    for (uint32_t i = 0; i < 3; i++) {
        char label[160];
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRBIP32PrivKeyListBIP86 secret == individual BRBIP32PrivKeyBIP86 secret", i);
        check(UInt256Eq(batchKeys[i].secret, individual_secret[i]), label);
    }

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
