package dev.norsehorse.vaultpony.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.theme.VaultPonyTheme
import uniffi.vault_ffi.VaultSession

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Create : Screen
    data class Unlock(val uri: Uri) : Screen
}

/** App shell: home → unlock → browse, plus create and settings. */
@Composable
fun VaultPonyRoot(
    pickedContainer: Uri?,
    onPickContainer: () -> Unit,
    onContainerConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // A container opened via the SAF picker or an intent goes straight to
    // unlock; consume it so re-opening the same file routes again.
    LaunchedEffect(pickedContainer) {
        if (pickedContainer != null) {
            screen = Screen.Unlock(pickedContainer)
            onContainerConsumed()
        }
    }

    // Auto-lock: drop the session and return home when the app is backgrounded.
    val lockGeneration by SessionRegistry.lockGeneration.collectAsState()
    LaunchedEffect(lockGeneration) {
        if (lockGeneration > 0) {
            session = null
            screen = Screen.Home
        }
    }

    VaultPonyTheme {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val s = session
                if (s != null) {
                    BrowserScreen(
                        session = s,
                        onLock = {
                            s.lock()
                            session = null
                            screen = Screen.Home
                        },
                    )
                } else {
                    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                    when (val sc = screen) {
                        Screen.Home -> HomeScreen(
                            onOpenVault = { uri -> screen = Screen.Unlock(uri) },
                            onPickContainer = onPickContainer,
                            onCreateNew = { screen = Screen.Create },
                            onSettings = { screen = Screen.Settings },
                        )
                        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                        Screen.Create -> CreateContainerScreen(
                            onCreated = { uri -> screen = Screen.Unlock(uri) },
                            onCancel = { screen = Screen.Home },
                        )
                        is Screen.Unlock -> UnlockScreen(
                            container = sc.uri,
                            onPickContainer = onPickContainer,
                            onCreateNew = { screen = Screen.Create },
                            onUnlocked = { unlocked ->
                                VaultStore.add(context, sc.uri)
                                session = unlocked
                            },
                        )
                    }
                }
            }
        }
    }
}
