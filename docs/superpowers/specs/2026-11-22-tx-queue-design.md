# TX-QUEUE: sending again before the last send confirms (4.1.6 design draft)

> **Status 2026-11-22: DRAFT. Nothing is decided.** Read the independent critique at the end
> before the recommendation in section 6: it shows that the recommendation's "taken by a peer" mark
> does not prove a node accepted the send, and that the gate as drafted can hold a wallet's whole
> balance after a restart. Its point 13 proposes a simpler rule. The owner's answers to section 7
> are not in yet.

Design only. No production code, no PR.

Sources: `origin/develop` at `5bfcfe891`, core pinned at `8014392`. Paths under `core:` are the C
core (`native/src/main/jni/digibytewallet-core/`). Paths under `kt:` are
`core/src/main/java/app/aroundtheblock/wallet/core/`. Paths under `app:` are
`app/src/main/java/app/aroundtheblock/wallet/`.

## 0. The short version

The wallet already refuses to spend an unconfirmed coin someone else sent. It already spends
confirmed coins first. What it still does is spend **its own unconfirmed change, up to 25
generations deep, with no check that the parent ever reached the network**. That is the train.

The recommendation is a small rule in the core's coin selection (the "chain gate"), a clear
message on the Send screen when coins are waiting, and three repairs to the recovery side. It is
not a background queue. A queue that signs later cannot honour the PIN gate, and a queue that
holds signed sends makes "sent" mean "not sent yet".

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
- Off by one: the walk allows a coin whose transaction already has 24 unconfirmed ancestors.
  The new send is then the 26th in the chain. DigiByte Core's limit is 25 (section 3). This
  reading of the limit follows Bitcoin Core's meaning (the count includes the transaction
  itself). It should be confirmed in a regtest or KAT before it is relied on.
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
- The core answers each publish once: 0 (a peer took it), `EINVAL`, `ENOTCONN`, `ETIMEDOUT`
  (20 s of silence), `ECANCELED` (`kt:sync/PublishOutcome.kt:13-22`,
  `core:BRPeerManager.c:56`).
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
   `clearStuckSends`, `kt:WalletManager.kt:706`).
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

Checked on the local node (DigiByte Core 9.26.7, mainnet, synced, read-only RPC and help text)
and in the 8.22.2 source (`src/validation.h`, `src/policy/policy.h`):

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
   "the network took it".
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

No common wallet runs a background queue that signs later. The ones that avoid trains do it by
limiting what selection may take.

## 5. The designs

### Design A: chain gate (refuse or wait, in coin selection)

**Rule.** The wallet's own unconfirmed change is selectable only when all of these hold:

1. The parent was taken by a peer on this peer manager (a relay or a request, the evidence the
   publish verdict already uses), and it is not in a stem phase.
2. The new send would have at most **K** unconfirmed ancestors in total, counted (not by
   generation), and their size stays far below 101 kvB. Recommended K = 1.

Otherwise the coin waits. Confirmed coins are used first, as today.

**What the user sees.**

- Enough confirmed coins: no change at all.
- Not enough until the last send confirms: the Send screen says so in plain words. "1,200 DGB
  available now. 800 DGB more after your last send confirms (about 15 seconds)." The Review
  button enables itself when the coins arrive. Nothing is signed or sent on its own.
- Max uses the amount available now.
- The same message covers an unconfirmed receive from someone else, which today reads as
  "Insufficient balance".
- All text in all 13 languages in the same commit.

**Core changes.**

- `_BRWalletUtxoSelectable` pass 1 takes the new rule (`core:BRWallet.c:1780-1784`). Every
  builder inherits it: plain sends, DigiDollar fee coins, asset fee coins, the prepared-send
  check, Max.
- The wallet does not know relay evidence; the peer manager does
  (`core:BRPeerManager.c:1311-1312`, `txRelays`, `txRequests`). Add one per-transaction mark in
  the wallet, "taken by the network", set by the peer manager when a peer relays or requests the
  send, cleared when the manager is rebuilt. No evidence means not chainable. That is the safe
  side: the coin waits for a confirmation.
- Replace the generation walk with an ancestor count and size sum, with the same visit budget.
  This also fixes the off-by-one in 1.1.
- New read-only accessor: the amount selectable now, and the amount waiting. For the UI.
- A header-only policy file (same shape as `BRStaleSend.h`) holds K and the rule, so iOS takes
  the same rule.

**Kotlin changes.**

- `TransactionBuilder`: `Built.Short` splits into "not enough" and "waiting for N DGB"
  (`kt:TransactionBuilder.kt:404-416`).
- `SendViewModel` and `SendScreen`: the waiting state, the auto-enable, the strings.
- No change to signing, SpendAuth, or the prepared-send flow.

**Interactions.**

