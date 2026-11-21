// Host KAT: the DigiDollar recovery signer checks, before it signs, that every input names a different
// outpoint and that the fee the transfer pays is neither below the required fee nor above the fee the
// caller approved (DD-FEE-CEIL).
//
// THE INVARIANT. buildAndSignForeignDigiDollarTransfer signs the transfer it assembled only when
// dd_transfer_check (digidollar_transfer_guard.h) holds on it:
//   - the rate the caller states is above zero and not above DD_TRANSFER_MAX_FEE_PER_KB;
//   - no outpoint is listed twice among the inputs (the check the wallet asset signer makes);
//   - the fee -- the inputs as listed, minus the outputs -- is at least the required fee (the 0.1 DGB
//     DigiDollar floor, or what the size needs at the caller's rate), at most the fee the caller
//     approved, and at most FOREIGN_TX_MAX_FEE_FACTOR times the required fee whatever was approved.
// A refused transfer is not signed, and none of its coins is left claimed.
//
// Every case runs the bridge's own sequence on a transfer built here: the check, then
// BRTransactionSign, then signed_spend_gate (the claim). run.sh's source gate holds jni_derive.c to
// that order.
//
// RED (comparison arm, -DDD_TRANSFER_GUARD_UNFIXED=1): the checks before the header -- outputs within
// the inputs and the 0.1 DGB floor. There a transfer below the fee its size needs, one above the
// approved fee, one above the band, one at a rate out of range and one listing a coin twice are all
// SIGNED: those cases must fail.
// GUARDS (both arms): a fee under the 0.1 DGB floor and outputs over the inputs are refused; valid
// transfers sign, and their bytes are the bytes BRTransactionSign alone gives and the pinned digests.
//
// One case per process (argv[1]); "list" names them. Each case prints RESULT <case> pass|fail.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRTransaction.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRCrypto.h"
#include "digidollar_transfer_guard.h"
#include "signed_spend_gate.h"

#define H 0x80000000u
#define NOW 1000.0
#define RATE 100000ULL          // 100 sat/byte: the rate recovery sizes a transfer at
#define FLOOR DD_MIN_FEE_SATS   // 0.1 DGB

static int g_failed = 0;
static void ck(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_failed = 1; }

static UInt512 g_seed;

static BRKey keyAt(const uint32_t *path, size_t depth)
{
    BRKey k;
    BRBIP32PrivKeyArrayPath(&k, &g_seed, sizeof(g_seed), "Bitcoin seed", path, depth);
    return k;
}

// The keys of this KAT's coins: [0] the DigiDollar coin at m/86'/20'/0'/0/0, [1] and [2] plain DGB at
// m/84'/20'/0'/0/0 and m/84'/20'/0'/0/1, [3] the change address at m/84'/20'/0'/1/0 (never a signer).
static BRKey g_keys[4];
static uint8_t g_ddScript[34], g_feeScript[2][22], g_changeScript[22];

static void p2wpkh(BRKey *k, uint8_t out[22])
{
    UInt160 h = BRKeyHash160(k);
    out[0] = 0x00; out[1] = 0x14; memcpy(out + 2, h.u8, 20);
}

static void setup(void)
{
    const uint32_t dd[5] = { 86 | H, 20 | H, 0 | H, 0, 0 };
    const uint32_t f0[5] = { 84 | H, 20 | H, 0 | H, 0, 0 }, f1[5] = { 84 | H, 20 | H, 0 | H, 0, 1 };
    const uint32_t ch[5] = { 84 | H, 20 | H, 0 | H, 1, 0 };
    BRBIP39DeriveKey(g_seed.u8, "abandon abandon abandon abandon abandon abandon abandon abandon "
                                "abandon abandon abandon about", NULL);
    g_keys[0] = keyAt(dd, 5); g_keys[1] = keyAt(f0, 5); g_keys[2] = keyAt(f1, 5); g_keys[3] = keyAt(ch, 5);
    g_ddScript[0] = 0x51; g_ddScript[1] = 0x20;
    BRKeyTaprootOutputKey(&g_keys[0], g_ddScript + 2);
    p2wpkh(&g_keys[1], g_feeScript[0]);
    p2wpkh(&g_keys[2], g_feeScript[1]);
    p2wpkh(&g_keys[3], g_changeScript);
}

