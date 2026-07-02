# BIP158 Filter-Chain Re-Anchor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let legacy deep-deficit wallets recover BIP158 by re-anchoring the compact-filter chain at the block floor when cfTip persisted far below it, instead of falling back to bloom permanently.

**Architecture:** A new C-core function discards the stuck filter chain and resets the auto-fetch cursor to the lowest contiguous downloaded block (the "floor"); the existing lazy-create path in `_peerRelayedCFHeaders` then TOFU-anchors a fresh chain there and syncs forward. The Kotlin watchdog owns the trigger — in its "headers caught up but cfTip stuck" branch it calls the re-anchor once per session, gated on `hasReachedSynced` (proof the skipped gap was already bloom-scanned).

**Tech Stack:** C (BRPeerManager, native core submodule), JNI bridge, Kotlin (Android service). Spec: `docs/superpowers/specs/2026-06-07-bip158-filter-chain-reanchor-design.md`.

**Submodule note:** `BRPeerManager.c/.h` and `BRCompactFilterChainTests.c` live in the `digibytewallet-core` submodule. Commit them with the submodule git pattern (push to `johnnylaw` fork), then bump the pin in the main repo. See Task 5.

---

## File Structure

- `native/src/main/jni/digibytewallet-core/BRPeerManager.c` — add `_BRPeerManagerBlockFloor` (static helper) + `BRPeerManagerReanchorCompactFilterChainAtFloor` (public).
- `native/src/main/jni/digibytewallet-core/BRPeerManager.h` — declare the public function.
- `native/src/main/jni/digibytewallet-core/BRCompactFilterChainTests.c` — add `test_reanchor_restart_at_higher_start` (validates the discard + recreate-at-higher-start data-structure pattern the re-anchor relies on).
- `native/src/main/jni/bridge/jni_peer.c` — add `Java_..._reanchorCompactFilterChainAtFloor` JNI wrapper.
- `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` — declare `external fun reanchorCompactFilterChainAtFloor(): Boolean`.
- `app/src/main/java/io/digibyte/service/SyncService.kt` — wire the trigger into the watchdog's caught-up-but-stuck branch (+ once-per-session flag + stale-pref clear).

**Testing reality:** This codebase unit-tests the filter-chain *data structure* (`BRCompactFilterChainTests`) but has **no BRPeerManager/Service harness**. So the chain-level re-anchor assumption is unit-tested (Task 1), and the manager function + JNI + watchdog wiring are verified on the live Note8 legacy wallet (Task 5), which is a real repro. This split is intentional, not a shortcut.

---

### Task 1: C core — block-floor helper, re-anchor function, and chain-level unit test

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (add helper + function after `BRPeerManagerCFChainTipHeight`, which ends ~line 2693)
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.h` (declare after line 208, `uint32_t BRPeerManagerCFChainTipHeight(...)`)
- Test: `native/src/main/jni/digibytewallet-core/BRCompactFilterChainTests.c`

- [ ] **Step 1: Write the failing chain-level test**

Add this function in `BRCompactFilterChainTests.c` immediately before `int BRCompactFilterChainTests(void)` (line 209):

```c
// Re-anchor pattern: an old chain anchored low is discarded, and a new chain is
// created at a higher floor with a fresh peer-provided anchor. The new chain must
// append from its own anchor, independent of the discarded one. This is exactly
// what BRPeerManagerReanchorCompactFilterChainAtFloor relies on (it frees the
// chain; _peerRelayedCFHeaders lazily recreates it at the floor).
static int test_reanchor_restart_at_higher_start(void)
{
    UInt256 oldAnchor = u256_fill(0xa1);
    BRCompactFilterChain *old = BRCompactFilterChainNew(0, 50, oldAnchor);
    UInt256 oh = u256_fill(0x11);
    BRCompactFilterChainAppend(old, oldAnchor, &oh, 1);
    BRCompactFilterChainFree(old);   // discard (simulates the re-anchor)

    UInt256 floorAnchor = u256_fill(0xb2);
    BRCompactFilterChain *fresh = BRCompactFilterChainNew(0, 1000, floorAnchor);
    EXPECT(BRCompactFilterChainNextHeight(fresh) == 1000, "fresh chain NextHeight == floor start");
    EXPECT(BRCompactFilterChainCount(fresh) == 0, "fresh chain empty");

    UInt256 f0 = u256_fill(0x30);
    UInt256 f1 = u256_fill(0x40);
    UInt256 batch[] = { f0, f1 };
    int ok = BRCompactFilterChainAppend(fresh, floorAnchor, batch, 2);
    EXPECT(ok == 1, "append against fresh floor anchor should succeed");
    EXPECT(BRCompactFilterChainNextHeight(fresh) == 1002, "NextHeight after floor append");

    UInt256 hdr1000 = BRGCSFilterHeader(f0, floorAnchor);
    EXPECT(UInt256Eq(BRCompactFilterChainHeader(fresh, 1000), hdr1000), "floor header[1000] wrong");

    BRCompactFilterChainFree(fresh);
    return 0;
}
```

Register it inside `BRCompactFilterChainTests(void)` by adding this line after `r |= test_deserialize_rejects_garbage();`:

```c
    r |= test_reanchor_restart_at_higher_start();
