# Hand-off: the per-site master-key root in `BRDigiId.h` (Coinomi Digi-ID, step 1)

Written 2026-11-17 by the Android session, for the iOS and core session. Design:
`docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md` ("Core (shared with iOS)", and step 1
of "Order of work"). iOS facts are at iOS `develop` `776e6dd`, which pins core `fc35768`. They will
drift.

**Status (2026-11-17): open.** §3 to §5 are iOS's. How far step 1 has landed is `ROADMAP.md`'s to
say (Phase 2, item 6), not this file's. In the core, step 1 is `6a7742d`, with review round 1's
`c90a284` (a `hasUri` but 0 or 1 is refused) and `f263cdd` (`BRHMAC` clears both pads). Here,
`ab564c3e3` characterizes the header before the change, `14b0c99d4` moves the pin and holds the new
header, and `a6f417421` and `b40565997` hold round 1's two.

Core and host-KAT work is the iOS and core session's lane (`docs/development-process.md`, "Two
sessions, one Android repo"). Mark assigned step 1 to the Android session on 2026-11-17, as an
exception to that rule. Steps 2 and 3 are Android app work (Kotlin `core/`, the bridge, the UI).
Step 4 is not: watching Coinomi's fund paths changes what the wallet holds, so it is core engine work
with a host KAT ("Funds in the core"), and it lands with multi-wallet step 2 (the design's "Funds on
Coinomi's paths").

## 1. What changed in `BRDigiId.h`

- `BRDigiIdRoot`: `BR_DIGIID_ROOT_DIGIBYTE = 1` derives the master key with `"DigiByte seed"`, as
  every per-site identity has so far (its derivation call is unchanged). `BR_DIGIID_ROOT_STANDARD = 2`
  uses BIP32's `"Bitcoin seed"`, the one Coinomi signs with.
- Zero is not a root. `BR_DIGIID_ROOT_NONE = 0`, and every value but 1 and 2, is `BR_DIGIID_REFUSED`,
  before any derivation and ahead of `BR_DIGIID_CAPACITY`, with the key wiped or `out` left empty
  as every other refusal leaves them.
- `BRDigiIdSiteKey`, `BRDigiIdSignSite` and `BRDigiIdAddress` take the root as a required argument
  directly after `seedLen`. There is no default and no root-less variant: the caller passes its
  wallet's root. `BRDigiIdAddress` refuses an unknown root with and without `hasUri`; without a URI,
  both roots give the one shared address. `BRDigiIdSharedKey` and `BRDigiIdSignShared` take no root.
- A new include, `BRDerivationProfiles.h` (libc only, nothing to link), for the standard key's
  name. `STANDARD` derives through `BRBIP32PrivKeyArrayPath`.
- `BRDigiIdAddress` refuses a `hasUri` that is neither 0 nor 1, beside the root check and ahead of
  `BR_DIGIID_CAPACITY` (review round 1). The root sits directly before `hasUri`, and C converts
  between the enum and `int` with no warning under `-Wall -Wextra -Wpedantic`, so a C caller could
  swap the two: `(STANDARD, 1)` swapped is `(1, 2)`, which answered `DIGIBYTE`'s per-site address,
  one that `BRDigiIdSignSite` under `STANDARD` never signs with. Now every swapped call is refused,
  or is the call meant (`(DIGIBYTE, 1)`). The root values stay 1 and 2.
- Four comparison arms: 131072 `DIGIID_RED_ROOT_IGNORED`, 262144 `DIGIID_RED_ROOT_ALWAYS_STANDARD`,
  524288 `DIGIID_RED_ANY_ROOT`, and 1048576 `DIGIID_RED_ANY_HASURI` (review round 1).
- THE RULES say all of this. "Byte-identical to the bridge" now holds under `DIGIBYTE` only.
- Separately, and older than this change (review round 1 found it): `BRHMAC` (`BRCrypto.c`) cleared
  only the key's block of its two stack arrays, leaving the data (a BIP32 master key's seed, a
  hardened step's parent key) and the inner hash in its dead frame. It now clears both arrays whole.
  No MAC changes; `hmac_residue_kat` holds it, and iOS gets it with the same pin move.

## 2. Android, until step 2

The Android app does not sign through this header. Its bridge derives the per-site key itself
(`seed_derive_identity_key`, `native/src/main/jni/bridge/jni_wallet.c:555-568`, through
`BRBIP32PrivKeyPath`), so Android derives `DIGIBYTE` only. `digiid_core_kat` holds the header under
`DIGIBYTE` to that bridge (1800 of 1800 answers agree, 64- and 32-bit); `STANDARD` has no bridge
answer until step 2 gives the bridge a root.

When Android ships steps 2 and 3, the root becomes a per-wallet setting there (`siteKeyRoot`), set
by the design's table. Read that table where it is current: PR #267 amends it (decision 3,
reversed by the owner on 2026-11-17), so that every HoldFast wallet, new ones included, keeps
`DIGIBYTE`, and only a phrase restored as from Coinomi or another wallet gets `STANDARD`. Whatever
the table says when it ships, iOS gives the same phrase the same root as Android, or one seed has two
per-site identities (contract §0). That is for the iOS roadmap; step 1 changes nothing either app
computes.

