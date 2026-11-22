# TX-QUEUE: one send at a time, one send waiting (4.1.6 design)

> **Status 2026-11-22: third revision, written to the owner's final decisions. Ready for his
> answers to section 9.**
>
> The owner decided, all on 2026-11-22, in his words:
>
> - **D1, first:** *"we can queue as deep as we want, but we can't initiate until we have at
>   least 3 confirmations on previous transaction."*
> - **D1, amended. This is the one that stands:** *"it make sense for the queues to be only one
>   deep and then refuse, so that the user has to be available to complete/verify the send."*
> - **D2:** *"wait only"*. No "send anyway" when coins are waiting.
> - **D3:** *"cancel this send. 4.1.6 works fine"*. Cancel is built in 4.1.6.
>
> **The lead's reading, for the owner to confirm (question 1):** "one deep" means one waiting
> send in addition to the one in flight. A further send is refused until there is room. The
> waiting send is a stored request. It is not signed in advance and it never goes out on its
> own. The user approves it, with the PIN or biometric, when its turn comes.

Design only. No production code.

Sources: `origin/develop` at `5bfcfe891`, core pinned at `8014392`. Paths under `core:` are the C
core (`native/src/main/jni/digibytewallet-core/`). Paths under `kt:` are
`core/src/main/java/app/aroundtheblock/wallet/core/`. Paths under `app:` are
`app/src/main/java/app/aroundtheblock/wallet/`. Paths under `jni:` are
`native/src/main/jni/bridge/`.

## 0. The short version

- No new send from this wallet starts while a send from this wallet is on the network with
  fewer than 3 confirmations. The wallet's own sync must have proven them.
- The rule covers plain DGB, DigiDollar and DigiAsset sends. The core enforces it, so no screen
  can skip it.
- While one send is confirming, the user can leave **one** more send waiting. It is a note of
  what to pay: payee, amount, kind. Nothing is signed.
- A third send is refused, with a message that says what is waiting and that it can be removed.
- When the first send has 3 confirmations, the wallet says the waiting send is ready. The user
  opens it, sees the normal review screen built on the coins as they are then, and approves
  with the PIN or biometric.
- Nothing signs, and nothing goes on the network, without the user there. The PIN rule has no
  exception.
- Removing the waiting send is free. Nothing was signed, so there is nothing to undo.
- A send that does not confirm is shown as "Not confirmed yet", only when the wallet is at the
  chain tip. The user can try again or **cancel it**. Cancel is a small payment to yourself
  that spends the same coins, behind its own PIN prompt.
- No new automatic question to a server. No automatic removal.
- Size: three core changes and six app changes. Section 8 has the order.

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
  unconfirmed sends, with their full signed bytes, and a send writes the checkpoint right after
  it is registered (`kt:TransactionBuilder.kt:325-328`).
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
2. **One waiting send.** While the gate is closed, the wallet can hold one request to send. A
   second request is refused.
3. **The user approves at the time.** When the gate opens, the wallet says the request is
   ready. The user reviews it and approves it. Only then is anything built, signed or sent.

There is no signed transaction held on the device. There is no chain signed in advance, no
automatic release, no pause rule and no "held" set in the core.

### 5.2 The gate

**What counts as "a send from this wallet".** A transaction in the wallet's list that is valid
and spends at least one of this wallet's coins. That is read from the wallet's own
transactions, not from the app's record of what it sent.

| Kind | Must wait for the gate | Closes the gate while under 3 confirmations |
|---|---|---|
| Plain DGB send | Yes | Yes |
| DigiDollar send | Yes | Yes |
| DigiAsset send | Yes | Yes |
| "Cancel this send" (section 6.4) | No. It is the way out. | Yes |
| A send made on another device with the same phrase | (not ours to hold) | Yes, once this wallet sees it (question 6) |
| Legacy sweep, foreign-phrase sweep | No | No. It spends coins that are not this wallet's and pays this wallet. To this wallet it is a receive. (question 7) |
| Asset recovery move | No | No, on the reading that it spends only the other phrase's coins (section 8.6). It already waits for its own split to confirm (`kt:recovery/ForeignAssetTransferService.kt:117-122`). |
| An incoming payment | n/a | No |
| A republish of a send the wallet already holds | No | (already counted) |
| A transaction the wallet holds as invalid | n/a | No. The chain or an earlier transaction has beaten it (`core:BRWallet.c:417-431`). |
| Digi-ID | Not a transaction | No |

**How confirmations are counted.**

- The count is `tip - height + 1` for a transaction with a height, where the tip is the
  wallet's own verified tip. It is 0 for an unconfirmed transaction.
