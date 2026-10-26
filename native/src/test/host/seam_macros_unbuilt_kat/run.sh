#!/usr/bin/env bash
# Host gate: no Android build input defines a test seam.
#
# The C sources carry comparison-arm seams -- macros named *_UNFIXED or *_RED -- that host KATs
# define to build the code as it was before a fix (BRPreparedSend.h's PREPARED_SEND_UNFIXED turns the
# signer's check into "always OK"), and test-only hooks named *_KAT_* / KAT_* that count or expose
# internals. Defined in an app build, any of them ships the unfixed code or a test hook. Nothing in
# the sources can stop a build from defining one, so this gate reads what an app build is made from:
#
#   native/CMakeLists.txt and any other CMake file under native/ (outside the core's vendored
#   secp256k1, which the app build does not run: BRKey.c includes its sources directly), every Gradle
#   script and gradle.properties in the repository, and the release build script -- which between
#   them hold every -D, cFlags and CMake argument the Android build passes.
#
# It fails when any of them names a seam or a hook outside a comment. The rule is the naming pattern,
# so it covers every seam, including ones added later. Sections:
#   [1] the inventory: every seam and hook the native sources test in a preprocessor condition (not
#       blind: it must find many, including the ones this package added)
#   [2] COMPARISON ARM: for every inventoried name, a temp copy of the build inputs gets a define of it
#       planted (seven forms, in turn: add_definitions, target_compile_definitions, CMAKE_C_FLAGS, a
#       Gradle cFlags entry, a Gradle CMake argument, a property, another module's script, and the
#       release script) and the gate MUST catch each one, by name. Plus names no source carries yet.
#       A planted define inside a comment must NOT be reported (comments build nothing).
#   [3] the real build inputs: clean.
# GUARD: nothing defines a seam today, so section [3] cannot be red; section [2] is the proof the gate
# can see one.
#
# Exit code 0 = every expectation held, 1 = otherwise.
set -uo pipefail   # not -e: the comparison arm's nonzero exits are inspected
# Under pipefail nothing here pipes into a reader that can exit early (grep -q, grep -m1, head):
# here-strings and files only.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
SRC_DIR="$REPO_ROOT/native/src/main/jni"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SEAM_RE='\b[A-Z][A-Z0-9_]*_(UNFIXED|RED)\b'
HOOK_RE='\b([A-Z][A-Z0-9_]*_KAT_[A-Z0-9_]+|KAT_[A-Z0-9_]+|[A-Z][A-Z0-9_]*_KAT)\b'

# The build inputs under a root, one relative path per line.
build_inputs() {
    local root="$1"
    ( cd "$root" || exit 1
      find native -maxdepth 6 \( -path 'native/src/main/jni/digibytewallet-core/secp256k1' -o -path '*/build' -o -path '*/.cxx' \) -prune -o \
           \( -name CMakeLists.txt -o -name '*.cmake' \) -type f -print
      find . -maxdepth 3 \( -path './native/src' -o -path '*/build' -o -path './.gradle' -o -path '*/.cxx' \) -prune -o \
           \( -name '*.gradle.kts' -o -name '*.gradle' -o -name 'gradle.properties' \) -type f -print | sed 's@^\./@@'
      [ -f scripts/build-release.sh ] && echo scripts/build-release.sh
    ) | sort -u
}

# A file with its comments blanked. Gradle (Kotlin) scripts: '//' and '/* */' outside string literals
# (a string can hold either, e.g. a "prebuilt/*/lib" path); CMake: '#' to the end of the line;
# properties: lines starting with '#' or '!'; shell: '#' at the start of a line or after a blank.
uncommented() {
    local f="$1"
    case "$f" in
        *.kts|*.gradle)
            awk 'BEGIN { st = 0 }   # 0 code, 1 block comment, 2 string
            {
                line = $0; out = ""; n = length(line); i = 1
                while (i <= n) {
                    c = substr(line, i, 1); d = substr(line, i, 2)
                    if (st == 1) { if (d == "*/") { st = 0; i += 2 } else i++; continue }
                    if (st == 2) {
                        if (c == "\\") { out = out d; i += 2; continue }
                        if (c == "\"") st = 0
                        out = out c; i++; continue
                    }
                    if (d == "/*") { st = 1; i += 2; continue }
                    if (d == "//") break
                    if (c == "\"") st = 2
                    out = out c; i++
                }
                if (st == 2) st = 0   # a string does not run past its line
                print out
            }' "$f" ;;
        *.properties) sed -E '/^[[:space:]]*[#!]/d' "$f" ;;
        *.sh) sed -E 's/(^|[[:space:]])#.*$//' "$f" ;;
        *) sed -e 's/#.*$//' "$f" ;;
    esac
}

