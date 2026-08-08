# Disconnect ledger — build, findings, and open work

**Date:** 2026-08-08 · **Tree:** `/home/polloloco/wt-convoy` · **Status:** all changes UNCOMMITTED
**Raw capture:** `~/dgb-captures/2026-08-08-peer-ledger-note8-deep-restore.log` (16,206 lines)

---

## 1. Why this was built

Twelve days of "the deep restore never completes" produced three competing churn theories —
peer-side eviction, self-inflicted timeouts, OS freeze — and no way to separate them. The proximate
question was whether to adopt `.onion` transport so connections stabilise.

Nothing in the wallet recorded **who closed each connection**, and `error` structurally could not:

1. `read() == 0` is an ORDERLY FIN. `read() < 0 + ECONNRESET` is a hard reset. Both read loops
   collapsed them onto `error = ECONNRESET`. DigiByte Core's inbound eviction
   (`AttemptToEvictConnection` → `CloseSocketDisconnect`) closes **orderly**, so eviction and a
   network reset were literally the same observation.
2. `ETIMEDOUT` is produced by three different local deadlines, and the scheduled one is armed from
   ~8 call sites (20s `PROTOCOL_TIMEOUT`, the 90s inbound-idle reaper, publish timeouts).

## 2. What was built

`BRPeerCloseCause` / `BRPeerDisconnectTag` in `BRPeer.h`, recorded **alongside** `error` — every
existing consumer (`strerror` logging, the `ETIMEDOUT` test in `_peerDisconnected`) is untouched.

- `BRPeerClassifySocketResult(n, err)` — pure, so the KAT drives it with the exact tuples the loops
  produce. First-writer-wins via `_BRPeerNoteClose` so teardown cannot overwrite the real cause.
- 17 attribution points tagged: 8 deadline arms (`sync`, `publish`, `idle-reaper`, `maxconn-trim`)
  and 9 explicit disconnects (`misbehavin`, `not-synced`, `unusable-peer`, `download-swap`,
  `cf-stall`, `manager-stop`).
- Per-close log line + running histogram every 10 closes; `BRPeerManagerCloseLedgerSummary()` for UI.

```
[PEER-LEDGER] close cause=PEER_FIN tag=sync dl=1 err=104 life=18.3s in=..b/..msg out=..b/..msg handshake=1
[PEER-LEDGER] closes=120 shortLived(<30s)=51 | CONNECT_FAIL=1 LOCAL_SCHEDULED=1 LOCAL_PROTOCOL=20 ...
```

## 3. The verdict (Note 8, deep restore, 95 closes analysed)

| | Canon CF fleet (15 oracle IPs) | Non-canon (DHT/seeder) |
|---|---|---|
| Refused at the door (`handshake=0`, `in=0b`) | **35 / 80 = 44%** | **0 / 15** |
| Closed after a working session (`handshake=1`) | 45 | 15 |

1. **We are not hanging up on ourselves.** `LOCAL_SCHEDULED` = **1 in 95**. The 20s
   `PROTOCOL_TIMEOUT` was the leading self-inflicted hypothesis; it is refuted.
2. **The churn is concentrated on our own fleet.** The pool we call junk completed every handshake
   it started; the fleet we built refuses us 44% of the time.
3. "Refused at the door" = TCP connects, we send our 128-byte version, we receive **zero bytes**,
   they close. That is a node at inbound capacity.

### 3.1 This narrows the onion case — read carefully

Two different failures; onion addresses only one:

- **Can't GET a slot** (44% of canon closes). When inbound is full, Core tries to evict *someone
  else* to make room; failing that it drops the incoming connection. We are the one seeking room,
  not an eviction candidate — **network class does not help.** Cheaper lever: the unadvertised-port
  trick (13024 ≈ 2 inbound vs 12024 ≈ 106).
- **Can't HOLD a slot** (`handshake=1` then FIN). This is where `ProtectEvictionCandidatesByRatio`
  runs and where onion's rarity bonus applies.

> "PEER_FIN dominates ⇒ build onion" is **too coarse.** Always split on `handshake=`.

Before any onion rollout, `PROTOCOL_TIMEOUT` must become transport-aware —
`CF_REQUEST_TIMEOUT_SECS()` already prices Tor at 4× (20s vs 5s) while `PROTOCOL_TIMEOUT` stays 20.0.

