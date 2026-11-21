#!/usr/bin/env bash
# scripts/test-helpers/test-scenarios.sh
#
# The eight pre-publish scenarios. Each one drives the real UI through device.sh and ends by
# calling exactly one of pass / fail "<reason>" / skip "<reason>". A scenario passes only on
# an assertion that is true when its subject works; "no crash in logcat" alone is never a pass.
#
# The screen predicates in the first section are pure readers of the last UI dump, so
# `pre-publish-test.sh --self-test` runs them against canned dumps with no emulator.
#
# Nothing here writes a recovery phrase to disk. The phrase a create flow reads from the screen
# lives in a shell variable that is NOT exported (an exported one is in the environment of
# every adb, python and timeout the suite starts); the raw dump it is read from is mode 600 in
# a private tmpfs directory and is emptied as soon as the phrase screen is left; saved UI dumps
# go through `uidump.py redact` and saved logcat through `uidump.py redact-log`.

# The public BIP39 test phrase. It is in every wallet's test vectors; it protects nothing.
TEST_MNEMONIC="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
# A second public BIP39 vector, used only by the red arm of E (PREPUB_SABOTAGE=E).
OTHER_MNEMONIC="legal winner thank year wave sausage worth useful legal winner thank yellow"
# Where the expected first receive address of TEST_MNEMONIC is pinned (m/84'/20'/0'/0/0).
# The suite reads it from the committed KAT instead of keeping a second copy.
ADDRESS_VECTOR_FILE="native/src/test/host/wallet_open_kat/wallet_open_kat_main.c"

APP_PIN="${PREPUB_APP_PIN:-135790}"        # the wallet PIN every scenario sets
DEVICE_PIN="${PREPUB_DEVICE_PIN:-1357}"    # the emulator screen-lock PIN scenario B sets
SYNC_TIMEOUT="${PREPUB_SYNC_TIMEOUT:-1500}" # G: seconds a new wallet may take to finish syncing
SOAK_SECONDS="${PREPUB_SOAK_SECONDS:-60}"

# Strings from app/src/main/res/values/strings_wallet.xml (the emulator runs in English).
T_ONB_TITLE="Secure your DGB"                       # onb_title
T_ONB_CREATE="Create New Wallet"                    # onb_create
T_ONB_RECOVER="Recover Existing Wallet"             # onb_recover
T_SEED_TITLE="Your Recovery Phrase"                 # seed_title
T_SEED_WRITTEN="I have written these down"          # seed_written_down
T_VERIFY_OK="Verified!"                             # verify_success_title
T_VERIFY_RETRY="Let's try again"                    # verify_retry_title
T_VERIFY_SETUP_PIN="Set Up PIN"                     # verify_setup_pin
T_PASS_TITLE="Add a passphrase?"                    # pass_screen_title
T_PASS_SKIP="Continue without one"                  # pass_screen_continue_without
T_PIN_SET="Set Your PIN"                            # pin_set_title
T_PIN_CONFIRM="Confirm Your PIN"                    # pin_confirm_title
T_UNLOCK_TITLE="Enter PIN"                          # unlock_title
T_UNLOCK_SUB="Enter your 6-digit PIN to unlock"     # unlock_subtitle
T_NOLOCK_TITLE="This phone has no screen lock"      # ksb_notice_title
T_NOLOCK_OK="I understand"                          # ksb_notice_ok
T_BIO_ASK="Enable Biometric Unlock?"                # pin_biometric_ask
T_BIO_SKIP="Skip for now"                           # pin_biometric_skip
T_BATTERY_TITLE="Keep sync running in the background" # battery_title
T_TOR_TITLE="New Privacy Feature"                   # tor_title
T_NOT_NOW="Not now"                                 # common_not_now
T_QUICKSTART_TITLE="Show the balance sooner?"       # quickstart_title
T_QUICKSTART_SKIP="Skip and sync privately"         # quickstart_skip
T_RECOVER_TITLE="Recover Wallet"                    # recover_title
T_CONTINUE="Continue"                               # recover_continue
T_RESTORE_PRIVATE="Restore privately"               # restore_scan_title
T_RESTORE_DATE="When did you create this wallet?"   # restore_date_title
T_RESTORE_START="Start Recovery"                    # restore_date_start
T_RESTORE_BAD_PHRASE="This recovery phrase isn't valid" # restore_err_checksum (prefix)
T_WALLET_ACTIVITY="Recent Activity"                 # wallet_recent_activity
T_WALLET_SEND="Send"                                # wallet_send
T_WALLET_RECEIVE="Receive"                          # wallet_receive
T_NAV_WALLET="Wallet"                               # nav_wallet
T_NAV_SETTINGS="Settings"                           # nav_settings
T_RECEIVE_ADDR="Your DigiByte Address"              # receive_address_dgb
T_SET_SECURITY="Security"                           # set_security
T_SET_NETWORK="Network Info"                        # set_network_info
T_SEC_KEY="Wallet key"                              # sec_seed_key
T_SEC_KEY_BOUND="Tied to this phone's screen lock"  # sec_seed_key_bound
T_SEC_KEY_NO_LOCK="Not tied: this phone has no screen lock" # sec_seed_key_no_lock (prefix)
T_SEND_TITLE="Send DGB"                             # send_title_dgb
T_SEND_HINT="dgb1q… or D…"                          # send_hint_dgb
T_SEND_AMOUNT_HINT="0.00000000"                     # the amount field's placeholder
T_SEND_REVIEW="Review & Send"                       # send_review
T_SEND_WAIT_SYNC="Wait for sync to finish before sending" # send_wait_for_sync (prefix)
T_SEND_NO_PEERS="No DigiByte peers connected"       # send_no_peers (prefix)
T_SEND_FAIL_TITLE="Transaction not sent"            # send_fail_title
T_SEND_FAIL_UNTOUCHED="Nothing left your wallet. Your coins are untouched." # send_fail_nothing_sent
T_SEND_FAIL_INSUFFICIENT="Your balance has to cover the network fee as well as the amount." # send_fail_insufficient_fee (prefix)
T_SEND_CONFIRM_TITLE="Confirm Transaction"          # send_dialog_title
T_SEND_INVALID_ADDR="Invalid DigiByte address"      # send_invalid_dgb

# ── Verdicts ──────────────────────────────────────────────────────────────────

RESULT=""; REASON=""
pass() { RESULT="PASS"; REASON=""; }
fail() { RESULT="FAIL"; REASON="$*"; }
skip() { RESULT="SKIP"; REASON="$*"; }

