# BIP158 Continuity-Failure Re-Anchor Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When ≥2 distinct peers fail the cfheaders continuity check, recognize the wallet's filter chain is the outlier (it diverged via unverified TOFU) and re-anchor + re-sync instead of marking honest peers misbehavin' and burning the pool to bloom.

**Architecture:** C-core only. Refactor the existing re-anchor into a lock-held helper with a `force` flag; add a small disagreement-tracking set to the `BRPeerManager` struct; rewrite the continuity-fail branch of `_peerRelayedCFHeaders` to record-and-maybe-re-anchor; remove the temporary diagnostic logging. No JNI/Kotlin behavior change.

**Tech Stack:** C (digibytewallet-core submodule), JNI bridge (one diagnostic-removal edit). Spec: `docs/superpowers/specs/2026-06-08-bip158-continuity-reanchor-design.md`.

**Submodule note:** `BRPeerManager.c/.h` are in the `digibytewallet-core` git submodule. Commit with the submodule git pattern, push to the `johnnylaw` fork, then bump the pin. `jni_peer.c` is in the MAIN repo.

---

## File Structure

- `native/src/main/jni/digibytewallet-core/BRPeerManager.h` — two tuning constants (before the struct that uses one as an array size).
- `native/src/main/jni/digibytewallet-core/BRPeerManager.c` — struct fields, a forward decl, the re-anchor refactor (`_BRPeerManagerReanchorAtFloorLocked`), the continuity-fail rewrite, the success-path reset, and removal of the BRPeerManager.c DIAG log.
- `native/src/main/jni/bridge/jni_peer.c` — remove the restore-time DIAG log (main repo).

**Testing reality:** The chain-level continuity check is already unit-tested (`test_append_continuity_rejection` in `BRCompactFilterChainTests`). The new manager-level recovery (disagreement counting + re-anchor) has no unit harness in this codebase, so it is **device-verified** on the Note8 reboot repro (Task 3) — consistent with how the v3.6.0/3.6.1 watchdog and re-anchor logic were validated. Each C task compile-checks via the standalone test build.

---

### Task 1: C core — constants, struct fields, re-anchor refactor, continuity rewrite

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.h`
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c`

Apply each edit by finding the exact OLD anchor and replacing with NEW. If any anchor differs, STOP and report NEEDS_CONTEXT.

- [ ] **Step 1: Add tuning constants (BRPeerManager.h)**

OLD:
```c
#define CLEAR_MEM_CF_RETENTION_MARGIN 144
```
NEW:
```c
#define CLEAR_MEM_CF_RETENTION_MARGIN 144

/* BIP 158 continuity-failure recovery. If this many DISTINCT peers fail the
   cfheaders continuity check against our current tip since the last successful
   append, our chain is the outlier (it diverged via unverified TOFU) — re-anchor
   instead of marking the (honest) peers misbehavin'. Bounded per session so a
   persistently-divergent peer can't loop forever. */
#define CF_CONTINUITY_REANCHOR_K   2
#define CF_CONTINUITY_REANCHOR_MAX 3
```

- [ ] **Step 2: Add struct fields (BRPeerManager.c)**

OLD:
```c
    uint32_t cfHeadersRequestedThrough;  // batchEnd of the in-flight request (0 = none)
    time_t   cfHeadersRequestTime;       // when it was sent, for the timeout
```
NEW:
```c
    uint32_t cfHeadersRequestedThrough;  // batchEnd of the in-flight request (0 = none)
    time_t   cfHeadersRequestTime;       // when it was sent, for the timeout
    // Distinct peers that failed the cfheaders continuity check since the last
    // successful append. K of them disagreeing means WE are the outlier.
    UInt128  cfDisagreedPeers[CF_CONTINUITY_REANCHOR_K];
    uint8_t  cfDisagreedCount;
    uint8_t  cfReanchorCount;            // continuity-triggered re-anchors this session
```

- [ ] **Step 3: Add a forward declaration for the lock-held re-anchor helper (BRPeerManager.c)**

OLD:
```c
static void _BRPeerManagerRequestNextCFHeaders(BRPeerManager *manager, BRPeer *peer);
```
NEW:
```c
static void _BRPeerManagerRequestNextCFHeaders(BRPeerManager *manager, BRPeer *peer);
static int _BRPeerManagerReanchorAtFloorLocked(BRPeerManager *manager, int force);
```

- [ ] **Step 4: Refactor the public re-anchor into the lock-held helper + thin wrapper (BRPeerManager.c)**

