#!/usr/bin/env bash
# Guardrail: every submodule pin this repo ships MUST be reachable from a durable
# branch on the core fork — not merely from a feature branch someone may delete.
#
# Why this exists (2026-08-19..22):
#   * A parent branch once pinned a core commit that had NEVER been pushed at all.
#     It survived only in two local worktrees; a `git worktree prune` would have
#     destroyed the C implementation while leaving the branch pointing at a phantom.
#   * Later, three SHIPPED releases (v4.0.41/.42/.43/.44) had pins that existed only
#     on core feature branches. Deleting any one of those branches would have left a
#     release tag pointing at a commit nobody could fetch.
#
# A pin that resolves on your laptop proves nothing — the object is in your local
# store. This checks the FORK.
#
# The rule is CONTAINMENT: the pin must equal, or be an ancestor of, the tip of a
# durable branch. A pin BEHIND the tip passes (decided 2026-10-10). The core has two
# consumers, Android and iOS, and requiring EQUALITY forced both pins to move the same
# day as every core push. Equality was only ever required because CI checks the
# submodule out at depth 1, where `merge-base --is-ancestor` cannot see the history
# between pin and tip (it reddened CI on 2026-08-31 and 2026-09-03). This script now
# fetches that history before asking, so a shallow checkout gets the same answer as a
# full clone.
#
# The same script lives in both consumers (digibytewallet-ios/Scripts/check-core-pin.sh,
# digibytewallet-android/scripts/check-submodule-pin.sh), differing only in SUB_PATH.
# Change both together.
set -euo pipefail

CORE_REMOTE="${CORE_REMOTE:-git@github.com:JohnnyLawDGB/digibytewallet-core.git}"
SUB_PATH="native/src/main/jni/digibytewallet-core"
DURABLE="${DURABLE:-develop master}"
REF="${1:-HEAD}"

PIN="$(git ls-tree "$REF" "$SUB_PATH" | awk '{print $3}')"
if [ -z "$PIN" ]; then
    echo "FAIL: no submodule pin recorded at $REF for $SUB_PATH"
    exit 1
fi
echo "pin at $REF: $PIN"

# Ask the REMOTE what each durable branch points at, then check containment against
# history fetched from there. Deliberately not `git cat-file` against the local store:
# local reachability is exactly the false positive this script exists to catch.
cd "$SUB_PATH"
# An uninitialised submodule is an empty directory, and git run there acts on the
# PARENT repo: it would fetch core history into it and answer about the wrong store.
if [ "$(git rev-parse --show-toplevel 2>/dev/null)" != "$(pwd -P)" ]; then
    echo "FAIL: $SUB_PATH is not checked out. Run: git submodule update --init"
    exit 2
fi

UNVERIFIED=""
for b in $DURABLE; do
    # --exit-code: 2 means the branch does not exist (skip it); anything else non-zero
    # means the remote could not be asked, which must not read as "not contained".
    rc=0
    tip="$(git ls-remote --exit-code "$CORE_REMOTE" "refs/heads/$b" 2>/dev/null | awk '{print $1}')" || rc=$?
    if [ "$rc" -eq 2 ]; then continue; fi
    if [ "$rc" -ne 0 ] || [ -z "$tip" ]; then
        UNVERIFIED="$UNVERIFIED $b"
        continue
    fi
    if [ "$PIN" = "$tip" ]; then
        echo "OK: pin equals the tip of core '$b' ($tip)"
        exit 0
    fi
    # A shallow store (CI) holds the pin and nothing behind it, so ancestry would read
    # as "not contained". Fetch the branch's full history first.
    if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
        fetched="$(git fetch -q --unshallow "$CORE_REMOTE" "refs/heads/$b" 2>&1 && echo yes || true)"
    else
        fetched="$(git fetch -q "$CORE_REMOTE" "refs/heads/$b" 2>&1 && echo yes || true)"
    fi
    if [ "${fetched##*$'\n'}" != "yes" ]; then
        UNVERIFIED="$UNVERIFIED $b"
        continue
    fi
    if git merge-base --is-ancestor "$PIN" "$tip" 2>/dev/null; then
        behind="$(git rev-list --count "$PIN..$tip")"
        echo "OK: pin is contained in core '$b' ($behind commit(s) behind its tip $tip)"
        exit 0
    fi
done

if [ -n "$UNVERIFIED" ]; then
    cat <<MSG
FAIL: could not fetch core history to verify the pin (branches:$UNVERIFIED).

  pin:    $PIN
  remote: $CORE_REMOTE

This is a fetch failure, not a verdict on the pin. Check network and credentials,
or set CORE_REMOTE (e.g. https://github.com/JohnnyLawDGB/digibytewallet-core.git).
MSG
    exit 2
fi

cat <<MSG
FAIL: the submodule pin is NOT contained in any durable core branch ($DURABLE).

  pin: $PIN

It may still resolve locally, or sit on a feature branch — neither is durable. If
that branch is deleted the pin dangles and this release becomes unbuildable from a
fresh clone.

Fix: land the core commit on a durable branch, e.g.
  cd $SUB_PATH
  git push $CORE_REMOTE <sha>:refs/heads/develop
MSG
exit 1
