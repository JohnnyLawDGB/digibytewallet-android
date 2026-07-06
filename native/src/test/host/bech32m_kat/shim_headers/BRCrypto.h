// Minimal host-KAT shim for BRCrypto.h — NOT the real header.
// See BRAddress.h in this same directory for the full rationale.
// BRBech32.c #includes BRCrypto.h but calls none of its hashing
// functions (bech32 encode/decode is pure string/bit logic), so an
// empty guarded header is sufficient here.
#ifndef BRCrypto_h
#define BRCrypto_h
#endif // BRCrypto_h
