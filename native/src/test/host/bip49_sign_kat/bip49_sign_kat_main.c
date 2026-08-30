// Host KAT for the BIP49 (P2SH-wrapped segwit) SIGNING branch in
// BRTransactionSign — the path that lets recovery sweep an `S…` wallet.
//
// WHY IT DID NOT WORK BEFORE
//
// The signing loop matches keys with BRScriptPKH, which pulls the hash160 out of
// the scriptPubKey and compares it to hash160(pubkey). A BIP49 scriptPubKey
// commits hash160(REDEEMSCRIPT) instead, so no key ever matched and the input
// fell through unsigned. LegacySweepService refused the whole profile rather
// than emit a half-signed transaction.
//
// WHAT MAKES BIP49 DIFFERENT FROM EVERY OTHER INPUT TYPE HERE
//
// It is the only one that produces BOTH a scriptSig and a witness:
//   scriptSig = a single push of the redeemScript  (0x16 0x00 0x14 <hash160>)
//   witness   = push(sig) || push(pubkey)
// P2PKH is scriptSig-only, P2WPKH and P2TR are witness-only. That asymmetry is
// asserted directly, because it is exactly what a fall-through bug would break.
//
// THE LOAD-BEARING CHECK
//
// The DER signature is verified with secp256k1_ecdsa_verify against a BIP143
// sighash this KAT computes ITSELF, field by field from the spec, rather than by
// calling the signer's own helper. Recomputing with _BRTransactionWitnessData
// would only prove the signer agrees with itself. This proves the signature
// actually spends the coin.
//
// BIP143 also commits to the input AMOUNT, which the legacy P2PKH sighash does
// not — so a BIP49 input is structurally immune to the stale-amount class that
// LegacySweepService's amount-provenance gate exists to defend against.
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
#include "secp256k1.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) { printf("PASS: %s\n", desc); }
    else { printf("FAIL: %s\n", desc); g_failures++; }
}

/* ---- little helpers for building the BIP143 preimage by hand ------------- */

typedef struct { uint8_t *b; size_t n, cap; } Buf;

static void bufPut(Buf *w, const void *p, size_t n)
{
    if (w->n + n > w->cap) { w->cap = (w->n + n) * 2; w->b = realloc(w->b, w->cap); }
    memcpy(w->b + w->n, p, n);
    w->n += n;
}
static void bufU32(Buf *w, uint32_t v)
{
    uint8_t t[4] = { v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff };
    bufPut(w, t, 4);
}
static void bufU64(Buf *w, uint64_t v)
{
    uint8_t t[8];
    for (int i = 0; i < 8; i++) t[i] = (v >> (8 * i)) & 0xff;
    bufPut(w, t, 8);
}

