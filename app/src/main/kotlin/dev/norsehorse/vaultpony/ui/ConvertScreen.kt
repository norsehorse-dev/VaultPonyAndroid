package dev.norsehorse.vaultpony.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.norsehorse.vaultpony.ConvertResult
import dev.norsehorse.vaultpony.CoreErrorMap
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.TransferJobs
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.components.VaultSeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.KdfFilter
import uniffi.vault_ffi.TreeSummary
import uniffi.vault_ffi.VaultSession
import uniffi.vault_ffi.kdfFilterForPrf

/*
 * Convert to VeraCrypt (2.0.0 plan 6.2, D9). A TrueCrypt volume opens
 * read-only; this makes a new VeraCrypt container with the create form and
 * code, unlocks it, and copies every file across in TransferJobs (the
 * foreground service, like a bulk import). The core checks every file's size
 * and the counts at the end. The TrueCrypt container is only read, never
 * changed or deleted.
 */

private const val ONE_MIB: ULong = 1_048_576uL

// The new container's headroom over the files' bytes: 5% plus 8 MiB, and
// never less than the bytes plus 8 MiB.
private const val HEADROOM: ULong = 8_388_608uL

/** Round [bytes] up to a whole MiB. */
private fun roundUpMib(bytes: ULong): ULong = (bytes + ONE_MIB - 1uL) / ONE_MIB * ONE_MIB

/** FAT's per-file limit; a larger file needs exFAT. */
private const val FAT_MAX_FILE: ULong = 4_294_967_295uL

/** The new container could not be set up for the copy (unlocking it failed,
 *  or another copy is running). [volumeId] is set once it was unlocked. */
private class SetupFailure(val uri: Uri, val volumeId: String?, val message: String)

/**
 * The convert flow for the unlocked TrueCrypt [session] ([volumeId]):
 * counting, the create form, progress with Stop, then the result. [onOpenNew]
 * gets the new vault ("Open new vault"); [onDone] goes back to the TrueCrypt
 * vault's browser.
 */
@Composable
internal fun ConvertScreen(
    session: VaultSession,
    volumeId: String,
    onOpenNew: (VaultSession, String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()
    val notifGate = rememberNotificationGate()
    val unlockFailed = stringResource(R.string.unlock_failed)
    val genericError = stringResource(R.string.err_vault_generic)

    val transferAll by TransferJobs.state.collectAsState()
    // A convert from this vault (running or ended) survives a rotation in
    // TransferJobs; this screen picks it up again.
    val mine = TransferJobs.owner === session && TransferJobs.converting
    val transfer = if (mine) transferAll else TransferJobs.State.Idle

    var summary by remember { mutableStateOf<TreeSummary?>(null) }
    var containerBytes by remember { mutableStateOf<Long?>(null) }
    var countError by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var setupFailure by remember { mutableStateOf<SetupFailure?>(null) }

    LaunchedEffect(session) {
        if (mine && transfer != TransferJobs.State.Idle) return@LaunchedEffect
        try {
            summary = withContext(Dispatchers.IO) { session.summarizeTree() }
            hidden = withContext(Dispatchers.IO) { runCatching { session.facts().hidden }.getOrDefault(false) }
            containerBytes = SessionRegistry.uriOf(volumeId)?.let { repo.containerSize(Uri.parse(it)) }
        } catch (e: Exception) {
            countError = vaultFailureText(context.resources, e, genericError, where = "convert count")
        }
    }

    /** The new container exists at [uri]: unlock it with what the form
     *  set (its hash as the KDF filter, so no search) and start the copy. */
    fun startCopy(uri: Uri, password: String, hash: String) {
        adoptCreatedContainer(context, uri)
        starting = true
        scope.launch {
            val kdf = runCatching { kdfFilterForPrf(hash) }.getOrNull() ?: KdfFilter.AUTO
            val newSession = try {
                repo.unlock(uri = uri, passphrase = password, pim = 0u, kdf = kdf, onProgress = {})
            } catch (e: Exception) {
                CoreErrorMap.log("convert unlock", e)
                setupFailure = SetupFailure(uri, null, vaultErrorText(context, e, unlockFailed) ?: unlockFailed)
                starting = false
                return@launch
            }
            val newId = VaultRepository.volumeId(uri)
            notifGate.runAfter {
                val started = SessionRegistry.isOpen(session) && SessionRegistry.isOpen(newSession) &&
                    TransferJobs.convert(context, session, newSession, uri, newId)
                if (!started) setupFailure = SetupFailure(uri, newId, genericError)
                starting = false
            }
        }
    }

    // Leaving is only possible before the copy starts; after it, Stop and
    // the result's buttons are the ways out.
    val running = transfer is TransferJobs.State.Preparing || transfer is TransferJobs.State.Running
    val ended = transfer as? TransferJobs.State.Converted
    BackHandler(enabled = true) {
        if (!running && ended == null && setupFailure == null && !starting) onDone()
    }

    val failure = setupFailure
    when {
        ended != null -> ConvertResultView(
            lines = convertResultLines(context.resources, ended.result),
            ok = ended.result.ok,
            onOpenNew = {
                TransferJobs.dismiss()
                onOpenNew(ended.result.newSession, ended.result.newVolumeId)
            },
            onDone = {
                TransferJobs.dismiss()
                // The new vault is on the vault list; it opens from there.
                SessionRegistry.requestLock(ended.result.newVolumeId)
                onDone()
            },
            onDelete = {
                TransferJobs.dismiss()
                deleteNewContainer(context, ended.result.newVolumeId, ended.result.newUri)
                onDone()
            },
            onKeep = {
                TransferJobs.dismiss()
                SessionRegistry.requestLock(ended.result.newVolumeId)
                onDone()
            },
        )

        failure != null -> ConvertResultView(
            lines = listOf(stringResource(R.string.convert_stopped), failure.message),
            ok = false,
            onOpenNew = {},
            onDone = {},
            onDelete = {
                deleteNewContainer(context, failure.volumeId, failure.uri)
                onDone()
            },
            onKeep = {
                failure.volumeId?.let { SessionRegistry.requestLock(it) }
                onDone()
            },
        )

        running -> ConvertProgressView(transfer)

        starting -> ConvertBusyView(stringResource(R.string.create_working))

        countError != null -> ConvertResultView(
            lines = listOf(countError.orEmpty()),
            ok = true,
            onOpenNew = null,
            onDone = onDone,
            onDelete = {},
            onKeep = {},
        )

        summary == null -> ConvertBusyView(stringResource(R.string.convert_counting))

        else -> {
            val s = summary!!
            val setup = remember(s, containerBytes, hidden) {
                val needed = s.bytes + s.bytes / 20uL + HEADROOM
                val file = (containerBytes ?: 0L).coerceAtLeast(0L).toULong()
                ConvertSetup(
                    suggestedBytes = roundUpMib(maxOf(file, needed)),
                    minBytes = s.bytes + HEADROOM,
                    needsExfat = s.largestFile > FAT_MAX_FILE,
                    hiddenSource = hidden,
                )
            }
            CreateContainerScreen(
                onCreated = {},
                onCancel = onDone,
                convert = setup,
                onConvertCreated = { uri, password, hash -> startCopy(uri, password, hash) },
            )
        }
    }
}

@Composable
private fun ConvertBusyView(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.convert_title), style = MaterialTheme.typography.headlineMedium)
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConvertProgressView(state: TransferJobs.State) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.convert_title), style = MaterialTheme.typography.headlineMedium)
        if (state is TransferJobs.State.Running) {
            Text(pluralStringResource(R.plurals.convert_progress, state.total, state.index, state.total))
            // By bytes, so one big file still moves the bar.
            val fraction = if (state.bytesTotal > 0) {
                state.bytesDone.toFloat() / state.bytesTotal
            } else {
                state.index.toFloat() / state.total.coerceAtLeast(1)
            }
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.convert_counting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { TransferJobs.cancel() }) { Text(stringResource(R.string.common_stop)) }
    }
}

