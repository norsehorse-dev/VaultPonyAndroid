package dev.norsehorse.vaultpony

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.vault_ffi.VaultSession
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The mount table, app-side: unlocked sessions keyed by a stable volume id
 * (derived from the persisted SAF URI). Every lock goes through [lockAll] or
 * [lock]; auto-lock, the Lock buttons (in-app, notification, Quick Settings
 * tile) and screen-off all end there.
 *
 * Every mutation notifies the DocumentsProvider's roots URI, so the system
 * Files app adds a root the instant a volume unlocks and drops it the instant
 * it locks (doc §8: volumes appear system-wide), and refreshes the "Vault
 * unlocked" notification and the tile (plan §4.3, R2).
 */
object SessionRegistry {
    /** Must match the provider authority declared in AndroidManifest.xml. */
    const val AUTHORITY = "dev.norsehorse.vaultpony.documents"

    private val sessions = LinkedHashMap<String, VaultSession>()

    // Provider-facing token per unlocked volume id (plan §4.1, rc2 decision):
    // minted fresh on every unlock, dropped on lock. The DocumentsProvider
    // builds every root and document id from it, never from the volume id,
    // so nothing another app stored resolves after a lock.
    private val providerTokens = HashMap<String, String>()
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
    // The trip to the background was an in-app picker: no "Vault unlocked"
    // notification for it (the user is still in VaultPony's flow).
    private var pickerTrip = false
    private var lockDeadline = 0L
    // The delay this trip to the background started with, kept so the timer
    // can start late when a batch holds it (R1).
    private var backgroundDelay = 0L
    private val delayedLock = Runnable { lockIfDue() }

    // An in-app picker exempts its own trip to the background, but only this
    // long: a picker left open (or an app switch from inside it) still locks.
    private const val PICKER_CAP_MS = 3 * 60_000L

    // R1: while a bulk import/export is copying (TransferJobs, running in the
    // foreground service) its own vault waits for it: when the deadline comes,
    // every other vault locks on time and only the batch's vault [keep] stays
    // open, until [keepUntil]. That is "until the batch ends" while it copies
    // (Long.MAX_VALUE), then the delay restarted from the moment it ended. A
    // batch waiting on a question (collision, oversize) doesn't count as
    // copying, so an abandoned dialog can't keep a vault open.
    private var batchActive = false
    private var keep: VaultSession? = null
    private var keepUntil = 0L

    // A Lock the user asked for while a batch copies into or out of that vault:
    // the batch is asked to stop at its next chunk boundary (temp file removed,
    // vault flushed, summary says stopped) and the lock waits for it, but never
    // longer than HOLD_CAP_MS: a stuck SAF read must not keep the key in memory.
    private const val HOLD_CAP_MS = 5_000L
    private var holding = false
    // What the held lock closes once the batch stops: everything, or one volume.
    private var holdAll = false
    private var holdVolume: String? = null
    private val holdExpired = Runnable { endHold() }

    // Outer volumes whose hidden-volume protection tripped. The core latches
    // such a session read-only without a flag the shell can read, so the app
    // remembers it from the error (plan §4.1). Identity, not equality.
    private val latched: MutableSet<VaultSession> = Collections.newSetFromMap(IdentityHashMap())

    fun init(context: Context) {
        appContext = context.applicationContext
        // A process that was killed with a vault open (or mid-copy) can leave
        // its ongoing notifications behind; nothing is unlocked now.
        VaultNotifications.clearStale(context.applicationContext)
    }

    /** Call immediately before launching an in-app file picker. */
    fun expectPicker() {
        ignoreNextBackground = true
    }

    /** App went to background/screen-off: lock now or after the chosen delay
     *  (at least [PICKER_CAP_MS] when an in-app picker caused it). A running
     *  batch keeps only its own vault open until it ends (R1). Main thread. */
    fun onAppBackgrounded() {
        val userDelay = appContext?.let { AppPrefs.autoLockDelayMs(it) } ?: 0L
        val picker = ignoreNextBackground
        val delay = if (picker) {
            ignoreNextBackground = false
            maxOf(userDelay, PICKER_CAP_MS)
        } else {
            userDelay
        }
        synchronized(this) {
            inBackground = true
            pickerTrip = picker
            backgroundDelay = delay
            lockDeadline = SystemClock.elapsedRealtime() + delay
        }
        mainHandler.removeCallbacks(delayedLock)
        if (delay <= 0L) lockIfDue() else mainHandler.postDelayed(delayedLock, delay)
        synchronized(this) { publishStatus() }
    }

