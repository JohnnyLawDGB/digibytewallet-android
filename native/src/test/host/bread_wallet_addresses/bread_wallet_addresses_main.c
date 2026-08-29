// Host tool: derive the addresses a pre-BIP84 DigiByte BreadWallet would hand out.
//
// WHY THIS EXISTS. Universal Restore claims to recover a legacy BreadWallet — "Legacy DigiByte
// mobile wallet", m/0' with the "DigiByte seed" HMAC, P2PKH — but that claim has never been
// tested against a wallet actually funded on that path. To test it you first need such a wallet,
// and no current software will make you one: the app only creates BIP84.
//
// So this derives the same addresses the scan will look for, using the SAME C the scan uses
// (BRBIP32MasterPubKeyPath -> BRBIP32PubKey -> BRAddressFromKey). Deriving them any other way —
// a Python script, a web tool — would prove only that two implementations agree with each other,
// and the "DigiByte seed" HMAC is exactly the non-standard detail a third-party tool gets wrong.
//
// Usage:  bread_wallet_addresses "<12 or 24 word mnemonic>" [count]
//
// Prints external (receive) then internal (change) addresses with their paths.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#include "BRBIP39Mnemonic.h"
#include "BRBIP32Sequence.h"
#include "BRAddress.h"
#include "BRKey.h"
#include "BRBIP39WordsEn.h"
#include "BRBase58.h"
#include <time.h>

#define BIP32_HARD 0x80000000u

/* The built-in profiles, mirroring DerivationProfile.BUILT_INS. Kept in the same order and with
 * the same names so a mismatch between this tool and the app is visible at a glance rather than
 * discovered by a test wallet that scans as empty. */
typedef struct { const char *key; const char *label; const char *hmac; uint32_t path[3]; int pathLen; int fmt; } Profile;
static const Profile PROFILES[] = {
    { "bread",  "Legacy DigiByte mobile wallet", "DigiByte seed", { 0|BIP32_HARD }, 1, 0 },
    { "bread-std", "Legacy m/0H with standard HMAC", "Bitcoin seed", { 0|BIP32_HARD }, 1, 0 },
    { "bip44",  "BIP44 DGB (Coinomi/Trezor/Ledger)", "Bitcoin seed",
      { 44|BIP32_HARD, 20|BIP32_HARD, 0|BIP32_HARD }, 3, 0 },
    { "bip44-btc", "BIP44 wrong-coin accident", "Bitcoin seed",
      { 44|BIP32_HARD, 0|BIP32_HARD, 0|BIP32_HARD }, 3, 0 },
    { "bip84",  "BIP84 DGB (current wallet)", "Bitcoin seed",
      { 84|BIP32_HARD, 20|BIP32_HARD, 0|BIP32_HARD }, 3, 1 },
    { "bip84-legacy", "BIP84 key, Legacy (D...) encoding", "Bitcoin seed",
      { 84|BIP32_HARD, 20|BIP32_HARD, 0|BIP32_HARD }, 3, 0 },
    { "bip49",  "BIP49 DGB (P2SH-wrapped segwit)", "Bitcoin seed",
      { 49|BIP32_HARD, 20|BIP32_HARD, 0|BIP32_HARD }, 3, 2 },
};
#define NPROFILES ((int)(sizeof(PROFILES)/sizeof(PROFILES[0])))

/* Copied verbatim from jni_derive.c's pubkey_to_address. Deliberately a copy rather than a
 * reimplementation: if this ever disagrees with production, the addresses this tool prints are
 * not the addresses the scan will look for, and a funded test wallet scans as empty. */
