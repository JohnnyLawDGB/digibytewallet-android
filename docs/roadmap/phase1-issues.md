# Phase 1 — BIP 157/158 Sovereign Data Layer: Issue Breakdown

Canonical reference for the Phase 1 work defined in [`ROADMAP.md`](../../ROADMAP.md).
Each issue below is bounded, has explicit dependencies, and has concrete
acceptance criteria. Order within the dependency graph is enforced; rough
effort sizing is S (≤ 2 days), M (3 days – 1 week), L (> 1 week).

Supersedes any earlier sketch of Phase 1 work — this version reflects the
research conclusions summarized in §[Design decisions](#design-decisions).

## Dependency graph

```
Pre-flight (node-side + measurement)
  P1.1 ─ P1.3 ─ P1.8 ─ P1.9 ─┐
  P1.2 ─────────────────────── │
                               │
C core — decoder + codec       ├─ P1.10 ─ P1.11 ─┬─ P1.11a
  P1.4 ─ P1.5 ─┬─ P1.6 ─ P1.7 ─┘                 ├─ P1.17 (soak)
                └─ P1.16 (tests)                  ├─ P1.18 (doc)
                                                  ├─ P1.19 (doc)
                                                  └─ P1.20 (doc)
Seeder + Kotlin
  P1.12 ─ P1.14 ─────────────────────────────────┤
  P1.13 ─ P1.15 ─────────────────────────────────┤
  P1.16a ────────────────────────────────────────┘
```

**Critical path:** P1.4 → P1.5 → P1.6 → P1.7 → P1.9 → P1.10 → P1.11 → P1.17.
Realistically 5–7 weeks of focused C-core work, less if operational
(P1.1 / P1.2 / P1.12) and Kotlin (P1.13 / P1.14 / P1.15) work parallelizes.

## Design decisions

These are conclusions from research against Bitcoin Core v26.2 (on which
DGB Core 8.26 is based), neutrino, and the BIP 157/158 specs. They
constrain the issue structure below.

**Checkpoints are bootstrap hints, not authority.** Neutrino ships only
hardcoded *block-header* checkpoints. Filter-header checkpoints are
fetched at runtime via `getcfcheckpt` from multiple peers, with
peer-quorum agreement required before any value is accepted. On
disagreement, the honest peer is identified by requesting the actual
`cfilter` at the first divergent block and recomputing the hash locally.
A shipped checkpoint that disagrees with honest runtime peers causes a
loud sync failure — not silent trust in the shipped value.

This is the opposite of "authoritative hardcoded checkpoints" and is
strictly better for sovereignty. Issue P1.9 implements the runtime
side of this; issue P1.8 produces the shipped hints.

**10,000-block cadence for shipped hints.** 83 KB of compiled-in data
vs. 830 KB at a 1000-block cadence. Verifying the 10 k intermediate
headers against a runtime-verified anchor is milliseconds of SHA-256 on
mobile hardware, so the decoupling from BIP 157's native 1000-block
`cfcheckpt` cadence costs nothing and saves an order of magnitude in
APK impact.

**Start from vendored reference code.** `src/blockfilter.cpp` in DGB
Core (inherited from Bitcoin Core v26.2) is the canonical GCS encoder
and decoder implementation. Port it verbatim into C first; optimize
later. The SipHash key derivation and Golomb-Rice bit alignment are the
most bug-prone parts of a from-scratch implementation, and passing the
BIP 158 appendix test vectors requires both to be exactly right.

**Dual-mode fallback is transitional, not permanent.** The bloom path
stays as a fallback while the 0x40-capable peer population grows, but
the long-term goal is removing bloom entirely. Dual-mode code is
feature-flagged so it can be retired once the service-bit population
crosses a threshold.

## Operational reality

Real costs that shape P1.1 / P1.2 scoping:

- Enabling `blockfilterindex=basic` on a DGB node adds **~6–12 GB** of
  extra disk (extrapolated from Bitcoin Core's ~4–5 GB for 900 k blocks,
  adjusted for DGB's 23 M blocks and lower tx density per block).
- **Initial filter index build time: 4–12 hours** from a synced node,
  depending on disk speed. Not reversible without a rebuild.
- **Not compatible with `prune=<n>`.** Operators running pruned nodes
  cannot contribute to Phase 1 without unpruning.
- DGB's 15-second block times (vs. Bitcoin's 10 min) mean a full-chain
  filter-header sync needs **~40× more `getcfheaders` roundtrips** than
  Bitcoin — `MAX_GETCFHEADERS_SIZE=2000` covers ~8 hours of DGB vs.
  ~2 weeks of BTC. Aggregate roundtrip volume matters; P1.3 measures it.

---

## Issues

### P1.1 — Enable BIP 157/158 serving on digiscope.me DGB node
**Size:** S (30 min of ops + up to 12 h passive index build).
**Depends:** —
**Blocks:** P1.3, P1.2 (for coordination), P1.12.

Add to `/etc/digibyte/digibyte.conf` on the VPS:
```
blockfilterindex=basic
peerblockfilters=1
```
Restart the daemon. The initial filter index build runs in the
background and takes hours; the node continues serving blocks and
bloom peers throughout.

**Acceptance:**
- `digibyte-cli getindexinfo` reports `blockfilterindex.best_block_height`
  equal to current tip.
- `digibyte-cli getpeerinfo` on a remote test client shows our node's
  advertised services including bit 6 (value 0x40,
  `NODE_COMPACT_FILTERS`).
- `digibyte-cli getblockfilter <any-recent-blockhash>` returns a
  non-empty `filter` hex string.
- Measured: `du -sh ~/.digibyte/indexes/blockfilter/` — record real
  disk cost and index build time for the Phase 1 readout.

### P1.2 — Enlist two community-operated DGB nodes to serve filters
**Size:** S (coordination, days).
**Depends:** P1.1 (so we have a reference config + known resource cost
to cite to operators).
**Blocks:** P1.3 (cross-verification of measurements), P1.8
(cross-verification of shipped checkpoints), P1.9 (at runtime the
wallet needs ≥ 2 filter-serving peers to form a quorum).

Approach the existing bloom-seeder operators — they already run v8.26
with `peerbloomfilters=1` and have the ops chops. Flag disk + build-time
costs honestly.

**Acceptance:**
- Two nodes, operated by two independent people (not the wallet
  author), confirmed advertising `NODE_COMPACT_FILTERS` and serving
  filters.
- Endpoints documented in `docs/operations/filter-serving-nodes.md`.

### P1.3 — Measure real DGB filter economics
**Size:** S–M (a day including the script and the readout).
**Depends:** P1.1 (need a serving node to query).
**Blocks:** P1.8 (checkpoint cadence decision), P1.10 (strategy
viability).

Script in `scripts/measure-filter-economics.py` that hits a
filter-serving node and collects:
- Random sample of 10 k blocks across the full chain: avg, p95, p99
  filter size in bytes.
- Filter size distribution at current tip vs. historical blocks.
- Projected total download for (all filter headers genesis → tip) and
  (all filters for 90 days of blocks, the most common wallet-birthday
  case).
- Projected number of `getcfheaders` roundtrips to sync genesis → tip
  given `MAX_GETCFHEADERS_SIZE=2000`.

**Acceptance:**
- Script committed, re-runnable.
- Readout committed to `docs/research/2026-XX-XX-filter-economics.md`
  with the numbers and a conclusion on whether pure-BIP157 sync is
  viable over mobile data, or whether parallel-peer fetching is a hard
  requirement for P1.10.

### P1.4 — GCS filter decoder (ported from Bitcoin Core v26.2)
**Size:** M (1 week including test coverage).
**Depends:** —
**Blocks:** P1.5, P1.6, P1.11, P1.16.

New `native/src/main/jni/digibytewallet-core/BRGCSFilter.{c,h}`.
Transliterate `src/blockfilter.cpp` from the vendored DGB Core source
at `/home/polloloco/digibyte/src/blockfilter.cpp` (which is
unmodified from Bitcoin Core v26.2) into C. Stay close to the
reference; defer optimization.

Public API:
```c
typedef struct BRGCSFilter BRGCSFilter;

BRGCSFilter *BRGCSFilterFromBytes(const uint8_t *bytes, size_t len,
                                  const UInt256 *blockHash);
int          BRGCSFilterMatch(const BRGCSFilter *f,
                              const uint8_t *element, size_t elemLen);
int          BRGCSFilterMatchAny(const BRGCSFilter *f,
                                 const uint8_t *const *elements,
                                 const size_t *elemLens, size_t n);
UInt256      BRGCSFilterHash(const BRGCSFilter *f);   // dSHA256(filterBytes)
void         BRGCSFilterFree(BRGCSFilter *f);
```

Parameters fixed at BIP 158 basic filter values: P = 19, M = 784931,
SipHash-2-4 keyed by the first 16 raw-wire bytes of `blockHash` (NOT
the hex-string byte order — BIP 158 §Parameter section, Bitcoin Core
`blockfilter.cpp`).

**Acceptance:**
- Passes every BIP 158 appendix test vector byte-for-byte
  (bip-0158.mediawiki §Test Vectors — blocks 987876 and others).
- SipHash key derivation verified against appendix.
- Empty-filter case handled: filter bytes = empty ⇒ filter hash =
  `dSHA256(empty)` = `0x5df6e0e2...`.
- `NumFilterBytes` parsing rejects CompactSize > 100 KB with an
  explicit error, not an allocation.
- No `malloc` in `BRGCSFilterMatch` / `MatchAny` hot path (pre-decode on
  construction).

### P1.5 — Filter-header hash linkage
**Size:** S (1 day).
**Depends:** P1.4.
**Blocks:** P1.6, P1.8, P1.9.

Pure function in `BRGCSFilter.c`:
```c
UInt256 BRGCSFilterHeader(const UInt256 *filterHash, const UInt256 *prev);
```
Returns `dSHA256(filterHash || prev)`. Genesis `prev` is 32 × 0x00.
The name matters: `filterHeader` ≠ `filterHash`. `cfheaders` messages
carry hashes; the client reconstructs headers.

**Acceptance:**
- Given a known `(blockHash, filterHash, prevHeader)` triple, output
  matches Bitcoin Core's `BlockFilter::ComputeHeader` computation.
- Test vector pulled from a live v26.2 node via `getblockfilter
  <hash>` and `getblockheader <hash>` + `getblockfilter <prevHash>`.

### P1.6 — BIP 157 message codec
**Size:** M (3–4 days).
**Depends:** P1.4.
**Blocks:** P1.7, P1.9, P1.10.

Parse and serialize the six P2P message types. Wire into the existing
message dispatch in `native/src/main/jni/digibytewallet-core/BRPeer.c`.

Exact wire layouts (from BIP 157 and Bitcoin Core
`src/net_processing.cpp`):

| Message        | Direction | Layout |
|----------------|-----------|--------|
| `getcfcheckpt` | client → peer | `FilterType(1)` + `StopHash(32)` |
| `cfcheckpt`    | peer → client | `FilterType(1)` + `StopHash(32)` + `FilterHeadersLen(CompactSize)` + `FilterHeaders(n×32)` |
| `getcfheaders` | client → peer | `FilterType(1)` + `StartHeight(u32 LE, 4)` + `StopHash(32)`, range must be < 2000 |
| `cfheaders`    | peer → client | `FilterType(1)` + `StopHash(32)` + `PreviousFilterHeader(32)` + `FilterHashesLen(CompactSize)` + `FilterHashes(n×32)`, n ≤ 2000 |
| `getcfilters`  | client → peer | `FilterType(1)` + `StartHeight(u32 LE, 4)` + `StopHash(32)`, range must be < 1000 |
| `cfilter`      | peer → client | `FilterType(1)` + `BlockHash(32)` + `NumFilterBytes(CompactSize)` + `FilterBytes(var)`, bytes bounded at 100 KB |

Client only ever uses `FilterType = 0x00` (basic).

**Acceptance:**
- Round-trip parse/serialize tests against known-good byte sequences
  from a live node.
- Range-validated on send (`getcfheaders` rejects range ≥ 2000 locally).
- Range-validated on receive (reject peer-sent `cfheaders` with > 2000
  hashes, `cfilter` with > 100 KB bytes, `cfcheckpt` with malformed
  CompactSize before any allocation).
- Wire into `BRPeer.c` message dispatch; add logging at info level for
  each message type.

### P1.7 — Peer service-bit gating
**Size:** S (2 days).
**Depends:** P1.6.
**Blocks:** P1.10.

Extend peer-selection scoring in `BRPeerManager.c` (the bloom
prioritization region around line 1853). Prefer peers advertising
`NODE_COMPACT_FILTERS` (0x40) over bare `NODE_BLOOM` (0x04) when
selecting outbound connections.

Add to the outbound send path: never send `getcfilters`, `getcfheaders`,
or `getcfcheckpt` to a peer whose service bits don't include 0x40.

**Acceptance:**
- With a mixed peer set, outbound connections preferentially fill with
  0x40 peers before 0x04-only peers.
- Logs show no filter-request messages sent to non-0x40 peers.
- `NODE_BLOOM` path remains operational for non-0x40 peers —
  bloom-mode fallback (P1.10) must still work.

### P1.8 — Generate filter-header checkpoint bootstrap array
**Size:** S (1 day).
**Depends:** P1.3 (cadence decision), P1.5, P1.2 (cross-verification
source).
**Blocks:** P1.9.

`scripts/gen-filter-checkpoints.sh` (committed) queries two
independent filter-serving nodes (digiscope.me + the community node
from P1.2), fetches filter headers at every 10,000-block boundary from
genesis to tip, and emits the array only if the two nodes agree at
every sampled height. Mismatch aborts the script with a loud error and
a diff — it does not ship a checkpoint unless cross-verified.

Output: `native/src/main/jni/digibytewallet-core/BRFilterHeaders.{c,h}`
with a C source array:
```c
typedef struct {
    uint32_t height;
    uint8_t  filterHeader[32];
} BRFilterCheckpoint;

extern const BRFilterCheckpoint BRMainNetFilterCheckpoints[];
extern const size_t BRMainNetFilterCheckpointsCount;
```

**Acceptance:**
- Script runs, queries two nodes, produces the array, prints per-height
  agreement.
- Array covers genesis through (current tip − 10,000), so a fresh
  install doesn't rely on a checkpoint at the tip.
- Generation logged with both source nodes' fingerprints and heights at
  time of generation, committed alongside the array.
- Array is **bootstrap hint only**: P1.9 implements the runtime
  cross-verification that makes this non-authoritative.

### P1.9 — Filter-header chain sync with peer-quorum validation
**Size:** L (1.5 weeks — the core sovereignty work).
**Depends:** P1.6, P1.7, P1.8.
**Blocks:** P1.10.

This is the design-center of Phase 1. The trust model depends on this
logic being right.

On sync start, when ≥ 2 connected peers advertise `NODE_COMPACT_FILTERS`:

1. **Quorum fetch.** Send `getcfcheckpt` with `StopHash` = current tip
   block hash to ≥ 2 peers in parallel. Each response is
   `FilterHeaders[N]` at heights `1000, 2000, …, ⌊tip/1000⌋ × 1000`.
2. **Agreement check.** Peers agree ⇒ accept as runtime ground truth.
3. **Disagreement resolution.** Peers disagree at some checkpoint
   height H:
   - Request `cfilter` for the first block where the peers' implied
     cfheaders diverge.
   - Locally compute `BRGCSFilterHeader(cfilter) chained forward`.
     This reveals which peer is honest.
   - Ban the dissenting peer. Log with reason.
   - If fewer than 2 honest peers remain, surface error state: "filter
     data inconsistent, refusing to sync" — do not fall through to one
     peer's word.
4. **Bootstrap-hint cross-check.** At each checkpoint height covered
   by the shipped `BRMainNetFilterCheckpoints[]` array: compare the
   runtime-verified value against the shipped hint. Mismatch ⇒ refuse
   to sync, log loudly, show error UI. This detects a malicious or
   stale shipped checkpoint at the cost of locking out unreachable-
   network users (accepted trade-off).
5. **Fill the gaps.** Request `cfheaders` in batches (≤ 2000) between
   each pair of verified checkpoints. Verify that each batch's computed
   final header matches the downstream checkpoint before accepting
   any of its hashes.
6. **Persist.** Filter headers stored alongside block headers, same
   persistence layer (SharedPrefs hex blob for now, matching existing
   pattern).

**Acceptance:**
- Fresh install syncs against an honest-peer-majority test harness.
- Test peer serving wrong `cfcheckpt`: banned within one verification
  round; sync proceeds with remaining honest peers.
- Test peer serving a subtle wrong `cfheaders` (one bit flipped in one
  hash): detected and banned on the batch-finalization check.
- Shipped checkpoint set with a deliberately-wrong value: sync refuses
  with the error UI, doesn't silently accept either side.
- Restart with persisted headers: no re-download; only new headers
  after last sync are fetched.

### P1.10 — Dual-mode sync dispatch
**Size:** M (1 week, most of it in the edge cases).
**Depends:** P1.7, P1.9.
**Blocks:** P1.11, P1.17.

New logic in `BRPeerManager.c`. On each (re)connect to sufficient
peers, evaluate: do ≥ `PEER_MAX_CONNECTIONS / 2` (currently 3 of 5)
advertise `NODE_COMPACT_FILTERS`?

- **Yes** → enter compact-filter mode. Run P1.9 for headers, then
  request filters for the user's wallet-birthday range, match against
  wallet addresses, request full blocks on match (P1.11). Do not call
  `BRPeerSendFilterload()`.
- **No** → fall through to the existing bloom path. Log the reason
  (peer count below threshold, no 0x40 peers known).

Mid-sync peer drop below threshold: finish the current operation,
then re-evaluate on next reconnect. Don't thrash modes inside a single
sync cycle.

**Acceptance:**
- All-0x40 peer set → no `filterload` message is ever sent (assert
  via logcat grep in P1.17 soak).
- All-0x04 peer set → bloom mode runs, current behavior unchanged.
- Mixed set where 0x40 ≥ threshold → compact mode runs, bloom peers
  are used only for block relay (no `filterload` sent to them either,
  since they're not the address-privacy channel).

### P1.11 — Filter match → block request → tx extraction
**Size:** M (1 week).
**Depends:** P1.10.
**Blocks:** P1.11a, P1.17.

On filter match for any wallet address:

1. Request the full block via `getdata MSG_BLOCK`. Not merkleblock —
   merkleblock is the bloom path's privacy concession and has no reason
   to exist in compact-filter mode.
2. Parse the block. Extract txs that pay or spend wallet addresses.
3. Register each tx via the existing `BRWalletRegisterTransaction`
   path. Do not modify wallet ledger logic.

**False-positive budget:** per BIP 158, false-positive rate per query
element ≈ 1/M = 1/784931. For a wallet with W addresses scanned across
N blocks in the birthday range, expected false-positive block fetches
≈ W × N / M. A 1000-address wallet scanning 2 M blocks of DGB history
yields ~2,600 false-positive full-block downloads — real bandwidth
cost, budget it. **Filter by wallet birthday** to limit N.

**Acceptance:**
- A send to a wallet address on a test network is picked up via the
  compact-filter path. No `filterload` ever sent.
- Tx appears in history with correct amount, block height, and
  confirmation count.
- Logged counters: filter-match-count, false-positive-block-fetches,
  real-match-block-fetches.

### P1.11a — Reorg handling for filter headers
**Size:** S (2 days).
**Depends:** P1.11.

On a block-header reorg detected through the existing block-sync path:

1. Identify the fork point (highest common ancestor block).
2. Invalidate all cached filter headers at heights > fork point.
3. Re-sync filter headers from the fork point forward using P1.9's
   quorum logic.
4. Re-evaluate filter matches for txs in the new chain segment; register
   any that are now newly-confirmed, remove any that were in the
   invalidated segment.

**Acceptance:**
- Simulated reorg in test harness: filter header cache correctly
  truncated to fork point, new segment re-synced, no stale filter
  headers remain.
- No redundant downloads: filter headers unchanged across the reorg
  (i.e., below the fork point) are not re-fetched.

### P1.12 — Extend dgb-bloom-seeder with service bits
**Size:** S (1 day).
**Depends:** P1.1, P1.2.

Backend change at `/opt/dgb-bloom-seeder` on digiscope.me:

- Parse service bits from every peer the seeder tracks.
- New endpoint: `GET /api/peers/v2` returns JSON with shape:
  ```json
  { "peers": [ { "addr": "1.2.3.4:12024", "services": 1089, "firstSeen": ..., "lastSeen": ... } ] }
  ```
  where `services` is the raw u64 services field, so the wallet can
  decode both 0x04 and 0x40 bits without the seeder having to
  pre-classify.
- Keep `/api/peers/bloom` working unchanged so older wallet versions
  don't break (v3.5.x users don't get auto-updated instantly).

**Acceptance:**
- Curl `/api/peers/v2` returns ≥ 10 peers, at least 1 with
  `services & 0x40 != 0` once P1.1 + P1.2 are done.
- Curl `/api/peers/bloom` returns the same list it always has.

### P1.13 — NativeBridge surface for filter sync
**Size:** S (1 day).
**Depends:** P1.10.

Add to `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`:
```kotlin
external fun getSyncMode(): Int                // 0 = idle, 1 = bloom, 2 = compact
external fun getFilterHeaderProgress(): Float  // 0.0–1.0
external fun getFilterScanProgress(): Float    // 0.0–1.0 within scan window
external fun getFilterMatchCount(): Int        // diagnostic: matches this session
external fun getFilterFalsePositiveCount(): Int // diagnostic
```

Wire to corresponding C functions. Diagnostic counters are essential
for P1.17's soak measurements.

### P1.14 — Consume v2 seeder endpoint
**Size:** S (1 day).
**Depends:** P1.12.

Update `SyncService.kt` peer-injection (current call at line ~524):
- Try `GET /api/peers/v2` first.
- If 404 (older seeder), fall through to `/api/peers/bloom`.
- Parse `services` and pass to native side so P1.7's selection logic
  can use it without re-probing.

**Acceptance:**
- On startup, logs show v2 endpoint used + peer count breakdown by
  service bits (N with 0x40, M with 0x04, etc.).
- With seeder down, cached response is used (existing behavior
  preserved).

### P1.15 — UI: sync mode banner + quorum failure surface + dev toggle
**Size:** S–M (2–3 days).
**Depends:** P1.13.

Three UI additions in the Compose wallet screen:

1. **Sync mode indicator.** Small badge next to the sync progress
   (compact / bloom / offline). Neutral copy on bloom fallback:
   "using bloom fallback — privacy reduced until more peers support
   compact filters."
2. **Quorum failure banner.** When P1.9 surfaces a "filter data from
   different peers doesn't match" state, show a prominent warning with
   a retry action. Users need to know something is genuinely wrong.
3. **Dev toggle.** In an advanced settings surface, offer "force sync
   mode: [auto / compact / bloom]" for testing. Invisible in production
   builds unless an env/preference flag is set.

### P1.16 — GCS filter unit tests
**Size:** bundled with P1.4.
**Depends:** P1.4.

BIP 158 appendix test vectors encoded into the C test harness:
- Block 987876 basic filter vector.
- Empty-filter case (block with only coinbase, all-`OP_RETURN`
  outputs).
- SipHash key endianness regression test (specifically: verify the
  first-16-bytes-of-raw-wire-hash rule is applied, not hex-string byte
  order).
- `NumFilterBytes` overflow rejection.

### P1.16a — Peer-quorum simulation test
**Size:** M (3 days).
**Depends:** P1.9.

A C-level test harness that simulates three peers:
1. Honest peer serving correct `cfheaders` and `cfcheckpt` from a
   known test chain.
2. Honest peer serving the same data (independent source, same
   answers).
3. Malicious peer serving `cfheaders` with one hash flipped at a
   random position.

Run P1.9's sync logic against this harness and verify:
- The malicious peer is identified and banned.
- The ban happens on the batch-finalization check (one round-trip of
  requesting the actual `cfilter` for the divergent block).
- Sync completes correctly from the honest peers after the ban.
- A variant where both "honest" peers serve the same wrong answer
  (collusion) is detected against the shipped bootstrap hint — sync
  refuses rather than silently trusting the majority.

### P1.17 — Mainnet soak test on Note 8 with measurement readout
**Size:** M (2 days).
**Depends:** P1.11, P1.14.

Canonical test device: Samsung SM-N950U (Galaxy Note 8, Android 9 API
28). Wipe + fresh install of the Phase 1 branch. Recover from a
pre-prepared seed that has a mix of pre-checkpoint and post-checkpoint
send/receive history.

Record:
- Total sync time (header sync + filter header sync + filter scan).
- Bytes downloaded: for filter headers, for filters, for full blocks
  on filter match.
- Number of `getcfheaders` roundtrips.
- False-positive block fetch count (from P1.13 diagnostics).
- Peak memory during sync.
- Battery draw over the full sync (before / after readings from
  settings).
- Count of `filterload` messages sent (must be zero if any peers have
  0x40).

Compare the same wallet synced via bloom fallback (forced via the
P1.15 dev toggle).

**Acceptance:**
- All historical sends and receives appear in history, balance matches
  pre-test expected value.
- Zero `filterload` messages in the compact-mode run.
- Readout committed to `docs/research/2026-XX-XX-phase1-soak.md`.

### P1.18 — ARCHITECTURE.md compact-filter data-flow section
**Size:** S (half day).
**Depends:** Phase 0 initial ARCHITECTURE.md, P1.11.

Add a "Compact Filter Data Layer" section to `docs/ARCHITECTURE.md`.
Diagram data flow:

```
peer cfheaders ─┐
peer cfcheckpt ─┼─ quorum check ─ verified filter-header chain
                │   (P1.9)           │
shipped         ┘                    │
bootstrap                            ▼
checkpoint                   filter download
(P1.8)                            │
                                  ▼
                           wallet-address match
                                  │
                          on match: getdata MSG_BLOCK
                                  │
                                  ▼
                           BRWalletRegisterTransaction
```

File:line citations into the new submodule code. Explain the trust
model (shipped checkpoint as hint vs. runtime quorum as authority).

### P1.19 — THREAT_MODEL.md update for compact-filter mitigation
**Size:** S (half day).
**Depends:** Phase 0 initial THREAT_MODEL.md, P1.11.

Update the following adversary rows in `docs/THREAT_MODEL.md`:

- **Peer**: previously could surveil wallet's tx set via bloom-filter
  fingerprint; now receives no wallet-specific state at all under
  compact-filter mode.
- **Malicious seeder**: previously could eclipse onto hostile peers
  that would then surveil via bloom; now, eclipse still dangerous but
  the surveillance channel is closed — the worst a hostile peer can do
  is serve wrong `cfheaders`, which P1.9's quorum logic detects.
- **Shipped-checkpoint compromise**: new adversary row, explaining
  that a malicious shipped checkpoint is detected at runtime by peer
  quorum, not silently trusted.

Residual risks remain documented explicitly (e.g., timing correlation
of block-request patterns, peer IP observation if Tor is off).

### P1.20 — BIP_COMPLIANCE.md flip 157/158 to Implemented
**Size:** trivial.
**Depends:** Phase 0 initial BIP_COMPLIANCE.md, P1.11.

Flip rows for BIP 157 and BIP 158 from "Planned" to "Implemented" with
file:line citations into the submodule.

---

## After Phase 1

Once Phase 1 lands:

- Retire the bloom path behind a config flag; default-off once 0x40
  peer population is healthy. Target: Phase 2 minor version.
- Remove `_injectPriorityPeer` calls that bias toward bloom peers
  specifically.
- Deprecate the `/api/peers/bloom` seeder endpoint; sunset after
  sufficient user base is on the v2 endpoint.
- Feed Phase 1 learnings into Phase 2 hardening (Keystore auth-binding,
  PIN rate-limit, Digi-ID isolation, Tor default-on).
