package dev.norsehorse.vaultpony

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
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
 * - Video is recorded by CameraX's muxer into an anonymous in-memory file
 *   (memfd, Android 11+), never a file on disk, and copied into the vault
 *   when the recording ends ([openVideo], [finishVideo]). An earlier design
 *   gave the muxer a proxy file descriptor over the vault file; Android's
 *   MP4 writer refuses those, and CameraX 1.4.2 then crashes on the next
 *   frame, so it was dropped.
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

    /** Video needs memfd_create (Android 11). Older devices get photos only. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val videoSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // The most a recording may hold in memory: half of what the system says
    // is free right now, and never more than 2 GiB.
    private const val MEMORY_CAP = 2L shl 30

    /**
     * Open a recording's output: an anonymous in-memory file (memfd) that
     * CameraX's muxer writes the MP4 into. It is never a file on disk and has
     * no path; it lives in RAM until [finishVideo] copies it into the vault
     * and closes it. Android's MP4 writer needs an ordinary seekable file
     * descriptor, which a memfd is. The recording is capped ([VideoSink.sizeLimit])
     * by free memory, the vault's free space and FAT's 4 GB limit, so the
     * recorder stops cleanly at the cap instead of running out.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun openVideo(context: Context, session: VaultSession): VideoSink {
        val facts = session.facts()
        if (!facts.writable) throw IOException("read-only vault")
        val free = runCatching { session.space().freeBytes.toLong() }.getOrNull()
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            info.availMem / 2
        }.getOrNull()
        val limit = listOfNotNull(
            facts.maxFileBytes?.toLong(),
            free?.let { it - VIDEO_RESERVE },
            memory,
            MEMORY_CAP,
        ).minOrNull()?.takeIf { it > 0 } ?: throw IOException("no room to record")
        val raw = Os.memfd_create("vaultpony-video", OsConstants.MFD_CLOEXEC)
        try {
            val pfd = ParcelFileDescriptor.dup(raw)
            return VideoSink(session, pfd, limit)
        } finally {
            runCatching { Os.close(raw) }
        }
    }

    /**
     * End a recording. [keep]: the muxer finished the file. When kept and the
     * vault is still open, the in-memory file is copied into the vault folder
     * [dir] under a temp name, then renamed to "VID_<[started]>.mp4"
     * (keep-both on a clash), and that name is returned. Otherwise nothing is
     * written and null is returned. The in-memory file is closed either way,
     * which frees it. Blocking: call it off the main thread.
     */
    fun finishVideo(sink: VideoSink, dir: String, started: Date, keep: Boolean): String? {
        val session = sink.session
        try {
            if (!keep || !SessionRegistry.isOpen(session)) return null
            val fd = sink.pfd.fileDescriptor
            val size = Os.fstat(fd).st_size
            if (size <= 0) return null
            val temp = register(session, tempPath(dir))
            try {
                session.create(temp.path)
                val buf = ByteArray(CHUNK)
                var offset = 0L
                while (offset < size) {
                    if (temp.discarded) return null
                    val want = minOf(CHUNK.toLong(), size - offset).toInt()
                    var got = 0
                    while (got < want) {
                        val n = Os.pread(fd, buf, got, want - got, offset + got)
                        if (n <= 0) throw IOException("short read")
                        got += n
                    }
                    val chunk = if (got == buf.size) buf else buf.copyOf(got)
                    val written = session.writeAt(temp.path, offset.toULong(), chunk).toInt()
                    if (written != got) throw IOException("short write")
                    offset += got
                }
                buf.fill(0)
                if (temp.discarded) return null
                val name = freeName(session, dir, videoName(started))
                session.replace(temp.path, ProviderPaths.child(dir, name))
                return name
            } catch (e: Exception) {
                CoreErrorMap.log("camera video", e)
                if (!temp.discarded) {
                    runCatching { session.remove(temp.path) }
                    runCatching { session.flush() }
                }
                return null
            } finally {
                unregister(temp)
            }
        } finally {
            // Frees the memory the recording held.
            runCatching { sink.pfd.close() }
        }
    }

    /** One recording held in memory. [sizeLimit]: give the recorder this
     *  as its file size limit. */
    class VideoSink internal constructor(
        val session: VaultSession,
        val pfd: ParcelFileDescriptor,
        val sizeLimit: Long,
    )
}
