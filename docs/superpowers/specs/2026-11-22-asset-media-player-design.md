# DigiAsset media player: design (4.1.6)

**Date:** 2026-11-22 · **Status:** design only, nothing built · **Decision behind it:** the owner, 2026-11-22: "I think we should build a player."
**Code read at:** `origin/develop` 99e74e803 (version 4.1.5). Every `file:line` below is at that commit.
**Evidence:** the ASSET-MEDIA-RENDER investigation of 2026-11-22 (private records; measured on an API 34 emulator against the real mainnet assets), plus the measurements made for this document, which are marked "measured here".

This document replaces `2026-09-04-asset-media-playback-design.md`, which lives only on the unmerged branch `feature/asset-media-playback`. That design streamed unverified bytes from public gateways. This one does not. See question 12.

## 0. The short version

- The wallet plays a video or a song that a DigiAsset carries, on the asset detail screen, after a tap. Never in the list. Never by itself.
- The file is downloaded once, through the wallet's own pinned and Tor-aware HTTP client, into the app's private cache, and then played from that local file. The player itself has no network access at all.
- The engine is Jetpack Media3 ExoPlayer 1.11.1, one artifact, no native code. Measured cost: about 0.9 MB of dex (0.5 MB compressed).
- A file is limited to 32 MiB. Only MP4, WebM, MP3, WAV, Ogg and FLAC containers are opened, decided from the file's first bytes, not from what the metadata says.
- For today's assets the wallet cannot check the file against the asset's fingerprint (CID). It says so on screen. It never calls such a file verified.
- It works with the gateway as it is today. The gateway changes in section 7 make it better; they are not a precondition.

## 1. Scope

### 1.1 What 4.1.6 plays

| Kind | Containers | Tracks accepted | Where |
|---|---|---|---|
| Video | MP4 (`.mp4`, `.m4v`), WebM | H.264, VP8, VP9 video; AAC, Vorbis, Opus audio | Asset detail screen, and full screen from there |
| Audio | MP3, WAV, Ogg (Vorbis, Opus), FLAC, M4A (AAC in MP4) | MP3, PCM, Vorbis, Opus, FLAC, AAC | Asset detail screen |

FLAC needs a platform decoder, which Android has from 8.1 (API 27). On API 26, the project's `minSdk` (`app/build.gradle.kts:83`), a FLAC file gets the "this phone cannot play this format" message. HEVC and AV1 are left out of 4.1.6: support differs by device and neither appears in any asset found so far.

Today the app plays none of this. It has no player dependency (`gradle/libs.versions.toml:75` is Coil, the only media library) and `AssetMediaViewer` is still images only (`app/src/main/java/app/aroundtheblock/wallet/ui/asset/AssetMediaViewer.kt:39-41`).

### 1.2 Where, and what starts it

- **Only the asset detail screen** (`ui/asset/AssetDetailScreen.kt`). The list (`ui/asset/AssetListScreen.kt:232`) shows a still icon and, from the prerequisite PR, a small badge. It never fetches or plays media.
- **Tap to play.** Nothing is requested for a media file until the user taps Play: no download, no size request, no preloading. Opening the detail screen costs what it costs today.
- **No autoplay, no looping, no playlist.** One file, played once per tap.

### 1.3 Other kinds: what happens to each

Nothing below is dropped silently. Each kind gets a row on the detail screen that names it.

| Kind | 4.1.6 behaviour | Why |
|---|---|---|
| Animated GIF, animated WebP | First frame, as today, plus the label "Animated image". Animation is its own later PR (question 8). | It is now the most common moving media on the index (23 of the newest 106 assets). It is an image-loader change (`coil-gif`), not a player change, and it has its own memory risks. It should not ride in with the player. |
| SVG | Not drawn. Row: "SVG image. This wallet does not show it." | SVG can carry script and outside references. |
| PDF, HTML | Not opened. Row names the type. | Documents from strangers. HTML is a phishing page waiting to be opened. No WebView for asset media, ever (the Market rule, CLAUDE.md). |
| 3D (glTF, GLB) | Not opened. Row names the type. | No renderer, and none is planned. |
| Anything else | Row: "File (type as declared). This wallet does not open it." | |

For every unopened kind the row offers "Copy file ID" (the CID). It offers no "Open in browser" in 4.1.6 (question 9).

### 1.4 Not in 4.1.6

Background playback, a media notification, lock-screen or headset-button controls, picture-in-picture, casting, streaming, subtitles, cover art or titles read from the file, playback speed, download to shared storage, sharing, iOS.

## 2. The engine

### 2.1 Decision: Media3 ExoPlayer, one artifact

**`androidx.media3:media3-exoplayer:1.11.1`** and nothing else declared. Not `media3-ui`, not `media3-ui-compose`, not `media3-datasource-okhttp`.

| Option | Verdict | Reason |
|---|---|---|
| Platform `MediaPlayer` / `VideoView` | No | The whole file goes to the platform's native extractor. No way to refuse a track before it is decoded. Errors are two integers. Behaviour differs by vendor and by Android version, and the Note 8 is Android 9. Costs no APK bytes, which is its only advantage. |
| Media3 ExoPlayer | **Yes** | The container is parsed by Java code inside the app, which cannot corrupt memory. The wallet sees every track's codec, size and duration before anything is decoded, so it can refuse. Same behaviour on API 26 to 36. Errors are typed. |
| `media3-ui` (`PlayerView`) | No | It is an Android View with its own controls, a subtitle view, its own strings and `recyclerview`. The wallet needs its controls in its 13 languages and wants no subtitle surface. A plain `SurfaceView` in an `AndroidView` plus Compose controls is less code than taming `PlayerView`. |
| `media3-ui-compose` | No | Its POM imports `compose-bom:2026.05.00`. The project is on `2024.12.01` (`gradle/libs.versions.toml:50`). It would force a Compose upgrade inside a feature PR. |
| `media3-datasource-okhttp` | Not in 4.1.6 | The player reads a local file only (section 3). It is the right piece if streaming is added later; it depends on OkHttp 4.12.0, exactly the project's version (`gradle/libs.versions.toml:74`). |

### 2.2 Compatibility (measured here from the published 1.11.1 artifacts)

| Requirement of Media3 1.11.1 | Project | Source | OK |
|---|---|---|---|
| `minCompileSdk=36` (AAR metadata) | `compileSdk = 36` | `app/build.gradle.kts:77`, `core/build.gradle.kts:15` | yes |
| `minSdkVersion 23` | `minSdk = 26` | `app/build.gradle.kts:83` | yes |
| `minAndroidGradlePluginVersion=1.0.0` | AGP 8.10.1 | `gradle/libs.versions.toml:17` | yes |
| Kotlin: `media3-exoplayer` and what it pulls in declare no Kotlin dependency | Kotlin 2.4.20 | `gradle/libs.versions.toml:35` | yes |
| Native libraries: none. No `jni/` directory in any of the seven AARs | 16 KB page size | `app/build.gradle.kts:178-193` | nothing to align |
| Core library desugaring: not required | not enabled | | yes |

