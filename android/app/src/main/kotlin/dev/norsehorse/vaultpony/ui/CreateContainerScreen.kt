package dev.norsehorse.vaultpony.ui

import android.net.Uri
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.ChipTone
import dev.norsehorse.vaultpony.ui.components.MonoChip
import dev.norsehorse.vaultpony.ui.components.SectionLabel
import dev.norsehorse.vaultpony.ui.components.VaultSeal
import kotlinx.coroutines.launch

private data class SizeChoice(val label: String, val bytes: ULong)

private val SIZES = listOf(
    SizeChoice("16 MB", 16uL * 1024uL * 1024uL),
    SizeChoice("64 MB", 64uL * 1024uL * 1024uL),
    SizeChoice("256 MB", 256uL * 1024uL * 1024uL),
    SizeChoice("1 GB", 1024uL * 1024uL * 1024uL),
)

/**
 * Create a new AES/SHA-512 container with an empty FAT filesystem. The user
 * sets a password and size; the SAF "create document" picker chooses where it
 * lands. On success the new file flows back into the unlock screen so it can
 * be opened with the password just set (doc §7).
 */
@Composable
fun CreateContainerScreen(
    onCreated: (Uri) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var sizeIndex by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                repo.createContainer(uri, SIZES[sizeIndex].bytes, password)
                onCreated(uri)
            } catch (e: Exception) {
                error = e.message ?: "could not create container"
                busy = false
            }
        }
    }

    val passwordsOk = password.isNotEmpty() && password == confirm

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        VaultSeal()
        Text("New vault", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoChip("AES", ChipTone.Accent)
            MonoChip("SHA-512")
            MonoChip("FAT")
        }
        Spacer(Modifier.height(4.dp))

        SectionLabel("Password", Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != password,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Size", Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIZES.forEachIndexed { i, choice ->
                FilterChip(
                    selected = i == sizeIndex,
                    onClick = { sizeIndex = i },
                    label = { Text(choice.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (busy) {
            CircularProgressIndicator()
            Text(
                "Creating and formatting…",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(
                onClick = {
                    busy = true
                    error = null
                    SessionRegistry.expectPicker()
                    createDoc.launch("vault.vc")
                },
                enabled = passwordsOk,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose location & create") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
