# Digistamp in-app integration — design

**Date:** 2026-08-23
**Status:** Design, approved in conversation. Not implemented.
**Goal:** house `assets.digistamp.co` inside the wallet — Digi-ID login, and mint / list /
transfer without leaving the app.

## What this is

Reuse what the site already has rather than rebuild it. The digistamp pages are shown in-app,
and every action that needs the wallet routes to a native wallet screen.

The site is further along than the wallet on this: probed live 2026-08-23, it already serves

| route | evidence |
|---|---|
| `/api/health` | `{"ok":true,"services":{"dgb":ok,"digiAsset":ok,"ipfs":ok}}` |
| `/api/auth/digiid/callback` | rejects an empty POST with `Missing required fields: address, uri, signature` |
| `/api/auth/session` | `{"authenticated":false}` |
| `/api/mint`, `/api/ipfs/upload` | exist (405/400 on GET) |

The callback wants **exactly the three fields `DigiScopeClient.login` already posts**, so
wallet-side login is plumbing, not new protocol.

**Note the domain is `.co`.** `assets.digistamp.com` does not resolve.

## Architecture: a WebView that can navigate but never sign

The pages are hosted in a WebView. **There is no JavaScript bridge — not a limited one, none.**
The only path from a page to the wallet is *navigation*: the page links to a wallet URL, a
`WebViewClient` intercepts it, and a native screen opens.

That single rule is what makes housing a third-party site next to a hot wallet defensible. With
a bridge, page JavaScript can *request a signature* and the whole app's safety rests on the
bridge's argument validation. With interception, a fully compromised page can only ask to open
a screen — the user still sees, and confirms, a native confirmation built from data the wallet
fetched and decoded itself.

Rules the WebView carries:

- **Origin lock.** Navigation off `assets.digistamp.co` opens in the system browser instead.
- `setJavaScriptEnabled(true)` (the site is Next.js and needs it) but
  **`addJavascriptInterface` is never called**, and neither is any `@JavascriptInterface`
  annotation added anywhere in the app.
- No file access, no content-URI access, no geolocation.
- Pinned: a `<domain-config><pin-set>` for the host in `network_security_config.xml`, which
  applies platform-wide **including WebView traffic** — so one pin covers both pages and API.
  The app already ships that file with `cleartextTrafficPermitted="false"`.

### Why not native Compose screens for everything

The Community Hub is fully native against a REST API, and that is the house pattern — the
serious alternative. It was rejected for this because the marketplace UI already exists and
changes on the site's schedule; a native reimplementation would be a second copy drifting from
the first. The WebView is confined to *browsing*, where it carries no signing authority.

## Trust boundary

`NativeBridge.signTransaction` today has exactly one caller — `TransactionBuilder`, feeding it
the wallet's own `createTransaction` output. The JNI does:

    parse bytes → seed_sign_transaction(g_wallet, tx, 0) → serialize

**No inspection at all.** No output check, no value cap, no destination check. Safe while the
wallet is the sole author; the most dangerous surface in the app the moment a remote party can
supply bytes.

So: **the wallet never signs what it has not independently decoded, and displays only what it
derived from the bytes — never what the site claims.** The site's JSON is a rendering hint. The
signature is authorized by the bytes.

A new `UnsignedTxInspector` sits between any external transaction and `signTransaction` and
refuses unless all hold:

| check | what it stops |
|---|---|
| every input is a UTXO this wallet owns | the wallet acting as a signing oracle |
| total leaving ≤ the amount on the confirm screen | the one number the user actually agreed to |
| every non-change output displayed; change to our own address | a sweep hidden inside a "mint" |
| OP_RETURN decodes as a DigiAsset op whose asset + quantity match the screen | a "mint" that is really a transfer of something else |
| fee within a sane band | theft dressed as a miner fee |

This needs one new native primitive: **decode arbitrary unsigned bytes** into inputs, outputs
and OP_RETURN. The existing helpers (`getTransactionOutputsForHash`,
`getTransactionInputsForHash`) only work on transactions the wallet already knows, so they
cannot serve this.

Without the inspector, "site builds, wallet signs" is custody with extra steps. With it,
digistamp can be wholly compromised and the worst outcome is a refused mint.

## The three actions are not alike

| action | who builds the transaction | trust added |
|---|---|---|
| **Transfer** | the wallet, entirely | **none** |
| **List** | nobody — authenticated REST call | none (no signature involved) |
| **Mint** | site builds, wallet signs | the inspector, above |

**Transfer needs no new protocol.** `DigiAssetEncoder` already implements TRANSFER and the
`digibyte:…?assetId=&assetAmount=` request URI landed in v4.0.42. A native transfer screen runs
on shipped code.

**Mint** is the only crossing. `DigiAssetEncoder` is TRANSFER-only — ISSUANCE and BURN are
separate opcodes it does not implement — so the wallet cannot build an issuance today. The
native mint screen calls `/api/mint` over pinned HTTPS with the session, receives an unsigned
transaction, runs the inspector, shows what it decoded, signs, and **broadcasts through the
wallet's own peers** (`publishTransaction` + the stranded-send sweep), keeping the site out of
the broadcast path.

