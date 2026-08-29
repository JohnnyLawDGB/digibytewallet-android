# Restore path verification

Which wallets this app can actually restore, proven against wallets funded on DigiByte mainnet.

Universal Restore probes six derivation conventions and declines a seventh. A DigiByte seed
produces completely different addresses depending on which wallet wrote it — same twelve words,
different derivation path or HMAC key, different coins — so restore has to try all of them. Until
2026-08-28, most of those probes had never been pointed at a wallet that held anything.

## What kinds of value

A DigiByte wallet holds three different things and they are not interchangeable. Getting the
derivation right is only half the job — a scan that finds the right addresses can still leave
value behind if it does not understand what is sitting on them.

| Kind | Lives at | What recovery does | Status |
|---|---|---|---|
| **Plain DGB** | any scanned profile | Swept into the destination in one transaction per profile. | **proven** |
| **DigiAsset** | a specific UTXO + OP_RETURN marker | Held back from the sweep — spending it as DGB destroys it — then moved in its own transaction, before the sweep, while the fee money is still there. | **proven** |
| **DigiDollar** | `m/86'/20'/0'`, zero-value P2TR | Found via a separate DD-aware lookup, then moved in its own Taproot transaction before the sweep. | **proven** |

### DigiDollar was invisible, not broken

A DigiDollar token output carries **zero satoshis** — the cents live in the transaction's
OP_RETURN — and the reconcile endpoint filters zero-value outputs out of the UTXO set entirely.
Measured on mainnet: an address holding $1.00 answers `balance 0, utxo_count 0` through the
ordinary lookup. The scan also derived P2PKH, P2WPKH and P2SH-P2WPKH only, so `m/86'` addresses
were never asked about at all.

So a wallet was emptied of its DGB and its assets and told nothing about its dollars — "no funds
found" about money that exists, one layer up.

Three things had to change: a Taproot address format in the derivation, a separate DD-aware lookup
(the backend has one, keyed by the `DD…` encoding), and a transfer builder that a foreign seed can
sign. The last needed no new cryptography — `BRTransactionSign` already matches P2TR inputs by
their taproot output key and BIP341-signs them, and BIP86 is plain BIP32 under the standard HMAC.
Both were pinned in a KAT *before* the flow was designed on top of them.

```
9194b9fe07c7864e…   version 0x02000770   19 confirmations

vout0  witness_v1_taproot   the dollars
vout1  0.16 DGB change
vout2  nulldata 6a02444401020164

the chain's own decoder: { type: TRANSFER, decoded: true, total_dd_cents: 100 }
source wallet: 0 cents, 0 unspent
```

The fee is a **consensus floor of 0.1 DGB**, roughly twice a DigiAsset transfer — so a wallet can
comfortably move its assets and still be unable to move its dollars. Recovery refuses early with
the figure rather than letting the network reject a signed transaction, and reports the balance
either way: dollars found and not moved are still dollars you should know about.

## Which derivation

| Profile | Path | HMAC | Format | Status |
|---|---|---|---|---|
| BIP84 DGB *(this wallet)* | `m/84'/20'/0'` | Bitcoin seed | `dgb1q…` | **proven** |
| Legacy DigiByte mobile *(pre-BIP84 BreadWallet)* | `m/0'` | **DigiByte seed** | `D…` | **proven** |
| BIP44 DGB *(Coinomi, Trezor, Ledger)* | `m/44'/20'/0'` | Bitcoin seed | `D…` | **proven** |
| BIP84 key, legacy encoding *(Legacy receive tab)* | `m/84'/20'/0'` | Bitcoin seed | `D…` | **proven** |
| Legacy `m/0'` standard HMAC *(early non-DGB forks)* | `m/0'` | Bitcoin seed | `D…` | untested |
| BIP44 wrong-coin *(seed typed into a BTC wallet)* | `m/44'/0'/0'` | Bitcoin seed | `D…` | untested |
| BIP49 wrapped segwit | `m/49'/20'/0'` | Bitcoin seed | `S…` | **refuses — honestly** |

