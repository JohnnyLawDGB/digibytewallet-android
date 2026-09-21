#!/usr/bin/env bash
# Find a clang that can build AND RUN a host AddressSanitizer binary, and make it the
# `clang` on the PATH.
#
# SOURCE this file (run-host-kats.sh does). On success it exports PATH, sets HOST_CLANG to
# the compiler's absolute path and returns 0. On failure it explains what it tried and
# returns 1. Executed directly, it prints the path and exits with the same status.
#
# WHY "CAN BUILD AND RUN, REPEATEDLY", NOT "EXISTS"
# -------------------------------------------------
# Nearly every KAT calls plain `clang -fsanitize=address`. Having a binary called clang is
# not the same as being able to do that, and the Android NDK — on many machines the only
# clang there is — shows every way it can go wrong. Measured 2026-09-20, Linux 6.8 x86_64,
# against each NDK's own clang:
#
#   r21, r23, r25, r28   do not link: no HOST sanitizer runtime, only the *-android ones
#   r17 (clang 6.0.2)    links; 40 of 40 probe runs started cleanly
#   r27 (clang 18.0.1)   links; 40 of 40
#   r26 (clang 17.0.2)   links; 31 of 40 — nine runs stalled at start-up and had to be killed
#
# So there is no version rule to write down, and a compiler that passes ONE probe can
# still stall one test in five. With no time limit in the KATs' own run.sh files, that is a
# runner that hangs. Each candidate is therefore asked to compile a small heap-and-stdio
# program under ASan and to run it PROBE_RUNS times, each run time-limited; the first
# candidate that does all of that is chosen. A healthy compiler clears it in under a second.
#
# ORDER: `clang` already on the PATH; versioned `clang-NN` on the PATH (a distro often
# installs only that name); the usual LLVM install directories; then every NDK under
# ANDROID_HOME / ANDROID_SDK_ROOT / the SDK's default location, newest first.

_fc_probe_dir=""

PROBE_RUNS="${PROBE_RUNS:-20}"      # 0.775^20 < 1%: the odds a one-in-five staller slips through
PROBE_LIMIT_S="${PROBE_LIMIT_S:-5}"

_fc_bounded() {   # run "$@" quietly, killed after PROBE_LIMIT_S; portable (no coreutils `timeout`)
    "$@" >/dev/null 2>&1 &
    local pid=$! rc
    ( sleep "$PROBE_LIMIT_S"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watchdog=$!
    wait "$pid" 2>/dev/null; rc=$?
    kill "$watchdog" 2>/dev/null; wait "$watchdog" 2>/dev/null
    return $rc
}

_fc_write_probe() {   # $1 = path. The probe does what a KAT does: heap + stdio under ASan.
    cat > "$1" <<'C'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    char *p = malloc(64);
    if (!p) return 2;
    memset(p, 0x5a, 64);
    printf("%d\n", p[63]);
    free(p);
    return 0;
}
C
}

_fc_works() {     # $1 = compiler
    [ -x "$1" ] || return 1
    rm -f "$_fc_probe_dir/probe"
    "$1" -fsanitize=address "$_fc_probe_dir/probe.c" -o "$_fc_probe_dir/probe" >/dev/null 2>&1 || return 1
    local i
    for (( i = 0; i < PROBE_RUNS; i++ )); do
        _fc_bounded "$_fc_probe_dir/probe" || return 1
    done
    return 0
}

_fc_candidates() {
    command -v clang 2>/dev/null
    compgen -c 2>/dev/null | grep -E '^clang-[0-9]+$' | sort -u -t- -k2,2nr | while read -r c; do command -v "$c"; done
    ls -d /usr/lib/llvm-*/bin/clang 2>/dev/null | sort -Vr
    ls -d /usr/local/opt/llvm/bin/clang /opt/homebrew/opt/llvm/bin/clang /usr/local/bin/clang 2>/dev/null
    local sdk
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
        ls -d "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/clang 2>/dev/null | sort -Vr
    done
}

find_host_clang() {
    _fc_probe_dir="$(mktemp -d)"
    _fc_write_probe "$_fc_probe_dir/probe.c"
    local tried="" c seen=" "
    HOST_CLANG=""
    while read -r c; do
        [ -n "$c" ] || continue
        case "$seen" in *" $c "*) continue ;; esac
        seen="$seen$c "
        if _fc_works "$c"; then HOST_CLANG="$c"; break; fi
        tried="$tried    $c\n"
    done < <(_fc_candidates)
    rm -rf "$_fc_probe_dir"

    if [ -z "$HOST_CLANG" ]; then
        {
            echo "error: no clang that can build and run a host AddressSanitizer binary."
            if [ -n "$tried" ]; then
                echo "  tried, and each failed to compile an ASan probe or to run it $PROBE_RUNS times cleanly:"
                printf '%b' "$tried"
                echo "  (most Android NDK clangs fail here: they carry no host sanitizer runtime, or an old one that stalls)"
            else
                echo "  found no clang at all — not on the PATH, not in the usual LLVM directories, not in an NDK."
            fi
            echo "  fix: install a host clang — Debian/Ubuntu: apt install clang   macOS: xcode-select --install"
        } >&2
        return 1
    fi

    # The KATs call plain `clang`. If that name does not already resolve to the chosen
    # compiler, put a directory holding exactly that one name first on the PATH. (Not the
    # compiler's own directory: for /usr/bin that would be a no-op, and for an NDK it would
    # shadow the host's ld, ar and friends with Android ones.)
    if [ "$(command -v clang 2>/dev/null)" != "$HOST_CLANG" ]; then
        HOST_CLANG_SHIM="$(mktemp -d)"
        ln -s "$HOST_CLANG" "$HOST_CLANG_SHIM/clang"
        PATH="$HOST_CLANG_SHIM:$PATH"
    fi
    export PATH HOST_CLANG
    return 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    find_host_clang || exit 1
    echo "$HOST_CLANG"
    [ -n "${HOST_CLANG_SHIM:-}" ] && rm -rf "$HOST_CLANG_SHIM"
    exit 0
fi
