/* peer_close_ledger_kat — an ORDERLY peer close must be distinguishable from a RESET.
 *
 * WHY THIS EXISTS. Twelve days of "the wallet won't finish a deep restore" has produced
 * three competing churn theories — peer-side eviction off saturated nodes, self-inflicted
 * timeouts, and OS freeze — and no way to tell them apart, because nothing recorded WHO
 * closed each connection. The disconnect ledger (BRPeer.h) records it. This gate pins the
 * one classification the whole question turns on.
 *
 * THE DEFECT. Both socket read loops in _peerThreadRoutine did this:
 *
 *     n = read(socket, ...);
 *     if (n == 0) error = ECONNRESET;                       // orderly FIN
 *     if (n < 0 && errno != EWOULDBLOCK) error = errno;     // ECONNRESET == real reset
 *
 * read() == 0 means the remote sent FIN — an ORDERLY shutdown. read() < 0 with ECONNRESET
 * means the connection was torn down hard. The loop collapsed both onto ECONNRESET. For
 * `error` that is harmless (both mean "the connection is gone"), but it destroyed the only
 * evidence separating them — and DigiByte Core's inbound eviction path,
 * AttemptToEvictConnection -> CloseSocketDisconnect, closes ORDERLY.
 *
 * So on the unfixed build, "a saturated node evicted us" and "the network reset us" are
 * the same observation. That is precisely why "are we being evicted?" could never be
 * answered from our own logs, and why the .onion decision had no data behind it.
 *
 * WHAT IS ASSERTED. BRPeerClassifySocketResult is pure — no peer, no socket, no threads —
 * so this drives it with the exact (n, errno) tuples the loops produce:
 *
 *   FIXED:   n == 0 -> PEER_FIN, and (n < 0, ECONNRESET) -> PEER_RST. DIFFERENT values.
 *   UNFIXED (-DPEER_CLOSE_LEDGER_UNFIXED): n == 0 also yields PEER_RST — the two collapse,
 *            and the "orderly close is distinguishable from a reset" assertion MUST FAIL.
 *
 * The gate also pins the surrounding classifications, so the conflation cannot be "fixed"
 * by making everything return a distinct-but-wrong value: a live socket (n > 0) must not
 * be classified as a close at all, EWOULDBLOCK must never reach the classifier as a close,
 * and our own ETIMEDOUT/EPROTO must classify as LOCAL_*, never as anything peer-initiated.
 * That last one matters as much as the FIN/RST split: if our own timeouts were filed under
 * a PEER_ cause, the ledger would report the wallet being evicted when it is in fact
 * hanging up on itself, which is the exact wrong conclusion to draw about onion.
 *
 * DETERMINISTIC — no threads, no sockets, no timing. Fails 100% of the time when unfixed.
 */

#include <stdio.h>
#include <string.h>
#include <errno.h>

/* Included, not linked: the lifetime assertions below must reach BRPeerContext to
 * simulate the ping stopwatch resetting mid-connection. run.sh drops BRPeer.c from the
 * link line accordingly. */
#include "BRPeer.c"

static int g_fail = 0;

