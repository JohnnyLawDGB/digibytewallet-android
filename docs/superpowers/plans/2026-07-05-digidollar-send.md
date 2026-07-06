# DigiDollar SEND (transfer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build (and prove on testnet26) a DigiDollar transfer: `BRWalletCreateDigiDollarTransfer` produces an unsigned type-2 DD transfer that signs through the mainnet-proven `BRWalletSignTransaction` and broadcasts.

**Architecture:** Two new C units in the `digibytewallet-core` submodule — a `TD…` Base58Check address decoder and a transfer builder modeled on the DGB builder `BRWalletCreateTxForOutputsEx`. Signing reuses the existing P2TR key-path + P2WPKH branches (no new crypto). Host KATs + a real testnet26 on-chain proof.

**Tech Stack:** C (breadwallet fork), host KAT via `clang` (as the taproot/DD KATs), the synced v9.26.3 testnet26 node for broadcast.

**Source of truth:** `docs/superpowers/specs/2026-07-05-digidollar-send-design.md`; wire format `docs/superpowers/specs/2026-07-04-digidollar-wire-format.md` (§3.1 outputs, §3.2 binding, §4 address RESOLVED, §5 conservation, §6 signing).

## Global Constraints

- **Fund-moving code — fail closed.** The builder returns **NULL** (never a half-built tx) on: `cents==0`, no taproot key, DD balance `< cents`, or DGB `< fee`.
- **Strict DD conservation:** `Σ(selected DD input cents) == cents (recipient) + ddChangeCents`. The DD change output captures the **entire** remainder — **never drop a 1..99-cent dust remainder** (consensus enforces equality; wire spec §5.4).
- **Recipient key verbatim:** emit `51 20 <recipientKey32>` at value 0, **no re-tweak** (wire spec §3.1). Re-tweaking = fund loss.
- **DD change to a key we own:** an **internal taproot** address (`BRWalletUnusedAddrs(…, internal=1, scriptType=2)`), value 0.
- **Output order is consensus-significant** (positional binding): `vout[0]`=recipient DD, `vout[1]`=DD change (if any), then DGB change (nonzero, skipped by binding), **OP_RETURN LAST**. **NEVER shuffle** (unlike the DGB builder).
- **OP_RETURN:** `6a 02 4444 01 02 <cents CScriptNum> [<ddChangeCents CScriptNum>]` — minimal CScriptNum, one push per DD output in vout order, **no count field**.
- **Header:** `version = 0x02000770`, `lockTime = 0`, every input `sequence = TXIN_SEQUENCE (UINT32_MAX)`.
- **DD inputs added at value 0** (on-chain nValue — the BIP341 sighash commits input amounts). DGB fee inputs at their real value.
- **Signing is NOT in this plan's new code** — reuse `BRWalletSignTransaction(wallet, tx, 0, seed, seedLen)`; it already signs P2TR (empty 64-byte witness) + P2WPKH (SIGHASH_ALL). Confirmed on the taproot signing KAT + mainnet.
- **No regression:** DGB send path untouched; `:app:assembleMainnetDebug` green; 42 security tests green.
- **Submodule commits** (`BRDigiDollar.c/.h`, `BRWallet.c/.h`) via `git -C native/src/main/jni/digibytewallet-core commit -F -` (NOT `eval`); root commits for KATs + pin bump.
- **Address golden vector (real):** `TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC` → key `dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b`; testnet version bytes `b1 29`, mainnet `52 85`.

---

## File Structure

- **Modify (submodule):** `BRDigiDollar.c` / `.h` — add `BRDigiDollarAddressDecode` + the internal `_ddWriteScriptNum` encoder.
- **Modify (submodule):** `BRWallet.c` / `.h` — add `BRWalletCreateDigiDollarTransfer`.
- **Create (root):** `native/src/test/host/digidollar_send_kat/{digidollar_send_kat_main.c,run.sh}` — address decode + builder + sign/verify KATs.

---

