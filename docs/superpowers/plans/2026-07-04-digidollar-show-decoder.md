# DigiDollar SHOW Decoder (C core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A self-contained, pure C decoder (`BRDigiDollar.c`) that classifies a DigiDollar transaction and extracts its per-output cent amounts, byte-for-byte per the pinned wire format — unit-proven now with host KATs, zero dependency on testnet26.

**Architecture:** Mirror the existing `BRDigiAsset.c` OP_RETURN-scan idiom. Three public functions — classify (`BRDigiDollarTxType`), decode the OP_RETURN amount list (`BRDigiDollarDecodeAmounts`), and bind an amount to a specific output by DD-output ordinal (`BRDigiDollarOutputAmount`). No wallet/balance/JNI/UI wiring in this plan (that is the next increment, and it needs testnet26 to show a nonzero balance). This decoder is read-only and network-agnostic.

**Tech Stack:** C (C11, breadwallet fork conventions), host KAT harness compiled with `clang` exactly like the taproot KATs under `native/src/test/host/`.

**Source of truth:** `docs/superpowers/specs/2026-07-04-digidollar-wire-format.md` (spec) — every task cites the section it implements. When code and spec disagree, the spec's cited DigiByte-Core `file:line` wins.

## Global Constraints

Copied verbatim from the spec; every task's requirements implicitly include these:

- **DD tx marker:** a tx is DigiDollar iff `(tx->version & 0xFFFF) == 0x0770`. Type `= (tx->version >> 24) & 0xFF`, valid ∈ {1=MINT, 2=TRANSFER, 3=REDEEM}. (spec §1)
- **`tx->version` is `uint32_t`** (`BRTransaction.h:96`). TRANSFER version = `0x02000770` = 33556336.
- **Transfer OP_RETURN layout:** `6a 02 4444 <txType CScriptNum> <amount1 CScriptNum> … <amountN CScriptNum>` — the first push is the literal 2 bytes `44 44` ("DD"); **there is NO `output_count` field.** (spec §2.2, §2.3)
- **Amounts are minimal-encoded signed little-endian `CScriptNum`, ≤ 8 bytes.** The consensus reader `CScriptNum(data, true, 8)` **rejects non-minimal encodings** — our decoder MUST treat a non-minimal or > 8-byte push as "not a valid DD tx" (fail closed), never guess. (spec §2.1, §8 Q8)
- **Type-aware amount collection:** TRANSFER(2) → **all** remaining nonempty pushes; MINT(1)/REDEEM(3) → **first** nonempty push only. Empty pushes are skipped, consume no slot. (spec §3.2 Phase A)
- **Positional binding (the SHOW crux):** counter `k=0`; walk `tx->outputs` ascending; **skip** an output if `script[0]==0x6a` (OP_RETURN); **skip** if `amount != 0`; otherwise if `scriptLen==34 && script[0]==0x51` (OP_1) it is DD output ordinal `k` with cents `amounts[k]`, then `k++`. The counter advances on **every** qualifying zero-value 34-byte OP_1 output regardless of ownership. (spec §3.2 Phase B — reuse the exact rule)
- **A DD output is a bog-standard zero-value BIP-86 P2TR** `{OP_1,0x20,<32-byte x-only key>}`; there is NO DD marker in the output script. Classification REQUIRES the parent-tx marker + OP_RETURN — a lone `51 20 <32>` is never classifiable as DD by script alone. (spec §3.1, §3.4)
- **Never sum all pushes as a balance.** Balance = sum of amounts at *matched* outputs only. This decoder returns per-output amounts; it does NOT sum. (spec §3.4)
- **Fail closed on any malformation:** the safe answer is "not a DD tx / not a DD output." Never over-report.
- **No regression:** `native/CMakeLists.txt` must still build all ABIs; the 42 security tests stay green. Add `BRDigiDollar.c`/`.h` to the source list right after `BRDigiAsset.c`/`.h`.
- **Submodule commits** (the `.c`/`.h` live in the `digibytewallet-core` submodule): use `git -C native/src/main/jni/digibytewallet-core commit -F -` (NOT `eval` — breaks on parens). The `native/CMakeLists.txt` edit and the host KAT live in the ROOT repo.