sabotaged() { [[ "${PREPUB_SABOTAGE:-}" == "$1" ]]; }

test_name() {
    case "$1" in
        A) echo "Fresh install, no lock screen" ;;
        B) echo "Fresh install, with lock screen" ;;
        C) echo "Upgrade from previous version" ;;
        D) echo "Wallet creation flow" ;;
        E) echo "Wallet recovery flow" ;;
        F) echo "Sync without ANR" ;;
        G) echo "Send transaction" ;;
        H) echo "60-second stability soak" ;;
    esac
}

# ── Screen predicates: pure readers of the last dump ($UI_DUMP_FILE) ──────────

screen_is_onboarding() { ui_has "$T_ONB_TITLE" && ui_has "$T_ONB_CREATE" && ui_has "$T_ONB_RECOVER"; }
screen_is_unlock()     { ui_has "$T_UNLOCK_TITLE" && ui_has "$T_UNLOCK_SUB"; }
# The wallet home: the activity list, both actions and the bottom bar.
screen_is_wallet() {
    ui_has "$T_WALLET_ACTIVITY" && ui_has "$T_WALLET_SEND" && ui_has "$T_WALLET_RECEIVE" && ui_has "$T_NAV_SETTINGS"
}
screen_is_wallet_or_unlock() { screen_is_wallet || screen_is_unlock; }
screen_verify_passed() { ui_has "$T_VERIFY_OK" && ui_has "$T_VERIFY_SETUP_PIN"; }

# Prints the receive address on the Receive screen; fails unless it is a DigiByte bech32 address.
screen_receive_address() {
    local addr
    addr="$(ui_after "$T_RECEIVE_ADDR")" || return 1
    [[ "$addr" =~ ^dgb1[02-9ac-hj-np-z]{38,}$ ]] || return 1
    printf '%s\n' "$addr"
}

# Prints the status under "Wallet key" on the Security screen.
screen_wallet_key_status() { ui_after "$T_SEC_KEY"; }
screen_wallet_key_bound() {
    local s; s="$(screen_wallet_key_status)" || return 1
    [[ "${s//’/\'}" == "$T_SEC_KEY_BOUND" ]]
}
screen_wallet_key_no_lock() {
    local s; s="$(screen_wallet_key_status)" || return 1
    [[ "$s" == "$T_SEC_KEY_NO_LOCK"* ]]
}

# The refusal a spend larger than the balance gets: the failure sheet, its "nothing was sent"
# line and the insufficient-funds guidance, all three.
screen_send_refused_insufficient() {
    ui_has "$T_SEND_FAIL_TITLE" && ui_has "$T_SEND_FAIL_UNTOUCHED" && ui_has --prefix "$T_SEND_FAIL_INSUFFICIENT"
}

# Peer count and block height from the wallet home's status / progress lines.
screen_peer_count() { ui_match '· ([0-9]+) peers'; }
screen_block_height() {
    local h
    h="$(ui_match 'Block ([0-9][0-9,.  ]*[0-9])')" || return 1
    h="${h//[^0-9]/}"
    [[ -n "$h" ]] || return 1
    printf '%s\n' "$h"
}

# ── Health: crash and ANR ─────────────────────────────────────────────────────

HEALTH_REASON=""
# app_unhealthy — true (0) when the app crashed or stopped responding since logcat was cleared.
app_unhealthy() {
    local crash line anr
    crash="$(adbx logcat -b crash -d 2>/dev/null | tr -d '\r')"
    if grep -q -F "$PACKAGE" <<< "$crash"; then
        line="$(grep -m1 -E '(Exception|Error)[: ]|Fatal signal|signal [0-9]+ \(' <<< "$crash" | sed 's/^.*\(AndroidRuntime\|DEBUG\|libc\) *: *//')"
        HEALTH_REASON="crash: ${line:-see the saved logcat}"
        return 0
    fi
    anr="$(adbx logcat -d -s ActivityManager:E 2>/dev/null)"
    if grep -q -F "ANR in $PACKAGE" <<< "$anr"; then
        HEALTH_REASON="ANR in $PACKAGE"
        return 0
    fi
    return 1
}

# ── Install / launch ──────────────────────────────────────────────────────────

WALLET_STATE="none"      # none | created | restored — the wallet on the current install
GENERATED_WORDS=""       # the phrase read from the screen in a create flow; never written out,
                         # never exported

clear_logs() { adbx logcat -b all -c > /dev/null 2>&1 || adbx logcat -c > /dev/null 2>&1 || true; }

uninstall_app() {
    adbx shell am force-stop "$PACKAGE" > /dev/null 2>&1
    if adbt 60 uninstall "$PACKAGE" > /dev/null 2>&1; then
        sleep 3   # let the system finish removing the old install's task before the next one starts
    fi
    WALLET_STATE="none"
}

# fresh_install <apk> — remove any install, put <apk> on, clear the logs. No launch.
fresh_install() {
    uninstall_app
    adb_install "$1" || return 1
    clear_logs
}

# launch_app — start the activity. One case gets a second start: right after an uninstall and
# reinstall, ActivityManager can still be removing the PREVIOUS install's task, and kills the
# new process with it ("Killing <pid>:<package> … remove task"). That is the system, not the
# app, and it is named in the system log; anything else that leaves no process is left for
# the scenario to report.
launch_app() {
    local am
    adbt 30 shell am start -n "$ACTIVITY" > /dev/null 2>&1
    sleep 3
    [[ -n "$(app_pid)" ]] && return 0
    am="$(adbx logcat -d -s ActivityManager:I 2>/dev/null)"
    if grep -q -E "Killing [0-9]+:$PACKAGE.*remove task" <<< "$am"; then
        log "launch: the system killed the new process while removing the previous install's task; starting again"
        adbt 30 shell am start -n "$ACTIVITY" > /dev/null 2>&1
        sleep 2
    fi
}

relaunch_app() {
    adbx shell am force-stop "$PACKAGE" > /dev/null 2>&1
    launch_app
}

# ── Device screen lock (scenario B) ───────────────────────────────────────────

DEVICE_LOCK_SET=0
LAST_DEVICE_AUTH=0   # when the device credential was last given (set, or typed into a prompt)

device_lock_set() {
    local out
    out="$(adbx shell locksettings set-pin "$DEVICE_PIN" 2>&1)"
    DEVICE_LOCK_SET=1   # assume it took, so cleanup always tries
    LAST_DEVICE_AUTH=$(date +%s)
    [[ "$out" == *"Pin set to"* ]] || { log "locksettings set-pin said: $out"; return 1; }
}

