package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.ui.components.ChipTone
import dev.norsehorse.vaultpony.ui.components.MonoChip
import dev.norsehorse.vaultpony.ui.components.SectionLabel
import dev.norsehorse.vaultpony.ui.components.VaultSeal
import kotlinx.coroutines.launch

/** A labelled dropdown backed by [options]; stable Material3 only (no
 *  experimental menu-anchor APIs). */
@Composable
private fun ChoiceDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionLabel(label, Modifier.fillMaxWidth())
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Compact unit selector (MB / GB / TB) for the custom size field. */
@Composable
private fun UnitDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SIZE_UNITS.forEach { u ->
                DropdownMenuItem(
                    text = { Text(u) },
                    onClick = {
                        onSelect(u)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class SizePreset(val label: String, val amount: String, val unit: String)

private val SIZE_PRESETS = listOf(
    SizePreset("256 MB", "256", "MB"),
    SizePreset("1 GB", "1", "GB"),
    SizePreset("16 GB", "16", "GB"),
    SizePreset("64 GB", "64", "GB"),
)

private val SIZE_UNITS = listOf("MB", "GB", "TB")

/** Bytes for [amount] of [unit], sector-aligned, or null if not a valid
 *  positive number. Accepts decimals (e.g. "1.5"). */
private fun sizeToBytes(amount: String, unit: String): ULong? {
    val v = amount.trim().toDoubleOrNull() ?: return null
    if (v <= 0.0) return null
    val mult = when (unit) {
        "MB" -> 1024.0 * 1024
        "GB" -> 1024.0 * 1024 * 1024
        "TB" -> 1024.0 * 1024 * 1024 * 1024
        else -> return null
    }
    val bytes = (v * mult).toLong()
    val aligned = bytes / 512L * 512L
    return if (aligned <= 0L) null else aligned.toULong()
}

/** Smallest vault the UI allows; the core enforces its own hard minimum too.
 *  Hidden vaults need extra room so the concealed volume has a usable slice. */
private val MIN_SIZE_BYTES = 1uL * 1024uL * 1024uL // 1 MB
private val MIN_HIDDEN_SIZE_BYTES = 4uL * 1024uL * 1024uL // 4 MB

private data class HiddenFraction(val label: String, val pct: Int)

private val HIDDEN_FRACTIONS = listOf(
    HiddenFraction("¼", 25),
    HiddenFraction("½", 50),
    HiddenFraction("¾", 75),
)

/** Hidden data-area size for [pct]% of an [outer]-byte container, sector
 *  aligned. Mirrors the core's carve: hidden data ends at outer minus 256 KiB,
 *  so it must leave at least 128 KiB of outer beyond that. */
private fun hiddenBytesFor(outer: ULong, pct: Int): ULong {
    val raw = outer / 100uL * pct.toULong()
    val aligned = raw - (raw % 512uL)
    val ceiling = if (outer > 393_216uL) outer - 393_216uL else 0uL
    return minOf(aligned, ceiling - (ceiling % 512uL))
}

/**
 * Create a new container with an empty filesystem. The user sets a password,
 * size, cipher, hash, and filesystem; the SAF "create document" picker chooses
 * where it lands. On success the new file flows into the created screen (doc §7).
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
    var sizeAmount by remember { mutableStateOf("1") }
    var sizeUnit by remember { mutableStateOf("GB") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val invalidSize = stringResource(R.string.create_invalid_size)
    val createError = stringResource(R.string.create_error)

    // Cipher/hash options come straight from the core so the picker can never
    // list something creation would reject.
    val schemes = remember { runCatching { repo.encryptionSchemes() }.getOrDefault(listOf("AES")) }
    val hashList = remember { runCatching { repo.hashes() }.getOrDefault(listOf("SHA-512")) }
    val filesystems = remember { runCatching { repo.filesystems() }.getOrDefault(listOf("FAT")) }
    var scheme by remember { mutableStateOf(schemes.firstOrNull() ?: "AES") }
    var hash by remember { mutableStateOf(if ("SHA-512" in hashList) "SHA-512" else hashList.firstOrNull() ?: "SHA-512") }
    var filesystem by remember { mutableStateOf(filesystems.firstOrNull() ?: "FAT") }

    var hiddenEnabled by remember { mutableStateOf(false) }
    var hiddenPassword by remember { mutableStateOf("") }
    var hiddenConfirm by remember { mutableStateOf("") }
    var hiddenFracIndex by remember { mutableStateOf(0) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        val bytes = sizeToBytes(sizeAmount, sizeUnit)
        if (bytes == null) {
            error = invalidSize
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                if (hiddenEnabled) {
                    repo.createHiddenContainer(
                        uri,
                        bytes,
                        password,
                        hiddenPassword,
                        hiddenBytesFor(bytes, HIDDEN_FRACTIONS[hiddenFracIndex].pct),
                        scheme,
                        hash,
                        filesystem,
                    )
                } else {
                    repo.createContainer(uri, bytes, password, scheme, hash, filesystem)
                }
                onCreated(uri)
            } catch (e: Exception) {
                error = e.message ?: createError
                busy = false
            }
        }
    }

    val sizeBytes = sizeToBytes(sizeAmount, sizeUnit)
    val sizeOk = sizeBytes != null && sizeBytes >= MIN_SIZE_BYTES &&
        (!hiddenEnabled || sizeBytes >= MIN_HIDDEN_SIZE_BYTES)
    val outerOk = password.isNotEmpty() && password == confirm
    val hiddenOk = !hiddenEnabled || (
        hiddenPassword.isNotEmpty() &&
            hiddenPassword == hiddenConfirm &&
            hiddenPassword != password
        )
    val passwordsOk = outerOk && hiddenOk && sizeOk

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        VaultSeal()
        Text(stringResource(R.string.home_new_vault_title), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoChip(scheme, ChipTone.Accent)
            MonoChip(hash)
            MonoChip(filesystem)
        }
        Spacer(Modifier.height(4.dp))

        SectionLabel(stringResource(R.string.unlock_password), Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.unlock_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.create_confirm_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != password,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel(stringResource(R.string.create_size), Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIZE_PRESETS.forEach { p ->
                FilterChip(
                    selected = sizeAmount == p.amount && sizeUnit == p.unit,
                    onClick = {
                        sizeAmount = p.amount
                        sizeUnit = p.unit
                    },
                    label = { Text(p.label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = sizeAmount,
                onValueChange = { sizeAmount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.create_custom_size)) },
                singleLine = true,
                isError = sizeAmount.isNotEmpty() && !sizeOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            UnitDropdown(sizeUnit) { sizeUnit = it }
        }
        if (sizeAmount.isNotEmpty() && !sizeOk) {
            Text(
                if (hiddenEnabled) stringResource(R.string.create_min_hidden)
                else stringResource(R.string.create_min),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // -- Encryption, hash, and filesystem. All sourced from the core so
        // the picker can never offer something creation would reject.
        ChoiceDropdown(stringResource(R.string.create_encryption), schemes, scheme) { scheme = it }
        ChoiceDropdown(stringResource(R.string.create_hash), hashList, hash) { hash = it }
        ChoiceDropdown(stringResource(R.string.create_filesystem), filesystems, filesystem) { filesystem = it }
        if (filesystem == "exFAT") {
            Text(
                stringResource(R.string.create_exfat_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))

        // -- Hidden volume (doc §9). A second, concealed volume inside the
        // first. The outer password reveals nothing about it.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.create_hidden_volume), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.create_hidden_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = hiddenEnabled, onCheckedChange = { hiddenEnabled = it })
        }

        if (hiddenEnabled) {
            SectionLabel(stringResource(R.string.create_hidden_password), Modifier.fillMaxWidth())
            OutlinedTextField(
                value = hiddenPassword,
                onValueChange = { hiddenPassword = it },
                label = { Text(stringResource(R.string.create_hidden_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = hiddenPassword.isNotEmpty() && hiddenPassword == password,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hiddenConfirm,
                onValueChange = { hiddenConfirm = it },
                label = { Text(stringResource(R.string.create_confirm_hidden)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = hiddenConfirm.isNotEmpty() && hiddenConfirm != hiddenPassword,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hiddenPassword.isNotEmpty() && hiddenPassword == password) {
                Text(
                    stringResource(R.string.create_hidden_differ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel(stringResource(R.string.create_hidden_size), Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HIDDEN_FRACTIONS.forEachIndexed { i, frac ->
                    FilterChip(
                        selected = i == hiddenFracIndex,
                        onClick = { hiddenFracIndex = i },
                        label = { Text(stringResource(R.string.create_fraction_of_vault, frac.label)) },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.create_hidden_warning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }

        if (busy) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.create_working),
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
            ) { Text(stringResource(R.string.create_action)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
