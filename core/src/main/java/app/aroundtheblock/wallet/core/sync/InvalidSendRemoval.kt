package app.aroundtheblock.wallet.core.sync

/**
 * Whether the stranded-send sweep may remove a send the core now calls invalid (4.1.2 row 30).
 *
 * The sweep removed such a send at the first "invalid" it saw, on the premise that invalid means "spent
 * by another CONFIRMED transaction". It does not: the core also marks invalid an unconfirmed send that
 * lost an ordering race to another unconfirmed wallet transaction spending the same coin (the N8 case,
 * or a second device on the same seed), and that send can still be the one that confirms (Aguz Laks
 * 1a14a72d). So the verdict must hold, as the peer-refusal verdict does: [hold] answers true only once
 * the send has been invalid at every observation for its hold time on one peer manager. A send that
 * reads valid again in between starts over. On DigiByte's ~15 s blocks a confirmed winner settles it
 * well inside the hold.
 */
object InvalidSendRemoval {
    /** The service's one hold is shared with the peer-refusal verdict; these sightings have their own key. */
    private fun key(txid: String) = "invalid:$txid"

    fun mayRemove(hold: RefusalHold, txid: String, isValid: () -> Boolean): Boolean =
        hold.observe(key(txid)) { !isValid() }

    fun forget(hold: RefusalHold, txid: String) = hold.forget(key(txid))
}
