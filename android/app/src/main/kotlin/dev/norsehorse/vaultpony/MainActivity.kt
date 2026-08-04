package dev.norsehorse.vaultpony

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import dev.norsehorse.vaultpony.ui.VaultPonyRoot

// FragmentActivity (a subclass of ComponentActivity, so Compose/setContent and
// the ActivityResult APIs are unaffected) is required by androidx BiometricPrompt.
class MainActivity : FragmentActivity() {

    private val pickedContainer = mutableStateOf<Uri?>(null)

    // SAF picker with "*/*": containers frequently have no extension, so no
    // MIME filtering (the PGPony picker lesson, doc §8).
    private val openContainer =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Persist read+write so reopening is one tap and the volume can
                // be modified; fall back to read-only if write wasn't granted.
                val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, rw) }
                    .onFailure {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                pickedContainer.value = uri
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE default-on for unlock and browser surfaces (doc §8,
        // the Issue #8 gap). The settings toggle to relax it comes with the
        // settings screen — until then, always on. Also blanks the recents
        // thumbnail, which the deniability-adjacent posture wants anyway
        // (doc §11).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Container arriving via open-with / share.
        if (intent?.action == Intent.ACTION_VIEW) {
            pickedContainer.value = intent.data
        }

        setContent {
            VaultPonyRoot(
                pickedContainer = pickedContainer.value,
                onPickContainer = {
                    SessionRegistry.expectPicker()
                    openContainer.launch(arrayOf("*/*"))
                },
            )
        }
    }
    // Auto-lock is app-wide, not activity-scoped: VaultPonyApp registers a
    // screen-off receiver that drops every session (doc §8). Nothing to do
    // per-activity here.
}
