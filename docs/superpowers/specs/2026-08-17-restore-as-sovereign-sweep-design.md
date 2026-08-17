# Restore as a sovereign sweep — design (PARKED, part 1 of 3 approved)

**Status:** Architecture approved in conversation 2026-08-17. Implementation deliberately
parked until the current fix branch ships (see §6). Sections 4-6 of the full design — state
isolation, scan bounding, and the transfer itself — are NOT yet designed.

**Supersedes** the discovery half of `restore-flow-asset-aware.md` §2 Part A: the
"filter-based rescan of the restored seed's addresses" described there is replaced by
temporary adoption, for the reason in §2.

---

## 1. Decisions taken

| Question | Decision |
|---|---|
| Does restore adopt a seed or move value? | **Asset-aware transfer into the wallet the app created.** Restore never permanently adopts a seed. |
| How is the old seed's money discovered? | **Temporarily adopt the seed and use the app's own BIP158 sync.** No index, no third party. |
| What happens while it scans? | **A blocking one-shot session** with progress, cancellable and resumable. The app wallet is paused. |
| The derivations adoption doesn't cover? | **Teach the native wallet the BIP44 recovery chains** so one scan covers every profile restore probes today. |

## 2. Why not the mechanisms we rejected

**Why not a filter scan over the restored seed's addresses (the original Part A plan).**
The native invariant is *credit iff derived* (`watched_credit_kat`): a watched address becomes
a BIP158 filter element, so a payment to it DOES get its block fetched, but `_BRWalletContainsTx`
and `_BRWalletUpdateBalance` gate on the derived-address set, which by explicit design never
contains watched entries. `BRWalletAddWatchedAddress` only rescues addresses it can pull into
a derived chain, bounded by `WATCH_RESOLVE_MAX_SPAN` — which cannot reach another seed's keys.
So the blocks would be fetched and the transactions silently discarded. A foreign-key scan
therefore needs its own native collection sink, and adoption gets the same result by making
the keys derived instead of watched.

**Why not an ElectrumX-style index query, given the sweep abandons the keys anyway.** The
sweep argument is sound as far as it goes — `restore-flow-asset-aware.md` §2 makes it — but
it holds only for a sweep that COMPLETES. Three cases break it:

- Asset UTXOs we fail closed on (unclassifiable) are deliberately not swept, so those keys
  keep value.
- **Moving assets costs DGB.** A wallet holding assets but no spare DGB cannot sweep them at
  all — the markers are dust. The address set is disclosed and the assets stay put.
- The user abandons the flow, or broadcast fails.

In each case the old keys keep living and the disclosure stops being transient. Separately,
Part A's real objection was never the protocol but the *default*: an endpoint hardcoded in
the binary becomes the convenient path for everyone, which is what makes it a map of every
wallet that ever restored.

**Why not require the user's own node.** It is the cleanest privacy story and instant for
users with infrastructure, but it makes restore unavailable to everyone else. Adoption gives
a sovereign default that works with no infrastructure at all.

## 3. Architecture (approved)

One new orchestrator, `RestoreSession` (`core/recovery/`), driving a PERSISTED state machine.
Persisted because a deep scan on a low-end device will be killed by Doze at least once, and a
half-finished restore must resume rather than restart — or, far worse, forget that it was
mid-sweep.

```
PREPARE   derive destination addresses from the app wallet; record them in the session
ENTER     load the restored seed as an EPHEMERAL native wallet; set the scan floor;
          enable the recovery derivation chains
SCAN      ordinary BIP158 sync; progress is the scanned range; funds appear as found
CLASSIFY  each discovered UTXO: plain DGB vs asset-bearing
PREVIEW   dry run — what moves, which assets, which quantities, where, at what fee
TRANSFER  DGB sweep first (asset outputs excluded UPSTREAM of selection), then per-asset
EXIT      zero the seed, drop the scratch namespace, reload the app wallet, report
```

`PREPARE` is first deliberately: the sweep needs only the app wallet's ADDRESSES, which are
strings. Recording them up front means two live wallets are never required — the thing that
would otherwise force a native restructure around the single `g_wallet` global.

**New:** `RestoreSession`; `RestoreScanBounds` (date hint → floor, pure and testable); an
ephemeral-wallet JNI entry that loads a seed without it becoming the app wallet; the native
recovery chains (`m/44'/20'/0'`, `m/44'/0'/0'`).

**Reused unchanged:** the CF sync; `AssetManager` classification including the
implicit-change resolution; `AssetCoinSelector` and the asset transfer path; `ForeignSweep` /
`LegacySweepService` for signing and broadcast.

**Deleted:** `UtxoSource`, `ReconcileBackendUtxoSource`, `RecoveryScanService`'s index
dependency, and the two address-set POSTs in `DgbNodeClient` (`reconcileAddresses`,
`addressHistoryBatch`). With adoption as discovery, nothing needs those endpoints to exist —
which is the removal Spec 3 Part A actually asks for.

**Highest-risk step: `EXIT`.** It is where a deliberately-loaded seed must be provably gone,
and where a scratch-namespace deletion runs next to the app wallet's real data — the same
neighbourhood as the `wipeStaleData` / SQLCipher crash-loop history. It gets explicit tests
rather than trust.

## 4. Not yet designed

- **State isolation.** Chain-global state (saved blocks, filter headers, the filter-header
  chain) can be shared between the two seeds — it describes the chain, not the wallet.
  Wallet-specific state (Room tx/UTXO/asset rows, `saved_transactions`, the scan floor, the
  CF scan ledger) needs a scratch namespace for the session, deleted on EXIT.
- **Scan bounding.** How deep by default, what the existing `RecoveryDateScreen` hint maps
  to, what happens when the user does not know, and how the UI reports "scanned this far"
  without ever implying "restored everything".
- **The transfer.** Fail-closed classification, the dry-run preview, per-asset audit
  logging, and partial-failure reporting — `restore-flow-asset-aware.md` §3 is the input.

## 5. Coverage note

Adoption derives BIP84, taproot `m/86'` and the legacy `m/0'` tree. Today's index scan probes
five profiles, including `m/44'/20'/0'` and the wrong-coin `m/44'/0'/0'`. Adding those as
derived chains during a restore session keeps coverage at parity — without it, the sovereign
path would silently find LESS money than the path it replaces, which would be a bad trade
whatever its privacy properties.

## 6. Why this is parked

Shipping the current fix branch first is partly how its own work gets verified: the
conflicted-send fix (`18 → 9`) can only be confirmed on the S25 Ultra via a signed release,
because that device runs a release-signed build and sideloading a debug APK over a live
wallet is not acceptable. The same applies to the onboarding change, which needs a fresh
install. There is also an unreleased memory-safety fix (a peer-reachable heap over-read in
`BRTxOutputIsAsset`) that should not wait behind a restore restructure, and bundling a change
to wallet loading and seed lifecycle with asset, privacy and peer work would make any
regression report span four unrelated subsystems.
