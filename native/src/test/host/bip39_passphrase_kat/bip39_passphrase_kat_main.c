// BIP39 passphrase known-answer test.
//
// WHY THIS EXISTS
//
// The wallet is adding an OPTIONAL BIP39 passphrase (the "12+1 / 24+1" word).
// BRBIP39DeriveKey already takes a passphrase argument; every wallet call site
// currently passes NULL. Before that changes, three properties need pinning:
//
//   1. INTEROP. A passphrase wallet is only useful if the phrase + passphrase
//      restore somewhere else. Self-consistency proves nothing — the anchor has
//      to be the OFFICIAL BIP39 test vectors, which every other implementation
//      is also checked against. These come from the BIP39 specification's own
//      vectors.json (passphrase "TREZOR"). Every constant below was CROSS-CHECKED
//      against an independent implementation — Python's hashlib.pbkdf2_hmac —
//      before being pinned here, not read back out of this C. That check earned
//      its keep immediately: a mis-transcribed 24-word constant failed on the
//      first run while the 12-word one passed, and Python confirmed the C was
//      right and the constant was wrong.
//
//   2. NO REGRESSION FOR EXISTING WALLETS. Every wallet created so far derived
//      with passphrase=NULL. If NULL and "" ever stopped agreeing, or if the
//      no-passphrase seed changed, every existing user's addresses would move
//      and their balance would read zero. Cases 3 and 4 pin that.
//
//   3. NORMALISATION IS THE CALLER'S JOB. BRBIP39Mnemonic.h says "phrase and
//      passphrase must be unicode NFKD normalized" and the implementation does
//      no normalisation — it concatenates and runs PBKDF2. Case 5 DEMONSTRATES
//      that by showing a composed and a decomposed spelling of the same
//      passphrase derive DIFFERENT seeds. It is not a bug in C; it is the
//      reason the Kotlin layer must normalise before calling in. If case 5 ever
//      starts failing, the C began normalising and the Kotlin side should be
//      revisited rather than left double-normalising.
//
// A note on case 5's inputs: "é" has two Unicode spellings — U+00E9 (composed,
// NFC) and U+0065 U+0301 (e + combining acute, decomposed, NFD/NFKD). A user
// typing the same visible passphrase on two platforms can produce either.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRBIP39Mnemonic.h"

static int failures = 0;

static void hex(char *out, const uint8_t *b, size_t n) {
    for (size_t i = 0; i < n; i++) sprintf(out + i * 2, "%02x", b[i]);
    out[n * 2] = '\0';
}

static void expect_seed(const char *name, const char *phrase,
                        const char *passphrase, const char *want) {
    uint8_t seed[64];
    char got[129];
    BRBIP39DeriveKey(seed, phrase, passphrase);
    hex(got, seed, sizeof(seed));
    if (strcmp(got, want) == 0) {
        printf("PASS  %s\n", name);
    } else {
        printf("FAIL  %s\n      want %s\n      got  %s\n", name, want, got);
        failures++;
    }
}

static void expect_same(const char *name, const char *phrase,
                        const char *pa, const char *pb) {
    uint8_t a[64], b[64];
    char ha[129], hb[129];
    BRBIP39DeriveKey(a, phrase, pa);
    BRBIP39DeriveKey(b, phrase, pb);
    hex(ha, a, sizeof(a));
    hex(hb, b, sizeof(b));
    if (strcmp(ha, hb) == 0) {
        printf("PASS  %s\n", name);
    } else {
        printf("FAIL  %s\n      %s\n      %s\n", name, ha, hb);
        failures++;
    }
}

static void expect_differ(const char *name, const char *phrase,
                          const char *pa, const char *pb) {
    uint8_t a[64], b[64];
    char ha[129], hb[129];
    BRBIP39DeriveKey(a, phrase, pa);
    BRBIP39DeriveKey(b, phrase, pb);
    hex(ha, a, sizeof(a));
    hex(hb, b, sizeof(b));
    if (strcmp(ha, hb) != 0) {
        printf("PASS  %s\n", name);
    } else {
        printf("FAIL  %s (expected different seeds, both were %s)\n", name, ha);
        failures++;
    }
}

int main(void) {
    const char *m12 =
        "abandon abandon abandon abandon abandon abandon "
        "abandon abandon abandon abandon abandon about";
    const char *m24 =
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo "
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote";

    printf("== BIP39 passphrase KAT ==\n");

    // 1-2: official BIP39 vectors, passphrase "TREZOR". The interop anchor.
    expect_seed("official 12-word + TREZOR", m12, "TREZOR",
        "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955"
        "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04");
    expect_seed("official 24-word + TREZOR", m24, "TREZOR",
        "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e16"
        "13912f0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad");

    // 3: the no-passphrase seed every existing wallet already derives.
    expect_seed("12-word, no passphrase (existing wallets)", m12, NULL,
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1"
        "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4");

    expect_seed("24-word, no passphrase (existing wallets)", m24, NULL,
        "e28a37058c7f5112ec9e16a3437cf363a2572d70b6ceb3b6965447623d620f14"
        "d06bb321a26b33ec15fcd84a3b5ddfd5520e230c924c87aaa0d559749e044fef");

    // 4: NULL and "" must stay interchangeable — the whole "optional" claim
    //    rests on an absent passphrase and an empty one being the same wallet.
    expect_same("NULL and empty string agree", m12, NULL, "");

    // 5: the C does NOT normalise. This is why Kotlin must (spec R2).
    expect_differ("composed vs decomposed differ (normalise in Kotlin!)",
                  m12, "caf\xc3\xa9", "cafe\xcc\x81");

    // 6: a different passphrase is a different wallet. Stated because it is the
    //    entire hazard of a typo: not an error, a valid empty wallet.
    expect_differ("a typo is a different wallet", m12, "TREZOR", "TREZ0R");

    printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASS\n", failures);
    return failures ? 1 : 0;
}
