// Host KAT: proves BRPeerPenaltyPersist.h's empty/unknown/store decision, and --
// the part a pure table test would miss -- that its 4-byte floor actually
// matches what BRPeerPenaltySerialize produces for an empty set.
//
// The distinction under test: "nothing to save" and "can't tell right now" are
// different answers. An empty penalty set still serializes to a 4-byte count
// header, so a NULL blob means the probe failed. Treating that as empty deletes
// penalties already banked -- and a wallet that comes back up having forgotten
// which peers refused it can skip straight to the 0-peer dead wedge the penalty
// set exists to prevent.
//
// Ported from core/sync/PeerPenaltyPersist.kt. Links BRPeerPenalty.h's
// serializer so the header constant is checked against the real wire format
// rather than against a comment.
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "BRPeerPenaltyPersist.h"
#include "BRPeerPenalty.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

int main(void)
{
    uint8_t buf[512];

    // test1 -- the two ways "unknown" arrives. Both must Keep.
    check(BRPeerPenaltyDecide(0, 0) == BRPeerPenaltyActionKeep,
          "test1: a NULL blob is Keep, not Clear");
    check(BRPeerPenaltyDecide(0, 99) == BRPeerPenaltyActionKeep,
          "test1: NULL with a nonzero length is still Keep");
    {
        uint8_t tiny[3] = { 0, 0, 0 };
        check(BRPeerPenaltyDecide(tiny, 3) == BRPeerPenaltyActionKeep,
              "test1: a blob too short to hold its own count header is Keep");
        check(BRPeerPenaltyDecide(tiny, 0) == BRPeerPenaltyActionKeep,
              "test1: a zero-length blob is Keep");
    }

    // test2 -- exactly the header: a real, empty set.
    {
        uint8_t empty[BR_PEER_PENALTY_HEADER_BYTES];
        memset(empty, 0, sizeof(empty));
        check(BRPeerPenaltyDecide(empty, sizeof(empty)) == BRPeerPenaltyActionClear,
              "test2: exactly the header is Clear -- a genuinely empty set");
    }

    // test3 -- anything longer carries entries.
    {
        uint8_t some[BR_PEER_PENALTY_HEADER_BYTES + 1];
        memset(some, 0, sizeof(some));
        check(BRPeerPenaltyDecide(some, sizeof(some)) == BRPeerPenaltyActionStore,
              "test3: header + 1 byte is Store");
        check(BRPeerPenaltyDecide(buf, sizeof(buf)) == BRPeerPenaltyActionStore,
              "test3: a full blob is Store");
    }

    // test4 -- THE cross-check. The header's floor must equal what the real
    // serializer emits for an empty set. A constant that drifts from the wire
    // format silently converts every empty set into "unknown", so penalties
    // would never be cleared and a stale set would outlive its usefulness.
    {
        size_t n = BRPeerPenaltySerialize(0, 0, 0, 0, time(0), buf, sizeof(buf));
        printf("      BRPeerPenaltySerialize(empty) wrote %zu bytes; header constant is %zu\n",
               n, (size_t)BR_PEER_PENALTY_HEADER_BYTES);
        check(n == BR_PEER_PENALTY_HEADER_BYTES,
              "test4: an empty set serializes to exactly BR_PEER_PENALTY_HEADER_BYTES");
        check(BRPeerPenaltyDecide(buf, n) == BRPeerPenaltyActionClear,
              "test4: and the real empty blob decides Clear");
    }

    // test5 -- a serialized NON-empty set must decide Store, end to end.
    {
        UInt128 addrs[2];
        uint16_t ports[2] = { 12024, 12024 };
        time_t now = time(0);
        time_t until[2];
        size_t n;

        memset(addrs, 0, sizeof(addrs));
        addrs[0].u8[15] = 1;
        addrs[1].u8[15] = 2;
        until[0] = now + 600;
        until[1] = now + 600;

        n = BRPeerPenaltySerialize(addrs, ports, until, 2, now, buf, sizeof(buf));
        printf("      two live entries serialized to %zu bytes\n", n);
        check(n > BR_PEER_PENALTY_HEADER_BYTES, "test5: two entries exceed the header");
        check(BRPeerPenaltyDecide(buf, n) == BRPeerPenaltyActionStore,
              "test5: a populated blob decides Store");
    }

    // test6 -- an all-EXPIRED set serializes down to just the header, so it must
    // read as Clear rather than Store. This is the case where "empty" is a real
    // answer arrived at by expiry rather than by never having entries.
    {
        UInt128 addrs[2];
        uint16_t ports[2] = { 12024, 12024 };
        time_t now = time(0);
        time_t until[2];
        size_t n;

        memset(addrs, 0, sizeof(addrs));
        until[0] = now - 600;
        until[1] = now - 1;

        n = BRPeerPenaltySerialize(addrs, ports, until, 2, now, buf, sizeof(buf));
        printf("      two EXPIRED entries serialized to %zu bytes\n", n);
        check(n == BR_PEER_PENALTY_HEADER_BYTES,
              "test6: expired entries serialize to just the header");
        check(BRPeerPenaltyDecide(buf, n) == BRPeerPenaltyActionClear,
              "test6: an all-expired set decides Clear, not Store");
    }

    // test6b -- the entry stride is the other half of the wire format, and the
    // arithmetic must close: header + n*entry is exactly what the serializer
    // wrote for n live entries. If either constant drifts, this is where it
    // shows, rather than in a wallet that mis-reads its own stored blob.
    {
        UInt128 addrs[3];
        uint16_t ports[3] = { 12024, 12024, 12024 };
        time_t now = time(0);
        time_t until[3];
        size_t n, i;

        memset(addrs, 0, sizeof(addrs));
        for (i = 0; i < 3; i++) { addrs[i].u8[15] = (uint8_t)(i + 1); until[i] = now + 600; }

        n = BRPeerPenaltySerialize(addrs, ports, until, 3, now, buf, sizeof(buf));
        printf("      three live entries: %zu bytes; header %u + 3*entry %u = %u\n",
               n, (unsigned)BR_PEER_PENALTY_HEADER_BYTES,
               (unsigned)BR_PEER_PENALTY_ENTRY_BYTES,
               (unsigned)(BR_PEER_PENALTY_HEADER_BYTES + 3u * BR_PEER_PENALTY_ENTRY_BYTES));
        check(n == BR_PEER_PENALTY_HEADER_BYTES + 3u * BR_PEER_PENALTY_ENTRY_BYTES,
              "test6b: header + 3*entry equals what the serializer wrote");
    }

    // test7 -- the length-only accessor agrees with the buffer one wherever both
    // are defined.
    {
        size_t lens[] = { 0, 1, 3, 4, 5, 34, 512 };
        int agree = 1;
        size_t i;
        for (i = 0; i < sizeof(lens)/sizeof(lens[0]); i++) {
            if (BRPeerPenaltyDecideLength(lens[i]) != BRPeerPenaltyDecide(buf, lens[i])) agree = 0;
        }
        check(agree, "test7: DecideLength agrees with Decide for a non-NULL buffer");
        check(BRPeerPenaltyDecideLength(0) == BRPeerPenaltyActionKeep,
              "test7: DecideLength(0) is Keep");
    }

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nall checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
