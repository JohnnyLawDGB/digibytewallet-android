#!/usr/bin/env bash
# Regenerate the BRMainNetCheckpoints[] table in
# native/src/main/jni/digibytewallet-core/BRChainParams.h from an
# operator-controlled DigiByte full node.
#
# WHY THIS EXISTS
# ---------------
# The newest block checkpoint sets a FRESH WALLET'S COMPACT-FILTER SCAN FLOOR:
# SyncService.kt reads getWalletBirthCheckpointHeight() (which walks this table)
# and arms the BIP158 scan at `tip - 100`. On a fresh install there are no saved
# blocks, so that "tip" IS the newest checkpoint. Every block between the newest
# checkpoint and the real chain tip therefore becomes a cfilter the wallet
# downloads and evaluates — at ~700 bytes each — hunting for transactions that
# CANNOT EXIST, because the wallet was created after them.
#
# jni_wallet.c calls this anchor "a short, bounded catch-up that self-updates as
# checkpoints ship and never goes stale". That property is CONDITIONAL ON THIS
# SCRIPT BEING RUN. In 2026-08 the table was found 298,962 blocks stale (newest
# checkpoint 2026-06-12), which silently turned every new wallet into a ~290k
# filter / ~200 MB scan — the exact "ever-growing span" the anchor was written to
# prevent. The checkpoint_staleness_kat host KAT now fails the build when this
# table ages past CKPT_MAX_STALE_DAYS; run this script to clear it.
#
# BYTE ORDER — THE TRAP
# ---------------------
# Block-checkpoint hashes are stored as the RPC DISPLAY HEX **VERBATIM**. The C
# core applies UInt256Reverse at READ time (BRPeerManager.c:2331, :4755). This is
# the OPPOSITE of BRCompactFilterCheckpoints.h, whose filter-headers ARE
# pre-reversed in the source file by gen_cf_checkpoints.sh. Do not "fix" one to
# match the other. The self-check below would catch it, which is why it is not
# optional.
#
# SAFETY: this script REFUSES TO WRITE unless the node reproduces every height
# already in the shipped table byte-for-byte (hash + timestamp + bits). A node on
# a different chain, a pruned node, or a node mid-reindex therefore cannot poison
# the table.
#
# Usage: scripts/gen_block_checkpoints.sh [ssh_target] [ssh_key] [spacing]
#   defaults: root@digiscope.me  ~/.ssh/DigitalOcean  50000
# Requires: ssh access to the node, python3 locally.
set -euo pipefail

SSH_TARGET="${1:-root@digiscope.me}"
SSH_KEY="${2:-$HOME/.ssh/DigitalOcean}"
SPACING="${3:-50000}"
# MINIMUM DEPTH BELOW THE TIP — DO NOT LOWER. A checkpoint is not a hint: a
# relayed block whose hash differs from the checkpoint at that height fails
# _BRPeerManagerVerifyBlock (BRPeerManager.c:1715-1722), and the caller then calls
# _BRPeerManagerPeerMisbehavin — which REMOVES the peer from manager->peers and
# DISCONNECTS it, wiping the whole stored peer list after 10 such events. So a
# checkpoint captured from a block that later reorgs out does not merely go stale:
# it makes the wallet ban every honest peer serving the canonical chain and take
# itself off the network.
#
# DigiByte targets 15-second blocks across five algos and routine short reorgs on
# abandoned algo branches are a documented wedge class, so a checkpoint a few hours
# below the tip is inside the reorg horizon. 50,000 blocks is ~8.7 days.
#
# There is no cost to this depth. getWalletBirthCheckpointHeight (jni_peer.c) only
# ever selects a checkpoint at least 7 days OLDER than the wallet's creation time,
# so an entry fresher than ~7 days can never be chosen as a birth anchor — it is
# pure reorg risk for zero scan-span benefit.
MIN_DEPTH="${4:-50000}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HDR="$ROOT/native/src/main/jni/digibytewallet-core/BRChainParams.h"

[ -f "$HDR" ] || { echo "no such file: $HDR" >&2; exit 1; }

