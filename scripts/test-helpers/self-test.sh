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
UI_DUMP_FILE="$WORK/ui.xml"
source "$HERE/device.sh"
source "$HERE/report.sh"
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
OUT="$(GENERATED_WORDS="zebra quantum ladder" python3 "$UIDUMP" redact-log GENERATED_WORDS <<< "D Foo: saved zebra and quantum, not zebras")"
if [[ "$OUT" == "D Foo: saved [redacted] and [redacted], not zebras" ]]; then ok "saved logcat has the run's phrase words removed"; else bad "logcat redaction: '$OUT'"; fi
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
    *) echo stub ;;
esac
EOF
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
PATH="$OLD_PATH"

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
