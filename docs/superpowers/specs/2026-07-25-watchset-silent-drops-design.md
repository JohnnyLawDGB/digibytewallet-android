# Watch-set silent drops in the address → compact-filter-element path

**Date:** 2026-07-25
**Branch:** `seq/watchset-silent-drops` (app) + `seq/watchset-silent-drops` (submodule, off pin `dc3753e`)
**Status:** design — awaiting approval

---

## 0. Summary, and how this differs from the original brief

Three bugs were reported. Investigation with an ASan probe against the real submodule source
changed the picture materially. The corrected findings:

| Reported | Verdict | What is actually true |
|---|---|---|
| **A** — new Receive address not in the native match set until next `startSync` | **No-op as stated** | `getReceiveAddress` → `BRWalletUnusedAddrs` already `BRSetAdd`s into `allAddrs` *before returning*, and the element set is rebuilt from live wallet state on **every** cfilter (never cached). The address is a filter element the instant the JNI call returns. |
| **B** — DigiDollar address contributes zero filter elements | **True but harmless; drop point is one gate earlier** | The DD *string* is rejected at `BRWalletAddWatchedAddress` (`BRWallet.c:1055`) before `BRWalletFilterElements` ever sees it. But the DD *element* is already emitted by `taprootExternalChain[0]` — byte-identical `51 20 X(Q)`. **Measured: present at index 935 of 1045.** A "fix" would add a duplicate. |
| **C** — `BRWalletAllAddrs` writes past the caller's buffer | **CONFIRMED, high** | Reproduced under ASan. This is the real bug. |

Plus one bug not in the brief:

| New | Verdict | What is true |
|---|---|---|
| **D** — the watched set matches filters but can never credit | **CONFIRMED, high (latent)** | `_BRWalletContainsTx` (`:155`,`:168`), `_BRWalletUpdateBalance` (`:260`) and both amount functions gate solely on the `allAddrs` BRSet. `BRWallet.c:66` deliberately keeps `watchedAddrs` out of it. A watched-only address gets its block **downloaded** and the tx then **discarded**. |

### Evidence (ASan probe, submodule `dc3753e`)

```
Bug C — heap-buffer-overflow, WRITE of size 76 (sizeof BRAddress)
  count call returned : 1046
  growth of 21 addrs  : 1067
  ERROR: AddressSanitizer: heap-buffer-overflow
    BRWalletAllAddrs  BRWallet.c:1011      ← taproot external block

Bug B — the element is already there
  DD address        : DD2kFC8U9uXT8Todwx8PX44ax61eipJ2YyEo2nTu2WyftaLFn7tM
  DD in watch set   : 0                    ← rejected by BRAddressIsValid
  total elements    : 1045   (106 are 34-byte P2TR)
  DD element ALREADY PRESENT: YES (index 935)
```

The overflow is **non-monotonic** in the growth amount — 111/115/130 overflow, 200 does not. That is
why it presents as random heap corruption rather than a reproducible crash.

---

## 1. Bug C — `BRWalletAllAddrs` size-then-fill overflow

### 1.1 Root cause

The root cause is **the size-then-fill contract itself**, not the arithmetic. `BRWalletAllAddrs`
takes and releases `wallet->lock` independently on each call (`BRWallet.c:930` / `:1022`), so
`BRWalletFilterElements.c:18` (size) and `:23` (fill) observe two different wallets whenever any
chain grows in between. The buffer is sized from the old total; the fill computes its write offsets
from the new counts.

The `rest` budget arithmetic is a *consequence-amplifier*, broken in two independent ways:

1. **Missing decrement.** `legExtCount` is computed at `BRWallet.c:976-977` and `rest` is never
   decremented by it — every other chain decrements. `rest` enters the taproot and watched blocks
   inflated by up to 150 (a restored dual-key wallet pregens `legacyExternalChain` at 150).
2. **`addrsCount/4` fallback + `size_t` underflow.** Three chains (`:933`, `:937`, `:949`) clamp to
   `addrsCount/4` rather than `rest`; that value can exceed the true remaining space. Once
   `rest -= X` goes negative it wraps to ~2^64, after which every `array_count(...) < rest` test is
   trivially true and **nothing is clamped at all**.

