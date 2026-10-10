// Host KAT for BRWalletOpen.h -- the one recipe for opening a wallet from its seed.
//
// The defect it gates (digibytewallet-ios docs/port-plan-4.0.89.md, D2): iOS opened
// the wallet with BRWalletNew over the BIP84 key alone, while Android watched BIP84,
// the legacy breadwallet tree (m/0H, P2PKH and P2WPKH) and BIP86 Taproot. Same seed,
// different address set, different balance.
//
// The ORACLE is independent of BRWallet's chain bookkeeping: each tree's first
// external and internal address is derived straight from the seed with the BIP32
// private-key functions and encoded with the BRKey address functions, then the
// opened wallet is asked whether it watches that address. The BIP84 m/84'/20'/0'/0/0
// address is also pinned to a value fixed outside this code -- the one the iOS app
// asserts on launch and that matched Android on device (2026-09-05).
//
// Built twice by run.sh:
//   -DWALLET_OPEN_SINGLE_TREE_UNFIXED  the pre-fix iOS recipe; every RED case fails
//   (default)                          the shipped recipe; every case passes

#include "BRWalletOpen.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRInt.h"
#include <stdio.h>
#include <string.h>

static const char *PHRASE =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *PINNED_BIP84_0_0 = "dgb1q9gmf0pv8jdymcly6lz6fl7lf6mhslsd72e2jq8";

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

// Both chains (external 0, internal 1), index 0, must be watched.
typedef enum { ENC_P2PKH, ENC_P2WPKH, ENC_P2TR } Encoding;
typedef enum { TREE_BIP84, TREE_LEGACY, TREE_BIP86 } Tree;

static void derive(char *addr, size_t len, const UInt512 *seed, Tree tree, uint32_t chain, Encoding enc)
{
    BRKey key;

    if (tree == TREE_BIP84) BRBIP32PrivKeyBIP84(&key, seed->u8, sizeof(*seed), chain, 0);
    else if (tree == TREE_BIP86) BRBIP32PrivKeyBIP86(&key, seed->u8, sizeof(*seed), chain, 0);
    else BRBIP32PrivKey(&key, seed->u8, sizeof(*seed), chain, 0);   // m/0H, "DigiByte seed"

    addr[0] = '\0';
    if (enc == ENC_P2PKH) BRKeyAddress(&key, addr, len);
    else if (enc == ENC_P2WPKH) BRKeySegwitAddress(&key, addr, len, 0);
    else BRKeyTaprootAddress(&key, addr, len);
    BRKeyClean(&key);
}

static int watches_both_chains(BRWallet *w, const UInt512 *seed, Tree tree, Encoding enc, char *missing, size_t mlen)
{
    char addr[96];

    for (uint32_t chain = 0; chain < 2; chain++) {
        derive(addr, sizeof(addr), seed, tree, chain, enc);
        if (addr[0] == '\0' || ! BRWalletContainsAddress(w, addr)) {
            snprintf(missing, mlen, "chain %u index 0 (%s) not watched", chain, addr[0] ? addr : "<no address>");
            return 0;
        }
    }
    return 1;
}

int main(void)
{
    UInt512 seed = UINT512_ZERO;
    char why[160] = "";
    char addr[96];

    BRBIP39DeriveKey(seed.u8, PHRASE, NULL);

    BRWallet *w = BRWalletOpenFromSeed(seed.u8, sizeof(seed), NULL, 0);
    if (! w) { printf("FAIL: wallet did not open\n"); return 1; }

    // ---- GUARD: true of both recipes ------------------------------------------------
    derive(addr, sizeof(addr), &seed, TREE_BIP84, 0, ENC_P2WPKH);
    result("bip84_vector", strcmp(addr, PINNED_BIP84_0_0) == 0, addr);

    why[0] = '\0';
    result("bip84_watched", watches_both_chains(w, &seed, TREE_BIP84, ENC_P2WPKH, why, sizeof(why)), why[0] ? why : NULL);

    BRAddress receive = BRWalletReceiveAddress(w, 1);
    result("receive_is_bip84", strcmp(receive.s, PINNED_BIP84_0_0) == 0, receive.s);

    // Opening from stored PUBLIC keys must watch exactly what opening from the seed does,
    // so a platform may open (and sync) without reading the seed.
    BRWallet *fromKeys = BRWalletOpenWithKeys(NULL, 0, BRWalletMasterKeysFromSeed(seed.u8, sizeof(seed)));
    result("keys_open_matches_seed_open",
           fromKeys && BRWalletAllAddrsCount(fromKeys) == BRWalletAllAddrsCount(w), NULL);

    // ---- RED: the pre-fix BIP84-only recipe watches none of these ---------------------
    why[0] = '\0';
    result("legacy_p2pkh_watched", watches_both_chains(w, &seed, TREE_LEGACY, ENC_P2PKH, why, sizeof(why)), why[0] ? why : NULL);
    why[0] = '\0';
    result("legacy_p2wpkh_watched", watches_both_chains(w, &seed, TREE_LEGACY, ENC_P2WPKH, why, sizeof(why)), why[0] ? why : NULL);
    why[0] = '\0';
    result("bip86_watched", watches_both_chains(w, &seed, TREE_BIP86, ENC_P2TR, why, sizeof(why)), why[0] ? why : NULL);
    result("trees_constant_names_all_three",
           BR_WALLET_OPEN_TREES == (BR_WALLET_TREE_BIP84 | BR_WALLET_TREE_LEGACY | BR_WALLET_TREE_BIP86) &&
           BR_WALLET_OPEN_TREES == 0x7u, NULL);

    if (fromKeys) BRWalletFree(fromKeys);
    BRWalletFree(w);
    memset(&seed, 0, sizeof(seed));

    if (! g_failed) printf("ALL PASS\n");
    return g_failed ? 1 : 0;
}
