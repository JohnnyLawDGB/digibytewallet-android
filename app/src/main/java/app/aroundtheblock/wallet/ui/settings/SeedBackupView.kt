package app.aroundtheblock.wallet.ui.settings

/**
 * What the recovery-phrase screen shows, read from the wallet: the words, and the passphrase when the
 * wallet has one. A plain function so the decision runs on a JVM.
 */
internal sealed class SeedBackupView {
    /** The words, and the passphrase when the wallet has one. */
    class Shown(val words: List<String>, val passphrase: String?) : SeedBackupView()

    /**
     * The passphrase could not be read — the record cannot say whether there is one, or the Keystore
     * could not answer just now. The words are not shown: without the passphrase they may not restore
     * this wallet, and shown alone they would be written down as though they were the whole backup.
     */
    data object PassphraseUnreadable : SeedBackupView()

    companion object {
        /**
         * The passphrase first: when it cannot be read, the words are not read at all.
         *
         * @param passphrase the stored passphrase's bytes (null: none); zeroed here once read.
         * @param mnemonic the recovery words' bytes (null: none stored); zeroed here once read.
         */
        fun read(passphrase: () -> ByteArray?, mnemonic: () -> ByteArray?): SeedBackupView {
            val bytes = try {
                passphrase()
            } catch (e: Exception) {
                runCatching { android.util.Log.w("SeedViewScreen", "stored envelope not readable (${e.javaClass.simpleName})") }
                return PassphraseUnreadable
            }
            val shownPassphrase = bytes?.let { String(it, Charsets.UTF_8).also { _ -> it.fill(0) } }
            return Shown(words(mnemonic), shownPassphrase)
        }

        private fun words(mnemonic: () -> ByteArray?): List<String> = try {
            val decrypted = mnemonic()
            if (decrypted != null) {
                val phrase = String(decrypted, Charsets.UTF_8)
                decrypted.fill(0) // zero the ByteArray after use
                phrase.trim().split(" ")
            } else {
                List(12) { "••••••" }
            }
        } catch (e: Exception) {
            runCatching { android.util.Log.e("SeedViewScreen", "Failed to decrypt the recovery words: ${e.javaClass.simpleName}") }
            List(12) { "error" }
        }
    }
}
