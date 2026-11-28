// Host KAT for BRWatchDescriptor (digibytewallet-core): the watch-only "cold account" descriptor
// parser that reads a HoldFast Vault's public kit.
//
// VECTORS. Every descriptor and address below was produced by the HoldFast Vault's own generator
// (JohnnyLawDGB/Digibyte-Address-Generator, src/core.js, dgb-coldprep 2.0, branch
// claude/adoring-hypatia-whkzvk at 7bc4506), not by this repo's C code, from the BIP39 test mnemonic
// "abandon x11 about" with no passphrase (master fingerprint 73c5da0a). The generator's Bitcoin BIP84
// receive address #0 for that mnemonic is bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu, the published
// BIP84 test vector, which is the independent anchor for its derivation. Negative cases were built with
// the same generator's withChecksum(), so each one fails on the rule it names and not on its checksum.
//
// WHAT IT PROVES, per address type (P2WPKH, P2TR, P2SH-P2WPKH, P2PKH):
//   (a) the Vault's receive and change descriptors parse, with the right format, purpose, coin type,
//       account, branch and master fingerprint;
//   (b) receive #0..2, change #0..2 and receive #25 derived here equal the Vault's, so a cold account
//       hands out the very addresses the Vault shows;
//   (c) for P2WPKH and P2TR, the parsed account key is byte-identical to the key HoldFast's own wallet
//       derives from that seed (BRBIP32MasterPubKeyBIP84 / BIP86), so a keyless wallet built from the
//       kit is the same wallet as the hot one, address for address;
//   (d) BRWatchDescriptorFindAddress finds receive #25 and change #2, finds an upper-case bech32 copy,
//       and does not find an address from another seed or a Dogecoin address.
// And the refusals: wrong chain (DOGE, BTC, LTC), checksum missing or wrong, private key, purpose
// mismatch, origin missing/malformed/unhardened/too deep, xpub depth or child mismatch, path other
// than /0/* or /1/*, multisig, oversize and NULL input.
//
// RED ARM: -DWATCH_DESCRIPTOR_RED_CHAIN removes the coin-type check. Exactly the wrong-chain cases fail.
//
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <ctype.h>

#include "BRWatchDescriptor.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

typedef struct {
    const char *type;
    const char *receive;
    const char *change;
    uint32_t purpose;
    const char *recv[3];
    const char *chg[3];
    const char *recv25;
} Vec;

