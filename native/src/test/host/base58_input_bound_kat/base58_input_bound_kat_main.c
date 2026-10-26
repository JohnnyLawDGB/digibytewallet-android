// Host KAT: a base58 string longer than any value the wallet can use is rejected before it is
// decoded, and decoding never sizes a stack buffer from the length of the string it is given.
//
// INVARIANT. The longest base58 value the wallet has any use for is a serialized BIP32 extended
// key: 82 bytes, at most 112 characters. BIP38 keys are 58 characters, WIF keys 51 or 52, a
// DigiDollar address 52, a DigiByte address 34. BRBase58Decode and BRBase58CheckDecode answer 0
// for a string longer than BR_BASE58_MAX_STR_LEN without decoding it, and decode everything else
// in buffers of a fixed size, so neither the time a decode takes nor the stack it uses grows with
// the string. Every value the wallet decodes decodes exactly as it did before. Every base58 string
// the wallet decodes, whichever entry point it arrives through, is decoded by these two functions.
//
// CASES (argv[1]).
//   vectors        decodes a value of every kind the wallet decodes and checks each against a
//                  known answer from an implementation independent of the core (Python integers,
//                  or the published vector): raw base58 pairs and the core's own test vectors,
//                  DigiByte addresses (P2PKH and both script versions), WIF keys (both prefixes,
//                  both compressions), BIP38 keys, BIP32 extended keys, a DigiDollar address.
//                  run.sh also requires both arms, in both word sizes, to print the same lines.
//   bound          a string of exactly BR_BASE58_MAX_STR_LEN characters still decodes, raw and
//                  checked; a string one character longer is answered 0 by both decoders. The
//                  bound itself lies between the longest value (112) and 256.
//   entry <name>   one decoding entry point is handed a LONG_LEN-character string on the main
//                  thread. It must answer "not valid", within CPU_BUDGET_S of CPU time.
//   stack <name>   the same entry point is handed a STACK_INPUT_LEN-character string on a thread
//                  with a STACK_SIZE stack (about what an Android worker thread gets) and a guard
//                  region below it. It must answer "not valid".
//   prefix <name>  one of the two decoders is handed BR_BASE58_MAX_STR_LEN + 1 characters in a heap
//                  block of exactly that size, with no terminating NUL. It must answer 0 without
//                  reading past the block; AddressSanitizer reports any read that does.
//   list           prints the entry point names (list-decoders: the two decoders' four forms), so
//                  run.sh and this file cannot drift.
//
// COMPARISON ARM (-DBASE58_INPUT_BOUND_UNFIXED) builds the two decoders without the bound. There
// `bound` fails (a string one character over the bound decodes), `entry` does not return within
// run.sh's time limit, and `stack` and `prefix` do not return normally. `vectors` passes in both
// arms.
//
// MACRO CONVENTION: PRESENCE. The comparison arm is -DBASE58_INPUT_BOUND_UNFIXED; the shipped arm
// is built with no -D at all.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>
#include "BRBase58.h"
#include "BRAddress.h"
#include "BRKey.h"
#include "BRBIP38Key.h"
#include "BRDigiDollar.h"

#ifndef BR_BASE58_MAX_STR_LEN
#error "BRBase58.h does not define BR_BASE58_MAX_STR_LEN"
#endif

#define LONG_LEN        1000000u              // entry: a long paste
#define CPU_BUDGET_S    0.25                  // entry: CPU time a "not valid" answer may take
#define STACK_SIZE      (1u << 20)            // stack: 1 MiB
#define STACK_GUARD     (8u << 20)            // stack: guard region below the thread's stack
#define STACK_INPUT_LEN (2u * STACK_SIZE)     // stack: a buffer sized from this is ~1.47 MiB

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

// A string of n characters from the base58 alphabet, without a leading '1'.
static char *long_input(size_t n)
{
    static const char alphabet[] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    char *s = malloc(n + 1);
    if (! s) { printf("  FAIL: could not allocate %zu bytes\n", n + 1); exit(1); }
    for (size_t i = 0; i < n; i++) s[i] = alphabet[1 + i % 57];
    s[n] = '\0';
    return s;
}

// ---- vectors ---------------------------------------------------------------------------------

