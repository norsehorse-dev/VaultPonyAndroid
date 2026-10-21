package dev.norsehorse.vaultpony.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.R

/**
 * Asks for POST_NOTIFICATIONS (Android 13+) at most once, with a short
 * explanation first (plan §4.3, R2): the first time a delay longer than
 * Immediately is picked, or the first time an import or export starts.
 * [runAfter] runs its action straight away when there is nothing to ask, or
 * after the user answers. Whatever the answer, everything keeps working; only
 * the notifications are missing.
 */
class NotificationGate internal constructor(private val gate: (() -> Unit) -> Unit) {
    fun runAfter(action: () -> Unit) = gate(action)
}

private fun needsAsk(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED &&
        !AppPrefs.notificationRationaleShown(context)

@Composable
fun rememberNotificationGate(): NotificationGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explaining by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val next = pending
        pending = null
        next?.invoke()
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = {
                explaining = false
                val next = pending
                pending = null
                next?.invoke()
            },
            text = { Text(stringResource(R.string.notif_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val next = pending
                        pending = null
                        next?.invoke()
                    }
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explaining = false
                    val next = pending
                    pending = null
                    next?.invoke()
                }) { Text(stringResource(R.string.notif_permission_not_now)) }
            },
        )
    }

    return remember(context) {
        NotificationGate { action ->
            if (!needsAsk(context)) {
                action()
            } else {
                // Once only, whatever the answer.
                AppPrefs.setNotificationRationaleShown(context, true)
                pending = action
                explaining = true
            }
        }
    }
}