---

## File Structure

- **Create (submodule):** `native/src/main/jni/digibytewallet-core/BRDigiDollar.h` — public API.
- **Create (submodule):** `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` — the decoder.
- **Modify (root):** `native/CMakeLists.txt` — add the two new files to the explicit source list after `BRDigiAsset.*` (~line 33-34).
- **Create (root):** `native/src/test/host/digidollar_decode_kat/digidollar_decode_kat_main.c` + `run.sh` — host KAT, compiled like `native/src/test/host/bech32m_kat/run.sh` (it needs only `BRTransaction.c` + `BRAddress.c` + `BRDigiDollar.c` + their transitive deps).

### Public API (BRDigiDollar.h)

```c
#ifndef BRDigiDollar_h
#define BRDigiDollar_h

#include "BRTransaction.h"
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DD_VERSION_MARKER 0x0770u   // (tx->version & 0xFFFF) for any DigiDollar tx
#define DD_TYPE_MINT      1
#define DD_TYPE_TRANSFER  2
#define DD_TYPE_REDEEM    3

// Returns the DigiDollar tx type (1=MINT, 2=TRANSFER, 3=REDEEM), or 0 if `tx` is
// not a DigiDollar transaction (marker absent or type out of {1,2,3}).
int BRDigiDollarTxType(const BRTransaction *tx);

// Decodes the DD OP_RETURN cent-amount list into `amounts` (up to `maxAmounts`
// entries). Type-aware: TRANSFER -> all remaining pushes, MINT/REDEEM -> first push
// only. Returns the number of amounts written (>= 0), or -1 if `tx` is not a DD tx
// or the OP_RETURN is malformed / non-minimally-encoded / overflows `maxAmounts`.
int BRDigiDollarDecodeAmounts(const BRTransaction *tx, int64_t *amounts, size_t maxAmounts);

// Returns the DD cent amount bound to output `voutIndex` by DD-output ordinal
// (spec §3.2 Phase B), or -1 if that output is not a DD token output (OP_RETURN,
// nonzero value, or not a 34-byte OP_1 P2TR) or if no amount slot binds to it.
int64_t BRDigiDollarOutputAmount(const BRTransaction *tx, size_t voutIndex);

#ifdef __cplusplus
}
#endif

#endif // BRDigiDollar_h
```

---

## Task 1: Module skeleton + `BRDigiDollarTxType` + build wiring + KAT harness

**Files:**
- Create: `native/src/main/jni/digibytewallet-core/BRDigiDollar.h` (full API above)
- Create: `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` (with `BRDigiDollarTxType` implemented; the other two functions stubbed to return -1 for now)
- Modify: `native/CMakeLists.txt` (add both files after `BRDigiAsset.*`)
- Create: `native/src/test/host/digidollar_decode_kat/digidollar_decode_kat_main.c` + `run.sh`

**Interfaces:**
- Consumes: `BRTransaction` (`tx->version` uint32, `tx->outputs`, `tx->outCount`), `BRTxOutput` (`amount` uint64, `script`, `scriptLen`).
- Produces: `BRDigiDollarTxType(tx)` → int, and the KAT harness (extended in Tasks 2-3).

- [ ] **Step 1: Write the failing test** — create the KAT harness `digidollar_decode_kat_main.c` with a `check(cond,desc)` helper (copy the pattern from `native/src/test/host/bech32m_kat/`), and a first test that builds two `BRTransaction`s via `BRTransactionNew()` + set `tx->version`, and asserts classification:

