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
//       the bridge derives for it); DigiIdIdentityChoiceTest's request, whose two identities must
//       differ and whose address is the address the login signs with (sign_site_request); and the three
//       callback URLs Coinomi was measured signing in to, under the root BR_DIGIID_ROOT_DIGIBYTE, the
//       bridge's per-site master key "DigiByte seed" (site_key_digibyte_coinomi_uris: each address, and
//       DigiScope's login signed with the identity of its own callback). Addresses and signatures are
//       literals read off the REAL bridge by `digiid_core_bridge print` (run.sh builds it; its sweep holds
//       the header to the bridge end to end). Keys are also derived here by a separate BIP32 written in
//       this file (ckd below) from either master key, not by the core's BRBIP32PrivKeyPath or
//       BRBIP32PrivKeyArrayPath.
//       The root BR_DIGIID_ROOT_STANDARD ("Bitcoin seed") has nothing in the bridge to read off: the bridge
//       takes no root until step 2 of the design. Its literals are the addresses Coinomi itself showed for
//       the same three URLs, which an independent Python BIP32/SLIP-0013 model gives again
//       (site_key_standard_coinomi_uris), and the core's published BitID address
//       1J34vj4wowwPYafbeibZGht3zy3qERoUM1 (site_key_standard_bitid_vector); a signature under it is held to
//       its address by the signer it recovers to. Where nothing was measured (an index but 0, and a login's
//       whole answer) the literals are that model's alone, with RFC 6979 as libsecp256k1 signs, which gives
//       the bridge's DIGIBYTE login answer byte for byte as well (site_key_standard_login_and_indexes). An
//       unknown root is refused (unknown_root_refused), and so is a hasUri but 0 or 1, which a root swapped
//       with it would be (address_root_hasuri_swapped).
//    2. BY THE BRIDGE'S OWN slip13.c, compiled into this binary: the path indexes are held to it.
//    3. BY A MODEL. `b_...` below is the bridge's C written out line for line without JNI (a jstring
//       becomes its bytes or its UTF-16 units, NULL a Java null). The sweep_* cases hold the header to
//       it over fixed pseudo-random inputs; sweep_keys_standard, a root the bridge has no answer for, holds
//       every key to this file's BIP32 instead. The URI's bytes are what ART's GetStringUTFChars writes:
//       art8_utf below is ART's own encoder (Android 8.0 to 12) line for line, and
//       digiid_core_bridge_main.c's host env writes them with the newer ART's.
//
//  WHICH IDENTITY SIGNS (THE DIGISCOPE LIST and THE CHOICE in BRDigiId.h: BRDigiIdIsDigiScopeHost and
//  BRDigiIdChooseIdentity) has no bridge to be held to: it was never C, each app deciding in its own code
//  (Android's IdentityKeyPolicy.kt, a Swift list on iOS). It is held BY HAND. The list cases are iOS's
//  DigiIdIdentityTests, value for value (Android's IdentityKeyPolicyTest holds the same ones), every host
//  crossed with both digiScopeIdentity values and both roots; then every refusal; then END TO END, the choice
//  fed to BRDigiIdAddress and the signing calls, where a wallet restored as from Coinomi signs DigiScope with
//  DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri, the address Coinomi signed DigiScope's login with (measured: the
//  design's table). The two hosts and the login URL are written in this file, never read from the header.
//  `hosts` prints the two, and run.sh's SOURCE GATE holds the header's own list and Android's
//  IdentityKeyPolicy.kt to them, for as long as Android keeps a list of its own. Not carried from iOS's file:
//  testTheRecordedDerivationNamesAreAndroids, the words a login's history row records, which is each app's.
//
//  One case per process: `digiid_core_kat <case>` prints PASS:/FAIL: lines and `RESULT <case> pass|fail`;
//  `list` prints the case names. run.sh builds one binary per DIGIID_UNFIXED arm and holds each
//  comparison arm to failing exactly its cases.
//

#include "BRDigiId.h"
#include "BRBIP39Mnemonic.h"
#include "BRBase58.h"   // BRBase58CheckDecode: the published BitID address's hash160
#include "BRDigiScopePins.h"   // BR_DIGISCOPE_HOST: the server both apps pin, which the login URL names
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
// LOGIN signed with the per-site identity of DigiScope's login callback (https://api.digiscope.me/api/auth/digiid/callback)
// at index 0 under BR_DIGIID_ROOT_STANDARD: an independent Python model's answer, the bridge having none for that root
// (site_key_standard_login_and_indexes). Its address is the one Coinomi showed for that callback.
static const char *LOGIN_ANSWER_STANDARD_SITE =
    "DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri|IMx5g483pmrhXxBN7sywXP4364mTuX1jM7F2QAMJZ5CzKifnD1bbptCQGvlXMB78Xo6URQkWkPV1IIbSh/o+lfI=";

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

// The secret at path[0, depth) from seed[0, seedLen), the master key being HMAC-SHA512(mk, seed): "DigiByte seed"
// (the bread-wallet tree: the shared identity's, and BR_DIGIID_ROOT_DIGIBYTE's) or "Bitcoin seed" (BIP32's own:
// BR_DIGIID_ROOT_STANDARD's). Each caller writes the string itself; none is taken from the core.
static UInt256 ownSecret(const uint8_t *seed, size_t seedLen, const char *mk, const uint32_t *path, int depth)
{
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
    if (val <= 0xffffffffULL) {   /* signed_message.h, MSG-LEN */
        buf[0] = 0xfe;
        for (int i = 0; i < 4; i++) buf[1 + i] = (uint8_t)((val >> (8 * i)) & 0xff);
        return 5;
    }
    buf[0] = 0xff;
    for (int i = 0; i < 8; i++) buf[1 + i] = (uint8_t)((val >> (8 * i)) & 0xff);
    return 9;
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

// hSite and hAddress sign and address under BR_DIGIID_ROOT_DIGIBYTE, the bridge's per-site master key, as does
// every per-site call below that names no other root: the cases held to the bridge are held under that root.
static BRDigiIdStatus hSite(char *out, const uint8_t *seed, const char *msg, const char *uri, int32_t index)
{
    return BRDigiIdSignSite(out, BR_DIGIID_ANSWER_CAPACITY, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)msg, strlen(msg), uri,
                            strlen(uri), index, WORK, sizeof(WORK));
}

static BRDigiIdStatus hShared(char *out, const uint8_t *seed, const char *msg, int32_t format)
{
    return BRDigiIdSignShared(out, BR_DIGIID_ANSWER_CAPACITY, seed, 64, (const uint8_t *)msg, strlen(msg), format, WORK, sizeof(WORK));
}

