//
//  digiid_core_kat_main.c
//
//  Host KAT for BRDigiId.h: the Digi-ID per-site identity (SLIP-0013) and login signing, moved from
//  Android's JNI bridge (native/src/main/jni/bridge/slip13.c; seed_derive_identity_key in jni_wallet.c;
//  sign_with_key, base64_encode, write_varint, message_utf8, signMessage, signIdentityMessage and
//  identityAddress in jni_wallet_sign.c). Each function must answer what the bridge answers on every
//  input, so every expectation here is fixed OUTSIDE the header, three ways:
//
//    1. BY HAND. Published vectors (SLIP-0013's, and the BitID draft's callback that
//       slip13_identity_kat holds); every assertion of slip13_identity_kat and of identity_sign_bytes_kat
//       (the bytes a message is signed over); the derivation inputs DigiIdRequestTest pins
//       (request_golden_inputs: its golden callbackUrls and the letter-case one, each with the address
//       the bridge derives for it); and DigiIdIdentityChoiceTest's request, whose two identities must
//       differ and whose address is the address the login signs with (sign_site_request). Addresses and
//       signatures are literals read off the REAL bridge by `digiid_core_bridge print` (run.sh builds it;
//       its sweep holds the header to the bridge end to end). Keys are also derived here by a separate
//       BIP32 written in this file (ckd below), not by the core's BRBIP32PrivKeyPath.
//    2. BY THE BRIDGE'S OWN slip13.c, compiled into this binary: the path indexes are held to it.
//    3. BY A MODEL. `b_...` below is the bridge's C written out line for line without JNI (a jstring
//       becomes its bytes or its UTF-16 units, NULL a Java null). The sweep_* cases hold the header to
//       it over fixed pseudo-random inputs. The URI's bytes are what ART's GetStringUTFChars writes:
//       art8_utf below is ART's own encoder (Android 8.0 to 12) line for line, and
//       digiid_core_bridge_main.c's host env writes them with the newer ART's.
//
//  IdentityKeyPolicyTest's assertions are not here: IdentityKeyPolicy (which identity a site gets) stays
//  in Kotlin; only the C moved.
//
//  One case per process: `digiid_core_kat <case>` prints PASS:/FAIL: lines and `RESULT <case> pass|fail`;
//  `list` prints the case names. run.sh builds one binary per DIGIID_UNFIXED arm and holds each
//  comparison arm to failing exactly its cases.
//

#include "BRDigiId.h"
#include "BRBIP39Mnemonic.h"
#include "slip13.h"   // the bridge's own
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int digiid_core_kat_tu2(void);   // run.sh's second translation unit, which includes the header twice

