# TX-QUEUE: a send queue that starts one send at a time (4.1.6 design)

> **Status 2026-11-22: REVISED to the owner's decisions. Ready for his answers to section 8.**
>
> The owner decided three things on 2026-11-22. His words: *"we can queue as deep as we want, but
> we can't initiate until we have at least 3 confirmations on previous transaction. 2. wait only.
> 3. cancel this send. 4.1.6 works fine."*
>
> - **D1.** A real queue of any depth. The next send is not started (not put on the network)
>   until the previous one has at least 3 confirmations.
> - **D2.** When coins are waiting, the wallet waits. There is no "send anyway".
> - **D3.** "Cancel this send" is built in 4.1.6.
>
> These overrule the first draft, which recommended a rule in coin selection and advised against
> a queue. That draft's options and its independent critique are kept under History (sections 10
> and 11). The critique's confirmed errors are corrected in the text.

Design only. No production code.

Sources: `origin/develop` at `5bfcfe891`, core pinned at `8014392`. Paths under `core:` are the C
core (`native/src/main/jni/digibytewallet-core/`). Paths under `kt:` are
`core/src/main/java/app/aroundtheblock/wallet/core/`. Paths under `app:` are
`app/src/main/java/app/aroundtheblock/wallet/`. Paths under `jni:` are
`native/src/main/jni/bridge/`.

## 0. The short version

- The user may line up as many sends as they like. Each one is reviewed and approved with the
  PIN or biometric **when it is added**, exactly as a send is approved today.
- The wallet signs it then and **holds it on the device**. It puts it on the network only when
  the send before it has 3 confirmations that the wallet's own sync has proven.
- The rule covers every send from this wallet: DGB, DigiDollar and DigiAsset. One send is on the
  network at a time. On DigiByte, 3 confirmations is about 45 seconds.
- Nothing is ever signed without the user present. Releasing a held send needs no key, so it
  works while the app is locked.
- A queued send that can no longer go out as signed (an earlier one was cancelled, its coins
  were spent elsewhere, the queue sat idle too long) is **never sent silently**. It stays in the
  list as "Needs your approval" and asks for the PIN again.
- A send that has not confirmed is shown as such, and only when the wallet is at the chain tip.
  The user can try again or **cancel it**. Cancel is a small payment to yourself that spends the
  same coins. If it confirms, the stuck send can never confirm.
- There is no automatic release that asks a server. Cancel replaces it.
- This is the largest item in 4.1.6: four core changes and about eight app changes. Section 7
  has the order.

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
- Off by one, confirmed by the critique (point 12): DigiByte Core refuses a transaction when
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
- These paths do not build trains. They need no change.

### 1.6 Two sends at the same instant

- A signed send claims its inputs for 60 s until it is registered
  (`core:BRCoinReservation.h:4-9`, `:55-58`). The signer also checks the inputs are still unspent
  and still selectable (`core:BRSpendGate.h:10-19`, `core:BRPreparedSend.h:27-37`).
- This stops a double spend. It does not stop a chain.

### 1.7 Three facts the queue design rests on

- **A server-reported height is stored like a proven one.** `BRWalletUpdateTransactionsUnproven`
  writes the height a server reported into the same `blockHeight` field
  (`core:BRWallet.c:2757-2765`, `:3034-3042`). Selection then reads the coin as confirmed
  (`:1782`). Nothing in the wallet says today which heights it proved itself. A later proven
  update with the same height and time is skipped (`:3033`), so no trace of the proof is left.
- **Locking the app zeroes the seed and nothing else.** The wallet object and the peer manager
  stay loaded (`jni:jni_wallet.c:474-480`, `kt:WalletManager.kt:598-602`). Publishing a signed
  transaction needs no seed.
- **A confirmed transaction always sorts before an unconfirmed one.** The wallet orders by
  height, and "unconfirmed" is the greatest height (`core:BRWallet.c:161-162`, insert at `:224`).
  Of two conflicting transactions, the earlier in that order is valid and the later is invalid
  (`:417-431`). So the chain, not arrival order, decides between a confirmed transaction and an
  unconfirmed one. Between two unconfirmed ones, arrival order decides.

## 2. What happens after broadcast

- The send is registered in the wallet before it is published, so its coins are spent at once
  (`core:BRWalletSend.h:14-15`, `:36-39`; `kt:TransactionBuilder.kt:325-328`).
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
4. **Orphan or dead.** Same manual pass (`kt:WalletManager.kt:762-799`).

- Removing a transaction removes everything built on it (`core:BRWallet.c:2768-2803`). So
  releasing the root of a train releases the whole train.
- The core's own at-tip cleanup removes an unrelayed unconfirmed **receive**. It never removes a
  send, because the wallet has a stake in it (`core:BRPeerManager.c:1266-1380`, the stake rule at
  `:1340-1362`).
- A rescan moves the tip back and fetches the chain again. It does not remove unconfirmed
  sends. A confirmed one gets its height back. A rebuild from chain discards the local
  transaction cache, so an unconfirmed send is gone from the wallet after it
  (`kt:WalletManager.kt:838-859`).
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

No common wallet runs a background queue that signs later. This design does not sign later
either: it signs when the user approves and sends later (section 5.1).
## 5. The design

One design. Its parts are numbered so the build plan can name them.

### 5.1 What a queued send is

Two readings were worked through.

**Reading (i): approve and sign when it is queued, hold the signed transaction, release it
later.**

- The user reviews the send and gives the PIN or biometric, as today
  (`app:ui/components/SpendAuth.kt:131-142`, `app:ui/wallet/SendViewModel.kt:343`).
- The wallet signs those exact bytes then (`kt:TransactionBuilder.kt:302-309`). Today the next
  line publishes them (`:311`). In the queue, the wallet keeps them and publishes them when the
  gate opens.
- Releasing needs no key and no prompt. It is the same act as the re-publish the stranded-send
  sweep already does without a prompt, for hours (`app:service/SyncService.kt:3715-3860`).
- What leaves the device is exactly what the user approved: that payee, that amount, that fee,
  those coins. Nothing is rebuilt later.

**Reading (ii): store only the request, and ask for the PIN again when its turn comes.**

- Nothing is signed until the user is there again. That is the strictest reading of the PIN
  rule.
- But a queued send then cannot leave without the user. With a queue of five the user must
  come back and approve five times, about a minute apart. That is a reminder list, not a queue.
- The fee and the coins can differ from what the user saw when they queued it.

