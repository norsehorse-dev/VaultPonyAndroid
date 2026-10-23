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

    // Container URI per unlocked volume id, so a lock under the vault list's
    // "Don't keep" can release that container's file permission (plan §5.4).
    private val uris = HashMap<String, String>()

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

    // Auto-lock "Until I lock it" (D15, plan §11.1): this trip to the
    // background has no deadline at all. It holds only while the "A vault is
    // unlocked" notification can be seen; if notifications are turned off
    // meanwhile, the trip falls back to 1 hour from [backgroundAt]. Checked
    // on a timer and, throttled, from every entry point (the timer stalls in
    // the freezer like the lock timer). Guarded by this.
    private var untilLocked = false
    private var backgroundAt = 0L
    private var untilCheckedAt = 0L
    private const val UNTIL_RECHECK_MS = 30_000L
    private val untilCheck: Runnable = object : Runnable {
        override fun run() {
            checkUntilLocked(force = true)
            val again = synchronized(this@SessionRegistry) { untilLocked && inBackground }
            if (again) mainHandler.postDelayed(this, UNTIL_RECHECK_MS)
        }
    }

    // Bumped every time the app comes back to the foreground, so a screen
    // can re-read something the user may have changed in system settings
    // (Settings re-checks whether "Until I lock it" can be offered).
    private val _foregrounds = MutableStateFlow(0)
    val foregrounds = _foregrounds.asStateFlow()

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
    // Convert to VeraCrypt (2.0.0 rc5, plan 6.2) copies between two vaults:
    // the new container is kept open with [keep] (TransferJobs.partner).
    private var keepPartner: VaultSession? = null
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
        val stored = appContext?.let { AppPrefs.autoLockDelayMs(it) } ?: 0L
        // "Until I lock it": no deadline, whatever caused the trip.
        val until = stored == AppPrefs.AUTO_LOCK_UNTIL_LOCKED
        val userDelay = if (until) 0L else stored
        val picker = ignoreNextBackground
        val delay = if (picker) {
            ignoreNextBackground = false
            maxOf(userDelay, PICKER_CAP_MS)
        } else {
            userDelay
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            inBackground = true
            pickerTrip = picker
            untilLocked = until
            backgroundAt = now
            untilCheckedAt = now
            backgroundDelay = if (until) 0L else delay
            // 0 = no deadline (lockIfDue never fires).
            lockDeadline = if (until) 0L else now + delay
        }
        mainHandler.removeCallbacks(delayedLock)
        mainHandler.removeCallbacks(untilCheck)
        when {
            until -> mainHandler.postDelayed(untilCheck, UNTIL_RECHECK_MS)
            delay <= 0L -> lockIfDue()
            else -> mainHandler.postDelayed(delayedLock, delay)
        }
        synchronized(this) { publishStatus() }
    }

    /** "Until I lock it" holds only while its notification can be seen. When
     *  notifications were turned off during this trip to the background, fall
     *  back to "After 1 hour", counted from when the app left. [force]: skip
     *  the throttle (the timer); entry points check at most every 30 s. */
    @Synchronized
    private fun checkUntilLocked(force: Boolean) {
        if (!untilLocked || !inBackground) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - untilCheckedAt < UNTIL_RECHECK_MS) return
        untilCheckedAt = now
        val ctx = appContext ?: return
        if (VaultNotifications.unlockedAllowed(ctx)) return
        untilLocked = false
        backgroundDelay = AppPrefs.AUTO_LOCK_UNTIL_FALLBACK
        lockDeadline = backgroundAt + AppPrefs.AUTO_LOCK_UNTIL_FALLBACK
        mainHandler.removeCallbacks(delayedLock)
        mainHandler.postDelayed(delayedLock, (lockDeadline - now).coerceAtLeast(0L))
        publishStatus()
    }

    /** App is coming back to the foreground. Runs before the first frame, so
     *  an overdue lock happens before any vault content can show. Main thread. */
    fun onAppForegrounded() {
        mainHandler.removeCallbacks(delayedLock)
        mainHandler.removeCallbacks(untilCheck)
        synchronized(this) {
            lockIfDue()
            inBackground = false
            pickerTrip = false
            untilLocked = false
            lockDeadline = 0L
            backgroundDelay = 0L
            if (!batchActive) {
                keep = null
                keepPartner = null
            }
            publishStatus()
        }
        _foregrounds.value = _foregrounds.value + 1
        // The in-app summary takes over from a "finished" notification.
        appContext?.let { VaultNotifications.hideTransferDone(it) }
    }

    /** The user swiped the "A vault is unlocked" notification away (Android
     *  14+ allows it for a notification without a foreground service). Under
     *  "Until I lock it" a vault would then stay open with nothing on screen
     *  to say so, so the notification is posted again (D15, plan §11.1:
     *  it always shows while that option applies). Otherwise the swipe
     *  stands until the next status change. Any thread. */
    fun onUnlockedDismissed() {
        synchronized(this) {
            if (untilLocked && inBackground && sessions.isNotEmpty()) publishStatus()
        }
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
                keepPartner = TransferJobs.partner
                keepUntil = Long.MAX_VALUE
            } else if (inBackground && untilLocked) {
                // No deadline to wait for; a later fallback to 1 hour counts
                // from when the app left, like every other vault.
                keep = null
                keepPartner = null
            } else if (inBackground) {
                keepUntil = SystemClock.elapsedRealtime() + backgroundDelay
                schedule = backgroundDelay
            } else {
                keep = null
                keepPartner = null
            }
        }
        if (schedule >= 0L) mainHandler.postDelayed(delayedLock, schedule)
    }

    /** Lock if the background deadline has passed. The vault of a running
     *  batch (or of one that ended less than the delay ago) stays open; the
     *  others lock. */
    @Synchronized
    private fun lockIfDue() {
        checkUntilLocked(force = false)
        if (!inBackground || lockDeadline == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now < lockDeadline) return
        val kept = if (now < keepUntil) {
            listOfNotNull(keep, keepPartner).filter { k -> sessions.values.any { s -> s === k } }
        } else {
            emptyList()
        }
        if (kept.isEmpty()) lockAll() else lockAllExcept(kept)
    }

    /** Lock every vault but [kept] (the one a batch is copying with, and
     *  for a convert the one it copies into). */
    @Synchronized
    private fun lockAllExcept(kept: List<VaultSession>) {
        val gone = sessions.entries.filter { e -> kept.none { it === e.value } }.map { it.key to it.value }
        if (gone.isEmpty()) return
        CameraCapture.discardFor(gone.map { it.second })
        for ((id, s) in gone) {
            sessions.remove(id)
            providerTokens.remove(id)
            s.lock()
            latched.remove(s)
        }
        afterLocked(gone)
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
            // The batch's vault, and for a convert the vault it copies into.
            val batchIds = listOfNotNull(TransferJobs.owner, TransferJobs.partner)
                .mapNotNull { b -> sessions.entries.firstOrNull { it.value === b }?.key }
            val hitsBatch = batchIds.isNotEmpty() && TransferJobs.busy &&
                (volumeId == null || volumeId in batchIds)
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

    /** Register an unlocked [session]. [uri] is its container, kept so a lock
     *  under "Don't keep" can release the container's file permission. */
    @Synchronized
    fun put(volumeId: String, session: VaultSession, uri: String? = null) {
        sessions.remove(volumeId)?.let {
            CameraCapture.discardFor(listOf(it))
            it.lock()
            latched.remove(it)
            ThumbnailCache.clear()
            mainHandler.post { MediaPlayback.releaseFor(listOf(it)) }
        }
        sessions[volumeId] = session
        if (uri != null) uris[volumeId] = uri
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

    /** The container URI of the unlocked vault [volumeId], or null. */
    @Synchronized
    fun uriOf(volumeId: String): String? = if (volumeId in sessions) uris[volumeId] else null

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
        if (keepPartner === removed) keepPartner = null
        // A camera capture going into it loses its temp file first (plan 5.6).
        CameraCapture.discardFor(listOf(removed))
        // A batch on this vault waiting on a question would wait forever.
        TransferJobs.abandonFor(listOf(removed))
        removed.lock()
        latched.remove(removed)
        afterLocked(listOf(volumeId to removed))
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
        val gone = sessions.entries.map { it.key to it.value }
        CameraCapture.discardFor(gone.map { it.second })
        sessions.values.forEach { it.lock() }
        sessions.clear()
        providerTokens.clear()
        keep = null
        keepPartner = null
        latched.clear()
        afterLocked(gone)
        notifyRootsChanged()
        _lockGeneration.value = _lockGeneration.value + 1
        publishStatus()
    }

    /** Everything a lock takes with it besides the key: in-memory
     *  thumbnails, any media player on those vaults, and under the vault
     *  list's "Don't keep", each vault's file permission and biometric
     *  unlock (plan §5.3, §5.2, §5.4). Call holding this. */
    private fun afterLocked(gone: List<Pair<String, VaultSession>>) {
        ThumbnailCache.clear()
        val players = gone.map { it.second }
        mainHandler.post { MediaPlayback.releaseFor(players) }
        for ((id, _) in gone) uris.remove(id)
        val ctx = appContext ?: return
        if (AppPrefs.vaultListMode(ctx) == VaultListMode.NONE) {
            for ((id, _) in gone) BiometricUnlock.clear(ctx, id)
            VaultStore.releaseGrants(ctx, uris.values.toSet())
        }
    }

    /** Container URIs of the vaults unlocked right now. */
    @Synchronized
    fun openUris(): Set<String> = uris.filterKeys { it in sessions }.values.toSet()

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
        // Under "Until I lock it" an in-app picker's trip shows it too: the
        // vault stays open with no deadline, so the Lock button must be there.
        val show = inBackground && (untilLocked || !pickerTrip) && !copying
        LockControls.onStatus(ctx, sessions.size, showNotification = show)
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