```

- [ ] **Step 2: Run the test to verify it passes against existing chain code**

The chain-level behaviour already exists, so this test should pass once it compiles — it pins the contract the re-anchor depends on. Build and run the chain test suite. Plain `gcc` chokes on `const static int` array dims in `crypto/odocrypt.h` (valid C++, not C), so apply a temporary enum patch, build, run, then revert:

```bash
cd native/src/main/jni/digibytewallet-core
cp crypto/odocrypt.h /tmp/odocrypt.h.bak
sed -i -E 's/const static int ([A-Za-z_]+) = (.*);/enum { \1 = \2 };/' crypto/odocrypt.h
cat > /tmp/cf_runner.c <<'EOF'
#include <stdint.h>
#include <stdio.h>
int BRCompactFilterChainTests(void);
int main(void){ int r = BRCompactFilterChainTests(); return r ? 1 : 0; }
EOF
gcc -include stdint.h -I secp256k1/ -I. -o /tmp/cftest \
  $(ls *.c | grep -v '^test.c') crypto/*.c crypto/sha3/*.c /tmp/cf_runner.c -lm
/tmp/cftest
cp /tmp/odocrypt.h.bak crypto/odocrypt.h   # ALWAYS revert
rm -f /tmp/odocrypt.h.bak /tmp/cf_runner.c /tmp/cftest
```

Expected: `BRCompactFilterChainTests: all passing` (includes the new test). Confirm `crypto/odocrypt.h` is reverted: `git -C . diff --stat crypto/odocrypt.h` (via the submodule git pattern) shows nothing.

- [ ] **Step 3: Add the block-floor helper**

In `BRPeerManager.c`, add this static helper immediately before `int BRPeerManagerReanchorCompactFilterChainAtFloor` (which you add in Step 4) — place both right after the `BRPeerManagerCFChainTipHeight` function (ends ~line 2693, just before `void BRPeerManagerSetCompactFilterChain`):

```c
// Lowest contiguous block height reachable by walking prevBlock links from
// lastBlock through the block set — i.e. the deepest height the cfheaders
// stop-hash lookup can still resolve. Returns 0 if there is no lastBlock.
// Caller must hold manager->lock.
static uint32_t _BRPeerManagerBlockFloor(BRPeerManager *manager)
{
    BRMerkleBlock *b = manager->lastBlock;
    if (!b) return 0;
    for (;;) {
        BRMerkleBlock *prev = BRSetGet(manager->blocks, &b->prevBlock);
        if (!prev) break;
        b = prev;
    }
    return b->height;
}
```

- [ ] **Step 4: Add the re-anchor function**

Immediately after the helper from Step 3, add:

```c
// Re-anchor the compact-filter chain at the current block floor when cfTip has
// fallen below the lowest contiguous downloaded block — a legacy deficit the
// header-retention fix cannot bridge, because the gap blocks were never
// re-downloaded this session. Discards the stuck chain so the next cfheaders
// response TOFU-creates a fresh one at the floor (the existing lazy-create path
// in _peerRelayedCFHeaders). Returns 1 if it re-anchored, 0 otherwise.
//
// The historical gap [old cfTip, floor] is intentionally skipped: those blocks
// were already scanned by bloom in prior sessions. The caller (SyncService
// watchdog) gates this on has_synced, which is that guarantee.
int BRPeerManagerReanchorCompactFilterChainAtFloor(BRPeerManager *manager)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);

    if (manager->syncMode == BR_SYNC_MODE_BLOOM_ONLY || !manager->compactFilterChain) {
        pthread_mutex_unlock(&manager->lock);
        return 0;
    }

    uint32_t next  = BRCompactFilterChainNextHeight(manager->compactFilterChain);
    uint32_t floor = _BRPeerManagerBlockFloor(manager);

    // Only re-anchor when the next cfheaders batch starts BELOW the floor — that
    // is the genuinely unbridgeable case. If next >= floor the driver can still
    // walk back to its stop hash; nothing to do.
    if (floor == 0 || next >= floor) {
        pthread_mutex_unlock(&manager->lock);
        return 0;
    }

    peer_log(&BR_PEER_NONE, "cfheaders: re-anchoring filter chain from stuck tip %u to block floor %u",
             next > 0 ? next - 1 : 0, floor);

    BRCompactFilterChainFree(manager->compactFilterChain);
    manager->compactFilterChain = NULL;
    manager->autoFetchCFiltersStart    = floor;
    manager->autoFetchCFiltersThrough  = floor > 0 ? floor - 1 : 0;
    manager->cfHeadersRequestedThrough = 0;   // clear the serialization in-flight guard

    // Kick recovery immediately if a filter peer is connected; otherwise the
    // next block-extend kick handles it once filter-first connects one.
    BRPeer *fp = _BRPeerManagerAnyFilterCapablePeer(manager);
    if (fp) _BRPeerManagerRequestNextCFHeaders(manager, fp);

    pthread_mutex_unlock(&manager->lock);
    return 1;
}
```

Note: `_BRPeerManagerAnyFilterCapablePeer` and `_BRPeerManagerRequestNextCFHeaders` are static helpers forward-declared near line 860 and do **not** take the lock themselves (they're always called under it) — calling them here while holding the lock is correct and matches existing call sites (e.g. line ~1472).

- [ ] **Step 5: Declare the public function in the header**

In `BRPeerManager.h`, add this line immediately after line 208 (`uint32_t BRPeerManagerCFChainTipHeight(BRPeerManager *manager);`):

```c
// Re-anchor the compact-filter chain at the block floor when cfTip is stuck
// below the downloaded chain (legacy deficit). Returns 1 if re-anchored.
int BRPeerManagerReanchorCompactFilterChainAtFloor(BRPeerManager *manager);
```

- [ ] **Step 6: Verify the core still compiles (chain test build doubles as a compile check)**

Re-run the build command from Step 2 (temp odocrypt patch → `gcc ... -lm` → revert). The `gcc` link now also compiles the new `BRPeerManager.c` code. Expected: builds clean, `BRCompactFilterChainTests: all passing`. Revert `crypto/odocrypt.h`.

- [ ] **Step 7: Commit (submodule)**

```bash
cd /home/polloloco/digibytewallet-android
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRPeerManager.c BRPeerManager.h BRCompactFilterChainTests.c
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(bip158): re-anchor filter chain at block floor when cfTip stuck below it

When cfTip persisted far below the block-sync floor (legacy scar — cfheaders
never kept pace), the stop-hash walk can never reach the next batch and the
chain stalls forever. BRPeerManagerReanchorCompactFilterChainAtFloor discards
the stuck chain and resets the auto-fetch cursor to the lowest contiguous
downloaded block; _peerRelayedCFHeaders then TOFU-recreates a fresh chain there.
The historical gap was already bloom-scanned (caller gates on has_synced).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

(Push + pin bump happen in Task 5, after the JNI/Kotlin side is ready, to keep one pin bump.)

---

### Task 2: JNI bridge + NativeBridge declaration

**Files:**
- Modify: `native/src/main/jni/bridge/jni_peer.c` (add after the `getCFChainTipHeight` JNI, ~line 868)
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (add after line 334, `external fun getCFChainTipHeight(): Int`)

- [ ] **Step 1: Add the JNI wrapper**

In `jni_peer.c`, immediately after the closing brace of `Java_io_digibyte_core_bridge_NativeBridge_getCFChainTipHeight` (~line 868), add:

```c
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_reanchorCompactFilterChainAtFloor(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_peerManager) {
        LOGI("reanchorCompactFilterChainAtFloor: peer manager not created — ignoring");
        return JNI_FALSE;
    }
    int r = BRPeerManagerReanchorCompactFilterChainAtFloor(g_peerManager);
    if (r) LOGI("reanchorCompactFilterChainAtFloor: re-anchored filter chain at block floor");
    return r ? JNI_TRUE : JNI_FALSE;
}
```

- [ ] **Step 2: Declare the Kotlin external function**

In `NativeBridge.kt`, immediately after line 334 (`external fun getCFChainTipHeight(): Int`), add:

```kotlin
    /** Re-anchor the compact-filter chain at the block floor when cfTip is stuck
     *  below the downloaded chain (legacy deficit). Returns true if re-anchored. */
    external fun reanchorCompactFilterChainAtFloor(): Boolean