typedef struct { uint8_t fill; uint32_t vout; int which; uint64_t sats; } Coin;   // which: -1 DigiDollar, 0/1 a fee key

// A DigiDollar transfer of $1.00 in the bridge's layout: recipient, [DGB change], OP_RETURN last;
// the inputs in the order given.
static BRTransaction *transfer(const Coin *coins, size_t n, uint64_t change)
{
    BRTransaction *tx = BRTransactionNew();
    uint8_t recipient[34];
    uint8_t key[32];
    uint8_t orr[32];
    size_t ol;

    tx->version = DD_TX_VERSION_TRANSFER;
    memset(key, 0xbb, sizeof(key));
    dd_recipient_script(key, recipient);
    BRTransactionAddOutput(tx, 0, recipient, sizeof(recipient));
    if (change > 0) BRTransactionAddOutput(tx, change, g_changeScript, sizeof(g_changeScript));
    ol = dd_op_return_script(100, 0, orr, sizeof(orr));
    BRTransactionAddOutput(tx, 0, orr, ol);
    for (size_t i = 0; i < n; i++) {
        UInt256 h;
        memset(h.u8, coins[i].fill, 32);
        if (coins[i].which < 0) BRTransactionAddInput(tx, h, coins[i].vout, 0, g_ddScript, 34, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        else BRTransactionAddInput(tx, h, coins[i].vout, coins[i].sats, g_feeScript[coins[i].which], 22, NULL, 0, NULL, 0, TXIN_SEQUENCE);
    }
    return tx;
}

// What the bridge does with an assembled transfer: the check, then the signature, then the claim.
static int bridgeSign(BRTransaction *tx, uint64_t feePerKb, uint64_t approvedMaxFee, DdTransferVerdict *verdict)
{
    *verdict = dd_transfer_check(tx, feePerKb, approvedMaxFee, NULL);
    if (*verdict != DD_TRANSFER_OK) return 0;
    if (!BRTransactionSign(tx, 0, g_keys, 3) || !BRTransactionIsSigned(tx)) return 0;
    return signed_spend_gate(NULL, tx, NOW);
}

static int carriesNoSignature(const BRTransaction *tx)
{
    for (size_t i = 0; i < tx->inCount; i++) if (tx->inputs[i].sigLen != 0 || tx->inputs[i].witLen != 0) return 0;
    return 1;
}

// The transfer is refused with `want`, nothing of it is signed, and none of its coins is left claimed.
static void refused(const char *what, const Coin *coins, size_t n, uint64_t change, uint64_t feePerKb,
                    uint64_t approvedMaxFee, DdTransferVerdict want)
{
    char line[240];
    DdTransferVerdict verdict = DD_TRANSFER_OK;
    UInt256 other;
    BRTransaction *tx = transfer(coins, n, change);
    int handedOn = bridgeSign(tx, feePerKb, approvedMaxFee, &verdict);

    snprintf(line, sizeof(line), "%s: refused with verdict %d (got %d)", what, (int)want, (int)verdict);
    ck(verdict == want, line);
    snprintf(line, sizeof(line), "%s: no signed transfer is handed on", what);
    ck(!handedOn, line);
    snprintf(line, sizeof(line), "%s: no input carries a signature", what);
    ck(carriesNoSignature(tx) && !BRTransactionIsSigned(tx), line);
    memset(other.u8, 0xee, sizeof(other));
    snprintf(line, sizeof(line), "%s: none of its coins is left claimed (another send may claim them all)", what);
    ck(coin_reservation_claim(other, tx->inputs, tx->inCount, NOW), line);
    coin_reservation_release(other);
    BRTransactionFree(tx);
}

static void hexOf(const uint8_t *b, size_t n, char *out)
{
    for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]);
    out[2 * n] = '\0';
}

