> ## Disposition — the privacy hole is CLOSED (v4.0.36); the restructure is designed and PARKED
>
> **Shipped:** the automatic address-set disclosure is gone. Asset-holding lookups POSTed the
> entire address set to a backend without the user asking — the exact leak this spec and the
> handoff exist to stop. Removed in v4.0.36; holdings are now derived on-device.
>
> **Designed, not built:** `docs/superpowers/specs/2026-08-17-restore-as-sovereign-sweep-design.md`
> supersedes this document's §2 Part A. The "filter-based rescan of the restored seed's
> addresses" described here **cannot work as written**: the native invariant is *credit iff
> derived* (`watched_credit_kat`) — a watched address becomes a filter element, so its block
> IS fetched, but the transaction is then discarded because the address is not in the derived
> set. The blocks would arrive and the money would be dropped. Discovery is therefore
> temporary seed adoption using the wallet's own BIP158 sync, which makes the keys derived.
>
> **Still open:** the onboarding restructure (fresh-wallet-by-default) is written but
> unshipped pending visual verification, and `UtxoSource` / `ReconcileBackendUtxoSource` /
> the two address-set POSTs in `DgbNodeClient` still exist in the tree behind explicit
> user-initiated recovery actions. They are no longer on any automatic path.

# Spec: Restore Flow Restructure + Asset-Aware Sweep

**Status:** Draft / investigation-first
**Priority:** P1 — split into Part A (onboarding) and Part B (sweep)
**Depends on:** `digiasset-balance-accounting.md` for Part B

---

## 1. Problem

Two problems, tangled together in one code path.

**Privacy.** The "restore old keys" flow derives the address set from the seed, then
queries an ElectrumX endpoint for balances and UTXOs of that whole set. One request hands
the operator a complete map of the wallet. This nullifies the reason BIP158 exists in this
app. Today that endpoint is DigiScope infrastructure, which contains the blast radius to
me — but shipping it means every user's address set goes to whoever runs the node, and
"trust the operator" is precisely the property this wallet exists to eliminate.

**Legacy weight.** Supporting the legacy breadwallet-derived scheme (`"DigiByte seed"`
HMAC key, `m/0'/0/i`, P2PKH version byte `0x1E`, documented in
`docs/derivation/LEGACY_DERIVATION.md`) inside the live wallet means carrying that
derivation permanently through every future feature.

---

## 2. Target design

### Part A — Restore leaves the primary path

**New users always generate a fresh wallet with new keys.** No branch, no "restore
instead?" option competing for attention in onboarding. Onboarding does one thing.

**Restore becomes a separate side flow**, reachable deliberately (settings / a secondary
entry point on the welcome screen), not on the default journey.

**The DigiScope ElectrumX endpoint is removed from the app.** Replace with:

- Filter-based rescan as the default, private recovery path.
- An optional "point at your own node" path, with documentation, for users who want a
  fast scan and control their own infrastructure.
- Instructional content explaining the tradeoff plainly — what each option discloses and
  to whom.

Rationale for removing rather than defaulting-off: an endpoint that ships in the binary
is an endpoint that will get used, and being the convenient option makes DigiScope a
honeypot by default. Better not to be in that position at all.

### Part B — Restore means sweep, not adopt

The key reframe: **restore does not resurrect the old wallet. It moves value out of the
old keys into a fresh wallet, once.**

This is what makes the whole design work. If old addresses are being emptied and
permanently abandoned within minutes, then any disclosure during that scan is transient
and attaches to keys with no future. The new wallet is born clean, filter-syncs from its
own creation height, and never issues a direct address query in its life. The privacy cost
is confined to the moment it matters least.

It also quarantines the legacy derivation. The breadwallet scheme is needed only long
enough to derive, sign, and sweep. Afterward it is recovery-module code, not live-wallet
code.

**Flow:**

