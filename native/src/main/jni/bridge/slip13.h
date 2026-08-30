/*
 * slip13.h — SLIP-0013 / BitID per-site identity index derivation.
 *
 * Pure function, no JNI and no seed access, so the host KAT
 * (native/src/test/host/slip13_identity_kat) can compile it directly.
 *
 * Scheme (SLIP-0013, the derivation BitID's BIP draft specifies and the
 * Digi-ID ecosystem inherits): for a site's canonical callback URI and an
 * account index i,
 *
 *     h        = SHA256( LE32(i) || uri )
 *     A,B,C,D  = first 16 bytes of h read as four little-endian uint32
 *     path     = m/13'/A'/B'/C'/D'      (every step hardened)
 *
 * This header yields A..D WITHOUT the hardened bit; the caller ORs
 * BIP32_HARD when walking the path.
 */
#ifndef SLIP13_H
#define SLIP13_H

#include <stdint.h>
#include <stddef.h>

/* Fills out[0..3] with the SLIP-0013 child indexes (hardened bit not set).
 * Returns 1 on success, 0 on failure (NULL args or allocation failure);
 * on failure out[] is zeroed. */
int slip13_indexes(uint32_t out[4], const char *uri, size_t uriLen, uint32_t index);

#endif /* SLIP13_H */
