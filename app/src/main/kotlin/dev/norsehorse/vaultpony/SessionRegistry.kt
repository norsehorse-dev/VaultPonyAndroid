package dev.norsehorse.vaultpony

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
     *  resulting background transition doesn't lock at once. Consumed once. */
    @Volatile
    var ignoreNextBackground = false

    // Bumped when a lock actually closed a session; the UI observes it to
    // return home. Not bumped when nothing was open, so a half-filled unlock
    // screen survives a trip to the password manager (plan §2.3).
    private val _lockGeneration = MutableStateFlow(0)
    val lockGeneration = _lockGeneration.asStateFlow()

    // Auto-lock delay (plan §2.3). The deadline is on elapsedRealtime, which
    // keeps counting in deep sleep and while the cached-app freezer holds the
    // process; the Handler timer does neither, so every entry point re-checks
    // the deadline instead of trusting the timer alone. Guarded by this.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var inBackground = false
    private var lockDeadline = 0L
    private val delayedLock = Runnable { lockIfDue() }

    // An in-app picker exempts its own trip to the background, but only this
    // long: a picker left open (or an app switch from inside it) still locks.
    private const val PICKER_CAP_MS = 3 * 60_000L

    // Hold-off for a bulk import/export still running when the deadline
    // arrives. Locking under it would fail its next write, so the batch is
    // asked to stop at its next chunk boundary (temp file removed, vault
    // flushed, summary says stopped) and the lock waits for it, but never
    // longer than HOLD_CAP_MS: a stuck SAF read must not keep the key in
    // memory. holdUntil is elapsedRealtime, 0 = no hold.
    private const val HOLD_CAP_MS = 5_000L
    private var holdUntil = 0L
    private val holdExpired = Runnable { endHold() }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Call immediately before launching an in-app file picker. */
    fun expectPicker() {
        ignoreNextBackground = true
    }

    /** App went to background/screen-off: lock now or after the chosen delay
     *  (at least [PICKER_CAP_MS] when an in-app picker caused it). A batch
     *  keeps running until the deadline. Main thread. */
    fun onAppBackgrounded() {
        val userDelay = appContext?.let { AppPrefs.autoLockDelayMs(it) } ?: 0L
        val delay = if (ignoreNextBackground) {
            ignoreNextBackground = false
            maxOf(userDelay, PICKER_CAP_MS)
        } else {
            userDelay
        }
        synchronized(this) {
            inBackground = true
            lockDeadline = SystemClock.elapsedRealtime() + delay
        }
        mainHandler.removeCallbacks(delayedLock)
        if (delay <= 0L) lockIfDue() else mainHandler.postDelayed(delayedLock, delay)
    }

    /** App is coming back to the foreground. Runs before the first frame, so
     *  an overdue lock happens before any vault content can show. A lock that
     *  was being held for a batch happens now too: its next write fails and
     *  only its temp file is left, which the next import sweeps. Main thread. */
    fun onAppForegrounded() {
        mainHandler.removeCallbacks(delayedLock)
        synchronized(this) {
            val due = inBackground && lockDeadline != 0L &&
                SystemClock.elapsedRealtime() >= lockDeadline
            if (due) lockAll()
            inBackground = false
            lockDeadline = 0L
        }
    }

    /** Lock if the background deadline has passed. If a batch is running it
     *  gets the hold first; the lock then comes from [endHold]. */
    @Synchronized
    private fun lockIfDue() {
        if (!inBackground || lockDeadline == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now < lockDeadline) return
        if (holdUntil != 0L) {
            // Already holding for a batch: wait for it, up to the cap.
            if (now < holdUntil) return
            lockAll()
            return
        }
        // Set before asking the batch to stop, so a batch that finishes at
        // once still finds the hold to release (endHold waits on this lock).
        holdUntil = now + HOLD_CAP_MS
        if (TransferJobs.stopForBackground { endHold() }) {
            mainHandler.postDelayed(holdExpired, HOLD_CAP_MS)
            return
        }
        holdUntil = 0L
        lockAll()
    }

    /** The batch has cleaned up (called from its worker right after its
     *  flush), or the cap ran out: lock now if the deadline still stands. */
    private fun endHold() {
        mainHandler.removeCallbacks(holdExpired)
        synchronized(this) {
            if (holdUntil == 0L) return
            holdUntil = 0L
            val due = inBackground && lockDeadline != 0L &&
                SystemClock.elapsedRealtime() >= lockDeadline
            // Straight to lockAll, not lockIfDue: a batch stuck past the cap
            // must not be offered another hold.
            if (due) lockAll()
        }
    }

    @Synchronized
    fun put(volumeId: String, session: VaultSession) {
        sessions.remove(volumeId)?.lock()
        sessions[volumeId] = session
        notifyRootsChanged()
        // An unlock that finished while the app sat in the background past its
        // deadline must not survive (the deadline lock found nothing to close).
        lockIfDue()
    }

    @Synchronized
    fun get(volumeId: String): VaultSession? {
        lockIfDue()
        return sessions[volumeId]
    }

    @Synchronized
    fun ids(): List<String> {
        lockIfDue()
        return sessions.keys.toList()
    }

    /** True while [session] is registered (unlocked and not auto-locked). */
    @Synchronized
    fun isOpen(session: VaultSession): Boolean {
        lockIfDue()
        return sessions.values.any { it === session }
    }

    @Synchronized
    fun lock(volumeId: String) {
        val removed = sessions.remove(volumeId) ?: return
        removed.lock()
        notifyRootsChanged()
    }

    @Synchronized
    fun lockAll() {
        if (sessions.isEmpty()) return
        sessions.values.forEach { it.lock() }
        sessions.clear()
        notifyRootsChanged()
        _lockGeneration.value = _lockGeneration.value + 1
    }

    private fun notifyRootsChanged() {
        val ctx = appContext ?: return
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }
}
