# DigiDollar Wire-Format Spec (SHOW + SEND) — Byte-Level, Pinned

**Status:** authoritative recon synthesis. Fund-safety-critical. Format pinned from DigiByte
"rc25 / DigiDollar Edition" **source** (source wins over any recon summary). Every load-bearing
claim cites `file:line`. Uncertain bytes are marked `⚠️ NEEDS-TESTNET26`.

**Source tree:** `/home/polloloco/digibyte-rc25-src/src/` (all `file:line` below are relative to `src/`).
**Scope:** port **SHOW** (detect/decode DD balances) + **SEND** (transfer, tx type 2). **MINT** and
**REDEEM** are OUT of scope to *build*, documented enough to **identify and ignore** (a DD-holding
wallet receives mint token outputs and redeem-change outputs on chain).

**Amount unit throughout:** DigiDollar **cents** (integer). `100 cents = $1.00`
(`consensus/digidollar.h`: `minMintAmount=10000`=$100, `minOutputAmount=100`=$1). DD amounts never
appear in `nValue`; every DD token output carries `nValue == 0` satoshis.

---

## 0. One-screen summary (the pins SHOW/SEND depend on)

```
DD tx detect:   (nVersion & 0x0000FFFF) == 0x0770
DD tx type:     (nVersion >> 24) & 0xFF        // 1=MINT, 2=TRANSFER, 3=REDEEM, 0=not-DD
DD flags:       (nVersion >> 16) & 0xFF        // reserved, expect 0

nVersion (int32, serialized LE as the FIRST 4 tx bytes):
   MINT     0x01000770  ->  70 07 00 01   (dec 16,779,120)
   TRANSFER 0x02000770  ->  70 07 00 02   (dec 33,556,336)   <- our SEND
   REDEEM   0x03000770  ->  70 07 00 03   (dec 50,333,552)

DD token output (34 bytes, nValue=0):   51 20 <32-byte x-only taproot output key>
   (plain BIP-86 key-path P2TR; byte-identical to a normal dgb1p... taproot output)

TRANSFER OP_RETURN:   6a 02 44 44 01 02 <amt0><amt1>...<amtN>
   header is ALWAYS  6a 02 44 44 01 02   ; each amt = minimal CScriptNum push of cents
   amount[k] binds to the k-th zero-value 34-byte OP_1 output (vout order); NO count field

DD address:   Base58Check( <2-byte version> || <32-byte taproot output key> )
   mainnet {0x52,0x85}="DD"  testnet {0xb1,0x29}="TD"  regtest {0xa3,0xa4}="RD"
   the 32 bytes ARE the final tweaked output key — SEND emits them verbatim, NEVER re-tweaks
```

---

## 1. nVersion marker (32-bit layout + type values) — PINNED

### 1.1 Bit layout

Masks and marker constant (`primitives/transaction.h:45-50`, verified this pass):

```c
static const int32_t DD_TX_VERSION  = 0x0D1D0770;  // "DigiDollar" mnemonic; only low 16 bits load-bearing
static const int32_t DD_VERSION_MASK = 0x0000FFFF;  // low 16 = marker
static const int32_t DD_TYPE_MASK    = 0xFF000000;  // high 8  = tx type
static const int32_t DD_FLAGS_MASK   = 0x00FF0000;  // bits 16-23 = flags (reserved)
```

```
 bit: 31          24 23          16 15                        0
      +-------------+--------------+---------------------------+
      |  TX TYPE(8) |  FLAGS(8)    |        MARKER(16)         |
      +-------------+--------------+---------------------------+
       0xFF000000     0x00FF0000        0x0000FFFF
   MARKER must equal 0x0770.  FLAGS = 0 on every builder path.  TYPE in {1,2,3}.
```

`nVersion` is a **signed `int32_t`** (`primitives/transaction.h:336` immutable, `:434` mutable).
For types 1/2/3 bit 31 is 0, so the value is a small positive int and the arithmetic shifts are safe.

### 1.2 The marker is `0x0770` (low 16 bits), NOT the full `0x0D1D0770`

`MakeDigiDollarVersion` ORs in only `(DD_TX_VERSION & DD_VERSION_MASK)` = `0x0770`
(`primitives/transaction.h:53-58`, verified):

```c
inline int32_t MakeDigiDollarVersion(DigiDollarTxType type, uint8_t flags = 0) {
    return (static_cast<int32_t>(type)  << 24)
         | (static_cast<int32_t>(flags) << 16)
         | (DD_TX_VERSION & DD_VERSION_MASK);   // 0x0D1D0770 & 0xFFFF = 0x0770
}
```

Detection masks to 16 bits (`primitives/transaction.h:416-418`, verified):
`(tx.nVersion & DD_VERSION_MASK) == (DD_TX_VERSION & DD_VERSION_MASK)` → `(nVersion & 0xFFFF)==0x0770`.
Consensus twin `HasDigiDollarMarker` uses the identical rule with a locally re-declared
`DD_TX_VERSION=0x0D1D0770` (`consensus/digidollar.cpp:225-233`, verified). The inline
detect-and-classify in the redeem collateral guard uses the raw form
`(prev_tx->nVersion & 0xFFFF) != 0x0770` then `((nVersion>>24)&0xFF) != 0x01`
(`digidollar/validation.cpp:1768-1771`). **Test for `0x0770`, never `0x0D1D0770`.**

### 1.3 Type extraction & enum values

`GetDigiDollarTxType` = `(nVersion & 0xFF000000) >> 24` (`primitives/transaction.h:420-425`;
consensus twin `consensus/digidollar.cpp:235-243`, both verified). Enum (identical in two headers,
`primitives/transaction.h:38-44` and `consensus/digidollar.h:33-38`, verified):

```c
enum DigiDollarTxType : uint8_t {
    DD_TX_NONE=0, DD_TX_MINT=1, DD_TX_TRANSFER=2, DD_TX_REDEEM=3, DD_TX_MAX=4
};
```

ERR (Emergency Redemption Ratio) is **not** a separate type — folded into REDEEM=3, distinguished by
burn amount not by nVersion (`consensus/digidollar.h:33-38` comment; `primitives/transaction.h:34-36`).
There are exactly **3 on-chain type values**.

### 1.4 Serialization, endianness, txid commitment