**Which keeps the standing rule.** The rule is: every spend needs the in-app PIN or biometric at
the moment of action, and nothing signs without it. Both readings keep "nothing signs without
it". They differ on what "the moment of action" is. In (i) it is the moment the user adds the
send to the queue, which is the moment the user acts. In (ii) it is the moment the send leaves.

**Recommendation: reading (i), with reading (ii) as the fallback state.**

- A queued send is a signed transaction held on the device. This is what makes D1 a real queue.
- Whenever a held send can no longer go out exactly as signed, it becomes a stored request
  ("Needs your approval") and takes a fresh review and PIN. It is never rebuilt or re-signed
  without the user. Section 5.6 lists the cases.
- One extra guard, because a held send leaves later than it was approved: if the wallet could
  not work on the queue for a while (app killed, phone off), it does not resume on its own. It
  asks once, with the PIN (section 5.5, "pause"). How long "a while" is, is owner question 2.

**What the user sees.** On the confirmation screen, before the PIN: "This send will wait in your
queue. It goes out when your earlier send has 3 confirmations (about 1 minute)." After the PIN:
"Queued. 2nd in line." The row shows in the activity list as "Queued".

### 5.2 Which coins a queued send is built on

The queue is a **chain signed in advance, released one link at a time**.

- A held send is registered in the wallet when it is signed, like any send
  (`core:BRWalletSend.h:14-15`). Its inputs are spent at once. Its change exists at once. The
  balance and "Max" are right without any new accounting.
- The next send is built by the normal selection: confirmed coins first, then the wallet's own
  unconfirmed change (`core:BRWallet.c:1780-1784`, `:1917-1936`). So a queued send uses free
  confirmed coins when the wallet has them, and the change of an earlier queued send when it
  does not. A wallet whose whole balance is one coin can still queue ten payments.
- Reserving separate confirmed coins for each queued send was the other option. It fails for
  the common wallet, which has one coin after every send. It is not used.
- Each queue entry records which earlier entries it spends from. That is read from its inputs.
- By the time a link is released, the link before it has 3 confirmations. So a released send
  never has an unconfirmed ancestor. The node limits in section 3 are never approached, and
  causes 1, 5 and 6 of section 3 (an orphan child) cannot happen to a queued send.
- The walk that finds "own unconfirmed change" stops at 25 generations
  (`core:BRWallet.c:1783`). So at most 24 links can be signed ahead on one chain of change.
  A send queued beyond that is stored as a request and approved at its turn (owner question 3).
- Old-style inputs (a restored bread-wallet seed) can in theory have their txid altered before
  confirmation. A link built on such a parent would then be invalid. It is caught by the same
  check as every other broken link (section 5.6).

**What "held" needs in the core.** A held send must stay off the network until released. Today
three paths would leak or publish it:

1. On every connect the peer manager asks peers about each unconfirmed wallet transaction by
   txid (`core:BRPeerManager.c:1422-1445`). A held send must be left out, as a stemmed one is.
2. Publishing a transaction also lists its wallet parents for serving
   (`core:BRPeerManager.c:997-1001`). A held parent must not be listed. Under D1 a released send
   has no unconfirmed parent, so this is a guard, not a normal path.
3. The stranded-send sweep republishes every recorded unconfirmed send. A held send is not
   written to `OutgoingTxStore` until it is released, so the sweep never sees it.

The core gets a per-wallet "held" set with three calls: register held, release, is held. The
queue file is the source of truth. The held set is rebuilt from it at wallet load, before the
peer manager connects. If the mark is ever missing, the worst case is path 1: a txid is asked
about early. No held send is published by a missing mark, because nothing publishes it.

### 5.3 The gate: "3 confirmations of the previous transaction"

**The rule.** A send may start only when **no send from this wallet that is already on the
network has fewer than 3 proven confirmations**. Otherwise it is queued.

- "A send from this wallet" is any valid transaction that spends a wallet coin and is not held.
  A send made on another device with the same recovery phrase counts too, once this wallet sees
  it.
- The owner's words say "the previous transaction". This is read as **any new send**, not only
  a send that spends the previous send's change. So a wallet with many unrelated confirmed
  coins also sends one payment at a time, about one a minute. Paying twenty people takes about
  twenty minutes. The answer to that is a pay-many screen (one transaction, many payees), which
  is History option C and a later feature.
- A send with nothing ahead of it starts at once, as today. Most sends never see the queue.
- Receiving is not affected. An incoming payment never closes the gate.

**Proven by the wallet's own sync only.** A confirmation counts when the wallet itself matched
the transaction to a block on its verified header chain. A height a server reported does not
count. This was the first draft's Q6 and the recommendation stands. A server must not decide
when the wallet spends.

- The core cannot tell the two apart today (section 1.7). It needs one new bit per transaction,
  "height proven", set by the proven update path only, and set even when the height was already
  there from a server. And one accessor: proven confirmations of a transaction
  (`tip - height + 1`, or 0).
- Cost: when sync is wedged, the queue does not move. That is D2. The user is told the wallet
  is catching up, not that anything failed.

**Reorgs.**

- The previous send drops below 3 confirmations before the next was released: the gate closes
  again and the next waits. The core already marks transactions unconfirmed after a reorg
  (`core:BRWallet.c:3084-3105`), and that clears the proven bit.
- The previous send is reorganised out after the next was released: both are valid and both
  are on the network. The sweep republishes them, parent first (section 6.1). This needs a
  reorg at least 3 deep and is rare.
- 3 is the owner's number. It is one constant in one core header.

### 5.4 Where the queue lives

- **One file per wallet and network.** Base name `send_queue`, added to
  `WalletStores.WALLET_AND_NETWORK_FILES` (`kt:wallets/WalletStores.kt:16-17`). The name is
  resolved by `WalletStores.fileName`, so the legacy wallet gets `send_queue.bin` and
  `send_queue_testnet.bin`, and wallet `<id>` gets `send_queue_<id>.bin` and
  `send_queue_<id>_testnet.bin`. Written through `.bin.tmp` and a rename, like its neighbours.
- It is a file, not SharedPreferences. Signed transactions as hex strings in a prefs map would
  be pinned in memory, which is the leak `dgb_sync_data` had.
- The eraser reads the same list, so the **full wipe** erases every wallet's queue on both
  networks, and **removing a wallet** erases that wallet's. Both get a test that fails if the
  name is missing from the list.
- The app is not backed up (`app/src/main/AndroidManifest.xml:22-24`), so the file does not
  leave the device.
