# The passphrase is an immutable JVM String — scoping the fix

**Status:** scoped, not implemented
**Date:** 2026-08-28
**Found by:** changed-surface security audit of v4.0.63–v4.0.66
**Severity:** P0 by invariant, P2 by exploitability — see §3, which is the whole
argument and should be read before anyone spends a day on this.

---

## 1. The invariant that was broken

`CLAUDE.md:51`, recording the CRITICAL-3 remediation:

> `loadSeed()` returns `ByteArray` (not String) — mnemonic never becomes immutable
> JVM heap object

A JVM `String` cannot be zeroed. It lives on the heap until GC decides otherwise,
may be copied by GC compaction, and can be captured by a heap dump or by anything
that can read the process. The mnemonic path was deliberately rebuilt around
`ByteArray` + `fill(0)` for exactly that reason, and `jni_seed_buffer.h` exists to
carry the same discipline across the JNI boundary.

The passphrase introduced in v4.0.66 is `String` at every hop:

| Hop | File |
|---|---|
| UI entry, both fields | `PassphraseSection.kt:47` |
| Normalisation | `Bip39Passphrase.prepare(raw: String?): String?` |
| Wallet creation | `WalletManager.createWallet(…, passphrase: String?)` |
| Recovery | `WalletManager.recoverWallet(…, passphrase: String?)` |
| Persistence | `WalletManager.persistPassphrase(String?)` |
| Reload | `WalletManager.loadPassphrase(): String?` |
| Foreign restore | `RecoverFundsViewModel.pendingForeignPassphrase: String?` |
| Scan | `RecoveryScanService.scan(…, passphrase: String?)` |
| Sweep | `LegacySweepService(…, passphrase: String?)` |
| JNI | `NativeBridge.createWalletFromBytes(…, passphrase: String?)` → `jstring` |

The mnemonic and the passphrase together ARE the wallet. Protecting one as
carefully as this codebase does and the other not at all is not a defensible
split — whichever way the risk is judged, it should be judged the same way for
both.

## 2. What a fix costs

`CharArray` through Kotlin, `jbyteArray` at the boundary, mirroring
`jni_seed_buffer.h`. Concretely:

- Compose `TextField` yields `String`. The UI hop cannot be fully avoided without
  a custom field; the realistic goal is to shorten the window, not close it.
- `NativeBridge` signatures change again — `SeedIsolationTest` pins them by
  reflection, so that moves too.
- `loadPassphrase()` must return `ByteArray` and every caller must zero it,
  including the two derivation sites and the SeedView reveal.
- `Bip39Passphrase.prepare` normalises via `java.text.Normalizer`, which is
  String-only. NFKD on a `CharArray` means normalising a String and immediately
  discarding it — a window that cannot be removed, only narrowed.

That last point matters: **a complete fix is not reachable.** NFKD normalisation
and Compose text entry both require a String. The honest target is fewer and
shorter-lived copies, not zero.

## 3. Why this may not be worth doing

Stated plainly so the decision is made on the facts rather than on the word
"invariant":

- The passphrase is stored in the **same Keystore envelope as the mnemonic**. An
  attacker who can read the app's heap can also read the decrypted mnemonic. The
  String adds an in-memory window; it does not add a new way in.
- `CLAUDE.md` already records CRITICAL-1's open residual: no Keystore user-auth
  binding, so a compromised app process can decrypt the seed anyway. The threat
  model that makes the String matter is one where that door is already open.
- The mnemonic's `ByteArray` discipline was earned against a specific finding
  (CRITICAL-3), not adopted as a general principle.

Against that: the invariant is documented, a reader will reasonably assume it
holds for all seed material, and "the other secret is also exposed" is an argument
for fixing both, not for matching the weaker one.

## 4. Options

**(a) Do nothing, document it.** Amend `CLAUDE.md:51` to say the ByteArray
guarantee covers the mnemonic and NOT the passphrase, with the reasoning in §3.
Cheapest, and leaves the codebase honest. Risk: the next person extends the
feature assuming the guarantee holds.

**(b) Narrow the window.** Keep `String` at the UI and normalisation hops, switch
storage and the JNI boundary to `ByteArray`, zero after each use. Gets the
long-lived copies — `pendingForeignPassphrase`, `loadPassphrase()`'s return, the
ViewModel field — off the heap. Leaves the transient UI copy.

**(c) Full CharArray refactor.** Highest cost, still cannot close the
normalisation window, and touches code that shipped hours ago.

**Recommendation: (b).** It removes the copies that persist for the lifetime of a
screen or a scan, which is where the actual exposure is, and it does not pretend
to a guarantee §2 shows is unreachable. (a) is defensible if the security cycle
has more valuable work queued — but then it must actually amend CLAUDE.md, not
just be decided and forgotten.

## 5. Gate

Whichever option: `SeedIsolationTest` gains a case asserting the passphrase hop
types, so the decision is pinned in a test rather than in this document.
