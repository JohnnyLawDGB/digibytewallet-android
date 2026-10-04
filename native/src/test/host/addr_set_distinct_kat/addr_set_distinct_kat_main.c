// Host KAT: the wallet's address snapshot lists every address exactly once.
//
// The snapshot (BRWalletCopyAllAddrs / BRWalletAllAddrs, and the counts derived from it) emits
// the derived chains and then the explicitly-watched tail. A watched pin is first resolved into
// the derived set when it belongs to one of the wallet's chains, so such a pin is already emitted
// by its chain. The invariant pinned here:
//
//   - each address appears in the snapshot once, whether it reached the wallet by derivation,
//     by a watch pin, or by both;
//   - a watched pin that is NOT derived is still emitted, in the watched tail;
//   - every count the wallet reports for the snapshot (sizing call, BRWalletAllAddrsCount,
//     BRWalletAddrSetKey, the origins split) equals the number of distinct addresses;
//   - the compact-filter element set built from the snapshot holds the script of every
//     address the wallet knows (no script is lost) and holds each script once.
//
// Each case prints "RESULT <case> pass|fail". run.sh builds a comparison arm with
// -DADDR_SET_DISTINCT_UNFIXED (the snapshot without the distinct rule) in which every RED case
// must fail, and the shipped arm in which every case must pass, at 64 and 32 bits.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRWalletFilterElements.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRKey.h"

static int g_fail = 0;
static int g_caseFail = 0;
static void check(int c, const char *d)
{
    printf(c ? "PASS: %s\n" : "FAIL: %s\n", d);
    if (!c) { g_fail++; g_caseFail++; }
}
static void caseBegin(void) { g_caseFail = 0; }
static void caseEnd(const char *name) { printf("RESULT %s %s\n", name, g_caseFail ? "fail" : "pass"); }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// scriptType: 0 = P2PKH, 1 = P2WPKH, 2 = P2TR
static BRAddress addrAt(BRMasterPubKey mpk, uint32_t chain, uint32_t idx, int scriptType)
{
    uint8_t pub[BRBIP32PubKey(NULL, 0, mpk, chain, idx)];
    size_t len = BRBIP32PubKey(pub, sizeof(pub), mpk, chain, idx);
    BRKey key;
    BRAddress a = BR_ADDRESS_NONE;
    if (BRKeySetPubKey(&key, pub, len)) {
        if (scriptType == 2) BRKeyTaprootAddress(&key, a.s, sizeof(a));
        else if (scriptType == 1) BRKeySegwitAddress(&key, a.s, sizeof(a), OP_0);
        else BRKeyAddress(&key, a.s, sizeof(a));
    }
    return a;
}

static size_t occurrences(const BRAddress *all, size_t n, const char *s)
{
    size_t k = 0;
    for (size_t i = 0; i < n; i++) if (strcmp(all[i].s, s) == 0) k++;
    return k;
}

static int cmpAddr(const void *a, const void *b) { return strcmp(((const BRAddress *)a)->s, ((const BRAddress *)b)->s); }

static size_t distinctCount(const BRAddress *all, size_t n)
{
    if (n == 0) return 0;
    BRAddress *copy = malloc(n * sizeof(*copy));
    memcpy(copy, all, n * sizeof(*copy));
    qsort(copy, n, sizeof(*copy), cmpAddr);
    size_t d = 1;
    for (size_t i = 1; i < n; i++) if (strcmp(copy[i].s, copy[i - 1].s) != 0) d++;
    free(copy);
    return d;
}

static int hasElement(const BRWalletFilterElements *fe, const uint8_t *want, size_t wantLen)
{
    size_t k = 0;
    for (size_t i = 0; fe && i < fe->count; i++)
        if (fe->elementLens[i] == wantLen && memcmp(fe->elements[i], want, wantLen) == 0) k++;
    return (int)k;
}

