# Network destinations

Every place the Android app connects to, and what happens when each one is unreachable. Track 4 of
the iOS port plan's §19.2 ("no trusted server, as a test"): only P2P to DigiByte nodes may be
required. Everything else must degrade gracefully when it cannot be reached. The iOS repo keeps the
same list for its app in its own `docs/network-destinations.md`. The divergence table at the end is
shared with it.

**The gate.** `app/src/test/java/app/aroundtheblock/wallet/network/NetworkDestinationsGateTest.kt`
reads the two tables between the `gate:` markers below and checks them against the code. It fails when:

- a host name, URL host or IPv4 literal appears in shipped code or in a default-locale string resource
  and is not in the destinations table (a new destination), or a listed host is no longer found (a
  stale row);
- a file starts using a network API (an OkHttp call, a WebSocket, a socket, a name lookup, a
  WebView load, a Coil image load, the Tor runtime, a C `connect`/`getaddrinfo`) and is not in the
  call-site table, or a listed file no longer does;
- the JNI bridge's hardcoded mainnet peers stop matching the core's canon (`BRPeerCanon.h`).

Comments are not scanned. String templates (`$x`, `${x}`) are blanked first, so `"$txid.to"` is not
read as a host. Reserved example names (`example.com`), `localhost` and loopback are ignored. The
scan covers `app/src/{main,play,sideload}/java`, `core/src/main/java`, `game/src/main/java`, the JNI
bridge, and the top-level C sources of the core submodule (test files excluded), plus the
`<string>`/`<item>` bodies in `app/src/main/res/values/*.xml` (the default locale only: a
translation carries the same hosts).

**Adding a destination:** add a row with the host in backticks in the first column, say whether it
is required, and say what the user sees when it is unreachable. A destination that is required for
a core flow (open the wallet, sync status, receive address, balance, a DGB send) is not acceptable
unless it is a DigiByte P2P node.

## Destinations

"Required" means a core flow stops without it. **No row other than P2P is required.**

