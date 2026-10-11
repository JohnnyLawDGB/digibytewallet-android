#!/usr/bin/env bash
#
# Every per-suite header-check level pin must be LOAD-BEARING.
#
# The app compiles in two header checks at a level each (native/build.gradle.kts), and
# run-host-kats.sh builds every suite at those shipped levels -- unless the suite names a level
# itself, in which case it keeps it. That escape hatch is necessary: a fixture of synthetic
# headers cannot be judged at difficulty level 2, so some suites must drop to level 1 or nothing
# they test can happen.
#
# It is also a trap. A pin silently builds a suite at a level NO APP SHIPS, so the suite stops
# testing the verdict users actually get. On 2026-10-10 three suites carried the same level-1 pin
# and only two needed it: orphan_set_limits_kat passed at the shipped level on both release
# lines, so its pin had been copied from its siblings and was quietly costing coverage. Nothing
# caught it, because a pin that is not needed looks exactly like one that is.
#
# So each pin has to prove itself. The suite is rebuilt at the shipped levels and must FAIL
# there; if it passes, the pin buys nothing and must go. KAT_FORCE_SHIPPED_LEVELS=1 makes the
# runner's clang shim append the shipped levels, and a later -D wins, so no run.sh is edited.
#
# What counts as a pin, per macro, from the literal -DMACRO=<digit> flags in a suite's run.sh:
#   * two or more different literal levels -> the suite drives the levels itself. Not a pin.
#   * one literal level, equal to the shipped one -> a no-op that documents intent. Not a pin.
#   * one literal level, different from the shipped one -> A PIN. Must fail at the shipped level.
#   * no literal level (a variable such as "$DIFF_LEVEL", "$L", "$1") -> the suite reads the
#     shipped level or is parameterised. Not a pin.
# A suite that needs out of this for some other reason says so in its run.sh:
#     # KAT-LEVEL-SWEEP: <why this suite owns its levels>
#
# Usage: scripts/check-kat-level-pins.sh          (exit 0 = every pin is load-bearing)
#        scripts/check-kat-level-pins.sh --list   (classify only; runs nothing)
#
# Slow by nature -- it runs each pinned suite a second time -- so it belongs on the weekly
# schedule, not on every push.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_DIR="$ROOT/native/src/test/host"
GRADLE="$ROOT/native/build.gradle.kts"

shipped() {   # $1 = macro name
    local found
    found="$(grep -o "D$1=[0-9][0-9]*" "$GRADLE" 2>/dev/null | sort -u)"
    [ "$(printf '%s' "$found" | grep -c .)" -eq 1 ] || return 1
    printf '%s' "${found#D$1=}"
}
POW="$(shipped DGB_HEADER_POW_CHECK)"  || { echo "GATE FAILURE: no single shipped POW level";  exit 1; }
DIFF="$(shipped DGB_HEADER_DIFF_CHECK)" || { echo "GATE FAILURE: no single shipped DIFF level"; exit 1; }
echo "shipped levels: proof of work $POW, difficulty target $DIFF (from native/build.gradle.kts)"
echo

declare -a PINNED=() REASON=()
skipped=""

for run in "$HOST_DIR"/*/run.sh; do
    suite="$(basename "$(dirname "$run")")"
    if grep -q 'KAT-LEVEL-SWEEP' "$run"; then
        skipped+="  $suite: declares KAT-LEVEL-SWEEP"$'\n'; continue
    fi
    grep -qE -- '-D(DGB_HEADER_POW_CHECK|DGB_HEADER_DIFF_CHECK)=' "$run" || continue
    why=""
    for m in DGB_HEADER_POW_CHECK DGB_HEADER_DIFF_CHECK; do
        # Comments are stripped first. These files SHOULD discuss their levels in prose -- the
        # removal note in orphan_set_limits_kat names the flag it dropped -- and a gate that a
        # comment can trip is a gate that discourages the documentation.
        mapfile -t lits < <(sed 's/\(^\|[[:space:]]\)#.*$//' "$run" \
                            | grep -oE -- "-D$m=[0-9]+" | grep -oE '[0-9]+$' | sort -u)
        [ "${#lits[@]}" -eq 0 ] && continue
        if [ "${#lits[@]}" -gt 1 ]; then
            skipped+="  $suite: drives $m itself (levels ${lits[*]})"$'\n'; continue
        fi
        if [ "$m" = "DGB_HEADER_POW_CHECK" ]; then ship="$POW"; else ship="$DIFF"; fi
        if [ "${lits[0]}" = "$ship" ]; then
            skipped+="  $suite: $m=${lits[0]} equals the shipped level (no-op)"$'\n'
        else
            why+="${why:+, }$m=${lits[0]} vs shipped $ship"
        fi
    done
    if [ -n "$why" ]; then PINNED+=("$suite"); REASON+=("$why"); fi
done

[ -n "$skipped" ] && { echo "not pins:"; printf '%s' "$skipped"; echo; }

if [ "${#PINNED[@]}" -eq 0 ]; then echo "PASS: no pins to justify"; exit 0; fi
echo "pins to justify: ${#PINNED[@]}"
for i in "${!PINNED[@]}"; do echo "  ${PINNED[$i]} (${REASON[$i]})"; done
echo
if [ "${1:-}" = "--list" ]; then
    echo "(--list: classification only, nothing run)"
    exit 0
fi

fail=0
for i in "${!PINNED[@]}"; do
    suite="${PINNED[$i]}"
    echo "--- $suite must FAIL at the shipped levels"
    out="$(KAT_FORCE_SHIPPED_LEVELS=1 "$ROOT/scripts/run-host-kats.sh" "$suite" 2>&1)"
    if printf '%s\n' "$out" | grep -qE '^host KATs: 0 passed, 1 failed'; then
        echo "    load-bearing: fails at the shipped level, so the pin is required."
    elif printf '%s\n' "$out" | grep -qE '^host KATs: 1 passed, 0 failed'; then
        echo "    GATE FAILURE: it PASSES at the shipped levels, so the pin buys nothing."
        echo "    Drop the flag from native/src/test/host/$suite/run.sh and let the runner"
        echo "    supply the shipped level, or add a KAT-LEVEL-SWEEP line saying why not."
        fail=1
    else
        echo "    GATE FAILURE: could not read a verdict from the runner (build error?)."
        printf '%s\n' "$out" | tail -6 | sed 's/^/        /'
        fail=1
    fi
    echo
done

if [ "$fail" -eq 0 ]; then
    echo "PASS: check-kat-level-pins (${#PINNED[@]} pin(s), each proven load-bearing)"
    exit 0
fi
echo "FAIL: check-kat-level-pins"
exit 1
