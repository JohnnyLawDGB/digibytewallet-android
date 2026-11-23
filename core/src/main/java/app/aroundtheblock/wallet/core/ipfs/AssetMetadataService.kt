package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.model.AssetMetadata
import app.aroundtheblock.wallet.core.network.textOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches and caches DigiAsset metadata stored as JSON on IPFS.
 *
 * Cache policy:
 *  - If the asset's metadata is already in the Room database it is returned immediately,
 *    without any network request.  IPFS CIDs are content-addressed and immutable, so
 *    a cached entry is always valid.
 *  - If no cache entry exists and a [metadataCid] is provided, the JSON is fetched via
 *    [IpfsClient] (which verifies the content hash against the CID before returning it),
 *    parsed, persisted to Room, and then returned.
 *  - If [metadataCid] is null, returns null (no metadata to fetch).
 *  - One exception to "returned immediately": a named row that has never been read for the files
 *    the document declares (written before schema 13) is fetched once more by its own CID. That
 *    re-read is bounded ([MEDIA_REREAD_NOT_HERE_LIMIT], [MEDIA_REREAD_ROUNDS_PER_PROCESS]): a
 *    document that is gone is not asked for with no end.
 *
 * Expected JSON shape (all fields optional):
 * ```json
 * {
 *   "name": "My Asset",
 *   "symbol": "MYA",
 *   "description": "A demo DigiAsset",
 *   "decimals": 2,
 *   "totalSupply": 1000000,
 *   "issuerAddress": "dgb1q…",
 *   "image": "https://…"
 * }
 * ```
 */
