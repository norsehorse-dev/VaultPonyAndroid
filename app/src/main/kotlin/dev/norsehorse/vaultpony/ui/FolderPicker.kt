package dev.norsehorse.vaultpony.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.BulkPlanner
import dev.norsehorse.vaultpony.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.VaultSession

/**
 * Pick a folder inside an unlocked vault: the browser's Move ("Move to",
 * "Move here") and share into a vault ("Choose a folder", "Save here"),
 * plan §5.3 and §5.1. Lists folders only. [isBlocked]: folders that can be
 * neither entered nor chosen (a folder being moved, and everything below
 * it). [canPick]: whether the folder on screen can be chosen. [header]: an
 * optional line under the title.
 */
@Composable
internal fun FolderPickerScreen(
    session: VaultSession,
    title: String,
    confirmLabel: String,
    header: String?,
    startPath: String,
    isBlocked: (String) -> Boolean = { false },
    canPick: (String) -> Boolean = { true },
    enabled: Boolean = true,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val res = LocalContext.current.resources
    val rootLabel = stringResource(R.string.browser_title)
    val errListing = stringResource(R.string.browser_err_listing)
    var dir by rememberSaveable(startPath) { mutableStateOf(startPath) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session, dir) {
        try {
            folders = withContext(Dispatchers.IO) {
                session.list(dir)
                    .filter { it.isDir && !BulkPlanner.isTempName(it.name) }
                    .map { it.name }
                    .sortedBy { it.lowercase() }
            }
            error = null
        } catch (e: Exception) {
            folders = emptyList()
            error = vaultFailureText(res, e, errListing, where = "list")
        }
    }

    BackHandler { if (dir != "/") dir = parent(dir) else onCancel() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = breadcrumbs(dir, rootLabel)
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
                        .clickable { dir = full }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(folders, key = { it }) { name ->
                val target = child(dir, name)
                val blocked = isBlocked(target)
                val tint = if (blocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else MaterialTheme.colorScheme.primary
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !blocked) { dir = target }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        name,
                        modifier = Modifier.weight(1f),
                        color = if (blocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!blocked) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_cancel))
            }
            Button(
                onClick = { onPick(dir) },
                enabled = enabled && error == null && canPick(dir) && !isBlocked(dir),
                modifier = Modifier.weight(1f),
            ) { Text(confirmLabel) }
        }
    }
}
