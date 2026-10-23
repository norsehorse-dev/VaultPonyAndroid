package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.ui.components.RevealToggle
import dev.norsehorse.vaultpony.ui.components.passwordAutofill
import dev.norsehorse.vaultpony.ui.components.revealTransformation
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Change a container's password/PIM in place (doc §6). The master keys, and
 * therefore all the data, are untouched; only the header key is re-derived
 * from the new password. The current password is verified before anything is
 * written, so a wrong entry changes nothing.
 *
 * Keyfiles (plan §5.5): the current ones open the header, the new ones are
 * folded into the new password. They are separate lists, as in VeraCrypt:
 * keeping the same keyfiles means adding them again under New keyfiles, and
 * an empty New keyfiles list takes keyfiles off the vault.
 */
@Composable
fun ChangePasswordScreen(
    container: Uri,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var currentShown by remember { mutableStateOf(false) }
    var currentPim by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var nextShown by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    var confirmShown by remember { mutableStateOf(false) }
    var nextPim by remember { mutableStateOf("") }
    var currentKeyfiles by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var newKeyfiles by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    // Target KDF: "keep" (null) or a core registry name. Switching clears the
    // new PIM so the new KDF's own default applies, as VeraCrypt does.
    val keepLabel = stringResource(R.string.change_pw_kdf_keep)
    val kdfOptions = remember { runCatching { repo.hashes() }.getOrDefault(emptyList()) }
    var newKdf by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    val successMsg = stringResource(R.string.change_pw_success)
    val errorMsg = stringResource(R.string.change_pw_error)

    // Same picker as the unlock screen: several documents, each read up to
    // VeraCrypt's 1 MiB keyfile limit, held in memory only.
    val currentKeyfilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }.getOrNull()
                    }
                }
                currentKeyfiles.forEach { it.fill(0) }
                currentKeyfiles = read
            }
        }
    }
    val newKeyfilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }.getOrNull()
                    }
                }
                newKeyfiles.forEach { it.fill(0) }
                newKeyfiles = read
            }
        }
    }
    // Keyfile bytes never outlive the screen.
    DisposableEffect(Unit) {
        onDispose {
            currentKeyfiles.forEach { it.fill(0) }
            newKeyfiles.forEach { it.fill(0) }
        }
    }

    val keyfilesChange = currentKeyfiles.size != newKeyfiles.size ||
        currentKeyfiles.zip(newKeyfiles).any { (a, b) -> !a.contentEquals(b) }
    val sameSecret = next == current && !keyfilesChange
    val ready = (current.isNotEmpty() || currentKeyfiles.isNotEmpty()) &&
        (next.isNotEmpty() || newKeyfiles.isNotEmpty()) &&
        next == confirm && !sameSecret

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // While the header is being rewritten nothing may leave the screen
        // (leaving would wipe state the running change still reads).
        BackHandler(enabled = busy) {}
        TextButton(onClick = onBack, enabled = !busy) { Text("‹ " + stringResource(R.string.common_back)) }
        Text(stringResource(R.string.change_pw_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.change_pw_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(stringResource(R.string.change_pw_current_header), Modifier.fillMaxWidth())
        OutlinedTextField(
            value = current,
            onValueChange = { current = it },
            label = { Text(stringResource(R.string.change_pw_current_label)) },
            singleLine = true,
            visualTransformation = revealTransformation(currentShown),
            trailingIcon = { RevealToggle(currentShown) { currentShown = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().passwordAutofill(),
        )
        OutlinedTextField(
            value = currentPim,
            onValueChange = { currentPim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.change_pw_current_pim)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        KeyfilesRow(
            label = stringResource(R.string.change_pw_current_keyfiles),
            count = currentKeyfiles.size,
            enabled = !busy,
            onPick = {
                SessionRegistry.expectPicker()
                currentKeyfilePicker.launch(arrayOf("*/*"))
            },
            onClear = {
                currentKeyfiles.forEach { it.fill(0) }
                currentKeyfiles = emptyList()
            },
        )

        SectionLabel(stringResource(R.string.change_pw_new_header), Modifier.fillMaxWidth())
        OutlinedTextField(
            value = next,
            onValueChange = { next = it },
            label = { Text(stringResource(R.string.change_pw_new_label)) },
            singleLine = true,
            visualTransformation = revealTransformation(nextShown),
            trailingIcon = { RevealToggle(nextShown) { nextShown = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().passwordAutofill(new = true),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.change_pw_confirm_label)) },
            singleLine = true,
            visualTransformation = revealTransformation(confirmShown),
            trailingIcon = { RevealToggle(confirmShown) { confirmShown = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != next,
            modifier = Modifier.fillMaxWidth().passwordAutofill(new = true),
        )
        OutlinedTextField(
            value = nextPim,
            onValueChange = { nextPim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.change_pw_new_pim)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        KeyfilesRow(
            label = stringResource(R.string.change_pw_new_keyfiles),
            count = newKeyfiles.size,
            enabled = !busy,
            onPick = {
                SessionRegistry.expectPicker()
                newKeyfilePicker.launch(arrayOf("*/*"))
            },
            onClear = {
                newKeyfiles.forEach { it.fill(0) }
                newKeyfiles = emptyList()
            },
        )
        Text(
            stringResource(R.string.change_pw_keyfiles_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (kdfOptions.isNotEmpty()) {
            ChoiceDropdown(
                stringResource(R.string.change_pw_kdf_label),
                listOf(keepLabel) + kdfOptions,
                newKdf ?: keepLabel,
            ) { picked ->
                val target = picked.takeUnless { it == keepLabel }
                if (target != newKdf) nextPim = ""
                newKdf = target
            }
            if (newKdf != null) {
                Text(
                    stringResource(R.string.change_pw_kdf_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (next.isNotEmpty() && sameSecret) {
            Text(
                stringResource(R.string.change_pw_differ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(4.dp))
        if (busy) {
            CircularProgressIndicator()
            Text(stringResource(R.string.change_pw_working), style = MaterialTheme.typography.bodySmall)
        } else if (done) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_done))
            }
        } else {
            Button(
                onClick = {
                    busy = true
                    message = null
                    // The call works on copies: the screen's own arrays can be
                    // wiped (Clear, a new pick, leaving) while it runs.
                    val oldKf = currentKeyfiles.map { it.copyOf() }
                    val newKf = newKeyfiles.map { it.copyOf() }
                    val targetKdf = newKdf
                    scope.launch {
                        try {
                            repo.changePassword(
                                container,
                                current,
                                currentPim.toUIntOrNull() ?: 0u,
                                next,
                                nextPim.toUIntOrNull() ?: 0u,
                                newPrf = targetKdf,
                                oldKeyfiles = oldKf,
                                newKeyfiles = newKf,
                            )
                            current = ""; next = ""; confirm = ""
                            currentKeyfiles.forEach { it.fill(0) }
                            newKeyfiles.forEach { it.fill(0) }
                            currentKeyfiles = emptyList()
                            newKeyfiles = emptyList()
                            // The header now uses another hash: a hash
                            // remembered for the unlock menu no longer fits.
                            if (targetKdf != null) VaultStore.setHash(context, container, null)
                            message = successMsg
                            isError = false
                            done = true
                        } catch (e: Exception) {
                            message = vaultErrorText(context, e, errorMsg)
                            isError = true
                        } finally {
                            oldKf.forEach { it.fill(0) }
                            newKf.forEach { it.fill(0) }
                        }
                        busy = false
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.change_pw_title)) }
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

/** "Current keyfiles" / "New keyfiles": the count ("None", "2 selected") as
 *  a button that opens the document picker, and a Clear button once some are
 *  chosen. */
@Composable
private fun KeyfilesRow(label: String, count: Int, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPick, enabled = enabled) {
            Text(
                if (count == 0) stringResource(R.string.change_pw_keyfiles_none)
                else pluralStringResource(R.plurals.change_pw_keyfiles_selected, count, count),
            )
        }
    }
    if (count > 0) {
        TextButton(onClick = onClear, enabled = enabled) { Text(stringResource(R.string.unlock_clear_keyfiles)) }
    }
}
