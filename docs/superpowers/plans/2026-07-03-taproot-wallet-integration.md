# Taproot Wallet Integration (BIP86 derivation + receive) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The wallet derives a parallel **BIP86 `m/86'/20'/0'`** Taproot chain, hands out `dgb1p…` receive addresses via `getReceiveAddress(format=3)`, and watches them in the shared `allAddrs`/filter set — so incoming P2TR (and later DigiDollar) is credited. **Key-path receive only; no signing, no change-to-P2TR** (those are the next plan).

**Architecture:** Additive twin of the existing BIP84 chains. A second account xpub (`taprootPubKey`) + two new `BRAddress*` chains on `BRWalletStruct`, mirroring the legacy-recovery block. The address render/parse layer already handles witness-v1 (generic branch + the bech32m from the crypto-foundation plan), so **no `BRAddress` work**. Detection rides the existing BIP158 path (witness-version-agnostic); bloom is left P2TR-blind by design.

**Tech Stack:** C (breadwallet-core, in the `digibytewallet-core` submodule), JNI bridge, Kotlin `NativeBridge`, host-C + instrumented KATs.

## Global Constraints

- **Right key → right address (fund-loss guard):** taproot addresses MUST derive from `wallet->taprootPubKey` (the `m/86'` xpub), NEVER `wallet->masterPubKey` (the `m/84'` xpub). Deriving P2TR over the BIP84 key yields addresses unrecoverable from an `m/86'` seed import.
- **Canonicalization invariant (already satisfied — do not break):** both the derive path (`BRKeyTaprootAddress`, `BRKey.c:561`) and the recognize path (`BRAddressFromScriptPubKey` witness branch, `BRAddress.c:300-309`) funnel the identical 34-byte `{OP_1, 0x20, X(Q)}` into `BRBech32Encode(_, "dgb", …)` (bech32m). Do NOT add a second/alternate P2TR encoder, and do NOT alter the `data[1]=32` length byte or the `"dgb"` hrp at either site — divergence silently drops taproot receives from balance/watch.
- **No regression:** 42 security tests green; BIP84 receive/change, legacy recovery, and existing sync all unchanged. `BRWalletUnusedAddrs`'s widened arg must update EVERY caller in the same commit — no caller may silently default to the wrong chain.
- **Detection scope (per product decision):** taproot receive detection rides **BIP158** (`SyncMode.BOTH` default, or `COMPACT_FILTERS_ONLY`). Bloom stays P2TR-blind; `BLOOM_ONLY` and the 120s BIP158→bloom watchdog fallback will NOT see taproot — documented limitation, no bloom work in this plan.
- **Submodule:** C changes are in the `digibytewallet-core` submodule (commit via `GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git …`; bump the pin in the root commit). Do NOT push (feature branch `taproot-p2tr`).
- **Kotlin signature is frozen:** `getReceiveAddress(index: Int, format: Int)` stays 2-param (`SeedIsolationTest.kt:88-90` asserts it) — extend via new format VALUE `3`, never a new parameter. Keep BOTH `NativeBridge.kt` copies (core/src/main + native/src/androidTest) in sync.

---

### Task 1: BIP86 derivation twin + derivation KAT

**Files:** (submodule) `BRBIP32Sequence.h` (add `BIP86_PURPOSE`, decl), `BRBIP32Sequence.c` (add `BRBIP32MasterPubKeyBIP86`); (root) `native/src/test/host/bip86_derivation_kat/` (new host KAT)

**Interfaces produced:** `BRMasterPubKey BRBIP32MasterPubKeyBIP86(const void *seed, size_t seedLen)` — `m/86'/20'/0'` account xpub. Consumed by Task 2's install.

- [ ] **Step 1 — Constant.** `BRBIP32Sequence.h:43`: add `#define BIP86_PURPOSE 86` next to `BIP84_PURPOSE`. Reuse `DGB_COIN_TYPE`(20), `BIP84_ACCOUNT`(0), `SEQUENCE_EXTERNAL_CHAIN`(0)/`SEQUENCE_INTERNAL_CHAIN`(1).
- [ ] **Step 2 — Failing KAT.** Host-C KAT (reuse the Plan-1 host-KAT pattern; no emulator). Derive `m/86'/20'/0'/0/0..2` pubkeys via `BRBIP32PubKey(…, BRBIP32MasterPubKeyBIP86(seed), 0, i)`, render with `BRKeyTaprootAddress`, and assert each `dgb1p…` equals a reference computed independently (`bip_utils`/Python BIP86 with coin type 20 + bech32m hrp `dgb`) and pinned. **Also assert the derived receive string == `BRAddressFromScriptPubKey({OP_1,0x20,X})`** (the canonicalization round-trip). No DigiByte BIP86 vector exists publicly — generate + pin with a trusted reference, documenting the seed. RED: `BRBIP32MasterPubKeyBIP86` undefined.
- [ ] **Step 3 — Implement.** `BRBIP32Sequence.c`: clone the `BRBIP32MasterPubKeyBIP84` body (lines 140-171) verbatim, changing ONLY the purpose at line 159 from `BIP84_PURPOSE` to `BIP86_PURPOSE`. Keep `"Bitcoin seed"`, `DGB_COIN_TYPE`, account 0. Declare in `BRBIP32Sequence.h` mirroring the BIP84 decl (~:74).
- [ ] **Step 4 — GREEN + no regression.** KAT passes; `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` green.
- [ ] **Step 5 — Commit** (submodule BRBIP32Sequence.* + root host-KAT + pin bump).