// The transfer signs; its bytes are the bytes BRTransactionSign alone gives the same transfer (the
// signer before the check existed), and their double SHA-256 is `digestHex`. Prints VECTOR <name> <hex>.
static void signsUnchanged(const char *name, const Coin *coins, size_t n, uint64_t change, uint64_t feePerKb,
                           uint64_t approvedMaxFee, const char *digestHex)
{
    char line[240], digest[65];
    uint8_t a[2048], b[2048];
    size_t al, bl;
    UInt256 d;
    DdTransferVerdict verdict = DD_TRANSFER_MALFORMED;
    BRTransaction *tx = transfer(coins, n, change), *plain = transfer(coins, n, change);
    int handedOn = bridgeSign(tx, feePerKb, approvedMaxFee, &verdict);

    snprintf(line, sizeof(line), "%s: signed and handed on (verdict %d)", name, (int)verdict);
    ck(handedOn && verdict == DD_TRANSFER_OK && BRTransactionIsSigned(tx), line);
    ck(BRTransactionSign(plain, 0, g_keys, 3) && BRTransactionIsSigned(plain), "the same transfer signs with BRTransactionSign alone");
    al = BRTransactionSerialize(tx, a, sizeof(a));
    bl = BRTransactionSerialize(plain, b, sizeof(b));
    snprintf(line, sizeof(line), "%s: byte-identical to the transfer BRTransactionSign alone signs (%zu bytes)", name, al);
    ck(al > 0 && al == bl && memcmp(a, b, al) == 0, line);
    BRSHA256_2(&d, a, al);
    hexOf(d.u8, 32, digest);
    snprintf(line, sizeof(line), "%s: the signed bytes hash to the pinned digest (got %s)", name, digest);
    ck(strcmp(digest, digestHex) == 0, line);
    if (handedOn) {
        char *hex = malloc(al * 2 + 1);
        hexOf(a, al, hex);
        printf("VECTOR %s %s\n", name, hex);
        free(hex);
        coin_reservation_release(tx->txHash);
    }
    BRTransactionFree(tx);
    BRTransactionFree(plain);
}

// ---- guards: these hold in both arms ----------------------------------------------------------

static void valid_transfers_sign_unchanged(void)
{
    // Two fee coins, neither enough alone, no change: the shape dd_fee_profiles_kat signs. Fee 0.11 DGB.
    const Coin two[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 6000000 }, { 0x42, 1, 1, 5000000 } };
    signsUnchanged("two_fee_coins_no_change", two, 3, 0, RATE, 11000000,
                   "e6f27c7fb7320546fd1958286b7a43b8822194d7d0135376297597c4fdf542d6");
    // One fee coin with DGB change: the fee is exactly the 0.1 DGB floor, and exactly the approved fee.
    const Coin one[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 26000000 } };
    signsUnchanged("one_fee_coin_with_change", one, 2, 16000000, RATE, FLOOR,
                   "202a54bf165fe91bd8f0170326ba8369e0dead2e19a81a1adb56917e48ef157b");
    // The fee exactly at the top of the band (3 x the required 0.1 DGB), approved as such.
    const Coin top[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 3 * FLOOR } };
    signsUnchanged("fee_at_the_band_ceiling", top, 2, 0, RATE, 3 * FLOOR,
                   "7d987c33cea46a314f7a07dbd15a9484be2a3a035887b0941f7de17c9be7495a");
    // Two outputs of one transaction are two outpoints: the same txid at another index is not a repeat.
    const Coin sib[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 6000000 }, { 0x41, 1, 1, 5000000 } };
    signsUnchanged("sibling_outputs_are_distinct", sib, 3, 0, RATE, 11000000,
                   "8cde7b26ebe2296e7f3fae5ec94d600afcdc5a1af3c641bee139f6a002d26b81");
}

static void fee_below_dd_floor_refused(void)
{
    const Coin c[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, FLOOR - 1 } };
    refused("a fee one satoshi under the 0.1 DGB floor", c, 2, 0, RATE, FLOOR, DD_TRANSFER_FEE_TOO_LOW);
    const Coin d[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 26000000 } };
    refused("change that leaves a fee under the floor", d, 2, 26000000 - FLOOR + 1, RATE, FLOOR, DD_TRANSFER_FEE_TOO_LOW);
}

static void overspend_refused(void)
{
    const Coin c[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 26000000 } };
    refused("change above the fee coins", c, 2, 26000001, RATE, FLOOR, DD_TRANSFER_OVERSPEND);
}

