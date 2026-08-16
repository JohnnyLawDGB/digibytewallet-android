# DigiAsset implicit-change resolution — design

**Status:** Approved to build 2026-08-16
**Supersedes the hypothesis in** `digiasset-balance-accounting.md` §1 (history-replay
accounting). That hypothesis is REFUTED — see §1.

---

## 1. What the investigation found

The wallet does **not** replay transaction history to tally assets. Balance is already
UTXO-derived: `UtxoDao.getAssetBalances` is `SUM(asset_quantity) WHERE is_asset=1 AND
spent=0 GROUP BY asset_id`, and `AssetManager.computeHeldAssetBalances` re-filters every
row against the native wallet's authoritative spent set. There is no debit/credit
bookkeeping to remove. The target architecture in the P0 spec §3 is the shipped
architecture.

The balances are wrong for a different reason: **the wallet ignores the DigiAssets
implicit-change rule.**

Verified against the reference implementation, `DigiAsset_Core/src/DigiByteTransaction.cpp`
`decodeAssetTransfer` (on digiscope.me at `/opt/DigiAsset_Core/`):

```cpp
//see if any change
size_t lastOutput = _outputs.size() - 1;
for (const vector<DigiAsset>& input: inputs) {
    for (const DigiAsset& asset: input) { ... addAssetToOutput(lastOutput, asset) ... }
}
```

After the explicit transfer instructions are applied, **every input unit they did not
assign is credited to the transaction's last output.** Bread-wallet-era and
digiasset-core-built transfers depend on this: they emit one instruction for the
recipient and let the change ride implicitly.

Two independent places in this codebase decide "which outputs carry assets", and both
count only explicit instructions:

| Site | Purpose | Consequence of the gap |
|---|---|---|
| `AssetTxQuantity.forOutput` (Kotlin) | per-UTXO display quantity | balance under-counts |
| `BRTxOutputIsAsset` (`BRDigiAsset.c`), used at `BRWallet.c:319` | routes an output to `wallet->assetUtxos` (unspendable) vs `wallet->utxos` (spendable DGB) | **an implicit-change asset output is spendable as ordinary DGB — spending it destroys the asset** |

### Evidence (live, v4.0.35, SM-N950U)

Device: `heldBalances: La4WAqZf=10(1u), La8knZNC=100(1u), La8T4Rwy=100(1u), La7TGGea=95(2u)`

Chain (DigiAsset Core via `api.digiscope.me/api/digiassets/address/…`):

| Asset | Wallet | Truth |
|---|---|---|
| `La4WAqZf…` | 10 | **100** — 90 @ DQcps2q + 10 @ DKjzexS |
| `La7TGGea…` | 95 | 95 held (5 sent to an address we don't own) ✓ |
| `La8knZNC…` / `La8T4Rwy…` | 100 / 100 | ✓ |

The broken one is tx `6aa6d5c92b2bf0d2368aaf718e596e84764a52ba7eaabbcd336b17a483d5a04f`:
OP_RETURN `6a0644410115000a` = one instruction, 10 units → vout 0. The input carried
100, so **90 units ride to vout 3** (10,000 sats, DQcps2q) — unspent today, credited
zero by the wallet, and sitting in `wallet->utxos` as spendable DGB.

The correct one is `db5480e1…`, built by this wallet's own `sendAsset`, which emits an
explicit change instruction (`5 → vout 0`, `95 → vout 2`). Wallet-built sends are
self-consistent; the defect only bites assets received from other tools.

### Escalation — a wrong quantity can burn assets on send

`sendAsset` builds the OP_RETURN from local `UtxoEntity.assetQuantity`
(`buildTransferInstructions` asserts `totalIn == recipient + change`). Over-state it and
the reference throws `exceptionInvalidTransfer`, which **clears every asset output** —
the whole input burns. Under-state it and the true remainder silently rides to the tx's
last output (our DGB change). Per-UTXO quantity is not display-only.

### Confirmed correct — do not change

`AssetTxQuantity`'s RANGE rule (`vout <= outputIndex`, `amount` to every output in
`0..outputIndex`) matches the reference exactly (`startI = range ? 0 : output`). Note the
reference *consumes* `(output + 1) * amount` from the inputs for a range instruction —
that asymmetry matters for the leftover arithmetic in §3.

Still missing, out of scope here: percent instructions
(`amount = inputCount * (byte + 1) / 256`).

---

## 2. Why native cannot fix this alone

Computing the leftover requires knowing how many units the *inputs* carried.
`BRTxOutputIsAsset` sees one transaction's bytes and nothing else. Resolving input units
in C means walking the transfer chain back to issuance — i.e. reimplementing the M3
parent-walk the Kotlin layer already has, plus a persistent per-outpoint quantity store,
which is the Room `utxos` table.

