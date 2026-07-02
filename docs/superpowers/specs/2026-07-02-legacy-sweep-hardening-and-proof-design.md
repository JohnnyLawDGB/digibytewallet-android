# Legacy Sweep — Hardening + Test/Proof Design

**Date:** 2026-07-02
**Branch:** `phase1-modernization`
**Status:** PROPOSAL — pending user approval. No implementation and no on-chain
broadcast until this spec is approved and the mainnet proof is explicitly
authorized.
**Supersedes nothing; extends:** `2026-06-28-legacy-funds-sweep-design.md` +
`2026-06-29-legacy-funds-sweep.md` (that plan's Tasks 8–10 are the ship-gate;
this spec adds the bug-fixes that must land first).

## 1. Goal

Make the legacy-funds sweep (Universal Restore) **safe to ship** and, in the
same pass, validate that the wallet's shared transaction-broadcast path is a
solid base. "Safe to ship" = the confirmed fund-path defects are fixed, the
build→sign→broadcast path has automated proof, and one real on-chain sweep has
succeeded end-to-end.

Non-goals: BIP49 (P2SH-P2WPKH) sweep support (stays detect-but-defer);
coin-control; a regtest harness (see §5, defaulted out).

## 2. Verified defects (read against current code, not assumed)

Severity is for a **fund-moving** path. All citations verified this session.

| # | Severity | Site | Defect |
|---|----------|------|--------|
| 2 | **Fund-loss** | `jni_derive.c:521,535` | `outAmount = totalIn - fee` where `totalIn` = caller-supplied `amounts[]`. Legacy P2PKH sighash does not commit to input amounts, so a stale/under-reported amount still signs valid and the unreported remainder is **silently burned to fee**. No cross-check against real prevouts. |
| 3 | High (fails/mis-signs) | `RecoveryScanService.kt:197` + `LegacySweepService.kt:106` | Empty derived slots are filtered out, but each UTXO's `(chain,index)` is reconstructed **by position** vs `gapExternal`. One dropped slot mis-maps every later input to the **wrong child key** → invalid signatures / wrong-key signing. |
| 1 | Medium-high (latent, all sends) | `jni_transaction.c:221–227` | `publishTransaction` passes stack-local `&ctx` to `BRPeerManagerPublishTx`; the callback fires **async on the peer thread** after the JNI frame returns → cross-thread **use-after-free write**. Latent (nothing reads `ctx`), so sends work today. Stem path already avoids it with `NULL/NULL`. |
| 4 | Medium (availability) | `LegacySweepService.kt:112–116` | First UTXO with `scriptPubKeyHex == null` **returns** (aborts the whole profile) instead of `continue`. One bad backend row nukes the entire sweep. |
| 6 | Medium (false success) | `jni_transaction.c:227`, `LegacySweepService.kt:156–163` | `publishTransaction` returns the txid on **local relay**, not network/mempool acceptance. A sweep that never propagates reports success. |
| 5 | Medium (robustness) | `LegacySweepService.kt:156` | Sweep (and DigiAsset send) call `Broadcaster.broadcast` directly with **no** `OutgoingTxStore.record` / `WalletTxPersister.persist`; `rebroadcastStrandedSends` (`SyncService.kt:1362`) only replays `OutgoingTxStore.allTxids()`. Force-stop ~1s post-broadcast strands the tx. Mitigated: sweep is re-derivable/re-runnable, so a re-run, not permanent loss (the v3.5.41 fix covered normal sends only). |
| 8 | Low (efficiency) | `RecoveryScanService` two callers | Full multi-profile classify runs **twice** on the onboarding path (informational scan + `RecoverFundsViewModel.classify`), doubling load on the 429-prone reconcile backend. |

Reader-flagged fee overestimate is **not a defect**: `160 B/input`
(`jni_derive.c:521`) is a safe upper bound for legacy **P2PKH** (~148 vB) — the
segwit ~105 vB figure does not apply here.

## 3. Fixes

- **#2 (fund-loss):** Cross-check every input amount against a trusted prevout
  before signing. The local mainnet node (`gettxout txid vout`) or the wallet's
  own SPV view is authoritative; the backend `amountSatoshi` is a hint only.
  On mismatch → refuse to sign, surface which UTXO. At minimum, bound the
  discrepancy so no sweep can pay more than a sane fee cap.
- **#3 (wrong-key):** Carry explicit `(chain,index)` from derivation through
  `ProfileResult` to `sweepOneProfile` instead of reconstructing by position.
  Remove the `filter { isNotEmpty() }`-then-positional-map coupling.
- **#1 (UAF):** Pass `NULL/NULL` to `BRPeerManagerPublishTx` (Kotlin already
  polls `getRelayCount`), matching the stem path — or heap-alloc `ctx` and free
  it in the callback. Prefer the `NULL/NULL` route (proven, minimal).
- **#4 (abort):** `continue` past a null `scriptPubKeyHex`, collect the skipped
  UTXOs, and surface them in the outcome rather than aborting the profile.
- **#5 (durability):** Route sweep + DigiAsset broadcasts through the same
  `OutgoingTxStore.record` + `WalletTxPersister.persist` the normal send uses,
  so `rebroadcastStrandedSends` covers them.
- **#6 (false success):** Treat a returned txid as *pending*, not confirmed;
  reflect actual acceptance via relay-count / mempool, and don't report
  `SweepOutcome` success on local relay alone.