# ---- 1. the heights already shipped (preserved as a superset, never dropped) --
OLD="$(python3 - "$HDR" <<'PY'
import re,sys
src=open(sys.argv[1]).read()
m=re.search(r'BRMainNetCheckpoints\[\]\s*=\s*\{(.*?)\n\};', src, re.S)
if not m: sys.exit("could not locate BRMainNetCheckpoints[] in BRChainParams.h")
for h,hsh,t,b in re.findall(
        r'\{\s*(\d+),\s*uint256\("([0-9a-fA-F]{64})"\),\s*(\d+),\s*(0x[0-9a-fA-F]+)\s*\}', m.group(1)):
    print(h, hsh.lower(), t, b.lower())
PY
)"
OLD_HEIGHTS="$(echo "$OLD" | cut -d' ' -f1 | tr '\n' ' ')"
echo "shipped table: $(echo "$OLD" | wc -l) checkpoints" >&2

# ---- 2. pull genesis + every shipped height + the uniform grid ---------------
echo "pulling from $SSH_TARGET (spacing $SPACING)..." >&2
RAW="$(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$SSH_TARGET" bash -s <<EOF
CLI=/usr/local/bin/digibyte-cli
TIP=\$(\$CLI getblockcount)
# Stop the grid MIN_DEPTH below the tip, then round down to the grid. Never pin a
# height that could still reorg out — see the MIN_DEPTH rationale above.
SAFE=\$(( TIP - $MIN_DEPTH ))
LAST=\$(( SAFE - SAFE % $SPACING ))
echo "#TIP \$TIP"
echo "#LAST \$LAST"
emit() {
  hash=\$(\$CLI getblockhash \$1 2>/dev/null) || return 0
  \$CLI getblockheader "\$hash" 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d['height'], d['hash'], d['time'], d['bits'])
" 2>/dev/null || true
}
emit 0
for h in $OLD_HEIGHTS; do [ "\$h" = "0" ] || emit \$h; done
for (( h=$SPACING; h<=LAST; h+=$SPACING )); do emit \$h; done
EOF
)"

# ---- 3. self-check + write ----------------------------------------------------
# NOTE: the node rows and the shipped table are handed to python as FILE PATHS in
# argv, NOT piped. `python3 - <<'PY'` already consumes stdin to read its own program,
# so anything piped in is invisible to sys.stdin — a trap that silently yields an
# EMPTY parse rather than an error. (The self-check below catches it either way,
# which is exactly why the self-check is not optional.)
TMPDIR_GEN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_GEN"' EXIT
printf '%s\n' "$RAW" > "$TMPDIR_GEN/node.txt"
printf '%s\n' "$OLD" > "$TMPDIR_GEN/old.txt"

python3 - "$HDR" "$SPACING" "$TMPDIR_GEN/node.txt" "$TMPDIR_GEN/old.txt" "$MIN_DEPTH" <<'PY'
import re, sys, datetime

hdr_path, spacing, node_path, old_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
min_depth = int(sys.argv[5])

old = {}
for line in open(old_path):
    p = line.split()
    if len(p) != 4: continue
    old[int(p[0])] = (p[1].lower(), int(p[2]), p[3].lower())

tip = None
node = {}
for line in open(node_path):
    if line.startswith('#TIP'):
        tip = int(line.split()[1]); continue
    p = line.split()
    if len(p) != 4: continue
    node[int(p[0])] = (p[1].lower(), int(p[2]), '0x' + p[3].lower())

if tip is None:
    sys.exit("FATAL: node did not report a tip")
if len(node) < 2:
    sys.exit("FATAL: node returned %d rows — is digibyte-cli reachable?" % len(node))

# --- THE GATE: the node must reproduce the shipped table exactly ---
missing  = sorted(h for h in old if h not in node)
mismatch = [(h, old[h], node[h]) for h in sorted(old) if h in node and node[h] != old[h]]
if missing or mismatch:
    print("*** REFUSING TO WRITE — node disagrees with the shipped table ***", file=sys.stderr)
    for h in missing:
        print("  height %d: node returned nothing (pruned? reindexing?)" % h, file=sys.stderr)
    for h, o, n in mismatch:
        print("  height %d:\n    shipped: %s\n    node   : %s" % (h, o, n), file=sys.stderr)
    print("A node on a different chain must never be allowed to write this table.", file=sys.stderr)
    sys.exit(1)
