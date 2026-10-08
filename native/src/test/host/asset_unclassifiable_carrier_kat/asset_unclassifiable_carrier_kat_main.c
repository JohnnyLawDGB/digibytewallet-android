// Host KAT: a DigiAsset carrier the native reader cannot classify holds EVERY output of its
// transaction out of the spendable DGB set.
//
// INVARIANT. An OP_RETURN whose push begins with the "DA" tag is a DigiAsset carrier. When the
// reader cannot classify it -- the payload is framed with OP_PUSHDATA2 or OP_PUSHDATA4 (whose
// lengths are little-endian, as script reads them), the push is too short for the header or runs
// past the script, the version or opcode is not one DigiAssets defines, or the payload does not
// decode -- which outputs the protocol credits cannot be known. So the reader fails closed:
// BRTxOutputIsAsset answers 1 for every output of that transaction, the wallet files every owned
// output in assetUtxos rather than utxos, it is not part of the spendable balance, and coin
// selection cannot reach it. An OP_RETURN that is not tagged "DA" is not a carrier, however it
// is framed, and leaves the outputs of its transaction as ordinary DGB.
//
// RED arm (-DASSET_CARRIER_FAILCLOSED_UNFIXED) restores the earlier answer: an unclassifiable
// carrier reads as no carrier, so its outputs are ordinary spendable DGB. It must fail every
// check marked RED-THEN-GREEN below. GREEN passes every check.
//
// MACRO CONVENTION: PRESENCE of -DASSET_CARRIER_FAILCLOSED_UNFIXED (#ifdef) selects the red arm.
//
// Exit code 0 = every check passed; nonzero = a check failed or a sanitizer report fired.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiAsset.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// BRWalletRegisterTransaction asserts BRTransactionIsSigned(), which only checks that each
// input's signature/witness pointers are non-NULL. These synthetic txs aren't really signed.
static const uint8_t kPlaceholder[1] = {0};

static uint8_t kNormalScript[25] = {
    0x76,0xa9,0x14, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x88,0xac
};

static void finalizeTxHash(BRTransaction *tx) {
    uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
    size_t len = BRTransactionSerialize(tx, data, sizeof(data));
    BRTransaction *t = BRTransactionParse(data, len);
    if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
}

// Ordinary outputs at 0 and 2, the OP_RETURN at 1; asks whether output `queryVout` is held. The
// OP_RETURN script is copied into a heap block of exactly its own length, so the sanitizer sees
// any read that leaves it.
static int classify(const uint8_t *opReturn, size_t opReturnLen, int queryVout) {
    BRTxOutput outs[3];
    BRTransaction tx;
    uint8_t *exact = malloc(opReturnLen);
    int r;

    if (!exact) return -1;
    memcpy(exact, opReturn, opReturnLen);
    memset(outs, 0, sizeof(outs));
    outs[0].script = kNormalScript; outs[0].scriptLen = sizeof(kNormalScript); outs[0].amount = 100000;
    outs[1].script = exact;         outs[1].scriptLen = opReturnLen;           outs[1].amount = 0;
    outs[2].script = kNormalScript; outs[2].scriptLen = sizeof(kNormalScript); outs[2].amount = 100000;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = 3;
    r = BRTxOutputIsAsset(&tx, &tx.outputs[queryVout]);
    free(exact);
    return r;
}

// The same 6-byte transfer payload in every framing: "DA", version 2, transfer, one instruction
// sending 10 units to output 0. Output 2 is named by nothing.
#define XFER_PAYLOAD 0x44,0x41,0x02,0x15,0x00,0x0a

static const uint8_t kDirect[8]    = { 0x6a,0x06, XFER_PAYLOAD };
static const uint8_t kPushdata1[9] = { 0x6a,0x4c,0x06, XFER_PAYLOAD };
static const uint8_t kPushdata2[10]= { 0x6a,0x4d,0x06,0x00, XFER_PAYLOAD };            /* length 6, LE */
static const uint8_t kPushdata4[12]= { 0x6a,0x4e,0x06,0x00,0x00,0x00, XFER_PAYLOAD };  /* length 6, LE */
// A plain (not "DA") OP_RETURN framed with OP_PUSHDATA2: not a carrier.
static const uint8_t kPlainPd2[10] = { 0x6a,0x4d,0x06,0x00, 'h','e','l','l','o','!' };

