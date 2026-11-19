#!/usr/bin/env python3
"""Read THE DIGISCOPE LIST out of one source file, for digiid_core_kat's SOURCE GATE.

Until Android reads the list from the core (Coinomi step 2), the list is written twice in shipped
code: in the core's BRDigiId.h (BRDigiIdIsDigiScopeHost) and in Android's IdentityKeyPolicy.kt
(DIGISCOPE_HOSTS). run.sh holds both to the hosts digiid_core_kat_main.c names. This script reads
one of them:

  kotlin <IdentityKeyPolicy.kt>
      the string literals of `val DIGISCOPE_HOSTS[: Set<String>] = setOf(...)`
  header <preprocessed C>
      the string literals in the body of BRDigiIdIsDigiScopeHost, in the compiler's own
      preprocessing of BRDigiId.h (cc -E -P -DDIGIID_UNFIXED=0): comments are gone there, and of
      the test seams only the shipped lines are left

It prints one host per line, in source order. The reading is strict on purpose. Comments are
skipped by a scanner that tells a string from a comment, never by a pattern. Whatever the script
does not follow stops it with a message: a raw string, a string template, an escape, a set that
is not written as plain string literals, a declaration that goes on after its setOf(...), two
declarations, none. A change it cannot read then fails the gate instead of being read wrong.

Usage: python3 -I digiscope_hosts.py kotlin|header <file>
"""

import re
import sys


def fail(msg):
    sys.stderr.write("%s\n" % msg)
    sys.exit(1)


def plain_host(text, where):
    """A literal this script can print on a line of its own and compare byte for byte."""
    if not text or re.search(r'[^\x21-\x7e]|[\\$"]', text):
        fail("%s holds a literal that is not plain printable ASCII (%r): cannot read it" % (where, text))
    return text


def kotlin_skeleton(src):
    """Kotlin source with its comments dropped and each string literal replaced by \\0<n>\\0."""
    out, strings, i, n = [], [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j   # the newline stays: it ends a statement
        elif src.startswith("/*", i):   # Kotlin's block comments nest
            depth, i = 1, i + 2
            while depth and i < n:
                if src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            if depth:
                fail("a block comment is not closed")
            out.append(" ")
        elif src.startswith('"""', i):
            fail('the file holds a raw string ("""), which this reader does not follow')
        elif src[i] == '"':
            j, text = i + 1, []
            while True:
                if j >= n or src[j] == "\n":
                    fail("a string is not closed on its line")
                if src[j] == '"':
                    break
                if src.startswith("${", j):
                    fail("the file holds a string template (${...}), which this reader does not follow")
                if src[j] == "\\":
                    text.append(src[j:j + 2])
                    j += 2
                else:
                    text.append(src[j])
                    j += 1
            out.append("\0%d\0" % len(strings))
            strings.append("".join(text))
            i = j + 1
        elif src[i] == "'":   # a character literal: 'x', '\n', 'A', '"'
            j = i + (3 if src.startswith("\\", i + 1) else 2)
            k = src.find("'", j)
            if k < 0 or "\n" in src[i:k]:
                fail("a character literal is not closed on its line")
            out.append("' '")
            i = k + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out), strings


def kotlin_hosts(path):
    skeleton, strings = kotlin_skeleton(open(path, encoding="utf-8").read())
    declared = len(re.findall(r"\b(?:val|var)\s+DIGISCOPE_HOSTS\b", skeleton))
    if declared == 0:
        fail("no DIGISCOPE_HOSTS is declared (if Android now reads the list from the core, this gate goes with the Kotlin list)")
    if declared > 1:
        fail("DIGISCOPE_HOSTS is declared %d times" % declared)
    m = re.search(r"\bval\s+DIGISCOPE_HOSTS\s*(?::\s*Set\s*<\s*String\s*>\s*)?=\s*setOf\s*\(([^()]*)\)", skeleton)
    if not m:
        fail("DIGISCOPE_HOSTS is not written `val DIGISCOPE_HOSTS[: Set<String>] = setOf(...)`: cannot read it")
    if not re.fullmatch(r"\s*(?:\0\d+\0\s*(?:,\s*\0\d+\0\s*)*,?\s*)?", m.group(1)):
        fail("DIGISCOPE_HOSTS's setOf(...) holds something that is not a string literal: cannot read it")
    rest = skeleton[m.end():]
    if rest.split("\n", 1)[0].strip() or rest.lstrip().startswith((".", "?.", "?:")):
        fail("DIGISCOPE_HOSTS goes on after its setOf(...): cannot read it")
    return [plain_host(strings[int(k)], "DIGISCOPE_HOSTS") for k in re.findall(r"\0(\d+)\0", m.group(1))]


def header_hosts(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    name = "BRDigiIdIsDigiScopeHost"
    found = [m for m in re.finditer(r"\b%s\s*\([^()]*\)\s*\{" % name, src)]
    if len(found) != 1:
        fail("%s is defined %d times in the preprocessed header" % (name, len(found)))
    i, depth, hosts, after_string = found[0].end(), 1, [], False
    while depth:
        if i >= len(src):
            fail("%s's body is not closed" % name)
        ch = src[i]
        if ch == '"':
            m = re.compile(r'"((?:[^"\\\n]|\\.)*)"').match(src, i)
            if not m:
                fail("a string in %s's body is not closed" % name)
            if after_string:
                fail("%s's body holds two string literals side by side (a concatenation): cannot read it" % name)
            hosts.append(plain_host(m.group(1), "%s's body" % name))
            after_string, i = True, m.end()
            continue
        if ch == "'":
            m = re.compile(r"'(?:[^'\\\n]|\\.)+'").match(src, i)
            if not m:
                fail("a character constant in %s's body is not closed" % name)
            after_string, i = False, m.end()
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        if not ch.isspace():
            after_string = False
        i += 1
    if not hosts:
        fail("%s's body holds no string literal: the list is no longer written there" % name)
    return hosts


def main(argv):
    if len(argv) != 3 or argv[1] not in ("kotlin", "header"):
        fail("usage: python3 -I digiscope_hosts.py kotlin|header <file>")
    hosts = kotlin_hosts(argv[2]) if argv[1] == "kotlin" else header_hosts(argv[2])
    if len(set(hosts)) != len(hosts):
        fail("a host is written twice: %s" % " ".join(hosts))
    for host in hosts:
        print(host)


main(sys.argv)
