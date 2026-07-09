# Compact-Filters-Only Pipeline Audit — the map to kill bloom

**Date:** 2026-07-09. **Method:** 5-lens grounded audit of the real C core (BRPeerManager.c, BRWallet.c, BRPeer.c, BRCompactFilterChain.c) + JNI, adversarially synthesized.
**Verdict:** `COMPACT_FILTERS_ONLY` **detects and registers** txs (balance moves) but **never confirms** them — the sole `blockHeight` setter is structurally coupled to bloom's `merkleblock` delivery. Incoming AND the wallet's own sends stay `TX_UNCONFIRMED` forever in CF-only. **This is the last real blocker to bloom deprecation.**

## What already works in CF-only (bloom-free today)
| Process | Status | Where |
|---|---|---|
| Incoming-tx detection + registration | ✅ complete | cfilter match `BRGCSFilterMatchAny` (BRPeerManager.c:2325) → `BRPeerSendGetdataBlocks` (:2336) → full block `_BRPeerAcceptBlockMessage` (BRPeer.c:939-990) → `_peerRelayedTx` → `BRWalletRegisterTransaction` (:1194). BIP158 basic filters include every output + prevout scriptPubKey, so receives AND spends match. |
| Balance / UTXO | ✅ complete | `_BRWalletUpdateBalance` (BRWallet.c:1428), fires independent of sync mode. **This is why the user SEES the tx + balance but it never confirms.** |
| Outgoing broadcast | ✅ complete | `BRPeerManagerPublishTx` (BRPeerManager.c:2907), mode-agnostic. |
| Relay-back / seen / relay-count | ✅ complete | `_peerHasTx` / `_peerRelayedTx` increment `txRelays`, no bloom dependency. |

## THE LINCHPIN — confirmation (blockHeight assignment)
**Bloom-coupled.** The sole confirm setter `BRWalletUpdateTransactions` (BRWallet.c:1661) is reached only via `_BRPeerManagerUpdateTx` (BRPeerManager.c:1578, :1604, reorg :1663), all gated `if (txCount > 0)` where `txCount = BRMerkleBlockTxHashes` — populated **only by a BIP37 merkleblock**. The CF full-block handler (BRPeer.c:936-938) deliberately skips `relayedBlock`. In CF-only the chain extends headers-only (`totalTx == 0`), so `txCount` is always 0 → confirmation never fires.

**The fix (validated by the "measure confirmations by depth" insight):** the block height is **already known** at the CF match site — `_peerRelayedCFilter` does `b = BRSetGet(manager->blocks, &blockHash)` → `b->height` (logged BRPeerManager.c:2299/2331). After the CF-downloaded full block's wallet txs register, call `_BRPeerManagerUpdateTx(walletTxHashesInBlock, block->height, txTime)` → `BRWalletUpdateTransactions`. **No merkle proof needed** — the full block carries all tx bytes; only the block→height correlation is missing. Confirmations then derive by depth (`tip − height + 1`) automatically as the header chain grows.

## Gaps to close (ordered) — the "ground zero" scope
1. **CF confirmation driver (THE fix, highest value).** Thread the matched `block->height` through full-block completion and confirm the block's wallet txs via the existing `_BRPeerManagerUpdateTx`/`BRWalletUpdateTransactions` primitive. Fixes incoming + outgoing + stranded-send churn at once.
2. **Decouple the confirm primitive from `BRMerkleBlockTxHashes`.** Give the CF full-block path (BRPeer.c `_BRPeerAcceptBlockMessage`) a way to surface `(blockHash → wallet tx-hash list)` so `_BRPeerManagerUpdateTx` runs at the CF block height without a merkleblock.
3. **Hoist the watch-set pre-extension out of bloom-only code.** The `+100` gap-window pregen (BRPeerManager.c:372/377-385, skipped by the `_BRPeerManagerLoadBloomFilter` early-return in CF-only) and the per-tx taproot `+window` pregen (BRPeerManager.c:1218-1258, gated `bloomFilter != NULL`) feed `BRWalletGetFilterElements`/`allAddrs` — the BIP158 match set. Without them a cold scan can miss receives to gap-edge addresses. (`MISSING`/`GAP`.)
4. **Mainnet CF-peer retention.** `_peerRelayedPeers` (BRPeerManager.c:1138-1144) retains gossiped `NODE_COMPACT_FILTERS` peers only under a `BRNetworkIsTestnet()` guard; drop it for mainnet so CF-only holds filter peers without depending on seeder injection. (This is the retention half descoped from the own-node work — now required.)
5. **Define mempool / pre-mine behavior.** BIP157/158 has no mempool filter — CF-only surfaces an incoming tx only once **mined** + cfilter-matched (`fRelay=0`, no `filterload`, BRPeer.c:1762 / BRPeerManager.c:372). Product decision: accept mine-time-only visibility (document it) **or** add explicit mempool polling. Sent txs are unaffected (the wallet knows its own tx).
6. **Rebroadcast rework + tests.** Once #1 lands, retire the `blockHeight`-only stranded-send rebroadcast reliance (SyncService.kt:1489-1548); interim-gate on relay-count/age. Add an on-device regression proving a CF-only incoming AND outgoing tx transitions `UNCONFIRMED → confirmed` at the correct height.

## Then: excise bloom (the `X.0.0` major)
`filterload`, `BRBloomFilter`, the `merkleblock`/`_peerRelayedTx`-via-bloom handlers, `SyncMode.BLOOM_ONLY`/`BOTH`, the 120s watchdog fallback, the accept-gate bloom branch, DNS-seed bloom stamping. Only after 1–6 are proven.

## Quickest unblock (today, no C surgery)
Default is `SyncMode.BOTH` — bloom runs **parallel** with compact filters, so `bloomFilter` is non-NULL, a `filterload` is sent, peers emit `merkleblock`s, and confirmation fires normally. **The bug is scoped ONLY to opt-in `COMPACT_FILTERS_ONLY`.** Keep affected users on BOTH until the CF confirm driver (#1) lands; flag CF-only as "detection works, confirmation pending."
