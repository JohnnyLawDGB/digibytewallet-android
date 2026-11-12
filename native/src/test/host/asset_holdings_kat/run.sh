#!/usr/bin/env bash
# Host KAT runner for BRAssetHoldings.h: which DigiAsset rows count and what the wallet holds of each asset,
# the reconcile of a row's spent flag, the prunes of rows whose transaction the wallet no longer has, the
# write made for a detected output, the indexer's answer for one output, the prune gate, and a row's stored
# form. Moved from Kotlin core/.../asset/AssetManager.kt (isHeldForDisplay, computeHeldAssetBalancesImpl,
# decideAssetSpent, reconcileAssetRowsLocallyImpl, clearDeadAssetSend, pruneRemovedNativeAssetRowsImpl,
# pruneDeadBroadcastRowsImpl, persistDetectedAssetOutput, settledRow, settleFromIndexer,
# isOutgoingUnconfirmedRow), AssetSums.add, AssetMaintenanceGate.kt and network/DigiScopeAssetParsing.kt;
# their tests (HeldAssetBalancesTest, AssetHeldMeansNativeHoldsItTest, AssetConflictedSendTest,
# TxOutStackParsingTest, and AssetSpentReconcileTest, AssetMaintenanceGateTest, PruneRemovedNativeAssetRowsTest,
# AssetDeadBroadcastRowTest, AssetLocalReconcileTest, AssetProvenanceTaggingTest, AssetSourceFixTest and
# UnbackedAssetCreditTest's settle tests) run here value for value, beside a line-for-line C model of the
# Kotlin that the header is compared with over fixed pseudo-random inputs. See asset_holdings_kat_main.c.
#
#   comparison  one arm per rule, -DASSET_HOLDINGS_UNFIXED=<bit> (BRAssetHoldings.h): 1 a BACKEND row the
#               wallet has no record of is held; 2 a CONFLICTED outpoint is held; 4 a script the wallet does
#               not own is held; 8 a row counts whatever its credit; 16 a row flagged spent counts; 32 a
#               balance past the int64 range wraps; 64 a counted row of quantity zero adds to the row count;
#               128 an empty owned set answers an empty balance; 256 CONFLICTED sets the spent flag; 512 a
#               prune deletes on the first absent pass; 1024 a prune counts per txid; 2048 the dead-broadcast
#               prune takes confirmed rows; 4096 the native prune takes rows of any provenance; 8192 the
#               reconcile deletes a row with no script; 16384 an indexer count past the bound is believed;
#               32768 a reply naming another outpoint is read; 65536 a malformed entry is skipped; 131072
#               re-detection rewrites a settled row; 262144 our own unconfirmed send's change is written;
#               524288 the indexer is asked again at once; 1048576 a stored row with bytes after it is read;
#               2097152 the prune gate opens whatever the scan frontier; 4194304 re-detection lowers an
#               unsettled quantity. Each arm fails exactly the cases CASES lists for its bit and passes
#               every other.
#   shipped     -DASSET_HOLDINGS_UNFIXED=0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The header is the unit under test: nothing of the core is linked
# (the core headers it includes are header-only). The arms run with detect_leaks=0; then every case runs
# once more on the shipped arm under LeakSanitizer where the platform has one (said so, and skipped, where
# it does not -- Apple clang on macOS).
#
# Gates first: the header must test the seam's VALUE (#if, never #ifdef) with an #ifndef default. Then:
#   PORTABILITY GATE  the header includes only libc and the core headers whose types or functions it uses,
#                     reads no clock, does no I/O and allocates nothing, and builds clean under -Wall
#                     -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the header included twice in the
#                     second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_HOLDINGS_UNFIXED, a set of bits (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
HEADER="$CORE_DIR/BRAssetHoldings.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms (bits of ASSET_HOLDINGS_UNFIXED) that must fail it, comma-separated; - for none>
CASES=(
    balances_predicate_not_owned:4
    balances_predicate_owned_held:-
    balances_predicate_owned_spent:-
    balances_predicate_dead_send:-
    balances_predicate_no_record:1
    balances_predicate_probe_error:1
    balances_chang_shape:-
    balances_no_record_row:1
    balances_empty_owned:128
    balances_fully_phantom:-
    held_native_holds_it:-
    held_no_record_any_provenance:1
    held_probe_error:1
    held_spent_and_conflicted:2
    held_unowned_address:4
    held_ultra_wallet_nine:1
    conflicted_not_held:2
    conflicted_backend_not_rescued:2
    conflicted_leaves_spent_flag:256
    conflicted_stuck_attempt_excluded:2
    conflicted_other_states_unchanged:1
    stack_read_in_order:-
    stack_empty_is_answer:-
    stack_anything_else_no_answer:32768,65536
    stack_not_found_own_404:-
    spent_decide:256
    gate_rules:2097152
    outgoing_unconfirmed_rows:-
    prune_native_debounce:512
    prune_native_two_outpoints:512,1024
    prune_native_present_resets:512
    prune_native_malformed_txid:-
    prune_native_backend_untouched:4096
    prune_dead_never_confirmed:512
    prune_dead_confirmed_kept:512,2048
    prune_dead_present_kept:-
    prune_lookup_readings:512
    prune_shared_table:512,4096
    prune_stale_counts:512
    reconcile_prunes_unowned:-
    reconcile_unknown_owned:-
    reconcile_scriptless_kept:8192
    reconcile_spent_flags:-
    reconcile_undetected_left:-
    reconcile_prune_waits:-
    reconcile_rules:-
    clear_dead_send:-
    persist_new_native:-
    persist_existing_retagged:-
    persist_outgoing_unconfirmed:262144
    persist_confirmed_and_incoming:-
    persist_unknown_unchecked:8
    persist_quantity_rules:4194304
    settled_row_of:-
    settle_recheck_interval:524288
    settle_over_bound:8,16384
    settle_settled_final:131072
    settle_accept_rules:16384
    ask_decide_rules:524288
    balances_sums_saturate:32
    balances_zero_rows:64
    balances_unbacked:8
    balances_spent_flag:16
    balances_rows_seen:4
    wire_vectors:-
    wire_limits:-
    wire_refusals:1048576
    capacity:512,65536
    arguments:4
    zero_structs:-
    edges:4,512,32768
    second_unit:-
    model_sweep:1,2,4,8,16,32,64,128,256,512,1024,2048,4096,8192,16384,32768,65536,131072,262144,524288,1048576,2097152,4194304
)
ARMS=(1:backend-rescues 2:conflicted-held 4:ownership-ignored 8:unbacked-counts 16:spent-counts 32:sums-wrap
      64:empty-rows-counted 128:no-ownership-empty 256:conflicted-spent 512:no-debounce 1024:txid-debounce
      2048:dead-prunes-confirmed 4096:prune-any-source 8192:scriptless-pruned 16384:stack-unbounded
      32768:stack-any-output 65536:bad-entry-skipped 131072:settled-rewritten 262144:outgoing-persisted
      524288:asked-every-time 1048576:wire-trailing 2097152:gate-ignores-scan 4194304:quantity-lowered)

