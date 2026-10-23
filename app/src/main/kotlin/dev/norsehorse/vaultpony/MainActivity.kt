package dev.norsehorse.vaultpony

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import dev.norsehorse.vaultpony.i18n.ProvideAppLocale
import dev.norsehorse.vaultpony.ui.VaultPonyRoot

/**
 * Files or text another app shared with "Save to VaultPony" (ACTION_SEND /
 * ACTION_SEND_MULTIPLE, plan §5.1). [uris] are content:// documents the
 * receiving activity holds a read grant for; [text] is a text-only share,
 * saved as "Shared text.txt".
 */
data class ShareRequest(val uris: List<Uri>, val text: String?) {
    /** How many files the share will save. */
    val count: Int get() = if (uris.isNotEmpty()) uris.size else 1

    companion object {
        /** The share carried by [intent], or null when it isn't one or holds
         *  nothing VaultPony can save. Only content:// documents are taken,
         *  never file:// paths. */
        fun from(intent: Intent?): ShareRequest? {
            val i = intent ?: return null
            val action = i.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            val found = LinkedHashSet<Uri>()
            if (action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { found += it }
            } else {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { found += it }
            }
            i.clipData?.let { clip ->
                for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let { found += it }
            }
            val uris = found.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotEmpty() }
            if (uris.isEmpty() && text == null) return null
            return ShareRequest(uris, if (uris.isEmpty()) text else null)
        }
    }
}

// FragmentActivity (a subclass of ComponentActivity, so Compose/setContent and
// the ActivityResult APIs are unaffected) is required by androidx BiometricPrompt.
class MainActivity : FragmentActivity() {

    private val pickedContainer = mutableStateOf<Uri?>(null)

    // A share arriving through the "Save to VaultPony" alias. This activity
    // runs in the sharing app's task and holds the shared files' read grant
    // for as long as it is open, so it stays open until the import ends.
    private val share = mutableStateOf<ShareRequest?>(null)

    // This instance was started by a share (the alias, in the sharing app's
    // task), so it closes when the share is done. A share delivered to the
    // user's own VaultPony window (onNewIntent) only clears and stays.
    private var launchedForShare = false

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
        // FLAG_SECURE (blank screenshots + Recents thumbnail, doc §8/§11) is
        // default-on but user-configurable in Settings. Re-applied on resume.
        applySecure()

        // Container arriving via open-with / share.
        if (intent?.action == Intent.ACTION_VIEW) {
            pickedContainer.value = intent.data
        }
        share.value = ShareRequest.from(intent)
        launchedForShare = savedInstanceState?.getBoolean(KEY_LAUNCHED_FOR_SHARE)
            ?: (share.value != null)

        setContent {
            ProvideAppLocale {
                VaultPonyRoot(
                    pickedContainer = pickedContainer.value,
                    onPickContainer = {
                        SessionRegistry.expectPicker()
                        openContainer.launch(arrayOf("*/*"))
                    },
                    onContainerConsumed = { pickedContainer.value = null },
                    share = share.value,
                    onShareFinished = {
                        share.value = null
                        if (launchedForShare) {
                            // Back to the app that shared.
                            finish()
                        } else {
                            // Stay; a recreation must not bring the share back.
                            setIntent(Intent(Intent.ACTION_MAIN))
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val incoming = ShareRequest.from(intent) ?: return
        // singleTop: a new share delivered to this instance replaces one
        // still waiting for a folder. While a copy runs it is ignored, so it
        // can't take over the batch on screen.
        if (TransferJobs.busy) return
        setIntent(intent)
        share.value = incoming
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED_FOR_SHARE, launchedForShare)
    }

    private companion object {
        const val KEY_LAUNCHED_FOR_SHARE = "launched_for_share"
    }

    override fun onResume() {
        super.onResume()
        applySecure()
        // A picker closed within ProcessLifecycleOwner's 700 ms stop delay
        // never consumed its auto-lock exemption; drop it here so the next
        // real trip to the background still locks.
        SessionRegistry.ignoreNextBackground = false
    }

    private fun applySecure() {
        if (AppPrefs.secureScreen(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Auto-lock is app-wide (VaultPonyApp's ProcessLifecycle observer).
}
