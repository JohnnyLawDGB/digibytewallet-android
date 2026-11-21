#!/usr/bin/env python3
"""Reads a `uiautomator dump` XML file for the pre-publish suite.

Everything here is a pure function of the dump file, so `pre-publish-test.sh --self-test` can
feed it canned dumps without an emulator.

  uidump.py center FILE [--exact|--contains|--prefix] [--nth N] TEXT   -> "x y" of the node
  uidump.py has    FILE [--exact|--contains|--prefix] TEXT            -> exit 0 / 1
  uidump.py texts  FILE                                               -> every text/desc, in order
  uidump.py after  FILE TEXT                                          -> the text that follows TEXT
  uidump.py match  FILE REGEX                                         -> first text matching REGEX
  uidump.py fields FILE                                               -> "x y" of each EditText
  uidump.py seed-words FILE                                           -> the numbered phrase words
  uidump.py redact FILE OUT                                           -> a copy safe to keep on disk
  uidump.py redact-log FD                                             -> stdin to stdout, with the
                                                                         words read from file
                                                                         descriptor FD removed

A node matches on its `text` or its `content-desc`. Exit status 1 means "not on this screen";
2 means the dump itself could not be read.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
# A recovery-phrase word as the app shows it: one lowercase BIP39-shaped word and nothing else.
SEED_WORD = re.compile(r"^[a-z]{3,8}$")
REDACTED = "[redacted]"


def load(path):
    try:
        return list(ET.parse(path).getroot().iter("node"))
    except (ET.ParseError, OSError) as e:
        print(f"uidump: cannot read {path}: {e}", file=sys.stderr)
        sys.exit(2)


def labels(node):
    return [v for v in (node.get("text", ""), node.get("content-desc", "")) if v]


def norm(s):
    # The app uses a typographic apostrophe in places; a caller should not have to know which.
    return s.replace("’", "'").replace(" ", " ").strip()


def matches(node, want, mode):
    want = norm(want)
    for label in labels(node):
        label = norm(label)
        if mode == "exact" and label == want:
            return True
        if mode == "contains" and want in label:
            return True
        if mode == "prefix" and label.startswith(want):
            return True
    return False


def center(node):
    m = BOUNDS.match(node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None  # laid out off screen or zero-sized: not something a finger can reach
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(nodes, want, mode):
    return [n for n in nodes if matches(n, want, mode) and center(n)]


def parse_mode(args):
    mode, nth = "exact", 0
    while args and args[0].startswith("--"):
        flag = args.pop(0)
        if flag in ("--exact", "--contains", "--prefix"):
            mode = flag[2:]
        elif flag == "--nth":
            nth = int(args.pop(0))
        else:
            print(f"uidump: unknown flag {flag}", file=sys.stderr)
            sys.exit(2)
    return mode, nth, args


def seed_words(nodes):
    """The phrase on the seed-display screen: each cell is the text "N" followed by its word."""
    texts = [n.get("text", "") for n in nodes if n.get("text", "")]
    words = {}
    for i, t in enumerate(texts[:-1]):
        if t.isdigit() and 1 <= int(t) <= 24 and SEED_WORD.match(texts[i + 1]):
            words.setdefault(int(t), texts[i + 1])
    count = len(words)
    if count not in (12, 24) or sorted(words) != list(range(1, count + 1)):
        return None
    return [words[i] for i in range(1, count + 1)]


def redact_text(value, in_field):
    if not value:
        return value
    if in_field:
        return REDACTED
    if SEED_WORD.match(value.strip()):
        return REDACTED
    # A phrase shown or typed as one string.
    if len(re.findall(r"\b[a-z]{3,8}\b", value)) >= 6 and re.fullmatch(r"[a-z\s]+", value.strip()):
        return REDACTED
    return value


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    cmd, args = sys.argv[1], sys.argv[2:]

    if cmd == "redact-log":
        # The words come in on a pipe, not in the environment or argv: either would show the
        # phrase to every process that can read /proc.
        try:
            with os.fdopen(int(args[0])) as f:
                words = [w for w in f.read().split() if w]
        except (OSError, ValueError, IndexError):
            print("uidump: redact-log needs a readable file descriptor number", file=sys.stderr)
            return 2
        pattern = re.compile(r"\b(" + "|".join(map(re.escape, set(words))) + r")\b") if words else None
        for line in sys.stdin:
            sys.stdout.write(pattern.sub(REDACTED, line) if pattern else line)
        return 0

    path, args = args[0], args[1:]

    if cmd == "redact":
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError) as e:
            print(f"uidump: cannot read {path}: {e}", file=sys.stderr)
            return 2
        for n in tree.getroot().iter("node"):
            field = "EditText" in n.get("class", "") or n.get("password") == "true"
            for attr in ("text", "content-desc"):
                if n.get(attr):
                    n.set(attr, redact_text(n.get(attr), field))
        tree.write(args[0], encoding="unicode")
        return 0

    nodes = load(path)

    if cmd in ("center", "has"):
        mode, nth, rest = parse_mode(args)
        found = find(nodes, rest[0], mode)
        if len(found) <= nth:
            return 1
        if cmd == "center":
            print(*center(found[nth]))
        return 0

    if cmd == "texts":
        for n in nodes:
            for label in labels(n):
                print(label.replace("\n", "\\n"))
        return 0

    if cmd == "after":
        texts = [l for n in nodes for l in labels(n)]
        for i, t in enumerate(texts[:-1]):
            if norm(t) == norm(args[0]):
                print(texts[i + 1])
                return 0
        return 1

    if cmd == "match":
        rx = re.compile(args[0])
        for n in nodes:
            for label in labels(n):
                m = rx.search(label)
                if m:
                    print(m.group(1) if rx.groups else m.group(0))
                    return 0
        return 1

    if cmd == "fields":
        found = [center(n) for n in nodes if "EditText" in n.get("class", "") and center(n)]
        for c in found:
            print(*c)
        return 0 if found else 1

    if cmd == "seed-words":
        words = seed_words(nodes)
        if not words:
            return 1
        print(" ".join(words))
        return 0

    print(f"uidump: unknown command {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
