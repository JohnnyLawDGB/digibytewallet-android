// Host KAT for BRDigiScopePins.h -- the one api.digiscope.me pin list both platforms use.
//
// Built twice by run.sh:
//   -DDIGISCOPE_PINS_RED  a retired pin left in the current list (the failure that took
//                         Android's DigiAsset metadata offline); every RED case fails
//   (default)             the shipped list; every case passes

#include "BRDigiScopePins.h"
#include <stdio.h>

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

// "sha256/" + 44 base64 characters ending in a single '=': exactly 32 bytes.
static int wellFormed(const char *p)
{
    static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    if (! p || strncmp(p, "sha256/", 7) != 0 || strlen(p) != 7 + 44 || p[7 + 43] != '=') return 0;
    for (size_t i = 7; i < 7 + 43; i++) if (! strchr(b64, p[i])) return 0;
    // the last data character carries 2 bits of padding, which must be zero
    return strchr("AEIMQUYcgkosw048", p[7 + 42]) != NULL;
}

int main(void)
{
    size_t i, j;
    int ok;
    char why[200];

    result("host", strcmp(BR_DIGISCOPE_HOST, "api.digiscope.me") == 0, BR_DIGISCOPE_HOST);

    ok = BRDigiScopePinCount() >= 2;
    for (i = 0; i < BRDigiScopePinCount(); i++) ok = ok && wellFormed(BRDigiScopePinAt(i));
    for (i = 0; i < BRDigiScopeRetiredPinCount(); i++) ok = ok && wellFormed(BRDigiScopeRetiredPinAt(i));
    result("pins_well_formed_leaf_and_intermediate", ok, NULL);

    ok = 1; why[0] = '\0';
    for (i = 0; i < BRDigiScopePinCount(); i++)
        for (j = 0; j < BRDigiScopeRetiredPinCount(); j++)
            if (strcmp(BRDigiScopePinAt(i), BRDigiScopeRetiredPinAt(j)) == 0) { ok = 0; snprintf(why, sizeof(why), "%s is both live and retired", BRDigiScopePinAt(i)); }
    result("no_retired_pin_is_live", ok, why);

    ok = 1;
    for (i = 0; i < BRDigiScopePinCount(); i++)
        for (j = i + 1; j < BRDigiScopePinCount(); j++)
            if (strcmp(BRDigiScopePinAt(i), BRDigiScopePinAt(j)) == 0) ok = 0;
    result("no_duplicate_pin", ok, NULL);

    // The live chain, read 2026-10-10: both current pins match; both retired ones never do.
    ok = BRDigiScopePinMatches("sha256/mxkNfnacx0nKcMPntNt/8/iv7iEoVNyg0WkCOt2FdU0=") &&
         BRDigiScopePinMatches("sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=");
    result("live_chain_matches", ok, NULL);

    ok = 1;
    for (j = 0; j < BRDigiScopeRetiredPinCount(); j++) ok = ok && ! BRDigiScopePinMatches(BRDigiScopeRetiredPinAt(j));
    ok = ok && ! BRDigiScopePinMatches("sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=")   // Root YE: not pinned
            && ! BRDigiScopePinMatches(NULL) && ! BRDigiScopePinMatches("") && BRDigiScopePinAt(BRDigiScopePinCount()) == NULL;
    result("retired_and_unknown_never_match", ok, NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