# scan <root>: prints "path:line: TOKEN" for every seam or hook named outside a comment; exit 1 if any.
scan() {
    local root="$1" found=0 rel code hits
    local inputs; inputs="$(build_inputs "$root")"
    [ -n "$inputs" ] || { echo "  scan: no build inputs under $root -- blind"; return 2; }
    [ -f "$root/native/CMakeLists.txt" ] || { echo "  scan: native/CMakeLists.txt missing under $root -- blind"; return 2; }
    while IFS= read -r rel; do
        [ -n "$rel" ] || continue
        # A define is usually written "-DNAME": split the prefix off so a name with no prefix of its
        # own (KAT_...) still starts at a word boundary.
        code="$(uncommented "$root/$rel" | sed -e 's/-D/-D /g')"
        hits="$(grep -noE -- "$SEAM_RE|$HOOK_RE" <<<"$code")"
        if [ -n "$hits" ]; then
            found=1
            while IFS= read -r h; do echo "  $rel:$h"; done <<<"$hits"
        fi
    done <<<"$inputs"
    return $found
}

FAIL=0

# ---- [1] inventory -------------------------------------------------------------------------
echo "=== [1/3] the seams and hooks the native sources test ==="
COND="$(grep -rhE '^[[:space:]]*#[[:space:]]*(if|ifdef|ifndef|elif)\b' "$SRC_DIR" --include='*.c' --include='*.h')"
INVENTORY="$(grep -oE -- "$SEAM_RE|$HOOK_RE" <<<"$COND" | sort -u)"
N_INV="$(grep -c . <<<"$INVENTORY")"
echo "  $N_INV distinct names in preprocessor conditions under native/src/main/jni"
if [ "$N_INV" -lt 50 ]; then echo "  GATE FAILURE: the inventory found only $N_INV names -- blind, not clean"; FAIL=1; fi
for must in PREPARED_SEND_UNFIXED REJECT_QUORUM_UNFIXED FEE_POLICY_RED WALLET_KAT_COUNT_REBUILD PUBLISH_REJECT_UNFIXED; do
    if ! grep -qx -- "$must" <<<"$INVENTORY"; then echo "  GATE FAILURE: the inventory misses $must -- blind"; FAIL=1; fi
done