static void classifierChecks(void) {
    // GUARD: the framings the reader classifies still mark exactly what the instruction names.
    check(classify(kDirect, sizeof(kDirect), 0) == 1 && classify(kDirect, sizeof(kDirect), 2) == 0,
          "a direct-push transfer marks the output it names and only that one");
    check(classify(kPushdata1, sizeof(kPushdata1), 0) == 1 && classify(kPushdata1, sizeof(kPushdata1), 2) == 0,
          "a PUSHDATA1 transfer marks the output it names and only that one");

    // RED-THEN-GREEN: OP_PUSHDATA2 / OP_PUSHDATA4 carriers hold every output, named or not.
    check(classify(kPushdata2, sizeof(kPushdata2), 2) == 1,
          "a PUSHDATA2 carrier holds an output its instruction does not name");
    check(classify(kPushdata2, sizeof(kPushdata2), 0) == 1,
          "a PUSHDATA2 carrier holds the output its instruction names");
    check(classify(kPushdata4, sizeof(kPushdata4), 2) == 1,
          "a PUSHDATA4 carrier holds an output its instruction does not name");
    check(classify(kPushdata4, sizeof(kPushdata4), 0) == 1,
          "a PUSHDATA4 carrier holds the output its instruction names");

    // RED-THEN-GREEN: a PUSHDATA2 carrier whose two length bytes are equal (257 = 0x0101) -- the
    // same length read either way round -- is no more classifiable than any other.
    {
        uint8_t big[4 + 257];
        size_t k = 0;
        big[k++] = 0x6a; big[k++] = 0x4d; big[k++] = 0x01; big[k++] = 0x01;
        big[k++] = 0x44; big[k++] = 0x41; big[k++] = 0x02; big[k++] = 0x15;
        big[k++] = 0x00; big[k++] = 0x20; big[k++] = 0x01;                  /* 1 unit -> output 0, 2-byte amount */
        while (k < sizeof(big)) { big[k++] = 0x00; big[k++] = 0x01; }       /* 1 unit -> output 0 */
        check(k == sizeof(big), "built a well-formed 257-byte PUSHDATA2 payload");
        check(classify(big, sizeof(big), 2) == 1,
              "a 257-byte PUSHDATA2 carrier holds an output its instructions do not name");
    }

    // GUARD: an OP_RETURN that is not tagged "DA" is not a carrier, however it is framed.
    check(classify(kPlainPd2, sizeof(kPlainPd2), 0) == 0 && classify(kPlainPd2, sizeof(kPlainPd2), 2) == 0,
          "a plain PUSHDATA2 OP_RETURN leaves every output ordinary");
    {
        BRTxOutput o; memset(&o, 0, sizeof(o));
        o.script = (uint8_t *)kPlainPd2; o.scriptLen = sizeof(kPlainPd2);
        check(BROutpointIsAsset(&o) == 0, "a plain PUSHDATA2 OP_RETURN is not an asset outpoint");
        o.script = (uint8_t *)kPushdata2; o.scriptLen = sizeof(kPushdata2);
        // RED-THEN-GREEN: the outpoint-level test (what a foreign-seed recovery asks) agrees.
        check(BROutpointIsAsset(&o) != 0, "a PUSHDATA2 carrier is an asset outpoint");
    }

    // RED-THEN-GREEN: tagged pushes that do not decode hold every output too.
    static const uint8_t kTagOnly[4]   = { 0x6a,0x02,0x44,0x41 };                           /* no version/opcode */
    static const uint8_t kOverrun[8]   = { 0x6a,0x08, XFER_PAYLOAD };                        /* declares 8, has 6 */
    static const uint8_t kBadOpcode[8] = { 0x6a,0x06,0x44,0x41,0x02,0x16,0x00,0x0a };      /* opcode 0x16 */
    static const uint8_t kVersion0[8]  = { 0x6a,0x06,0x44,0x41,0x00,0x15,0x00,0x0a };      /* version 0 */
    static const uint8_t kCutAmount[8] = { 0x6a,0x06,0x44,0x41,0x02,0x15,0x00,0x20 };      /* 2-byte amount, 1 byte left */
    check(classify(kTagOnly, sizeof(kTagOnly), 2) == 1,   "a tagged push too short for the header holds every output");
    check(classify(kOverrun, sizeof(kOverrun), 2) == 1,   "a tagged push that runs past the script holds every output");
    check(classify(kBadOpcode, sizeof(kBadOpcode), 2) == 1, "an undefined opcode holds every output");
    check(classify(kVersion0, sizeof(kVersion0), 2) == 1, "version 0 holds every output");
    check(classify(kCutAmount, sizeof(kCutAmount), 2) == 1, "a transfer amount the payload ends inside holds every output");

    // GUARD: a version-3 issuance with its 32-byte metadata hash (and no 20-byte SHA1, which only
    // versions 1 and 2 carry) is read, not failed closed: the output its instruction names is
    // marked, and an output nothing names is not. (Both arms pass; the first check was red on the
    // earlier reader, which skipped 52 metadata bytes for every issuance version.)
    {
        uint8_t iss[2 + 4 + 32 + 1 + 2 + 1];
        size_t k = 0;
        iss[k++] = 0x6a; iss[k++] = (uint8_t)(sizeof(iss) - 2);
        iss[k++] = 0x44; iss[k++] = 0x41; iss[k++] = 0x03; iss[k++] = 0x01;
        memset(iss + k, 0x11, 32); k += 32;
        iss[k++] = 0x01;                  /* amount 1 */
        iss[k++] = 0x00; iss[k++] = 0x01; /* 1 unit -> output 0 */
        iss[k++] = 0x10;                  /* issuance flags: locked, aggregable */
        check(k == sizeof(iss), "built a version-3 issuance");
        check(classify(iss, sizeof(iss), 0) == 1, "a version-3 issuance marks the output its instruction names");
        check(classify(iss, sizeof(iss), 2) == 0, "a version-3 issuance does not hold an output nothing names");
    }
}