static int FAILED = 0;

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (! cond) FAILED = 1;
    printf(cond ? "PASS: " : "FAIL: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

#define N(a) (sizeof(a) / sizeof((a)[0]))
#define HARD 0x80000000u

static uint8_t WORK[1 << 18];   // the suite's work buffer: larger than any input here needs

// ---- fixtures -------------------------------------------------------------------------------------

static const char *ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
static const char *INHALE = "inhale praise target steak garlic cricket paper better evil almost sadness crawl city banner amused "
                            "fringe fox insect roast aunt prefer hollow basic ladder";

static void seedOf(uint8_t seed[64], const char *phrase) { BRBIP39DeriveKey(seed, phrase, NULL); }

// The bridge's answers for ABANDON (digiid_core_bridge print).
static const char *SHARED_ADDRESS = "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn";
static const char *LOGIN = "digiid://api.digiscope.me/api/auth/digiid/callback?x=00112233445566778899aabbccddeeff";
static const char *LOGIN_ANSWER_P2PKH =
    "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn|IJLIETravKerVp4gJvRQ06/4Iply4OS/rx8qzRCV5MfVJxwTH0pm0cSkHYy8JvOYVUNrD+ldzl5b1qQZZ6CYmqU=";
static const char *LOGIN_ANSWER_P2WPKH =
    "dgb1q0r60rmemrz7gk0xljrtvd6vt7cek7mmfsqjh3z|IJLIETravKerVp4gJvRQ06/4Iply4OS/rx8qzRCV5MfVJxwTH0pm0cSkHYy8JvOYVUNrD+ldzl5b1qQZZ6CYmqU=";
static const char *EMPTY_ANSWER =
    "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn|H8FWF4XbSvrhxdYGp4k7w5C0eibZUum5mhSZdR3VAU8kNWT9jfOVlRaBwLFOL6fGnwwiHO9Iz5bebcFxAfrKgVk=";
static const char *REQUEST = "digiid://example.com/callback?x=abc123";          // DigiIdIdentityChoiceTest's
static const char *REQUEST_CALLBACK = "https://example.com/callback";
static const char *REQUEST_SITE_ANSWER =
    "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68|H04hUIUu+y0CNyLxVH35AVWVzTxQgDJxiRyH7JP9RUHnEZfnLkA70TurMuFs1A1YTbYfC5y0WF6fROn2Z7SA1dI=";
static const char *REQUEST_SHARED_ANSWER =
    "DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn|H8xIXGJ7lXa6l/eDeVwtkusc6h0Aa8CN7s9FQoEDUBzrTGwOe+xF2oes1/uS/qf2La+8L1tjP4R3fqeHQyc84fk=";
// A callback URL with an emoji in its path, as Android hashes it (ART writes the pair as its four-byte
// character), and two that only GetStringUTFChars's U+0000 and lone-surrogate rules reach: the bridge's
// addresses, at index 0, and the request signed with the first.
static const char *EMOJI_URI_ADDRESS = "DJ7bsa92E89mALV8yB9x7d6K1icB8Q9KHP";
static const char *NUL_URI_ADDRESS = "DC4WY65HLkjENNHiQLFq4vU2vDrb7L41d4";
static const char *LONE_URI_ADDRESS = "DP7tdtpUk51YSBP5iWCWMXznSTSoKpHuva";
static const char *EMOJI_URI_REQUEST_ANSWER =
    "DJ7bsa92E89mALV8yB9x7d6K1icB8Q9KHP|H8PhhdIpZA4vNCMkr9DLjH5GyhqKOPPQoeE8e9DpJSXqbScat2Iwc3k2Utr6q1ZCc3ZRl1nk0vMMor4bQGx0D/s=";
// The same emoji URL in the JNI specification's modified UTF-8 (the pair as two three-byte halves), which
// ART does not write: an independent model's address for it (not the bridge's, which never sees it).
static const char *EMOJI_URI_SPEC_ADDRESS = "DJEY3MhnjYjB7RxKHfxfZeZiyDu6ziGV6F";
static const char *EMOJI_SITE_ANSWER =
    "DFfdC1dpu7GjqwbHBGwMbuHzwtfBE2r5Wx|IEjMM6/dfsv1K8w5HKPNCFHV/bjkzpPkLyi9KRdyyqm5a70pMTDcL5Bc+HjtmX+FblU+9Up/NIXxIVDNNLfAbDE=";

// ---- a BIP32 of this file's own (hardened and normal private steps), to hold keys outside the core --

static void ckd(UInt256 *k, UInt256 *c, uint32_t i)
{
    uint8_t buf[37];
    UInt512 I;
    UInt256 il;
    BRKey pub;

    if (i & HARD) {
        buf[0] = 0;
        memcpy(buf + 1, k, 32);
    }
    else {
        BRKeySetSecret(&pub, k, 1);
        BRKeyPubKey(&pub, buf, 33);
        BRKeyClean(&pub);
    }
    buf[33] = (uint8_t)(i >> 24);
    buf[34] = (uint8_t)(i >> 16);
    buf[35] = (uint8_t)(i >> 8);
    buf[36] = (uint8_t)i;
    BRHMAC(&I, BRSHA512, 64, c, 32, buf, 37);
    memcpy(&il, I.u8, 32);
    memcpy(c, I.u8 + 32, 32);
    BRSecp256k1ModAdd(k, &il);
}

// The secret at path[0, depth) from seed[0, seedLen), the master key being HMAC-SHA512("DigiByte seed", seed).
static UInt256 ownSecret(const uint8_t *seed, size_t seedLen, const uint32_t *path, int depth)
{
    static const char mk[] = "DigiByte seed";
    UInt512 I;
    UInt256 k, c;
    int i;

    BRHMAC(&I, BRSHA512, 64, mk, strlen(mk), seed, seedLen);
    memcpy(&k, I.u8, 32);
    memcpy(&c, I.u8 + 32, 32);
    for (i = 0; i < depth; i++) ckd(&k, &c, path[i]);
    return k;
}

static int sameSecret(const BRKey *key, UInt256 s) { return memcmp(&key->secret, &s, 32) == 0; }

// Fills the stack below the caller with 0x5a, so that a callee's buffer it forgets to clear is not
// zero by luck (Apple clang has no MemorySanitizer to say so).
#if defined(__GNUC__) || defined(__clang__)
__attribute__((noinline))
#endif
static void dirtyStack(void)
{
    volatile uint8_t junk[16384];
    size_t i;

    for (i = 0; i < sizeof(junk); i++) junk[i] = 0x5a;
}

static int allZero(const void *p, size_t n)
{
    const uint8_t *b = p;
    size_t i;

    for (i = 0; i < n; i++) if (b[i]) return 0;
    return 1;
}

// ---- the bridge, line for line without JNI ------------------------------------------------------

// seed_derive_identity_key (jni_wallet.c), the seed as a parameter (NULL: the session is locked).
static int b_seed_derive_identity_key(BRKey *outKey, const uint8_t *seed, size_t seedLen, const char *uri, size_t uriLen,
                                      uint32_t index)
{
    uint32_t idx[4];

    if (! seed || ! outKey || ! uri) return 0;
    if (! slip13_indexes(idx, uri, uriLen, index)) return 0;
    BRBIP32PrivKeyPath(outKey, seed, seedLen, 5, 13 | BIP32_HARD, idx[0] | BIP32_HARD, idx[1] | BIP32_HARD, idx[2] | BIP32_HARD,
                       idx[3] | BIP32_HARD);
    return 1;
}

// seed_derive_key (jni_wallet.c).
static int b_seed_derive_key(BRKey *outKey, const uint8_t *seed, size_t seedLen, uint32_t chain, uint32_t index)
{
    if (! seed) return 0;
    BRBIP32PrivKey(outKey, seed, seedLen, chain, index);
    return 1;
}

static const char b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static size_t b_base64_encode(char *out, size_t outLen, const uint8_t *in, size_t inLen)
{
    size_t needed = ((inLen + 2) / 3) * 4 + 1;
    if (! out || outLen < needed) return needed;

    size_t pos = 0;
    for (size_t i = 0; i < inLen; i += 3) {
        uint32_t n = ((uint32_t)in[i]) << 16;
        if (i + 1 < inLen) n |= ((uint32_t)in[i + 1]) << 8;
        if (i + 2 < inLen) n |= (uint32_t)in[i + 2];

        out[pos++] = b64_table[(n >> 18) & 0x3F];
        out[pos++] = b64_table[(n >> 12) & 0x3F];
        out[pos++] = (i + 1 < inLen) ? b64_table[(n >> 6) & 0x3F] : '=';
        out[pos++] = (i + 2 < inLen) ? b64_table[n & 0x3F] : '=';
    }
    out[pos] = '\0';
    return pos;
}

static size_t b_write_varint(uint8_t *buf, uint64_t val)
{
    if (val < 0xfd) {
        buf[0] = (uint8_t)val;
        return 1;
    } else if (val <= 0xffff) {
        buf[0] = 0xfd;
        buf[1] = (uint8_t)(val & 0xff);
        buf[2] = (uint8_t)((val >> 8) & 0xff);
        return 3;
    }
    buf[0] = (uint8_t)val;
    return 1;
}

// message_utf8, from the string's UTF-16 units: a buffer the caller frees, *outLen bytes.
static uint8_t *b_message_utf8(const uint16_t *units, size_t n, size_t *outLen)
{
    uint8_t *out = malloc(n * 3 + 1);
    size_t len = 0;

    if (! out) return NULL;
    for (size_t i = 0; i < n; i++) {
        uint32_t c = units[i];
        if (c < 0x80) {
            out[len++] = (uint8_t)c;
        } else if (c < 0x800) {
            out[len++] = (uint8_t)(0xC0 | (c >> 6));
            out[len++] = (uint8_t)(0x80 | (c & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
            uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + ((uint32_t)units[i + 1] - 0xDC00);
            i++;
            out[len++] = (uint8_t)(0xF0 | (cp >> 18));
            out[len++] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
            out[len++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
            out[len++] = (uint8_t)(0x80 | (cp & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            out[len++] = '?';
        } else {
            out[len++] = (uint8_t)(0xE0 | (c >> 12));
            out[len++] = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
            out[len++] = (uint8_t)(0x80 | (c & 0x3F));
        }
    }
    *outLen = len;
    return out;
}

// sign_with_key: the answer into result[256] and 1, or 0 (NULL). Cleans the key in every path.
// ART's ConvertUtf16ToModifiedUtf8 as Android 8.0 to 12 have it (runtime/utf.cc on 8.x, then
// libdexfile/dex/utf.cc), which their GetStringUTFChars calls, line for line but for its all-ASCII fast
// path (a copy of the same bytes). Writes into utf8_out (room for 3 * char_count); returns the bytes.
static size_t art8_utf(uint8_t *utf8_out, const uint16_t *utf16_in, size_t char_count)
{
    uint8_t *start = utf8_out;

    while (char_count--) {
        const uint16_t ch = *utf16_in++;
        if (ch > 0 && ch <= 0x7f) {
            *utf8_out++ = (uint8_t)ch;
        } else {
            if ((ch >= 0xd800 && ch <= 0xdbff) && (char_count > 0)) {
                const uint16_t ch2 = *utf16_in;
                if (ch2 >= 0xdc00 && ch2 <= 0xdfff) {
                    utf16_in++;
                    char_count--;
                    const uint32_t code_point = ((uint32_t)ch << 10) + ch2 - 0x035fdc00;
                    *utf8_out++ = (uint8_t)((code_point >> 18) | 0xf0);
                    *utf8_out++ = (uint8_t)(((code_point >> 12) & 0x3f) | 0x80);
                    *utf8_out++ = (uint8_t)(((code_point >> 6) & 0x3f) | 0x80);
                    *utf8_out++ = (uint8_t)((code_point & 0x3f) | 0x80);
                    continue;
                }
            }
            if (ch > 0x07ff) {
                *utf8_out++ = (uint8_t)((ch >> 12) | 0xe0);
                *utf8_out++ = (uint8_t)(((ch >> 6) & 0x3f) | 0x80);
                *utf8_out++ = (uint8_t)((ch & 0x3f) | 0x80);
            } else {
                *utf8_out++ = (uint8_t)((ch >> 6) | 0xc0);
                *utf8_out++ = (uint8_t)((ch & 0x3f) | 0x80);
            }
        }
    }
    return (size_t)(utf8_out - start);
}

static int b_sign_with_key(char result[256], BRKey *key, const uint8_t *msgChars, size_t msgLen, int32_t addressFormat)
{
    key->compressed = 1;

    char addr[75];
    memset(addr, 0, sizeof(addr));
    if (addressFormat == 2) {
        BRKeySegwitAddress(key, addr, sizeof(addr), 0);
    } else {
        BRKeyAddress(key, addr, sizeof(addr));
    }

    if (addr[0] == '\0') {
        BRKeyClean(key);
        return 0;
    }

    static const char header[] = "\x19" "DigiByte Signed Message:\n";
    size_t headerLen = sizeof(header) - 1;

    uint8_t varint[9];
    size_t varintLen = b_write_varint(varint, msgLen);

    size_t totalLen = headerLen + varintLen + msgLen;
    uint8_t *payload = malloc(totalLen);
    if (! payload) {
        BRKeyClean(key);
        return 0;
    }

    memcpy(payload, header, headerLen);
    memcpy(payload + headerLen, varint, varintLen);
    memcpy(payload + headerLen + varintLen, msgChars, msgLen);

    UInt256 md;
    BRSHA256_2(&md, payload, totalLen);
    free(payload);

    uint8_t compactSig[65];
    size_t sigLen = BRKeyCompactSign(key, compactSig, sizeof(compactSig), md);
    BRKeyClean(key);

    if (sigLen == 0) return 0;

    char sigB64[92];
    b_base64_encode(sigB64, sizeof(sigB64), compactSig, sigLen);
    snprintf(result, 256, "%s|%s", addr, sigB64);
    return 1;
}

// signMessage, after the wallet check: seed NULL is a locked session, msg NULL a Java null.
static int b_signMessage(char result[256], const uint8_t *seed, const uint8_t *msg, size_t msgLen, int32_t addressFormat)
{
    BRKey key;

    if (! seed || ! msg) return 0;
    memset(&key, 0, sizeof(key));
    if (! b_seed_derive_key(&key, seed, 64, 0, 0)) return 0;
    return b_sign_with_key(result, &key, msg, msgLen, addressFormat);
}

// signIdentityMessage, after the wallet check.
static int b_signIdentityMessage(char result[256], const uint8_t *seed, const uint8_t *msg, size_t msgLen, const char *uri,
                                 size_t uriLen, int32_t index)
{
    BRKey key;

    if (! seed) return 0;
    if (! msg || ! uri || index < 0) return 0;
    memset(&key, 0, sizeof(key));
    if (! b_seed_derive_identity_key(&key, seed, 64, uri, uriLen, (uint32_t)index)) return 0;
    return b_sign_with_key(result, &key, msg, msgLen, 0);
}

// identityAddress, after the wallet check; uri NULL is the Java null (the shared identity).
static int b_identityAddress(char addr[75], const uint8_t *seed, const char *uri, size_t uriLen, int32_t index)
{
    BRKey key;
    int derived;

    if (! seed || index < 0) return 0;
    memset(&key, 0, sizeof(key));
    if (uri) derived = b_seed_derive_identity_key(&key, seed, 64, uri, uriLen, (uint32_t)index);
    else derived = index == 0 && b_seed_derive_key(&key, seed, 64, 0, 0);
    if (! derived) {
        BRKeyClean(&key);
        return 0;
    }

    key.compressed = 1;
    memset(addr, 0, 75);
    BRKeyAddress(&key, addr, 75);
    BRKeyClean(&key);
    return addr[0] != '\0';
}

// ---- helpers over the header ---------------------------------------------------------------------

static BRDigiIdStatus hSite(char *out, const uint8_t *seed, const char *msg, const char *uri, int32_t index)
{
    return BRDigiIdSignSite(out, BR_DIGIID_ANSWER_CAPACITY, seed, 64, (const uint8_t *)msg, strlen(msg), uri, strlen(uri), index, WORK,
                            sizeof(WORK));
}

static BRDigiIdStatus hShared(char *out, const uint8_t *seed, const char *msg, int32_t format)
{
    return BRDigiIdSignShared(out, BR_DIGIID_ANSWER_CAPACITY, seed, 64, (const uint8_t *)msg, strlen(msg), format, WORK, sizeof(WORK));
}

static BRDigiIdStatus hAddress(char *out, const uint8_t *seed, const char *uri, int32_t index)
{
    return BRDigiIdAddress(out, BR_DIGIID_ADDRESS_CAPACITY, seed, 64, uri != NULL, uri, uri ? strlen(uri) : 0, index, WORK,
                           sizeof(WORK));
}

static int b64value(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static size_t b64decode(const char *in, uint8_t *out, size_t cap)
{
    size_t n = 0, len = strlen(in), i;
    int k;

    if (len % 4 != 0) return 0;
    for (i = 0; i < len; i += 4) {
        int v[4];
        uint32_t w;
        uint8_t b[3];
        int take;

        for (k = 0; k < 4; k++) v[k] = (in[i + k] == '=') ? 0 : b64value(in[i + k]);
        if (v[0] < 0 || v[1] < 0 || v[2] < 0 || v[3] < 0) return 0;
        w = ((uint32_t)v[0] << 18) | ((uint32_t)v[1] << 12) | ((uint32_t)v[2] << 6) | (uint32_t)v[3];
        b[0] = (uint8_t)(w >> 16); b[1] = (uint8_t)(w >> 8); b[2] = (uint8_t)w;
        take = 3 - (in[i + 2] == '=') - (in[i + 3] == '=');
        for (k = 0; k < take; k++) { if (n >= cap) return 0; out[n++] = b[k]; }
    }
    return n;
}

// True when `answer` is a signature by its address's P2PKH key over the message format of exactly bytes[0, len)
// (written here: standard compact size up to 0xffff, as identity_sign_bytes_kat checks it).
static int signsBytes(const char *answer, const uint8_t *bytes, size_t len)
{
    static const char header[] = "\x19" "DigiByte Signed Message:\n";
    const char *bar = answer ? strchr(answer, '|') : NULL;
    char address[75] = { 0 }, recovered[75] = { 0 };
    uint8_t sig[65], *payload;
    size_t at = 0, addressLen;
    UInt256 md;
    BRKey key;

    if (! bar || len > 0xffff) return 0;
    addressLen = (size_t)(bar - answer);
    if (addressLen == 0 || addressLen >= sizeof(address)) return 0;
    memcpy(address, answer, addressLen);
    if (b64decode(bar + 1, sig, sizeof(sig)) != sizeof(sig)) return 0;

    payload = malloc(26 + 3 + len);
    memcpy(payload, header, 26); at = 26;
    if (len < 0xfd) payload[at++] = (uint8_t)len;
    else { payload[at++] = 0xfd; payload[at++] = (uint8_t)(len & 0xff); payload[at++] = (uint8_t)(len >> 8); }
    if (len) memcpy(payload + at, bytes, len);
    at += len;
    BRSHA256_2(&md, payload, at);
    free(payload);

    memset(&key, 0, sizeof(key));
    if (! BRKeyRecoverPubKey(&key, md, sig, sizeof(sig))) return 0;
    BRKeyAddress(&key, recovered, sizeof(recovered));
    return strcmp(recovered, address) == 0;
}

static const char *st(BRDigiIdStatus s)
{
    switch (s) {
        case BR_DIGIID_OK: return "OK";
        case BR_DIGIID_REFUSED: return "REFUSED";
        case BR_DIGIID_CAPACITY: return "CAPACITY";
        case BR_DIGIID_NO_ADDRESS: return "NO_ADDRESS";
        case BR_DIGIID_NOT_SIGNED: return "NOT_SIGNED";
    }
    return "?";
}

static int idxEq(const uint32_t a[4], uint32_t w, uint32_t x, uint32_t y, uint32_t z) { return a[0] == w && a[1] == x && a[2] == y && a[3] == z; }

// ---- the path ------------------------------------------------------------------------------------

// SLIP-0013's published example and the BitID draft's callback (slip13_identity_kat's vector).
static void slip13_published_vectors(void)
{
    uint32_t idx[4];
    const char *satoshi = "https://satoshi@bitcoin.org/login", *bitid = "http://bitid.bitcoin.blue/callback";

    ck(BRDigiIdSlip13Indexes(idx, satoshi, strlen(satoshi), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK, "SLIP-0013's example derives");
    // SLIP-0013: sha256 d0e2389d 4c8394a9 f3e32de0 1104bf6e ...; path m/2147483661/2637750992/2845082444/3761103859/4005495825
    ck(idxEq(idx, 0x9d38e2d0u, 0xa994834cu, 0xe02de3f3u, 0x6ebf0411u), "SLIP-0013's A..D (%08x %08x %08x %08x)", idx[0], idx[1], idx[2], idx[3]);
    ck((13u | HARD) == 2147483661u && (idx[0] | HARD) == 2637750992u && (idx[1] | HARD) == 2845082444u && (idx[2] | HARD) == 3761103859u &&
       (idx[3] | HARD) == 4005495825u, "SLIP-0013's path, hardened, as it prints it");
    ck(BRDigiIdSlip13Indexes(idx, bitid, strlen(bitid), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK, "the BitID callback derives");
    ck(idxEq(idx, 0xbe553112u, 0xc0af82cfu, 0x4361fb3bu, 0xedd2bf37u), "the BitID callback's A..D are slip13_identity_kat's");
}

// slip13_identity_kat, every assertion, on the header.
static void slip13_identity_carried(void)
{
    const char *vectorUri = "http://bitid.bitcoin.blue/callback", *otherUri = "https://example.com/digiid";
    uint32_t idx[4], idx2[4], idxOther[4], idxAcct1[4], z[4] = { 1, 2, 3, 4 };
    uint8_t seed[64];
    BRKey keyA, keyA2, keyB;
    char addrA[75] = { 0 }, addrA2[75] = { 0 }, addrB[75] = { 0 };
    int i;

    ck(BRDigiIdSlip13Indexes(idx, vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK, "vector derivation succeeds");
    ck(idx[0] == 0xbe553112u, "vector A == 0xbe553112");
    ck(idx[1] == 0xc0af82cfu, "vector B == 0xc0af82cf");
    ck(idx[2] == 0x4361fb3bu, "vector C == 0x4361fb3b");
    ck(idx[3] == 0xedd2bf37u, "vector D == 0xedd2bf37");
    BRDigiIdSlip13Indexes(idx2, vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK));
    ck(memcmp(idx, idx2, sizeof(idx)) == 0, "same uri+index -> same indexes");
    BRDigiIdSlip13Indexes(idxOther, otherUri, strlen(otherUri), 0, WORK, sizeof(WORK));
    ck(memcmp(idx, idxOther, sizeof(idx)) != 0, "different uri -> different indexes");
    BRDigiIdSlip13Indexes(idxAcct1, vectorUri, strlen(vectorUri), 1, WORK, sizeof(WORK));
    ck(memcmp(idx, idxAcct1, sizeof(idx)) != 0, "different account index -> different indexes");

    for (i = 0; i < 64; i++) seed[i] = (uint8_t)(i + 1);
    ck(BRDigiIdSiteKey(&keyA, seed, sizeof(seed), vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       BRDigiIdSiteKey(&keyA2, seed, sizeof(seed), vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       BRDigiIdSiteKey(&keyB, seed, sizeof(seed), otherUri, strlen(otherUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK, "the path keys derive");
    {
        uint32_t path[5] = { 13 | HARD, idx[0] | HARD, idx[1] | HARD, idx[2] | HARD, idx[3] | HARD };
        ck(sameSecret(&keyA, ownSecret(seed, 64, path, 5)), "the key is m/13'/A'/B'/C'/D' (this file's BIP32)");
    }
    keyA.compressed = 1; keyA2.compressed = 1; keyB.compressed = 1;
    BRKeyAddress(&keyA, addrA, sizeof(addrA));
    BRKeyAddress(&keyA2, addrA2, sizeof(addrA2));
    BRKeyAddress(&keyB, addrB, sizeof(addrB));
    ck(addrA[0] != '\0', "path key yields an address");
    ck(strcmp(addrA, addrA2) == 0, "path derivation is deterministic");
    ck(strcmp(addrA, addrB) != 0, "different site -> different identity address");
    BRKeyClean(&keyA); BRKeyClean(&keyA2); BRKeyClean(&keyB);

    ck(BRDigiIdSlip13Indexes(z, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "NULL uri refused");
    ck(z[0] == 0 && z[1] == 0 && z[2] == 0 && z[3] == 0, "output zeroed on failure");
    ck(BRDigiIdSlip13Indexes(NULL, vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "NULL out refused");
}

// The account index at its edges, held to literals read off the bridge's slip13.c and to that file itself.
static void slip13_index_values(void)
{
    static const struct { uint32_t index; uint32_t a, b, c, d; } V[] = {
        { 1u, 0x9ce568cau, 0x2623d7f1u, 0x842ce138u, 0x523605aau },
        { 0x01020304u, 0x5eae7f3fu, 0xca6ca051u, 0xb73531e2u, 0xc0cede64u },
        { 0x7fffffffu, 0xd946ef5du, 0x6b70ebdbu, 0x7bb18b8bu, 0x60bb1f19u },
        { 0x80000000u, 0x56d1cea2u, 0x692a1f2bu, 0xebad93d3u, 0x4c426012u },
        { 0xffffffffu, 0xb53e5e26u, 0xb0c1a446u, 0x6ffe38bbu, 0xb8aaa21cu },
    };
    static const uint32_t more[] = { 0, 2, 255, 256, 65535, 65536, 0x00ff00ffu, 0xff00ff00u, 0x7ffffffeu, 0x80000001u, 0xfffffffeu };
    uint32_t h[4], b[4];
    size_t i, n = strlen(REQUEST_CALLBACK);

    for (i = 0; i < N(V); i++) {
        BRDigiIdSlip13Indexes(h, REQUEST_CALLBACK, n, V[i].index, WORK, sizeof(WORK));
        ck(idxEq(h, V[i].a, V[i].b, V[i].c, V[i].d), "index 0x%08x: A..D as the bridge has them", V[i].index);
    }
    for (i = 0; i < N(more); i++) {
        BRDigiIdSlip13Indexes(h, REQUEST_CALLBACK, n, more[i], WORK, sizeof(WORK));
        slip13_indexes(b, REQUEST_CALLBACK, n, more[i]);
        ck(memcmp(h, b, sizeof(h)) == 0, "index 0x%08x: as the bridge's slip13.c", more[i]);
    }
}

// Empty and odd URIs, refusals and the work buffer.
static void slip13_edges(void)
{
    static const char odd[] = { 'h', 0x00, 't', (char)0x80, (char)0xff, (char)0xc0, (char)0x80, '/' };
    uint32_t h[4], b[4];
    size_t need;

    ck(BRDigiIdSlip13Indexes(h, "", 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       idxEq(h, 0x98613fdfu, 0xdb2fa904u, 0x2d195740u, 0x48d73dc4u), "an empty URI is hashed as LE32(0) alone, as the bridge does");
    BRDigiIdSlip13Indexes(h, odd, sizeof(odd), 7, WORK, sizeof(WORK));
    slip13_indexes(b, odd, sizeof(odd), 7);
    ck(memcmp(h, b, sizeof(h)) == 0, "a URI holding a zero byte and bytes above 0x7f is hashed as given, as the bridge hashes it");

    need = BRDigiIdIndexesWorkSize(sizeof(odd));
    ck(need == 4 + sizeof(odd), "work for an 8-byte URI is 12 bytes");
    ck(BRDigiIdSlip13Indexes(h, odd, sizeof(odd), 7, WORK, need) == BR_DIGIID_OK && memcmp(h, b, sizeof(h)) == 0, "work of exactly that size suffices");
    h[0] = 9;
    ck(BRDigiIdSlip13Indexes(h, odd, sizeof(odd), 7, WORK, need - 1) == BR_DIGIID_CAPACITY && allZero(h, sizeof(h)),
       "one byte less is CAPACITY, out zeroed");
    ck(BRDigiIdSlip13Indexes(h, odd, sizeof(odd), 7, NULL, 0) == BR_DIGIID_CAPACITY, "no work is CAPACITY");
    ck(BRDigiIdSlip13Indexes(h, "", 0, 0, WORK, 4) == BR_DIGIID_OK && BRDigiIdSlip13Indexes(h, "", 0, 0, WORK, 3) == BR_DIGIID_CAPACITY,
       "an empty URI needs 4 bytes of work");
    ck(BRDigiIdIndexesWorkSize(SIZE_MAX) == 0 && BRDigiIdIndexesWorkSize(SIZE_MAX - 3) == 0 && BRDigiIdIndexesWorkSize(SIZE_MAX - 4) == SIZE_MAX,
       "a URI length that does not fit with its 4 bytes has no work size");
    ck(BRDigiIdSlip13Indexes(h, "x", SIZE_MAX, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY, "and is CAPACITY, read no further");
    ck(BRDigiIdSlip13Indexes(NULL, NULL, 0, 0, NULL, 0) == BR_DIGIID_REFUSED, "nothing at all is refused");
}

// ---- the keys ------------------------------------------------------------------------------------

static void site_key_derivation(void)
{
    uint8_t seed[64], inhale[64];
    uint32_t idx[4];
    BRKey key, bitid;
    char addr[75];
    static const int32_t indexes[] = { 0, 1, 2, INT32_MAX };
    size_t i, n = strlen(REQUEST_CALLBACK);

    seedOf(seed, ABANDON);
    for (i = 0; i < N(indexes); i++) {
        uint32_t path[5];

        slip13_indexes(idx, REQUEST_CALLBACK, n, (uint32_t)indexes[i]);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        ck(BRDigiIdSiteKey(&key, seed, 64, REQUEST_CALLBACK, n, indexes[i], WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           sameSecret(&key, ownSecret(seed, 64, path, 5)), "index %d: the key is m/13'/A'/B'/C'/D' of the bridge's A..D", (int)indexes[i]);
        BRBIP32BitIDKey(&bitid, seed, 64, (uint32_t)indexes[i], REQUEST_CALLBACK);
        ck(memcmp(&key.secret, &bitid.secret, 32) == 0, "index %d: the key BRBIP32BitIDKey derives", (int)indexes[i]);
        BRKeyClean(&bitid);
    }

    seedOf(inhale, INHALE);
    ck(BRDigiIdSiteKey(&key, inhale, 64, "http://bitid.bitcoin.blue/callback", 34, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK, "the BitID phrase derives");
    key.compressed = 1;
    BRKeyAddress(&key, addr, sizeof(addr));
    ck(strcmp(addr, "DSFBMsXMiph22GAvPaqSKqnfm6n2ELUPMi") == 0, "the BitID phrase and callback: the bridge's address (%s)", addr);

    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, REQUEST_CALLBACK, n, -1, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "index -1 is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, REQUEST_CALLBACK, n, INT32_MIN, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "index INT32_MIN is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, NULL, 64, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "no seed (a locked session) is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)), "no URI is refused");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, REQUEST_CALLBACK, n, 0, WORK, 4 + n - 1) == BR_DIGIID_CAPACITY && allZero(&key, sizeof(key)),
       "too little work is CAPACITY, the key wiped");
    ck(BRDigiIdSiteKey(NULL, seed, 64, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no key is refused");
}

static void shared_key_derivation(void)
{
    static const uint32_t path[3] = { 0 | HARD, 0, 0 };
    uint8_t seed[64];
    BRKey key, ref;
    char addr[75];

    seedOf(seed, ABANDON);
    ck(BRDigiIdSharedKey(&key, seed, 64) == BR_DIGIID_OK && sameSecret(&key, ownSecret(seed, 64, path, 3)), "the shared identity is m/0'/0/0");
    BRBIP32PrivKey(&ref, seed, 64, 0, 0);
    ck(memcmp(&key.secret, &ref.secret, 32) == 0, "the key BRBIP32PrivKey(chain 0, index 0) derives, as the bridge's seed_derive_key");
    key.compressed = 1;
    BRKeyAddress(&key, addr, sizeof(addr));
    ck(strcmp(addr, SHARED_ADDRESS) == 0, "its address is the bridge's (%s)", addr);
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSharedKey(&key, NULL, 64) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)), "no seed is refused, the key wiped");
    ck(BRDigiIdSharedKey(NULL, seed, 64) == BR_DIGIID_REFUSED, "no key is refused");
    BRKeyClean(&key); BRKeyClean(&ref);
}

// ---- the message ---------------------------------------------------------------------------------

static void message_prefix(void)
{
    static const struct { size_t len; uint8_t bytes[3]; size_t n; } V[] = {
        { 0, { 0x00 }, 1 }, { 1, { 0x01 }, 1 }, { 0xfc, { 0xfc }, 1 }, { 0xfd, { 0xfd, 0xfd, 0x00 }, 3 },
        { 0xfe, { 0xfd, 0xfe, 0x00 }, 3 }, { 0x100, { 0xfd, 0x00, 0x01 }, 3 }, { 0xffff, { 0xfd, 0xff, 0xff }, 3 },
        { 0x10000, { 0x00 }, 1 }, { 0x10001, { 0x01 }, 1 }, { 0x100fc, { 0xfc }, 1 }, { 0x100fd, { 0xfd }, 1 },
        { 0x1ffff, { 0xff }, 1 }, { 0xffffffffu, { 0xff }, 1 },
    };
    uint8_t p[BR_DIGIID_MESSAGE_PREFIX_MAX], q[9];
    size_t i, n, m;

    for (i = 0; i < N(V); i++) {
        n = BRDigiIdMessagePrefix(p, V[i].len);
        m = b_write_varint(q, V[i].len);
        ck(n == V[i].n && memcmp(p, V[i].bytes, n) == 0 && n == m && memcmp(p, q, n) == 0, "a %zu-byte message: prefix %zu byte(s) %02x...",
           V[i].len, V[i].n, V[i].bytes[0]);
        // On a 32-bit size_t the 0xffffffff-byte message does not fit with its header: no work size.
        ck(BRDigiIdDigestWorkSize(V[i].len) == (V[i].len > SIZE_MAX - 26 - V[i].n ? 0 : 26 + V[i].n + V[i].len),
           "and its digest work is header + prefix + message");
    }
    ck(BRDigiIdMessagePrefix(p, SIZE_MAX) == 1 && p[0] == 0xff, "SIZE_MAX: one byte, 0xff");
    ck(BRDigiIdDigestWorkSize(SIZE_MAX) == 0 && BRDigiIdDigestWorkSize(SIZE_MAX - 27) == SIZE_MAX && BRDigiIdDigestWorkSize(SIZE_MAX - 26) == 0,
       "a message length that does not fit with its header has no work size");
}

static void message_digest(void)
{
    static const uint8_t hi[] = { 'h', 'i' };
    uint8_t buf[64];
    UInt256 md, want;
    size_t need = BRDigiIdDigestWorkSize(2);

    memcpy(buf, "\x19" "DigiByte Signed Message:\n", 26);
    buf[26] = 2; buf[27] = 'h'; buf[28] = 'i';
    BRSHA256_2(&want, buf, 29);
    ck(need == 29, "\"hi\" needs 29 bytes of work");
    ck(BRDigiIdMessageDigest(&md, hi, 2, WORK, need) == BR_DIGIID_OK && memcmp(&md, &want, 32) == 0, "\"hi\" is hashed twice over header, 02, hi");
    ck(BRDigiIdMessageDigest(&md, hi, 2, WORK, need - 1) == BR_DIGIID_CAPACITY && allZero(&md, 32), "one byte less is CAPACITY, the hash zero");
    ck(BRDigiIdMessageDigest(&md, (const uint8_t *)"", 0, WORK, 27) == BR_DIGIID_OK, "an empty message needs 27 bytes");
    ck(BRDigiIdMessageDigest(&md, NULL, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&md, 32), "no message is refused");
    ck(BRDigiIdMessageDigest(NULL, hi, 2, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no md is refused");
    ck(BRDigiIdMessageDigest(&md, hi, SIZE_MAX, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY, "a length with no work size is CAPACITY");
}

// identity_sign_bytes_kat's messages: the bytes a message is signed over.
static void utf8_bytes(void)
{
    static const uint16_t emoji[] = { 'g', 'm', ' ', 0xD83D, 0xDE00, ' ', '!' };
    static const uint8_t emojiUtf8[] = { 'g', 'm', ' ', 0xF0, 0x9F, 0x98, 0x80, ' ', '!' };
    static const uint16_t nul[] = { 'a', 0x0000, 'b' };
    static const uint8_t nulUtf8[] = { 'a', 0x00, 'b' };
    static const uint16_t lone[] = { 'a', 0xD800, 'b', 0xDC00 };
    static const uint8_t loneUtf8[] = { 'a', '?', 'b', '?' };
    static const uint16_t bmp[] = { 0x00E9, ' ', 0x00FC, ' ', 0x4E2D };
    static const uint8_t bmpUtf8[] = { 0xC3, 0xA9, ' ', 0xC3, 0xBC, ' ', 0xE4, 0xB8, 0xAD };
    static const uint16_t edges[] = { 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xDBFF, 0xDFFF, 0xDC00, 0xD800, 0xE000, 0xFFFF, 0xDBFF, 0xDFFF };
    static const uint8_t edgesUtf8[] = { 0x7F, 0xC2, 0x80, 0xDF, 0xBF, 0xE0, 0xA0, 0x80, 0xED, 0x9F, 0xBF, 0xF4, 0x8F, 0xBF, 0xBF, '?', '?',
                                         0xEE, 0x80, 0x80, 0xEF, 0xBF, 0xBF, 0xF4, 0x8F, 0xBF, 0xBF };
    static const uint16_t highAtEnd[] = { 'x', 0xD83D };
    static const struct { const char *name; const uint16_t *u; size_t n; const uint8_t *b; size_t bn; } V[] = {
        { "an emoji (a surrogate pair) as its four bytes", emoji, N(emoji), emojiUtf8, sizeof(emojiUtf8) },
        { "U+0000 as one 00 byte", nul, N(nul), nulUtf8, sizeof(nulUtf8) },
        { "a surrogate without its partner as '?'", lone, N(lone), loneUtf8, sizeof(loneUtf8) },
        { "two- and three-byte characters as they always were", bmp, N(bmp), bmpUtf8, sizeof(bmpUtf8) },
        { "the encoding's boundaries", edges, N(edges), edgesUtf8, sizeof(edgesUtf8) },
    };
    uint8_t out[64];
    size_t i, len, blen;
    uint8_t *b;

    for (i = 0; i < N(V); i++) {
        ck(BRDigiIdUtf8FromUtf16(out, sizeof(out), &len, V[i].u, V[i].n) == BR_DIGIID_OK && len == V[i].bn && memcmp(out, V[i].b, len) == 0,
           "%s", V[i].name);
        b = b_message_utf8(V[i].u, V[i].n, &blen);
        ck(blen == len && memcmp(b, out, len) == 0, "and the bridge's message_utf8 writes the same");
        free(b);
    }
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(out), &len, highAtEnd, 2) == BR_DIGIID_OK && len == 2 && out[1] == '?', "a high surrogate at the end is '?'");
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(out), &len, emoji, 0) == BR_DIGIID_OK && len == 0, "no units, no bytes");

    memset(out, 0x5a, sizeof(out));
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(emojiUtf8) - 1, &len, emoji, N(emoji)) == BR_DIGIID_CAPACITY && len == sizeof(emojiUtf8) &&
       out[0] == 0x5a, "a byte too few is CAPACITY: the length needed, nothing written");
    ck(BRDigiIdUtf8FromUtf16(NULL, 0, &len, emoji, N(emoji)) == BR_DIGIID_CAPACITY && len == 9, "no out asks the length");
    ck(BRDigiIdUtf8FromUtf16(NULL, 64, &len, emoji, N(emoji)) == BR_DIGIID_CAPACITY && len == 9, "no out is CAPACITY whatever outCap says");
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(emojiUtf8), &len, emoji, N(emoji)) == BR_DIGIID_OK && len == 9, "exactly the length suffices");
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(out), &len, NULL, 0) == BR_DIGIID_REFUSED && len == 0, "no units (a null message) is refused");
    ck(BRDigiIdUtf8FromUtf16(out, sizeof(out), NULL, emoji, N(emoji)) == BR_DIGIID_REFUSED, "no outLen is refused");
}

// The URI's bytes: what ART's GetStringUTFChars writes, written out here by hand.
static void uri_bytes(void)
{
    static const uint16_t emoji[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                      0xD83D, 0xDE00 };
    static const uint8_t emojiBytes[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                          0xF0, 0x9F, 0x98, 0x80 };
    static const uint16_t nul[] = { 'a', 0x0000, 'b' };
    static const uint8_t nulBytes[] = { 'a', 0xC0, 0x80, 'b' };
    static const uint16_t lone[] = { 'a', 0xD800, 'b', 0xDC00 };
    static const uint8_t loneBytes[] = { 'a', 0xED, 0xA0, 0x80, 'b', 0xED, 0xB0, 0x80 };
    static const uint16_t highAtEnd[] = { 'x', 0xD83D };
    static const uint8_t highAtEndBytes[] = { 'x', 0xED, 0xA0, 0xBD };
    static const uint16_t highHighLow[] = { 0xD800, 0xD83D, 0xDE00 };
    static const uint8_t highHighLowBytes[] = { 0xED, 0xA0, 0x80, 0xF0, 0x9F, 0x98, 0x80 };
    static const uint16_t lowHigh[] = { 0xDC00, 0xD800 };
    static const uint8_t lowHighBytes[] = { 0xED, 0xB0, 0x80, 0xED, 0xA0, 0x80 };
    static const uint16_t edges[] = { 0x01, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xDBFF, 0xDFFF, 0xE000, 0xFFFF, 0xD800, 0xDC00 };
    static const uint8_t edgesBytes[] = { 0x01, 0x7F, 0xC2, 0x80, 0xDF, 0xBF, 0xE0, 0xA0, 0x80, 0xED, 0x9F, 0xBF, 0xF4, 0x8F, 0xBF, 0xBF,
                                          0xEE, 0x80, 0x80, 0xEF, 0xBF, 0xBF, 0xF0, 0x90, 0x80, 0x80 };
    static const struct { const char *name; const uint16_t *u; size_t n; const uint8_t *b; size_t bn; } V[] = {
        { "an emoji in the path (a surrogate pair) as its four bytes, as ART writes it", emoji, N(emoji), emojiBytes, sizeof(emojiBytes) },
        { "U+0000 as C0 80", nul, N(nul), nulBytes, sizeof(nulBytes) },
        { "a surrogate without its partner as its own three bytes", lone, N(lone), loneBytes, sizeof(loneBytes) },
        { "a high surrogate at the end as its three bytes", highAtEnd, N(highAtEnd), highAtEndBytes, sizeof(highAtEndBytes) },
        { "a high surrogate, then a pair", highHighLow, N(highHighLow), highHighLowBytes, sizeof(highHighLowBytes) },
        { "a low surrogate, then a high one: two lone ones", lowHigh, N(lowHigh), lowHighBytes, sizeof(lowHighBytes) },
        { "the encoding's boundaries (U+10FFFF and U+10000 as pairs)", edges, N(edges), edgesBytes, sizeof(edgesBytes) },
    };
    uint16_t ascii[64];
    uint8_t out[64], art[192];
    size_t i, len, alen;

    for (i = 0; i < N(V); i++) {
        ck(BRDigiIdUriBytesFromUtf16(out, sizeof(out), &len, V[i].u, V[i].n) == BR_DIGIID_OK && len == V[i].bn && memcmp(out, V[i].b, len) == 0,
           "%s", V[i].name);
        alen = art8_utf(art, V[i].u, V[i].n);
        ck(alen == len && memcmp(art, out, len) == 0, "and ART's own encoder writes the same");
        ck(memchr(out, 0, len) == NULL && len <= 3 * V[i].n, "no 00 byte, at most three bytes a unit");
    }
    for (i = 0; i < strlen(REQUEST_CALLBACK); i++) ascii[i] = (uint16_t)REQUEST_CALLBACK[i];
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(out), &len, ascii, strlen(REQUEST_CALLBACK)) == BR_DIGIID_OK && len == strlen(REQUEST_CALLBACK) &&
       memcmp(out, REQUEST_CALLBACK, len) == 0, "an ASCII callback URL is its own bytes");
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(out), &len, emoji, 0) == BR_DIGIID_OK && len == 0, "no units, no bytes");

    memset(out, 0x5a, sizeof(out));
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(emojiBytes) - 1, &len, emoji, N(emoji)) == BR_DIGIID_CAPACITY && len == sizeof(emojiBytes) &&
       out[0] == 0x5a, "a byte too few is CAPACITY: the length needed, nothing written");
    ck(BRDigiIdUriBytesFromUtf16(NULL, 0, &len, nul, N(nul)) == BR_DIGIID_CAPACITY && len == 4, "no out asks the length");
    ck(BRDigiIdUriBytesFromUtf16(NULL, 64, &len, nul, N(nul)) == BR_DIGIID_CAPACITY && len == 4, "no out is CAPACITY whatever outCap says");
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(emojiBytes), &len, emoji, N(emoji)) == BR_DIGIID_OK && len == sizeof(emojiBytes),
       "exactly the length suffices");
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(out), &len, NULL, 0) == BR_DIGIID_REFUSED && len == 0, "no units (a null URI) is refused");
    ck(BRDigiIdUriBytesFromUtf16(out, sizeof(out), NULL, emoji, N(emoji)) == BR_DIGIID_REFUSED, "no outLen is refused");
}

