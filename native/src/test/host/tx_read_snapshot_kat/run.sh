#!/usr/bin/env bash
# Host KAT runner: a reader of the wallet's transactions keeps no BRTransaction pointer past the
# wallet lock (tx_read_snapshot_kat_main.c states the rule and the arms).
#
# ==== RED-BEFORE-GREEN GATE (64-bit and 32-bit) =============================================
#   * COMPARISON ARM (-DTX_READ_SNAPSHOT_UNFIXED): the readers use the pointers BRWalletTransactionForHash
#     and BRWalletTransactions hand out after the lock is released. The fixed-schedule arms lookup, list
#     and hashes MUST each be stopped by AddressSanitizer with a heap-use-after-free. The race arm (no
#     fixed schedule) is reported, not required: whether its window is hit is up to the scheduler.
#   * SHIPPED ARM: the readers take copies under the lock (BRWalletCopyTransactionForHash,
#     BRWalletCopyTransactions, BRWalletTransactionHashes). Every arm, contract included, MUST print ok,
#     exit 0 and carry no sanitizer report.
# Every run is bounded (timeout): a sanitizer report from a second thread can stall rather than abort.
#
# ==== SOURCE GATE ==========================================================================
# The arms run the core accessors; the JNI files include Android headers and do not build on the host.
# So the bridge itself is scanned (comments dropped): in native/src/main/jni/bridge/*.c
#   - BRWalletTransactions may be called only as a count query: (wallet, NULL, 0);
#   - BRWalletTxUnconfirmedBefore likewise; BRGetTxForUTXO not at all;
#   - BRWalletTransactionForHash may be used only as a presence answer -- the whole condition of an
#     `if`, possibly negated, or compared with `!=` / `==`, or the test of a `?:` -- never kept in a
#     variable, passed on, or read through.
# The gate is checked against a fixture of the earlier shapes first, so it cannot pass vacuously.
# TX_READ_GATE_DIR=<dir> points the source gate at another bridge directory (used to show it refuses
# the earlier bridge).
set -uo pipefail   # deliberately NOT -e: the comparison arm's non-zero exits are expected

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BRIDGE_DIR="${TX_READ_GATE_DIR:-$REPO_ROOT/native/src/main/jni/bridge}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
SAN_RE="ERROR: AddressSanitizer|runtime error:|ERROR: LeakSanitizer"
LIMIT="${TX_READ_LIMIT:-120}"
FAIL=0

build() { # $1 = output, $2 = bits, rest = extra flags
    local out="$1" bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    clang -w -include stdint.h -g -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/tx_read_snapshot_kat_main.c" \
        "$CORE_DIR/BRWallet.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRMerkleBlock.c" \
        "$CORE_DIR/BRWalletFilterElements.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" \
        "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" \
        "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lm -lpthread -o "$out"
}

bounded() { timeout -k 5 "$LIMIT" "$@"; }

