// Host KAT: BRWalletAllAddrs / BRWalletCopyAllAddrs bounds contract.
//
// Guards the heap-buffer-overflow class in the wallet's address enumeration —
// the sole feed for the BIP158 compact-filter element set.
//
// The pre-fix defect: BRWalletAllAddrs is a size-then-fill API that takes and
// RELEASES wallet->lock on each call (BRWallet.c:930/1022). BRWalletGetFilterElements
// sizes a malloc from the count call and fills with a second call, so any chain that
// grows in between makes the fill compute write offsets from the NEW counts into a
// buffer sized from the OLD total. The `rest` budget that is supposed to clamp this
// is broken two ways: `rest -= legExtCount` is missing entirely, and three chains
// clamp to `addrsCount/4` which can exceed the true remaining space and underflow
// `rest` (size_t) to ~2^64, after which nothing is clamped at all.
//
// Under ASan the pre-fix code aborts with heap-buffer-overflow (WRITE of size 76)
// in check 4 below. Post-fix: clean, ALL PASS.
//
// Exit code 0 = all checks passed, 1 = check failed / ASan fault.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
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

// Grow every derived chain to `gapLimit`, simulating the mid-session chain growth
// that _BRPeerManagerPregenAddrWindow / BRWalletRegisterTransaction / getReceiveAddress
// perform on other threads while a cfilter is being evaluated.
static void growChains(BRWallet *w, uint32_t gapLimit) {
    for (int scriptType = 0; scriptType <= 2; scriptType++)
        for (int internal = 0; internal <= 1; internal++)
            BRWalletUnusedAddrs(w, NULL, gapLimit, internal, scriptType);
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84  = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86  = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRMasterPubKey mpkLeg = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));

    // Legacy + taproot + watched chains ALL populated — the shape that makes the
    // missing `rest -= legExtCount` load-bearing (legacyExternalChain pregens to 150).
    BRWallet *w = BRWalletNewDual(NULL, 0, mpk84, mpkLeg);
    check(w != NULL, "wallet created (dual key -> legacy chains populated)");
    if (!w) { printf("\nFATAL\n"); return 1; }
    BRWalletSetTaprootKey(w, mpk86);

    // a few explicitly-watched addresses so the watched tail is non-empty
    for (uint32_t i = 0; i < 5; i++) {
        uint8_t pub[BRBIP32PubKey(NULL, 0, mpk84, 0, 500 + i)];
        size_t plen = BRBIP32PubKey(pub, sizeof(pub), mpk84, 0, 500 + i);
        BRKey k; BRAddress a = BR_ADDRESS_NONE;
        if (BRKeySetPubKey(&k, pub, plen)) BRKeySegwitAddress(&k, a.s, sizeof(a), OP_0);
        BRWalletAddWatchedAddress(w, a.s);
    }

    // ---- 1. count mode is honest and unclamped -----------------------------------
    size_t total = BRWalletAllAddrs(w, NULL, 0);
    check(total > 0, "count mode returns a non-zero total");

    // ---- 2. same-state fill writes exactly what the count promised ---------------
    {
        BRAddress *a = (BRAddress *)malloc(total * sizeof(*a));
        size_t got = BRWalletAllAddrs(w, a, total);
        check(got == total, "same-state fill returns exactly the counted total");
        int allSet = 1;
        for (size_t i = 0; i < got; i++) if (a[i].s[0] == '\0') allSet = 0;
        check(allSet, "same-state fill populated every returned slot");
        free(a);
    }

    // ---- 3. an undersized buffer must be respected, not overrun -------------------
    {
        size_t small = total / 3;
        BRAddress *a = (BRAddress *)malloc(small * sizeof(*a)); // ASan-guarded, exact
        size_t got = BRWalletAllAddrs(w, a, small);
        check(got <= small, "undersized fill never claims more than addrsCount");
        free(a);
    }

    // ---- 4. THE REGRESSION: chains grow between the count and the fill ------------
    // This is exactly BRWalletGetFilterElements.c:18 -> malloc -> :23 with the lock
    // released in between. Pre-fix this is a heap-buffer-overflow under ASan.
    {
        size_t sized = BRWalletAllAddrs(w, NULL, 0);
        BRAddress *a = (BRAddress *)malloc(sized * sizeof(*a)); // EXACT size
        growChains(w, 111);                                     // +21 addresses
        size_t got = BRWalletAllAddrs(w, a, sized);
        check(got <= sized, "fill after concurrent growth never exceeds addrsCount");
        free(a);
    }
    // and again with a growth shape that underflows `rest` on the pre-fix code
    {
        size_t sized = BRWalletAllAddrs(w, NULL, 0);
        BRAddress *a = (BRAddress *)malloc(sized * sizeof(*a));
        growChains(w, 260);
        size_t got = BRWalletAllAddrs(w, a, sized);
        check(got <= sized, "fill after large concurrent growth never exceeds addrsCount");
        free(a);
    }

    // ---- 5. the single-call API: no TOCTOU window at all --------------------------
    {
        BRWalletAddrOrigins origins;
        size_t n = 0;
        BRAddress *a = BRWalletCopyAllAddrs(w, &n, &origins);
        check(a != NULL && n > 0, "BRWalletCopyAllAddrs returns a populated snapshot");
        check(n == origins.derived + origins.watched,
              "origins split accounts for every returned address");
        check(origins.watched == 5, "origins.watched matches the 5 pinned addresses");
        int allSet = 1;
        for (size_t i = 0; i < n; i++) if (a[i].s[0] == '\0') allSet = 0;
        check(allSet, "BRWalletCopyAllAddrs populated every slot");
        // the watched entries live in the tail, after the derived chains
        int tailWatched = 1;
        for (size_t i = origins.derived; i < n; i++)
            if (! BRWalletContainsAddress(w, a[i].s)) tailWatched = 0;
        check(tailWatched, "watched entries occupy the tail of the snapshot");
        free(a);
    }

    // ---- 6. NULL-safety / degenerate inputs ---------------------------------------
    {
        size_t n = 123;
        BRAddress *a = BRWalletCopyAllAddrs(NULL, &n, NULL);
        check(a == NULL && n == 0, "BRWalletCopyAllAddrs(NULL wallet) fails closed");
        n = 456;
        a = BRWalletCopyAllAddrs(w, &n, NULL);   // NULL origins is allowed
        check(a != NULL && n > 0, "BRWalletCopyAllAddrs tolerates a NULL origins arg");
        free(a);
    }

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
