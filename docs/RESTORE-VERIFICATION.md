# Restore path verification

Which wallets this app can actually restore, proven against wallets funded on DigiByte mainnet.

Universal Restore probes six derivation conventions and declines a seventh. A DigiByte seed
produces completely different addresses depending on which wallet wrote it — same twelve words,
different derivation path or HMAC key, different coins — so restore has to try all of them. Until
2026-08-28, most of those probes had never been pointed at a wallet that held anything.

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
native/src/test/host/bread_wallet_addresses/run.sh --generate 3 bip44
```

Profiles: `bread`, `bread-std`, `bip44`, `bip44-btc`, `bip84`, `bip84-legacy`, `bip49`.

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