- **What an entry holds.** Its place in line. Its kind (DGB, DigiDollar, asset). The request:
  payee, amount, asset id. The signed bytes and their txid, when it is held. Its state. The
  entries it spends from. When it was approved.
- **Tampering.** Signed bytes cannot be changed without breaking the signature, and the release
  step re-reads payee and amount from the bytes, never from the stored fields. A stored request
  has no signature, but it is never sent as stored: the user sees it in full on the review
  screen and approves it with the PIN.
- **A restore** starts with an empty queue. The queue is not part of the recovery phrase. A
  held send that was never released does not exist anywhere else.

**What survives what.**

| Event | The queue |
|---|---|
| Auto-lock, app in background | Keeps moving. Release needs no key (section 1.7). |
| Process death, reboot | Kept on disk. Held sends are registered again at load. Resumes, unless the pause rule applies. |
| App update | Kept. The file has a version number. An entry the new version cannot read is shown as "Needs your approval", never dropped. |
| Rescan | Kept. A rescan does not remove unconfirmed sends. |
| Rebuild from chain | The wallet's local transactions are discarded (`kt:WalletManager.kt:838-859`). Held sends are registered again from the file. One whose coins are gone becomes "Needs your approval". |
| Wallet switch | Kept, and stopped. Only the active wallet syncs today (multi-wallet step 1). Before switching, the wallet says: "3 sends are still waiting in this wallet. They will not go out until you switch back." |
| Wallet removal, full wipe | Erased. |

### 5.5 Who releases the next send

One function, `releaseNext`, with one lock so two callers cannot release at once. It:

1. Stops if the wallet is not at the chain tip, or the pause rule applies.
2. Asks the core whether the gate is open (section 5.3).
3. Takes the first entry in line. Checks it can still go out as signed (section 5.6).
4. Writes "releasing" to the file, records it in `OutgoingTxStore`, clears its held mark, and
   publishes it through `Broadcaster.broadcast`. Publishing the same bytes twice is harmless, so
   a crash between any two of these steps is safe.

Its callers:

- **`SyncService`**, on every transaction update from the core and on the 90-second sweep tick.
  This is the normal path while the app is open, locked or not.
- **The app coming to the foreground.**
- **`SyncWorker`**, in its 30-second window every 15 minutes
  (`app:service/SyncWorker.kt:27-28`, `:181`). It can release at most one send per run. So with
  the app in the background and the service gone, the queue moves at most one send per quarter
  hour.

**Staying alive while there is work.** `SyncService` drops its foreground notification after
sync. While the queue has entries that can be released, it keeps the foreground notification:
"Sending queued payments: 2 of 5". It is a `dataSync` service already
(`app/src/main/AndroidManifest.xml:37`). This is owner question 4. On phones that freeze
background apps hard (the wedge class (c) in CLAUDE.md), the queue can still stall in the
background. It continues when the app is opened. The wording never promises a time.

**The pause rule.** The queue file keeps the time `releaseNext` last ran at the tip. If more
than the pause limit has passed since then, the queue is paused. The wallet shows "Your queue
was paused. 3 sends are waiting." with one button, "Resume", behind the PIN or biometric. One
prompt resumes the whole queue. So a phone that was off for a day does not start paying people
the moment it is opened. Recommended limit: 30 minutes (owner question 2).

### 5.6 A queued send that cannot go out as signed

Before a release the wallet asks the core: is this transaction still valid, are all its inputs
still unspent by anything else, and does every input have at least one proven confirmation?
If not, the entry becomes **"Needs your approval"**. Its request is kept. Its signed bytes are
discarded and the core removes it. The causes:

| Cause | What happens |
|---|---|
| An earlier queued send was removed by the user | Every held send that spends its change is removed with it by the core (`core:BRWallet.c:2768-2803`). Each becomes "Needs your approval". Sends that do not depend on it stay signed. |
| An earlier started send was cancelled (section 6.4) | The same. |
| An earlier send was reorganised out and did not return | The same, found by the check above. |
| Its coins were spent on another device | The same. |
| A parent confirmed under a different txid | The same. |
| It was queued past the 24-link limit | It was stored as a request from the start. |
| The queue was paused | Nothing is discarded. The signed sends resume after one PIN. |

"The previous send's change differs" cannot happen while the bytes are held: a signed
transaction's outputs are fixed. If the parent is replaced by anything else, the child is
invalid and lands in the table above.

When the user opens a "Needs your approval" entry, it goes through the normal review. If the
balance no longer covers it, the review says so in plain words: "You no longer have enough for
this send. 120 DGB short." The user can change the amount or remove it. The queue does not wait
for it: later entries that do not depend on it carry on.

### 5.7 The same rule for the other send types

**DigiDollar.**

- The gate applies. A DigiDollar send is a send from this wallet.
- Holding one needs a bridge change: today one native call builds, signs and publishes
  (`jni:jni_transaction.c:1121`, `kt:TransactionBuilder.kt:380`). It is split into "build
  and sign, register held" and "release".
- A DigiDollar send is always pending in the core, so none of its outputs can be spent until it
  confirms (section 1.3). A second DigiDollar send queued behind it usually needs that change.
  It then cannot be signed ahead. It is stored as a request and approved at its turn.
- The $1.00 minimum per output, change included, and the 0.1 DGB fee are untouched. They are
  checked when the send is built, as today.

**DigiAssets.**

- The gate applies.
- Asset sends already sign and publish in two steps (`kt:asset/AssetManager.kt:3266-3283`), so
  holding one is a Kotlin change.
- The transfer-rule gate runs at signing, as today: only `TransferRuleState.NONE` is signed
  (`kt:asset/AssetManager.kt:3129-3131`). It is not loosened and not skipped. An entry that
  needs approval again goes through the whole gate again.
- Implicit change to the last output is inside the signed bytes, and held bytes never change.
- A plain queued send can never take an asset coin. Selection leaves asset-held outputs out
  (`core:BRWallet.c:1813-1814`, `:1832-1855`), and a held asset send is pending, so its outputs
  are in no coin list at all.
- Unconfirmed asset change is not a holding (`kt:asset/AssetManager.kt:1761-1783`). A second
  transfer of the same asset queued behind the first is stored as a request.
- When a held asset send is removed without ever being sent, its rows are cleaned the way a
  dead send's are (`clearDeadAssetSend`, `app:service/SyncService.kt:3808-3817`). If it is
  approved again, the abandoned attempt reads `CONFLICTED` and is not counted twice.

**Sweep and recovery.**

- A legacy sweep spends coins that are not wallet coins and pays the wallet. It is not a send
  from this wallet. It is not queued and it does not close the gate. The swept coins cannot be
  spent until the sweep confirms, as today (section 1.5).
