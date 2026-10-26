// Host KAT: a DigiDollar recovery transfer whose fee is paid by coins found under two derivation
// profiles signs every one of them -- each with the key of its own derivation.
//
// THE INVARIANT. The dollars of a recovered seed sit at m/86'/20'/0'; the DGB paying their fee sits
// wherever the scan found it, and one transfer's fee may need coins from two profiles. The bridge's
// buildAndSignForeignDigiDollarTransfer derives each fee input's key through digidollar_fee_keys.h from
// that input's own derivation (HMAC key, prefix, chain, index). This KAT builds such a transfer -- one
// DigiDollar input at m/86'/20'/0'/0/0, one fee coin from the BIP84 profile ("Bitcoin seed",
// m/84'/20'/0'/0/0) and one from the legacy DigiByte profile ("DigiByte seed", m/0'/0/1), both
// native segwit -- signs it with BRTransactionSign, and verifies each fee input's signature against a
// BIP143 sighash computed here, field by field, with the public key that hashes to that coin's script.
//
// RED (comparison arm, -DDIGIDOLLAR_FEE_KEYS_UNFIXED=1): one derivation for every fee input -- the
// single HMAC key and prefix the bridge used to take. The second coin's key then comes from the wrong
// derivation, BRTransactionSign finds no key for it, and that input stays unsigned: the case must fail.
// GUARDS (both arms): two fee coins of ONE profile both sign; a prefix too long to hold is refused.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
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
#include "digidollar_fee_keys.h"

#define H 0x80000000u
static int g_failed = 0;
static void ck(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_failed = 1; }

static UInt512 g_seed;

typedef struct { uint8_t *b; size_t n, cap; } Buf;
static void bufPut(Buf *w, const void *p, size_t n)
{
    if (w->n + n > w->cap) { w->cap = (w->n + n) * 2 + 64; w->b = realloc(w->b, w->cap); }
    memcpy(w->b + w->n, p, n); w->n += n;
}
static void bufU32(Buf *w, uint32_t v) { uint8_t b[4] = { v, v >> 8, v >> 16, v >> 24 }; bufPut(w, b, 4); }
static void bufU64(Buf *w, uint64_t v) { for (int i = 0; i < 8; i++) { uint8_t b = (uint8_t)(v >> (8 * i)); bufPut(w, &b, 1); } }

// The key at `path` under `hmac`, derived here -- independently of the header under test.
static BRKey keyAt(const char *hmac, const uint32_t *path, size_t depth)
{
    BRKey k;
    BRBIP32PrivKeyArrayPath(&k, &g_seed, sizeof(g_seed), hmac, path, depth);
    return k;
}

static size_t p2wpkhScript(BRKey *k, uint8_t out[22])
{
    UInt160 h = BRKeyHash160(k);
    out[0] = 0x00; out[1] = 0x14; memcpy(out + 2, h.u8, 20);
    return 22;
}

