# DigiAsset media player: design (4.1.6)

**Date:** 2026-11-22 · **Revision 2**, after the independent design review · **Status:** design only, nothing built
**Decision behind it:** the owner, 2026-11-22: "I think we should build a player." That is settled. The questions in section 10 are not answered yet.
**Code read at:** `origin/develop` 99e74e803 (version 4.1.5). Every `file:line` below is at that commit. The prerequisite PR #308 was read at 97f5d92a8.
**Evidence:** the ASSET-MEDIA-RENDER investigation of 2026-11-22 (private records; measured on an API 34 emulator against real mainnet assets), the measurements of revision 1 (marked "measured in revision 1"), and what this revision read from the published Media3 1.11.1 artifacts (marked "read from the published artifact").

This document replaces `2026-09-04-asset-media-playback-design.md`, which lives only on the unmerged branch `feature/asset-media-playback`. That design streamed unverified bytes from public gateways. This one does not.

Revision 2 answers the 26 points of the review posted on PR #307. Section 11 lists each point and where it was answered. Four of them were plain errors in revision 1 and are corrected where they stood (points 4, 13, 22, 25).

## 0. The short version

- The wallet plays a video or a song that a DigiAsset carries, on the asset detail screen, after a tap. Never in the list. Never by itself.
- The file is downloaded once, into the app's private cache, and then played from that local file. The player itself has no network access at all.
- **Media is asked of one server only: the pinned DigiScope gateway, with redirects off.** This is the recommended rule (question 3). Revision 1 said "pinned, no redirect" and then asked public gateways for some files. That was a contradiction and is gone.
- One tap is one download of at most the size the user was shown. A download stops when the route changes, when the wallet locks, when the app leaves the screen, and before a wipe.
- The engine is Jetpack Media3 ExoPlayer 1.11.1, one declared artifact, no native code.
- A file is limited to 32 MiB. Only MP4, WebM, MP3, WAV, Ogg and FLAC containers are opened, decided from the file's first bytes, not from what the metadata says.
- A track is refused before any decoder is created. The mechanism is named in 4.4.
- For today's assets the wallet cannot check the file against the asset's file ID (CID). It says so on screen. It never calls such a file verified.
- With the default settings a song or a video longer than one minute would be cut off by the auto-lock or by the display going to sleep. Question 6 puts that choice to the owner.
- It works with the gateway as it is today. The gateway changes in section 7 make it better; they are not a precondition.

## 1. Scope

### 1.1 What 4.1.6 plays

| Kind | Containers | Tracks accepted | Where |
|---|---|---|---|
| Video | MP4 (`.mp4`, `.m4v`), plain or fragmented; WebM | H.264, VP8, VP9 video; AAC, Vorbis, Opus audio | Asset detail screen, and full screen from there |
| Audio | MP3, WAV, Ogg (Vorbis, Opus), FLAC, M4A (AAC in MP4) | MP3, PCM, Vorbis, Opus, FLAC, AAC | Asset detail screen |

FLAC needs a platform decoder, which Android has from 8.1 (API 27). On API 26, the project's `minSdk` (`app/build.gradle.kts:83`), a FLAC file gets the "this phone cannot play this format" message. HEVC and AV1 are left out of 4.1.6: support differs by device and neither appears in any asset found so far. "Left out" is enforced, not hoped for: see 4.4.

HEIC and AVIF pictures also start with `ftyp`. They are pictures, not media, and the first-bytes check refuses them by brand (4.2).

Today the app plays none of this. It has no player dependency (`gradle/libs.versions.toml:75` is Coil, the only media library) and `AssetMediaViewer` is still images only (`app/src/main/java/app/aroundtheblock/wallet/ui/asset/AssetMediaViewer.kt:39-41`).

### 1.2 Where, and what starts it

- **Only the asset detail screen** (`ui/asset/AssetDetailScreen.kt`). The list (`ui/asset/AssetListScreen.kt:232`) shows a still icon. It never fetches or plays media. Revision 1 said the list also gets "a small badge" from the prerequisite PR. PR #308 does not contain one. The badge is now a named row in the table in 8.3 instead of a remark.
- **Tap to play.** Nothing is requested for a media file until the user taps Play: no download, no size request, no preloading. Opening the detail screen costs what it costs today.
- **No autoplay, no looping, no playlist.** One file, played once per tap.

### 1.3 Other kinds: what happens to each

Nothing below is dropped silently. Each kind gets a row on the detail screen that names it.

| Kind | 4.1.6 behaviour | Why |
|---|---|---|
| Animated GIF, animated WebP | First frame, as today, plus the label "Animated image". Animation is its own later PR (question 12). | It is now the most common moving media on the index (23 of the newest 106 assets). It is an image-loader change (`coil-gif`), not a player change, and it has its own memory risks. |
| SVG | Not drawn. Row: "SVG image. This wallet does not show it." | SVG can carry script and outside references. |
| PDF, HTML | Not opened. Row names the type. | Documents from strangers. HTML is a phishing page waiting to be opened. No WebView for asset media, ever (the Market rule, CLAUDE.md). |
| 3D (glTF, GLB) | Not opened. Row names the type. | No renderer, and none is planned. |
| Anything else | Row: "File (type as declared). This wallet does not open it." | |

For every unopened kind the row offers "Copy file ID" (the CID), when the entry has one. It offers no "Open in browser" in 4.1.6 (question 13).

### 1.4 Not in 4.1.6

Background playback, a media notification, lock-screen or headset-button controls, picture-in-picture, casting, streaming, subtitles, cover art or titles read from the file, playback speed, download to shared storage, sharing, protected (DRM) files, iOS.

## 2. The engine

### 2.1 Decision: Media3 ExoPlayer, one artifact

**`androidx.media3:media3-exoplayer:1.11.1`** and nothing else declared. Not `media3-ui`, not `media3-ui-compose`, not `media3-datasource-okhttp`.

| Option | Verdict | Reason |
|---|---|---|
| Platform `MediaPlayer` / `VideoView` | No | The whole file goes to the platform's native extractor. No way to refuse a track before it is decoded. Errors are two integers. Behaviour differs by vendor and by Android version, and the Note 8 is Android 9. Costs no APK bytes, which is its only advantage. |
| Media3 ExoPlayer | **Yes** | The container is parsed by Java code inside the app, which cannot corrupt memory. The wallet can read every track's codec and size before a decoder exists, and refuse (4.4 says how). Same behaviour on API 26 to 36. Errors are typed. |
| `media3-ui` (`PlayerView`) | No | It is an Android View with its own controls, a subtitle view, its own strings and `recyclerview`. The wallet needs its controls in its 13 languages and wants no subtitle surface. |
| `media3-ui-compose` | No | Its POM imports `compose-bom:2026.05.00`. The project is on `2024.12.01` (`gradle/libs.versions.toml:50`). It would force a Compose upgrade inside a feature PR. |
| `media3-datasource-okhttp` | Not in 4.1.6 | The player reads a local file only (section 3). |

### 2.2 Compatibility (read from the published 1.11.1 artifacts)

| Requirement of Media3 1.11.1 | Project | Source | OK |
|---|---|---|---|
| `minCompileSdk=36` (AAR metadata) | `compileSdk = 36` | `app/build.gradle.kts:77`, `core/build.gradle.kts:15` | yes |
| `minSdkVersion 23` | `minSdk = 26` | `app/build.gradle.kts:83` | yes |
| `minAndroidGradlePluginVersion=1.0.0` | AGP 8.10.1 | `gradle/libs.versions.toml:17` | yes |
| Kotlin: `media3-exoplayer` and what it pulls in declare no Kotlin dependency | Kotlin 2.4.20 | `gradle/libs.versions.toml:35` | yes |
| Native libraries: none. No `jni/` directory in any of the seven AARs (re-checked for this revision by unpacking all seven) | 16 KB page size | `app/build.gradle.kts:178-193` | nothing to align |
| Core library desugaring: not required | not enabled | | yes |

Version 1.11.1 is the newest stable release on Google Maven today (1.12.0 is in beta).

**Everything that comes with it.** Revision 1 said "the review surface is Media3 plus Guava". That left out what Guava brings. The full list, read from the published POMs for this revision:

| Artifact | Version | Brought by | What it is |
|---|---|---|---|
| `androidx.media3:media3-exoplayer` | 1.11.1 | declared | the player |
| `androidx.media3:media3-common` | 1.11.1 | exoplayer | shared types |
| `androidx.media3:media3-container` | 1.11.1 | exoplayer | container helpers |
| `androidx.media3:media3-database` | 1.11.1 | exoplayer | a small SQLite helper (used by Media3's own download cache, which the wallet does not use) |
| `androidx.media3:media3-datasource` | 1.11.1 | exoplayer | data sources, including HTTP ones the wallet never constructs (2.4) |
| `androidx.media3:media3-decoder` | 1.11.1 | exoplayer | decoder interfaces, no decoder |
| `androidx.media3:media3-extractor` | 1.11.1 | exoplayer | every container parser; R8 keeps the ones named (2.4) |
| `androidx.annotation:annotation` | 1.6.0 asked | all seven | annotations only |
| `androidx.annotation:annotation-experimental` | 1.3.1 asked | common | annotations only |
| `androidx.exifinterface:exifinterface` | 1.3.6 asked | exoplayer, datasource | already in the app at 1.3.7 (revision 1, `:app:dependencies`) |
| `com.google.guava:guava` | 33.3.1-android | common | **new code in the app** |
| `com.google.guava:failureaccess` | 1.0.2 | guava | one small class, new |
| `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | guava | replaces the `listenablefuture:1.0` stub the app has today with an empty one |
| `com.google.code.findbugs:jsr305` | 3.0.2 | guava | annotations only |
| `org.checkerframework:checker-qual` | 3.43.0 | guava | annotations only |
| `com.google.errorprone:error_prone_annotations` | 2.28.0 | guava | annotations only |
| `com.google.j2objc:j2objc-annotations` | 3.0.0 | guava | annotations only |

Not checked: which of the annotation artifacts the app already has, and which version Gradle resolves when two are asked for. No Gradle run was made for this revision. So the build PR (PR 3) must paste the diff of `:app:dependencies` for `mainnetPlayReleaseRuntimeClasspath` and `mainnetSideloadReleaseRuntimeClasspath`, before and after, and the list above is corrected from it. Media3, Guava and `failureaccess` join the per-build dependency CVE check.

**A permission arrives with it.** The `media3-exoplayer` manifest declares `ACCESS_NETWORK_STATE` and `WAKE_LOCK` (re-read for this revision). The app already has the first (`app/src/main/AndroidManifest.xml:8`). It does not have `WAKE_LOCK`, and the merged manifest would gain it silently. The player never calls `setWakeMode`, so the build PR removes it with `tools:node="remove"` and adds a test that the merged manifest's permission list is unchanged (question 14).

### 2.3 APK size

**Corrected.** Revision 1 said "the cost is dex only". That is wrong. The published `media3-exoplayer` AAR carries **83 `res/values*` directories**, each with nine strings for a download notification the wallet never shows, 71,445 bytes of XML in all (counted for this revision from the unpacked AAR). The other six AARs carry no resources. The stand-alone probe of revision 1 could not see resources at all.

So the cost has three parts, and revision 1 measured one:

| Part | Known | How PR 3 measures it |
|---|---|---|
| dex | Measured in revision 1 on a stand-alone probe: 921,324 B (521,103 B compressed) for six named extractors; 1,095,864 B (623,052 B) for ExoPlayer's defaults. | `apkanalyzer dex packages`, filtered to `androidx.media3` and `com.google.common` |
| resources | 71 KB of source XML across 83 locales. Not measured as built. Resource shrinking is on (`app/build.gradle.kts:156`, `:171`), so the built cost may be lower. | size of `resources.arsc` before and after; the count of `exo_download_*` entries left in it |
| compiled code on the device | Not measured. ART compiles the dex after install, and that code takes space beyond the dex. "0.9 MB installed" in revision 1 left this out. | `adb shell dumpsys package <id>` code size on an emulator, before and after; stated as an estimate |

The probe's dex figure is also now low for another reason: this revision adds a seventh extractor (fragmented MP4, 4.3) and a few small classes. It was not re-run. Expect about 1 MB of dex. No native code, so the cost is the same for every ABI.

**How the build PR measures it for real.** Build `:app:assembleMainnetSideloadMinifiedDebug` (the `minifiedDebug` type, `app/build.gradle.kts:164-175`) at the commit before the dependency and at the commit after. Report, for each APK: `apkanalyzer apk file-size`, `apkanalyzer apk download-size`, the dex packages above, and `resources.arsc`. Confirm `unzip -l` shows the same `lib/` entries before and after. The owner's release build gives the final number.

### 2.4 R8, and which constructor builds the player

Media3 ships consumer rules in `common`, `datasource`, `exoplayer` and `extractor`. No rule is expected in `app/proguard-rules.pro`. The build PR must show a minified build with **no new R8 warnings** and must not add a blanket `-dontwarn`.

Which classes R8 keeps depends on which constructor the wallet calls. Read from the published `ExoPlayer$Builder` class for this revision:

| Constructor | What it refers to by itself |
|---|---|
| `ExoPlayer.Builder(Context)` | `DefaultRenderersFactory`, and `DefaultMediaSourceFactory` over `DefaultExtractorsFactory`: every extractor and every data source |
| `ExoPlayer.Builder(Context, RenderersFactory)` | the same `DefaultMediaSourceFactory` |
| `ExoPlayer.Builder(Context, RenderersFactory, MediaSource.Factory)` | neither. **This is the one the wallet uses.** |

And for the source: `ProgressiveMediaSource.Factory(DataSource.Factory, ProgressiveMediaExtractor.Factory, DrmSessionManagerProvider, LoadErrorHandlingPolicy, int)`, the five-argument constructor, so that the wallet hands in its own "no DRM" provider and the default provider (which can build an HTTP licence client) is never constructed (4.3).

The player is constructed with direct calls, not by reflection.

### 2.5 The built APK is checked, not only the source

Revision 1 proved "the parsers are not present" with a test that searched the app's own source for class names. That proves the wallet does not name them. It does not prove R8 removed them. The check moves to the artifact:

`scripts/check-media-apk.sh <apk>` runs `apkanalyzer dex packages` on the minified APK and fails if any class is present under:

- `androidx.media3.extractor.ts`, `.flv`, `.avi`, `.amr`, `.jpeg`, `.png`, `.bmp`, `.webp`, `.heif`, `.avif`, `.text`
- `androidx.media3.extractor.DefaultExtractorsFactory`
- `androidx.media3.exoplayer.source.DefaultMediaSourceFactory`
- `androidx.media3.datasource.DefaultHttpDataSource`, `DefaultDataSource`, `ContentDataSource`, `AssetDataSource`, `RawResourceDataSource`, `UdpDataSource`
- `androidx.media3.exoplayer.drm.DefaultDrmSessionManager`, `HttpMediaDrmCallback`, `FrameworkMediaDrm`
- `androidx.media3.exoplayer.offline` (the download service the 83 resource files belong to)

It is wired into CI's `app` job on the minified debug APK. Like every gate here it is proven both ways: PR 3 shows it failing on an APK built with `ExoPlayer.Builder(Context)` and passing on the real one. If R8 keeps one of these classes for a reason not foreseen here, the PR names the reference that holds it; the list is not quietly shortened. The source gate of revision 1 stays as a second, cheaper check.

### 2.6 Housekeeping

One catalog key, `media3`, in `gradle/libs.versions.toml`, so the family moves together like the other lockstep families listed at the top of that file.

## 3. The data path

### 3.1 The rule: which servers may ever be asked for media

**Media bytes come from the trusted, pinned gateway only: `api.digiscope.me`. No public gateway is asked for media, whatever kind of CID it is.** This is the recommended answer to question 3.

Why revision 1 was wrong here. It said media comes "through the pinned client" and "uses the same no-redirect client". Then 3.3 asked all four gateways for a raw-codec CID. The code gives only the trusted gateway the no-redirect client; the three public gateways get the plain shared client (`IpfsClient.kt:253-254`). That client holds pins for DigiScope's host only, so a public gateway gets ordinary TLS (`NetworkModule.kt:59-62`, `:101`), and it follows redirects. Cleartext is blocked by the network security config, but a redirect to any other HTTPS host is followed. So up to 32 MiB could arrive from a host on no list, and that host would learn the file's CID and the user's address. A hash check protects the bytes. It does not protect the privacy.

The rule, in full:

- **One host.** The media fetcher is built with the trusted list (`IpfsClient.kt:167-169`) as its only gateway list. It does not take `DEFAULT_GATEWAYS` (`IpfsClient.kt:148-153`).
- **Pinned.** The DigiScope certificate pins are on the shared client (`NetworkModule.kt:101`, `core/.../network/DigiScopePins.kt:31-37`).
- **No redirect.** The fetcher uses a client with both redirect switches off, as `IpfsClient` does for a trusted gateway (`IpfsClient.kt:59-64`). A 3xx is a failed fetch.
- **Only a CID.** The request URL is built only by `IpfsClient.gatewayUrl` (`IpfsClient.kt:102-105`), which refuses anything that is not exactly a CID.

**What the strict rule costs.** A raw-codec media file that DigiScope does not have cannot be played, although a public gateway could serve it and the wallet could check it. The cost looks small: no raw-codec media file has been found (every media file found so far is dag-pb), and a raw-codec CID names a single IPFS block, which nodes exchange only up to about 2 MiB. That block limit is from memory of the IPFS specifications; it was not measured for this document.

**The other choice,** if the owner prefers it: ask the public gateways for raw-codec CIDs only, with redirects off on every gateway (a new derived client; at least one public gateway is believed to redirect path requests to a subdomain and would then simply fail), and say in 5.1 that those hosts are unpinned and learn the CID and the address. Each further gateway is then a further download and needs its own consent (3.5).

**A URL in an asset's metadata is never fetched.** That is already the rule for images (`ui/asset/AssetImageResolver.kt:12-18`). Which entries have a fetchable CID is decided once, by PR #308's parser (section 8). The player does not decide it again.

**ExoPlayer makes no network request.** It is built over a data source that opens exactly one path, the finished cache file, and refuses every other URI. So the question "did the player bypass the pins or Tor" has a structural answer: it has no socket. The APK check in 2.5 shows the HTTP data sources are not even in the build.

### 3.2 New code: `AssetMediaFetcher` (core)

A sibling of `IpfsClient.fetch`, in `core/.../ipfs`. It shares the URL builder and the trusted list. It differs in five ways:

1. **It writes to a file, not to memory.** `IpfsClient` holds the whole answer in a `ByteArray` under a 5 MiB cap (`IpfsClient.kt:156`, `:267`). That cap stays as it is for metadata and icons.
2. **Its own time limit.** The shared client ends any call at 45 seconds (`NetworkModule.kt:111`). 32 MiB in 45 seconds needs about 6 Mbit/s. The fetcher derives a client with `newBuilder().callTimeout(10 minutes)`; `newBuilder()` keeps the proxy selector, DNS, interceptor, pool and pins. The 30 second read timeout stays. A unit test asserts the derived client's proxy selector, DNS and pinner are the shared client's own objects.
3. **Every byte goes through `BoundedBody`.** See 3.4 for the correction about what that object does and does not do.
4. **Its own "not here" memory.** Revision 1 shared `IpfsClient`'s `GatewayMissMemory`. That would break after PR #308, which also files a picture refused for size in that memory (PR #308, `IpfsClient.kt:396`): a 7 MB song once asked for as a picture would then be "not here" for the player. The fetcher gets its own instance of the same class (`GatewayMissMemory.kt:25-30`).
5. **It can be stopped, and it is.** See 3.6.

The request sends `Accept-Encoding: identity`. With that header set by the caller, OkHttp does not inflate anything, so the bytes counted are the bytes on the wire. An answer that carries a `Content-Encoding` anyway is refused.

### 3.3 What kind of CID it is: decided from the codec, not from the text

**Corrected rule.** Revision 1 named the kinds by how the text starts (`Qm…`, `bafybe…`, `bafkre…`), the same test the image path uses today (`IpfsClient.kt:286`). That test is wrong for a CID written another way. `CidVerifier` reads base58 (`z…`) and base16 (`f…`) CIDv1 as well as base32 (`CidVerifier.kt:103-107`), and it skips the codec field without looking at it (`CidVerifier.kt:114-115`). So a dag-pb CID written in base58 does not start with `bafybe`. It would take the hash-checked path, and a gateway's root block does hash to it. A file must never earn "checked" that way.

The fetcher asks a new function, `CidVerifier.kindOf(cid)`, which decodes the CID and answers from the codec and the hash function:

| Decoded | Kind | What the fetcher does |
|---|---|---|
| CIDv0 (`Qm…`): dag-pb by definition | `DAG_PB` | Trusted gateway only. Not checked against the CID. |
| CIDv1, codec `0x70` (dag-pb), any multibase | `DAG_PB` | The same. |
| CIDv1, codec `0x55` (raw), hash sha2-256 (`0x12`) or sha2-512 (`0x13`) | `RAW_CHECKABLE` | Hash of the file, computed while it is written, must equal the digest in the CID. Both hash functions are checked, because the verifier supports both (`CidVerifier.kt:134-138`). |
| CIDv1, codec `0x55`, any other hash function (the identity hash included) | refused | "This wallet cannot check this kind of file ID." Nothing requested. |
| Any other codec (dag-cbor, dag-json, …) | refused | The same message. Nothing requested. |

The label on screen follows from the kind and from nothing else (3.7). The first-bytes gate would probably stop a root block anyway. The label does not depend on that.

`CidVerifier.verify` takes the whole content as a `ByteArray` (`CidVerifier.kt:40-45`). The fetcher needs a variant that takes a finished digest, so that 32 MiB is hashed while it streams to disk and is never held in memory.

**One copy of the rule.** The same wrong test decides trust for metadata and pictures today (`IpfsClient.kt:286`). PR 2 replaces it with `kindOf`, with a test seen red on a dag-pb CIDv1 written in base58. That is a change to the image path made by a player PR. It is listed in 8.3 and in question 22 so that it is not done in passing.

A CID can also name a **directory**. The wallet cannot tell before asking. A directory is not a media file: whatever the gateway answers (a listing page, an error) fails the first-bytes gate or the HTTP status check. No model field is needed for it.

### 3.4 Size: what the user agreed to is what can be downloaded

**Corrected.** Revision 1's corpus said a server that "declares 1 MB and sends 40 MB" is "stopped at the declared length". The reader it was built on does no such thing. `BoundedBody.read` refuses a declared length over the cap unread, and otherwise reads to the cap plus one byte. Its own header says "a declared length is never relied on to stop the read" (`BoundedBody.kt:14-18`, `:33-42`). So in revision 1 that server would have delivered up to 32 MiB to a user who was shown "1 MB". Revision 1 also quoted that header as "the one way the wallet reads a network answer". The source says "into memory" (`BoundedBody.kt:11`), which is exactly the part a file download changes.

The rule now:

1. **The agreed size.** After the tap the fetcher sends one `HEAD`. If it declares a length, that length is the agreed size. It is the number shown to the user wherever a size is shown. If it declares none, the agreed size is the cap, and the screen says "up to 32 MB".
2. **Over the cap is refused unread.** An agreed size over 33,554,432 bytes: nothing is downloaded.
3. **The `GET` is bound to it.** If the `GET` declares a length and it differs from the agreed size, the body is not read. The read then stops at the agreed size plus one byte. That is `BoundedBody`'s existing rule with the agreed size passed as its cap: no new stopping rule is invented. One byte too many is a failure: "The file was larger than the server said. Nothing was kept."
4. **Short is a failure too.** Fewer bytes than agreed, when a length was declared, is a broken download.
5. `BoundedBody` gains `copyTo(source, declaredLength, cap, sink)`, the same two rules as `read`, writing to a file. Its header comment and `NetworkSourceGateTest` are changed to say "into memory or to a file".

Whether OkHttp itself cuts a body at the declared length was not verified for HTTP/2, and the design does not rely on it. The wallet counts the bytes itself.

### 3.5 One consent, one download

A tap (and, where one is shown, the confirmation after it) allows **one `HEAD` and one `GET`, to one host, of at most the agreed size, on the route the user was told about.** Nothing else.

- A failed download is not retried by itself. "Try again" is a new tap, with a new confirmation where one applies.
- A raw-codec file whose hash does not match is deleted, and that is the end: "This file does not match its file ID. Nothing was kept." Under the recommended rule there is no next gateway. Under the other choice in 3.1 the next gateway would be a new download and would need a new tap.
- So one "This file is N MB" can never cost more than N MB plus one byte.

### 3.6 When a download is stopped

Revision 1 described this for the player only, and claimed one case that nothing provided. Every case is now stated, with who does it.

There is at most one download at a time. It is held by one object in core (`AssetMediaDownloads`), which owns the OkHttp `Call`. Stopping means: cancel the call, wait for the writer to finish (bounded, 5 seconds), delete the `.part` file.

| Event | What stops the download | Notes |
|---|---|---|
| The Tor route changes | A collector on `TorManager.routes` (`TorManager.kt:139-143`), the flow that already exists for the Hub socket. Any route other than the one the user agreed to cancels the call. | **This is new code.** Revision 1 said "a route change cancels it". Nothing did: `TorWaitInterceptor` reads the route once, when the call starts (`NetworkModule.kt:133-152`), and `pool.evictAll()` drops idle connections only (`:150`). A download begun direct would have kept going direct for up to ten minutes after the user turned Tor on. |
| The route differs between consent, `HEAD` and `GET` | The route is recorded at consent. Before the `GET` is sent it is compared again. The fetcher's client also carries a network interceptor that reads the proxy of the connection the request is about to be written to, and fails the call if it is not the agreed kind (SOCKS on the agreed port, or direct). | Read before the request is written, so the CID is not sent on the wrong route. The TCP and TLS connection itself has been opened by then; the server has seen an address, not a request. That a network interceptor runs after the connection is chosen and before the request is written is OkHttp's documented order; it was not re-checked against 4.12.0 for this document, and PR 2's test must show it. |
| The wallet locks | The holder observes `walletState`; anything other than `Unlocked` stops it. | Covers the idle auto-lock (`MainActivity.kt:227-245`). |
| The app goes to the background | Stopped on `ON_STOP`, directly, as well as through the lock that `onStop` causes (`MainActivity.kt:442-462`). | Neither path depends on the other. A locked or backgrounded wallet does not keep pulling a stranger's file on mobile data. The cost: a slow download needs the app kept in front. |
| The user leaves the asset's detail screen | Stopped when the screen is disposed. | |
| The user switches wallet | Stopped before `WalletSwitcher` restarts the process (`WalletSwitcher.kt:22-32`). The restart would kill it anyway, and every `.part` is deleted at the next start. | A switch always restarts the process. So a download for wallet A cannot be running in the process of wallet B, and a `.part` of A cannot be completed after the switch. |
| A full wipe | The holder registers as a `WalletSessionStop` (`WalletDataEraser.kt:116-126`). The wipe stops every such session before it erases anything (`WalletManager.kt:1071-1073`). `stopForWipe` cancels, waits, and **closes** the store. | See 5.5 for why closing matters. |
| A wallet is removed | Another wallet: no download can be running for it (see "switches wallet"). The running wallet: removal marks it and asks for a restart (`WalletRemoval.kt:83`); the holder is stopped and closed at the mark. | The eraser also asks the holder to stop before it deletes or reads back, so no caller can forget (5.5). |
| Cancel is tapped | The same stop. | |

### 3.7 "Not available", and what "Try again" does

A 404 or a 400 from the gateway is remembered, ten minutes at first and growing to an hour (`IpfsClient.kt:125-140`, `GatewayMissMemory.kt:52-63`). Inside that time no request is made. Revision 1 told the user they "can try again later" and showed a button that, inside that time, would have asked nobody.

- **A remembered miss shows no "Try again".** The card says: "This file was not on the server when the wallet last asked. It will ask again after 14:32." The time is the end of the remembered wait. The memory class gains one read-only function for it.
- When that time has passed, the Play button is back.
- **A failure that may be different a moment later** (a timeout, a broken connection, a 5xx) shows "Try again" at once. It makes a real request.
- **A 429** shows "The server is busy." If the answer carried `Retry-After`, "Try again" appears after that time.
- The memory is in the process only. Restarting the app asks again at once, as today.

### 3.8 Download, then play. No streaming in 4.1.6.

The gateway ignores `Range` today (section 7.2). But the decision does not rest on that. Download-then-play is right even after the gateway honours `Range`:

- The size limit, the first-bytes check, the structure checks and (for raw CIDs) the hash are all settled **before** a player exists.
- The player has no network stack. One less thing to audit.
- Seeking is instant and costs no data.

The cost is a wait before the first frame. Measured in revision 1, DigiScope sent 4.27 MB in 1.3 s and 7.30 MB in 1.8 s to a wired host; a phone on mobile data or Tor will be slower, which is why the UI shows progress and a Cancel button.

### 3.9 Integrity: what is verified and what is not

**The project's open question.** A dag-pb CID is the hash of a UnixFS tree, not of the file's bytes. To check a file against it, the wallet must fetch the tree's blocks, hash each one, and rebuild the file from them. The wallet has no code for that. Today it accepts dag-pb content from DigiScope on that server's pinned certificate (`IpfsClient.kt:158-166`). The comment there says it exactly: "That is a server-identity check, not a content check: the pinned server itself is still trusted for these bytes." This design does not close that.

| Check | dag-pb CID (all known media) | raw CID |
|---|---|---|
| The bytes came from the pinned DigiScope host, over TLS, with no redirect | yes | yes (recommended rule) |
| The CID asked for is the one the asset's metadata names | yes | yes |
| The file's bytes hash to the CID, at download | **no** | **yes** |
| The file on disk is checked again each time it is opened | against what this run downloaded (5.4) | against the CID again (5.4) |
| Size within what was agreed, container on the allow-list | yes | yes |

**What it cannot do:** tell whether DigiScope, or anyone who controls that server, sent a different file than the issuer published. A swapped video moves no funds and changes no balance. It can mislead about what the asset is.

**What the screen says.** One line under the player, always visible while a file is loaded:

- raw CID, hash matched: "Checked against the asset's file ID."
- dag-pb: "Served by DigiScope. This wallet cannot check this file against the asset's file ID."

**The label is derived, never stored.** It is a function of `CidVerifier.kindOf(cid)`: `RAW_CHECKABLE` gives the first line, `DAG_PB` the second. Revision 1 stored it in a `.state` file beside the media file. That was a second copy of a fact, and it could go missing or disagree with the CID. There is no `.state` file any more (5.4). No type, log line or string may call a dag-pb file verified.

The existing `IpfsFetcher` comment that says bytes are "already authenticated" (`ui/asset/IpfsFetcher.kt:22-23`) is wrong for dag-pb pictures in the same way. It is still there in PR #308 at 97f5d92a8. It has a row in 8.3.

**The real fix, later.** Verified dag-pb needs either a trustless CAR response from the gateway or raw block fetches, plus a UnixFS reader in the wallet. Question 11.

## 4. Limits against hostile files

Anyone can send an asset to an address. The file is a stranger's input, and a tap is the only consent.

### 4.1 The gates, in the order they run

| # | Gate | Value | On failure |
|---|---|---|---|
| 1 | The entry is fetchable and of a kind this wallet plays | section 8 | Row shows the kind; no Play button |
| 2 | The CID's kind | 3.3 | "This wallet cannot check this kind of file ID." |
| 3 | The wallet has finished starting | 4.8 | "The wallet is still starting. Try again in a moment." |
| 4 | Room | free heap over a floor (4.7); free storage over a floor (5.4) | "Not enough memory…" / "Not enough free storage…" |
| 5 | Consent, where one is needed | 5.2, 5.3 | Nothing sent |
| 6 | Agreed size, from `HEAD` | ≤ 33,554,432 B (32 MiB) | "This file is too large for the wallet to play (N MB; the limit is 32 MB)." Nothing downloaded. |
| 7 | Actual size while downloading | exactly the agreed size; stop at agreed + 1 byte (3.4) | "The file was larger than the server said." Partial file deleted. |
| 8 | Download progress | 30 s read timeout; 10 min whole call; every stop in 3.6 | "The download stopped. Try again." |
| 9 | Hash, raw CIDs only | digest equals the CID's | "This file does not match its file ID." File deleted. |
| 10 | First bytes and structure | 4.2 | "This is not a video or audio file this wallet can play." File deleted. |
| 11 | Tracks, read with no decoder present | 4.4 | "This phone cannot play this format." / "This video is larger than the wallet plays." / "This file is copy-protected." |
| 12 | Prepare | ready within 10 s of opening the local file | "This file could not be opened." Player released. |
| 13 | While playing | 4.5 | Stop, release the player, plain message |

**Why 32 MiB.** The largest media files found so far are 9.08 MB (a GIF), 7.30 MB and 6.91 MB (songs); the one video is 2.30 MB for 10 seconds of 1080p. 32 MiB is about 3.5 times the largest known file. It holds about 2.4 minutes of video at that bitrate, or about 35 minutes of 128 kbit/s audio. The file-size limit bounds disk and data. **It does not bound memory.** That is 4.7.

### 4.2 First bytes, and structure checks the wallet makes itself

Read from the finished file by the wallet's own code, before a player exists. The metadata's `mimeType` only chooses the label shown before the tap. It decides nothing about how the file is handled.

| Container | Accepted when | Refused although it looks close |
|---|---|---|
| MP4 / M4A | `ftyp` at offset 4, the box between 16 and 4,096 bytes, and the major brand is not a picture brand | Major brand `heic`, `heix`, `hevc`, `hevx`, `heim`, `heis`, `mif1`, `msf1`, `avif`, `avis`: these are HEIC and AVIF pictures. "Not a video or audio file." |
| WebM | `1A 45 DF A3` at offset 0 | |
| MP3 | `ID3` at offset 0 with a sane header (below), or **two complete MPEG audio frame headers in a row**: sync bits, a valid version, layer, bitrate and sample-rate field, and a second matching header exactly one frame length later | Two bytes of sync alone. They match other data, as the review said. |
| WAV | `RIFF` at 0 and `WAVE` at 8 | |
| Ogg | `OggS` at offset 0 | |
| FLAC | `fLaC` at offset 0 | |

Text that starts with `#EXTM3U` or `<` fails this gate, so a playlist or an HTML page named `.mp4` never reaches the player.

**Structure checks.** A few header bytes can declare sizes far larger than the file. A parser that believes them allocates memory to match. For the two containers every known asset uses, the wallet checks the declared sizes itself first, reading box headers only:

- **MP4:** walk the top-level boxes. Every box must fit inside the file. `moov` must be at most 4 MiB, each `moof` at most 1 MiB. At most 4,096 top-level boxes. Inside `moov`, walk the container boxes only, to a depth of 8, at most 4,096 boxes, every child inside its parent, at most 8 tracks.
- **MP3:** an ID3v2 tag's declared length must fit inside the file and be at most 4 MiB. In Media3 1.11.1 the ID3 reader allocates a buffer of the tag's declared length before it reads the tag (read from the published `Id3Peeker` class: the length field is read, then a byte array of that size is made). The length field can say up to 256 MiB.

These numbers are proposals. PR 2 checks them against every real asset file before fixing them. For WebM, Ogg, FLAC and WAV the wallet makes no such check: their size fields are not as simple to walk. They rest on the corpus (9.4) and on 4.7. If the corpus shows one of them allocating more than 16 MiB from a declared size, that container leaves the allow-list for 4.1.6.

### 4.3 How the player is built, and why each setting

| Setting | Effect |
|---|---|
| `ExoPlayer.Builder(Context, RenderersFactory, MediaSource.Factory)` | No default media source factory, so no default extractor list and no HTTP data source is referred to (2.4). |
| `ProgressiveMediaSource` with **seven** extractors, named one by one: MP4, fragmented MP4, Matroska (WebM), MP3, WAV, Ogg, FLAC | No HLS, DASH, SmoothStreaming or RTSP code. No MPEG-TS, FLV, AMR, AVI or picture parser. |
| Fragmented MP4 is the seventh | Revision 1 named six. In 1.11.1 `Mp4Extractor` accepts unfragmented files only (read from the published class: its sniff step calls `Sniffer.sniffUnfragmented`); `FragmentedMp4Extractor` is a separate class. Browser recorders write fragmented MP4, so with six extractors real files would fail with "could not be played". Recommended: include it (question 18). The other choice is a named refusal: "This video is in a streaming layout this wallet does not play." |
| Extractor flags | `Mp4Extractor.FLAG_DISABLE_ARTWORK_METADATA`, `FragmentedMp4Extractor.FLAG_DISABLE_ARTWORK_METADATA`, `Mp3Extractor.FLAG_DISABLE_ID3_METADATA` and `FLAG_DISABLE_ARTWORK_METADATA` (all present in 1.11.1). Cover pictures and tags are not even built into objects. `FLAG_ENABLE_EMSG_TRACK` is not set. |
| Data source opens one file path only | Nothing in a file can make the player open a URL, another file or a content provider. |
| **No DRM, by name** | The source factory is given a provider that always returns `DrmSessionManager.DRM_UNSUPPORTED` (present in 1.11.1), through the five-argument constructor in 2.4. A track that declares protection is refused at gate 11 ("This file is copy-protected. This wallet does not play it."). No licence or provisioning request can be made: there is no session manager to make one, and the APK check (2.5) shows the classes that would are not in the build. The corpus has a protected file (9.4). |
| `SubtitleParser.Factory.UNSUPPORTED`; text, image and metadata track types disabled | No subtitle is parsed or drawn. The only text on screen is the wallet's own. |
| `DefaultRenderersFactory` with extension renderers off; no decoder extension in the build | Every decoder is the platform's `MediaCodec`. The wallet bundles no decoder. |
| The wallet's own `MediaCodecSelector` | The codec allow-list. See 4.4. |
| Track selector: `setExceedRendererCapabilitiesIfNecessary(false)`, `setExceedVideoConstraintsIfNecessary(false)`, `setExceedAudioConstraintsIfNecessary(false)` | All three are **true by default** in 1.11.1 (read from the published class). Left alone, the selector picks a track the limits forbid when nothing else is available. |
| `setMaxVideoSize(1920, 1080)`, `setMaxVideoFrameRate(60)`, `setMaxAudioChannelCount(8)` | A second layer under gate 11. |
| No `MediaSession`, no wake mode, no foreground service | No notification, no lock-screen or headset control, no background playback. |
| Audio focus handled by the player; pause when headphones are unplugged | Ordinary good manners. |

A file cannot run script: there is no WebView, no JavaScript engine and no bridge in this path, and `scripts/check-no-js-bridge.sh` keeps it so. The build PR adds the new files to what that script scans.

### 4.4 Refusing a track before a decoder is created

Revision 1 said "the wallet sees every track before anything is decoded, so it can refuse" and tested that "no renderer is started". It named no mechanism. The review is right that there was none: ExoPlayer selects tracks and starts decoders during prepare, and nothing in a track selector's parameters restricts codecs. `setPreferredVideoMimeTypes` is a preference, not a limit.

Two mechanisms, each enough alone. Both were checked against the published 1.11.1 classes, not from memory. Neither was run.

**A. The codec allow-list is a `MediaCodecSelector`.** `DefaultRenderersFactory.setMediaCodecSelector(...)` exists in 1.11.1. The interface has one method, `getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)`. The wallet's selector returns an **empty list** for any MIME type not on the list (H.264, VP8, VP9, AAC, MP3, Vorbis, Opus, FLAC), and for any secure or tunnelling request. For a listed type it returns what the platform's default selector returns. With no decoder offered, the renderer reports the format as unsupported (read from the bytecode of `MediaCodecAudioRenderer.supportsFormat`: an empty list returns "unsupported subtype"; the video renderer was not traced line by line). With "exceed renderer capabilities" off (4.3), an unsupported track is not selected. And if anything asked anyway, there is no decoder to create. PCM in a WAV needs no decoder.

So HEVC, AV1, Dolby and vendor audio codecs are refused on every phone, including phones that have a decoder for them.

**B. A first pass with audio and video switched off.** The player is first prepared with the audio and video track types disabled (`setTrackTypeDisabled`). The extractor runs and the wallet receives the track list (`Player.Listener.onTracksChanged`). No renderer is enabled, so no decoder is created: in 1.11.1 the codec is created inside the renderer's render path (`maybeInitCodecOrBypass` is called from `render` and from the input-format handler), and a disabled renderer is not rendered. The wallet then runs gate 11 on every track:

| Field of each track | Rule |
|---|---|
| sample MIME type | on the allow-list |
| protection (`drmInitData`) | must be absent |
| video width and height | declared, and at most 1920×1080 either way up |
| video frame rate | at most 60 when declared. **When it is not declared** (common in WebM) the track passes: a frame rate cannot be judged without decoding. The size limit and the decoder's own level limits are what bound the work. |
| audio channel count | declared, 1 to 8 |
| audio sample rate | declared, 8,000 to 192,000 Hz |
| largest sample (`maxInputSize`), when declared | at most 4 MiB |
| count | at most one video and one audio track are enabled. More than 8 tracks in all: refused. |

Only when every track has passed are audio and video enabled.

What was not verified: that a prepare with both types disabled delivers the full track list in every one of the seven extractors, and that enabling them afterwards restarts cleanly. That is PR 3's first test, and it must be seen red with the gate stubbed out. If B does not hold for a container, A still does.

### 4.5 Checks while a file plays

Gates 10 and 11 read what the container declares. The stream can say something else later. Revision 1 had no check for that. This also corrects question 7 of revision 1, which said a looping-free player "leaves a phone unlocked for at most 30 minutes". That was not true as designed: the duration gate applied only "when the file states one", and a 32 MiB low-bitrate audio file can run for many hours.

| Watched | Rule | Mechanism |
|---|---|---|
| Picture size | If the decoder reports a size over 1920×1080 at any time, stop and release. | `Player.Listener.onVideoSizeChanged` (present in 1.11.1). Said plainly: the platform decoder has already seen the larger frame by the time this fires. The wallet cannot stop the decoder from meeting it. It can stop feeding it. |
| Position | Stop when the position reaches 30 minutes, whether or not the file declared a duration. | The position ticker the scrubber needs anyway. |
| Playing time | Stop when the file has been playing for 30 minutes of clock time since the tap, whatever the position says. | A clock, not the file. This is what makes "at most 30 minutes" true (question 6). |
| Declared duration | Over 30 minutes: refused at gate 11, as before. | |
| Progress | No progress for 5 seconds on a local file: stop. | |
| Errors | Any `PlaybackException`, any decoder error: stop, release, plain message. | |
| Memory | See 4.7. | |

Corpus files for each: a stream that changes size after the first frames; an audio file with no stated duration that runs past 30 minutes; a file that declares 10 seconds and runs for an hour (9.4).

### 4.6 Decoders are attack surface: what the wallet can and cannot do

- **Parsing the container** happens in the wallet's process, in Java. A malformed file can cause an exception or a large allocation. It cannot overwrite memory.
- **Decoding** is done by the platform's codecs. Revision 1 said flatly that they "run outside the wallet's process". That is how Android is built since version 8, and it is the ground for allowing Android 9. But it was stated, not measured. It is now a measurement (below).
- **What the wallet cannot do:** fix a phone's codecs. On Android 10 and later, Google updates the software codecs through system updates. On Android 9, which is the Note 8, they stopped at the phone's last patch.
- **In-process, not isolated.** The player runs in the app's process. See 4.7 and question 17.

**The Android 9 measurement (on the Note 8, in PR 5, before question 7 is closed).** For every corpus file and every real asset:

1. Record the decoder chosen for each track (`AnalyticsListener.onVideoDecoderInitialized` and `onAudioDecoderInitialized` give its name; both exist in 1.11.1).
2. While it plays, read the wallet process's memory map (`/proc/<pid>/maps`) and show that no codec library is mapped into it.
3. Record which process hosts the codec (`ps` and `dumpsys media.codec` during playback).
4. Play one file per listed codec in an unusual profile: 10-bit H.264, High 4:4:4, VP9 profile 2, 5.1 AAC, HE-AAC v2, 24-bit FLAC. Each must either play or give "This phone cannot play this format". Seen on API 28, not assumed.

The result is a table in the PR. If step 2 or 3 shows a decoder inside the wallet's process for any file, playback on that Android version is off until the owner says otherwise. This design run could not touch a phone; nothing above has been done.

### 4.7 Memory

Revision 1 rested memory safety on three things: the 32 MiB cap, a free-heap check at the tap, and the claim that an out-of-memory error "ends playback, never the app". The review is right that none of them is enough.

- The cap bounds the file, not what the file declares.
- The floor is checked once, before the parser runs.
- Media3's loader thread does catch `OutOfMemoryError` (confirmed for this revision in the published `Loader$LoadTask` class: its exception table has an entry for it). But the heap is shared. While it is exhausted, an allocation on the sync, database or signing thread fails too, and nothing catches that one. The app also asks for a large heap (`app/src/main/AndroidManifest.xml:28`), so a hostile allocation of a few hundred megabytes can succeed and squeeze everything else.

What bounds memory now, none of it resting on the file-size cap:

1. **The wallet's own structure checks (4.2)** remove the known "declared size" allocations for MP4 and MP3 before a player exists.
2. **The player's buffers are fixed.** `DefaultLoadControl` with `setTargetBufferBytes(16 MiB)`, `setPrioritizeTimeOverSizeThresholds(false)` so the byte limit wins over the time target, and no back buffer (all three setters exist in 1.11.1).
3. **A heap watch while a player exists.** Used heap is sampled four times a second from the tap until release. Growth of more than 64 MiB over the level at the tap, or free heap under the floor, releases the player: "This file could not be opened." This is not atomic. It bounds how long pressure lasts, not whether it starts.
4. **One player at a time,** released on stop, error, leaving the screen, lock, and `onTrimMemory` at `TRIM_MEMORY_RUNNING_CRITICAL` or worse.
5. **The download holds an 8 KiB buffer,** not the file.

**The test that decides it.** The corpus gains a "declares far more than the file holds" case for each of the six containers (9.4). Each is played **while a sync is running** on the emulator. Pass means: the sync continues, no thread other than the player's loader sees an out-of-memory error, the process lives, and used heap returns to its level before the tap.

**If that test fails for any container,** the in-process player does not ship in 4.1.6 for that container. If it fails in a way the structure checks cannot fix, the separate `:media` process stops being a later idea and becomes a precondition. That is the honest reading of question 17.

### 4.8 The start-up window: a file must not look like a corrupt wallet

`BootGuard` treats a process that crashed while `restore_pending` is set as a failed restore. It then wipes the saved sync state, and the database on a repeat (`BootGuard.kt:22-45`, `:214-240`). The flag is set when the unlock starts a restore (`ui/onboarding/UnlockScreen.kt:233`, `ui/navigation/AppNavigation.kt:385`, `BootGuard.kt:179-181`) and cleared only later, by the sync service, once the saved blocks have loaded (`service/SyncService.kt:2360`, `BootGuard.kt:191-200`). In between, the wallet is unlocked and the asset screen can be opened. A crash counts when it is a Java or native crash or an ANR (`BootGuard.kt:101-105`), which is exactly what a file-triggered failure would be. So in revision 1 a stranger's file, tapped in that window, could have cost the user a re-sync from the wallet's birth.

There is a second, smaller window: the boot counter is reset 12 seconds after start (`BootGuard.kt:69`, `:162`).

**Rule: Play is refused until both are settled.** `BootGuard` gains one read-only function that answers whether the boot counter is back at zero and no restore is pending. Until it says yes, the Play button shows "The wallet is still starting. Try again in a moment." Nothing is requested and no player is built.

The lock releases the player (6.3), so a player cannot be alive when the next unlock sets the flag. PR 4 has a test for both directions.

One cost, not measured: if the sync service never reaches the point where it clears the flag (no network at all, for instance), playback stays refused for that session. PR 4 must find out whether that happens, and say so.

## 5. Privacy and data use

### 5.1 What leaves the device, and when

Before a tap: nothing new. After a tap, and after the confirmation where one applies: one `HEAD` and one `GET` for the media CID, to `api.digiscope.me` and to no other host (3.1). The request carries the CID, the device's IP address (or a Tor exit's), and the same headers the wallet's other gateway requests carry. No cookie, no referrer, no wallet identifier.

