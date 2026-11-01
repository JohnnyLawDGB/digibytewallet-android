#!/usr/bin/env bash
# check-pins-live.sh — every pinned leaf and intermediate is still served by its host (4.1.3 row 2).
#
# A pin set goes stale silently: a renewal on a new key leaves the shipped leaf pin matching nothing, and
# pinning then rests on whatever pin is left (4.1.2 shipped Digistamp that way). For each pins file below
# this reads the PINS block, fetches the host's served chain, and fails when a pin labelled "leaf" or
# "intermediate" is not in it. Pins labelled "backup" (roots above the served chain) are not required.
# Exit 0 = every leaf/intermediate pin is live; 1 = a pin is stale; 2 = a host could not be reached.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINDIR="$ROOT/core/src/main/java/app/aroundtheblock/wallet/core/network"
FAIL=0

served_pins() {   # host -> one sha256/... per served certificate
    local host="$1" dir; dir="$(mktemp -d)"
    timeout 30 openssl s_client -connect "$host:443" -servername "$host" -showcerts </dev/null 2>/dev/null |
        awk -v d="$dir" '/BEGIN CERT/{n++} n{print > (d "/c" n ".pem")}'
    for c in "$dir"/c*.pem; do
        [ -f "$c" ] || continue
        printf 'sha256/%s\n' "$(openssl x509 -in "$c" -pubkey -noout | openssl pkey -pubin -outform der |
            openssl dgst -sha256 -binary | openssl enc -base64)"
    done
    rm -rf "$dir"
}

for f in DigistampPins.kt DigiScopePins.kt; do
    file="$PINDIR/$f"
    host="$(grep -m1 -oE 'HOST = "[^"]+"' "$file" | cut -d'"' -f2)"
    [ -n "$host" ] || { echo "GATE FAILURE: no HOST in $f"; exit 1; }
    live="$(served_pins "$host")"
    [ -n "$live" ] || { echo "UNREACHABLE: $host served no chain"; exit 2; }
    # the PINS = listOf( ... ) block only, never RETIRED_PINS
    block="$(awk '/val PINS = listOf\(/{p=1; next} p && /^\s*\)/{exit} p' "$file")"
    n=0
    while IFS= read -r line; do
        pin="$(<<<"$line" grep -oE 'sha256/[A-Za-z0-9+/=]+')" || continue
        label="$(<<<"$line" sed -n 's#.*// *##p')"
        n=$((n + 1))
        case "$label" in
            *backup*) echo "  $host: backup pin not required to be served ($label)"; continue ;;
        esac
        if <<<"$live" grep -qxF "$pin"; then echo "  $host: live      $pin ($label)"
        else echo "STALE: $host no longer serves $pin ($label)"; FAIL=1; fi
    done <<<"$block"
    [ "$n" -gt 0 ] || { echo "GATE FAILURE: read no pins from $f"; exit 1; }
done

[ "$FAIL" -eq 0 ] && { echo "PASS: every pinned leaf and intermediate is served"; exit 0; }
echo "FAIL: a pin is stale -- rotate it (move it to RETIRED_PINS, add the served value)"; exit 1
