# TX-QUEUE: one send at a time, one send waiting (4.1.6 design)

> **Status 2026-11-22: fifth revision. Corrected against the independent check of the fourth
> revision: 4 earlier points it found answered only in part, and 11 new findings (section
> 10.7). Closed for build, subject to the three questions in section 9.1 and any veto of the
> list in section 9.2.**
>
> The owner decided, all on 2026-11-22, in his words:
>
> - **D1, first:** *"we can queue as deep as we want, but we can't initiate until we have at
>   least 3 confirmations on previous transaction."*
> - **D1, amended. This is the one that stands:** *"it make sense for the queues to be only one
>   deep and then refuse, so that the user has to be available to complete/verify the send."*
> - **D2:** *"wait only"*. No "send anyway" when coins are waiting.
> - **D3:** *"cancel this send. 4.1.6 works fine"*. Cancel is built in 4.1.6.
> - **D4 and D5, to the two questions put to him after the third revision:** *"1. Go with
>   recommended, 2. Yes wait til sync"*.
>   - **D4** (the first of the two: the reading of "one deep", question 1 of the third
>     revision): one send in flight plus one waiting. While a send is waiting, a new send is
>     refused even when the waiting one is ready. The user reviews it or removes it first.
>   - **D5** (the second: question 8 of the third revision): after a restore, a rebuild from
>     chain or the crash-loop breaker, no send starts until the wallet has reached the chain
>     tip once.
>
> The waiting send is a stored request. It is not signed in advance and it never goes out on its
> own. The user approves it, with the PIN or biometric, when its turn comes.

Design only. No production code.

Sources: this branch's tree at `64935e2d7` (app and bridge) and core `8014392`, the submodule
commit it pins. Every line cited for a point of section 10.7 was opened in that tree for this
revision. Cites carried over from the fourth revision, in sections 1 to 4 and elsewhere, were
not opened again. `origin/develop` has since moved to `442de80fe` and pins core `c3236de`. It
differs from this tree in `SyncService.kt`, the reconcile package and `jni_peer.c`. The cites
were **not** re-checked against it: whoever builds a PR opens each one again. Paths under `core:`
are the C core (`native/src/main/jni/digibytewallet-core/`). Paths under `kt:` are
`core/src/main/java/app/aroundtheblock/wallet/core/`. Paths under `app:` are
`app/src/main/java/app/aroundtheblock/wallet/`. Paths under `jni:` are
`native/src/main/jni/bridge/`.

## 0. The short version

- No new send from this wallet starts while a send from this wallet is on the network with
  fewer than 3 confirmations. The wallet's own sync must have proven them.
- The rule covers plain DGB, DigiDollar and DigiAsset sends, and any sweep that spends one of
  this wallet's coins. It is decided by what a transaction spends, not by which screen made it.
- The rule is one function in the shared core. It is checked in three places: at the signer,
  when the wallet takes the send into its list, and when the send is handed to the network. The
  middle one decides. A refused send is not published and is not left in the wallet.
- After a restore, a rebuild from chain or the crash-loop breaker, no send starts until the
  wallet's **scan** of the chain has reached the tip once. Headers at the tip are not enough.
- While one send is confirming, the user can leave **one** more send waiting. It is a note of
  what to pay: payee, amount, kind. Nothing is signed.
- Any further send is refused while one is waiting, ready or not (D4).
- When the first send has 3 confirmations, the wallet says the waiting send is ready. The user
  opens it, sees the normal review screen built on the coins as they are then, and approves
  with the PIN or biometric.
- Nothing signs, and nothing goes on the network, without the user there. The PIN rule has no
  exception.
- Every send is in the wallet's saved list **before** it goes on the network. A crash cannot
  leave a send on the network that the wallet does not know.
- A send that does not confirm is shown as "Not confirmed yet". The user can try again or
  **cancel it**. Cancel is a small payment to yourself that spends the same coins, behind its
  own PIN prompt. It has its own narrow signing entry, which builds the cancel itself. The
  entry belongs to the peer manager, so the send it replaces leaves the wallet and the
  network list together.
- Before the PIN prompt of any review, the wallet says so when the payee may already have been
  paid: a cancel that lost, a send that was removed, a send to the same address that confirmed.
- No new automatic question to a server. No automatic removal. No override.
- Size: three core changes and seven app changes. Section 8 has the order.

## 1. What the wallet does today

### 1.1 Plain DGB sends (C core)

- Selection runs three passes over the wallet's coins: confirmed coins, then the wallet's own
  unconfirmed change, then uneconomic coins as a last resort
  (`core:BRWallet.c:1917-1936`, `_BRWalletCreateTxForOutputsAt`).
- A coin is selectable on pass 0 only when confirmed. On pass 1 only when its transaction is the
  wallet's own unconfirmed send (`core:BRWallet.c:1780-1784`, `_BRWalletUtxoSelectable`).
- "Own" means every input spends a wallet output and the transaction pays out no more than it
  spends. The walk goes up to **25 generations** and 10,000 visits
  (`core:BRWallet.c:1752-1775`, `_BRWalletTxIsOwnUnconfirmed`, `SELECT_WALK_BUDGET`).
- An unconfirmed coin received from someone else is **never** selected
  (`core:BRWallet.c:1777-1778`). It still counts in the balance, so the balance can be higher
  than what a send can use. The Send screen then says only "Insufficient balance"
  (`kt:TransactionBuilder.kt:414`).
- The same predicate serves every other builder: `BRWalletSelectableUTXOs`
  (`core:BRWallet.c:1796-1826`), `BRWalletOutpointSelectable` (`:1833-1855`),
  `BRWalletMaxOutputAmount` (`:3325-3357`). One change to the predicate reaches all of them.
- The gate is about ownership and depth only. It does not ask whether a peer took the parent,
  or how many ancestors the new send would have in total.
- Off by one, confirmed by the first critique (point 12): DigiByte Core refuses a transaction when
  `ancestors + 1 > 25`, so a transaction counts itself and may have at most 24 unconfirmed
  ancestors. The walk starts at depth 25 on the coin's transaction (`core:BRWallet.c:1783`), so
  it accepts a coin with 24 ancestors and the new send then has 25. One too many.
- The limit is by generation, not by count or size. A send that merges several unconfirmed
  coins can pass the walk and still exceed 25 ancestors or 101 kvB.

### 1.2 "Pending" in the core

- `pending` is a narrower word in the core. An unconfirmed transaction is pending when it is
  over 100 kB, has an output below `TX_MIN_OUTPUT_AMOUNT` (54,600 sat), signals replace-by-fee,
  has a future lock time, or spends a pending transaction (`core:BRWallet.c:444-461`).
- A pending transaction's outputs are **not credited** until it confirms (`:460-482`). Its
  inputs are still marked spent (`:439-442`).
- An unconfirmed transaction whose input is already spent by an earlier wallet transaction is
  `invalid` (`core:BRWallet.c:417-431`). Order decides which of two unconfirmed conflicts loses.

### 1.3 DigiDollar sends

- A DigiDollar output is credited only when confirmed, at or above the activation height
  (`core:BRWallet.c:538-539`). Unconfirmed DigiDollar change cannot be spent.
- A DigiDollar send has zero-value outputs, so it is always pending. None of its outputs are
  credited until it confirms, its DGB change included (`core:BRStaleSend.h:6-7`,
  `core:BRWallet.c:448-449`). A DigiDollar send cannot be chained on.
- Its DGB fee coins come from the plain set through the same predicate
  (`core:BRWallet.c:2196-2212`, `_BRWalletUtxoSelectableAny`). So a DigiDollar send **can** sit on
  top of a plain-send train, as its last car.
- Fee floor 0.1 DGB, every output at least $1.00, change included (`core:BRWallet.c:2014-2016`).

### 1.4 DigiAsset sends

- Asset inputs must be confirmed by the indexer to hold exactly that asset at that quantity.
  Anything else is left out of this send, or the send is refused as `Unconfirmed`
  (`kt:asset/send/AssetSendSelection.kt:65-78`, `core:BRAssetSendPlan.h:78-84`).
- The change marker of an unconfirmed outgoing asset send is not stored as a holding at all
  (`kt:asset/AssetManager.kt:1761-1783`). So unconfirmed asset change cannot be sent.
- An asset send has 600-sat outputs, so it is pending in the core. Its DGB change is not
  credited until it confirms.
- Its DGB fee coins come from `BRWalletSelectableUTXOs` (`core:BRAssetSend.c:484-487`). Same
  exposure as DigiDollar: it can be the last car of a plain-send train.
- A send that was abandoned and re-sent reads `CONFLICTED`, so its change is not counted twice
  (`kt:asset/AssetSpentState.kt:7-28`).

### 1.5 Sweep and recovery paths

- A legacy sweep spends coins found by a scan and pays the wallet's own address
  (`kt:recovery/LegacySweepService.kt:229-403`). Its inputs are not wallet outputs, so the sweep
  is not "own unconfirmed". The swept coins are spendable only after the sweep confirms.
- The scan can list the same outpoint twice, once at height 0. The confirmed copy wins
  (`kt:recovery/UtxoDedup.kt:27-33`).
- The asset recovery fan-out waits for its split to confirm before moving anything
  (`kt:recovery/ForeignAssetTransferService.kt:117-122`).
- These paths do not build trains.

### 1.6 Two sends at the same instant

- A signed send claims its inputs for 60 s until it is registered
  (`core:BRCoinReservation.h:4-9`, `:55-58`). The signer also checks the inputs are still unspent
  and still selectable (`core:BRSpendGate.h:10-19`, `core:BRPreparedSend.h:27-37`).
- This stops a double spend. It does not stop a chain.
- (Corrected.) Android does not call those core headers. The bridge has its own copies:
  `jni:spend_guard.h`, `jni:signed_spend_gate.h`, `jni:coin_reservation.c`, and
  `_registerWalletCopy` for the registration (`jni:jni_transaction.c:162-174`). The core's
  header says so (`core:BRWalletSend.h:12-14`). There are four signers on Android, not one:
  `seed_sign_transaction` (`jni:jni_wallet.c:490-514`) and three recovery signers that sign
  with keys of their own and then pass `wallet_signed_spend_gate` (`jni:jni_derive.c:635-648`,
  `:964-977`, `:1258-1270`).

### 1.7 Facts the design rests on

- **Every height the shipped wallet writes is one it proved.** (Corrected; the second revision
  said a server-reported height is stored like a proven one.) `BRWalletUpdateTransactionsUnproven`
  has one caller, `core:BRPeerManager.c:11117`, and it sits in the `#else` of the comparison
  arms (`:11110-11119`). The production arm returns `BR_SERVER_HEIGHT_UNPROVABLE` and changes
  nothing. Since 4.1.3 a server height either triggers a P2P re-check of that block
  (`RECHECKING`, `:11083-11104`) or is dropped. So no new "proven" bit is needed: the height
  is the proof.
- **The core's `WALLET_SIG_ALWAYS` mark is not a "height unproven" flag the gate can use.**
  (`core:BRWallet.c:2490-2501`.) It is set when a transaction is first registered on a
  server's word (`:2621`, `:2663`; `jni:jni_transaction.c:859-866`), and such a transaction is
  registered unconfirmed. It stays set after the wallet later proves the height over P2P, and
  it is not saved. A gate that read it would treat a proven send as unproven until the next
  restart.
- **The height is what survives a restart.** The transaction checkpoint stores, per
  transaction, its size, height, timestamp and bytes, and nothing else
  (`core:BRWallet.c:1289-1311`). `WALLET_SIG_ALWAYS` is not saved. The checkpoint includes
  unconfirmed sends, with their full signed bytes. (Corrected.) Today a send writes the
  checkpoint **after** it is published, not right after it is registered: registration and
  publish are one native call (`jni:jni_transaction.c:558-568`), and the checkpoint is written
  after that call and after the record (`kt:TransactionBuilder.kt:311-328`). Section 5.6
  changes that order.
- **The wallet's tip can go down.** "A lower tip is accepted (reorg, rescan)"
  (`core:BRWallet.c:3108-3116`). At load, before the peer manager reports the real tip, the tip
  is the highest confirmed height among the loaded transactions (`core:BRWallet.c:626-637`).
  Heights are unsigned, so `tip - height + 1` wraps to a huge number when the tip is below the
  height.
- **The background worker does nothing without a loaded wallet.** `SyncWorker` returns at once
  when no wallet is loaded (`app:service/SyncWorker.kt:79-82`). A wallet is loaded by an
  unlock. After a process death or a reboot, nothing happens until the user opens the app and
  unlocks it.
- **Locking the app zeroes the seed and nothing else.** The wallet object and the peer manager
  stay loaded (`jni:jni_wallet.c:474-480`, `kt:WalletManager.kt:598-602`). Publishing a signed
  transaction needs no seed.
- **A confirmed transaction always sorts before an unconfirmed one.** The wallet orders by
  height, and "unconfirmed" is the greatest height (`core:BRWallet.c:161-162`, insert at `:224`).
  Of two conflicting transactions, the earlier in that order is valid and the later is invalid
  (`:417-431`). So the chain, not arrival order, decides between a confirmed transaction and an
  unconfirmed one. (Corrected.) Between two unconfirmed ones that do not depend on each other,
  the position of their outputs on the wallet's legacy address chains decides first, and
  arrival order only after that (`core:BRWallet.c:124-133`, `:205-214`). Change goes to a
  segwit address, which that comparison does not read, so for an ordinary send arrival order
  does decide.

## 2. What happens after broadcast

- The send is registered in the wallet before it is published, in the same native call, so
  its coins are spent at once (`core:BRWalletSend.h:14-15`, `:36-39`;
  `jni:jni_transaction.c:558-568`). The registration returns nothing, and the publish follows
  whatever it did.
- A published transaction the wallet does not hold is registered, as trusted, when a peer asks
  for it (`core:BRPeerManager.c:5161-5162`).
- `Broadcaster.broadcast` stems to one Dandelion peer when the setting is on and a capable peer
  is connected. Otherwise it floods (`kt:dandelion/Broadcaster.kt:57-65`).
- A stem arms an embargo of 10 to 30 s. If no peer relayed the send back by then, the wallet
  floods it (`kt:dandelion/Broadcaster.kt:90-105`, `kt:dandelion/DandelionBroadcastPolicy.kt:6-16`).
  Embargoes live in memory only (`Broadcaster.kt:37-38`).
- The stem peer is "any Dandelion peer" at that moment (`core:BRPeerManager.c:9811`). A child
  stemmed a few seconds later can go to a different peer than its parent.
- The core answers each publish once: 0, `EINVAL`, `ENOTCONN`, `ETIMEDOUT` (20 s of silence),
  `ECANCELED` (`kt:sync/PublishOutcome.kt:13-22`, `core:BRPeerManager.c:55`).
- **A verdict of 0 means a peer asked for the bytes. It does not mean a node accepted the send.**
  (Corrected; the first draft said "a peer took it".) The verdict and the `txRelays` record are
  set when a peer sends `getdata` for the send (`core:BRPeerManager.c:5119-5160`). A node asks
  for any announced transaction it does not have, before it checks it. A send that is then
  refused has the same record as an accepted one. The core's own header records such a case
  (`core:BRPublishOutcome.h:17-20`).
