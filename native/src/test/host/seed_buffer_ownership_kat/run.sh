#!/usr/bin/env bash
#
# Host KAT runner for seed_buffer_ownership_kat — security cycle v4.0.58, finding 1.
#
# Guards the rule that native code must not write through a JNI array pointer it
# does not own. See jni_seed_buffer.h for why, and the _main.c header for how the
# pre-fix shape turned that into wrong-key signing in the legacy-sweep loop.
#
# THREE CHECKS, and the third is the one that matters most
# --------------------------------------------------------
#   [1] RED   — the -DSEED_BUFFER_UNFIXED arm (the v4.0.58 shape) must FAIL, at
#               the specific assertions about the caller's buffer, having first
#               reached test1. A merely non-zero exit is not accepted: that is
#               also what a build error looks like, and a gate that can go red on
#               a compile error proves nothing.
#   [2] GREEN — the production arm must print ALL PASS.
#   [3] WIRED — jni_derive.c must actually USE the header. [1] and [2] only prove
#               the header is correct; they would both stay green if the fix were
#               written and never called. A previous gate in this repo passed
#               while observing nothing because a rename moved the code out from
#               under its seam, so this check asserts a POSITIVE count of the new
#               API and a ZERO count of the pattern it replaced.
set -uo pipefail   # deliberately NOT -e: the RED arm's non-zero exit is expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
BRIDGE_DIR="$REPO_ROOT/native/src/main/jni/bridge"
DERIVE_C="$BRIDGE_DIR/jni_derive.c"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

build() { # $1=output  $2...=extra clang flags
    local out="$1"; shift
    clang -w -include stdint.h -fsanitize=address -fno-omit-frame-pointer -g "$@" \
        -I "$BRIDGE_DIR" \
        "$SCRIPT_DIR/seed_buffer_ownership_kat_main.c" \
        -o "$out"
}

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"

# ---------------------------------------------------------------- [1] RED ----
echo "=== red-before-green [1/3]: UNFIXED (v4.0.58 shape) must FAIL ==="
if ! build "$BUILD_DIR/unfixed" -DSEED_BUFFER_UNFIXED; then
    echo "GATE FAILED: unfixed build error"
    exit 1
fi
"$BUILD_DIR/unfixed" > "$BUILD_DIR/red.log" 2>&1
RED_EXIT=$?
if [ "$RED_EXIT" -eq 0 ]; then
    echo "GATE FAILED: the unfixed shape did NOT fail (exit 0) — the gate cannot"
    echo "             detect the bug it exists for. Output was:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "PASS: test1: all 64 bytes reached the private copy" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed run never reached test1 — that is a broken harness,"
    echo "             not a real RED:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "FAIL: test4: release() left all 64 source bytes intact" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed run exited $RED_EXIT but NOT by clobbering the"
    echo "             caller's buffer — so -DSEED_BUFFER_UNFIXED is no longer"
    echo "             reproducing the pre-fix shape:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
if ! grep -q "profiles that derived from an intact seed: 1 of 3" "$BUILD_DIR/red.log"; then
    echo "GATE FAILED: the unfixed run did not show the sweep loop losing profiles 2 and 3."
    echo "             That loss IS the funds-path consequence; without it this is not the bug:"
    sed 's/^/             | /' "$BUILD_DIR/red.log"
    exit 1
fi
echo "RED confirmed — the pre-fix shape wipes the caller's seed and the sweep loop loses profiles:"
grep -E "FAIL: test(2|4)|profiles that derived" "$BUILD_DIR/red.log" | sed 's/^/    | /'

# -------------------------------------------------------------- [2] GREEN ----
echo "=== red-before-green [2/3]: production shape must be CLEAN ==="
if ! build "$BUILD_DIR/fixed"; then
    echo "GATE FAILED: production-shape build error"
    exit 1
fi
"$BUILD_DIR/fixed" > "$BUILD_DIR/green.log" 2>&1
GREEN_EXIT=$?
if [ "$GREEN_EXIT" -ne 0 ]; then
    echo "GATE FAILED: production shape failed (exit $GREEN_EXIT):"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
if ! grep -q "ALL PASS" "$BUILD_DIR/green.log"; then
    echo "GATE FAILED: production shape did not print ALL PASS:"
    sed 's/^/             | /' "$BUILD_DIR/green.log"
    exit 1
fi
echo "GREEN confirmed:"
sed 's/^/    | /' "$BUILD_DIR/green.log"

# -------------------------------------------------------------- [3] WIRED ----
echo "=== [3/3]: jni_derive.c must actually use the header ==="
if [ ! -f "$DERIVE_C" ]; then
    echo "GATE FAILED: $DERIVE_C not found — the file was moved or renamed, and this"
    echo "             check would otherwise silently observe nothing."
    exit 1
fi

# A POSITIVE count: the three seed-taking entry points must each take a copy.
TAKES=$(grep -c 'seed_buffer_take' "$DERIVE_C")
if [ "$TAKES" -lt 3 ]; then
    echo "GATE FAILED: jni_derive.c calls seed_buffer_take() $TAKES time(s); expected at"
    echo "             least 3 (deriveAddresses, derivePrivateKeyWIF, buildAndSignLegacySweep)."
    echo "             The header can be perfect and still unused."
    exit 1
fi

if ! grep -q '#include "jni_seed_buffer.h"' "$DERIVE_C"; then
    echo "GATE FAILED: jni_derive.c does not include jni_seed_buffer.h"
    exit 1
fi

# A ZERO count: nothing may wipe a pointer obtained from GetByteArrayElements.
# Matches the identifier the pre-fix code used for it, in any zeroing call.
OFFENDERS=$(grep -nE '(secure_zero|seed_buffer_wipe|memset)\s*\(\s*(\(void\s*\*\)\s*)?(seedRaw|phraseRaw)' "$DERIVE_C" || true)
if [ -n "$OFFENDERS" ]; then
    echo "GATE FAILED: jni_derive.c still zeroes a JNI-owned buffer:"
    echo "$OFFENDERS" | sed 's/^/             | /'
    echo "             JNI_ABORT can only discard writes to a COPY. Copy first"
    echo "             (seed_buffer_take), then wipe the copy."
    exit 1
fi
echo "WIRED confirmed: seed_buffer_take() used $TAKES times, no JNI-owned buffer is wiped."

echo "seed_buffer_ownership_kat: PASS (RED on the v4.0.58 shape, GREEN on production, WIRED into jni_derive.c)"
exit 0
