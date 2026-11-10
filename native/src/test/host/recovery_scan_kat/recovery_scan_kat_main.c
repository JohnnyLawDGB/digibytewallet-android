// Host KAT: the Recover-funds scan decisions above one UTXO row (BRRecoveryScan.h).
//
// THE RULES (moved from Kotlin: RecoveryScanService.kt classifyWidening / highestUsed / State.Done and
// classifyDerived's cache condition; ForeignSweep.kt sweepSet; PassphraseScanVerdict.kt;
// RecoverableValue.kt):
//   - an address is used when a selected coin names it, or a returned transaction has an output with
//     its script (parsed by the core); the highest used index per chain asks the gap rule;
//   - one round: nothing derived -> answered empty; unanswered -> kept unreachable, never asked again;
//     otherwise the gap rule's outcome, an unknown outcome read as capped;
//   - a finished scan: any/all unreachable (a profile with no addresses was never asked), capped,
//     incomplete = unreachable or capped, cached only when every asked profile answered;
//   - the sweep set: positive totals; the native profiles only for a foreign phrase;
//   - the passphrase verdict: FOUND, LIKELY_TYPO, then INCOMPLETE, then NONE_ANYWHERE;
//   - recoverable value: a sweepable finding or DigiDollar cents; "nothing" is an answer only when the
//     DigiDollar lookup, if it ran, answered.
// Six comparison arms, one per bit of -DRECOVERY_SCAN_UNFIXED (see BRRecoveryScan.h): 1 an unreachable
// profile read as answered and empty; 2 incomplete ranked ahead of a positive finding; 4 coins only (an
// address paid and since emptied not used); 8 the native profiles swept on the wallet's own seed; 16 a
// profile with nothing derived read as answered and empty; 32 an unanswered DigiDollar scan read as a
// zero balance. Each arm fails exactly the cases run.sh lists for its bit.
//
// Cases carry Android's PassphraseScanVerdictTest, PartialScanFailureTest, RecoverableValueTest,
// ForeignSweepSelectionTest and RecoveryGapWidenTest case for case. RecoveryGapWidenTest scripted the
// gap rule (NativeBridge cannot load on the host JVM); here the rule is the core's own, so each widening
// case states the windows and rounds the real rule gives, and the loop is a reference executor written
// to the header's kind-B specification (one profile at a time; an unreachable profile kept as such).
// Every expectation is written here by hand.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdarg.h>
#include <inttypes.h>
#include <time.h>

#include "BRRecoveryScan.h"

#define BIG_BOUND_SECONDS 10.0

static int g_failed = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!cond) g_failed = 1;
}

#define N(a) (sizeof(a) / sizeof((a)[0]))

// ---- synthetic addresses -----------------------------------------------------------------------
// P2PKH addresses over a hash160 that encodes (tag, chain, index); written by the core's encoder.

static size_t p2pkhScript(uint8_t script[25], uint8_t tag, int chain, int32_t index)
{
    memset(script, 0, 25);
    script[0] = 0x76; script[1] = 0xa9; script[2] = 20;
    script[3] = tag; script[4] = (uint8_t)chain;
    script[5] = (uint8_t)((uint32_t)index >> 24); script[6] = (uint8_t)((uint32_t)index >> 16);
    script[7] = (uint8_t)((uint32_t)index >> 8);  script[8] = (uint8_t)index;
    script[23] = 0x88; script[24] = 0xac;
    return 25;
}

static void addressOf(char out[76], uint8_t tag, int chain, int32_t index)
{
    uint8_t s[25];
    p2pkhScript(s, tag, chain, index);
    if (BRAddressFromScriptPubKey(out, 76, s, sizeof(s)) == 0) out[0] = '\0';
}

// ---- raw transactions --------------------------------------------------------------------------
// version 1, one input (empty scriptSig), the given outputs, lock time 0: the network format.

typedef struct { int64_t sat; uint8_t script[64]; size_t len; } Out;

static size_t rawTx(uint8_t *buf, size_t cap, const Out *outs, size_t n)
{
    size_t off = 0, i;
#define PUT(b) do { if (off < cap) buf[off] = (uint8_t)(b); off++; } while (0)
    PUT(1); PUT(0); PUT(0); PUT(0);
    PUT(1);
    for (i = 0; i < 32; i++) PUT(i == 0 ? 0x11 : 0);
    PUT(0); PUT(0); PUT(0); PUT(0);
    PUT(0);
    PUT(0xff); PUT(0xff); PUT(0xff); PUT(0xff);
    if (n < 0xfd) PUT(n); else { PUT(0xfd); PUT(n & 0xff); PUT((n >> 8) & 0xff); }
    for (i = 0; i < n; i++) {
        size_t k;
        for (k = 0; k < 8; k++) PUT(((uint64_t)outs[i].sat >> (8 * k)) & 0xff);
        PUT(outs[i].len);
        for (k = 0; k < outs[i].len; k++) PUT(outs[i].script[k]);
    }
    PUT(0); PUT(0); PUT(0); PUT(0);
#undef PUT
    return off;
}

// ---- a reference executor of the scan loop (the header's kind-B spec, one profile) -------------

typedef struct { int chain; int32_t index; int64_t sat; } Coin;

typedef struct {
    const Coin *coins; size_t coinCount;
    // A transaction returned whenever (txChain, txIndex) is among the addresses asked.
    const uint8_t *tx; size_t txLen; int txChain; int32_t txIndex;
    int reachable;
    int fetches;
    int failFromRound;   // > 0: the lookup stops answering from this round on
    int slotShort;       // nonzero: the executor hands BRRecoveryScanUsedBegin one slot too few
} Lookup;

#define MAX_ROUNDS 64
typedef struct { int32_t ext, in, usedExt, usedInt, round, bound; } RoundRec;

typedef struct {
    BRRecoveryScanProfileResult result;
    BRRecoveryScanStep last;
    int rounds;
    RoundRec rec[MAX_ROUNDS];
    int32_t finalExt, finalIn;
} ScanOut;