run_bits() {
    local bits="$1" arm out rc
    echo "=== ${bits}-bit ==="
    if ! build "$BUILD_DIR/red$bits" "$bits" -DTX_READ_SNAPSHOT_UNFIXED; then
        echo "  GATE FAILURE: ${bits}-bit comparison arm did not compile (a build error is not evidence)"; FAIL=1; return
    fi

    echo "--- comparison arm (-DTX_READ_SNAPSHOT_UNFIXED): lookup, list, hashes MUST be stopped by ASan ---"
    for arm in lookup list hashes; do
        out="$(bounded "$BUILD_DIR/red$bits" "$arm" 2>&1)"; rc=$?
        if [ $rc -ne 0 ] && <<<"$out" grep -q "heap-use-after-free"; then
            echo "  [$bits red $arm] stopped, as it must: $(<<<"$out" grep -m1 -E 'READ of size|WRITE of size' )"
        else
            echo "  [$bits red $arm] GATE FAILURE: no heap-use-after-free (rc=$rc)"
            <<<"$out" tail -3 | sed 's/^/      /'; FAIL=1
        fi
    done
    out="$(bounded "$BUILD_DIR/red$bits" race 2>&1)"; rc=$?
    if <<<"$out" grep -q "heap-use-after-free"; then
        echo "  [$bits red race] stopped by ASan (reported, not required)"
    else
        echo "  [$bits red race] window not hit this run (rc=$rc; reported, not required)"
    fi

    echo "--- shipped arm: every arm MUST print ok, exit 0, no sanitizer report ---"
    if ! build "$BUILD_DIR/green$bits" "$bits" 2> "$BUILD_DIR/green$bits.err"; then
        echo "  GATE FAILURE: ${bits}-bit shipped arm did not compile (BRWalletCopyTransactionForHash / BRWalletCopyTransactions / BRWalletTransactionHashes missing?)"
        grep -m3 "error:" "$BUILD_DIR/green$bits.err" | sed 's/^/      /'
        FAIL=1; return
    fi
    for arm in lookup list hashes race contract; do
        out="$(bounded "$BUILD_DIR/green$bits" "$arm" 2>&1)"; rc=$?
        if [ $rc -eq 0 ] && <<<"$out" grep -q "^tx_read_snapshot_kat $arm: ok$" && ! <<<"$out" grep -Eq "$SAN_RE"; then
            echo "  [$bits green $arm] ok"
        else
            echo "  [$bits green $arm] GATE FAILURE (rc=$rc)"
            <<<"$out" grep -E "FAIL:|$SAN_RE" | head -5 | sed 's/^/      /'; FAIL=1
        fi
    done
}

