// Host KAT (BIP39-24W-VECTORS): a 24-word recovery phrase gives exactly the addresses an independent
// implementation computes, for every address type the wallet derives.
//
// INVARIANT. The committed KAT set holds address vectors for 24-word recovery phrases, so a change that
// breaks 24-word derivation fails CI. derivation_vectors_kat already holds the wallet's own trees to one
// 24-word phrase (the core's vectors/derivation/abandon-art*.txt). This suite adds the rest: all seven
// derivation profiles Recover funds probes (BIP84 as P2WPKH and P2PKH, the legacy m/0' tree under both
// master keys, BIP44 with coin 20 and coin 0, BIP49 P2SH-P2WPKH, BIP86 P2TR -- the DigiDollar path), for
// four published 24-word BIP39 test phrases, each without a passphrase and with "TREZOR". Mainnet.
//
// The expected values are vectors.txt beside this file, written by generate.py: Python's hashlib for the
// seed (and the BIP39 specification's published seeds), the core repo's independent.py for BIP32 and the
// encodings. Nothing in it is this wallet's output. argv[1] is that file; the phrases are read from it.
//
// Cases (RESULT <case> pass|fail), s = 0..7 (odd s has the passphrase), p = 0..6:
//   GUARDS   vectors_parsed     8 phrases, 4 without and 4 with a passphrase; 1280 rows, each (seed, profile,
//                               format) either absent or the 10 receive + 10 change rows, once each.
//            phrases_24_words   the core accepts each phrase as BIP39 (BRBIP39PhraseIsValid, what restore
//                               asks), counts 24 words, decodes 32 bytes of entropy and re-encodes it.
//   seed_<s>          the core's two seed functions (BRBIP39DeriveKey, which create/restore call, and
//                     BRRecoveryMnemonicToSeed) and the bridge's mnemonicToSeed give the file's seed.
//   core_s<s>_p<p>    the core's profile table through BRRecoveryDeriveAddresses, from the seed the core
//                     derived from the phrase: every format the table probes equals the file, and the file
//                     has no format the table does not probe.
//   bridge_s<s>_p<p>  the same through Android's bridge (jni_derive.c) with Kotlin's table (BUILT_INS),
//                     from the seed the bridge derived from the phrase.
//   wallet_s<s>       the wallet's own trees, as create/restore build them: BIP84, BIP86 and legacy
//                     addresses from the master PUBLIC keys (what is watched) and from the signing keys
//                     (what can be spent), and a wallet built by the shared recipe watches them.
//
// Built five times by run.sh. The two macros below live in this file only (comparison arms of the KAT):
//   -DBIP39_24W_TRUNCATE_RED       a wrong word-count handling: only the first 12 words of each phrase
//                                  reach the seed. Every seed_, core_, bridge_ and wallet_ case fails.
//   -DBIP39_24W_NO_PASSPHRASE_RED  the passphrase is dropped. Every case of an odd seed fails; even seeds pass.
//   -DDERIVATION_PROFILES_RED, -DRECOVERY_ENCODER_RED  the core's own seams: only core_s<s>_p5 fails.
//   (default)                      everything as shipped; every case passes.

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jni.h"
#include "BRRecoveryDerive.h"
#include "BRBIP39Mnemonic.h"
#include "BRBIP39WordsEn.h"
#include "BRWallet.h"
#include "kotlin_profiles.h"
#include "wallet_spend_gate.h"   /* jni_derive.c's one wallet question, stubbed below */

jbyteArray JNICALL NativeBridge_mnemonicToSeed(JNIEnv *env, jobject thiz, jbyteArray phraseBytes, jbyteArray passphrase);
jobjectArray JNICALL NativeBridge_deriveAddresses(JNIEnv *env, jobject thiz, jbyteArray seedBytes, jstring hmacKey,
                                                  jintArray prefixPath, jint gapExternal, jint gapInternal,
                                                  jint addressFormat);

// jni_derive.c's signers ask jni_wallet.c's gate; nothing here signs.
int wallet_signed_spend_gate(BRTransaction *tx, const char *who) { (void)tx; (void)who; return 0; }
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }

#include "host_env.h"   /* derivation_profiles_kat's host JNIEnv (ENV), its arena and the h* constructors */

#define SEED_COUNT 8
#define PER_CHAIN  10
#define ROWS_MAX   2048

typedef struct { char phrase[400], pass[130], seedHex[160]; int hasPhrase, hasPass, hasSeed; } SeedRow;
typedef struct { unsigned s, p, chain, index; int fmt; char addr[100]; } Row;

static SeedRow g_seed[SEED_COUNT];
static Row g_rows[ROWS_MAX];
static size_t g_rowCount = 0;

static int g_caseFail = 0, g_anyFail = 0;
static void failf(const char *c, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void failf(const char *c, const char *fmt, ...)
{
    va_list ap;

    if (g_caseFail++ < 3) {
        printf("FAIL: %s: ", c);
        va_start(ap, fmt);
        vprintf(fmt, ap);
        va_end(ap);
        printf("\n");
    }
}
static void begin(void) { g_caseFail = 0; }
static void end(const char *c)
{
    printf("RESULT %s %s\n", c, g_caseFail ? "fail" : "pass");
    if (g_caseFail) g_anyFail = 1;
}

static void tohex(const uint8_t *b, size_t n, char *out) { for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]); }

// The file's address for one position, or NULL when the file has no such row.
static const char *row_addr(unsigned s, unsigned p, int fmt, unsigned chain, unsigned index)
{
    for (size_t i = 0; i < g_rowCount; i++) {
        const Row *r = &g_rows[i];

        if (r->s == s && r->p == p && r->fmt == fmt && r->chain == chain && r->index == index) return r->addr;
    }
    return NULL;
}

static size_t rows_for(unsigned s, unsigned p, int fmt)
{
    size_t n = 0;

    for (size_t i = 0; i < g_rowCount; i++) if (g_rows[i].s == s && g_rows[i].p == p && g_rows[i].fmt == fmt) n++;
    return n;
}

/* ---- what reaches the seed functions: the file's phrase and passphrase, except in the two comparison arms ---- */

static const char *used_phrase(unsigned s, char buf[400])
{
    strcpy(buf, g_seed[s].phrase);
#ifdef BIP39_24W_TRUNCATE_RED
    {   // COMPARISON ARM: a wrong word-count handling, the first 12 words only
        size_t words = 0;

        for (char *q = buf; *q; q++) if (*q == ' ' && ++words == 12) { *q = '\0'; break; }
    }
#endif
    return buf;
}

static const char *used_pass(unsigned s)
{
#ifdef BIP39_24W_NO_PASSPHRASE_RED
    (void)s;
    return "";                       // COMPARISON ARM: the passphrase never reaches the seed
#else
    return g_seed[s].pass;
#endif
}

static int core_recovery_seed(unsigned s, uint8_t out[64])
{
    char buf[400];
    const char *ph = used_phrase(s, buf), *pa = used_pass(s);
    int ok = BRRecoveryMnemonicToSeed(out, ph, strlen(ph), pa, strlen(pa));

    memset(buf, 0, sizeof(buf));
    return ok;
}

static int bridge_seed(unsigned s, uint8_t out[64])
{
    char buf[400];
    const char *ph = used_phrase(s, buf), *pa = used_pass(s);
    jbyteArray r = NativeBridge_mnemonicToSeed(ENV, NULL, hbytes(ph, strlen(ph)), pa[0] ? hbytes(pa, strlen(pa)) : NULL);
    int ok = r && ((HObj *)r)->len == 64;

    if (ok) memcpy(out, ((HObj *)r)->data, 64);
    arena_free();
    memset(buf, 0, sizeof(buf));
    return ok;
}

// What create and restore call (jni_wallet.c): BRBIP39DeriveKey, NULL for no passphrase.
static void wallet_seed(unsigned s, uint8_t out[64])
{
    char buf[400];
    const char *ph = used_phrase(s, buf), *pa = used_pass(s);

    BRBIP39DeriveKey(out, ph, pa[0] ? pa : NULL);
    memset(buf, 0, sizeof(buf));
}