# ---- the seam is real ----------------------------------------------------------------------
if [ ! -f "$HEADER" ]; then
    echo "GATE FAILURE: $HEADER not found: a core pin from before the asset holdings moved into the core."; exit 1
fi
HDR="$(cat "$HEADER")"
for fn in BRAssetIdIsUnresolved BRAssetTxIsOutgoingUnconfirmed BRAssetRowSpentDecide BRAssetPruneGateOpen BRAssetHeldForDisplay \
          BRAssetRowCounts BRAssetHeldBalances BRAssetReconcileRows BRAssetClearDeadSend BRAssetPruneRemovedNativeRows \
          BRAssetPruneDeadBroadcastRows BRAssetSettledRowOf BRAssetPersistDetectedOutput BRAssetIndexerAskDecide \
          BRAssetStackAnswerRead BRAssetStackNotFoundRead BRAssetStackAnswerAccept BRAssetRowWireLength BRAssetRowEncode \
          BRAssetRowDecode; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR"; then
        echo "GATE FAILURE: $HEADER does not define $fn."; exit 1
    fi
done
if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?ASSET_HOLDINGS_UNFIXED" <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER does not test the value of ASSET_HOLDINGS_UNFIXED; the arms would not differ."; exit 1
fi
if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+ASSET_HOLDINGS_UNFIXED" <<<"$HDR" ||
   grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+ASSET_HOLDINGS_UNFIXED" <<<"$HDR"; then
    echo "GATE FAILURE: $HEADER selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
fi
for arm in "${ARMS[@]}"; do   # every arm's bit is one the header reads
    bit="${arm%%:*}"
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+ASSET_HOLDINGS_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$HDR"; then
        echo "GATE FAILURE: no ASSET_HOLDINGS_RED_* seam of value $bit; arm $bit would be the shipped code."; exit 1
    fi
done

FAIL=0

