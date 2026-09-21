// Host KAT: every declared count in a wire message is bounded by the bytes that
// remain, before anything is allocated.
//
// WHAT THIS ESTABLISHES. A peer-supplied message names a count -- "this
// transaction has N inputs", "this headers message carries N headers", "this
// user agent is N bytes", "this filter holds N elements" -- and the parser then
// sizes an allocation or a copy from N. The invariant this KAT locks in is that
// N is compared against the bytes actually left in the message BEFORE the parser
// trusts it, and that the comparison never performs an addition on the side that
// could wrap. Where the comparison is the length guard for a variable-length
// field, the guard is rewritten so the wrapping addition never happens.
//
// MACRO CONVENTION. This KAT uses the PRESENCE convention: the comparison (red)
// arm is built with a -D<...>_UNFIXED flag and the shipped (green) arm is built
// with no -D at all (matching the other file-static BRPeer.c host KATs in this
// tree). The seams in BRArray.h, BRTransaction.c, BRPeer.c, BRGCSFilter.c and
// BRMerkleBlock.c are therefore all `#ifdef WIRE_COUNT_BOUNDS_UNFIXED`, never
// `#if`, so the flag's ABSENCE selects the shipped (bounded) code. The per-input
// witness bound (see the third invariant below) has its own presence flag,
// WIRE_WITNESS_COUNT_UNFIXED, on the same convention.
//
// The parsers under test are reached as follows:
//   * BRTransactionParse and BRGCSFilterParse are public API, called directly.
//   * the _BRPeerAccept*Message handlers are file-static in BRPeer.c, so this
//     main #includes BRPeer.c and BRPeer.c is NOT passed as a separate unit.
//
// Each case builds its wire bytes into a heap buffer of the EXACT wire length so
// AddressSanitizer's redzones bracket the message; any access beyond the message
// bytes is then a reported error rather than a silent one. That is what lets the
// KAT observe, in the red arm, that a count was trusted past the bytes present.
//
// SECOND INVARIANT -- THE STORE HOLDS WHAT THE LOOP FILLS. A count that passes the
// bound is still only a request: BRArray.h leaves an array and its count unchanged
// when the allocator cannot satisfy a grow. BRTransactionParse therefore checks,
// after sizing each store, that the store really holds the declared number of
// entries before it fills them. The tx_store_* cases exercise that with a grow
// hook: run.sh builds them with -DKAT_GROW_HOOK -Drealloc=kat_realloc, which routes
// the realloc calls of the separately compiled core units (BRTransaction.c among
// them) through kat_realloc below, and the hook declines any request of
// kat_decline_from bytes or more. The array_insert_* cases put the inserting macros
// of BRArray.h through the same hook from this file. Their red arm is
// -DWIRE_STORE_CHECK_UNFIXED (presence convention, as above). tx_store_ctl sends the
// same message with the hook idle and must be ACCEPTED, so the rejection in the
// other two is attributable to the store check and to nothing else.
//
// THIRD INVARIANT -- THE PER-INPUT WITNESS COUNT IS BOUNDED BY THE BYTES THAT
// REMAIN. A segwit input's witness stack begins with an item count read from the
// message, and the parser walks that many items. The count is bounded by the bytes left
// (each item needs at least a one-byte length prefix) before the walk, and the
// accumulated item length is bounded as the walk proceeds, so neither the walk
// count nor the accumulated length can run past the buffer. The red evidence for
// tx_witness is not a sanitizer report but a call that DOES NOT RETURN: with the
// bound absent, a count near the type maximum makes the walk take that many
// steps. run.sh time-limits that arm and treats "stopped at the limit" as its
// expected result. tx_witness_ctl is the control: an honest two-item witness on
// the same transaction, which must be ACCEPTED and whose bytes must round-trip.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>

#ifdef KAT_GROW_HOOK
// -Drealloc=kat_realloc renames the call in every unit, this one included; take the
// real one back here so the hook can forward to it.
#undef realloc
extern void *realloc(void *ptr, size_t size);
static size_t kat_decline_from = (size_t)-1;   // idle: forwards everything
void *kat_realloc(void *ptr, size_t size)
{
    return (size >= kat_decline_from) ? NULL : realloc(ptr, size);
}
#endif

// Reach the file-static peer message handlers. BRPeer.c must NOT also be a
// separate compilation unit in run.sh.
#include "BRPeer.c"

#include "BRTransaction.h"
#include "BRGCSFilter.h"

// ---- little helpers ---------------------------------------------------------

