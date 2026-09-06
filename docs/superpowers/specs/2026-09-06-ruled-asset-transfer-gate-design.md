# Rule-bearing DigiAsset transfer gate — design

**Date:** 2026-09-06 · **Status:** approved (chat, 2026-09-06) · **Branch:** `fix/ruled-asset-send-gate`
**Report:** `security/reports/bounty/2026-09-06-ruled-asset-send-burns-holding.md` (HIGH, Brasa Studios)
**Base:** local `develop` 5619cbfb (audited snapshot 68abf333 is identical on every file touched)

## Problem

A DigiAsset issued with opcode 0x03 or 0x04 carries transfer rules (royalty, deflation, signers,
vote, KYC/geofence, expiry). Every DigiAsset Core indexer re-checks those rules on replay
(`DigiAsset::checkRulesPass`); a transfer that fails one has the assets on **every** output
cleared and its inputs spent (`DigiByteTransaction.cpp:271-277`). The wallet builds transfers
that satisfy no rule, on two paths:

- `AssetManager.sendAsset` (`AssetManager.kt:1382-1592`) → plain v3 `0x15` OP_RETURN →
  `buildAndSignAssetTransferTx`.
- Recovery: `ForeignAssetTransferPlan` → `ForeignAssetTransferService.moveAssets` →
  `buildAndSignForeignAssetTransfer`, to the current wallet or an external address.

Nothing on either path knows whether the asset is ruled: `DigiAssetRules.kt` is an uncalled stub,
`DecodedAssetHeader.opcode` is dropped by every downstream model, `AssetMetadataEntity.rulesJson`
has no writer, and the API parsers discard Core's `rules` object. After such a send the wallet
reports success and keeps counting its own change output as held (count-iff-native-holds), so the
sender sees a phantom, not a loss.

## Goals

1. No path in this wallet can broadcast a transfer of an asset that may carry rules.
2. "May carry rules" is decided fail-closed: unknown means refuse.
3. The user is told, in plain language, in every supported locale, why the action is blocked and
   that the asset is untouched.
4. Existing installs are covered after upgrade without a rescan.

## Non-goals (follow-ups, not this branch)

- Building rule-compliant transfers (royalty outputs, deflation burns). Phase 2.
- Parsing or displaying rule contents. The decoder only flags that rules exist.
- Removing `DigiAssetsNetClient` (squatted domain). Its own branch.
- The on-device reproduction with the reporter's throwaway asset. Follows the build; the protocol
  is in the report file.

## Design

### 1. Rule state and the gate (pure, `core/asset/rules/`)

```kotlin
enum class TransferRuleState { NONE, RULE_BOUND, UNKNOWN }

object AssetTransferRuleGate {
    fun stateOf(issuanceOpcode: Int?, issuanceLocked: Boolean?, apiRulesPresent: Boolean?): TransferRuleState
}
```

Decision table (first matching row wins):

| issuanceOpcode | issuanceLocked | apiRulesPresent | state |
|---|---|---|---|
| 3 or 4 | any | any | RULE_BOUND |
| any | any | true | RULE_BOUND |
| 1, 2 or 5 | true | false or null | NONE |
| 1, 2 or 5 | false or null | any | UNKNOWN (unlocked: a later reissuance can add rules) |
| null | any | false or null | UNKNOWN |
| other value | any | any | UNKNOWN |

Only `NONE` may move. `apiRulesPresent` is true iff the proxy response carried a `rules` object
with at least one key (Core emits the key only when `!rules.empty()`); `null` when no endpoint
answered. The chain-proven opcode is the floor; the API is corroboration that can only tighten.

### 2. Decoder

`DecodedAssetHeader` gains `val hasRules: Boolean get() = opcode == 3 || opcode == 4`. Nothing
else changes: issuance supply is credited by `AssetTxQuantity.forOutput` to the first
non-OP_RETURN output without reading instructions, so the (already misparsed) instruction bytes
after a rules block do not affect crediting. Documented in the KAT.

### 3. Provenance carries the opcode

- `ResolvedAssetFacts` gains `issuanceOpcode: Int?` and `issuanceLocked: Boolean?`.
- `AssetProvenanceEntity` gains `issuanceOpcode INTEGER` (nullable) and `issuanceLocked INTEGER`
  (nullable). `MIGRATION_10_11` adds both with the idempotent PRAGMA-check pattern of
  `MIGRATION_9_10`; `WalletDatabase.version = 11`; schema `11.json` exported.
- `AssetProvenanceDao.issuanceFactsFor(assetId)` returns the first row for that assetId whose
  `issuanceOpcode` is non-null (all rows on a walked path share facts).
- `AssetManager.classifyProvenanceHop` fills both fields from `header.opcode` / `header.locked`
  (today the walk only resolves locked issuances, so `issuanceLocked` is true on every resolved
  path; the field exists so the invariant is data, not an assumption).
- `AssetProvenanceWalker.resolve(startTxid, forceToIssuance = false)`: with `true` the walk
  ignores `assetFor` short-circuits (both the start hit and the "known ancestor" hit) and walks
  until it reaches `Hop.Issuance`, then `putAssets` for the whole path as today. Frontier logic
  unchanged. This is how a pre-migration row (null opcode) is upgraded on demand.

### 4. API rules signal

- `AssetDataResponse` gains `rules: Map<String, Any?>?`.
- `DigiScopeAssetParsing` (and `DigistampAssetClient` if the response shape carries it) parse
  `rules`; `DigiAssetsNetClient` is untouched.
- `AssetMetadataService` writes the object as a JSON string into `AssetMetadataEntity.rulesJson`
  when it stores metadata for an asset; `null` when absent from a successful response. A failed
  fetch leaves the column untouched.