**What the gateway learns:** that this address asked for this file, so very likely holds or was sent this asset, and that its user tapped Play. DigiScope already receives the metadata and icon requests for the same asset from the same address, so the new facts are the tap and the file.

**What no one else learns,** under the recommended rule: nothing. No public gateway and no redirect target is contacted. Under the other choice in 3.1 this paragraph would have to say that a public, unpinned gateway learns the CID and the address for raw-codec files.

This changes what leaves the device. That makes it the owner's decision (question 2).

### 5.2 Tor

The player has no route of its own. It uses the shared client. What the user is told, and when:

| The user's Tor setting, and the route | What happens on a tap |
|---|---|
| Off | Direct. No Tor notice. |
| On, connected | Through Tor (`NetworkModule.kt:64-71`). A confirmation first, with the size: "Over Tor this download (N MB) can take several minutes." Continue or Cancel. |
| On, not connected, no fallback announced | The request waits up to 30 s for Tor, then fails (`NetworkModule.kt:133-140`). "Waiting for Tor. Nothing was sent." It never goes direct. |
| **On, not connected, and the app has fallen back to a direct connection** | **Question 4.** See below. |

**The fallback case.** With the Tor setting on, the sync service can announce a clearnet fallback, after which the route is direct for every request the app makes (`core/.../tor/TorRoute.kt:30-35`, `TorManager.kt:99-101`). The banner that announces it is on the wallet screen. The tap is on the asset screen. In revision 1 the player "simply follows whatever route the app is on", so a user who had turned Tor on would send this request direct and the player would say nothing. Revision 1 stated that as intended and did not ask the owner.

