# Pre-Play-Store roadmap — future state

**Status:** future state, not a plan. Nothing here is scheduled or designed to task level.
**Gate:** items 1–3 **and 5** complete before submitting to the Play Store. **Item 4 is not active.**
**Written:** 2026-08-27, against v4.0.60.

Five items, one of them **not active**. One is a **funds-safety gap that is live today**; the
rest are product work. Ordered by consequence, not by effort.

---

## 1. Asset-aware restore, moved out of first-run

### The part that is urgent

`LegacySweepService` has **no asset awareness at all** — no reference to assets, asset UTXOs, or
`is_asset` anywhere in the file. It collects every UTXO it can map and spends them into a single
plain DGB output.

The wallet already documents what that does to an asset. From
`project/digiasset-implicit-change-rule-missing`: *a plain-DGB spend can destroy an asset*. A
DigiAsset lives on a specific UTXO; spend that UTXO as ordinary DGB, without the DigiAsset output
structure, and the asset is gone. Not moved — gone.

So: **sweeping a wallet that holds DigiAssets appears able to burn them.** That path is shipped
and reachable today at Settings → Recovery → "Recover funds from another wallet".

**This is inference, not a demonstration.** Two established facts (the sweep has no asset filter;
plain-DGB spends destroy assets) point at a third. Before any fix, prove it on testnet26 with a
throwaway asset: sweep a seed that holds one, and see whether it survives. If it does, something
else is protecting it and that protection needs finding and naming. If it does not, this becomes
the highest-priority item in the wallet, ahead of everything else in this document.

Interim mitigation if the burn is confirmed and a full fix is not close: refuse to sweep any UTXO
the wallet believes carries an asset, and tell the user which ones were skipped and why. Leaving
an asset behind is recoverable. Burning it is not.

### Current state

- **Full restore lives in first-run.** It is one of the two opening choices, alongside "create
  new" — `OnboardingScreen` → `MnemonicInputScreen` → `RecoveryDateScreen` → `RecoveryScanScreen`.
- **A partial side flow already exists:** Settings → Recovery → `recover_funds`, the foreign-seed
  sweep. It sweeps *into* the current wallet rather than replacing it.
- Those two do different things and the difference is not obvious from either entry point.

### Target state

- **First run offers create-new only — DONE 2026-08-27, and for a security reason, not a UX one.**

  Restoring an old phrase re-imports its keys, including legacy derivations whose public keys may
  already be exposed on-chain. **P2PK outputs carry the public key in the output script itself**,
  and any legacy address that has ever been *spent from* has published its key in the spending
  input. Those coins are readable to anyone with a sufficiently capable quantum computer, whenever
  that arrives.

  So the wallet generates fresh keys at first run, and an older wallet's funds are **transferred
  in** from Settings — landing on never-spent addresses whose keys are still behind a hash.
  Importing carries the exposure forward; sweeping leaves it behind.

  The restore/transfer code is unchanged and still reachable from Settings → Recovery. The
  first-run *entry point* was removed, not the capability. The lost-PIN path is unaffected: it
  uses `restoreFromDisk` via `pin_setup`, never `mnemonic_input`.

  Consequence to keep in view: someone reinstalling after losing a phone now creates a wallet
  first and transfers into it. That is the intended trade — they end up on modern keys — but it
  makes the Settings transfer flow **the only route back to funds**, so its discoverability and
  its completeness (every derivation path, every address encoding, assets included) stop being
  nice-to-haves.
- **Restore is asset-aware:** DigiAssets are enumerated, carried, or explicitly skipped with a
  reason. Never silently consumed.
- **The two flows are named for what they do.** "Restore a wallet" (replace this one) and "Sweep
  funds from another seed" (pull into this one) are different operations with different
  consequences, and today both read as "recovery".

### Open questions

- Restore replaces the active wallet. What happens if the current one holds funds? Refuse,
  warn, or sweep-then-replace?
