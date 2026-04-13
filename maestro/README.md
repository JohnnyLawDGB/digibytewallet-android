# Maestro Flows

UI regression tests that run against a connected device or emulator.
Each `.yaml` file is an independent flow; Maestro runs them in
filename order (alphabetical) by default.

## Running

```bash
# Default suite — excludes wallet-state-dependent flows.
# Use this in CI and local pre-release checks.
maestro test --exclude-tags requires-wallet maestro/

# Everything, including flows that assume an existing wallet.
# Requires manually seeding an unlocked wallet before running.
maestro test maestro/
```

## Flows

| File | Tag | Assumes |
|------|-----|---------|
| `launch-no-crash.yaml` | — | Fresh install (clears state) |
| `create-wallet.yaml` | — | Fresh install (clears state) |
| `recover-wallet.yaml` | — | Fresh install (clears state) |
| `receive-screen.yaml` | `requires-wallet` | Unlocked wallet on home screen |
| `about-screen.yaml` | `requires-wallet` | Unlocked wallet, reachable Settings |

## Why two classes of flows

The first three are **onboarding** tests — they all start from a fresh
install (`clearState: true`) and exercise the paths that every new
user goes through. They catch most UI regressions early.

The last two are **post-setup** tests that need a wallet to already
exist. Running them as part of the default suite would require either:

1. A reusable "recover wallet through PIN to home" prelude flow that
   runs before each wallet-dependent test. Blocked on adding `testTag`
   modifiers to the Compose widgets involved
   (`app/src/main/java/io/digibyte/ui/onboarding/MnemonicInputScreen.kt`
   has 12 separate `WordInputField` composables that Maestro can't
   address reliably without explicit tags).

2. Pre-seeding wallet state on the device before invoking Maestro.
   Rejected — couples the test setup to the device lifecycle and
   loses the self-contained-flow property.

Until (1) lands, `requires-wallet` flows are tag-excluded from the
default suite and only run when someone manually sets up a wallet
first. The assertions in those files are still maintained so that
when testTag instrumentation arrives, the flows are ready to plug in.

## Conventions

- Name flows by the screen or action under test (`receive-screen`,
  `create-wallet`).
- First step is always `launchApp` with `clearState: true` for
  onboarding flows, bare `launchApp` for wallet-state flows.
- Assertions use literal UI text where possible — Compose widgets
  without `contentDescription` or `testTag` are reachable only by
  visible text.
- Tag any flow that assumes existing device state with an explicit
  `tags:` list so the default suite can exclude them.
