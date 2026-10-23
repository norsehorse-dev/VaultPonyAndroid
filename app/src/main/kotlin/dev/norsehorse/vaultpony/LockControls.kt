package dev.norsehorse.vaultpony

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.norsehorse.vaultpony.i18n.LanguageState
import dev.norsehorse.vaultpony.i18n.LocaleManager
import dev.norsehorse.vaultpony.ui.transferSummaryLines

/*
 * Lock controls (2.0.0 plan §4.3, R2): the "Vault unlocked" notification with
 * a Lock action, the copy progress notification of TransferService, and the
 * "Lock vaults" Quick Settings tile. Every notification is generic (no vault
 * or file names, nothing about hidden volumes), silent, low importance and
 * hidden on the lock screen. If POST_NOTIFICATIONS is denied, nothing here is
 * shown except what Android shows for a foreground service, and every lock
 * path keeps working.
 */

/** Glue between SessionRegistry and the notification and tile. */
object LockControls {
    /** [unlocked] vaults are open; [showNotification]: the app is in the
     *  background on its own (not an in-app picker). */
    fun onStatus(context: Context, unlocked: Int, showNotification: Boolean) {
        if (showNotification && unlocked > 0) {
            VaultNotifications.showUnlocked(context, unlocked)
        } else {
            VaultNotifications.hideUnlocked(context)
        }
        refreshTile(context)
    }

    /** Ask the tile to redraw (it reads the registry when it listens). */
    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, LockTileService::class.java))
        }
    }
}

object VaultNotifications {
    private const val CHANNEL_UNLOCKED = "unlocked_vaults"
    private const val CHANNEL_TRANSFER = "copying_files"

    const val ID_UNLOCKED = 1001
    const val ID_TRANSFER = 1002
    const val ID_TRANSFER_DONE = 1003

    const val ACTION_LOCK = "dev.norsehorse.vaultpony.action.LOCK_ALL"
    const val ACTION_CANCEL_TRANSFER = "dev.norsehorse.vaultpony.action.CANCEL_TRANSFER"
    const val ACTION_UNLOCKED_DISMISSED = "dev.norsehorse.vaultpony.action.UNLOCKED_DISMISSED"

    private fun manager(c: Context): NotificationManager? = c.getSystemService(NotificationManager::class.java)