    /** App is coming back to the foreground. Runs before the first frame, so
     *  an overdue lock happens before any vault content can show. Main thread. */
    fun onAppForegrounded() {
        mainHandler.removeCallbacks(delayedLock)
        synchronized(this) {
            lockIfDue()
            inBackground = false
            pickerTrip = false
            lockDeadline = 0L
            backgroundDelay = 0L
            if (!batchActive) keep = null
            publishStatus()
        }
        // The in-app summary takes over from a "finished" notification.
        appContext?.let { VaultNotifications.hideTransferDone(it) }
    }

    /** True while the app is in the background (backgrounded or screen off). */
    @Synchronized
    fun isInBackground(): Boolean = inBackground

    /** A batch started or stopped copying (R1). While active, its vault
     *  (TransferJobs.owner) is exempt from the deadline; every other vault
     *  keeps its timer. When it stops in the background, its vault gets the
     *  full delay again from now. Any thread. */
    fun setBatchActive(active: Boolean) {
        var schedule = -1L
        synchronized(this) {
            if (batchActive == active) return
            batchActive = active
            if (active) {
                keep = TransferJobs.owner
                keepUntil = Long.MAX_VALUE
            } else if (inBackground) {
                keepUntil = SystemClock.elapsedRealtime() + backgroundDelay
                schedule = backgroundDelay
            } else {
                keep = null
            }
        }
        if (schedule >= 0L) mainHandler.postDelayed(delayedLock, schedule)
    }

    /** Lock if the background deadline has passed. The vault of a running
     *  batch (or of one that ended less than the delay ago) stays open; the
     *  others lock. */
    @Synchronized
    private fun lockIfDue() {
        if (!inBackground || lockDeadline == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now < lockDeadline) return
        val kept = keep?.takeIf { now < keepUntil && sessions.values.any { s -> s === it } }
        if (kept == null) lockAll() else lockAllExcept(kept)
    }

    /** Lock every vault but [kept] (the one a batch is copying with). */
    @Synchronized
    private fun lockAllExcept(kept: VaultSession) {
        val gone = sessions.entries.filter { it.value !== kept }
        if (gone.isEmpty()) return
        for ((id, s) in gone) {
            sessions.remove(id)
            providerTokens.remove(id)
            s.lock()
            latched.remove(s)
        }
        notifyRootsChanged()
        // The UI goes home unless it shows the vault that is still open.
        _lockGeneration.value = _lockGeneration.value + 1
        publishStatus()
    }

    /**
     * A Lock the user asked for (in-app button, notification action, Quick
     * Settings tile). [volumeId] null locks everything. When a batch is
     * copying into or out of a vault being locked, it stops at its next chunk
     * boundary first (temp file removed, vault flushed), then the lock goes
     * ahead, waiting at most [HOLD_CAP_MS]. Main thread.
     */
    fun requestLock(volumeId: String? = null) {
        synchronized(this) {
            val owner = TransferJobs.owner
            val ownerId = if (owner == null) null else sessions.entries.firstOrNull { it.value === owner }?.key
            val hitsBatch = ownerId != null && TransferJobs.busy && (volumeId == null || volumeId == ownerId)
            if (hitsBatch) {
                if (holding) {
                    // Already stopping that batch; widen the pending lock if asked.
                    if (volumeId == null) holdAll = true
                    return
                }
                holding = true
                holdAll = volumeId == null
                holdVolume = volumeId
                if (TransferJobs.stopForLock { endHold() }) {
                    mainHandler.postDelayed(holdExpired, HOLD_CAP_MS)
                    return
                }
                holding = false
            }
        }
        if (volumeId == null) lockAll() else lock(volumeId)
    }

    /** The batch has cleaned up (called from its worker right after its
     *  flush), or the cap ran out: do the lock the user asked for. */
    private fun endHold() {
        mainHandler.removeCallbacks(holdExpired)
        val all: Boolean
        val volume: String?
        synchronized(this) {
            if (!holding) return
            holding = false
            all = holdAll
            volume = holdVolume
            holdVolume = null
        }
        if (all) lockAll() else if (volume != null) lock(volume)
    }