int main(void)
{
    /* Fixed vector: the published all-zeros-entropy mnemonic, BIP49 path. */
    UInt512 seed;
    BRBIP39DeriveKey(seed.u8, "abandon abandon abandon abandon abandon abandon "
                              "abandon abandon abandon abandon abandon about", NULL);
    BRKey key;
    BRBIP32PrivKeyPath(&key, &seed, sizeof(seed), 5,
                       49 | BIP32_HARD, 20 | BIP32_HARD, 0 | BIP32_HARD, 0, (uint32_t)0);
    key.compressed = 1;
    memset(&seed, 0, sizeof(seed));

    uint8_t pubKey[65];
    size_t pkLen = BRKeyPubKey(&key, pubKey, sizeof(pubKey));
    check(pkLen == 33, "pubkey is compressed (BIP49 requires it)");

    /* redeemScript = OP_0 <20-byte keyhash>; scriptPubKey = HASH160(redeem) EQUAL */
    UInt160 keyHash = BRKeyHash160(&key);
    uint8_t redeem[22];
    redeem[0] = OP_0; redeem[1] = 20;
    memcpy(&redeem[2], keyHash.u8, 20);

    UInt160 redeemHash;
    BRHash160(&redeemHash, redeem, sizeof(redeem));

    uint8_t spk[23];
    spk[0] = OP_HASH160; spk[1] = 20;
    memcpy(&spk[2], redeemHash.u8, 20);
    spk[22] = OP_EQUAL;

    /* The address form the scan derives, so the KAT and production agree. */
    char addr[75];
    size_t addrLen = BRAddressFromScriptPubKey(addr, sizeof(addr), spk, sizeof(spk));
    check(addrLen > 0 && addr[0] == 'S', "BIP49 scriptPubKey encodes to an S… address");
    printf("  address  %s\n", addr);

    /* ---- build and sign ------------------------------------------------- */

    const uint64_t inAmount  = 100000000;   /* 1 DGB */
    const uint64_t outAmount =  99000000;
    UInt256 prev = UINT256_ZERO;
    memset(prev.u8, 0x77, sizeof(prev.u8));

    uint8_t outScript[25];
    outScript[0] = OP_DUP; outScript[1] = OP_HASH160; outScript[2] = 20;
    memset(&outScript[3], 0xab, 20);
    outScript[23] = OP_EQUALVERIFY; outScript[24] = OP_CHECKSIG;

    BRTransaction *tx = BRTransactionNew();
    BRTransactionAddInput(tx, prev, 0, inAmount, spk, sizeof(spk), NULL, 0, NULL, 0, TXIN_SEQUENCE);
    BRTransactionAddOutput(tx, outAmount, outScript, sizeof(outScript));

    check(BRTransactionSign(tx, 0, &key, 1) == 1, "BIP49 input signs");
    check(BRTransactionIsSigned(tx), "transaction reports signed");

    /* ---- the asymmetry: BOTH a scriptSig and a witness ------------------- */

    const BRTxInput *in = &tx->inputs[0];
    check(in->sigLen == 23 && in->signature[0] == 22 &&
          memcmp(&in->signature[1], redeem, sizeof(redeem)) == 0,
          "scriptSig is a single push of the redeemScript");
    check(in->witLen > 0, "witness is present — BIP49 carries BOTH, unlike every other type");

    /* witness stack items, no leading count (see sign_regression_kat).
     * Guarded: with no BIP49 branch the input is unsigned and witness is NULL,
     * and a segfault is a useless way for a gate to go red. */
    if (in->witLen == 0 || ! in->witness) {
        check(0, "witness item 0 is a DER signature (input is UNSIGNED)");
        check(0, "witness item 1 is the compressed pubkey (input is UNSIGNED)");
        check(0, "witness signature parses as DER (input is UNSIGNED)");
        check(0, "signature VERIFIES against an independently computed BIP143 sighash (UNSIGNED)");
        check(0, "signature does NOT verify against a one-satoshi-different amount (UNSIGNED)");
        BRTransactionFree(tx);
        printf("\n%s\n", "FAILURES");
        return 1;
    }

    size_t sigItemLen = in->witness[0];
    check(sigItemLen >= 70 && sigItemLen <= 73, "witness item 0 is a DER signature");
    const uint8_t *derSig = &in->witness[1];
    size_t wpkOff = 1 + sigItemLen;
    check(in->witness[wpkOff] == 33 &&
          memcmp(&in->witness[wpkOff + 1], pubKey, 33) == 0,
          "witness item 1 is the compressed pubkey");

    /* ---- independent BIP143 sighash ------------------------------------- */

    Buf w = { 0 };
    UInt256 hashPrevouts, hashSequence, hashOutputs, sighash;

    { Buf t = { 0 }; bufPut(&t, prev.u8, 32); bufU32(&t, 0);
      BRSHA256_2(&hashPrevouts, t.b, t.n); free(t.b); }
    { Buf t = { 0 }; bufU32(&t, TXIN_SEQUENCE);
      BRSHA256_2(&hashSequence, t.b, t.n); free(t.b); }
    { Buf t = { 0 }; bufU64(&t, outAmount);
      uint8_t len = (uint8_t)sizeof(outScript); bufPut(&t, &len, 1);
      bufPut(&t, outScript, sizeof(outScript));
      BRSHA256_2(&hashOutputs, t.b, t.n); free(t.b); }

    bufU32(&w, 1);                                  /* nVersion */
    bufPut(&w, hashPrevouts.u8, 32);
    bufPut(&w, hashSequence.u8, 32);
    bufPut(&w, prev.u8, 32); bufU32(&w, 0);         /* outpoint */
    /* scriptCode for P2WPKH (and for P2SH-P2WPKH — BIP143 says they are the
     * same): 0x19 76 a9 14 <keyhash> 88 ac */
    { uint8_t sc[26] = { 0x19, OP_DUP, OP_HASH160, 20 };
      memcpy(&sc[4], keyHash.u8, 20); sc[24] = OP_EQUALVERIFY; sc[25] = OP_CHECKSIG;
      bufPut(&w, sc, sizeof(sc)); }
    bufU64(&w, inAmount);                           /* the amount BIP143 commits to */
    bufU32(&w, TXIN_SEQUENCE);
    bufPut(&w, hashOutputs.u8, 32);
    bufU32(&w, 0);                                  /* nLockTime */
    bufU32(&w, 0x01);                               /* SIGHASH_ALL */

    BRSHA256_2(&sighash, w.b, w.n);
    free(w.b);

    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
    secp256k1_pubkey pk;
    secp256k1_ecdsa_signature sig;
    int parsedPk  = secp256k1_ec_pubkey_parse(ctx, &pk, pubKey, 33);
    int parsedSig = secp256k1_ecdsa_signature_parse_der(ctx, &sig, derSig, sigItemLen - 1);
    check(parsedPk && parsedSig, "witness signature parses as DER");
    check(parsedPk && parsedSig &&
          secp256k1_ecdsa_verify(ctx, &sig, sighash.u8, &pk) == 1,
          "signature VERIFIES against an independently computed BIP143 sighash");
    secp256k1_context_destroy(ctx);

    /* A wrong amount must not verify — proves BIP143 really committed to it,
     * which is why a BIP49 input cannot be hit by the stale-amount class. */
    {
        Buf w2 = { 0 };
        UInt256 sh2;
        bufU32(&w2, 1);
        bufPut(&w2, hashPrevouts.u8, 32);
        bufPut(&w2, hashSequence.u8, 32);
        bufPut(&w2, prev.u8, 32); bufU32(&w2, 0);
        { uint8_t sc[26] = { 0x19, OP_DUP, OP_HASH160, 20 };
          memcpy(&sc[4], keyHash.u8, 20); sc[24] = OP_EQUALVERIFY; sc[25] = OP_CHECKSIG;
          bufPut(&w2, sc, sizeof(sc)); }
        bufU64(&w2, inAmount + 1);                  /* one satoshi off */
        bufU32(&w2, TXIN_SEQUENCE);
        bufPut(&w2, hashOutputs.u8, 32);
        bufU32(&w2, 0);
        bufU32(&w2, 0x01);
        BRSHA256_2(&sh2, w2.b, w2.n);
        free(w2.b);

        secp256k1_context *c2 = secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
        secp256k1_pubkey pk2; secp256k1_ecdsa_signature s2;
        int ok = secp256k1_ec_pubkey_parse(c2, &pk2, pubKey, 33) &&
                 secp256k1_ecdsa_signature_parse_der(c2, &s2, derSig, sigItemLen - 1) &&
                 secp256k1_ecdsa_verify(c2, &s2, sh2.u8, &pk2) == 1;
        check(! ok, "signature does NOT verify against a one-satoshi-different amount");
        secp256k1_context_destroy(c2);
    }

    BRTransactionFree(tx);

    printf("\n%s\n", g_failures == 0 ? "ALL PASS" : "FAILURES");
    return g_failures == 0 ? 0 : 1;
}
