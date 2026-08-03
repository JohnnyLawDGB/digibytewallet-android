#!/usr/bin/env bash
# Pin the wallet to ONE known-good CF peer for an acceptance run.
#
# WHY THIS EXISTS. Six deep-restore runs on 2026-08-02/03 produced scan rates of 249, 703,
# 1418, 5177, 5992 and 13448 blocks/min across builds that differed by a lock and a
# diagnostic — a 54x spread. That variance is LARGER THAN ANY EFFECT WE WERE TRYING TO
# MEASURE, so every fix was validated against noise and several confident conclusions had to
# be retracted. The dominant variable is which peers the wallet happens to land on, not the
# code.
#
# Pinning removes that variable. A run against a fixed peer measures THE CHANGE.
#
# ⚠ A PINNED RUN IS NOT A FIELD RUN. One peer means no fan-out, no churn, no peer-quality
# roulette — deliberately. It answers "did this change help?", never "how fast is it for
# users?". Always confirm a fix on an unpinned run afterwards; never ship on a pinned result
# alone.
#
# NO APP CHANGES NEEDED. This uses the shipped own-node pairing path: exclusive pinning is
# already implemented natively (BRPeerManager.c gates all three dial paths on
# pinnedExclusive — the CF connect loop and both shotgun-fallback passes) and reaches it via
# CustomNodePrefs -> SyncService.injectCustomNode -> NativeBridge.setPinnedPeer.
#
# The peer must genuinely serve filters. Verify before trusting a run:
#   python3 cfprobe.py <startHeight> <stopHash> 1000 <host>:12024
# digiscope.me measured 1000/1000 in 0.2s (DigiByte 9.26.4, basic block filter index synced).
#
# Usage:
#   ./pin-acceptance-peer.sh <serial> on  [host:port]   # default digiscope.me:12024
#   ./pin-acceptance-peer.sh <serial> off              # restore normal peer discovery
#   ./pin-acceptance-peer.sh <serial> status
set -u

SERIAL="${1:?usage: $0 <adb-serial> on|off|status [host:port]}"
ACTION="${2:?usage: $0 <adb-serial> on|off|status [host:port]}"
HOSTPORT="${3:-digiscope.me:12024}"
PKG=io.digibyte
PREFS=shared_prefs/dgb_settings.xml   # mainnet: networkSuffix() is empty

adb_shell() { adb -s "$SERIAL" shell "$@"; }

case "$ACTION" in
  status)
    echo "=== own-node prefs on $SERIAL ==="
    adb_shell run-as $PKG cat $PREFS 2>/dev/null | tr -d '\r' \
      | grep -E "custom_node_(enabled|hostport|exclusive)" || echo "(none set)"
    ;;

  on)
    # The app must have been launched at least once so shared_prefs exists.
    if ! adb_shell run-as $PKG ls $PREFS >/dev/null 2>&1; then
        echo "ERROR: $PREFS not present — launch the app once, then re-run." >&2
        exit 1
    fi
    # Written via the app's own prefs file. Deliberately NOT a code path change: the point is
    # to exercise the SHIPPING dial logic with one peer, not a special test build.
    adb_shell run-as $PKG sh -c "
        f=$PREFS
        cp \$f \$f.bak-acceptance 2>/dev/null
        # strip any existing keys, then re-add before </map>
        sed -i '/custom_node_enabled/d;/custom_node_hostport/d;/custom_node_exclusive/d' \$f
        sed -i 's#</map>#    <boolean name=\"custom_node_enabled\" value=\"true\" />\n    <string name=\"custom_node_hostport\">$HOSTPORT</string>\n    <boolean name=\"custom_node_exclusive\" value=\"true\" />\n</map>#' \$f
    "
    echo "pinned EXCLUSIVELY to $HOSTPORT (backup at $PREFS.bak-acceptance)"
    echo "force-stop and relaunch the app for it to take effect."
    ;;

  off)
    adb_shell run-as $PKG sh -c "
        f=$PREFS
        sed -i '/custom_node_enabled/d;/custom_node_hostport/d;/custom_node_exclusive/d' \$f
    "
    echo "pin cleared — normal peer discovery restored (relaunch to apply)."
    ;;

  *) echo "usage: $0 <adb-serial> on|off|status [host:port]" >&2; exit 1 ;;
esac
