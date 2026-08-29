// Host KAT: a FOREIGN seed can sign a Taproot input — the signing half of DigiDollar recovery.
//
// WHY THIS MATTERS. DigiDollar lives at m/86'/20'/0' as a zero-value P2TR output. Recovering it
// from someone else's seed needs a key derived from that seed and a BIP341 key-path Schnorr
// signature over it. The existing foreign signer (buildAndSignForeignAssetTransfer) derives keys
// with BRBIP32PrivKeyArrayPath and hands them to BRTransactionSign — both of which were built for
// ECDSA inputs.
//
// The claim this KAT tests, before anything is built on it:
//
//   1. BRBIP32PrivKeyArrayPath with prefix [86', 20', 0'] produces the SAME key as
//      BRBIP32PrivKeyBIP86. If it did not, the foreign path would need its own derivation.
//   2. BRTransactionSign recognises a P2TR input by its taproot output key X(Q) and signs it,
//      rather than skipping it as unmatched — which would yield a silently unsigned transaction.
//
// Both are assumptions the DigiDollar restore design rests on. Cheap to check here; expensive to
// discover on a device holding someone's dollars.
//
// Exit code 0 = all checks passed, 1 = a check failed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "BRBIP39Mnemonic.h"
#include "BRBIP32Sequence.h"
#include "BRKey.h"
#include "BRAddress.h"
#include "BRTransaction.h"

#define BIP32_HARD 0x80000000u
static int failures = 0;

static void ok(const char *what, int cond) {
    printf("  %s %s\n", cond ? "ok  " : "FAIL", what);
    if (!cond) failures++;
}

int main(void) {
    printf("foreign_taproot_sign_kat\n");

    const char *mnemonic =
        "abandon abandon abandon abandon abandon abandon "
        "abandon abandon abandon abandon abandon about";
    UInt512 seed;
    BRBIP39DeriveKey(seed.u8, mnemonic, NULL);

    /* ---- 1. the generic path derivation must equal the BIP86 helper ---------------------- */
    BRKey viaHelper, viaPath;
    BRBIP32PrivKeyBIP86(&viaHelper, &seed, sizeof(seed), 0, 0);

    uint32_t path[5] = { 86|BIP32_HARD, 20|BIP32_HARD, 0|BIP32_HARD, 0, 0 };
    BRBIP32PrivKeyArrayPath(&viaPath, &seed, sizeof(seed), "Bitcoin seed", path, 5);

    ok("m/86'/20'/0'/0/0 via the generic path == via the BIP86 helper",
       memcmp(&viaHelper.secret, &viaPath.secret, sizeof(UInt256)) == 0);

    /* The foreign signer derives exactly this way, so if the above holds it can produce
     * DigiDollar keys without a new derivation routine. */
    uint8_t xq[32];
    ok("the derived key yields a taproot output key", BRKeyTaprootOutputKey(&viaPath, xq) == 1);

    char addr[91];
    size_t n = BRKeyTaprootAddress(&viaPath, addr, sizeof(addr));
    ok("it encodes as a dgb1p address", n > 0 && strncmp(addr, "dgb1p", 5) == 0);
    printf("       %s\n", addr);

    /* ---- 2. BRTransactionSign must actually sign a P2TR input ---------------------------- */
    /* Build a one-input, one-output transaction spending a P2TR script that pays X(Q). */
    uint8_t p2tr[34];
    p2tr[0] = OP_1;
    p2tr[1] = 32;
    memcpy(&p2tr[2], xq, 32);

    BRTransaction *tx = BRTransactionNew();
    UInt256 prev;
    memset(&prev, 0x11, sizeof(prev));
    BRTransactionAddInput(tx, prev, 0, 100000, p2tr, sizeof(p2tr), NULL, 0, NULL, 0, TXIN_SEQUENCE);

    uint8_t out[25];
    size_t outLen = BRAddressScriptPubKey(out, sizeof(out), "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe");
    BRTransactionAddOutput(tx, 90000, out, outLen);

    BRKey keys[1];
    keys[0] = viaPath;
    int signedOk = BRTransactionSign(tx, 0, keys, 1);

    ok("BRTransactionSign accepts a P2TR input from a foreign-derived key", signedOk != 0);
    ok("the signed transaction reports itself signed", BRTransactionIsSigned(tx) != 0);
    /* A key-path spend carries exactly one 64-byte witness element and an EMPTY scriptSig.
     * If BRTransactionSign had skipped the input, both would be empty and IsSigned would lie. */
    ok("the input carries a witness (the Schnorr signature)", tx->inputs[0].witLen >= 64);
    ok("a key-path spend leaves scriptSig empty", tx->inputs[0].sigLen == 0);

    BRTransactionFree(tx);
    memset(&seed, 0, sizeof(seed));

    printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
