# 2026-09-06-ruled-asset-send-burns-holding — Sending a rule-bearing DigiAsset builds a rule-less transfer; DigiAsset Core invalidates it and the sender's entire holding is destroyed
**Received:** 2026-09-06 (forwarded by Johnny)
**Source:** direct (message to Johnny; reporter is a DigiAsset Core contributor working the royalty-rules bug on the Core side; handle not given in the forward)
**Reporter:** Medina (Brasa Studios) — holds royalty-ruled test asset 5401; reproduced the Core-side mechanism with asset 5383 on mainnet 2026-08-27; tested chopperbriano's Core-side fix (assertTransferableAsset) on 2026-09-06; planning the "Elements of War" ruled collection for phone wallets

## Raw report
Johnny, heads-up on something I'd rather you hear from me first, since Jared's audit is going to hit it.

I've been working through the DigiAsset rules problem on the DigiAsset Core side (the one where
sending a royalty-ruled asset destroys the sender's whole holding - I reproduced it on mainnet on Aug 27 and just tested Brian's wallet-side fix on the 6th). While checking the audit plan's Wave 5 against your code, I found the same door in the wallet.

The short version:

- DigiAssetRules.kt is a Phase 2 stub. It checks quantity and the NFT-split rule; royalties, KYC, expiry, deflation and signers are all TODO Phase 3 and it returns allowed for every one of them.

- Nothing in production code calls it at all - I grepped the whole tree at 68abf333. Only its own unit test references it.

- The decoder doesn't parse rules, and AssetData has no rules field, so the send path can't tell a governed asset from a plain one.

- The stub's own comment says Phase 3 comes "when the wallet needs to construct asset transactions, not just display them" - but asset send shipped (AssetSendScreen, AssetCoinSelector, jni_asset_send.c), behind your spend gate.

What that means in practice: someone holding a royalty-ruled asset in your wallet sends one unit, the wallet builds a normal transfer with no rule outputs, and every DigiAsset Core node clears every output including the change. Their entire holding of that asset is gone, silently, and the wallet reports success. Same mechanism I proved with asset 5383 - supply 5 to 0 from sending 1.

I haven't reproduced it end to end from the wallet yet, because there's no non-destructive way to get a ruled asset onto a phone except issuing directly to it. I'm happy to do that with you if you want a live case - I hold a royalty-ruled test asset (5401) and can issue another.

A cheap fix until Phase 3: the decoder already knows which issuance opcodes carry rules (0x03 and0x04). Refusing to send any asset whose issuance carried rules - with a plain message saying why -closes it without parsing a single rule. That's basically what Brian (chopperbriano) did on the Core side (assertTransferableAsset), just coarser.

Not urgent in the sense that few ruled assets exist today. Very urgent in the sense that the moment anyone issues a ruled collection to phone wallets, the first person who sends one loses everything. That's the exact thing I'm planning to do with Elements of War, so I'd rather it'sclosed before then.

Everything else I've reviewed in version .76 and .78 of your wallet has been excellent, especially the auth-binding.

Thanks, bro.

## Verification (2026-09-06, against HEAD 5619cbfb; audited snapshot 68abf333 is an ancestor and identical in every file cited)

Reporter identified from the on-chain metadata of test asset 5401 ("Brasa Royalty Refusal Test 003 … issued 2026-09-06 by Brasa Studios"): Brasa Studios.

