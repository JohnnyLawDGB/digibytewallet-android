#ifndef DIGIDOLLAR_FEE_KEYS_H
#define DIGIDOLLAR_FEE_KEYS_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "BRKey.h"
#include "BRBIP32Sequence.h"

/*
 * The keys that sign a DigiDollar recovery transfer's fee inputs, each from the derivation its own coin
 * was found under. Kept free of JNI so that dd_fee_profiles_kat signs a transfer with it on the host.
 *
 * The dollars of a recovered seed sit at m/86'/20'/0'; the DGB that pays their fee sits wherever the
 * scan found it -- BIP84, BIP44, the bread path -- and the fee one transfer needs may only be covered
 * by coins from two of them. So each fee input carries its own derivation: the HMAC key, the prefix,
 * and the (chain, index) of its address under that prefix. A coin signed with another derivation's key
 * is not signed at all, and the transfer is refused.
 *
 * MACRO CONVENTION: DIGIDOLLAR_FEE_KEYS_UNFIXED is tested by VALUE (#if). =1 is dd_fee_profiles_kat's
 * comparison arm, the behaviour before: the bridge took one HMAC key and one prefix for every fee
 * input (the first one's here), with only the chain and index per input. Unset or =0 is what ships.
 */

#ifndef DIGIDOLLAR_FEE_KEYS_UNFIXED
#define DIGIDOLLAR_FEE_KEYS_UNFIXED 0
#endif

/** The longest derivation prefix a fee input may carry (the built-in profiles use one to three levels). */
#define DD_FEE_PATH_MAX 8

/** The longest HMAC key a fee input may name, its terminator included ("DigiByte seed" is 14). */
#define DD_FEE_HMAC_MAX 64

typedef struct {
    /** The derivation's HMAC key: "Bitcoin seed", "DigiByte seed". */
    const char *hmacKey;
    /** Its prefix path, hardened levels included (e.g. 84', 20', 0'). */
    uint32_t prefix[DD_FEE_PATH_MAX];
    size_t prefixLen;
    /** Where the coin's address sits under that prefix. */
    uint32_t chain, index;
} DdFeeInputPath;

/* keys[i] for each fee input, derived from paths[i]: 1 when every key was derived, 0 when a path cannot
 * be used (no HMAC key, or a prefix longer than DD_FEE_PATH_MAX) -- then no key is to be used. */
static inline int dd_fee_input_keys(BRKey *keys, const DdFeeInputPath *paths, size_t count,
                                    const void *seed, size_t seedLen)
{
    uint32_t full[DD_FEE_PATH_MAX + 2];

    if (! keys || ! paths || ! seed) return 0;
    for (size_t i = 0; i < count; i++) {
#if DIGIDOLLAR_FEE_KEYS_UNFIXED
        const DdFeeInputPath *d = &paths[0];   /* one derivation for every fee input */
#else
        const DdFeeInputPath *d = &paths[i];
#endif
        if (! d->hmacKey || d->prefixLen > DD_FEE_PATH_MAX) return 0;
        memcpy(full, d->prefix, d->prefixLen * sizeof(uint32_t));
        full[d->prefixLen] = paths[i].chain;
        full[d->prefixLen + 1] = paths[i].index;
        BRBIP32PrivKeyArrayPath(&keys[i], seed, seedLen, d->hmacKey, full, d->prefixLen + 2);
    }
    return 1;
}

#endif /* DIGIDOLLAR_FEE_KEYS_H */
