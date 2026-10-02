//
//  checkpoint_time.h
//
//  The compiled block-checkpoint table, read by HEIGHT without a wallet: the timestamp of
//  the highest checkpoint at or below a given height. The wallet's header anchor is chosen
//  by TIME (BRPeerManagerNewEx and getWalletBirthCheckpointHeight take the latest
//  checkpoint whose timestamp is more than a week before the wallet's creation time), so a
//  creation time no later than this timestamp selects a checkpoint at or below the height.
//  Pure over a caller-supplied table so it is testable on the host
//  (native/src/test/host/checkpoint_time_kat/).
//

#ifndef CHECKPOINT_TIME_H
#define CHECKPOINT_TIME_H

#include <stddef.h>
#include <stdint.h>
#include "BRChainParams.h"

/** Timestamp of the highest checkpoint whose height is <= [height]; 0 if there is none. The
 *  table is not assumed to be sorted. */
static inline uint32_t checkpoint_time_at_or_below(const BRCheckPoint *cp, size_t count, uint64_t height)
{
    uint32_t bestTime = 0;
    int found = 0;
    uint32_t bestHeight = 0;
    for (size_t i = 0; cp && i < count; i++) {
#ifdef CHECKPOINT_TIME_UNFIXED
        /* red arm: the lowest checkpoint at or ABOVE the height */
        if ((uint64_t)cp[i].height >= height && (!found || cp[i].height < bestHeight)) {
#else
        if ((uint64_t)cp[i].height <= height && (!found || cp[i].height > bestHeight)) {
#endif
            found = 1;
            bestHeight = cp[i].height;
            bestTime = cp[i].timestamp;
        }
    }
    return found ? bestTime : 0;
}

#endif // CHECKPOINT_TIME_H
