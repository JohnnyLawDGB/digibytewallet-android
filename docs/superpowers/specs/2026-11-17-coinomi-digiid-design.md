# Coinomi-compatible Digi-ID — design

Status: **APPROVED by the owner 2026-11-17** (text and all four decisions below).
Amended 2026-11-17 with the owner's OK, after review of #266 and #267:
- Fund watching is its own per-wallet setting, separate from sign-in, with an add-only writer.
- **Decision 3 is reversed:** every HoldFast wallet keeps the DigiByte root (owner, after review found
  that a phrase cannot say which HoldFast version made it).
- The policy adopts Q4 A (owner, 2026-11-10): the shared key only on the fixed DigiScope list, with
  no login-history rule and no restore-time choice.
- Turning Coinomi-compatible sign-in off has a defined result.
- The URI rule is frozen as written for both roots, and Coinomi parity for unmeasured spellings is
  not claimed.
- Also recorded: the DigiScope server check (works as is) and the core API decided for step 1.
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
3. **Every HoldFast wallet keeps the DigiByte root** (`"DigiByte seed"`) for its per-site identities:
   created, upgraded, or restored as "HoldFast or the DigiByte app" or "not sure", on every device.
   The standard root is used only when the user says the phrase came from Coinomi or another wallet.
   *Reversed 2026-11-17 (owner). The first text gave new wallets the standard root. Review showed a
   phrase cannot tell which HoldFast version made it: restoring such a phrase under the true answer
   "HoldFast" would have moved every per-site identity, and the same seed would have signed
   differently on iOS.*
4. **A wallet restored from a Coinomi phrase watches the Coinomi fund paths privately**, by compact
   filters, whatever its sign-in setting.

## Design

### The per-wallet properties

Each wallet record carries three values. They are written with the wallet record, in the same commit,
and after that only the user changes them, in Settings:

- **`siteKeyRoot`** — the BIP32 master-key constant for per-site identities: `DIGIBYTE` (`"DigiByte
  seed"`) or `STANDARD` (`"Bitcoin seed"`).
- **`digiScopeIdentity`** — what DigiScope is signed with: `SHARED` (`m/0'/0/0`, today) or `SITE`
  (the per-site key of DigiScope's one login callback, below).
- **`watchedAccounts`** — the other apps' accounts the sync engine also watches for this wallet:
  empty, or Coinomi's two (BIP44 `m/44'/20'/0'` and BIP49 `m/49'/20'/0'`, "Funds on Coinomi's paths"
  below).

