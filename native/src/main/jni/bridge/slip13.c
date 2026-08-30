#include "slip13.h"
#include "BRCrypto.h"

#include <stdlib.h>
#include <string.h>

int slip13_indexes(uint32_t out[4], const char *uri, size_t uriLen, uint32_t index) {
    if (out) memset(out, 0, 4 * sizeof(uint32_t));
    if (!out || !uri) return 0;

    /* LE32(index) || uri — heap-allocated on purpose: a long URI must never
     * become a stack VLA (see the v3.6.6 peer-message P0). */
    size_t totalLen = 4 + uriLen;
    uint8_t *buf = malloc(totalLen);
    if (!buf) return 0;

    buf[0] = (uint8_t)(index & 0xff);
    buf[1] = (uint8_t)((index >> 8) & 0xff);
    buf[2] = (uint8_t)((index >> 16) & 0xff);
    buf[3] = (uint8_t)((index >> 24) & 0xff);
    memcpy(buf + 4, uri, uriLen);

    uint8_t h[32];
    BRSHA256(h, buf, totalLen);
    free(buf);

    for (int i = 0; i < 4; i++) {
        out[i] = ((uint32_t)h[4 * i]) |
                 ((uint32_t)h[4 * i + 1] << 8) |
                 ((uint32_t)h[4 * i + 2] << 16) |
                 ((uint32_t)h[4 * i + 3] << 24);
    }

    return 1;
}
