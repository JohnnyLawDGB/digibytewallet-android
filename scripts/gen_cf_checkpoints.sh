#!/usr/bin/env bash
# Regenerate native/.../BRCompactFilterCheckpoints.h — DigiByte mainnet BIP157
# basic-filter-header checkpoints — from an operator-controlled full node with
# blockfilterindex=1.
#
# Each entry pins the cumulative filter-header at a fixed height so the wallet's
# TOFU filter-header chain can be cross-checked against a root of trust (R1 from
# the Neutrino review). The node's getblockfilter RPC returns the header in
# reversed (big-endian display) order; the wallet stores it INTERNAL order, so
# each hex is byte-reversed here (verified against BIP157 dSHA256(filterHash||prev)).
#
# Usage: scripts/gen_cf_checkpoints.sh [ssh_target] [ssh_key] [spacing]
#   defaults: root@digiscope.me  ~/.ssh/DigitalOcean  50000
#
# REORG SAFETY MARGIN (CF_TIP_MARGIN, default 10000 blocks ~= 42h): the newest
# checkpoint must sit at least this far below the node's tip. Since
# cfcheckpt-active-rejection, a checkpoint mismatch REJECTS the batch and BANS the
# peer — so pinning a height shallow enough to reorg would make the wallet ban every
# honest peer serving the correct chain: a self-inflicted eclipse, shipped in the
# binary. Without this bound the margin is pure luck of when the script is run
# (LAST = TIP - TIP%SPACING, so a run 12 minutes after a boundary pins ~50 blocks deep).
#
# EVERY ROW IS PROVEN BEFORE IT IS WRITTEN: the header is recomputed locally as the BIP157
# fold of the block's filter onto the previous header, and the node is bound to the right
# chain through the block-checkpoint table. A shipped row is never changed or dropped as a
# side effect of a refresh — differences are listed and the script refuses, unless
# CF_ALLOW_CORRECTIONS=1 accepts them. native/src/test/host/cf_checkpoint_fold_kat holds the
# same proof as a host KAT, from a second node (rerun its gen_vectors.py after a refresh).
# Requires: ssh access to the node, python3 locally and on the node.
set -euo pipefail

SSH_TARGET="${1:-root@digiscope.me}"
SSH_KEY="${2:-$HOME/.ssh/DigitalOcean}"
SPACING="${3:-50000}"
CF_TIP_MARGIN="${CF_TIP_MARGIN:-10000}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/native/src/main/jni/digibytewallet-core/BRCompactFilterCheckpoints.h"

BLOCK_HDR="$(cd "$(dirname "$0")/.." && pwd)/native/src/main/jni/digibytewallet-core/BRChainParams.h"

# Each row carries everything needed to PROVE it locally, not just the value to pin:
#   <height> <blockhash> <header> <filter> <header of height-1>
# Fields are read from getblockfilter's JSON BY KEY. The reply holds two hex strings
# ("filter" first, then "header"), and a block's filter is itself 64+ hex characters
# whenever it has more than a handful of elements — so the header can only be
# identified by name, never by position or by shape.
echo "Pulling filter-headers from $SSH_TARGET (spacing $SPACING)..." >&2
RAW="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$SSH_TARGET" bash -s <<EOF
CLI=/usr/local/bin/digibyte-cli
TIP=\$(\$CLI getblockcount)
LAST=\$(( TIP - TIP % $SPACING ))
# Reorg safety: never pin a checkpoint within CF_TIP_MARGIN of the tip.
if [ \$(( TIP - LAST )) -lt $CF_TIP_MARGIN ]; then
  LAST=\$(( LAST - $SPACING ))
  echo "note: top checkpoint dropped to \$LAST — the next one up is within $CF_TIP_MARGIN of tip \$TIP" >&2
fi
field() { python3 -c "import sys,json; print(json.load(sys.stdin)['\$1'])"; }
for (( h=$SPACING; h<=LAST; h+=$SPACING )); do
  hash=\$(\$CLI getblockhash \$h 2>/dev/null) || continue
  phash=\$(\$CLI getblockhash \$(( h - 1 )) 2>/dev/null) || continue
  cur=\$(\$CLI getblockfilter "\$hash" basic 2>/dev/null) || continue
  hdr=\$(printf '%s' "\$cur" | field header) || continue
  flt=\$(printf '%s' "\$cur" | field filter) || continue
  phdr=\$(\$CLI getblockfilter "\$phash" basic 2>/dev/null | field header) || continue
  echo "\$h \$hash \$hdr \$flt \$phdr"
