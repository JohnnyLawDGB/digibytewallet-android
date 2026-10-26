// Host KAT for BRRecoveryDate.h -- the dates a restore offers and the floor each gives.
// The table is Android's RecoveryDateScreen.recoveryDateOptions, value for value.
//
// Built twice by run.sh:
//   -DRECOVERY_DATE_RED  the floor rule without its week of margin; every RED case fails
//   (default)            the shipped rule; every case passes

#define _DEFAULT_SOURCE
#include "BRRecoveryDate.h"
#include <stdio.h>
#include <time.h>

static int g_failed = 0;
static void result(const char *name, int pass, const char *detail)
{
    if (! pass) { printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : ""); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

int main(void)
{
    static const struct { int year; uint32_t ts; } android[] = {
        { 2026, 1767225600u }, { 2025, 1735689600u }, { 2024, 1704067200u }, { 2023, 1672531200u },
        { 2022, 1640995200u }, { 2021, 1609459200u }, { 2020, 1577836800u }, { 2019, 1546300800u },
        { 2018, 1514764800u }, { 2017, 1483228800u }, { 2016, 1451606400u }, { 2015, 1420070400u },
        { 2014, 1389388394u },
    };
    char why[160];
    int ok = 1;

    for (size_t i = 0; i < sizeof(android) / sizeof(android[0]); i++) {
        if (BRRecoveryDateTimestamp(android[i].year) != android[i].ts) {
            ok = 0; snprintf(why, sizeof(why), "%d: %u, Android %u", android[i].year, BRRecoveryDateTimestamp(android[i].year), android[i].ts);
        }
    }
    result("androids_table_value_for_value", ok, ok ? NULL : why);

    ok = 1;
    for (int y = 2015; y <= BR_RECOVERY_LAST_YEAR; y++) {
        struct tm t = { 0 };
        t.tm_year = y - 1900; t.tm_mon = 0; t.tm_mday = 1;
        if ((uint32_t)timegm(&t) != BRRecoveryDateTimestamp(y)) { ok = 0; snprintf(why, sizeof(why), "%d", y); }
    }
    result("january_first_utc_by_timegm", ok, ok ? NULL : why);

    result("outside_the_table_is_zero",
           BRRecoveryDateTimestamp(2013) == 0 && BRRecoveryDateTimestamp(BR_RECOVERY_LAST_YEAR + 1) == 0 &&
           BR_RECOVERY_LAST_YEAR - BR_RECOVERY_FIRST_YEAR + 1 == 13, NULL);

    // Floors: never later for an earlier year, the genesis date scans from the first checkpoint,
    // and every floor's checkpoint is at least a week older than the date.
    {
        const BRCheckPoint *cp = BRMainNetCheckpoints;
        size_t n = sizeof(BRMainNetCheckpoints) / sizeof(*BRMainNetCheckpoints);
        uint32_t prev = UINT32_MAX;
        int mono = 1, margin = 1;
        for (int y = BR_RECOVERY_LAST_YEAR; y >= BR_RECOVERY_FIRST_YEAR; y--) {
            uint32_t ts = BRRecoveryDateTimestamp(y), h = BRRecoveryBirthCheckpointHeight(cp, n, ts);
            if (h > prev) mono = 0;
            prev = h;
            for (size_t i = 0; i < n; i++)
                if (cp[i].height == h && i > 0 && (uint64_t)cp[i].timestamp + BR_RECOVERY_FLOOR_MARGIN >= ts) { margin = 0; snprintf(why, sizeof(why), "%d floor %u at %u", y, h, cp[i].timestamp); }
        }
        result("floors_never_later_for_an_earlier_year", mono, NULL);
        result("genesis_scans_from_the_first_checkpoint", BRRecoveryBirthCheckpointHeight(cp, n, BR_RECOVERY_GENESIS_TIMESTAMP) == cp[0].height, NULL);
        result("a_week_of_margin_below_every_floor", margin, margin ? NULL : why);

        // A checkpoint exactly at, or inside the week before, the creation time is not the floor.
        {
            BRCheckPoint t[3] = { { 0, UINT256_ZERO, 1000, 0 }, { 100, UINT256_ZERO, 2000000, 0 }, { 200, UINT256_ZERO, 3000000, 0 } };
            result("margin_edges",
                   BRRecoveryBirthCheckpointHeight(t, 3, 3000000) == 100 &&
                   BRRecoveryBirthCheckpointHeight(t, 3, 3000000 + BR_RECOVERY_FLOOR_MARGIN) == 100 &&
                   BRRecoveryBirthCheckpointHeight(t, 3, 3000000 + BR_RECOVERY_FLOOR_MARGIN + 1) == 200 &&
                   BRRecoveryBirthCheckpointHeight(t, 3, 0) == 0 && BRRecoveryBirthCheckpointHeight(NULL, 0, 5) == 0, NULL);
        }
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