```

- [ ] **Step 3: Verify native + app compile**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`
Expected: `BUILD SUCCESSFUL`. (If the native `.so` does not pick up the C change, force it: `rm -rf native/build/intermediates/cxx native/.cxx native/build/intermediates/cmake` then rebuild.)

- [ ] **Step 4: Commit (main repo — JNI + Kotlin only, no pin bump yet)**

```bash
git add native/src/main/jni/bridge/jni_peer.c core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt
git commit -m "feat(bip158): JNI + NativeBridge for reanchorCompactFilterChainAtFloor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: SyncService watchdog wiring

**Files:**
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt` — add a once-per-session flag near the watchdog's local vars (after line 386, `var lastBlockProgressMs = startedAt`), and rewrite the caught-up-but-stuck fallback branch (currently line 469, `if (elapsedMs >= BIP158_FALLBACK_TIMEOUT_MS) {` inside the "Headers are caught up" comment block).

- [ ] **Step 1: Add the once-per-session flag**

In `startBip158Watchdog`, immediately after `var lastBlockProgressMs = startedAt` (line 386), add:

```kotlin
            // Re-anchor recovery is attempted at most once per sync session so a
            // poll landing before the first re-anchored cfheaders append (cfTip
            // not yet jumped) can't re-fire it every poll.
            var reanchoredThisSession = false
```