Three ways to handle it:

- **A. Say nothing** (revision 1). Not recommended.
- **B. Ask, every time** (recommended). The confirmation comes **before the `HEAD`**, because the `HEAD` already carries the CID and the address. It says: "Tor is not connected. This download will not use Tor. DigiScope will see your network address." Cancel is the default button. The size is shown as "up to 32 MB", since it has not been asked for yet. This is the project's own rule applied at the point of the request: nothing goes direct with the setting on until the wallet has told the user (`TorRoute.kt:8-12`).
- **C. Refuse** while the setting is on and Tor is not connected: "Waiting for Tor." The user can turn Tor off to play. Stricter. The cost: on a network where Tor never connects, media cannot be played without changing a setting, while the same asset's name and picture are already being fetched direct.

**The route is part of what was agreed.** Whatever was shown, the download is tied to it (3.6): a change of route between the confirmation and the `GET`, or during the download, stops it. The partial file is deleted and the card returns to "before the tap".

DigiScope rate-limits by IP address (section 7.2). Over Tor many users share an exit, so a 429 is possible (3.7).

### 5.3 Metered networks

On tap, if `ConnectivityManager.isActiveNetworkMetered` is true, the wallet asks first, with the agreed size from the `HEAD`: "Play using mobile data? This file is N MB." If the size is not known: "…up to 32 MB." Always asked on a metered network, whatever the size (question 16).

