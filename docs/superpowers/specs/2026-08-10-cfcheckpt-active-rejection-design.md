# cfcheckpt Active Rejection — Design

**Date:** 2026-08-10
**Status:** Approved design (brainstorm complete); implementation plan to follow
**Phase:** Phase 1 (sovereign data layer) remainder — the client half of "never stranded / can't be lied to"
**Supersedes-in-part:** the unshipped `FIX 2` sketch in `docs/superpowers/specs/2026-07-19-cfheaders-continuity-wedge.md`

## Goal

Graduate the compact-filter-header checkpoint cross-check from **observe-and-log**
(shipped v3.10.25) to **active rejection**: a peer that serves a filter-header chain
diverging from a pinned checkpoint is rejected and banned, and the wallet can never be
driven off a checkpoint-confirmed chain by a lying peer — **without ever hard-bricking**
a wallet on a bad table entry or an eclipse.

This earns the Phase 1 client claim: *the filter chain cannot lie to you below the last
checkpoint.* (Tip trust is a separate concern — see Scope boundary.)

## Background — current state (surveyed 2026-08-10)

- **Observe-and-log cross-check (v3.10.25):** `_peerRelayedCFHeaders` (BRPeerManager.c
  ~3474-3500), *after* `BRCompactFilterChainAppend` succeeds (`ok==1`), compares the
  wallet's computed filter header at each pinned checkpoint height inside the batch range
  to `BRMainNetCFCheckpoints[]`. On mismatch it **only logs** (`cf-checkpoint: … MISMATCH
  (observe)`); persistence runs unconditionally. Mainnet-only (`standardPort` gate).
- **The checkpoint table:** `BRCompactFilterCheckpoints.h` — 478 entries at 50,000-block
  spacing (h50000 → ~23.85M), generated offline by `scripts/gen_cf_checkpoints.sh` from
  the digiscope.me operator node (`getblockfilter`, byte-reversed to internal order).
  Single-operator-attested (same operator as the seeder) — a known provenance residual.
- **`getcfcheckpt` wire message:** fully implemented (BRPeer.c) but **dead code** — never
  driven by the sync loop. Out of scope here.
- **Filter-header trust today:** TOFU anchor at wallet birth + continuity-only append +
  a K=2 "quorum" re-anchor (`cfDisagreedPeers[]`, `CF_CONTINUITY_REANCHOR_K=2`) with a
  single-peer escape hatch (`CF_SINGLE_PEER_REANCHOR_ROUNDS`) that can **TOFU-accept a
  liar**, budget-capped at `CF_CONTINUITY_REANCHOR_MAX=3`/session. The checkpoint table is
  **not** part of this decision — it only observes.
- **Levers that already exist:** `_BRPeerManagerPeerMisbehavin` (disconnect+evict; already
  used for cfilter-vs-chain verify failures), `_BRPeerManagerReanchorAtFloorLocked`,
  refuse-to-advance (`cfHeadersRequestedThrough = 0`), the C2/I3 abandoned-band /
  recover-me surfacing. **No filter-chain truncate/rollback API exists.**
- **Tests:** the survey found **zero** KAT/unit coverage of the checkpoint cross-check
  (neither MATCH nor MISMATCH). This design adds it.
- **Soak evidence:** the observe cross-check is clean in the field — this session's Note 8
  deep restore crossed ~20 checkpoints (22.65M–23.65M), **all MATCH, zero mismatch**,
  proving byte-order and wiring end-to-end.

## Decisions locked in brainstorm

| Decision | Choice |
|---|---|
| Table-trust posture | Soak-trusted, but the design must **never hard-brick** — a mismatch degrades to a loud recoverable state, never a crash/silent wedge |
| Scope | Security core (enforce + checkpoint-veto + never-brick recovery) **+** quorum-reliability fixes (disagreers-must-agree, strict-majority+floor≥3). `getcfcheckpt` **deferred** |
| Correction mechanism | **A — pre-commit validation**: validate a checkpoint-crossing batch *before* append; reject+ban on mismatch so a bad header is never committed (no rollback API needed) |

## Design

### Piece 1 — Pre-commit checkpoint enforcement (the core)

In `_peerRelayedCFHeaders`, **before** `BRCompactFilterChainAppend` commits a batch:

1. Determine the pinned checkpoints whose height falls in the batch range `[batchStart,
   candidateTip]` (new lookup `BRCFCheckpointsInRange(a, b)` over the table).
2. For each such checkpoint height H, compute the **candidate** filter header at H from
   the batch bytes + the chain's current tip header, *without* committing — a new pure
   helper `BRCompactFilterChainCandidateHeaderAt(chain, batch, targetHeight)` factored out
   of the existing append fold (identical computation, no state mutation).
3. Compare each candidate to the pin (`UInt256Eq`):
   - **All match (or no checkpoint in range)** → proceed with `Append` exactly as today
     (continuity + quorum still apply to non-checkpoint batches).
   - **Any mismatch** → **REJECT**: do not `Append`, do not persist, do not advance
     `cfHeadersRequestedThrough`; call `_BRPeerManagerPeerMisbehavin(manager, peer)` (a
     checkpoint mismatch is cryptographic proof the peer served a divergent chain — a safe
     ban even on a thin fleet, unlike a mere continuity break); re-request the batch from
     another filter peer.

A bad header is never committed; a lying peer cannot advance a fake chain across a
checkpoint.

### Piece 2 — Checkpoint-vetoes-reanchor (closes the single-peer-liar hole)

Before **any** re-anchor (the quorum-disagreement path *and* the single-peer escape
hatch), consult the checkpoints: compute the highest pinned checkpoint at/below the
contested height and check whether OUR current chain matches it
(`BRCFHighestCheckpointAtOrBelow(H)` + compare to our computed header).

- **Our chain matches the pin** → our chain is checkpoint-confirmed ground truth →
  **VETO the re-anchor** (return without re-anchoring; the peer disagreement is the peer's
  problem, not ours). The single-peer escape hatch is gated behind this veto.
- **Our chain does not reach a checkpoint** (contested height above the highest
  checkpoint, i.e., recent/tip region) → fall through to the quorum path (as amended by
  Piece 4). Checkpoints don't cover the tip.

A lone liar can no longer force a re-anchor off a checkpoint-confirmed chain.

### Piece 3 — Never-brick recovery (terminal-advance + surface)

If enforcement/veto leaves the wallet unable to obtain a checkpoint-consistent chain — an
eclipse where every connected peer serves a mismatching chain, or the re-anchor budget
exhausts against persistent divergence — the wallet must degrade safely:

1. **Refuse to advance** the filter-header chain past the last checkpoint-confirmed height
   (do not accept any batch that fails Piece 1).
2. **Park the fetch cursor at the nearest trusted pinned checkpoint at/below tip** — never
   a peer-supplied value (the "terminal advance" from the 2026-07-19 spec). The wallet
   sits at a *verified* point, not a peer's claim.
3. **Surface a loud recoverable banner** by reusing the existing C2/I3 recover-me / abandoned-
   band surfacing (WalletViewModel/SyncService), messaged as *"couldn't verify the filter
   chain — pair your own node or rescan."* Funds already scanned below the parked point are
   safe and displayed.

Invariant: **never crash, never silently wedge.** A bad table entry or an eclipse produces
a surfaced, user-recoverable state, not a brick.

### Piece 4 — Quorum-reliability fixes (fold into the re-anchor path)

The current K=2 continuity quorum both false-fires on honest reorgs and ignores the
checkpoint. Alongside Pieces 1–3:

- **Disagreers-must-agree** (`_cfDisagreersShareSamePrev`): a peer counts toward a
  re-anchor only if it agrees with the *other* disagreers (shares their `prevFilterHeader`),
  not merely disagrees with our tip. Prevents two independently-wrong/transient peers from
  triggering a false re-anchor.
- **Strict-majority + floor ≥3:** replace `CF_CONTINUITY_REANCHOR_K=2` with a strict
  majority of connected filter peers AND an absolute floor of ≥3 distinct agreeing
  disagreers, so a 1–2-peer fleet cannot false-fire a re-anchor on an honest reorg.

## Scope boundary (do not overstate)

- **Mainnet-only** — `standardPort` gate, as the observe check today. Testnet26 has no
  checkpoint table; behavior there is unchanged.
