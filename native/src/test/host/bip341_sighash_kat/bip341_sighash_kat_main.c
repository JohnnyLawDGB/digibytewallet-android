// Host KAT for the BIP-341 key-path sighash (_BRTransactionTaprootSighash),
// added to BRTransaction.c in Taproot Sign-Task 3.
//
// _BRTransactionTaprootSighash is a FILE-STATIC helper (internal linkage), so
// this KAT reaches it by #include-ing the real BRTransaction.c translation unit
// directly (see run.sh: BRTransaction.c is therefore NOT also passed on the
// clang command line, which would duplicate every symbol). Everything else it
// depends on (BRKey.c/BRAddress.c/BRCrypto.c/...) is linked normally.
//
// VECTOR PROVENANCE -- transcribed VERBATIM from the authoritative source:
// github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json,
// object keyPathSpending[0]. `given.rawUnsignedTx` is the full unsigned tx
// (version, 9 inputs' outpoints+sequences, 2 outputs, locktime);
// `given.utxosSpent[i]` supplies each input's spent-amount + scriptPubKey.
//
// The sighash MESSAGE depends only on the transaction + spent-utxo data and the
// hash_type/spend_type/input_index -- NOT on the internal key or merkleRoot.
// So although keyPathSpending inputs 2 and 3 carry non-null merkleRoots, their
// intermediary.sigHash is a pure function of this tx, and both are valid KATs
// for the message this helper builds. This helper implements ONLY the
// non-ANYONECANPAY DEFAULT(0x00)/ALL(0x01) key-path case, so of the 7
// inputSpending entries exactly these two are in-scope:
//
//   inputSpending[3]: txinIndex=4, hashType=0x00 (SIGHASH_DEFAULT)
//       intermediary.sigHash = 4f900a0bae3f1446fd48490c2958b5a023228f01661cda3496a11da502a7f7ef
//   inputSpending[2]: txinIndex=3, hashType=0x01 (SIGHASH_ALL)
//       intermediary.sigHash = bf013ea93474aa67815b1b6cc441d23b64fa310911d991e713cd34c7f5d46669
//
// (The other five inputs use SINGLE / NONE / ANYONECANPAY variants, which change
// the message layout and are deliberately unsupported -- asserted to return 0.)
//
// Exit code 0 = all checks passed, 1 = at least one failed (or build error).

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

// Pull in the real source so the file-static _BRTransactionTaprootSighash is
// visible. Do NOT also compile BRTransaction.c on the clang line.
#include "BRTransaction.c"

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

// decodes strlen(hex)/2 bytes into out, returns byte count
static size_t hex2bin(const char *hex, uint8_t *out)
{
    size_t n = strlen(hex) / 2;
    for (size_t i = 0; i < n; i++) {
        out[i] = (uint8_t)((hexval(hex[2*i]) << 4) | hexval(hex[2*i + 1]));
    }
    return n;
}

// --- BIP-341 wallet-test-vectors.json keyPathSpending[0].given ---
static const char *kRawUnsignedTx =
    "02000000097de20cbff686da83a54981d2b9bab3586f4ca7e48f57f5b55963115f3b334e9c010000000000000000"
    "d7b7cab57b1393ace2d064f4d4a2cb8af6def61273e127517d44759b6dafdd990000000000ffffffff"
    "f8e1f583384333689228c5d28eac13366be082dc57441760d957275419a418420000000000ffffffff"
    "f0689180aa63b30cb162a73c6d2a38b7eeda2a83ece74310fda0843ad604853b0100000000feffffff"
    "aa5202bdf6d8ccd2ee0f0202afbbb7461d9264a25e5bfd3c5a52ee1239e0ba6c0000000000feffffff"
    "956149bdc66faa968eb2be2d2faa29718acbfe3941215893a2a3446d32acd050000000000000000000"
    "e664b9773b88c09c32cb70a2a3e4da0ced63b7ba3b22f848531bbb1d5d5f4c94010000000000000000"
    "e9aa6b8e6c9de67619e6a3924ae25696bb7b694bb677a632a74ef7eadfd4eabf0000000000ffffffff"
    "a778eb6a263dc090464cd125c466b5a99667720b1c110468831d058aa1b82af10100000000ffffffff"
    "0200ca9a3b000000001976a91406afd46bcdfd22ef94ac122aa11f241244a37ecc88ac"
    "807840cb0000000020ac9a87f5594be208f8532db38cff670c450ed2fea8fcdefcc9a663f78bab962b"
    "0065cd1d";

// given.utxosSpent[i] (one per input; ALL of these are hashed for a
// non-ANYONECANPAY sighash, hence every input must carry them)
static const char *kSpk[9] = {
    "512053a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343",
    "5120147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3",
    "76a914751e76e8199196d454941c45d1b3a323f1433bd688ac",
    "5120e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e",
    "512091b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605",
    "00147dd65592d0ab2fe0d0257d571abf032cd9db93dc",
    "512075169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831",
    "5120712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5",
    "512077e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220",
};
static const uint64_t kAmt[9] = {
    420000000, 462000000, 294000000, 504000000, 630000000,
    378000000, 672000000, 546000000, 588000000,
};

// intermediary.sigHash targets for the two in-scope inputs
static const char *kSigHashDefaultIdx4 =
    "4f900a0bae3f1446fd48490c2958b5a023228f01661cda3496a11da502a7f7ef"; // txinIndex 4, hashType 0x00
