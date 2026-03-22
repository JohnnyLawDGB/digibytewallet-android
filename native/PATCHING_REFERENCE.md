# DigiByte Core 8.26 — Patching Reference

> Generated: 2026-03-22
> Source: `DigiByte-Core/digibyte` @ tag `v8.26.2` (released 2026-01-16)
> Purpose: Guide for patching `digibytewallet-core` C library to connect to 8.26+ peers

---

## 1. Protocol Version

### 8.26 Values (`src/version.h`)

| Constant              | Value  | Notes                                              |
|-----------------------|--------|----------------------------------------------------|
| `PROTOCOL_VERSION`    | 70019  | Current advertised protocol version                |
| `INIT_PROTO_VERSION`  | 209    | Initial version before handshake completes         |
| `MIN_PEER_PROTO_VERSION` | 70017 | 8.26 will disconnect peers older than this        |
| `WTXID_RELAY_VERSION` | 70018  | wtxid-based relay support                         |
| `LARGE_HEADERS_VERSION` | 70019 | Supports 20,000 headers (vs old 10,000)          |
| `SENDHEADERS_VERSION` | 70012  | Block announcements via headers                    |
| `FEEFILTER_VERSION`   | 70013  | Feefilter message support                          |
| `SHORT_IDS_BLOCKS_VERSION` | 70014 | Compact block support                         |

### Client Version (`configure.ac`)

| Field                    | Value |
|--------------------------|-------|
| `CLIENT_VERSION_MAJOR`   | 8     |
| `CLIENT_VERSION_MINOR`   | 26    |
| `CLIENT_VERSION_BUILD`   | 2     |
| `CLIENT_VERSION_IS_RELEASE` | true |
| Full version string      | `8.26.2` |

### Current Wallet-Core Values (`BRPeer.c`)

| Constant           | Value | File        |
|--------------------|-------|-------------|
| `PROTOCOL_VERSION` | 70017 | BRPeer.c:50 |
| `MIN_PROTO_VERSION`| 70017 | BRPeer.c:51 |
| `BR_VERSION`       | "1.0.0" | BRPeer.h:78 |
| User-agent string  | `/digiwallet:1.0.0/` | BRPeer.h:79 |

### Delta

- `PROTOCOL_VERSION`: wallet-core uses **70017**, 8.26 peers advertise **70019**
- `MIN_PEER_PROTO_VERSION` on 8.26 nodes is **70017** — our wallet's 70017 is at the minimum and will NOT be rejected
- However, advertising 70017 means we miss features added in 70018 (wtxid relay) and 70019 (large headers)
- **Recommendation (Task 2c):** Bump `PROTOCOL_VERSION` to `70019` in `BRPeer.c`

---

## 2. DNS Seeds (Mainnet)

### 8.26 Seeds (`src/kernel/chainparams.cpp` — `vSeeds`)

| Seed Host                    | Operator                     | Status vs Wallet-Core |
|------------------------------|------------------------------|-----------------------|
| `seed.digibyte.io`           | Jared Tate @JaredTate        | Existing              |
| `seed.diginode.tools`        | Olly Stedall @saltedlolly    | **NEW**               |
| `seed.digibyteblockchain.org`| John Song @j50ng              | **NEW**               |
| `eu.digibyteseed.com`        | Jan De Jong @jongjan88       | **NEW**               |
| `seed.digibyte.link`         | Bastian Driessen @bastiandriessen | **NEW**          |
| `seed.quakeguy.com`          | Paul Morgan @SnKQuaKe        | Replaces `dgb.quakeguy.com` |
| `seed.aroundtheblock.app`    | Mark McNiel @JohnnyLawDGB    | **NEW**               |
| `seed.digibyte.services`     | Craig Donnachie @cdonnachie  | **NEW**               |

### Current Wallet-Core Seeds (`BRChainParams.h`)

```
seed.digibyte.io        // Jared Tate
seed.digibyte.org       // Website collective
dnsseed.lifehash.com    // LifeHash
seed.digihash.co        // Jared Tate
seed.digiassets.net     // DigiByte Foundation
digibyte.host           // SashaD
seed.digiexplorer.info  // DigiByte Foundation
seed2.digibyte.io       // Jared Tate
seed3.digibyte.io       // Jared Tate
dgb.quakeguy.com        // Quakeitup
```

### Delta

- 7 of 10 current seeds are **not in 8.26** and are likely stale/dead (2021-era)
- Only `seed.digibyte.io` carries over
- `dgb.quakeguy.com` → renamed to `seed.quakeguy.com`
- **Recommendation (Task 2c):** Replace entire `BRMainNetDNSSeeds[]` with the 8 seeds from 8.26

---

## 3. Fee Constants

### 8.26 Values (`src/policy/policy.h`)

