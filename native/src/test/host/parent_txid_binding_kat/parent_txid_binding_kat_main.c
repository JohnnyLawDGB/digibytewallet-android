// Host KAT: the id a parent transaction is checked against, and the inputs of an asset send.
//
// The asset paths fetch a parent transaction by its id and accept the bytes only when they
// are that transaction. The id is computed by raw_tx_id (bridge/asset_tx_checks.h), which the
// JNI accessor NativeBridge.rawTransactionId calls; this suite compiles that same header
// against the live core parser and pins its answers.
//
// WHICH ID. A txid is the hash of the serialization WITHOUT witness data. For a transaction
// with a witness that differs from the hash of the bytes as fetched, so comparing the wrong
// form would refuse every parent that has a witness. The first case is a real DigiByte
// testnet26 transaction with a witness (block 83946, the same bytes digidollar_realtx_kat
// decodes); its id is the one the node reported for it. The suite also shows the case can
// tell the two forms apart: the full-serialization hash of those bytes is a different value.
//
// The other known ids were computed independently of the core (double SHA-256 over the
// serialization with the marker, flag and witness removed) for the signed fixtures that
// sign_regression_kat pins: one input with no witness, one P2WPKH, one P2TR key path, and
// one with a legacy input beside a witness input.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "BRInt.h"
#include "BRTransaction.h"
#include "asset_tx_checks.h"

static int failures = 0;

static void check(int cond, const char *what)
{
    printf(cond ? "  ok   %s\n" : "  FAIL %s\n", what);
    if (!cond) failures++;
}

static uint8_t *from_hex(const char *hex, size_t *len)
{
    size_t n = strlen(hex) / 2;
    uint8_t *b = malloc(n + 1);   /* +1: room for the appended-byte case */
    for (size_t i = 0; i < n; i++) {
        unsigned x;
        sscanf(hex + 2 * i, "%2x", &x);
        b[i] = (uint8_t)x;
    }
    *len = n;
    return b;
}

/* Display-order hex of an id held in internal order. */
static void display_hex(UInt256 h, char out[65])
{
    for (int k = 0; k < 32; k++) sprintf(out + 2 * k, "%02x", h.u8[31 - k]);
    out[64] = '\0';
}

/* raw_tx_id reports an id for these bytes and it is `want` (display order). */
static void check_id(const char *name, const uint8_t *b, size_t n, const char *want)
{
    UInt256 id = UINT256_ZERO;
    char got[65] = "";
    char what[160];
    int ok = raw_tx_id(b, n, &id);
    if (ok) display_hex(id, got);
    snprintf(what, sizeof(what), "%s: id %s", name, want);
    check(ok && strcmp(got, want) == 0, what);
    if (!(ok && strcmp(got, want) == 0)) printf("         got ok=%d id=%s\n", ok, got);
}

static void check_no_id(const char *what, const uint8_t *b, size_t n)
{
    UInt256 id = UINT256_ZERO;
    check(raw_tx_id(b, n, &id) == 0, what);
}

/* ---- fixtures ------------------------------------------------------------------------- */

/* DigiByte testnet26 block 83946. A two-input P2TR key-path spend with a witness. */
static const char *REAL_WITNESS_TX =
    "70070002000102c831f28e22826503174ad58ad774e8210b4148c09879c48a68280a308a3c61"
    "ea0100000000fffffffff18057367c04eacb4f4cc762e9a1808d3732e473cdac1621cef3447b"
    "1055dca50000000000ffffffff040000000000000000225120effc01464e19f45d27d3fd670e"
    "62f4b21a441e894bc9cd1726b99ca8c02000cd000000000000000022512055e1ba29a8289e01"
    "ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300882a110000000022512055e1ba"
    "29a8289e01ba58d4101fa8985650f4d2d2f20193a6bd5da99d7d3064a300000000000000000c"
    "6a024444010202c409024c1d0140254bd6882c3308f50d10f435c71df63bdad5c44bb56168a2"
    "ca031b5d45cf742114b53d4c51fac01cf29a3c5567861cd2ef85395b0188c0b0b438af9a813c"
    "281b0140d74bfc3623042619dc236ed0144b69c0484410175f02bd9592573428e4b483024215"
    "e194215f43ba7aefcffb54832bfde53cd44e0ff9af802bb8029922df228400000000";
static const char *REAL_WITNESS_TXID =
    "3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346";

