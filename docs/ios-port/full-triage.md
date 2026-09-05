# iOS port — full code triage

**2026-09-03.** Covers `core/sync/`, `core/asset/`, `core/reconcile/`, `core/dandelion/`,
`core/tor/`, `core/network/`, `core/security/`, `core/bridge/NativeBridge.kt`, and
`native/src/main/jni/bridge/`. The method for acting on it is in
[`push-down-recipe.md`](push-down-recipe.md).

Rule used throughout: **→ C** if it can produce different wallet state on one platform
(balance, asset count, spent state, scan floor, signature, or what goes on the wire).
**→ Swift** if it is lifecycle, storage, transport, or UI.

---

## 1. The finding that changes the plan

**~7,500 lines of wallet-correctness logic live in `native/src/main/jni/bridge/`, which is an
Android-only compilation unit and will NOT be in the XCFramework.**

This is worse than logic sitting in Kotlin, because it *looks* like it is already in C. A plan
that says "wrap `digibytewallet-core` as an XCFramework and write SwiftUI over it" silently
loses all of it. 117 JNI entry points across ~7,483 lines. Stranded there:

- ~~**The 15 hardcoded mainnet CF oracle IPs** (`jni_peer.c:405–464`) plus the testnet set~~
  **✅ moved 2026-09-05 → `BRPeerCanon.h`** (`peer_canon_kat`; `jni_peer.c` and
  `SyncService.kt`'s testnet copy now read it). Still in the bridge: `INJECT_DEFAULT_SERVICES`
  (the untagged-injection default, not the canon) and the penalty-restore exemption's
  *call site* — the exemption's *set* is now `BRPeerCanonContains`/`BRPeerCanonAddrs`.
  CLAUDE.md calls this peer set "the CANON" — the wallet's only reliable CF source.
- **`startSync`** (`jni_peer.c:784–1001`) — ~215 lines: rescan-defer heuristic, saved-blocks
  creation gate, priority-peer resolve-and-prepend, saved-block ownership transfer, BIP158
  re-apply, penalty restore, pin re-apply.
- **`_applyPendingBip158State`** (`jni_peer.c:1453–1524`) — defer-and-apply plus the *sticky
  re-arm* of auto-CF-fetch across manager recreates.
- **First-sync-then-rescan latching** in `bridge_syncStopped` (`jni_peer.c:144–197`).
- **The publish-result ring** (`jni_transaction.c:39–121`) — the wallet's only record that the
  network refused a transaction.
- **Foreign-seed transaction builders** — `buildAndSignForeignAssetTransfer`,
  `buildAndSignForeignDigiDollarTransfer`, `buildAndSignLegacySweep`,
  `buildAndSignAssetTransferTx`. Consensus-critical construction.
- **`getWalletBirthCheckpointHeight`** (`jni_peer.c:1227`) reimplements the core's checkpoint
  selection rule in the bridge.
- **`generateMnemonic` reads `/dev/urandom` directly** (`jni_wallet.c:88`). iOS should use
  `SecRandomCopyBytes`; the RNG choice belongs in core.

**The pattern to extend already exists.** Six JNI-free headers are factored out and
host-KAT'd: `foreign_tx_fee_guard.h`, `digidollar_transfer_layout.h`,
`saved_blocks_deserialize.h`, `bridge_status_stale.h`, `slip13.c/h`, `jni_seed_buffer.h`.
Those port to iOS unchanged. The work is to keep pulling logic out of `jni_*.c` into that
shape.

**Revised sequencing: de-Android the bridge BEFORE building the XCFramework.** Everything
left in `jni_*.c` when the framework is cut is work that gets done twice and diverges.

## 2. Two things to fix regardless of iOS

### `getChangeAddress(index, format)` ignores both arguments — VERIFIED

`jni_wallet.c:540` is literally `(void)index; (void)format;` before returning
`BRWalletInternalChangeAddress(g_wallet)`. Consequences today:
- `healLegacyChangeAddressOrphans` is **non-functional** — it cannot address a specific index.
- `sendAsset` (`AssetManager.kt:1537`) requests change index 1 believing it gets a *distinct*
  asset-change address. It gets the same internal change address.

### IPv4-only DNS is a likely App Store rejection — VERIFIED

`jni_peer.c:526`: `hints.ai_family = AF_INET;  /* IPv4 — most reliable for mobile */`.
Apple requires apps to work on IPv6-only networks (NAT64/DNS64) and actively tests it. A
rejection risk independent of wallet guidelines; `AF_UNSPEC` plus IPv6-capable peer records.

## 3. errno crosses the JNI boundary raw

`core/sync/PublishOutcome.kt` hardcoded `ENOTCONN = 107`, `ETIMEDOUT = 110` (Linux; Darwin
57/60). Also `bridge_syncStopped` passes raw `error` to `onSyncFailed` (`jni_peer.c:150–166`)
and `_publishResult` records raw errno (`jni_transaction.c:85`). **Fixed for the policy table
by `BRPublishOutcome.h`** (switches on `<errno.h>` symbols). The two bridge sites still pass
raw errno. `strerror()` is also not thread-safe and its text is being sent to the UI.

## 4. Verdicts

### `core/sync/` — DONE for the pure-policy files

| File | Verdict | Status |
|---|---|---|
| `RecreateSequence` | → C (spec only) | ✅ `BRRecreateSequence.h` |
| `CfRecoveryPolicy` | → C | ✅ `BRCFRecoveryPolicy.h` |
| `PublishOutcome` | → C | ✅ `BRPublishOutcome.h` |
| `PeerPenaltyPersist` | → C | ✅ `BRPeerPenaltyPersist.h` |
| `CfAbandonmentStore` pure predicates (`nextAbandonedBand`, `bandIsRetired`, `coverageIsProven`) | → C | ✅ `BRCFAbandonment.h` (2026-09-05) |
| `ChainTipPolicy` | Swift + carry tests — DISPLAY ONLY by design | — |
| `KeepaliveHealth` | Swift, rewritten — Kotlin `Job`/dispatcher semantics | — |
| The five stores | Swift — `Context`/SharedPreferences → `FileManager`/Keychain | — |

`CfAbandonmentStore`'s two-phase witness must live above the core — the recreate frees the
manager, so the pre-recreate frontier no longer exists to compare against. Only the
persistence is platform; the predicates are pure.

### `core/asset/` — mostly C, and the largest single block of work

`AssetTxQuantity`, `BitReader`, `BitWriter`, `DigiAssetDecoder`, `DigiAssetEncoder`,
`AssetSpentState`, `AssetCoinSelector`, `AssetFeeEstimator`, `DeadSendPredicate`,
`OrphanSendPredicate` → **all C**. The encoder produces the OP_RETURN that gets *signed*;
encoder and decoder cannot be two codebases.

`AssetManager` (2,035 lines) must **split**: the counting core (`isHeldForDisplay`,
`computeHeldAssetBalancesImpl`, `resolveInputAssetUnits`, `decideAssetSpent`, prune/heal,
`buildTransferInstructions`, sendAsset's layout) → C; Flow/debounce/Room orchestration → Swift.

Networking (`asset/network/`, `reconcile/DgbNodeClient`) → Swift (OkHttp → URLSession).

## 5. Tor on iOS is harder than the Android design implies

The **transport is already in C** — `BRPeer.c` holds `g_socksHost`/`g_socksPort` behind a
mutex with a SOCKS5 handshake. That ports free. What does not:

- **kmp-tor is Kotlin/Android and unportable**, and its no-exec/`dlopen` approach is not
  permissible on the App Store. iOS needs Tor.framework or Arti, linked in.
- **Background networking.** `TorManager` assumes ~90s bootstrap inside a foreground service.
  iOS gets ~30s on suspend and no long-lived background socket without a
  `NEPacketTunnelProvider` entitlement. **Expect Tor-on-iOS to be foreground-only.**

Three Kotlin rules are wire-visible and must move to C or be re-specified exactly:
1. Connected requires `socksPort != null` AND `bootstrap >= 100` — LISTENERS fires long before
   circuits exist.
2. **`SafeSocks=0` is mandatory** — the core dials raw IPs. Several iOS Tor wrappers do not
   expose this knob; check before choosing one.
3. On direct→Tor transition: `stopSync()` + `startSync()`, never `forceReconnect()` — the core
   will not re-route already-connected peers.

## 6. Dandelion++ embargo timing is a privacy divergence

The stem/fluff mechanism is in C, but the **timing and decision are in Kotlin**: a 10–30s
uniform draw, `relayCount == 0 ⇒ fluff`, stem-null ⇒ flood. Two platforms with two timer
implementations produce two observable embargo distributions — a fingerprint. Push
`EMBARGO_MIN/MAX_MS`, `embargoDelayMs`, `shouldFluffAfterEmbargo` and the timer into C.

Preserve: `catch (_: Throwable) { 1 }` on `getRelayCount` is a deliberate fail-open.

**iOS hazard:** the embargo is process-lifetime; suspension kills the timer mid-embargo and
strands the transaction. iOS needs a *persisted* embargo deadline re-armed on foreground.

## 7. Do not inherit the Android Keystore decision

**Carry over:** AES-256-GCM seed wrapping, hardware-backed key, second auth-bound alias with
migration, ~300s auth window, delete-legacy-after-verified-migration, and the typed
`UserAuthRequired` / `KeyInvalidated` distinction.

**Do not copy:** the `SeedKeyBinding` API-level matrix, the `BIOMETRIC_STRONG` vs
`WEAK|DEVICE_CREDENTIAL` split, `KeyguardManager.isDeviceSecure` probing.

**Needs fresh judgement:** the repo avoids `setUserAuthenticationRequired` because Android's
auth binding crashed across three API levels. **That rationale is Android-specific.** iOS
`kSecAccessControlUserPresence` / `.biometryCurrentSet` with
`kSecAttrAccessibleWhenUnlockedThisDeviceOnly` is far more reliable; inheriting "don't bind"
imports a workaround for a bug that does not exist on the platform. It is the open half of
CRITICAL-1.

`PinManager` (345 lines) is already a pure rate-limit state machine over a `PinStore` seam —
port to Swift with its tests, backed by Keychain, keeping synchronous-commit durability.

## 8. Correctness hazards to fix before or during the port

- **Silent `Long` overflow in SFFC decode** (`BitReader.kt:139`, `mantissa * pow10(exponent)`).
  Kotlin wraps; **C signed overflow is UB**. A negative asset quantity flows into balance sums.
- **Encoder/decoder bucket-6 asymmetry** — `BitWriter.kt:102` writes a 2-bit prefix `11`;
  `BitReader.kt:103–109` reads 3 bits then rewinds 1. A C port mirroring the writer literally
  desynchronizes the stream.
- **Sign-extension** — `BitReader.kt:51` is correct only because of a trailing mask. In C with
  `int8_t` and `>>`, sign-fill corrupts every read from a byte ≥ 0x80.
- **Three conflicting dust constants** — `DA_ASSET_DUST_AMOUNT = 700` (stale; 9.26 rejects
  it), `DUST_FLOOR = 5460`, `DA_MARKER_SATS = 6000`. One C definition.
- **`TX_UNCONFIRMED` re-declared as `Int.MAX_VALUE`** in Kotlin while native stores a
  `uint32_t`. A Swift bridge typing that field `Int32` reclassifies every unconfirmed send.
- **`String.split("|", limit = 3)`** (`AssetManager.kt:454, 529, 1272`) — Kotlin's `limit`
  leaves the remainder unsplit, which protects the script hex. Swift has no direct equivalent;
  a naive `components(separatedBy:)` truncates scripts and drops asset outputs.
- **`Character.digit(c, 16)`** accepts non-ASCII digits. The app ships 12 locales.
- **Locale-sensitive `"%02x".format()`** on the ownership-comparison hot path
  (`AssetManager.kt:924, 1657`, `PinManager`). Swift must use explicit ASCII hex.
- **Fixed stack buffers** — `uint8_t scriptBytes[256]` in `jni_derive.c` truncates larger
  scriptPubKeys.
- **Struct layout** — peer records hardcoded as `16+2+8+8 = 34` bytes, `sizeof(BRMerkleBlock)
  == 192` asserted in comments, `BRPeer.timestamp` truncated `uint64→uint32`. Audit under the
  iOS toolchain.
- **Seeder tiering is duplicated** across `injectFilterPeers` (SyncService) and `SyncWorker`.
  Two Kotlin copies today; a Swift copy makes three. Consolidate into C.

## 9. Threading model — reproduce, do not translate

C→Kotlin goes through one global-ref `jobject` with cached `jmethodID`s; every callback calls
`jni_get_env()` with an `AttachCurrentThread` fallback. All `BRPeerManager` callbacks run on
the manager's own peer threads. Every JNI entry touching `g_peerManager` takes a scoped
recursive lock (`PEER_GUARD()`); **callbacks must not take it** or `BRPeerManagerFree`'s
thread-join deadlocks. Status reads bypass the lock via six `_Atomic` mirrors.

On iOS the attach/detach and global refs vanish — replace with a C function-pointer struct
plus `void *info`, hopping to a Swift actor or dispatch queue. But **the mutex, the guard
discipline, and the atomic status mirrors must be reproduced**: they exist because of real
use-after-free and 31-minute-wedge incidents.

## 10. Confidence

§1 (bridge inventory), §2 (both verified by direct source read), §3 (errno, confirmed on the
macOS SDK) are solid. §4–§8 come from an automated pass over ~14,000 lines and are
structurally sound, but the subtler claims — the SFFC overflow, the bucket-6 asymmetry —
deserve a human read before anyone acts on them.