Two exposed callers today: `BRWalletFilterElements.c:18/23` and `dumpAllAddresses`
(`jni_wallet.c:851/856`) — the latter also heap-over-**reads**, because it reassigns `count` to the
return value and then loops to that bound.

### 1.2 What the evidence does and does not justify

Adding only `rest -= legExtCount` cleared **every** case I could construct, symmetric and
asymmetric. So:

- The missing decrement is the **load-bearing arithmetic fix**.
- The unconditional bounds check is **defence in depth**, not a demonstrated-necessary fix. It is
  still correct to add, because the invariant is not locally provable from the arithmetic and this
  function has now shipped one memory-safety bug of exactly this class.

This document states that distinction rather than overclaiming.

### 1.3 Fix

**C1 — new single-call API** (the actual fix; eliminates the contract):

```c
// Snapshot every address the wallet knows, under ONE wallet->lock hold, so the set
// cannot change between sizing and filling. Caller frees the returned buffer with free().
// Returns NULL and sets *countOut = 0 on allocation failure or an empty wallet.
BRAddress *BRWalletCopyAllAddrs(BRWallet *wallet, size_t *countOut,
                                BRWalletAddrOrigins *originsOut);

typedef struct { size_t derived; size_t watched; } BRWalletAddrOrigins;
```

Implementation: lock → sum `array_count()` over all chains → `malloc` exactly that many → copy each
chain in order → unlock → return. No `rest`, no clamping, no second call, no TOCTOU.

**C2 — keep legacy `BRWalletAllAddrs`, make its fill path unconditionally safe.** Existing callers
(`jni_wallet.c:204/367/851`, `test.c:1903`, host KATs) keep working:

- Add the missing `rest -= legExtCount`.
- Convert **every** write to a single running counter — `addrs[out++] = ...` — guarded by
  `if (out >= addrsCount) goto done;`. This is mandatory: today every write uses a *computed*
  offset (`addrs[i + internalCountSegwit]`, `addrs[off + i]`, `addrs[toff + i]`, `addrs[woff + i]`),
  so a guard on a new counter while the writes keep the old offsets would protect a different index
  than the one being written and the overflow would survive the fix.
- **`done:` sits immediately above `pthread_mutex_unlock(&wallet->lock)`; exactly one unlock and one
  return in the function.** An early `return` inside the lock-held region leaves the wallet mutex
  permanently locked and hard-wedges every wallet API with no crash to diagnose.

**C3 — migrate `BRWalletGetFilterElements`** to `BRWalletCopyAllAddrs`. Also migrate
`dumpAllAddresses` (`jni_wallet.c:851`), so the two existing tests that cover it
(`watched_addr_kat`, `TaprootWatchSetTest.kt`) exercise the same enumeration the filter path uses —
otherwise the two enumerations can drift apart with green tests.

### 1.4 Return contract (explicit — getting this wrong is worse than the bug)

```
addrs == NULL  → return the TOTAL available, unclamped. Never consult addrsCount.
addrs != NULL  → return the number actually WRITTEN (≤ addrsCount).
```

The count-query form is the sizing protocol for five call sites. Returning 0 there would make
`BRWalletGetFilterElements` bail at `BRWalletFilterElements.c:19`, giving a wallet-wide silent miss —
strictly worse than the bug being fixed. The `addrs == NULL` total must be computed from raw
`array_count()` sums so the new bound can never reduce it. This is written into `BRWallet.h`.

### 1.5 Deadlock analysis (why allocate-inside, not hold-across-two-calls)

`BRWalletUnusedAddrs` takes `wallet->lock` **internally**, and the mutex is **non-recursive**
(`pthread_mutex_init(&wallet->lock, NULL)`, `BRWallet.c:353`). The codebase already documents this
constraint at `BRWallet.c:517`:

> `wallet->lock` here — `BRWalletUnusedAddrs` takes it internally (non-recursive).

Therefore:

- **Exporting a lock/unlock pair so a caller can hold it across two `BRWalletAllAddrs` calls is
  unsafe.** `BRWalletAllAddrs` itself takes the lock (immediate self-deadlock), and any intervening
  call into `BRWalletUnusedAddrs`, `BRWalletBalance`, `BRWalletContainsAddress` or
  `BRWalletCreateTransaction` would deadlock. This is the option the brief asked to be analysed; it
  is rejected.