The number shown is the number enforced (3.4). One confirmation is one download (3.5). If the network becomes metered after an unmetered download began, the download is not stopped; the consent was the tap.

When more than one notice applies (metered and Tor, for instance) they are shown in one confirmation, not one after another.

No preloading of any kind. The list never fetches media. The detail screen fetches media only after the tap.

### 5.4 The cache

**The decision this design has to face.** IPFS pictures have no disk cache in this app, on purpose. The image loader is built with `diskCache(null)` (`DigiByteApp.kt:130`), and the comment above it gives the reason: a disk hit does not run the fetcher again, so "verified bytes wouldn't be re-authenticated on read" (`DigiByteApp.kt:113-116`). Revision 1 proposed a media cache that replays a file from disk with no check at all, kept between sessions, and did not mention that it reversed that decision. Its question weighed privacy only.

**What can be checked when a cached file is opened:**

- **A raw-codec file can be checked again against its CID.** The file is hashed again on every open, before a player exists. 32 MiB of SHA-256 is well under a second on a phone (not measured).
- **A dag-pb file cannot be checked against its CID. Not at download, and not on open.** That is every media file found so far. Nothing the wallet stores next to such a file can make it checkable: whoever can change the file can change a note beside it.
- What can be done for dag-pb is weaker and should be called what it is: the wallet can remember, **in memory**, the SHA-256 of what it downloaded in this run, and compare on open. That shows the file is the one this process received from the pinned server. It says nothing about the CID. It is lost when the process ends.

**Two choices for the owner (question 5):**

| | A. Kept while the app's process lives (recommended) | B. Kept between sessions |
|---|---|---|
| Lifetime | Every `asset_media*` directory is emptied when the process starts. A file lives until the process ends. It survives a lock, as the picture memory cache does. | Until evicted, wiped, or cleared by the system. |
| Check on open, raw | Re-hashed against the CID. | Re-hashed against the CID. |
| Check on open, dag-pb | Compared with the digest held in memory from the download. A mismatch deletes the file. | **None is possible.** A file changed at rest is played under "Served by DigiScope". |
| Fits the existing decision for pictures | Yes: nothing is replayed that this run did not fetch. | No. It needs the owner to say the picture rule does not apply to media, and why. |
| Cost | After the system kills the app in the background (common on Samsung), a song is downloaded again: 7 MB, and a mobile-data prompt. | Someone who can read or write the app's private storage can see what was played, and can change it. |
| At rest | Files are left behind by a killed process until the next start. | Files stay. |

Recommended: **A**. It is the stricter one, it matches what the project already decided for pictures, and its cost is data, not safety. If the owner wants B for the data it saves, the integrity line for a dag-pb file replayed from an earlier session should say so.

**Properties under either choice:**

| Property | Decision |
|---|---|
| Place | `context.cacheDir`, app-private. Never shared storage, never the gallery, never `MediaStore`. |
| Directory name | `asset_media` + the wallet's storage suffix, built by `storageSuffix(scope, StoreScope.WALLET_AND_NETWORK)` (`core/.../wallets/WalletScope.kt:53-62`). The legacy wallet on mainnet: `asset_media`. Wallet `<id>` on testnet: `asset_media_<id>_testnet`. |
| Files | `<cid>.media` (finished and accepted) and `<cid>.part` (download in progress). **No `.state` file.** The label is derived from the CID (3.9). The container is read again from the first bytes on every open. |
| Rename | A `.part` becomes `.media` only after every gate up to the structure checks has passed, and only while the store is open (5.5). |
| Limit per directory | 96 MiB. Before a download, least recently played files are deleted until the new file fits. |
| **Limit across all wallets** | Revision 1 had none: 96 MiB per wallet and per network, times any number of wallets. Under A only the running wallet's directory can hold anything, so the total is 96 MiB. Under B the total is 192 MiB; over it, the oldest files of other wallets go first. Deleting is not reading: a wallet still never opens another wallet's file. |
| **Free-space floor** | No download starts unless the cache partition has the agreed size plus 256 MiB free. "Not enough free storage to play this right now." A 32 MiB write on a nearly full phone competes with the wallet's own saves (block window, ledger, database) on the same partition. 256 MiB is a judgement, not a measurement; PR 2 states what those saves need at their largest. |
| Per wallet | One directory per wallet and network. A wallet never reads another wallet's directory. |
| Stale parts | Every `.part` in every `asset_media*` directory is deleted at app start and when a download ends for any reason. |
| The system clears the cache | Expected. A missing file means "download again on the next tap". |

### 5.5 Wipe and wallet removal

Revision 1 added one line to the store list and stopped there. Three things were missing.

**1. The eraser has never handled the cache directory.** It deletes files under `filesDir` only (`WalletStoreEraser.kt:204-209`) and its read-back looks only there (`:168-178`). The media cache is the first per-wallet store in `cacheDir`. So:

- `WalletStores` ("the one list of per-wallet stores", `WalletStores.kt:3`) gains `WALLET_AND_NETWORK_CACHE_DIRS = listOf("asset_media")`.
- `WalletStoreEraser.eraseStores` (`:101-111`) deletes those directories with what is in them. Only plain files are expected inside; anything else is reported as left, not followed.
- `otherStoresLeft` (`:168-178`) reports a directory that still exists. So an erase that leaves media behind is not a verified erase.

**2. Order: a download can outlive the erase.** An in-flight download can create a directory, or rename a `.part`, after the eraser has read back "nothing left". So:

- The wipe already stops every running session before it erases (`WalletManager.kt:1071-1073`). The download holder registers as one (3.6). Its `stopForWipe` cancels, **waits** for the writer, deletes the `.part`, and **closes** the store.
- A closed store creates no directory and renames no file for the rest of the process.
- The fetcher creates a directory through one function only. That function refuses when the store is closed, when the wallet is not the one this process runs, and when the registry has the wallet marked for removal.
- The eraser itself asks the holder to stop and waits before it deletes or reads back. The order then does not depend on each caller remembering.

**3. The wipe-owed record, and the legacy wallet.** A full wipe erases the legacy wallet's stores through its own list of steps (`WalletManager.kt:1107-1115`) and every other wallet's through the eraser (`:1116-1118`). A wallet whose stores could not be cleared stays on the `dgb_wipe_owed` record and is listed again by the next launch's wipe (`:1100`, `:1122-1132`; `ActiveWallet.kt:47-59`).

- For another wallet this already works once point 1 is done: a directory left behind makes `eraseStoresOnBothNetworks` report failure, so the wallet's id stays owed and the next launch tries again.
- For the legacy wallet, and for a directory that no list names any more, the full wipe gains one step: delete every entry of `cacheDir` named `asset_media` or starting with `asset_media_`, then read back that none is left. The step is part of the wipe's verdict.
- Removing one wallet erases that wallet's directories on both networks, and no other.
- Finishing a removal at the next launch (`WalletHousekeeping.kt:49`, `:64`) goes through the same eraser, so it covers the directories too.

The names are reserved in the list in the first PR that can write them (PR 2).

### 5.6 Screenshots

Unchanged. The asset detail screen does not set `FLAG_SECURE` today (it is held only by the sensitive screens, through `ui/components/SecureWindow.kt`), and the player does not add or remove it. The full-screen video is a dialog, and so a window of its own (`SecureWindow.kt:46`); it gets no flag either.

## 6. The screen

### 6.1 Detail view

Under the asset's header, one **media card** per playable entry (at most four are shown; section 8).

Before the tap:

- **Poster.** The asset's picture, loaded the way it is today (`ui/asset/AssetIcon.kt:32-67`). For an asset with no picture: a plain tile with a film or note glyph. No frame is pulled from the video and no cover is read from the song.
- **Type.** "Video · MP4" or "Audio · MP3", from the declared type, shown as declared.
- **Size.** Shown as soon as it is known: at once when the file is already in the cache, otherwise after the tap. Asking for it earlier would be a request before the tap.
- **A Play button**, at least 48 dp.

