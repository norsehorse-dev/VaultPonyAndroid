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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.R
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
    var rememberOn by remember { mutableStateOf(AppPrefs.rememberVaults(context)) }
    var secure by remember { mutableStateOf(AppPrefs.secureScreen(context)) }
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
            SettingSwitch(
                title = stringResource(R.string.settings_remember_title),
                subtitle = stringResource(R.string.settings_remember_subtitle),
                checked = rememberOn,
            ) {
                rememberOn = it
                AppPrefs.setRememberVaults(context, it)
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
            SettingStatic(
                stringResource(R.string.settings_autolock_title),
                stringResource(R.string.settings_autolock_subtitle),
            )

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

@Composable
private fun SettingStatic(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
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
