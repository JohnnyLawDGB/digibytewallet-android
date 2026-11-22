#!/usr/bin/env python3
"""An independent model of the Digi-ID identities, for digiid_core_kat's MODEL leg (run.sh).

BIP39 phrase -> seed (PBKDF2) -> BIP32 master key under a chosen HMAC key -> a private path -> P2PKH
address, and a signed message in the form the wallet answers a Digi-ID request with (RFC 6979 nonce,
low s, compact recoverable signature, base64). The per-site path is SLIP-0013's m/13'/A'/B'/C'/D'; the
shared identity is m/0'/0/0.

Python's standard library only, and of hashlib only SHA-256, SHA-512 and PBKDF2, which every build has
(RIPEMD-160 is written out below: an OpenSSL without its legacy provider gives hashlib none). It shares no code
with the core (BRDigiId.h, BRBIP32Sequence.c, BRKey.c, libsecp256k1), with the Android bridge (slip13.c,
jni_wallet.c, jni_wallet_sign.c) or with digiid_core_kat_main.c: the elliptic-curve arithmetic, BIP32,
Base58Check and the signature below are written here. It reads no file and holds no expected answer.
Whoever runs it compares what it prints with literals kept elsewhere.

The two master-key roots, by the names the core gives them (BRDigiIdRoot):
    DIGIBYTE   HMAC key "DigiByte seed": every per-site identity this wallet has given so far.
    STANDARD   HMAC key "Bitcoin seed": BIP32's own, which Coinomi's per-site identities come from.
The shared identity is always derived under "DigiByte seed"; no root moves it.

Usage:
    python3 -I slip13_model.py [--hmac-key ROOT=KEY ...]

--hmac-key replaces the HMAC key of a root (DIGIBYTE or STANDARD, or SHARED for the shared identity), and
exists for the comparison arms: a model whose STANDARD root derives with "DigiByte seed" must be refused
by whoever compares its lines.

Output, one answer per line, fields separated by one space, the answer last:
    KAT <id> <answer>       an answer digiid_core_kat_main.c holds as a literal; run.sh's MODEL_ROWS says
                            where. The id starts with the root it was derived under (digibyte., standard.,
                            shared.).
    GOLDEN <line>           a whole line of bridge_golden.txt, as the bridge of 4.1.5 printed it (all of
                            them DIGIBYTE or shared: that bridge takes no root).
"""
import base64
import hashlib
import hmac
import struct
import sys
import unicodedata

# ---- secp256k1 ------------------------------------------------------------------------------------
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0:
        return None
    if a == b:
        slope = (3 * a[0] * a[0] * pow(2 * a[1], -1, P)) % P
    else:
        slope = ((b[1] - a[1]) * pow(b[0] - a[0], -1, P)) % P
    x = (slope * slope - a[0] - b[0]) % P
    return (x, (slope * (a[0] - x) - a[1]) % P)


def point_mul(k, point=G):
    result = None
    while k:
        if k & 1:
            result = point_add(result, point)
        point = point_add(point, point)
        k >>= 1
    return result


def public_key(secret):
    x, y = point_mul(secret)
    return bytes([2 + (y & 1)]) + x.to_bytes(32, 'big')


# ---- BIP39, BIP32, SLIP-0013 ----------------------------------------------------------------------
HARD = 0x80000000


def seed_of(phrase, passphrase=''):
    return hashlib.pbkdf2_hmac('sha512', unicodedata.normalize('NFKD', phrase).encode(),
                               ('mnemonic' + unicodedata.normalize('NFKD', passphrase)).encode(), 2048)


def derive(seed, hmac_key, path):
    block = hmac.new(hmac_key, seed, hashlib.sha512).digest()
    secret, chain = int.from_bytes(block[:32], 'big'), block[32:]
    for index in path:
        parent = b'\0' + secret.to_bytes(32, 'big') if index & HARD else public_key(secret)
        block = hmac.new(chain, parent + struct.pack('>I', index), hashlib.sha512).digest()
        secret, chain = (int.from_bytes(block[:32], 'big') + secret) % N, block[32:]
    return secret


def slip13_path(uri_bytes, index=0):
    digest = hashlib.sha256(struct.pack('<I', index) + uri_bytes).digest()
    return [13 | HARD] + [struct.unpack('<I', digest[i:i + 4])[0] | HARD for i in (0, 4, 8, 12)]


