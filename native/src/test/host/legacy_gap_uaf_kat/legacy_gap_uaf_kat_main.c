// Host KAT: use-after-free regression guard for _BRWalletPregenLegacyChain.
//
// BRWalletNewDual pregenerates the legacy (m/0H) recovery chains. The external
// recovery chain is filled to SEQUENCE_GAP_LIMIT_EXTERNAL_LEGACY (150) entries
// into an array_new(...,50) buffer, so array_add() reallocs the chain mid-fill.
// The pre-fix code did `BRSetAdd(allAddrs, &addrChain[last])` INSIDE the fill
// loop, so every pointer added before a realloc pointed into the since-freed
// block — a use-after-free that crashed BRWalletContainsAddress (strlen/strcmp
// on freed memory) and corrupted address matching once the block was reused.
//
// This KAT builds the dual wallet, then re-derives each legacy recovery external
// address and asserts the wallet still contains it. Built with -fsanitize=address:
//   pre-fix  -> ASan reports heap-use-after-free (exit != 0)
//   post-fix -> every address resolves, no UAF, ALL PASS.
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

// canonical all-zeros mnemonic (same fixture the other wallet KATs use)
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84    = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));

    // Triggers _BRWalletPregenLegacyChain (150 external recovery addrs into a
    // cap-50 array -> guaranteed mid-loop realloc).
    BRWallet *w = BRWalletNewDual(NULL, 0, mpk84, mpkLegacy);
    check(w != NULL, "dual wallet created (legacy recovery chains pregenerated)");
    if (!w) { printf("\nFATAL\n"); return 1; }

    // Re-derive each legacy recovery EXTERNAL address exactly as
    // _BRWalletPregenLegacyChain does (chain=SEQUENCE_EXTERNAL_CHAIN, P2PKH) and
    // assert containment. Each lookup does BRSetGet(allAddrs,..) -> traverses a
    // bucket -> dereferences stored address pointers; a dangling one is a UAF.
    int checked = 0, contained = 0;
    for (uint32_t idx = 0; idx < 150; idx++) {
        uint8_t pub[BRBIP32PubKey(NULL, 0, mpkLegacy, SEQUENCE_EXTERNAL_CHAIN, idx)];
        size_t len = BRBIP32PubKey(pub, sizeof(pub), mpkLegacy, SEQUENCE_EXTERNAL_CHAIN, idx);
        BRKey key;
        if (!BRKeySetPubKey(&key, pub, len)) continue;
        BRAddress a = BR_ADDRESS_NONE;
        if (!BRKeyAddress(&key, a.s, sizeof(a)) || BRAddressEq(&a, &BR_ADDRESS_NONE)) continue;
        checked++;
        if (BRWalletContainsAddress(w, a.s)) contained++;
    }
    check(checked >= 100, "derived legacy recovery addresses to probe");
    printf("  probed %d recovery addresses, %d contained\n", checked, contained);
    check(contained == checked, "every legacy recovery address is contained (no dangling pointer)");

    // A non-wallet address must also traverse buckets without crashing/UAF.
    (void)BRWalletContainsAddress(w, "DFakeAddrNotInWallet1111111111111111");

    BRWalletFree(w);
    printf(g_fail==0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