- `txRequests` is the other list, and it is not evidence either. It holds the peers **this
  wallet** asked about a transaction and that have not answered
  (`core:BRPeerManager.c:1441-1444`). (Corrected; the first draft read it as "a peer requested
  the send".)
- What does prove a mempool has the send: a peer serving it back, or a peer announcing it
  (`core:BRPeerManager.c:2704-2729`, `:2837-2858`). The wallet rarely gets this, because it
  never asks a peer that already fetched from it. So this design does not use relay evidence to
  decide anything. It uses confirmations.
- Nodes do not send reject messages any more. Silence is the usual sign of a refusal, and it is
  also what a slow relay looks like, so a timeout is never final
  (`core:BRPublishOutcome.h:14-28`).
- The Send screen waits up to 25 s and shows "sent" only on a verdict of 0. Otherwise it shows
  "not yet relayed" (`kt:TransactionBuilder.kt:399-402`, `app:ui/wallet/SendViewModel.kt:381`).
  Nothing stops the next send in either case.

### 2.1 The stranded-send sweep (every 90 s with peers)

`app:service/SyncService.kt:3715-3860`, interval at `:4512`.

- Works from the per-wallet record of sends (`kt:OutgoingTxStore.kt:8-20`).
- Confirmed: settled, never published again. Unconfirmed: published on three sweeps in a row,
  then once an hour (`core:BRStrandedSend.h:14-26`, `:66-72`;
  `app:service/StrandedSendSelector.kt:111-121`).
- It works through the sends **in txid order** (`StrandedSendSelector.kt:108`). A child can be
  published before its parent. A node that lacks the parent treats the child as an orphan.
- It skips a send whose embargo is still running (`SyncService.kt:3834`).

### 2.2 When are a send's coins released

1. **Refused by the network.** Plain DGB only. Peers in two network groups rejected it, none
   relayed it, and that held for 10 minutes on one peer manager. The send is removed and its
   coins return (`SyncService.kt:3757-3789`, `kt:sync/RefusalHold.kt:7-23`, `:82`). Modern nodes
   rarely send rejects, so this fires rarely.
2. **Invalid.** Another wallet transaction spent its inputs, and that held for 10 minutes
   (`SyncService.kt:3790-3824`, `kt:sync/InvalidSendRemoval.kt:6-13`).
3. **Stale.** One day old, the pinned node says "transaction not found", and every input is
   unspent there (`core:BRStaleSend.h:9-22`, `:53`). This runs **only when the user opens the
   reconcile screen** (`app:ui/settings/ReconcileScreen.kt:301`; no other caller of
   `clearStuckSends`, `kt:WalletManager.kt:706`). It answers only when the endpoint is the
   default one (`kt:reconcile/DgbNodeClient.kt:274`, `core:BRStaleSend.h:109`). A user with a
   custom endpoint never gets it. Its clock needs a timestamp above 0
   (`core:BRStaleSend.h:87`), and the peer manager sets an unconfirmed transaction's timestamp
   to 0 when too few peers relay it (`core:BRPeerManager.c:1379-1381`). Whether that stops the
   clock for exactly the sends that went nowhere has not been measured.
   (Corrected: the *removal* is manual, but a stuck send's **txid** already goes to the endpoint
   with no user action. `SyncService.maybeRunConfirmationReconcile` runs on every sync-complete
   and on the keepalive tick, at most once in 5 minutes, and asks
   `{endpoint}/explorer/tx/<txid>` for every unconfirmed wallet transaction
   (`app:service/SyncService.kt:4211-4236`, `kt:reconcile/ChainReconciliationService.kt:442-448`,
   `kt:reconcile/DgbNodeClient.kt:211-221`). Its inputs are not sent on that path.)
4. **Orphan or dead.** Same manual pass (`kt:WalletManager.kt:762-799`).

- Removing a transaction removes everything built on it (`core:BRWallet.c:2768-2803`). So
  releasing the root of a train releases the whole train.
- The core's own at-tip cleanup removes an unrelayed unconfirmed **receive**. It never removes a
  send, because the wallet has a stake in it (`core:BRPeerManager.c:1266-1380`, the stake rule at
  `:1340-1362`).
- A rescan moves the tip back and fetches the chain again. It does not remove unconfirmed
  sends. A confirmed one gets its height back. A rebuild from chain discards the local
  transaction cache, so an unconfirmed send is gone from the wallet after it
  (`kt:WalletManager.kt:838-859`). It also erases the app's records of sends (`:899`).
- Asset or DigiDollar sends are never removed by rule 1. The asset layer reads an abandoned
  attempt as `CONFLICTED` (`kt:sync/PublishOutcome.kt:76-81`).

### 2.3 The gap, in one paragraph

A valid send that no node ever took keeps its coins for at least a day, and then only if the
user finds the reconcile screen. Meanwhile its change looks spendable. Each further send takes
that change, signs a child, and publishes a transaction no node can accept. The sweep republishes
them in an order that does not help. That is a train, and nothing tells the user.

## 3. Why a train gets stuck on DigiByte

Checked on the local node (DigiByte Core 9.26.7, mainnet, synced, read-only RPC and help text).
The critique re-checked the limits in the local 8.26.0 tree (`src/policy/policy.h:61-67`,
`src/kernel/mempool_options.h:24`); the 8.22.2 column was not re-checked:

| Policy | 8.22.2 | 9.26.7 |
|---|---|---|
| Ancestor count limit | 25 | 25 |
| Ancestor size limit | 101 kvB | 101 kvB |
| Descendant count limit | 25 | 25 |
| Descendant size limit | 101 kvB | 101 kvB |
| Mempool expiry | 336 h (14 days) | 336 h |
| Min relay fee | 100,000 sat/kvB | 0.001 DGB/kvB (the same) |
| Dust relay fee | 30,000 sat/kvB | 0.0003 DGB/kvB (the same) |
| Incremental relay fee | 10,000 sat/kvB | 0.0001 DGB/kvB (the same) |
| Full RBF | not checked | off (`getmempoolinfo`: `fullrbf false`) |

These are the same numbers as Bitcoin's 25/101. The limits are not the usual cause. A user
rarely reaches 25. The causes that do happen:

1. **The parent never reached a mempool.** A stem that died at the stem peer. A peer set that
   never asked for it. A process killed during the embargo. Every child is then an orphan
   everywhere. Nodes do not relay orphans.
2. **Blocksonly peers.** They accept the connection and never take or relay a transaction.
   Note: this node reports `localrelay: true` and its config has no `blocksonly` line today,
   which differs from the September note. Its `minrelaytxfee` is set to 0.00001, below the
   default, so its mempool accepts sends that default nodes refuse. It is a poor witness for
   "the network took it". Three of its twenty peers report `relaytxes: false`.
3. **Not a cause here: a fee below the relay floor.** The builder never pays less than
   100 sat/vB, whatever custom total is entered (`core:BRWallet.c:113-121`, `_txFee`;
   `kt:send/FeeQuote.kt:26-36`). A send from this wallet is not dropped for its fee by a default
   node.
4. **A parent refused as non-standard or dust.** Same result, and no reject message arrives.
5. **Parent and child stemmed to different peers.** The child arrives where the parent is not.
6. **A parent that left the mempools.** Expiry after 14 days, a node restart, or eviction. The
   wallet keeps both, and the child has no parent anywhere.
7. **A conflict.** A second device on the same seed, or a re-send, spends the parent's input.
   The parent dies, and every child with it.
8. **No fee bump exists.** Inputs are final (`core:BRTransaction.h:48`) and full RBF is off.
   The child-pays-for-parent code is commented out (`core:BRWallet.c:1976-1979`). A stuck parent
   cannot be pushed.

How long 3 confirmations take, measured on the local node over the last 400 blocks: median
36 s, 90th percentile 180 s, longest 608 s. The average spacing over 2,000 blocks was 14 s. So
"about a minute" is the usual case, not a promise.

With 15-second blocks, a healthy send confirms before most people finish typing the next
address. So a child built on unconfirmed change is, more often than on Bitcoin, a child built on
a parent that has a problem.

## 4. What comparable wallets do

From memory of their behaviour, not re-checked for this draft.

- **Bitcoin Core.** Never spends unconfirmed coins from others. Spends its own unconfirmed
  change only after confirmed coins cannot cover the send, and then in steps: first change with
  very few unconfirmed ancestors, then more, up to the mempool limits. It can refuse a send that
  would break the chain limits (`walletrejectlongchains`; the 9.26.7 help text says default 1).
  It rebroadcasts its own unconfirmed sends and offers `abandontransaction` for one that is in
  no mempool.
- **Electrum.** Spends unconfirmed coins by default, with a setting for confirmed only. Shows
  each coin's state. Offers fee bump, child-pays-for-parent, and removal of a local transaction
  that never left the device.
- **BlueWallet.** Marks sends replaceable and offers bump and cancel. That is its answer to a
  stuck send.
- **Breadwallet line (this core's ancestor).** Spends own change freely. Drops unconfirmed
  transactions that no connected peer has, once the mempools are heard. This wallet has since
  narrowed both rules (sections 1.1 and 2.2).

No common wallet runs a background queue that signs or sends later. This design does neither:
the user approves each send when it goes (section 5).
## 5. The design

### 5.1 The whole rule

1. **The gate.** A send from this wallet may start only when no send from this wallet is on the
   network with fewer than 3 proven confirmations.
2. **One waiting send.** While the gate is closed, the wallet can hold one request to send.
   While a request is held, every further send is refused, whether the request is still waiting
   or ready (D4).
3. **The user approves at the time.** When the gate opens, the wallet says the request is
   ready. The user reviews it and approves it. Only then is anything built, signed or sent.
4. **History first.** After a restore, a rebuild from chain or the crash-loop breaker, no send
   starts until the wallet has reached the chain tip once (D5). "Reached the tip" means its
   scan has, by the one definition in 5.2.

There is no signed transaction held on the device. There is no chain signed in advance, no
automatic release, no pause rule and no "held" set in the core.

### 5.2 The gate

**What counts as "a send from this wallet".** A transaction in the wallet's list that is valid
and spends at least one of this wallet's coins. That is read from the wallet's own
transactions, not from the app's record of what it sent. **It is decided by content, not by
path:** the same question is asked of whatever a screen, a sweep or a recovery tool hands the
signer.

| Kind | Must wait for the gate | Closes the gate while under 3 confirmations |
|---|---|---|
| Plain DGB send | Yes | Yes |
| DigiDollar send | Yes | Yes |
| DigiAsset send | Yes | Yes |
| Any sweep or recovery move that spends at least one of this wallet's coins (for example the user typed this wallet's own phrase into the sweep) | Yes | Yes |
| A sweep or recovery move that spends only another phrase's coins | No. To this wallet it is a receive. | No |
| "Cancel this send" (section 6.4) | No. It is the way out, and it has its own entry. | Yes |
| A send made on another device with the same phrase | (not ours to hold) | Yes, once this wallet sees it |
| An incoming payment | n/a | No |
| A republish of a send the wallet already holds | No | (already counted) |
| A transaction the wallet holds as invalid | n/a | No. The chain or an earlier transaction has beaten it (`core:BRWallet.c:417-431`). |
| Digi-ID | Not a transaction | No |

**How confirmations are counted.**

- A transaction with no height has 0 confirmations.
- A transaction with a height has `mark - height + 1`, where the **mark** is the highest tip
  this wallet has verified since a height was last taken away from any of its transactions.
- Why a mark and not the current tip. A rescan moves the wallet's tip back to a checkpoint and
  keeps every transaction's height (`core:BRPeerManager.c:9603-9631`). A peer manager built on
  a floored block window does the same (`core:BRPeerManager.c:5533`). Counting against the
  current tip would read every recent send as 0 for the whole rescan, which can be hours, and
  Rescan is the cure this design sends users to. A height the wallet proved is still proven
  when the tip moves back for a rescan.
- The mark is kept in the wallet beside `blockHeight`. `BRWalletSetBlockHeight`
  (`core:BRWallet.c:3116`) raises it and never lowers it. A reorg lowers it:
  `BRWalletSetTxUnconfirmedAfter` (`core:BRWallet.c:3084-3106`) already takes the heights away
  from the transactions the reorg orphans, and it sets the mark to the join height in the same
  hold of the lock.
- At load the mark starts at the higher of two numbers: the highest height among the loaded
  transactions (what `_BRWalletSeedBlockHeight` does today, `core:BRWallet.c:626-637`), and the
  mark saved with the transaction checkpoint. Without the saved one, a restart in the middle of
  a rescan would read the newest send as 1 confirmation until the scan passed it.
- If the mark is below a height, the count is 0. By the rules above that cannot happen. It is
  guarded anyway, because heights are unsigned and the subtraction would wrap.
- "Proven" needs no new bit. Since 4.1.3 the wallet writes a height only when it matched the
  transaction to a block itself (section 1.7). A server's answer never becomes a height
  (`core:BRPeerManager.c:11060-11119`), so it never opens the gate.
- 3 is the owner's number. It is one constant in one core header.
- The messages never show a count the wallet cannot stand behind. While the wallet is behind
  the tip and the send has no height, the text is "catching up", not "0 of 3".

**"At the tip": one definition.** Wherever this design says "at the tip" it means the same
thing. It is the test Kotlin already applies before it marks a wallet synced
(`app:service/SyncService.kt:1398-1403`, and again at `:3064-3072`). All of these hold:

- the header tip is at or above the newest built-in checkpoint, and within 5 blocks of the
  height the peers report;
- the compact-filter **scan** has reached it too: `getLowestNeededHeight() - 1` is within 10
  blocks (`CF_TIP_SLACK`, `:4437`) of that height. A frontier that reads 0 means "unknown" and
  never counts as caught up;
- the bridge is not in a rescan (`g_isRescanning`, `jni:jni_peer.c:130`).

The header tip alone is never enough. The core says "sync succeeded" when the headers arrive
(`core:BRPeerManager.c:1494-1505`), and the scan can be far behind. Kotlin records a measured
case of 60,817 blocks not yet scanned at that moment (`app:service/SyncService.kt:1387-1392`).

The two Kotlin sites become one function, `SyncTip.atTip()`. Every user of the words calls it:
"history complete" below, "Ready" (5.3), the clocks and the third state (6.2), the reading of
notes and records (5.7, 6.7).

**History first (D5).**

- The wallet carries one flag: "history complete". The gate is closed while it is off. The
  screen says: "Your wallet is rebuilding its history. You can send when it has finished."
- **Where it lives.** In the core's wallet object, under the wallet lock. G1, G2 and G3 read it
  in the same hold as the rest of the rule. It is off in a new wallet object.
- **Who sets it.** The platform, through one core call. **The peer manager never sets it.**
  (The fourth revision said it did. That would have fired at the header tip.) On Android,
  Kotlin calls it from two places only:
  1. at load, when the saved key is on and the transaction checkpoint loaded whole;
  2. when `persistSyncCompletionState()` has returned true
     (`app:service/SyncService.kt:1436`, `:3080`). Both callers run only at the tip by the
     definition above. The function returns true only after the transaction checkpoint is on
     disk (`:4008-4009`) and `has_synced` is committed (`:4035-4039`).
- **The bridge's rescan after the first sync.** In a wallet that has never synced, the bridge
  answers the core's first "sync complete" by starting a rescan, and does **not** pass that
  signal to Kotlin (`jni:jni_peer.c:311-327`). So it reaches neither place above, and the flag
  stays off through that rescan. As a second lock on the same door, the native call that sets
  the flag refuses while `g_isRescanning` is set and the flag is off. A wallet that synced
  before skips that rescan (`jni:jni_peer.c:1528-1539`).
- **A rescan of a wallet whose flag is on leaves it on.** A rescan keeps the transactions and
  their heights. That is item K of section 9.2.
- The saved value is a new key, `send_history_complete`, in `dgb_sync_data`. It is written in
  the same commit as `has_synced` (`app:service/SyncService.kt:4035-4038`).
- It is turned off, on disk and in memory, wherever the saved transactions are cleared:
  `clearSyncData` (`kt:WalletManager.kt:1911-1912`, the whole file), the crash-loop breaker
  (`app:StaleDataWiper.kt:56`, the whole file), the rebuild's clear
  (`kt:sync/HistoryRebuildOnUpgrade.kt:536-545`, a new line in that list), and the start that
  finds the checkpoint shorter than it declared (`app:service/SyncService.kt:2255-2273`). Where
  a wallet object is loaded at that moment (the rebuild from chain), the same step turns the
  flag off in memory.
- A wallet above the transaction-checkpoint limit never gets `has_synced` today. Section 5.6
  says what that means for this flag. It is question 2 of section 9.1.
- It is **not** cleared by the other reasons for the filter-chain reset, which keep the
  transactions (`app:service/SyncService.kt:2275-2291` removes `has_synced` for every reason).
  That is why the gate does not reuse `has_synced`: that reset re-fetches headers for hours
  with the history intact.
- First start after the update: the key is absent. If `has_synced` is on and the checkpoint
  loaded whole, the key is written on. A wallet that was synced on 4.1.5 can send at once.

**Where the gate is enforced.** Three checks, one rule. The rule is in the shared core
(`BRSendGate.h` and one function in `BRWallet.c`, under the wallet lock).

- **G1, at the signer.** So that no signature leaves native memory for a send that cannot
  start. Android has two signer exits and both get the check.
  - `seed_sign_transaction` (`jni:jni_wallet.c:490-514`) asks **before** it signs.
  - The three recovery signers sign first, with keys of their own, and then pass
    `wallet_signed_spend_gate` (`jni:jni_derive.c:635` then `:648`; the gate's body at
    `jni:jni_wallet.c:529-537`). For them the check comes **after** the signature. A refusal
    frees the signed transaction inside the same native call (`jni:jni_derive.c:648`). The
    signature never reaches Kotlin.
  - The core's own copy for iOS gets the check in `BRSignedSpendGate` (`core:BRSpendGate.h:68`).
- **G1 while the history is not complete.** "Spends a wallet coin" is read from the wallet's own
  transactions (`_BRWalletOwnedInputs`, `core:BRWallet.c:2506-2527`). A wallet that is still
  rebuilding does not know its coins yet. A sweep of this wallet's own phrase would read as
  another phrase's coins and pass. So while the flag is off, G1 **refuses all three recovery
  signers, whatever they spend**, and G2 and G3 refuse anything the wallet does not already
  hold. The screen says: "Your wallet is rebuilding its history. You can sweep when it has
  finished." The cost: a sweep of another phrase's coins also waits for the scan.
- **G2, when the wallet takes the send. This one decides.** A new registration mode,
  `BR_WALLET_REGISTER_OWN_SEND`, beside the three that exist (`core:BRWallet.h:380-382`).
  `_BRWalletRegisterTx` (`core:BRWallet.c:2603`) puts a record among the wallet's transactions
  in **two** places, and G2 sits before both, in the same hold of the wallet lock:
  - the ordinary insert, for a transaction the wallet does not hold (`:2624-2631`);
  - the promote branch, which takes a record the wallet already keeps as "not mine" and makes
    it one of the wallet's transactions (`:2646-2670`). It is a second insert.

  In both: if the record spends a wallet coin and the gate is closed, it is not inserted and
  not promoted, and the outcome is the new `BR_WALLET_REGISTER_GATE_CLOSED`. Two sends approved
  in the same instant cannot both pass: the second one's check sees the first one's insert.
- **G3, when the send is handed to the network.** `BRPeerManagerPublishTx`
  (`core:BRPeerManager.c:9903`) and `BRPeerManagerStemPublishTx` (`:9802`) refuse a transaction
  that spends a wallet coin and that the wallet does not hold as valid. Two peer handlers can
  also put a listed transaction into the wallet, and today both register it as plain trusted:
  `_peerRequestedTx`, when a peer asks for it (`:5161-5162`), and `_peerHasTx`, when a peer
  announces it (`:2868-2872`). Both change to the own-send mode. `_peerRequestedTx` serves
  nothing when that is refused. With the publish refusal in place a refused send is never
  listed, so neither handler can meet one. The change is there so that this stays true if a
  later change lists one. So a send that skipped G2 cannot reach the wire, and cannot enter the
  wallet through a peer's request or a peer's announcement.

The peer-relay path (`core:BRPeerManager.c:2752-2754`) and the server import
(`jni:jni_transaction.c:866`) keep their modes. They are how another device's send arrives. They
are not gated, and what they bring in closes the gate.

**Every Android path, traced.**

| # | Path | Kotlin | Signs in | Registers and publishes in | Checked at |
|---|---|---|---|---|---|
| 1 | Plain DGB | `kt:TransactionBuilder.kt:293-333` | `signPreparedTransaction`, `jni:jni_transaction.c:479` (`seed_sign_transaction`) | `publishTransaction` `:558-568` or `publishTransactionStem` `:618-635`, through `kt:dandelion/Broadcaster.kt:57-65` | G1, G2, G3 |
| 2 | DigiDollar | `kt:TransactionBuilder.kt:375-392` | `sendApprovedDigiDollar`, `jni:jni_transaction.c:1148` | the same call, `:1170-1181` | G1, G2, G3 |
| 3 | DigiAsset | `kt:asset/AssetManager.kt:3266-3306` | `buildAndSignAssetTransferTx`, `jni:jni_asset_send.c:293` | `Broadcaster.broadcast`, as path 1 | G1, G2, G3 |
| 4 | Legacy sweep | `kt:recovery/LegacySweepService.kt:363`, `:419` | `buildAndSignLegacySweep`, `jni:jni_derive.c:635`, gate at `:648` | `Broadcaster.broadcast` | G1, G2, G3. Each passes a sweep with no wallet coin. |
| 5 | Foreign asset move | `kt:recovery/ForeignAssetTransferService.kt:58`, `:537` | `jni:jni_derive.c:964`, gate at `:977` | `Broadcaster.broadcast` | as path 4 |
| 6 | Foreign DigiDollar move | `kt:recovery/DigiDollarTransferService.kt:39`, `:256` | `jni:jni_derive.c:1258`, gate at `:1270` | `Broadcaster.broadcast` | as path 4 |
| 7 | The generic signer | no Kotlin caller today | `signTransaction`, `jni:jni_transaction.c:299` | (none) | G1 |
| 8 | Stranded-send republish | `app:service/SyncService.kt:3849` | (already signed) | `publishTransaction` with the wallet's own bytes | G2 and G3 pass: the wallet holds it |
| 9 | Embargo flood | `kt:dandelion/Broadcaster.kt:99` | (already signed) | `BRPeerManagerFluffTx`, only a transaction already listed | passed when it was listed |
| 10 | A peer asks for a published send, or announces it | none | (already signed) | `core:BRPeerManager.c:5161-5162`, `:2868-2872` | G2, in the core |
| 11 | Cancel | section 6.4 | its own entry, in the peer manager | its own swap, then `publishTransaction` | Not gated. Its own checks. |

All of 1 to 6 publish through three native entry points, and each of those registers through
one function, `_registerWalletCopy` (`jni:jni_transaction.c:162-174`). That function is the
bridge's copy of `BRWalletRegisterOwnSend` (`core:BRWalletSend.h:90-110`). Both return nothing
today.

**What changes there.**

- `BRWalletRegisterOwnSend` returns a verdict: held, already held, gate closed, refused. The
  bridge's `_registerWalletCopy` becomes a call to it. One copy of the rule.
- The three publish entry points read the verdict. On "gate closed" they release the coin
  reservation, free the transaction, publish nothing and report the refusal to Kotlin. The stem
  entry reports it as a refusal, so the caller does not fall through to a flood.
- The coin reservation ends wherever a send stops being "signed and not yet in the wallet".
  `registerSend` (5.6) releases it when the wallet takes the send, as the publish entry does
  today (`jni:jni_transaction.c:559`). `registerSend` also releases it on a refusal. The
  rollback of 5.6 step 4 releases it. Nothing is left held for the table's 60 seconds.
- A send that is refused therefore leaves: no signature outside native memory, no entry in the
  wallet, no entry in the peer manager's publish list, no coin reservation.
- A host KAT reads the bridge source, as `tx_publish_ownership_kat` does today, and fails when a
  publish entry point publishes without reading the verdict, or when a signer exit lacks G1.

**Core, bridge or both.** The rule and G2 and G3 are in the core. No bridge code can skip them,
and iOS has them with the pin. G1 is called from the bridge on Android (two lines) and from the
core's own gate on iOS. Kotlin asks the gate too, only to choose which screen to show. Kotlin
cannot open it.

**What iOS gets.** G2 and G3 as they are. G1 through `BRSignedSpendGate` and the asset send
(`core:BRAssetSend.c:520`). An iOS caller that ignores the new verdict still cannot publish a
gated send: G3 refuses it.

**Reorgs.** If the send in flight drops below 3 confirmations, the gate closes again. If a
later send had already started, both are valid and both are on the network. The sweep
republishes them, parent first (section 6.1). That needs a reorg at least 3 deep.

**Cost.** When sync is stuck, the user cannot send. That is D2. Section 6.6 lists every case
where the gate could stay closed and the way out of each.

**One at a time, even with many coins.** The owner's words say "the previous transaction". This
is read as any new send, not only one that spends the last send's change. Paying twenty people
takes twenty waits. The real answer to that is one transaction with many payees, a later
feature (History, option C).

### 5.3 The waiting send

**What it is.** A stored request. It holds:

- the kind: DGB, DigiDollar or DigiAsset;
- the payee address;
- the amount, and for an asset its id;
- the note, if the Send screen took one;
- when it was asked;
- the txid of the send it was stored behind;
- the fingerprint of the wallet it belongs to (section 7);
- once approved and signed: the txid of the signed send (below).

It holds no signature, no key, no transaction and no choice of coins. It reserves nothing: the
balance and "Max" are not changed by it. It is checked lightly when stored (a valid address, an
amount above zero). It is checked fully at its turn.

**Its states.**

| State | Meaning | Stored |
|---|---|---|
| Waiting | The gate is closed. | Yes |
| Ready | The gate is open and the wallet is at the tip (the one definition, 5.2). | No. It is worked out each time from the gate, so it cannot be wrong after a restart. |
| Signed | The user approved it. The signed txid is in the request. | Yes: the txid, written before anything else is done with the signature. |

**No guessing after a crash.** The request is matched to a send by **txid**, never by payee and
amount. A second send to the same payee for the same amount is common (it is why people queue),
so payee and amount cannot tell two sends apart.

The order at approval is the order of every send (section 5.6):

1. Sign. Nothing has left native memory but the signed bytes, in Kotlin, for this call.
2. Write the signed txid into the request (`commit()`).
3. Register the send in the wallet (G2).
4. Save the transaction checkpoint. It must land.
5. Write the send's record (`commit()`).
6. Publish.
7. Delete the request (`commit()`).

What a restart finds, and what it does:

| The request has | The wallet holds that txid | It means | The wallet does |
|---|---|---|---|
| no txid | n/a | Nothing was published. Step 6 comes after step 2. | The request is a plain request again. |
| a txid | yes | The send is in the wallet. It was published, or the sweep will publish it. | Deletes the request. Writes the record if step 5 did not happen. |
| a txid | no | The checkpoint of step 4 did not land, so step 6 did not run. | See below. |

The last row should be safe: publish comes after the checkpoint. But a checkpoint file can be
lost later (a storage fault, a clearing path). So the wallet does not trust the order alone.
The request stays, marked "checking", and cannot be reviewed until the wallet has been at the
tip for 20 blocks in this process (the count of section 6.2). If the txid has appeared in the
wallet by then, the request is deleted. If not, it becomes a plain request again and its row
says once: "An earlier try at this send could not be found." While it is "checking" the row
says: "Checking whether this send went out."

The wallet does not say "did not go out", because it cannot know that. After the crash-loop
breaker the request can carry the txid of a send that was published and is still unconfirmed.
Such a send has a record (step 5 comes before step 6), so the "missing from the history"
notice of 5.7 is owed before this request can be approved again.

**It never moves on its own.** No service, worker or timer builds, signs or publishes it. The
app locked, in the background or killed: it stays as it is.

### 5.4 What the user sees

All text below ships in all 13 languages of `AppLocale.SUPPORTED` in the same commit as the
screen that shows it. The wording is a proposal. Each new screen is added to `COVERED` in
`OnboardingHardcodedStringTest`.

**(a) Tapping Send while one is in flight and nothing is waiting.** The Send screen opens and
is filled in as today. On Review, in place of the review screen:

> Your last send is still confirming (1 of 3).
> You can send again when it has 3 confirmations. That is usually about a minute, sometimes a
> few.
>
> **[Send when the last one has confirmed]**  **[Not now]**

- "Not now" returns to the Send screen with the form as it was.
- The first button stores the request and says: "Saved. Nothing has been sent. We will tell you
  when it is ready for you to approve."
- No PIN is asked to store it. Nothing is signed.
- If the wallet is behind the tip and the last send has no height, the first line reads: "Your
  wallet is catching up with the network. It cannot tell yet whether your last send has
  confirmed." No count is shown.
- If the history is not complete (D5): "Your wallet is rebuilding its history. You can send
  when it has finished." There is no offer to store a request in this state: the wallet does
  not know yet what it is waiting behind.
- If the wallet is rescanning and the last send already has 1 or 2 confirmations: "Your wallet
  is rescanning. Your last send has 2 of 3 confirmations. You can send when the rescan has
  caught up." The offer to store a request is shown as usual (section 6.6).
- If the gate opens while the user is still on the screen, Review works as today.

**(b) Tapping Send when one is already waiting (D4).** Refused before the form:

> One send is already waiting: 50 DGB to D8k…x2Q, asked today 14:02.
> Review it or remove it before you start another.
>
> **[Show the waiting send]**  **[OK]**

This holds whether the waiting send is still waiting or ready. It holds for all three kinds.

**(c) The waiting row.** At the top of the activity list and on the home screen, above the
send it waits for:

- Waiting: "Waiting to send · 50 DGB to D8k…x2Q · after your last send confirms (2 of 3)".
- Ready: "Ready to send · 50 DGB to D8k…x2Q · tap to review".
- Tapping it opens a small sheet: the request in full, when it was asked, **Review** (only
  when ready) and **Remove**.

**(d) The "ready" prompt.**

- In the app: the row changes to "Ready to send", and a banner on the home screen says "Your
  waiting send is ready. Review it to send."
- Outside the app: one notification, once: "A send is ready for your approval." No amount and
  no payee.
- The notification can only come from a running, unlocked-once process (section 1.7). After a
  reboot or a process death there is none. The user sees the banner on opening the app. The
  wording never promises a time.
- Tapping either goes through the app lock as usual, then to the review.
- The prompt is not raised while a notice of section 5.7 is owed for this request. The row then
  reads "Ready to review · read this first".

**(e) The review at its turn.** The notices of section 5.7 come first, if any apply. Then the
normal review screen for that kind of send, opened with the request's payee and amount. It is
built fresh: coins, fee and change as they are now. The user approves with the PIN or
biometric, as for any send (`app:ui/components/SpendAuth.kt`). The line "You asked for this
send on 22 Nov, 14:02" is always shown.

**(f) If it can no longer be paid.** The review says so in plain words and signs nothing:

- "You no longer have enough for this send. You are 120 DGB short." with **Change amount** and
  **Remove**.
- For an asset: the transfer-rule gate runs at review as it does today. Only
  `TransferRuleState.NONE` may move. A refusal reads as it reads today.
- For DigiDollar: the $1.00 minimum per output, change included, and the 0.1 DGB fee are
  checked when the send is built, as today.
- An address that no longer validates: "This address is not valid. Remove this send."

The request stays until the user changes it or removes it. Changing the amount goes through
the Send screen and a new review.

**(g) Removing it.** From the row's sheet: "Remove this waiting send? Nothing was sent and
nothing was signed." **[Remove]** **[Keep]**. No fee. No PIN. Nothing goes on the network. A
request that carries a signed txid cannot be removed this way; it resolves by the table in 5.3.

**(h) Expiry.** The waiting send does not expire. It cannot send itself, so age is not a
danger. It always shows when it was asked, on the row and on the review.

**(i) Wallet switch and removal.** Only the active wallet syncs (multi-wallet step 1). Before a
switch: "A send is waiting in this wallet. It stays here until you come back." The wallet
removal dialog gets one line: "This wallet has a send waiting. It will be removed. A send
already on the network still confirms."

**There is no "send anyway" (D2).**

### 5.5 DigiDollar, DigiAsset and the rest

- **DigiDollar.** The gate applies from the first PR, at G1, G2 and G3. The native call is
  split in two, for the order in 5.6: `signApprovedDigiDollar` builds, checks and signs exactly
  as today (`jni:jni_transaction.c:1121-1159`) and returns the signed bytes; the bytes then take
  the same register, checkpoint, record and publish steps as a DGB send. The generic signer
  still refuses DigiDollar; only this entry signs it.
- **DigiAsset.** Signed by `buildAndSignAssetTransferTx` as today, then the same steps. The
  transfer-rule gate is untouched and runs at the review. Implicit change to the last output is
  worked out when the send is built, at its turn.
- **Their DGB change.** A DigiDollar or asset send is "pending" in the core, so its DGB change
  is not credited until it confirms (sections 1.3, 1.4). That lasts about one block, as today.
- **Until the app PR for these two kinds lands** (PR 9), a DigiDollar or asset send while the
  gate is closed gets the plain message "Wait for your last send to confirm (1 of 3)." with no
  offer to store it. It is never sent early. If a DGB request is waiting, they are refused with
  message (b).
- **Sweep and recovery.** Gated by content (table in 5.2). They keep their own PIN prompts. A
  sweep that is refused says: "Wait for your last send to confirm. This sweep would spend coins
  of this wallet." While the wallet's history is not complete every sweep and recovery move
  waits, because the wallet cannot yet tell whose coins they are (5.2, G1). Swept coins cannot
  be spent until the sweep confirms, as today.
- **Digi-ID.** Not a transaction. Never waits.
- **Dandelion.** Unchanged. A send goes out by the user's setting.
- **Selection.** A send made under the gate never needs the wallet's own unconfirmed change.
  Pass 1 of selection (section 1.1) stays in the core for what the gate does not cover: wallets
  updated with a train already in flight.

### 5.6 The order of every send

Today the wallet's record of a send, and the saved checkpoint, are written **after** the
publish call returns. Registration and publish are one native call
(`jni:jni_transaction.c:558-568`). The checkpoint is written after it and after the record
(`kt:TransactionBuilder.kt:311-328`), and the record is written with `apply()`
(`kt:OutgoingTxStore.kt:36-51`). A process death in that window leaves a send on the network
that the wallet does not hold. Under the gate the wallet's own list is the only evidence, so
that window must close.

New order, for plain DGB, DigiDollar, DigiAsset, sweeps and recovery moves alike. It is one
shared helper (8.2), and every path goes through it:

0. **Ask `SendAdmission`.** The helper asks it itself, as its first line: start, offer to
   wait, refused because another send is waiting (D4), or history not complete (D5). The one
   send that may pass while a request is held is that request's own review. D4 is a rule about
   requests, which only Kotlin has, so the core cannot enforce it. Putting the question in the
   helper is what stops a path from skipping it: the native sign entries are called from the
   helper and nowhere else, and a source test fails when another file calls one.
1. **Sign** (native). G1.
2. **If it came from a request:** write the signed txid into the request, `commit()`.
3. **Register** (new native `registerSend(bytes)`: the verdict of G2). On "gate closed": stop,
   show the offer of 5.4 (a), nothing else happens.
4. **Save**, synchronously. "Saved" means the transaction checkpoint landed
   (`WalletTxPersister.Result.landed`, `kt:WalletTxPersister.kt:41-53`). If it does not land:
   remove the send from the wallet again, release its coin reservation, stop, and say "Nothing
   was sent." Nothing was published. The removal goes through the peer manager's removal
   (`core:BRPeerManager.c:10004-10023`), like every other. A wallet above the checkpoint limit
   is the case below.
5. **Write the record** (`OutgoingTxStore.record`, changed to `commit()` on this path).
6. **Publish.** The publish entry points find the send already held. Their own registration is
   then a no-op, as it is for a republish today (`jni:jni_transaction.c:165`).
7. **If it came from a request:** delete the request, `commit()`.

What a process death leaves, step by step:

| Dies after | On disk | On the network | After restart |
|---|---|---|---|
| 1 or 2 | No send. Perhaps a txid in the request. | Nothing | The table in 5.3. |
| 3 | No send (the checkpoint is not written yet). | Nothing | As above. |
| 4 | The send, no record. | Nothing | The wallet holds it. The gate is closed. It is shown from the wallet's transaction (section 6.2). Try again publishes it. |
| 5 | The send and its record. | Nothing | The sweep publishes it. |
| 6 | The send and its record. | The send | Normal. |

There is no row where the network has a send the saved wallet does not.

**A wallet above the checkpoint limit (question 2 of section 9.1).**

The checkpoint writer refuses a wallet with more than 100,000 transaction records. It writes
nothing and answers `REFUSED_OVER_LIMIT` (`kt:WalletTxPersister.kt:46-48`, `:68-82`; the limit
at `kt:sync/SavedTransactionsCheckpoint.kt:25`). Today such a wallet still sends. It never
reaches "synced", because the same refusal stops `has_synced`
(`app:service/SyncService.kt:3994-3999`, `:4008-4009`), and it rescans at each launch
(`kt:WalletTxPersister.kt:77-78`).

Under steps 0 to 7 as written, step 4 would fail on every send, the cancel's save would fail
on every cancel, and "history complete" would never be set. That wallet could never send. It
needs a rule of its own. Four were weighed.

- **A. A small record of the one send in flight. Recommended.** When the checkpoint answers
  `REFUSED_OVER_LIMIT`, the helper writes one small file instead: the signed bytes of this
  send, and, for a cancel, the txid it replaced. Written to a temporary file, synced, renamed.
  That counts as "saved". At the next start it is loaded after the checkpoint: the replaced
  send, if named and held, is taken out with everything built on it, then the recorded send is
  registered. The file is deleted when its send has 3 confirmations, is removed, or is beaten
  in a block. For such a wallet "history complete" is set in memory when the wallet is at the
  tip and is never saved, so after each launch it waits for its launch rescan before it can
  send. *Cost:* one more store with its row in 7.2; one more load path; the signed bytes of
  one unconfirmed send in a second file (the checkpoint already holds such bytes for every
  other wallet, section 1.7); and a path that only rare wallets take, so its tests must force
  the refusal.
- **B. The same small record for every wallet, in place of the checkpoint at step 4.** One
  path, taken by every send, and no whole-checkpoint write before each send. *Cost:* every
  wallet gets a new load path and two sources to merge at start, in the same release that
  adds the gate. More to get wrong, for everyone, for the sake of a rare wallet.
- **C. Let such a wallet publish without a save.** Today's order, for it alone. *Cost:* the
  gap this section closes stays open for it. A process death after the publish leaves a send
  on the network that the restarted wallet does not hold. The record still names it (step 5),
  so the "missing from the history" notice is owed. Cancel would have to be refused for such a
  wallet: after a restart its start table could not tell a cancel that went out from one that
  did not.
- **D. Refuse to send above the limit.** *Cost:* a wallet that can receive and never spend.
  Today it can send. Not acceptable.

The rest of this design is written to A. If the owner chooses B, step 4 changes for every
wallet and nothing else does. If C, the over-limit row of 6.6 changes and Cancel is refused
for that wallet.

### 5.7 Notices before a review

The gate stops two sends being in flight. It cannot stop the user approving a second payment to
someone who was paid after all. So the wallet says so first. These notices open **before** the
review of any send, the waiting one included. Each must be acknowledged before the PIN prompt
appears. Each is worked out **when the review opens**, from what the wallet holds at that
moment. None depends on a background task having run.

| Notice | Worked out from | Wording |
|---|---|---|
| A cancel lost | A "cancel requested" mark (section 7) for a txid the wallet now holds at a height | "A send you cancelled was paid after all: 50 DGB to D8k…x2Q, confirmed 22 Nov, 14:05. It was not cancelled." |
| A removed send may still confirm | A "removed" mark for a txid the wallet does not hold | "You removed a send of 50 DGB to D8k…x2Q. Removing it did not stop it. It could still confirm." |
| A send is missing from the history | A record in `dgb_outgoing_tx` that is unsettled or marked "missing" (6.7), or a note the rebuild wrote, for a txid the wallet does not hold, with the history complete and the wallet at the tip (5.2) | "A send of 50 DGB to D8k…x2Q is not in your wallet's history. It may still confirm. Check with the person before you pay again." |
| This wallet was restored | A "restored" mark (7.2), written by a restore that could not keep this wallet's stores, not yet acknowledged | "This wallet was restored from its recovery phrase. It cannot show a payment you sent before that if the payment has not confirmed yet. If you sent one recently, check with the person before you pay again." |
| The same address was just paid | This review pays an address that a send of this wallet also paid, where that send is the one the request was stored behind, or was registered after the request was stored | "Your earlier send of 50 DGB to this address confirmed on 22 Nov, 14:05. Approving this pays the same address a second time." **[Pay a second time]** **[Remove this send]** |
| The history was rebuilt while this waited | The request is older than the last time the history was cleared | "Your wallet's history was rebuilt while this send waited. Check your activity for a payment to this address before you approve." |

- The fourth notice reads the address from the wallet's transaction, not from the record. So it
  works for a send made on another device.
- The fourth notice is for a waiting send only. A direct send has no request to compare with.
- What is stored is only "acknowledged", per notice, with `commit()`. The fact itself is
  derived each time. An acknowledged "cancel lost" or "removed" mark is deleted once its txid
  has 3 confirmations, or on the wallet's next clearing path (section 7).
- The first three are also shown on the send's own row in the activity list.
- No review opens while the history is not complete (D5). So no notice is ever worked out from
  a half-built history. Section 6.7 says what keeps the evidence until then.

## 6. A send that does not confirm, and "Cancel this send"

A gate that opens only on 3 confirmations turns a first send that never reached a node into a
wallet that cannot send. So this half is required. It ships in the same release as the gate.

### 6.1 Republish parents first

- Today the sweep works in txid order, and each send has its own schedule: three sweeps in a
  row, then once an hour (`app:service/StrandedSendSelector.kt:108`, `:111-121`). A parent on
  its hourly hold is not in the list when its child is retried. Ordering the list alone does
  not fix that.
- New rule: **republishing a send republishes its unconfirmed wallet ancestors first, in the
  same sweep, whatever their own schedule says.** The 15-second wait after a publish
  (`app:service/SyncService.kt:3851`) is taken once for the group, not once per transaction.
- The rule goes in `core:BRStrandedSend.h` so iOS follows it.
- Under the gate a new send has no unconfirmed parent. This rule is for what is left: trains
  made by older versions, a second device, and a reorg.
- The sweep works from the app's record of its own sends (`kt:OutgoingTxStore.kt`). A send made
  on another device has no record, so the sweep never retries it. "Try again" (6.2) republishes
  from the wallet's own copy of the transaction, record or not.
- The sweep never republishes a send that carries a "cancel requested" mark (6.4).
- The walk fix ships with it: `core:BRWallet.c:1783`, 25 becomes 24, so a send can have at most
  24 unconfirmed ancestors, which is the node's limit (section 1.1).

### 6.2 The three states of a send that has no confirmation

All three are defined from the wallet's transaction, not from the app's record. So a send made
on another device gets the same states and the same actions.

**"Not confirmed yet".** All of these hold: the wallet is at the tip (the one definition,
5.2); the send is valid and has no height; and at least **20 blocks** (about 5 minutes) have
been verified at the tip since this wallet first saw the send, or since it last reached the
tip, whichever is later.

- Counted in blocks the wallet verified, in this process. A restart starts the count again.
  That is the safe side.
- Text: "This send has not confirmed yet. It may not have reached the network." Not "Not sent"
  and not "Failed".
- Actions: **Try again** (republish now, parents first) and **Cancel this send** (6.4).

**"Cannot check yet".** The first state needs blocks to arrive at the tip. A wallet whose tip
has stopped never gets there: a tip on a dead branch (wedge b), or a sync loop with no peers
(wedge c). A wallet in a long rescan does not either. So there is a second clock.

- It counts **time with the app open**. It runs while a valid send of this wallet has no
  height. It is set back to zero each time the wallet verifies a new block **at the tip**.
  Blocks verified below the tip, during a rescan, do not reset it. Time in the background or
  with the app closed is not counted.
- At **20 minutes** the send is in this state. A healthy wallet at the tip never reaches it:
  the longest gap between blocks measured in section 3 was 608 seconds.
- Why app-open time. A process frozen overnight would otherwise read "stalled" the moment it
  is opened, seconds before the sync loop revives and finds the send confirmed.
- Text: "Your wallet has not been able to check this send for 20 minutes. It may have
  confirmed. Your wallet cannot tell yet."
- Actions: **Try again**; **Rescan** (the existing action); and **Cancel this send**, with
  stronger words in its dialog (6.4).

**"Reported confirmed, not verified".** The wallet already asks its endpoint about each
unconfirmed transaction, at most once in 5 minutes (`app:service/SyncService.kt:4211-4236`).
For each one it calls `txConfirmation(txid)` and hands the height it gets to the core
(`kt:reconcile/ChainReconciliationService.kt:344-351`, the two calls at `:347-348`).

The core never takes a server's height (section 1.7). It gives one of three answers and
changes nothing:

- "refused", when the height is above the wallet's own tip, or is not on a chain the wallet
  vouches for (`core:BRPeerManager.c:11076`; the rule at `:3898-3915`);
- "re-checking" (`:11101-11104`);
- "unprovable" (`:11114`).

**The state is keyed on the endpoint's answer, not on the core's.** The fourth revision keyed
it on "re-checking" and "unprovable". But a wallet whose tip has stopped gets "refused" for
exactly the send that confirmed after the tip stopped. That is the wallet that most needs
Cancel hidden.

- The rule: when `txConfirmation` returns a height for a transaction this wallet holds without
  a height, that answer is **kept**, whatever the core then says: the txid, the height, and
  when it was given.
- It is kept in the marks store (7.1), with `commit()`. So it is still there after a restart,
  when both clocks have started again and the next check may be minutes away.
- Not kept: an answer for a transaction the wallet does not hold; and an answer naming a
  height more than 20 blocks above the height the wallet's peers report, when there are peers.
- A send with a kept answer is in this state, whatever the two clocks say.
- Text: "The network reports this send as confirmed. Your wallet has not verified that yet."
- Actions: **Rescan** only. **No Cancel and no Try again.** Cancelling a send that is in a
  block cannot work and would take the real payment out of the wallet's list (6.4). The cancel
  path looks for a kept answer again after the PIN, and stops if there is one.
- **It never opens the gate.** G1, G2 and G3 read heights the wallet proved and nothing else.
  This answer is not a height, and it lives in Kotlin.
- It is not a new query. It uses an answer the existing check already fetched.
- The kept answer is dropped when: the wallet proves a height for the send; or the wallet no
  longer holds the send; or the wallet is at the tip (5.2), its scan has passed the reported
  height, and the send still has no height. An answer dropped for that last reason is not kept
  again for the same height.
- A wrong answer can therefore hide Cancel until the wallet's own scan has covered that
  height. It cannot make the wallet send or sign anything, and Rescan stays. Because the answer
  is kept across restarts, an endpoint that is wrong hides Cancel for longer than in the
  fourth revision. That is the price of not offering Cancel on a confirmed payment.

**Notifications.** One, once per send: "A send has not confirmed yet." No amount and no payee.

**If a send is waiting behind it,** the waiting row says: "Waiting for your last send. It has
not confirmed yet."

### 6.3 No new automatic question to a server, and no automatic removal

- This design adds **no** new automatic request to any server.
- One exists today and is left as it is: the confirmation check sends the txid of each
  unconfirmed wallet transaction to the endpoint (section 2.2). It sends no inputs and it
  removes nothing. **A cancel is an unconfirmed wallet transaction, so its txid goes to the
  endpoint the same way.**
- The stale-send removal stays manual, on the reconcile screen, default endpoint only. It is
  not run from the sweep and it is not added to the stuck row. One server's answer must not
  decide when coins come back with nobody watching.
- Cancel is the exit that needs no server.
- The cost: a user who never opens the app is never unstuck automatically. With D2 and the
  amended D1 that is the intended behaviour.

### 6.4 Cancel this send

**What it is.** A payment from the wallet to itself that spends coins the stuck send spends.
Only one of the two can ever confirm. If the cancel confirms, the stuck send is dead by the
chain's own rule.

**When it is offered.** On a send in "Not confirmed yet" or "Cannot check yet", and only on the
**oldest** unconfirmed send of a chain. Cancelling it cancels everything built on it. It is
not offered on a send that is seconds old, not on a send the endpoint reports confirmed, and
not on a cancel (a cancel gets Try again and Rescan).

**What it cannot do.** If a node already accepted the original, nodes refuse the cancel:
replacement is off on DigiByte (section 3). Then the original confirms and the cancel dies. A
cancel that dies pays no fee, because it never confirms. The dialog says so before the PIN:

> Cancelling works only if the network has not accepted this send. If it already has, the send
> will confirm and the cancel does nothing.
> If the cancel works it costs a network fee of 0.00x DGB.
> Do not pay this person another way until the wallet says which of the two confirmed.

- When later sends were built on this one, the dialog lists them: "Cancelling this also cancels
  2 later sends: 10 DGB to D7a…k1P, 5 DGB to DQx…m3Z. Those people will not be paid."
- From "Cannot check yet" the dialog adds: "Your wallet has not been able to check this send. It
  may already have confirmed. If it has, the cancel does nothing, and your wallet will show the
  payment again after a rescan."

**Why the present signer cannot sign it.** The original is still in the wallet when the cancel
is signed, so every input of the cancel is a coin the wallet holds as spent. The signer refuses
exactly that, before it signs: `seed_sign_transaction` asks `spend_guard_inputs_unspent`
(`jni:jni_wallet.c:490-497`, `jni:spend_guard.h`), and the core copy is the same
(`core:BRSpendGate.h:51-62`). The prepared-send check refuses it too: it needs every coin to be
selectable, and `BRWalletOutpointSelectable` looks only in the unspent list
(`core:BRWallet.c:1832-1855`). That guard is the 4.1.2 double-sign fix. It stays as it is for
every other signer.

**Two ways were weighed.**

- *Swap, then sign, with a rollback.* Take the original out of the wallet, sign the cancel with
  the ordinary signer, register it, and put the original back if anything fails. Rejected.
  Removing a transaction frees it and everything built on it and tells the app so
  (`core:BRWallet.c:2768-2803`), so the rollback would have to rebuild a whole chain and take
  back messages the app already acted on. And between the removal and the signature the wallet
  holds neither transaction.
- *A cancel-only entry that builds, checks, signs and swaps in one call.* **Chosen.** It takes
  no transaction from its caller, so it can be narrow. Nothing in the wallet changes until
  every check has passed and the cancel is signed.

**The entry: `BRPeerManagerCancelSend`.** In the core, so iOS gets it. It is a **peer manager**
entry. The fourth revision made it a wallet function, `BRWalletCancelSend`; that was a
memory-safety fault, set out below. On Android it is reached through one function in
`jni:jni_wallet.c`, the only file that can read the seed. It is refused when no peer manager
is paired with the loaded wallet, which is the window the bridge already marks with
`g_walletSwapped` (`jni:jni_transaction.c:723`): "Try again in a moment."

It takes: the txid of the send to cancel, the fee the user approved, the amount the user was
shown coming back, the seed, and the coin reservation to use (two functions and a clock
reading, below). It returns the signed cancel, or a refusal that says why.

It does **not** take a transaction, inputs, outputs or an address. It builds the cancel itself.
That is the whole of its narrowness: it can only ever sign "the plain inputs of X, paid to this
wallet".

Steps, inside the one call:

1. **Check, under the wallet lock.** All of these, or it stops with nothing changed:
   - X is in the wallet, is valid, has no height, and spends at least one wallet coin.
   - X is the oldest of its chain: none of its inputs comes from a transaction without a height.
   - The plan: each input of X that **qualifies** (below). At least one must.
   - The history is complete (5.2) and the platform has said asset detection ran to completion
     in this process.
2. **Build**, still under the lock: those inputs, and **one** output to a fresh address on this
   wallet's change chain. The fee is the default rate for its size. What comes back must be
   above the dust line, the fee must equal the approved fee and the output must equal the
   approved amount. Otherwise it stops: the user approved something else.
3. **Sign**, outside both locks, with keys derived from the seed. The spend guard is not
   asked: by step 1 every input is spent by X, and by X only.
4. **Claim** the signed cancel's inputs in the coin reservation, for the signed cancel's txid.
   Refused if another signed send holds one of them.
5. **Check again and swap, in one hold of the peer manager's lock, and inside it one hold of
   the wallet lock.** That is the order the manager already takes them in
   (`core:BRWallet.c:3114-3115`, `core:BRPeerManager.c:10008-10014`).
   1. Take the manager lock. Record which entries of the publish list the wallet holds a
      record for (`_BRPeerManagerSnapshotHeld`, `core:BRPeerManager.c:1097-1102`).
   2. Take the wallet lock. Everything of step 1 again, on the signed transaction: X still
      held, valid, without a height and still the oldest; every input of the cancel is an input
      of X and still qualifies; the one output pays an address of this wallet; the transaction
      is fully signed. Everything the swap needs is allocated first. Then, with nothing left
      that can fail: X and everything built on it are taken out of the wallet, their txids are
      noted, and the cancel is put in. Release the wallet lock.
   3. Purge the publish list: every entry the wallet held before and does not hold now is
      dropped (`_BRPeerManagerPurgeReleasedRecords`, `:1112-1128`). Drop the relay, reject and
      request records of each removed txid, as the existing removal does for one (`:10016-10018`).
   4. Release the manager lock. Deliver the answers the purge queued (`:1063-1075`).
6. Release the reservation. Tell the app, after both locks are released, in this order: the
   removed transactions, the cancel added, the balance.

The signature exists outside this call only if step 5 completed. On any earlier stop the
signed transaction is freed inside the call.

**Why the swap belongs to the peer manager (memory safety).** The publish list holds plain
pointers to transactions the wallet owns. A swap under the wallet lock alone would free X and
leave the list pointing at it. The next peer to ask for X would make the manager read freed
memory (`core:BRPeerManager.c:5131-5135`). That is reachable in the very case Cancel exists
for: X is live on the network and a peer relayed it back. Every removal the wallet makes today
goes through the manager for this reason (`core:BRPeerManager.c:10004-10023`, used by the
bridge at `jni:jni_transaction.c:721-727`; the manager's own clean-up does the same at
`:1371-1375`). The swap follows the same road: snapshot, change the wallet, purge.

Everything that can hold a pointer to a transaction the wallet owns, and what the swap does
about it:

| What holds it | Where | After the swap |
|---|---|---|
| A publish-list entry for the parent of a published child. The list does not own it. | added at `core:BRPeerManager.c:997-1001` | X is such a parent whenever a send built on X was published. Purged by its cached txid, never by reading the object (`:1104-1111`). The wallet freed the object. The list does not free it again (`:1047`, `:1057`). |
| A publish-list entry for a send a peer relayed back. The list does not own it. | `core:BRPeerManager.c:2784-2790` | The same. This is the usual state of a send that reached the network. |
| A publish-list entry whose object the wallet took when a peer announced it or asked for it. | ownership follows the object: `:1137-1144`, from `:2872-2876` and `:5162-5165` | The same. |
| The publish list's own copy of X, which the list does own. | the bridge publishes the original and gives the wallet a copy, `jni:jni_transaction.c:553-568` | Dropped and freed by the list in the same purge (`core:BRPeerManager.c:1034-1058`). A publish still waiting for its answer gets "cancelled", once. Left in the list, X would be announced to every peer beside the cancel (`:1466-1482`). |
| The pointer a peer handler holds while it runs: a transaction a peer announced (`_peerHasTx`, `:2848-2873`), one a peer relayed (`_peerRelayedTx`, `:2770-2790`), and the two walks over unconfirmed transactions (`:1289-1297`, `:1424-1445`). | each holds the manager lock from the lookup to the last use | The swap holds the manager lock, so it cannot run in the middle of one. This is why the wallet lock alone is not enough. |
| What a peer is served. | `_peerRequestedTx` hands out a copy made under the lock (`:5176-5182`) | Not affected. A request for X after the purge finds no entry and serves nothing. |
| A later announcement of X by a peer. | `_peerHasTx` looks X up in the wallet, then in the list (`:2848-2853`) | Finds neither. Nothing is registered and nothing is read. If a peer goes on to relay X itself, that is the case "A peer relays X without a height", below. |
| The relay, reject and request records. | they hold txids, not pointers (`:10016-10018`) | Dropped for X and for each transaction removed with it. |
| The reject handler and the server-height check. | both read copies (`:2917`, `:11067`) | Not affected. |

The wallet's half of the swap is a function for the peer manager only. It is declared in a
header the platforms do not include, and a source check in the KAT fails when it has a second
caller. The existing removal unlocks and calls itself for each dependant
(`core:BRWallet.c:2794-2802`); the swap needs a form that stays inside one hold. That is
unwritten code (8.6).

**The coin reservation.** There are two tables. Android's signers use the bridge's
(`jni:coin_reservation.c:18-20`; claimed at `jni:jni_wallet.c:501` and in
`jni:signed_spend_gate.h`). The core has its own (`core:BRCoinReservation.h`), and the bridge
does not call it. A claim made in the core's table would be invisible to every Android signer.

- So the entry does not choose a table. Its caller passes the reservation in: a claim
  function, a release function and one clock reading.
- Android passes the bridge's table and `coin_reservation_now()`, which is monotonic seconds
  (`jni:coin_reservation.h:29-41`, `jni:coin_reservation.c:94-97`). iOS passes the core's table
  and the one clock its other signers use (`core:BRCoinReservation.h:24-38`).
- **The key is the signed cancel's txid, and the claim is made after signing**, as the ordinary
  signer does (`jni:jni_wallet.c:498-501`). A txid taken before signing is not the final one
  when an input is a legacy input: its signature is part of the txid.
- What the claim is for: one rule for every signer. A signed transaction that the wallet does
  not hold yet holds its coins in the table.
- What it is **not** for: keeping two cancels of one send apart. Two cancels built from the
  same inputs to the same address can have the same txid, and the table renews a claim for
  the same owner (`jni:coin_reservation.c:56`). Step 5 keeps them apart: the second one finds X
  gone and stops with nothing changed.
- It is released on every way out of the call after step 4. The 60-second lapse is only a
  backstop.

**Which inputs qualify.** An input of X qualifies only if all of these hold:

- the wallet owns the output it spends, and can sign for it;
- the transaction that made that output has a height;
- the output is not in the wallet's asset list. That list is not pruned of spends, so it still
  answers for an outpoint the wallet holds as spent (`core:BRWallet.c:1149-1160`, `:3587-3595`);
- the output is not a DigiDollar output and not held by the one-family rule;
- **and, when X is an asset or DigiDollar send: the transaction that made the output carries no
  asset data and no DigiDollar marker at all.**

The last rule is there for implicit change. An output can hold asset units only as the
remainder that DigiAssets credits to the last output. The core knows such an output only from
a list the app fills in again after each start (`core:BRWallet.c:3459-3473`), and that replay
**skips every row the asset layer has marked spent** (`kt:asset/AssetManager.kt:2791`). After a
restart, the asset input of a stuck asset send is exactly such a row. It would read as a plain
coin, and a cancel that spent it with no asset instruction would destroy the units. The rule
does not depend on the replay: it reads the funding transaction's own bytes. An output of a
transaction with no asset data cannot hold units. It costs something: the DGB change of an
earlier asset send cannot fund a cancel of a later one. That send then "cannot be cancelled"
(6.6).

For a plain DGB send the first four rules are enough. Its inputs were checked as plain coins
when it was signed, by the prepared-send check, against the list as filled in that process.

**An input that cannot be classified does not qualify.** A wrong "plain" would destroy an
asset. A wrong "not plain" leaves a send that cannot be cancelled.

**What can go wrong at each step, and what the wallet holds afterwards.**

| Step | What can go wrong | The wallet holds |
|---|---|---|
| 1 Check | X confirmed a moment ago; X is not the oldest; no input qualifies; history not complete | X, unchanged. Nothing signed. The screen says which. |
| 2 Build | Dust; the fee or the amount is no longer what was approved | X, unchanged. Nothing signed. The review opens again. |
| 3 Sign | The session locked meanwhile; a key cannot be derived | X, unchanged. Nothing is claimed. |
| 4 Claim | Another signed send holds an input | X, unchanged. The signed cancel is freed inside the call. |
| 5 Swap | The block that confirms X arrived between the PIN and here; a peer delivered a child of X; X is no longer held | X, or whatever replaced it, unchanged. The signed cancel is freed inside the call. The claim is released. The publish list is untouched. |
| 5 Swap, done | (nothing can fail after the first change) | The cancel, valid, without a height. Not X, and nothing built on X. No publish-list entry names X or anything built on it. |
| After the call | The process dies before the save lands | On disk: X, as before. The cancel was never published. See the write order below. |
| After the call | The save does not land and the process lives on | In memory: the cancel. On disk: X. The wallet rolls **forward**, below. |

**The order of what is written.** The swap changes memory. Disk and network follow in a fixed
order, each step finished before the next:

1. Write the mark "cancel requested" for X: its txid, payee, amount, kind and time (`commit()`).
2. Call `BRPeerManagerCancelSend`. On a refusal: delete the mark, show why.
3. Add the cancel's txid to the mark, with "not saved yet", and write the cancel's own record
   (`commit()`). This is done whether or not the next step lands.
4. Save (the meaning of "saved" in 5.6 step 4). What is saved now holds the cancel and not X.
   When it lands, set the mark to "saved" (`commit()`).
5. Publish the cancel, by the user's Dandelion setting. **Only a cancel whose mark says
   "saved" is ever published.**

**When the save does not land: roll forward.** After step 2 there is no way back. X was freed
in the swap, and the wallet in memory holds the cancel. The fourth revision said "publish
nothing" and stopped there. That was not a state the wallet could stay in: the next
checkpoint write by anything else would have saved a cancel that had no txid in its mark and
no record, so nothing would ever have published it. So a save that does not land is retried,
never undone:

- The mark says "not saved yet". While it does, nothing publishes the cancel. The sweep and
  Try again both read the mark first.
- The gate stays closed on the cancel.
- The row reads: "Cancelling. Your wallet could not save this yet and keeps trying. Nothing has
  been sent."
- The sweep's timer saves again, with or without peers. When a save lands it sets "saved" and
  publishes the cancel.
- A checkpoint write by another part of the app that lands in between also holds the cancel,
  because every write saves what is in memory. The mark is still set only by a save made after
  step 3, so the order "txid and record, then saved, then published" holds in every case.
- If the process dies first, the disk still has X and no cancel went out. The start table
  below puts X back as an ordinary stuck send.

At every start, each "cancel requested" mark is read against the wallet:

| The wallet holds | The mark says | It means | The wallet does |
|---|---|---|---|
| X valid and without a height, and no cancel | no cancel txid | The call of step 2 never finished. | Deletes the mark. X is an ordinary stuck send again and the sweep looks after it. |
| X valid and without a height, and no cancel | a cancel txid, "not saved yet" | The swap never reached the disk. No cancel was published (step 5 needs "saved"). | Deletes the mark and the cancel's record. X is an ordinary stuck send again. |
| X valid and without a height, and no cancel | a cancel txid, "saved" | A save landed and was lost afterwards. The cancel may be on the network. | Keeps the mark. X's row reads "A cancel was sent for this send. One of the two will confirm." Cancel is offered again only after 20 blocks at the tip. |
| the cancel, not X | "not saved yet" | A save by something else landed before the process died. | Rolls forward: saves, sets "saved", publishes. |
| the cancel, not X | "saved" | The normal case. | Nothing. The sweep republishes the cancel like any send. |
| X at a height | any | The cancel lost. | The "cancel lost" notice of 5.7, worked out when a review opens. |
| neither | any | The history was cleared since. | The "missing from the history" notice of 5.7. |

**What decides the outcome.** The chain. A transaction with a height always sorts before one
without (`core:BRWallet.c:161-162`), and of two that conflict the earlier is valid
(`:417-431`).

- **The cancel confirms.** X can never confirm. Its coins are back, less one fee. The gate
  opens when the cancel has 3 confirmations. The mark is deleted then.
- **X confirms after all.** It arrives in a block, sorts before the cancel, and the cancel
  becomes invalid. The existing ten-minute invalid hold removes the cancel
  (`app:service/SyncService.kt:3790-3824`). X shows as confirmed. The gate waits for **X** to
  have 3 confirmations. The "cancel lost" notice is owed (5.7).
- **A peer relays X without a height.** Usually X is ordered after the cancel and is invalid in
  this wallet. Not always: between two transactions without a height the order is decided
  first by the position of their outputs on the wallet's **legacy** address chains, and only
  then by arrival (`core:BRWallet.c:124-133`, `:205-214`). The cancel pays a segwit change
  address, which that comparison does not see. If X pays a legacy address of this wallet, X
  can sort first, and then the cancel is the invalid one and the hold removes it. That outcome
  is safe: the gate follows X, the mark stays until X has a height or the user cancels again,
  and Cancel is offered again later. The KAT has both orders.
- **Neither confirms.** The cancel is itself a send that has not confirmed. It gets the states
  of 6.2, with Try again and Rescan.

**A cancel is never removed by the "refused by the network" rule.** That rule removes a plain
DGB send when peers in two network groups reject it (`app:service/SyncService.kt:3769-3787`).
Removing a cancel would leave the wallet holding neither transaction, with the gate open and X
perhaps alive. The sweep knows a cancel by its mark and keeps it. If a cancel is ever removed
by hand (the manual check on the reconcile screen), a "removed" mark is written **for X**, with
X's payee and amount, so the notice of 5.7 names the payment that matters.

**A cancel of a send that had in fact confirmed.** The wallet cannot always know. If its tip is
stalled it has not seen the block, and step 1 passes. The cancel then takes the real payment out
of the wallet's list, and no node will ever accept the cancel. This is why the endpoint's
answer hides Cancel when there is one (6.2), and why the dialog from "Cannot check yet" says
what it says. When it still happens, the way back is in the wallet's own hands: the cancel's
row offers **Rescan**, with the line "If the send you cancelled had already confirmed, a rescan
will find it." The scan finds X in its block. X sorts before the cancel, the cancel is removed
as invalid, the gate follows X, and the "cancel lost" notice is shown. No money moved.

**Kotlin side.**

- The mark is the thing the sweep reads: a send with a "cancel requested" mark is never
  republished. X's record in `dgb_outgoing_tx` is kept, so its row comes back with its payee if
  X confirms.
- For a send from another device there is no record. The mark carries the payee and amount
  itself, read from the wallet's transaction before the swap.
- An asset send's rows are cleaned as for a dead send (`clearDeadAssetSend`,
  `app:service/SyncService.kt:3808-3817`). A re-send reads the old attempt as `CONFLICTED`.