Version 1.11.1 is the newest stable release on Google Maven today (1.12.0 is in beta). The September attempt pinned 1.9.4 because the project then compiled at SDK 35; that reason is gone.

**What comes with it.** `media3-exoplayer` brings six sibling modules (`common`, `container`, `database`, `datasource`, `decoder`, `extractor`), `exifinterface` (already in the app at 1.3.7) and **Guava `33.3.1-android`**. The app has no Guava today, only the empty `listenablefuture:1.0` stub (checked with `:app:dependencies` on `mainnetPlayReleaseRuntimeClasspath`). So the review surface is Media3 plus Guava. R8 keeps only the Guava classes Media3 uses.

**A permission arrives with it.** The `media3-exoplayer` manifest declares `ACCESS_NETWORK_STATE` and `WAKE_LOCK`. The app already has the first (`app/src/main/AndroidManifest.xml:8`). It does not have `WAKE_LOCK`, and the merged manifest would gain it silently. The player never calls `setWakeMode` (there is no background playback), so the build PR removes it with `tools:node="remove"` and adds a test that the merged manifest's permission list is unchanged (question 10).

### 2.3 APK size

No native code, so the cost is the same for every ABI: it is dex only.

Measured here, outside the app: a small class that builds the player exactly as section 4 describes (six extractors, file data source, no extension renderers) was run through R8 9.1.57 (the project's R8, `gradle/libs.versions.toml:32`), release mode, `--min-api 26`, with Media3's own consumer rules and Guava on the class path.

| Build of the player | dex | compressed (as in an APK) |
|---|---|---|
| Six named extractors, as designed | 921,324 B | 521,103 B |
| ExoPlayer defaults (every extractor, default source factory) | 1,095,864 B | 623,052 B |

So expect **about 0.9 MB installed and 0.5 MB of download**, for each ABI. The investigation's earlier guess of 1.5 to 2 MB was for `exoplayer` plus `ui`.

Limits of that number, said plainly: it was not measured in the app. The probe passed `-dontwarn **`, used no app keep rules, and shares nothing with the app's existing dex. The real figure can differ by a few hundred KB either way.

**How the build PR measures it for real.** Build `:app:assembleMainnetSideloadMinifiedDebug` (the `minifiedDebug` type, `app/build.gradle.kts:164-175`) at the commit before the dependency and at the commit after. Report, for each APK: `apkanalyzer apk file-size`, `apkanalyzer apk download-size`, and `apkanalyzer dex packages` filtered to `androidx.media3` and `com.google.common`. Confirm `unzip -l` shows the same `lib/` entries before and after. The owner's release build gives the final number.

### 2.4 R8

Media3 ships consumer rules in `common`, `datasource`, `exoplayer` and `extractor`. They keep constructors of optional decoder extensions that the app does not include, and are harmless. No rule is expected in `app/proguard-rules.pro`. The build PR must show a minified build with **no new R8 warnings** and must not add a blanket `-dontwarn`. The player is constructed with direct calls, not by reflection, so that R8 removes the extractors the wallet does not name.

### 2.5 Housekeeping

One catalog key, `media3`, in `gradle/libs.versions.toml`, so the family moves together like the other lockstep families listed at the top of that file. Media3 and Guava join the per-build dependency CVE check.

## 3. The data path

### 3.1 Rule

Media bytes reach the device through **one** code path: the wallet's shared `OkHttpClient`, asking a gateway on the wallet's own list for a CID. Nothing else.

- The shared client is built once (`app/src/main/java/app/aroundtheblock/wallet/di/NetworkModule.kt:64-113`). It carries the Tor route (`:65-71`), the no-local-DNS rule under Tor (`:83-90`), the wait-for-Tor interceptor (`:125-153`) and the DigiScope certificate pins (`:101`, `core/.../network/DigiScopePins.kt:31-37`).
- The gateway list and the trusted list are `IpfsClient`'s (`core/.../ipfs/IpfsClient.kt:148-153`, `:167-169`). The player adds no gateway.
- A trusted gateway is asked on a client that follows no redirect (`IpfsClient.kt:59-64`, IPFS-NO-REDIRECT). Media uses the same client.
- A request URL is built only by `IpfsClient.gatewayUrl` (`IpfsClient.kt:102-105`), which refuses anything that is not exactly a CID.

**A URL in an asset's metadata is never fetched.** That is already the rule for images (`ui/asset/AssetImageResolver.kt:12-18`): a web URL is read only for the CID inside it, when it has the shape of an IPFS gateway URL, and is then fetched from the wallet's gateways. Media follows the same rule. A media entry that names no CID is shown as "this file is not on IPFS; this wallet does not fetch it".

**ExoPlayer makes no network request.** It is built with a `ProgressiveMediaSource.Factory` over a data source that opens exactly one path, the finished cache file, and refuses every other URI. `DefaultDataSource`, `DefaultHttpDataSource` and `DefaultMediaSourceFactory` are never constructed; a source gate test fails the build if any of those names appears under `ui/asset` or the new `core/.../media` package. So the question "did the player bypass the pins or Tor" has a structural answer: it has no socket.

### 3.2 New code: `AssetMediaFetcher` (core)

A sibling of `IpfsClient.fetch`, in `core/.../ipfs`, sharing its gateway list, its trusted list, its URL builder, its no-redirect client and its "not here" memory (`GatewayMissMemory`, `IpfsClient.kt:66-67`, `:125-140`). It differs in three ways:

1. **It writes to a file, not to memory.** `IpfsClient` holds the whole answer in a `ByteArray` under a 5 MiB cap (`IpfsClient.kt:156`, `:267`). That cap stays as it is for metadata and icons. Media gets its own cap and goes to disk.
2. **Its own time limit.** The shared client ends any call at 45 seconds (`NetworkModule.kt:111`). 32 MiB in 45 seconds needs about 6 Mbit/s, which Tor and many mobile links do not give. The fetcher derives a client with `newBuilder().callTimeout(10 minutes)`; `newBuilder()` keeps the proxy selector, DNS, interceptor, pool and pins. The 30 second read timeout stays, so a stalled download still ends. A unit test asserts the derived client's proxy selector, DNS and pinner are the shared client's own objects.
3. **Every byte goes through `BoundedBody`.** That object is "the one way the wallet reads a network answer" and a gate test enforces it (`core/.../network/BoundedBody.kt:11-22`, `NetworkSourceGateTest`). It gains `copyTo(source, declaredLength, cap, sink)`: the same two rules as `read` (`BoundedBody.kt:33-42`), writing to a file sink. A declared length over the cap is refused unread; an answer with no declared length stops one byte past the cap.

The request sends `Accept-Encoding: identity`. With that header set by the caller, OkHttp does not inflate anything, so the bytes counted are the bytes on the wire. An answer that carries a `Content-Encoding` anyway is refused. This closes the "small download that inflates to gigabytes" route.

### 3.3 Which gateway serves which CID

| CID kind | Asked of | Accepted when |
|---|---|---|
| dag-pb (`Qm…`, `bafybe…`): every media file found so far | `api.digiscope.me/api/ipfs/{cid}` only | The pinned host itself answered 200, with no redirect. **Not checked against the CID.** |
| raw codec (`bafkre…`) | all four gateways, in list order | SHA-256 of the file, computed while it is written, equals the digest in the CID. Otherwise the file is deleted and the next gateway is asked. |

Why dag-pb is DigiScope only: the three public gateways are asked with `?format=raw`, and for a dag-pb CID that returns the root block, a small index, not the file (`IpfsClient.kt:278-285`). They cannot serve a multi-block file in a form this wallet can check. So if DigiScope does not have a dag-pb media file, the wallet cannot play it. The user sees "This file is not available right now" and can try again later.

### 3.4 Download, then play. No streaming in 4.1.6.

The gateway ignores `Range` today (section 7.2: a ranged request gets 200 and the whole file). ExoPlayer over HTTP without ranges cannot seek and re-downloads on every seek back.

But the decision does not rest on that. Download-then-play is right even after the gateway honours `Range`:

- The size cap, the first-bytes check and (for raw CIDs) the hash are all settled **before** the first byte reaches a decoder. With streaming, bytes are decoded as they arrive.
- The player has no network stack (3.1). One less thing to audit.
- Seeking is instant and costs no data. Replaying costs no data.
- It behaves the same on every gateway, fixed or not.

The cost is a wait before the first frame: the whole file must arrive. Measured here, DigiScope sent 4.27 MB in 1.3 s and 7.30 MB in 1.8 s to a wired host; a phone on mobile data or Tor will be slower, which is why the UI shows progress and a Cancel button. `Range`, once honoured, is used for one thing: resuming a broken download (7.3, item 2).

### 3.5 Integrity: what is verified and what is not

This is the part that must not be blurred.

**The project's open question.** A dag-pb CID is the hash of a UnixFS tree, not of the file's bytes. To check a file against it, the wallet must fetch the tree's blocks, hash each one, and rebuild the file from them. The wallet has no code for that. Today it accepts dag-pb content from DigiScope on that server's pinned certificate (`IpfsClient.kt:158-166`, `:286-293`). The code comment says it exactly: "That is a server-identity check, not a content check: the pinned server itself is still trusted for these bytes." The project tracks this as open. This design does not close it.

**What the player verifies:**

| Check | dag-pb CID (all known media) | raw CID |
|---|---|---|
| The bytes came from the pinned DigiScope host, over TLS, with no redirect | yes | n/a (any gateway) |
| The CID asked for is the one the asset's metadata names | yes | yes |
| The metadata document itself matches the CID in the issuance | as today: verified for a raw CID, trusted to DigiScope for a dag-pb CID (`IpfsClient.kt:286-293`) | same |
| The file's bytes hash to the CID | **no** | **yes** |
| Size under the cap, container on the allow-list | yes | yes |

**What it cannot do:** tell whether DigiScope, or anyone who controls that server, sent a different file than the issuer published. A swapped video moves no funds and changes no balance. It can mislead about what the asset is.

**What the screen says.** One line under the player, always visible while a file is loaded:

- raw CID, hash matched: "Checked against the asset's file ID."
- dag-pb: "Served by DigiScope. This wallet cannot check this file against the asset's file ID."

In code, a cached file carries one of two states, `CONTENT_VERIFIED` or `SERVER_TRUSTED`, decided by the fetcher and stored beside the file. No type, log line or string may call a `SERVER_TRUSTED` file verified. (The existing `IpfsFetcher` comment that says bytes are "already authenticated", `ui/asset/IpfsFetcher.kt:22-23`, is wrong for dag-pb images in the same way; correcting it belongs to the prerequisite PR.)

**The real fix, later.** Verified dag-pb needs either a trustless CAR response from the gateway (`?format=car`) or raw block fetches, plus a UnixFS reader in the wallet. That would also let the public gateways serve media. The cache layout here (a finished file plus a state) does not get in its way. Question 6.

## 4. Limits against hostile files

Anyone can send an asset to an address. The file is a stranger's input, and a tap is the only consent.

### 4.1 The gates, in the order they run

| # | Gate | Value | On failure |
|---|---|---|---|
| 1 | Entry is a CID, on a kind this wallet plays | section 8 | Row shows the kind; no Play button |
| 2 | Declared size (from `HEAD`, or from the `GET` headers) | ≤ 33,554,432 B (32 MiB) | "This file is too large for the wallet to play (N MB; the limit is 32 MB)." Nothing downloaded. |
| 3 | Actual size while downloading | stop at cap + 1 byte | Same message. Partial file deleted. |
| 4 | Download progress | 30 s read timeout; 10 min whole call | "The download stopped. Try again." |
| 5 | First bytes | allow-list below | "This is not a video or audio file this wallet can play." File deleted. |
| 6 | Hash, raw CIDs only | SHA-256 equals CID digest | Next gateway, else "not available". File deleted. |
| 7 | Prepare | player reaches READY within 10 s of opening the local file | "This file could not be opened." Player released. |
| 8 | Tracks | allow-listed codecs only; video ≤ 1920×1080 (either way up), ≤ 60 fps; at most one video and one audio track are selected | "This phone cannot play this format." or "This video is larger than the wallet plays (W×H)." |
| 9 | Duration | ≤ 30 minutes, when the file states one | "This file is longer than the wallet plays." |
| 10 | While playing | no progress for 5 s on a local file; any `PlaybackException`; any decoder error | Stop, release the player, show the plain message for that error |

**Why 32 MiB.** The largest media files found so far are 9.08 MB (a GIF), 7.30 MB and 6.91 MB (songs); the one video is 2.30 MB for 10 seconds of 1080p. 32 MiB is about 3.5 times the largest known file. It holds about 2.4 minutes of video at that bitrate, or about 35 minutes of 128 kbit/s audio. It downloads in about half a minute at 1 MB/s. It never sits in memory: it goes to disk, and ExoPlayer reads it through a small buffer. Three of them fit in the cache (5.4). A larger cap buys longer video, at the price of longer waits before the first frame and more mobile data per tap. The cap is one constant; raising it later is a one-line change with its own review. Question 2.

**First-bytes allow-list.** Read from the finished file, before a player exists:

| Container | Bytes |
|---|---|
| MP4 / M4A | `ftyp` at offset 4 |
| WebM | `1A 45 DF A3` at offset 0 |
| MP3 | `ID3` at offset 0, or an MPEG audio frame sync |
| WAV | `RIFF` at 0 and `WAVE` at 8 |
| Ogg | `OggS` at offset 0 |
| FLAC | `fLaC` at offset 0 |

The metadata's `mimeType` only chooses the label shown before the tap. It decides nothing about how the file is handled. Text that starts with `#EXTM3U` or `<` fails this gate, so a playlist or an HTML page named `.mp4` never reaches the player.

### 4.2 How the player is built, and why each setting

| Setting | Effect |
|---|---|
| `ProgressiveMediaSource` with six extractors, named one by one: MP4, Matroska (WebM), MP3, WAV, Ogg, FLAC | No HLS, DASH, SmoothStreaming or RTSP code is present, so no playlist or manifest can be followed. No MPEG-TS, FLV, AMR, AVI or other parser is reachable. |
| Data source opens one file path only | Nothing in a file can make the player open a URL, another file or a content provider. MP4 files can carry "data reference" entries that point elsewhere; they have nowhere to go. |
| `SubtitleParser.Factory.UNSUPPORTED`; text, image and metadata track types disabled in the track selector | No subtitle is parsed or drawn. No ID3 tag, cover picture, title or comment from the file is shown. The only text on screen is the wallet's own. |
| `DefaultRenderersFactory` with extension renderers off; no decoder extension in the build | Every decoder is the platform's `MediaCodec`. The wallet bundles no decoder: no FFmpeg, libvpx, dav1d, Opus or FLAC library. |
| Decoder fallback on | If the first platform decoder for a format fails to start, the next platform decoder is tried. Never an in-app decoder. |
| `setMaxVideoSize(1920, 1080)`, never exceed | A larger track is not selected; gate 8 turns that into a message. |
| No `MediaSession`, no wake mode, no foreground service | No notification, no lock-screen or headset control, no background playback. |
| Audio focus handled by the player; pause when headphones are unplugged | Ordinary good manners; nothing else on the phone is affected after playback ends. |

A file cannot run script: there is no WebView, no JavaScript engine and no bridge in this path, and `scripts/check-no-js-bridge.sh` keeps it so. The build PR adds the new files to what that script scans.

### 4.3 Decoders are attack surface: what the wallet can and cannot do

Said plainly.

- **Parsing the container** happens in the wallet's process, in Java. A malformed file can cause an exception or a large allocation. It cannot overwrite memory. ExoPlayer's loader reports these as playback errors; the hostile-file corpus (section 9.4) is there to prove that on this build, including for an out-of-memory error.
- **Decoding the video and audio** happens in the platform's codec processes, not in the wallet's process. That is where a memory-corruption bug would be, in code the wallet does not ship and cannot patch. A crash there comes back as a decoder error (gate 10). An exploit there runs in the codec sandbox, which holds no seed and no wallet files.
- **What the wallet cannot do:** fix a phone's codecs. On Android 10 and later, Google updates the software codecs through system updates. On Android 9, which is the Note 8, they stopped at the phone's last patch. The wallet's defences there are the ones above: nothing is decoded without a tap, only six containers and a short codec list are reachable, and the sizes are capped. Question 11 asks whether that is enough for Android 9.
- **In-process, not isolated.** The player runs in the app's process. A separate `:media` process would keep even the Java parsing away from the wallet, at the cost of a second process without the wallet's dependency graph, with cross-process controls. Not proposed for 4.1.6. Question 14.

### 4.4 Memory pressure

- One player at a time. It is created on tap and released on stop, error, leaving the screen, lock and `onTrimMemory` at `TRIM_MEMORY_RUNNING_CRITICAL` or worse.
- The download holds an 8 KiB buffer, not the file.
- ExoPlayer's load buffer is capped (a `DefaultLoadControl` with a small byte target, about 16 MB) since the source is a local file and needs no deep buffer.
- `OutOfMemoryError` on the playback path ends playback with "This file could not be opened", never the app.
- The DigiRunner lesson applies: do not allocate a player while a fresh full sync is running low on heap. If `Runtime` free heap is under a floor when Play is tapped, say "Not enough memory to play this right now" rather than try.

## 5. Privacy and data use

### 5.1 What leaves the device, and when

Before a tap: nothing new. After a tap: one `HEAD` and one `GET` for the media CID, to `api.digiscope.me` (or, for a raw CID, possibly a public gateway). The request carries the CID, the device's IP address (or a Tor exit's), and the same headers the wallet's other gateway requests carry. No cookie, no referrer, no wallet identifier.

