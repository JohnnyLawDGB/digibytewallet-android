#!/usr/bin/env bash
# The coverage gate after the shards: every suite in the plan ran, in the shard it was planned
# for, and passed.
#
#   check.sh <plan dir> <results dir> <shard count> <floor>
#
# <plan dir> holds all.txt and shard-<i>.txt (plan.py); <results dir> holds results-<i>.txt
# (run-shard.sh). Fails if a shard's results are missing, a planned suite has no PASS line from
# its own shard, the shards' union differs from all.txt, a suite outside the plan ran, or fewer
# than <floor> suites passed (a checkout with few or no suites). Writes <results dir>/timings.txt
# in the format of .github/kat-shards/timings.txt.
#
# ORIGIN: a copy of the core repo's .github/kat-shards/check.sh (see plan.py). Differences:
#   - the header line of the timings it writes names the repository the run belongs to;
#   - it prints the compiler and header check levels the shards reported (results-<i>.toolchain,
#     from this repo's run-shard.sh) and warns if the shards did not all use the same ones. A
#     warning, not a failure: each shard is its own runner, and GitHub replaces the runner image
#     a few machines at a time, so two shards of one run can differ through no fault of the change
#     under test. Within one shard a difference does fail (run-shard.sh).
set -euo pipefail
export LC_ALL=C

plan="$1"; results="$2"; count="$3"; floor="$4"
bad=0
err() { echo "::error::$*"; bad=1; }

[ -s "$plan/all.txt" ] || { echo "::error::no plan (all.txt missing or empty)"; exit 1; }

: > "$results/union.txt"
: > "$results/passed.txt"
for (( i = 0; i < count; i++ )); do
    want="$plan/shard-$i.txt"; got="$results/results-$i.txt"
    [ -f "$want" ] || { err "shard $i: no plan file"; continue; }
    cat "$want" >> "$results/union.txt"
    if [ ! -f "$got" ]; then
        err "shard $i: no results (the shard did not finish)"
        continue
    fi
    awk '$2 == "PASS" { print $1 }' "$got" >> "$results/passed.txt"
    missing="$(comm -23 <(sort -u "$want") <(awk '$2 == "PASS" { print $1 }' "$got" | sort -u))"
    if [ -n "$missing" ]; then
        err "shard $i: planned but not passed: $(tr '\n' ' ' <<<"$missing")"
    fi
done

all="$(sort "$plan/all.txt")"
union="$(sort "$results/union.txt")"
if [ "$union" != "$all" ]; then
    err "the shards' union is not the suite list"
    diff <(cat <<<"$all") <(cat <<<"$union") || true
fi
dups="$(sort "$results/union.txt" | uniq -d)"
[ -z "$dups" ] || err "planned in more than one shard: $(tr '\n' ' ' <<<"$dups")"

extra="$(comm -13 <(cat <<<"$all") <(sort -u "$results/passed.txt"))"
[ -z "$extra" ] || err "ran but not in the plan: $(tr '\n' ' ' <<<"$extra")"

passed="$(comm -12 <(cat <<<"$all") <(sort -u "$results/passed.txt") | grep -c . || true)"
total="$(grep -c . <<<"$all")"
echo "coverage: $passed of $total planned suites passed across $count shards"
[ "$passed" -ge "$floor" ] || err "only $passed host KAT suites passed (expected at least $floor)"

# What built the suites. One compiler line and one levels line if every shard agreed.
toolchains="$(for (( i = 0; i < count; i++ )); do
    if [ -f "$results/results-$i.toolchain" ]; then cat "$results/results-$i.toolchain"; fi
done | sort -u)"
if [ -n "$toolchains" ]; then
    cat <<<"$toolchains"
    if [ "$(grep -c . <<<"$toolchains")" -gt 2 ]; then
        echo "::warning::the shards did not all use the same compiler and header check levels"
    fi
fi

# Timings for the next plan: each suite's seconds in the shard that planned it.
{
    echo "# Seconds per host KAT on a GitHub ubuntu-latest runner, from ${GITHUB_REPOSITORY:-a local} CI run ${GITHUB_RUN_ID:-(no run id)}."
    for (( i = 0; i < count; i++ )); do
        [ -f "$results/results-$i.txt" ] || continue
        awk 'NR == FNR { want[$1] = 1; next } ($1 in want) && $2 == "PASS" { print $3, $1 }' \
            "$plan/shard-$i.txt" "$results/results-$i.txt"
    done | sort -k2
} > "$results/timings.txt"

exit "$bad"