`nVersion` is the FIRST field serialized (`primitives/transaction.h` `SerializeTransaction`), written LE
via `htole32`. On the wire a DD tx **begins with `70 07 00 0X`** (X=type). A byte detector may match
`70 07 00` at offset 0 then read byte[3] as type. **`nVersion` is inside the txid/wtxid pre-image**
(hashed first), so the DD tag is consensus-committed and tamper-evident — a relay node cannot strip it
without changing the txid.

### 1.5 Flags (bits 16-23) — reserved, 0 today

`GetDigiDollarFlags` = `(nVersion & 0x00FF0000) >> 16` (`primitives/transaction.h:427-432`, verified).
All builders default `flags=0`. **Mirror consensus, not the exact word:** classify on
`marker==0x0770 && type==2`, do NOT require the whole word to equal `0x02000770` — a nonzero flags byte
would still be a valid transfer per consensus. ⚠️ NEEDS-TESTNET26: confirm flags are always 0 on-chain.

### 1.6 Landmines (do NOT copy)

- `digidollar/validation.h:31` declares a **stale/dead** `DigiDollar::DD_TX_VERSION = 0x44440000`
  with a `0x4444XXYY` "format" comment. `0x44440000 & 0xFFFF = 0x0000 ≠ 0x0770` — it detects nothing.
  No production code reads it. **Ignore entirely.** (The `4444`/"DD" bytes are real only as the ASCII
  push *inside* the OP_RETURN, §2 — never in nVersion.)
- Some *test* files build markers with the stale constant / wrong bit positions
  (`test/digidollar_wallet_tests.cpp:3035`, `test/digidollar_timelock_tests.cpp:2498`) →
  `0x44440002` etc. which production would reject. Do not use tests as a byte-layout reference; trust
  `MakeDigiDollarVersion`/`GetDigiDollarTxType`.

---

## 2. OP_RETURN metadata — TRANSFER(2) byte-by-byte (MINT/REDEEM for identify-and-ignore)

### 2.1 Push mechanics (the fund-safety crux) — PINNED

`CScript::operator<<` (`script/script.h:465-490`, verified):
- `<< OP_RETURN` → single byte `0x6a` (opcode overload, `:457`).
- `<< std::vector<uchar> v` with `v.size() < OP_PUSHDATA1(0x4c)` → length byte `v.size()` then bytes.
- `<< CScriptNum(n)` → `<< n.getvch()` → a **length-prefixed data push** of the minimal
  little-endian sign-magnitude bytes (`:465-469` → `getvch` → `serialize`).

`CScriptNum::serialize` (`script/script.h:353-384`, verified):
```
value == 0            -> []  (empty push)
else emit LE bytes of |value|; then
   if top byte & 0x80: append 0x00 (positive) / 0x80 (negative)   // sign-bit padding
   elif negative:      top byte |= 0x80
```

**THE PIN:** every DD scalar (`txType`, every amount) is wrapped in an explicit `CScriptNum(...)`, so
it is a **length-prefixed data push, never an `OP_N` opcode**. `txType=2` is `01 02` (push 1 byte
0x02), **not** `0x52`(OP_2). An amount of `1` cent is `01 01`, not `0x51`(OP_1). A decoder that
compares against small-int opcodes fails every real DD tx. Decode via `GetOp`→`CScriptNum(data,...)`.
Verified amount encodings (authoritative, computed against `serialize`):
```
      1 c -> 01 01          100 c -> 01 64          128 c -> 02 80 00   (sign pad)
     50 c -> 01 32          200 c -> 02 c8 00       300 c -> 02 2c 01
   2500 c -> 02 c4 09      5000 c -> 02 88 13     10000 c -> 02 10 27
  12345 c -> 02 39 30     30000 c -> 02 30 75     67890 c -> 03 32 09 01
  75000 c -> 03 f8 24 01                      10000000 c -> 04 80 96 98 00   ($100k, per-output max)
```
Note sign-bit padding: whenever the top magnitude byte ≥ 0x80 a `0x00` is appended, so $2.00 = 200c is
**two** data bytes `c8 00`. Use real CScriptNum decode, not a fixed-width read.

### 2.2 TRANSFER (type 2) — the SEND/SHOW target

Emitter (`digidollar/txbuilder.cpp:750-770`, verified this pass):
```cpp
std::vector<CAmount> ddOutputAmounts;
for (const auto& [address, amount] : params.recipients) ddOutputAmounts.push_back(amount);
if (ddChange > 0 && ddChange >= minOutput) ddOutputAmounts.push_back(ddChange);   // change LAST
CScript metadataScript;
metadataScript << OP_RETURN << std::vector<unsigned char>{'D','D'} << CScriptNum(2);
for (CAmount amt : ddOutputAmounts) metadataScript << CScriptNum(amt);            // one push per DD output
tx.vout.push_back(CTxOut(0, metadataScript));
```

Wire layout of the transfer OP_RETURN scriptPubKey:
```
6a                 OP_RETURN
02 44 44           push "DD"  (0x44='D', 0x44='D')
01 02              push CScriptNum(2)   -> txType = TRANSFER
<len><le bytes>    push CScriptNum(amount_0)   DD cents for the 0th DD output (first recipient)
<len><le bytes>    push CScriptNum(amount_1)   ... 1st DD output (recipient 1, or DD change)
...                one push per zero-value P2TR DD output, in vout order; NO trailing count
--- nValue = 0 ---
```
Header is **always** `6a 02 44 44 01 02`. Worked example — 2 recipients $50.00 + $25.00, no change:
```
6a 02 44 44 01 02  02 88 13  02 c4 09      (5000c=0x1388→88 13; 2500c=0x09c4→c4 09)
```
With DD change $3.00 appended (300c=0x012c→2c 01):
```
6a 02 44 44 01 02  02 88 13  02 c4 09  02 2c 01
```

Parser (`digidollar/validation.cpp:1087-1118`, verified this pass), the authoritative decode:
1. Scan `tx.vout`; take the **first** OP_RETURN whose first push is exactly the 2 bytes `44 44`.
2. Read next push as `CScriptNum(data, /*minimal*/true)` (default max 4 bytes); require `getint()==2`,
   else `continue` to next output.
3. `while GetOp(...)`: for each remaining push with `data.size()>0`, append
   `CScriptNum(data, /*minimal*/true, /*max*/8).GetInt64()` to `dd_amounts`. Then `break`.
