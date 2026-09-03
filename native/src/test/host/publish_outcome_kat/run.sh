#!/usr/bin/env bash
# Host KAT runner for BRPublishOutcome.h -- the publish errno -> action mapping.
#
# Header-only: includes BRPublishOutcome.h from the core submodule and links
# nothing but libc.
#
# ## Why the RED gate is conditional here
#
# The defect being fixed is that core/sync/PublishOutcome.kt hardcoded LINUX
# errno values (ENOTCONN=107, ETIMEDOUT=110). On Linux those literals are
# CORRECT, so the pre-fix shape and the fixed shape are indistinguishable --
# which is exactly why the bug survived review and would have shipped to iOS
# unnoticed. Darwin is 57/60.
#
# So -DPUBLISH_OUTCOME_LINUX_LITERALS_UNFIXED is only a meaningful gate on a
# platform whose ETIMEDOUT is not 110. On such a platform the RED build MUST
# fail, and specifically at test3 (ETIMEDOUT -> UnconfirmedDelivery). On Linux
# the gate is skipped with a printed explanation rather than faked -- a gate that
# cannot go red proves nothing, and pretending otherwise is worse than saying so.
#
# Run this on macOS at least once before trusting the header.
#
# Exit 0 = all checks passed; 1 = a check failed or a gate misbehaved.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
command -v "$CC" >/dev/null 2>&1 || CC=cc

build() {
    local out="$1"; shift
    "$CC" -std=c99 -Wall -Wextra -Wpedantic -Werror "$@" \
    -I "$CORE_DIR" \
    "$SCRIPT_DIR/publish_outcome_kat_main.c" \
    -o "$out"
}

# What is this platform's ETIMEDOUT? Decides whether the gate can be enforced.
cat > "$BUILD_DIR/probe.c" <<'PEOF'
#include <stdio.h>
#include <errno.h>
int main(void) { printf("%d\n", ETIMEDOUT); return 0; }
PEOF
"$CC" -std=c99 "$BUILD_DIR/probe.c" -o "$BUILD_DIR/probe"
PLATFORM_ETIMEDOUT="$("$BUILD_DIR/probe")"
echo "This platform's ETIMEDOUT = $PLATFORM_ETIMEDOUT"

if [ "$PLATFORM_ETIMEDOUT" = "110" ]; then
    echo "SKIP RED gate: ETIMEDOUT is 110 here, so the pre-fix Linux-literal table"
    echo "               is indistinguishable from the fix. Run this on macOS"
    echo "               (ETIMEDOUT 60) to actually exercise the gate."
else
    build "$BUILD_DIR/publish_outcome_kat_unfixed" -DPUBLISH_OUTCOME_LINUX_LITERALS_UNFIXED
    set +e
    "$BUILD_DIR/publish_outcome_kat_unfixed" > "$BUILD_DIR/red.log" 2>&1
    RED_STATUS=$?
    set -e
    if [ "$RED_STATUS" -eq 0 ]; then
        echo "GATE FAILED: the Linux-literal build passed on a platform whose"
        echo "             ETIMEDOUT is $PLATFORM_ETIMEDOUT. The KAT is not testing what it claims."
        sed 's/^/             | /' "$BUILD_DIR/red.log"
        exit 1
    fi
    if ! grep -q "FAIL: test3: ETIMEDOUT is UnconfirmedDelivery" "$BUILD_DIR/red.log"; then
        echo "GATE FAILED: the pre-fix build failed, but not at the ETIMEDOUT checkpoint"
        echo "             -- so the failure is not the defect under test."
        sed 's/^/             | /' "$BUILD_DIR/red.log"
        exit 1
    fi
    echo "RED gate OK: hardcoded Linux literals misclassified a timeout on this platform."
fi

build "$BUILD_DIR/publish_outcome_kat"
"$BUILD_DIR/publish_outcome_kat"
