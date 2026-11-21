#!/usr/bin/env bash
# scripts/test-helpers/device.sh
#
# The only place the pre-publish suite talks to adb.
#
#   - Every call carries `-s $SERIAL` and a `timeout`. A call that does not return is a failed
#     call (status 124), never a hung suite: on 2026-11-21 one `adb install` blocked a run for
#     1h42m because nothing bounded it.
#   - $SERIAL must be an emulator. Physical phones are attached to the machines this runs on;
#     `require_emulator_serial` refuses anything else before the first command is sent.
#   - The app's Compose UI ignores `input tap` (a zero-length press). `ui_tap` sends
#     `input swipe X Y X Y 120`, a held press, which is what registers.
#   - Controls are found by their visible text or content-description in a `uiautomator dump`,
#     never by fixed coordinates, so the suite does not depend on the screen size.

HELPERS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UIDUMP="$HELPERS_DIR/uidump.py"

ADB_TIMEOUT="${ADB_TIMEOUT:-30}"          # one ordinary adb call
ADB_INSTALL_TIMEOUT="${ADB_INSTALL_TIMEOUT:-180}"
# The raw UI dump can hold a recovery phrase (the seed screen is readable by uiautomator), so
# it has no default location: the caller points UI_DUMP_FILE into `ui_dump_dir_create`'s
# directory before the first dump.
UI_DUMP_FILE="${UI_DUMP_FILE:-}"
SUITE_LOG="${SUITE_LOG:-/dev/null}"

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >> "$SUITE_LOG"; }

# ── adb ───────────────────────────────────────────────────────────────────────

require_emulator_serial() {
    local serial="$1"
    if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
        echo "REFUSED: '$serial' is not an emulator serial; this suite never drives a physical device." >&2
        return 1
    fi
}

# adbx <adb args...> — bounded, always addressed to $SERIAL.
adbx() {
    require_emulator_serial "${SERIAL:-}" || return 125
    timeout -k 5 "$ADB_TIMEOUT" adb -s "$SERIAL" "$@"
}

# adbt <seconds> <adb args...> — the same with its own bound.
adbt() {
    local t="$1"; shift
    require_emulator_serial "${SERIAL:-}" || return 125
    timeout -k 5 "$t" adb -s "$SERIAL" "$@"
}

# The serial names an emulator AND the thing answering on it says it is one.
verify_emulator() {
    require_emulator_serial "${SERIAL:-}" || return 1
    local qemu
    qemu="$(adbx shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
    [[ "$qemu" == "1" ]] || qemu="$(adbx shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
    if [[ "$qemu" != "1" ]]; then
        echo "REFUSED: $SERIAL does not report itself as an emulator (ro.kernel.qemu/ro.boot.qemu)." >&2
        return 1
    fi
}

# adb_install [-r] <apk> — one retry, each attempt bounded. Prints the failure on stderr.
adb_install() {
    local out attempt
    for attempt in 1 2; do
        out="$(adbt "$ADB_INSTALL_TIMEOUT" install "$@" 2>&1)"
        local rc=$?
        if [[ $rc -eq 0 && "$out" == *Success* ]]; then
            return 0
        fi
        log "install attempt $attempt failed (rc=$rc): ${out##*$'\n'}"
        [[ $rc -eq 124 ]] && log "install attempt $attempt TIMED OUT after ${ADB_INSTALL_TIMEOUT}s"
    done
    INSTALL_ERROR="${out##*$'\n'}"
    return 1
}

