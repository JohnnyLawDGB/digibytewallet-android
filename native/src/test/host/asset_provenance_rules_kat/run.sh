#!/usr/bin/env bash
# Host KAT runner for BRAssetProvenance.h and BRAssetRuleGate.h: how a wallet names the asset a DigiAsset
# transfer carries (the provenance walk as a step-by-step specification the platform executes, its hop
# classifier, and the search for a named asset's facts), and whether an asset may move by a plain
# transfer. Moved from Kotlin core/.../asset/AssetProvenanceWalker.kt, AssetManager.kt's
# classifyProvenanceHopFrom, factsForNamedAsset and verifyTransferRules, and asset/rules/TransferRuleState.kt;
# their tests (AssetProvenanceWalkerTest, AssetProvenanceTxidBindingTest, AssetTransferRuleGateTest,
# AssetSendRuleGateTest) run here value for value, beside a line-for-line C model of the Kotlin that the
# headers are compared with over fixed pseudo-random inputs. See asset_provenance_rules_kat_main.c.
#
#   comparison  one arm per defect. p<bit>: -DASSET_PROVENANCE_UNFIXED=<bit> (BRAssetProvenance.h): 1 the
#               frontier is not read; 2 a transaction that could not be fetched clears the frontier; 4 a
#               dead end puts a frontier; 8 a txid seen before in the attempt is walked again; 16 only the
#               start txid's facts are read from the store; 32 a forced walk answers from the store; 64 only
#               the start txid is memoised; 128 a frontier counts this attempt's hops only; 256 an unlocked
#               issuance is given the locked id; 512 no bytes is a dead end; 1024 a burn is walked through;
#               2048 a walk's answer for another asset is taken; 4096 the other inputs' transactions are not
#               walked; 8192 a repeated parent, and the start txid, are walked again; 16384 a row without the
#               issuance opcode answers for the issuance; 32768 the store's issuance facts are taken without
#               the id check. r<bit>: -DASSET_RULE_GATE_UNFIXED=<bit> (BRAssetRuleGate.h): 1 a proxy answer
#               without rules stands in for the lock; 2 a rule-free opcode is NONE whether or not it is
#               locked; 4 the proxy's rules object is not read; 8 only opcode 3 carries rules; 16 a locked
#               issuance of any opcode but 3 and 4 is NONE; 32 the verdict for the facts' asset is taken for
#               the one asked; 64 UNKNOWN allows a send. Each arm fails exactly the cases CASES lists for it
#               and passes every other.
#   shipped     both macros 0: every case passes, exit 0.
# All arms under ASan + UBSan, 64-bit. The headers are the units under test; the core's transaction parser,
# hashes and base58 (BRTransaction.c and the units it calls) are linked, built once, without the seams; so
# is the second translation unit. The arms run with detect_leaks=0; then every case runs once more on the
# shipped arm under LeakSanitizer where the platform has one (said so, and skipped, where it does not --
# Apple clang on macOS).
#
# Gates first: each header must test its seam's VALUE (#if, never #ifdef) with an #ifndef default.
# Then:
#   PORTABILITY GATE  each header includes only libc and the core headers whose types or functions it
#                     uses, reads no clock, does no I/O and allocates nothing, and builds clean under -Wall
#                     -Wextra -Wpedantic -Werror at c99/c11/c17 (two TUs, the headers included twice in the
#                     second); for iphoneos/iphonesimulator, and through Swift's C importer (swiftc
#                     -import-objc-header) on macOS, where an Xcode SDK / swiftc is present (skipped, and
#                     said so, elsewhere).
#
# CORE_DIR may be set to run against a core checkout other than the android submodule.
# MACRO CONVENTION: VALUE of -DASSET_PROVENANCE_UNFIXED and -DASSET_RULE_GATE_UNFIXED, each a set of bits
# (one bit per comparison arm, =0 shipped).
# Exit code 0 = every expectation held, 1 = otherwise (a build error is never evidence).
set -uo pipefail   # not -e: the comparison arm's nonzero exit is inspected
# Here-strings and files, never pipes into an early-exit reader (harness_pipe_status_kat).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="${CORE_DIR:-$REPO_ROOT/native/src/main/jni/digibytewallet-core}"
PROV="$CORE_DIR/BRAssetProvenance.h"
GATE="$CORE_DIR/BRAssetRuleGate.h"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CC="${CC:-clang}"
export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 detect_leaks=0 symbolize=0"
ASAN_LEAK="halt_on_error=1 abort_on_error=1 detect_leaks=1 symbolize=0"
export UBSAN_OPTIONS="halt_on_error=1 print_stacktrace=1"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

