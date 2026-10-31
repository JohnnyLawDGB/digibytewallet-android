// Host KAT: a transaction's log tag is HMAC-SHA256(key, display-order txid hex), first 8 hex (4.1.3, review R7).
// The known answers are computed outside the app (Python hmac) and are the ones LogTagTest holds Kotlin to.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRCrypto.h"
#include "BRInt.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

int main(void)
{
    char out[9];
    UInt256 h = UINT256_ZERO;
    h.u8[0] = 0xff;   // internal order: display order is 00..00ff
    check(strcmp(BRLogTxTag(h.u8, out), "--------") == 0, "no key yet: no tag");
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)i;
    BRLogTagSetKey(key);
    check(strcmp(BRLogTxTag(h.u8, out), "cd29415f") == 0, "a UInt256 txHash's tag is the known answer");
    check(strcmp(BRLogTxTagHex("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90", out), "02764d55") == 0,
          "a display txid's tag is the known answer");
    check(strcmp(BRLogTxTagHex("A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90", out), "02764d55") == 0,
          "upper-case hex gives the same tag");
    check(strcmp(BRLogTxTagHex("abc", out), "--------") == 0, "a short string gives no tag");
    check(strcmp(BRLogTxTagHex(NULL, out), "--------") == 0, "NULL gives no tag");
    printf("\n%s: log_tag_kat (%d failure%s)\n", g_fail ? "FAIL" : "PASS", g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