- The asset recovery move already waits for its own split to confirm
  (`kt:recovery/ForeignAssetTransferService.kt:117-122`). It is left as it is. Whether its
  inputs are ever wallet coins was not traced (section 9).
- These flows keep their own PIN gates.

**Digi-ID.** Not a transaction. Never queued. Never blocked by the queue.

**Dandelion.**

- A release goes through `Broadcaster.broadcast` like any send, stem or flood by the user's
  setting.
- Cause 5 of section 3 (parent and child stemmed to different peers) cannot happen. The child
  is released when the parent is 3 blocks deep.
- A stem that dies with the process is recovered by the sweep, as today.

### 5.8 What the user sees

All text below ships in all 13 languages in the same commit as the screen that shows it. The
wording is a proposal. Translations are machine-assisted and need the usual note.

**Queueing a send.** The Send screen works as today. When the gate is closed, the review screen
adds one line and the button changes:

- Line: "Your earlier send is still confirming. This send will wait in your queue and go out
  after it (about 1 minute)."
- Button: "Add to queue" instead of "Send".
- After the PIN: "Queued. 2nd in line."

**The Send screen while something is waiting.** A banner at the top: "1 send confirming, 2
waiting." Tapping it opens the queue. The balance and Max already leave out everything in the
queue. There is no "send anyway" (D2).

**The queue list.** One screen, reached from the banner and from the wallet screen.

- First row: the send that is on the network. "Confirming: 1 of 3."
- Then each queued send in order: payee, amount, and its state. "Queued", "Needs your
  approval", or "Paused".
- **Remove.** Any queued send can be removed before it starts. No fee and nothing goes on the
  network. The dialog says what else is affected: "Removing this also returns 2 later sends for
  approval, because they were built on it." No PIN is asked: removing spends nothing (owner
  question 6).
- **Reorder.** Not in 4.1.6. The signed sends are tied to their order. Moving one would return
  every send after it for approval. Remove and add again does the same job (owner question 5).

**Notifications.** No payee and no amount in any notification text.

- While the queue is working: the ongoing service notification, "Sending queued payments: 2 of
  5".
- When the user is needed: "A queued send needs your approval." Also for a paused queue and for
  a stuck send (section 6.2). Once per event, not repeated.
- When the queue is empty: "All queued sends are confirmed." (owner question 7).

**The activity list.** A held send is a row marked "Queued". It turns into the normal pending
row when it is released.

## 6. A send that does not confirm, and "Cancel this send"

A queue waits for ever behind a first send that never reached a node. So this half is required,
not optional.

### 6.1 Republish parents first

- Today the sweep works in txid order, and each send has its own schedule: three sweeps in a
  row, then once an hour (`app:service/StrandedSendSelector.kt:108`, `:116`). A parent on its
  hourly hold is not in the list when its child is retried (critique point 7).
- New rule: **republishing a send republishes its unconfirmed wallet ancestors first, in the
  same sweep, whatever their own schedule says.** The 15-second wait after each publish
  (`app:service/SyncService.kt:3851`) is taken once for the group, not once per transaction.
- The rule goes in `core:BRStrandedSend.h` so iOS follows it.
- Under D1 a new send has no unconfirmed parent. This rule is for what is left: trains made by
  older versions, a second device, and a reorg.
- The one-line walk fix from critique point 12 (25 to 24, section 1.1) ships with it.

### 6.2 Say so, and only when it is true

- A send is shown as **"Not confirmed yet"** when all of these hold: the wallet is at the chain
  tip; at least 20 blocks (about 5 minutes) have passed at the tip since the send was started
  or since this wallet reached the tip, whichever is later; and it has no confirmation.
- It does not use the publish verdict. The verdict is lost on restart and proves little
  (section 2). It uses the chain, which the wallet verifies itself. This answers critique point
  8: a wallet that is behind the tip says "Catching up", never "not sent".
- The words do not deny the payment: "This send has not confirmed yet. It may not have reached
  the network." Not "Not sent".
- The row and the queue list offer three actions:
  - **Try again.** Republish now, parents first.
  - **Cancel this send** (section 6.4).
  - **Check with the network.** The existing manual check on the reconcile screen. Shown only
    when the default endpoint is in use, since the check answers only there.
- The queue list says why the rest is waiting: "Your later sends are waiting for this one."
- One notification, once.

### 6.3 No automatic release in 4.1.6

The first draft proposed running the stale-send rule from the sweep, so a send the pinned node
does not know is removed on its own after a day. This design does **not** do that.

- It would send a stuck send's txid and inputs to the default endpoint with no user action.
  Today that happens only when the user opens the reconcile screen. It is a change to the data
  path on a sovereignty-first roadmap (critique point 6).
- It does nothing for a user with a custom endpoint, and its clock may never start for the
  sends it is meant for (critique point 5; section 2.2 item 3).
- One server's answer would decide when coins come back, with nobody watching.
- Cancel gives the exit that needs no server.

The manual check stays where it is and is linked from the stuck send (section 6.2). The cost of
this choice: a user who never opens the app is never unstuck automatically. With D2 that is the
intended behaviour. This is owner question 1.

### 6.4 Cancel this send

**What it is.** A payment from the wallet to itself that spends the same coins as the stuck
send. Only one of the two can ever confirm. If the cancel confirms, the stuck send is dead by
the chain's own rule.

**When it is offered.** On a send in the "Not confirmed yet" state, and only on the **oldest**
unconfirmed send of a chain. Cancelling it cancels everything built on it. It is not offered on
a send that is merely seconds old.

**What it cannot do.** If a node already accepted the original, nodes refuse the cancel:
replacement is off on DigiByte (section 3). Then the original confirms and the cancel dies. The
dialog says so before the PIN: "Cancelling works only if the network has not accepted this
send. If it already has, the send will confirm and the cancel does nothing. The cancel costs a
network fee." The fee itself is shown as a figure, from the built transaction.

**How it works in the core.** Critique point 9 showed the first draft's version cannot be
published: a transaction that spends what an earlier unconfirmed one spends is ordered second,
marked invalid (`core:BRWallet.c:417-431`), and refused by the peer manager
(`core:BRPeerManager.c:5133-5140`). The critique proposed changing the conflict order. This
design does something smaller and leaves the order alone:

