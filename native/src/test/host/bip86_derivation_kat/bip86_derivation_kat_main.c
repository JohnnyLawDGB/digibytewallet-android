// Host KAT for the BIP-86 account master-pubkey derivation twin
// (BRBIP32MasterPubKeyBIP86), added to BRBIP32Sequence.c/BRBIP32Sequence.h in
// Taproot Plan-2 Task 1 (.superpowers/sdd/task-1-brief.md).
//
// BRBIP32MasterPubKeyBIP86 is a verbatim clone of BRBIP32MasterPubKeyBIP84
// with only the hardened purpose level changed (84 -> 86), so it derives
// N(m/86'/20'/0') instead of N(m/84'/20'/0') -- same "Bitcoin seed" HMAC key,
// same DGB_COIN_TYPE (20), same account 0.
//
// FIXED SEED: the well-known BIP-32 spec test-vector-1 seed
// ("000102030405060708090a0b0c0d0e0f", 16 raw bytes -- NOT a BIP-39 mnemonic,
// just the raw seed bytes BIP-32's own worked examples use). Chosen because
// it's a widely recognized, unambiguous constant -- anyone re-deriving these
// vectors starts from the same well-documented 16 bytes.
//
// REFERENCE VALUES (for m/86'/20'/0'/0/i, i = 0..2) were computed
// independently in Python, NOT via this repo's C code, using bip_utils'
// Bip32Slip10Secp256k1 (raw BIP-32 derivation -- bip_utils has no DigiByte
// BIP-86 coin preset, so the path is walked by hand: 86' / 20' / 0' / 0 / i)
// for the account derivation, then the same BIP-341 key-path-only taptweak
// math Task 4 used (t = TaggedHash("TapTweak", P), Q = P + t*G via a
// from-scratch `ecdsa`-package point implementation -- not this repo's or
// any C secp256k1 build), then SegwitBech32Encoder.Encode('dgb', 1, X(Q))
// (BIP-350 bech32m), round-tripped through SegwitBech32Decoder before
// pinning. See task reports for the exact scripts and a SECOND, fully
// independent cross-check: a from-scratch pure-Python BIP-32 CKDpriv
// (HMAC-SHA512 based, no bip_utils) reproduced byte-identical compressed
// pubkeys at m/86'/20'/0'/0/i for all three indices before any value below
// was transcribed into this file.
//
// This KAT exercises three things per index i in {0, 1, 2}:
//   (a) BRBIP32MasterPubKeyBIP86(seed, seedLen) + BRBIP32PubKey(pk, ..., mpk,
//       0, i) reproduces the independently-computed compressed pubkey for
//       m/86'/20'/0'/0/i.
//   (b) BRKeyTaprootAddress (Plan-1 Task 4, already KAT-verified) on a BRKey
//       carrying only that derived pubkey (BRKeySetPubKey -- no secret
//       needed, since Taproot rendering is pubkey-only) produces the
//       independently-computed dgb1p... address.
//   (c) CANONICALIZATION ROUND-TRIP: the derived receive string equals
//       BRAddressFromScriptPubKey({OP_1, 0x20, X(Q)}) for the SAME X(Q) this
//       KAT just derived (via BRKeyTaprootOutputKey) -- i.e. the "derive an
//       address to show/watch" path and the "recognize a scriptPubKey as
//       mine" path emit byte-identical strings. This guards the
//       fund-visibility invariant: an address the wallet derives for
//       receiving must be recognized by the wallet's own scriptPubKey
//       decoder, or funds sent to it could go unnoticed by that path.
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRBIP32Sequence.h"
#include "BRKey.h"
#include "BRAddress.h"

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

// BIP-32 spec test-vector-1 seed (16 raw bytes) -- see file header.
static const char *SEED_HEX = "000102030405060708090a0b0c0d0e0f";

int main(void)
{
    uint8_t seed[16];
    hex2bin(SEED_HEX, seed, sizeof(seed));

    // Independently computed (Python, bip_utils + from-scratch pure-Python
    // BIP-32 CKDpriv cross-check + from-scratch `ecdsa`-package taptweak
    // math) for m/86'/20'/0'/0/i -- see file header and task report for full
    // provenance.
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

    for (uint32_t i = 0; i < 3; i++) {
        char label[128];

        uint8_t pk[33];
        size_t pkLen = BRBIP32PubKey(pk, sizeof(pk), mpk, 0, i);
        snprintf(label, sizeof(label), "m/86'/20'/0'/0/%u: BRBIP32PubKey returns 33 bytes", i);
        check(pkLen == 33, label);

        uint8_t expected_pk[33];
        hex2bin(expected_pubkey_hex[i], expected_pk, 33);
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: derived pubkey matches independently-computed reference", i);
        check(memcmp(pk, expected_pk, 33) == 0, label);

        // BRKeyTaprootAddress is pubkey-only (Plan-1 Task 4) -- no secret
        // needed, just BRKeySetPubKey.
        BRKey key;
        memset(&key, 0, sizeof(key));
        check(BRKeySetPubKey(&key, pk, sizeof(pk)) != 0, "BRKeySetPubKey succeeds");

        char addr[91];
        memset(addr, 0, sizeof(addr));
        size_t addrLen = BRKeyTaprootAddress(&key, addr, sizeof(addr));
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRKeyTaprootAddress return value == strlen(expected) + 1", i);
        check(addrLen == strlen(expected_addr[i]) + 1, label);
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRKeyTaprootAddress output matches independently-computed dgb1p... reference", i);
        check(strcmp(addr, expected_addr[i]) == 0, label);

        // --- Canonicalization round-trip ---
        // Rebuild the same 34-byte P2TR scriptPubKey {OP_1, 0x20, X(Q)} via
        // BRKeyTaprootOutputKey (the same primitive BRKeyTaprootAddress uses
        // internally) and decode it back with BRAddressFromScriptPubKey --
        // the wallet's "recognize a scriptPubKey as mine" path -- asserting
        // it emits the IDENTICAL string as the derive path above.
        uint8_t xq[32];
        check(BRKeyTaprootOutputKey(&key, xq) == 1, "BRKeyTaprootOutputKey succeeds (for round-trip)");

        uint8_t script[34];
        script[0] = OP_1;
        script[1] = 32;
        memcpy(&script[2], xq, 32);

        char addr2[91];
        memset(addr2, 0, sizeof(addr2));
        size_t addr2Len = BRAddressFromScriptPubKey(addr2, sizeof(addr2), script, sizeof(script));
        snprintf(label, sizeof(label),
                 "m/86'/20'/0'/0/%u: BRAddressFromScriptPubKey({OP_1,0x20,X(Q)}) round-trip == derived address", i);
        check(addr2Len > 0 && strcmp(addr2, addr) == 0, label);
    }

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
