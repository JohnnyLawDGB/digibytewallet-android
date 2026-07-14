# Pair Your Own Node — Wallet Guide

**Who this is for:** a node operator (or a user handing a QR code to a
friend) who wants the DigiByte Wallet for Android to sync from a
specific node instead of — or in addition to — the public
filter-serving fleet. Two minutes if the node is already configured.

This is the wallet-side companion to
[`enable-compact-filters.md`](enable-compact-filters.md). Do that guide
first if your node doesn't serve compact filters yet.

## Prerequisite: the node must serve compact filters

The node needs:

```
peerblockfilters=1
```

(alongside `blockfilterindex=basic`, with the index finished building —
see `enable-compact-filters.md`). If this isn't set, pairing still
*succeeds* — the wallet will connect to the node — but the health
readout shows **⚠ Not reachable / not serving filters** instead of **✓
serving**, and the node won't actually carry the sync. Verification is
warn-but-allow, not a hard block, since nodes go down or are mid-index
transiently.

## Generate a pairing QR code (operator one-liner)

```bash
qrencode -o node.png "dgbnode://$HOST:$PORT?net=mainnet&label=$(hostname)"
```

- `$HOST` — the node's reachable IP or hostname (as seen from the
  phone: LAN IP, VPN address, or public IP/DNS name).
- `$PORT` — the node's P2P port. Mainnet is `12024`, testnet26 is
  `12033` (same ports `enable-compact-filters.md` uses — no firewall
  change beyond what that guide already covers).
- `net=` — `mainnet` or `testnet`. Any other value (or omitting it) is
  ignored; if present and it disagrees with the network the wallet is
  running on, the confirm screen shows an inline warning before you
  pair rather than silently pairing to the wrong chain.
- `label=` — optional display name (letters/digits/`-_.`/whitespace
  only, capped at 32 characters after sanitizing); shown in the wallet
  next to the health chip.
- Print `node.png`, or display it on a screen, for the wallet to scan.

A raw `host:port` (no `dgbnode://` prefix) also works if typed manually
in the wallet — it just skips the `net`/`label` metadata.

## Pair the wallet

**Settings → Network Info → Own node → Scan QR** (manual `host:port`
entry is still there too). The wallet parses the URI, persists it, and
applies it immediately via a reconnect — **no app restart required**.

Health shows on the Network Info screen and as a chip on the main
screen:

| Health | Meaning |
|---|---|
| ✓ serving | Pinned, connected, and has answered `getcfheaders`/`getcfilters` — normal, sync is (or can be) carried by this node. |
| ⚠ not reachable / not serving filters | The single ⚠ state the app shows for *either* cause — the wallet can't distinguish them, so check both: (a) the node isn't reachable (host/port, firewall, or the node process is down), **or** (b) it's reachable but hasn't produced filter data (almost always a missing `peerblockfilters=1`, or an index still building). |

If a pinned node goes dark **after** pairing, the wallet shows a loud
banner on the main screen (it does not fail silently), and re-dials the
node automatically once it's reachable again.

## Additive (default) vs. exclusive

By default, pairing is **additive**: your node is pinned and
prioritized (reserved dial slot, never dropped by churn eviction), but
the wallet keeps dialing public compact-filter peers too, as a backup.

An opt-in **"Only my node" (exclusive)** switch is available for the
strongest privacy posture short of running your own JSON-RPC backend —
in exclusive mode the wallet talks to your node only; no public peer is
ever dialed.

**Caveat — read before enabling exclusive:** if your node goes offline
while exclusive mode is on, **the wallet has no other peers** until you
either bring the node back or tap **"Use public peers"** on the
dark-node banner. That escape hatch flips the wallet to additive for
the current session (public sync resumes immediately) without
discarding the pairing — your node stays pinned and is re-dialed
automatically once it answers again. Toggling exclusive back off is a
deliberate action in Settings if you want it to stick across sessions.

## What this does not do (yet)

- **No Tor onion pairing.** The `dgbnode://` URI reserves room for an
  onion host, but the parser rejects one today — pairing is
  **clearnet host:port only**. Onion support is planned but depends on
  the Tor transport's clearnet-fallback work landing first.
- **One pinned node.** There's no multi-node failover — you pair a
  single node at a time.
- **Verification proves *serving*, not *honest*.** A paired node is
  still just a compact-filter peer subject to the same filter-header
  trust model as any other peer (continuity/TOFU today). Pairing does
  not add cryptographic trust in the filter chain — that's a separate,
  still-open roadmap item (`cfcheckpt` checkpoint enforcement).

## See also

- [`enable-compact-filters.md`](enable-compact-filters.md) — turning on
  `peerblockfilters=1` on the node itself, with troubleshooting.
- `ROADMAP.md`, Phase 1 — this pairing flow is the "own-node track"
  remainder that shipped; oracle-bootstrap peer diversity and
  `cfcheckpt` enforcement are the Phase 1 items still open.
