package dev.norsehorse.vaultpony

import android.content.Context

/** Small settings store (doc §8, §11). Privacy is a user choice. */
object AppPrefs {
    private const val FILE = "vaultpony_prefs"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Remember opened vaults on the home list. Off = no-trace: nothing about
     *  which containers exist or where is stored. Turning it off wipes the
     *  existing list. */
    fun rememberVaults(c: Context): Boolean = p(c).getBoolean("remember_vaults", true)

    fun setRememberVaults(c: Context, v: Boolean) {
        p(c).edit().putBoolean("remember_vaults", v).apply()
        if (!v) VaultStore.clear(c)
    }

    /** FLAG_SECURE: block screenshots and hide contents in Recents. On by
     *  default; the deniability posture wants it (doc §8, §11). */
    fun secureScreen(c: Context): Boolean = p(c).getBoolean("secure_screen", true)

    fun setSecureScreen(c: Context, v: Boolean) =
        p(c).edit().putBoolean("secure_screen", v).apply()

    /** Selected UI language tag (BCP-47), or null to follow the device. */
    fun language(c: Context): String? = p(c).getString("language", null)

    fun setLanguage(c: Context, tag: String) =
        p(c).edit().putString("language", tag).apply()

    /** Auto-lock delay choices in ms, in Settings order (plan §2.3, D5). 0 =
     *  lock the moment the app leaves the foreground, the default. Tops out at
     *  1 hour; there is deliberately no "never". */
    val AUTO_LOCK_DELAYS_MS = longArrayOf(0L, 30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)

    /** How long sessions stay unlocked after the app is backgrounded or the
     *  screen turns off. Anything not in [AUTO_LOCK_DELAYS_MS] reads as 0. */
    fun autoLockDelayMs(c: Context): Long =
        p(c).getLong("auto_lock_delay_ms", 0L).takeIf { it in AUTO_LOCK_DELAYS_MS } ?: 0L

    fun setAutoLockDelayMs(c: Context, v: Long) =
        p(c).edit().putLong("auto_lock_delay_ms", v).apply()

    /** First-run onboarding gate. */
    fun onboardingDone(c: Context): Boolean = p(c).getBoolean("onboarding_done", false)

    fun setOnboardingDone(c: Context, v: Boolean) =
        p(c).edit().putBoolean("onboarding_done", v).apply()
}
