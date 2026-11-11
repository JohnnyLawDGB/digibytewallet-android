# Development process: Android, iOS and the shared core

The one copy of how work moves through the three repos (`digibytewallet-core`,
`digibytewallet-android`, `digibytewallet-ios`). Each repo's `CLAUDE.md` points here (core from #41, iOS on its next docs push) and does not
repeat it. Decided by Mark, 2026-10-26.

## Merging without Mark

Agent review plus CI stand in for Mark's line-by-line review. A pull request into `develop` of
any of the three repos may be marked ready and merged by the lead agent when **all** of these
hold:

1. **CI is green** on the PR's head. Where a repo has no CI for the change, the lead runs the
   host KATs that cover it and pastes the result in the PR.
2. **An independent review passed.** A reviewer agent that did not write the change is given
   the diff, the goal and the files to read, **not the author's conclusions**, and tries to
   break it: correctness, divergence between the platforms, security, and whether the tests
   would fail without the fix. Every finding is fixed or answered. The review's summary and
   verdict are posted on the PR as a comment.
3. **A push-down proves its gate.** A change to a core rule ships with its host KAT: green on
   the fix, and every red arm fails exactly its cases.
4. **Order holds across repos:** core PR first, then the two pins. Once a core commit's
   `develop` CI run is green (core CI runs Android's host KATs on Linux for every core PR),
   the Android pin PR and the iOS pin move may go in either order. The day iOS moves its pin,
   the iOS and core session opens an Android pin PR to that commit or a later one. Core CI
   does not build the bridge or run the Gradle and parity tests; only that PR does, so it is
   not left to wait. A core commit that changes what either app computes ships in neither app
   until both pins contain it (the iOS repo's `docs/cross-platform-contract.md` §4, item 3).
   A pin is moved only to a commit reachable from core `develop` (`check-submodule-pin.sh`,
   `Scripts/check-core-pin.sh`). *Changed 2026-11-10: iOS used to wait for the Android pin PR
   to merge.*
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
  One standing exception (Mark, 2026-11-10): when a stage of the iOS roadmap has merged and
  passed its exit check on the test iPhone, the iOS lead uploads one TestFlight build of
  `develop` for **internal testers only** and tells Mark afterwards. External testers, App
  Store submission, tags and releases need him each time.
- Force-pushes to `develop`, `master` or any branch someone else works on.
- Security decisions: anything that changes how seeds, keys or PINs are stored, unlocked or
  wiped, or what the spend gate covers (the owner checklist's questions). A PR that does this
  waits for him even when review and CI pass.
- Private advisories: their details stay out of the public repos.
- Changes to this document's rules.

Mark sees merged work as a batch: the progress page and a short digest of what merged, what
review found, and what waits for him.

## Where wallet logic goes

- **Funds in the core** (Mark, 2026-11-10). Anything that signs, spends, selects coins, builds
  a transaction, or decides what the wallet holds or what a balance is, lives in
  `digibytewallet-core` with a host KAT. Neither app carries a second implementation. A
  Kotlin mirror that a parity test binds to the core is allowed where the push-down recipe
  says so.
- **Display on the platform.** Code that only reads and shows (asset names and images,
  metadata parsing, list order, labels) may be written per platform. The second platform
  carries the first one's test cases value for value. Display code never feeds a spend, a
  balance or the set of held outputs, every amount on a screen comes from the core, and a
  refusal is never display. This is a summary; the rule's text and its conditions are the iOS
  repo's `docs/cross-platform-contract.md` §3.

## Two sessions, one Android repo

Since 2026-11-10 two lead sessions work in this repo. Each is the lead for its own PRs: it
pushes them, gets the independent review, and merges them.

- **The Android session** owns the app and the bridge (`app/`, `core/`, `native/src/main`,
  `native/CMakeLists.txt`, the Gradle files) and everything else in this repo that is not
  named in the next item: adopting new core headers, and fixing what the core reviews found
  on Android. An adoption PR also re-anchors the host KATs it breaks; that is part of the
  adoption.
- **The iOS and core session** changes only the host KATs (`native/src/test/host`), the core
  pin, and the docs these rules make it edit (this file, and `ROADMAP.md` when its PR changes
  a planned item). New host-KAT work that is not part of an adoption is this session's. The
  KAT shard lists are in the core repo (`.github/kat-shards/`), also this session's.
- Either session may open a pin PR. A pin conflict resolves to core `develop` (rule 8), at a
  commit whose `develop` run is green (rule 7).
- `jni_registry_gen.h`: one PR at a time across both sessions (rule 6). The session that is
  second regenerates.
- Work passes between the sessions as a hand-off file committed under `docs/handoffs/` in the
  writing session's repo, never as a chat summary.
- Only the iOS and core session touches the test iPhone.

## Keeping plans current

Each app has **one** current plan: `ROADMAP.md` here, `docs/roadmap.md` in the iOS repo. What
follows holds for both. The iOS roadmap's §8 (rules K1 to K9) applies it to iOS in detail.

- **Status moves with the code.** A PR that finishes, starts or changes a planned item edits
  the plan in the same PR, and "done" names the test or device check that shows it. The
  reviewer checks this.
- **Answered questions leave the open list.** A list of what waits for Mark holds open items
  only; an answer is recorded with its date, in the PR that acts on it. (In the iOS repo:
  `docs/owner-checklist.md` and `docs/decisions.md`.)
- **True-up weekly, and within two days of a release tag:** compare the other platform's
  changes, core `develop` against each pin, and the open items; update the plan and its date.
  The iOS repo's CI fails on a plan more than 14 days old.
- **An overrun stops for a re-plan.** A stage that passes its target date by more than half
  its planned length goes back to Mark with what slipped, why, and a new date or a cut.
- **Scope and target change only by Mark's word,** recorded the same day. A new Android
  release becomes the iOS target at a true-up, never mid-stage.

## Subagents: who does what

**The lead** (a session Mark talks to; there are two, see "Two sessions, one Android repo")
owns integration for its own work: the plan, briefs, pins, pushes, merges, and everything that
goes to Mark. Only a lead pushes or merges; no author or reviewer agent does.

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
  including the 32-bit, sanitizer and leak arms the Mac cannot run.
- **iOS:** the simulator suite, the pin check, the string-catalog checks, and the roadmap
  freshness check (`Scripts/check-roadmap.sh`).
