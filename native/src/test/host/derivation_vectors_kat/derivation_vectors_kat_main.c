// Host KAT: the core derives exactly the addresses in vectors/derivation/abandon-about.txt.
//
// The vectors come from vectors/derivation/generate.py, an implementation independent of the
// core (Python standard library only; it reproduces the published BIP84, BIP86 and BIP44 test
// vectors). The iOS test target reads the same file. Every address is checked three ways:
//   from the master PUBLIC key, as the wallet derives what it watches (BRBIP32PubKey);
//   from the PRIVATE key the wallet signs with (BRBIP32PrivKey*), so watched == spendable;
//   contained in a wallet built by the shared recipe (BRWalletNewDual + BRWalletSetTaprootKey).
//
// Built twice by run.sh:
//   -DDERIVATION_VECTORS_RED  plausible porting mistakes: BIP84 over the legacy master key,
//                             Taproot over the BIP84 key (the documented fund-loss bug),
//                             legacy receive and change swapped, and the single-tree wallet
//                             (BRWalletNew over BIP84 alone). Every RED case fails.
//   (default)                 the core as shipped; every case passes

#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"
#include "BRAddress.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { char tree[8], fmt[8]; uint32_t chain, index; char addr[96]; } Vector;

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static int address(BRKey *key, const char *fmt, char *out, size_t outLen)
{
    if (strcmp(fmt, "p2wpkh") == 0) return BRKeySegwitAddress(key, out, outLen, OP_0) > 0;
    if (strcmp(fmt, "p2tr") == 0) return BRKeyTaprootAddress(key, out, outLen) > 0;
    if (strcmp(fmt, "p2pkh") == 0) return BRKeyAddress(key, out, outLen) > 0;
    return 0;
}

