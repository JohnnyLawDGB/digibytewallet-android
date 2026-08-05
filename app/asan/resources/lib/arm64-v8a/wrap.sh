#!/system/bin/sh
# ASan launcher. Packaged ONLY when the build is run with -PasanNative=true.
#
# halt_on_error=0 is the point of this file: paired with -fsanitize-recover=address it lets the
# app keep running past a report, so ONE session surfaces every distinct corruption site instead
# of dying at the first and forcing another restore cycle per defect.
HERE="$(cd "$(dirname "$0")" && pwd)"
export ASAN_OPTIONS=log_to_syslog=false,allow_user_segv_handler=1,halt_on_error=0,abort_on_error=0,detect_leaks=0,print_stacktrace=1,symbolize=0,handle_segv=0
ASAN_LIB=$(ls "$HERE"/libclang_rt.asan-*-android.so)
if [ -f "$HERE/libc++_shared.so" ]; then
    export LD_PRELOAD="$ASAN_LIB $HERE/libc++_shared.so"
else
    export LD_PRELOAD="$ASAN_LIB"
fi
exec "$@"