# Derive the CF birth height from address history — design

**Status:** DESIGN, source-grounded (4-reader pass, 2026-07-29), pre-review. Kills the "what year did you create this wallet?" question and the genesis scan behind it.

## Problem

Restoring a wallet asks the user **what year they created it**, converts that to a birth height, and compact-filter-scans from there to the tip. Three things are wrong with it:

1. **The user is being asked something the wallet can already find out.** The address-history index knows the earliest transaction height for the derived address set.
2. **"I don't remember" means a genesis scan (~24M blocks)** — and an on-device run against the live oracle fleet on 2026-07-29 proved that is not merely slow but **impossible**: peers served `cfheaders` normally (5 batches) and returned **zero `cfilters`** at genesis-era heights, twice, from different peers. `scannedThrough` pinned at 20,433 while `outstanding` climbed to 2,134. No amount of client-side pacing fixes an upstream data-availability gap.
3. **Even a *correct* year is coarse.** The timestamp→height conversion (`jni_peer.c:1017-1032`) walks the hardcoded checkpoint table and picks the last checkpoint ≥7 days older than the creation time. Early checkpoint gaps are enormous (521,000 → 1,380,000 → 3,000,000 → 4,255,555) and the modern tail is every 500,000 blocks — so "2017" can floor the scan a million blocks too low.

## What grounding established (facts — do not re-derive)

- **Heights are already on the wire.** `parseAddressHistory` builds `AddressTx(txid, height)` from a flat `transactions[]` array; height is an absolute block height, single-hop, no blockhash lookup (`DgbNodeClient.kt:313-326`). Fixture confirms real server bodies carry `height` (`AddressHistoryParseTest.kt:21-31`).
- **The batched endpoint exists**: `POST {endpoint}/rpc/address-history` with `{"addresses":[…]}`, chunked at 500 (`DgbNodeClient.kt:144-155`). It returns **spent and 0-value txs too** — the taproot/DigiDollar cases `scantxoutset` misses (`DgbNodeClient.kt:125-135`).
- **`cf_birth_height` has exactly two writers and two readers.** Writers: `WalletManager.recoverWallet` (`:158`) and `rebuildFromChainRescan` (`:510`), both writing `cfBirthHeightToPersist(getWalletBirthCheckpointHeight())`. Readers: `SyncService.kt:1796` (→ `enableAutoCompactFilterFetch`) and `:1906` (→ BIP158 watchdog). **It is never advanced with scan progress.**
- **The year picker's entire contribution is one `Long` timestamp**, funnelled through `OnboardingViewModel.setRecoveryTimestamp` (`RecoveryDateScreen.kt:179-181`) — a single choke point.
- **Onboarding order is already favourable**: `mnemonic_input → recovery_scan → recovery_date → recoverWallet → pin_setup`. The scan runs **before** the picker by design, and both screens share the same `OnboardingViewModel` via the `onboarding` back-stack entry — so a value computed during the scan needs **no new plumbing** to reach the picker.
- **`cfBirthHeightToPersist(raw) = raw.takeIf { it > 0 }`** (`WalletManager.kt:45`) — a non-positive height *removes* the pref rather than storing a genesis floor. A failed computation degrades to `SyncService`'s default, not to genesis.

## ⚠️ The four traps (each would silently break this)

1. **`height` 0 does not mean genesis — it means unconfirmed.** `optLong("height", 0L)` (`DgbNodeClient.kt:323`) returns 0 for a missing field, a malformed field, **and for a mempool tx** under the Electrum `get_history` convention this backend wraps. A naive `minOf { it.height }` returns 0 for any wallet with one pending transaction and floors the scan at genesis — precisely the failure this design exists to remove. **Every earliest-height computation must filter `height > 0`, and "all heights ≤ 0" must mean *no usable answer*, never *genesis*.**
2. **The UTXO path is the wrong source.** `RecoveryScanService` currently gets heights from `UtxoEntry.blockHeight`, but that endpoint wraps `scantxoutset` — **currently-unspent outputs only** (`DgbNodeClient.kt:23-26`, and the screen says so at `RecoveryScanScreen.kt:217`). `min()` over it is the earliest *unspent* height; all older *spent* history would fall outside the scan window forever. Use `addressHistoryBatch`.
3. **The timestamp is not only the CF floor — it anchors the header chain.** `g_walletCreationTime` feeds `syncFromTime → BPPeerManagerMainNetNew` (`jni_peer.c:837-847`). Writing `cf_birth_height` without also supplying a timestamp leaves the header anchor at `time(NULL)` and the two disagree.
4. **The persisted height is a *request*, not the effective floor.** `BRPeerManager.c:5196-5223` raises `startHeight` to `tip-1999` (or `tip`) if the requested height's block hash is unresolvable in the in-memory window. Whatever we compute can still be clamped upward natively — the same mechanism behind the C-1 finding on the convoy branch.

