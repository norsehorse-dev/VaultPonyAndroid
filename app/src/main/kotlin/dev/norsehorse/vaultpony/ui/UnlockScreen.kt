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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.BiometricUnlock
import dev.norsehorse.vaultpony.MemoryCheck
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.ui.components.RevealToggle
import dev.norsehorse.vaultpony.ui.components.passwordAutofill
import dev.norsehorse.vaultpony.ui.components.revealTransformation
import dev.norsehorse.vaultpony.i18n.findActivity
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.UnlockProgress
import dev.norsehorse.vaultpony.VaultListMode
import dev.norsehorse.vaultpony.VaultRepository
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.components.VaultSeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.KdfFilter
import uniffi.vault_ffi.UnlockCancel
import uniffi.vault_ffi.VaultException
import uniffi.vault_ffi.VaultSession
import uniffi.vault_ffi.hashChoices
import uniffi.vault_ffi.kdfFilterMayUseArgon2

/** VeraCrypt only mixes the first 1 MiB of each keyfile (the core caps this
 *  too); read no more than that so a giant file can't balloon the process. */
private const val KEYFILE_CAP = 1024 * 1024

@Composable
fun UnlockScreen(
    container: Uri?,
    onPickContainer: () -> Unit,
    onCreateNew: () -> Unit,
    onRecovery: () -> Unit,
    onChangePassword: () -> Unit,
    onUnlocked: (VaultSession) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    val repo = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()

    val volumeId = container?.let { VaultRepository.volumeId(it) }
    val canBio = remember { BiometricUnlock.canUse(context) }
    // Under "Don't keep" a biometric unlock would be removed at the next
    // lock, so it isn't offered (plan §5.4).
    val keepsList = remember { AppPrefs.vaultListMode(context) != VaultListMode.NONE }
    val unlockFailed = stringResource(R.string.unlock_failed)

    var passphrase by remember { mutableStateOf("") }
    var passphraseShown by remember { mutableStateOf(false) }
    var protectHidden by remember { mutableStateOf(false) }
    var hiddenPassphrase by remember { mutableStateOf("") }
    var hiddenPassphraseShown by remember { mutableStateOf(false) }
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
    // Which hash to try (D16, D21): Auto (every hash, like VeraCrypt), one
    // PBKDF2 hash, or Argon2id. Starts on the hash remembered for this vault,
    // which is only ever one the user picked by hand. [hashPicked]: the
    // current value came from the menu, so a successful unlock remembers it
    // (or, for Auto, forgets the remembered one).
    var kdf by remember(container) {
        mutableStateOf(container?.let { VaultStore.rememberedHash(context, it) } ?: KdfFilter.AUTO)
    }
    var hashPicked by remember(container) { mutableStateOf(false) }
    var cancelHandle by remember { mutableStateOf<UnlockCancel?>(null) }
    var memoryWarning by remember { mutableStateOf<MemoryCheck?>(null) }

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

    /** Run the typed-password unlock with [kdfChoice]. Called straight from
     *  the button, or after the user answers the memory warning. */
    fun startUnlock(kdfChoice: KdfFilter) {
        val uri = container ?: return
        val pass = passphrase
        val hiddenPass = hiddenPassphrase
        val protect = protectHidden
        val pimV = pim.toUIntOrNull() ?: 0u
        val kf = keyfiles
        val enrollNow = !protect && keepsList &&
            enrollBio && kf.isEmpty() && activity != null && volumeId != null
        val rememberHash = hashPicked
        val handle = UnlockCancel()
        cancelHandle = handle
        memoryWarning = null
        busy = true
        error = null
        scope.launch {
            try {
                val session = if (protect) {
                    repo.unlockOuterProtected(
                        uri = uri,
                        outerPassphrase = pass,
                        hiddenPassphrase = hiddenPass,
                        pim = pimV,
                        kdf = kdfChoice,
                        cancel = handle,
                        onProgress = { progress = it },
                    )
                } else {
                    repo.unlock(
                        uri = uri,
                        passphrase = pass,
                        pim = pimV,
                        keyfiles = kf,
                        kdf = kdfChoice,
                        cancel = handle,
                        onProgress = { progress = it },
                    )
                }
                passphrase = ""
                hiddenPassphrase = ""
                keyfiles.forEach { it.fill(0) }
                keyfiles = emptyList()
                // Only a hash picked by hand, only after it opened the vault,
                // and never under "Don't keep" (VaultStore checks, D21).
                if (rememberHash) {
                    VaultStore.add(context, uri)
                    VaultStore.setHash(context, uri, kdfChoice)
                }
                if (enrollNow) {
                    // Remember which KDF family opened it, so the biometric
                    // unlock goes straight there next time.
                    val found = if (runCatching { session.facts().prf }.getOrNull() == "Argon2id") {
                        KdfFilter.ARGON2ID_ONLY
                    } else {
                        KdfFilter.PBKDF2_ONLY
                    }
                    BiometricUnlock.enroll(activity!!, volumeId!!, pass, pimV, found) {
                        onUnlocked(session)
                    }
                } else {
                    onUnlocked(session)
                }
            } catch (e: VaultException) {
                error = vaultErrorText(context, e, unlockFailed)
                busy = false
            } finally {
                // Not destroyed here: a Cancel tap from a frame that still
                // shows the button must not hit a freed handle. The UniFFI
                // cleaner frees it once nothing references it.
                cancelHandle = null
            }
        }
    }

    // While an unlock is running, system back cancels it instead of leaving:
    // leaving would abandon a search whose session could still open behind
    // the user's back.
    BackHandler(enabled = busy) { cancelHandle?.cancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!busy) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                Text("‹ " + stringResource(R.string.common_back))
            }
        }
        Spacer(Modifier.height(8.dp))
        VaultSeal()
        Text(
            if (container == null) stringResource(R.string.app_name) else stringResource(R.string.unlock_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            if (container == null) stringResource(R.string.unlock_subtitle_none)
            else stringResource(R.string.unlock_subtitle_sealed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        if (container == null) {
            Button(onClick = onPickContainer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unlock_open_container))
            }
            OutlinedButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unlock_create_new))
            }
        } else {
            // Biometric fast-path: only when this container has an enrollment.
            // Hidden-protection needs both passwords typed, so it's hidden then.
            if (enrolled && canBio && activity != null && volumeId != null && !busy && !protectHidden) {
                OutlinedButton(
                    onClick = {
                        error = null
                        busy = true
                        BiometricUnlock.retrieve(
                            activity, volumeId,
                            onSecret = { pimV, pass, kdfV ->
                                val handle = UnlockCancel()
                                cancelHandle = handle
                                scope.launch {
                                    try {
                                        val session = repo.unlock(
                                            uri = container,
                                            passphrase = pass,
                                            pim = pimV,
                                            kdf = kdfV,
                                            cancel = handle,
                                            onProgress = { progress = it },
                                        )
                                        onUnlocked(session)
                                    } catch (e: VaultException) {
                                        error = vaultErrorText(context, e, unlockFailed)
                                        busy = false
                                    } finally {
                                        cancelHandle = null
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
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.unlock_biometrics))
                }
                TextButton(onClick = {
                    BiometricUnlock.clear(context, volumeId)
                    enrolled = false
                }) { Text(stringResource(R.string.unlock_forget_biometrics)) }
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = {
                    Text(
                        if (protectHidden) stringResource(R.string.unlock_outer_password)
                        else stringResource(R.string.unlock_password),
                    )
                },
                singleLine = true,
                visualTransformation = revealTransformation(passphraseShown),
                trailingIcon = { RevealToggle(passphraseShown) { passphraseShown = it } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (protectHidden) ImeAction.Next else ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().passwordAutofill(),
            )
            // Shown for every container, hidden volume or not, so the screen
            // itself reveals nothing (plan §2.1).
            Text(
                stringResource(R.string.unlock_hidden_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // Protect a hidden volume: open the outer/decoy volume read-write
            // while shielding the hidden region, so adding files here can't
            // overwrite the hidden volume (doc §9).
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = protectHidden,
                        role = Role.Checkbox,
                        onValueChange = {
                            protectHidden = it
                            if (!it) hiddenPassphrase = ""
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = protectHidden, onCheckedChange = null)
                Text(
                    stringResource(R.string.unlock_protect_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (protectHidden) {
                OutlinedTextField(
                    value = hiddenPassphrase,
                    onValueChange = { hiddenPassphrase = it },
                    label = { Text(stringResource(R.string.unlock_hidden_password)) },
                    singleLine = true,
                    visualTransformation = revealTransformation(hiddenPassphraseShown),
                    trailingIcon = { RevealToggle(hiddenPassphraseShown) { hiddenPassphraseShown = it } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().passwordAutofill(),
                )
                Text(
                    stringResource(R.string.unlock_protect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (!protectHidden) {
                    TextButton(onClick = {
                        SessionRegistry.expectPicker()
                        keyfilePicker.launch(arrayOf("*/*"))
                    }) {
                        Text(
                            if (keyfiles.isEmpty()) stringResource(R.string.unlock_add_keyfiles)
                            else stringResource(R.string.unlock_keyfiles_count, keyfiles.size),
                        )
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                if (!showPim) {
                    TextButton(onClick = { showPim = true }) { Text(stringResource(R.string.unlock_pim_button)) }
                }
            }
            if (keyfiles.isNotEmpty()) {
                TextButton(onClick = {
                    keyfiles.forEach { it.fill(0) }
                    keyfiles = emptyList()
                    enrollBio = false
                }) { Text(stringResource(R.string.unlock_clear_keyfiles)) }
            }
            if (showPim) {
                OutlinedTextField(
                    value = pim,
                    onValueChange = { pim = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.unlock_pim_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HashMenu(
                selected = kdf,
                onSelect = {
                    kdf = it
                    hashPicked = true
                    // Picking Auto forgets a remembered hash right away; a
                    // single hash is only remembered once it opens the vault.
                    if (it == KdfFilter.AUTO) VaultStore.setHash(context, container, null)
                },
            )

            if (canBio && keepsList && activity != null && volumeId != null && !enrolled && keyfiles.isEmpty() && !protectHidden) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = enrollBio,
                            role = Role.Checkbox,
                            onValueChange = { enrollBio = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = enrollBio, onCheckedChange = null)
                    Text(
                        stringResource(R.string.unlock_enroll_bio),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (enrollBio) {
                    Text(
                        stringResource(R.string.unlock_enroll_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            if (busy) {
                CircularProgressIndicator()
                progress?.let {
                    Text(
                        stringResource(
                            R.string.unlock_progress,
                            it.prf,
                            (it.step + 1u).toInt(),
                            it.total.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cancelHandle?.let { handle ->
                    // Takes effect before the next key derivation (up to a few
                    // seconds for one Argon2id pass set).
                    OutlinedButton(onClick = { handle.cancel() }) {
                        Text(stringResource(R.string.unlock_cancel))
                    }
                }
            } else if (memoryWarning != null) {
                val w = memoryWarning!!
                Text(
                    stringResource(R.string.unlock_memory_warning, w.neededMib.toInt(), w.freeMib.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { startUnlock(kdf) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.unlock_memory_try))
                    }
                    Button(
                        onClick = {
                            // Not a hand pick: nothing is remembered from it.
                            kdf = KdfFilter.PBKDF2_ONLY
                            hashPicked = false
                            startUnlock(KdfFilter.PBKDF2_ONLY)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.unlock_memory_pbkdf2)) }
                }
            } else {
                Button(
                    onClick = {
                        error = null
                        memoryWarning = null
                        val pimV = pim.toUIntOrNull() ?: 0u
                        // Argon2id needs hundreds of MiB for a moment. Check
                        // before starting rather than let the system kill the
                        // app mid-unlock.
                        val check = if (mayUseArgon2(kdf)) repo.argon2MemoryCheck(pimV) else null
                        if (check != null && !check.enough) {
                            memoryWarning = check
                        } else {
                            startUnlock(kdf)
                        }
                    },
                    enabled = if (protectHidden) {
                        passphrase.isNotEmpty() && hiddenPassphrase.isNotEmpty()
                    } else {
                        passphrase.isNotEmpty() || keyfiles.isNotEmpty()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (protectHidden) stringResource(R.string.unlock_open_outer)
                        else stringResource(R.string.unlock_open_vault),
                    )
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (!busy) {
                TextButton(onClick = onChangePassword, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.change_pw_title))
                }
                TextButton(onClick = onRecovery, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock_recovery_link))
                }
            }
        }
    }
}

/** Whether an unlock under [filter] may run Argon2id, so free memory is
 *  checked first. Errs on the side of checking. */
private fun mayUseArgon2(filter: KdfFilter): Boolean =
    runCatching { kdfFilterMayUseArgon2(filter) }.getOrDefault(filter != KdfFilter.PBKDF2_ONLY)

/** The unlock screen's "Hash" menu (D16, D21): the core's list, Auto first,
 *  then VeraCrypt's PRF order and Argon2id. Only "Auto" is translated; the
 *  hash names are the same in every language. PBKDF2 only, which "Skip
 *  Argon2id" sets, isn't in the menu but still reads correctly. */
@Composable
private fun HashMenu(selected: KdfFilter, onSelect: (KdfFilter) -> Unit) {
    val choices = remember { runCatching { hashChoices() }.getOrDefault(emptyList()) }
    val labels = remember(choices) { choices.associate { it.filter to it.label } }
    val options = remember(choices) { choices.map { it.filter }.ifEmpty { listOf(KdfFilter.AUTO) } }
    val auto = stringResource(R.string.kdf_auto)
    val pbkdf2 = stringResource(R.string.kdf_pbkdf2)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceDropdown(
            label = stringResource(R.string.unlock_hash_label),
            options = options,
            selected = selected,
            display = { f ->
                when (f) {
                    KdfFilter.AUTO -> auto
                    KdfFilter.PBKDF2_ONLY -> pbkdf2
                    else -> labels[f] ?: f.name
                }
            },
            onSelect = onSelect,
        )
        Text(
            stringResource(R.string.unlock_hash_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Read at most [KEYFILE_CAP] bytes, matching VeraCrypt's per-keyfile limit.
 *  Shared with the recovery screen's keyfile picker. */
internal fun readCapped(input: java.io.InputStream): ByteArray {
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