# ---- source gate ----------------------------------------------------------------------------
# Prints one line per use that keeps or reads a wallet-owned BRTransaction pointer.
strip_comments() { # drop /* */ and // comments, keep line numbers
    awk 'BEGIN { c = 0 }
    { s = $0; o = ""
      while (length(s) > 0) {
        if (c) { i = index(s, "*/"); if (i == 0) { s = "" } else { s = substr(s, i + 2); c = 0 } }
        else {
          i = index(s, "/*"); j = index(s, "//")
          if (j > 0 && (i == 0 || j < i)) { o = o substr(s, 1, j - 1); s = "" }
          else if (i > 0) { o = o substr(s, 1, i - 1); s = substr(s, i + 2); c = 1 }
          else { o = o s; s = "" }
        }
      }
      print o }' "$1"
}
# A call's argument list here never nests more than one level: ( x, f(y) ) or ( x, y->z ).
ARGS='\([^()]*(\([^()]*\)[^()]*)*\)'
gate_scan() { # files...
    local f
    for f in "$@"; do
        strip_comments "$f" | GATE_ARGS="$ARGS" awk -v F="$(basename "$f")" '
        BEGIN { A = ENVIRON["GATE_ARGS"] }
        {
          line = $0
          if (line ~ /BRGetTxForUTXO[ \t]*\(/) print F ":" NR ": BRGetTxForUTXO hands out a wallet pointer"
          t = line
          gsub("BRWalletTransactions[ \t]*\\([^,()]*,[ \t]*NULL[ \t]*,[ \t]*0[ \t]*\\)", "", t)
          if (t ~ /BRWalletTransactions[ \t]*\(/) print F ":" NR ": BRWalletTransactions fills an array of wallet pointers"
          t = line
          gsub("BRWalletTxUnconfirmedBefore[ \t]*\\([^,()]*,[ \t]*NULL[ \t]*,[ \t]*0[ \t]*,[^()]*\\)", "", t)
          if (t ~ /BRWalletTxUnconfirmedBefore[ \t]*\(/) print F ":" NR ": BRWalletTxUnconfirmedBefore fills an array of wallet pointers"
          t = line
          call = "BRWalletTransactionForHash[ \t]*" A
          gsub("if[ \t]*\\([ \t]*!?[ \t]*" call "[ \t]*\\)", "if()", t)              # if (FH(..)) / if (!FH(..))
          gsub(call "[ \t]*[!=]=", "", t)                                           # FH(..) != x / == x
          gsub("[!=]=[ \t]*" call, "", t)                                           # x != FH(..)
          gsub(call "[ \t]*\\?", "", t)                                             # FH(..) ? a : b
          if (t ~ /BRWalletTransactionForHash[ \t]*\(/) print F ":" NR ": BRWalletTransactionForHash result kept or read: " line
        }'
    done
}

echo "=== source gate self-check: the earlier shapes MUST be refused ==="
FIX="$BUILD_DIR/fixture.c"
cat > "$FIX" <<'C'
static void a(void) { BRTransaction *tx = BRWalletTransactionForHash(g_wallet, hash); use(tx); }
static void b(void) { n = BRWalletTransactions(g_wallet, txs, n); }
static void c(void) { if ((tx = BRWalletTransactionForHash(g_wallet, h)) != NULL) use(tx); }
static void d(void) { if (BRWalletTransactionForHash(g_wallet, h)->blockHeight == 0) use(h); }
static void e(void) { valid = BRWalletTransactionIsValid(g_wallet, BRWalletTransactionForHash(g_wallet, h)); }
static void f(void) { n = BRWalletTxUnconfirmedBefore(g_wallet, txs, n, height); }
static void g(void) { BRTransaction *t = BRGetTxForUTXO(g_wallet, u); }
static void ok1(void) { if (BRWalletTransactionForHash(g_wallet, tx->txHash)) return; }
static void ok2(void) { if (! BRWalletTransactionForHash(g_wallet, _u256FromTxidHex(txid))) return; }
static void ok3(void) { removed = BRWalletTransactionForHash(g_wallet, h) ? JNI_FALSE : JNI_TRUE; }
static void ok4(void) { if (tx && BRWalletTransactionForHash(wallet, tx->txHash) != tx) BRTransactionFree(tx); }
static void ok5(void) { size_t n = BRWalletTransactions(g_wallet, NULL, 0); /* BRWalletTransactions(w, txs, n) */ }
C
fixout="$(gate_scan "$FIX")"
want_lines=(":1:" ":2:" ":3:" ":4:" ":5:" ":6:" ":7:")
for w in "${want_lines[@]}"; do
    if <<<"$fixout" grep -q "$w"; then :; else echo "  GATE FAILURE: the gate let fixture line ${w//:/} through"; FAIL=1; fi
done
for w in ":8:" ":9:" ":10:" ":11:" ":12:"; do
    if <<<"$fixout" grep -q "$w"; then echo "  GATE FAILURE: the gate refused presence-only fixture line ${w//:/}"; FAIL=1; fi
done
[ "$FAIL" -eq 0 ] && echo "  refuses all 7 earlier shapes, accepts all 5 presence-only shapes"

echo "=== source gate: $BRIDGE_DIR/*.c ==="
shopt -s nullglob
BRIDGE_SRCS=("$BRIDGE_DIR"/*.c)
shopt -u nullglob
if [ ${#BRIDGE_SRCS[@]} -eq 0 ]; then echo "  GATE FAILURE: no bridge sources in $BRIDGE_DIR"; FAIL=1
else
    gout="$(gate_scan "${BRIDGE_SRCS[@]}")"
    if [ -n "$gout" ]; then
        echo "  GATE FAILURE: $(<<<"$gout" grep -c .) use(s) keep a wallet pointer past the lock:"
        <<<"$gout" sed 's/^/      /'; FAIL=1
    else
        echo "  ${#BRIDGE_SRCS[@]} files: no wallet BRTransaction pointer is kept past the lock"
    fi
fi
[ "${TX_READ_GATE_ONLY:-0}" = "1" ] && { [ "$FAIL" -eq 0 ] && exit 0 || exit 1; }

run_bits 64
run_bits 32

echo
if [ "$FAIL" -eq 0 ]; then
    echo "PASS: tx_read_snapshot_kat (comparison arm stopped on lookup/list/hashes, shipped arm clean, bridge gate clean; 64-bit and 32-bit)"
    exit 0
fi
echo "FAIL: tx_read_snapshot_kat"
exit 1
