# Context

Domain language for the DigiByte Android wallet. Decisions live in `docs/adr/`; sequencing in `ROADMAP.md`.

## Language

**DigiDollar**:
DigiByte's decentralized, USD-pegged stablecoin, created by locking DGB as collateral. Amounts are
denominated in cents.
_Avoid_: stablecoin (as a proper noun), DD token, the coin

**Mint**:
To create DigiDollar by locking DGB as Collateral for a chosen Lock tier.
_Avoid_: buy, issue, create

**Collateral**:
DGB locked to back minted DigiDollar; released back to the owner on Redemption.
_Avoid_: deposit, stake, escrow

**Lock tier**:
A fixed (lock period, collateral ratio) pairing that sets how much DGB backs a Mint — ten
consensus tiers from 1 hour at 1000% down to 10 years at 200%.
_Avoid_: term, plan, option

**Transfer**:
Sending DigiDollar from one user to another. A consensus-level spend of a DigiDollar output — not
a plain DGB payment.
_Avoid_: send (for DigiDollar specifically), payment

**Redemption**:
Converting DigiDollar back into its locked DGB Collateral. Full only — a Mint's Collateral is
redeemed in its entirety, never partially. Requires only the owner's signature after the Lock
tier's timelock expires.
_Avoid_: burn, withdraw, cash-out, unlock

**Position**:
A Mint owned by this wallet whose Collateral output is still unspent: minted amount, locked
Collateral, Lock tier, unlock height.
_Avoid_: loan, vault, CDP

**Oracle**:
The on-chain price-feed network that supplies the DGB/USD price the protocol uses, in micro-USD
per DGB. The wallet reads the Oracle price; it never collects Oracle signatures.
_Avoid_: price feed (as a proper noun), aggregator

**Owner key**:
The wallet's BIP86-derived x-only public key that a Mint commits to; the only key that can
Transfer or Redeem the resulting DigiDollar.
_Avoid_: DD key, taproot key (ambiguous)

**Hop**:
A preparatory transaction moving funds from the wallet's spending balance to its own Owner-key
address so a Mint can be funded, presented to the user as part of one Mint operation.
_Avoid_: pre-fund (in UI), intermediate tx