# ---- [2] comparison arm --------------------------------------------------------------------
echo "=== [2/3] COMPARISON ARM: a define of every inventoried name, planted in a temp copy, is caught by name ==="
INPUTS="$(build_inputs "$REPO_ROOT")"
fresh_copy() {   # a temp copy of the real build inputs, same relative paths
    rm -rf "$WORK/tree"; mkdir -p "$WORK/tree"
    while IFS= read -r rel; do
        [ -n "$rel" ] || continue
        mkdir -p "$WORK/tree/$(dirname "$rel")"
        cp "$REPO_ROOT/$rel" "$WORK/tree/$rel"
    done <<<"$INPUTS"
}
# plant <form 0-7> <NAME> into $WORK/tree; returns 1 if the form's anchor is missing (a blind plant).
plant() {
    local form="$1" name="$2" t="$WORK/tree"
    case "$form" in
        0) printf '\nadd_definitions(-D%s=1)\n' "$name" >> "$t/native/CMakeLists.txt" ;;
        1) printf '\ntarget_compile_definitions(core-lib PRIVATE %s)\n' "$name" >> "$t/native/CMakeLists.txt" ;;
        2) grep -q -- '-fvisibility=hidden")' "$t/native/CMakeLists.txt" || return 1
           sed -i "0,/-fvisibility=hidden\")/s//-fvisibility=hidden -D$name\")/" "$t/native/CMakeLists.txt" ;;
        3) grep -q -- '"-DANDROID_TOOLCHAIN=clang",' "$t/native/build.gradle.kts" || return 1
           sed -i "0,/\"-DANDROID_TOOLCHAIN=clang\",/s//\"-DANDROID_TOOLCHAIN=clang\", \"-D$name=1\",/" "$t/native/build.gradle.kts" ;;
        4) grep -q -- 'arguments("-DANDROID_TOOLCHAIN=clang")' "$t/native/build.gradle.kts" || return 1
           sed -i "s/arguments(\"-DANDROID_TOOLCHAIN=clang\")/arguments(\"-DANDROID_TOOLCHAIN=clang\", \"-DCMAKE_C_FLAGS=-D$name\")/" "$t/native/build.gradle.kts" ;;
        5) printf '\nnativeExtraCFlags=-D%s\n' "$name" >> "$t/gradle.properties" ;;
        6) printf '\nval extraNativeFlag = "-D%s"\n' "$name" >> "$t/app/build.gradle.kts" ;;
        7) [ -f "$t/scripts/build-release.sh" ] || return 1
           printf '\nGRADLE_EXTRA="-Pandroid.cflags=-D%s"\n' "$name" >> "$t/scripts/build-release.sh" ;;
    esac
}
FORMS=8
k=0; caught=0; missed=0
NAMES="$INVENTORY"$'\n'"UNLISTED_FUTURE_CHECK_UNFIXED"$'\n'"UNLISTED_FUTURE_GATE_RED"$'\n'"UNLISTED_COUNTER_KAT_HOOK"
while IFS= read -r name; do
    [ -n "$name" ] || continue
    form=$(( k % FORMS )); k=$((k + 1))
    fresh_copy
    if ! plant "$form" "$name"; then echo "  GATE FAILURE: form $form found no anchor to plant $name -- the arm is blind"; FAIL=1; continue; fi
    out="$(scan "$WORK/tree")"; rc=$?
    if [ $rc -eq 1 ] && grep -q -- "$name" <<<"$out"; then
        caught=$((caught + 1))
    else
        missed=$((missed + 1)); FAIL=1
        echo "  GATE FAILURE: a define of $name planted in form $form was not caught (scan rc=$rc)"
        [ -n "$out" ] && sed 's/^/      /' <<<"$out"
    fi
done <<<"$NAMES"
echo "  caught $caught of $((caught + missed)) planted defines (forms 0-$((FORMS - 1)) in turn), each by name"
# Comments build nothing: a define written only inside comments is not reported.
fresh_copy
printf '\n# add_definitions(-DPREPARED_SEND_UNFIXED=1)\n' >> "$WORK/tree/native/CMakeLists.txt"
printf '\n// cFlags("-DREJECT_QUORUM_UNFIXED")\n/* "-DFEE_POLICY_RED" */\n' >> "$WORK/tree/native/build.gradle.kts"
printf '\n# nativeExtraCFlags=-DWALLET_KAT_COUNT_REBUILD\n' >> "$WORK/tree/gradle.properties"
out="$(scan "$WORK/tree")"; rc=$?
if [ $rc -eq 0 ]; then echo "  comments only: not reported, as it must be"
else echo "  GATE FAILURE: a define inside comments was reported (rc=$rc):"; sed 's/^/      /' <<<"$out"; FAIL=1; fi

# ---- [3] the real build inputs --------------------------------------------------------------
echo "=== [3/3] the real build inputs ==="
echo "  reading: $(tr '\n' ' ' <<<"$INPUTS")"
out="$(scan "$REPO_ROOT")"; rc=$?
if [ $rc -eq 0 ]; then
    echo "  clean: no build input names a seam or a hook"
else
    echo "  GATE FAILURE: an Android build input names a test seam or hook (rc=$rc):"; sed 's/^/      /' <<<"$out"; FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: seam_macros_unbuilt_kat ($N_INV seams and hooks inventoried; $caught planted defines caught by name; the real build inputs are clean)"
    exit 0
fi
echo "FAIL: seam_macros_unbuilt_kat"
exit 1
