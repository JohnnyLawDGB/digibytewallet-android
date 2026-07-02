# Legacy Sweep — Hardening + Test/Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix six code-verified defects in the DigiByte legacy-funds sweep (and the shared broadcast path it rides), prove the fund-moving code with automated tests, and clear the ship-gate with one self-funded on-chain sweep.

**Architecture:** The sweep classifies non-native derivation profiles from the stored seed (JVM-testable `UtxoSource` seam), re-derives per-UTXO child keys, and builds+signs one full-sweep tx via the offline RFC6979 JNI `buildAndSignLegacySweep`, then broadcasts through the shared `Broadcaster → publishTransaction` chokepoint. Fixes land shared-path-first (so every normal send is hardened too), then sweep-specific correctness, then test layers, then the proof.

**Tech Stack:** Kotlin (core + Compose app), C/JNI (`native/src/main/jni/bridge` + `digibytewallet-core` submodule), JUnit (JVM) + instrumented `androidTest`, local `digibyte-cli` v8.26.2 for structural cross-checks and the self-funded proof.

**Design spec:** `docs/superpowers/specs/2026-07-02-legacy-sweep-hardening-and-proof-design.md`

## Global Constraints

- Android `minSdk = 26`, `targetSdk = 35`; regression devices: Note 8 (API 28) + `dgb-test-api33` AVD for instrumented tests.
- Seed material is always `ByteArray`, zeroed in `finally` on every path (never a `String`).
- Native (C) changes require `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug` before the APK is valid; a plain Kotlin change can use `./gradlew :app:assembleMainnetDebug`.
- `jni_transaction.c` / `jni_derive.c` live in the **main repo** (`native/src/main/jni/bridge/`), NOT the `digibytewallet-core` submodule — ordinary commits, no `GIT_DIR`/`GIT_WORK_TREE` dance. Only edits under `native/src/main/jni/digibytewallet-core/` use the submodule commit pattern (none planned here).
- Every commit uses conventional-commits and ends with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Fund-safety gate:** no real on-chain broadcast (Task 6 proof) until the user explicitly authorizes it. Never use a public/known test seed for a funded address — anyone can sweep it.
- **Do not drive the user's live tethered Note 8.** On-device steps that need gestures are user-driven; the agent observes read-only (logcat/screencap). UAF/KAT stress runs on the `dgb-test-api33` AVD or a dedicated handset.
- Ship bump per versioning policy (`3.X.Y`) only after the full regression + proof pass.

## Task order & dependencies

Strict order: **Task 1 (shared broadcast path) → Task 2 (classify/derivation) → Task 3 (amount provenance) → Task 4 (JVM classify tests) → Task 5 (signed-tx KAT) → Task 6 (mainnet proof + regression)**. Task 1 changes `SweepOutcome` to a 7-arg shape with a required `broadcastState`; Tasks 2–3 construct `SweepOutcome` and MUST pass a `BroadcastState` (use `FAILED` for build/derivation failures). Tasks 4–6 assert on `Result.allSubmitted` / `Result.anyPending` (never the removed `allSucceeded`).

## ⚠️ Convergence target — `LegacySweepService` shared members (READ BEFORE Tasks 1B/1C/2/3)

Tasks **1B, 1C, 2, and 3 each rewrite** `SweepOutcome` / `Result` / `sweepOneProfile` / `sweepFromSeed` from the *original* baseline, so applied in sequence a later task's code block would silently drop an earlier task's additions. **Apply them additively.** When a per-task code block below conflicts with the definitions here, **these are the final target**; the per-task steps still drive the TDD tests. Final shapes:

```kotlin
enum class BroadcastState { PENDING, RELAYED, FAILED }   // Task 1C

data class SweepOutcome(
    val profile: DerivationProfile,
    val txHex: String?,
    val txid: String?,
    val sweptSat: Long,
    val inputCount: Int,
    val failureReason: String?,
    val broadcastState: BroadcastState,               // Task 1C — REQUIRED, every construction passes it
    val skippedNoScript: List<String> = emptyList(),  // Task 2 (bug #4)
)

data class Result(val outcomes: List<SweepOutcome>) {
    val allSubmitted: Boolean = outcomes.isNotEmpty() && outcomes.all { it.broadcastState != BroadcastState.FAILED } // Task 1C (replaces allSucceeded)
    val anyPending: Boolean = outcomes.any { it.broadcastState == BroadcastState.PENDING }                          // Task 1C
}

// FINAL merged sweepOneProfile — carries #2 gate (caller), #3 indices, #4 skip, #5 durability, #6 broadcastState:
private fun sweepOneProfile(
    seed: ByteArray,
    result: RecoveryScanService.ProfileResult,
    destAddress: String,
    feePerKb: Long,
): SweepOutcome {
    val profile = result.profile
    val inputs = assembleSweepInputs(result)                              // Task 2 (#3/#4)
    if (inputs.txids.isEmpty()) {
        val reason = if (inputs.skippedNoScript.isNotEmpty())
            "all ${inputs.skippedNoScript.size} UTXO(s) missing scriptPubKey (old backend?)"
        else "no mappable UTXOs"
        return SweepOutcome(profile, null, null, 0L, 0, reason,
            broadcastState = BroadcastState.FAILED, skippedNoScript = inputs.skippedNoScript)
    }
    val signedHex = NativeBridge.buildAndSignLegacySweep(
        seedBytes = seed, hmacKey = profile.hmacKey, prefixPath = profile.prefixPath,
        txidsHex = inputs.txids.toTypedArray(), vouts = inputs.vouts.toIntArray(),
        amounts = inputs.amounts.toLongArray(), chainIndices = inputs.chains.toIntArray(),
        addressIndices = inputs.indices.toIntArray(), scriptPubKeysHex = inputs.scripts.toTypedArray(),
        destAddress = destAddress, feePerKb = feePerKb,
    ) ?: return SweepOutcome(profile, null, null, 0L, inputs.txids.size,
        "buildAndSignLegacySweep failed (sign mismatch or dust)",
        broadcastState = BroadcastState.FAILED, skippedNoScript = inputs.skippedNoScript)
    val txBytes = runCatching { hexToBytes(signedHex) }.getOrNull()
        ?: return SweepOutcome(profile, signedHex, null, inputs.totalIn, inputs.txids.size,
            "signed hex malformed (self-check failed)",
            broadcastState = BroadcastState.FAILED, skippedNoScript = inputs.skippedNoScript)
    val txid = Broadcaster.broadcast(txBytes)
    if (txid != null) {                                                  // Task 1B durability
        outgoingTxStore.record(txid = txid, sentSats = inputs.totalIn,
            feeSats = estimateFee(txBytes.size, feePerKb), toAddress = destAddress)
        walletTxPersister.persist()
    }
    return SweepOutcome(
        profile = profile, txHex = signedHex, txid = txid,
        sweptSat = inputs.totalIn, inputCount = inputs.txids.size,
        failureReason = if (txid == null) "publishTransaction returned null" else null,
        broadcastState = if (txid == null) BroadcastState.FAILED else BroadcastState.PENDING,  // Task 1C/#6
        skippedNoScript = inputs.skippedNoScript,
    )
}

// FINAL merged sweepFromSeed — Task 3 gate + BIP49 defer, every SweepOutcome passes broadcastState:
suspend fun sweepFromSeed(
    seedBytes: ByteArray,
    nonNativeResults: List<RecoveryScanService.ProfileResult>,
    destAddress: String,
    feePerKb: Long = 100_000L,
): Result {
    val outcomes = nonNativeResults.map { result ->
        if (result.profile.addressFormat == 2 /* BIP49 P2SH-P2WPKH */) {
            SweepOutcome(result.profile, null, null, 0L, 0,
                "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required",
                broadcastState = BroadcastState.FAILED)
        } else {
            val refusal = amountProvenanceGate(result)                  // Task 3 (bug #2)
            if (refusal != null) {
                SweepOutcome(result.profile, null, null, 0L, 0, refusal,
                    broadcastState = BroadcastState.FAILED)
            } else sweepOneProfile(seedBytes, result, destAddress, feePerKb)
        }
    }
    return Result(outcomes)
}
```

`LegacySweepService` must be constructed with `(outgoingTxStore, walletTxPersister)` (Task 1B) for `sweepOneProfile` above to compile; `estimateFee(sizeBytes, feePerKb)` is the private helper Task 1B adds. Any test that builds `SweepOutcome` directly (Tasks 4/5) uses the 8-field shape with an explicit `broadcastState`.

---

### Task 1A: #1 UAF — publishTransaction passes NULL/NULL, delete stack PublishContext

Kills a latent cross-thread use-after-free on the shared broadcast path: `publishTransaction`
hands `&ctx` (a stack local) to `BRPeerManagerPublishTx`, whose callback fires async on the
peer thread *after* the JNI frame returns → a write to freed stack. The stem path
(`publishTransactionStem`) already proves the fix: pass `NULL/NULL` and let Kotlin poll
`getRelayCount`. This hardens EVERY send, not just the sweep.

**Files:**
- Modify: `native/src/main/jni/bridge/jni_transaction.c:153-228` (delete `PublishContext` typedef + `publish_callback`; change the publish call)
- Test: on-device crash-buffer stress (no cheap unit test exists for a latent UAF — see Step 4)

**Submodule note:** `jni_transaction.c` is in the **main repo** (`native/src/main/jni/bridge/`),
NOT the `digibytewallet-core` submodule (`native/src/main/jni/digibytewallet-core`). `BRPeerManager.c`
is untouched. So this is an ordinary main-repo commit — the `GIT_DIR`/`GIT_WORK_TREE` submodule
pattern from CLAUDE.md does **not** apply here.

**Interfaces:**
- Consumes: `BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL)` — same call the proven stem path uses at `jni_transaction.c:276`.
- Produces: `publishTransaction` no longer allocates a stack `PublishContext` or registers `publish_callback`.

- [ ] **Step 1: Delete the `PublishContext` typedef and `publish_callback`.** Edit `native/src/main/jni/bridge/jni_transaction.c`. Old:
  ```c
  /* Publish callback context */
  typedef struct {
      int  error;
      int  done;
      char txid_hex[65]; /* 32 bytes * 2 hex chars + nul */
  } PublishContext;

  static void publish_callback(void *info, int error) {
      PublishContext *ctx = (PublishContext *)info;
      ctx->error = error;
      ctx->done = 1;
      if (error) {
          LOGE("publishTransaction: callback error=%d (%s)", error, strerror(error));
      } else {
          LOGD("publishTransaction: broadcast succeeded");
      }
  }

  JNIEXPORT jstring JNICALL
  Java_io_digibyte_core_bridge_NativeBridge_publishTransaction(JNIEnv *env, jobject thiz,
  ```
  New:
  ```c
  JNIEXPORT jstring JNICALL
  Java_io_digibyte_core_bridge_NativeBridge_publishTransaction(JNIEnv *env, jobject thiz,
  ```

- [ ] **Step 2: Pass NULL/NULL to BRPeerManagerPublishTx.** In the same file, old:
  ```c
      /* Publish — note: BRPeerManagerPublishTx takes ownership of tx, do NOT free it */
      PublishContext ctx = { .error = 0, .done = 0 };
      BRPeerManagerPublishTx(g_peerManager, tx, &ctx, publish_callback);

      /* The callback may be asynchronous; return txid immediately.
         The caller can monitor status via NativeCallback events. */
      LOGD("publishTransaction: submitted txid=%s", txidHex);
  ```
  New:
  ```c
      /* Publish — BRPeerManagerPublishTx takes ownership of tx, do NOT free it.
         Pass NULL info/callback: the callback fires asynchronously on the peer
         thread AFTER this JNI frame returns, so a stack-local PublishContext
         would be written cross-thread once it is out of scope — a use-after-free.
         Kotlin already polls acceptance via getRelayCount (Broadcaster embargo +
         SyncService.rebroadcastStrandedSends), so no native callback is needed.
         Matches publishTransactionStem's proven NULL/NULL pattern below. */
      BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL);

      LOGD("publishTransaction: submitted txid=%s", txidHex);
  ```

- [ ] **Step 3: Rebuild native + app.** This is a C change, so the native module must be rebuilt before the APK:
  ```bash
  cd /home/polloloco/digibytewallet-android && ./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
  ```
  Expected: `BUILD SUCCESSFUL`. (No submodule commit — see Submodule note above.)

- [ ] **Step 4: On-device UAF stress (no unit test — latent UAF).** There is no cheap JVM/instrumented test for a latent cross-thread UAF; the proof is (a) the code now matches the proven stem-path NULL/NULL pattern so the freed-stack write is structurally impossible, and (b) an on-device crash-buffer stress. Run on a **dedicated test handset that is NOT the user's live tethered device** (do not drive their live device — the churn loop bounces the UI and reads as a freeze). Install the freshly built APK, then clear the crash buffer, have a real small default-fee self-send performed (§7 pace 1), and immediately churn background/foreground so the peer thread would fire the (now-absent) callback after the JNI frame is gone:
  ```bash
  adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
  adb logcat -b crash -c   # clear crash buffer (SIGSEGV/FATAL survive release log-stripping)
  # --- perform one real small self-send in the app now (broadcast happens) ---
  for i in $(seq 1 40); do
    adb shell input keyevent KEYCODE_HOME
    adb shell am start -n io.digibyte/io.digibyte.MainActivity
    sleep 1
  done
  adb logcat -b crash -d | grep -iE "SIGSEGV|FATAL|use-after|io\.digibyte" \
    && echo "FAIL: crash detected" || echo "PASS: no crash"
  ```
  Expected: `PASS: no crash`. Also confirm real peers exist during the run (`adb shell cat /proc/net/tcp | grep -i 2EF8` shows connections) so the callback path was actually exercised.

