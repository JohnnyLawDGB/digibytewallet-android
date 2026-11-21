package app.aroundtheblock.wallet.core.network

import org.json.JSONObject

/**
 * The text under [key], or null when the key is absent, JSON `null`, or empty.
 *
 * Use this, not `optString`, for a value that decides something. Android's `JSONObject.optString`
 * turns a JSON `null` into the four-letter text "null" (`String.valueOf(JSONObject.NULL)`), where
 * the org.json build the unit tests run on returns the fallback. So `{"cid": null}` read with
 * `optString("cid").takeIf { it.isNotEmpty() }` is no CID in a test and the CID "null" on a phone,
 * which is how the wallet came to ask a gateway for `/api/ipfs/null` (IPFS-NO-NULL). This reads the
 * value itself, so it answers the same on both.
 *
 * A number or boolean is still given as its text, as `optString` gave it.
 */
internal fun JSONObject.textOrNull(key: String): String? = when (val value = opt(key)) {
    null, JSONObject.NULL -> null
    is String -> value.takeIf { it.isNotEmpty() }
    else -> value.toString().takeIf { it.isNotEmpty() }
}