1. **Plan.** A new core call takes the stuck send's txid. It lists the inputs the cancel may
   spend. An input qualifies only if all of these hold: the wallet owns the output; the output's
   transaction has a proven confirmation; the output is not asset-held, not ONE-FAMILY-held,
   not a DigiDollar output, and is signable; and asset detection has run to completion, the
   same preflight a review runs before it builds anything (`SpendPreflight.completed`,
   `kt:TransactionBuilder.kt:349-351`). These are
   the conditions of normal selection (`core:BRWallet.c:1812-1818`), asked of an outpoint the
   wallet currently holds as spent. **An input that cannot be classified is refused.** A wrong
   "plain" would destroy an asset. For a DigiDollar or asset send this leaves only its DGB fee
   coins. If no input qualifies, the send cannot be cancelled and the screen says so.
2. **Build.** Those inputs, one output to a new change address of this wallet, the default fee
   rate. If what is left after the fee is below the dust limit, cancel is refused.
3. **Approve and sign.** The user sees the fee and what comes back, and gives the PIN or
   biometric. Cancel signs and pays a fee, so it is a spend and takes the same gate as a send.
4. **Swap.** One core call, under the wallet lock where it can be: check that the cancel spends
   only inputs of the named send and pays only this wallet's addresses; remove the named send
   and everything built on it; register the cancel. If the cancel is not accepted, the original
   is registered again. After this the cancel is an ordinary valid send. The peer manager
   serves it and the sweep republishes it with no special case.
5. **Publish** by the user's Dandelion setting.

**What decides the outcome.** The chain (section 1.7).

- The cancel confirms. The original can never confirm. Its coins are back, less one fee.
- The original confirms after all. It arrives in a block, sorts before the unconfirmed cancel,
  and the cancel becomes invalid. The existing ten-minute invalid hold removes it
  (`app:service/SyncService.kt:3790-3824`). The send shows as confirmed, with a note: "This send
  had already been accepted. It was not cancelled."
- A peer relays the original unconfirmed. It is ordered after the cancel and marked invalid in
  this wallet. Nothing is published for it. The chain still decides.

**Kotlin side.**

- The original's record in `OutgoingTxStore` is marked "cancel requested" and leaves the
  sweep's working set. The sweep must never republish a send the user cancelled. The record is
  kept so the row comes back with its payee if the original confirms.
- An asset send's rows are cleaned as for a dead send. A re-send reads the old attempt as
  `CONFLICTED`.

**What cancelling does to the queue.**

- Held sends built on the cancelled send's change are removed with it and become "Needs your
  approval" (section 5.6). Held sends built on other coins stay.
- The cancel is now "the previous transaction". Nothing else starts until **the cancel** has 3
  proven confirmations.
- This is what makes cancel safe against paying twice. The user cannot pay the same person
  again until the cancel is 3 blocks deep, and by then the original is dead.

**Removing a queued send is not this.** A send that never started is removed for free (section
5.8). Cancel is only for a send that is already on the network, or was meant to be.

## 7. Build plan

### 7.1 Core and Kotlin

**In the shared core** (so iOS gets the same rules):

- `BRSendQueue.h`, header-only policy. The number 3. The gate rule. The pre-release check. The
  pause rule. The "Not confirmed yet" rule (20 blocks at the tip). The entry states and which
  state follows which event.
- `BRWallet`: the "height proven" bit and the proven-confirmations accessor. The held set
  (register held, release, is held). "Is the gate open" as one call under the wallet lock.
- `BRPeerManager`: leave held sends out of the unrelayed-transaction request and out of the
  parents listed for serving.
- `BRWallet`: the cancel plan and the cancel swap.
- `BRStrandedSend.h`: ancestors first. `BRWallet.c:1783`: 25 becomes 24.

**In the Android bridge and Kotlin:**

- JNI for the calls above. The table generator runs with `--check` in CI
  (`docs/development-process.md` rule 6).
- `SendQueueStore` (the file), the `WalletStores` list entry, eraser and removal tests.
- The queue engine: enqueue in place of publish when the gate is closed, `releaseNext`, load at
  start, the pause rule, the wallet-switch warning.
- The DigiDollar call split in two. The asset send's hold step.
- `SyncService`, `SyncWorker` and foreground callers. The foreground notification.
- Screens: review line and button, banner, queue list, activity rows, stuck state, cancel
  dialog. Strings in 13 languages. Each new screen added to `COVERED` in
  `OnboardingHardcodedStringTest`.

### 7.2 Pull requests, in order

Every one that touches what is signed, held, released, cancelled or erased is **owner-gated**:
the owner reviews it before merge, whatever the independent review says. Only the last is not.

| # | Repo | What | Owner-gated |
|---|---|---|---|
| 1 | core | `BRSendQueue.h` policy and its KAT | Yes |
| 2 | core | Proven bit, proven confirmations, held set, gate call, peer manager exclusions | Yes |
| 3 | app | Core pin, JNI, `SendQueueStore`, `WalletStores` entry, wipe and removal coverage | Yes |
| 4 | app | Queue engine for plain DGB sends, `releaseNext` and its callers, pause rule | Yes |
| 5 | app | Send screen, queue list, activity rows, strings in 13 languages | Yes (it changes what "Send" does) |
| 6 | core, then app | Ancestors-first republish, the 25 to 24 fix, "Not confirmed yet" state | Yes |
| 7 | core | Cancel plan and cancel swap, with KAT | Yes |
| 8 | app | Cancel this send: flow, dialog, PIN gate, store marking, asset cleanup | Yes |
| 9 | core bridge, then app | DigiDollar and DigiAsset sends in the queue | Yes |
| 10 | app | Notifications and the foreground service while the queue works | No (independent review and green CI) |

After PR 5 the plain DGB queue works end to end. PRs 6 to 8 must be in the same release: a
queue without the stuck half can stall for ever. PR 9 can follow them. Until PR 9, a DigiDollar
or asset send while the gate is closed is refused with "Wait for your earlier send to confirm",
never sent early. Core lands on its `develop` before each app pin moves.

### 7.3 Tests

Every new gate is seen red on the unfixed code first, and the PR says how.

**Host KATs (core).**

- `send_queue_policy_kat`: gate open and closed at 0, 1, 2, 3 confirmations; a server-reported
  height does not open it; a reorg closes it again; held sends do not close it; the pause rule;
  the 20-block rule. Red arms: 2 confirmations accepted; an unproven height accepted.
- `held_send_kat`: a held send's coins are spent; its change is selectable for the next link;
  it is not in the unrelayed request; it is not listed as a parent; release clears the mark;
  removing a link removes its dependants and no others; the 24-link limit. Red arm: held mark
  ignored by the peer manager.