done
EOF
)"

# The node rows go to python as a FILE PATH in argv, NOT piped. `python3 - <<'PY'`
# already consumes stdin to read its own program, so anything piped in is invisible
# to sys.stdin. This script previously piped $RAW in exactly that way: it parsed
# ZERO rows, and would have silently overwritten all 476 shipped checkpoints with an
# empty array — which still COMPILES (count becomes 0) and silently disables the
# filter-header cross-check at BRPeerManager.c:2939. Verified 2026-08-02.
TMPDIR_GEN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_GEN"' EXIT
printf '%s\n' "$RAW" > "$TMPDIR_GEN/node.txt"

python3 - "$OUT" "$SPACING" "$TMPDIR_GEN/node.txt" "$BLOCK_HDR" "${CF_ALLOW_CORRECTIONS:-0}" <<'PY'
import sys, os, re, hashlib
out, spacing, node_path, block_hdr, allow_corrections = sys.argv[1:6]
HEX64 = re.compile(r'^[0-9a-f]{64}$')
def dsha(b): return hashlib.sha256(hashlib.sha256(b).digest()).digest()

# The block-checkpoint table is the chain anchor: gen_block_checkpoints.sh only ever
# writes it from a node that reproduced every shipped block checkpoint, and it sits on
# the same grid as this table.
m = re.search(r'BRMainNetCheckpoints\[\]\s*=\s*\{(.*?)\n\};', open(block_hdr).read(), re.S)
if not m: sys.exit("FATAL: could not locate BRMainNetCheckpoints[] in %s" % block_hdr)
block_ckpt = {int(h): x.lower() for h, x in
              re.findall(r'\{\s*(\d+),\s*uint256\("([0-9a-fA-F]{64})"\)', m.group(1))}

rows=[]; bound=0
for line in open(node_path):
    p=line.split()
    if len(p)!=5 or not p[0].isdigit(): continue
    h, blockhash, hdr, flt, phdr = int(p[0]), p[1], p[2], p[3], p[4]
    if not (HEX64.match(blockhash) and HEX64.match(hdr) and HEX64.match(phdr)) \
       or not re.match(r'^([0-9a-f]{2})+$', flt):
        sys.exit("REFUSING TO WRITE: malformed row from the node at height %d" % h)
    # CHAIN BINDING — the node must be on the chain the block table pins.
    if h in block_ckpt:
        if block_ckpt[h] != blockhash:
            sys.exit("REFUSING TO WRITE: node's block at height %d is %s but "
                     "BRMainNetCheckpoints pins %s — wrong chain." % (h, blockhash, block_ckpt[h]))
        bound += 1
    # THE PROOF — a filter header is, by definition (BIP157), the double-SHA256 of this
    # block's filter hash followed by the previous block's filter header. Recompute it
    # from the filter and the predecessor and require the node's "header" to be exactly
    # that. A value read from the wrong field, or in the wrong byte order, cannot pass.
    internal = bytes.fromhex(hdr)[::-1]                # internal = reverse(RPC display hex)
    folded = dsha(dsha(bytes.fromhex(flt)) + bytes.fromhex(phdr)[::-1])
    if folded != internal:
        sys.exit("REFUSING TO WRITE: height %d: the node's header is not the BIP157 fold "
                 "of its own filter onto the header at %d." % (h, h - 1))
    rows.append((h, internal.hex()))
rows.sort()
if rows and not bound:
    sys.exit("REFUSING TO WRITE: no row could be bound to BRMainNetCheckpoints.")
print("proved %d rows by BIP157 fold; %d bound to a block checkpoint" % (len(rows), bound),
      file=sys.stderr)

# A SHIPPED ROW IS NEVER CHANGED OR DROPPED SILENTLY. Every row above is proven, so a
# disagreement with the shipped table means the shipped value is the wrong one — but
# replacing a root-of-trust value is a decision, not a side effect of a refresh.
# Set CF_ALLOW_CORRECTIONS=1 to accept the listed corrections.
if os.path.exists(out):
    shipped = {int(h): x.lower() for h, x in
               re.findall(r'\{\s*(\d+),\s*uint256\("([0-9a-fA-F]{64})"\)\s*\}', open(out).read())}
    now = dict(rows)
    dropped = sorted(h for h in shipped if h not in now)
    changed = sorted(h for h in shipped if h in now and now[h] != shipped[h])
    if dropped:
        sys.exit("REFUSING TO WRITE: the node returned nothing for %d shipped height(s): %s"
                 % (len(dropped), ", ".join(map(str, dropped))))
    if changed:
        print("%d shipped row(s) differ from the proven value:" % len(changed), file=sys.stderr)
        for h in changed:
            print("  %9d  shipped %s\n             proven  %s" % (h, shipped[h], now[h]), file=sys.stderr)
        if allow_corrections != "1":
            sys.exit("REFUSING TO WRITE: re-run with CF_ALLOW_CORRECTIONS=1 to accept these corrections.")

