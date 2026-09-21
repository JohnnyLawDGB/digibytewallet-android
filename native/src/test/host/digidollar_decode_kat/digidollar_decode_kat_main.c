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
#include <string.h>

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

    // --- Task 3: BRDigiDollarOutputAmount (positional amount<->output binding).
    // Vectors from .superpowers/sdd/task-3-brief.md. ---

    uint8_t p2tr_a[34]; p2tr_a[0]=0x51; p2tr_a[1]=0x20; memset(p2tr_a+2,0xAA,32);
    uint8_t p2tr_b[34]; p2tr_b[0]=0x51; p2tr_b[1]=0x20; memset(p2tr_b+2,0xBB,32);
    uint8_t p2wpkh2[22]={0x00,0x14,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20};
    uint8_t orr[]={0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09}; // [5000,2500]

    BRTransaction *t = BRTransactionNew(); t->version = 0x02000770;
    BRTransactionAddOutput(t, 0, p2tr_a, 34);        // vout 0 -> DD ordinal 0 -> 5000
    BRTransactionAddOutput(t, 0, p2tr_b, 34);        // vout 1 -> DD ordinal 1 -> 2500
    BRTransactionAddOutput(t, 123456, p2wpkh2, 22);   // vout 2 -> nonzero -> not DD (-1)
    BRTransactionAddOutput(t, 0, orr, sizeof(orr));  // vout 3 -> OP_RETURN -> not DD (-1)

    check(BRDigiDollarOutputAmount(t, 0) == 5000, "vout0 DD ordinal 0 -> 5000");
    check(BRDigiDollarOutputAmount(t, 1) == 2500, "vout1 DD ordinal 1 -> 2500");
    check(BRDigiDollarOutputAmount(t, 2) == -1,   "vout2 nonzero-value -> not DD");
    check(BRDigiDollarOutputAmount(t, 3) == -1,   "vout3 OP_RETURN -> not DD");
    check(BRDigiDollarOutputAmount(t, 9) == -1,   "out-of-range vout -> -1");

    // Skip rule: a nonzero-value P2TR (looks like 51 20 but amount!=0) must NOT consume a slot.
    // vout0 nonzero 51-20 (mint-collateral shape), vout1 zero 51-20 -> ordinal 0 -> 5000
    BRTransaction *s = BRTransactionNew(); s->version = 0x02000770;
    uint8_t ors[]={0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13}; // [5000]
    BRTransactionAddOutput(s, 999, p2tr_a, 34);      // vout0 nonzero 51-20 -> skipped, no slot
    BRTransactionAddOutput(s, 0,   p2tr_b, 34);      // vout1 zero 51-20 -> DD ordinal 0 -> 5000
    BRTransactionAddOutput(s, 0,   ors, sizeof(ors));
    check(BRDigiDollarOutputAmount(s, 0) == -1,   "nonzero 51-20 is not DD");
    check(BRDigiDollarOutputAmount(s, 1) == 5000, "first ZERO 51-20 is DD ordinal 0 -> 5000");

    // A DD output whose ordinal exceeds the amount list -> -1 (never over-credit).
    BRTransaction *x = BRTransactionNew(); x->version = 0x02000770;
    BRTransactionAddOutput(x, 0, p2tr_a, 34);        // ordinal 0 -> 5000
    BRTransactionAddOutput(x, 0, p2tr_b, 34);        // ordinal 1 -> no amount[1] (list is [5000])
    BRTransactionAddOutput(x, 0, ors, sizeof(ors));  // [5000]
    check(BRDigiDollarOutputAmount(x, 0) == 5000, "ordinal 0 -> 5000");
    check(BRDigiDollarOutputAmount(x, 1) == -1,   "ordinal 1 with no amount slot -> -1");

    BRTransactionFree(t);
    BRTransactionFree(s);
    BRTransactionFree(x);

    // --- Extended coverage: the reader understands all four standard push
    // encodings, is bounded by the bytes that remain, walks to the requested output
    // rather than filling a fixed array, validates the whole amount list, and requires
    // the canonical DD-output second byte.
    //
    // Each group below is labelled with what kind of check it is:
    //   RED-THEN-GREEN  the assertion did not hold before the reader learned the property,
    //                   and holds now.
    //   GUARD           the property already held; the assertion pins it so that it stays.
    //                   A guard is never the proof that a property was newly established. ---

    // (a) All four push encodings for the "DD" marker, the type push and the amount push.
    //     Direct (0x02) is the minimal form; PUSHDATA1/2/4 are the same bytes reframed, as
    //     another wallet may legitimately emit them. All four must decode to [5000].
    //     Direct and PUSHDATA1: GUARD.  PUSHDATA2 and PUSHDATA4: RED-THEN-GREEN.
    //     Direct: OP_RETURN push2 "DD", push1 type(2), push2 amount(5000).
    uint8_t enc_direct[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    //     PUSHDATA1: 4c LL <data> for each push.
    uint8_t enc_pd1[] = {0x6a,0x4c,0x02,0x44,0x44,0x4c,0x01,0x02,0x4c,0x02,0x88,0x13};
    //     PUSHDATA2: 4d LLLL(LE) <data> for each push.
    uint8_t enc_pd2[] = {0x6a,0x4d,0x02,0x00,0x44,0x44,0x4d,0x01,0x00,0x02,0x4d,0x02,0x00,0x88,0x13};
    //     PUSHDATA4: 4e LLLLLLLL(LE) <data> for each push.
    uint8_t enc_pd4[] = {0x6a,0x4e,0x02,0x00,0x00,0x00,0x44,0x44,
                                 0x4e,0x01,0x00,0x00,0x00,0x02,
                                 0x4e,0x02,0x00,0x00,0x00,0x88,0x13};
    struct { uint8_t *s; size_t n; const char *desc; } enc[] = {
        { enc_direct, sizeof(enc_direct), "direct-push framing -> [5000]" },
        { enc_pd1,    sizeof(enc_pd1),    "PUSHDATA1 framing -> [5000]" },
        { enc_pd2,    sizeof(enc_pd2),    "PUSHDATA2 framing -> [5000]" },
        { enc_pd4,    sizeof(enc_pd4),    "PUSHDATA4 framing -> [5000]" },
    };
    for (size_t e = 0; e < sizeof(enc)/sizeof(enc[0]); e++) {
        BRTransaction *tt = BRTransactionNew(); tt->version = 0x02000770;
        BRTransactionAddOutput(tt, 0, enc[e].s, enc[e].n);
        int64_t a1[8]; int nn = BRDigiDollarDecodeAmounts(tt, a1, 8);
        check(nn == 1 && a1[0] == 5000, enc[e].desc);
        BRTransactionFree(tt);
    }

    // (b) GUARD — every declared push length is compared against the bytes that remain, in
    //     each of the four encodings; a push that does not fit is refused (fail closed).
    //     BRTransactionAddOutput stores each script in a heap buffer of exactly scriptLen
    //     bytes and run.sh builds this KAT with AddressSanitizer, so the tool, not only the
    //     -1 return, checks that the reader stays inside the script.
    uint8_t pd2_over[]  = {0x6a,0x4d,0xff,0x00,0x44,0x44};            // marker: declares 255, has 2
    uint8_t pd4_over[]  = {0x6a,0x4e,0xff,0xff,0xff,0x7f,0x44,0x44};  // marker: declares 2^31-1, has 2
    uint8_t pd4_max[]   = {0x6a,0x4e,0xff,0xff,0xff,0xff,0x44,0x44};  // marker: declares 2^32-1, has 2
    uint8_t pd2_nodata[]= {0x6a,0x4d,0x02,0x00};                      // marker: declares 2, has 0
    uint8_t pd4_hdr_m[] = {0x6a,0x4e,0x02,0x00,0x00};                 // marker: 3 of 4 length bytes
    //     A length header that is itself cut short, at the amount push, once per encoding.
    uint8_t pd1_hdr[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4c};                 // 0 of 1 length bytes
    uint8_t pd2_hdr[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4d,0x02};            // 1 of 2 length bytes
    uint8_t pd4_hdr[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4e,0x02,0x00,0x00};  // 3 of 4 length bytes
    //     One past: the amount push declares 3 bytes and 2 remain, once per encoding.
    uint8_t d_one[]     = {0x6a,0x02,0x44,0x44,0x01,0x02,0x03,0x88,0x13};
    uint8_t pd1_one[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4c,0x03,0x88,0x13};
    uint8_t pd2_one[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4d,0x03,0x00,0x88,0x13};
    uint8_t pd4_one[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4e,0x03,0x00,0x00,0x00,0x88,0x13};
    //     The amount push declares 2^32-1 bytes and 2 remain.
    uint8_t pd4_amt[]   = {0x6a,0x02,0x44,0x44,0x01,0x02,0x4e,0xff,0xff,0xff,0xff,0x88,0x13};
    struct { uint8_t *s; size_t n; const char *desc; } bounded[] = {
        { pd2_over,  sizeof(pd2_over),  "PUSHDATA2 length past end -> -1 (bounded)" },
        { pd4_over,  sizeof(pd4_over),  "PUSHDATA4 length past end -> -1 (bounded)" },
        { pd4_max,   sizeof(pd4_max),   "PUSHDATA4 largest declarable length -> -1 (bounded)" },
        { pd2_nodata,sizeof(pd2_nodata),"PUSHDATA2 length header with no data -> -1 (bounded)" },
        { pd4_hdr_m, sizeof(pd4_hdr_m), "PUSHDATA4 partial length header at the marker -> -1 (bounded)" },
        { pd1_hdr,   sizeof(pd1_hdr),   "PUSHDATA1 partial length header -> -1 (bounded)" },
        { pd2_hdr,   sizeof(pd2_hdr),   "PUSHDATA2 partial length header -> -1 (bounded)" },
        { pd4_hdr,   sizeof(pd4_hdr),   "PUSHDATA4 partial length header -> -1 (bounded)" },
        { d_one,     sizeof(d_one),     "direct push declares one more byte than remains -> -1 (bounded)" },
        { pd1_one,   sizeof(pd1_one),   "PUSHDATA1 declares one more byte than remains -> -1 (bounded)" },
        { pd2_one,   sizeof(pd2_one),   "PUSHDATA2 declares one more byte than remains -> -1 (bounded)" },
        { pd4_one,   sizeof(pd4_one),   "PUSHDATA4 declares one more byte than remains -> -1 (bounded)" },
        { pd4_amt,   sizeof(pd4_amt),   "PUSHDATA4 amount, largest declarable length -> -1 (bounded)" },
    };
    for (size_t e = 0; e < sizeof(bounded)/sizeof(bounded[0]); e++) {
        int64_t a2[8];
        BRTransaction *bt = BRTransactionNew(); bt->version = 0x02000770;
        BRTransactionAddOutput(bt, 0, bounded[e].s, bounded[e].n);
        check(BRDigiDollarDecodeAmounts(bt, a2, 8) == -1, bounded[e].desc);
        BRTransactionFree(bt);
    }

    // (c) The output binding walks to the requested output rather than filling a fixed array,
    //     so a transfer with any number of DD outputs is bound correctly.
    //     63 and 64 outputs: GUARD.  65 and 200 outputs: RED-THEN-GREEN.
    //     Built helper: N zero-value DD outputs (OP_1 <32>) at vout 0..N-1, then a "DD"
    //     OP_RETURN carrying amounts 1..N; the ordinal of vout k is exactly k.
    for (size_t caseIdx = 0; caseIdx < 4; caseIdx++) {
        static const size_t counts[] = { 63, 64, 65, 200 };
        size_t N = counts[caseIdx];
        BRTransaction *big = BRTransactionNew(); big->version = 0x02000770;
        uint8_t p2tr[34]; p2tr[0]=0x51; p2tr[1]=0x20; memset(p2tr+2,0xC0,32);
        for (size_t i = 0; i < N; i++) BRTransactionAddOutput(big, 0, p2tr, 34);
        uint8_t script[4096]; size_t p = 0;
        script[p++]=0x6a; script[p++]=0x02; script[p++]=0x44; script[p++]=0x44; // OP_RETURN push2 "DD"
        script[p++]=0x01; script[p++]=0x02;                                     // push1 type TRANSFER
        for (size_t i = 0; i < N; i++) {
            uint8_t num[9]; size_t nl = BRDigiDollarWriteScriptNum((int64_t)(i + 1), num);
            script[p++] = (uint8_t)nl; memcpy(script + p, num, nl); p += nl;    // direct push amount i+1
        }
        BRTransactionAddOutput(big, 0, script, p);                             // OP_RETURN at vout N
        char d0[64], dN[64];
        snprintf(d0, sizeof(d0), "%zu DD outputs: first output -> ordinal 0 -> 1 cent", N);
        snprintf(dN, sizeof(dN), "%zu DD outputs: last output -> ordinal %zu -> %zu cents", N, N-1, N);
        check(BRDigiDollarOutputAmount(big, 0) == 1, d0);
        check(BRDigiDollarOutputAmount(big, N - 1) == (int64_t)N, dN);
        BRTransactionFree(big);
    }

    // (d) A DD token output is exactly Core's canonical form, OP_1 followed by a 32-byte push
    //     (0x51 0x20 ...). A 34-byte OP_1 script with any other second byte is not one.
    //     Canonical output binds: GUARD.  Other second byte does not bind: RED-THEN-GREEN.
    {
        uint8_t good[34];  good[0]=0x51;  good[1]=0x20;  memset(good+2,0xAA,32);  // canonical DD output
        uint8_t other[34]; other[0]=0x51; other[1]=0x21; memset(other+2,0xBB,32); // OP_1, second byte 0x21
        uint8_t orr2[]={0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09}; // [5000,2500]
        BRTransaction *nc = BRTransactionNew(); nc->version = 0x02000770;
        BRTransactionAddOutput(nc, 0, good, 34);    // vout0: canonical DD output
        BRTransactionAddOutput(nc, 0, other, 34);   // vout1: not the canonical form
        BRTransactionAddOutput(nc, 0, orr2, sizeof(orr2));
        check(BRDigiDollarOutputAmount(nc, 0) == 5000, "canonical OP_1 <32> output binds to ordinal 0");
        check(BRDigiDollarOutputAmount(nc, 1) == -1,   "OP_1 output with a wrong second byte is not DD");
        BRTransactionFree(nc);
    }

    // (e) GUARD — the two public readers agree about every transaction. The per-output
    //     binding validates the WHOLE amount list, exactly as BRDigiDollarDecodeAmounts does:
    //     when any amount in a transfer's list is not a minimal, positive number of at most
    //     8 bytes, the list as a whole is refused and NO output of that transaction binds to
    //     an amount — including the outputs whose own slot reads cleanly.
    {
        uint8_t o_a[34]; o_a[0]=0x51; o_a[1]=0x20; memset(o_a+2,0xA1,32);
        uint8_t o_b[34]; o_b[0]=0x51; o_b[1]=0x20; memset(o_b+2,0xB2,32);
        // second amount 05 00: a padded (non-minimal) number
        uint8_t l_pad[]  = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0x05,0x00};
        // second amount 85: minus five
        uint8_t l_neg[]  = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x01,0x85};
        // second amount: a 9-byte number
        uint8_t l_long[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,
                            0x09,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09};
        struct { uint8_t *s; size_t n; const char *what; } lists[] = {
            { l_pad,  sizeof(l_pad),  "[5000, padded number]" },
            { l_neg,  sizeof(l_neg),  "[5000, negative number]" },
            { l_long, sizeof(l_long), "[5000, 9-byte number]" },
        };
        for (size_t e = 0; e < sizeof(lists)/sizeof(lists[0]); e++) {
            BRTransaction *wl = BRTransactionNew(); wl->version = 0x02000770;
            BRTransactionAddOutput(wl, 0, o_a, 34);                 // vout0: DD ordinal 0
            BRTransactionAddOutput(wl, 0, o_b, 34);                 // vout1: DD ordinal 1
            BRTransactionAddOutput(wl, 0, lists[e].s, lists[e].n);  // vout2: the amount list
            int64_t a3[8]; char d[3][96];
            snprintf(d[0], sizeof(d[0]), "%s: the list is refused as a whole -> -1", lists[e].what);
            snprintf(d[1], sizeof(d[1]), "%s: output 0 binds to no amount -> -1", lists[e].what);
            snprintf(d[2], sizeof(d[2]), "%s: output 1 binds to no amount -> -1", lists[e].what);
            check(BRDigiDollarDecodeAmounts(wl, a3, 8) == -1, d[0]);
            check(BRDigiDollarOutputAmount(wl, 0) == -1, d[1]);
            check(BRDigiDollarOutputAmount(wl, 1) == -1, d[2]);
            BRTransactionFree(wl);
        }

        // The agreement holds in the accepting direction too. A transfer's list ends at the
        // first byte that is not a push; what was read up to there stands, for both readers.
        uint8_t l_tail[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09,0x51};
        BRTransaction *tl = BRTransactionNew(); tl->version = 0x02000770;
        BRTransactionAddOutput(tl, 0, o_a, 34);
        BRTransactionAddOutput(tl, 0, o_b, 34);
        BRTransactionAddOutput(tl, 0, l_tail, sizeof(l_tail));
        int64_t a4[8]; int n4 = BRDigiDollarDecodeAmounts(tl, a4, 8);
        check(n4 == 2 && a4[0] == 5000 && a4[1] == 2500, "list ending at a non-push byte -> [5000,2500]");
        check(BRDigiDollarOutputAmount(tl, 0) == 5000 && BRDigiDollarOutputAmount(tl, 1) == 2500,
              "list ending at a non-push byte: both outputs bind as the list reads");
        BRTransactionFree(tl);

        // A MINT carries one amount, its first; both readers stop there.
        uint8_t l_mint[] = {0x6a,0x02,0x44,0x44,0x01,0x01,0x02,0x88,0x13,0x02,0x05,0x00};
        BRTransaction *mt = BRTransactionNew(); mt->version = 0x01000770;
        BRTransactionAddOutput(mt, 0, o_a, 34);
        BRTransactionAddOutput(mt, 0, l_mint, sizeof(l_mint));
        int64_t a5[8]; int n5 = BRDigiDollarDecodeAmounts(mt, a5, 8);
        check(n5 == 1 && a5[0] == 5000, "mint reads its first amount only -> [5000]");
        check(BRDigiDollarOutputAmount(mt, 0) == 5000, "mint: output 0 binds to the first amount");
        BRTransactionFree(mt);
    }

    if (g_failures == 0) {
        printf("\nALL PASS (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
