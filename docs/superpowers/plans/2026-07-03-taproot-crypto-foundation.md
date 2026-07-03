# Taproot Crypto Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the C SPV core the cryptographic primitives for BIP340/BIP341 Taproot **key-path** — a Schnorr-capable secp256k1, bech32m addresses, BIP340 signing, and a BIP86 taptweaked P2TR address — all KAT-verified, with ECDSA, Digi-ID, and the 42 security tests still green.

**Architecture:** Additive to `BRKey`/`BRBech32`. secp256k1 is bumped in place (still `#include`-compiled into `BRKey.c`, still used only from `BRKey.c`). No wallet/derivation/signing-integration here — this plan ends at "the core can produce a valid `dgb1p…` address and a valid BIP340 signature from a `BRKey`." Derivation, receive-detection, and transaction signing are follow-up plans.

**Tech Stack:** C (breadwallet-core style), secp256k1 (schnorrsig+extrakeys), CMake (`native/CMakeLists.txt`), JUnit KATs under `native/src/androidTest` + `core/src/test`.

## Global Constraints

- **secp256k1 → ONE canonical vendored copy** carrying modules `ecdh + recovery + extrakeys + schnorrsig`. The current tree has TWO copies (`native/src/main/jni/digibytewallet-core/secp256k1` — the one `BRKey.c:59-60` actually `#include`s — and `native/src/main/secp/secp256k1` — the one `native/CMakeLists.txt:118-120` puts on the include path); both currently ship only `ecdh + recovery`. End state: one copy, referenced consistently by both the `#include` and the CMake include dirs.
- **No regressions, verified every task:** `BRKeySign` (ECDSA/DER), `BRKeyCompactSign` (recoverable ECDSA, Digi-ID), and all **42 security tests** (`core/src/test/java/io/digibyte/core/security/`) stay green. `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` and `:native:assembleMainnetDebug` pass after each task.
- **Encapsulation:** secp symbols are used only inside `BRKey.c` (the amalgamation-include pattern). Do not expose secp types across compilation units.
- **Addresses:** hrp = `"dgb"`. bech32 checksum constant `1` for witness v0; **bech32m constant `0x2bc830a3` for v1+** (BIP350).
- **Crypto test vectors are transcribed from the authoritative BIP sources, never invented.** BIP340: `github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv`. BIP341 key-path: `bip-0341/wallet-test-vectors.json`. BIP86 addresses: `bip-0086.mediawiki`. Any DGB-specific (`dgb` hrp) value is cross-checked against a reference (DigiByte Core `deriveaddresses`, or a Python `bip_utils`/reference-impl computation) and then pinned.
- **Zero key material** (`BRKey.secret`, seeds, scratch) after use, matching existing `BRKey` discipline.

---

### Task 1: Bump secp256k1 to a Schnorr/extrakeys build, reconcile to one copy, keep ECDSA + Digi-ID green

**Files:**
- Replace/vendor: `native/src/main/jni/digibytewallet-core/secp256k1/**` (canonical copy)
- Delete: `native/src/main/secp/secp256k1/**` (duplicate) — or make it the canonical one; pick ONE
- Modify: `native/CMakeLists.txt:118-120` (include dirs → the canonical copy)
- Modify: `native/src/main/jni/digibytewallet-core/BRKey.c:49` (module defines), `:59-60` (amalgamation include), `:77,:85,:109,:122` (deprecated tweak-API call sites)
- Test: `core/src/test/java/io/digibyte/core/security/` (existing 42), plus a small `BRKey` ECDSA/Digi-ID sanity KAT if one isn't already present

**Interfaces:**
- Consumes: nothing new.
- Produces: a secp256k1 amalgamation exposing `secp256k1_keypair_create`, `secp256k1_schnorrsig_sign32`, `secp256k1_schnorrsig_verify`, `secp256k1_xonly_pubkey_parse/_serialize/_from_pubkey`, `secp256k1_xonly_pubkey_tweak_add` — available to `BRKey.c`. Existing `secp256k1_ecdsa_sign`, `_ecdsa_sign_recoverable`, `_ec_pubkey_create` etc. still resolve (via shims if renamed).