- It is also **0 when the tip is below the height**. The tip can go down during a reorg or a
  rescan (section 1.7). Without this the subtraction wraps and reads as billions of
  confirmations.
- "Proven" needs no new mark. Since 4.1.3 the wallet writes a height only when it matched the
  transaction to a block itself (section 1.7). The gate reads the height and nothing else. It
  does not read `WALLET_SIG_ALWAYS`, for the reason given in section 1.7. A server's answer
  never opens the gate, because a server's answer never becomes a height.
- 3 is the owner's number. It is one constant in one core header.

**Across a restart.**

- The height is saved in the transaction checkpoint and read back at load (section 1.7).
  Nothing new is stored. A send that had 3 confirmations before the restart has them after it.
- Right after load the wallet's tip is the highest height it holds, until the peer manager
  reports the real one. So the newest send can read 1 confirmation for a moment. That is the
  safe side: the gate is closed a few seconds longer.
- On the day of the upgrade nothing changes for old records. They have heights. The gate reads
  them as it reads new ones.

**Reorgs.** If the send in flight drops below 3 confirmations, the gate closes again. If a
later send had already started, both are valid and both are on the network. The sweep
republishes them, parent first (section 6.1). That needs a reorg at least 3 deep.

**When the wallet's history is missing.** A rebuild from chain throws the local transactions
away, the send in flight included (`kt:WalletManager.kt:838-859`). So does the crash-loop
breaker, and a restore starts with none. Until the scan has found them again the gate would
read "open" for a wallet that may have a send in flight. So: **no send starts until the wallet
has reached the chain tip once since its history was last cleared.** The screen says: "Your
wallet is rebuilding its history. You can send when it has finished." (question 8).

**Where the gate is enforced.** In the shared core, in two places, both under the wallet lock:

- before a signature may leave the signer (beside the existing checks in
  `core:BRSpendGate.h`);
- again when an own send is registered (`BRWalletRegisterOwnSend`, `core:BRWalletSend.h`), so
  two sends approved in the same instant cannot both start.

It applies to a transaction that spends a wallet coin and that the wallet does not hold yet. A
republish of a held send passes. The cancel swap (section 6.4) is its own call. Kotlin also
asks the gate first, only to choose which screen to show. Kotlin cannot open it.

**Cost.** When sync is stuck, the user cannot send. That is D2. The screen says the wallet is
catching up. It does not say anything failed. Section 6.6 lists every case where the gate could
stay closed and what the user can do in each.

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
- a fingerprint of the wallet it belongs to (section 7).

It holds no signature, no key, no transaction and no choice of coins. It reserves nothing: the
balance and "Max" are not changed by it. It is checked lightly when stored (a valid address, an
amount above zero). It is checked fully at its turn.

**Its three states.**

| State | Meaning | Stored |
|---|---|---|
| Waiting | The gate is closed. | Yes |
| Ready | The gate is open and the wallet is at the tip. | No. It is worked out each time from the gate, so it cannot be wrong after a restart. |
| Approving | The user has approved it and the wallet is signing. | Yes, written before the signature. |

**"Approving" is one-way.** The order at approval is: write "approving"; sign; register the
send; delete the request; publish. If the app dies between "approving" and the delete, the
wallet cannot know from the request alone whether the send went. So on the next start such a
request is never offered as a plain request again. If the wallet holds a new send to that payee
for that amount, the request is deleted. If not, the row says: "This send may already have gone
out. Check your activity before you send it again." The user must dismiss that before a new
review opens.

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
- No PIN is asked to store it. Nothing is signed (question 4).
- If the wallet is behind the tip the first line reads: "Your wallet is catching up with the
  network. It cannot tell yet whether your last send has confirmed."
- If the gate opens while the user is still on the screen, Review works as today.

**(b) Tapping Send when one is already waiting.** Refused before the form:

> One send is already waiting: 50 DGB to D8k…x2Q, asked today 14:02.
> Review it or remove it before you start another.
>
> **[Show the waiting send]**  **[OK]**

This holds whether the waiting send is still waiting or ready. The waiting one goes first or is
removed (question 1).

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
  no payee (question 2).
- The notification can only come from a running, unlocked-once process (section 1.7). After a
  reboot or a process death there is none. The user sees the banner on opening the app. The
  wording never promises a time.
- Tapping either goes through the app lock as usual, then to the review.

