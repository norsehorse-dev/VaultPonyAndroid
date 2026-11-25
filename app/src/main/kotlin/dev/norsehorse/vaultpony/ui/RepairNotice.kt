package dev.norsehorse.vaultpony.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.norsehorse.vaultpony.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.RepairNotice
import uniffi.vault_ffi.VaultSession
import java.util.Collections
import java.util.WeakHashMap

/*
 * Releases up to 2.0.0 could leave junk entries in the top folder of a FAT32
 * vault (2.1.0 plan 2, finding 1). The core removes them when the vault is
 * unlocked and says nothing about it. What it does report are files whose
 * data may be gone because one of those junk entries was deleted before the
 * repair. That list is shown once, the first time the vault's browser is on
 * screen.
 */

/** The core hands its report over once per unlock; this keeps it until the
 *  user has seen it, whatever recomposes in between. */
internal object RepairNotices {
    private val asked: MutableSet<VaultSession> =
        Collections.newSetFromMap(WeakHashMap<VaultSession, Boolean>())
    private val pending = WeakHashMap<VaultSession, RepairNotice>()
    private val gate = Mutex()

    /** The notice already fetched for [session] and not yet dismissed. */
    fun peek(session: VaultSession): RepairNotice? = synchronized(this) { pending[session] }

    /** The notice still to show for [session], or null. Taking the report
     *  from the core and keeping it here is one step that a cancelled caller
     *  (a rotation, leaving the browser) cannot split, or the report would
     *  be taken and then dropped. */
    suspend fun pendingFor(session: VaultSession): RepairNotice? =
        withContext(NonCancellable + Dispatchers.IO) {
            gate.withLock {
                if (synchronized(this@RepairNotices) { asked.add(session) }) {
                    val report = runCatching { session.takeRepairReport() }.getOrNull()
                    if (report != null && report.damagedTotal > 0uL) {
                        synchronized(this@RepairNotices) { pending[session] = report }
                    }
                }
                peek(session)
            }
        }

    fun dismiss(session: VaultSession) {
        synchronized(this) { pending.remove(session) }
    }
}

@Composable
internal fun RepairNoticeDialog(notice: RepairNotice, onDismiss: () -> Unit) {
    val res = LocalContext.current.resources
    val more = (notice.damagedTotal.toLong() - notice.damaged.size).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.repair_damaged_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.repair_damaged_body))
                // Selectable, so the names can be copied out: the list is
                // shown this once.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        notice.damaged.forEach { path ->
                            Text(
                                path.removePrefix("/"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                if (more > 0) Text(res.getQuantityString(R.plurals.repair_damaged_more, more, more))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        },
    )
}
