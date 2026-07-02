# DigiByte Android Wallet — Next Priorities

## Research Summary (April 2026)

Analyzed: BlueWallet, Electrum, BRD/Breadwallet, Mycelium, Dogecoin Wallet, Litewallet, Edge Wallet, Trust Wallet. Plus Android tooling landscape and BIP157/158 compact block filters.

## Key Finding

**No competing SPV wallet uses BIP157/158 compact block filters.** Everyone is either on BIP37 bloom filters (Dogecoin, old Litewallet) or server-based (Edge/Blockbook, Trust/backend). Our bloom seeder approach is already ahead of the DOGE and LTC wallets. BIP157/158 would put us genuinely ahead of the entire field, but it's a multi-week effort and not urgent.

**The Litecoin Foundation abandoned breadwallet-core.** Their Litewallet Android (same C core ancestry as ours) is unmaintained. We are the only actively maintained wallet on this C core lineage. This is both a risk (no upstream fixes) and an opportunity (we own the direction).

---

## 4 Priority Areas

### Priority 1: Release Infrastructure (Before Play Store)

**Why:** We're manually building APKs, scp'ing to VPS, and editing HTML. This doesn't scale and introduces errors. Every competitor has automated release pipelines. We need this before Play Store submission.

**What to build:**

1. **Release signing key** — Generate a proper 25-year RSA release keystore. Store in GitHub Secrets. Currently shipping debug-signed APKs — Play Store requires release signing.

2. **Automated release workflow** — Enhance existing `release.yml`: build release APK (not debug), sign it, compute SHA-256, create GitHub Release with auto-generated changelog, upload APK to VPS download page automatically. One `git tag v3.1.0` → everything happens.

3. **Conventional commits + release-please** — Automated changelogs from commit messages. No more manually writing release notes. Google's `release-please` creates a "Release PR" that accumulates changes.

4. **F-Droid submission** — The Docker reproducible build in `release.yml` is 90% of what F-Droid needs. Create metadata YAML, submit to fdroiddata. Privacy-conscious crypto users expect F-Droid availability.

**Effort:** ~2-3 days. Highest ROI — eliminates manual deploy errors and unblocks Play Store.

---

### Priority 2: Maestro E2E Test Suite

**Why:** Our bash test suite catches crashes but can't test real user flows (the UI tap coordinates are fragile and can't verify screen content). Maestro is the modern standard — YAML-based, black-box, works with Compose, 99%+ pass rates vs Espresso's 50%.

**What to build:**

1. **Maestro flows for critical paths:**
   - `create-wallet.yaml` — full onboarding: create → view seed → verify → set PIN → wallet screen
   - `recover-wallet.yaml` — recover → enter seed → set date → set PIN → wallet syncs
   - `send-dgb.yaml` — navigate to send → enter address → enter amount → confirm → broadcast
   - `receive-dgb.yaml` — navigate to receive → verify address displayed → copy address
   - `digi-id.yaml` — scan QR → confirm → sign → callback

2. **Run in CI** — Maestro has a GitHub Action. Add to CI workflow: boot emulator, run Maestro flows, fail on any assertion failure.

3. **Replace fragile bash tap tests** — Maestro finds elements by text/accessibility labels, not pixel coordinates. "Tap the button that says 'Create New Wallet'" instead of "tap at 540,1500".

**Effort:** ~1-2 days. The YAML flows are short and readable.

---

### Priority 3: User-Facing Feature Gap Closers

**Why:** Every competing wallet offers these. Users switching from BlueWallet/Mycelium/Edge expect them.

**What to build (in priority order):**

1. **Transaction labels + detailed history** — Mycelium shows block height, confirmations, inputs/outputs, fee breakdown, fiat value at time of tx. Our transaction list shows basic amount/date. Add: label field (stored in Room), detail screen with full tx breakdown, CSV export.

2. **Watch-only wallet mode** — Import xpub to monitor a cold storage wallet without exposing keys. BlueWallet and Electrum both offer this. Low risk, high utility. We already have the BIP32 key derivation; just need to skip seed storage and disable signing.

3. **Coin control / UTXO management** — Manual UTXO selection, freezing, labeling. BlueWallet, Electrum, and Mycelium all offer it. Power users need this for privacy (avoiding address linkage). We already have UTXO data in Room; need a UI screen and selection integration in the send flow.

4. **Sweep paper wallet** — Scan a WIF private key QR, sweep funds to the wallet. Dogecoin wallet has this. Useful for physical DGB giveaways and migration from old wallets.

**Effort:** Items 1-2 are ~1-2 days each. Items 3-4 are ~2-3 days each.

---

### Priority 4: BIP157/158 Compact Block Filters (v4.0 Milestone)

**Why:** Bloom filters leak wallet addresses to connected peers. Compact filters provide strong privacy by default — the client never reveals any address. This would make the DigiByte wallet the first UTXO mobile wallet with native compact filter support (no competitor has it).

**What's needed:**

1. **Test server support** — Enable `blockfilterindex=1` and `peerblockfilters=1` on digiscope.me node. Verify it builds the index and signals `NODE_COMPACT_FILTERS` (0x40). Measure filter sizes for DigiByte blocks.

2. **C core changes (~2,000-4,000 lines):**
   - GCS filter decoder (SipHash + Golomb-Rice, ~400 lines)
   - BIP157 message handlers in `BRPeer.c` (getcfilters, cfilter, getcfheaders, cfheaders)
   - Compact filter sync mode in `BRPeerManager.c`
   - Full block fetcher for filter-matched blocks
   - Dual-mode: use compact filters when available, fall back to bloom

3. **Challenge: DigiByte has ~20M blocks** — filter header chain alone is ~640MB. Needs hardcoded checkpoints and creation-date-based pruning for mobile feasibility.

4. **Bloom seeder can also discover compact filter peers** — add `NODE_COMPACT_FILTERS` (0x40) detection alongside `NODE_BLOOM` (0x04).

**Effort:** Multi-week project. Not urgent given the bloom seeder works well. Schedule for v4.0 alongside v9.26 integration.

---

## Tools to Install/Configure Now

| Tool | What | Why | Effort |
|------|------|-----|--------|
| **Maestro** | YAML-based mobile E2E testing | Replace fragile bash tap tests, run in CI | 1 hour setup |
| **release-please** | Automated version bumps + changelogs | Stop manually editing version/notes | 1 hour setup |
| **Detekt + ktlint** | Kotlin static analysis + formatting | Code quality gate before Play Store | 30 min setup |
| **Renovate** | Automated dependency updates | Compose BOM, Kotlin, AndroidX evolve fast | 30 min setup |

---

## Recommended Sequence

```
Now:        Priority 1 (release infra) — unblocks Play Store
Next:       Priority 2 (Maestro E2E) — replaces fragile bash tests
Then:       Priority 3 (feature gaps) — transaction detail, watch-only, coin control
v4.0:       Priority 4 (BIP157/158) — privacy upgrade, v9.26 integration
```