/* ---- cases ---------------------------------------------------------------------------------------------- */

static void parse(const char *path)
{
    FILE *fp = path ? fopen(path, "r") : NULL;
    char line[600];
    size_t bad = 0, without = 0, with = 0;

    begin();
    if (! fp) { failf("vectors_parsed", "cannot open %s", path ? path : "(no file given)"); end("vectors_parsed"); return; }
    while (fgets(line, sizeof(line), fp)) {
        unsigned s;
        int at = 0;
        char hex[160];
        Row r;

        line[strcspn(line, "\r\n")] = '\0';
        if (line[0] == '#' || line[0] == '\0') continue;
        if (sscanf(line, "phrase %u %n", &s, &at) == 1 && at > 0 && s < SEED_COUNT && strlen(line + at) < sizeof(g_seed[s].phrase)) {
            strcpy(g_seed[s].phrase, line + at);
            g_seed[s].hasPhrase++;
        }
        else if (sscanf(line, "passphrase %u %n", &s, &at) == 1 && at > 0 && s < SEED_COUNT && strlen(line + at) < sizeof(g_seed[s].pass)) {
            strcpy(g_seed[s].pass, strcmp(line + at, "(empty)") == 0 ? "" : line + at);
            g_seed[s].hasPass++;
        }
        else if (sscanf(line, "seed %u %159s", &s, hex) == 2 && s < SEED_COUNT) {
            strcpy(g_seed[s].seedHex, hex);
            g_seed[s].hasSeed++;
        }
        else if (sscanf(line, "addr %u %u %d %u %u %99s", &r.s, &r.p, &r.fmt, &r.chain, &r.index, r.addr) == 6 &&
                 r.s < SEED_COUNT && r.p < BR_DERIVATION_PROFILE_COUNT && r.fmt >= 0 && r.fmt < BR_DERIVATION_FORMAT_COUNT &&
                 r.chain <= 1 && r.index < PER_CHAIN && g_rowCount < ROWS_MAX) g_rows[g_rowCount++] = r;
        else if (bad++ < 3) failf("vectors_parsed", "bad line: %.80s", line);
    }
    fclose(fp);
    for (unsigned s = 0; s < SEED_COUNT; s++) {
        if (g_seed[s].hasPhrase != 1 || g_seed[s].hasPass != 1 || g_seed[s].hasSeed != 1 || strlen(g_seed[s].seedHex) != 128) {
            failf("vectors_parsed", "seed %u: phrase, passphrase and seed lines are not one each", s);
            continue;
        }
        if (g_seed[s].pass[0]) with++; else without++;
        // run.sh's passphrase arm relies on this: odd seeds carry the passphrase, even seeds none.
        if ((g_seed[s].pass[0] != '\0') != (s & 1)) failf("vectors_parsed", "seed %u: passphrase on the wrong parity", s);
        if ((s & 1) && strcmp(g_seed[s].phrase, g_seed[s - 1].phrase)) failf("vectors_parsed", "seed %u is not seed %u's phrase", s, s - 1);
        for (unsigned p = 0; p < BR_DERIVATION_PROFILE_COUNT; p++) {
            for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
                size_t n = rows_for(s, p, f);

                if (n == 0) continue;
                if (n != 2 * PER_CHAIN) failf("vectors_parsed", "seed %u profile %u format %d: %zu rows", s, p, f, n);
                for (unsigned chain = 0; chain <= 1; chain++)
                    for (unsigned i = 0; i < PER_CHAIN; i++)
                        if (! row_addr(s, p, f, chain, i)) failf("vectors_parsed", "seed %u profile %u format %d: no row %u/%u", s, p, f, chain, i);
            }
        }
    }
    if (with != SEED_COUNT / 2 || without != SEED_COUNT / 2) failf("vectors_parsed", "%zu seeds without and %zu with a passphrase", without, with);
    // 7 profiles, 8 probed (profile, format) pairs, 20 rows each, 8 seeds.
    if (g_rowCount != SEED_COUNT * 8 * 2 * PER_CHAIN) failf("vectors_parsed", "%zu rows, expected %d", g_rowCount, SEED_COUNT * 8 * 2 * PER_CHAIN);
    printf("   %zu address rows, %zu phrases without and %zu with a passphrase\n", g_rowCount, without, with);
    end("vectors_parsed");
}