- [ ] **Step 2: Rewrite the caught-up fallback branch to attempt re-anchor first**

Replace this exact block (the "Headers are caught up" branch, lines 466–480):

```kotlin
                // Headers are caught up to the network tip but cfheaders still
                // isn't advancing — genuine filter-peer/decode failure. Fall back
                // once past the grace window.
                if (elapsedMs >= BIP158_FALLBACK_TIMEOUT_MS) {
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: headers caught up (blockTip=$blockTip) but no " +
                        "cfheaders progress after ${elapsedMs}ms (cfTip stuck at $cfTipNow, " +
                        "gap=$gap) — falling back to bloom")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
                }
```

with:

```kotlin
                // Headers are caught up to the network tip but cfheaders still
                // isn't advancing. Before falling back to bloom, try a one-time
                // re-anchor: a legacy wallet can have cfTip persisted far below the
                // block floor (the gap was never re-downloaded), which retention
                // can't bridge. Re-anchoring discards the stuck chain and restarts
                // filters at the floor. The skipped gap was already bloom-scanned —
                // gated on hasReachedSynced, which is that guarantee.
                if (elapsedMs >= BIP158_FALLBACK_TIMEOUT_MS) {
                    if (hasReachedSynced && !reanchoredThisSession) {
                        val reanchored = try {
                            NativeBridge.reanchorCompactFilterChainAtFloor()
                        } catch (t: Throwable) {
                            android.util.Log.e("SyncService", "BIP158 watchdog: re-anchor threw", t)
                            false
                        }
                        if (reanchored) {
                            reanchoredThisSession = true
                            // Kotlin owns SharedPreferences: drop the stale chain so a
                            // kill before the first re-anchored append can't restore
                            // the stuck cfTip.
                            getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                                .edit().remove("saved_filter_headers").apply()
                            android.util.Log.i("SyncService",
                                "BIP158 watchdog: re-anchored filter chain at block floor " +
                                "(cfTip was $cfTipNow, below floor) — staying on filters")
                            continue
                        }
                    }
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: headers caught up (blockTip=$blockTip) but no " +
                        "cfheaders progress after ${elapsedMs}ms (cfTip stuck at $cfTipNow, " +
                        "gap=$gap) — falling back to bloom")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
                }
```

- [ ] **Step 3: Verify app compiles**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit (main repo — watchdog wiring only, pin bump in Task 5)**