- [ ] **Step 5: Commit.**
  ```bash
  cd /home/polloloco/digibytewallet-android && git add native/src/main/jni/bridge/jni_transaction.c && \
  git commit -m "$(cat <<'EOF'
  fix(native): publishTransaction passes NULL/NULL to BRPeerManagerPublishTx

  The publish callback fires async on the peer thread after the JNI frame
  returns; the stack-local PublishContext it wrote to is gone by then — a
  cross-thread use-after-free latent on every send. Delete the stack ctx +
  publish_callback and pass NULL/NULL, matching the proven stem path. Kotlin
  monitors acceptance via getRelayCount.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 1B: #5 durability — route sweep + DigiAsset broadcasts through OutgoingTxStore + persist

`SyncService.rebroadcastStrandedSends()` only replays `OutgoingTxStore.allTxids()`. Normal sends
(`TransactionBuilder`) already `record(...)` + `persist()` after broadcast, so they are covered; the
sweep and the DigiAsset send call `Broadcaster.broadcast` directly and are NOT — a force-stop ~1s
post-broadcast strands them. Mirror `TransactionBuilder`'s pattern: inject `OutgoingTxStore` +
`WalletTxPersister` and record + persist after a non-null broadcast.

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt:18-178` (constructor deps + record/persist + estimateFee)
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt:39-138` (inject deps, pass to `LegacySweepService(...)`)
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt:30-37,681-794` (nullable deps + record/persist)
- Modify: `app/src/main/java/io/digibyte/di/AppModule.kt:258-271` (pass singletons to `provideAssetManager`)
- Test: compile/build gate + on-device durability check (record path is Android-Context-backed like `TransactionBuilder`'s — not cheaply unit-testable without a refactor; see Step 6)

**Interfaces:**
- Consumes: `OutgoingTxStore.record(txid, sentSats, feeSats, toAddress)`, `WalletTxPersister.persist()`, `AppModule.provideOutgoingTxStore()`, `AppModule.provideWalletTxPersister()` (both already `@Provides @Singleton`).
- Produces: `LegacySweepService(outgoingTxStore, walletTxPersister)` constructor; `AssetManager(..., outgoingTxStore: OutgoingTxStore? = null, walletTxPersister: WalletTxPersister? = null)`.

- [ ] **Step 1: Add the two collaborators to LegacySweepService.** Edit `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt`. Add imports after the existing `import io.digibyte.core.dandelion.Broadcaster` line:
  ```kotlin
  import io.digibyte.core.OutgoingTxStore
  import io.digibyte.core.WalletTxPersister
  ```
  Change the class declaration. Old:
  ```kotlin
  class LegacySweepService {
  ```
  New:
  ```kotlin
  class LegacySweepService(
      private val outgoingTxStore: OutgoingTxStore,
      private val walletTxPersister: WalletTxPersister,
  ) {
  ```

- [ ] **Step 2: Record + persist after a non-null sweep broadcast, add estimateFee.** In `sweepOneProfile`, old:
  ```kotlin
          val txid = Broadcaster.broadcast(txBytes)
          return SweepOutcome(
              profile = profile,
              txHex = signedHex,
              txid = txid,
              sweptSat = totalIn,
              inputCount = txids.size,
              failureReason = if (txid == null) "publishTransaction returned null" else null,
          )
      }
  ```
  New:
  ```kotlin
          val txid = Broadcaster.broadcast(txBytes)
          if (txid != null) {
              // Durability: route the sweep through the same OutgoingTxStore +
              // WalletTxPersister the normal send uses so
              // SyncService.rebroadcastStrandedSends() re-publishes it if a
              // force-stop within ~1s of broadcast strands the stem before the
              // network relays it back. Best-effort — never affects on-chain state.
              outgoingTxStore.record(
                  txid = txid,
                  sentSats = totalIn,
                  feeSats = estimateFee(txBytes.size, feePerKb),
                  toAddress = destAddress,
              )
              walletTxPersister.persist()
          }
          return SweepOutcome(
              profile = profile,
              txHex = signedHex,
              txid = txid,
              sweptSat = totalIn,
              inputCount = txids.size,
              failureReason = if (txid == null) "publishTransaction returned null" else null,
          )
      }

      private fun estimateFee(signedSize: Int, feePerKb: Long): Long =
          (signedSize.toLong() * feePerKb + 999L) / 1000L
  ```
  (`estimateFee` mirrors `TransactionBuilder.estimateFee`; `feePerKb` is the `sweepOneProfile` param, `txBytes`/`totalIn`/`destAddress` are in scope.)

- [ ] **Step 3: Inject the deps into RecoverFundsViewModel and pass them.** Edit `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt`. Old:
  ```kotlin
  class RecoverFundsViewModel @Inject constructor(
      private val scanService: RecoveryScanService,
      private val seedProvider: SeedProvider,
  ) : ViewModel() {
  ```
  New:
  ```kotlin
  class RecoverFundsViewModel @Inject constructor(
      private val scanService: RecoveryScanService,
      private val seedProvider: SeedProvider,
      private val outgoingTxStore: io.digibyte.core.OutgoingTxStore,
      private val walletTxPersister: io.digibyte.core.WalletTxPersister,
  ) : ViewModel() {
  ```
  Then old:
  ```kotlin
                              LegacySweepService().sweepFromSeed(
  ```
  New:
  ```kotlin
                              LegacySweepService(outgoingTxStore, walletTxPersister).sweepFromSeed(
  ```
  (Both singletons are already `@Provides @Singleton` in `AppModule`, so Hilt injects them into the `@HiltViewModel` with no module change.)

- [ ] **Step 4: Verify DigiAsset test constructions before touching AssetManager.** The `AssetManager` constructor is used in unit tests without the new deps; confirm nullable-with-default is required so nothing breaks:
  ```bash
  cd /home/polloloco/digibytewallet-android && grep -rn "AssetManager(" core/src/test app/src | grep -v "class AssetManager"
  ```
  Expected: test/helper constructions that do NOT pass `outgoingTxStore`/`walletTxPersister` (e.g. `AssetHistoryBackfillTest`) — which is why Step 5 gives them `null` defaults.

- [ ] **Step 5: Add nullable deps to AssetManager and record + persist after the asset broadcast.** Edit `core/src/main/java/io/digibyte/core/asset/AssetManager.kt`. Old:
  ```kotlin
  class AssetManager(
      private val utxoDao: UtxoDao,
      private val transactionDao: TransactionDao,
      private val metadataDao: AssetMetadataDao,
      private val metadataService: AssetMetadataService,
      private val decoder: DigiAssetDecoder = DigiAssetDecoder(),
      private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient? = null,
  ) {
  ```
  New:
  ```kotlin
  class AssetManager(
      private val utxoDao: UtxoDao,
      private val transactionDao: TransactionDao,
      private val metadataDao: AssetMetadataDao,
      private val metadataService: AssetMetadataService,
      private val decoder: DigiAssetDecoder = DigiAssetDecoder(),
      private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient? = null,
      private val outgoingTxStore: io.digibyte.core.OutgoingTxStore? = null,
      private val walletTxPersister: io.digibyte.core.WalletTxPersister? = null,
  ) {
  ```
  Then in `sendAsset`, old:
  ```kotlin
          val signedBytes = signedHex.hexToByteArray() ?: return TxResult.Error("Bad signed-tx hex")
          val txid = Broadcaster.broadcast(signedBytes)
              ?: return TxResult.Error("Broadcast failed — check peer connection")

          return TxResult.Success(txid)
  ```
  New:
  ```kotlin
          val signedBytes = signedHex.hexToByteArray() ?: return TxResult.Error("Bad signed-tx hex")
          val txid = Broadcaster.broadcast(signedBytes)
              ?: return TxResult.Error("Broadcast failed — check peer connection")

          // Durability: record + persist through the same path the normal send
          // uses so SyncService.rebroadcastStrandedSends() re-publishes this asset
          // transfer if a force-stop within ~1s of broadcast strands the stem.
          // Best-effort — never affects on-chain state. sentSats is the recipient
          // DGB marker (the asset quantity isn't a DGB amount); feeSats is exact.
          outgoingTxStore?.record(
              txid = txid,
              sentSats = markerSats,
              feeSats = feeSats,
              toAddress = toAddress,
          )
          walletTxPersister?.persist()

          return TxResult.Success(txid)
  ```
  (`markerSats` is the `val` at the top of `sendAsset`; `feeSats` and `toAddress` are its params — all in scope at the broadcast site.)

- [ ] **Step 6: Wire the singletons into provideAssetManager.** Edit `app/src/main/java/io/digibyte/di/AppModule.kt`. Old:
  ```kotlin
      @Provides @Singleton
      fun provideAssetManager(
          utxoDao: UtxoDao,
          transactionDao: TransactionDao,
          metadataDao: AssetMetadataDao,
          metadataService: AssetMetadataService,
          assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
      ): AssetManager = AssetManager(
          utxoDao = utxoDao,
          transactionDao = transactionDao,
          metadataDao = metadataDao,
          metadataService = metadataService,
          assetNetworkClient = assetNetworkClient,
      )
  ```
  New:
  ```kotlin
      @Provides @Singleton
      fun provideAssetManager(
          utxoDao: UtxoDao,
          transactionDao: TransactionDao,
          metadataDao: AssetMetadataDao,
          metadataService: AssetMetadataService,
          assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient,
          outgoing: io.digibyte.core.OutgoingTxStore,
          persister: io.digibyte.core.WalletTxPersister,
      ): AssetManager = AssetManager(
          utxoDao = utxoDao,
          transactionDao = transactionDao,
          metadataDao = metadataDao,
          metadataService = metadataService,
          assetNetworkClient = assetNetworkClient,
          outgoingTxStore = outgoing,
          walletTxPersister = persister,
      )
  ```

- [ ] **Step 7: Build gate.** The record path is Android-Context-backed exactly like `TransactionBuilder`'s (which is likewise not unit-tested), so verification is a compile/build gate plus the on-device durability check below:
  ```bash
  cd /home/polloloco/digibytewallet-android && ./gradlew :core:testMainnetDebugUnitTest :app:assembleMainnetDebug
  ```
  Expected: `BUILD SUCCESSFUL` (existing tests, incl. the `AssetManager(...)` test constructions from Step 4, still compile against the `null`-default params).

- [ ] **Step 8: On-device durability check (state explicitly — no cheap unit test).** On a non-live test handset with the freshly built APK: trigger a sweep (or an asset send), and within ~1s of the broadcast `adb shell am force-stop io.digibyte`. Relaunch; confirm `SyncService.rebroadcastStrandedSends()` picks the txid up:
  ```bash
  adb logcat -c
  # trigger sweep/asset-send in app, then immediately:
  adb shell am force-stop io.digibyte
  adb shell am start -n io.digibyte/io.digibyte.MainActivity
  adb logcat -d | grep -iE "Dandelion recovery: .* re-published|rebroadcast"
  ```
  Expected: a `Dandelion recovery: <txid> re-published & propagated` line — i.e. the previously-uncovered sweep/asset tx is now in `OutgoingTxStore.allTxids()` and gets replayed.

- [ ] **Step 9: Commit.**
  ```bash
  cd /home/polloloco/digibytewallet-android && \
  git add core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt \
          app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt \
          core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
          app/src/main/java/io/digibyte/di/AppModule.kt && \
  git commit -m "$(cat <<'EOF'
  fix(recovery,assets): persist sweep + DigiAsset broadcasts for stranded-send recovery

  rebroadcastStrandedSends() only replays OutgoingTxStore.allTxids(). Normal
  sends record + persist after broadcast; the sweep and DigiAsset send called
  Broadcaster.broadcast directly and were uncovered — a force-stop ~1s after
  broadcast stranded them. Inject OutgoingTxStore + WalletTxPersister (same as
  TransactionBuilder) and record + persist after a non-null broadcast.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 1C: #6 false-success — treat a returned sweep txid as PENDING, not confirmed

`publishTransaction` returns a txid on *local relay*, not network/mempool acceptance, yet
`SweepOutcome`/`Result` and the UI treat "txid != null" as confirmed success — a sweep that never
propagates reports success. Introduce a `BroadcastState` (PENDING/RELAYED/FAILED); a returned txid is
`PENDING`, and `Result` exposes honest `allSubmitted`/`anyPending` instead of `allSucceeded`. TDD:
failing model test first. **Depends on 1B** — edits the broadcast return block 1B modified.

**Files:**
- Create: `core/src/test/java/io/digibyte/core/recovery/SweepOutcomeStateTest.kt`
- Modify: `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt:18-178` (BroadcastState enum, SweepOutcome field, Result props, all 7 constructions)
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt:544-662` (honest wording + pending caption)

**Interfaces:**
- Consumes: `DerivationProfile.BUILT_INS` (test fixture).
- Produces: `LegacySweepService.BroadcastState { PENDING, RELAYED, FAILED }`; `SweepOutcome.broadcastState: BroadcastState`; `Result.allSubmitted: Boolean`; `Result.anyPending: Boolean` (replaces `allSucceeded`, which has no external callers — verified).

- [ ] **Step 1 (RED): Write the failing model test.** Create `core/src/test/java/io/digibyte/core/recovery/SweepOutcomeStateTest.kt`:
  ```kotlin
  package io.digibyte.core.recovery

  import io.digibyte.core.recovery.LegacySweepService.BroadcastState
  import io.digibyte.core.recovery.LegacySweepService.Result
  import io.digibyte.core.recovery.LegacySweepService.SweepOutcome
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class SweepOutcomeStateTest {

      private val profile =
          DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

      /** A returned txid means the tx reached local relay only — PENDING, never
       *  confirmed. Result must not claim confirmed success on local relay alone. */
      @Test
      fun returnedTxid_isPendingNotConfirmed() {
          val outcome = SweepOutcome(
              profile = profile,
              txHex = "00",
              txid = "ab".repeat(32),
              sweptSat = 1_000L,
              inputCount = 1,
              failureReason = null,
              broadcastState = BroadcastState.PENDING,
          )
          assertEquals(BroadcastState.PENDING, outcome.broadcastState)
          val result = Result(listOf(outcome))
          assertTrue("a submitted tx counts as submitted", result.allSubmitted)
          assertTrue("a pending tx is surfaced as pending", result.anyPending)
      }

      @Test
      fun nullTxid_isFailed_andNotSubmitted() {
          val outcome = SweepOutcome(
              profile = profile,
              txHex = null,
              txid = null,
              sweptSat = 0L,
              inputCount = 0,
              failureReason = "broadcast failed — no peer accepted the sweep",
              broadcastState = BroadcastState.FAILED,
          )
          val result = Result(listOf(outcome))
          assertFalse("a failed broadcast is not 'submitted'", result.allSubmitted)
          assertFalse(result.anyPending)
      }
  }
  ```
  Run:
  ```bash
  cd /home/polloloco/digibytewallet-android && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.SweepOutcomeStateTest"
  ```
  Expected: compilation failure — `unresolved reference: BroadcastState` / `broadcastState` / `allSubmitted` / `anyPending`. That is the RED state.

- [ ] **Step 2 (GREEN): Add BroadcastState, the SweepOutcome field, and the Result props.** Edit `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt`. Old:
  ```kotlin
      data class SweepOutcome(
          val profile: DerivationProfile,
          val txHex: String?,      // signed hex, or null if we couldn't build
          val txid: String?,       // broadcast txid, or null on broadcast failure
          val sweptSat: Long,
          val inputCount: Int,
          val failureReason: String?,
      )

      data class Result(
          val outcomes: List<SweepOutcome>,
      ) {
          val totalSweptSat: Long = outcomes.sumOf { it.sweptSat }
          val allSucceeded: Boolean = outcomes.all { it.txid != null }
      }
  ```
  New:
  ```kotlin
      /** Acceptance state of a sweep broadcast. A returned txid means the tx
       *  reached local relay / mempool-pending only — NOT that the network
       *  accepted or confirmed it (bug #6). RELAYED is reserved for a future
       *  relay-count confirmation; today a submitted sweep terminates at PENDING
       *  and confirmation is observed later via normal BIP158/SPV sync. */
      enum class BroadcastState { PENDING, RELAYED, FAILED }

      data class SweepOutcome(
          val profile: DerivationProfile,
          val txHex: String?,      // signed hex, or null if we couldn't build
          val txid: String?,       // relay txid (PENDING), or null on broadcast failure
          val sweptSat: Long,
          val inputCount: Int,
          val failureReason: String?,
          val broadcastState: BroadcastState,
      )

      data class Result(
          val outcomes: List<SweepOutcome>,
      ) {
          val totalSweptSat: Long = outcomes.sumOf { it.sweptSat }
          /** True when every profile at least reached local relay (PENDING/RELAYED).
           *  Deliberately NOT "succeeded": a PENDING tx is submitted, not confirmed
           *  — never claim confirmed success on local relay alone (#6). */
          val allSubmitted: Boolean = outcomes.all { it.broadcastState != BroadcastState.FAILED }
          /** Any tx still awaiting network relay/confirmation. */
          val anyPending: Boolean = outcomes.any { it.broadcastState == BroadcastState.PENDING }
      }
  ```

- [ ] **Step 3 (GREEN): Add broadcastState to every SweepOutcome construction.** In the same file there are 6 build/derivation-failure constructions plus the terminal broadcast one. Update each. `sweep()` seed-fail — old:
  ```kotlin
                      SweepOutcome(it.profile, null, null, 0L, 0, "seed derivation failed")
  ```
  new:
  ```kotlin
                      SweepOutcome(it.profile, null, null, 0L, 0, "seed derivation failed", BroadcastState.FAILED)
  ```
  `sweepFromSeed()` BIP49 — old:
  ```kotlin
                  SweepOutcome(result.profile, null, null, 0L, 0,
                      "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required")
  ```
  new:
  ```kotlin
                  SweepOutcome(result.profile, null, null, 0L, 0,
                      "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required",
                      BroadcastState.FAILED)
  ```
  `sweepOneProfile` scriptPubKey-missing — old:
  ```kotlin
                  ?: return SweepOutcome(
                      profile, null, null, 0L, 0,
                      "scriptPubKey missing for ${utxo.address} (old backend?)"
                  )
  ```
  new:
  ```kotlin
                  ?: return SweepOutcome(
                      profile, null, null, 0L, 0,
                      "scriptPubKey missing for ${utxo.address} (old backend?)",
                      BroadcastState.FAILED
                  )
  ```
  "no mappable UTXOs" — old:
  ```kotlin
              return SweepOutcome(profile, null, null, 0L, 0, "no mappable UTXOs")
  ```
  new:
  ```kotlin
              return SweepOutcome(profile, null, null, 0L, 0, "no mappable UTXOs", BroadcastState.FAILED)
  ```
  `buildAndSignLegacySweep` failed — old:
  ```kotlin
          ) ?: return SweepOutcome(
              profile, null, null, 0L, txids.size,
              "buildAndSignLegacySweep failed (sign mismatch or dust)"
          )
  ```
  new:
  ```kotlin
          ) ?: return SweepOutcome(
              profile, null, null, 0L, txids.size,
              "buildAndSignLegacySweep failed (sign mismatch or dust)",
              BroadcastState.FAILED
          )
  ```
  "signed hex malformed" — old:
  ```kotlin
              ?: return SweepOutcome(
                  profile, signedHex, null, totalIn, txids.size,
                  "signed hex malformed (self-check failed)"
              )
  ```
  new:
  ```kotlin
              ?: return SweepOutcome(
                  profile, signedHex, null, totalIn, txids.size,
                  "signed hex malformed (self-check failed)",
                  BroadcastState.FAILED
              )
  ```

- [ ] **Step 4 (GREEN): Terminal broadcast outcome carries PENDING/FAILED (edits 1B's return block).** In `sweepOneProfile`, after 1B this block reads as below. Old:
  ```kotlin
          return SweepOutcome(
              profile = profile,
              txHex = signedHex,
              txid = txid,
              sweptSat = totalIn,
              inputCount = txids.size,
              failureReason = if (txid == null) "publishTransaction returned null" else null,
          )
  ```
  New:
  ```kotlin
          return SweepOutcome(
              profile = profile,
              txHex = signedHex,
              txid = txid,
              sweptSat = totalIn,
              inputCount = txids.size,
              failureReason = if (txid == null) "broadcast failed — no peer accepted the sweep" else null,
              // A non-null txid is local relay only — PENDING, never confirmed (#6).
              broadcastState = if (txid == null) BroadcastState.FAILED else BroadcastState.PENDING,
          )
  ```

- [ ] **Step 5 (GREEN): Make the sweep-result UI honest (no confirmed-success claim).** Edit `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt`. Header — old:
  ```kotlin
                  Text(
                      text = "Sweep complete",
  ```
  new:
  ```kotlin
                  Text(
                      text = "Sweep submitted",
  ```
  `OutcomeCard` "succeeded" derivation — old:
  ```kotlin
      val succeeded = outcome.txid != null
  ```
  new:
  ```kotlin
      // "succeeded" here means the broadcast was submitted (reached local relay),
      // NOT confirmed — a PENDING tx still shows a check plus the pending caption
      // below so we never claim confirmed success on local relay alone (#6).
      val succeeded = outcome.broadcastState != LegacySweepService.BroadcastState.FAILED
  ```
  Add a pending caption after the TXID text — old:
  ```kotlin
                  Text(
                      text = txid,
                      color = ACCENT,
                      style = MaterialTheme.typography.bodySmall,
                      fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis
                  )
              } else if (!succeeded) {
  ```
  new:
  ```kotlin
                  Text(
                      text = txid,
                      color = ACCENT,
                      style = MaterialTheme.typography.bodySmall,
                      fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis
                  )
                  Spacer(Modifier.height(6.dp))
                  Text(
                      text = "Pending network confirmation",
                      color = MUTED,
                      style = MaterialTheme.typography.labelSmall
                  )
              } else if (!succeeded) {
  ```
  (`MUTED` and `LegacySweepService` are already imported in this file; the existing subtitle "Recovered funds will appear once confirmed." stays.)

- [ ] **Step 6 (GREEN): Run the model test + build.**
  ```bash
  cd /home/polloloco/digibytewallet-android && \
  ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.SweepOutcomeStateTest" && \
  ./gradlew testMainnetDebugUnitTest :app:assembleMainnetDebug
  ```
  Expected: `SweepOutcomeStateTest` passes (2 tests), full `testMainnetDebugUnitTest` `BUILD SUCCESSFUL`, and `app:assembleMainnetDebug` `BUILD SUCCESSFUL` (the UI compiles against `BroadcastState`; `allSucceeded` had no external callers so its removal breaks nothing).

- [ ] **Step 7: Commit.**
  ```bash
  cd /home/polloloco/digibytewallet-android && \
  git add core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt \
          core/src/test/java/io/digibyte/core/recovery/SweepOutcomeStateTest.kt \
          app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt && \
  git commit -m "$(cat <<'EOF'
  fix(recovery): treat a returned sweep txid as PENDING, not confirmed success

  publishTransaction returns a txid on local relay, not network acceptance, so
  a sweep that never propagates was reporting success. Add BroadcastState
  (PENDING/RELAYED/FAILED); a returned txid is PENDING. Result exposes honest
  allSubmitted/anyPending instead of allSucceeded, and the result screen says
  "Sweep submitted" with a "Pending network confirmation" caption.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task: Sweep classify/derivation correctness (bugs #3 wrong-key desync, #4 null-script abort, #8 double-classify)

Fixes three confirmed defects on the legacy-funds sweep path, all JVM-testable (no JNI, no funds):
- **#3 (wrong-key desync):** `deriveAllProfiles` filters out empty derived slots, then `sweepOneProfile` reconstructs each UTXO's `(chain,index)` **by position** vs `gapExternal`. One dropped middle slot mis-maps every later input to the wrong child key → invalid signatures. Fix: carry explicit `(chain,index)` from derivation (`DerivedAddress`) through `ProfileResult` into the sweeper.
- **#4 (null-script abort):** the first UTXO with `scriptPubKeyHex == null` **returns** and aborts the entire profile. Fix: `continue`, collect skipped addresses, surface them in `SweepOutcome.skippedNoScript`.
- **#8 (double-classify):** onboarding classifies twice (informational `scan` then `RecoverFundsViewModel.classify` → `scanFromSeed`), hitting the 429-prone reconcile backend twice. Fix: memoize the last `State.Done` on the `@Singleton` `RecoveryScanService`, keyed by the derived-address set.

**Files:**
- Create: `core/src/main/java/io/digibyte/core/recovery/DerivedAddress.kt`
- Create (test): `core/src/test/java/io/digibyte/core/recovery/RecoveryDerivationTest.kt`
- Create (test): `core/src/test/java/io/digibyte/core/recovery/LegacySweepInputsTest.kt`
- Modify: `core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt` (ProfileResult :50-61; `_state` field :63-64; `classifyDerived` :73-110; `deriveAllProfiles` :184-201)
- Modify: `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt` (SweepOutcome :20-27; `sweepOneProfile` :80-165; append after class-close :178)
- Modify (test): `core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt` (:13-25 add `fetchCount`)
- Modify (test): `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt` (:21-23, :32-34 wrap in `DerivedAddress`; append #8 test)
- Test cmd (targeted): `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.*"`
- Test cmd (repo gate): `./gradlew testMainnetDebugUnitTest`

**Interfaces:**
- Consumes: `NativeBridge.deriveAddresses(...) : Array<String>?` (returns `external[0..gapExternal-1]` then `internal[0..gapInternal-1]`, empty strings where derivation failed); `UtxoEntry(txid, vout, amountSatoshi, address, blockHeight, scriptPubKeyHex: String? = null)`; `UtxoSource.fetchUtxos(addresses): ReconcileResult?`.
- Produces:
  - `data class DerivedAddress(val address: String, val chain: Int, val index: Int)` (chain 0=external, 1=internal)
  - `internal fun mapDerived(raw: Array<String>, gapExternal: Int): List<DerivedAddress>`
  - `RecoveryScanService.ProfileResult(profile, addresses, derivedAddresses: List<DerivedAddress>, utxos, rawTxs, reachableBackend=true)` with `val totalSat: Long`
  - `RecoveryScanService.classifyDerived(derivedByProfile: Map<DerivationProfile, List<DerivedAddress>>): State.Done`
  - `internal data class SweepInputs(txids, vouts, amounts, chains, indices, scripts, totalIn: Long, skippedNoScript: List<String>)`
  - `internal fun assembleSweepInputs(result: RecoveryScanService.ProfileResult): SweepInputs`
  - `LegacySweepService.SweepOutcome(profile, txHex, txid, sweptSat, inputCount, failureReason, skippedNoScript: List<String> = emptyList())`

---

- [ ] **Step 1: RED — #3 derivation test targets `mapDerived`/`DerivedAddress` (do not exist yet)**

Create `core/src/test/java/io/digibyte/core/recovery/RecoveryDerivationTest.kt`:
```kotlin
package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryDerivationTest {

    /** #3: dropping an empty middle slot must NOT shift a surviving address's
     *  (chain,index). The old code filtered empties, then indexed by position —
     *  so "addrE2" would have mapped to index 1 and "addrI0" to (chain 0, 2). */
    @Test
    fun mapDerived_droppedMiddleSlot_keepsTrueIndices() {
        // gapExternal = 3 → raw positions 0..2 are external, 3+ internal.
        val raw = arrayOf("addrE0", "", "addrE2", "addrI0")

        val derived = mapDerived(raw, gapExternal = 3)

        assertEquals(3, derived.size) // empty slot dropped
        assertEquals(DerivedAddress("addrE0", chain = 0, index = 0), derived[0])
        assertEquals(DerivedAddress("addrE2", chain = 0, index = 2), derived[1])
        assertEquals(DerivedAddress("addrI0", chain = 1, index = 0), derived[2])
    }
}
```
Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryDerivationTest"
```
Expected: `BUILD FAILED` with test-compile errors `unresolved reference: mapDerived` and `unresolved reference: DerivedAddress`.

- [ ] **Step 2: GREEN — #3 derivation side (`DerivedAddress` + `mapDerived` + `ProfileResult.derivedAddresses` + `classifyDerived` signature)**

Create `core/src/main/java/io/digibyte/core/recovery/DerivedAddress.kt`:
```kotlin
package io.digibyte.core.recovery

/**
 * A derived address paired with its true position in the profile's derivation:
 * [chain] 0 = external (receive), 1 = internal (change); [index] is the child
 * index within that chain. Carried explicitly from derivation through
 * [RecoveryScanService.ProfileResult] to the sweeper so a filtered-out empty
 * slot can never desync a UTXO from its signing key (bug #3).
 */
data class DerivedAddress(
    val address: String,
    val chain: Int,
    val index: Int,
)
```

In `RecoveryScanService.kt`, replace the `ProfileResult` data class (:50-61):
```kotlin
    data class ProfileResult(
        val profile: DerivationProfile,
        val addresses: List<String>,
        /** Same addresses as [addresses] but each tagged with its true
         *  (chain,index) from derivation. The sweeper reads THIS — never the
         *  positional index of [addresses] — so dropped empty slots can't
         *  mis-map an input to the wrong child key (bug #3). */
        val derivedAddresses: List<DerivedAddress>,
        val utxos: List<UtxoEntry>,
        val rawTxs: Map<String, io.digibyte.core.reconcile.RawTxEntry>,
        /** False when the reconcile call returned null (network/timeout/etc.).
         *  Distinguishes "we asked and got nothing" from "we never got an
         *  answer" — critical for honest UX during backend outages. */
        val reachableBackend: Boolean = true,
    ) {
        val totalSat: Long = utxos.sumOf { it.amountSatoshi }
    }
```

Replace the `classifyDerived` body (:73-110) — param type changes to `List<DerivedAddress>`; `addrs` is derived internally:
```kotlin
    suspend fun classifyDerived(
        derivedByProfile: Map<DerivationProfile, List<DerivedAddress>>,
    ): State.Done {
        val profileAddrs = derivedByProfile.entries.toList()
        val results = mutableListOf<ProfileResult>()

        // Serialize per-profile reconciles. Backend dev's note 2026-04-25:
        // the digiscope reconcile endpoint serializes requests internally
        // and rejects concurrent ones with HTTP 429. Firing 6 profiles in
        // parallel from one wallet was both slower (queue + retries) and
        // poisoned the backend's circuit breaker. One at a time is faster
        // in practice and friendlier to the shared infra.
        for ((i, entry) in profileAddrs.withIndex()) {
            val (profile, derived) = entry
            val addrs = derived.map { it.address }
            _state.value = State.Scanning(
                "Reconciling profile ${i + 1}/${profileAddrs.size}: ${profile.label}",
                0.4f + (0.5f * i / profileAddrs.size.coerceAtLeast(1)),
            )
            val result = if (derived.isEmpty()) {
                // No addresses derived for this profile (rare: BIP49 etc.
                // when JNI returns empty). Treat as "checked, empty"
                // rather than backend-failure.
                ProfileResult(profile, addrs, derived, emptyList(), emptyMap(), reachableBackend = true)
            } else {
                val fetched = utxoSource.fetchUtxos(addrs)
                ProfileResult(
                    profile = profile,
                    addresses = addrs,
                    derivedAddresses = derived,
                    utxos = fetched?.utxos ?: emptyList(),
                    rawTxs = fetched?.rawTxs ?: emptyMap(),
                    reachableBackend = fetched != null,
                )
            }
            results.add(result)
        }

        return State.Done(results)
    }
```

Replace `deriveAllProfiles` + the file's closing brace (:184-201) with the new function that assigns `(chain,index)` from the RAW position, plus a top-level `mapDerived`:
```kotlin
    private fun deriveAllProfiles(
        seedBytes: ByteArray,
    ): Map<DerivationProfile, List<DerivedAddress>> {
        val result = LinkedHashMap<DerivationProfile, List<DerivedAddress>>(profiles.size)
        for (profile in profiles) {
            val arr = NativeBridge.deriveAddresses(
                seedBytes,
                profile.hmacKey,
                profile.prefixPath,
                profile.gapExternal,
                profile.gapInternal,
                profile.addressFormat,
            ) ?: emptyArray()
            result[profile] = mapDerived(arr, profile.gapExternal)
        }
        return result
    }
}

/**
 * Tag each raw derived address with its true (chain,index) from its RAW array
 * position — external[0..gapExternal-1] then internal[…] — and drop empty
 * slots. Computing (chain,index) BEFORE filtering is the #3 fix: a dropped
 * empty slot can no longer shift a surviving address onto the wrong child key.
 * Pure + JNI-free so it is unit-testable.
 */
internal fun mapDerived(raw: Array<String>, gapExternal: Int): List<DerivedAddress> =
    raw.mapIndexedNotNull { pos, addr ->
        when {
            addr.isEmpty() -> null
            pos < gapExternal -> DerivedAddress(addr, chain = 0, index = pos)
            else -> DerivedAddress(addr, chain = 1, index = pos - gapExternal)
        }
    }
```

Update the two existing calls in `RecoveryScanClassifyTest.kt` to the new param type. Replace (:21-23):
```kotlin
        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0))),
        )