- [ ] **Step 1: Vendor the Schnorr-capable secp256k1.** Obtain a secp256k1 that ships `src/modules/{ecdh,recovery,extrakeys,schnorrsig}` and the headers `include/secp256k1_extrakeys.h` + `include/secp256k1_schnorrsig.h` — take it from **DigiByte Core's `src/secp256k1`** (the exact source its node uses for Taproot; matches DGB's activated ruleset) or upstream `bitcoin-core/secp256k1` v0.4.x. Place it as the single canonical copy at `native/src/main/jni/digibytewallet-core/secp256k1/`. Remove the second copy under `native/src/main/secp/`.

- [ ] **Step 2: Point the build at one copy.** Update `native/CMakeLists.txt:118-120` include dirs to the canonical `src/main/jni/digibytewallet-core/secp256k1/` path so the `#include "secp256k1/..."` in `BRKey.c` and the CMake include path resolve to the same tree. Confirm no other target references the deleted copy.

- [ ] **Step 3: Enable the modules.** In `BRKey.c` near line 49, alongside `#define ENABLE_MODULE_RECOVERY 1`, add `#define ENABLE_MODULE_ECDH 1` (if already implicitly needed), `#define ENABLE_MODULE_EXTRAKEYS 1`, `#define ENABLE_MODULE_SCHNORRSIG 1` before the `#include "secp256k1/src/secp256k1.c"` at :59-60 (and its `basic-config.h`). Match whatever config mechanism the vendored version expects (some versions use `src/precomputed_ecmult.c` / a different amalgamation entrypoint — include what that version documents).

- [ ] **Step 4: Shim the renamed tweak API.** Modern secp renamed the functions used at `BRKey.c:77/85/109/122`: `secp256k1_ec_privkey_tweak_add → secp256k1_ec_seckey_tweak_add`, `_privkey_tweak_mul → _seckey_tweak_mul` (and `_ec_pubkey_tweak_add/_mul` may retain names — verify against the vendored headers). For each removed/renamed name, either update the call site or add a `#define` shim (e.g. `#define secp256k1_ec_privkey_tweak_add secp256k1_ec_seckey_tweak_add`) placed before the amalgamation include. Do NOT change tweak behaviour — this is a rename only.

- [ ] **Step 5: Build.** Run: `./gradlew :native:assembleMainnetDebug 2>&1 | tail -5`. Expected: `BUILD SUCCESSFUL`. If the amalgamation complains about a missing module source or a duplicate symbol, resolve include set per the vendored version's docs.

- [ ] **Step 6: Prove no crypto regression.** Run the security suite: `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"`. Expected: all 42 pass. If any ECDSA/Digi-ID assertion changed, the bump altered signing behaviour — stop and reconcile (RFC6979 nonce, DER serialization, recoverable recid must be byte-identical to before).

- [ ] **Step 7: Commit.**
```bash
git add native/CMakeLists.txt native/src/main/jni/digibytewallet-core/secp256k1 native/src/main/jni/digibytewallet-core/BRKey.c
git rm -r native/src/main/secp/secp256k1   # if that copy was retired
git commit -m "build(taproot): bump secp256k1 to schnorrsig+extrakeys, reconcile to one copy"
```

---