## 4. Bug found by the ledger: false EPROTO wipes the peer pool

`LOCAL_PROTOCOL` was the fastest-growing cause (6 → 12 → 20), every instance on a canon oracle node.
Field signature, five different peers inside 85 ms:

```
dropping oracleprice, length 129,  not implemented
dropping oramusigctx, length 2095, not implemented
malformed message header: type not NULL terminated
disconnected
```

The wire header is magic(4) + command(**12, bytes 4..15**) + length(4) + checksum(4). The old test
`header[15] != 0` demanded a NUL terminator — but **a command name using all twelve bytes is legal
and has none.** Core's own `CMessageHeader::IsCommandValid()` accepts it.

The cost is not a dropped message: `EPROTO` → `_peerDisconnected` → `_BRPeerManagerPeerMisbehavin`,
which **removes the peer from `manager->peers`**, and on the tenth event runs
`array_clear(manager->peers)` — **wiping the entire pool.** A one-byte spec bug was evicting our own
CF fleet for speaking the protocol correctly.

**Fix:** `_BRPeerCommandFieldValid()` mirrors Core — printable ASCII; once a NUL appears all
remaining bytes must be NUL (trailing garbage still rejected); full-12 accepted.

> ⚠ **The offending command name is still unproven.** Enumerating names from `dropping X, not
> implemented` is biased — a message rejected at the header stage never reaches dispatch and is never
> named, which is exactly why this went unnoticed. The fix now logs the raw 12 bytes. Confirm from
> the next capture. A non-zero `header[15]` is either (a) a legal 12-char name, or (b) a genuine
> stream desync — a **different** bug still to find. The raw bytes discriminate.

## 5. Instrumentation defects found and fixed in my own code

- **`ctx->startTime` is a ping stopwatch, not a connection clock.** `BRPeerSendPing`/`SendPingProbe`
  reset it to now; verack and pong zero it. Lifetime measured from it printed `life=0.0s` on
  connections that had moved megabytes, and testing it `== 0` to detect a failed connect misfires on
  any peer past its handshake. Fixed with write-once `ctx->connectTime`.
  `handshake=0 && in=0b` turned out to be a **better** get-vs-hold discriminator anyway — no clock.
- `time(NULL) − <gettimeofday double>` printed negative ages (whole-second floor vs fractional).

## 6. Harness lesson

Retargeting production calls to `BRPeerScheduleDisconnectTagged` **blinded**
`convoy_hold_selfkill_kat`'s `-Wl,--wrap=BRPeerScheduleDisconnect` seam — `--wrap` only rewrites
linker-resolved references, and the intra-object delegation inside `BRPeer.c` is not one.

The near-miss is the lesson: the green arm failed loudly, but the red arm still "failed correctly"
for an unrelated reason (count 0 in both arms). Had the green assertion been *"no spurious refresh"*
rather than *"a refresh happened"*, both arms would have gone green and the gate would have silently
stopped gating. **Every gate needs at least one assertion that fails when the seam sees nothing.**

## 7. State and open work

**Changed, uncommitted, in `wt-convoy`:** `BRPeer.h`, `BRPeer.c`, `BRPeerManager.h`,
`BRPeerManager.c`, `native/src/test/host/peer_close_ledger_kat/` (new, 3 red arms),
`native/src/test/host/convoy_hold_selfkill_kat/` (seam fix).

Deployed to the Note 8 is the **first** build — it has the classifier but NOT the `connectTime`
fix, the `lastRecvAgo` fix, or the 12-char command fix.

**Next, in order:**
1. Rebuild + redeploy to confirm the raw command bytes and prove/disprove the 12-char hypothesis.
2. Test the unadvertised-port lever against the 44% door-rejection half.
3. Commit the ledger + the command-field fix (submodule first, then the pin — see the submodule
   commit-and-push pattern).
4. `mainchain_walk_skip_kat` still has NO RUNNER — needs `BRPeerCallbackInfo` and a `run.sh`.
5. Unrelated and still open: the DigiDollar missing-transaction gap-limit circularity —
   `dumpAllAddresses()` returns only derived addresses (~20 on a fresh restore) with no widening
   before `reconcileAddressHistory()` queries.