```c
// TRANSFER: version 0x02000770 -> type 2
BRTransaction *t2 = BRTransactionNew();
t2->version = 0x02000770;
check(BRDigiDollarTxType(t2) == 2, "version 0x02000770 -> TRANSFER(2)");

// MINT 0x01000770 -> 1 ; REDEEM 0x03000770 -> 3
BRTransaction *t1 = BRTransactionNew(); t1->version = 0x01000770;
check(BRDigiDollarTxType(t1) == 1, "version 0x01000770 -> MINT(1)");
BRTransaction *t3 = BRTransactionNew(); t3->version = 0x03000770;
check(BRDigiDollarTxType(t3) == 3, "version 0x03000770 -> REDEEM(3)");

// Non-DD: standard v1/v2, and a marker-collision with an invalid type (0x04000770 -> type 4)
BRTransaction *n1 = BRTransactionNew(); n1->version = 1;
check(BRDigiDollarTxType(n1) == 0, "version 1 -> not DD");
BRTransaction *n2 = BRTransactionNew(); n2->version = 2;
check(BRDigiDollarTxType(n2) == 0, "version 2 -> not DD");
BRTransaction *n3 = BRTransactionNew(); n3->version = 0x04000770; // marker present, type 4 invalid
check(BRDigiDollarTxType(n3) == 0, "marker+invalid type 4 -> not DD");
// free all
```

- [ ] **Step 2: Run it to verify it fails** — `run.sh` must fail to compile (no `BRDigiDollar.h`). Write `run.sh` mirroring `native/src/test/host/bech32m_kat/run.sh` but compiling `$SCRIPT_DIR/digidollar_decode_kat_main.c`, `$CORE_DIR/BRDigiDollar.c`, `$CORE_DIR/BRTransaction.c`, `$CORE_DIR/BRAddress.c`, `$CORE_DIR/BRSet.c`, `$CORE_DIR/BRKey.c`, `$CORE_DIR/BRBase58.c`, `$CORE_DIR/BRBech32.c`, `$CORE_DIR/BRCrypto.c`, `$CORE_DIR/BRDigiAsset.c`, `$CORE_DIR/BRBIP32Sequence.c`, `$CORE_DIR/BRBIP39Mnemonic.c`, the `crypto/*.c` (groestl/skein/qubit/odocrypt) and `crypto/sha3/*.c`, `-I$CORE_DIR -I$CORE_DIR/secp256k1/include`, `clang -w -include stdint.h ... -lm`. Run: `bash native/src/test/host/digidollar_decode_kat/run.sh`. Expected: compile error `BRDigiDollar.h: No such file`.
- [ ] **Step 3: Write minimal implementation** — create `BRDigiDollar.h` (full API above) and `BRDigiDollar.c`:

```c
#include "BRDigiDollar.h"

int BRDigiDollarTxType(const BRTransaction *tx)
{
    if (! tx) return 0;
    if ((tx->version & 0xFFFFu) != DD_VERSION_MARKER) return 0;
    int type = (int)((tx->version >> 24) & 0xFFu);
    if (type == DD_TYPE_MINT || type == DD_TYPE_TRANSFER || type == DD_TYPE_REDEEM) return type;
    return 0;
}

// stubs (implemented in later tasks)
int BRDigiDollarDecodeAmounts(const BRTransaction *tx, int64_t *amounts, size_t maxAmounts) { return -1; }
int64_t BRDigiDollarOutputAmount(const BRTransaction *tx, size_t voutIndex) { return -1; }
```

- [ ] **Step 4: Wire the build** — in `native/CMakeLists.txt`, add after the `BRDigiAsset.c` / `BRDigiAsset.h` lines:

```cmake
        src/main/jni/digibytewallet-core/BRDigiDollar.c
        src/main/jni/digibytewallet-core/BRDigiDollar.h
```

- [ ] **Step 5: Run the KAT to verify it passes** — `bash native/src/test/host/digidollar_decode_kat/run.sh`. Expected: all classification checks PASS, exit 0.
- [ ] **Step 6: Verify no build/security regression** — `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` ends `BUILD SUCCESSFUL`; `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*" 2>&1 | tail -5` green (42 tests).
- [ ] **Step 7: Commit** — submodule (`git -C … commit -F -`) for `BRDigiDollar.c/.h`; root commit for `native/CMakeLists.txt`, the KAT, and the submodule pin bump.

---