After the tap, in place, the card becomes: the confirmation where one applies; progress with Cancel while downloading; then the player.

- **Video:** the picture (16:9 box, letterboxed, never cropped), play/pause, a scrubber with elapsed and total time, mute, full screen.
- **Audio:** the poster stays; play/pause, scrubber, times, mute.
- **The integrity line** from 3.9, always visible under the controls.
- **Full screen (video only):** a dialog built like `AssetMediaViewer` (`ui/asset/AssetMediaViewer.kt:50-55`): black, close button, back dismisses. The same player moves to it; playback does not restart.

### 6.2 What stops a file that is playing, and the choice it forces

Revision 1 kept the screen on for video only, and described the auto-lock timer as the one thing that could cut a file off. Both halves were incomplete.

Three things end playback without the user doing anything:

1. **The display goes to sleep.** Display off stops the activity. `onStop` locks the wallet (`MainActivity.kt:442-462`) and the player is released. With a 30 second display timeout, a song ends after 30 seconds unless the user keeps touching the screen. This happens whatever is decided about the auto-lock.
2. **The auto-lock timer.** It locks after a time with no touch while the app is in front (`MainActivity.kt:227-245`). Its default is **60 seconds**, the shortest option (`core/.../settings/AutoLockPrefs.kt:44-45`). Playback is not a touch. So with default settings a video is cut off after one minute too, even with the screen held on.
3. **The 30 minute limit** of 4.5.

So as revision 1 stood, with default settings, nothing longer than about a minute could be watched or heard without touching the screen. The songs found so far are each several megabytes, which is several minutes. This is the real choice, and it is the owner's (question 6):

| | A. Playback holds the screen and the lock (recommended) | B. Nothing is held |
|---|---|---|
| Screen | Kept on while a video **or a song** is actually playing. Not while paused, not after it ends. | Kept on for video only, as in revision 1. |
| Auto-lock | Playing counts as activity. Paused does not. | Playing does not count. |
| Longest time the wallet stays unlocked with no touch | 30 minutes of playing (the clock limit in 4.5, which revision 1 did not have), plus the auto-lock time. One file, no loop, no playlist. | The auto-lock time. |
| What the user sees | A song plays to its end. | A song stops when the display sleeps or after the auto-lock time, whichever comes first. The card says so before the tap: "Playback stops when the screen turns off or the wallet locks." |
| What an unlocked wallet exposes in that time | Balances, addresses and history, to someone who picks up a lit phone. Not a spend: every send, Digi-ID approval and sweep asks for the PIN or biometric at the moment of action (CLAUDE.md, "Lock + spend gate"). | Nothing more than today. |

Recommended: **A**. Without it the feature does not do what a person expects of a player, and what it gives up is bounded and is not the ability to spend. B is the stricter choice and is a fair one; if it is chosen, the sentence on the card is not optional.

### 6.3 What it does not do, on purpose

- **No background playback.** Audio plays while the detail screen of that asset is in front. Leaving the screen stops it.
- **No media notification, no lock-screen controls, no headset buttons.** There is no `MediaSession`.
- **No picture-in-picture, no casting, no download or share button.**

### 6.4 Lock and lifecycle

The player and the download follow the same events. The download's are in 3.6.

- **App to background, or display off.** The activity locks the wallet in `onStop` (`MainActivity.kt:442-462`). The player stops and is released on `ON_STOP`, directly, not by asking the dialog to close: Compose does not recompose while the app is stopped, so a dismissal alone would leave audio playing. (The September branch found this the hard way.)
- **Auto-lock.** The player observes `walletState`; when it is no longer `Unlocked`, it stops and releases. After unlocking, the card is back in its "before the tap" state.
- **Leaving the detail screen, switching wallets, wipe, wallet removal:** stop and release. A wipe also deletes the file (5.5).
- **Rotation:** playback continues; the position is kept.
- **A phone call or another app taking audio focus:** pause. It does not resume by itself.

### 6.5 Errors, in plain words

Each is one sentence. "Try again" is shown only where it makes a real request (3.7).

| Situation | Text (English) |
|---|---|
| Not on the gateway (remembered) | This file was not on the server when the wallet last asked. It will ask again after HH:MM. |
| Too large | This file is too large for the wallet to play (N MB; the limit is 32 MB). |
| Larger than the server said | The file was larger than the server said. Nothing was kept. |
| Download broke or was stopped | The download stopped. Try again. |
| Tor not connected | Waiting for Tor. Nothing was sent. |
| Server busy (429) | The server is busy. Try again in a few minutes. |
| Hash mismatch (raw CID) | This file does not match its file ID. Nothing was kept. |
| File ID of a kind the wallet cannot check | This wallet cannot check this kind of file ID. |
| Not media | This is not a video or audio file this wallet can play. |
| Codec not allowed, or not on this phone | This phone cannot play this format. |
| Protected file | This file is copy-protected. This wallet does not play it. |
| Too big a picture, or too long | This video is larger than the wallet plays. / This file is longer than the wallet plays. |
| Would not open, or broke while playing | This file could not be played. It may be damaged. |
| Low memory / low storage | Not enough memory to play this right now. / Not enough free storage to play this right now. |
| Wallet still starting | The wallet is still starting. Try again in a moment. |
| No CID | This file is not on IPFS. This wallet does not fetch it. |
| A CID with a path | This file is inside a folder on IPFS. This wallet does not fetch it. |

Logs name the true cause. Anything from the file or the metadata reaches a log only through `LogSafe.text`. The wrong log line revision 1 pointed at ("possible gateway tampering" for an over-size file, `ui/asset/IpfsFetcher.kt:42-45`) is fixed by PR #308; it has a row in 8.3.

### 6.6 Accessibility

- Every control has a content description: Play, Pause, Mute, Unmute, Full screen, Close, Cancel download.
- The scrubber is a real slider with a state description in words ("1 minute 5 seconds of 3 minutes 6 seconds") and works with TalkBack's volume-key and swipe adjustments.
- Download progress is a progress semantic with a percentage when the size is known.
- Errors and confirmations are a polite live region, so TalkBack reads them once.
- 48 dp targets. No meaning carried by colour alone. Text scales with the system font size.
- Full-screen controls do not auto-hide while TalkBack is on.

### 6.7 Strings

About 50 new keys in `app/src/main/res/values*/strings_wallet.xml` (revision 1 said 35; this revision adds the confirmations and the new refusals), in all 13 languages of `AppLocale.SUPPORTED` (`core/.../locale/AppLocale.kt:50`), in the same commit as the UI, with the Android qualifiers (`values-b+pt+BR`, `values-b+fil`). `LocaleResourceParityTest` covers missing keys. `ui/asset/AssetDetailScreen.kt` is already in `OnboardingHardcodedStringTest.COVERED` (`app/src/test/.../ui/locale/OnboardingHardcodedStringTest.kt:65`); the new player file is added to it. The Tor and mobile-data confirmations are statements about privacy and cost; like the seed warnings, they want a native speaker before they are called done.

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


## 8. The prerequisite PR (#308, ASSET-MEDIA-FIXES), and who delivers what

PR #308 is separate and is being corrected in parallel with this revision. It fixes what is wrong today whether or not a player exists: the parser picked the picture by entry name and never read `mimeType` (`core/.../ipfs/AssetMetadataService.kt:362-380`), so a song became the "image"; every other entry was dropped; a file that did not decode was downloaded again on every draw; and the hero opened an empty viewer (`ui/asset/AssetDetailScreen.kt:154-163`).

**PR #308's code is the one copy of the model.** This section says what the player needs from it. If #308 lands with other names or other limits, this section is corrected to match it, not the other way round.

### 8.1 The model the player needs

**Corrected.** Revision 1 wrote `cid: String` (never null, "never a URL") and, three lines later, kept an entry "with `kind = OTHER` and no `cid`". Those cannot both hold. And turning every entry without a CID into `OTHER` threw away its kind, which the screen needs in order to say "this video is not on IPFS". The properties, as #308 is being corrected to provide them:

| Property | Rule |
|---|---|
| `kind` | `IMAGE`, `AUDIO`, `VIDEO`, `OTHER`, or `UNKNOWN` (nothing declared). From the declared MIME type first, the name's extension second. **Kept for every entry**, with or without a CID. |
| `cid` | **Nullable.** A bare CID that `IpfsClient.normalizeCid` accepts (`IpfsClient.kt:78-92`), or null. Never a URL, never a CID with a path. |
| fetchable | True only when `cid` is not null and the entry's value named that CID and nothing more. An `http(s)` URL is kept as an entry and marked not fetchable. So is a CID followed by a path. |
| declared MIME type | The issuer's word, display-safe, used for a label only. |
| name | Display-safe, short. |

What the screen does with each:

| Entry | Card |
|---|---|
| `VIDEO` or `AUDIO`, fetchable | A media card with Play. The first four such entries get one. |
| `VIDEO` or `AUDIO`, an `http(s)` URL | Named by its kind: "Video. This file is not on IPFS. This wallet does not fetch it." No Play. |
| `VIDEO` or `AUDIO`, a CID with a path | "This file is inside a folder on IPFS. This wallet does not fetch it." No Play. "Copy file ID" copies the CID. |
| Anything else | The row from 1.3. |

The player reads nothing else from the metadata. It does not parse a URL, and it does not decide what is a CID.

Two things the model cannot know, and does not try to: whether a CID names a directory (3.3), and whether the file is what its declared type says (4.2). Both are settled from the bytes.

### 8.2 Other things the player relies on from #308

1. **Classify by MIME type first.** An entry with `mimeType` `audio/*` or `video/*` is never the picture, whatever its name is.
2. **Keep the media entries,** bounded and cleaned, in one nullable column on `asset_metadata`: schema 12 → 13, migration registered, exported schema committed, migration test. #308 keeps up to 16 entries (revision 1 said 8; #308's number stands). The player PRs then need **no** schema change.
3. **No audio or video as the picture.** No image request is made for an MP3.
4. **No repeated download** of a file that did not decode, and **no empty viewer**.
5. **Rows written before #308** are read once more for their media. Until then the asset shows no media card.

### 8.3 Who delivers what

Revision 1 handed three jobs to "the prerequisite PR" in passing, in other sections, and they were not in this list. Nothing is assigned in passing now. "In #308" means seen in its diff at 97f5d92a8. "Asked of #308" means it is not in that diff and is part of the correction under way; if it does not land there, the row moves to the PR named in the last column and this table is updated.