```
Replace (:32-34):
```kotlin
        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(DerivedAddress("DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk", chain = 0, index = 0))),
        )
```

Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryDerivationTest" --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"
```
Expected: `BUILD SUCCESSFUL` — `mapDerived_droppedMiddleSlot_keepsTrueIndices`, `classify_marksNonNativeWithFunds`, `classify_backendDown_flagsUnreachable` all pass. (`LegacySweepService` still compiles: it reads `result.addresses` and uses the old positional logic — rewired in Step 4.)

- [ ] **Step 2b: Commit #3 derivation side**
```bash
git add core/src/main/java/io/digibyte/core/recovery/DerivedAddress.kt \
        core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt \
        core/src/test/java/io/digibyte/core/recovery/RecoveryDerivationTest.kt \
        core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt
git commit -m "$(cat <<'EOF'
fix(recovery): carry explicit (chain,index) from derivation (bug #3, derivation side)

Assign each derived address its true (chain,index) from its RAW array
position before filtering empty slots, and thread it through
ProfileResult.derivedAddresses. Prevents a dropped empty slot from
shifting later addresses onto the wrong child key. Sweep-side consumption
lands next.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: commit succeeds on `phase1-modernization`.

- [ ] **Step 3: RED — #3 sweep-side + #4 tests target `assembleSweepInputs`/`SweepInputs` (do not exist yet)**

Create `core/src/test/java/io/digibyte/core/recovery/LegacySweepInputsTest.kt`:
```kotlin
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacySweepInputsTest {
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

    private fun profileWith(
        derived: List<DerivedAddress>,
        utxos: List<UtxoEntry>,
    ) = RecoveryScanService.ProfileResult(
        profile = legacyProfile,
        addresses = derived.map { it.address },
        derivedAddresses = derived,
        utxos = utxos,
        rawTxs = emptyMap(),
    )

    /** #3: a UTXO on the post-gap address signs with its CARRIED (chain,index),
     *  even though an empty slot was filtered out of the derived list. The old
     *  positional logic would have signed "addrE2" as index 1 and "addrI0" as
     *  external (chain 0, index 2). */
    @Test
    fun assembleSweepInputs_usesCarriedChainIndex_notPosition() {
        val derived = mapDerived(arrayOf("addrE0", "", "addrE2", "addrI0"), gapExternal = 3)
        val result = profileWith(
            derived,
            listOf(
                UtxoEntry("bb".repeat(32), 1, 500L, "addrE2", 10L, "76a914bb88ac"),
                UtxoEntry("cc".repeat(32), 0, 700L, "addrI0", 11L, "76a914cc88ac"),
            ),
        )

        val inputs = assembleSweepInputs(result)

        assertEquals(listOf(0, 1), inputs.chains)  // external, then internal
        assertEquals(listOf(2, 0), inputs.indices) // true derivation indices
        assertEquals(1200L, inputs.totalIn)
        assertEquals(emptyList<String>(), inputs.skippedNoScript)
    }

    /** #4: one null-scriptPubKey row is collected + skipped, not fatal to the
     *  whole profile. The good UTXO still builds; the bad address is reported. */
    @Test
    fun assembleSweepInputs_nullScript_skipsOneKeepsRest() {
        val derived = mapDerived(arrayOf("addrE0", "addrE1"), gapExternal = 200)
        val result = profileWith(
            derived,
            listOf(
                UtxoEntry("dd".repeat(32), 0, 400L, "addrE0", 10L, scriptPubKeyHex = null),
                UtxoEntry("ee".repeat(32), 0, 900L, "addrE1", 11L, "76a914ee88ac"),
            ),
        )

        val inputs = assembleSweepInputs(result)

        assertEquals(1, inputs.txids.size)               // only the good one
        assertEquals(900L, inputs.totalIn)               // null-script amount NOT counted
        assertEquals(listOf("addrE0"), inputs.skippedNoScript)
    }
}
```
Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.LegacySweepInputsTest"
```
Expected: `BUILD FAILED` with `unresolved reference: assembleSweepInputs` (and `SweepInputs` members `chains`/`indices`/`skippedNoScript`).

