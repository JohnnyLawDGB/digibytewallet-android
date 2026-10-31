// Host KAT: no zero-length VLA (4.1.2 row 12, N9). Built under UndefinedBehaviorSanitizer (vla-bound).
//   arm pkh      BRScriptPKH on {0x02}               -> NULL
//   arm spk      BRAddressFromScriptPubKey on {0x02} -> 0
//   arm sig      BRAddressFromScriptSig on {0x02}    -> 0
//   arm phrase   BRBIP39PhraseIsValid on ""          -> 0
//   arm verify   BRTransactionVerifyInput, a P2PKH input whose scriptSig is {0x02} (a peer's tx) -> 0   (review R9)
//   arm wallet   BRWalletUpdateTransactions with no hashes, on an empty wallet                  -> 0   (review R9)
//   arm unconfirmed BRWalletSetTxUnconfirmedAfter with nothing above the height                 -> 0   (review R9)
//   arm sign     BRTransactionSign with no keys                                                 -> 0   (repair review #6)
//   arm signinput BRTransactionSign with a key, on an input whose script parses to no elements    -> 0   (4.1.3 row 4)
//   arm walletsign BRWalletSignTransaction on a transaction with no inputs                       -> 0   (4.1.3 row 4)
// Every other VLA in the core is held to BR_VLA_LEN or a reasoned allowlist by run.sh's census (4.1.3 row 4).
// RED  -DZERO_VLA_UNFIXED: each arm declares a zero-length array and UBSan reports it.
// GREEN each arm answers as above with no report.
#include <stdio.h>
#include <string.h>
#include "BRKey.h"
#include "BRAddress.h"
#include "BRBIP39Mnemonic.h"
#include "BRBIP39WordsEn.h"
#include "BRTransaction.h"
#include "BRWallet.h"
#include "BRBIP32Sequence.h"

static BRWallet *emptyWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    return BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
}

int main(int argc, char **argv)
{
    const char *arm = argc > 1 ? argv[1] : "";
    const uint8_t s[1] = { 0x02 };   // a push of two bytes with none following: no elements
    char a[91];
    long got, want;
    if (! strcmp(arm, "pkh")) { got = BRScriptPKH(s, sizeof(s)) != NULL; want = 0; }
    else if (! strcmp(arm, "spk")) { got = (long)BRAddressFromScriptPubKey(a, sizeof(a), s, sizeof(s)); want = 0; }
    else if (! strcmp(arm, "sig")) { got = (long)BRAddressFromScriptSig(a, sizeof(a), s, sizeof(s)); want = 0; }
    else if (! strcmp(arm, "phrase")) { got = BRBIP39PhraseIsValid(BRBIP39WordsEn, ""); want = 0; }
    else if (! strcmp(arm, "verify")) {
        uint8_t pkh[25] = { 0x76, 0xa9, 20 };
        pkh[23] = 0x88; pkh[24] = 0xac;
        UInt256 prev; memset(prev.u8, 0x31, 32);
        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prev, 0, 1000, pkh, sizeof(pkh), s, sizeof(s), NULL, 0, 0xffffffff);
        BRTransactionAddOutput(tx, 500, pkh, sizeof(pkh));
        got = BRTransactionVerifyInput(tx, 0); want = 0;
        BRTransactionFree(tx);
    }
    else if (! strcmp(arm, "wallet")) {
        BRWallet *w = emptyWallet();
        BRWalletUpdateTransactions(w, NULL, 0, 100, 1700000000);
        got = 0; want = 0;
        BRWalletFree(w);
    }
    else if (! strcmp(arm, "unconfirmed")) {
        BRWallet *w = emptyWallet();
        BRWalletSetTxUnconfirmedAfter(w, 100);
        got = 0; want = 0;
        BRWalletFree(w);
    }
    else if (! strcmp(arm, "sign")) {
        BRTransaction *tx = BRTransactionNew();
        got = BRTransactionSign(tx, 0, NULL, 0); want = 0;
        BRTransactionFree(tx);
    }
    else if (! strcmp(arm, "signinput")) {
        BRKey key;
        UInt256 secret; memset(secret.u8, 0x42, 32);
        BRKeySetSecret(&key, &secret, 1);
        UInt256 prev; memset(prev.u8, 0x33, 32);
        uint8_t pkh[25] = { 0x76, 0xa9, 20 };
        pkh[23] = 0x88; pkh[24] = 0xac;
        BRTransaction *tx = BRTransactionNew();
        BRTransactionAddInput(tx, prev, 0, 1000, s, sizeof(s), NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(tx, 500, pkh, sizeof(pkh));
        got = BRTransactionSign(tx, 0, &key, 1); want = 0;
        BRTransactionFree(tx);
    }
    else if (! strcmp(arm, "walletsign")) {
        BRWallet *w = emptyWallet();
        uint8_t seed[64];
        BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
        BRTransaction *tx = BRTransactionNew();
        got = BRWalletSignTransaction(w, tx, 0, seed, sizeof(seed)); want = 0;
        BRTransactionFree(tx);
        BRWalletFree(w);
    }
    else { printf("unknown arm %s\n", arm); return 2; }
    printf("zero_vla_kat %s: answered %ld\n", arm, got);
    return got == want ? 0 : 1;
}
