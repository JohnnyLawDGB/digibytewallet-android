# Defect note — compact-filter delivery has no accounting, and a DigiDollar receive is deleted

**Date:** 2026-07-25
**Owner:** the `BRPeerManager.c` sequence (`seq/cf-scan-ledger-observe` or a successor)
**Raised by:** `seq/watchset-silent-drops` — these are all in `BRPeerManager.c`, which that branch
does not touch. Nothing here has been changed.

This is **Layer 2** of the Ultra missed-DigiDollar-receive. Layer 1 (the wallet-side half) is fixed
in `seq/watchset-silent-drops`; see `2026-07-25-watchset-silent-drops-design.md` §9. Layer 1 alone
converts a permanent `$0` into a possibly-permanent "pending" — **the permanence lives here.**

---

## D1 — the cfilter cursor advances on SEND, not on receipt (primary)

`autoFetchCFiltersThrough` is set the moment a `getcfilters` is written to the socket
(`BRPeerManager.c:2374-2376`). The struct comment concedes it: *"highest height already requested"*
(`:231`). There is:

- no in-flight request set,
- no timeout,
- no peer rotation on a stalled request,
- no rewind of the cursor in `_peerDisconnected` (`:987`),
- and four silent early-returns in `_peerRelayedCFilter` that consume a height forever, e.g.
  `if (!b) { peer_log(peer, "cfilter: unknown block %s, dropping", …); return; }` (`:2411-2416`).

The **cfheaders** path one function away has all of this — `cfHeadersRequestedThrough`,
`cfHeadersRequestTime`, `cfHeadersPeerAddr`, and `CF_HEADERS_REQUEST_TIMEOUT_SECS` (`:1987`) with
peer rotation. There is no cfilter analogue anywhere in the file.

There is also no second path into the wallet: `fRelay=0` (`BRPeer.c:1826`) means peers never send tx
invs, so there is no mempool route. **One lost cfilter round-trip = one permanently invisible
receive**, on a device documented to drop peers.

**Fix:** give cfilters the delivery accounting cfheaders already has — record the in-flight range,
time and peer; advance a *received* watermark from `_peerRelayedCFilter`; add a timeout with peer
rotation; rewind `autoFetchCFiltersThrough` to the watermark on disconnect.

**Already built and unwired:** `BRPeerManagerRequestCompactFilters` /
`NativeBridge.requestCompactFilters` has **zero callers** anywhere in `app/` or `core/`. The
gap-repair API exists; nothing invokes it.

---

## D2 — a zero-value DigiDollar receive is DELETED as "no stake"

`_requestUnrelayedTxGetdataDone` (`BRPeerManager.c:524-537`) removes transactions with no relays,
guarded by a "does the wallet have a stake in this tx" test written as:

```c
BRWalletAmountSentByTx(...) == 0 && BRWalletAmountReceivedFromTx(...) == 0
```

Both are **pure-DGB satoshi sums**. A DigiDollar token output is zero-value by protocol, so a DD
receive scores zero stake and is deleted from `wallet->transactions` and `allTx`. The same applies
to a zero-value DigiAsset marker output.

This is why the Ultra's reconcile took the clean fresh-register branch rather than the
duplicate/promote branch: the txid had already been purged.

**Fix:** express stake as ownership, not as DGB value — `! BRWalletContainsTransaction(wallet, tx)`
— or add an explicit token test (`BRDigiDollarOutputAmount(tx, j) >= 0 || BRTxOutputIsAsset(...)`).

`dd_unconfirmed_credit_kat` in `seq/watchset-silent-drops` already asserts
`BRWalletAmountReceivedFromTx == 0 && BRWalletAmountSentByTx == 0` for a real DD receive, so the
predicate's behaviour is pinned and this fix has a ready-made test.

---

## D3 — no persisted CF *scan* frontier; the floor is taken from the header tip

Only the cfheaders chain is persisted; there is no record of how far the cfilter *scan* actually
got. On cold start the Kotlin floor (`savedTip - 100` or `cf_birth_height`,
`SyncService.kt:1564-1567`) is overridden by the native clamp at `BRPeerManager.c:3638-3654`, which
snaps `startHeight` to the saved-blocks tip when the height cannot be resolved. Requests are then
hard-floored at `:2368`.

Because cfheaders outrun cfilters by construction (`MAX_CFHEADERS_RESULTS` 2000 vs
`MAX_CFILTERS_RESULTS` 1000, `BRPeer.h:115-116`) and the header tip is saved independently of CF
progress, blocks between the previous session's real cfilter coverage and the persisted header tip
are **permanently skipped**.

**Fix:** persist a real CF scan frontier and floor from that, not from the header tip.

---

## D4 — a void bloom-era justification now opens a permanent hole

The re-anchor at `BRPeerManager.c:3450-3453` still justifies discarding `[old cfTip, floor]` with
*"those blocks were already scanned by bloom in prior sessions"*. **BIP37 was excised in v4.0.0** —
that guarantee no longer exists, so this re-anchor now silently drops a range nothing ever scanned.
The same stale claim is mirrored at `SyncService.kt:1297-1299`.

**Fix:** delete the justification and re-scan the range (or record it as a hole for the ledger).

---

## D5 — `fe == NULL` marks a height evaluated anyway

`_peerRelayedCFilter` calls `BRCFScanLedgerMarkEvaluated(&manager->cfLedger, b->height)` even when
`BRWalletGetFilterElements` returned NULL — allocation failure, or an empty element set. Every other
failure branch in that function deliberately leaves the height outstanding. A block scanned with an
empty match set is therefore recorded as scanned, and the ledger's own contract
(*"scannedThrough advances ONLY over heights actually evaluated"*) is violated.

This is in the ledger sequence's own new code. `seq/watchset-silent-drops` now surfaces the
distinction: `BRWalletFilterElementsGetStats` carries a sticky `allocFailures` counter, so an OOM is
distinguishable from "this wallet has no addresses" — read it via
`NativeBridge.getFilterElementStats()`.

**Fix:** treat `fe == NULL` as fail-open (download the block) or leave the height outstanding, like
the neighbouring branches.

---

## D6 — the v4.0.23 peer-cap reduction preferentially evicts the CF-serving peer

Not a cause of the original incident (the dynamic `maxConnectCount` work post-dates v4.0.20), but it
raises the future rate of D1.

`BRPeerManager.c:2566` evicts from the **tail** of `connectedPeers`, protecting only `downloadPeer`
and the pinned own-node. The tail is exactly what `_BRPeerManagerAnyFilterCapablePeer` (`:3602`) and
the cfilter fallback selector read **first**, since `array_add` appends. So the 8→3 synced-state
reduction preferentially kills the peer holding in-flight CF requests — and under D1 each such
eviction is a permanent scan hole.

**Fix:** exclude a peer with outstanding cfilter requests from eviction, or rewind the cursor when
one is evicted (which D1's fix would give for free).

---

## Suggested order

D2 and D5 are small and independently valuable. D1 is the real fix and the largest. D3 depends on
the ledger landing (it is the natural place to persist the frontier). D4 is a comment plus a
re-scan. D6 falls out of D1.