- Does asset-aware mean *transfer* assets to the new wallet, or *detect and report* them so the
  user moves them deliberately? Transfer is a send, with fees and failure modes, from a wallet
  the user may be abandoning.
- First-run without a restore option is a real UX risk: someone reinstalling after losing a phone
  must still find it quickly, and "create new" is the wrong door. The side flow has to be
  discoverable from the first screen without being *offered* there.

---

## 2. BIP39 passphrase at setup (12+1 and 24+1)

### Current state — better than expected

- `NativeBridge.mnemonicToSeed(phraseBytes, passphrase)` **already takes a passphrase**, and the
  native side already threads it into `BRBIP39DeriveKey`.
- **12- and 24-word generation already exists** — `OnboardingViewModel` picks 128 or 256 bits of
  entropy from `/dev/urandom`.
- `RecoveryScanScreen` passes `passphrase = null` and carries the comment *"the passphrase picker
  is a follow-up we can add"*.

So the cryptography and the plumbing are done. What is missing is the UI, the storage decision,
and the safety design — and the last two are the whole job.

### Standard compliance

BIP39's passphrase is not a 13th or 25th word from the wordlist. It is arbitrary text mixed into
PBKDF2 as `"mnemonic" + passphrase`, so any BIP39 wallet given the same phrase and passphrase
derives the same seed. Compliance means exactly that: the same 12 or 24 words plus the same
passphrase must restore identically in any other BIP39 wallet, with no DigiByte-specific salt,
normalisation, or "+1 word" reinterpretation. The existing native path already does this;
the risk is a well-meaning UI inventing a variation on top.

### The hazard that has to drive the design

**A BIP39 passphrase has no checksum.** The mnemonic does — a mistyped word is caught. A mistyped
passphrase is not: it silently derives a *different, perfectly valid, empty* wallet. The user sees
a zero balance and concludes their funds are gone.

This is the dominant failure mode and it is a user-support catastrophe, not an edge case. The
design has to make a typo visible at the moment it happens, not months later. Options worth
weighing: confirm-entry, showing the first derived address so it can be checked against a known
one, or a deliberate "this is a different wallet" confirmation when a passphrase produces an
empty result.

### The storage decision

Where the passphrase lives determines whether it is worth having:

- **Not stored, entered each unlock** — full benefit (a stolen seed backup is not enough), worst
  UX, and every unlock is another chance to typo it.
- **Stored beside the seed** — good UX, and it becomes little more than a longer seed: whatever
  compromises the seed store gets both.
- **Stored, with the trade-off stated plainly** — probably the honest middle, provided the UI does
  not claim protection it is not giving.

This is a product decision about what is being promised, and it should be made explicitly rather
than falling out of an implementation.

---

## 3. Retire the forums; keep one general chat

### Current state

Hub has four tabs: **Chat / Forum / Games / Profile**. The forum is unused.

| file | lines |
|---|---|
| `ThreadDetailScreen.kt` | 601 |
| `ForumView.kt` | 348 |
| `CreateThreadScreen.kt` | 290 |
| `ForumViewModel.kt` | 208 |
| **total** | **~1,450** |

Plus the `FORUM` tab, its nav routes, and the thread endpoints in `DigiScopeClient`
(`/hub/threads`, `/hub/threads/{id}`). `ChatView` (583 lines) is untouched, so "one general chat"
is what remains rather than something new to build.

### Target state

- Hub becomes **Chat / Games / Profile**.
- Forum UI, routes, view models and client methods deleted rather than hidden. Dead surface behind
  a flag is the shape this codebase has repeatedly had to excise later.
- Backend `/api/hub/threads*` retired **if the app is the only consumer** — to be confirmed, since
  the website may render forums too.

### Open questions

- **What happens to existing threads?** Deleting the client is easy; deciding whether that content
  is archived, exported, or simply discarded is not a code decision. If the site keeps a read-only
  archive, the app can link out instead of the content vanishing.