# <case>:<the comparison arms that must fail it, comma-separated; - for none>. p<bit> is a bit of
# ASSET_PROVENANCE_UNFIXED, r<bit> a bit of ASSET_RULE_GATE_UNFIXED. Every walker_* case also holds the
# header's walk to the model's, read for read and write for write, so an arm that changes what the walk
# asks the store fails it even where the Kotlin test's own assertions would not.
CASES=(
    walker_short_chain:p16,p64
    walker_deep_chain:p1,p16,p64,p128
    walker_resume_no_rewalk:p1,p16,p128
    walker_memory:p16,p64
    walker_known_ancestor:p16
    walker_transient_keeps_frontier:p2,p16
    walker_dead_end_clears:p4,p16
    walker_cycle:p8,p16
    walker_default_mode_cache:-
    walker_force_past_start:p32,p64
    walker_force_past_ancestor:p32,p64
    walker_force_unreachable:p2,p32,p16384
    walker_issuance_rows:p16384
    binding_different_tx_unavailable:p2,p512
    binding_next_endpoint:p64
    binding_no_id_no_answer:-
    binding_wrong_answer_refused:-
    binding_guard_requested:-
    binding_guard_upper_case:-
    hop_carriers:-
    hop_rules:p256,p512,p1024
    hop_edges:-
    walk_refusals:p1,p4,p16,p32
    walk_hop_counts:p1,p2,p16,p32,p128
    walk_path_rules:p1,p8,p16,p32,p64
    capacity:p1,p32,p64
    zero_structs:p16384
    named_rules:p2048,p4096,p8192,p32768
    named_through_walk:p2048,p4096
    named_edges:p4096
    gate_opcode_3:-
    gate_opcode_4:r8
    gate_api_rules:r4
    gate_locked_rule_free:-
    gate_unlocked_unknown:r1,r2
    gate_no_opcode:-
    gate_outside_set:r16
    gate_never_loosens:r1,r2
    gate_send_state:r64
    gate_enum_shared:r1,r2,r8
    gate_for_asset:r8,r32
    send_rule_bound:r8
    send_never_walked:r64
    send_proxy_rules:r4
    send_two_stores:r4
    send_no_utxo:-
    send_placeholder:r32
    sweep_walk:p1,p2,p4,p8,p16,p32,p64,p128
    sweep_hop:p256,p512,p1024
    sweep_named:p2048,p4096,p8192,p32768
    sweep_gate:r1,r2,r4,r8,r16,r32
    two_units:-
)
ARMS=(p1:no-resume p2:unavailable-clears p4:dead-end-keeps p8:no-cycle-check p16:ancestor-ignored
      p32:force-reads-cache p64:path-start-only p128:hops-not-carried p256:unlocked-named p512:no-bytes-dead-end
      p1024:burn-walks p2048:other-asset p4096:parents-not-tried p8192:parents-repeated p16384:issuance-any-row
      p32768:known-unchecked
      r1:api-loosens r2:unlocked-final r4:api-ignored r8:opcode-4-free r16:any-opcode-free r32:other-asset
      r64:unknown-sends)

macro_of() {   # <p|r> -> the seam macro
    case "$1" in p) echo ASSET_PROVENANCE ;; r) echo ASSET_RULE_GATE ;; esac
}

