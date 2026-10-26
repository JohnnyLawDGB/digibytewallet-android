#!/usr/bin/env bash
# Gate: no script or workflow switches off SSH host-key checking.
#
# The checkpoint generators write the wallet's trusted checkpoint tables from what a remote node says.
# With StrictHostKeyChecking=no (as they had until 2026-10-11, ledger NEW-F07) any host answering for
# that name is believed. They now pin scripts/known_hosts.digiscope; this keeps it that way.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
hits=$(grep -rnE 'StrictHostKeyChecking[= ]+(no|accept-new|off)|UserKnownHostsFile[= ]+/dev/null' \
        "$ROOT/scripts" "$ROOT/.github" 2>/dev/null | grep -v 'check-ssh-host-keys.sh')
if [ -n "$hits" ]; then
    echo "FAIL: host-key checking is switched off:"; echo "$hits" | sed 's/^/  /'
    echo "Pin the host in a checked-in known_hosts file and use StrictHostKeyChecking=yes."
    exit 1
fi
echo "ok: no script switches off SSH host-key checking"
