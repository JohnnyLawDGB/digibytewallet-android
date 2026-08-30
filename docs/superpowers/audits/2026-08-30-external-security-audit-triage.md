# External security audit (2026-08-30) — verified triage

An independent-model audit was run against this repo on 2026-08-30 and returned a
"hold the release" verdict with four release blockers. This is the verification of every
claim it made, against the **shipped** tree (v4.0.75, `01e6c9a5` = `origin/develop`) and
the Note 8 (SM-N950U, Android 9, v4.0.75 debug install). Method: one grounding reader per
finding, then two adversarial refuters with distinct lenses (release reachability;
existing mitigation / recorded maintainer decision), then synthesis — 34 agents, plus the
on-device probes below. Line numbers here are from the shipped tree; the audit read the
stale `v4.0.24` clone, so its own citations do not line up.

## Verdict on the audit

**One real release blocker (F1), confirmed on-device and worse than stated.** Everything
else is either an already-recorded, deliberate residual (F2, F3a), cheap unrecorded
hygiene (F3b, F5, F7), test hygiene (F9), refuted as an exploit (F4, F6), or not a
security finding (F10).

The audit's largest error was reading a v4.0.24 clone: the "raw Digi-ID URI logged"
claim was fixed and dex-verified 2026-08-26; the Digi-ID key is `m/0'/0/0`, not
`m/44'/20'/0'/0/0` (corrected 2026-08-19); "42 security tests" is now 56. Its
load-bearing exploit implication for the "shared trust zone" (F4) collapses on the
signed-message magic: `jni_wallet_sign.c:114` prepends `\x19DigiByte Signed Message:\n`
+ varint and double-SHA256s before `BRKeyCompactSign`, so a server-supplied challenge can
never be a transaction sighash.

## Device evidence (Note 8, v4.0.75)

| step | result |
|---|---|
| Wallet unlocked, same `ActivityRecord` (`e11a0a3`, task 349) | Wallet screen |
| HOME, 6 s on launcher (activity STOPPED, `onStop()` → `lockUi()`) | — |
| launcher tap (`monkey -c LAUNCHER`, same instance, no new record) | **Wallet screen, balance visible, no PIN** |
| tap Send → own address, 0.01 DGB → Review & Send | **"Confirm Transaction" dialog with live Send button, no PIN** |
| this device has no fingerprint enrolled (`dumpsys fingerprint` count 0) | `SendScreen` takes the "No biometric — proceed directly" branch |
| `am start -n` instead of launcher tap (stacks a NEW instance) | "Enter PIN" — the fresh `remember{}` routes correctly; this is why casual testing misses it |
| "View Recovery Phrase" | still PIN-gated (not exercised) |

Nothing was broadcast; the dialog was dismissed with BACK.

## Triage

| # | finding | verdict | severity | fix |
|---|---|---|---|---|
| F1 | warm-resume lock bypass | **CONFIRMED** (code + device) | **RELEASE BLOCKER** | SMALL — fixed on this branch (A); (B) needs one decision |
| F12 | other entry points open on warm relock (completeness sweep) | CONFIRMED, same root | HIGH (closes with F1-A) | node_pair_confirm added to the F1-B gate list |
| F2 | Keystore not auth-bound, biometric without CryptoObject | CONFIRMED, **recorded** (CLAUDE.md CRITICAL-1, ROADMAP Phase 2) | ACCEPTED RESIDUAL | new: hardware backing asserted but never checked; two THREAT_MODEL lines describe controls that don't exist |
| F3a | mnemonic as immutable String | CONFIRMED, partially recorded (AUDIT-SUMMARY P2) | ACCEPTED RESIDUAL | docs: CLAUDE.md:51 overclaims; `generateMnemonic` jstring + SeedViewScreen not in the P2 entry |
| F3b | recovery/passphrase input unhardened (no FLAG_SECURE, learnable IME) | CONFIRMED (PIN entry refuted — custom keypad) | MEDIUM | TRIVIAL |
| F4 | no signer firewall / Digi-ID shares trust zone | **REFUTED** as exploit | — | nothing; identity linkability already Phase 2 |
| F5 | JWT logged (`DigiScopeClient.kt:131` body.take(300)) | CONFIRMED | LOW | TRIVIAL; same class as AUDIT-LOG findings 3 and 7 |
| F6 | address pool + raw URI logged | **REFUTED** as stated | — | address loops live only in legacy String JNI entries with no production caller; URI log fixed 2026-08-26 |
| F7 | no `dataExtractionRules` | CONFIRMED, exposure overstated | LOW | SMALL; seed/PIN/DB-key blobs are Keystore-wrapped and inert off-device; the migratable value is the plaintext JWT + address/tx set |
| F9 | source-string tests; misnamed unlockSession test; KeyStoreManagerTest deletes the prod alias | CONFIRMED | INFO | SMALL; `unlockSession`/`WalletManager.unlock` are dead code |
| F10 | 12-word default | **REFUTED** as security claim | INFO | product call; 128-bit is the BIP39 baseline |

### F1 — what is open in the locked-but-showing-wallet state

