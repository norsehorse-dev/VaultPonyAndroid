package dev.norsehorse.vaultpony.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.ui.components.SectionLabel

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rememberOn by remember { mutableStateOf(AppPrefs.rememberVaults(context)) }
    var secure by remember { mutableStateOf(AppPrefs.secureScreen(context)) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionLabel("Privacy", Modifier.padding(top = 10.dp, bottom = 4.dp))
            SettingSwitch(
                title = "Remember my vaults",
                subtitle = "Show a home list of vaults you've opened. Stores their locations on this device.",
                checked = rememberOn,
            ) {
                rememberOn = it
                AppPrefs.setRememberVaults(context, it)
            }

            SectionLabel("Security", Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingSwitch(
                title = "Hide screen contents",
                subtitle = "Block screenshots and hide the app in Recents (FLAG_SECURE).",
                checked = secure,
            ) {
                secure = it
                AppPrefs.setSecureScreen(context, it)
                applySecure(context, it)
            }
            SettingStatic("Auto-lock", "Locks on leaving the app or screen-off")
        }
    }
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
    val window = (context as? Activity)?.window ?: return
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
