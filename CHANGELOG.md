# Changelog

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