// A site whose callback URL is not ASCII gets the identity it gets on Android: the bridge's addresses.
static void uri_identity(void)
{
    static const uint16_t emoji[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                      0xD83D, 0xDE00 };
    static const uint16_t nul[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                    'a', 0x0000, 'b' };
    static const uint16_t lone[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                     'a', 0xD800, 'b' };
    static const uint8_t emojiSpec[] = { 'h', 't', 't', 'p', 's', ':', '/', '/', 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', '/',
                                         0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80 };
    static const struct { const char *name; const uint16_t *u; size_t n; const char **address; } V[] = {
        { "https://example.com/<U+1F600>", emoji, N(emoji), &EMOJI_URI_ADDRESS },
        { "https://example.com/a<U+0000>b", nul, N(nul), &NUL_URI_ADDRESS },
        { "https://example.com/a<U+D800>b", lone, N(lone), &LONE_URI_ADDRESS },
    };
    uint8_t seed[64], uri[96];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY];
    size_t i, len;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(V); i++) {
        ck(BRDigiIdUriBytesFromUtf16(uri, sizeof(uri), &len, V[i].u, V[i].n) == BR_DIGIID_OK &&
           BRDigiIdAddress(addr, sizeof(addr), seed, 64, 1, (const char *)uri, len, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           strcmp(addr, *V[i].address) == 0, "%s: the bridge's address (%s)", V[i].name, addr);
    }
    BRDigiIdUriBytesFromUtf16(uri, sizeof(uri), &len, emoji, N(emoji));
    ck(BRDigiIdSignSite(answer, sizeof(answer), seed, 64, (const uint8_t *)REQUEST, strlen(REQUEST), (const char *)uri, len, 0, WORK,
                        sizeof(WORK)) == BR_DIGIID_OK && strcmp(answer, EMOJI_URI_REQUEST_ANSWER) == 0,
       "the request signed with the emoji URL's identity: the bridge's answer");
    ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, 1, (const char *)emojiSpec, sizeof(emojiSpec), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       strcmp(addr, EMOJI_URI_SPEC_ADDRESS) == 0 && strcmp(addr, EMOJI_URI_ADDRESS) != 0,
       "the JNI specification's bytes for it would be another identity (%s), as an independent model says", addr);
}

