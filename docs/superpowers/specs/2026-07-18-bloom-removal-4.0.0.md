# Bloom Removal → BIP157/158-only (4.0.0) — Removal Spec

**Date:** 2026-07-18
**Status:** Approved direction (user decision 2026-07-08 to deprecate bloom; 2026-07-18 to execute as 4.0.0); inventory complete; ready for implementation plan.
**Supersedes/updates:** `docs/superpowers/specs/2026-07-08-bloom-deprecation-bip158-only.md` (draft) + `docs/superpowers/specs/2026-07-09-cf-only-pipeline-audit.md`.

## Goal

Excise the BIP37 bloom-filter data path entirely — the wallet becomes **BIP157/158 compact-filters-ONLY**, so the address set never goes on the wire under any condition. This is the reserved **X.0.0 (4.0.0)** trigger (CLAUDE.md Versioning Policy). Gates satisfied: CF peer diversity (oracle-bootstrap v3.10.33), mainnet CF accept-gate + own-node CF peer (PR #22 / v3.10.1), CF-confirmation driver (PR #26), own-node first-class pairing (merged). The own node is the sovereign fallback, not bloom.

**Batched into the same submodule fork-push:** the Chang native accessor `BRWalletInternalAddrs` (completes the C9 heal — see `2026-07-18-sovereign-native-asset-balance-design.md`).

## Non-goals

- Retiring the `SyncMode`/`BRSyncMode` enum to a single constant — KEEP the 3 values this release (ABI contract; follow-up once no `BLOOM_ONLY` comparison remains).
- Model A (wallet↔node JSON-RPC) — future.
- Wiping the `sync_mode` pref — leave orphaned (already keep-as-noop).

## Change sets

### A. SUBMODULE (`digibytewallet-core` C core) — ONE fork-push + pin-bump (the X.0.0 trigger)

**DELETE (whole file + drop from CMake source list):** `BRBloomFilter.h`, `BRBloomFilter.c`.

**EDIT (surgical bloom-only excision — KEEP the shared spine):**
- `BRPeer.h` — remove `MSG_FILTERLOAD/FILTERADD/FILTERCLEAR` + `MSG_MERKLEBLOCK` send/dispatch usage, `BRPeerSendFilterload`/`BRPeerSetNeedsFilterUpdate` protos. KEEP `MSG_HEADERS/BLOCK/GETDATA/INV/TX`, all `MSG_*CF*`, `BRPeerSetCompactFiltersOnly`, `relayedBlock` callback, `BRMerkleBlock` refs. `SERVICES_NODE_BLOOM 0x04` may stay as a protocol constant, but every gating USE migrates to CF.
- `BRPeer.c` — remove `_BRPeerAcceptMerkleblockMessage`, `BRPeerSendFilterload`, `BRPeerSetNeedsFilterUpdate`, the merkleblock dispatcher branch; collapse `sentFilter`/`needsFilterUpdate` branches in inv/tx handlers to their CF side (woven into shared inv/getdata/tx path — do NOT blind-delete). KEEP `relayedBlock` registration + headers(576)/block(632) fire-sites; collapse `_BRPeerAcceptInvMessage` to the CF branch (block inv → `relayedBlockInv`, never `getdata(merkleblock)`). Version `relay=0` flag (1887) STAYS (de-bloom comment only).
- `BRPeerServices.h` — collapse `BRPeerServicesAllowedForSyncMode` to CF-only (accept iff `SERVICES_NODE_COMPACT_FILTERS`).
- `BRPeerManager.h` — remove `BRPeerManagerFallbackToBloom` proto+doc; flip DEFAULT + null-sentinel off `BLOOM_ONLY`.
- `BRPeerManager.c` — remove `#include BRBloomFilter.h`; struct fields `bloomFilter/fpRate/averageTxPerBlock/filterUpdateHeight`; `_BRPeerManagerLoadBloomFilter` + reload cluster (`_updateFilterRerequestDone/_updateFilterLoadDone/_updateFilterPingDone/_BRPeerManagerUpdateFilter/_loadBloomFilterDone`) + `BRPeerManagerFallbackToBloom`. COLLAPSE (not delete): `_BRPeerManagerLoadMempools`, `_peerConnected` bloom hooks, `_peerRelayedTx` gap-refresh, `_peerRelayedBlock` (remove fpRate 1625-1649 + bloom bailout 1657-1665, KEEP from 1666 on), the `BLOOM_ONLY` guards, DNS mask + shotgun ordering, `Set/GetSyncMode` null-default, TOFU re-anchor comments. **KEEP + RE-HOME `_BRPeerManagerPregenAddrWindow` onto the CF path (top risk).**
- `BRWalletFilterElements.h/.c` — KEEP (CF local-matching, not bloom); re-point header comment cross-ref from `_BRPeerManagerLoadBloomFilter` → `_BRPeerManagerPregenAddrWindow`.
- `BRMerkleBlock.h/.c`, `BRWallet.h`, `BRWallet.c`, `BRChainParams.h` — KEEP all code; comment-only de-bloom. `BRWallet.c` 1529-1539 legacy walk-forward: KEEP unconditional.
- `test.c` — remove `BRBloomFilterTests` + runner + include; KEEP CF tests.
- **ADD (Chang):** `BRWalletInternalAddrs(wallet, addrs[], count)` — enumerate the internal (change) chains only (`internalChainSegwit`, `internalChain`, `taprootInternalChain`, + legacy variants when `hasLegacyKey`), mirroring `BRWalletAllAddrs`.

### B. OUTER-REPO (jni_*, Kotlin) — normal PR, lands with the pin-bump

- `jni_peer.c` — REMOVE `fallbackToBloom` impl (1235-1245); COLLAPSE `_applyPendingBip158State` (1099-1106) + `setSyncMode` (1183-1185) guards → UNCONDITIONAL `SetSaveFilterHeaders`; flip `g_pendingSyncMode` default → `COMPACT_FILTERS_ONLY`; RETARGET `INJECT_DEFAULT_SERVICES` (320) `NODE_BLOOM` → `NODE_NETWORK|NODE_COMPACT_FILTERS`; de-bloom `bridge_syncStopped` comments. **ADD:** JNI wrapper `dumpInternalChangeAddresses()` → `BRWalletInternalAddrs`.
- `jni_wallet.c`, `jni_transaction.c` — reword bloom log/comment strings.
- `NativeBridge.kt` — KEEP `SyncMode` enum ints + `setSyncMode/getSyncMode`; REMOVE `external fun fallbackToBloom()` (lockstep w/ JNI); update stale default-BLOOM doc. **ADD:** `external fun dumpInternalChangeAddresses(): String`.
- `NativeCallback.kt` — KEEP `onSaveFilterHeaders` (exact name+sig); reword doc.
- `CustomNode.kt` — `syncModeFor` already CF; KEEP signature; drop the `sync_mode` pref param.
- `SyncService.kt` — REMOVE dead `bloomFallbackActive` flow, `bloomRecoveryActive`/block-stall machinery (740-775), `FALLBACK_BLOOM` arm; COLLAPSE `injectBloomPeers`→CF-only (`capability=filter`); drop `sync_mode` reads (1098/1177). KEEP re-anchor recovery + `injectFilterPeers`/`injectDandelionPeers`/`injectTestnetPeers` + `BLOCK_CATCHUP_GRACE`. Rename `injectBloomPeers`→`injectPeers` (5 sites).
- `SyncWorker.kt` — CF-only peer fetch (`capability=filter`).
- `Bip158WatchdogPolicy.kt` (+ test) — rename `FALLBACK_BLOOM` → `STAY_ON_FILTERS`/`GIVE_UP`; KEEP re-anchor decision core.
- `PeerServicesPolicy.kt` — KEEP (CF tag parser); doc-only.
- `WalletViewModel.kt`, `WalletScreen.kt` — REMOVE dead bloom-fallback flow relay + `BloomFallbackBanner`.
- `ReconcileScreen.kt` — rewrite user copy → "compact filters (BIP157/158)".
- `AssetManager.buildChangeScriptHexes` — rewire to `dumpInternalChangeAddresses()` (enables the C9 heal); flip `legacyHealDryRun=false` only after on-device dry-run confirms candidates.
- Pref owners (see COLLAPSE): `StaleDataWiper`, `WalletDataEraser`, `SyncWorker`, `SyncService`; + `SendScreen`, `MainActivity`, `DigiByteApp`, `NetworkState`, `OutgoingTxStore` prose.

## KEEP list (the traps — enumerate in the plan)

1. `BRMerkleBlock.c/.h` — the struct is ALSO plain block HEADERS (header chain, orphan storage, difficulty, CF stop-hash). Deleting it destroys sync, no compile error.
2. `relayedBlock` callback (field 230 / wiring 1595 / fire-sites 576+632) — the header-chain feed ("merkleblock OR headers"). Only the merkleblock fire-site (881) goes with its handler.
3. `_peerRelayedBlock` body from 1666 on — orphan handling, chain extend, `_BRPeerManagerClearMemory`, CF autofetch kick (1709-1713). Remove only fpRate + bloom bailout.
4. `_BRPeerManagerPregenAddrWindow` (439-447) — bloom-INDEPENDENT gap+100 look-ahead for legacy/segwit/taproot. KEEP + re-home onto CF.
5. `BRWalletFilterElements.c/.h` — the CF LOCAL-matching path (the privacy win itself).
6. `BRWallet.c` 1529-1539 legacy walk-forward — now CF's SOLE gap-extension feed; KEEP unconditional.
7. `_peerRelayedTx` address-window extension incl. taproot pregen (1376-1379) — keep the pregen; drop only the `bloomFilter!=NULL` recheck.
8. `BRPeerSetCompactFiltersOnly` — becomes always-on.
9. Kotlin CF-era load-bearing: `setSyncMode/getSyncMode`, `onSaveFilterHeaders`, `syncModeFor`, `PeerServicesPolicy.parseSeederServicesHex`, `reanchorCompactFilterChainAtFloor` + REANCHOR/AWAIT_REANCHOR decision, `injectFilterPeers`/`injectDandelionPeers`, `BLOCK_CATCHUP_GRACE`, `ChainReconciliationService`.
10. Version `relay=0` flag (1887) — CF-only wallets still want it.

## Collapse decisions

- **SyncMode enum:** KEEP 3 values + `set/getSyncMode` (ABI contract — Kotlin int cast straight to `(BRSyncMode)`; renumbering silently mis-selects). Only make CF the default + delete `fallbackToBloom`. Full enum retirement = follow-up.
- **C-core DEFAULT mode:** flip to `COMPACT_FILTERS_ONLY` — `BRPeerManagerGetSyncMode(NULL)` null-default (3636) + `g_pendingSyncMode` init (jni_peer.c:1089). Defense-in-depth against a missed `setSyncMode`.
- **`BLOOM_ONLY` guards in BRPeerManager.c:** `==BLOOM_ONLY return` early-returns (2196/2757/3704/3792) never-taken → DELETE. `!=BLOOM_ONLY` enable-gates (1499 retention, 1710 CF kick, 3026 CF pre-pass) always-true → SIMPLIFY to unconditional.
- **Watchdog:** remove `BRPeerManagerFallbackToBloom` + JNI + Kotlin `external fun` + `FALLBACK_BLOOM` arm + `bloomRecoveryActive`. Watchdog reduces to: healthy | header-catchup | one-time re-anchor. REANCHOR vs AWAIT_REANCHOR grace STAYS.
- **Seeder fallthrough → CF-only:** `injectBloomPeers` default `fetchFromSeeder()` → `fetchFromSeeder("filter")` (or drop the secondary pool; `injectFilterPeers` already fetches `capability=filter`). `SyncWorker` → `capability=filter`. Native DNS mask (935) → `NODE_COMPACT_FILTERS`; shotgun bloom-first ordering (3072-3168) inverted/neutralized (modern CF nodes ship bloom OFF → bloom-first sorts the WRONG peers first); `INJECT_DEFAULT_SERVICES` → CF.
- **Prefs:** `sync_mode` → keep-as-noop (drop reads, leave key). `dgb_bloom_peers` → this is the LIVE CF peer cache under a legacy name; rename to `dgb_filter_peers` WITH migration read, touching ALL 4 owners together (SyncService `peer_pool` shape, SyncWorker `peers_json` shape, StaleDataWiper, WalletDataEraser) + add a clear for the new key in both wipers + `WalletWipeTest`. Safe fallback if the rename feels too broad: keep the name as-noop, only fix fetch capability + prose.

## Safe ordering (compiles + syncs at each step)

**Stage 0 — Outer-repo dead-code sweep (no behavior change).** `fallbackToBloom` (Kotlin+JNI in lockstep), `bloomFallbackActive` flow + VM relay + `BloomFallbackBanner`, `bloomRecoveryActive`/block-stall, `FALLBACK_BLOOM` arm; rename policy enum + fix test. Verify: `./gradlew testMainnetDebugUnitTest` green.

**Stage 1 — Outer-repo peer-path CF-hardening.** Retarget `INJECT_DEFAULT_SERVICES`, un-guard CF-header save + CF default, seeder → `capability=filter`, pref rename+migration. Compiles against UNCHANGED submodule. Verify: **on-device cold CF sync → 100%**, `/proc/net/tcp :2EF8` real peers, FilterHeaderStore file grows, grep no `injectBloomPeers`/`dgb_bloom_peers` residue.

**Stage 2 — Submodule KEEP-safe internal collapse (no symbol deletion).** Re-home `_BRPeerManagerPregenAddrWindow` onto CF; collapse handlers to CF sides; simplify `BLOOM_ONLY` guards; flip null-default. Bloom funcs still exist, uncalled. **ADD the Chang accessor here** (additive, CF-neutral). Verify: native+app build; **regression-test a receive past the gap limit**; on-device sync + a send that consumes an address then receives change.

**Stage 3 — Submodule delete now-unreferenced bloom symbols.** After grep proves zero callers: delete the loader cluster + `FallbackToBloom` + struct fields; `_BRPeerAcceptMerkleblockMessage` + dispatcher branch + `SendFilterload`/`SetNeedsFilterUpdate`; collapse `BRPeerServicesAllowedForSyncMode`; migrate `SERVICES_NODE_BLOOM` uses; remove `MSG_FILTER*`/merkleblock send; delete `BRBloomFilter.c/.h` + CMake + `test.c` bloom test. Verify: native links clean (proves no dangling ref); full suite; on-device cold sync.

**Stage 4 — Prose de-bloom + Chang wiring + fork-push + pin-bump.** Final gate grep the ENTIRE tree for `filterload`/`filteradd`/`filterclear`/merkleblock-message-send/`NODE_BLOOM`-gating/`BRBloomFilter`/`SendFilterload` → zero live wire/gating hits. Then `./scripts/pre-publish-test.sh` (API 28/33/34/35) + on-device cold sync Note8 + S25 Ultra. Fork-push the single submodule commit (bloom excision + Chang accessor) → pin-bump → tag 4.0.0.

## Top regression risks + guards

1. **Pregen re-home (HIGHEST).** CF's gap+100 may today ride on the bloom loader's `_BRPeerManagerPregenAddrWindow` call (loader early-returns for CF at 457). Delete loader without re-homing → CF match window decays to bare gap → silently missed look-ahead receives. Guard: add explicit CF-side pregen call + regress-test a past-gap receive in Stage 2 BEFORE deleting the loader in Stage 3.
2. **Struct-vs-message conflation.** Deleting `BRMerkleBlock`/`relayedBlock` nukes the header chain, no compile error. Guard: the KEEP list; remove only the merkleblock MESSAGE handler + dispatcher branch + fire-site 881.
3. **CF dispatcher branch accidental deletion.** Removing an `MSG_CF*` branch instead of `MSG_MERKLEBLOCK` breaks CF sync, no compile error. Guard: line-by-line diff review + on-device sync (Stage 3).
4. **Enum/ABI drift.** Freeze the int values; keep the enum; unit assertion pinning Kotlin ints == native.
5. **Peer-selection inversion.** Modern CF nodes advertise bloom OFF; leaving/blind-deleting the bloom-first ordering/DNS-mask/`INJECT_DEFAULT_SERVICES`/bloom-off penalty (1082-1086) dials/penalizes the CF peers we need → 0-peer/churn wedge. Guard: migrate every `SERVICES_NODE_BLOOM` gating/ordering use to `NODE_COMPACT_FILTERS`; verify `/proc/net/tcp :2EF8` on device.
6. **CF-header persistence guard collapse.** UN-guard (not delete) `SetSaveFilterHeaders` — deleting the block kills CF-header saving → the 512MB OOM-loop class returns. Verify FilterHeaderStore file grows on device.
7. **TOFU safety-net void (design).** Re-anchor liveness justified single-peer TOFU with "bloom runs in parallel"; that net is void once bloom is gone. Guard: route to the Neutrino CF review R1/R2 (checkpoints / close-liar) as a follow-up decision.
8. **Multi-owner pref rename desync.** `dgb_bloom_peers` = 2 on-disk shapes × 4 owners; partial rename strands discovery or stops the wipe. Guard: rename all owners in one change + migration read + `WalletWipeTest`.

## 4.0.0 contents

Sovereign asset balance (merged) + "built for 9.26" (merged) + Chang heal (native accessor, this batch) + bloom removal (this spec). First legitimate major since the 3.5.x BIP157/158 landing retired the old "4.0.0 = BIP158" trigger.