int main(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84  = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86  = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRMasterPubKey mpkLeg = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));

    // Every chain populated: BIP84, legacy m/0H (dual) and BIP86 taproot.
    BRWallet *w = BRWalletNewDual(NULL, 0, mpk84, mpkLeg);
    if (!w) { printf("FAIL: wallet created\n\n1 FAIL\n"); return 1; }
    BRWalletSetTaprootKey(w, mpk86);
    for (int scriptType = 0; scriptType <= 2; scriptType++)
        for (int internal = 0; internal <= 1; internal++)
            BRWalletUnusedAddrs(w, NULL, 10, internal, scriptType);

    // Watch pins of every shape the Receive screen produces:
    BRAddress derivedSegwit  = addrAt(mpk84, SEQUENCE_EXTERNAL_CHAIN, 2, 1);   // already derived
    BRAddress derivedTaproot = addrAt(mpk86, SEQUENCE_EXTERNAL_CHAIN, 0, 2);   // already derived (P2TR)
    BRAddress derivedLegacy  = addrAt(mpkLeg, SEQUENCE_EXTERNAL_CHAIN, 0, 0);  // legacy m/0H chain
    BRAddress resolvedSegwit = addrAt(mpk84, SEQUENCE_EXTERNAL_CHAIN, 120, 1); // derived BY the pin
    BRAddress farSegwit      = addrAt(mpk84, SEQUENCE_EXTERNAL_CHAIN, 900, 1); // never derived
    int containsBefore = BRWalletContainsAddress(w, resolvedSegwit.s);
    BRWalletAddWatchedAddress(w, derivedSegwit.s);
    BRWalletAddWatchedAddress(w, derivedTaproot.s);
    BRWalletAddWatchedAddress(w, derivedLegacy.s);
    BRWalletAddWatchedAddress(w, resolvedSegwit.s);
    BRWalletAddWatchedAddress(w, farSegwit.s);
    BRWalletAddWatchedAddress(w, derivedSegwit.s);   // idempotent re-pin

    size_t n = 0;
    BRWalletAddrOrigins origins = { 0, 0 };
    BRAddress *all = BRWalletCopyAllAddrs(w, &n, &origins);
    if (!all || n == 0) { printf("FAIL: snapshot built\n\n1 FAIL\n"); return 1; }
    size_t distinct = distinctCount(all, n);

    // ---- RED: a pin on an address its chain already emits appears once ------------------------
    caseBegin();
    check(occurrences(all, n, derivedSegwit.s) == 1, "a watched, derived P2WPKH address appears once");
    caseEnd("watched_derived_segwit_once");

    caseBegin();
    check(occurrences(all, n, derivedTaproot.s) == 1, "a watched, derived P2TR address appears once");
    caseEnd("watched_derived_taproot_once");

    caseBegin();
    check(occurrences(all, n, derivedLegacy.s) == 1, "a watched address on the legacy chain appears once");
    caseEnd("watched_derived_legacy_once");

    // ---- RED: a pin that the wallet derived because of the pin appears once -------------------
    caseBegin();
    check(!containsBefore, "fixture: the index-120 address is outside the window before the pin");
    check(occurrences(all, n, resolvedSegwit.s) == 1, "a pin resolved into the derived set appears once");
    caseEnd("watched_resolved_once");

    // ---- GUARD: a pin that is not derived is still emitted, in the watched tail ---------------
    caseBegin();
    check(occurrences(all, n, farSegwit.s) == 1, "a watched address that is not derived is emitted once");
    {
        int inTail = 0;
        for (size_t i = origins.derived; i < n; i++) if (strcmp(all[i].s, farSegwit.s) == 0) inTail = 1;
        check(inTail, "it sits in the watched tail of the snapshot");
    }
    caseEnd("watched_only_kept");

    // ---- RED: every count equals the number of distinct addresses -----------------------------
    caseBegin();
    check(n == distinct, "the snapshot has no repeated address");
    check(origins.derived + origins.watched == n, "the origins split accounts for every entry");
    check(origins.watched == 1, "the watched tail carries only the one pin that is not derived");
    check(BRWalletAllAddrs(w, NULL, 0) == distinct, "the sizing call reports the distinct count");
    check(BRWalletAllAddrsCount(w) == distinct, "BRWalletAllAddrsCount reports the distinct count");
    {
        uint64_t gen = 0; size_t cnt = 0;
        BRWalletAddrSetKey(w, &gen, &cnt);
        check(cnt == distinct, "BRWalletAddrSetKey reports the distinct count");
        check(gen != 0, "BRWalletAddrSetKey reports a generation stamp");
    }
    {
        size_t sized = BRWalletAllAddrs(w, NULL, 0);
        BRAddress *buf = malloc(sized * sizeof(*buf));
        size_t got = BRWalletAllAddrs(w, buf, sized);
        check(got == sized, "the two-call form fills exactly what it sized");
        check(distinctCount(buf, got) == got, "the two-call form has no repeated address");
        free(buf);
    }
    caseEnd("counts_are_distinct");

    // ---- RED + GUARD: the filter element set holds every script, each once --------------------
    caseBegin();
    {
        BRWalletFilterElements *fe = BRWalletGetFilterElements(w);
        check(fe != NULL, "filter elements built");
        int everyScriptOnce = 1, everyScriptPresent = 1;
        for (size_t i = 0; fe && i < n; i++) {
            uint8_t script[64];
            size_t sl = BRAddressScriptPubKey(script, sizeof(script), all[i].s);
            if (sl == 0) continue;
            int k = hasElement(fe, script, sl);
            if (k == 0) everyScriptPresent = 0;
            if (k != 1) everyScriptOnce = 0;
        }
        const BRAddress pins[] = { derivedSegwit, derivedTaproot, derivedLegacy, resolvedSegwit, farSegwit };
        for (size_t p = 0; fe && p < sizeof(pins) / sizeof(pins[0]); p++) {
            uint8_t script[64];
            size_t sl = BRAddressScriptPubKey(script, sizeof(script), pins[p].s);
            if (sl == 0 || hasElement(fe, script, sl) == 0) everyScriptPresent = 0;
        }
        check(everyScriptPresent, "every address the wallet knows has its script in the element set");
        check(everyScriptOnce, "each script appears in the element set once");
        check(fe && fe->count == distinct, "element count == distinct address count");
        BRWalletFilterElementsStats st;
        if (BRWalletFilterElementsGetStats(w, &st)) {
            check(st.derived + st.watched == st.elements, "stats: derived + watched == elements");
            check(st.watched == 1, "stats: one element comes from the watched tail");
        } else {
            check(0, "stats available");
        }
        BRWalletFilterElementsFree(fe);
    }
    caseEnd("filter_elements_distinct");

    free(all);

    // ---- RED: a pin that becomes derived later is still listed once -----------------------------
    // A pin outside the derived window is emitted from the tail; once its chain grows over it,
    // its chain emits it and the tail drops it. The distinct set is the same before and after,
    // and the snapshot still has no repeated address.
    caseBegin();
    {
        BRWallet *w2 = BRWalletNew(NULL, 0, mpk84);
        BRAddress later = addrAt(mpk84, SEQUENCE_EXTERNAL_CHAIN, 260, 1);   // beyond the resolve span
        BRWalletAddWatchedAddress(w2, later.s);
        size_t n1 = 0; BRWalletAddrOrigins o1;
        BRAddress *a1 = BRWalletCopyAllAddrs(w2, &n1, &o1);
        check(a1 && occurrences(a1, n1, later.s) == 1 && o1.watched == 1, "before: emitted once, from the tail");
        uint64_t gen1 = 0; size_t cnt1 = 0;
        BRWalletAddrSetKey(w2, &gen1, &cnt1);
        BRWalletUnusedAddrs(w2, NULL, 300, 0, 1);   // the external segwit chain grows over it
        size_t n2 = 0; BRWalletAddrOrigins o2;
        BRAddress *a2 = BRWalletCopyAllAddrs(w2, &n2, &o2);
        check(a2 && occurrences(a2, n2, later.s) == 1, "after: emitted once");
        check(a2 && o2.watched == 0, "after: emitted by its chain, not the tail");
        check(a2 && distinctCount(a2, n2) == n2, "after: no repeated address");
        uint64_t gen2 = 0; size_t cnt2 = 0;
        BRWalletAddrSetKey(w2, &gen2, &cnt2);
        check(gen2 != gen1, "the chain growth moved the generation stamp (cache key changes)");
        check(cnt2 == n2, "the key's count equals the snapshot size");
        free(a1); free(a2);
        BRWalletFree(w2);
    }
    caseEnd("pin_derived_later");

    BRWalletFree(w);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