| Constant                     | Value (sat/KB) | sat/byte |
|------------------------------|----------------|----------|
| `DEFAULT_MIN_RELAY_TX_FEE`   | 100,000        | 100      |
| `DUST_RELAY_TX_FEE`          | 30,000         | 30       |
| `DEFAULT_BLOCK_MIN_TX_FEE`   | 100,000        | 100      |
| `DEFAULT_INCREMENTAL_RELAY_FEE` | 10,000      | 10       |
| `MAX_STANDARD_TX_WEIGHT`     | 400,000 weight units | — |
| `MIN_STANDARD_TX_NONWITNESS_SIZE` | 65 bytes | — |

### Current Wallet-Core Values

| Constant            | Value (sat/KB) | Source        |
|---------------------|----------------|---------------|
| `TX_FEE_PER_KB`     | 1,000          | BRTransaction.h:38 |
| `DEFAULT_FEE_PER_KB`| ~26,178        | BRWallet.h:72 (5000*1000+99)/100 |
| `MIN_FEE_PER_KB`    | ~5,236         | BRWallet.h:73 |
| `MAX_FEE_PER_KB`    | ~5,236,649     | BRWallet.h:74 |

### Delta

- The wallet-core `TX_FEE_PER_KB` of **1,000 sat/KB** is 100x below the 8.26 minimum relay fee of **100,000 sat/KB**
- Transactions built with 1,000 sat/KB will be **rejected by 8.26 nodes** as below min relay fee
- `DEFAULT_FEE_PER_KB` (~26,178 sat/KB) is also **still below** the 8.26 minimum of 100,000 sat/KB
- **Recommendation (Task 2c):**
  - Set `TX_FEE_PER_KB` to `100000ULL` (100 sat/byte)
  - Review and update `DEFAULT_FEE_PER_KB`, `MIN_FEE_PER_KB`, `MAX_FEE_PER_KB` in `BRWallet.h`
  - Minimum dust output should align with `DUST_RELAY_TX_FEE` (30,000 sat/KB)

---

## 4. Service Bits

### 8.26 Values (`src/protocol.h`)

| Flag                   | Value        | Description                                          |
|------------------------|--------------|------------------------------------------------------|
| `NODE_NONE`            | `0`          | No services                                          |
| `NODE_NETWORK`         | `1 << 0` = 1 | Full block chain node                                |
| `NODE_BLOOM`           | `1 << 2` = 4 | Bloom filter support (deprecated default in 70011+)  |
| `NODE_WITNESS`         | `1 << 3` = 8 | SegWit witness data support (BIP144)                 |
| `NODE_COMPACT_FILTERS` | `1 << 6` = 64 | BIP157/158 compact block filter support             |
| `NODE_NETWORK_LIMITED` | `1 << 10` = 1024 | Pruned node (serves last 288 blocks, BIP159)    |
| `NODE_P2P_V2`          | `1 << 11` = 2048 | BIP324 encrypted transport support             |

### Current Wallet-Core Values (`BRPeer.h`)

| Constant                   | Value  | Description                                  |
|----------------------------|--------|----------------------------------------------|
| `SERVICES_NODE_NETWORK`    | `0x01` | Full blocks node                             |
| `SERVICES_NODE_BLOOM`      | `0x04` | BIP111 bloom filter                          |
| `SERVICES_NODE_BCASH`      | `0x20` | Bitcoin Cash UAHF flag (irrelevant for DGB)  |

Services field in `BRMainNetParams`: set to **`0`** (requests no specific services from peers).

### Delta

- `SERVICES_NODE_BCASH` (`0x20`) is a **Bitcoin Cash-specific flag** with no meaning on DigiByte — dead code
- `NODE_WITNESS` (`0x08`) is absent from wallet-core but required to request witness transactions from 8.26 nodes
- `NODE_NETWORK_LIMITED` and `NODE_P2P_V2` are new since 2021
- **Recommendation (Task 2c):** Add `SERVICES_NODE_WITNESS 0x08` to `BRPeer.h`; remove `SERVICES_NODE_BCASH`

---

## 5. Checkpoints

### 8.26 Checkpoint Data (`src/kernel/chainparams.cpp` — `checkpointData`)

The 8.26 source intentionally uses a **minimal checkpoint set** (only 4 entries), deferring to `assumevalid` for fast-sync:

| Height   | Block Hash                                                           | Notes                      |
|----------|----------------------------------------------------------------------|----------------------------|
| 0        | `7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496`  | Genesis block              |
| 5,000    | `95753d284404118788a799ac754a3fdb5d817f5bd73a78697dfe40985c085596`  |                            |
| 10,000   | `12f90b8744f3b965e107ad9fd8b33ba6d95a91882fbc4b5f8588d70d494bed88`  |                            |
| 110,000  | `ab2da24656493015f2fd288994661e1cc657d90aa34c755514af044aaaf1569d`  |                            |

**assumevalid block** (8.26): `457f6864b52e5076a433afe3c28e3ae0bbeeaba9036a782ddb691242326fcb80` @ height 21,700,000

