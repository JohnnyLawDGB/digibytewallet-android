# Complete DigiByte wallet-state reconstruction (all 4 address/tx types) — DESIGN

**Date:** 2026-07-19
**Status:** operator-approved (design + backstop-first sequencing). No code written yet.
**Operator's crisp spec:** "scan for ALL transactions touching our address set — legacy, segwit, taproot, digidollar — classify them properly (native DGB / DigiDollar / DigiAsset), and properly increment/decrement all the respective asset balances, which have to be tied to specific UTXOs for selection."

Synthesizes three read-only investigations (data-source, classify, reflect/UTXO) plus a lead-engineer design pass, run 2026-07-19 as the `wallet-state-reconstruction-audit` workflow. All line references verified in those passes.

---

## 0. The core finding, stated plainly

The reconstruction math is **already correct and sovereign**. `_BRWalletUpdateBalance` (`BRWallet.c:185-316`) is a pure function of `wallet->transactions[]` that, in one pass, builds three disjoint UTXO sets (`utxos` / `ddUtxos` / `assetUtxos`), two native balances, and the `spentOutputs` set — and coin selection for DGB and DD already spends from the same native arrays it displays. The watch set already covers all four address types (legacy/segwit/taproot verified, bech32m decodes). Classification (DD-first → asset → DGB) is correct per output.

**The DD/asset miss is NOT an accounting, classification, or watch-set defect. It is upstream: a tx that sync never registered contributes to nothing.** Blocks were never scanned because the CF cursor advances on *request*, not on *response*. Everything else is downstream of that one hole, plus one genuinely broken asset-send fee path.

So this design is narrow by intent: fix the scan so the from-birth rebuild is actually complete (sovereign), add a trust-minimized address-history backstop for what CF still can't self-heal, and close the asset UTXO/fee divergence so assets get the same "balance = the set you spend from" guarantee DGB/DD already have.

**Enabling event (2026-07-19):** the backend Electrum scripthash builder was writing the raw witness version (v1 → `0x01`) instead of the opcode (`OP_1 = 0x51`) for taproot addresses (`electrum-service.js:91`), so taproot/DD addresses were invisible to the Electrum index. Fixed + deployed; the DD taproot address `dgb1plr8…429c` now returns txCount:5 (was 0), including the stuck DD receive `8096…` and three more taproot txs. **This is what makes the address-history backstop viable today** — the backend can now answer history for all four address types.

---

## 1. ARCHITECTURE — the end-to-end reconstruction