- Site work is the longer pole and needs its own sequencing. The app change is a day; the backend
  and website deprecation are not.

---

## 4. Buy tab — NOT ACTIVE

**Status: not active (2026-08-27).** Parked deliberately, not forgotten. The research below is
kept because it is the expensive part and it will go stale quietly — re-check before reviving.

### The finding

Checked against live APIs on 2026-08-27, not marketing pages:

| provider | DGB | evidence |
|---|---|---|
| **Transak** | **no** | live crypto list carries 144 coins, none of them DGB |
| **Guardarian** | fiat **no**, swap yes | DGB `enabled=true`, but its only payment method is `CN_CUSTODY` |
| **ChangeNOW** | swap yes | `dgb` active, `supportsFixedRate=true` |

Guardarian is the instructive one. Its page says "Buy DGB with credit card" and the API says
`enabled=true` — both look like support. But BTC, ETH, LTC and DOGE each also carry **`PAYBIS`**,
Guardarian's actual fiat rail, and DGB does not. Only **71 of 664** currencies have any rail
beyond `CN_CUSTODY`.

**So every "buy DGB with a card" page is fiat → BTC/USDT → swap to DGB.** Two hops, two spreads,
and the card statement names a different asset than the user believes they bought.

### Direction: ChangeNOW, as the reference integration

**Decided 2026-08-27.** Wire ChangeNOW in — an account already exists — and treat it as the
worked example of how the wallet supports an external provider at all. Not scheduled; recorded so
the next person starts from the decision rather than the survey.

What it does and does not give:

- **It is a swap, not a fiat on-ramp.** It answers "I hold BTC and want DGB". It does not answer
  "I have dollars". The UI has to say so plainly, or a Buy tab that cannot take money is worse
  than no Buy tab.
- Non-custodial: funds move between wallets, and the payout goes to an address we supply.
- Live pair confirmed 2026-08-27: `btc_dgb`, minimum **0.0001763 BTC**, 0.01 BTC ≈ **155,227
  DGB**, quoted 10–60 minutes. Fixed-rate is supported.

**Widget or API is the real design decision, and it turns on one thing: the payout address.**

The embed widget takes `from`, `to`, `amount`, `link_id` and styling, but has **no documented
parameter to prefill and lock the payout address**. In a wallet that is the whole argument: it
would mean the user pasting their own DGB address into a third-party page rendered inside the
app. Mistype it and the funds are gone, and a wallet that owns the address has no business asking
its user to re-enter it.

The API path creates the exchange with `address` set to a receive address the wallet derives
itself, then shows a native screen with the deposit address and QR. More work, no second WebView
next to a hot wallet, and the destination is never user-typed or page-supplied. **The wallet must
supply the address; it must never accept one back from a page.**

If the widget is chosen anyway for speed, the origin-lock and no-JS-bridge pattern already built
for the digistamp WebView is the precedent to follow — but the address question does not go away.

### The options that were weighed

1. **Swap widget / API** (ChangeNOW) — chosen, above.
2. **Two-hop on-ramp** — embed fiat→BTC, then swap. Works today. The combined cost must be shown
   as one number, or it reads as a terrible DGB price and the wallet wears the blame for someone
   else's spread.
3. **Hand off to exchanges** with real DGB/fiat books — no integration, no liability, weakest UX.
4. **Ship nothing** until a direct rail exists, and spend the effort on getting DGB onto one.

Guardarian also runs a non-custodial partner programme delivering to a user-supplied address, so
it stays a viable second provider if the fiat leg ever appears — its DGB listing is swap-only
today, not a card rail.

### Simplex — unverified, and unverifiable from outside

Simplex did once offer direct debit-card purchase of DGB, which is why it is worth chasing. It is
now **owned by Nuvei** (acquired for $250M), and post-acquisition asset lists have been pruned
across the industry.

