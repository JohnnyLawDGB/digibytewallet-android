#!/usr/bin/env bash
# Run one shard of the host KATs, one suite at a time, through the repo's own runner.
#
#   run-shard.sh <android checkout> <shard list> <results file>
#
# Each suite goes through `scripts/run-host-kats.sh <name>` unchanged, so it gets the same
# compiler choice and shipped header check levels as the full sweep. One suite at a time: a
# runner shared by several ASan builds is where the rc=137 (out of memory) kills came from, and a
# retry would hide a real failure, so there is none.
#
# The runner's filter is a substring match, so a name contained in another suite's name runs both;
# every suite line the runner prints is recorded, not only the one asked for. The results file
# gets one line per suite run: "<name> <PASS|FAIL|NO_RUNNER|MISSING> <seconds>". MISSING means the
# runner never printed a line for the suite it was asked to run.
#
# Exit 0 only if every suite in the list passed and all of them were built by one compiler.
#
# ORIGIN: a copy of the core repo's .github/kat-shards/run-shard.sh (see plan.py). Differences:
#   - the runner's stdin is /dev/null. In the original it is the shard list, which the loop is
#     reading: a suite that read its stdin would swallow the rest of the list. (The gate would
#     catch that, as suites planned but not passed; this stops it happening.)
#   - the runner's `compiler:` and `header checks:` lines are kept, in <results file minus
#     .txt>.toolchain, and the shard fails if its suites did not all report the same ones. The
#     full sweep picks its compiler once; here each suite's runner picks again, and the search
#     can settle on a different clang if its first choice fails the probe.
set -uo pipefail

android="$1"; list="$2"; results="$3"
toolchain="${results%.txt}.toolchain"
logdir="$(mktemp -d)"
: > "$results"
: > "$logdir/toolchain"
fail=0; ran=0

while IFS= read -r name; do
    [ -n "$name" ] || continue
    ran=$((ran + 1))
    log="$logdir/$name.log"
    start=$SECONDS
    rc=0
    (cd "$android" && ./scripts/run-host-kats.sh "$name") > "$log" 2>&1 < /dev/null || rc=$?
    secs=$((SECONDS - start))
    grep -E '^(compiler|header checks): ' "$log" >> "$logdir/toolchain"

    # Suite lines are printf '%-32s %s': name, spaces, status. Status words: PASS, FAIL, NO RUNNER.
    status=MISSING
    while read -r suite word rest; do
        [ "$word" = NO ] && word=NO_RUNNER
        if [ "$suite" = "$name" ]; then
            status="$word"
        else
            echo "$suite $word $secs" >> "$results"   # ran too: the filter matched it as well
        fi
    done < <(grep -E '^[A-Za-z0-9._-]+_kat +(PASS|FAIL|NO RUNNER)$' "$log")
    # A suite that printed PASS but whose runner still exited non-zero is not a pass.
    [ "$status" = PASS ] && [ "$rc" -ne 0 ] && status=FAIL
    echo "$name $status $secs" >> "$results"

    printf '%-40s %-9s %5ss\n' "$name" "$status" "$secs"
    if [ "$status" != PASS ]; then
        fail=$((fail + 1))
        echo "::error::$name: $status (runner exit $rc)"
        sed 's/^/    | /' "$log"
    fi
done < "$list"

# One `compiler:` line and one `header checks:` line, if every suite was built alike.
LC_ALL=C sort -u "$logdir/toolchain" > "$toolchain"
rm -rf "$logdir"
echo
cat "$toolchain"
mixed=0
if [ "$(grep -c . "$toolchain")" -gt 2 ]; then
    mixed=1
    echo "::error::the suites in this shard were not all built by the same compiler at the same levels"
fi
echo "shard: $ran suites, $fail not passed (${SECONDS}s)"
[ "$ran" -gt 0 ] && [ "$fail" -eq 0 ] && [ "$mixed" -eq 0 ]
