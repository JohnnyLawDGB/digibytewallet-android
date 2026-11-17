# Coinomi-compatible Digi-ID — design

Status: owner decisions taken 2026-11-17 (all four below); for owner approval of this text.
Follows: `docs/superpowers/specs/2026-11-16-multi-wallet-design.md` (Non-goals: "Coinomi-compatible
Digi-ID ... has its own design next"). Builds on `docs/specs/digiid-key-isolation.md`.

## Goal

A user who restores a **Coinomi** phrase in HoldFast signs in to every Digi-ID site, DigiScope
included, **as the same account Coinomi signed in to**, and sees the DGB Coinomi received, without
any server learning their addresses.

## What Coinomi does (measured 2026-11-17)

A throwaway Coinomi wallet on the public BIP39 test phrase (`abandon` ×11 `about`, no passphrase)
signed three Digi-ID challenges through a temporary DigiScope probe route. All three signatures
recover the addresses Coinomi reported, and exactly one derivation reproduces all three, in pure
Python and in this repo's C core:

- **Key:** SLIP-0013. `h = SHA256(LE32(0) || U)`; key = `m/13'/A'/B'/C'/D'`, every step hardened,
  where A..D are `h`'s first 16 bytes as four little-endian uint32. BIP32 master key from the BIP39
  seed with the **standard constant `"Bitcoin seed"`**. Compressed key, legacy P2PKH `D…` address.
- **U:** the callback URL Coinomi posts to, `https://<host><path>`: no query, so no nonce; no port; no
  trailing slash. Host and path both change the key.
- **Signature:** DigiByte signed message over the full signed `uri` (query included), base64, 65 bytes.

| U | Address |
|---|---|
| `https://api.digiscope.me/api/digiid-probe/callback` | `DSSfzAosbs1mBJE4rZPtZHGuwjHorJDNau` |
| `https://api.digiscope.me/api/auth/digiid/callback` | `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri` |
| `https://digiscope.me/api/digiid-probe/callback` | `DA3TaNPPTHwStZp3K98sGzk7aaQ8sh7sBo` |

The phrase is public by definition, so these rows are committed as a test vector as they are.

## Why HoldFast does not match today

`BRDigiIdSiteKey` (`BRDigiId.h`), used for every site `IdentityKeyPolicy` sends to `PER_SITE`, already
hashes the same `U` at the same index along the same path. It goes through `BRBIP32PrivKeyPath`,
whose master key is **`"DigiByte seed"`** (`BRBIP32Sequence.c:31`, the bread-wallet tree). That
constant is the only difference: with it, the first row gives `DHawr8VSh4T2iEM9NxJdubbDTra31RKU94`.

`"DigiByte seed"` per-site identities are not only HoldFast's. The original DigiByte mobile app
signed per-site with the same bread code, and io.digibyte 3.x–4.0.76 used the shared `m/0'/0/0`
(`IdentityKeyPolicy.offersChoice`). So the identity a restored phrase needs depends on the app it
came from, and changing the constant in place would move every existing wallet's per-site identity
and lock it out of the sites it uses. It is a per-wallet property, never a global swap.

## Owner decisions (2026-11-17)

1. **A per-wallet setting**, offered when a phrase is restored and changeable later in Settings. Not
   a choice on each sign-in screen.
2. **A Coinomi-mode wallet signs DigiScope with its Coinomi key**, so a restored Coinomi user lands in
   the DigiScope account Coinomi created, not in a new one.
3. **New wallets use the standard constant** for their per-site identities. Existing wallets keep
   `"DigiByte seed"`.
4. **A Coinomi-mode wallet watches the Coinomi fund paths privately**, by compact filters.

## Design

### The two per-wallet properties

Each wallet record carries two values, written once and changed only by the user in Settings:

- **`siteKeyRoot`** — the BIP32 master-key constant for per-site identities: `DIGIBYTE` (`"DigiByte
  seed"`) or `STANDARD` (`"Bitcoin seed"`).
- **`digiScopeIdentity`** — what DigiScope is signed with: `SHARED` (`m/0'/0/0`, today) or `SITE`
  (the per-site key of DigiScope's one login callback, below).

"Coinomi-compatible Digi-ID" in the UI is the pair `STANDARD` + `SITE`. Nothing else in the app reads
the word Coinomi.

| How the wallet got here | `siteKeyRoot` | `digiScopeIdentity` |
|---|---|---|
| Upgraded from 4.1.x (legacy and every step-1 wallet) | `DIGIBYTE` | `SHARED` |
| Created on this device after this ships | `STANDARD` | `SHARED` |
| Restored, "HoldFast or the DigiByte app" | `DIGIBYTE` | `SHARED` |
| Restored, "Coinomi" | `STANDARD` | `SITE` |
| Restored, "another wallet" or "not sure" | `STANDARD` | `SHARED` |

A missing value reads as the upgrade row, so an old record never changes identity. These live in the
wallet's own store (`WalletStores` class WALLET), so they follow multi-wallet's per-wallet rules: a
wipe removes them, a switch reads the other wallet's.

### Core (shared with iOS)

- `BRDigiIdSiteKey` takes the master-key constant as an argument (an enum, not a string), and the
  bridge passes the wallet's `siteKeyRoot`. `BRBIP32BitIDKey` is left alone and is not used.
- **Characterize before editing** (this repo's rule for shared crypto): first commit a KAT that pins
  today's `"DigiByte seed"` outputs for the three `U`s above and for the existing Digi-ID vectors;
  it must stay green through the change. Then add the `STANDARD` rows from the table, which must fail
  on the unchanged code (red before green) and pass after.
- The URI bytes rule in `BRDigiId.h` (verbatim, `BRDigiIdUriBytesFromUtf16`) is unchanged; the probe
  shows Coinomi hashes the same `https://host/path` string `DigiIdRequest.callbackUrl` builds.

### App: one DigiScope identity, everywhere

DigiScope is signed in three places today, and all are hard-wired to `m/0'/0/0`: the Digi-ID login
(`DigiIdManager`, `IdentityKeyPolicy` sends DigiScope hosts to `LEGACY`), Hub quick-login
(`DigiScopeClient.signWithSharedIdentity`, `signMessage(…, 0)`) and Hub content signing
(`HubIdentity.sign`, `signMessage(…, 1)`), with `HubIdentity.address()` reading `m/0'/0/0`'s address.
They become one function, `DigiScopeIdentity` (core), that answers the address and signs for the
active wallet:

- `SHARED`: exactly today's key and calls.
- `SITE`: the per-site key of `U = https://api.digiscope.me/api/auth/digiid/callback` under the
  wallet's `siteKeyRoot`. That is DigiScope's single login and step-up callback (compendium #399: there
  is one callback URL, so one key); a Coinomi user's existing account is bound to exactly this address
  (`DSsC7WWi…` in the vector).

No other code may sign for a DigiScope host. A source gate fails the build on a `signMessage` or
`signIdentityMessage` call outside `DigiScopeIdentity` and `DigiIdManager`.

**To verify on the server before building (compendium):** that Hub quick-login and content-signature
checks accept any address in the account's `user_addresses`, not only the first. The login does
(auto-provision binds the address it saw). If they do not, the server change lands first.

### App: the policy

`IdentityKeyPolicy.choose` keeps its rules and gains the wallet's two properties:

- DigiScope hosts: `digiScopeIdentity` decides (`SHARED` → `LEGACY`, `SITE` → `PER_SITE` with the
  fixed `U` above).
- A domain with a successful legacy login on record stays `LEGACY` (grandfathering, unchanged).
- Every other domain: `PER_SITE` under `siteKeyRoot`.
- The existing sign-time choice for restored wallets (`offersChoice`, 4.1.2 row 16) stays as it is
  and offers the shared key or the per-site key under `siteKeyRoot`.

### UI (every language in `AppLocale.SUPPORTED`, same commit)

- **Restore, after the phrase:** "Where is this phrase from?" HoldFast or the DigiByte app / Coinomi /
  another wallet / not sure. One line each on what it changes ("signs in to sites as Coinomi did").
  First-run restore and multi-wallet add mode both show it.
- **Settings → Digi-ID → "Coinomi-compatible sign-in"** for the active wallet. Turning it on or off
  first says, in plain words, that every site will see a different identity, sign-ins made the other
  way stop working, and the Hub signs this wallet out. Then it clears this wallet's Hub session and
  Digi-ID history records, so nothing grandfathers across the change.
- The Digi-ID approval screen shows the identity address it will sign with (it does today) and, for
  a Coinomi-mode wallet, says so.

### Funds on Coinomi's paths

The restore already finds Coinomi's DGB through **Recover funds** (BIP44 `m/44'/20'/0'` and BIP49
`m/49'/20'/0'`, both proven on mainnet in `docs/RESTORE-VERIFICATION.md`), but only as a one-time
sweep, by sending the addresses to the reconcile server, after a warning, and multi-wallet's add mode
skips it.

For a Coinomi-mode wallet the sync engine also **watches** both accounts, receive and change chains
with the usual gap limit, through compact filters on the phone. Its balance, history and spends
include them, and nothing is sent to a server. Coins there are spent like any other: P2PKH and
P2SH-P2WPKH signing are both in `BRTransactionSign`; change still goes to the wallet's own BIP84
chain. Recover funds stays the opt-in quick check, and the restore offers it for a Coinomi phrase in
both first-run and add mode.

Watching extra account chains per wallet is engine work. It lands **with multi-wallet step 2**, which
rebuilds the watch set per wallet anyway, not before.

## Order of work

1. Core: characterization KAT, then the master-key argument and the `STANDARD` vectors.
2. App: the two properties, `DigiScopeIdentity` and its source gate, the policy. Server check first.
3. UI: the restore question and the Settings toggle, all languages.
4. With multi-wallet step 2: watching the Coinomi fund paths.

Steps 1–3 ship on their own: a restored Coinomi user signs in correctly from that release, and finds
funds through Recover funds until step 4.

## Non-goals

- Other wallets' Digi-ID schemes. "Another wallet" gets the standard per-site key, which is what
  SLIP-0013 specifies; a wallet that does something else gets its own measurement first.
- Watching Coinomi's other coins, or DGB on accounts other than `0'`.
- Migrating a site from one identity to another. Linking a new address to an existing account is
  DigiScope's "My Account → addresses", not the wallet's.

## Anti-goals (a failure even if it works)

- **An identity moves without the user turning a setting.** No upgrade, restore default or code
  change may alter which address an existing wallet signs any site with.
- **A server learns the wallet's addresses** to make Coinomi funds appear, outside the opt-in Recover
  funds screen.
- **Two places decide the DigiScope key.** If the login and the Hub can disagree, the Hub rejects half
  of what the wallet signs.