// ---- signing -------------------------------------------------------------------------------------

// identity_sign_bytes_kat's ascii scenario and the shared identity's answers.
static void sign_shared_login(void)
{
    static const int32_t legacyFormats[] = { 0, 1, 3, -1, INT32_MIN, INT32_MAX };
    uint8_t seed[64];
    char out[BR_DIGIID_ANSWER_CAPACITY], small[BR_DIGIID_ANSWER_CAPACITY];
    size_t i, len;

    seedOf(seed, ABANDON);
    ck(hShared(out, seed, LOGIN, 0) == BR_DIGIID_OK && strcmp(out, LOGIN_ANSWER_P2PKH) == 0, "the login, format 0: the bridge's answer");
    ck(signsBytes(out, (const uint8_t *)LOGIN, strlen(LOGIN)), "the signature is over the login's bytes");
    for (i = 0; i < N(legacyFormats); i++)
        ck(hShared(out, seed, LOGIN, legacyFormats[i]) == BR_DIGIID_OK && strcmp(out, LOGIN_ANSWER_P2PKH) == 0, "format %d answers P2PKH",
           (int)legacyFormats[i]);
    ck(hShared(out, seed, LOGIN, 2) == BR_DIGIID_OK && strcmp(out, LOGIN_ANSWER_P2WPKH) == 0, "format 2 answers P2WPKH, the same signature");
    ck(hShared(out, seed, "", 0) == BR_DIGIID_OK && strcmp(out, EMPTY_ANSWER) == 0, "an empty message: the bridge's answer");

    len = strlen(LOGIN_ANSWER_P2PKH);
    ck(BRDigiIdSignShared(small, len + 1, seed, 64, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       strcmp(small, LOGIN_ANSWER_P2PKH) == 0, "out of exactly the answer and its NUL suffices");
    ck(BRDigiIdSignShared(small, len, seed, 64, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY &&
       small[0] == '\0', "a byte less is CAPACITY, out empty");
    ck(BRDigiIdSignShared(out, sizeof(out), seed, 64, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, BRDigiIdDigestWorkSize(strlen(LOGIN)) - 1) ==
       BR_DIGIID_CAPACITY && out[0] == '\0', "too little work is CAPACITY");
    ck(BRDigiIdSignShared(out, sizeof(out), NULL, 64, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       out[0] == '\0', "no seed (a locked session) is refused");
    ck(BRDigiIdSignShared(out, sizeof(out), seed, 64, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no message is refused");
    ck(BRDigiIdSignShared(NULL, 0, seed, 64, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no out is refused");
}

// DigiIdIdentityChoiceTest's request, and identity_sign_bytes_kat's identity scenario.
static void sign_site_request(void)
{
    static const uint16_t emoji[] = { 'g', 'm', ' ', 0xD83D, 0xDE00, ' ', '!' };
    uint8_t seed[64], msg[32];
    char site[BR_DIGIID_ANSWER_CAPACITY], shared[BR_DIGIID_ANSWER_CAPACITY], a1[BR_DIGIID_ADDRESS_CAPACITY], a2[BR_DIGIID_ADDRESS_CAPACITY];
    size_t len;

    seedOf(seed, ABANDON);
    ck(hSite(site, seed, REQUEST, REQUEST_CALLBACK, 0) == BR_DIGIID_OK && strcmp(site, REQUEST_SITE_ANSWER) == 0,
       "the request signed with the per-site identity of its callback: the bridge's answer");
    ck(signsBytes(site, (const uint8_t *)REQUEST, strlen(REQUEST)), "over the request's bytes (the URI with its nonce)");
    ck(hShared(shared, seed, REQUEST, 0) == BR_DIGIID_OK && strcmp(shared, REQUEST_SHARED_ANSWER) == 0, "and with the shared identity: the bridge's");
    ck(hAddress(a1, seed, REQUEST_CALLBACK, 0) == BR_DIGIID_OK && hAddress(a2, seed, NULL, 0) == BR_DIGIID_OK && strcmp(a1, a2) != 0,
       "the two identities the choice offers differ (%s, %s)", a1, a2);
    ck(strncmp(site, a1, strlen(a1)) == 0 && site[strlen(a1)] == '|', "the per-site address offered is the one the login signs with");
    ck(strncmp(shared, a2, strlen(a2)) == 0 && shared[strlen(a2)] == '|', "and the shared one likewise");
    ck(hAddress(a1, NULL, REQUEST_CALLBACK, 0) == BR_DIGIID_REFUSED && hAddress(a2, NULL, NULL, 0) == BR_DIGIID_REFUSED,
       "a locked wallet (no seed) gives neither address: no choice is offered");
    ck(hSite(site, NULL, REQUEST, REQUEST_CALLBACK, 0) == BR_DIGIID_REFUSED && hShared(shared, NULL, REQUEST, 0) == BR_DIGIID_REFUSED,
       "and signs with neither");

    BRDigiIdUtf8FromUtf16(msg, sizeof(msg), &len, emoji, N(emoji));
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, msg, len, "https://site.example/login", 26, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       strcmp(site, EMOJI_SITE_ANSWER) == 0, "an emoji message with the per-site identity: the bridge's answer");
    ck(signsBytes(site, msg, len), "over the message's UTF-8");

    ck(hSite(site, seed, REQUEST, REQUEST_CALLBACK, -1) == BR_DIGIID_REFUSED && site[0] == '\0', "index -1 is refused");
    ck(hSite(site, seed, REQUEST, REQUEST_CALLBACK, INT32_MIN) == BR_DIGIID_REFUSED, "index INT32_MIN is refused");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, (const uint8_t *)REQUEST, strlen(REQUEST), NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED,
       "no URI is refused");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, NULL, 0, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no message is refused");
    ck(BRDigiIdSignSiteWorkSize(28, strlen(REQUEST)) == 26 + 1 + strlen(REQUEST) && BRDigiIdSignSiteWorkSize(200, 1) == 204 &&
       BRDigiIdSignSiteWorkSize(SIZE_MAX, 1) == 0 && BRDigiIdSignSiteWorkSize(1, SIZE_MAX) == 0, "the work is the larger of the two hashes'");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0, WORK,
                        BRDigiIdSignSiteWorkSize(28, strlen(REQUEST))) == BR_DIGIID_OK && strcmp(site, REQUEST_SITE_ANSWER) == 0,
       "exactly that work suffices");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0, WORK,
                        BRDigiIdSignSiteWorkSize(28, strlen(REQUEST)) - 1) == BR_DIGIID_CAPACITY, "a byte less is CAPACITY");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, 31) == BR_DIGIID_CAPACITY,
       "work for the message but not the URI is CAPACITY");
}

