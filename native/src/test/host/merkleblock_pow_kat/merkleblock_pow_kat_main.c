// merkleblock_pow_kat -- every header the wallet accepts carries a proof-of-work hash computed with the
// algorithm its version names, that hash meets the header's target, and a header whose algorithm is
// unknown has no hash and is not accepted.
//
// FIXTURES. headers.inc holds real headers from full nodes on both chains (mainnet and testnet26), each
// with the node's own pow_hash and pow_algo: every algorithm, every algorithm boundary +-1, the last
// groestl blocks before their retirement, two Odocrypt key-epoch edge pairs on mainnet and one on testnet.
//
// CASES (selected by argv[1]; each prints one "RESULT <case> <verdict>" line that run.sh reads):
//   rows          for every row: Parse -> blockHash equals the node's; re-serialisation is byte-identical to
//                 the wire header; BRMerkleBlockAlgo names the node's algorithm; BRMerkleBlockPoWHash equals
//                 the node's pow_hash; IsValid accepts. (level-independent except IsValid, true at every level)
//   mutated       every row with its nonce changed: verdict "rejected" if IsValid refuses ALL of them,
//                 "accepted" if it accepts ALL of them, "mixed" otherwise
//   unknown       rows re-versioned to algorithm bits 0xA00, 0xC00, 0xF00 and 0x100/0x300 (bit 8 set):
//                 Algo is UNKNOWN and PoWHash returns 0 at every level; verdict as for `mutated`
//   counter       BRMerkleBlockPoWMismatchCount after `rows` (unchanged), `mutated` and `unknown`: prints
//                 "exact" if it grew by exactly one per mutated/unknown header, "zero" if it never moved
//   algo_table    BRChainParamsAlgoAllowed at every boundary on both chains, and every fixture row is
//                 allowed at its own height on its own chain
//   odo_interval  OdoKey with the chain interval reproduces the node's odo_key on every Odo row; keys differ
//                 across each epoch edge; an Odo header hashed with the OTHER chain's interval does not
//                 reproduce the node's pow_hash (for rows whose key differs between the intervals)
//   field_zero    GUARD: the powHash struct field is never written (zero after Parse and IsValid)
//   --bench       ns/header per algorithm over the fixture rows x BENCH_ROUNDS (not a pass/fail)
//
// The level is DGB_HEADER_POW_CHECK, set by run.sh per arm: level 0 must reproduce the verdicts of the
// builds before the check existed (mutated and unknown headers ACCEPTED), level 1 must accept and count,
// level 2 must reject.
#include "BRMerkleBlock.h"
#include "BRChainParams.h"
#include "BRCrypto.h"
#include "BRNetwork.h"
#include "crypto/odocrypt.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdarg.h>

typedef struct {
    uint32_t height;
    const char *hdr;        // 80 bytes, wire order, hex
    const char *hash;       // display order
    const char *powHash;    // display order, the node's
    const char *algo;       // the node's pow_algo name
    int chain;              // 0 mainnet, 1 testnet
    uint32_t odoKey;        // the node's odo_key, 0 when not Odo
} KatRow;

#include "headers.inc"

#ifndef BENCH_ROUNDS
#define BENCH_ROUNDS 1000
#endif

static int g_fail = 0;
static void check(int cond, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void check(int cond, const char *fmt, ...)
{
    va_list ap;
    printf("\n  [%s] ", cond ? "PASS" : "FAIL");
    va_start(ap, fmt); vprintf(fmt, ap); va_end(ap);
    if (! cond) g_fail = 1;
}

static void hex2bin(const char *h, uint8_t *b, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v = 0; sscanf(h + 2*i, "%2x", &v); b[i] = (uint8_t)v; }
}

static UInt256 display2u256(const char *h)
{
    UInt256 u; uint8_t b[32];
    hex2bin(h, b, 32);
    for (int i = 0; i < 32; i++) u.u8[i] = b[31 - i];
    return u;
}

