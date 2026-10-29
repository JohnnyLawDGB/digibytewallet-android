// Host KAT for BRSavedBlocks.h's write-side guard -- a saved window below the persisted tip never
// replaces it. Android's BlockPersistencePolicyTest (the persistence cases), case for case and
// value for value; its hex-decoding cases test Android's preference storage and stay there.
//
// Built twice by run.sh:
//   -DSAVED_BLOCKS_GUARD_RED  the blind overwrite; every RED case fails
//   (default)                 the shipped rule; every case passes

#include "BRSavedBlocks.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static void le(uint8_t *b, size_t off, uint32_t v)
{
    b[off] = (uint8_t)v; b[off + 1] = (uint8_t)(v >> 8); b[off + 2] = (uint8_t)(v >> 16); b[off + 3] = (uint8_t)(v >> 24);
}

int main(void)
{
    result("a_higher_tip_is_persisted", BRSavedBlocksShouldPersist(23184000, 23180000) == 1);
    result("an_equal_tip_is_persisted", BRSavedBlocksShouldPersist(23660000, 23660000) == 1);
    result("a_lower_tip_is_rejected", BRSavedBlocksShouldPersist(23060000, 23176000) == 0);
    result("the_first_save_is_persisted", BRSavedBlocksShouldPersist(23500000, 0) == 1);
    {   uint8_t four[4] = { 0 };
        result("an_unreadable_top_fails_open", BRSavedBlocksTopHeight(four, sizeof(four)) == -1 &&
               BRSavedBlocksShouldPersist(-1, 23176000) == 1 && BRSavedBlocksTopHeight(NULL, 12) == -1);
    }
    {   // One byte short of the height (count 4 + len 4 + height 4 = 12): unreadable, as Kotlin's
        // data.size < 12. A "len < 11" guard would read one byte past the buffer (ASan reports it).
        uint8_t eleven[11];
        le(eleven, 0, 300); le(eleven, 4, 119); eleven[8] = 0x13; eleven[9] = 0x20; eleven[10] = 0x69;
        result("an_eleven_byte_buffer_is_unreadable", BRSavedBlocksTopHeight(eleven, sizeof(eleven)) == -1);
    }
    {   uint8_t buf[12];
        le(buf, 0, 300); le(buf, 4, 119); le(buf, 8, 23666195);
        result("the_top_height_is_read_from_the_buffer", BRSavedBlocksTopHeight(buf, sizeof(buf)) == 23666195);
    }
    {   uint8_t buf[12];   // a height above 2^31 stays positive (read unsigned, as Kotlin masks it)
        le(buf, 0, 1); le(buf, 4, 1); le(buf, 8, 0xFFFFFFFFu);
        result("a_top_above_two_to_the_31_stays_positive", BRSavedBlocksTopHeight(buf, sizeof(buf)) == 4294967295LL);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
