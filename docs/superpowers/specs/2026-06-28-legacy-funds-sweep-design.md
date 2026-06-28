# Legacy Funds Sweep — Design

**Date:** 2026-06-28
**Status:** Approved design, pre-implementation
**Branch:** phase1-modernization

## Problem

A real user restored a 12-word seed from an **old DigiByte mobile wallet** (BreadWallet
fork) into the current Kotlin wallet (v3.7.1) and saw a **0.00 balance** despite holding
**4.24797024 DGB** (verified unspent on-chain: tx `fad5f9b356a1072e326111acec8e7b04956bd3329c7d123417b4f33ba8a0dd2b`
vout 0 → `DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk`, 107k+ confirmations).

Root cause: the old wallet derives keys with a **non-standard HMAC seed string
`"DigiByte seed"` at path `m/0'`** (see `DerivationProfile` profile #2). The current
wallet uses standard `"Bitcoin seed"` BIP84 (`m/84'/20'/0'`) + BIP44. Same seed, entirely
different key tree → the funds sit on an address the native keychain never derives.

Two existing paths both fail this user:
- **"Scan for missing funds"** (`ChainReconciliationService`) detects the on-chain UTXO
  but imports via `registerRawTransaction()`, which only accepts txs the **native
  keychain owns**. The non-native address is rejected and mis-bucketed as
  `alreadyKnown++` (`ChainReconciliationService.kt:123`) → "Already in wallet: 1" but
  balance stays 0. Reconcile structurally cannot recover non-native funds.
- **Universal Restore auto-sweep** (`OnboardingViewModel`) does exist but **swallows all
  failures** (`OnboardingViewModel.kt:171-174`; `SweepOutcome.failureReason` is log-only),
  so a flaky backend scan or a `buildAndSignLegacySweep` failure leaves the user with no
  signal and no funds. This is the "untested sweep path."

## Goal

A robust, **visible**, re-runnable **sweep** that re-homes non-native funds onto a native
BIP84 address with a single on-chain transaction — so accessibility never depends on
chain reconciliation or full sync. A sweep only needs (1) the legacy key (derived from the
seed) and (2) a **targeted UTXO lookup** for the legacy addresses; it then builds, signs,
and broadcasts one tx to a fresh native address. No chain scan required.

## Decisions (from brainstorming)

1. **Entry points = both** — auto-classify at onboarding (surfacing the choice, NOT a
   silent auto-sweep) **and** a re-runnable Settings screen (recovers already-restored
   users like the reporter).
2. **UTXO source = pluggable interface**, reconcile backend as the first impl; multi-Electrum
   fallback deferred.
3. **Paths = all P2PKH/bech32 profiles now; BIP49 detect-but-defer** (honest "manual for
   now" message, never a silent skip).
4. **Two segregated phases: Classify → Choose (Sweep | Sync)**. Sweep is the v1 actionable
   path; "Sync" (native multi-path adoption) is presented as coming-soon and built as its
   own separate project.
5. **Testing = regtest-primary end-to-end + known-answer unit vectors + one mainnet proof.**
   The design includes standing up a regtest node.

## User Flow

### Phase 1 — Classify (diagnosis, no action)
Run the multi-profile derivation scan and tell the user exactly what they have, e.g.:
> "Found **4.24797024 DGB** on an **Old DigiByte mobile wallet** path (m/0', "DigiByte
> seed"). Your current wallet uses BIP84."

### Phase 2 — Choose per finding
- **[Sweep]** — recover the funds with 1 tx. Confirmation shows amount, fee, and destination
  before broadcasting. **Destination choice (default A):**
  - *Default:* a **fresh native address in this wallet** (re-home — instant native tracking,
    no reconcile). One tap.
  - *Advanced (opt-in):* **send to a different address** the user pastes. Gated behind an
    explicit "this address is NOT in your wallet" warning, since a sweep is irreversible.
- **[Sync — coming soon]** — keep the address, adopt the path natively. Disabled in v1 with
  an honest explanation; tracked as a separate future project.

### Entry points (one shared screen)
- **Onboarding:** after `recoverWallet` succeeds, auto-run Phase 1. If sweepable findings
  exist, navigate to the classify→choose screen — **not** a silent sweep. This directly
  fixes the silent-failure bug.
- **Settings → "Recover funds from another/older wallet":** runs Phase 1 on the current
  wallet's seed on demand. Re-runnable.

## Architecture & Components

| Component | Status | Responsibility |
|---|---|---|
| `UtxoSource` (interface) | **new** | `suspend fun fetchUtxos(addresses: List<String>): List<LegacyUtxo>`. `LegacyUtxo` = {address, txid, vout, amountSatoshi, scriptPubKeyHex}. Pluggable; `scriptPubKeyHex` is mandatory (signer needs it). |
| `ReconcileBackendUtxoSource` | **new** | First impl; wraps existing `DgbNodeClient`/reconcile backend address→UTXO lookup. |
| `FakeUtxoSource` | **new** | Deterministic test impl. |
| `RecoveryScanService` | refactor | Phase 1: derive addresses per non-native `DerivationProfile` (existing native derive) → `UtxoSource.fetchUtxos` → emit `List<Finding>`. `Finding` = {profile, addressesWithUtxos, totalSatoshi, addressType, sweepable}. BIP49 → `sweepable=false`. Distinguishes "scanned, empty" from "scan failed." |
| `LegacySweepService` | harden | Phase 2 sweep: reuse `NativeBridge.buildAndSignLegacySweep` + `Broadcaster.broadcast`. **Surface `SweepOutcome` (txid or failureReason)** — remove all silent swallowing. One tx per profile; per-profile results. |
| `RecoverFundsScreen` + ViewModel | **new** | Shared classify→choose UI for both entry points. |
| `OnboardingViewModel` sweep block | rewrite | Replace silent auto-sweep (lines ~139-176) with "navigate to RecoverFundsScreen when findings exist." |
| Settings entry | **new** | Row + nav to `RecoverFundsScreen`. |
| Regtest harness | **new** | `scripts/regtest/` — stand up `digibyted -regtest`, fund the fixed test seed's legacy addresses; used by the e2e tests. |

## Data Flow (Sweep)

```
seed ──> RecoveryScanService.classify()
          ├─ derive addresses per non-native DerivationProfile (existing native)
          └─ UtxoSource.fetchUtxos(addrs)   ◄── pluggable (reconcile backend now)
                 │
                 ▼
        List<Finding>  ──> RecoverFundsScreen (user picks Sweep)
                 │
                 ▼
   LegacySweepService.sweep(finding, dest = fresh native receive addr from current wallet)
          ├─ map UTXO → (chain,index) within the profile's derived list
          ├─ NativeBridge.buildAndSignLegacySweep(seed, hmacKey, prefixPath, utxos…, dest, feePerKb)
          ├─ Broadcaster.broadcast(signedBytes)
          └─ SweepOutcome{txid | failureReason}  ──> shown to user
                 │
                 ▼
   coins now on native BIP84 addr ──> normal BIP158/SPV tracks them (no reconcile)
```

The sweep requires **no chain sync** — only `fetchUtxos` + the native signer + broadcast.

**Destination address:** defaults to a fresh native receive address from the *current*
wallet (the exact `getReceiveAddress` format constant, matching the existing onboarding
sweep, is pinned during implementation). The user may instead supply an external address
via the Advanced option; `buildAndSignLegacySweep` already accepts an arbitrary
`destAddress`, so this is a UI + validation concern, not a signer change. Any external
address is **validated** (format + checksum across P2PKH `D…`, P2SH `S…`, bech32 `dgb1…`)
and confirmed behind a "not in your wallet" warning before broadcast. **Fee:** the wallet's
default `feePerKb` (`DEFAULT_FEE_PER_KB`, 100 sat/byte); a sweep is a single small tx, so no
fee UI is needed beyond showing the computed fee in the confirmation.

## Error Handling & Edge Cases

Theme: **nothing silent.**

| Case | Handling |
|---|---|
| Sweep fails (sign mismatch, broadcast fail) | `failureReason` shown with retry. No swallowed catch. |
| `UtxoSource` down/timeout | Distinguish "found nothing" from "scan failed"; failure → "Couldn't check — retry," never a false "no funds." |
| No UTXOs / already swept | Clean "No recoverable funds found." Re-run safe. |
| Double-sweep (double-tap, auto+manual race) | Button disabled in-flight; finding marked swept in-session; re-classify returns empty once UTXO spent (self-heals); second broadcast = double-spend → rejected → surfaced as "already swept/pending," not an error. |
| Dust | `totalIn − fee ≤ dust` → don't build; "amount too small to recover economically." |
| Partial multi-profile | One tx per profile; A succeeds while B fails; per-profile results; no all-or-nothing. |
| scriptPubKey missing (old backend) | Signer needs it; fail that finding loudly, never skip. |
| BIP49 detected | `sweepable=false` → "Found X DGB on wrapped-segwit (S…); manual recovery for now." |
| Broadcast can't reach peers | Signed tx preserved (retry/show hex), not lost — ties into the durable-send path. |
| Seed handling | Native signing needs the seed `ByteArray`; reuse existing zeroing discipline (`loadSeed` → `ByteArray` → `fill(0)` in `finally`). Never seed-as-String. |
| Destination needs loaded wallet | `getReceiveAddress` requires the native wallet; onboarding sweeps only after `recoverWallet` succeeds. |
| External destination (Advanced) | Validate format + checksum (P2PKH `D…`, P2SH `S…`, bech32 `dgb1…`); reject invalid/empty before signing. Explicit "not in your wallet — irreversible" confirmation. Default path (fresh native addr) skips this. |

## Test Plan

### Layer 1 — Known-answer unit tests (no network)
- Committed **TEST seed** (clearly marked, never holds real funds) → precomputed expected
  addresses per profile (m/0' "DigiByte seed", m/0' "Bitcoin seed", BIP44 DGB, BIP44
  wrong-coin). Assert derivation matches.
- `FakeUtxoSource` feeds known UTXOs → assert `buildAndSignLegacySweep` yields the expected
  signed-tx bytes (ECDSA is RFC6979-deterministic → reproducible): inputs, outputs, fee,
  destination all asserted.
- Edge vectors: dust, multi-UTXO/single-address, multi-address, missing scriptPubKey,
  BIP49 (`sweepable=false`), empty result, "scan failed" vs "empty."

### Layer 2 — Regtest end-to-end (primary heavy layer)
- **Stand up `digibyted -regtest`** — script + docs committed (`scripts/regtest/`).
- Harness mines coins, computes the TEST seed's legacy addresses, funds them.
- Full pipeline: classify (`UtxoSource` → regtest node) → sweep → broadcast → mine 1 block
  → **assert UTXO spent AND balance landed on the native BIP84 address.**
- Every profile + every edge case, repeatable. A regtest-backed `UtxoSource` (or
  `FakeUtxoSource` fed from regtest RPC).

### Layer 3 — One mainnet proof
- Before "done": one real sweep — coordinate with the reporter's actual DCrAZ… funds, or a
  small self-funded mainnet sweep. One-time, documented.

## Out of Scope (v1)
- Native multi-path **Sync** (separate project; "coming soon" in UI).
- **BIP49** P2SH-P2WPKH signing (detect-but-defer).
- Arbitrary **WIF / private-key paste** import (seed-derived only for v1).
- **Multi-Electrum fallback** `UtxoSource` (interface ready; impl later).

## Follow-on Projects
- **Native multi-path keychain ("Sync"):** derive/watch/spend a second derivation path
  alongside BIP84 so legacy addresses are tracked natively without moving coins.
- **Multi-Electrum `UtxoSource`:** resilient public ElectrumX fallback behind the interface.
- **BIP49 sweep:** P2SH-P2WPKH signing in native.