**(e) The review at its turn.** The normal review screen for that kind of send, opened with
the request's payee and amount. It is built fresh: coins, fee and change as they are now. The
user approves with the PIN or biometric, as for any send (`app:ui/components/SpendAuth.kt`).
The line "You asked for this send on 22 Nov, 14:02" is always shown.

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
nothing was signed." **[Remove]** **[Keep]**. No fee. No PIN (question 4). Nothing goes on the
network. A request in "approving" cannot be removed this way; it gets the notice in 5.3.

**(h) Expiry.** The waiting send does not expire (question 3). It cannot send itself, so age is
not a danger. It always shows when it was asked, on the row and on the review.

**(i) Wallet switch and removal.** Only the active wallet syncs (multi-wallet step 1). Before a
switch: "A send is waiting in this wallet. It stays here until you come back." The wallet
removal dialog gets one line: "This wallet has a send waiting. It will be removed. A send
already on the network still confirms."

**There is no "send anyway" (D2).**

### 5.5 DigiDollar, DigiAsset and the rest

- **DigiDollar.** The gate applies in the core from the first PR. The native call that builds,
  signs and publishes in one go (`jni:jni_transaction.c:1121`) asks the gate before it signs.
  It does not need splitting. A waiting DigiDollar request is the same stored note.
- **DigiAsset.** The gate applies in the same way at signing
  (`kt:asset/AssetManager.kt:3266-3283`). The transfer-rule gate is untouched and runs at the
  review. Implicit change to the last output is worked out when the send is built, at its turn.
- **Their DGB change.** A DigiDollar or asset send is "pending" in the core, so its DGB change
  is not credited until it confirms (section 1.3, 1.4). That lasts about one block, as today.
  By the time the next send may start, the last one has 3 confirmations and its change is
  credited.
- **Until the app PR for these two kinds lands** (PR 8), a DigiDollar or asset send while the
  gate is closed gets the plain message "Wait for your last send to confirm (1 of 3)." with no
  offer to store it. It is never sent early.
- **Sweep and recovery.** Not gated and they do not close the gate (table in 5.2). They keep
  their own PIN prompts. Swept coins cannot be spent until the sweep confirms, as today.
- **Digi-ID.** Not a transaction. Never waits.
- **Dandelion.** Unchanged. A send goes out by the user's setting. Cause 5 of section 3 (parent
  and child stemmed to different peers) cannot happen to a send made by this version: it has no
  unconfirmed parent.
- **Selection.** A send made under the gate never needs the wallet's own unconfirmed change,
  because the send that made the change has 3 confirmations. Pass 1 of selection
  (section 1.1) stays in the core for what the gate does not cover: wallets upgraded with a
  train already in flight.

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
- The walk fix ships with it: `core:BRWallet.c:1783`, 25 becomes 24, so a send can have at most
  24 unconfirmed ancestors, which is the node's limit (section 1.1).

### 6.2 "Not confirmed yet"

- A send is shown as **"Not confirmed yet"** when all of these hold: the wallet is at the
  chain tip; the send is valid and has no confirmation; and at least **20 blocks** (about 5
  minutes) have passed at the tip since this wallet first saw the send, or since it last
  reached the tip, whichever is later (question 5).
- It is counted in blocks the wallet verified. It does not use the device clock or the publish
  verdict. A wallet behind the tip says "Catching up", never this.
- It is defined from the wallet's transaction, not from the app's record. So a send made on
  another device gets the same state and the same actions.
- The words do not deny the payment: "This send has not confirmed yet. It may not have reached
  the network." Not "Not sent" and not "Failed".
- The row offers two actions: **Try again** (republish now, parents first) and **Cancel this
  send** (6.4).
- If a send is waiting behind it, the waiting row says: "Waiting for your last send. It has not
  confirmed yet."
- One notification, once: "A send has not confirmed yet." No amount and no payee.

### 6.3 No new automatic question to a server, and no automatic removal

- This design adds **no** new automatic request to any server.
- One exists today and is left as it is: the confirmation check sends the txid of each
  unconfirmed wallet transaction to the endpoint, at most once in 5 minutes (section 2.2). It
  sends no inputs and it removes nothing.
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

**When it is offered.** On a send in the "Not confirmed yet" state, and only on the **oldest**
unconfirmed send of a chain. Cancelling it cancels everything built on it. It is not offered on
a send that is seconds old, and not on a cancel (a cancel gets "Try again" only).

**What it cannot do.** If a node already accepted the original, nodes refuse the cancel:
replacement is off on DigiByte (section 3). Then the original confirms and the cancel dies. A
cancel that dies pays no fee, because it never confirms. The dialog says so before the PIN:

