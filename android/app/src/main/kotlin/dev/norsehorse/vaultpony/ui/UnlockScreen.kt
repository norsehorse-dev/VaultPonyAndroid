package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.norsehorse.vaultpony.BiometricUnlock
import dev.norsehorse.vaultpony.UnlockProgress
import dev.norsehorse.vaultpony.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.VaultException
import uniffi.vault_ffi.VaultSession

/** VeraCrypt only mixes the first 1 MiB of each keyfile (the core caps this
 *  too); read no more than that so a giant file can't balloon the process. */
private const val KEYFILE_CAP = 1024 * 1024

@Composable
fun UnlockScreen(
    container: Uri?,
    onPickContainer: () -> Unit,
    onCreateNew: () -> Unit,
    onUnlocked: (VaultSession) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    val volumeId = container?.let { VaultRepository.volumeId(it) }
    val canBio = remember { BiometricUnlock.canUse(context) }

    var passphrase by remember { mutableStateOf("") }
    var pim by remember { mutableStateOf("") }
    var showPim by remember { mutableStateOf(false) }
    var keyfiles by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var enrollBio by remember { mutableStateOf(false) }
    var enrolled by remember(container) {
        mutableStateOf(volumeId != null && BiometricUnlock.hasEnrollment(context, volumeId))
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<UnlockProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Keyfile picker: multi-select, any type (keyfiles are often extensionless).
    // Bytes are read once here — no persisted permission needed.
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("VaultPony", style = MaterialTheme.typography.headlineMedium)

        if (container == null) {
            Button(onClick = onPickContainer) { Text("Open container…") }
            TextButton(onClick = onCreateNew) { Text("Create new container…") }
        } else {
            Text("Container selected", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onPickContainer) { Text("Choose a different file") }

            // Biometric fast-path: only when this container has an enrollment.
            if (enrolled && canBio && activity != null && volumeId != null && !busy) {
                Button(
                    onClick = {
                        error = null
                        busy = true
                        BiometricUnlock.retrieve(
                            activity, volumeId,
                            onSecret = { pimV, pass ->
                                scope.launch {
                                    try {
                                        val session = repo.unlock(
                                            uri = container,
                                            passphrase = pass,
                                            pim = pimV,
                                            onProgress = { progress = it },
                                        )
                                        onUnlocked(session)
                                    } catch (e: VaultException) {
                                        error = e.message ?: "unlock failed"
                                        busy = false
                                    }
                                }
                            },
                            onError = { msg ->
                                error = msg
                                busy = false
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock with biometrics") }
                TextButton(onClick = {
                    volumeId.let { BiometricUnlock.clear(context, it) }
                    enrolled = false
                }) { Text("Forget biometric unlock") }
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Keyfiles (doc §4): optional; a container may use password,
            // keyfiles, or both. Selection count only — never a filename.
            TextButton(onClick = {
                dev.norsehorse.vaultpony.SessionRegistry.expectPicker()
                keyfilePicker.launch(arrayOf("*/*"))
            }) {
                Text(
                    if (keyfiles.isEmpty()) "Add keyfiles (optional)"
                    else "${keyfiles.size} keyfile(s) selected — change",
                )
            }
            if (keyfiles.isNotEmpty()) {
                TextButton(onClick = {
                    keyfiles.forEach { it.fill(0) }
                    keyfiles = emptyList()
                    enrollBio = false
                }) { Text("Clear keyfiles") }
            }

            // PIM is an advanced field (doc §6): tucked away by default.
            if (showPim) {
                OutlinedTextField(
                    value = pim,
                    onValueChange = { pim = it.filter(Char::isDigit) },
                    label = { Text("PIM (advanced, usually empty)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { showPim = true }) { Text("Advanced: PIM") }
            }

            // Biometric enrollment offer — password-only (keyfiles keep their
            // "have" factor), and warned against for hidden/deniable volumes.
            if (canBio && activity != null && volumeId != null && !enrolled && keyfiles.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = enrollBio, onCheckedChange = { enrollBio = it })
                    Text(
                        "Enable biometric unlock on this device",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (enrollBio) {
                    Text(
                        "Stores this password on the device behind your biometrics. " +
                            "Don't enable this for a hidden volume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (busy) {
                CircularProgressIndicator()
                // Per-PRF progress so a wrong password on an old phone
                // doesn't look like a hang (doc §6).
                progress?.let {
                    Text(
                        "Trying ${it.prf} (${it.step + 1u}/${it.total})…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Button(
                    onClick = {
                        val pass = passphrase
                        val pimV = pim.toUIntOrNull() ?: 0u
                        val kf = keyfiles
                        val enrollNow =
                            enrollBio && kf.isEmpty() && activity != null && volumeId != null
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val session = repo.unlock(
                                    uri = container,
                                    passphrase = pass,
                                    pim = pimV,
                                    keyfiles = kf,
                                    onProgress = { progress = it },
                                )
                                passphrase = ""
                                keyfiles.forEach { it.fill(0) }
                                keyfiles = emptyList()
                                if (enrollNow) {
                                    // Enroll shows its own prompt; navigate from
                                    // its callback so we don't dismiss it.
                                    BiometricUnlock.enroll(activity!!, volumeId!!, pass, pimV) {
                                        onUnlocked(session)
                                    }
                                } else {
                                    onUnlocked(session)
                                }
                            } catch (e: VaultException) {
                                error = e.message ?: "unlock failed"
                                busy = false
                            }
                        }
                    },
                    // A container may be keyfile-only, so either input unlocks.
                    enabled = passphrase.isNotEmpty() || keyfiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock") }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Read at most [KEYFILE_CAP] bytes — matches VeraCrypt's per-keyfile limit. */
private fun readCapped(input: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (total < KEYFILE_CAP) {
        val n = input.read(chunk, 0, minOf(chunk.size, KEYFILE_CAP - total))
        if (n < 0) break
        out.write(chunk, 0, n)
        total += n
    }
    return out.toByteArray()
}
