# Threat Model

## Assets Protected
- BIP39 seed phrase (master secret)
- Derived private keys
- Transaction history and UTXO set
- User's IP address linkage to transactions

## Threats Resisted

### Device Theft (locked device)
- Seed encrypted with AES-256-GCM via AndroidKeyStore, hardware-backed where the device provides it; `KeyInfo.isInsideSecureHardware` is probed and logged at key creation, not enforced (a software-only Keystore still gets a key)
- PIN required (Argon2id hashed, 600k PBKDF2 iterations fallback); rate-limited (3 free attempts, then 1/5/30/60-minute cooldowns, backward-clock guard)
- Biometric authentication as a UI gate (the Keystore key is not auth-bound; see "Threats NOT Resisted")
- Lock: immediate on background (`MainActivity.onStop()` → `lockUi()`), enforced by a reactive re-route to the PIN screen whenever the wallet state flips to Locked (`AppNavigation` + `LockGatePolicy`), plus an in-foreground inactivity timeout honouring the Security-settings value (as of the 2026-08-30 follow-ups)
- Spend gate (as of the 2026-08-30 follow-ups): in-app PIN or biometric before DGB / DigiDollar / DigiAsset sends, Digi-ID approve, foreign-seed sweeps, Hub quickLogin and own-node pairing — unlocking the app does not by itself authorise a spend
- The Keystore key is NOT invalidated on new biometric enrollment — that property belongs to auth-bound keys, which this app deliberately does not use (`setUserAuthenticationRequired` crashed inconsistently on API 28/33/35)

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

### Malicious IME / accessibility service during phrase entry
A user-installed keyboard or accessibility service can observe the recovery phrase and passphrase as they are typed. Bounded, not eliminated: `FLAG_SECURE` on the entry screens (no screenshots/screen-share) and a password-type IME (no learning, no suggestions) as of the 2026-08-30 follow-ups. Only a system keyboard the user trusts closes this fully; the app cannot pick the keyboard for them.

### Lost-PIN branch (accepted residual, 2026-08-30)
If no PIN hash is on disk the app routes to PIN setup over the existing seed rather than demanding the seed phrase. Reaching that state requires deleting `dgb_pin_store`, which on release builds needs root: `isDebuggable=false` kills `run-as`, `allowBackup=false` kills `adb backup`, and `dataExtractionRules` exclude the store from cloud backup and device transfer. Root already defeats every control above, so this branch is recorded rather than closed.

### Compromised app process
The seed key is not bound to device unlock (no `setUserAuthenticationRequired`), so code running inside the app's process can decrypt the seed without the user's PIN or biometric. Keystore auth-binding is ROADMAP Phase 2.

## Trust Boundaries

| Boundary | Trusted | Untrusted |
|----------|---------|-----------|
| Cryptography | C core (secp256k1, BIP32/39), Android Keystore | Managed-code crypto |
| Network | None — all peers untrusted | SPV peers, DNS seeds, IPFS gateways |
| Storage | SQLCipher-encrypted Room DB, Keystore-backed keys | Cleartext files |
| Backend | DigiScope API (authenticated, cert-pinned) | Third-party price APIs |
