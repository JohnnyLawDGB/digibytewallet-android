# Changelog

## [3.4.0](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.3.0...v3.4.0) (2026-07-07)


### Features

* **assets:** DigiAsset transfer encoder + coin selector — v3.5.24 ([2b6692a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2b6692a6b0c77c6562c6ef9cf48ad3e2102a2df5))
* **assets:** harden metadata parser + support data URIs for inline images ([7db96c2](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7db96c2e28c80f4665a59fe2af871eff8be74728))
* **assets:** MAX button + DGB cost preview on asset send — v3.5.28 ([6887813](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/688781310143657dd6dd439342809753ca53f2d3))
* **assets:** multi-endpoint asset client with circuit breaker — v3.5.23 ([7a3ba48](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7a3ba48d3759b0a0c32b6d3a8386c4e66caf73cd))
* **assets:** native asset transfer tx builder + sendAsset orchestration — v3.5.25 ([7fe4878](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7fe48785fb622be6435d4cc9d0615e494582d3b4))
* **assets:** native DigiAsset discovery + rendering — v3.5.27 ([88813ad](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/88813add88bd0483e09896bdc7ac6ba4c3509235))
* **assets:** partial DigiAsset transfers — emit asset change marker ([0ced2e9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0ced2e9cc7c696b57c87fe6018ad47fa3381f489))
* **assets:** per-asset transaction history + backfill — v3.5.22 ([8474faf](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8474faf8434a5ce8123fa2eea8dd4772ae749dd6))
* **assets:** periodic + on-detect asset UTXO refresh — no manual Scan needed ([142cf41](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/142cf41fb6fa61df83edbf7e7600d49d607c32ca))
* **assets:** populate scriptPubKey on refreshed UTXOs so they're spendable ([e8857b3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e8857b3a9c56d511d4be4cf9dd6c8e962f6ae18c))
* **assets:** render DigiAsset images via Coil + hash-verifying IPFS fetcher — v3.5.21 ([60ad554](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/60ad554b91066a1fa8fb29e15991211f8e02fc38))
* **assets:** send UI + Doze-frozen keepalive fix + issuance callout — v3.5.26 ([e1c1f28](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e1c1f285a71bdfd1576c1c9fff1400a1828fc7e8))
* **assets:** wire AssetSendScreen to real sendAsset flow — v3.5.26 WIP ([613befe](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/613befeb9d93a50cb67b5e65e98cc13100f51291))
* **bip157:** bump submodule pin for compact-filter wire protocol ([3061f46](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/3061f4647ea09944351dbc659b2ed3d647362111))
* **bip158:** bump core pin for continuity-failure re-anchor; drop restore DIAG ([89cb70d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/89cb70d9699b4b06d1494ee7f1db38b80193c3f2))
* **bip158:** bump submodule + add BRCompactFilterChain to CMake ([a8ed3b7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a8ed3b78c42b678b2936d61561a49a03cda03b8d))
* **bip158:** bump submodule + wire BRWalletFilterElements into CMake ([e596c00](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e596c00a8756f40f6cd49a173d9c76612348f15c))
* **bip158:** JNI + NativeBridge for reanchorCompactFilterChainAtFloor ([e244009](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e244009ddc66675b1821b3f4c58cf10badb0ac6c))
* **bip158:** Path A + C2 — capability-aware seeder, JNI bridges, Settings toggle ([812d897](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/812d8976d83f93d8658e0dc49cbb51c662a32261))
* **bip158:** polling watchdog + activity-list send override (v3.5.40) ([aad75d9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/aad75d938ab9f4221952bccfd2cf9ab47c2721af))
* **bip158:** privacy-first default + 120s bloom fallback watchdog ([b812936](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b812936423a058d15637b4e24e48b65d87a69310))
* **bip158:** watchdog catch-up tolerance + bump core pin (retention + serialization) ([75cdd11](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/75cdd11e1dec3a1b25189eb8baf940c1c618cbf9))
* **bip158:** watchdog recovers transient block-stalls instead of staying on bloom ([664a3a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/664a3a386bd2c3bb70534c478166783256315b4b))
* **bip158:** watchdog triggers filter-chain re-anchor before bloom fallback ([42aa17b](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/42aa17b43fbbffa6871272907a2e7adc05e919da))
* BIP84 for new wallets, dual-scan for recovery in JNI bridge ([da604b3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/da604b37b12e1a8dc206af8b300dc57762a82f42))
* **dandelion:** Broadcaster (stem + embargo) routes all sends ([08ba17c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/08ba17ccd2c563c208ee08d3b65a2c5839865819))
* **dandelion:** fetch + inject + mark dandelion peers on sync start ([98f2bc7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/98f2bc7c94f08ffa0acea2f9a1b8e83a2305a81b))
* **dandelion:** JNI bridge for stem/fluff/capability ([19e7700](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/19e770048161c4e00d0786795f0b4563dd8dd49f))
* **dandelion:** pure broadcast policy (stem/fluff/embargo) + tests ([ca66654](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/ca6665498a6d16d7d9e88f1d9b0a6abf705c9075))
* **dandelion:** settings toggle (default on) in Network → Privacy ([512d24a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/512d24a802a49f0ef9f74746c03ab21d4643b746))
* expose BIP84 derivation path in NativeBridge and AboutScreen ([506cc90](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/506cc90d6ab81ab5b2f5baaa2925855220b75526))
* **native:** wire BRGCSFilter (BIP 158 GCS decoder) into the core-lib build ([a7b277e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a7b277e8248ccef0302d84937a6c076270999e73))
* **reconcile:** chain reconciliation recovers UTXOs SPV bloom missed ([41865f3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/41865f3eb096d88fe66d3d1aa27c86e3f896a769))
* **recovery:** auto-reconcile on version bump + IME padding fixes — v3.5.16 ([c9229bb](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c9229bb57aa55047521b49138ac6f089f213bbe3))
* **recovery:** expose allWithFunds (incl. native) for foreign-seed sweep ([7abf95b](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7abf95b93892e0b21199110b0f7e72cc515436d7))
* **recovery:** onboarding routes to RecoverFundsScreen; author derivation vector test (pin pending) ([dfbb784](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/dfbb78408010c68a3753741ea0f494e5721d9aeb))
* **recovery:** pluggable UtxoSource seam + reconcile-backend impl ([a2b4d95](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a2b4d95cf43771ed722ecf4f9a1cd0dd18ef4de7))
* **recovery:** Recover Funds mode toggle + foreign-phrase entry UI ([46efeb6](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/46efeb692593501c0b140293f6e699cb9373f11d))
* **recovery:** RecoverFundsScreen + Settings entry + nav route ([e582c18](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e582c1834d3e00636723ddc8f2cc4bf09a53a62a))
* **recovery:** RecoverFundsViewModel (classify + sweep, seed-zeroing) ([1d0771a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/1d0771a1191f793d539f503c3e41f3b8533780dd))
* **recovery:** seed-bytes sweep entry point for re-runnable recovery ([fd025a8](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/fd025a857dd50b482656bfa485f18e4aeab2d363))
* **recovery:** SweepDestination (native default / validated external) ([8e98fe1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8e98fe14c47f87dfa4ff9f151131b67cc2c09adf))
* **recovery:** ViewModel foreign classify+sweep (entered phrase, seed zeroed) ([4fc4abc](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/4fc4abc995d5e7e05580eaac8035c21870e66e56))
* **restore:** Universal Restore — multi-path scan + sweep during recovery ([c4309c8](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c4309c8c4ab27baaf6f9d3427393ad1bd8df9a9c))
* **sync:** active-screen readiness — gently wake a dormant 0-peer manager on the 5s poll while foreground, Tor-guarded, startSync-only ([64899a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/64899a36fc9c1babd44e9d7c14d5ea1b057019b7))
* **sync:** anchor progress percent to authoritative chain tip — v3.5.19 ([676999f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/676999fb41e7eb0a3b224f815ccb44eeb088b4d6))
* **sync:** persistent rotating bloom peer pool ([287e3bf](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/287e3bff568dd30ff1feb25e5af8edd06e9b7a1a))
* **tor:** add DNS leak prevention to OkHttpClient ([1b60bfe](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/1b60bfe850675d99e13469c390985ffb3a8c17e4))
* **tor:** add kmp-tor dependencies for Tor integration ([8207f59](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8207f5956cd8c6ba832337b9f46dbe83096387d8))
* **tor:** complete Tor integration — kmp-tor exec mode ([1dcd79e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/1dcd79e4e2876d78b90ecf11e69d90d60d1299f0))
* **tor:** implement TorManager with kmp-tor runtime ([47b4324](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/47b4324f72779069f7bd284e4e3f6bd9129820d1))
* **ui:** open external URLs in Chrome Custom Tabs ([10909c7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/10909c712972d1caad25adc375b74a9e78a63122))
* **ux:** verbose sync state with scan-window honesty + send gate ([f779bd2](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/f779bd2b4df4c8e6b9e01efb3937117bce47787f))
* **wallet:** gate send/receive UX on peer connectivity ([913763f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/913763f48733bc654e2bdf0c9205c54ef90cace4))
* **wallet:** pull-to-refresh wake-up on the main screen ([7fce476](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7fce4763e4f285474a7180993dea4c27a5a14e80))