static int algo_of(const char *name)
{
    if (! strcmp(name, "scrypt"))  return BLOCK_VERSION_SCRYPT;
    if (! strcmp(name, "sha256d")) return BLOCK_VERSION_SHA256D;
    if (! strcmp(name, "groestl")) return BLOCK_VERSION_GROESTL;
    if (! strcmp(name, "skein"))   return BLOCK_VERSION_SKEIN;
    if (! strcmp(name, "qubit"))   return BLOCK_VERSION_QUBIT;
    if (! strcmp(name, "odo"))     return BLOCK_VERSION_ODO;
    return BLOCK_ALGO_UNKNOWN;
}

static uint32_t interval_of(int chain) { return chain ? ODOCRYPT_SHAPECHANGE_INTERVAL_TESTNET : ODOCRYPT_CHAPECHANGE_INTERVAL; }

static BRMerkleBlock *parse_row(const KatRow *r, uint8_t wire[80])
{
    hex2bin(r->hdr, wire, 80);
    BRSetNetwork(r->chain);
    return BRMerkleBlockParse(wire, 80);
}

// ---- rows ---------------------------------------------------------------------------------------
static int case_rows(void)
{
    int ok = 1;
    for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
        const KatRow *r = &kat_rows[i];
        uint8_t wire[80], again[80];
        BRMerkleBlock *b = parse_row(r, wire);
        UInt256 pow = UINT256_ZERO, want = display2u256(r->powHash);
        int rowOk = 1;

        rowOk &= UInt256Eq(b->blockHash, display2u256(r->hash));
        rowOk &= BRMerkleBlockSerialize(b, again, sizeof(again)) == 80 && memcmp(wire, again, 80) == 0;
        rowOk &= BRMerkleBlockAlgo(b) == algo_of(r->algo);
        rowOk &= BRMerkleBlockPoWHash(b, &pow) == 1;
        rowOk &= UInt256Eq(pow, want);
        rowOk &= BRMerkleBlockIsValid(b, b->timestamp + 60) == 1;
        if (! rowOk) {
            printf("\n  row h=%u chain=%d algo=%s: hash %s serialize %s algo %s pow %s (%s) valid %s",
                   r->height, r->chain, r->algo,
                   UInt256Eq(b->blockHash, display2u256(r->hash)) ? "ok" : "DIFFERS",
                   (BRMerkleBlockSerialize(b, again, sizeof(again)) == 80 && ! memcmp(wire, again, 80)) ? "ok" : "DIFFERS",
                   BRMerkleBlockAlgo(b) == algo_of(r->algo) ? "ok" : BRMerkleBlockAlgoName(BRMerkleBlockAlgo(b)),
                   UInt256Eq(pow, want) ? "ok" : "DIFFERS", u256hex(pow),
                   BRMerkleBlockIsValid(b, b->timestamp + 60) ? "ok" : "REFUSED");
        }
        ok &= rowOk;
        BRMerkleBlockFree(b);
    }
    check(ok, "every fixture row: hash, byte-identical re-serialisation, algorithm, node's pow_hash, IsValid");
    printf("\nRESULT rows %s\n", ok ? "pass" : "fail");
    return ok;
}

// ---- mutated / unknown ---------------------------------------------------------------------------
static void verdict(const char *name, size_t accepted, size_t rejected, size_t total)
{
    const char *v = (rejected == total) ? "rejected" : (accepted == total) ? "accepted" : "mixed";
    printf("\n  %s: %zu accepted, %zu rejected of %zu", name, accepted, rejected, total);
    printf("\nRESULT %s %s\n", name, v);
}

static void case_mutated(void)
{
    size_t accepted = 0, rejected = 0;
    for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
        uint8_t wire[80];
        BRMerkleBlock *b = parse_row(&kat_rows[i], wire);
        b->nonce ^= 0x5a5a5a5au;           // a different header: its real hash no longer meets its target
        if (BRMerkleBlockIsValid(b, b->timestamp + 60)) accepted++; else rejected++;
        BRMerkleBlockFree(b);
    }
    verdict("mutated", accepted, rejected, KAT_ROW_COUNT);
}

