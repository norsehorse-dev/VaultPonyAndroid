package dev.norsehorse.vaultpony.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.VaultSession

/**
 * Read/write browser (doc §7). Folders descend, files open in the viewer.
 * When the volume is writable, the top bar offers New Folder / Import and a
 * long-press gives Rename / Delete. Every mutation flushes and refreshes; no
 * filename ever reaches logs or system UI.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(session: VaultSession, onLock: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var writable by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<DirEntry?>(null) }

    // Dialog state.
    var showNewFolder by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<DirEntry?>(null) }
    var renaming by remember { mutableStateOf<DirEntry?>(null) }
    var deleting by remember { mutableStateOf<DirEntry?>(null) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    repo.importFile(session, path, uri)
                    refresh++
                } catch (e: Exception) {
                    error = e.message ?: "import failed"
                }
            }
        }
    }

    BackHandler(enabled = viewing != null || path != "/") {
        when {
            viewing != null -> viewing = null
            else -> path = parent(path)
        }
    }

    LaunchedEffect(session) {
        writable = try {
            withContext(Dispatchers.IO) { session.facts().writable }
        } catch (e: Exception) {
            false
        }
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

    LaunchedEffect(path, refresh) {
        try {
            entries = withContext(Dispatchers.IO) {
                session.list(path).sortedWith(
                    compareByDescending<DirEntry> { it.isDir }.thenBy { it.name.lowercase() },
                )
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "listing failed"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = path,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (path != "/") {
                TextButton(onClick = { path = parent(path) }) { Text("Up") }
            }
            TextButton(onClick = onLock) { Text("Lock") }
        }
        if (writable) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = { showNewFolder = true }) { Text("New Folder") }
                TextButton(onClick = {
                    SessionRegistry.expectPicker()
                    importPicker.launch(arrayOf("*/*"))
                }) { Text("Import") }
            }
        }
        HorizontalDivider()

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        LazyColumn {
            items(entries, key = { it.name }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (entry.isDir) path = child(path, entry.name) else viewing = entry
                            },
                            onLongClick = { if (writable) actionFor = entry },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (entry.isDir) "${entry.name}/" else entry.name,
                        modifier = Modifier.weight(1f),
                    )
                    if (!entry.isDir) {
                        Text(
                            text = humanSize(entry.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    // -- Dialogs ----------------------------------------------------------

    if (showNewFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    try {
                        repo.mkdir(session, child(path, name))
                        refresh++
                    } catch (e: Exception) {
                        error = e.message ?: "could not create folder"
                    }
                }
            },
        )
    }

    actionFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(entry.name) },
            text = { Text("Choose an action.") },
            confirmButton = {
                TextButton(onClick = { renaming = entry; actionFor = null }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = entry; actionFor = null }) { Text("Delete") }
            },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename",
            initial = entry.name,
            confirm = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                renaming = null
                scope.launch {
                    try {
                        repo.rename(session, child(path, entry.name), child(path, newName))
                        refresh++
                    } catch (e: Exception) {
                        error = e.message ?: "rename failed"
                    }
                }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (entry.isDir) "The folder must be empty."
                    else "This permanently removes the file from the volume.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try {
                            repo.remove(session, child(path, entry.name))
                            refresh++
                        } catch (e: Exception) {
                            error = e.message ?: "delete failed"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
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
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && '/' !in name,
                onClick = { onConfirm(name.trim()) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
