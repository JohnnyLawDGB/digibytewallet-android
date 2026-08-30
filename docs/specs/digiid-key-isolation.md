# Digi-ID key isolation — per-site identity derivation

**Status:** In progress (branch `feat/digiid-key-isolation`)
**Provenance:** Security audit CRITICAL-4 residual → THREAT_MODEL known-limitation #6 →
ROADMAP Phase 2 (top slot since duress-PIN cancellation, 2026-08-19 sequencing).

## Problem

Every Digi-ID site sees the same identity: `signMessage()` hardcodes
`seed_derive_key(chain=0, index=0)` → `m/0'/0/0` (`jni_wallet_sign.c:85`). The cost is
**cross-site linkability** (one address for every site), plus the two lesser residuals the
roadmap records: a restored bread-wallet seed whose `m/0'/0/0` held funds gets an identity
with public on-chain history, and the recoverable-signature scheme publishes that pubkey
permanently. It is NOT key exposure — the signed-message prefix domain-separates from
sighashes, and app-created wallets never fund `m/0'`.

## Design

### Derivation — SLIP-0013 (the BitID scheme the roadmap names)

For a site with canonical callback URI `U` (scheme://host/path — the `callbackUrl` that
`DigiIdRequest.parse` builds, which strips the query and therefore the per-login nonce)
and index `i = 0`:

```
h = SHA256( LE32(i) || U )
A,B,C,D = first 16 bytes of h as four little-endian uint32
path = m/13'/A'/B'/C'/D'      (all hardened)
```

Key → compressed pubkey → legacy P2PKH address (same address format the current code
emits with `addressFormat=0`), then the existing `\x19DigiByte Signed Message:\n`
compact-recoverable signature. Verified against the SLIP-0013/BitID test vector
(`http://bitid.bitcoin.blue/callback`, i=0 → `13'/0xbe553112'/0xc0af82cf'/0x4361fb3b'/0xedd2bf37'`).

The pure derivation (`uri → four hardened indexes`) lives in `bridge/slip13.{c,h}` with no
JNI dependency so a host KAT can compile it directly; `jni_wallet.c` exposes
`seed_derive_identity_key(BRKey*, const char *uri, uint32_t index)` as the only seed-touching
entry (same encapsulation contract as `seed_derive_key` — CRITICAL-2 remediation style),
using `BRBIP32PrivKeyPath(key, seed, 64, 5, …)`.

### Compatibility — who still gets the legacy key

Switching an already-registered site to a per-site key would lock the user out of that
account (sites bind accounts to the address they saw at signup; api.digiscope.me binds
`admin_users.digibyte_address` + `user_addresses`). Policy, decided per login by
`IdentityKeyPolicy` (pure Kotlin, unit-tested):

| Case | Key |
|---|---|
| DigiScope domains (`digiscope.me`, `api.digiscope.me`, `*.digiscope.me`) | **Legacy `m/0'/0/0`** — the Hub identity. `DigiScopeClient.quickLogin` and Hub content signing are untouched; the wallet's own backend account keeps its address. |
| Domain has a **successful legacy login in Digi-ID history** | **Legacy** — grandfathered so existing site accounts keep working. History is never pruned in production (`pruneOlderThan` has no production caller), so the grandfather list is stable. |
| Everything else (any new domain) | **Per-site SLIP-0013** — unlinkable across sites. |

`digiid_history` gains a `derivation` TEXT column (`'legacy'`/`'site'`, Room migration
8→9, existing rows backfilled `'legacy'`) so the policy reads recorded fact, not
inference, and the history UI can label identities later.

### Trade-offs recorded

- **Callback-path dependence** (inherent to BitID/SLIP-0013): if a site relocates its
  callback path, the derived identity changes. The spec accepts this — it is the standard
  scheme, and the history row pins what we used.
- **No re-binding flow yet.** Grandfathered sites stay on the legacy key indefinitely;
  migrating them needs per-site account re-binding (DigiScope could use `user_addresses`
  linking). Deliberately out of scope — zero backend coordination in this phase, which is
  what made the item "coordination-heavy" before.
- The legacy key remains derivable forever (restores, grandfathered sites) — this change
  stops NEW linkage, it cannot unpublish old signatures.

## Deliverables

1. `bridge/slip13.{c,h}` — pure SLIP-0013 index derivation; host KAT
   `slip13_identity_kat` (vector + determinism + URI separation + index separation).
2. `seed_derive_identity_key` accessor (`jni_wallet.c`, decl in `jni_bridge.h`).
3. JNI `signIdentityMessage(message, siteUri, index)` in `jni_wallet_sign.c`
   (shared signing core factored out of `signMessage`, which keeps its exact behavior).
4. `NativeBridge.signIdentityMessage` extern.
5. `IdentityKeyPolicy` + `DigiIdManager` wiring + DAO query; history rows record the
   derivation used.
6. Room migration 8→9 + MigrationTest case; `IdentityKeyPolicyTest` unit tests.
7. THREAT_MODEL #6 + ROADMAP Phase 2 updated in the same PR (standing obligation).