# ---- PORTABILITY GATE ----------------------------------------------------------------------
strip_comments() {   # a C file with // and /* */ comments removed
    sed -e 's@//.*$@@' "$1" | awk 'BEGIN{c=0} { line=$0; out="";
        while (length(line)) {
            if (c) { i = index(line, "*/"); if (!i) { line=""; break } line = substr(line, i+2); c=0 }
            else { i = index(line, "/*"); if (!i) { out = out line; line=""; break }
                   out = out substr(line, 1, i-1); line = substr(line, i+2); c=1 } }
        print out }'
}
IO_RE='(^|[^A-Za-z0-9_])(time|clock|clock_gettime|gettimeofday|fopen|fread|fwrite|printf|fprintf|open|read|write|socket|malloc|calloc|realloc|free)[[:space:]]*\('
WANT_INCLUDES="\"BRAssetAllocate.h\" \"BRSyncFrontier.h\" <stddef.h> <stdint.h> <string.h> "
CODE="$(strip_comments "$HEADER")"
includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | sort -u | tr '\n' ' ')"
if [ "$includes" != "$WANT_INCLUDES" ]; then
    echo "PORTABILITY GATE FAILED: BRAssetHoldings.h includes $includes(want $WANT_INCLUDES)"; FAIL=1
fi
if grep -Eq "$IO_RE" <<<"$CODE"; then
    echo "PORTABILITY GATE FAILED: BRAssetHoldings.h calls a clock, I/O or the allocator:"
    grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
fi
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetHoldings.h"
int strict_tu1(void);
int strict_tu1(void)
{
    static const uint8_t own[] = { 1, 2, 3 };
    BRAssetScript owned[1] = { { own, 3 } };
    BRAssetRow r;
    BRAssetHeldBalance b[1];
    BRAssetRowChange ch[1];
    int st = BR_ASSET_STATE_HELD;
    size_t n = 0, pruned = 9;

    memset(&r, 0, sizeof(r));
    r.txid = "aa";
    r.script = own;
    r.scriptLen = 3;
    r.isAsset = 1;
    r.assetId = "La1";
    r.assetQuantity = 4;
    r.spent = 1;
    r.credit = BR_ASSET_CREDIT_VERIFIED;
    return BRAssetHeldBalances(&r, 1, &st, owned, 1, b, 1, &n) == BR_ASSET_HOLDINGS_OK && n == 0 &&
           BRAssetReconcileRows(&r, 1, owned, 1, 1, &st, ch, &pruned) == BR_ASSET_HOLDINGS_OK && pruned == 0 &&
           ch[0] == BR_ASSET_ROW_MARK_UNSPENT;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetHoldings.h"
#include "BRAssetHoldings.h"
int strict_tu1(void);
int main(void)
{
    BRAssetRow r, d;
    BRAssetAbsence e[2];
    BRAssetAbsenceTable t;
    BRAssetRowChange ch[1];
    BRAssetTxLookup lk[1] = { BR_ASSET_TX_NOT_HELD };
    BRAssetStackAnswerEntry entry = { 1, "La1", 1, 5 };
    BRAssetStackAnswer a = { 0, "ab", 1, 0, 1, &entry, 1 };
    BRAssetUnits u[1];
    BRAssetStackLookup lookup;
    BRAssetDeliverable bound = { 0, 5 };
    BRAssetStackAcceptance acc;
    BRAssetDetectedOutput out;
    BRAssetPersist p;
    uint8_t buf[BR_ASSET_ROW_WIRE_FIXED + 128];
    size_t n = 0, removed = 9;
    int spent = 0, ok;

    memset(&r, 0, sizeof(r));
    memset(&out, 0, sizeof(out));
    r.txid = "0000000000000000000000000000000000000000000000000000000000000000";
    r.isAsset = 1;
    r.source = BR_ASSET_SOURCE_NATIVE;
    t.entries = e;
    t.count = 0;
    t.capacity = 2;
    out.txid = r.txid;
    out.placeholderAssetId = "unresolved:t";
    p = BRAssetPersistDetectedOutput(&out, NULL);
    acc = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, u,
                                   BRAssetStackAnswerRead(&a, "AB", 0, u, 1, &n, &lookup) == BR_ASSET_HOLDINGS_OK ? n : 0,
                                   bound, "unresolved:t");
    ok = BRAssetPruneRemovedNativeRows(&r, 1, lk, &t, ch, &removed) == BR_ASSET_HOLDINGS_OK && removed == 0 && t.count == 1 &&
         BRAssetPruneRemovedNativeRows(&r, 1, lk, &t, ch, &removed) == BR_ASSET_HOLDINGS_OK && removed == 1 &&
         ch[0] == BR_ASSET_ROW_DELETE && lookup == BR_ASSET_STACK_FOUND && acc.verdict == BR_ASSET_ACCEPT_SETTLED &&
         acc.row.quantity == 5 && acc.labelsHistory && p.action == BR_ASSET_PERSIST_INSERT && p.unresolved &&
         BRAssetRowSpentDecide(BR_ASSET_STATE_SPENT, &spent) && spent == 1 && BRAssetPruneGateOpen(1, 1, 1.0f, 1, 10, 10) &&
         BRAssetRowEncode(&p.row, buf, sizeof(buf), &n) == BR_ASSET_HOLDINGS_OK && BRAssetRowDecode(buf, n, &d) == BR_ASSET_HOLDINGS_OK &&
         strcmp(d.assetId, "unresolved:t") == 0 && BRAssetIndexerAskDecide(&d, 1, 0, BR_ASSET_RECEIPT_RECHECK_MS) == BR_ASSET_ASK_NOW &&
         BRAssetTxIsOutgoingUnconfirmed(1, BR_ASSET_TX_UNCONFIRMED) && BRAssetStackNotFoundRead(1, 1) == BR_ASSET_STACK_NOT_UNSPENT &&
         BR_ASSET_STATE_UNDETECTED == -1 && BR_ASSET_ABSENCE_PASSES == 2;
    return (ok && strict_tu1()) ? 0 : 1;
}
EOF
strict_ok=1
for std in c99 c11 c17; do
    if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
            "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/strict_$std" ||
       ! "$BUILD_DIR/strict_$std"; then
        echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
    fi