// Always the file's phrase, in every arm: this is what says the vectors are 24-word vectors at all.
static void case_phrases(void)
{
    begin();
    for (unsigned s = 0; s < SEED_COUNT; s++) {
        const char *ph = g_seed[s].phrase;
        size_t words = 0, entLen = 0, len = 0;
        uint8_t entropy[40];
        char again[400] = "";

        for (const char *q = ph; *q; q++) if (*q != ' ' && (q == ph || q[-1] == ' ')) words++;
        if (words != 24) { failf("phrases_24_words", "seed %u: %zu words", s, words); continue; }
        if (BRBIP39PhraseIsValid(BRBIP39WordsEn, ph) != 1) { failf("phrases_24_words", "seed %u: the core refuses the phrase", s); continue; }
        entLen = BRBIP39Decode(entropy, sizeof(entropy), BRBIP39WordsEn, ph);
        if (entLen != 32) { failf("phrases_24_words", "seed %u: %zu bytes of entropy, expected 32", s, entLen); continue; }
        len = BRBIP39Encode(again, sizeof(again), BRBIP39WordsEn, entropy, entLen);
        if (len == 0 || strcmp(again, ph)) failf("phrases_24_words", "seed %u: re-encoded to a different phrase", s);
        memset(entropy, 0, sizeof(entropy));
    }
    end("phrases_24_words");
}

static void case_seed(unsigned s)
{
    char c[32], hex[129];
    uint8_t a[64], b[64], w[64];

    snprintf(c, sizeof(c), "seed_%u", s);
    begin();
    wallet_seed(s, w);
    tohex(w, 64, hex);
    if (strcmp(hex, g_seed[s].seedHex)) failf(c, "BRBIP39DeriveKey: %s", hex);
    if (! core_recovery_seed(s, a)) failf(c, "BRRecoveryMnemonicToSeed refused");
    else { tohex(a, 64, hex); if (strcmp(hex, g_seed[s].seedHex)) failf(c, "BRRecoveryMnemonicToSeed: %s", hex); }
    if (! bridge_seed(s, b)) failf(c, "the bridge's mnemonicToSeed refused");
    else { tohex(b, 64, hex); if (strcmp(hex, g_seed[s].seedHex)) failf(c, "bridge mnemonicToSeed: %s", hex); }
    end(c);
}

// One profile's addresses in one format against the file. got = 2 * PER_CHAIN slots, receive then change.
static size_t compare_slots(const char *c, unsigned s, unsigned p, int fmt, const char *got)
{
    size_t checked = 0;

    for (unsigned chain = 0; chain <= 1; chain++) {
        for (unsigned i = 0; i < PER_CHAIN; i++) {
            const char *want = row_addr(s, p, fmt, chain, i);
            const char *have = got + (chain * PER_CHAIN + i) * BR_RECOVERY_ADDRESS_SLOT;

            if (! want) failf(c, "format %d %u/%u: the vectors have no such row", fmt, chain, i);
            else if (! have[0] || strcmp(have, want)) failf(c, "format %d %u/%u: derived %s, vector %s", fmt, chain, i, have, want);
            else checked++;
        }
    }
    return checked;
}