int main(int argc, char *argv[])
{
    static Vector v[512];
    size_t n = 0, i;
    char line[512], mnemonic[256] = "", passphrase[256] = "", seedHex[160] = "", why[300];
    FILE *f;
    UInt512 seed;

    if (argc < 2 || ! (f = fopen(argv[1], "r"))) { printf("FAIL: cannot open the vector file\n"); return 1; }
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "# mnemonic: %255[^\n]", mnemonic) == 1) continue;
        if (sscanf(line, "# passphrase: %255[^\n]", passphrase) == 1) continue;
        if (sscanf(line, "# bip39 seed: %159s", seedHex) == 1) continue;
        if (line[0] == '#' || line[0] == '\n') continue;
        if (n < sizeof(v) / sizeof(v[0]) &&
            sscanf(line, "%7s %7s %u %u %95s", v[n].tree, v[n].fmt, &v[n].chain, &v[n].index, v[n].addr) == 5) n++;
    }
    fclose(f);
    snprintf(why, sizeof(why), "%zu rows, mnemonic %s", n, mnemonic[0] ? "found" : "MISSING");
    result("vectors_parsed", n == 160 && mnemonic[0], why);

    // BIP39: the seed the whole file hangs from ("(empty)" is no passphrase).
    BRBIP39DeriveKey(seed.u8, mnemonic, strcmp(passphrase, "(empty)") == 0 ? NULL : passphrase);
    {
        char hex[129];
        for (i = 0; i < 64; i++) snprintf(hex + 2 * i, 3, "%02x", seed.u8[i]);
        result("bip39_seed", strcmp(hex, seedHex) == 0, hex);
    }

    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed.u8, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed.u8, sizeof(seed));
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed.u8, sizeof(seed));

    static const struct { const char *tree, *fmt, *pubCase, *privCase; } kinds[] = {
        { "bip84", "p2wpkh", "bip84_from_master_pubkey", "bip84_from_signing_key" },
        { "bip86", "p2tr", "bip86_from_master_pubkey", "bip86_from_signing_key" },
        { "legacy", "p2pkh", "legacy_p2pkh_from_master_pubkey", "legacy_p2pkh_from_signing_key" },
        { "legacy", "p2wpkh", "legacy_p2wpkh_from_master_pubkey", "legacy_p2wpkh_from_signing_key" },
    };

    for (size_t k = 0; k < sizeof(kinds) / sizeof(kinds[0]); k++) {
        int pubOK = 1, privOK = 1, seen = 0;
        char firstBad[200] = "";
        for (i = 0; i < n; i++) {
            if (strcmp(v[i].tree, kinds[k].tree) != 0 || strcmp(v[i].fmt, kinds[k].fmt) != 0) continue;
            seen++;
            uint32_t chain = v[i].chain, index = v[i].index;
            BRMasterPubKey mpk = (strcmp(v[i].tree, "bip84") == 0) ? mpk84
                               : (strcmp(v[i].tree, "bip86") == 0) ? mpk86 : mpkLegacy;
#ifdef DERIVATION_VECTORS_RED
            if (strcmp(v[i].tree, "bip84") == 0) mpk = mpkLegacy;      // the wrong tree
            if (strcmp(v[i].tree, "bip86") == 0) mpk = mpk84;          // P2TR over m/84': fund loss
            if (strcmp(v[i].tree, "legacy") == 0) chain ^= 1;          // receive and change swapped
#endif
            uint8_t pub[65];
            size_t len = BRBIP32PubKey(pub, sizeof(pub), mpk, chain, index);
            BRKey key;
            char a[96] = "";
            if (! BRKeySetPubKey(&key, pub, len) || ! address(&key, v[i].fmt, a, sizeof(a)) || strcmp(a, v[i].addr) != 0) {
                if (pubOK) snprintf(firstBad, sizeof(firstBad), "%s %s %u/%u: got %s, want %s", v[i].tree, v[i].fmt, v[i].chain, v[i].index, a, v[i].addr);
                pubOK = 0;
            }

            BRKey priv;
            memset(&priv, 0, sizeof(priv));
#ifdef DERIVATION_VECTORS_RED
            if (strcmp(v[i].tree, "bip84") == 0) BRBIP32PrivKey(&priv, seed.u8, sizeof(seed), chain, index);
            else if (strcmp(v[i].tree, "bip86") == 0) BRBIP32PrivKeyBIP84(&priv, seed.u8, sizeof(seed), chain, index);
            else BRBIP32PrivKey(&priv, seed.u8, sizeof(seed), chain, index);
#else
            if (strcmp(v[i].tree, "bip84") == 0) BRBIP32PrivKeyBIP84(&priv, seed.u8, sizeof(seed), chain, index);
            else if (strcmp(v[i].tree, "bip86") == 0) BRBIP32PrivKeyBIP86(&priv, seed.u8, sizeof(seed), chain, index);
            else BRBIP32PrivKey(&priv, seed.u8, sizeof(seed), chain, index);
#endif
            char b[96] = "";
            if (! address(&priv, v[i].fmt, b, sizeof(b)) || strcmp(b, v[i].addr) != 0) privOK = 0;
            BRKeyClean(&priv);
        }
        snprintf(why, sizeof(why), "%d vectors; %s", seen, firstBad[0] ? firstBad : "pubkey path matched");
        result(kinds[k].pubCase, seen == 40 && pubOK, why);
        snprintf(why, sizeof(why), "%d vectors", seen);
        result(kinds[k].privCase, seen == 40 && privOK, why);
    }

    // The shared recipe watches every vector inside its gap windows. BIP84 change is generated
    // SEQUENCE_GAP_LIMIT_INTERNAL_BIP84 (10) ahead of use, so a fresh wallet watches 0..9 of it.
    {
#ifdef DERIVATION_VECTORS_RED
        BRWallet *w = BRWalletNew(NULL, 0, mpk84);                     // the single-tree wallet
#else
        BRWallet *w = BRWalletNewDual(NULL, 0, mpk84, mpkLegacy);
        if (w) BRWalletSetTaprootKey(w, mpk86);
#endif
        size_t expected = 0, contained = 0;
        char firstMissing[200] = "";
        for (i = 0; w && i < n; i++) {
            if (strcmp(v[i].tree, "bip84") == 0 && v[i].chain == 1 && v[i].index >= SEQUENCE_GAP_LIMIT_INTERNAL_BIP84) continue;
            expected++;
            if (BRWalletContainsAddress(w, v[i].addr)) contained++;
            else if (! firstMissing[0]) snprintf(firstMissing, sizeof(firstMissing), "first missing: %s %s %u/%u", v[i].tree, v[i].fmt, v[i].chain, v[i].index);
        }
        snprintf(why, sizeof(why), "%zu of %zu watched; %s", contained, expected, firstMissing[0] ? firstMissing : "none missing");
        result("recipe_wallet_watches_every_vector", w && expected == 150 && contained == expected, why);
        if (w) BRWalletFree(w);
    }

    memset(&seed, 0, sizeof(seed));
    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
