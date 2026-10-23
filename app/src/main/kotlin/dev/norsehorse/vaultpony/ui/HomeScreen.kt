package dev.norsehorse.vaultpony.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.VaultListMode
import dev.norsehorse.vaultpony.VaultRef
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.components.SectionLabel

/** [shareNote]: shown at the top while files shared from another app wait
 *  for a vault to be unlocked (plan §5.1). */
@Composable
fun HomeScreen(
    onOpenVault: (Uri) -> Unit,
    onPickContainer: () -> Unit,
    onCreateNew: () -> Unit,
    onSettings: () -> Unit,
    shareNote: String? = null,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val mode = AppPrefs.vaultListMode(context)
    val rememberOn = mode != VaultListMode.NONE
    val vaults = remember(refresh) { if (rememberOn) VaultStore.list(context) else emptyList() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (shareNote != null) {
                item {
                    Text(
                        shareNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }
            if (rememberOn && vaults.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.home_your_vaults), Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                itemsIndexed(vaults, key = { _, ref -> ref.uri }) { index, ref ->
                    // "Hide names" stores none: "Vault 1", "Vault 2"... in
                    // list order. An entry saved under it keeps no name after
                    // switching back to "Show names" until it is opened again.
                    val numbered = stringResource(R.string.home_vault_numbered, index + 1)
                    val title = if (mode == VaultListMode.HIDE) numbered else ref.name.ifBlank { numbered }
                    VaultCard(
                        ref = ref,
                        title = title,
                        onOpen = { onOpenVault(Uri.parse(ref.uri)) },
                        onForget = {
                            VaultStore.remove(context, ref.uri)
                            refresh++
                        },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.home_add), Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item {
                AddCard(
                    icon = Icons.Filled.Add,
                    title = stringResource(R.string.home_new_vault_title),
                    subtitle = stringResource(R.string.home_new_vault_subtitle),
                    accent = true,
                    onClick = onCreateNew,
                )
            }
            item {
                AddCard(
                    icon = Icons.Filled.FolderOpen,
                    title = stringResource(R.string.home_open_title),
                    subtitle = stringResource(R.string.home_open_subtitle),
                    accent = false,
                    onClick = onPickContainer,
                )
            }

            if (!rememberOn) {
                item {
                    Text(
                        stringResource(R.string.home_no_trace),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun VaultCard(ref: VaultRef, title: String, onOpen: () -> Unit, onForget: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(cs.surfaceContainer, RoundedCornerShape(16.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(cs.surface, RoundedCornerShape(12.dp))
                .border(1.dp, cs.outline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            val opened = if (ref.lastOpened > 0) relative(ref.lastOpened)
            else stringResource(R.string.home_recently)
            Text(
                stringResource(R.string.home_opened_relative, opened),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        IconButton(onClick = onForget) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_forget), tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddCard(icon: ImageVector, title: String, subtitle: String, accent: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                if (accent) cs.primary.copy(alpha = 0.10f) else cs.surfaceContainer,
                RoundedCornerShape(16.dp),
            )
            .border(
                1.dp,
                if (accent) cs.primary.copy(alpha = 0.45f) else cs.outline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(cs.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (accent) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (accent) cs.primary else cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

private fun relative(ts: Long): String {
    if (ts <= 0) return "recently"
    return DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