Step 2 is the first C caller of `BRDigiIdAddress` on Android: PR #267's design moves
`signIdentityMessage` and `identityAddress` onto `BRDigiIdSignSite` and `BRDigiIdAddress`. A root and
`hasUri` swapped there is refused by the header (§1). Step 2 still owes a bridge test that holds
`identityAddress` under `STANDARD` to the address `signIdentityMessage` signs with, as
`address_root_hasuri_swapped` holds the header. `ROADMAP.md` records it with step 2 (Phase 2,
item 6), which is where step 2's PR reads its plan.

## 3. iOS: four calls stop compiling once the pin contains this change

Every code file at iOS `develop` `776e6dd` (343: `.swift`, `.c`, `.h`, `.sh`, `.py`, `.modulemap`)
was searched. These four calls use the old argument list, and nothing calls `BRDigiIdSiteKey`:

| File | Lines | Call |
|---|---|---|
| `Policy/DigiIdSigner.swift` | 53-54 | `BRDigiIdSignSite` |
| `Policy/DigiIdSigner.swift` | 80-81 | `BRDigiIdAddress`, `hasUri` 1 |
| `Policy/DigiIdSigner.swift` | 88 | `BRDigiIdAddress`, `hasUri` 0 |
| `DigiByteWalletTests/DigiIdCoreTests.swift` | 110 | `BRDigiIdAddress`, `hasUri` 1 |

Each passes `BR_DIGIID_ROOT_DIGIBYTE` after `seed.count`. The three `BRDigiIdAddress` calls pass
`hasUri` 1, 0 and 1, the only values the header takes now. No address or signature changes: every
literal the KAT had before the change is still in it and passes, and the bridge comparison above
still agrees. Since nothing either app computes changes, contract §4 item 3 does not force a pin
move; the first move that contains the core merge carries these four edits.

## 4. iOS: Mac-only checks owed against the new header

Linux skips two legs of `digiid_core_kat`'s portability gate, and core CI runs on Linux, so neither
has run against the new header:

- the `iphoneos` and `iphonesimulator` arm64 compile (the leg of
  `native/src/test/host/digiid_core_kat/run.sh` that sets `ios_note`);
- Swift's C importer (`swiftc -typecheck -import-objc-header`, the leg that sets `swift_note`), whose
  snippet now passes `BR_DIGIID_ROOT_DIGIBYTE` to all three calls.

Run `run.sh` on the Mac at `feat/digiid-site-key-root`, or at any later commit that pins the new
header. The gate's line must end `(iphoneos + iphonesimulator; Swift imports it)`.
`BRDerivationProfiles.h` and `BRDigiId.h` are both in iOS's `PUBLIC_HEADERS` already
(`Scripts/build-core-xcframework.sh:39`, `:46`), so the new include should need no change there;
the XCFramework build is what shows it.

`hmac_residue_kat` (new, review round 1) has run only on Linux. It searches a thread's stack after
`BRHMAC` returns, so it depends on how the platform lays out a stack it is given: its comparison arm
must still find the old residue on a Mac, at -O0 and -O2, before its green arm there means anything.
Read that off its `[red64-O0 <case>] failed, as it must` and `[red64-O2 <case>] …` lines, not off a
`PASS`. Review round 2 changed its runner for macOS's bash 3.2: the empty `-m32` and flag arrays
no longer stop it at its first build, and a run that stops before its verdict exits 1, where bash
3.2 would have exited 0. That change has not run under bash 3.2 yet.

## 5. iOS: L2 tests to carry

iOS's `docs/cross-platform-contract.md`: L2 runs L1's vectors (:145); shared vectors live with the
C rule (:150-152); and every C enum crossing the boundary has a defined default that is never the
destructive action, which the L2 test must reach (:156-157). From
`native/src/test/host/digiid_core_kat/digiid_core_kat_main.c`, value for value:

- **`site_key_standard_coinomi_uris`**: the phrase `abandon` ×11 `about`, no passphrase, index 0,
  under `STANDARD`:

  | Callback URL | Address |
  |---|---|
  | `https://api.digiscope.me/api/digiid-probe/callback` | `DSSfzAosbs1mBJE4rZPtZHGuwjHorJDNau` |
  | `https://api.digiscope.me/api/auth/digiid/callback` | `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri` |
  | `https://digiscope.me/api/digiid-probe/callback` | `DA3TaNPPTHwStZp3K98sGzk7aaQ8sh7sBo` |

  Each differs from its URL's `DIGIBYTE` identity (`site_key_digibyte_coinomi_uris`, read off the
  real bridge: `DHawr8VSh4T2iEM9NxJdubbDTra31RKU94`, `DKBDZRPxPz6qGseZKNNzSwW3zfaRr1FQNA`,
  `DS4cZJP33QZ2mc1bTy9RgHZ3nDznEzS5eh`). The KAT's `LOGIN` (`DigiIdCoreTests`' `login`), signed
  under `STANDARD` with the second URL's identity, answers `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri|…`
  and recovers to that address. The bridge has no `STANDARD` signature to read off; the next case
  pins the whole answer from the independent model.
- **`site_key_standard_login_and_indexes`**: the same phrase under `STANDARD`, where nothing was
  measured. `https://api.digiscope.me/api/auth/digiid/callback` at index 1 is
  `D6jnpnRo8u9x3FsgSMcUPwRGphbANMqz4g`, at 2 `DT1N5cjfpXxrTQztFQSQGCEiNdfhYTbJSc`, and at
  `INT32_MAX` `D7D31HYwVkgv2wGjx3vxczkx6kXpa6zg6r`. `LOGIN` signed with its index-0 identity answers
  `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri|IMx5g483pmrhXxBN7sywXP4364mTuX1jM7F2QAMJZ5CzKifnD1bbptCQGvlXMB78Xo6URQkWkPV1IIbSh/o+lfI=`.
  Index -1 and `INT32_MIN` are refused by all three calls. The values are the independent model's
  (BIP32 from `"Bitcoin seed"`, SLIP-0013, RFC 6979 as libsecp256k1 signs), which gives the bridge's
  `DIGIBYTE` answer for the same login byte for byte.
- **`site_key_standard_bitid_vector`**: the BitID phrase (`inhale …`) and
  `http://bitid.bitcoin.blue/callback`, index 0, under `STANDARD`: the key's hash160 is the published
  `1J34vj4wowwPYafbeibZGht3zy3qERoUM1`'s.
- **`shared_address_both_roots`**: with no URI, both roots give `DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn`
  and refuse index 1.
- **`unknown_root_refused`**, the zero value: the enum's values are `BR_DIGIID_ROOT_NONE` 0,
  `DIGIBYTE` 1 and `STANDARD` 2. Roots 0, 3 and `(BRDigiIdRoot)-1` are `BR_DIGIID_REFUSED` from all
  three calls (on both branches of `BRDigiIdAddress`), also when `work` is too small, with the key
  wiped and `out` empty. Swift writes 0 as `BR_DIGIID_ROOT_NONE` and other values as
  `BRDigiIdRoot(rawValue: 3)`, as `CoreParityTests.swift:88` does for `BRCFRecoveryReason`.
- **`address_root_hasuri_swapped`**: `hasUri` 2, 3, -1, `INT_MAX` and `INT_MIN` are
  `BR_DIGIID_REFUSED` from `BRDigiIdAddress` under both roots, also when `work` is too small, `out`
  empty. In Swift the enum is its own type (hence `BRDigiIdRoot(rawValue: 3)` above), so a swapped
  root and `hasUri` should not type-check; no Swift toolchain here showed it. Swift can still pass
  those `hasUri` values, and they are refused there too.
- `sweep_keys_standard` holds every `STANDARD` key over a pseudo-random stream to the KAT's own
  BIP32; it has no literal to carry.

The `STANDARD` values at index 0 are Coinomi's own addresses (the design's table, measured
2026-11-17) and the published BitID vector, not the header's output; an independent Python
BIP32/SLIP-0013 model gives all four again, and the three `DIGIBYTE` addresses. The other `STANDARD`
values are that model's alone. The new arms reach the test bundle the way `DigiIdCoreTests`' header
records (`-Xcc -DDIGIID_UNFIXED=<bit>`). On Linux (run.sh at `a6f417421`), 131072 fails exactly the
four `STANDARD` cases (`site_key_standard_coinomi_uris`, `site_key_standard_bitid_vector`,
`site_key_standard_login_and_indexes`, `sweep_keys_standard`), 524288 exactly
`unknown_root_refused`, and 1048576 exactly `address_root_hasuri_swapped`.

## 6. Merge order