> Cancelling works only if the network has not accepted this send. If it already has, the send
> will confirm and the cancel does nothing.
> If the cancel works it costs a network fee of 0.00x DGB.
> Do not pay this person another way until the wallet says which of the two confirmed.

**How it works.** A cancel written as an ordinary second spend cannot be published by this
core: it is ordered after the original, marked invalid (`core:BRWallet.c:417-431`) and refused
by the peer manager (`core:BRPeerManager.c:5133-5140`). So the cancel replaces the original in
the wallet. The conflict order is left alone.

1. **Plan** (core). Takes the stuck send's txid. Lists the inputs the cancel may spend. An
   input qualifies only if all of these hold: the wallet owns the output; the output's
   transaction is confirmed; the output is not asset-held, not ONE-FAMILY-held, not a
   DigiDollar output, and can be signed; and asset detection has run to completion, the same
   preflight a review runs (`SpendPreflight.completed`, `kt:TransactionBuilder.kt:349-351`).
   These are the conditions of normal selection (`core:BRWallet.c:1812-1818`), asked of an
   outpoint the wallet holds as spent. **An input that cannot be classified is refused.** A
   wrong "plain" would destroy an asset. For a DigiDollar or asset send this leaves only its
   DGB fee coins. If no input qualifies, the send cannot be cancelled and the screen says so.
2. **Build** (core). Those inputs, one output to a new change address of this wallet, the
   default fee rate. If what is left after the fee is below the dust limit, cancel is refused.
3. **Approve and sign.** The user sees the fee and what comes back, and gives the PIN or
   biometric. Cancel signs and pays a fee, so it is a spend. It takes the same prompt as a
   send.
4. **Swap** (core), one call, in one hold of the wallet lock. It checks again, there, that:
   - the named send is still in the wallet, still valid and still unconfirmed (the block that
     confirms it can arrive between the PIN and the swap);
   - it is still the oldest of its chain: none of its inputs comes from an unconfirmed wallet
     transaction;
   - the cancel spends only inputs of the named send, and each still qualifies as in step 1;
   - the cancel pays only this wallet's addresses.

   If any check fails, nothing is changed and the call says which. Otherwise it removes the
   named send and everything built on it, and registers the cancel. If the cancel is not
   accepted, the original is registered again. After the swap the cancel is an ordinary valid
   send. The peer manager serves it and the sweep republishes it with no special case.
5. **Publish** by the user's Dandelion setting.

**What decides the outcome.** The chain (section 1.7: a confirmed transaction always sorts
before an unconfirmed one).

- **The cancel confirms.** The original can never confirm. Its coins are back, less one fee.
  The gate opens when the cancel has 3 confirmations.
- **The original confirms after all.** It arrives in a block, sorts before the unconfirmed
  cancel, and the cancel becomes invalid. The existing ten-minute invalid hold removes it
  (`app:service/SyncService.kt:3790-3824`). The send shows as confirmed. The gate now waits for
  **the original** to have 3 confirmations.
- **A peer relays the original unconfirmed.** It is ordered after the cancel and marked
  invalid in this wallet. Nothing is published for it. The chain still decides.
- **Neither confirms.** The cancel is itself a send that has not confirmed. It gets "Not
  confirmed yet" and "Try again".

**A cancel that loses must not lead to paying twice.** Nothing leaves this wallet without the
user approving it at the time, so a lost cancel can no longer send anything by itself. What is
left is the user's own next approval. So:

- When the original confirms after a cancel was asked, the wallet records "cancel lost" beside
  the send's record. It survives a restart.
- The send's row says: "This send had already been accepted. It was not cancelled. It has been
  paid."
- **The next review of any send, the waiting one included, opens with that notice and the
  payee and amount of the send that was paid.** The user must acknowledge it before the PIN
  prompt appears. One acknowledgement clears it.
- The same notice is the content of one notification, without amount or payee: "A send you
  cancelled was paid after all."

**Kotlin side.**

- The original's record in `OutgoingTxStore` is marked "cancel requested" and leaves the
  sweep's working set. The sweep must never republish a send the user cancelled. The record is
  kept so the row comes back with its payee if the original confirms.
- For a send from another device there is no record. The mark is written as a new record keyed
  by the original's txid.
- An asset send's rows are cleaned as for a dead send (`clearDeadAssetSend`,
  `app:service/SyncService.kt:3808-3817`). A re-send reads the old attempt as `CONFLICTED`.

### 6.5 Removal is not cancel

- The manual check on the reconcile screen can **remove** a send as stale
  (`core:BRStaleSend.h`). Removal gives the coins back in this wallet. It does not spend them.
  The removed send stays valid on the network for as long as its inputs are unspent. One node
  saying "not found" is not every mempool.