    @Synchronized
    fun put(volumeId: String, session: VaultSession) {
        sessions.remove(volumeId)?.let {
            it.lock()
            latched.remove(it)
        }
        sessions[volumeId] = session
        providerTokens[volumeId] = ProviderTokens.mint()
        notifyRootsChanged()
        // An unlock that finished while the app sat in the background past its
        // deadline must not survive (the deadline lock found nothing to close).
        lockIfDue()
        publishStatus()
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

    /** How many vaults are unlocked right now (for the tile). */
    @Synchronized
    fun unlockedCount(): Int {
        lockIfDue()
        return sessions.size
    }

    /** True while [session] is registered (unlocked and not auto-locked). */
    @Synchronized
    fun isOpen(session: VaultSession): Boolean {
        lockIfDue()
        return sessions.values.any { it === session }
    }

    /** Every unlocked vault as the DocumentsProvider sees it. */
    @Synchronized
    fun providerRoots(): List<ProviderRoot> {
        lockIfDue()
        return sessions.mapNotNull { (id, s) -> providerTokens[id]?.let { ProviderRoot(it, id, s) } }
    }

    /** The unlocked vault behind a provider [token], or null when the token
     *  belongs to an earlier unlock (or was never issued). */
    @Synchronized
    fun providerRoot(token: String): ProviderRoot? {
        lockIfDue()
        val id = providerTokens.entries.firstOrNull { it.value == token }?.key ?: return null
        val s = sessions[id] ?: return null
        return ProviderRoot(token, id, s)
    }

    /** The volume id [session] is registered under, or null once it locked. */
    @Synchronized
    fun idOf(session: VaultSession): String? =
        sessions.entries.firstOrNull { it.value === session }?.key

    /** Remember a failure on [session]. A write refused to protect the hidden
     *  volume latches the session read-only in the core; from then on the
     *  browser and the Files app offer no writes for it. Any thread. */
    fun noteFailure(session: VaultSession, e: Throwable) {
        if (CoreErrorMap.kindOf(e) != VaultErrorKind.HIDDEN_PROTECTED) return
        val id: String?
        synchronized(this) {
            if (!latched.add(session)) return
            id = sessions.entries.firstOrNull { it.value === session }?.key
        }
        // Root flags drop FLAG_SUPPORTS_CREATE; the browser re-reads.
        notifyRootsChanged()
        if (id != null) VaultChanges.post(VaultChange(id, everything = true))
    }

    /** True once [session]'s hidden-volume protection has tripped. */
    @Synchronized
    fun isReadOnlyLatched(session: VaultSession): Boolean = session in latched

    @Synchronized
    fun lock(volumeId: String) {
        val removed = sessions.remove(volumeId) ?: return
        providerTokens.remove(volumeId)
        if (keep === removed) keep = null
        // A batch on this vault waiting on a question would wait forever.
        TransferJobs.abandonFor(listOf(removed))
        removed.lock()
        latched.remove(removed)
        notifyRootsChanged()
        publishStatus()
    }

    @Synchronized
    fun lockAll() {
        if (sessions.isEmpty()) {
            publishStatus()
            return
        }
        TransferJobs.abandonFor(sessions.values.toList())
        sessions.values.forEach { it.lock() }
        sessions.clear()
        providerTokens.clear()
        keep = null
        latched.clear()
        notifyRootsChanged()
        _lockGeneration.value = _lockGeneration.value + 1
        publishStatus()
    }

    private fun notifyRootsChanged() {
        val ctx = appContext ?: return
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }

    /** A batch has fully ended and its copy notification is gone: a lock
     *  that is due now happens at once (no flash of the unlocked
     *  notification with "Immediately"), otherwise "A vault is unlocked"
     *  comes back if the app is still in the background. Any thread. */
    fun onTransferEnded() {
        synchronized(this) {
            lockIfDue()
            publishStatus()
        }
    }

    /** "Vault unlocked" notification (R2: only in the background, not for an
     *  in-app picker's trip, and not while the copy notification of a running
     *  batch is showing, which has its own Lock) and the Quick Settings tile.
     *  Call holding this. */
    private fun publishStatus() {
        val ctx = appContext ?: return
        val copying = TransferJobs.active.value
        LockControls.onStatus(ctx, sessions.size, showNotification = inBackground && !pickerTrip && !copying)
    }
}

/** One unlocked vault as the DocumentsProvider sees it: [token] is what other
 *  apps get, [volumeId] stays inside the app. */
class ProviderRoot(val token: String, val volumeId: String, val session: VaultSession)

/**
 * Content changes made outside the in-app browser (other apps writing through
 * the DocumentsProvider, or a hidden-volume trip), so an open browser can
 * refresh the folder it shows. A simple last-change signal: the browser
 * compares the volume and folder and reloads.
 */
object VaultChanges {
    private val seq = AtomicLong()
    private val _last = MutableStateFlow<VaultChange?>(null)
    val last: StateFlow<VaultChange?> = _last.asStateFlow()

    fun post(change: VaultChange) {
        _last.value = change.copy(seq = seq.incrementAndGet())
    }
}