<!-- gate:destinations:begin -->
| Destination | Code | Purpose | Required | When unreachable |
|---|---|---|---|---|
| `134.199.198.90` `129.212.182.152` `134.56.44.241` `174.131.163.123` `64.182.71.26` `66.64.43.14` `172.104.191.6` `103.230.156.247` `216.250.127.199` `112.213.39.221` `101.103.12.129` `95.111.238.51` `84.30.137.73` `109.123.231.205` `147.93.171.46` (port 12024) | `native/.../bridge/jni_peer.c` `MAINNET_PRIORITY_PEER_IPS`; the same 15 in core `BRPeerCanon.h` | The canon: hardcoded compact-filter P2P peers, dialled first | P2P: at least one P2P peer is required, not any particular one | The seeder's list, then the DNS seeds, supply other peers |
| `164.68.98.125` (and `95.111.238.51`, `129.212.182.152`, port 12033) | `jni_peer.c` `TESTNET_PRIORITY_PEER_IPS`, `SyncService.injectTestnetPeers`; core `BRPeerCanon.h` | Testnet26 canon (testnet builds only) | P2P | Testnet DNS seeds |
| `digiscope.me` `seed.diginode.tools` `seed.digibyte.link` `seed.quakeguy.com` `seed.aroundtheblock.app` `seed.digibyte.io` | core `BRChainParams.h` `BRMainNetDNSSeeds` (`BRPeerManager.c` resolves them) | DNS seeds: name lookups that return P2P node addresses | No | Used only while the pool is short of peers; the canon and the cached seeder list still work. `digiscope.me` is also a web page (rows below) |
| `testnetseed.digibyte.io` `testnetseed.digibyte.link` `testnetseed.digibyte.services` | core `BRChainParams.h` `BRTestNetDNSSeeds` | Testnet DNS seeds | No | As above |
| `api.digiscope.me` | `SyncService` / `SyncWorker` `SEEDER_URL` through `SeederClient`; `core/network/DigiScopePins.kt`; core `BRDigiScopePins.h` | **Seeder:** `/api/peers?capability=filter` (and `=dandelion` when Dandelion is on), hourly, certificate-pinned | No | The cached list is used; with no cache, the canon and the DNS seeds. **Gap G1:** a stale cache makes the sync start wait for the fetch (below) |
| `api.digiscope.me` | `DigiScopeAssetClient`, `MultiEndpointAssetClient` | DigiAsset indexer: metadata, address history, the stack on an output | No (assets only) | Metadata reads "offline". An asset **send** refuses (`Outcome.Unverified`) and a received asset stays unchecked (not credited) until the indexer answers. Fails closed; DGB is not affected. **Gap G2** |
| `assets.digistamp.co` | `DigistampAssetClient`, `DigistampClient`, `DigistampUris`, `DigistampPins`, `network_security_config.xml` | Second asset indexer; the DigiStamp section (WebView) and its Digi-ID sign-in | No | The DigiScope indexer answers alone; the DigiStamp page does not load |
| `api.digiscope.me` | `core/ipfs/IpfsClient.kt` `DEFAULT_GATEWAYS[0]` | IPFS pin proxy for asset images and metadata | No | The next gateway |
| `trustless-gateway.link` `dweb.link` `ipfs.io` | `IpfsClient.DEFAULT_GATEWAYS` | Public IPFS gateways (content checked against the CID) | No | The next gateway; after the last, a placeholder image |
| `api.digiscope.me` | `DigiScopeClient`, `HubWebSocket` (`wss://…/api/hub/ws`) | Community Hub: Digi-ID login, profile, chat, DigiRunner scores | No (Hub only) | The Hub tab shows its error state; the socket retries with backoff. Nothing in the wallet waits on it |
| `api.digiscope.me` | `core/reconcile/DgbNodeClient.kt` (endpoint can be changed in Settings) | Recover funds / reconcile: asks a node what the wallet's addresses hold. Opened by the user, who is told the address list goes to the server | No | The screen reports the failure |
| `api.coingecko.com` `api.binance.com` | `core/PriceProvider.kt` | DGB price in 22 currencies (CoinGecko first, Binance for USD) | No | The last price cached in Room, labelled cached; with none, "unavailable" and the fiat line is a dash. The Send form's fiat line stays empty while a lookup runs; a DGB amount is never blocked |
| `api.github.com` | `core/UpdateChecker.kt` (sideload flavor only, `SelfUpdatePrompt`) | Update check: the sideload repository's latest release | No | No prompt. The Play flavor makes no update request |
| `github.com` | `UpdateChecker.isReleaseUrl`; `AboutScreen`; `NetworkInfoScreen` own-node guide | Release download link; About links (source, licence, issues); the own-node guide | No | Links open in the browser; the app does not fetch them |
| `chainz.cryptoid.info` | `ui/wallet/TransactionDetailScreen.kt` | "View on Explorer" | No | Opens in the browser on a tap only |
| `www.aroundtheblock.us` `aroundtheblock.us` `digibyte.org` | `ui/settings/AboutScreen.kt` | About links | No | Browser, on a tap |
| `digiscope.me` | `AboutScreen`, `BugReportLink` (`/report`), `AssetListScreen` (`/assets/create`), Hub `ProfileView` (privacy policy) | Web pages | No | Browser, on a tap |
| `redirect.digibyteprojects.com` `www.reddit.com` `twitter.com` | `res/values/no_trans_strings.xml` `digi_share`; `res/values/strings.xml` `About.reddit`, `About.twitter`, `About.blog`, `Alert.keystore.generic.android` | Legacy bread-wallet strings. No code reads them | Not a destination | — |
<!-- gate:destinations:end -->

Destinations with no literal in the code (the gate cannot list them; they are listed here):