static void scan(Lookup *lk, int32_t ext0, int32_t in0, int isNative, ScanOut *o)
{
    int32_t starts[1] = { ext0 }, startsIn[1] = { in0 };
    int32_t bound = BRRecoveryScanRoundBound(starts, startsIn, 1), ext = ext0, in = in0, round = 0;

    memset(o, 0, sizeof(*o));
    for (;;) {
        size_t n = (size_t)ext + (size_t)in, i, k;
        char (*text)[76] = malloc((n ? n : 1) * sizeof(*text));
        BRRecoveryScanAddress *a = malloc((n ? n : 1) * sizeof(*a));
        BRRecoveryScanSlot *slots = malloc((n ? n : 1) * sizeof(*slots));
        BRRecoveryScanUsed u;
        int32_t usedExt = -1, usedInt = -1, nextExt = 0, nextIn = 0;
        int64_t total = 0;
        int txAsked = 0, answered;

        round++;
        for (i = 0; i < n; i++) {
            int chain = i < (size_t)ext ? 0 : 1;
            int32_t idx = chain == 0 ? (int32_t)i : (int32_t)(i - (size_t)ext);
            addressOf(text[i], 0xa5, chain, idx);
            a[i].address = text[i]; a[i].chain = chain; a[i].index = idx;
            if (lk->tx && chain == lk->txChain && idx == lk->txIndex) txAsked = 1;
        }
        if (n > 0) lk->fetches++;   // nothing derived is not asked
        answered = lk->reachable && !(lk->failFromRound > 0 && round >= lk->failFromRound);
        // A used set that could not be built is never "nothing used": the round is unreachable.
        if (answered && n > 0 &&
            BRRecoveryScanUsedBegin(&u, a, n, slots, lk->slotShort ? n - 1 : n) != BR_RECOVERY_SCAN_OK) answered = 0;
        if (answered && n > 0) {
            for (k = 0; k < lk->coinCount; k++) {
                const Coin *c = &lk->coins[k];
                if ((c->chain == 0 && c->index < ext) || (c->chain == 1 && c->index < in)) {
                    char addr[76];
                    addressOf(addr, 0xa5, c->chain, c->index);
                    BRRecoveryScanUsedMarkAddress(&u, addr);
                    total += c->sat;
                }
            }
            if (txAsked) BRRecoveryScanUsedMarkRawTx(&u, lk->tx, lk->txLen, NULL);
            usedExt = BRRecoveryScanUsedHighest(&u, 0);
            usedInt = BRRecoveryScanUsedHighest(&u, 1);
        }
        if (round <= MAX_ROUNDS) {
            RoundRec r = { ext, in, usedExt, usedInt, round, bound };
            o->rec[round - 1] = r;
        }
        o->rounds = round;
        if (!answered) total = 0;   // the round's answer is the unreachable one: no rows
        o->last = BRRecoveryScanRoundStep(answered, n, ext, in, usedExt, usedInt, round, bound, &nextExt, &nextIn);
        o->result.addressCount = n;
        o->result.reachable = answered || n == 0;
        o->result.capped = (o->last == BR_RECOVERY_SCAN_STEP_CAPPED);
        o->result.isNative = isNative;
        o->result.totalSat = total;
        o->finalExt = ext; o->finalIn = in;
        free(slots); free(a); free(text);
        if (o->last != BR_RECOVERY_SCAN_STEP_ASK_AGAIN || round >= MAX_ROUNDS) return;
        ext = nextExt; in = nextIn;
    }
}

// ---- PassphraseScanVerdictTest -----------------------------------------------------------------

static void pv_funds_is_success(void)
{
    ck(BRRecoveryPassphraseVerdict(500, 1, 0, 0) == BR_RECOVERY_PASSPHRASE_FOUND, "500 with, 0 without: FOUND");
}

static void pv_typo(void)
{
    ck(BRRecoveryPassphraseVerdict(0, 1, 900, 0) == BR_RECOVERY_PASSPHRASE_LIKELY_TYPO, "0 with, 900 without: LIKELY_TYPO");
}

static void pv_none_anywhere(void)
{
    ck(BRRecoveryPassphraseVerdict(0, 1, 0, 0) == BR_RECOVERY_PASSPHRASE_NONE_ANYWHERE, "0 and 0, complete: NONE_ANYWHERE");
}

static void pv_incomplete_cannot_claim_nothing(void)
{
    ck(BRRecoveryPassphraseVerdict(0, 1, 0, 1) == BR_RECOVERY_PASSPHRASE_INCOMPLETE, "0 and 0, incomplete: INCOMPLETE");
}

static void pv_positive_survives_incomplete(void)
{
    ck(BRRecoveryPassphraseVerdict(500, 1, 0, 1) == BR_RECOVERY_PASSPHRASE_FOUND,
       "funds under the passphrase are found, whatever else went unchecked");
    ck(BRRecoveryPassphraseVerdict(0, 1, 900, 1) == BR_RECOVERY_PASSPHRASE_LIKELY_TYPO,
       "funds WITHOUT the passphrase are equally observed");
}

static void pv_no_comparison_no_typo(void)
{
    ck(BRRecoveryPassphraseVerdict(0, 0, 0, 0) == BR_RECOVERY_PASSPHRASE_NONE_ANYWHERE, "no comparison scan: NONE_ANYWHERE");
    ck(BRRecoveryPassphraseVerdict(0, 0, 900, 0) == BR_RECOVERY_PASSPHRASE_NONE_ANYWHERE,
       "no comparison scan: its total is ignored, so the typo verdict cannot fire");
}

static void pv_found_needs_no_comparison(void)
{
    ck(BRRecoveryPassphraseVerdict(1, 0, 0, 0) == BR_RECOVERY_PASSPHRASE_FOUND, "1 sat, no comparison: FOUND");
}

static void pv_negative_totals_are_not_funds(void)
{
    ck(BRRecoveryPassphraseVerdict(-5, 1, -7, 0) == BR_RECOVERY_PASSPHRASE_NONE_ANYWHERE, "negative totals are not funds");
}

// ---- PartialScanFailureTest --------------------------------------------------------------------

static BRRecoveryScanProfileResult res(size_t addresses, int reachable, int64_t sat)
{
    BRRecoveryScanProfileResult r;
    memset(&r, 0, sizeof(r));
    r.addressCount = addresses; r.reachable = reachable; r.totalSat = sat;
    return r;
}

static void partial_one_failed_among_several(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 0, 0), res(1, 1, 0), res(1, 1, 0) };
    ck(!BRRecoveryScanAllUnreachable(r, 3), "not everything was down");
    ck(BRRecoveryScanAnyUnreachable(r, 3), "but the scan is incomplete and must say so");
    ck(BRRecoveryScanIncomplete(r, 3), "incomplete");
}

static void partial_clean_empty_not_partial(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 1, 0), res(1, 1, 0) };
    ck(!BRRecoveryScanAllUnreachable(r, 2), "not all unreachable");
    ck(!BRRecoveryScanAnyUnreachable(r, 2), "nothing failed: 'no funds' is an honest answer here");
    ck(!BRRecoveryScanIncomplete(r, 2), "complete");
}

static void partial_total_outage_is_both(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 0, 0), res(1, 0, 0) };
    ck(BRRecoveryScanAllUnreachable(r, 2), "all unreachable");
    ck(BRRecoveryScanAnyUnreachable(r, 2), "any unreachable");
}

static void partial_no_addresses_not_unreachable(void)
{
    BRRecoveryScanProfileResult r[] = { res(0, 0, 0), res(1, 1, 0) };
    ck(!BRRecoveryScanAnyUnreachable(r, 2), "a profile never asked cannot have failed");
    ck(!BRRecoveryScanAllUnreachable(r, 2), "nor make the scan all-unreachable");
}