**What the gateway learns:** that this IP address asked for this file, so very likely holds or was sent this asset, and that its user tapped Play. DigiScope already receives the metadata and icon requests for the same asset from the same address, so the new facts are the tap and the file. This still changes what leaves the device, which makes it the owner's decision (section 9.2).

### 5.2 Tor

The player has no route of its own. It uses the shared client, so:

- **Tor on and connected:** the download goes through Tor (`NetworkModule.kt:67`). Before it starts, a notice with the size: "Over Tor this download (N MB) can take several minutes." Continue or Cancel.
- **Tor on and not connected:** the request waits up to 30 s for Tor, then fails with `TorNotReadyException` (`NetworkModule.kt:136-140`). The player shows "Waiting for Tor. Nothing was sent." **It never falls back to a direct connection.** The only direct route with Tor on is the app-wide clearnet fallback that the sync service announces with its own banner (`core/.../tor/TorRoute.kt:30-35`); the player neither triggers nor announces it, and simply follows whatever route the app is on.
- **Tor off:** direct.
- A route change during a download cancels it. The partial file is deleted.

DigiScope rate-limits by IP address (section 7.2). Over Tor many users share an exit, so a 429 is possible; the player shows "The server is busy. Try again in a few minutes."

### 5.3 Metered networks

On tap, if `ConnectivityManager.isActiveNetworkMetered` is true, the wallet asks first, with the size from the `HEAD`: "Play using mobile data? This file is N MB." If the size is not known: "…up to 32 MB." Always asked on a metered network, whatever the size (question 13). On an unmetered network with Tor off, the download starts at once with a progress bar and Cancel.