# NEVER SHRINK THE TABLE. A node that is pruned, mid-reindex, or missing
# blockfilterindex yields fewer rows than are already shipped; overwriting with that
# is silent loss of a root-of-trust table that nothing downstream would flag.
prev = 0
if os.path.exists(out):
    prev = len(re.findall(r'\{\s*\d+,\s*uint256\("[0-9a-fA-F]{64}"\)\s*\}', open(out).read()))
if not rows:
    sys.exit("REFUSING TO WRITE: parsed 0 checkpoints from the node.")
if prev and len(rows) < prev:
    sys.exit("REFUSING TO WRITE: parsed %d checkpoints but %s already ships %d. "
             "A pruned/reindexing/unreachable node must not shrink this table."
             % (len(rows), out, prev))

with open(out,'w') as f:
    f.write("// AUTO-GENERATED by scripts/gen_cf_checkpoints.sh — DigiByte mainnet BIP157\n")
    f.write("// basic-filter-header checkpoints from an operator node (blockfilterindex=1).\n")
    f.write("// Headers stored in the wallet's INTERNAL byte order (= byte-reversed\n")
    f.write("// getblockfilter RPC hex; verified vs BIP157 dSHA256(filterHash||prevHeader)).\n")
    f.write("// %d checkpoints, %s-block spacing.\n" % (len(rows), spacing))
    f.write("#ifndef BRCompactFilterCheckpoints_h\n#define BRCompactFilterCheckpoints_h\n\n#include \"BRInt.h\"\n\n")
    f.write("typedef struct { uint32_t height; UInt256 filterHeader; } BRCFCheckpoint;\n\n")
    f.write("static const BRCFCheckpoint BRMainNetCFCheckpoints[] = {\n")
    for h,internal in rows:
        f.write('    { %8d, uint256("%s") },\n' % (h, internal))
    f.write("};\n\n")
    f.write("static const size_t BRMainNetCFCheckpointsCount = sizeof(BRMainNetCFCheckpoints)/sizeof(BRMainNetCFCheckpoints[0]);\n\n")
    # The lookup helpers are part of this header's CONTRACT, not decoration:
    # BRPeerManager.c and BRCompactFilterChain.c call them from the checkpoint
    # enforcement, re-anchor veto and never-brick paths. An earlier version of this
    # generator emitted only the table, so regenerating after cfcheckpt-active-rejection
    # landed silently DELETED all three and broke the build. Emit them verbatim.
    f.write("""static inline const BRCFCheckpoint *BRCFHighestCheckpointAtOrBelow(uint32_t height) {
    const BRCFCheckpoint *best = NULL;
    for (size_t i = 0; i < BRMainNetCFCheckpointsCount; i++) {
        if (BRMainNetCFCheckpoints[i].height <= height) best = &BRMainNetCFCheckpoints[i];
        else break; // ascending
    }
    return best;
}

// The height of the highest pinned checkpoint in the table (the historical/tip
// boundary). BRCFHighestCheckpointAtOrBelow(h) clamps to this same top entry for
// ANY h at or above it, so callers that need to know whether a height is still
// inside the checkpoint-covered historical region (as opposed to the tip region
// checkpoints say nothing about) must compare against this, not merely check
// that BRCFHighestCheckpointAtOrBelow(h) returned non-NULL.
static inline uint32_t BRCFTopCheckpointHeight(void) {
    return BRMainNetCFCheckpointsCount ? BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount - 1].height : 0;
}
static inline size_t BRCFCheckpointsInRange(uint32_t lo, uint32_t hi,
                                            const BRCFCheckpoint **out, size_t outCap) {
    size_t n = 0;
    for (size_t i = 0; i < BRMainNetCFCheckpointsCount && n < outCap; i++) {
        uint32_t h = BRMainNetCFCheckpoints[i].height;
        if (h >= lo && h <= hi) out[n++] = &BRMainNetCFCheckpoints[i];
        else if (h > hi) break;
    }
    return n;
}

""")
    f.write("#endif // BRCompactFilterCheckpoints_h\n")
print("wrote %d checkpoints to %s" % (len(rows), out), file=sys.stderr)
PY
