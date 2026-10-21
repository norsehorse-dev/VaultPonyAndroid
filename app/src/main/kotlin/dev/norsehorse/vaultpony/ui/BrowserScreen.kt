package dev.norsehorse.vaultpony.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.norsehorse.vaultpony.CollisionPolicy
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.TransferJobs
import dev.norsehorse.vaultpony.TransferResult
import dev.norsehorse.vaultpony.VaultChanges
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.ChipTone
import dev.norsehorse.vaultpony.ui.components.MonoChip
import dev.norsehorse.vaultpony.ui.components.fileIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.VaultSession
import uniffi.vault_ffi.VolumeFacts
import uniffi.vault_ffi.VolumeSpace

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(session: VaultSession, onLock: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    // Pre-resolved so they can be used inside coroutine and launcher callbacks.
    val errImport = stringResource(R.string.browser_err_import)
    val errExport = stringResource(R.string.browser_err_export)
    val errListing = stringResource(R.string.browser_err_listing)
    val errMkdir = stringResource(R.string.browser_err_mkdir)
    val errRename = stringResource(R.string.browser_err_rename)
    val errDelete = stringResource(R.string.browser_err_delete)
    val rootLabel = stringResource(R.string.browser_title)

    // Saveable so a rotation (which now keeps the vault open) keeps the folder.
    var path by rememberSaveable { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var facts by remember { mutableStateOf<VolumeFacts?>(null) }
    var space by remember { mutableStateOf<VolumeSpace?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<DirEntry?>(null) }
    // Changes made through the Files app (or a hidden-volume trip, which also
    // posts one) reload the folder on screen. Reading it here also recomposes,
    // so the read-only latch below is re-checked.
    val outsideChange by VaultChanges.last.collectAsState()
    // An outer volume whose hidden-volume protection tripped is read-only for
    // the rest of the session, whatever facts.writable says.
    val latched = facts != null && SessionRegistry.isReadOnlyLatched(session)
    val writable = (facts?.writable ?: false) && !latched
    // POST_NOTIFICATIONS explanation before the first import/export (R2).
    val notifGate = rememberNotificationGate()

    var showNewFolder by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<DirEntry?>(null) }
    var renaming by remember { mutableStateOf<DirEntry?>(null) }
    var deleting by remember { mutableStateOf<DirEntry?>(null) }
    var exporting by remember { mutableStateOf<DirEntry?>(null) }
    // Vault folder waiting for the export destination picker. Saveable: the
    // activity can be recreated while the picker is up.
    var exportDir by rememberSaveable { mutableStateOf<String?>(null) }

    // Bulk import/export runs in TransferJobs, outside this composable, so a
    // rotation or recomposition can't kill it. Only this session's batch shows.
    val transferAll by TransferJobs.state.collectAsState()
    val transfer = if (TransferJobs.owner === session) transferAll else TransferJobs.State.Idle

    val importFilesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) TransferJobs.importFiles(context, session, path, uris)
    }

    val importFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) TransferJobs.importTree(context, session, path, tree)
    }

    val exportFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        val dir = exportDir
        exportDir = null
        if (tree != null && dir != null) TransferJobs.exportFolder(context, session, dir, tree)
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = exporting
        exporting = null
        if (uri != null && entry != null) {
            scope.launch {
                try {
                    repo.exportFile(session, child(path, entry.name), entry.size, uri)
                    refresh++
                } catch (e: Exception) {
                    error = vaultFailureText(context.resources, e, errExport, session, "export")
                }
            }
        }
    }

    fun startFolderExport(dir: String) {
        notifGate.runAfter {
            exportDir = dir
            SessionRegistry.expectPicker()
            exportFolderPicker.launch(null)
        }
    }

    BackHandler(enabled = viewing != null || path != "/") {
        when {
            viewing != null -> viewing = null
            else -> path = parent(path)
        }
    }

    // The folder first, then facts and free space. Space is re-read after
    // every change (import, delete, export, mkdir...); the first call per
    // session counts every free FAT cluster under the session's lock, so it
    // goes last and never delays the listing. Later calls are cached and
    // cheap. Filesystems that can't report it (NTFS) throw, and the figure is
    // simply not shown.
    LaunchedEffect(session, path, refresh) {
        try {
            entries = withContext(Dispatchers.IO) {
                session.list(path).sortedWith(
                    compareByDescending<DirEntry> { it.isDir }.thenBy { it.name.lowercase() },
                )
            }
            error = null
        } catch (e: Exception) {
            error = vaultFailureText(context.resources, e, errListing, where = "list")
        }
        facts = try {
            withContext(Dispatchers.IO) { session.facts() }
        } catch (e: Exception) {
            null
        }
        space = try {
            withContext(Dispatchers.IO) { session.space() }
        } catch (e: Exception) {
            null
        }
    }

    // A finished batch changed the folder (or, on export, nothing), refresh both.
    LaunchedEffect(transfer) {
        if (transfer is TransferJobs.State.Done) refresh++
    }

    // Another app wrote into this vault through the Files app (plan §4.1).
    LaunchedEffect(outsideChange) {
        val c = outsideChange ?: return@LaunchedEffect
        if (c.volumeId == SessionRegistry.idOf(session) && c.affects(path)) refresh++
    }

    val current = viewing
    if (current != null) {
        ViewerScreen(
            session = session,
            path = child(path, current.name),
            size = current.size,
            onBack = { viewing = null },
        )
        return
    }


    val volumeLabel = facts?.let { volumeLabelRes(it) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // App bar
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(rootLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                MonoChip(
                    if (writable) stringResource(R.string.browser_rw) else stringResource(R.string.browser_ro),
                    ChipTone.Accent,
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.browser_volume_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onLock) { Text(stringResource(R.string.browser_lock)) }
            }

            // Which volume is open, and free space (plan §2.1, §2.2). A plain
            // outer unlock gets no label.
            val sp = space
            if (volumeLabel != null || sp != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    volumeLabel?.let { MonoChip(stringResource(it), ChipTone.Hidden) }
                    if (sp != null) {
                        Text(
                            stringResource(
                                R.string.browser_space_free,
                                Formatter.formatFileSize(context, sp.freeBytes.toLong()),
                                Formatter.formatFileSize(context, sp.totalBytes.toLong()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (latched) {
                Text(
                    stringResource(R.string.err_hidden_protected),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // Breadcrumb
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val crumbs = breadcrumbs(path, rootLabel)
                crumbs.forEachIndexed { i, (label, full) ->
                    if (i > 0) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        label,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { path = full }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }

            // Actions. Scrolls sideways: four pills don't fit a narrow phone.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (writable) {
                    ActionPill(Icons.Filled.CreateNewFolder, stringResource(R.string.browser_new_folder), accent = true) {
                        showNewFolder = true
                    }
                    ActionPill(Icons.Filled.FileUpload, stringResource(R.string.browser_import_files)) {
                        notifGate.runAfter {
                            SessionRegistry.expectPicker()
                            importFilesPicker.launch(arrayOf("*/*"))
                        }
                    }
                    ActionPill(Icons.Filled.Folder, stringResource(R.string.browser_import_folder)) {
                        notifGate.runAfter {
                            SessionRegistry.expectPicker()
                            importFolderPicker.launch(null)
                        }
                    }
                }
                // Reading out works on a read-only vault too.
                ActionPill(Icons.Filled.FileDownload, stringResource(R.string.browser_export_folder)) {
                    startFolderExport(path)
                }
            }
            HorizontalDivider(Modifier.padding(top = 6.dp))

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.name }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (entry.isDir) path = child(path, entry.name) else viewing = entry
                                },
                                // The sheet's export actions work read-only;
                                // rename and delete only show when writable.
                                onLongClick = { actionFor = entry },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                fileIcon(entry.name, entry.isDir),
                                contentDescription = null,
                                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Text(entry.name, modifier = Modifier.weight(1f))
                        if (entry.isDir) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            MonoChip(humanSize(entry.size))
                        }
                    }
                }
            }
        }
    }

    // -- Bottom sheet: per-entry actions ----------------------------------
    actionFor?.let { entry ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { actionFor = null }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        fileIcon(entry.name, entry.isDir),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                if (!entry.isDir) {
                    SheetRow(Icons.Filled.Visibility, stringResource(R.string.browser_open)) {
                        actionFor = null
                        viewing = entry
                    }
                    SheetRow(Icons.Filled.FileDownload, stringResource(R.string.browser_export_copy)) {
                        exporting = entry
                        actionFor = null
                        SessionRegistry.expectPicker()
                        exportPicker.launch(entry.name)
                    }
                } else {
                    SheetRow(Icons.Filled.FileDownload, stringResource(R.string.browser_export_folder)) {
                        actionFor = null
                        startFolderExport(child(path, entry.name))
                    }
                }
                if (writable) {
                    SheetRow(Icons.Filled.Edit, stringResource(R.string.browser_rename)) {
                        renaming = entry
                        actionFor = null
                    }
                    SheetRow(Icons.Filled.Delete, stringResource(R.string.common_delete), danger = true) {
                        deleting = entry
                        actionFor = null
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = stringResource(R.string.browser_new_folder), initial = "",
            confirm = stringResource(R.string.browser_create),
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    try { repo.mkdir(session, child(path, name)); refresh++ }
                    catch (e: Exception) { error = vaultFailureText(context.resources, e, errMkdir, session, "mkdir") }
                }
            },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = stringResource(R.string.browser_rename), initial = entry.name,
            confirm = stringResource(R.string.browser_rename),
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                renaming = null
                scope.launch {
                    try { repo.rename(session, child(path, entry.name), child(path, newName)); refresh++ }
                    catch (e: Exception) { error = vaultFailureText(context.resources, e, errRename, session, "rename") }
                }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.browser_delete_title, entry.name)) },
            text = {
                Text(
                    if (entry.isDir) stringResource(R.string.browser_delete_folder_empty)
                    else stringResource(R.string.browser_delete_file_warning),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try { repo.remove(session, child(path, entry.name)); refresh++ }
                        catch (e: Exception) { error = vaultFailureText(context.resources, e, errDelete, session, "delete") }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showInfo) {
        VolumeInfoDialog(
            facts = facts,
            space = space,
            labelRes = volumeLabel,
            writable = writable,
            onDismiss = { showInfo = false },
        )
    }

    TransferDialogs(transfer, fallbackError = if (transferIsExport(transfer)) errExport else errImport)
}