OLD (the entire current public function):
```c
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
    // Establishing a fresh auto-fetch anchor at the floor — arm auto-fetch so the
    // chain-less driver path resolves `next` to the floor (not genesis) and the
    // lazy-create in _peerRelayedCFHeaders uses it.
    manager->autoFetchCFiltersEnabled  = 1;
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
NEW:
```c
// Discard the compact-filter chain and re-anchor at the block floor. Caller MUST
// hold manager->lock. With force=0 (watchdog path) only re-anchors when cfTip is
// below the floor (the unbridgeable-gap case). With force=1 (continuity-failure
// recovery) re-anchors regardless — the chain is divergent wherever cfTip sits.
// Returns 1 if it re-anchored, 0 otherwise.
static int _BRPeerManagerReanchorAtFloorLocked(BRPeerManager *manager, int force)
{
    if (manager->syncMode == BR_SYNC_MODE_BLOOM_ONLY || !manager->compactFilterChain) return 0;

    uint32_t next  = BRCompactFilterChainNextHeight(manager->compactFilterChain);
    uint32_t floor = _BRPeerManagerBlockFloor(manager);
    if (floor == 0) return 0;
    if (!force && next >= floor) return 0;   // watchdog path keeps the cfTip<floor guard

    peer_log(&BR_PEER_NONE, "cfheaders: re-anchoring filter chain (force=%d) from tip %u to block floor %u",
             force, next > 0 ? next - 1 : 0, floor);

    BRCompactFilterChainFree(manager->compactFilterChain);
    manager->compactFilterChain = NULL;
    // Arm auto-fetch so the chain-less driver resolves `next` to the floor (not
    // genesis) and the lazy-create in _peerRelayedCFHeaders uses it.
    manager->autoFetchCFiltersEnabled  = 1;
    manager->autoFetchCFiltersStart    = floor;
    manager->autoFetchCFiltersThrough  = floor > 0 ? floor - 1 : 0;
    manager->cfHeadersRequestedThrough = 0;
    manager->cfDisagreedCount          = 0;   // fresh disagreement window

    // Kick recovery immediately if a filter peer is connected; otherwise the
    // next block-extend kick handles it once filter-first connects one.
    BRPeer *fp = _BRPeerManagerAnyFilterCapablePeer(manager);
    if (fp) _BRPeerManagerRequestNextCFHeaders(manager, fp);
    return 1;
}

int BRPeerManagerReanchorCompactFilterChainAtFloor(BRPeerManager *manager)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    int r = _BRPeerManagerReanchorAtFloorLocked(manager, 0);
    pthread_mutex_unlock(&manager->lock);
    return r;
}
```

- [ ] **Step 5: Rewrite the continuity-fail branch and remove its DIAG (BRPeerManager.c)**

OLD:
```c
    int ok = BRCompactFilterChainAppend(manager->compactFilterChain, prevFilterHeader, filterHashes, count);
    if (!ok) {
        {
            UInt256 _ctip = BRCompactFilterChainTipHeader(manager->compactFilterChain);
            uint32_t _cs = BRCompactFilterChainStartHeight(manager->compactFilterChain);
            uint32_t _cc = (uint32_t)BRCompactFilterChainCount(manager->compactFilterChain);
            peer_log(peer, "cfheaders: continuity FAIL [DIAG] chain start=%u count=%u next=%u "
                     "peerPrev=%02x%02x%02x%02x chainTip=%02x%02x%02x%02x",
                     _cs, _cc, _cs + _cc,
                     prevFilterHeader.u8[0], prevFilterHeader.u8[1], prevFilterHeader.u8[2], prevFilterHeader.u8[3],
                     _ctip.u8[0], _ctip.u8[1], _ctip.u8[2], _ctip.u8[3]);
        }
        // Release the in-flight guard so another peer can retry this batch.
        manager->cfHeadersRequestedThrough = 0;
        _BRPeerManagerPeerMisbehavin(manager, peer);
        pthread_mutex_unlock(&manager->lock);
        return;
    }
```
NEW:
```c
    int ok = BRCompactFilterChainAppend(manager->compactFilterChain, prevFilterHeader, filterHashes, count);
    if (!ok) {
        // Record this peer as one that disagrees with our tip (dedup by address).
        // Do NOT mark it misbehavin'/disconnect — if the majority disagrees, the
        // honest peers are right and OUR chain is the divergent outlier.
        int _known = 0;
        for (uint8_t i = 0; i < manager->cfDisagreedCount; i++) {
            if (UInt128Eq(manager->cfDisagreedPeers[i], peer->address)) { _known = 1; break; }
        }
        if (!_known && manager->cfDisagreedCount < CF_CONTINUITY_REANCHOR_K) {
            manager->cfDisagreedPeers[manager->cfDisagreedCount++] = peer->address;
        }
        manager->cfHeadersRequestedThrough = 0;  // let another peer be tried

        if (manager->cfDisagreedCount >= CF_CONTINUITY_REANCHOR_K &&
            manager->cfReanchorCount < CF_CONTINUITY_REANCHOR_MAX) {
            manager->cfReanchorCount++;
            peer_log(peer, "cfheaders: %u peers disagree with our tip — chain is the outlier, "
                     "re-anchoring (attempt %u/%u)",
                     manager->cfDisagreedCount, manager->cfReanchorCount, CF_CONTINUITY_REANCHOR_MAX);
            _BRPeerManagerReanchorAtFloorLocked(manager, 1);
            pthread_mutex_unlock(&manager->lock);
            return;
        }

        // Below the K threshold, or re-anchor budget exhausted: don't append and
        // don't punish. If the budget is exhausted the chain stops advancing and
        // the SyncService watchdog falls back to bloom as today — pool never burned.
        peer_log(peer, "cfheaders: continuity mismatch (%u/%u disagree, reanchors %u/%u) — not appending",
                 manager->cfDisagreedCount, CF_CONTINUITY_REANCHOR_K,
                 manager->cfReanchorCount, CF_CONTINUITY_REANCHOR_MAX);
        pthread_mutex_unlock(&manager->lock);
        return;
    }