static void nothing_derived_is_incomplete(void)
{
    // Every profile starts at a nonzero window, so nothing derived is a failed derivation.
    BRRecoveryScanProfileResult r[] = { res(0, 1, 0), res(1, 1, 0) };
    ck(BRRecoveryScanAnyNothingDerived(r, 2), "a profile with nothing derived");
    ck(BRRecoveryScanIncomplete(r, 2), "makes the scan incomplete");
    ck(!BRRecoveryScanMayCache(r, 2), "and not cacheable");
    ck(BRRecoveryPassphraseVerdict(0, 0, 0, BRRecoveryScanIncomplete(r, 2)) == BR_RECOVERY_PASSPHRASE_INCOMPLETE,
       "so the verdict is INCOMPLETE, never NONE_ANYWHERE");
    ck(!BRRecoveryScanAnyUnreachable(r, 2), "it is not an unreachable lookup (it was never asked)");
}

static void partial_failure_with_funds_still_matters(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 0, 0), res(1, 1, 100000000) };
    ck(BRRecoveryScanAnyUnreachable(r, 2), "funds found elsewhere do not make it complete");
    ck(BRRecoveryScanIncomplete(r, 2), "incomplete");
}

static void partial_nothing_asked_is_all_unreachable(void)
{
    // Kotlin's State.Done: results exist and the asked subset is empty, so `all` holds.
    BRRecoveryScanProfileResult r[] = { res(0, 1, 0), res(0, 0, 0) };
    ck(BRRecoveryScanAllUnreachable(r, 2), "no profile asked: there is no answer to report (Kotlin's value)");
    ck(!BRRecoveryScanAnyUnreachable(r, 2), "and no lookup failed");
    ck(BRRecoveryScanIncomplete(r, 2) && !BRRecoveryScanMayCache(r, 2), "Incomplete and MayCache agree: incomplete, not cached");
    ck(!BRRecoveryScanAllUnreachable(r, 0) && !BRRecoveryScanAllUnreachable(NULL, 0), "no results: not all unreachable");
    ck(BRRecoveryScanIncomplete(NULL, 0), "no results: incomplete");
}

static void cache_only_when_all_reachable(void)
{
    BRRecoveryScanProfileResult ok[] = { res(1, 1, 0), res(7, 1, 0), res(1, 1, 5) };
    BRRecoveryScanProfileResult partial[] = { res(1, 1, 5), res(1, 0, 0) };
    BRRecoveryScanProfileResult down[] = { res(1, 0, 0) };
    BRRecoveryScanProfileResult underived[] = { res(1, 1, 5), res(0, 1, 0) };
    ok[1].capped = 1;
    ck(BRRecoveryScanMayCache(ok, 3), "every profile asked and answered (one capped): cached");
    ck(!BRRecoveryScanMayCache(partial, 2), "a partial outage is never remembered");
    ck(!BRRecoveryScanMayCache(down, 1), "a total outage is never remembered");
    ck(!BRRecoveryScanMayCache(underived, 2), "a failed derivation is never remembered");
    ck(!BRRecoveryScanMayCache(NULL, 0), "no results: nothing to remember");
}

static void incomplete_from_capped_or_unreachable(void)
{
    BRRecoveryScanProfileResult capped[] = { res(1, 1, 0), res(1, 1, 0) };
    BRRecoveryScanProfileResult down[] = { res(1, 1, 0), res(1, 0, 0) };
    capped[1].capped = 1;
    ck(BRRecoveryScanAnyCapped(capped, 2) && BRRecoveryScanIncomplete(capped, 2), "a capped path is incomplete");
    ck(!BRRecoveryScanAnyUnreachable(capped, 2), "capped is not unreachable");
    ck(!BRRecoveryScanAnyCapped(down, 2) && BRRecoveryScanIncomplete(down, 2), "an unreachable path is incomplete");
}

// ---- RecoverableValueTest ----------------------------------------------------------------------

static void rv_dollars_alone(void)
{
    ck(BRRecoverableValueExists(0, BR_RECOVERY_DD_ANSWERED, 100), "dollars alone are recoverable value");
    ck(BRRecoverableValueExists(0, BR_RECOVERY_DD_UNANSWERED, 100), "dollars found by a partly unanswered scan too");
}

static void rv_nothing(void)
{
    ck(!BRRecoverableValueExists(0, BR_RECOVERY_DD_ANSWERED, 0), "no utxos and no dollars is genuinely nothing");
}

static void rv_no_dd_scan_nothing(void)
{
    ck(!BRRecoverableValueExists(0, BR_RECOVERY_DD_NOT_RUN, 0), "no utxos and no dollar scan at all is nothing found");
    ck(!BRRecoverableValueExists(0, BR_RECOVERY_DD_NOT_RUN, 100), "cents of a scan that did not run are not read");
    ck(BRRecoverableValueAnswered(0, BR_RECOVERY_DD_NOT_RUN, 0), "no dollar scan by design: nothing found is an answer");
}

static void rv_utxos_alone(void)
{
    ck(BRRecoverableValueExists(2, BR_RECOVERY_DD_NOT_RUN, 0), "utxos alone are recoverable value");
}

static void rv_unreachable_dd_not_answered(void)
{
    ck(!BRRecoverableValueAnswered(0, BR_RECOVERY_DD_UNANSWERED, 0), "an unreachable dollar lookup is not nothing found");
    ck(BRRecoverableValueAnswered(1, BR_RECOVERY_DD_UNANSWERED, 0), "with value found there is nothing to close");
}

static void rv_failed_dd_scan_is_unanswered(void)
{
    // A DigiDollar scan that was attempted and failed (derivation empty, or it threw) is UNANSWERED with 0
    // cents -- never NOT_RUN -- and a state the header does not know reads as UNANSWERED.
    ck(!BRRecoverableValueExists(0, BR_RECOVERY_DD_UNANSWERED, 0), "a failed scan finds nothing to move");
    ck(!BRRecoverableValueAnswered(0, BR_RECOVERY_DD_UNANSWERED, 0), "and is not an answer");
    ck(BRRecoveryDigiDollarScanRead(7) == BR_RECOVERY_DD_UNANSWERED, "an unknown state 7 reads as UNANSWERED");
    ck(BRRecoveryDigiDollarScanRead(-1) == BR_RECOVERY_DD_UNANSWERED, "an unknown state -1 reads as UNANSWERED");
    ck(!BRRecoverableValueAnswered(0, 7, 0), "so an unknown state is not an answer");
    ck(BRRecoverableValueExists(0, 7, 5), "but cents it reports still count");
}

static void rv_reachable_zero_answered(void)
{
    ck(BRRecoverableValueAnswered(0, BR_RECOVERY_DD_ANSWERED, 0), "reachable zero is a confident nothing");
}

