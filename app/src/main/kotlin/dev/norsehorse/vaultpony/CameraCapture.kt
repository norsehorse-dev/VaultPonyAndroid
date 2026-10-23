package dev.norsehorse.vaultpony

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uniffi.vault_ffi.VaultSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Camera capture into the vault (2.0.0 plan 5.6, D10, D11), the part that
 * touches the vault. Nothing here goes to the gallery, MediaStore, the app's
 * cache or any other file outside the vault:
 *
 * - Photos arrive as JPEG bytes in memory (CameraX OnImageCapturedCallback)
 *   and are written into the vault.
 * - Video is written by CameraX's muxer into a proxy file descriptor whose
 *   reads and writes go straight to a file in the vault ([openVideo]).
 *
 * Both write to a ".vaultpony-<token>.part" temp in the destination folder,
 * then `replace` it to the final name, as bulk import does, so a crash never
 * leaves a half file under the real name. A lock while a capture writes
 * removes its temp first ([discardFor], called by SessionRegistry).
 */
object CameraCapture {

    /** Where captures are written: process-wide, so a photo or video still
     *  lands in the vault after the camera screen closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val CHUNK = 1 shl 20

    // Space left free in the vault while recording, so a full vault ends the
    // recording cleanly instead of failing a write.
    private const val VIDEO_RESERVE = 4L shl 20

    /** A temp file a capture is writing right now. [discarded]: a lock
     *  removed it, so it must not be renamed into place. */
    class Temp internal constructor(val session: VaultSession, val path: String) {
        @Volatile
        var discarded = false
            internal set
    }

    private val active = ArrayList<Temp>()

    private fun register(session: VaultSession, path: String): Temp {
        val t = Temp(session, path)
        synchronized(active) { active += t }
        return t
    }

    private fun unregister(t: Temp) {
        synchronized(active) { active.remove(t) }
    }

    /** The vaults in [locked] are about to lock: remove every capture temp
     *  going into them while they can still be written. Called by
     *  SessionRegistry under its lock, before the keys go. */
    fun discardFor(locked: Collection<VaultSession>) {
        val hit = synchronized(active) {
            val h = active.filter { t -> locked.any { it === t.session } }
            active.removeAll(h.toSet())
            h
        }
        for (t in hit) {
            t.discarded = true
            runCatching { t.session.remove(t.path) }
            runCatching { t.session.flush() }
        }
    }

    /** "IMG_yyyyMMdd_HHmmss.jpg" in local time. */
    fun photoName(at: Date): String = "IMG_" + stamp(at) + ".jpg"

    /** "VID_yyyyMMdd_HHmmss.mp4" in local time. */
    fun videoName(at: Date): String = "VID_" + stamp(at) + ".mp4"

