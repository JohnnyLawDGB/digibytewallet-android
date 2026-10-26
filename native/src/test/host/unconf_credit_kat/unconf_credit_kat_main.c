// Host KAT: an unconfirmed receive no peer relays does not stay credited forever (4.1.2 row 32).
//   [A] a plain foreign DGB receive saved while pending: at the tip, every peer's mempool heard and none
//       holding it, the cleanup removes it and its credit -- RED under UNCONF_CREDIT_UNFIXED, where any
//       receive counted as the wallet's stake and was kept (a payment that never happened, shown for good);
//   [B] that receive while the wallet holds its own send spending it: both are kept -- removing the receive
//       released the send as its dependant, and the send's other coins became selectable while it may still
//       sit in the network's mempools (review R5); RED under UNCONF_DEPENDANT_UNFIXED.
// The token stake (an owned DigiDollar or asset output) is unchanged and stays covered by
// tipsweep_token_stake_kat.
// Harness: tipsweep_token_stake_kat's, #included with its own main renamed.
#define main tipsweep_token_stake_main
#include "../tipsweep_token_stake_kat/tipsweep_token_stake_kat_main.c"
#undef main

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("-- dgb_receive: a pending foreign DGB receive nobody relays --\n");
    OwnScripts own;
    ownScripts(&own);
    BRTransaction *P = makeRecord(REC_DGB, &own, NULL, NULL);
    BRTransaction *S = makeRecord(REC_PLAIN, &own, NULL, NULL);
    UInt256 pH = P->txHash, sH = S->txHash;
    BRTransaction *recs[] = { P, S };
    size_t blobLen = 0;
    uint8_t *blob = saveSession(recs, 2, &blobLen);
    Fx f;
    memset(&f, 0, sizeof(f));
    relaunch(&f, blob, blobLen, 2);
    free(blob);
    check(BRWalletBalance(f.w) == DGB_TO_US, "setup: the receive is credited at 0-conf");
    startAtTip(&f);
    reachCleanupRound(&f, pH);
    UInt256 asked[2] = { pH, sH };
    for (size_t k = 0; k < f.peerCount; k++) { answerNotfound(f.peers[k], asked, 2); answerPong(f.peers[k]); }
    check(! held(&f, sH), "control: the cleanup ran");
    if (held(&f, pH) || BRWalletBalance(f.w) != 0) {
        printf("FAIL: A: the unrelayed receive is still credited (%llu)\n", (unsigned long long)BRWalletBalance(f.w));
        g_fail++;
    } else check(1, "the unrelayed receive and its credit are gone");
    teardown(&f);

    printf("-- dgb_receive_spent: the same receive, and the wallet's own send that spends it --\n");
    {
        BRTransaction *P2 = makeRecord(REC_DGB, &own, NULL, NULL);
        BRTransaction *C2 = makeRecord(REC_PLAIN, &own, NULL, NULL);
        /* session 1 by hand: the receive and the control as saveSession registers them, then the wallet's own
         * send of the receive, signed with the wallet's key and registered through the checked path */
        BRWallet *w1 = openWallet(NULL, 0);
        UInt256 p2H = P2->txHash, c2H = C2->txHash;
        int ok = BRWalletRegisterTransaction(w1, P2) && BRWalletRegisterTransaction(w1, C2);
        BRTransaction *S2 = BRTransactionNew();
        uint8_t dest[22] = { 0x00, 0x14 };
        memset(dest + 2, 0x44, 20);
        S2->version = 2;
        BRTransactionAddInput(S2, p2H, 0, DGB_TO_US, own.wpkh, own.wpkhLen, NULL, 0, NULL, 0, 0xffffffff);
        BRTransactionAddOutput(S2, DGB_TO_US - 100000, dest, sizeof(dest));
        uint8_t seed[64];
        BRBIP39DeriveKey(seed, kMnemonic, NULL);
        ok = ok && BRWalletSignTransaction(w1, S2, 0, seed, sizeof(seed)) && BRTransactionIsSigned(S2);
        memset(seed, 0, sizeof(seed));
        S2->timestamp = P2->timestamp + 60;
        UInt256 s2H = S2->txHash;
        ok = ok && BRWalletRegisterTransaction(w1, S2) && BRWalletTransactionForHash(w1, s2H) != NULL;
        check(ok, "session 1: the receive, the control and the wallet's signed send of the receive are held");
        size_t len2 = BRWalletSerializeTransactions(w1, NULL, 0);
        uint8_t *blob2 = malloc(len2);
        len2 = BRWalletSerializeTransactions(w1, blob2, len2);
        BRWalletFree(w1);
        Fx g;
        memset(&g, 0, sizeof(g));
        relaunch(&g, blob2, len2, 3);
        free(blob2);
        check(held(&g, s2H), "setup: the wallet holds its send of the receive");
        startAtTip(&g);
        reachCleanupRound(&g, p2H);
        UInt256 asked2[3] = { p2H, s2H, c2H };
        for (size_t k = 0; k < g.peerCount; k++) { answerNotfound(g.peers[k], asked2, 3); answerPong(g.peers[k]); }
        check(! held(&g, c2H), "control: the cleanup ran");
        if (! held(&g, s2H) || ! held(&g, p2H)) {
            printf("FAIL: B: the wallet's own send was released with the receive it spends (send %s, receive %s)\n",
                   held(&g, s2H) ? "kept" : "gone", held(&g, p2H) ? "kept" : "gone");
            g_fail++;
        } else check(1, "the receive and the wallet's send of it are both kept");
        teardown(&g);
    }

    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: unconf_credit_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