## Task 1: `BRDigiDollarAddressDecode` (TD/DD Base58Check → key) + host KAT

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` (add the function), `BRDigiDollar.h` (declare it)
- Create: `native/src/test/host/digidollar_send_kat/digidollar_send_kat_main.c` + `run.sh`

**Interfaces:**
- Consumes: `BRBase58CheckDecode(uint8_t *data, size_t dataLen, const char *str) -> size_t` (returns decoded payload length, checksum verified) from `BRBase58.h`.
- Produces: `int BRDigiDollarAddressDecode(uint8_t key32[32], const char *addr, int isTestnet);` (1 = ok, 0 = fail).

- [ ] **Step 1: Write the failing KAT.** Create `digidollar_send_kat_main.c`:

```c
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "BRDigiDollar.h"
static int g=0; static void ck(int c,const char*d){printf(c?"PASS: %s\n":"FAIL: %s\n",d); if(!c)g++;}

int main(void){
    // real testnet TD golden vector
    const char *TD = "TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC";
    uint8_t exp[32] = {
        0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
        0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b };
    uint8_t key[32];
    ck(BRDigiDollarAddressDecode(key, TD, 1) == 1, "decode real TD address (testnet)");
    ck(memcmp(key, exp, 32) == 0, "decoded key == golden 32-byte key");
    // wrong network: TD is testnet, decoding as mainnet must fail (version mismatch)
    ck(BRDigiDollarAddressDecode(key, TD, 0) == 0, "TD rejected when isTestnet=0 (wrong version)");
    // corrupted checksum (flip last char) -> fail
    char bad[64]; strcpy(bad, TD); bad[strlen(bad)-1] = (bad[strlen(bad)-1]=='C'?'D':'C');
    ck(BRDigiDollarAddressDecode(key, bad, 1) == 0, "corrupted checksum -> fail");
    // a normal DGB address is not a DD address -> fail
    ck(BRDigiDollarAddressDecode(key, "dgb1q6hwtu62c3wmdmexdpgpwmcycc7htrhr0f5w62z", 1) == 0, "bech32 addr -> fail");
    // NULL-safe
    ck(BRDigiDollarAddressDecode(key, NULL, 1) == 0, "NULL addr -> fail");
    printf(g==0?"\nALL PASS\n":"\n%d FAIL\n",g); return g?1:0;
}
```

Create `run.sh` by copying `native/src/test/host/digidollar_realtx_kat/run.sh` and pointing it at `digidollar_send_kat_main.c` (its source list — BRDigiDollar.c, BRTransaction.c, BRAddress.c, BRSet.c, BRKey.c, BRBase58.c, BRBech32.c, BRCrypto.c, BRDigiAsset.c, BRBIP32Sequence.c, BRBIP39Mnemonic.c, crypto/* — already covers this task and Tasks 2/3).

- [ ] **Step 2: Run to verify it fails.** `bash native/src/test/host/digidollar_send_kat/run.sh` → link error: `BRDigiDollarAddressDecode` undefined.

- [ ] **Step 3: Implement.** In `BRDigiDollar.c` add (and `#include "BRBase58.h"` if not already included):

```c
// Decodes a DigiDollar address ("TD…" testnet / "DD…" mainnet, Base58Check) into its 32-byte
// taproot output key. Returns 1 on success, 0 on any failure (fail closed).
int BRDigiDollarAddressDecode(uint8_t key32[32], const char *addr, int isTestnet)
{
    if (! addr || ! key32) return 0;
    uint8_t data[64];
    size_t len = BRBase58CheckDecode(data, sizeof(data), addr); // verifies 4-byte double-SHA256 checksum
    if (len != 34) return 0;                                     // 2-byte version + 32-byte key
    uint8_t v0 = isTestnet ? 0xb1 : 0x52, v1 = isTestnet ? 0x29 : 0x85; // "TD" / "DD"
    if (data[0] != v0 || data[1] != v1) return 0;
    memcpy(key32, data + 2, 32);
    return 1;
}
```

In `BRDigiDollar.h`, after the existing declarations:

```c
// Decode a DigiDollar address (TD… testnet / DD… mainnet Base58Check) to its 32-byte taproot
// output key. Returns 1 on success (writes key32), 0 on failure (bad checksum/version/length).
int BRDigiDollarAddressDecode(uint8_t key32[32], const char *addr, int isTestnet);
```

- [ ] **Step 4: Run to verify it passes.** `bash …/run.sh` → `ALL PASS`.
- [ ] **Step 5: Regression.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` green; `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*" 2>&1 | tail -5` 42/42.
- [ ] **Step 6: Commit.** Submodule (`BRDigiDollar.c/.h`) then root (KAT + pin bump).

---

