#!/usr/bin/env bash
# The host-KAT runner must start on a machine that has no `clang` on its PATH.
#
# Every native fix proves itself with a host KAT, so a runner that refuses to start is a
# proof that silently never happens. This test takes `clang` off the PATH, runs one KAT
# through the runner, and requires the runner to (a) say which compiler it chose and
# (b) pass the KAT with it.
#
# It uses gcs_match_kat on purpose. bech32m_kat builds with gcc and would pass even if
# compiler discovery were broken.
#
# Usage: scripts/test-helpers/kat-runner-test.sh        (exit 0 = pass)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHADOW="$(mktemp -d)"
trap 'rm -rf "$SHADOW"' EXIT

# A PATH on which no directory offers clang. A directory that does (usually /usr/bin) is
# replaced by a shadow holding symlinks to everything else in it, so bash, mktemp, grep
# and the rest of the toolchain the runner needs are all still there.
newpath=""
IFS=: read -ra dirs <<<"$PATH"
for d in "${dirs[@]}"; do
    [ -d "$d" ] || continue
    if compgen -G "$d/clang*" >/dev/null; then
        s="$SHADOW/$(printf '%s' "$d" | tr '/' '_')"
        if [ ! -d "$s" ]; then
            mkdir -p "$s"
            find "$d" -mindepth 1 -maxdepth 1 ! -name 'clang*' -exec ln -s -t "$s" {} + 2>/dev/null
        fi
        d="$s"
    fi
    newpath="${newpath:+$newpath:}$d"
done

fail() { echo "FAIL: $*"; exit 1; }

# Precondition — without it this test proves nothing.
if PATH="$newpath" command -v clang >/dev/null 2>&1; then
    fail "could not take clang off the PATH (still resolves to $(PATH="$newpath" command -v clang))"
fi

out="$(PATH="$newpath" bash "$ROOT/scripts/run-host-kats.sh" gcs_match 2>&1)"; rc=$?
echo "$out" | sed 's/^/    | /'

[ $rc -eq 0 ] || fail "the runner exited $rc with no clang on the PATH"
grep -Eq '^compiler: /' <<<"$out" || fail "the runner did not print the compiler it chose (expected a 'compiler: /abs/path' line)"
grep -Eq '^gcs_match_kat +PASS' <<<"$out" || fail "gcs_match_kat did not pass"
echo "PASS: kat-runner-test"