- The records of the later sends removed with X are kept and marked "removed with a cancelled
  send". Their rows say: "Not sent. Cancelled with an earlier send." Their payees are unpaid.

### 6.5 Removal is not cancel

- The manual check on the reconcile screen can **remove** a send as stale
  (`core:BRStaleSend.h`). Removal gives the coins back in this wallet. It does not spend them.
  The removed send stays valid on the network for as long as its inputs are unspent. One node
  saying "not found" is not every mempool.
- With the gate, a removal opens the gate at once. The user could pay again, and the original
  could still confirm.
- So the stuck row offers Cancel, never removal. And on the reconcile screen, when the check
  finds a send unknown **and a cancel can be built for it**, the action offered is "Cancel this
  send", not removal.
- Removal stays for a send that cannot be cancelled (no qualifying input, or dust). Its dialog
  gains: "Removing this send does not stop it. It could still confirm later." Every such
  removal writes a "removed" mark (section 7) **before** the transaction is removed, with
  `commit()`. The notice of 5.7 follows from the mark.
- The two automatic removals that exist today stay, with two changes. The "refused" removal
  skips a cancel (6.4). And a removal writes the same "removed" mark first, unless the send
  was beaten by a transaction that has a height. A send beaten in a block can never confirm
  and needs no notice. A send that two groups of peers said they refused, or one beaten by a
  transaction that has no height itself, could still be alive somewhere, so its payee is named
  before the next review.

