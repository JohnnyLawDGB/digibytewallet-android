#!/usr/bin/env bash
# scripts/test-helpers/self-test.sh  (run as: ./scripts/pre-publish-test.sh --self-test)
#
# The suite's own red arm. No emulator, no adb server: the assertion helpers are fed canned
# UI dumps (self-test-dumps/, captured from the app and redacted) and a stub `adb`.
#
# Every helper is checked in BOTH directions: true on the screen it names, and false on a
# screen where the scenario's subject has failed. A helper that cannot say "no" would let its
# scenario pass on any screen, which is what the suite this replaced did.

set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$HERE/../.." && pwd)"
DUMPS="$HERE/self-test-dumps"
WORK="$(mktemp -d -t prepub-selftest-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

SERIAL="emulator-5554"
PACKAGE="app.aroundtheblock.wallet.debug"
SUITE_LOG="$WORK/suite.log"
# The self-test runs under an OPEN umask on purpose: the helpers that must keep a file private
# have to do so themselves, not lean on the caller's umask.
umask 022
source "$HERE/device.sh"
source "$HERE/emulator-setup.sh"
source "$HERE/report.sh"
UI_DUMP_DIR="$(ui_dump_dir_create)" || { echo "cannot create the UI dump directory"; exit 1; }
UI_DUMP_FILE="$UI_DUMP_DIR/ui.xml"
trap 'rm -rf "$WORK" "$UI_DUMP_DIR"' EXIT
source "$HERE/test-scenarios.sh"

CHECKS=0; FAILED=0
ok()  { CHECKS=$((CHECKS + 1)); printf '  ok    %s\n' "$1"; }
bad() { CHECKS=$((CHECKS + 1)); FAILED=$((FAILED + 1)); printf '  WRONG %s\n' "$1"; }

use() { cp "$DUMPS/$1.xml" "$UI_DUMP_FILE"; }
# variant <dump> <sed expression> — the same screen with one thing changed.
variant() { sed "$2" "$DUMPS/$1.xml" > "$UI_DUMP_FILE"; }

# yes <description> <helper...>: the helper must succeed.  no: it must fail.
yes() { local d="$1"; shift; if "$@" > /dev/null 2>&1; then ok "$d"; else bad "$d (expected true)"; fi; }
no()  { local d="$1"; shift; if "$@" > /dev/null 2>&1; then bad "$d (expected FAIL, the helper accepted it)"; else ok "$d"; fi; }
prints() { local d="$1" want="$2" got; shift 2; got="$("$@" 2>/dev/null)"; if [[ "$got" == "$want" ]]; then ok "$d"; else bad "$d (got '$got', want '$want')"; fi; }

echo "=== Pre-publish suite self-test (no emulator) ==="

echo "A: first onboarding screen"
use onboarding;   yes "onboarding screen is recognised" screen_is_onboarding
use unlock;       no  "unlock screen is not onboarding" screen_is_onboarding
use wallet-home;  no  "wallet screen is not onboarding" screen_is_onboarding
variant onboarding 's/Create New Wallet/Create Wallet/'
                  no  "onboarding without its 'Create New Wallet' button fails" screen_is_onboarding

echo "A/B: the Security screen's 'Wallet key' line"
use security-tied;     yes "tied key reads as bound" screen_wallet_key_bound
use security-not-tied; no  "untied key does NOT read as bound" screen_wallet_key_bound
use security-not-tied; yes "no-lock key reads as no-lock" screen_wallet_key_no_lock
use security-tied;     no  "tied key does NOT read as no-lock" screen_wallet_key_no_lock
use wallet-home;       no  "a screen with no 'Wallet key' line fails" screen_wallet_key_bound
variant security-tied 's/Tied to this phone’s screen lock/Not tied yet: it will be tied to your screen lock the next time the app starts and opens the wallet/'
                       no  "'Not tied yet' does NOT read as bound" screen_wallet_key_bound

echo "C: after an upgrade"
use unlock;      yes "unlock screen is recognised" screen_is_unlock
use onboarding;  no  "onboarding (the wallet is gone) is not the unlock screen" screen_is_unlock