No preloading of any kind. The list never fetches media. The detail screen fetches media only after the tap.

### 5.4 The cache

| Property | Decision |
|---|---|
| Place | `context.cacheDir`, app-private. Never shared storage, never the gallery, never `MediaStore`. Android excludes the cache directory from backups. |
| Directory name | `asset_media` + the wallet's storage suffix, built by `storageSuffix(scope, StoreScope.WALLET_AND_NETWORK)` (`core/.../wallets/WalletScope.kt:53-62`). The legacy wallet on mainnet: `asset_media`. Wallet `<id>` on testnet: `asset_media_<id>_testnet`. |
| Files | `<cid>.media` (finished and accepted), `<cid>.state` (one of the two states in 3.5, plus the sniffed container), `<cid>.part` (download in progress). A `.part` is renamed only after every gate up to the hash has passed. |
| Limit | 96 MiB per directory. Before a download, least recently played files are deleted until the new file fits. |
| Per wallet | One directory per wallet and network. A wallet never reads another wallet's directory. The same CID held by two wallets is stored twice; that is the price of removal being simple and complete. |
| Stale parts | Every `.part` is deleted at app start and when a download ends for any reason. |
| The system clears the cache | Expected. A missing file means "download again on the next tap". |

**Wipe and wallet removal.** `WalletStores` is "the one list of per-wallet stores" (`core/.../wallets/WalletStores.kt:3`). It gains:

