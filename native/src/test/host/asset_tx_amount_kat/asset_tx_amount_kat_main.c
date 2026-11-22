// Host KAT for BRAssetTxAmount.h: what one DigiAsset transaction moved, for its activity row and details
// (BRAssetTxActivityAmount). Moved from Kotlin v4.1.4 core/.../asset/AssetManager.kt (assetTokenCountForTx
// 2886-2922, receivedBackedUnits 2925-2928, the count and id of assetAmountForTx 2936-2950) and
// core/.../db/dao/UtxoDao.kt (getResolvedAssetIdForTx 62-63, getAssetUtxosForTxNow 116-117).
//
// THE EXPECTATIONS ARE WRITTEN HERE BY HAND, from the Kotlin, never computed by the header:
//   - receive: receivedBackedUnits over the rows getAssetUtxosForTxNow returns (is_asset = 1 AND txid =
//     :txid, spent or not): AssetSums.saturating of the quantities of the rows AssetCredit.counts (BACKED,
//     VERIFIED); null unless above zero. The rows are the end states UnbackedAssetCreditTest and
//     AssetArithmeticNeverWrapsTest assert after their receives: a forged credit from unknown inputs stays
//     UNCHECKED at its claim and then settles VERIFIED at 0 (every_forged_vector_from_unknown_inputs...:
//     receivedBackedUnits null both times); from known inputs BACKED at 0 (null); the honest control
//     VERIFIED 5 (the_honest_control_from_unknown_inputs...: 5) and BACKED 5 + 4 from known inputs;
//     the issuance whose output 0 is BACKED 0 (an_issuance_credits_only_where...: null); a range whose
//     consumption is past the range, settled at 0 (a_range_consumption_past...: null).
//   - send: the units AssetTxQuantity.forOutput gives every output that is not OP_RETURN and whose script
//     is not in the owned set (`owned.isNotEmpty() && hex in owned`), AssetSums.add over them; null when
//     not above zero, when the transaction has no carrier or an unclassifiable one.
//   - the asset: of the rows with is_asset = 1, txid = :txid, asset_id NOT LIKE 'unresolved:%' (NULL is
//     not, and LIKE ignores ASCII case) and asset_credit IN ('BACKED', 'VERIFIED'), the one with the lowest
//     vout: the query has no ORDER BY, and SQLite reads txid = :txid through the table's only index, the
//     (txid, vout) primary key (UtxoEntity; EXPLAIN QUERY PLAN: USING INDEX sqlite_autoindex_utxos_1).
// Each vector's inputs are in the core's vectors/asset-tx-amount/corpus.txt (read by its read.h); the
// expectations below are this file's. Case `vectors_file` holds the core's vectors.txt to them too.
//
// MACRO CONVENTION: VALUE of -DASSET_TX_AMOUNT_UNFIXED (a set of bits, =0 shipped); see run.sh.

#include <stdio.h>
#include "vectors/asset-tx-amount/read.h"

#define X "La" "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
#define Y "La" "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"
#define I64MAX INT64_C(9223372036854775807)

typedef struct {
    const char *name;
    BRAssetTxAmountStatus status;
    int64_t units;
    const char *assetId;   // NULL: none
} Want;

#define OK_ BR_ASSET_TX_AMOUNT_OK
#define NONE_ BR_ASSET_TX_AMOUNT_NONE