---

### Task 2: Wallet struct + lifecycle + `BRWalletUnusedAddrs` 3-way widening + realloc-loop fix

**Files:** (submodule) `BRWallet.c` (struct 44-53, `BRWalletNew` 298-306, `BRWalletFree` 1612-1619, `BRWalletUnusedAddrs` 514-611, and EVERY `nativeSegwit` caller: 726, 735, 1148-1149, 337-340, 440-443), `BRWallet.h`

**Interfaces produced:** the taproot struct fields; `BRWalletUnusedAddrs(…, int scriptType)` where `scriptType` is `0=P2PKH`, `1=P2WPKH`, `2=P2TR` (replacing the binary `nativeSegwit`). No behavior change yet — `hasTaprootKey` stays `0` and no caller passes `2`, so taproot mode is dormant until Task 3 installs the key.

- [ ] **Step 1 — Struct fields** (after the legacy block ~:53): `BRMasterPubKey taprootPubKey; int hasTaprootKey; BRAddress *taprootExternalChain; BRAddress *taprootInternalChain;`.
- [ ] **Step 2 — Lifecycle.** `BRWalletNew` array_new block (298-306): `array_new(wallet->taprootExternalChain, 50); array_new(wallet->taprootInternalChain, 50); wallet->hasTaprootKey = 0;`. `BRWalletFree` (1612-1619): `array_free(wallet->taprootExternalChain); array_free(wallet->taprootInternalChain);` (wallet is freed+recreated every unlock — a missed free leaks per cycle).
- [ ] **Step 3 — Widen the seam.** `BRWalletUnusedAddrs`: replace `int nativeSegwit` with `int scriptType`. Chain select (524-528): `2`→`taprootExternalChain/taprootInternalChain`. mpk select (543-544): `2`→`wallet->taprootPubKey` (**never masterPubKey**). Converter (549-557): `2`→`BRKeyTaprootAddress(&key, address.s, sizeof(address))` (`BRKey.c:561`, from the crypto-foundation plan). Extend the "addrChain moved" check (573-574) and realloc-reassign (581-587) to know the taproot arrays. An out-of-range `scriptType` must NOT silently fall back to segwit.
- [ ] **Step 4 — Fix the realloc rebuild loop (fund-visibility).** The loop at `BRWallet.c:590-606` re-adds only the 4 primary chains and already OMITS the 4 legacy chains (latent eviction bug). Add `taprootExternalChain` + `taprootInternalChain` (mandatory), AND fix the legacy omission in the same pass (we're editing the exact loop; leaving legacy evicts recovery addrs on any array-growth realloc).
- [ ] **Step 5 — Update ALL callers** (same commit, mechanical `nativeSegwit`→`scriptType` with the same value): `BRWalletReceiveAddress`(726, stays `1`), `BRWalletInternalChangeAddress`(735, stays `1`), the gap pre-gen at 337-340 / 440-443, the register-tx top-up at 1148-1149. No caller passes `2` yet.
- [ ] **Step 6 — Verify no behavior change.** Build 3 ABIs; 42 security tests green; `getReceiveAddress(0,2)` returns the same BIP84 `dgb1q…` as before (taproot dormant).
- [ ] **Step 7 — Commit** (submodule + pin bump).

---

### Task 3: Install (`BRWalletSetTaprootKey`) + JNI threading + `getReceiveAddress(format=3)`

**Files:** (submodule) `BRWallet.c` (new `BRWalletSetTaprootKey`), `BRWallet.h`; (bridge) `jni_wallet.c` (createWallet 152/161, createWalletFromBytes 238, recoverWallet 293-311, recoverWalletFromBytes 395-397, `getReceiveAddress` 503-505); (root) both `NativeBridge.kt` copies (KDoc only) + instrumented test

**Interfaces produced:** `void BRWalletSetTaprootKey(BRWallet *wallet, BRMasterPubKey taprootMpk)` — sets `taprootPubKey`, `hasTaprootKey=1`, and pre-generates the gap+100 taproot external+internal windows (via the Task-2 `scriptType=2`). `getReceiveAddress(index, 3)` returns a `dgb1p…`.

- [ ] **Step 1 — Failing test (instrumented).** On `emulator-5554`: create a wallet from the SAME fixed seed as the Task-1 KAT, call `getReceiveAddress(0, 3)`, assert it equals the Task-1-pinned `m/86'/20'/0'/0/0` `dgb1p…`. RED: format 3 falls through to `useSegwit=0` (returns a non-taproot address).
- [ ] **Step 2 — Setter.** Implement `BRWalletSetTaprootKey`: set `taprootPubKey`, `hasTaprootKey=1`, then pre-gen gap+100 via `BRWalletUnusedAddrs(wallet, addrs, SEQUENCE_GAP_LIMIT_EXTERNAL+100, 0, 2)` (external) and `…INTERNAL+100, 1, 2)` (internal) — reuse the PLAIN gap constants (BRWallet.c:337-340 style), NOT the unused `_BIP84` ones.
- [ ] **Step 3 — Thread into 4 JNI constructors.** In each, derive `mpkBIP86 = BRBIP32MasterPubKeyBIP86(seed)` from the same seed as BIP84, and call `BRWalletSetTaprootKey(g_wallet, mpkBIP86)` right after `BRWalletNew`/`BRWalletNewDual` (for the Dual/restore path, in the pre-transaction window before bulk-adding txs, mirroring the legacy install at 392-393/395-408). Zero the seed as the existing paths do.
- [ ] **Step 4 — JNI + Kotlin receive.** `getReceiveAddress` (jni_wallet.c:503-505): add `format==3` → the taproot `scriptType=2` path. Update both `NativeBridge.kt` copies' KDoc to document `3 = Taproot (dgb1p)` — signature unchanged.
- [ ] **Step 5 — GREEN + regression.** The instrumented test passes (`getReceiveAddress(0,3)` == KAT address, derived from the `m/86'` xpub); `getReceiveAddress(0,2)` unchanged; 42 security tests green.
- [ ] **Step 6 — Commit** (submodule + bridge + root KDoc + pin bump).

---

### Task 4: allAddrs / gap-limit coherence (watch set + filter)

**Files:** (submodule) `BRWallet.c` (`BRWalletAllAddrs` 741-814, `BRWalletRegisterTransaction` top-up 1148-1149), `BRPeerManager.c` (`_BRPeerManagerUpdateFilter` gap pre-gen 350-353, `_peerRelayedTx` recheck 1154-1168); (root) an instrumented coherence test

**Interfaces:** `BRWalletAllAddrs` (the sole source feeding bloom + BIP158) returns taproot addresses; taproot gap auto-extends when a taproot address is used.

- [ ] **Step 1 — Failing test.** Instrumented: after createWallet, assert the taproot receive address is in the wallet's watch set — via a small test-only JNI hook `walletContainsAddress(addr)` (calls `BRWalletContainsAddress`) OR by asserting `BRWalletAllAddrs` (through a JNI count/list hook) includes it. RED: `BRWalletAllAddrs` omits the taproot chains, so the address isn't watched.
- [ ] **Step 2 — `BRWalletAllAddrs` (741-814).** Extend the `addrsCount==0` sizing return (814) to include both taproot chains, and the `addrs!=NULL` partition (the `addrsCount/4` quota + `primaryTotal` at 785) so taproot addresses are actually emitted without misindexing the caller buffer. Rebalance the quota math for the added chains.
- [ ] **Step 3 — Auto-extension.** Add a taproot `BRWalletUnusedAddrs(…, scriptType=2)` top-up to `BRWalletRegisterTransaction` (1148-1149, alongside the existing segwit top-up) so the taproot gap extends when a taproot addr is seen. Add taproot gap pre-gen to `_BRPeerManagerUpdateFilter` (350-353) and the `_peerRelayedTx` recheck (1154-1168) so the rebuilt filter watches the next taproot window.
- [ ] **Step 4 — GREEN + realloc regression.** The watch-set test passes. Add a regression asserting the taproot address SURVIVES a `BRWalletUnusedAddrs` realloc (generate > 50 addresses to force array growth, then re-check containment) — guards the Step-3/Task-3 rebuild-loop fix. 42 security tests green; native build green.
- [ ] **Step 5 — Commit** (submodule + root test + pin bump).

---

## Done when
- `getReceiveAddress(0,3)` returns the KAT-pinned `m/86'/20'/0'/0/0` `dgb1p…`, derived from the `m/86'` xpub (not `m/84'`).
- That address is in `allAddrs` / the watch set and survives a rebuild realloc.
- BIP84 receive/change, legacy recovery, and the 42 security tests are unchanged.
- Derivation + canonicalization round-trip KATs green.

## Deferred / documented
- **Bloom P2TR insert** — taproot detection requires BIP158 (BOTH/COMPACT_FILTERS_ONLY); BLOOM_ONLY and the watchdog fallback don't see it. (Product decision: compact filters are the standard.)
- **Change-to-P2TR** and **taproot signing** — next plan (needs `BRKeyTaprootOutputKey` tweak + `BRKeySchnorrSign` in the spend path).
- On-chain **receive proof on mainnet** (Taproot is live on DGB mainnet) — run after this plan lands, before the signing plan.
