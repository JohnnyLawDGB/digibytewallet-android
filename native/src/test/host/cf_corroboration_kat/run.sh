#!/usr/bin/env bash
# Host KAT runner: second-source corroboration of the filter-header chain
# (observe-only). See cf_corroboration_kat_main.c for the invariant.
#
# Rig: main.c #include-s BOTH BRPeer.c and BRPeerManager.c (the
# cf_checkpoint_quorum_kat idiom), so neither is passed as a separate unit below.
# The two request senders the connect hook calls are intercepted by a per-TU
# rename inside main.c (no linker flags needed).
#
# ==== RED-BEFORE-GREEN GATE ====
#   RED   -DCF_CORROBORATION_UNFIXED restores the pre-corroboration shape in
#         BRPeerManager.c (handler logs only; the connect hook sends no
#         getcfcheckpt). The build must exit nonzero AND fail at the connect
#         and agree checks named below -- proof the seam reaches both sites.
#   GREEN the production shape must pass every check, exit 0, and print
#         exactly ONE "cf-corroborate: height <h> MISMATCH" line for the one
#         mismatching reply in test_disagree plus the one in test_same_address.
#   GUARD test_never, test_same_address, test_bounded, test_request_gated_at_reply_cap:
#         same outcome in both arms (the fields stay put / no request either way);
#         they pin the bounds, not the fix.
#   RED   Four G5-QUORUM reference arms, each compared WHOLE against its listed FAIL
#         lines (see red_exact below; the lists are the contract, the counts follow them):
#         -DCF_CKPT_QUORUM_UNFIXED  the 4.1.3 every-connected-peer denominator;
#         -DCF_CKPT_RECHECK_UNFIXED ask once per connect (no re-check, so no probe either);
#         -DCF_CKPT_PROBE_UNFIXED   re-check with a full cfcheckpt, not a single-height probe;
#         -DCF_CKPT_G5R2_UNFIXED    the f795f60 shape: per-port counting, a dispute cap of
#                                   CF_DISAGREED_CAP, Connecting peers voting, votes surviving a
#                                   reconnect, a full table refusing newcomers, late probe
#                                   answers handed to the sync path, no filter-type check
#                                   on a probe answer;
#         -DCF_CKPT_G5R3_UNFIXED    the dbce2b5 group rule (any disputing voter makes its
#                                   network group "for").
#         The g5r3-colocate check is not red under the f795f60 or 4.1.3 arms: their per-port
#         denominators already outnumber the two newcomers, so it does not apply there.
#         The recheck and probe arms also list "g5r3-type: setup -- the probe went out" (and
#         "g5r2-late: setup -- ..."): those tests need a single-height probe to exist before they
#         can test its answer, and neither arm sends one (no re-check at all / a cfcheckpt instead),
#         so their setup check fails there by construction, not because of the behaviour tested.
#   GUARD g5r3-guard (a genuine one-hash sync batch at the expected height is appended), the
#         g5r3-colocate control pass in every arm. g5r3-planted (the accepted invariant: a planted
#         agreeing voter delays but never falsifies a rebuild) passes in green; the earlier rules
#         rebuild sooner there (4.1.3 with 2 extra hosts, f795f60 with 1, dbce2b5 with 0), so its
#         "kept" checks are listed in those arms.
#   GUARD g5-lone and g5-agreers pass in every G5 arm: one replier never rebuilds, and
#         answering agreers count against a dissent. "Not re-asked before the crossing" and the
#         full-capacity jam are guards too (the earlier shapes never re-ask; f795f60 already
#         evicted departed repliers at full capacity).
# Both word sizes are built and run (ASan, -m32 loop as wire_count_bounds_kat).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

export ASAN_OPTIONS="halt_on_error=1 abort_on_error=1 symbolize=0"
SAN_RE='ERROR: AddressSanitizer|ERROR: LeakSanitizer|runtime error:'

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

UNITS=(
    "$CORE_DIR/BRWallet.c"
    "$CORE_DIR/BRTransaction.c"
    "$CORE_DIR/BRMerkleBlock.c"
    "$CORE_DIR/BRCompactFilterChain.c"
    "$CORE_DIR/BRGCSFilter.c"
    "$CORE_DIR/BRWalletFilterElements.c"
    "$CORE_DIR/BRCFScanLedger.c"
    "$CORE_DIR/BRNetwork.c"
    "$CORE_DIR/BRDigiDollar.c"
    "$CORE_DIR/BRDigiAsset.c"
    "$CORE_DIR/BRKey.c"
    "$CORE_DIR/BRAddress.c"
    "$CORE_DIR/BRSet.c"
    "$CORE_DIR/BRBase58.c"
    "$CORE_DIR/BRBech32.c"
    "$CORE_DIR/BRCrypto.c"
    "$CORE_DIR/BRBIP32Sequence.c"
    "$CORE_DIR/BRBIP39Mnemonic.c"
    "$CORE_DIR/crypto/groestl.c"
    "$CORE_DIR/crypto/skein.c"
    "$CORE_DIR/crypto/qubit.c"
    "$CORE_DIR/crypto/odocrypt.c"
    "${SHA3_SRCS[@]}"
)

