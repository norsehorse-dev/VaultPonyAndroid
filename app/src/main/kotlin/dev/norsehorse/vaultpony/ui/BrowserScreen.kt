package dev.norsehorse.vaultpony.ui

import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.BrowserSort
import dev.norsehorse.vaultpony.BulkPlanner
import dev.norsehorse.vaultpony.CollisionPolicy
import dev.norsehorse.vaultpony.ProviderPaths
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.ThumbnailCache
import dev.norsehorse.vaultpony.TransferJobs
import dev.norsehorse.vaultpony.TransferResult
import dev.norsehorse.vaultpony.VaultChanges
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.ChipTone
import dev.norsehorse.vaultpony.ui.components.MonoChip
import dev.norsehorse.vaultpony.ui.components.fileIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.VaultSession
import uniffi.vault_ffi.VolumeFacts
import uniffi.vault_ffi.VolumeSpace

/** A file open in the viewer: its full vault path, so a whole-vault search
 *  result in another folder opens too. */
private data class Viewing(val path: String, val size: ULong)

/** A whole-vault search match: [dir] is the folder it is in. */
private data class SearchHit(val dir: String, val entry: DirEntry) {
    val path: String get() = child(dir, entry.name)
}

// Whole-vault search stops listing more once it has this many matches.
private const val MAX_SEARCH_HITS = 2000

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(session: VaultSession, onLock: () -> Unit, onConvert: () -> Unit = {}) {
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
    val errMove = stringResource(R.string.browser_err_move)
    val rootLabel = stringResource(R.string.browser_title)

    // Saveable so a rotation (which now keeps the vault open) keeps the folder.
    var path by rememberSaveable { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var facts by remember { mutableStateOf<VolumeFacts?>(null) }
    var space by remember { mutableStateOf<VolumeSpace?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
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

    // View and sort (plan §5.3): app-wide, remembered in AppPrefs.
    var grid by remember { mutableStateOf(AppPrefs.browserGrid(context)) }
    var sort by remember { mutableStateOf(AppPrefs.browserSort(context)) }
    var descending by remember { mutableStateOf(AppPrefs.browserSortDescending(context)) }
    var showMenu by remember { mutableStateOf(false) }

    // Search (plan §5.3): this folder filters as you type; the whole vault is
    // walked in the background, cancelled by any change and by a lock.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var wholeVault by rememberSaveable { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var walking by remember { mutableStateOf(false) }
    val needle = query.trim()
    val vaultSearch = searchOpen && wholeVault && needle.isNotEmpty()

    // Multi-select (plan §5.3): names in the folder on screen. Long-press
    // starts it; empty means not selecting.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    // Names being moved out of [moveFrom]; the folder picker shows meanwhile.
    var moving by remember { mutableStateOf<List<String>?>(null) }
    var moveFrom by remember { mutableStateOf("/") }
    // Selection waiting for the export destination picker (saveable, as above).
    var exportNames by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var exportNamesDir by rememberSaveable { mutableStateOf<String?>(null) }
    // Camera capture into the folder on screen (2.0.0 rc5, plan 5.6): the
    // mode it opened in, CAMERA_PHOTO or CAMERA_VIDEO, while it is open.
    var camera by rememberSaveable { mutableStateOf<String?>(null) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    // Bulk import/export runs in TransferJobs, outside this composable, so a
    // rotation or recomposition can't kill it. Only this session's batch shows,
    // and never a convert, which has its own screen (2.0.0 rc5).
    val transferAll by TransferJobs.state.collectAsState()
    val transfer = if (TransferJobs.owner === session && !TransferJobs.converting) {
        transferAll
    } else {
        TransferJobs.State.Idle
    }

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

    // Export of the multi-selection, through the same bulk export pipeline.
    val exportItemsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        val names = exportNames
        val dir = exportNamesDir
        exportNames = null
        exportNamesDir = null
        if (tree != null && names != null && dir != null) {
            TransferJobs.exportItems(context, session, dir, names, tree)
        }
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

    fun closeSearch() {
        searchOpen = false
        query = ""
        hits = emptyList()
    }

    fun open(entry: DirEntry) {
        if (entry.isDir) path = child(path, entry.name) else viewing = Viewing(child(path, entry.name), entry.size)
    }

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }

    BackHandler(enabled = viewing != null || selecting || searchOpen || path != "/") {
        when {
            viewing != null -> viewing = null
            selecting -> selected = emptySet()
            searchOpen -> closeSearch()
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
            entries = withContext(Dispatchers.IO) { session.list(path) }
            error = null
        } catch (e: Exception) {
            error = vaultFailureText(context.resources, e, errListing, where = "list")
        }
        // A reload drops selected names that are gone.
        val names = entries.mapTo(HashSet()) { it.name }
        if (selected.any { it !in names }) selected = selected.filterTo(HashSet()) { it in names }
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

    // A selection belongs to one folder, and to the search filter it was
    // made under: never act on items the filter has hidden.
    LaunchedEffect(path, searchOpen, wholeVault, needle) { selected = emptySet() }

    // A finished batch changed the folder (or, on export, nothing), refresh both.
    LaunchedEffect(transfer) {
        if (transfer is TransferJobs.State.Done) refresh++
    }

    // Another app wrote into this vault through the Files app (plan §4.1).
    LaunchedEffect(outsideChange) {
        val c = outsideChange ?: return@LaunchedEffect
        if (c.volumeId == SessionRegistry.idOf(session) && c.affects(path)) refresh++
    }

    // Whole-vault search: walk folder by folder off the main thread, adding
    // matches as each folder is read. Restarts on every keystroke (after a
    // short pause) and stops with the screen or at lock.
    LaunchedEffect(session, vaultSearch, needle, refresh) {
        hits = emptyList()
        if (!vaultSearch) {
            walking = false
            return@LaunchedEffect
        }
        walking = true
        try {
            delay(250)
            val found = ArrayList<SearchHit>()
            val pending = ArrayDeque<String>()
            pending.addLast("/")
            while (pending.isNotEmpty() && found.size < MAX_SEARCH_HITS) {
                yield()
                if (!SessionRegistry.isOpen(session)) break
                val dir = pending.removeFirst()
                val list = withContext(Dispatchers.IO) { session.list(dir) }
                var added = false
                for (e in list.sortedBy { it.name.lowercase() }) {
                    if (BulkPlanner.isTempName(e.name)) continue
                    if (e.isDir) pending.addLast(child(dir, e.name))
                    if (e.name.contains(needle, ignoreCase = true) && found.size < MAX_SEARCH_HITS) {
                        found += SearchHit(dir, e)
                        added = true
                    }
                }
                if (added) hits = found.toList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The vault locked or a folder couldn't be read: keep what was found.
        } finally {
            walking = false
        }
    }

    val cam = camera
    if (cam != null) {
        CameraScreen(
            session = session,
            dir = path,
            folderLabel = if (path == "/") rootLabel else path.trimEnd('/').substringAfterLast('/'),
            startVideo = cam == CAMERA_VIDEO,
            onClose = {
                camera = null
                refresh++
            },
        )
        return
    }

    val current = viewing
    if (current != null) {
        ViewerScreen(
            session = session,
            path = current.path,
            size = current.size,
            onBack = { viewing = null },
        )
        return
    }

    val names = moving
    if (names != null) {
        val from = moveFrom
        FolderPickerScreen(
            session = session,
            title = stringResource(R.string.browser_move_to),
            confirmLabel = stringResource(R.string.browser_move_here),
            header = null,
            startPath = from,
            // Never into a folder being moved, or anywhere below it.
            isBlocked = { dir -> names.any { ProviderPaths.isWithin(child(from, it), dir) } },
            canPick = { dir -> !ProviderPaths.samePath(dir, from) },
            onPick = { target ->
                moving = null
                selected = emptySet()
                scope.launch {
                    try {
                        repo.move(session, from, names, target)
                    } catch (e: Exception) {
                        error = vaultFailureText(context.resources, e, errMove, session, "move")
                    }
                    refresh++
                }
            },
            onCancel = { moving = null },
        )
        return
    }

    val volumeLabel = facts?.let { volumeLabelRes(it) }
    // Folders first, then the chosen order; "This folder" search filters it.
    val sorted = remember(entries, sort, descending) { sortEntries(entries, sort, descending) }
    val shown = if (searchOpen && !wholeVault && needle.isNotEmpty()) {
        sorted.filter { it.name.contains(needle, ignoreCase = true) }
    } else {
        sorted
    }
    val thumbPx = with(LocalDensity.current) { 112.dp.roundToPx() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // App bar
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
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
                IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.browser_search),
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.browser_more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ViewSortMenu(
                        expanded = showMenu,
                        grid = grid,
                        sort = sort,
                        descending = descending,
                        onDismiss = { showMenu = false },
                        onGrid = {
                            grid = it
                            AppPrefs.setBrowserGrid(context, it)
                        },
                        onSort = {
                            sort = it
                            AppPrefs.setBrowserSort(context, it)
                        },
                        onDescending = {
                            descending = it
                            AppPrefs.setBrowserSortDescending(context, it)
                        },
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

            // TrueCrypt volumes open read-only; the way to change one is to
            // convert it (2.0.0 rc5, plan 6.2, D9).
            if (facts?.truecrypt == true) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                        Text(
                            stringResource(R.string.browser_truecrypt_banner),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        TextButton(onClick = onConvert, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.convert_title))
                        }
                    }
                }
            }

            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.browser_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { closeSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !wholeVault,
                        onClick = { wholeVault = false },
                        label = { Text(stringResource(R.string.browser_search_this_folder)) },
                    )
                    FilterChip(
                        selected = wholeVault,
                        onClick = {
                            wholeVault = true
                            selected = emptySet()
                        },
                        label = { Text(stringResource(R.string.browser_search_whole_vault)) },
                    )
                }
            }

            if (!vaultSearch) {
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
            }

            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    writable = writable,
                    onSelectAll = { selected = shown.mapTo(HashSet()) { it.name } },
                    onDelete = { confirmDeleteSelected = true },
                    onExport = {
                        val picked = ArrayList(selected)
                        val dir = path
                        notifGate.runAfter {
                            exportNames = picked
                            exportNamesDir = dir
                            selected = emptySet()
                            SessionRegistry.expectPicker()
                            exportItemsPicker.launch(null)
                        }
                    },
                    onMove = {
                        moveFrom = path
                        moving = selected.toList()
                    },
                    onCancel = { selected = emptySet() },
                    // One item: its own actions (open, export a copy, rename,
                    // delete), which a long-press no longer opens.
                    onMore = selected.singleOrNull()
                        ?.let { name -> entries.firstOrNull { it.name == name } }
                        ?.let { entry -> { actionFor = entry } },
                )
            } else if (!vaultSearch) {
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
                        // Straight into this folder, never the gallery (plan 5.6).
                        if (hasCamera) {
                            ActionPill(Icons.Filled.PhotoCamera, stringResource(R.string.camera_take_photo)) {
                                camera = CAMERA_PHOTO
                            }
                            ActionPill(Icons.Filled.Videocam, stringResource(R.string.camera_record_video)) {
                                camera = CAMERA_VIDEO
                            }
                        }
                    }
                    // Reading out works on a read-only vault too.
                    ActionPill(Icons.Filled.FileDownload, stringResource(R.string.browser_export_folder)) {
                        startFolderExport(path)
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

            when {
                vaultSearch -> {
                    if (walking) SearchStatus(stringResource(R.string.browser_searching), progress = true)
                    if (!walking && hits.isEmpty()) {
                        SearchStatus(stringResource(R.string.browser_search_no_matches), progress = false)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(hits, key = { it.path }) { hit ->
                            SearchHitRow(hit, rootLabel) {
                                if (hit.entry.isDir) {
                                    path = hit.path
                                    closeSearch()
                                } else {
                                    viewing = Viewing(hit.path, hit.entry.size)
                                }
                            }
                        }
                    }
                }

                shown.isEmpty() && searchOpen && needle.isNotEmpty() ->
                    SearchStatus(stringResource(R.string.browser_search_no_matches), progress = false)

                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.name }) { entry ->
                        GridCell(
                            session = session,
                            dir = path,
                            entry = entry,
                            thumbPx = thumbPx,
                            selecting = selecting,
                            isSelected = entry.name in selected,
                            onClick = { if (selecting) toggle(entry.name) else open(entry) },
                            onLongClick = { if (selecting) toggle(entry.name) else selected = setOf(entry.name) },
                        )
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.name }) { entry ->
                        ListRow(
                            entry = entry,
                            selecting = selecting,
                            isSelected = entry.name in selected,
                            onClick = { if (selecting) toggle(entry.name) else open(entry) },
                            onLongClick = { if (selecting) toggle(entry.name) else selected = setOf(entry.name) },
                            onMore = { actionFor = entry },
                        )
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
                        viewing = Viewing(child(path, entry.name), entry.size)
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

    // Multi-select delete: folders go with everything in them.
    if (confirmDeleteSelected) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(pluralStringResource(R.plurals.browser_delete_items_title, count, count)) },
            text = { Text(stringResource(R.string.browser_delete_items_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    val dir = path
                    val targets = selected.map { child(dir, it) }
                    selected = emptySet()
                    scope.launch {
                        try {
                            repo.removeAll(session, targets)
                        } catch (e: Exception) {
                            error = vaultFailureText(context.resources, e, errDelete, session, "delete")
                        }
                        refresh++
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showInfo) {
        VolumeInfoDialog(
            facts = facts,
            space = space,
            labelRes = volumeLabel,
            writable = writable,
            onDismiss = { showInfo = false },
            onConvert = {
                showInfo = false
                onConvert()
            },
        )
    }

    TransferDialogs(transfer, fallbackError = if (transferIsExport(transfer)) errExport else errImport)
}

// The browser's camera modes (plan 5.6), saved across a rotation.
private const val CAMERA_PHOTO = "photo"
private const val CAMERA_VIDEO = "video"

/** A filesystem as the user knows it: the core says "Ext4" for ext2, ext3
 *  and ext4 alike, shown as "ext4" (2.0.0 rc5, plan 6.3). Others as the core
 *  names them. */
internal fun filesystemName(raw: String): String =
    if (raw.equals("Ext4", ignoreCase = true)) "ext4" else raw

/** Folders first (always), then by [sort], ties by name. */
private fun sortEntries(list: List<DirEntry>, sort: BrowserSort, descending: Boolean): List<DirEntry> {
    val byName = compareBy<DirEntry> { it.name.lowercase() }
    val key: Comparator<DirEntry> = when (sort) {
        BrowserSort.NAME -> byName
        BrowserSort.SIZE -> compareBy<DirEntry> { it.size }.then(byName)
        BrowserSort.DATE -> compareBy<DirEntry> { it.mtimeMs ?: Long.MIN_VALUE }.then(byName)
    }
    val ordered = if (descending) key.reversed() else key
    return list.sortedWith(compareByDescending<DirEntry> { it.isDir }.then(ordered))
}

/** The browser's menu: List / Grid, then Sort by Name / Size / Date
 *  modified, Ascending / Descending. The current choices carry a check. */
@Composable
private fun ViewSortMenu(
    expanded: Boolean,
    grid: Boolean,
    sort: BrowserSort,
    descending: Boolean,
    onDismiss: () -> Unit,
    onGrid: (Boolean) -> Unit,
    onSort: (BrowserSort) -> Unit,
    onDescending: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuCheckItem(stringResource(R.string.browser_view_list), !grid, onDismiss) { onGrid(false) }
        MenuCheckItem(stringResource(R.string.browser_view_grid), grid, onDismiss) { onGrid(true) }
        HorizontalDivider()
        Text(
            stringResource(R.string.browser_sort_by),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        MenuCheckItem(stringResource(R.string.browser_sort_name), sort == BrowserSort.NAME, onDismiss) { onSort(BrowserSort.NAME) }
        MenuCheckItem(stringResource(R.string.browser_sort_size), sort == BrowserSort.SIZE, onDismiss) { onSort(BrowserSort.SIZE) }
        MenuCheckItem(stringResource(R.string.browser_sort_date), sort == BrowserSort.DATE, onDismiss) { onSort(BrowserSort.DATE) }
        HorizontalDivider()
        MenuCheckItem(stringResource(R.string.browser_sort_ascending), !descending, onDismiss) { onDescending(false) }
        MenuCheckItem(stringResource(R.string.browser_sort_descending), descending, onDismiss) { onDescending(true) }
    }
}

@Composable
private fun MenuCheckItem(label: String, checked: Boolean, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            onClick()
            onDismiss()
        },
        leadingIcon = {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
    )
}

/** "%d selected" with Select all, Delete, Export, Move and Cancel. Delete
 *  and Move only on a writable vault. */
@Composable
private fun SelectionBar(
    count: Int,
    writable: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onCancel: () -> Unit,
    onMore: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.browser_selected, count, count),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (onMore != null) {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.browser_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.browser_select_all)) }
            if (writable) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onExport) { Text(stringResource(R.string.browser_export)) }
            if (writable) TextButton(onClick = onMove) { Text(stringResource(R.string.browser_move)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** Selected / not selected, while selecting. */
@Composable
private fun SelectionMark(isSelected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(22.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    entry: DirEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
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
        if (!entry.isDir) MonoChip(humanSize(entry.size))
        if (selecting) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SelectionMark(isSelected) }
        } else {
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.browser_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    session: VaultSession,
    dir: String,
    entry: DirEntry,
    thumbPx: Int,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) cs.primary.copy(alpha = 0.10f) else cs.surface.copy(alpha = 0f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = rememberThumbnail(session, child(dir, entry.name), entry, thumbPx)
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    fileIcon(entry.name, entry.isDir),
                    contentDescription = null,
                    tint = if (entry.isDir) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (selecting) {
                SelectionMark(
                    isSelected,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(cs.surface, RoundedCornerShape(50)),
                )
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** An image's thumbnail, decoded in memory (ThumbnailCache); null for
 *  folders, other files and anything that can't be decoded. */
@Composable
private fun rememberThumbnail(session: VaultSession, path: String, entry: DirEntry, targetPx: Int): ImageBitmap? {
    if (entry.isDir || !ThumbnailCache.supported(entry.name, entry.size)) return null
    val key = ThumbnailCache.key(session, path, entry.size, entry.mtimeMs) ?: return null
    val thumb by produceState(initialValue = ThumbnailCache.cached(key)?.asImageBitmap(), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                ThumbnailCache.load(session, path, entry.size, entry.mtimeMs, targetPx)
            }?.asImageBitmap()
        }
    }
    return thumb
}

@Composable
private fun SearchStatus(text: String, progress: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A whole-vault match: the name, and the folder it is in under it. */
@Composable
private fun SearchHitRow(hit: SearchHit, rootLabel: String, onClick: () -> Unit) {
    val entry = hit.entry
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
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
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folderLabel(hit.dir, rootLabel),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/** "Vault/Photos/2024" for "/Photos/2024", "Vault" for the root. */
private fun folderLabel(dir: String, rootLabel: String): String =
    if (dir == "/") rootLabel else rootLabel + dir

/** "Hidden volume" / "Hidden area protected", or nothing for a plain outer
 *  unlock. Never a control: switching volumes means lock and unlock. */
private fun volumeLabelRes(f: VolumeFacts): Int? = when {
    f.hidden -> R.string.browser_label_hidden
    f.outerProtected -> R.string.browser_label_protected
    else -> null
}

internal fun transferIsExport(t: TransferJobs.State): Boolean = when (t) {
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
    onConvert: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_volume_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labelRes?.let { MonoChip(stringResource(it), ChipTone.Hidden) }
                if (facts != null) {
                    // VeraCrypt or TrueCrypt (2.0.0 rc5, plan 6.2).
                    InfoRow(
                        stringResource(R.string.info_format),
                        stringResource(if (facts.truecrypt) R.string.info_format_truecrypt else R.string.info_format_veracrypt),
                    )
                    // VeraCrypt's name for the scheme (D16). The hash can be
                    // RIPEMD-160 for TrueCrypt.
                    InfoRow(stringResource(R.string.info_encryption), schemeName(facts.scheme))
                    InfoRow(stringResource(R.string.info_hash), facts.prf)
                    InfoRow(stringResource(R.string.info_filesystem), filesystemName(facts.filesystem))
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
                if (facts?.truecrypt == true) {
                    TextButton(onClick = onConvert) { Text(stringResource(R.string.convert_title)) }
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
internal fun TransferDialogs(state: TransferJobs.State, fallbackError: String) {
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

        // The convert screen shows its own result (2.0.0 rc5).
        is TransferJobs.State.Converted -> Unit
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
internal fun breadcrumbs(path: String, rootLabel: String): List<Pair<String, String>> {
    val out = mutableListOf(rootLabel to "/")
    val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
    var acc = ""
    for (s in segs) {
        acc += "/$s"
        out.add(s to acc)
    }
    return out
}

internal fun parent(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

internal fun child(path: String, name: String): String =
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
