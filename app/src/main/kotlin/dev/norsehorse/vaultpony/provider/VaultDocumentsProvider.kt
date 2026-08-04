package dev.norsehorse.vaultpony.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import dev.norsehorse.vaultpony.SessionRegistry
import uniffi.vault_ffi.VaultSession

/**
 * Exposes unlocked volumes system-wide (doc §8): other apps read files
 * straight out of a container via proxy file descriptors — random access,
 * no extraction, which is what makes video seek work.
 *
 * Doc ids: "<volumeId>:<path-inside-volume>". Roots appear only while a
 * volume is unlocked; locking removes the root and in-flight proxy reads
 * fail cleanly with EBADF.
 *
 * P3 status: read-only queries + openDocument via proxy fd. Thumbnails are
 * deliberately not implemented (doc §11 — no content in system surfaces).
 */
class VaultDocumentsProvider : DocumentsProvider() {

    private lateinit var ioThread: HandlerThread

    override fun onCreate(): Boolean {
        ioThread = HandlerThread("vaultpony-proxy-io").also { it.start() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        for (volumeId in SessionRegistry.ids()) {
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, volumeId)
                add(Root.COLUMN_DOCUMENT_ID, docId(volumeId, "/"))
                add(Root.COLUMN_TITLE, "VaultPony")
                add(Root.COLUMN_SUMMARY, "Unlocked container")
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_ICON, android.R.drawable.ic_lock_lock)
            }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val (session, path) = resolve(documentId)
        val entry = session.stat(path)
        addEntryRow(c, documentId, entry.name, entry.isDir, entry.size, entry.mtimeMs)
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val (session, path) = resolve(parentDocumentId)
        for (entry in session.list(path)) {
            val childPath = if (path == "/") "/${entry.name}" else "$path/${entry.name}"
            addEntryRow(
                c,
                docId(volumeOf(parentDocumentId), childPath),
                entry.name,
                entry.isDir,
                entry.size,
                entry.mtimeMs,
            )
        }
        return c
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        require(mode == "r") { "read-only in P3" }
        val (session, path) = resolve(documentId)
        val size = session.stat(path).size
        val storage = requireContext().getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size.toLong()

                override fun onRead(offset: Long, requested: Int, data: ByteArray): Int {
                    val chunk = try {
                        session.readAt(path, offset.toULong(), requested.toUInt())
                    } catch (e: Exception) {
                        // Locked mid-read or FS error: fail closed, no detail.
                        throw ErrnoException("read", OsConstants.EBADF)
                    }
                    chunk.copyInto(data)
                    return chunk.size
                }

                override fun onRelease() {}
            },
            Handler(ioThread.looper),
        )
    }

    // -- helpers -----------------------------------------------------------

    private fun docId(volumeId: String, path: String) = "$volumeId:$path"

    private fun volumeOf(documentId: String) = documentId.substringBefore(':')

    private fun resolve(documentId: String): Pair<VaultSession, String> {
        val volumeId = volumeOf(documentId)
        val path = documentId.substringAfter(':')
        val session = SessionRegistry.get(volumeId)
            ?: throw java.io.FileNotFoundException("volume locked")
        return session to path
    }

    private fun addEntryRow(
        c: MatrixCursor,
        documentId: String,
        name: String,
        isDir: Boolean,
        size: ULong,
        mtimeMs: Long?,
    ) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (name == "/") "Container" else name)
            add(
                Document.COLUMN_MIME_TYPE,
                if (isDir) Document.MIME_TYPE_DIR else guessMime(name),
            )
            add(Document.COLUMN_SIZE, size.toLong())
            add(Document.COLUMN_LAST_MODIFIED, mtimeMs)
            // No thumbnails, no writes in P3: flags stay 0.
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )
        private val DEFAULT_DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