print("self-check OK: node reproduces all %d shipped checkpoints" % len(old), file=sys.stderr)

# --- union: keep every shipped anchor, add the grid on top ---
rows = sorted(node.items())

# ...EXCEPT anchors inside the reorg horizon. The preserve-every-shipped-height union
# would otherwise RE-IMPORT a too-fresh checkpoint written by an earlier run of this
# script, silently re-introducing the peer-ban hazard the MIN_DEPTH guard below exists
# to prevent — the grid stops at a safe height, but the union pulled the bad entry
# straight back in. The integrity self-check above still ran against the FULL shipped
# set, so dropping here costs no verification coverage.
too_fresh = [h for h, _ in rows if tip - h < min_depth]
if too_fresh:
    print("dropping %d checkpoint(s) inside the reorg horizon (< %d blocks deep): %s"
          % (len(too_fresh), min_depth, ", ".join(str(h) for h in too_fresh)), file=sys.stderr)
    rows = [(h, v) for h, v in rows if tip - h >= min_depth]
if rows[0][0] != 0:
    sys.exit("FATAL: genesis (height 0) missing — checkpoints[0] must be genesis "
             "(BRPeerManager.c:68 genesis_block_hash)")

newest_h, (_, newest_t, _) = rows[-1]

# THE REORG GUARD. A checkpoint too close to the tip is not a weaker checkpoint, it
# is an active hazard: if that block reorgs out, every honest peer relaying the
# canonical chain fails _BRPeerManagerVerifyBlock and is DISCONNECTED + REMOVED by
# _BRPeerManagerPeerMisbehavin, and 10 of those wipe the stored peer list. Refuse to
# emit one rather than trust the caller to have passed a sane MIN_DEPTH.
depth = tip - newest_h
if depth < min_depth:
    sys.exit("REFUSING TO WRITE: newest checkpoint %d is only %d blocks below the tip "
             "(%d); minimum safe depth is %d. A checkpoint inside the reorg horizon "
             "makes the wallet ban every honest peer if that block is reorged out."
             % (newest_h, depth, tip, min_depth))
# Comma-separated, no trailing comma after the final entry (matches house style).
lines = ['        { %8d, uint256("%s"), %d, %s }' % (h, v[0], v[1], v[2]) for h, v in rows]
body = ",\n".join(lines) + "\n"

banner = (
    "// AUTO-GENERATED by scripts/gen_block_checkpoints.sh — DO NOT HAND-EDIT.\n"
    "// %d checkpoints: genesis + every previously-shipped height + a uniform\n"
    "// %s-block grid, from an operator full node. Hashes are the RPC display hex\n"
    "// VERBATIM (UInt256Reverse is applied at READ time, BRPeerManager.c:2331/:4755)\n"
    "// — NOT pre-reversed like BRCompactFilterCheckpoints.h.\n"
    "//\n"
    "// The NEWEST entry sets a fresh wallet's BIP158 scan floor, so a stale table\n"
    "// silently becomes a multi-hundred-thousand-filter scan. checkpoint_staleness_kat\n"
    "// fails the build when this ages out; rerun the generator to clear it.\n"
    "// newest: height %d @ %s UTC (chain tip was %d at generation)\n"
) % (len(rows), spacing, newest_h,
     datetime.datetime.fromtimestamp(newest_t, datetime.timezone.utc).strftime('%Y-%m-%d'), tip)

src = open(hdr_path).read()
pat = re.compile(r'(static const BRCheckPoint BRMainNetCheckpoints\[\]\s*=\s*\{\n).*?(\n\};)', re.S)
if not pat.search(src):
    sys.exit("FATAL: could not locate BRMainNetCheckpoints[] body to replace")
# Drop any previous AUTO-GENERATED banner so they don't stack up.
src = re.sub(r'(?m)^// AUTO-GENERATED by scripts/gen_block_checkpoints\.sh.*?(?=^static const BRCheckPoint BRMainNetCheckpoints)',
             '', src, flags=re.S)
src = pat.sub(lambda m: banner + m.group(1) + body + m.group(2), src, count=1)
open(hdr_path, 'w').write(src)

print("wrote %d checkpoints (newest %d, %d blocks behind tip %d)"
      % (len(rows), newest_h, tip - newest_h, tip), file=sys.stderr)
PY
