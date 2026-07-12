# Duress PIN — Design Spec

**Date:** 2026-07-12
**Status:** Approved (design) — pending implementation plan
**Roadmap:** Phase 2 (Key & trust hardening)
**Repos:** `digibytewallet-android` (primary) + `digibyte-compendium` (DigiScope backend, alert)

## Overview

An optional **duress PIN** (a.k.a. wrench-attack / decoy PIN). When armed, entering
the duress PIN at unlock (instead of the real PIN) opens a plausible **decoy
wallet** holding only the small amount the user pre-funded (~5%). The main funds,
DigiAssets, DigiDollar, and the recovery seed are unreachable in that session, and
a silent real-time alert fires to the user via DigiScope.

Goal: give a coercer something plausible to take while protecting the majority of
funds, and notify the user that a coercion event is happening.

## Threat model & scope (honest)

**Protects:** the *coerced-to-unlock-the-app* case — someone forcing the victim to
open the wallet. They see a real, plausible small wallet; the 95% and the seed are
not derivable in that session.

**Does NOT protect against:** seed extraction, device forensics/imaging, malware
with process access, or an attacker who already holds the seed backup. The seed is
still the real key; this is an app-level decoy, not a hidden-volume of the keys.
This limitation is stated plainly in the in-app setup copy and here.

**Deniability caveat:** the feature is open-source and publicly documented, so a
knowledgeable coercer may *know* a duress mode can exist and demand "the other
PIN." Plausible deniability is therefore **not absolute**. Mitigations: no UI tells
that a duress PIN exists, and the decoy is a genuinely real, spendable wallet (not
an obvious fake).

## Terminology

- **Real PIN / real wallet** — the existing PIN and account `m/84'/20'/0'`.
- **Duress PIN** — a second, optional PIN.
- **Decoy account** — BIP44 account **1'** (`m/84'/20'/1'`, `m/86'/20'/1'`) of the
  same seed; the wallet the duress PIN opens.
- **Duress session** — an app session started by the duress PIN; carries a
  `DuressSession` flag that gates behavior.

## Design

### 1. Decoy = account 1' (same seed)

The main wallet derives from account 0 (`m/84'/20'/0'` for BIP84 + `m/86'/20'/0'`
for Taproot). The account level is currently a hardcoded constant
`BIP84_ACCOUNT` (native `BRBIP32Sequence.c:161,197`). The decoy is **account 1'**.

In a duress session the native wallet is created/loaded **at account 1'**, so it
only ever derives, watches, holds UTXOs for, and signs with the decoy account's
addresses. The main account's keys are **never derived** that session — there is
genuinely nothing more to surrender from within a duress session. One seed backs
up both accounts (standard BIP44). The decoy shows its **real** balance/history —
whatever the user funded it with (target ~5%); there is no computed "5% filter."

*Native work (the core dependency):* parameterize the hardened account level in
the BIP84 + BIP86 derivation (master-pub-key + priv-key-path functions) and in
wallet creation/signing so the JNI can request account N. Default remains 0'.

### 2. Two PINs, branch at unlock

`PinManager` (`core/.../security/PinManager.kt`, `dgb_pin_store`
EncryptedSharedPreferences) gains a second credential: `duress_pin_hash` +
`duress_pin_salt` + `duress_pin_method`, using the same Argon2id/PBKDF2 path as the
real PIN. `verifyPin(pin)` returns **which** credential matched: `REAL | DURESS |
NONE` (constant-time against both; a PIN that matches neither is NONE).

