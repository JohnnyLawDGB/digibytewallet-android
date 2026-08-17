// Host KAT for BRPeerPenaltyContains (BRPeerPenalty.h, Task 1 of the
// cf-sync-peer-reliability plan, .superpowers/sdd/task-1-brief.md).
//
// Context: the peer manager's "node isn't synced" reject
// (BRPeerManager.c:914-916) disconnects a behind peer but the filter-first
// dial loop (BRPeerManager.c:2448-2472) has no memory of that rejection, so
// on the next BRPeerManagerConnect() pass it immediately re-dials the same
// still-behind peer -- observed live as one peer dialed 122x in a tight
// loop while the wallet held 0 peers. BRPeerPenaltyContains is the pure
// predicate the dial loop uses to skip a recently-rejected (address, port)
// for PEER_PENALTY_SECONDS (10 min) before retrying it.
//
// This is a pure/header-only predicate over caller-supplied parallel arrays
// (no BRPeerManager struct, no networking, no allocation) so it's fully
// unit-testable on the host: build tiny addr/port/until arrays and assert
// the exact behavior the dial loop depends on.
//
// Exit code 0 = all checks passed, 1 = at least one check failed (or build
// error, e.g. the header doesn't exist yet / doesn't declare the symbol).

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <time.h>

#include "BRInt.h"
#include "BRPeerPenalty.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) {
        printf("PASS: %s\n", desc);
    } else {
        printf("FAIL: %s\n", desc);
        g_failures++;
    }
}

static UInt128 make_addr(uint8_t last_octet)
{
    // IPv4-mapped IPv6 form, same layout the real peer code uses
    // (address.u32[3] holds the IPv4 octets) -- see BRPeerManager.c:2468.
    UInt128 a;
    memset(a.u8, 0, sizeof(a.u8));
    a.u8[10] = 0xff;
    a.u8[11] = 0xff;
    a.u8[12] = 10;
    a.u8[13] = 0;
    a.u8[14] = 0;
    a.u8[15] = last_octet;
    return a;
}

