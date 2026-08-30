// Characterization KAT: BRTransactionSign must keep producing BYTE-IDENTICAL
// signatures for the input types the wallet already spends.
//
// WHY THIS EXISTS
//
// BRTransactionSign signs EVERY spend this wallet makes — plain sends, sweeps,
// asset transfers, DigiDollar moves. Adding a P2SH-P2WPKH (BIP49) branch to it
// means editing the one function standing between the user and their money. The
// argument that "the new branch only fires on a script shape nothing currently
// produces" is an argument, not evidence. This is the evidence.
//
// Every expected string below was captured from the UNMODIFIED submodule at
// e12b3bc. They are not derived, hand-computed or reasoned about — they are a
// photograph of what the signer does today. Their whole job is to fail LATER.
//
// This is sound only because both signature schemes here are deterministic:
//   - ECDSA  via secp256k1_nonce_function_rfc6979 (BRKey.c:360)
//   - Schnorr via secp256k1_schnorrsig_sign32(..., NULL) — NULL aux_rand32,
//     so BIP-340 with zero auxiliary randomness (BRKey.c:548)
// A randomized nonce would make byte-equality meaningless and this KAT wrong.
//
// COVERAGE
//
//   1. P2PKH        — legacy sighash path
//   2. P2WPKH       — BIP143 witness path (the one BIP49 will reuse)
//   3. P2TR         — witness-v1 key-path branch, which sits BEFORE the
//                     BRScriptPKH match and `continue`s, i.e. exactly where the
//                     new BIP49 branch goes
//   4. MIXED P2PKH + P2WPKH in one transaction — the new branch changes control
//      flow inside the per-input loop, so per-input branch selection is asserted
//      rather than assumed
//
// PROVEN TO FAIL (a gate that cannot go red proves nothing):
//
//   A. legacy sighash SIGHASH_ALL -> SIGHASH_SINGLE
//        -> P2PKH and MIXED fail; P2WPKH and P2TR stay green
//   B. BIP143 scriptCode copies 19 of 20 hash bytes  (the path BIP49 reuses)
//        -> P2WPKH and MIXED fail; P2PKH and P2TR stay green
//   C. P2TR branch falls through instead of `continue`
//        -> NOT DETECTED, and worth stating plainly rather than leaving implied.
//           After the taproot branch signs, falling through reaches BRScriptPKH,
//           which finds no hash160 in an {OP_1, 32, X(Q)} script, so no key
//           matches and the loop `continue`s anyway. That `continue` is
//           defensive, not load-bearing — for THIS script shape. It is load-
//           bearing for a P2SH script, whose scriptPubKey DOES carry a hash160
//           that BRScriptPKH will happily return. The shape assertions below
//           (exactly one of scriptSig/witness per input, and the taproot
//           witness being one 64-byte element) are what catch a fall-through
//           that actually overwrites something.
//   D. P2TR branch additionally writes a scriptSig — the realistic form of the
//      hazard C could not express
//        -> P2TR fails on BOTH the byte-equality and the shape assertion
//
// Exit code 0 = all checks passed, 1 = at least one failed (or build error).

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRAddress.h"
#include "BRCrypto.h"
#include "BRNetwork.h"

/* ---------------------------------------------------------------------------
 * BASELINE — captured from the unmodified submodule at e12b3bc.
 *
 * Photographs, not derivations. Nothing below was hand-computed or reasoned
 * about; each is what BRTransactionSign emitted before the BIP49 branch existed.
 * Verified byte-identical across three consecutive runs before being pinned.
 * ------------------------------------------------------------------------ */

#define EXPECT_P2PKH \
    "01000000011111111111111111111111111111111111111111111111111111111111111111" \
    "000000006a4730440220635af8fdba2e71c9bdb553650c9dee10199051ce3e0a4e4c03fd0d" \
    "971a36e5c702202b4ba433809c644eb323c09db4d6ccb56987d6a435cf9793ac045d76d9e7" \
    "2fe7012102479cde2b0481402582375fa9803db7141b29caa0aac60f3ecc329cff4a1f6c43" \
    "ffffffff01c09ee605000000001976a914dcd5e7759e887ae4198162935816610ecc05debb" \
    "88ac00000000"

#define EXPECT_P2WPKH \
    "01000000000101222222222222222222222222222222222222222222222222222222222222" \
    "22220100000000ffffffff01c09ee605000000001976a914dcd5e7759e887ae41981629358" \
    "16610ecc05debb88ac024730440220165de492ce4cea402879b755cdc04740efd1d29dfa08" \
    "92b7dd6649f3355134ab02207ae3f435fc43a188b1e863c503f02a6bc26c3e30204a5d85f8" \
    "b45bbdab4636fc012102c8d63f5ca5e50398fbc1d13d870c61fa7d329a193e79f374a692d2" \
    "a4059c2f0900000000"

