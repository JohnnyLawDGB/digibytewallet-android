// Host KAT: the consensus-significant shape of a DigiDollar TRANSFER built from a foreign seed.
//
// WHY. Recovery moves someone's dollars. Three things about a DD transfer are load-bearing and
// none of them is visible in a signature check:
//
//   * the nVersion marker — without 0x0770 in the low 16 bits the transaction is not a DigiDollar
//     transaction at all, it is an ordinary spend that destroys the token;
//   * the OP_RETURN bytes — the cents live there, not in any output amount;
//   * the ORDER of the outputs — BRWalletCreateDigiDollarTransfer carries the comment
//     "NO shuffle (output order is consensus-significant)".
//
// Each is cheap to pin here and impossible to reconstruct after a bad transfer has confirmed.
//
// The reference values are not invented. They are taken from a real mainnet DigiDollar transfer,
// 40a78f1306123354dfcbe3b067a2cc81b916567b418a22fe2c2a9108dae54653, whose marker reads
// 6a 02 44 44 01 02 01 64 — "DD", type 2, one push of 100 cents — and whose version serialises as
// 70 07 00 02 little-endian, i.e. 0x02000770.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "digidollar_transfer_layout.h"

static int failures = 0;

static void ok(const char *what, int cond) {
    printf("  %s %s\n", cond ? "ok  " : "FAIL", what);
    if (!cond) failures++;
}

static void hexdump(const char *label, const uint8_t *b, size_t n) {
    printf("       %s", label);
    for (size_t i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

int main(void) {
    printf("digidollar_transfer_kat\n");

    /* ---- the version marker ------------------------------------------------------------- */
    ok("nVersion is 0x02000770", DD_TX_VERSION_TRANSFER == 0x02000770u);
    ok("its low 16 bits are the DigiDollar marker 0x0770",
       (DD_TX_VERSION_TRANSFER & 0xFFFFu) == 0x0770u);
    ok("its top byte is type 2 (TRANSFER)",
       ((DD_TX_VERSION_TRANSFER >> 24) & 0xFFu) == 2u);

    /* ---- the OP_RETURN, against the real mainnet transfer -------------------------------- */
    uint8_t orr[32];
    size_t ol = dd_op_return_script(100, 0, orr, sizeof(orr));
    const uint8_t expect[] = { 0x6a, 0x02, 0x44, 0x44, 0x01, 0x02, 0x01, 0x64 };
    ok("a $1.00 transfer encodes exactly as the mainnet transfer did",
       ol == sizeof(expect) && memcmp(orr, expect, ol) == 0);
    hexdump("built    ", orr, ol);
    hexdump("on chain ", expect, sizeof(expect));

    /* Change adds a SECOND push and must not disturb the first. */
    ol = dd_op_return_script(100, 100, orr, sizeof(orr));
    ok("change appends a second push, leaving the first intact",
       ol == 10 && memcmp(orr, expect, 8) == 0 && orr[8] == 0x01 && orr[9] == 0x64);

    /* Script numbers are SIGNED. An amount whose top byte has the high bit set gains a 0x00 sign
     * byte, or it would read back negative — 250 is 0xfa, so it encodes as {0xfa, 0x00}, two
     * bytes not one. Pinned because a hand-written encoder gets this wrong and the resulting
     * marker still looks plausible. */
    ol = dd_op_return_script(100, 250, orr, sizeof(orr));
    ok("an amount with the high bit set gains a sign byte",
       ol == 11 && orr[8] == 0x02 && orr[9] == 0xfa && orr[10] == 0x00);
    hexdump("250c push", orr + 8, 3);

    ok("no change means no second push", dd_op_return_script(100, 0, orr, sizeof(orr)) == 8);

    /* A buffer too small must refuse rather than truncate — a half-written marker would be a
     * transaction that spends the token and records no amount. */
    uint8_t tiny[7];
    ok("a buffer too small refuses rather than truncating",
       dd_op_return_script(100, 0, tiny, sizeof(tiny)) == 0);

    /* ---- the recipient script ------------------------------------------------------------ */
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)(i + 1);
    uint8_t spk[34];
    size_t sl = dd_recipient_script(key, spk);
    ok("the recipient script is 34 bytes", sl == 34);
    ok("it is OP_1 <push32> X(Q)", spk[0] == 0x51 && spk[1] == 0x20);
    ok("the key is copied verbatim, not re-tweaked", memcmp(spk + 2, key, 32) == 0);

    /* ---- the consensus bounds ------------------------------------------------------------ */
    ok("$1.00 is the minimum accepted", dd_cents_in_range(100));
    ok("$0.99 is refused", !dd_cents_in_range(99));
    ok("zero is refused", !dd_cents_in_range(0));
    ok("a negative amount is refused", !dd_cents_in_range(-1));
    ok("$100,000 is the maximum accepted", dd_cents_in_range(10000000));
    ok("a cent over the maximum is refused", !dd_cents_in_range(10000001));

    /* ---- the fee floor -------------------------------------------------------------------- */
    ok("the DigiDollar fee floor is 0.1 DGB", DD_MIN_FEE_SATS == 10000000ULL);

    printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