```
/** Directory bases in cacheDir, per wallet and network; each is deleted with everything in it. */
val WALLET_AND_NETWORK_CACHE_DIRS: List<String> = listOf("asset_media")
```

`WalletStoreEraser.eraseStores` (`core/.../wallets/WalletStoreEraser.kt:101-111`) deletes those directories next to the files it already deletes (`:204-209`), and `otherStoresLeft` reports one that survives, so a wipe that leaves media behind is not a verified wipe. Removing one wallet erases that wallet's directory only. The full wipe erases every wallet's, on both networks. The names are reserved in the list in the first PR that can write them (PR 2), with a test that an unknown directory starting `asset_media` is also removed by the full wipe.

**At rest.** Cached files are plain files named by CID. Someone who can read the app's private storage can see which media a wallet has played. The wallet database is encrypted; these files are not. Question 4 asks whether to keep the cache between sessions at all.

### 5.5 Screenshots

Unchanged. The asset detail screen does not set `FLAG_SECURE` today (it is held only by the sensitive screens, through `ui/components/SecureWindow.kt`), and the player does not add or remove it. The full-screen video is a dialog, and so a window of its own (`SecureWindow.kt:46`); it gets no flag either. The player's surface is an ordinary, non-secure `SurfaceView`.

## 6. The screen

### 6.1 Detail view

Under the asset's header, one **media card** per playable entry (at most four are shown; section 8).

Before the tap:

- **Poster.** The asset's picture, loaded the way it is today (`ui/asset/AssetIcon.kt:32-67`). For an asset with no picture: a plain tile with a film or note glyph. No frame is pulled from the video and no cover is read from the song, because both need the file, and nothing is fetched before the tap.
- **Type.** "Video · MP4" or "Audio · MP3", from the declared type, shown as declared.
- **Size.** Shown as soon as it is known: at once when the file is already in the cache, otherwise after the tap, in the confirmation or the progress line. Asking for it earlier would be a request before the tap.
- **A Play button**, at least 48 dp.

After the tap, in place, the card becomes: progress with Cancel while downloading; then the player.

- **Video:** the picture (16:9 box, letterboxed, never cropped), play/pause, a scrubber with elapsed and total time, mute, full screen.
- **Audio:** the poster stays; play/pause, scrubber, times, mute.
- **The integrity line** from 3.5, always visible under the controls.
- **Full screen (video only):** a dialog built like `AssetMediaViewer` (`ui/asset/AssetMediaViewer.kt:50-55`): black, close button, back dismisses. The same player moves to it; playback does not restart. Controls hide after 3 s while playing and return on touch.

The screen stays on while a **video** is playing (`keepScreenOn` on the surface), and only then. Not for audio, not while paused.

### 6.2 What it does not do, on purpose

- **No background playback.** Audio plays while the detail screen of that asset is in front. Leaving the screen stops it.
- **No media notification, no lock-screen controls, no headset buttons.** There is no `MediaSession`.
- **No picture-in-picture, no casting, no download or share button.**

### 6.3 Lock and lifecycle

- **App to background.** The activity locks the wallet in `onStop` (`MainActivity.kt:442-462`). The player stops and is released on `ON_STOP`, directly, not by asking the dialog to close: Compose does not recompose while the app is stopped, so a dismissal alone would leave audio playing. (The September branch found this the hard way.)
- **Auto-lock.** The idle timer (`MainActivity.kt:228-245`) can lock while the app is in front. The player observes `walletState`; when it is no longer `Unlocked`, it stops and releases. After unlocking, the card is back in its "before the tap" state; the cached file means Play is instant.
- **Playback does not count as activity.** Watching a video does not reset the idle timer, so a long file can be cut off by the auto-lock. That is the safe default and it is noticeable; question 7.
- **Leaving the detail screen, switching wallets, wipe:** stop and release. A wipe also deletes the file (5.4).
- **Rotation:** playback continues; the position is kept.
- **A phone call or another app taking audio focus:** pause. It does not resume by itself.

### 6.4 Errors, in plain words

Each is one sentence, with Try again where trying again can help.

| Situation | Text (English) |
|---|---|
| Not on the gateway | This file is not available right now. |
| Too large | This file is too large for the wallet to play (N MB; the limit is 32 MB). |
| Download broke | The download stopped. Try again. |
| Tor not connected | Waiting for Tor. Nothing was sent. |
| Server busy (429) | The server is busy. Try again in a few minutes. |
| Not media | This is not a video or audio file this wallet can play. |
| Codec not on this phone | This phone cannot play this format. |
| Too big a picture, or too long | This video is larger than the wallet plays. / This file is longer than the wallet plays. |
| Would not open, or broke while playing | This file could not be played. It may be damaged. |
| Low memory | Not enough memory to play this right now. |
| No CID | This file is not on IPFS. This wallet does not fetch it. |

Logs name the true cause. In particular an over-cap file is logged as over the cap, not as "possible gateway tampering" (the wrong line today is `ui/asset/IpfsFetcher.kt:42-45`). Anything from the file or the metadata reaches a log only through `LogSafe.text`.

### 6.5 Accessibility

- Every control has a content description: Play, Pause, Mute, Unmute, Full screen, Close, Cancel download.
- The scrubber is a real slider with a state description in words ("1 minute 5 seconds of 3 minutes 6 seconds") and works with TalkBack's volume-key and swipe adjustments.
- Download progress is a progress semantic with a percentage when the size is known.
- Errors are a polite live region, so TalkBack reads them once.
- 48 dp targets. No meaning carried by colour alone. Text scales with the system font size.
- Full-screen controls do not auto-hide while TalkBack is on.

### 6.6 Strings

About 35 new keys in `app/src/main/res/values*/strings_wallet.xml`, in all 13 languages of `AppLocale.SUPPORTED` (`core/.../locale/AppLocale.kt:50`), in the same commit as the UI, with the Android qualifiers (`values-b+pt+BR`, `values-b+fil`). `LocaleResourceParityTest` covers missing keys. `ui/asset/AssetDetailScreen.kt` is already in `OnboardingHardcodedStringTest.COVERED` (`app/src/test/.../ui/locale/OnboardingHardcodedStringTest.kt:65`); the new player file is added to it, and so is `AssetMediaViewer.kt`, whose "Close" and "Asset artwork, full screen" are still hardcoded English (`AssetMediaViewer.kt:70`, `:83`). Durations and sizes are formatted with the locale's own number rules.

## 7. Gateway hand-off: what `api.digiscope.me/api/ipfs` should change

*This section is written to be read alone, by the team that runs the server.*

### 7.1 Why you are getting this