4. Empty pushes (`data.size()==0`, e.g. OP_0) are **skipped**, not counted (builder never emits one —
   amounts are always >0 → non-empty). No count field is consumed.

### 2.3 CRITICAL: the `<output_count>` field does NOT exist

The builder **comment** at `digidollar/txbuilder.cpp:751` says
`OP_RETURN <"DD"> <txType> <output_count> <amount1>...` — **the code emits no count** (`:760-768`),
and no decoder reads one (`validation.cpp:1104-1115`; wallet SHOW `digidollarwallet.cpp:6693-6699`).
Trust the code. **Do NOT consume a count byte** or every amount shifts by one → total misalignment.

### 2.4 Amount ↔ output positional coupling (the SHOW crux) — see §3

### 2.5 MINT (type 1) — identify & ignore

Emitter (`digidollar/txbuilder.cpp:365-372`):
```
6a 02 44 44 01 01 <push ddAmount> <push lockHeight> <push lockTier> 20 <32-byte owner x-only pubkey>
```
Field order: `DD, type=1, ddAmount, lockHeight, lockTier, ownerXOnly(32)`.
- `01 01` = txType 1. `ddAmount` = minimal CScriptNum cents (the **FIRST** push after type = the amount).
- `lockHeight` = absolute block height (large, e.g. 22.5M → ~4-5 data bytes). **NOT an amount.**
- `lockTier` = 0-9. **NOT an amount.** Tier 0 serializes empty → push length byte `00` (i.e. `00`).
- Owner pubkey = `20` + 32 bytes.
- **Identify rule:** `type==1` from nVersion, or OP_RETURN begins `6a 02 44 44 01 01`. For SHOW, if we
  ever value a mint's DD token output, its amount is the **first push only** (§3 type-aware rule).

### 2.6 REDEEM (type 3) — identify & ignore

Emitter, only when there is DD change (`digidollar/txbuilder.cpp:1199-1204`):
```
6a 02 44 44 01 03 <push ddChange>
```
- `01 03` = txType 3; a single amount push = the DD change.
- **Caveat:** the redeem OP_RETURN is emitted **only if `ddChange > 0`**. An **exact-burn** redeem
  (`ddChange==0`) has **NO DD OP_RETURN at all** — absence of a DD OP_RETURN does NOT imply "not DD".
  Recognize such a redeem by its nVersion marker (`type==3`); we simply observe our DD UTXO spent.

### 2.7 Type decision table (decoder)

| Header prefix | Type | Fields after type | Amount(s) for SHOW |
|---|---|---|---|
| `6a 02 44 44 01 01` | MINT (1) | ddAmount, lockHeight, lockTier, owner(`20`+32) | **first** push only |
| `6a 02 44 44 01 02` | TRANSFER (2) | amount0..amountN (one per zero-value P2TR out) | **all** pushes |
| `6a 02 44 44 01 03` | REDEEM (3) | ddChange (only if change>0) | **first** push only |
| (no DD OP_RETURN) + nVersion type 3 | exact-burn REDEEM | — | none |

The MINT/REDEEM-vs-TRANSFER split is a **consensus security rule** (`validation.cpp:224-253`,
verified): reading a MINT's `lockHeight` as an amount inflates supply (a 360-day lockHeight 2,073,600
misread as $20,736). MINT/REDEEM = first push only; TRANSFER = all pushes.

### 2.8 Legacy / alternate OP_RETURN shape (defensive read only)

`ExtractDDAmount` (`digidollar/validation.cpp:89-155`) also recognizes
`OP_RETURN OP_DIGIDOLLAR(0xbb) <8-byte little-endian amount>` (fixed 8-byte LE, NOT CScriptNum;
`OP_DIGIDOLLAR=0xbb` at `script/script.h:210`, verified). **No builder in this tree emits it.** Our
SEND must emit the `"DD"` CScriptNum form. Our SHOW may read `6a bb ...` defensively.
⚠️ NEEDS-TESTNET26: confirm whether any live DD tx uses the `0xbb` form or it is dead.

---

## 3. DD token output structure + amount↔output association (the SHOW-decoder crux)

### 3.1 The output — PINNED

A DD token output is a **plain zero-value BIP-86 key-path P2TR**, 34 bytes, `nValue == 0`:
```
51                 OP_1  (witness v1; OP_1=0x51, script/script.h:80 verified)
20                 push 32 bytes
<32 bytes>         x-only taproot OUTPUT key Q = P + H_TapTweak(P)*G  (empty merkle root, BIP-86)
--- nValue = 0 satoshis ---
```
There is **no DD opcode, marker, amount, or commitment in the output script.** It is byte-identical to
a normal `dgb1p...` taproot output. Built:
- transfer recipient: `ddScript << OP_1 << ToByteVector(*taproot)`; `CTxOut(0, ddScript)`
  (`digidollar/txbuilder.cpp:665-667`) — key taken **verbatim** from the decoded DD address, **no
  re-tweak** ("already a fully-formed Taproot output key", `:662-664`).
- transfer DD change: `CreateTapTweak(nullptr)` on the spender pubkey, then `OP_1 << tweaked_key`,
  `CTxOut(0, ...)` (`digidollar/txbuilder.cpp:684-698`, verified) — BIP-86 tweak because the spender
  holds the internal key.
- mint token / redeem change: shared `CreateDigiDollarP2TR(owner)` which applies `CreateTapTweak(nullptr)`
  internally (`digidollar/scripts.cpp:175-212`).

All four routes yield the same shape. **The SHOW decoder must never assume any DD-specific tweak — a DD
output is a bog-standard BIP-86 P2TR.**

### 3.2 The association rule — CANONICAL (consensus) — PINNED

`ExtractDDAmountFromTxRef` (`digidollar/validation.cpp:171-292`, verified this pass) is the source of
truth. Two phases:

**Phase A — amounts from OP_RETURN, gated + type-aware (`:180-256`):**
- Reject if `prev_tx->IsCoinBase()` (`:180`, attack T5-02).
- Reject if `!HasDigiDollarMarker(*prev_tx)` (`:191`) — the creating tx must itself carry the DD marker.
- First OP_RETURN whose first push == `"DD"`; read `txType`; then:
  `if txType==1||txType==3` → amounts = `[first push only]`; `else (==2)` → amounts = `[all remaining
  pushes]`. Each push `CScriptNum(data, true, 8)`. Wrapped in try/catch here (throws are swallowed).