- **Allocating inside the lock is safe.** `malloc` never re-enters `BRWallet`, so `wallet->lock`
  remains a leaf lock with respect to the allocator. No new lock-order edge is created.
- **No new inversion against `manager->lock`.** The existing order is `manager->lock` →
  `wallet->lock` (`_peerRelayedCFilter` holds `manager->lock` across
  `BRWalletGetFilterElements` → `BRWalletAllAddrs`). `BRWalletCopyAllAddrs` occupies exactly the
  same position in that order.
- **`PEER_GUARD` must never be acquired while holding `wallet->lock`.** That is the only ordering
  that would close a cycle against `BRPeerManagerFree`'s peer-thread join. The new JNI getter
  (§5) therefore takes no lock at all.

**Accepted cost:** the `malloc` moves inside `wallet->lock`. Sizing: `sizeof(BRAddress)` is 76;
N ≈ 1045 fresh, 1300–1600 for a used restored wallet → 79–122 KB, above Scudo's 64 KB
primary/secondary boundary, so each call is an mmap. The hold-time delta is *one allocation* — the
11 struct-copy loops are already inside the lock today. This is accepted for correctness; the
per-block rebuild cost is a known follow-up (§8).

---

## 2. Bug D — the watched set can match but never credit

### 2.1 The constraint that shapes the fix

`BRWalletSignTransaction` (`BRWallet.c:1380-1390`) resolves an input address to a key **index** by
linear-scanning the derived chain arrays (BIP84 ×4, legacy ×4, taproot ×2). It does **not** consult
`watchedAddrs`.

So the naive fix — OR a `watchedAddrs` scan into `_BRWalletContainsTx` / `_BRWalletUpdateBalance` —
would credit a coin the wallet **cannot sign for**. Balance would rise, the UTXO would enter
`wallet->utxos`, coin selection would pick it, and the resulting transaction would be unsignable.
That is a fund-safety regression, and worse than the current behaviour. **Rejected.**

The invariant worth preserving is: **an address is safe to credit only if the wallet can sign for
it**, and signing works by index within a derived chain. Therefore *credit ⇔ derived*, which is
exactly why `allAddrs` is the crediting gate.

### 2.2 Fix — make watched addresses genuinely derived (SHIPPED)

`BRWalletAddWatchedAddress` gained a bounded resolution step. It first checks whether the address is
already in `allAddrs` — the overwhelmingly common case, since the address was just handed out by
`BRWalletUnusedAddrs`, and it short-circuits at zero cost. Otherwise it extends the chains in steps
(50 at a time, up to `WATCH_RESOLVE_MAX_SPAN` = 200), re-checking after each step, so an address
that belongs to one of our chains becomes **derived** — and therefore creditable *and signable*.

Beyond that span the address stays watch-only: still matched, still not credited. That limitation is
**pinned by a test assertion** rather than left implicit, because it is the honest boundary of the
fix. The bound is deliberate — each step is EC point maths over six chain/scriptType combinations,
and every derived address also becomes a compact-filter element, so an unbounded search would cost
both CPU and filter bandwidth.

Resolution runs **outside** `wallet->lock`: `BRWalletUnusedAddrs` takes it internally and it is
non-recursive (§1.5).

Verified as a genuine regression test — with the resolve call disabled, four `watched_credit_kat`
checks fail, including "payment to a watched address is RECOGNISED as ours".

---

## 3. Bug A — re-scoped honestly

Fix A as briefed is a no-op for live detection: the address is already in `allAddrs` before
`getReceiveAddress` returns. It is kept only as **post-restart replay durability**, and re-scoped:

- The native pin fires on `Dispatchers.IO` from a scope `WalletManager` owns — **not** synchronously.
  `ReceiveScreen.kt:59-63` calls `getReceiveAddress` ×3 plus `getDigiDollarReceiveAddress` inside
  `remember { }`, i.e. **on the main thread during composition**. A synchronous JNI call that takes
  `wallet->lock` there is the same shape as the v3.10.26/27 Pixel ANR.
- The `SharedPreferences` write stays synchronous and remains the durable replay store, so a dropped
  or late native pin costs nothing.
- **Ordering: A lands strictly after C.** A adds a new writer (`BRWalletAddWatchedAddress` →
  `array_add` under `wallet->lock`) to exactly the window C's overflow needs. Shipping A first
  measurably increases the heap-corruption rate.