#define EXPECT_P2TR \
    "01000000000101333333333333333333333333333333333333333333333333333333333333" \
    "33330200000000ffffffff01c09ee605000000001976a914dcd5e7759e887ae41981629358" \
    "16610ecc05debb88ac01404bd91df0673ac20d23c2cdd5c64c0c1dbab9642af914dc49a158" \
    "4e3b61c2dc6df4e9ef51d8e76522dc5f95d4f7ac761d21ed43721cf3661e0d79f4343bcc78" \
    "fa00000000"

#define EXPECT_MIXED \
    "010000000001024444444444444444444444444444444444444444444444444444444444444444" \
    "000000006a47304402202afbdb6ef5029a60265bb816002f4121ae87af2f13b0e74d6c8230" \
    "3df77730780220528c54414fef8d42d455ccda11d5f0f3fe9f914ac1f6277952b88c5ad969" \
    "07c7012102479cde2b0481402582375fa9803db7141b29caa0aac60f3ecc329cff4a1f6c43" \
    "ffffffff5555555555555555555555555555555555555555555555555555555555555555" \
    "0100000000ffffffff01c09ee605000000001976a914dcd5e7759e887ae4198162935816610e" \
    "cc05debb88ac000247304402204ffdd9df0e3efc9b9ad341484d26207fe844cf7a97139960" \
    "6ff9356ebe80b944022017c478413a0bab723d8c8557f3cdad618f86d4f8ce1767ca42938d" \
    "90438c8862012102c8d63f5ca5e50398fbc1d13d870c61fa7d329a193e79f374a692d2a405" \
    "9c2f0900000000"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) { printf("PASS: %s\n", desc); }
    else { printf("FAIL: %s\n", desc); g_failures++; }
}

static void hexdump(char *out, const uint8_t *b, size_t n)
{
    for (size_t i = 0; i < n; i++) sprintf(out + i * 2, "%02x", b[i]);
    out[n * 2] = '\0';
}

/* A fixed, published-vector seed. Deterministic keys in, deterministic
 * signatures out — see the determinism note in the header comment. */
static void fixedSeed(UInt512 *seed)
{
    const char *mnemonic = "abandon abandon abandon abandon abandon abandon "
                           "abandon abandon abandon abandon abandon about";
    BRBIP39DeriveKey(seed->u8, mnemonic, NULL);
}

/* Derive m/84'/20'/0'/0/index as a signing key. Which derivation is used does
 * not matter here — only that it is FIXED, so the signature bytes are too. */
static void deriveKey(BRKey *key, int index)
{
    UInt512 seed;
    fixedSeed(&seed);
    BRBIP32PrivKeyPath(key, &seed, sizeof(seed), 5,
                       84 | BIP32_HARD, 20 | BIP32_HARD, 0 | BIP32_HARD, 0, (uint32_t)index);
    key->compressed = 1;
    memset(&seed, 0, sizeof(seed));
}

/* A synthetic previous-output hash. Fixed so the tx bytes are fixed. */
static UInt256 prevHash(uint8_t tag)
{
    UInt256 h = UINT256_ZERO;
    memset(h.u8, tag, sizeof(h.u8));
    return h;
}

/* Sign a one-output transaction over the given inputs and return its full
 * serialization as hex. Caller frees nothing; `out` must hold 2*len+1. */
static int signAndSerialize(BRTransaction *tx, BRKey *keys, size_t keysCount, char *out, size_t outSize)
{
    if (! BRTransactionSign(tx, 0, keys, keysCount)) return 0;
    size_t len = BRTransactionSerialize(tx, NULL, 0);
    uint8_t *buf = malloc(len);
    if (! buf) return 0;
    len = BRTransactionSerialize(tx, buf, len);
    if (len * 2 + 1 > outSize) { free(buf); return 0; }
    hexdump(out, buf, len);
    free(buf);
    return 1;
}

/* A P2PKH scriptPubKey over a key hash, built directly rather than round-tripped
 * through an address — fewer moving parts between the fixture and the bytes. */
static size_t p2pkhScript(uint8_t *script, UInt160 h)
{
    script[0] = OP_DUP; script[1] = OP_HASH160; script[2] = 20;
    memcpy(&script[3], h.u8, 20);
    script[23] = OP_EQUALVERIFY; script[24] = OP_CHECKSIG;
    return 25;
}

/* The destination every case pays, so only the INPUT handling varies. */
static size_t destScript(uint8_t *script, size_t scriptLen)
{
    (void)scriptLen;
    BRKey k;
    deriveKey(&k, 99);
    return p2pkhScript(script, BRKeyHash160(&k));
}