static const uint32_t unknown_bits[] = { 0xA00u, 0xC00u, 0xF00u, 0x100u, 0x300u, 0x500u, 0x700u, 0x900u, 0xB00u, 0xD00u };
#define UNKNOWN_COUNT (sizeof(unknown_bits)/sizeof(unknown_bits[0]))

static void case_unknown(void)
{
    size_t accepted = 0, rejected = 0, total = 0;
    int shape = 1;
    for (size_t i = 0; i < KAT_ROW_COUNT; i += 5) {        // a spread of rows across both chains and all algorithms
        for (size_t u = 0; u < UNKNOWN_COUNT; u++) {
            uint8_t wire[80];
            BRMerkleBlock *b = parse_row(&kat_rows[i], wire);
            UInt256 pow = UINT256_ZERO;
            b->version = (b->version & ~(uint32_t)BLOCK_VERSION_ALGO) | unknown_bits[u];
            shape &= BRMerkleBlockAlgo(b) == BLOCK_ALGO_UNKNOWN;
            shape &= BRMerkleBlockPoWHash(b, &pow) == 0 && UInt256IsZero(pow);   // returns 0, *out untouched
            if (BRMerkleBlockIsValid(b, b->timestamp + 60)) accepted++; else rejected++;
            total++;
            BRMerkleBlockFree(b);
        }
    }
    check(shape, "every unknown algorithm value (incl. bit 8 set): Algo is UNKNOWN, PoWHash returns 0 and writes nothing");
    verdict("unknown", accepted, rejected, total);
}

static void case_counter(void)
{
    uint32_t c0 = BRMerkleBlockPoWMismatchCount();
    (void)case_rows();
    uint32_t c1 = BRMerkleBlockPoWMismatchCount();
    case_mutated();
    uint32_t c2 = BRMerkleBlockPoWMismatchCount();
    case_unknown();
    uint32_t c3 = BRMerkleBlockPoWMismatchCount();
    size_t unknownTotal = 0;
    for (size_t i = 0; i < KAT_ROW_COUNT; i += 5) unknownTotal += UNKNOWN_COUNT;

    printf("\n  counter: start %u, after rows %u, after mutated %u, after unknown %u (rows %zu, unknown %zu)",
           c0, c1, c2, c3, (size_t)KAT_ROW_COUNT, unknownTotal);
    int exact = c1 == c0 && c2 == c1 + KAT_ROW_COUNT && c3 == c2 + unknownTotal;
    int zero = c0 == 0 && c3 == 0;
    printf("\nRESULT counter %s\n", exact ? "exact" : zero ? "zero" : "other");
}

// ---- allowed algorithm by height -----------------------------------------------------------------
typedef struct { int chain; uint32_t height; int algo; int allowed; const char *why; } AlgoCase;