// Duplicate literal bytes into an exact-length heap buffer (ASan-bracketed).
static uint8_t *dup_exact(const uint8_t *bytes, size_t len)
{
    uint8_t *p = (uint8_t *)malloc(len ? len : 1);
    if (len) memcpy(p, bytes, len);
    return p;
}

// Store an 8-byte little-endian value.
static void put_u64le(uint8_t *p, uint64_t v)
{
    for (int i = 0; i < 8; i++) p[i] = (uint8_t)(v >> (8 * i));
}

// Store a 4-byte little-endian value.
static void put_u32le(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (8 * i));
}

// ---- case: transaction input/output count ----------------------------------
//
// The 13-byte transaction 01 00 00 00 ff ab aa aa aa aa aa aa 02: a 4-byte
// version, then a CompactSize 0xff introducing an 8-byte input count of
// 0x02aaaaaaaaaaaaab. Nothing follows. Each input needs at least 41 bytes, so the
// declared count is far larger than the zero bytes that remain. The bounded
// parser rejects it before the input array is sized; the red arm sizes the array
// from the unbounded count instead, and the sanitizer trips.
static int case_tx(void)
{
    static const uint8_t bytes[] = {
        0x01, 0x00, 0x00, 0x00,             // version = 1
        0xff,                               // CompactSize: 8-byte count follows
        0xab, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0x02 // input count
    };
    size_t len = sizeof(bytes);
    uint8_t *buf = dup_exact(bytes, len);
    BRTransaction *tx = BRTransactionParse(buf, len);
    int accepted = (tx != NULL);
    if (tx) BRTransactionFree(tx);
    free(buf);
    return accepted;
}

// ---- case: headers count ----------------------------------------------------
//
// A headers message is a CompactSize count then 81 bytes per header. The count is
// chosen per word size so that the product 81*count wraps size_t and lands low:
// 3188326136196712625 on 64-bit, 53024288 on 32-bit. The bounded parser bounds
// the count by (msgLen-off)/81 -- a comparison with no addition on the wrapping
// side -- and rejects the message before acting on the count. The red arm keeps
// the addition-first guard, which the wrapped product satisfies, so it proceeds
// on the declared count and the sanitizer trips.
static int case_headers(void)
{
    uint8_t raw[64];
    size_t off = 0;
    if (sizeof(size_t) >= 8) {
        raw[off++] = 0xff;
        put_u64le(&raw[off], (uint64_t)3188326136196712625ULL);
        off += 8;
    } else {
        raw[off++] = 0xfe;
        put_u32le(&raw[off], (uint32_t)53024288u);
        off += 4;
    }
    // Pad so the wrapped-low length guard is satisfied and the fixed header
    // fields the handler reads are in-buffer. With the count left unbounded the
    // handler proceeds on the full declared count; in this exact-length buffer the
    // sanitizer then trips. The bounded parser rejects before that point.
    size_t pad = 96;
    size_t len = off + pad;
    uint8_t *buf = (uint8_t *)malloc(len);
    memcpy(buf, raw, off);
    memset(buf + off, 0, pad);

    BRPeer *peer = BRPeerNew(0x12345678);
    int r = _BRPeerAcceptHeadersMessage(peer, buf, len);
    BRPeerFree(peer);
    free(buf);
    return r; // 1 = accepted, 0 = rejected
}

// ---- case: version user-agent length ---------------------------------------
//
// The version message ends with a CompactSize user-agent length then that many
// bytes then a 4-byte start height. The chosen length is such that value plus
// offset plus four wraps size_t, so the original addition-first guard is
// satisfied. The bounded guard compares the length against the bytes that remain
// with no addition on the wrapping side and rejects the message; the red arm
// accepts it and the sanitizer trips.
static int case_version(void)
{
    size_t len = 89; // 80 fixed fields + a 9-byte CompactSize user-agent length
    uint8_t *buf = (uint8_t *)malloc(len);
    memset(buf, 0, len);
    put_u32le(&buf[0], 70018u);            // protocol version >= MIN_PROTO_VERSION
    // services(8) timestamp(8) recvServices(8) recvAddr(16) recvPort(2)
    // fromServices(8) fromAddr(16) fromPort(2) nonce(8) fill offsets 4..80.
    buf[80] = 0xff;                        // CompactSize: 8-byte length follows
    put_u64le(&buf[81], (uint64_t)0xFFFFFFFFFFFFFFA3ULL); // wraps 64- and 32-bit

    BRPeer *peer = BRPeerNew(0x12345678);
    int r = _BRPeerAcceptVersionMessage(peer, buf, len);
    BRPeerFree(peer);
    free(buf);
    return r;
}