- [ ] **Step 4: GREEN — #3 sweep-side + #4 (`assembleSweepInputs`, `SweepOutcome.skippedNoScript`, rewrite `sweepOneProfile`)**

In `LegacySweepService.kt`, replace the `SweepOutcome` data class (:20-27) to add the skipped field:
```kotlin
    data class SweepOutcome(
        val profile: DerivationProfile,
        val txHex: String?,      // signed hex, or null if we couldn't build
        val txid: String?,       // broadcast txid, or null on broadcast failure
        val sweptSat: Long,
        val inputCount: Int,
        val failureReason: String?,
        /** Addresses whose backend row had no scriptPubKey and were skipped
         *  rather than aborting the profile (bug #4). Empty on a clean sweep. */
        val skippedNoScript: List<String> = emptyList(),
    )
```

Replace `sweepOneProfile` (:80-165) to consume the carried `(chain,index)` via `assembleSweepInputs`:
```kotlin
    private fun sweepOneProfile(
        seed: ByteArray,
        result: RecoveryScanService.ProfileResult,
        destAddress: String,
        feePerKb: Long,
    ): SweepOutcome {
        val profile = result.profile
        val inputs = assembleSweepInputs(result)

        if (inputs.txids.isEmpty()) {
            val reason = if (inputs.skippedNoScript.isNotEmpty())
                "all ${inputs.skippedNoScript.size} UTXO(s) missing scriptPubKey (old backend?)"
            else "no mappable UTXOs"
            return SweepOutcome(
                profile, null, null, 0L, 0, reason,
                skippedNoScript = inputs.skippedNoScript,
            )
        }

        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed,
            hmacKey = profile.hmacKey,
            prefixPath = profile.prefixPath,
            txidsHex = inputs.txids.toTypedArray(),
            vouts = inputs.vouts.toIntArray(),
            amounts = inputs.amounts.toLongArray(),
            chainIndices = inputs.chains.toIntArray(),
            addressIndices = inputs.indices.toIntArray(),
            scriptPubKeysHex = inputs.scripts.toTypedArray(),
            destAddress = destAddress,
            feePerKb = feePerKb,
        ) ?: return SweepOutcome(
            profile, null, null, 0L, inputs.txids.size,
            "buildAndSignLegacySweep failed (sign mismatch or dust)",
            skippedNoScript = inputs.skippedNoScript,
        )

        // Broadcast via the existing publishTransaction JNI — it takes raw
        // bytes, not hex, so decode here.
        val txBytes = runCatching { hexToBytes(signedHex) }.getOrNull()
            ?: return SweepOutcome(
                profile, signedHex, null, inputs.totalIn, inputs.txids.size,
                "signed hex malformed (self-check failed)",
                skippedNoScript = inputs.skippedNoScript,
            )

        val txid = Broadcaster.broadcast(txBytes)
        return SweepOutcome(
            profile = profile,
            txHex = signedHex,
            txid = txid,
            sweptSat = inputs.totalIn,
            inputCount = inputs.txids.size,
            failureReason = if (txid == null) "publishTransaction returned null" else null,
            skippedNoScript = inputs.skippedNoScript,
        )
    }
```

Append the pure, testable input-assembler after the class-closing brace (after :178):
```kotlin

/** Input arrays for one sweep tx, assembled from a profile's UTXOs. Each UTXO's
 *  (chain,index) comes straight from its [DerivedAddress] — no positional
 *  reconstruction (bug #3 fix). UTXOs whose backend row lacks a scriptPubKey are
 *  collected in [skippedNoScript] and skipped, not fatal to the profile (bug #4).
 *  Pure + JNI-free so it is unit-testable. */
internal data class SweepInputs(
    val txids: List<String>,
    val vouts: List<Int>,
    val amounts: List<Long>,
    val chains: List<Int>,
    val indices: List<Int>,
    val scripts: List<String>,
    val totalIn: Long,
    val skippedNoScript: List<String>,
)

internal fun assembleSweepInputs(
    result: RecoveryScanService.ProfileResult,
): SweepInputs {
    val byAddress: Map<String, DerivedAddress> =
        result.derivedAddresses.associateBy { it.address }

    val txids = mutableListOf<String>()
    val vouts = mutableListOf<Int>()
    val amounts = mutableListOf<Long>()
    val chains = mutableListOf<Int>()
    val indices = mutableListOf<Int>()
    val scripts = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var totalIn = 0L

    for (utxo in result.utxos) {
        val derived = byAddress[utxo.address] ?: continue
        val script = utxo.scriptPubKeyHex
        if (script == null) {
            // #4: one missing-scriptPubKey row must not abort the whole profile.
            skipped += utxo.address
            continue
        }
        txids += utxo.txid
        vouts += utxo.vout
        amounts += utxo.amountSatoshi
        chains += derived.chain      // #3: carried from derivation, not reconstructed
        indices += derived.index
        scripts += script
        totalIn += utxo.amountSatoshi
    }
    return SweepInputs(txids, vouts, amounts, chains, indices, scripts, totalIn, skipped)
}
```

Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.LegacySweepInputsTest"
```
Expected: `BUILD SUCCESSFUL` — `assembleSweepInputs_usesCarriedChainIndex_notPosition` and `assembleSweepInputs_nullScript_skipsOneKeepsRest` pass.

- [ ] **Step 4b: Commit #3 sweep-side + #4**
```bash
git add core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt \
        core/src/test/java/io/digibyte/core/recovery/LegacySweepInputsTest.kt
