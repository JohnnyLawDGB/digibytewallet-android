#!/usr/bin/env bash
#
# A security cycle is due every 10 releases. Fails a release build when the shipped versionCode
# has run 10 or more ahead of the last recorded cycle.
#
# WHY THIS BLOCKS RELEASES rather than every build: development should not stop, but shipping is
# the point of pressure that actually gets the review done. The previous arrangement — a practice
# everyone agreed with and no gate — produced 52 releases without one.
#
# To satisfy it: run the cycle, append an entry to security/AUDIT-LOG.md, and move the
# LAST_AUDITED_VERSION_CODE marker. To defer: record the deferral in the log WITH a reason and
# move the marker deliberately. Both are fine; forgetting is what this prevents.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/security/AUDIT-LOG.md"
INTERVAL=10

CURRENT=$(grep -oE 'versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)
[ -n "$CURRENT" ] || { echo "FAIL: could not read versionCode"; exit 1; }

LAST=$(grep -oE 'LAST_AUDITED_VERSION_CODE: [0-9]+' "$LOG" 2>/dev/null | grep -oE '[0-9]+' | head -1)
if [ -z "$LAST" ]; then
    echo "FAIL: no LAST_AUDITED_VERSION_CODE marker in security/AUDIT-LOG.md."
    echo "      The gate cannot tell 'never audited' from 'marker reformatted', so it stops."
    exit 1
fi

# versionCode delta, NOT a release count — they coincide within a version line (we bump by one
# per release) but not across a major, where 30606 -> 40058 reads as 9452 and means nothing.
BEHIND=$(( CURRENT - LAST ))
echo "versionCode $CURRENT, last security cycle at $LAST (delta $BEHIND, interval $INTERVAL)"

if [ "$BEHIND" -ge "$INTERVAL" ]; then
    cat <<EOF

SECURITY CYCLE DUE — versionCode has moved $BEHIND since the last recorded cycle ($LAST).

  1. ./scripts/security-cycle.sh          # automated half; prints a report
  2. review the changed surface since $LAST  # JNI, native parsing, intents, crypto, network
  3. append the findings to security/AUDIT-LOG.md
  4. move LAST_AUDITED_VERSION_CODE to $CURRENT

Deferring is allowed — record it in the log with a reason and move the marker deliberately.
EOF
    exit 1
fi

echo "ok: next cycle due at versionCode $(( LAST + INTERVAL ))"