done
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRAssetHoldings.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
let own: [UInt8] = [1, 2, 3]
let result: (BRAssetHoldingsStatus, Int, Int64) = own.withUnsafeBufferPointer { s in
    var owned = [BRAssetScript(bytes: s.baseAddress, len: 3)]
    return "La1".withCString { id in
        "aa".withCString { txid in
            var r = BRAssetRow()
            r.txid = txid
            r.script = s.baseAddress
            r.scriptLen = 3
            r.isAsset = 1
            r.assetId = id
            r.assetQuantity = 4
            r.credit = BR_ASSET_CREDIT_VERIFIED
            r.source = BR_ASSET_SOURCE_NATIVE
            var states: [Int32] = [Int32(BR_ASSET_STATE_HELD)]
            var out = [BRAssetHeldBalance](repeating: BRAssetHeldBalance(), count: 2)
            var count = 0
            let st = BRAssetHeldBalances(&r, 1, &states, &owned, 1, &out, out.count, &count)
            return (st, count, out[0].quantity)
        }
    }
}
var entries = [BRAssetAbsence](repeating: BRAssetAbsence(), count: 4)
let tableCount: Int = entries.withUnsafeMutableBufferPointer { e in
    var table = BRAssetAbsenceTable(entries: e.baseAddress, count: 0, capacity: 4)
    var changes = [BRAssetRowChange](repeating: BR_ASSET_ROW_KEEP, count: 1)
    var lookups = [BR_ASSET_TX_NOT_HELD]
    var removed = 0
    var row = BRAssetRow()
    _ = BRAssetPruneDeadBroadcastRows(&row, 1, &lookups, &table, &changes, &removed)
    return table.count
}
var spent: Int32 = 0
let decided: Int32 = BRAssetRowSpentDecide(Int32(BR_ASSET_STATE_SPENT), &spent)
let gate: Int32 = BRAssetPruneGateOpen(1, 1, 1.0, 1, 10, 10)
let verdict: BRAssetAcceptVerdict = BRAssetStackAnswerAccept(BR_ASSET_STACK_FOUND, nil, 0, BRAssetDeliverable(unbounded: 1, max: 0), "p").verdict
let ask: BRAssetIndexerAsk = BRAssetIndexerAskDecide(nil, 0, 0, 0)
let lookup: BRAssetStackLookup = BRAssetStackNotFoundRead(1, 1)
var answer = BRAssetStackAnswer()
var units = [BRAssetUnits](repeating: BRAssetUnits(), count: 1)
var n = 0
var read = BR_ASSET_STACK_UNAVAILABLE
let readStatus: BRAssetHoldingsStatus = BRAssetStackAnswerRead(&answer, "ab", 0, &units, 1, &n, &read)
var wire = [UInt8](repeating: 0, count: Int(BR_ASSET_ROW_WIRE_MAX))
var decoded = BRAssetRow()
let decodeStatus: BRAssetHoldingsStatus = BRAssetRowDecode(&wire, wire.count, &decoded)
let absence = BRAssetAbsence()
let txidBytes = absence.txid
let persist: BRAssetPersistAction = BRAssetPersistDetectedOutput(nil, nil).action
let limits: (Int32, Int64, Int64, Int32, Int32, Int32, String) = (BR_ASSET_ABSENCE_PASSES, BR_ASSET_RECEIPT_RECHECK_MS,
    BR_ASSET_TX_UNCONFIRMED, BR_ASSET_ROW_WIRE_FIXED, BR_ASSET_STATE_PROBE_ERROR, BR_ASSET_STATE_CONFLICTED, BR_ASSET_UNRESOLVED_PREFIX)
