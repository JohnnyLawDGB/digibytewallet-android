// Host KAT for BIP-340 Schnorr signing (BRKeySchnorrSign) + tagged hash
// (BRKeyTaggedHash), added to BRKey.c/BRKey.h in Taproot Task 3.
//
// Vectors 0-3 (the "sign + verify" rows) are transcribed verbatim from the
// authoritative source: github.com/bitcoin/bips/blob/master/bip-0340/
// test-vectors.csv (fetched directly; byte lengths re-verified with a
// Python csv parse before transcription -- see task-3-report.md for the
// exact fetch/verify commands run). Vector 6 (a "verification result:
// FALSE" row, comment "has_even_y(R) is false") is included as the
// negative case.
//
// IMPORTANT correction vs. the task brief: the brief claimed "indices 0-3
// are deterministic with aux_rand = 32 zero bytes". That is only true for
// index 0 -- checked directly against the CSV:
//   index 0 aux_rand = 32 x 0x00
//   index 1 aux_rand = 31 x 0x00 followed by a single 0x01 byte
//   index 2 aux_rand = C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906 (pseudorandom)
//   index 3 aux_rand = 32 x 0xFF
// BRKeySchnorrSign's interface (BRKey *, sig64, UInt256 md) has no aux_rand
// argument at all -- per the brief's own sketch it always passes NULL aux
// internally (a deliberate choice: NULL and all-zero are defined to be
// equivalent by secp256k1_schnorrsig_sign32, and a fixed aux keeps the
// primitive's output a pure function of (key, md), useful for reproducible
// signing/testing; production randomization is left to a later phase per
// the brief's own comment). That means BRKeySchnorrSign's own output can
// only be checked for EXACT byte-for-byte equality against a published
// vector when that vector's aux really is zero (index 0 only). For indices
// 1-3 this KAT does two separate things instead:
//   (a) calls BRKeySchnorrSign itself (exercising the real production
//       function, fixed aux) and confirms the result is a VALID BIP-340
//       signature via secp256k1_schnorrsig_verify against that vector's
//       real secret key / message / pubkey -- proving the wrapper is
//       spec-correct for real-world (non-toy) key material, not just
//       vector 0's key=3 case.
//   (b) calls secp256k1_schnorrsig_sign32 directly (bypassing the
//       fixed-aux wrapper) with the VECTOR's own aux_rand bytes, and checks
//       THAT result for exact byte equality against the published
//       signature -- proving the underlying vendored secp256k1 build
//       reproduces the official BIP-340 vectors bit-for-bit.
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

typedef struct {
    const char *sk;   // 64 hex chars (32 bytes)
    const char *pk;   // 64 hex chars (32 bytes, x-only)
    const char *aux;  // 64 hex chars (32 bytes)
    const char *msg;  // 64 hex chars (32 bytes)
    const char *sig;  // 128 hex chars (64 bytes)
} SignVector;

// bip-0340/test-vectors.csv, indices 0-3 (the sign+verify rows), transcribed
// verbatim -- see file header for provenance and the aux_rand correction.
static const SignVector kSignVectors[4] = {
    { // index 0 -- aux_rand genuinely all-zero
        "0000000000000000000000000000000000000000000000000000000000000003",
        "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0000000000000000000000000000000000000000000000000000000000000000",
        "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
    },
    { // index 1 -- aux_rand = 31 zero bytes + 0x01 (NOT all-zero)
        "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
        "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
        "0000000000000000000000000000000000000000000000000000000000000001",
        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
        "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"
    },
    { // index 2 -- aux_rand pseudorandom (NOT all-zero)
        "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
        "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
        "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
        "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
        "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7"
    },
    { // index 3 -- aux_rand all-0xFF (NOT all-zero); also exercises msg
      // being all-0xFF (comment: "test fails if msg is reduced modulo p or n")
        "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
        "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
        "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3"
    },
};

// index 6 -- a "verification result: FALSE" row (comment: "has_even_y(R) is
// false"): verify-only, no secret key in the CSV for this row.
static const char *kNegPubkey = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659";
static const char *kNegMsg    = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89";
static const char *kNegSig    = "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2";

