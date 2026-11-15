#ifndef SIGNED_MESSAGE_H
#define SIGNED_MESSAGE_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

/*
 * The bytes a DigiByte signed message is hashed over, kept free of JNI so that msg_len_kat checks them on
 * the host: "\x19DigiByte Signed Message:\n", the message's length as a compact size, then the message.
 *
 * MSG-LEN: the length is written as a FULL compact size, whatever it is -- one byte below 0xfd, 0xfd and
 * two bytes up to 0xffff, 0xfe and four bytes up to 0xffffffff, 0xff and eight bytes above -- so a
 * verifier, which reads the length the same way, hashes the same bytes. The earlier encoder wrote one byte
 * above 0xffff (the length's low byte), so the signature was over bytes no verifier would build.
 *
 * MACRO CONVENTION: MSG_LEN_UNFIXED is tested by VALUE (#if). =1 is msg_len_kat's comparison arm, the
 * encoder before this item. Unset or =0 is what ships.
 */

#ifndef MSG_LEN_UNFIXED
#define MSG_LEN_UNFIXED 0
#endif

/* Writes val to buf as a compact size and returns how many bytes that took (1, 3, 5 or 9). */
static inline size_t signed_message_compact_size(uint8_t buf[9], uint64_t val)
{
    if (val < 0xfd) {
        buf[0] = (uint8_t)val;
        return 1;
    }
    if (val <= 0xffff) {
        buf[0] = 0xfd;
        buf[1] = (uint8_t)(val & 0xff);
        buf[2] = (uint8_t)((val >> 8) & 0xff);
        return 3;
    }
#if MSG_LEN_UNFIXED
    buf[0] = (uint8_t)val;   /* the encoder before MSG-LEN: one byte, the length's low byte */
    return 1;
#else
    if (val <= 0xffffffffULL) {
        buf[0] = 0xfe;
        for (int i = 0; i < 4; i++) buf[1 + i] = (uint8_t)((val >> (8 * i)) & 0xff);
        return 5;
    }
    buf[0] = 0xff;
    for (int i = 0; i < 8; i++) buf[1 + i] = (uint8_t)((val >> (8 * i)) & 0xff);
    return 9;
#endif
}

/* The bytes msg[0..msgLen) is signed over, in a buffer the caller frees, holding *outLen bytes; NULL when
 * they cannot be allocated or their length would not fit a size_t. */
static inline uint8_t *signed_message_payload(const uint8_t *msg, size_t msgLen, size_t *outLen)
{
    static const char header[] = "\x19" "DigiByte Signed Message:\n";
    const size_t headerLen = sizeof(header) - 1;   /* without the terminating nul */
    uint8_t len[9];
    size_t lenLen = signed_message_compact_size(len, (uint64_t)msgLen);
    uint8_t *payload;

    if (!outLen || (msgLen && !msg) || msgLen > SIZE_MAX - headerLen - lenLen) return NULL;
    payload = (uint8_t *)malloc(headerLen + lenLen + msgLen);
    if (!payload) return NULL;
    memcpy(payload, header, headerLen);
    memcpy(payload + headerLen, len, lenLen);
    if (msgLen) memcpy(payload + headerLen + lenLen, msg, msgLen);
    *outLen = headerLen + lenLen + msgLen;
    return payload;
}

#endif /* SIGNED_MESSAGE_H */