## ⏱ A bounded deadline is mandatory (measured on hardware, 2026-07-29)

Observed live during the convoy acceptance run, against a genuinely wedged production index: **the restore blocked for ~9 minutes on a static spinner** with no progress, no error, and no escape. The mechanism is exact and is not a retry loop — `DgbNodeClient` contains no retry code at all. `RecoveryScanService` **serializes six derivation profiles by design** (`RecoveryScanService.kt:113-141` — the backend serializes internally and 429s on concurrency), and each request rides a **90-second OkHttp `readTimeout`** (`DgbNodeClient.kt:44-47`). Six profiles × 90 s = 540 s, matching the observed hang to the second. Cutting the network instead produced a clean degrade in seconds — so the failure classifier handles *unreachable* correctly and *reachable-but-hung* not at all.

Two requirements follow:

1. **One overall deadline for the whole estimate, not per-request.** This design adds a *second* serialized pass over the same ~1,800 addresses, so the naive worst case is ~18 minutes of blocked first-run restore. The estimate must be bounded end-to-end (single-digit seconds), and expiry means `Unknown` → picker. **A restore must never be gated on an index answering.**
2. **The progress label must track the actual stage.** Throughout the 9-minute reconcile the screen read "Deriving addresses…" — a stage that had already finished. A spinner that misreports its stage makes a hang indistinguishable from work.

Both are pre-existing defects on the shipped recovery path, worth fixing independently of this feature; this design must not inherit them.

## Coverage limits — why the answer can be *too high*

The dangerous direction is a birth height **above** the true first transaction: those blocks are never scanned and the receive is silently missed.

- **No taproot.** `NativeBridge.deriveAddresses` emits only P2PKH / P2WPKH / P2SH-P2WPKH (`NativeBridge.kt:392-397`). A wallet whose earliest activity is a **P2TR / DigiDollar** output is invisible to the queried address set.
- **Gap limit.** 200 external / 100 internal per profile, ≤1,800 addresses per restore (`DerivationProfile.kt:44-45`). Early activity beyond the window is invisible.
- **Partial outage is invisible.** `allBackendUnreachable` is all-or-nothing (`RecoveryScanService.kt:51-54`), and with zero funds found the screen **auto-advances** (`RecoveryScanScreen.kt:105-108`). A height computed from a partially-answered address set would be silently too high.

## Design

**Principle: the index says WHERE to look, never WHAT you own.** A wrong index costs a wrong start height — bounded, detectable, recoverable — never a wrong balance.

**1. Compute an earliest-confirmed height during the existing scan.**
Widen the `UtxoSource` seam (or inject `DgbNodeClient`) so `RecoveryScanService` can call `addressHistoryBatch` for the union of all derived addresses across profiles — **one batched call set, not per-profile**, since the endpoint 429s under concurrency and profiles are already serialised for that reason (`RecoveryScanService.kt:111-117`).

```
earliest = heights.filter { it > 0 }.minOrNull()      // trap 1
confident = every queried profile had reachableBackend == true
            && no chunk returned null                  // trap: fail-closed
```

**2. Only use it when confident.** Emit a tri-state, not a number:
- `Confident(height)` — complete answer, at least one confirmed tx found.
- `NoHistory` — complete answer, genuinely zero confirmed txs. **This is a real signal**: a wallet with no history needs no deep scan at all; floor near the tip.
- `Unknown` — partial/failed/unreachable, or all heights ≤ 0. **Fall back to the year picker unchanged.**

**3. Apply a conservative margin and a floor.** `birthHeight = max(0, earliest − CF_BIRTH_MARGIN_BLOCKS)`. The margin absorbs index skew and reorgs; it does not absorb the coverage gaps above — those are handled by detection, not by padding.

