# Own-Node First-Class Pairing — Design Spec

**Date:** 2026-07-14
**Status:** Design — approved in-session, pending written review → implementation plan
**Type:** Feature design (Sequence 1.1 + 1.2 of `WORKFLOW-wallet.md`)
**Phase:** ROADMAP Phase 1 remainder — "Never stranded" (own-node track, Model C)
**Authoritative sequence doc:** `docs/superpowers/specs/2026-07-11-cf-fleet-reliability-own-node-track.md`
**Builds on (context):** `docs/superpowers/plans/2026-07-08-own-node-cf-peer-and-mainnet-cf-gate.md` (the v3.10.1 primitive; this spec closes three of its explicit "out of scope" follow-ups)

---

## 1. Problem

v3.10.1 shipped the own-node **primitive**: `Settings → Network Info → "Use my own node"` toggle + a `host:port` text field. On each sync start, `SyncService.injectCustomNode()` resolves the host (off the native lock) and injects it as a *priority* compact-filter peer (services `0x41`).

Three gaps keep it from being the "cannot be stranded" pairing experience the roadmap promises:

1. **No verification.** The node is injected optimistically. If it isn't actually reachable or doesn't actually serve `NODE_COMPACT_FILTERS`, the user gets no signal — it silently no-ops while public peers carry the sync (or, worse, the wallet strands).
2. **Not pinned.** The injected node is only *high-priority*, not protected: churn eviction can drop it like any peer, and native `startSync` re-prepends `digiscope.me` while `_prependSavedPeerAddr` stamps `time(NULL)` into a non-stable qsort — so the node can be reordered past the dial cutoff and not dialed at all that launch (7/8 plan, "out of scope" line 655).
3. **No health surfacing.** Only the buried global peer count exists; there's no "your node is serving filters" readout, and nothing on the main screen (7/8 plan, "out of scope" line 656).

There is also no fast, discoverable entry point (QR pairing) and no way to run **exclusively** through your own node — the most sovereign posture short of Model A RPC.

## 2. Goals / Non-goals

**Goals (this PR — Seq 1.1 + 1.2):**
- QR-scan pairing (+ manual entry retained) that pre-fills, verifies, and pins a user's node.
- Native **verification**: is *this* peer connected **and** serving compact filters?
- Native **pinning**: the paired node is never churn-evicted and is always dialed (reserved slot).
- **Additive** posture by default (own node prioritized, public CF fleet stays as backup) with an opt-in **exclusive** ("only my node") toggle.
- **Health** on the main screen (paired ✓ / dark ⚠) + a loud banner if a pinned node goes dark.
- **Immediate-apply**: pairing takes effect via a deliberate, tested reconnect — no forced app restart.

**Non-goals (deferred, named in §10):**
- Tor **onion** pairing (couples to the Tor transport + its no-clearnet-fallback gap → lands with Seq 2.5).
- **Oracle-bootstrap** peer diversity (Seq 1.3–1.4; gated on the cross-repo operator prerequisite).
- **`cfcheckpt` enforcement** (Seq 1.5; separate trust-hardening item).
- **Model A** (wallet↔node JSON-RPC / `UtxoSource` seam) — a later Model-C tier.
- Bloom-code excision / `X.0.0` — unchanged trigger, unaffected here.

## 3. Locked decisions (in-session)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| QR payload | **Custom `dgbnode://` URI + operator helper** | Carries `net` (mismatch warning) + `label`; manual entry stays as fallback |
| Address scope | **Clearnet host:port only** (IPv4 literal / A-record hostname) | Onion needs Tor routing (fragile today); deferred to Seq 2.5 |
| Peer posture | **Additive default + exclusive toggle** | Resilient by default; exclusive available for max sovereignty |
| Verify-fail | **Warn but allow** | Nodes go down transiently; never hard-block; retry on reconnect |
| Apply timing | **Immediate-apply via controlled reconnect** | First-class UX + enables pairing-time verify; risk mitigated by a single tested apply path |

## 4. Architecture overview