- `ChatViewModel.kt:33` calls `NativeBridge.getReceiveAddress(0, 0)` **directly**, bypassing
  `WalletManager`, so A does not cover it. Harmless today (same index-0 address) — a comment records
  the asymmetry.

---

## 4. Bug B — no production code change

The DD element is already present (measured, index 935/1045). B1/B2 would add a duplicate element,
and B1 would additionally push a `DD…` string into `dumpAllAddresses`, which
`ChainReconciliationService` POSTs to `api.digiscope.me` in 500-address batches — a privacy
regression in a wallet whose CF-only design exists so the address set never leaves the device, and a
way for one unparseable entry to fail a whole batch and break "Scan for missing funds".

Making `BRAddressIsValid` DD-aware globally is also **rejected on fund-safety grounds**: it would
unlock `jni_transaction.c:41` and `jni_wallet.c:691`, so a DD address pasted into the plain DGB Send
field would build an ordinary non-zero-value payment to a bare `OP_1 0x20 X(Q)` output — no
`0x…0770` version marker, no OP_RETURN. DD accounting skips non-zero-amount outputs
(`BRDigiDollar.c:134`), so the recipient's DigiDollar balance would show nothing.

What ships instead:

- **B-test** — a KAT asserting the DD element **is** present in `BRWalletGetFilterElements`, so the
  taproot alias is pinned and can never silently break. This is the regression guard the alias has
  never had.
- **B3** — replace the silent `continue` at `BRWalletFilterElements.c:36` with a **rate-limited**
  dropped-element counter (see §5). Never an unconditional per-build log: `BRWalletGetFilterElements`
  runs once per cfilter, up to 1000 per batch, tens of millions of times across a deep sync, and it
  runs with `manager->lock` held — an unbounded log there extends exactly the window the
  lock-contention work is trying to shrink.
- **Comment corrections** — `WalletManager.kt:462-467` claims the pin is what makes DD receives
  visible (it is inert; the taproot chain does the work), and `SyncService.kt:1576` claims "Null when
  DD isn't active" when `getDigiDollarReceiveAddress` has no DD-active check at all.

---

## 5. Observability

A single snapshot updated at element-build time: `{total, derived, watched, digidollar, dropped,
allocFailures}`.

- Stored as `_Atomic` fields (or a seqlock pair) — it is written on peer threads and read from a JVM
  thread; a multi-field struct read non-atomically on ARM is a torn read and formally UB.
- Carries an `owner` pointer so a host KAT building two wallets cannot cross-contaminate counters.
- Logged **only when the tuple changes**, with a floor between repeats.
- Exposed via an accessor in `BRWalletFilterElements.h`; the JNI getter lives in the outer repo's
  `jni_wallet.c` and **takes no lock** — explicitly not `PEER_GUARD` (§1.5).
- Allocation failure logs at ERROR and bumps `allocFailures`, so an OOM is distinguishable from
  "this wallet has no addresses".

`BRPeerManager.c` is **not** touched. The existing per-block `peer_log` at `BRPeerManager.c:2450`
already prints `feCount` and is left alone.

---

## 6. Test plan

| Test | Kind | Asserts |
|---|---|---|
| `allads_bounds_kat` | host, ASan | `BRWalletAllAddrs` count == fill for legacy+taproot+watched; **never** exceeds `addrsCount`; includes the concurrent-growth case (link-wrap `--wrap=BRWalletAllAddrs` to grow the chains between the two calls). Fails on today's code. |
| `allads_bounds_kat` | host, ASan | `BRWalletCopyAllAddrs` returns exact counts and correct `origins` split; empty-chain and OOM paths. |
| `filter_elements_kat` | host, ASan | DD element **is** present (pins the taproot alias); element count by source matches the snapshot; dropped counter increments for an unparseable entry. |
| `watched_credit_kat` | host, ASan | A watched address that resolves to a derived index becomes creditable **and signable**; one that does not resolve stays watch-only and is counted, never credited. |
| Element count non-decreasing | host | Across a simulated session for a wallet with a DD address, reading the same snapshot counter. |
| `dd_unconfirmed_credit_kat` | host, ASan | Registers a DD tx left at `TX_UNCONFIRMED`: asserts today's code credits **nothing** (`ddBalance == 0`), then that `BRWalletUpdateTransactions` with a height releases it — pinning the live-vs-reconcile asymmetry. After U1, asserts the chosen visibility variant. Also asserts `BRWalletAmountSentByTx == 0 && BRWalletAmountReceivedFromTx == 0` for that tx, which is literally the zero-stake deletion predicate at `BRPeerManager.c:534` — documenting why a DD receive is eligible for deletion. |
| `TaprootWatchSetTest` | androidTest | Receive address in the match set without a restart. **Must be androidTest** — `NativeBridge`'s static initializer throws `UnsatisfiedLinkError` on the host JVM, so it cannot be mocked (documented in-repo at `AssetPersistOnDetectTest.kt:19-33`). |