**4. Set both anchors.** Supply the timestamp (for `g_walletCreationTime` / header anchor) **and** the exact height, rather than routing a synthetic timestamp through the coarse checkpoint walk. That means a third `cf_birth_height` writer — acceptable, but it must use the same `cfBirthHeightToPersist` guard and the same `.commit()` discipline as the existing two.

**5. Replace the year picker only in the `Confident`/`NoHistory` cases.** Show what was found and why ("earliest activity found at block N — scanning from N−margin") with an explicit **"scan deeper"** escape. On `Unknown`, the picker appears exactly as today. **Never remove the manual path** — it is the only input when the backend is down, which we observed happening.

## Detection — being wrong must be visible, not silent

This is what makes an index-derived floor acceptable in a sovereignty-first wallet.

After sync reaches the tip, run the existing address-history reconcile and check for **any confirmed tx whose height is below `cf_birth_height`**. If one exists, the floor was too high: surface it through the **existing abandoned-band machinery** (banner + withheld "Synced" + recover action) rather than inventing a second surface, and offer a deeper rescan. That reuses the GATE-3 path already built and reviewed on the convoy branch, and it converts the taproot/gap-limit blind spots from *silent loss* into *visible, recoverable deferral* — the same trade the abandonment valve makes.

### Does the detector share the estimator's blind spot? Checked — on coverage, no; on availability, yes.

**Coverage: independent, and this is the load-bearing fact.** The estimator runs during restore, *before a wallet exists*, so it can only use the stateless `NativeBridge.deriveAddresses` — which emits no P2TR. The detector runs after sync with the wallet loaded, and `ChainReconciliationService` enumerates via `NativeBridge.dumpAllAddresses()` (`ChainReconciliationService.kt:105`) — which **does** include the taproot chains. That is asserted directly: `TaprootWatchSetTest.taprootReceiveAddress_isInWatchSet` gates on `dumpAllAddresses()` containing the KAT P2TR address, and was written red-before-green against exactly the `BRWalletAllAddrs` omission that would break this. So a taproot/DigiDollar receive below the floor — invisible to the estimator — **is** visible to the detector. The design's central blind spot is closed by the detector rather than by padding.

**Availability: not independent, and it must be stated in the code.** Both paths resolve through the same `DgbNodeClient.endpoint()`. An index outage during restore is harmless (it yields `Unknown` → picker, so no bad height is ever written), but an outage *later* means the check simply does not run. Therefore:

> **Requirement:** "detection could not run" is a **distinct state** from "detection found nothing", and must never be reported as all-clear. Model it on the existing `lastHistoryPassCovered` gate in `ChainReconciliationService` — which already refuses to clear the abandoned-band banner on a partial pass, for precisely this reason — and reuse that flag rather than adding a second notion of coverage.

## Relationship to the paced convoy — complementary, not a replacement

The convoy stays and is still load-bearing. It is the **floor**: what makes any depth work at all when there is no index answer (`Unknown`), when the user distrusts the index, or when they choose "scan deeper". This design makes the deep path **rare**; it does not make it unnecessary. Notably, the on-device run showed the convoy pacing correctly (`holding header continuation`, window measured at 10,134 against `CF_CONVOY_WINDOW` 10,000) while the scan was blocked upstream — the two failures are independent.

## Privacy — same bytes, different frequency, different consent posture

The restore **already** POSTs up to 1,800 derived addresses to `api.digiscope.me` for the UTXO query (`RecoveryScanService.kt:130`), so this design sends nothing new to anyone new. That framing is true and insufficient. The thing that actually changes is **frequency and consent**: today that disclosure rides an explicit user-initiated recovery action; if auto-birth-height becomes the default restore experience, it becomes routine and automatic. An exception that fires rarely reads very differently from one that fires on every restore. Three requirements follow, and none of them are large.

**P1 — Consent at the point of disclosure, as a peer option.** The restore screen states plainly that the wallet can ask the index to estimate your wallet's start date, **which sends your addresses to that server**, with "pick a date myself" offered *right there* as an equal choice — not a settings toggle nobody finds, and not a default with an opt-out buried elsewhere. Since the picker survives permanently anyway (Decision 1), the alternative already exists; this only requires presenting it as a peer rather than as a fallback.

**P2 — The README claim gets scoped to match reality.** `README.md:11` and `:175` assert the address set never leaves the device as an unqualified sovereignty property. That is already untrue of the recovery path *today*, before this feature exists. Amended in this same change to scope the claim to **sync** — which is the real achievement, bloom is gone and CF-only is the only wire path — and to name recovery as an explicit, user-initiated, overridable exception. When this feature ships, the README must be amended a second time to name birth-height estimation in that same exception. An honest scoped claim is stronger than an absolute one with a silent exception; the project has been rigorous about not letting code comments overclaim safety properties, and user-facing docs get the same treatment.