static const char *P2PKH_TX =
    "0100000001111111111111111111111111111111111111111111111111111111111111111100"
    "0000006a4730440220635af8fdba2e71c9bdb553650c9dee10199051ce3e0a4e4c03fd0d971a"
    "36e5c702202b4ba433809c644eb323c09db4d6ccb56987d6a435cf9793ac045d76d9e72fe701"
    "2102479cde2b0481402582375fa9803db7141b29caa0aac60f3ecc329cff4a1f6c43ffffffff"
    "01c09ee605000000001976a914dcd5e7759e887ae4198162935816610ecc05debb88ac000000"
    "00";
static const char *P2PKH_TXID =
    "eccaf4f831abb2749a0ab82b62ec68f9d9bb7165a803cde52a0b93702e08c4b6";

static const char *P2WPKH_TX =
    "0100000000010122222222222222222222222222222222222222222222222222222222222222"
    "220100000000ffffffff01c09ee605000000001976a914dcd5e7759e887ae419816293581661"
    "0ecc05debb88ac024730440220165de492ce4cea402879b755cdc04740efd1d29dfa0892b7dd"
    "6649f3355134ab02207ae3f435fc43a188b1e863c503f02a6bc26c3e30204a5d85f8b45bbdab"
    "4636fc012102c8d63f5ca5e50398fbc1d13d870c61fa7d329a193e79f374a692d2a4059c2f09"
    "00000000";
static const char *P2WPKH_TXID =
    "3a7d6bde5e15369c1766c849e38a4f0055b96bb2b9bbddbfdc136e6e5087088c";

static const char *P2TR_TX =
    "0100000000010133333333333333333333333333333333333333333333333333333333333333"
    "330200000000ffffffff01c09ee605000000001976a914dcd5e7759e887ae419816293581661"
    "0ecc05debb88ac01404bd91df0673ac20d23c2cdd5c64c0c1dbab9642af914dc49a1584e3b61"
    "c2dc6df4e9ef51d8e76522dc5f95d4f7ac761d21ed43721cf3661e0d79f4343bcc78fa000000"
    "00";
static const char *P2TR_TXID =
    "bf377e73aa38efa3ee58752eb86c8cab703b4a55b4d71ad31925fc827a52a552";

static const char *MIXED_TX =
    "0100000000010244444444444444444444444444444444444444444444444444444444444444"
    "44000000006a47304402202afbdb6ef5029a60265bb816002f4121ae87af2f13b0e74d6c8230"
    "3df77730780220528c54414fef8d42d455ccda11d5f0f3fe9f914ac1f6277952b88c5ad96907"
    "c7012102479cde2b0481402582375fa9803db7141b29caa0aac60f3ecc329cff4a1f6c43ffff"
    "ffff555555555555555555555555555555555555555555555555555555555555555501000000"
    "00ffffffff01c09ee605000000001976a914dcd5e7759e887ae4198162935816610ecc05debb"
    "88ac000247304402204ffdd9df0e3efc9b9ad341484d26207fe844cf7a971399606ff9356ebe"
    "80b944022017c478413a0bab723d8c8557f3cdad618f86d4f8ce1767ca42938d90438c886201"
    "2102c8d63f5ca5e50398fbc1d13d870c61fa7d329a193e79f374a692d2a4059c2f0900000000";
static const char *MIXED_TXID =
    "88d98b04f20fc1a8878f218c244eb0b605147728a7489b390e329197af65e34f";

/* ---- the id --------------------------------------------------------------------------- */

static void ids(void)
{
    size_t n;
    uint8_t *b;
    char full[65];

    printf("the id is the one the network names the transaction by\n");

    b = from_hex(REAL_WITNESS_TX, &n);
    check_id("real testnet26 transaction with a witness", b, n, REAL_WITNESS_TXID);
    {
        /* The case discriminates: the hash of the bytes as fetched is another value. */
        BRTransaction *tx = BRTransactionParse(b, n);
        check(tx != NULL, "real testnet26 transaction parses");
        if (tx) {
            display_hex(tx->wtxHash, full);
            check(strcmp(full, REAL_WITNESS_TXID) != 0,
                  "its full-serialization hash is NOT its id (the case tells the forms apart)");
            BRTransactionFree(tx);
        }
    }
    {
        /* A change inside the witness leaves the id where it was: the id does not cover it. */
        b[n - 10] ^= 0x01;
        check_id("same transaction, one witness byte changed", b, n, REAL_WITNESS_TXID);
        b[n - 10] ^= 0x01;
    }
    free(b);

    b = from_hex(P2PKH_TX, &n);
    check_id("signed, no witness", b, n, P2PKH_TXID);
    free(b);

    b = from_hex(P2WPKH_TX, &n);
    check_id("signed P2WPKH spend", b, n, P2WPKH_TXID);
    free(b);

    b = from_hex(P2TR_TX, &n);
    check_id("signed P2TR key-path spend", b, n, P2TR_TXID);
    free(b);

    b = from_hex(MIXED_TX, &n);
    check_id("legacy input beside a witness input", b, n, MIXED_TXID);
    free(b);
}