**Pre-existing test rot found, fixed in this branch** (it currently hides regressions in exactly this
code):

- `cf_confirm_kat` **cannot build** — links `BRBloomFilter.c`, deleted in the v4.0.0 bloom excision.
  It is the *only* KAT compiling `BRWalletFilterElements.c`.
- `cf_gate_kat` is **RED** on stale pre-v4.0.0 bloom expectations; the implementation is correct.
- `gcs_match_kat` has a `_main.c` but **no `run.sh`**, so it can never run — and it pins the BIP158
  block-hash byte-order convention.
- **No CI job runs the host KATs at all** (`ci.yml:32-39`), and there is no aggregate runner.
  A `scripts/run-host-kats.sh` is added and wired into CI so this suite's green/red means something.

---

## 7. Sequencing and rebase plan

1. Design doc (this file) approved.
2. Submodule work on `seq/watchset-silent-drops` off pin **`dc3753e`**: C1 → C2 → C3 → D → U1 →
   B-test/B3.
3. App-repo work: A (after C), U2, U3, JNI getter, comment corrections, KAT runner + CI.
4. **No pin bump yet.** When `seq/cf-scan-ledger-observe` merges its pin, rebase the submodule branch
   onto that commit, re-run every host KAT, then pin-bump in the app PR.
5. On-device gate on a build that includes the merged ledger Phase 1, so the ledger logs confirm no
   scan holes during the test window.

**Collision status: clean.** The ledger branch (`44a804d`) touches `BRCFScanLedger.{c,h}` and
`BRPeerManager.{c,h}`. This branch touches `BRWallet.{c,h}` and `BRWalletFilterElements.{c,h}`.
Zero file overlap. If any part of this work turns out to need `BRPeerManager.c`, work stops and the
operator is told.

---

## 8. Out of scope — routed elsewhere

- **Ledger hole (route to `seq/cf-scan-ledger-observe`).** `BRPeerManager.c:2523` marks a height
  evaluated even when `fe == NULL` (allocation failure or empty element set), while the three
  neighbouring failure branches all leave the height outstanding. A block scanned with an empty
  match set is recorded as scanned. Not touched here — that file belongs to the ledger sequence.
- **Element-list caching.** The per-block rebuild runs ~2×N `BRAddressScriptPubKey` calls (~2100
  base58/bech32 decodes) inside `manager->lock`, every block. Caching behind a wallet generation
  counter makes it O(1). Deferred by decision — keeps this branch a reviewable correctness fix.
- **Gap-limit collapse.** `BRWalletRegisterTransaction` (`BRWallet.c:1528`) extends chains by the
  bare `SEQUENCE_GAP_LIMIT_*` (10 external / 5 internal) while every load-time pregen uses `+100`.
  Once first-unused passes ~100 the look-ahead cushion is spent. Filed, not fixed here.
- **`dgb_watched_addrs` is not network-suffixed**, unlike every other per-network store, so a
  mainnet↔testnet toggle mixes watch sets. Filed.

---

## 9. Ultra missed-DigiDollar-receive — root cause

> Folded into this branch by decision. v4.0.20 shipped as the fix for this symptom and is **100%
> inert in both arms**, so the real cause was never addressed and is currently believed fixed.

The symptom is **two layers**, and only the first is in this branch's files.

### 9.1 Layer 1 — why it is *invisible* rather than *pending* (in scope, `BRWallet.c`)