app_pid() { adbx shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# The window that has input focus, e.g. "app.aroundtheblock.wallet.debug/…MainActivity".
focused_window() {
    adbx shell dumpsys window 2>/dev/null | tr -d '\r' | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | head -1
}

app_has_focus() { [[ "$(focused_window)" == "$PACKAGE/"* ]]; }

# ── UI: read ──────────────────────────────────────────────────────────────────

# ui_dump_dir_create — prints a new directory only this user can enter, in memory where the
# machine has a tmpfs (the user's runtime directory, else /dev/shm), for the raw dump. A raw
# dump of the seed screen holds the phrase, so it must not land on a disk or be readable by
# anyone else. With no tmpfs it falls back to the temp directory and says so on stderr.
ui_dump_dir_create() {
    local base
    for base in "${XDG_RUNTIME_DIR:-}" /dev/shm; do
        if [[ -n "$base" && -d "$base" && -w "$base" ]]; then
            (umask 077; mktemp -d "$base/prepub-ui-XXXXXX") && return 0
        fi
    done
    echo "WARNING: no tmpfs (XDG_RUNTIME_DIR, /dev/shm); the raw UI dump goes to ${TMPDIR:-/tmp}, mode 600" >&2
    (umask 077; mktemp -d -t prepub-ui-XXXXXX)
}

# ui_dump — refresh $UI_DUMP_FILE from the device. uiautomator waits for the UI to go idle and
# gives up while something animates (the sync indicator does, forever), so it gets a few tries.
# The file is written under umask 077 whatever the caller's umask is: the redirection creates
# it, and `mv` carries that mode onto $UI_DUMP_FILE.
ui_dump() {
    local attempt
    [[ -n "$UI_DUMP_FILE" ]] || { log "ui_dump: UI_DUMP_FILE is not set"; return 1; }
    for attempt in 1 2 3 4; do
        if (umask 077
            adbt 20 exec-out uiautomator dump /dev/tty 2>/dev/null \
                | sed 's/UI hierchary dumped to: \/dev\/tty$//' > "$UI_DUMP_FILE.tmp") \
            && grep -q '<hierarchy' "$UI_DUMP_FILE.tmp"; then
            mv -f "$UI_DUMP_FILE.tmp" "$UI_DUMP_FILE"
            return 0
        fi
        sleep 1
    done
    rm -f "$UI_DUMP_FILE.tmp"
    ui_dump_scrub
    return 1
}

# Empty the last dump (kept mode 600). Called as soon as a screen that showed the phrase has
# been read, so the words do not sit in the file until the next dump.
ui_dump_scrub() {
    [[ -n "$UI_DUMP_FILE" ]] || return 0
    (umask 077; : > "$UI_DUMP_FILE")
    chmod 600 "$UI_DUMP_FILE" 2>/dev/null
    return 0
}

# Pure readers of the last dump. `--contains` / `--prefix` may precede the text.
ui_has()    { python3 "$UIDUMP" has "$UI_DUMP_FILE" "$@"; }
ui_center() { python3 "$UIDUMP" center "$UI_DUMP_FILE" "$@"; }
ui_texts()  { python3 "$UIDUMP" texts "$UI_DUMP_FILE"; }
ui_after()  { python3 "$UIDUMP" after "$UI_DUMP_FILE" "$@"; }
ui_match()  { python3 "$UIDUMP" match "$UI_DUMP_FILE" "$@"; }

# ui_wait <seconds> [--contains|--prefix] <text> — poll until the text is on screen.
ui_wait() {
    local deadline=$(( $(date +%s) + $1 )); shift
    while :; do
        ui_dump && ui_has "$@" && return 0
        [[ $(date +%s) -ge $deadline ]] && return 1
        sleep 1
    done
}

# ui_wait_for <seconds> <predicate> [args...] — poll until a screen predicate (screen_is_…)
# holds on a fresh dump. A screen is drawn in steps: a title can be in one dump and the buttons
# under it only in the next, so "wait for the title, then test the whole screen once on that
# same dump" fails on a screen that is merely not finished. This tests the WHOLE predicate on
# every poll.
ui_wait_for() {
    local deadline=$(( $(date +%s) + $1 )); shift
    while :; do
        ui_dump && "$@" && return 0
        [[ $(date +%s) -ge $deadline ]] && return 1
        sleep 1
    done
}

# ui_wait_any <seconds> <text>... — poll until one of the (exact) texts is on screen; prints it.
ui_wait_any() {
    local deadline=$(( $(date +%s) + $1 )); shift
    local t
    while :; do
        if ui_dump; then
            for t in "$@"; do
                if ui_has --contains "$t"; then printf '%s\n' "$t"; return 0; fi
            done
        fi
        [[ $(date +%s) -ge $deadline ]] && return 1
        sleep 1
    done
}

# ui_wait_gone <seconds> [--contains] <text>
ui_wait_gone() {
    local deadline=$(( $(date +%s) + $1 )); shift
    while :; do
        ui_dump && ! ui_has "$@" && return 0
        [[ $(date +%s) -ge $deadline ]] && return 1
        sleep 1
    done
}

# ── UI: act ───────────────────────────────────────────────────────────────────

# A held press. `input tap` is ignored by this app's Compose UI.
ui_press_xy() { adbx shell input swipe "$1" "$2" "$1" "$2" 120 > /dev/null 2>&1; }

# ui_tap [--contains|--prefix] [--nth N] <text> — press the node in the LAST dump.
ui_tap() {
    local xy
    xy="$(ui_center "$@")" || return 1
    # shellcheck disable=SC2086
    ui_press_xy $xy
}

# ui_wait_tap <seconds> [flags] <text> — wait for it, then press it.
ui_wait_tap() {
    local t="$1"; shift
    ui_wait "$t" "$@" || return 1
    ui_tap "$@"
}

# Scroll the content up by half a screen (a real drag: distinct start and end).
ui_scroll_down() {
    local size w h
    size="$(adbx shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/.*: \([0-9]*x[0-9]*\).*/\1/p' | tail -1)"
    w="${size%x*}"; h="${size#*x}"
    [[ -n "$w" && -n "$h" ]] || return 1
    adbx shell input swipe $((w / 2)) $((h * 7 / 10)) $((w / 2)) $((h * 3 / 10)) 300 > /dev/null 2>&1
}

# ui_scroll_to <tries> [flags] <text> — scroll down until the text is on screen.
ui_scroll_to() {
    local tries="$1"; shift
    local i
    for ((i = 0; i <= tries; i++)); do
        ui_dump && ui_has "$@" && return 0
        ui_scroll_down
        sleep 1
    done
    return 1
}

# Type into whatever has focus, but only while that is the app's own window: text sent while a
# system dialog or another app is in front would land there instead. Long strings go in short
# pieces: one long `input text` drops characters on a busy emulator.
ui_type() {
    local text="$1" piece
    while [[ -n "$text" ]]; do
        app_has_focus || { log "ui_type refused: focus is '$(focused_window)'"; return 1; }
        piece="${text:0:8}"; text="${text:8}"
        adbx shell input text "$piece" > /dev/null 2>&1 || return 1
        sleep 0.3
    done
}

# Delete <n> characters before the cursor in the focused field.
ui_erase() {
    local n="$1" keys=""
    app_has_focus || return 1
    adbx shell input keyevent KEYCODE_MOVE_END > /dev/null 2>&1
    while [[ $n -gt 0 ]]; do keys+=" 67"; n=$((n - 1)); done
    # shellcheck disable=SC2086
    adbx shell input keyevent $keys > /dev/null 2>&1
}

ui_key() { adbx shell input keyevent "$1" > /dev/null 2>&1; }

# The app PIN pad is Compose buttons labelled with their digit.
ui_enter_app_pin() {
    local pin="$1" i d
    ui_dump || return 1
    for ((i = 0; i < ${#pin}; i++)); do
        d="${pin:$i:1}"
        ui_tap "$d" || return 1
        sleep 0.25
    done
}
