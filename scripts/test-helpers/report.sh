#!/usr/bin/env bash
# scripts/test-helpers/report.sh
#
# What the suite keeps on disk: the result file's lines, and on a FAIL the evidence.
#
# Nothing saved here may hold a recovery phrase. A UI dump goes through `uidump.py redact`
# (every text field, and every node that is one lowercase BIP39-shaped word, is blanked), and
# logcat through `uidump.py redact-log` with the words the run read from the screen. Those
# words reach it on a pipe (fd 3), never through the environment or a temp file.

# result_line <id> <name> <status> <seconds> [reason] — the result file's per-scenario line.
result_line() {
    local id="$1" name="$2" status="$3" secs="$4" reason="${5:-}"
    local line
    line="  [$id] $name $(printf '%*s' $((40 - ${#name})) '')$status  (${secs}s)"
    [[ -n "$reason" ]] && line+="  $reason"
    printf '%s\n' "$line"
}

# claim_run_name <results dir> <stamp> — prints a run name nobody else has, and creates its
# directory. `mkdir` is the claim: it succeeds for exactly one of several runs started in the
# same second, where a "does it exist yet?" test passes for all of them.
claim_run_name() {
    local dir="$1" stamp="$2" name n=1
    mkdir -p "$dir" || return 1
    name="$stamp"
    until mkdir "$dir/$name" 2>/dev/null; do
        n=$((n + 1))
        [[ $n -le 50 ]] || return 1
        name="$stamp-$BASHPID"
        [[ $n -gt 2 ]] && name+="-$n"
    done
    printf '%s\n' "$name"
}

# skips_not_allowed <skipped letters> <allowed letters> — prints the skipped scenarios the
# caller did not accept in PREPUB_ALLOW_SKIP (letters separated by spaces or commas).
skips_not_allowed() {
    local allowed=" ${2//,/ } " id out=""
    for id in $1; do
        [[ "$allowed" == *" $id "* ]] || out+="${out:+ }$id"
    done
    printf '%s\n' "$out"
}

# level_summary <pass> <total> <fail> <skipped letters> <allowed letters> — one API level's
# summary status. A scenario that did not run proves nothing, so a level with a SKIP is
# INCOMPLETE, not PASS, unless every skipped scenario was accepted by name.
level_summary() {
    local pass="$1" total="$2" fail="$3" skipped="$4" allowed="${5:-}" n refused
    if [[ "$fail" -gt 0 ]]; then
        printf 'FAIL (%s failures)\n' "$fail"
        return
    fi
    # shellcheck disable=SC2086
    set -- $skipped; n=$#
    if [[ $n -eq 0 ]]; then
        printf 'PASS (%s/%s)\n' "$pass" "$total"
        return
    fi
    refused="$(skips_not_allowed "$skipped" "$allowed")"
    if [[ -n "$refused" ]]; then
        printf 'INCOMPLETE (%s/%s, %s SKIP: %s)\n' "$pass" "$total" "$n" "$skipped"
    else
        printf 'PASS (%s/%s, %s SKIP accepted: %s)\n' "$pass" "$total" "$n" "$skipped"
    fi
}

# overall_verdict <level status>... — FAIL if any level failed, did not boot or did not run;
# else INCOMPLETE if any level is; else PASS.
overall_verdict() {
    local s verdict="PASS"
    [[ $# -gt 0 ]] || { echo "FAIL"; return; }
    for s in "$@"; do
        case "$s" in
            PASS*) ;;
            INCOMPLETE*) [[ "$verdict" == "PASS" ]] && verdict="INCOMPLETE" ;;
            *) verdict="FAIL" ;;
        esac
    done
    printf '%s\n' "$verdict"
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
    } | python3 "$UIDUMP" redact-log 3 3< <(printf '%s' "${GENERATED_WORDS:-}") > "$base-logcat.txt" 2>/dev/null
    # The native core's peer chatter (tag "bread") fills 300 lines in seconds; the same window
    # without it is where the app's own story is.
    if [[ -n "$pid" ]]; then
        adbx logcat -d --pid="$pid" 2>/dev/null | grep -v ' bread *: ' | tail -300 \
            | python3 "$UIDUMP" redact-log 3 3< <(printf '%s' "${GENERATED_WORDS:-}") > "$base-logcat-app.txt" 2>/dev/null
    fi
    log "saved failure evidence: $base-*"
}
