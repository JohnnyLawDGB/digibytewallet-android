#!/usr/bin/env bash
# scripts/test-helpers/emulator-setup.sh
#
# Functions for managing Android emulators: download images, create AVDs,
# boot/kill emulators, wait for boot completion.

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"

# Map API level to system image package
image_package() {
    echo "system-images;android-$1;google_apis;x86_64"
}

# Map API level to AVD name
avd_name() {
    echo "dgb-test-api$1"
}

# Download system image if not present
ensure_system_image() {
    local api="$1"
    local pkg
    pkg=$(image_package "$api")
    local img_dir="$ANDROID_HOME/system-images/android-$api"

    if [[ -d "$img_dir" ]]; then
        return 0
    fi

    echo "  Downloading system image for API $api..."
    yes | "$SDKMANAGER" "$pkg" > /dev/null 2>&1
    echo "  Downloaded."
}

# Create AVD if not present
ensure_avd() {
    local api="$1"
    local name
    name=$(avd_name "$api")

    if "$AVDMANAGER" list avd -c 2>/dev/null | grep -q "^${name}$"; then
        return 0
    fi

    echo "  Creating AVD: $name..."
    echo "no" | "$AVDMANAGER" create avd \
        -n "$name" \
        -k "$(image_package "$api")" \
        -d pixel_3a \
        --force > /dev/null 2>&1
    echo "  Created."
}

# Boot emulator (kills any existing first)
boot_emulator() {
    local api="$1"
    local name
    name=$(avd_name "$api")

    # Kill any running emulator
    kill_emulator 2>/dev/null || true
    sleep 2

    echo "  Booting emulator: $name..."
    "$EMULATOR" -avd "$name" \
        -no-window -no-audio -gpu swiftshader_indirect \
        -no-snapshot-save -wipe-data \
        > /dev/null 2>&1 &

    EMULATOR_PID=$!
}

# Wait for emulator to fully boot
wait_for_boot() {
    local serial="$1"
    local timeout="${2:-120}"
    local elapsed=0

    while [[ $elapsed -lt $timeout ]]; do
        local boot_complete
        boot_complete=$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [[ "$boot_complete" == "1" ]]; then
            echo "  Emulator booted (${elapsed}s)"
            # Extra settle time for system services
            sleep 5
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done

    echo "  Emulator boot timed out after ${timeout}s"
    return 1
}

# Kill the running emulator
kill_emulator() {
    adb -s emulator-5554 emu kill > /dev/null 2>&1 || true
    if [[ -n "${EMULATOR_PID:-}" ]]; then
        kill "$EMULATOR_PID" 2>/dev/null || true
        wait "$EMULATOR_PID" 2>/dev/null || true
    fi
    sleep 2
}
