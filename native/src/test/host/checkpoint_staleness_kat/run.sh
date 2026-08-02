#!/usr/bin/env bash
# Host KAT runner for the BRChainParams.h block-checkpoint table.
#
# Guards the two properties that bound how much pointless BIP158 work a wallet does
# on first sync:
#   - STALENESS: the newest mainnet checkpoint sets a NEW wallet's filter-scan floor.
#   - DENSITY:   the largest gap between adjacent checkpoints bounds a RECOVERY
#                wallet's over-scan.
# Plus the structural invariants the C core relies on (checkpoints[0] == genesis,
# ascending heights, non-zero targets).
#
# BRChainParams.h is self-contained on the host: it needs only -I on the submodule
# dir and links no submodule .c sources (verified — it pulls BRMerkleBlock.h/BRSet.h
# for types only). Same header-only shape as status_staleness_kat / cf_peer_status_kat.
#
# THIS KAT IS INTENTIONALLY TIME-DEPENDENT. It goes red as the checkpoint table ages,
# with no code change, which is the entire point: a stale constant table is invisible
# by inspection and had already cost every new wallet a ~290k-filter scan once. If it
# fails, do not raise the threshold — run scripts/gen_block_checkpoints.sh.
#
# Exit code 0 = all checks passed, 1 = at least one failed (or a build error).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

clang -w \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/checkpoint_staleness_kat_main.c" \
    -o "$BUILD_DIR/checkpoint_staleness_kat"

# Run the C gate WITHOUT `set -e` aborting on a non-zero exit: both this and the
# cross-language check below must run and report, so a failure in one never hides
# the other. Same reason the greps below are `|| true`-guarded — `grep | head` can
# raise SIGPIPE(141) under `pipefail` and would masquerade as a check failure.
set +e
"$BUILD_DIR/checkpoint_staleness_kat"
kat_rc=$?
set -e

# ---- CROSS-LANGUAGE INVARIANT: the Kotlin mirror must track the C table -------
#
# SyncService.kt carries LATEST_CHECKPOINT_HEIGHT, an anti-eclipse floor: sync is
# never latched Complete while the chain tip is below it, so a cohort of lagging or
# dishonest peers cannot make the wallet stop scanning and strand transactions in
# blocks it never looked at. It is a HAND-MAINTAINED COPY of the newest entry in
# BRMainNetCheckpoints, kept in sync by nothing but a comment saying "update this
# when a newer entry is added" — the identical rot that let the C table itself go
# 298,962 blocks stale. It had already drifted twice (its own doc comment says
# 23,187,000 in one paragraph and 23,660,000 in the next).
#
# A stale-LOW mirror does not crash; it silently WEAKENS the eclipse guard. That is
# exactly the kind of degradation nothing else would ever surface, so assert it here
# rather than leave another reminder.
SYNC_KT="$REPO_ROOT/app/src/main/java/io/digibyte/service/SyncService.kt"
[ -f "$SYNC_KT" ] || { echo "FAIL: cannot find $SYNC_KT to cross-check LATEST_CHECKPOINT_HEIGHT" >&2; exit 1; }

kt_h="$( { grep -oE 'LATEST_CHECKPOINT_HEIGHT[[:space:]]*=[[:space:]]*[0-9_]+' "$SYNC_KT" || true; } \
         | { grep -oE '[0-9_]+$' || true; } | tr -d '_' | { head -1 || true; } )"
# Newest MAINNET checkpoint. BRTestNetCheckpoints lives in the same header, so take
# the max height only from the mainnet array body — a testnet row must never win.
c_h="$( awk '/BRMainNetCheckpoints\[\][[:space:]]*=/{f=1;next} f&&/^};/{exit} f' \
          "$CORE_DIR/BRChainParams.h" \
        | { grep -oE '\{[[:space:]]*[0-9]+,' || true; } | { grep -oE '[0-9]+' || true; } \
        | sort -n | { tail -1 || true; } )"

if [ -z "$kt_h" ] || [ -z "$c_h" ]; then
    echo "FAIL: could not extract heights (kotlin='$kt_h' c='$c_h')" >&2
    exit 1
fi

if [ "$kt_h" != "$c_h" ]; then
    echo "FAIL: SyncService.kt LATEST_CHECKPOINT_HEIGHT ($kt_h) != newest BRMainNetCheckpoints ($c_h)"
    echo "      The Kotlin anti-eclipse floor is a hand-copied mirror of the C table and has drifted."
    echo "      Set LATEST_CHECKPOINT_HEIGHT = ${c_h} in app/src/main/java/io/digibyte/service/SyncService.kt"
    exit 1
fi
echo "PASS: SyncService.kt LATEST_CHECKPOINT_HEIGHT ($kt_h) matches newest BRMainNetCheckpoints"

exit $kat_rc
