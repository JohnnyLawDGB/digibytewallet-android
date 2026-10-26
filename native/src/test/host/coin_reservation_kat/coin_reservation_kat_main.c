// Host KAT: a coin signed for one send is never signed into another before the first is published
// (4.1.2 row 3, N8). Drives coin_reservation.c, which seed_sign_transaction consults after every
// signature and the publish paths release (run.sh pins both against the source).
//
//   [A] send A holds X and Y; send B, spending Y and Z, is refused -- RED under COIN_RESERVATION_UNFIXED;
//   [B] a refused claim takes nothing: Z alone is free for a third send;
//   [C] the same transaction signed again (deterministic signatures: same txid) renews its own claim;
//   [D] once A is published (released), B is granted;
//   [E] a claim whose send was never published lapses after the TTL, and not before;
//   [F] the table is bounded: a claim that would not fit is refused, whole.
#include "coin_reservation.h"
#include <stdio.h>
#include <string.h>

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }

static UInt256 h(uint8_t b) { UInt256 x = UINT256_ZERO; x.u8[0] = b; x.u8[31] = 0x5a; return x; }
static BRTxInput in(uint8_t b, uint32_t n) { BRTxInput i; memset(&i, 0, sizeof(i)); i.txHash = h(b); i.index = n; return i; }

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    const UInt256 A = h(0xa1), B = h(0xb2), C = h(0xc3);
    const double t = 1000.0;

    printf("[A] a second send on a held coin\n");
    coin_reservation_clear();
    BRTxInput xa[2] = { in(1, 0), in(2, 1) };            /* X, Y */
    BRTxInput yz[2] = { in(2, 1), in(3, 0) };            /* Y, Z */
    ck(coin_reservation_claim(A, xa, 2, t), "send A claims X and Y");
    if (coin_reservation_claim(B, yz, 2, t)) { printf("FAIL: A: send B was signed on coin Y, which send A holds\n"); g_fail++; }
    else ck(1, "send B, which also spends Y, is refused");

    printf("[B] a refused claim takes nothing\n");
    BRTxInput z[1] = { in(3, 0) };
    ck(coin_reservation_claim(C, z, 1, t), "Z alone is free for a third send");
    coin_reservation_release(C);

    printf("[C] the same send signed again\n");
    ck(coin_reservation_claim(A, xa, 2, t + 5), "send A renews its own claim");

    printf("[D] published\n");
    coin_reservation_release(A);
    ck(coin_reservation_claim(B, yz, 2, t + 6), "once A is published, B is granted");
    coin_reservation_release(B);

    printf("[E] a send never published\n");
    coin_reservation_clear();
    ck(coin_reservation_claim(A, xa, 2, t), "send A claims X and Y");
    ck(! coin_reservation_claim(B, yz, 2, t + COIN_RESERVATION_TTL_S - 1), "before the TTL, B is still refused");
    ck(coin_reservation_claim(B, yz, 2, t + COIN_RESERVATION_TTL_S + 1), "after the TTL, A's claim has lapsed and B is granted");

    printf("[F] bounded\n");
    coin_reservation_clear();
    static BRTxInput many[COIN_RESERVATION_MAX + 1];
    for (size_t i = 0; i <= COIN_RESERVATION_MAX; i++) { many[i] = in((uint8_t)(i & 0xff), (uint32_t)(i >> 8) + 7); }
    ck(! coin_reservation_claim(A, many, COIN_RESERVATION_MAX + 1, t), "a claim over the bound is refused");
    ck(coin_reservation_claim(B, many, COIN_RESERVATION_MAX, t), "and took nothing: a claim at the bound is granted");

    if (g_fail == 0) printf("\nPASS: coin_reservation_kat\n");
    else printf("\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