// Whether fee input `i` (P2WPKH, `amount` sat) carries a signature that verifies against a BIP143
// sighash computed here, by a public key that hashes to the input's own script.
static int p2wpkhVerifies(const BRTransaction *tx, size_t i, uint64_t amount, char *why, size_t whyLen)
{
    const BRTxInput *in = &tx->inputs[i];
    if (!in->witness || in->witLen < 2) { snprintf(why, whyLen, "input %zu carries no witness", i); return 0; }
    size_t sigLen = in->witness[0];
    if (sigLen < 9 || 1 + sigLen + 1 + 33 > in->witLen || in->witness[1 + sigLen] != 33) {
        snprintf(why, whyLen, "input %zu's witness is not <signature> <33-byte key>", i); return 0;
    }
    const uint8_t *der = &in->witness[1], *pub = &in->witness[2 + sigLen];
    BRKey pk;
    if (!BRKeySetPubKey(&pk, pub, 33)) { snprintf(why, whyLen, "input %zu's key does not parse", i); return 0; }
    UInt160 h = BRKeyHash160(&pk);
    if (in->scriptLen != 22 || memcmp(in->script + 2, h.u8, 20) != 0) {
        snprintf(why, whyLen, "input %zu's key does not hash to its script", i); return 0;
    }

    UInt256 hashPrevouts, hashSequence, hashOutputs, sighash;
    Buf t = { 0 };
    for (size_t j = 0; j < tx->inCount; j++) { bufPut(&t, tx->inputs[j].txHash.u8, 32); bufU32(&t, tx->inputs[j].index); }
    BRSHA256_2(&hashPrevouts, t.b, t.n); t.n = 0;
    for (size_t j = 0; j < tx->inCount; j++) bufU32(&t, tx->inputs[j].sequence);
    BRSHA256_2(&hashSequence, t.b, t.n); t.n = 0;
    for (size_t j = 0; j < tx->outCount; j++) {
        uint8_t len = (uint8_t)tx->outputs[j].scriptLen;   // every script here is under 253 bytes
        bufU64(&t, tx->outputs[j].amount); bufPut(&t, &len, 1); bufPut(&t, tx->outputs[j].script, len);
    }
    BRSHA256_2(&hashOutputs, t.b, t.n); t.n = 0;
    bufU32(&t, tx->version);
    bufPut(&t, hashPrevouts.u8, 32);
    bufPut(&t, hashSequence.u8, 32);
    bufPut(&t, in->txHash.u8, 32); bufU32(&t, in->index);
    uint8_t sc[26] = { 0x19, 0x76, 0xa9, 0x14 };
    memcpy(sc + 4, h.u8, 20); sc[24] = 0x88; sc[25] = 0xac;
    bufPut(&t, sc, sizeof(sc));
    bufU64(&t, amount);
    bufU32(&t, in->sequence);
    bufPut(&t, hashOutputs.u8, 32);
    bufU32(&t, tx->lockTime);
    bufU32(&t, 0x01);   // SIGHASH_ALL
    BRSHA256_2(&sighash, t.b, t.n);
    free(t.b);

    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_VERIFY);
    secp256k1_pubkey sp;
    secp256k1_ecdsa_signature sig;
    int ok = secp256k1_ec_pubkey_parse(ctx, &sp, pub, 33) &&
             secp256k1_ecdsa_signature_parse_der(ctx, &sig, der, sigLen - 1) &&
             secp256k1_ecdsa_verify(ctx, &sig, sighash.u8, &sp) == 1;
    secp256k1_context_destroy(ctx);
    if (!ok) snprintf(why, whyLen, "input %zu's signature does not verify against its BIP143 sighash", i);
    return ok;
}