| Item | #308 fixes | PR 2 fetch | PR 3 engine | PR 4 screen | PR 5 check | If not in #308 |
|---|---|---|---|---|---|---|
| Picture chosen by media type | in #308 | | | | | |
| Media entries kept; schema 12 → 13 | in #308 | | | | | |
| Old rows read again for media | in #308 | | | | | |
| A non-picture is downloaded once | in #308 | | | | | |
| No empty viewer | in #308 | | | | | |
| A "Media" row on the detail screen (kind and known size, no Play) | in #308 | | | replaced by the media card | | |
| The over-size log line no longer says "possible gateway tampering" (`ui/asset/IpfsFetcher.kt:42-45`) | in #308 | | | | | |
| Model: nullable `cid`, `kind` kept, not-fetchable entries for `http(s)` URLs and for a CID with a path (8.1) | asked of #308 | | | | | PR 2 |
| A CID with a path is refused, not fetched as the bare CID. Today `IpfsFetcher.extractCid` drops the path (`ui/asset/IpfsFetcher.kt:65-68`) and the bare CID is fetched. | asked of #308 | | | | | PR 2 |
| The "already authenticated" comment corrected for dag-pb (`ui/asset/IpfsFetcher.kt:22-23`) | asked of #308 | | | | | PR 2 |
| A badge in the asset list for an asset that carries media (1.2). In no PR today. | | | | PR 4, with its content description in 13 languages | | |
| `CidVerifier.kindOf`, and verifying a streamed digest (3.3) | | PR 2 | | | | |
| `IpfsClient.kt:286` uses `kindOf` instead of the text prefix (3.3). Changes the image path. | | PR 2 | | | | |
| `AssetMediaFetcher`; `BoundedBody.copyTo`; the agreed-size rule; its own miss memory and the "ask again after" time | | PR 2 | | | | |
| The download holder: route binding and every stop in 3.6 (the core part) | | PR 2 | | wired to the screen and the lifecycle | | |
| First-bytes and structure checks (4.2) | | PR 2 | | | | |
| Cache directories, both limits, the free-space floor, start-up cleanup (5.4) | | PR 2 | | | | |
| `WalletStores` entry, eraser handling of `cacheDir`, the full-wipe step, the session stop (5.5) | | PR 2 | | | | |
| The Media3 dependency; `WAKE_LOCK` removal; the dependency diff; the three size figures | | | PR 3 | | | |
| The player wrapper: constructor, seven extractors, codec selector, first pass, no DRM, checks while playing, heap watch | | | PR 3 | | | |
| `scripts/check-media-apk.sh` in CI | | | PR 3 | | | |
| The start-up gate (`BootGuard` read-only function and the refusal, 4.8) | | | | PR 4 | | |
| The media card, the confirmations, the screen and lock behaviour of question 6, errors, accessibility | | | | PR 4 | | |
| Strings in 13 languages; the player file and `AssetMediaViewer.kt` added to the hardcoded-string gate (its "Close" and "Asset artwork, full screen" are still hardcoded English, `AssetMediaViewer.kt:70`, `:83`) | | | | PR 4 | | |
| The hostile-file corpus, the emulator script, the Note 8 measurement (4.6), the under-sync memory test (4.7) | | | | | PR 5 | |
| The gateway note (section 7) | another team | | | | | |

## 9. Delivery

### 9.1 PRs, in order

| # | PR | What | Depends on |
|---|---|---|---|
| 0 | #308 ASSET-MEDIA-FIXES (separate, being corrected) | Section 8. Bug fixes that ship in 4.1.6 with or without a player. | none |
| 1 | #307, this document | Design, questions, gateway note. | none |
| 2 | MEDIA-FETCH (core) | The PR 2 column of 8.3. Unit tests for every gate. No UI, no new dependency. Nothing calls it yet. | 0; questions 2, 3, 5 |
| 3 | MEDIA-ENGINE (app) | The PR 3 column of 8.3. No screen yet. | question 1 |
| 4 | MEDIA-UI (app) | The PR 4 column of 8.3. | 2, 3; questions 4, 6 |
| 5 | MEDIA-CHECK (test) | The PR 5 column of 8.3. | 3, 4; closes question 7 |
| G | Gateway changes | Section 7. Another team, another repository. Any time; not a blocker. | none |

Each code PR runs the full unit suites before its final commit (`./gradlew --continue --no-build-cache :core:testMainnetDebugUnitTest --rerun :app:testMainnetPlayDebugUnitTest --rerun :app:testMainnetSideloadDebugUnitTest --rerun`) and gets the independent review and green CI that `docs/development-process.md` requires.

### 9.2 What needs the owner's decision

Section 10, group A: seven questions. No code PR starts before the ones it depends on (the table above) are answered. Group B is what the work proceeds on unless he says otherwise.

### 9.3 Unit tests

Every test is written first and seen red against the code before its fix; the PR says how (for a new gate: the test fails while the gate is a stub that lets everything through).

- **CID kind:** CIDv0 is dag-pb; a dag-pb CIDv1 in base32, base58 and base16 is dag-pb in all three, and none of them can ever give the "checked" label; raw with sha2-256 and with sha2-512 are both checkable; raw with another hash function, and any other codec, are refused with no request. `IpfsClient` gives a base58 dag-pb CID the same trust as a base32 one.
- **Which host:** the fetcher's gateway list is the trusted list and nothing else; a public gateway is never asked for any kind of CID; a 3xx is not followed.
- **Size:** an agreed size over the cap is refused with no body byte read; a `GET` that declares a different length than the `HEAD` is refused unread; **a server that declares 1 MB and sends 40 MB is stopped at 1 MB plus one byte and nothing is kept**; no declared length stops at the cap plus one; a short body fails; `Content-Encoding` present is refused.
- **One consent:** a failure makes no second request by itself; a hash mismatch makes no second request.
- **Stops:** each row of 3.6 has a test. A route change during a download cancels the call and deletes the `.part`. A route change between the confirmation and the `GET` means the `GET` is not sent. A connection whose proxy is not the agreed kind fails before the request is written. Lock, `ON_STOP`, leaving the screen, wallet switch and wipe each stop it. After `stopForWipe` no directory is created and no file is renamed.
- **Misses:** a 404 and a 400 are remembered, in the fetcher's own memory and not in `IpfsClient`'s; a picture refused for size does not make the same CID "not here" for the player; a remembered miss makes no request and reports when it ends; 429 and 503 are not remembered.
- **Tor:** `TorNotReadyException` ends the attempt with nothing sent; with the setting on and the fallback announced, nothing is sent before the confirmation (question 4).
- **First bytes and structure:** each allowed container passes; PNG, HTML, `#EXTM3U`, ZIP, PDF, an empty file and a 3-byte file fail; HEIC and AVIF fail by brand; two bytes of MPEG sync followed by noise fail; an MP4 box larger than the file, a `moov` over the limit, a nesting over the limit, and an ID3 length over the file each fail.
- **Cache:** the directory name for legacy, for a wallet id, and on testnet; eviction order; both limits; the free-space floor; `.part` cleanup; two wallets do not see each other's files; a raw file changed on disk is refused on open; a dag-pb file changed on disk is refused on open under choice A; no `.state` file is ever written.
- **Wipe:** `eraseStores` deletes the directory; a directory that cannot be deleted makes the erase report failure and keeps the wallet on the owed record; the full wipe removes every `asset_media*` entry, the legacy one and an unknown one included, on both networks; a download in flight when the wipe starts leaves nothing behind.
- **Track gate:** a 3840×2160 format, an unlisted codec (HEVC, AV1) on a device that reports a decoder for it, a protected track, a 31 minute duration, nine channels, two video tracks: each gives its message, and **no decoder is created** (asserted on the codec selector and on the renderer state, not on a log line).
- **While playing:** a size change over the limit stops it; position past 30 minutes stops it with no declared duration; 30 minutes of clock time stops it.
- **Start-up gate:** Play is refused while a restore is pending and while the boot counter is not zero.
- **Source and artifact gates:** the names of 2.5 are absent from the minified APK, and the script fails on an APK built the wrong way; no `MediaSession`, `WebView` or `addJavascriptInterface` in the player's packages; the merged manifest has no `WAKE_LOCK`; `NetworkSourceGateTest` still passes with the fetcher in it.
- **Locale:** the two existing gates, with the new files in `COVERED`.

### 9.4 Hostile-file corpus

Generated by a script (ffmpeg plus a few byte edits), kept under test resources, each with its expected result. Served to the fetcher from a local server in unit tests, and played on an emulator.

| File | Expected |
|---|---|
| MP4 cut off in the middle; cut off inside the header | Plays to the cut then stops cleanly, or "could not be played" |
| Server declares 40 MB | Refused unread |
| Server declares nothing and never stops sending | Stopped at cap + 1 |
| **Server declares 1 MB and sends 40 MB** | **Stopped at 1 MB + 1 byte. "Larger than the server said." Nothing kept, nothing played.** (Revision 1 said "stopped at the declared length; file fails or plays its first MB". The reader did not do that; see 3.4.) |
| `HEAD` says 1 MB, `GET` declares 40 MB | Refused unread |
| gzip answer that inflates to 1 GB | Refused on `Content-Encoding` |
| HTML, PNG, ZIP, PDF, each declared `video/mp4` | "Not a video or audio file" |
| A HEIC picture and an AVIF picture, each declared `video/mp4` | "Not a video or audio file", by brand |
| A real MP4 declared `image/png` | Handled by what it is |
| A fragmented MP4 from a browser recorder | Plays (or the named refusal, by question 18) |
| `#EXTM3U` playlist pointing at an outside URL | Refused; **no request leaves** |
| MP4 with a data reference to an `http://` URL | Plays or fails; **no request leaves** |
| A protected (encrypted) MP4 | "Copy-protected". **No request leaves.** |
| An HEVC file and an AV1 file, on an emulator that has decoders for them | "This phone cannot play this format". No decoder created. |
| 8192×4320 video; header claiming 16384×16384 | "Larger than the wallet plays" |
| **A video that declares 1280×720 and switches to 3840×2160 after the first frames** | Stopped at the change |
| Atom size `0xFFFFFFFF`; atoms nested 10,000 deep; 1,000 tracks | Refused by the structure check, before a player exists |
| **One "declares far more than the file holds" file for each of the six containers** | Error message; **played while a sync runs**: sync continues, no out-of-memory on any other thread, process alive, heap back to its level (4.7) |
| Duration claimed as 100 hours | "Longer than the wallet plays" |
| **An audio file with no stated duration that runs for 45 minutes; a file that declares 10 seconds and runs for an hour** | Stopped at 30 minutes |
| MP3 with a 30 MB ID3 tag; MP3 whose ID3 header claims 200 MB in a 1 KB file | Refused by the structure check |
| WAV claiming 4 GB of samples in a 1 KB file | Stops cleanly |
| WebM with absurd element lengths; Ogg with chained streams | Error or plays; process alive |
| MP4 with a subtitle track; MP3 with a cover picture | Plays; no text or picture from the file is shown |
| A valid 32 MiB file; a valid file one byte over | Plays; refused |

"No request leaves" is asserted in unit tests by the data source refusing the URI, and on the emulator by the local test gateway's request log showing only the expected `HEAD` and `GET`.

### 9.5 Emulator check against the real assets

The investigation's probe activity is the pattern: a debug-only activity that calls the production `AssetMetadataService` for an asset id and composes the production UI, with no wallet needed. PR 5 turns that into `scripts/asset-media-check.sh`, using `scripts/test-helpers/device.sh` so it can only ever drive an emulator, on API 28, 33, 34 and 35 (the project's AVDs). For assets 5376 (video), 5252 (picture plus song) and 5361 to 5364 (songs): the card appears; nothing is requested before the tap; after the tap the file plays; the scrubber moves; home-button stops the sound and the download; logcat shows no crash and one `HEAD` and one `GET`. Each ends PASS, FAIL or SKIP with a reason; a skip is not a pass. `androidTest` runs in no CI job, so this is a script and a recorded result, not a claim of CI coverage.

### 9.6 Device checks

- **Note 8 (Android 9, API 28):** the measurement of 4.6, in full; every real asset; the whole corpus; a phone call arriving during a song; headphones pulled out; the display timing out during a song and during a download; auto-lock firing during playback; Tor on, and Tor switched on in the middle of a download; a metered connection.
- **Moto G 5G:** the same list, short form.
- **S25 Ultra (Android 15, 16 KB pages):** a release-signed build only, owner's hands. One video, one song. Confirms no native library arrived.
- Heap before and after ten play and stop cycles, to show the player is released.

### 9.7 What this revision checked and did not check

**Did.** Opened every source line cited for a claim this revision changed. Downloaded the seven published Media3 1.11.1 AARs and the POMs of Media3 and Guava, and read from them: the resource directories and strings; the manifest permissions; the absence of `jni/`; the transitive dependency list; the constructors of `ExoPlayer.Builder` and `ProgressiveMediaSource.Factory` and what each refers to; `MediaCodecSelector` and `DefaultRenderersFactory.setMediaCodecSelector`; the track selector's parameters and their defaults; `DrmSessionManager.DRM_UNSUPPORTED`; the extractor classes and their flags; that `Mp4Extractor` sniffs unfragmented files only; that the ID3 reader allocates from the declared tag length; that the loader catches `OutOfMemoryError`; the `DefaultLoadControl` setters; the listener callbacks named in 4.5 and 4.6. Read PR #308's description and its diff at 97f5d92a8.