echo "D: wallet screen and receive address"
use wallet-home;          yes "wallet screen is recognised" screen_is_wallet
use wallet-home-syncing;  yes "wallet screen is recognised while connected" screen_is_wallet
use unlock;               no  "unlock screen is not the wallet screen" screen_is_wallet
use receive;              no  "Receive screen is not the wallet screen" screen_is_wallet
use send-refused-insufficient; no "the Send failure sheet is not the wallet screen" screen_is_wallet
use receive;              yes "Receive screen yields an address" screen_receive_address
use wallet-home;          no  "wallet screen yields no receive address" screen_receive_address
ADDR="$(use receive; screen_receive_address)"
variant receive "s/$ADDR/Address unavailable/"
                          no  "'Address unavailable' is not an address" screen_receive_address
variant receive "s/$ADDR/DsvYFDAyGDMbhs7ZVtvFSHwmRGAoyZLYPo/"
                          no  "a non-bech32 string is not accepted as the SegWit address" screen_receive_address
use verify-ok;            yes "verification success is recognised" screen_verify_passed
use verify-retry;         no  "'Let's try again' is not a verification success" screen_verify_passed

echo "E: restored address comparison"
EXPECTED="$(expected_restore_address)"
if [[ "$EXPECTED" =~ ^dgb1q[a-z0-9]{38}$ ]]; then ok "expected address is read from the committed KAT ($EXPECTED)"; else bad "no pinned address in $ADDRESS_VECTOR_FILE"; fi
variant receive "s/$ADDR/$EXPECTED/"
prints "the pinned address on screen compares equal" "$EXPECTED" screen_receive_address
use receive
if [[ "$(screen_receive_address)" != "$EXPECTED" ]]; then ok "another wallet's address does NOT equal the pinned one"; else bad "a different address compared equal"; fi

echo "F: peers and block height"
use wallet-home-syncing
prints "peer count is read from the status line" "8" screen_peer_count
prints "block height is read from the status line" "24602838" screen_block_height
variant wallet-home-syncing 's/Connected · 8 peers · Block 24,602,838[^"]*/Connecting…/'
no "a status line with no peers yields no peer count" screen_peer_count
no "a status line with no block yields no height" screen_block_height
variant wallet-home-syncing 's/Connected · 8 peers · Block 24,602,838[^"]*/Disconnected · 0 peers/'
prints "0 peers is read as 0, which F fails on" "0" screen_peer_count
use unlock; no "the lock screen yields no peer count" screen_peer_count

echo "G: the insufficient-funds refusal"
use send-refused-insufficient; yes "the insufficient-funds refusal is recognised" screen_send_refused_insufficient
use wallet-home;               no  "the wallet screen is not a refusal" screen_send_refused_insufficient
variant send-refused-insufficient 's/Your balance has to cover the network fee as well as the amount[^"]*/Your wallet could not reach any peers to publish this. Wait a moment and try again./'
                               no  "a refusal for another reason (broadcast) is NOT the insufficient-funds refusal" screen_send_refused_insufficient
variant send-refused-insufficient 's/Transaction not sent/Confirm Transaction/'
                               no  "a confirmation dialog is NOT a refusal" screen_send_refused_insufficient

echo "H: after the soak"
use wallet-home; yes "wallet screen passes the end-of-soak check" screen_is_wallet
use onboarding
if screen_is_wallet || screen_is_unlock; then bad "onboarding passed the end-of-soak check"; else ok "onboarding (a wiped wallet) fails the end-of-soak check"; fi

echo "Finding controls by text"
use onboarding
yes "a control on screen has a centre" ui_center "Recover Existing Wallet"
no  "a control not on screen has none" ui_center "Send"
no  "exact match does not accept a substring" ui_center "Recover"
yes "--contains accepts a substring" ui_center --contains "Recover"
variant onboarding 's/\(text="Create New Wallet"[^>]*bounds="\)[^"]*"/\1[0,0][0,0]"/'
no  "a zero-sized (off-screen) control cannot be pressed" ui_center "Create New Wallet"
: > "$UI_DUMP_FILE"
no  "an empty dump finds nothing" ui_has "Secure your DGB"

