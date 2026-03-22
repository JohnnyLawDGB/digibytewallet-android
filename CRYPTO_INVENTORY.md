# Cryptographic Inventory

## Signing
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| secp256k1 ECDSA | libsecp256k1 (bitcoin-core) | Transaction signing |
| RFC 6979 | libsecp256k1 | Deterministic nonce generation |

## Key Derivation
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| BIP39 PBKDF2-HMAC-SHA512 | digibytewallet-core | Mnemonic → seed (2048 rounds) |
| BIP32 HMAC-SHA512 | digibytewallet-core | HD key derivation |
| BIP44/49/84 paths | digibytewallet-core | Address derivation (coin type 20) |

## Encryption
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| AES-256-GCM | Android Keystore (TEE/Strongbox) | Seed encryption at rest |
| SQLCipher (AES-256-CBC) | net.zetetic:sqlcipher-android | Database encryption |

## Hashing
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| Argon2id | org.signal:argon2 | PIN hashing (t=3, m=64MB, p=4) |
| PBKDF2-HMAC-SHA256 | javax.crypto | PIN hashing fallback (600k iterations) |
| SHA-256 | java.security | IPFS CID verification (Phase 2) |

## Block Header Validation
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| SHA256d | digibytewallet-core | Block header hash (algo slot 1) |
| Scrypt | digibytewallet-core | Block header hash (algo slot 2) |
| Groestl | digibytewallet-core | Block header hash (algo slot 3) |
| Skein | digibytewallet-core | Block header hash (algo slot 4) |
| Qubit | digibytewallet-core | Block header hash (pre-fork algo 5) |
| OdoCrypt | digibytewallet-core | Block header hash (post-fork algo 5) |

## Encoding
| Algorithm | Library | Purpose |
|-----------|---------|---------|
| Base58Check | digibytewallet-core | Legacy address encoding |
| Bech32 | digibytewallet-core | SegWit address encoding (dgb1) |
