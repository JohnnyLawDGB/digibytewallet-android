package app.aroundtheblock.wallet.ui.settings

import app.aroundtheblock.wallet.core.security.KeystoreKeyInvalidatedException
import app.aroundtheblock.wallet.core.security.KeystoreUserAuthRequiredException

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

    /**
     * SEED-VIEW-AUTH: the auth-bound key is outside its device-unlock window
     * (docs/specs/keystore-auth-binding.md). Nothing is shown; the device lock must be confirmed and the
     * read made again — which "Try again" does, prompting afresh every time.
     */
    data object DeviceLockNeeded : SeedBackupView()

    /** The device lock screen was removed and the key with it: only the written phrase restores this
     *  wallet. Never retried, never prompted for. */
    data object KeyInvalidated : SeedBackupView()

    companion object {
        /**
         * SEED-VIEW-AUTH: [read]; when the key needs the device lock, [confirmDeviceLock] once and, if it
         * was confirmed, [read] exactly once more. A prompt not confirmed, or a key still refused after
         * one, ends in [DeviceLockNeeded], from which the screen's "Try again" runs this again. A
         * [Shown] read while the app is not [unlocked] is discarded the same way.
         */
        suspend fun readConfirmingDeviceLock(
            read: suspend () -> SeedBackupView,
            confirmDeviceLock: suspend () -> Boolean,
            unlocked: () -> Boolean,
        ): SeedBackupView {
            val first = read()
            val view = when {
                first !== DeviceLockNeeded -> first
                !confirmDeviceLock() -> DeviceLockNeeded
                else -> read()
            }
            // The app may have locked while this read or its prompt ran (on return from the
            // credential activity): the words are not handed to a locked app. Try again reads anew.
            return if (view is Shown && !unlocked()) DeviceLockNeeded else view
        }

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
                return keyRefusal(e) ?: PassphraseUnreadable
            }
            val shownWords = try {
                words(mnemonic)
            } catch (e: Exception) {
                bytes?.fill(0)
                return keyRefusal(e) ?: throw e
            }
            val shownPassphrase = bytes?.let { String(it, Charsets.UTF_8).also { _ -> it.fill(0) } }
            return Shown(shownWords, shownPassphrase)
        }

        private fun keyRefusal(e: Exception): SeedBackupView? = when (e) {
            is KeystoreUserAuthRequiredException -> DeviceLockNeeded
            is KeystoreKeyInvalidatedException -> KeyInvalidated
            else -> null
        }

        /** Throws only the key's refusals ([keyRefusal]); any other failure reads as placeholder words. */
        private fun words(mnemonic: () -> ByteArray?): List<String> = try {
            val decrypted = mnemonic()
            if (decrypted != null) {
                val phrase = String(decrypted, Charsets.UTF_8)
                decrypted.fill(0) // zero the ByteArray after use
                phrase.trim().split(" ")
            } else {
                List(12) { "••••••" }
            }
        } catch (e: KeystoreUserAuthRequiredException) {
            throw e
        } catch (e: KeystoreKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.e("SeedViewScreen", "Failed to decrypt the recovery words: ${e.javaClass.simpleName}") }
            List(12) { "error" }
        }
    }
}
