// Host KAT: a base58 string is decoded only if every character in it is in the alphabet; any other
// string is answered 0. So a value the wallet accepts is the value of the whole string it was given,
// never of a part of it, and the address a user approves is exactly the address that is paid.
//
// INVARIANT. BRBase58Decode answers 0 for a string holding any character outside the base58
// alphabet, wherever it stands. BRBase58CheckDecode and every function built on it (BRAddressIsValid,
// BRAddressScriptPubKey, BRAddressHash160, BRDigiDollarAddressDecode, BRPrivKeyIsValid,
// BRKeySetPrivKey, BRBIP38KeyIsValid) therefore accept a string only when the whole string is the
// value. A string the alphabet admits decodes exactly as before.
//
// VALUES: DigiByte addresses (P2PKH, both script versions), DigiDollar addresses (testnet and
// mainnet), WIF keys (both compressions), BIP32 extended keys (xpub, xprv) and a BIP38 key. Each
// payload is a known answer from an implementation independent of the core.
//
// CASES (argv[1]).
//   trailing     each value followed by a character outside the alphabet and, for most, more text
//                (the four look-alikes 0 O I l, whitespace, URI punctuation, non-ASCII, a second
//                address). Every decoding entry point for the value's kind must answer 0. A string
//                that also exceeds BR_BASE58_MAX_STR_LEN is answered 0 by the length bound in both
//                arms, and its line is marked GUARD.
//   every_byte   each of four values followed by every byte value outside the alphabet (1-255),
//                alone and followed by more text. Every entry point must answer 0.
//   middle       each value with a character outside the alphabet inserted at its middle, and with
//                its middle character replaced by one. The raw decoder must answer 0. The checked
//                entry points must answer 0 as well; they did before too, because the checksum of
//                the part before the character fails, so those lines are GUARDS, not red.
//   vectors      every value decodes to its known payload, its kind's entry points accept it, and
//                encoding the payload gives back exactly the string: the accepted string is the one
//                spelling of what is paid. run.sh requires both arms, in both word sizes, to print
//                the same lines.
//
// COMPARISON ARM (-DBASE58_STRICT_ALPHABET_UNFIXED) decodes up to the first character outside the
// alphabet and answers for that part. There every line of `trailing`, `every_byte` and `middle` that
// is not a GUARD fails, every GUARD line passes, and `vectors` passes.
//
// MACRO CONVENTION: PRESENCE. The comparison arm is -DBASE58_STRICT_ALPHABET_UNFIXED; the shipped
// arm is built with no -D at all.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "BRBase58.h"
#include "BRAddress.h"
#include "BRKey.h"
#include "BRBIP38Key.h"
#include "BRDigiDollar.h"

