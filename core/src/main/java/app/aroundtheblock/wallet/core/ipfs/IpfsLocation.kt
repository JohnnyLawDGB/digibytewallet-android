package app.aroundtheblock.wallet.core.ipfs

/**
 * The IPFS object a piece of asset metadata names: a CID, and whatever followed it.
 *
 * ONE copy of "which file does this text name". Metadata writes the same file four ways, and the
 * picture loader (`AssetImageResolver`, which builds its `ipfs://` model from [uri]) and the media
 * model ([app.aroundtheblock.wallet.core.model.AssetMediaEntry.cid]) must agree on all of them:
 *
 *  - a bare CID (CIDv0 `Qm…`, CIDv1 `b…`, `z…`, `f…`);
 *  - `ipfs://<cid>[/path]`;
 *  - `/ipfs/<cid>[/path]`;
 *  - an IPFS gateway URL, `https://<any host>/ipfs/<cid>[/path]` or the subdomain form
 *    `https://<cid>.ipfs.<host>[/path]`. Only the CID and path are taken from it: the host it
 *    names is never contacted (4.1.2 row 28).
 *
 * Everything else names no IPFS object and [parse] returns null: any other `http(s)://` URL, a
 * `data:` URI, a word, `ipfs://null`.
 *
 * @param cid  the CID, as written.
 * @param rest what followed it, as written: empty, or a path, query or fragment (`/logo.png`).
 */
class IpfsLocation private constructor(val cid: String, val rest: String) {

    /** `ipfs://<cid>[rest]`: the form the picture loader dispatches on. */
    val uri: String get() = "ipfs://$cid$rest"

    /** The path inside the CID, without query or fragment and without trailing slashes; empty for the CID itself. */
    val path: String get() = rest.substringBefore('?').substringBefore('#').trimEnd('/')

    /**
     * True when this names something INSIDE the CID (`<cid>/song.mp3`): the CID is then a
     * directory and the file is another object. The wallet's client fetches whole CIDs only, and
     * the bare CID is not this file.
     */
    val hasPath: Boolean get() = path.isNotEmpty()

    /**
     * True when [other] names the same file: the same CID text and the same path. A query or a
     * fragment does not change which file it is. The same content under two different CID
     * spellings (CIDv0 and its CIDv1, or two multibases) is NOT recognised as the same.
     */
    fun sameFileAs(other: IpfsLocation): Boolean = cid == other.cid && path == other.path

    companion object {
        private val CID_V0_REGEX = Regex("^Qm[1-9A-HJ-NP-Za-km-z]{44}$")

        // CIDv1: one of several multibase prefixes followed by at least ~48 chars.
        // Loose bound is intentional — strict decoding lives in CidVerifier.
        private val CID_V1_REGEX = Regex("^(b[a-z2-7]{58,}|z[1-9A-HJ-NP-Za-km-z]{48,}|f[0-9a-fA-F]{60,})$")

        fun parse(raw: String?): IpfsLocation? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) ->
                    gatewayCid(trimmed)?.let { IpfsLocation(it.substringBefore('/'), it.substring(it.substringBefore('/').length)) }
                trimmed.startsWith("data:", ignoreCase = true) -> null
                // Only a real CID (IPFS-NO-NULL): `ipfs://null`, or any other word after the scheme, names nothing.
                trimmed.startsWith("ipfs://") -> afterScheme(trimmed.removePrefix("ipfs://"))
                trimmed.startsWith("/ipfs/") -> afterScheme(trimmed.removePrefix("/ipfs/"))
                isCid(trimmed) -> IpfsLocation(trimmed, "")
                else -> null
            }
        }

        /** [rest] = `<cid>[/path]`, or null when it names no CID. */
        private fun afterScheme(rest: String): IpfsLocation? {
            // The CID ends where android.net.Uri ends the host: at a path, a query or a fragment.
            val cid = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            if (IpfsClient.normalizeCid(cid) == null) return null
            return IpfsLocation(cid, rest.substring(cid.length))
        }

        /** The `<cid>[/path]` an IPFS gateway URL names, or null when the URL is not one. */
        private fun gatewayCid(url: String): String? {
            val afterScheme = url.substringAfter("://")
            val host = afterScheme.substringBefore('/').substringBefore('?').lowercase()
            val path = afterScheme.substringAfter('/', missingDelimiterValue = "").substringBefore('?').substringBefore('#')
            // https://<cid>.ipfs.<gateway>/<path>
            val sub = host.substringBefore(".ipfs.", missingDelimiterValue = "")
            if (sub.isNotEmpty() && isCid(sub)) return if (path.isEmpty()) sub else "$sub/$path"
            // https://<gateway>/ipfs/<cid>/<path>
            if (!path.startsWith("ipfs/")) return null
            val rest = path.removePrefix("ipfs/")
            val cid = rest.substringBefore('/')
            return if (isCid(cid)) rest else null
        }

        private fun isCid(s: String): Boolean =
            CID_V0_REGEX.matches(s) || CID_V1_REGEX.matches(s)
    }
}
