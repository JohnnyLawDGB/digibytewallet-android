# AdamantineOS Adapter Boundary Proposal

Author attribution: **DarekDGB**  
Repository context: `digibytewallet-android` fork  
Status: proposal-only adapter boundary  
Default behaviour: not wired into wallet execution; if invoked without runtime configuration, fail closed

## Purpose

This proposal adds a small optional boundary where the DigiByte Android wallet can ask AdamantineOS whether a sensitive wallet action should continue.

The wallet remains the wallet. AdamantineOS does not become a signer, key holder, network broadcaster, consensus engine, or wallet controller.

The first boundary is intentionally small:

```text
Wallet action request
    -> AdamantineOS adapter boundary
    -> AdamantineOS final policy decision
    -> wallet receives ALLOW / DENY / REQUIRE_HUMAN_CONFIRMATION
```

## What this does not change

This adapter proposal does not change:

- DigiByte consensus rules
- block validation
- mining
- supply
- SPV validation rules
- peer networking
- wallet seed handling
- private key handling
- native signing internals
- broadcast internals
- JNI key isolation boundaries

## Security model

The adapter boundary is decision-only.

It must never receive or expose:

- seed phrases
- mnemonics
- private keys
- xprv / xpub material
- PINs or passwords
- authentication tokens
- raw signed transactions
- raw unsigned transactions
- signatures
- native wallet memory

Sensitive actions may later be routed through this boundary only after maintainers choose the exact execution points.

## Default behaviour

The initial implementation is disabled/not wired into wallet execution.

If `DisabledAdamantineWalletDecisionBoundary` is invoked, it returns:

```text
DENY_ADAMANTINEOS_ADAPTER_DISABLED
```

That gives the repository a safe reviewable interface without silently allowing anything.

## AdamantineOS response model

The wallet adapter only needs a small local decision model:

```text
ALLOW
DENY
REQUIRE_HUMAN_CONFIRMATION
```

AdamantineOS may internally evaluate Shield, Q-ID, Adaptive Core, WSQK posture, replay/nonce rules, wallet policy, human gate, and AI Gateway evidence when AI is involved. The wallet does not need direct access to those internal systems.

The wallet receives only the final decision, reason ID, optional context hash, and optional proof/evidence artifacts.

## AI Gateway rule

The AI Gateway is not the main path for normal wallet actions.

Normal human wallet action:

```text
Wallet -> AdamantineOS adapter -> final policy decision
```

AI-assisted wallet action:

```text
AI flow -> Adamantine AI Gateway evidence -> AdamantineOS final policy decision
```

## Future integration points

Future maintainer-approved wiring could protect actions such as:

- send transaction
- sign message
- broadcast transaction
- wipe wallet
- recover wallet
- high-value transfer
- new recipient transfer
- AI-assisted wallet instruction

No execution path should be wired until the adapter has deterministic tests and maintainer review.

## PR scope

This proposal is adapter-boundary only.

It should be reviewed as:

- optional
- fail-closed
- no key access
- no consensus change
- no signing authority
- no network authority
- no bypass of native wallet protection