```
QR scan / manual entry
   │  dgbnode://host:port?net=&label=   OR   raw host:port
   ▼
OwnNodeUri.parse  ──►  CustomNode(host,port) + label + net   ──► CustomNodePrefs (persist, network-suffixed)
   │                                                                 │
   │  net mismatch? → warn                                           ▼
   ▼                                                        SyncService.applyOwnNodeNow()
pairing UI                                                   = stopSync? no → forceReconnect path that RE-RUNS
   ▲                                                            injectCustomNode() + pin
   │  poll                                                          │
OwnNodeHealth (ip,port) ◄──────── NativeBridge.compactFilterPeerStatus(ip,port) ◄── g_peerManager
   │                                    {UNKNOWN, CONNECTING, CONNECTED_NOT_SERVING, SERVING}
   ▼
Network Info readout  +  main-screen health chip / dark banner
```

Two native additions on `g_peerManager` (both behind the existing `PEER_GUARD`), mirroring the existing `BRPeerPenalty.h` pattern:
- a **CF-peer status accessor** (read-only), and
- a **pinned-peer** mechanism (reserved dial slot + eviction exemption + optional exclusive dial suppression).

Everything else is Kotlin/Compose on top of the shipped `CustomNode` / `CustomNodePrefs` / `injectCustomNode()` spine.

## 5. Component design

### 5.1 QR payload + parser (Kotlin, `core`)

**Format:** `dgbnode://<host>:<port>?net=<mainnet|testnet>&label=<url-encoded>`
- `port` optional (defaults per network); `net` optional (absence = no mismatch check); `label` optional (display name, ≤ 32 chars after decode, sanitized to printable).
- New pure parser `OwnNodeUri.parse(raw): OwnNodeUri?` in `core/…/settings/`. It recognizes the `dgbnode://` scheme, extracts host/port/net/label, and delegates host:port validation to the **existing** `CustomNode.parse` (IPv4/hostname, no IPv6, no scheme-in-host). A raw string with no `dgbnode://` scheme falls through to `CustomNode.parse` unchanged (manual field behavior preserved).
- **Untrusted input:** the QR is attacker-controllable. The parser must not crash on arbitrary bytes, must cap lengths, and must reject anything `CustomNode.parse` rejects. It returns `null` (not throw) on any malformed input.

**Mismatch handling:** if `net` is present and disagrees with the wallet's active network, the UI shows a blocking-confirm ("This QR is for testnet; you're on mainnet — pair anyway?") but still allows override (consistent with warn-but-allow).

**Operator helper (docs, not app code):** a documented one-liner
`qrencode -o node.png "dgbnode://$HOST:$PORT?net=mainnet&label=$(hostname)"`
plus a short "pair your node" section for the wallet docs / VPS. No backend dependency.

### 5.2 Native — CF-peer status accessor

`int BRPeerManagerCompactFilterPeerStatus(BRPeerManager *manager, uint32_t ip, uint16_t port)`
returns one of:
- `0 UNKNOWN` — no such peer in the pool
- `1 CONNECTING` — present, socket not yet established / handshaking
- `2 CONNECTED_NOT_SERVING` — connected (`status==Connected`, socket>0) but has **not** answered our `getcfheaders` (no compact-filter data received from it)
- `3 SERVING` — connected **and** has delivered `cfheaders`/`cfilter` (it is a working filter peer)

Determination reuses whatever per-peer compact-filter-response signal the manager already tracks for the filter-first dial logic (the implementer confirms the exact field; if none is per-peer, add a `lastCfResponseTime`/flag set in the `cfheaders`/`cfilter` handlers — small, lock-held). JNI: `NativeBridge.compactFilterPeerStatus(ip: String, port: Int): Int`. Read-only, `PEER_GUARD`-held, O(peers). **Host KAT** feeds a synthetic manager/peer table and asserts the four states.

### 5.3 Native — pinned peer + exclusive dial

