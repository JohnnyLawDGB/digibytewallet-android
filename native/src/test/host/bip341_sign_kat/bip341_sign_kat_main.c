// Host KAT for the tap-tweaked BIP-341 key-path Schnorr signer
// (BRKeyTaprootSchnorrSign), added to BRKey.c/BRKey.h in Taproot Sign-Task 2.
//
// The load-bearing vector is transcribed VERBATIM from the authoritative
// source: github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json
// (fetched directly; the specific fields below were extracted with a Python
// json parse before transcription -- see sign-task-2-report.md for the exact
// fetch/extract commands). It is `keyPathSpending[0].inputSpending[0]`, the
// ONLY key-path-spending input in that file whose `merkleRoot` is null -- i.e.
// the BIP-341 key-path-only (empty script tree) case, which is precisely what
// BRKeyTaprootSchnorrSign implements (BIP-86 taptweak: t = TaggedHash(
// "TapTweak", P) with NOTHING appended). The other six inputs commit to a
// non-null merkle root and therefore tweak the key with TaggedHash("TapTweak",
// P || merkleRoot); BRKeyTaprootSchnorrSign deliberately does NOT append a
// merkle root, so those inputs' output keys are different and are out of scope
// for this key-path-only signer.
//
//   internalPrivkey (d) = 6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa
//   merkleRoot          = null  (key-path-only)
//   internalPubkey (P)  = d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d
//   tweak (t)           = b86e7be8f39bab32a6f2c0443abbc210f0edac0e2c53d501b36b64437d9c6c70
//   sigHash (md)        = 2514a6272f85cfa0f45eb907fcb0d121b808ed37c6ea160a5a9046ed5526d555
//   witness[0]          = ed7c1647...b87276c 03   (65 bytes = 64-byte sig || 0x03 SIGHASH_SINGLE flag)
//
// The tweaked OUTPUT key X(Q) for this input is taken from `scriptPubKey[0]`
// (whose `given.internalPubkey` is the SAME d6889cb0... key and whose
// `given.scriptTree` is null): its `intermediary.tweakedPubkey` =
//   53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343
// This is the x-only output key any P2TR UTXO paying that internal key locks
// to, and it is the key a valid key-path witness signature must verify under.
//
// AUX HANDLING (documented per the brief): the BIP-341 witness signature above
// was produced with auxiliary randomness = 32 zero bytes, which
// secp256k1_schnorrsig_sign32 treats as identical to a NULL aux pointer. This
// was determined empirically (see report): signing the vector's tweakedPrivkey
// over the vector's sigHash with aux=NULL reproduces witness[0..64) exactly.
// BRKeyTaprootSchnorrSign also passes aux=NULL internally, so its output can be
// -- and is -- asserted for EXACT byte-for-byte equality against the published
// witness signature here (assertion 3 below), on top of the required
// validity (assertion 1) and tweak-math (assertion 2) gates.
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRKey.h"
#include "secp256k1.h"
#include "secp256k1_extrakeys.h"
#include "secp256k1_schnorrsig.h"

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

// --- BIP-341 wallet-test-vectors.json keyPathSpending[0].inputSpending[0]
//     (merkleRoot == null) + scriptPubKey[0].tweakedPubkey (same internal key)
static const char *kInternalPrivkey = "6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa";
static const char *kInternalPubkey  = "d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d";
static const char *kTweak           = "b86e7be8f39bab32a6f2c0443abbc210f0edac0e2c53d501b36b64437d9c6c70";
static const char *kSigHash         = "2514a6272f85cfa0f45eb907fcb0d121b808ed37c6ea160a5a9046ed5526d555";
// 64-byte Schnorr signature = witness[0][0..64) (the trailing 0x03 SIGHASH flag is dropped)
static const char *kWitnessSig64    = "ed7c1647cb97379e76892be0cacff57ec4a7102aa24296ca39af7541246d8ff1"
                                      "4d38958d4cc1e2e478e4d4a764bbfd835b16d4e314b72937b29833060b87276c";
