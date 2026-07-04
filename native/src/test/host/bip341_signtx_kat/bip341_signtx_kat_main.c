// Host KAT for the Taproot key-path SIGNING integration (Sign-Task 4):
// BRWalletSignTransaction (BRWallet.c) collecting m/86' privkeys for P2TR inputs
// + BRTransactionSign (BRTransaction.c) producing a valid witness-v1 key-path
// witness. This is the fund-safety-critical wiring that turns a P2TR-input
// transaction into a broadcastable, network-valid spend.
//
// Unlike bip341_sign_kat (which checks the low-level BRKeyTaprootSchnorrSign
// against the published BIP-341 vector), this KAT drives the REAL end-to-end
// wallet path: build a wallet from a fixed seed, install its m/86' taproot key,
// take a real P2TR receive address, synthesize a UTXO paying it, build a spend
// transaction, and sign it with BRWalletSignTransaction — then prove the
// resulting witness is a valid BIP-341 key-path witness.
//
// The load-bearing assertion (VALIDITY): the produced witness is a stack of
// exactly ONE 64-byte element, and that 64-byte Schnorr signature is ACCEPTED
// by secp256k1_schnorrsig_verify under the input's x-only output key X(Q) for
// the BIP-341 key-path sighash of this exact transaction. The sighash is
// recomputed with the SAME proven helper the signer used
// (_BRTransactionTaprootSighash — file-static, reached by #include-ing the real
// BRTransaction.c translation unit, exactly like bip341_sighash_kat), so this
// is a true "the signature that came out actually spends the coin" check.
//
// Also asserts NO REGRESSION: a BIP84/P2WPKH-only spend still signs and reports
// signed (its 2-item witness left untouched by the taproot path).
//
// Fixed vector: the canonical all-zeros-entropy BIP39 mnemonic; the m/86'/20'/0'/0/0
// P2TR receive address is the KAT-pinned dgb1pcevt23... (see TaprootReceiveAddressTest
// / TaprootReloadBalanceTest).
//
// Exit code 0 = all checks passed, 1 = at least one failed (or build error).

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

// Pull in the real source so the file-static _BRTransactionTaprootSighash is
// visible. Do NOT also compile BRTransaction.c on the clang line (it would
// define its symbols twice). BRWallet.c IS compiled separately and references
// BRTransaction's (now in-this-TU) non-static symbols; the linker resolves them.
#include "BRTransaction.c"

#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRAddress.h"
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

// canonical all-zeros-entropy BIP39 mnemonic
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// KAT-pinned m/86'/20'/0'/0/0 P2TR receive address for the above seed
static const char *kTaproot0 =
    "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q";

