#!/usr/bin/env bash
#
# Google Play offers an app only to devices that have every feature the app REQUIRES and one of
# the ABIs it carries native code for. Both lists are easy to grow without meaning to:
#
#   - A required feature needs no <uses-feature> line. The build tools imply one from a permission
#     (CAMERA -> camera + autofocus, a location permission -> location, BLUETOOTH -> bluetooth, ...)
#     or from an activity attribute (screenOrientation="portrait" -> screen.portrait). Through
#     4.1.5 the portrait lock made android.hardware.screen.portrait required, and nothing in the
#     manifest said so.
#   - An ABI directory needs no build of ours. A dependency that ships lib/x86/ puts that directory
#     in the APK, the APK then says it supports x86, and a 32-bit x86 device loads lib/x86/, where
#     our own libcore-lib.so is missing: the app stops at System.loadLibrary("core-lib"). Through
#     4.1.5 the APK carried exactly that directory.
#
# So this reads the BUILT APK, the way Play does (aapt2 dump badging, which lists implied features
# with the declared ones), and fails if:
#
#   1. a required feature is not on ALLOWED_REQUIRED_FEATURES below, or
#   2. the APK's lib/ directories are not exactly wallet.nativeAbis (gradle.properties), or one of
#      them lacks libcore-lib.so.
#
# To make a new feature pass: if the app can run without it, declare it in
# app/src/main/AndroidManifest.xml with android:required="false". Add it to the list below only
# if the app cannot run without it, and say why beside it.
#
# Usage: scripts/check-play-device-reach.sh [apk ...]
#        With no argument it checks every app/build/outputs/apk/mainnet*/debug/*.apk
#        (./gradlew :app:assembleMainnetSideloadDebug :app:assembleMainnetPlayDebug).
#        AAPT2=/path/to/aapt2 overrides the SDK lookup.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# android.hardware.faketouch: implied for every app that does not ask for a real touchscreen. It is
# the weakest pointer requirement there is (a mouse or a remote satisfies it) and the UI does need
# a pointer.
ALLOWED_REQUIRED_FEATURES="android.hardware.faketouch"

OUR_LIBRARY="libcore-lib.so"

die() { echo "FAIL: $*" >&2; exit 1; }

find_aapt2() {
    if [ -n "${AAPT2:-}" ]; then echo "$AAPT2"; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$REPO_ROOT/local.properties" ]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tail -n 1)
    fi
    [ -n "$sdk" ] || return 1
    # Newest build-tools that has an aapt2.
    local dir
    while IFS= read -r dir; do
        if [ -x "$sdk/build-tools/$dir/aapt2" ]; then echo "$sdk/build-tools/$dir/aapt2"; return; fi
    done < <(ls -1 "$sdk/build-tools" 2>/dev/null | sort -rV)
    return 1
}

AAPT2_BIN=$(find_aapt2) || die "aapt2 not found: set ANDROID_HOME, or sdk.dir in local.properties, or AAPT2"
[ -x "$AAPT2_BIN" ] || die "aapt2 at $AAPT2_BIN is not executable"

want_abis=$(sed -n 's/^wallet\.nativeAbis=//p' "$REPO_ROOT/gradle.properties" | tr ',' '\n' | sed '/^$/d' | LC_ALL=C sort)
[ -n "$want_abis" ] || die "wallet.nativeAbis is not set in gradle.properties"

if [ "$#" -gt 0 ]; then
    apks=("$@")
else
    shopt -s nullglob
    apks=("$REPO_ROOT"/app/build/outputs/apk/mainnet*/debug/*.apk)
    shopt -u nullglob
    # Finding nothing to check is not a pass.
    [ "${#apks[@]}" -gt 0 ] || die "no APK under app/build/outputs/apk/mainnet*/debug/: build one first, or name one"
fi

status=0
fail() { echo "FAIL: $*"; status=1; }

for apk in "${apks[@]}"; do
    echo "== $apk"
    [ -f "$apk" ] || { fail "no such file"; continue; }

    badging=$("$AAPT2_BIN" dump badging "$apk" 2>&1) || { fail "aapt2 dump badging failed: $badging"; continue; }
    # Proof that the output is the format this parses: every APK has a package line, and every
    # app has at least one feature line (faketouch or touchscreen). Without this, a change in
    # aapt2's wording would read as "no required features" and pass.
    grep -q "^package: name=" <<<"$badging" || { fail "aapt2 output has no package line"; continue; }
    grep -qE "^[[:space:]]*uses-feature(-not-required)?: name='" <<<"$badging" \
        || { fail "aapt2 output lists no feature at all; its format may have changed"; continue; }

    required=$(sed -n "s/^[[:space:]]*uses-feature\(-sdk23\)\{0,1\}: name='\([^']*\)'.*/\2/p" <<<"$badging" | LC_ALL=C sort -u)
    extra=""
    while IFS= read -r feature; do
        [ -n "$feature" ] || continue
        case " $ALLOWED_REQUIRED_FEATURES " in
            *" $feature "*) ;;
            *) extra="$extra $feature" ;;
        esac
    done <<<"$required"
    if [ -n "$extra" ]; then
        for feature in $extra; do
            reason=$(sed -n "s/^[[:space:]]*uses-implied-feature: name='$feature' reason='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)
            fail "required feature $feature${reason:+ (implied: $reason)}"
        done
        echo "      Play hides the app from every device without it. If the app runs without it, declare"
        echo "      <uses-feature android:name=\"...\" android:required=\"false\" /> in app/src/main/AndroidManifest.xml."
    else
        echo "ok: required features: $(tr '\n' ' ' <<<"$required")"
    fi

    entries=$(unzip -Z1 "$apk" 2>/dev/null) || { fail "cannot list the APK's entries"; continue; }
    have_abis=$(sed -n 's|^lib/\([^/]*\)/.*|\1|p' <<<"$entries" | LC_ALL=C sort -u)
    if [ "$have_abis" != "$want_abis" ]; then
        fail "the APK carries native code for [$(tr '\n' ' ' <<<"$have_abis")] but wallet.nativeAbis is [$(tr '\n' ' ' <<<"$want_abis")]"
    else
        echo "ok: ABIs: $(tr '\n' ' ' <<<"$have_abis")"
    fi
    while IFS= read -r abi; do
        [ -n "$abi" ] || continue
        grep -qxF "lib/$abi/$OUR_LIBRARY" <<<"$entries" \
            || fail "lib/$abi/ has no $OUR_LIBRARY: a device that picks $abi installs the app and stops at System.loadLibrary"
    done <<<"$have_abis"
done

if [ "$status" -eq 0 ]; then echo "PASS: device reach is what the lists say"; fi
exit "$status"