// tweaked output x-only key X(Q) -- scriptPubKey[0].intermediary.tweakedPubkey
static const char *kTweakedPubkey   = "53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343";

// Extra arbitrary-key cross-checks: valid secp256k1 scalars (borrowed from the
// BIP-340 test-vector secret keys -- guaranteed on-curve, unrelated to the
// merkle-root question). These prove the signer<->address-path agreement for
// keys OTHER than the single published vector.
static const char *kArbSecrets[2] = {
    "0000000000000000000000000000000000000000000000000000000000000003",
    "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef",
};

int main(void)
{
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);

    UInt256 secret, md, tvec;
    uint8_t Pbytes[32], Qbytes[32], expected_sig[64];
    hex2bin(kInternalPrivkey, secret.u8, 32);
    hex2bin(kSigHash,         md.u8,     32);
    hex2bin(kTweak,           tvec.u8,   32);
    hex2bin(kInternalPubkey,  Pbytes,    32);
    hex2bin(kTweakedPubkey,   Qbytes,    32);
    hex2bin(kWitnessSig64,    expected_sig, 64);

    BRKey key;
    memset(&key, 0, sizeof(key));
    check(BRKeySetSecret(&key, &secret, 1) != 0, "BRKeySetSecret(internalPrivkey) succeeds");

    // Parse the vector's tweaked output key X(Q) -- signatures must verify under it.
    secp256k1_xonly_pubkey xoQ;
    check(secp256k1_xonly_pubkey_parse(ctx, &xoQ, Qbytes) != 0,
          "xonly_pubkey_parse of vector tweakedPubkey X(Q) succeeds");

    // ---- Assertion 0: BRKeyTaggedHash("TapTweak", P) equals the vector tweak t
    // (the exact tweak the signer computes internally -- BIP-86, no merkle root).
    {
        UInt256 t;
        BRKeyTaggedHash("TapTweak", Pbytes, 32, &t);
        check(memcmp(t.u8, tvec.u8, 32) == 0,
              "BRKeyTaggedHash(\"TapTweak\", P) == vector tweak t (no merkle root appended)");
    }

    // ---- Assertion 2 (tweak math): our X(Q) matches the vector's output key,
    // via BOTH the keypair-tweak path (the one the signer uses internally) and
    // the BRKeyTaprootOutputKey (receive/address) path.
    {
        // (a) keypair-tweak path -- byte-for-byte what BRKeyTaprootSchnorrSign does
        secp256k1_keypair kp;
        check(secp256k1_keypair_create(ctx, &kp, secret.u8) != 0,
              "keypair_create(internalPrivkey) succeeds (tweak-math path)");
        secp256k1_xonly_pubkey xo;
        check(secp256k1_keypair_xonly_pub(ctx, &xo, NULL, &kp) != 0,
              "keypair_xonly_pub succeeds (internal key P)");
        uint8_t p32[32];
        check(secp256k1_xonly_pubkey_serialize(ctx, p32, &xo) != 0,
              "xonly serialize of internal key succeeds");
        check(memcmp(p32, Pbytes, 32) == 0,
              "keypair-derived internal x-only key P == vector internalPubkey");
        UInt256 t;
        BRKeyTaggedHash("TapTweak", p32, 32, &t);
        check(secp256k1_keypair_xonly_tweak_add(ctx, &kp, t.u8) != 0,
              "keypair_xonly_tweak_add succeeds (parity handled internally)");
        uint8_t q32[32];
        secp256k1_xonly_pubkey xoq2;
        check(secp256k1_keypair_xonly_pub(ctx, &xoq2, NULL, &kp) != 0 &&
              secp256k1_xonly_pubkey_serialize(ctx, q32, &xoq2) != 0,
              "xonly serialize of tweaked keypair succeeds");
        check(memcmp(q32, Qbytes, 32) == 0,
              "keypair-tweak-path X(Q) == vector tweakedPubkey (tweak+parity correct)");
        memset(&kp, 0, sizeof(kp));
    }
    {
        // (b) BRKeyTaprootOutputKey (address/receive path) must agree with the vector too
        uint8_t out32[32];
        check(BRKeyTaprootOutputKey(&key, out32) == 1,
              "BRKeyTaprootOutputKey succeeds");
        check(memcmp(out32, Qbytes, 32) == 0,
              "BRKeyTaprootOutputKey X(Q) == vector tweakedPubkey (signer<->address agree)");
    }

    // ---- Assertion 1 (validity, load-bearing) + Assertion 3 (exact match):
    // BRKeyTaprootSchnorrSign signs md; result must (1) VERIFY under X(Q) and
    // (3) equal the published witness signature byte-for-byte (aux=NULL).
    {
        uint8_t sig[64];
        size_t n = BRKeyTaprootSchnorrSign(&key, sig, md);
        check(n == 64, "BRKeyTaprootSchnorrSign returns 64");

        check(secp256k1_schnorrsig_verify(ctx, sig, md.u8, 32, &xoQ) == 1,
              "VALIDITY: secp256k1_schnorrsig_verify ACCEPTS signature under tweaked output key X(Q)");

        check(memcmp(sig, expected_sig, 64) == 0,
              "EXACT: BRKeyTaprootSchnorrSign output == BIP-341 witness signature byte-for-byte");

        // Negative: the SAME signature must NOT verify under the UNtweaked
        // internal key P -- proving the taptweak was genuinely applied (a signer
        // that forgot the tweak would verify under P, not X(Q)).
        secp256k1_xonly_pubkey xoP;
        secp256k1_xonly_pubkey_parse(ctx, &xoP, Pbytes);
        check(secp256k1_schnorrsig_verify(ctx, sig, md.u8, 32, &xoP) == 0,
              "NEGATIVE: signature does NOT verify under untweaked internal key P (tweak really applied)");
    }

    // ---- Arbitrary-key cross-checks: for keys OTHER than the vector, prove the
    // signer's output key agrees with BRKeyTaprootOutputKey (the receive path),
    // that verify accepts, and that verify under the untweaked key rejects.
    for (int i = 0; i < 2; i++) {
        char label[192];
        UInt256 s2;
        hex2bin(kArbSecrets[i], s2.u8, 32);
        BRKey k2;
        memset(&k2, 0, sizeof(k2));
        snprintf(label, sizeof(label), "arb key %d: BRKeySetSecret succeeds", i);
        check(BRKeySetSecret(&k2, &s2, 1) != 0, label);

        // arbitrary but fixed message digest
        UInt256 m2;
        for (int j = 0; j < 32; j++) m2.u8[j] = (uint8_t)(0x10 + i * 32 + j);

        uint8_t sig2[64];
        snprintf(label, sizeof(label), "arb key %d: BRKeyTaprootSchnorrSign returns 64", i);
        check(BRKeyTaprootSchnorrSign(&k2, sig2, m2) == 64, label);

        uint8_t q2[32];
        snprintf(label, sizeof(label), "arb key %d: BRKeyTaprootOutputKey succeeds", i);
        check(BRKeyTaprootOutputKey(&k2, q2) == 1, label);
        secp256k1_xonly_pubkey xq2;
        secp256k1_xonly_pubkey_parse(ctx, &xq2, q2);
        snprintf(label, sizeof(label), "arb key %d: verify ACCEPTS under BRKeyTaprootOutputKey X(Q)", i);
        check(secp256k1_schnorrsig_verify(ctx, sig2, m2.u8, 32, &xq2) == 1, label);

        // untweaked internal x-only key -> must REJECT
        secp256k1_keypair kp2;
        secp256k1_keypair_create(ctx, &kp2, s2.u8);
        secp256k1_xonly_pubkey xp2;
        secp256k1_keypair_xonly_pub(ctx, &xp2, NULL, &kp2);
        memset(&kp2, 0, sizeof(kp2));
        snprintf(label, sizeof(label), "arb key %d: verify REJECTS under untweaked internal key", i);
        check(secp256k1_schnorrsig_verify(ctx, sig2, m2.u8, 32, &xp2) == 0, label);
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