Unlock (`UnlockScreen.kt` → `WalletManager`):
- REAL → load wallet at account 0 (today's behavior).
- DURESS → load wallet at account 1' and set `DuressSession = true`.

**Biometric unlock is automatically disabled while a duress PIN is armed
(mandatory, not optional).** A fingerprint/face is a single identity that can only
open the real wallet, so leaving it on would let a forced finger bypass duress.
Consequences: unlock is PIN-only while duress is armed; to restore biometric
unlock the user must first remove the duress PIN. Setup copy states this.

### 3. Duress session behavior

Threaded by the `DuressSession` flag:
- Balance, transaction history, receive addresses, and send all operate on the
  account-1' wallet → the decoy's real, self-consistent state.
- **DigiAssets and DigiDollar are hidden** (not queried, not shown).
- **View Recovery Phrase is blocked/absent** (`SecuritySettingsScreen` view-seed
  path gated off).
- The **"Duress PIN" settings entry is hidden** — no UI reveals that a duress PIN
  exists. The Security screen shows biometrics simply "off" (no reason) so it does
  not leak that duress is why.
- Sends work normally and are naturally capped at the decoy's balance.
- The session is visually indistinguishable from an ordinary small wallet.

### 4. Real-time alert (app ping + on-chain keyed backup)

**Config (real mode, when arming duress):** register an alert channel with
DigiScope — email / Telegram (the Hub already has a TG bot) / push — plus a
per-user secret key. Stored encrypted on-device (`dgb_pin_store` or a sibling
EncryptedSharedPreferences); the secret is also known to DigiScope for the user.

**Triggers → immediate path:** on a duress **unlock** and on a duress **send**, the
app fires a background ping to a new DigiScope endpoint (`POST /api/duress/alert`
with the registered token). DigiScope notifies the user immediately. The ping
fails **silently** (no UI, no error) if offline — never a tell.

**On-chain backup path:** every duress **send** carries an OP_RETURN =
`HMAC(secret, firstInputOutpoint)` — where `firstInputOutpoint` is the tx's input[0]
`txid:vout` (visible on-chain, fixed before outputs are built, so no circular
dependency on the tx's own id). This is a **keyed marker**: indistinguishable from
random data to an observer, but DigiScope's `tx-monitor` — which already scans
mempool/blocks — recomputes `HMAC(secret, input0.outpoint)` per candidate tx and
matches it against the OP_RETURN, recognizing the user's duress sends without any
plaintext marker. So the user is alerted even if the app was offline at unlock,
once the duress tx propagates. No plaintext "DURESS" anywhere.

*Native work:* the duress-send path appends the keyed OP_RETURN output.

### 5. Setup & management (real mode only; invisible under duress)

- **Onboarding:** an optional, skippable "Set a duress PIN" step after the real PIN
  is set.
- **Settings → Security → Duress PIN:** enable / change / disable; register the
  alert channel; and a **"Top up decoy"** action that surfaces the account-1'
  receive address so the user funds the decoy (~5%).
- Enabling shows the biometrics-off warning (§2). All of this is absent in a
  duress session.

## Components & boundaries

- **Native (C/JNI):**
  - account-parameterized BIP84/BIP86 derivation + wallet creation + signing
    (`BRBIP32Sequence.c`, `BRWallet.c`, `jni_wallet.c`); JNI accepts an account
    index (default 0).
  - keyed-marker OP_RETURN builder on the duress send path
    (`jni_transaction.c` / tx builder).
- **Core (Kotlin):**
  - `PinManager` — second credential + `verifyPin → {REAL,DURESS,NONE}`.
  - `WalletManager` — `unlock(account, duress)`; `DuressSession` state exposed to UI.
  - `SendViewModel` — duress send (keyed marker) + alert ping.
  - `DuressAlertClient` — registers channel + pings DigiScope; fails silently.
  - `DuressConfig` — encrypted storage of enabled flag, alert channel, secret.
- **App (Compose):** unlock branch; duress-session gating (hide assets/DD/seed/
  settings entry); duress setup + top-up UI; biometrics auto-off wiring.
- **DigiScope backend (`digibyte-compendium`):** `POST /api/duress/register` +
  `POST /api/duress/alert` + notifier; `tx-monitor` extension to recognize the
  keyed marker and alert.

## Data & storage

- PIN credentials: `dgb_pin_store` (existing) + duress credential keys.
- Duress config (enabled, alert channel, per-user secret): encrypted at rest.
- Decoy account state persists like the main account (blocks/UTXOs) but is only
  loaded/synced within a duress session (or a background top-up context).

## Error handling & edge cases

- **Offline at duress unlock:** app ping silently fails; on-chain marker backstops
  once a send propagates. No error UI (no tell).
- **Duress send offline:** broadcast when connectivity returns; marker still lands
  on-chain → alert fires then.
- **Forgotten which PIN:** the real PIN always works; duress is additive and never
  blocks the real wallet.
- **Disable duress:** removing the duress PIN clears its credential + config and
  re-enables biometric unlock.
- No error, log, or UI state under duress ever reveals that a duress PIN exists.

## Security considerations

- App-level decoy, not key hiding (see threat model). Document in-app.
- Biometrics-off is enforced, not optional (§2).
- The alert secret is sensitive (it keys the on-chain marker + authorizes alerts) —
  store encrypted; never log; scope it to alerting.
- The keyed marker must be a fixed-length, standards-clean OP_RETURN so the duress
  tx is not structurally unusual vs a normal wallet tx that carries data.
- PIN rate-limiting (an existing CRITICAL-1 residual) is adjacent but out of scope
  here; note it as a companion hardening.

## Testing

- **Native KATs:** account-1' derivation vectors (BIP84 + BIP86); sign a tx at
  account 1'; keyed-marker determinism for a known (secret, tx).
- **Unit:** `verifyPin` returns the correct branch incl. neither-match; duress
  credential set/clear; `DuressSession` gates balance/assets/DD/seed; send confined
  to decoy account; `DuressAlertClient` fails silently offline.
- **On-device integration:** arm duress → fund decoy → duress-unlock shows only the
  decoy, seed blocked, biometrics off, no duress tells → alert fires (mock
  DigiScope) → real PIN restores the full wallet, assets, DD, seed.

## Phasing

- **Phase A — wallet-side decoy protection (the core value):** account-1' native
  derivation/signing; PinManager second credential + unlock branch; biometrics
  auto-off; DuressSession gating (hide 95%/assets/DD, block seed, hide settings
  tell); decoy top-up UI.
- **Phase B — alert integration:** `DuressAlertClient` + DigiScope
  register/alert endpoints; keyed-marker OP_RETURN on duress send + `tx-monitor`
  recognition.
- **Phase C — polish:** onboarding step, top-up UX refinements, copy.

Each phase is independently shippable; Phase A delivers protection even before the
alert lands.

## Out of scope / open questions

- Separate decoy *seed* (hidden-volume) model — rejected in favor of the decoy
  account (one seed backup).
- Auto-maintaining the ~5% ratio — rejected; the user funds the decoy manually.
- Which alert channels ship first (email vs Telegram vs push) — decide in Phase B
  against DigiScope's existing notifier capabilities.
- Whether the decoy account should also expose a Taproot receive type in duress
  mode (probably yes, for plausibility) — confirm in Phase A.

## Global constraints

- One seed; decoy is account 1' of the same seed (no second backup).
- Biometrics-off is a mandatory, automatic consequence of arming duress.
- No UI/log/error path may reveal, under duress, that a duress PIN exists.
- The alert's immediate path (app ping) must fail silently; the on-chain path must
  be covert (keyed, no plaintext marker).
- Honest in-app disclosure of what duress does and does not protect.