static void case_algo_table(void)
{
    static const AlgoCase t[] = {
        // mainnet: scrypt-only through 145,000 (145,000 itself is scrypt v2; 145,001 is the first sha256d)
        { 0, 1,        BLOCK_VERSION_SCRYPT,  1, "genesis era scrypt" },
        { 0, 1,        BLOCK_VERSION_SHA256D, 0, "sha256d before multi-algo" },
        { 0, 144999,   BLOCK_VERSION_SCRYPT,  1, "last-but-one scrypt-only height" },
        { 0, 145000,   BLOCK_VERSION_SCRYPT,  1, "145,000 is still scrypt-only" },
        { 0, 145000,   BLOCK_VERSION_SHA256D, 0, "sha256d at 145,000" },
        { 0, 145001,   BLOCK_VERSION_SHA256D, 1, "first sha256d height" },
        { 0, 145001,   BLOCK_VERSION_GROESTL, 1, "groestl from multi-algo" },
        { 0, 145001,   BLOCK_VERSION_ODO,     0, "odo before its activation" },
        { 0, 400000,   BLOCK_VERSION_GROESTL, 1, "groestl mid-chain" },
        { 0, 9112319,  BLOCK_VERSION_ODO,     0, "odo one below activation" },
        { 0, 9112319,  BLOCK_VERSION_GROESTL, 1, "groestl one below odo activation" },
        { 0, 9112320,  BLOCK_VERSION_ODO,     1, "first odo height" },
        { 0, 9112320,  BLOCK_VERSION_GROESTL, 1, "groestl at odo activation (not enforced then)" },
        { 0, 23807986, BLOCK_VERSION_GROESTL, 1, "real groestl block in the grandfathered band" },
        { 0, 23807995, BLOCK_VERSION_GROESTL, 1, "last real groestl block" },
        { 0, 23807999, BLOCK_VERSION_GROESTL, 1, "last grandfathered height" },
        { 0, 23807999, BLOCK_VERSION_ODO,     1, "odo in the grandfathered band" },
        { 0, 23808000, BLOCK_VERSION_GROESTL, 0, "groestl at the retirement height" },
        { 0, 23808000, BLOCK_VERSION_SHA256D, 1, "sha256d at the retirement height" },
        { 0, 23808001, BLOCK_VERSION_ODO,     1, "odo after retirement" },
        { 0, 23808001, BLOCK_VERSION_GROESTL, 0, "groestl after retirement" },
        { 0, 24278140, BLOCK_VERSION_SKEIN,   1, "recent skein" },
        { 0, 24278140, BLOCK_VERSION_QUBIT,   1, "recent qubit" },
        { 0, 24278140, BLOCK_VERSION_SCRYPT,  1, "recent scrypt" },
        { 0, 1,        BLOCK_ALGO_UNKNOWN,    0, "unknown at 1" },
        { 0, 9112320,  BLOCK_ALGO_UNKNOWN,    0, "unknown in the grandfathered band" },
        { 0, 24278140, BLOCK_ALGO_UNKNOWN,    0, "unknown recent" },
        // testnet26: scrypt-only genesis; groestl set through 500 (prev-height keying); odo set from 501
        { 1, 0,        BLOCK_VERSION_SCRYPT,  1, "testnet genesis scrypt" },
        { 1, 0,        BLOCK_VERSION_SHA256D, 0, "testnet genesis sha256d" },
        { 1, 1,        BLOCK_VERSION_GROESTL, 1, "testnet groestl from height 1" },
        { 1, 1,        BLOCK_VERSION_ODO,     0, "testnet odo before activation" },
        { 1, 410,      BLOCK_VERSION_GROESTL, 1, "testnet real groestl" },
        { 1, 499,      BLOCK_VERSION_GROESTL, 1, "testnet groestl at 499" },
        { 1, 499,      BLOCK_VERSION_ODO,     0, "testnet odo at 499" },
        { 1, 500,      BLOCK_VERSION_GROESTL, 1, "testnet real groestl at 500 (prev 499 < 500)" },
        { 1, 500,      BLOCK_VERSION_ODO,     0, "testnet odo at 500 (prev 499 < 500)" },
        { 1, 501,      BLOCK_VERSION_SHA256D, 1, "testnet real sha256d at 501" },
        { 1, 501,      BLOCK_VERSION_GROESTL, 0, "testnet groestl at 501" },
        { 1, 501,      BLOCK_VERSION_ODO,     1, "testnet odo allowed from 501" },
        { 1, 519,      BLOCK_VERSION_ODO,     1, "testnet first real odo block" },
        { 1, 435500,   BLOCK_VERSION_GROESTL, 0, "testnet groestl recent" },
        { 1, 435500,   BLOCK_VERSION_QUBIT,   1, "testnet qubit recent" },
        { 1, 1,        BLOCK_ALGO_UNKNOWN,    0, "testnet unknown at 1" },
        { 1, 519,      BLOCK_ALGO_UNKNOWN,    0, "testnet unknown at 519" },
    };
    int ok = 1;
    for (size_t i = 0; i < sizeof(t)/sizeof(t[0]); i++) {
        const BRChainParams *p = t[i].chain ? &BRTestNetParams : &BRMainNetParams;
        int got = BRChainParamsAlgoAllowed(p, t[i].height, t[i].algo);
        if (got != t[i].allowed) {
            printf("\n  table: chain=%d h=%u algo=%s expected %d got %d (%s)", t[i].chain, t[i].height,
                   BRMerkleBlockAlgoName(t[i].algo), t[i].allowed, got, t[i].why);
            ok = 0;
        }
    }
    check(ok, "allowed-algorithm table at every boundary on both chains");

    int rowsOk = 1;
    for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
        const KatRow *r = &kat_rows[i];
        const BRChainParams *p = r->chain ? &BRTestNetParams : &BRMainNetParams;
        if (! BRChainParamsAlgoAllowed(p, r->height, algo_of(r->algo))) {
            printf("\n  fixture row not allowed at its own height: chain=%d h=%u algo=%s", r->chain, r->height, r->algo);
            rowsOk = 0;
        }
    }
    check(rowsOk, "every fixture row is allowed at its own height on its own chain");
    check(BRMainNetParams.odoShapechangeInterval == ODOCRYPT_CHAPECHANGE_INTERVAL &&
          BRTestNetParams.odoShapechangeInterval == ODOCRYPT_SHAPECHANGE_INTERVAL_TESTNET,
          "the params' Odocrypt intervals are the ones the hash uses");
    printf("\nRESULT algo_table %s\n", (ok && rowsOk && ! g_fail) ? "pass" : "fail");
}