/* ---- bytes that are not exactly one transaction --------------------------------------- */

static void refusals(void)
{
    size_t n;
    uint8_t *b;
    UInt256 id = UINT256_ZERO;
    char got[65];

    printf("an id is reported only for exactly one complete signed transaction\n");

    check(raw_tx_id(NULL, 0, &id) == 0, "no bytes: no id");

    b = from_hex(REAL_WITNESS_TX, &n);
    b[n] = 0x00;
    check_no_id("one byte appended after a transaction with a witness: no id", b, n + 1);
    check_no_id("last byte missing: no id", b, n - 1);
    check_no_id("first half only: no id", b, n / 2);
    free(b);

    b = from_hex(P2PKH_TX, &n);
    b[n] = 0x00;
    check_no_id("one byte appended after a transaction with no witness: no id", b, n + 1);
    check_no_id("last byte missing: no id", b, n - 1);

    /* The same transaction with one output amount changed is a different transaction:
     * it has an id, and it is not the id it was requested by. */
    {
        static const uint8_t amount[] = { 0xc0, 0x9e, 0xe6, 0x05 };
        size_t at = 0;
        for (size_t i = 0; i + sizeof(amount) <= n; i++) {
            if (memcmp(b + i, amount, sizeof(amount)) == 0) { at = i; break; }
        }
        check(at != 0, "found the output amount in the fixture");
        b[at] ^= 0x01;
        int ok = raw_tx_id(b, n, &id);
        if (ok) display_hex(id, got);
        check(ok && strcmp(got, P2PKH_TXID) != 0,
              "one output amount changed: another id, not the requested one");
    }
    free(b);
}

/* ---- inputs of an asset send ---------------------------------------------------------- */

static UInt256 hash_of(uint8_t fill)
{
    UInt256 h;
    memset(h.u8, fill, sizeof(h.u8));
    return h;
}

static BRTransaction *with_inputs(const UInt256 *hashes, const uint32_t *indexes, size_t count)
{
    static const uint8_t script[] = {
        0x76, 0xa9, 0x14, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        0x88, 0xac
    };
    BRTransaction *tx = BRTransactionNew();
    for (size_t i = 0; i < count; i++) {
        BRTransactionAddInput(tx, hashes[i], indexes[i], 6000, script, sizeof(script),
                              NULL, 0, NULL, 0, TXIN_SEQUENCE);
    }
    return tx;
}

static void inputs(void)
{
    printf("an asset send never lists an outpoint twice\n");

    UInt256 a = hash_of(0xaa), d = hash_of(0xdd);

    {
        UInt256 h[] = { a };
        uint32_t i[] = { 1 };
        BRTransaction *tx = with_inputs(h, i, 1);
        check(tx_inputs_distinct(tx) == 1, "one input: distinct");
        BRTransactionFree(tx);
    }
    {
        UInt256 h[] = { a, d, a };
        uint32_t i[] = { 1, 0, 2 };
        BRTransaction *tx = with_inputs(h, i, 3);
        check(tx_inputs_distinct(tx) == 1, "same txid, different output index: distinct");
        BRTransactionFree(tx);
    }
    {
        UInt256 h[] = { a, d, a };
        uint32_t i[] = { 1, 0, 1 };
        BRTransaction *tx = with_inputs(h, i, 3);
        check(tx_inputs_distinct(tx) == 0, "an asset input listed again among the fee inputs: refused");
        BRTransactionFree(tx);
    }
    {
        UInt256 h[] = { d, d };
        uint32_t i[] = { 4, 4 };
        BRTransaction *tx = with_inputs(h, i, 2);
        check(tx_inputs_distinct(tx) == 0, "two adjacent copies of one outpoint: refused");
        BRTransactionFree(tx);
    }
    check(tx_inputs_distinct(NULL) == 0, "no transaction: not distinct");
}

int main(void)
{
    printf("parent_txid_binding_kat\n");
    ids();
    refusals();
    inputs();
    printf(failures == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", failures);
    return failures ? 1 : 0;
}