// ---- ForeignSweepSelectionTest -----------------------------------------------------------------

static void sweep_foreign_includes_native_own_excludes(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 1, 200000000), res(1, 1, 100000000) };
    size_t out[2] = { 9, 9 }, n = 9;
    r[0].isNative = 1;
    ck(BRRecoveryScanSweepSet(r, 2, 1, out, 2, &n) == BR_RECOVERY_SCAN_OK && n == 2 && out[0] == 0 && out[1] == 1,
       "foreign: native + legacy both swept (%zu)", n);
    ck(BRRecoveryScanSweepSet(r, 2, 0, out, 2, &n) == BR_RECOVERY_SCAN_OK && n == 1 && out[0] == 1,
       "own: native left in place (%zu)", n);
}

static void sweep_unfunded_not_swept(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 1, 0), res(1, 1, -5), res(1, 0, 0), res(1, 1, 7) };
    size_t out[4], n = 9;
    int64_t t = -1;
    ck(BRRecoveryScanSweepSet(r, 4, 1, out, 4, &n) == BR_RECOVERY_SCAN_OK && n == 1 && out[0] == 3,
       "only a positive total is swept");
    ck(BRRecoveryScanTotal(r, 4, out, n, &t) == BR_RECOVERY_SCAN_OK && t == 7, "the set's total is 7");
}

static void sweep_and_total_arguments(void)
{
    BRRecoveryScanProfileResult r[] = { res(1, 1, 5), res(1, 1, 6) }, big[] = { res(1, 1, INT64_MAX), res(1, 1, 1) };
    size_t out[1], n = 9, bad[1] = { 2 };
    int64_t t = -1;
    ck(BRRecoveryScanSweepSet(r, 2, 1, out, 1, &n) == BR_RECOVERY_SCAN_BUFFER_TOO_SMALL && n == 0, "too small");
    ck(BRRecoveryScanSweepSet(r, 2, 1, out, 1, NULL) == BR_RECOVERY_SCAN_NULL_ARGUMENT, "NULL count");
    ck(BRRecoveryScanSweepSet(NULL, 0, 1, NULL, 0, &n) == BR_RECOVERY_SCAN_OK && n == 0, "nothing to sweep");
    ck(BRRecoveryScanTotal(r, 2, NULL, 2, &t) == BR_RECOVERY_SCAN_OK && t == 11, "total 11");
    ck(BRRecoveryScanTotal(big, 2, NULL, 2, &t) == BR_RECOVERY_SCAN_OVERFLOW && t == 0, "overflow reported, never wrapped");
    ck(BRRecoveryScanTotal(r, 2, bad, 1, &t) == BR_RECOVERY_SCAN_BAD_INDEX, "bad index");
    ck(!BRRecoveryScanSweeps(NULL, 1), "NULL result not swept");
}

// ---- RecoveryGapWidenTest ----------------------------------------------------------------------

static void widen_coin_past_first_window(void)
{
    // 150 inside the 200 window, 250 past it. The real rule: 200 -> 300 (150 + 101, by a whole gap),
    // -> 400 (250 + 101 > 300), then finished (351 <= 400).
    Coin coins[] = { { 0, 150, 1000 }, { 0, 250, 1000 } };
    Lookup lk = { coins, 2, NULL, 0, 0, 0, 1, 0 };
    ScanOut o;
    scan(&lk, 200, 100, 0, &o);
    ck(o.result.totalSat == 2000, "both coins found: %" PRId64, o.result.totalSat);
    ck(o.last == BR_RECOVERY_SCAN_STEP_FINISHED && !o.result.capped, "finished, not capped");
    ck(lk.fetches == 3 && o.rounds == 3, "three rounds (%d)", o.rounds);
    ck(o.rec[0].ext == 200 && o.rec[0].usedExt == 150 && o.rec[0].in == 100 && o.rec[0].usedInt == -1, "round 1 at 200: used 150");
    ck(o.rec[1].ext == 300 && o.rec[1].usedExt == 250, "round 2 at 300: used 250");
    ck(o.rec[2].ext == 400 && o.rec[2].usedExt == 250 && o.rec[2].in == 100, "round 3 at 400; internal never grew");
    ck(o.rec[0].bound == 20, "the bound from starting windows 200/100 is 20 (%d)", o.rec[0].bound);
}

static void widen_empty_path_asked_once(void)
{
    Lookup lk = { NULL, 0, NULL, 0, 0, 0, 1, 0 };
    ScanOut o;
    scan(&lk, 200, 100, 0, &o);
    ck(lk.fetches == 1 && o.result.totalSat == 0 && !o.result.capped, "asked once, empty, not capped");
    ck(o.rec[0].ext == 200 && o.rec[0].in == 100 && o.rec[0].usedExt == -1 && o.rec[0].usedInt == -1 &&
       o.rec[0].round == 1 && o.rec[0].bound == 20, "asked with (200, 100, nothing used, round 1, bound 20)");
}

static void widen_capped_marked_incomplete(void)
{
    // Every external address funded: the far end stays in use until the 2000 cap stops the window.
    Coin *coins = malloc(2000 * sizeof(*coins));
    Lookup lk = { coins, 2000, NULL, 0, 0, 0, 1, 0 };
    BRRecoveryScanProfileResult r[1];
    ScanOut o;
    int i;
    for (i = 0; i < 2000; i++) { coins[i].chain = 0; coins[i].index = i; coins[i].sat = 1; }
    scan(&lk, 200, 100, 0, &o);
    r[0] = o.result;
    ck(o.last == BR_RECOVERY_SCAN_STEP_CAPPED && o.result.capped, "capped");
    ck(BRRecoveryScanAnyCapped(r, 1) && BRRecoveryScanIncomplete(r, 1), "and the scan says so");
    ck(o.result.totalSat == 2000 && o.finalExt == 2000, "the answer at the cap is kept: 2000 coins at 2000");
    free(coins);
}

static void widen_unknown_outcome_capped(void)
{
    ck(BRRecoveryScanStepForGapOutcome(7) == BR_RECOVERY_SCAN_STEP_CAPPED, "7 reads as capped");
    ck(BRRecoveryScanStepForGapOutcome(-1) == BR_RECOVERY_SCAN_STEP_CAPPED, "-1 reads as capped");
    ck(BRRecoveryScanStepForGapOutcome(BR_RECOVERY_GAP_CAPPED) == BR_RECOVERY_SCAN_STEP_CAPPED, "capped");
    ck(BRRecoveryScanStepForGapOutcome(BR_RECOVERY_GAP_FINISHED) == BR_RECOVERY_SCAN_STEP_FINISHED, "finished");
    ck(BRRecoveryScanStepForGapOutcome(BR_RECOVERY_GAP_ASK_AGAIN) == BR_RECOVERY_SCAN_STEP_ASK_AGAIN, "ask again");
}

