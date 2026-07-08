# Compact-Filter Sync Peer Reliability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.
> **Device verification authorized on the Note 8 (SM-N950U, serial ce061716640b191c017e)** — install/run/force-stop/logcat are all permitted for this work.

**Goal:** Stop the compact-filters-only sync from stranding at 0 connected peers by (1) not re-dialing peers that were just rejected, (2) holding more peer connections so scarce filter peers can be found, and (3) keeping the Network Info screen read-only. Diagnosed live on the Note 8: in compact-filters-only mode the wallet hammered one behind filter node (`64.182.71.56` @ block 16.4M, ~7M behind tip) 122× — `/proc/net/tcp` showed 0 ESTABLISHED / 122 TIME_WAIT — never holding a peer.

**Architecture:** Small C-core changes in `BRPeerManager.c` (a session-scoped penalty set for rejected peers, consulted by the filter-first dial loop + connect path; a higher connection cap) plus the already-committed Kotlin Network-Info read-only fix.

**Spec/context:** the oracle-bootstrap note (`docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md`) is the *strategic* fix (more filter nodes); this plan is the *client-robustness* near-term fix so the few good filter peers aren't crowded out by churn.

## Global Constraints
- Submodule (`native/src/main/jni/digibytewallet-core/`): commit via `GIT_DIR`/`GIT_WORK_TREE`; push to `johnnylaw` fork before bumping the pin. Outer repo git uses `--ignore-submodules=all`.
- The behind-peer reject is at `BRPeerManager.c:914-916` (`BRPeerLastBlock(peer) + 10 < manager->lastBlock->height` → "node isn't synced" → `BRPeerDisconnect`). The relentless re-dial is the filter-first loop at `BRPeerManager.c:2448-2472` (dials every `manager->peers[k]` with `SERVICES_NODE_COMPACT_FILTERS` not already connected, no failure check).
- `manager->peers` holds **plain `BRPeer` structs** (not `BRPeerContext`) — do NOT call `peer_log()` on them (the existing comment at :2461-2467 explains the OOB-write hazard; format IPv4 octets from a local copy).
- Penalty state must be **session-scoped** (cleared on manager recreate) so a peer that later catches up is retried next launch — no persistent blacklist.
- Do NOT change the accept-gate predicate (that's correct) or gossip retention (out of scope, ships with oracle-bootstrap).
- Device: Note 8 verification is authoritative for a release build; `/proc/net/tcp` `:2EF8` (port 12024) counts real mainnet peer sockets; native log tag is `bread`.

## File Structure
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` — penalty set + dial-loop/connect skip; cap bump (submodule).
- Create: `native/src/test/host/peer_penalty_kat/` — host KAT for the penalty predicate.
- Already done (reference): `app/.../ui/settings/SettingsViewModel.kt` + `NetworkInfoScreen.kt` (read-only fix, branch `fix/network-info-readonly-no-teardown`).

---

## Task 1: Don't re-dial recently-rejected filter peers (the churn fix)

**Files:** Modify `BRPeerManager.c` (penalty set + reject site + dial loop + connect path). Create `native/src/test/host/peer_penalty_kat/{peer_penalty_kat_main.c,run.sh}`.

**Design:** Add a small fixed-size session penalty set to `BRPeerManager`:
```c
#define PEER_PENALTY_MAX 32
#define PEER_PENALTY_SECONDS (10*60)   // 10 min; a genuinely-behind node is skipped this long
// in struct BRPeerManager: UInt128 penaltyAddr[PEER_PENALTY_MAX]; uint16_t penaltyPort[PEER_PENALTY_MAX];
//                          double penaltyUntil[PEER_PENALTY_MAX]; size_t penaltyCount;
```
Add pure helpers (testable via host KAT):
- `int BRPeerPenaltyContains(const UInt128 *addrs, const uint16_t *ports, const double *until, size_t count, UInt128 a, uint16_t p, double now)` → 1 if (a,p) is penalized and not expired.
- The manager methods `_penalize(manager, addr, port, now)` (ring-buffer insert/refresh) and `_isPenalized(manager, addr, port, now)` wrap it.

- [ ] **Step 1: host KAT (failing first)** — `peer_penalty_kat_main.c`: build a tiny addr/port/until array, assert: a freshly-penalized (addr,port) is contained; an expired one (until < now) is NOT; a different addr/port is NOT; refreshing extends the window. Model `run.sh` on `native/src/test/host/network_switch_kat/run.sh` (compile `BRPeerServices.h`-style: include the header that declares `BRPeerPenaltyContains`). Run → fail (symbol missing).
- [ ] **Step 2: implement `BRPeerPenaltyContains`** (a `static inline` in a new small header `BRPeerPenalty.h`, or in BRPeerManager.c with a test hook) → KAT passes.
- [ ] **Step 3: wire the penalty on reject** — at `BRPeerManager.c:914-916`, before/after `BRPeerDisconnect`, call `_penalize(manager, BRPeerAddress(peer), BRPeerPort(peer), <now>)`. (Use the same time source the manager already uses; check how `manager` gets "now" — likely a `time(NULL)` or an existing field.) Also penalize on repeated connect-failure if trivial; keep scope to "not synced" if not.
- [ ] **Step 4: skip penalized peers in the filter-first loop** — at `BRPeerManager.c:2448-2472`, add `if (_isPenalized(manager, manager->peers[k].address, manager->peers[k].port, now)) continue;` alongside the existing `alreadyConnected` skip. (Format IPv4 from a local copy for any log — never `peer_log` a bare `BRPeer`.)
- [ ] **Step 5: build native + app** → `BUILD SUCCESSFUL`. Re-run `peer_penalty_kat` + `network_switch_kat` + `cf_gate_kat` → all `ALL PASSED`.
- [ ] **Step 6: on-device (Note 8) — churn gone.** Install the debug build (`adb install -r ...`), set Sync Mode = Compact Filters Only, launch. Verify with `adb -s ce061716640b191c017e shell 'cat /proc/net/tcp | grep -c :2EF8'` that TIME_WAIT churn to the behind peer drops sharply, and `logcat | grep bread` no longer shows the same peer re-dialed in a tight loop. (If no good filter peer exists at all, it still won't sync — that's Task 2 + oracle-bootstrap territory; the success criterion here is *the churn stops*.)
- [ ] **Step 7: commit** (submodule + KAT).

---

## Task 2: Raise the connection cap (peer diversity)

**Files:** Modify `BRPeerManager.c` (`PEER_MAX_CONNECTIONS`, used at :129, :876, :1827, :1832).

**DECISION (flag for user):** current `PEER_MAX_CONNECTIONS = 5`. The user wants ≥16 on boot. Tradeoffs: more connections = better filter-peer discovery, but higher battery/bandwidth, and it can't hold more filter peers than exist (~6 in the seeder today). **Recommendation:** raise to **8** now (conservative, matches Bitcoin Core's default outbound) and revisit toward 12–16 as the filter-node population grows via oracle-bootstrap. If you want 16 immediately, that's a one-line value change — say so and I'll set it.

- [ ] **Step 1:** change `#define PEER_MAX_CONNECTIONS 5` → the chosen value. Confirm all four use sites (array sizing at :129/:1832, DNS fill loop :876, `maxConnectCount` init :1827) scale correctly (they reference the constant, so they do).
- [ ] **Step 2: build native + app** → `BUILD SUCCESSFUL`.
- [ ] **Step 3: on-device (Note 8):** confirm it holds more connected peers (`/proc/net/tcp` `:2EF8` ESTABLISHED count > previous) and, in BOTH mode, still syncs cleanly (no regression, no ANR/OOM — the Note 8 is memory-constrained; watch `logcat -b crash`).
- [ ] **Step 4: commit** (submodule).

---

## Task 3: Network Info read-only (already implemented) + correct rationale

The read-only fix is committed on branch `fix/network-info-readonly-no-teardown` (`7756f9d9`). The adversarial review found the commit's *mechanism wording* was wrong: it does NOT "bypass the saved-block load → empty manager" (the native creation gate `g_savedBlocksLoadComplete` gates all startSync callers). The real harms are (a) a destructive recreate + peer churn fired on a transient-0-peer read from a passive screen, and (b) the frozen one-shot 0/0/0 snapshot diverging from the main screen.

- [ ] **Step 1:** reword the commit message / add a follow-up note correcting the mechanism to (a)+(b) above (drop the "bypasses saved-block load" claim). No code change needed — the fix itself (read-only + poll) is correct and stands.
- [ ] **Step 2:** fold this branch into the same release as Tasks 1–2.

---

## Out of scope (tracked elsewhere)
- **Seeder-side health-check**: the capability seeder served a filter peer stuck at 16.4M (`64.182.71.56`). It should verify filter peers are near the tip before serving. Server-side (`dgb-bloom-seeder` on the VPS), not the app — separate work item.
- **The saved-blocks use-after-free** → its own plan (`2026-07-08-savedblocks-uaf-hardening.md`).
- **Compact-Filters-Only as a mainnet footgun**: consider warning/disabling it on mainnet until filter-node population is sufficient — product decision.
- **Oracle-bootstrap** (more filter nodes + gossip) — the strategic "16 reliable peers without a central seeder" fix.

## Verification checklist
- [ ] Host KATs green (`peer_penalty_kat`, `network_switch_kat`, `cf_gate_kat`).
- [ ] Both flavors build.
- [ ] Note 8 (Compact-Filters-Only): churn stops (TIME_WAIT to the behind peer no longer climbs; that peer not re-dialed in a loop).
- [ ] Note 8 (BOTH): still syncs, no crash (`logcat -b crash` clean), tx confirms.
- [ ] Submodule pushed to `johnnylaw`; pin bumped.