int main(void)
{
    UInt128 addrs[4];
    uint16_t ports[4];
    time_t until[4];
    size_t count = 0;

    UInt128 addrA = make_addr(1);   // penalized, unexpired
    UInt128 addrB = make_addr(2);   // penalized, EXPIRED
    UInt128 addrC = make_addr(3);   // never penalized (different addr)
    uint16_t portA = 12024, portB = 12024, portOther = 22024;

    time_t now = time(NULL);

    // Slot 0: addrA/portA, until 10 minutes in the future (unexpired).
    addrs[count] = addrA; ports[count] = portA; until[count] = now + 10*60; count++;
    // Slot 1: addrB/portB, until 10 minutes in the PAST (expired).
    addrs[count] = addrB; ports[count] = portB; until[count] = now - 10*60; count++;

    check(BRPeerPenaltyContains(addrs, ports, until, count, addrA, portA, now) == 1,
          "a freshly-penalized (addr,port) with until > now IS contained");

    check(BRPeerPenaltyContains(addrs, ports, until, count, addrB, portB, now) == 0,
          "an expired entry (until < now) is NOT contained");

    check(BRPeerPenaltyContains(addrs, ports, until, count, addrC, portA, now) == 0,
          "a different address (same port) is NOT contained");

    check(BRPeerPenaltyContains(addrs, ports, until, count, addrA, portOther, now) == 0,
          "the same address with a different port is NOT contained");

    check(BRPeerPenaltyContains(addrs, ports, until, 0, addrA, portA, now) == 0,
          "an empty set (count == 0) never contains anything");

    // Boundary: until == now is NOT "not yet expired" (strict >, matches
    // "10 minutes in the past" style expiry -- an entry expires exactly at
    // its deadline, not one tick after).
    {
        UInt128 addrsB[1] = { addrA };
        uint16_t portsB[1] = { portA };
        time_t untilB[1] = { now };
        check(BRPeerPenaltyContains(addrsB, portsB, untilB, 1, addrA, portA, now) == 0,
              "until == now is treated as expired (strict until > now required)");
    }

    // Refreshing a slot (simulating what _penalize's ring-buffer insert
    // does on a repeat reject) extends the window past the old value.
    {
        UInt128 addrsR[1] = { addrA };
        uint16_t portsR[1] = { portA };
        time_t untilR[1] = { now + 20*60 }; // refreshed further out
        check(BRPeerPenaltyContains(addrsR, portsR, untilR, 1, addrA, portA, now + 15*60) == 1,
              "a refreshed entry is still contained at a time past the original window");
    }

    // ---- persistence across process restarts -------------------------------------
    // The penalty set was session-scoped, so every cold start re-dialled peers we had
    // already learned were behind — exactly the "one peer dialled 122x" churn the
    // penalty exists to stop, reintroduced once per launch. These two pure helpers
    // round-trip it through a blob the Kotlin layer can persist.
    {
        UInt128 addrs[3] = { addrA, addrB, addrA };
        uint16_t ports[3] = { portA, portB, (uint16_t)(portA + 1) };
        time_t until[3] = { now + 10*60, now + 5*60, now - 60 }; // third is already expired

        uint8_t buf[512];
        size_t written = BRPeerPenaltySerialize(addrs, ports, until, 3, now, buf, sizeof(buf));
        check(written > 0, "the penalty set serializes");

        UInt128 outAddrs[8]; uint16_t outPorts[8]; time_t outUntil[8];
        size_t loaded = BRPeerPenaltyDeserialize(buf, written, now, outAddrs, outPorts, outUntil, 8);
        check(loaded == 2, "an entry whose window already lapsed is not carried across the restart");
        check(BRPeerPenaltyContains(outAddrs, outPorts, outUntil, loaded, addrA, portA, now) == 1,
              "a live penalty survives the round trip");
        check(BRPeerPenaltyContains(outAddrs, outPorts, outUntil, loaded, addrB, portB, now) == 1,
              "a second live penalty survives too");
        check(BRPeerPenaltyContains(outAddrs, outPorts, outUntil, loaded, addrA, (uint16_t)(portA + 1), now) == 0,
              "the expired entry is absent after the round trip");

        // Deadlines are absolute, so a blob written long ago must not re-penalize
        // anyone: reading it later drops everything whose window has since lapsed.
        size_t stale = BRPeerPenaltyDeserialize(buf, written, now + 30*60, outAddrs, outPorts, outUntil, 8);
        check(stale == 0, "a blob read after every window lapsed restores nothing");
    }

    // A short buffer must report failure rather than write a truncated blob that would
    // deserialize into garbage penalties on the next launch.
    {
        UInt128 addrs[1] = { addrA };
        uint16_t ports[1] = { portA };
        time_t until[1] = { now + 10*60 };
        uint8_t tiny[8];
        check(BRPeerPenaltySerialize(addrs, ports, until, 1, now, tiny, sizeof(tiny)) == 0,
              "serialization into an undersized buffer writes nothing and reports 0");
    }

    // Garbage in must not become penalties out — the blob comes off disk.
    {
        UInt128 outAddrs[8]; uint16_t outPorts[8]; time_t outUntil[8];
        uint8_t truncated[10] = { 9, 0, 0, 0, 1, 2, 3, 4, 5, 6 }; // claims 9 entries, holds none
        check(BRPeerPenaltyDeserialize(truncated, sizeof(truncated), now, outAddrs, outPorts, outUntil, 8) == 0,
              "a truncated blob restores nothing");
        check(BRPeerPenaltyDeserialize(NULL, 0, now, outAddrs, outPorts, outUntil, 8) == 0,
              "an empty blob restores nothing");
    }

    if (g_failures == 0) {
        printf("\nALL PASSED (0 failure(s))\n");
        return 0;
    } else {
        printf("\nSOME FAILED (%d failure(s))\n", g_failures);
        return 1;
    }
}