### Bug Fixes

* Fix:  ([e1e71a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e1e71a39775737f36ce7ec06465b4505f4e95a90))
* **app:** largeHeap=true — avoid OOM loading the heavy wallet on low-RAM devices ([25a270c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/25a270c79f8c2e0661ed91f07dbb9d0c207f226f))
* **assets:** populate utxos.is_asset via listunspent — Assets tab renders held assets ([5f049ba](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5f049bab1d421f4b9ff5d570995bc4bcd9b43687))
* **assets:** unwrap {data:...}, digiscope IPFS gateway, cache refetch on CID ([b9c716e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b9c716ecc59b88216fc4ee97ac0385b3cbf2da31))
* **balance:** release anti-flash guard after first Synced, trust C core ([7d33a7c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7d33a7cd3b40b8954e69daeb4ffce51a214d6158))
* **bip158:** accurate bloom-fallback banner text ([b0389d1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b0389d1b85e8658660f170cd485d5ec552d00d7d))
* **bip158:** active-probe continuity recovery + honest sync progress (v3.6.2) ([317aac3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/317aac397eee551a02dcaae911191b8e84f2066e))
* **bip158:** grace window so deep-deficit re-anchor isn't abandoned to bloom ([2e10d39](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2e10d392473cc7b83d73ab5eab0e2a61d4ee6545))
* bloom peer priority + sync stall recovery ([b7d5779](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b7d5779bcefe97307969e4c5111404e9f338421f))
* BRWalletNewDual upgrade crash — register txs after legacy addrs ([5f2b0b3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5f2b0b3e976376ab5b76f6ec1b444ecfa22ce996))
* bulk-add saved txs to prevent send transactions being dropped ([bb887b9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/bb887b9c3a733099a11a6e194abec284698d4960))
* **connectivity:** runtime peer injection actually reaches the live manager (v3.5.42) ([58398cc](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/58398cc1e06aeefc867e77f45a8a07c78c85a29a))
* **dandelion:** inject dandelion peers at ALL sync-start paths ([de27c36](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/de27c361a085a90aaeef29a017c6be612377928c))
* defer mempool tx cleanup until chain tip — prevents send tx deletion ([c868826](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c868826b633af290b46d0c62194aa79656cb027f))
* detect dead Tor proxy and fall back to direct connections ([243ccaf](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/243ccaf38900f32cca07cce7764b794dd428b18e))
* filter non-bloom peers from gossip to prevent pool flooding ([2d8de26](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2d8de2648487f7beb6da710dc36030f305bd972b))
* force rescan on BIP84 upgrade + wider legacy scan ([f980ee7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/f980ee79567c1ea2a301daba9dd99d7ed242d8d2))
* **native:** publishTransaction passes NULL/NULL to BRPeerManagerPublishTx ([8674fa7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8674fa74a3f558835bf42cfd1c5db0daea16ec9d))
* **onboarding,tor:** keyboard scroll-into-view + tor failure teardown ([617cf52](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/617cf528b4ec88754da69d014d5d4959d9223ca8))
* **onboarding+16kb:** reject invalid recovery phrases + 16 KB page-size compat (v3.6.4) ([0464d71](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0464d71b9b92428232ce3382afa77df147b37bb0))
* **onboarding:** freshly-set PIN was being wiped by createWallet — v3.5.37 ([f141808](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/f141808d9af035803411d08c310373e26aad24ba))
* only run cleanup at chain tip (lastBlock &gt;= estimatedHeight). ([c868826](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c868826b633af290b46d0c62194aa79656cb027f))
* prevent parent tx deletion during sync in dual-key wallets ([0ff6f06](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0ff6f060e86ac09e8e747d040755cd9415303f30))
* re-inject bloom peers on reconnect when all peers drop ([b1d26f2](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b1d26f2fb575281f2c999c2ccc949dd8c0c3f702))
* **reconcile:** banner fires only when balance == 0 — v3.5.35 ([b9ae757](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b9ae757db64ff062f09cee82782dbdc2a0b8742c))
* **reconcile:** only show failed-reconcile banner after sync settles — v3.5.33 ([9e11b3f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/9e11b3f6e751da6655b33d86c7d694dd0e76202f))
* **reconcile:** surface post-upgrade reconcile failure to user — v3.5.32 ([53c451f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/53c451fd920f5394a04a61d1df43386444a81d83))
* **reconnect:** wake-up — recover a stuck 0-peer manager via clean recreate ([3a74e51](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/3a74e51ea52298d4c14351c8d9dca048b79fcd7e))
* **recovery,assets:** persist sweep + DigiAsset broadcasts for stranded-send recovery ([a548f08](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a548f084b94643593c9a09316dbca6d2870f95b4))
* **recovery:** amount-provenance gate — refuse sweep on unreachable backend or non-positive amount ([87c3466](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/87c3466536f556d053b8ef2731267fbbc227fcfb))
* **recovery:** carry explicit (chain,index) from derivation (bug [#3](https://github.com/JohnnyLawDGB/digibytewallet-android/issues/3), derivation side) ([d93c37e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d93c37ed1df7f14d0f5086ffd8637012608f0599))
* **recovery:** clean empty-findings UI, safe error-copy fallback, tidy outcome check ([13d7675](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/13d7675e5fcf84285f5e8c4b557bdf4aef5b58ca))
* **recovery:** don't render self-sweep as negative "Sent"; tighten classify cache ([89a17c8](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/89a17c8210a340276446f24671d161463d00cb74))
* **recovery:** drop dead passphrase param; classifyDerived no longer publishes terminal state ([af90c9a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/af90c9aa3e98f2a380eb591910b278fd88bdcaa5))
* **recovery:** FakeUtxoSource preserves fixture chainHeight ([d4d343d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d4d343d197b4125710219ef065a7d3f42c139cb9))
* **recovery:** guard mode-switch, drop foreign phrase from saved state, show foreign errors, cancel stale scan/sweep on reset ([a133102](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a133102f46f41626a0964e5f6ee1a0634e4793ea))
* **recovery:** honest empty-vs-unreachable + auto-skip + serialize reconciles ([56a61e7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/56a61e7332da4ebd3c7392c1b1007394a08637b7))
* **recovery:** lowercase foreign phrase before BIP39 validation, tidy null-derive branch ([5740ecb](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5740ecb91bdaa2d0c16440eb48c67bffa87ae1c5))
* **recovery:** native fee-sanity guard rejects grossly under-reported legacy sweeps ([730fbab](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/730fbab441b1ae36c8b23aa78f52ba2d2a58f03c))
* **recovery:** rethrow CancellationException, disable mode chips mid-scan ([2005af0](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2005af06cebbd1be962771597c5101d3afb9a0cd))
* **recovery:** sweep consumes carried (chain,index); skip null-script UTXOs (bugs [#3](https://github.com/JohnnyLawDGB/digibytewallet-android/issues/3), [#4](https://github.com/JohnnyLawDGB/digibytewallet-android/issues/4)) ([289ebc5](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/289ebc5e7708f034899d4b7fd44b9e746c6a3a44))
* **recovery:** treat a returned sweep txid as PENDING, not confirmed success ([e087024](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e08702468c37d140698542a561fcb2ccd3d5fb1e))
* remove forced rescan on BIP84 upgrade — preserves send transactions ([4376529](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/43765293d2e25ace6ca1978044de1f6ed803e43d))
* resurrect keepalive + ANR/UX polish — v3.5.18 ([4bcb2df](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/4bcb2df9966af8aa475f5d6b52fe44707e87960d))
* ship chain-tip cleanup protection for historical sends (v3.5.11) ([6d9d151](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/6d9d151525bc02f0b38b8f1f831730b937d2101c))
* SOCKS5 IPv4/IPv6 support, dead proxy detection, clearSocksProxy on toggle-off ([17a42f7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/17a42f748ce46f0ec6c3cc8bb722875b0857c6a4))
* sort transactions newest-first, fix QR scanner on Android 15 ([e3fa68b](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e3fa68b0176e6171e828fdf58084b67e3b52acda))
* **sync+tor+banners:** BOTH default, peer-DoS crash, fix start-sequence banners; Tor off by default (v3.6.6) ([af64ea0](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/af64ea0a52fc049b5f04f9df7d5cff2e9e26b1d0))
* **sync:** defensive reconnect on foreground return; harden keepalive loop ([d6a3266](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d6a3266aba26c1ab7911737b1c71f61689ce3708))
* **sync:** detect Doze-frozen keepalive coroutines via tick watermark ([b2af401](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b2af40102233be9f0555a285c5957be8966f9eb6))
* **sync:** serialize g_peerManager — native UAF/SIGSEGV crash-on-open (Android 15) ([451b83b](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/451b83b452801f7f422cb5abdcda5b1dca3ad4b3))
* **sync:** stamp new-wallet creation time to now, not a hardcoded 2025 date ([c36f6ee](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c36f6eea6f75bd325dc1b44b72a425659239f858))
* **sync:** tighten Synced gate + reconcile prunes stale UTXOs — v3.5.20 ([98a14fd](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/98a14fd75a7b145b179ff0c1672d887ca7eb9356))
* **sync:** UI-level watchdog + stickier notification for process death ([856e595](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/856e595760e41bf2a167e884c66c1726f09f0617))
* **sync:** unify sync indicators against single source of truth — v3.5.34 ([e1e71a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e1e71a39775737f36ce7ec06465b4505f4e95a90))
* **tor:** address code review issues in TorManager ([378c95d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/378c95dc795b3085a92518e556ecb121d0faf771))
* **tor:** mark _runtime as @Volatile for DCLP correctness ([5867c5f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5867c5fcafe288217f885542670f8378f2dc01da))
* **tor:** prevent bootstrap cancellation on Settings navigation ([0d4ff13](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0d4ff13d4f6ea4a4792721d90fc73f9c2825508d))
* **tor:** route bloom seeder fetch through OkHttpClient ([7eebfd1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7eebfd159ab8bf570580e60bc701c0b6dac67a91))
* **tor:** switch kmp-tor to no-exec mode so Tor works with 16 KB packaging (v3.6.5) ([d7c8ec6](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d7c8ec60f86dcde1bafa089dc70a847fe572def8))
* **tx:** show recorded send amount when BRWalletAmountSentByTx partially undercounts (parent UTXOs not all in allTx) ([64899a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/64899a36fc9c1babd44e9d7c14d5ea1b057019b7))
* **ui:** correct Wallet Type label from BIP44 to BIP84 on About screen ([a208ac9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a208ac9d0046bfd7eb13d3d87d7224d36c998248))
* **ui:** lifecycle-aware flow collection across Settings/Onboarding — v3.5.17 ([0a7b75f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0a7b75f7036dc1a8cfbdb1724c3c49cca462e04e))
* **ui:** run wallet poll loop on Dispatchers.IO — main-thread getPeerCount() blocked on the v3.7.1 PEER_GUARD mutex during a send, ANR'ing the UI (~52s input stall). Device-verified: send completes, no ANR. ([796313d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/796313dc51640b51f68c0c7dd19f476bac57f462))
* **ui:** share WalletViewModel across send/receive/tx-detail — v3.5.15 ([aab51d0](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/aab51d045a33393781f83795af850fdc1aeb275a))
* **wallet:** balance=0 after restart for wallets with deep address use — v3.5.36 ([65d9760](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/65d97603151ca82fac3268c8adde0f4432cbaee7))
* **wallet:** persist tx + extend gap so post-broadcast force-stop doesn't drop sends (v3.5.41) ([e135189](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e135189a41589a59d3fffe1cc76f50bf65ad247e))
* **wallet:** show the most-recent transactions, not the oldest (v3.6.3) ([dbd5449](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/dbd5449962f005178ab61e54248e3bdb4dbe5ae3))
* widen legacy address scan to 150/50 for upgrade coverage ([c3adec9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c3adec92226c9257850d072142bf4ef1d9bd808c))


### Performance

* **recovery:** de-dupe onboarding classify to hit reconcile backend once (bug [#8](https://github.com/JohnnyLawDGB/digibytewallet-android/issues/8)) ([8622c39](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8622c3939a7aacf603b7754f7d89f13fee698b39))


### Tests

* BIP84 derivation path sanity tests ([58fbc6a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/58fbc6a12aa0088ceaf84bd07917e2dcfcd4f3dc))
* **ipfs:** mock android.util.Log in IpfsClientTest ([d5c3813](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d5c38134d1715af521b2854797344d8020c630ef))
* **reconcile:** cover PostUpgradeReconciler + land BIP157 rollout plan ([d13ebf1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d13ebf1327d8f433a04949120f18b3d446510b9b))
* **reconcile:** update PostUpgradeReconciler tests for fresh-install skip ([74be1b2](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/74be1b2ccb31260e0366777db80fff3fb01f6cef))
* **recovery:** cover SweepDestination trim + empty-string edges ([7327c82](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7327c82d9b3ae09d4b8438134c916a51b57975d7))
* **recovery:** Layer-B signed-tx KAT + native isRawTransactionSigned helper ([950e86c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/950e86ccbb70700e85feab5915f752643b7b0b5f))
* **recovery:** over-report rejection check via node testmempoolaccept (bug [#2](https://github.com/JohnnyLawDGB/digibytewallet-android/issues/2) defense d) ([c4be744](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c4be7444aca7606403617cd64b25de5eeb425f33))
* **recovery:** P2WPKH-input signing KAT (native BIP84 sweep) ([678e45d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/678e45dd639bc637734daf21d01ca598cee33618))
* **recovery:** pin classify edge cases — empty / multi-addr sum / BIP49 deferral ([2651b1a](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2651b1a041d503a914aefc927117de43ff499a08))
* **recovery:** pin legacy derivation known-answer vector ([4bb943c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/4bb943c3f75f7a4cde4e4cbd3fce60e2e55cc8fb))
* **tor:** add TorState unit tests ([9857295](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/985729576d69e2dd73a2f976b87458f7f8099b25))


### Documentation

* add legacy derivation spec for old wallet seed recovery reference ([b5542b7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b5542b76ee43c97687a4ddd16c58e5bbb450ba2a))
* add Tor integration implementation plan ([6af2d3c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/6af2d3c3baf1e73d6f76fe80b6a45b2a42bef84f))
* **bip158:** design spec for continuity-failure re-anchor recovery (v3.6.2) ([ad6afd7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/ad6afd7f853d272edd63f4cdb2ded0ed7a12a430))
* **bip158:** design spec for filter-chain re-anchor (deep-deficit recovery) ([703958f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/703958fd891b81c30ebaa75dd50535c872188e38))
* **bip158:** design spec for watchdog stall-recovery + banner accuracy (v3.6.1) ([cd06678](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/cd066786c76151c062f03739fc7835f6b62d8ee6))
* **bip158:** implementation plan for continuity-failure re-anchor recovery ([22aca3d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/22aca3d5db1347fec4b6d7653be91ab2f976c4d8))
* **bip158:** implementation plan for filter-chain re-anchor ([ef07960](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/ef07960c28ad020f0ac133f05fddbda380081052))
* **bip158:** implementation plan for watchdog stall-recovery + banner fix ([65f74c3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/65f74c3372407bd914e399d4b991e22d2bdf61d8))
* BIP84 derivation implementation plan ([5abe084](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5abe0842a2ee5aa71a3fe9f3a99d4e6c3ddd8f69))
* BIP84 derivation path design spec ([9d9a252](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/9d9a2528769c20334af75b65a3e587e85d84ac33))
* **bounty:** correct rewards to DGB, link from SECURITY.md + README ([079c798](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/079c798152b53b964f4822595536f177e992ced5))
* **bounty:** launch security bug bounty program — up to USD \$100K ([d46dddf](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d46dddfb91a482a3a5b3b95d6d69ba973d3f232a))
* **bug:** track SyncService stall root cause as process death ([0a9d062](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0a9d0623dc325054f573ad3fb6df97bcca29392e))
* bump current-version banners to v3.5.16 ([160a440](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/160a440f40cd4d1abcdddc6e8d0789ba157fa0e4))
* **dandelion:** SPV Dandelion++ stem-submission design spec ([8599a11](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/8599a11617603c7875ed1aedb8f2071dc35e0fff))
* **dandelion:** SPV stem-submission implementation plan ([e12e4f9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e12e4f9e26b180222b65a4fb41799878ea5b4169))
* legacy funds sweep design spec ([2f5d54d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2f5d54da04aec881fd493073c40200d0ece197b6))
* legacy funds sweep implementation plan ([863268f](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/863268fa58ec0ef81761a812c1ec2885448eafe8))
* **maestro:** explain requires-wallet tag-exclusion and prelude deferral ([09722dc](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/09722dcad7d0ab0681dd32a829904e8e2969ef66))
* **ops:** clarify no port changes needed for filter serving ([84997a1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/84997a1685a78aeae08e93da0933cc0a9b80601f))
* **ops:** node-operator guide for enabling BIP 157/158 filter serving ([3d3ebc4](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/3d3ebc498a5a68c210bfba89189caedf76c9d9a9))
* **phase 0:** ARCHITECTURE, THREAT_MODEL, BIP_COMPLIANCE, PROCESS_FLOWS ([6ae38fb](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/6ae38fb996d58a3d42655fd18beb6b9df65c769f))
* **plan:** scanFromSeed drops passphrase; classifyDerived caller-publishes state ([7ed3d53](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/7ed3d5357625b307cf5472644b5f210164bed01b))
* reconcile CLAUDE.md/ROADMAP.md with shipped reality (v3.7.6) ([87089b1](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/87089b1bc345cb83c09b4359dbf0e33f5886b649))
* **recovery:** foreign-seed sweep design spec (import another wallet) ([f121e55](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/f121e559bbec616b997f91c767c219cded20f01f))
* **recovery:** foreign-seed sweep implementation plan ([b14ade9](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b14ade91d387ce10529ce1d58991fa4d3361f666))
* **recovery:** mainnet proof PASS — legacy sweep confirmed on-chain ([fab58c3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/fab58c38ee22486b72c29e07f266ad1c91c03816))
* **recovery:** sweep hardening + test/proof design spec ([d669070](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d66907093ce4f50635affa1cb9a49a2dd3086939))
* **recovery:** sweep hardening implementation plan (6 bugs + test layers + proof) ([71a6e4c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/71a6e4c5841e4e98187e40d4da8f3c1a5e4bd3f9))
* **security:** v3.6.6 changed-surface audit + MobSF report ([c0992a8](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c0992a8663b6fe1af782d93b614fcf33c00fb6ae))
* sovereignty-first roadmap and Phase 1 BIP 157/158 issue breakdown ([71a35d7](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/71a35d7d622e8e5e5afc827e335104f9943bb4f5))
* Tor integration design spec — kmp-tor with SOCKS5 proxy ([048b002](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/048b002b85d5baf2d259cf98b3de88c327f9cbea))

## [3.3.0](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.2.0...v3.3.0) (2026-04-08)


### Features

* Digi-ID deep link forwarding after PIN unlock ([d0b234e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d0b234e7a2a1b787f4541617cdfb11291615e14e))
* own messages right-aligned with distinct styling in chat ([0e76bd8](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0e76bd8e25487c6c14da575722f3a6a009b47d61))


### Bug Fixes

* keyboard covers chat input + chat timestamps wrong ([c454abe](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/c454abe4997667f856ef93a9b6e2571a8fdd2b40))
* profile refreshes every time Profile tab is visited ([97330bd](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/97330bd9fc51cabff94291a2f27c7c289c2c87e1))

## [3.2.0](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.1.1...v3.2.0) (2026-04-08)


### Features

* show version in status bar when synced ([bb7049e](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/bb7049e86cd9eaac05bea189e16ae16f5bd84ecb))


### Bug Fixes

* complete BIP39 word list — was missing 1807 of 2048 words ([1bc336d](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/1bc336d98c5360dce7794f4f491a28e5c14aa5cc))
* persist currency preference across restarts ([2f1540b](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/2f1540b255e755d60e770e60603a6845610bbd35))
* view recovery phrase actually decrypts and shows the seed words ([69de7cc](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/69de7cc5522d0be1397e5202ecbb04b76071db51))
* wipeWallet clears saved transactions and stops sync ([5000f62](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5000f621a0ce5ad9704e40126deb5410f7c12280))

## [3.1.1](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.1.0...v3.1.1) (2026-04-07)


### Bug Fixes

* DigiRunner game shows during ALL sync states, not just Syncing ([4b31448](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/4b31448aeea6018ed89881b580cbea63f81c18e8))
* double PIN prompt — startDestination computed once, not on state change ([08e3fcf](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/08e3fcfc959ba486759558fa5ad5f54a9c2a3eb9))
* rescan resets hasReachedSynced so progress + DigiRunner show ([bb97e79](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/bb97e791ddafaf16a63e3ba860b0089b69283782))
* sync progress visible on new wallets — poll-driven state updates ([6782c46](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/6782c46b597f4336924d78f8f35dcc52cb3d1922))
* SyncService starts on wallet screen arrival, not just startDestination ([e2fe10c](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e2fe10c478e3a946119cb0aea50e403955bc0b28))
* visible sync progress — start from block ~20M, show block height always ([98c5721](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/98c5721ff40cc3c911c9119a33f4441291ca76e9))

## [3.1.0](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.0.16...v3.1.0) (2026-04-07)


### Features

* currency switcher (USD/BTC/PHP), always-on sync, About fixes ([5c2b2e5](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/5c2b2e58e1d830590272ae35fc40c1536d447c93))
* DGB logo from official repo, rescan block progress ([d900f81](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/d900f81079576864f07ae289785cde2aa8ae1589))
* in-app update checker — notifies users of new releases ([25b8725](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/25b87250d4aabdacad5b060975d19fa7bf261230))
* official DGB logo on onboarding + dynamic version on settings ([e216006](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/e2160064b9ff049af57863b94f6ddbd53b9c6942))
* Phase 1.2 — release-please for automated versioning + changelogs ([a69b416](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a69b4165f0783c34f468c1dd3c9502ebd2531424))
* Phase 1.3 — Maestro E2E test suite ([ed161a3](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/ed161a3f1138c7ac1ad51e9893290c1b3584be2b))
* Phase 1.4 — Detekt static analysis + Renovate dependency updates ([b8c6e40](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/b8c6e4052b6f1727ce63611e01985b6a025ee7ab))
* Wallet Info section + dynamic version on About screen ([5624032](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/56240326ba0ee19c090c185f26fb979f8d84a2b8))


### Bug Fixes

* confirmations never updating — TransactionEntity.equals only compared txid ([a5597ad](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/a5597ada6a105b6354c2596c311ab9f9f12bdfe3))
* release-please target-branch for non-default branch ([0ccd5c2](https://github.com/JohnnyLawDGB/digibytewallet-android/commit/0ccd5c2a1b62ecc40fb9afb978824d6dfad338cd))
