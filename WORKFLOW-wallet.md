# Claude Code Workflow — digibytewallet-android

Drop this file in the repo root. Each Claude Code session in this
terminal starts by reading: `CLAUDE.md` → `ROADMAP.md` → this file →
the one spec for the sequence in flight. One sequence = one branch =
one PR. Do not interleave sequences.

---

## Step 0 — True-up (do before any feature work, ~1 session)

The repo's code is ahead of its documentation. Fix that first so every
subsequent session inherits accurate state.

- [ ] **0.1 Branch reconciliation.** `CLAUDE.md` names the working
      branch as `phase1-modernization`; the repo's default is `develop`.
      Confirm which is canonical, merge or retire the other, and update
      `CLAUDE.md` to match. Every future session keys off this.
- [ ] **0.2 Replace `ROADMAP.md`** with the July 2026 revision
      (delivered separately). Resolve its one flagged softener first:
      own-node Model C — confirmed decision or still open?
- [ ] **0.3 Update `CLAUDE.md`.** Version line says v3.7.6; actual is
      v3.10.26. Also stale: the Sync Mode paragraph (describes
      `SyncMode.BOTH` default + bloom fallback — bloom is removed).
      Rewrite that block to describe the CF-only reality.
- [ ] **0.4 Doc debts named in the roadmap revision:**
      - `docs/THREAT_MODEL.md` — peer/observer rows: bloom removal is
        complete mitigation; filter-header-source trust is the residual.
      - `docs/BIP_COMPLIANCE.md` — BIP 37 → **Removed**; 157/158 →
        Implemented-and-only; 341/342 → Partial; 174 → Planned.
- [ ] **0.5 Housekeeping sweep.** `git status` clean; release-please
      config sane; CI green on the canonical branch; `TESTING.md`
      reflects the real suite (62 test files, KAT harness).
- [ ] **0.6 Commit as a single `docs: true-up to v3.10.26 reality` PR.**
      No code changes in this PR — it must be trivially reviewable.

**Exit criteria:** a fresh Claude Code session reading only CLAUDE.md +
ROADMAP.md would have zero false beliefs about the codebase.

---

## Sequence 1 — Never stranded (Phase 1 remainder → v4.0.0)

*Branch: `seq1/own-node-track`. Spec: `2026-07-11-cf-fleet-reliability-own-node-track.md` + `2026-07-08-oracle-bootstrap-peer-discovery.md`.*

Logical progression — each step is testable before the next begins:

- [x] **1.1** Own-node pairing flow: QR-scan `host:port` →
      verify `NODE_COMPACT_FILTERS` service bit → pin → health on main
      screen. Builds on the v3.10.1 custom-node primitive. **Shipped**
      (`seq1/own-node-track`) — `dgbnode://host:port?net=&label=` QR
      parser, native CF-peer status accessor, main-screen health chip.
      Onion scanning deferred: the grammar reserves an onion host but
      the parser rejects it today (lands with Seq 2.5, loud Tor
      fallback).
- [x] **1.2** Pinned-node behavior in the peer manager: pinned peer
      never evicted by churn logic; loud banner if it goes dark.
      **Shipped** — native reserved dial slot + eviction exemption +
      exclusive-mode dial suppression, dark-node banner with a "use
      public peers" escape hatch, immediate-apply via reconnect (no
      forced restart).
- [ ] **1.3** Oracle-bootstrap peer injection: hardcoded oracle set as
      CF bootstrap peers, `addr` gossip bloom-out, seeder demoted to
      accelerant. Gate on the operator prerequisite (see cross-repo
      dependencies below).
- [ ] **1.4** Generalize the two mainnet gates embedding the
      testnet-only CF exception (or confirm operators run Path B) —
      resolve the A/B open decision when the oracle roster freezes.
- [ ] **1.5** `cfcheckpt` graduation: v3.10.25's observe-and-log
      cross-check → active rejection of a misbehaving filter chain.
      KAT + device verification.