// DigiIdRequestTest's golden key-derivation inputs, and its letter-case one, with the bridge's address for each.
static void request_golden_inputs(void)
{
    static const struct { const char *callbackUrl, *address; } V[] = {
        { "https://example.com/callback", "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68" },
        { "https://example.com:8443/callback", "D61WFJS2zyXYuKu8RD5YbCUq5uWorXKE3A" },
        { "https://example.com:443/callback", "DJU3pDBfF3ztr66tCELTaYKprGEhQueWiQ" },
        { "https://shop.example.com/digiid/callback/v1", "DTQ4YVF2dQ3AMjh5nHvB5WZKb7RAwJTkjM" },
        { "http://example.com/auth", "D5jh6XUsURT9XdrpQqEB5TycRRBdQv5Rov" },
        { "http://localhost:8080/auth", "DPGQzPAtmrpVhR5v98UtCzAtnEwgTjQ25Y" },
        { "https://api.digiscope.me/api/digiid/callback", "D8CgmEXYGQKZsaCkUGgEKCjrSZHX9Q1okp" },
        { "https://example.com/cb", "DKc9iwJy6CRFVLgcM7LtCXCkvniZjwwNyz" },
        { "https://example.com/", "D5mJfVt2vMAbvghSE9p5FGpG1f2DFZ7Xni" },
        { "https://example.com", "DATG5iNAU2akoYUbTEXTRsSbuwdUgTJFWk" },
        { "https://sub.domain-with-dash.co.uk/digiid/login.php", "DDS7QSgsfFPL4xmFaEXGR6YDXFdvCNnvNz" },
        { "http://192.168.1.10:3000/digiid", "DKQcHvHov1j3x5JXiKJue3yJwqK6E2Phfw" },
        { "https://Example.COM/cb", "DJVAcR9bDZ4aqaPnnFXzuoo2juZyZeVDBN" },
    };
    uint8_t seed[64];
    char got[N(V)][BR_DIGIID_ADDRESS_CAPACITY];
    size_t i, j;
    int distinct = 1;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(V); i++)
        ck(hAddress(got[i], seed, V[i].callbackUrl, 0) == BR_DIGIID_OK && strcmp(got[i], V[i].address) == 0, "%s: %s", V[i].callbackUrl, got[i]);
    for (i = 0; i < N(V); i++) for (j = i + 1; j < N(V); j++) if (strcmp(got[i], got[j]) == 0) distinct = 0;
    ck(distinct, "every input gives its own identity (example.com, :443, a trailing slash and Example.COM all differ)");
}

