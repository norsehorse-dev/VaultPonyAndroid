package dev.norsehorse.vaultpony

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps a bulk import or export
 * running while VaultPony is in the background (2.0.0 plan §4.3, R1). It owns
 * nothing: the copy itself runs in [TransferJobs]; this only holds the
 * process in the foreground, shows progress with Cancel and Lock, and leaves
 * as soon as the batch has settled. If the batch ends with the app in the
 * background, a "finished" notification carries the summary.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground()
        // within a few seconds, so do it first, every time.
        val shown = try {
            ServiceCompat.startForeground(
                this,
                VaultNotifications.ID_TRANSFER,
                VaultNotifications.transferProgress(this, TransferJobs.state.value, TransferJobs.exporting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not enter the foreground", e)
            false
        }
        if (!shown) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
        // A killed process takes the batch with it; never restart empty.
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        var lastKind: Class<*>? = null
        var lastAt = 0L
        var ended = false
        combine(TransferJobs.state, TransferJobs.active) { s, a -> s to a }.collect { (s, a) ->
            if (!a) {
                if (!ended) {
                    ended = true
                    finish(s)
                }
                return@collect
            }
            ended = false
            // Throttled: Android drops notification updates past a few per
            // second, and a batch of small files changes state constantly.
            val now = SystemClock.elapsedRealtime()
            if (s.javaClass != lastKind || now - lastAt >= UPDATE_EVERY_MS) {
                lastKind = s.javaClass
                lastAt = now
                VaultNotifications.updateTransfer(
                    this,
                    VaultNotifications.transferProgress(this, s, TransferJobs.exporting),
                )
            }
        }
    }

    private fun finish(end: TransferJobs.State) {
        if (end is TransferJobs.State.Done && SessionRegistry.isInBackground()) {
            VaultNotifications.showTransferDone(this, end.result)
        }
        if (end is TransferJobs.State.Converted && SessionRegistry.isInBackground()) {
            VaultNotifications.showConvertDone(this, end.result)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Keeps running if another batch already asked for the service.
        stopSelfResult(lastStartId)
    }

    /** Android 15 caps dataSync services (6 hours a day). Stop the copy at its
     *  next chunk boundary (partial file removed, vault flushed) and leave the
     *  foreground at once, as the system requires. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferJobs.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VaultPony"
        private const val UPDATE_EVERY_MS = 1_000L

        /** Called when a batch starts, which is always from the app in the
         *  foreground (a picker result or a tap). If Android refuses anyway,
         *  the batch still runs; it just has no foreground protection.
         *  [grants]: documents shared into a vault (plan §5.1). They ride on
         *  the start intent with FLAG_GRANT_READ_URI_PERMISSION, so the
         *  service holds their read grant until it stops, not only the
         *  activity that received the share. */
        fun start(context: Context, grants: List<Uri> = emptyList()) {
            val app = context.applicationContext
            val intent = Intent(app, TransferService::class.java)
            if (grants.isNotEmpty()) {
                val clip = ClipData.newRawUri("", grants.first())
                grants.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Log.w(TAG, "transfer service not started", e)
            }
        }
    }
}
