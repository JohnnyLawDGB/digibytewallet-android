// Host KAT: the wallet pays only to witness programs that someone controls.
//
// INVARIANT. BRAddressIsValid and BRAddressScriptPubKey accept a bech32/bech32m address only for
// version 0 with a 20- or 32-byte program (P2WPKH, P2WSH) or version 1 with a 32-byte program
// (Taproot). BIP341 leaves a version-1 output of any other length unencumbered (anyone can spend
// it), and versions 2 to 16 are undefined, so the wallet refuses both. Base58 addresses and every
// payable witness program answer exactly as before.
//
// The addresses are built here with the core's own BRBech32Encode (which encodes any version 0..16
// and any length 2..40), plus two literal vectors: a published-style v1/32 vector (bech32m_kat) and
// the reported v1/20 address (twenty 0x11 bytes; the report's printed copy dropped characters).
//
// COMPARISON ARM (-DWITNESS_PROGRAM_LEN_UNFIXED) restores the old rule (only version 0 had a length
// rule). There exactly the refused non-zero-version cases fail; everything else passes in both arms.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRAddress.h"
#include "BRBech32.h"

static int failures = 0;

static void check(int ok, const char *what)
{
    printf("  %s: %s\n", ok ? "PASS" : "FAIL", what);
    if (! ok) failures++;
}

static void expect(const char *addr, int payable, const char *label)
{
    uint8_t script[64];
    char what[256];
    int v = BRAddressIsValid(addr);
    size_t need = BRAddressScriptPubKey(NULL, 0, addr), n = BRAddressScriptPubKey(script, sizeof(script), addr);

    snprintf(what, sizeof(what), "%-28s %s valid=%d scriptLen=%zu (want %s)", label, addr, v, n,
             payable ? "payable" : "refused");
    check(payable ? (v == 1 && n > 0 && need == n) : (v == 0 && n == 0 && need == 0), what);
}

static void program(int ver, int len, int payable)
{
    uint8_t data[42];
    char addr[91], label[64];

    data[0] = ver == 0 ? OP_0 : (uint8_t)(OP_1 + ver - 1);
    data[1] = (uint8_t)len;
    for (int i = 0; i < len; i++) data[2 + i] = (uint8_t)(0x11 + i);
    if (BRBech32Encode(addr, "dgb", data) == 0) {
        snprintf(label, sizeof(label), "encode v%d/%d", ver, len);
        check(0, label);
        return;
    }
    snprintf(label, sizeof(label), "v%d/%d", ver, len);
    expect(addr, payable, label);
}

int main(void)
{
    // Payable: unchanged in both arms.
    program(0, 20, 1);
    program(0, 32, 1);
    program(1, 32, 1);
    expect("dgb1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s470eva", 1, "v1/32 bech32m_kat vector");
    expect("DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn", 1, "base58 P2PKH");
    expect("SPwYjsECsfKWQYyDVTDDVtvf1ZAQF74uam", 1, "base58 P2SH");

    // Refused in both arms: version 0 already had its length rule.
    program(0, 16, 0);
    program(0, 33, 0);
    expect("tb1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzhtfd6", 0, "foreign hrp");
    expect("dgb1qqqqqqqqqq", 0, "undecodable bech32");
    expect("not-an-address", 0, "not an address");

    // Refused only by the fix: version 1 of any length but 32, and versions 2..16.
    printf("--- the cases the fix governs ---\n");
    expect("dgb1pzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3772kx9", 0, "v1/20 reported (0x11 x20)");
    program(1, 2, 0);
    program(1, 16, 0);
    program(1, 20, 0);
    program(1, 31, 0);
    program(1, 33, 0);
    program(1, 40, 0);
    program(2, 20, 0);
    program(2, 32, 0);
    program(16, 32, 0);
    program(16, 40, 0);

    printf("%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
