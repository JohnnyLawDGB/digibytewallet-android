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
    # The app MUST be stopped first: SharedPreferences are cached in memory and flushed on
    # edit, so a running app would either not see our file or clobber it on its next write.
    if adb -s "$SERIAL" shell pidof $PKG >/dev/null 2>&1; then
        echo "app running — force-stopping so the prefs write is not clobbered"
        adb_shell am force-stop $PKG >/dev/null 2>&1
        sleep 2
    fi

    # Build the file HOST-SIDE and push it, rather than composing shell quoting through
    # `adb shell run-as sh -c`. An earlier version did the latter and the quoting mangled
    # silently: sed reported "No such file or directory" and the script still printed
    # "pinned EXCLUSIVELY" over a pin that never happened. Never hand-quote through that chain.
    #
    # Android creates a prefs file on first WRITE, not on first launch, so dgb_settings.xml is
    # legitimately absent on a fresh install. The pin must exist BEFORE the wallet is created,
    # because injectCustomNode() reads it when sync starts — so we create the file if needed.
    HOST_TMP="$(mktemp)"
    HP_HOST="${HOSTPORT%%:*}"; HP_PORT="${HOSTPORT##*:}"
    cat > "$HOST_TMP" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="custom_node_enabled" value="true" />
    <string name="custom_node_hostport">${HP_HOST}:${HP_PORT}</string>
    <boolean name="custom_node_exclusive" value="true" />
</map>
XML
    # Merge with any pre-existing settings rather than discarding them.
    if adb_shell run-as $PKG cat $PREFS >/dev/null 2>&1; then
        adb_shell run-as $PKG cat $PREFS 2>/dev/null | tr -d '\r' > "$HOST_TMP.old"
        if grep -q "</map>" "$HOST_TMP.old"; then
            adb_shell run-as $PKG cp $PREFS $PREFS.bak-acceptance 2>/dev/null
            grep -v -E "custom_node_(enabled|hostport|exclusive)|</map>" "$HOST_TMP.old" > "$HOST_TMP"
            {
              echo '    <boolean name="custom_node_enabled" value="true" />'
              echo "    <string name=\"custom_node_hostport\">${HP_HOST}:${HP_PORT}</string>"
              echo '    <boolean name="custom_node_exclusive" value="true" />'
              echo '</map>'
            } >> "$HOST_TMP"
        fi
    fi

    adb -s "$SERIAL" push "$HOST_TMP" /data/local/tmp/dgb_pin.xml >/dev/null 2>&1
    adb_shell run-as $PKG sh -c "mkdir -p shared_prefs" >/dev/null 2>&1
    adb_shell "run-as $PKG cp /data/local/tmp/dgb_pin.xml $PREFS" >/dev/null 2>&1
    adb -s "$SERIAL" shell rm -f /data/local/tmp/dgb_pin.xml >/dev/null 2>&1
    rm -f "$HOST_TMP" "$HOST_TMP.old"

    # VERIFY, and FAIL LOUDLY. The whole value of a control is that it is actually applied;
    # a harness that reports a pin it did not make is worse than no harness, because every
    # measurement taken afterwards is silently uncontrolled.
    got="$(adb_shell run-as $PKG cat $PREFS 2>/dev/null | tr -d '\r')"
    if ! echo "$got" | grep -q 'custom_node_exclusive" value="true"' \
       || ! echo "$got" | grep -q "custom_node_hostport" \
       || ! echo "$got" | grep -q 'custom_node_enabled" value="true"'; then
        echo "ERROR: pin NOT applied — prefs read back as:" >&2
        echo "$got" >&2
        exit 1
    fi
    echo "VERIFIED: pinned EXCLUSIVELY to $HOSTPORT"
    echo "$got" | grep -E "custom_node_" | sed 's/^/  /'
    echo "relaunch the app for it to take effect."
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
