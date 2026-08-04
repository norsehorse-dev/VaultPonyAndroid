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

    /** First-run onboarding gate. */
    fun onboardingDone(c: Context): Boolean = p(c).getBoolean("onboarding_done", false)

    fun setOnboardingDone(c: Context, v: Boolean) =
        p(c).edit().putBoolean("onboarding_done", v).apply()
}