git commit -m "$(cat <<'EOF'
fix(recovery): sweep consumes carried (chain,index); skip null-script UTXOs (bugs #3, #4)

sweepOneProfile now reads each UTXO's (chain,index) straight from its
DerivedAddress via assembleSweepInputs — no positional reconstruction vs
gapExternal, so a dropped empty slot can't sign the wrong child key (#3).
A UTXO with a null scriptPubKey is collected in SweepOutcome.skippedNoScript
and skipped instead of aborting the whole profile (#4).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: commit succeeds.

- [ ] **Step 5: RED — #8 dedupe test (add `fetchCount` to fake; assert backend hit once — currently 2)**

In `FakeUtxoSource.kt`, add a call counter. Replace the class body (:13-25 region) so `fetchUtxos` increments it:
```kotlin
) : UtxoSource {
    var lastQueried: List<String>? = null
        private set

    /** Number of times [fetchUtxos] actually reached the (fake) backend.
     *  Used by the #8 dedupe test to prove a repeated classify serves cache. */
    var fetchCount = 0
        private set

    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? {
        fetchCount++
        lastQueried = addresses
        if (!reachable) return null
        val utxos = addresses.flatMap { byAddress[it]?.utxos.orEmpty() }
        val rawTxs = addresses.flatMap { byAddress[it]?.rawTxs?.entries.orEmpty() }
            .associate { it.key to it.value }
        val chainHeight = addresses.mapNotNull { byAddress[it]?.chainHeight }.maxOrNull() ?: 0L
        return ReconcileResult(utxos, rawTxs, chainHeight)
    }
}
```

Append a test to `RecoveryScanClassifyTest.kt` (before the final closing brace):
```kotlin
    @Test
    fun classify_repeatSameDerivedSet_hitsBackendOnce() = runBlocking {
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val utxo = UtxoEntry("aa".repeat(32), 0, 100_000L, addr, 100L, "76a914aa88ac")
        val source = FakeUtxoSource(mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 200L)))
        val service = RecoveryScanService(source)

        // Two structurally-equal derived sets — the second models
        // RecoverFundsViewModel.classify re-running after the onboarding scan.
        val d1 = mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0)))
        val d2 = mapOf(legacyProfile to listOf(DerivedAddress(addr, chain = 0, index = 0)))

        val first = service.classifyDerived(d1)
        val second = service.classifyDerived(d2)

        assertEquals(1, source.fetchCount) // second call served from cache
        assertEquals(first.totalBalanceSat, second.totalBalanceSat)
    }
```
Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"
```
Expected: `BUILD FAILED` — `classify_repeatSameDerivedSet_hitsBackendOnce FAILED` with `java.lang.AssertionError: expected:<1> but was:<2>` (no cache yet). The other two tests still pass.

- [ ] **Step 6: GREEN — #8 memoize `classifyDerived` on the singleton**

In `RecoveryScanService.kt`, add cache members right after `_state`/`state` (:63-64):
```kotlin
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** #8: RecoveryScanService is a @Singleton, so the onboarding path's
     *  informational scan and RecoverFundsViewModel.classify share this
     *  instance. Cache the last usable Done keyed by the derived-address set so
     *  the second, structurally-identical classify is served without a second
     *  round of reconcile-backend calls (the endpoint is 429-prone). A
     *  different seed/profile produces a different key → cache miss → re-scan. */
    private data class ClassifyCache(
        val key: Map<DerivationProfile, List<DerivedAddress>>,
        val done: State.Done,
    )
    @Volatile private var lastClassify: ClassifyCache? = null
```

Add the cache check at the top of `classifyDerived` (right after the opening `): State.Done {`):
```kotlin
    ): State.Done {
        lastClassify?.let { cached ->
            if (cached.key == derivedByProfile) {
                _state.value = cached.done
                return cached.done
            }
        }
        val profileAddrs = derivedByProfile.entries.toList()
```

Replace the final `return State.Done(results)` of `classifyDerived` with a store-then-return (only cache when the backend was actually reachable, so a retry after an outage still retries):
```kotlin
        val done = State.Done(results)
        if (!done.allBackendUnreachable) {
            lastClassify = ClassifyCache(derivedByProfile, done)
        }
        return done
```
Run:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"
```
Expected: `BUILD SUCCESSFUL` — `classify_repeatSameDerivedSet_hitsBackendOnce`, `classify_marksNonNativeWithFunds`, `classify_backendDown_flagsUnreachable` all pass.

- [ ] **Step 6b: Commit #8**
```bash
git add core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt \
        core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt \
        core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt
git commit -m "$(cat <<'EOF'
perf(recovery): de-dupe onboarding classify to hit reconcile backend once (bug #8)

The onboarding path classified twice — the informational scan and
RecoverFundsViewModel.classify — doubling load on the 429-prone reconcile
endpoint. Memoize the last usable Done on the singleton RecoveryScanService,
keyed by the derived-address set; a different seed/profile misses and
re-scans, and an unreachable-backend result is never cached so retries
still retry.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: commit succeeds.

- [ ] **Step 7: Verify whole cluster — full core unit suite + app build**
```bash
./gradlew :core:testMainnetDebugUnitTest && ./gradlew :app:assembleMainnetDebug
```
Expected: both `BUILD SUCCESSFUL`. All recovery tests green (`RecoveryDerivationTest`, `LegacySweepInputsTest`, `RecoveryScanClassifyTest`) and the app compiles with the new `ProfileResult.derivedAddresses` / `SweepOutcome.skippedNoScript` surface (no callers broke — `RecoveryScanScreen` still reads `result.addresses`, `RecoverFundsScreen` still reads `outcome.failureReason`). No new commit — verification only.

---

### Task: Amount-provenance hardening (bug #2 fund-loss)

The legacy P2PKH sighash does **not** commit to input amounts, so a stale/under-reported `amountSatoshi` still produces a consensus-valid signature; the tx then spends the *real* on-chain prevout and the unreported remainder is silently burned to fee. Full on-device prevout verification is heavy (a foreign address's prevout can only be known by fetching the prevout tx over SPV/BIP158) and is the belt-and-suspenders follow-on — **out of scope here**. This task lands the shippable, testable defense set: (a) Kotlin guard rejecting any UTXO with `amountSatoshi <= 0`; (b) assert `reachableBackend == true` before signing; (c) a native fee-sanity guard that refuses a multi-input sweep whose computed fee is ≥5% of the reported total; (d) a live-node `testmempoolaccept` assertion proving the network catches the over-report failure mode (outputs > inputs → `bad-txns-in-belowout`).

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt` (`sweepFromSeed` dispatch :63-78; add `amountProvenanceGate` after :78)
- Modify: `native/src/main/jni/bridge/jni_derive.c` (`buildAndSignLegacySweep`, insert guard between fee-clamp :523 and dust-check :525) — **main-repo file, NOT the submodule; plain `git add`, no `GIT_DIR`/`GIT_WORK_TREE`**
- Create: `core/src/test/java/io/digibyte/core/recovery/AmountProvenanceGateTest.kt` (JVM unit test)
- Create: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepAmountGuardTest.kt` (instrumented, offline)
- Create: `scripts/overreport-rejection-check.sh` (defense d live-node assertion)
- Create: `docs/operations/overreport-rejection-check.md` (defense d runbook)
- Test: `AmountProvenanceGateTest.kt`, `LegacySweepAmountGuardTest.kt`

**Interfaces:**
- Consumes (existing, verified this session):
  - `NativeBridge.buildAndSignLegacySweep(seedBytes: ByteArray, hmacKey: String, prefixPath: IntArray, txidsHex: Array<String>, vouts: IntArray, amounts: LongArray, chainIndices: IntArray, addressIndices: IntArray, scriptPubKeysHex: Array<String>, destAddress: String, feePerKb: Long): String?`
  - `NativeBridge.deriveAddresses(seedBytes: ByteArray, hmacKey: String, prefixPath: IntArray, gapExternal: Int, gapInternal: Int, addressFormat: Int): Array<String>?`
  - `NativeBridge.mnemonicToSeed(phraseBytes: ByteArray, passphrase: String?): ByteArray?`
  - `NativeBridge.addressToScriptPubKey(address: String): ByteArray?`
  - `RecoveryScanService.ProfileResult(profile: DerivationProfile, addresses: List<String>, utxos: List<UtxoEntry>, rawTxs: Map<String, RawTxEntry>, reachableBackend: Boolean = true)` with `.utxos` and `.reachableBackend`
  - `io.digibyte.core.reconcile.UtxoEntry(txid: String, vout: Int, amountSatoshi: Long, address: String, blockHeight: Long, scriptPubKeyHex: String? = null)`
  - `DerivationProfile.BUILT_INS` (label `"Legacy DigiByte mobile wallet"`, `hmacKey = "DigiByte seed"`, `prefixPath = m/0'`, `addressFormat = 0`)
- Produces:
  - `LegacySweepService.amountProvenanceGate(result: RecoveryScanService.ProfileResult): String?` (internal; `null` = safe, non-null = refusal reason)
  - `buildAndSignLegacySweep` returns `NULL` when `inputCount > 1 && totalIn <= fee*20` (behavior change, no signature change)
  - `scripts/overreport-rejection-check.sh <signed_tx_hex>`

---

- [ ] **Step 1: Write the failing JVM unit test for the amount-provenance gate (TDD red).** Create `core/src/test/java/io/digibyte/core/recovery/AmountProvenanceGateTest.kt`:

```kotlin
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #2 defense (a)+(b): the legacy P2PKH sighash is amount-blind, so a
 * stale/under-reported amount still signs valid and burns the remainder to
 * fee. amountProvenanceGate is a pure pre-sign gate that refuses the whole
 * profile-sweep on an unreachable backend or any non-positive UTXO amount.
 * No JNI — runs under ./gradlew :core:testMainnetDebugUnitTest.
 */
class AmountProvenanceGateTest {
    private val service = LegacySweepService()
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }
    private val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"

    private fun result(utxos: List<UtxoEntry>, reachable: Boolean = true) =
        RecoveryScanService.ProfileResult(
            profile = legacyProfile,
            addresses = listOf(addr),
            utxos = utxos,
            rawTxs = emptyMap(),
            reachableBackend = reachable,
        )

    private fun utxo(amount: Long) =
        UtxoEntry("aa".repeat(32), 0, amount, addr, 100L, "76a914${"11".repeat(20)}88ac")

    @Test
    fun gate_positiveAmounts_reachable_allows() {
        assertNull(service.amountProvenanceGate(result(listOf(utxo(100_000L), utxo(250_000L)))))
    }

    @Test
    fun gate_zeroAmount_refuses() {
        val reason = service.amountProvenanceGate(result(listOf(utxo(0L))))
        assertNotNull(reason)
        assertTrue(reason!!.contains("non-positive"))
    }

    @Test
    fun gate_negativeAmount_refuses() {
        assertNotNull(service.amountProvenanceGate(result(listOf(utxo(100_000L), utxo(-1L)))))
    }

    @Test
    fun gate_backendUnreachable_refuses() {
        val reason = service.amountProvenanceGate(result(listOf(utxo(100_000L)), reachable = false))
        assertNotNull(reason)
        assertTrue(reason!!.contains("unreachable"))
    }

    @Test
    fun sweepFromSeed_backendUnreachable_refusesWithoutSigning() = runBlocking {
        val res = service.sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = listOf(result(listOf(utxo(500_000L)), reachable = false)),
            destAddress = "dgb1qdestplaceholder",
        )
        assertNull(res.outcomes[0].txid)
        assertTrue(res.outcomes[0].failureReason!!.contains("unreachable"))
    }

    @Test
    fun sweepFromSeed_zeroAmountUtxo_refusesWithoutSigning() = runBlocking {
        val res = service.sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = listOf(result(listOf(utxo(0L)))),
            destAddress = "dgb1qdestplaceholder",
        )
        assertNull(res.outcomes[0].txid)
        assertTrue(res.outcomes[0].failureReason!!.contains("non-positive"))
    }
}
```

The two `sweepFromSeed_*` tests exercise the wiring **without touching JNI**: the gate short-circuits before `sweepOneProfile` is reached. Run to confirm RED:
```
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.AmountProvenanceGateTest"
```
Expected: `BUILD FAILED` with `e: .../AmountProvenanceGateTest.kt: unresolved reference: amountProvenanceGate` (the function does not exist yet).

- [ ] **Step 2: Implement `amountProvenanceGate` and wire it into `sweepFromSeed` (green).** In `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt`, replace the `sweepFromSeed` body (:63-78):

```kotlin
    suspend fun sweepFromSeed(
        seedBytes: ByteArray,
        nonNativeResults: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        feePerKb: Long = 100_000L,
    ): Result {
        val outcomes = nonNativeResults.map { result ->
            if (result.profile.addressFormat == 2 /* P2SH-P2WPKH / BIP49 */) {
                SweepOutcome(result.profile, null, null, 0L, 0,
                    "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required")
            } else {
                val refusal = amountProvenanceGate(result)
                if (refusal != null) {
                    SweepOutcome(result.profile, null, null, 0L, 0, refusal)
                } else {
                    sweepOneProfile(seedBytes, result, destAddress, feePerKb)
                }
            }
        }
        return Result(outcomes)
    }

    /**
     * Amount-provenance pre-sign gate (bug #2 — fund-loss defense).
     *
     * The legacy P2PKH sighash does NOT commit to input amounts, so a stale or
     * under-reported amountSatoshi still signs into a consensus-valid tx that
     * spends the REAL prevout and burns the unreported remainder to fee. We
     * cannot verify a foreign prevout on-device without fetching it, so we
     * apply the cheap, honest guards we CAN:
     *   - refuse if the reconcile backend was unreachable (amounts are
     *     unverified hints; never sign against a null reconcile result);
     *   - refuse if ANY UTXO reports a non-positive amount — a corrupt/hostile
     *     row, and because the sighash is amount-blind, one bad row means the
     *     whole response's amounts are untrustworthy, so we refuse the entire
     *     profile-sweep rather than sign a subset.
     * Returns a human-readable refusal reason, or null when the profile's
     * UTXOs are safe to hand to the signer. Pure — no JNI, unit-testable.
     */
    internal fun amountProvenanceGate(
        result: RecoveryScanService.ProfileResult,
    ): String? {
        if (!result.reachableBackend) {
            return "backend unreachable — refusing to sign against unverified input amounts"
        }
        val bad = result.utxos.firstOrNull { it.amountSatoshi <= 0L }
        if (bad != null) {
            return "non-positive amount ${bad.amountSatoshi} on ${bad.txid}:${bad.vout} — refusing sweep"
        }
        return null
    }
```

Re-run:
```
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.AmountProvenanceGateTest"
```
Expected: `BUILD SUCCESSFUL` (6 tests pass). Also confirm no regression in the sibling suite:
```
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.*"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the Kotlin gate.**
```
git add core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt \
        core/src/test/java/io/digibyte/core/recovery/AmountProvenanceGateTest.kt
git commit -m "fix(recovery): amount-provenance gate — refuse sweep on unreachable backend or non-positive amount

Bug #2 defense (a)+(b): the legacy P2PKH sighash is amount-blind, so a stale/
under-reported amountSatoshi still signs valid and silently burns the
remainder to fee. Add a pure, unit-tested pre-sign gate (amountProvenanceGate)
that refuses the whole profile-sweep when the reconcile backend was
unreachable or any UTXO reports amount <= 0. Wired ahead of sweepOneProfile so
no signing happens on refused profiles.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Write the failing instrumented native guard test (TDD red).** Create `native/src/androidTest/java/io/digibyte/native_core/LegacySweepAmountGuardTest.kt`:

```kotlin
package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline, deterministic proof of the native amount-provenance fee-sanity
 * guard in buildAndSignLegacySweep (bug #2, defense c).
 *
 * All three cases share the SAME two real legacy-DGB-seed inputs
 * (m/0'/0/0 and m/0'/0/1 under the BIP39 Trezor test vector — never funded on
 * mainnet) with their real matching P2PKH scriptPubKeys, differing ONLY in the
 * reported amounts. That isolates the guard: a refusal or a successful sign can
 * only be the amounts, not the keys/scripts. txids are synthetic (never
 * validated offline).
 *
 * Requires a booted AVD (dgb-test-api33). Build check only:
 *   ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepAmountGuardTest {

    private val HARD = 0x80000000.toInt()
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"
    private val hmac = "DigiByte seed"
    private val prefix = intArrayOf(0 or HARD)
    private val feePerKb = 100_000L // matches LegacySweepService default

    private class Fixture(
        val seed: ByteArray,
        val txids: Array<String>,
        val vouts: IntArray,
        val chains: IntArray,
        val indices: IntArray,
        val scripts: Array<String>,
        val dest: String,
    )

    /** Derive 3 legacy addrs: [0],[1] are the two inputs; [2] is the dest. */
    private fun fixture(): Fixture {
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)!!
        val addrs = NativeBridge.deriveAddresses(seed, hmac, prefix, 3, 0, 0)!!
        fun spk(a: String) =
            NativeBridge.addressToScriptPubKey(a)!!.joinToString("") { "%02x".format(it) }
        return Fixture(
            seed = seed,
            txids = arrayOf("11".repeat(32), "22".repeat(32)),
            vouts = intArrayOf(0, 0),
            chains = intArrayOf(0, 0),
            indices = intArrayOf(0, 1),
            scripts = arrayOf(spk(addrs[0]), spk(addrs[1])),
            dest = addrs[2],
        )
    }

    private fun build(f: Fixture, amounts: LongArray): String? =
        NativeBridge.buildAndSignLegacySweep(
            seedBytes = f.seed, hmacKey = hmac, prefixPath = prefix,
            txidsHex = f.txids, vouts = f.vouts, amounts = amounts,
            chainIndices = f.chains, addressIndices = f.indices,
            scriptPubKeysHex = f.scripts, destAddress = f.dest, feePerKb = feePerKb,
        )

    @Test
    fun feeSanityGuard_underReportedMultiInput_refuses() {
        val f = fixture()
        // 2 inputs => estSize=364B => fee=36_400 sat => fee*20=728_000.
        // totalIn=600_000 <= 728_000 trips the guard; 600_000 clears the dust
        // floor (fee+546=36_946), so ONLY the fee-sanity guard rejects this.
        val hex = build(f, longArrayOf(300_000L, 300_000L))
        f.seed.fill(0)
        assertNull("under-reported multi-input sweep must be refused", hex)
    }

    @Test
    fun feeSanityGuard_normalAmounts_signs() {
        val f = fixture()
        // totalIn=60_000_000 >> fee*20=728_000 => guard passes; real keys +
        // matching scripts => BRTransactionSign succeeds => non-null hex.
        val hex = build(f, longArrayOf(30_000_000L, 30_000_000L))
        f.seed.fill(0)
        assertNotNull("realistic-amount sweep must sign", hex)
    }

    @Test
    fun overReportedAmounts_signLocally_proveGuardCannotCatch() {
        val f = fixture()
        // Over-report is the case the on-device guard CANNOT catch (inflated
        // total makes fee a tiny fraction). It signs locally; only the network
        // (testmempoolaccept, defense d) rejects outputs>inputs.
        val hex = build(f, longArrayOf(5_000_000_000L, 5_000_000_000L))
        f.seed.fill(0)
        assertNotNull("over-reported sweep signs locally — network must reject it", hex)
        android.util.Log.i(
            "LegacySweepAmountGuard",
            "over-reported signed hex (feed to scripts/overreport-rejection-check.sh " +
                "against a REAL prevout during the mainnet proof) = $hex",
        )
    }
}
```

Confirm it compiles, then confirm RED on a booted AVD:
```
./gradlew :native:assembleMainnetDebugAndroidTest
./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepAmountGuardTest"
```
Expected: assemble `BUILD SUCCESSFUL`; connected run FAILS on `feeSanityGuard_underReportedMultiInput_refuses` with `java.lang.AssertionError: under-reported multi-input sweep must be refused expected null, but was:<02000000...>` (without the guard, the under-reported tx signs and returns hex). The other two tests pass.

- [ ] **Step 5: Add the native fee-sanity guard (green).** In `native/src/main/jni/bridge/jni_derive.c`, insert the guard between the fee clamp and the dust check. Replace:

```c
    if (fee < 1000) fee = 1000; /* DGB min relay */

    if (totalIn <= fee + 546 /* dust */) {
```

with:

```c
    if (fee < 1000) fee = 1000; /* DGB min relay */

    /* ── Amount-provenance fee-sanity guard (bug #2, fund-loss). ──
     * The legacy P2PKH sighash does NOT commit to input amounts, so a stale or
     * under-reported amounts[] still produces a VALID signature. The tx then
     * spends the REAL on-chain prevout value and the unreported remainder
     * (realValue - outAmount) is silently burned to fee by the network. We
     * cannot verify a FOREIGN prevout on-device without fetching it, but we
     * CAN refuse the pathological under-report: on a multi-input consolidation
     * a legitimate total dwarfs the fee, so a computed fee that is >= 5% of the
     * reported total (fee*20 >= totalIn) means the amounts are almost certainly
     * stale/under-reported. Refuse rather than sign a lopsided sweep.
     * Single-input sweeps are exempt — a lone small UTXO can legitimately have
     * the fee be a meaningful fraction of its value (the dust check below still
     * protects that case). fee*20 cannot overflow: fee is bounded by
     * estSize*feePerKb. */
    if (inputCount > 1 && totalIn <= fee * 20) {
        LOGW("buildAndSignLegacySweep: fee-sanity guard tripped "
             "(inputs=%d fee=%llu totalIn=%llu) — refusing under-reported sweep",
             (int)inputCount, (unsigned long long)fee, (unsigned long long)totalIn);
        for (jsize i = 0; i < inputCount; i++) BRKeyClean(&keys[i]);
        free(keys);
        BRTransactionFree(tx);
        (*env)->ReleaseStringUTFChars(env, hmacKey, hmac);
        secure_zero(seedRaw, (size_t)seedLen);
        (*env)->ReleaseByteArrayElements(env, seedBytes, seedRaw, JNI_ABORT);
        return NULL;
    }

    if (totalIn <= fee + 546 /* dust */) {
```

Rebuild native + app, then re-run the instrumented test:
```
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepAmountGuardTest"
```
Expected: `BUILD SUCCESSFUL`, all 3 tests pass (`feeSanityGuard_underReportedMultiInput_refuses` now returns null → passes; the two positive/over-report cases still sign → pass). Sanity-check no regression in the sibling native suites that sign real sweeps (single-input and realistic multi-input are unaffected — guard requires `inputCount > 1` AND fee ≥ 5% of total):
```
./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepDerivationTest" --tests "io.digibyte.native_core.UniversalRestoreTest"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the native guard + instrumented test.** (Main-repo file — no submodule pattern.)
```
git add native/src/main/jni/bridge/jni_derive.c \
        native/src/androidTest/java/io/digibyte/native_core/LegacySweepAmountGuardTest.kt
git commit -m "fix(recovery): native fee-sanity guard rejects grossly under-reported legacy sweeps

Bug #2 defense (c): in buildAndSignLegacySweep, refuse a multi-input sweep
whose computed fee is >= 5% of the reported input total (inputCount>1 &&
totalIn <= fee*20) — the pathological under-report the amount-blind legacy
P2PKH sighash would otherwise sign, burning the real remainder to fee. Single
-input sweeps stay covered by the existing dust check. Offline instrumented
test isolates the guard on shared real inputs (amounts are the only variable)
and documents that over-report signs locally and must be caught by the network.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Add the defense (d) over-report rejection check (live-node assertion).** This is an integration assertion, NOT part of offline CI — `testmempoolaccept` needs the real prevout in the node's UTXO set, so it is run once during the §6 mainnet proof against the self-funded prevout. Create `scripts/overreport-rejection-check.sh`:

```bash
#!/usr/bin/env bash
# overreport-rejection-check.sh — defense (d) for legacy-sweep bug #2.
#
# The on-device fee-sanity guard (jni_derive.c) catches gross UNDER-reporting,
# but the legacy P2PKH sighash is amount-blind, so an OVER-reported sweep
# (outputs > real prevout value) still signs locally. Only the network catches
# it: outputs exceeding inputs are consensus-invalid (bad-txns-in-belowout).
#
# This asserts exactly that against the live mainnet node via testmempoolaccept
# (no broadcast). Run during the §6 mainnet proof with an over-reported signed
# hex built against the REAL self-funded prevout (LegacySweepAmountGuardTest
# logs such a hex, but with a SYNTHETIC prevout the node returns "missing-
# inputs" instead — the prevout must exist and be unspent for the belowout
# assertion to fire).
#
# Usage: scripts/overreport-rejection-check.sh <signed_tx_hex>
# Exit 0 iff the node rejects the tx with a below-output (outputs>inputs)
# reason; non-zero otherwise.
set -euo pipefail

HEX="${1:?usage: overreport-rejection-check.sh <signed_tx_hex>}"
SSH="ssh -i ${HOME}/.ssh/DigitalOcean root@digiscope.me"
CLI="digibyte-cli"

RESULT="$($SSH "$CLI testmempoolaccept '[\"$HEX\"]'")"
echo "testmempoolaccept => $RESULT"

ALLOWED="$(printf '%s' "$RESULT" | grep -o '"allowed"[[:space:]]*:[[:space:]]*[a-z]*' | head -1 | grep -o '[a-z]*$')"
REASON="$(printf '%s' "$RESULT" | grep -o '"reject-reason"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1)"

if [ "$ALLOWED" = "false" ] && printf '%s' "$REASON" | grep -qi 'belowout'; then
    echo "PASS: over-reported sweep rejected by network ($REASON)"
    exit 0
fi

echo "FAIL: expected allowed=false with a below-output reject reason, got allowed=$ALLOWED $REASON" >&2
exit 1
```

Create `docs/operations/overreport-rejection-check.md`:

```markdown
# Over-report rejection check (legacy sweep bug #2, defense d)

## Why
The on-device fee-sanity guard (`jni_derive.c` `buildAndSignLegacySweep`)
catches gross *under*-reporting of input amounts. It cannot catch
*over*-reporting: the legacy P2PKH sighash is amount-blind, so a tx whose
claimed inputs exceed the real prevout value still signs locally. The network
is the authority here — outputs exceeding inputs are consensus-invalid
(`bad-txns-in-belowout`). This check proves the network rejects that case.

## When to run
Once, during the §6 mainnet proof
(`2026-07-02-legacy-sweep-mainnet-proof.md`), against the fresh self-funded
prevout — before the real, correctly-amounted sweep is broadcast.

## Procedure
1. Note the real self-funded prevout: `txid`, `vout`, `scriptPubKey`, and its
   true value `R` (from `digibyte-cli gettxout <txid> <vout>` on the node).
2. Build an OVER-reported signed hex with the app's own signer, inflating the
   reported amount well above `R` (e.g. 10x): call
   `NativeBridge.buildAndSignLegacySweep(...)` with `amounts = [10 * R]` for
   that single real input (chain/index = the derivation slot that owns it).
   The instrumented `LegacySweepAmountGuardTest` shows the exact call shape.
3. Assert the network rejects it:
   `scripts/overreport-rejection-check.sh <over_reported_hex>`
   Expected: `PASS: over-reported sweep rejected by network
   ("reject-reason": "bad-txns-in-belowout")`, exit 0.
4. Do NOT broadcast the over-reported tx. Proceed to the correctly-amounted
   sweep for the real proof.

## Follow-on (out of scope for the ship-gate)
Full on-device prevout verification — fetch each input's prevout over
SPV/BIP158 and compare its value against the backend's `amountSatoshi` before
signing — is the belt-and-suspenders defense that would make this network
check redundant. Tracked separately.
```

Make the script executable and shell-check it locally (no node access needed for the syntax gate):
```
chmod +x scripts/overreport-rejection-check.sh
bash -n scripts/overreport-rejection-check.sh && echo "syntax OK"
scripts/overreport-rejection-check.sh 2>&1 | head -1
```
Expected: `syntax OK`; the no-arg invocation prints `scripts/overreport-rejection-check.sh: ... usage: overreport-rejection-check.sh <signed_tx_hex>` (the `${1:?...}` guard) and exits non-zero. (The live-node PASS path is exercised only during the authorized §6 proof.)

- [ ] **Step 8: Commit the defense (d) script + runbook.**
```
git add scripts/overreport-rejection-check.sh docs/operations/overreport-rejection-check.md
git commit -m "test(recovery): over-report rejection check via node testmempoolaccept (bug #2 defense d)

The amount-blind legacy sighash lets an OVER-reported sweep (outputs > real
prevout) sign locally; the on-device guard cannot catch it. Add a live-node
testmempoolaccept assertion + runbook proving the network rejects it with
bad-txns-in-belowout. Run once during the mainnet proof against the real
self-funded prevout (synthetic prevouts return missing-inputs instead). Full
on-device prevout verification is noted as the out-of-scope follow-on.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task: Test Layer A — JVM classify edge tests

Add three pure-JVM guard tests to the existing `RecoveryScanClassifyTest` so the multi-profile classify path and the BIP49 detect-but-defer decision are pinned before the §3 sweep-hardening refactors touch that code. No infrastructure, no JNI, no funds — runs under `./gradlew testMainnetDebugUnitTest`. `classifyDerived` is pure Kotlin (RecoveryScanService.kt:73-110); the BIP49 branch of `sweepFromSeed` short-circuits at LegacySweepService.kt:70 **before** any `NativeBridge`/`sweepOneProfile` call, so `sweepFromSeed` with a BIP49-only list runs on the JVM with a zero seed buffer.

These tests PASS against current code (characterization guards). Their job is to fail loudly if a later §3 fix (#3 carry explicit `(chain,index)`; #8 classify de-dupe) or the #4/#70 sweep edits regress classify totals, the reachable-vs-empty distinction, or the BIP49 deferral.

**Files:**
- Modify: `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt:1-37` (add two JUnit imports; append three `@Test` methods before the class-closing brace)
- Test: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"`

**Interfaces:**
- Consumes (all existing, verified against source):
  - `RecoveryScanService(utxoSource: UtxoSource)`
  - `suspend RecoveryScanService.classifyDerived(derivedByProfile: Map<DerivationProfile, List<String>>): RecoveryScanService.State.Done`
  - `State.Done.results: List<ProfileResult>`, `.totalBalanceSat: Long`, `.nonNativeWithFunds: List<ProfileResult>` (filter `!isNative && totalSat>0`), `.allBackendUnreachable: Boolean`
  - `ProfileResult.totalSat: Long`, `.reachableBackend: Boolean`, `.utxos: List<UtxoEntry>`, `.profile: DerivationProfile`
  - `FakeUtxoSource(byAddress: Map<String, ReconcileResult>, reachable: Boolean = true)` (test double; unmapped addr on a reachable source → non-null empty `ReconcileResult`)
  - `UtxoEntry(txid: String, vout: Int, amountSatoshi: Long, address: String, blockHeight: Long, scriptPubKeyHex: String?)`, `ReconcileResult(utxos, rawTxs, chainHeight)`
  - `DerivationProfile.BUILT_INS`, `DerivationProfile.addressFormat: Int`
  - `suspend LegacySweepService.sweepFromSeed(seedBytes: ByteArray, nonNativeResults: List<ProfileResult>, destAddress: String, feePerKb: Long = 100_000L): Result`; `SweepOutcome.{txHex, txid, sweptSat, failureReason, broadcastState}`; `Result.{outcomes, allSubmitted, anyPending}`
- Produces (test-only, no production symbols):
  - `classify_emptyUtxos_noFindings()`, `classify_multipleAddresses_sumsBalance()`, `bip49Profile_isNotSweepable()`
- Imports needed: add `org.junit.Assert.assertFalse`, `org.junit.Assert.assertNull`. `LegacySweepService`, `DerivationProfile`, `RecoveryScanService`, `FakeUtxoSource` are same-package (`io.digibyte.core.recovery`) — no import. `UtxoEntry`/`ReconcileResult` imports already present.

- [ ] **Step 1: Add the two missing JUnit assertion imports.**

Edit `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt`.

old_string:
```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
```
new_string:
```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
```

- [ ] **Step 2: Add `classify_emptyUtxos_noFindings` — reachable-but-empty must not read as backend-down.**

A reachable backend that returns zero UTXOs yields no sweepable findings, `totalBalanceSat==0`, `reachableBackend==true`, and `allBackendUnreachable==false`. `FakeUtxoSource(emptyMap())` defaults `reachable=true`, so an unmapped address returns a non-null empty `ReconcileResult` (RecoveryScanService.kt:97-104 sets `reachableBackend = fetched != null`).

Edit the same file. old_string (the current tail of the class):
```kotlin
        assertTrue(done.allBackendUnreachable)
    }
}
```
new_string:
```kotlin
        assertTrue(done.allBackendUnreachable)
    }

    @Test
    fun classify_emptyUtxos_noFindings() = runBlocking {
        // Reachable backend, but the profile's address holds no UTXOs.
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val source = FakeUtxoSource(emptyMap()) // reachable defaults true; addr absent -> empty
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(addr)),
        )

        // Nothing is sweepable and the total is zero...
        assertTrue(done.nonNativeWithFunds.isEmpty())
        assertEquals(0L, done.totalBalanceSat)
        // ...but "reachable, empty" must NOT masquerade as "backend down".
        assertFalse(done.allBackendUnreachable)
        assertEquals(1, done.results.size)
        assertTrue(done.results[0].reachableBackend)
    }
}
```

Run it:
```bash
./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"
```
Expected tail:
```
BUILD SUCCESSFUL in 24s
1 actionable task: 1 executed
```
(3 tests run — 2 pre-existing + the new one — 0 failures; report at `core/build/reports/tests/testMainnetDebugUnitTest/classes/io.digibyte.core.recovery.RecoveryScanClassifyTest.html`.)

- [ ] **Step 3: Add `classify_multipleAddresses_sumsBalance` — per-profile total sums across addresses.**

Three funded addresses under one profile produce one `ProfileResult` whose `totalSat` is the exact sum (1.0 + 2.5 + 0.49 DGB = 3.99 DGB = 399_000_000 sat). `FakeUtxoSource.fetchUtxos` flat-maps the queried addresses, and `ProfileResult.totalSat` sums `utxos.amountSatoshi` (RecoveryScanService.kt:60).

Edit the same file. old_string (tail added in Step 2):
```kotlin
        assertEquals(1, done.results.size)
        assertTrue(done.results[0].reachableBackend)
    }
}
```
new_string:
```kotlin
        assertEquals(1, done.results.size)
        assertTrue(done.results[0].reachableBackend)
    }

    @Test
    fun classify_multipleAddresses_sumsBalance() = runBlocking {
        // Three funded addresses under ONE profile -> one ProfileResult whose
        // totalSat is the exact sum (1.0 + 2.5 + 0.49 DGB = 3.99 DGB).
        val a1 = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val a2 = "DPhx7bckLtP2RVpEwUJvkFktjhLfMKz9aB"
        val a3 = "DQm5RhTZ4pW8sN3vXyC1gLbA9dK6eF2uHt"
        val u1 = UtxoEntry("aa".repeat(32), 0, 100_000_000L, a1, 100L, "76a914aa88ac")
        val u2 = UtxoEntry("bb".repeat(32), 1, 250_000_000L, a2, 101L, "76a914bb88ac")
        val u3 = UtxoEntry("cc".repeat(32), 0, 49_000_000L, a3, 102L, "76a914cc88ac")
        val source = FakeUtxoSource(
            mapOf(
                a1 to ReconcileResult(listOf(u1), emptyMap(), 200L),
                a2 to ReconcileResult(listOf(u2), emptyMap(), 200L),
                a3 to ReconcileResult(listOf(u3), emptyMap(), 200L),
            ),
        )
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(a1, a2, a3)),
        )

        assertEquals(1, done.results.size)
        assertEquals(3, done.results[0].utxos.size)
        assertEquals(399_000_000L, done.results[0].totalSat)
        assertEquals(399_000_000L, done.totalBalanceSat)
        assertEquals(1, done.nonNativeWithFunds.size)
    }
}
```

Run the same targeted command; expected `BUILD SUCCESSFUL` (4 tests, 0 failures).

- [ ] **Step 4: Add `bip49Profile_isNotSweepable` — addressFormat==2 is detected then deferred, never silently skipped or swept.**

Classify must DETECT a funded BIP49 profile (it appears in `nonNativeWithFunds`), and `LegacySweepService.sweepFromSeed` must return a manual-recovery `SweepOutcome` (LegacySweepService.kt:70-72) with no built tx, no txid, nothing swept. The BIP49 branch short-circuits before `sweepOneProfile`/`NativeBridge`, so `seedBytes` is unused — a zero buffer keeps this pure JVM.

Edit the same file. old_string (tail added in Step 3):
```kotlin
        assertEquals(1, done.nonNativeWithFunds.size)
    }
}
```
new_string:
```kotlin
        assertEquals(1, done.nonNativeWithFunds.size)
    }

    @Test
    fun bip49Profile_isNotSweepable() = runBlocking {
        // A BIP49 (P2SH-P2WPKH, addressFormat==2) profile that DOES hold funds.
        val bip49 = DerivationProfile.BUILT_INS.first { it.addressFormat == 2 }
        val addr = "SXBip49TestKeyDoNotSendRealFunds123"
        val utxo = UtxoEntry("dd".repeat(32), 0, 12_345_000L, addr, 300L, "a914dd87")
        val source = FakeUtxoSource(
            mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 400L)),
        )
        val service = RecoveryScanService(source)

        // classify: the funds are DETECTED, never silently dropped.
        val done = service.classifyDerived(mapOf(bip49 to listOf(addr)))
        assertEquals(1, done.results.size)
        assertEquals(12_345_000L, done.results[0].totalSat)
        assertEquals(1, done.nonNativeWithFunds.size)
        assertEquals(2, done.nonNativeWithFunds[0].profile.addressFormat)

        // sweep: BIP49 is deferred to manual recovery -> no tx built, no txid,
        // nothing swept, and the reason names manual recovery. It is NEVER a
        // silent skip or a success. seedBytes is unused on this branch (it
        // short-circuits before any JNI), so a zero buffer is fine.
        val result = LegacySweepService().sweepFromSeed(
            seedBytes = ByteArray(64),
            nonNativeResults = done.nonNativeWithFunds,
            destAddress = "dgb1qdummydestinationplaceholderaddr",
        )
        assertEquals(1, result.outcomes.size)
        val outcome = result.outcomes[0]
        assertNull(outcome.txHex)
        assertNull(outcome.txid)
        assertEquals(0L, outcome.sweptSat)
        assertTrue(outcome.failureReason!!.contains("manual recovery", ignoreCase = true))
        assertFalse(result.allSubmitted)
    }
}
```

Run the targeted command; expected `BUILD SUCCESSFUL` (5 tests, 0 failures).

- [ ] **Step 5: Run the full JVM unit suite, then commit.**

Confirm the whole aggregate is green (the task-cluster's stated gate):
```bash
./gradlew testMainnetDebugUnitTest
```
Expected tail:
```
BUILD SUCCESSFUL in 1m 12s
```
(No compile errors; `RecoveryScanClassifyTest` shows 5 passed, 0 failed.)

Commit:
```bash
git add core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt
git commit -m "$(cat <<'EOF'
test(recovery): pin classify edge cases — empty / multi-addr sum / BIP49 deferral

Layer A of the sweep-hardening proof (spec 2026-07-02). Pure-JVM guards, no infra:
- classify_emptyUtxos_noFindings: reachable-but-empty != backend-down
- classify_multipleAddresses_sumsBalance: ProfileResult.totalSat sums across addrs
- bip49Profile_isNotSweepable: addressFormat==2 detected then deferred to manual
  recovery, never silently skipped or marked sweepable

Locks classify + the BIP49 short-circuit before the #3 (explicit chain,index) and
#8 (classify de-dupe) refactors touch that code.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: `1 file changed, N insertions(+)` on branch `phase1-modernization`.

---

### Task: Test Layer B — deterministic signed-tx known-answer vector

The one currently-missing proof: that the fund-MOVING signer (`buildAndSignLegacySweep`) produces a **consensus-valid, fully-signed** transaction. Offline, no funds, no wallet state — feed the fixed BIP39 Trezor test seed + a synthetic P2PKH UTXO that pays the seed's own legacy `m/0'/0/0` address, assert `BRTransactionIsSigned` (re-parsed in the C core), and pin the exact signed hex (RFC6979 → stable). Structure cross-checked once via the local node's `decoderawtransaction`.

**Files:**
- Create: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepSignedTxKatTest.kt`
- Modify: `native/src/main/jni/bridge/jni_derive.c` (append `isRawTransactionSigned` JNI after `buildAndSignLegacySweep`, currently ending at :569)
- Modify: `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` (test-only bridge copy — add `buildAndSignLegacySweep` + `isRawTransactionSigned` externs after :46)
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (canonical bridge — add `isRawTransactionSigned` extern after :354, keep the two copies in sync)
- Test: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepSignedTxKatTest.kt` via `:native:connectedMainnetDebugAndroidTest`

**Interfaces:**
- Consumes:
  - `NativeBridge.mnemonicToSeed(phraseBytes: ByteArray, passphrase: String?): ByteArray?`
  - `NativeBridge.buildAndSignLegacySweep(seedBytes: ByteArray, hmacKey: String, prefixPath: IntArray, txidsHex: Array<String>, vouts: IntArray, amounts: LongArray, chainIndices: IntArray, addressIndices: IntArray, scriptPubKeysHex: Array<String>, destAddress: String, feePerKb: Long): String?`
  - C core: `BRTransaction *BRTransactionParse(const uint8_t *buf, size_t bufLen)` (BRTransaction.h:115), `int BRTransactionIsSigned(const BRTransaction *)`, `void BRTransactionFree(BRTransaction *)`
  - C static in jni_derive.c: `static size_t hex_to_bytes(const char *hex, uint8_t *out, size_t outMax)` (jni_derive.c:48)
- Produces:
  - JNI `Java_io_digibyte_core_bridge_NativeBridge_isRawTransactionSigned(JNIEnv*, jobject, jstring): jboolean`
  - `external fun NativeBridge.isRawTransactionSigned(rawTxHex: String): Boolean`
  - `class io.digibyte.native_core.LegacySweepSignedTxKatTest` with pinned `EXPECTED_SIGNED_TX_HEX`

Fixed KAT inputs (all real, computed from the seed's `m/0'/0/0` legacy address `DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn`, hash160 `78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69`):
- `SYNTHETIC_TXID = 1111…1111` (32×0x11, palindromic so display==internal)
- `SYNTHETIC_VOUT = 0`, `SYNTHETIC_AMOUNT_SAT = 500_000_000` (5 DGB)
- `SYNTHETIC_SCRIPT_PUBKEY = 76a91478f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f6988ac`
- `DEST_ADDRESS = DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe` (valid DGB P2PKH, hash160 = 0x42×20)
- `FEE_PER_KB = 100_000` → estSize `10+160+34=204`, fee `204*100000/1000 = 20400`, out `499_979_600`

---

- [ ] **Step 1: Add the `isRawTransactionSigned` JNI helper to the (main-repo) bridge.**
  `buildAndSignLegacySweep` already returns null unless signed, but this gives the KAT an *authoritative* `BRTransactionIsSigned` re-parse matching the spec. `jni_derive.c` is in the main repo (submodule is `native/src/main/jni/digibytewallet-core/`), so no GIT_DIR dance. Append after the closing brace of `buildAndSignLegacySweep` at the end of the file (currently line 569). `hex_to_bytes` (jni_derive.c:48), `BRTransactionParse`/`BRTransactionIsSigned`/`BRTransactionFree` (BRTransaction.h, already `#include`d at the top), and `LOGW` (defined at jni_derive.c:~40) are all in scope.

  ```c
  /**
   * Test-support: parse a serialized transaction (hex) and report whether
   * BRTransactionIsSigned() holds. Used by the Layer-B signed-tx known-answer
   * vector (LegacySweepSignedTxKatTest) to assert the sweep signer emits a
   * fully-signed, consensus-shaped tx without a live wallet. Stateless; touches
   * no native global.
   *
   * Kotlin signature:
   *   external fun isRawTransactionSigned(rawTxHex: String): Boolean
   */
  JNIEXPORT jboolean JNICALL
  Java_io_digibyte_core_bridge_NativeBridge_isRawTransactionSigned(
      JNIEnv *env, jobject thiz, jstring rawTxHex)
  {
      (void)thiz;
      if (!rawTxHex) return JNI_FALSE;

      const char *hex = (*env)->GetStringUTFChars(env, rawTxHex, NULL);
      if (!hex) return JNI_FALSE;

      size_t hexLen = strlen(hex);
      if (hexLen == 0 || (hexLen & 1)) {
          (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
          return JNI_FALSE;
      }

      size_t bufLen = hexLen / 2;
      uint8_t *buf = (uint8_t *)malloc(bufLen);
      if (!buf) {
          (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
          return JNI_FALSE;
      }

      size_t n = hex_to_bytes(hex, buf, bufLen);
      (*env)->ReleaseStringUTFChars(env, rawTxHex, hex);
      if (n != bufLen) { free(buf); return JNI_FALSE; }

      BRTransaction *tx = BRTransactionParse(buf, bufLen);
      free(buf);
      if (!tx) {
          LOGW("isRawTransactionSigned: BRTransactionParse failed");
          return JNI_FALSE;
      }

      jboolean isSigned = BRTransactionIsSigned(tx) ? JNI_TRUE : JNI_FALSE;
      BRTransactionFree(tx);
      return isSigned;
  }
  ```

- [ ] **Step 2: Declare the new extern in the canonical bridge (parity, no drift).**
  In `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`, immediately after the `buildAndSignLegacySweep(...) : String?` declaration that ends at line 354, add:

  ```kotlin
    /**
     * Test-support: re-parse a serialized tx (hex) and return
     * BRTransactionIsSigned(). Used by the Layer-B signed-tx KAT.
     */
    external fun isRawTransactionSigned(rawTxHex: String): Boolean
  ```

- [ ] **Step 3: Add `buildAndSignLegacySweep` + `isRawTransactionSigned` to the test-only bridge copy.**
  The instrumented tests load `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt`, which currently lacks `buildAndSignLegacySweep`. Add both externs after the `derivePrivateKeyWIF` line (currently :46), before the closing `}`:

  ```kotlin
      external fun buildAndSignLegacySweep(
          seedBytes: ByteArray,
          hmacKey: String,
          prefixPath: IntArray,
          txidsHex: Array<String>,
          vouts: IntArray,
          amounts: LongArray,
          chainIndices: IntArray,
          addressIndices: IntArray,
          scriptPubKeysHex: Array<String>,
          destAddress: String,
          feePerKb: Long,
      ): String?
      external fun isRawTransactionSigned(rawTxHex: String): Boolean
  ```

- [ ] **Step 4: Write the KAT test (pin OFF — assertEquals commented, EXPECTED empty).**
  Create `native/src/androidTest/java/io/digibyte/native_core/LegacySweepSignedTxKatTest.kt`. Modeled on the sibling `LegacySweepDerivationTest.kt` (same package/runner/import style). The `assertEquals` pin is commented and `EXPECTED_SIGNED_TX_HEX = ""` for Pass 1; Step 7 fills+uncomments them.

  ```kotlin
  package io.digibyte.native_core

  import androidx.test.ext.junit.runners.AndroidJUnit4
  import io.digibyte.core.bridge.NativeBridge
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test
  import org.junit.runner.RunWith

  /**
   * Layer-B known-answer vector for the fund-MOVING sweep signer
   * (buildAndSignLegacySweep). Offline, no funds, no wallet state: feeds the
   * fixed BIP39 Trezor test seed + a SYNTHETIC P2PKH UTXO that pays the seed's
   * own legacy m/0'/0/0 address, asserts the returned tx is fully signed
   * (BRTransactionIsSigned, re-parsed in the C core), and pins the exact signed
   * hex. RFC6979 deterministic ECDSA makes the hex stable, so the pin is a
   * regression lock on the consensus shape of the swept transaction.
   *
   * PINNING WORKFLOW (two-pass — see the plan):
   *   Pass 1 — EXPECTED_SIGNED_TX_HEX == "" and the assertEquals below is
   *            commented out. Run on a booted dgb-test-api33 AVD, read logcat
   *            tag "LegacySweepKat", copy the emitted hex.
   *   Pass 2 — paste the hex into EXPECTED_SIGNED_TX_HEX, uncomment the
   *            assertEquals, re-run green. The vector is now regression-locked.
   *
   * Requires a connected emulator/device. Build-only check:
   *   ./gradlew :native:assembleMainnetDebugAndroidTest
   */
  @RunWith(AndroidJUnit4::class)
  class LegacySweepSignedTxKatTest {

      private val HARD = 0x80000000.toInt()

      // BIP39 Trezor test vector #1 — never funded on mainnet.
      private val testMnemonic =
          "abandon abandon abandon abandon abandon abandon " +
          "abandon abandon abandon abandon abandon about"

      @Test
      fun legacySweep_signsSyntheticP2pkhUtxo_deterministically() {
          // Fixed seed from the fixed mnemonic.
          val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
          assertTrue("seed must be 64 bytes", seed != null && seed.size == 64)

          // Build + sign a sweep of ONE synthetic P2PKH UTXO paying the legacy
          // m/0'/0/0 address (DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn) of this seed
          // under the "DigiByte seed" HMAC. chain=0,index=0 selects the matching
          // private key, so the ECDSA signature is self-consistent with the
          // scriptPubKey being spent.
          val signedHex = NativeBridge.buildAndSignLegacySweep(
              seedBytes = seed!!,
              hmacKey = "DigiByte seed",
              prefixPath = intArrayOf(0 or HARD),      // m/0'
              txidsHex = arrayOf(SYNTHETIC_TXID),
              vouts = intArrayOf(SYNTHETIC_VOUT),
              amounts = longArrayOf(SYNTHETIC_AMOUNT_SAT),
              chainIndices = intArrayOf(0),            // external chain
              addressIndices = intArrayOf(0),          // index 0 -> DGAf4Mmt...
              scriptPubKeysHex = arrayOf(SYNTHETIC_SCRIPT_PUBKEY),
              destAddress = DEST_ADDRESS,
              feePerKb = FEE_PER_KB,
          )

          // Zero the seed immediately.
          seed.fill(0)

          // The fund-moving path must produce a non-null, non-empty result.
          assertTrue("buildAndSignLegacySweep must return a signed hex", signedHex != null)
          assertTrue("signed hex must be non-empty", signedHex!!.isNotEmpty())

          // Authoritative signed-ness: re-parse via the C core BRTransactionIsSigned.
          assertTrue(
              "re-parsed sweep tx must satisfy BRTransactionIsSigned",
              NativeBridge.isRawTransactionSigned(signedHex),
          )

          // Emit the hex so it can be pinned on the first run.
          android.util.Log.i("LegacySweepKat", "signed sweep tx hex = $signedHex")

          // Deterministic known-answer pin (RFC6979 => stable). Enable in Pass 2.
          // assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
      }

      companion object {
          // Synthetic prevout paying the seed's legacy m/0'/0/0 address.
          // Palindromic txid so decoderawtransaction display == this literal.
          const val SYNTHETIC_TXID =
              "1111111111111111111111111111111111111111111111111111111111111111"
          const val SYNTHETIC_VOUT = 0
          const val SYNTHETIC_AMOUNT_SAT = 500_000_000L    // 5 DGB
          // P2PKH scriptPubKey for DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn
          // (hash160 78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69).
          const val SYNTHETIC_SCRIPT_PUBKEY =
              "76a91478f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f6988ac"
          // Fixed valid DGB P2PKH sweep destination (hash160 = 0x42 * 20).
          const val DEST_ADDRESS = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"
          const val FEE_PER_KB = 100_000L

          // Pass 1: leave "" and keep the assertEquals above commented.
          // Pass 2: paste the logcat hex here and uncomment. A later change to
          // this value means the signer output changed — treat as a red flag.
          const val EXPECTED_SIGNED_TX_HEX = ""
      }
  }
  ```

- [ ] **Step 5: Rebuild native + build the androidTest APK (compile gate — no device yet).**
  Confirms the new JNI symbol links and both bridge copies compile. Run:

  ```bash
  cd /home/polloloco/digibytewallet-android
  ./gradlew :native:assembleMainnetDebug :native:assembleMainnetDebugAndroidTest
  ```

  Expected tail:

  ```
  BUILD SUCCESSFUL in Xs
  ```

  (If it fails with `undefined reference` / `UnsatisfiedLinkError` for `isRawTransactionSigned`, the JNI symbol name in Step 1 does not match the `io.digibyte.core.bridge.NativeBridge` package — fix the `Java_io_digibyte_core_bridge_NativeBridge_` prefix.)

- [ ] **Step 6: Boot the dgb-test-api33 AVD and confirm it is online.**

  ```bash
  cd /home/polloloco/digibytewallet-android
  $ANDROID_HOME/emulator/emulator -avd dgb-test-api33 -no-snapshot -no-boot-anim -netdelay none -netspeed full &
  adb wait-for-device
  adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
  adb devices
  ```

  Expected:

  ```
  List of devices attached
  emulator-5554	device
  ```

- [ ] **Step 7: PASS 1 — run the KAT with the pin OFF, capture the emitted signed hex.**
  Clear logcat, run the single test, then read the tag. This is the legitimate KAT-pinning capture — the exact hex only exists once the signer runs.

  ```bash
  cd /home/polloloco/digibytewallet-android
  adb logcat -c
  ./gradlew :native:connectedMainnetDebugAndroidTest --tests '*LegacySweepSignedTxKat*'
  adb logcat -d -s LegacySweepKat:I
  ```

  Expected (test is green because the pin is commented; the hex is emitted):

  ```
  > Task :native:connectedMainnetDebugAndroidTest
  Starting 1 tests on dgb-test-api33(AVD) - 13
  BUILD SUCCESSFUL in Xm Ys

  ... I LegacySweepKat: signed sweep tx hex = 0100000001111111111111111111111111111111111111111111111111111111111111111100000000<scriptSig ~6b bytes>ffffffff0110dd cd1d000000001976a914424242...4288ac00000000
  ```

  Copy the full hex string after `signed sweep tx hex = `.
  (If Gradle reports `Unknown command-line option '--tests'` for the connected task on this AGP, use the instrumentation filter instead: `./gradlew :native:connectedMainnetDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.digibyte.native_core.LegacySweepSignedTxKatTest`.)

- [ ] **Step 8: Pin the hex and re-enable the assertion (Pass 2 prep).**
  In `LegacySweepSignedTxKatTest.kt`: set `EXPECTED_SIGNED_TX_HEX` to the captured value and uncomment the `assertEquals`.

  ```kotlin
          const val EXPECTED_SIGNED_TX_HEX =
              "0100000001111111111111111111111111111111111111111111111111111111111111111100000000...ac00000000"
  ```
  ```kotlin
          // Deterministic known-answer pin (RFC6979 => stable). Enable in Pass 2.
          assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
  ```

- [ ] **Step 9: PASS 2 — re-run; the vector is now regression-locked and green.**

  ```bash
  cd /home/polloloco/digibytewallet-android
  ./gradlew :native:connectedMainnetDebugAndroidTest --tests '*LegacySweepSignedTxKat*'
  ```

  Expected:

  ```
  > Task :native:connectedMainnetDebugAndroidTest
  Starting 1 tests on dgb-test-api33(AVD) - 13
  BUILD SUCCESSFUL in Xm Ys
  ```

  HTML report at `native/build/reports/androidTests/connected/debug/index.html` shows `LegacySweepSignedTxKatTest > legacySweep_signsSyntheticP2pkhUtxo_deterministically` — 1 test, 0 failures.

- [ ] **Step 10: One-time structural cross-check via the local node (`decoderawtransaction`).**
  Confirms the pinned bytes are a real DGB tx — not just self-consistently "signed". Uses the VPS node from CLAUDE.md (`digibyte-cli`, port 12024). Substitute the pinned hex:

  ```bash
  ssh -i ~/.ssh/DigitalOcean root@digiscope.me \
    "digibyte-cli decoderawtransaction 0100000001111111...ac00000000"
  ```

  Eyeball the JSON:
  - `vin` length 1, `vin[0].txid == "1111111111111111111111111111111111111111111111111111111111111111"`, `vin[0].vout == 0`, `vin[0].scriptSig.hex` non-empty (signature + pubkey present).
  - `vout` length 1, `vout[0].value == 4.99979600`, `vout[0].scriptPubKey.type == "pubkeyhash"`, `vout[0].scriptPubKey.address(es) == ["DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"]`.

  This is a manual read-only sanity check (node does not broadcast); no on-chain action.

- [ ] **Step 11: Commit.**

  ```bash
  cd /home/polloloco/digibytewallet-android
  git add native/src/androidTest/java/io/digibyte/native_core/LegacySweepSignedTxKatTest.kt \
          native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt \
          core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt \
          native/src/main/jni/bridge/jni_derive.c
  git commit -m "$(cat <<'EOF'
  test(recovery): Layer-B signed-tx KAT + native isRawTransactionSigned helper

  Pin a deterministic known-answer vector for buildAndSignLegacySweep: the
  fixed Trezor test seed + a synthetic P2PKH UTXO paying its own legacy
  m/0'/0/0 address, asserted signed via a new C-core re-parse helper
  (BRTransactionIsSigned) and locked to the exact signed hex. RFC6979 makes
  the output stable; structure cross-checked once via decoderawtransaction.
  This is the previously-missing proof that the fund-MOVING path produces a
  consensus-valid transaction.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

  `jni_derive.c` lives in the main repo (not the `digibytewallet-core` submodule), so the standard `git add`/`git commit` applies — no GIT_DIR/GIT_WORK_TREE override needed.

---

### Task: Test Layer D — self-funded mainnet sweep proof (ship-gate)

The one-time on-chain proof that the fund-*moving* path (build → sign → broadcast → confirm → reflect via BIP158) works end-to-end on real mainnet DGB. This is the ship-gate from `project_legacy_sweep_ship_gate`: until one real sweep confirms, the branch stays unpushed and the feature stays hidden. The task both CREATES the runbook doc and EXECUTES it. It moves real funds, so two steps are hard-gated on explicit user go-ahead and every on-device gesture is user-driven (we observe read-only).

**Files:**
- Create: `docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md`
- Modify: `/home/polloloco/.claude/projects/-home-polloloco-digibytewallet-android/memory/project_legacy_sweep_ship_gate.md` (frontmatter description line 3 + a CLEARED body note)
- Test (regression gate, no new test files): `./gradlew :core:testMainnetDebugUnitTest`, `./gradlew :app:assembleMainnetDebug`
- Ad-hoc utility (untracked, already on disk, delete-after-use): `native/src/androidTest/java/io/digibyte/native_core/LegacyAddressGenTest.kt`

**Interfaces:**
- Consumes:
  - `NativeBridge.generateMnemonic(entropyBits: Int): String?` — `128` → fresh random 12-word BIP39 mnemonic (NOT a public test seed).
  - `NativeBridge.deriveAddresses(seedBytes, hmacKey="DigiByte seed", prefixPath=intArrayOf(0 or 0x80000000.toInt()), gapExternal=1, gapInternal=0, addressFormat=0): Array<String>?` → legacy `m/0'/0/0` `D…` P2PKH address (the exact derivation the app's "Legacy DigiByte mobile wallet" classify profile scans; external index 0 sits inside its gap-200 set).
  - `LegacySweepService.sweepFromSeed(seedBytes, nonNativeResults, destAddress, feePerKb=100_000L): Result` — routes through the six hardened fixes and `Broadcaster.broadcast`; exercised via the app UI (`RecoverFundsScreen`).
  - App entry points: onboarding restore → `RecoveryScanScreen` → `RecoverFundsScreen`; or Settings → "Recover funds from another wallet" → `RecoverFundsScreen`.
  - Local mainnet node via `digibyte-cli` (reads `~/.digibyte/digibyte.conf`: rpcport `14022`, wallet `JohnnyTest` ~887 DGB spendable): `sendtoaddress`, `gettransaction`, `gettxout`.
- Produces:
  - Completed runbook with real values: `FUND_TXID`, funding `vout`, `SWEEP_TXID`, dest `dgb1…` address, swept value, cfilter-match block height, screenshot paths.
  - `project_legacy_sweep_ship_gate` memory marked **SHIP-GATE CLEARED** with the real txids.

---

- [ ] **Step 1: Write the runbook doc**

Create `docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md` with exactly this content (the "Results" table has intentional fill-during-execution blanks — a data-capture form, not a logic gap):

```markdown
# Legacy Sweep — Mainnet Proof Runbook (one-time, ship-gate)

**Date:** 2026-07-02
**Branch:** `phase1-modernization`
**Status:** EXECUTE ONLY WITH EXPLICIT USER GO-AHEAD. Moves real mainnet DGB.
**Precondition:** the six fund-path fixes (#1 UAF `NULL/NULL` publish, #2 prevout
amount cross-check, #3 explicit `(chain,index)` carry, #4 continue-on-null
scriptPubKey, #5 `OutgoingTxStore`/persister durability, #6 pending-not-confirmed)
have LANDED, and Layer A + Layer B tests are green.

## Actors / facts
- Local mainnet node: `digibyted` v8.26.2, RPC 14022, wallet `JohnnyTest` ~887 DGB.
  `digibyte-cli` auto-reads `~/.digibyte/digibyte.conf`. `gettxout` reads the
  chainstate (no `txindex` needed) — authoritative for spent/received.
- Fresh THROWAWAY seed: generated per-run by `LegacyAddressGenTest` (random
  `generateMnemonic(128)`). NEVER a public/BIP39-vector seed — a public seed is
  sweepable by anyone the instant it is funded.
- Note 8: serial `ce061716640b191c017e` (SM-N950U, Android 9). User drives all
  gestures; we observe read-only via `logcat`/`screencap`.
- Proof build: locally-built DEBUG APK (app `Log.*` + native `bread` peer_log
  present — required for the cfilter/reconcile greps).

## Two hard gates
- **GATE 1** — before `sendtoaddress` (spends 5 real DGB from JohnnyTest).
- **GATE 2** — before the in-app Sweep/broadcast (moves the funded UTXO).

## Procedure (see the plan task for the exact commands)
1. Build+install the debug proof APK on the Note 8.
2. Generate the fresh seed on the `dgb-test-api33` AVD; record MNEMONIC +
   LEGACY_ADDR (`D…`) from logcat tag `LegacyFund`.
3. GATE 1 → fund `LEGACY_ADDR` with 5 DGB from JohnnyTest; wait 1 conf; assert
   the funding UTXO is present (`gettxout` non-null).
4. GATE 2 → user restores the seed on the Note 8, runs Recover Funds, taps
   Sweep. Capture `SWEEP_TXID` + Findings/Done screenshots.
5. Assert on-chain: (a) source UTXO SPENT, (b) dest `dgb1…` received swept−fee,
   (c) reflected via BIP158 (`bread: cfilter: MATCH …`) with NO reconcile call.
6. Record results below; run the regression gate; clear the ship-gate memory.

## Results (fill during execution)
| Field | Value |
|---|---|
| Throwaway MNEMONIC (secure, destroy after) | ______ |
| LEGACY_ADDR (m/0'/0/0, D…) | ______ |
| FUND_TXID | ______ |
| Funding vout (pays LEGACY_ADDR) | ______ |
| Funding amount (DGB) | 5 |
| `gettxout FUND_TXID vout` before sweep (non-null?) | ______ |
| SWEEP_TXID | ______ |
| Dest address (dgb1…) | ______ |
| Dest value (DGB, swept−fee) | ______ |
| `gettxout FUND_TXID vout` after sweep (null = spent?) | ______ |
| cfilter MATCH block height (logcat `bread`) | ______ |
| Reconcile network call fired? (must be NO) | ______ |
| Screenshots | findings.png / done.png / balance.png |

## Rollback
Fresh throwaway seed → worst case ~5 DGB at risk. If the sweep fails, the funds
sit unspent on `LEGACY_ADDR` and are re-sweepable (the sweep is re-derivable).
```

- [ ] **Step 2: Build the debug proof APK from the branch (contains the six fixes)**

```bash
cd /home/polloloco/digibytewallet-android
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
ls -l app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```
Expected: `BUILD SUCCESSFUL`; the APK file exists (native rebuilt first so the fixed C signer/publish path is in `libcore-lib.so`).

- [ ] **Step 3: Generate the fresh throwaway seed + legacy address on the AVD**

Boot the offline emulator (no funds ever touch it) and run the untracked utility, which logs a fresh random mnemonic + its legacy `D…` address to tag `LegacyFund`:

```bash
cd /home/polloloco/digibytewallet-android
# start the AVD if not already up:
emulator -avd dgb-test-api33 -no-window -no-audio -no-snapshot >/dev/null 2>&1 &
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 logcat -c
./gradlew :native:connectedMainnetDebugAndroidTest \
  --tests "io.digibyte.native_core.LegacyAddressGenTest"
adb -s emulator-5554 logcat -d -s LegacyFund:I
```
Expected logcat (values differ every run — they are fresh):
```
LegacyFund: MNEMONIC=<12 random words>
LegacyFund: LEGACY_ADDR(m/0'/0/0 DigiByte-seed)=D...
LegacyFund: NATIVE_ADDR(m/84'/20'/0' bech32)=dgb1q...
```
Record `MNEMONIC` (SECURE — it will briefly hold 5 real DGB; destroy after) and `LEGACY_ADDR` into the runbook Results table. Export for the shell:
```bash
LEGACY_ADDR="D...."   # paste the exact D-address from logcat
```

- [ ] **Step 4: GATE 1 — get explicit user go-ahead, then fund the legacy address**

STOP. Ask the user to confirm go-ahead before spending 5 real DGB. Only after an explicit yes:

```bash
digibyte-cli -rpcwallet=JohnnyTest getbalance          # expect ~887 (enough)
FUND_TXID=$(digibyte-cli -rpcwallet=JohnnyTest sendtoaddress "$LEGACY_ADDR" 5)
echo "FUND_TXID=$FUND_TXID"
```
Wait ~1 mainnet block (~15-30 s), then confirm 1 confirmation and find the vout that pays the legacy address (the funding tx is a JohnnyTest wallet tx, so `gettransaction … true` decodes it without `txindex`):

```bash
digibyte-cli -rpcwallet=JohnnyTest gettransaction "$FUND_TXID" | grep -m1 '"confirmations"'
FUND_VOUT=$(digibyte-cli -rpcwallet=JohnnyTest gettransaction "$FUND_TXID" true \
  | jq -r --arg a "$LEGACY_ADDR" '.decoded.vout[] | select(.scriptPubKey.address==$a) | .n')
echo "FUND_VOUT=$FUND_VOUT"
# BASELINE assertion — the funding UTXO must be PRESENT (unspent) before the sweep:
digibyte-cli gettxout "$FUND_TXID" "$FUND_VOUT"
```
Expected: `"confirmations": 1` (or more); `FUND_VOUT` is `0` or `1`; the final `gettxout` prints a JSON object whose `scriptPubKey.address` equals `$LEGACY_ADDR` and `value` is `5.00000000` (non-null = unspent). Record `FUND_TXID` + `FUND_VOUT` in Results. (If `jq` is unavailable, read `.decoded.vout[]` from `gettransaction "$FUND_TXID" true` by eye and use the `n` of the output whose address is `$LEGACY_ADDR`.)

- [ ] **Step 5: GATE 2 — get explicit user go-ahead, prep the Note 8, start read-only capture**

STOP. Confirm with the user: (a) go-ahead to run the in-app sweep, and (b) their REAL wallet seed is backed up, because installing the debug proof build replaces the current wallet. Only after explicit yes:

```bash
S=ce061716640b191c017e
adb -s "$S" uninstall io.digibyte                       # debug signer != release; clean install (wipes wallet)
adb -s "$S" install app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
adb -s "$S" logcat -c
# read-only full capture (do NOT drive the device):
adb -s "$S" logcat -v time > /tmp/claude-1000/-home-polloloco-digibytewallet-android/960b7017-4dd4-4ce5-bc07-e9231485468f/scratchpad/sweep-proof-logcat.txt &
LOGPID=$!
echo "capturing to sweep-proof-logcat.txt (pid $LOGPID)"
```
Expected: `Success` on install; the background `logcat` pid is printed. We now only OBSERVE.

- [ ] **Step 6: User-driven in-app sweep (you observe read-only)**

Ask the user to perform, on the Note 8, unassisted:
1. Open DigiByte wallet → "Restore existing wallet" → enter the throwaway 12-word `MNEMONIC` → set a PIN.
2. Onboarding runs `RecoveryScanScreen`, auto-classifies, and routes to `RecoverFundsScreen`; Findings shows **Legacy DigiByte mobile wallet ≈ 5 DGB**. (Equivalent path: Settings → "Recover funds from another wallet".)
3. Destination = "Into this wallet" (native `dgb1…`).
4. **This tap is the GATE-2 broadcast** — the user taps **Sweep** only when they say go.

While they act, capture evidence read-only (never `input`/`am start`/`force-stop`):
```bash
S=ce061716640b191c017e
DIR=/tmp/claude-1000/-home-polloloco-digibytewallet-android/960b7017-4dd4-4ce5-bc07-e9231485468f/scratchpad
adb -s "$S" exec-out screencap -p > "$DIR/findings.png"   # after Findings renders
adb -s "$S" exec-out screencap -p > "$DIR/done.png"       # after the Done screen shows the txid
```
Read `SWEEP_TXID` from the Done screen (screenshot) and/or from the capture:
```bash
grep -iE "publish|broadcast|sweep|txid" "$DIR/sweep-proof-logcat.txt" | tail -20
SWEEP_TXID="...."   # the 64-hex sweep txid
```
Record `SWEEP_TXID` + screenshot paths in Results.

- [ ] **Step 7: On-chain assertions — source SPENT + dest RECEIVED**

Both are chainstate (`gettxout`) queries — no `txindex` needed. Wait ~1 block for the sweep to confirm, then:

```bash
# (a) source UTXO must now be SPENT (was non-null in Step 4):
digibyte-cli gettxout "$FUND_TXID" "$FUND_VOUT"
# (b) dest output at vout 0 must exist with the swept value, address = app's dgb1 dest:
digibyte-cli gettxout "$SWEEP_TXID" 0
```
Expected:
- (a) prints nothing / `null` → the funding UTXO is spent by the sweep. ✅
- (b) prints a JSON object with `scriptPubKey.address` = a `dgb1…` (the app's native receive address), `value` ≈ `4.99…` (5 DGB − sweep fee), and `confirmations` ≥ 1. ✅

Record dest address + value. Cross-check the dest belongs to the restored wallet: on the Note 8 the balance now shows ≈ the swept amount and the Activity list shows the incoming tx (capture `balance.png`). Secondary confirmation (optional): the digiscope explorer for `SWEEP_TXID`.

- [ ] **Step 8: BIP158-reflected + NO-reconcile assertion (from the captured log)**

```bash
S=ce061716640b191c017e
DIR=/tmp/claude-1000/-home-polloloco-digibytewallet-android/960b7017-4dd4-4ce5-bc07-e9231485468f/scratchpad
kill "$LOGPID" 2>/dev/null   # stop the capture
# BIP158 match on the sweep's block (native tag "bread", BRPeerManager.c:2200):
grep -nE "cfilter: MATCH on block .* requesting full block" "$DIR/sweep-proof-logcat.txt"
# NO real reconcile: the network call (DgbNodeClient) and the import must be ABSENT.
# The benign fresh-install PostUpgradeReconcile line is expected and is excluded:
grep -niE "reconcile" "$DIR/sweep-proof-logcat.txt" \
  | grep -viE "PostUpgradeReconcile: (fresh install|no reconcile needed)"
```
Expected:
- ≥ 1 `bread: … cfilter: MATCH on block <hash> @ height <h>, requesting full block` line → the re-homed funds were discovered by the wallet's own compact-filter sync. Record the height.
- The second grep prints NOTHING → no `DgbNodeClient: reconcile <url>` network call and no `PostUpgradeReconcile: … reconcile done: imported=` line. The sweep reflected via sovereign BIP158/SPV, not the reconcile backend. ✅

- [ ] **Step 9: Fill the runbook Results + regression gate**

Complete every row of the Results table in `docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md` with the recorded values (Edit the file). Then run the release regression gate:

```bash
cd /home/polloloco/digibytewallet-android
./gradlew :core:testMainnetDebugUnitTest
./gradlew :app:assembleMainnetDebug
```
Expected: `BUILD SUCCESSFUL` for both; all `:core` unit tests green (Layer A `RecoveryScanClassifyTest` included); the release APK builds. Also confirm the on-device run left no crash: `adb -s ce061716640b191c017e logcat -d -b crash` prints no `SIGSEGV`/`FATAL EXCEPTION` for `io.digibyte`.

- [ ] **Step 10: Commit the completed runbook**

```bash
cd /home/polloloco/digibytewallet-android
git add docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md
git commit -m "$(cat <<'EOF'
docs(recovery): mainnet sweep proof runbook — ship-gate CLEARED

One self-funded mainnet sweep confirmed end-to-end: source legacy UTXO spent,
funds landed on the wallet's native dgb1 address, balance reflected via BIP158
compact-filter match with no reconcile call. Real txids recorded in the runbook
(FUND_TXID / SWEEP_TXID).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: one commit on `phase1-modernization` adding the runbook. (Do NOT push here — pushing the branch is a separate, explicitly-authorized release step.)

- [ ] **Step 11: Mark the ship-gate CLEARED in memory**

Edit `/home/polloloco/.claude/projects/-home-polloloco-digibytewallet-android/memory/project_legacy_sweep_ship_gate.md`. Update the frontmatter description (line 3) — replace:

```
description: Legacy-funds sweep (Recover-Funds) is code-complete (plan Tasks 1-7) but its fund-moving path has ZERO end-to-end proof (Tasks 8-10 missing); GATED — do not push the 15 unpushed commits / ship until ≥1 on-chain build→sign→broadcast→confirm
```
with:
```
description: Legacy-funds sweep (Recover-Funds) SHIP-GATE CLEARED 2026-07-02 — one self-funded mainnet sweep confirmed end-to-end (source UTXO spent, funds on native dgb1, reflected via BIP158 no-reconcile); the six fund-path fixes (#1-#6) landed and Layer A/B/D proofs are green. Safe to push/ship per release policy
```
and append a body note recording the outcome (fill the real txids from the run):
```
**SHIP-GATE CLEARED (2026-07-02):** Layer D mainnet proof executed per
`docs/superpowers/plans/2026-07-02-legacy-sweep-mainnet-proof.md`. Fresh throwaway
seed funded with 5 DGB from JohnnyTest → legacy m/0' D-address (FUND_TXID
<record>); in-app Recover-Funds sweep on the Note 8 (SWEEP_TXID <record>) spent
that UTXO and delivered ~4.99 DGB to the wallet's own dgb1 address; balance
reflected via a `bread: cfilter: MATCH` at height <record> with NO DgbNodeClient
reconcile call. Regression green (`:core:testMainnetDebugUnitTest` +
`:app:assembleMainnetDebug`). The build→sign→broadcast→confirm path that moves
real DGB is now proven; the earlier "ZERO end-to-end proof" blocker is resolved.
```
Expected: the memory file reflects CLEARED with the real txids; the branch is now unblocked for the (separately authorized) push/release.

---

