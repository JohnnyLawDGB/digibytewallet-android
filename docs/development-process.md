# Development process: Android, iOS and the shared core

The one copy of how work moves through the three repos (`digibytewallet-core`,
`digibytewallet-android`, `digibytewallet-ios`). Each repo's `CLAUDE.md` points here (core from #41, iOS on its next docs push) and does not
repeat it. Decided by Mark, 2026-10-26.

## Merging without Mark

Agent review plus CI stand in for Mark's line-by-line review. A pull request into `develop` of
any of the three repos may be marked ready and merged by the lead agent when **all** of these
hold:

1. **CI is green** on the PR's head. Where a repo has no CI for the change yet (core, until its
   workflow lands), the lead runs the host KATs that cover it and pastes the result in the PR.
2. **An independent review passed.** A reviewer agent that did not write the change is given
   the diff, the goal and the files to read, **not the author's conclusions**, and tries to
   break it: correctness, divergence between the platforms, security, and whether the tests
   would fail without the fix. Every finding is fixed or answered. The review's summary and
   verdict are posted on the PR as a comment.
3. **A push-down proves its gate.** A change to a core rule ships with its host KAT: green on
   the fix, and every red arm fails exactly its cases.
4. **Order holds across repos:** core PR → Android PR that pins core `develop` → iOS pin. A pin
   is moved only to a commit reachable from core `develop` (`check-submodule-pin.sh`,
   `Scripts/check-core-pin.sh`).
5. **Merge commit or fast-forward, never a squash**, so pins stay reachable.
6. **Generated files are regenerated, never merged.** `jni_registry_gen.h` can merge with no
   conflict and still be stale (two PRs that each add natives). Before merging a PR that touches
   it, merge current `develop` into the branch, run `python3 scripts/gen-jni-registry.py`, push,
   and wait for its CI. One registry PR at a time.
7. **An Android pin moves only to a core commit whose `develop` CI run is green.** Core CI tests
   a PR's head; the merge commit is tested by the run on `develop` that follows it.
8. **A pin conflict is resolved to core `develop`, never to a PR's older pin**: an older pin can
   silently roll back core fixes (check `git merge-base --is-ancestor <last release's core> <pin>`).

### Still Mark's, always

- Releases, tags, version bumps meant for a release, Play Store, App Store, TestFlight.
- Force-pushes to `develop`, `master` or any branch someone else works on.
- Security decisions: anything that changes how seeds, keys or PINs are stored, unlocked or
  wiped, or what the spend gate covers (the owner checklist's questions). A PR that does this
  waits for him even when review and CI pass.
- Private advisories: their details stay out of the public repos.
- Changes to this document's rules.

Mark sees merged work as a batch: the progress page and a short digest of what merged, what
review found, and what waits for him.

## Subagents: who does what

**The lead** (the session Mark talks to) owns integration: the plan, briefs, pins, pushes,
merges, the test device, and everything that goes to Mark. Only the lead pushes, merges or
touches the device.

**Author agents** take one bounded task each, in their own worktree (`isolation: worktree`, or
a `git worktree` the lead made), and return a branch with commits plus a report: what changed,
what was run, what was not verified. They do not push.

**Reviewer agents** start fresh for each PR or batch (never the author, never forked from the
author's context). They get the code and the goal, and return findings ranked by severity,
each with a concrete failure scenario.

**Rules for both:**

- A brief names the goal, what is already ruled out, the files and docs to read (this file,
  the repo's `CLAUDE.md`, the cross-platform contract, the push-down recipe), the scope it must
  not leave, and how to verify.
- An agent's report is input, not fact. The lead re-runs the decisive checks before merging.
- Tracks that touch the same files do not run at the same time. Independent tracks do.
- Durable output goes in a repo, never in a scratch directory (macOS deletes `/private/tmp`
  files after about three days).

## What CI must cover

- **Android:** `ci.yml` (Gradle build and unit tests, the native host KATs, the guardrail
  scripts), CodeQL, gitleaks.
- **Core:** a workflow on Linux that builds the core and runs every host KAT that exercises it,
  including the 32-bit, sanitizer and leak arms the Mac cannot run (owed: the first track of the
  iOS port plan's §19, which lands with the iOS repo's next push).
- **iOS:** the simulator suite, the pin check, and the string-catalog checks.
