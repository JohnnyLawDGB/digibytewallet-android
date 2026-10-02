#!/usr/bin/env python3
"""Write header_diff_v4_vectors.inc: real consecutive DigiByte headers for header_diff_v4_kat.

Every header comes from a local full node and is kept only if its dSHA256 is the block hash and each
header's prevBlock is the hash of the row before it. The script also carries an independent reference
of the reference client's GetNextWorkRequiredV4 (Python integers); it computes, for every row with
enough history inside its range, the target V4 gives, and refuses to write the file unless that equals
the header's own nBits for every one of them. The per-range counts of judged and not-judged rows are
written into the file, and the KAT requires the wallet's code to arrive at exactly those counts.

    gen_vectors.py                       # regenerate the fixture (read-only RPC)
    gen_vectors.py --live N FILE         # the last N mainnet headers (plus warm-up rows) as a judge file
                                         # for `header_diff_v4_kat judge_file FILE` (run.sh --live)

Mainnet: /usr/local/bin/digibyte-cli. Testnet26: the node in ~/.digibyte-tn over RPC when it is running;
otherwise its block file is read directly (blocks/blk*.dat, the chain walked down from the last
"UpdateTip" in its debug.log), which is what the file header then says.
"""
import hashlib, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN_CLI = ["/usr/local/bin/digibyte-cli"]
TN_DIR = os.path.expanduser("~/.digibyte-tn")
TN_CLI = [os.path.expanduser("~/digibyte-9.26.3/bin/digibyte-cli"), "-datadir=" + TN_DIR, "-testnet"]

# Fixed ranges, so a re-run reproduces the same file (first height, count).
MAIN_RANGES = [
    ("mainnet_recent", 24312000, 360),   # near the tip at generation (2026-10-02, tip 24,313,198)
    ("mainnet_algo_swap", 9099820, 360),  # spans algoSwapChangeTarget 9,100,000
    ("mainnet_first_odo", 9112140, 360),  # spans OdoHeight 9,112,320: the first Odo block and the last Groestl ones
]
TN_RANGE = ("testnet_clamps", 418430, 360)  # testnet26: powLimit results, both timespan clamps