```

- [ ] **Step 6: Clear the disagreement window on a successful append (BRPeerManager.c)**

OLD:
```c
    uint32_t chainTip = BRCompactFilterChainNextHeight(manager->compactFilterChain) - 1;
    // Mark the in-flight request satisfied through the actual new tip (a peer
    // may return fewer than MAX_CFHEADERS_RESULTS); the continuation below then
    // requests the next batch instead of being blocked by a stale guard value.
    manager->cfHeadersRequestedThrough = chainTip;
```
NEW:
```c
    uint32_t chainTip = BRCompactFilterChainNextHeight(manager->compactFilterChain) - 1;
    // Mark the in-flight request satisfied through the actual new tip (a peer
    // may return fewer than MAX_CFHEADERS_RESULTS); the continuation below then
    // requests the next batch instead of being blocked by a stale guard value.
    manager->cfHeadersRequestedThrough = chainTip;
    manager->cfDisagreedCount = 0;   // appended cleanly — clear the disagreement window
```

- [ ] **Step 7: Compile-check the core (standalone C test build)**

Plain `gcc` chokes on `const static int` array dims in `crypto/odocrypt.h`; apply a temporary enum patch, build, run, then REVERT:

```bash
cd /home/polloloco/digibytewallet-android/native/src/main/jni/digibytewallet-core
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
Expected: compiles clean, prints `BRCompactFilterChainTests: all passing`. Confirm `crypto/odocrypt.h` is reverted (uses `const static int`).

- [ ] **Step 8: Commit the submodule (do NOT push yet)**

```bash
cd /home/polloloco/digibytewallet-android
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRPeerManager.c BRPeerManager.h
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(bip158): re-anchor when the peer majority rejects our filter chain

A TOFU-built cfheaders chain can diverge if a single peer seeds/extends it with
non-canonical filter data; on restore every honest peer then fails the continuity
check, and the wallet marked them misbehavin', burned the filter pool, and fell to
bloom every restart. Now: when K=2 distinct peers disagree with our tip, WE are the
outlier — discard and re-anchor at the (recent, retention-bounded) block floor and
re-sync, without punishing the honest peers. Bounded to 3 re-anchors/session, then
the watchdog handles the terminal bloom fallback. Removes the temporary DIAG log.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Before committing, verify the submodule tree shows ONLY BRPeerManager.c/.h modified and `crypto/odocrypt.h` is reverted:
`GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git status --short --ignore-submodules=all`

Report the new submodule HEAD SHA.

---

### Task 2: Remove the restore DIAG, push submodule, bump pin

**Files:**
- Modify: `native/src/main/jni/bridge/jni_peer.c` (main repo)

- [ ] **Step 1: Remove the restore-time DIAG log (jni_peer.c)**

OLD:
```c
    {
        UInt256 _rtip = BRCompactFilterChainTipHeader(chain);
        uint32_t _rs = BRCompactFilterChainStartHeight(chain);
        uint32_t _rc = (uint32_t)BRCompactFilterChainCount(chain);
        LOGI("setCompactFilterChain: restored chain (%d bytes) [DIAG] start=%u count=%u next=%u tip=%02x%02x%02x%02x",
             (int)len, _rs, _rc, _rs + _rc,
             _rtip.u8[0], _rtip.u8[1], _rtip.u8[2], _rtip.u8[3]);
    }
    BRPeerManagerSetCompactFilterChain(g_peerManager, chain);
    return JNI_TRUE;
```
NEW:
```c
    BRPeerManagerSetCompactFilterChain(g_peerManager, chain);
    LOGI("setCompactFilterChain: restored chain (%d bytes)", (int)len);
    return JNI_TRUE;