### Task 2: Version-aware bech32m (BIP350) in BRBech32

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRBech32.c:87` (decode checksum test), `:137` (encode checksum XOR)
- Test: `native/src/androidTest/java/io/digibyte/native_core/Bech32mKatTest.kt` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `BRBech32Encode`/`BRBech32Decode` that use constant `1` for a v0 program and `0x2bc830a3` for v1+ — so a `dgb1p…` (witness v1) string round-trips, while `dgb1q…` (v0) is unchanged. `ver` is already available at both sites (`BRBech32Decode` sets `ver` at :79; `BRBech32Encode` sets `ver` at :122).

- [ ] **Step 1: Write the failing test.** A KAT that (a) encodes a fixed 32-byte witness-v1 program `{OP_1,0x20,<32B>}` with hrp `dgb` and asserts it decodes back to the same program bytes, and (b) asserts a fixed v0 20-byte P2WPKH program still round-trips (regression). Cross-check the v1 string against a reference (DigiByte Core `deriveaddresses` on a `tr(<key>)` descriptor, or Python `bech32m`) and pin the expected `dgb1p…` string. Expected before the fix: the v1 round-trip FAILS (decode rejects, `chk != 1`).

```kotlin
// native/src/androidTest/java/io/digibyte/native_core/Bech32mKatTest.kt
// Program bytes and expected string PINNED against a bech32m reference during impl.
// v1: {0x51,0x20, <32-byte X(Q)>}  -> "dgb1p…"   (must round-trip)
// v0: {0x00,0x14, <20-byte hash160>} -> "dgb1q…" (regression, unchanged)
```

- [ ] **Step 2: Run it, confirm the v1 case fails.** `ANDROID_SERIAL=emulator-5554 ./gradlew :native:connectedMainnetDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.digibyte.native_core.Bech32mKatTest`. Expected: FAIL on the v1 round-trip.

- [ ] **Step 3: Make it version-aware.** In `BRBech32Decode` change the `chk != 1` at line 87 to `chk != (ver == 0 ? 1u : 0x2bc830a3u)`. In `BRBech32Encode` change `chk ^= 1;` at line 137 to `chk ^= (ver == 0 ? 1u : 0x2bc830a3u);`. (`polymod` and everything else are identical between bech32 and bech32m.)

- [ ] **Step 4: Run it, confirm pass + no v0 regression.** Same command. Expected: PASS (both v1 and v0). Also re-run `*.security.*` to be safe.

- [ ] **Step 5: Commit.**
```bash
git add native/src/main/jni/digibytewallet-core/BRBech32.c native/src/androidTest/java/io/digibyte/native_core/Bech32mKatTest.kt
git commit -m "feat(taproot): version-aware bech32m (BIP350) for witness v1 addresses"
```

---

### Task 3: BIP340 Schnorr signing primitive (BRKeySchnorrSign) + tagged-hash + x-only helpers

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRKey.h` (declare `BRKeySchnorrSign`, `BRKeyTaggedHash`)
- Modify: `native/src/main/jni/digibytewallet-core/BRKey.c` (implement; mirrors the `BRKeySign` block near :352)
- Bridge (optional, for the KAT): a JNI `schnorrSign(seed/key, msg32)` under `native/src/main/jni/bridge/` + a stub in `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt`
- Test: `native/src/androidTest/java/io/digibyte/native_core/Bip340KatTest.kt` (new)

**Interfaces:**
- Consumes: secp `keypair_create`, `schnorrsig_sign32`, `schnorrsig_verify`, `xonly_pubkey_*` (Task 1).
- Produces:
  - `void BRKeyTaggedHash(const char *tag, const uint8_t *msg, size_t msgLen, UInt256 *out)` — `SHA256(SHA256(tag) ‖ SHA256(tag) ‖ msg)`.
  - `size_t BRKeySchnorrSign(BRKey *key, uint8_t *sig64, UInt256 md)` — BIP340 sign of 32-byte `md` under `key`, writing 64 bytes; returns 64 on success, 0 on failure. Uses the **untweaked** key (this is the raw BIP340 primitive; the taptweak is applied by the caller in Task 4 / the signing-integration plan).