**Phase B — bind amount to output by position (`:264-287`):**
```
dd_output_idx = 0
for n in 0..prev_tx.vout.size():
    if vout[n].scriptPubKey[0] == OP_RETURN: continue    // skip metadata
    if vout[n].nValue != 0:                  continue    // skip DGB change, mint collateral
    if scriptPubKey.size()==34 && scriptPubKey[0]==OP_1: // a DD (zero-value P2TR) output
        if n == prevout.n:
            if dd_output_idx < dd_amounts.size(): amount = dd_amounts[dd_output_idx]; return amount > 0
        dd_output_idx++                                   // advance for EVERY DD output, matched or not
```

**THE RULE (state it exactly):** enumerate `tx.vout` in ascending index order with a counter `k=0`.
Skip an output if `scriptPubKey[0]==OP_RETURN(0x6a)`; skip if `nValue != 0`; otherwise if it is exactly
34 bytes beginning with `OP_1(0x51)` it is a **DD output** whose amount is `dd_amounts[k]`, then `k++`.
The counter advances on **every** qualifying zero-value P2TR output regardless of ownership. Amounts
bind by **DD-output ordinal, not raw vout index.** OP_RETURN and every non-zero-value output are
transparently skipped and consume no amount slot. This is why a transfer's DGB fee-change (non-zero
value, usually P2WPKH) and a mint's collateral (non-zero value) never steal an amount.

### 3.3 Wallet SHOW decoder mirror (`digidollarwallet.cpp:6656-6765`)

`DetectIncomingDDOutputs` mirrors 3.2 but its Phase-A gate accepts `type==2 || type==3` and collects
*all* pushes for both (not strictly type-split for redeem). In practice a redeem emits a single amount,
so they agree. Ownership test: the output's 32-byte key (bytes `[2..34)`) is in the wallet's
`dd_address_keys` map, OR `IsMine & ISMINE_SPENDABLE`; the descriptor fallback re-derives via
`XOnlyPubKey.CreateTapTweak(nullptr)` and compares to the output key (`digidollarwallet.cpp:682-701`),
confirming BIP-86. **Implement the stricter consensus rule (§3.2): MINT/REDEEM=first push only,
TRANSFER=all pushes.**

### 3.4 Fund-safety consequences

- A DD output carries **0 satoshis** (`txbuilder.cpp:667`). A naive SPV wallet that drops 0-value
  outputs as dust loses sight of DD; one that treats any P2TR paying its key as spendable DGB will try
  to spend a DD token as DGB. Gate DD value on the tx marker + OP_RETURN, never on `nValue`.
- **A lone `51 20 <32>` output is NOT classifiable as DD by script alone.** Classification REQUIRES the
  parent tx context: `(nVersion & 0xFFFF)==0x0770` AND positional correspondence to a `"DD"` OP_RETURN
  amount. Never credit a bare zero-value P2TR without its parent tx.
- The node's `RegisterScriptMetadata` / in-memory registry is a Phase-1 dev shim (`scripts.cpp:216-248`)
  that does not exist on-chain — useless to SPV. Reconstruct amounts **solely** from the OP_RETURN.

---

## 4. DD address format — PINNED

**Base58Check, NOT bech32.** `CDigiDollarAddress` lives in `base58.h:50-96` / `base58.cpp:176-325`
(NOT `key_io.h` — that file has no DD symbol). Payload = `<2-byte version> || <32-byte taproot output
key>` = 34 bytes; Base58Check appends a 4-byte **double-SHA256** checksum.

Version prefixes (`base58.cpp:180-182`, verified this pass):
```
mainnet {0x52, 0x85}  -> string prefix "DD"
testnet {0xb1, 0x29}  -> "TD"
regtest {0xa3, 0xa4}  -> "RD"
```

Decode ctor (`base58.cpp:188-206`, verified): `DecodeBase58Check` → require `size()==34` → split
`[0,2)`=version, `[2,34)`=data → accept only if version ∈ {DD,TD,RD}. `GetDigiDollarDestination`
(`base58.cpp:250-264`, verified) requires `vchData.size()==32`, copies the 32 bytes **verbatim** into
`XOnlyPubKey`, returns `WitnessV1Taproot(...)` — **no tweak, no hash.** The 32 payload bytes ARE the
final (already-tweaked) taproot output key.

Send transform (fund-critical): Base58Check-decode → strip 2-byte version → 32 bytes → emit
`51 20 <32>` at `nValue=0`, **verbatim, no BIP-86 re-tweak** (`txbuilder.cpp:662-667`). **Re-tweaking a
DD-address payload sends to the wrong key = fund loss.**

Receive-address transform: take our BIP-86 taproot output key Q, prepend `{0x52,0x85}` (mainnet) /
`{0xb1,0x29}` (testnet), Base58Check-encode (`base58.cpp:266-279`).

**Validation — do it OURSELVES, strongly.** Core's `IsValidDigiDollarAddress` (`base58.cpp:286-295`,
verified) checks **only the first 2 chars** (`"DD"/"TD"/"RD"`) — no decode, length, or checksum — and
that weak check is what the transfer path calls to "validate" (`txbuilder.cpp:446-448,471`). The strong
checks only run implicitly when the builder later `DecodeDigiDollarAddress`es and null-checks the
`WitnessV1Taproot`. **Our port must replicate the constructor's strong checks: Base58Check-decode →
require 34 bytes → split 2/32 → require version matches the active network.** A prefix-only accept on a
corrupted address decodes to a garbage key and loses funds.

**Red herrings to ignore:** a `dd1...` lowercase-bech32 `ValidateDDAddress` stub
(`consensus/digidollar_transaction_validation.cpp:66-76`) claims DD addresses are bech32 — it is NOT
the shipped format and gates only an RPC helper. A unit test
(`test/digidollar_transaction_tests.cpp:594-602`) validates a bech32 `dd1q...` string — an
aspirational/stale mock, not the shipped Base58Check path.

⚠️ NEEDS-TESTNET26: no real on-chain DD address string exists in the tree. The DD/TD/RD prefix
determinism was computed from the algorithm (mainnet example synth `DD1dd5...`, len ~52), not sampled.
Confirm a real testnet26 address is Base58Check `TD...`, and reject bech32 `dd1/td1` recipients with a
clear error until confirmed.