- **Protects the historical region up to the highest checkpoint (~23.85M), NOT the tip.**
  Blocks above the last checkpoint remain TOFU + continuity + (amended) quorum. Recent-block
  / tip trust is the **oracle-bootstrap / own-node tip-anchor** work (Phase 1 remainder),
  explicitly out of scope. The claim this earns is bounded: *cannot lie below the last
  checkpoint.*
- **Table provenance unchanged.** The table is single-operator-generated; this work trusts
  it (soak-validated) but does not solve provenance — multi-operator attestation is the
  oracle-bootstrap concern.
- **`getcfcheckpt` not driven.** The dead wire path stays dead; finer-grained peer-supplied
  anchors are Sybil-bait without a pin and are a separate (R5) effort.

## Components / files

| File | Change |
|---|---|
| `BRCompactFilterChain.c/.h` | New pure helper `BRCompactFilterChainCandidateHeaderAt(chain, batch, targetHeight)` (candidate-header fold, no mutation); no truncate API |
| `BRCompactFilterCheckpoints.h` | Add lookups: `BRCFCheckpointsInRange(a,b)`, `BRCFHighestCheckpointAtOrBelow(h)` |
| `BRPeerManager.c` | Pre-commit enforce in `_peerRelayedCFHeaders`; checkpoint-veto + quorum fixes on the re-anchor path; terminal-advance-to-checkpoint + surface on exhaustion |
| `BRPeerManager.h` | Quorum constants (majority rule, `CF_CONTINUITY_REANCHOR_FLOOR=3`); retire/repurpose `CF_CONTINUITY_REANCHOR_K` |
| app (Kotlin) | Reuse existing recover-me surfacing; add the filter-chain-rejected message string |

## Error handling / failure modes (the never-brick spine)

- Checkpoint-crossing batch mismatches → reject + ban + re-request; never commit.
- Every peer mismatches / budget exhausts → refuse-advance, park at nearest trusted
  checkpoint, surface recoverable banner. **No crash, no silent wedge.**
- Our chain matches a checkpoint → veto any re-anchor (ground truth).
- Honest reorg below a checkpoint is impossible (checkpoints are deep-confirmed); above the
  highest checkpoint, the amended quorum (disagreers-agree + majority+floor≥3) prevents
  false re-anchors while still allowing a genuine majority re-anchor.

## Testing (new — none exists today)

Red-before-green host KATs (native), each failing on the pre-fix/observe code by its
intended mechanism:

1. **Enforce:** a batch crossing a checkpoint with a mismatching header → rejected, chain
   unchanged, peer `misbehavin`'d; a matching batch → appended. (Core security gate.)
2. **Checkpoint-veto:** a lone peer (and a K-quorum) attempting a re-anchor away from our
   checkpoint-matching chain → vetoed, chain unchanged.
3. **Never-brick:** all peers serve mismatching chains → wallet refuses to advance, parks at
   the last trusted checkpoint, surfaces the recoverable state, and does **not** crash or
   wedge. (The load-bearing safety test.)
4. **Quorum-reliability:** an honest-reorg transient with 2 non-agreeing disagreers → **no**
   false re-anchor (floor≥3 / disagreers-must-agree); a genuine majority-agreeing re-anchor
   above the checkpoint region → still succeeds.
5. **Candidate-header correctness:** `BRCompactFilterChainCandidateHeaderAt` at a mid-batch
   checkpoint height byte-for-byte equals the value the committed chain would compute, and
   matches the pinned table byte-order (the observe soak proved the table order; the
   pre-commit computation must match it).

Plus **on-device verification:** the observe telemetry already shows clean MATCHes; under
enforce the same crossings must MATCH with **zero false rejections** across a real
deep-restore on the Note 8 before merge.

## Out of scope (follow-ups)

- Tip anchor (recent-block trust) — oracle-bootstrap / own-node tip pin (Phase 1 remainder).
- Multi-operator checkpoint attestation (table provenance) — oracle-bootstrap.
- Driving `getcfcheckpt` for finer anchors / deep-birth crawl (R5).

## References
- `docs/superpowers/specs/2026-07-19-cfheaders-continuity-wedge.md` (FIX 2 sketch)
- `docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md` (residual framing)
- `docs/THREAT_MODEL.md` (filter-header-source trust residual)
- Memory: neutrino CF review (R1/R2), oracle-bootstrap, CF fleet reliability own-node track
