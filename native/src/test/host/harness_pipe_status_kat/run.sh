#!/usr/bin/env bash
# Host check on the KAT harnesses themselves: a gate's answer must not depend on scheduling.
#
# INVARIANT. In a script that sets pipefail, no early-exit reader (grep -q, grep -m, --quiet,
# --max-count) reads from a pipe. Such a reader exits at its first match; the writer can then be
# killed by SIGPIPE, and pipefail turns that into "no match" for a line that is there. Measured
# 2026-10-11: `echo "$out" | grep -q` missed a present line 2 times in 3,000 on a 4 KB output, and
# every time on a large one. Where the line searched for is a sanitizer report, a miss is a pass
# that should have been a failure. Read a variable with a here-string (`<<<"$out" grep -q ...`) or
# a file by name.
#
#   RED    the scan over planted scripts that break the rule, in each form: every one MUST be found;
#          and the mechanism itself: the piped form MUST miss a present line on a large output.
#   GREEN  the scan over native/src/test/host/*/*.sh and scripts/*.sh finds nothing, and the
#          here-string form never misses.
# Exit code 0 = gate held, 1 = it did not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

RULE='(^|[^|])\|[[:space:]]*grep[[:space:]]+(-[A-Za-z]*[qm][A-Za-z0-9]*|--quiet|--max-count)'

# Prints file:line: text for every breach in the files given.
scan() {
    local f
    for f in "$@"; do
        grep -q pipefail "$f" || continue
        grep -nE "$RULE" "$f" | grep -vE '^[0-9]+:[[:space:]]*#' | sed "s|^|$f:|"
    done
}

FAIL=0
echo "--- COMPARISON: planted scripts, every breach MUST be found, every lawful line passed ---"
cat > "$WORK/breaks.sh" <<'PLANT'
set -uo pipefail
if echo "$out" | grep -q "^ALL PASS$"; then :; fi
printf '%s\n' "$out" | grep -Eq "$SAN_RE" && FAIL=1
nm -D "$SO" | grep -q "__stack_chk_fail" || echo "no canary"
first="$(echo "$out" | grep -m1 '^RESULT')"
readelf -lW "$SO" | grep --quiet "GNU_RELRO" && echo relro
PLANT
cat > "$WORK/lawful.sh" <<'PLANT'
set -uo pipefail
if <<<"$out" grep -q "^ALL PASS$"; then :; fi
[ $rc -ne 0 ] || grep -q "ERROR: AddressSanitizer" "$log"
# echo "$out" | grep -q "a comment is not code"
<<<"$out" grep -E "$SAN_RE" | head -3
PLANT
cat > "$WORK/no-pipefail.sh" <<'PLANT'
set -u
if echo "$out" | grep -q "^ALL PASS$"; then :; fi
PLANT
found="$(scan "$WORK/breaks.sh" | wc -l)"
if [ "$found" -eq 5 ]; then echo "  [red planted] all 5 breaches found, as they must be"
else echo "  [red planted] GATE FAILURE: found $found of 5"; scan "$WORK/breaks.sh" | sed 's/^/      /'; FAIL=1; fi
lawful="$(scan "$WORK/lawful.sh" "$WORK/no-pipefail.sh")"
if [ -z "$lawful" ]; then echo "  [red planted] lawful lines and a script without pipefail passed"
else echo "  [red planted] GATE FAILURE: flagged a lawful line"; echo "$lawful" | sed 's/^/      /'; FAIL=1; fi

# The mechanism, on an output far larger than a pipe's buffer with the match at its start.
big="$(seq 1 2000000)"
piped_miss=0; here_miss=0
for _ in 1 2 3 4 5; do
    echo "$big" | grep -q '^5$' || piped_miss=$((piped_miss + 1))
    <<<"$big" grep -q '^5$' || here_miss=$((here_miss + 1))
done
if [ "$piped_miss" -gt 0 ]; then echo "  [red mechanism] the piped form missed a present line $piped_miss time(s) in 5, as it can"
else echo "  [red mechanism] GATE FAILURE: the piped form never missed; this check shows nothing here"; FAIL=1; fi

echo "--- SHIPPED: the harness scripts in this tree ---"
if [ "$here_miss" -eq 0 ]; then echo "  [green mechanism] the here-string form never missed (0 in 5)"
else echo "  [green mechanism] GATE FAILURE: the here-string form missed $here_miss time(s)"; FAIL=1; fi
# This file is left out: it plants the forms on purpose (above), as the comparison.
mapfile -t files < <(ls "$REPO_ROOT"/native/src/test/host/*/*.sh "$REPO_ROOT"/scripts/*.sh 2>/dev/null \
                     | grep -vxF "$SCRIPT_DIR/run.sh")
breaches="$(scan "${files[@]}")"
if [ -z "$breaches" ]; then echo "  [green tree] ${#files[@]} scripts: no early-exit reader on a pipe under pipefail"
else echo "  [green tree] GATE FAILURE: an early-exit reader reads a pipe under pipefail:"; echo "$breaches" | sed "s|$REPO_ROOT/||; s/^/      /"; FAIL=1; fi

[ $FAIL -eq 0 ] && echo "harness_pipe_status_kat: GATE HELD"
exit $FAIL
