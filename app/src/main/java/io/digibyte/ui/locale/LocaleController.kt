package io.digibyte.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.digibyte.core.locale.AppLocale
import java.util.Locale

/**
 * Stores the chosen language and applies it to the app.
 *
 * ## Why this is hand-rolled
 *
 * `AppCompatDelegate.setApplicationLocales` is the usual answer, but it needs appcompat and an
 * `AppCompatActivity`; this app has neither — `MainActivity` is a `FragmentActivity` and
 * appcompat is not a dependency. Pulling in appcompat and changing the activity's base class to
 * get one setter would be a large change to a wallet's entry point for a small gain.
 *
 * So: persist the tag, wrap the base context (works on minSdk 26), and additionally tell
 * [LocaleManager] on API 33+ so the OS per-app language screen lists the wallet and stays in step
 * with the in-app choice. Users on 33+ can then change it from either place.
 *
 * ## Reading happens before anything exists
 *
 * [wrap] runs from `attachBaseContext`, before the activity, before Compose, before any error
 * handling worth the name. Everything here is total: a bad stored value yields the system
 * language rather than an exception, because a wallet that cannot start cannot be fixed by the
 * person who owns it — and the setting that broke it is the one they would need to reach.
 */
object LocaleController {

    private const val PREFS = "dgb_settings"
    private const val KEY = "app_language"

    /** The stored tag, or [AppLocale.SYSTEM] when the user has not chosen. */
    fun storedTag(context: Context): String =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        }.getOrDefault(AppLocale.SYSTEM)

    /** The chosen entry, or null for "follow the system". */
    fun current(context: Context): AppLocale.Entry? = AppLocale.resolve(storedTag(context))

    /**
     * Persist a choice. Pass null (or [AppLocale.SYSTEM]) to follow the device language.
     *
     * The caller must recreate the activity afterwards — resources are resolved when a context is
     * created, so a running screen keeps the old language until it is rebuilt.
     */
    fun set(context: Context, entry: AppLocale.Entry?) {
        val tag = entry?.tag ?: AppLocale.SYSTEM
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, tag).apply()
        }
        // API 33+ keeps its own per-app language. Without this the OS screen and the in-app
        // setting disagree, and whichever the user last touched silently loses.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (entry == null) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(entry.tag)
            }
        }
    }

    /**
     * Wrap a base context so resources resolve in the chosen language.
     *
     * Called from `attachBaseContext`. Returns the context unchanged when following the system,
     * which is both the default and the fallback for anything unreadable.
     */
    fun wrap(base: Context): Context {
        val entry = runCatching { current(base) }.getOrNull() ?: return base
        return runCatching {
            val locale = entry.toLocale()
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }
}