**List** needs nothing from the wallet beyond being logged in — no signature moves an asset when
you merely offer it. So listing **stays a site page in the WebView**; it does not get a native
screen. Only mint and transfer do.

### Session propagation — how the WebView becomes logged in

Login is native (Digi-ID) but the pages need the session, and a WebView keeps its own cookie
jar. **The site establishes its own session server-side; the wallet never injects one.**

The site's login page issues a Digi-ID challenge bound to a nonce, exactly as its QR flow does
today. In-app that link is intercepted, `DigiIdManager` signs, and the wallet POSTs
`{address, uri, signature}` to `/api/auth/digiid/callback`. The server, which has been holding
that nonce, marks the session authenticated and the WebView reloads into it — the same sequence
as scanning from a desktop browser, with the tap replacing the scan.

This is deliberately *not* "native logs in, then hands a token to the WebView". Injecting
credentials into a WebView means the app holds a bearer token whose only purpose is to be handed
to page context, which is the thing worth avoiding. The wallet keeps its own token for the API
calls it makes itself (mint); the pages get theirs from the server.

### Scope boundary: listing is an intent, not a swap

A trustless "asset and payment change hands atomically or not at all" trade requires PSBT, which
does not exist in this wallet in any form. v1 delivers: mint on chain, list for sale, transfer.
Settlement is a hand-off. PSBT deserves its own design and should not be smuggled in here.

## Login

`DigiIdManager` needs no changes. It has **no domain allowlist** — `isDigiScopeDomain` only
gates the extra Hub-JWT step — so Digi-ID against digistamp works today. The wallet mirrors
`DigiScopeClient.login`: POST `{address, uri, signature}` to `/api/auth/digiid/callback`,
persist the returned session token under a `dgb_digistamp` prefs key shaped like `dgb_digiscope`.

Two independent authenticity layers stack and neither replaces the other: Android verifies the
host owns the app (App Links / `assetlinks.json`), and Digi-ID validates the callback host
against the URI domain and blocks plaintext HTTP.

The existing `digiid://` QR path stays — scanning from a desktop browser is a different
situation from tapping on a phone.

## Page links

Verified **App Links** (`https://assets.digistamp.co/…`), not a custom scheme: a custom scheme
can be claimed by any installed app, and there is no graceful fallback when the wallet is
absent. Requires `https://assets.digistamp.co/.well-known/assetlinks.json` carrying the
**release** signing certificate's SHA-256 — release signing is CI-only in this repo, so that
fingerprint comes from CI, not a local keystore.

`MainActivity.handleDigiIdIntent` (called from `onCreate` and `onNewIntent`) generalises into a
deep-link router. A shared digistamp link opens the corresponding in-app screen.

## Dependencies

1. **Five read endpoints on digistamp.co**, matching `AssetNetworkClient` — the mint confirm
   screen must resolve asset data to show what is being minted:
   `getAssetData`, `getAddressHoldings`, `getAddressHistory`, `getSyncState`, `getRawTransaction`.
   None are currently mounted (every asset read route 404s) even though `/api/health` reports
   `digiAsset: ok`. `getRawTransaction` also fixes today's **"metadata offline"**: a TRANSFER
   carries no metadata hash, so resolving it needs a walk back to the issuance tx, and both
   existing sources are dead (`api.digiscope.me/api/assets/*` → 404;
   `api.digiassets.net/v3/assetdata/*` → 200 with an empty body).
2. **Add digistamp.co to `DigiScopePins`** rather than hand-copying a fourth pin. That file
   exists because the same pin copied into three clients and fixed in one is what caused
   "metadata offline" before.
3. **Keep digiscope in the endpoint rotation.** `MultiEndpointAssetClient` already does
   per-endpoint circuit breaking; depending on one host is the state that produced the current
   outage.

## Testing

The inspector is where the tests live. All refusal tests, **written red-first** — each watched
failing against an inspector that does not yet reject it:

1. a sweep to a foreign address wearing a valid mint OP_RETURN → refused
2. an OP_RETURN whose asset or quantity disagrees with the confirm screen → refused
3. change directed to an address the wallet does not own → refused
4. an absurd fee → refused
5. **a legitimate mint → signed**

(5) carries as much weight as the rest: an inspector that refuses everything passes 1–4 and
ships nothing. Same trap as the neverbrick KAT, which encoded existing behaviour instead of
intended behaviour and cost a shipped regression.

WebView tests: navigation off-origin leaves the WebView; an intercepted wallet link opens the
native screen and passes no page-supplied value into it unchecked.

A build-time assertion that **no `@JavascriptInterface` exists anywhere in the app** — the rule
is only worth as much as its enforcement.

## Open questions

- Does `/api/auth/digiid/callback` return a bearer token in JSON, or set a cookie? A cookie
  session is awkward for a native client and would want a token variant. Not determinable
  without a valid signature.
- Whether `/api/mint` returns an unsigned transaction, or expects the client to supply inputs.
  The inspector's shape is unaffected either way.
