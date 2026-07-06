# Mint, Redemption, and Transfer ship together

## Amendment (2026-07, upstream reconciliation)

Upstream shipped Transfer (send + detection + UI) alone at v3.9.0, so the trio no longer
binds Transfer — receiving and sending DigiDollar without Mint is the "read-only-plus"
variant of the permitted incremental path: funds received can always be sent onward, and
no Collateral is at stake. The gate is re-bound to the remaining pair: **Mint (#11) and
Redemption (#13) gate each other** — no release exposes Mint until Redemption works and
passes the correctness gate, because Mint without Redemption is the one-way Collateral
trap this ADR exists to prevent.

## Context

DigiDollar reaches users as three operations: Mint (lock DGB Collateral, create DigiDollar),
Transfer (spend DigiDollar to another user), and Redemption (recover the locked Collateral).
Building them incrementally would let a release exist in which a user can lock funds they cannot
recover or send. dgb-support reached the same fork and recorded the rule as its ADR-0002; this
integration inherits the reasoning, not just the habit.

## Decision

No release exposes any DigiDollar action until Mint, Transfer, and Redemption all work and pass
the correctness gate. Internal build order is unconstrained — Redemption (the only script-path
spend) is deliberately built early to de-risk the hardest transaction shape. A read-only slice
(detect and display DigiDollar received from elsewhere) may ship earlier: funds you can only
look at cannot be trapped.

## Considered options

- **Mint-first incremental.** Rejected: a wallet that can Mint but not Redeem is a one-way trap
  for user Collateral; a stablecoin you cannot send is not money.
- **Read-only first, then the action trio (permitted variant).** Display-only DigiDollar
  awareness is safe to ship alone and exercises the detection layer in the field before any
  value is at stake.
- **All three together (chosen).**

## Consequences

- "Done" for the user-facing feature means three transaction shapes, including two tapscript
  concerns (key-path Transfer spends, script-path Redemption), all fixture-verified.
- The feature flag that unlocks the DigiDollar UI is a single gate covering all three actions —
  there is no per-action rollout.