## Task 2: `BRDigiDollarDecodeAmounts` — OP_RETURN "DD" push-walker + minimal CScriptNum

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` (implement the internal helpers + `BRDigiDollarDecodeAmounts`)
- Modify: `native/src/test/host/digidollar_decode_kat/digidollar_decode_kat_main.c` (add amount-decode tests)

**Interfaces:**
- Consumes: `BRDigiDollarTxType` (Task 1), `tx->outputs[i].script/scriptLen`.
- Produces: `BRDigiDollarDecodeAmounts(tx, amounts, maxAmounts)` → count or -1; internal static `_ddReadScriptNum`, `_ddNextPush`, `_ddFindOpReturn`.

- [ ] **Step 1: Write the failing tests** — build real transactions carrying the synthesized OP_RETURN vectors from spec §7 and assert the decoded amount lists. Helper to add a raw-script output: `BRTransactionAddOutput(tx, 0, scriptBytes, scriptLen)`.

```c
// $50 one-recipient transfer: OP_RETURN = 6a 02 44 44 01 02 02 88 13  -> [5000]
uint8_t or1[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
BRTransaction *a = BRTransactionNew(); a->version = 0x02000770;
BRTransactionAddOutput(a, 0, or1, sizeof(or1));
int64_t amt[8]; int n = BRDigiDollarDecodeAmounts(a, amt, 8);
check(n == 1 && amt[0] == 5000, "transfer OP_RETURN -> [5000]");

// $50/$25 two-recipient: ... 02 88 13 02 c4 09 -> [5000,2500]
uint8_t or2[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09};
BRTransaction *b = BRTransactionNew(); b->version = 0x02000770;
BRTransactionAddOutput(b, 0, or2, sizeof(or2));
n = BRDigiDollarDecodeAmounts(b, amt, 8);
check(n == 2 && amt[0] == 5000 && amt[1] == 2500, "transfer -> [5000,2500]");

// $50/$25 + $3 change: ... 02 2c 01 -> [5000,2500,300]
uint8_t or3[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09,0x02,0x2c,0x01};
BRTransaction *c = BRTransactionNew(); c->version = 0x02000770;
BRTransactionAddOutput(c, 0, or3, sizeof(or3));
n = BRDigiDollarDecodeAmounts(c, amt, 8);
check(n == 3 && amt[0]==5000 && amt[1]==2500 && amt[2]==300, "transfer -> [5000,2500,300]");

// MINT: first push only. mint OP_RETURN 6a 02 4444 01 01 02 88 13 (type 1, amount 5000) -> [5000], n==1
uint8_t orm[] = {0x6a,0x02,0x44,0x44,0x01,0x01,0x02,0x88,0x13};
BRTransaction *m = BRTransactionNew(); m->version = 0x01000770;
BRTransactionAddOutput(m, 0, orm, sizeof(orm));
n = BRDigiDollarDecodeAmounts(m, amt, 8);
check(n == 1 && amt[0] == 5000, "mint -> first push only [5000]");

// Negatives:
BRTransaction *nd = BRTransactionNew(); nd->version = 1;         // not DD
BRTransactionAddOutput(nd, 0, or1, sizeof(or1));
check(BRDigiDollarDecodeAmounts(nd, amt, 8) == -1, "non-DD tx -> -1");

// DD marker but no "DD" OP_RETURN present -> -1
BRTransaction *no = BRTransactionNew(); no->version = 0x02000770;
uint8_t p2wpkh[] = {0x00,0x14, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20};
BRTransactionAddOutput(no, 0, p2wpkh, sizeof(p2wpkh));
check(BRDigiDollarDecodeAmounts(no, amt, 8) == -1, "DD marker w/o DD OP_RETURN -> -1");

// Non-minimal amount push (0x8813 padded to 88 13 00 -> non-minimal) -> -1 (fail closed)
uint8_t orbad[] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x03,0x88,0x13,0x00};
BRTransaction *bad = BRTransactionNew(); bad->version = 0x02000770;
BRTransactionAddOutput(bad, 0, orbad, sizeof(orbad));
check(BRDigiDollarDecodeAmounts(bad, amt, 8) == -1, "non-minimal amount push -> -1");

