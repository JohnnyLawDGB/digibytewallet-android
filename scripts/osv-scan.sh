#!/usr/bin/env bash
#
# Dependency CVE scan against OSV (osv.dev), the same data osv-scanner uses.
#
# Queries the OSV batch API directly with the Maven coordinates Gradle resolves, rather than
# requiring osv-scanner plus a Gradle lockfile the project does not keep. No new tooling, no
# lockfile churn, runs the same locally and in CI.
#
# WHY THIS RUNS ON EVERY BUILD rather than in the periodic security cycle: a new CVE appears
# without anyone touching this repository. It is the one class of vulnerability that cannot be
# found by reading our own code, and the only one whose arrival is unrelated to our commits.
#
# Usage: scripts/osv-scan.sh [configuration]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${1:-mainnetReleaseRuntimeClasspath}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "Resolving $CONFIG ..."
if ! (cd "$ROOT" && ./gradlew -q :app:dependencies --configuration "$CONFIG" > "$WORK/deps.txt" 2>/dev/null); then
    echo "FAIL: could not resolve $CONFIG"; exit 1
fi

# group:artifact:version, taking the RESOLVED version after any "->" arrow.
grep -oE '[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9][a-zA-Z0-9._-]*( -> [0-9][a-zA-Z0-9._-]*)?' "$WORK/deps.txt" \
  | sed -E 's/^([^:]+:[^:]+):[^ ]+ -> (.+)$/\1:\2/' \
  | sort -u > "$WORK/coords.txt"

COUNT=$(wc -l < "$WORK/coords.txt")
[ "$COUNT" -gt 0 ] || { echo "FAIL: resolved zero coordinates — the parse is wrong, not the project"; exit 1; }
echo "Querying OSV for $COUNT package(s) ..."

python3 - "$WORK/coords.txt" <<'PY'
import json, sys, urllib.request

coords = [l.strip() for l in open(sys.argv[1]) if l.strip()]
queries = []
for c in coords:
    g, a, v = c.rsplit(":", 2)[0], c.rsplit(":", 2)[1], c.rsplit(":", 2)[2]
    queries.append({"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v})

req = urllib.request.Request(
    "https://api.osv.dev/v1/querybatch",
    data=json.dumps({"queries": queries}).encode(),
    headers={"Content-Type": "application/json"},
)
try:
    res = json.load(urllib.request.urlopen(req, timeout=90))
except Exception as e:
    print(f"FAIL: OSV query failed: {e}")
    sys.exit(1)

hits = []
for coord, result in zip(coords, res.get("results", [])):
    for v in result.get("vulns", []) or []:
        hits.append((coord, v.get("id", "?")))

if not hits:
    print(f"ok: no known vulnerabilities across {len(coords)} package(s)")
    sys.exit(0)

print(f"FAIL: {len(hits)} advisory match(es):")
seen = set()
for coord, vid in hits:
    if (coord, vid) in seen:
        continue
    seen.add((coord, vid))
    print(f"    {coord}\n      → {vid}  https://osv.dev/vulnerability/{vid}")
sys.exit(1)
PY