static const Vec VECS[] = {
    { "p2wpkh", "wpkh([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#xw9vv378", "wpkh([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/1/*)#h6qd3ywl", 84,
      { "dgb1q9gmf0pv8jdymcly6lz6fl7lf6mhslsd72e2jq8", "dgb1qg2qjk6wgrcqpnr7l2fvy2me9x0xc6czsehk7jt", "dgb1qfqnjvjzumxt0yfvyjalg9ggts8ey85lmuc3wft" },
      { "dgb1quca5eag8pyzhmnqtfg47krt7r4ywn4dgfzela7", "dgb1qy9zzyylmvsu3y59gnzn0ku5q0g0pl7cr42wlzq", "dgb1q58vxsq89k6l8nzzcn5ywjtgzg0t9t8gauf3ra9" },
      "dgb1qfzl7kus67tz6sxhmn5cqnse6z07lqhzaxxtarr" },
    { "p2tr", "tr([73c5da0a/86h/20h/0h]xpub6D3cnMM6nJLD7ztuJzS9g9Y1ABYQngz7WWLUZH8oGZ6FbJGBsXTF4wMuGYjZrHjS4f1Cp7L8jjKiBHnzPBkxFzrGcxbgVpveemmbw455hwS/0/*)#z6f6ngtx", "tr([73c5da0a/86h/20h/0h]xpub6D3cnMM6nJLD7ztuJzS9g9Y1ABYQngz7WWLUZH8oGZ6FbJGBsXTF4wMuGYjZrHjS4f1Cp7L8jjKiBHnzPBkxFzrGcxbgVpveemmbw455hwS/1/*)#nwvmwam7", 86,
      { "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q", "dgb1pr5rw9wm8p0hrt7q2g6n3ncu4k2j9y2mtwes74q7ky3mlrnlmd4jqmxwxw4", "dgb1p4cxeyewyre2nq6ks8ydpmwpawjxlsqavcgjelem02jd2rn4fke8snwpp3r" },
      { "dgb1pedzc2qugc6tf6pfluhvrjqz2e0upaczkanwvec9gj4fl4u9wucys563fpg", "dgb1pq296zua4gmxfkn698jcmztvnym34cxqyr5ddqak575ry8mvz9wjqgtx0dd", "dgb1p3x5fy4r0a8g3lvye3zt6xklznk4mednct4tcw90pe2vht5pu6gxq3gxrug" },
      "dgb1perkukl8cur7hkrz8svqrhmhu6mrrmjtylnc44ja7ucrljmmq9y9qcxfthe" },
    { "p2sh-p2wpkh", "sh(wpkh([73c5da0a/49h/20h/0h]xpub6DLj3kRUU4bB1rWkg6SB5PqduFHae7aPrYaXNFvRia6vqYVQpgLgGoqxxfBXMvzPbV5VtYkf5sA2qbsfE7GSFsCVNnCrGNUFsmEMYMuZdDc/0/*))#05raepzf", "sh(wpkh([73c5da0a/49h/20h/0h]xpub6DLj3kRUU4bB1rWkg6SB5PqduFHae7aPrYaXNFvRia6vqYVQpgLgGoqxxfBXMvzPbV5VtYkf5sA2qbsfE7GSFsCVNnCrGNUFsmEMYMuZdDc/1/*))#fhtczvfa", 49,
      { "SQ9EXABrHztGgefL9aH3FyeRjowdjtLfn4", "SToCoEx2uDXQNnH8WPXi7kGirPBez6Kqmh", "Sh5YJm1aTn7NkUtPRM5cDbVW3UQpzCSr56" },
      { "SMYEz7jv2HpjqaGDM3JAATwXLXRpJi8iDw", "ShKvwPk4Gaxsy5NmJcMkVXiKztcrxUS5nJ", "SkSg3ZjCtdVQ7WWEeKSzraSS9eg6uqVpv6" },
      "SWxbwSp3a3AojkTEwu9M6NeBtRdGbXeFjM" },
    { "p2pkh", "pkh([73c5da0a/44h/20h/0h]xpub6Cj2cdNXaWhn9mwjaxofCJujxrALww7kw6WcyCsGnU9twBEsGcaMqR6gCtQ9b3k6awqL2egNaat2btUCVoETYzcmngU9outdn6RA2KxmNEn/0/*)#a5xmpgaa", "pkh([73c5da0a/44h/20h/0h]xpub6Cj2cdNXaWhn9mwjaxofCJujxrALww7kw6WcyCsGnU9twBEsGcaMqR6gCtQ9b3k6awqL2egNaat2btUCVoETYzcmngU9outdn6RA2KxmNEn/1/*)#vqr6uad9", 44,
      { "DG1KhhBKpsyWXTakHNezaDQ34focsXjN1i", "DMXxrXVmBNqgSZot4bcUa9AjJvTrx2Nvdu", "DFsBvwLTaPJpXH63SLsgWL4k2WtLzf3GDa" },
      { "DRxxhYdXPMKL9y1kZpgLz1GJpaZ23bTcQE", "D65SjH1qM6pUPf2yY8dPbAiMJ8MZcFuUTD", "DTDt5rj25xBXzr6EaQGVJSmokmu5qQDRih" },
      "DJFnLTXZ1BkveCfLeRkTvzpNLnq3rMuW8k" },
};
static const int FORMATS[] = { BR_DERIVATION_FORMAT_P2WPKH, BR_DERIVATION_FORMAT_P2TR, BR_DERIVATION_FORMAT_P2SH_P2WPKH, BR_DERIVATION_FORMAT_P2PKH };