static const char *kSigHashAllIdx3 =
    "bf013ea93474aa67815b1b6cc441d23b64fa310911d991e713cd34c7f5d46669"; // txinIndex 3, hashType 0x01

int main(void)
{
    uint8_t raw[1024];
    size_t rawLen = hex2bin(kRawUnsignedTx, raw);

    BRTransaction *tx = BRTransactionParse(raw, rawLen);
    check(tx != NULL, "BRTransactionParse(rawUnsignedTx) succeeds");
    if (! tx) { printf("\nSOME FAILED (fatal parse)\n"); return 1; }

    check(tx->version == 2, "parsed version == 2");
    check(tx->inCount == 9, "parsed inCount == 9");
    check(tx->outCount == 2, "parsed outCount == 2");
    check(tx->lockTime == 0x1dcd6500u, "parsed lockTime == 0x1dcd6500");

    // set each input's spent amount + prevout scriptPubKey from utxosSpent
    for (size_t i = 0; i < tx->inCount; i++) {
        uint8_t spk[128];
        size_t spkLen = hex2bin(kSpk[i], spk);
        BRTxInputSetScript(&tx->inputs[i], spk, spkLen);
        tx->inputs[i].amount = kAmt[i];
    }

    UInt256 out, exp;

    // ---- Vector A: SIGHASH_DEFAULT (0x00), input index 4
    {
        size_t n = _BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x00, &out);
        check(n == 175, "DEFAULT: helper returns message length 175");
        hex2bin(kSigHashDefaultIdx4, exp.u8);
        check(memcmp(out.u8, exp.u8, 32) == 0,
              "DEFAULT(0x00) idx4 sigHash == BIP-341 intermediary.sigHash byte-for-byte");
    }

    // ---- Vector B: SIGHASH_ALL (0x01), input index 3
    {
        size_t n = _BRTransactionTaprootSighash(tx, NULL, 0, 3, 0x01, &out);
        check(n == 175, "ALL: helper returns message length 175");
        hex2bin(kSigHashAllIdx3, exp.u8);
        check(memcmp(out.u8, exp.u8, 32) == 0,
              "ALL(0x01) idx3 sigHash == BIP-341 intermediary.sigHash byte-for-byte");
    }

    // ---- data-buffer form: message is written to caller buffer; hash still computed
    {
        uint8_t buf[175];
        UInt256 out2;
        size_t n = _BRTransactionTaprootSighash(tx, buf, sizeof(buf), 4, 0x00, &out2);
        check(n == 175, "data-buffer form returns 175");
        // layout: epoch[0] hashType[1] version[2..5] lockTime[6..9]
        //         sha_prevouts[10..41] sha_amounts[42..73] sha_scriptpubkeys[74..105]
        //         sha_sequences[106..137] sha_outputs[138..169] spend_type[170] input_index[171..174]
        check(buf[0] == 0x00, "message[0] == epoch 0x00");
        check(buf[1] == 0x00, "message[1] == hash_type 0x00 (DEFAULT)");
        check(buf[170] == 0x00, "message[170] == spend_type 0x00 (key-path, no annex)");
        // input_index (4 LE) == 4
        check(buf[171] == 0x04 && buf[172] == 0x00 && buf[173] == 0x00 && buf[174] == 0x00,
              "message[171..174] == input_index 4 (LE)");
        hex2bin(kSigHashDefaultIdx4, exp.u8);
        check(memcmp(out2.u8, exp.u8, 32) == 0, "data-buffer form hash matches vector too");
    }

    // ---- Negative: unsupported hash types must return 0 (no bogus digest)
    check(_BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x03, &out) == 0,
          "SIGHASH_SINGLE (0x03) unsupported -> returns 0");
    check(_BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x02, &out) == 0,
          "SIGHASH_NONE (0x02) unsupported -> returns 0");
    check(_BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x81, &out) == 0,
          "SIGHASH_ALL|ANYONECANPAY (0x81) unsupported -> returns 0");

    // ---- Negative: index out of range
    check(_BRTransactionTaprootSighash(tx, NULL, 0, 9, 0x00, &out) == 0,
          "input index >= inCount -> returns 0");

    // ---- Guard: a missing (zero) spent amount must be surfaced, not silently
    // hashed into a wrong digest.
    {
        // A zero spent-amount is LEGITIMATE: a DigiDollar token input is a genuine zero-satoshi P2TR,
        // and the BIP341 sighash correctly commits amount 0. It must NOT be rejected — only a missing
        // prevout script (checked below) signals the caller forgot to attach data.
        uint64_t saved = tx->inputs[0].amount;
        tx->inputs[0].amount = 0;
        check(_BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x00, &out) > 0,
              "zero spent-amount (DigiDollar token input) is accepted, not rejected");
        tx->inputs[0].amount = saved;
    }

    // ---- Guard: a missing (NULL) prevout scriptPubKey must be surfaced too.
    {
        BRTxInputSetScript(&tx->inputs[1], NULL, 0); // clears script -> scriptLen 0
        check(_BRTransactionTaprootSighash(tx, NULL, 0, 4, 0x00, &out) == 0,
              "GUARD: NULL/empty prevout scriptPubKey on any input -> returns 0");
        // restore so free path is clean
        uint8_t spk[128];
        size_t spkLen = hex2bin(kSpk[1], spk);
        BRTxInputSetScript(&tx->inputs[1], spk, spkLen);
    }

    BRTransactionFree(tx);

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