int main(void)
{
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);

    // --- BRKeyTaggedHash cross-check against secp256k1's own reference
    // implementation of the identical BIP-340 construction
    // (SHA256(SHA256(tag)||SHA256(tag)||msg), per secp256k1.h's doc comment
    // for secp256k1_tagged_sha256). Two different tag/msg pairs.
    {
        const char *tag = "BIP0340/challenge";
        const uint8_t msg[3] = { 0xAA, 0xBB, 0xCC };
        UInt256 ours;
        uint8_t theirs[32];
        BRKeyTaggedHash(tag, msg, sizeof(msg), &ours);
        secp256k1_tagged_sha256(ctx, theirs, (const unsigned char *)tag, strlen(tag), msg, sizeof(msg));
        check(memcmp(ours.u8, theirs, 32) == 0,
              "BRKeyTaggedHash(\"BIP0340/challenge\", 0xAABBCC) matches secp256k1_tagged_sha256");
    }
    {
        const char *tag = "TapTweak";
        uint8_t msg[32];
        hex2bin("0000000000000000000000000000000000000000000000000000000000000000", msg, 32);
        UInt256 ours;
        uint8_t theirs[32];
        BRKeyTaggedHash(tag, msg, sizeof(msg), &ours);
        secp256k1_tagged_sha256(ctx, theirs, (const unsigned char *)tag, strlen(tag), msg, sizeof(msg));
        check(memcmp(ours.u8, theirs, 32) == 0,
              "BRKeyTaggedHash(\"TapTweak\", 32 zero bytes) matches secp256k1_tagged_sha256");
    }

    // --- BIP-340 sign+verify vectors, indices 0-3
    for (int i = 0; i < 4; i++) {
        const SignVector *v = &kSignVectors[i];
        char label[160];

        UInt256 secret;
        hex2bin(v->sk, secret.u8, 32);
        BRKey key;
        memset(&key, 0, sizeof(key));
        check(BRKeySetSecret(&key, &secret, 1) != 0, "BRKeySetSecret succeeds");

        UInt256 md;
        hex2bin(v->msg, md.u8, 32);

        uint8_t pk_bytes[32];
        hex2bin(v->pk, pk_bytes, 32);
        secp256k1_xonly_pubkey xonly;
        int parsed = secp256k1_xonly_pubkey_parse(ctx, &xonly, pk_bytes);
        snprintf(label, sizeof(label), "index %d: xonly_pubkey_parse succeeds", i);
        check(parsed, label);

        uint8_t expected_sig[64];
        hex2bin(v->sig, expected_sig, 64);

        // (a) the real production primitive (fixed/NULL aux)
        uint8_t sig[64];
        size_t n = BRKeySchnorrSign(&key, sig, md);
        snprintf(label, sizeof(label), "index %d: BRKeySchnorrSign returns 64", i);
        check(n == 64, label);

        int verify_ok = secp256k1_schnorrsig_verify(ctx, sig, md.u8, 32, &xonly);
        snprintf(label, sizeof(label), "index %d: secp256k1_schnorrsig_verify accepts BRKeySchnorrSign output", i);
        check(verify_ok == 1, label);

        if (i == 0) {
            // aux_rand is genuinely all-zero for this vector, so the
            // fixed-aux production primitive must reproduce it exactly.
            snprintf(label, sizeof(label), "index %d: BRKeySchnorrSign output matches published vector signature exactly", i);
            check(memcmp(sig, expected_sig, 64) == 0, label);
        } else {
            // (b) direct secp call using the VECTOR's own aux_rand (bypasses
            // the fixed-aux wrapper) -- proves the vendored secp256k1 build
            // reproduces the official vector bit-for-bit.
            uint8_t aux[32];
            hex2bin(v->aux, aux, 32);
            secp256k1_keypair kp;
            check(secp256k1_keypair_create(ctx, &kp, secret.u8) != 0, "keypair_create succeeds (direct-aux check)");
            uint8_t sig_direct_aux[64];
            secp256k1_schnorrsig_sign32(ctx, sig_direct_aux, md.u8, &kp, aux);
            memset(&kp, 0, sizeof(kp));
            snprintf(label, sizeof(label), "index %d: secp256k1_schnorrsig_sign32 with vector's own aux_rand matches published vector signature exactly", i);
            check(memcmp(sig_direct_aux, expected_sig, 64) == 0, label);
        }
    }

    // --- negative vector: index 6, verification result FALSE
    {
        uint8_t pk_bytes[32], msg[32], sig[64];
        hex2bin(kNegPubkey, pk_bytes, 32);
        hex2bin(kNegMsg, msg, 32);
        hex2bin(kNegSig, sig, 64);
        secp256k1_xonly_pubkey xonly;
        check(secp256k1_xonly_pubkey_parse(ctx, &xonly, pk_bytes) != 0, "index 6 (negative): xonly_pubkey_parse succeeds");
        int verify_ok = secp256k1_schnorrsig_verify(ctx, sig, msg, 32, &xonly);
        check(verify_ok == 0, "index 6 (negative, has_even_y(R) is false): secp256k1_schnorrsig_verify correctly REJECTS");
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