Add to `BRPeerManager` (mirroring the `penaltyAddr/Port/Until[]` pattern): a single `pinnedAddr` / `pinnedPort` (0 = none) and a `pinnedExclusive` flag.
- **Set/clear** via new JNI: `NativeBridge.setPinnedPeer(ip, port, exclusive)` / `clearPinnedPeer()`. Kotlin resolves the hostname to IPv4 first (same discipline as `injectCustomNode`).
- **Eviction exemption:** the churn/dead-socket eviction and penalty logic skip the pinned peer (it is never the one dropped to make room; a genuinely dead pinned socket still gets reaped by the existing dead-socket path, then re-dialed — see dark-node handling §7).
- **Dial guarantee:** the dial loop always dials the pinned peer first (reserved slot 0), independent of the `time(NULL)`/qsort ordering that today can bury it. This closes the 7/8 "not guaranteed dial slot" gap.
- **Exclusive mode:** when `pinnedExclusive`, the peer-fill logic does **not** dial public CF peers and native `startSync` suppresses the `digiscope.me` prepend — the wallet talks to the pinned node only. Additive mode leaves public dialing exactly as today.
- **Host KAT:** assert (a) pinned peer selected into dial slot 0 regardless of timestamps, (b) pinned peer exempt from eviction selection, (c) exclusive suppresses public candidates, (d) additive does not.

### 5.4 Kotlin — pairing + apply + health

