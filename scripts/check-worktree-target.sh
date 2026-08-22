#!/usr/bin/env bash
# Guardrail: refuse to commit into the MAIN checkout when a release worktree exists.
#
# Why (2026-08-21): the shell's cwd reset between commands, so a `git add -A && git
# commit` intended for a feature worktree ran in the main checkout instead. It landed
# on a stale branch, on an unrelated base, and swept up that repo's pre-existing dirty
# submodule pin plus an embedded git repository. Nothing of the intended change was in
# it. Recovering meant a --mixed reset and re-committing by explicit path.
#
# The main checkout is for merges, releases and device work (see CLAUDE.md); feature
# work belongs in a worktree. Install as a pre-commit hook:
#     ln -sf ../../scripts/check-worktree-target.sh .git/hooks/pre-commit
# Override for a deliberate main-checkout commit:
#     ALLOW_MAIN_COMMIT=1 git commit ...
set -euo pipefail

[ "${ALLOW_MAIN_COMMIT:-0}" = "1" ] && exit 0

TOPLEVEL="$(git rev-parse --show-toplevel)"
COMMON="$(git rev-parse --git-common-dir)"
# In a linked worktree, --git-dir differs from --git-common-dir. Equal means MAIN.
case "$COMMON" in /*) COMMON_ABS="$COMMON" ;; *) COMMON_ABS="$TOPLEVEL/$COMMON" ;; esac
GITDIR="$(git rev-parse --absolute-git-dir)"
[ "$GITDIR" != "$COMMON_ABS" ] && exit 0     # a worktree — fine

# Main checkout. Allowed only when no other worktree is in play.
OTHERS="$(git worktree list --porcelain | grep -c '^worktree ' || true)"
[ "$OTHERS" -le 1 ] && exit 0

cat <<MSG
REFUSED: committing in the MAIN checkout while $((OTHERS - 1)) worktree(s) exist.

  main checkout: $TOPLEVEL
  branch:        $(git rev-parse --abbrev-ref HEAD)

The main checkout is for merges, releases and device work. A commit landing here by
accident — which is what a reset cwd produces — goes onto whatever branch main happens
to be on, over an unrelated base, and sweeps up whatever was already dirty here.

If this is deliberate:  ALLOW_MAIN_COMMIT=1 git commit ...
MSG
exit 1