// ---- the invariant: each of these is signed in the comparison arm ------------------------------

// A DigiDollar coin and `feeCoins` plain coins, each a different output of one transaction, that hold
// `total` between them.
static void manyCoins(Coin *out, size_t feeCoins, uint64_t total)
{
    const uint64_t each = total / feeCoins;
    out[0] = (Coin){ 0x31, 0, -1, 0 };
    for (size_t i = 0; i < feeCoins; i++) {
        out[1 + i] = (Coin){ 0x41, (uint32_t)i, (int)(i % 2), (i + 1 == feeCoins) ? total - each * (feeCoins - 1) : each };
    }
}

static void fee_below_size_required_refused(void)
{
    // At the highest rate accepted (1,000 sat/byte) the estimate for 70 inputs and 2 outputs
    // (10 + 70*160 + 2*34 = 11,278 bytes) is 0.11278 DGB: above the 0.1 DGB floor, so the size sets the
    // required fee.
    enum { INPUTS = 70 };
    const uint64_t rate = DD_TRANSFER_MAX_FEE_PER_KB, required = 11278000ULL;
    Coin under[INPUTS], at[INPUTS];
    DdTransferVerdict verdict;
    BRTransaction *tx;

    // The estimate itself, at any rate: 2 inputs and 2 outputs are 398 bytes.
    ck(dd_transfer_required_fee(2, 2, 100000000ULL) == 39800000ULL, "the required fee of 2 inputs and 2 outputs at 100,000 sat/byte is 0.398 DGB");
    ck(dd_transfer_required_fee(2, 2, RATE) == FLOOR, "at 100 sat/byte the 0.1 DGB floor is the required fee");
    ck(dd_transfer_required_fee(2, 2, rate) == FLOOR, "for 2 inputs the 0.1 DGB floor is the required fee at the highest rate accepted too");
    ck(dd_transfer_required_fee(INPUTS, 2, rate) == required, "the required fee of 70 inputs and 2 outputs at 1,000 sat/byte is 0.11278 DGB");
    manyCoins(under, INPUTS - 1, required - 1);
    manyCoins(at, INPUTS - 1, required);
    refused("a fee over the 0.1 DGB floor and one satoshi under what the size needs", under, INPUTS, 0, rate, required,
            DD_TRANSFER_FEE_TOO_LOW);
    tx = transfer(at, INPUTS, 0);
    ck(bridgeSign(tx, rate, required, &verdict) && verdict == DD_TRANSFER_OK, "exactly the required fee is signed");
    coin_reservation_release(tx->txHash);
    BRTransactionFree(tx);
}

static void fee_above_approved_refused(void)
{
    // The fee is held to the approved fee: a transfer with no change output against an approved 0.1 DGB.
    const Coin whole[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 2500000000ULL } };
    refused("a 25 DGB coin with no change against an approved 0.1 DGB", whole, 2, 0, RATE, FLOOR,
            DD_TRANSFER_FEE_ABOVE_APPROVED);
    const Coin over[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 26000000 } };
    refused("a fee one satoshi above the approved fee", over, 2, 26000000 - FLOOR - 1, RATE, FLOOR,
            DD_TRANSFER_FEE_ABOVE_APPROVED);
}

static void fee_above_band_refused(void)
{
    // The caller approves the whole of what its own plan pays: the band still holds.
    const Coin whole[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 2500000000ULL } };
    refused("a 25 DGB fee the caller itself approved", whole, 2, 0, RATE, 2500000000ULL, DD_TRANSFER_FEE_TOO_HIGH);
    const Coin over[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 3 * FLOOR + 1 } };
    refused("a fee one satoshi over the band, approved", over, 2, 0, RATE, 3 * FLOOR + 1, DD_TRANSFER_FEE_TOO_HIGH);
    refused("a fee one satoshi over the band, approved without limit", over, 2, 0, RATE, UINT64_MAX, DD_TRANSFER_FEE_TOO_HIGH);
    // The band at the highest rate accepted is still the band over the 0.1 DGB floor for this shape.
    refused("a fee one satoshi over the band at the highest rate accepted", over, 2, 0, DD_TRANSFER_MAX_FEE_PER_KB,
            UINT64_MAX, DD_TRANSFER_FEE_TOO_HIGH);
}