- **`CustomNodePrefs`** gains `label` and `exclusive` (network-suffixed keys), alongside existing `enabled`/`hostPort`.
- **`SyncService.injectCustomNode()`** is extended to also call `setPinnedPeer(ip, port, exclusive)` after the existing `injectPeerByIp(ip, port, 0x41)`; on toggle-off it calls `clearPinnedPeer()`. The CF-only forcing + bloom-watchdog suppression under the toggle are **unchanged** (preserved sovereignty invariant).
- **Immediate-apply** — `SettingsViewModel.applyOwnNodeNow()` triggers the existing `forceReconnect` path **and** ensures `injectCustomNode()` runs on that path (today injection is in a latched setup coroutine a live reconnect skips — the fix is to call `injectCustomNode()` from the reconnect path too, guarded so it's idempotent). This is the one lifecycle-sensitive change; it goes through the *existing, already-exercised* `forceReconnect` (pull-to-refresh uses it), not new manager surgery.
- **Health polling** — a small `OwnNodeHealthMonitor` (or an addition to `SettingsViewModel`/the sync status source) polls `compactFilterPeerStatus(ip,port)` every few seconds while relevant, exposing a `StateFlow<OwnNodeHealth>` = `{ PAIRED_SERVING, PAIRED_CONNECTING, PAIRED_DARK(reachable-not-serving | unreachable), UNPAIRED }`.

### 5.5 UI

- **Network Info "Own node" section (upgraded):** the toggle + host:port field stay; add a **"Scan QR"** button (reuses the existing ZXing/CameraX scanner), a **verification readout** (✓ serving filters / ⚠ reachable, not serving / ✗ unreachable — from the health flow), an **"Only my node (exclusive)"** switch (default off, with a one-line "if your node goes down the wallet has no other peers" caption), and the node **label** if set.
- **Main screen:** a compact **health chip** when a node is paired (✓ node / ⚠ node dark), and a **loud banner** when a pinned node goes dark — in **exclusive** mode the banner offers a one-tap **"temporarily use public peers"** escape hatch (flip to additive for the session) so the user is never stranded without recourse.

## 6. Data flow — pairing sequence

1. User taps **Scan QR** → scanner returns raw string → `OwnNodeUri.parse`.
2. Net-mismatch? → confirm dialog (override allowed).
3. Persist (`CustomNodePrefs`: hostPort, label, enabled=true, exclusive as chosen).
4. `applyOwnNodeNow()` → controlled `forceReconnect` → `injectCustomNode()` re-runs → `injectPeerByIp(0x41)` + `setPinnedPeer(...)`.
5. Health monitor polls `compactFilterPeerStatus` → UI transitions CONNECTING → SERVING (or shows ⚠/✗, warn-but-allow).
6. On subsequent launches, `injectCustomNode()` at each sync-start site re-pins the node (idempotent).

## 7. Error handling

- **Unparseable QR / raw entry:** parser returns null → inline error, nothing persisted.
- **Net mismatch:** confirm-to-override; if overridden, pair as-is.
- **Unreachable / not-serving at pair time:** warn-but-allow; health shows ⚠/✗; retries on reconnect.
- **Pinned node goes dark mid-session:** dead-socket path reaps it (existing), pinned mechanism re-dials it (reserved slot). Health flips to `PAIRED_DARK`; banner shown. Additive mode keeps syncing on public peers meanwhile; exclusive mode shows the "use public peers" escape hatch.
- **Exclusive + node down = 0 peers:** the *accepted* single-point-of-failure. Surfaced loudly, never silently; the escape hatch converts it to additive for the session without losing the pairing.
- **DNS resolve fails (hostname node):** logged, no injection this cycle (existing behavior); health shows unreachable.

## 8. Sovereignty / privacy invariants (must not regress)

- Own-node toggle → **`COMPACT_FILTERS_ONLY` forced** and the **bloom-fallback watchdog suppressed** — no `filterload`, the address set never leaves the device (preserved from v3.10.1; re-verified by the on-device test).
- **Exclusive mode** = the wallet contacts *only* the user's node for chain data — the strongest privacy posture available pre-Model-A.
- QR pairing adds **no network trust**: a scanned node is still just a CF peer subject to the same filter-header TOFU/observe checks as any peer (Seq 1.5 will harden that layer). Verification proves *serving*, not *honest*.
- No new outbound endpoints, trackers, or backend calls. The operator QR helper runs on the operator's side.

## 9. Testing strategy

- **Native host KATs** (house rule 3): `own_node_pin_kat` (dial-slot guarantee, eviction exemption, exclusive vs additive candidate suppression) and `cf_peer_status_kat` (the four status states). Both compile the real submodule C with clang.
- **JVM unit tests:** `OwnNodeUri.parse` (scheme, port default, net, label sanitize/cap, malformed/adversarial input, fallthrough to `CustomNode.parse`); `CustomNodePrefs` label/exclusive round-trip; health-state mapping.
- **Security suite** re-run (accessors touch the peer manager; house rule 4).
- **On-device (mainnet, own node with `peerblockfilters=1`):** QR pair → immediate SERVING without restart; verify-warn on a wrong/down node; pinned node survives churn (not evicted, always re-dialed); exclusive → only the node connects, `/proc/net/tcp` shows the single peer; kill the node → dark banner + escape hatch → public peers resume; toggle off → normal public sync; **regression:** a non-own-node wallet is completely unaffected.
- **Pre-publish suite** (API 28/33/34/35) before the release tag.

## 10. Out of scope / explicit follow-ups

- **Onion / Tor pairing** — `dgbnode://` will reserve room for an onion host; routing lands with Seq 2.5 (loud Tor fallback).
- **Oracle-bootstrap** peer diversity + gossip-retention mainnet generalization (Seq 1.3–1.4; operator prerequisite).
- **`cfcheckpt` enforcement** graduating v3.10.25's observe-mode cross-check (Seq 1.5).
- **Model A** wallet↔node JSON-RPC backend (later Model-C tier, `UtxoSource` seam).
- **Multi-node** pairing / failover among several own nodes (single pinned node here).
- The **QR helper page** on the VPS/docs is a nicety, not a blocker — manual entry + a documented one-liner suffice for v1.

## 11. Cross-repo / operator notes

- No backend dependency for this PR. (The oracle-bootstrap operator prerequisite — `blockfilterindex=1`+`peerblockfilters=1` — is Seq 1.3, not here; a user's *own* node needs `peerblockfilters=1`, which the pairing copy states.)
- Docs: add a "Pair your own node" section (the `qrencode` one-liner + the `peerblockfilters=1` requirement) to the wallet docs when this ships.

## 12. Open questions for the plan

- Exact per-peer compact-filter-response signal for the status accessor (reuse existing vs add a `lastCfResponseTime`) — resolved by reading the current `cfheaders`/`cfilter` handlers during planning.
- Whether the main-screen health chip lives in the existing sync-status composable or a new one — a placement detail for the plan, not a design fork.
