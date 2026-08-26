# Putting Assets inside the DigiByte wallet

**For Brian (Digistamp) · 23 August 2026**

What I want to build, what already works on your side, and the five things I need
from you to finish it.

Everything below was verified against the live site on 23 August 2026 — endpoint
shapes, status codes and all.

---

## What I'm building

I want **assets.digistamp.co to live inside the DigiByte Android wallet** — your
marketplace, collections and explorer pages housed in the app, Digi-ID login, and
mint / list / transfer that don't push the user out to a browser.

I'm not rebuilding your marketplace. Your pages render as they are; the wallet
handles the parts only a wallet can do — holding the key, signing, and
broadcasting. Where a page needs the wallet, it links to a wallet screen.

## Your login already works, unchanged

I traced this against your live site and my code. `GET /api/auth/digiid/challenge`
hands back a standard Digi-ID URI:

```json
{
  "uri": "digiid://assets.digistamp.co/api/auth/digiid/callback?x=0b65...",
  "nonce": "0b65...",
  "callbackUrl": "https://assets.digistamp.co/api/auth/digiid/callback",
  "expiresAt": "2026-08-23T12:42:29.259Z"
}
```

My wallet parses that shape exactly, validates the callback host against the
domain, signs, and POSTs `{address, uri, signature}` — which is precisely what
your callback demands. Your page picks it up on its own `/api/auth/digiid/poll`.

**So login needs nothing from you. Please just don't change that endpoint's
shape.** I can ship in-app browsing and login off what you already have.

---

## What I need

In priority order. The first one is the whole critical path.

### 1. Five read endpoints — **blocker**

Your `/api/health` already reports `digiAsset: ok`, so the data is there — it's
just consumed server-side and never exposed. Every asset read route currently
404s. I need these five under `https://assets.digistamp.co/api`:

```
GET /assets/{assetId}
    → {"assetId":"La3...","cid":"bafkrei...","issuer":"D7...",
       "count":1000,"decimals":0}

GET /assets/holdings/{address}
    → {"La3...":25,"Lb9...":1}          // flat map, assetId → quantity

GET /assets/history/{address}?limit=50
    → ["txid","txid", ...]              // bare array, newest first

GET /syncstate
    → {"count":24070312,"sync":0}       // sync: 0 = at tip; NEGATIVE = blocks behind
                                        // (-40 = 40 behind). Small POSITIVES are
                                        // states, not distances: 1 stopped,
                                        // 2 initializing, 3 rewinding, 4 optimizing.
                                        // Corrected 2026-08-26 — this doc previously
                                        // said "blocks behind", which inverts the sign
                                        // and misreads the state codes as distances.
                                        // The value passes through unmodified from
                                        // DigiAsset Core so it matches other providers.

GET /tx/raw/{txid}
    → {"hex":"0100000001..."}           // thin getrawtransaction passthrough
```

For failures return `{"error":"..."}` with any status — my client treats the
presence of an `error` key as "no data" and rotates to another provider, so I
never need specific status codes.

These shapes aren't arbitrary: they match what my wallet already parses from
another provider. **Hit them exactly and I need close to zero new client code.**

> #### Why `/tx/raw` matters even though it isn't urgent
>
> A DigiAsset *transfer* carries no metadata hash in its OP_RETURN — only an
> issuance does. So to show a name or image for an asset that arrived by transfer,
> the wallet walks back through each parent transaction to the issuance, and every
> hop needs a raw transaction lookup.
>
> Today exactly one host serves that: `api.digiscope.me/api/tx/raw`. It works, but
> it is a single point of failure for every asset name and image in the wallet, and
> its sibling asset routes have already gone dark (`/api/assets/*` 404s;
> `api.digiassets.net` answers 200 with an empty body). You have a healthy
> DigiAsset node, so you are the natural second source.
>
> Not urgent, then — but it is the endpoint whose absence would hurt most later.

### 2. What `/api/mint` takes and returns — one answer

Specifically: does it hand back a **ready unsigned transaction**, or does it
expect the client to supply inputs first?

My plan is that you build the issuance and my wallet signs it — my encoder only
does transfers today, so it can't construct an issuance itself. But the wallet
will fully decode and verify anything before signing it, and show the user what
it derived from the raw bytes rather than what the API claims. That's not
distrust of you; it's that a wallet that signs bytes it hasn't read is a wallet
with no security story at all. Knowing the exact response shape lets me build
that check properly.

### 3. An `assetlinks.json` file

At `https://assets.digistamp.co/.well-known/assetlinks.json` — currently 404.
This is what lets a digistamp link tapped anywhere on the phone open directly in
the wallet instead of the browser. I'll send you the app's release signing
fingerprint to paste in:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "io.digibyte",
    "sha256_cert_fingerprints": ["<I'll send this>"]
  }
}]
```

Must be served as `application/json` over HTTPS with no redirect.

### 4. A heads-up before certificate changes

I certificate-pin the hosts the wallet depends on. My pinning covers routine
renewals, but if you move certificate authorities or change the TLS setup without
warning, asset data goes dark in the wallet with no way for me to push a fix
quickly.

I've been bitten by exactly this before, so it's a standing ask rather than a
one-off: tell me before the chain changes.

### 5. Your agreement to the embedding itself

This isn't a technical item. Your pages would sit inside an app holding people's
money — your uptime becomes my uptime, and your design changes reach my users
without passing me. I'd rather that be something we agree to on purpose than
something I infer from your site being reachable.

---

## Where things stand

| Piece | State | Needs |
|---|---|---|
| Browse your pages in-app | **Ready** | Nothing |
| Digi-ID login | **Ready** | Nothing — works today |
| Asset transfer | **Ready** | Nothing — already shipping |
| Show asset names & images | **Blocked** | `/tx/raw`, `/assets/{id}` |
| Mint from the wallet | **Blocked** | All five, plus ask 2 |
| List for sale | **Ready** | Nothing — stays your page |
| Tap a link → opens wallet | **Blocked** | `assetlinks.json` |

I'm shipping the first three now, so you'll be able to log into your own
marketplace inside the wallet and browse it. Mint will stop short — deliberately —
because the wallet can't show a user what it's about to sign without those
endpoints. That gap is the clearest way I can show you what this needs.

## What I'm not asking for

- **No changes to your existing pages or design.** They render as they are.
- **No JavaScript bridge.** I'm not asking you to call into the wallet from page
  code, and I won't expose a way to. Pages reach the wallet by linking to a wallet
  URL, nothing more.
- **No custody or key handling on your side.** Signing and broadcasting stay in
  the wallet, over its own peer connections.
- **No atomic swaps yet.** Trustless settlement needs PSBT, which my wallet
  doesn't have. Listing stays an offer for now — worth planning for, not worth
  blocking on.

---

If your agent wants to check my working, `/api/health`,
`/api/auth/digiid/challenge` and `/api/auth/session` all respond today; the asset
read routes are the ones returning 404.

Happy to jump on a call for any of it, especially ask 2.

---

*Wallet-side design for this integration:
[`docs/superpowers/specs/2026-08-23-digistamp-in-app-integration-design.md`](../superpowers/specs/2026-08-23-digistamp-in-app-integration-design.md)*
