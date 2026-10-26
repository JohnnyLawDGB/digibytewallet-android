// Host KAT: Recover funds derives the taproot addresses this wallet hands out (4.1.2 row 34).
//   The scan derives a profile's addresses from its public key path (deriveAddresses: the master public
//   key at hmac + prefix, then chain/index, then pubkey_to_address). For the BIP86 profile -- "Bitcoin
//   seed", m/86'/20'/0', format 3 -- each address must be the one the wallet's own Receive gives for that
//   index (BRBIP32PrivKeyBIP86 -> BRKeyTaprootAddress), or the scan looks at addresses nobody was paid at.
//   run.sh also pins that deriveAddresses accepts format 3 (it refused anything above 2).
#include <stdio.h>
#include <string.h>
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRKey.h"

static int g_fail = 0;
static void ck(int c, const char *d) { printf(c ? "  ok   %s\n" : "  FAIL %s\n", d); if (!c) g_fail++; }

int main(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", NULL);
    const uint32_t path[3] = { 86 | BIP32_HARD, 20 | BIP32_HARD, 0 | BIP32_HARD };
    BRMasterPubKey mpk = BRBIP32MasterPubKeyPath(seed, sizeof(seed), "Bitcoin seed", path, 3);
    for (uint32_t chain = 0; chain < 2; chain++) {
        for (uint32_t index = 0; index < 5; index++) {
            uint8_t pub[33];
            size_t n = BRBIP32PubKey(pub, sizeof(pub), mpk, chain, index);
            BRKey fromPub, fromPriv;
            char a[100] = "", b[100] = "";
            BRKeySetPubKey(&fromPub, pub, n);
            BRKeyTaprootAddress(&fromPub, a, sizeof(a));
            BRBIP32PrivKeyBIP86(&fromPriv, seed, sizeof(seed), chain, index);
            BRKeyTaprootAddress(&fromPriv, b, sizeof(b));
            char what[96];
            snprintf(what, sizeof(what), "m/86'/20'/0'/%u/%u: the scan's address is the wallet's own", chain, index);
            ck(a[0] && strncmp(a, "dgb1p", 5) == 0 && strcmp(a, b) == 0, what);
        }
    }
    printf(g_fail ? "\n%d FAIL\n" : "\nPASS: recover_taproot_kat\n", g_fail);
    return g_fail ? 1 : 0;
}
