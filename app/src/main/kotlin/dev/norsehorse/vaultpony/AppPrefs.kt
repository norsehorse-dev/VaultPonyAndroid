package dev.norsehorse.vaultpony

import android.content.Context

/** How the home screen's vault list treats what it stores (2.0.0 plan §5.4). */
enum class VaultListMode {
    /** File names shown, the default and the pre-2.0.0 behaviour. */
    SHOW,

    /** "Vault 1", "Vault 2"... No display names are stored. */
    HIDE,

    /** Nothing kept: a vault leaves the list, with its biometric unlock and
     *  remembered hash, when it locks. The old "remember my vaults" off. */
    NONE,
}

/** Browser view and sort, app-wide rather than per vault (plan §5.3). */
enum class BrowserSort { NAME, SIZE, DATE }

/** Small settings store (doc §8, §11). Privacy is a user choice. */
object AppPrefs {
    private const val FILE = "vaultpony_prefs"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The vault list setting. Before 2.0.0 rc4 this was the "remember my
     *  vaults" switch: on reads as [VaultListMode.SHOW], off as
     *  [VaultListMode.NONE]. */
    fun vaultListMode(c: Context): VaultListMode =
        when (p(c).getString("vault_list", null)) {
            "show" -> VaultListMode.SHOW
            "hide" -> VaultListMode.HIDE
            "none" -> VaultListMode.NONE
            else -> if (p(c).getBoolean("remember_vaults", true)) VaultListMode.SHOW else VaultListMode.NONE
        }

    /** Store [mode] and apply what it means to the list right away: "Hide
     *  names" wipes the stored names, "Don't keep" deletes every entry (with
     *  its persisted file permission, biometric unlock and remembered hash).
     *  [keepUris]: containers that are unlocked right now keep their file
     *  permission until they lock. */
    fun setVaultListMode(c: Context, mode: VaultListMode, keepUris: Set<String> = emptySet()) {
        val value = when (mode) {
            VaultListMode.SHOW -> "show"
            VaultListMode.HIDE -> "hide"
            VaultListMode.NONE -> "none"
        }
        p(c).edit().putString("vault_list", value).remove("remember_vaults").apply()
        when (mode) {
            VaultListMode.SHOW -> Unit
            VaultListMode.HIDE -> VaultStore.wipeNames(c)
            VaultListMode.NONE -> VaultStore.deleteAll(c, keepUris)
        }
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

    /** "Until I lock it" (D15, issue #4): no timer at all. Offered only while
     *  VaultPony may post its "A vault is unlocked" notification. */
    const val AUTO_LOCK_UNTIL_LOCKED = -1L

    /** What a saved "Until I lock it" reads as while notifications are off. */
    const val AUTO_LOCK_UNTIL_FALLBACK = 3_600_000L

    /** Auto-lock delay choices in ms, in Settings order (plan §2.3, D5, D15).
     *  0 = lock the moment the app leaves the foreground, the default. The
     *  timed choices top out at 1 hour; [AUTO_LOCK_UNTIL_LOCKED] comes last. */
    val AUTO_LOCK_DELAYS_MS = longArrayOf(
        0L, 30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, AUTO_LOCK_UNTIL_LOCKED,
    )

    /** How long sessions stay unlocked after the app is backgrounded or the
     *  screen turns off. Anything not in [AUTO_LOCK_DELAYS_MS] reads as 0. A
     *  saved "Until I lock it" reads as 1 hour while the "Unlocked vaults"
     *  notification can't be shown (plan §11.1). */
    fun autoLockDelayMs(c: Context): Long {
        val v = p(c).getLong("auto_lock_delay_ms", 0L).takeIf { it in AUTO_LOCK_DELAYS_MS } ?: 0L
        if (v == AUTO_LOCK_UNTIL_LOCKED && !VaultNotifications.unlockedAllowed(c)) return AUTO_LOCK_UNTIL_FALLBACK
        return v
    }

    fun setAutoLockDelayMs(c: Context, v: Long) =
        p(c).edit().putLong("auto_lock_delay_ms", v).apply()

    /** The notification-permission explanation (Android 13+) was shown once,
     *  either when a delay longer than Immediately was first picked or when
     *  the first import/export started. It is never shown again. */
    fun notificationRationaleShown(c: Context): Boolean = p(c).getBoolean("notif_rationale_shown", false)

    fun setNotificationRationaleShown(c: Context, v: Boolean) =
        p(c).edit().putBoolean("notif_rationale_shown", v).apply()

    /** First-run onboarding gate. */
    fun onboardingDone(c: Context): Boolean = p(c).getBoolean("onboarding_done", false)

    fun setOnboardingDone(c: Context, v: Boolean) =
        p(c).edit().putBoolean("onboarding_done", v).apply()

    // -- Browser view and sort (plan §5.3) --------------------------------

    fun browserGrid(c: Context): Boolean = p(c).getBoolean("browser_grid", false)

    fun setBrowserGrid(c: Context, v: Boolean) =
        p(c).edit().putBoolean("browser_grid", v).apply()

    fun browserSort(c: Context): BrowserSort =
        when (p(c).getString("browser_sort", null)) {
            "size" -> BrowserSort.SIZE
            "date" -> BrowserSort.DATE
            else -> BrowserSort.NAME
        }

    fun setBrowserSort(c: Context, v: BrowserSort) {
        val value = when (v) {
            BrowserSort.NAME -> "name"
            BrowserSort.SIZE -> "size"
            BrowserSort.DATE -> "date"
        }
        p(c).edit().putString("browser_sort", value).apply()
    }

    fun browserSortDescending(c: Context): Boolean = p(c).getBoolean("browser_sort_desc", false)

    fun setBrowserSortDescending(c: Context, v: Boolean) =
        p(c).edit().putBoolean("browser_sort_desc", v).apply()
}