| Destination | Code | Purpose | Required | When unreachable |
|---|---|---|---|---|
| Any P2P node | `BRPeerManager.c` (`addr` relay, DNS seeds), the seeder's list | Further P2P peers | P2P | Others |
| The user's own node (typed host or IP, port 12024) | `service/OwnNodeAddress.kt`, `CustomNode.kt`, `injectCustomNode` | Sync from the user's node, optionally exclusive | P2P, opt-in | Exclusive: Network Info shows the node dark; otherwise the canon carries the sync |
| Any HTTPS Digi-ID callback | `core/digiid/DigiIdManager.kt` | The signed login POST to the site whose QR was scanned (the callback host must equal the URI's domain; HTTP refused) | No, user-initiated | The approval screen shows the error |
| The Tor network (directory authorities and relays built into tor) | `TorManager` (kmp-tor, `resource-noexec-tor`) | Opt-in Tor transport, default off | No, opt-in | HTTP waits up to 30 s for Tor and then fails; P2P falls back to clearnet after 120 s with 0 peers and raises the "Tor unavailable" banner |
| Dandelion stem peers | `SyncService.injectDandelionPeers` | Opt-in, default off; P2P peers the seeder tags | P2P, opt-in | Sends flood directly |
| Loopback `127.0.0.1` | `NetworkModule` (Tor SOCKS), `RootDetector` (a local port probe) | On the device only | — | — |

**Third-party SDKs.** No analytics, crash reporting or advertising SDK is linked (`gradle/libs.versions.toml`:
OkHttp, Coil, ZXing, CameraX, Room, SQLCipher, Argon2, kmp-tor, AndroidX). Coil loads only what
`AssetImageResolver` gives it: `ipfs://` URIs fetched through `IpfsClient`, never an arbitrary URL from
asset metadata. ZXing and CameraX decode on the device. kmp-tor contacts the Tor network only when the
user turns Tor on. The Play flavor makes no Play-services calls.

<!-- gate:call-sites:begin -->
| Call site | Network API | Destinations |
|---|---|---|
| `app/src/main/java/app/aroundtheblock/wallet/DigiByteApp.kt` | Coil image loader (IPFS fetcher) | IPFS gateways |
| `app/src/main/java/app/aroundtheblock/wallet/service/OwnNodeAddress.kt` | `InetAddress` name lookup | The user's own node |
| `app/src/main/java/app/aroundtheblock/wallet/service/SeederClient.kt` | OkHttp | `api.digiscope.me` seeder |
| `app/src/main/java/app/aroundtheblock/wallet/ui/asset/AssetIcon.kt` | Coil `AsyncImage` | IPFS gateways |
| `app/src/main/java/app/aroundtheblock/wallet/ui/asset/AssetMediaViewer.kt` | Coil `AsyncImage` | IPFS gateways |
| `app/src/main/java/app/aroundtheblock/wallet/ui/asset/IpfsFetcher.kt` | Coil fetcher for `ipfs://` (through `IpfsClient`) | IPFS gateways |
| `app/src/main/java/app/aroundtheblock/wallet/ui/components/RootDetector.kt` | `Socket` to loopback | The device only |
| `app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampScreen.kt` | WebView | `assets.digistamp.co` |
| `app/src/main/java/app/aroundtheblock/wallet/ui/digistamp/DigistampWebViewClient.kt` | WebView | `assets.digistamp.co` |
| `core/src/main/java/app/aroundtheblock/wallet/core/PriceProvider.kt` | OkHttp | CoinGecko, Binance |
| `core/src/main/java/app/aroundtheblock/wallet/core/UpdateChecker.kt` | OkHttp | `api.github.com` |
| `core/src/main/java/app/aroundtheblock/wallet/core/asset/network/DigiScopeAssetClient.kt` | OkHttp | `api.digiscope.me` |
| `core/src/main/java/app/aroundtheblock/wallet/core/asset/network/DigistampAssetClient.kt` | OkHttp | `assets.digistamp.co` |
| `core/src/main/java/app/aroundtheblock/wallet/core/digiid/DigiIdManager.kt` | OkHttp | Digi-ID callbacks |
| `core/src/main/java/app/aroundtheblock/wallet/core/digiscope/DigiScopeClient.kt` | OkHttp | `api.digiscope.me` Hub |
| `core/src/main/java/app/aroundtheblock/wallet/core/digistamp/DigistampClient.kt` | OkHttp | `assets.digistamp.co` |
| `core/src/main/java/app/aroundtheblock/wallet/core/hub/HubWebSocket.kt` | OkHttp WebSocket | `api.digiscope.me` Hub |
| `core/src/main/java/app/aroundtheblock/wallet/core/ipfs/IpfsClient.kt` | OkHttp | IPFS gateways |
| `core/src/main/java/app/aroundtheblock/wallet/core/reconcile/DgbNodeClient.kt` | OkHttp | `api.digiscope.me` reconcile |
| `core/src/main/java/app/aroundtheblock/wallet/core/tor/TorManager.kt` | kmp-tor runtime | The Tor network |
| `native/src/main/jni/bridge/jni_peer.c` | `getaddrinfo` (own node, priority peers) | P2P |
| `native/src/main/jni/digibytewallet-core/BRPeer.c` | `socket`/`connect`, SOCKS5 | P2P |
| `native/src/main/jni/digibytewallet-core/BRPeerManager.c` | `getaddrinfo` (DNS seeds) | P2P |
<!-- gate:call-sites:end -->

## Degradation gaps

- **G1. The sync start waits for a stale seeder.** `SyncService.injectPeers` runs before
  `NativeBridge.startSync()` on every start path. When the cached peer list is over an hour old (or
  empty), it fetches `api.digiscope.me/api/peers` synchronously, and does so twice (once for the
  filter pool, once for the general pool, both `capability=filter`), three times with Dandelion on.
  The fetch is bounded by the shared client's 45 s call timeout. If the server fails fast (offline,
  DNS failure, refused), there is no delay. If it drops packets, the first P2P dial waits about
  30–90 s, and the canon, which needs no server, is dialled only after that. The wallet still syncs, from the canon and the stale cache, but late. iOS adds the cached list at once and fetches in
  the background (`WalletCore.addSeederPeers`). Fix (not done here; `SyncService` is the canonical
  peer-discovery design and wants its own reviewed PR): inject the cached list, start the sync, and
  run one fetch in the background that injects what it gets.