- [ ] **Step 1: Write the failing test** using BIP340 vectors transcribed from `bip-0340/test-vectors.csv` (indices 0–3 are deterministic with `aux_rand = 0…0`). For each: set `key.secret` = the vector's secret key, call `BRKeySchnorrSign`, assert the 64-byte sig equals the vector, and assert `secp256k1_schnorrsig_verify` accepts it under the vector's pubkey. Include one negative (vector's "verification fails" case) via a verify-only JNI if exposed.

- [ ] **Step 2: Run it, confirm it fails** (function not defined). Command as in Task 2.

- [ ] **Step 3: Implement `BRKeyTaggedHash` and `BRKeySchnorrSign`** in `BRKey.c`. Sketch:
```c
size_t BRKeySchnorrSign(BRKey *key, uint8_t *sig64, UInt256 md) {
    secp256k1_keypair kp;
    size_t r = 0;
    pthread_once(&_ctx_once, _ctx_init);              // reuse the existing _ctx init pattern
    if (secp256k1_keypair_create(_ctx, &kp, key->secret.u8) &&
        secp256k1_schnorrsig_sign32(_ctx, sig64, md.u8, &kp, NULL)) r = 64;   // aux_rand NULL = deterministic-ish; for KAT pass zeros to match vectors
    var_clean(&kp);
    return r;
}
```
Match the vector expectation for `aux_rand` (BIP340 vectors 0–3 use all-zero aux_rand; pass a 32-byte zero buffer via `secp256k1_schnorrsig_sign32`'s aux argument to reproduce them exactly).

- [ ] **Step 4: Run it, confirm pass.** All BIP340 vectors match; verify accepts. Re-run `*.security.*`.

- [ ] **Step 5: Commit.**
```bash
git add native/src/main/jni/digibytewallet-core/BRKey.c native/src/main/jni/digibytewallet-core/BRKey.h native/src/main/jni/bridge native/src/androidTest/java/io/digibyte/native_core/Bip340KatTest.kt native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt
git commit -m "feat(taproot): BIP340 Schnorr signing + tagged-hash primitive (KAT-verified)"
```

---

### Task 4: BIP341/BIP86 taptweak + BRKeyTaprootAddress

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRKey.h` (declare `BRKeyTaprootAddress`, `BRKeyTaprootOutputKey`)
- Modify: `native/src/main/jni/digibytewallet-core/BRKey.c` (implement; mirrors `BRKeySegwitAddress` at :322, reusing its `segwitVersion` shape)
- Test: `native/src/androidTest/java/io/digibyte/native_core/TaprootAddressKatTest.kt` (new)

**Interfaces:**
- Consumes: bech32m (Task 2), `xonly_pubkey_from_pubkey` + `xonly_pubkey_tweak_add` + `BRKeyTaggedHash` (Tasks 1, 3).
- Produces:
  - `int BRKeyTaprootOutputKey(BRKey *key, uint8_t out32[32])` — BIP86 key-path-only: `P = x_only(pubkey)`; `t = TaggedHash("TapTweak", P)`; `Q = P + t·G`; write `X(Q)`. Returns 1 on success.
  - `size_t BRKeyTaprootAddress(BRKey *key, char *addr, size_t addrLen)` — `{OP_1, 0x20, X(Q)}` → bech32m with hrp `"dgb"`; returns bytes written.

- [ ] **Step 1: Write the failing test.** Two layers: (a) **taptweak KAT** — using a BIP341 `wallet-test-vectors.json` key-path-only case, assert `BRKeyTaprootOutputKey` produces the vector's tweaked output key `X(Q)` from its internal key. (b) **address KAT** — a fixed key → `BRKeyTaprootAddress` → a `dgb1p…` string cross-checked against a reference (DGB Core `deriveaddresses 'tr(<pubkey>)'` or a Python taproot impl with hrp `dgb`) and pinned. Expected: FAIL (functions absent).

- [ ] **Step 2: Run it, confirm it fails.**

- [ ] **Step 3: Implement.** BIP86 has an empty script tree, so the tweak is `t = TaggedHash("TapTweak", P_xonly_32)` (no merkle root appended). Sketch:
```c
int BRKeyTaprootOutputKey(BRKey *key, uint8_t out32[32]) {
    secp256k1_pubkey pk; secp256k1_xonly_pubkey xo; UInt256 t; int parity = 0;
    // derive full pubkey from key, xonly_pubkey_from_pubkey -> xo, serialize xo -> P32
    // t = BRKeyTaggedHash("TapTweak", P32, 32)
    // secp256k1_xonly_pubkey_tweak_add(_ctx, &pkQ, &xo, t.u8) -> xonly serialize -> out32
    ...
}
```
`BRKeyTaprootAddress` builds `data[0]=OP_1; data[1]=0x20; memcpy(data+2, out32, 32)` and calls `BRBech32Encode(addr, "dgb", data)`.

- [ ] **Step 4: Run it, confirm pass.** taptweak matches the BIP341 vector; the `dgb1p…` address matches the pinned reference. Re-run `*.security.*`.

- [ ] **Step 5: Commit.**
```bash
git add native/src/main/jni/digibytewallet-core/BRKey.c native/src/main/jni/digibytewallet-core/BRKey.h native/src/androidTest/java/io/digibyte/native_core/TaprootAddressKatTest.kt
git commit -m "feat(taproot): BIP86 taptweak + BRKeyTaprootAddress (dgb1p, KAT-verified)"
```

---

## Done when
- `:native` builds; 42 security tests + Digi-ID green across all four commits.
- BIP340 signing, BIP341 taptweak, and a `dgb1p…` address are each KAT-verified against authoritative vectors.
- No secp symbols leak outside `BRKey.c`; one canonical secp copy remains.

## Next plans (not here)
- **Derivation + wallet integration:** BIP86 twin chains, second account xpub, `allAddrs`/gap-limit, `getReceiveAddress(format=3)`.
- **Receive + detection:** prove a P2TR receive is credited via BIP158 (mainnet).
- **Key-path signing integration:** `_BRTransactionTaprootSighash` + witness-v1 branch in `BRTransactionSign`; P2TR self-send proof (mainnet).
- **DigiDollar show → send:** pin the DD `OP_RETURN` format on testnet26, then decoder + transfer builder.