    /** API 33+ needs the runtime grant; below that notifications are allowed. */
    fun canPost(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** The "A vault is unlocked" notification can actually be seen: the
     *  app may post (the runtime grant on 13+), its notifications aren't
     *  blocked, and the "Unlocked vaults" channel isn't turned off. Auto-lock
     *  "Until I lock it" depends on it (D15). A channel not created yet
     *  counts as on: it is created enabled the first time it is used. */
    fun unlockedAllowed(c: Context): Boolean {
        val app = c.applicationContext
        if (!canPost(app)) return false
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        val channel = runCatching { manager(app)?.getNotificationChannel(CHANNEL_UNLOCKED) }.getOrNull()
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // In-app language the channels were last named in; null = not created
    // in this process yet.
    @Volatile
    private var channelsLanguage: String? = null

    /** Create both channels on first use, and rename them when the in-app
     *  language changed (re-creating an existing channel only updates its
     *  name and description). Low importance: no sound, no peeking; hidden
     *  when locked. */
    private fun ensureChannels(app: Context, text: Context) {
        val language = LanguageState.current.value
        if (channelsLanguage == language) return
        val m = manager(app) ?: return
        fun channel(id: String, name: Int, desc: Int) =
            NotificationChannel(id, text.getString(name), NotificationManager.IMPORTANCE_LOW).apply {
                description = text.getString(desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        runCatching {
            m.createNotificationChannels(
                listOf(
                    channel(CHANNEL_UNLOCKED, R.string.notif_channel_unlocked, R.string.notif_channel_unlocked_desc),
                    channel(CHANNEL_TRANSFER, R.string.notif_channel_transfer, R.string.notif_channel_transfer_desc),
                ),
            )
            channelsLanguage = language
        }
    }

    private fun openAppIntent(app: Context): PendingIntent {
        // Same as the launcher: brings the existing task to the front.
        val intent = Intent.makeMainActivity(ComponentName(app, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcast(app: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            requestCode,
            Intent(app, LockReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun lockIntent(app: Context) = broadcast(app, ACTION_LOCK, 1)

    private fun cancelIntent(app: Context) = broadcast(app, ACTION_CANCEL_TRANSFER, 2)

    // Android 14 lets the user swipe away even an ongoing notification that
    // doesn't belong to a foreground service. Under "Until I lock it" the
    // unlocked notification must stay, so a swipe comes back here (D15).
    private fun dismissedIntent(app: Context) = broadcast(app, ACTION_UNLOCKED_DISMISSED, 3)

    // -- "A vault is unlocked" (R2) -----------------------------------------

    fun showUnlocked(context: Context, count: Int) {
        val app = context.applicationContext
        if (!canPost(app)) return
        val text = LocaleManager.localizedContext(app)
        ensureChannels(app, text)
        val title = if (count == 1) R.string.notif_unlocked_title_one else R.string.notif_unlocked_title_many
        val n = NotificationCompat.Builder(app, CHANNEL_UNLOCKED)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(text.getString(title))
            .setContentText(text.getString(R.string.notif_unlocked_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(app))
            .setDeleteIntent(dismissedIntent(app))
            .addAction(R.drawable.ic_stat_lock, text.getString(R.string.notif_action_lock), lockIntent(app))
            .build()
        runCatching { manager(app)?.notify(ID_UNLOCKED, n) }
    }

    fun hideUnlocked(context: Context) {
        runCatching { manager(context.applicationContext)?.cancel(ID_UNLOCKED) }
    }

    // -- Copy progress (TransferService) -----------------------------------

    /** The foreground notification for a batch in [state]. Counts only, never
     *  file names. Built even without POST_NOTIFICATIONS: Android still lists
     *  the foreground service in its task manager. */
    fun transferProgress(context: Context, state: TransferJobs.State, export: Boolean): Notification {
        val app = context.applicationContext
        val text = LocaleManager.localizedContext(app)
        ensureChannels(app, text)
        val b = NotificationCompat.Builder(app, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(text.getString(if (export) R.string.notif_export_title else R.string.notif_import_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(app))
            .addAction(0, text.getString(R.string.common_cancel), cancelIntent(app))
            .addAction(R.drawable.ic_stat_lock, text.getString(R.string.notif_action_lock), lockIntent(app))
        if (state is TransferJobs.State.Running) {
            b.setContentText(text.getString(R.string.transfer_progress, state.index, state.total))
            if (state.bytesTotal > 0) {
                val permille = (state.bytesDone.coerceIn(0L, state.bytesTotal) * 1000 / state.bytesTotal).toInt()
                b.setProgress(1000, permille, false)
            } else {
                b.setProgress(state.total.coerceAtLeast(1), (state.index - 1).coerceAtLeast(0), false)
            }
        } else {
            b.setContentText(text.getString(R.string.transfer_preparing))
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    fun updateTransfer(context: Context, notification: Notification) {
        runCatching { manager(context.applicationContext)?.notify(ID_TRANSFER, notification) }
    }

    /** "Import finished" / "Export finished" with the in-app summary lines.
     *  Posted only when the batch ended with the app in the background. */
    fun showTransferDone(context: Context, result: TransferResult) {
        val app = context.applicationContext
        if (!canPost(app)) return
        val text = LocaleManager.localizedContext(app)
        ensureChannels(app, text)
        val fallback = text.getString(if (result.export) R.string.browser_err_export else R.string.browser_err_import)
        val lines = transferSummaryLines(text.resources, result, fallback, forNotification = true)
        val n = NotificationCompat.Builder(app, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(text.getString(if (result.export) R.string.notif_export_done else R.string.notif_import_done))
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(app))
            .build()
        runCatching { manager(app)?.notify(ID_TRANSFER_DONE, n) }
    }

    /** At process start: an "unlocked" or copy notification left by a
     *  process that was killed is stale (nothing is unlocked now). */
    fun clearStale(context: Context) {
        val m = manager(context.applicationContext) ?: return
        runCatching { m.cancel(ID_UNLOCKED) }
        runCatching { m.cancel(ID_TRANSFER) }
    }

    fun hideTransferDone(context: Context) {
        runCatching { manager(context.applicationContext)?.cancel(ID_TRANSFER_DONE) }
    }
}

/** Lock and Cancel actions from the notifications. Not exported: only this
 *  app's own PendingIntents reach it. */
class LockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            VaultNotifications.ACTION_LOCK -> SessionRegistry.requestLock(null)
            VaultNotifications.ACTION_CANCEL_TRANSFER -> TransferJobs.cancel()
            VaultNotifications.ACTION_UNLOCKED_DISMISSED -> SessionRegistry.onUnlockedDismissed()
        }
    }
}

/** Quick Settings "Lock vaults": active while anything is unlocked, a tap
 *  locks everything (a running copy stops first, as with any Lock). Locking
 *  needs no device unlock, so it works from the lock screen too. */
class LockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        SessionRegistry.requestLock(null)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val text = LocaleManager.localizedContext(this)
        val unlocked = SessionRegistry.unlockedCount()
        tile.label = text.getString(R.string.tile_lock_label)
        tile.state = if (unlocked > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (unlocked > 0) null else text.getString(R.string.tile_nothing_unlocked)
        }
        tile.updateTile()
    }
}
