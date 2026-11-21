#!/usr/bin/env bash
# scripts/test-helpers/emulator-setup.sh
#
# Emulators for the pre-publish suite: system image, AVD, boot, wait, kill.
#
# The emulator's console port is chosen here (PREPUB_EMULATOR_PORT, default 5554) and passed
# to the emulator with -port, so the serial the suite talks to is the one it started and no
# other. Every wait has a deadline; every adb call goes through device.sh.

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"

EMULATOR_PORT="${PREPUB_EMULATOR_PORT:-5554}"
BOOT_TIMEOUT="${PREPUB_BOOT_TIMEOUT:-300}"
EMULATOR_PID=""

image_package() { echo "system-images;android-$1;google_apis;x86_64"; }
avd_name() { echo "dgb-test-api$1"; }

ensure_system_image() {
    local api="$1"
    [[ -d "$ANDROID_HOME/system-images/android-$api" ]] && return 0
    echo "  Downloading system image for API $api..."
    yes | timeout 1800 "$SDKMANAGER" "$(image_package "$api")" > /dev/null 2>&1
}

ensure_avd() {
    local api="$1" name
    name="$(avd_name "$api")"
    local avds; avds="$(timeout 60 "$EMULATOR" -list-avds 2>/dev/null)"
    if grep -q -x "$name" <<< "$avds"; then
        return 0
    fi
    echo "  Creating AVD: $name..."
    echo "no" | timeout 120 "$AVDMANAGER" create avd -n "$name" -k "$(image_package "$api")" -d pixel_3a --force > /dev/null 2>&1
}

# True when something already answers on the port the suite is about to use.
emulator_port_busy() {
    [[ "$(timeout 10 adb -s "emulator-$EMULATOR_PORT" get-state 2>/dev/null)" == "device" ]] && return 0
    (exec 3<>"/dev/tcp/127.0.0.1/$EMULATOR_PORT") 2>/dev/null && return 0
    return 1
}

# boot_emulator <api> — a clean (-wipe-data), headless emulator on $EMULATOR_PORT.
boot_emulator() {
    local api="$1" name
    name="$(avd_name "$api")"
    echo "  Booting emulator: $name on port $EMULATOR_PORT..."
    "$EMULATOR" -avd "$name" -port "$EMULATOR_PORT" \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        -no-snapshot-save -wipe-data \
        > "${EMULATOR_LOG:-/dev/null}" 2>&1 &
    EMULATOR_PID=$!
}

# wait_for_boot [seconds] — boot completed AND the package manager answers.
wait_for_boot() {
    local limit="${1:-$BOOT_TIMEOUT}" t0
    t0=$(date +%s)
    while [[ $(( $(date +%s) - t0 )) -lt $limit ]]; do
        if [[ -n "$EMULATOR_PID" ]] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
            echo "  The emulator process exited during boot"
            return 1
        fi
        if [[ "$(adbt 10 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] \
            && [[ "$(adbt 15 shell pm path android 2>/dev/null)" == package:* ]]; then
            echo "  Emulator booted ($(( $(date +%s) - t0 ))s)"
            sleep 5
            return 0
        fi
        sleep 3
    done
    echo "  Emulator boot timed out after ${limit}s"
    return 1
}

# Settings every scenario relies on: the screen stays on and unlocked.
prepare_emulator() {
    adbx shell svc power stayon true > /dev/null 2>&1
    adbx shell settings put system screen_off_timeout 1800000 > /dev/null 2>&1
    adbx shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    adbx shell wm dismiss-keyguard > /dev/null 2>&1
    return 0
}

# Kill the emulator this suite started (and only that one).
kill_emulator() {
    if [[ -n "$EMULATOR_PID" ]]; then
        adbt 15 emu kill > /dev/null 2>&1 || true
        local i
        for i in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$EMULATOR_PID" 2>/dev/null || break
            sleep 1
        done
        # Still there: ask, give it a few seconds, then do not ask. An emulator that ignores
        # SIGTERM must not hold the suite between API levels or in its exit trap.
        if kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill "$EMULATOR_PID" 2>/dev/null || true
            for i in 1 2 3 4 5; do
                kill -0 "$EMULATOR_PID" 2>/dev/null || break
                sleep 1
            done
            kill -9 "$EMULATOR_PID" 2>/dev/null || true
        fi
        wait "$EMULATOR_PID" 2>/dev/null || true
        EMULATOR_PID=""
        sleep 2
    fi
}
