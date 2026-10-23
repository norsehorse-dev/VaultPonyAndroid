package dev.norsehorse.vaultpony.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultListMode
import dev.norsehorse.vaultpony.VaultNotifications
import dev.norsehorse.vaultpony.i18n.LanguageState
import dev.norsehorse.vaultpony.i18n.LocaleManager
import dev.norsehorse.vaultpony.i18n.SupportedLanguage
import dev.norsehorse.vaultpony.i18n.findActivity
import dev.norsehorse.vaultpony.ui.components.SectionLabel

private data class PonyApp(val name: String, val subtitleRes: Int, val url: String)

// Every pony app except VaultPony itself. BurnPony and PassPony are included
// here even though BurnPony's own list omits BurnPony (it is the host there).
private val PONY_APPS = listOf(
    PonyApp("PGPony", R.string.pony_pgpony_subtitle, "https://pgpony.app"),
    PonyApp("PassPony", R.string.pony_passpony_subtitle, "https://passpony.app"),
    PonyApp("BurnPony", R.string.pony_burnpony_subtitle, "https://burnpony.app"),
    PonyApp("CarrierPony", R.string.pony_carrierpony_subtitle, "https://carrierpony.com"),
    PonyApp("AgePony", R.string.pony_agepony_subtitle, "https://agepony.com"),
    PonyApp("RelayPony", R.string.pony_relaypony_subtitle, "https://relaypony.app"),
    PonyApp("QuorumPony", R.string.pony_quorumpony_subtitle, "https://quorumpony.com"),
)