- `cancel_send_kat`: a cancel of a plain send; of an asset send (fee coin only); of a
  DigiDollar send; refused when an input is asset-held, unclassified or unconfirmed; refused
  when an output is not the wallet's; the swap leaves a valid, publishable cancel; the original
  arriving confirmed makes the cancel invalid; the cancel confirming keeps the original out.
  Red arms: an asset input accepted; a non-wallet output accepted.
- `stranded_send_kat` extended: ancestors first. Red arm: txid order.
- Existing selection, prepared-send, coin-reservation and stale-send KATs stay green.

**Unit tests (Kotlin).**

- `SendQueueStore`: round trip, torn write, unknown version, a stored field that disagrees with
  the bytes.
- Engine: enqueue, release order, single release under two callers, crash between each pair of
  release steps, every row of the table in 5.6, pause and resume.
- Wipe and wallet removal erase the queue for every network; fails if the name is unlisted.
- `OutgoingTxStore`: a cancelled send is never selected for republish.
- View models; `LocaleResourceParityTest`; `OnboardingHardcodedStringTest`.
- Before each commit: the full core and app unit suites with `--continue`.

**Device sends (Note 8 mule, small amounts between its own addresses).** About 40 sends.

1. Five sends queued as fast as the screen allows from a one-coin wallet. Expected: never more
   than one unconfirmed on the network; each starts after the one before has 3 confirmations;
   all confirm.
2. The same with Dandelion on.
3. Queue three, lock the app. Expected: the queue finishes.
4. Queue three, force-stop, reopen after 2 minutes and after 40 minutes. Expected: resumes; and
   paused with one PIN.
5. Queue three, remove the second. Expected: the third asks for approval.
6. Network cut right after the first send; wait for "Not confirmed yet"; Try again with the
   network back.
7. Cancel a send that never reached a node (network cut at send, then restored for the
   cancel). Expected: the cancel confirms; the original never does; the queue continues after
   the cancel has 3 confirmations.
8. Cancel a send that did reach the network. Expected: the original confirms; the cancel dies.
9. A DigiDollar send and an asset send behind a DGB send. Expected: each waits its turn. One of
   the test assets must be an unruled one; a rule-bound one must still be refused.
10. Switch wallet with a queue waiting. Expected: the warning, and nothing sent until back.

The pre-publish suite sends no coins, so it cannot cover this. It gets one scenario check at
most: the queue screen opens empty.

### 7.4 What iOS gets

From the shared core, with the pin: the gate rule and its number, the proven-confirmation
accessor, the held set, the peer manager exclusions, the pre-release check, the pause rule, the
cancel plan and swap, ancestors-first republish, and all the KATs.

iOS still has to build: its own queue file and wipe coverage, its own release loop, the screens
and strings. iOS gives a background app far less time than Android. There the queue will move
only while the app is open, and the wording must say so.

## 8. Questions for the owner

D1 to D3 answered the first draft's questions 1, 2, 3, 5 and 6. These are what is left.

1. **Automatic release of a never-confirmed send by asking the default node: yes or no?**
   *Recommended: no in 4.1.6.* Cancel is the exit. The manual check stays on the reconcile
   screen and is linked from the stuck send. Yes would mean the default endpoint learns of stuck
   sends with no user action (section 6.3).
2. **How long may the queue sit unworked before it pauses and asks for the PIN to resume?**
   *Recommended: 30 minutes.* Shorter is safer and asks more often. "Never pause" means a phone
   switched on after a week sends what was queued a week ago.
3. **Is it acceptable that only the first 24 queued sends on one chain of change are signed
   ahead, and later ones ask for approval at their turn?** *Recommended: yes.* The alternative
   is a core change to the selection walk for a case few users will meet.
4. **Keep the foreground notification ("Sending queued payments: 2 of 5") while the queue is
   working?** *Recommended: yes.* Without it Android may stop the service and the queue moves
   one send per 15 minutes at best.
5. **Reorder queued sends?** *Recommended: not in 4.1.6. Remove only.* Reordering returns every
   later send for approval.
6. **Ask for the PIN to remove a queued send that has not started?** *Recommended: no.* It
   spends nothing. A confirm dialog is enough. Cancel (which signs) always asks.
7. **A notification when the queue has finished?** *Recommended: yes, one line, no amounts.*
8. **When is "Not confirmed yet" shown?** *Recommended: after 20 blocks at the tip (about 5
   minutes).* Cancel is offered from the same moment.
9. **DigiDollar and DigiAsset sends in the queue in 4.1.6, or one release later?**
   *Recommended: in 4.1.6 if PR 9 is ready; otherwise they wait with a clear message and are
   never sent early.* The rule (D1) applies to them from the first PR either way.
10. **Does a send made on another device with the same phrase close the gate here?**
    *Recommended: yes.* It is a send from this wallet. The cost is that this device waits for a
    send it did not make.
11. **iOS at the same time?** *Recommended: the core rules together, with the pin. The iOS
    queue screens when that work is scheduled.*
12. **Budget for device test sends on the Note 8.** *Recommended: approve 40 sends of small
    amounts between the mule's own addresses, plus one DigiDollar and two asset sends.*

## 9. Not verified

- Nothing was built, run or sent. No KAT, no unit test, no device, no emulator. Every statement
  about the code is from reading the cited lines.
- That a confirmed original displaces an unconfirmed cancel was read from the ordering code
  (`core:BRWallet.c:161-162`, `:224`, `:417-431`). It was not run. `cancel_send_kat` must show
  it before cancel is relied on.
- That a later proven update leaves no trace over a server-reported height (`:3033`) was read,
  not run.
- Whether asset detection can classify an outpoint the wallet holds as spent, which the cancel
  plan needs. `BRWalletUtxoIsAsset` is asked of unspent outputs today.
- Whether the asset recovery move ever spends wallet coins. If it does, it must take the gate.
- Whether the stale rule's clock starts for a send that went nowhere (critique point 5b). Not
  needed by this design, since the rule stays manual.
- Section 4 is from memory.
- The 8.22.2 policy column.
- The September note calls the home node blocksonly. Today it reports `localrelay: true`. One
  of the two is out of date.
- The 13 translations. None exist yet. Cancel and queue wording need a native reader before
  they are called done.

## 10. History: the options the first draft weighed

