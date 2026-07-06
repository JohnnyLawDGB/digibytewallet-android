// Host KAT for BIP-86 key-path-only Taproot output key (BRKeyTaprootOutputKey)
// and address (BRKeyTaprootAddress), added to BRKey.c/BRKey.h in Taproot
// Task 4 (.superpowers/sdd/task-4-brief.md).
//
// PRIMARY vector: an OFFICIAL BIP-341 test vector, transcribed from
// github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json,
// `keyPathSpending[0].inputSpending[0]` -- the ONLY one of the 7 per-input
// entries in that fixture with `merkleRoot: null`, i.e. the empty-script-tree
// / key-path-only shape that BIP-86 always uses (no merkle root appended to
// the tagged hash). Fields transcribed:
//   given.internalPrivkey             -> SK_HEX (the secret key)
//   intermediary.internalPubkey       -> INTERNAL_PUBKEY_HEX (x-only pubkey of SK_HEX)
//   intermediary.tweak                -> TWEAK_HEX (TaggedHash("TapTweak", P), no merkle root)
//   given.utxosSpent[0].scriptPubKey  -> the last 32 bytes (after the 5120
//                                        OP_1+len prefix) is OUTPUT_KEY_HEX,
//                                        the tweaked output key X(Q)
//
// Before transcribing, all three relationships were independently
// re-derived with a from-scratch pure-Python secp256k1 point implementation
// (the `ecdsa` PyPI package's curve/generator -- NOT this repo's or any C
// secp256k1 build, and not coincurve either, to keep the check maximally
// independent of any libsecp256k1 binary):
//   sk*G .x                    == internalPubkey
//   TaggedHash(TapTweak, P)    == tweak
//   X(P + t*G)                 == output key (the scriptPubKey's 32-byte payload)
// All three passed. See task-4-report.md for the exact script and output.
//
// SECONDARY vector: the dgb1p... address for the SAME output key, computed
// independently via bip_utils' SegwitBech32Encoder('dgb', 1, X(Q)) (BIP-350
// bech32m), then round-tripped through SegwitBech32Decoder before pinning
// here. See task-4-report.md.
//
// This KAT exercises the new production code two ways:
//   (a) a PUBKEY-ONLY replay of the tweak math (parse the vector's own
//       internalPubkey as an x-only pubkey, then call the exact same
//       primitives BRKeyTaprootOutputKey uses internally -- BRKeyTaggedHash
//       (Task 3) + secp256k1_xonly_pubkey_tweak_add/from_pubkey/serialize)
//       -- proves the tweak math without needing a BRKey/secret at all.
//   (b) the real END-TO-END production functions, from the vector's own
//       secret key: BRKeySetSecret -> BRKeyTaprootOutputKey (compared
//       against the BIP-341 vector's own output key) and
//       BRKeySetSecret -> BRKeyTaprootAddress (compared against the
//       independently-computed dgb1p... reference).
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRKey.h"
#include "secp256k1.h"
#include "secp256k1_extrakeys.h"

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

// bip-0341/wallet-test-vectors.json, keyPathSpending[0].inputSpending[0]
// (txinIndex 0, merkleRoot: null) -- see file header for provenance.
static const char *SK_HEX              = "6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa";
static const char *INTERNAL_PUBKEY_HEX = "d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d";
static const char *TWEAK_HEX           = "b86e7be8f39bab32a6f2c0443abbc210f0edac0e2c53d501b36b64437d9c6c70";
static const char *OUTPUT_KEY_HEX      = "53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343";

// Independently computed (bip_utils SegwitBech32Encoder('dgb', 1, X(Q))),
// round-tripped through SegwitBech32Decoder before pinning -- see
// task-4-report.md.
static const char *EXPECTED_DGB1P_ADDRESS = "dgb1p2wsldez5mud2yam29q22wgfh9439spgduvct83k3pm50fcxa5dpsf0nhat";

int main(void)
{
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);

    uint8_t expected_output_key[32];
    hex2bin(OUTPUT_KEY_HEX, expected_output_key, 32);

    // --- (a) pubkey-only replay of the tweak math -- no BRKey/secret at all.
    // Exercises the exact primitives BRKeyTaprootOutputKey uses internally.
    {
        uint8_t p32[32];
        hex2bin(INTERNAL_PUBKEY_HEX, p32, 32);

        secp256k1_xonly_pubkey xo;
        check(secp256k1_xonly_pubkey_parse(ctx, &xo, p32) != 0,
              "vector: internalPubkey parses as a valid x-only pubkey");

        // BIP-86 key-path-only tweak: TaggedHash("TapTweak", P) -- NO merkle
        // root appended (that's the whole point of key-path-only spending).
        UInt256 t;
        BRKeyTaggedHash("TapTweak", p32, sizeof(p32), &t);
        uint8_t expected_tweak[32];
        hex2bin(TWEAK_HEX, expected_tweak, 32);
        check(memcmp(t.u8, expected_tweak, 32) == 0,
              "BRKeyTaggedHash(\"TapTweak\", internalPubkey) matches the BIP-341 vector's tweak");

        secp256k1_pubkey q;
        check(secp256k1_xonly_pubkey_tweak_add(ctx, &q, &xo, t.u8) != 0,
              "secp256k1_xonly_pubkey_tweak_add(P, t) succeeds");

        secp256k1_xonly_pubkey qxo;
        check(secp256k1_xonly_pubkey_from_pubkey(ctx, &qxo, NULL, &q) != 0,
              "xonly_pubkey_from_pubkey(Q) succeeds");

        uint8_t qx[32];
        check(secp256k1_xonly_pubkey_serialize(ctx, qx, &qxo) != 0,
              "xonly_pubkey_serialize(Q) succeeds");

        check(memcmp(qx, expected_output_key, 32) == 0,
              "pubkey-only tweak math: X(P + t*G) matches the BIP-341 vector's output key");
    }

    // --- (b) end-to-end via the real production functions, from the
    // vector's own secret key.
    {
        UInt256 secret;
        hex2bin(SK_HEX, secret.u8, 32);
        BRKey key;
        memset(&key, 0, sizeof(key));
        check(BRKeySetSecret(&key, &secret, 1) != 0, "BRKeySetSecret succeeds");

        uint8_t out32[32];
        int ok = BRKeyTaprootOutputKey(&key, out32);
        check(ok == 1, "BRKeyTaprootOutputKey returns 1");
        check(memcmp(out32, expected_output_key, 32) == 0,
              "BRKeyTaprootOutputKey output matches the BIP-341 vector's output key exactly");

        char addr[91];
        memset(addr, 0, sizeof(addr));
        size_t n = BRKeyTaprootAddress(&key, addr, sizeof(addr));
        check(n == strlen(EXPECTED_DGB1P_ADDRESS) + 1,
              "BRKeyTaprootAddress return value == strlen(expected dgb1p address) + 1 (null terminator)");
        check(strcmp(addr, EXPECTED_DGB1P_ADDRESS) == 0,
              "BRKeyTaprootAddress output matches the independently-computed dgb1p... reference");
    }

    secp256k1_context_destroy(ctx);

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
