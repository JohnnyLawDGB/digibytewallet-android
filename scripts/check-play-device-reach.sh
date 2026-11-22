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
#   2. the manifest asks for an OpenGL ES version not on ALLOWED_GL_ES_VERSIONS below. aapt2 prints
#      <uses-feature android:glEsVersion> on a line of its own (uses-gl-es), not as a uses-feature
#      line, and prints it whatever android:required says; Play offers the app only to devices with
#      at least that version, or
#   3. the manifest restricts screens: a <supports-screens> that leaves out a size or any density,
#      or any <compatible-screens>. The only accepted answer is REQUIRED_SCREENS, all densities, or
#   4. the APK's lib/ directories are not exactly wallet.nativeAbis (gradle.properties), or one of
#      them lacks libcore-lib.so.
#
# To make a new feature pass: if the app can run without it, declare it in
# app/src/main/AndroidManifest.xml with android:required="false". Add it to the list below only
# if the app cannot run without it, and say why beside it.
#
# An .aab is read too (the release build hands Play one). Its native directories are base/lib/, and
# its manifest and resource table are in the protobuf form, which aapt2 reads when they sit where
# an APK keeps them: the two files are copied into a scratch archive and that is what gets dumped.
#
# Usage: scripts/check-play-device-reach.sh [apk-or-aab ...]
#        With no argument it checks every app/build/outputs/apk/mainnet*/debug/*.apk
#        (./gradlew :app:assembleMainnetSideloadDebug :app:assembleMainnetPlayDebug).
#        AAPT2=/path/to/aapt2 overrides the SDK lookup.
#
#        scripts/check-play-device-reach.sh --self-test
#        Needs no APK and no SDK. Feeds the manifest checks canned aapt2 output (taken from real
#        scratch manifests linked with aapt2 36.1.0) and requires FAIL on each restricted one and
#        a pass on the plain one. Run it after changing the checks.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# android.hardware.faketouch: implied for every app that does not ask for a real touchscreen. It is
# the weakest pointer requirement there is (a mouse or a remote satisfies it) and the UI does need
# a pointer.
ALLOWED_REQUIRED_FEATURES="android.hardware.faketouch"

# OpenGL ES versions the app may ask for, as aapt2 prints them (0x20000 is 2.0). None: the app
# draws with Compose and asks for no version, so any uses-gl-es line came from a dependency.
ALLOWED_GL_ES_VERSIONS=""

# What aapt2 prints for an app that restricts no screen size. Anything else narrows Play's list.
REQUIRED_SCREENS="'small' 'normal' 'large' 'xlarge'"

OUR_LIBRARY="libcore-lib.so"

die() { echo "FAIL: $*" >&2; exit 1; }

status=0
fail() { echo "FAIL: $*"; status=1; }