// ---- Odocrypt key interval -----------------------------------------------------------------------
static void case_odo_interval(void)
{
    int keysOk = 1, edgesOk = 1, crossOk = 1, crossSeen = 0;
    const KatRow *prevOdo = NULL;

    for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
        const KatRow *r = &kat_rows[i];
        if (algo_of(r->algo) != BLOCK_VERSION_ODO) continue;
        uint8_t wire[80];
        BRMerkleBlock *b = parse_row(r, wire);
        uint32_t own = interval_of(r->chain), other = interval_of(! r->chain);

        // the chain's interval reproduces the node's key
        if (r->odoKey && OdoKey(b->timestamp, own) != r->odoKey) {
            printf("\n  odo key: chain=%d h=%u t=%u key %u expected %u", r->chain, r->height, b->timestamp, OdoKey(b->timestamp, own), r->odoKey);
            keysOk = 0;
        }

        // consecutive Odo rows of the same chain that the fixtures place on either side of an epoch edge
        if (prevOdo && prevOdo->chain == r->chain && prevOdo->odoKey && r->odoKey && prevOdo->odoKey != r->odoKey) {
            uint8_t pw[80]; BRMerkleBlock *pb = parse_row(prevOdo, pw); BRSetNetwork(r->chain);
            edgesOk &= OdoKey(pb->timestamp, own) != OdoKey(b->timestamp, own);
            edgesOk &= (OdoKey(b->timestamp, own) % own) == 0;
            printf("\n  epoch edge chain=%d: h=%u key %u -> h=%u key %u", r->chain, prevOdo->height, OdoKey(pb->timestamp, own), r->height, OdoKey(b->timestamp, own));
            BRMerkleBlockFree(pb);
        }

        // hashed with the other chain's interval: a different key gives a different hash
        if (OdoKey(b->timestamp, own) != OdoKey(b->timestamp, other)) {
            UInt256 pow = UINT256_ZERO, want = display2u256(r->powHash);
            BRSetNetwork(! r->chain);
            crossOk &= BRMerkleBlockPoWHash(b, &pow) == 1 && ! UInt256Eq(pow, want);
            crossOk &= BRMerkleBlockIsValid(b, b->timestamp + 60) == (DGB_HEADER_POW_CHECK >= 2 ? 0 : 1);
            BRSetNetwork(r->chain);
            crossSeen++;
        }
        prevOdo = r;
        BRMerkleBlockFree(b);
    }
    check(keysOk, "OdoKey with the chain's interval reproduces the node's odo_key on every Odo row");
    check(edgesOk, "keys differ across every fixture epoch edge and the new key is a multiple of the interval");
    check(crossOk && crossSeen >= 2, "%d Odo rows hashed with the other chain's interval do not reproduce the node's hash", crossSeen);
    check(OdoKey(1790380894u, 864000) == 1790208000u && OdoKey(1790380894u, 86400) == 1790380800u,
          "OdoKey arithmetic: 10-day and 1-day intervals on one timestamp");
    printf("\nRESULT odo_interval %s\n", (keysOk && edgesOk && crossOk && crossSeen >= 2) ? "pass" : "fail");
}

