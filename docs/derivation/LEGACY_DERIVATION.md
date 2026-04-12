# Legacy Seed-to-Key Derivation (Historical Wart Spec)

> **DO NOT "CLEAN UP" THIS CODE.** It is intentionally non-standard. Restoring
> any wallet created by breadwallet-android, digibytewallet-android (DigiByte-Core),
> or this fork prior to a future BIP44 migration depends on every byte of the
> deviations documented below. Read §6 before touching anything.

## §1 Status & Stakes

- **Status:** Load-bearing legacy. Currently the *only* derivation path used by
  this wallet for receive, change, and signing.
- **Stakes:** Funds. A maintainer who "fixes" the HMAC key string, hardens the
  wrong index, or migrates the path template to BIP44 will silently produce a
  different master key from the same BIP39 mnemonic. Old users restoring from
  seed will see an empty wallet and assume the wallet stole their coins.
- **Source of truth (verified):**
  - C library: `DigiByte-Core/digibytewallet-core` @ commit
    `d57c13af512685dd2374d895bd6254483db9388e` (the SHA pinned by
    `DigiByte-Core/digibytewallet-android` master via submodule
    `app/src/main/jni/digibytewallet-core`).
  - Wrapper: `DigiByte-Core/digibytewallet-android` master,
    `app/src/main/jni/transition/`.
  - Fork: `JohnnyLawDGB/digibytewallet-android` head, `native/src/main/jni/bridge/`.
- **Submodule pin warning:** This fork's submodule pointer
  (`native/src/main/jni/jni/digibytewallet-core` → `6bd3005453292d3b7ce9abb30d1431264148a36a`)
  **does not resolve on the public `DigiByte-Core/digibytewallet-core` repo** as
  of the audit date. Before merging anything that depends on the C lib, this
  pin needs to be re-pointed to a public, reviewable commit, or the divergence
  documented as a vendored hard fork. See §6.

## §2 The Deviations

Each subsection cites a confirmed divergence from the BIP32 reference and from
"plain" breadwallet-android (which itself deviates from BIP32; this fork
inherits both).

### §2.1 HMAC-SHA512 key string is `"DigiByte seed"`, not `"Bitcoin seed"`

BIP32 §"Master key generation" specifies the constant ASCII byte string
`"Bitcoin seed"` (12 bytes, `0x426974636f696e2073656564`) as the HMAC-SHA512 key
when computing the master extended key from a seed. This wallet uses
`"DigiByte seed"` (13 bytes, `0x44696769427974652073656564`).

**Citation:** `BRBIP32Sequence.c:31`

```c
#define BIP32_SEED_KEY "DigiByte seed"
```

Used at:
- `BRBIP32Sequence.c:118` — `BRBIP32MasterPubKey`
- `BRBIP32Sequence.c:175` — `BRBIP32PrivKeyList`
- `BRBIP32Sequence.c:217` — `BRBIP32PrivKeyPath`

**Consequence:** Every key in the tree — master, all children, all addresses —
differs from a BIP32-standard derivation over the same seed. A standards-
compliant restore tool (e.g. Electrum, Ian Coleman BIP39 with BIP32 mode) will
produce the wrong addresses for a wallet exported from this app, *even if the
path were also corrected*.

### §2.2 Default derivation path is `m/0'/chain/index` (bread layout), not BIP44

BIP44 prescribes `m/44'/20'/account'/change/index` for DigiByte (coin type 20).
breadwallet's original layout — inherited unchanged here — is
`m/0'/chain/index`: a single hardened account at index 0 directly under the
master, with the chain (0=external/receive, 1=internal/change) as an unhardened
child, and the address index as another unhardened child.

**Citation:** `BRBIP32Sequence.c:157–161`

```c
// sets the private key for path m/0H/chain/index to key
void BRBIP32PrivKey(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index)
{
    BRBIP32PrivKeyPath(key, seed, seedLen, 3, 0 | BIP32_HARD, chain, index);
}
```

The wallet then iterates `chain ∈ {SEQUENCE_EXTERNAL_CHAIN=0, SEQUENCE_INTERNAL_CHAIN=1}`
in `BRWallet.c:362` (`_BRWalletUnusedAddrs`).

The `BRBIP32MasterPubKey` function (§2.3) materializes the `m/0'` node and
exports its public key + chain code as the wallet's "master public key" — so
all subsequent BIP32 derivations of receive/change addresses happen *from
`N(m/0')`*, never re-touching the seed.

