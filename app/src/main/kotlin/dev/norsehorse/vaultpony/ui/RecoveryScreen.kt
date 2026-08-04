package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Header backup and recovery for one container (doc §6). Backup exports the
 * 128 KiB header group to a file; restore rewrites the primary header from the
 * container's own embedded backup or from an exported file, always after
 * verifying the password, so a wrong password writes nothing.
 */
@Composable
fun RecoveryScreen(
    container: Uri,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var pim by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // Strings used inside non-composable callbacks are resolved up front.
    val opFailed = stringResource(R.string.common_op_failed)
    val msgBackupSaved = stringResource(R.string.recovery_msg_backup_saved)
    val msgRestoredFile = stringResource(R.string.recovery_msg_restored_file)
    val msgRestoredEmbedded = stringResource(R.string.recovery_msg_restored_embedded)

    fun run(label: String, block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
                message = label
                isError = false
            } catch (e: Exception) {
                message = e.message ?: opFailed
                isError = true
            }
            busy = false
        }
    }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { dest ->
        if (dest == null) return@rememberLauncherForActivityResult
        run(msgBackupSaved) { repo.saveHeaderBackup(container, dest) }
    }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { src ->
        if (src == null) return@rememberLauncherForActivityResult
        val pimV = pim.toUIntOrNull() ?: 0u
        run(msgRestoredFile) {
            repo.restoreHeaderFromFile(container, src, password, pimV, keyfiles)
        }
    }

    // Same keyfiles the container was created with; folded into the password by
    // the core exactly as unlock does, so recovery matches the vault's real key.
    val keyfilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
                    }
                }
                keyfiles = read
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.common_back)) }
        Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.recovery_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(stringResource(R.string.recovery_backup_header), Modifier.fillMaxWidth())
        Button(
            onClick = {
                SessionRegistry.expectPicker()
                saveBackup.launch("vaultpony-header.vpbak")
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.recovery_save_backup)) }
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.recovery_backup_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        SectionLabel(stringResource(R.string.recovery_restore_header), Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.recovery_restore_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.recovery_vault_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pim,
            onValueChange = { pim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.recovery_pim_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            SessionRegistry.expectPicker()
            keyfilePicker.launch(arrayOf("*/*"))
        }) {
            Text(
                if (keyfiles.isEmpty()) stringResource(R.string.unlock_add_keyfiles)
                else stringResource(R.string.unlock_keyfiles_count, keyfiles.size),
            )
        }
        if (keyfiles.isNotEmpty()) {
            TextButton(onClick = {
                keyfiles.forEach { it.fill(0) }
                keyfiles = emptyList()
            }) { Text(stringResource(R.string.unlock_clear_keyfiles)) }
        }
        OutlinedButton(
            onClick = {
                val pimV = pim.toUIntOrNull() ?: 0u
                run(msgRestoredEmbedded) {
                    repo.restoreHeaderFromEmbedded(container, password, pimV, keyfiles)
                }
            },
            enabled = !busy && (password.isNotEmpty() || keyfiles.isNotEmpty()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.recovery_restore_embedded)) }
        OutlinedButton(
            onClick = {
                SessionRegistry.expectPicker()
                pickBackup.launch(arrayOf("*/*"))
            },
            enabled = !busy && (password.isNotEmpty() || keyfiles.isNotEmpty()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.recovery_restore_file)) }

        if (busy) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator()
        }
        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