**Attempted verification 2026-08-27 and could not settle it either way:**

- `sandbox.test-simplexcc.com/v2/supported_crypto_currencies` exists but returns
  `"query.public_key is required` — the supported-currency list sits **behind partner
  credentials**, unlike Transak, Guardarian and ChangeNOW, whose lists are public.
- `buy.simplex.com` serves a 30 KB JavaScript shell and loads its currency list client-side, so
  the served HTML contains no coins at all — not even BTC. It confirms nothing about DGB.

So Simplex is **unknown**, not "no". That distinction matters: the other three were ruled in or
out on live data, and this one simply cannot be checked without a key.

**To resolve it,** either use a Simplex/Nuvei partner `public_key` against the endpoint above, or
ask them directly whether DGB is still a supported payout asset. If it is, Simplex becomes the
only known **direct fiat→DGB** rail found so far and changes the shape of this whole item — the
two-hop compromise stops being necessary.

### Re-checking (rails change quietly)

```
curl -s "https://api.changenow.io/v1/currencies?active=true" | grep -i '"ticker":"dgb"'
curl -s "https://api-payments.guardarian.com/v1/currencies"     # inspect DGB's payment_methods[]
curl -s "https://api.transak.com/api/v2/currencies/crypto-currencies"
```

Read `payment_methods`, **not** `enabled`. `enabled=true` with only `CN_CUSTODY` means swap-only
and reads as "supported" to anyone skimming.

---

## Sequencing note

Items 1 and 2 both touch seed handling and restore, and item 1's first task — proving or refuting
the asset burn — should happen before either is designed, because the answer changes what restore
has to do. Item 3 is independent and can proceed in parallel. Item 4 is blocked on an external
dependency and may be answered by deciding not to ship it.


---

## 5. Localisation — selectable language

**Added 2026-08-27, and it gates the Play Store** (owner's call: a global chain's wallet launching
English-only is a weak first impression, and Play surfaces localised listings).

### Measured scope

| | |
|---|---|
| Hardcoded UI strings | **~426** across **36 files** |
| `stringResource()` call sites today | **0** |
| Core-module candidates (errors, refusal reasons) | ~76 |
| Existing `values/strings.xml` | 365 lines of bread-wallet legacy, entirely unused |

### Two things that make it more than extraction

**A half-extracted app is worse than an unextracted one.** English and Spanish on one screen reads
as broken. Extraction has to complete per-screen, not per-string, so the unit of work is a screen.

**This wallet's riskiest text is its warnings.** "Sweeping them as ordinary DGB would destroy the
assets." "Anyone with these words can take your DGB." "Downgrading afterwards is not supported."
A mistranslation there costs someone money. Machine translation gives coverage quickly and is
exactly where that goes wrong, so warnings are held back for human review before release while
ordinary UI text can ship from a machine pass.

### Languages

The currency picker's markets, so the two upgrades tell one story: Hindi, Chinese, Japanese,
Portuguese (BR), Spanish, Indonesian, Vietnamese, Turkish, Russian, Filipino — plus English as the
source. Each names itself in its own script; a language list written only in English is unusable
by exactly the people who need it.

### Mechanism

No appcompat dependency and `MainActivity : FragmentActivity`, so `AppCompatDelegate` is not
available. Persist the tag, wrap the context in `attachBaseContext` (works on minSdk 26), and also
inform `LocaleManager` on API 33+ so the OS per-app language picker lists the wallet.
`supportsRtl` is already true, so Arabic is not structurally blocked if it is added later.

`AppLocale` (done) resolves a stored tag and, critically, **fails to "follow the system"** rather
than throwing: that value is read on every cold start before any UI exists, and a wallet whose
settings screen is unreachable because of its own language setting cannot be fixed by its owner.

### Remaining

Extraction (36 screens), the picker UI, the machine-translation pass, and human review of the
warning strings before any of it ships.
