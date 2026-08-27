package io.digibyte.core.locale

import java.util.Locale

/**
 * The label for a language control, always written in the language it refers to.
 *
 * Kept out of the Compose layer so the rule it encodes — never make an English word the only way
 * to escape English — is stated once and tested, rather than living inside a UI expression where
 * the next person to touch the screen may quietly replace it with "Language".
 */
object LanguageChipLabel {

    /** Shown when a locale supplies no name for itself; never surfaces an exception. */
    private const val UNNAMED = "—"

    /**
     * @param chosen the user's explicit choice, or null for "follow the device".
     * @param device the device locale, used only when there is no explicit choice.
     */
    fun forChoice(chosen: AppLocale.Entry?, device: Locale): String {
        if (chosen != null) return chosen.endonym
        return runCatching { device.getDisplayLanguage(device) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: UNNAMED
    }
}