---

## 5. Transfer validation / value-conservation — reject conditions our decoder mirrors

`ValidateTransferTransaction` (`digidollar/validation.cpp:1063-1265`, verified this pass). Counters
`inputDD, outputDD, ddInputCount, ddOutputCount` start at 0.

### 5.1 Gate + output side
| # | Condition | Reject code | line |
|---|---|---|---|
| R1 | `!HasDigiDollarMarker(tx)` | `transfer-missing-dd-marker` | 1073 |
| R2 | `GetDigiDollarTxType(tx) != DD_TX_TRANSFER` | `transfer-wrong-tx-type` | 1077 |
| R3 | oracle volatility freeze (live oracle only; N/A to SPV) | `all-operations-frozen` | 1084 |
| R4 | no `"DD"`/type-2 OP_RETURN, or zero amount pushes | `transfer-no-op-return-data` | 1121 |
| R5 | more zero-value P2TR outputs than OP_RETURN amounts | `transfer-dd-output-amount-mismatch` | 1134 |
| R6 | some output amount `<= 0` | `transfer-zero-or-negative-dd-amount` | 1142 |
| R7 | amount `< 100` cents or `> MAX_DIGIDOLLAR` (ValidateOutputAmount) | `transfer-dd-amount-below-minimum` | 1146 |
| R8 | amount `> 10,000,000` cents ($100k) per output | `transfer-dd-amount-exceeds-maximum` | 1151 |
| R9 | zero DD (zero-value P2TR) outputs | `transfer-no-dd-outputs` | 1160 |

Valid per-output DD amount range: **`100 <= amt <= 10,000,000` cents** ($1 .. $100k). A zero-value
**non-P2TR** output falls through, consuming no amount slot. Extra trailing OP_RETURN amounts (more
amounts than outputs) are silently ignored — **never `sum(dd_amounts)` blindly; sum only amounts that
map to a real zero-value P2TR output.**

### 5.2 Input side (`:1163-1231`)
- Reject if `tx.vin.empty()` → `transfer-no-inputs` (`:1165`).
- Each input's DD value is read from the **creating tx's OP_RETURN** via `ExtractDDAmountFromPrevTx`
  (txindex) or block-db (§3.2 rule). Value-bearing DGB fee inputs yield no DD amount and add 0.
- If no input yields a DD amount → `dd-input-amounts-unknown` (`:1228`). At least one real DD input
  required.

### 5.3 Conservation — STRICT EQUALITY (`:1234`, verified this pass)
```c
if (inputDD != outputDD)
    return state.Invalid(..., "transfer-dd-conservation-violation");   // NO >=, NO tolerance
```
A transfer must have `sum(input DD) == sum(output DD)` **exactly**; the miner fee is paid in DGB by
separate value-bearing inputs, never in DD. (The task-brief "input >= output" is the REDEEM/burn rule,
**wrong for transfers.**)

### 5.4 ⚠️ Builder-vs-consensus dust discrepancy (FUND-SAFETY, affects SEND)

The **builder** tolerates dropping a DD dust remainder: `ddDifference = totalDDIn - finalDDOut` is
accepted for `0 <= ddDifference <= minOutput(=100)` (`digidollar/txbuilder.cpp:786-789`, verified) and,
when the remainder is `1..99` cents, it is **silently dropped** rather than emitted as change (change
requires `>= minOutput`, `txbuilder.cpp:679`). A tx built that way has on-chain `inputDD > outputDD`,
which **consensus §5.3 rejects** (strict equality). Consensus also forbids a change output below 100
cents (R7). Therefore a transfer whose remainder is `1..99` cents is **unbuildable under strict
consensus** — the reference builder's dust-drop path produces a tx a full node would reject.

