# Handoff: the wallet Hub becomes one chat stream

**For a wallet session on the Linux Android machine.** Standalone: everything needed is here.
Written 2026-10-28 against `develop` at `5ae96bab2` and PR #170 at `881bc0cab`.

## Why

The DigiScope website moved the Hub to one chat room on 2026-10-18 (server migration 141,
deployed). Channels 2 to 8 and the forum are closed on the server. The wallet still shows
them, so the Hub looks broken. Mark (2026-10-28): delete the dead options and streamline the
chat. Website spec, for reference only: `digibyte-compendium/docs/superpowers/specs/2026-10-18-hub-single-chat-design.md` §6.9.

## Status

| Item | Status |
|---|---|
| Forum + Profile tabs removed; Hub = Chat + DigiRunner; sign-in, handle, logout in the Chat tab | ✅ **PR #170**, CI green, Codex review clean, mergeable into `develop`. Not merged yet. |
| Everything in "The work" below | ⏳ Not started. One follow-up PR on top of #170. |
| Release (4.1.3) | ⏳ After the follow-up PR. |
| Server cleanup of the old-wallet stubs | ⏳ Website repo, only after most users run the new wallet (see the end). |

**Step 0:** merge #170 first (merge commit), then branch the follow-up from the new `develop`.

## The work (follow-up PR on top of #170)

Paths are under `app/src/main/java/app/aroundtheblock/wallet/` (`ui/hub/…`) and
`core/src/main/java/app/aroundtheblock/wallet/core/` (`hub/…`, `digiscope/…`).

### 1. BUG, fix first: the wallet never joins the room, so live messages never arrive

The server does **not** put a socket in a room by itself; the client must send
`{"type":"join","channelId":1}` (`hub-ws.js` `handleClientMessage` → `joinChannel`).
The wallet sends `join` only from `ChatViewModel.selectChannel()`, which returns early when the
channel is already selected, and channel 1 is the default. `HubWebSocket.onOpen` sets
`CONNECTED` and sends nothing. Result: history loads over REST, but no `message`, `deleted` or
`preview` event ever reaches the wallet.
**Fix:** send `join` for channel 1 in `onOpen` (every connect and every reconnect), not only
from a UI action. Test: a fake socket sees a `join` frame after each open.

### 2. Delete the channel options

- `ChatViewModel.loadChannels()`: the **hardcoded fallback list** (General, Trading, Dev,
  Assets, Ask Enigma; ids 1 to 5) shows whenever `GET /hub/channels` is empty, for example when
  signed out. Ids 2 to 5 are closed; tapping one gets `404 CHANNEL_CLOSED`. Delete the fallback
  and `loadChannels()` altogether.
- `ChatView.kt` `ChannelChipRow` (call ~line 103, definition ~line 277): delete it.
- `selectChannel`, `leaveChannel` and `_channels`: delete them. The room id is a constant
  `CHAT_CHANNEL_ID = 1`.
- `DigiScopeClient.getChannels()` and the `Channel` model: delete them if nothing else uses
  them.
- Room cache (`cachedMessageDao`): keep it, keyed on channel 1. Rows cached for channels 2 to 5
  can be deleted in a Room migration or simply ignored. Your call; say which in the PR.

### 3. Remove in-chat tipping

`POST /hub/tip` answers **501** (`Tipping from chat isn't available yet — tip from your
DigiScope tip wallet`). Delete `ui/hub/TipBottomSheet.kt`, the `tipTarget` state and
`onTapHandle` tip wiring in `ChatView.kt` (~lines 84, 120, 179), the client method, and the
`TipBottomSheet` entries in `SpendGateWiringTest` and `SendAmountBindingWiringTest`. Narrow
the spend gate to what remains; do not loosen it.

### 4. Fix the history hang

`ChatViewModel.loadHistory()`: when the network returns nothing on the first page, it calls
`cachedMessageDao.getMessagesForChannel(...).collect { … }`. A Room Flow never completes, so
`isLoadingHistory = false` is never reached and "load more" is dead for the life of the
screen. Use `.first()` (or a one-shot DAO query), and set `isLoadingHistory = false` in a
`finally`.

