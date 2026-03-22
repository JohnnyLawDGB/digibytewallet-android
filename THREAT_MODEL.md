# Threat Model

## Assets Protected
- BIP39 seed phrase (master secret)
- Derived private keys
- Transaction history and UTXO set
- User's IP address linkage to transactions

## Threats Resisted

### Device Theft (locked device)
- Seed encrypted with AES-256-GCM via Android Keystore (TEE/Strongbox)
- PIN required (Argon2id hashed, 600k PBKDF2 iterations fallback)
- Biometric authentication
- Auto-lock on timeout and app background
- Key invalidated on new biometric enrollment

### Man-in-the-Middle
- TLS 1.2+ enforced (cleartext blocked via network security config)
- Certificate pinning for DigiScope API (Phase 3)
- SPV peer connections are protocol-level (no TLS, but transaction integrity is cryptographic)

### Malicious Peers / Eclipse Attack
- Connect to 4-8 peers across diverse IP ranges
- DNS seeds from 8 independent operators
- Bloom filter FPR 0.005 for privacy
- Merkle proof verification for all transactions (CVE-2012-2459 protected)
- Chain-split detection (4-peer agreement, 100-block reorg alert)

### Supply Chain Attack
- Reproducible Docker builds
- Multi-party attestation (SHA-256 hash comparison)
- No obfuscation (R8 shrink only) for auditability
- Pinned dependencies with hash verification
- No proprietary dependencies (no Play Services, Firebase, Crashlytics)

### Key Extraction via App
- Raw keys never cross JNI boundary during normal operation
- C core zeros key material after use (secure_zero)
- FLAG_SECURE on seed display screens
- No clipboard for seed phrases
- No analytics, telemetry, or crash reporting that could leak keys

## Threats NOT Resisted

### Compromised OS Kernel
A compromised Android OS can read any process memory including the C core's key storage.

### Nation-State Physical Access (Unlocked Device)
If an attacker has physical access to an unlocked device, they can extract keys.

### Compromised Build Environment
If Docker itself or the base Ubuntu image is compromised, builds are compromised. Mitigated by multi-party attestation on diverse machines.

## Trust Boundaries

| Boundary | Trusted | Untrusted |
|----------|---------|-----------|
| Cryptography | C core (secp256k1, BIP32/39), Android Keystore | Managed-code crypto |
| Network | None — all peers untrusted | SPV peers, DNS seeds, IPFS gateways |
| Storage | SQLCipher-encrypted Room DB, Keystore-backed keys | Cleartext files |
| Backend | DigiScope API (authenticated, cert-pinned) | Third-party price APIs |