**Fund-safe SEND rule (adopt this, do NOT copy the builder's dust-drop):**
- Full-balance send → recipient amount must equal the **exact** selected DD input total (no remainder).
- Partial send → DD change output = **exact** remainder and must be `>= 100` cents.
- If the remainder would be `1..99` cents → the transfer is not constructible; either fold the dust into
  the send amount / a different UTXO selection, or reject with a clear error. **Never broadcast a
  transfer with `inputDD != outputDD`.**

---

## 6. Transfer builder output-ordering template (the SEND reference) — PINNED

Reference: canonical `DigiDollarWallet::TransferDigiDollar` (`digidollarwallet.cpp:997`, the overload
that **signs**) → `TransferTxBuilder::BuildTransferTransaction` (`txbuilder.cpp:586-872`).
**Do NOT port the `(to, amount, tx_out)` overload at `:3978`** — it broadcasts **unsigned**.

```
nVersion  = 0x02000770        (txbuilder.cpp:807; also SetDigiDollarType at :626 — same value)
nLockTime = 0                 (txbuilder.cpp:810)

INPUTS (nSequence = 0xFFFFFFFF each — single-arg CTxIn, SEQUENCE_FINAL):
  vin[0 .. D-1]      DD token UTXOs      (SelectDDCoins, smallest-first)   txbuilder.cpp:629-631
  vin[D .. D+F-1]    DGB fee UTXOs       (SelectFeeCoins, smallest-first, DISJOINT from DD)  :639-649

OUTPUTS (this build order; optional lines only when their guard passes):
  vout[0]                 value=0        51 20 <recipient x-only>       DD to recipient (amount A)   :665-667
  [if ddChange >= 100c]
  vout[1]                 value=0        51 20 <tweaked self x-only>    DD change (CreateTapTweak(nullptr))  :684-698
  [if dgbChange >= 1000 sats]
  vout[next]              value=dgbChange  <P2WPKH bech32 self>         DGB change (0014<20>)        :731-745
  vout[LAST]              value=0        6a 02 44 44 01 02 <A>[ <ddChange>]   OP_RETURN (ALWAYS last) :760-770
```

Invariants to reproduce **exactly**:
1. DD inputs always precede fee inputs.
2. Recipient DD output is `vout[0]`; DD change (if any) immediately follows recipients.
3. DGB change (if any) follows all DD outputs.
4. **OP_RETURN is always the last output.**
5. OP_RETURN amount pushes are positionally aligned to the DD (`OP_1`) outputs only (recipient then DD
   change); DGB change and OP_RETURN are excluded from the amount list.
6. All DD (`OP_1 <32B>`) outputs have `nValue==0`; the amount lives only in the OP_RETURN.
7. `nVersion=0x02000770`, `nLockTime=0`, all `nSequence=0xFFFFFFFF` (transfer does not use RBF/CLTV —
   contrast REDEEM which uses `0xFFFFFFFE`, `txbuilder.cpp:1120`).

Index cases:
- Exact DD (no DD change) + DGB change: `vout[0]=recipient, vout[1]=DGB change, vout[2]=OP_RETURN` (OPRET carries `[A]`).
- DD change + DGB change (typical): `vout[0]=recipient, vout[1]=DD change, vout[2]=DGB change, vout[3]=OP_RETURN` (OPRET `[A, ddChange]`).
- DD change, no DGB change: `vout[0]=recipient, vout[1]=DD change, vout[2]=OP_RETURN`.
- Minimal (exact DD, no DGB change): `vout[0]=recipient, vout[1]=OP_RETURN`.

**Selection:** `SelectDDCoins` sorts DD UTXOs ascending by cents, greedily accumulates to the target
(`digidollarwallet.cpp:4850-4894`). `SelectFeeCoins` sorts DGB coins ascending by `nValue`, greedily
accumulates to the estimated fee, **excluding all DD UTXOs** (`:4922-4995`,
`m_include_unsafe_inputs=true` to allow own unconfirmed change). Pre-build fee estimate targets
`(350*35,000,000)/1000 *1.5` floored at `10,000,000` sats = **0.1 DGB** (`digidollarwallet.cpp:1071-1073`).

**Fee:** `actualFee = max(CalculateFee(tx,feeRate), MIN_DD_FEE=10,000,000)` (`txbuilder.cpp:721-723`);
`CalculateFee = (EstimateTransactionVSize(tx)*feeRate)/1000`, `feeRate=35,000,000` sat/kB
(`digidollarwallet.cpp:1046`). Effective floor **0.1 DGB**. `dgbChange = totalFeeIn - actualFee`,
emitted only if `>= DUST_THRESHOLD=1000` sats (`txbuilder.cpp:27,731`).

**Signing (P2TR key-path):** `SignTransaction`→`SignDDInputs` (`digidollarwallet.cpp:5042`). The coins
map enters DD tokens at their on-chain `nValue=0` ("MUST use 0 to match network verification" — the
BIP-341 sighash commits to input amounts, `:5077-5079`). Base wallet signs DGB fee inputs + received-DD
inputs first via `m_wallet->SignTransaction(..., SIGHASH_DEFAULT, ...)`. Each DD token input is
key-path signed: `CreateTapTweak(nullptr)`, `SignatureHashSchnorr(..., SIGHASH_DEFAULT, TAPROOT)`,
`ownerKey.SignSchnorr(sighash, sig, &empty_merkle_root, aux)`; **witness = exactly one 64-byte Schnorr
sig** (no sighash byte, because SIGHASH_DEFAULT) (`:5464-5532`). The wallet stores the **internal** key
and passes a **zero merkle root** so the sig verifies against the **tweaked** output key. (Collateral
script-path signing at `:5538-5657` is REDEEM territory — out of scope.)

> **Implementer trap (stale source comment):** `digidollarwallet.cpp:5126` carries a dead comment
> `"our modified DD coins (with dummy value=1)"` that does NOT match the code — the actual coins map
> enters DD inputs at the real on-chain **`nValue=0`** (`:5079`). Use **0**, never 1; a value of 1
> computes a wrong TapSighash → consensus-invalid DD spend. The spec is right; guard against the comment.

✅ Fee-input sighash (SOURCE-RESOLVED, not a testnet26 blocker): the base wallet passes `SIGHASH_DEFAULT`,
but Core **auto-remaps `SIGHASH_DEFAULT`→`SIGHASH_ALL` for BASE/WITNESS_V0** signature creation
(`script/sign.cpp:51-52`), so a real DD transfer's P2WPKH fee witness **carries `0x01` (SIGHASH_ALL)**
on-chain. Sign our P2WPKH fee inputs with **SIGHASH_ALL (0x01, BIP143)** and reserve empty-sighash
(DEFAULT, bare 64-byte Schnorr) for the P2TR DD inputs.

**Relay caveat:** a DD version (`0x02000770` = 33,556,336 ≫ `TX_MAX_STANDARD_VERSION`) is only relayed
by peers that implement the `0x0770` standardness carve-out (`policy/policy.cpp:96-115`). A stock SPV
peer treats it as non-standard "version". Broadcast to DD-aware nodes.

**Post-broadcast bookkeeping:** register the 2nd `OP_1` output (DD change) as our own spendable DD UTXO,
keyed by the tweaked spender key, so the key-path signer can spend it later
(`digidollarwallet.cpp:1390-1431`).

---

## 7. Known test vectors

**✅ REAL testnet26 golden vector CAPTURED (2026-07-05) — confirms this spec byte-for-byte.**
DigiByte testnet26 block **83946**, transfer txid
`3d8797cb87f6903bceeea28e6366093faec34af629e051dfda7b3a9616c5a346` (pulled from a v9.26.3 node):
- `version = 0x02000770` (marker `0x0770`, type 2) — as pinned (§1).
- `vout[0]` value 0, P2TR `5120 effc01…2000cd` (DD token, ordinal 0)
- `vout[1]` value 0, P2TR `5120 55e1ba…3064a3` (DD token, ordinal 1)
- `vout[2]` value **2.88 DGB**, P2TR `5120 55e1ba…3064a3` (DGB change, **non-zero → skipped**)
- `vout[3]` OP_RETURN `6a 02 4444 01 02 02c409 024c1d` = `"DD"` type2 amounts **[2500, 7500]** cents — **no count field** (§2.3).
`BRDigiDollar.c` decodes it exactly (type 2, [2500,7500], ordinal binding skips the DGB change) —
committed as `native/src/test/host/digidollar_realtx_kat/`. **Address note (SEND-relevant):** the on-chain
DD outputs render as standard **bech32m P2TR** (`dgbt1p…` testnet HRP), i.e. a plain zero-value taproot
output — consistent with §3.1. Whether the SEND recipient-input format is that `dgbt1p…` bech32m or the
`TD…` Base58Check `CDigiDollarAddress` (§4) is still to confirm against the node's DD send RPC before
writing SEND recipient-parsing (§8 Q2) — the node is now synced, so this is resolvable.

**In-tree test suite has NONE.** Exhaustive greps of the entire DD test suite (C++ + functional
python) found **no raw serialized DD transaction hex** anywhere — only placeholder txids, x-only pubkey
literals, and the NUMS point. There is no committed golden vector for the on-wire byte serialization of
a DD transfer, mint, address, or OP_RETURN.

**Structural KATs** (assert fields/amounts, not raw bytes) from `test/digidollar_transfer_tests.cpp`:
- `result.tx.nVersion == 0x02000770` (`:1084, :1110, :1244`); `nLockTime == 0` (`:1087, :1111, :1245`).
- DD output `scriptPubKey[0]==0x51 && [1]==0x20`, size≥34 (`:832-834, :909-915`); `nValue==0`
  (`:1144, :1256`).
- Hand-built transfer (`:1608-1666`): mint 10000c → transfer 5000c + change 5000c; asserts 2 DD P2TR
  outputs, `ddAmounts.size()==2`, `amounts[0]==5000`, `amounts[1]==5000`, total==10000.
- Builder amount KATs: `{30000}`→1 out; `{50000,25000}`→2, total 75000; `{10000,20000,30000}`→3, total
  60000; `{12345,67890}`→2.

**Functional (RPC, cents)** `test/functional/digidollar_transfer.py`: `senddigidollar(addr, 1000)` →
sender −1000, receiver +1000; balances in cents.

**Constants worth pinning:** NUMS internal key (mint collateral, so we ignore it)
`50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0` (`digidollar/scripts.h:35-40`).
DD amount reader range `[1, 100000000000]` cents (`validation.cpp:128,143`).

**Synthesized worked vectors** (derived from source + verified against `CScriptNum::serialize`; NOT
sampled on-chain — treat as advisory until a real testnet26 tx confirms):
```
nVersion TRANSFER (LE first 4 tx bytes):  70 07 00 02
OP_RETURN, 1 recip $50, no change:        6a 02 44 44 01 02 02 88 13
OP_RETURN, 2 recip $50/$25, no change:    6a 02 44 44 01 02 02 88 13 02 c4 09
OP_RETURN, 2 recip + $3 change:           6a 02 44 44 01 02 02 88 13 02 c4 09 02 2c 01
DD token output (any):                    51 20 <32-byte x-only key>   (nValue 0)
```

---

## 8. OPEN QUESTIONS — require a real testnet26 DD tx before writing fund-moving code

1. **⚠️ Dust-conservation discrepancy (§5.4) — HIGHEST fund-safety priority.** Confirm on testnet26
   whether a transfer with a `1..99` cent remainder is rejected by consensus (as strict `inputDD ==
   outputDD` at `validation.cpp:1234` implies) or whether some node-side tolerance exists. Until
   confirmed, our SEND enforces exact conservation and never drops dust.
2. **✅ DD address format — RESOLVED (2026-07-05) against a real testnet26 address.** `getdigidollaraddress`
   on a v9.26.3 node returns `TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC` — Base58Check, 38 bytes
   = 2-byte version `b1 29` ("TD" testnet, exactly §4) + 32-byte taproot output key
   (`dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b`) + 4-byte double-SHA256 checksum
   (verified). `validateaddress` REJECTS it (DD-specific encoding, not a standard address); the node's
   `getdigidollarbalance "TD…"` accepts it. **SEND decoder:** base58check-decode → verify version
   (`b129` testnet / `5285` mainnet "DD") → extract the 32-byte key → emit `51 20 <key>` at value 0
   **verbatim, no re-tweak** (§3.1). The on-chain output then renders as `dgbt1p…` bech32m P2TR. No `dd1…`
   bech32 form is used on the send path. **SEND recipient-parsing is unblocked.**
3. **✅ Fee-input sighash — SOURCE-RESOLVED (no longer a testnet26 blocker).** Core remaps
   `SIGHASH_DEFAULT`→`SIGHASH_ALL` for BASE/WITNESS_V0 at `script/sign.cpp:51-52`, so P2WPKH fee
   witnesses carry `0x01` on-chain. Sign our P2WPKH fee inputs with SIGHASH_ALL. No live tx needed.
4. **⚠️ Realized miner fee vs `actualFee`.** `CalculateFee` runs before the DGB-change + OP_RETURN
   outputs are appended (`txbuilder.cpp:722`), so the sized-for fee omits ~2 outputs. Floored at 0.1
   DGB it is harmless in practice, but confirm a real DD transfer relays and the fee equals `actualFee`.
5. **⚠️ vout ordering is final (no canonicalization/BIP-69 sort).** The builder appends
   `[recipients, DD change, DGB change, OP_RETURN]` with no re-sort observed; positional decode depends
   on this. Confirm no wallet/consensus step reorders vout before broadcast.
6. **⚠️ OP_RETURN relay-size cap / max recipients.** Whether DD txs are exempt from the standard 83-byte
   `MAX_OP_RETURN_RELAY` (via the `0x0770` marker / policy override) is not determinable from the files
   read. Conservatively cap SEND at ~15 DD outputs until a multi-recipient testnet26 transfer confirms.
7. **⚠️ Legacy `OP_RETURN OP_DIGIDOLLAR(0xbb) <8-byte LE>` form (§2.8).** Confirm no live tx uses it;
   our encoder must emit only the `"DD"` CScriptNum form; our decoder may read `0xbb` defensively.
8. **⚠️ Minimal-encoding + empty-push edges.** Confirm on-chain amounts are always minimally encoded
   (consensus reader `CScriptNum(data,true,8)` throws on non-minimal; the fallback `ExtractDDAmount`
   uses non-minimal) and that no live tx relies on a positional empty push.
9. **⚠️ Flags byte always 0** (§1.5). Classify on `marker && type==2` (not the exact word) so a nonzero
   flags byte does not false-negative.
10. **⚠️ Sibling-input signing-order independence.** Source signs fee inputs "first"; per BIP-341
    key-path this should not matter. Confirm a DD transfer signed in any input order validates.

---

## 9. Implementation checklist mapped to our C-core seams

### 9.1 What SHOW needs (detect + decode DD balances) — `BRDigiAsset.c`-style OP_RETURN decode

Seam: `native/.../digibytewallet-core/BRDigiAsset.c` already has the OP_RETURN-scan idiom (`*ptr !=
OP_RETURN` guard `:48`; op_return output search `:84-94`; `ptr = or_output->script + 4 /* OP_RETURN +
LEN + TAG(2) */` `:111`). Add a parallel `BRDigiDollar.c` (or extend the asset path) that:

- [ ] **Classify tx:** read `tx->version` (int32); `isDD = (version & 0xFFFF)==0x0770`;
      `ddType = (version >> 24) & 0xFF`. Only proceed for `ddType ∈ {1,2,3}`. (§1)
- [ ] **Find the DD OP_RETURN:** first output with `script[0]==0x6a` whose first push is the 2 bytes
      `44 44`. Parse pushes with a `GetOp`-equivalent (opcode + data), NOT by fixed offsets — DD amounts
      are variable-length CScriptNum pushes. (§2.1, §2.2)
- [ ] **Read txType push** as a minimal CScriptNum (bytes `01 02` → value 2). Reject `OP_N` assumption.
      (§2.1 the fund-safety pin)
- [ ] **Collect amounts type-aware:** `type==2` → all remaining nonempty pushes; `type==1||3` → first
      nonempty push only. Each = signed minimal little-endian CScriptNum, up to 8 bytes; reject
      negative. (§2.7, §3.2 Phase A)
- [ ] **Bind amount→output positionally:** counter `k=0`; walk `tx->outputs` in order; skip
      `script[0]==0x6a`; skip `nValue != 0`; else if `scriptLen==34 && script[0]==0x51` it is DD output
      `k`, amount `dd_amounts[k]`, `k++`. (§3.2 Phase B — reuse the exact rule)
- [ ] **Ownership:** extract the 32-byte key = `script[2..34)`; compare against our BIP-86 taproot
      output keys (`BRKeyTaprootOutputKey`, present in `BRKey.c`, produces Q for each of our internal
      keys). A match with `k` in range ⇒ we hold `dd_amounts[k]` cents at that outpoint. (§3.3)
- [ ] **Persist DD UTXOs separately from DGB UTXOs** (0-value, cents-denominated). Never surface a DD
      output as spendable DGB; never drop it as dust. Sum only matched amounts for the DD balance —
      never `sum(all pushes)`. (§3.4, §5.1)
- [ ] **Identify-and-ignore MINT/REDEEM correctly:** never present a mint collateral output (`nValue !=
      0`, NUMS-internal-key MAST) as spendable; MINT token/REDEEM change to us are receives, valued by
      the type-aware first-push rule. (§2.5, §2.6, §3.4)
- [ ] **Address decode for display / send target:** Base58Check-decode → 34 bytes → 2-byte version ∈
      {DD,TD,RD} → 32-byte key. Strong-validate (do not trust prefix-only). (§4)

### 9.2 What SEND needs (transfer, type 2) — txbuilder path + the P2TR key-path signer we shipped

Signer seams already in-core (confirmed in `BRKey.c` / `BRTransaction.c`):
`BRKeyTaprootSchnorrSign` (BIP-86 tweaked key-path Schnorr), `BRKeyTaprootOutputKey`,
`_BRTransactionTaprootSighash` (BIP-341 "TapSighash", commits all input amounts + scriptPubKeys,
SIGHASH_DEFAULT/ALL only), `BRKeyTaprootAddress`. These are exactly what a DD key-path spend requires.

- [ ] **Address→script:** decode recipient DD address (§4) → emit `51 20 <32 bytes>` at `nValue=0`
      **verbatim (no re-tweak).** Re-tweaking = fund loss. (§4, §3.1)
- [ ] **nVersion / locktime / sequence:** set `tx.version = 0x02000770`, `nLockTime=0`, every
      `nSequence=0xFFFFFFFF`. (§6 invariant 7)
- [ ] **Input selection:** DD token UTXOs (smallest-first) to cover the send; DGB fee UTXOs
      (smallest-first) DISJOINT from DD, targeting ≥ 0.1 DGB. (§6)
- [ ] **Output ordering:** `vout[0]=recipient (51 20 <recip key>)`; `vout[1]=DD change (51 20 <tweaked
      self key via BIP-86 tweak of our internal key>)` only if change ≥ 100c; `vout[next]=DGB change
      (P2WPKH 0014<20>)` only if ≥ 1000 sats; `vout[LAST]=OP_RETURN`. (§6 invariants 1-6)
- [ ] **OP_RETURN build:** `6a 02 44 44 01 02` then one minimal CScriptNum push per DD output in order
      (recipient, then DD change). No count field. (§2.2)
- [ ] **Conservation (fund-safe, stricter than the reference builder):** enforce `sum(input DD) ==
      sum(output DD)` EXACTLY. Change must be ≥ 100c or zero; never drop a `1..99` cent dust remainder;
      reject/re-select if it would occur. (§5.3, §5.4)
- [ ] **Sign DD inputs:** for each DD token input, key-path Schnorr via `BRKeyTaprootSchnorrSign` over
      `_BRTransactionTaprootSighash(..., SIGHASH_DEFAULT)`; witness = single 64-byte sig, no sighash
      byte; input amount in the sighash prevout set = **0** (on-chain nValue). (§6 signing)
- [ ] **Sign DGB fee inputs:** P2WPKH BIP-143, **SIGHASH_ALL (0x01)** (not DEFAULT). (§6, open Q3)
- [ ] **Broadcast to a DD-aware peer** (version standardness carve-out) and, post-broadcast, register the
      DD change output (2nd `OP_1` output) as our own spendable DD UTXO. (§6 relay + bookkeeping)
- [ ] **Gate SEND behind the open questions in §8** — especially exact-conservation (Q1) and address
      round-trip (Q2) — until a real testnet26 DD transfer is captured and byte-diffed against this spec.

---

## 10. Provenance

All `file:line` above verified against `/home/polloloco/digibyte-rc25-src/src/` this pass, cross-checked
with the seven recon extracts under `scratchpad/dd-recon/`. Where an extract disagreed, the **source
won**: notably the nVersion **decimal** values were miscalculated in two extracts — the correct,
computed values are MINT `0x01000770`=**16,779,120**, TRANSFER `0x02000770`=**33,556,336**, REDEEM
`0x03000770`=**50,333,552** (the hex, which the port should key on, was correct everywhere). The
builder-vs-consensus dust discrepancy (§5.4) is surfaced here as a first-class fund-safety hazard.
