#!/usr/bin/env bash
#
# The automatable half of a security cycle. Prints a report to paste into
# security/AUDIT-LOG.md.
#
# It does NOT replace the manual changed-surface review — JNI boundary, native parsing, intents,
# crypto and network paths still need reading by someone. What it does is remove the excuse that
# starting a cycle is expensive.
#
# Usage: scripts/security-cycle.sh [path/to/release.apk]
#        With no APK, builds nothing and checks only what can be checked from source.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-}"
VER=$(grep -oE 'versionName = "[0-9.]+"' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9.]+' | head -1)

echo "### v$VER — $(date -u +%Y-%m-%d) (automated half)"
echo

echo "**Dependencies**"
if bash "$ROOT/scripts/osv-scan.sh" 2>&1 | tail -3 | sed 's/^/    /'; then :; fi
echo

if [ -z "$APK" ]; then
    echo "_No APK supplied — native, secret and manifest checks skipped._"
    echo "_Re-run as: scripts/security-cycle.sh path/to/digibyte-wallet-vX.apk_"
    exit 0
fi
[ -f "$APK" ] || { echo "FAIL: no such APK: $APK"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$APK" -d "$WORK" "lib/arm64-v8a/*" 2>/dev/null
SO="$WORK/lib/arm64-v8a/libcore-lib.so"

echo "**Native hardening** (arm64 \`libcore-lib.so\`)"
if [ -f "$SO" ]; then
    readelf -h "$SO" | grep -q "DYN" && echo "    - PIE: yes" || echo "    - PIE: **NO**"
    readelf -lW "$SO" | grep -q "GNU_STACK.*RWE" && echo "    - exec stack: **YES (bad)**" || echo "    - NX: yes"
    if readelf -lW "$SO" | grep -q "GNU_RELRO"; then
        readelf -dW "$SO" | grep -q "BIND_NOW" && echo "    - RELRO: FULL" || echo "    - RELRO: partial"
    else echo "    - RELRO: **NONE**"; fi
    nm -D "$SO" | grep -q "__stack_chk_fail" && echo "    - stack canary: yes" || echo "    - stack canary: **NO**"
    echo "    - fortify: $(nm -D "$SO" | grep -c '_chk@') checked libc call(s)"
    file "$SO" | grep -q "not stripped" && echo "    - symbols: **NOT STRIPPED**" || echo "    - symbols: stripped"
else
    echo "    - **no arm64 library found in the APK**"
fi
echo

echo "**Embedded secrets**"
DEX=$(unzip -p "$APK" classes.dex classes2.dex classes3.dex classes4.dex classes5.dex 2>/dev/null | strings)
SECRETS=$(echo "$DEX" | grep -oiE "(api[_-]?key|secret|password|bearer|private[_-]?key)[\"' :=]{1,3}[A-Za-z0-9/_+-]{16,}" | head -5)
[ -z "$SECRETS" ] && echo "    - none found" || { echo "    - **FOUND:**"; echo "$SECRETS" | sed 's/^/        /'; }
echo

echo "**Hosts in the dex**"
echo "$DEX" | grep -oE "https://[a-zA-Z0-9._-]+" | sort | uniq -c | sort -rn | head -15 | sed 's/^/    /'
echo
echo "_Manual half still required: changed-surface review since the last cycle (JNI boundary,_"
echo "_native parsing, intents, crypto, network), MobSF re-scan, and a jadx pass checking whether_"
echo "_the R8 keep rules over-kept._"