```

- [ ] **Step 2: Push the submodule commit to the fork**

```bash
cd /home/polloloco/digibytewallet-android
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git push johnnylaw feature/bip158
```
Expected: `... -> feature/bip158`. Capture the submodule HEAD:
```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git rev-parse HEAD
```

- [ ] **Step 3: Bump the pin and commit jni_peer.c together (main repo)**

```bash
git add native/src/main/jni/digibytewallet-core native/src/main/jni/bridge/jni_peer.c
git commit -m "feat(bip158): bump core pin for continuity-failure re-anchor; drop restore DIAG

Pins digibytewallet-core to the continuity-failure re-anchor recovery and removes
the temporary diagnostic restore log from the JNI bridge.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
Verify the committed gitlink matches the pushed HEAD:
`git ls-tree HEAD native/src/main/jni/digibytewallet-core | grep -oE '[0-9a-f]{40}'`

---

### Task 3: Build, install, and verify on the Note8 reboot repro

**Files:** none (verification). The Note8 currently runs the instrumented DIAG build; this replaces it with the fix.

- [ ] **Step 1: Build and confirm the new code is in the APK, and the DIAG is gone**

```bash
cd /home/polloloco/digibytewallet-android
rm -rf native/build/intermediates/cxx native/.cxx native/build/intermediates/cmake
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -3
APK=app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
echo "re-anchor recovery present: $(unzip -p $APK lib/arm64-v8a/libcore-lib.so | strings | grep -c 'peers disagree with our tip')"   # expect 1
echo "DIAG gone: $(unzip -p $APK lib/arm64-v8a/libcore-lib.so | strings | grep -c '\[DIAG\]')"                                        # expect 0
adb -s ce061716640b191c017e install -r $APK
```
Expected: `BUILD SUCCESSFUL`, recovery present = 1, DIAG gone = 0, install `Success`.

- [ ] **Step 2: Reproduce the reboot scenario and capture**

```bash
D=ce061716640b191c017e
adb -s $D shell am force-stop io.digibyte
adb -s $D logcat -c
adb -s $D shell monkey -p io.digibyte -c android.intent.category.LAUNCHER 1
adb -s $D shell input keyevent KEYCODE_WAKEUP
# (enter PIN on the device)
timeout 240 adb -s $D logcat bread:* SyncService:* '*:S' > /tmp/dgb_continuity.log
```

- [ ] **Step 3: Verify recovery, not bloom**

```bash
echo "re-anchor fired:";  grep -c "peers disagree with our tip" /tmp/dgb_continuity.log
echo "cfheaders advancing after re-anchor:"; grep "cfheaders: chain extended" /tmp/dgb_continuity.log | tail -3
echo "peers marked misbehavin' (should be ~0 for filter peers):"; grep -c "treating peer as misbehavin'" /tmp/dgb_continuity.log
echo "fell back to bloom:"; grep -c "falling back to bloom" /tmp/dgb_continuity.log
```
Expected: re-anchor fires (`peers disagree with our tip … re-anchoring`), `cfheaders: chain extended` climbs afterward, no storm of `misbehavin'` disconnects on the seeder filter peers, and **no `falling back to bloom`** (the wallet stays on compact filters). If it still falls back after 3 re-anchors, that's the bounded give-up — note it and check whether a single peer kept winning the TOFU race (the seeder-side correctness validation is the prevention, tracked separately).

---

## Self-Review notes

- **Spec coverage:** constants (T1 S1) ✓; struct fields (S2) ✓; forward decl (S3) ✓; `_BRPeerManagerReanchorAtFloorLocked` + wrapper (S4) ✓; continuity rewrite incl. K-distinct + N-bound + no-punish (S5) ✓; success reset (S6) ✓; remove BRPeerManager.c DIAG (S5) ✓; remove jni_peer.c DIAG (T2 S1) ✓; push+pin (T2) ✓; device verify (T3) ✓.
- **Type consistency:** `CF_CONTINUITY_REANCHOR_K`/`_MAX` (int defines), `cfDisagreedPeers` (`UInt128[K]`), `cfDisagreedCount`/`cfReanchorCount` (`uint8_t`), `_BRPeerManagerReanchorAtFloorLocked(manager, int force)` signature matches forward decl, call sites, and definition. `UInt128Eq`, `peer->address`, `_BRPeerManagerBlockFloor`, `_BRPeerManagerAnyFilterCapablePeer`, `_BRPeerManagerRequestNextCFHeaders` all pre-exist.
- **No placeholders:** every step has complete code/commands.

## Release

After Tasks 1–3 pass, cut **v3.6.2** via the release-prep ritual (3.6.1→3.6.2 / 30062→30063). Not part of this plan's commits.

## Out of scope (separate)

- `getcfcheckpt` anchor cross-verification (client-side divergence prevention).
- Bounding the persisted chain (9.2 MB SharedPreferences blob).
- Seeder-side filter-correctness validation.