The first draft (2026-11-22, earlier the same day) set out three designs and recommended the
first. The owner's decisions replaced that recommendation. **Design A, the chain gate,** changed
coin selection so the wallet's own unconfirmed change could be spent only when a peer had taken
the parent and the chain was at most one deep; the critique showed its evidence rule proved
nothing and could hold a whole balance after a restart. **Design B, a queue,** came in two
shapes: B1 signed later, which cannot honour the PIN rule without the user present, and B2
signed at approval and held the bytes, which the draft advised against because a frozen app
would leave a "queued" send unsent. The design above is B2, with the pieces that answer that
objection: the pause rule, the foreground notification, wording that never promises a time, and
a fallback to a fresh approval. **Design C, batching,** puts several payees in one transaction;
it removes the chain for a user who plans ahead and does nothing for one who sends twice. It is
still a good later feature and is the real answer to "one payment a minute". The recovery items
R1 to R4 became sections 6.1 to 6.4; R3 (automatic release) was dropped.

## 11. History: critique of the first draft

The independent critique is kept below as written. Its section and design names refer to the
first draft. First, how this revision answers each point:

1. **"Taken by a peer" proves nothing.** Accepted. Corrected in section 2. No rule in this
   design uses relay evidence; the gate uses proven confirmations.
2. **`txRequests` misread.** Accepted. Corrected in section 2. The mark it fed is gone.
3. **Strong evidence exists.** Recorded in section 2. Not used: confirmations are stronger.
4. **The gate locks more coins.** Accepted; the claim is gone with Design A. Under D1 the
   waiting is the owner's rule and is shown to the user. A wedged sync stops the queue, and the
   screen says the wallet is catching up (section 5.3).
5. **The stale release misses custom endpoints and may never start its clock.** Accepted. The
   automatic release is dropped (section 6.3). The gaps are recorded in section 2.2.
6. **The stale release becomes an automatic query.** Accepted. Same answer. Owner question 1.
7. **Ordering alone does not put parent before child.** Accepted. The rule is now "a republish
   republishes its unconfirmed ancestors first", with one wait for the group (section 6.1).
8. **"Not sent yet" could be said of a confirmed send.** Accepted. The state is shown only at
   the tip, is driven by blocks and not by the verdict, and its words do not deny the payment
   (section 6.2).
9. **The cancel could not be published; "plain" must be proven.** Accepted. The cancel replaces
   the original in the wallet instead of outranking it, so no conflict-order change is needed,
   and an unclassified input is refused (section 6.4).
10. **DigiDollar and asset senders got the gate without the message.** Accepted. Both go through
    the queue with the same screens, or are refused with a clear message until PR 9
    (sections 5.7, 7.2). The "waiting" figure is not read from the coin set.
11. **An approved send could be refused after the PIN.** The in-memory mark is gone. A held
    send that fails its pre-release check becomes "Needs your approval" (section 5.6).
12. **The off-by-one is real.** Accepted. Corrected in section 1.1. The fix ships with PR 6.
13. **A simpler rule.** Overtaken by D1. Its part (b), ancestors first, is section 6.1.
14. **Small corrections.** Applied in sections 2 and 3.

### The critique as written

Method: every cited line was opened in the same sources the draft used (`origin/develop`
`5bfcfe891`, core `8014392`). Node policy was read from the local node (`digibyte-cli`,
`digibyted -help-debug`, 9.26.7, read-only) and from the local Core tree `~/digibyte` (v8.26.0).
No device, no transaction. Line citations in the draft are accurate to within two lines unless a
point below says otherwise. The policy table in section 3 matches the node for 9.26.7; the
8.22.2 column was not re-checked (the local tree is 8.26.0, same values, in
`src/policy/policy.h:61-67` and `src/kernel/mempool_options.h:24`).

1. **CONFIRMED-WRONG: "taken by a peer" does not mean a node accepted the send, so section 6's
   "gives the invariant by construction" does not hold.** The publish verdict 0 and the
   `txRelays` record are set when a peer *asks for the bytes* (`core:BRPeerManager.c:5119-5160`,
   `_peerRequestedTx`, answered 0 at `:5192`). A node sends `getdata` for any announced
   transaction it does not have, before it validates it. A parent that is then refused (orphan,
   non-standard, dust, conflict) has the same record as an accepted one. The core's own header
   records this exact case: "an asset transfer that published, reported six relays, and existed
   in no mempool and no block" (`core:BRPublishOutcome.h:17-20`). Rule 1 of Design A therefore
   does not close causes 4, 6 or 7 of section 3, and closes cause 1 only when no peer even asked.
   With K = 1 the result is a train of two, not zero.

2. **CONFIRMED-WRONG: `txRequests` is not "a peer requested the send".** It is the list of peers
   *this wallet* sent a `getdata` to and that have not answered
   (`core:BRPeerManager.c:1441-1444`; removed on answer `:2798`, `:2895`, on notfound
   `:5072-5073`). The draft's core change ("set by the peer manager when a peer relays or
   requests the send", citing `txRelays`, `txRequests`) would mark a send as taken because the
   wallet asked about it. Only `txRelays` carries evidence, and it mixes weak evidence (point 1)
   with strong.

3. **SUGGESTION: strong evidence exists and is separable.** Core answers a `getdata` for a
   transaction only when it is in its mempool (or the latest block)
   (`~/digibyte/src/net_processing.cpp:2577-2594`, `FindTxForGetData`). So a peer *serving the
   send back* (`_peerRelayedTx`, `core:BRPeerManager.c:2704-2729`) or *announcing it*
   (`_peerHasTx`, `:2837-2858`) proves a mempool has it; a peer fetching it from us proves
   nothing. If the gate keeps an evidence rule, the mark should come from those two handlers
   only. Note the catch: the wallet never asks a peer that already fetched from it
   (`:1441` skips peers in `txRelays`), and on a flood every peer fetches. Getting strong
   evidence needs a deliberate ask-back (for example to one peer the send was not announced to).

4. **CONFIRMED-WRONG: "It does not lock more coins than today" (section 6).** Today the change of
   an unconfirmed send is spendable. Under the gate it is not, unless the in-memory mark is set.
   The mark is lost on process death, on a manager rebuild, and on a wallet switch (the draft
   says so). After every send a typical wallet's whole balance is one change output. Concrete
   case: send, close the app within a block, reopen later. The parent confirmed on chain, the
   wallet still holds it as unconfirmed, there is no mark, and a confirmed transaction is not
   served or announced by peers again, so the mark cannot be rebuilt. The whole balance waits
   until compact-filter sync reaches that block. In a healthy sync that is seconds. In any of the
   three wedge classes this project documents as recurring it is hours or days, and Q6 recommends
   refusing the server-reported height. Today that user can pay, and the payment is sound.

