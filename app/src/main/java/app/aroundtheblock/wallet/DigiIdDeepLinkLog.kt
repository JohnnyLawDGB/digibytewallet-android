package app.aroundtheblock.wallet

/** What a log line may say about a Digi-ID deep link. */
internal object DigiIdDeepLinkLog {

    /**
     * [host] as a log line carries it: every character outside printable ASCII (0x21..0x7E, the
     * range `DigiIdRequest.parse` accepts in an authority) is written as '?'. A deep link can come
     * from any app or web page, and `Uri.getHost()` decodes percent escapes, so a host can hold a
     * line break or another control character that would end the log line and start one the app
     * never wrote.
     */
    fun host(host: String?): String? =
        host?.map { if (it.code in 0x21..0x7e) it else '?' }?.joinToString("")
}