static void widen_paid_but_emptied_counts(void)
{
    // 190 holds nothing now, but a transaction returned with 10 pays it; 280 holds a coin past the window.
    Coin coins[] = { { 0, 10, 500 }, { 0, 280, 1000 } };
    Out outs[2];
    uint8_t tx[256];
    size_t len;
    Lookup lk;
    ScanOut o;
    outs[0].sat = 700; outs[0].len = p2pkhScript(outs[0].script, 0xa5, 0, 190);
    outs[1].sat = 9; outs[1].len = p2pkhScript(outs[1].script, 0x77, 0, 5);   // someone else's
    len = rawTx(tx, sizeof(tx), outs, 2);
    memset(&lk, 0, sizeof(lk));
    lk.coins = coins; lk.coinCount = 2; lk.tx = tx; lk.txLen = len; lk.txChain = 0; lk.txIndex = 10;
    lk.reachable = 1; lk.fetches = 0;
    scan(&lk, 200, 100, 0, &o);
    ck(o.rec[0].usedExt == 190, "round 1 is asked with 190, the paid-and-emptied address (%d)", o.rec[0].usedExt);
    ck(o.result.totalSat == 1500, "the coin at 280 is found: %" PRId64, o.result.totalSat);
    ck(o.last == BR_RECOVERY_SCAN_STEP_FINISHED, "finished");
}

static void widen_round_bound_and_numbering(void)
{
    // The always-growing lookup of widen_capped_marked_incomplete: rounds at 200, 300, ..., 2000.
    Coin *coins = malloc(2000 * sizeof(*coins));
    Lookup lk = { coins, 2000, NULL, 0, 0, 0, 1, 0 };
    ScanOut o;
    int i, ok = 1;
    for (i = 0; i < 2000; i++) { coins[i].chain = 0; coins[i].index = i; coins[i].sat = 1; }
    scan(&lk, 200, 100, 0, &o);
    ck(o.rounds == 19 && lk.fetches == 19, "19 rounds (%d)", o.rounds);
    for (i = 0; i < o.rounds && i < MAX_ROUNDS; i++) {
        if (o.rec[i].round != i + 1 || o.rec[i].bound != 20 || o.rec[i].ext != 200 + 100 * i || o.rec[i].in != 100) ok = 0;
    }
    ck(ok, "each round numbered from 1, bound 20, at the windows the previous round gave");
    // A bound reached while still growing is capped: round 2 of bound 2.
    {
        int32_t ne = 0, ni = 0;
        ck(BRRecoveryScanRoundStep(1, 300, 200, 100, 199, -1, 2, 2, &ne, &ni) == BR_RECOVERY_SCAN_STEP_CAPPED && ne == 200 && ni == 100,
           "still growing at the round bound: capped, windows unchanged");
        ck(BRRecoveryScanRoundStep(1, 300, 200, 100, 199, -1, 1, 2, &ne, &ni) == BR_RECOVERY_SCAN_STEP_ASK_AGAIN && ne == 300 && ni == 100,
           "before the bound: asked again at 300");
    }
    free(coins);
}

static void widen_unreachable_kept_unreachable(void)
{
    Coin coins[] = { { 0, 150, 1000 } };
    Lookup lk = { coins, 1, NULL, 0, 0, 0, 0, 0 };
    BRRecoveryScanProfileResult r[2];
    ScanOut o;
    scan(&lk, 200, 100, 0, &o);
    ck(o.last == BR_RECOVERY_SCAN_STEP_UNREACHABLE, "an unanswered lookup is UNREACHABLE (step %d)", (int)o.last);
    ck(lk.fetches == 1, "and not asked again in this scan");
    r[0] = o.result; r[1] = res(10, 1, 0);
    ck(BRRecoveryScanAnyUnreachable(r, 2) && !BRRecoveryScanMayCache(r, 2), "its result is unreachable and not cached");
    ck(BRRecoveryScanRoundStep(1, 0, 200, 100, -1, -1, 1, 20, NULL, NULL) == BR_RECOVERY_SCAN_STEP_NOTHING_DERIVED,
       "nothing derived: answered empty");
    ck(BRRecoveryScanRoundStep(0, 0, 200, 100, -1, -1, 1, 20, NULL, NULL) == BR_RECOVERY_SCAN_STEP_NOTHING_DERIVED,
       "nothing derived is never unreachable");
}

static void widen_nothing_derived_is_incomplete(void)
{
    // A derivation that came back empty (Android's `?: emptyArray()`): the profile is kept with nothing
    // derived, never asked, and the scan is incomplete.
    Lookup lk = { NULL, 0, NULL, 0, 0, 0, 1, 0, 0, 0 };
    BRRecoveryScanProfileResult r[2];
    ScanOut o;
    scan(&lk, 0, 0, 0, &o);
    ck(o.last == BR_RECOVERY_SCAN_STEP_NOTHING_DERIVED && lk.fetches == 0, "nothing derived, nothing asked");
    r[0] = o.result; r[1] = res(300, 1, 0);
    ck(BRRecoveryScanIncomplete(r, 2) && !BRRecoveryScanMayCache(r, 2), "incomplete, not cached");
    ck(BRRecoveryPassphraseVerdict(0, 0, 0, BRRecoveryScanIncomplete(r, 2)) == BR_RECOVERY_PASSPHRASE_INCOMPLETE,
       "the verdict cannot be NONE_ANYWHERE");
}

static void widen_used_begin_failure_is_unreachable(void)
{
    // The lookup answered, but the used set could not be built (here: too few slots). Never "nothing
    // used": the round is unreachable.
    Coin coins[] = { { 0, 150, 1000 } };
    Lookup lk = { coins, 1, NULL, 0, 0, 0, 1, 0, 0, 1 };
    BRRecoveryScanProfileResult r[1];
    ScanOut o;
    scan(&lk, 200, 100, 0, &o);
    ck(o.last == BR_RECOVERY_SCAN_STEP_UNREACHABLE, "the round is UNREACHABLE (step %d)", (int)o.last);
    r[0] = o.result;
    ck(BRRecoveryScanAnyUnreachable(r, 1) && BRRecoveryScanIncomplete(r, 1) && !BRRecoveryScanMayCache(r, 1),
       "the profile is unreachable, the scan incomplete and not cached");
}

static void widen_later_round_unreachable_replaces_rows(void)
{
    // KNOWN LIMIT (fail-closed, as Kotlin): round 1 finds the coin at 150 and widens; round 2 is not
    // answered, and its empty unreachable answer replaces round 1's rows.
    Coin coins[] = { { 0, 150, 1000 }, { 0, 250, 1000 } };
    Lookup lk = { coins, 2, NULL, 0, 0, 0, 1, 0, 2, 0 };
    BRRecoveryScanProfileResult r[1];
    ScanOut o;
    scan(&lk, 200, 100, 0, &o);
    ck(o.rounds == 2 && o.last == BR_RECOVERY_SCAN_STEP_UNREACHABLE, "round 2 unreachable (%d rounds, step %d)", o.rounds, (int)o.last);
    ck(o.result.totalSat == 0 && !o.result.reachable, "the kept answer is round 2's: unreachable, no rows");
    r[0] = o.result;
    ck(BRRecoveryScanIncomplete(r, 1) && BRRecoveryPassphraseVerdict(0, 0, 0, 1) == BR_RECOVERY_PASSPHRASE_INCOMPLETE,
       "reported incomplete, never 'no funds'");
}

