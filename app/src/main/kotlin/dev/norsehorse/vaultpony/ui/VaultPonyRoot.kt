package dev.norsehorse.vaultpony.ui

import android.content.Intent
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
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultStore
import dev.norsehorse.vaultpony.ui.theme.VaultPonyTheme
import uniffi.vault_ffi.VaultSession

private sealed interface Screen {
    data class Onboarding(val startPage: Int = 0) : Screen
    data object Home : Screen
    data object Settings : Screen
    data class Create(val fromOnboarding: Boolean = false) : Screen
    data class VaultCreated(val uri: Uri, val fromOnboarding: Boolean) : Screen
    data class Unlock(val uri: Uri) : Screen
    data class Recovery(val uri: Uri) : Screen
    data class ChangePassword(val uri: Uri) : Screen
}

// Index of the "Create your first vault" slide in the onboarding carousel;
// after creating we return the user to the slide after it so they can finish.
private const val ONBOARDING_CREATE_SLIDE = 2

/** App shell: home → unlock → browse, plus create and settings. */
@Composable
fun VaultPonyRoot(
    pickedContainer: Uri?,
    onPickContainer: () -> Unit,
    onContainerConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var screen by remember {
        mutableStateOf<Screen>(
            if (AppPrefs.onboardingDone(context)) Screen.Home else Screen.Onboarding(),
        )
    }

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
                    BackHandler(
                        enabled = screen != Screen.Home && screen !is Screen.Onboarding,
                    ) { screen = Screen.Home }
                    when (val sc = screen) {
                        is Screen.Onboarding -> OnboardingScreen(
                            startPage = sc.startPage,
                            onCreateVault = { screen = Screen.Create(fromOnboarding = true) },
                            onFinish = {
                                AppPrefs.setOnboardingDone(context, true)
                                screen = Screen.Home
                            },
                        )
                        Screen.Home -> HomeScreen(
                            onOpenVault = { uri -> screen = Screen.Unlock(uri) },
                            onPickContainer = onPickContainer,
                            onCreateNew = { screen = Screen.Create() },
                            onSettings = { screen = Screen.Settings },
                        )
                        Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            onReplayOnboarding = { screen = Screen.Onboarding() },
                        )
                        is Screen.Create -> CreateContainerScreen(
                            onCreated = { uri ->
                                // Persist read+write so the home entry can reopen
                                // the new vault after a restart (the create-document
                                // grant is otherwise only good for this process).
                                val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(uri, rw)
                                }.onFailure {
                                    runCatching {
                                        context.contentResolver.takePersistableUriPermission(
                                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                }
                                // Remember it now so it shows on home even if the
                                // user taps Done instead of opening it. Respects
                                // no-trace mode (VaultStore.add gates on the pref).
                                VaultStore.add(context, uri)
                                // Always land on the "how to open it" screen first.
                                screen = Screen.VaultCreated(uri, sc.fromOnboarding)
                            },
                            onCancel = {
                                screen = if (sc.fromOnboarding) {
                                    Screen.Onboarding(startPage = ONBOARDING_CREATE_SLIDE)
                                } else {
                                    Screen.Home
                                }
                            },
                        )
                        is Screen.VaultCreated -> VaultCreatedScreen(
                            continueSetup = sc.fromOnboarding,
                            onOpen = {
                                if (sc.fromOnboarding) AppPrefs.setOnboardingDone(context, true)
                                screen = Screen.Unlock(sc.uri)
                            },
                            onDone = {
                                screen = if (sc.fromOnboarding) {
                                    // Back to onboarding, past the create slide, to finish.
                                    Screen.Onboarding(startPage = ONBOARDING_CREATE_SLIDE + 1)
                                } else {
                                    Screen.Home
                                }
                            },
                        )
                        is Screen.Unlock -> UnlockScreen(
                            container = sc.uri,
                            onPickContainer = onPickContainer,
                            onCreateNew = { screen = Screen.Create() },
                            onRecovery = { screen = Screen.Recovery(sc.uri) },
                            onChangePassword = { screen = Screen.ChangePassword(sc.uri) },
                            onUnlocked = { unlocked ->
                                VaultStore.add(context, sc.uri)
                                session = unlocked
                            },
                        )
                        is Screen.Recovery -> RecoveryScreen(
                            container = sc.uri,
                            onBack = { screen = Screen.Unlock(sc.uri) },
                        )
                        is Screen.ChangePassword -> ChangePasswordScreen(
                            container = sc.uri,
                            onBack = { screen = Screen.Unlock(sc.uri) },
                        )
                    }
                }
            }
        }
    }
}