    private fun stamp(at: Date): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at)

    private fun tempPath(dir: String): String =
        ProviderPaths.child(dir, BulkPlanner.tempName(UUID.randomUUID().toString().replace("-", "").take(12)))

    /** [name] in [dir], or "name (1).ext" and so on when taken (D6). */
    private fun freeName(session: VaultSession, dir: String, name: String): String =
        ProviderNames.unique(name, isDir = false, existing = session.list(dir).map { it.name })

    /**
     * JPEG bytes ready for the vault: [jpeg] as the camera gave it, or, when
     * the picture needs turning ([rotationDegrees] not 0), decoded, rotated
     * and encoded again at quality 95 (which also drops the camera's EXIF).
     * If the device can't hold the decoded picture, the unrotated original is
     * kept rather than losing the photo.
     */
    fun uprightJpeg(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
        if (rotationDegrees % 360 == 0) return jpeg
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            val out = ByteArrayOutputStream(jpeg.size)
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            rotated.recycle()
            out.toByteArray()
        } catch (e: OutOfMemoryError) {
            jpeg
        }
    }

    /** Write a photo into the vault folder [dir] under "IMG_<[at]>.jpg"
     *  (keep-both on a clash) and return the name it got. Blocking: call it
     *  off the main thread. */
    fun savePhoto(session: VaultSession, dir: String, jpeg: ByteArray, at: Date): String {
        val temp = register(session, tempPath(dir))
        try {
            session.create(temp.path)
            var offset = 0
            while (offset < jpeg.size) {
                val n = minOf(CHUNK, jpeg.size - offset)
                val chunk = jpeg.copyOfRange(offset, offset + n)
                val written = session.writeAt(temp.path, offset.toULong(), chunk).toInt()
                if (written != n) throw IOException("short write")
                offset += n
            }
            if (temp.discarded) throw IOException("vault locked")
            val name = freeName(session, dir, photoName(at))
            session.replace(temp.path, ProviderPaths.child(dir, name))
            return name
        } catch (e: Exception) {
            if (!temp.discarded) {
                runCatching { session.remove(temp.path) }
                runCatching { session.flush() }
            }
            throw e
        } finally {
            unregister(temp)
        }
    }

    /**
     * Open a recording's output: an empty temp file in the vault folder [dir]
     * and a read-write proxy file descriptor over it for CameraX's muxer (it
     * seeks back to finish the file, which this descriptor supports). Every
     * read, write and fsync on the descriptor runs on the sink's own thread
     * through the session. Blocking: call it off the main thread.
     */
    fun openVideo(context: Context, session: VaultSession, dir: String, started: Date): VideoSink {
        val temp = register(session, tempPath(dir))
        var thread: HandlerThread? = null
        try {
            session.create(temp.path)
            session.flush()
            val facts = session.facts()
            // The recording stops cleanly at FAT's 4 GB limit or when the
            // vault is nearly full, rather than on a failed write.
            val free = runCatching { session.space().freeBytes.toLong() }.getOrNull()
            val limit = listOfNotNull(facts.maxFileBytes?.toLong(), free?.let { it - VIDEO_RESERVE })
                .minOrNull()
                ?.takeIf { it > 0 }
            val t = HandlerThread("VaultPony camera").also { it.start() }
            thread = t
            val storage = context.getSystemService(StorageManager::class.java)
                ?: throw IOException("storage service unavailable")
            val callback = VideoFdCallback(session, temp.path, t)
            val pfd = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_WRITE,
                callback,
                Handler(t.looper),
            )
            return VideoSink(session, dir, temp, pfd, started, limit)
        } catch (e: Exception) {
            thread?.quitSafely()
            if (!temp.discarded) {
                runCatching { session.remove(temp.path) }
                runCatching { session.flush() }
            }
            unregister(temp)
            throw e
        }
    }

    /**
     * End a recording. [keep]: the muxer finished the file. The descriptor
     * is closed first; then the temp is renamed to "VID_<started>.mp4"
     * (keep-both on a clash) and that name returned, or, when not kept or
     * the vault locked meanwhile, the temp is removed and null returned.
     * Blocking: call it off the main thread.
     */
    fun finishVideo(sink: VideoSink, keep: Boolean): String? {
        runCatching { sink.pfd.close() }
        val session = sink.session
        val temp = sink.temp
        try {
            if (temp.discarded || !SessionRegistry.isOpen(session)) return null
            if (!keep) {
                runCatching { session.remove(temp.path) }
                runCatching { session.flush() }
                return null
            }
            return try {
                val name = freeName(session, sink.dir, videoName(sink.started))
                session.replace(temp.path, ProviderPaths.child(sink.dir, name))
                name
            } catch (e: Exception) {
                CoreErrorMap.log("camera video", e)
                runCatching { session.remove(temp.path) }
                runCatching { session.flush() }
                null
            }
        } finally {
            unregister(temp)
        }
    }

    /** One recording's way into the vault. [sizeLimit]: give the recorder
     *  this as its file size limit, when set. */
    class VideoSink internal constructor(
        val session: VaultSession,
        val dir: String,
        internal val temp: Temp,
        val pfd: ParcelFileDescriptor,
        val started: Date,
        val sizeLimit: Long?,
    )

    /**
     * The muxer's file, as a proxy descriptor sees it. Runs on [thread]. Every
     * failure, including the vault locking underneath it, becomes an errno:
     * an exception escaping a callback would take the app down.
     */
    private class VideoFdCallback(
        private val session: VaultSession,
        private val path: String,
        private val thread: HandlerThread,
    ) : ProxyFileDescriptorCallback() {
        // Tracked here, so the size never needs a core call.
        private var length = 0L

        override fun onGetSize(): Long = length

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (size <= 0 || offset >= length) return 0
            val chunk = try {
                session.readAt(path, offset.toULong(), size.toUInt())
            } catch (e: Throwable) {
                throw ErrnoException("read", OsConstants.EIO)
            }
            val n = minOf(chunk.size, size, data.size)
            chunk.copyInto(data, 0, 0, n)
            return n
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            if (size <= 0) return 0
            val bytes = if (size == data.size) data else data.copyOf(size)
            val written = try {
                session.writeAt(path, offset.toULong(), bytes).toInt()
            } catch (e: Throwable) {
                CoreErrorMap.log("camera write", e)
                val errno = if (CoreErrorMap.kindOf(e) == VaultErrorKind.FULL) OsConstants.ENOSPC else OsConstants.EIO
                throw ErrnoException("write", errno)
            }
            if (offset + written > length) length = offset + written
            return written
        }

        override fun onFsync() {
            try {
                session.flush()
            } catch (e: Throwable) {
                throw ErrnoException("fsync", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            runCatching { session.flush() }
            thread.quitSafely()
        }
    }
}