# The manifest half: $1 is the whole output of aapt2 dump badging. Calls fail() for each problem.
# Returns 1 only when the text is not in the format this parses.
check_badging() {
    local badging="$1" required extra feature reason gles version screens density compatible
    # Proof that the output is the format this parses: every APK has a package line, every app has
    # at least one feature line (faketouch or touchscreen), and aapt2 always prints the two screens
    # lines. Without this, a change in aapt2's wording would read as "nothing required" and pass.
    grep -q "^package: name=" <<<"$badging" || { fail "aapt2 output has no package line"; return 1; }
    grep -qE "^[[:space:]]*uses-feature(-not-required)?: name='" <<<"$badging" \
        || { fail "aapt2 output lists no feature at all; its format may have changed"; return 1; }
    { grep -q "^supports-screens:" <<<"$badging" && grep -q "^supports-any-density:" <<<"$badging"; } \
        || { fail "aapt2 output has no supports-screens / supports-any-density line; its format may have changed"; return 1; }

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

    # Any line that starts uses-gl-es counts, whatever follows it: a line this cannot read is a
    # requirement it cannot clear.
    gles=$(sed -n "s/^[[:space:]]*uses-gl-es:[[:space:]]*//p" <<<"$badging")
    if [ -n "$gles" ]; then
        while IFS= read -r version; do
            version=$(tr -d "'" <<<"$version")
            case " $ALLOWED_GL_ES_VERSIONS " in
                *" $version "*) echo "ok: OpenGL ES version $version (allowed)" ;;
                *) fail "the manifest asks for OpenGL ES version $version (uses-gl-es)"
                   echo "      Play offers the app only to devices with at least that version. Find the manifest that"
                   echo "      declares android:glEsVersion (the merged-manifest report names it) and remove the element"
                   echo "      with tools:node=\"remove\", or add the version to ALLOWED_GL_ES_VERSIONS and say why." ;;
            esac
        done <<<"$gles"
    else
        echo "ok: no OpenGL ES version asked for"
    fi

    screens=$(sed -n "s/^supports-screens:[[:space:]]*//p" <<<"$badging" | sed 's/[[:space:]]*$//')
    density=$(sed -n "s/^supports-any-density:[[:space:]]*//p" <<<"$badging" | sed 's/[[:space:]]*$//')
    compatible=$(sed -n "s/^compatible-screens:[[:space:]]*//p" <<<"$badging")
    local screens_ok=1
    if [ "$screens" != "$REQUIRED_SCREENS" ]; then
        fail "supports-screens is [$screens], not [$REQUIRED_SCREENS]: a <supports-screens> element leaves a screen size out"
        screens_ok=0
    fi
    if [ "$density" != "'true'" ]; then
        fail "supports-any-density is [$density], not ['true']"
        screens_ok=0
    fi
    if grep -q "^compatible-screens:" <<<"$badging"; then
        fail "the manifest has <compatible-screens> [$compatible]: Play offers the app to those screens only"
        screens_ok=0
    fi
    if [ "$screens_ok" -eq 1 ]; then
        echo "ok: screens: every size, any density, no compatible-screens list"
    fi
    return 0
}

# The native half: $1 is the archive's entry list, $2 the directory the ABI directories sit in
# ("lib/" in an APK, "base/lib/" in an .aab).
check_native_dirs() {
    local entries="$1" prefix="$2" have_abis abi
    have_abis=$(sed -n "s|^$prefix\([^/]*\)/.*|\1|p" <<<"$entries" | LC_ALL=C sort -u)
    if [ "$have_abis" != "$want_abis" ]; then
        fail "the archive carries native code for [$(tr '\n' ' ' <<<"$have_abis")] but wallet.nativeAbis is [$(tr '\n' ' ' <<<"$want_abis")]"
    else
        echo "ok: ABIs: $(tr '\n' ' ' <<<"$have_abis")"
    fi
    while IFS= read -r abi; do
        [ -n "$abi" ] || continue
        grep -qxF "$prefix$abi/$OUR_LIBRARY" <<<"$entries" \
            || fail "$prefix$abi/ has no $OUR_LIBRARY: a device that picks $abi installs the app and stops at System.loadLibrary"
    done <<<"$have_abis"
}

# ── --self-test ─────────────────────────────────────────────────────────────────────────────────
# Canned aapt2 output. $1: lines before the feature group, $2: lines inside it, $3: the
# supports-screens value, $4: the any-density value, $5: lines after them.
self_test_badging() {
    printf '%s\n' "package: name='fx.reach' versionCode='' versionName='' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'" \
        "minSdkVersion:'26'" "targetSdkVersion:'36'"
    [ -z "$1" ] || printf '%s\n' "$1"
    printf '%s\n' "application: label='fx' icon=''" "feature-group: label=''"
    [ -z "$2" ] || printf '%s\n' "$2"
    printf '%s\n' "  uses-feature: name='android.hardware.faketouch'" \
        "  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'" \
        "supports-screens:${3:+ $3}" "supports-any-density: '$4'"
    [ -z "$5" ] || printf '%s\n' "$5"
    printf '%s\n' "locales:" "densities:"
}

