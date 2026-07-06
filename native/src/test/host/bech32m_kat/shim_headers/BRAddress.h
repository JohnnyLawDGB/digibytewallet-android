// Minimal host-KAT shim for BRAddress.h — NOT the real header.
//
// This directory exists so bech32m_kat_main.c can compile and link the
// LIVE `native/src/main/jni/digibytewallet-core/BRBech32.c` submodule
// source on the host (gcc), without pulling in the full BRAddress.h ->
// BRCrypto.h -> crypto/odocrypt.h dependency chain, which is unrelated
// to bech32 string encode/decode and not needed to exercise it.
//
// Verified (see run.sh / task-2-report.md): BRBech32.c references only
// the OP_0 / OP_1 opcode constants from BRAddress.h — no BRSHA256,
// BRRMD160, BRHash, UInt256, or UInt160 symbols. This shim reproduces
// those two macros bit-for-bit from the real header and nothing else.
//
// If BRBech32.c ever starts using more of BRAddress.h/BRCrypto.h, this
// shim will fail to compile (undefined reference) rather than silently
// passing a stale test — that's a deliberate tripwire.
#ifndef BRAddress_h
#define BRAddress_h

#define OP_0           0x00
#define OP_1           0x51

#endif // BRAddress_h
