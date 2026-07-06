# DigiDollar transactions: Kotlin builds the bytes, C signs the digests

## Context

DigiDollar (DigiByte Core v9.26.4 softfork) requires taproot machinery this wallet has none of:
BIP340 Schnorr, BIP341 sighash/output construction, BIP342 tapscript, BIP86 derivation, bech32m.
The C core's secp256k1 submodule is pinned to a commit that predates the `schnorrsig` module
entirely.

Every existing signing path routes through the C core, and the wallet's seed-security
architecture (CRITICAL-2/3 remediations) deliberately keeps key material out of the JVM heap:
`g_seed` is static to `jni_wallet.c` behind an accessor API.

A complete, differentially-proven reference implementation exists: `digidollar-js` in the
`dgb-support` repo, with byte-exact mint/transfer/redeem fixtures proven satoshi-identical to
Core-built transactions on regtest.

## Decision

Split construction from signing:

- **Kotlin** ports the keyless parts of `digidollar-js` — nVersion envelope, OP_RETURN metadata,
  taproot output construction (NUMS + MAST collateral, key-path DD-token), BIP341/BIP143 sighash,
  serialization, coin selection — as a pure, deterministic layer with zero I/O, validated
  byte-for-byte against the dgb-support fixtures.
- **C** gains a minimal new accessor surface only: bump the secp256k1 submodule and enable
  `schnorrsig` + `extrakeys`; add seed accessors that derive BIP86 keys, apply the BIP341 tap
  tweak, and BIP340-sign a 32-byte digest handed across JNI. Private keys never cross the JNI
  boundary in either direction.

## Considered options

- **Everything in C** (consistent with the existing send path). Rejected: the largest and
  riskiest C diff (full BIP340/341/342/86 + bech32m in a 2015-era codebase), and the JSON
  fixtures are much harder to exercise from C than from Kotlin unit tests.
- **Everything in Kotlin** (straight digidollar-js port including keys). Rejected: seed and
  private keys would become JVM heap objects, undoing the CRITICAL-2/3 security posture.
- **Kotlin build + C sign (chosen).** Fixture-testable where testing is cheap, key-safe where
  keys already live. Mirrors the existing `jni_asset_send` pattern (Kotlin-prepared outputs,
  C-held keys).

## Consequences

- We reimplement consensus-critical byte construction outside Core. Mitigation is a hard CI
  gate: Kotlin-built mint/transfer/redeem transactions must be byte-identical to the dgb-support
  fixtures, the native signer must pass the official BIP340/341 test vectors, and a manual
  regtest end-to-end (mint, transfer, redeem accepted by a v9.26.4 node) is required before any
  release exposes the UI.
- The sighash digest crosses JNI in the clear. It is not key material — possessing it does not
  enable signing anything else — but the C accessor signs whatever digest it is given, so the
  Kotlin layer's correctness is what binds signatures to intended transactions (the same trust
  the existing `createTransaction`/`signTransaction` split already places in the JNI caller).
- The secp256k1 submodule bump touches the wallet's most sensitive dependency and must be
  reviewed and pinned deliberately (release tag, not a floating branch).