---

## The refusal that matters most

BIP49 is the one path this wallet cannot sweep. The lookup backend rejects `S…` addresses outright
— `{"error":"invalid address"}` — and one bad address fails the whole request, so the profile
cannot even be surveyed.

The question was never whether it recovers. It was what it *says*. "No recoverable funds found",
reported about a wallet that visibly holds money, is the worst message this feature could produce,
and it was entirely plausible: an unanswerable lookup is easy to read as an empty one.

```
wallet holds       0.01 DGB on m/49'/20'/0'/0/0  (confirmed, untouched)

the app says       Couldn't finish checking
                   "Some derivation paths could not be checked, so this is
                    not a final answer — there may be funds we haven't seen."
                   Unchecked: BIP49 DGB (P2SH-wrapped segwit)
```

It names the path it could not check and refuses to call the answer final. "Some paths could not
be checked" is treated as its own outcome, distinct from both "no funds" and "everything is down"
— the rule in `PartialScanFailureTest`, written against an earlier bug, holding on live money.

---

## Fifty assets, one output

An asset moves in its own transaction and two transactions cannot spend the same UTXO, so moving
*N* assets needs *N* spendable outputs — and a transfer's change goes to the destination, never
back to fund the next. Holders with 50+ assets and a single UTXO are common. Until v4.0.71 such a
wallet moved exactly one asset and stranded the rest.

Combining assets into one transaction does not fix it: an instruction costs ~2 bytes and the
80-byte OP_RETURN holds roughly 38 against 50 needed, and it would concentrate every asset onto
one output where a single plain-DGB spend destroys all of them. Splitting the DGB first keeps each
asset in its own transaction.

```
wallet             3 assets, 1 spendable output

fan-out            1 input -> 3 fee outputs, fee 30,600
                   c7c262159bfa5d17…   — then waited 19s and re-scanned
moved              ec7deca10005f59b…
                   cbb7064da98db061…
                   d8c24c33d77b7e2b…
swept              0.04785317 DGB
source wallet      0 UTXOs

before v4.0.71     1 asset moved, 2 stranded with no DGB behind them
```

The split waits for a confirmation rather than chaining — DigiByte inherits Bitcoin's
`limitdescendantcount`, so dozens of unconfirmed children of one parent are rejected. It then
re-scans rather than predicting the new UTXO set, and the loop is bounded at one split because
after one there is an output per asset.

Cost for fifty assets: 61,361 sats each plus a 190,400-sat split fee — about **0.033 DGB**.
Verified with three; the arithmetic is linear from there.

## Run A — BIP44, and the change chain

The derivation Coinomi, Trezor, Ledger and Atomic produce, so it carries more real migration
traffic than anything else here. Funded deliberately awkwardly: receive index 0, receive index
**2** with nothing on 1, and — for the first time — an output on the **change chain**.

```
profile detected   BIP44 DGB — m/44'/20'/0'
found              0.15006 DGB across …/0/0, …/0/2 and …/1/0
asset moved        53d13136239b559e…  funded by 0.05, fee 54,700
swept              57eade41887a76ac…  0.10 DGB
source wallet      0 UTXOs
```

The change output had never been tested. Every earlier run funded receive addresses only, yet any
wallet that has ever spent has coins on `…/1/i`. A scan walking only chain 0 would have reported
0.10 DGB and left a third of the money behind — confidently, with no error.

## Run B — Legacy BreadWallet

The `"DigiByte seed"` HMAC is the entire identity of the bread-era derivation and the one detail
third-party tools get wrong, so this path could only be tested with a wallet built for it. Funds
were placed on **three separate receive indices**.

```
profile detected   Legacy DigiByte mobile wallet — m/0'
found              0.60006 DGB across m/0'/0/0, /1, /2
asset moved        33972374b1efd6ef…  funded by the 0.05 output, fee 54,700
swept              40c0132b1b475c34…  0.55 DGB
source wallet      0 UTXOs
```

