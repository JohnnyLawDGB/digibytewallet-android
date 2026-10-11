#!/usr/bin/env bash
# Source/build gate: BRPaymentProtocol.c (BIP70/BIP75 messages) is not compiled into the native library
# while nothing calls it.
#
# The file has no caller in the app: no core or bridge source names its API, no JNI function wraps it,
# and no Kotlin declares one. A source with no caller is surface without purpose, so it is kept out of
# the build rather than guarded. The file itself stays in the core tree (upstream test.c and the iOS
# module map still name it); only the Android build leaves it out.
#
# The gate fails when
#   1. native/CMakeLists.txt names BRPaymentProtocol.c or BRPaymentProtocol.h (comments ignored);
#   2. native/CMakeLists.txt gathers sources by glob (file(GLOB...) or aux_source_directory), which could
#      bring the file back without naming it;
#   3. a source the build compiles, or any core or bridge header, names BRPaymentProtocol -- a caller
#      exists again, and the file must not re-enter the build until its length handling is reviewed
#      (lengths read from a message compared without wrapping);
#   4. native/build.gradle.kts stops pointing its CMake build at native/CMakeLists.txt (the file this
#      gate reads would no longer be the build).
#
# COMPARISON ARMS, run every time: the same checks are applied to copies of CMakeLists.txt that put the
# file back by name, by glob, and through a compiled source that calls into it. Each MUST be refused, or
# the gate cannot see what it governs. PAYMENT_PROTOCOL_GATE_CMAKE=<path> points the main check at
# another CMakeLists.txt (used to show the gate refuses an earlier build).
set -uo pipefail   # not -e: the exit codes of the checks are inspected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
NATIVE_DIR="$REPO_ROOT/native"
JNI_DIR="$NATIVE_DIR/src/main/jni"
CMAKE="${PAYMENT_PROTOCOL_GATE_CMAKE:-$NATIVE_DIR/CMakeLists.txt}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# gate <CMakeLists.txt>: prints each violation; returns 0 when there is none.
gate() {
    local cm="$1" code bad=0 src f
    if [ ! -f "$cm" ]; then echo "    cannot read $cm"; return 1; fi
    code="$(sed 's/#.*$//' "$cm")"   # CMake comments run from # to the end of the line

    if printf '%s\n' "$code" | grep -nE 'BRPaymentProtocol\.(c|h)' >"$WORK_DIR/named"; then
        sed 's/^/    names the file: line /' "$WORK_DIR/named"; bad=1
    fi
    if printf '%s\n' "$code" | grep -niE 'file[[:space:]]*\([[:space:]]*GLOB|aux_source_directory' >"$WORK_DIR/glob"; then
        sed 's/^/    gathers sources by glob: line /' "$WORK_DIR/glob"; bad=1
    fi

    # Every .c the build compiles: the .c entries inside add_library(...) and target_sources(...), resolved
    # against native/ (strings elsewhere, e.g. in message(), are not sources).
    printf '%s\n' "$code" | awk '
        /^[ \t]*(add_library|target_sources)[ \t]*\(/ { inblock = 1 }
        inblock { n = split($0, t, /[ \t()"]+/); for (i = 1; i <= n; i++) if (t[i] ~ /\.c$/) print t[i] }
        inblock && /\)/ { inblock = 0 }' | sort -u >"$WORK_DIR/compiled"
    if [ ! -s "$WORK_DIR/compiled" ]; then echo "    found no compiled .c in $cm -- cannot check callers"; return 1; fi
    while read -r src; do
        case "$src" in /*) f="$src" ;; *) f="$NATIVE_DIR/$src" ;; esac
        if [ ! -f "$f" ]; then echo "    compiled source not found: $src"; bad=1; continue; fi
        if grep -q 'BRPaymentProtocol' "$f"; then echo "    a compiled source names BRPaymentProtocol: $src"; bad=1; fi
    done <"$WORK_DIR/compiled"
    return $bad
}

headers_clean() {   # no core or bridge header other than BRPaymentProtocol.h names the API
    local hits
    hits="$(grep -rlE 'BRPaymentProtocol' --include='*.h' "$JNI_DIR/bridge" "$JNI_DIR/digibytewallet-core" 2>/dev/null |
            grep -v '/digibytewallet-core/secp256k1/' | grep -v '/BRPaymentProtocol\.h$')"
    [ -z "$hits" ] && return 0
    printf '%s\n' "$hits" | sed "s#^$REPO_ROOT/#    a header names BRPaymentProtocol: #"; return 1
}

FAIL=0
if [ ! -f "$JNI_DIR/digibytewallet-core/BRPaymentProtocol.c" ]; then
    echo "note: BRPaymentProtocol.c is no longer in the core tree; the build checks below still apply"
fi

echo "--- the build: $CMAKE ---"
if gate "$CMAKE"; then echo "  BRPaymentProtocol.c is not compiled, and no compiled source calls into it"
else echo "  GATE FAILURE: see above"; FAIL=1; fi
if headers_clean; then echo "  no core or bridge header names the API"; else echo "  GATE FAILURE: see above"; FAIL=1; fi
if grep -qE 'path\("CMakeLists\.txt"\)' "$NATIVE_DIR/build.gradle.kts"; then echo "  native/build.gradle.kts builds native/CMakeLists.txt"
else echo "  GATE FAILURE: native/build.gradle.kts no longer builds native/CMakeLists.txt; this gate reads the wrong file"; FAIL=1; fi

echo "--- COMPARISON ARMS: each copy puts the file back and MUST be refused ---"
must_refuse() {   # <label> <CMakeLists copy>
    if gate "$2" >"$WORK_DIR/out" 2>&1; then echo "  [$1] GATE FAILURE: accepted; the gate cannot see this"; FAIL=1
    else echo "  [$1] refused: $(head -1 "$WORK_DIR/out" | sed 's/^ *//')"; fi
}
BASE="$NATIVE_DIR/CMakeLists.txt"
# by name: listed next to BRMerkleBlock.c, where it used to sit
sed 's#^\([[:space:]]*\)src/main/jni/digibytewallet-core/BRMerkleBlock\.c#&\n\1src/main/jni/digibytewallet-core/BRPaymentProtocol.c#' "$BASE" >"$WORK_DIR/named.cmake"
grep -q 'BRPaymentProtocol\.c' "$WORK_DIR/named.cmake" || { echo "  GATE FAILURE: could not build the by-name copy (BRMerkleBlock.c line not found)"; FAIL=1; }
must_refuse "by name" "$WORK_DIR/named.cmake"
# by glob over the core directory
{ cat "$BASE"; echo 'file(GLOB CORE_SOURCES src/main/jni/digibytewallet-core/*.c)'; echo 'target_sources(core-lib PRIVATE ${CORE_SOURCES})'; } >"$WORK_DIR/glob.cmake"
must_refuse "by glob" "$WORK_DIR/glob.cmake"
# through a compiled source that calls into it
cat >"$WORK_DIR/caller.c" <<'C'
#include "BRPaymentProtocol.h"
void *payment_request_from(const unsigned char *b, unsigned long n) { return BRPaymentProtocolRequestParse(b, n); }
C
{ cat "$BASE"; echo "target_sources(core-lib PRIVATE $WORK_DIR/caller.c)"; } >"$WORK_DIR/caller.cmake"
must_refuse "through a caller" "$WORK_DIR/caller.cmake"

echo
if [ "$FAIL" -eq 0 ]; then echo "PASS: payment_protocol_unbuilt_kat"; exit 0; else echo "FAIL: payment_protocol_unbuilt_kat"; exit 1; fi
