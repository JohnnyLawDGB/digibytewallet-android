// Host KAT for the SegWit fee-estimation fix (BRTransactionVSize / BRTransactionSize).
//
// Root cause of "sends never confirm": for an UNSIGNED native-segwit (P2WPKH) input the
// vsize estimator omitted the ~41 base input bytes (outpoint+index+empty-scriptSig+sequence),
// counting only the witness estimate. So the pre-sign fee was computed on a too-small vsize
// and the broadcast tx paid BELOW the node min relay fee (100 sat/vByte) → peers rejected it
// ("min relay fee not met"). On-chain the real signed 1-in/2-out P2WPKH tx measured vsize=141.
//
// This KAT builds that exact unsigned shape and asserts the estimate now covers the real
// signed vsize, so the fee clears the min relay fee. Exit 0 = pass, 1 = fail.
#include <stdio.h>
#include <string.h>
#include "BRTransaction.h"
#include "BRInt.h"

int main(void)
{
    int fails = 0;

    // P2WPKH scriptPubKey: OP_0 (0x00) PUSH20 (0x14) <20-byte hash>
    uint8_t p2wpkh[22] = { 0x00, 0x14 };
    for (int i = 0; i < 20; i++) p2wpkh[2 + i] = (uint8_t)(0x10 + i);

    BRTransaction *tx = BRTransactionNew();
    UInt256 prev = UINT256_ZERO;
    prev.u8[0] = 0xAB;
    // unsigned P2WPKH input (signature/witness NULL — the coin-selection fee-estimate state)
    BRTransactionAddInput(tx, prev, 0, 10000000000ULL, p2wpkh, sizeof(p2wpkh),
                          NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 8999988700ULL, p2wpkh, sizeof(p2wpkh)); // change
    BRTransactionAddOutput(tx, 1000000000ULL, p2wpkh, sizeof(p2wpkh)); // recipient

    size_t vsize = BRTransactionVSize(tx);
    size_t size  = BRTransactionSize(tx);
    printf("unsigned 1-in(P2WPKH) 2-out: BRTransactionVSize=%zu  BRTransactionSize=%zu\n", vsize, size);

    const size_t REAL_SIGNED_VSIZE = 141; // measured via node decoderawtransaction on the real tx
    const unsigned MIN_RELAY_SAT_PER_VB = 100;

    unsigned long long estFee  = (unsigned long long)vsize * MIN_RELAY_SAT_PER_VB;
    unsigned long long needFee = (unsigned long long)REAL_SIGNED_VSIZE * MIN_RELAY_SAT_PER_VB;
    printf("est fee @100 sat/vB = %llu sat ; min relay needs >= %llu sat\n", estFee, needFee);

    if (vsize < REAL_SIGNED_VSIZE) {
        printf("FAIL: vsize estimate %zu < real signed %zu — fee underpays min relay (the bug)\n",
               vsize, REAL_SIGNED_VSIZE);
        fails++;
    }
    else {
        printf("PASS: vsize estimate %zu >= real signed %zu — fee clears min relay\n",
               vsize, REAL_SIGNED_VSIZE);
    }

    BRTransactionFree(tx);
    if (fails) { printf("FAILED (%d)\n", fails); return 1; }
    printf("ALL PASS\n");
    return 0;
}