**Did not.** Run any code: no Gradle task, no unit suite (no code changed), no emulator, no phone. Build the app with Media3. Run the player, so the two mechanisms of 4.4 are read from class files, not observed. Trace the video renderer's "unsupported" path line by line (the audio renderer's was). Re-run the size probe with seven extractors. Measure the built resource cost or the compiled-code cost. Check which annotation artifacts the app already has. Verify the IPFS block-size limit quoted in 3.1 (from memory). Verify OkHttp's handling of a wrong `Content-Length` under HTTP/2 (the design does not rely on it). Verify against OkHttp 4.12.0 that a network interceptor sees the connection before the request is written (3.6 relies on it; PR 2's test must show it). Test any public gateway. Read Google's Media3 release notes. Look at iOS.

**Section 7 is unchanged from revision 1,** word for word. A copy of it exists in the private records, and changing one without the other would make two versions. Under the recommended answer to question 3 one sentence of 7.1 becomes stronger (the server is the only source for all media, not only for dag-pb files); that edit is held until the owner has answered.

## 10. Questions for the owner

Each has a recommended answer. The number revision 1 gave a question is in brackets.

### A. Needs your decision before anything is built

1. **The dependency.** [was 1] Add `androidx.media3:media3-exoplayer:1.11.1`? It brings six Media3 modules, Guava, `failureaccess`, and five annotation-only artifacts (the full list is in 2.2), about 1 MB of code, some unused resources, and a `WAKE_LOCK` permission that the build removes. Recommended: **yes.** The alternative, the platform player, gives the wallet no way to refuse a track before it is decoded.

2. **What leaves the device.** [new as a question; was 9.2] After a tap, the wallet sends one size request and one download request for the file to `api.digiscope.me`, with the file's ID and the device's address (or a Tor exit's), and receives up to 32 MiB of a stranger's file. Nothing before the tap. One tap is one download, of at most the size shown. Recommended: **yes, on those terms.**

3. **Public gateways for media.** [new] Ask only the pinned DigiScope server for media, for every kind of file ID? Recommended: **yes, DigiScope only.** The public gateways are not pinned, they follow redirects, and they learn the file ID and the address. The cost: a hash-checkable (raw-codec) file that DigiScope lacks cannot be played, although a public gateway could serve it and the wallet could check it. No such media file has been found. The other choice is in 3.1.

4. **Tor on, but the app has fallen back to a direct connection.** [new] What should a tap do? Recommended: **ask every time, before anything is sent:** "Tor is not connected. This download will not use Tor." with Cancel as the default. The stricter choice is to refuse until Tor connects; its cost is that media cannot be played on a network where Tor never connects, while the asset's name and picture are fetched direct anyway. Saying nothing, as revision 1 did, is not recommended.

5. **The cache.** [was 4, rewritten] Keep a played file only while the app's process lives, or between sessions? Recommended: **only while the process lives.** Every file found so far is of the kind the wallet cannot check against its file ID, at download or later. The project already decided to keep no disk cache for IPFS pictures for this reason (`DigiByteApp.kt:113-116`, `:130`). Kept for the process only, a file can be compared on each open with what this run downloaded. The cost: after the system kills the app, a song is downloaded again. Keeping files between sessions saves that data and gives up the check; it would be a decision to treat media differently from pictures.

6. **Audio, the display and the auto-lock.** [was 7, with a new half] With default settings a song stops when the display sleeps, and a song or a video stops after 60 seconds without a touch (the default auto-lock). Should playing hold the screen on and count as activity? Recommended: **yes, for video and audio alike, while actually playing, for one file, never looping, and for at most 30 minutes.** In that time an unlocked wallet shows balances and history to someone who picks up the phone; it cannot spend without the PIN or biometric. The stricter choice is to hold nothing and say on the card that playback stops when the screen turns off or the wallet locks. Note: revision 1's sentence that the first choice "leaves a phone unlocked for at most 30 minutes" was not true then. It is true now only because of the clock limit added in 4.5.

7. **Android 9.** [was 11] Its codecs get no more security fixes. Allow playback there? Recommended: **decide after the measurement in 4.6, and allow it only if that measurement shows on the Note 8 that every decoder runs outside the wallet's process and the whole corpus passes.** What is asked now is whether a pass is enough. The stricter choice is no Play button below Android 10, whatever the measurement shows.

### B. Proceeding on these unless told otherwise

8. **32 MiB is the size limit.** [was 2] It is 3.5 times the largest file known, and one constant.
9. **Download, then play; no streaming,** even after the gateway honours `Range`. [was 3]
10. **The integrity line** reads "Served by DigiScope. This wallet cannot check this file against the asset's file ID." for today's files, and "Checked against the asset's file ID." only for a hash-checked one. [was 5] It is a statement to users, so say if the wording should change.
11. **A dag-pb file DigiScope lacks cannot be played.** Real dag-pb verification is its own item after 4.1.6. The gateway note goes to the server's team on your word. [was 6]
12. **Animated GIF and WebP are not in the player PRs.** First frame and a label in 4.1.6. [was 8]
13. **No "Open in browser"** for files the wallet does not open. "Copy file ID" only. [was 9]
14. **The `WAKE_LOCK` permission Media3 adds is removed,** with a test. [was 10]
15. **The September branch `feature/asset-media-playback` is not merged.** It is not deleted either until you say so. [was 12]
16. **On a metered network the wallet always asks,** with the size, whatever the size. [was 13]
17. **The player runs in the app's process, on one condition.** [was 14] The memory test in 4.7 (hostile files played while a sync runs) must pass. If it fails in a way the wallet's own checks cannot fix, a separate process becomes a precondition and this comes back to you.
18. **Fragmented MP4 is played,** through a seventh extractor. [new] The other choice is a named refusal.
19. **One tap is one download,** stopped at the agreed size. No automatic retry, no second gateway. [new]
20. **A download stops** when the route changes, the wallet locks, the app leaves the foreground, the user leaves the screen or switches wallet, and before a wipe or removal. [new] The cost: a slow download needs the app kept in front.
21. **"Try again" is shown only when it will make a request.** After a remembered "not here" the card says when the wallet will ask again. [new]
22. **PR 2 also corrects how the picture path decides a CID's kind** (`IpfsClient.kt:286`), so the rule exists once. [new] This touches how pictures and metadata are trusted, not only media. Say if it should be its own PR.
23. **Play is refused while the wallet is still starting or restoring,** so a file can never be mistaken for a corrupt wallet store. [new]
24. **Storage:** no download when less than the file's size plus 256 MiB is free; at most 96 MiB of media kept. [new]
25. **Limits on what is played:** 1920×1080, 60 frames a second where declared, 30 minutes, 8 audio channels, 192 kHz, no protected files; the allowed codecs are H.264, VP8, VP9, AAC, MP3, Vorbis, Opus, FLAC and PCM, on every phone. [partly new]

## 11. History

**Revision 1,** 2026-11-22, commit 9317b4a20: the first design. **Revision 2,** 2026-11-22: answers the independent design review on PR #307, 26 points. One line each:

| # | Review point | How it was answered | Where |
|---|---|---|---|
| 1 | "Pinned, no redirect" held for DigiScope only | Media is asked of the pinned, no-redirect gateway only, for every kind of CID. The contradiction is removed. The looser choice is described and put to the owner. | 3.1, 5.1, question 3 |
| 2 | "A route change cancels it" described nothing that existed | Named as new code: a collector on the route flow, a comparison before the `GET`, and a check of the connection's proxy before the request is written. Tests listed. | 3.6, 5.2, 9.3 |
| 3 | Tor on with the clearnet fallback: the player said nothing | Made an explicit owner question with three choices. Recommended: ask every time, before the `HEAD`. | 5.2, question 4 |
| 4 | CONFIRMED-WRONG: the reader does not stop at the declared length | Corrected where it stood. The agreed size is passed to the reader as its cap; the corpus row and the quoted header are fixed. | 3.4, 9.4 |
| 5 | The size agreed to was not the size that could be downloaded | One consent covers one `HEAD` and one `GET` of at most the agreed size. More is a failure. No automatic second download. | 3.4, 3.5, 5.3 |
| 6 | No rule for a download on lock, background, leaving, wallet switch | A table of every event and what stops the download, with tests. | 3.6, 6.4, 9.3 |
| 7 | "Try again" after a 404 asked nobody | No button for a remembered miss; the card says when the wallet will ask again. The fetcher gets its own miss memory. | 3.7, 3.2 |
| 8 | dag-pb and raw were told apart by text prefix | Decided from the decoded codec and hash function. Other codecs refused. The picture path gets the same rule. | 3.3, question 22 |
| 9 | Replaying a cached file reversed the picture decision, unmentioned | Set beside `DigiByteApp.kt`. Raw files are re-hashed on open. Said plainly that a dag-pb file cannot be checked. Owner question with a recommendation. | 5.4, question 5 |
| 10 | The `.state` file was a second copy of a fact | Removed. The label is derived from the CID; the container is read again on open. | 3.9, 5.4 |
| 11 | No mechanism refused a track before decoding | Two named mechanisms, checked against the published 1.11.1 classes: a `MediaCodecSelector` allow-list, and a first pass with audio and video disabled. Undeclared frame rate and audio limits stated. Not run. | 4.4, 4.3 |
| 12 | The stream can differ from what the container declares | Checks while playing: picture size, position, clock time. Corpus files added. | 4.5, 9.4 |
| 13 | CONFIRMED-WRONG: "at most 30 minutes" | Corrected. A clock limit makes it true, and the question says it was not true before. | 4.5, 6.2, question 6 |
| 14 | Memory was not bounded by the file-size cap | The wallet's own structure checks, fixed player buffers, a heap watch, and a test under a running sync that decides whether in-process is acceptable. | 4.2, 4.7, question 17 |
| 15 | A crash from a file could be read as a corrupt store by `BootGuard` | Play is refused until the boot counter is zero and no restore is pending. | 4.8, question 23 |
| 16 | The container and extractor lists did not match real files | Fragmented MP4 is a seventh extractor (or a named refusal). HEIC and AVIF are refused by brand. MP3 needs two full frame headers. | 4.2, 4.3, question 18 |
| 17 | Close the DRM path by name | `DRM_UNSUPPORTED` handed in by constructor; protected tracks refused; corpus file; DRM classes checked absent from the APK. | 4.3, 2.5, 9.4 |
| 18 | "The parsers are not present" was a source check | A check on the built APK, proven both ways. The constructor is named. Artwork and tag flags set. | 2.4, 2.5 |
| 19 | The wipe never handled the cache directory; a download could outlive it | Eraser and read-back cover `cacheDir`; stop, wait and close before erasing; the wipe-owed record and the legacy wallet covered by a full-wipe step. | 5.5, 3.6 |
| 20 | Audio stops when the display sleeps | Stated with the 60 second default. The real choice is put to the owner with a recommendation. | 6.2, question 6 |
| 21 | No total cache bound; no free-space check | A total across wallets and a free-space floor. | 5.4, question 24 |
| 22 | CONFIRMED-WRONG: "the cost is dex only" | Corrected. 83 resource directories counted; three cost parts, each with how it is measured. | 2.3 |
| 23 | Guava is not alone | The full transitive list, with versions; the build PR shows the dependency diff. | 2.2 |
| 24 | The Android 9 claim was stated, not measured | Turned into a four-step measurement on the Note 8 that closes the owner's question. | 4.6, question 7 |
| 25 | CONFIRMED-WRONG: the media model contradicted itself | Corrected. `cid` is nullable, `kind` is kept, not-fetchable entries are named; aligned with #308. | 8.1 |
| 26 | Jobs assigned to the fixes PR in passing | A table of every item and the PR that delivers it, marking what #308 holds today and what is asked of it. Directory CIDs and CIDs with a path stated. | 8.3, 3.3 |