static int pubkey_to_address(BRKey *key, int addressFormat, char *out, size_t outLen) {
    if (outLen > 0) out[0] = '\0';
    switch (addressFormat) {
        case 0: { size_t n = BRKeyAddress(key, out, outLen); return (n > 0 && n <= outLen); }
        case 1: { size_t n = BRKeySegwitAddress(key, out, outLen, OP_0); return (n > 0 && n <= outLen); }
        case 2: {
            UInt160 pkh = BRKeyHash160(key);
            uint8_t witness[22];
            witness[0] = 0x00; witness[1] = 0x14;
            memcpy(&witness[2], &pkh, 20);
            UInt160 scriptHash;
            BRHash160(&scriptHash, witness, sizeof(witness));
            uint8_t data[21];
            data[0] = DIGIBYTE_SCRIPT_ADDRESS;
            memcpy(&data[1], &scriptHash, 20);
            size_t n = BRBase58CheckEncode(out, outLen, data, sizeof(data));
            return (n > 0 && n <= outLen);
        }
        default: return 0;
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s \"<mnemonic>\"|--generate [count] [profile]\n", argv[0]);
        fprintf(stderr, "profiles:");
        for (int i = 0; i < NPROFILES; i++) fprintf(stderr, " %s", PROFILES[i].key);
        fprintf(stderr, "\n");
        return 2;
    }
    char generated[256] = {0};
    const char *mnemonic = argv[1];

    /* --generate makes a FRESH random phrase. Deliberately not a published test vector: the
     * "abandon abandon ... about" mnemonic is the best-known seed in existence and anything
     * sent to its addresses is swept by bots within seconds. A wallet we are about to fund on
     * mainnet has to be one nobody else holds. */
    if (strcmp(mnemonic, "--generate") == 0) {
        uint8_t entropy[16];
        FILE *f = fopen("/dev/urandom", "rb");
        if (!f || fread(entropy, 1, sizeof(entropy), f) != sizeof(entropy)) {
            fprintf(stderr, "could not read entropy\n");
            return 1;
        }
        fclose(f);
        size_t n = BRBIP39Encode(NULL, 0, BRBIP39WordsEn, entropy, sizeof(entropy));
        if (n == 0 || n >= sizeof(generated)) { fprintf(stderr, "encode failed\n"); return 1; }
        BRBIP39Encode(generated, sizeof(generated), BRBIP39WordsEn, entropy, sizeof(entropy));
        memset(entropy, 0, sizeof(entropy));
        if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, generated)) {
            fprintf(stderr, "generated phrase failed its own checksum\n");
            return 1;
        }
        mnemonic = generated;
        printf("MNEMONIC (write this down — it is the only copy):\n  %s\n\n", generated);
    } else if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, mnemonic)) {
        fprintf(stderr, "that phrase is not a valid BIP39 mnemonic\n");
        return 1;
    }
    const int count = (argc > 2) ? atoi(argv[2]) : 3;
    const char *want = (argc > 3) ? argv[3] : "bread";
    const Profile *prof = NULL;
    for (int i = 0; i < NPROFILES; i++) if (strcmp(PROFILES[i].key, want) == 0) prof = &PROFILES[i];
    if (!prof) { fprintf(stderr, "unknown profile: %s\n", want); return 2; }

    /* BIP39 seed. No passphrase — a bread-era wallet had no passphrase concept. */
    UInt512 seed;
    BRBIP39DeriveKey(seed.u8, mnemonic, NULL);

    /* m/0' under the DigiByte-fork HMAC. This is the whole difference from a standard
     * wallet: same seed, same curve, different HMAC key, therefore different addresses. */
    uint32_t path[3];
    for (int i = 0; i < prof->pathLen; i++) path[i] = prof->path[i];
    BRMasterPubKey mpk = BRBIP32MasterPubKeyPath(
        &seed, sizeof(seed), prof->hmac, path, (size_t)prof->pathLen);

    printf("profile : %s\n", prof->label);
    printf("path    : m");
    for (int i = 0; i < prof->pathLen; i++) printf("/%u'", prof->path[i] & 0x7fffffffu);
    printf("   hmac \"%s\"   format %s\n\n", prof->hmac,
           prof->fmt == 0 ? "P2PKH (D...)" : prof->fmt == 1 ? "P2WPKH (dgb1q...)" : "P2SH-P2WPKH (S...)");

    uint8_t pub[33];
    BRKey key;
    for (int chain = 0; chain < 2; chain++) {
        printf("%s (chain %d):\n", chain == 0 ? "RECEIVE" : "CHANGE ", chain);
        for (int i = 0; i < count; i++) {
            size_t pubLen = BRBIP32PubKey(pub, sizeof(pub), mpk, (uint32_t)chain, (uint32_t)i);
            if (pubLen == 0) { printf("  %d: <derivation skipped>\n", i); continue; }
            BRKeySetPubKey(&key, pub, pubLen);
            char addr[91];
            int ok = pubkey_to_address(&key, prof->fmt, addr, sizeof(addr));
            printf("  .../%d/%-3d  %s\n", chain, i, ok ? addr : "<encode failed>");
            BRKeyClean(&key);
        }
        printf("\n");
    }

    memset(&seed, 0, sizeof(seed));
    return 0;
}
