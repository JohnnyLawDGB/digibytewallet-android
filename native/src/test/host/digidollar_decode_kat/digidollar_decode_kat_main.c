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

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
