package dev.norsehorse.vaultpony.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.norsehorse.vaultpony.AppPrefs
import java.util.Locale

/**
 * In-app language selection with live switching. Unlike an AppCompat per-app
 * locale (which recreates the activity), the choice here is applied through a
 * composition-local override: [LanguageState.current] is observable, and
 * [ProvideAppLocale] re-derives a localized Context whenever it changes, so
 * every stringResource re-resolves immediately and the user never leaves the
 * screen they are on.
 */
enum class SupportedLanguage(val tag: String, val nativeName: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    FR("fr", "Français"),
    PT_BR("pt-BR", "Português (Brasil)"),
    RU("ru", "Русский");

    companion object {
        /** Snap a device/BCP-47 tag to the closest supported language, or null. */
        fun resolve(tag: String?): SupportedLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-')
            entries.firstOrNull { it.tag.equals(normalized, ignoreCase = true) }?.let { return it }
            return when (normalized.substringBefore('-').lowercase()) {
                "en" -> EN
                "de" -> DE
                "es" -> ES
                "fr" -> FR
                "pt" -> PT_BR
                "ru" -> RU
                else -> null
            }
        }
    }
}

/** Process-wide observable of the applied language tag (BCP-47). */
object LanguageState {
    val current: MutableState<String> = mutableStateOf("en")

    /** Seed from the saved choice, or the device language on first run. Call
     *  once from the Application before any Composable mounts. */
    fun init(context: Context) {
        val saved = AppPrefs.language(context)
        current.value = if (saved != null) {
            SupportedLanguage.resolve(saved)?.tag ?: SupportedLanguage.EN.tag
        } else {
            LocaleManager.detectInitial(context).tag
        }
    }
}

/**
 * Unwrap a Context (possibly the [ProvideAppLocale] wrapper) to the Activity
 * underneath. Needed because the locale wrapper is a ContextWrapper, so a plain
 * `context as? Activity` no longer succeeds.
 */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

object LocaleManager {
    /** Closest supported language to the device's preferred locales. */
    fun detectInitial(context: Context): SupportedLanguage {
        val locales = context.resources.configuration.locales
        for (i in 0 until locales.size()) {
            SupportedLanguage.resolve(locales.get(i).toLanguageTag())?.let { return it }
        }
        return SupportedLanguage.EN
    }

    /** Persist and apply [lang]. The UI relocalizes live via [ProvideAppLocale]. */
    fun setLanguage(context: Context, lang: SupportedLanguage) {
        AppPrefs.setLanguage(context, lang.tag)
        LanguageState.current.value = lang.tag
    }

    fun current(): SupportedLanguage =
        SupportedLanguage.resolve(LanguageState.current.value) ?: SupportedLanguage.EN
}

/**
 * Wrap the app's Compose content so that all string/resource lookups resolve
 * in the currently-selected language. Re-runs whenever [LanguageState.current]
 * changes, giving an instant, in-place language switch.
 */
@Composable
fun ProvideAppLocale(content: @Composable () -> Unit) {
    val tag by LanguageState.current
    val context = LocalContext.current
    // Wrap the ORIGINAL context (usually the Activity) and swap ONLY its
    // resources for the chosen locale. Because everything else (startActivity,
    // getSystemService, the activity task) still delegates to `context`, links
    // and pickers keep working; only string/resource lookups are localized.
    val localized = remember(tag, context) {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedResources = context.createConfigurationContext(config).resources
        object : ContextWrapper(context) {
            override fun getResources(): Resources = localizedResources
        }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}
