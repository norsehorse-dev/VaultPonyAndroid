package dev.norsehorse.vaultpony.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.ShareRequest
import dev.norsehorse.vaultpony.TransferJobs
import uniffi.vault_ffi.VaultSession

/**
 * Share into a vault (plan §5.1): pick the folder in the unlocked [session]
 * ("Choose a folder", "Save here"), then the files go in through the bulk
 * import pipeline (foreground service, progress notification, name
 * collisions keep both). [started]: the import has begun; its progress and
 * summary show here, and VaultPonyRoot finishes the share when it is over.
 */
@Composable
internal fun ShareSaveScreen(
    session: VaultSession,
    share: ShareRequest,
    started: Boolean,
    onStarted: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val notifGate = rememberNotificationGate()
    val transferAll by TransferJobs.state.collectAsState()
    val copying by TransferJobs.active.collectAsState()
    val transfer = if (TransferJobs.owner === session) transferAll else TransferJobs.State.Idle
    val errImport = stringResource(R.string.browser_err_import)
    val writable = remember(session) { runCatching { session.facts().writable }.getOrDefault(false) } &&
        !SessionRegistry.isReadOnlyLatched(session)

    val count = pluralStringResource(R.plurals.share_files_count, share.count, share.count)
    FolderPickerScreen(
        session = session,
        title = stringResource(R.string.share_choose_folder),
        confirmLabel = stringResource(R.string.share_save_here),
        header = if (writable) count else count + "\n" + stringResource(R.string.err_read_only),
        startPath = "/",
        // One copy at a time: wait for a batch already running elsewhere.
        enabled = writable && !started && !copying,
        onPick = { dir ->
            notifGate.runAfter {
                // Only a batch that really started ends the share; if another
                // copy got there first, the picker stays for another try.
                if (TransferJobs.importShared(context, session, dir, share.uris, share.text)) onStarted()
            }
        },
        onCancel = onCancel,
    )
    if (started) TransferDialogs(transfer, fallbackError = errImport)
}

/** True when files can be written into [session]: not NTFS, ext4 or
 *  TrueCrypt (facts.writable), and not latched read-only by hidden-volume
 *  protection. Share only offers such vaults (2.0.0 rc5). */
internal fun sessionWritable(session: VaultSession): Boolean =
    runCatching { session.facts().writable }.getOrDefault(false) && !SessionRegistry.isReadOnlyLatched(session)
