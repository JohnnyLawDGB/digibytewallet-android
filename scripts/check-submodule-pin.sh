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

# Ask the REMOTE what each durable branch points at, then check containment there.
# Deliberately not `git cat-file` against the local store: local reachability is
# exactly the false positive this script exists to catch.
cd "$SUB_PATH"
git fetch -q "$CORE_REMOTE" $DURABLE 2>/dev/null || true

# EQUALITY, not containment. CI checks out the submodule with actions/checkout
# (`submodules: recursive`, depth 1), so the only core commit in its store is the
# pin itself; a fetch of `develop` into that shallow store brings the tip but none
# of the history behind it, and `merge-base --is-ancestor` can only succeed when
# pin == tip. A local full clone answers "contained" for a pin one commit behind —
# exactly the case that reddens CI (bit 2026-08-31 and 2026-09-03). So: pass only
# on equality; report "behind" separately so the fix is obvious.
BEHIND=""
for b in $DURABLE; do
    tip="$(git ls-remote "$CORE_REMOTE" "refs/heads/$b" 2>/dev/null | awk '{print $1}')"
    [ -z "$tip" ] && continue
    if [ "$PIN" = "$tip" ]; then
        echo "OK: pin equals the tip of core '$b' ($tip)"
        exit 0
    fi
    git fetch -q "$CORE_REMOTE" "$b" 2>/dev/null || true
    if [ -z "$BEHIND" ] && git merge-base --is-ancestor "$PIN" "$tip" 2>/dev/null; then
        BEHIND="$b $tip"
    fi
done

if [ -n "$BEHIND" ]; then
    set -- $BEHIND
    cat <<MSG
FAIL: the submodule pin is BEHIND the tip of core '$1'.

  pin: $PIN
  tip: $2

The pin is reachable from '$1' on a full clone, but CI clones the submodule at
depth 1 and cannot see the history between pin and tip — it will fail this same
check. Core's durable tip must EQUAL the android pin.

Fix: bump the pin forward and commit it here, e.g.
  cd $SUB_PATH && git checkout -q $2 && cd - >/dev/null
  git add $SUB_PATH && git commit -m "chore(core): bump submodule pin to ${2:0:7}"
MSG
    exit 1
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
