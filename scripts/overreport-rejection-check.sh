#!/usr/bin/env bash
# overreport-rejection-check.sh — defense (d) for legacy-sweep bug #2.
#
# The on-device fee-sanity guard (jni_derive.c) catches gross UNDER-reporting,
# but the legacy P2PKH sighash is amount-blind, so an OVER-reported sweep
# (outputs > real prevout value) still signs locally. Only the network catches
# it: outputs exceeding inputs are consensus-invalid (bad-txns-in-belowout).
#
# This asserts exactly that against the live mainnet node via testmempoolaccept
# (no broadcast). Run during the §6 mainnet proof with an over-reported signed
# hex built against the REAL self-funded prevout (LegacySweepAmountGuardTest
# logs such a hex, but with a SYNTHETIC prevout the node returns "missing-
# inputs" instead — the prevout must exist and be unspent for the belowout
# assertion to fire).
#
# Usage: scripts/overreport-rejection-check.sh <signed_tx_hex>
# Exit 0 iff the node rejects the tx with a below-output (outputs>inputs)
# reason; non-zero otherwise.
set -euo pipefail

HEX="${1:?usage: overreport-rejection-check.sh <signed_tx_hex>}"
SSH="ssh -i ${HOME}/.ssh/DigitalOcean root@digiscope.me"
CLI="digibyte-cli"

RESULT="$($SSH "$CLI testmempoolaccept '[\"$HEX\"]'")"
echo "testmempoolaccept => $RESULT"

ALLOWED="$(printf '%s' "$RESULT" | grep -o '"allowed"[[:space:]]*:[[:space:]]*[a-z]*' | head -1 | grep -o '[a-z]*$')"
REASON="$(printf '%s' "$RESULT" | grep -o '"reject-reason"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1)"

if [ "$ALLOWED" = "false" ] && printf '%s' "$REASON" | grep -qi 'belowout'; then
    echo "PASS: over-reported sweep rejected by network ($REASON)"
    exit 0
fi

echo "FAIL: expected allowed=false with a below-output reject reason, got allowed=$ALLOWED $REASON" >&2
exit 1
