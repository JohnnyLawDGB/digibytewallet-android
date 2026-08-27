# BIP39 passphrase (12+1 / 24+1) — design

**Status:** design, not yet implemented
**Date:** 2026-08-27
**Scope:** an OPTIONAL BIP39 passphrase on wallet creation, and matching support in the
restore / recover-from-another-wallet flow.

---

## 1. What this is, in one line

A user may add a passphrase to their recovery phrase. The phrase alone then no longer opens
the wallet — the phrase *and* the passphrase do.

## 2. Why someone would want it

The recovery phrase is the whole wallet. Anyone who reads those twelve words can take the
coins, and the most common way that happens is not malware — it is the piece of paper. A photo
in a camera roll that syncs to a cloud account. A drawer someone else opens. A "support agent"
who asks you to type the words to "verify your wallet".

A passphrase makes the written phrase insufficient on its own. Someone who finds the paper
finds nothing they can spend.

**Who should turn it on:** anyone whose written backup lives somewhere they do not fully
control, or who wants the paper to be useless if found.

**Who should not:** anyone who will not reliably record a second secret. See §7 — the
passphrase creates a new way to lose coins, and that is not a hypothetical.

## 3. Purely optional

Off by default. A wallet created without one behaves exactly as today, byte for byte: BIP39
derives the same seed for a NULL passphrase and for an empty string (`salt = "mnemonic" + ""`),
which is verified in `BRBIP39DeriveKey`. Nobody is prompted, nudged, or interrupted. The
control is an expandable "Advanced" affordance on the seed screen, closed by default.

## 4. What already exists

Grounded against source, not assumed:

| Piece | State |
|---|---|
| `BRBIP39DeriveKey(key64, phrase, passphrase)` | Takes a passphrase. All four `jni_wallet.c` call sites pass `NULL`. |
| `NativeBridge.mnemonicToSeed(phrase, passphrase: String?)` | Already passphrase-capable; already used by the foreign-seed sweep. |
| `RecoveryScanScreen` | Already calls `runRecoveryScan(passphrase = null)`; the comment there calls the picker "a follow-up we can add". |
| `WalletManager.loadBip39Seed()` | Carries the note "this wallet does not use a BIP39 passphrase". |

**The cryptography is done.** This is plumbing and UX.

## 5. Decision: the passphrase is STORED on device

Encrypted in the same Keystore envelope as the mnemonic (`dgb_wallet_seed`).

**Why stored.** `restoreFromDisk()` is invoked from `AppNavigation` on resume and from
`BootGuard` recovery paths, **with no UI attached**. A passphrase that must be typed on every
unlock would leave those paths unable to rebuild the wallet, and background sync would die
until the user happened to open the app and re-enter it.

**What this costs, stated honestly.** A stored passphrase inherits exactly the mnemonic's
protection — the two sit behind the same door. It defends the *backup*, not the *device*. The
UI must never imply otherwise; see R6.

## 6. Requirements

Each is testable. Numbers are referenced by the implementation plan.

- **R1 — Optional and inert by default.** No passphrase ⇒ identical seed, identical addresses,
  identical behaviour to today. `null` and `""` are equivalent and both mean "no passphrase".
- **R2 — NFKD normalisation.** `BRBIP39DeriveKey` performs none; its header states the caller
  must. Kotlin normalises with `Normalizer.Form.NFKD` before the JNI call. Without this, a
  passphrase containing an accent derives one seed here and a different one in Electrum — an
  interop failure that only surfaces at restore, on someone else's software, too late to fix.
- **R3 — Bounded length.** The salt is a stack VLA sized by the passphrase:
  `char salt[strlen("mnemonic") + strlen(passphrase) + 1]`. Unbounded input is a stack
  overflow. Kotlin enforces a cap (proposed: 128 characters) before the value reaches native.
- **R4 — Versioned fingerprint (see §8).** Fingerprint moves from the mnemonic to the derived
  seed, under a NEW key, with migration. Getting this wrong forces a full re-sync on every
  existing install.
- **R5 — Confirmed twice at creation.** A BIP39 passphrase has no checksum: a typo does not
  error, it derives a different, valid, empty wallet. Entry is confirmed like the PIN, and the
  two must match before the wallet is created.
- **R6 — Honest copy.** The screen says the passphrase protects the written phrase, and does
  NOT claim it protects the device. It states plainly that the phrase alone will no longer
  restore the wallet.
- **R7 — `SeedViewScreen` reveals both.** If the app stores the passphrase, showing only the
  phrase hands the user half a backup while implying it is whole.