```bash
git add app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(bip158): watchdog triggers filter-chain re-anchor before bloom fallback

When headers are caught up but cfTip is stuck (cfTip below the block floor on a
legacy wallet), attempt a one-time re-anchor at the floor and stay on filters,
gated on hasReachedSynced (proof the skipped gap was bloom-scanned). Falls back
to bloom only if not synced, already re-anchored this session, or re-anchor
reports it's not actually below the floor.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Push submodule, bump pin

**Files:**
- The submodule commit from Task 1 (Step 7) → push to `johnnylaw` fork.
- Modify: main-repo submodule gitlink `native/src/main/jni/digibytewallet-core`.

- [ ] **Step 1: Push the submodule commit to the fork**

```bash
cd /home/polloloco/digibytewallet-android
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git push johnnylaw feature/bip158
```

Expected: `... -> feature/bip158`. Capture the new submodule HEAD:

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git rev-parse HEAD
```

- [ ] **Step 2: Stage and commit the pin bump**

```bash
git add native/src/main/jni/digibytewallet-core
git commit -m "chore(native): bump core pin for filter-chain re-anchor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Verify the committed gitlink matches the pushed HEAD:
`git ls-tree HEAD native/src/main/jni/digibytewallet-core | grep -oE '[0-9a-f]{40}'`

---

### Task 5: Build, install, and verify on the legacy Note8 wallet

**Files:** none (verification). This is the real test of the manager function + JNI + watchdog (no unit harness exists for those layers).

- [ ] **Step 1: Build and install**

```bash
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
# confirm the new code is in the APK .so:
unzip -p app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk lib/arm64-v8a/libcore-lib.so | strings | grep -c "re-anchoring filter chain"
# expected: 1
adb -s ce061716640b191c017e install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```

- [ ] **Step 2: Launch, unlock (PIN entered on device), and capture recovery**

```bash
D=ce061716640b191c017e
adb -s $D shell monkey -p io.digibyte -c android.intent.category.LAUNCHER 1
adb -s $D shell input keyevent KEYCODE_WAKEUP
adb -s $D logcat -c
# (enter PIN on the device)
# wait for sync, then capture ~3-4 min:
timeout 240 adb -s $D logcat bread:* SyncService:* DGB-JNI:* '*:S' > /tmp/dgb_reanchor.log
```

- [ ] **Step 3: Verify success criteria**

```bash
echo "re-anchor fired:";        grep -c "re-anchoring filter chain\|re-anchored filter chain at block floor" /tmp/dgb_reanchor.log
echo "cfheaders advancing:";    grep "cfheaders: chain extended" /tmp/dgb_reanchor.log | tail -3
echo "watchdog healthy:";       grep -c "BIP158 watchdog: healthy" /tmp/dgb_reanchor.log
echo "fell back to bloom:";     grep -c "falling back to bloom" /tmp/dgb_reanchor.log
```

Expected:
- re-anchor fired ≥ 1
- `cfheaders: chain extended to height …` climbing from ~the block floor (~23.57M) toward the tip
- watchdog `healthy` ≥ 1 (cfTip caught blockTip), OR cfTip visibly within ~100 of blockTip in the final watchdog line
- fell back to bloom = 0

If it fell back instead: check whether `hasReachedSynced` was true at trigger time, whether a filter peer was connected, and whether `BRPeerManagerReanchorCompactFilterChainAtFloor` returned true (the `re-anchoring filter chain` DGB-JNI/bread line). File findings before iterating — do not blind-retry.

- [ ] **Step 4: Regression check — healthy wallet path unaffected**

Confirm the re-anchor never fires on a caught-up wallet: in the captured log, a wallet that reaches `healthy` quickly should show **0** `re-anchoring filter chain` lines (the `next >= floor` guard returns 0). This is implicit in Step 3 for the legacy wallet once recovered, but note it explicitly for any fresh-wallet test.

---

## Notes for the implementer

- **One pin bump:** Task 1 commits the submodule but does **not** push; Task 4 pushes + bumps the pin once, after JNI/Kotlin are in. This avoids a half-wired pin.
- **Native rebuild caching:** gradle's CMake task can silently skip a C change. If `strings` on the APK `.so` doesn't show new code, `rm -rf native/build/intermediates/cxx native/.cxx native/build/intermediates/cmake` and rebuild.
- **odocrypt revert:** the C test build temporarily rewrites `crypto/odocrypt.h`. ALWAYS revert it (the steps do). Verify the submodule is clean before committing.
- **Device PIN:** the Note8 is PIN-locked; Step 2 of Task 5 requires the human to enter the PIN. There is no automated unlock.
