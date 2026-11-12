/*
 * tx_rows_list.h
 *
 * The JNI-free core of getTransactionRows (jni_wallet.c): the activity list's rows as the shared
 * core computes them (BRTxRows.h, core #61), written out as text the app parses
 * (core/TransactionRows.kt). A host KAT (tx_rows_list_kat) drives this code without a JVM.
 *
 * Every figure is the core's. This file reads the rows and prints them; it computes nothing, and it
 * keeps the core's order (newest first): the app never re-sorts. One line per row:
 *
 *     <txid>|<amount>|<fee>|<blockHeight>|<shownTimestamp>|<sent>|<received>|<rule>\n
 *
 *   txid            display order (the wallet's bytes reversed), 64 hex
 *   amount          signed satoshis, the AMOUNT rule's (a send is its payee amount, the fee apart)
 *   fee             satoshis, or 18446744073709551615 (BR_TX_ROW_FEE_UNKNOWN): never shown as a figure
 *   blockHeight     TX_UNCONFIRMED (2147483647) while unconfirmed
 *   shownTimestamp  the DATE rule's: the transaction's timestamp, or `now` while it has none
 *   sent, received  BRWalletAmountSentByTx / BRWalletAmountReceivedFromTx
 *   rule            a BRTxRowAmountRule: 0 Net, 1 Payee, 2 DigiDollar
 *
 * DISPLAY ONLY (BRTxRows.h): nothing read from these rows may drive crediting, asset ingestion,
 * notification gating or the stranded-send sweep. Those read getTransactionDetails.
 *
 * READING. BRWalletTxRows writes the newest `maxRows` rows. The buffer is sized from the wallet's
 * count plus 8 (at most `limit`); a read that fills it may have cut the oldest rows off a wallet that
 * grew in between, so it is read again into one twice the size, up to three times, then taken as it
 * is. The core returns 0 when it cannot allocate, and so does this: an empty list, never a partial one.
 * The caller must not hold the wallet's lock (BRTxRows.h).
 */
#ifndef TX_ROWS_LIST_H
#define TX_ROWS_LIST_H

#include "BRTxRows.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* The longest line the format can produce:
 *
 *     txid | amount | fee | blockHeight | shownTimestamp | sent | received | rule | seven '|' and '\n'
 *      64  +   20   + 20  +     10      +       10       +  20  +    20    +  3   +          8          = 175
 */
#define TX_ROWS_LINE_MAX (64 + 20 + 20 + 10 + 10 + 20 + 20 + 3 + 8)
_Static_assert(TX_ROWS_LINE_MAX == 175, "TX_ROWS_LINE_MAX: see the arithmetic above");

/* Retries after the first read, each into a buffer twice the size. */
#define TX_ROWS_LIST_RETRIES 3

/* The wallet's newest rows, at most `limit`, in the core's order, in a buffer the caller frees.
 * *count is how many. NULL with *count 0 when there are none, limit is 0, or allocation fails. */
static inline BRTxRow *tx_rows_list_read(BRWallet *wallet, uint32_t now, size_t limit, size_t *count)
{
    size_t cap, n;
    BRTxRow *rows;

    *count = 0;
    if (!wallet || limit == 0) return NULL;
    n = BRWalletTxRows(wallet, now, NULL, 0);   /* NULL buffer: how many rows there are */
    cap = (n >= limit || limit - n < 8) ? limit : n + 8;
    for (int attempt = 0; ; attempt++) {
        if (cap > SIZE_MAX / sizeof(BRTxRow)) return NULL;
        rows = (BRTxRow *)calloc(cap, sizeof(BRTxRow));
        if (!rows) return NULL;
        n = BRWalletTxRows(wallet, now, rows, cap);
        /* Not full: every row. Full at the limit: the newest `limit`, as asked. */
        if (n < cap || cap == limit || attempt >= TX_ROWS_LIST_RETRIES) break;
        free(rows);
        cap = (cap > limit / 2) ? limit : cap * 2;
    }
    if (n == 0) { free(rows); return NULL; }
    *count = n;
    return rows;
}

/* The rows as text, one line each in the order given, NUL-terminated, for the caller to free. NULL if
 * the buffer cannot be allocated. A line that would not fit is not written (none can: the buffer is
 * sized from the longest line). */
static inline char *tx_rows_list_format(const BRTxRow *rows, size_t count)
{
    size_t size, pos = 0;
    char *buf;

    if (count > (SIZE_MAX - 1) / TX_ROWS_LINE_MAX) return NULL;
    size = count * TX_ROWS_LINE_MAX + 1;
    buf = (char *)malloc(size);
    if (!buf) return NULL;
    buf[0] = '\0';
    for (size_t i = 0; i < count; i++) {
        const BRTxRow *r = &rows[i];
        char hex[65];
        for (int j = 0; j < 32; j++) snprintf(&hex[j * 2], 3, "%02x", r->txHash.u8[31 - j]);
        hex[64] = '\0';
        size_t remaining = size - pos;
        int written = snprintf(buf + pos, remaining, "%s|%lld|%llu|%u|%u|%llu|%llu|%u\n",
                               hex, (long long)r->amount, (unsigned long long)r->fee, r->blockHeight,
                               r->shownTimestamp, (unsigned long long)r->sent, (unsigned long long)r->received,
                               (unsigned)r->rule);
        if (written < 0 || (size_t)written >= remaining) { buf[pos] = '\0'; break; }
        pos += (size_t)written;
    }
    return buf;
}

/* The list getTransactionRows returns: the newest `limit` rows as text. "" (an allocated empty string)
 * when there are none; NULL only when even that cannot be allocated. */
static inline char *tx_rows_list(BRWallet *wallet, uint32_t now, size_t limit)
{
    size_t count = 0;
    BRTxRow *rows = tx_rows_list_read(wallet, now, limit, &count);
    char *text = tx_rows_list_format(rows, count);
    free(rows);
    return text;
}

#endif /* TX_ROWS_LIST_H */
