package dev.norsehorse.vaultpony.ui

import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.SectionLabel
import kotlinx.coroutines.launch

/**
 * Change a container's password/PIM in place (doc §6). The master keys, and
 * therefore all the data, are untouched; only the header key is re-derived
 * from the new password. The current password is verified before anything is
 * written, so a wrong entry changes nothing.
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
    var currentPim by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var nextPim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    val successMsg = stringResource(R.string.change_pw_success)
    val errorMsg = stringResource(R.string.change_pw_error)

    val ready = current.isNotEmpty() && next.isNotEmpty() && next == confirm && next != current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.common_back)) }
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
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = currentPim,
            onValueChange = { currentPim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.change_pw_current_pim)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel(stringResource(R.string.change_pw_new_header), Modifier.fillMaxWidth())
        OutlinedTextField(
            value = next,
            onValueChange = { next = it },
            label = { Text(stringResource(R.string.change_pw_new_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.change_pw_confirm_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != next,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nextPim,
            onValueChange = { nextPim = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.change_pw_new_pim)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (next.isNotEmpty() && next == current) {
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
                    scope.launch {
                        try {
                            repo.changePassword(
                                container,
                                current,
                                currentPim.toUIntOrNull() ?: 0u,
                                next,
                                nextPim.toUIntOrNull() ?: 0u,
                            )
                            current = ""; next = ""; confirm = ""
                            message = successMsg
                            isError = false
                            done = true
                        } catch (e: Exception) {
                            message = e.message ?: errorMsg
                            isError = true
                        }
                        busy = false
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.change_pw_title)) }
        }
        Text(
            stringResource(R.string.change_pw_keyfile_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
