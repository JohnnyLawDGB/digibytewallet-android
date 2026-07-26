// Host KAT: BIP158 filter-element construction.
//
// Pins three properties that were all previously untested — cf_confirm_kat was the only
// KAT compiling BRWalletFilterElements.c and it has not built since the v4.0.0 bloom
// excision, so this file has shipped with zero regression coverage.
//
// 1. THE DIGIDOLLAR ALIAS. The wallet's DD receive address encodes the tap-tweaked output
//    key X(Q) of m/86'/20'/0'/0/0 — the SAME key as taprootExternalChain[0]. A DD token
//    output is a plain P2TR script, so the element a full node inserts into the basic
//    filter for a DD payment is exactly `OP_1 0x20 <X(Q)>` (34 bytes), which the taproot
//    chain already emits. The DD address STRING itself is unencodable (Base58Check over a
//    34-byte payload) and contributes nothing.
//    This alias is load-bearing and undocumented at both gate sites: if a future change
//    breaks it, DigiDollar receives stop being detected with no test failing. Hence this
//    KAT asserts the element is PRESENT, and that the DD string is NOT in the watch set.
//
// 2. ELEMENT COUNT IS NON-DECREASING as the wallet's address set grows. The pre-fix
//    BRWalletAllAddrs could silently TRUNCATE the match set when chains grew between its
//    sizing and filling calls — dropping the entire watched tail from the filter, which by
//    this repo's own framing is a silently missed receive.
//
// 3. THE STATS SNAPSHOT accounts for every address: elements + dropped == addrs, and
//    derived + watched == elements.
//
// Exit code 0 = all checks passed, 1 = check failed / ASan fault.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRWalletFilterElements.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"
#include "BRNetwork.h"
#include "BRKey.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static int hasElement(const BRWalletFilterElements *fe, const uint8_t *want, size_t wantLen) {
    for (size_t i = 0; fe && i < fe->count; i++)
        if (fe->elementLens[i] == wantLen && memcmp(fe->elements[i], want, wantLen) == 0) return 1;
    return 0;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRWallet *w = BRWalletNewDual(NULL, 0,
                                  BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)),
                                  BRBIP32MasterPubKeyLegacy(seed, sizeof(seed)));
    check(w != NULL, "wallet created");
    if (!w) { printf("\nFATAL\n"); return 1; }
    BRWalletSetTaprootKey(w, BRBIP32MasterPubKeyBIP86(seed, sizeof(seed)));

    // ---- the DigiDollar element, derived independently of the wallet ------------------
    BRAddress ta = BRWalletReceiveAddress(w, 2);                 // taproot addr[0]
    uint8_t tspk[64];
    size_t tspkLen = BRAddressScriptPubKey(tspk, sizeof(tspk), ta.s);
    check(tspkLen == 34 && tspk[0] == 0x51 && tspk[1] == 0x20,
          "taproot addr[0] encodes to a 34-byte OP_1 <32> script");

    char dd[128] = {0};
    size_t ddLen = BRDigiDollarAddressEncode(dd, sizeof(dd), &tspk[2], BRNetworkIsTestnet());
    check(ddLen > 0, "DigiDollar address encodes from that output key");

    uint8_t k32[32];
    check(BRDigiDollarAddressDecode(k32, dd, BRNetworkIsTestnet()) == 1,
          "DigiDollar address round-trips through decode");
    check(memcmp(k32, &tspk[2], 32) == 0,
          "DD payload IS the taproot output key X(Q) — the alias holds");

    // The DD string is not a DigiByte address and must never enter the watch set: it would
    // reach dumpAllAddresses, which the reconcile path POSTs to the backend in batches.
    BRWalletAddWatchedAddress(w, dd);
    check(BRWalletContainsAddress(w, dd) == 0,
          "DD address string is NOT admitted to the watch set");
    check(BRAddressScriptPubKey(NULL, 0, dd) == 0,
          "DD address string has no encodable scriptPubKey (as expected)");

    // ---- 1. the element is already present, via the taproot chain ---------------------
    uint8_t want[34]; want[0] = 0x51; want[1] = 0x20; memcpy(want + 2, k32, 32);

    BRWalletFilterElements *fe = BRWalletGetFilterElements(w);
    check(fe != NULL && fe->count > 0, "filter elements built");
    check(hasElement(fe, want, sizeof(want)),
          "DD element (OP_1 <X(Q)>) IS in the filter set via taprootExternalChain[0]");

    size_t firstCount = fe ? fe->count : 0;

    // ---- 3. the stats snapshot accounts for everything --------------------------------
    {
        BRWalletFilterElementsStats st;
        check(BRWalletFilterElementsGetStats(w, &st) == 1, "stats snapshot readable");
        check(st.elements == firstCount, "stats.elements matches the built element count");
        check(st.derived + st.watched == st.elements, "derived + watched == elements");
        check(st.elements + st.dropped == st.addrs, "elements + dropped == addrs enumerated");
        // A healthy wallet drops nothing: BRAddressIsValid and BRAddressScriptPubKey accept
        // the same set today. The counter exists so that if they ever diverge — a new address
        // format taught to one and not the other — it is visible instead of silent.
        check(st.dropped == 0, "no addresses dropped for a healthy wallet");
        check(st.allocFailures == 0, "no allocation failures");
        check(st.watched == 0, "DD string was rejected, so the watched tail is empty");
    }

    // stats are scoped to their owning wallet
    {
        BRWallet *w2 = BRWalletNew(NULL, 0, BRBIP32MasterPubKeyBIP84(seed, sizeof(seed)));
        BRWalletFilterElementsStats st;
        check(BRWalletFilterElementsGetStats(w2, &st) == 0,
              "stats for a wallet that never built are not another wallet's");
        BRWalletFree(w2);
    }

    BRWalletFilterElementsFree(fe);

    // ---- 2. element count never decreases as the address set grows --------------------
    {
        size_t prev = firstCount;
        int monotonic = 1;
        for (uint32_t gap = 120; gap <= 400; gap += 70) {
            for (int st = 0; st <= 2; st++)
                for (int internal = 0; internal <= 1; internal++)
                    BRWalletUnusedAddrs(w, NULL, gap, internal, st);

            BRWalletFilterElements *f2 = BRWalletGetFilterElements(w);
            size_t n = f2 ? f2->count : 0;
            if (n < prev) {
                printf("      regression: %zu -> %zu after growing to gap %u\n", prev, n, gap);
                monotonic = 0;
            }
            prev = n;
            BRWalletFilterElementsFree(f2);
        }
        check(monotonic, "element count is non-decreasing across a growing session");
        check(prev > firstCount, "element count actually grew (the test exercised growth)");
    }

    // the DD element survives all that growth
    {
        BRWalletFilterElements *f3 = BRWalletGetFilterElements(w);
        check(hasElement(f3, want, sizeof(want)), "DD element still present after growth");
        BRWalletFilterElementsFree(f3);
    }

    BRWalletFree(w);
    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
