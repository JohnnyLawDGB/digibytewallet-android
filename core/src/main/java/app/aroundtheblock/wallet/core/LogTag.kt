package app.aroundtheblock.wallet.core

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A transaction's tag for a log line (4.1.3, review R7): the first 8 hex characters of
 * HMAC-SHA256(this install's key, the txid as 64 lowercase hex characters) -- the same value the C core
 * prints (BRLogTxTag), so Kotlin and native lines about one transaction still match. A txid prefix is
 * near-unique on chain and tied a captured log to the wallet's transactions; a tag correlates only lines
 * of this install's logs. The key is 32 random bytes made once per install ([PREFS], erased with the
 * wallet) and is not a secret of the wallet. Until [init] has run every tag reads "--------".
 */
object LogTag {
    const val PREFS = "dgb_log_tag"
    private const val KEY = "k"
    private const val NONE = "--------"

    @Volatile private var key: ByteArray? = null

    /** Loads (or makes) this install's key and hands it to the core. Call once, after the native library loads. */
    fun init(context: Context, toNative: (ByteArray) -> Unit = {}) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)?.let { hex -> runCatching { hexToBytes(hex) }.getOrNull() }
        val k = stored?.takeIf { it.size == 32 } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            prefs.edit().putString(KEY, it.joinToString("") { b -> "%02x".format(b) }).commit()
        }
        setKey(k)
        runCatching { toNative(k) }
    }

    internal fun setKey(k: ByteArray?) { key = k?.copyOf() }

    /** [txid] (display order, 64 hex characters) as its tag; "--------" without a key or for anything else. */
    fun tx(txid: String?): String {
        val k = key ?: return NONE
        val t = txid?.lowercase() ?: return NONE
        if (t.length != 64 || t.any { it !in '0'..'9' && it !in 'a'..'f' }) return NONE
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(t.toByteArray(Charsets.US_ASCII))
        return mac.take(4).joinToString("") { "%02x".format(it) }
    }

    /** "txid:vout" as "<tag>:vout". */
    fun outpoint(op: String?): String {
        val at = op?.lastIndexOf(':') ?: -1
        return if (op == null || at < 0) NONE else tx(op.substring(0, at)) + op.substring(at)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