**Consequence:** No `purpose'` (44'), no `coin_type'` (20'), no `account'` level.
A BIP44 restore tool pointed at this seed will look at `m/44'/20'/0'/0/0` and
find nothing.

### §2.3 `BRBIP32MasterPubKey` exports `N(m/0')`, not `N(m)`

**Citation:** `BRBIP32Sequence.c:107–108`

```c
// returns the master public key for the default BIP32 wallet layout - derivation path N(m/0H)
BRMasterPubKey BRBIP32MasterPubKey(const void *seed, size_t seedLen)
```

Function signature:
```c
BRMasterPubKey BRBIP32MasterPubKey(const void *seed, size_t seedLen);
```

The returned `BRMasterPubKey` struct contains the `m/0'` fingerprint, chain
code, and compressed pubkey — i.e. the watch-only neutered child at the single
hardened account. This is what gets persisted to disk as the wallet's pubkey
material.

### §2.4 P2PKH version byte: `0x1E` (DGB) vs `0x00` (BTC)

Verified, but *not* a deviation from upstream `DigiByte-Core/digibytewallet-core` —
this is the standard DGB constant. Documented here only because pairing the
right version byte with the wrong derivation is the most common way for an
incorrect "fix" to silently produce plausible-looking but wrong addresses.

**Citation:** `BRAddress.h`

```c
#define DIGIBYTE_PUBKEY_LEGACY                30 // "D"
#define DIGIBYTE_SCRIPT_ADDRESS_LEGACY        5  // "3"
#define DIGIBYTE_SCRIPT_ADDRESS               63
```

`30` decimal = `0x1E`. Used in `BRAddress.c` lines 274, 292, 325, 365, 427.
Bitcoin's equivalent is `0x00` (P2PKH) / `0x05` (P2SH).

### §2.5 Diff: this fork vs DigiByte-Core master

The **C derivation source itself is unchanged** between
`DigiByte-Core/digibytewallet-android` master (submodule pin
`d57c13a…`) and the breadwallet-derived BIP32 logic.

This fork's structural change is at the *wrapper* layer
(`native/src/main/jni/bridge/JNIBIP32Sequence.c` and the Kotlin
`core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`), which reorganizes
the JNI surface but — based on the audit — calls into the same `BRBIP32*`
functions. The submodule pin difference is unverifiable at audit time (see §1
warning); if the pinned C lib has been modified, that diff must be pulled into
this section before this doc is considered complete.

## §3 Exact Spec

Given a BIP39 seed `S` (64 bytes, the output of PBKDF2 over the mnemonic +
passphrase), the wallet computes addresses as follows:

1. **Master key:**
   ```
   I        = HMAC-SHA512(key = "DigiByte seed", msg = S)
   I_L      = I[0..32]
   I_R      = I[32..64]
   k_master = I_L                  (master private key)
   c_master = I_R                  (master chain code)
   ```
2. **Account 0 hardened (`m/0'`):** standard BIP32 CKDpriv with index
   `0x80000000`. Result: `(k_{m/0'}, c_{m/0'})`.
3. **Persisted master pubkey:** `(K_{m/0'}, c_{m/0'}, fingerprint(K_{m/0'}))`
   stored as `BRMasterPubKey`.
4. **Receive address `i`:** CKDpub on the persisted mpk with chain index `0`
   then with index `i` (both *unhardened*). Encode as P2PKH with version byte
   `0x1E`. (Or, when `defaultAddressFormat = 2`, encode as bech32 `dgb1…`
   over the same pubkey hash — the *derivation* is identical, only the
   address encoding differs.)
5. **Change address `i`:** same as (4) but with chain index `1`.

**Path summary:** `m/0'/{0|1}/i`, hardening only at the account level.

## §4 Dual-Scan Recovery Algorithm

**Audit finding (Step 5):** No dual-scan recovery code, no "legacy scan"
branch, and no `3.5.1` version reference exists in this fork's
`core/`, `app/`, or `native/` Kotlin/Java sources as of the audit. A grep
across `**/*.kt` and `**/*.java` for `legacy`, `dual`, `recovery`, `3.5.1`
returns only address-format enums and unrelated comments.

**Implication:** This section is currently a **placeholder for work not yet
done**, not documentation of existing behavior. Before this fork ships any
restore UX that needs to recover wallets created by older builds with a
*different* derivation, a dual-scan algorithm has to actually be written.
The intended shape (subject to revision once the historical bread/DGB
versions are catalogued):

1. Derive candidate master via §3 (`"DigiByte seed"`, `m/0'`) — current spec.
2. In parallel, derive candidate master via any documented prior variant
   (e.g. `"Bitcoin seed"`, `m/0'/0/i` — bread original).
3. For each candidate, generate the first `gapLimit` external + internal
   addresses and query the SPV layer for any history.
4. If exactly one candidate has on-chain history → restore as that variant
   and write a flag into wallet metadata recording which derivation was used.
5. If both have history → surface a UI choice; do not silently merge.
6. If neither has history → restore as §3 (current default).

**Do not implement this from this section.** Implement it from a real audit
of every prior shipped APK's `BRBIP32Sequence.c` and update this section with
verified cite-able variants before writing code.

## §5 Test Vectors

Test vectors below use BIP39 standard mnemonics as input. **The "legacy"
column is the spec in §3. The "BIP44-standard" column is what a
spec-compliant tool (e.g. Ian Coleman BIP39, derivation path
`m/44'/20'/0'/0/0`, with the standard `"Bitcoin seed"` HMAC key) produces
from the same mnemonic, for comparison.**

> ⚠️ **Vectors not yet computed.** This audit pass verified the source-level
> spec; producing the test vectors requires running a reference
> implementation against each mnemonic and capturing both columns. This
> table must be filled in (and checked into CI) before this doc is
> considered authoritative. Recommended generator: a small Python script
> using `coincurve` + a hand-rolled `HMAC-SHA512(b"DigiByte seed", seed)`
> for the legacy column and `bip_utils` for the BIP44 column.

| # | Mnemonic | Passphrase | Legacy `m/0'/0/0` (DGB addr) | BIP44 `m/44'/20'/0'/0/0` (DGB addr) |
|---|----------|------------|------------------------------|-------------------------------------|
| 1 | `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about` | `""` | _TBD_ | _TBD_ |
| 2 | `legal winner thank year wave sausage worth useful legal winner thank yellow` | `""` | _TBD_ | _TBD_ |
| 3 | `letter advice cage absurd amount doctor acoustic avoid letter advice cage above` | `TREZOR` | _TBD_ | _TBD_ |

The two columns **must** differ in every row. If they ever match, the spec
or the generator is wrong.

## §6 What Would Break If You "Fixed" This

Each item below describes a plausible "cleanup" PR a future maintainer might
file, and what it would actually do.

1. **"Use the standard BIP32 HMAC key string."** Changing
   `BIP32_SEED_KEY` from `"DigiByte seed"` to `"Bitcoin seed"` regenerates
   every key in the tree. Every existing wallet on a user's device continues
   to function (because the persisted `BRMasterPubKey` is loaded from disk,
   not re-derived) — until that user wipes the app and restores from seed.
   At that point, addresses with funds become unreachable. **Silent fund
   loss for every restoring user.**

2. **"Migrate to BIP44 (`m/44'/20'/0'/0/i`)."** Same failure mode as (1),
   plus the `BRMasterPubKey` itself changes shape (different fingerprint,
   different chain code), so the on-disk wallet file format effectively
   changes too. Without a migration that *also* runs the legacy derivation
   for restore, every restoring user loses funds.

3. **"Harden the chain index" / "harden the address index."** BIP32 hardened
   children require the parent private key, not just the parent chain code.
   The wallet's persisted master pubkey is `N(m/0')` — neutered. Hardening
   `chain` or `i` would make watch-only address generation impossible and
   would, again, change every derived address. Nothing on-disk would
   restore.

4. **"Drop the `m/0'` account level since it's always 0."** Tempting,
   because the account is never configurable. But removing it changes
   `BRMasterPubKey` from `N(m/0')` to `N(m)`, which has a different
   fingerprint and chain code, which means every child key changes.
   Restores break.

5. **"Re-point the submodule to the latest upstream `digibytewallet-core`."**
   Could be safe, could be catastrophic. The submodule pin
   (`6bd3005…` in this fork) is not currently resolvable on the public
   upstream — meaning either it's a private commit, an unpushed local commit,
   or a deleted branch. Before any submodule bump, diff the new commit
   against `d57c13a…` (the DigiByte-Core master pin) and confirm the
   constants in §2.1, §2.2, §2.3, §2.4 are byte-identical. If they aren't,
   the bump *is* a derivation change and needs the full §4 dual-scan
   treatment.

6. **"Switch the default address format to bech32 to save fees."** Safe.
   The address *encoding* is independent of the derivation; only the last
   step of §3 changes. Existing legacy `D…` addresses remain spendable
   because the underlying private keys are unchanged. This is the *only*
   "modernization" on this list that doesn't risk funds — but document the
   format flip prominently in release notes so users don't think their old
   addresses disappeared.

---

*Audit performed against DigiByte-Core/digibytewallet-android master (submodule
`digibytewallet-core` @ `d57c13af`) and JohnnyLawDGB/digibytewallet-android head.
Section §2.5 and §5 contain explicit unverified gaps that must be closed before
this doc is treated as ground truth.*
