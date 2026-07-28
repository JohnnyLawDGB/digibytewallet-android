# Paced-convoy CF fetch — merge + acceptance runbook

Consolidates the merge mechanics and every hardware-only check the branch reviews flagged. Written 2026-07-28, at branch code-complete.

**Design of record:** `docs/superpowers/specs/2026-07-28-paced-convoy-fetch-design.md`

## What shipped

Block-header sync used to fast-forward to the chain tip unpaced, so restoring an old wallet accumulated the whole chain in `manager->blocks` before the compact-filter scan evaluated any of it — the OOM that made deep restores impossible. The predecessor "fix" refused wallets deeper than a threshold; that refusal is deleted. Instead the header/cfheader frontiers are now paced to stay within `CF_CONVOY_WINDOW` (10000) of the scan frontier, driven from the KeepAlive tick, with an evidence-gated valve to retire genuinely-unservable scan holes and a surfacing path so any skipped band is visible and recoverable.

**Measured:** 14,203 headers resident across a 105,000-block descent (13.5%) vs 105,060 (100.1%) unpaced. Bound is derived from source constants, not fitted, and cannot be met by a stalled scan (six independent progress assertions).

## Merge mechanics — ordering is load-bearing

The outer repo's submodule gitlink is stale relative to the core branch, and the direction matters: the submodule deletes `#define CF_RETENTION_MAX_SPAN`, which earlier outer commits still reference.

1. **Core PR first.** Push the submodule branch, open the PR against the core fork's `develop`, merge it.
2. **Confirm reachability before the app PR:**
   `git merge-base --is-ancestor <core-merge-commit> <core-develop>` must succeed.
3. **Bump the pin at an outer commit ≥ the final outer HEAD** — never earlier, or the tree does not compile.
4. **App PR second**, with the bumped pin. Human approval required for both merges.
5. Local `develop` was stale (`e2052a84`) at branch time — `git switch develop && git pull && git submodule update` before merging.

## Acceptance run — what must actually be exercised

Two wallets. Nothing here is covered by the host KATs.

### A. Shallow LAB wallet
Credits the $1 DigiDollar at block 23,920,918 via CF alone, `outstanding → 0`, no crash/wedge.

### B. Deep restore (the real test)
Birth ≫ 100k below tip. Must credit **every** DGB / DD / asset the keys control by scanning birth→tip through the convoy, with `manager->blocks` bounded the whole way.

**Diff the credited transaction set against a node-side address history for the whole `[birth..tip]` range — not just the balance.** A ~10,000-block skip is invisible in a balance check unless the wallet happened to be paid in that band.

### Watch items (each from a specific review finding)

1. **Resume mid-descent — background-kill + resume at least twice.** Grep for `BIP158: applied pending auto-fetch — requested X, clamped to Y` (expect `Y ≫ X`) followed by `cf-ledger: resume cursor snap N -> N`. Then assert `getLowestNeededHeight()` does **not** jump by ~`W` on the first KeepAlive tick after resume. This is the signature of the Critical the whole-branch review caught.
2. **Manager recreate mid-descent.** Trigger a pull-to-refresh or airplane-mode toggle mid-descent and watch whether `getLowestNeededHeight()` falls back to `cf_birth_height`. If it does, a genuinely deep restore will never converge (a recreate re-arms without restoring the ledger).
3. **`BRCompactFilterChain` footprint.** The convoy does **not** bound this structure — it held ~105k entries (~3.4 MB) on the scale run and grows linearly with depth. Measure it on the deep restore. If it dominates, that is the next memory sequence. *"The OOM is gone" is true of `manager->blocks` specifically.*
4. **The two opposing valve signals, read from the same run:**
   - a height abandons and a later reconcile **credits** it ⇒ `CF_CONVOY_REARM_MAX` is too tight, raise it (now runtime-read by Kotlin, so the C header is the single source);
   - `adb logcat -s bread | grep "B2 re-armed"` shows the same height at `cycle N ≥ 5` with `TAINTED` every time ⇒ too reluctant, needs a taint-independent backstop.
5. **Run `WalletManagerRescanTest`** (`core/androidTest`, 2 tests) — compile-verified but never executed. It is the only end-to-end proof of the recovery escape hatch (`rebuildFromChainRescan()` → restart → `restoreCfScanLedger()` → `abandonedBelow == 0`). A bare unit test of the native `Init` would pass while production stayed broken.
6. **`getConvoyAbandonmentPending()` value semantics on hardware** — host-KAT-covered only. Confirm it returns `rearmCycles + 1` and that the watchdog suppression releases above the bound.
7. **Unconditional arming** — removing the depth refusal made `enableAutoCompactFilterFetch` unconditional; the deepest-restore path has no on-device coverage at all yet.
8. **Banner behaviour.** A deep restore that is killed and resumed will legitimately surface an abandoned band (the persisted block window can never reach a paced descent's frontier). Confirm it is recoverable — one node reconcile clears the accumulated band — and that a *healthy* wallet killed abruptly does **not** banner.

## Known limitations (accepted, with signals)

- **Fleet saturation → indefinite re-arm.** The valve proves refusal only by the *currently connected* CF peers, never fleet-wide. Under a churny fleet it can re-arm indefinitely and the convoy stays pinned — a bounded-memory **visible** stall, never an OOM and never a wrong balance. Watch item 4 is the signal.
- **Deep-restore resume bands.** Real, recoverable, and surfaced by design rather than hidden.

## Out-of-scope items tracked elsewhere

- A **pre-existing remote-DoS** (unsolicited block message at a checkpoint height → NULL deref) is handled on its own branch; it is not a convoy regression, and the convoy in fact narrows one of its two instances.
- **Wipe-then-restore-a-different-seed** inherits the old wallet's CF ledger (`WalletDataEraser` does not clear it and the restore has no wallet-identity check). Priority rose now that `abandonedBelow` is load-bearing.