- DigiDollar: a DigiDollar send already cannot chain on DigiDollar change. Its fee coin now
  obeys the gate. If the only DGB is waiting, the send waits with the same message. The $1.00
  output rule and the 0.1 DGB fee are untouched.
- DigiAssets: asset inputs are untouched. Fee coins obey the gate. Implicit change to the last
  output and `CONFLICTED` are untouched.
- Dandelion: a stemmed parent is not chainable until it has fluffed and a peer has it. This
  closes cause 5.
- Multi-wallet: the rule is per wallet object. No new store.
- PIN / SpendAuth: unchanged. Nothing is deferred, so nothing is signed away from the prompt.

**Failure modes.**

- Sync is wedged and confirmations do not arrive. The user gets K sends on top of confirmed
  coins, then waits. Today they could build 25. Mitigation: the waiting message offers "check
  now", which uses the existing node check and the existing unproven-height update
  (`core:BRWallet.c:2757-2765`). Whether a server-reported height should open the gate is a
  question for the owner (Q6).
- The peer manager is rebuilt and the evidence is lost. The coin waits one block. Acceptable.
- A parent taken by one peer and then dropped. The child is stuck with it. K = 1 bounds the
  damage to one child, and the recovery half handles both.
- A wallet with one coin makes several payments in a row. With K = 1 the third waits for the
  first to confirm. On 15-second blocks that has usually already happened.

**Tests.**

- Host KAT `chain_gate_kat` with a comparison arm that must fail on today's code: depth, count,
  size, no evidence, stem phase, the 26th-in-chain case, DigiDollar fee coin,
  asset fee coin, Max.
- Existing selection, prepared-send and coin-reservation KATs stay green.
- Unit: builder outcomes, view-model states, locale parity, hardcoded-string gate.
- Device (Note 8, budgeted sends): five sends as fast as the UI allows from a one-coin wallet;
  the same with Dandelion on; the same with the network cut right after the first send.
  Expected: never more than K+1 unconfirmed in a row, and all confirm.

### Design B: a real queue (accept now, release one confirmation at a time)

Two shapes exist. They differ in when the signature is made.

**B1: sign later.** The wallet stores the request (address, amount). When the previous send
confirms it builds and signs the next.

- This cannot sign without the user. The PIN or biometric is required at the moment of action,
  and the seed key needs a device unlock inside its 300 s window. So B1 becomes "we will remind
  you": a notification, the user opens the app, reviews and approves. That is Design A plus a
  reminder.
- The fee and the coins can differ from what the user saw when they queued it.

**B2: sign now, hold the bytes.** The user approves each send as today. The wallet signs a child
on unconfirmed change, registers it, and holds the signed bytes until the parent confirms. Then
it publishes.

- User sees "Queued. Will be sent when your previous send confirms."
- Core: registration without publish, a held state the sweep must not republish, a release
  step on the parent's confirmation, and removal when the parent dies.
- Kotlin: a durable per-wallet queue store, a release worker in `SyncService`, wipe coverage,
  and new rows in the activity list.
- PIN gate: honoured. The user approved those exact bytes.
- Failure modes are the problem:
  - The app is frozen in the background. The held send does not go out. The user was told it
    was queued and the payee sees nothing, not even an unconfirmed payment. This wallet's
    background loop is known to freeze on aggressive devices.
  - Only the active wallet syncs. A held send in another wallet waits until the user switches.
  - The parent dies. Every held child dies with it and needs a fresh approval.
  - Signed transactions at rest are a new thing to store, protect from tampering, and wipe.
  - The 60 s coin reservation does not cover this. The held send must be registered to hold its
    coins, which is new core behaviour.
- DigiDollar and assets: their change is not spendable unconfirmed, so a held child could only
  be a plain send. The queue would not help the two paths users find most confusing.
- Tests: all of Design A's, plus process-death, freeze, wallet-switch and wipe cases on devices.

### Design C: batch (one transaction, several payments)

- The user adds several recipients, or the wallet delays a send for a short window and merges
  the next one into it.
- One transaction, no chain. Lower total fee.
- Merging after broadcast is not possible. Inputs are final and full RBF is off. So this needs
  either a pay-many screen or a deliberate send delay. A delay breaks "sent means sent".
- Core: the builder already takes several outputs (`core:BRWallet.c:2273-2276`). The
  prepared-send check assumes one payment plus change and would need a new approved shape
  (`core:BRPreparedSend.h:27-37`).
- DigiDollar: the transfer builder has one recipient. Assets: one transfer per transaction.
  Neither batches.
- It is a good feature. It does not give the invariant, because a user who sends twice still
  makes two transactions.

### The recovery half (every option needs it)

**R1. Publish parents before children.** The sweep orders by txid today. Order by dependency
instead. Small, Kotlin only, with the order rule written in `BRStrandedSend.h` so iOS matches.