- `AssetMetadataDao` exposes `rulesJsonFor(assetId)`.

### 5. Composition in `AssetManager`

```kotlin
suspend fun transferRuleState(assetId: String): TransferRuleState          // reads only, no network
suspend fun verifyTransferRulesForTx(txid: String): TransferRuleState     // force-walk + fetch
suspend fun verifyTransferRules(assetId: String): TransferRuleState       // picks a held UTXO, delegates
```

`transferRuleState` combines `issuanceFactsFor(assetId)` and `rulesJsonFor(assetId)` through the
gate. `verifyTransferRulesForTx` runs `resolve(txid, forceToIssuance = true)`; if the walk reaches
an issuance it refreshes metadata for the resolved assetId (which persists `rulesJson`) and returns
the gate's verdict on (opcode, locked, apiRulesPresent); if the walk does not reach an issuance it
returns `UNKNOWN`. `verifyTransferRules(assetId)` picks one held UTXO of the asset and delegates
to the txid form; it is what the screens call when the read-only state is `UNKNOWN`. `OwnedAsset`
gains `transferRules: TransferRuleState`, filled from `transferRuleState` in `getOwnedAssets`.

### 6. Send-path gates

- `TxResult.Refused(val reason: SendRefusal)` is added to the sealed class;
  `enum class SendRefusal { RULE_BOUND_ASSET, RULES_UNKNOWN }`. `sendAsset` calls
  `transferRuleState(assetId)` before step 1 and returns `Refused` unless `NONE`. It never
  re-verifies itself; verification is the screen's job, so a bypassed screen simply cannot send.
- `AssetViewModel`: on asset selection, if `transferRules == UNKNOWN`, launch
  `verifyTransferRules` and expose `RuleCheckState { CHECKING, NONE, RULE_BOUND, UNVERIFIED }`
  (`UNVERIFIED` = verification finished without reaching `NONE` or `RULE_BOUND`, e.g. offline).
  `SendState.Refused(reason)` maps `TxResult.Refused`.
- `AssetSendScreen`: Send is disabled unless `RuleCheckState.NONE`; a banner explains
  `CHECKING` / `RULE_BOUND` / `UNVERIFIED` (with a retry for `UNVERIFIED`). `AssetDetailScreen`
  shows the same badge for `RULE_BOUND` and hides its Send entry point.

### 7. Recovery gate

- `ForeignUtxoAssetClassifier` takes an injected
  `resolveRuleState: suspend (txid: String) -> TransferRuleState` (default:
  `AssetManager.verifyTransferRulesForTx` for the outpoint's txid; foreign transactions are not in
  the native wallet, so the walk fetches them through the asset network client as the M3 walk
  already does) and its asset verdict carries the state.
- `ForeignAssetTransferService.moveAssets` moves an outpoint only when the state is `NONE`;
  otherwise it records `Move(refusal = MoveRefusal.RULE_BOUND | RULES_UNKNOWN, txid = null)` and
  leaves the outpoint where it is. `Move` gains `refusal: MoveRefusal?`; existing
  `failureReason` strings are untouched.
- The DGB sweep already holds every asset-bearing outpoint (`SweepPartition`), so a refused asset
  stays on the old seed untouched.
- `RecoverFundsScreen` renders `refusal` through string resources.

### 8. Strings (all 13 locales in `AppLocale.SUPPORTED`, keys in `strings_wallet.xml`)

`as_rules_checking`, `as_rules_bound_title`, `as_rules_bound_body`, `as_rules_unverified_body`,
`as_rules_retry`, `as_refused_rule_bound`, `as_refused_rules_unknown`, `rf_move_refused_rule_bound`,
`rf_move_refused_unknown`. English first, then machine-assisted translations for the other twelve
in the same commit; `LocaleResourceParityTest` and `OnboardingHardcodedStringTest` must pass
(`AssetSendScreen.kt` and `AssetDetailScreen.kt` are already in `COVERED`; add
`RecoverFundsScreen.kt` if it is not).

### 9. Tests (written red first, each must fail on the unfixed code)

1. `DigiAssetDecoderTest`: the real 5401 issuance payload
   `444103041a343da7c7a2b6d25667e63e24980cb56ddf65970bbd8315e99e97c0ec847d9e0510101f000510`
   decodes to version 3, opcode 4, `hasRules = true`, locked, totalQuantity 5; an 0x01 issuance
   and an 0x15 transfer give `hasRules = false`.
2. `AssetTransferRuleGateTest`: every row of the decision table.
3. `AssetProvenanceWalkerTest`: `forceToIssuance` re-walks past a cached start txid and a cached
   ancestor, and memoises the path with the opcode; default mode unchanged.
4. `RoomProvenanceStoreTest` (or the existing store test): opcode/locked round-trip; null for
   legacy rows.
5. `Migration_10_11Test`: versions, both ALTERs when absent, none when present.
6. `DigiScopeAssetParsingTest`: `rules` parsed when present, null when absent.
7. `ForeignAssetTransferServiceTest`: an outpoint resolving `RULE_BOUND` or `UNKNOWN` is not
   signed, not broadcast, reported with the right `refusal`; `NONE` moves as before.
8. `AssetSendScreenStateTest` / a new `RuleCheckStateTest`: Send enabled only for `NONE`.
9. Full `:core:testMainnetDebugUnitTest` and `:app:testMainnetDebugUnitTest` green; native host
   KATs unaffected (no C changes).

### 10. Rollout

No version bump on this branch. Release timing sits under the 2026-09-05 founder-review hold; the
plan pins snapshot 68abf333 and treats later pushes as a scoped delta. Before release: build the
mainnet debug APK, run the reporter's live protocol (unfixed build first, per-row logs, then the
fixed build), and add both results to the report file.