- [ ] **1.6 Cut v4.0.0.** Release notes lead with: "cannot leak,
      cannot be stranded." Update ROADMAP header + appendix in the
      same PR (Step 0's standing rule).

---

## Sequence 2 — Key & trust hardening (Phase 2, in dependency order)

*Branch: `seq2/key-hardening`. Spec: `2026-07-12-duress-pin-design.md` + roadmap Phase 2.*

- [ ] **2.1 PIN rate-limit** (`PinManager.kt:43`): 3 free attempts →
      1/5/30/60-min cooldowns → optional wipe-after-N toggle. Small,
      lands first, gates 2.2.
- [ ] **2.2 Duress PIN, Phase A** per spec, plus the two adopted
      addenda: (a) duress session severs Hub/Digi-ID identity;
      (b) OP_RETURN beacon deferred to research — app-ping alert only.
      Requires backend coordination (cross-repo).
- [ ] **2.3 Keystore auth binding, per-API probing**
      (`KeyStoreManager.kt:37`). Riskiest item — full device-matrix
      pass on API 28/33/34/35 before merge.
- [ ] **2.4 Digi-ID key isolation** (`DigiIdManager.kt:49`): dedicated
      subtree, migration window on the backend, derivation-namespace
      registry entry in `docs/derivation/`.
- [ ] **2.5 Loud Tor fallback**: user-enabled Tor that fails bootstrap
      falls back to clearnet with a main-screen banner + retry. Never
      silent, never 0-peers.

---

## Sequence 3 — Sovereign feature layer (Phase 3)

*Branch per item. New specs required before coding (write spec → review → implement, per house pattern).*

- [ ] **3.1 PSBT foundation** — new `core/…/psbt/` package. BIP 174
      **with BIP 371 Taproot fields in scope from PR #1** (the vault
      requirement is why this moved up). KAT-test the codec against
      published test vectors before any UI.
- [ ] **3.2 Watch-only** — xpub/descriptor import, CF-private history,
      unsigned-PSBT construction.
- [ ] **3.3 DigiDollar vault lifecycle** — open/mint/redeem/monitor,
      vault state derived on-device from filter-matched blocks (per the
      new anti-pattern: no hosted vault dashboard in the data path),
      every signing path hot-key OR PSBT round-trip. Testnet first;
      mainnet activation is audit-gated (Sequence 4).
- [ ] **3.4 Multi-algo security dashboard** — per-algo difficulty +
      DigiShield from header version bits + nBits already on device.
      Zero network additions. Coordinate visuals with DigiScope's
      Gauntlet.
- [ ] **3.5** Coin control → RBF → WIF sweep → address book, as
      independent small PRs, in that order (coin control feeds duress
      decoy funding and vault UTXO selection).
- [ ] **3.6 Multisig** — last, on top of hardened descriptors/PSBT.

---

## Sequence 4 — Audit & distribution (Phase 4)

- [ ] **4.1** Engage third-party auditor. Scope: seed handling, duress
      properties (no tell / no flag / biometric kill), CF sync
      integrity, DigiDollar signing paths.
- [ ] **4.2** F-Droid submission (finish reproducible-build metadata) —
      can precede audit completion with honest beta labeling. **No longer
      first in the channel order** — see 4.4.
- [ ] **4.3** Audit-gated releases: DigiDollar mainnet-in-wallet;
      public duress-PIN promotion.
- [ ] **4.4** Play Store — **now the first distribution channel** (developer
      account approved 2026-08-27; the at/after-v4.0.0 timing condition is
      already met). Numbering kept for stable references; ordering is 4.4
      before 4.2.
- [ ] **4.5** Coldcard QR → NFC → multi-account.

---

## Cross-repo dependencies (coordinate, don't block)

| Wallet needs | Lives in | Needed by |
|---|---|---|
| Oracle nodes with `blockfilterindex=1` + `peerblockfilters=1` | Ops / oracle operators | Seq 1.3 — and DigiDollar launch |
| Duress alert endpoint (app-ping) | DigiScope backend | Seq 2.2 Phase B |
| Digi-ID new-subtree acceptance + migration window | DigiScope backend | Seq 2.4 |
| Gauntlet visual language for dashboard | DigiScope frontend | Seq 3.4 (nice-to-have) |

## House rules for every Claude Code session (this repo)

1. Read `CLAUDE.md` + `ROADMAP.md` + the active spec before touching code.
2. Spec-first for anything ≥ M: write/extend the dated spec in
   `docs/superpowers/specs/`, get it approved in-session, then implement.
3. Native (C) changes ship with a KAT in `native/src/test/host/`.
4. Security-touching changes run the security suite + relevant
   pre-publish API levels before PR.
5. Docs that a change invalidates update **in the same PR** (the
   standing legibility rule).
6. Never start a new sequence with the previous sequence's branch
   unmerged.
7. **Crash-triage BuildId gate (HARD):** a native tombstone/backtrace
   drives NO decision until its `.so` BuildId matches the retained
   unstripped `.so` you symbolize against. `readelf -n <so> | grep 'Build ID'`
   vs the tombstone's `(BuildId: …)` — a 10-second check. (2026-07-26: one
   wrong-BuildId symbolization sent a crash hunt down a false CF-parser-RCE
   path — private branch, a 33-min fuzz run, disclosure panic — all to unwind
   a stack read off the wrong binary. Gradle can package a stale native lib
   into the APK while the cmake obj re-links to a different BuildId; the
   installed APK's `.so` is the source of truth — extract it and match.)
