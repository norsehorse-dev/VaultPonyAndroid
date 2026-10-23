package dev.norsehorse.vaultpony

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uniffi.vault_ffi.VaultSession
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thumbnails for the browser's grid (2.0.0 plan §5.3). Each one is decoded in
 * memory from the vault through readAt, downsampled with inSampleSize, and
 * kept in a small in-memory LRU. Nothing is ever written to disk, and every
 * lock empties the cache (SessionRegistry). Files over [MAX_SOURCE_BYTES] get
 * the file icon instead: decoding reads the whole source.
 */
object ThumbnailCache {
    private const val MAX_SOURCE_BYTES = 20L shl 20
    private const val CACHE_BYTES = 32 shl 20
    private const val READ_CHUNK: UInt = 1_048_576u

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    // BitmapFactory decodes HEIF from Android 9.
    private val HEIF_EXTENSIONS = setOf("heic", "heif")

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Bumped by every clear(), so a decode that was running when the vault
    // locked can't put its result back afterwards. The generation check and
    // the put happen under [lock], as do the bump and the eviction, so a
    // clear can never fall between them.
    private val generation = AtomicInteger()
    private val lock = Any()

    // Two decodes at a time: each one holds the source (up to 20 MB) in memory.
    private val gate = Semaphore(2)

    /** True when the grid should try a thumbnail for this file. */
    fun supported(name: String, size: ULong): Boolean {
        if (size == 0uL || size > MAX_SOURCE_BYTES.toULong()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS || (ext in HEIF_EXTENSIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    /** Cache key for one file of one unlocked vault, or null once it locked. */
    fun key(session: VaultSession, path: String, size: ULong, mtimeMs: Long?): String? {
        val id = SessionRegistry.idOf(session) ?: return null
        return "$id\u0000$path\u0000$size\u0000${mtimeMs ?: 0L}"
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Decode a thumbnail at least [targetPx] on each side (or the image's own
     *  size when smaller). Null when the file isn't a decodable image, is too
     *  big, or the vault locked. Call off the main thread. */
    suspend fun load(session: VaultSession, path: String, size: ULong, mtimeMs: Long?, targetPx: Int): Bitmap? {
        if (size > MAX_SOURCE_BYTES.toULong()) return null
        return gate.withPermit {
            val k = key(session, path, size, mtimeMs) ?: return@withPermit null
            cache.get(k)?.let { return@withPermit it }
            val gen = generation.get()
            val bytes = readAll(session, path, size.toInt()) ?: return@withPermit null
            val bitmap = try {
                decode(bytes, targetPx)
            } catch (e: Throwable) {
                // OutOfMemoryError included: a thumbnail is never worth a crash.
                null
            } finally {
                bytes.fill(0)
            }
            // isOpen first, outside [lock]: clear() runs inside SessionRegistry's
            // lock, so taking that lock while holding ours could deadlock.
            if (bitmap != null && SessionRegistry.isOpen(session)) {
                synchronized(lock) {
                    if (generation.get() == gen) cache.put(k, bitmap)
                }
            }
            bitmap
        }
    }

    /** Drop every thumbnail. Called at every lock. */
    fun clear() {
        synchronized(lock) {
            generation.incrementAndGet()
            cache.evictAll()
        }
    }

    private fun readAll(session: VaultSession, path: String, size: Int): ByteArray? {
        if (!SessionRegistry.isOpen(session)) return null
        val out = ByteArray(size)
        var offset = 0
        try {
            while (offset < size) {
                val chunk = session.readAt(path, offset.toULong(), minOf(READ_CHUNK, (size - offset).toUInt()))
                if (chunk.isEmpty()) break
                chunk.copyInto(out, offset)
                offset += chunk.size
                chunk.fill(0)
            }
        } catch (e: Exception) {
            out.fill(0)
            return null
        }
        if (offset < size) {
            // Shorter than its directory entry said; decode what is there.
            val trimmed = out.copyOf(offset)
            out.fill(0)
            return trimmed
        }
        return out
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Thumbnails.sampleSize(bounds.outWidth, bounds.outHeight, targetPx, targetPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