- With the gate, a removal opens the gate at once. The user could pay again, and the original
  could still confirm.
- So the stuck row offers Cancel, never removal. And on the reconcile screen, when the check
  finds a send unknown **and a cancel can be built for it**, the action offered is "Cancel this
  send", not removal (question 10).
- Removal stays for a send that cannot be cancelled (no qualifying input, or dust). Its dialog
  gains: "Removing this send does not stop it. It could still confirm later." After such a
  removal the next review opens with a notice naming the removed send, as for a lost cancel.
- The two automatic removals that exist today are unchanged: a send two network groups
  refused, and a send another wallet transaction has beaten (section 2.2, items 1 and 2).

### 6.6 Every case where the gate could stay closed

D2 allows no override. So each case needs a stated way out.

| Case | What the user sees | The way out |
|---|---|---|
| The last send never reached a node | "Not confirmed yet" after 20 blocks at the tip | Try again. Cancel. |
| The last send was refused or beaten | Removed by today's rules after 10 minutes | None needed. |
| A send from another device never confirms | The same "Not confirmed yet", from the wallet's transaction | Try again and Cancel work here too: this wallet holds the transaction and the keys. |
| A cancel that does not confirm | "Not confirmed yet" on the cancel | Try again. If the original confirms instead, the gate follows the original. |
| A send that cannot be cancelled (no plain input, or dust) | "This send cannot be cancelled" | Try again. The manual check and removal on the reconcile screen (default endpoint). Rebuild from chain as the last resort. |
| The wallet cannot reach the tip (the three wedge classes) | "Catching up with the network" | The existing recoveries: the watchdogs, then Rescan, which cures wedge classes (a) and (b); force-stop cures (c). Cancel is also offered here after the wallet has been behind the tip for 20 minutes of running time, measured on the device's monotonic clock (question 9). A cancel of a send that had in fact confirmed does nothing and costs nothing. |
| A user on a custom endpoint | As above | Everything above except the manual check. Cancel needs no server. |
| History cleared (rebuild, breaker, restore) | "Your wallet is rebuilding its history" | It ends when the scan reaches the tip. If it cannot, it is the row above. |
| Confirmed history after an update or restart | Nothing. The gate reads saved heights. | None needed (section 5.2). |
| A record whose height an old version (before 4.1.3) took from a server | Nothing. It reads as confirmed. | None needed. See section 8.6 for what that costs. |

**The documented last resort** is the rebuild from chain (`rebuildFromChainRescan`). It drops
every unconfirmed send from this wallet and rebuilds from blocks. It does not cancel anything
on the network. Its screen should say so.

## 7. Where the waiting send lives, and what erases it

**One small store per wallet and network.**

- A preferences file, base name `dgb_waiting_send`, added to
  `WalletStores.WALLET_AND_NETWORK_PREFS` (`kt:wallets/WalletStores.kt:6-7`) and resolved only
  through `WalletStores.prefsName`. The legacy wallet gets `dgb_waiting_send` and
  `dgb_waiting_send_testnet`. Wallet `<id>` gets `dgb_waiting_send_<id>` and
  `dgb_waiting_send_<id>_testnet`.
- It holds a handful of short values, so the memory problem `dgb_sync_data` had does not arise.
  It is written with `commit()`, whole, in one edit.
- It is plain, like `dgb_outgoing_tx`, which already holds payee addresses. It holds no key and
  no signature. The app is excluded from backup (`app/src/main/AndroidManifest.xml:22-24`).
- **It carries the wallet's seed fingerprint.** A file whose fingerprint is not the loaded
  wallet's is refused and deleted. It is never shown. A new wallet must not see the last
  wallet's payee.

**Load never throws.** The store is read at start, so a throw there would be a crash on every
launch. Every read is guarded. A file that cannot be read, has an unknown version, or fails
its own checks reads as "nothing waiting", is deleted, and the user is told once: "A waiting
send could not be read and was removed. Nothing was sent." Losing it loses a note, not money.

**What erases it.** Adding the name to the list reaches only the eraser. The other paths clear
their stores by name, so each needs its own line and its own test.