A DigiDollar token output is **zero-value** by protocol — the dollar amount lives in the OP_RETURN.
`TX_MIN_OUTPUT_AMOUNT` is 54,600 dsat (`BRTransaction.h:41`), so a DD output **always** trips the
dust check at `BRWallet.c:233-235`, and the `continue` at `:245-249` skips the **entire output
loop** — which contains the only DD credit site (`:267`, `array_add(wallet->ddUtxos, …)`).

This is precisely why the bug is DigiDollar-specific. A plain DGB receive *is* credited at 0-conf,
so a lost confirmation leaves only a cosmetic "Pending". A DD receive shows **$0 — nothing at all**.

The gate is scoped to `blockHeight == TX_UNCONFIRMED` and `_BRWalletUpdateBalance` recomputes
`ddBalance` from scratch each pass (`:189-197`), so on its own this is a *delay*, not a permanent
miss. What makes it permanent is Layer 2.

### 9.2 Layer 2 — why the confirmation never arrives (OUT of scope, `BRPeerManager.c`)

In CF-only mode there is exactly **one** live event that ever attaches a confirming height:
`_peerRelayedBlockTxns`, reached only via cfilter MATCH → `getdata(full block)` → block message.
That round-trip has **no retry of any kind**:

- `autoFetchCFiltersThrough` advances when the `getcfilters` is **SENT**, not when it is answered
  (`BRPeerManager.c:2374-2376`); the struct comment concedes it — *"highest height already
  requested"* (`:231`).
- No in-flight set, no timeout, no peer rotation, no rewind in `_peerDisconnected`. The cfheaders
  path one function away has all four (`cfHeadersRequestedThrough`, `cfHeadersRequestTime`,
  `cfHeadersPeerAddr`, `CF_HEADERS_REQUEST_TIMEOUT_SECS`); there is no cfilter analogue.
- `BRPeerManagerRequestCompactFilters` — the targeted re-request API — has **zero callers**. The
  gap-repair mechanism is already built and unwired.
- `fRelay=0` (`BRPeer.c:1826`) means peers never send tx invs, so there is no mempool fallback.

One lost round-trip on a device documented to drop peers = one permanently invisible receive.

**And then it is deleted.** `_requestUnrelayedTxGetdataDone` (`BRPeerManager.c:524-537`) removes txs
with no relays, guarded by a "does the wallet have a stake" test written as
`BRWalletAmountSentByTx(...) == 0 && BRWalletAmountReceivedFromTx(...) == 0`. Both are **pure-DGB
sums**, and a DD token output is zero-value — so a DD receive scores zero stake and is **deleted from
the wallet**. That is also why the reconcile took the clean fresh-register branch: the txid had been
purged.

### 9.3 Why "Scan for missing transactions" always works

`registerRawTransaction` sets `tx->blockHeight`/`timestamp` from the node **before**
`BRWalletRegisterTransaction` (`jni_transaction.c:421-424`), so the first `_BRWalletUpdateBalance`
already sees a **confirmed** tx and the dust gate at `:230` is never evaluated. The credit lands
immediately. The asymmetry between the live and reconcile paths is the whole bug.

The in-tree comment at `jni_transaction.c:400` already names this mechanism — the knowledge existed
and was never connected to the symptom.

### 9.4 Fix

**In scope, this branch:**

- **U1 (`BRWallet.c:233-235`)** — stop letting a consensus-legal zero-value DD/asset token output
  poison the whole transaction's accounting. The gate exists to defend against unconfirmed dust
  spam, not against a protocol-mandated 0-value token.

  **Chosen variant: visible immediately, credited on confirmation.**

  **As implemented, this is narrower than first designed, and the reason matters.** The first
  attempt exempted protocol 0-value outputs from the dust check outright. That worked — and broke
  the confirmation path: dropping out of the dust check also drops the tx out of `pendingTx`, and
  `BRWalletUpdateTransactions` gates its recompute-on-confirm on `pendingTx` membership
  (`BRWallet.c:1751`). The DD credit then never landed at all. Caught by the KAT, which is why the
  KAT asserts both halves.

  Shipped instead: the tx stays pending exactly as before, but the pending branch now records
  `tx->outputs[].address` into `usedAddrs` before it `continue`s. That is the whole observable
  defect — an unconfirmed DD receive left *no trace* in the wallet's address bookkeeping, so the
  Receive screen kept handing out the same address. Credit semantics are untouched: still nothing
  at 0-conf, still released on confirmation, so no 0-conf DigiDollar credit is introduced.

  The display amount was never the problem: `digiDollarTxAmount` (`jni_transaction.c:545`) computes
  from the transaction directly via `BRDigiDollarOutputAmount` + `BRWalletContainsAddress`, none of
  which depend on the output loop. A registered DD tx has always been able to show its dollar
  amount; what it could not do was confirm, credit, or rotate the address.