build() {
    local out="$1"; local bits="$2"; shift 2
    local m=()
    [ "$bits" = "32" ] && m=(-m32)
    # -DDEBUG: on the host peer_log is compiled out without it (BRPeer.h); the green
    # arm asserts on the MISMATCH and request log lines.
    clang -w -include stdint.h -g -DDEBUG -DCF_CKPT_KAT_READOUT -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        -I "$CORE_DIR/secp256k1" \
        "$SCRIPT_DIR/cf_corroboration_kat_main.c" \
        "${UNITS[@]}" \
        -lm -lpthread \
        -o "$out"
}

# The seam must exist at BOTH sites or the -D would be inert.
if [ "$(grep -c 'CF_CORROBORATE_ACT_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 2 ]; then
    echo "GATE FAILURE: BRPeerManager.c has no CF_CORROBORATE_ACT_UNFIXED seam; -D would be inert."; exit 1
fi
for seam in CF_CKPT_QUORUM_UNFIXED CF_CKPT_RECHECK_UNFIXED CF_CKPT_PROBE_UNFIXED CF_CKPT_G5R2_UNFIXED CF_CKPT_G5R3_UNFIXED; do
    if ! grep -q "$seam" "$CORE_DIR/BRPeerManager.c"; then
        echo "GATE FAILURE: BRPeerManager.c has no $seam seam; -D would be inert."; exit 1
    fi
done
if [ "$(grep -c 'CF_CORROBORATION_UNFIXED' "$CORE_DIR/BRPeerManager.c")" -lt 2 ]; then
    echo "GATE FAILURE: BRPeerManager.c has fewer than two CF_CORROBORATION_UNFIXED seams; -D would be inert."
    exit 1
fi

FAIL=0

run_bits() {
    local bits="$1"
    echo "======================================================================"
    echo "=== ${bits}-bit build ==="
    echo "======================================================================"

    if ! build "$BUILD_DIR/red${bits}" "$bits" -DCF_CORROBORATION_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit red arm did not compile (a build error is not evidence)."
        FAIL=1; return
    fi
    if ! build "$BUILD_DIR/green${bits}" "$bits"; then
        echo "GATE FAILURE: ${bits}-bit green arm did not compile."
        FAIL=1; return
    fi

    echo "--- RED ARM (-DCF_CORROBORATION_UNFIXED): must fail the connect + agree + disagree checks ---"
    "$BUILD_DIR/red${bits}" > "$BUILD_DIR/red${bits}.log" 2>&1; local rc=$?
    if [ "$rc" -eq 0 ]; then
        echo "  [$bits red] GATE FAILURE: the pre-corroboration build exited 0"
        sed 's/^/      | /' "$BUILD_DIR/red${bits}.log" | grep -E "^      \| (PASS|FAIL)" | head -20
        FAIL=1
    fi
    for want in \
        "FAIL: connect: exactly one getcfcheckpt is sent on a filter-capable connect" \
        "FAIL: agree: cfCorroboratedThrough advances to the highest matched 1000-multiple above the compiled table" \
        "FAIL: disagree: cfCheckptDisagreeCount increments once for the mismatching reply"; do
        if grep -qF "$want" "$BUILD_DIR/red${bits}.log"; then
            echo "  [$bits red] required failure seen: ${want#FAIL: }"
        else
            echo "  [$bits red] GATE FAILURE: expected line missing: $want"
            FAIL=1
        fi
    done
    if grep -Eq "$SAN_RE" "$BUILD_DIR/red${bits}.log"; then
        echo "  [$bits red] GATE FAILURE: sanitizer report in the red arm"; FAIL=1
    fi
    echo "  [$bits red] GUARD cases (same outcome in both arms): never / same-address / bounded / request_gated_at_reply_cap"

    if ! build "$BUILD_DIR/act${bits}" "$bits" -DCF_CORROBORATE_ACT_UNFIXED; then
        echo "GATE FAILURE: ${bits}-bit observe-only arm did not compile."; FAIL=1; return
    fi
    echo "--- RED ARM (-DCF_CORROBORATE_ACT_UNFIXED, observe-only): the majority case must fail ---"
    "$BUILD_DIR/act${bits}" > "$BUILD_DIR/act${bits}.log" 2>&1; rc=$?
    if [ "$rc" -ne 0 ] && grep -qF "FAIL: majority: the chain is rebuilt (the source's chain is dropped)" "$BUILD_DIR/act${bits}.log" \
       && ! grep -qF "FAIL: setup" "$BUILD_DIR/act${bits}.log"; then
        echo "  [$bits act] required failure seen: the observe-only build leaves the outlier chain in place"
    else
        echo "  [$bits act] GATE FAILURE: the observe-only arm did not fail the majority case (rc=$rc)"; FAIL=1
    fi

    # G5-QUORUM reference arms: each must fail exactly its own checks (the set of FAIL lines is compared whole).
    red_exact() {   # $1 = flag, $2 = label, remaining = the exact expected FAIL lines
        local flag="$1" label="$2"; shift 2
        if ! build "$BUILD_DIR/$label${bits}" "$bits" "-D$flag"; then
            echo "GATE FAILURE: ${bits}-bit $flag arm did not compile."; FAIL=1; return
        fi
        "$BUILD_DIR/$label${bits}" > "$BUILD_DIR/$label${bits}.log" 2>&1; local r=$?
        local want got
        want="$(printf 'FAIL: %s\n' "$@" | sort)"
        got="$(grep '^FAIL: ' "$BUILD_DIR/$label${bits}.log" | sort)"
        if [ "$r" -ne 0 ] && [ "$want" = "$got" ]; then
            echo "  [$bits $label] fails exactly its $# G5-QUORUM check(s)"
        else
            echo "  [$bits $label] GATE FAILURE (rc=$r): expected exactly:"; sed 's/^/      | /' <<<"$want"
            echo "      saw:"; sed 's/^/      | /' <<<"$got"; FAIL=1
        fi
        if grep -Eq "$SAN_RE" "$BUILD_DIR/$label${bits}.log"; then
            echo "  [$bits $label] GATE FAILURE: sanitizer report"; FAIL=1
        fi
    }
    echo "--- RED ARM (-DCF_CKPT_QUORUM_UNFIXED): the 4.1.3 every-connected-peer rule fails exactly these ---"
    red_exact CF_CKPT_QUORUM_UNFIXED quorum \
        "g5-silent: two agreeing answering dissenters beside three silent peers rebuild the chain" \
        "g5-silent: the cfheaders source is marked misbehaving" \
        "g5-silent: the rebuild clears the dissent, replier and re-check windows" \
        "g5-source-dup: two agreeing dissenters are not out-voted by the source's own address" \
        "g5-departed: a dissenter that has disconnected no longer counts toward the quorum" \
        "g5-one-host: three ports on one non-source address cannot reach the quorum alone" \
        "g5r2-jam: two honest hosts rebuild after 8 departed one-host disputes" \
        "g5r2-jam: two honest hosts rebuild after a full table of departed disputes (eviction)" \
        "g5r2-ports: one host agreeing on three ports does not out-vote two dissenting hosts" \
        "g5r2-netgroup: two addresses in one /16 cannot reach the quorum alone" \
        "g5r2-netgroup: a peer in the source's network group is not an independent dissent" \
        "g5r2-state: a Connecting peer's earlier dispute does not vote" \
        "g5r2-late: setup -- the chain was rebuilt" \
        "g5r2-late: a probe answer arriving after a rebuild is consumed, not taken for a sync batch" \
        "g5r3-planted: with 2 extra clean dissenting host(s) the chain is kept (delayed, not falsified)"
    echo "--- RED ARM (-DCF_CKPT_RECHECK_UNFIXED): ask-once-per-connect fails exactly the re-check and probe checks ---"
    red_exact CF_CKPT_RECHECK_UNFIXED recheck \
        "g5-recheck: after the crossing the peer is re-asked exactly once, at the new stop (the source never)" \
        "g5-recheck: a clean cfheaders append re-asks the peer once its chain covers the new stop" \
        "g5-probe: the re-check is one single-height getcfheaders at the crossed height, not a cfcheckpt" \
        "g5-probe: an agreeing answer corroborates height h and counts its peer as a replier" \
        "g5-probe: a differing answer is recorded as a dissent at (h, the peer's header) and appends nothing" \
        "g5-probe: the tag is spent by the first answer" \
        "g5r2-late: setup -- the probe went out" \
        "g5r3-type: setup -- the probe went out"
    echo "--- RED ARM (-DCF_CKPT_PROBE_UNFIXED): a full-cfcheckpt re-check fails exactly the probe checks ---"
    red_exact CF_CKPT_PROBE_UNFIXED probe \
        "g5-probe: the re-check is one single-height getcfheaders at the crossed height, not a cfcheckpt" \
        "g5-probe: an agreeing answer corroborates height h and counts its peer as a replier" \
        "g5-probe: a differing answer is recorded as a dissent at (h, the peer's header) and appends nothing" \
        "g5-probe: the tag is spent by the first answer" \
        "g5r2-late: setup -- the probe went out" \
        "g5r3-type: setup -- the probe went out"
    echo "--- RED ARM (-DCF_CKPT_G5R2_UNFIXED): the f795f60 shape fails exactly the review-round-2 checks ---"
    red_exact CF_CKPT_G5R2_UNFIXED g5r2 \
        "g5r2-jam: two honest hosts rebuild after 8 departed one-host disputes" \
        "g5r2-ports: one host agreeing on three ports does not out-vote two dissenting hosts" \
        "g5r2-netgroup: two addresses in one /16 cannot reach the quorum alone" \
        "g5r2-netgroup: a peer in the source's network group is not an independent dissent" \
        "g5r2-evict: a full table of live peers still admits the newcomer's vote" \
        "g5r2-state: a Connecting peer's earlier dispute does not vote" \
        "g5r2-state: a peer reconnecting at the same address and port starts with no vote" \
        "g5r2-late: a probe answer arriving after a rebuild is consumed, not taken for a sync batch" \
        "g5r3-type: a tagged probe answer of another filter type is consumed and not judged" \
        "g5r3-planted: with 1 extra clean dissenting host(s) the chain is kept (delayed, not falsified)" \
        "g5r3-planted: with 2 extra clean dissenting host(s) the chain is kept (delayed, not falsified)"
    echo "--- RED ARM (-DCF_CKPT_G5R3_UNFIXED): the dbce2b5 any-voter group rule fails exactly the co-location check ---"
    red_exact CF_CKPT_G5R3_UNFIXED g5r3 \
        "g5r3-colocate: newcomers inside honest agreeing hosts' /16s do not turn those hosts into votes" \
        "g5r3-planted: with 0 extra clean dissenting host(s) the chain is kept (delayed, not falsified)" \
        "g5r3-planted: with 1 extra clean dissenting host(s) the chain is kept (delayed, not falsified)" \
        "g5r3-planted: with 2 extra clean dissenting host(s) the chain is kept (delayed, not falsified)"

    echo "--- GREEN ARM: every check must pass, one MISMATCH line per mismatching reply ---"
    "$BUILD_DIR/green${bits}" > "$BUILD_DIR/green${bits}.log" 2>&1; rc=$?
    grep -E "^(PASS|FAIL|test_|cf_corroboration_kat)" "$BUILD_DIR/green${bits}.log" | sed 's/^/  /'
    if [ "$rc" -ne 0 ]; then
        echo "  [$bits green] FAILED (rc=$rc)"; FAIL=1
    fi
    if grep -Eq "$SAN_RE" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] GATE FAILURE: sanitizer report"; grep -E "$SAN_RE" "$BUILD_DIR/green${bits}.log" | head -3; FAIL=1
    fi
    local mm; mm="$(grep -c 'cf-corroborate: height [0-9]* MISMATCH' "$BUILD_DIR/green${bits}.log")"
    # disagree + bounded (2), majority (2: the second triggers the rebuild), split (2) -- 4.1.3 G5 added four;
    # G5-QUORUM: silent (2), source-dup (2), lone (4: source, other twice, third), agreers (2), departed (2), one-host (3);
    # review round 2: jam (8+2, then 16+2), ports (2), netgroup (2+2), evict (1), state (2+2), late (2);
    # review round 3: colocate (2+2), planted (2, 3, 4, 5)
    if [ "$mm" -eq 80 ]; then
        echo "  [$bits green] exactly one MISMATCH log line per mismatching reply (80 replies, $mm lines)"
    else
        echo "  [$bits green] GATE FAILURE: expected 80 MISMATCH log lines (one per mismatching reply), saw $mm"; FAIL=1
    fi
    if grep -q "calling getcfcheckpt" "$BUILD_DIR/green${bits}.log"; then
        echo "  [$bits green] the real sender was reached (request logged)"
    else
        echo "  [$bits green] GATE FAILURE: the real getcfcheckpt sender never logged a request"; FAIL=1
    fi
}

run_bits 64
run_bits 32

# Optional: keep the raw per-arm logs (KAT_KEEP_LOGS=<dir>) for an evidence record.
if [ -n "${KAT_KEEP_LOGS:-}" ]; then
    mkdir -p "$KAT_KEEP_LOGS" && cp "$BUILD_DIR"/red64.log "$BUILD_DIR"/green64.log "$BUILD_DIR"/red32.log "$BUILD_DIR"/green32.log "$KAT_KEEP_LOGS"/ 2>/dev/null || true
fi

echo
if [ "$FAIL" -ne 0 ]; then
    echo "cf_corroboration_kat: FAILED"
    exit 1
fi
echo "cf_corroboration_kat: PASS (64- and 32-bit; red arm refused, green arm clean)"