print(result, tableCount, decided, spent, gate, verdict == BR_ASSET_ACCEPT_SETTLED, ask == BR_ASSET_ASK_NO, lookup, readStatus,
      decodeStatus, txidBytes.0, persist, limits, BRAssetIdIsUnresolved("unresolved:x"), BR_ASSET_HOLDINGS_CAPACITY,
      BR_ASSET_ROW_MARK_SPENT, BR_ASSET_SOURCE_BACKEND, BR_ASSET_TX_LOOKUP_FAILED, BR_ASSET_ABSENCE_DEAD, BR_CF_BEHIND_THRESHOLD)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    mkdir -p "$BUILD_DIR/swift_inc"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports it"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the header"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + the core headers it uses, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetHoldings.h"
#include "BRAssetHoldings.h"
int asset_holdings_kat_tu2(void);
int asset_holdings_kat_tu2(void)
{
    static const uint8_t own[] = { 1, 2, 3 };
    BRAssetScript owned[1] = { { own, 3 } };
    BRAssetRow r;
    BRAssetHeldBalance b[1];
    int st = BR_ASSET_STATE_HELD;
    size_t n = 0;

    memset(&r, 0, sizeof(r));
    r.txid = "aa";
    r.script = own;
    r.scriptLen = 3;
    r.isAsset = 1;
    r.assetId = "La1";
    r.assetQuantity = 4;
    r.credit = BR_ASSET_CREDIT_VERIFIED;
    return BRAssetHeldBalances(&r, 1, &st, owned, 1, b, 1, &n) == BR_ASSET_HOLDINGS_OK && n == 1 && b[0].quantity == 4 &&
           b[0].utxoCount == 1;
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
build() {   # <out> <ASSET_HOLDINGS_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_HOLDINGS_UNFIXED="$2" -I "$CORE_DIR" \
        "$SCRIPT_DIR/asset_holdings_kat_main.c" "$BUILD_DIR/tu2.c" -o "$1"
}

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"
    build "$BUILD_DIR/red_$bit" "$bit" || { echo "GATE FAILURE: comparison arm $bit did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    bit="${arm%%:*}"; name="${arm#*:}"
    echo "--- COMPARISON ARM -DASSET_HOLDINGS_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$bit,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$bit" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$bit $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [$bit $c] failed, as it must: ${first:6:150}"
            else
                echo "  [$bit $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$bit $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$bit] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (=0): every case passes, no sanitizer report ---"
for entry in "${CASES[@]}"; do
    c="${entry%%:*}"
    out="$("$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
    if [ $rc -eq 0 ] && ! grep -Eq "$SAN_RE" <<<"$out" && grep -q "^RESULT $c pass$" <<<"$out"; then
        echo "  [green $c] passed ($(grep -c '^PASS:' <<<"$out") checks)"
    else
        echo "  [green $c] GATE FAILURE (rc=$rc)"; grep -E "^FAIL:|$SAN_RE" <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
    fi
done

# ---- LEAK CHECK ----------------------------------------------------------------------------
leak_note="skipped: this platform's AddressSanitizer has no LeakSanitizer"
leak_probe="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" list 2>&1)"
if ! grep -q "detect_leaks is not supported" <<<"$leak_probe"; then
    leak_note="every case clean"
    echo "--- LEAK CHECK: every case on the shipped arm under detect_leaks=1 ---"
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"
        out="$(ASAN_OPTIONS="$ASAN_LEAK" "$BUILD_DIR/green" "$c" 2>&1)"; rc=$?
        if [ $rc -ne 0 ] || grep -Eq "$SAN_RE" <<<"$out" || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [leak $c] GATE FAILURE (rc=$rc)"; grep -E "$SAN_RE|^FAIL:" <<<"$out" | head -6 | sed 's/^/      /'; FAIL=1
            leak_note="FAILED"
        fi
    done
fi
echo "leak check: $leak_note"

[ $FAIL -eq 0 ] && echo "PASS: asset_holdings_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_holdings_kat"
exit $FAIL