echo "Recovery phrase: read, and never written"
SEED_XML="$WORK/seed.xml"
{
    echo '<?xml version="1.0"?><hierarchy rotation="0"><node text="Your Recovery Phrase" class="android.widget.TextView" content-desc="" bounds="[0,0][100,50]">'
    i=0
    for wd in $TEST_MNEMONIC; do
        i=$((i + 1))
        echo "<node text=\"$i\" class=\"android.widget.TextView\" content-desc=\"\" bounds=\"[0,$((i * 60))][50,$((i * 60 + 20))]\"/><node text=\"$wd\" class=\"android.widget.TextView\" content-desc=\"\" bounds=\"[0,$((i * 60 + 20))][50,$((i * 60 + 50))]\"/>"
    done
    echo '<node text="about" class="android.widget.EditText" content-desc="" password="true" bounds="[0,900][100,950]"/>'
    echo '</node></hierarchy>'
} > "$SEED_XML"
prints "12 numbered words are read in order" "$TEST_MNEMONIC" python3 "$UIDUMP" seed-words "$SEED_XML"
sed 's/<node text="12"[^>]*\/><node text="about"[^>]*\/>//' "$SEED_XML" > "$WORK/seed11.xml"
no "11 words is not a phrase" python3 "$UIDUMP" seed-words "$WORK/seed11.xml"
no "a screen with no phrase yields none" python3 "$UIDUMP" seed-words "$DUMPS/wallet-home.xml"
python3 "$UIDUMP" redact "$SEED_XML" "$WORK/seed-redacted.xml"
if grep -q -E 'abandon|about' "$WORK/seed-redacted.xml"; then bad "a redacted dump still holds phrase words"; else ok "a redacted dump holds no phrase word (grid cells and the text field)"; fi
if grep -q 'Your Recovery Phrase' "$WORK/seed-redacted.xml"; then ok "redaction keeps the screen's other text"; else bad "redaction removed ordinary text"; fi
OUT="$(python3 "$UIDUMP" redact-log 3 3< <(printf '%s' "zebra quantum ladder") <<< "D Foo: saved zebra and quantum, not zebras")"
if [[ "$OUT" == "D Foo: saved [redacted] and [redacted], not zebras" ]]; then ok "saved logcat has the run's phrase words removed (words passed on a pipe)"; else bad "logcat redaction: '$OUT'"; fi
PW="zebra quantum ladder mango"
pil() { python3 "$UIDUMP" phrase-in-log 3 3< <(printf '%s' "$PW"); }
P='11-21 10:00:00.000  100  200 I Tag     : '
yes "a phrase logged as plain words is found" pil <<< "${P}words: zebra quantum ladder mango"
yes "a phrase logged as a list is found" pil <<< "${P}[zebra, quantum, ladder]"
yes "a phrase logged with quotes and other case is found" pil <<< "${P}\"Quantum\",\"LADDER\",\"mango\""
yes "a phrase logged one word per log call is found" pil <<< "${P}zebra"$'\n'"${P}quantum"$'\n'"${P}ladder"
no "two phrase words in a row are not a phrase" pil <<< "${P}zebra quantum and then a mango ladder"
no "the same words out of order are not a phrase" pil <<< "${P}ladder quantum zebra"
no "a log with none of the words is clean" pil <<< "${P}Connected to 8 peers"
OUT="$(GENERATED_WORDS="zebra quantum" python3 "$UIDUMP" redact-log GENERATED_WORDS <<< "x" 2>/dev/null)"; rc=$?
if [[ $rc -ne 0 ]]; then ok "redact-log no longer reads the words from the environment"; else bad "redact-log accepted an environment variable name"; fi
GENERATED_WORDS="zebra quantum ladder"
if env | grep -q '^GENERATED_WORDS='; then bad "the phrase variable is exported: every child process has it in its environment"; else ok "the phrase variable is not in the environment of child processes"; fi
GENERATED_WORDS=""
if grep -n -E '<<< *"?\$\{?GENERATED_WORDS' "$HERE"/*.sh | grep -v -F 'self-test.sh' | grep -q .; then bad "a helper feeds the phrase through a here-string (bash may back it with a temp file)"; else ok "no helper feeds the phrase through a here-string"; fi
for f in "$DUMPS"/*.xml; do
    if python3 "$UIDUMP" texts "$f" | grep -q -x -E '[a-z]{3,8}'; then bad "committed dump $(basename "$f") holds a bare lowercase word"; fi
done
ok "no committed dump holds a phrase-shaped word"

echo "Devices: emulators only, every call bounded"
yes "emulator-5554 is accepted" require_emulator_serial emulator-5554
no  "a phone's serial is refused" require_emulator_serial R58M12ABCDE
no  "an empty serial is refused" require_emulator_serial ""
no  "a host:port serial is refused" require_emulator_serial 192.168.1.20:5555
# A stub adb: records its arguments; `install` and `shell hang` never return in time.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/adb" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/adb-calls"
case "\$*" in
    *install*|*hang*) sleep 30 ;;
    # The screen as a sequence: dump 1 is seq/1.xml, dump 2 is seq/2.xml ...; the last repeats.
    *uiautomator*)
        n=\$(( \$(cat "$WORK/seq-n" 2>/dev/null || echo 0) + 1 )); echo \$n > "$WORK/seq-n"
        [[ -f "$WORK/seq/\$n.xml" ]] || n=\$(ls "$WORK/seq" | wc -l)
        cat "$WORK/seq/\$n.xml" ;;
    *) echo stub ;;
esac
EOF
# screens <dump file>... — what the stub device shows, one file per successive dump.
screens() { rm -rf "$WORK/seq" "$WORK/seq-n"; mkdir -p "$WORK/seq"; local i=0 f; for f in "$@"; do i=$((i + 1)); cp "$f" "$WORK/seq/$i.xml"; done; }
chmod +x "$WORK/bin/adb"
OLD_PATH="$PATH"; PATH="$WORK/bin:$PATH"
: > "$WORK/adb-calls"
SERIAL="R58M12ABCDE" adbx shell echo hi > /dev/null 2>&1; rc=$?
if [[ $rc -eq 125 && ! -s "$WORK/adb-calls" ]]; then ok "adbx sends NOTHING to a non-emulator serial"; else bad "adbx reached adb for a phone serial (rc=$rc)"; fi
adbx shell echo hi > /dev/null 2>&1
if grep -q -x -- "-s emulator-5554 shell echo hi" "$WORK/adb-calls"; then ok "adbx always passes -s <serial>"; else bad "adbx did not pass -s: $(cat "$WORK/adb-calls")"; fi
t0=$(date +%s); ADB_TIMEOUT=2 adbx shell hang > /dev/null 2>&1; rc=$?; dt=$(( $(date +%s) - t0 ))
if [[ $rc -eq 124 && $dt -lt 10 ]]; then ok "a call that does not return is cut off (status 124 after ${dt}s)"; else bad "a hung adb call was not bounded (rc=$rc after ${dt}s)"; fi
: > "$WORK/adb-calls"
t0=$(date +%s); ADB_INSTALL_TIMEOUT=2 adb_install /nonexistent.apk > /dev/null 2>&1; rc=$?; dt=$(( $(date +%s) - t0 ))
n="$(grep -c install "$WORK/adb-calls")"
if [[ $rc -ne 0 && $n -eq 2 && $dt -lt 20 ]]; then ok "a hung install fails after exactly one retry (${dt}s), it does not hang"; else bad "install: rc=$rc attempts=$n after ${dt}s"; fi

echo "The raw UI dump is private (it can hold a recovery phrase)"
screens "$SEED_XML"
ui_dump
MODE="$(stat -c '%a' "$UI_DUMP_FILE")"
if [[ "$MODE" == "600" ]]; then ok "a dump written under umask 022 is mode 600"; else bad "the dump file is mode $MODE under umask 022: readable by others"; fi
MODE="$(stat -c '%a' "$UI_DUMP_DIR")"
if [[ "$MODE" == "700" ]]; then ok "the dump directory is mode 700"; else bad "the dump directory is mode $MODE"; fi
if [[ -d "${XDG_RUNTIME_DIR:-/nonexistent}" || -d /dev/shm ]]; then
    FS="$(stat -f -c '%T' "$UI_DUMP_DIR")"
    if [[ "$FS" == "tmpfs" || "$FS" == "ramfs" ]]; then ok "the dump directory is in memory ($FS), not on a disk"; else bad "the dump directory is on $FS although a tmpfs is available"; fi
fi
if python3 "$UIDUMP" seed-words "$UI_DUMP_FILE" > /dev/null; then ok "(the dump under test does hold a phrase)"; else bad "the seed dump did not reach the dump file"; fi
ui_dump_scrub
if [[ ! -s "$UI_DUMP_FILE" && "$(stat -c '%a' "$UI_DUMP_FILE")" == "600" ]]; then ok "ui_dump_scrub leaves the file empty and private"; else bad "ui_dump_scrub left $(wc -c < "$UI_DUMP_FILE") bytes"; fi
if ls "$UI_DUMP_DIR" | grep -q -v -x 'ui.xml'; then bad "ui_dump left another file behind: $(ls "$UI_DUMP_DIR" | tr '\n' ' ')"; else ok "ui_dump leaves no second copy (.tmp) behind"; fi
( UI_DUMP_FILE=""; ui_dump ) > /dev/null 2>&1 && bad "ui_dump wrote somewhere with no dump file set" || ok "ui_dump refuses to run with no dump file set (there is no default location)"

echo "Waiting for a whole screen, not for its title"
HALF="$WORK/onboarding-half.xml"
sed 's/text="Create New Wallet"/text=""/; s/text="Recover Existing Wallet"/text=""/' "$DUMPS/onboarding.xml" > "$HALF"
cp "$HALF" "$UI_DUMP_FILE"
yes "(the half-drawn screen has the title)" ui_has "$T_ONB_TITLE"
no  "a screen with its title but not yet its buttons is not onboarding" screen_is_onboarding
screens "$HALF" "$HALF" "$DUMPS/onboarding.xml"
yes "ui_wait_for keeps polling past a half-drawn screen until the whole predicate holds" ui_wait_for 10 screen_is_onboarding
screens "$HALF"
t0=$(date +%s); ui_wait_for 3 screen_is_onboarding > /dev/null 2>&1; rc=$?; dt=$(( $(date +%s) - t0 ))
if [[ $rc -ne 0 && $dt -le 8 ]]; then ok "ui_wait_for FAILS at its deadline on a screen that never completes (${dt}s)"; else bad "ui_wait_for on a screen that never completes: rc=$rc after ${dt}s"; fi
screens "$DUMPS/unlock.xml"
no  "ui_wait_for fails on the wrong screen" ui_wait_for 2 screen_is_onboarding
if grep -n -E 'ui_dump; *screen_is_|ui_wait [0-9]+ "\$T_ONB_TITLE" *\|\| *! *screen_is' "$HERE/test-scenarios.sh" | grep -q .; then bad "a scenario still tests a screen on a single snapshot"; else ok "no scenario tests a whole screen on a single snapshot"; fi

echo "F and H: a wallet that cannot be reached is a failure, not replaced"
OUT="$(
    goto_wallet_home() { FLOW_ERROR="could not get back to the wallet screen"; return 1; }
    app_unhealthy() { return 1; }
    fresh_install() { echo REINSTALLED; }; launch_app() { :; }; create_wallet() { echo CREATED; WALLET_STATE=created; }
    WALLET_STATE=restored; APK_PATH=x
    ensure_wallet; echo "rc=$? state=$WALLET_STATE err=$FLOW_ERROR"
)"
if [[ "$OUT" == "rc=1 state=restored err=the restored wallet from the previous scenario could not be reached"* ]]; then ok "an unreachable restored wallet fails ensure_wallet and is left in place"; else bad "ensure_wallet replaced or accepted an unreachable wallet: $OUT"; fi
OUT="$(
    goto_wallet_home() { FLOW_ERROR="could not get back to the wallet screen"; return 1; }
    app_unhealthy() { HEALTH_REASON="crash: java.lang.IllegalStateException"; return 0; }
    fresh_install() { echo REINSTALLED; }; launch_app() { :; }; create_wallet() { echo CREATED; }
    WALLET_STATE=restored; APK_PATH=x
    ensure_wallet; echo "$FLOW_ERROR"
)"
if [[ "$OUT" == *"crash: java.lang.IllegalStateException"* && "$OUT" != *REINSTALLED* ]]; then ok "the failure names the crash that made the wallet unreachable"; else bad "ensure_wallet lost the crash: $OUT"; fi
OUT="$(
    goto_wallet_home() { echo SHOULD-NOT-BE-ASKED; return 1; }
    fresh_install() { echo REINSTALLED; }; launch_app() { :; }; create_wallet() { echo CREATED; WALLET_STATE=created; }
    WALLET_STATE=none; APK_PATH=x
    ensure_wallet; echo "rc=$?"
)"
if [[ "$OUT" == $'REINSTALLED\nCREATED\nrc=0' ]]; then ok "with no wallet yet (--test F / --test H) one is created"; else bad "ensure_wallet with no wallet: $OUT"; fi
OUT="$(
    goto_wallet_home() { return 0; }
    fresh_install() { echo REINSTALLED; }; create_wallet() { echo CREATED; }
    WALLET_STATE=restored; ensure_wallet; echo "rc=$?"
)"
if [[ "$OUT" == "rc=0" ]]; then ok "a reachable wallet is reused as it is"; else bad "ensure_wallet with a reachable wallet: $OUT"; fi

echo "kill_emulator is bounded"
( trap '' TERM; exec sleep 300 ) &
EMULATOR_PID=$!; STUBBORN=$EMULATOR_PID
( sleep 45; kill -9 "$STUBBORN" 2>/dev/null ) & WATCHDOG=$!
t0=$(date +%s); kill_emulator > /dev/null 2>&1; dt=$(( $(date +%s) - t0 ))
kill "$WATCHDOG" 2>/dev/null; wait "$WATCHDOG" 2>/dev/null
if [[ $dt -lt 30 ]] && ! kill -0 "$STUBBORN" 2>/dev/null; then ok "an emulator that ignores SIGTERM is killed and kill_emulator returns (${dt}s)"; else bad "kill_emulator took ${dt}s on a process that ignores SIGTERM (the watchdog fires at 45 s)"; fi
if [[ -z "$EMULATOR_PID" ]]; then ok "kill_emulator forgets the pid it killed"; else bad "EMULATOR_PID still set"; fi
PATH="$OLD_PATH"

echo "A SKIP is not a pass"
prints "all passed, none skipped: PASS" "PASS (8/8)" level_summary 8 8 0 "" ""
prints "a skipped scenario makes the level INCOMPLETE" "INCOMPLETE (7/8, 1 SKIP: C)" level_summary 7 8 0 "C" ""
prints "the only scenario skipped (--test C, no fixture) is INCOMPLETE, not PASS (0/1)" "INCOMPLETE (0/1, 1 SKIP: C)" level_summary 0 1 0 "C" ""
prints "a skip the caller accepted by name passes, and says so" "PASS (7/8, 1 SKIP accepted: C)" level_summary 7 8 0 "C" "C"
prints "accepting C does not accept F" "INCOMPLETE (5/8, 3 SKIP: C F G)" level_summary 5 8 0 "C F G" "C"
prints "PREPUB_ALLOW_SKIP takes commas" "PASS (5/8, 3 SKIP accepted: C F G)" level_summary 5 8 0 "C F G" "C,F,G"
prints "a failure is a FAIL whatever was skipped" "FAIL (1 failures)" level_summary 6 8 1 "C" "C"
prints "overall: all levels PASS" "PASS" overall_verdict "PASS (8/8)" "PASS (8/8)"
prints "overall: one INCOMPLETE level makes the run INCOMPLETE" "INCOMPLETE" overall_verdict "PASS (8/8)" "INCOMPLETE (7/8, 1 SKIP: C)"
prints "overall: accepted skips still pass" "PASS" overall_verdict "PASS (7/8, 1 SKIP accepted: C)"
prints "overall: a FAIL outranks INCOMPLETE" "FAIL" overall_verdict "INCOMPLETE (7/8, 1 SKIP: C)" "FAIL (1 failures)"
prints "overall: a level that did not boot is a FAIL" "FAIL" overall_verdict "PASS (8/8)" "BOOT FAILED"
prints "overall: a level that did not run is a FAIL" "FAIL" overall_verdict "NOT RUN"
prints "overall: no level at all is a FAIL" "FAIL" overall_verdict

echo "Run names"
RES="$WORK/results"
for i in 1 2 3 4 5 6; do ( claim_run_name "$RES" "2026-01-01-000000" > "$WORK/claim-$i" ) & done; wait
N="$(cat "$WORK"/claim-* | sort -u | wc -l)"; D="$(find "$RES" -mindepth 1 -maxdepth 1 -type d | wc -l)"
if [[ $N -eq 6 && $D -eq 6 ]]; then ok "six runs started in the same second get six different result names"; else bad "six runs in one second got $N names and $D directories"; fi
if grep -q -x "2026-01-01-000000" "$WORK"/claim-*; then ok "the first of them keeps the plain timestamp"; else bad "no run kept the plain timestamp"; fi

echo "Command line"
MAIN="$PROJECT_DIR/scripts/pre-publish-test.sh"
timeout 10 "$MAIN" --test > /dev/null 2>&1; rc=$?
if [[ $rc -eq 1 ]]; then ok "--test with no letter is refused"; else bad "--test with no letter: status $rc (124 = it hung)"; fi
# (The message is checked, not only the status: with no APK built the script exits 1 anyway.)
for arg in 29 33x 5554; do
    OUT="$(timeout 10 "$MAIN" "$arg" --skip-build 2>&1)"; rc=$?
    if [[ $rc -eq 1 && "$OUT" == "Unknown API level: $arg"* ]]; then ok "API level '$arg' is refused before anything is started"; else bad "API level '$arg': status $rc, said '${OUT%%$'\n'*}'"; fi
done
OUT="$(PREPUB_ALLOW_SKIP="Z" timeout 10 "$MAIN" 33 --skip-build 2>&1)"; rc=$?
if [[ $rc -eq 1 && "$OUT" == "PREPUB_ALLOW_SKIP must be"* ]]; then ok "PREPUB_ALLOW_SKIP with something that is not a scenario letter is refused"; else bad "PREPUB_ALLOW_SKIP=Z: status $rc, said '${OUT%%$'\n'*}'"; fi
for v in "C" "C,F" "C F G"; do
    OUT="$(PREPUB_ALLOW_SKIP="$v" timeout 10 "$MAIN" 29 --skip-build 2>&1)"
    if [[ "$OUT" == "Unknown API level: 29"* ]]; then ok "PREPUB_ALLOW_SKIP='$v' is accepted"; else bad "PREPUB_ALLOW_SKIP='$v' was refused: '${OUT%%$'\n'*}'"; fi
done

echo "Result lines"
LINE="$(result_line D "Wallet creation flow" FAIL 12 "(seed verification rejected the answers)")"
if [[ "$LINE" == "  [D] Wallet creation flow"*"FAIL  (12s)  (seed verification rejected the answers)" ]]; then ok "a FAIL line carries its reason"; else bad "result line: '$LINE'"; fi

echo ""
if [[ $FAILED -eq 0 ]]; then
    echo "Self-test: PASS ($CHECKS checks)"
else
    echo "Self-test: FAIL ($FAILED of $CHECKS checks wrong)"
    exit 1
fi