### 6.6 Every case where the gate could stay closed

D2 allows no override. So each case needs a stated way out. None of them adds a server query,
and none of them opens the gate without proof.

| Case | What the user sees | The way out |
|---|---|---|
| The last send never reached a node | "Not confirmed yet" after 20 blocks at the tip | Try again. Cancel. |
| The last send was refused or beaten | Removed by today's rules after 10 minutes | None needed. A notice names it at the next review (6.5). |
| A send from another device never confirms | The same states, from the wallet's transaction | Try again and Cancel work here too: this wallet holds the transaction and the keys. |
| **A send that confirmed, but this wallet still holds it without a height** | "Reported confirmed, not verified" when the existing check has the endpoint's answer. Otherwise "Not confirmed yet" or "Cannot check yet". | Rescan. The wallet proves the height itself and the gate opens on that proof. Cancel is hidden when the endpoint's answer is known. If the user cancelled without it, the rescan also undoes that (6.4). |
| **A rescan is running** | Sends that had 3 confirmations still have them: the count is taken against the verified mark (5.2). The gate is open for them. | None needed. A send that has no height yet waits until the scan reaches its block. |
| **A rescan starts while the last send has 1 or 2 confirmations** | "Your wallet is rescanning. Your last send has 2 of 3 confirmations. You can send when the rescan has caught up." The count does not move during the rescan: the mark rises only when the wallet verifies a block above the tip it had before. The send has a height, so it is in none of the three states of 6.2. No Try again and no Cancel: it is in a block. | Wait. It is shorter than the whole rescan: the mark follows the verified header tip (`core:BRPeerManager.c:4428`), and the headers are back at the tip well before the scan is (`app:service/SyncService.kt:3058-3060`). One or two blocks after that the gate opens. A request can be stored meanwhile. A rescan the user starts says so first: "Your last send has 2 of 3 confirmations. If you rescan now, you cannot send until the rescan has caught up." **[Wait]** **[Rescan now]**. If the headers cannot get back to the tip, the cures are those of the stalled-tip row. None of this is an override: the gate still opens only on 3 proven confirmations. |
| **A wallet above the checkpoint limit** (more than 100,000 records) | It sends like any other, by the rule the owner chooses (5.6; A is assumed). After each launch: "Your wallet is rebuilding its history" until its launch rescan is at the tip. | Wait for the launch rescan. Question 2 of section 9.1. |
| **The tip has stalled** (dead branch, or no peers) and the wallet believes it is at the tip | "Cannot check yet" after 20 minutes with the app open and no new block | Try again. Rescan, which cures a dead-branch tip. Force-stop, which cures a dead sync loop. Cancel, with the stronger wording. |
| The wallet is behind the tip for a long time | "Catching up with the network", then "Cannot check yet" after 20 minutes with the app open | The same. |
| A cancel that does not confirm | The states of 6.2 on the cancel | Try again. Rescan. If the original confirms instead, the gate follows the original. |
| A send that cannot be cancelled (no qualifying input, or dust) | "This send cannot be cancelled" | Try again. The manual check and removal on the reconcile screen (default endpoint), with the notice. Rebuild from chain as the last resort. |
| A user on a custom endpoint | As above | Everything above except the manual check. Cancel and Rescan need no server. |
| History cleared (restore, rebuild, breaker) | "Your wallet is rebuilding its history" (D5) | It ends when the scan reaches the tip. If it cannot, it is the stalled or behind-the-tip row. |
| **Updated from an earlier version with a send already unconfirmed, or a train of them** | The gate is closed from the first start. The oldest send of the train is the one named. | The states of 6.2 apply to it. Try again republishes the whole train, parents first. Cancel on the oldest cancels the train. |
| Confirmed history after an update or restart | Nothing. The gate reads saved heights against the saved mark. | None needed. |
| A record whose height an old version (before 4.1.3) took from a server | Nothing. It reads as confirmed. | None needed. See section 8.6 for what that costs. |