- **U2 (`SyncService.kt`)** — the confirmation-reconcile backstop that recovers a stranded tx runs
  **only** inside `onSyncComplete`, gated on `pending > 0` and 5-minute debounced
  (`SyncService.kt:1808-1818`). An already-synced wallet receiving live never gets another
  `onSyncComplete`, so the backstop is dormant exactly when it is needed. Move it onto the existing
  keepalive tick with a longer debounce and a tx-age condition. Pure Kotlin, zero native risk,
  self-heals the symptom under **every** surviving cause.
- **U3** — de-document `ea590b14` as the resolution of this symptom, so the project narrative stops
  masking the real defect. `CLAUDE.md` turned out to carry no such claim; the false attribution
  lived in two code comments (`WalletManager.getDigiDollarReceiveAddress` and the SyncService pin
  block), both now replaced with the measured facts and a "do not re-add this" note. The SyncService
  block also stopped adding the DD address, which additionally fixes the `BIP158: pinned N …` log
  over-reporting the watch set by one.

**Routed to the `BRPeerManager.c` sequence** (see §8): cfilter delivery accounting + timeout +
rewind-on-disconnect; wire up `BRPeerManagerRequestCompactFilters`; persist a real CF **scan**
frontier rather than flooring from the saved-blocks header tip; fix the zero-stake deletion
predicate at `:534` to use `BRWalletContainsTransaction` instead of DGB-satoshi sums; and delete the
now-void bloom-era justification at `:3450-3453` ("already scanned by bloom in prior sessions" —
BIP37 was excised in v4.0.0, so that re-anchor now opens a permanent hole).

### 9.5 Refuted — recorded so they are not re-investigated

- **DD amount decode overrun** (`BRDigiDollar.c:126`, fixed `int64_t amounts[64]`). Real mechanism,
  fails the whole tx closed — but a normal 1-recipient DD transfer is nowhere near 64 pushes.
  Latent hardening item only. *(REFUTED, confidence 88.)*
- **Taproot chain absent/truncated when the payment arrived.** All four wallet-construction paths
  call `BRWalletSetTaprootKey` before sync can start. *(REFUTED, confidence 87.)*
- **Rejected at registration.** Nothing in `BRWalletRegisterTransaction` / `_BRWalletContainsTx`
  rejects a DD tx that the reconcile path accepts — both run the identical ownership test on
  identical bytes. It is *lost after* registration, not rejected at it.
- **The v4.0.20 watch-set pin.** Inert (§0). Dead end.

### 9.6 The decisive on-device test — deliberately not a host KAT

A KAT can confirm the dust gate exists but **cannot** discriminate "tx was in the wallet but
stranded unconfirmed" from "tx never entered the wallet" — and that distinction is the whole
question. On the next DD receive that fails to appear, **before** tapping "Scan for missing
transactions" (reconciling destroys the evidence, which is what happened the first time):

1. Dump `NativeBridge.getTransactionDetails()` and grep the DD txid; read field 3 of
   `txHash|amount|fee|blockHeight|timestamp|sent|received`.
   - **Present, field 3 == 2147483647** (`TX_UNCONFIRMED`) → Layer 1, stranded-pending. In scope.
   - **Absent entirely** → Layer 2: never delivered, or deleted by the zero-stake predicate. Route.
2. Split the Layer-2 sub-causes with existing log lines, no rebuild needed:
   `adb logcat -v time | grep -E "cfilters: auto-requested|cfilter: block [0-9]+ —|cfilter: unknown block|applied pending auto-fetch"`
   - DD block height never inside any `cfilters: auto-requested [A..B]` range → scan floor clamped
     above it (cross-session hole).
   - Height inside a requested range but no matching `cfilter: block H — matching …` line →
     requested, never delivered, never retried (intra-session hole).