The HoldFast wallet (Android, version 4.1.6) will play the video or song attached to a DigiAsset. It fetches the file from `https://api.digiscope.me/api/ipfs/{cid}`, where `{cid}` is the file's IPFS CID taken from the asset's metadata. It downloads the whole file, up to 32 MiB (33,554,432 bytes), then plays it from disk. The connection is certificate-pinned and may arrive through Tor.

For these files your server is the only source the wallet can use. They are multi-block (dag-pb) CIDs, and the wallet cannot yet verify such a file from a public gateway. So if your server does not have a file, the wallet's user cannot play it.

The player works against the server as it is today. The changes below remove the rough edges. They are listed most useful first.

### 7.2 What the server does today (measured 2026-11-22, about 17:00 UTC, plain HTTPS from one host)

| Request | Answer |
|---|---|
| `GET /api/ipfs/bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4` (an MP3, asset 5364) | `200`, `content-type: application/octet-stream`, `content-length: 4265952`, `cache-control: public, max-age=31536000, immutable`, a weak `etag`, 1.3 s |
| The same with `Range: bytes=0-1023` | `200`, the whole 4,265,952 bytes. No `accept-ranges`, no `content-range`. The same over HTTP/1.1 with `Range: bytes=1000000-`. |
| `HEAD` of the same | `200` with the same `content-type` and `content-length` |
| The same with `Accept-Encoding: gzip` | not compressed (good) |
| `GET` of the MP4 of asset 5376, `bafybeiedl6y2tkbftdlxbmetyr3dn64arfyq3inlbieima2ungs2mxgga4` | `200`, 2,299,893 bytes, `application/octet-stream`. **Earlier the same day this was `404`.** |
| `GET` of the MP3 of asset 5252, `QmQANGgTo4PvDP9oYiCRhwn3n8QfwrbHWAMphJ6tYBzSt2` | `200`, 5,059,451 bytes, `application/octet-stream`. **Earlier the same day this was `404`.** |
| `GET` of a well-formed CID nobody has | `404`, `{"error":"cid not pinned locally"}`, after 2.2 s |
| `GET /api/ipfs/notacid` | `400`, `{"error":"invalid cid format"}` |
| Rate limit headers | `ratelimit-policy: 3000;w=900` on `/api/ipfs`; `500;w=900` on `/api/digiassets/ipfs` |
| `GET /api/digiassets/ipfs/{cid}` (a route the wallet does not use) | Serves the same files; also ignores `Range`; also `application/octet-stream` |

From the investigation earlier that day: the audio files of assets 5556 and 5554 were `404`, and of 23 newer assets whose picture is an animated GIF, 13 GIFs were `404`. Some answers already carry a real type (a GIF came back as `image/gif`), so the type is right for some files and not for others.

### 7.3 What we ask for

1. **Have the files.** Pin every file that an indexed asset's metadata names, not only the metadata document and the icon: each `urls[]` entry and the `image`, `icon`, `animation_url`, `video` and `audio` values that are CIDs. Do it when an asset is indexed, and once for the roughly 780 assets already indexed. Please tell us the rule in one sentence, including any size limit above which you do not pin. Two files went from `404` to `200` within a day with no request from us; we would like to know why, so the wallet can tell "not yet" from "never".

2. **Honour `Range`.** Answer a single-range request with `206 Partial Content`, `Content-Range: bytes a-b/total` and only those bytes. Send `Accept-Ranges: bytes` on every `200` and `HEAD`. Answer an unsatisfiable range with `416` and `Content-Range: bytes */total`. Multi-range requests may be refused or answered whole. Do not answer a ranged request with `200` and a partial body, ever: the wallet would take it for the whole file. The wallet will use this to resume a broken download; without it, a download that breaks at 30 of 32 MB starts again from zero.

3. **Send the real `Content-Type`.** Detect it from the file's first bytes, not from the asset's metadata, which the issuer writes. At least: `video/mp4`, `video/webm`, `audio/mpeg`, `audio/wav`, `audio/ogg`, `audio/flac`, `image/png`, `image/jpeg`, `image/gif`, `image/webp`. Keep `application/octet-stream` for anything else, and keep `X-Content-Type-Options: nosniff`. Never serve `text/html`, `image/svg+xml` or a script type from this route as such: send those as `application/octet-stream` with `Content-Disposition: attachment`. The wallet does not rely on your type (it checks the bytes itself), but browsers and other clients of this route do.

4. **Always send `Content-Length`,** on `GET`, `HEAD` and `206`, equal to the bytes that follow. No chunked answers without a length on this route, and no `Content-Encoding` (media does not compress, and the wallet refuses an encoded answer). The wallet shows this number to the user before spending their mobile data, and refuses a file whose declared length is over its limit without downloading it.

5. **Keep the `404` and `400` meanings, and keep them fast.** `404` means "this server does not have this CID now". `400` means "this is not a CID". The wallet remembers either for ten minutes, growing to an hour, and does not ask again inside that time. Please do not answer a missing file with `200` and an error page, with a redirect, or with `5xx`. A `404` that takes 2 s is fine; one that waits on a network lookup for 30 s is not. Use `429` with `Retry-After` for rate limiting and `503` when the node is down, so the wallet does not record those as "not here".

6. **Caching headers: keep what you have.** `Cache-Control: public, max-age=31536000, immutable` is correct for content named by its hash. Please make the `ETag` strong and stable (the CID in quotes is ideal) and do not put `immutable` or a long `max-age` on a `404`.

7. **Rate limits.** Today's limit is 3000 requests per 15 minutes per IP address on this route. One play is two requests (`HEAD`, then `GET`), so the count is not a problem for a direct user. Two things to check: users on Tor share an exit address, so please do not lower this limit, and consider counting bytes rather than requests if load becomes a concern; and if you add a size limit, make it at least 32 MiB and tell us, because a smaller one silently makes files unplayable.

8. **No redirects, ever, on this route.** The wallet does not follow a redirect from a pinned host. A `301` or `302` is a failed fetch.

9. **Later, not for 4.1.6: a verifiable form.** If the route could also answer `?format=car` (or `Accept: application/vnd.ipld.car`) with the file's blocks, the wallet could one day check a file against its CID itself, and would no longer have to take your server's word for it. We would like to know whether your node can do this.

### 7.4 If none of this is done

The player still works. It downloads the whole file with a plain `GET`, checks the size from `Content-Length` (present today), ignores your `Content-Type`, and plays from disk. What the user loses: a file your server does not have cannot be played; a broken download starts over; and a download cannot be resumed after the app was in the background. Nothing in the wallet depends on a date from you.

### 7.5 How to check your side

```
C=bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4
curl -sI https://api.digiscope.me/api/ipfs/$C                       # 200, audio/mpeg, Content-Length: 4265952, Accept-Ranges: bytes
curl -s -o /dev/null -D - -H 'Range: bytes=0-1023' https://api.digiscope.me/api/ipfs/$C
                                                                    # 206, Content-Range: bytes 0-1023/4265952, Content-Length: 1024
curl -s -o /dev/null -D - -H 'Range: bytes=9999999-' https://api.digiscope.me/api/ipfs/$C
                                                                    # 416, Content-Range: bytes */4265952
```

