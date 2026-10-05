#!/usr/bin/env bash
#
# The release key signs every build of app.aroundtheblock.wallet, on Play and on the download page.
# A copy in this repository is a copy on every clone and fork, forever: deleting it later does not
# take it back out of history. .gitignore keeps the usual names out, but .gitignore is advice — a
# renamed file, `git add -f`, or a key inside some other format walks straight past it.
#
# So this checks what is actually TRACKED (or staged, with --staged), by name and by content:
#   - names: *.jks *.keystore *.p12 *.pfx *.pepk keystore.properties, and pepk output zips
#   - content: JKS (FE ED FE ED) and JCEKS (CE CE CE CE) magic, and PEM private keys
# PKCS12 has no fixed magic, so it is caught by name only — another reason to keep the .p12 name.
#
# Usage: scripts/check-no-keystore-tracked.sh            # CI: everything tracked at HEAD
#        scripts/check-no-keystore-tracked.sh --staged   # pre-commit: what is about to be committed
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ "${1:-}" = "--staged" ]; then
    files=$(git diff --cached --name-only --diff-filter=ACMR)
else
    files=$(git ls-files)
fi

status=0
while IFS= read -r f; do
    [ -n "$f" ] || continue
    base="$(basename "$f")"
    case "$base" in
        *.jks|*.keystore|*.p12|*.pfx|*.pepk|keystore.properties|*pepk*.zip|encryption_public_key.pem.zip)
            echo "FAIL: signing material tracked by name: $f"; status=1; continue ;;
    esac
    [ -f "$f" ] || continue
    magic=$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')
    case "$magic" in
        feedfeed|cececece) echo "FAIL: Java keystore content in $f"; status=1; continue ;;
    esac
    if grep -Iq -- "-----BEGIN [A-Z ]*PRIVATE KEY-----" "$f" 2>/dev/null; then
        echo "FAIL: PEM private key in $f"; status=1
    fi
done <<< "$files"

if [ "$status" -eq 0 ]; then
    echo "ok: no keystore, key or keystore.properties is tracked"
else
    echo
    echo "Remove it from the index (git rm --cached <file>) and, if it was ever pushed, treat the key"
    echo "as compromised: see docs/SIGNING.md, 'If the key leaks'."
fi
exit "$status"
