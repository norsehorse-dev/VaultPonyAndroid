package dev.norsehorse.vaultpony

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Surface
import android.webkit.MimeTypeMap
import uniffi.vault_ffi.VaultSession
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/*
 * In-app video and audio playback (2.0.0 plan §5.2, D22). The platform
 * MediaPlayer reads the file through a MediaDataSource over the vault's
 * readAt: only the ranges the player asks for, no temp file, no extra
 * dependency, and no MediaSession, so nothing about the file reaches the
 * system's media controls or Recents. Every lock releases the players of the
 * vaults it closed (SessionRegistry).
 */

object MediaKinds {
    /** "video/..." or "audio/..." for a file name by its extension, else null. */
    fun mimeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return null
        return mime.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
    }

    fun isMedia(name: String): Boolean = mimeOf(name) != null

    fun isVideo(name: String): Boolean = mimeOf(name)?.startsWith("video/") == true
}

/** One vault file as MediaPlayer sees it. Reads on the player's own thread.
 *  The size comes from a stat when the player first asks (at open), not
 *  from the folder listing, which may be stale; [listedSize] is only the
 *  fallback if that stat fails. */
class VaultMediaDataSource(
    private val session: VaultSession,
    private val path: String,
    private val listedSize: Long,
) : MediaDataSource() {
    @Volatile
    private var closed = false
    private var checkedAt = -OPEN_CHECK_MS

    private val fileSize: Long by lazy {
        runCatching { session.stat(path).size.toLong() }.getOrDefault(listedSize)
    }

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("closed")
        if (length <= 0) return 0
        if (position >= fileSize) return -1
        // A lock ends playback at the next read, even before the player is
        // released on the main thread.
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt >= OPEN_CHECK_MS) {
            if (!SessionRegistry.isOpen(session)) throw IOException("vault locked")
            checkedAt = now
        }
        val want = minOf(length.toLong(), fileSize - position, MAX_READ).toInt()
        val chunk = try {
            session.readAt(path, position.toULong(), want.toUInt())
        } catch (e: Exception) {
            throw IOException("vault read failed")
        }
        if (chunk.isEmpty()) return -1
        chunk.copyInto(buffer, offset)
        val n = chunk.size
        chunk.fill(0)
        return n
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val OPEN_CHECK_MS = 250L
        const val MAX_READ = 1L shl 20
    }
}

/**
 * A MediaPlayer over one vault file, safe to poke after it was released (a
 * lock can release it from under the screen). Create, set the callbacks and
 * call [prepare] on the main thread; MediaPlayer then calls back there too.
 */
class VaultPlayer(val session: VaultSession, path: String, fileSize: Long) {
    private var player: MediaPlayer? = MediaPlayer()
    private val source = VaultMediaDataSource(session, path, fileSize)
    private var prepared = false

    var onPrepared: ((durationMs: Int) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    /** Released (by the screen or by a lock). */
    val released: Boolean get() = player == null

    fun prepare() {
        val p = player ?: return
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            p.setDataSource(source)
            p.setOnPreparedListener {
                prepared = true
                onPrepared?.invoke(runCatching { it.duration }.getOrDefault(0).coerceAtLeast(0))
            }
            p.setOnVideoSizeChangedListener { _, w, h -> onVideoSize?.invoke(w, h) }
            p.setOnCompletionListener { onCompletion?.invoke() }
            p.setOnErrorListener { _, _, _ ->
                onError?.invoke()
                true
            }
            p.prepareAsync()
            MediaPlayback.register(this)
        } catch (e: Exception) {
            onError?.invoke()
        }
    }

    fun play() {
        if (!prepared) return
        runCatching { player?.start() }
    }

    fun pause() {
        if (!prepared) return
        runCatching { player?.pause() }
    }

    val isPlaying: Boolean
        get() = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)

    val positionMs: Int
        get() = if (!prepared) 0 else runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun seekTo(ms: Int) {
        if (!prepared) return
        runCatching { player?.seekTo(ms) }
    }

    fun setSurface(surface: Surface?) {
        runCatching { player?.setSurface(surface) }
    }

    fun release() {
        val p = player ?: return
        player = null
        prepared = false
        MediaPlayback.unregister(this)
        runCatching { p.reset() }
        runCatching { p.release() }
        source.close()
    }
}

/** Players open right now, so a lock can release the ones on its vaults. */
object MediaPlayback {
    private val players: MutableSet<VaultPlayer> = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap()),
    )

    fun register(p: VaultPlayer) {
        players.add(p)
    }

    fun unregister(p: VaultPlayer) {
        players.remove(p)
    }

    /** Release every player reading from one of [sessions]. Main thread. */
    fun releaseFor(sessions: Collection<VaultSession>) {
        val hit = synchronized(players) { players.filter { p -> sessions.any { it === p.session } } }
        hit.forEach { it.release() }
    }
}