static const struct { const char *receive; const char *a0; } OTHER_SEED[] = {
    { "wpkh([b4e3f5ed/84h/20h/0h]xpub6CAb5qPhfusVo6YpPLRFeqDKjxQ7uHJGUadg7gR3VWzR2sSrupq7EQqieBbEV1SDfbUFoa8kCfcxuGtz92946BnE4y9ABoxjLGd1qLMhuGo/0/*)#tq8eeldy", "dgb1q0svw0cd3scn3xpea0cvjg5vlg5z6dgqgh255fd" },
    { "tr([b4e3f5ed/86h/20h/0h]xpub6Bwy7BMgoXAr5vfnptM9o4G7LcX6YSctWujhbNKnXHutZzGJBQEsRdu3fSrGfWhCcW1fNeBK6BsbyaWUa3uLnN79AZGd37oW873pjqs37yE/0/*)#n9d8s7zl", "dgb1ppk37cv5dngwz34yq8h90y6gjh4pucmr3qstl986d3mcs7f5lydrsm5nppq" },
    { "sh(wpkh([b4e3f5ed/49h/20h/0h]xpub6DDe8QBEDwCaNEZaty5Di7D9MSXgMD1mCdeCVi4L4WUr5A2We6UQMXX3TydrFhmH2xkKjt8SFu82JCk4f3HhFVuygrZff4N6L9tu3323SN9/0/*))#turve9a7", "SgNoLAg3iqB5ywB6EXyoVPB1C2LiiK61yw" },
    { "pkh([b4e3f5ed/44h/20h/0h]xpub6CzjhQnFjsbBD7Q4ZceVtDcULCHPT7CtJq2unjBiTWGMqZSbawX41zXQqJ8ZUZmG5b2vDhUDrvFVKqjhxTXVhg5Lu79itACRUH8yJkTqXua/0/*)#cr673gy5", "DFEP9AsShfncnnpPouRWdWMN5XbV8suKAL" },
};

static void expectResult(const char *desc, BRWatchResult want, const char *label)
{
    BRWatchDescriptor d;
    BRWatchResult got = BRWatchDescriptorParse(&d, desc);
    char msg[200];

    snprintf(msg, sizeof(msg), "%s -> %s (got %s)", label, BRWatchResultName(want), BRWatchResultName(got));
    check(got == want, msg);
}

static void expectWrongChain(const char *desc, uint32_t coin, const char *label)
{
    BRWatchDescriptor d;
    BRWatchResult got = BRWatchDescriptorParse(&d, desc);
    char msg[200];

    snprintf(msg, sizeof(msg), "%s -> wrong_chain, coin type %u (got %s, %u)", label, coin,
             BRWatchResultName(got), d.coinType);
    check(got == BR_WATCH_ERR_WRONG_CHAIN && d.coinType == coin, msg);
}

static int sameMpk(BRMasterPubKey a, BRMasterPubKey b)
{
    return a.fingerPrint == b.fingerPrint && memcmp(&a.chainCode, &b.chainCode, sizeof(a.chainCode)) == 0 &&
           memcmp(a.pubKey, b.pubKey, sizeof(a.pubKey)) == 0;
}