## Task 2: `BRWalletCreateDigiDollarTransfer` builder + host KAT

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` (add internal `_ddWriteScriptNum`), `BRDigiDollar.h` (declare it non-static for the builder's TU — OR define the builder in BRWallet.c and expose `_ddWriteScriptNum` via BRDigiDollar.h)
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.c` (add the builder), `BRWallet.h` (declare it)
- Modify: `native/src/test/host/digidollar_send_kat/digidollar_send_kat_main.c` (add builder tests)

**Interfaces:**
- Consumes: `BRWalletDigiDollarUTXOs`, `BRDigiDollarOutputAmount`, `BRWalletUTXOs`, `BRWalletUnusedAddrs(wallet, addrs, gapLimit, internal, scriptType)`, `BRWalletMinOutputAmount`, `BRAddressScriptPubKey`, `_txFee`, `BRTransactionVSize`, `TX_OUTPUT_SIZE`, `TXIN_SEQUENCE`. `size_t BRDigiDollarWriteScriptNum(int64_t v, uint8_t out[9]);` (new, exposed via BRDigiDollar.h).
- Produces: `BRTransaction *BRWalletCreateDigiDollarTransfer(BRWallet *wallet, const uint8_t recipientKey32[32], uint64_t cents);`

- [ ] **Step 1: Write the failing builder KAT.** Add to `digidollar_send_kat_main.c` a test that reuses the wiring-KAT wallet setup (all-zeros mnemonic, `BRWalletSetTaprootKey`, credit DD via a registered DD tx), then builds a transfer. Include the DD-crediting helper (copy the confirmed pattern from `native/src/test/host/digidollar_wallet_kat/digidollar_wallet_kat_main.c`: non-NULL placeholder sig/witness, serialize→parse for `txHash`, `blockHeight` set so it's confirmed). Fund the wallet with a **10000-cent** DD UTXO, then:

```c
    // recipientKey32 = the golden TD key
    uint8_t rk[32] = {0xdc,0xea,0x60,0x96,0x99,0x3f,0x47,0x81,0x40,0x2e,0x76,0x3c,0x9d,0x36,0x09,0x79,
                      0xc3,0xcf,0x66,0xa4,0x38,0x18,0xc9,0x5b,0x90,0x87,0xf0,0x88,0xcf,0x62,0x63,0x1b};
    // NB: wallet also needs DGB for the fee — credit a P2WPKH UTXO to the wallet first (see setup).
    BRTransaction *t = BRWalletCreateDigiDollarTransfer(w, rk, 4000); // send $40 of the $100 held
    ck(t != NULL, "builder returns a tx");
    ck(t->version == 0x02000770, "version 0x02000770");
    // vout0 recipient DD: 51 20 <rk>, value 0
    ck(t->outputs[0].amount==0 && t->outputs[0].scriptLen==34 && t->outputs[0].script[0]==0x51
       && memcmp(t->outputs[0].script+2, rk, 32)==0, "vout0 recipient DD verbatim, value 0");
    // vout1 DD change: 51 20 <ours>, value 0 (6000 cents change)
    ck(t->outputs[1].amount==0 && t->outputs[1].scriptLen==34 && t->outputs[1].script[0]==0x51,
       "vout1 DD change zero-value P2TR");
    // last output OP_RETURN with [4000,6000]: 6a 02 4444 01 02 02<a01e> 02<7017>? -> compute:
    //   4000 = 0x0fa0 -> LE minimal a0 0f ; 6000 = 0x1770 -> 70 17
    BRTxOutput *op = &t->outputs[t->outCount-1];
    uint8_t exp_or[] = {0x6a,0x02,0x44,0x44,0x01,0x02, 0x02,0xa0,0x0f, 0x02,0x70,0x17};
    ck(op->scriptLen==sizeof(exp_or) && memcmp(op->script,exp_or,sizeof(exp_or))==0,
       "OP_RETURN == 6a 02 4444 0102 [4000] [6000]");
    // conservation: decode the built tx's own DD amounts, sum == selected input DD (10000)
    int64_t a[8]; int n=BRDigiDollarDecodeAmounts(t,a,8);
    ck(n==2 && a[0]+a[1]==10000, "strict conservation: out DD == in DD (10000c)");
    // NULL cases:
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 0)==NULL, "cents==0 -> NULL");
    ck(BRWalletCreateDigiDollarTransfer(w, rk, 999999)==NULL, "cents > DD balance -> NULL");
    BRTransactionFree(t);
```

- [ ] **Step 2: Run to verify it fails.** `bash …/run.sh` → `BRWalletCreateDigiDollarTransfer` / `BRDigiDollarWriteScriptNum` undefined.

- [ ] **Step 3: Add the CScriptNum encoder.** In `BRDigiDollar.c` (and declare in `BRDigiDollar.h`):

```c
// Minimal signed little-endian CScriptNum encode of a non-negative value; writes to out (<=9 bytes),
// returns the byte length (0 if v==0). Inverse of _ddReadScriptNum. Positive-only (DD amounts > 0).
size_t BRDigiDollarWriteScriptNum(int64_t v, uint8_t out[9])
{
    if (v <= 0) return 0;
    uint64_t a = (uint64_t)v;
    size_t len = 0;
    while (a) { out[len++] = (uint8_t)(a & 0xff); a >>= 8; }
    if (out[len - 1] & 0x80) out[len++] = 0x00; // sign byte so it reads back positive
    return len;
}
```
Header decl:
```c
size_t BRDigiDollarWriteScriptNum(int64_t v, uint8_t out[9]);
```

- [ ] **Step 4: Implement the builder** in `BRWallet.c` (needs `#include "BRDigiDollar.h"` — already present from the wiring increment). Add after `BRWalletForceCreateTxForOutputs`:

```c
#define DD_MIN_FEE 10000000ULL   // 0.1 DGB floor, matching the DigiByte Core DD builder (wire spec §6)

// Builds an UNSIGNED DigiDollar transfer paying `cents` to `recipientKey32`. Selects DD UTXOs to cover
// `cents` and DGB UTXOs for the fee, emits recipient DD + DD change + DGB change + OP_RETURN, version
// 0x02000770. Returns the unsigned tx (caller signs with BRWalletSignTransaction), or NULL on failure.
BRTransaction *BRWalletCreateDigiDollarTransfer(BRWallet *wallet, const uint8_t recipientKey32[32],
                                                uint64_t cents)
{
    assert(wallet != NULL); assert(recipientKey32 != NULL);
    if (cents == 0 || ! wallet->hasTaprootKey) return NULL;

    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;

    pthread_mutex_lock(&wallet->lock);

    // --- collect (utxo, cents) for our DD UTXOs, sort smallest-first ---
    size_t ddN = array_count(wallet->ddUtxos);
    struct { BRUTXO u; int64_t c; } sel[ddN > 0 ? ddN : 1];
    size_t m = 0;
    for (size_t i = 0; i < ddN; i++) {
        BRTransaction *dt = BRSetGet(wallet->allTx, &wallet->ddUtxos[i].hash);
        if (! dt) continue;
        int64_t c = BRDigiDollarOutputAmount(dt, wallet->ddUtxos[i].n);
        if (c <= 0) continue;
        sel[m].u = wallet->ddUtxos[i]; sel[m].c = c; m++;
    }
    for (size_t i = 1; i < m; i++) { // insertion sort ascending by cents
        struct { BRUTXO u; int64_t c; } k = sel[i]; size_t j = i;
        while (j > 0 && sel[j-1].c > k.c) { sel[j] = sel[j-1]; j--; }
        sel[j] = k;
    }
    uint64_t selDD = 0; size_t ddIn = 0;
    for (size_t i = 0; i < m && selDD < cents; i++) { selDD += (uint64_t)sel[i].c; ddIn++; }
    if (selDD < cents) { pthread_mutex_unlock(&wallet->lock); BRTransactionFree(tx); return NULL; }
    uint64_t ddChange = selDD - cents;

    // --- outputs: recipient DD, then DD change (order matters; OP_RETURN added last) ---
    uint8_t rspk[34] = { 0x51, 0x20 }; memcpy(rspk + 2, recipientKey32, 32);
    BRTransactionAddOutput(tx, 0, rspk, 34);                     // vout0 recipient (verbatim, no re-tweak)
    if (ddChange > 0) {
        BRAddress ca = BR_ADDRESS_NONE;
        BRWalletUnusedAddrs(wallet, &ca, 1, 1, 2);               // internal taproot change (we own it)
        uint8_t cspk[42]; size_t cl = BRAddressScriptPubKey(cspk, sizeof(cspk), ca.s);
        BRTransactionAddOutput(tx, 0, cspk, cl);                 // vout1 DD change, value 0
    }

    // --- DD inputs (value 0) ---
    for (size_t i = 0; i < ddIn; i++) {
        BRTransaction *dt = BRSetGet(wallet->allTx, &sel[i].u.hash);
        BRTransactionAddInput(tx, sel[i].u.hash, sel[i].u.n, 0,
                              dt->outputs[sel[i].u.n].script, dt->outputs[sel[i].u.n].scriptLen,
                              NULL, 0, NULL, 0, TXIN_SEQUENCE);
    }

    // --- build OP_RETURN bytes (added last) ---
    uint8_t orr[32]; size_t ol = 0;
    orr[ol++] = 0x6a; orr[ol++] = 0x02; orr[ol++] = 0x44; orr[ol++] = 0x44;  // OP_RETURN "DD"
    orr[ol++] = 0x01; orr[ol++] = 0x02;                                      // push txType 2
    uint8_t enc[9]; size_t el = BRDigiDollarWriteScriptNum((int64_t)cents, enc);
    orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el;
    if (ddChange > 0) { el = BRDigiDollarWriteScriptNum((int64_t)ddChange, enc);
                        orr[ol++] = (uint8_t)el; memcpy(orr + ol, enc, el); ol += el; }

    // --- DGB fee inputs; compute fee; DGB change ---
    uint64_t dgbIn = 0, fee = DD_MIN_FEE, dust = BRWalletMinOutputAmount(wallet);
    for (size_t i = 0; i < array_count(wallet->utxos); i++) {
        BRUTXO *o = &wallet->utxos[i];
        BRTransaction *ut = BRSetGet(wallet->allTx, o);
        if (! ut || o->n >= ut->outCount) continue;
        BRTransactionAddInput(tx, ut->txHash, o->n, ut->outputs[o->n].amount,
                              ut->outputs[o->n].script, ut->outputs[o->n].scriptLen, NULL, 0, NULL, 0, TXIN_SEQUENCE);
        dgbIn += ut->outputs[o->n].amount;
        size_t est = BRTransactionVSize(tx) + ol + TX_OUTPUT_SIZE;  // + OP_RETURN + possible DGB change
        fee = _txFee(wallet->feePerKb, est);
        if (fee < DD_MIN_FEE) fee = DD_MIN_FEE;
        if (dgbIn >= fee) break;
    }
    if (dgbIn < fee) { pthread_mutex_unlock(&wallet->lock); BRTransactionFree(tx); return NULL; }

    if (dgbIn - fee >= dust) {                                    // DGB change (P2WPKH), else remainder -> fee
        BRAddress dca = BR_ADDRESS_NONE;
        BRWalletUnusedAddrs(wallet, &dca, 1, 1, 1);              // internal P2WPKH change
        uint8_t dspk[42]; size_t dl = BRAddressScriptPubKey(dspk, sizeof(dspk), dca.s);
        BRTransactionAddOutput(tx, dgbIn - fee, dspk, dl);
    }
    BRTransactionAddOutput(tx, 0, orr, ol);                      // OP_RETURN LAST

    pthread_mutex_unlock(&wallet->lock);
    return tx;                                                    // NO shuffle (order is consensus-significant)
}
```

Declare in `BRWallet.h` (after `BRWalletForceCreateTxForOutputs`):
```c
// Build an unsigned DigiDollar transfer of `cents` to `recipientKey32` (decoded TD-address key);
// NULL on shortfall. Sign with BRWalletSignTransaction. Result freed by BRTransactionFree().
BRTransaction *BRWalletCreateDigiDollarTransfer(BRWallet *wallet, const uint8_t recipientKey32[32],
                                                uint64_t cents);
```

- [ ] **Step 5: Run to verify it passes.** `bash …/run.sh` → `ALL PASS`. (If the DGB-fee path returns NULL, the KAT wallet needs a funded DGB UTXO ≥ `DD_MIN_FEE` — credit a confirmed P2WPKH output to `BRWalletReceiveAddress(w,1)` in setup.)
- [ ] **Step 6: Regression.** app build + 42 security green.
- [ ] **Step 7: Commit.** Submodule (`BRDigiDollar.c/.h`, `BRWallet.c/.h`) then root (KAT + pin bump).

---

## Task 3: sign + verify KAT (built transfer signs through the proven path)

**Files:**
- Modify: `native/src/test/host/digidollar_send_kat/digidollar_send_kat_main.c`
- Modify: `.../run.sh` (add `secp256k1` include + the schnorr-verify headers, as `bip341_signtx_kat/run.sh` does)

**Interfaces:**
- Consumes: `BRWalletSignTransaction`, `BRTransactionIsSigned`, `_BRTransactionTaprootSighash` (via `#include "BRTransaction.c"`), `secp256k1_schnorrsig_verify`.

- [ ] **Step 1: Write the failing sign+verify test.** Extend the KAT: take the built transfer `t`, sign it, and verify the DD input witness. This mirrors `bip341_signtx_kat_main.c`'s validity check. (To reach `_BRTransactionTaprootSighash`, this KAT file must `#include "BRTransaction.c"` and the run.sh must therefore NOT also list BRTransaction.c — mirror `bip341_signtx_kat`.)

```c
    int r = BRWalletSignTransaction(w, t, 0, seed, sizeof(seed));
    ck(r == 1, "BRWalletSignTransaction signs the DD transfer");
    ck(BRTransactionIsSigned(t) == 1, "transfer reports fully signed");
    // the DD input (input spending our DD UTXO) must carry a 1-item 64-byte witness that verifies under X(Q)
    // find the DD input by its P2TR prevout script (51 20 ...); recompute its BIP341 sighash and verify.
    // (Use the same secp256k1_schnorrsig_verify + _BRTransactionTaprootSighash pattern as bip341_signtx_kat.)
```
(Reproduce the exact verify block from `bip341_signtx_kat_main.c`: parse X(Q) from the input's prevout script bytes `[2..34)`, recompute `_BRTransactionTaprootSighash(t, NULL, 0, ddInputIndex, SIGHASH_DEFAULT, &md)`, assert `secp256k1_schnorrsig_verify(ctx, sig64, md.u8, 32, &xoQ)==1`.)

- [ ] **Step 2: Run to verify it fails, then passes.** Before the sign call exists in the test it fails to compile; after adding it, `bash …/run.sh` → `ALL PASS` (the transfer signs and the DD-input Schnorr sig verifies — proving the built transfer is spendable).
- [ ] **Step 3: Regression.** app build + 42 security green.
- [ ] **Step 4: Commit.** Root (KAT change) — no submodule change this task.

---

## Task 4: testnet26 on-chain proof (USER-GATED funding)

**Not a code task — the end-to-end proof.** Uses the synced v9.26.3 node
(`/home/polloloco/digibyte-9.26.3/bin/digibyte-cli -datadir=/home/polloloco/.digibyte-tn -testnet`).

- [ ] **Step 1: Fund a throwaway wallet.** Derive a fresh throwaway mnemonic; get its taproot receive address (`getReceiveAddress(0,3)` path / the KAT harness) and a DGB receive address. Fund with testnet **DGB** (fee) and **DD** — the user sends from the johnnylaw machine, or mine testnet blocks + `mintdigidollar` on the node's `ddprobe` wallet then `senddigidollar` to our taproot address. Confirm both credit (node `getdigidollarbalance` for DD; `scantxoutset` for DGB).
- [ ] **Step 2: Build + sign via the real path.** In a host harness (like `taproot_sweep`): recover the wallet from the mnemonic, `BRWalletSetTaprootKey`, register the funding txs, `BRDigiDollarAddressDecode` a recipient `TD…` (from the node's `getdigidollaraddress`), `BRWalletCreateDigiDollarTransfer(wallet, recipientKey, cents)`, `BRWalletSignTransaction`, serialize → hex. Gate: assert `BRTransactionIsSigned` before emitting.
- [ ] **Step 3: Broadcast + confirm.** `digibyte-cli … testmempoolaccept '["<hex>"]'` → `allowed:true`; if it fails for low fee, raise `DD_MIN_FEE`/`feePerKb` and rebuild. Then `sendrawtransaction`, mine/wait 1 block, and confirm: the recipient DD credited (`getdigidollarbalance "TD…"`), our DD change returned, the transfer shows in `listdigidollartxs`. Record the txid + block.
- [ ] **Step 4: Record.** Update the ledger + a memory with the on-chain proof (txid, block, amounts).

---

## Out of scope (later increments)

- **JNI + SendScreen DD-send UI** — the next increment (wraps `BRDigiDollarAddressDecode` + `BRWalletCreateDigiDollarTransfer` + sign + `publishTransaction`).
- **Multi-recipient** (`sendmany`) — the OP_RETURN/positional format already supports it; a small later extension.
