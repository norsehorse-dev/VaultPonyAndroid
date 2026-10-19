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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.TransferJobs
import dev.norsehorse.vaultpony.AppPrefs
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.VaultRepository
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
    // The open vault's registry id survives activity recreation (rotation);
    // the session itself lives in SessionRegistry, so the browser, and any
    // bulk copy running in it, come back instead of dropping to home while
    // the vault stays unlocked behind it.
    var openVolumeId by rememberSaveable { mutableStateOf<String?>(null) }
    var session by remember {
        mutableStateOf<VaultSession?>(openVolumeId?.let { SessionRegistry.get(it) })
    }
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

    // Auto-lock: drop the session and return home when a lock closed one.
    // Compared against the generation at first composition so a recreated
    // activity doesn't treat an old lock as new.
    val lockGeneration by SessionRegistry.lockGeneration.collectAsState()
    val baseGeneration = remember { SessionRegistry.lockGeneration.value }
    LaunchedEffect(lockGeneration) {
        if (lockGeneration != baseGeneration) {
            session = null
            openVolumeId = null
            screen = Screen.Home
        }
    }
    // Re-checked whenever this recomposes (a lockGeneration change does it),
    // so a session locked while backgrounded never draws a frame, even before
    // the effect above clears it.
    val visibleSession = session?.takeIf { SessionRegistry.isOpen(it) }
    // A batch summary whose vault isn't on screen any more (backgrounding
    // stopped the batch and the vault locked): show it wherever the user is.
    // The browser shows its own session's batch itself.
    val transferState by TransferJobs.state.collectAsState()
    val orphanSummary = (transferState as? TransferJobs.State.Done)
        ?.takeIf { TransferJobs.owner !== visibleSession }
        ?.result

    VaultPonyTheme {
        if (orphanSummary != null) {
            TransferSummaryDialog(
                orphanSummary,
                fallbackError = stringResource(
                    if (orphanSummary.export) R.string.browser_err_export else R.string.browser_err_import,
                ),
            )
        }
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val s = visibleSession
                if (s != null) {
                    BrowserScreen(
                        session = s,
                        onLock = {
                            // Through the registry, so the Files root goes too.
                            openVolumeId?.let { SessionRegistry.lock(it) }
                            s.lock()
                            session = null
                            openVolumeId = null
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
                                // An unlock that finished in the background past
                                // the auto-lock deadline was locked on arrival.
                                if (SessionRegistry.isOpen(unlocked)) {
                                    openVolumeId = VaultRepository.volumeId(sc.uri)
                                    session = unlocked
                                } else {
                                    screen = Screen.Home
                                }
                            },
                            onBack = { screen = Screen.Home },
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