private const val WEBSITE_URL = "https://vaultpony.app"
private const val PONY_FAMILY_URL = "https://pony.norsehor.se"
private const val SOURCE_APP_URL = "https://github.com/norsehorse-dev/VaultPonyAndroid"
private const val SOURCE_CORE_URL = "https://github.com/norsehorse-dev/VaultPonyCore"
private const val CONTACT_URL = "mailto:NorseHorse@norsehor.se"

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayOnboarding: () -> Unit) {
    val context = LocalContext.current
    var listMode by remember { mutableStateOf(AppPrefs.vaultListMode(context)) }
    var confirmDontKeep by remember { mutableStateOf(false) }
    var secure by remember { mutableStateOf(AppPrefs.secureScreen(context)) }
    var autoLock by remember { mutableLongStateOf(AppPrefs.autoLockDelayMs(context)) }
    var confirmUntilLocked by remember { mutableStateOf(false) }
    // "Until I lock it" needs the unlocked notification (D15). Re-read every
    // time the app comes back, since the user may have just changed it in
    // system settings; a saved "Until I lock it" reads as 1 hour without it.
    val foregrounds by SessionRegistry.foregrounds.collectAsState()
    var notificationsOn by remember { mutableStateOf(VaultNotifications.unlockedAllowed(context)) }
    LaunchedEffect(foregrounds) {
        notificationsOn = VaultNotifications.unlockedAllowed(context)
        autoLock = AppPrefs.autoLockDelayMs(context)
    }
    // A delay longer than Immediately is when the "Vault unlocked"
    // notification starts to matter: explain and ask once (R2).
    val notifGate = rememberNotificationGate()
    val currentLang by LanguageState.current

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionLabel(
                stringResource(R.string.settings_privacy_header),
                Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            VaultListPicker(selected = listMode) { mode ->
                if (mode == VaultListMode.NONE) {
                    // Deletes every entry: ask first.
                    if (listMode != VaultListMode.NONE) confirmDontKeep = true
                } else {
                    listMode = mode
                    AppPrefs.setVaultListMode(context, mode)
                }
            }

            SectionLabel(
                stringResource(R.string.settings_security_header),
                Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            SettingSwitch(
                title = stringResource(R.string.settings_secure_title),
                subtitle = stringResource(R.string.settings_secure_subtitle),
                checked = secure,
            ) {
                secure = it
                AppPrefs.setSecureScreen(context, it)
                applySecure(context, it)
            }
            AutoLockPicker(selected = autoLock, untilLockedAvailable = notificationsOn) {
                if (it == AppPrefs.AUTO_LOCK_UNTIL_LOCKED) {
                    if (autoLock != it) confirmUntilLocked = true
                } else {
                    autoLock = it
                    AppPrefs.setAutoLockDelayMs(context, it)
                    // The permission prompt doesn't send the app to the
                    // background, so re-read the answer here too.
                    if (it > 0L) notifGate.runAfter { notificationsOn = VaultNotifications.unlockedAllowed(context) }
                }
            }

            // Live language switcher: tapping applies immediately (no restart).
            SectionLabel(
                stringResource(R.string.settings_language_header),
                Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            for (lang in SupportedLanguage.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { LocaleManager.setLanguage(context, lang) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.nativeName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (lang.tag == currentLang) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            SectionLabel(
                stringResource(R.string.settings_pony_header),
                Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            for (app in PONY_APPS) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(app.url) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(app.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel(
                stringResource(R.string.settings_links_header),
                Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            LinkRow(stringResource(R.string.settings_link_website)) { open(WEBSITE_URL) }
            LinkRow(stringResource(R.string.settings_link_pony_family)) { open(PONY_FAMILY_URL) }
            LinkRow(stringResource(R.string.settings_link_source_app)) { open(SOURCE_APP_URL) }
            LinkRow(stringResource(R.string.settings_link_source_core)) { open(SOURCE_CORE_URL) }
            LinkRow(stringResource(R.string.settings_link_contact)) { open(CONTACT_URL) }

            SectionLabel(
                stringResource(R.string.settings_help_header),
                Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            LinkRow(stringResource(R.string.settings_replay_onboarding)) { onReplayOnboarding() }

            val version = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0"
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    version ?: "1.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.settings_family_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )
        }
    }

    if (confirmDontKeep) {
        AlertDialog(
            onDismissRequest = { confirmDontKeep = false },
            title = { Text(stringResource(R.string.vault_list_remove_title)) },
            text = { Text(stringResource(R.string.vault_list_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDontKeep = false
                    listMode = VaultListMode.NONE
                    // Unlocked vaults keep their file permission until they lock.
                    AppPrefs.setVaultListMode(context, VaultListMode.NONE, SessionRegistry.openUris())
                }) { Text(stringResource(R.string.vault_list_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDontKeep = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmUntilLocked) {
        AlertDialog(
            onDismissRequest = { confirmUntilLocked = false },
            title = { Text(stringResource(R.string.autolock_until_confirm_title)) },
            text = { Text(stringResource(R.string.autolock_until_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUntilLocked = false
                    AppPrefs.setAutoLockDelayMs(context, AppPrefs.AUTO_LOCK_UNTIL_LOCKED)
                    autoLock = AppPrefs.autoLockDelayMs(context)
                }) { Text(stringResource(R.string.autolock_until_confirm_keep)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUntilLocked = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Vault list (plan §5.4): Show names (default), Hide names, Don't keep. */
@Composable
private fun VaultListPicker(selected: VaultListMode, onSelect: (VaultListMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.settings_vault_list_title), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup()) {
            listOf(
                VaultListMode.SHOW to R.string.settings_vault_list_show,
                VaultListMode.HIDE to R.string.settings_vault_list_hide,
                VaultListMode.NONE to R.string.settings_vault_list_none,
            ).forEach { (mode, label) ->
                RadioRow(
                    label = stringResource(label),
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                )
            }
        }
        when (selected) {
            VaultListMode.HIDE -> Text(
                stringResource(R.string.settings_vault_list_hide_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VaultListMode.NONE -> Text(
                stringResource(R.string.home_no_trace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VaultListMode.SHOW -> Unit
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    note: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// Labels for AppPrefs.AUTO_LOCK_DELAYS_MS, same order.
private val AUTO_LOCK_LABELS = listOf(
    R.string.settings_autolock_immediately,
    R.string.settings_autolock_30s,
    R.string.settings_autolock_1m,
    R.string.settings_autolock_5m,
    R.string.settings_autolock_15m,
    R.string.settings_autolock_1h,
    R.string.settings_autolock_until_locked,
)

/** Auto-lock delay (plan §2.3): a radio list, with the memory trade-off
 *  stated right under it. */
@Composable
private fun AutoLockPicker(selected: Long, untilLockedAvailable: Boolean, onSelect: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.settings_autolock_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_autolock_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup()) {
            AppPrefs.AUTO_LOCK_DELAYS_MS.forEachIndexed { i, ms ->
                // "Until I lock it" only while its notification can show (D15).
                val until = ms == AppPrefs.AUTO_LOCK_UNTIL_LOCKED
                val enabled = !until || untilLockedAvailable
                RadioRow(
                    label = stringResource(AUTO_LOCK_LABELS[i]),
                    selected = ms == selected,
                    onClick = { onSelect(ms) },
                    enabled = enabled,
                    note = if (enabled) null else stringResource(R.string.settings_autolock_until_needs_notifications),
                )
            }
        }
        Text(
            stringResource(R.string.settings_autolock_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun applySecure(context: Context, secure: Boolean) {
    val window = context.findActivity()?.window ?: return
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