// The wallet: every owned output of a transaction with an unclassifiable carrier is held.
static void walletChecks(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
    check(w != NULL, "wallet created"); if (!w) return;

    BRAddress a0 = BRWalletReceiveAddress(w, 0);
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    check(spkLen > 0, "our scriptPubKey resolves");

    // 1. a plain DGB receive: 0.2 DGB.
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *plain = BRTransactionNew();
    BRTransactionAddInput(plain, h1, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(plain, 20000000, spk, spkLen);
    finalizeTxHash(plain);
    check(BRWalletRegisterTransaction(w, plain) != 0, "plain DGB receive registered");
    BRWalletUpdateTransactions(w, &plain->txHash, 1, 700000, 1784980000);

    // 2. a PUSHDATA2 carrier paying us twice: the output its instruction names (0) and one it
    //    does not (2). Both are ordinary-sized, not dust.
    UInt256 h2; memset(h2.u8, 0x22, 32);
    BRTransaction *pd2 = BRTransactionNew();
    BRTransactionAddInput(pd2, h2, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(pd2, 5000000, spk, spkLen);
    BRTransactionAddOutput(pd2, 0, kPushdata2, sizeof(kPushdata2));
    BRTransactionAddOutput(pd2, 1000000, spk, spkLen);
    finalizeTxHash(pd2);
    check(BRWalletRegisterTransaction(w, pd2) != 0, "PUSHDATA2 carrier tx registered");
    BRWalletUpdateTransactions(w, &pd2->txHash, 1, 700001, 1784980100);

    // 3. a PUSHDATA4 carrier paying us once.
    UInt256 h3; memset(h3.u8, 0x33, 32);
    BRTransaction *pd4 = BRTransactionNew();
    BRTransactionAddInput(pd4, h3, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(pd4, 4000000, spk, spkLen);
    BRTransactionAddOutput(pd4, 0, kPushdata4, sizeof(kPushdata4));
    finalizeTxHash(pd4);
    check(BRWalletRegisterTransaction(w, pd4) != 0, "PUSHDATA4 carrier tx registered");
    BRWalletUpdateTransactions(w, &pd4->txHash, 1, 700002, 1784980200);

    // 4. a plain PUSHDATA2 OP_RETURN (not "DA") paying us once: ordinary DGB.
    UInt256 h4; memset(h4.u8, 0x44, 32);
    BRTransaction *note = BRTransactionNew();
    BRTransactionAddInput(note, h4, 0, 0, spk, spkLen, kPlaceholder, 0, kPlaceholder, 0, 0xffffffff);
    BRTransactionAddOutput(note, 3000000, spk, spkLen);
    BRTransactionAddOutput(note, 0, kPlainPd2, sizeof(kPlainPd2));
    finalizeTxHash(note);
    check(BRWalletRegisterTransaction(w, note) != 0, "plain PUSHDATA2 OP_RETURN tx registered");
    BRWalletUpdateTransactions(w, &note->txHash, 1, 700003, 1784980300);

    BRUTXO pd2Named = { pd2->txHash, 0 }, pd2Unnamed = { pd2->txHash, 2 };
    BRUTXO pd4Out = { pd4->txHash, 0 }, noteOut = { note->txHash, 0 };
    // RED-THEN-GREEN
    check(BRWalletUtxoIsAsset(w, &pd2Named) == 1,   "the PUSHDATA2 carrier's named output is held as an asset output");
    check(BRWalletUtxoIsAsset(w, &pd2Unnamed) == 1, "the PUSHDATA2 carrier's unnamed output is held as an asset output");
    check(BRWalletUtxoIsAsset(w, &pd4Out) == 1,     "the PUSHDATA4 carrier's output is held as an asset output");
    check(BRWalletBalance(w) == 23000000,
          "only the plain receive and the plain OP_RETURN's output count toward the spendable balance");
    // GUARD
    check(BRWalletUtxoIsAsset(w, &noteOut) == 0, "the plain PUSHDATA2 OP_RETURN's output stays ordinary DGB");

    // RED-THEN-GREEN: 23,000,000 spendable plus 10,000,000 held. A 25,000,000 send is satisfiable
    // only by spending a held output, so it must fail. (Both amounts clear the fee by ~1,000,000,
    // so no fee edge decides this.)
    BRTransaction *tooBig = BRWalletCreateTransaction(w, 25000000, a0.s);
    check(tooBig == NULL, "a DGB send that would need a held output fails instead of spending it");
    if (tooBig) BRTransactionFree(tooBig);
    // GUARD
    BRTransaction *ok = BRWalletCreateTransaction(w, 22000000, a0.s);
    check(ok != NULL, "a DGB send within the spendable balance still succeeds");
    if (ok) BRTransactionFree(ok);

    BRWalletFree(w);
}

int main(void) {
    classifierChecks();
    walletChecks();
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