**The documented last resort** is the rebuild from chain (`rebuildFromChainRescan`,
`kt:WalletManager.kt:859`). It drops every unconfirmed send from this wallet and rebuilds from
blocks. It does not cancel anything on the network, and its screen says so. Today it also
erases the records of those sends (`kt:WalletManager.kt:899`; the step `CLEAR_OUTGOING_STORE`,
`core:BRChainRebuild.h:52-55`). Under the gate that would be a way to pay twice: the only
evidence of a live send would be gone, and a waiting send to the same payee would read "Ready".
So the rebuild changes, in the shared step list:

- A new step, **before** the clears, and one the clears depend on: for every valid send of this
  wallet without a height, and for every unsettled record, write a note with its txid, payee,
  amount and kind into the marks store (section 7). If that write does not land, the rebuild
  stops before anything is cleared, as it does today for its first three steps.
- The marks store and the waiting send are **kept** by the rebuild.
- After the rebuild the gate is closed until the tip is reached (D5). Then each note is read
  against the wallet. A txid now held at a height is a send that confirmed: the note is dropped,
  or becomes "cancel lost" if a cancel was asked. A txid not held is a send that may still
  confirm: the "missing from the history" notice is owed before the next review (5.7).
- A waiting send that is older than the rebuild opens with the "history was rebuilt" notice.

The crash-loop breaker clears the saved transactions and keeps the records
(`app:StaleDataWiper.kt:47-95`). The one-time history rebuild after an update keeps the
records too (`kt:sync/HistoryRebuildOnUpgrade.kt:28-30`). Neither needs a new step, **but both
need the sweep to leave those records alone until the wallet is at the tip.** Today it does
not. Section 6.7 is that rule.

### 6.7 The sweep and a recorded send the wallet does not hold

**What happens today.** The sweep gives up on such a record.

- It counts a try before it looks for the bytes (`app:service/SyncService.kt:3835-3837`). After
  three tries it marks the record settled (`SETTLE_GAVE_UP`,
  `app:service/StrandedSendSelector.kt:120`; `markSettled` at `app:service/SyncService.kt:3738`).
- In a wallet whose list is full it settles at once. It takes "not among the newest 100 rows"
  to mean "confirmed long ago" (`SETTLE_AGED_OUT`, `StrandedSendSelector.kt:119`).
- It runs every 90 seconds with peers. So about five minutes into a scan that takes hours, the
  record is settled.

After the breaker, the one-time rebuild or a same-wallet restore (7.2), that record is the
only evidence of a send that may still be live. The notice of 5.7 would find nothing to read.

**The new rules.** They go in the selector, so there is one copy and it has a unit test.

1. **Whether the wallet holds a txid is asked of the wallet, by txid.** It is not read off the
   100-row list. The lookup the sweep already makes for the bytes answers it
   (`app:service/SyncService.kt:3838`). "Aged out" then means: held, at a height.
2. **While the history is not complete, the sweep does nothing with a record the wallet does
   not hold.** No try is counted. It is not settled. It is not republished: there are no bytes
   to send. The record stays exactly as it was.
3. **Once the history is complete and the wallet is at the tip (5.2):**
   - held at a height: settled as confirmed, as today;
   - held without a height: republished, as today;
   - not held: marked **missing**. Not settled.
4. A missing record leaves the sweep's working set, so it is not tried again. It stays
   readable. The notice of 5.7 reads it, and its row in the activity list shows it. The mark is
   written with `commit()`; today's settled mark is written with `apply()`
   (`kt:OutgoingTxStore.kt:79-80`).
5. A missing record becomes settled only when its txid later appears in the wallet at a
   height.

**What the user sees in the window.** No review can open while the history is not complete
(D5), so no notice is due yet.

- The home screen says: "Your wallet is rebuilding its history. You can send when it has
  finished."
- The record's row in the activity list says: "Sent 50 DGB to D8k…x2Q · your wallet is
  rebuilding its history and has not found this send yet".
- At the tip, a send the scan found is an ordinary confirmed row.
- At the tip, a send the scan did not find is missing. Its row says: "Not in your wallet's
  history. It may still confirm." Before the next review of any send the notice reads: "A send
  of 50 DGB to D8k…x2Q is not in your wallet's history. It may still confirm. Check with the
  person before you pay again."

Try again is not offered on a missing record. The wallet has no bytes to send.

## 7. What this design stores, and what erases it

### 7.1 The stores

**Two new stores, each per wallet and per network.** Both are preferences files added to
`WalletStores.WALLET_AND_NETWORK_PREFS` (`kt:wallets/WalletStores.kt:6-7`) and resolved only
through `WalletStores.prefsName`. The legacy wallet gets the bare names and `_testnet`. Wallet
`<id>` gets `_<id>` and `_<id>_testnet`.

- **`dgb_waiting_send`**: the one request (section 5.3).
- **`dgb_send_marks`**: the marks beside a send. "Cancel requested", with the cancel's txid
  and its "saved" bit once there is one (6.4). "Removed" (6.5). The notes a rebuild writes for
  sends in flight (6.6). The endpoint's "confirmed at height h" answers (6.2). The "restored"
  mark (7.2). The "acknowledged" bit of each notice (5.7). Each mark carries the txid, payee,
  amount, kind and time it needs to be shown.