// maxAmounts overflow: 3 amounts into a size-2 buffer -> -1
check(BRDigiDollarDecodeAmounts(c, amt, 2) == -1, "amount count > maxAmounts -> -1");
```

- [ ] **Step 2: Run to verify it fails** — `bash …/run.sh`. Expected: the new checks FAIL (stub returns -1 for the positive cases).
- [ ] **Step 3: Write the implementation** in `BRDigiDollar.c`:

```c
// Minimal-encoded signed little-endian CScriptNum decode (Satoshi rules), <= 8 bytes.
// Returns 1 and sets *out on success; returns 0 on non-minimal encoding or len > 8.
// Empty (len==0) decodes to 0 with success (caller decides whether to skip).
static int _ddReadScriptNum(const uint8_t *data, size_t len, int64_t *out)
{
    if (len > 8) return 0;
    if (len == 0) { *out = 0; return 1; }
    // minimal-encoding check: top byte can't be 0x00 unless it sets the sign bit of the next
    if ((data[len - 1] & 0x7f) == 0) {
        if (len == 1 || (data[len - 2] & 0x80) == 0) return 0; // non-minimal
    }
    int64_t result = 0;
    for (size_t i = 0; i < len; i++) result |= (int64_t)data[i] << (8 * i);
    if (data[len - 1] & 0x80) { // negative
        int64_t mask = (int64_t)1 << (8 * len - 1);
        result &= ~mask;
        result = -result;
    }
    *out = result;
    return 1;
}

// Advance a script-push cursor. On entry *pos indexes an opcode in script[0..scriptLen).
// On success sets *dataOff/*dataLen for the pushed bytes, advances *pos past the push,
// returns 1. Returns 0 at end-of-script or on a non-push / OP_PUSHDATA it can't read.
// Handles direct pushes 0x01..0x4b and OP_PUSHDATA1 (0x4c). An empty push (OP_0/0x00)
// yields dataLen 0. (DD metadata never uses larger pushdata; reject them = fail closed.)
static int _ddNextPush(const uint8_t *script, size_t scriptLen, size_t *pos,
                       size_t *dataOff, size_t *dataLen)
{
    if (*pos >= scriptLen) return 0;
    uint8_t op = script[*pos];
    if (op == 0x00) { *dataOff = *pos + 1; *dataLen = 0; *pos += 1; return 1; } // OP_0 / empty
    if (op >= 0x01 && op <= 0x4b) {
        size_t l = op;
        if (*pos + 1 + l > scriptLen) return 0;
        *dataOff = *pos + 1; *dataLen = l; *pos += 1 + l; return 1;
    }
    if (op == 0x4c) { // OP_PUSHDATA1
        if (*pos + 2 > scriptLen) return 0;
        size_t l = script[*pos + 1];
        if (*pos + 2 + l > scriptLen) return 0;
        *dataOff = *pos + 2; *dataLen = l; *pos += 2 + l; return 1;
    }
    return 0; // any other opcode (incl OP_N numeric) is not a DD metadata push
}

// Find the first output that is an OP_RETURN whose FIRST push is the 2 bytes "DD" (44 44).
// Returns the output index, or -1.
static long _ddFindDDOpReturn(const BRTransaction *tx)
{
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];
        if (o->scriptLen < 4 || ! o->script || o->script[0] != OP_RETURN) continue;
        size_t pos = 1, off = 0, len = 0;
        if (! _ddNextPush(o->script, o->scriptLen, &pos, &off, &len)) continue;
        if (len == 2 && o->script[off] == 0x44 && o->script[off + 1] == 0x44) return (long)i;
    }
    return -1;
}