static void check(int cond, const char *what)
{
    printf("%s: %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail++;
}

static void show(const char *label, BRPeerCloseCause c)
{
    printf("   [classify] %-34s -> %s\n", label, BRPeerCloseCauseName(c));
}

int main(void)
{
#ifdef PEER_CLOSE_LEDGER_UNFIXED
    printf("ARM: UNFIXED (-DPEER_CLOSE_LEDGER_UNFIXED) — orderly FIN reported as a reset\n\n");
#else
    printf("ARM: FIXED\n\n");
#endif

    /* The two the whole eviction question turns on. */
    BRPeerCloseCause fin = BRPeerClassifySocketResult(0, 0);            /* read() == 0  */
    BRPeerCloseCause rst = BRPeerClassifySocketResult(-1, ECONNRESET);  /* hard reset   */

    show("read()==0 (remote sent FIN)", fin);
    show("read()<0 ECONNRESET (hard reset)", rst);

    check(fin != rst,
          "an ORDERLY close (FIN) is distinguishable from a RESET");
    check(fin == BR_CLOSE_PEER_FIN,
          "read()==0 classifies as PEER_FIN — the eviction signature");
    check(rst == BR_CLOSE_PEER_RST,
          "ECONNRESET classifies as PEER_RST");

    printf("\n");

    /* Both are peer-initiated: neither may ever be filed under a LOCAL_ cause, or the
     * ledger would under-report eviction and we would wrongly blame ourselves. */
    check(fin != BR_CLOSE_LOCAL_SCHEDULED && fin != BR_CLOSE_LOCAL_MSG_TIMEOUT &&
          fin != BR_CLOSE_LOCAL_SEND_TIMEOUT && fin != BR_CLOSE_LOCAL_PROTOCOL &&
          fin != BR_CLOSE_LOCAL_EXPLICIT,
          "an orderly peer close is never attributed to a LOCAL rule");

    /* And the converse: our own deadlines must never masquerade as the peer hanging up,
     * or the ledger would report eviction that never happened. */
    BRPeerCloseCause tmo = BRPeerClassifySocketResult(-1, ETIMEDOUT);
    BRPeerCloseCause pro = BRPeerClassifySocketResult(-1, EPROTO);
    show("read()<0 ETIMEDOUT (our deadline)", tmo);
    show("read()<0 EPROTO (we rejected it)", pro);
    check(tmo != BR_CLOSE_PEER_FIN && tmo != BR_CLOSE_PEER_RST,
          "our own ETIMEDOUT is never attributed to the peer");
    check(pro != BR_CLOSE_PEER_FIN && pro != BR_CLOSE_PEER_RST,
          "our own EPROTO is never attributed to the peer");

    printf("\n");

    /* A live socket is not a close. If n > 0 classified as anything, _BRPeerNoteClose would
     * latch a cause on the FIRST successful read and — being first-writer-wins — every peer
     * would report that cause forever after. */
    BRPeerCloseCause live = BRPeerClassifySocketResult(1500, 0);
    show("read()>0 (bytes arrived)", live);
    check(live == BR_CLOSE_UNKNOWN,
          "a successful read is NOT a close (first-writer-wins must not latch on live traffic)");

    /* An unknown errno must still land somewhere real, not silently read as UNKNOWN — an
     * unclassified close would quietly vanish from the histogram. */
    BRPeerCloseCause odd = BRPeerClassifySocketResult(-1, EHOSTUNREACH);
    show("read()<0 EHOSTUNREACH (unmapped)", odd);
    check(odd == BR_CLOSE_SOCKET_ERR,
          "an unmapped socket errno classifies as SOCKET_ERR, never UNKNOWN");

    printf("\n");

    /* Names must be distinct and non-empty: the histogram is read by eye, and two causes
     * printing the same string would re-create the exact ambiguity this gate exists to end. */
    int namesOk = 1;
    for (int a = 0; a < BR_CLOSE_CAUSE_COUNT; a++) {
        const char *na = BRPeerCloseCauseName((BRPeerCloseCause)a);
        if (! na || ! na[0]) { namesOk = 0; break; }
        for (int b = a + 1; b < BR_CLOSE_CAUSE_COUNT; b++) {
            if (strcmp(na, BRPeerCloseCauseName((BRPeerCloseCause)b)) == 0) { namesOk = 0; break; }
        }
        if (! namesOk) break;
    }
    check(namesOk, "every close cause has a distinct, non-empty name");

    int tagsOk = 1;
    for (int a = 1; a < BR_DISC_TAG_COUNT; a++) {
        const char *na = BRPeerDisconnectTagName((BRPeerDisconnectTag)a);
        if (! na || ! na[0]) { tagsOk = 0; break; }
        for (int b = a + 1; b < BR_DISC_TAG_COUNT; b++) {
            if (strcmp(na, BRPeerDisconnectTagName((BRPeerDisconnectTag)b)) == 0) { tagsOk = 0; break; }
        }
        if (! tagsOk) break;
    }
    check(tagsOk, "every disconnect tag has a distinct, non-empty name");

    /* ---- CONNECTION LIFETIME vs THE PING STOPWATCH ----------------------------
     *
     * THE DEFECT THIS PINS. The ledger first measured lifetime from ctx->startTime. That
     * field is not a connection clock — it is a PING STOPWATCH: BRPeerSendPing and
     * BRPeerSendPingProbe reset it to now, and the verack and pong handlers zero it after
     * folding it into pingTime. On-device this printed life=0.0s for connections that had
     * moved megabytes, and it silently corrupted shortLived(<30s) — which is THE
     * discriminator between "evicted before we could use the peer" and "closed after a
     * long productive session". A broken clock there does not look broken; it just quietly
     * answers the eviction question wrong.
     *
     * The same field was also tested against 0 to detect a failed connect, so a peer that
     * completed its handshake (startTime zeroed by the verack handler) could be filed as
     * CONNECT_FAIL — inflating "never got a slot" with peers that plainly had one.
     */
    {
        BRPeer *p = BRPeerNew(0xd9b4bef9);
        BRPeerContext *pc = (BRPeerContext *)p;

        /* A peer connected 120s ago that has since been pinged and verack'd — i.e. the
         * exact state every healthy long-lived peer is in. */
        struct timeval tv;
        gettimeofday(&tv, NULL);
        double now = tv.tv_sec + (double)tv.tv_usec/1000000;

        pc->connectTime = now - 120.0;   /* socket opened 120s ago */
        pc->closeTime   = now;
        /* BOTH arms model the SAME peer state: a ping went out 0.5s ago, so the stopwatch
         * has been reset to a recent instant. Only the field being measured differs, which
         * is exactly the defect. (Leaving startTime at its calloc'd 0 would NOT model this
         * — it would measure from the epoch and read as a huge "lifetime", passing the red
         * arm for a reason that has nothing to do with the bug.) */
        pc->startTime = now - 0.5;

#ifdef PEER_LIFETIME_UNFIXED
        double life = (pc->closeTime > pc->startTime) ? pc->closeTime - pc->startTime : 0;
        printf("   [lifetime] measured from the PING STOPWATCH: %.1fs\n", life);
#else
        double life = BRPeerConnectedSecs(p);
        printf("   [lifetime] measured from the CONNECTION CLOCK: %.1fs\n", life);
#endif
        check(life > 100.0,
              "a 120s connection reports its real lifetime after a ping reset startTime");

        /* And a peer that never got a socket must still read as no connection at all. */
        BRPeer *q = BRPeerNew(0xd9b4bef9);
        check(BRPeerConnectedSecs(q) == 0,
              "a peer that never opened a socket reports zero lifetime");
        BRPeerFree(q);
        BRPeerFree(p);
    }

    /* ---- 12-BYTE COMMAND FIELD (the false-EPROTO that evicted our own oracle nodes) ----
     *
     * MEASURED, Note 8, deep restore 2026-08-08: 12 of 60 peer closes were LOCAL_PROTOCOL,
     * every one of them "malformed message header: type not NULL terminated", and every one
     * on a canon CF oracle node. EPROTO routes into _BRPeerManagerPeerMisbehavin, which
     * removes the peer from manager->peers and clears the WHOLE pool on the tenth event.
     *
     * The wire command field is 12 bytes, NUL-PADDED. A name that uses all twelve carries no
     * terminator and is legal — Core's CMessageHeader::IsCommandValid() accepts it. The old
     * test (header[15] == 0) rejected it, so a correctly-speaking peer was punished.
     */
    {
        const uint8_t full12[12]  = {'o','r','a','m','u','s','i','g','p','a','r','t'}; /* 12, no NUL */
        const uint8_t padded[12]  = {'v','e','r','a','c','k',0,0,0,0,0,0};
        const uint8_t trailing[12]= {'p','i','n','g',0,0,'X',0,0,0,0,0};  /* garbage after the NUL */
        const uint8_t nonascii[12]= {'p','i','n',0x01,0,0,0,0,0,0,0,0};
        const uint8_t empty[12]   = {0,0,0,0,0,0,0,0,0,0,0,0};

        printf("   [command] full-12 '%.12s' -> %s\n", (const char *)full12,
               _BRPeerCommandFieldValid(full12) ? "accepted" : "REJECTED");

        check(_BRPeerCommandFieldValid(full12) == 1,
              "a legal 12-character command name (no NUL terminator) is ACCEPTED");
        check(_BRPeerCommandFieldValid(padded) == 1,
              "a short, NUL-padded command name is accepted");

        /* The fix must not become "accept anything" — these are the cases the old check got
         * right, and losing them would turn a false-reject bug into a parser hole. */
        check(_BRPeerCommandFieldValid(trailing) == 0,
              "non-NUL padding after the terminator is still REJECTED");
        check(_BRPeerCommandFieldValid(nonascii) == 0,
              "a non-printable byte in the command field is still REJECTED");
        check(_BRPeerCommandFieldValid(empty) == 1,
              "an all-NUL command field parses (length/checksum reject it downstream)");
    }

    printf("\n%d FAIL\n", g_fail);
    return g_fail ? 1 : 0;
}