**P3 — Prefer the user's own index, and be honest about what pairing does and does not give them.** The seam exists and is cheap: `DgbNodeClient.endpoint()` already returns a user-set override (`dgb_reconcile/custom_rpc_endpoint`, unpinned TLS, `DgbNodeClient.kt:62-73`) and both the estimator and the detector route through it — so a user pointed at their own index gets birth-height estimation with **zero third-party disclosure, for free**, provided the estimator calls `endpoint()` rather than hardcoding the default.

**But `dgbnode://` pairing does not supply one, and the UI must not imply it does.** `OwnNodeUri` parses only `host[:port]` plus `net`/`label` (`OwnNodeUri.kt:10-42`) — a **P2P** reference used for the native peer pin and CF status. It carries no RPC endpoint and no credentials, and `/rpc/address-history` is an **ElectrumX-backed backend route**, not a call a bare DigiByte Core node answers. Today `setCustomEndpoint` is only ever written from the manual field in `ReconcileScreen.kt:196`. So the honest framing is *"prefers your index when you have one"*, and a paired Core node alone is not one. A user running the compatible index should not have to type it twice — wiring pairing to offer/prefill the reconcile endpoint is worth doing — but it must be presented as a separate capability, not as something pairing already conferred.

## Tasks

1. Widen the `UtxoSource` seam (or inject `DgbNodeClient`) into `RecoveryScanService`; add the batched union query. DI is a 3-line change, no graph cycle (`AppModule.kt:147-164`).
2. Add the tri-state earliest-height computation with the `height > 0` filter and the per-profile `reachableBackend` gate. Pure function, host-JVM testable.
3. Carry the result on `ProfileResult`/`State.Done`; respect the existing classify-cache guard so a partial outage is never memoised (`RecoveryScanService.kt:144-152`).
4. Consume it at `OnboardingViewModel.setRecoveryTimestamp` — supply timestamp **and** exact height; add the third `cf_birth_height` writer under the existing guard.
5. UI: replace the picker on `Confident`/`NoHistory`, keep it on `Unknown`, always offer "scan deeper". **P1** — present the estimate and "pick a date myself" as peer options, with the disclosure stated at that screen.
6. Post-sync detection: reconcile-below-floor check wired into the existing abandoned-band surfacing, with **"could not run" distinct from "found nothing"**, reusing `lastHistoryPassCovered`.
7. **P3** — estimator resolves its endpoint through `DgbNodeClient.endpoint()` (never the hardcoded default), so an own-index user gets zero disclosure automatically. Optionally offer to prefill the reconcile endpoint at `dgbnode://` pair time, labeled as a **separate** capability from the P2P pin.
8. **P2** — second README amendment naming birth-height estimation in the scoped exception (the first, covering today's reconcile path, ships with this spec).

## Sequencing

**This is gated behind the paced-convoy acceptance run.** The convoy branch sits at the merge gate with the deep-restore acceptance unrun; if that run surfaces something, this design's assumptions about scan behaviour may need to change. Land the convoy, run the acceptance, then build this.

## Tests (red-before-green)

- `height = 0` (mempool) present ⇒ **not** treated as genesis; falls to `Unknown` if no positive heights remain.
- One chunk fails ⇒ `Unknown`, picker shown, no height written.
- Partial profile unreachable ⇒ `Unknown` (must fail on the per-profile gate, since `allBackendUnreachable` would return false here).
- Zero confirmed txs, complete answer ⇒ `NoHistory`, floor near tip, **not** genesis.
- Confident path ⇒ `cf_birth_height == earliest − margin`, and the persisted timestamp and height agree.
- Detection: a confirmed tx below the floor ⇒ band surfaced, Synced withheld, recover action offered.
- Detection **unavailable** (index unreachable at check time) ⇒ reported as unknown, **not** as all-clear; no banner clear, no "Synced".
- Detector coverage: a **P2TR** tx below the floor is found by the detector even though the estimator's `deriveAddresses` set could never have seen it (the asymmetry the design depends on).
- **P3**: with a custom endpoint set, the estimator issues **zero** requests to the default host.