Questions: open an issue on the wallet repository (`JohnnyLawDGB/digibytewallet-android`).

## 8. What the player expects from the prerequisite PR (ASSET-MEDIA-FIXES)

That PR is being built separately. It fixes what is wrong today whether or not a player exists: the parser picks the picture by entry name and never reads `mimeType` (`core/.../ipfs/AssetMetadataService.kt:362-380`), so a song becomes the "image"; every other entry is dropped; an undecodable file is downloaded again on every draw; and the hero opens an empty viewer whenever the string is a CID (`ui/asset/AssetDetailScreen.kt:154-163`).

The player needs this model from it. Names can differ; the properties cannot.

```kotlin
enum class AssetMediaKind { IMAGE, VIDEO, AUDIO, OTHER }

data class AssetMediaEntry(
    val kind: AssetMediaKind,      // from the declared MIME type first, the name's extension second
    val cid: String,               // already passed through IpfsClient.normalizeCid; never a URL
    val declaredMimeType: String?, // the issuer's word, display-safe, used for a label only
    val name: String?,             // display-safe, short
)

data class AssetMetadata(
    …,
    val imageUrl: String?,              // only ever an IMAGE entry, or one of the legacy untyped keys
    val media: List<AssetMediaEntry>,   // everything else the document names, in document order
)
```

1. **Classify by MIME type first.** An entry with `mimeType` `audio/*` or `video/*` is never the picture, whatever its name is. Two entries both named `icon`, one `image/jpeg` and one `audio/mpeg` (asset 5252), give one picture and one audio entry.
2. **Keep the media entries.** Bounded: at most 8 entries, each field length-capped, sanitised like the other text (`AssetMetadataService.sanitize`). Stored in one new nullable column on `asset_metadata` (`core/.../db/entity/AssetMetadataEntity.kt:6-19`), as JSON text. That is schema 12 → 13 (`core/.../db/WalletDatabase.kt:16-23`), with `MIGRATION_12_13` registered, its exported schema committed and a migration test. The player PRs then need **no** schema change.
3. **Only CIDs.** An entry's value becomes a `cid` by the same rule the image resolver uses (`ui/asset/AssetImageResolver.kt:42-62`): `ipfs://`, `/ipfs/`, a bare CID, or a gateway-shaped URL read for its CID. Anything else is kept with `kind = OTHER` and no `cid`, so the screen can name it without fetching it. An entry with a path after the CID is not playable in 4.1.6: the gateway URL takes a CID only (`IpfsClient.kt:102-105`).
4. **No audio or video as the picture.** `imageUrl` is null for an audio-only asset, so no image request is made for an MP3 and the letter tile is not tappable.
5. **No repeated download** of a file that did not decode, and **no empty viewer**: the hero is tappable when a picture loaded, not when a string parsed.
6. **Rows written before this PR** have no media column value. They are refreshed by the normal metadata path; until then the asset shows no media card. The PR should say how long that takes, or re-parse on upgrade.

The player shows a card for the first four entries of kind `VIDEO` or `AUDIO` that have a `cid`. It reads nothing else from the metadata.

## 9. Delivery

### 9.1 PRs, in order

| # | PR | What | Depends on |
|---|---|---|---|
| 0 | ASSET-MEDIA-FIXES (separate, in progress) | Section 8. Bug fixes that ship in 4.1.6 with or without a player. | none |
| 1 | This document | Design, questions, gateway note. | none |
| 2 | MEDIA-FETCH (core) | `AssetMediaFetcher`, `BoundedBody.copyTo`, the first-bytes check, the cache with its limit, the two integrity states, the wipe list entry and eraser change. Unit tests for every gate. No UI, no new dependency. Nothing calls it yet. | 0 |
| 3 | MEDIA-ENGINE (app) | The Media3 dependency, the `WAKE_LOCK` removal, the player wrapper built as in 4.2, the track and duration gates, the source gate tests, the measured APK size. No screen yet. | owner's yes to question 1 |
| 4 | MEDIA-UI (app) | The media card, full screen, lifecycle and lock handling, error states, strings in 13 languages, accessibility. | 2, 3 |
| 5 | MEDIA-CHECK (test) | The hostile-file corpus, the emulator script, the device checklist and its results. May be folded into 3 and 4. | 3, 4 |
| G | Gateway changes | Section 7. Another team, another repository. Any time; not a blocker. | none |

Each code PR runs the full unit suites before its final commit (`./gradlew --continue --no-build-cache :core:testMainnetDebugUnitTest --rerun :app:testMainnetPlayDebugUnitTest --rerun :app:testMainnetSideloadDebugUnitTest --rerun`) and gets the independent review and green CI that `docs/development-process.md` requires.

### 9.2 What needs the owner's decision

- **PR 3:** a new dependency (Media3, and Guava with it) in a wallet. Question 1.
- **PR 2 and PR 4 together:** they change what leaves the device. A tap sends a new request, and up to 32 MiB of a stranger's file comes back. Questions 2, 4, 6 and 13.
- **The wording of the integrity line** (question 5), because it is a statement to users about what the wallet checked.
- **The auto-lock choice** (question 7) and **Android 9** (question 11), because both are security decisions.

PR 0 and PR 5 need no decision. The gateway note goes to the server's team on the owner's say.

### 9.3 Unit tests

Every test is written first and seen red against the code before its fix; the PR says how (for a new gate: the test fails while the gate is a stub that lets everything through).

- **Fetcher:** declared length over the cap is refused with no body byte read; an answer with no length stops at cap + 1; `Content-Encoding` present is refused; a 3xx from the trusted gateway is not followed; a dag-pb CID is never asked of a public gateway; a raw CID with a wrong hash is deleted and the next gateway asked; a raw CID with the right hash is `CONTENT_VERIFIED`; a dag-pb answer is `SERVER_TRUSTED` and never anything else; 404 and 400 are remembered and 429, 503 are not; `TorNotReadyException` ends the attempt with nothing sent; the derived client shares the proxy selector, DNS and pinner objects and has the 10 minute call timeout; cancel deletes the `.part`.
- **First bytes:** each allowed container passes; PNG, HTML, `#EXTM3U`, ZIP, PDF, an empty file and a 3-byte file fail.
- **Cache:** the directory name for legacy, for a wallet id, and on testnet; eviction order; the 96 MiB limit; `.part` cleanup; two wallets do not see each other's files.
- **Wipe:** `eraseStores` deletes the directory; a directory that cannot be deleted makes the erase report failure; the full wipe removes every wallet's on both networks.
- **Track gate:** a 3840×2160 format, an unlisted codec, a 31 minute duration, two video tracks: each gives its message, and no renderer is started.
- **Source gates:** no `DefaultHttpDataSource`, `DefaultDataSource`, `DefaultMediaSourceFactory`, `MediaSession`, `WebView` or `addJavascriptInterface` in the player's packages; the merged manifest has no `WAKE_LOCK`; `NetworkSourceGateTest` still passes with the fetcher in it.
- **Locale:** the two existing gates, with the new files in `COVERED`.