static void rate_out_of_range_refused(void)
{
    // The band is sized at the caller's rate, so the rate has a bound of the signer's own: a rate and
    // an approved fee that agree with each other do not widen it.
    const uint64_t big = 2500000000ULL, rate = 6000000000ULL;   // at this rate 2 inputs "need" 23.88 DGB
    const Coin whole[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, big } };
    const Coin ok[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 11000000 } };
    DdTransferVerdict verdict;
    BRTransaction *tx;

    ck(dd_transfer_required_fee(2, 2, rate) * FOREIGN_TX_MAX_FEE_FACTOR >= big && dd_transfer_required_fee(2, 2, rate) <= big,
       "at the stated rate the whole coin would sit inside the band");
    refused("a rate and an approved fee that agree on the whole coin", whole, 2, 0, rate, big, DD_TRANSFER_RATE_OUT_OF_RANGE);
    refused("an otherwise valid transfer one above the highest rate", ok, 2, 0, DD_TRANSFER_MAX_FEE_PER_KB + 1, 11000000,
            DD_TRANSFER_RATE_OUT_OF_RANGE);
    refused("an otherwise valid transfer at a rate of zero", ok, 2, 0, 0, 11000000, DD_TRANSFER_RATE_OUT_OF_RANGE);
    refused("an otherwise valid transfer at a rate read from a negative number", ok, 2, 0, (uint64_t)(int64_t)-1, 11000000,
            DD_TRANSFER_RATE_OUT_OF_RANGE);
    tx = transfer(ok, 2, 0);
    ck(bridgeSign(tx, DD_TRANSFER_MAX_FEE_PER_KB, 11000000, &verdict) && verdict == DD_TRANSFER_OK,
       "the same transfer at exactly the highest rate is signed");
    coin_reservation_release(tx->txHash);
    BRTransactionFree(tx);
}

static void duplicate_input_refused(void)
{
    // Every input names a different outpoint.
    const Coin fee[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 7000000 }, { 0x41, 0, 0, 7000000 } };
    refused("a fee coin listed twice", fee, 3, 0, RATE, 14000000, DD_TRANSFER_DUPLICATE_INPUT);
    const Coin dd[] = { { 0x31, 0, -1, 0 }, { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 11000000 } };
    refused("a DigiDollar coin listed twice", dd, 3, 0, RATE, 11000000, DD_TRANSFER_DUPLICATE_INPUT);
    const Coin apart[] = { { 0x31, 0, -1, 0 }, { 0x41, 0, 0, 7000000 }, { 0x42, 1, 1, 5000000 }, { 0x41, 0, 0, 7000000 } };
    refused("a fee coin listed twice with another coin between", apart, 4, 0, RATE, 19000000, DD_TRANSFER_DUPLICATE_INPUT);
}

typedef struct { const char *name; void (*run)(void); } Case;
static const Case kCases[] = {
    { "valid_transfers_sign_unchanged", valid_transfers_sign_unchanged },
    { "fee_below_dd_floor_refused", fee_below_dd_floor_refused },
    { "overspend_refused", overspend_refused },
    { "fee_below_size_required_refused", fee_below_size_required_refused },
    { "fee_above_approved_refused", fee_above_approved_refused },
    { "fee_above_band_refused", fee_above_band_refused },
    { "rate_out_of_range_refused", rate_out_of_range_refused },
    { "duplicate_input_refused", duplicate_input_refused },
};

int main(int argc, char **argv)
{
    size_t n = sizeof(kCases) / sizeof(kCases[0]);
    if (argc < 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < n; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    setup();
    for (size_t i = 0; i < n; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("ARM: %s\n", DD_TRANSFER_GUARD_UNFIXED ? "comparison (DD_TRANSFER_GUARD_UNFIXED=1)" : "shipped");
        kCases[i].run();
        for (size_t k = 0; k < 4; k++) BRKeyClean(&g_keys[k]);
        printf("RESULT %s %s\n", kCases[i].name, g_failed ? "fail" : "pass");
        return g_failed;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
