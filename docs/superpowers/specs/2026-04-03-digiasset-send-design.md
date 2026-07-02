# DigiAsset Send — Design Spec

## Goal

Enable the wallet to send DigiAssets to other addresses using full-UTXO transfers, with asset UTXO protection to prevent accidental spending.

## Background

The wallet already detects DigiAssets via OP_RETURN parsing (C + Kotlin, 26 tests), fetches metadata from IPFS, and displays owned assets. The AssetSendScreen UI exists but the send function is a stub. This spec covers wiring up the actual transaction construction and broadcast.

DigiAsset transactions work by spending 700-satoshi "marker" UTXOs that carry asset data. A transfer instruction encoded in an OP_RETURN output tells the network which output receives the asset. The marker amount (700 sats) is defined as `DA_ASSET_DUST_AMOUNT` in `BRDigiAsset.h`.

## Constraints

- **Full UTXO transfers only** — no partial/split transfers in v1. All asset quantity from the input goes to the recipient.
- **Self-contained** — no dependency on DigiAsset Core or external services for transaction construction.
- **Existing signing/broadcast** — reuse `NativeBridge.signTransaction()` and `publishTransaction()`.

## Architecture

### Component 1: BitIO Encoder (`DigiAssetEncoder.kt`)

Mirror of the existing `DigiAssetDecoder.kt`. Encodes a full-UTXO transfer OP_RETURN.

**Output format (OP_RETURN payload):**
```
[0x44, 0x41]          — "DA" magic prefix (2 bytes)
[version]             — Protocol version byte (0x03 for v3)
[0x15]                — TRANSFER opcode
[transfer instruction] — BitIO-encoded: skip=0, range=false, percent=false,
                         output=recipient_index, amount=0 (means "all from this input")
[padding to byte boundary]
```

**Interface:**
```kotlin
object DigiAssetEncoder {
    fun encodeTransfer(recipientOutputIndex: Int): ByteArray
}
```