The third revision put the marks in `dgb_outgoing_tx`, "which every wipe path already clears".
That was wrong: `clearSyncData` does not touch that file (`kt:WalletManager.kt:1902-1927`) and
neither does the crash-loop breaker (`app:StaleDataWiper.kt:47-95`). And the rebuild, which
does clear it (`kt:WalletManager.kt:899`), is the one path where the marks must survive. So
they get their own store.

Rules for both:

- Written whole, with `commit()`, never `apply()`. A mark that must survive a restart cannot
  sit in a queue.
- Plain, like `dgb_outgoing_tx`, which already holds payee addresses. No key and no signature.
  The app is excluded from backup (`app/src/main/AndroidManifest.xml:22-24`).
- **Each carries the wallet's fingerprint, the v2 one** (`seed_fingerprint_v2`,
  `kt:WalletManager.kt:91-92`). It is made from the derived seed, so two wallets that differ
  only in passphrase do not share it; the v1 one they do share.
- A file whose fingerprint is not the loaded wallet's is **never shown and is deleted**. A new
  wallet must not see the last wallet's payee.
- When the loaded wallet's v2 fingerprint cannot be read right now (it can be written later, at
  an unlock), the file is **not shown and not deleted**. It is read again at the next unlock.
- Opened with an explicit scope: **the wallet this process runs**, not the registry's active
  id. After a wallet is added, the registry already names the new one while the old one is
  still loaded (`app:WalletSwitcher.kt:14-15`). The sweep's `OutgoingTxStore(this)` takes the
  registry's scope by default (`kt:OutgoingTxStore.kt:18`); the calls this design adds pass the
  scope.
- **Load never throws.** The stores are read at start, so a throw there would be a crash on
  every launch. Every read is guarded. A request that cannot be read, has an unknown version,
  or fails its own checks reads as "nothing waiting", is deleted, and the user is told once: "A
  waiting send could not be read and was removed. Nothing was sent." A mark that cannot be read
  is kept on disk and skipped, and one line says: "Some notes about earlier sends could not be
  read. Check your activity before you pay someone again."

**Two new keys in a store that exists, `dgb_sync_data`** (section 5.2): `send_history_complete`
and `verified_tip_mark`. Both are written by the code that saves the transaction checkpoint, in
the same commit as the values it already writes there.

**Two changes to a store that exists, `dgb_outgoing_tx`:** the send path writes its record
with `commit()` and before the publish (5.6); and a record can be marked "missing", with
`commit()` (6.7). The store is otherwise as it is. This design relies on its "not settled" and
"missing" records for one notice (5.7).

**One new file, if the owner chooses option A of 5.6: the in-flight record.** Per wallet and
per network, named and resolved through `WalletStores` like the transaction checkpoint. It
holds the signed bytes of one unconfirmed send, and for a cancel the txid it replaced. Only a
wallet above the checkpoint limit ever has one. It is cleared wherever the saved transactions
are cleared, with no exception.

**Kept in memory only, lost at a restart on purpose:** the tip height at which a send was first
seen (the 20-block count), and the app-open clock (6.2). Losing either makes the wallet wait
longer. Neither can open the gate. The endpoint's answers are no longer in this list: they are
kept on disk (6.2).

### 7.2 Every mark and store against every clearing path

"Kept" and "cleared" below are what the design requires. Each "cleared" that is not by the list
is its own line of code with its own test.

| | Full wipe | Removing a wallet | `clearSyncData` for **another** wallet (create; a recover or a seed change whose v2 fingerprint differs or cannot be shown to match) | Rebuild from chain | One-time rebuild after an update | Crash-loop breaker |
|---|---|---|---|---|---|---|
| The waiting request (`dgb_waiting_send`) | **Cleared.** By the list for other wallets (`kt:wallets/WalletStoreEraser.kt:193`); a named step on both networks, read back, for legacy (`kt:WalletDataEraser.kt:319-337`). | **Cleared**, by the list. | **Cleared**, by name. It is another wallet's note. | **Kept.** Opens with the "history was rebuilt" notice. | **Kept**, same notice. | **Kept**, same notice. Reading it cannot crash a start. |
| "Cancel requested" (`dgb_send_marks`) | **Cleared**, as above. | **Cleared**, by the list. | **Cleared**, by name. | **Kept.** It is the evidence the rebuild would otherwise destroy. | **Kept.** | **Kept.** |
| "Cancel lost" | Not stored. It is worked out from "cancel requested" and the wallet's transactions when a review opens. | | | | | |
| "Removed, may still confirm" (`dgb_send_marks`) | **Cleared.** | **Cleared.** | **Cleared.** | **Kept.** | **Kept.** | **Kept.** |
| Notes for sends in flight (`dgb_send_marks`) | **Cleared.** | **Cleared.** | **Cleared.** | **Written** by the new step, then kept. | Not needed: this rebuild keeps the records and waits while one is unsettled. | Not needed: the breaker keeps the records. |
| "Acknowledged" bits (`dgb_send_marks`) | **Cleared.** | **Cleared.** | **Cleared.** | **Kept**, with the mark they belong to. | **Kept.** | **Kept.** |
| "Reported confirmed" answers (`dgb_send_marks`) | **Cleared.** | **Cleared.** | **Cleared.** | **Kept.** Dropped by their own rule (6.2). | **Kept.** | **Kept.** |
| The "restored" mark (`dgb_send_marks`) | **Cleared.** | **Cleared.** | **Written**, by a recover, after the clear. | **Kept** until acknowledged. | **Kept.** | **Kept.** |
| "Missing" on a record (`dgb_outgoing_tx`) | With the record. | With the record. | With the record. | Copied into a note by the new step, then cleared with the record. | **Kept.** | **Kept.** |
| The in-flight record (option A of 5.6) | **Cleared.** | **Cleared**, by the list. | **Cleared**, with the saved transactions. | **Cleared**, after the new step has noted the send. | **Cleared.** | **Cleared.** Loading it cannot then crash a start. |
| Records of sends (`dgb_outgoing_tx`, exists) | Cleared today (`kt:WalletDataEraser.kt:319`). | Cleared today, by the list (`kt:wallets/WalletStores.kt:10-13`). | **Not cleared today** (`kt:WalletManager.kt:1902-1927`). **Changed: cleared**, by name. See below. | Cleared today (`kt:WalletManager.kt:899`), after the new step has copied what is unsettled. | Kept today. | Kept today. |
| `send_history_complete` (`dgb_sync_data`) | Cleared with the file. | Cleared, by the list. | **Cleared** with the file (`kt:WalletManager.kt:1911-1912`). | **Cleared**: a new line in the rebuild's list (`kt:sync/HistoryRebuildOnUpgrade.kt:536-545`). | **Cleared**, same function. | **Cleared** with the file (`app:StaleDataWiper.kt:56`). |
| `verified_tip_mark` (`dgb_sync_data`) | Cleared with the file. | Cleared. | **Cleared** with the file. | **Cleared**, same new line. | **Cleared.** | **Cleared.** |
| A wipe that could not finish (`dgb_wipe_owed`) | No change: it works by wallet id through the same list. | | | | | |

Two details the table needs:

- **The "missing from the history" notice reads `dgb_outgoing_tx`, which has no fingerprint.**
  After a recover with a different phrase the old wallet's unsettled records are still in that
  file today. A notice raised from them would show the old wallet's payee to the new wallet.
  So `clearSyncData` clears `dgb_outgoing_tx` from now on, by name, with a test, **unless the
  wallet is shown to be the same one** (below). That also closes a leftover that exists today.
- **A wallet removed and a full wipe erase both new stores on both networks**, read back. The
  tests that pin the store lists are updated in the same PR (8.4).

**A restore of the same wallet.** The column above is for another wallet. The fourth revision
sent every recover down it. But `recoverWallet` calls `clearSyncData` every time
(`kt:WalletManager.kt:344-346`); only the unlock path first asks whether the seed changed
(`:483-484`). `recoverWallet` has one caller, the onboarding restore
(`app:ui/onboarding/OnboardingViewModel.kt:475`). A user who restores the same phrase on the
same device, with the wallet's stores still on disk, would have lost the marks, the records
and the waiting send with no note written. At the tip the gate would have opened with a send
perhaps still live and nothing to say so.

The rule:

- Before the new seed record is stored, the restore reads the v2 fingerprints already on
  disk: the old seed record's (`seed_fingerprint_v2`, `kt:WalletManager.kt:92`) and the one
  each of the two new stores carries (7.1).
- **If the restored wallet's v2 fingerprint equals one of them, it is the same wallet.**
  `clearSyncData` then **keeps** `dgb_send_marks`, `dgb_waiting_send` and `dgb_outgoing_tx`. It
  clears everything it clears today: the sync data, the saved transactions, the two new keys.
- So "history complete" is off, and no send starts until the tip (D5).
- At the tip the kept stores are read as after the breaker. Records the wallet does not hold
  are missing, by 6.7, and raise the notice. The waiting send opens with the "history was
  rebuilt" notice. Each "cancel requested" mark is read by the start table of 6.4.
- No "notes" step runs, and none can: at a restore no old wallet object is loaded to read
  sends from. A send made on another device has no record here and is not noted. That is true
  after any restore.
- If no fingerprint on disk equals the new one, or none can be read, the stores are cleared as
  another wallet's. If the new wallet's own fingerprint cannot be worked out right now, the
  stores are neither shown nor deleted (the rule of 7.1).

**What a restore cannot keep, and what the user is told.** After a full wipe, or on a new
device, the stores are gone or were never there. Lost: the waiting send; the "cancel
requested" and "removed" marks; the records, which hold the payee names of past sends and the
only evidence of a send that has not confirmed. The chain gives back every confirmed payment.
It does not give back a send that has not confirmed: the wallet learns of it only when it
confirms, or if a peer happens to relay it. Nothing on the device can close that gap. So the
user is told, twice:

- On the restore screen: "If you sent a payment from this wallet in the last few minutes, let
  it confirm before you send again. A restored wallet cannot see a payment that has not
  confirmed."
- Before the first review after the restore: the "This wallet was restored" notice of 5.7. It
  comes from a "restored" mark that every recover writes when it did not keep the stores.

Which routes reach the restore screen with a wallet's stores still on disk was not traced
(8.6). The rule is by fingerprint, so it holds whichever route it is.

**Multi-wallet.** Storing, approving and removing all check that the wallet this process runs
is still the one the screen was opened for. A wallet switch restarts the process
(`app:WalletSwitcher.kt:22-32`), so one process never holds two wallets' gates. The request
stays with its wallet (section 5.4 (i)).

## 8. Build plan

### 8.1 What changes in the shared core

iOS gets all of this with the pin.

**For the gate.**

- `BRSendGate.h`, header-only: the number 3; the count against the verified mark, with its
  guard; the 20-block rule of "Not confirmed yet".
- `BRWallet`: the verified mark and the "history complete" flag, both under the wallet lock.
  One call that sets the flag and one that clears it, **for the platform only**: nothing in
  the core calls either, and a source check in `send_gate_kat` fails if `BRPeerManager.c`
  does. One call that answers the gate for the wallet: open, closed with the txid and count of
  the send it waits for, or history not complete. One call that answers it for a transaction:
  does it spend a wallet coin, is it already held, may it start. No new field on a transaction
  and no change to the checkpoint format.
- `BRWallet`: the registration mode `BR_WALLET_REGISTER_OWN_SEND` and the outcome
  `BR_WALLET_REGISTER_GATE_CLOSED` (G2), checked before the insert **and** before the promote
  branch.
- `BRWalletSend.h`: `BRWalletRegisterOwnSend` returns its verdict.
- `BRSpendGate.h` and `BRAssetSend.c`: the sign-time check (G1) for a platform that uses the
  core's signer gate.
- `BRPeerManager`: `BRPeerManagerPublishTx` and `BRPeerManagerStemPublishTx` refuse a
  transaction that spends a wallet coin and that the wallet does not hold as valid, with their
  own error code; `_peerRequestedTx` and `_peerHasTx` register as an own send, and
  `_peerRequestedTx` serves nothing on a refusal (G3). The peer manager does **not** set
  "history complete".

**For republish and rebuild.**

- `BRStrandedSend.h`: ancestors first, one wait per group.
- `BRWallet.c:1783`: 25 becomes 24.
- `BRChainRebuild.h`: the new step that notes the sends in flight, before the clears, as a step
  the clears depend on (6.6). The platform parity tests follow the list.

**For Cancel.**

- `BRWallet`: the cancel plan (which inputs qualify, the fee, what comes back, which later
  sends go with it) for the dialog; the build-and-sign half of the cancel; and the swap half,
  in a form that stays inside one hold of the wallet lock, for the peer manager only.
- `BRPeerManager`: `BRPeerManagerCancelSend`, the one public entry (6.4). It runs the swap
  under the manager lock with the snapshot and purge the existing removal uses, and takes the
  coin reservation from its caller.

**What the core does not get.** No held set. No change to the conflict order. No pause rule.
No change to the spend guard or the coin reservation for any existing signer. No knowledge of
the waiting request: D4 is asked in the platform's send helper (5.6).

### 8.2 What changes in the Android bridge and in Kotlin

**Bridge (`jni:`).**

- `seed_sign_transaction` and `wallet_signed_spend_gate` ask the gate (G1).
- `_registerWalletCopy` becomes a call to `BRWalletRegisterOwnSend`; the three publish entry
  points read the verdict and stop on a refusal (5.2).
- New natives: the gate's answer for the screens; `registerSend`, which releases the coin
  reservation on every outcome; `signApprovedDigiDollar` (the first half of today's
  `sendApprovedDigiDollar`); the cancel plan; `cancelSend`, in `jni_wallet.c`, which passes the
  bridge's reservation table and clock; the verified mark, read and seeded; "history
  complete", set and cleared, where the set refuses during a rescan while the flag is off; a
  by-txid "held, and at what height" answer for the sweep.
- The table generator runs with `--check` in CI (`docs/development-process.md` rule 6).

**Kotlin.**

- The send order of 5.6 for all five kinds of path, in one shared helper, so there is one copy.
  Its first line asks `SendAdmission`. Its rollback releases the coin reservation. It is the
  only caller of the native sign entries, and a source test holds that.
- `SendAdmission`: the one function every review asks first, and the helper asks again. It
  answers: start, offer to wait, refused because one is waiting (D4), or history not complete
  (D5).
- `SyncTip.atTip()`: the one definition of "at the tip" (5.2), taken out of the two places
  `SyncService` has it today. The call that sets "history complete" sits behind it.
- The in-flight record for a wallet above the checkpoint limit, if the owner chooses option A
  (5.6): its store, its load at start, its clearing lines.
- `WaitingSendStore`, `SendMarksStore`, their `WalletStores` entries, and every cell of 7.2.
- The two keys in `dgb_sync_data` and their clearing lines.
- The screens of 5.4, the notices of 5.7, the three states of 6.2, the dialogs of 6.4 and 6.5.
- The sweep: skips a send with a "cancel requested" mark; never removes a cancel as refused;
  writes the "removed" mark before a removal; publishes a cancel only when its mark says
  "saved", and retries the save until it does (6.4); asks the wallet by txid; leaves an unheld
  record alone while the history is not complete, and marks it "missing" afterwards (6.7).
- The existing confirmation check keeps the **endpoint's** answer in the marks store, whatever
  the core said (6.2).
- The restore: the same-wallet rule of 7.2, the line on the restore screen, the "restored"
  mark.
- The manual rescan's line when the last send has 1 or 2 confirmations (6.6).
- The rebuild's new step; the reading of notes and unsettled records at the tip.
- The reconcile screen offering Cancel in place of removal.
- Asset row clean-up after a cancel.
- Strings in 13 languages.

### 8.3 Pull requests, in order

Every PR that touches what may be signed, sent, cancelled, republished or erased is
**owner-gated**: the owner reviews it before merge, whatever the independent review says. Core
lands on its `develop` before each app pin moves.

| # | Repo | What | Owner-gated |
|---|---|---|---|
| 1 | core | The gate: `BRSendGate.h`, the mark, "history complete" set by the platform only, the own-send registration mode and verdict before both inserts, the publish refusal, both peer handlers, the sign-time check, the recovery signers' refusal while the history is not complete; `send_gate_kat` | Yes |
| 2 | core | Ancestors-first republish; 25 to 24; the rebuild's new step; `stranded_send_kat` extended | Yes |
| 3 | core | Cancel plan and `BRPeerManagerCancelSend`, with the swap under the manager lock and the publish-list purge; `cancel_send_kat`, built with the address and leak sanitizers | Yes. This is the one that adds a signing entry outside the spend guard (section 9.1, question 1). |
| 4 | app | Pin to 1 and 2. Bridge: G1, the verdict, the publish entry points, the new natives. Kotlin: the send order of 5.6 for every path with `SendAdmission` asked by the helper, the reservation released on every way out, the rule for a wallet above the checkpoint limit (question 2), the "wait" message for all kinds, `SyncTip.atTip()`, the two keys and D5, ancestors-first wiring | Yes |
| 5 | app | `SendMarksStore` and every cell of 7.2; the "removed" mark on every removal; the rebuild's new step; the sweep's rules for a record the wallet does not hold (6.7); the same-wallet restore and the "restored" notice (7.2); the reading at the tip; the notices of 5.7 that need no request | Yes |
| 6 | app | `WaitingSendStore` and its cells of 7.2; the offer, the refusal (D4), the waiting row, the ready banner, review from a request, the same-address notice, remove. Plain DGB. | Yes |
| 7 | app | The three states of 6.2, defined from the wallet's transaction; the two clocks; the endpoint's answer kept in the marks store; Try again; Rescan on the row; the row and the rescan line for a send with 1 or 2 confirmations (6.6) | Yes |
| 8 | app | Pin to 3. Cancel this send: dialog, PIN prompt, the write order with its roll-forward, the marks, the lost-cancel notice, asset clean-up, the sweep's rules for a cancel, the reconcile screen change | Yes |
| 9 | app | DigiDollar and DigiAsset requests in the waiting slot | Yes |
| 10 | app | Notifications: ready, not confirmed yet, cancel lost | No. Independent review and green CI. No amount or payee in any text. |

**What must ship together.**

- **1, 2, 3, 4, 5, 7 and 8 are one release.** A gate without the stuck half is a wallet that
  can stop sending. A gate without the marks is a wallet that can be led to pay twice after a
  rebuild or a removal. Cancel without the marks has no lost-cancel notice.
- **The sweep's rules of 6.7 are in 5, and 5 ships with 4.** With the gate and D5 in place and
  the old sweep still running, a record would be settled before its notice could be read.