### 9.4 Hostile-file corpus

Generated by a script (ffmpeg plus a few byte edits), kept under test resources, each with its expected result. Served to the fetcher from a local server in unit tests, and played on an emulator.

| File | Expected |
|---|---|
| MP4 cut off in the middle; cut off inside the header | Plays to the cut then stops cleanly, or "could not be played" |
| Server declares 40 MB | Refused unread |
| Server declares nothing and never stops sending | Stopped at cap + 1 |
| Server declares 1 MB and sends 40 MB | Stopped at the declared length; file fails or plays its first MB |
| gzip answer that inflates to 1 GB | Refused on `Content-Encoding` |
| HTML, PNG, ZIP, PDF, each declared `video/mp4` | "Not a video or audio file" |
| A real MP4 declared `image/png` | Handled by what it is |
| `#EXTM3U` playlist pointing at an outside URL | Refused; **no request leaves** |
| MP4 with a data reference to an `http://` URL | Plays or fails; **no request leaves** |
| 8192×4320 video; header claiming 16384×16384 | "Larger than the wallet plays" |
| Atom size `0xFFFFFFFF`; atoms nested 10,000 deep; 1,000 tracks | Error message; process alive; heap back to baseline |
| Duration claimed as 100 hours | "Longer than the wallet plays" |
| MP3 with a 30 MB ID3 tag and a huge cover picture | Plays or fails; nothing from the tag is shown |
| WAV claiming 4 GB of samples in a 1 KB file | Stops cleanly |
| WebM with absurd element lengths; Ogg with chained streams | Error or plays; process alive |
| MP4 with a subtitle track | Plays; no text drawn |
| A valid 32 MiB file; a valid file one byte over | Plays; refused |

"No request leaves" is asserted in unit tests by the data source refusing the URI, and on the emulator by the local test gateway's request log showing only the expected `HEAD` and `GET`.

### 9.5 Emulator check against the real assets

The investigation's probe activity is the pattern: a debug-only activity that calls the production `AssetMetadataService` for an asset id and composes the production UI, with no wallet needed. The build PR turns that into `scripts/asset-media-check.sh`, using `scripts/test-helpers/device.sh` so it can only ever drive an emulator, on API 28, 33, 34 and 35 (the project's AVDs). For assets 5376 (video), 5252 (picture plus song) and 5361 to 5364 (songs): the card appears; nothing is requested before the tap; after the tap the file plays; the scrubber moves; home-button stops the sound; logcat shows no crash and one `HEAD` and one `GET`. Each ends PASS, FAIL or SKIP with a reason; a skip is not a pass. `androidTest` runs in no CI job, so this is a script and a recorded result, not a claim of CI coverage.

### 9.6 Device checks

- **Note 8 (Android 9, API 28):** every real asset above; the whole corpus; a phone call arriving during a song; headphones pulled out; auto-lock firing during playback; Tor on; a metered connection. This is the oldest media stack the project tests and the one that matters most for 4.3.
- **Moto G 5G:** the same list, short form.
- **S25 Ultra (Android 15, 16 KB pages):** a release-signed build only, owner's hands. One video, one song. Confirms no native library arrived.
- Heap before and after ten play and stop cycles, to show the player is released.

### 9.7 What this design run did and did not check

Did: read the code cited; fetched the published Media3 1.11.1 artifacts and read their metadata, manifests and rules; ran R8 on a stand-alone probe for the size; ran `:app:dependencies` for the current tree; made the gateway requests in 7.2.

Did not: build the app with Media3; run any unit suite (no code changed); run an emulator or touch a phone; test playback of anything; verify the claim in 4.3 that ExoPlayer reports an out-of-memory error instead of crashing (the corpus is there to check it); test the public gateways; re-scan the roughly 670 older assets; look at iOS.

## 10. Questions for the owner

1. **Add Media3 ExoPlayer 1.11.1 (one artifact, with Guava behind it, about 0.9 MB)?** Recommended: **yes.** The alternative, the platform player, gives the wallet no way to refuse a track before it is decoded.
2. **Is 32 MiB the right limit?** Recommended: **yes.** It is 3.5 times the largest file known. It is one constant and can be raised later with its own review.
3. **Download-then-play only, even after the gateway honours `Range`?** Recommended: **yes for 4.1.6.** Every check finishes before any decoding, and the player needs no network. Revisit streaming only if files grow past what a wait can bear.
4. **Keep played files between sessions (up to 96 MiB per wallet, app-private, erased by wipe and wallet removal), or delete them when the wallet locks?** Recommended: **keep.** Deleting on lock means a 7 MB song is downloaded again after every app switch. The cost of keeping: someone who can read the app's private storage can see which media was played.
5. **The integrity line.** Recommended: "Served by DigiScope. This wallet cannot check this file against the asset's file ID." It is exact. A softer line would claim more than the code does.
6. **dag-pb media from DigiScope only, so a file DigiScope lacks cannot be played?** Recommended: **accept for 4.1.6**, send the gateway note, and schedule real dag-pb verification (which would also open the public gateways) as its own item after 4.1.6.
7. **Should watching or listening count as activity for the auto-lock?** Recommended: **no.** The lock is a security control, and the player should not weaken it. The visible cost: with a short auto-lock time, a long song is cut off unless the user touches the screen. The alternative is "counts as activity while playing, for one file, never looping", which leaves a phone unlocked for at most 30 minutes.
8. **Animated GIF and WebP.** Recommended: **not in the player PRs.** 4.1.6 shows the first frame with an "Animated image" label; animation is a separate, small image-loader PR after the player, with its own memory tests.
9. **An "Open in browser" action for files the wallet does not open?** Recommended: **no, in 4.1.6.** It bypasses Tor, tells a gateway what the user holds, and opens a stranger's HTML in a browser. "Copy file ID" is enough.
10. **Remove the `WAKE_LOCK` permission that Media3's manifest adds?** Recommended: **yes**, with a test that the permission list is unchanged. The player never holds a wake lock.
11. **Allow playback on Android 9, whose codecs get no more fixes?** Recommended: **yes, after the corpus passes on the Note 8.** Nothing is decoded without a tap, and the decoders run outside the wallet's process. The stricter alternative is to show the card without a Play button below Android 10.
12. **The September branch `feature/asset-media-playback`** streamed unverified media from `dweb.link` and `ipfs.io` and added two columns in a migration numbered for an old schema. Recommended: **do not merge it; delete it once this design is accepted.** Its `ON_STOP` lesson is carried into 6.3.
13. **Ask before every play on a metered network, or only above some size?** Recommended: **always ask**, with the size shown. One rule, easy to explain.
14. **Run the player in a separate process?** Recommended: **not in 4.1.6.** The parsing is memory-safe code and the decoders are already outside the process. Reconsider if the corpus shows the app can be made to crash.
