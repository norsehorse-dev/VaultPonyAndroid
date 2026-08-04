package dev.norsehorse.vaultpony

import android.content.Context
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.vault_ffi.VaultSession

/**
 * The mount table, app-side: unlocked sessions keyed by a stable volume id
 * (derived from the persisted SAF URI). One lock path — [lockAll] — feeds
 * auto-lock, screen-off policy, and the explicit lock action (doc §8).
 *
 * Every mutation notifies the DocumentsProvider's roots URI, so the system
 * Files app adds a root the instant a volume unlocks and drops it the instant
 * it locks (doc §8 — volumes appear system-wide).
 */
object SessionRegistry {
    /** Must match the provider authority declared in AndroidManifest.xml. */
    const val AUTHORITY = "dev.norsehorse.vaultpony.documents"

    private val sessions = LinkedHashMap<String, VaultSession>()
    private var appContext: Context? = null

    /** Set true right before the app launches its own SAF picker, so the
     *  resulting background transition doesn't auto-lock. Consumed once. */
    @Volatile
    var ignoreNextBackground = false

    // Bumped on every lock; the UI observes it to return to the unlock screen.
    private val _lockGeneration = MutableStateFlow(0)
    val lockGeneration = _lockGeneration.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Call immediately before launching an in-app file picker. */
    fun expectPicker() {
        ignoreNextBackground = true
    }

    /** App went to background/screen-off: lock unless a picker is in flight. */
    fun onAppBackgrounded() {
        if (ignoreNextBackground) {
            ignoreNextBackground = false
            return
        }
        lockAll()
    }

    @Synchronized
    fun put(volumeId: String, session: VaultSession) {
        sessions.remove(volumeId)?.lock()
        sessions[volumeId] = session
        notifyRootsChanged()
    }

    @Synchronized
    fun get(volumeId: String): VaultSession? = sessions[volumeId]

    @Synchronized
    fun ids(): List<String> = sessions.keys.toList()

    @Synchronized
    fun lock(volumeId: String) {
        val removed = sessions.remove(volumeId) ?: return
        removed.lock()
        notifyRootsChanged()
    }

    @Synchronized
    fun lockAll() {
        val had = sessions.isNotEmpty()
        sessions.values.forEach { it.lock() }
        sessions.clear()
        if (had) notifyRootsChanged()
        // Always signal, so the UI leaves any unlocked screen even if the
        // registry was already empty (belt and suspenders).
        _lockGeneration.value = _lockGeneration.value + 1
    }

    private fun notifyRootsChanged() {
        val ctx = appContext ?: return
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }
}