int BRDigiDollarDecodeAmounts(const BRTransaction *tx, int64_t *amounts, size_t maxAmounts)
{
    int type = BRDigiDollarTxType(tx);
    if (type == 0) return -1;
    long ri = _ddFindDDOpReturn(tx);
    if (ri < 0) return -1;
    const BRTxOutput *o = &tx->outputs[ri];

    size_t pos = 1, off = 0, len = 0;
    // push 0: "DD" (already validated by _ddFindDDOpReturn)
    if (! _ddNextPush(o->script, o->scriptLen, &pos, &off, &len)) return -1;
    // push 1: txType
    if (! _ddNextPush(o->script, o->scriptLen, &pos, &off, &len)) return -1;
    int64_t tt;
    if (! _ddReadScriptNum(o->script + off, len, &tt) || (int)tt != type) return -1;

    int count = 0;
    while (_ddNextPush(o->script, o->scriptLen, &pos, &off, &len)) {
        if (len == 0) continue;               // empty push consumes no slot (spec §3.2)
        int64_t v;
        if (! _ddReadScriptNum(o->script + off, len, &v)) return -1; // non-minimal -> fail closed
        if (v <= 0) return -1;                 // amounts must be positive
        if ((size_t)count >= maxAmounts) return -1;
        amounts[count++] = v;
        if (type != DD_TYPE_TRANSFER) break;   // MINT/REDEEM: first push only
    }
    if (count == 0) return -1;
    return count;
}
```

- [ ] **Step 4: Run to verify it passes** — `bash …/run.sh`. Expected: all amount-decode checks PASS.
- [ ] **Step 5: Verify no regression** — native build + 42 security tests green (as Task 1 Step 6).
- [ ] **Step 6: Commit** — submodule `BRDigiDollar.c`; root KAT + pin bump.

---

## Task 3: `BRDigiDollarOutputAmount` — positional amount↔output binding

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRDigiDollar.c` (implement `BRDigiDollarOutputAmount`)
- Modify: `native/src/test/host/digidollar_decode_kat/digidollar_decode_kat_main.c` (add binding tests)

**Interfaces:**
- Consumes: `BRDigiDollarDecodeAmounts` (Task 2).
- Produces: `BRDigiDollarOutputAmount(tx, voutIndex)` → cents or -1.

- [ ] **Step 1: Write the failing tests** — build a realistic transfer: `vout[0]` = recipient DD output (zero-value `51 20 <32>`), `vout[1]` = second DD output (zero-value `51 20 <32>`), `vout[2]` = DGB fee change (nonzero P2WPKH), `vout[3]` = OP_RETURN `[5000,2500]`. Assert the ordinal binding + all the skips.

