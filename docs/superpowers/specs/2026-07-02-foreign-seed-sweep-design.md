# Foreign-Seed Sweep ("Recover funds from another wallet") — Design

**Date:** 2026-07-02
**Status:** PROPOSAL — pending user approval. No implementation until approved.
**Extends:** the shipped Recover-Funds feature (v3.8.0), which sweeps only the
*current* wallet's own non-native derivation funds. This adds the ability to
enter a *different* wallet's recovery phrase and sweep its funds into the
current wallet.
**Fork defaults (chosen while user away — confirm):** coverage = all supported
paths incl. native BIP84 (BIP49 deferred); entry point = mode toggle inside
Recover Funds; input = mnemonic only (WIF + BIP39 passphrase deferred).

## 1. Problem

On-device testing of v3.8.0 found: with a wallet already loaded, Recover Funds
re-scans the *loaded* wallet's own seed — there is no way to enter a **different**
wallet's phrase and pull its coins in. `RecoverFundsViewModel.classify()`/`sweep()`
both call `seedProvider.loadSeed()`; `RecoverFundsScreen` has no seed input. The
original spec deliberately deferred foreign-seed/WIF import to a later version.
This is that version, for phrases.

## 2. Goal & non-goals

- **Goal:** enter a foreign BIP39 mnemonic → scan all its derivation paths →
  sweep every funded, supported path (P2PKH legacy + P2WPKH native BIP84) into
  the current wallet's own native address. The phrase is used once and never
  stored.
- **Non-goals (deferred):** WIF/private-key paste; BIP39 passphrase; BIP49
  (P2SH-P2WPKH) sweeping (stays "manual recovery needed" as today); choosing an
  external destination for a foreign sweep (foreign funds always land in *this*
  wallet).

## 3. Key technical facts (verified)

- The sweep signer handles **P2PKH (format 0) and P2WPKH (format 1)** inputs
  (`jni_derive.c:340-346`, `BRTransaction.c:252` BIP143 witness). **BIP49
  (format 2) is not supported end-to-end** and is deferred everywhere.
- `RecoveryScanService.scanFromSeed(seed)` already scans all 6
  `DerivationProfile.BUILT_INS` and computes both `nonNativeWithFunds` and
  `nativeResult`. It takes the seed as a **parameter** — nothing is hardcoded to
  the stored seed.
- `LegacySweepService.sweepFromSeed(seedBytes, nonNativeResults, destAddress,
  destIsSelf)` takes the seed + the list of profiles to sweep + destination as
  parameters. It already defers `addressFormat == 2` (BIP49) internally.
- The mainnet proof (2026-07-02) exercised the foreign-seed **P2PKH-input**
  sign+sweep path end-to-end. **P2WPKH-input** signing is supported but not yet
  exercised — this design requires a test for it.

## 4. Architecture

Own-seed vs foreign-seed differ in exactly two places; everything else is shared.

- **UI (`RecoverFundsScreen`)** — add a mode selector at the top:
  - *This wallet* (default): unchanged — auto-classifies the stored seed.
  - *Another wallet's phrase*: shows a mnemonic input (reuse the
    `MnemonicInputScreen` field pattern), a one-line warning ("Enter another
    wallet's recovery phrase to move its funds into this wallet. The phrase is
    used once to sign the transfer and is never saved."), and a **Scan** button
    (no auto-scan). Findings + sweep UI are shared; the destination in this mode
    is fixed to *this wallet* (no external-address option).
- **ViewModel (`RecoverFundsViewModel`)** — add a foreign path that mirrors the
  existing one but sources the seed from the entered phrase and sweeps the full
  funded set:
  - `classifyForeign(mnemonic: String)`: trim/normalize; `isValidMnemonic` →
    else inline error; `mnemonicToSeed(bytes)` → `foreignSeed: ByteArray`;
    `scanFromSeed(foreignSeed)`; **`finally { foreignSeed.fill(0) }`**. Findings =
    `nonNativeWithFunds` **+ `nativeResult` if it has funds** (this is the one
    behavioral difference — for a foreign seed we sweep native BIP84 too). Hold
    an `isForeign = true` flag on the resulting `UiState.Findings`.
  - `sweepForeign(mnemonic: String)`: re-derive `foreignSeed` from the entered
    phrase, `sweepFromSeed(seedBytes = foreignSeed, resultsToSweep = <the funded
    set>, destAddress = current wallet native (`getReceiveAddress(0, format=2)`),
    destIsSelf = true)`; **`finally { foreignSeed.fill(0) }`**.
- **Core (`RecoveryScanService` / `LegacySweepService`)** — no signer change.
  Optional clarity: expose an `allWithFunds` (nonNative + native, funded,
  sweepable) on `State.Done` so the VM doesn't hand-assemble it, and rename
  `sweepFromSeed`'s `nonNativeResults` param to `resultsToSweep` (it already
  sweeps whatever list it's given). BIP49 funded results are surfaced as "manual
  recovery," never silently swept.

## 5. Seed & key security

- The foreign seed is a `ByteArray`, **zeroed in `finally`** on both classify and
  sweep, and **never persisted** — it never touches the KeyStore or prefs (only
  the user's own wallet seed is stored). The foreign seed's derived keys are used
  solely to sign the sweep and are never registered as wallet keys.
- The entered mnemonic `String` lives only in transient Compose UI state during
  the flow and is dropped on completion/navigation. (JVM `String` can't be
  zeroed — same accepted limitation as onboarding restore; noted, not solved
  here.)
- Destination is always this wallet's own address, so a foreign sweep is a
  *receive* — `destIsSelf = true` keeps the correct receive categorization
  (the v3.8.0 `OutgoingTxStore.shouldApplyOutgoingOverride` fix).

## 6. Error handling

- Invalid mnemonic → inline field error; no scan.
- No funded paths on the foreign seed → "No recoverable funds found for that phrase."
- Backend unreachable → same messaging as own-seed.
- BIP49 funds present → listed as "needs manual recovery" (not swept).
- Sweep build/sign/broadcast failure → error surfaced; no funds moved (fail-closed).

## 7. Testing (required)

- **JVM:** `classifyForeign`/`sweepForeign` with `FakeUtxoSource` + a fixed
  foreign seed — assert (a) it uses the ENTERED seed, not `loadSeed()`; (b) the
  foreign sweep set INCLUDES the funded native profile; (c) BIP49 is excluded/
  flagged; (d) the derived seed is zeroed.
- **Instrumented signed-tx KAT — P2WPKH input (new, required):** sign a sweep of
  a synthetic **native BIP84 (P2WPKH)** UTXO of a fixed foreign seed; assert
  signed + pin hex; decode via node. Closes the "P2WPKH input never exercised" gap.
- **Security:** assert no KeyStore/prefs write occurs during a foreign sweep
  (the foreign seed is never stored).
- **Device (emulator-5554):** enter a foreign phrase → scan → sweep a funded
  legacy + a funded native address → confirm on-chain (mirrors the v3.8.0 proof,
  extended to a P2WPKH input).

## 8. Open items for the user

- Confirm the §2 fork defaults (coverage incl. native; mode toggle; mnemonic-only).
- Confirm foreign sweeps always target *this* wallet (no external-dest option in
  foreign mode).
