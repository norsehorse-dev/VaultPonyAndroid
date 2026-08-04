package dev.norsehorse.vaultpony.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.ChipTone
import dev.norsehorse.vaultpony.ui.components.MonoChip
import dev.norsehorse.vaultpony.ui.components.fileIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.VaultSession

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
                Text("Vault", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                MonoChip(if (writable) "read/write" else "read-only", ChipTone.Accent)
                TextButton(onClick = onLock) { Text("Lock") }
            }

            // Breadcrumb
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val crumbs = breadcrumbs(path)
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

            if (writable) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    ActionPill(Icons.Filled.CreateNewFolder, "New folder", accent = true) {
                        showNewFolder = true
                    }
                    Spacer(Modifier.size(8.dp))
                    ActionPill(Icons.Filled.FileUpload, "Import") {
                        SessionRegistry.expectPicker()
                        importPicker.launch(arrayOf("*/*"))
                    }
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
                                onLongClick = { if (writable) actionFor = entry },
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
                    SheetRow(Icons.Filled.Visibility, "Open") {
                        actionFor = null
                        viewing = entry
                    }
                }
                SheetRow(Icons.Filled.Edit, "Rename") {
                    renaming = entry
                    actionFor = null
                }
                SheetRow(Icons.Filled.Delete, "Delete", danger = true) {
                    deleting = entry
                    actionFor = null
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = "New folder", initial = "", confirm = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    try { repo.mkdir(session, child(path, name)); refresh++ }
                    catch (e: Exception) { error = e.message ?: "could not create folder" }
                }
            },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename", initial = entry.name, confirm = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                renaming = null
                scope.launch {
                    try { repo.rename(session, child(path, entry.name), child(path, newName)); refresh++ }
                    catch (e: Exception) { error = e.message ?: "rename failed" }
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
                        try { repo.remove(session, child(path, entry.name)); refresh++ }
                        catch (e: Exception) { error = e.message ?: "delete failed" }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
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

/** Path → [(label, fullPath)] with the root shown as "Vault". */
private fun breadcrumbs(path: String): List<Pair<String, String>> {
    val out = mutableListOf("Vault" to "/")
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