```c
uint8_t p2tr_a[34]; p2tr_a[0]=0x51; p2tr_a[1]=0x20; memset(p2tr_a+2,0xAA,32);
uint8_t p2tr_b[34]; p2tr_b[0]=0x51; p2tr_b[1]=0x20; memset(p2tr_b+2,0xBB,32);
uint8_t p2wpkh[22]={0x00,0x14,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20};
uint8_t orr[]={0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13,0x02,0xc4,0x09}; // [5000,2500]

BRTransaction *t = BRTransactionNew(); t->version = 0x02000770;
BRTransactionAddOutput(t, 0, p2tr_a, 34);        // vout 0 -> DD ordinal 0 -> 5000
BRTransactionAddOutput(t, 0, p2tr_b, 34);        // vout 1 -> DD ordinal 1 -> 2500
BRTransactionAddOutput(t, 123456, p2wpkh, 22);   // vout 2 -> nonzero -> not DD (-1)
BRTransactionAddOutput(t, 0, orr, sizeof(orr));  // vout 3 -> OP_RETURN -> not DD (-1)

check(BRDigiDollarOutputAmount(t, 0) == 5000, "vout0 DD ordinal 0 -> 5000");
check(BRDigiDollarOutputAmount(t, 1) == 2500, "vout1 DD ordinal 1 -> 2500");
check(BRDigiDollarOutputAmount(t, 2) == -1,   "vout2 nonzero-value -> not DD");
check(BRDigiDollarOutputAmount(t, 3) == -1,   "vout3 OP_RETURN -> not DD");
check(BRDigiDollarOutputAmount(t, 9) == -1,   "out-of-range vout -> -1");

// Skip rule: a nonzero-value P2TR (looks like 51 20 but amount!=0) must NOT consume a slot.
// vout0 nonzero 51-20 (mint-collateral shape), vout1 zero 51-20 -> ordinal 0 -> 5000
BRTransaction *s = BRTransactionNew(); s->version = 0x02000770;
uint8_t ors[]={0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13}; // [5000]
BRTransactionAddOutput(s, 999, p2tr_a, 34);      // vout0 nonzero 51-20 -> skipped, no slot
BRTransactionAddOutput(s, 0,   p2tr_b, 34);      // vout1 zero 51-20 -> DD ordinal 0 -> 5000
BRTransactionAddOutput(s, 0,   ors, sizeof(ors));
check(BRDigiDollarOutputAmount(s, 0) == -1,   "nonzero 51-20 is not DD");
check(BRDigiDollarOutputAmount(s, 1) == 5000, "first ZERO 51-20 is DD ordinal 0 -> 5000");

// A DD output whose ordinal exceeds the amount list -> -1 (never over-credit).
BRTransaction *x = BRTransactionNew(); x->version = 0x02000770;
BRTransactionAddOutput(x, 0, p2tr_a, 34);        // ordinal 0 -> 5000
BRTransactionAddOutput(x, 0, p2tr_b, 34);        // ordinal 1 -> no amount[1] (list is [5000])
BRTransactionAddOutput(x, 0, ors, sizeof(ors));  // [5000]
check(BRDigiDollarOutputAmount(x, 0) == 5000, "ordinal 0 -> 5000");
check(BRDigiDollarOutputAmount(x, 1) == -1,   "ordinal 1 with no amount slot -> -1");
```

- [ ] **Step 2: Run to verify it fails** — stub returns -1 for all; the positive checks FAIL.
- [ ] **Step 3: Write the implementation:**

```c
int64_t BRDigiDollarOutputAmount(const BRTransaction *tx, size_t voutIndex)
{
    if (! tx || voutIndex >= tx->outCount) return -1;
    int64_t amounts[64];
    int n = BRDigiDollarDecodeAmounts(tx, amounts, 64);
    if (n < 0) return -1;

    size_t k = 0;
    for (size_t i = 0; i < tx->outCount; i++) {
        const BRTxOutput *o = &tx->outputs[i];
        if (o->scriptLen >= 1 && o->script && o->script[0] == OP_RETURN) continue; // skip metadata
        if (o->amount != 0) continue;                                              // skip DGB/collateral
        if (o->scriptLen == 34 && o->script && o->script[0] == 0x51) {             // a DD (zero-value P2TR) output
            if (i == voutIndex) {
                if (k < (size_t)n) return amounts[k];
                return -1;                                                         // ordinal past amount list
            }
            k++;                                                                   // advance for every DD output
        } else if (i == voutIndex) {
            return -1;                                                             // target isn't a DD output
        }
    }
    return -1;
}
```

- [ ] **Step 4: Run to verify it passes** — all binding checks PASS; the harness prints `ALL PASS`.
- [ ] **Step 5: Verify no regression** — native build + 42 security tests green.
- [ ] **Step 6: Commit** — submodule `BRDigiDollar.c`; root KAT + pin bump.

---

## Out of scope (explicit — later increments)

- **Wallet wiring:** DD balance accounting, a separate DD-UTXO set, ownership match against the wallet's BIP-86 taproot output keys, and the hook in tx registration. (Next plan.)
- **JNI + Kotlin + UI:** surfacing a DD balance for display. (Next plan; will show 0 on mainnet until a testnet26 build syncs real DD txs.)
- **DD address decode** (`TD…` Base58Check): needed for SEND recipient parsing and receive-address display — **gated on confirming the encoding against a real testnet26 DD address** (spec §8 Q2, fund-safety).
- **SEND (transfer builder):** the whole of spec §9.2 — gated on a real testnet26 DD transfer capture.
- **On-chain SHOW proof:** requires a 9.26 node on testnet26 (local node is 8.26.2, no DD consensus).
