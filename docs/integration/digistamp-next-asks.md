# Assets ↔ wallet: where we are, and the three things left

**For Brian (Digistamp) · 24 August 2026** · reply to your `what's built` note

Everything in the first list is done on your side and verified live from here. This
is deliberately short — the previous document over-asked, and I'd rather you spend
the time on the three items at the bottom than on a long list.

---

## Verified live from the wallet

| | |
|---|---|
| Five read endpoints | all `200`, in the exact shapes | ✅ |
| `assetlinks.json` | `200 application/json`, no redirect, `[]` pending our key | ✅ |
| `/api/auth/digiid/{challenge,callback,poll}` | unchanged, as promised | ✅ |
| Sign-in from inside the app | **works end to end** | ✅ |

The fingerprints are in a separate message — there are two, because our release key
has been rotated and Android's verifier needs the whole lineage.

## Two things your responses caught in *our* code

Both would have broken the moment we pointed at you. Both fixed, with tests:

- **`cid` comes back as `ipfs://bafy…`** while our other source returns a bare CID. We
  pasted it straight into a gateway URL, producing `…/ipfs/ipfs://bafy…` — a 404 that
  surfaces as "metadata offline". Now normalised, and anything that isn't a plain CID is
  refused rather than repaired: the CID is the integrity check, so a value that could
  steer the fetch elsewhere isn't something to tidy up and use.
- **Holdings include a `"DigiByte"` key** carrying the address's DGB in satoshis. Read
  literally that's an asset named DigiByte holding 6000 units. Dropped.

You were also right about `syncstate.sync`. Nothing reads it yet, so nothing was
broken, but the doc was wrong and is fixed.

## A correction: the sign-in bug was ours

I was about to report that your poll doesn't set a session cookie. **It does.** What
actually happened is that the session arrived *minutes* late, so every run I watched
looked like a failure and the *next* app launch was quietly signed in.

Cause: while our Digi-ID confirmation is on screen, the WebView showing your page sits
detached, and Android throttles a detached WebView's timers — so the poll stalled.
Fixed on our side by reloading on return, which asks you directly instead of hoping a
throttled poll survived.

Worth knowing because **it will affect any in-app client, not just ours.** See the
optional item below.

---

## What we need next

### 1. The issuance flags, in the `/api/mint/unsigned` response

We re-derive the assetId rather than trust it, and our derivation matches yours —
`RIPEMD160(SHA256("txid:vout"))` — but the full id is:

```
payload = versionPrefix(locked, aggregation) || hash160 || {0x00, divisibility}
assetId = base58check(payload)
```

The response gives us `assetId` and `supply` but not `locked`, `aggregation` or
`divisibility`, so we can compute the hash160 and still not know which prefix you used.
Comparing your string to ours isn't verification if we had to ask you for half the
inputs.

**Please add `locked`, `aggregation` (0/1/2) and `divisibility` to the response.**
Three fields and re-derivation becomes independent.

### 2. Exactly which bytes are hashed into the metadata commitment

You wrote: fetch `ipfs://metadataCid`, hash the canonical JSON, find that hash in
`opReturnHex`. We want to do precisely that — it's the check that proves the asset
points where you say.

But "canonical JSON" needs pinning: key ordering, whitespace, unicode escaping, and
whether the hash covers the raw IPFS bytes or a re-serialisation. A one-line answer, or
a pointer at the function, is enough. If it's the raw bytes as pinned, say so and this
gets simpler for both of us.

### 3. A way to exercise mint without spending

The flow costs real DGB and pre-funds a real address, and the intent is consumed at
prepare rather than at broadcast — so a failed integration attempt burns 0.002 DGB and
an intent every time. That's fine occasionally and awkward while wiring it up.

Anything that helps: a test mode that skips the fee, a small faucet allowance on a
named account, or just "here's a funded test account, don't abuse it". If the honest
answer is "it's 0.002 DGB, just spend it", that's a fine answer too — we'd just like to
know before we start.

---

## Optional, and genuinely optional

**A way to exchange an authenticated nonce for a session**, so a client doesn't depend
on poll timing:

```
POST /api/auth/digiid/claim   {"nonce": "…"}   → sets the session cookie
```

Our reload works and we're not blocked. But polling is the fragile part of the flow on
mobile for the reason above, and a deterministic claim would make in-app sign-in
reliable by construction rather than by us reloading at the right moment. Worth it only
if it's small.

---

## Not asking for

- **Nothing on the read API.** It's right, and you've said it's a contract — that's what
  we needed.
- **No changes to `/api/mint`.** The custodial endpoint is fine as it is; we use
  `/api/mint/unsigned`.
- **Nothing on forgery.** Your fingerprint floor is the right floor.

Thanks for `/api/mint/unsigned` in particular. "The wallet signs bytes it verified" was
the part my own spec said mint would have to stop without, and you built it before
being asked twice.
