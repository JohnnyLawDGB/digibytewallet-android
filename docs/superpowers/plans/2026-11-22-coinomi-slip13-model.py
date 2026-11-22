#!/usr/bin/env python3
"""Independent model: BIP39 seed -> BIP32 master (chosen HMAC key) -> SLIP-0013 m/13'/A'/B'/C'/D' -> DGB P2PKH.
Standard library only. Shares no code with BRDigiId.h or the bridge."""
import hashlib, hmac, struct, sys, unicodedata
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
def add(a, b):
    if a is None: return b
    if b is None: return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0: return None
    l = (3 * a[0] * a[0] * pow(2 * a[1], -1, P)) % P if a == b else ((b[1] - a[1]) * pow(b[0] - a[0], -1, P)) % P
    x = (l * l - a[0] - b[0]) % P
    return (x, (l * (a[0] - x) - a[1]) % P)
def mul(k, pt=G):
    r = None
    while k:
        if k & 1: r = add(r, pt)
        pt = add(pt, pt); k >>= 1
    return r
def pub(k):
    x, y = mul(k)
    return bytes([2 + (y & 1)]) + x.to_bytes(32, 'big')
B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
def b58check(payload):
    d = payload + hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4]
    n = int.from_bytes(d, 'big'); s = ''
    while n: n, r = divmod(n, 58); s = B58[r] + s
    return '1' * (len(d) - len(d.lstrip(b'\0'))) + s
def seed_of(phrase, passphrase=''):
    return hashlib.pbkdf2_hmac('sha512', unicodedata.normalize('NFKD', phrase).encode(),
                               ('mnemonic' + unicodedata.normalize('NFKD', passphrase)).encode(), 2048)
def derive(seed, hmac_key, path):
    I = hmac.new(hmac_key, seed, hashlib.sha512).digest()
    k, c = int.from_bytes(I[:32], 'big'), I[32:]
    for i in path:
        data = (b'\0' + k.to_bytes(32, 'big') if i & 0x80000000 else pub(k)) + struct.pack('>I', i)
        I = hmac.new(c, data, hashlib.sha512).digest()
        k, c = (int.from_bytes(I[:32], 'big') + k) % N, I[32:]
    return k
def slip13_path(uri, index=0):
    h = hashlib.sha256(struct.pack('<I', index) + uri.encode()).digest()
    H = 0x80000000
    return [13 | H] + [struct.unpack('<I', h[i:i + 4])[0] | H for i in (0, 4, 8, 12)]
def address(k, version=0x1e):
    h160 = hashlib.new('ripemd160', hashlib.sha256(pub(k)).digest()).digest()
    return b58check(bytes([version]) + h160)
ABANDON = ' '.join(['abandon'] * 11 + ['about'])
URIS = ['https://api.digiscope.me/api/digiid-probe/callback',
        'https://api.digiscope.me/api/auth/digiid/callback',
        'https://digiscope.me/api/digiid-probe/callback',
        'https://id.digistamp.co/auth/digiid/callback', 'https://example.com/callback']
if __name__ == '__main__':
    s = seed_of(ABANDON)
    print('seed', s.hex()[:16], '...')
    for name, key in (('STANDARD "Bitcoin seed"', b'Bitcoin seed'), ('DIGIBYTE "DigiByte seed"', b'DigiByte seed')):
        print(name)
        for u in URIS:
            print('  ', u, address(derive(s, key, slip13_path(u))))
    H = 0x80000000
    print('shared m/0\'/0/0 under DigiByte seed', address(derive(s, b'DigiByte seed', [H, 0, 0])))
    bit = seed_of('inhale praise target steak garlic cricket paper better evil almost sadness crawl city banner amused fringe fox insect roast aunt prefer hollow basic ladder')
    print('BitID published 1J34vj4wowwPYafbeibZGht3zy3qERoUM1 ->', address(derive(bit, b'Bitcoin seed', slip13_path('http://bitid.bitcoin.blue/callback')), 0x00))
