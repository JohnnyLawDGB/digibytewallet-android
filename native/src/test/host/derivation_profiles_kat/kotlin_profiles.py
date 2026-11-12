#!/usr/bin/env python3
"""Read DerivationProfile.BUILT_INS out of the Kotlin source and write it as a C table.

derivation_profiles_kat compares this table -- what Android's Recover funds actually probes -- with
the core's BRDerivationProfiles.h, field by field, and drives the bridge's deriveAddresses with it.
The parse is deliberately strict: every BUILT_INS entry must name each field this script knows, and
any constant it cannot resolve stops it, so a new field or a renamed constant fails the gate instead
of being silently skipped.

Usage: python3 -I kotlin_profiles.py <DerivationProfile.kt> > kotlin_profiles.h
"""

import re
import sys


def fail(msg):
    sys.stderr.write("kotlin_profiles.py: %s\n" % msg)
    sys.exit(1)


src = open(sys.argv[1], encoding="utf-8").read()

consts = {}
for name, value in re.findall(r'const val (\w+)\s*=\s*("(?:[^"\\]|\\.)*"|-?\d+)', src):
    consts[name] = value
for need in ("HMAC_STANDARD", "HMAC_DIGIBYTE", "P2PKH", "P2WPKH", "P2SH_P2WPKH", "P2TR",
             "DEFAULT_GAP_EXTERNAL", "DEFAULT_GAP_INTERNAL"):
    if need not in consts:
        fail("constant %s not found" % need)

# Defaults of the gap parameters must be the named constants (the core states 200/100 once).
if not re.search(r"val gapExternal: Int = DEFAULT_GAP_EXTERNAL", src) or \
   not re.search(r"val gapInternal: Int = DEFAULT_GAP_INTERNAL", src):
    fail("gap parameter defaults are no longer DEFAULT_GAP_EXTERNAL / DEFAULT_GAP_INTERNAL")

m = re.search(r"val BUILT_INS: List<DerivationProfile> = listOf\((.*?)\n        \)\n", src, re.S)
if not m:
    fail("BUILT_INS not found")
body = m.group(1)
# Drop line comments (none of the string fields contains //).
body = "\n".join(re.sub(r"//.*$", "", line) for line in body.split("\n"))

entries = re.findall(r"DerivationProfile\((.*?)\n            \),", body, re.S)
if not entries:
    fail("no DerivationProfile entries in BUILT_INS")
if body.count("DerivationProfile(") != len(entries):
    fail("an entry of BUILT_INS was not parsed")

KNOWN = {"label", "description", "hmacKey", "prefixPath", "addressFormat", "extraAddressFormats",
         "isNative", "gapExternal", "gapInternal"}


def resolve_int(tok):
    tok = tok.strip()
    if re.fullmatch(r"-?\d+", tok):
        return int(tok)
    if tok in consts and re.fullmatch(r"-?\d+", consts[tok]):
        return int(consts[tok])
    fail("cannot resolve integer %r" % tok)


def resolve_str(tok):
    tok = tok.strip()
    if tok.startswith('"'):
        return tok
    if tok in consts and consts[tok].startswith('"'):
        return consts[tok]
    fail("cannot resolve string %r" % tok)


def split_top(text):
    """Split a Kotlin argument list on commas outside strings and parentheses."""
    parts, cur, depth, in_str, esc = [], [], 0, False, False
    for ch in text:
        if in_str:
            cur.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            continue
        cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur))
    return parts


rows = []
for e in entries:
    fields = {}
    for part in split_top(e):
        fm = re.fullmatch(r"\s*(\w+)\s*=\s*(.*?)\s*", part, re.S)
        if not fm:
            fail("cannot read argument %r" % part.strip()[:60])
        if fm.group(1) in fields:
            fail("argument %s given twice" % fm.group(1))
        fields[fm.group(1)] = fm.group(2)
    unknown = set(fields) - KNOWN
    if unknown:
        fail("unknown field(s) %s" % sorted(unknown))
    for need in ("label", "description", "hmacKey", "prefixPath", "addressFormat", "isNative"):
        if need not in fields:
            fail("entry without %s: %s" % (need, e.strip()[:60]))
    pm = re.fullmatch(r"hardened\(([^)]*)\)", fields["prefixPath"].strip())
    if not pm:
        fail("prefixPath is not hardened(...): %r" % fields["prefixPath"])
    path = [resolve_int(t) for t in pm.group(1).split(",")]
    formats = [resolve_int(fields["addressFormat"])]
    if "extraAddressFormats" in fields:
        em = re.fullmatch(r"listOf\(([^)]*)\)", fields["extraAddressFormats"].strip())
        if not em:
            fail("extraAddressFormats is not listOf(...)")
        formats += [resolve_int(t) for t in em.group(1).split(",") if t.strip()]
    native = {"true": 1, "false": 0}.get(fields["isNative"].strip())
    if native is None:
        fail("isNative is not a literal")
    gap_e = resolve_int(fields.get("gapExternal", "DEFAULT_GAP_EXTERNAL"))
    gap_i = resolve_int(fields.get("gapInternal", "DEFAULT_GAP_INTERNAL"))
    rows.append((resolve_str(fields["label"]), resolve_str(fields["description"]),
                 resolve_str(fields["hmacKey"]), path, formats, native, gap_e, gap_i))

print("/* Generated by kotlin_profiles.py from DerivationProfile.kt BUILT_INS. Do not edit. */")
print("#define KOTLIN_PROFILE_COUNT %d" % len(rows))
print("#define KOTLIN_PATH_MAX %d" % max(len(r[3]) for r in rows))
print("#define KOTLIN_FORMATS_MAX %d" % max(len(r[4]) for r in rows))
print("typedef struct { const char *label, *description, *hmacKey; uint32_t path[KOTLIN_PATH_MAX]; size_t pathLen;")
print("                 int formats[KOTLIN_FORMATS_MAX]; size_t formatCount; int isNative; int32_t gapExternal, gapInternal; } KotlinProfile;")
print("static const KotlinProfile KOTLIN_PROFILES[KOTLIN_PROFILE_COUNT] = {")
for label, desc, hmac, path, formats, native, gap_e, gap_i in rows:
    print("    { %s, %s, %s, { %s }, %d, { %s }, %d, %d, %d, %d }," % (
        label, desc, hmac, ", ".join("%du | 0x80000000u" % p for p in path), len(path),
        ", ".join(str(f) for f in formats), len(formats), native, gap_e, gap_i))
print("};")
