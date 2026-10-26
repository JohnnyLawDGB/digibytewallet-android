// Host KAT: a coinbase is never valued as DigiDollar (4.1.2 row 18, dhikariski11 1a14c645).
//   [A] a mature coinbase carrying the DigiDollar version marker, a token output to this wallet and the
//       "DD" OP_RETURN: the DigiDollar balance stays 0 and no plain coin is made of the token output --
//       RED under DD_COINBASE_UNFIXED, where its dollars were credited (consensus T5-02 excludes them);
//   [B] guard: the coinbase's ordinary DGB payout is still credited once mature.
// Harness: tipsweep_token_stake_kat's (openWallet, ownScripts, ddOpReturn), #included with main renamed.
#define main tipsweep_token_stake_main
#include "../tipsweep_token_stake_kat/tipsweep_token_stake_kat_main.c"
#undef main

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    OwnScripts own;
    ownScripts(&own);
    BRWallet *w = openWallet(NULL, 0);
    const uint32_t H = BRNetworkDigiDollarActivationHeight() + 1000;

    uint8_t ftr[34] = { 0x51, 0x20 }; memset(ftr + 2, 0x7e, 32);
    uint8_t opr[80];
    UInt256 placeholder; memset(placeholder.u8, 0xcb, 32);   /* AddInput refuses the null prevout; written below */
    uint8_t sig[6] = { 0x03, 1, 2, 3, 0x42, 0 };
    BRTransaction *cb = BRTransactionNew();
    cb->version = ((uint32_t)DD_TYPE_TRANSFER << 24) | DD_VERSION_MARKER;
    BRTransactionAddInput(cb, placeholder, 0xffffffff, 0, NULL, 0, sig, 5, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(cb, 0, own.tr, own.trLen);                                  // DD to us
    BRTransactionAddOutput(cb, 0, ftr, sizeof(ftr));                                   // DD elsewhere
    BRTransactionAddOutput(cb, 0, opr, ddOpReturn(opr, DD_TO_US_CENTS, DD_CHANGE_CENTS));
    BRTransactionAddOutput(cb, 1000000000ULL, own.wpkh, own.wpkhLen);                  // the DGB payout
    {   /* the hash, as the harness makes it: serialize and parse back */
        size_t len = BRTransactionSerialize(cb, NULL, 0);
        uint8_t *buf = malloc(len);
        BRTransactionSerialize(cb, buf, len);
        size_t at = (buf[4] == 0x00) ? 7 : 5;          /* past version (+ segwit marker/flag) and input count */
        memset(&buf[at], 0, 32);                        /* the null prevout: this is a coinbase */
        BRTransaction *parsed = BRTransactionParseSigned(buf, len);
        free(buf);
        BRTransactionFree(cb);
        cb = parsed;
        if (! cb) { printf("FAIL: setup: the coinbase did not parse\n"); return 2; }
    }
    cb->blockHeight = H; cb->timestamp = 1790000000u;
    check(BRDigiDollarTxType(cb) != 0 && BRDigiDollarOutputAmount(cb, 0) == DD_TO_US_CENTS,
          "setup: a coinbase-shaped DigiDollar transfer binding $50.00 to an output that pays this wallet");
    BRWalletRegisterTransactionTrusted(w, cb);
    BRWalletSetBlockHeight(w, H + 100);   // mature

    printf("[A] the dollars\n");
    if (BRWalletDigiDollarBalance(w) != 0) {
        printf("FAIL: A: a coinbase's DigiDollar was credited (%llu cents)\n", (unsigned long long)BRWalletDigiDollarBalance(w));
        g_fail++;
    } else check(1, "no DigiDollar credit from a coinbase");
    printf("[B] the DGB payout\n");
    check(BRWalletBalance(w) == 1000000000ULL && BRWalletUTXOs(w, NULL, 0) == 1,
          "the mature DGB payout is the one coin (the token output is no coin)");

    BRWalletFree(w);
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: dd_coinbase_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