// ---- case: reject string length --------------------------------------------
//
// The reject message opens with a CompactSize message-type length. The chosen
// length is such that length plus offset plus one wraps size_t, so the original
// addition-first guard is satisfied. The bounded guard compares the length
// against the bytes that remain with no addition on the wrapping side and rejects
// the message before the type string is read; the red arm accepts it and the
// sanitizer trips.
static int case_reject(void)
{
    size_t len = 16; // 9-byte CompactSize length + 7 non-zero filler bytes
    uint8_t *buf = (uint8_t *)malloc(len);
    buf[0] = 0xff;                         // CompactSize: 8-byte length follows
    put_u64le(&buf[1], (uint64_t)0xFFFFFFFFFFFFFFF6ULL); // wraps 64- and 32-bit
    memset(&buf[9], 0xaa, len - 9);        // non-zero: no NUL within the present bytes

    BRPeer *peer = BRPeerNew(0x12345678);
    int r = _BRPeerAcceptRejectMessage(peer, buf, len);
    BRPeerFree(peer);
    free(buf);
    return r;
}

// ---- case: compact-filter element count (32-bit) ---------------------------
//
// The GCS filter payload begins with a CompactSize element count N, then the
// Golomb-Rice stream. The decoder sizes an N * sizeof(uint64_t) array; on a
// 32-bit target that product wraps size_t, so the size computation no longer
// bounds N. Payload fe 01 00 00 20 00 00 00 00 00 sets N = 0x20000001 with five
// zero stream bytes behind it. The bounded parser rejects N before allocating,
// because the size must fit size_t and N cannot exceed the remaining bit budget.
// On a 64-bit target the product does not wrap, so the red arm returns "rejected"
// without the sanitizer tripping; the case is DEFERRED-RED there.
static int case_cf(void)
{
    static const uint8_t bytes[] = {
        0xfe, 0x01, 0x00, 0x00, 0x20,       // CompactSize N = 0x20000001
        0x00, 0x00, 0x00, 0x00, 0x00        // Golomb-Rice stream (all zero)
    };
    size_t len = sizeof(bytes);
    uint8_t *buf = dup_exact(bytes, len);
    BRGCSFilter *f = BRGCSFilterParse(buf, len, 0, 0,
                                      BR_GCS_BASIC_FILTER_P, BR_GCS_BASIC_FILTER_M);
    int accepted = (f != NULL);
    if (f) BRGCSFilterFree(f);
    free(buf);
    return accepted;
}

// ---- cases: per-input witness item count -----------------------------------
//
// A segwit transaction carries, after its outputs, one witness stack per input:
// a CompactSize item count, then each item as a CompactSize length prefix
// followed by that many bytes. The count and the accumulated item length are both
// read from the message. Once the buffer is exhausted BRVarInt returns 0 and reports a
// one-byte length, so the walk advances one byte per declared item; a count near
// its type's maximum makes the walk run for that many steps -- it does not return
// in any practical time. The bounded parser bounds the count by the bytes that
// remain (each item needs at least its one-byte prefix) before the walk, and
// bounds the accumulated length as it walks, so the message is rejected.
//
// Both cases share a well-formed prefix (version, segwit marker+flag, one input,
// one output); they differ only in the witness stack.

// Write the shared prefix into p and return its length (58): 4-byte version, the
// segwit marker/flag pair, a one-input body with an empty scriptSig, and a
// one-output body with an empty scriptPubKey.
static size_t put_segwit_prefix(uint8_t *p)
{
    size_t o = 0;
    put_u32le(&p[o], 1); o += 4;                 // version = 1
    p[o++] = 0x00;                               // segwit marker (reads as input count 0)
    p[o++] = 0x01;                               // segwit flag
    p[o++] = 0x01;                               // real input count = 1
    memset(&p[o], 0x11, 32); o += 32;            // previous-output hash
    put_u32le(&p[o], 0); o += 4;                 // previous-output index
    p[o++] = 0x00;                               // empty scriptSig
    put_u32le(&p[o], 0xffffffff); o += 4;        // sequence
    p[o++] = 0x01;                               // output count = 1
    put_u64le(&p[o], 100000000ULL); o += 8;      // value
    p[o++] = 0x00;                               // empty scriptPubKey
    return o;                                     // 58
}