# Always called at the end of a scenario and on exit: the suite never leaves a lock it set.
device_lock_clear() {
    [[ "$DEVICE_LOCK_SET" == 1 ]] || return 0
    local out
    out="$(adbx shell locksettings clear --old "$DEVICE_PIN" 2>&1)"
    if [[ "$out" == *"cleared"* || "$out" == *"user has no password"* ]]; then
        DEVICE_LOCK_SET=0
        return 0
    fi
    log "locksettings clear said: $out"
    return 1
}

# The device-credential window: the system's, not the app's. On it the PIN is typed with
# `input text`, and only there.
credential_prompt_showing() {
    local f; f="$(focused_window)"
    [[ "$f" == com.android.systemui* || "$f" == com.android.settings* || "$f" == *BiometricPrompt* || "$f" == *ConfirmDeviceCredential* ]]
}

credential_prompt_answer() {
    # Some versions first show a "Use PIN" choice.
    if ui_has "Use PIN"; then ui_tap "Use PIN"; sleep 1; fi
    adbx shell input text "$DEVICE_PIN" > /dev/null 2>&1
    sleep 0.5
    ui_key KEYCODE_ENTER
    LAST_DEVICE_AUTH=$(date +%s)
    log "device-credential prompt answered (window '$(focused_window)')"
}

# ── Flow pieces ───────────────────────────────────────────────────────────────

FLOW_ERROR=""
SEEN_NO_LOCK_NOTICE=0
SEEN_CREDENTIAL_PROMPTS=0

# (Captured before grep: a `grep -q` that exits early on a pipe reads as "no match" under pipefail.)
keyboard_shown() {
    local ime; ime="$(adbx shell dumpsys input_method 2>/dev/null)"
    grep -q 'mInputShown=true' <<< "$ime"
}
hide_keyboard() { if keyboard_shown; then ui_key KEYCODE_BACK; sleep 1; fi; }

# advance_to_wallet <seconds> — answer whatever stands between a finished PIN (or an unlock)
# and the wallet home: the system permission dialog, the device-credential prompt, the
# no-screen-lock notice, the biometric offer, the battery and Tor prompts, quick start.
advance_to_wallet() {
    local deadline=$(( $(date +%s) + $1 )) focus
    while [[ $(date +%s) -lt $deadline ]]; do
        focus="$(focused_window)"
        if [[ "$focus" == "$PACKAGE/"* ]]; then
            ui_dump || { sleep 1; continue; }
            if screen_is_wallet; then return 0; fi
            if ui_has "$T_NOLOCK_TITLE"; then SEEN_NO_LOCK_NOTICE=1; ui_tap "$T_NOLOCK_OK"
            elif ui_has "$T_BIO_ASK"; then ui_tap "$T_BIO_SKIP"
            elif ui_has "$T_BATTERY_TITLE"; then ui_tap "$T_NOT_NOW"
            elif ui_has "$T_TOR_TITLE"; then ui_tap "$T_NOT_NOW"
            elif ui_has "$T_QUICKSTART_TITLE"; then ui_tap "$T_QUICKSTART_SKIP"
            elif ui_has "$T_RESTORE_PRIVATE" && ui_has "$T_CONTINUE"; then ui_tap "$T_CONTINUE"
            elif ui_has --prefix "Wallet creation failed" || ui_has --prefix "Failed to save PIN"; then
                FLOW_ERROR="the app reported: $(ui_match '(Wallet creation failed.*|Failed to save PIN.*)')"
                return 1
            fi
        elif [[ "$focus" == *permissioncontroller* || "$focus" == *packageinstaller* ]]; then
            ui_dump && { ui_tap "Allow" || ui_tap "ALLOW" || ui_tap --contains "llow"; }
        elif credential_prompt_showing; then
            if [[ "$DEVICE_LOCK_SET" == 1 ]]; then
                ui_dump
                credential_prompt_answer
                SEEN_CREDENTIAL_PROMPTS=$((SEEN_CREDENTIAL_PROMPTS + 1))
                sleep 2
            fi
        fi
        sleep 1
    done
    ui_dump
    FLOW_ERROR="did not reach the wallet screen; focus was '$(focused_window)', screen showed: $(ui_texts | grep -v -E '^[a-z]{3,8}$' | head -4 | tr '\n' '/' )"
    return 1
}

set_app_pin() {
    ui_wait 30 "$T_PIN_SET" || { FLOW_ERROR="the PIN setup screen ('$T_PIN_SET') did not appear"; return 1; }
    ui_enter_app_pin "$APP_PIN" || { FLOW_ERROR="could not press the PIN pad"; return 1; }
    ui_wait 15 "$T_PIN_CONFIRM" || { FLOW_ERROR="the PIN confirmation screen did not appear after 6 digits"; return 1; }
    ui_enter_app_pin "$APP_PIN" || { FLOW_ERROR="could not press the PIN pad (confirm)"; return 1; }
}