**Stage 1 — Enumerate the full address set (REUSE, unchanged).**
`BRWalletAllAddrs` (`BRWallet.c:919-1049`) walks external/internal segwit P2WPKH + legacy P2PKH + legacy-key chains + taproot P2TR (int/ext, incl. the m/86'/20'/0'/0/0 DD output key) + `watchedAddrs`. Same enumeration feeds both the CF watch set (`BRWalletGetFilterElements`) and the Kotlin owned-set (`dumpAllAddresses`). Verified complete. One hardening only: add a bounds assertion on the count-pass/fill-pass sizes (taproot/DD/watched are appended last and are the first to drop if the buffer is ever undersized — latent, not active).

**Stage 2 — Obtain every tx touching the set.**
- **PRIMARY / sovereign: from-birth CF rebuild.** `WalletManager.rebuildFromChainRescan()` (`WalletManager.kt:388-417`) clears saved txs/headers/blocks/has_synced, sets `cf_birth_height`, restarts; the header chain re-syncs from birth and the CF cursor walks every block birth→tip. On each GCS match the full block is downloaded and every tx registered with its confirming height (`_peerRelayedCFilter` → `BRPeerSendGetdataBlocks` → `BRWalletRegisterTransaction`, height stamped on connect). This already picks up DD + asset + native correctly **for every block it actually scans**. Made complete by the P0 fix below.
- **BACKSTOP / trust-minimized: address-HISTORY reconcile (NEW — build #1).** For residual gaps CF can't self-heal (a block whose headers aren't resident, an operator with `addressindex`, belt-and-suspenders, immediate recovery of an already-missed tx): per address → `get_history`/`getaddresstxids` → diff against known txids → `getrawtransaction` → `registerRawTransaction(bytes, height, time)`. Must be **history-based, not UTXO-based** — the existing `/api/wallet/reconcile` (scantxoutset) structurally misses 0-value DD outputs and fully-spent asset markers and is therefore retired as the DD/asset path.

**Stage 3 — Classify each tx (REUSE + consolidate).** Per-output, owned-gated, mutually exclusive: DD if `BRDigiDollarOutputAmount ≥ 0` (version-marker 0x0770 gated) → asset if `BRTxOutputIsAsset` (OP_RETURN "DA" gated) → else plain DGB. Correct today. The change (gap P4) is to make native the *single* asset verdict and have Kotlin consume it, instead of running a second independent DA parser (`DigiAssetDecoder`).

**Stage 4 — One UTXO set per type + spent-tracking (REUSE for DGB/DD; make asset sovereign).**
DGB → `wallet->utxos`, DD → `wallet->ddUtxos`, asset → `wallet->assetUtxos`, all from the same pass; spends flow into `spentOutputs` and prune DGB + DD. **Change:** also prune `assetUtxos` against `spentOutputs` (today it is never spent-pruned — `BRWallet.c:807` — because it currently only gates DGB selection).

**Stage 5 — Balance = SUM(unspent of that type); selection spends the SAME set.**
- DGB: `BRWalletBalance` and `BRWalletCreateTxForOutputsEx` both off `wallet->utxos` — consistent today.
- DD: `BRWalletDigiDollarBalance` and `BRWalletCreateDigiDollarTransfer` both off `wallet->ddUtxos`(+`utxos` for fee) — consistent today.
- **Asset (NEW):** expose `wallet->assetUtxos` (outpoint + quantity + scriptPubKey) via a JNI accessor paired like `BRWalletDigiDollarUTXOs`; drive both the displayed asset SUM and `AssetCoinSelector` from that one native set, and fund the asset-send DGB fee from native `wallet->utxos`. Collapses the parallel Room reconstruction into the sovereign native one.

Reused verbatim: `BRWalletAllAddrs`, `_BRWalletUpdateBalance`, the CF match→download→register path, `registerRawTransaction`/`confirmTransaction`, the ChainReconciliationService import loop (`:126-137`), `DgbNodeClient.setCustomEndpoint`. New: per-height cfilter-receipt tracking; a Kotlin caller for `requestCompactFilters(start,stop)`; `DgbNodeClient.addressHistory` + `getRawTransaction`; a native asset-UTXO export accessor + Kotlin wiring; assetUtxos spent-pruning.

---

## 2. THE KEY DECISION — sovereign CF vs. backend address-history vs. both

**Both, strictly tiered — sovereign CF is the reconstruction; address-history is a bounded backstop.**

- **Sovereign CF is the only path that is complete AND private for all four types.** The address set never leaves the device (CLAUDE.md invariant). The single defect that makes it *incomplete* is mechanical and fixable (P0). This is the primary long-term fix.
- **A backend address-history reconcile cannot be the *sole* long-term path** because it exposes the address set to whatever backend answers it, and requires a full-index source (own-node `addressindex=1`+`getaddresstxids`, or an ElectrumX/electrs `blockchain.scripthash.get_history` — the only backend shape returning 0-value + spent + all-4-types). The digiscope backend now answers this after the 2026-07-19 taproot fix.
- **Why build the backstop FIRST anyway (operator decision):** it recovers the operator's *currently-missing* DD/asset txs **today**, app-only, with no native submodule rebuild — and it strictly dominates and replaces the retired UTXO reconcile. Pointed at the user's own node via `setCustomEndpoint`, it is trust-minimized rather than trusted-third-party. The P0 sovereign fix follows as the durable answer.

**Sequencing (operator-approved 2026-07-19): backstop first (build #1) → then P0 sovereign scan-fix → P2 continuity → P3 asset-fee → P4/P5/P6 asset sovereign parity.**

---

## 3. GAPS TO CLOSE — prioritized

**BUILD #1 — Address-history backstop (Kotlin/app only; no native rebuild). Recovers the operator's missing txs now.**
Add `DgbNodeClient.addressHistory(address)→List<txid>` + `getRawTransaction(txid)→{hex,height,time}` against the (now taproot-capable) history source; a new ChainReconciliationService pass that enumerates the full owned address set, diffs each address's history against known txids, fetches each missing tx's raw hex, and `registerRawTransaction(bytes, height, time)` **with confirming height** (satisfies the dust-pending gate so 0-value DD credits). Wire into the existing "Scan for missing funds" (ReconcileScreen). Default endpoint = digiscope (trusted, explicit); own-node via `setCustomEndpoint` is the trust-minimized path.

**P0 — CF cursor skips unacknowledged blocks (native/submodule). THE root cause.**
`BRPeerManager.c:2366-2381` advances `autoFetchCFiltersThrough = reqStop` right after the `getcfilters` send, with no tracking of which `cfilter` responses arrived. Minimal fix: record per-height (or per-batch) cfilter receipt in `_peerRelayedCFilter` (`:2398`); do not advance the cursor past a height until its cfilter is received + chain-verified; on peer drop/timeout mid-batch, re-request the unanswered sub-range (bounded retry then advance-with-record-as-gap so one dead peer can't wedge sync). Makes *every* scan (fresh sync and from-birth rebuild) complete for all four types. Highest leverage; single fix.

**P1 — No reachable targeted historical re-scan (native exists, Kotlin caller missing).**
`requestCompactFilters(start,stop)` (`jni_peer.c:1367`, `NativeBridge.kt:480`) has zero Kotlin callers. Add a "re-scan height range" recovery caller (around a known-pending txid's block, or a suspected gap), guarded to ensure headers span the range first. Heals a gap without a full rebuild.

**P2 — cfheaders continuity wedge triggers spurious re-anchor mid-rebuild (native).** Fix the traced continuity wedge so re-anchor isn't spuriously fired, and ensure re-anchor during a rebuild floors at `cf_birth`, not above a missed block (`reanchorCompactFilterChainAtFloor` currently floors at lowest *contiguous in-memory* block and intentionally skips [old cfTip, floor]). **This is the held FIX 2** from the cfheaders-continuity spec.

**P3 — Asset-send cannot fund its DGB fee (Kotlin + native; likely a LIVE blocker).** `AssetManager.sendAsset` funds fee + 6,000-sat markers from Room `getSpendableDigiByteUtxosNow()` (is_asset=0), a partition **nothing ever populates** → `AssetCoinSelector` returns `InsufficientDgb(required=X, available=0)` → "Not enough DGB for fee" even when the home screen shows healthy native DGB. Fix: fund the asset-send DGB fee from native `wallet->utxos` (cleanest by moving asset-tx fee-input selection into native as `BRWalletCreateDigiDollarTransfer` already does, or by exporting `wallet->utxos` to feed the selector). Then delete the dead Room DGB partition, the unused `CoinSelector`, and the ignored `spendableUtxos` plumbing. **Must be confirmed on-device first** — the older "pipeline complete" proofs predate this v3.10.34 Room-fee path.

**P4 — DigiAsset is a parallel Room reconstruction, not the sovereign native set (native accessor + Kotlin).** Add a JNI export of `wallet->assetUtxos` (outpoint+quantity+scriptPubKey); drive display SUM and coin-selection from it; add assetUtxos spent-pruning (P0's `spentOutputs` already computes the spends). Gives assets the DGB/DD invariant: balance = SUM of the exact set you spend from. **This is the guarantee for all three types.**

**P5 — range/percent asset instructions disagree between layers (native).** Native `BRTxOutputIsAsset` matches only `outputIdx == idx` (`BRDigiAsset.c:210`) → a range instruction drops its 700/6000-sat marker into DGB balance (over-count); Kotlin filters range/percent out → inserts a 0-unit asset row. Once P4 makes native the single verdict, fix range as `idx <= outputIdx` and compute percent/range units via the M3 parent-input walk in that one parser.

**P6 — asset OP_RETURN push-length gaps (native + Kotlin, low sev).** Both miss OP_PUSHDATA2 markers; native rejects `scriptLen <= 6`. Handle PUSHDATA2 in the shared parser. Batch with P5.

**How "balance = spendable UTXO set" is guaranteed per type:** DGB and DD already derive both display and selection from the same native array (`utxos`/`ddUtxos`) — no change beyond P0 feeding them a complete tx set. Asset is brought to parity by P4 (single native `assetUtxos` set drives SUM + selection) + P3 (fee from native `utxos`) + assetUtxos spent-pruning. All three then satisfy: one native set per type, balance = SUM(unspent), selection spends that set.

---

## 4. RISKS + on-device verification (operator has wallet + fleet)

**Risks:**
- **Backstop trust:** if pointed at digiscope (default), it's a trusted third party seeing the address set — acceptable as an explicit path; own-node via `setCustomEndpoint` is the trust-minimized option. Must not become the silent *sovereign* reconstruction (that's P0's job).
- **Dust-pending gate:** any backstop-registered DD/asset tx MUST carry confirming height or it's parked in `pendingTx` and credits nothing.
- **P0 correctness/perf:** per-height receipt tracking must not stall the cursor forever on a permanently-missing block — bounded retry then advance-with-record-as-gap. Verify no throughput regression on a full birth→tip walk.
- **P3 is the sharpest live risk:** asset send may be currently broken on any clean wallet (empty Room DGB partition). Confirm before/after.
- **P4 migration:** switching asset display/selection from Room to native `assetUtxos` must preserve the "30-for-10" heal (ownership gate + phantom prune). Native `assetUtxos` is judged only against the native address set (the correct sole authority), so should be safer — but confirm no legitimately-held asset disappears.

**Must verify on-device (Ultra + fleet):**
1. **Backstop proof (build #1):** run "Scan for missing funds" on the wallet missing the ~23.87M DD block; confirm the DD receive `8096…` + the three taproot txs register with height, DD balance credits, and asset rows appear. Own-node path recovers a deliberately CF-skipped tx.
2. **P0 proof:** trigger a from-birth rebuild on the same wallet with NO backstop enabled; confirm the DD block registers (proves CF is now self-complete). Watch native log tag `bread` for cfilter re-request on induced peer drop.
3. **P3 proof:** on a clean-restored wallet with real DGB and a held asset, attempt an asset send; confirm no "Not enough DGB for fee" and the fee funds from native DGB.
4. **P4 parity:** asset balance shown == SUM of native `assetUtxos`; send an asset, confirm immediate decrement (spent-prune) and that a dropped/unconfirmed send un-spends on reconcile; no "30-for-10" regression.
5. **Full 4-type sweep:** wallet holding legacy+segwit DGB, DD (taproot 0-value), and a DigiAsset — one rebuild, confirm all four recover with correct per-type balances tied to concrete UTXOs.
6. **Fleet reality:** run the rebuild across the churny CF fleet (0-peer/bloom-off-majority) to ensure P0's receipt-gating degrades gracefully rather than wedging — do NOT test-drive on the operator's live tethered device; have the operator perform gestures / observe read-only.

**Sequencing:** BUILD #1 backstop (verify #1, recovers the wallet now) → P0 → P2 (so re-anchor doesn't fight the rebuild; verify #2) → P3 (verify #3, may be urgent if assets are shipping) → P4/P5/P6 (verify #4/#5). Backstop's own-node/index track can proceed in parallel.