| # | Claim | Result | Evidence |
|---|-------|--------|----------|
| 1 | `DigiAssetRules.kt` is a Phase 2 stub; royalties/KYC/expiry/deflation/signers are TODO and it returns allowed | CONFIRMED | `core/src/main/java/io/digibyte/core/asset/DigiAssetRules.kt:48-88` — quantity>0 and DISPERSED-split are the only checks; five `TODO Phase 3` blocks; `return RuleValidationResult(true)` |
| 2 | Nothing in production calls it | CONFIRMED at HEAD and at 68abf333 | only reference outside the class is `core/src/test/java/io/digibyte/core/asset/DigiAssetRulesTest.kt` |
| 3 | Decoder does not parse rules; `AssetData` has no rules field | CONFIRMED, with one nuance | `DigiAssetDecoder.kt:121-127` maps opcodes 1-5 to ISSUANCE and never reads the rules section; `DecodedAssetHeader.opcode` (`:310`) DOES keep the opcode byte, but `toAssetData` (`:332-345`), `ResolvedAssetFacts` (`AssetProvenanceWalker.kt:4-9`), `AssetProvenanceEntity` and `UtxoEntity` all drop it. `AssetMetadataEntity.rulesJson` exists (`:21`, migration 1→2) but has ZERO writers and ZERO readers. The three API clients parse only cid/issuer/count/decimals/ipfs and discard the `rules` object DigiAsset Core emits (`DigiAsset.cpp:904`). Native detector (`BRDigiAsset.h:26-30`) classifies by type-byte masks only |
| 4 | Stub comment says Phase 3 arrives with tx construction, but asset send shipped | CONFIRMED | `DigiAssetRules.kt:28-31` vs `AssetManager.sendAsset` (`AssetManager.kt:1382-1592`) → `DigiAssetEncoder.encodeTransferScript(version=3)` (`:1459`) → `NativeBridge.buildAndSignAssetTransferTx` (`native/src/main/jni/bridge/jni_asset_send.c:101-259`). Outputs: recipient marker, OP_RETURN, optional asset-change marker, optional DGB change (`:1527-1553`). No royalty/burn/rule output; no rules check anywhere on the path (`AssetViewModel.sendAssetTransfer` app/…/AssetViewModel.kt:147-185 checks only quantity/balance) |
| 5 | Every DigiAsset Core node clears every output including change | CONFIRMED from Core source (`/home/polloloco/DigiAsset_Core`, fork of DigiAsset-Core/DigiAsset_Core; upstream unchanged since v0.3.0 2024-12) | `DigiByteTransaction.cpp:438-441` runs `checkRulesPass()` after every non-issuance transfer; `:456-459` checks every input asset; `DigiAsset.cpp:721-751` throws `exceptionRuleFailed("Royalty")` unless an output pays each royalty address ≥ count×amount×rate/1e8; catch at `DigiByteTransaction.cpp:271-277` sets `_unintentionalBurn=true` and clears `assets` on EVERY output (sent units, asset change, and any bystander asset in the tx). Exemption `DigiAsset.cpp:696-701`: rules are skipped only when NO address gains the asset (pure consolidation/burn) — a send to any recipient always trips them |
| 6 | Cheap fix: refuse when issuance opcode is 0x03/0x04 | SOUND for every asset this wallet can send today; NOT sufficient in general | `DigiAssetRules.cpp:36-40`: the rules object is non-empty only for opcode 3/4. But `DigiAsset.cpp:400-415` (`handleRulesConflict`) returns early when NO old rules exist, so for an **unlocked aggregatable** asset a later same-assetId reissuance with opcode 3/4 attaches rules to an asset first issued with 0x01, and a later 0x01 reissuance inherits existing rules (`Database::addAsset` updates the rules blob per assetIndex; inputs are hydrated with the CURRENT rules). For **locked** assets (single issuance) and non-aggregatable assets (each issuance = new assetId) the issuance opcode is exact. The wallet's provenance walk resolves ONLY locked issuances (`AssetManager.kt:852-856`, unlocked → DeadEnd), and `sendAsset` selects by assetId, so every asset the wallet can name and send today is locked — the opcode gate is exact for them. Design rule for the fix: the authoritative signal is the asset's CURRENT rules (Core's `rules` object), the issuance opcode is a chain-proven floor, and "rules unknown" must fail closed |

**Chain-proven vector.** Asset 5401 `La8UJyF13C2VG1EbxvkWmDf3gu5y2W4Worg9rn`, issuance `de8969169a38241fe520f461c06f9222934d325c640b83b40172aa3f751a00c6` (height 24,164,934): OP_RETURN payload `4441 03 04 …` = version 3, opcode **0x04** (immutable rules); `api.digiscope.me/api/digiassets/asset/5401` → `rules.royalty.addresses.DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK = 10000000` (0.1 DGB per receiving output), `count: 5`. The burned asset 5383 `La9T71BHHeX8fSAnmqnSUkREY3ekJPGiGnhjTW` carries the same rule and now reports `count: 0` — consistent with the reporter's "supply 5 → 0 from sending 1".

**Second instance the report did not name.** The foreign-seed recovery path also builds plain transfers: `core/src/main/java/io/digibyte/core/recovery/ForeignAssetTransferPlan.kt:138` (`encodeTransferScript`) signed by `buildAndSignForeignAssetTransfer` (`jni_derive.c:735`). Its destination is a fresh address of the current wallet, which is a net gain for that address, so Core's consolidation exemption does not apply: sweeping a ruled asset from an old seed would destroy it the same way. No rules gate there either (grep of `core/recovery/` for rule/royalty/opcode: none).

**Feasibility of the gate.** The opcode is in scope at `AssetManager.classifyProvenanceHop` (`AssetManager.kt:841-882`, `header.opcode`) and is dropped at `:874-880`. Retaining it needs a field on `ResolvedAssetFacts` + `AssetProvenanceEntity` (Room migration) and a check before `sendAsset` step 1 and in the recovery classifier. A second signal is already available from the DigiScope proxy (`rules` object) but is discarded by `DigiScopeAssetParsing.kt:24-27`. Native-detected rows (`assetSource = NATIVE`) that were never provenance-walked have NO issuance opcode; a fail-closed gate must treat "rules unknown" as "do not send".

## Side finding (out of the report's scope, found while probing): the fallback asset API domain is squatted

`DigiAssetsNetClient.DEFAULT_BASE_URL = https://api.digiassets.net/v3` (`DigiAssetsNetClient.kt:111`; third in the default rotation, `app/src/main/java/io/digibyte/di/AppModule.kt:225-231`; class comment calls it "the official endpoint maintained by the DigiByte Core team", no cert pinning by design). As of 2026-09-06: `digiassets.net` whois shows Registrar **DropCatch.com 1388 LLC**, Creation Date **2025-09-08** (re-registered after the original lapsed), expiry 2026-09-08; A records are generic EC2 hosts; every path 302-redirects to `hugedomains.com/domain_profile.cfm?d=digiassets.net` (for sale); Let's Encrypt cert issued 2026-08-29 covers `api.digiassets.net`. Today it fails safe: OkHttp follows the redirect, the HTML body fails `JSONObject()` and returns null; the circuit breaker then skips it. Exposure: whoever buys the domain can serve `assetdata` (name/symbol/decimals/issuer/count/ipfs metadata) and `status` to any wallet whose first two endpoints are down. `getAddressHistory` has zero production callers at HEAD and at 68abf333, so no wallet address reaches it; `getRawTransaction` returns null by design. Recommend removing the client from the default rotation (or repointing it) — the trust anchor it describes no longer exists. Jared's plan lists DigiAssets.net in the Wave 12 per-endpoint review, so expect it there too.

## Adversarial verification (workflow wf_ebc1e97c-971, four independent lenses, all "not refuted / confirmed")

- **Scope precision.** What Core destroys is every asset unit on the CONSUMED INPUTS: sent units, change, and any other asset ID riding on those inputs (`DigiByteTransaction.cpp:271-277` clears every output's whole `assets` vector; `checkRulesPass` at `:456-459` evaluates every asset on every input). UTXOs the coin selector did not spend survive. "Entire holding" therefore means "entire input holding" — for 5383 the five units sat in one UTXO. The wallet's selector spends whole UTXOs, so a 1-unit send from a 5-unit UTXO destroys 5.
- **Consolidation exemption cannot save the wallet's layout.** Rules are skipped only if no address nets a gain of the asset. The recipient always nets +N (prior holdings are irrelevant, only tx inputs are subtracted), and the wallet's asset change goes to a DIFFERENT own address (`getChangeAddress(1, format=2)`, `AssetManager.kt:1539`), so even a self-send with change trips the rules.
- **Exchange rate cannot pass vacuously.** With no `RULE_ROYALTY_UNITS`, `getAcceptedExchangeRate` returns 1e8 (`Database.cpp:1998-2000`), so a DGB royalty of 10,000,000 sats requires an output of ≥ count×0.1 DGB−1 sat to the royalty address. (One non-burn escape exists only for a foreign-currency royalty whose rate was never published: `out_of_range` → tx stored as STANDARD with assets intact.)
- **Expiry.** `getIfExpired` (`DigiAsset.cpp:769`) fails every non-consolidation transfer unconditionally, so an expired asset is destroyed by any send from either wallet path.
- **Second production path CONFIRMED and wider than first noted.** The recovery asset move (`ForeignAssetTransferPlan.kt:102-190` → `ForeignAssetTransferService.moveAssets` `:121-282` → `jni_derive.c:735`) is reachable from Settings → "Recover funds from another wallet" for foreign seeds AND the wallet's own legacy chains (`RecoverFundsViewModel.kt:246`, isForeign=false), and its destination can be the current wallet's fresh receive address OR an arbitrary external address (`SweepDestination.kt:7`, `RecoverFundsViewModel.kt:226-246`). No rules gate; the classifier is a bare "carries a DA marker" boolean (`ForeignUtxoAssetClassifier.kt:31-33`). Its documented safety argument, "there is no arrangement of our arithmetic that burns it" (`ForeignAssetTransferPlan.kt:17-31`), is true only for rule-free assets. NOT affected: the DGB fan-out (no OP_RETURN, `ForeignAssetFanOut.kt`), the legacy DGB sweep, the DigiDollar move (`6a 02 'DD'` payload), and plain DGB sends. The wallet never builds a burn (0x25).
- **Decoder gap that matters for the live test.** For opcode 3/4 the Kotlin decoder reads hash and totalQuantity in Core's order and then feeds the RULES bytes to `parseTransferInstructions` (`DigiAssetDecoder.kt:138-141, :157-161, :183-187`, empty list on failure). It never skips the rules block, so a received rule-bearing issuance may decode with garbage or empty instructions; whether the phone credits a directly-issued ruled asset correctly is not established. Log the per-row COUNT/drop decision during the test (CLAUDE.md process rule). The native detector has the same blind spot (`BRDigiAsset.c:141-151`, `BRAssetData` never populated).
- **Post-send state is a permanent phantom.** Success is a function of broadcast alone (`AssetManager.kt:1567-1592`; UI `AssetSendScreen.kt:172-176` shows the broadcast banner). The wallet's own change output is skipped while unconfirmed (`SyncService.kt:2698-2699`, `AssetManager.kt:735`) and then inserted by the confirmed-tx sweep as a NATIVE HELD row (`AssetManager.kt:985-1005`, `:569-590`, `:754-768`); `BRWalletOutpointAssetState` is a spent-set check with no rule awareness, and every indexer write/prune path was removed in v4.0.36 (`AssetManager.kt:338-361`), so nothing corrects it. The phantom is re-selectable as an asset input (`:1393`), so the next send chains another phantom and burns real DGB on two 6,000-sat markers plus fee. The recipient's same-app wallet credits a phantom immediately (`SyncService.kt:2864-2865`). The activity row shows the intended quantity as sent.

## Triage
- **Severity: HIGH** (upper half). Rubric: C1 (direct destruction of funds-at-rest: the user's whole input holding of the asset, silently, with a success banner) + A1 (no attacker needed for the accidental case; an issuer can weaponise it by airdropping a ruled asset to phone wallets, after which any send destroys it) + R1 (mechanism proven from source at both ends; Core half proven on mainnet by the reporter via asset 5383; wallet end-to-end needs a ruled asset on a device, which the reporter has offered) + no applicable mitigation (the PIN spend gate authorises a send the user believes is normal) + mainnet (+½). Matrix C1×A1 = HIGH; the mainnet bump keeps it HIGH, not CRITICAL (Critical is reserved for seed/key theft, unauthorised signing, RCE, or loss of most wallet value; this destroys one asset's input holding on the user's own action). Matches the founder audit plan's own High definition: "silent destruction … of DigiAssets". Confidence: confirmed by source; on-device reproduction pending.
- **Blast radius today:** only assets issued with opcode 0x03/0x04 that carry royalty/deflation/signer/vote/KYC/expiry rules (`DigiAsset.cpp:672` skips empty rules). Unknown how many mainnet holders of such assets use this wallet; the reporter's planned "Elements of War" collection would put many in phone wallets.
- **Class: A** (accepted; fix on a branch). Shipping is gated by the 2026-09-05 hold for the founder review — the plan pins snapshot 68abf333 and treats later pushes as a scoped delta, so a release is possible without moving his target; that call is the user's.
- **Internal action:** Phase 0 fail-closed gate on BOTH asset-moving paths + decoder `hasRules` + retain issuance opcode through the provenance walk + parse the proxy's `rules` object into the dead `rulesJson` column + red-before-green tests using the 5401 issuance payload as the KAT vector + strings in all 12 languages. Live verification with the reporter's throwaway ruled asset: reproduce the burn on the UNFIXED build first, then prove the refusal. Separate item: drop `DigiAssetsNetClient` from the default rotation (squatted domain).
- **Response sent:** pending user approval (draft below)
- **Closed:** open

## Draft response to the reporter
Thanks for this — and for sending it to us first. Severity: High. Confirmed from source at both ends today: the wallet's asset send (AssetManager.sendAsset → a plain v3 0x15 transfer, no rule outputs, no rules check anywhere on the path; DigiAssetRules.kt is exactly the uncalled stub you describe) and DigiAsset Core's handling (checkRulesPass → exceptionRuleFailed → every output's assets cleared, inputs spent). Your 5401 issuance decodes to version 3 / opcode 0x04 and the proxy returns its immutable 0.1 DGB royalty rule; 5383 reads count 0 on the same API, matching your 5→0.

Two things you may not have seen: (1) the same door exists in the recovery flow ("Recover funds from another wallet"), which builds the same plain transfer to move assets off an old seed, including to external destinations; (2) after such a send the wallet not only reports success but keeps counting its own change output as held, because our balance rule is "count iff the native wallet still holds the output" and the indexer's verdict never reaches it. So the sender sees a phantom, not a loss, and the recipient sees a phantom too.

Fix direction is your coarse gate, made fail-closed: refuse to send or recover any asset whose issuance carried rules (0x03/0x04), and any asset whose rules state is unknown, with a plain message; applied to both paths. For the assets this wallet can currently name (locked issuances) that is exact; for unlocked aggregatable assets Core lets a later reissuance add rules, so we will also read the current rules object from the API rather than trust the original opcode alone. We will keep a real "transfer with royalty outputs" (Brian's Phase 2 shape) as the follow-on.

Yes please on the live case. What we need from you: the txid of the 5383 send that burned (for the Core-side vector), and a fresh throwaway royalty-ruled asset issued directly to a test-phone address we will send you. We will run the unfixed build first to capture the burn (indexer count and the wallet's phantom, per-row logs), then the fixed build to prove the refusal, and both go into the report. One caveat we will be watching for: our decoder does not skip the rules block on a 0x03/0x04 issuance, so the phone may credit a directly-issued ruled asset incorrectly; if it does, that is a second finding, not a reason to stop.

Timing: the repo is under a founder security review right now, so the fix lands on a branch immediately and the release decision is separate; we will tell you the version when it ships. Credit under BUG-BOUNTY.md applies.

## Fix built (branch `fix/ruled-asset-send-gate`)

Implemented per `docs/superpowers/plans/2026-09-06-ruled-asset-transfer-gate.md`:
- `AssetTransferRuleGate` (pure, decision table tested) — NONE only for a locked issuance with opcode 1/2/5 and no proxy rules object; RULE_BOUND for opcode 3/4 or a proxy rules object; UNKNOWN otherwise.
- Decoder `hasRules`; provenance walk records `issuanceOpcode`/`issuanceLocked` (Room migration 10→11) and can be forced to the issuance for pre-upgrade rows; proxy `rules` object persisted to `rulesJson`.
- `AssetManager.sendAsset` returns `TxResult.Refused` before reading a UTXO; `ForeignAssetTransferService` leaves RULE_BOUND/UNKNOWN outpoints on the old seed with a typed `MoveRefusal`.
- Detail and send screens show `TransferRuleCard` and remove/disable Send for anything but NONE; recovery screen names refused outpoints. Strings in 13 languages.

**Residual (recorded by the final review):** an outpoint carrying MORE THAN ONE asset is outside
the gate. `UtxoEntity` keys a single `asset_id` per outpoint and the provenance walk follows
`input[0]` only, so the wallet can name at most one asset on an outpoint. A NONE verdict for the
named asset therefore permits a send that spends an outpoint which may also carry a ruled asset
the wallet cannot see — and that second asset would be cleared with the rest. Rare in practice
(it needs an aggregated or multi-asset outpoint); recorded as a follow-up, not fixed here.

Still open: the live protocol with the reporter (unfixed build first, then this build), and the release decision under the founder-review hold.

## Reply to Medina — 2026-09-07 (supersedes the draft above; sent by the user with the Note 8 SegWit receive address)

Medina — thank you for bringing this to us first, and for the precision of it. Severity: High. We confirmed every point from source on both sides the same day: the wallet builds a plain v3 transfer with no rule outputs and nothing on the path checks rules (the rules class is exactly the uncalled stub you described), and DigiAsset Core's replay clears every output and spends the inputs when a rule fails. Your 5401 issuance decodes to version 3, opcode 0x04, and the proxy returns its immutable 0.1 DGB royalty; 5383 reads count 0 on the same API, matching your 5 to 0.

Two things beyond your report, so you have the full picture. The recovery flow ("Recover funds from another wallet") builds the same plain transfer to move assets off an old seed, including to external addresses, so it had the same door. And after such a send the wallet not only reports success but keeps counting its own change output as held, because our balance rule is "count iff the native wallet still holds the output" and the indexer's verdict never reaches it. The sender sees a phantom, and the recipient's wallet credits one too.

The fix is built and reviewed, not yet released. It is your coarse gate, made fail-closed and applied on both paths: an asset may move only when the wallet has walked it to a locked issuance with opcode 1, 2 or 5 and the proxy reports no rules object. Opcode 3 or 4, or a rules object, refuses; anything the wallet cannot prove refuses too, and unlocked assets stay refused because Core lets a later reissuance add rules to them. Refusals are explained on screen in the wallet's thirteen languages and nothing is signed or broadcast; in recovery the asset simply stays where it was. The known-answer test uses the real bytes of your 5401 issuance.

Yes please on the live case; it is the evidence we want before release and it goes into the report. What we need from you:
1. The txid of the 5383 send that burned, for the Core-side vector.
2. A fresh throwaway royalty-ruled asset issued directly to this address on our test phone, with that address as the first output of the issuance: dgb1q9phqevg8r492dy3n5rma9zk0nyxwaz3xpl5v2y

We will run it twice: first on the current release, v4.0.79, which does not contain the gate, to capture the burn end to end with per-output logging (that is the one time we spend one of your assets on purpose), then on the fixed build to prove the refusal on send and on recovery.

Timing: the repo is under an independent security review right now, so the release with the gate will follow the live case as its own small version. Credit under BUG-BOUNTY.md applies. Separately, v4.0.79 went out today with an unrelated fix you may care about on the DigiScope side: scanned payment amounts were being dropped and every DGB amount was converted through floating point one satoshi short, which was breaking tip-wallet dust codes.

## Live case, part 1 of 2 — burn captured on the shipped release (2026-09-09)

Medina issued two throwaway royalty-ruled assets directly to the Note 8's SegWit receive
address `dgb1q9phqevg8r492dy3n5rma9zk0nyxwaz3xpl5v2y`, each as **vout 0** of its issuance,
so the burn capture and the refusal run each have their own holding:

| | asset | index | issuance txid | block |
|---|---|---|---|---|
| Test 004 | `LaAHVShJDdfsJcAxceouMjktdLVYyGrfuvhzj6` | 5403 | `07ff360968e14b630e4cae05a9b3551efec5875f344a93d649db667d796f6152` | 24,179,111 |
| Test 005 | `La8L1QQkZESDaRLARQELU9ijhv3q69UaTme2dB` | 5404 | `7b45d310065e68cf30d18c09cf98d5e63a22edc49cce0c9a5b84641b5f5b6a31` | 24,179,152 |

Both read off our own node (not an indexer): OP_RETURN `4441 03 04 …` = DigiAssets v3
**opcode 0x04**, 5 units, locked, with the royalty output of 0.1 DGB to
`DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK` at vout 1. The proxy returns
`{"changeable": false, "royalty": {"addresses": {"DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK": 10000000}}}`.
The gate is therefore double-covered on these: opcode 0x04 alone forces RULE_BOUND, and the
proxy rules object forces it independently.

**Core-side vector, now chain-proven end to end.** Medina supplied the 5383 burn txid, which
closes the last gap in the mechanism — previously we had Core's source and a `count: 0`, but not
the transaction between them. Issuance `8adb12ad7f4b1f979a91a53475a6a9fc5610ed7541e3fa7cbadb4bf53484f73e`
(block 24,106,262) is opcode `04` with the 0.1 DGB royalty at vout 1. The burn
`f385d004eca95a52b415a21d3e8c01c2a5431f41849f7de01c17dfb2a9fdfdc0` (block 24,106,276) spends that
holding and carries OP_RETURN `6a08 4441 03 15 00010104` — a plain v3 transfer — with outputs
`0.0001 / 0.0001 / OP_RETURN / change` and **no royalty output anywhere**. Asset 5383: 5 → 0.

### The run

Build: **v4.0.79, tag `v4.0.79` (worktree `7adb41be`), mainnet debug**, installed over the prior
40078 as an upgrade so wallet state was preserved. The tag was built specifically so the capture
names the shipped release. Verified pre-gate: `core/.../asset/rules/` does not exist at that tag.
The 78→79 diff touches this path in exactly one place — `AssetViewModel`'s **custom-fee** parsing
moving to `DgbAmount` — so with the fee left on default the send path is identical between the two.

Device state before the send: synced to tip, `cf-ledger: outstanding=0 gaveUp=0`, and both assets
credited correctly — `row 07ff360968e1:0 qty=5 … -> COUNT`, `row 7b45d310065e:0 qty=5 … -> COUNT`.

Sent **2 of the 5 units** of Test 004 to Medina's issuer address
`dgb1q6njlsdcrrqqecu497jmcx67gy63l90ll2l5jnt`. Two units rather than all five deliberately: sending
the whole holding leaves no asset-change output, and the change output is what becomes the phantom.
An external destination avoids any consolidation-exemption ambiguity.

What the wallet showed on the way — no warning at any point:
- Asset Details carries **no rules indication of any kind**; Send is enabled.
- The DGB cost preview itemises `Recipient marker 6,000 sats`, `Asset-change marker 6,000 sats`,
  `Network fee ≈61,300 sats` — **and no royalty line**.
- It states **"3 units stays in your wallet"**.
- The confirm dialog warns only "Asset transfers are irreversible."
- The spend gate correctly demanded the PIN before broadcast.

Broadcast txid **`f14051bfe7f3c2722431df3a99e400fdc10244940e5b8ac4dcab59949486b80d`**, confirmed in
block 24,180,727. Decoded on our node:

```
in  0  07ff360968e1…:0     the entire 5-unit holding
in  1  8d8a66be3cef…:0     funding
out 0  6,000 sats          dgb1q6njlsdcrrqqecu497jmcx67gy63l90ll2l5jnt   recipient, 2 units
out 1  0                   OP_RETURN 6a08 4441 03 15 00020203            v3 TRANSFER
out 2  6,000 sats          dgb1ql4rmzydpzvd0fhsy3hnqgcwwlsz9q263yfh6t6   asset change, 3 units
out 3  1.82640383 DGB      dgb1ql4rmzydpzvd0fhsy3hnqgcwwlsz9q263yfh6t6   DGB change
```

**No royalty output. Not one P2PKH output in the transaction** — the rule demanded 0.1 DGB to
`DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK` and the wallet paid nothing. Byte-for-byte the same shape as
the 5383 burn.

### Outcome — both halves of the bug, observed

**Chain: all 5 units destroyed.** The recipient received nothing; our own change address holds
nothing. Using the holders view, with 5404 as an in-query control proving the view is current and
not merely empty:

| asset | `/api/digiassets/holders/<idx>` | |
|---|---|---|
| 5383 | `{"holders":[],"total":0}` | Medina's known burn |
| **5403** | **`{"holders":[],"total":0}`** | **our send — all 5 gone** |
| 5404 | `{"holders":[{"address":"dgb1q9phq…","quantity":5}],"total":1}` | untouched control |

(The asset endpoint's `count` field still read 5 for some time afterwards while every address
holding was already gone — it is a lagging aggregate. 5383's reached 0 eventually. The holders
view is the current one, and the indexer reported itself synced to 24,180,794, well past the
burn block.)

**Wallet: phantom balance.** The wallet credits its own change output and reports three units
that no longer exist anywhere:

```
row f14051bfe7f3:2 qty=3 h=0 src=NATIVE state=1 owned=true -> COUNT
heldBalances: … LaAHVShJ=3(2u) …
```

The DigiAssets screen shows **Brasa Royalty Refusal Test 004 — 3**, with nothing to indicate
anything went wrong. This is the count-iff-native-holds rule behaving exactly as designed: the
native wallet does hold that outpoint, and the indexer's verdict never reaches it.

Net: the user asked to send 2 units, kept 3 by the wallet's own statement, and in fact lost all 5
while the app reported success and still displays 3.

### Notes for the fix

- The feared 0x04 rules-block decode garbage **did not** materialise: both issuances credited at
  the correct quantity 5. This case cannot distinguish "parsed the placement instructions" from
  "credited the whole supply to the first non-OP_RETURN output by the naive rule", since vout 0 is
  the recipient and receives the whole supply either way. The issuance-crediting gap is untested by it.
- `row f14051bfe7f3:3 qty=0 … -> COUNT` — the DGB change output is also counted as a zero-quantity
  row. Harmless to the totals here, but it is the same zero-value blind spot recorded elsewhere.

**Part 2 (refusal on the fixed build, using Test 005 / 5404) is still to run.**

## Live case, part 2 of 2 — refusal proven on the gated build (2026-09-09)

Build: local `develop` @ `86bbc37b` (the merged gate), mainnet debug, installed over the
v4.0.79 build from part 1 so the wallet state — including the phantom — carried across.

**Caveat on what this build is.** Local develop carries the gate *and* the 18 held
media-playback/debug-bypass commits, and those also touch `AssetDetailScreen` and
`AssetViewModel`. So this proves the gate's behaviour, not the shipping candidate. The
v4.0.80 candidate is the 13 gate commits cherry-picked onto `origin/develop`, and that
tree has not been built or tested yet.

### Red / green on the same screen

| | asset | issuance bytes (read off our node) | gated build |
|---|---|---|---|
| **Refuses** | Test 005 / 5404 | `4441 03 04` — v3, opcode **0x04**, royalty rules | rule card shown, **Send button removed** |
| **Permits** | DigiScope Test v4 / `La8T4Rwy…` | `6a3e 4441 01 01` — v1, opcode **0x01**, no rules block | no card, **Send present and enabled** |

The permit row is the positive control, and it matters: without it the refusal only shows the
gate blocks *something*, not that it still lets legitimate assets move. Its opcode was read
from the issuance transaction `c09f2f2d9e67a39cebd7d3d8349f81e706cc02695ac93a53920ae0e480a8b8d6`
on our own node — **not** taken from the gate's own verdict, so this is independent of the code
under test. (It also means no plain rule-free control needs to be requested from the reporter;
the wallet already held one.)

On Test 005 the wallet says, in place of the Send button:

> **This asset has transfer rules**
> It was issued with rules (such as royalties) that this wallet cannot satisfy yet. Sending it
> now would destroy it, so sending is disabled. Your asset stays safe in this wallet.

Directly comparable to part 1, where the same screen for the same class of asset offered Send
with no warning of any kind and destroyed the holding.

### Two things this run does NOT show

- **The phantom is not repaired.** The gated build still displays `Test 004 — 3`. The gate stops
  a burn from happening; it has no path to correct the accounting of one that already did. A
  wallet that burned an asset before upgrading keeps its phantom. Whether that needs its own fix
  is open.
- **The recovery path (`ForeignAssetTransferService`) was not exercised here** — only the
  send path. It is gated in the same commit and unit-tested, but it has no live case.

### Reproduction record

- Unfixed: v4.0.79 tag build → txid `f14051bf…86b80d`, block 24,180,727, asset 5403 → 0 holders.
- Fixed: develop `86bbc37b` → refusal on 5404, permit on `La8T4Rwy…`, nothing signed or broadcast.
- Device: Note 8 (SM-N950U), API 28, mainnet, synced to tip throughout.

## v4.0.80 release candidate — built, gated, device-verified (2026-09-09)

Branch `release/v4.0.80-gate` = `origin/develop` (`8c20c7cd`) + the 13 gate commits
cherry-picked + the version bump. This closes the gap left by part 2, which ran on local
develop and therefore also carried the held media commits.

**Cherry-picking surfaced a real dependency, not a mechanical conflict.** The gate's Room
migration was numbered **10→11**, which silently assumed the held media commits' 9→10
migration. On `origin/develop` the database is at version **9**, so the gate had to be
renumbered to **9→10** (`Migration_9_10.kt`, `version = 10`). Its ALTERs are guarded and
idempotent, so the renumber is safe on its own.

> **Obligation this creates — do not lose it.** When the media-playback commits land on top
> of v4.0.80, **their** migration must be renumbered **9→10 → 10→11** (schema version 11).
> Leaving them at 9→10 means a wallet that came through v4.0.80 is already at version 10, so
> the media migration never runs and its columns are silently absent. The renumber is recorded
> in the body of commit `e6f208d8` as well as here.

Two other conflicts, both resolved narrowly:
- `OnboardingHardcodedStringTest.COVERED` — the gate commit added both `TransferRuleCard.kt`
  and `AssetMediaPlayer.kt`; only the former exists here. The gate asserts
  `missing.isEmpty()`, so keeping the media entry would have failed the build rather than
  passing blind — the locale gate did its job.
- `AssetViewModel` imports — `DgbAmount` (v4.0.79's fee fix) and `CancellationException`
  (the gate's final-review wave) are both needed; kept both.

**Verification on the candidate:**
- `:core:` + `:app:` unit tests: **1,225 tests, 0 failures, 0 skipped** — including the
  renumbered `Migration_9_10Test` (4) and the gate's decision tables (`AssetTransferRuleGateTest` 9,
  `AssetSendRuleGateTest` 6).
- `scripts/check-security-cycle.sh`: versionCode 40080, last cycle 40076, delta 4 — next due 40086.
- `scripts/check-submodule-pin.sh`: pin `e1a7b82` equals the tip of core `develop` — durable.
- On the Note 8 as **40080**: Test 005 shows the rule card with **Send removed**; DigiScope Test
  v4 (opcode 0x01) still shows **Send enabled**. Same red/green as part 2, now on the shipping tree.

Not yet done: tagging, pushing, and release notes — held pending the founder review.
