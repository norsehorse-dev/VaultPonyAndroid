package dev.norsehorse.vaultpony.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.ui.components.VaultSeal

/**
 * Success screen shown right after a container is created. It explains how to
 * open the new vault (it is not on the home list until the first open), with a
 * primary action to open it now and a secondary action to move on.
 */
@Composable
fun VaultCreatedScreen(
    continueSetup: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VaultSeal()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.vault_created_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.vault_created_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vault_created_open))
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (continueSetup) R.string.vault_created_continue
                    else R.string.vault_created_done,
                ),
            )
        }
    }
}