class AssetMetadataService(
    private val ipfsClient: IpfsClient,
    private val assetMetadataDao: AssetMetadataDao,
    /** Optional fallback network client that queries digiasset_core instances
     *  (digistamp's indexer, the digiscope.me proxy) when the IPFS fetch fails or
     *  no CID is available. Nullable so tests can construct without one. */
    private val assetNetworkClient: app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient? = null,
    /** Wall-clock milliseconds, for the two waits kept here (a failure's ten minutes, and the
     *  re-read for media's day). A test passes its own so that it can measure days without waiting. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * In-memory negative cache — CIDs for which every resolver failed
     * recently. Asset detection sweeps run every 30s; without this,
     * each sweep kicks off an IPFS fetch for every unresolved asset,
     * which spirals into a retry storm against every gateway when
     * they're all down. An entry lives [NEGATIVE_CACHE_TTL_MS] before
     * we try again. Cleared on process restart (intentional — gives
     * IPFS a chance to recover without the user having to do anything).
     */
    private val recentlyFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Failed re-reads for media in this process, by asset: see [MEDIA_REREAD_ROUNDS_PER_PROCESS]. */
    private val mediaRereadRounds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    internal companion object {
        /**
         * The only issuer the wallet will show: the one the chain proves.
         *
         * @param proven  issuer derived from the owner of input[0] of the issuance transaction.
         * @param claimed whatever the metadata document asserted. Accepted as a parameter so the
         *                rule is stated where it can be read and tested, rather than living as an
         *                absence somewhere in a parser — and so a test can prove a claim alone
         *                still yields nothing. It is never used.
         *
         * @return the proven issuer, or null. Null renders no row at all, which is the point:
         *         absent beats wrong, because a wrong attribution is precisely what a forger is
         *         trying to obtain.
         */
        @Suppress("UNUSED_PARAMETER")
        internal fun provenIssuerOnly(proven: String?, claimed: String?): String? =
            proven?.takeIf { it.isNotBlank() }

        /**
         * True for a named row whose document has never been read for the files it declares
         * (written before schema 13, so `mediaJson` is NULL) and that has a CID to read it by. One
         * copy of the rule: [getMetadata] re-reads such a row, and [rereadForMedia], called from
         * AssetManager's metadata retry pass, is what asks for it. A row with no CID has nothing to be read again from.
         */
        fun needsMediaReread(row: AssetMetadataEntity, now: Long = System.currentTimeMillis()): Boolean =
            row.name != null && IpfsClient.normalizeCid(row.metadataCid) != null && when {
                row.mediaJson == null -> true
                // Tried, and the trusted gateway said the document is not there: again, but not
                // before a day has passed, and only until that has been said often enough.
                else -> MediaRereadMark.read(row.mediaJson)?.let {
                    it.notHere < MEDIA_REREAD_NOT_HERE_LIMIT && !MediaRereadMark.isResting(it, now, MEDIA_REREAD_NOT_HERE_GAP_MS)
                } ?: false
            }

        /**
         * When the re-read for media is given up FOR GOOD: after the TRUSTED gateway has answered
         * "not here" for the row's document 5 times, each at least [MEDIA_REREAD_NOT_HERE_GAP_MS]
         * (24 hours) after the one before. So no sooner than FOUR DAYS after the first such answer,
         * however often the app is started in between, and after five requests for the document.
         * The count and the time of the last answer are kept with the row ([MediaRereadMark], in
         * `mediaJson`, so no column is added), which is what makes the days count across launches.
         *
         * What is counted is only the trusted gateway's own 404 or 400
         * ([IpfsClient.Fetched.trustedSaidNotHere]). That gateway is the one that holds the
         * wallet's documents (the row was read from it once), so its "not here" is an answer about
         * the document. A PUBLIC gateway's 404 is not: they hold what somebody happened to pin, and
         * during an outage of the trusted gateway (5xx, timeout, no route) they answer 404 for
         * documents that are perfectly well. Counting theirs would turn an outage of our own
         * gateway into rows given up for good. No network, a timeout and a 5xx count nothing
         * either and are bounded per process instead ([MEDIA_REREAD_ROUNDS_PER_PROCESS]).
         *
         * After a counted answer the row is not asked for again until the day has passed
         * ([needsMediaReread]), so a document that stays missing costs five rounds of requests in
         * all; one the gateway pins again is read at the next daily try and the count is gone.
         *
         * What giving up costs: the row keeps what 4.1.5 stored. It gets no "Media" line, and if
         * its image is a file that is not a picture, that file is still downloaded once per day
         * per process and judged ([IpfsClient.rememberNotAPicture]).
         */
        const val MEDIA_REREAD_NOT_HERE_LIMIT = 5

        /** The least time between two counted "not here" answers, and the rest after one: a day. */
        const val MEDIA_REREAD_NOT_HERE_GAP_MS = 24L * 60 * 60_000L

        /**
         * How many failed re-reads for media ONE PROCESS makes for one asset, whatever the reason:
         * 6, which at the service's ten-minute wait after a failure ([NEGATIVE_CACHE_TTL_MS]) is
         * the hour of [IpfsClient.MISS_TTL_MS], the gateway memory's ceiling. After that the row is
         * left as it is until the app is started again. This is the bound for failures that are no
         * evidence about the document (no network, timeouts, 5xx), which is why it ends with the
         * process and writes nothing to the row.
         */
        const val MEDIA_REREAD_ROUNDS_PER_PROCESS = 6

        /** 10 minutes. Long enough to stop hammering broken gateways;
         *  short enough that a proxy recovery is picked up naturally
         *  on the next sweep after the interval passes. */
        const val NEGATIVE_CACHE_TTL_MS = 10L * 60_000L

        /** Display-text caps. Generous enough for any legit asset's
         *  name / symbol / description; tight enough that a 10MB
         *  attacker-controlled string can't bloat the local DB. */
        const val MAX_SHORT_TEXT_LEN = 200
        const val MAX_LONG_TEXT_LEN = 2000
        const val MAX_IMAGE_URL_LEN = 2048

        /**
         * Strip everything that is not visible text, then cap the length.
         *
         * Why:
         *  - The BiDi overrides and isolates (U+202A..U+202E, U+2066..U+2069) flip text direction,
         *    so "MyEvil-\u202Egnp.token" renders as "MyEvil-token.png". The direction MARKS
         *    (U+200E, U+200F, U+061C) and the zero-width characters reorder or hide text the same
         *    way. Unicode files all of them under one category, Cf (format), so the filter goes by
         *    category rather than by a list of ranges: a list only stops the characters someone
         *    thought of (bounty BB-2026-10-08-ricki).
         *  - U+2028 / U+2029 are mandatory line breaks in category Zl / Zp, not control characters.
         *    A name or symbol is one line; [allowNewlines] permits '\n' and nothing else.
         *  - Control characters (Cc), unpaired surrogates (Cs) and private-use code points (Co)
         *    have no place in a display name either.
         *  - The cap counts code points, so it never cuts an emoji or any other astral character in
         *    half. It stops a megabyte name from bloating the local DB: issuer-controlled IPFS
         *    metadata is fully attacker-controlled in the worst case.
         *  - Code points that draw nothing whatever their category (see [drawsNothing]): variation
         *    selectors, the combining grapheme joiner, the Hangul fillers, U+2800.
         *
         * Unassigned code points are kept on purpose: which ones are unassigned depends on the
         * device's Unicode version, and a newer emoji must not vanish from a name on an older phone.
         * Two exceptions: the unassigned ranges Unicode reserves as default-ignorable, and the
         * noncharacters (see [isNoncharacter]), which no Unicode version will ever assign.
         *
         * Internal so the security test suite can verify the predicate
         * directly without standing up Room + IPFS infrastructure.
         */
        internal fun sanitize(input: String?, maxLen: Int, allowNewlines: Boolean): String? {
            if (input.isNullOrBlank()) return null
            val out = StringBuilder(minOf(input.length, maxLen * 2))
            var kept = 0
            var i = 0
            while (i < input.length && kept < maxLen) {
                val cp = input.codePointAt(i)
                i += Character.charCount(cp)
                if (isDisplaySafe(cp) || (allowNewlines && cp == '\n'.code)) {
                    out.appendCodePoint(cp)
                    kept++
                }
            }
            return out.toString().takeIf { it.isNotBlank() }
        }

        internal fun isDisplaySafe(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.CONTROL,              // Cc: C0, DEL, C1
            Character.FORMAT,               // Cf: BiDi overrides/isolates/marks, zero-width chars, BOM, soft hyphen, tags
            Character.LINE_SEPARATOR,       // Zl: U+2028
            Character.PARAGRAPH_SEPARATOR,  // Zp: U+2029
            Character.SURROGATE,            // Cs: an unpaired half of a surrogate pair
            Character.PRIVATE_USE,          // Co: renders as whatever the installed fonts decide
            -> false
            else -> !drawsNothing(codePoint) && !isNoncharacter(codePoint)
        }

        /**
         * The 66 code points Unicode sets aside for good (ASSET-NONCHAR): U+FDD0..U+FDEF, and the
         * last two of each of the 17 planes, U+FFFE and U+FFFF through U+10FFFE and U+10FFFF. They
         * are not text and are not meant to be interchanged, a font draws them as nothing or as a
         * box of its own choosing, and the JVM reports them as unassigned, the category kept above.
         * The reason for keeping unassigned code points does not reach them: their status is fixed
         * by the standard, so the answer is the same on every device.
         *
         * The plane-end test works on a whole code point, so a supplementary one arrives here as
         * the pair [sanitize] already joined; a lone surrogate never matches (it is dropped as Cs).
         */
        private fun isNoncharacter(cp: Int): Boolean =
            cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE

        /**
         * Code points that draw nothing but are not in the categories above. Unicode marks them
         * Default_Ignorable_Code_Point; outside Cf they are combining marks (Mn) and letters (Lo),
         * so a category filter alone lets them through and "Digi\u180BDollar" renders exactly as
         * "DigiDollar". The ranges are the property's non-Cf part (Unicode 15.1
         * DerivedCoreProperties.txt), unassigned ones included: those are reserved as ignorable,
         * so a newer font draws them as nothing too. U+2800 is not ignorable but is a blank cell.
         *
         * Stripping U+FE0E/U+FE0F only changes an emoji's presentation (text vs. colour), never
         * which character it is.
         */
        private fun drawsNothing(cp: Int): Boolean =
            cp == 0x034F ||                 // combining grapheme joiner
            cp == 0x115F || cp == 0x1160 || // Hangul choseong / jungseong fillers
            cp == 0x17B4 || cp == 0x17B5 || // Khmer inherent vowels
            cp in 0x180B..0x180F ||         // Mongolian free variation selectors (U+180E is Cf)
            cp in 0x2060..0x206F ||         // word joiner block; U+2065 is reserved
            cp == 0x3164 ||                 // Hangul filler
            cp in 0xFE00..0xFE0F ||         // variation selectors
            cp == 0xFFA0 ||                 // halfwidth Hangul filler
            cp in 0xFFF0..0xFFF8 ||         // reserved
            cp in 0x1BCA0..0x1BCA3 ||       // shorthand format controls
            cp in 0x1D173..0x1D17A ||       // musical symbol format controls
            cp in 0xE0000..0xE0FFF ||       // tags, variation selectors supplement, reserved
            cp == 0x2800                    // braille pattern blank

        internal fun isDisplaySafe(ch: Char): Boolean = isDisplaySafe(ch.code)

        /**
         * Rows cached by 4.0.88 and earlier went through the old range filter, and a cached row is
         * never fetched again (CIDs are immutable). Every read that puts a cached name, symbol or
         * description on screen goes through this, so those rows display clean without a
         * migration. Idempotent and cheap: the fields are already length-capped.
         */
        fun AssetMetadataEntity.displaySafe(): AssetMetadataEntity = copy(
            name = sanitize(name, MAX_SHORT_TEXT_LEN, allowNewlines = false),
            symbol = sanitize(symbol, MAX_SHORT_TEXT_LEN, allowNewlines = false),
            description = sanitize(description, MAX_LONG_TEXT_LEN, allowNewlines = true),
        )
    }
    /**
     * Return metadata for [assetId], fetching from IPFS via [metadataCid] if not cached.
     *
     * Resolution order:
     *  1. Room cache (immutable — CID content-addressed).
     *  2. IPFS via [metadataCid] if supplied, verified against its multihash.
     *  3. [assetNetworkClient] — digiasset_core instances that already have
     *     the metadata pinned and parsed server-side. Survives the case where
     *     public IPFS gateways are slow/down AND the CID wasn't cached locally.
     *
     * @param assetId     The DigiAsset identifier.
     * @param metadataCid Optional IPFS CID of the JSON metadata document.
     * @return [AssetMetadata] on success, `null` if every resolver failed.
     */
    suspend fun getMetadata(assetId: String, metadataCid: String?): AssetMetadata? {
        // Cache-first for real (content-addressed) metadata — CIDs are
        // immutable, so a cached entry with a real name is always valid.
        // Bare placeholder rows (name==null) are treated as "not yet
        // fetched"; returning them here would short-circuit the IPFS pull
        // and leave the user staring at an anonymous colored-letter icon
        // forever. Refetch when we have a CID to actually fetch from.
        val cached = assetMetadataDao.getMetadata(assetId)
        // A named row is complete once it has also been read for media. A row written before schema
        // 13 has not (mediaJson NULL), and its image may be an MP3 that happened to be named "icon"
        // (ASSET-MEDIA-CLASSIFY): its document is fetched once more, below, when the row has a CID
        // to fetch it by. Same gateways, same backoff. Until that lands the row is shown as it is.
        val named = cached?.takeIf { it.name != null }
        if (named != null && !needsMediaReread(named, clock())) return named.toModel()
        // ...and the re-read ends (bounded twice: see MEDIA_REREAD_NOT_HERE_LIMIT, just checked,
        // and MEDIA_REREAD_ROUNDS_PER_PROCESS).
        if (named != null && (mediaRereadRounds[assetId] ?: 0) >= MEDIA_REREAD_ROUNDS_PER_PROCESS) return named.toModel()

        // The CID is the issuance's: the caller's, or the one the parent walk recorded with the chain facts.
        // A value that is not a CID is no CID (IPFS-NO-NULL): a row written before this rule could hold
        // a provider's "null". An issuance's own CID is computed from its hash and is always one.
        val cid = sequenceOf(metadataCid, cached?.metadataCid).firstOrNull { IpfsClient.normalizeCid(it) != null }
        // The provider answers only for an issuance the chain shows carried no metadata hash (4.1.2 row 29):
        // a chain-facts row with no CID. Supply is the marker: only AssetManager.applyChainFacts writes it (an
        // issued asset never has 0), and the same write records the issuance's CID (updateChainFacts). Without that row it is not
        // yet known whether the issuance names a CID, and a provider's document stored now, with a name,
        // would shadow that CID for good once the walk brings it (review R6). That waits for the walk.
        val providerAllowed = cid == null && cached != null && cached.totalSupply > 0
        if (cid == null && !providerAllowed) return cached?.toModel()
        // (A re-read for media always has a CID, so it never reaches the provider pass below.)

        // Negative-cache short-circuit: if we tried this CID recently and every
        // gateway failed, don't hammer them again for NEGATIVE_CACHE_TTL_MS.
        val now = clock()
        val negativeKey = cid ?: assetId
        val lastFail = recentlyFailed[negativeKey]
        if (lastFail != null && (now - lastFail) < NEGATIVE_CACHE_TTL_MS) {
            return named?.toModel()
        }

        return withContext(Dispatchers.IO) {
            // Pass 1: IPFS via CID (if we have one)
            var saidNotHere = false
            if (cid != null) {
                val fetched = ipfsClient.fetch(cid)
                // The trusted gateway's own answer only: see MEDIA_REREAD_NOT_HERE_LIMIT.
                saidNotHere = fetched.trustedSaidNotHere
                val bytes = fetched.bytes
                if (bytes != null) {
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json != null) {
                        recentlyFailed.remove(negativeKey)
                        mediaRereadRounds.remove(assetId)
                        // Path 1 resolves the document straight from the issuance's metadata hash and
                        // never consults a provider, so there is no chain-proven issuer to record.
                        // null is the correct answer here, and the row simply will not render.
                        return@withContext storeFromJson(assetId, cid, json, provenIssuer = null)
                    }
                }
            }

            // Pass 2: provider fallback, for assets whose issuance carried no metadata hash -- ONLY those
            // (4.1.2 row 29). When the issuance names a CID, the document at that CID is the asset's
            // metadata and nothing else is: a provider's inline document or CID stored in its place was
            // never checked against the issuance, and once cached with a name it shadowed the CID for
            // good. With IPFS unreachable the row waits for the next attempt instead.
            val netClient = assetNetworkClient.takeIf { providerAllowed }
            if (netClient != null) {
                val remote = runCatching { netClient.getAssetData(assetId) }.getOrNull()

                // 2a: some providers inline the metadata document.
                val ipfsMap = remote?.ipfs
                if (remote != null && ipfsMap != null) {
                    recentlyFailed.remove(negativeKey)
                    val json = JSONObject(ipfsMap)
                    // The provider's issuer is its claim, not chain proof: never recorded as proven.
                    return@withContext storeFromJson(assetId, remote.cid, json, provenIssuer = null)
                }

                // 2b: most return a CID INSTEAD of the document — which is the thing we were
                // missing, so fetch it. Without this the fallback would retrieve a perfectly
                // good response, find no inline blob, and discard the very CID it just learned;
                // the asset stayed a bare id with supply and divisibility but no name.
                val remoteCid = remote?.cid
                if (remoteCid != null) {
                    val bytes = ipfsClient.fetchVerified(remoteCid)
                    val json = bytes?.let {
                        runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull()
                    }
                    if (json != null) {
                        recentlyFailed.remove(negativeKey)
                        return@withContext storeFromJson(assetId, remoteCid, json, provenIssuer = null)
                    }
                }
            }

            // Everything failed — mark for suppression until TTL expires.
            recentlyFailed[negativeKey] = clock()
            // A row that was already named stays on screen: only its re-read for media failed.
            if (named != null) noteMediaRereadFailed(named, saidNotHere)
            named?.toModel()
        }
    }

    /**
     * Read [assetId]'s document once more if its row was written before the declared files were
     * kept ([needsMediaReread]); for a row already read, one cache read and no request. It is
     * [getMetadata] with the row's own CID, under the name of what the caller wants from it.
     */
    suspend fun rereadForMedia(assetId: String) {
        getMetadata(assetId, null)
    }

    /**
     * A re-read for media of the named row [row] just failed: counted, so that it ends. Every
     * failure counts toward this process's bound; one in which the TRUSTED gateway answered "not
     * here" ([saidNotHere]) is also recorded with the row, toward the bound that outlives the process.
     */
    private suspend fun noteMediaRereadFailed(row: AssetMetadataEntity, saidNotHere: Boolean) {
        val rounds = mediaRereadRounds.merge(row.assetId, 1, Int::plus) ?: 1
        val before = MediaRereadMark.read(row.mediaJson)
        val now = clock()
        val mark = if (saidNotHere) MediaRereadMark.next(before, now, MEDIA_REREAD_NOT_HERE_GAP_MS) else before
        if (mark != null && mark != before) {
            runCatching { assetMetadataDao.updateMedia(row.assetId, mark.encode()) }
                .onFailure { android.util.Log.w("AssetMetadataService", "media re-read count not saved for ${row.assetId}", it) }
        }
        val givenUp = (mark?.notHere ?: 0) >= MEDIA_REREAD_NOT_HERE_LIMIT
        if (givenUp || rounds >= MEDIA_REREAD_ROUNDS_PER_PROCESS) {
            android.util.Log.i("AssetMetadataService", "media re-read for ${row.assetId} stopped: " +
                if (givenUp) "the trusted gateway said on ${mark?.notHere} separate days that its document is not there; not asked for again"
                else "$rounds failed attempts; not asked for again until the app restarts")
        }
    }

    /**
     * Ask the asset proxy whether [assetId] carries transfer rules and remember the answer.
     *
     * @return true when the proxy returned a non-empty `rules` object, false when it answered
     *   without one, null when no endpoint answered. The row is created if missing so the answer
     *   has somewhere to live; `"{}"` is stored for "answered, none" so a later reader can tell it
     *   from "never asked" (NULL). Never throws: the gate treats null as unknown.
     */
    suspend fun refreshRules(assetId: String): Boolean? {
        val client = assetNetworkClient ?: return null
        // A silent failure here reads downstream as "rules unknown", which refuses the send with
        // no recorded reason — so the reason gets recorded. No JVM test executes this method
        // (the callers mock this service), so android.util.Log is safe to call directly.
        val remote = runCatching { client.getAssetData(assetId) }
            .onFailure { android.util.Log.w("AssetMetadataService", "rules lookup failed for $assetId", it) }
            .getOrNull() ?: return null
        val present = !remote.rules.isNullOrEmpty()
        val json = if (present) JSONObject(remote.rules!!).toString() else "{}"
        runCatching {
            assetMetadataDao.insertChainFacts(AssetMetadataEntity(assetId = assetId))
            assetMetadataDao.updateRulesJson(assetId, json)
        }.onFailure { android.util.Log.w("AssetMetadataService", "rules persist failed for $assetId", it) }
        return present
    }

    internal suspend fun storeFromJson(
        assetId: String,
        cid: String?,
        jsonRaw: JSONObject,
        provenIssuer: String?,
    ): AssetMetadata {
        // Canonical DigiAsset metadata wraps everything in {"data": {...}}.
        // Some older tools produce the same fields at top-level. Unwrap if
        // present so downstream code only sees the asset-level shape.
        val json = jsonRaw.optJSONObject("data") ?: jsonRaw

        // Every text below is read with textOrNull, never optString: on Android a JSON null read with
        // optString is the text "null", which would be stored as the asset's name, symbol or image.

        // Name field — canonical schema uses "assetName", older tools use "name".
        val name = sanitizeShortText(
            json.textOrNull("assetName")
                ?: json.textOrNull("name")
        )

        // Issuer — deliberately NOT read from this document.
        //
        // The metadata carries `issuer` / `issuerAddress` / `issuer.address`, and we used to take
        // whichever was present and show it as "Issuer Address". Every one of those is written by
        // whoever minted the asset, which makes attribution the easiest thing in the file to
        // forge: copy an asset, re-mint it, name the original creator, and the claim arrives with
        // the image. Rendering it made a forgery MORE convincing, not less. (In practice the bare
        // `issuer` key is usually a username anyway, so a row labelled "Address" could show a
        // handle.)
        //
        // The issuer now comes from what the chain proves — see [provenIssuerOnly] and the
        // `proven` argument, which the provider derives from the owner of input[0] of the
        // issuance transaction. If a display-only, clearly-labelled "self-asserted by the minter"
        // field is ever wanted, it must be a SEPARATE field with its own label; it must never
        // flow into this one.

        // The picture and the declared files: one rule, in AssetMediaEntries (ASSET-MEDIA-CLASSIFY).
        // The picture is chosen by media type; an entry named "icon" that declares audio is not one.
        val files = AssetMediaEntries.parse(json)
        val imageUrl = files.imageUrl
        val mediaJson = AssetMediaEntries.encode(files.media)

        val entity = AssetMetadataEntity(
            assetId = assetId,
            name = name,
            symbol = sanitizeShortText(
                json.textOrNull("symbol")
                    ?: json.textOrNull("ticker")
            ),
            description = sanitizeLongText(
                json.textOrNull("description")
            ),
            // decimals and totalSupply are DELIBERATELY LEFT AT THEIR DEFAULTS here.
            //
            // They are chain data — cryptographically tied to the issuance tx — and this document
            // is written by whoever minted the asset and authenticated by nothing. Canonical
            // DigiAsset metadata carries no `totalSupply` key at all, so reading it with a default
            // of 0 and writing the whole row through `insert` (REPLACE) stamped 0 over the
            // chain-derived value: a device showed `Total Supply 5`, then `Unknown` eight minutes
            // later once the metadata fetch supplied the name. AssetManager already guards the
            // other direction via updateChainFacts; this is the missing mirror.
            //
            // This row is only ever a SEED for an asset we have no chain facts for yet, written
            // INSERT-OR-IGNORE, so these zeros can never overwrite a real value.
            issuerAddress = provenIssuerOnly(proven = provenIssuer, claimed = null),
            metadataCid = cid,
            // imageUrl is bounds-checked at render time by AssetImageResolver
            // (refuses non-http(s)/ipfs schemes) but cap length here too so
            // a 10MB string doesn't end up in the row.
            imageUrl = imageUrl?.takeIf { it.length <= MAX_IMAGE_URL_LEN },
            mediaJson = mediaJson,
            cachedAt = System.currentTimeMillis(),
        )
        // Seed the row if this asset has none yet (IGNORE: never clobbers a richer one), then
        // merge only the columns this document is the source of.
        assetMetadataDao.insertChainFacts(entity)
        assetMetadataDao.updateIpfsFacts(
            assetId = entity.assetId,
            name = entity.name,
            symbol = entity.symbol,
            description = entity.description,
            issuerAddress = entity.issuerAddress,
            metadataCid = entity.metadataCid,
            imageUrl = entity.imageUrl,
            cachedAt = entity.cachedAt,
        )
        // Second, so a write interrupted between the two leaves the row marked "not read for media"
        // (NULL) and it is simply read again.
        assetMetadataDao.updateMedia(entity.assetId, entity.mediaJson)
        // Re-read so the caller sees the merged row rather than this document's view of it — the
        // entity above deliberately carries no supply or divisibility, and returning it would
        // report 0 for an asset whose chain facts are already known.
        return (assetMetadataDao.getMetadata(assetId) ?: entity).toModel()
    }

    private fun sanitizeShortText(input: String?): String? = sanitize(input, MAX_SHORT_TEXT_LEN, allowNewlines = false)
    private fun sanitizeLongText(input: String?): String? = sanitize(input, MAX_LONG_TEXT_LEN, allowNewlines = true)

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private fun AssetMetadataEntity.toModel() = displaySafe().run { AssetMetadata(
        assetId = assetId,
        name = name,
        symbol = symbol,
        description = description,
        decimals = decimals,
        totalSupply = totalSupply,
        issuerAddress = issuerAddress,
        imageUrl = imageUrl,
        metadataCid = metadataCid,
        media = AssetMediaEntries.decode(mediaJson),
    ) }
}