// ---- the struct field ----------------------------------------------------------------------------
static void case_field_zero(void)
{
    int ok = 1;
    for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
        uint8_t wire[80];
        BRMerkleBlock *b = parse_row(&kat_rows[i], wire);
        ok &= UInt256IsZero(b->powHash);
        (void)BRMerkleBlockIsValid(b, b->timestamp + 60);
        ok &= UInt256IsZero(b->powHash);
        BRMerkleBlockFree(b);
    }
    check(ok, "GUARD: the powHash struct field stays zero through Parse and IsValid");
    printf("\nRESULT field_zero %s\n", ok ? "pass" : "fail");
}

// ---- bench ---------------------------------------------------------------------------------------
static double now_ns(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec*1e9 + ts.tv_nsec; }

static void bench(void)
{
    static const char *algos[] = { "sha256d", "scrypt", "groestl", "skein", "qubit", "odo" };
    printf("bench: %d rounds per row, mainnet rows, ns/header (level %d, sizeof(void*)=%zu)\n", BENCH_ROUNDS, DGB_HEADER_POW_CHECK, sizeof(void *));
    for (size_t a = 0; a < 6; a++) {
        size_t n = 0; double t0 = now_ns(); volatile uint8_t sink = 0;
        for (size_t i = 0; i < KAT_ROW_COUNT; i++) {
            if (kat_rows[i].chain || strcmp(kat_rows[i].algo, algos[a])) continue;
            uint8_t wire[80]; BRMerkleBlock *b = parse_row(&kat_rows[i], wire);
            for (int k = 0; k < BENCH_ROUNDS; k++) { UInt256 pow; BRMerkleBlockPoWHash(b, &pow); sink ^= pow.u8[0]; n++; }
            BRMerkleBlockFree(b);
        }
        double dt = now_ns() - t0;
        printf("  %-8s %8zu headers  %10.0f ns/header  (%.1f ms per 20000)\n", algos[a], n, n ? dt/n : 0.0, n ? dt/n*20000/1e6 : 0.0);
    }
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    const char *c = argc > 1 ? argv[1] : "";
    printf("merkleblock_pow_kat: level %d, %zu fixture rows, case %s", DGB_HEADER_POW_CHECK, (size_t)KAT_ROW_COUNT, c);

    if (! strcmp(c, "rows")) case_rows();
    else if (! strcmp(c, "mutated")) case_mutated();
    else if (! strcmp(c, "unknown")) case_unknown();
    else if (! strcmp(c, "counter")) case_counter();
    else if (! strcmp(c, "algo_table")) case_algo_table();
    else if (! strcmp(c, "odo_interval")) case_odo_interval();
    else if (! strcmp(c, "field_zero")) case_field_zero();
    else if (! strcmp(c, "--bench")) { printf("\n"); bench(); return 0; }
    else { printf("\nusage: %s rows|mutated|unknown|counter|algo_table|odo_interval|field_zero|--bench\n", argv[0]); return 2; }

    printf("\n");
    return g_fail ? 1 : 0;
}
