package dev.norsehorse.vaultpony.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.VaultPlayer
import kotlinx.coroutines.delay
import uniffi.vault_ffi.VaultSession

/**
 * Plays a video or audio file straight from the vault (plan §5.2, D22):
 * MediaPlayer over a MediaDataSource that reads only what the player asks
 * for. Video renders on a TextureView, which is part of the window, so the
 * "Hide screen contents" setting covers it. Controls: play/pause, a seek
 * slider, elapsed and total time. The player is released when this leaves
 * the screen and at lock, and pauses when VaultPony goes to the background.
 */
@Composable
internal fun MediaPlayerPane(session: VaultSession, path: String, size: ULong, video: Boolean) {
    val player = remember(session, path) { VaultPlayer(session, path, size.toLong()) }
    var ready by remember(player) { mutableStateOf(false) }
    var failed by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var durationMs by remember(player) { mutableIntStateOf(0) }
    var positionMs by remember(player) { mutableIntStateOf(0) }
    var videoWidth by remember(player) { mutableIntStateOf(0) }
    var videoHeight by remember(player) { mutableIntStateOf(0) }
    // Only an explicit 0 x 0 size report means "no picture"; onPrepared can
    // arrive before the first size report of a real video.
    var audioOnly by remember(player) { mutableStateOf(false) }
    // While the slider is dragged it shows the drag, not the player.
    var dragging by remember(player) { mutableStateOf(false) }
    var dragMs by remember(player) { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        player.onPrepared = { d ->
            durationMs = d
            ready = true
            player.play()
            playing = player.isPlaying
        }
        player.onVideoSize = { w, h ->
            if (w == 0 && h == 0) {
                audioOnly = true
            } else {
                audioOnly = false
                videoWidth = w
                videoHeight = h
            }
        }
        player.onCompletion = {
            playing = false
            positionMs = durationMs
        }
        player.onError = {
            failed = true
            playing = false
        }
        player.prepare()
        // Leaving VaultPony pauses playback (no background audio from a vault).
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                playing = false
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.onPrepared = null
            player.onVideoSize = null
            player.onCompletion = null
            player.onError = null
            player.release()
        }
    }

    // Elapsed time while playing.
    LaunchedEffect(player, playing) {
        while (playing) {
            if (player.released) {
                playing = false
                break
            }
            if (!dragging) positionMs = player.positionMs
            delay(250)
        }
    }

    if (failed) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.viewer_cant_play),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A "video" file that turns out to hold only sound shows no picture.
        val showVideo = video && !audioOnly
        if (showVideo) {
            val ratio = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null

                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val s = Surface(st)
                                surface = s
                                player.setSurface(s)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                player.setSurface(null)
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.size(16.dp))
        val shownMs = if (dragging) dragMs.toInt() else positionMs
        Slider(
            value = shownMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = {
                dragging = true
                dragMs = it
            },
            onValueChangeFinished = {
                val target = dragMs.toInt()
                player.seekTo(target)
                positionMs = target
                dragging = false
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            enabled = ready && durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatMs(shownMs),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatMs(durationMs),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = {
                if (playing) {
                    player.pause()
                    playing = false
                } else {
                    // At the end, play from the start again.
                    if (durationMs > 0 && positionMs >= durationMs - 250) {
                        player.seekTo(0)
                        positionMs = 0
                    }
                    player.play()
                    playing = true
                }
            },
            enabled = ready,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.media_pause else R.string.media_play),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** "1:05" or "1:02:05". */
private fun formatMs(ms: Int): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