| Path | Clears by | Change |
|---|---|---|
| Removing a wallet; the full wipe of every non-legacy wallet | The list (`kt:wallets/WalletStoreEraser.kt:193`) | The list entry. A test fails if the name is missing. |
| The full wipe of the legacy wallet | Named steps (`kt:WalletDataEraser.kt:322-337`, `:379`) | A named step on every network, read back. |
| Create, recover, seed change, fingerprint-mismatch restore | `clearSyncData`, by name (`kt:WalletManager.kt:1901-1927`) | An explicit delete, with a test. |
| The crash-loop breaker | By name (`app:StaleDataWiper.kt:47-95`) | It deletes the waiting send too. A bad file can then never cause a second loop, and only a note is lost. |
| Rebuild from chain | Named steps | Kept. It is a note, and the gate stays closed until the rebuild reaches the tip (section 5.2). |
| A wipe that could not finish | `dgb_wipe_owed` | No change: it works by wallet id through the same list. |

**Multi-wallet.** The store is opened with an explicit scope. Storing, approving and removing
all check that the active wallet is still the one the screen was opened for. A wallet switch
leaves the request with its wallet (section 5.4 (i)).

**The marks beside a send's record.** "Cancel requested", "cancel lost" and "removed, may still
confirm" live in `dgb_outgoing_tx`, which every wipe path already clears. The tip height at
which a send was first seen, for the 20-block count, is kept there for the wallet's own sends
and in memory for others. Losing it restarts the count, which is the safe side.

## 8. Build plan

### 8.1 What changes in the shared core

iOS gets all of this with the pin.

**For the gate and the confirmation count.**

- `BRSendGate.h`, header-only: the number 3; the count with the tip-below-height case; the
  "Not confirmed yet" rule (20 blocks at the tip).
- `BRWallet`: one call under the wallet lock that answers the gate: open, or closed with the
  txid and the confirmations of the send it waits for. It reads heights the wallet already
  holds. No new field on a transaction and no change to the checkpoint format.
- `BRSpendGate.h` and `BRWalletRegisterOwnSend`: refuse, with their own result code, a
  transaction that spends a wallet coin, that the wallet does not hold yet, while the gate is
  closed.

**For Cancel.**

- `BRWallet`: the cancel plan (which inputs qualify), the cancel build, and the cancel swap
  with its re-checks in one lock hold (section 6.4).

**For republish.**

- `BRStrandedSend.h`: ancestors first, one wait per group.
- `BRWallet.c:1783`: 25 becomes 24.

**What the core does not get.** No held set. No change to what the peer manager asks or
serves. No change to the conflict order. No pause rule.

### 8.2 What stays in Kotlin

- JNI for the calls above. The table generator runs with `--check` in CI
  (`docs/development-process.md` rule 6).
- `WaitingSendStore`, its `WalletStores` entry, and every clearing path in section 7.
- The screens in section 5.4, the notices in 6.4 and 6.5, the notifications.
- The "history cleared" flag (section 5.2).
- `OutgoingTxStore`: the three marks, and "a cancelled send is never republished".
- The reconcile screen offering Cancel in place of removal.
- Asset row clean-up after a cancel.
- Strings in 13 languages.

### 8.3 Pull requests, in order

Every PR that touches what may be signed, sent, cancelled, republished or erased is
**owner-gated**: the owner reviews it before merge, whatever the independent review says. Core
lands on its `develop` before each app pin moves.

| # | Repo | What | Owner-gated |
|---|---|---|---|
| 1 | core | `BRSendGate.h`, the gate call, enforcement at the signer and at own-send registration, `send_gate_kat` | Yes |
| 2 | core | Ancestors-first republish; 25 to 24; `stranded_send_kat` extended | Yes |
| 3 | core | Cancel plan, build and swap; `cancel_send_kat` | Yes |
| 4 | app | Pin to 1 and 2; JNI; every send path asks the gate; the "wait" message for all three kinds; the "history cleared" rule; ancestors-first wiring | Yes |
| 5 | app | `WaitingSendStore` and all its clearing paths; the offer, the refusal, the waiting row, the ready banner, review from a request, remove. Plain DGB. | Yes |
| 6 | app | "Not confirmed yet", defined from the wallet's transaction; Try again | Yes |
| 7 | app | Pin to 3; Cancel this send: dialog, PIN prompt, marks, lost-cancel notice, asset clean-up, the reconcile screen change | Yes |
| 8 | app | DigiDollar and DigiAsset requests in the waiting slot | Yes |
| 9 | app | Notifications: ready, not confirmed yet, cancel lost | No. Independent review and green CI. No amount or payee in any text. |

- PRs 4, 6 and 7 must ship in the same release. A gate without the stuck half is a wallet that
  can stop sending.
- PR 5 can ship with them or after. Without it the user simply waits and sends again.
- PR 8 can follow. Until then those two kinds get the plain "wait" message.
- Each PR that adds strings adds them in all 13 languages.