static const Want WANT[] = {
    // receive
    { "recv_forged_unchecked",            NONE_, 0, NULL },          // UNCHECKED at its 1,000,000 claim: not counted
    { "recv_forged_settled_zero",         NONE_, 0, NULL },          // VERIFIED at 0 after the indexer
    { "recv_honest_verified",             OK_,   5, X },             // the honest control, indexer-confirmed: 5
    { "recv_honest_backed_two",           OK_,   9, X },             // 5 at output 0 + 4 at output 2, both BACKED
    { "recv_known_forged_zero",           NONE_, 0, NULL },          // known inputs: BACKED at 0
    { "recv_issuance_output0",            NONE_, 0, NULL },          // output 0 of the issuance: BACKED 0
    { "recv_range_settled_zero",          NONE_, 0, NULL },          // the range past the signed range, settled 0
    { "recv_spent_counts",                OK_,   5, X },             // getAssetUtxosForTxNow: spent and unspent
    { "recv_sums_saturate",               OK_,   I64MAX, X },        // INT64_MAX + 1 stops at INT64_MAX
    { "recv_unsettled_uncounted",         NONE_, 0, NULL },          // MIXED and UNCHECKED do not count
    { "recv_other_txid",                  OK_,   2, Y },             // another transaction's row is not read
    { "recv_not_asset_row",               NONE_, 0, NULL },          // is_asset = 0 is not read
    { "recv_id_skips_placeholder",        OK_,   5, X },             // 3 + 2; the placeholder names nothing
    { "recv_id_skips_upper_placeholder",  OK_,   4, X },             // LIKE ignores case: "UNRESOLVED:" too
    { "recv_id_skips_uncounted",          OK_,   1, X },             // the UNCHECKED row's Y is not the asset
    { "recv_id_placeholder_only",         OK_,   3, NULL },          // an amount, no asset
    { "recv_id_absent",                   OK_,   2, NULL },          // a NULL asset_id is not NOT LIKE anything
    { "recv_no_rows",                     NONE_, 0, NULL },          // the carrier's claim is never read
    { "recv_id_lowest_vout",              OK_,   3, X },             // given vout 3 (Y), then vout 1 (X): the lower vout names
    // send
    { "send_to_recipient",                OK_,   5, X },             // 5 to the recipient; the 3 of change not counted
    { "send_owned_empty",                 OK_,   8, X },             // no owned set: every output is someone else's
    { "send_to_self",                     NONE_, 0, NULL },          // every output the wallet's own
    { "send_data_output_named",           OK_,   3, NULL },          // the 5 to the OP_RETURN is skipped
    { "send_burn",                        OK_,   7, NULL },          // a burn's other instruction delivers; 31 destroys
    { "send_issuance",                    OK_,   1000000, NULL },    // forOutput: the whole issue on the first non-OP_RETURN
    { "send_range_saturates",             OK_,   I64MAX, NULL },     // 8e18 to each of outputs 0 and 1: stops at INT64_MAX
    { "send_uncountable",                 NONE_, 0, NULL },          // an amount past the range: UNCLASSIFIABLE
    { "send_no_carrier",                  NONE_, 0, NULL },
    { "send_undecided",                   NONE_, 0, NULL },          // two carriers: undecided
    { "send_no_transaction",              NONE_, 0, NULL },          // getTransactionOutputsForHash null
    { "send_unresolved_change",           OK_,   5, NULL },          // the change row is UNCHECKED and a placeholder
};
#define NWANT (sizeof(WANT) / sizeof(WANT[0]))

static int fails = 0, passes = 0;

static void check(int ok, const char *what)
{
    if (ok) { passes++; printf("PASS: %s\n", what); }
    else { fails++; printf("FAIL: %s\n", what); }
}

static int same(const BRAssetTxAmount *a, const Want *w)
{
    if (a->status != w->status || a->units != w->units) return 0;
    if (! w->assetId) return a->assetId == NULL;
    return a->assetId && strcmp(a->assetId, w->assetId) == 0;
}

static const char *corpusPath(void) { const char *p = getenv("ASSET_TX_AMOUNT_CORPUS"); return p ? p : "corpus.txt"; }
static const char *vectorsPath(void) { const char *p = getenv("ASSET_TX_AMOUNT_VECTORS"); return p ? p : "vectors.txt"; }

// Runs the corpus line named `name` and compares it with its hand-written expectation.
static void vectorCase(const Want *w)
{
    char line[2048], what[256];
    FILE *f = fopen(corpusPath(), "r");
    int found = 0;

    if (! f) { check(0, "the corpus opens"); return; }
    while (fgets(line, sizeof(line), f)) {
        TxaCase c;
        char head[96];

        snprintf(head, sizeof(head), "amount %s ", w->name);
        if (strncmp(line, head, strlen(head)) != 0) continue;
        found = 1;
        if (! txaRead(line, &c)) { check(0, "the corpus line reads"); break; }
        {
            BRAssetTxAmount a = txaRun(&c);
            snprintf(what, sizeof(what), "%s: %s %" PRId64 " %s (want %s %" PRId64 " %s)", w->name, txaStatusName(a.status),
                     a.units, a.assetId ? a.assetId : "-", txaStatusName(w->status), w->units, w->assetId ? w->assetId : "-");
            check(same(&a, w), what);
            // the id is a row's own pointer
            if (a.assetId) {
                size_t i;
                int ours = 0;
                for (i = 0; i < c.rowCount; i++) if (c.rows[i].assetId == a.assetId) ours = 1;
                check(ours, "the asset id points into the caller's rows");
            }
        }
        txaFree(&c);
        break;
    }
    fclose(f);
    if (! found) check(0, "the vector is in the corpus");
}