# ---- reference client parameters (kernel/chainparams.cpp) ------------------------------------------
M = 1 << 256
POW_LIMIT = (M - 1) >> 20
NET = {
    "main": dict(T=750, mn=750 * (100 - 8) // 100, mx=750 * (100 + 16) // 100, adj=4, wct=1430000),
    "test": dict(T=750, mn=750 * (100 - 8) // 100, mx=750 * (100 + 8) // 100, adj=4, wct=400),
}
ALGO = {0x000: "scrypt", 0x200: "sha256d", 0x400: "groestl", 0x600: "skein", 0x800: "qubit", 0xE00: "odo"}


def dsha(b):
    return hashlib.sha256(hashlib.sha256(b).digest()).digest()


def set_compact(c):
    size, word = c >> 24, c & 0x007FFFFF
    if size <= 3:
        return word >> (8 * (3 - size))
    return (word << (8 * (size - 3))) % M


def get_compact(v):
    size = (v.bit_length() + 7) // 8
    c = (v << (8 * (3 - size))) if size <= 3 else (v >> (8 * (size - 3)))
    c &= 0xFFFFFFFFFFFFFFFF
    if c & 0x00800000:
        c >>= 8
        size += 1
    return (c | (size << 24)) & 0xFFFFFFFF


def tdiv(a, b):  # C/C++ integer division (truncates toward zero)
    q = abs(a) // b
    return q if a >= 0 else -q


def algo_of(version):
    return ALGO.get(version & 0xF00)


def v4(rows, i, net, stats=None, allow_min=False):
    """target V4 gives rows[i] from rows[0..i-1]; None when rows[0..i-1] do not hold what it reads.
    allow_min: the reference client's fPowAllowMinDifficultyBlocks (false on both networks; see min_rule below)"""
    P = NET[net]
    a = algo_of(rows[i]["version"])
    if allow_min and rows[i]["time"] > rows[i - 1]["time"] + 2 * 60:
        return get_compact(POW_LIMIT)
    if a is None:
        return get_compact(POW_LIMIT)
    last = i - 1
    if last - 50 - 10 < 0:
        return None
    pa = None
    for j in range(last, -1, -1):
        if (algo_of(rows[j]["version"]) or "scrypt") != a:
            continue
        if allow_min:
            if j == 0:
                return None  # its parent is not in hand: cannot tell whether it is a minimum-difficulty block
            if rows[j]["time"] > rows[j - 1]["time"] + 2 * 60:
                continue  # GetLastBlockIndexForAlgo skips a minimum-difficulty block
        pa = j
        break
    if pa is None:
        return None
    t = [r["time"] for r in rows]
    mtp = lambda k: sorted(t[k - 10:k + 1])[5]
    ts = P["T"] + tdiv(mtp(last) - mtp(last - 50) - P["T"], 4)
    if stats is not None:
        stats["clamp_min"] += ts < P["mn"]
        stats["clamp_max"] += ts > P["mx"]
    ts = max(P["mn"], min(P["mx"], ts))
    bn = set_compact(rows[pa]["bits"]) * ts % M // P["T"]
    n = pa + 5 - 1 - last
    if stats is not None:
        stats["harder"] += n > 0
        stats["easier"] += n < 0
    for _ in range(max(n, 0)):
        bn = bn * 100 % M // (100 + P["adj"])
    for _ in range(max(-n, 0)):
        bn = bn * (100 + P["adj"]) % M // 100
        if bn > POW_LIMIT:
            bn = POW_LIMIT
            break
    if bn > POW_LIMIT:
        bn = POW_LIMIT
    if stats is not None:
        stats["at_pow_limit"] += bn == POW_LIMIT
    return get_compact(bn)


def v4_inputs(net, last_times, first_times, bits, distance):
    """V4 on gathered inputs, as BRDifficultyV4Target takes them (synthetic vectors for the parameter edges)"""
    P = NET[net]
    ts = P["T"] + tdiv(sorted(last_times)[5] - sorted(first_times)[5] - P["T"], 4)
    ts = max(P["mn"], min(P["mx"], ts))
    bn = set_compact(bits) * ts % M // P["T"]
    n = 5 - 1 - distance
    for _ in range(max(n, 0)):
        bn = bn * 100 % M // (100 + P["adj"])
    for _ in range(max(-n, 0)):
        bn = bn * (100 + P["adj"]) % M // 100
        if bn > POW_LIMIT:
            bn = POW_LIMIT
            break
    return get_compact(min(bn, POW_LIMIT))


def synthetic():
    """inputs at the edges real ranges rarely reach: both clamps on both networks (mainnet's maximum is built from
    nMaxAdjustDownV4, testnet's from nMaxAdjustUpV4), truncating division of a negative step, unsorted median
    spans, 0..4 harder steps, the easier loop reaching powLimit"""
    base = 1700000000
    first = [base + 15 * k for k in (3, 9, 0, 7, 1, 10, 4, 8, 2, 6, 5)]   # deliberately out of order
    vec = []
    for net in ("main", "test"):
        for span, bits, dist, what in (
                (750, 0x1a0ebd68, 4, "on schedule, no per-algo step"),
                (100000, 0x1a0ebd68, 4, "slow: maximum timespan clamp"),
                (0, 0x1a0ebd68, 4, "fast: minimum timespan clamp"),
                (1000, 0x1a0ebd68, 4, "750 + 250/4 = 812: under mainnet's maximum, over testnet's"),
                (520, 0x1a0ebd68, 4, "750 + (-230)/4 truncates to -57 (693), not -58"),
                (750, 0x1904ffcb, 0, "four harder steps"),
                (750, 0x1904ffcb, 3, "one harder step"),
                (750, 0x1a53efb1, 30, "26 easier steps"),
                (900, 0x1e0ffff0, 9, "easier steps reach powLimit and stop"),
                (750, 0x1e0fffff, 4, "at powLimit, slower: clamped to powLimit"),
                (750, 0x1d00ffff, 1000, "far behind: easier loop ends at powLimit")):
            last = [t + span for t in first[::-1]]
            vec.append((net == "test", last, first, bits, dist, v4_inputs(net, last, first, bits, dist), what))
    return vec


def parse(raw, height):
    return dict(height=height, raw=raw, version=int.from_bytes(raw[0:4], "little"),
                time=int.from_bytes(raw[68:72], "little"), bits=int.from_bytes(raw[72:76], "little"))


def check_linked(rows):
    for a, b in zip(rows, rows[1:]):
        assert b["raw"][4:36] == dsha(a["raw"]), ("not linked", b["height"])


def judge(rows, net):
    stats = dict(judged=0, skip=0, clamp_min=0, clamp_max=0, harder=0, easier=0, at_pow_limit=0)
    for i in range(1, len(rows)):
        e = v4(rows, i, net, stats)
        if e is None:
            stats["skip"] += 1
        elif e != rows[i]["bits"]:
            sys.exit("REFERENCE DISAGREES with the chain at height %d: bits %08x, V4 %08x -- not writing"
                     % (rows[i]["height"], rows[i]["bits"], e))
        else:
            stats["judged"] += 1
    return stats


# ---- sources -------------------------------------------------------------------------------------
def rpc(cli, *a):
    return subprocess.check_output(cli + list(a), text=True, stderr=subprocess.DEVNULL).strip()


def fetch_rpc(cli, first, count):
    h, out = rpc(cli, "getblockhash", str(first + count - 1)), []
    for height in range(first + count - 1, first - 1, -1):
        raw = bytes.fromhex(rpc(cli, "getblockheader", h, "false"))
        assert len(raw) == 80 and dsha(raw)[::-1].hex() == h, ("dSHA256 != hash", height)
        out.append(parse(raw, height))
        h = raw[4:36][::-1].hex()
    out.reverse()
    assert dsha(out[0]["raw"])[::-1].hex() == rpc(cli, "getblockhash", str(first))
    return out


def fetch_testnet(first, count):
    try:
        rpc(TN_CLI, "getblockcount")
        return fetch_rpc(TN_CLI, first, count), "testnet26 node over RPC (%s)" % json.loads(
            rpc(TN_CLI, "getnetworkinfo"))["subversion"]
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    # node not running: read its own block files, walk down from its last recorded tip
    tip, tips = None, {}
    for line in open(os.path.join(TN_DIR, "testnet26", "debug.log"), errors="replace"):
        m = re.search(r"UpdateTip: new best=([0-9a-f]{64}) height=(\d+)", line)
        if m:
            tip = m.group(1)
            tips[int(m.group(2))] = m.group(1)
    headers, bdir = {}, os.path.join(TN_DIR, "testnet26", "blocks")
    for name in sorted(n for n in os.listdir(bdir) if n.startswith("blk")):
        data, off = open(os.path.join(bdir, name), "rb").read(), 0
        while off + 8 <= len(data) and data[off:off + 4] == bytes.fromhex("fec6b9e7"):
            size = int.from_bytes(data[off + 4:off + 8], "little")
            raw = data[off + 8:off + 88]
            headers[dsha(raw)[::-1].hex()] = raw
            off += 8 + size
    chain, h = [], tip
    while h in headers:
        chain.append(headers[h])
        h = headers[h][4:36][::-1].hex()
    chain.reverse()  # chain[k] is height k: the walk ends at the genesis block
    assert h == "00" * 32, "block file walk did not reach the genesis block"
    # cross-check: how many of the node's logged tips lie on the chain walked (a tip later reorganised away
    # does not)
    ok = sum(1 for height, hh in tips.items() if height < len(chain) and dsha(chain[height])[::-1].hex() == hh)
    rows = [parse(chain[k], k) for k in range(first, first + count)]
    return rows, ("testnet26 node's block files (node not running), chain walked from its last tip %s, "
                  "height %d; %d of its logged tips lie on that chain" % (tip, len(chain) - 1, ok))


def grind_easiest(prev):
    """a sha256d header on top of `prev` with the easiest target the wallet accepts, proof of work met"""
    target = set_compact(0x1E0FFFFF)
    root = dsha(b"header_diff_v4_kat easiest-target header")
    head = (0x20000202).to_bytes(4, "little") + dsha(prev["raw"]) + root + \
           (prev["time"] + 30).to_bytes(4, "little") + (0x1E0FFFFF).to_bytes(4, "little")
    n = 0
    while int.from_bytes(dsha(head + n.to_bytes(4, "little")), "little") > target:
        n += 1
    return head + n.to_bytes(4, "little"), n


def c_rows(name, rows):
    s = "static const char *const %s[%d] = {\n" % (name, len(rows))
    for r in rows:
        s += '    "%s", // %d %s\n' % (r["raw"].hex(), r["height"], algo_of(r["version"]) or "unknown")
    return s + "};\n"


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "--live":
        n = int(sys.argv[2])
        tip = int(rpc(MAIN_CLI, "getblockcount"))
        warm = 200
        rows = fetch_rpc(MAIN_CLI, tip - n - warm + 1, n + warm)
        check_linked(rows)
        with open(sys.argv[3], "w") as f:
            f.write("main %d\n" % n)
            for r in rows:
                f.write("%d %s\n" % (r["height"], r["raw"].hex()))
        print("live: %d headers %d..%d from %s (the last %d are judged)" % (
            len(rows), rows[0]["height"], rows[-1]["height"], json.loads(rpc(MAIN_CLI, "getnetworkinfo"))["subversion"], n))
        return

    node = json.loads(rpc(MAIN_CLI, "getnetworkinfo"))["subversion"]
    tip = int(rpc(MAIN_CLI, "getblockcount"))
    ranges = []
    for name, first, count in MAIN_RANGES:
        rows = fetch_rpc(MAIN_CLI, first, count)
        ranges.append((name, 0, rows, "mainnet node %s" % node))
    rows, src = fetch_testnet(TN_RANGE[1], TN_RANGE[2])
    ranges.append((TN_RANGE[0], 1, rows, src))

    out = []
    for name, testnet, rows, src in ranges:
        check_linked(rows)
        st = judge(rows, "test" if testnet else "main")
        if st["judged"] < 200:
            sys.exit("%s: only %d judged rows" % (name, st["judged"]))
        out.append((name, testnet, rows, src, st))
        print("%-18s %d..%d judged=%d skip=%d clamp_min=%d clamp_max=%d harder=%d easier=%d at_pow_limit=%d" % (
            name, rows[0]["height"], rows[-1]["height"], st["judged"], st["skip"], st["clamp_min"], st["clamp_max"],
            st["harder"], st["easier"], st["at_pow_limit"]))

    # The minimum-difficulty rule is off on both networks in the reference client, and the testnet26 chain agrees
    # (with it on, the reference disagrees with the chain). The KAT turns it on over the testnet range and requires
    # the wallet to reach these same counts.
    tn = out[-1][2]
    min_rule = dict(match=0, mismatch=0, skip=0, first=0, match_before=0, skip_before=0)
    for i in range(1, len(tn)):
        e = v4(tn, i, "test", allow_min=True)
        verdict = "skip" if e is None else ("match" if e == tn[i]["bits"] else "mismatch")
        if verdict == "mismatch" and not min_rule["first"]:  # level 2 refuses here; nothing above it connects
            min_rule.update(first=i, match_before=min_rule["match"], skip_before=min_rule["skip"])
        min_rule[verdict] += 1
    print("testnet range with the minimum-difficulty rule on: match=%d mismatch=%d skip=%d" % (
        min_rule["match"], min_rule["mismatch"], min_rule["skip"]))
    assert min_rule["mismatch"] > 0

    recent = out[0][2]
    easy, nonce = grind_easiest(recent[-1])
    assert int.from_bytes(dsha(easy), "little") <= set_compact(0x1E0FFFFF)
    print("easiest-target header on %d: nonce %d" % (recent[-1]["height"], nonce))

    with open(os.path.join(HERE, "header_diff_v4_vectors.inc"), "w") as f:
        f.write("// AUTO-GENERATED by gen_vectors.py -- DO NOT HAND-EDIT. Regenerate instead.\n")
        f.write("// Real consecutive DigiByte headers (80 bytes, wire order), each linked to the row before it and kept\n")
        f.write("// only where dSHA256 equals the node's block hash. Mainnet tip at generation: %d.\n" % tip)
        f.write("// judged/skip: rows (after the first, which is seeded as resident) whose target the generator's\n")
        f.write("// independent reference computes from the rows above them / cannot compute for want of history.\n")
        f.write("// Every judged row's nBits equals the reference's value; the file is not written otherwise.\n\n")
        for name, testnet, rows, src, st in out:
            f.write("// %s: %s\n" % (name, src))
            f.write("//   judged %d, skip %d; clamp_min %d, clamp_max %d, harder %d, easier %d, at powLimit %d\n" % (
                st["judged"], st["skip"], st["clamp_min"], st["clamp_max"], st["harder"], st["easier"],
                st["at_pow_limit"]))
            f.write(c_rows("k_" + name, rows) + "\n")
        f.write("typedef struct { const char *name; int testnet; uint32_t firstHeight; size_t count;\n"
                "                 const char *const *hex; uint32_t judged, skip; } DiffRange;\n\n")
        f.write("static const DiffRange kDiffRanges[] = {\n")
        for name, testnet, rows, src, st in out:
            f.write('    { "%s", %d, %d, %d, k_%s, %d, %d },\n' % (
                name, testnet, rows[0]["height"], len(rows), name, st["judged"], st["skip"]))
        f.write("};\n\n")
        f.write("// The testnet range judged with fPowAllowMinDifficultyBlocks turned on (it is off on both networks): the\n")
        f.write("// counts the reference reaches. The chain was built without the rule, so most of its mismatches are real.\n")
        f.write("// Level 2 refuses the first mismatching row (kMinRuleFirstMismatch); the counts up to it are the *Before ones.\n")
        f.write("static const uint32_t kMinRuleMatch = %d, kMinRuleMismatch = %d, kMinRuleSkip = %d;\n" % (
            min_rule["match"], min_rule["mismatch"], min_rule["skip"]))
        f.write("static const uint32_t kMinRuleFirstMismatch = %d, kMinRuleMatchBefore = %d, kMinRuleSkipBefore = %d;\n\n" % (
            min_rule["first"], min_rule["match_before"], min_rule["skip_before"]))
        f.write("// Synthetic inputs for BRDifficultyV4Target at the parameter edges; expected from the reference above.\n")
        f.write("typedef struct { int testnet; uint32_t lastTimes[11], firstTimes[11], bits, distance, expected;\n"
                "                 const char *what; } DiffInputVector;\n\n")
        f.write("static const DiffInputVector kDiffInputVectors[] = {\n")
        for testnet, last, first, bits, dist, exp, what in synthetic():
            f.write("    { %d, { %s },\n      { %s },\n      0x%08x, %d, 0x%08x, \"%s\" },\n" % (
                testnet, ", ".join(map(str, last)), ", ".join(map(str, first)), bits, dist, exp, what))
        f.write("};\n\n")
        f.write("// A sha256d header on top of the last mainnet_recent row with the easiest target the wallet accepts\n")
        f.write("// (0x1e0fffff), its nonce ground until the proof of work meets that target (%d).\n" % nonce)
        f.write('static const char *kEasiestTargetHeader = "%s";\n' % easy.hex())


if __name__ == "__main__":
    main()