SHARED_PATH = [0 | HARD, 0, 0]

# ---- RIPEMD-160 (the 1996 specification's tables) -------------------------------------------------
_R1 = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
       3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12, 1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
       4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13]
_R2 = [5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12, 6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
       15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13, 8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
       12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11]
_S1 = [11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8, 7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
       11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5, 11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
       9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6]
_S2 = [8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6, 9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
       9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5, 15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
       8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11]
_K1 = [0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xA953FD4E]
_K2 = [0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000]
_M32 = 0xFFFFFFFF


def _rol(x, n):
    return ((x << n) | (x >> (32 - n))) & _M32


def _f(j, x, y, z):
    if j < 16:
        return x ^ y ^ z
    if j < 32:
        return (x & y) | (~x & _M32 & z)
    if j < 48:
        return (x | (~y & _M32)) ^ z
    if j < 64:
        return (x & z) | (y & ~z & _M32)
    return x ^ (y | (~z & _M32))


def ripemd160(data):
    h = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0]
    padded = data + b'\x80' + b'\0' * ((55 - len(data)) % 64) + struct.pack('<Q', 8 * len(data))
    for offset in range(0, len(padded), 64):
        x = struct.unpack('<16I', padded[offset:offset + 64])
        a1, b1, c1, d1, e1 = h
        a2, b2, c2, d2, e2 = h
        for j in range(80):
            t = (_rol((a1 + _f(j, b1, c1, d1) + x[_R1[j]] + _K1[j // 16]) & _M32, _S1[j]) + e1) & _M32
            a1, e1, d1, c1, b1 = e1, d1, _rol(c1, 10), b1, t
            t = (_rol((a2 + _f(79 - j, b2, c2, d2) + x[_R2[j]] + _K2[j // 16]) & _M32, _S2[j]) + e2) & _M32
            a2, e2, d2, c2, b2 = e2, d2, _rol(c2, 10), b2, t
        t = (h[1] + c1 + d2) & _M32
        h = [t, (h[2] + d1 + e2) & _M32, (h[3] + e1 + a2) & _M32, (h[4] + a1 + b2) & _M32, (h[0] + b1 + c2) & _M32]
    return struct.pack('<5I', *h)


# ---- addresses ------------------------------------------------------------------------------------
BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
DGB_P2PKH = 0x1e


def base58check(payload):
    data = payload + hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4]
    number, text = int.from_bytes(data, 'big'), ''
    while number:
        number, digit = divmod(number, 58)
        text = BASE58[digit] + text
    return '1' * (len(data) - len(data.lstrip(b'\0'))) + text


def address(secret, version=DGB_P2PKH):
    hash160 = ripemd160(hashlib.sha256(public_key(secret)).digest())
    return base58check(bytes([version]) + hash160)


# ---- the signed answer ----------------------------------------------------------------------------
def rfc6979_nonce(secret, digest):
    """RFC 6979 section 3.2 with HMAC-SHA256, for a 32-byte digest already below the group order's size."""
    key_and_digest = secret.to_bytes(32, 'big') + digest
    v, k = b'\x01' * 32, b'\x00' * 32
    k = hmac.new(k, v + b'\x00' + key_and_digest, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b'\x01' + key_and_digest, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        nonce = int.from_bytes(v, 'big')
        if 0 < nonce < N:
            return nonce
        k = hmac.new(k, v + b'\x00', hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def compact_size(n):
    if n < 0xfd:
        return bytes([n])
    if n <= 0xffff:
        return b'\xfd' + struct.pack('<H', n)
    return b'\xfe' + struct.pack('<I', n)


def message_digest(message):
    prefix = b'DigiByte Signed Message:\n'
    data = compact_size(len(prefix)) + prefix + compact_size(len(message)) + message
    return hashlib.sha256(hashlib.sha256(data).digest()).digest()


def signed_answer(secret, message):
    """'<address>|<base64 of the 65-byte compact signature>', the key compressed."""
    digest = message_digest(message)
    nonce = rfc6979_nonce(secret, digest)
    rx, ry = point_mul(nonce)
    r = rx % N
    s = (pow(nonce, -1, N) * (int.from_bytes(digest, 'big') + r * secret)) % N
    recovery = (ry & 1) | (2 if rx >= N else 0)
    if s > N // 2:
        s, recovery = N - s, recovery ^ 1
    signature = bytes([27 + 4 + recovery]) + r.to_bytes(32, 'big') + s.to_bytes(32, 'big')
    return address(secret) + '|' + base64.b64encode(signature).decode()


# ---- the inputs -----------------------------------------------------------------------------------
ABANDON = ' '.join(['abandon'] * 11 + ['about'])
INHALE = ('inhale praise target steak garlic cricket paper better evil almost sadness crawl city banner amused '
          'fringe fox insect roast aunt prefer hollow basic ladder')
BITID_CALLBACK = 'http://bitid.bitcoin.blue/callback'

# The three callback URLs Coinomi was measured signing in to (docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md).
COINOMI_URIS = ['https://api.digiscope.me/api/digiid-probe/callback',
                'https://api.digiscope.me/api/auth/digiid/callback',
                'https://digiscope.me/api/digiid-probe/callback']
LOGIN_CALLBACK = COINOMI_URIS[1]
LOGIN = 'digiid://api.digiscope.me/api/auth/digiid/callback?x=00112233445566778899aabbccddeeff'
REQUEST = 'digiid://example.com/callback?x=abc123'
REQUEST_CALLBACK = 'https://example.com/callback'
INT32_MAX = 2147483647

# The callback URLs `digiid_core_bridge print` asks the bridge for, in its order.
PRINTED_URIS = [REQUEST_CALLBACK, 'https://example.com:8443/callback', 'https://example.com:443/callback',
                'https://shop.example.com/digiid/callback/v1', 'http://example.com/auth', 'http://localhost:8080/auth',
                'https://api.digiscope.me/api/digiid/callback', 'https://example.com/cb', 'https://example.com/',
                'https://example.com', 'https://sub.domain-with-dash.co.uk/digiid/login.php',
                'http://192.168.1.10:3000/digiid', 'https://Example.COM/cb', 'https://site.example/login'] + COINOMI_URIS

# A callback URL with U+1F600 in its path: the bytes Android's runtime hands the bridge (the character's
# four bytes), and the JNI specification's (each surrogate half as three), which Android does not write.
# Then U+0000 and a lone U+D800, as that runtime writes them.
EMOJI_URI = b'https://example.com/' + '\U0001F600'.encode()
EMOJI_URI_SPEC = b'https://example.com/' + b'\xed\xa0\xbd\xed\xb8\x80'
NUL_URI = b'https://example.com/a' + b'\xc0\x80' + b'b'
LONE_URI = b'https://example.com/a' + b'\xed\xa0\x80' + b'b'


def main(argv):
    keys = {'DIGIBYTE': b'DigiByte seed', 'STANDARD': b'Bitcoin seed', 'SHARED': b'DigiByte seed'}
    args = argv[1:]
    while args:
        if args[0] != '--hmac-key' or len(args) < 2 or '=' not in args[1]:
            sys.stderr.write('usage: slip13_model.py [--hmac-key ROOT=KEY ...]\n')
            return 2
        root, key = args[1].split('=', 1)
        if root not in keys:
            sys.stderr.write('unknown root %s (DIGIBYTE, STANDARD, SHARED)\n' % root)
            return 2
        keys[root] = key.encode()
        args = args[2:]

    abandon, inhale = seed_of(ABANDON), seed_of(INHALE)

    def site(root, uri, index=0, seed=abandon):
        return derive(seed, keys[root], slip13_path(uri if isinstance(uri, bytes) else uri.encode(), index))

    def shared(seed=abandon):
        return derive(seed, keys['SHARED'], SHARED_PATH)

    out = []

    def kat(name, answer):
        out.append('KAT %s %s' % (name, answer))

    def golden(line):
        out.append('GOLDEN ' + line)

    # ---- what digiid_core_kat_main.c holds --------------------------------------------------------
    for i, uri in enumerate(COINOMI_URIS):
        kat('standard.coinomi.%d' % i, address(site('STANDARD', uri)))
        kat('digibyte.coinomi.%d' % i, address(site('DIGIBYTE', uri)))
    for index in (1, 2, INT32_MAX):
        kat('standard.login.index.%d' % index, address(site('STANDARD', LOGIN_CALLBACK, index)))
    kat('standard.login.answer', signed_answer(site('STANDARD', LOGIN_CALLBACK), LOGIN.encode()))
    kat('digibyte.login.answer', signed_answer(site('DIGIBYTE', LOGIN_CALLBACK), LOGIN.encode()))
    kat('standard.bitid', address(site('STANDARD', BITID_CALLBACK, 0, inhale), 0x00))
    kat('digibyte.bitid', address(site('DIGIBYTE', BITID_CALLBACK, 0, inhale)))
    for i, uri in enumerate(PRINTED_URIS[:13]):
        kat('digibyte.request.%d' % i, address(site('DIGIBYTE', uri)))
    kat('digibyte.request.index.1', address(site('DIGIBYTE', REQUEST_CALLBACK, 1)))
    kat('digibyte.request.index.%d' % INT32_MAX, address(site('DIGIBYTE', REQUEST_CALLBACK, INT32_MAX)))
    kat('digibyte.empty-uri', address(site('DIGIBYTE', b'')))
    kat('digibyte.emoji-uri', address(site('DIGIBYTE', EMOJI_URI)))
    kat('digibyte.emoji-uri.spec-bytes', address(site('DIGIBYTE', EMOJI_URI_SPEC)))
    kat('digibyte.nul-uri', address(site('DIGIBYTE', NUL_URI)))
    kat('digibyte.lone-surrogate-uri', address(site('DIGIBYTE', LONE_URI)))
    kat('digibyte.request.answer', signed_answer(site('DIGIBYTE', REQUEST_CALLBACK), REQUEST.encode()))
    kat('digibyte.emoji-uri.answer', signed_answer(site('DIGIBYTE', EMOJI_URI), REQUEST.encode()))
    kat('digibyte.emoji-message.answer',
        signed_answer(site('DIGIBYTE', 'https://site.example/login'), 'gm \U0001F600 !'.encode()))
    kat('shared.address', address(shared()))
    kat('shared.login.answer', signed_answer(shared(), LOGIN.encode()))
    kat('shared.empty.answer', signed_answer(shared(), b''))
    kat('shared.request.answer', signed_answer(shared(), REQUEST.encode()))

    # ---- what the bridge printed (bridge_golden.txt), line for line -------------------------------
    for uri in PRINTED_URIS:
        golden('ADDRESS %s %s' % (uri, address(site('DIGIBYTE', uri))))
    golden('ADDRESS shared %s' % address(shared()))
    golden('ADDRESS example.com/callback index 1 %s' % address(site('DIGIBYTE', REQUEST_CALLBACK, 1)))
    golden('ADDRESS example.com/callback index %d %s' % (INT32_MAX, address(site('DIGIBYTE', REQUEST_CALLBACK, INT32_MAX))))
    golden('ADDRESS empty uri %s' % address(site('DIGIBYTE', b'')))
    golden('ADDRESS https://example.com/<U+1F600> %s' % address(site('DIGIBYTE', EMOJI_URI)))
    golden('ADDRESS https://example.com/a<U+0000>b %s' % address(site('DIGIBYTE', NUL_URI)))
    golden('ADDRESS https://example.com/a<U+D800>b %s' % address(site('DIGIBYTE', LONE_URI)))
    golden('SIGN site emoji uri %s' % signed_answer(site('DIGIBYTE', EMOJI_URI), REQUEST.encode()))
    golden('SIGN shared login format 0 %s' % signed_answer(shared(), LOGIN.encode()))
    golden('SIGN shared empty format 0 %s' % signed_answer(shared(), b''))
    golden('SIGN site request %s' % signed_answer(site('DIGIBYTE', REQUEST_CALLBACK), REQUEST.encode()))
    golden('SIGN site login %s' % signed_answer(site('DIGIBYTE', LOGIN_CALLBACK), LOGIN.encode()))
    golden('SIGN shared request %s' % signed_answer(shared(), REQUEST.encode()))
    golden('SIGN site emoji %s' % signed_answer(site('DIGIBYTE', 'https://site.example/login'), 'gm \U0001F600 !'.encode()))
    golden('ADDRESS bitid inhale %s' % address(site('DIGIBYTE', BITID_CALLBACK, 0, inhale)))

    sys.stdout.write('\n'.join(out) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