Returns the full OP_RETURN payload bytes (without the OP_RETURN opcode itself — that's added during transaction construction).

**Location:** `core/src/main/java/io/digibyte/core/asset/DigiAssetEncoder.kt`

### Component 2: Asset Transaction Builder (`AssetTxBuilder.kt`)

Orchestrates asset UTXO selection, output construction, and delegates to JNI for serialization.

**Inputs:**
- `recipientAddress: String` — destination address
- `assetUtxo: UtxoEntity` — the specific asset UTXO to spend (selected by UI from owned list)
- `feePerKb: Long` — fee rate for the DGB fee portion (default 100,000 sat/KB)

**Outputs constructed:**
1. **Recipient output:** 700 sats to `recipientAddress` (carries the asset)
2. **OP_RETURN output:** 0 sats, `OP_RETURN <payload>` script (transfer instruction)
3. **DGB change output:** remaining DGB from fee UTXO minus fee (if any)

**Fee UTXO selection:** Uses the C core's existing UTXO set (via a new JNI call or the existing `createTransaction` mechanism) to find a DGB-only UTXO for the fee. The fee covers the full transaction size (~250-300 bytes for a 2-input asset transfer).

**Interface:**
```kotlin
class AssetTxBuilder(private val utxoManager: UtxoManager) {
    suspend fun buildAssetTransfer(
        recipientAddress: String,
        assetUtxo: UtxoEntity,
        feePerKb: Long
    ): ByteArray  // unsigned transaction bytes
}
```

**Location:** `core/src/main/java/io/digibyte/core/asset/AssetTxBuilder.kt`

### Component 3: JNI — `createTransactionFromParts`

New C function that accepts explicit inputs and outputs and returns a serialized unsigned transaction.

**Why:** The existing `createTransaction` uses `BRWalletCreateTransaction` which does its own UTXO selection — we can't control which inputs it picks. Asset transactions need explicit input/output control.

**JNI signature:**
```kotlin
external fun createTransactionFromParts(
    inputTxids: Array<ByteArray>,   // 32-byte txid per input (little-endian)
    inputVouts: IntArray,            // output index per input
    outputAddresses: Array<String>,  // address per output ("" for OP_RETURN)
    outputAmounts: LongArray,        // satoshis per output (0 for OP_RETURN)
    outputScripts: Array<ByteArray>  // raw scriptPubKey per output (used for OP_RETURN; empty for address-based outputs)
): ByteArray?
```

**C implementation:** Creates a `BRTransaction`, adds inputs via `BRTransactionAddInput`, adds outputs via `BRTransactionAddOutput` (using either address-derived scriptPubKey or raw script for OP_RETURN), serializes via `BRTransactionSerialize`.

**Location:** `native/src/main/jni/bridge/jni_asset.c` (extend existing file)

### Component 4: JNI — Asset UTXO Blacklist

Prevents the C core's `BRWalletCreateTransaction` from spending asset UTXOs during regular DGB sends.

**JNI signatures:**
```kotlin
external fun markAssetUtxo(txid: ByteArray, vout: Int): Boolean
external fun unmarkAssetUtxo(txid: ByteArray, vout: Int): Boolean
```

**C implementation:** Maintains a static array/set of `(UInt256, uint32_t)` outpoints. `BRWalletCreateTransaction` is modified (or a wrapper is used) to skip inputs that match the blacklist. Since the C core iterates UTXOs for coin selection in `BRWallet.c`, the cleanest approach is adding a callback or filter check in the UTXO iteration.

Alternative: Override `BRWalletSetTxUnspent` behavior — mark asset UTXOs as "spent" so the wallet never selects them. Simpler but less clean.

**Location:** `native/src/main/jni/bridge/jni_asset.c`

### Component 5: Wire Up `AssetManager.sendAsset()`

Replace the stub in `AssetManager.kt` with the real flow:

```kotlin
suspend fun sendAsset(recipientAddress: String, assetUtxo: UtxoEntity): TxResult {
    // 1. Validate address
    if (!NativeBridge.isValidAddress(recipientAddress)) return TxResult.Error("Invalid address")

    // 2. Build unsigned transaction
    val unsigned = assetTxBuilder.buildAssetTransfer(
        recipientAddress, assetUtxo, DEFAULT_FEE_PER_KB
    ) ?: return TxResult.Error("Failed to build asset transaction")

    // 3. Sign
    val signed = NativeBridge.signTransaction(unsigned)
        ?: return TxResult.Error("Failed to sign transaction")

    // 4. Broadcast
    val txid = NativeBridge.publishTransaction(signed)
        ?: return TxResult.Error("Failed to broadcast transaction")

    // 5. Unmark the spent asset UTXO
    NativeBridge.unmarkAssetUtxo(assetUtxo.txidBytes(), assetUtxo.vout)

    return TxResult.Success(txid)
}
```

**Location:** `core/src/main/java/io/digibyte/core/asset/AssetManager.kt`

## Transaction Layout

```
INPUTS:
  [0] Asset UTXO     — 700 sats, carries the asset
  [1] Fee UTXO       — regular DGB UTXO for fee payment

OUTPUTS:
  [0] Recipient       — 700 sats to destination address (asset goes here)
  [1] OP_RETURN       — 0 sats, transfer instruction (output index = 0)
  [2] DGB Change      — fee UTXO value - 700 - fee (back to wallet)

OP_RETURN payload:
  44 41 03 15 [transfer_instruction_bits] [padding]
  ^^ ^^ ^^ ^^
  D  A  v3 TRANSFER
```

## Files Summary

| Action | File |
|--------|------|
| Create | `core/src/main/java/io/digibyte/core/asset/DigiAssetEncoder.kt` |
| Create | `core/src/main/java/io/digibyte/core/asset/AssetTxBuilder.kt` |
| Modify | `native/src/main/jni/bridge/jni_asset.c` — add `createTransactionFromParts`, `markAssetUtxo`, `unmarkAssetUtxo` |
| Modify | `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` — declare new JNI methods |
| Modify | `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` — replace `sendAsset()` stub |
| Modify | `app/src/main/java/io/digibyte/ui/asset/AssetSendScreen.kt` — remove "coming soon" banner, enable send button |

## Testing Plan

1. **Unit tests:** DigiAssetEncoder — encode a transfer, decode it with existing decoder, verify round-trip
2. **Unit tests:** AssetTxBuilder — mock UTXO selection, verify transaction structure
3. **Integration test:** Issue test asset via DigiAsset Core on VPS, send to wallet address, verify detection, then send from wallet to another address, verify on-chain

## What's NOT in Scope

- Partial transfers / asset splitting
- Asset issuance from the wallet
- Asset burn
- Rules enforcement (royalties, KYC, expiry) — Phase 3
- Asset receive improvements (C core callback) — separate sub-project