static void used_script_kinds(void)
{
    // A returned transaction paying a P2SH-P2WPKH, a P2WPKH and a P2TR address: each is used through it.
    uint8_t sh[23], wpkh[22], tr[34];
    char a1[76], a2[76], a3[76];
    BRRecoveryScanAddress a[3];
    BRRecoveryScanSlot s[3];
    BRRecoveryScanUsed u;
    Out outs[3];
    uint8_t tx[512];
    size_t i, len, m = 9;
    sh[0] = 0xa9; sh[1] = 20; for (i = 0; i < 20; i++) sh[2 + i] = (uint8_t)(0x21 + i); sh[22] = 0x87;
    wpkh[0] = 0x00; wpkh[1] = 20; for (i = 0; i < 20; i++) wpkh[2 + i] = (uint8_t)(0x41 + i);
    tr[0] = 0x51; tr[1] = 32; for (i = 0; i < 32; i++) tr[2 + i] = (uint8_t)(0x61 + i);
    ck(BRAddressFromScriptPubKey(a1, sizeof(a1), sh, sizeof(sh)) > 0 && a1[0] == 'S', "P2SH address %s", a1);
    ck(BRAddressFromScriptPubKey(a2, sizeof(a2), wpkh, sizeof(wpkh)) > 0 && strncmp(a2, "dgb1q", 5) == 0, "P2WPKH address %s", a2);
    ck(BRAddressFromScriptPubKey(a3, sizeof(a3), tr, sizeof(tr)) > 0 && strncmp(a3, "dgb1p", 5) == 0, "P2TR address %s", a3);
    a[0].address = a1; a[0].chain = 0; a[0].index = 11;
    a[1].address = a2; a[1].chain = 1; a[1].index = 22;
    a[2].address = a3; a[2].chain = 0; a[2].index = 33;
    BRRecoveryScanUsedBegin(&u, a, 3, s, 3);
    for (i = 0; i < 3; i++) outs[i].sat = (int64_t)(1000 * (i + 1));
    memcpy(outs[0].script, sh, sizeof(sh)); outs[0].len = sizeof(sh);
    memcpy(outs[1].script, wpkh, sizeof(wpkh)); outs[1].len = sizeof(wpkh);
    memcpy(outs[2].script, tr, sizeof(tr)); outs[2].len = sizeof(tr);
    len = rawTx(tx, sizeof(tx), outs, 3);
    ck(BRRecoveryScanUsedMarkRawTx(&u, tx, len, &m) == BR_RECOVERY_SCAN_OK && m == 3, "three outputs matched (%zu)", m);
    ck(BRRecoveryScanUsedIs(&u, 0) && BRRecoveryScanUsedIs(&u, 1) && BRRecoveryScanUsedIs(&u, 2), "each kind used");
    ck(BRRecoveryScanUsedHighest(&u, 0) == 33 && BRRecoveryScanUsedHighest(&u, 1) == 22, "receive 33, change 22");
}

static void round_bound_from_profiles(void)
{
    size_t n = 0;
    const BRDerivationProfile *p = BRDerivationProfiles(&n);
    int32_t e[2] = { 300, 200 }, i[2] = { 50, 100 };
    ck(BRRecoveryScanRoundBoundForProfiles(p, n) == 20, "the built-in table's bound is 20");
    ck(BRRecoveryScanRoundBound(e, i, 2) == BRRecoveryGapScanRoundBound(200, 50), "the smallest windows, per chain");
    ck(BRRecoveryScanRoundBound(NULL, NULL, 0) == BRRecoveryGapScanRoundBound(0, 0), "no profiles: the bound of windows 0 and 0");
    ck(BRRecoveryScanRoundBound(NULL, i, 2) == 0 && BRRecoveryScanRoundBound(e, NULL, 2) == 0, "count > 0 with a NULL array: 0");
    ck(BRRecoveryScanRoundBoundForProfiles(NULL, 3) == 0, "count > 0 with NULL profiles: 0");
    ck(BRRecoveryScanRoundBoundForProfiles(NULL, 0) == BRRecoveryGapScanRoundBound(0, 0), "no profiles: windows 0 and 0");
    {
        int32_t ne = 0, ni = 0;
        ck(BRRecoveryScanRoundStep(1, 300, 200, 100, 150, -1, 1, 0, &ne, &ni) == BR_RECOVERY_SCAN_STEP_CAPPED && ne == 200,
           "a bound of 0 never asks again: a path that would grow is capped");
    }
}

// ---- used addresses ----------------------------------------------------------------------------

static void used_highest_per_chain(void)
{
    char a3[76], b7[76], c9[76];
    BRRecoveryScanAddress one[1], three[3];
    BRRecoveryScanSlot s[3];
    BRRecoveryScanUsed u;
    addressOf(a3, 1, 0, 3); addressOf(b7, 1, 0, 7); addressOf(c9, 1, 1, 9);
    one[0].address = a3; one[0].chain = 0; one[0].index = 3;
    BRRecoveryScanUsedBegin(&u, one, 1, s, 1);
    BRRecoveryScanUsedMarkAddress(&u, a3);
    ck(BRRecoveryScanUsedHighest(&u, 1) == -1, "nothing used on the change chain");
    three[0] = one[0];
    three[1].address = b7; three[1].chain = 0; three[1].index = 7;
    three[2].address = c9; three[2].chain = 1; three[2].index = 9;
    BRRecoveryScanUsedBegin(&u, three, 3, s, 3);
    BRRecoveryScanUsedMarkAddress(&u, a3); BRRecoveryScanUsedMarkAddress(&u, b7); BRRecoveryScanUsedMarkAddress(&u, c9);
    ck(BRRecoveryScanUsedHighest(&u, 0) == 7, "receive: 7");
    ck(BRRecoveryScanUsedHighest(&u, 1) == 9, "change: 9");
}

