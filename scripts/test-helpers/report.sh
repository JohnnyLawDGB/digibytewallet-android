#!/usr/bin/env bash
# scripts/test-helpers/report.sh
#
# What the suite keeps on disk: the result file's lines, and on a FAIL the evidence.
#
# Nothing saved here may hold a recovery phrase. A UI dump goes through `uidump.py redact`
# (every text field, and every node that is one lowercase BIP39-shaped word, is blanked), and
# logcat through `uidump.py redact-log` with the words the run read from the screen.

# result_line <id> <name> <status> <seconds> [reason] — the result file's per-scenario line.
result_line() {
    local id="$1" name="$2" status="$3" secs="$4" reason="${5:-}"
    local line
    line="  [$id] $name $(printf '%*s' $((40 - ${#name})) '')$status  (${secs}s)"
    [[ -n "$reason" ]] && line+="  $reason"
    printf '%s\n' "$line"
}

# save_failure_artifacts <id> — the screen and the app's last 300 logcat lines, redacted.
save_failure_artifacts() {
    local id="$1" base pid
    [[ -n "${RUN_DIR:-}" ]] || return 0
    mkdir -p "$RUN_DIR"
    base="$RUN_DIR/api${API_LEVEL:-x}-$id"
    if ui_dump; then
        python3 "$UIDUMP" redact "$UI_DUMP_FILE" "$base-ui.xml" 2>/dev/null
    else
        echo "uiautomator dump failed; focused window: $(focused_window)" > "$base-ui.txt"
    fi
    pid="$(app_pid)"
    {
        if [[ -n "$pid" ]]; then
            adbx logcat -d -t 300 --pid="$pid" 2>/dev/null
        else
            echo "--- the app has no process; crash buffer and the last lines naming the package ---"
            adbx logcat -b crash -d -t 300 2>/dev/null
            adbx logcat -d 2>/dev/null | grep -F "$PACKAGE" | tail -300
        fi
    } | python3 "$UIDUMP" redact-log GENERATED_WORDS > "$base-logcat.txt" 2>/dev/null
    # The native core's peer chatter (tag "bread") fills 300 lines in seconds; the same window
    # without it is where the app's own story is.
    if [[ -n "$pid" ]]; then
        adbx logcat -d --pid="$pid" 2>/dev/null | grep -v ' bread *: ' | tail -300 \
            | python3 "$UIDUMP" redact-log GENERATED_WORDS > "$base-logcat-app.txt" 2>/dev/null
    fi
    log "saved failure evidence: $base-*"
}