- **G2. DigiAsset sends and asset receipt need the indexer.** An asset send verifies each input's
  stack with `api.digiscope.me` and refuses when it cannot (fails closed, correctly). A received asset
  is credited only once the indexer confirms it. With the server gone, asset balances freeze and
  asset sends are impossible; DGB is unaffected. Removing the dependency means checking the stack
  from the chain itself (the M8 push-downs), not a small fix.
- The price lookup runs on every keystroke in the Send form's amount field (a CoinGecko request per
  edit, falling back to the cache). It degrades correctly; it is noted as needless traffic to a third
  party.

## Divergence between the apps

Kept the same in both repos' copies of this document.

| Destination | Android | iOS | Verdict |
|---|---|---|---|
| Canon peers, DNS seeds | `jni_peer.c` list (the gate checks it equals core `BRPeerCanon.h`) + core seeds | Core `BRPeerCanon.h` + core seeds | Same |
| Seeder `api.digiscope.me/api/peers` | Fetched on the sync-start path when stale (G1); twice per stale start | Cached list at once, one background fetch | **Timing differs** (G1); the peers that end up in the pool are the same. Align Android to iOS |
| Saved peers | Restored | Not restored (absent until the push-down, plan §19.3) | Absent |
| Explorer, own-node guide, About links to DigiScope, Around The Block, digibyte.org | Same URLs | Same URLs | Same |
| GitHub About links (source, licence, issues), bug-report page | Present | Absent | Absent |
| Price (CoinGecko, Binance) | Present | Absent | Absent |
| DigiAsset indexers, IPFS gateways, DigiStamp | Present | Absent (M8) | Absent |
| Digi-ID callbacks | Present | Absent (M6) | Absent |
| Hub, chat, DigiRunner (`api.digiscope.me`) | Present | Absent | Absent |
| Reconcile / Recover funds | Present | Absent (M9) | Absent |
| Update check (GitHub) | Sideload flavor only | Absent (the App Store updates) | Absent |
| Tor, Dandelion | Opt-in | Absent (M10) | Absent |
| iOS Local Network probe | — | `LocalNetworkAccess` opens a connection to the own node's address to read the permission state; sends no byte | iOS-only platform need, no Android counterpart |
| Analytics, crash reporting | None | None | Same |
