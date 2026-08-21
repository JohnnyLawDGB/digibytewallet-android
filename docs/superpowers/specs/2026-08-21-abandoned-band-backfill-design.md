# Abandoned-band backfill — design

**Date:** 2026-08-21
**Status:** Design, not implemented
**Problem:** the only way to clear a "history gap" is a full rebuild from chain — re-scanning
~24 million blocks to recover ~20 thousand.

## The question this answers

> If it knows it has a gap, and it knows where the gap is, why wouldn't it just download and
> scan those blocks again?

It should. Nothing prevents it. The current code doesn't, and the comments describing the
watermark as permanent describe *this implementation*, not a constraint of BIP157/158.

## Why it currently can't — a self-reinforcing loop

1. A band `[lo..hi]` is abandoned (`abandonedBelow = hi + 1`).
2. `BRCFScanLedgerLowestNeededHeight` clamps abandoned heights out of "needed":
   ```c
   if (l->abandonedBelow > lo) lo = l->abandonedBelow;   // hard floor
   ```
3. The prune floor is `min(cfNext, lowestNeeded) − CLEAR_MEM_CF_RETENTION_MARGIN`, so it
   rises above the band.
4. The block headers for `[lo..hi]` are pruned.
5. `getcfilters(type, startHeight, stopHash)` needs a **block hash** for the stop height. With
   the headers gone the stop hash cannot be resolved, so the range is now genuinely
   unrequestable — **retroactively justifying step 1.**

Abandoning creates the condition that makes it permanent. That is the whole trap, and it is
why "monotonic" felt like a safety property. It is not: it is a consequence of having no way
to put the headers back.

## What makes a fix possible

`BRPeerSendGetheaders(peer, locators[], count, hashStop)` takes a **locator**, so headers can
be requested starting from any height. There is no protocol obstacle to re-fetching
`[lo..hi]`. Headers are 80 bytes on the wire.

The two existing header-refetch paths are both all-or-nothing:

| path | scope | cost |
|---|---|---|
| `rebuildFromChainRescan` | floors to wallet birth | hours |
| node reconcile | not a header fetch at all — POSTs the address set to a backend | privacy |

Neither is "re-fetch the 20k headers you dropped". That is the missing capability.

## Design

A bounded, resumable operation: **BackfillBand**.

```
PLAN      read the band [lo..hi] from the ledger; split into chunks (see Sizing)
FETCH     for each chunk, getheaders from a locator at chunkLo through chunkHi
ADMIT     insert the returned headers into manager->blocks so prevBlock links are
          contiguous across the chunk and the stop hash resolves
UNCLAMP   lower abandonedBelow to chunkLo — ONLY now, and only for the chunk whose
          headers are demonstrably resident
SCAN      the existing CF machinery re-requests filters for the chunk; no new scan code
RETIRE    when the last chunk completes, the band is gone; CfAbandonmentStore clears
```

**The ordering is the design.** Headers must be resident *before* `abandonedBelow` drops,
never after. Lowering it first is precisely the mistake I nearly shipped: it would send the
scan at heights whose stop hashes cannot resolve — the wedge the floor exists to prevent.
That is also why this cannot be "just make the watermark decrease".

### Sizing

- `CLEAR_MEM_BLOCKS_COUNT_TRIGGER` is **36,000** resident blocks; resident cost is ~224 B each
  (`CLEAR_MEM_PRUNE_STRIDE` 2048 ≈ 459 KB).
- A 20,273-height band is ~4.5 MB and ~20k blocks — enough, on top of a normal working set, to
  cross the trigger and have the pruner delete the very headers just fetched.
- So chunk at **2048** (`CLEAR_MEM_PRUNE_STRIDE`), which is the granularity the pruner already
  reasons in. Ten passes for a 20k band, each far below the trigger.

### Pinning during a chunk

While a chunk is in flight its headers must be exempt from pruning. `LowestNeededHeight`
already drives the prune floor, and step UNCLAMP makes the chunk "needed" — so the existing
mechanism protects it for free, provided UNCLAMP happens before the scan and the chunk is
small enough not to trip the count trigger on its own.

### Failure and resumption

- A chunk that cannot be fetched (no peer serves that depth) leaves `abandonedBelow` where it
  was for that chunk. The band shrinks by what succeeded; the rest stays surfaced. **Partial
  progress is kept** — the opposite of today's all-or-nothing.
- Process death mid-backfill is safe: the ledger is the state, and it only records completed
  chunks.
- The band must never *grow* as a result of a failed backfill.

## Scope boundary

- Does **not** change what causes a band to be abandoned. That was fixed separately in
  v4.0.43 (corroboration gate).
- Does **not** touch the second abandonment cause seen in the field
  (`resume frontier below saved block window`) — this makes that recoverable too, but does not
  address why it fires.
- Does **not** make `abandonedBelow` freely mutable. It gains exactly one lowering path, gated
  on demonstrated header residency.

## Testing

The trap to avoid is a KAT that asserts the current behaviour — the neverbrick KAT did exactly
that and cost a shipped regression. Red arms must distinguish:

1. **Ordering is load-bearing** — a red arm that unclamps *before* admitting headers must fail
   with unresolvable stop hashes. This is the one that matters.
2. **Chunking is load-bearing** — a red arm using a single 20k chunk must trip the prune
   trigger and lose headers mid-flight.
3. **Partial progress** — a chunk failure must shrink the band, not abandon the operation, and
   must not grow it.
4. **The band actually retires** — `abandonedBelow` reaches `lo` and `CfAbandonmentStore`
   clears, on a wallet that started with a surfaced gap.

## Open question

Whether a peer will serve `getheaders` at that depth reliably from the CF oracle fleet. The
fleet is filter-capable by construction, but depth-serving has not been measured. If it is
unreliable the design still holds — the band shrinks by whatever can be fetched, which is
strictly better than today.