With **no PIN and no biometric on any device**: DigiAsset send (`AssetSendScreen.kt:103-117`,
confirm dialog only), foreign-seed sweep to an external address (`RecoverFundsViewModel.kt:220-240`),
Hub `quickLogin` identity signing (`ProfileViewModel.kt:58`), own-node pairing
(`NodePairConfirmScreen`, no auth). Without a BIOMETRIC_STRONG credential (`BiometricAuth.kt:12-17`
requests STRONG only, no DEVICE_CREDENTIAL): DGB send (`SendScreen.kt:150`), DigiDollar send
(`:178`), in-app Digi-ID approve (`DigiIdConfirmScreen.kt:127`). The comment at
`SendScreen.kt:150` "(PIN fallback handled by system)" is false. Seed view, change PIN and wipe
remained PIN-gated (`SecuritySettingsScreen.kt:289/:414/:466`).

Also found: `WalletConfigEntity.autoLockTimeoutMs` (default 60 s, editable in Security
settings) has **no consumer** — the auto-lock setting is inert, and `THREAT_MODEL.md:15`
claims a timeout lock that does not exist.

The external `digiid://` deep link is **not** a vector on its own (standard launchMode → new
instance → start destination "unlock"); only in-app entries were open.

### F1 fix (A) — this branch

`LockGatePolicy.kt` + `LockGatePolicyTest` (6 JVM tests) and a `LaunchedEffect(walletState)`
in `AppNavigation` that navigates to `unlock` with `popUpTo(0)` when
`shouldRouteToUnlock(state, hasPin, currentRoute)` — Locked, PIN present, NavHost has a
graph, and the route is not in the onboarding/pin_setup/unlock set. The once-only start
destination is untouched, so the "double PIN prompt" it guards against cannot recur; the
lost-PIN branch (`Locked && !hasPin` → `pin_setup`) is excluded explicitly.

### Decisions still owed (none block A)

1. **F1-B** — the no-biometric branches on Send / DD send / Digi-ID approve, and the never-gated
   AssetSend / sweep / quickLogin / node-pair: gate with the in-app PIN dialog (the
   `SecuritySettingsScreen.kt:414` `verifyPin` pattern; recommended) or with BiometricPrompt
   `DEVICE_CREDENTIAL` (STRONG|DEVICE_CREDENTIAL needs API 30+; `setDeviceCredentialAllowed` on
   28/29 — the same per-API hazard class that got `setUserAuthenticationRequired` removed)?
2. **Auto-lock timeout** — implement the inactivity timer the setting and THREAT_MODEL promise,
   or delete the setting and the doc line?
3. **12 words** — keep as default and record the decision (recommended), or flip to 24?
4. **Lost-PIN branch** — "no PIN hash → set a new PIN over the on-disk seed" is root-only on
   release builds (allowBackup=false kills `adb backup`; isDebuggable=false kills `run-as`).
   Record as accepted residual, or require seed-phrase re-entry / biometric first?
5. **Release Log stripping** (`-assumenosideeffects android.util.Log`) would close the whole
   logcat-leak class but removes the sync diagnostics read from release devices. Not recommended
   without an explicit call; F5 is fixed by redaction instead.

### Follow-ups in order

1. F1-A (this branch) → Note 8 gate → patch release.
2. F1-B per decision 1; delete the false comment at `SendScreen.kt:150`.
3. F5 + F6 cleanup: `DigiScopeClient.kt:131` log code/length only; drop `$address` at
   `DigiScopeClient.kt:100` and `DigiIdManager.kt:105`; JWT → EncryptedSharedPreferences; delete
   legacy String JNI `createWallet`/`recoverWallet` (`jni_wallet.c:210/:383`, `NativeBridge.kt:29/:32`)
   and port the androidTest callers.
4. F3b: FLAG_SECURE `DisposableEffect` (copy of `SeedVerifyScreen.kt:46-55`) on
   MnemonicInputScreen / PassphraseScreen / RecoverFundsScreen (phrase mode); `KeyboardType.Password`
   without masking on the word fields; `keyboardOptions` on `PassphraseSection.kt:96/:116`;
   `semantics { password() }`. Confirm on the Note 8 stock keyboard that suggestions stop.
5. F7: `res/xml/data_extraction_rules.xml` (cloud-backup `disableIfNoEncryptionCapabilities`,
   device-transfer, exclude root/sharedpref/database/file/external) + manifest attribute +
   `ManifestSecurityTest` assertion.
6. Doc reconciliation: THREAT_MODEL.md:12 (TEE/StrongBox unverified), :15 (timeout), :16
   (enrollment invalidation — false for a non-auth-bound key, delete); CLAUDE.md:51 scope wording,
   :100 redaction claim, :55/:198 test count; AUDIT-LOG.md:201 vs CLAUDE.md:201 on CRITICAL-1;
   AUDIT-SUMMARY P2 entry gains `generateMnemonic`/SeedViewScreen; record the 12-word default.
7. F9: delete `NativeBridge.unlockSession` / `WalletManager.unlock` / JNI body; fix or delete
   `SeedIsolationTest.kt:130`; alias-inject or `Assume`-guard `KeyStoreManagerTest`.
8. F2 (Phase 2 as scheduled): `KeyInfo.isInsideSecureHardware` probe at key creation now;
   auth-binding or PIN-derived KEK per the roadmap.
