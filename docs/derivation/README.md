# Derivation Specs

This directory documents every key derivation scheme this wallet uses, has used,
or will use. **Read the relevant doc before touching any code that turns a seed
into a key.** The specs here are load-bearing for restore — getting them wrong
loses funds.

## Index

| Doc | Status | Scope |
|-----|--------|-------|
| [`LEGACY_DERIVATION.md`](./LEGACY_DERIVATION.md) | ✅ Drafted (with marked gaps in §4 and §5) | The current, breadwallet-inherited, non-BIP44 derivation: HMAC key `"DigiByte seed"`, path `m/0'/{0\|1}/i`, P2PKH version byte `0x1E`. This is what every existing wallet uses today. |
| `BIP84_DERIVATION.md` | 📋 Placeholder — not yet written | Planned native-segwit derivation at `m/84'/20'/0'/{0\|1}/i` with `dgb1…` bech32 encoding. Will require dual-scan recovery alongside `LEGACY_DERIVATION.md` once shipped. |
| `DIGIDOLLAR_DERIVATION.md` | 📋 Placeholder — not yet written | Planned DigiDollar account derivation. Path, HMAC variant, and address encoding TBD. Will need its own §6-style "what would break" analysis before any implementation lands. |

## Conventions

- Every spec doc must include: §1 Status & Stakes, §2 The Deviations (with
  `file:line` citations against a pinned commit SHA), §3 Exact Spec, §4 Recovery
  Algorithm, §5 Test Vectors, §6 What Would Break If You "Fixed" This.
- Test vectors must use BIP39 standard mnemonics as input and must be
  reproducible from a checked-in generator script. CI must verify them.
- Any change to a derivation constant (HMAC key string, path, hardening flag,
  version byte) is a **breaking change requiring a dual-scan migration** —
  never a routine refactor. PRs touching these constants without an updated
  spec doc + migration plan should be rejected on sight.

## Out of scope here

Address encoding (legacy `D…` vs P2SH `S…` vs bech32 `dgb1…`) is independent
of derivation and is documented separately under `docs/addresses/`. The same
private key can be encoded in any of the three formats; only the *encoding*
step changes.