static void case_core(unsigned s, unsigned p)
{
    char c[32], out[2 * PER_CHAIN * BR_RECOVERY_ADDRESS_SLOT];
    const BRDerivationProfile *prof = BRDerivationProfileAt(p);
    uint8_t seed[64];
    size_t checked = 0;

    snprintf(c, sizeof(c), "core_s%u_p%u", s, p);
    begin();
    if (! prof) failf(c, "the core has no profile %u", p);
    else if (! core_recovery_seed(s, seed)) failf(c, "the core refused the phrase");
    else {
        for (size_t f = 0; f < prof->formatCount; f++) {
            memset(out, 0, sizeof(out));
            if (! BRRecoveryDeriveAddresses(out, 2 * PER_CHAIN, seed, 64, prof->hmacKey, prof->path, prof->pathLen,
                                            PER_CHAIN, PER_CHAIN, prof->formats[f])) { failf(c, "format %d refused", prof->formats[f]); continue; }
            checked += compare_slots(c, s, p, prof->formats[f], out);
        }
        // Nothing in the file that the table does not probe.
        for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
            int probed = 0;

            for (size_t j = 0; j < prof->formatCount; j++) if (prof->formats[j] == f) probed = 1;
            if (! probed && rows_for(s, p, f)) failf(c, "the vectors hold format %d, which the core's table does not probe", f);
        }
        if (checked == 0 || checked != prof->formatCount * 2 * PER_CHAIN) failf(c, "%zu addresses matched", checked);
    }
    memset(seed, 0, sizeof(seed));
    end(c);
}

static void case_bridge(unsigned s, unsigned p)
{
    char c[32], got[2 * PER_CHAIN * BR_RECOVERY_ADDRESS_SLOT];
    const KotlinProfile *k = &KOTLIN_PROFILES[p];
    uint8_t seed[64];
    size_t checked = 0;

    snprintf(c, sizeof(c), "bridge_s%u_p%u", s, p);
    begin();
    if (! bridge_seed(s, seed)) failf(c, "the bridge refused the phrase");
    else {
        for (size_t f = 0; f < k->formatCount; f++) {
            jobjectArray b = NativeBridge_deriveAddresses(ENV, NULL, hbytes(seed, 64), hstr(k->hmacKey), hints(k->path, k->pathLen),
                                                          PER_CHAIN, PER_CHAIN, k->formats[f]);

            memset(got, 0, sizeof(got));
            if (! b || ((HObj *)b)->len != 2 * PER_CHAIN) failf(c, "format %d: the bridge returned no 20 addresses", k->formats[f]);
            else {
                for (size_t i = 0; i < 2 * PER_CHAIN; i++) {
                    HObj *str = ((jobject *)((HObj *)b)->data)[i];

                    if (str && str->data) snprintf(got + i * BR_RECOVERY_ADDRESS_SLOT, BR_RECOVERY_ADDRESS_SLOT, "%s", (char *)str->data);
                }
                checked += compare_slots(c, s, p, k->formats[f], got);
            }
            arena_free();
        }
        for (int f = 0; f < BR_DERIVATION_FORMAT_COUNT; f++) {
            int probed = 0;

            for (size_t j = 0; j < k->formatCount; j++) if (k->formats[j] == f) probed = 1;
            if (! probed && rows_for(s, p, f)) failf(c, "the vectors hold format %d, which Kotlin's table does not probe", f);
        }
        if (checked == 0 || checked != k->formatCount * 2 * PER_CHAIN) failf(c, "%zu addresses matched", checked);
    }
    memset(seed, 0, sizeof(seed));
    end(c);
}

static void expect_key(const char *c, BRKey *key, int fmt, const char *want, const char *what, unsigned chain, unsigned i)
{
    char a[BR_RECOVERY_ADDRESS_SLOT] = "";
    size_t n = fmt == BR_DERIVATION_FORMAT_P2WPKH ? BRKeySegwitAddress(key, a, sizeof(a), OP_0)
             : fmt == BR_DERIVATION_FORMAT_P2TR ? BRKeyTaprootAddress(key, a, sizeof(a))
             : BRKeyAddress(key, a, sizeof(a));

    if (! want) failf(c, "%s %u/%u: the vectors have no such row", what, chain, i);
    else if (n == 0 || strcmp(a, want)) failf(c, "%s %u/%u: derived %s, vector %s", what, chain, i, a, want);
}