static void used_coin_matching(void)
{
    char t[5][76], other[76], upper[76];
    BRRecoveryScanAddress a[6];
    BRRecoveryScanSlot s[6];
    BRRecoveryScanUsed u;
    size_t i;
    for (i = 0; i < 5; i++) addressOf(t[i], 2, 0, (int32_t)i);
    addressOf(other, 3, 0, 0);
    // Entries 0..4, then entry 5 repeats address 4 (one key in two encodings coinciding), and an empty one.
    for (i = 0; i < 5; i++) { a[i].address = t[i]; a[i].chain = 0; a[i].index = (int32_t)i; }
    a[5].address = ""; a[5].chain = 0; a[5].index = 99;
    ck(BRRecoveryScanUsedBegin(&u, a, 6, s, 5) == BR_RECOVERY_SCAN_BUFFER_TOO_SMALL, "slots < addresses: too small");
    ck(BRRecoveryScanUsedHighest(&u, 0) == -1, "and it matches nothing");
    ck(BRRecoveryScanUsedBegin(&u, a, 6, s, 6) == BR_RECOVERY_SCAN_OK, "begin");
    ck(!BRRecoveryScanUsedMarkAddress(&u, other), "a coin on another address marks nothing");
    ck(!BRRecoveryScanUsedMarkAddress(&u, ""), "an empty address marks nothing");
    ck(!BRRecoveryScanUsedMarkAddress(&u, NULL), "NULL marks nothing");
    ck(BRRecoveryScanUsedHighest(&u, 0) == -1, "nothing used yet");
    strcpy(upper, t[2]);
    for (i = 0; upper[i]; i++) if (upper[i] >= 'a' && upper[i] <= 'z') { upper[i] = (char)(upper[i] - 32); break; }
    ck(!BRRecoveryScanUsedMarkAddress(&u, upper), "addresses match exactly, no case folding");
    ck(BRRecoveryScanUsedMarkAddress(&u, t[2]), "a coin on index 2");
    ck(BRRecoveryScanUsedIs(&u, 2) && !BRRecoveryScanUsedIs(&u, 1) && !BRRecoveryScanUsedIs(&u, 5), "only 2 is used");
    ck(BRRecoveryScanUsedHighest(&u, 0) == 2, "highest 2");
    a[5].address = t[4];
    BRRecoveryScanUsedBegin(&u, a, 6, s, 6);
    BRRecoveryScanUsedMarkAddress(&u, t[4]);
    ck(BRRecoveryScanUsedIs(&u, 4) && BRRecoveryScanUsedIs(&u, 5), "equal addresses share one mark");
    ck(BRRecoveryScanUsedHighest(&u, 0) == 99, "the copy at 99 counts too");
    ck(BRRecoveryScanUsedBegin(&u, NULL, 0, NULL, 0) == BR_RECOVERY_SCAN_OK && BRRecoveryScanUsedHighest(&u, 0) == -1,
       "no addresses: nothing used");
    ck(BRRecoveryScanUsedBegin(NULL, a, 6, s, 6) == BR_RECOVERY_SCAN_NULL_ARGUMENT, "NULL matcher");
}

static void used_script_matching(void)
{
    char t[4][76];
    BRRecoveryScanAddress a[4];
    BRRecoveryScanSlot s[4];
    BRRecoveryScanUsed u;
    Out outs[3];
    uint8_t tx[512];
    size_t i, len, m = 9;
    for (i = 0; i < 4; i++) { addressOf(t[i], 4, (int)(i & 1), (int32_t)i); a[i].address = t[i]; a[i].chain = (int)(i & 1); a[i].index = (int32_t)i; }
    BRRecoveryScanUsedBegin(&u, a, 4, s, 4);
    outs[0].sat = 1; outs[0].len = p2pkhScript(outs[0].script, 4, 1, 3);   // pays index 3 (change)
    outs[1].sat = 1; outs[1].len = p2pkhScript(outs[1].script, 9, 0, 0);   // pays someone else
    outs[2].sat = 0; outs[2].len = 2; outs[2].script[0] = 0x6a; outs[2].script[1] = 0x00;   // OP_RETURN
    len = rawTx(tx, sizeof(tx), outs, 3);
    ck(BRRecoveryScanUsedMarkRawTx(&u, tx, len, &m) == BR_RECOVERY_SCAN_OK && m == 1, "one output pays a derived address (%zu)", m);
    ck(BRRecoveryScanUsedIs(&u, 3) && !BRRecoveryScanUsedIs(&u, 0), "index 3 is used through the transaction");
    ck(BRRecoveryScanUsedHighest(&u, 1) == 3 && BRRecoveryScanUsedHighest(&u, 0) == -1, "change 3, receive none");
    ck(BRRecoveryScanUsedMarkScript(&u, outs[0].script, outs[0].len), "the script alone marks it");
    ck(!BRRecoveryScanUsedMarkScript(&u, outs[0].script, 24), "a prefix of a script is not the script");
}

static void used_unparseable_tx_skipped(void)
{
    char t[76];
    BRRecoveryScanAddress a[1];
    BRRecoveryScanSlot s[1];
    BRRecoveryScanUsed u;
    Out o;
    uint8_t tx[128], junk[3] = { 1, 2, 3 };
    size_t len, m = 9;
    addressOf(t, 5, 0, 0); a[0].address = t; a[0].chain = 0; a[0].index = 0;
    BRRecoveryScanUsedBegin(&u, a, 1, s, 1);
    o.sat = 1; o.len = p2pkhScript(o.script, 5, 0, 0);
    len = rawTx(tx, sizeof(tx), &o, 1);
    ck(BRRecoveryScanUsedMarkRawTx(&u, junk, sizeof(junk), &m) == BR_RECOVERY_SCAN_UNPARSEABLE && m == 0, "junk: unparseable");
    ck(BRRecoveryScanUsedMarkRawTx(&u, tx, len - 1, &m) == BR_RECOVERY_SCAN_UNPARSEABLE, "a truncated transaction: unparseable");
    ck(BRRecoveryScanUsedMarkRawTx(&u, NULL, 0, &m) == BR_RECOVERY_SCAN_UNPARSEABLE, "no bytes: unparseable");
    ck(BRRecoveryScanUsedHighest(&u, 0) == -1, "and they add nothing");
}