/** "Hidden volume" / "Hidden area protected", or nothing for a plain outer
 *  unlock. Never a control: switching volumes means lock and unlock. */
private fun volumeLabelRes(f: VolumeFacts): Int? = when {
    f.hidden -> R.string.browser_label_hidden
    f.outerProtected -> R.string.browser_label_protected
    else -> null
}

private fun transferIsExport(t: TransferJobs.State): Boolean = when (t) {
    is TransferJobs.State.Preparing -> t.export
    is TransferJobs.State.Running -> t.export
    is TransferJobs.State.Done -> t.result.export
    else -> false
}

@Composable
private fun VolumeInfoDialog(
    facts: VolumeFacts?,
    space: VolumeSpace?,
    labelRes: Int?,
    writable: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_volume_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labelRes?.let { MonoChip(stringResource(it), ChipTone.Hidden) }
                if (facts != null) {
                    InfoRow(stringResource(R.string.info_encryption), facts.scheme)
                    InfoRow(stringResource(R.string.info_hash), facts.prf)
                    InfoRow(stringResource(R.string.info_filesystem), facts.filesystem)
                }
                if (space != null) {
                    InfoRow(
                        stringResource(R.string.info_size),
                        Formatter.formatFileSize(context, space.totalBytes.toLong()),
                    )
                    InfoRow(
                        stringResource(R.string.info_free),
                        Formatter.formatFileSize(context, space.freeBytes.toLong()),
                    )
                }
                InfoRow(
                    stringResource(R.string.info_access),
                    stringResource(if (writable) R.string.info_access_rw else R.string.info_access_ro),
                )
                if (facts?.maxFileBytes != null) {
                    Text(
                        stringResource(R.string.info_fat_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// Dialogs of a bulk batch never close on back or an outside tap: each state
// has its own way out (Cancel, Continue, OK) so a long copy can't be lost by
// a stray touch.
private val TRANSFER_DIALOG = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

@Composable
private fun TransferDialogs(state: TransferJobs.State, fallbackError: String) {
    val context = LocalContext.current
    when (state) {
        TransferJobs.State.Idle -> Unit

        is TransferJobs.State.Preparing -> AlertDialog(
            onDismissRequest = {},
            properties = TRANSFER_DIALOG,
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.transfer_preparing))
                }
            },
            confirmButton = {
                TextButton(onClick = { TransferJobs.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )

        is TransferJobs.State.AskCollision -> {
            var choice by remember { mutableStateOf(CollisionPolicy.KEEP_BOTH) }
            AlertDialog(
                onDismissRequest = {},
                properties = TRANSFER_DIALOG,
                title = { Text(stringResource(R.string.transfer_collision_title)) },
                text = {
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            CollisionPolicy.KEEP_BOTH to R.string.transfer_keep_both,
                            CollisionPolicy.SKIP to R.string.transfer_skip,
                            CollisionPolicy.REPLACE to R.string.transfer_replace,
                        ).forEach { (policy, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = choice == policy,
                                        role = Role.RadioButton,
                                        onClick = { choice = policy },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = choice == policy, onClick = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(label))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { TransferJobs.answerCollision(choice) }) {
                        Text(stringResource(R.string.common_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { TransferJobs.cancel() }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        is TransferJobs.State.NoSpace -> AlertDialog(
            onDismissRequest = {},
            properties = TRANSFER_DIALOG,
            title = { Text(stringResource(R.string.transfer_no_space_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.transfer_no_space_body,
                        Formatter.formatFileSize(context, state.needed),
                        Formatter.formatFileSize(context, state.free),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { TransferJobs.dismiss() }) { Text(stringResource(R.string.common_ok)) }
            },
        )

        is TransferJobs.State.Oversize -> AlertDialog(
            onDismissRequest = {},
            properties = TRANSFER_DIALOG,
            text = {
                Text(context.resources.getQuantityString(R.plurals.transfer_oversize, state.count, state.count))
            },
            confirmButton = {
                TextButton(onClick = { TransferJobs.answerOversize(true) }) {
                    Text(stringResource(R.string.common_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { TransferJobs.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )

        is TransferJobs.State.Running -> AlertDialog(
            onDismissRequest = {},
            properties = TRANSFER_DIALOG,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.transfer_progress, state.index, state.total))
                    Text(
                        state.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // By bytes when known, so one big file still moves the bar.
                    val fraction = if (state.bytesTotal > 0) {
                        state.bytesDone.toFloat() / state.bytesTotal
                    } else {
                        (state.index - 1).toFloat() / state.total.coerceAtLeast(1)
                    }
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { TransferJobs.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )

        is TransferJobs.State.Done -> TransferSummaryDialog(state.result, fallbackError)
    }
}

/** A finished batch's summary. Also shown by VaultPonyRoot when the batch's
 *  vault is no longer on screen (a Lock stopped the batch, or the vault
 *  auto-locked after it), so the user still learns how far it got. */
@Composable
internal fun TransferSummaryDialog(result: TransferResult, fallbackError: String) {
    val res = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = {},
        properties = TRANSFER_DIALOG,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                transferSummaryLines(res, result, fallbackError).forEach { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { TransferJobs.dismiss() }) { Text(stringResource(R.string.common_ok)) }
        },
    )
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .background(
                if (accent) cs.primary.copy(alpha = 0.13f) else cs.surfaceVariant,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (accent) cs.primary else cs.onSurface,
        )
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, color = color)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.browser_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && '/' !in name && '\\' !in name &&
                    name.trim() != "." && name.trim() != "..",
                onClick = { onConfirm(name.trim()) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Path to [(label, fullPath)] with the root shown as the localized vault label. */
private fun breadcrumbs(path: String, rootLabel: String): List<Pair<String, String>> {
    val out = mutableListOf(rootLabel to "/")
    val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
    var acc = ""
    for (s in segs) {
        acc += "/$s"
        out.add(s to acc)
    }
    return out
}

private fun parent(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

private fun child(path: String, name: String): String =
    if (path == "/") "/$name" else "$path/$name"

internal fun humanSize(bytes: ULong): String {
    val b = bytes.toDouble()
    return when {
        b >= 1 shl 30 -> "%.1f GiB".format(b / (1 shl 30))
        b >= 1 shl 20 -> "%.1f MiB".format(b / (1 shl 20))
        b >= 1 shl 10 -> "%.1f KiB".format(b / (1 shl 10))
        else -> "${bytes} B"
    }
}
