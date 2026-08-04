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
}
