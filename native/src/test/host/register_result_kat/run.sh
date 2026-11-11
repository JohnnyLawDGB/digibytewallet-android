#!/usr/bin/env bash
# Host KAT runner REGISTER-RESULT: every tx-register entry point tells its caller whether the wallet now
# holds the tx (KEPT / KEPT_FOREIGN: the wallet owns it; REFUSED / DUPLICATE: the caller still does),
# and no production caller decides ownership any other way.
#
# ARMS. Shipped: no -D. Comparison: -DREGISTER_RESULT_UNFIXED (the earlier shape: the int return value
# was the only answer, so "held" is read from it). Each arm runs in a 64-bit and a 32-bit build, every
# run under a time limit, with AddressSanitizer and LeakSanitizer on (see the _main.c header).
#
# EXPECTED. Shipped: every case passes, sanitizer-clean. Comparison: every case in which the wallet keeps
# a non-wallet record (foreign, bound, same, promote, held-refused, unproven-bound) ends in an
# AddressSanitizer report (the caller released an object the wallet holds, as the int answered 0);
# duplicate ends in a LeakSanitizer report (a copy with no owner, as the int answered 1); kept,
# confirmed, range, sig-refused and legacy pass. Mutation: sig-refused leaks; range and held-refused pass.
#
# SOURCE GATE. No production source calls the three int forms (BRWalletRegisterTransaction,
# ...Trusted, ...Unproven), whose return value does not say who owns tx: every production registration
# goes through BRWalletRegisterTransactionOutcome. Scanned: every .c/.h in the core except test.c and
# the *Tests.c suites (in BRWallet.c and BRWallet.h only the three definitions and declarations may
# name them), and every .c/.h in
# the JNI bridge. Comments are dropped before the scan. The gate is first shown to refuse a fixture
# that calls an int form. REGISTER_GATE_ROOT=<jni dir> points it at another tree (to show it refuses
# the earlier sources).
#
# OUTCOME GATE. Every production call of BRWalletRegisterTransactionOutcome passes &<a variable> as its
# outcome (never NULL or 0) and reads that variable again, in the same function, through
# BRWalletRegisterOutcomeHeld(<it>) or _BRPeerManagerOwnershipFollowsObject(..., <it>) (which reads it
# with BRWalletRegisterOutcomeHeld): a call that drops the outcome and releases by the int is the
# earlier hazard again. Same files and comment stripping as above. Shown first to refuse two fixtures:
# a NULL outcome with a release by the int, and an outcome taken and never read.
#
# MUTATION ARM. sig-refused holds in the comparison arm too (the int answered 0 there, which was right),
# so it is shown load-bearing on a mutation of the shipped BRWallet.c that reports a signature-refused
# fresh object as KEPT: sig-refused must then end in a LeakSanitizer report, and range, which takes the
# same refusal branch for a different reason, must still pass.
#
# BRWallet.c is #included by the main, so it is NOT on the clang line.
# Exit code 0 = every expectation held.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
GATE_ROOT="${REGISTER_GATE_ROOT:-$REPO_ROOT/native/src/main/jni}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

SAN_RE='ERROR: AddressSanitizer|runtime error:'
LIMIT_S=180
FAILS=0

if ! grep -q 'REGISTER_RESULT_UNFIXED' "$CORE_DIR/BRWallet.c"; then
    echo "GATE FAILURE: the REGISTER_RESULT_UNFIXED comparison seam is not in BRWallet.c"; exit 1
fi

# ------------------------------------------------------------------ source gate ----
strip_comments() { perl -0777 -pe 's{/\*.*?\*/}{ }gs; s{//[^\n]*}{}g' "$1"; }
LEGACY='\<BRWalletRegisterTransaction(Trusted|Unproven)?[[:space:]]*\('

