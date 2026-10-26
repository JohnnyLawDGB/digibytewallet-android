// Host KAT: an oversized tx / dandeliontx message is refused before it is parsed, and the peer is dropped
// as misbehaving (4.1.2 row 4, K3).
//
// The REAL _BRPeerAcceptTxMessage is called on a well-formed transaction of minimal outputs:
//   [A] 18 MB tx: verdict 0 (the caller disconnects the peer) and peak RSS < 10 MB above the baseline
//       taken after the message itself is built -- RED under TX_SIZE_GATE_UNFIXED (the parser allocates
//       one BRTxOutput per 9-byte output, ~200 MB here, and the peer is kept);
//   [B] the same as a dandeliontx;
//   [C] a normal 1-in/1-out transaction is not refused (verdict 1: unrequested, ignored);
//   [D] a transaction of exactly MAX_TX_MESSAGE_LENGTH bytes is not refused for its size.
// BRPeer.c is #included (same idiom as peer_command_field_kat). Exit 0 = all passed.
#include "BRPeer.c"
#include "BRChainParams.h"
#include <sys/resource.h>

static int g_fail = 0;
static void ck(int c, const char *what) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", what); if (!c) g_fail++; }
static long peakKb(void) { struct rusage u; getrusage(RUSAGE_SELF, &u); return u.ru_maxrss; }

// A well-formed tx of exactly `len` bytes (len >= 64): one input, then as many 9-byte empty-script
// outputs as fit, the remainder absorbed by the input's scriptSig.
static uint8_t *buildTx(size_t len) {
    uint8_t *b = calloc(1, len);
    size_t fixed = 4 + 1 + 32 + 4 + 5 + 4 + 5 + 4;   /* version, vin, outpoint, scriptSig varint(5), seq, vout varint(5), locktime */
    size_t outs = (len - fixed) / 9, pad = (len - fixed) - outs * 9, o = 0;
    UInt32SetLE(&b[o], 1); o += 4;
    b[o++] = 1;
    memset(&b[o], 0x11, 32); o += 32; UInt32SetLE(&b[o], 0); o += 4;
    b[o++] = 0xfe; UInt32SetLE(&b[o], (uint32_t)pad); o += 4; memset(&b[o], 0x51, pad); o += pad;
    UInt32SetLE(&b[o], 0xffffffff); o += 4;
    b[o++] = 0xfe; UInt32SetLE(&b[o], (uint32_t)outs); o += 4;
    for (size_t i = 0; i < outs; i++) { UInt64SetLE(&b[o], 1000); o += 8; b[o++] = 0; }
    UInt32SetLE(&b[o], 0); o += 4;
    if (o != len) { printf("FAIL: setup: built %zu of %zu bytes\n", o, len); exit(1); }
    return b;
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);

    for (int dand = 0; dand <= 1; dand++) {
        printf(dand ? "[B] 18 MB dandeliontx\n" : "[A] 18 MB tx\n");
        size_t len = 18u * 1000u * 1000u;
        uint8_t *m = buildTx(len);
        long before = peakKb();
        int r = _BRPeerAcceptTxMessage(p, m, len, dand);
        long grew = peakKb() - before;
        free(m);
        if (r != 0 || grew >= 10 * 1024) {
            printf("FAIL: %s: an 18 MB message was parsed (verdict %d, peak RSS +%ld KB)\n", dand ? "B" : "A", r, grew);
            g_fail++;
        } else ck(1, "refused before parsing: verdict 0, peak RSS grew < 10 MB");
    }

    printf("[C] a normal transaction\n");
    {
        uint8_t *m = buildTx(128);
        ck(_BRPeerAcceptTxMessage(p, m, 128, 0) == 1, "not refused (unrequested, ignored)");
        free(m);
    }
    printf("[D] exactly at the bound\n");
    {
        uint8_t *m = buildTx(MAX_TX_MESSAGE_LENGTH);
        ck(_BRPeerAcceptTxMessage(p, m, MAX_TX_MESSAGE_LENGTH, 0) == 1, "not refused for its size");
        free(m);
    }

    BRPeerFree(p);
    if (g_fail == 0) printf("\nPASS: tx_size_gate_kat\n");
    else printf("\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
