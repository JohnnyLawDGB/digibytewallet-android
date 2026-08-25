#!/usr/bin/env bash
#
# The version in README.md and CLAUDE.md must match app/build.gradle.kts.
#
# Both drifted badly before this existed — README sat at v4.0.46 while the app shipped v4.0.58,
# twelve releases behind, and CLAUDE.md at v4.0.48 despite carrying a note telling the reader it
# is mirrored on release. A version written where it cannot update is wrong by default; the only
# question is how long before someone notices.
#
# Same class as the site advertising 4.0.46 beside a 4.0.55 download. There the fix was to read
# the value at runtime. Docs cannot do that, so the next best thing is to make drift fail.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"

VER=$(grep -oE 'versionName = "[0-9.]+"' "$GRADLE" | grep -oE '[0-9.]+' | head -1)
[ -n "$VER" ] || { echo "FAIL: could not read versionName from $GRADLE"; exit 1; }

status=0
check() {
    local file="$1" label="$2" found
    found=$(grep -oE "$3" "$ROOT/$file" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    if [ -z "$found" ]; then
        echo "FAIL: no $label version line found in $file"; status=1
    elif [ "$found" != "$VER" ]; then
        echo "FAIL: $file says $found, app/build.gradle.kts says $VER"; status=1
    else
        echo "ok: $file at $VER"
    fi
}

check README.md  "status"  '\*\*Status:\*\* Active development — \*\*v[0-9.]+\*\*'
check CLAUDE.md  "version" '\*\*Version:\*\* v[0-9.]+'

if [ "$status" -ne 0 ]; then
    echo
    echo "Bump the doc line(s) to match, or adjust this gate if the format changed."
fi
exit "$status"
