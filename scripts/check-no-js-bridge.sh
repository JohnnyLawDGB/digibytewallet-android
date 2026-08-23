#!/usr/bin/env bash
#
# The digistamp section renders a third-party site in a WebView inside a wallet that holds
# funds. That is defensible for exactly one reason: page JavaScript has NO channel into the
# wallet. A page can navigate to a wallet URL; it cannot call a method.
#
# The moment someone adds addJavascriptInterface — however narrow the object, however careful
# the argument checks — the safety argument changes from "there is nothing to call" to "every
# argument is validated correctly", which is a far weaker claim and one nobody can verify by
# reading a class comment.
#
# So the rule is enforced rather than documented. This fails the build if either appears.
#
# Usage: scripts/check-no-js-bridge.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status=0

scan() {
    local pattern="$1" label="$2"
    # Source only — comments in this script and the doc that explains the rule don't count.
    local hits
    # Strip the file:line: prefix before deciding whether the hit is a comment, otherwise the
    # path itself defeats the filter — which it did on the first run of this gate.
    hits=$(grep -rn --include=*.kt --include=*.java "$pattern" \
             "$REPO_ROOT/app/src" "$REPO_ROOT/core/src" 2>/dev/null \
           | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' || true)
    if [ -n "$hits" ]; then
        echo "FAIL: $label found —"
        echo "$hits" | sed 's/^/    /'
        status=1
    else
        echo "ok: no $label"
    fi
}

scan "addJavascriptInterface" "JavaScript bridge registration"
scan "@JavascriptInterface" "JavaScript-exposed method"

if [ "$status" -ne 0 ]; then
    echo
    echo "A WebView bridge next to an unlocked wallet lets page script request wallet"
    echo "operations directly. If a bridge is genuinely required, that is a design decision"
    echo "with a threat model attached — not a change this gate should be edited to allow."
fi

exit "$status"
