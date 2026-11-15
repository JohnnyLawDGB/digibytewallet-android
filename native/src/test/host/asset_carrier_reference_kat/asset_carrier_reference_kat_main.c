// Host KAT (DA-014-REF): the native DigiAsset carrier rule is the one rule the wallet shares with
// its Kotlin side, and it is DigiAsset Core's.
//
// INVARIANT. A transaction is credited from a DigiAsset carrier only when that carrier is its single
// nulldata output (OP_RETURN followed only by complete pushes) and its single "DA"-tagged output,
// and its payload classifies: DigiAsset Core reads the LAST nulldata output as the asset, after
// offering the FIRST to its KYC, exchange-rate and encrypted-key readers. Any other transaction
// with a tagged output is credited nothing and every one of its outputs is held (BRTxOutputIsAsset
// answers 1 for each); a transaction with no tagged output holds nothing.
// The vectors (vectors/da-carrier/vectors.txt, argv[1]) are the same ones the Kotlin
// DigiAssetCarrierVectorsTest reads, so the two sides cannot drift apart.
//
// RED arm (-DASSET_CARRIER_REF_UNFIXED) restores the earlier rule: the last output that frames as a
// carrier, whatever OP_RETURN follows it, and no header-level check in the verdict. It must fail every vector named RED-THEN-GREEN in run.sh.
//
// MACRO CONVENTION: PRESENCE of -DASSET_CARRIER_REF_UNFIXED (#ifdef) selects the red arm.
//
// Exit code 0 = every check passed; nonzero = a check failed or a sanitizer report fired.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "BRTransaction.h"
#include "BRDigiAsset.h"

#define MAX_OUTS 16

static int g_fail = 0, g_vectors = 0;
static void check(int c, const char *name, const char *what) {
    printf(c ? "PASS: %s: %s\n" : "FAIL: %s: %s\n", name, what);
    if (!c) g_fail++;
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// Each script in a heap block of exactly its own length, so the sanitizer sees any read past it.
static uint8_t *unhex(const char *h, size_t *len) {
    size_t n = strlen(h);
    uint8_t *b;
    if (n % 2) return NULL;
    b = malloc(n / 2 ? n / 2 : 1);
    if (!b) return NULL;
    for (size_t i = 0; i < n / 2; i++) {
        int hi = hexval(h[2 * i]), lo = hexval(h[2 * i + 1]);
        if (hi < 0 || lo < 0) { free(b); return NULL; }
        b[i] = (uint8_t)((hi << 4) | lo);
    }
    *len = n / 2;
    return b;
}

static void runVector(char *line) {
    char *save = NULL, *tok;
    char name[128], expected[32], got[32];
    BRTxOutput outs[MAX_OUTS];
    BRTransaction tx;
    size_t n = 0, idx = 0, inCount = 2, nAmt = 0;
    uint64_t amts[MAX_OUTS];
    int verdict, allHeld = 1, noneHeld = 1;

    tok = strtok_r(line, " \n", &save);               /* "v" */
    tok = strtok_r(NULL, " \n", &save); if (!tok) return;
    snprintf(name, sizeof(name), "%s", tok);
    tok = strtok_r(NULL, " \n", &save); if (!tok) return;
    snprintf(expected, sizeof(expected), "%s", tok);
    memset(outs, 0, sizeof(outs));
    while ((tok = strtok_r(NULL, " \n", &save)) != NULL && n < MAX_OUTS) {
        if (strncmp(tok, "in=", 3) == 0) { inCount = (size_t)strtoul(tok + 3, NULL, 10); continue; }
        if (strncmp(tok, "amt=", 4) == 0) {
            char *a = tok + 4;
            nAmt = 0;
            while (*a && nAmt < MAX_OUTS) { amts[nAmt++] = strtoull(a, &a, 10); if (*a == ',') a++; }
            continue;
        }
        outs[n].script = unhex(tok, &outs[n].scriptLen);
        if (!outs[n].script) { check(0, name, "a script is not hex"); goto done; }
        outs[n].amount = 700;
        n++;
    }
    for (size_t i = 0; i < nAmt && i < n; i++) outs[i].amount = amts[i];
    g_vectors++;
    memset(&tx, 0, sizeof(tx));
    tx.outputs = outs;
    tx.outCount = n;
    tx.inCount = inCount;   /* inputs are never dereferenced by the rule */

    verdict = BRTxAssetCarrier(&tx, &idx);
    if (verdict == DA_TX_NONE) snprintf(got, sizeof(got), "NONE");
    else if (verdict == DA_TX_CARRIER) snprintf(got, sizeof(got), "CARRIER:%zu", idx);
    else snprintf(got, sizeof(got), "UNDECIDED");
    {
        char what[96];
        snprintf(what, sizeof(what), "carrier verdict %s (want %s)", got, expected);
        check(strcmp(got, expected) == 0, name, what);
    }

    // What the hold answers: UNDECIDED holds every output, NONE none.
    for (size_t i = 0; i < n; i++) {
        uint8_t h = BRTxOutputIsAsset(&tx, &tx.outputs[i]);
        if (!h) allHeld = 0; else noneHeld = 0;
    }
    if (strcmp(expected, "UNDECIDED") == 0) check(allHeld, name, "every output is held");
    if (strcmp(expected, "NONE") == 0) check(noneHeld, name, "no output is held");

done:
    for (size_t i = 0; i < n; i++) free(outs[i].script);
}

int main(int argc, char **argv) {
    FILE *f;
    char line[4096];

    if (argc < 2 || !(f = fopen(argv[1], "r"))) { printf("FAIL: vectors file not readable\n"); return 1; }
    while (fgets(line, sizeof(line), f)) {
        if (line[0] == 'v' && line[1] == ' ') runVector(line);
    }
    fclose(f);
    if (g_vectors < 45) { printf("FAIL: only %d vectors read\n", g_vectors); g_fail++; }
    printf("%d vectors, %d failure(s)\n", g_vectors, g_fail);
    return g_fail ? 1 : 0;
}