1. User enters seed in the restore side flow.
2. Derive addresses across both the legacy and current schemes, out to gap limit.
3. Discover UTXOs (filter rescan by default; user's own node if configured).
4. Classify every UTXO: plain DGB vs. asset-bearing, via the parser.
5. Sweep plain DGB to the new wallet.
6. Handle asset-bearing UTXOs via proper asset-aware transfer — **never** in the DGB
   consolidation.
7. Report what moved, what remains, and what failed.

---

## 3. Asset-aware handling (the part that must not be gotten wrong)

DigiAssets are bound to specific outputs. A naive sweep that selects by balance will
consume asset-bearing UTXOs as ordinary DGB, and the asset is destroyed — irreversibly,
with no error, because from the chain's perspective nothing invalid happened.

### Hard requirements

1. **Classification before selection.** The coin selector for the DGB sweep must receive
   a UTXO set with asset-bearing outputs already excluded. Exclusion happens upstream of
   selection, not as a filter inside it.
2. **Asset transfers use the DigiAssets transfer path**, constructing correct transfer
   instructions — not a generic send.
3. **Fail closed.** If the parser cannot confidently classify a UTXO, treat it as
   asset-bearing and exclude it from the sweep. An unswept output is recoverable; a
   destroyed asset is not.
4. **Never sweep an asset-bearing UTXO to pay fees.**

### Testing vs. production behavior

**Testing: explicit, one at a time.** Each asset transfer is a deliberate, individually
confirmed action, producing a per-asset audit trail. When something breaks we know exactly
which asset ID and which outpoint, rather than debugging a black-box batch.

Validation loop: after each transfer, look up the asset ID in the DigiScope DigiAsset
explorer (backed by the DigiAsset Core node) and confirm the on-chain landing spot matches
what the wallet believes. Both ends are under our control, so this is ground truth, not
inference.

**Production: automated, with guardrails.** Once the transfer logic is validated, fold it
into the restore flow automatically. Non-negotiable guardrails:

- **Dry-run preview.** Show what will move — which assets, which quantities, to which
  destination — and require confirmation before broadcast. Asset transfers are
  irreversible; automation without a preview is not acceptable.
- **Full transfer logging.** Log every asset transfer with asset ID, source outpoint,
  destination, quantity, and resulting txid. If a user later reports something missing,
  we need forensic breadcrumbs.
- **Partial-failure tolerance.** If asset 3 of 7 fails, assets 1–2 stay moved, 4–7 still
  attempt, and the user gets an accurate report. No all-or-nothing rollback illusion —
  broadcast transactions cannot be rolled back.

### Test coverage

Primary target is the **common single-asset transfer** — that is the overwhelming majority
of real usage. Note but do not block on:

- One output carrying multiple asset IDs.
- Partial transfers leaving a remainder on change.
- Aggregation policy variants (aggregatable / hybrid / dispersed).
- Unlocked/re-issuable assets.
- Assets on the legacy derivation path specifically.

---

## 4. Sweep mechanics and linkage

Consolidating many old UTXOs into one transaction creates an obvious on-chain linkage
between all of them. This is a real cost but a much smaller one than an ongoing disclosure
channel, and it is accepted here deliberately.

Worth considering, not required for v1:

- Allow the user to sweep in batches rather than a single consolidating transaction.
- Sweep to multiple fresh receive addresses rather than one.
- Do not tie the linkage decision to the asset transfers, which have their own constraints.

---

## 5. Investigation required

- Where exactly is the ElectrumX call made, and what else depends on it? Is the restore
  path the only caller, or does regular sync/"scan for missing transactions" also use it?
- Does a filter-based rescan path already exist that restore can reuse, or is it new work?
- Can a "scan from height" hint be offered, so users who roughly recall wallet creation
  time avoid a full-chain filter walk? Deep legacy history is the worst case for restore
  latency.
- Does the current gap-limit logic cover both the legacy `m/0'/0/i` path and the current
  scheme?
- Is there any existing coin-selection exclusion for asset-bearing outputs? If not, this
  is a latent asset-destruction bug in the *existing* send flow, not just restore — treat
  that as its own finding and report it.

---

## 6. Acceptance criteria

**Part A**
1. Onboarding has no restore branch; new users always get fresh keys.
2. Restore is reachable from a secondary entry point.
3. No DigiScope ElectrumX endpoint remains in the shipped binary or config.
4. Filter-based rescan works as the default restore discovery path.
5. User-supplied node configuration works and is documented, with the disclosure tradeoff
   stated plainly in-app.

**Part B**
6. A wallet holding both plain DGB and assets sweeps DGB correctly while leaving every
   asset-bearing UTXO untouched by the consolidation.
7. Asset transfers land correctly, verified by asset ID in the DigiScope explorer.
8. Coin selection provably cannot select an asset-bearing UTXO.
9. Unclassifiable UTXOs are excluded and reported, never swept.
10. Dry-run preview accurately predicts the outcome in every tested case.
11. Partial failure produces an accurate report and does not lose track of what moved.

---

## 7. Removed from scope

**Duress code workflow — cancelled.** Not proceeding. Remove any existing scaffolding,
roadmap entries, or references. This is a deletion task, not a design decision to
revisit.
