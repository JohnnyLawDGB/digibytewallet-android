#!/usr/bin/env python3
"""24-word recovery-phrase address vectors (BIP39-24W-VECTORS), from an implementation INDEPENDENT of
the wallet's C core and bridge.

Every address type the wallet derives -- the seven derivation profiles Recover funds probes, which
include the wallet's own BIP84, BIP86 (the DigiDollar path) and legacy m/0' trees and the BIP44 /
BIP49 profiles of other wallets -- for four published 24-word BIP39 test phrases, each without a
passphrase and with the passphrase "TREZOR". Mainnet, like every other committed address vector.

Nothing here comes from the wallet's own output:

  phrase -> seed     hashlib.pbkdf2_hmac (PBKDF2-HMAC-SHA512, 2048 rounds), and for the four TREZOR
                     seeds ALSO the value published in the BIP39 specification's test vectors
                     (vectors.json), written out below; the two must agree or nothing is printed.
  phrase checks      24 words, each on the published English list (the core repo's
                     vectors/derivation/bip39-english.wordlist, pinned here by its SHA-256), 256 bits
                     of entropy and the 8-bit checksum BIP39 specifies.
  seed -> addresses  the core repo's vectors/recovery-derivation/independent.py: BIP32, secp256k1,
                     Base58Check, Bech32/Bech32m and the BIP86 tweak in the Python standard library,
                     with its profile table written by hand. It shares no code with the C core. It is
                     loaded from the core checkout rather than copied, so there is one copy of it.

The host KAT bip39_24w_vectors_kat holds the core, the bridge and the wallet's own trees to
vectors.txt, and checks that vectors.txt is what this script prints.

Usage:   python3 -I generate.py <core checkout> > vectors.txt
"""

import hashlib
import importlib.util
import os
import sys

COUNT = 10          # first 10 receive (chain 0) and 10 change (chain 1) addresses
WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

ABANDON_ART = "abandon " * 23 + "art"
ZOO_VOTE = "zoo " * 23 + "vote"
LEGAL_TITLE = "legal winner thank year wave sausage worth useful " * 2 + \
              "legal winner thank year wave sausage worth title"
LETTER_BLESS = "letter advice cage absurd amount doctor acoustic avoid " * 2 + \
               "letter advice cage absurd amount doctor acoustic bless"

# (phrase, passphrase, the seed the BIP39 specification's vectors.json publishes, or None).
SEEDS = [
    (ABANDON_ART, "", None),
    (ABANDON_ART, "TREZOR",
     "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd30971"
     "70af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8"),
    (ZOO_VOTE, "", None),
    (ZOO_VOTE, "TREZOR",
     "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e16"
     "13912f0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad"),
    (LEGAL_TITLE, "", None),
    (LEGAL_TITLE, "TREZOR",
     "bc09fca1804f7e69da93c2f2028eb238c227f2e9dda30cd63699232578480a40"
     "21b146ad717fbb7e451ce9eb835f43620bf5c514db0f8add49f5d121449d3e87"),
    (LETTER_BLESS, "", None),
    (LETTER_BLESS, "TREZOR",
     "c0c519bd0e91a2ed54357d9d1ebef6f5af218a153624cf4f2da911a0ed8f7a09"
     "e2ef61af0aca007096df430022f7a2b6fb91661a9589097069720d015e4e982f"),
]


def fail(msg):
    sys.stderr.write("generate.py: %s\n" % msg)
    sys.exit(1)


def check_phrase(phrase, words):
    """A phrase a BIP39 wallet could produce at 24 words: 256 bits of entropy + 8 checksum bits."""
    parts = phrase.split(" ")
    if len(parts) != 24:
        fail("%d words, not 24: %s" % (len(parts), phrase))
    bits = 0
    for w in parts:
        if w not in words:
            fail("not on the BIP39 English list: %r" % w)
        bits = (bits << 11) | words[w]
    entropy = (bits >> 8).to_bytes(32, "big")
    if hashlib.sha256(entropy).digest()[0] != (bits & 0xFF):
        fail("BIP39 checksum does not match: %s" % phrase)


def main():
    if len(sys.argv) != 2:
        fail("usage: python3 -I generate.py <core checkout> > vectors.txt")
    core = sys.argv[1]
    raw = open(os.path.join(core, "vectors", "derivation", "bip39-english.wordlist"), "rb").read()
    if hashlib.sha256(raw).hexdigest() != WORDLIST_SHA256:
        fail("bip39-english.wordlist is not the published list (SHA-256 %s)" % hashlib.sha256(raw).hexdigest())
    listed = raw.decode("ascii").split()
    if len(listed) != 2048:
        fail("the word list holds %d words" % len(listed))
    words = {w: i for i, w in enumerate(listed)}

    path = os.path.join(core, "vectors", "recovery-derivation", "independent.py")
    sys.dont_write_bytecode = True   # loading it must leave nothing behind in the core checkout
    spec = importlib.util.spec_from_file_location("recovery_independent", path)
    ind = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ind)   # defines functions and tables only; its main() is not run
    if len(ind.PROFILES) != 7:
        fail("independent.py holds %d profiles" % len(ind.PROFILES))

    out = []
    out.append("# 24-word recovery-phrase address vectors (BIP39-24W-VECTORS), from an implementation INDEPENDENT of")
    out.append("# the wallet's core and bridge: generate.py beside this file (Python standard library; the published")
    out.append("# BIP39 test phrases and seeds; the core repo's independent.py for BIP32 and the encodings). Do not edit:")
    out.append("# the host KAT bip39_24w_vectors_kat checks this file is what generate.py prints.")
    out.append("# network: mainnet")
    out.append("# phrase <s> <the 24 words>")
    out.append("# passphrase <s> <the BIP39 passphrase, or (empty)>")
    out.append("# seed <s> <64-byte BIP39 seed, hex>")
    out.append("# addr <s> <profile> <format> <chain> <index> <address>   (first %d of each chain)" % COUNT)
    out.append("#   profile: 0 m/84'/20'/0', 1 m/0' (DigiByte master), 2 m/0', 3 m/44'/20'/0', 4 m/44'/0'/0',")
    out.append("#            5 m/49'/20'/0', 6 m/86'/20'/0'; format: 0 P2PKH, 1 P2WPKH, 2 P2SH-P2WPKH, 3 P2TR")
    for s, (phrase, passphrase, published) in enumerate(SEEDS):
        check_phrase(phrase, words)
        seed = hashlib.pbkdf2_hmac("sha512", phrase.encode(), b"mnemonic" + passphrase.encode(), 2048)
        if published is not None and seed.hex() != published:
            fail("seed %d is not the seed BIP39's test vectors publish" % s)
        if published is None and passphrase:
            fail("seed %d has a passphrase and no published seed" % s)
        out.append("phrase %d %s" % (s, phrase))
        out.append("passphrase %d %s" % (s, passphrase if passphrase else "(empty)"))
        out.append("seed %d %s" % (s, seed.hex()))
        for p, (hkey, hpath, formats) in enumerate(ind.PROFILES):
            k, c = ind.master(seed, hkey)
            for e in hpath:
                k, c = ind.ckd_priv(k, c, e | ind.H)
            acct = (ind.mul(k), c)
            for fmt in formats:
                for chain in (0, 1):
                    K1, c1 = ind.ckd_pub(acct[0], acct[1], chain)
                    for index in range(COUNT):
                        K2, _ = ind.ckd_pub(K1, c1, index)
                        out.append("addr %d %d %d %d %d %s" % (s, p, fmt, chain, index, ind.address(K2, fmt)))
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