self_test() {
    local bad=0 all="$REQUIRED_SCREENS"
    # name, expected status (0 pass, 1 fail), text the output must contain, the canned badging
    expect() {
        local name="$1" want="$2" needle="$3" badging="$4" out got
        out=$(status=0; check_badging "$badging"; echo "status=$status")
        got=$(sed -n 's/^status=//p' <<<"$out")
        if [ "$got" != "$want" ] || ! grep -qF -- "$needle" <<<"$out"; then
            echo "SELF-TEST FAIL: $name: wanted status $want and \"$needle\", got:"; sed 's/^/    /' <<<"$out"; bad=1
        else
            echo "self-test ok: $name -> $([ "$want" = 0 ] && echo pass || echo FAIL) ($needle)"
        fi
    }
    expect "plain manifest" 0 "ok: screens: every size" "$(self_test_badging "" "" "$all" true "")"
    expect "required feature off the list" 1 "required feature android.hardware.location" \
        "$(self_test_badging "" "  uses-feature: name='android.hardware.location'" "$all" true "")"
    expect "glEsVersion 3.0" 1 "OpenGL ES version 0x30000" \
        "$(self_test_badging "" "  uses-gl-es: '0x30000'" "$all" true "")"
    expect "glEsVersion 2.0 with required=false (aapt2 prints it the same)" 1 "OpenGL ES version 0x20000" \
        "$(self_test_badging "" "  uses-gl-es: '0x20000'" "$all" true "")"
    expect "supports-screens without small and xlarge" 1 "supports-screens is ['normal' 'large']" \
        "$(self_test_badging "" "" "'normal' 'large'" true "")"
    expect "supports-screens with requiresSmallestWidthDp (aapt2 prints no size)" 1 "supports-screens is []" \
        "$(self_test_badging "" "" "" true "requires-smallest-width:'600'")"
    expect "anyDensity=false" 1 "supports-any-density is ['false']" \
        "$(self_test_badging "" "" "$all" false "")"
    expect "compatible-screens" 1 "<compatible-screens> ['300/320']" \
        "$(self_test_badging "compatible-screens:'300/320'" "" "$all" true "")"
    expect "no screens lines at all" 1 "no supports-screens / supports-any-density line" \
        "$(self_test_badging "" "" "$all" true "" | grep -v '^supports-')"
    if [ "$bad" -eq 0 ]; then echo "PASS: self-test"; else echo "FAIL: self-test"; fi
    return "$bad"
}

if [ "${1:-}" = "--self-test" ]; then self_test; exit $?; fi

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

scratch=$(mktemp -d) || die "mktemp failed"
trap 'rm -rf "$scratch"' EXIT

for apk in "${apks[@]}"; do
    echo "== $apk"
    [ -f "$apk" ] || { fail "no such file"; continue; }
    entries=$(unzip -Z1 "$apk" 2>/dev/null) || { fail "cannot list the archive's entries"; continue; }

    readable="$apk"; lib_prefix="lib/"
    if grep -qxF "base/manifest/AndroidManifest.xml" <<<"$entries"; then
        # An .aab. aapt2 reads a protobuf manifest and resource table when they sit at an APK's
        # paths, so put the base module's two files there.
        lib_prefix="base/lib/"
        readable="$scratch/aab-base.apk"
        rm -f "$readable"
        python3 -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as bundle, zipfile.ZipFile(sys.argv[2], "w") as out:
    out.writestr("AndroidManifest.xml", bundle.read("base/manifest/AndroidManifest.xml"))
    out.writestr("resources.pb", bundle.read("base/resources.pb"))
' "$apk" "$readable" || { fail "cannot read the bundle's base manifest and resource table"; continue; }
        echo "   (an .aab: the base module's manifest and resource table; native code under base/lib/)"
    fi

    badging=$("$AAPT2_BIN" dump badging "$readable" 2>&1) || { fail "aapt2 dump badging failed: $badging"; continue; }
    check_badging "$badging" || continue
    check_native_dirs "$entries" "$lib_prefix"
done

if [ "$status" -eq 0 ]; then echo "PASS: device reach is what the lists say"; fi
exit "$status"