static void raw(const char *b58, const char *hex)
{
    uint8_t buf[256];
    char got[2*sizeof(buf) + 1], what[400];
    size_t need = BRBase58Decode(NULL, 0, b58), len = BRBase58Decode(buf, sizeof(buf), b58);

    to_hex(got, buf, len);
    printf("raw    %s -> %s\n", b58, got);
    snprintf(what, sizeof(what), "raw %.60s%s decodes to its known answer", b58, strlen(b58) > 60 ? "..." : "");
    check(need == len && strcmp(got, hex) == 0, what);
}

static void checked(const char *b58, const char *hex)
{
    uint8_t buf[128];
    char got[2*sizeof(buf) + 1], what[400];
    size_t need = BRBase58CheckDecode(NULL, 0, b58), len = BRBase58CheckDecode(buf, sizeof(buf), b58);

    to_hex(got, buf, len);
    printf("check  %s -> %s\n", b58, got);
    snprintf(what, sizeof(what), "check %.60s%s decodes to its known answer", b58, strlen(b58) > 60 ? "..." : "");
    check(need == len && strcmp(got, hex) == 0, what);
}

static void address(const char *a, int valid, const char *spk, const char *h160)
{
    uint8_t script[64], md[20];
    char spkHex[2*sizeof(script) + 1], mdHex[41], what[200];
    int v = BRAddressIsValid(a), h = BRAddressHash160(md, a);
    size_t n = BRAddressScriptPubKey(script, sizeof(script), a);

    to_hex(spkHex, script, n);
    if (h) to_hex(mdHex, md, sizeof(md)); else strcpy(mdHex, "-");
    printf("addr   %s valid=%d script=%s hash160=%s\n", a, v, spkHex, mdHex);
    snprintf(what, sizeof(what), "address %s: valid, script and hash160 as known", a);
    check(v == valid && strcmp(spkHex, spk) == 0 && strcmp(mdHex, h160) == 0, what);
}

static void wif(const char *w, int valid, int set, const char *secret, int compressed)
{
    BRKey key;
    char got[65], what[200];
    int v = BRPrivKeyIsValid(w), r;

    memset(&key, 0, sizeof(key));
    r = BRKeySetPrivKey(&key, w);
    if (r) to_hex(got, key.secret.u8, sizeof(key.secret)); else strcpy(got, "-");
    printf("wif    %s valid=%d set=%d secret=%s compressed=%d\n", w, v, r, got, r ? key.compressed : -1);
    snprintf(what, sizeof(what), "key %s: valid, set, secret and compression as known", w);
    check(v == valid && r == set && strcmp(got, secret) == 0 && (! r || key.compressed == compressed), what);
}

static void bip38(const char *k, int valid)
{
    char what[200];
    int v = BRBIP38KeyIsValid(k);

    printf("bip38  %s valid=%d\n", k, v);
    snprintf(what, sizeof(what), "BIP38 key %s is valid", k);
    check(v == valid, what);
}

static void digidollar(const char *a, int isTestnet, int ok, const char *key32)
{
    uint8_t k[32];
    char got[65], what[200];
    int r = BRDigiDollarAddressDecode(k, a, isTestnet);

    if (r) to_hex(got, k, sizeof(k)); else strcpy(got, "-");
    printf("dd     %s testnet=%d ok=%d key=%s\n", a, isTestnet, r, got);
    snprintf(what, sizeof(what), "DigiDollar address %s decodes to its known key", a);
    check(r == ok && strcmp(got, key32) == 0, what);
}