- **R8 — Restore scans both ways.** When a passphrase is supplied, the recovery scan also runs
  without it. If the bare mnemonic finds funds and the passphrase does not, say so — it is the
  strongest available signal of a typo, and it is nearly free.
- **R9 — Never a bare "no funds found."** With a passphrase in play the honest sentence is
  "no funds found **with this passphrase**", plus a retry. Same class of defect as the
  `anyBackendUnreachable` fix: a confident answer to a question we did not finish asking,
  about someone's money.
- **R11 — Creation only; existing wallets are never offered it.** No Settings entry point, no
  prompt, no migration path. An existing wallet's derivation must be provably untouched by this
  change (§8), and the surest way to keep that true is to give the passphrase no way to reach a
  wallet that already exists.
- **R10 — Wipe paths clear it.** `WalletDataEraser` and `StaleDataWiper` must remove the
  passphrase with the seed. A passphrase surviving a wipe is a secret outliving the wallet it
  belonged to.

## 7. The risk this creates

Distinct from what it fails to prevent.

**A new way to lose coins.** Today the twelve words are sufficient. With a passphrase they are
not — and because the app remembers it, the user is actively encouraged not to write it down.
The device dies, they restore from paper, and the paper no longer works. There is no checksum
to explain why: the wrong passphrase opens a valid, empty wallet.

This is judged the larger practical danger, larger than the theft it prevents, and R5/R6/R7
exist to attack it directly.

**False confidence.** On-device the PIN remains the only gate. `CLAUDE.md` records CRITICAL-1's
open residual — no Keystore user-auth binding, so a compromised app process can decrypt the
seed without device unlock. The passphrase sits behind that same door and must not be sold as
if it did not.

## 8. Backwards compatibility

**Derivation: free.** Existing wallets have no passphrase, `null` ⇒ `salt = "mnemonic"`,
identical seed, identical addresses. Nothing to migrate.

**Fingerprint: NOT free, and the trap in this whole change.**

`saveSeedFingerprint()` stores SHA-256 of the **mnemonic bytes**. `restoreFromDisk()` compares
it and calls `clearSyncData()` on mismatch. Two wallets sharing a mnemonic but differing by
passphrase would produce an identical fingerprint — so it must move to the derived seed.

But changing the basis in place means every existing install's stored value stops matching on
first launch after upgrade, and **every user gets their sync data cleared and re-syncs from the
floor.** On a wallet whose deep-restore behaviour has been the source of this much work, that is
not an acceptable upgrade.

Therefore:

- `seed_fingerprint` (v1, mnemonic-based) is left untouched and is never written again.
- `seed_fingerprint_v2` holds SHA-256 of the derived 64-byte seed.
- On first launch after upgrade: if v2 is absent and v1 matches the mnemonic, compute and write
  v2 and treat the wallet as **unchanged** — no `clearSyncData()`.
- Once v2 exists, only v2 is consulted.

A test must assert that an install carrying only v1 does not clear sync data on upgrade.

## 9. Out of scope

- Plausible-deniability / decoy wallets (the "hidden wallet" model). That requires *not*
  storing the passphrase and is a different product; see [duress PIN notes].
- Changing a passphrase on an existing wallet. That is a new wallet with new addresses, not an
  edit, and needs its own flow with a funds-migration step.
- Passphrase on the DigiDollar or asset paths beyond what falls out of the seed change —
  `buildAndSignForeignTx` takes seed bytes, so a passphrase-derived seed flows through it
  unchanged.

## 10. Gates

- Host KAT: a known mnemonic + passphrase produces the BIP39 test-vector seed (proves interop).
- Host KAT: NFKD — the same passphrase in composed and decomposed form derives one seed.
- Unit: `null` and `""` derive identically (R1).
- Unit: fingerprint v1-only install does not clear sync data (§8).
- Unit: length cap rejects before the JNI boundary (R3).
- Device: create with passphrase, force-stop, cold start, wallet still opens (stored path).
- Device: restore with the wrong passphrase reports "with this passphrase", not "no funds".

## 11. Open questions

1. Length cap: 128 characters proposed. Any reason to go higher?
2. Does `SeedViewScreen` reveal the passphrase behind the same PIN + biometric gate as the
   phrase, or a second confirmation?
3. ~~Should an existing wallet be offered "add a passphrase"?~~ **RESOLVED 2026-08-27: no.**
   Creation only. Adding a passphrase to an existing wallet is a different wallet with different
   addresses, not an edit — offering it in Settings would imply an in-place upgrade that does not
   exist, and the first thing the user would see is a zero balance. See R11.