# ---- the seams are real --------------------------------------------------------------------
for h in "$PROV" "$GATE"; do
    if [ ! -f "$h" ]; then
        echo "GATE FAILURE: $h not found: a core pin from before the provenance walk moved into the core."; exit 1
    fi
done
HDR_P="$(cat "$PROV")"
HDR_R="$(cat "$GATE")"
for fn in BRAssetIssuanceFactsMatch BRAssetHopClassify BRAssetWalkBegin BRAssetWalkStep BRAssetFactsForNamedAssetBegin \
          BRAssetFactsForNamedAsset; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_P"; then
        echo "GATE FAILURE: $PROV does not define $fn."; exit 1
    fi
done
for fn in BRAssetRuleStateOf BRAssetRuleStateForAsset BRAssetRuleAllowsSend; do
    if ! grep -Eq "static inline [a-zA-Z0-9_ ]+ \*?$fn\(" <<<"$HDR_R"; then
        echo "GATE FAILURE: $GATE does not define $fn."; exit 1
    fi
done
for pair in "ASSET_PROVENANCE:$PROV" "ASSET_RULE_GATE:$GATE"; do
    m="${pair%%:*}"; h="${pair#*:}"; text="$(cat "$h")"
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*(el)?if[[:space:]]+!?[[:space:]]*\(?${m}_UNFIXED" <<<"$text"; then
        echo "GATE FAILURE: $h does not test the value of ${m}_UNFIXED; the arms would not differ."; exit 1
    fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*ifndef[[:space:]]+${m}_UNFIXED" <<<"$text" ||
       grep -Eq "^[[:space:]]*#[[:space:]]*ifdef[[:space:]]+${m}_UNFIXED" <<<"$text"; then
        echo "GATE FAILURE: $h selects an arm by the macro's PRESENCE, or gives it no default of 0."; exit 1
    fi
done
for arm in "${ARMS[@]}"; do   # every arm's bit is one its header reads
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"; m="$(macro_of "$kind")"
    if [ "$kind" = p ]; then text="$HDR_P"; else text="$HDR_R"; fi
    if ! grep -Eq "^[[:space:]]*#[[:space:]]*define[[:space:]]+${m}_RED_[A-Z0-9_]+[[:space:]]+${bit}[[:space:]]*$" <<<"$text"; then
        echo "GATE FAILURE: no ${m}_RED_* seam of value $bit; arm $tag would be the shipped code."; exit 1
    fi
done

FAIL=0

# The core units the KAT links (the transaction parser for the fixtures and BRRawTxId, the hashes and base58
# for BRAssetId.h), compiled once per flag set; they do not read the seams.
shopt -s nullglob; SHA3=("$CORE_DIR"/crypto/sha3/*.c); shopt -u nullglob
UNITS=("$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRTransaction.c "$CORE_DIR"/BRDigiDollar.c
       "$CORE_DIR"/BRDigiAsset.c "$CORE_DIR"/BRKey.c "$CORE_DIR"/BRNetwork.c "$CORE_DIR"/BRAddress.c
       "$CORE_DIR"/BRSet.c "$CORE_DIR"/BRBase58.c "$CORE_DIR"/BRBech32.c "$CORE_DIR"/BRCrypto.c
       "$CORE_DIR"/BRBIP32Sequence.c "$CORE_DIR"/BRBIP39Mnemonic.c "$CORE_DIR"/crypto/groestl.c
       "$CORE_DIR"/crypto/skein.c "$CORE_DIR"/crypto/qubit.c "$CORE_DIR"/crypto/odocrypt.c "${SHA3[@]}")
objs() {   # <dir> <extra flags...>: compile UNITS into <dir>, print nothing; fails on a build error
    local dir="$1"; shift
    mkdir -p "$dir"
    local u
    for u in "${UNITS[@]}"; do
        "$CC" -w -std=c99 -include stdint.h "$@" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
            -c "$u" -o "$dir/$(basename "$(dirname "$u")")_$(basename "${u%.c}").o" || return 1
    done
}

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
for pair in "$PROV|\"BRAssetDecode.h\" \"BRAssetId.h\" \"BRTransaction.h\" <stddef.h> <stdint.h> <string.h> " \
            "$GATE|\"BRSweepPartition.h\" <stdint.h> <string.h> "; do
    h="${pair%%|*}"; want="${pair#*|}"
    CODE="$(strip_comments "$h")"
    includes="$(grep -E '^[[:space:]]*#[[:space:]]*include' <<<"$CODE" | sed -E 's/.*include[[:space:]]*//; s/[[:space:]]+$//' | LC_ALL=C sort -u | tr '\n' ' ')"
    if [ "$includes" != "$want" ]; then
        echo "PORTABILITY GATE FAILED: $(basename "$h") includes $includes(want $want)"; FAIL=1
    fi
    if grep -Eq "$IO_RE" <<<"$CODE"; then
        echo "PORTABILITY GATE FAILED: $(basename "$h") calls a clock, I/O or the allocator:"
        grep -nE "$IO_RE" <<<"$CODE" | sed 's/^/      /'; FAIL=1
    fi
done
cat > "$BUILD_DIR/strict1.c" <<'EOF'
#include "BRAssetProvenance.h"
#include "BRAssetRuleGate.h"
int strict_tu1(void);
int strict_tu1(void)
{
    BRAssetTxidSlot slots[BR_ASSET_WALK_SLOTS(1)];
    BRAssetWalk w;
    BRAssetWalkFeed feed;

    memset(&feed, 0, sizeof(feed));
    if (BRAssetWalkBegin(&w, "t0", 0, 1, slots, BR_ASSET_WALK_SLOTS(1)) != BR_ASSET_WALK_NEEDS_KNOWN) return 0;
    if (BRAssetWalkStep(&w, &feed) != BR_ASSET_WALK_NEEDS_FRONTIER) return 0;
    if (BRAssetWalkStep(&w, &feed) != BR_ASSET_WALK_NEEDS_KNOWN) return 0;
    if (BRAssetWalkStep(&w, &feed) != BR_ASSET_WALK_NEEDS_HOP) return 0;
    feed.hop = BRAssetHopClassify(1, NULL);
    return BRAssetWalkStep(&w, &feed) == BR_ASSET_WALK_DONE && w.frontier == BR_ASSET_FRONTIER_CLEAR && ! w.hasAnswer &&
           BRAssetRuleStateOf(1, 4, 0, 0, 0, 0) == BR_SWEEP_RULE_BOUND;
}
EOF
cat > "$BUILD_DIR/strict2.c" <<'EOF'
#include "BRAssetProvenance.h"
#include "BRAssetProvenance.h"
#include "BRAssetRuleGate.h"
#include "BRAssetRuleGate.h"
int strict_tu1(void);
int main(void)
{
    BRAssetNamedSearch s;
    BRAssetNamedFeed feed;
    BRAssetResolvedFacts f;

    memset(&feed, 0, sizeof(feed));
    memset(&f, 0, sizeof(f));
    f.assetId[0] = 'L';
    f.hasIssuanceOpcode = 1;
    feed.facts = &f;
    if (BRAssetFactsForNamedAssetBegin(&s, "L", "t0") != BR_ASSET_NAMED_NEEDS_KNOWN) return 1;
    if (BRAssetFactsForNamedAsset(&s, &feed) != BR_ASSET_NAMED_DONE || ! s.hasAnswer) return 1;
    return (BRAssetIssuanceFactsMatch(&f, "L") && BRAssetHopClassify(0, NULL).kind == BR_ASSET_HOP_UNAVAILABLE &&
            BRAssetRuleStateForAsset("L", "L", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_NONE && BRAssetRuleAllowsSend(BR_SWEEP_RULE_NONE) &&
            BR_ASSET_WALK_DEFAULT_MAX_HOPS == 50 && BR_ASSET_TXID_BUFFER == 65 && strict_tu1()) ? 0 : 1;
}
EOF
strict_ok=1
if ! objs "$BUILD_DIR/strict_objs" -O0; then
    echo "PORTABILITY GATE FAILED: the core units did not compile"; FAIL=1; strict_ok=0
else
    for std in c99 c11 c17; do
        if ! "$CC" -std=$std -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
                "$BUILD_DIR/strict1.c" "$BUILD_DIR/strict2.c" "$BUILD_DIR"/strict_objs/*.o -lm -lpthread -o "$BUILD_DIR/strict_$std" ||
           ! "$BUILD_DIR/strict_$std"; then
            echo "PORTABILITY GATE FAILED: -std=$std -Wall -Wextra -Wpedantic -Werror (two TUs)"; FAIL=1; strict_ok=0
        fi
    done
fi
ios_note="no Xcode SDK here, iOS cross-compile skipped"
if command -v xcrun >/dev/null 2>&1 && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    ios_note="iphoneos + iphonesimulator"
    for sdk in iphoneos iphonesimulator; do
        if ! xcrun --sdk $sdk clang -arch arm64 -std=c11 -Wall -Wextra -Wpedantic -Werror -Wno-missing-braces -Wno-#pragma-messages -I "$CORE_DIR" \
                -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/strict2.c" -o "$BUILD_DIR/ios_$sdk.o"; then
            echo "PORTABILITY GATE FAILED: $sdk arm64"; FAIL=1; strict_ok=0
        fi
    done
fi
swift_note="not macOS, Swift import skipped (the iOS toolchain is Apple's)"
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null 2>&1; then
    printf '#include "BRAssetProvenance.h"\n#include "BRAssetRuleGate.h"\n' > "$BUILD_DIR/bridging.h"
    cat > "$BUILD_DIR/use.swift" <<'EOF'
var walk = BRAssetWalk()
var slots = [BRAssetTxidSlot](repeating: BRAssetTxidSlot(), count: 51)
var need: BRAssetWalkNeed = BRAssetWalkBegin(&walk, "t0", 1, BR_ASSET_WALK_DEFAULT_MAX_HOPS, &slots, slots.count)
var feed = BRAssetWalkFeed()
need = BRAssetWalkStep(&walk, &feed)
feed.hop = BRAssetHopClassify(0, nil)
let kind: BRAssetHopKind = feed.hop.kind
need = BRAssetWalkStep(&walk, &feed)
let done = need == BR_ASSET_WALK_DONE
let frontier: BRAssetFrontierWrite = walk.frontier
let resume: String = withUnsafeBytes(of: walk.frontierResume) { String(cString: $0.bindMemory(to: CChar.self).baseAddress!) }
let why: BRAssetWalkWhy = walk.why
let path: Int = walk.pathCount
var facts = BRAssetResolvedFacts()
facts.hasIssuanceOpcode = 1
let matches: Int32 = BRAssetIssuanceFactsMatch(&facts, "")
var search = BRAssetNamedSearch()
var named = BRAssetNamedFeed()
let first: BRAssetNamedNeed = BRAssetFactsForNamedAssetBegin(&search, "LaA", "t0")
let second: BRAssetNamedNeed = BRAssetFactsForNamedAsset(&search, &named)
let state: BRSweepRuleState = BRAssetRuleStateOf(1, 1, 1, 1, 0, 0)
let forAsset: BRSweepRuleState = BRAssetRuleStateForAsset("LaA", "LaB", 1, 1, 1, 1, 0, 0)
let send: Int32 = BRAssetRuleAllowsSend(state)
let limits: (Int32, Int32, Int32) = (BR_ASSET_TXID_LENGTH, BR_ASSET_TXID_BUFFER, BR_ASSET_WALK_DEFAULT_MAX_HOPS)
print(done, kind == BR_ASSET_HOP_UNAVAILABLE, frontier == BR_ASSET_FRONTIER_PUT, resume, why == BR_ASSET_WALK_WHY_NONE, path,
      matches, first == BR_ASSET_NAMED_NEEDS_KNOWN, second == BR_ASSET_NAMED_NEEDS_RESOLVE, state == BR_SWEEP_RULE_NONE,
      forAsset == BR_SWEEP_RULE_UNKNOWN, send, limits, BR_ASSET_ID_BUFFER, BR_ASSET_CID_BUFFER)
EOF
    # The headers alone, without the core's module.modulemap (Swift would read it as the BRCore module, and it
    # names headers this core no longer has): the way an app's bridging header sees them.
    mkdir -p "$BUILD_DIR/swift_inc/crypto" "$BUILD_DIR/swift_inc/secp256k1/include"
    cp "$CORE_DIR"/*.h "$BUILD_DIR/swift_inc/"
    cp "$CORE_DIR"/crypto/*.h "$BUILD_DIR/swift_inc/crypto/"
    cp "$CORE_DIR"/secp256k1/include/*.h "$BUILD_DIR/swift_inc/secp256k1/include/" 2>/dev/null
    if swiftc -typecheck -import-objc-header "$BUILD_DIR/bridging.h" -Xcc -I"$BUILD_DIR/swift_inc" \
            -Xcc -I"$BUILD_DIR/swift_inc/secp256k1/include" "$BUILD_DIR/use.swift" >"$BUILD_DIR/swift.out" 2>&1; then
        swift_note="Swift imports them"
    else
        echo "PORTABILITY GATE FAILED: swiftc could not import the headers"; sed -n '1,12p' "$BUILD_DIR/swift.out" | sed 's/^/      /'
        FAIL=1; strict_ok=0
    fi
fi
[ $strict_ok -eq 1 ] && echo "portability gate: libc + the core headers each uses, no clock/I/O/allocation; clean at c99/c11/c17 ($ios_note; $swift_note)"

# ---- the arms ------------------------------------------------------------------------------
cat > "$BUILD_DIR/tu2.c" <<'EOF'
#include "BRAssetProvenance.h"
#include "BRAssetProvenance.h"
#include "BRAssetRuleGate.h"
#include "BRAssetRuleGate.h"
int asset_provenance_rules_kat_tu2(void);
int asset_provenance_rules_kat_tu2(void)   /* built without the seams: links into every arm */
{
    BRAssetTxidSlot slots[BR_ASSET_WALK_SLOTS(2)];
    BRAssetWalk w;
    BRAssetWalkFeed feed;
    BRAssetNamedSearch s;
    BRAssetNamedFeed nf;

    memset(&feed, 0, sizeof(feed));
    memset(&nf, 0, sizeof(nf));
    if (BRAssetWalkBegin(&w, "t0", 1, 2, slots, BR_ASSET_WALK_SLOTS(2)) != BR_ASSET_WALK_NEEDS_FRONTIER) return 0;
    if (BRAssetWalkStep(&w, &feed) != BR_ASSET_WALK_NEEDS_HOP) return 0;
    feed.hop = BRAssetHopClassify(0, NULL);
    if (BRAssetWalkStep(&w, &feed) != BR_ASSET_WALK_DONE || w.frontier != BR_ASSET_FRONTIER_PUT || strcmp(w.frontierResume, "t0") != 0) return 0;
    if (BRAssetFactsForNamedAssetBegin(&s, "LaA", "t0") != BR_ASSET_NAMED_NEEDS_KNOWN) return 0;
    if (BRAssetFactsForNamedAsset(&s, &nf) != BR_ASSET_NAMED_NEEDS_RESOLVE) return 0;
    return BRAssetRuleStateOf(1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_NONE && BRAssetRuleAllowsSend(BR_SWEEP_RULE_NONE) &&
           BRAssetRuleStateForAsset("LaA", "LaB", 1, 1, 1, 1, 0, 0) == BR_SWEEP_RULE_UNKNOWN &&
           ! BRAssetIssuanceFactsMatch(NULL, "LaA");
}
EOF

SAN=(-g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
objs "$BUILD_DIR/san_objs" "${SAN[@]}" || { echo "GATE FAILURE: the core units did not compile under the sanitizers."; exit 1; }
"$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" -c "$BUILD_DIR/tu2.c" \
    -o "$BUILD_DIR/san_objs/zz_tu2.o" || { echo "GATE FAILURE: the second translation unit did not compile."; exit 1; }
build() {   # <out> <ASSET_PROVENANCE_UNFIXED bits> <ASSET_RULE_GATE_UNFIXED bits>
    "$CC" -w -std=c99 -include stdint.h "${SAN[@]}" -DASSET_PROVENANCE_UNFIXED="$2" -DASSET_RULE_GATE_UNFIXED="$3" \
        -I "$CORE_DIR" -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/asset_provenance_rules_kat_main.c" "$BUILD_DIR"/san_objs/*.o -lm -lpthread -o "$1"
}

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; kind="${tag:0:1}"; bit="${tag:1}"
    if [ "$kind" = p ]; then p=$bit; r=0; else p=0; r=$bit; fi
    build "$BUILD_DIR/red_$tag" "$p" "$r" || { echo "GATE FAILURE: comparison arm $tag did not compile (a build error is not evidence)."; exit 1; }
done
build "$BUILD_DIR/green" 0 0 || { echo "GATE FAILURE: shipped arm did not compile."; exit 1; }

known="$("$BUILD_DIR/green" list | sort | tr '\n' ' ')"
listed="$(printf '%s\n' "${CASES[@]%%:*}" | sort | tr '\n' ' ')"
if [ "$known" != "$listed" ]; then
    echo "GATE FAILURE: case lists disagree"; echo "  binary: $known"; echo "  run.sh: $listed"; exit 1
fi

for arm in "${ARMS[@]}"; do
    tag="${arm%%:*}"; name="${arm#*:}"; kind="${tag:0:1}"; bit="${tag:1}"
    echo "--- COMPARISON ARM -D$(macro_of "$kind")_UNFIXED=$bit ($name): fails exactly its cases ---"
    red_any=0
    for entry in "${CASES[@]}"; do
        c="${entry%%:*}"; arms_of="${entry##*:}"
        want=pass
        case ",$arms_of," in *",$tag,"*) want=fail ;; esac
        out="$("$BUILD_DIR/red_$tag" "$c" 2>&1)"; rc=$?
        if grep -Eq "$SAN_RE" <<<"$out"; then echo "  [$tag $c] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" <<<"$out" | head -3; FAIL=1; continue; fi
        if [ "$want" = fail ]; then
            red_any=1
            if [ $rc -ne 0 ] && grep -q "^RESULT $c fail$" <<<"$out"; then
                first="$(grep -m1 '^FAIL:' <<<"$out")"
                echo "  [$tag $c] failed, as it must: ${first:6:150}"
            else
                echo "  [$tag $c] GATE FAILURE: this arm did not fail the case -- it cannot see the defect"; FAIL=1
            fi
        elif [ $rc -ne 0 ] || ! grep -q "^RESULT $c pass$" <<<"$out"; then
            echo "  [$tag $c] GATE FAILURE: failed in an arm that is not its own"; grep '^FAIL:' <<<"$out" | cut -c1-200 | sed 's/^/      /'; FAIL=1
        fi
    done
    [ $red_any -eq 0 ] && { echo "  [$tag] GATE FAILURE: no case is listed for this arm; it proves nothing"; FAIL=1; }
done

echo "--- SHIPPED ARM (both macros 0): every case passes, no sanitizer report ---"
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

[ $FAIL -eq 0 ] && echo "PASS: asset_provenance_rules_kat (${#CASES[@]} cases: ${#ARMS[@]} comparison arms each failing exactly its cases, shipped arm clean under ASan + UBSan; leak check $leak_note; portability)"
[ $FAIL -ne 0 ] && echo "FAIL: asset_provenance_rules_kat"
exit $FAIL
