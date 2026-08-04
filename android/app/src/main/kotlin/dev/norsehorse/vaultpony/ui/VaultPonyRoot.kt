package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.norsehorse.vaultpony.SessionRegistry
import uniffi.vault_ffi.VaultSession

/** App shell: pick → unlock → browse. Read-only for P3 (doc §12). */
@Composable
fun VaultPonyRoot(
    pickedContainer: Uri?,
    onPickContainer: () -> Unit,
) {
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var creating by remember { mutableStateOf(false) }
    // A container we just created flows back here so it can be opened with the
    // password just set; it takes precedence over the externally-picked one.
    var created by remember { mutableStateOf<Uri?>(null) }
    val container = created ?: pickedContainer

    // Auto-lock: when the app is backgrounded/screen-off, SessionRegistry
    // locks every session and bumps this counter — drop our reference so the
    // unlock screen (and biometric re-prompt) comes back.
    val lockGeneration by SessionRegistry.lockGeneration.collectAsState()
    LaunchedEffect(lockGeneration) {
        if (lockGeneration > 0) session = null
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val current = session
                when {
                    current != null -> BrowserScreen(
                        session = current,
                        onLock = {
                            current.lock()
                            session = null
                        },
                    )
                    creating -> CreateContainerScreen(
                        onCreated = { uri ->
                            created = uri
                            creating = false
                        },
                        onCancel = { creating = false },
                    )
                    else -> UnlockScreen(
                        container = container,
                        onPickContainer = onPickContainer,
                        onCreateNew = { creating = true },
                        onUnlocked = { session = it },
                    )
                }
            }
        }
    }
}