int main(void)
{
    secp256k1_context *ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN | SECP256K1_CONTEXT_VERIFY);

    // --- Build a wallet from the fixed seed, with its m/86' taproot key installed
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);

    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));

    BRWallet *wallet = BRWalletNew(NULL, 0, mpk84);
    check(wallet != NULL, "BRWalletNew (BIP84) succeeds");
    if (! wallet) { printf("\nSOME FAILED (fatal)\n"); return 1; }
    BRWalletSetTaprootKey(wallet, mpk86);

    // --- Take a real P2TR receive address (scriptType 2 = taproot chain)
    BRAddress tAddr = BRWalletReceiveAddress(wallet, 2);
    check(strcmp(tAddr.s, kTaproot0) == 0, "receive addr[taproot,0] == KAT-pinned dgb1p...");

    // scriptPubKey for the P2TR address: {OP_1, 0x20, X(Q)}
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), tAddr.s);
    check(spkLen == 34 && spk[0] == OP_1 && spk[1] == 32,
          "P2TR scriptPubKey is {OP_1, 0x20, <32>} (34 bytes)");

    // X(Q) = the 32-byte program (what a valid key-path sig must verify under)
    uint8_t xq[32];
    memcpy(xq, &spk[2], 32);
    secp256k1_xonly_pubkey xoQ;
    check(secp256k1_xonly_pubkey_parse(ctx, &xoQ, xq) == 1, "parse X(Q) from scriptPubKey");

    // A plausible destination: the wallet's first P2WPKH receive address.
    BRAddress destAddr = BRWalletReceiveAddress(wallet, 1);
    uint8_t dspk[64];
    size_t dspkLen = BRAddressScriptPubKey(dspk, sizeof(dspk), destAddr.s);
    check(dspkLen > 0, "destination P2WPKH scriptPubKey resolves");

    const uint64_t inAmount  = 250000000ULL; // 2.5 DGB
    const uint64_t outAmount = inAmount - 100000ULL;

    // ============================================================
    // Scenario 1 — sign a spend of the synthetic P2TR UTXO
    // ============================================================
    BRTransaction *tx = BRTransactionNew();
    UInt256 prevHash;
    memset(prevHash.u8, 0x11, sizeof(prevHash));
    // BRTxInputSetScript(scriptPubKey) sets input->address to the P2TR dgb1p... too.
    BRTransactionAddInput(tx, prevHash, 0, inAmount, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, outAmount, dspk, dspkLen);

    check(strcmp(tx->inputs[0].address, kTaproot0) == 0,
          "spend input carries the P2TR address (matchable by the wallet)");

    int r = BRWalletSignTransaction(wallet, tx, 0, seed, sizeof(seed));
    check(r == 1, "BRWalletSignTransaction(P2TR spend) returns 1 (signed)");
    check(BRTransactionIsSigned(tx) == 1, "P2TR spend reports fully signed");

    BRTxInput *in = &tx->inputs[0];

    // scriptSig must be empty (present-but-empty so the IsSigned gate passes)
    check(in->signature != NULL, "P2TR input scriptSig array present (non-NULL)");
    check(in->sigLen == 0, "P2TR input scriptSig is empty (sigLen 0)");

    // witness = single 64-byte item: stored as [0x40][64 sig bytes] = 65 bytes
    check(in->witness != NULL && in->witLen == 65,
          "P2TR witness is 65 bytes ([len-prefix 0x40][64-byte sig])");
    if (in->witness && in->witLen == 65) {
        check(in->witness[0] == 0x40, "witness item length-prefix == 0x40 (64 bytes)");

        uint8_t sig64[64];
        memcpy(sig64, &in->witness[1], 64);

        // recompute the BIP-341 key-path sighash of THIS tx/input (same helper the signer used)
        UInt256 md = UINT256_ZERO;
        size_t n = _BRTransactionTaprootSighash(tx, NULL, 0, 0, SIGHASH_DEFAULT, &md);
        check(n > 0, "recompute BIP-341 key-path sighash succeeds");

        check(secp256k1_schnorrsig_verify(ctx, sig64, md.u8, 32, &xoQ) == 1,
              "VALIDITY: witness sig ACCEPTED by schnorr verify under X(Q) for the tx sighash");

        // Negative: it must NOT verify under the UNtweaked internal key P (proves the
        // taptweak really happened — a signer that forgot it would verify under P).
        {
            uint32_t idx0 = 0;
            BRKey ik;
            BRBIP32PrivKeyListBIP86(&ik, 1, seed, sizeof(seed), SEQUENCE_EXTERNAL_CHAIN, &idx0);
            secp256k1_keypair kp;
            secp256k1_xonly_pubkey xoP;
            uint8_t p32[32];
            if (secp256k1_keypair_create(ctx, &kp, ik.secret.u8) &&
                secp256k1_keypair_xonly_pub(ctx, &xoP, NULL, &kp) &&
                secp256k1_xonly_pubkey_serialize(ctx, p32, &xoP)) {
                secp256k1_xonly_pubkey xoPk;
                secp256k1_xonly_pubkey_parse(ctx, &xoPk, p32);
                check(secp256k1_schnorrsig_verify(ctx, sig64, md.u8, 32, &xoPk) == 0,
                      "NEGATIVE: sig does NOT verify under untweaked internal key P");
            }
            memset(&kp, 0, sizeof(kp));
            BRKeyClean(&ik);
        }
    }

    // serialize + reparse: the 1-item witness must round-trip byte-correct
    {
        size_t L = BRTransactionSerialize(tx, NULL, 0);
        uint8_t *buf = malloc(L);
        check(buf != NULL, "alloc for serialize");
        if (buf) {
            size_t wrote = BRTransactionSerialize(tx, buf, L);
            check(wrote == L, "serialize wrote full length");
            BRTransaction *rt = BRTransactionParse(buf, L);
            check(rt != NULL, "reparse serialized signed tx");
            if (rt) {
                check(rt->inCount == 1 && rt->inputs[0].witLen == 65,
                      "reparsed P2TR witness round-trips (65 bytes, stack count == 1)");
                BRTransactionFree(rt);
            }
            free(buf);
        }
    }

    BRTransactionFree(tx);

    // ============================================================
    // Scenario 2 — NO REGRESSION: a BIP84/P2WPKH-only spend still signs
    // ============================================================
    {
        BRAddress wAddr = BRWalletReceiveAddress(wallet, 1); // wallet's own P2WPKH (matchable)
        uint8_t wspk[64];
        size_t wspkLen = BRAddressScriptPubKey(wspk, sizeof(wspk), wAddr.s);
        check(wspkLen == 22 && wspk[0] == OP_0 && wspk[1] == 20,
              "P2WPKH scriptPubKey is {OP_0, 0x14, <20>} (22 bytes)");

        BRTransaction *tx2 = BRTransactionNew();
        UInt256 prevHash2;
        memset(prevHash2.u8, 0x22, sizeof(prevHash2));
        BRTransactionAddInput(tx2, prevHash2, 0, inAmount, wspk, wspkLen, NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(tx2, outAmount, dspk, dspkLen);

        int r2 = BRWalletSignTransaction(wallet, tx2, 0, seed, sizeof(seed));
        check(r2 == 1, "BRWalletSignTransaction(P2WPKH spend) still returns 1");
        check(BRTransactionIsSigned(tx2) == 1, "P2WPKH spend reports fully signed");
        // P2WPKH witness = 2 items (sig||hashtype, pubkey): must NOT be the 1-item taproot shape
        check(tx2->inputs[0].witness != NULL && tx2->inputs[0].witLen > 65,
              "P2WPKH witness unchanged (2-item sig+pubkey stack, not the taproot 1-item)");
        BRTransactionFree(tx2);
    }

    BRWalletFree(wallet);
    secp256k1_context_destroy(ctx);

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