static const char ALPHABET[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

typedef enum { K_ADDR, K_DD_TEST, K_DD_MAIN, K_WIF, K_EXT, K_BIP38 } Kind;

typedef struct {
    const char *name;
    Kind kind;
    const char *s;
    const char *payload;   // hex of the base58check payload
} Value;

static const Value VALUES[] = {
    { "P2PKH address",           K_ADDR,    "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn",
      "1e78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69" },
    { "script address (63)",     K_ADDR,    "SPwYjsECsfKWQYyDVTDDVtvf1ZAQF74uam",
      "3f1d0a1c2f5d24af7c3546748869351423663a3a4e" },
    { "script address (5)",      K_ADDR,    "34LZdZwVhCSgyQtC57tjNd92WJDgypSrYg",
      "051d0a1c2f5d24af7c3546748869351423663a3a4e" },
    { "DigiDollar testnet",      K_DD_TEST, "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC",
      "b129dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b" },
    { "DigiDollar mainnet",      K_DD_MAIN, "DD2vBgrmGBn24DJwiPdB49Wjuz9x2hpLDeVC7fCoJqwPY3aRPZkP",
      "5285dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b" },
    { "WIF compressed",          K_WIF,     "KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617",
      "800c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d01" },
    { "WIF uncompressed",        K_WIF,     "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ",
      "800c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d" },
    { "BIP32 xpub",              K_EXT,
      "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
      "0488b21e000000000000000000873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d5080339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2" },
    { "BIP32 xprv",              K_EXT,
      "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
      "0488ade4000000000000000000873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d50800e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35" },
    { "BIP38 key",               K_BIP38,   "6PRVWUbkzzsbcVac2qwfssoUJAN1Xhrg6bNk8J7Nzm5H7kxEbn2Nh2ZoGg",
      "0142c0e957a24ad357fafb81c71f8375a9a4d0ac02bad5f6c87c4b459fabe34c0c314b33708ec3" },
};
#define VALUE_COUNT (sizeof(VALUES)/sizeof(*VALUES))

static const struct { const char *label; const char *text; } TRAILING[] = {
    { "'0'",                       "0" },
    { "'O'",                       "O" },
    { "'I'",                       "I" },
    { "'l'",                       "l" },
    { "a space and text",          " x" },
    { "a newline",                 "\n" },
    { "a tab",                     "\t" },
    { "a URI query",               "?amount=1" },
    { "'&' and text",              "&label=x" },
    { "'#' and text",              "#x" },
    { "'/'",                       "/" },
    { "'.'",                       "." },
    { "'-' and text",              "-x" },
    { "a no-break space (UTF-8)",  "\xc2\xa0" },
    { "a zero-width space (UTF-8)", "\xe2\x80\x8b" },
    { "'0' and a second address",  "0D9QsbucvuXjR7rMkrMqgkPvGEHQFdb9mQ2" },
};
#define TRAILING_COUNT (sizeof(TRAILING)/sizeof(*TRAILING))

static int failures = 0;

static void check(int ok, const char *what)
{
    printf("  %s: %s\n", ok ? "PASS" : "FAIL", what);
    if (! ok) failures++;
}

static void to_hex(char *out, const uint8_t *b, size_t n)
{
    static const char d[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) out[2*i] = d[b[i] >> 4], out[2*i + 1] = d[b[i] & 0x0f];
    out[2*n] = '\0';
}

// Every entry point that decodes a value of this kind, run on s (the raw decoder only when withRaw).
// Writes the names of those that accepted s into acc ("" when none did) and returns how many did.
static int accepted_by(Kind kind, const char *s, int withRaw, char *acc, size_t accLen)
{
    uint8_t buf[256], md[20], key32[32];
    BRKey key;
    int n = 0;

    acc[0] = '\0';
#define ACC(cond, label) do { if (cond) { n++; strncat(acc, acc[0] ? ", " label : label, accLen - strlen(acc) - 1); } } while (0)
    ACC(withRaw && BRBase58Decode(NULL, 0, s) != 0, "BRBase58Decode");
    ACC(BRBase58CheckDecode(NULL, 0, s) != 0, "BRBase58CheckDecode(size)");
    ACC(BRBase58CheckDecode(buf, sizeof(buf), s) != 0, "BRBase58CheckDecode");
    switch (kind) {
        case K_ADDR:
            ACC(BRAddressIsValid(s), "BRAddressIsValid");
            ACC(BRAddressScriptPubKey(NULL, 0, s) != 0, "BRAddressScriptPubKey(size)");
            ACC(BRAddressScriptPubKey(buf, sizeof(buf), s) != 0, "BRAddressScriptPubKey");
            ACC(BRAddressHash160(md, s), "BRAddressHash160");
            break;
        case K_DD_TEST:
        case K_DD_MAIN:
            ACC(BRDigiDollarAddressDecode(key32, s, kind == K_DD_TEST), "BRDigiDollarAddressDecode");
            break;
        case K_WIF:
            ACC(BRPrivKeyIsValid(s), "BRPrivKeyIsValid");
            memset(&key, 0, sizeof(key));
            ACC(BRKeySetPrivKey(&key, s), "BRKeySetPrivKey");
            break;
        case K_BIP38:
            ACC(BRBIP38KeyIsValid(s), "BRBIP38KeyIsValid");
            break;
        case K_EXT:
            break;
    }
#undef ACC
    return n;
}

static int entries_of(Kind kind, int withRaw)
{
    int n = withRaw ? 3 : 2;   // BRBase58Decode, BRBase58CheckDecode (size query and decode)

    switch (kind) {
        case K_ADDR: return n + 4;
        case K_DD_TEST: case K_DD_MAIN: return n + 1;
        case K_WIF: return n + 2;
        case K_BIP38: return n + 1;
        case K_EXT: return n;
    }
    return n;
}

static char *join(const char *a, const char *b)
{
    char *s = malloc(strlen(a) + strlen(b) + 1);
    strcpy(s, a), strcat(s, b);
    return s;
}

// A printable label for one byte.
static void byte_label(char *out, unsigned b)
{
    if (b > 32 && b < 127) sprintf(out, "'%c'", (char)b); else sprintf(out, "0x%02x", b);
}

// ---- trailing ---------------------------------------------------------------------------------

static int case_trailing(void)
{
    char acc[512], what[700];

    for (size_t v = 0; v < VALUE_COUNT; v++) {
        for (size_t t = 0; t < TRAILING_COUNT; t++) {
            char *s = join(VALUES[v].s, TRAILING[t].text);
            int n = accepted_by(VALUES[v].kind, s, 1, acc, sizeof(acc));
            // A string longer than BR_BASE58_MAX_STR_LEN is answered 0 by the length bound in both arms.
            const char *guard = (strlen(s) > BR_BASE58_MAX_STR_LEN) ? "GUARD (longer than the bound): " : "";
            if (n == 0) snprintf(what, sizeof(what), "%s%s followed by %s: answered 0 by all %d entry points",
                                 guard, VALUES[v].name, TRAILING[t].label, entries_of(VALUES[v].kind, 1));
            else snprintf(what, sizeof(what), "%s%s followed by %s: accepted by %s", guard, VALUES[v].name, TRAILING[t].label,
                          acc);
            check(n == 0, what);
            free(s);
        }
    }
    printf("trailing: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

// ---- every_byte -------------------------------------------------------------------------------

static int case_every_byte(void)
{
    static const size_t picks[] = { 0, 4, 5, 7 };   // P2PKH, DigiDollar mainnet, WIF, xpub
    char acc[512], what[700], tail[3], lab[8];

    for (size_t p = 0; p < sizeof(picks)/sizeof(*picks); p++) {
        const Value *v = &VALUES[picks[p]];
        unsigned tried = 0, accepted = 0, first = 0;

        for (unsigned b = 1; b < 256; b++) {
            if (strchr(ALPHABET, (int)b)) continue;
            for (int more = 0; more < 2; more++) {
                tail[0] = (char)b, tail[1] = more ? 'x' : '\0', tail[2] = '\0';
                char *s = join(v->s, tail);
                tried++;
                if (accepted_by(v->kind, s, 1, acc, sizeof(acc)) != 0) { if (! accepted++) first = b; }
                free(s);
            }
        }
        if (accepted == 0) snprintf(what, sizeof(what), "%s followed by each of the %u byte values outside the alphabet "
                                    "(alone, and with more text): %u strings, all answered 0", v->name, tried/2, tried);
        else {
            byte_label(lab, first);
            snprintf(what, sizeof(what), "%s followed by a byte outside the alphabet: %u of %u strings accepted (first: %s)",
                     v->name, accepted, tried, lab);
        }
        check(accepted == 0, what);
    }
    printf("every_byte: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

// ---- middle -----------------------------------------------------------------------------------

static int case_middle(void)
{
    char acc[512], what[700], *s;

    for (size_t v = 0; v < VALUE_COUNT; v++) {
        size_t len = strlen(VALUES[v].s), mid = len/2;

        for (int form = 0; form < 2; form++) {
            s = malloc(len + 2);
            if (form == 0) {   // '0' inserted at the middle
                memcpy(s, VALUES[v].s, mid), s[mid] = '0', strcpy(&s[mid + 1], &VALUES[v].s[mid]);
            }
            else {             // the middle character replaced by 'l'
                strcpy(s, VALUES[v].s), s[mid] = 'l';
            }
            snprintf(what, sizeof(what), "raw: %s with %s its middle is answered 0", VALUES[v].name,
                     form == 0 ? "'0' inserted at" : "'l' in place of");
            check(BRBase58Decode(NULL, 0, s) == 0, what);
            int n = accepted_by(VALUES[v].kind, s, 0, acc, sizeof(acc));
            snprintf(what, sizeof(what), "GUARD: %s with %s its middle is refused by its checked entry points%s%s",
                     VALUES[v].name, form == 0 ? "'0' inserted at" : "'l' in place of", n ? "; accepted by " : "",
                     n ? acc : "");
            check(n == 0, what);
            free(s);
        }
    }
    printf("middle: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

// ---- vectors ----------------------------------------------------------------------------------

static int case_vectors(void)
{
    uint8_t buf[256];
    char hex[2*sizeof(buf) + 1], back[256], acc[512], what[700];

    for (size_t v = 0; v < VALUE_COUNT; v++) {
        const Value *x = &VALUES[v];
        size_t n = BRBase58CheckDecode(buf, sizeof(buf), x->s);

        to_hex(hex, buf, n);
        BRBase58CheckEncode(back, sizeof(back), buf, n);
        int m = accepted_by(x->kind, x->s, 1, acc, sizeof(acc));
        printf("%-22s %s -> %s\n", x->name, x->s, hex);
        snprintf(what, sizeof(what), "%s decodes to its known payload", x->name);
        check(strcmp(hex, x->payload) == 0, what);
        snprintf(what, sizeof(what), "%s: the payload encodes back to exactly the string", x->name);
        check(n > 0 && strcmp(back, x->s) == 0, what);
        snprintf(what, sizeof(what), "%s is accepted by all %d of its entry points (%s)", x->name, entries_of(x->kind, 1), acc);
        check(m == entries_of(x->kind, 1), what);
    }

    // Strings the alphabet admits are unaffected: leading ones, the whole alphabet, the empty string.
    {
        uint8_t z[8];
        snprintf(what, sizeof(what), "raw: \"1111\" decodes to four zero bytes");
        check(BRBase58Decode(z, sizeof(z), "1111") == 4 && memcmp(z, "\0\0\0\0", 4) == 0, what);
        check(BRBase58Decode(NULL, 0, ALPHABET) == 43, "raw: the whole alphabet decodes (43 bytes)");
        check(BRBase58Decode(NULL, 0, "") == 0 && BRBase58CheckDecode(NULL, 0, "") == 0, "raw and checked: \"\" is answered 0");
    }
    printf("vectors: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 2 && strcmp(argv[1], "trailing") == 0) return case_trailing();
    if (argc == 2 && strcmp(argv[1], "every_byte") == 0) return case_every_byte();
    if (argc == 2 && strcmp(argv[1], "middle") == 0) return case_middle();
    if (argc == 2 && strcmp(argv[1], "vectors") == 0) return case_vectors();
    fprintf(stderr, "usage: %s trailing | every_byte | middle | vectors\n", argv[0]);
    return 2;
}