### 8.4 Tests

Every new gate is seen red on the unfixed code first, and the PR comment says how.

**Host KATs (core).**

- `send_gate_kat`: closed at 0, 1 and 2 confirmations, open at 3; **the tip below the height
  reads 0**; a reorg closes it again; **a confirmed send saved to a checkpoint and loaded again
  leaves the gate open**; an invalid transaction does not close it; a receive does not close
  it; a transaction with no wallet input (a sweep) is not refused and does not close it; a send
  from another device closes it; a transaction first imported from a server and then
  confirmed over P2P counts by its height; a second
  own send is refused at registration while the first is under 3; a republish of a held send
  passes; a DigiDollar build and an asset build are refused while closed; the 20-block rule.
  Red arms: 2 confirmations accepted; the unsigned wrap; the gate skipped at registration.
- `cancel_send_kat`: a cancel of a plain send; of an asset send (fee coin only); of a
  DigiDollar send; refused when an input is asset-held, unclassified or unconfirmed; refused
  when an output is not the wallet's; **refused, with nothing changed, when the named send
  confirmed before the swap; refused when it is no longer the oldest of its chain**; the swap
  leaves a valid, publishable cancel; the swap passes while the gate is closed; the original
  arriving confirmed makes the cancel invalid **and the gate then follows the original**; the
  cancel confirming keeps the original out. Red arms: an asset input accepted; a non-wallet
  output accepted; the re-check skipped.
- `stranded_send_kat` extended: ancestors first; a parent on its hourly hold is still sent
  before its child; one wait per group. Red arm: txid order.
- The selection KAT: a coin with 24 unconfirmed ancestors is not selected. Red arm: 25.
- Existing selection, prepared-send, coin-reservation and stale-send KATs stay green.

**Unit tests (Kotlin).**

- `WaitingSendStore`: round trip; **load never throws** on a torn file, an unknown version, a
  wrong type, random bytes; a file with another wallet's fingerprint is refused and deleted; a
  second request is refused; "approving" is one-way.
- Clearing: `clearSyncData` deletes it; the crash-loop breaker deletes it; the full wipe
  erases it for every wallet on both networks, read back; wallet removal erases that wallet's;
  the name-in-list test. The existing name tests (`LegacyStoreNameParityTest`,
  `UpgradeFrom414Test`, `WalletStoreEraserTest`) are updated in the same PR.
- View models: each state of 5.4; the gate closing between Review and the PIN gives the offer,
  not an error; ready is shown only at the tip with the gate open; history cleared closes it.
- Cancel: **cancel asked, a request to the same payee waiting, the original confirms: the next
  review opens with the notice and no PIN prompt until it is acknowledged**; a cancelled send
  is never selected for republish; the mark for a send with no record.
- "Not confirmed yet": not shown behind the tip; shown for a send with no `OutgoingTxStore`
  record; counted in blocks.
- `LocaleResourceParityTest`; `OnboardingHardcodedStringTest`.
- Before each commit: the full core and app unit suites with `--continue`.

**Device sends (Note 8 mule, small amounts between its own addresses).** About 25 sends.

1. Send, then Send again at once. Expected: the offer; the request is stored; nothing second on
   the network; "ready" after 3 confirmations; review; PIN; it confirms. Once with Dandelion
   off, once on.
2. A third Send while one waits. Expected: refused, naming the waiting send.
3. Store a request, force-stop, reopen after 2 minutes. Expected: the request is there, ready
   after unlock, and nothing was sent meanwhile.
4. Store a request for more than will be left. Expected at its turn: "not enough", nothing
   signed.
5. Remove a waiting send. Expected: no PIN, nothing on the network.
6. Lock the phone while a request waits. Expected: one notification with no amount or payee.
7. Network cut right after a send. Expected: "Not confirmed yet" after 20 blocks at the tip;
   Try again with the network back confirms it.
8. Cancel a send that never reached a node (network cut at send, restored for the cancel).
   Expected: the cancel confirms; the original never does; the gate opens when the cancel has
   3.
9. Cancel a send that did reach the network, with a request to the same payee waiting.
   Expected: the original confirms; the cancel dies; the notice appears before the waiting
   send can be approved.
10. A DigiDollar send and an asset send while a DGB send is confirming. Expected: each waits.
    One test asset must be unruled; a rule-bound one must still be refused at review.
11. Switch wallet with a request waiting. Expected: the line in the dialog; the request is
    there on return.
12. Update from 4.1.5 with a confirmed history. Expected: the first send starts at once.