# $1 = a jni dir holding digibytewallet-core/ and bridge/. Prints one line per offence; returns 1 if any.
scan_tree() {
    local root="$1" bad=0 f n base
    local files=()
    shopt -s nullglob
    files=("$root"/digibytewallet-core/*.c "$root"/digibytewallet-core/*.h "$root"/bridge/*.c "$root"/bridge/*.h)
    shopt -u nullglob
    if [ "${#files[@]}" -lt 5 ]; then echo "    scanner is blind: no sources under $root"; return 1; fi
    for f in "${files[@]}"; do
        base="$(basename "$f")"
        case "$base" in test.c|*Tests.c) continue ;; esac
        n="$(strip_comments "$f" | grep -Eo -- "$LEGACY" | wc -l)"
        if [ "$base" = "BRWallet.c" ] || [ "$base" = "BRWallet.h" ]; then
            # The three definitions (declarations, in the header), and nothing else.
            local defs
            defs="$(strip_comments "$f" | grep -Ec -- "^int[[:space:]]+BRWalletRegisterTransaction(Trusted|Unproven)?[[:space:]]*\(BRWallet")"
            if [ "$defs" -ne 3 ] || [ "$n" -ne 3 ]; then
                echo "    $base: names an int register form $n time(s), $defs of them definitions (want 3 and 3)"; bad=1
            fi
        elif [ "$n" -ne 0 ]; then
            echo "    ${f#$root/}: calls an int register form $n time(s) -- its return value does not say who owns tx"
            strip_comments "$f" | grep -nE -- "$LEGACY" | sed -n '1,4s/^/      | /p'
            bad=1
        fi
    done
    return $bad
}

echo "=== source gate: no production caller uses an int register form ==="
FIX="$BUILD_DIR/fixture"
mkdir -p "$FIX/digibytewallet-core" "$FIX/bridge"
cp "$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRWallet.h "$CORE_DIR"/BRPeerManager.c "$CORE_DIR"/BRWalletSend.h "$FIX/digibytewallet-core/"
cp "$REPO_ROOT"/native/src/main/jni/bridge/*.c "$FIX/bridge/"
cat > "$FIX/bridge/zz_fixture.c" <<'FIXTURE'
/* BRWalletRegisterTransaction(w, tx) in a comment is not a call */
static void f(BRWallet *w, BRTransaction *tx) { if (!BRWalletRegisterTransactionTrusted (w, tx)) BRTransactionFree(tx); }
FIXTURE
if scan_tree "$FIX" > "$BUILD_DIR/fixture.out"; then
    echo "  FAIL the gate passed a fixture that calls an int register form -- it is blind"; cat "$BUILD_DIR/fixture.out"; FAILS=$((FAILS + 1))
elif [ "$(grep -c 'calls an int register form' "$BUILD_DIR/fixture.out")" -ne 1 ] || ! grep -q 'zz_fixture.c: calls an int register form 1 time' "$BUILD_DIR/fixture.out"; then
    echo "  FAIL the gate refused the fixture for the wrong reason:"; cat "$BUILD_DIR/fixture.out"; FAILS=$((FAILS + 1))
else
    echo "  ok   the gate refuses a fixture that calls an int register form (and only that file)"
fi
if scan_tree "$GATE_ROOT" > "$BUILD_DIR/tree.out"; then
    echo "  ok   no production source under ${GATE_ROOT#$REPO_ROOT/} calls an int register form"
else
    echo "  FAIL production sources call an int register form:"; cat "$BUILD_DIR/tree.out"; FAILS=$((FAILS + 1))
fi

# ------------------------------------------------------------------ outcome gate ----
# $1 = a source file. Prints one line per call that does not pass &var and read var by the outcome.
outcome_offences() {
    strip_comments "$1" | OG_BASE="$(basename "$1")" perl -0777 -ne '
        my $src = $_; my $base = $ENV{OG_BASE};
        while ($src =~ /\bBRWalletRegisterTransactionOutcome\s*\(/g) {
            my $start = $-[0]; my $open = $+[0];
            my $lineStart = rindex($src, "\n", $start) + 1;
            next if substr($src, $lineStart, $start - $lineStart) =~ /^int\s+$/;   # the definition / declaration
            my $depth = 1; my $i = $open; my @args = (); my $cur = "";
            while ($i < length($src) && $depth > 0) {
                my $ch = substr($src, $i, 1);
                if ($ch eq "(") { $depth++; } elsif ($ch eq ")") { $depth--; last if $depth == 0; }
                if ($ch eq "," && $depth == 1) { push @args, $cur; $cur = ""; } else { $cur .= $ch; }
                $i++;
            }
            push @args, $cur;
            my $line = 1 + (substr($src, 0, $start) =~ tr/\n//);
            if (@args != 4 || $args[3] !~ /^\s*&\s*(\w+)\s*$/) {
                my $a = defined $args[3] ? $args[3] : "(none)"; $a =~ s/^\s+|\s+$//g;
                print "    $base:$line: outcome argument is \"$a\", not &<variable> -- the caller cannot know who owns tx\n";
                next;
            }
            my $v = $1;
            my $rest = substr($src, $i);
            $rest = $1 if $rest =~ /\A(.*?)\n\}/s;   # to the end of the enclosing function
            unless ($rest =~ /\bBRWalletRegisterOutcomeHeld\s*\(\s*\Q$v\E\s*\)/ ||
                    $rest =~ /\b_BRPeerManagerOwnershipFollowsObject\s*\([^;]*?\b\Q$v\E\s*\)/) {
                print "    $base:$line: outcome \"$v\" is never read by BRWalletRegisterOutcomeHeld -- ownership is decided some other way\n";
            }
        }'
}

# $1 = a jni dir. Returns 1 if any call offends, and fails closed if it finds no call at all.
scan_outcome_tree() {
    local root="$1" bad=0 calls=0 f base out
    shopt -s nullglob
    local files=("$root"/digibytewallet-core/*.c "$root"/digibytewallet-core/*.h "$root"/bridge/*.c "$root"/bridge/*.h)
    shopt -u nullglob
    for f in "${files[@]}"; do
        base="$(basename "$f")"
        case "$base" in test.c|*Tests.c) continue ;; esac
        calls=$((calls + $(strip_comments "$f" | grep -Ec -- '\<BRWalletRegisterTransactionOutcome[[:space:]]*\(' )))
        out="$(outcome_offences "$f" | sed "s#^    $base:#    ${f#$root/}:#")"
        if [ -n "$out" ]; then echo "$out"; bad=1; fi
    done
    if [ "$calls" -lt 3 ]; then echo "    scanner is blind: $calls call(s) of BRWalletRegisterTransactionOutcome under $root"; return 1; fi
    return $bad
}

echo "=== outcome gate: every production call passes &outcome and reads it ==="
FIX2="$BUILD_DIR/fixture2"
mkdir -p "$FIX2/digibytewallet-core" "$FIX2/bridge"
cp "$CORE_DIR"/BRWallet.c "$CORE_DIR"/BRWallet.h "$CORE_DIR"/BRPeerManager.c "$CORE_DIR"/BRWalletSend.h "$FIX2/digibytewallet-core/"
cp "$REPO_ROOT"/native/src/main/jni/bridge/*.c "$FIX2/bridge/"
cat > "$FIX2/bridge/zz_null.c" <<'FIXTURE'
static void f(BRWallet *w, BRTransaction *tx)
{
    if (!BRWalletRegisterTransactionOutcome(w, tx, BR_WALLET_REGISTER_CHECKED, NULL)) BRTransactionFree(tx);
}
FIXTURE
cat > "$FIX2/bridge/zz_unread.c" <<'FIXTURE'
static void g(BRWallet *w, BRTransaction *tx)
{
    BRWalletRegisterOutcome held = BR_WALLET_REGISTER_REFUSED;
    if (!BRWalletRegisterTransactionOutcome(w, tx, BR_WALLET_REGISTER_CHECKED, &held)) BRTransactionFree(tx);
}
static void h(BRWallet *w, BRTransaction *tx, BRWalletRegisterOutcome held)
{
    if (BRWalletRegisterOutcomeHeld(held)) return;   /* another function's read does not count */
}
FIXTURE
scan_outcome_tree "$FIX2" > "$BUILD_DIR/fixture2.out"
if [ "$(grep -c '' "$BUILD_DIR/fixture2.out")" -ne 2 ] ||
   ! grep -q 'bridge/zz_null.c:3: outcome argument is "NULL"' "$BUILD_DIR/fixture2.out" ||
   ! grep -q 'bridge/zz_unread.c:4: outcome "held" is never read' "$BUILD_DIR/fixture2.out"; then
    echo "  FAIL the outcome gate did not refuse exactly the two fixtures:"; cat "$BUILD_DIR/fixture2.out"; FAILS=$((FAILS + 1))
else
    echo "  ok   the outcome gate refuses a NULL outcome and an unread outcome (and nothing else)"
fi
if scan_outcome_tree "$GATE_ROOT" > "$BUILD_DIR/tree2.out"; then
    echo "  ok   every production call under ${GATE_ROOT#$REPO_ROOT/} passes &outcome and reads it"
else
    echo "  FAIL production calls drop or ignore the outcome:"; cat "$BUILD_DIR/tree2.out"; FAILS=$((FAILS + 1))
fi

# ------------------------------------------------------------------ arms ----
bounded() {
    local outfile="$1"; shift
    local stopped="$outfile.stopped"
    rm -f "$stopped"
    "$@" >"$outfile" 2>&1 &
    local pid=$!
    ( trap - EXIT; sleep "$LIMIT_S"; : >"$stopped"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; local rc=$?
    kill -9 "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    [ -e "$stopped" ] && return 124
    return $rc
}

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

# MUT_DIR, when set, holds a mutated BRWallet.c that the main's #include finds before the core's.
build() {
    local out="$1"; local bits="$2"; shift 2
    local m=() mi=()
    [ "$bits" = "32" ] && m=(-m32)
    [ -n "${MUT_DIR:-}" ] && mi=(-I "$MUT_DIR")
    clang -w -include stdint.h -g -O1 -fsanitize=address -fno-omit-frame-pointer \
        "${m[@]}" "$@" \
        "${mi[@]}" \
        -I "$CORE_DIR" \
        -I "$CORE_DIR/secp256k1/include" \
        "$SCRIPT_DIR/register_result_kat_main.c" \
        "$CORE_DIR/BRTransaction.c" \
        "$CORE_DIR/BRDigiDollar.c" \
        "$CORE_DIR/BRDigiAsset.c" \
        "$CORE_DIR/BRKey.c" \
        "$CORE_DIR/BRNetwork.c" \
        "$CORE_DIR/BRAddress.c" \
        "$CORE_DIR/BRSet.c" \
        "$CORE_DIR/BRBase58.c" \
        "$CORE_DIR/BRBech32.c" \
        "$CORE_DIR/BRCrypto.c" \
        "$CORE_DIR/BRBIP32Sequence.c" \
        "$CORE_DIR/BRBIP39Mnemonic.c" \
        "$CORE_DIR/crypto/groestl.c" "$CORE_DIR/crypto/skein.c" \
        "$CORE_DIR/crypto/qubit.c" "$CORE_DIR/crypto/odocrypt.c" \
        "${SHA3_SRCS[@]}" \
        -lpthread -lm \
        -o "$out"
}

# expect <label> <binary> <case> <want: pass|fail|san|leak>
expect() {
    local label="$1" bin="$2" kase="$3" want="$4" out="$BUILD_DIR/$1.$3.out" rc got
    LSAN_OPTIONS="detect_leaks=1" ASAN_OPTIONS="abort_on_error=1 detect_leaks=1 symbolize=0" bounded "$out" "$bin" "$kase"; rc=$?
    if grep -Eq "$SAN_RE" "$out"; then got=san
    elif grep -q "ERROR: LeakSanitizer" "$out"; then got=leak
    elif [ "$rc" -eq 124 ]; then got=timeout
    elif [ "$rc" -eq 0 ] && grep -q "^CASE PASS $kase" "$out"; then got=pass
    elif [ "$rc" -eq 1 ] && grep -q "^CASE FAIL $kase" "$out"; then got=fail
    elif [ "$rc" -ge 128 ]; then got="signal $((rc - 128)) before the limit, not the watchdog (rc=$rc)"
    else got="other(rc=$rc)"
    fi
    if [ "$got" = "$want" ]; then
        echo "  ok   $label $kase: $got"
    else
        echo "  FAIL $label $kase: wanted $want, got $got"; cat "$out"; FAILS=$((FAILS + 1))
    fi
    grep -E "outcome=|FAIL |ERROR: |SUMMARY: " "$out" | sed -n "1,6s/^/       | /p"
}

# The mutation: a signature-refused fresh object reported as KEPT (money-range refusals untouched).
MUT="$BUILD_DIR/mut"
mkdir -p "$MUT"
perl -0777 -pe '
    $n += s{(int wasAdded = 0, r = 1;)}{$1 int mutSig = 0;};
    $n += s{(\(checkInputs && ! _BRWalletSpendsSigned\(wallet, tx, checkInputs == 2\)\)\) \{\n\s*r = 0;)}{$1 if (_BRWalletTxMoneyRangeOK(tx)) mutSig = 1;};
    $n += s{(\n\s*if \(outcome\) \*outcome = held;)}{\n    if (mutSig) held = BR_WALLET_REGISTER_KEPT;$1};
    END { print STDERR "mutations=$n\n" }' "$CORE_DIR/BRWallet.c" > "$MUT/BRWallet.c" 2> "$BUILD_DIR/mut.log"
if ! grep -q '^mutations=3$' "$BUILD_DIR/mut.log"; then
    echo "GATE FAILURE: the sig-refused mutation did not apply ($(cat "$BUILD_DIR/mut.log")) -- BRWallet.c changed shape"; exit 1
fi

CASES="kept foreign bound confirmed range duplicate same promote held-refused sig-refused unproven-bound legacy"
for bits in 64 32; do
    echo "=== $bits-bit ==="
    build "$BUILD_DIR/red$bits" "$bits" -DREGISTER_RESULT_UNFIXED || { echo "FAIL: build error (comparison, $bits)"; exit 1; }
    build "$BUILD_DIR/green$bits" "$bits" || { echo "FAIL: build error (shipped, $bits)"; exit 1; }
    MUT_DIR="$MUT" build "$BUILD_DIR/mut$bits" "$bits" || { echo "FAIL: build error (mutation, $bits)"; exit 1; }
    echo "--- comparison arm (-DREGISTER_RESULT_UNFIXED) ---"
    for c in kept confirmed range sig-refused legacy; do expect "red$bits" "$BUILD_DIR/red$bits" "$c" pass; done
    for c in foreign bound same promote held-refused unproven-bound; do expect "red$bits" "$BUILD_DIR/red$bits" "$c" san; done
    expect "red$bits" "$BUILD_DIR/red$bits" duplicate leak
    echo "--- mutation arm (a signature-refused object reported KEPT) ---"
    expect "mut$bits" "$BUILD_DIR/mut$bits" sig-refused leak
    for c in range held-refused; do expect "mut$bits" "$BUILD_DIR/mut$bits" "$c" pass; done
    echo "--- shipped arm ---"
    for c in $CASES; do expect "green$bits" "$BUILD_DIR/green$bits" "$c" pass; done
done

if [ "$FAILS" -eq 0 ]; then echo "register_result_kat: ALL PASS"; exit 0; fi
echo "register_result_kat: $FAILS expectation(s) failed"; exit 1
