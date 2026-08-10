#include <assert.h>
#include <stdio.h>
#include "BRCompactFilterCheckpoints.h"
int main(void) {
    // AtOrBelow
    assert(BRCFHighestCheckpointAtOrBelow(49999) == NULL);
    assert(BRCFHighestCheckpointAtOrBelow(50000)->height == 50000);
    assert(BRCFHighestCheckpointAtOrBelow(50001)->height == 50000);
    assert(BRCFHighestCheckpointAtOrBelow(149999)->height == 100000);
    const BRCFCheckpoint *top = &BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount-1];
    assert(BRCFHighestCheckpointAtOrBelow(top->height + 1000000)->height == top->height);
    // InRange
    const BRCFCheckpoint *hits[8];
    assert(BRCFCheckpointsInRange(50000, 150000, hits, 8) == 3);   // 50k,100k,150k
    assert(hits[0]->height == 50000 && hits[2]->height == 150000);
    assert(BRCFCheckpointsInRange(50001, 99999, hits, 8) == 0);    // none strictly between
    assert(BRCFCheckpointsInRange(0, 40000, hits, 8) == 0);
    printf("cf_checkpoint_lookup_kat: ALL PASS\n");
    return 0;
}
