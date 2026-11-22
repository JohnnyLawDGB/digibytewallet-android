package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.LogSafe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.aroundtheblock.wallet.core.network.BoundedBody
import app.aroundtheblock.wallet.core.tor.TorNotReadyException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Fetches IPFS content from public gateways and verifies the payload against the CID.
 *
 * Security guarantees:
 *  - Every response is hashed and compared to the CID digest before it is returned,
 *    with one exception: a dag-pb CID answered by the DigiScope gateway, which is
 *    accepted on that server's pinned identity instead (see [TRUSTED_GATEWAY_PREFIXES]).
 *    Any other gateway cannot substitute content.
 *  - A response larger than [MAX_RESPONSE_SIZE] is refused while it streams; it is never held whole.
 *  - Gateways are tried in order; failures (network or hash mismatch) cause a
 *    transparent fall-through to the next gateway.
 *  - A redirect from a trusted gateway is never followed (IPFS-NO-REDIRECT): trust-by-pin covers
 *    only bytes the pinned host itself answered. Its 3xx is a failed fetch like any other, and the
 *    next gateway is asked. The public gateways are content-verified on every answer, wherever it
 *    came from, and keep following redirects.
 *  - No request is made for a value that is not a CID (IPFS-NO-NULL): [normalizeCid] refuses it and
 *    [gatewayUrl], the one place a gateway URL is built, refuses it again. A provider's JSON `null`
 *    read as the text "null" was asked of every gateway, every ten minutes, for as long as the app ran.
 *  - A gateway's "this CID is not here" (HTTP 404) or "bad request" (400) is remembered for
 *    [MISS_FIRST_MS], growing to [MISS_TTL_MS] while it keeps saying so, and that gateway is not
 *    asked for that CID again inside it ([GatewayMissMemory]), whichever caller asks: the metadata
 *    sweep and the image loader share this one client.
 *  - A gateway whose answer was refused for size is remembered the same way, in the same memory
 *    (ASSET-MEDIA-FIXES): without a declared length the refusal costs a read up to the cap, and
 *    the answer will be as large a second later. [Fetched.refusedForSize] says that is what
 *    happened, so a caller does not report an oversized file as a gateway failing. When it is the
 *    TRUSTED gateway that refused, that is the reason reported whatever the others then say
 *    ([Fetched.trustedRefusedForSize]), and for a dag-pb CID, whose file only the trusted gateway
 *    can serve, the public gateways are not asked at all, then or while the refusal is remembered.
 *  - A trusted gateway's answer is taken on its pinned identity only when it is COMPLETE
 *    ([endIsMarked]): an empty answer, one shorter than it declared, or one whose end nothing
 *    marked is a failed fetch. Nothing checks those bytes against the CID, so a cut answer would
 *    otherwise be returned, judged and measured as the file.
 *  - A file a caller found not to be a picture is remembered as that ([rememberNotAPicture]), in
 *    the same memory again, for [NOT_A_PICTURE_MS]. Only bytes that are the whole file may be
 *    judged ([Fetched.wholeFile]): a gateway that failed, or a public gateway's block of a dag-pb
 *    CID, says nothing about the file.
 *  - Callers that ask for one CID at the same moment share one request ([fetch]).
 *
 * No IPFS daemon or libp2p dependency is required.
 */