- **4 must not merge to `develop` ahead of 5, 7 and 8 by more than the time it takes to review
  them.** `develop` is what ships. If a release had to be cut in between, it would be cut from
  before 4.
- **6** can ship with them or after. Without it the user waits and sends again; D4 has nothing
  to refuse.
- **9** can follow 6. Until then those two kinds get the plain "wait" message.
- **10** can follow. Without it the prompts are in the app only.
- Each PR that adds strings adds them in all 13 languages.

### 8.4 Tests

Every new gate is seen red on the unfixed code first, and the PR comment says how.

**Host KATs (core).**

- `send_gate_kat`:
  - closed at 0, 1 and 2 confirmations, open at 3;
  - a reorg closes it again and lowers the mark;
  - **a rescan that lowers the tip leaves a send with 3 confirmations at 3**, and a send with
    no height closed;
  - a confirmed send saved and loaded again, with the saved mark, leaves the gate open; with no
    saved mark it reads from the highest held height;
  - the mark below a height reads 0 (the guard);
  - an invalid transaction does not close it; a receive does not close it;
  - **a sweep with no wallet input is not refused and does not close it; a sweep that spends
    one wallet coin is refused while closed and closes it**;
  - a send from another device (peer-relayed, or imported from a server) closes it and is not
    refused;
  - a transaction first imported from a server and then confirmed over P2P counts by its height;
  - **two own sends registered at once: exactly one is held, the other's verdict is "gate
    closed" and the wallet does not hold it**;
  - **a refused registration followed by a publish: the peer manager refuses it, and a later
    `getdata` for it serves nothing and registers nothing; a later `inv` for it registers
    nothing either**;
  - **a record the wallet keeps as "not mine" that spends a wallet coin, registered as an own
    send while the gate is closed: it is not promoted** (red arm: the check before the promote
    branch removed);
  - **headers at the tip and the filter scan not: "history complete" stays off, because
    nothing in the core sets it**; the source check that `BRPeerManager.c` never calls the
    setter;
  - while "history complete" is off, the signer gate refuses a sweep whatever it spends;
  - a republish of a held send passes all three checks;
  - a DigiDollar build and an asset build are refused at the signer while closed;
  - history not complete closes it; the platform's call opens it;
  - the 20-block rule.
  - Red arms: 2 confirmations accepted; the count taken against the current tip (the rescan arm
    then fails); the gate skipped at registration; the publish refusal skipped; the verdict
    ignored by a publish entry point (the source check on the bridge).
- `cancel_send_kat`:
  - **the present signer refuses the cancel** (the reason the entry exists; this arm stays
    green before and after, and pins the spend guard as untouched);
  - a cancel of a plain send; of an asset send (plain fee coin only); of a DigiDollar send;
  - refused when an input is asset-held, unclassified, or has no height;
  - **an asset send whose asset input is implicit change, with the asset list empty as after a
    restart: that input is not in the plan** (the tx-local rule);
  - the built cancel has exactly one output and it is the wallet's; **an entry made to pay a
    foreign address signs nothing** (red arm: the output check removed);
  - refused when the fee or the amount is not the approved one;
  - **refused, with nothing changed, when the named send confirmed before the swap; when it is
    no longer the oldest of its chain; when a child arrived between the two checks**;
  - nothing is left signed after any refusal; the claim is released;
  - the swap leaves a valid, publishable cancel; it passes while the gate is closed; the gate
    is then closed on the cancel;
  - the original arriving in a block makes the cancel invalid **and the gate then follows the
    original**; the cancel confirming keeps the original out;
  - **the original relayed without a height, in both orders: paying a segwit address (the
    cancel stays valid) and paying a legacy-chain address of the wallet (the original sorts
    first and the cancel is invalid)**; the same after a checkpoint save and load;
  - **a cancel of a send that is in fact confirmed: after the block is delivered the wallet
    holds the original at its height and the cancel as invalid**.
  - **Memory safety, with the address and leak sanitizers.** X published and relayed back by a
    peer, so the publish list points at the wallet's own record; then the cancel is swapped;
    then, in turn: a `getdata` for X, an `inv` for X, a new peer that is sent the list, and
    the manager torn down. Expected: no read of freed memory, no double free, no leak; X is
    not served and is not announced. The same with X listed as the parent of a published
    child (a train). The same with X listed as the list's own copy with a publish still
    waiting: it is answered "cancelled" exactly once.
  - **Red arm for it:** the swap done under the wallet lock alone, with no purge. The sanitizer
    must report a use of freed memory at the `getdata`. The arm runs under a timeout, because
    a sanitizer report can hang instead of exit.
  - the source check: the wallet's swap half has one caller, in `BRPeerManager.c`;
  - two cancels of one send at once: exactly one swaps, the other stops with nothing changed
    and its claim released;
  - a cancel with a legacy input: the claim is held under the signed txid;
  - the entry is refused when no peer manager is paired with the wallet.
  - Red arms: an asset input accepted; a non-wallet output accepted; the second check skipped;
    two cancels of one send both swapped (the second check skipped).
- `stranded_send_kat` extended: ancestors first; a parent on its hourly hold is still sent
  before its child; one wait per group. Red arm: txid order.
- The selection KAT: a coin with 24 unconfirmed ancestors is not selected. Red arm: 25.
- The chain-rebuild parity KAT: the new step is before every clear, and a failure of it stops
  the rebuild.
- Existing selection, prepared-send, coin-reservation, spend-guard, publish-ownership and
  stale-send KATs stay green.

**Unit tests (Kotlin).**

- The send order: for each of DGB, DigiDollar, asset, sweep, a fake that fails at each step
  shows the row of the table in 5.6. Red arm: publish before the checkpoint.
- The helper: **a send started while a request is held is refused by the helper itself, on
  every path, even when the screen did not ask** (D4); the request's own review passes; after
  a failed save the coin reservation is free; the source test that only the helper calls the
  native sign entries.
- A wallet above the checkpoint limit (a persister forced to answer `REFUSED_OVER_LIMIT`): by
  the owner's choice. For option A: the send is saved in the in-flight record before the
  publish; a restart registers it; a cancel's record replaces the send it cancelled; the
  record is gone after every clearing path.
- `SyncTip.atTip()`: false with headers at the tip and the scan behind; false with a frontier
  of 0; false during a rescan; "history complete" is not set in any of the three.
- `WaitingSendStore`: round trip; **load never throws** on a torn file, an unknown version, a
  wrong type, random bytes; another wallet's fingerprint is refused and deleted; **a fingerprint
  that cannot be read now is not shown and not deleted**; a second request is refused; **the
  three rows of the restart table in 5.3, matched by txid**; a second send to the same payee for
  the same amount does not satisfy a request with a different txid.
- `SendMarksStore`: the same load and fingerprint arms; every write is a `commit()`.
- Clearing, one test per cell of 7.2 that says cleared or kept. Among them: **marks after
  `clearSyncData` are gone; marks after the breaker are there; marks after a rebuild are there
  and the notes were written before the clear; the waiting send after a rebuild is there and
  opens with its notice; `dgb_outgoing_tx` after `clearSyncData` is gone.** The name tests are
  updated in the same PR: `LegacyStoreNameParityTest`, `UpgradeFrom414Test`,
  `WalletStoreEraserTest`, `WalletDataEraserReachTest`, `ReservedStoreNamesTest`,
  `PerWalletStoresTest`, `StoreSuffixSourceGateTest`.
- `SendAdmission` and view models: each state of 5.4; the gate closing between Review and the
  PIN gives the offer, not an error; ready is shown only at the tip with the gate open; **a new
  send is refused while a request is ready (D4)**; history not complete closes it (D5); the
  update from 4.1.5 with `has_synced` on writes the new key on.
- Notices (5.7): **cancel asked, a request to the same payee waiting, the original confirms
  while no sweep has run: the review opens with the notice and no PIN prompt until it is
  acknowledged**; **the send a request waited behind paid the same address and confirmed: the
  same-address notice, acknowledge before PIN**; a removed send; a record with no transaction
  after the breaker; a note after a rebuild.
- The sweep and an unheld record (6.7): **the breaker, then three sweeps with the history not
  complete: the record is not settled and no try is counted; then the tip: it is "missing" and
  the notice is owed**; the same in a wallet with more than 100 rows (red arm: "aged out" read
  off the list); a record held at a height is settled; a missing record whose txid later
  appears at a height is settled.
- The restore (7.2): **the same phrase restored with marks, records and a request on disk:
  all three are still there, the gate is closed until the tip, and the request opens with its
  notice**; a different phrase: all three are gone and the "restored" mark is written; a
  fingerprint that cannot be worked out: nothing shown, nothing deleted.
- Cancel: the write order with a death at each step, against the start table of 6.4; a
  cancelled send is never selected for republish; a cancel is never removed as refused; the
  mark for a send with no record; the dialog lists the later sends.
- Cancel, roll forward: **the save fails after the swap and the process lives: the cancel is
  not published; the mark has its txid and "not saved yet"; another writer's checkpoint lands;
  the cancel is still not published until the sweep's own save sets "saved"; then it is
  published once.** Red arm: publish without "saved".
- The asset arm the KAT cannot show: **an asset send whose input is implicit change, left
  unconfirmed, the replay run again as after a restart, Cancel planned: the asset input is not
  in the plan.**
- The states of 6.2: "Not confirmed yet" not shown behind the tip and shown for a send with no
  record; "Cannot check yet" after 20 minutes of app-open time with no block at the tip, not
  after 20 minutes in the background, reset by a block at the tip and not by a block below it;
  the endpoint's answer hides Cancel and Try again and is dropped once the scan has covered
  the height; **the endpoint reports a height and the core answers "refused" (a stalled tip):
  Cancel and Try again are still hidden** (red arm: keyed on the core's answer); the answer is
  still there after a restart; it never changes what the gate answers.
- A send with 1 or 2 confirmations when a rescan starts: the row's text; no Try again and no
  Cancel; the manual rescan shows its line first.
- `LocaleResourceParityTest`; `OnboardingHardcodedStringTest`.
- Before each commit: the full core and app unit suites with `--continue`.

**Device sends (Note 8 mule, small amounts between its own addresses).** About 34 sends.

1. Send, then Send again at once. Expected: the offer; the request is stored; nothing second on
   the network; "ready" after 3 confirmations; review; PIN; it confirms. Once with Dandelion
   off, once on.
2. A third Send while one waits, and again when it is ready. Expected: refused both times,
   naming the waiting send.
3. Store a request, force-stop, reopen after 2 minutes. Expected: the request is there, ready
   after unlock, and nothing was sent meanwhile.
4. Force-stop during an approval, as close to the PIN as a hand can manage, five times.
   Expected each time: either the send is in the activity list and the request is gone, or the
   request is back and nothing is on the network. Never a send on the network that the list
   does not show.
5. Store a request for more than will be left. Expected at its turn: "not enough", nothing
   signed.
6. Remove a waiting send. Expected: no PIN, nothing on the network.
7. Lock the phone while a request waits. Expected: one notification with no amount or payee.
8. Network cut right after a send. Expected: "Not confirmed yet" after 20 blocks at the tip;
   Try again with the network back confirms it.
9. Cancel a send that never reached a node (network cut at send, restored for the cancel).
   Expected: the cancel confirms; the original never does; the gate opens when the cancel has
   3.
10. Cancel a send that did reach the network, with a request to the same payee waiting.
    Expected: the original confirms; the cancel dies; the notice appears before the waiting
    send can be approved.
11. Send to an address, store a second send to the same address, let the first confirm.
    Expected: the same-address notice before the PIN.
12. Start a rescan with a confirmed history. Expected: Send works during the rescan.
13. Rebuild from chain with a send unconfirmed and a request waiting. Expected: no send until
    the tip; then the notice naming the send, before the waiting send can be approved.
14. A DigiDollar send and an asset send while a DGB send is confirming. Expected: each waits.
    One test asset must be unruled; a rule-bound one must still be refused at review.
15. Cancel an asset send. Expected: only its DGB fee coin is spent; the asset is still held
    after the cancel confirms.
16. Switch wallet with a request waiting. Expected: the line in the dialog; the request is
    there on return.
17. Update from 4.1.5 with a confirmed history. Expected: the first send starts at once. Again
    with a send left unconfirmed before the update. Expected: the gate is closed and the row
    shows the states of 6.2.

18. Send, wait for 1 confirmation, start a rescan. Expected: the line before the rescan; the
    row's text during it; Send works again soon after the headers are back, well before the
    scan ends.
19. Cancel a send that a peer relayed back (send with the network on, cut it before a block,
    cancel). Expected: no crash; the original is no longer announced; the outcome is one of
    the two in test 9 or 10.
20. Restore the same phrase on the same device with a send unconfirmed and a request waiting,
    if a route to the restore screen keeps the stores (8.6). Expected: no send until the tip;
    then the notice naming the send, before the waiting send can be approved.
21. Restore on a wiped device. Expected: the line on the restore screen, and the "restored"
    notice before the first review.

The pre-publish suite sends no coins, so it cannot cover this. It gets one check at most: Send
on an empty wallet still gives the insufficient-funds refusal, not a gate message.

### 8.5 What iOS gets

From the shared core, with the pin: the gate and its number, the mark, "history complete", the
own-send registration verdict, the publish refusal, the sign-time check in the core's signer
gate, the "Not confirmed yet" rule, the cancel plan and `BRPeerManagerCancelSend`,
ancestors-first republish, the 24 fix, the rebuild's new step, and all the KATs.

iOS still has to build: reading the registration verdict in its publish path (until it does,
the publish refusal stops a gated send anyway); the send order of 5.6; the call that sets
"history complete", behind the same definition of "at the tip" (until it does, the gate stays
closed on iOS, which is the safe side); passing the core's reservation table and its clock to
the cancel entry; its own stores and their wipe coverage; the screens, the notices and the
strings. Nothing in this design needs
background time, so iOS loses nothing by having little of it.

### 8.6 Not verified

- Nothing was built, run or sent for this revision. No KAT, no unit test, no device, no
  emulator. Statements about the code are from reading the cited lines at the two commits
  named at the top.
- That a confirmed original displaces an unconfirmed cancel was read from the ordering code
  (`core:BRWallet.c:161-162`, `:224`, `:417-431`). It was not run. `cancel_send_kat` must show
  it before cancel is relied on.
- That nothing after the first change in the swap can fail. It rests on allocating first. The
  removal code today releases the lock and calls itself again for each dependant
  (`core:BRWallet.c:2794-2802`); the swap needs a form that stays inside one hold. Not written.
- That the table in 6.4 lists every holder of a pointer to a wallet-owned transaction. It was
  made by reading every use of the publish list and every wallet lookup in `BRPeerManager.c`
  at core `8014392`. It was not run. The sanitizer arm of `cancel_send_kat` is the proof, and
  it must be seen red first.
- That the wallet's removal callbacks may be delivered after the manager lock is released.
  Today's removal delivers them inside it (`core:BRWallet.c:2831-2832`, called from
  `core:BRPeerManager.c:10014`). The swap's order of messages is a requirement on new code.
- That the verified mark rises with the header tip during a rescan, so a send with 1 or 2
  confirmations waits only for the headers. Read from the three callers of
  `BRWalletSetBlockHeight` (`core:BRPeerManager.c:4428`, `:5533`, `:9631`). Not run, and how
  long the headers take after a rescan was not measured.
- Whether the compact-filter frontier is moved back by the bridge's rescan after the first
  sync. The design does not rely on it: the flag is not set during that rescan by rule.
- How long the launch rescan of a wallet above the checkpoint limit takes. No such wallet was
  at hand. Under option A that is how long it waits before it can send after each launch.
- Which routes reach the restore screen with a wallet's stores still on disk. `recoverWallet`
  has one caller. Whether the full wipe always runs before it, for example when a removed lock
  screen has made the seed key unusable, was not traced.
- Records that 4.1.5 already settled as "gave up" cannot be told from confirmed ones. A send
  given up on before the update raises no notice after it.
- Whether a peer ever relays a wallet's own unconfirmed send back to a freshly restored
  wallet. The design assumes it cannot be relied on.
- That an output of a transaction with no asset data can never hold asset units. It follows
  from the DigiAsset rules as this project records them. It was not checked against DigiAsset
  Core for this revision.
- A reorg whose join point has left the block window does not take heights away
  (`core:BRPeerManager.c:4647-4663`). A send confirmed on the abandoned branch then keeps a
  stale height, and the gate reads it. That exists today and fails on the side of letting the
  user send.
- An old record whose height came from a server before 4.1.3. Nothing in the checkpoint tells
  it from a proven one. Same side.
- (Answered in this revision.) A same-phrase recover does reach `clearSyncData`
  (`kt:WalletManager.kt:344-346`). Section 7.2 has the rule for it.
- (Answered in this revision.) The saved-checkpoint write does report failure in a form step
  4 of 5.6 can act on (`Result.landed`, `kt:WalletTxPersister.kt:41-53`). One of its answers,
  `REFUSED_OVER_LIMIT`, is permanent for a large wallet. That is question 2.
- Whether the existing Rescan can start from a given height, for the "reported confirmed" row.
  The design needs only the Rescan that exists.
- Whether the Send screen takes a note today.
- The measured block times and node policy are the earlier critiques' and the first draft's
  readings of the local node. They were not re-run for this revision.
- Section 4 is from memory. The 8.22.2 policy column was not re-checked.
- The 13 translations. None exist yet. The cancel dialog and the notices of 5.7 need a native
  reader before they are called done.

## 9. Questions for the owner

D1 (as amended), D2, D3, D4 and D5 are decided and not asked again.

### 9.1 Still open

1. **May Cancel have its own signing entry, outside the 4.1.2 double-sign guard?** Every other
   signer refuses a coin the wallet holds as spent. A cancel spends nothing else, so it cannot
   use them. The entry proposed in 6.4 takes only a txid, builds the cancel itself from that
   send's plain inputs, pays one address of this wallet, and changes nothing until every check
   has passed. The guard stays as it is for every other signer. *Recommended: yes.* If the
   answer is no, Cancel cannot be built and D3 has to be looked at again.
2. **What may a wallet with more than 100,000 transaction records do?** Its checkpoint is
   refused on every write, so "saved before it is published" can never be true for it by the
   ordinary road. It could neither send nor cancel. The choices are in 5.6: (A) save just the
   one send in flight in its own small file; (B) do that for every wallet; (C) let such a
   wallet publish without a save, and refuse Cancel for it; (D) refuse to send. *Recommended:
   A.* Under A such a wallet sends and cancels like any other, and after each launch it waits
   for its launch rescan first, as it rescans at each launch today.