static int case_vectors(void)
{
    char ones[68], zeros[2*67 + 1];

    // Raw base58: the published encode/decode pairs (Bitcoin Core's base58 vectors), each checked
    // here against Python's integers as well.
    raw("", "");
    raw("2g", "61");
    raw("a3gV", "626262");
    raw("aPEr", "636363");
    raw("2cFupjhnEsSn59qHXstmK2ffpLv2", "73696d706c792061206c6f6e6720737472696e67");
    raw("1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L", "00eb15231dfceb60925886b67d065299925915aeb172c06647");
    raw("ABnLTmg", "516b6fcd0f");
    raw("3SEo3LWLoPntC", "bf4f89001e670274dd");
    raw("3EFU7m", "572e4794");
    raw("EJDM8drfXA6uyA", "ecac89cad93923c02321");
    raw("Rt5zm", "10c8511e");
    raw("1111111111", "00000000000000000000");
    raw("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz",
        "000111d38e5fc9071ffcd20b4a763cc9ae4f252bb4e48fd66a835e252ada93ff480d6dd43dc62a641155a5");

    // The core's own vectors (test.c): BRBase58Tests and the compact public keys and 65-byte
    // compact signatures of BRKeyCompactSign / BRPubKeyRecover (the longest, 89 characters).
    raw("#&$@*^(*#!^", "");
    raw("z", "39");
    memset(ones, '1', 67), ones[67] = '\0', memset(zeros, '0', 2*67), zeros[2*67] = '\0';
    raw(ones, zeros);
    ones[66] = 'z', zeros[2*66] = '3', zeros[2*66 + 1] = '9';
    raw(ones, zeros);
    raw("26wZYDdvpmCrYZeUcxgqd1KquN4o6wXwLomBW5SjnwUqG",
        "03b60a6e602617be4d5ac81378fd7bd835e6363e2d406d2c616ce9197193c7a17b");
    raw("gpRv1sNA3XURB6QEtGrx6Q18DZ5cSgUSDQKX4yYypxpW",
        "024f9e42311b599d4ecc0add79cd78c9b4d06e01f9e85e2f3ddf38dfaa9e5d4037");
    raw("3kq9e842BzkMfbPSbhKVwGZgspDSkz4YfqjdBYQPWDzqd77gPgR1zq4XG7KtAL5DZTcfFFs2iph4urNyXeBkXsEYY",
        "1f3a397e4f7781ab2f58feb26d325e78b3aec9bcf3c966dbacad935ffc60b764db45aeec178125d16d7083ce26c027d0b4dc0211f445f32efcb68ac5afdae94489");
    raw("3qECEYmb6x4X22sH98Aer68SdfrLwtqvb5Ncv7EqKmzbxeYYJ1hU9irP6R5PeCctCPYo5KQiWFgoJ3H5MkuX18gHu",
        "20162a5bf83de48104edd6906a7ba3247b8cd38e8c5eb41454d14e8b7626bd787166421eff3c6a92bddae805e163c881ac2ccb16d6fdaad70c050680782f7b28a8");
    raw("3oHQhxq5eW8dnp7DquTCbA5tECoNx7ubyiubw4kiFm7wXJF916SZVykFzb8rB1K6dEu7mLspBWbBEJyYk79jAosVR",
        "1fb4e76cb91919b41adf09456f6db57acb5f039e80be02bba5ff5b94260831278021415b8dc3554fbd08e582d53cac63c14839da1d07b37a79d7970a26de200678");

    // Base58Check: BIP32 test vector 1, chain m (the longest values: 111 characters), and the
    // payloads of the keys and addresses below.
    checked("xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            "0488b21e000000000000000000873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d5080339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2");
    checked("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            "0488ade4000000000000000000873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d50800e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35");
    checked("6PRVWUbkzzsbcVac2qwfssoUJAN1Xhrg6bNk8J7Nzm5H7kxEbn2Nh2ZoGg",
            "0142c0e957a24ad357fafb81c71f8375a9a4d0ac02bad5f6c87c4b459fabe34c0c314b33708ec3");
    checked("KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617",
            "800c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d01");
    checked("DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn", "1e78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69");
    checked("TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC",
            "b129dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b");
    checked("", "");

    // DigiByte mainnet addresses. P2PKH from the core's independently generated derivation
    // vectors (vectors/derivation/abandon-about.txt); the script addresses (version 63 "S" and
    // the legacy version 5 "3") over one hash160, encoded by Python.
    address("DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn", 1,
            "76a91478f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f6988ac", "78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69");
    address("D9QsbucvuXjR7rMkrMqgkPvGEHQFdb9mQ2", 1,
            "76a9142edc5bf4523b43e93d078d0fb6bb5cc758cd7bda88ac", "2edc5bf4523b43e93d078d0fb6bb5cc758cd7bda");
    address("SPwYjsECsfKWQYyDVTDDVtvf1ZAQF74uam", 1,
            "a9141d0a1c2f5d24af7c3546748869351423663a3a4e87", "1d0a1c2f5d24af7c3546748869351423663a3a4e");
    address("34LZdZwVhCSgyQtC57tjNd92WJDgypSrYg", 1,
            "a9141d0a1c2f5d24af7c3546748869351423663a3a4e87", "1d0a1c2f5d24af7c3546748869351423663a3a4e");
    address("DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyo", 0, "", "-");   // last character changed

    // WIF keys over one secret: prefix 0x80, uncompressed (51 characters) and compressed (52),
    // and the legacy prefix 0x9e, which BRPrivKeyIsValid accepts and BRKeySetPrivKey does not.
    wif("5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ", 1, 1,
        "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d", 0);
    wif("KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617", 1, 1,
        "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d", 1);
    wif("QP2GKa5kuU2i2G3xJMH5KL9NErbVYGxMoRiF5trrJJvHzrJ2Ebp7", 1, 0, "-", 0);
    wif("6JDyVDw6R82kH9PsbHq3nqk8XnDoLmrFEEknLEZbHA3ZQ8cSuqc", 1, 0, "-", 0);

    // BIP38 test vectors: no EC multiply (prefix 0x0142) and EC multiply (0x0143), 58 characters.
    bip38("6PRVWUbkzzsbcVac2qwfssoUJAN1Xhrg6bNk8J7Nzm5H7kxEbn2Nh2ZoGg", 1);
    bip38("6PfQu77ygVyJLZjfvMLyhLMQbYnu5uguoJJ4kMCLqWwPEdfpwANVS76gTX", 1);

    // DigiDollar: the golden testnet address of digidollar_addr_encode_kat (an independent Kotlin
    // implementation's vector), 52 characters.
    digidollar("TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC", 1, 1,
               "dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b");

    printf("vectors: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

// ---- bound -----------------------------------------------------------------------------------

static int case_bound(void)
{
    const size_t max = BR_BASE58_MAX_STR_LEN;
    char *s = malloc(max + 2), what[200];
    uint8_t *buf = malloc(max + 64), payload[BR_BASE58_MAX_STR_LEN + 64], tail[32];
    size_t n, n2, k, z;

    printf("bound: BR_BASE58_MAX_STR_LEN = %zu\n", max);
    check(max >= 112, "the bound admits any 82-byte value (a serialized extended key: up to 112 characters)");
    check(max <= 256, "the bound is at most 256 characters, so a decode at the bound costs microseconds");
    if (failures) { free(s), free(buf); printf("bound: FAIL\n"); return 1; } // nothing below is quick past these

    // Raw: the same digit repeated, and leading '1's (each one zero byte).
    memset(s, '2', max), s[max] = '\0';
    n = BRBase58Decode(NULL, 0, s), n2 = BRBase58Decode(buf, max + 64, s);
    snprintf(what, sizeof(what), "raw: %zu characters decode (%zu bytes)", max, n2);
    check(n > 0 && n2 == n, what);
    memset(s, '2', max + 1), s[max + 1] = '\0';
    n = BRBase58Decode(NULL, 0, s), n2 = BRBase58Decode(buf, max + 64, s);
    snprintf(what, sizeof(what), "raw: %zu characters are answered 0 (got %zu and %zu)", max + 1, n, n2);
    check(n == 0 && n2 == 0, what);
    memset(s, '1', max), s[max] = '\0';
    n = BRBase58Decode(buf, max + 64, s);
    for (z = 0; z < n && buf[z] == 0; z++);
    snprintf(what, sizeof(what), "raw: %zu leading ones decode to %zu zero bytes", max, max);
    check(n == max && z == max, what);
    memset(s, '1', max + 1), s[max + 1] = '\0';
    n = BRBase58Decode(NULL, 0, s), n2 = BRBase58Decode(buf, max + 64, s);
    snprintf(what, sizeof(what), "raw: %zu leading ones are answered 0 (got %zu and %zu)", max + 1, n, n2);
    check(n == 0 && n2 == 0, what);

    // Checked: valid base58check strings of exactly the bound and one character more, made by the
    // encoder (which this change leaves alone) from k zero bytes and a fixed 32-byte tail. Each
    // leading zero byte adds exactly one character, so k fixes the length.
    memset(tail, 0xff, sizeof(tail));
    k = max - (BRBase58CheckEncode(NULL, 0, tail, sizeof(tail)) - 1); // the encoder counts the NUL
    memset(payload, 0, k), memcpy(&payload[k], tail, sizeof(tail));
    n = BRBase58CheckEncode(NULL, 0, payload, k + sizeof(tail));
    check(n == max + 1, "a valid base58check string of exactly the bound can be made");
    if (n == max + 1) {
        char *t = malloc(max + 2);
        BRBase58CheckEncode(t, max + 2, payload, k + sizeof(tail));
        n = BRBase58CheckDecode(NULL, 0, t), n2 = BRBase58CheckDecode(buf, max + 64, t);
        snprintf(what, sizeof(what), "check: a valid %zu-character string decodes to its %zu-byte payload", strlen(t),
                 k + sizeof(tail));
        check(n == k + sizeof(tail) && n2 == n && memcmp(buf, payload, n2) == 0, what);
        memset(payload, 0, k + 1), memcpy(&payload[k + 1], tail, sizeof(tail));
        BRBase58CheckEncode(t, max + 2, payload, k + 1 + sizeof(tail));
        n = BRBase58CheckDecode(NULL, 0, t), n2 = BRBase58CheckDecode(buf, max + 64, t);
        snprintf(what, sizeof(what), "check: a valid %zu-character string is answered 0 (got %zu and %zu)", strlen(t),
                 n, n2);
        check(strlen(t) == max + 1 && n == 0 && n2 == 0, what);
        free(t);
    }

    free(s), free(buf);
    printf("bound: %s\n", failures ? "FAIL" : "ALL PASS");
    return failures ? 1 : 0;
}

// ---- entry points ----------------------------------------------------------------------------

static int e_raw(const char *s)          { uint8_t b[256]; return BRBase58Decode(b, sizeof(b), s) != 0; }
static int e_raw_size(const char *s)     { return BRBase58Decode(NULL, 0, s) != 0; }
static int e_check(const char *s)        { uint8_t b[256]; return BRBase58CheckDecode(b, sizeof(b), s) != 0; }
static int e_check_size(const char *s)   { return BRBase58CheckDecode(NULL, 0, s) != 0; }
static int e_addr_valid(const char *s)   { return BRAddressIsValid(s); }
static int e_addr_script(const char *s)  { uint8_t b[64]; return BRAddressScriptPubKey(b, sizeof(b), s) != 0; }
static int e_addr_hash160(const char *s) { uint8_t md[20]; return BRAddressHash160(md, s); }
static int e_key_valid(const char *s)    { return BRPrivKeyIsValid(s); }
static int e_key_set(const char *s)      { BRKey k; memset(&k, 0, sizeof(k)); return BRKeySetPrivKey(&k, s); }
static int e_bip38_valid(const char *s)  { return BRBIP38KeyIsValid(s); }
static int e_bip38_set(const char *s)    { BRKey k; memset(&k, 0, sizeof(k)); return BRKeySetBIP38Key(&k, s, ""); }
static int e_dd_decode(const char *s)    { uint8_t k[32]; return BRDigiDollarAddressDecode(k, s, 0); }

typedef struct { const char *name; int (*fn)(const char *); int decoder; } Entry;

static const Entry ENTRIES[] = {   // decoder = 1: BRBase58Decode or BRBase58CheckDecode itself
    { "base58_decode",        e_raw,          1 },
    { "base58_decode_size",   e_raw_size,     1 },
    { "base58check_decode",   e_check,        1 },
    { "base58check_size",     e_check_size,   1 },
    { "address_is_valid",     e_addr_valid,   0 },  // NativeBridge.isValidAddress
    { "address_script",       e_addr_script,  0 },
    { "address_hash160",      e_addr_hash160, 0 },
    { "privkey_is_valid",     e_key_valid,    0 },
    { "key_set_privkey",      e_key_set,      0 },
    { "bip38_key_is_valid",   e_bip38_valid,  0 },
    { "key_set_bip38_key",    e_bip38_set,    0 },
    { "digidollar_decode",    e_dd_decode,    0 },  // NativeBridge.isValidDigiDollarAddress
};

static const Entry *find_entry(const char *name)
{
    for (size_t i = 0; i < sizeof(ENTRIES)/sizeof(*ENTRIES); i++) {
        if (strcmp(ENTRIES[i].name, name) == 0) return &ENTRIES[i];
    }
    printf("  FAIL: no entry point named %s\n", name);
    exit(2);
}

static int case_entry(const char *name)
{
    const Entry *e = find_entry(name);
    char *s = long_input(LONG_LEN), what[200];
    clock_t t0;
    double cpu;
    int r;

    printf("entry %s: decoding %u characters on the main thread\n", name, LONG_LEN);
    t0 = clock();
    r = e->fn(s);
    cpu = (double)(clock() - t0)/CLOCKS_PER_SEC;
    printf("entry %s: answered %s in %.4f s of CPU\n", name, r ? "VALID" : "not valid", cpu);
    snprintf(what, sizeof(what), "%u characters are not valid", LONG_LEN);
    check(r == 0, what);
    snprintf(what, sizeof(what), "answered within %.2f s of CPU", CPU_BUDGET_S);
    check(cpu < CPU_BUDGET_S, what);
    free(s);
    return failures ? 1 : 0;
}

typedef struct { const Entry *e; const char *s; int r; } StackJob;

static void *stack_thread(void *arg)
{
    StackJob *job = arg;
    job->r = job->e->fn(job->s);
    return NULL;
}

static int case_stack(const char *name)
{
    StackJob job = { find_entry(name), NULL, -1 };
    pthread_attr_t attr;
    pthread_t t;
    char what[200];

    job.s = long_input(STACK_INPUT_LEN);
    if (pthread_attr_init(&attr) != 0 || pthread_attr_setstacksize(&attr, STACK_SIZE) != 0 ||
        pthread_attr_setguardsize(&attr, STACK_GUARD) != 0) {
        printf("  FAIL: could not set up a %u KiB thread stack\n", STACK_SIZE >> 10);
        return 1;
    }
    printf("stack %s: decoding %u characters on a thread with a %u KiB stack\n", name, STACK_INPUT_LEN,
           STACK_SIZE >> 10);
    if (pthread_create(&t, &attr, stack_thread, &job) != 0 || pthread_join(t, NULL) != 0) {
        printf("  FAIL: could not run the thread\n");
        return 1;
    }
    pthread_attr_destroy(&attr);
    printf("stack %s: answered %s\n", name, job.r == 0 ? "not valid" : "VALID");
    snprintf(what, sizeof(what), "%u characters are not valid, on a %u KiB stack", STACK_INPUT_LEN, STACK_SIZE >> 10);
    check(job.r == 0, what);
    free((char *)job.s);
    return failures ? 1 : 0;
}

static int case_prefix(const char *name)
{
    const Entry *e = find_entry(name);
    const size_t n = BR_BASE58_MAX_STR_LEN + 1;
    char *s = malloc(n), what[200]; // exactly n bytes, so the sanitizer's redzone starts right after them
    int r;

    if (! e->decoder) { printf("  FAIL: %s is not one of the two decoders\n", name); return 2; }
    for (size_t i = 0; i < n; i++) s[i] = "23456789"[i % 8];
    printf("prefix %s: %zu characters and no terminator, in a block of exactly that size\n", name, n);
    r = e->fn(s);
    printf("prefix %s: answered %s\n", name, r ? "VALID" : "not valid");
    snprintf(what, sizeof(what), "answered 0, reading no more than the first %zu characters", n);
    check(r == 0, what);
    free(s);
    return failures ? 1 : 0;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0); // every line is out before a run is stopped at its limit
    if (argc == 2 && strcmp(argv[1], "vectors") == 0) return case_vectors();
    if (argc == 2 && strcmp(argv[1], "bound") == 0) return case_bound();
    if (argc == 3 && strcmp(argv[1], "entry") == 0) return case_entry(argv[2]);
    if (argc == 3 && strcmp(argv[1], "stack") == 0) return case_stack(argv[2]);
    if (argc == 3 && strcmp(argv[1], "prefix") == 0) return case_prefix(argv[2]);
    if (argc == 2 && (strcmp(argv[1], "list") == 0 || strcmp(argv[1], "list-decoders") == 0)) {
        int all = (strcmp(argv[1], "list") == 0);

        for (size_t i = 0; i < sizeof(ENTRIES)/sizeof(*ENTRIES); i++) {
            if (all || ENTRIES[i].decoder) printf("%s\n", ENTRIES[i].name);
        }
        return 0;
    }
    fprintf(stderr, "usage: %s vectors | bound | entry <name> | stack <name> | prefix <name> | list | list-decoders\n",
            argv[0]);
    return 2;
}