static BRDigiIdStatus hAddress(char *out, const uint8_t *seed, const char *uri, int32_t index)
{
    return BRDigiIdAddress(out, BR_DIGIID_ADDRESS_CAPACITY, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, uri != NULL, uri, uri ? strlen(uri) : 0,
                           index, WORK, sizeof(WORK));
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
    ck(BRDigiIdSiteKey(&keyA, seed, sizeof(seed), BR_DIGIID_ROOT_DIGIBYTE, vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       BRDigiIdSiteKey(&keyA2, seed, sizeof(seed), BR_DIGIID_ROOT_DIGIBYTE, vectorUri, strlen(vectorUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       BRDigiIdSiteKey(&keyB, seed, sizeof(seed), BR_DIGIID_ROOT_DIGIBYTE, otherUri, strlen(otherUri), 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
       "the path keys derive");
    {
        uint32_t path[5] = { 13 | HARD, idx[0] | HARD, idx[1] | HARD, idx[2] | HARD, idx[3] | HARD };
        ck(sameSecret(&keyA, ownSecret(seed, 64, "DigiByte seed", path, 5)), "the key is m/13'/A'/B'/C'/D' (this file's BIP32)");
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
        ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, indexes[i], WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           sameSecret(&key, ownSecret(seed, 64, "DigiByte seed", path, 5)),
           "index %d: the key is m/13'/A'/B'/C'/D' of the bridge's A..D", (int)indexes[i]);
        BRBIP32BitIDKey(&bitid, seed, 64, (uint32_t)indexes[i], REQUEST_CALLBACK);
        ck(memcmp(&key.secret, &bitid.secret, 32) == 0, "index %d: the key BRBIP32BitIDKey derives", (int)indexes[i]);
        BRKeyClean(&bitid);
    }

    seedOf(inhale, INHALE);
    ck(BRDigiIdSiteKey(&key, inhale, 64, BR_DIGIID_ROOT_DIGIBYTE, "http://bitid.bitcoin.blue/callback", 34, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
       "the BitID phrase derives");
    key.compressed = 1;
    BRKeyAddress(&key, addr, sizeof(addr));
    ck(strcmp(addr, "DSFBMsXMiph22GAvPaqSKqnfm6n2ELUPMi") == 0, "the BitID phrase and callback: the bridge's address (%s)", addr);

    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, -1, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       allZero(&key, sizeof(key)), "index -1 is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, INT32_MIN, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       allZero(&key, sizeof(key)), "index INT32_MIN is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, NULL, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       allZero(&key, sizeof(key)), "no seed (a locked session) is refused, the key wiped");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "no URI is refused");
    memset(&key, 0xa5, sizeof(key));
    ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, 0, WORK, 4 + n - 1) == BR_DIGIID_CAPACITY &&
       allZero(&key, sizeof(key)), "too little work is CAPACITY, the key wiped");
    ck(BRDigiIdSiteKey(NULL, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED,
       "no key is refused");
}

// Every header call the Coinomi rows make, in one place: uri's per-site key at index 0 under `root` into *key, its
// address into addr and, when msg is given, msg signed with it into answer (empty otherwise). Nonzero when every
// call answered BR_DIGIID_OK.
static int hRootSite(BRDigiIdRoot root, BRKey *key, char addr[BR_DIGIID_ADDRESS_CAPACITY], char answer[BR_DIGIID_ANSWER_CAPACITY],
                     const uint8_t *seed, const char *uri, const char *msg)
{
    size_t n = strlen(uri);
    int ok = BRDigiIdSiteKey(key, seed, 64, root, uri, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK;

    ok = BRDigiIdAddress(addr, BR_DIGIID_ADDRESS_CAPACITY, seed, 64, root, 1, uri, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK && ok;
    answer[0] = '\0';
    if (msg)
        ok = BRDigiIdSignSite(answer, BR_DIGIID_ANSWER_CAPACITY, seed, 64, root, (const uint8_t *)msg, strlen(msg), uri, n, 0, WORK,
                              sizeof(WORK)) == BR_DIGIID_OK && ok;
    return ok;
}

// Every header call site_key_digibyte_coinomi_uris makes: hRootSite under BR_DIGIID_ROOT_DIGIBYTE, the per-site master
// key HMAC-SHA512("DigiByte seed", seed) (BRBIP32PrivKeyPath's) that the case pinned before an argument named it.
// Naming the root here left the case's literals and checks as they were.
static int hDigiByteSite(BRKey *key, char addr[BR_DIGIID_ADDRESS_CAPACITY], char answer[BR_DIGIID_ANSWER_CAPACITY],
                         const uint8_t *seed, const char *uri, const char *msg)
{
    return hRootSite(BR_DIGIID_ROOT_DIGIBYTE, key, addr, answer, seed, uri, msg);
}

// The per-site identity of the three callback URLs Coinomi was measured signing in to
// (docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md) under BR_DIGIID_ROOT_DIGIBYTE: master key "DigiByte
// seed", which every per-site identity had before the root was an argument, so none of them is the address Coinomi
// showed (site_key_standard_coinomi_uris holds those). Pinned before the master key became an argument
// (characterize shared crypto, then change it). The addresses and the login's answer are the bridge's
// (digiid_core_bridge print); each key is also this file's BIP32 from "DigiByte seed" and the bridge's
// seed_derive_identity_key, byte for byte.
static void site_key_digibyte_coinomi_uris(void)
{
    static const struct { const char *uri, *address, *loginAnswer; } V[] = {
        { "https://api.digiscope.me/api/digiid-probe/callback", "DHawr8VSh4T2iEM9NxJdubbDTra31RKU94", NULL },
        // DigiScope's login callback, LOGIN's own: LOGIN signed with its identity.
        { "https://api.digiscope.me/api/auth/digiid/callback", "DKBDZRPxPz6qGseZKNNzSwW3zfaRr1FQNA",
          "DKBDZRPxPz6qGseZKNNzSwW3zfaRr1FQNA|H6Y5Lov+rhzj1oz38NlPjC1XM80aNswPBThCuh4bS/SSFi9VH4xMrN317Cx/mtcsLAwNZt50r431nlfMGb741y4=" },
        { "https://digiscope.me/api/digiid-probe/callback", "DS4cZJP33QZ2mc1bTy9RgHZ3nDznEzS5eh", NULL },
    };
    uint8_t seed[64];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY], keyAddr[75];
    BRKey key, bridge;
    size_t i;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(V); i++) {
        size_t n = strlen(V[i].uri);
        uint32_t idx[4], path[5];

        ck(hDigiByteSite(&key, addr, answer, seed, V[i].uri, V[i].loginAnswer ? LOGIN : NULL),
           "%s at index 0: BRDigiIdSiteKey and BRDigiIdAddress answer%s", V[i].uri, V[i].loginAnswer ? ", BRDigiIdSignSite signs LOGIN" : "");
        ck(strcmp(addr, V[i].address) == 0, "%s: the bridge's address (%s)", V[i].uri, addr);
        slip13_indexes(idx, V[i].uri, n, 0);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        ck(sameSecret(&key, ownSecret(seed, 64, "DigiByte seed", path, 5)),
           "%s: the key is m/13'/A'/B'/C'/D' from \"DigiByte seed\" (this file's BIP32)", V[i].uri);
        memset(&bridge, 0, sizeof(bridge));
        ck(b_seed_derive_identity_key(&bridge, seed, 64, V[i].uri, n, 0) && memcmp(&key, &bridge, sizeof(key)) == 0,
           "%s: and the bridge's seed_derive_identity_key's, byte for byte", V[i].uri);
        memset(keyAddr, 0, sizeof(keyAddr));
        BRKeyAddress(&key, keyAddr, sizeof(keyAddr));
        ck(strcmp(keyAddr, V[i].address) == 0, "%s: whose P2PKH address is that address", V[i].uri);
        if (V[i].loginAnswer) {
            ck(strcmp(answer, V[i].loginAnswer) == 0, "%s: LOGIN signed with it, the bridge's answer", V[i].uri);
            ck(signsBytes(answer, (const uint8_t *)LOGIN, strlen(LOGIN)) && strncmp(answer, addr, strlen(addr)) == 0 &&
               answer[strlen(addr)] == '|', "over LOGIN's bytes, by that address");
        }
        BRKeyClean(&key);
        BRKeyClean(&bridge);
    }
}

// The same three callback URLs under BR_DIGIID_ROOT_STANDARD, master key "Bitcoin seed": the addresses Coinomi showed
// for them, read off Coinomi itself (the design's table) and given again by an independent Python BIP32/SLIP-0013
// model. The bridge takes no root, so it has none of these to read off until step 2 of the design: each key is held
// to this file's BIP32 from "Bitcoin seed" instead, each identity to differing from DIGIBYTE's for its URL, and
// DigiScope's login, signed with the identity of its own callback, to that address by the signer it recovers to.
static void site_key_standard_coinomi_uris(void)
{
    static const struct { const char *uri, *address; int signsLogin; } V[] = {
        { "https://api.digiscope.me/api/digiid-probe/callback", "DSSfzAosbs1mBJE4rZPtZHGuwjHorJDNau", 0 },
        // DigiScope's login callback, LOGIN's own: LOGIN signed with its identity.
        { "https://api.digiscope.me/api/auth/digiid/callback", "DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri", 1 },
        { "https://digiscope.me/api/digiid-probe/callback", "DA3TaNPPTHwStZp3K98sGzk7aaQ8sh7sBo", 0 },
    };
    uint8_t seed[64];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY], keyAddr[75], digibyte[BR_DIGIID_ADDRESS_CAPACITY];
    BRKey key;
    size_t i;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(V); i++) {
        size_t n = strlen(V[i].uri), alen = strlen(V[i].address);
        uint32_t idx[4], path[5];

        ck(hRootSite(BR_DIGIID_ROOT_STANDARD, &key, addr, answer, seed, V[i].uri, V[i].signsLogin ? LOGIN : NULL),
           "%s at index 0, STANDARD: BRDigiIdSiteKey and BRDigiIdAddress answer%s", V[i].uri,
           V[i].signsLogin ? ", BRDigiIdSignSite signs LOGIN" : "");
        ck(strcmp(addr, V[i].address) == 0, "%s: the address Coinomi showed (%s)", V[i].uri, addr);
        slip13_indexes(idx, V[i].uri, n, 0);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        ck(sameSecret(&key, ownSecret(seed, 64, "Bitcoin seed", path, 5)),
           "%s: the key is m/13'/A'/B'/C'/D' from \"Bitcoin seed\" (this file's BIP32)", V[i].uri);
        memset(keyAddr, 0, sizeof(keyAddr));
        BRKeyAddress(&key, keyAddr, sizeof(keyAddr));
        ck(strcmp(keyAddr, V[i].address) == 0, "%s: whose P2PKH address is that address", V[i].uri);
        ck(BRDigiIdAddress(digibyte, sizeof(digibyte), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, V[i].uri, n, 0, WORK, sizeof(WORK)) ==
           BR_DIGIID_OK && strcmp(digibyte, addr) != 0, "%s: another identity than DIGIBYTE gives it (%s)", V[i].uri, digibyte);
        if (V[i].signsLogin) {
            ck(strncmp(answer, V[i].address, alen) == 0 && answer[alen] == '|', "%s: LOGIN signed with it answers that address", V[i].uri);
            ck(signsBytes(answer, (const uint8_t *)LOGIN, strlen(LOGIN)), "over LOGIN's bytes, by that address's key (the signer recovered)");
        }
        BRKeyClean(&key);
    }
}

// The core's published BitID vector (test.c, BRBIP32BitIDKey's test): for its phrase and callback at index 0, the key
// of the Bitcoin address 1J34vj4wowwPYafbeibZGht3zy3qERoUM1, derived from BIP32's "Bitcoin seed". Under
// BR_DIGIID_ROOT_STANDARD the per-site identity is that key: the same hash160, here as a DigiByte address.
// (BRBIP32BitIDKey itself derives from "DigiByte seed" in this core; site_key_derivation holds it to DIGIBYTE.)
static void site_key_standard_bitid_vector(void)
{
    static const char *bitid = "http://bitid.bitcoin.blue/callback";
    uint8_t seed[64], published[21] = { 0 }, decoded[21] = { 0 };
    char addr[BR_DIGIID_ADDRESS_CAPACITY];
    uint32_t idx[4], path[5];
    size_t n = strlen(bitid);
    UInt160 h;
    BRKey key;

    seedOf(seed, INHALE);
    ck(BRBase58CheckDecode(published, sizeof(published), "1J34vj4wowwPYafbeibZGht3zy3qERoUM1") == 21 && published[0] == 0,
       "the published address is version 0 and a hash160");
    ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_STANDARD, bitid, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
       "the BitID phrase and callback derive, STANDARD");
    h = BRKeyHash160(&key);
    ck(memcmp(h.u8, published + 1, 20) == 0, "the key's hash160 is the published address's");
    slip13_indexes(idx, bitid, n, 0);
    path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
    ck(sameSecret(&key, ownSecret(seed, 64, "Bitcoin seed", path, 5)), "the key is m/13'/A'/B'/C'/D' from \"Bitcoin seed\" (this file's BIP32)");
    BRKeyClean(&key);
    ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, BR_DIGIID_ROOT_STANDARD, 1, bitid, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       BRBase58CheckDecode(decoded, sizeof(decoded), addr) == 21 && memcmp(decoded + 1, published + 1, 20) == 0,
       "BRDigiIdAddress answers that hash160 as a DigiByte address (%s)", addr);
}

// BR_DIGIID_ROOT_STANDARD where nothing was measured: DigiScope's login callback at indexes 1, 2 and INT32_MAX
// (Coinomi signs at index 0 alone, and at 0 the index's little- and big-endian bytes are the same), and LOGIN's whole
// answer, signed with that callback's identity at index 0. The literals are an independent Python model's (BIP39;
// BIP32 from "Bitcoin seed"; SLIP-0013; RFC 6979 as libsecp256k1 signs), which gives the bridge's DIGIBYTE answer
// for the same login (site_key_digibyte_coinomi_uris) byte for byte as well. A negative index is refused under
// STANDARD as under DIGIBYTE: the key wiped, out empty.
static void site_key_standard_login_and_indexes(void)
{
    static const char *uri = "https://api.digiscope.me/api/auth/digiid/callback";
    static const struct { int32_t index; const char *address; } V[] = {
        { 1, "D6jnpnRo8u9x3FsgSMcUPwRGphbANMqz4g" },
        { 2, "DT1N5cjfpXxrTQztFQSQGCEiNdfhYTbJSc" },
        { INT32_MAX, "D7D31HYwVkgv2wGjx3vxczkx6kXpa6zg6r" },
    };
    static const int32_t negative[] = { -1, INT32_MIN };
    const char *loginAnswer = LOGIN_ANSWER_STANDARD_SITE;
    uint8_t seed[64];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY], keyAddr[75];
    size_t i, n = strlen(uri);
    BRKey key;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(V); i++) {
        uint32_t idx[4], path[5];

        ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, BR_DIGIID_ROOT_STANDARD, 1, uri, n, V[i].index, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           strcmp(addr, V[i].address) == 0, "index %d, STANDARD: the model's address (%s)", (int)V[i].index, addr);
        ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_STANDARD, uri, n, V[i].index, WORK, sizeof(WORK)) == BR_DIGIID_OK,
           "index %d, STANDARD: BRDigiIdSiteKey derives", (int)V[i].index);
        slip13_indexes(idx, uri, n, (uint32_t)V[i].index);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        ck(sameSecret(&key, ownSecret(seed, 64, "Bitcoin seed", path, 5)),
           "index %d: the key is m/13'/A'/B'/C'/D' of the bridge's A..D, from \"Bitcoin seed\" (this file's BIP32)", (int)V[i].index);
        memset(keyAddr, 0, sizeof(keyAddr));
        BRKeyAddress(&key, keyAddr, sizeof(keyAddr));
        ck(strcmp(keyAddr, V[i].address) == 0, "index %d: whose P2PKH address is that address", (int)V[i].index);
        BRKeyClean(&key);
    }
    ck(BRDigiIdSignSite(answer, sizeof(answer), seed, 64, BR_DIGIID_ROOT_STANDARD, (const uint8_t *)LOGIN, strlen(LOGIN), uri, n, 0, WORK,
                        sizeof(WORK)) == BR_DIGIID_OK && strcmp(answer, loginAnswer) == 0,
       "LOGIN signed with the callback's STANDARD identity: the model's answer");
    ck(signsBytes(answer, (const uint8_t *)LOGIN, strlen(LOGIN)), "over LOGIN's bytes, by that address");

    for (i = 0; i < N(negative); i++) {
        memset(&key, 0xa5, sizeof(key));
        ck(BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_STANDARD, uri, n, negative[i], WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
           allZero(&key, sizeof(key)), "index %d, STANDARD: BRDigiIdSiteKey refuses, the key wiped", (int)negative[i]);
        memset(answer, 'X', sizeof(answer));
        ck(BRDigiIdSignSite(answer, sizeof(answer), seed, 64, BR_DIGIID_ROOT_STANDARD, (const uint8_t *)LOGIN, strlen(LOGIN), uri, n,
                            negative[i], WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && answer[0] == '\0',
           "index %d, STANDARD: BRDigiIdSignSite refuses, out empty", (int)negative[i]);
        memset(addr, 'X', sizeof(addr));
        ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, BR_DIGIID_ROOT_STANDARD, 1, uri, n, negative[i], WORK, sizeof(WORK)) ==
           BR_DIGIID_REFUSED && addr[0] == '\0', "index %d, STANDARD: BRDigiIdAddress refuses, out empty", (int)negative[i]);
    }
}

