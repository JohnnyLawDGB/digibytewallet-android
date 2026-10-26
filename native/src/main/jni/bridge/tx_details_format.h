/*
 * tx_details_format.h
 *
 * The JNI-free core of getTransactionDetails (jni_wallet.c), so a host KAT
 * (tx_details_format_bound_kat) drives the code the app runs without a JVM or a wallet.
 *
 * The list is one line per transaction, for at most the TX_DETAILS_MAX_LINES most recent:
 *
 *     <txid>|<amount>|<fee>|<blockHeight>|<timestamp>|<sent>|<received>\n
 *
 * jni_wallet.c reads each row from the wallet (tx_details_row_from_wallet); this file
 * sizes the buffer and writes the lines.
 *
 * INVARIANT. The buffer is sized from the longest line the format can produce
 * (TX_DETAILS_LINE_MAX), for the number of lines the list can carry, and the write position
 * never passes the buffer, whatever the values: a line that does not fit is not written and the
 * list ends at its last complete line. Sized so, every line fits; the stop is what keeps the
 * position inside the buffer if that ever stops being true.
 *
 * -DTX_DETAILS_FORMAT_UNFIXED keeps the earlier sizing and advance, for the KAT's comparison
 * arm only; no app build defines it.
 */
#ifndef TX_DETAILS_FORMAT_H
#define TX_DETAILS_FORMAT_H

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* BRWalletTransactions returns txs sorted by date OLDEST-FIRST (BRWallet.c); the list carries
 * the most recent TX_DETAILS_MAX_LINES of them. */
#define TX_DETAILS_MAX_LINES 100

/* The longest text each field can print: the widest value of its type. */
#define TX_DETAILS_TXID_CHARS 64                                    /* 32 bytes as hex */
#define TX_DETAILS_I64_CHARS  (sizeof("-9223372036854775808") - 1)  /* 20: INT64_MIN, %lld */
#define TX_DETAILS_U64_CHARS  (sizeof("18446744073709551615") - 1)  /* 20: UINT64_MAX, %llu */
#define TX_DETAILS_U32_CHARS  (sizeof("4294967295") - 1)            /* 10: UINT32_MAX, %u */

/* The longest line the format can produce:
 *
 *     txid | amount | fee | blockHeight | timestamp | sent | received | six '|' and '\n'
 *      64  +   20   + 20  +     10      +    10     +  20  +    20    +        7          = 171
 *
 * fee is at its widest for every receive: UINT64_MAX, an input the wallet does not hold. */
#define TX_DETAILS_LINE_MAX (TX_DETAILS_TXID_CHARS + TX_DETAILS_I64_CHARS + 3 * TX_DETAILS_U64_CHARS \
                             + 2 * TX_DETAILS_U32_CHARS + 7)
_Static_assert(TX_DETAILS_LINE_MAX == 171, "TX_DETAILS_LINE_MAX: see the arithmetic above");

/* One transaction's fields as the wallet reports them. txHash is in the wallet's internal byte
 * order (UInt256.u8); the line prints it reversed, the display order a txid is known by. */
typedef struct {
    uint8_t  txHash[32];
    int64_t  amount;        /* received - sent: positive = received, negative = sent */
    uint64_t fee;           /* UINT64_MAX when an input is not the wallet's (every receive) */
    uint32_t blockHeight;   /* TX_UNCONFIRMED (INT32_MAX) while unconfirmed */
    uint32_t timestamp;
    uint64_t sent;
    uint64_t received;
} TxDetailsRow;

/* Fills *row for the i-th wallet transaction (0 <= i < txCount, oldest first) and returns 1,
 * or returns 0 to leave that transaction out of the list. */
typedef int (*TxDetailsRowFn)(void *ctx, size_t i, TxDetailsRow *row);

/* The bytes the list needs for txCount wallet transactions: TX_DETAILS_LINE_MAX for each line it
 * can carry (the TX_DETAILS_MAX_LINES most recent at most) and the NUL. At most 17,101, so the
 * product cannot wrap in any word size. */
static inline size_t tx_details_buffer_size(size_t txCount)
{
#ifdef TX_DETAILS_FORMAT_UNFIXED
    return txCount * 160 + 1;   /* comparison shape: 160 per transaction, for every transaction */
#else
    size_t lines = (txCount > TX_DETAILS_MAX_LINES) ? TX_DETAILS_MAX_LINES : txCount;
    return lines * TX_DETAILS_LINE_MAX + 1;
#endif
}

/* Appends one transaction's line at buf + *pos. Returns 1 with *pos advanced past the line when
 * the whole line and its NUL fit in what is left of bufSize. Otherwise returns 0, leaves *pos
 * where it was and writes nothing past the last complete line, so the list ends there. */
static inline int tx_details_append_line(char *buf, size_t bufSize, size_t *pos,
                                         const TxDetailsRow *row)
{
    char hashHex[TX_DETAILS_TXID_CHARS + 1];
    for (int j = 0; j < 32; j++) {
        sprintf(&hashHex[j * 2], "%02x", row->txHash[31 - j]);
    }
    hashHex[64] = '\0';

#ifndef TX_DETAILS_FORMAT_UNFIXED
    if (*pos >= bufSize) return 0;   /* not even the NUL fits: nothing is written */
#endif
    size_t remaining = bufSize - *pos;
    int written = snprintf(buf + *pos, remaining,
        "%s|%lld|%llu|%u|%u|%llu|%llu\n",
        hashHex,
        (long long)row->amount,
        (unsigned long long)row->fee,
        row->blockHeight,
        row->timestamp,
        (unsigned long long)row->sent,
        (unsigned long long)row->received);
#ifdef TX_DETAILS_FORMAT_UNFIXED
    if (written > 0) *pos += written;   /* comparison shape: by the would-be length, unclamped */
    return 1;
#else
    if (written < 0 || (size_t)written >= remaining) {
        buf[*pos] = '\0';   /* snprintf wrote part of the line: the list ends before it */
        return 0;
    }
    *pos += (size_t)written;
    return 1;
#endif
}

/* Builds the list for the wallet's txCount transactions (oldest first): a line for each of the
 * TX_DETAILS_MAX_LINES most recent that rowAt does not leave out, oldest of them first. Returns
 * a NUL-terminated string the caller frees, or NULL if the buffer could not be allocated. */
static inline char *tx_details_format(size_t txCount, TxDetailsRowFn rowAt, void *ctx)
{
    size_t bufSize = tx_details_buffer_size(txCount);
    char *buf = malloc(bufSize);
    if (!buf) return NULL;
    buf[0] = '\0';
    size_t pos = 0;

    /* Start near the end of the array, not at index 0: a wallet with more than
     * TX_DETAILS_MAX_LINES txs must list its newest, or every new send past the cap would
     * never appear (the balance still updates, it sums all txs). */
    size_t first = (txCount > TX_DETAILS_MAX_LINES) ? txCount - TX_DETAILS_MAX_LINES : 0;
    for (size_t i = first; i < txCount; i++) {
        TxDetailsRow row;
        if (!rowAt(ctx, i, &row)) continue;
        if (!tx_details_append_line(buf, bufSize, &pos, &row)) break;
    }
    return buf;
}

#endif /* TX_DETAILS_FORMAT_H */