"Coinomi-compatible sign-in" in the UI is the pair `STANDARD` + `SITE`. Turning it off never removes
anything from `watchedAccounts`: sign-in compatibility must never hide funds or take them out of the
spend path (review finding P1 on #266). Nothing else in the app reads the word Coinomi.

| How the wallet got here | `siteKeyRoot` | `digiScopeIdentity` | `watchedAccounts` |
|---|---|---|---|
| Upgraded from 4.1.x (legacy and every step-1 wallet) | `DIGIBYTE` | `SHARED` | empty |
| Created on this device | `DIGIBYTE` | `SHARED` | empty |
| Restored, "HoldFast or the DigiByte app" | `DIGIBYTE` | `SHARED` | empty |
| Restored, "not sure" | `DIGIBYTE` | `SHARED` | empty |
| Restored, "Coinomi" | `STANDARD` | `SITE` | Coinomi's two |
| Restored, "another wallet" | `STANDARD` | `SHARED` | empty |

**`watchedAccounts` only grows (add-only).** Two actions add Coinomi's two accounts, and neither
removes anything:
- the restore answer "Coinomi";
- **Settings → "Also watch Coinomi's accounts"** for the active wallet. This is for a wallet that
  restored a Coinomi phrase before this ships, or answered otherwise at restore. Turning on
  Coinomi-compatible sign-in offers it too.

Only removing the wallet or the wipe empties it. Watching an account that holds nothing costs a
little filter matching and nothing else.

A missing value reads as the upgrade row, so an old record never changes identity. These live in the
wallet's own store (`WalletStores` class WALLET), so they follow multi-wallet's per-wallet rules: a
wipe removes them, a switch reads the other wallet's.

### Core (shared with iOS)

- `BRDigiIdSiteKey`, `BRDigiIdSignSite` and `BRDigiIdAddress` take a **required** root, an enum
  placed after `seedLen`: `BRDigiIdRoot`, `DIGIBYTE` = 1, `STANDARD` = 2. Zero and every other value
  are refused, with the key wiped. There is no root-less variant, so no caller can sign per-site
  without choosing. The shared identity (`BRDigiIdSharedKey`, `BRDigiIdSignShared`) takes none.
  `DIGIBYTE` keeps today's derivation call; `STANDARD` derives through `BRBIP32PrivKeyArrayPath` with
  `BR_DERIVATION_HMAC_STANDARD` (`BRDerivationProfiles.h`), so neither string gains a copy.
  `BRBIP32BitIDKey` is left alone and is not used. (This is the API as decided for step 1. It exists
  once step 1's core PR merges.)
- **One implementation, step 2.** Android's bridge does not call `BRDigiId.h` today: `signIdentityMessage`
  and `identityAddress` derive through the bridge's own `seed_derive_identity_key`. In step 2 those
  two natives move onto `BRDigiIdSignSite` and `BRDigiIdAddress` and pass the wallet's `siteKeyRoot`.
  The bridge never gains a second, Android-only `STANDARD` derivation ("Funds in the core";
  `docs/development-process.md`), so iOS and Android sign from one copy.
- **Landing (owner, 2026-11-17).** The signatures change, so Android `develop`'s host KAT cannot
  compile against the merged core until the pin moves. A dispatch run of core CI on `develop`
  against the companion Android branch is the green run the pin waits for
  (`docs/development-process.md` rule 7). iOS passes `DIGIBYTE` at its four call sites at its next pin
  move. The hand-off is in this repo's `docs/handoffs/`, whose first file arrives with step 1.
- **Characterize before editing** (this repo's rule for shared crypto): first commit a KAT that pins
  today's `"DigiByte seed"` outputs for the three `U`s above and for the existing Digi-ID vectors;
  it must stay green through the change. Then add the `STANDARD` rows from the table, which must fail
  on the unchanged code (red before green) and pass after.
- The URI bytes rule in `BRDigiId.h` (verbatim, `BRDigiIdUriBytesFromUtf16`) is unchanged, and it is
  **frozen as written for both roots** from the first release that signs with them. Changing it later
  would move identities already in use (anti-goal 1).
  - For the three measured callbacks (no port, no trailing slash, lower-case host, non-empty path),
    Coinomi hashes the same `https://host/path` string `DigiIdRequest.callbackUrl` builds.
  - **Unmeasured (review finding P2 on #266):** a callback with an explicit port (`:443`), a trailing
    slash, upper-case letters in the host, or an empty path. HoldFast keeps every one of these as
    written (`DigiIdRequestTest`; `digiid_core_kat` pins them as distinct identities). Whether
    Coinomi does too is not known, so Coinomi parity is **not claimed** for these spellings, and no
    test vector asserts it.
  - Measuring needs probe scans for each spelling. The probe issues only its own path, so the port
    and slash variants must be hand-built QRs, and the letter-case and empty-path variants need a
    probe change. If a measurement ever differs, the frozen rule still stands: that site does not
    reach Coinomi's account, and nothing in use moves.

### App: one DigiScope identity, everywhere

DigiScope is signed in three places today, and all are hard-wired to `m/0'/0/0`: the Digi-ID login
(`DigiIdManager`, `IdentityKeyPolicy` sends DigiScope hosts to `LEGACY`), Hub quick-login
(`DigiScopeClient.signWithSharedIdentity`, `signMessage(…, 0)`) and Hub content signing
(`HubIdentity.sign`, `signMessage(…, 1)`). `HubIdentity.address()` is documented as `m/0'/0/0`'s
address but is not (below). They become one function, `DigiScopeIdentity` (core), that answers the
address and signs for the active wallet:

- `SHARED`: today's key and calls. The address is `m/0'/0/0`'s P2PKH address (the bridge's
  `identityAddress(null, 0)`), the address it signs with.
- `SITE`: the per-site key of `U = https://api.digiscope.me/api/auth/digiid/callback` under the
  wallet's `siteKeyRoot`. That is DigiScope's single login and step-up callback (compendium #399: there
  is one callback URL, so one key); a Coinomi user's existing account is bound to exactly this address
  (`DSsC7WWi…` in the vector).

No other code may sign for a DigiScope host. A source gate fails the build on a `signMessage` or
`signIdentityMessage` call outside `DigiScopeIdentity` and `DigiIdManager`.

Why the three must move together: after every successful DigiScope QR login or step-up,
`DigiIdManager` runs `quickLogin()`, which today always signs with `m/0'/0/0`. A Coinomi-mode wallet
that switched only the QR signer would have quick-login provision a new account, and
`DigiScopeClient.login` would store that account's token over the right one: a silent account switch.

`DigiScopeIdentity.address()` replaces `HubIdentity.address()`. That function is documented as
`m/0'/0/0`'s address but calls `getReceiveAddress(0, 0)`, which ignores its index and returns the
wallet's first unused receive address. `ChatView` compares it with each message's author address
(the account's primary), so the chat likely never marks your own messages as yours today. That was
found by reading the code and has not been reproduced on a device.

**Server check, answered 2026-11-17 (compendium `274720ac`, unchanged at `006e6ea7`):** no server change
is needed. The login callback and quick-login check the signature against the address sent, then
accept any address linked to the account, primary or not. Step-up accepts any address linked to that
account. Hub chat signatures are stored without a check (the session decides). The one precondition
is on the wallet: its per-site address must equal Coinomi's, which is the primary address of the
account Coinomi's own login created. Any other address gets a new account (auto-provision, within
the per-IP and global limits) or a 401 at step-up. Posting rights (Trust L2) belong to the account,
so a Coinomi-mode wallet posts with the Coinomi account's level.

### App: the policy

The policy is **Q4 A** (owner, 2026-11-10, recorded in the iOS repo's `docs/decisions.md`; iOS
hand-off `docs/handoffs/2026-11-13-android-digiid.md` §1). Android adopts it in its own PR, before the
Coinomi app work, and this design builds on it:

- The **fixed list**: exactly `digiscope.me` and `api.digiscope.me`, byte for byte. It is read from
  the core (`BRDigiIdUsesSharedIdentity`, a small core PR after step 1); until then it is the Kotlin
  list in `IdentityKeyPolicy`. On a list host, the wallet's `digiScopeIdentity` decides:
  `SHARED` signs with `m/0'/0/0`, and `SITE` with the per-site key of the fixed `U` above.
- **Every other host** gets the per-site key under the wallet's `siteKeyRoot`.
- **This phone's login history never decides, and there is no sign-time choice.** History
  grandfathering, `offersChoice` and the restored-wallet identity card go (Q4 A). That also closes
  review finding P2 on #266 with no special case: no wallet, Coinomi-mode or not, is offered the
  shared key on a site off the list.
- The same seed, with the same three properties, signs the same way on every device. For a
  Coinomi-mode wallet to match on iOS, iOS carries the same per-wallet properties (hand-off).

### UI (every language in `AppLocale.SUPPORTED`, same commit)

- **Restore, after the phrase:** "Where is this phrase from?" HoldFast or the DigiByte app / Coinomi /
  another wallet / not sure. One line each on what it changes ("signs in to sites as Coinomi did").
  First-run restore and multi-wallet add mode both show it.
- **Settings → Digi-ID → "Coinomi-compatible sign-in"** for the active wallet. Turning it on or off
  first says, in plain words, that every site will see a different identity, sign-ins made the other
  way stop working, and the Hub signs this wallet out. Then it clears this wallet's Hub session.
  - **On** stores the wallet's current pair, then sets `STANDARD` + `SITE`.
  - **Off** restores the stored pair. A wallet restored as "Coinomi" has no stored pair, so off sets
    `STANDARD` + `SHARED`: its per-site keys stay Coinomi's, and DigiScope goes back to the shared
    key.
  - Neither ever removes anything from `watchedAccounts`. On offers "Also watch Coinomi's accounts"
    when they are not watched yet.
- The Digi-ID approval screen shows the identity address it will sign with (it does today) and, for
  a Coinomi-mode wallet, says so.

### Funds on Coinomi's paths

The restore already finds Coinomi's DGB through **Recover funds** (BIP44 `m/44'/20'/0'` and BIP49
`m/49'/20'/0'`, both proven on mainnet in `docs/RESTORE-VERIFICATION.md`), but only as a one-time
sweep, by sending the addresses to the reconcile server, after a warning, and multi-wallet's add mode
skips it.

For a wallet whose `watchedAccounts` holds Coinomi's two accounts (set by the restore answer
"Coinomi", and never by the sign-in setting) the sync engine also **watches** both, receive and change
chains with the usual gap limit, through compact filters on the phone. Its balance, history and spends
include them, and nothing is sent to a server. Coins there are spent like any other: P2PKH and
P2SH-P2WPKH signing are both in `BRTransactionSign`; change still goes to the wallet's own BIP84
chain.

What makes that real (step 4):
- The two accounts are **derived chains of the core wallet**, in the wallet recipe iOS shares, with
  their signing keys. They are not watched addresses, which the engine never credits.
- Both paths are hardened, so their account public keys need the seed. They are derived when the
  accounts are added (the seed is present at restore, and the Settings action runs on the unlocked
  active wallet). They are stored with the wallet's watch-only material, so a non-active wallet keeps
  watching them (multi-wallet step 2).
- Adding them to a wallet that already synced starts a back-scan of those chains from the wallet's
  birth height.

Recover funds stays the opt-in quick check. A first-run restore offers it for a Coinomi phrase. Add
mode does not, because multi-wallet ruling F9 keeps its only server option to the quick start, whose
text says the lookup links the wallets; Settings → Recover funds remains available for the active
wallet.

Watching extra account chains per wallet is engine work. It lands **with multi-wallet step 2**, which
rebuilds the watch set per wallet anyway, not before.

## Order of work

1. Core: characterization KAT, then the master-key argument and the `STANDARD` vectors.
2. App: the three properties, `DigiScopeIdentity` and its source gate, the bridge's two per-site
   natives moved onto `BRDigiId.h`, the policy on top of Q4 A. (The server check is answered above:
   no server change.)
3. UI: the restore question, the Settings toggle and "Also watch Coinomi's accounts", all languages.
4. With multi-wallet step 2: watching the accounts in `watchedAccounts`.

Before step 2: Q4 A on Android (its own PR), and `BRDigiIdUsesSharedIdentity` in the core (its own
small core PR, after step 1).

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
