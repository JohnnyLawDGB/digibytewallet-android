// Host KAT: explicitly-watched address set (Receive-screen pins).
//
// Verifies BRWalletAddWatchedAddress: an address OUTSIDE the derived gap window is
// (a) not contained before watching, (b) contained after, (c) present in
// BRWalletAllAddrs (so it flows into the BIP158 filter match set), and (d) adding
// many (past the 16-slot initial array, forcing realloc) never dangles — run under
// -fsanitize=address. This is the fix for the compact-filter address-set gap:
// a receive to a pinned address is scanned in every block even if derivation never
// reaches it after a restart.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRKey.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// derive the segwit (P2WPKH) external address at `idx` from mpk84
static BRAddress segwitAddrAt(BRMasterPubKey mpk, uint32_t idx) {
    uint8_t pub[BRBIP32PubKey(NULL, 0, mpk, SEQUENCE_EXTERNAL_CHAIN, idx)];
    size_t len = BRBIP32PubKey(pub, sizeof(pub), mpk, SEQUENCE_EXTERNAL_CHAIN, idx);
    BRKey key; BRAddress a = BR_ADDRESS_NONE;
    if (BRKeySetPubKey(&key, pub, len)) BRKeySegwitAddress(&key, a.s, sizeof(a), OP_0);
    return a;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk84);
    check(w != NULL, "wallet created"); if (!w){printf("\nFATAL\n");return 1;}

    // index 300 is far beyond the external gap limit (10) — not in the derived set.
    BRAddress far = segwitAddrAt(mpk84, 300);
    check(!BRAddressEq(&far, &BR_ADDRESS_NONE), "derived a far (idx 300) address");
    check(BRWalletContainsAddress(w, far.s) == 0, "far address NOT contained before watching");

    BRWalletAddWatchedAddress(w, far.s);
    check(BRWalletContainsAddress(w, far.s) == 1, "far address contained AFTER watching");
    BRWalletAddWatchedAddress(w, far.s); // idempotent

    // it must also appear in BRWalletAllAddrs (which feeds BRWalletGetFilterElements)
    size_t n = BRWalletAllAddrs(w, NULL, 0);
    BRAddress *all = malloc(n * sizeof(*all));
    n = BRWalletAllAddrs(w, all, n);
    int inAll = 0;
    for (size_t i = 0; i < n; i++) if (strcmp(all[i].s, far.s) == 0) { inAll = 1; break; }
    free(all);
    check(inAll, "far address present in BRWalletAllAddrs (in CF match set)");

    // Add 60 more watched addresses (past the 16-slot initial array -> realloc) and
    // confirm every one is contained afterward. Under ASan, a dangling &array[i]
    // would abort here; the plain-array design must stay clean.
    int allContained = 1;
    for (uint32_t idx = 400; idx < 460; idx++) {
        BRAddress a = segwitAddrAt(mpk84, idx);
        if (BRAddressEq(&a, &BR_ADDRESS_NONE)) continue;
        BRWalletAddWatchedAddress(w, a.s);
    }
    for (uint32_t idx = 400; idx < 460; idx++) {
        BRAddress a = segwitAddrAt(mpk84, idx);
        if (BRAddressEq(&a, &BR_ADDRESS_NONE)) continue;
        if (!BRWalletContainsAddress(w, a.s)) allContained = 0;
    }
    check(allContained, "all 60 watched addresses contained after array growth (no UAF)");

    BRWalletFree(w);
    printf(g_fail==0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