int main(void)
{
    char msg[256], addr[100], fp[9];
    uint8_t seed[64];
    uint32_t c, i;

    BRBIP39DeriveKey(seed, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "");
    BRMasterPubKey hot84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey hot86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    memset(seed, 0, sizeof(seed));

    for (size_t v = 0; v < sizeof(VECS) / sizeof(VECS[0]); v++) {
        const Vec *t = &VECS[v];
        BRWatchDescriptor d, dc;

        BRWatchResult r = BRWatchDescriptorParse(&d, t->receive);
        snprintf(msg, sizeof(msg), "%s receive descriptor parses (got %s)", t->type, BRWatchResultName(r));
        check(r == BR_WATCH_OK, msg);
        if (r != BR_WATCH_OK) continue;

        BRWatchDescriptorFingerprintHex(&d, fp);
        snprintf(msg, sizeof(msg), "%s fields: format, purpose %u, coin 20, account 0, branch 0, fp 73c5da0a", t->type, t->purpose);
        check(d.format == FORMATS[v] && d.purpose == t->purpose && d.coinType == 20 && d.account == 0 &&
              d.branch == 0 && strcmp(fp, "73c5da0a") == 0, msg);

        r = BRWatchDescriptorParse(&dc, t->change);
        snprintf(msg, sizeof(msg), "%s change descriptor parses with branch 1, same key", t->type);
        check(r == BR_WATCH_OK && dc.branch == 1 && sameMpk(d.mpk, dc.mpk), msg);

        for (i = 0; i < 3; i++) {
            size_t n = BRWatchDescriptorAddress(&d, 0, i, addr, sizeof(addr));
            snprintf(msg, sizeof(msg), "%s receive #%u = %s (got %s)", t->type, i, t->recv[i], n ? addr : "nothing");
            check(n == strlen(t->recv[i]) + 1 && strcmp(addr, t->recv[i]) == 0, msg);
            n = BRWatchDescriptorAddress(&d, 1, i, addr, sizeof(addr));
            snprintf(msg, sizeof(msg), "%s change #%u = %s (got %s)", t->type, i, t->chg[i], n ? addr : "nothing");
            check(n == strlen(t->chg[i]) + 1 && strcmp(addr, t->chg[i]) == 0, msg);
        }
        size_t n25 = BRWatchDescriptorAddress(&d, 0, 25, addr, sizeof(addr));
        snprintf(msg, sizeof(msg), "%s receive #25 = %s", t->type, t->recv25);
        check(n25 && strcmp(addr, t->recv25) == 0, msg);

        snprintf(msg, sizeof(msg), "%s finds receive #25 at 0/25", t->type);
        check(BRWatchDescriptorFindAddress(&d, t->recv25, 1000, &c, &i) && c == 0 && i == 25, msg);
        snprintf(msg, sizeof(msg), "%s finds change #2 at 1/2", t->type);
        check(BRWatchDescriptorFindAddress(&d, t->chg[2], 1000, &c, &i) && c == 1 && i == 2, msg);
        snprintf(msg, sizeof(msg), "%s does not find another seed's address #0", t->type);
        check(! BRWatchDescriptorFindAddress(&d, OTHER_SEED[v].a0, 200, &c, &i), msg);

        if (d.format == BR_DERIVATION_FORMAT_P2WPKH || d.format == BR_DERIVATION_FORMAT_P2TR) {
            char upper[100];
            size_t k;
            for (k = 0; t->recv25[k] && k < sizeof(upper) - 1; k++) upper[k] = (char)toupper((unsigned char)t->recv25[k]);
            upper[k] = '\0';
            snprintf(msg, sizeof(msg), "%s finds an upper-case copy of receive #25", t->type);
            check(BRWatchDescriptorFindAddress(&d, upper, 1000, &c, &i) && c == 0 && i == 25, msg);
        } else {
            char lower[100];
            size_t k;
            for (k = 0; t->recv25[k] && k < sizeof(lower) - 1; k++) lower[k] = (char)tolower((unsigned char)t->recv25[k]);
            lower[k] = '\0';
            snprintf(msg, sizeof(msg), "%s does not match a base58 address with its case changed", t->type);
            check(! BRWatchDescriptorFindAddress(&d, lower, 1000, &c, &i), msg);
        }

        if (d.format == BR_DERIVATION_FORMAT_P2WPKH) {
            check(sameMpk(d.mpk, hot84), "p2wpkh kit key equals HoldFast's own BIP84 account key for the seed");
        }
        if (d.format == BR_DERIVATION_FORMAT_P2TR) {
            check(sameMpk(d.mpk, hot86), "p2tr kit key equals HoldFast's own BIP86 account key for the seed");
        }
    }

    // another seed parses, with its own fingerprint
    {
        BRWatchDescriptor d;
        check(BRWatchDescriptorParse(&d, OTHER_SEED[0].receive) == BR_WATCH_OK, "another seed's descriptor parses");
        BRWatchDescriptorFingerprintHex(&d, fp);
        check(strcmp(fp, "b4e3f5ed") == 0, "another seed's fingerprint reads b4e3f5ed");
        check(BRWatchDescriptorAddress(&d, 0, 0, addr, sizeof(addr)) && strcmp(addr, OTHER_SEED[0].a0) == 0,
              "another seed's receive #0 matches the Vault");
    }

    // a Dogecoin address with the same D prefix is not a DigiByte P2PKH address of the seed
    {
        BRWatchDescriptor d;
        BRWatchDescriptorParse(&d, VECS[3].receive);
        check(! BRWatchDescriptorFindAddress(&d, "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", 1000, &c, &i),
              "the seed's Dogecoin address #0 (also D...) is not found in its DigiByte P2PKH account");
    }

    // account 1 is accepted and derives its own addresses
    {
        BRWatchDescriptor d;
        check(BRWatchDescriptorParse(&d, "wpkh([73c5da0a/84h/20h/1h]xpub6BmjNc3e3DmWgppB4YHXb2n5QtJHZjGxnYDEsL5UhwXsGrqMPJvhNMBMi1X22M2RWr5j5BcejcsVL3EaNajwhastDXdX6wKfSehCynw1Esw/0/*)#pdf3yzsh") == BR_WATCH_OK && d.account == 1, "account 1 parses");
        check(BRWatchDescriptorAddress(&d, 0, 0, addr, sizeof(addr)) && strcmp(addr, "dgb1q7zmmuf2np3mnxf2rteuywaxz82w8pmwjgxgcnk") == 0,
              "account 1 receive #0 matches");
    }
    expectResult("wpkh([73c5da0a/84'/20'/0']xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#x7csluyy", BR_WATCH_OK, "apostrophe hardened markers");

    // wrong chain: the chain tag is the origin's coin type
    expectWrongChain("pkh([73c5da0a/44h/3h/0h]dgub8rUhDtD3YFGZTUphBfpBbzvFxSMKQXYLzg87Me2ta78r2SdVLmypBUkkxrrn9RTnchsyiJSkHZyLWxD13ibBiXtuFWktBoDaGaZjQUBLNLs/0/*)#5ek7tjw6", 3, "Dogecoin pkh descriptor");
    expectWrongChain("wpkh([73c5da0a/84h/0h/0h]xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/0/*)#afwvtk2s", 0, "Bitcoin wpkh descriptor");
    expectWrongChain("wpkh([73c5da0a/84h/2h/0h]xpub6CjGURuDpczf6uNrCCwfhVizn5J3hsWcvZ2m6GAdmAjZnoWJPrx6TFPjGSftc2o5fvox6ubQjSXmjjaHZjwYMH7SGFpHHb9Jg24zBf66mbE/0/*)#xy7gfejd", 2, "Litecoin wpkh descriptor");

    // checksum
    {
        char buf[600];
        const char *good = VECS[0].receive;
        size_t len = strlen(good);

        memcpy(buf, good, len - 9);
        buf[len - 9] = '\0';
        expectResult(buf, BR_WATCH_ERR_NO_CHECKSUM, "descriptor without checksum");

        strcpy(buf, good);
        char *x = strstr(buf, "xpub6");
        x[10] = (x[10] == 'a') ? 'b' : 'a';   // one changed character in the key
        expectResult(buf, BR_WATCH_ERR_BAD_CHECKSUM, "one character changed in the key");

        strcpy(buf, good);
        buf[len - 1] = (buf[len - 1] == 'q') ? 'p' : 'q';
        expectResult(buf, BR_WATCH_ERR_BAD_CHECKSUM, "checksum character changed");
    }

    expectResult("wpkh([73c5da0a/84h/20h/0h]xprv9xnNy6WkCrDDTqXcrrQEnd85epTeRzDHyPNAYVENDbDvfxp2etXJNxB7tw7cYVrVRci4KdYsbsHYtdaorxf7NpXX74QDFBKb6dCqsnL4otN/0/*)#dvjn6uef", BR_WATCH_ERR_PRIVATE_KEY, "xprv in place of xpub");
    expectResult("wpkh([73c5da0a/44h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#qf7cl3gq", BR_WATCH_ERR_PURPOSE, "wpkh with a 44h origin");
    expectResult("tr([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#xhqfnzgz", BR_WATCH_ERR_PURPOSE, "tr with an 84h origin");
    expectResult("wpkh(xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#zeg58df8", BR_WATCH_ERR_NO_ORIGIN, "no key origin");
    expectResult("wpkh([73c5da0a/84h/20h/0h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#jldfqctk", BR_WATCH_ERR_ORIGIN, "origin with four levels");
    expectResult("wpkh([73c5da0a/84h/20/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#68vu5xtz", BR_WATCH_ERR_ORIGIN, "origin with an unhardened coin type");
    expectResult("wpkh([73c5da0g/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#8x6xwmq8", BR_WATCH_ERR_ORIGIN, "fingerprint that is not hex");
    expectResult("wpkh([73c5da0a/84h/20h/1h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*)#9a6nrzvc", BR_WATCH_ERR_XPUB_ORIGIN, "origin says account 1, xpub is account 0");
    expectResult("wpkh([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/0)#zlfnzt7y", BR_WATCH_ERR_PATH, "path /0/0 (no wildcard)");
    expectResult("wpkh([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/2/*)#d00wljh7", BR_WATCH_ERR_PATH, "path /2/*");
    expectResult("wpkh([73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*h)#cu6wff7j", BR_WATCH_ERR_PATH, "hardened wildcard /0/*h");
    expectResult("wsh(sortedmulti(2,[73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*,[73c5da0a/84h/20h/0h]xpub6BmjNc3e3DmWgKc5xswF9m4pCrJ8qSw9LcHmLsdymvkuYm9BCRqYvkVbkC8JijGLZDwgG62hysxgAf32EdHVVQjiabWVfJ6xMRe425ph1B2/0/*))#vdgmwazl", BR_WATCH_ERR_SCRIPT, "2-of-2 multisig descriptor");
    expectResult(NULL, BR_WATCH_ERR_NULL, "NULL descriptor");
    {
        char big[BR_WATCH_DESCRIPTOR_MAX_LEN + 2];
        memset(big, 'a', sizeof(big) - 1);
        big[sizeof(big) - 1] = '\0';
        expectResult(big, BR_WATCH_ERR_TOO_LONG, "oversize descriptor");
    }
    check(BRWatchDescriptorParse(NULL, VECS[0].receive) == BR_WATCH_ERR_NULL, "NULL output struct");

    // address argument checks
    {
        BRWatchDescriptor d;
        BRWatchDescriptorParse(&d, VECS[0].receive);
        check(BRWatchDescriptorAddress(&d, 2, 0, addr, sizeof(addr)) == 0, "chain 2 refused");
        check(BRWatchDescriptorAddress(&d, 0, 0x80000000u, addr, sizeof(addr)) == 0, "hardened index refused");
        check(BRWatchDescriptorAddress(&d, 0, 0, addr, 10) == 0, "buffer too small refused");
        check(! BRWatchDescriptorFindAddress(&d, "", 10, &c, &i), "empty address not found");
    }

    printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "ALL PASSED", g_failures);
    return g_failures ? 1 : 0;
}
