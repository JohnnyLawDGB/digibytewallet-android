# Block-header chain resume + honest sync progress (+ Tor fix bundle)

**Date:** 2026-06-14
**Branch:** phase1-modernization
**Status:** approved approach → implementation

## Problem

On every cold launch the **block-header chain** re-downloads from the static
birth checkpoint (~23,000,000) up to the real tip (~23,669,600) — ~670k headers,
minutes long (slower over Tor). During that window:

- `getLastBlockHeight()` reports the low, re-syncing height, so recent
  transactions show **unconfirmed** (confs = localHeight − txHeight).
- The UI shows **"Connected/Complete" with no progress bar** — `hasReachedSynced`
  latched true and the activity list's `maxTxHeight` floor made the displayed
  height read as the tip, masking the catch-up. The wallet is **silently behind**.

This is the true "never at the tip / confirmations regress" root cause. v3.7.4
(monotonic saved-blocks guard + checkpoint refresh) protected the disk tip and
the compact-filter anchor, and the `maxTxHeight` floor *masked* the block-chain
re-sync — but did not fix it.

### Root cause (to confirm by instrumentation before fixing)

The wallet persists only `SAVE_BLOCK_COUNT = 300` headers, so there is a ~670k
gap between the static checkpoint and the saved window. On connect,
`BRPeerManager` resets `lastBlock` to the most recent checkpoint older than the
wallet birth (`BRPeerManager.c:2511-2520`, taken because `startSyncFrom == NULL`)
and re-walks the whole gap. The compact-filter sync is unaffected because it
anchors on `savedTip` (`getSavedBlocksTip`), which is why filters resume high
while the header chain restarts low.

**Confirmation step (do first):** log `lastBlock->height` right after
`loadSavedBlocks`, and again inside the connect path, on one instrumented build.
Expect: high after load, reset to the checkpoint on connect.

## Design

### 1. Block-header chain resumes at the saved tip (core, C)
Set the peer manager's `startSyncFrom` to the **highest saved block** so the
connect path anchors there (`lastBlock = startSyncFrom`) instead of the birth
checkpoint. Cold launches then resume at ~the tip; only the last few blocks are
fetched. Safe because `has_synced` guarantees the skipped range was already
scanned — identical rationale to the existing BIP158 filter re-anchor.

Mechanism: pass the saved tip as `startSyncFrom` at manager creation (or an
equivalent native setter applied post-`loadSavedBlocks`, pre-`startSync`). Gate
on `has_synced` so a never-synced wallet still scans from its birth.

### 2. Honest sync progress (UI)
Don't let `hasReachedSynced` mask an active catch-up: when block height is
materially behind the network tip, surface catching-up progress instead of
Complete. With #1 the window shrinks to near-zero; this is the safety net so the
wallet never silently sits behind again. Keep the existing "honest progress"
height handling (no clamping up to est).

### 3. Tor fix (already implemented + device-verified)
- `SafeSocks=false` in `TorManager` — Tor was rejecting the C core's raw-IP
  SOCKS5 CONNECTs; flipping it lets peer dials route. (device-confirmed:
  `SOCKS5 tunnel established`, 0 direct leak.)
- On Tor-activate, `stopSync()+startSync()` (disconnect + re-dial, chain
  preserved) instead of `forceReconnect()` (which re-floored the chain).

## Rejected alternatives
- Save the full contiguous header chain (~54 MB) — too large for prefs.
- Only refresh the static checkpoint — helps new wallets (done in v3.7.4) but an
  existing April-born wallet still re-syncs from its birth checkpoint.

## Verification
- Instrument → confirm the `lastBlock` reset (above).
- After #1: cold launch resumes at saved tip (`heights: last≈tip` immediately,
  no run of `skipping regressive write`), confirmations stay correct.
- After #2: force a behind state → UI shows catch-up progress, not Complete.
- Bundle: re-verify Tor routing + no-leak still hold.
- Ship as v3.7.5 (submodule pin bump for the C change).