### Current Wallet-Core Checkpoints (`BRChainParams.h`)

The wallet-core has **36 checkpoints** spanning blocks 0–13,510,000 (last entry added ~2021). The final entries:

| Height     | Block Hash (prefix)                | Timestamp  |
|------------|------------------------------------|------------|
| 12,000,000 | `0000000000000000e231d6...`        | 2020-12-03 |
| 13,000,000 | `a4c1069938986237270340...`        | 2021-05-25 |
| 13,510,000 | `e41f3bbb0668b4db506825...`        | 2021-08-21 |

Current chain height is approximately **21,700,000+** (as of 8.26 assumevalid height).

### Delta

- The wallet-core checkpoints stop at block **13,510,000** (August 2021) — chain is now ~8.2 million blocks ahead
- Checkpoints are used as SPV sync starting points — outdated checkpoints force longer initial sync
- **Recommendation (Task 2c):** Add recent checkpoints at heights 14M, 15M, 16M, 17M, 18M, 19M, 20M, 21M. Block hashes must be sourced from a live mainnet node or block explorer (e.g., `digiexplorer.info`)

---

## 6. Network / Chain Parameters

### 8.26 vs Wallet-Core

| Parameter         | 8.26 Value                        | Wallet-Core Value          | Match? |
|-------------------|-----------------------------------|----------------------------|--------|
| Default P2P Port  | 12024                             | 12024                      | YES    |
| Magic Bytes (LE)  | `0xfa 0xc3 0xb6 0xda`            | `0xdab6c3fa`               | YES    |
| Genesis Hash      | `7497ea1b...e8496`                | `7497ea1b...e8496`         | YES    |
| Bech32 HRP        | `dgb`                             | (not implemented in core)  | N/A    |
| PUBKEY_ADDRESS    | 30 (D prefix)                     | (not in BRChainParams.h)   | N/A    |
| SCRIPT_ADDRESS    | 63 (S prefix)                     | (not in BRChainParams.h)   | N/A    |

Magic number confirmed identical: wallet-core `0xdab6c3fa` = 8.26 bytes `[0xfa, 0xc3, 0xb6, 0xda]` in little-endian.

---

## 7. Consensus Changes Since Wallet-Core Era (~2021)

### Hard Forks and Major Activations

| Change                    | Activation Height | Notes                                              |
|---------------------------|-------------------|----------------------------------------------------|
| MultiAlgo Hard Fork       | 145,000           | Multi-algorithm PoW (pre-dates wallet-core)        |
| MultiShield Hard Fork     | 400,000           | Difficulty algorithm (pre-dates wallet-core)       |
| DigiSpeed Hard Fork       | 1,430,000         | 15-second blocks (pre-dates wallet-core)           |
| SegWit + BIP34/65/66/CSV  | 4,394,880         | All activated simultaneously                       |
| ReserveAlgoBits           | 8,547,840         | Block version field reservation                    |
| Odo PoW Hard Fork         | 9,100,000         | OdoHash algorithm activated                        |
| Odo Height                | 9,112,320         | Finalized Odo activation                           |
| Taproot deployment        | Starts 2025-01-10 | BIPs 340-342, nStartTime=1736510438               |

### Key Observations for SPV Client

- **SegWit (BIP141/143/144)** activated at block 4,394,880: all modern transactions are potentially SegWit. The wallet-core must handle witness data to decode transactions correctly.
- **OdoHash PoW** at 9,100,000: SPV clients verify block headers. The C core must implement the OdoHash algorithm (or accept headers without full PoW verification for SPV).
- **Taproot** is deploying via BIP9 signaling from January 2025. The wallet-core does not implement Taproot key paths; received Taproot outputs will be unrecognized as P2TR.
- `defaultAssumeValid` at block **21,700,000** means 8.26 full nodes skip script validation for ancestors — but SPV clients are not affected by this.
- `fRbfEnabled = false` — DigiByte does **not** enable opt-in Replace-By-Fee.

---

## 8. Summary: Files to Patch in Task 2c

| File                   | Changes Needed                                                              |
|------------------------|-----------------------------------------------------------------------------|
| `BRPeer.c`             | `PROTOCOL_VERSION`: 70017 → **70019**; `MIN_PROTO_VERSION`: keep at 70017  |
| `BRPeer.h`             | Add `SERVICES_NODE_WITNESS 0x08`; remove `SERVICES_NODE_BCASH 0x20`        |
| `BRChainParams.h`      | Replace all 10 DNS seeds with 8 seeds from 8.26; add checkpoints 14M–21M+ |
| `BRTransaction.h`      | `TX_FEE_PER_KB`: 1000 → **100000** (100 sat/byte min relay)                |
| `BRWallet.h`           | Recalculate `DEFAULT_FEE_PER_KB`, `MIN_FEE_PER_KB` against new base        |

> **Do NOT modify consensus logic or PoW validation** in this phase — those require deeper testing (Task 2d).
