# Over-report rejection check (legacy sweep bug #2, defense d)

## Why
The on-device fee-sanity guard (`jni_derive.c` `buildAndSignLegacySweep`)
catches gross *under*-reporting of input amounts. It cannot catch
*over*-reporting: the legacy P2PKH sighash is amount-blind, so a tx whose
claimed inputs exceed the real prevout value still signs locally. The network
is the authority here — outputs exceeding inputs are consensus-invalid
(`bad-txns-in-belowout`). This check proves the network rejects that case.

## When to run
Once, during the §6 mainnet proof
(`2026-07-02-legacy-sweep-mainnet-proof.md`), against the fresh self-funded
prevout — before the real, correctly-amounted sweep is broadcast.

## Procedure
1. Note the real self-funded prevout: `txid`, `vout`, `scriptPubKey`, and its
   true value `R` (from `digibyte-cli gettxout <txid> <vout>` on the node).
2. Build an OVER-reported signed hex with the app's own signer, inflating the
   reported amount well above `R` (e.g. 10x): call
   `NativeBridge.buildAndSignLegacySweep(...)` with `amounts = [10 * R]` for
   that single real input (chain/index = the derivation slot that owns it).
   The instrumented `LegacySweepAmountGuardTest` shows the exact call shape.
3. Assert the network rejects it:
   `scripts/overreport-rejection-check.sh <over_reported_hex>`
   Expected: `PASS: over-reported sweep rejected by network
   ("reject-reason": "bad-txns-in-belowout")`, exit 0.
4. Do NOT broadcast the over-reported tx. Proceed to the correctly-amounted
   sweep for the real proof.

## Follow-on (out of scope for the ship-gate)
Full on-device prevout verification — fetch each input's prevout over
SPV/BIP158 and compare its value against the backend's `amountSatoshi` before
signing — is the belt-and-suspenders defense that would make this network
check redundant. Tracked separately.