5. **RISK: R3 does not release for the users who run their own node, and may not start its clock
   at all.** (a) The stale rule answers only when the endpoint is the default one
   (`kt:reconcile/DgbNodeClient.kt:274`, `core:BRStaleSend.h:109`). With a custom endpoint a
   never-relayed send is never stale, so under Design A its change waits with no automatic exit.
   (b) The age comes from the transaction's timestamp, and "a send with no timestamp is never old
   enough" (`core:BRStaleSend.h:9-11`, `:87`). The peer manager sets an unconfirmed transaction's
   timestamp to 0 when fewer than `maxConnectCount` peers relay it
   (`core:BRPeerManager.c:1379-1381`), which is the state of a send that went nowhere. Whether the
   row the clear reads (`kt:WalletManager.kt:790`, field 4) is that timestamp was not traced to
   the JNI; it must be measured before R3 is relied on as the exit from the gate.

6. **RISK: R3 turns a user-initiated query into an automatic one.** Today the default endpoint
   learns a stuck send's txid and inputs only when the user opens the reconcile screen
   (`app:ui/settings/ReconcileScreen.kt:301`, the only caller). From the sweep it would be sent
   for every wallet with a day-old unconfirmed send, with no user action. This is a data-path
   change on a sovereignty-first roadmap and belongs in section 7 as an owner question. It is
   also one server's answer deciding when coins come back, now without a person in the loop.

7. **RISK: R1 alone still republishes a child without its parent.** The cadence is per txid:
   three sweeps, then once an hour (`app:service/StrandedSendSelector.kt:115-116`,
   `core:BRStrandedSend.h:66-72`). A parent sent ten minutes before its child is on HOLD while
   the child is in its three attempts, and their hourly slots never line up. Ordering the list
   by dependency changes nothing when the parent is not in that sweep's list. The rule needs to
   be "a republish of a send republishes its unconfirmed ancestors first". Each republish also
   waits 15 s (`app:service/SyncService.kt:3851`), so a nearby parent and child go out a block
   apart.

8. **RISK: R2 can say "Not sent yet" about a send that confirmed.** Verdicts live in the peer
   manager's memory. After a restart or rebuild the sweep republishes, and a node that already
   has the send (in its mempool or in a block) does not ask for it, so the verdict is
   `ETIMEDOUT`. With the wallet behind the tip, a confirmed payment would read "Not sent yet".
   A user who believes it pays again by another route. R2 should be shown only when the wallet
   is at the tip, and should not use words that deny the payment happened.

9. **RISK: R4 as written cannot be published by this core.** The cancel spends an input an
   earlier unconfirmed wallet transaction already spends, so the wallet orders it second and
   marks it invalid (`core:BRWallet.c:417-431`). The peer manager refuses to serve an invalid
   transaction and answers the publish `EINVAL` (`core:BRPeerManager.c:5133-5140`), and the
   sweep neither republishes an invalid send nor keeps it past ten minutes
   (`app:service/SyncService.kt:3794-3824`). R4 needs a rule that the cancel outranks the send
   it names. That is a change to the wallet's conflict order, larger than "a builder". Also, "a
   plain DGB input" must be proven plain by the same standard as the transfer-rule gate: an
   input the asset layer has not classified must refuse, because a wrong "plain" burns an asset.

10. **RISK: DigiDollar and DigiAsset senders get the gate without the message.** The gate reaches
    their fee coins through `_BRWalletUtxoSelectableAny` (`core:BRWallet.c:2205`) and
    `BRWalletSelectableUTXOs` (`core:BRAssetSend.c:484-487`). The Kotlin changes listed cover only
    `TransactionBuilder` and the DGB Send screen. A DigiDollar or asset send whose only fee coin
    is waiting would fail with today's generic error. Separately, the "waiting" figure cannot be
    read from the coin set for these two: a DigiDollar or asset send is pending, so its DGB change
    is in no coin list and no balance until it confirms (`core:BRWallet.c:444-482`). After such a
    send the accessor must count pending change, or the screen shows "0 available" with nothing
    "waiting". The $1.00 output rule, the 0.1 DGB fee and implicit change to the last output are
    not touched by Design A; no break found there.

11. **RISK: an approved send can be refused after the PIN.** The signer re-checks that every
    input is still selectable (`core:BRSpendGate.h:10-19`, via `BRWalletOutpointSelectable`,
    `core:BRWallet.c:1833-1855`). With a mark that a manager rebuild clears, a recovery path
    firing between Review and the signature turns an approved send into a refusal. No path was
    found where Design A signs without a fresh PIN or biometric; this is an availability fault
    only. The clear itself must be explicit: the wallet object outlives the manager, and a mark
    left set after a rebuild fails on the unsafe side.

12. **SUGGESTION: section 8's first open item is answerable now, and the off-by-one is real.**
    Core refuses when `ancestors + 1 > limit` (`~/digibyte/src/txmempool.cpp:192-193`, v8.26.0),
    and the 9.26.7 help text reads "if number of in-mempool ancestors is <n> or more (default:
    25)". The transaction counts itself: at most 24 unconfirmed ancestors. The walk starts at
    depth 25 on the coin's transaction (`core:BRWallet.c:1783`), so it accepts a coin with 24
    ancestors and the new send has 25. Changing 25 to 24 is a one-line fix worth shipping on its
    own, whatever is decided about the rest.

13. **SUGGESTION: something simpler meets the stated goal.** Since the evidence rule is weak
    (points 1-3) and is what causes the lock (point 4), consider: (a) the ancestor cap by count
    and size, K small, with no evidence condition; (b) the core's publish and the sweep always
    send a send's unconfirmed ancestors first, to the same peers, which removes the orphan child
    (causes 1, 5 and 6) at the source; (c) the evidence, taken from point 3's two handlers and
    saved with the send's record, drives the Send-screen message and R2, not selection. If the
    owner wants the invariant strictly by construction, K = 0 is the only setting that gives it,
    at the cost in point 4 for every send.

14. **SUGGESTION: small corrections.** Section 2's "`core:BRPeerManager.c:56`" is line 55, and
    Design A's "`:1311-1312`" is `:1313-1314`. Section 3 cites `src/validation.h` for the limits;
    in the tree available here they are in `src/policy/policy.h` and
    `src/kernel/mempool_options.h`. The local node has three peers with `relaytxes: false` out of
    twenty, which supports cause 2. Section 2.1's claim that the sweep runs in txid order is
    correct (`StrandedSendSelector.kt:108`).