static void shared_key_derivation(void)
{
    static const uint32_t path[3] = { 0 | HARD, 0, 0 };
    uint8_t seed[64];
    BRKey key, ref;
    char addr[75];

    seedOf(seed, ABANDON);
    ck(BRDigiIdSharedKey(&key, seed, 64) == BR_DIGIID_OK && sameSecret(&key, ownSecret(seed, 64, "DigiByte seed", path, 3)),
       "the shared identity is m/0'/0/0");
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

// The shared identity takes no root (THE SHARED IDENTITY): BRDigiIdAddress without a URI gives the one shared address
// under either known root, and refuses any index but 0 under either.
static void shared_address_both_roots(void)
{
    static const BRDigiIdRoot roots[] = { BR_DIGIID_ROOT_DIGIBYTE, BR_DIGIID_ROOT_STANDARD };
    uint8_t seed[64];
    char out[BR_DIGIID_ADDRESS_CAPACITY];
    size_t i;

    seedOf(seed, ABANDON);
    for (i = 0; i < N(roots); i++) {
        ck(BRDigiIdAddress(out, sizeof(out), seed, 64, roots[i], 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           strcmp(out, SHARED_ADDRESS) == 0, "root %d, no URI: the shared address, the bridge's (%s)", (int)roots[i], out);
        memset(out, 'X', sizeof(out));
        ck(BRDigiIdAddress(out, sizeof(out), seed, 64, roots[i], 0, NULL, 0, 1, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && out[0] == '\0',
           "root %d, no URI, index 1: refused", (int)roots[i]);
    }
}

// THE ROOT: only BR_DIGIID_ROOT_DIGIBYTE (1) and BR_DIGIID_ROOT_STANDARD (2) derive. 0, which a zeroed or unset root
// holds, and every other value are REFUSED by each call that takes a root, on both branches of BRDigiIdAddress and
// ahead of CAPACITY, the key wiped and out empty as every other refusal leaves them. The same calls under a known
// root answer, so each refusal is the root's alone.
static void unknown_root_refused(void)
{
    static const struct { BRDigiIdRoot root; const char *name; } BAD[] = {
        { BR_DIGIID_ROOT_NONE, "0 (BR_DIGIID_ROOT_NONE)" }, { (BRDigiIdRoot)3, "3" }, { (BRDigiIdRoot)-1, "(BRDigiIdRoot)-1" },
    };
    static const BRDigiIdRoot GOOD[] = { BR_DIGIID_ROOT_DIGIBYTE, BR_DIGIID_ROOT_STANDARD };
    const uint8_t *msg = (const uint8_t *)REQUEST;
    const size_t m = strlen(REQUEST), n = strlen(REQUEST_CALLBACK), little = 4 + strlen(REQUEST_CALLBACK) - 1;   // a byte short
    uint8_t seed[64];
    char out[BR_DIGIID_ANSWER_CAPACITY], addr[BR_DIGIID_ADDRESS_CAPACITY];
    BRKey key;
    size_t i;

    ck(BR_DIGIID_ROOT_NONE == 0 && BR_DIGIID_ROOT_DIGIBYTE == 1 && BR_DIGIID_ROOT_STANDARD == 2,
       "the roots are the values every platform passes: none 0, DIGIBYTE 1, STANDARD 2");
    seedOf(seed, ABANDON);
    for (i = 0; i < N(BAD); i++) {
        const BRDigiIdRoot r = BAD[i].root;
        const char *s = BAD[i].name;

        memset(&key, 0xa5, sizeof(key));
        ck(BRDigiIdSiteKey(&key, seed, 64, r, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
           "root %s: BRDigiIdSiteKey refuses, the key wiped", s);
        memset(&key, 0xa5, sizeof(key));
        ck(BRDigiIdSiteKey(&key, seed, 64, r, REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
           "root %s, and too little work: REFUSED, not CAPACITY, the key wiped", s);
        memset(out, 'X', sizeof(out));
        ck(BRDigiIdSignSite(out, sizeof(out), seed, 64, r, msg, m, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
           out[0] == '\0', "root %s: BRDigiIdSignSite refuses, out empty", s);
        memset(out, 'X', sizeof(out));
        ck(BRDigiIdSignSite(out, sizeof(out), seed, 64, r, msg, m, REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_REFUSED &&
           out[0] == '\0', "root %s, and too little work: REFUSED", s);
        memset(addr, 'X', sizeof(addr));
        ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, r, 1, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
           addr[0] == '\0', "root %s: BRDigiIdAddress refuses the per-site address, out empty", s);
        memset(addr, 'X', sizeof(addr));
        ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, r, 1, REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_REFUSED && addr[0] == '\0',
           "root %s, and too little work: REFUSED", s);
        memset(addr, 'X', sizeof(addr));
        ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, r, 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && addr[0] == '\0',
           "root %s: and the shared address too, though it takes no root: out empty", s);
    }
    for (i = 0; i < N(GOOD); i++) {
        ck(BRDigiIdSiteKey(&key, seed, 64, GOOD[i], REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           BRDigiIdSignSite(out, sizeof(out), seed, 64, GOOD[i], msg, m, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           BRDigiIdAddress(addr, sizeof(addr), seed, 64, GOOD[i], 1, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           BRDigiIdAddress(addr, sizeof(addr), seed, 64, GOOD[i], 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
           "root %d: the same calls answer", (int)GOOD[i]);
        BRKeyClean(&key);
        ck(BRDigiIdSiteKey(&key, seed, 64, GOOD[i], REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_CAPACITY &&
           BRDigiIdSignSite(out, sizeof(out), seed, 64, GOOD[i], msg, m, REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_CAPACITY &&
           BRDigiIdAddress(addr, sizeof(addr), seed, 64, GOOD[i], 1, REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_CAPACITY,
           "root %d: and with too little work, CAPACITY", (int)GOOD[i]);
    }
}

// THE ROOT, beside hasUri: in BRDigiIdAddress the two are neighbouring arguments, and C passes an int where the enum
// goes, and the enum where an int goes, without a word (clang -Wall -Wextra -Wpedantic says nothing). So hasUri is 0
// or 1, and every other value is REFUSED, ahead of CAPACITY, out empty. A caller that swaps the two is then refused,
// or makes the very call it meant (DIGIBYTE with a URI): a STANDARD wallet asking for a per-site address, swapped,
// asks the DIGIBYTE root with hasUri 2, which is refused instead of answering DIGIBYTE's identity, an address the
// login (BRDigiIdSignSite, whose root is followed by a pointer) never signs with.
static void address_root_hasuri_swapped(void)
{
    static const int BAD[] = { 2, 3, -1, INT_MAX, INT_MIN };
    static const BRDigiIdRoot ROOTS[] = { BR_DIGIID_ROOT_DIGIBYTE, BR_DIGIID_ROOT_STANDARD };
    const size_t n = strlen(REQUEST_CALLBACK), little = 4 + strlen(REQUEST_CALLBACK) - 1;   // a byte short
    uint8_t seed[64];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], meant[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY];
    size_t r, i;
    int h;

    seedOf(seed, ABANDON);
    for (r = 0; r < N(ROOTS); r++) {
        const BRDigiIdRoot root = ROOTS[r];

        for (i = 0; i < N(BAD); i++) {
            memset(addr, 'X', sizeof(addr));
            ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, root, BAD[i], REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
               addr[0] == '\0', "root %d, hasUri %d: refused, out empty", (int)root, BAD[i]);
            memset(addr, 'X', sizeof(addr));
            ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, root, BAD[i], REQUEST_CALLBACK, n, 0, WORK, little) == BR_DIGIID_REFUSED &&
               addr[0] == '\0', "root %d, hasUri %d, and too little work: REFUSED, not CAPACITY", (int)root, BAD[i]);
        }
        // Each call a caller means (a URI exactly when hasUri is 1), and the same call with root and hasUri swapped.
        for (h = 0; h <= 1; h++) {
            const char *u = h ? REQUEST_CALLBACK : NULL;
            const size_t ul = h ? n : 0;
            BRDigiIdStatus s;

            ck(BRDigiIdAddress(meant, sizeof(meant), seed, 64, root, h, u, ul, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
               "root %d, hasUri %d: the call meant answers (%s)", (int)root, h, meant);
            memset(addr, 'X', sizeof(addr));
            s = BRDigiIdAddress(addr, sizeof(addr), seed, 64, (BRDigiIdRoot)h, (int)root, u, ul, 0, WORK, sizeof(WORK));
            if (root == BR_DIGIID_ROOT_DIGIBYTE && h == 1)
                ck(s == BR_DIGIID_OK && strcmp(addr, meant) == 0, "root %d, hasUri %d swapped: the same call, the same address", (int)root, h);
            else
                ck(s == BR_DIGIID_REFUSED && addr[0] == '\0', "root %d, hasUri %d swapped: refused, out empty (%s%s%s)", (int)root, h, st(s),
                   s == BR_DIGIID_OK ? " " : "", s == BR_DIGIID_OK ? addr : "");
        }
        // The per-site address a caller shows is the one the login signs with, under either root.
        ck(BRDigiIdAddress(meant, sizeof(meant), seed, 64, root, 1, REQUEST_CALLBACK, n, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           BRDigiIdSignSite(answer, sizeof(answer), seed, 64, root, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, n, 0, WORK,
                            sizeof(WORK)) == BR_DIGIID_OK && strncmp(answer, meant, strlen(meant)) == 0 && answer[strlen(meant)] == '|',
           "root %d: the per-site address is the one the login signs with", (int)root);
    }
}

// ---- the message ---------------------------------------------------------------------------------

static void message_prefix(void)
{
    static const struct { size_t len; uint8_t bytes[9]; size_t n; } V[] = {
        { 0, { 0x00 }, 1 }, { 1, { 0x01 }, 1 }, { 0xfc, { 0xfc }, 1 }, { 0xfd, { 0xfd, 0xfd, 0x00 }, 3 },
        { 0xfe, { 0xfd, 0xfe, 0x00 }, 3 }, { 0x100, { 0xfd, 0x00, 0x01 }, 3 }, { 0xffff, { 0xfd, 0xff, 0xff }, 3 },
        { 0x10000, { 0xfe, 0x00, 0x00, 0x01, 0x00 }, 5 }, { 0x10001, { 0xfe, 0x01, 0x00, 0x01, 0x00 }, 5 },
        { 0x100fd, { 0xfe, 0xfd, 0x00, 0x01, 0x00 }, 5 }, { 0x1ffff, { 0xfe, 0xff, 0xff, 0x01, 0x00 }, 5 },
        { 0xffffffffu, { 0xfe, 0xff, 0xff, 0xff, 0xff }, 5 },
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
    {
        const size_t pmax = SIZE_MAX > 0xffffffffu ? 9 : 5;   /* SIZE_MAX's own prefix */
        ck(BRDigiIdMessagePrefix(p, SIZE_MAX) == pmax && p[0] == (pmax == 9 ? 0xff : 0xfe) && p[1] == 0xff && p[pmax - 1] == 0xff,
           "SIZE_MAX: a full compact size, %zu bytes", pmax);
        ck(BRDigiIdDigestWorkSize(SIZE_MAX) == 0 && BRDigiIdDigestWorkSize(SIZE_MAX - 26 - pmax) == SIZE_MAX &&
           BRDigiIdDigestWorkSize(SIZE_MAX - 25 - pmax) == 0,
       "a message length that does not fit with its header has no work size");
    }
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
           BRDigiIdAddress(addr, sizeof(addr), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, (const char *)uri, len, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
           strcmp(addr, *V[i].address) == 0, "%s: the bridge's address (%s)", V[i].name, addr);
    }
    BRDigiIdUriBytesFromUtf16(uri, sizeof(uri), &len, emoji, N(emoji));
    ck(BRDigiIdSignSite(answer, sizeof(answer), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)REQUEST, strlen(REQUEST), (const char *)uri,
                        len, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK && strcmp(answer, EMOJI_URI_REQUEST_ANSWER) == 0,
       "the request signed with the emoji URL's identity: the bridge's answer");
    ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, (const char *)emojiSpec, sizeof(emojiSpec), 0, WORK, sizeof(WORK)) ==
       BR_DIGIID_OK && strcmp(addr, EMOJI_URI_SPEC_ADDRESS) == 0 && strcmp(addr, EMOJI_URI_ADDRESS) != 0,
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
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, msg, len, "https://site.example/login", 26, 0, WORK, sizeof(WORK)) ==
       BR_DIGIID_OK && strcmp(site, EMOJI_SITE_ANSWER) == 0, "an emoji message with the per-site identity: the bridge's answer");
    ck(signsBytes(site, msg, len), "over the message's UTF-8");

    ck(hSite(site, seed, REQUEST, REQUEST_CALLBACK, -1) == BR_DIGIID_REFUSED && site[0] == '\0', "index -1 is refused");
    ck(hSite(site, seed, REQUEST, REQUEST_CALLBACK, INT32_MIN) == BR_DIGIID_REFUSED, "index INT32_MIN is refused");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)REQUEST, strlen(REQUEST), NULL, 0, 0, WORK,
                        sizeof(WORK)) == BR_DIGIID_REFUSED, "no URI is refused");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, NULL, 0, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED,
       "no message is refused");
    ck(BRDigiIdSignSiteWorkSize(28, strlen(REQUEST)) == 26 + 1 + strlen(REQUEST) && BRDigiIdSignSiteWorkSize(200, 1) == 204 &&
       BRDigiIdSignSiteWorkSize(SIZE_MAX, 1) == 0 && BRDigiIdSignSiteWorkSize(1, SIZE_MAX) == 0, "the work is the larger of the two hashes'");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0,
                        WORK, BRDigiIdSignSiteWorkSize(28, strlen(REQUEST))) == BR_DIGIID_OK && strcmp(site, REQUEST_SITE_ANSWER) == 0,
       "exactly that work suffices");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0,
                        WORK, BRDigiIdSignSiteWorkSize(28, strlen(REQUEST)) - 1) == BR_DIGIID_CAPACITY, "a byte less is CAPACITY");
    ck(BRDigiIdSignSite(site, sizeof(site), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, 31) ==
       BR_DIGIID_CAPACITY, "work for the message but not the URI is CAPACITY");
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
    ck(BRDigiIdAddress(out, sizeof(out), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED,
       "a URI asked for with none given: refused");
    len = strlen(SHARED_ADDRESS);
    ck(BRDigiIdAddress(small, len + 1, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_OK &&
       strcmp(small, SHARED_ADDRESS) == 0, "out of exactly the address and its NUL suffices (and the shared identity needs no work)");
    ck(BRDigiIdAddress(small, len, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_CAPACITY && small[0] == '\0',
       "a byte less is CAPACITY");
    ck(BRDigiIdAddress(out, sizeof(out), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, REQUEST_CALLBACK, 28, 0, WORK, 31) == BR_DIGIID_CAPACITY,
       "too little work is CAPACITY");
    ck(BRDigiIdAddress(NULL, 0, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 0, NULL, 0, 0, WORK, 0) == BR_DIGIID_REFUSED, "no out is refused");
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
    BRDigiIdSiteKey(&key, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, REQUEST_CALLBACK, strlen(REQUEST_CALLBACK), 0, WORK, sizeof(WORK));
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
        ok = BRDigiIdSignSite(out, sizeof(out), seed, 64, BR_DIGIID_ROOT_DIGIBYTE, msg, lens[i], REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) ==
             BR_DIGIID_OK;
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
    ck(BRDigiIdSignSite(one, 0, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) ==
       BR_DIGIID_CAPACITY && one[0] == 'X', "SignSite: likewise");
    ck(BRDigiIdAddress(one, 0, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && one[0] == 'X',
       "Address: likewise");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(one, 0, &key, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_CAPACITY && one[0] == 'X' &&
       allZero(&key, sizeof(key)), "SignWithKey: likewise, the key wiped");
    ck(BRDigiIdSignShared(one, 0, NULL, 64, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && one[0] == 'X',
       "a refusal with an out of 0 bytes writes nothing");

    ck(BRDigiIdSignShared(NULL, 10, seed, 64, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED &&
       BRDigiIdSignSite(NULL, 10, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)"m", 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) ==
       BR_DIGIID_REFUSED &&
       BRDigiIdAddress(NULL, 10, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED,
       "a NULL out with a capacity is refused, untouched");
    BRDigiIdSharedKey(&key, seed, 64);
    ck(BRDigiIdSignWithKey(NULL, 10, &key, (const uint8_t *)"m", 1, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && allZero(&key, sizeof(key)),
       "and by SignWithKey, the key wiped");

    len = strlen(ref);
    exact = malloc(len + 1);
    ck(hSite(exact, seed, REQUEST, REQUEST_CALLBACK, 0) == BR_DIGIID_OK, "warm-up");
    ck(BRDigiIdSignSite(exact, len + 1, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, (const uint8_t *)REQUEST, strlen(REQUEST), REQUEST_CALLBACK, 28, 0, WORK,
                        sizeof(WORK)) == BR_DIGIID_OK && strcmp(exact, ref) == 0, "an answer into a heap out of exactly its size");
    free(exact);
    len = strlen(refAddr);
    exact = malloc(len + 1);
    ck(BRDigiIdAddress(exact, len + 1, seed, 64, BR_DIGIID_ROOT_DIGIBYTE, 1, REQUEST_CALLBACK, 28, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
       strcmp(exact, refAddr) == 0, "an address into a heap out of exactly its size");
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

// ---- which identity signs ------------------------------------------------------------------------

// THE DIGISCOPE LIST as this file holds it: the two hosts iOS's DigiIdIdentityTests and Android's
// IdentityKeyPolicyTest name, written here and never read from the header. `digiid_core_kat hosts` prints them for
// run.sh's SOURCE GATE.
static const char *const LISTED_HOSTS[] = { "digiscope.me", "api.digiscope.me" };

// DigiScope's login callback URL, written here: the second of the three URLs Coinomi was measured signing in to (the
// design's table). Its per-site identity for ABANDON is the address Coinomi showed for it under "Bitcoin seed"
// (site_key_standard_coinomi_uris) and the bridge's under "DigiByte seed" (site_key_digibyte_coinomi_uris).
static const char *DIGISCOPE_LOGIN_URI = "https://api.digiscope.me/api/auth/digiid/callback";
static const char *DIGISCOPE_LOGIN_STANDARD_ADDRESS = "DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri";
static const char *DIGISCOPE_LOGIN_DIGIBYTE_ADDRESS = "DKBDZRPxPz6qGseZKNNzSwW3zfaRr1FQNA";

// The two values each of a wallet's two properties takes (THE CHOICE).
static const BRDigiIdScopeIdentity SCOPES[] = { BR_DIGIID_SCOPE_SHARED, BR_DIGIID_SCOPE_SITE };
static const BRDigiIdRoot ROOTS[] = { BR_DIGIID_ROOT_DIGIBYTE, BR_DIGIID_ROOT_STANDARD };

static const char *scopeName(BRDigiIdScopeIdentity s)
{
    return s == BR_DIGIID_SCOPE_SHARED ? "SHARED" : (s == BR_DIGIID_SCOPE_SITE ? "SITE" : "?");
}

static const char *rootName(BRDigiIdRoot r)
{
    return r == BR_DIGIID_ROOT_DIGIBYTE ? "DIGIBYTE" : (r == BR_DIGIID_ROOT_STANDARD ? "STANDARD" : "?");
}

// BRDigiIdChooseIdentity into *c, which is filled with 0xa5 first: what a refusal must zero is then not zero already.
static BRDigiIdStatus hChoose(BRDigiIdChoice *c, const char *host, size_t hostLen, const char *callback, size_t callbackLen,
                              BRDigiIdScopeIdentity scope, BRDigiIdRoot root)
{
    memset(c, 0xa5, sizeof(*c));
    return BRDigiIdChooseIdentity(c, host, hostLen, callback, callbackLen, scope, root);
}

// hChoose for a host and a callback that are C strings (callback NULL: the request has none).
static BRDigiIdStatus hChooseStr(BRDigiIdChoice *c, const char *host, const char *callback, BRDigiIdScopeIdentity scope, BRDigiIdRoot root)
{
    return hChoose(c, host, strlen(host), callback, callback ? strlen(callback) : 0, scope, root);
}

// What a refusal leaves: every byte zero, so no identity, no URI and no root.
static int isNoChoice(const BRDigiIdChoice *c)
{
    return allZero(c, sizeof(*c)) && c->kind == BR_DIGIID_IDENTITY_NONE && c->uri == NULL && c->uriLen == 0 && c->root == BR_DIGIID_ROOT_NONE;
}

// The SHARED answer: the shared identity, which names no URI and no root.
static int isSharedChoice(const BRDigiIdChoice *c)
{
    return c->kind == BR_DIGIID_IDENTITY_SHARED && c->uri == NULL && c->uriLen == 0 && c->root == BR_DIGIID_ROOT_NONE;
}

// The SITE answer that passes the request's callback through: the caller's own pointer and length, under `root`.
static int isCallbackChoice(const BRDigiIdChoice *c, const char *callback, size_t callbackLen, BRDigiIdRoot root)
{
    return c->kind == BR_DIGIID_IDENTITY_SITE && c->uri == callback && c->uriLen == callbackLen && c->root == root;
}

// The SITE answer for DigiScope's login URL under `root`: that URL's bytes (this file's copy of them), in storage that
// is not the request's callback, whatever the callback's bytes are.
static int isLoginChoice(const BRDigiIdChoice *c, const char *callback, BRDigiIdRoot root)
{
    const size_t n = strlen(DIGISCOPE_LOGIN_URI);

    return c->kind == BR_DIGIID_IDENTITY_SITE && c->root == root && c->uri != NULL && c->uri != callback && c->uriLen == n &&
           memcmp(c->uri, DIGISCOPE_LOGIN_URI, n) == 0;
}

// Every answer THE CHOICE gives a host that is NOT on the list, for a request with this callback: both digiScopeIdentity
// values crossed with both roots give the per-site identity of that callback, the caller's pointer and length, under
// the wallet's root. Nonzero when all four do.
static int offListAnswers(const char *host, size_t hostLen, const char *callback, size_t callbackLen)
{
    BRDigiIdChoice c;
    size_t s, r;
    int ok = 1;

    for (s = 0; s < N(SCOPES); s++)
        for (r = 0; r < N(ROOTS); r++)
            ok = hChoose(&c, host, hostLen, callback, callbackLen, SCOPES[s], ROOTS[r]) == BR_DIGIID_OK &&
                 isCallbackChoice(&c, callback, callbackLen, ROOTS[r]) && ok;
    return ok;
}

// offListAnswers for a host and a callback that are C strings.
static int offListStr(const char *host, const char *callback)
{
    return offListAnswers(host, strlen(host), callback, strlen(callback));
}

// One host that is not on the list, as a request names it (callback https://<host>/api/digiid/callback, the path of
// both apps' list tests): BRDigiIdIsDigiScopeHost answers 0, and THE CHOICE its own per-site identity (offListAnswers).
static void ckOffList(const char *host)
{
    char callback[128];
    size_t n = strlen(host);

    snprintf(callback, sizeof(callback), "https://%s/api/digiid/callback", host);
    ck(BRDigiIdIsDigiScopeHost(host, n) == 0, "\"%s\" is not a DigiScope host", host);
    ck(offListStr(host, callback), "\"%s\": its own per-site identity, under both digiScopeIdentity values and both roots", host);
}

// THE DIGISCOPE LIST holds the two hosts: iOS's testTheListedDigiScopeHostsAlwaysUseTheSharedIdentity and
// testTheListHoldsExactlyTheTwoDigiScopeHosts (Android's IdentityKeyPolicyTest: the same two). iOS's choose(domain:)
// is THE CHOICE as iOS asks it until it has per-wallet properties: digiScopeIdentity SHARED, root DIGIBYTE. Then what
// the header states about them: the two enums' values, the login URL's text, and that its host is on the list and is
// the server both apps pin.
static void digiscope_list_hosts(void)
{
    static const char among[] = { 'x', 'd', 'i', 'g', 'i', 's', 'c', 'o', 'p', 'e', '.', 'm', 'e', 'x' };   // no NUL anywhere
    BRDigiIdChoice c;
    size_t i;

    ck(N(LISTED_HOSTS) == 2 && strcmp(LISTED_HOSTS[0], "digiscope.me") == 0 && strcmp(LISTED_HOSTS[1], "api.digiscope.me") == 0,
       "the list this file holds is the two hosts: digiscope.me, api.digiscope.me");
    for (i = 0; i < N(LISTED_HOSTS); i++) {
        const char *h = LISTED_HOSTS[i];
        char callback[128];

        snprintf(callback, sizeof(callback), "https://%s/api/digiid/callback", h);
        ck(BRDigiIdIsDigiScopeHost(h, strlen(h)) == 1, "\"%s\" is a DigiScope host", h);
        ck(hChooseStr(&c, h, callback, BR_DIGIID_SCOPE_SHARED, BR_DIGIID_ROOT_DIGIBYTE) == BR_DIGIID_OK && isSharedChoice(&c),
           "\"%s\", SHARED and DIGIBYTE (as iOS asks today): the shared identity", h);
    }
    ck(BRDigiIdIsDigiScopeHost(among + 1, 12) == 1,
       "the 12 bytes of digiscope.me between other bytes, with no NUL after them: the length is what ends the host");

    ck(BR_DIGIID_SCOPE_NONE == 0 && BR_DIGIID_SCOPE_SHARED == 1 && BR_DIGIID_SCOPE_SITE == 2,
       "digiScopeIdentity's values are the ones every platform passes: none 0, SHARED 1, SITE 2");
    ck(BR_DIGIID_IDENTITY_NONE == 0 && BR_DIGIID_IDENTITY_SHARED == 1 && BR_DIGIID_IDENTITY_SITE == 2,
       "and the chosen identity's: none 0, SHARED 1, SITE 2");
    ck(strcmp(BR_DIGIID_DIGISCOPE_LOGIN_URI, DIGISCOPE_LOGIN_URI) == 0 && strlen(BR_DIGIID_DIGISCOPE_LOGIN_URI) == 49,
       "BR_DIGIID_DIGISCOPE_LOGIN_URI is DigiScope's login callback, 49 bytes (%s)", BR_DIGIID_DIGISCOPE_LOGIN_URI);
    ck(strncmp(DIGISCOPE_LOGIN_URI, "https://", 8) == 0 && strncmp(DIGISCOPE_LOGIN_URI + 8, LISTED_HOSTS[1], 16) == 0 &&
       DIGISCOPE_LOGIN_URI[24] == '/' && BRDigiIdIsDigiScopeHost(DIGISCOPE_LOGIN_URI + 8, 16) == 1,
       "its host is api.digiscope.me, a DigiScope host");
    ck(strcmp(LISTED_HOSTS[1], BR_DIGISCOPE_HOST) == 0, "and that host is the server both apps pin (BRDigiScopePins.h's BR_DIGISCOPE_HOST, %s)",
       BR_DIGISCOPE_HOST);
}

// iOS's testADigiScopeSubdomainThatIsNotOnTheListGetsAPerSiteIdentity, value for value: a name under a listed host is
// not on the list. Beyond iOS's five: an empty label under the host, and a name under it that is not a word.
static void digiscope_list_subdomains(void)
{
    static const char *IOS[] = { "hub.digiscope.me", "a.digiscope.me", "www.digiscope.me", "x.api.digiscope.me", "api.api.digiscope.me" };
    static const char *MORE[] = { ".digiscope.me", ".api.digiscope.me", "pi.digiscope.me", "evil.example.digiscope.me" };
    size_t i;

    for (i = 0; i < N(IOS); i++) ckOffList(IOS[i]);
    for (i = 0; i < N(MORE); i++) ckOffList(MORE[i]);
}

// iOS's testALookalikeDomainIsNotTreatedAsDigiScope and testANewDomainGetsAPerSiteIdentity, value for value: a name
// that only holds a listed host's letters, or ends in them with no dot before, or carries a trailing dot, is another
// site. Beyond iOS's: what no parse gives as a domain (a port, a slash, a blank), one letter more, and other sites.
static void digiscope_list_lookalikes(void)
{
    static const char *IOS[] = { "notdigiscope.me", "digiscope.me.other.example", "adigiscope.me", "api-digiscope.me", "digiscope.me.",
                                 "api.digiscope.me.", "example.com" };
    static const char *MORE[] = { "digiscope.me:443", "api.digiscope.me:8443", "digiscope.me/", " digiscope.me", "digiscope.me ",
                                  "digiscope.mee", "digiscope_me", "apidigiscope.me", "digiscope.com", "me", "localhost" };
    size_t i;

    for (i = 0; i < N(IOS); i++) ckOffList(IOS[i]);
    for (i = 0; i < N(MORE); i++) ckOffList(MORE[i]);
}

// iOS's testTheDomainComesFromTheParse: the list is matched byte for byte and the parse lower-cases the domain, so the
// parse's spelling is the one that counts. The parse is each app's own: the domain it gives digiid://API.DigiScope.me/...
// is "api.digiscope.me", which digiscope_list_hosts holds. The spellings no parse gives are off the list, iOS's first.
static void digiscope_list_letter_case(void)
{
    static const char *HOSTS[] = { "API.DigiScope.me", "DigiScope.ME", "DIGISCOPE.ME", "Digiscope.me", "digiscope.mE", "API.DIGISCOPE.ME",
                                   "api.Digiscope.me" };
    size_t i;

    for (i = 0; i < N(HOSTS); i++) ckOffList(HOSTS[i]);
}

// Beyond iOS's cases: no host, an empty one, and a length that is not the host's own. A listed host's bytes with one
// fewer, or with the NUL that ends its C string counted in, are another host, as is any host with a NUL among its
// bytes: all hostLen bytes are compared, and nothing after them is read. THE CHOICE reads the same bytes; with no host
// it refuses, where both apps' choose("") answered per-site: no request has an empty host (each parse refuses it).
static void digiscope_list_edges(void)
{
    static const char host[] = "digiscope.me", api[] = "api.digiscope.me";   // 13 and 17 bytes with their NULs
    static const char nulInside[] = { 'd', 'i', 'g', 'i', 0, 'c', 'o', 'p', 'e', '.', 'm', 'e' };
    static const char nulThenRest[] = { 'd', 'i', 'g', 'i', 's', 'c', 'o', 'p', 'e', '.', 'm', 'e', 0, 'x' };
    static const char noNulAfter[] = { 'd', 'i', 'g', 'i', 's', 'c', 'o', 'p', 'e', '.', 'm', 'e', 'e' };
    const size_t n = strlen(REQUEST_CALLBACK);
    BRDigiIdChoice c;
    size_t s, r;

    ck(BRDigiIdIsDigiScopeHost("", 0) == 0 && BRDigiIdIsDigiScopeHost(host, 0) == 0, "an empty host is not a DigiScope host (digiscope.me with length 0 is empty)");
    ck(BRDigiIdIsDigiScopeHost(NULL, 0) == 0 && BRDigiIdIsDigiScopeHost(NULL, 12) == 0 && BRDigiIdIsDigiScopeHost(NULL, 16) == 0,
       "no host (NULL) is not one, whatever length comes with it");
    ck(BRDigiIdIsDigiScopeHost(host, 12) == 1 && BRDigiIdIsDigiScopeHost(host, 11) == 0, "digiscope.me one byte short (digiscope.m) is not");
    ck(BRDigiIdIsDigiScopeHost(host, 13) == 0, "digiscope.me one byte long, its NUL counted in, is not: an embedded NUL never matches");
    ck(BRDigiIdIsDigiScopeHost(api, 16) == 1 && BRDigiIdIsDigiScopeHost(api, 15) == 0 && BRDigiIdIsDigiScopeHost(api, 17) == 0,
       "api.digiscope.me one byte short and one byte long are not");
    ck(BRDigiIdIsDigiScopeHost(api, 12) == 0 && BRDigiIdIsDigiScopeHost(host, 1) == 0 && BRDigiIdIsDigiScopeHost(api, 3) == 0,
       "a listed host's first bytes are not (api.digiscop, d, api)");
    ck(BRDigiIdIsDigiScopeHost(nulInside, sizeof(nulInside)) == 0, "12 bytes with a NUL where digiscope.me has its s are not");
    ck(BRDigiIdIsDigiScopeHost(nulThenRest, sizeof(nulThenRest)) == 0 && BRDigiIdIsDigiScopeHost(nulThenRest, 13) == 0,
       "digiscope.me, a NUL and more bytes are not: the host does not end at the NUL");
    ck(BRDigiIdIsDigiScopeHost(noNulAfter, 12) == 1 && BRDigiIdIsDigiScopeHost(noNulAfter, 13) == 0,
       "digiscope.mee: its first 12 bytes are the host, all 13 are not, and no NUL is looked for");

    ck(offListAnswers(host, 11, REQUEST_CALLBACK, n) && offListAnswers(host, 13, REQUEST_CALLBACK, n) && offListAnswers(api, 15, REQUEST_CALLBACK, n) &&
       offListAnswers(api, 17, REQUEST_CALLBACK, n) && offListAnswers(nulInside, sizeof(nulInside), REQUEST_CALLBACK, n),
       "THE CHOICE reads the same bytes: each of these is another site, with its own per-site identity");
    for (s = 0; s < N(SCOPES); s++) {
        for (r = 0; r < N(ROOTS); r++) {
            ck(hChoose(&c, "", 0, REQUEST_CALLBACK, n, SCOPES[s], ROOTS[r]) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
               hChoose(&c, NULL, 0, REQUEST_CALLBACK, n, SCOPES[s], ROOTS[r]) == BR_DIGIID_REFUSED && isNoChoice(&c),
               "%s, %s: an empty host and no host are refused, the answer all zero", scopeName(SCOPES[s]), rootName(ROOTS[r]));
        }
    }
}

// The callbacks a request to a DigiScope host is tried with: none at all (the Hub's own login has no request), an
// empty one, DigiScope's login URL in the request's own storage, the probe callback Coinomi was measured at for that
// host, and another site's.
typedef struct { const char *uri; size_t len; const char *name; } Callback;

static size_t digiScopeCallbacks(Callback out[5], char ownLogin[64], const char *probe)
{
    strcpy(ownLogin, DIGISCOPE_LOGIN_URI);
    out[0].uri = NULL; out[0].len = 0; out[0].name = "no callback";
    out[1].uri = ""; out[1].len = 0; out[1].name = "an empty callback";
    out[2].uri = ownLogin; out[2].len = strlen(ownLogin); out[2].name = "the login URL, in the request's own storage";
    out[3].uri = probe; out[3].len = strlen(probe); out[3].name = probe;
    out[4].uri = REQUEST_CALLBACK; out[4].len = strlen(REQUEST_CALLBACK); out[4].name = REQUEST_CALLBACK;
    return 5;
}

static const struct { const char *host, *probe; } DIGISCOPE[] = {
    { "digiscope.me", "https://digiscope.me/api/digiid-probe/callback" },
    { "api.digiscope.me", "https://api.digiscope.me/api/digiid-probe/callback" },
};

// THE CHOICE, a DigiScope host and digiScopeIdentity SHARED (every wallet but one restored as from Coinomi): the shared
// identity, m/0'/0/0, on both hosts, under either root and whatever callback the request has, or none. The answer
// names no URI and no root.
static void choice_digiscope_shared(void)
{
    Callback cb[5];
    char own[64];
    BRDigiIdChoice c;
    size_t h, r, k, count;

    for (h = 0; h < N(DIGISCOPE); h++) {
        count = digiScopeCallbacks(cb, own, DIGISCOPE[h].probe);
        for (r = 0; r < N(ROOTS); r++) {
            for (k = 0; k < count; k++) {
                BRDigiIdStatus s = hChoose(&c, DIGISCOPE[h].host, strlen(DIGISCOPE[h].host), cb[k].uri, cb[k].len, BR_DIGIID_SCOPE_SHARED, ROOTS[r]);

                ck(s == BR_DIGIID_OK && isSharedChoice(&c), "%s, SHARED, %s, %s: the shared identity, no URI and no root (%s)", DIGISCOPE[h].host,
                   rootName(ROOTS[r]), cb[k].name, st(s));
            }
        }
    }
}

// THE CHOICE, a DigiScope host and digiScopeIdentity SITE (a wallet restored as from Coinomi): the per-site identity
// of DigiScope's one login URL under the wallet's root, on both hosts and whatever callback the request has, or none.
// Never the request's own callback: not its bytes when they differ (the probe's, another site's), and not its pointer
// when they are the login URL's own. One DigiScope account, one key.
static void choice_digiscope_site(void)
{
    Callback cb[5];
    char own[64];
    BRDigiIdChoice c;
    size_t h, r, k, count;

    for (h = 0; h < N(DIGISCOPE); h++) {
        count = digiScopeCallbacks(cb, own, DIGISCOPE[h].probe);
        for (r = 0; r < N(ROOTS); r++) {
            for (k = 0; k < count; k++) {
                BRDigiIdStatus s = hChoose(&c, DIGISCOPE[h].host, strlen(DIGISCOPE[h].host), cb[k].uri, cb[k].len, BR_DIGIID_SCOPE_SITE, ROOTS[r]);

                ck(s == BR_DIGIID_OK && isLoginChoice(&c, cb[k].uri, ROOTS[r]),
                   "%s, SITE, %s, %s: the per-site identity of the login URL under that root, not of the callback (%s)", DIGISCOPE[h].host,
                   rootName(ROOTS[r]), cb[k].name, st(s));
            }
        }
    }
}

// THE CHOICE, a host off the list: the per-site identity of the request's own callback URL under the wallet's root,
// whatever its digiScopeIdentity. The answer is the caller's pointer and length: the bytes are not copied, changed or
// read (THE URI: hashed as given), so a callback with no NUL after it, or with a NUL or bytes above 0x7f in it, comes
// back as it went in. The host and the callback are the caller's pair and are not compared: DigiScope's login URL
// given with a host off the list is still that request's callback.
static void choice_other_host(void)
{
    static const char odd[] = { 'h', 0x00, 't', (char)0x80, (char)0xff, (char)0xc0, (char)0x80, '/' };
    static const char idn[] = { 'b', (char)0xc3, (char)0xbc, 'c', 'h', 'e', 'r', '.', 'e', 'x', 'a', 'm', 'p', 'l', 'e' };   // b<U+00FC>cher.example, in UTF-8
    static const char *off = "example.com";
    const size_t n = strlen(REQUEST_CALLBACK);
    char unterminated[40], ownLogin[64];
    BRDigiIdChoice c;
    size_t s, r;

    memset(unterminated, 'x', sizeof(unterminated));
    memcpy(unterminated + 3, REQUEST_CALLBACK, n);   // 28 bytes, 'x' before and after, no NUL in the buffer
    strcpy(ownLogin, DIGISCOPE_LOGIN_URI);

    for (s = 0; s < N(SCOPES); s++) {
        for (r = 0; r < N(ROOTS); r++) {
            BRDigiIdStatus status = hChooseStr(&c, off, REQUEST_CALLBACK, SCOPES[s], ROOTS[r]);

            ck(status == BR_DIGIID_OK && c.kind == BR_DIGIID_IDENTITY_SITE && c.uri == REQUEST_CALLBACK && c.uriLen == 28 && c.root == ROOTS[r],
               "example.com, %s, %s: SITE, the request's callback (its pointer, 28 bytes), root %s (%s)", scopeName(SCOPES[s]), rootName(ROOTS[r]),
               rootName(ROOTS[r]), st(status));
        }
    }
    ck(offListAnswers(off, strlen(off), unterminated + 3, n), "a callback with no NUL after it: its pointer and length");
    ck(offListAnswers(off, strlen(off), odd, sizeof(odd)), "a callback with a NUL and bytes above 0x7f in it: its pointer and length");
    ck(offListStr(off, "/"), "a callback of one byte: its pointer and length");
    ck(offListStr(off, ownLogin), "DigiScope's login URL given with a host off the list: that request's callback (the pair is the caller's)");
    ck(offListAnswers(idn, sizeof(idn), REQUEST_CALLBACK, n) && BRDigiIdIsDigiScopeHost(idn, sizeof(idn)) == 0,
       "a host with bytes above 0x7f: another site");
    ck(offListStr("shop.example.com", "https://shop.example.com/digiid/callback/v1"), "shop.example.com: its own callback");
}

// What THE CHOICE refuses that is not a wallet property: no out, no host, and a host off the list with no callback URL.
// Each leaves the answer all zero, and the same call with nothing missing answers. (A DigiScope host needs no callback:
// choice_digiscope_shared, choice_digiscope_site.) Both properties unknown at once are refused as well.
static void choice_refused(void)
{
    static const char *off = "example.com", *listed = "digiscope.me";
    const size_t n = strlen(REQUEST_CALLBACK), on = strlen(off), ln = strlen(listed);
    BRDigiIdChoice c;
    size_t s, r;

    ck(BRDigiIdChooseIdentity(NULL, off, on, REQUEST_CALLBACK, n, BR_DIGIID_SCOPE_SHARED, BR_DIGIID_ROOT_DIGIBYTE) == BR_DIGIID_REFUSED &&
       BRDigiIdChooseIdentity(NULL, listed, ln, NULL, 0, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD) == BR_DIGIID_REFUSED,
       "no out: refused, for a host off the list and for a DigiScope host");
    for (s = 0; s < N(SCOPES); s++) {
        for (r = 0; r < N(ROOTS); r++) {
            const BRDigiIdScopeIdentity scope = SCOPES[s];
            const BRDigiIdRoot root = ROOTS[r];
            const char *sn = scopeName(scope), *rn = rootName(root);

            ck(hChoose(&c, NULL, on, REQUEST_CALLBACK, n, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
               hChoose(&c, NULL, 0, REQUEST_CALLBACK, n, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c),
               "%s, %s: no host (NULL), with a length or without: refused, the answer all zero", sn, rn);
            ck(hChoose(&c, off, 0, REQUEST_CALLBACK, n, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
               hChoose(&c, listed, 0, REQUEST_CALLBACK, n, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c),
               "%s, %s: an empty host (length 0, whatever it points at): refused, the answer all zero", sn, rn);
            ck(hChoose(&c, off, on, NULL, n, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
               hChoose(&c, off, on, NULL, 0, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c),
               "%s, %s: a host off the list with no callback (NULL): refused, the answer all zero", sn, rn);
            ck(hChoose(&c, off, on, REQUEST_CALLBACK, 0, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
               hChoose(&c, off, on, "", 0, scope, root) == BR_DIGIID_REFUSED && isNoChoice(&c),
               "%s, %s: a host off the list with an empty callback: refused, the answer all zero", sn, rn);
            ck(hChoose(&c, off, on, REQUEST_CALLBACK, n, scope, root) == BR_DIGIID_OK && isCallbackChoice(&c, REQUEST_CALLBACK, n, root),
               "%s, %s: the same call with its host and its callback answers", sn, rn);
        }
    }
    ck(hChoose(&c, off, on, REQUEST_CALLBACK, n, BR_DIGIID_SCOPE_NONE, BR_DIGIID_ROOT_NONE) == BR_DIGIID_REFUSED && isNoChoice(&c) &&
       hChoose(&c, listed, ln, REQUEST_CALLBACK, n, BR_DIGIID_SCOPE_NONE, BR_DIGIID_ROOT_NONE) == BR_DIGIID_REFUSED && isNoChoice(&c),
       "neither property set (both 0): refused on and off the list, the answer all zero");
}

// The hosts and callbacks the two property refusals are tried on: both DigiScope hosts, with a callback and with none,
// and a host off the list. Each case then makes the same calls with a known value, for the rows that have a callback
// (a DigiScope host with none answers too: choice_digiscope_shared, choice_digiscope_site).
static const struct { const char *host, *callback; } PROPERTY_ROWS[] = {
    { "digiscope.me", NULL }, { "digiscope.me", "https://digiscope.me/api/digiid-probe/callback" },
    { "api.digiscope.me", NULL }, { "api.digiscope.me", "https://api.digiscope.me/api/auth/digiid/callback" },
    { "example.com", "https://example.com/callback" },
};

// THE CHOICE refuses a digiScopeIdentity that is neither SHARED (1) nor SITE (2): 0, which a zeroed or unset property
// holds, and every other value. On a DigiScope host, and off the list, where the answer would not use it; under either
// root; the answer left all zero. The same calls with a known value answer, so each refusal is the property's alone.
static void choice_unknown_scope_refused(void)
{
    static const struct { BRDigiIdScopeIdentity scope; const char *name; } BAD[] = {
        { BR_DIGIID_SCOPE_NONE, "0 (BR_DIGIID_SCOPE_NONE)" }, { (BRDigiIdScopeIdentity)3, "3" },
        { (BRDigiIdScopeIdentity)-1, "(BRDigiIdScopeIdentity)-1" },
    };
    BRDigiIdChoice c;
    size_t i, r, k;

    for (k = 0; k < N(PROPERTY_ROWS); k++) {
        const char *host = PROPERTY_ROWS[k].host, *callback = PROPERTY_ROWS[k].callback;

        for (r = 0; r < N(ROOTS); r++) {
            for (i = 0; i < N(BAD); i++) {
                BRDigiIdStatus s = hChooseStr(&c, host, callback, BAD[i].scope, ROOTS[r]);

                ck(s == BR_DIGIID_REFUSED && isNoChoice(&c), "%s, callback %s, %s, digiScopeIdentity %s: refused, the answer all zero (%s)", host,
                   callback ? callback : "none", rootName(ROOTS[r]), BAD[i].name, st(s));
            }
            for (i = 0; callback && i < N(SCOPES); i++)
                ck(hChooseStr(&c, host, callback, SCOPES[i], ROOTS[r]) == BR_DIGIID_OK && c.kind != BR_DIGIID_IDENTITY_NONE,
                   "%s, callback %s, %s, digiScopeIdentity %s: the same call answers", host, callback ? callback : "none", rootName(ROOTS[r]),
                   scopeName(SCOPES[i]));
        }
    }
}

// THE CHOICE refuses a root that is neither DIGIBYTE (1) nor STANDARD (2), as every call that takes a root does (THE
// ROOT, unknown_root_refused): 0 and every other value, also where the answer would not name a root (a DigiScope host
// with digiScopeIdentity SHARED), the answer left all zero. The same calls with a known root answer.
static void choice_unknown_root_refused(void)
{
    static const struct { BRDigiIdRoot root; const char *name; } BAD[] = {
        { BR_DIGIID_ROOT_NONE, "0 (BR_DIGIID_ROOT_NONE)" }, { (BRDigiIdRoot)3, "3" }, { (BRDigiIdRoot)-1, "(BRDigiIdRoot)-1" },
    };
    BRDigiIdChoice c;
    size_t i, s, k;

    for (k = 0; k < N(PROPERTY_ROWS); k++) {
        const char *host = PROPERTY_ROWS[k].host, *callback = PROPERTY_ROWS[k].callback;

        for (s = 0; s < N(SCOPES); s++) {
            for (i = 0; i < N(BAD); i++) {
                BRDigiIdStatus status = hChooseStr(&c, host, callback, SCOPES[s], BAD[i].root);

                ck(status == BR_DIGIID_REFUSED && isNoChoice(&c), "%s, callback %s, %s, root %s: refused, the answer all zero (%s)", host,
                   callback ? callback : "none", scopeName(SCOPES[s]), BAD[i].name, st(status));
            }
            for (i = 0; callback && i < N(ROOTS); i++)
                ck(hChooseStr(&c, host, callback, SCOPES[s], ROOTS[i]) == BR_DIGIID_OK && c.kind != BR_DIGIID_IDENTITY_NONE,
                   "%s, callback %s, %s, root %s: the same call answers", host, callback ? callback : "none", scopeName(SCOPES[s]),
                   rootName(ROOTS[i]));
        }
    }
}

// The address THE CHOICE's answer gives ABANDON at index 0, as a caller derives it: for SITE, BRDigiIdAddress under
// the answer's root with the answer's URI; for SHARED, BRDigiIdAddress with no URI under the wallet's own root, the
// answer naming none. Empty when the choice or the address is refused.
static void chosenAddress(char addr[BR_DIGIID_ADDRESS_CAPACITY], const uint8_t *seed, const char *host, const char *callback,
                          BRDigiIdScopeIdentity scope, BRDigiIdRoot root)
{
    BRDigiIdChoice c;

    addr[0] = '\0';
    if (hChooseStr(&c, host, callback, scope, root) != BR_DIGIID_OK) return;
    if (c.kind == BR_DIGIID_IDENTITY_SITE)
        BRDigiIdAddress(addr, BR_DIGIID_ADDRESS_CAPACITY, seed, 64, c.root, 1, c.uri, c.uriLen, 0, WORK, sizeof(WORK));
    else if (c.kind == BR_DIGIID_IDENTITY_SHARED)
        BRDigiIdAddress(addr, BR_DIGIID_ADDRESS_CAPACITY, seed, 64, root, 0, NULL, 0, 0, WORK, sizeof(WORK));
}

// The answer a login signs msg with after THE CHOICE, as a caller signs it: BRDigiIdSignSite under the answer's root
// over the answer's URI, or BRDigiIdSignShared (P2PKH). Empty when the choice or the signing is refused.
static void chosenLogin(char answer[BR_DIGIID_ANSWER_CAPACITY], const uint8_t *seed, const char *host, const char *callback,
                        BRDigiIdScopeIdentity scope, BRDigiIdRoot root, const char *msg)
{
    BRDigiIdChoice c;

    answer[0] = '\0';
    if (hChooseStr(&c, host, callback, scope, root) != BR_DIGIID_OK) return;
    if (c.kind == BR_DIGIID_IDENTITY_SITE)
        BRDigiIdSignSite(answer, BR_DIGIID_ANSWER_CAPACITY, seed, 64, c.root, (const uint8_t *)msg, strlen(msg), c.uri, c.uriLen, 0, WORK,
                         sizeof(WORK));
    else if (c.kind == BR_DIGIID_IDENTITY_SHARED)
        BRDigiIdSignShared(answer, BR_DIGIID_ANSWER_CAPACITY, seed, 64, (const uint8_t *)msg, strlen(msg), 0, WORK, sizeof(WORK));
}

// END TO END: THE CHOICE's answer, fed to the calls that derive and sign, for ABANDON at index 0.
//   A wallet restored as from Coinomi (digiScopeIdentity SITE, root STANDARD) signs DigiScope with
//   DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri, the address Coinomi signed DigiScope's login with (measured: the design's
//   table), and its login is the independent model's answer byte for byte. It is that one address on both hosts and
//   for every callback: the two probe callbacks Coinomi was also measured at have identities of their own
//   (DSSfzAos..., DA3TaNPP...), which no DigiScope host is signed with.
//   SITE under the DigiByte root is the bridge's address for the login URL.
//   digiScopeIdentity SHARED is the shared address under either root, the one BRDigiIdSignShared signs with, and the
//   login is the bridge's answer byte for byte: what every wallet signs DigiScope with today.
//   A host off the list signs as it does today under the DigiByte root (the bridge's answers), and under the standard
//   root with this file's own BIP32 key from "Bitcoin seed".
static void choice_end_to_end(void)
{
    static const struct { const char *host, *callback, *itsOwn; } OTHER[] = {
        { "api.digiscope.me", "https://api.digiscope.me/api/digiid-probe/callback", "DSSfzAosbs1mBJE4rZPtZHGuwjHorJDNau" },
        { "digiscope.me", "https://digiscope.me/api/digiid-probe/callback", "DA3TaNPPTHwStZp3K98sGzk7aaQ8sh7sBo" },
        { "digiscope.me", NULL, NULL }, { "api.digiscope.me", NULL, NULL },   // no request: the Hub's own login
        { "digiscope.me", "https://example.com/callback", NULL },
    };
    const size_t n = strlen(REQUEST_CALLBACK);
    uint8_t seed[64];
    char login[64], addr[BR_DIGIID_ADDRESS_CAPACITY], own[BR_DIGIID_ADDRESS_CAPACITY], answer[BR_DIGIID_ANSWER_CAPACITY];
    BRDigiIdChoice c;
    BRKey key;
    size_t i, r, s;

    seedOf(seed, ABANDON);
    strcpy(login, DIGISCOPE_LOGIN_URI);   // LOGIN's callback URL, in the request's own storage

    chosenAddress(addr, seed, "api.digiscope.me", login, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD);
    ck(strcmp(addr, DIGISCOPE_LOGIN_STANDARD_ADDRESS) == 0,
       "api.digiscope.me, SITE, STANDARD, the login request: the address Coinomi signed DigiScope's login with (%s)", addr);
    chosenLogin(answer, seed, "api.digiscope.me", login, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD, LOGIN);
    ck(strcmp(answer, LOGIN_ANSWER_STANDARD_SITE) == 0, "and LOGIN signed after that choice is the model's answer, byte for byte");
    for (i = 0; i < N(OTHER); i++) {
        chosenAddress(addr, seed, OTHER[i].host, OTHER[i].callback, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_STANDARD);
        ck(strcmp(addr, DIGISCOPE_LOGIN_STANDARD_ADDRESS) == 0, "%s, SITE, STANDARD, callback %s: that same address (%s)", OTHER[i].host,
           OTHER[i].callback ? OTHER[i].callback : "none", addr);
        if (! OTHER[i].itsOwn) continue;
        ck(BRDigiIdAddress(own, sizeof(own), seed, 64, BR_DIGIID_ROOT_STANDARD, 1, OTHER[i].callback, strlen(OTHER[i].callback), 0, WORK,
                           sizeof(WORK)) == BR_DIGIID_OK && strcmp(own, OTHER[i].itsOwn) == 0 && strcmp(own, addr) != 0,
           "and not %s, which Coinomi showed for that callback itself", own);
    }

    chosenAddress(addr, seed, "api.digiscope.me", login, BR_DIGIID_SCOPE_SITE, BR_DIGIID_ROOT_DIGIBYTE);
    ck(strcmp(addr, DIGISCOPE_LOGIN_DIGIBYTE_ADDRESS) == 0, "api.digiscope.me, SITE, DIGIBYTE: the bridge's address for the login URL (%s)", addr);

    for (r = 0; r < N(ROOTS); r++) {
        for (i = 0; i < N(LISTED_HOSTS); i++) {
            chosenAddress(addr, seed, LISTED_HOSTS[i], login, BR_DIGIID_SCOPE_SHARED, ROOTS[r]);
            ck(strcmp(addr, SHARED_ADDRESS) == 0, "%s, SHARED, %s: the shared address, the bridge's (%s)", LISTED_HOSTS[i], rootName(ROOTS[r]), addr);
            chosenLogin(answer, seed, LISTED_HOSTS[i], login, BR_DIGIID_SCOPE_SHARED, ROOTS[r], LOGIN);
            ck(strcmp(answer, LOGIN_ANSWER_P2PKH) == 0 && strncmp(answer, addr, strlen(addr)) == 0 && answer[strlen(addr)] == '|',
               "and LOGIN signed after that choice is the bridge's answer, byte for byte, signed with that address");
        }
    }
    ck(hChooseStr(&c, "api.digiscope.me", login, BR_DIGIID_SCOPE_SHARED, BR_DIGIID_ROOT_STANDARD) == BR_DIGIID_OK && c.root == BR_DIGIID_ROOT_NONE,
       "the SHARED answer names no root");
    memset(addr, 'X', sizeof(addr));
    ck(BRDigiIdAddress(addr, sizeof(addr), seed, 64, c.root, 0, NULL, 0, 0, WORK, sizeof(WORK)) == BR_DIGIID_REFUSED && addr[0] == '\0',
       "so BRDigiIdAddress given the answer's root refuses: the shared address is asked under the wallet's own root");

    for (s = 0; s < N(SCOPES); s++) {
        uint32_t idx[4], path[5];

        chosenAddress(addr, seed, "example.com", REQUEST_CALLBACK, SCOPES[s], BR_DIGIID_ROOT_DIGIBYTE);
        chosenLogin(answer, seed, "example.com", REQUEST_CALLBACK, SCOPES[s], BR_DIGIID_ROOT_DIGIBYTE, REQUEST);
        ck(strcmp(addr, "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68") == 0 && strcmp(answer, REQUEST_SITE_ANSWER) == 0,
           "example.com, %s, DIGIBYTE: the bridge's per-site address and its answer for the request, byte for byte (%s)", scopeName(SCOPES[s]), addr);

        memset(&key, 0, sizeof(key));
        ck(hChooseStr(&c, "example.com", REQUEST_CALLBACK, SCOPES[s], BR_DIGIID_ROOT_STANDARD) == BR_DIGIID_OK && c.kind == BR_DIGIID_IDENTITY_SITE &&
           BRDigiIdSiteKey(&key, seed, 64, c.root, c.uri, c.uriLen, 0, WORK, sizeof(WORK)) == BR_DIGIID_OK,
           "example.com, %s, STANDARD: the answer derives a key", scopeName(SCOPES[s]));
        slip13_indexes(idx, REQUEST_CALLBACK, n, 0);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        ck(sameSecret(&key, ownSecret(seed, 64, "Bitcoin seed", path, 5)),
           "which is m/13'/A'/B'/C'/D' of the request's callback from \"Bitcoin seed\" (this file's BIP32)");
        BRKeyClean(&key);
        chosenAddress(addr, seed, "example.com", REQUEST_CALLBACK, SCOPES[s], BR_DIGIID_ROOT_STANDARD);
        ck(addr[0] != '\0' && strcmp(addr, "DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68") != 0, "another identity than the DigiByte root gives that site (%s)", addr);
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
        h = BRDigiIdSiteKey(&hk, seed, seedLen, BR_DIGIID_ROOT_DIGIBYTE, (const char *)uri, uriLen, index, WORK, sizeof(WORK)) == BR_DIGIID_OK;
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
            if (! sameSecret(&hk, ownSecret(seed, seedLen, "DigiByte seed", path, 5))) ownDiffer++;
        }
        if (n % 10 == 0) {
            static const uint32_t sharedPath[3] = { 0 | HARD, 0, 0 };
            BRDigiIdSharedKey(&hk, seed, seedLen);
            if (! sameSecret(&hk, ownSecret(seed, seedLen, "DigiByte seed", sharedPath, 3))) ownDiffer++;
        }
        BRKeyClean(&hk); BRKeyClean(&bk);
    }
    ck(differ == 0, "1500 seeds, URIs and indexes: every per-site key (and refusal) is the bridge's (%ld differ)", differ);
    ck(ownDiffer == 0, "every tenth per-site and shared key is this file's BIP32's (%ld differ)", ownDiffer);
    ck(refused > 150 && refused < 1500 - 150, "the stream reaches keys and refusals (%ld refused)", refused);
}

// sweep_keys's stream under BR_DIGIID_ROOT_STANDARD, which the bridge has no answer for: every key is held to this
// file's BIP32 from "Bitcoin seed" and the bridge's A..D, over seeds of 16 to 80 bytes and indexes up to INT32_MAX;
// a negative index, and only a negative one, is refused with the key wiped; and every tenth key's address is
// BRDigiIdAddress's, the address BRDigiIdSignSite signs a message with. (Every other STANDARD check is at index 0
// with a 64-byte seed, where an index or seed length the STANDARD branch dropped would not show.)
static void sweep_keys_standard(void)
{
    uint8_t seed[80], uri[200], msg[200];
    char addr[BR_DIGIID_ADDRESS_CAPACITY], keyAddr[75], answer[BR_DIGIID_ANSWER_CAPACITY];
    long ownDiffer = 0, wrong = 0, siteDiffer = 0, n, refused = 0, nonzeroIndex = 0, otherLength = 0, signedAt = 0;

    for (n = 0; n < 1500; n++) {
        size_t seedLen = below(4) ? 64 : (size_t)(16 + below(65)), uriLen, msgLen, i;
        int32_t index = below(5) == 0 ? -(int32_t)below(3) - 1 : (int32_t)(rndIndex32() & 0x7fffffff);
        uint32_t idx[4], path[5];
        BRDigiIdStatus s;
        BRKey key;

        for (i = 0; i < seedLen; i++) seed[i] = (uint8_t)rnd();
        uriLen = rndBytes(uri, sizeof(uri));
        memset(&key, 0xa5, sizeof(key));
        s = BRDigiIdSiteKey(&key, seed, seedLen, BR_DIGIID_ROOT_STANDARD, (const char *)uri, uriLen, index, WORK, sizeof(WORK));
        if (index < 0) {
            refused++;
            if ((s != BR_DIGIID_REFUSED || ! allZero(&key, sizeof(key))) && wrong++ < 4)
                ck(0, "seed of %zu bytes, %zu-byte URI, index %d, STANDARD: %s, not refused with the key wiped", seedLen, uriLen, (int)index, st(s));
            continue;
        }
        if (s != BR_DIGIID_OK) {
            if (wrong++ < 4) ck(0, "seed of %zu bytes, %zu-byte URI, index %d, STANDARD: %s", seedLen, uriLen, (int)index, st(s));
            continue;
        }
        if (index != 0) nonzeroIndex++;
        if (seedLen != 64) otherLength++;
        slip13_indexes(idx, (const char *)uri, uriLen, (uint32_t)index);
        path[0] = 13 | HARD; path[1] = idx[0] | HARD; path[2] = idx[1] | HARD; path[3] = idx[2] | HARD; path[4] = idx[3] | HARD;
        if (! sameSecret(&key, ownSecret(seed, seedLen, "Bitcoin seed", path, 5)) && ownDiffer++ < 4)
            ck(0, "seed of %zu bytes, %zu-byte URI, index %d, STANDARD: the key is not this file's BIP32's", seedLen, uriLen, (int)index);
        if (n % 10 == 0) {
            int ok;

            memset(keyAddr, 0, sizeof(keyAddr));
            BRKeyAddress(&key, keyAddr, sizeof(keyAddr));
            msgLen = rndBytes(msg, sizeof(msg));
            ok = BRDigiIdAddress(addr, sizeof(addr), seed, seedLen, BR_DIGIID_ROOT_STANDARD, 1, (const char *)uri, uriLen, index, WORK,
                                 sizeof(WORK)) == BR_DIGIID_OK && strcmp(addr, keyAddr) == 0;
            ok = ok && BRDigiIdSignSite(answer, sizeof(answer), seed, seedLen, BR_DIGIID_ROOT_STANDARD, msg, msgLen, (const char *)uri, uriLen,
                                        index, WORK, sizeof(WORK)) == BR_DIGIID_OK &&
                 strncmp(answer, addr, strlen(addr)) == 0 && answer[strlen(addr)] == '|' && signsBytes(answer, msg, msgLen);
            if (! ok && siteDiffer++ < 4)
                ck(0, "seed of %zu bytes, %zu-byte URI, index %d, STANDARD: the address or the login is not the key's", seedLen, uriLen, (int)index);
            signedAt++;
        }
        BRKeyClean(&key);
    }
    ck(ownDiffer == 0, "1500 seeds, URIs and indexes, STANDARD: every key is this file's BIP32's from \"Bitcoin seed\" (%ld differ)", ownDiffer);
    ck(wrong == 0, "a negative index, and only a negative one, is refused, the key wiped (%ld wrong)", wrong);
    ck(siteDiffer == 0, "every tenth key's address is BRDigiIdAddress's, and BRDigiIdSignSite signs with it (%ld of %ld differ)", siteDiffer,
       signedAt);
    ck(refused > 150 && nonzeroIndex > 1000 && otherLength > 200,
       "the stream reaches refusals (%ld), indexes but 0 (%ld) and seeds of other lengths than 64 (%ld)", refused, nonzeroIndex, otherLength);
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
        h = BRDigiIdSignSite(hs, sizeof(hs), seedArg, 64, BR_DIGIID_ROOT_DIGIBYTE, msg, msgLen, (const char *)uri, uriLen, index, WORK,
                             sizeof(WORK)) == BR_DIGIID_OK;
        b = b_signIdentityMessage(bs, seedArg, msg, msgLen, (const char *)uri, uriLen, index);
        if (h != b || (h && strcmp(hs, bs) != 0)) { if (differ++ < 4) ck(0, "step %ld: the per-site answer is not the bridge's", n); }
        answers += h;
        h = BRDigiIdAddress(ha, sizeof(ha), seedArg, 64, BR_DIGIID_ROOT_DIGIBYTE, hasUri, (const char *)uri, uriLen, index, WORK,
                            sizeof(WORK)) == BR_DIGIID_OK;
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
    { "site_key_derivation", site_key_derivation }, { "site_key_digibyte_coinomi_uris", site_key_digibyte_coinomi_uris },
    { "site_key_standard_coinomi_uris", site_key_standard_coinomi_uris }, { "site_key_standard_bitid_vector", site_key_standard_bitid_vector },
    { "site_key_standard_login_and_indexes", site_key_standard_login_and_indexes },
    { "shared_key_derivation", shared_key_derivation }, { "shared_address_both_roots", shared_address_both_roots },
    { "unknown_root_refused", unknown_root_refused }, { "address_root_hasuri_swapped", address_root_hasuri_swapped },
    { "message_prefix", message_prefix }, { "message_digest", message_digest }, { "utf8_bytes", utf8_bytes },
    { "uri_bytes", uri_bytes }, { "uri_identity", uri_identity },
    { "sign_shared_login", sign_shared_login }, { "sign_site_request", sign_site_request },
    { "request_golden_inputs", request_golden_inputs }, { "address_rules", address_rules },
    { "sign_with_key_wipes", sign_with_key_wipes }, { "address_with_key_wipes", address_with_key_wipes }, { "long_messages", long_messages }, { "edges", edges }, { "buffer_edges", buffer_edges },
    { "digiscope_list_hosts", digiscope_list_hosts }, { "digiscope_list_subdomains", digiscope_list_subdomains },
    { "digiscope_list_lookalikes", digiscope_list_lookalikes }, { "digiscope_list_letter_case", digiscope_list_letter_case },
    { "digiscope_list_edges", digiscope_list_edges },
    { "choice_digiscope_shared", choice_digiscope_shared }, { "choice_digiscope_site", choice_digiscope_site },
    { "choice_other_host", choice_other_host }, { "choice_refused", choice_refused },
    { "choice_unknown_scope_refused", choice_unknown_scope_refused }, { "choice_unknown_root_refused", choice_unknown_root_refused },
    { "choice_end_to_end", choice_end_to_end },
    { "sweep_indexes", sweep_indexes }, { "sweep_keys", sweep_keys }, { "sweep_keys_standard", sweep_keys_standard },
    { "sweep_sign", sweep_sign }, { "sweep_utf8", sweep_utf8 },
    { "sweep_uri_bytes", sweep_uri_bytes },
};

int main(int argc, char **argv)
{
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 2) { fprintf(stderr, "usage: %s <case>|list|hosts\n", argv[0]); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        for (i = 0; i < N(CASES); i++) printf("%s\n", CASES[i].name);
        return 0;
    }
    if (strcmp(argv[1], "hosts") == 0) {   // THE DIGISCOPE LIST as this file holds it, for run.sh's SOURCE GATE
        for (i = 0; i < N(LISTED_HOSTS); i++) printf("%s\n", LISTED_HOSTS[i]);
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