class IpfsClient internal constructor(
    private val httpClient: OkHttpClient,
    private val cidVerifier: CidVerifier,
    private val gateways: List<String>,
    /**
     * The gateways trusted on their pinned identity. Settable only inside this module, where a test
     * serves one locally: no caller outside it can name a host the pinner holds no pins for.
     */
    private val trustedGatewayPrefixes: List<String>,
    /** Wall-clock milliseconds, for [misses]. A test passes its own so no test waits for time to pass. */
    clock: () -> Long = System::currentTimeMillis,
) {
    /** The shipped client: the trusted gateways are [TRUSTED_GATEWAY_PREFIXES] and nothing else. */
    constructor(
        httpClient: OkHttpClient,
        cidVerifier: CidVerifier = CidVerifier(),
        gateways: List<String> = DEFAULT_GATEWAYS,
    ) : this(httpClient, cidVerifier, gateways, TRUSTED_GATEWAY_PREFIXES)

    /**
     * The client a trusted gateway is asked on: [httpClient] (its Tor routing, pins and pool) with
     * both redirect switches off, as on every other pinned client (4.1.2 row 30). Made on first use.
     */
    private val trustedGatewayClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Which gateway said, and when, that it does not have which CID. Held in memory only. */
    private val misses = GatewayMissMemory(MISS_FIRST_MS, MISS_TTL_MS, MISS_MAX_ENTRIES, clock)

    companion object {
        /**
         * Reduce a provider's CID to the bare identifier, or null if it is not one.
         *
         * Strips `ipfs://` and a leading `/ipfs/`. Everything else is REFUSED rather than
         * repaired: the CID is the integrity check that content is verified against, so a
         * value that could steer the fetch elsewhere is not something to tidy up and use.
         */
        @JvmStatic
        fun normalizeCid(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            val bare = trimmed.removePrefix("ipfs://").removePrefix("/ipfs/").trim()

            if (bare.isEmpty()) return null
            // A CID is base32/base58 alphanumerics only — no slashes, dots, queries, spaces.
            if (!bare.all { it.isLetterOrDigit() }) return null
            // ...and it has to BE one (IPFS-NO-NULL): the text "null" is letters only, and so is any
            // other word. A value that does not decode to a multihash names no content, so no gateway
            // can serve it and nothing could be verified against it.
            if (!CID_SYNTAX.isWellFormed(bare)) return null
            return bare
        }

        /**
         * The URL [cid] is asked for at [gateway], or null when [cid] is not exactly a CID
         * [normalizeCid] accepts. The ONE place a gateway URL is built, so no caller, present or
         * future, can put anything else into a request path.
         *
         * The template may contain a `{cid}` placeholder; an entry without one gets the traditional
         * `/ipfs/<cid>?format=raw` suffix.
         */
        internal fun gatewayUrl(gateway: String, cid: String): String? {
            if (normalizeCid(cid) != cid) return null
            return if (gateway.contains("{cid}")) gateway.replace("{cid}", cid) else "$gateway/ipfs/$cid?format=raw"
        }

        /** Stateless; shared so [normalizeCid] can stay a plain function. */
        private val CID_SYNTAX = CidVerifier()

        /**
         * The longest a gateway's 404 or 400 for a CID is remembered: one hour. The first miss is
         * remembered for [MISS_FIRST_MS], and each further one for twice as long, up to this.
         *
         * Decided from how the wallet refreshes. The asset sweep runs every 30 seconds and
         * [AssetMetadataService] holds an all-gateways failure for ten minutes, so an unpinned CID
         * was asked of the first gateway about 144 times a day; the image loader held nothing at
         * all and asked on every draw of the icon. Ten minutes growing to an hour makes that 26 on
         * the first day and 24 on each day after, per CID and gateway. It is not longer because
         * "not here" stops being true: the first gateway serves pinned files only, and a CID is
         * pinned after the asset is issued, so the hour is the longest a newly pinned name or
         * picture waits. The memory is in this object only, so it also ends with the process:
         * restarting the app asks again at once. No refresh bypasses it, automatic or by hand: the
         * repeats came from refreshes.
         */
        const val MISS_TTL_MS = 60L * 60_000L

        /**
         * How long the FIRST 404 or 400 for a CID is remembered: ten minutes, the time
         * [AssetMetadataService] already waited after a failure, so a just-issued asset (whose
         * first answer is normally 404, the document being pinned after the wallet has seen the
         * issuance) shows its name and picture no later than it did before this memory existed.
         */
        const val MISS_FIRST_MS = 10L * 60_000L

        /** The most (gateway, CID) answers remembered; past it the oldest is dropped (and asked again). */
        const val MISS_MAX_ENTRIES = 512

        /**
         * How long "this file is not a picture" is remembered: a day (ASSET-MEDIA-FIXES).
         *
         * Longer than a gateway's "not here", because it is a different kind of answer. A gateway
         * may pin a CID an hour from now; the bytes of a CID never change, so a file that did not
         * decode as an image will not decode on the next draw either, and asking again costs the
         * whole file (measured: a 4,265,952-byte MP3 named "icon", once per image instance per
         * draw). It is still not for ever, and it lives in this object only: a new process, or a
         * day, asks once more.
         */
        const val NOT_A_PICTURE_MS = 24L * 60 * 60_000L

        /** The most file sizes remembered for [knownSize]; past it the oldest is dropped. */
        const val SIZES_MAX_ENTRIES = 256

        /** The name the "not a picture" answers are filed under in [misses], where a gateway's URL
         *  template goes. No gateway can have it: a template is a URL. */
        private const val PICTURE_VERDICT = "not-a-picture"

        /** The name "the trusted gateway refused this dag-pb CID's file for size" is filed under in
         *  [misses], beside that gateway's own entry and with the same growing wait, so the two end
         *  together. It is what tells a remembered size refusal from a remembered 404: after a
         *  404 the public gateways are still asked, after a size refusal they are not. */
        private const val TOO_LARGE_VERDICT = "too-large-at-trusted"

        /**
         * True when [response]'s body has an end the server MARKED, so that reading it to that end
         * without an error means every byte arrived ([declared] is the body's declared length, -1
         * for none):
         *
         *  - a declared length: OkHttp fails a body that ends before it (`unexpected end of stream`);
         *  - `Transfer-Encoding: chunked`: ended by the last-chunk marker, and failed the same way without it;
         *  - HTTP/2: ended by the END_STREAM flag; a stream cut short is reset, which is an error;
         *  - a compressed answer whose own framing was one of the above (OkHttp removes
         *    `Content-Length` after inflating, so the network response is asked).
         *
         * False for the one remaining shape: HTTP/1 with neither, where the body simply runs until
         * the connection closes and a connection that DROPPED looks exactly like one that finished.
         */
        internal fun endIsMarked(response: Response, declared: Long): Boolean {
            if (declared >= 0) return true
            val net = response.networkResponse ?: response
            if (net.protocol == Protocol.HTTP_2 || net.protocol == Protocol.H2_PRIOR_KNOWLEDGE) return true
            if (net.header("Transfer-Encoding").equals("chunked", ignoreCase = true)) return true
            return (net.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L) >= 0
        }

        /** The answers that say the CID itself is the problem at this gateway: not here, or not acceptable.
         *  Everything else (5xx, a redirect refused, a timeout, no route) may be different a moment later. */
        internal fun isMiss(httpCode: Int): Boolean = httpCode == 404 || httpCode == 400

        /** Ordered list of IPFS gateways to try. Our own digiscope.me proxy
         *  is tried first — it resolves anything pinned on the local IPFS
         *  node (including freshly-minted test assets) without depending on
         *  DHT propagation to public gateways, which can take minutes or
         *  never happen at all for small pins. Public trustless gateways
         *  follow as the sovereign fallback. */
        val DEFAULT_GATEWAYS = listOf(
            "https://api.digiscope.me/api/ipfs/{cid}",  // local pin proxy, serves raw bytes
            "https://trustless-gateway.link/ipfs/{cid}?format=raw",
            "https://dweb.link/ipfs/{cid}?format=raw",
            "https://ipfs.io/ipfs/{cid}?format=raw"
        )

        /** Maximum allowed response body size (5 MiB). */
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L // 5 MB as Long for contentLength check

        /** Gateways whose answer for a dag-pb CID (every CIDv0 `Qm…`; a CIDv1 whose codec field is
         *  dag-pb, most often written `bafybe…`; read from the CID by [CidVerifier.codec]) is accepted
         *  without re-verifying the content hash: a dag-pb CID hashes the UnixFS wrapper, which
         *  this unwrapping gateway strips before serving, so a local rehash would always fail.
         *  For those CIDs the trust anchor is the server's identity: the DigiScope host is
         *  certificate-pinned on the shared client this class is given (NetworkModule; the pins
         *  are host-scoped, so only gateway #1 is pinned — the public gateways are not, and stay
         *  strictly content-verified). That is a server-identity check, not a content check:
         *  the pinned server itself is still trusted for these bytes. CIDv1 raw (`bafkre…`)
         *  fetches are content-verified on every gateway. */
        val TRUSTED_GATEWAY_PREFIXES = listOf(
            "https://api.digiscope.me/"
        )

        /** Per-request timeout in milliseconds (used by the shared OkHttpClient). */
        const val TIMEOUT_MS = 15_000L

        private const val TAG = "IpfsClient"
    }

    /**
     * Fetch content identified by [cid] from IPFS and verify its hash.
     *
     * Tries each gateway in [gateways] in order.  Returns the raw bytes on
     * the first gateway that returns a body whose hash matches the CID.
     * Returns `null` if all gateways fail or return unverifiable content.
     */
    suspend fun fetchVerified(rawCid: String): ByteArray? = fetch(rawCid).bytes

    /**
     * What [fetch] came to: the verified [bytes], or null with [gatewaysFailed], the number of
     * gateways that were asked and gave nothing usable. Null bytes with none failed means nothing
     * was asked (not a CID, every gateway inside its remembered "not here", or Tor not connected):
     * a routine outcome, and not one to report as a gateway failing or tampering.
     */
    class Fetched internal constructor(
        val bytes: ByteArray?,
        val gatewaysFailed: Int,
        /** How many of [gatewaysFailed] answered with a file over [MAX_RESPONSE_SIZE] and nothing
         *  else wrong. When it equals [gatewaysFailed], nothing failed but the size. */
        val refusedForSize: Int = 0,
        /**
         * True only when [bytes] are known to be the WHOLE FILE the CID names, from a source that
         * can speak for it (ASSET-MEDIA-FIXES):
         *
         *  - a trusted, pinned gateway's complete answer (HTTP 200) for a dag-pb CID, which it
         *    serves unwrapped; or
         *  - an answer that hashes to a `raw`-codec CID, whose hash covers the file's every byte,
         *    whichever gateway gave it.
         *
         * False for everything else, and above all for a PUBLIC gateway's answer to a dag-pb CID:
         * that is the CID's root block (it hashes to the CID, so it verifies), not the file. Such
         * bytes are no evidence of what the file is. A caller that draws a conclusion ABOUT THE
         * FILE (it is not a picture; it is this long) may do so only when this is true. A fetch
         * that returned nothing is never evidence either: [bytes] is null and this is false.
         */
        val wholeFile: Boolean = false,
        /** How many of [gatewaysFailed] answered, in this fetch, that they do not have the CID
         *  (HTTP 404 or 400): an answer about the CID, where a 5xx, a timeout or no network is not. */
        val saidNotHere: Int = 0,
        /**
         * True when the TRUSTED gateway itself answered, in this fetch, that it does not have the
         * CID (HTTP 404 or 400). The trusted gateway is the one that holds the wallet's documents,
         * so this is the only "not here" that is evidence a document is gone: a public gateway's
         * 404 while the trusted one is failing or was not asked says nothing about that.
         */
        val trustedSaidNotHere: Boolean = false,
        /**
         * True when nothing was fetched and the trusted gateway refused the file for its size
         * (over [MAX_RESPONSE_SIZE]): in this fetch, or, for a dag-pb CID, in an earlier one that is
         * still remembered (then no gateway was asked and [gatewaysFailed] is 0). The trusted
         * gateway serves the file itself, so this is the reason there is no file, whatever the
         * public gateways answered or failed to answer afterwards.
         */
        val trustedRefusedForSize: Boolean = false,
    )

    /**
     * A caller fetched [rawCid] to show it as a picture and its bytes are not one (an asset's
     * "icon" can be a song). Remembered for [NOT_A_PICTURE_MS] so that no screen downloads it for
     * that purpose again. It does not stop [fetch]: the file is still a file, for a document
     * reader or a later player. A value that is not a CID is ignored.
     *
     * The caller must have judged the whole file: call this only for bytes of a [Fetched] whose
     * [Fetched.wholeFile] is true. A failed fetch, or a block that is not the file, did not decode
     * as an image either, and remembering THAT hid a good picture for a day whenever the trusted
     * gateway was down while a public one had the root block.
     */
    fun rememberNotAPicture(rawCid: String) {
        val cid = normalizeCid(rawCid) ?: return
        misses.remember(PICTURE_VERDICT, cid, fixedWaitMs = NOT_A_PICTURE_MS)
    }

    /** True while [rawCid] is remembered as [not a picture][rememberNotAPicture]. Makes no request. */
    fun isKnownNotAPicture(rawCid: String): Boolean {
        val cid = normalizeCid(rawCid) ?: return false
        return misses.isRemembered(PICTURE_VERDICT, cid)
    }

    /**
     * The size in bytes of the file [rawCid] names, when an answer ALREADY MADE gave it; else
     * null. Never makes a request: it exists so a screen can say "Audio · 4.1 MB" without paying a
     * request to say it. Known after a fetch that returned the file (its length), or after the
     * trusted gateway declared a length the wallet then refused as too large. Only an answer that
     * is the whole file ([Fetched.wholeFile]) is recorded: a public gateway's answer for a dag-pb
     * CID is that CID's root block, not the file.
     * Held in memory only, at most [SIZES_MAX_ENTRIES].
     */
    fun knownSize(rawCid: String?): Long? {
        val cid = normalizeCid(rawCid) ?: return null
        return synchronized(sizes) { sizes[cid] }
    }

    private val sizes = LinkedHashMap<String, Long>()

    private fun recordSize(cid: String, size: Long) {
        if (size <= 0) return
        synchronized(sizes) {
            sizes.remove(cid)
            sizes[cid] = size
            while (sizes.size > SIZES_MAX_ENTRIES) sizes.keys.iterator().let { it.next(); it.remove() }
        }
    }

    /** The fetches under way, by CID. A caller that finds its CID here waits for that answer. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Fetched>>()

    /**
     * Where a shared fetch runs: not in any caller's scope, so one caller leaving (an icon
     * scrolled away) does not cancel the answer the others are waiting for. A fetch is bounded by
     * the gateway list, the client's timeouts and [MAX_RESPONSE_SIZE], so it always ends.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [fetchVerified], for a caller that reports a failure and so has to know whether one happened.
     *
     * Callers asking for the same CID at the same moment get one answer from one round of
     * requests (ASSET-MEDIA-FIXES): an asset's picture is asked for by its list card, its detail
     * hero and the full-screen viewer, and each used to download it separately. This is not a
     * cache: the answer is shared only with callers that arrived while it was being fetched.
     */
    suspend fun fetch(rawCid: String): Fetched {
        // Providers disagree on shape: digistamp returns "ipfs://bafy…", the issuance header
        // gives a bare CID. The value is pasted into a gateway URL, so a prefix left in place
        // becomes a 404 that surfaces as "metadata offline".
        val cid = normalizeCid(rawCid) ?: run {
            android.util.Log.w(TAG, "fetchVerified: refusing malformed cid")
            return Fetched(null, 0)
        }
        val mine = CompletableDeferred<Fetched>()
        val running = inFlight.putIfAbsent(cid, mine)
        if (running != null) return running.await()
        fetchScope.launch {
            val result = runCatching { fetchFromGateways(cid) }
            // Taken out BEFORE the answer is set: a caller that already found this entry holds it and
            // gets the answer; one that arrives from now on starts a fetch of its own. The other
            // order would hand a finished answer to a later caller, which is a cache nobody asked for.
            inFlight.remove(cid, mine)
            result.fold(mine::complete, mine::completeExceptionally)
        }
        return mine.await()
    }

    private fun isTrusted(gateway: String) = trustedGatewayPrefixes.any { gateway.startsWith(it) }

    private fun fetchFromGateways(cid: String): Fetched {
        android.util.Log.i(TAG, "fetchVerified: cid=$cid")
        // Only the trusted gateway can serve a dag-pb CID's FILE (a public gateway's answer is its
        // root block). Once it has refused the file for size there is nothing left to ask anyone,
        // so nobody is asked, now or on any draw while that is remembered.
        val onlyTrustedHasTheFile = cidVerifier.codec(cid) == CidVerifier.CODEC_DAG_PB
        if (onlyTrustedHasTheFile && misses.isRemembered(TOO_LARGE_VERDICT, cid)) {
            android.util.Log.d(TAG, "fetchVerified: $cid was refused for size by the trusted gateway; no gateway asked")
            return Fetched(null, 0, trustedRefusedForSize = true)
        }
        var asked = 0
        var tooLarge = 0
        var notHere = 0
        var trustedNotHere = false
        var trustedTooLarge = false
        for (gateway in gateways) {
            if (misses.isRemembered(gateway, cid)) {
                android.util.Log.d(TAG, "fetchVerified: $gateway said recently it does not have $cid, or not in a size the wallet takes; not asked")
                continue
            }
            asked++
            val result = try {
                tryGateway(gateway, cid)
            } catch (e: TorNotReadyException) {
                // Tor is on and not connected: every gateway would wait and be refused the same
                // way, so stop here rather than wait out each one in turn.
                android.util.Log.i(TAG, "fetchVerified: waiting for Tor; not fetched")
                return Fetched(null, asked - 1, tooLarge, saidNotHere = notHere,
                    trustedSaidNotHere = trustedNotHere, trustedRefusedForSize = trustedTooLarge)
            }
            if (result === TOO_LARGE) {
                tooLarge++
                if (isTrusted(gateway)) {
                    trustedTooLarge = true
                    if (onlyTrustedHasTheFile) {
                        misses.remember(TOO_LARGE_VERDICT, cid)
                        android.util.Log.i(TAG, "fetchVerified: $cid is over $MAX_RESPONSE_SIZE bytes at the trusted gateway, " +
                            "the only one that serves the file; no other gateway asked")
                        return Fetched(null, asked, tooLarge, saidNotHere = notHere,
                            trustedSaidNotHere = trustedNotHere, trustedRefusedForSize = true)
                    }
                }
                continue
            }
            if (result === NOT_HERE) {
                notHere++
                if (isTrusted(gateway)) trustedNotHere = true
                continue
            }
            if (result != null) {
                android.util.Log.i(TAG, "fetchVerified: OK via $gateway (${result.bytes.size}b" +
                    (if (result.wholeFile) ")" else "; a block, not known to be the whole file)"))
                // It has it now: a later "not here" from it starts at the short wait again.
                misses.forget(gateway, cid)
                return Fetched(result.bytes, asked - 1, tooLarge, wholeFile = result.wholeFile, saidNotHere = notHere,
                    trustedSaidNotHere = trustedNotHere)
            }
        }
        if (asked == 0) {
            // Not a failure of anything: said so, so a missing name or picture can be traced to it.
            android.util.Log.i(TAG, "fetchVerified: no gateway asked for $cid; each said within " +
                "the last ${MISS_TTL_MS / 60_000} min at most that it does not have it, or not in a size the wallet takes")
        } else if (tooLarge == asked) {
            android.util.Log.i(TAG, "fetchVerified: $cid is over $MAX_RESPONSE_SIZE bytes at every gateway asked; not fetched")
        } else if (trustedTooLarge) {
            android.util.Log.i(TAG, "fetchVerified: $cid is over $MAX_RESPONSE_SIZE bytes at the trusted gateway, and no other gateway gave it; not fetched")
        } else {
            android.util.Log.w(TAG, "fetchVerified: all gateways failed for $cid")
        }
        return Fetched(null, asked, tooLarge, saidNotHere = notHere,
            trustedSaidNotHere = trustedNotHere, trustedRefusedForSize = trustedTooLarge)
    }

    /** One gateway's accepted answer, and whether it is the whole file ([Fetched.wholeFile]). */
    private class Answer(val bytes: ByteArray, val wholeFile: Boolean)

    /** What [tryGateway] returns for an answer refused for size and nothing else. Compared by identity. */
    private val TOO_LARGE = Answer(ByteArray(0), false)

    /** What [tryGateway] returns for a gateway's "not here" ([isMiss]). Compared by identity. */
    private val NOT_HERE = Answer(ByteArray(0), false)

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun tryGateway(gateway: String, cid: String): Answer? {
        return try {
            val url = gatewayUrl(gateway, cid) ?: run {
                android.util.Log.w(TAG, "tryGateway: not a cid; nothing requested")
                return null
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.ipld.raw")
                .build()

            // Decided from the configured gateway, before the request: a trusted gateway is asked on
            // the client that follows no redirect, so the answer read below is that host's own.
            val isTrustedGateway = isTrusted(gateway)
            val client = if (isTrustedGateway) trustedGatewayClient else httpClient

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.d(TAG, "tryGateway: $gateway → HTTP ${response.code} for $cid")
                    // "Not here" and "bad request" are answers about this CID at this gateway: kept,
                    // so the next refresh does not ask the same question. Any other failure is not.
                    if (isMiss(response.code)) {
                        misses.remember(gateway, cid)
                        return NOT_HERE
                    }
                    return null
                }

                // Checked while the answer streams, not after it is held: a declared length over the
                // limit is refused unread, and an answer with none stops one byte past the limit.
                val body = response.body ?: run {
                    android.util.Log.w(TAG, "tryGateway: $gateway → no body for $cid")
                    return null
                }
                val declared = body.contentLength()
                val bytes = BoundedBody.bytes(body, MAX_RESPONSE_SIZE) ?: run {
                    // A 200 that BoundedBody refused is over the cap, declared or found while reading:
                    // an answer about this file at this gateway, like "not here", and kept the same
                    // way. Said in its own words: it is not a failed or tampered answer.
                    android.util.Log.i(TAG, "tryGateway: $gateway → $cid is over the $MAX_RESPONSE_SIZE-byte limit" +
                        (if (declared > MAX_RESPONSE_SIZE) " (declares $declared bytes; not read)" else " (no usable length declared; read stopped at the limit)"))
                    misses.remember(gateway, cid)
                    // The trusted gateway serves the file itself, so its declared length is the file's.
                    if (declared > MAX_RESPONSE_SIZE && isTrustedGateway) recordSize(cid, declared)
                    return TOO_LARGE
                }

                // CIDv0 (Qm…) carries the dag-pb / UnixFS wrapper hash,
                // not the raw content hash. Our gateway serves the unwrapped
                // bytes, so content-hash verification would always fail here.
                // For those CIDs, trust rests on the pinned server identity of
                // the gateway in [TRUSTED_GATEWAY_PREFIXES] — a weakening
                // documented there. CIDv1 raw fetches stay strictly content-verified.
                // dag-pb / UnixFS CIDs hash the WRAPPER block, not the raw file content —
                // CIDv0 (Qm…) AND CIDv1 dag-pb (bafybe…). Content-hash verification against
                // the assembled bytes our trusted (cert-pinned) gateway serves can never
                // pass, so defer to the pinned server for those. Only CIDv1 RAW codec (bafkre…)
                // hashes the raw content and stays strictly content-verified. Without the
                // bafybe… case, dag-pb v1 images (e.g. CHANG token art) failed verify on the
                // real 279KB file and "passed" on the 108-byte dag root from a ?format=raw
                // fallback — so the picture never rendered.
                //
                // Which CIDs those are is read from the CID's CODEC ([CidVerifier.codec]), not from
                // how its text begins: `Qm…` and `bafybe…` are two spellings of dag-pb, and the
                // same codec in base58, in base16 or with another hash begins differently.
                val codec = cidVerifier.codec(cid)
                val isDagPbWrapped = codec == CidVerifier.CODEC_DAG_PB
                val byPin = isDagPbWrapped && isTrustedGateway
                if (byPin) {
                    // Nothing checks these bytes against the CID: the pinned server is believed. So
                    // they are taken only when they are ALL of its answer. An answer that is empty,
                    // shorter than it declared, or whose end nothing marked (HTTP/1, no length, not
                    // chunked: it ends when the connection does, and a dropped connection looks the
                    // same) is a FAILED FETCH: not returned as the file, so never judged "not a
                    // picture" and never measured. The next gateway is asked, as after any failure.
                    val incomplete = when {
                        bytes.isEmpty() -> "an empty answer"
                        declared >= 0 && bytes.size.toLong() != declared -> "${bytes.size} of $declared declared bytes"
                        !endIsMarked(response, declared) -> "no length and no end marker (${bytes.size} bytes before the connection closed)"
                        else -> null
                    }
                    if (incomplete != null) {
                        android.util.Log.w(TAG, "tryGateway: $gateway → HTTP ${response.code} for $cid is not a complete file: $incomplete; treated as a failed fetch")
                        return null
                    }
                }
                val accept = if (byPin) {
                    android.util.Log.i(TAG,
                        "tryGateway: $gateway → dag-pb trust-by-cert for $cid (${bytes.size}b)")
                    true
                } else {
                    cidVerifier.verify(cid, bytes)
                }
                if (accept) {
                    // The whole file, from a source that can speak for it ([Fetched.wholeFile]):
                    // the pinned gateway's complete answer, or bytes that hash to a raw-codec CID.
                    // Anything else that verified is a BLOCK (a public gateway's root block of a
                    // dag-pb CID, or a block of some other codec): returned as before, and marked
                    // as not known to be the file, so nothing is concluded about the file from it.
                    val wholeFile = if (byPin) response.code == 200 else codec == CidVerifier.CODEC_RAW
                    if (wholeFile) recordSize(cid, bytes.size.toLong())
                    Answer(bytes, wholeFile)
                } else {
                    android.util.Log.w(TAG, "tryGateway: $gateway → hash MISMATCH for $cid (${bytes.size}b)")
                    null
                }
            }
        } catch (e: TorNotReadyException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d(TAG, "tryGateway: $gateway threw for $cid — ${e.javaClass.simpleName}: ${LogSafe.text(e.message)}")
            null
        }
    }

}