/** The end of a convert: [ok] offers "Open new vault" (when [onOpenNew] is
 *  set) and "Done"; otherwise "Delete new container" and "Keep it". */
@Composable
private fun ConvertResultView(
    lines: List<String>,
    ok: Boolean,
    onOpenNew: (() -> Unit)?,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        VaultSeal()
        Text(stringResource(R.string.convert_title), style = MaterialTheme.typography.headlineMedium)
        lines.forEachIndexed { i, line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (!ok && i > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        if (ok) {
            if (onOpenNew != null) {
                Button(onClick = onOpenNew, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.convert_open_new))
                }
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_done))
            }
        } else {
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.convert_delete_new), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.convert_keep))
            }
        }
    }
}

/** A convert's result when its screen is gone (a lock stopped it, or it
 *  ended in the background and the vaults locked since). Shown by
 *  VaultPonyRoot wherever the user is. */
@Composable
internal fun ConvertResultDialog(result: ConvertResult) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.convert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                convertResultLines(context.resources, result).forEach { Text(it) }
            }
        },
        confirmButton = {
            if (result.ok) {
                TextButton(onClick = { TransferJobs.dismiss() }) { Text(stringResource(R.string.common_ok)) }
            } else {
                TextButton(onClick = {
                    TransferJobs.dismiss()
                    SessionRegistry.requestLock(result.newVolumeId)
                }) { Text(stringResource(R.string.convert_keep)) }
            }
        },
        dismissButton = {
            if (!result.ok) {
                TextButton(onClick = {
                    TransferJobs.dismiss()
                    deleteNewContainer(context, result.newVolumeId, result.newUri)
                }) { Text(stringResource(R.string.convert_delete_new), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

/** "Converted. N files and M folders copied and checked." and the renamed
 *  and skipped counts, or "Conversion stopped..." and why. Used on screen,
 *  in the dialog and in the background notification. */
internal fun convertResultLines(res: Resources, r: ConvertResult): List<String> {
    val lines = ArrayList<String>()
    val report = r.report
    if (report != null) {
        val files = report.files.toInt()
        val folders = report.folders.toInt()
        lines += res.getString(
            R.string.convert_done,
            res.getQuantityString(R.plurals.convert_count_files, files, files),
            res.getQuantityString(R.plurals.convert_count_folders, folders, folders),
        )
        val renamed = report.renamed.toInt()
        if (renamed > 0) lines += res.getQuantityString(R.plurals.convert_renamed, renamed, renamed)
        val skipped = report.skipped.toInt()
        if (skipped > 0) lines += res.getQuantityString(R.plurals.convert_skipped, skipped, skipped)
    } else {
        lines += res.getString(R.string.convert_stopped)
        r.error?.let { lines += batchErrorText(res, it, res.getString(R.string.err_vault_generic)) }
    }
    return lines
}

/** "Delete new container": lock it, take it off the vault list, delete the
 *  file through the provider it was created with, and release the saved
 *  permission. The TrueCrypt container is never touched. */
private fun deleteNewContainer(context: Context, volumeId: String?, uri: Uri) {
    if (volumeId != null) SessionRegistry.requestLock(volumeId)
    val app = context.applicationContext
    VaultStore.remove(app, uri.toString())
    Thread {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }.start()
}