3. **Budget for device test sends on the Note 8.** *Recommended: approve 34 sends of small
   amounts between the mule's own addresses, plus two DigiDollar sends and three asset sends
   (one of them cancelled).*

### 9.2 Proceeding on these unless told otherwise

Each can be vetoed in one line, by its letter.

- **A. Notifications.** One, once each, with no amount and no payee: "A send is ready for your
  approval", "A send has not confirmed yet", "A send you cancelled was paid after all". (Was
  question 2.)
- **B. The waiting send does not expire.** It cannot send itself. It always shows when it was
  asked. (Was question 3.)
- **C. No PIN to store or to remove the waiting send.** Nothing is signed by either. The
  approval always asks. (Was question 4.)
- **D. "Not confirmed yet" after 20 blocks at the tip**, about 5 minutes. Cancel is offered
  from then. (Was question 5.)
- **E. A send made on another device with the same phrase closes the gate here.** Try again
  and Cancel work on it here. (Was question 6.)
- **F. Sweeps and recovery moves are judged by what they spend.** One that spends only another
  phrase's coins neither waits nor closes the gate. One that spends a coin of this wallet is a
  send. (Was question 7, corrected by critique point 17.)
- **G. "Cannot check yet" after 20 minutes with the app open and no new block at the tip**, and
  Cancel is offered there with wording that says the wallet cannot tell whether the send
  confirmed. This covers a wallet behind the tip and a wallet whose tip has stalled. (Was
  question 9, widened by critique point 7.)
- **H. On the reconcile screen, Cancel is offered in place of removal when a cancel can be
  built.** (Was question 10.)
- **I. DigiDollar and DigiAsset requests in the waiting slot ship in 4.1.6 if PR 9 is ready.**
  Otherwise those two kinds get the plain "wait" message. The 3-confirmation rule applies to
  them from PR 1 either way. (Was question 11.)
- **J. iOS gets the core rules with the pin. The iOS screens come when that work is
  scheduled.** (Was question 12.)
- **K. A rescan does not stop sending.** Confirmations are counted against the highest tip the
  wallet has verified, and that number is saved with the transaction checkpoint. The other
  choice is simpler and costs more: count against the current tip, and no send starts for the
  whole of every rescan. (New, critique point 6.)
- **L. When the endpoint has already reported a send as confirmed, Cancel and Try again are
  hidden for that send and Rescan is offered.** It uses the answer the existing check already
  fetches. It adds no query and does not open the gate. The answer is kept whatever the core
  says about it, and it is kept across restarts. A wrong answer can hide Cancel until the
  wallet's own scan has covered that block. (Critique point 5; changed by check finding N4.)
- **M. The rebuild from chain keeps a note of every send that was in flight**, keeps the
  waiting send, and shows the note before the next review. Today it erases the records. (New,
  critique points 4 and 16.)
- **N. Every send is saved before it is published.** The DigiDollar native call is split into
  "sign" and the common "register, save, publish" to allow it. (New, critique point 8.)
- **O. A cancel of an asset or DigiDollar send spends only coins whose funding transaction
  carries no asset data at all.** Some such sends then cannot be cancelled. (New, critique
  point 15.)
- **P. `clearSyncData` also clears the records of sends**, so a new wallet never sees the last
  wallet's payees. Not when the same wallet is restored: see S. (Critique point 3; changed by
  check finding N5.)
- **Q. "At the tip" means the scan, not the headers.** One definition for the whole design,
  the one Kotlin already uses before it marks a wallet synced. "History complete" is set by
  the app from that test and never by the core. (Check finding N2.)
- **R. The sweep leaves a recorded send it cannot find alone while the history is rebuilding.**
  Afterwards such a record is "missing", not settled, and the notice names it before the next
  review. (Check finding N3.)
- **S. Restoring the same wallet on the same device keeps its marks, its records and its
  waiting send.** A restore that cannot keep them says so, on the restore screen and before
  the first review. (Check finding N5.)
- **T. While the history is rebuilding, every sweep and recovery move waits too**, even one
  that spends another phrase's coins. The wallet cannot yet tell whose coins they are. (Check
  finding N6.)
- **U. A send with 1 or 2 confirmations when a rescan starts makes the user wait** until the
  headers are back at the tip. A rescan the user starts says so first and offers to wait.
  (Check finding N8.)
- **V. A cancel is published only after it is saved.** If the save fails, the wallet keeps
  trying and says so. It never goes back to the original. (Check finding N10.)
- **W. The cancel entry belongs to the peer manager and is refused in the short window when
  no peer manager is paired with the wallet.** (Check finding N1.)

## 10. History

### 10.1 The first draft's three options

The first draft (2026-11-22) set out three designs and recommended the first.

- **A, the chain gate.** Coin selection would spend the wallet's own unconfirmed change only
  when a peer had taken the parent and the chain was at most one deep. Dropped: its evidence
  proved nothing (a peer asking for the bytes is not a node accepting them), and it could hold
  a whole balance after a restart. D1 then replaced it.
- **B, a queue.** B1 signed later, without the user, which breaks the PIN rule. B2 signed at
  approval and held the bytes. The draft advised against both.
- **C, batching.** Several payees in one transaction. Not dropped: it is a good later feature
  and the real answer to "one payment at a time". It does nothing for a user who sends twice.

The recovery items R1 to R4 became sections 6.1 to 6.4. R3, automatic release by asking the
default node, was dropped then and stays dropped.

### 10.2 The second revision: a queue signed in advance

Written to D1 as first given. A queue of any depth. Each send was approved and signed when
added, held on the device, and released automatically when the one before had 3
confirmations. Dropped because the owner amended D1: one deep, and the user present to
complete each send. An independent critique of that revision (26 points) also showed the
automatic release was where most of its risk sat: a direct send could be built on a held
send's change, a lost cancel could pay twice with nobody there, and held signed bytes had a
second copy on disk.

### 10.3 What was removed with automatic release, and why

Each of these existed only so a signed send could wait and leave on its own. With the user
approving at the time, none has a job.

- The held signed transaction, and the queue file that stored signed bytes.
- The chain signed in advance, and its 24-link limit.
- The "held" set in the core, and the peer manager changes that kept held sends off the wire.
- `releaseNext`, its lock, and its callers in `SyncService` and `SyncWorker`.
- The foreground notification that kept the service alive to release sends.
- The pause rule and its clock.
- The pre-release check and the "Needs your approval" state.
- The DigiDollar native split into "sign and hold" and "release".
- The "height proven" bit. It was dropped for a different reason: production writes only
  heights the wallet proved, so the height is the proof (critique v2, point 7).

### 10.4 The critique of the second revision, point by point

The answers below are the third revision's, with its section numbers. Where this revision
changed one, 10.6 says so.

1. **A direct send could be built on a held send.** Gone with automatic release. There are no
   held sends, and the gate is enforced in the core for every builder (5.2).
2. **A cancel that loses could pay twice, automatically.** Nothing is automatic now. The
   remaining risk, the user's own next approval, is answered by the lost-cancel notice (6.4).
3. **Remove racing a release.** Gone with automatic release. Remove is refused for a request
   in "approving" (5.3, 5.4 (g)).
4. **An entry that may have left coming back as a request.** Gone with automatic release. Its
   lesson is kept: "approving" is one-way (5.3).
5. **The authorisation had no age limit.** Gone with automatic release. The approval and the
   send are the same moment.
6. **The pause clock was the wall clock.** Gone with the pause rule. "Not confirmed yet" counts
   blocks (6.2). The one timer left uses the monotonic clock (6.6).
7. **Server heights are not stored as proven.** Accepted. Corrected in 1.7. No new bit.
8. **The proven bit had nowhere to live across a restart.** Answered: the gate reads the
   height, which the checkpoint saves (5.2). `send_gate_kat` has the reload arm.
9. **`tip - height + 1` wraps below the height.** Answered: the count is 0 there (5.2), with a
   KAT arm.
10. **The worker cannot act without a loaded wallet.** Accepted. Corrected in 1.7. Nothing
    acts in the background now; only a notification, and only from a live process (5.4 (d)).
11. **Held sends leaked their txids to the endpoint.** Gone with automatic release. The fact
    about the existing txid check is corrected in 2.2 and 6.3. No new query is added.
12. **The manual check saw and removed held sends.** Gone with automatic release. The manual
    check is not added to the stuck row (6.3).
13. **Stale removal does not spend the coins.** Answered: 6.5.
14. **The list does not reach every clearing path.** Answered: section 7, path by path, with
    the fingerprint.
15. **A second copy of held signed bytes in the checkpoint.** Gone with automatic release. The
    request holds no signature. The fact is recorded in 1.7.
16. **Loading the queue could crash the start.** Answered: load never throws, and the breaker
    deletes the store (section 7).
17. **Wallet removal should warn.** Answered: 5.4 (i). The store takes an explicit scope
    (section 7).
18. **Cases with no exit.** Answered: the table in 6.6. The stuck state and Cancel are defined
    from the wallet's transaction.
19. **After a rebuild the send in flight is forgotten.** Answered: no send until the tip is
    reached once (5.2).
20. **A held DigiDollar or asset send hid the wallet's DGB.** Gone with automatic release. A
    send in flight hides its change for about a block, as today (5.5).
21. **"Stored as a request" needed a reason from the builder; 24 versus 23 links.** Gone with
    automatic release. Every waiting send is a request. The 24 is now stated as ancestors
    (6.1).
22. **Refuse DigiDollar and asset sends while the queue is non-empty.** Gone with automatic
    release. The gate covers them in the core from PR 1.
23. **What the asset layer shows for a held asset send.** Gone with automatic release. Nothing
    is registered until the user approves.
24. **"About 1 minute" is the median.** Answered: "usually about a minute, sometimes a few"
    (5.4 (a)); figures in section 3.
25. **The cancel swap's preconditions under the lock.** Answered: 6.4 step 4.
26. **Tests to add.** Answered: 8.4 carries the arms for points 2, 8, 9, 14 and 25. The arms
    for points 1, 3, 11 and 12 have no subject left.

### 10.5 The critique of the first draft, point by point

The full text of both critiques is in this file's history (commit `af9d8ad8b` for the first).

1. **"Taken by a peer" proves nothing.** Accepted. Corrected in section 2. Nothing here uses
   relay evidence.
2. **`txRequests` misread.** Accepted. Corrected in section 2.
3. **Strong evidence exists.** Recorded in section 2. Not used: confirmations are stronger.
4. **The gate locks more coins.** True of every gate, this one included. It is now the owner's
   rule, shown to the user, with the exits in 6.6.
5. **The stale release misses custom endpoints and may never start its clock.** Accepted.
   There is no automatic release (6.3).
6. **The stale release becomes an automatic query.** Accepted. Same answer.
7. **Ordering alone does not put parent before child.** Accepted: 6.1.
8. **"Not sent yet" could be said of a confirmed send.** Accepted: 6.2.
9. **The cancel could not be published; "plain" must be proven.** Accepted: 6.4.
10. **DigiDollar and asset senders got the gate without the message.** Accepted: 5.5.
11. **An approved send could be refused after the PIN.** The gate is asked before the review,
    so this needs another device's send to arrive in those seconds. The user then gets the
    offer in 5.4 (a), not an error.
12. **The off-by-one is real.** Accepted. Corrected in 1.1. The fix is in PR 2.
13. **A simpler rule.** Overtaken by D1. Its part (b), ancestors first, is 6.1.
14. **Small corrections.** Applied in sections 2 and 3.

### 10.6 The third revision, and its critique point by point

The third revision (commit `8ca18a567`) was the first written to the amended D1: one send
waiting, approved at its turn. An independent critique of it had 20 points: 3 found wrong, 13
risks, 4 suggestions. It found no path that signs or broadcasts with nobody present. The
fourth revision answered each, as below. The owner's D4 and D5 arrived with it. Where the fifth
revision changed an answer, 10.7 says so.

1. **The gate was placed where Android's send paths do not go, and a refused registration did
   not stop the publish.** Accepted. Every path is traced in 5.2. The gate is now checked
   before signing in both of the bridge's signer exits, decided at an own-send registration
   that returns a verdict, and backed by a refusal in the peer manager's publish and in its
   answer to a peer's request.
2. **Cancel could not be signed by the present signer.** Accepted. 6.4: a cancel-only entry
   that takes a txid, builds the cancel itself, and checks, signs and swaps in one call. The
   swap-then-sign order was weighed and rejected. It is question 1 of section 9.1.
3. **`dgb_outgoing_tx` is not cleared by every wipe path.** Accepted. The marks have their own
   store with the v2 fingerprint and `commit()` writes. 7.2 is the table of every mark against
   every clearing path. `clearSyncData` now clears the records too.
4. **The rebuild erased every guard and kept the waiting send.** Accepted. 6.6: the rebuild
   writes a note for each send in flight before it clears, keeps the marks, and the waiting
   send opens with a notice.
5. **A send that confirmed but is still held without a height.** Accepted. 6.2, the third
   state: the endpoint's answer, already fetched, hides Cancel and Try again and offers Rescan.
   6.4 says what happens when Cancel is used without that answer, and how a rescan undoes it.
6. **"Tip below height reads 0" closed the gate for every rescan.** Accepted. 5.2: the count is
   taken against the highest verified tip, lowered only by a reorg, saved with the checkpoint.
7. **A stalled tip started neither clock.** Accepted. 6.2, the second state: 20 minutes with the
   app open and no new block at the tip.
8. **A crash between publish and the checkpoint lost the send.** Accepted. 5.6: register, save,
   record, then publish, for every kind. The native call is split.
9. **"Approving" matched by payee and amount.** Accepted. 5.3: the signed txid is written into
   the request before anything else, and the match is by txid.
10. **The notices missed the commonest case.** Accepted. 5.7, the same-address notice, with the
    wording and an acknowledgement before the PIN.
11. **The "refused" removal could remove a cancel.** Accepted. 6.4: a cancel is exempt, and a
    cancel removed by hand writes a mark for the original.
12. **"Cancel lost" depended on the sweep.** Accepted. 5.7: every notice is worked out when a
    review opens. Only "acknowledged" is stored.
13. **Arrival order does not always decide.** Accepted. Corrected in 1.7 and 6.4. The KAT has
    both orders, and the same after a checkpoint load.
14. **No stated write order for the cancel swap.** Accepted. 6.4: mark, swap, checkpoint, cancel
    txid and record, publish; and a table of what each start finds.
15. **Implicit change across a restart.** Accepted, and confirmed in source: the replay skips
    rows marked spent (`kt:asset/AssetManager.kt:2791`). 6.4: a rule that reads the funding
    transaction's own bytes and does not depend on the replay; a new predicate, not
    `BRWalletOutpointSelectable`; a Kotlin test arm.
16. **"History cleared" did not cover a send still unconfirmed.** Accepted. 5.7 and 6.6: an
    unsettled record, or a rebuild's note, for a txid the wallet does not hold raises a notice
    once the wallet is at the tip.
17. **The gate is defined by what a transaction spends.** Accepted. The table in 5.2 says so,
    and `send_gate_kat` has the arm for a sweep that spends a wallet coin.
18. **Fingerprint and scope details.** Accepted. 7.1: the v2 fingerprint; "cannot be read now"
    means do not show and do not delete; the scope is the wallet this process runs; the four
    extra tests are named in 8.4.
19. **Tests and rows to add.** Accepted. 6.6 has the four rows. 8.4 has the arms. The Cancel
    dialog lists the later sends it removes (6.4).
20. **Checked and found as the design says.** No change, except that 6.3 now says a cancel's
    txid goes to the endpoint through the existing check.

### 10.7 The fourth revision, and its check finding by finding

The fourth revision (commit `64935e2d7`) answered the 20 points above. An independent check of
it traced every Android path that signs or publishes and found the gate's placement right and
the cancel signable. It found 16 of the 20 points answered and 4 answered only in part
(points 2, 5, 14 and 16), and it raised eleven new findings. The numbers below are the
check's own.

The four points answered in part:

- **Point 2** (Cancel can be signed): the swap bypassed the publish-list purge, and the
  reservation was unstated. Now N1 and N7.
- **Point 5** (a send that confirmed but is held without a height): the stalled-tip answer was
  not kept. Now N4.
- **Point 14** (the cancel's write order): a failed save left the swap live in memory. Now
  N10.
- **Point 16** (history cleared with a send unconfirmed): the sweep settled the record first.
  Now N3.

The eleven findings:

- **N1. The cancel swap freed transactions the publish list still pointed at.** Accepted. The
  entry is now the peer manager's, and the swap runs under its lock with the snapshot and
  purge of the existing removal. 6.4 lists every holder of such a pointer. `cancel_send_kat`
  has the sanitizer arm.
- **N2. "History complete" would have opened at the header tip.** Accepted. One definition of
  "at the tip" (5.2): the scan frontier at the tip. The app sets the flag from it; the core
  never does; it is not set during the bridge's rescan after the first sync.
- **N3. The sweep settled a record the wallet did not hold before the notice could read it.**
  Accepted. 6.7: nothing is done with such a record while the history is not complete;
  afterwards it is "missing", not settled.
- **N4. The endpoint's answer for a send above a stalled tip is "refused", and was not kept.**
  Accepted. 6.2: the third state is keyed on the endpoint's answer, kept in the marks store,
  whatever the core says. It never opens the gate.
- **N5. A restore of the same phrase erased the marks, the records and the waiting send.**
  Accepted. 7.2: kept when the v2 fingerprint is unchanged. A restore that cannot keep them
  tells the user.
- **N6. Three gaps in the wording of G2 and G3.** Accepted. 5.2: both peer handlers register
  as an own send; G2 sits before the promote branch as well as the insert; the recovery
  signers are refused while the history is not complete; "before signing" is said only of the
  signer it is true of.
- **N7. Which reservation the cancel uses, its key and its clock.** Accepted. 6.4: the caller
  passes its own table and clock; the key is the signed txid, claimed after signing; the text
  says what the claim is not for.
- **N8. A send with 1 or 2 confirmations when a rescan starts had no row.** Accepted. 6.6 has
  the row, the message, and the line a manual rescan shows first.
- **N9. A wallet above the checkpoint limit could neither send nor cancel.** Accepted. 5.6
  sets out four choices. It is question 2 for the owner; A is recommended.
- **N10. "Publish nothing" after a failed save was not a state the wallet could stay in.**
  Accepted. 6.4: the cancel rolls forward. Its txid and record are written first, the save is
  retried, and it is published only when its mark says "saved".
- **N11. Small items.** Accepted. 5.3: "could not be found", not "did not go out". 5.6: the
  shared helper asks the one-deep rule itself, so no path skips it. 5.2 and 5.6: the coin
  reservation is released on a refusal and in the rollback.