Blunt alternatives were considered and rejected:

- **"Last output of any DA transfer is asset-bearing" (fail-closed on shape).** Also
  excludes the DGB change of every wallet-built asset send — `db5480e1:3` is 6,100 sats
  of ordinary change that would become unspendable. Stranded DGB is not fund loss, but on
  a large send the stranded amount is unbounded, and nothing recovers it without another
  code change.
- **Dust-threshold heuristic.** Asset markers (700 / 6,000 / 10,000 sats historically) and
  small DGB change occupy the same range. No threshold separates them.

## 3. Design — Kotlin owns the accounting, native owns the exclusion set

**Kotlin is the asset brain.** It already resolves asset IDs (M3 walk), holds per-outpoint
quantities in Room, and knows which outpoints funded a transaction.

**Native gains a registration API** so Kotlin's conclusion can move an outpoint out of the
spendable DGB set:

```c
/** Move (txHash, n) out of wallet->utxos and into wallet->assetUtxos, adjusting balance.
 *  Idempotent. Returns 1 if the outpoint moved, 0 if it was already excluded/absent. */
int BRWalletRegisterAssetOutpoint(BRWallet *wallet, UInt256 txHash, uint32_t n);
```

exposed as `NativeBridge.registerAssetOutpoint(txidHex, vout)`.

### 3a. Kotlin: implicit-change quantity

New pure function beside `forOutput`:

```kotlin
/** Units the instructions do NOT assign, which the protocol credits to the tx's LAST
 *  output. Null when the input units are unknown — never guess. */
fun implicitChange(header: DecodedAssetHeader, inputUnits: Long?, outputCount: Int): Long?
```

`assigned = Σ over non-percent instructions of (if (range) (outputIndex + 1) * amount else amount)`,
burns included (a burn consumes its units). Any percent instruction → `null`. Then
`leftover = inputUnits - assigned`, credited to output `outputCount - 1` when positive.
The last output is used verbatim, including when it is the OP_RETURN — the reference
credits it there too, which is a burn, and we must mirror rather than "improve".

Input units are resolved from what we already hold:

```
for each (prevTxid, prevVout) of the tx's inputs:
    row = utxos[prevTxid, prevVout]        -> row.assetQuantity
    no row, funding tx has no DA OP_RETURN -> 0 units (plain DGB input)
    no row, funding tx IS a DA tx          -> UNKNOWN (bail, credit nothing)
    funding tx not retrievable             -> UNKNOWN
```

### 3b. Fail-closed spending, independent of display confidence

The two decisions are deliberately separated:

- **Display** credits only what we can prove. Unknown input units → credit nothing (today's
  behaviour), never a guess.
- **Spending** fails closed. Whenever a DigiAsset transfer's leftover is positive *or
  unknown*, the last output is registered as an asset outpoint so plain-DGB coin selection
  cannot reach it — even though we may not be able to display its quantity.

An unswept output is recoverable; a destroyed asset is not.

### 3c. Replay on wallet load

Native rebuilds its UTXO set from the tx set on every wallet load, discarding the
registration. The asset rows survive in Room, so the registration is replayed from Room
after wallet load, gated by the existing `AssetMaintenanceGate` so a send cannot race
ahead of the replay.

---

## 4. Acceptance criteria

1. **Red-before-green host KAT** (`native/src/test/host/asset_implicit_change_kat`) built
   from the real bytes of `6aa6d5c9…` and `db5480e1…`:
   - `6aa6d5c9:3` is excluded from the spendable set (RED today: it is spendable).
   - `6aa6d5c9:0` (explicit instruction) stays excluded — the fix must not work by
     blanket-excluding everything.
   - a plain non-asset tx's outputs stay spendable.
   - registration is idempotent and ASan-clean.
2. **Kotlin unit tests** at the `implicitChange` / input-resolution seams, using the real
   instruction bytes: `6aa6d5c9` credits 90 to vout 3 (RED today: 0); `db5480e1` credits 0
   (fully assigned); a percent instruction yields `null`, not a number.
3. On-device: `La4WAqZf` reads **100**, matching the explorer, and the other three assets
   are unchanged at 95/100/100.
4. Balance survives a forced resync with no divergence.
5. No plain-DGB coin selection can reach an asset-bearing output, implicit or explicit.

## 5. Out of scope

- Percent transfer instructions (still skipped; under-count, never over-count).
- Multiple asset IDs on one outpoint (the `(txid, vout)` primary key still collapses to one).
- Removing the dead `/api/assets/*` backend call — separate branch, see
  `project_digiscope_asset_endpoints_dead`.
