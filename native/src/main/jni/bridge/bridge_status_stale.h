//
//  bridge_status_stale.h
//
//  Staleness predicate for the bridge-level lock-free status mirrors
//  (jni_peer.c). The bridge keeps _Atomic mirrors of the peer count, block
//  heights, CF tip and sync mode, refreshed from BRPeerManager's own lock-free
//  accessors at every safe site (peer-thread callbacks + PEER_GUARD-holding
//  mutator tails) and stamped with a CLOCK_MONOTONIC timestamp. A UI/watchdog
//  status read is then a pure atomic_load — no PEER_GUARD, no g_peerManager
//  deref, so it can never block behind (or use-after-free against) a teardown.
//
//  A refresh timestamp lets a consumer tell "0 peers" (a real, fresh sample)
//  from "no fresh sample" (the frozen-loop signature — the mirror stopped
//  updating). This header holds ONLY the pure mapping over caller-supplied
//  scalars (no BRPeerManager struct, no locking, no I/O) so it is testable
//  standalone on the host (see native/src/test/host/status_staleness_kat/).
//  Modeled on BRPeerCFStatus.h's static-inline-predicate-in-a-header shape.
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to deal
//  in the Software without restriction, including without limitation the rights
//  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//  copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//  THE SOFTWARE.

#ifndef bridge_status_stale_h
#define bridge_status_stale_h

#include <stdint.h>

// Default staleness bound (ms). 2x the fastest planned supervisor cadence.
// The idle heartbeat that keeps the mirror warm is keepAlivePeers at ~10s.
#define STATUS_STALE_MS 10000

// True if the last mirror refresh is too old to trust:
//   - lastMs == 0            → never refreshed this session → stale
//   - (nowMs - lastMs) > bound → past the freshness bound   → stale
// Exactly at the bound is NOT stale (strict `>`), so a sample taken exactly
// `boundMs` ago still reads fresh. All times are CLOCK_MONOTONIC ms.
static inline int bridge_status_is_stale(int64_t lastMs, int64_t nowMs, int64_t boundMs)
{
    if (lastMs == 0) return 1;
    return (nowMs - lastMs) > boundMs ? 1 : 0;
}

#endif // bridge_status_stale_h
