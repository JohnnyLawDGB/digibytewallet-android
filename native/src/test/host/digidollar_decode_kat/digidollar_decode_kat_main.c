// Host KAT for the DigiDollar SHOW decoder module skeleton (DD-Show Task 1,
// .superpowers/sdd/task-1-brief.md).
//
// This first task implements only BRDigiDollarTxType (the tx-version
// classifier). BRDigiDollarDecodeAmounts / BRDigiDollarOutputAmount are
// stubbed to return -1 and are exercised in Tasks 2-3's extensions to this
// same harness.
//
// Marker rule (spec docs/superpowers/specs/2026-07-04-digidollar-wire-format.md
// §1): a tx is DigiDollar iff (tx->version & 0xFFFF) == 0x0770. Type is the
// top byte of version: 1=MINT, 2=TRANSFER, 3=REDEEM; anything else with the
// marker present is NOT a valid DD tx (fails closed).
//
// Same real-file compile approach as the taproot host KATs (see e.g.
// bip341_signtx_kat/run.sh): compiles the REAL, live submodule
// BRDigiDollar.c directly out of the tree, plus BRTransaction.c (for
// BRTransactionNew/BRTransactionFree) and its full transitive dependency
// chain so every symbol referenced by those translation units resolves at
// link time.
//
// Exit code 0 = all checks passed, 1 = at least one check failed (or build
// error, which is expected before BRDigiDollar.h/.c exist).

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRTransaction.h"
#include "BRDigiDollar.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) {
        printf("PASS: %s\n", desc);
    } else {
        printf("FAIL: %s\n", desc);
        g_failures++;
    }
}

int main(void)
{
    // TRANSFER: version 0x02000770 -> type 2
    BRTransaction *t2 = BRTransactionNew();
    t2->version = 0x02000770;
    check(BRDigiDollarTxType(t2) == 2, "version 0x02000770 -> TRANSFER(2)");

    // MINT 0x01000770 -> 1 ; REDEEM 0x03000770 -> 3
    BRTransaction *t1 = BRTransactionNew();
    t1->version = 0x01000770;
    check(BRDigiDollarTxType(t1) == 1, "version 0x01000770 -> MINT(1)");

    BRTransaction *t3 = BRTransactionNew();
    t3->version = 0x03000770;
    check(BRDigiDollarTxType(t3) == 3, "version 0x03000770 -> REDEEM(3)");

    // Non-DD: standard v1/v2, and a marker-collision with an invalid type
    // (0x04000770 -> type 4, not in {1,2,3})
    BRTransaction *n1 = BRTransactionNew();
    n1->version = 1;
    check(BRDigiDollarTxType(n1) == 0, "version 1 -> not DD");

    BRTransaction *n2 = BRTransactionNew();
    n2->version = 2;
    check(BRDigiDollarTxType(n2) == 0, "version 2 -> not DD");

    BRTransaction *n3 = BRTransactionNew();
    n3->version = 0x04000770; // marker present, type 4 invalid
    check(BRDigiDollarTxType(n3) == 0, "marker+invalid type 4 -> not DD");

    // NULL tx must fail closed, not crash
    check(BRDigiDollarTxType(NULL) == 0, "NULL tx -> not DD (no crash)");

    BRTransactionFree(t2);
    BRTransactionFree(t1);
    BRTransactionFree(t3);
    BRTransactionFree(n1);
    BRTransactionFree(n2);
    BRTransactionFree(n3);

    // --- Task 2: BRDigiDollarDecodeAmounts (OP_RETURN "DD" push-walker + minimal
    // CScriptNum amount decode). Vectors from spec §7. ---

    // $50 one-recipient transfer: OP_RETURN = 6a 02 44 44 01 02 02 88 13  -> [5000]
    uint8_t or1[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    BRTransaction *a = BRTransactionNew(); a->version = 0x02000770;
    BRTransactionAddOutput(a, 0, or1, sizeof(or1));
    int64_t amt[8]; int n = BRDigiDollarDecodeAmounts(a, amt, 8);
    check(n == 1 && amt[0] == 5000, "transfer OP_RETURN -> [5000]");

    // $50/$25 two-recipient: ... 02 88 13 02 c4 09 -> [5000,2500]
    uint8_t or2[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09};
    BRTransaction *b = BRTransactionNew(); b->version = 0x02000770;
    BRTransactionAddOutput(b, 0, or2, sizeof(or2));
    n = BRDigiDollarDecodeAmounts(b, amt, 8);
    check(n == 2 && amt[0] == 5000 && amt[1] == 2500, "transfer -> [5000,2500]");

    // $50/$25 + $3 change: ... 02 2c 01 -> [5000,2500,300]
    uint8_t or3[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09,0x02,0x2c,0x01};
    BRTransaction *c = BRTransactionNew(); c->version = 0x02000770;
    BRTransactionAddOutput(c, 0, or3, sizeof(or3));
    n = BRDigiDollarDecodeAmounts(c, amt, 8);
    check(n == 3 && amt[0]==5000 && amt[1]==2500 && amt[2]==300, "transfer -> [5000,2500,300]");

    // MINT: first push only. mint OP_RETURN 6a 02 4444 01 01 02 88 13 (type 1, amount 5000) -> [5000], n==1
    uint8_t orm[] = {0x6a,0x02,0x44,0x44,0x01,0x01,0x02,0x88,0x13};
    BRTransaction *m = BRTransactionNew(); m->version = 0x01000770;
    BRTransactionAddOutput(m, 0, orm, sizeof(orm));
    n = BRDigiDollarDecodeAmounts(m, amt, 8);
    check(n == 1 && amt[0] == 5000, "mint -> first push only [5000]");

    // Negatives:
    BRTransaction *nd = BRTransactionNew(); nd->version = 1;         // not DD
    BRTransactionAddOutput(nd, 0, or1, sizeof(or1));
    check(BRDigiDollarDecodeAmounts(nd, amt, 8) == -1, "non-DD tx -> -1");

    // DD marker but no "DD" OP_RETURN present -> -1
    BRTransaction *no = BRTransactionNew(); no->version = 0x02000770;
    uint8_t p2wpkh[] = {0x00,0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20};
    BRTransactionAddOutput(no, 0, p2wpkh, sizeof(p2wpkh));
    check(BRDigiDollarDecodeAmounts(no, amt, 8) == -1, "DD marker w/o DD OP_RETURN -> -1");

    // Non-minimal amount push (0x8813 padded to 88 13 00 -> non-minimal) -> -1 (fail closed)
    uint8_t orbad[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x03,0x88,0x13,0x00};
    BRTransaction *bad = BRTransactionNew(); bad->version = 0x02000770;
    BRTransactionAddOutput(bad, 0, orbad, sizeof(orbad));
    check(BRDigiDollarDecodeAmounts(bad, amt, 8) == -1, "non-minimal amount push -> -1");

    // maxAmounts overflow: 3 amounts into a size-2 buffer -> -1
    check(BRDigiDollarDecodeAmounts(c, amt, 2) == -1, "amount count > maxAmounts -> -1");

    BRTransactionFree(a);
    BRTransactionFree(b);
    BRTransactionFree(c);
    BRTransactionFree(m);
    BRTransactionFree(nd);
    BRTransactionFree(no);
    BRTransactionFree(bad);

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
