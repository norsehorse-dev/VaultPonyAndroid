package dev.norsehorse.vaultpony

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class VaultPonyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply the saved UI language (or detect the device one) before any
        // Composable mounts, so the first frame is already localized.
        dev.norsehorse.vaultpony.i18n.LanguageState.init(this)

        // Sessions live here so the DocumentsProvider (a separate entry
        // point) can reach volumes the activity unlocked.
        SessionRegistry.init(this)

        // Auto-lock (doc §8, plan §2.3): when the whole app leaves the
        // foreground (backgrounded, recents, or screen-off) every session is
        // locked, at once or after the delay chosen in Settings, and the UI is
        // sent back home. In-app SAF pickers briefly background the app too,
        // so those are exempted via SessionRegistry.expectPicker(). onStart
        // catches a delayed lock whose timer was stalled by the freezer.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    SessionRegistry.onAppForegrounded()
                }

                override fun onStop(owner: LifecycleOwner) {
                    SessionRegistry.onAppBackgrounded()
                }
            },
        )
    }
}