# create_wallet [right|wrong] — from the first onboarding screen to the wallet home.
# "wrong" answers the verification questions incorrectly on purpose (the red arm of D).
create_wallet() {
    local answers="${1:-right}" q n word choice outcome
    local -a words
    FLOW_ERROR=""; GENERATED_WORDS=""
    ui_wait 40 "$T_ONB_CREATE" || { FLOW_ERROR="the first onboarding screen did not appear"; return 1; }
    ui_tap "$T_ONB_CREATE"
    ui_wait 30 "$T_SEED_TITLE" || { FLOW_ERROR="the recovery-phrase screen ('$T_SEED_TITLE') did not appear"; return 1; }
    GENERATED_WORDS="$(python3 "$UIDUMP" seed-words "$UI_DUMP_FILE")" || {
        ui_dump_scrub
        FLOW_ERROR="could not read 12 numbered words from the recovery-phrase screen"; return 1; }
    # Plain word splitting, not a here-string: bash may back a here-string with a temp file.
    # shellcheck disable=SC2206
    words=($GENERATED_WORDS)
    [[ ${#words[@]} -eq 12 ]] || { ui_dump_scrub; FLOW_ERROR="the phrase screen showed ${#words[@]} words, expected 12"; return 1; }
    ui_scroll_to 3 "$T_SEED_WRITTEN" || { ui_dump_scrub; FLOW_ERROR="'$T_SEED_WRITTEN' is not on the phrase screen"; return 1; }
    ui_tap "$T_SEED_WRITTEN"
    ui_dump_scrub   # the phrase screen's dump is not kept until the next one replaces it

    for q in 1 2 3; do
        ui_wait 15 --prefix "Verification $q of 3" || { FLOW_ERROR="verification question $q did not appear"; return 1; }
        n="$(ui_match 'What is word #([0-9]+)\?')" || { FLOW_ERROR="question $q does not ask for a word number"; return 1; }
        word="${words[$((n - 1))]}"
        if [[ "$answers" == "wrong" ]]; then
            choice="$(ui_texts | grep -E '^[a-z]{3,8}$' | grep -v -x "$word" | head -1)"
        else
            choice="$word"
        fi
        ui_tap "$choice" || { FLOW_ERROR="question $q does not offer the phrase's word #$n"; return 1; }
        sleep 0.6
        ui_dump
        ui_tap "Next" || ui_tap "Finish" || { FLOW_ERROR="no Next/Finish after answering question $q"; return 1; }
    done

    outcome="$(ui_wait_any 15 "$T_VERIFY_OK" "$T_VERIFY_RETRY")" || { FLOW_ERROR="no verification result screen"; return 1; }
    if [[ "$outcome" != "$T_VERIFY_OK" ]]; then
        FLOW_ERROR="seed verification rejected the answers ('$T_VERIFY_RETRY')"
        return 1
    fi
    ui_tap "$T_VERIFY_SETUP_PIN"
    ui_wait 15 "$T_PASS_TITLE" || { FLOW_ERROR="the passphrase question did not appear"; return 1; }
    ui_tap "$T_PASS_SKIP"
    set_app_pin || return 1
    advance_to_wallet 120 || return 1
    WALLET_STATE="created"
}

# Type the 12 words into the 12 separate fields. Each word goes in only while the app's own
# window has focus.
enter_phrase() {
    local phrase="$1" i xy word
    local -a words fields
    read -r -a words <<< "$phrase"
    for i in "${!words[@]}"; do
        word="${words[$i]}"
        hide_keyboard
        ui_dump || { FLOW_ERROR="could not read the phrase-entry screen"; return 1; }
        mapfile -t fields < <(python3 "$UIDUMP" fields "$UI_DUMP_FILE")
        [[ ${#fields[@]} -eq ${#words[@]} ]] || { FLOW_ERROR="expected ${#words[@]} word fields, the screen has ${#fields[@]}"; return 1; }
        xy="${fields[$i]}"
        # shellcheck disable=SC2086
        ui_press_xy $xy
        sleep 0.7
        ui_type "$word" || { FLOW_ERROR="the app's window lost focus while typing word $((i + 1))"; return 1; }
        sleep 0.4
    done
    hide_keyboard
}

# restore_wallet [phrase] — from the first onboarding screen to the wallet home.
restore_wallet() {
    local phrase="${1:-$TEST_MNEMONIC}" year seen deadline
    FLOW_ERROR=""
    ui_wait 40 "$T_ONB_RECOVER" || { FLOW_ERROR="the first onboarding screen did not appear"; return 1; }
    ui_tap "$T_ONB_RECOVER"
    ui_wait 20 "$T_RECOVER_TITLE" || { FLOW_ERROR="the phrase-entry screen ('$T_RECOVER_TITLE') did not appear"; return 1; }
    enter_phrase "$phrase" || return 1
    ui_scroll_to 3 "$T_CONTINUE" || { FLOW_ERROR="no '$T_CONTINUE' on the phrase-entry screen"; return 1; }
    if ui_has --prefix "$T_RESTORE_BAD_PHRASE"; then FLOW_ERROR="the app rejected the phrase as invalid"; return 1; fi
    ui_tap "$T_CONTINUE"

    # "Restore privately" (Continue), then the wallet's age.
    seen="$(ui_wait_any 30 "$T_RESTORE_DATE" "$T_RESTORE_PRIVATE" "$T_RESTORE_BAD_PHRASE")" || {
        FLOW_ERROR="nothing followed the phrase-entry screen"; return 1; }
    if [[ "$seen" == "$T_RESTORE_BAD_PHRASE" ]]; then FLOW_ERROR="the app rejected the phrase as invalid"; return 1; fi
    if [[ "$seen" == "$T_RESTORE_PRIVATE" ]]; then
        ui_scroll_to 3 "$T_CONTINUE" && ui_tap "$T_CONTINUE"
        ui_wait 30 "$T_RESTORE_DATE" || { FLOW_ERROR="the wallet-age screen ('$T_RESTORE_DATE') did not appear"; return 1; }
    fi
    # Pick the most recent year offered: the shortest scan.
    year="$(ui_texts | grep -o -E '^(19|20)[0-9]{2}$' | sort -n | tail -1)"
    [[ -n "$year" ]] || { FLOW_ERROR="the wallet-age screen offers no year"; return 1; }
    ui_tap "$year" || { FLOW_ERROR="could not press year $year"; return 1; }
    sleep 0.5
    ui_scroll_to 4 "$T_RESTORE_START" || { FLOW_ERROR="no '$T_RESTORE_START' on the wallet-age screen"; return 1; }
    ui_tap "$T_RESTORE_START"
    # With a screen lock the device-credential prompt can come up here, before PIN setup.
    deadline=$(( $(date +%s) + 60 ))
    while [[ $(date +%s) -lt $deadline ]]; do
        if app_has_focus; then
            ui_dump && ui_has "$T_PIN_SET" && break
        elif credential_prompt_showing && [[ "$DEVICE_LOCK_SET" == 1 ]]; then
            ui_dump; credential_prompt_answer; SEEN_CREDENTIAL_PROMPTS=$((SEEN_CREDENTIAL_PROMPTS + 1)); sleep 2
        fi
        sleep 1
    done
    set_app_pin || return 1
    advance_to_wallet 180 || return 1
    WALLET_STATE="restored"
}

# unlock_app — from the unlock screen to the wallet home.
unlock_app() {
    local i
    ui_key KEYCODE_WAKEUP
    ui_wait 40 "$T_UNLOCK_TITLE" || { FLOW_ERROR="the unlock screen ('$T_UNLOCK_TITLE') did not appear"; return 1; }
    ui_enter_app_pin "$APP_PIN" || { FLOW_ERROR="could not press the unlock PIN pad"; return 1; }
    if ui_wait_gone 12 "$T_UNLOCK_SUB"; then
        advance_to_wallet 90
        return $?
    fi
    # Still the PIN pad. If the app counted a wrong PIN, that is a result. If it did not, a
    # press was dropped (a screen that had to wake first): clear the partial entry, enter once more.
    if ui_has --prefix "Incorrect PIN"; then
        FLOW_ERROR="the app rejected the PIN this run set ($(ui_match '(Incorrect PIN.*)'))"
        return 1
    fi
    for i in 1 2 3 4 5 6; do ui_tap "Backspace"; sleep 0.2; done
    ui_enter_app_pin "$APP_PIN" || { FLOW_ERROR="could not press the unlock PIN pad"; return 1; }
    advance_to_wallet 90
}

# Bring the app to the wallet home whatever it is showing (locked, a sub-screen, a prompt).
goto_wallet_home() {
    local i
    ui_key KEYCODE_WAKEUP
    app_has_focus || launch_app
    for i in 1 2 3 4 5 6 7 8; do
        ui_dump || { sleep 1; continue; }
        if screen_is_wallet; then return 0; fi
        if screen_is_unlock; then unlock_app; return $?; fi
        if keyboard_shown; then hide_keyboard
        elif ui_has "Back"; then ui_tap "Back"     # a sub-screen's own back arrow (common_back)
        elif ui_has "$T_NAV_WALLET" && ui_has "$T_NAV_SETTINGS"; then ui_tap "$T_NAV_WALLET"
        elif app_has_focus; then
            advance_to_wallet 5 && return 0
            ui_key KEYCODE_BACK
        else launch_app
        fi
        sleep 1.5
    done
    FLOW_ERROR="could not get back to the wallet screen"
    return 1
}

# read_receive_address — wallet home → Receive → address → back. Prints the address.
read_receive_address() {
    local addr
    ui_dump && ui_tap "$T_WALLET_RECEIVE" || return 1
    ui_wait 20 "$T_RECEIVE_ADDR" || return 1
    addr="$(screen_receive_address)" || return 1
    ui_key KEYCODE_BACK
    sleep 1
    printf '%s\n' "$addr"
}

# read_wallet_key_status — wallet home → Settings → Security → the "Wallet key" line.
read_wallet_key_status() {
    local status deadline
    ui_dump && ui_tap "$T_NAV_SETTINGS" || return 1
    ui_wait 20 "$T_SET_NETWORK" || return 1
    ui_tap "$T_SET_SECURITY" || return 1
    sleep 1.5
    ui_scroll_to 4 "$T_SEC_KEY" || return 1
    # The status is resolved off the main thread; give the line a moment to fill in.
    deadline=$(( $(date +%s) + 15 ))
    while :; do
        status="$(screen_wallet_key_status)" && [[ -n "$status" && "$status" != "DANGER ZONE" ]] && break
        [[ $(date +%s) -ge $deadline ]] && return 1
        sleep 1; ui_dump
    done
    printf '%s\n' "$status"
}

# A wallet for the scenarios that only need one (F, H): this run's, or, when there is none
# (`--test F`, `--test H`, or the scenario before it never got a wallet), a new one.
# A wallet that exists and cannot be brought to its home screen is a FAILURE, never replaced:
# an app that wedges or keeps dying on the wallet the previous scenario left (E's restore scan,
# for F) is what these scenarios are there to catch, and a fresh wallet would hide it.
ensure_wallet() {
    local why
    if [[ "$WALLET_STATE" != "none" ]]; then
        goto_wallet_home && return 0
        why="$FLOW_ERROR"
        app_unhealthy && why+="; $HEALTH_REASON"
        log "ensure_wallet: the $WALLET_STATE wallet is unreachable ($why); NOT replacing it"
        FLOW_ERROR="the $WALLET_STATE wallet from the previous scenario could not be reached (${why:-no detail})"
        return 1
    fi
    fresh_install "$APK_PATH" || { FLOW_ERROR="install failed: ${INSTALL_ERROR:-timeout}"; return 1; }
    launch_app
    create_wallet
}

# The seed key's auth window, read from the source it is defined in rather than copied here.
AUTH_WINDOW_FILE="core/src/main/java/app/aroundtheblock/wallet/core/security/SeedKeyBinding.kt"
seed_key_auth_window() {
    local f v
    f="$PROJECT_DIR/$AUTH_WINDOW_FILE"
    [[ -f "$f" ]] || return 1
    v="$(sed -n 's/.*SEED_KEY_AUTH_WINDOW_SECS *= *\([0-9][0-9_]*\).*/\1/p' "$f" | head -1)"
    printf '%s\n' "${v//_/}"
}

expected_restore_address() {
    sed -n 's/.*PINNED_BIP84_0_0 *= *"\(dgb1[a-z0-9]*\)".*/\1/p' "$PROJECT_DIR/$ADDRESS_VECTOR_FILE" 2>/dev/null | head -1
}

# The emulator can reach the internet at all (a peer problem is the app's; no route is not).
network_reachable() {
    adbt 15 shell 'ping -c 1 -W 4 8.8.8.8 >/dev/null 2>&1 || ping -c 1 -W 4 1.1.1.1 >/dev/null 2>&1'
}

# ── A: Fresh install, no lock screen ──────────────────────────────────────────
# The app, newly installed on a device with no screen lock, opens on the first onboarding
# screen; a wallet can be set up; the one-time "no screen lock" notice appears, is dismissed
# and does not block; Security then reports the key as not tied for lack of a lock.
test_A() {
    local status
    fresh_install "$APK_PATH" || { fail "install failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    if sabotaged A; then sleep 3; adbx shell am crash "$PACKAGE" > /dev/null 2>&1; sleep 2; fi
    if ! ui_wait_for 60 screen_is_onboarding; then
        app_unhealthy && { fail "no onboarding screen; $HEALTH_REASON"; return; }
        fail "the first onboarding screen ('$T_ONB_TITLE' with '$T_ONB_CREATE' / '$T_ONB_RECOVER') did not appear"
        return
    fi
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    SEEN_NO_LOCK_NOTICE=0
    create_wallet || { fail "wallet setup with no screen lock: $FLOW_ERROR"; return; }
    [[ "$SEEN_NO_LOCK_NOTICE" == 1 ]] || { fail "the no-screen-lock notice ('$T_NOLOCK_TITLE') was never shown on a device with no lock"; return; }
    status="$(read_wallet_key_status)" || { fail "Security screen has no '$T_SEC_KEY' line"; return; }
    [[ "$status" == "$T_SEC_KEY_NO_LOCK"* ]] || { fail "'$T_SEC_KEY' reads '$status' on a device with no screen lock"; return; }
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    pass
}

# ── B: Fresh install, with lock screen ────────────────────────────────────────
# With a screen lock set, a wallet is created end to end and Security reports the wallet key
# as tied to the lock (KS-BIND). Setting the lock counts as a fresh authentication, so setup
# itself may need no prompt (it may show at most one). The prompt is then forced: once the
# key's auth window has lapsed the app is restarted and unlocked, the device-credential prompt
# must come up exactly once, and answering it in the system UI must open the wallet.
test_B() {
    local status window wait_left
    if ! sabotaged B; then
        device_lock_set || { fail "could not set the emulator's screen-lock PIN"; return; }
    fi
    fresh_install "$APK_PATH" || { fail "install failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    SEEN_CREDENTIAL_PROMPTS=0
    create_wallet || { fail "wallet setup with a screen lock: $FLOW_ERROR"; return; }
    [[ "$SEEN_CREDENTIAL_PROMPTS" -le 1 ]] || { fail "the device-credential prompt came up $SEEN_CREDENTIAL_PROMPTS times during one wallet setup (KS-BIND allows one)"; return; }
    B_NOTE="prompts at setup: $SEEN_CREDENTIAL_PROMPTS"
    status="$(read_wallet_key_status)" || { fail "Security screen has no '$T_SEC_KEY' line"; return; }
    [[ "${status//’/\'}" == "$T_SEC_KEY_BOUND" ]] || { fail "'$T_SEC_KEY' reads '$status', expected '$T_SEC_KEY_BOUND'"; return; }
    app_unhealthy && { fail "$HEALTH_REASON"; return; }

    window="$(seed_key_auth_window)"
    [[ -n "$window" ]] || { fail "could not read SEED_KEY_AUTH_WINDOW_SECS from $AUTH_WINDOW_FILE"; return; }
    # The window runs from the LAST device authentication: setting the lock, or a prompt
    # answered during setup (API 28 asks once at setup; a window counted from the lock being
    # set would still be open here).
    wait_left=$(( LAST_DEVICE_AUTH + window + 20 - $(date +%s) ))
    log "B: waiting ${wait_left}s for the ${window}s auth window to lapse"
    while [[ $(( LAST_DEVICE_AUTH + window + 20 - $(date +%s) )) -gt 0 ]]; do sleep 5; done
    relaunch_app
    SEEN_CREDENTIAL_PROMPTS=0
    unlock_app || {
        app_unhealthy && { fail "unlocking after the auth window lapsed: $HEALTH_REASON"; return; }
        fail "unlocking after the ${window}s auth window lapsed (prompts answered: $SEEN_CREDENTIAL_PROMPTS): $FLOW_ERROR"; return; }
    [[ "$SEEN_CREDENTIAL_PROMPTS" -eq 1 ]] || { fail "after the ${window}s auth window lapsed the wallet opened with $SEEN_CREDENTIAL_PROMPTS device-credential prompts, expected exactly 1 ($B_NOTE)"; return; }
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    B_NOTE+=", after the ${window}s window: 1 (answered)"
    pass
}

# ── C: Upgrade from previous version ──────────────────────────────────────────
# A wallet made on the previous release is still there, behind the same PIN and with the same
# receive address, after the new APK is installed over it.
test_C() {
    local prev="$PREVIOUS_APK" aapt2 prev_pkg before after seen
    if [[ ! -f "$prev" ]]; then
        skip "no previous-release APK at test-fixtures/previous-release.apk (see the header of pre-publish-test.sh)"
        return
    fi
    aapt2="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
    [[ -n "$aapt2" ]] || { skip "aapt2 not found; cannot check the previous APK is the same package"; return; }
    prev_pkg="$("$aapt2" dump packagename "$prev" 2>/dev/null)"
    # An APK of a different package installs beside the app instead of under it, and the
    # "upgrade" would then upgrade nothing.
    [[ "$prev_pkg" == "$PACKAGE" ]] || { skip "previous APK is ${prev_pkg:-unreadable}, not $PACKAGE"; return; }

    fresh_install "$prev" || { fail "installing the previous release failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    create_wallet || { fail "creating a wallet on the previous release: $FLOW_ERROR"; return; }
    before="$(read_receive_address)" || { fail "the previous release shows no receive address"; return; }
    ui_wait_for 15 screen_is_wallet || { fail "the previous release is not on the wallet screen before the upgrade"; return; }

    adbx shell am force-stop "$PACKAGE" > /dev/null 2>&1
    if sabotaged C; then adbt 60 uninstall "$PACKAGE" > /dev/null 2>&1; fi
    clear_logs
    adb_install -r "$APK_PATH" || { fail "installing the new APK over the previous release failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    seen="$(ui_wait_any 60 "$T_UNLOCK_SUB" "$T_ONB_CREATE")" || {
        app_unhealthy && { fail "after the upgrade: $HEALTH_REASON"; return; }
        fail "after the upgrade neither the unlock screen nor onboarding appeared"; return; }
    if [[ "$seen" == "$T_ONB_CREATE" ]]; then
        fail "after the upgrade the app shows ONBOARDING: the wallet made on the previous release is gone"
        return
    fi
    unlock_app || { fail "unlocking after the upgrade with the PIN set before it: $FLOW_ERROR"; return; }
    WALLET_STATE="created"
    after="$(read_receive_address)" || { fail "no receive address after the upgrade"; return; }
    [[ "$after" == "$before" ]] || { fail "receive address changed across the upgrade: $before before, $after after"; return; }
    app_unhealthy && { fail "after the upgrade: $HEALTH_REASON"; return; }
    pass
}

# ── D: Wallet creation flow ───────────────────────────────────────────────────
# A 12-word wallet is created completely: the phrase is read from the screen, the three
# verification questions are answered from it, a PIN is set, the wallet screen is reached and
# the Receive screen shows a DigiByte address. The phrase must not reach logcat.
test_D() {
    local addr answers="right" logcat_all
    local -a w
    sabotaged D && answers="wrong"
    fresh_install "$APK_PATH" || { fail "install failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    create_wallet "$answers" || { fail "$FLOW_ERROR"; return; }
    addr="$(read_receive_address)" || { fail "the Receive screen shows no DigiByte address under '$T_RECEIVE_ADDR'"; return; }
    # shellcheck disable=SC2206
    w=($GENERATED_WORDS)
    logcat_all="$(adbx logcat -d 2>/dev/null)"
    if grep -q -F "${w[0]} ${w[1]} ${w[2]}" <<< "$logcat_all"; then
        fail "the generated recovery phrase appears in logcat"
        return
    fi
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    pass
}

# ── E: Wallet recovery flow ───────────────────────────────────────────────────
# The public BIP39 test phrase is restored through the 12 word fields; the receive address is
# the BIP84 address the committed KAT pins for that phrase.
test_E() {
    local expected got phrase="$TEST_MNEMONIC"
    expected="$(expected_restore_address)"
    [[ -n "$expected" ]] || { fail "no pinned address found in $ADDRESS_VECTOR_FILE (PINNED_BIP84_0_0)"; return; }
    sabotaged E && phrase="$OTHER_MNEMONIC"
    fresh_install "$APK_PATH" || { fail "install failed: ${INSTALL_ERROR:-timeout}"; return; }
    launch_app
    restore_wallet "$phrase" || { fail "$FLOW_ERROR"; return; }
    got="$(read_receive_address)" || { fail "the Receive screen shows no DigiByte address after the restore"; return; }
    [[ "$got" == "$expected" ]] || { fail "restored wallet's receive address is $got, the KAT pins $expected"; return; }
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    pass
}

# ── F: Sync without ANR ───────────────────────────────────────────────────────
# With a wallet open the app holds at least one peer and the block height it shows moves
# forward within a bounded time. No network on the emulator is a SKIP, not a pass.
# The height is the assertion that carries the weight: the app's peer count includes sockets
# that are still connecting, so with the app's network cut (PREPUB_SABOTAGE=F) the status line
# can go on saying "7 peers" while every connection fails. The height then stands still.
test_F() {
    local deadline peers="" first="" now="" t0 uid
    ensure_wallet || { fail "no wallet to sync: $FLOW_ERROR"; return; }
    if ! network_reachable; then
        skip "the emulator has no network (ping to 8.8.8.8 and 1.1.1.1 failed)"
        return
    fi
    if sabotaged F; then
        # The app, and only the app, loses its network: what a peer-connection regression looks like.
        uid="$(adbx shell pm list packages -U "$PACKAGE" 2>/dev/null | tr -d '\r' | sed -n "s/^package:$PACKAGE uid:\\([0-9]*\\).*/\\1/p" | head -1)"
        [[ -n "$uid" ]] || uid="$(adbx shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/.*\(userId\|appId\)=\([0-9]*\).*/\2/p' | head -1)"
        [[ -n "$uid" ]] || { fail "sabotage F could not find the app's uid"; return; }
        adbt 20 root > /dev/null 2>&1; sleep 2; adbt 30 wait-for-device > /dev/null 2>&1
        adbx shell "ip6tables -I OUTPUT -m owner --uid-owner $uid -j DROP; iptables -I OUTPUT -m owner --uid-owner $uid -j DROP" > /dev/null 2>&1 \
            || { fail "sabotage F could not install its firewall rule (uid '$uid')"; return; }
        relaunch_app
        goto_wallet_home || { fail "sabotage F: $FLOW_ERROR"; return; }
    fi

    t0=$(date +%s); deadline=$(( t0 + 240 ))
    while [[ $(date +%s) -lt $deadline ]]; do
        goto_wallet_home > /dev/null 2>&1 || true
        if ui_dump && screen_is_wallet; then
            peers="$(screen_peer_count)" || peers=""
            now="$(screen_block_height)" || now=""
            if [[ -n "$now" && -z "$first" ]]; then first="$now"; fi
            if [[ -n "$peers" && "$peers" -ge 1 && -n "$first" && -n "$now" && "$now" -gt "$first" ]]; then
                break
            fi
        fi
        app_unhealthy && { fail "$HEALTH_REASON"; return; }
        sleep 4
    done
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    if [[ -z "$peers" || "$peers" -lt 1 ]]; then
        fail "no peer connected within 240 s although the emulator has network (status showed ${peers:-no} peers)"
        return
    fi
    if [[ -z "$first" || -z "$now" || "$now" -le "$first" ]]; then
        fail "the block height did not advance within 240 s (first ${first:-unread}, last ${now:-unread}, $peers peers)"
        return
    fi
    F_NOTE="$peers peers, block $first -> $now in $(( $(date +%s) - t0 )) s"
    pass
}

# ── G: Send transaction ───────────────────────────────────────────────────────
# NO COINS ARE SENT. The emulator wallet is empty, so the honest assertion is the refusal:
# once the wallet has synced, the Send screen takes an address and an amount, and Review
# answers with the insufficient-funds failure ("Transaction not sent … Your balance has to
# cover the network fee as well as the amount") instead of a confirmation or a crash.
test_G() {
    local dest amount="1.5" deadline seen
    # A wallet created by this run syncs from a recent checkpoint (minutes). A restored one
    # scans from the start of the chosen year (hours), and Send stays disabled until it ends.
    if [[ "$WALLET_STATE" != "created" ]]; then
        fresh_install "$APK_PATH" || { fail "install failed: ${INSTALL_ERROR:-timeout}"; return; }
        launch_app
        create_wallet || { fail "no wallet to send from: $FLOW_ERROR"; return; }
    fi
    goto_wallet_home || { fail "$FLOW_ERROR"; return; }
    if ! network_reachable; then
        skip "the emulator has no network, and Send stays disabled until the wallet has synced"
        return
    fi
    dest="$(expected_restore_address)"
    [[ -n "$dest" ]] || { fail "no destination address (PINNED_BIP84_0_0 in $ADDRESS_VECTOR_FILE)"; return; }
    sabotaged G && dest="dgb1qnotanaddress"

    ui_dump && ui_tap "$T_WALLET_SEND" || { fail "could not press '$T_WALLET_SEND' on the wallet screen"; return; }
    ui_wait 20 "$T_SEND_TITLE" || { fail "the Send screen ('$T_SEND_TITLE') did not open"; return; }
    ui_tap "$T_SEND_HINT" || { fail "the Send screen has no recipient field ('$T_SEND_HINT')"; return; }
    sleep 0.8
    ui_type "$dest" || { fail "the app's window lost focus while entering the address"; return; }
    ui_dump
    if ! ui_has "$dest"; then
        # A dropped keystroke is the emulator's, not the app's: erase and type once more.
        log "G: address field did not read back; retyping"
        ui_erase 70; sleep 0.5
        ui_type "$dest" || { fail "the app's window lost focus while entering the address"; return; }
    fi
    hide_keyboard
    ui_dump
    ui_has "$dest" || { fail "the recipient field does not show the address that was typed"; return; }
    ui_tap "$T_SEND_AMOUNT_HINT" || { fail "the Send screen has no amount field"; return; }
    sleep 0.8
    ui_type "$amount" || { fail "the app's window lost focus while entering the amount"; return; }
    hide_keyboard
    ui_dump
    ui_has "$amount" || { fail "the amount field does not show the amount that was typed"; return; }

    # Review is enabled only with peers and a finished sync.
    deadline=$(( $(date +%s) + SYNC_TIMEOUT ))
    while :; do
        if ui_dump && app_has_focus; then
            if screen_is_unlock; then
                fail "the app locked itself while the Send screen was being used"; return
            fi
            if ui_has "$T_SEND_REVIEW" && ! ui_has --prefix "$T_SEND_WAIT_SYNC" && ! ui_has --prefix "$T_SEND_NO_PEERS"; then
                break
            fi
            # Keep the session alive: the wallet locks after a minute without a touch.
            ui_tap "$T_SEND_TITLE" > /dev/null 2>&1
        fi
        app_unhealthy && { fail "$HEALTH_REASON"; return; }
        if [[ $(date +%s) -ge $deadline ]]; then
            fail "the wallet did not finish syncing within ${SYNC_TIMEOUT} s, so Send stayed disabled ('$T_SEND_WAIT_SYNC…')"
            return
        fi
        sleep 10
    done

    ui_tap "$T_SEND_REVIEW" || { fail "could not press '$T_SEND_REVIEW'"; return; }
    seen="$(ui_wait_any 40 "$T_SEND_FAIL_TITLE" "$T_SEND_CONFIRM_TITLE" "$T_SEND_INVALID_ADDR")" || {
        app_unhealthy && { fail "after Review: $HEALTH_REASON"; return; }
        fail "Review produced neither a refusal nor a confirmation: $(ui_texts | head -6 | tr '\n' '/')"; return; }
    if [[ "$seen" == "$T_SEND_CONFIRM_TITLE" ]]; then
        ui_key KEYCODE_BACK
        fail "an EMPTY wallet was offered a confirmation for $amount DGB instead of the insufficient-funds refusal"
        return
    fi
    if [[ "$seen" == "$T_SEND_INVALID_ADDR" ]]; then
        fail "expected the insufficient-funds refusal, the app answered '$T_SEND_INVALID_ADDR' for $dest"
        return
    fi
    screen_send_refused_insufficient || {
        fail "expected the insufficient-funds refusal ('$T_SEND_FAIL_TITLE' / '$T_SEND_FAIL_INSUFFICIENT…'), the sheet says: $(ui_texts | head -4 | tr '\n' '/')"
        return; }
    app_unhealthy && { fail "$HEALTH_REASON"; return; }
    ui_tap "Done" > /dev/null 2>&1
    G_NOTE="no coins sent: the empty wallet was refused with the insufficient-funds message"
    pass
}

# ── H: 60-second stability soak ───────────────────────────────────────────────
# The app stays the foreground window, in the same process, for 60 s on the wallet screen with
# no crash and no ANR. (The wallet's own one-minute auto-lock may put the unlock screen up at
# the end; that is the app working, and it is still the app's window.)
test_H() {
    local pid0 pid1 t0 focus
    ensure_wallet || { fail "no wallet to soak: $FLOW_ERROR"; return; }
    ui_wait_for 15 screen_is_wallet || { fail "not on the wallet screen at the start of the soak"; return; }
    pid0="$(app_pid)"
    [[ -n "$pid0" ]] || { fail "the app has no process at the start of the soak"; return; }
    t0=$(date +%s)
    while [[ $(( $(date +%s) - t0 )) -lt $SOAK_SECONDS ]]; do
        sleep 10
        if sabotaged H && [[ $(( $(date +%s) - t0 )) -ge 20 ]]; then ui_key KEYCODE_HOME; sleep 1; fi
        focus="$(focused_window)"
        if [[ "$focus" != "$PACKAGE/"* ]]; then
            app_unhealthy && { fail "$(( $(date +%s) - t0 )) s into the soak: $HEALTH_REASON"; return; }
            fail "$(( $(date +%s) - t0 )) s into the soak the foreground window was '$focus', not the app"
            return
        fi
        app_unhealthy && { fail "$(( $(date +%s) - t0 )) s into the soak: $HEALTH_REASON"; return; }
    done
    pid1="$(app_pid)"
    [[ "$pid1" == "$pid0" ]] || { fail "the app's process changed during the soak (pid $pid0 -> ${pid1:-none}): it died and restarted"; return; }
    ui_wait_for 15 screen_is_wallet_or_unlock || { fail "after the soak the app shows neither the wallet nor its lock screen: $(ui_texts | head -4 | tr '\n' '/')"; return; }
    pass
}

# ── Dispatcher ────────────────────────────────────────────────────────────────

# run_scenario <id> — sets RESULT / REASON / NOTE. Always clears a screen lock it set.
run_scenario() {
    local id="$1"
    RESULT=""; REASON=""; NOTE=""; FLOW_ERROR=""; HEALTH_REASON=""
    B_NOTE=""; F_NOTE=""; G_NOTE=""
    clear_logs
    case "$id" in
        A) test_A ;; B) test_B ;; C) test_C ;; D) test_D ;;
        E) test_E ;; F) test_F ;; G) test_G ;; H) test_H ;;
        *) skip "unknown test" ;;
    esac
    [[ -n "$RESULT" ]] || fail "the scenario ended without a verdict"
    case "$id" in B) NOTE="$B_NOTE" ;; F) NOTE="$F_NOTE" ;; G) NOTE="$G_NOTE" ;; esac
    if [[ "$RESULT" == "FAIL" ]]; then save_failure_artifacts "$id"; fi
    if [[ "$DEVICE_LOCK_SET" == 1 ]]; then
        # The key tied to the lock dies with it, so the wallet goes too.
        uninstall_app
        device_lock_clear || { RESULT="FAIL"; REASON="${REASON:+$REASON; }COULD NOT CLEAR the emulator screen-lock PIN ($DEVICE_PIN) this scenario set"; }
    fi
    if sabotaged F && [[ "$id" == F ]]; then
        adbx shell "iptables -F OUTPUT; ip6tables -F OUTPUT" > /dev/null 2>&1
        adbt 20 unroot > /dev/null 2>&1; sleep 2; adbt 30 wait-for-device > /dev/null 2>&1
    fi
}