- **#8 (efficiency):** De-dupe the onboarding classify (reuse the informational
  scan result, or gate the second run).

## 4. Test layers (cheapest-first, all pre-mainnet)

- **Layer A — JVM classify edge tests** (`core/.../recovery/RecoveryScanClassifyTest.kt`,
  no infra, runs under `./gradlew testMainnetDebugUnitTest`):
  `classify_emptyUtxos_noFindings` (reachable-but-empty), `classify_multipleAddresses_sumsBalance`
  (assert `ProfileResult.totalSat`), `bip49Profile_isNotSweepable`
  (`addressFormat == 2` → manual-recovery, never silent skip).
- **Layer B — deterministic signed-tx known-answer vector** (instrumented
  `androidTest`, offline, no funds, on a `dgb-test-api33` AVD): feed the test
  seed + a synthetic P2PKH UTXO to `buildAndSignLegacySweep`, assert
  `BRTransactionIsSigned`, and **pin the exact signed hex** (RFC6979 makes it
  stable). Structure cross-checked once via the local node's
  `decoderawtransaction`. This is the key currently-missing proof that the
  fund-*moving* code produces a consensus-valid tx.
- **Layer C — regtest e2e:** DEFAULTED OUT (see §5). If built later, add
  dust-fails-clean, multi-UTXO-consolidates, second-broadcast-rejected.
- **Layer D — one self-funded mainnet proof** (§6): the ship-gate.

## 5. Decisions (proposed defaults — CONFIRM before acting)

The user was away when asked; these are the recommended defaults, to be
confirmed:

1. **Fix scope:** fix **all** confirmed fund-path bugs (§2/#1–#6) before the
   proof. Rationale: it moves real funds; #2/#3 can lose funds or sign wrong
   keys, #1 is on the shared path the proof exercises.
2. **Proof source:** **self-fund ~5 DGB** from the local node's `JohnnyTest`
   wallet (887 DGB) to a **fresh private seed**, then sweep it. Fully
   controlled, exercises the app's real broadcast, pennies. (Optionally also
   confirm against the reporter's still-unspent 4.25 DGB legacy UTXO if they
   engage.)
3. **Regtest harness: SKIP.** The C core (`BRChainParams.h`) has only
   MainNet + TestNet params and the JNI hardcodes MainNet, so the app cannot
   peer with a regtest node — a regtest harness could only broadcast via node
   RPC and would never exercise the app's own `Broadcaster`/SPV path. Layers
   A+B+D cover the signer and the real path; regtest's marginal value doesn't
   justify ~half a day.
4. **Automated-test surface:** unattended `dgb-test-api33` AVD for Layer B;
   reserve the Note 8 for the final user-driven on-device gestures.

## 6. Mainnet proof procedure (execute only with explicit go-ahead)

1. Write `docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md` (the
   one-time validation runbook).
2. Generate a **fresh private seed** (never a public test seed — those are
   sweepable by anyone). Derive its legacy `m/0'` P2PKH address.
3. From the local `JohnnyTest` wallet, send ~5 DGB to that legacy address; mine/
   wait for confirmation.
4. In the app (release-lineage build), restore that seed → classify → sweep →
   broadcast via the app's own path.
5. Assert on-chain: (a) source UTXO spent, (b) funds land on the app's native
   `dgb1` address, (c) balance reflects via normal BIP158/SPV sync with **no
   reconcile**. Record txids + screenshots into the runbook.

## 7. Transaction "paces" (validate the base, user-driven)

Product is mainnet-only, so exercise the normal path with **small real
self-sends on the Note 8, driven by the user** (do not drive their live tethered
device): (1) default-fee send-to-self, (2) MAX / empty-wallet send (single
output, no change — dust edge), (3) custom-fee send (fee-rate conversion +
below-relay/zero warnings), (4) a receive from the local node confirmed via
BIP158 with no reconcile, (5) optional DigiAsset send. All share the
`Broadcaster → publishTransaction` chokepoint, so the #1 UAF fix and #5/#6
durability fixes land first and are covered by these runs. Note:
`SendViewModel` passes `getSpendableUtxos` to `TransactionBuilder` which ignores
it (C core selects) — coin-control is not honored; don't assert it.

## 8. Sequencing, acceptance, rollback

**Order:** (1) fix #1 UAF + #5/#6 durability on the shared broadcast path →
(2) fix sweep-specific #2/#3/#4 + #8 → (3) Layer A + B tests green →
(4) `:core:testMainnetDebugUnitTest` green + `:app:assembleMainnetDebug` builds →
(5) user-driven paces on Note 8 → (6) authorized mainnet proof → (7) commit per
step, version bump per policy, then the sweep clears its ship-gate.

**Acceptance:** all Layer A/B tests green in CI; the mainnet proof runbook
complete with real txids; no `-b crash` entries during on-device runs; sweep
funds reflected via BIP158 (no reconcile).

**Rollback:** each fix is an independent commit; the sweep UI already fails
closed (BIP49 deferred, dust rejected), so a reverted fix degrades to
"unavailable," not "loses funds." The mainnet proof is a fresh throwaway seed,
so a failed proof risks only the ~5 DGB test amount.

## 9. Open items for the user

- Confirm the §5 defaults (fix-all / self-fund / skip-regtest / AVD).
- Explicitly authorize the §6 mainnet broadcast when ready (moves real funds).
- Confirm the fresh-seed proof amount (~5 DGB) is acceptable.
