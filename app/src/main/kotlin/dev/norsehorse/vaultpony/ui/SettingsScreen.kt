package dev.norsehorse.vaultpony.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    PonyApp("StegPony", R.string.pony_stegpony_subtitle, "https://stegpony.app"),
    PonyApp("TapPony", R.string.pony_tappony_subtitle, "https://tappony.app"),
    PonyApp("HashPony", R.string.pony_hashpony_subtitle, "https://hashpony.app"),
)

private const val WEBSITE_URL = "https://vaultpony.app"
private const val PONY_FAMILY_URL = "https://pony.norsehor.se"
private const val SOURCE_APP_URL = "https://github.com/norsehorse-dev/VaultPonyAndroid"
private const val SOURCE_CORE_URL = "https://github.com/norsehorse-dev/VaultPonyCore"
private const val CONTACT_URL = "mailto:NorseHorse@norsehor.se"

/** The pages under Settings. The header's title and back arrow follow the page. */
private enum class SettingsPage { MAIN, VAULT_LIST, AUTO_LOCK, LANGUAGE, ABOUT }

private val VAULT_LIST_LABELS = listOf(
    VaultListMode.SHOW to R.string.settings_vault_list_show,
    VaultListMode.HIDE to R.string.settings_vault_list_hide,
    VaultListMode.NONE to R.string.settings_vault_list_none,
)

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

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayOnboarding: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
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
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    // A sub-page goes back to the main page; the main page leaves Settings.
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (page == SettingsPage.MAIN) onBack() else page = SettingsPage.MAIN }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(
                    when (page) {
                        SettingsPage.MAIN -> R.string.settings_title
                        SettingsPage.VAULT_LIST -> R.string.settings_vault_list_title
                        SettingsPage.AUTO_LOCK -> R.string.settings_autolock_title
                        SettingsPage.LANGUAGE -> R.string.settings_language_header
                        SettingsPage.ABOUT -> R.string.settings_about
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }

        // Keyed by page so each page starts scrolled to its top.
        key(page) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                when (page) {
                    SettingsPage.MAIN -> {
                        NavRow(
                            title = stringResource(R.string.settings_vault_list_title),
                            value = stringResource(VAULT_LIST_LABELS.first { it.first == listMode }.second),
                        ) { page = SettingsPage.VAULT_LIST }
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_autolock_title),
                            value = AUTO_LOCK_LABELS.getOrNull(AppPrefs.AUTO_LOCK_DELAYS_MS.indexOf(autoLock))
                                ?.let { stringResource(it) },
                        ) { page = SettingsPage.AUTO_LOCK }
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_secure_title),
                            subtitle = stringResource(R.string.settings_secure_subtitle),
                            checked = secure,
                        ) {
                            secure = it
                            AppPrefs.setSecureScreen(context, it)
                            applySecure(context, it)
                        }
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_language_header),
                            value = (SupportedLanguage.resolve(currentLang) ?: SupportedLanguage.EN).nativeName,
                        ) { page = SettingsPage.LANGUAGE }
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_replay_onboarding),
                            value = null,
                        ) { onReplayOnboarding() }
                        RowDivider()
                        NavRow(
                            title = stringResource(R.string.settings_about),
                            value = if (version.isEmpty()) null else stringResource(R.string.settings_about_version, version),
                        ) { page = SettingsPage.ABOUT }
                    }

                    SettingsPage.VAULT_LIST -> VaultListPage(selected = listMode) { mode ->
                        if (mode == VaultListMode.NONE) {
                            // Deletes every entry: ask first.
                            if (listMode != VaultListMode.NONE) confirmDontKeep = true
                        } else {
                            listMode = mode
                            AppPrefs.setVaultListMode(context, mode)
                        }
                    }

                    SettingsPage.AUTO_LOCK -> AutoLockPage(
                        selected = autoLock,
                        untilLockedAvailable = notificationsOn,
                    ) {
                        if (it == AppPrefs.AUTO_LOCK_UNTIL_LOCKED) {
                            if (autoLock != it) confirmUntilLocked = true
                        } else {
                            autoLock = it
                            AppPrefs.setAutoLockDelayMs(context, it)
                            // The permission prompt doesn't send the app to the
                            // background, so re-read the answer here too.
                            if (it > 0L) {
                                notifGate.runAfter { notificationsOn = VaultNotifications.unlockedAllowed(context) }
                            }
                        }
                    }

                    // Tapping applies the language at once, in place.
                    SettingsPage.LANGUAGE -> LanguagePage(currentLang) { LocaleManager.setLanguage(context, it) }

                    SettingsPage.ABOUT -> AboutPage(version = version, open = { open(it) })
                }
            }
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
private fun VaultListPage(selected: VaultListMode, onSelect: (VaultListMode) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        VAULT_LIST_LABELS.forEachIndexed { i, (mode, label) ->
            if (i > 0) RowDivider()
            RadioRow(
                label = stringResource(label),
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
    when (selected) {
        VaultListMode.HIDE -> PageNote(stringResource(R.string.settings_vault_list_hide_note))
        VaultListMode.NONE -> PageNote(stringResource(R.string.home_no_trace))
        VaultListMode.SHOW -> Unit
    }
}

/** Auto-lock delay (plan §2.3): what it does, the choices, then the memory
 *  trade-off. */
@Composable
private fun AutoLockPage(selected: Long, untilLockedAvailable: Boolean, onSelect: (Long) -> Unit) {
    Text(
        stringResource(R.string.settings_autolock_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        AppPrefs.AUTO_LOCK_DELAYS_MS.forEachIndexed { i, ms ->
            if (i > 0) RowDivider()
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
    PageNote(stringResource(R.string.settings_autolock_note))
}

/** One row per shipped language, by its own name. */
@Composable
private fun LanguagePage(current: String, onSelect: (SupportedLanguage) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        SupportedLanguage.entries.forEachIndexed { i, lang ->
            if (i > 0) RowDivider()
            val selected = lang.tag == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(lang) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lang.nativeName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AboutPage(version: String, open: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_version),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            version,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.settings_family_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PageHeading(stringResource(R.string.settings_links_header))
    LinkRow(stringResource(R.string.settings_link_website), null) { open(WEBSITE_URL) }
    RowDivider()
    LinkRow(stringResource(R.string.settings_link_source_app), null) { open(SOURCE_APP_URL) }
    RowDivider()
    LinkRow(stringResource(R.string.settings_link_source_core), null) { open(SOURCE_CORE_URL) }
    RowDivider()
    LinkRow(stringResource(R.string.settings_link_contact), null) { open(CONTACT_URL) }

    PageHeading(stringResource(R.string.settings_pony_header))
    for (app in PONY_APPS) {
        LinkRow(app.name, stringResource(app.subtitleRes)) { open(app.url) }
        RowDivider()
    }
    LinkRow(stringResource(R.string.settings_link_pony_family), null) { open(PONY_FAMILY_URL) }
}

@Composable
private fun RowDivider() {
    HorizontalDivider()
}

/** A heading inside a page, above a group of rows. */
@Composable
private fun PageHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 32.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Explanation under a group of choices. */
@Composable
private fun PageNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/** Title, optional second line, trailing icon. The whole row is the target. */
@Composable
private fun IconRow(title: String, value: String?, trailing: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!value.isNullOrEmpty()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opens another page of the app; the second line is the current value. */
@Composable
private fun NavRow(title: String, value: String?, onClick: () -> Unit) {
    IconRow(title, value, Icons.Filled.ChevronRight, onClick)
}

/** Opens a browser or mail app. */
@Composable
private fun LinkRow(title: String, subtitle: String?, onClick: () -> Unit) {
    IconRow(title, subtitle, Icons.AutoMirrored.Filled.OpenInNew, onClick)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
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
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
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