### 5. Show what the server already sends

Message shape (REST `GET /hub/channels/1/messages` and WS `message`): the usual fields plus
`attachment: { url, width, height } | null` and `preview: { url, title, description,
site_name, image_url } | null`. `attachment.url` is a path (`/api/hub/media/<file>`): prefix
the API host. A WS `{type:'preview', messageId, …}` arrives a moment after a message that has
a link; patch that message in place.
- **Images:** render inline (Coil or equivalent), tap for full size. A message can be
  image-only (empty `content`); today that shows as an empty bubble.
- **Links:** make `http(s)://` text tappable; render the preview card. Only show
  `https:` preview images. Never render message text as HTML.
- **WS `{type:'deleted', id}`:** remove the message live.

### 6. Posting gate (Trust L2) and image upload

- `GET /hub/profile` returns `trust_level` and `can_post`. When `can_post` is false, show a
  short note instead of the composer (what to do next: link a quality X or Telegram account in
  DigiScope My Account to reach L2). The server is the boundary; this flag is cosmetic.
- If a post is refused, the WS sends `{type:'error', code:'TRUST_L2_REQUIRED', message}`:
  show `message` as is.
- **Upload (L2 only):** `POST /api/hub/images`, multipart, ONE part named `image`, no other
  fields; PNG/JPEG/GIF/WebP, max 8 MB; 10 uploads/hour. Returns `{ id, url, width, height }`.
  Then send the WS `message` with `attachmentId: id` (camelCase, as `hub-ws.js` reads it; text may be empty). Use the system photo
  picker (`PickVisualMedia`), no storage permission.
- **Signing:** add the `DigiScope Hub signed post v2` form with an `attachment: <id>` line
  (omitted when none), per the website spec §6.3. v1 stays for text-only posts. The server
  stores and returns `content` exactly as sent; never put the image URL into `content`.

### 7. Report

Long-press a message → Report → `POST /hub/report` with JSON `{ messageId, reason? }`
(`DigiScopeClient` already has the call at ~line 357). Success is `{reported:true}`; a deleted
message answers 400 `That message no longer exists.` Rate limit 10/hour.

### Copy and strings

- Every new string in **all 13** `values*` locales (wallet convention; #170 did the same).
- Copy rules: no em or en dashes in new strings; never call HoldFast secure, audited, private
  or safe.
- Enigma answers when a message contains `@enigma`; mention that in the empty-chat hint if you
  add one. There is no "Ask Enigma" channel any more.

## Done means

- Tests: the join-on-open test (item 1), no `Channel(` literals left in `ui/hub`, no
  `TipBottomSheet` anywhere, the history-hang case (empty network page + empty cache →
  `isLoadingHistory` false), the v2 signing payload with and without `attachment`.
- Core and app unit tests green on sideload and Play flavors; detekt clean.
- **On a device (emulator is fine), with two accounts:** A (L2) and B (L1 or L2) both in Chat.
  A sends text → it appears on B **without** a refresh. A sends an image → B sees it inline.
  A sends a link → B sees the preview card arrive. B (if L1) sees the note, not the composer.
  B reports A's message → it shows in website `/admin/hub`. Staff deletes it there → it
  disappears on both phones. Kill wifi for 30 s and restore → live messages resume (rejoin).
- No channel chips anywhere; the Hub has two tabs: Chat and DigiRunner.

## After the release (website repo, not this one)

Old wallets still call the retired endpoints, so the server keeps answering them cleanly:
`GET /hub/threads` → `{threads:[]}`, `POST /hub/threads*` → 410 `FORUM_CLOSED`,
`POST /hub/tip` → 501, closed channel → 404 `CHANNEL_CLOSED`. Once the Play console shows most
active installs on the release with this work (suggest ≥90%), a website PR removes those
stubs. **Do not delete `hub_channels` rows 2 to 8:** migration 075 re-seeds channels by name
with `INSERT OR IGNORE`, so deleted rows come back as LIVE channels on the next boot (this
happened on 2026-10-18). Remove those names from 075's seed list first, in the same PR, if the
rows must go.