// identityAddress's rules: the shared identity only at index 0, the per-site at any index >= 0.
static void address_rules(void)
{
    uint8_t seed[64];
    char out[BR_DIGIID_ADDRESS_CAPACITY], small[BR_DIGIID_ADDRESS_CAPACITY];
    size_t len;

    seedOf(seed, ABANDON);
    ck(hAddress(out, seed, NULL, 0) == BR_DIGIID_OK && strcmp(out, SHARED_ADDRESS) == 0, "no URI, index 0: the shared identity");
    ck(hAddress(out, seed, NULL, 1) == BR_DIGIID_REFUSED && out[0] == '\0', "no URI, index 1: refused");
    ck(hAddress(out, seed, NULL, INT32_MAX) == BR_DIGIID_REFUSED, "no URI, index INT32_MAX: refused");
    ck(hAddress(out, seed, NULL, -1) == BR_DIGIID_REFUSED && hAddress(out, seed, REQUEST_CALLBACK, -1) == BR_DIGIID_REFUSED &&
       hAddress(out, seed, REQUEST_CALLBACK, INT32_MIN) == BR_DIGIID_REFUSED, "a negative index: refused, with a URI or without");
    ck(hAddress(out, seed, REQUEST_CALLBACK, 1) == BR_DIGIID_OK && strcmp(out, "DK5YekRVwRMUFWscdgrKS31RVehh4eu9jK") == 0, "index 1: the bridge's");
    ck(hAddress(out, seed, REQUEST_CALLBACK, INT32_MAX) == BR_DIGIID_OK && strcmp(out, "DEPxtbrQd6i2xZGiXsy6iK7grw2u9g5KQW") == 0,
       "index INT32_MAX: the bridge's");
    ck(hAddress(out, seed, "", 0) == BR_DIGIID_OK && strcmp(out, "DMfBUzqdMH5X2WaEKNRHcvQ1n4Gk5RPDag") == 0, "an empty URI: the bridge's");
    ck(BRDigiIdAddress(out, sizeof(out), seed, 64, 1, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "a URI asked for with none given: refused");
    len = strlen(SHARED_ADDRESS);
    ck(BRDigiIdAddress(small, len + 1, seed, 64, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_OK && strcmp(small, SHARED_ADDRESS) == 0,
       "out of exactly the address and its NUL suffices (and the shared identity needs no work)");
    ck(BRDigiIdAddress(small, len, seed, 64, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_CAPACITY && small[0] == '\0', "a byte less is CAPACITY");
    ck(BRDigiIdAddress(out, sizeof(out), seed, 64, 1, REQUEST_CALLBACK, 28, 0, WORK, 31) == BR_DIGIID_CAPACITY, "too little work is CAPACITY");
    ck(BRDigiIdAddress(NULL, 0, seed, 64, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_REFUSED, "no out is refused");
}

// BRDigiIdSignWithKey wipes the caller's key on every path, as sign_with_key did.
static void sign_with_key_wipes(void)
{
    uint8_t seed[64];
    char out[BR_DIGIID_ANSWER_CAPACITY], ref[BR_DIGIID_ANSWER_CAPACITY];
    BRKey key;

    seedOf(seed, ABANDON);
    hShared(ref, seed, LOGIN, 0);
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(out, sizeof(out), &key, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       strcmp(out, ref) == 0, "the shared key signs as BRDigiIdSignShared does");
    ck(allZero(&key, sizeof(key)), "and is wiped after signing");

    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(out, sizeof(out), &key, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "no message: refused, the key wiped");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(NULL, 0, &key, (const uint8_t *)LOGIN, 3, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "no out: refused, the key wiped");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(out, sizeof(out), &key, (const uint8_t *)LOGIN, 3, 0, WORK, 10) == BR_DIGIID_CAPACITY && allZero(&key, sizeof(key)),
       "too little work: CAPACITY, the key wiped");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(out, 10, &key, (const uint8_t *)LOGIN, 3, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && allZero(&key, sizeof(key)) &&
       out[0] == '\0', "too small an out: CAPACITY, the key wiped");
    {
        BRKey shared;
        UInt256 secret;

        BRDigiIdSharedKey(&shared, seed, 64);
        secret = shared.secret;
        BRKeyClean(&shared);
        BRKeySetSecret(&key, &secret, 0);   // the caller's key marked uncompressed
        ck(BRDigiIdSignWithKey(out, sizeof(out), &key, (const uint8_t *)LOGIN, strlen(LOGIN), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           strcmp(out, ref) == 0, "a key marked uncompressed signs as compressed: the same answer");
        mem_clean(&secret, sizeof(secret));
    }
    memset(&key, 0, sizeof(key));
    key.compressed = 1;
    dirtyStack();
    ck(BRDigiIdSignWithKey(out, sizeof(out), &key, (const uint8_t *)LOGIN, 3, 0, WORK, sizeof(WORK)) == BR_DIGIID_NO_ADDRESS && out[0] == '\0',
       "a key with no secret gives no address: NO_ADDRESS");
    {
        char b[256];
        memset(&key, 0, sizeof(key));
        ck(b_sign_with_key(b, &key, (const uint8_t *)LOGIN, 3, 0) == 0, "and the bridge refuses it too");
    }
    ck(BRDigiIdSignWithKey(out, sizeof(out), NULL, (const uint8_t *)LOGIN, 3, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "no key: refused");
}

// BRDigiIdAddressWithKey (identityAddress's last step) wipes the caller's key on every path.
static void address_with_key_wipes(void)
{
    uint8_t seed[64];
    char out[BR_DIGIID_ADDRESS_CAPACITY], shared[BR_DIGIID_ADDRESS_CAPACITY], site[BR_DIGIID_ADDRESS_CAPACITY];
    BRKey key;

    seedOf(seed, ABANDON);
    hAddress(shared, seed, NULL, 0);
    hAddress(site, seed, REQUEST_CALLBACK, 0);
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdAddressWithKey(out, sizeof(out), &key) == BR_DIGIID_OK && strcmp(out, shared) == 0, "the shared key's address is BRDigiIdAddress's");
    ck(allZero(&key, sizeof(key)), "and the key is wiped after");
    BRDigiIdSiteKey(&key, seed, 64, REQUEST_CALLBACK, strlen(REQUEST_CALLBACK), 0, WORK, sizeof(WORK));
    ck(BRDigiIdAddressWithKey(out, sizeof(out), &key) == BR_DIGIID_OK && strcmp(out, site) == 0 && allZero(&key, sizeof(key)),
       "the per-site key's likewise, the key wiped");

    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdAddressWithKey(NULL, 0, &key) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)), "no out: refused, the key wiped");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdAddressWithKey(out, strlen(shared), &key) == BR_DIGIID_CAPACITY && out[0] == '\0' && allZero(&key, sizeof(key)),
       "a byte too small an out: CAPACITY, out empty, the key wiped");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdAddressWithKey(out, strlen(shared) + 1, &key) == BR_DIGIID_OK && strcmp(out, shared) == 0 && allZero(&key, sizeof(key)),
       "exactly the address and its NUL suffices");
    {
        BRKey k2;
        UInt256 secret;

        BRDigiIdSharedKey(&k2, seed, 64);
        secret = k2.secret;
        BRKeyClean(&k2);
        BRKeySetSecret(&key, &secret, 0);   // the caller's key marked uncompressed
        ck(BRDigiIdAddressWithKey(out, sizeof(out), &key) == BR_DIGIID_OK && strcmp(out, shared) == 0 && allZero(&key, sizeof(key)),
           "a key marked uncompressed gives the compressed key's address, and is wiped");
        mem_clean(&secret, sizeof(secret));
    }
    memset(&key, 0, sizeof(key));
    dirtyStack();
    ck(BRDigiIdAddressWithKey(out, sizeof(out), &key) == BR_DIGIID_NO_ADDRESS && out[0] == '\0' && allZero(&key, sizeof(key)),
       "a key with no secret gives no address: NO_ADDRESS, the key wiped");
    ck(BRDigiIdAddressWithKey(out, sizeof(out), NULL) == BR_DIGIID_REFUSED, "no key: refused");
}