The pre-publish suite sends no coins, so it cannot cover this. It gets one check at most: Send
on an empty wallet still gives the insufficient-funds refusal, not a gate message.

### 8.5 What iOS gets

From the shared core, with the pin: the gate and its number, the confirmation count,
enforcement at the signer and at registration, the "Not confirmed yet" rule, the cancel plan,
build and swap, ancestors-first republish, the 24 fix, and all the KATs.

iOS still has to build: its own store for the waiting send and its wipe coverage, the screens,
the notices and the strings. Nothing in this design needs background time, so iOS loses
nothing by having little of it.

### 8.6 Not verified

- Nothing was built, run or sent for this revision. No KAT, no unit test, no device, no
  emulator. Statements about the code are from reading the cited lines at the two commits
  named at the top.
- That a confirmed original displaces an unconfirmed cancel was read from the ordering code
  (`core:BRWallet.c:161-162`, `:224`, `:417-431`). It was not run. `cancel_send_kat` must show
  it before cancel is relied on.
- An old record whose height came from a server before 4.1.3. Nothing in the checkpoint tells
  it from a proven one. The one-time history rebuild on upgrade may already have replaced
  these. Not traced. The worst case is a gate that reads open past a very old send that never
  truly confirmed. That fails on the side of letting the user send, as today.
- Whether asset detection can classify an outpoint the wallet holds as spent, which the cancel
  plan needs. `BRWalletUtxoIsAsset` is asked of unspent outputs today.
- Whether the asset recovery move ever spends this wallet's coins. Its native is
  `buildAndSignForeignAssetTransfer`; the C was not read. If it does, it must take the gate.
- Whether the existing "has synced" flag is cleared by all three history-clearing paths, or a
  new flag is needed.
- Whether the Send screen takes a note today.
- Whether the stale rule's clock starts for a send that went nowhere. Not needed here, since
  the rule stays manual.
- The measured block times and node policy are the critique's and the first draft's readings
  of the local node. They were not re-run for this revision.
- Section 4 is from memory. The 8.22.2 policy column was not re-checked.
- The 13 translations. None exist yet. Cancel and "may already have gone out" wording need a
  native reader before they are called done.

## 9. Questions for the owner

D1 (as amended), D2 and D3 are decided and not asked again. These are what they left open.

1. **Is this the right reading of "one deep"?** One send in flight plus one waiting. And while
   one is waiting, a new send is refused even if the waiting one is ready: review it or remove
   it first. *Recommended: yes to both.*
2. **A notification outside the app when the waiting send is ready?** *Recommended: yes. One,
   once, "A send is ready for your approval." No amount and no payee.* The same for "A send
   has not confirmed yet" and "A send you cancelled was paid after all".
3. **Does the waiting send expire?** *Recommended: no.* It cannot send itself. It always shows
   when it was asked, and the user removes it when it is no longer wanted.
4. **A PIN to store or to remove the waiting send?** *Recommended: no for both.* Nothing is
   signed by either. The approval always asks.
5. **"Not confirmed yet" after 20 blocks at the tip (about 5 minutes)?** *Recommended: yes.*
   Cancel is offered from the same moment.
6. **Does a send made on another device with the same phrase close the gate here?**
   *Recommended: yes.* It is a send from this wallet. Try again and Cancel work on it here.
7. **Do the legacy sweep and the recovery moves wait for the gate, or close it?**
   *Recommended: neither.* They spend coins that are not this wallet's.
8. **No sending until the wallet has reached the tip once after a restore, a rebuild from
   chain or the crash-loop breaker?** *Recommended: yes.* Until then the wallet cannot know
   whether a send is in flight. This is new for a freshly restored wallet.
9. **Offer Cancel when the wallet cannot reach the tip?** *Recommended: yes, after 20 minutes
   behind the tip, with wording that says the wallet cannot tell whether the send confirmed.*
   Otherwise a wedged sync plus a stuck send has no way out but a rescan.
10. **On the reconcile screen, offer Cancel in place of removal when a cancel can be built?**
    *Recommended: yes.* Removal does not stop the send; cancel does.
11. **DigiDollar and DigiAsset in the waiting slot in 4.1.6, or one release later?**
    *Recommended: in 4.1.6 if PR 8 is ready; otherwise they get the plain "wait" message.* The
    3-confirmation rule applies to them from the first PR either way.
12. **iOS at the same time?** *Recommended: the core rules together, with the pin. The iOS
    screens when that work is scheduled.*
13. **Budget for device test sends on the Note 8.** *Recommended: approve 25 sends of small
    amounts between the mule's own addresses, plus one DigiDollar and two asset sends.*

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