Also proved assets can move off a legacy P2PKH wallet, not just a native segwit one — different
derivation, different address format, different signing path.

## Run C — BIP84 key in legacy encoding

A user of this wallet who received on the Legacy `D…` tab. Same key, written the other way.
Recovery once answered "no funds found" about coins that existed because of exactly this.

```
found              0.1 DGB under "BIP84 DGB (current wallet)"
swept              333c77a8d3cd0ae7…
source wallet      0 UTXOs
```

## Run D — Two assets, one fee pool

Each asset moves in its own transaction, so several assets share one pool of DGB without
colliding. The allocator draws smallest-first; the sweep takes what the moves did not spend.

```
2 assets, 5 plain outputs (0.04 · 0.05 · 0.30 · 0.45 · 0.70)

asset A moved      0debe97d799f1b89…  funded by 0.04 — smallest first
asset B moved      431f0f4d72fc27bd…  funded by 0.05
swept              ac4965ac9cd75c81…  1.45 DGB — the exact complement
source wallet      0 UTXOs
```

Log line that matters: `2 outpoint(s) already claimed by the DigiAsset move(s); sweeping 3`.
Nothing estimated — the plans are built first, so what they spend is a fact and the sweep is
defined as everything else.

---

## Why assets move before the sweep

Until v4.0.69 the sweep ran first, which forced something to hold DGB back so the assets could pay
their own transfer fee. That hold-back had to guess the fee *before the transfer existed*. It
shipped at 40,000 sats against a real cost of 54,900–70,100, and its own comment called it
"deliberately an over-estimate" while being an under-estimate.

Moving assets first deletes the guess rather than correcting it, and fixes the failure mode: a
move that fails now leaves the wallet untouched, so the retry has everything, instead of leaving
only a reserve that might be too small to move the asset at all.

Net **−469 lines** — the reserve, its constant, three test files and four strings in thirteen
languages, replaced by a set of already-spent outpoints.

---

## Building a test wallet

Most of these derivations cannot be created by any current software — nothing makes a bread-era
wallet any more, and this app only creates BIP84. Test wallets are generated with the same C the
scan uses:

```
native/tools/wallet-addresses/run.sh --generate 3 bip44
```

Profiles: `bread`, `bread-std`, `bip44`, `bip44-btc`, `bip84`, `bip84-legacy`, `bip49`, `bip86`.
The `bip86` profile prints both the `dgb1p…` and `DD…` encodings of the same taproot key —
DigiDollar only accepts the latter, while a scan looks the output up by the former.

`pubkey_to_address` is copied verbatim from `jni_derive.c` rather than reimplemented. If the tool
disagreed with production, the addresses it prints would not be the addresses the scan looks for,
and a funded test wallet would simply scan as empty.

Deriving with a third-party tool would be worse: they assume the `"Bitcoin seed"` HMAC, and the
bread profile's `"DigiByte seed"` HMAC is exactly the detail they get wrong — producing addresses
that look correct and are not.

> **`--generate` produces a real mainnet seed.** Entropy comes from `/dev/urandom` and exists in
> one place only. Anything sent to those addresses is recoverable through that phrase and nothing
> else. It deliberately refuses to hand back a published test vector: the
> `abandon abandon … about` mnemonic is the best-known seed in existence and deposits to it are
> swept by bots within seconds.

---

## Still unproven

Two rare paths remain: `m/0'` under the standard HMAC (early non-DGB forks) and the wrong-coin
`m/44'/0'/0'` accident. Both are real; neither is common.

**BIP49 funds stay stranded** even though the refusal is honest. The coins are recoverable — the
phrase is a standard BIP49 mnemonic any wrapped-segwit wallet can restore — but not by this app.
Supporting it needs both a sweep path and a lookup backend that accepts `S…` addresses.

Every transaction identifier above is on DigiByte mainnet and can be checked independently. Test
wallets were funded, drained and discarded.