**R2. Tell the user.** A send with no "taken by a peer" verdict after the first three sweeps
(about five minutes) shows as "Not sent yet" in the activity list and on its detail screen, with
"Try again now". Today the Send screen says it once and the row looks like any pending send.

**R3. Release automatically on the evidence the wallet already trusts.** Run the stale rule from
the sweep, not only from the reconcile screen. Same evidence: the pinned node says "not found"
and every input is unspent there. Removing the root removes the train. The age threshold stays
one day unless the owner shortens it (Q4). Asset rows are cleaned as the manual pass does today.

**R4. Cancel by conflict (the release that needs no trust).** "Cancel this send" builds a
self-payment that spends one plain DGB input of the stuck send and publishes it. If it confirms,
the stuck send is dead by the chain's own rule, the existing invalid-hold removes it, and the
coins are back. If the original confirms instead, nothing is lost but the attempt.

- It never touches an asset or DigiDollar input. It takes a plain DGB fee coin only. An asset
  input spent by a plain transaction would destroy the asset.
- It costs one fee and needs its own SpendAuth prompt.
- Core: a builder that may select an input the wallet holds as spent by one named unconfirmed
  transaction, and nothing else. This is a new signing path and needs its own KAT with a red arm.
- The asset layer already reads the abandoned attempt as `CONFLICTED`.

R1 to R3 are small and reuse rules that already ship. R4 is new signing surface.

## 6. Recommendation

**Design A with K = 1, plus R1, R2 and R3, in 4.1.6. R4 designed now, built in the release
after. Design C later, as a pay-many feature. Design B not built.**

Why:

- It gives the invariant by construction. A send is never built on a parent the network has not
  taken, and never more than one deep.
- It is one predicate in one file. Every builder already goes through it.
- It does not lock more coins than today. A stuck send already holds its inputs. The gate only
  stops the wallet from building on its change.
- It leaves signing, SpendAuth and the seed-key window exactly as they are.
- "Sent" keeps meaning sent. Nothing is held back from the network.
- No fee rule is needed in the gate: the builder cannot go below the relay floor (section 3).
- 15-second blocks make the wait short when the network is healthy. When it is not, waiting is
  the right answer.

Order of work: core rule and KAT first (red arm proves it), then the accessor and the Send
screen, then R1, R2, R3. Core lands on its `develop` before the app pin moves.

## 7. Questions only the owner can answer

1. **How deep may a chain go (K)?** 0 means confirmed coins only. 1 means one send on top of
   one waiting send. 3 is looser. *Recommended: 1.*
2. **Must the parent have been taken by a peer before its change is used?** *Recommended: yes.
   Without it K = 1 still lets a child sit on a parent that went nowhere.*
3. **When coins are waiting, wait or offer a way through?** The screen can only wait, or it can
   offer "send anyway" behind a warning. *Recommended: wait only. No override. An override is the
   train again.*
4. **How old must a never-relayed send be before the wallet releases it on its own?** One day
   today, and only by hand. *Recommended: make it automatic and keep one day for 4.1.6. Shorten
   it only after R4 exists, because a wrong early release can lead to paying twice.*
5. **Build "Cancel this send" (R4)?** It is the only release that does not rest on one node's
   answer, and it is a new signing path. *Recommended: yes, in the release after 4.1.6, with its
   own review.*
6. **May a confirmation reported by the pinned node (not yet proven by the wallet's own sync)
   open the gate?** It helps when sync is wedged. It also lets a server influence what the
   wallet spends. *Recommended: no for 4.1.6. Proven confirmations only.*
7. **Should a "Not sent yet" send raise a notification, or only show in the app (R2)?**
   *Recommended: in the app only. No notification for 4.1.6.*
8. **Show two figures on the home screen (balance and available now), or only on Send?**
   *Recommended: only on Send, and only when they differ.*
9. **Is the same rule wanted on iOS at the same time?** The rule lives in the shared core, so
   iOS gets the selection change with the pin. Its Send screen text is separate work.
   *Recommended: yes, core rule together; iOS text when that screen is next touched.*
10. **Budget for device test sends on the Note 8 for this item?** The tests need about 30 small
    sends. *Recommended: approve 30 sends of dust-safe amounts between the mule's own addresses.*

## 8. Things this draft did not verify

- That DigiByte Core counts the transaction itself in the ancestor limit of 25 (taken from
  Bitcoin Core's meaning). Needs a regtest run.
- Section 4 is from memory.
- Whether a height reported by a server makes a coin "confirmed" for selection today. The code
  reads that way (`core:BRWallet.c:1782`, `:2757-2765`). It matters for Q6 and should be measured.
- No device or emulator was used. No transaction was built or sent.
- The September note calls the home node blocksonly. Today it reports `localrelay: true`. One of
  the two is out of date.

## Critique (independent)

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