// Every line of the core's vectors.txt carries the hand-written answer, and the header gives it.
static void vectorsFile(void)
{
    char line[2048], what[256];
    FILE *f = fopen(vectorsPath(), "r");
    size_t seen = 0, i;
    int matched[NWANT];

    memset(matched, 0, sizeof(matched));
    if (! f) { check(0, "vectors.txt opens"); return; }
    while (fgets(line, sizeof(line), f)) {
        TxaCase c;
        const Want *w = NULL;

        if (line[0] == '#' || line[0] == '\n') continue;
        if (! txaRead(line, &c) || ! c.hasAnswer) { check(0, "a vectors.txt line reads"); continue; }
        for (i = 0; i < NWANT; i++) if (strcmp(WANT[i].name, c.name) == 0) { w = &WANT[i]; matched[i] = 1; }
        snprintf(what, sizeof(what), "vectors.txt %s is a hand-written case", c.name);
        check(w != NULL, what);
        if (w) {
            BRAssetTxAmount fileAnswer = { c.status, c.units, c.assetId[0] ? c.assetId : NULL };
            BRAssetTxAmount a = txaRun(&c);

            snprintf(what, sizeof(what), "vectors.txt %s says the hand-written answer", c.name);
            check(same(&fileAnswer, w), what);
            snprintf(what, sizeof(what), "vectors.txt %s: the header gives it", c.name);
            check(same(&a, w), what);
        }
        txaFree(&c);
        seen++;
    }
    fclose(f);
    for (i = 0; i < NWANT; i++) {
        snprintf(what, sizeof(what), "vectors.txt has %s", WANT[i].name);
        check(matched[i], what);
    }
    check(seen == NWANT, "vectors.txt has as many lines as there are hand-written cases");
}

static void arguments(void)
{
    BRAssetRow row;
    BRAssetTxAmount a, zero;
    BRTransaction *tx = BRTransactionNew();
    static const uint8_t data[] = { 0x6a, 0x06, 0x44, 0x41, 0x03, 0x15, 0x00, 0x05 }, pay[] = { 0x51 };
    UInt256 prev;
    size_t i;

    memset(&row, 0, sizeof(row));
    memset(&zero, 0, sizeof(zero));
    row.txid = TXA_TXID;
    row.isAsset = 1;
    row.assetId = X;
    row.assetQuantity = 5;
    row.credit = BR_ASSET_CREDIT_BACKED;

    check(zero.status == BR_ASSET_TX_AMOUNT_REFUSED, "a struct nobody filled is REFUSED, never an amount");
    a = BRAssetTxActivityAmount(NULL, 0, &row, 1, NULL, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_REFUSED && a.units == 0 && ! a.assetId, "a NULL txid is refused");
    a = BRAssetTxActivityAmount(TXA_TXID, 0, NULL, 1, NULL, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_REFUSED, "rows NULL with a count is refused");
    a = BRAssetTxActivityAmount(TXA_TXID, 0, NULL, 0, NULL, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_NONE, "no rows is no amount");
    a = BRAssetTxActivityAmount(TXA_TXID, 0, &row, 1, NULL, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_OK && a.units == 5 && a.assetId == row.assetId, "a receive needs no transaction");

    memset(&prev, 0, sizeof(prev));
    prev.u8[0] = 1;
    BRTransactionAddInput(tx, prev, 0, 0, NULL, 0, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    BRTransactionAddOutput(tx, 600, pay, sizeof(pay));
    BRTransactionAddOutput(tx, 0, data, sizeof(data));
    for (i = 0; i < 32; i++) tx->txHash.u8[i] = 0xdd;   // TXA_OTHER's hash
    a = BRAssetTxActivityAmount(TXA_TXID, 1, &row, 1, tx, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_REFUSED, "a send's transaction that is not the txid's is refused");
    for (i = 0; i < 32; i++) tx->txHash.u8[i] = 0xcc;
    a = BRAssetTxActivityAmount(TXA_TXID, 1, &row, 1, tx, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_OK && a.units == 5 && a.assetId == row.assetId, "the same send, its own transaction: 5");
    a = BRAssetTxActivityAmount("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", 1, &row, 1, tx, NULL, 0);
    check(a.status == BR_ASSET_TX_AMOUNT_OK && a.units == 5 && a.assetId == NULL,
          "an upper-case txid reads the transaction, and the rows by their stored txid, byte for byte");
    BRTransactionFree(tx);
}

int asset_tx_amount_kat_tu2(void);

int main(int argc, char **argv)
{
    size_t i;
    const char *c = argc > 1 ? argv[1] : "";

    if (strcmp(c, "list") == 0) {
        for (i = 0; i < NWANT; i++) printf("%s\n", WANT[i].name);
        printf("vectors_file\narguments\nsecond_unit\n");
        return 0;
    }
    for (i = 0; i < NWANT; i++) if (strcmp(c, WANT[i].name) == 0) vectorCase(&WANT[i]);
    if (strcmp(c, "vectors_file") == 0) vectorsFile();
    else if (strcmp(c, "arguments") == 0) arguments();
    else if (strcmp(c, "second_unit") == 0) check(asset_tx_amount_kat_tu2() == 5, "the header links from a second unit");
    if (passes + fails == 0) { printf("unknown case %s\n", c); return 2; }
    printf("RESULT %s %s\n", c, fails ? "fail" : "pass");
    return fails ? 1 : 0;
}