static void used_large_inputs(void)
{
    // A profile's widest windows in two encodings' worth of addresses (6000), 200000 coins the lookup
    // names (most on addresses not ours) and 2000 transactions of 100 outputs.
    const size_t D = 6000, U = 200000, T = 2000, O = 100;
    char (*text)[76] = malloc(D * sizeof(*text)), addr[76];
    BRRecoveryScanAddress *a = malloc(D * sizeof(*a));
    BRRecoveryScanSlot *s = malloc(D * sizeof(*s));
    Out *outs = malloc(O * sizeof(*outs));
    uint8_t *tx = malloc(64 + O * 40);
    BRRecoveryScanUsed u;
    size_t i, k, hits = 0, len;
    clock_t start;
    double secs;
    for (i = 0; i < D; i++) {
        int chain = i < 4000 ? 0 : 1;
        int32_t idx = chain == 0 ? (int32_t)(i % 2000) : (int32_t)((i - 4000) % 1000);
        addressOf(text[i], (uint8_t)(i < 2000 || (i >= 4000 && i < 5000) ? 6 : 7), chain, idx);
        a[i].address = text[i]; a[i].chain = chain; a[i].index = idx;
    }
    start = clock();
    BRRecoveryScanUsedBegin(&u, a, D, s, D);
    for (k = 0; k < U; k++) {
        // One in 1000 names a receive address of tag 6, at 1499 down to 1300; the rest are strangers.
        if (k % 1000 == 0) addressOf(addr, 6, 0, (int32_t)(1499 - k / 1000));
        else addressOf(addr, 8, 0, (int32_t)k);
        hits += (size_t)BRRecoveryScanUsedMarkAddress(&u, addr);
    }
    for (k = 0; k < T; k++) {
        size_t m = 0, j;
        for (j = 0; j < O; j++) {
            outs[j].sat = 1;
            // The last transaction pays change index 777 (tag 7); everything else pays strangers.
            if (k == T - 1 && j == O - 1) outs[j].len = p2pkhScript(outs[j].script, 7, 1, 777);
            else outs[j].len = p2pkhScript(outs[j].script, 9, (int)(j & 1), (int32_t)(k * O + j));
        }
        len = rawTx(tx, 64 + O * 40, outs, O);
        BRRecoveryScanUsedMarkRawTx(&u, tx, len, &m);
        hits += m;
    }
    secs = (double)(clock() - start) / CLOCKS_PER_SEC;
    ck(hits == 201, "200 coins and one output matched (%zu)", hits);
    ck(BRRecoveryScanUsedHighest(&u, 0) == 1499, "receive: 1499 (%d)", BRRecoveryScanUsedHighest(&u, 0));
    ck(BRRecoveryScanUsedHighest(&u, 1) == 777, "change: 777, through the transaction (%d)", BRRecoveryScanUsedHighest(&u, 1));
    ck(secs < BIG_BOUND_SECONDS, "in %.3f s (bound %.1f s)", secs, BIG_BOUND_SECONDS);
    free(tx); free(outs); free(s); free(a); free(text);
}

static void used_one_address_many_times(void)
{
    // Every derived entry is the same address: one mark serves all, and marking it 100000 times stays
    // O(log n) each.
    const size_t D = 6000, U = 100000;
    char t[76];
    BRRecoveryScanAddress *a = malloc(D * sizeof(*a));
    BRRecoveryScanSlot *s = malloc(D * sizeof(*s));
    BRRecoveryScanUsed u;
    size_t i;
    clock_t start;
    double secs;
    addressOf(t, 10, 0, 0);
    for (i = 0; i < D; i++) { a[i].address = t; a[i].chain = 0; a[i].index = (int32_t)i; }
    start = clock();
    BRRecoveryScanUsedBegin(&u, a, D, s, D);
    for (i = 0; i < U; i++) BRRecoveryScanUsedMarkAddress(&u, t);
    secs = (double)(clock() - start) / CLOCKS_PER_SEC;
    ck(BRRecoveryScanUsedHighest(&u, 0) == (int32_t)(D - 1), "every copy used");
    ck(secs < BIG_BOUND_SECONDS, "in %.3f s (bound %.1f s)", secs, BIG_BOUND_SECONDS);
    free(s); free(a);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "pv_funds_is_success", pv_funds_is_success },
    { "pv_typo", pv_typo },
    { "pv_none_anywhere", pv_none_anywhere },
    { "pv_incomplete_cannot_claim_nothing", pv_incomplete_cannot_claim_nothing },
    { "pv_positive_survives_incomplete", pv_positive_survives_incomplete },
    { "pv_no_comparison_no_typo", pv_no_comparison_no_typo },
    { "pv_found_needs_no_comparison", pv_found_needs_no_comparison },
    { "pv_negative_totals_are_not_funds", pv_negative_totals_are_not_funds },
    { "partial_one_failed_among_several", partial_one_failed_among_several },
    { "partial_clean_empty_not_partial", partial_clean_empty_not_partial },
    { "partial_total_outage_is_both", partial_total_outage_is_both },
    { "partial_no_addresses_not_unreachable", partial_no_addresses_not_unreachable },
    { "nothing_derived_is_incomplete", nothing_derived_is_incomplete },
    { "partial_failure_with_funds_still_matters", partial_failure_with_funds_still_matters },
    { "partial_nothing_asked_is_all_unreachable", partial_nothing_asked_is_all_unreachable },
    { "cache_only_when_all_reachable", cache_only_when_all_reachable },
    { "incomplete_from_capped_or_unreachable", incomplete_from_capped_or_unreachable },
    { "rv_dollars_alone", rv_dollars_alone },
    { "rv_nothing", rv_nothing },
    { "rv_no_dd_scan_nothing", rv_no_dd_scan_nothing },
    { "rv_utxos_alone", rv_utxos_alone },
    { "rv_unreachable_dd_not_answered", rv_unreachable_dd_not_answered },
    { "rv_reachable_zero_answered", rv_reachable_zero_answered },
    { "rv_failed_dd_scan_is_unanswered", rv_failed_dd_scan_is_unanswered },
    { "sweep_foreign_includes_native_own_excludes", sweep_foreign_includes_native_own_excludes },
    { "sweep_unfunded_not_swept", sweep_unfunded_not_swept },
    { "sweep_and_total_arguments", sweep_and_total_arguments },
    { "widen_coin_past_first_window", widen_coin_past_first_window },
    { "widen_empty_path_asked_once", widen_empty_path_asked_once },
    { "widen_capped_marked_incomplete", widen_capped_marked_incomplete },
    { "widen_unknown_outcome_capped", widen_unknown_outcome_capped },
    { "widen_paid_but_emptied_counts", widen_paid_but_emptied_counts },
    { "widen_round_bound_and_numbering", widen_round_bound_and_numbering },
    { "widen_unreachable_kept_unreachable", widen_unreachable_kept_unreachable },
    { "widen_nothing_derived_is_incomplete", widen_nothing_derived_is_incomplete },
    { "widen_used_begin_failure_is_unreachable", widen_used_begin_failure_is_unreachable },
    { "widen_later_round_unreachable_replaces_rows", widen_later_round_unreachable_replaces_rows },
    { "round_bound_from_profiles", round_bound_from_profiles },
    { "used_highest_per_chain", used_highest_per_chain },
    { "used_coin_matching", used_coin_matching },
    { "used_script_matching", used_script_matching },
    { "used_script_kinds", used_script_kinds },
    { "used_unparseable_tx_skipped", used_unparseable_tx_skipped },
    { "used_large_inputs", used_large_inputs },
    { "used_one_address_many_times", used_one_address_many_times },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]), i;
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: RECOVERY_SCAN_UNFIXED=%d (%s)\n", RECOVERY_SCAN_UNFIXED, RECOVERY_SCAN_UNFIXED ? "comparison" : "shipped");
        kCases[i].run();
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