// A DigiDollar transfer of $1.00 moving one m/86' coin, its fee paid by `nFee` coins at `feeScripts`.
static BRTransaction *transfer(const uint8_t ddScript[34], const uint8_t (*feeScripts)[22], const uint64_t *feeSats, size_t nFee)
{
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    UInt256 h;
    memset(h.u8, 0x31, 32);
    BRTransactionAddInput(tx, h, 0, 0, ddScript, 34, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    for (size_t i = 0; i < nFee; i++) {
        memset(h.u8, 0x41 + (int)i, 32);
        BRTransactionAddInput(tx, h, (uint32_t)i, feeSats[i], feeScripts[i], 22, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    }
    uint8_t recipient[34] = { 0x51, 0x20 };
    memset(recipient + 2, 0xbb, 32);
    BRTransactionAddOutput(tx, 0, recipient, sizeof(recipient));
    const uint8_t orr[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x01, 0x64 };   // 'DD' type 2 [100]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));
    return tx;
}

// Sign `tx` with the m/86' key plus the fee keys the header derives from `paths`; then verify every fee input.
static void signAndVerify(BRTransaction *tx, BRKey ddKey, const DdFeeInputPath *paths, const uint64_t *feeSats, size_t nFee)
{
    BRKey keys[4];
    char line[200], why[160];
    keys[0] = ddKey;
    ck(dd_fee_input_keys(keys + 1, paths, nFee, &g_seed, sizeof(g_seed)), "every fee input's key is derived");
    int signedOk = BRTransactionSign(tx, 0, keys, 1 + nFee);
    ck(signedOk && BRTransactionIsSigned(tx), "the transfer is signed in full: the DigiDollar input and every fee input");
    ck(tx->inputs[0].witLen >= 64 && tx->inputs[0].sigLen == 0, "the DigiDollar input carries its Schnorr key-path witness");
    for (size_t i = 0; i < nFee; i++) {
        int ok = p2wpkhVerifies(tx, 1 + i, feeSats[i], why, sizeof(why));
        snprintf(line, sizeof(line), "fee input %zu (%s, %s chain %u index %u) verifies%s%s", i, paths[i].hmacKey,
                 paths[i].prefixLen == 3 ? "m/84'/20'/0'" : "m/0'", paths[i].chain, paths[i].index, ok ? "" : ": ", ok ? "" : why);
        ck(ok, line);
    }
    for (size_t i = 0; i < 1 + nFee; i++) BRKeyClean(&keys[i]);
}

static BRKey ddKeyAndScript(uint8_t script[34])
{
    const uint32_t ddPath[5] = { 86 | H, 20 | H, 0 | H, 0, 0 };
    BRKey k = keyAt("Bitcoin seed", ddPath, 5);
    script[0] = 0x51; script[1] = 0x20;
    BRKeyTaprootOutputKey(&k, script + 2);
    return k;
}

static void two_profiles_both_verify(void)
{
    uint8_t ddScript[34];
    BRKey ddKey = ddKeyAndScript(ddScript);
    // The coins as the scan found them: one under BIP84, one under the legacy DigiByte path.
    const uint32_t bip84[5] = { 84 | H, 20 | H, 0 | H, 0, 0 };
    const uint32_t legacy[3] = { 0 | H, 0, 1 };
    BRKey a = keyAt("Bitcoin seed", bip84, 5), b = keyAt("DigiByte seed", legacy, 3);
    uint8_t feeScripts[2][22];
    p2wpkhScript(&a, feeScripts[0]);
    p2wpkhScript(&b, feeScripts[1]);
    const uint64_t feeSats[2] = { 6000000, 5000000 };   // neither pays the 0.1 DGB fee alone
    DdFeeInputPath paths[2] = {
        { "Bitcoin seed", { 84 | H, 20 | H, 0 | H }, 3, 0, 0 },
        { "DigiByte seed", { 0 | H }, 1, 0, 1 },
    };
    BRTransaction *tx = transfer(ddScript, (const uint8_t (*)[22])feeScripts, feeSats, 2);
    signAndVerify(tx, ddKey, paths, feeSats, 2);
    BRTransactionFree(tx);
    BRKeyClean(&a); BRKeyClean(&b); BRKeyClean(&ddKey);
}

static void one_profile_signs(void)
{
    uint8_t ddScript[34];
    BRKey ddKey = ddKeyAndScript(ddScript);
    const uint32_t p0[5] = { 84 | H, 20 | H, 0 | H, 0, 0 }, p1[5] = { 84 | H, 20 | H, 0 | H, 1, 3 };
    BRKey a = keyAt("Bitcoin seed", p0, 5), b = keyAt("Bitcoin seed", p1, 5);
    uint8_t feeScripts[2][22];
    p2wpkhScript(&a, feeScripts[0]);
    p2wpkhScript(&b, feeScripts[1]);
    const uint64_t feeSats[2] = { 6000000, 5000000 };
    DdFeeInputPath paths[2] = {
        { "Bitcoin seed", { 84 | H, 20 | H, 0 | H }, 3, 0, 0 },
        { "Bitcoin seed", { 84 | H, 20 | H, 0 | H }, 3, 1, 3 },
    };
    BRTransaction *tx = transfer(ddScript, (const uint8_t (*)[22])feeScripts, feeSats, 2);
    signAndVerify(tx, ddKey, paths, feeSats, 2);
    BRTransactionFree(tx);
    BRKeyClean(&a); BRKeyClean(&b); BRKeyClean(&ddKey);
}

static void bad_path_refused(void)
{
    BRKey keys[1];
    DdFeeInputPath tooLong = { "Bitcoin seed", { 0 }, DD_FEE_PATH_MAX + 1, 0, 0 };
    ck(!dd_fee_input_keys(keys, &tooLong, 1, &g_seed, sizeof(g_seed)), "a prefix longer than the path can hold derives no key");
    DdFeeInputPath noHmac = { NULL, { 84 | H, 20 | H, 0 | H }, 3, 0, 0 };
    ck(!dd_fee_input_keys(keys, &noHmac, 1, &g_seed, sizeof(g_seed)), "a derivation with no HMAC key derives no key");
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "two_profiles_both_verify", two_profiles_both_verify },
    { "one_profile_signs", one_profile_signs },
    { "bad_path_refused", bad_path_refused },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    BRBIP39DeriveKey(g_seed.u8, "abandon abandon abandon abandon abandon abandon abandon abandon "
                                "abandon abandon abandon about", NULL);
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", DIGIDOLLAR_FEE_KEYS_UNFIXED ? "comparison (DIGIDOLLAR_FEE_KEYS_UNFIXED=1)" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