// Messages around 0xffff bytes and above: the bridge's prefix, held to the model.
static void long_messages(void)
{
    static const size_t lens[] = { 0xfffe, 0xffff, 0x10000, 0x10001, 0x100fd, 70000 };
    uint8_t seed[64], *msg = malloc(70000);
    char out[BR_DIGIID_ANSWER_CAPACITY], b[256];
    size_t i;

    seedOf(seed, ABANDON);
    for (i = 0; i < 70000; i++) msg[i] = (uint8_t)('a' + i % 26);
    for (i = 0; i < N(lens); i++) {
        int ok = BRDigiIdSignShared(out, sizeof(out), seed, 64, msg, lens[i], 0, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        ck(ok && b_signMessage(b, seed, msg, lens[i], 0) && strcmp(out, b) == 0, "a %zu-byte message, shared: the bridge's answer", lens[i]);
        ok = BRDigiIdSignSite(out, sizeof(out), seed, 64, msg, lens[i], REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        ck(ok && b_signIdentityMessage(b, seed, msg, lens[i], REQUEST_CALLBACK, 28, 0) && strcmp(out, b) == 0,
           "a %zu-byte message, per-site: the bridge's answer", lens[i]);
    }
    free(msg);
}

// The int32 edges of the account index and the address format, across every entry point.
static void edges(void)
{
    static const int32_t formats[] = { INT32_MIN, -2, -1, 0, 1, 2, 3, INT32_MAX };
    static const int32_t indexes[] = { INT32_MIN, INT32_MIN + 1, -2, -1, 0, 1, INT32_MAX - 1, INT32_MAX };
    uint8_t seed[64];
    char out[BR_DIGIID_ANSWER_CAPACITY], b[256], a[BR_DIGIID_ADDRESS_CAPACITY], ba[75];
    size_t i;
    int same = 1;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(formats); i++) {
        int h = hShared(out, seed, "m", formats[i]) == BR_DIGIID_OK, m = b_signMessage(b, seed, (const uint8_t *)"m", 1, formats[i]);
        if (h != m || (h && strcmp(out, b) != 0)) same = 0;
    }
    ck(same, "every address format at its int32 edges answers as the bridge does");
    same = 1;
    for (i = 0; i < N(indexes); i++) {
        int h = hSite(out, seed, "m", REQUEST_CALLBACK, indexes[i]) == BR_DIGIID_OK;
        int m = b_signIdentityMessage(b, seed, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, indexes[i]);
        int ha = hAddress(a, seed, REQUEST_CALLBACK, indexes[i]) == BR_DIGIID_OK, ma = b_identityAddress(ba, seed, REQUEST_CALLBACK, 28, indexes[i]);
        int hs = hAddress(a, seed, NULL, indexes[i]) == BR_DIGIID_OK, ms = b_identityAddress(ba, seed, NULL, 0, indexes[i]);
        if (h != m || (h && strcmp(out, b) != 0) || ha != ma || hs != ms) same = 0;
        ck(h == (indexes[i] >= 0) && hs == (indexes[i] == 0), "index %d: per-site %s, shared %s", (int)indexes[i], h ? "answers" : "refused",
           hs ? "answers" : "refused");
    }
    ck(same, "every account index at its int32 edges answers as the bridge does");
    ck(digiid_core_kat_tu2(), "the second translation unit agrees");
}

// The caller's buffers at their edges: an out of 0 bytes is never written, a NULL out never touched, an out of
// exactly the answer's size suffices (heap-allocated, so one byte past it is a sanitizer report), and a refusal
// leaves out empty. Keys BRDigiIdSignWithKey cannot use are refused, and wiped, as the bridge refuses them.
static void buffer_edges(void)
{
    uint8_t seed[64];
    char ref[BR_DIGIID_ANSWER_CAPACITY], refAddr[BR_DIGIID_ADDRESS_CAPACITY], one[1], *exact;
    BRKey key;
    size_t len;

    seedOf(seed, ABANDON);
    hSite(ref, seed, REQUEST, REQUEST_CALLBACK, 0);
    hAddress(refAddr, seed, REQUEST_CALLBACK, 0);

    one[0] = 'X';
    ck(BRDigiIdSignShared(one, 0, seed, 64, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && one[0] == 'X',
       "SignShared: an out of 0 bytes is CAPACITY and never written");
    ck(BRDigiIdSignSite(one, 0, seed, 64, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY &&
       one[0] == 'X', "SignSite: likewise");
    ck(BRDigiIdAddress(one, 0, seed, 64, 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && one[0] == 'X', "Address: likewise");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(one, 0, &key, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && one[0] == 'X' &&
       allZero(&key, sizeof(key)), "SignWithKey: likewise, the key wiped");
    ck(BRDigiIdSignShared(one, 0, NULL, 64, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && one[0] == 'X',
       "a refusal with an out of 0 bytes writes nothing");

    ck(BRDigiIdSignShared(NULL, 10, seed, 64, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       BRDigiIdSignSite(NULL, 10, seed, 64, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       BRDigiIdAddress(NULL, 10, seed, 64, 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED, "a NULL out with a capacity is refused, untouched");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(NULL, 10, &key, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "and by SignWithKey, the key wiped");

    len = strlen(ref);
    exact = malloc(len + 1);
    ck(hSite(exact, seed, REQUEST, REQUEST_CALLBACK, 0) == BR_DIGIID_OK, "warm-up");
    ck(BRDigiIdSignSite(exact, len + 1, seed, 64, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) ==
       BR_DIGIID_OK && strcmp(exact, ref) == 0, "an answer into a heap out of exactly its size");
    free(exact);
    len = strlen(refAddr);
    exact = malloc(len + 1);
    ck(BRDigiIdAddress(exact, len + 1, seed, 64, 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK && strcmp(exact, refAddr) == 0,
       "an address into a heap out of exactly its size");
    free(exact);

    {
        char out[BR_DIGIID_ANSWER_CAPACITY], addr[BR_DIGIID_ADDRESS_CAPACITY];
        memset(out, 'X', sizeof(out));
        ck(hShared(out, NULL, "m", 0) == BR_DIGIID_REFUSED && out[0] == '\0', "SignShared refused: out empty");
        memset(out, 'X', sizeof(out));
        ck(hSite(out, NULL, "m", REQUEST_CALLBACK, 0) == BR_DIGIID_REFUSED && out[0] == '\0', "SignSite refused: out empty");
        memset(addr, 'X', sizeof(addr));
        ck(hAddress(addr, NULL, REQUEST_CALLBACK, 0) == BR_DIGIID_REFUSED && addr[0] == '\0', "Address refused: out empty");
    }

    {
        uint8_t pub[33];
        char out[BR_DIGIID_ANSWER_CAPACITY], b[256];
        BRKey pubOnly, bridge;

        BRDigiIdSharedKey(&key, seed, 64);
        key.compressed = 1;
        BRKeyPubKey(&key, pub, sizeof(pub));
        BRKeyClean(&key);
        BRKeySetPubKey(&pubOnly, pub, sizeof(pub));
        bridge = pubOnly;
        memset(out, 'X', sizeof(out));
        ck(BRDigiIdSignWithKey(out, sizeof(out), &pubOnly, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_NOT_SIGNED &&
           out[0] == '\0' && allZero(&pubOnly, sizeof(pubOnly)), "a public key alone has an address but signs nothing: NOT_SIGNED, wiped");
        ck(b_sign_with_key(b, &bridge, (const uint8_t *)"m", 1, 0) == 0, "and the bridge refuses it too");

        memset(&key, 0, sizeof(key));
        memset(&key.secret, 0xff, sizeof(key.secret));   // above the curve order: no public key
        key.compressed = 1;
        ck(BRDigiIdSignWithKey(out, sizeof(out), &key, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_NO_ADDRESS &&
           allZero(&key, sizeof(key)), "a secret above the curve order gives no address: NO_ADDRESS, the key wiped");
        memset(&bridge, 0, sizeof(bridge));
        memset(&bridge.secret, 0xff, sizeof(bridge.secret));
        ck(b_sign_with_key(b, &bridge, (const uint8_t *)"m", 1, 0) == 0, "and the bridge refuses it too");
    }
}

// ---- a fixed pseudo-random stream ----------------------------------------------------------------

static uint64_t RNG = 0x9e3779b97f4a7c15ULL;

static uint64_t rnd(void)   // xorshift64
{
    RNG ^= RNG << 13;
    RNG ^= RNG >> 7;
    RNG ^= RNG << 17;
    return RNG;
}

static uint64_t below(uint64_t n) { return rnd() % n; }

static size_t rndBytes(uint8_t *out, size_t cap)
{
    size_t n = (size_t)below(cap + 1), i;
    for (i = 0; i < n; i++) out[i] = below(3) ? (uint8_t)(0x20 + below(0x5f)) : (uint8_t)rnd();
    return n;
}

static uint32_t rndIndex32(void)
{
    switch (below(6)) {
        case 0: return (uint32_t)rnd();
        case 1: return 0xffffffffu - (uint32_t)below(4);
        case 2: return 0x7fffffffu + (uint32_t)below(3);
        default: return (uint32_t)below(1000);
    }
}

static void sweep_indexes(void)
{
    uint8_t uri[400];
    uint32_t h[4], b[4];
    long differ = 0, n;

    for (n = 0; n < 20000; n++) {
        size_t len = rndBytes(uri, sizeof(uri));
        uint32_t index = rndIndex32();

        BRDigiIdSlip13Indexes(h, (const char *)uri, len, index, WORK, sizeof(WORK));
        slip13_indexes(b, (const char *)uri, len, index);
        if (memcmp(h, b, sizeof(h)) != 0 && differ++ < 4) ck(0, "a %zu-byte URI at index %u: A..D differ from the bridge's", len, index);
    }
    ck(differ == 0, "20000 URIs and indexes: A..D are the bridge's slip13.c's (%ld differ)", differ);
}

static void sweep_keys(void)
{
    uint8_t seed[80], uri[200];
    long differ = 0, ownDiffer = 0, n, refused = 0;

    for (n = 0; n < 1500; n++) {
        size_t seedLen = below(4) ? 64 : (size_t)(16 + below(65)), uriLen, i;
        int32_t index = below(5) == 0 ? -(int32_t)below(3) - 1 : (int32_t)(rndIndex32() & 0x7fffffff);
        BRKey hk, bk;
        int h, b;

        for (i = 0; i < seedLen; i++) seed[i] = (uint8_t)rnd();
        uriLen = rndBytes(uri, sizeof(uri));
        h = BRDigiIdSiteKey(&hk, seed, seedLen, (const char *)uri, uriLen, index, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        memset(&bk, 0, sizeof(bk));
        b = index >= 0 && b_seed_derive_identity_key(&bk, seed, seedLen, (const char *)uri, uriLen, (uint32_t)index);
        if (h != b || (h && memcmp(&hk, &bk, sizeof(hk)) != 0)) {
            if (differ++ < 4) ck(0, "seed of %zu bytes, %zu-byte URI, index %d: the key is not the bridge's", seedLen, uriLen, (int)index);
        }
        if (! h) refused++;
        if (h && n % 10 == 0) {
            uint32_t idx[4], path[5];
            slip13_indexes(idx, (const char *)uri, uriLen, (uint32_t)index);
            path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
            if (! sameSecret(&hk, ownSecret(seed, seedLen, path, 5))) ownDiffer++;
        }
        if (n % 10 == 0) {
            static const uint32_t sharedPath[3] = { 0 | HARD, 0, 0 };
            BRDigiIdSharedKey(&hk, seed, seedLen);
            if (! sameSecret(&hk, ownSecret(seed, seedLen, sharedPath, 3))) ownDiffer++;
        }
        BRKeyClean(&hk); BRKeyClean(&bk);
    }
    ck(differ == 0, "1500 seeds, URIs and indexes: every per-site key (and refusal) is the bridge's (%ld differ)", differ);
    ck(ownDiffer == 0, "every tenth per-site and shared key is this file's BIP32's (%ld differ)", ownDiffer);
    ck(refused > 150 && refused < 1500 - 150, "the stream reaches keys and refusals (%ld refused)", refused);
}

static void sweep_sign(void)
{
    static const int32_t formats[] = { 0, 1, 2, 2, -1, INT32_MAX };
    uint8_t seed[64], msg[600], uri[160];
    char hs[BR_DIGIID_ANSWER_CAPACITY], bs[256], ha[BR_DIGIID_ADDRESS_CAPACITY], ba[75];
    long differ = 0, n, answers = 0;
    size_t i;

    for (n = 0; n < 400; n++) {
        size_t msgLen, uriLen;
        int32_t format = formats[below(N(formats))], index = below(6) == 0 ? -1 : (int32_t)below(50);
        const uint8_t *seedArg = below(15) == 0 ? NULL : seed;
        int hasUri = below(3) != 0, h, b;

        for (i = 0; i < 64; i++) seed[i] = (uint8_t)rnd();
        msgLen = rndBytes(msg, sizeof(msg));
        uriLen = rndBytes(uri, sizeof(uri));

        h = BRDigiIdSignShared(hs, sizeof(hs), seedArg, 64, msg, msgLen, format, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        b = b_signMessage(bs, seedArg, msg, msgLen, format);
        if (h != b || (h && strcmp(hs, bs) != 0)) { if (differ++ < 4) ck(0, "step %ld: the shared answer is not the bridge's", n); }
        answers += h;
        h = BRDigiIdSignSite(hs, sizeof(hs), seedArg, 64, msg, msgLen, (const char *)uri, uriLen, index, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        b = b_signIdentityMessage(bs, seedArg, msg, msgLen, (const char *)uri, uriLen, index);
        if (h != b || (h && strcmp(hs, bs) != 0)) { if (differ++ < 4) ck(0, "step %ld: the per-site answer is not the bridge's", n); }
        answers += h;
        h = BRDigiIdAddress(ha, sizeof(ha), seedArg, 64, hasUri, (const char *)uri, uriLen, index, WORK, sizeof(WORK)) == BR_DIGIID_OK;
        b = b_identityAddress(ba, seedArg, hasUri ? (const char *)uri : NULL, uriLen, index);
        if (h != b || (h && strcmp(ha, ba) != 0)) { if (differ++ < 4) ck(0, "step %ld: the address is not the bridge's", n); }
        answers += h;
    }
    ck(differ == 0, "400 seeds, messages, URIs, indexes and formats: every answer and refusal is the bridge's (%ld differ)", differ);
    ck(answers > 600 && answers < 1200 - 100, "the stream reaches answers and refusals (%ld answered of 1200)", answers);
}

static void sweep_utf8(void)
{
    uint16_t u[300];
    uint8_t out[900];
    long differ = 0, n, pairs = 0, lone = 0;

    for (n = 0; n < 20000; n++) {
        size_t count = (size_t)below(N(u) + 1), i, len, blen;
        uint8_t *b;

        for (i = 0; i < count; i++) {
            switch (below(8)) {
                case 0: u[i] = 0; break;
                case 1: u[i] = (uint16_t)(0xD800 + below(0x400)); break;
                case 2: u[i] = (uint16_t)(0xDC00 + below(0x400)); break;
                case 3: u[i] = (uint16_t)(0x80 + below(0x780)); break;
                case 4: u[i] = (uint16_t)rnd(); break;
                case 5: if (i + 1 < count) { u[i++] = (uint16_t)(0xD800 + below(0x400)); u[i] = (uint16_t)(0xDC00 + below(0x400)); pairs++; break; }
                /* fall through */
                default: u[i] = (uint16_t)below(0x80); break;
            }
        }
        if (BRDigiIdUtf8FromUtf16(out, sizeof(out), &len, u, count) != BR_DIGIID_OK) { differ++; continue; }
        b = b_message_utf8(u, count, &blen);
        if (blen != len || memcmp(b, out, len) != 0) { if (differ++ < 4) ck(0, "%zu units: the UTF-8 is not the bridge's", count); }
        for (i = 0; i < len; i++) if (out[i] == '?') lone++;
        free(b);
    }
    ck(differ == 0, "20000 strings of UTF-16 units: the bytes are the bridge's message_utf8's (%ld differ)", differ);
    ck(pairs > 10000 && lone > 10000, "the stream holds surrogate pairs (%ld) and lone surrogates (%ld)", pairs, lone);
}

// BRDigiIdUriBytesFromUtf16 against ART's own encoder over arbitrary UTF-16.
static void sweep_uri_bytes(void)
{
    uint16_t u[300];
    uint8_t out[900], art[900];
    long differ = 0, n, pairs = 0, nuls = 0;

    for (n = 0; n < 20000; n++) {
        size_t count = (size_t)below(N(u) + 1), i, len, alen;

        for (i = 0; i < count; i++) {
            switch (below(8)) {
                case 0: u[i] = 0; nuls++; break;
                case 1: u[i] = (uint16_t)(0xD800 + below(0x400)); break;
                case 2: u[i] = (uint16_t)(0xDC00 + below(0x400)); break;
                case 3: u[i] = (uint16_t)(0x80 + below(0x780)); break;
                case 4: u[i] = (uint16_t)rnd(); break;
                case 5: if (i + 1 < count) { u[i++] = (uint16_t)(0xD800 + below(0x400)); u[i] = (uint16_t)(0xDC00 + below(0x400)); pairs++; break; }
                /* fall through */
                default: u[i] = (uint16_t)below(0x80); break;
            }
        }
        if (BRDigiIdUriBytesFromUtf16(out, sizeof(out), &len, u, count) != BR_DIGIID_OK) { differ++; continue; }
        alen = art8_utf(art, u, count);
        if (alen != len || memcmp(art, out, len) != 0 || memchr(out, 0, len) != NULL) {
            if (differ++ < 4) ck(0, "%zu units: the bytes are not ART's", count);
        }
    }
    ck(differ == 0, "20000 strings of UTF-16 units: the URI bytes are ART's GetStringUTFChars's (%ld differ)", differ);
    ck(pairs > 10000 && nuls > 10000, "the stream holds surrogate pairs (%ld) and U+0000 (%ld)", pairs, nuls);
}

// ---- main ----------------------------------------------------------------------------------------

typedef struct { const char *name; void (*run)(void); } Case;

static const Case CASES[] = {
    { "slip13_published_vectors", slip13_published_vectors }, { "slip13_identity_carried", slip13_identity_carried },
    { "slip13_index_values", slip13_index_values }, { "slip13_edges", slip13_edges },
    { "site_key_derivation", site_key_derivation }, { "shared_key_derivation", shared_key_derivation },
    { "message_prefix", message_prefix }, { "message_digest", message_digest }, { "utf8_bytes", utf8_bytes },
    { "uri_bytes", uri_bytes }, { "uri_identity", uri_identity },
    { "sign_shared_login", sign_shared_login }, { "sign_site_request", sign_site_request },
    { "request_golden_inputs", request_golden_inputs }, { "address_rules", address_rules },
    { "sign_with_key_wipes", sign_with_key_wipes }, { "address_with_key_wipes", address_with_key_wipes }, { "long_messages", long_messages }, { "edges", edges }, { "buffer_edges", buffer_edges },
    { "sweep_indexes", sweep_indexes }, { "sweep_keys", sweep_keys }, { "sweep_sign", sweep_sign }, { "sweep_utf8", sweep_utf8 },
    { "sweep_uri_bytes", sweep_uri_bytes },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    for (i = 0; i < N(CASES); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        CASES[i].run();
        printf("RESULT %s %s\n", CASES[i].name, FAILED ? "fail" : "pass");
        return FAILED ? 1 : 0;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