// One input whose witness stack declares a count far larger than the bytes
// present: a VAR_INT64 count of 0xFFFFFFFFFFFFFFF0 with no items behind it and
// only the 4-byte lock time remaining. The bounded parser rejects it; the
// comparison arm walks the declared count and does not return.
static int case_tx_witness(void)
{
    uint8_t raw[128];
    size_t o = put_segwit_prefix(raw);
    raw[o++] = 0xff;                             // CompactSize: 8-byte count follows
    put_u64le(&raw[o], (uint64_t)0xFFFFFFFFFFFFFFF0ULL); o += 8;
    put_u32le(&raw[o], 0); o += 4;              // lock time
    size_t len = o;
    uint8_t *buf = dup_exact(raw, len);
    BRTransaction *tx = BRTransactionParse(buf, len);
    int accepted = (tx != NULL);
    if (tx) BRTransactionFree(tx);
    free(buf);
    return accepted;
}

// Control: the same transaction with an honest witness -- two small items, a
// two-byte one and a three-byte one. It must be ACCEPTED and the parsed witness
// bytes must equal exactly what was on the wire after the item count (the two
// length-prefixed items concatenated). Returns 1 only when both hold.
static int case_tx_witness_ctl(void)
{
    static const uint8_t want[] = { 0x02, 0xAA, 0xBB, 0x03, 0xCC, 0xDD, 0xEE };
    uint8_t raw[128];
    size_t o = put_segwit_prefix(raw);
    raw[o++] = 0x02;                             // witness item count = 2
    raw[o++] = 0x02; raw[o++] = 0xAA; raw[o++] = 0xBB;                 // item 0 (2 bytes)
    raw[o++] = 0x03; raw[o++] = 0xCC; raw[o++] = 0xDD; raw[o++] = 0xEE; // item 1 (3 bytes)
    put_u32le(&raw[o], 0); o += 4;             // lock time
    size_t len = o;
    uint8_t *buf = dup_exact(raw, len);
    BRTransaction *tx = BRTransactionParse(buf, len);
    int ok = 0;
    if (tx) {
        if (tx->inCount == 1 && tx->inputs[0].witness &&
            tx->inputs[0].witLen == sizeof(want) &&
            memcmp(tx->inputs[0].witness, want, sizeof(want)) == 0) {
            ok = 1;                             // parsed and the witness bytes round-trip
        } else {
            fprintf(stderr, "tx_witness_ctl: witness did not round-trip (witLen=%zu)\n",
                    tx->inputs[0].witLen);
        }
        BRTransactionFree(tx);
    }
    free(buf);
    return ok;
}

// ---- cases: the store holds what the loop fills --------------------------------
//
// A well-formed transaction with `nIn` inputs and `nOut` outputs, every script
// empty: 4-byte version, CompactSize input count, 41 bytes per input, CompactSize
// output count, 9 bytes per output, 4-byte lock time. Both counts satisfy the
// bounds above, so the parser goes on to size the two stores.
#ifdef KAT_GROW_HOOK
static size_t put_count(uint8_t *p, size_t n)   // CompactSize, n < 0x10000
{
    if (n < 0xfd) { p[0] = (uint8_t)n; return 1; }
    p[0] = 0xfd; p[1] = (uint8_t)(n & 0xff); p[2] = (uint8_t)(n >> 8); return 3;
}

static int parse_plain_tx(size_t nIn, size_t nOut, size_t declineFrom)
{
    size_t len = 4 + 3 + nIn*41 + 3 + nOut*9 + 4, off = 0;
    uint8_t *tmp = (uint8_t *)calloc(1, len);
    put_u32le(&tmp[off], 1); off += 4;
    off += put_count(&tmp[off], nIn);
    for (size_t i = 0; i < nIn; i++) {
        tmp[off] = (uint8_t)(i + 1);          // a distinct previous-output hash
        off += 32 + 4;                        // hash + index
        tmp[off++] = 0;                       // empty script
        put_u32le(&tmp[off], 0xffffffff); off += 4;
    }
    off += put_count(&tmp[off], nOut);
    for (size_t i = 0; i < nOut; i++) { put_u64le(&tmp[off], 1000 + i); off += 8; tmp[off++] = 0; }
    put_u32le(&tmp[off], 0); off += 4;

    uint8_t *buf = dup_exact(tmp, off);       // exact wire length, ASan-bracketed
    free(tmp);
    kat_decline_from = declineFrom;
    BRTransaction *tx = BRTransactionParse(buf, off);
    kat_decline_from = (size_t)-1;
    int accepted = (tx != NULL);
    if (tx) BRTransactionFree(tx);
    free(buf);
    return accepted;
}

// 400 entries is tens of kilobytes of store in either word size, and nothing else
// on this path reallocates anywhere near 8 KiB, so the hook declines exactly the
// one grow under test.
#define KAT_STORE_DECLINE_FROM ((size_t)8192)
static int case_tx_store_in(void)  { return parse_plain_tx(400, 1, KAT_STORE_DECLINE_FROM); }
static int case_tx_store_out(void) { return parse_plain_tx(1, 400, KAT_STORE_DECLINE_FROM); }
static int case_tx_store_ctl(void) { return parse_plain_tx(400, 400, (size_t)-1); }