static void case_wallet(unsigned s)
{
    char c[32];
    UInt512 seed;
    BRMasterPubKey mpk84, mpk86, mpkLegacy;
    BRWallet *w;
    size_t watched = 0;

    snprintf(c, sizeof(c), "wallet_s%u", s);
    begin();
    wallet_seed(s, seed.u8);
    mpk84 = BRBIP32MasterPubKeyBIP84(seed.u8, sizeof(seed));
    mpk86 = BRBIP32MasterPubKeyBIP86(seed.u8, sizeof(seed));
    mpkLegacy = BRBIP32MasterPubKeyLegacy(seed.u8, sizeof(seed));
    w = BRWalletNewDual(NULL, 0, mpk84, mpkLegacy);
    if (w) BRWalletSetTaprootKey(w, mpk86);
    if (! w) failf(c, "the recipe wallet was not built");
    for (unsigned chain = 0; chain <= 1; chain++) {
        for (unsigned i = 0; i < PER_CHAIN; i++) {
            static const struct { unsigned p; int fmt; const char *what; } t[] = {
                { 0, BR_DERIVATION_FORMAT_P2WPKH, "BIP84 P2WPKH" }, { 0, BR_DERIVATION_FORMAT_P2PKH, "BIP84 P2PKH" },
                { 6, BR_DERIVATION_FORMAT_P2TR, "BIP86 P2TR" }, { 1, BR_DERIVATION_FORMAT_P2PKH, "legacy P2PKH" },
            };

            for (size_t j = 0; j < sizeof(t) / sizeof(t[0]); j++) {
                const char *want = row_addr(s, t[j].p, t[j].fmt, chain, i);
                BRMasterPubKey mpk = t[j].p == 0 ? mpk84 : t[j].p == 6 ? mpk86 : mpkLegacy;
                uint8_t pub[65];
                size_t len = BRBIP32PubKey(pub, sizeof(pub), mpk, chain, i);
                BRKey key, priv;
                char what[48];

                snprintf(what, sizeof(what), "%s (watched)", t[j].what);
                if (! BRKeySetPubKey(&key, pub, len)) failf(c, "%s %u/%u: no public key", what, chain, i);
                else expect_key(c, &key, t[j].fmt, want, what, chain, i);

                memset(&priv, 0, sizeof(priv));
                if (t[j].p == 0) BRBIP32PrivKeyBIP84(&priv, seed.u8, sizeof(seed), chain, i);
                else if (t[j].p == 6) BRBIP32PrivKeyBIP86(&priv, seed.u8, sizeof(seed), chain, i);
                else BRBIP32PrivKey(&priv, seed.u8, sizeof(seed), chain, i);
                snprintf(what, sizeof(what), "%s (signing)", t[j].what);
                expect_key(c, &priv, t[j].fmt, want, what, chain, i);
                BRKeyClean(&priv);

                // The shared recipe hands out and watches these three encodings.
                if (j != 1 && w && want) {
                    if (BRWalletContainsAddress(w, want)) watched++;
                    else failf(c, "%s %u/%u: the recipe wallet does not watch %s", t[j].what, chain, i, want);
                }
            }
        }
    }
    if (w && watched != 3 * 2 * PER_CHAIN) failf(c, "the recipe wallet watches %zu of %d", watched, 3 * 2 * PER_CHAIN);
    if (w) BRWalletFree(w);
    memset(&seed, 0, sizeof(seed));
    end(c);
}

int main(int argc, char **argv)
{
    parse(argc > 1 ? argv[1] : NULL);
    if (KOTLIN_PROFILE_COUNT != BR_DERIVATION_PROFILE_COUNT) {
        printf("FAIL: Kotlin has %d profiles, the core %d\n", KOTLIN_PROFILE_COUNT, BR_DERIVATION_PROFILE_COUNT);
        return 1;
    }
    case_phrases();
    for (unsigned s = 0; s < SEED_COUNT; s++) {
        if (g_seed[s].hasPhrase != 1) continue;   // vectors_parsed has already failed
        case_seed(s);
        for (unsigned p = 0; p < BR_DERIVATION_PROFILE_COUNT; p++) case_core(s, p);
        for (unsigned p = 0; p < KOTLIN_PROFILE_COUNT; p++) case_bridge(s, p);
        case_wallet(s);
    }
    if (! g_anyFail) printf("ALL PASS\n");
    return g_anyFail;
}
