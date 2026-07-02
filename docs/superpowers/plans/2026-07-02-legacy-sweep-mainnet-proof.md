# Legacy Sweep — Mainnet Proof (Task 6, executed 2026-07-02)

**Result: PASS.** The app's own `buildAndSignLegacySweep` signed a sweep of a REAL
funded legacy `m/0'/0/0` ("DigiByte seed" HMAC, P2PKH) UTXO on DigiByte mainnet;
the network accepted it (`testmempoolaccept` → allowed) and it confirmed on-chain,
spending the legacy UTXO and delivering the funds to a native bech32 address —
exactly the Recover-Funds feature's real behavior.

This clears the ship-gate set in `2026-07-02-legacy-sweep-hardening-and-proof-design.md`.

## What this proves (and what it doesn't)

- PROVES: the fund-critical path — the app's legacy-sweep **derivation + build + sign**
  (the #2 amount handling and #3 wrong-key fixes) produces a **consensus-valid** tx
  that spends a real legacy UTXO and delivers real funds. Deterministic, on-chain.
- Broadcast transport: this proof broadcast the app-signed tx via the local node's
  `sendrawtransaction` (deterministic, verifiable). The app's own broadcast transport
  (`Broadcaster → publishTransaction`) is validated separately by (a) the Task-1A UAF
  fix matching the proven `publishTransactionStem` NULL/NULL pattern, and (b) live
  confirmed normal sends on the Note 8 using the identical `publishTransaction`.

## Executed values (2026-07-02)

| Item | Value |
|------|-------|
| Throwaway seed (12-word, generated on emulator) | `visa wheat park two zero typical pave write hover stay right organ` |
| Legacy funding addr (`m/0'/0/0`, P2PKH) | `DCSQB81qZkdN66Qkgo3gg5zCkbhFyxfEUy` |
| Native dest (`m/84'/20'/0'`, bech32) | `dgb1q6yz7l2uzpckdz4hwrcah7tr7gacrrpf4yve2aq` |
| Funding tx (JohnnyTest → legacy, 5 DGB) | `5243f42f006b6222bd3d17b3f394d46fdb1a877da491d87e34530cb10dfcf758` vout 1 |
| Funded UTXO | 5.00000000 DGB, script `76a914500eac5b9ebecb21cd4ab82ad48b37a1f763242688ac` |
| Sweep tx (app-signed) | `5ea992b28104272b4e64c3abf647a2e47cfdde08c8f96e95ce45d91743f1b2f1` |
| Sweep output | 4.99979600 DGB → `dgb1q6yz7l2uzpckdz4hwrcah7tr7gacrrpf4yve2aq` (fee 0.00020400) |
| Confirmed at block | 23,779,513 |
| Funding UTXO after sweep | `gettxout` → null (SPENT ✓) |

## Procedure (reproducible)

1. Run `LegacyAddressGenTest` on `emulator-5554` (dgb-test-api33) → logs a fresh
   throwaway mnemonic + its legacy funding address (tag `LegacyFund`).
2. `digibyte-cli -rpcwallet=JohnnyTest sendtoaddress <legacyAddr> 5` → funding txid;
   wait ≥1 confirmation. `getrawtransaction <txid> true` → vout index + scriptPubKey.
3. Fill `LegacySweepMainnetProofTest` constants (mnemonic, txid, vout, amount-sat,
   scriptPubKey, dest = the native address) and run it on `emulator-5554` → logs the
   app-signed sweep hex (tag `SweepProof`).
4. `digibyte-cli testmempoolaccept '["<hex>"]'` → allowed:true (dry run), then
   `digibyte-cli sendrawtransaction <hex>` → sweep txid.
5. Verify: `gettxout <fundingTxid> <vout>` → null (spent); `getrawtransaction
   <sweepTxid> true` → confirmed, output pays the native address the expected amount.

## Environment / safety
- Only `emulator-5554` (dgb-test-api33, empty snapshot) was used. The api34/api35
  DigiAssets wallets and the Note 8 were never touched. No wallet was wiped.
- The one-off `LegacySweepMainnetProofTest.kt` (contained a throwaway mnemonic +
  this run's UTXO) is deleted after use and NOT committed to the feature.
- Net funds: JohnnyTest spent ~5 DGB + fees; the swept 4.99979600 DGB rests at the
  throwaway seed's native address (recoverable via the recorded mnemonic).