Core first, then the pins (rule 4). Android `develop`'s `digiid_core_kat` does not compile against
the new header, so the push run on core `develop` after the core merge is red by construction. By
Mark's ruling of 2026-11-17 (rule 7, recorded with this hand-off), the rule-7 run is then a
`workflow_dispatch` of core CI on `develop` at that merge commit with
`android_ref=feat/digiid-site-key-root`, and the Android pin PR follows it. Until that pin PR merges,
a core PR whose CI tests against Android `develop` fails `digiid_core_kat` as well.

A dispatch takes no commit: core CI checks the core out at `github.sha`, which for a dispatch is the
head of `develop` when it is dispatched. So dispatch before anything else merges to core `develop`,
and read the run's "Show what is under test" step: its `core:` line must name the merge commit, and
its `android:` line must end `(feat/digiid-site-key-root)`. If `develop` has moved on, the `core:`
line names the commit the run tested, and that commit, which contains the merge, is the one the pin
takes once the run is green.

## 7. The DigiScope list and the identity choice, in the core (iOS hand-off 2026-11-13 §3)

The iOS session's hand-off of 2026-11-13 (§3) asked the core lane for
`BRDigiIdUsesSharedIdentity(host, hostLen)`. Mark gave that header to the Android session
(2026-11-17), and the Coinomi design ("App: the policy") reshaped it: a Coinomi-mode wallet signs
DigiScope per-site, so "on the list" no longer means "the shared key". It is built in `BRDigiId.h`
(core `3ab900a`), under these names and not that one:

- **`BRDigiIdIsDigiScopeHost(host, hostLen)`**: 1 exactly for `digiscope.me` and `api.digiscope.me`,
  byte for byte, on the request's parsed (lower-cased) domain.
- **`BRDigiIdChooseIdentity(&choice, host, hostLen, callbackUri, callbackLen, scope, siteKeyRoot)`**:
  the whole choice. `scope` is the wallet's `digiScopeIdentity`, a `BRDigiIdScopeIdentity`
  (`BR_DIGIID_SCOPE_SHARED` 1, `BR_DIGIID_SCOPE_SITE` 2), and `siteKeyRoot` its `BRDigiIdRoot`. The
  answer is a `BRDigiIdChoice { kind, uri, uriLen, root }`, whose `kind` is a `BRDigiIdIdentityKind`:

  | Host | `scope` | `kind` | `uri` and `root` |
  |---|---|---|---|
  | on the list | `SHARED` | `BR_DIGIID_IDENTITY_SHARED` | none: `NULL`, `BR_DIGIID_ROOT_NONE` |
  | on the list | `SITE` | `BR_DIGIID_IDENTITY_SITE` | `BR_DIGIID_DIGISCOPE_LOGIN_URI`, `siteKeyRoot` |
  | any other | either | `BR_DIGIID_IDENTITY_SITE` | the caller's `callbackUri`, `siteKeyRoot` |

  `BR_DIGIID_DIGISCOPE_LOGIN_URI` is a literal,
  `https://api.digiscope.me/api/auth/digiid/callback`. A `SITE` wallet signs every DigiScope request
  with that one identity, never with the request's own callback. `SHARED` signs with
  `BRDigiIdSignShared`, and `SITE` with `BRDigiIdSignSite` under `choice.root` over `choice.uri`.
- **Refused**, the choice left all zero: no host; a `scope` or a root that is 0 or unknown, also
  where the answer would not use it; no callback for a host off the list. A list host needs no
  callback.
- The `SHARED` answer's root is `BR_DIGIID_ROOT_NONE`, which `BRDigiIdAddress` refuses. Ask it for the
  shared address under the wallet's own root, with `hasUri` 0.

**iOS** replaces `DigiIdIdentityPolicy.sharedIdentityHosts` with that one call, passing
`BR_DIGIID_SCOPE_SHARED` and `BR_DIGIID_ROOT_DIGIBYTE` until it stores the per-wallet properties.
That gives exactly today's answers: the shared identity on the two hosts, and on every other host
the per-site identity of the request's callback under `DIGIBYTE`. One input answers differently, and
no request has it: an empty host is refused, where `choose("")` answered per-site.

**Android** keeps `IdentityKeyPolicy.DIGISCOPE_HOSTS` until Coinomi step 2 moves it onto the call.
Until then `digiid_core_kat`'s source gate fails when that list and the header's differ.

Held by `digiid_core_kat`: iOS's `DigiIdIdentityTests` list cases, value for value
(`digiscope_list_*`), each host crossed with both `scope` values and both roots; every refusal; and
`choice_end_to_end`, where `api.digiscope.me` with `SITE` and `STANDARD` derives
`DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri`, the address Coinomi signed DigiScope's login with. Arms
2097152, 4194304, 8388608, 16777216 and 33554432 each fail exactly their cases. The Swift leg of
`run.sh` now imports these names (the literal as a `String`). Like §4's legs, it has not run on a
Mac.