// ---- cases: the inserting macros ---------------------------------------------
//
// array_insert and array_insert_array grow first, and move the count and shift the
// elements only once the store holds the new total. In this file the macros' realloc
// is pointed at the hook for the duration of these three functions.
#define realloc kat_realloc

// GUARD (no comparison arm: both forms agree when every grow is satisfied). The known
// answers are the ones the core's own test.c states for these two macros.
static int case_array_insert_known(void)
{
    int *a = NULL, b[] = { 1, 2, 3 }, c[] = { 3, 2 }, ok = 1;
    array_new(a, 0);
    array_add(a, 0);
    array_add_array(a, b, 3);                       // [ 0, 1, 2, 3 ]
    array_insert(a, 0, 1);                          // [ 1, 0, 1, 2, 3 ]
    ok &= (array_count(a) == 5 && a[0] == 1 && a[1] == 0 && a[4] == 3);
    array_insert_array(a, 0, c, 2);                 // [ 3, 2, 1, 0, 1, 2, 3 ]
    ok &= (array_count(a) == 7 && a[0] == 3 && a[1] == 2 && a[2] == 1 && a[6] == 3);
    array_clear(a);
    array_add_array(a, b, 3);                       // [ 1, 2, 3 ]
    array_insert_array(a, 3, c, 2);                 // [ 1, 2, 3, 3, 2 ]
    ok &= (array_count(a) == 5 && a[3] == 3 && a[4] == 2);
    array_insert(a, 5, 1);                          // [ 1, 2, 3, 3, 2, 1 ]
    ok &= (array_count(a) == 6 && a[5] == 1);
    array_free(a);
    return ok;                                      // 1 = every known answer held
}

// A full store of four, every grow declined. The insert must leave the four entries and
// the count exactly as they were. Returns 0 ("rejected") when it did.
static int insert_into_full_store(int many)
{
    int *a = NULL, extra[] = { 7, 8, 9 }, changed;
    array_new(a, 4);
    for (int i = 0; i < 4; i++) array_add(a, 10 + i);
    kat_decline_from = 1;                           // decline every grow
    if (many) array_insert_array(a, 1, extra, 3);
    else      array_insert(a, 0, 99);
    kat_decline_from = (size_t)-1;
    changed = (array_count(a) != 4 || a[0] != 10 || a[1] != 11 || a[2] != 12 || a[3] != 13);
    array_free(a);
    return changed;
}
static int case_array_insert_full(void)       { return insert_into_full_store(0); }
static int case_array_insert_array_full(void) { return insert_into_full_store(1); }

#undef realloc
#endif

// ---- dispatch ---------------------------------------------------------------

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <tx|headers|version|reject|cf|tx_witness|tx_witness_ctl>\n", argv[0]);
        return 2;
    }
    const char *c = argv[1];
    int accepted;
    if      (strcmp(c, "tx") == 0)      accepted = case_tx();
    else if (strcmp(c, "headers") == 0) accepted = case_headers();
    else if (strcmp(c, "version") == 0) accepted = case_version();
    else if (strcmp(c, "reject") == 0)  accepted = case_reject();
    else if (strcmp(c, "cf") == 0)      accepted = case_cf();
    else if (strcmp(c, "tx_witness") == 0)     accepted = case_tx_witness();
    else if (strcmp(c, "tx_witness_ctl") == 0) accepted = case_tx_witness_ctl();
#ifdef KAT_GROW_HOOK
    else if (strcmp(c, "tx_store_in") == 0)  accepted = case_tx_store_in();
    else if (strcmp(c, "tx_store_out") == 0) accepted = case_tx_store_out();
    else if (strcmp(c, "tx_store_ctl") == 0) accepted = case_tx_store_ctl();
    else if (strcmp(c, "array_insert_known") == 0)      accepted = case_array_insert_known();
    else if (strcmp(c, "array_insert_full") == 0)       accepted = case_array_insert_full();
    else if (strcmp(c, "array_insert_array_full") == 0) accepted = case_array_insert_array_full();
#endif
    else { fprintf(stderr, "unknown case: %s\n", c); return 2; }

    // If we reach here the parser returned rather than faulting. The bounded
    // (green) parser must REJECT each of these messages -- except tx_store_ctl and
    // array_insert_known, which run.sh requires to be ACCEPTED.
    printf("RESULT %s %s\n", c, accepted ? "accepted" : "rejected");
    return accepted ? 1 : 0;
}
