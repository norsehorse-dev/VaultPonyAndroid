package dev.norsehorse.vaultpony

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class VaultPonyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Sessions live here so the DocumentsProvider (a separate entry
        // point) can reach volumes the activity unlocked.
        SessionRegistry.init(this)

        // Auto-lock (doc §8): the moment the whole app leaves the foreground —
        // backgrounded, recents, or screen-off — every session is locked and
        // the UI is sent back to unlock. In-app SAF pickers briefly background
        // the app too, so those are exempted via SessionRegistry.expectPicker().
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    SessionRegistry.onAppBackgrounded()
                }
            },
        )
    }
}
