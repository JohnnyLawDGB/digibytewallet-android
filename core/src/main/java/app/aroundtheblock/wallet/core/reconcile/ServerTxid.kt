package app.aroundtheblock.wallet.core.reconcile

/**
 * A transaction id as a server wrote it, in the wallet's one spelling (UTXO-DEDUP-CASE).
 *
 * A txid is 32 bytes and its hex has two spellings per letter. The wallet keeps txids as text and
 * compares them as text: map keys, set members, `==`. The core writes lower case, so that is the
 * spelling. A server's answer is the one place another spelling can enter, and every txid read
 * from one passes through here before it is stored in a row, used as a key or compared. One coin
 * listed under two spellings is then one coin, and a row finds its parent transaction by a plain
 * lookup.
 *
 * Only the ASCII letters are folded, as the core folds them (BRAssetSendPlan.h): a string that is
 * not hex stays whatever it was, and is refused where a txid is decoded.
 *
 * `ServerTxidSpellingTest` holds the rule and the gate: a `"txid"` member of a JSON answer is not
 * read anywhere in the app without this.
 */
internal fun canonicalTxid(fromServer: String): String {
    if (fromServer.none { it in 'A'..'Z' }) return fromServer
    return buildString(fromServer.length) {
        for (c in fromServer) append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
    }
}