int main(void)
{
    char hex[4096];

    /* ---- 1. P2PKH ------------------------------------------------------- */
    {
        BRKey key; deriveKey(&key, 0);
        uint8_t inScript[64];
        size_t inLen = p2pkhScript(inScript, BRKeyHash160(&key));

        uint8_t outScript[64];
        size_t outLen = destScript(outScript, sizeof(outScript));

        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prevHash(0x11), 0, 100000000, inScript, inLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(tx, 99000000, outScript, outLen);

        check(signAndSerialize(tx, &key, 1, hex, sizeof(hex)), "P2PKH signs");
        check(strcmp(hex, EXPECT_P2PKH) == 0, "P2PKH serialization is byte-identical to baseline");
        if (strcmp(hex, EXPECT_P2PKH) != 0) printf("  got %s\n  want %s\n", hex, EXPECT_P2PKH);
        check(tx->inputs[0].sigLen > 0 && tx->inputs[0].witLen == 0,
              "P2PKH: scriptSig only, no witness");
        BRTransactionFree(tx);
    }

    /* ---- 2. P2WPKH ------------------------------------------------------ */
    {
        BRKey key; deriveKey(&key, 1);
        UInt160 h = BRKeyHash160(&key);
        uint8_t inScript[64];
        inScript[0] = OP_0; inScript[1] = 20; memcpy(&inScript[2], h.u8, 20);
        size_t inLen = 22;

        uint8_t outScript[64];
        size_t outLen = destScript(outScript, sizeof(outScript));

        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prevHash(0x22), 1, 100000000, inScript, inLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(tx, 99000000, outScript, outLen);

        check(signAndSerialize(tx, &key, 1, hex, sizeof(hex)), "P2WPKH signs");
        check(strcmp(hex, EXPECT_P2WPKH) == 0, "P2WPKH serialization is byte-identical to baseline");
        if (strcmp(hex, EXPECT_P2WPKH) != 0) printf("  got %s\n  want %s\n", hex, EXPECT_P2WPKH);
        check(tx->inputs[0].witLen > 0 && tx->inputs[0].sigLen == 0,
              "P2WPKH: witness only, no scriptSig");
        BRTransactionFree(tx);
    }

    /* ---- 3. P2TR key-path ----------------------------------------------- */
    {
        BRKey key; deriveKey(&key, 2);
        uint8_t xq[32];
        check(BRKeyTaprootOutputKey(&key, xq), "taproot output key derives");
        uint8_t inScript[64];
        inScript[0] = OP_1; inScript[1] = 32; memcpy(&inScript[2], xq, 32);
        size_t inLen = 34;

        uint8_t outScript[64];
        size_t outLen = destScript(outScript, sizeof(outScript));

        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prevHash(0x33), 2, 100000000, inScript, inLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(tx, 99000000, outScript, outLen);

        check(signAndSerialize(tx, &key, 1, hex, sizeof(hex)), "P2TR signs");
        check(strcmp(hex, EXPECT_P2TR) == 0, "P2TR serialization is byte-identical to baseline");
        if (strcmp(hex, EXPECT_P2TR) != 0) printf("  got %s\n  want %s\n", hex, EXPECT_P2TR);
        /* input->witness holds the stack ITEMS only — the item count is prepended at
         * serialization, not stored. So a key-path witness is 65 bytes: a 0x40
         * push opcode followed by the 64-byte Schnorr signature. Pinned because a
         * sibling branch inserted beside the P2TR one that fell through instead of
         * `continue`ing could overwrite it. */
        check(tx->inputs[0].witLen == 65 && tx->inputs[0].witness[0] == 0x40 &&
              tx->inputs[0].sigLen == 0,
              "P2TR: one 64-byte witness element, no scriptSig");
        BRTransactionFree(tx);
    }

    /* ---- 4. MIXED P2PKH + P2WPKH in one transaction ---------------------- */
    {
        BRKey keys[2]; deriveKey(&keys[0], 0); deriveKey(&keys[1], 1);

        uint8_t s0[64];
        size_t l0 = p2pkhScript(s0, BRKeyHash160(&keys[0]));

        UInt160 h1 = BRKeyHash160(&keys[1]);
        uint8_t s1[64]; s1[0] = OP_0; s1[1] = 20; memcpy(&s1[2], h1.u8, 20);

        uint8_t outScript[64];
        size_t outLen = destScript(outScript, sizeof(outScript));

        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prevHash(0x44), 0, 50000000, s0, l0, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddInput(tx, prevHash(0x55), 1, 50000000, s1, 22, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        BRTransactionAddOutput(tx, 99000000, outScript, outLen);

        check(signAndSerialize(tx, keys, 2, hex, sizeof(hex)), "mixed P2PKH+P2WPKH signs");
        check(strcmp(hex, EXPECT_MIXED) == 0, "mixed serialization is byte-identical to baseline");
        if (strcmp(hex, EXPECT_MIXED) != 0) printf("  got %s\n  want %s\n", hex, EXPECT_MIXED);
        /* Per-input branch selection: the legacy input carries a scriptSig and no
         * witness; the segwit input carries a witness and an empty scriptSig. */
        BRTransaction *t2 = tx;
        check(t2->inputs[0].sigLen > 0 && t2->inputs[0].witLen == 0,
              "mixed: input 0 took the P2PKH branch (scriptSig, no witness)");
        check(t2->inputs[1].witLen > 0 && t2->inputs[1].sigLen == 0,
              "mixed: input 1 took the P2WPKH branch (witness, no scriptSig)");
        BRTransactionFree(tx);
    }

    printf("\n%s\n", g_failures == 0 ? "ALL PASS" : "FAILURES");
    return g_failures == 0 ? 0 : 1;
}
