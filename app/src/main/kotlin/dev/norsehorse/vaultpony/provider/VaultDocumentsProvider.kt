package dev.norsehorse.vaultpony.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.norsehorse.vaultpony.BulkPlanner
import dev.norsehorse.vaultpony.CoreErrorMap
import dev.norsehorse.vaultpony.OpenMode
import dev.norsehorse.vaultpony.ProviderNames
import dev.norsehorse.vaultpony.ProviderPaths
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.Thumbnails
import dev.norsehorse.vaultpony.VaultChange
import dev.norsehorse.vaultpony.VaultChanges
import dev.norsehorse.vaultpony.VaultErrorKind
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.VaultSession
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Exposes unlocked volumes system-wide (doc §8, 2.0.0 plan §4.1): other apps
 * read and write files straight in a container through proxy file
 * descriptors. Random access, no extraction, nothing written to VaultPony's
 * own storage; the bytes stream through the core's encryption.
 *
 * Doc ids: "<token>:<path-inside-volume>" (ProviderPaths), where the token
 * is random, minted when a vault unlocks and thrown away when it locks
 * (SessionRegistry). So no id, folder grant or document grant another app
 * kept still works after a lock, and a stored id never tells which volume
 * (outer or hidden) of which container was open. Roots appear only while a
 * volume is unlocked; locking removes the root, and callbacks on descriptors
 * still open fail cleanly with EIO.
 *
 * Writes: create, delete (folders recursively), rename, move within one
 * vault, and open for "w", "wt", "wa", "rw", "rwt". Other apps' writes go in
 * place (no temp swap), unlike imports. A read-only volume (NTFS) and an
 * outer volume whose hidden-volume protection tripped expose no write flags
 * and refuse writes. Opening or writing through here does not count as
 * activity: it never pushes the auto-lock deadline back.
 *
 * Thumbnails: images only, decoded and scaled in memory, served from memory,
 * never cached to disk.
 */
class VaultDocumentsProvider : DocumentsProvider() {

    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    override fun onCreate(): Boolean {
        ioThread = HandlerThread("vaultpony-proxy-io").also { it.start() }
        ioHandler = Handler(ioThread.looper)
        return true
    }

    // -- Queries -----------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        for (root in SessionRegistry.providerRoots()) {
            val session = root.session
            var flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
            if (canWrite(session)) flags = flags or Root.FLAG_SUPPORTS_CREATE
            // NTFS can't report space; leave the column empty then. After the
            // first call per session the core answers from its own count.
            val free = try {
                session.space().freeBytes.toLong()
            } catch (e: Exception) {
                null
            }
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, root.token)
                add(Root.COLUMN_DOCUMENT_ID, ProviderPaths.docId(root.token, "/"))
                add(Root.COLUMN_TITLE, "VaultPony")
                add(Root.COLUMN_SUMMARY, "Unlocked container")
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, android.R.drawable.ic_lock_lock)
                add(Root.COLUMN_AVAILABLE_BYTES, free)
            }
        }
        // Register the roots URI so DocumentsUI observes lock/unlock and
        // re-queries: on lock the session is gone, this cursor returns no
        // rows, and the stale "Unlocked container" root drops instead of
        // lingering in Files (doc §8 — roots track the mount table exactly).
        c.setNotificationUri(
            requireContext().contentResolver,
            DocumentsContract.buildRootsUri(SessionRegistry.AUTHORITY),
        )
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val doc = resolve(documentId)
        val entry = stat(doc.session, doc.path)
        val caps = capsOf(doc.session)
        addEntryRow(c, documentId, doc.path == "/", entry, caps)
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val dir = resolve(parentDocumentId)
        val caps = capsOf(dir.session)
        for (entry in list(dir.session, dir.path)) {
            // Import temp files are not documents.
            if (!entry.isDir && BulkPlanner.isTempName(entry.name)) continue
            val childPath = ProviderPaths.child(dir.path, entry.name)
            addEntryRow(c, ProviderPaths.docId(dir.token, childPath), false, entry, caps)
        }
        // Same observer path for the open volume's contents: every mutation
        // below notifies this URI, DocumentsUI re-queries.
        c.setNotificationUri(
            requireContext().contentResolver,
            DocumentsContract.buildChildDocumentsUri(SessionRegistry.AUTHORITY, parentDocumentId),
        )
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val pv = ProviderPaths.tokenOf(parentDocumentId) ?: return false
        val dv = ProviderPaths.tokenOf(documentId) ?: return false
        if (pv != dv) return false
        val pp = ProviderPaths.pathOf(parentDocumentId) ?: return false
        val dp = ProviderPaths.pathOf(documentId) ?: return false
        if (!ProviderPaths.isCleanPath(pp) || !ProviderPaths.isCleanPath(dp)) return false
        return ProviderPaths.isWithin(pp, dp)
    }

    // -- Mutations ---------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = resolve(parentDocumentId)
        requireWritable(dir.session)
        if (!stat(dir.session, dir.path).isDir) throw FileNotFoundException("not a folder")
        val isDir = mimeType == Document.MIME_TYPE_DIR
        var name = ProviderNames.clean(displayName)
        if (!isDir) {
            name = ProviderNames.withMimeExtension(name, defaultExtension(mimeType)) { ext ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) == mimeType
            }
        }
        name = ProviderNames.unique(name, isDir, list(dir.session, dir.path).map { it.name })
        val path = ProviderPaths.child(dir.path, name)
        mutate(dir.session, "create") {
            if (isDir) dir.session.mkdir(path) else dir.session.create(path)
            dir.session.flush()
        }
        changed(dir.token, dir.volumeId, dir.path)
        return ProviderPaths.docId(dir.token, path)
    }

    /** Folders are deleted with everything in them, as in Android's own
     *  file provider; Files asks the user before calling this. */
    override fun deleteDocument(documentId: String) {
        val doc = resolve(documentId)
        if (doc.path == "/") throw UnsupportedOperationException("the vault root can't be deleted")
        requireWritable(doc.session)
        val entry = stat(doc.session, doc.path)
        mutate(doc.session, "delete") {
            if (entry.isDir) removeTree(doc.session, doc.path) else doc.session.remove(doc.path)
            doc.session.flush()
        }
        changed(doc.token, doc.volumeId, ProviderPaths.parent(doc.path), gone = doc.path)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val doc = resolve(documentId)
        if (doc.path == "/") throw UnsupportedOperationException("the vault root can't be renamed")
        requireWritable(doc.session)
        val entry = stat(doc.session, doc.path)
        val dir = ProviderPaths.parent(doc.path)
        val current = ProviderPaths.leaf(doc.path)
        val wanted = ProviderNames.clean(displayName)
        if (wanted == current) return null
        val siblings = list(doc.session, dir).map { it.name }
        val target: String
        if (wanted.equals(current, ignoreCase = true)) {
            // Only the case changes. FAT and exFAT see the same name, so go
            // through a temporary one (never an import temp name).
            target = ProviderPaths.child(dir, wanted)
            val via = ProviderPaths.child(
                dir,
                ProviderNames.unique(ProviderNames.clean("$current~"), entry.isDir, siblings),
            )
            mutate(doc.session, "rename") {
                doc.session.rename(doc.path, via)
                try {
                    doc.session.rename(via, target)
                } catch (e: Exception) {
                    runCatching { doc.session.rename(via, doc.path) }
                    throw e
                }
                doc.session.flush()
            }
        } else {
            val others = siblings.filterNot { it.equals(current, ignoreCase = true) }
            target = ProviderPaths.child(dir, ProviderNames.unique(wanted, entry.isDir, others))
            mutate(doc.session, "rename") {
                doc.session.rename(doc.path, target)
                doc.session.flush()
            }
        }
        changed(doc.token, doc.volumeId, dir, gone = doc.path)
        return ProviderPaths.docId(doc.token, target)
    }

    /** Move within one vault. A name already taken in the target folder gets
     *  keep-both naming, as imports do. Across vaults, and for folders on FAT
     *  (whose ".." entry the core doesn't rewrite on a move), Files falls
     *  back to copy and delete: those have no FLAG_SUPPORTS_MOVE. */
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val doc = resolve(sourceDocumentId)
        val target = resolve(targetParentDocumentId)
        if (doc.token != target.token) throw UnsupportedOperationException("move across vaults")
        if (doc.path == "/") throw UnsupportedOperationException("the vault root can't be moved")
        requireWritable(doc.session)
        val entry = stat(doc.session, doc.path)
        if (!stat(target.session, target.path).isDir) throw FileNotFoundException("target is not a folder")
        if (entry.isDir && !capsOf(doc.session).moveDirs) {
            throw UnsupportedOperationException("folders can't be moved in place on FAT")
        }
        if (ProviderPaths.isWithin(doc.path, target.path)) {
            throw IllegalArgumentException("a folder can't be moved into itself")
        }
        val fromDir = ProviderPaths.parent(doc.path)
        if (ProviderPaths.samePath(fromDir, target.path)) return sourceDocumentId
        val name = ProviderNames.unique(
            ProviderPaths.leaf(doc.path),
            entry.isDir,
            list(doc.session, target.path).map { it.name },
        )
        val dest = ProviderPaths.child(target.path, name)
        mutate(doc.session, "move") {
            doc.session.rename(doc.path, dest)
            doc.session.flush()
        }
        changed(doc.token, doc.volumeId, fromDir, gone = doc.path)
        changed(doc.token, doc.volumeId, target.path)
        return ProviderPaths.docId(doc.token, dest)
    }

    // -- Open --------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val open = try {
            OpenMode.parse(mode)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException("unsupported mode: $mode")
        }
        val doc = resolve(documentId)
        val entry = stat(doc.session, doc.path)
        if (entry.isDir) throw FileNotFoundException("is a folder")
        var size = entry.size.toLong()
        var cap: Long? = null
        if (open.write) {
            val facts = try {
                doc.session.facts()
            } catch (e: Exception) {
                throw FileNotFoundException("vault locked")
            }
            if (!facts.writable || SessionRegistry.isReadOnlyLatched(doc.session)) {
                throw FileNotFoundException("this vault is read-only")
            }
            cap = facts.maxFileBytes?.toLong()
            if (open.truncate && size != 0L) {
                try {
                    doc.session.truncate(doc.path, 0uL)
                    doc.session.flush()
                } catch (e: Exception) {
                    CoreErrorMap.log("provider truncate", e)
                    SessionRegistry.noteFailure(doc.session, e)
                    throw FileNotFoundException(callerMessage(e))
                }
                size = 0L
                changed(doc.token, doc.volumeId, ProviderPaths.parent(doc.path))
            }
        }
        val pfdMode = when {
            open.read && open.write -> ParcelFileDescriptor.MODE_READ_WRITE
            open.write -> ParcelFileDescriptor.MODE_WRITE_ONLY
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        val storage = storageManager()
        return try {
            storage.openProxyFileDescriptor(
                pfdMode,
                VaultFdCallback(doc.token, doc.volumeId, doc.session, doc.path, open, size, cap),
                ioHandler,
            )
        } catch (e: IOException) {
            throw FileNotFoundException("could not open: ${e.message}")
        }
    }

    /**
     * One open descriptor. Runs on [ioThread]. Every failure, including the
     * vault locking underneath it, becomes an errno for the caller: an
     * exception escaping a callback would take the app down.
     */
    private inner class VaultFdCallback(
        private val token: String,
        private val volumeId: String,
        private val session: VaultSession,
        private val path: String,
        private val mode: OpenMode,
        /** Tracked here while writing, so the size never needs a core call. */
        private var size: Long,
        /** FAT's 4 GB per-file cap, if any. */
        private val cap: Long?,
    ) : ProxyFileDescriptorCallback() {

        private var dirty = false
        private var checkedAt = -OPEN_CHECK_MS

        /** The descriptor outlives nothing: re-check the auto-lock deadline
         *  (SessionRegistry.isOpen runs it) at most every [OPEN_CHECK_MS], so
         *  a vault due to lock locks even while another app keeps reading. */
        private fun ensureOpen(op: String) {
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt < OPEN_CHECK_MS) return
            if (!SessionRegistry.isOpen(session)) throw ErrnoException(op, OsConstants.EIO)
            checkedAt = now
        }

        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, chunkSize: Int, data: ByteArray): Int {
            if (!mode.read) throw ErrnoException("read", OsConstants.EBADF)
            ensureOpen("read")
            if (chunkSize <= 0 || offset >= size) return 0
            val chunk = try {
                session.readAt(path, offset.toULong(), chunkSize.toUInt())
            } catch (e: Throwable) {
                // Locked mid-read or a filesystem error: fail closed, no detail.
                throw ErrnoException("read", OsConstants.EIO)
            }
            val n = minOf(chunk.size, chunkSize, data.size)
            chunk.copyInto(data, 0, 0, n)
            return n
        }

        override fun onWrite(offset: Long, chunkSize: Int, data: ByteArray): Int {
            if (!mode.write) throw ErrnoException("write", OsConstants.EBADF)
            ensureOpen("write")
            if (chunkSize <= 0) return 0
            // Append mode: every write goes to the current end, whatever
            // offset the caller's descriptor is at.
            val at = if (mode.append) size else offset
            if (cap != null && at + chunkSize > cap) throw ErrnoException("write", OsConstants.EFBIG)
            val bytes = if (chunkSize == data.size) data else data.copyOf(chunkSize)
            val written = try {
                session.writeAt(path, at.toULong(), bytes).toInt()
            } catch (e: Throwable) {
                throw writeErrno("write", e)
            }
            dirty = true
            if (at + written > size) size = at + written
            return written
        }

        override fun onFsync() {
            ensureOpen("fsync")
            if (!dirty) return
            try {
                session.flush()
            } catch (e: Throwable) {
                throw writeErrno("fsync", e)
            }
        }

        override fun onRelease() {
            if (!dirty) return
            try {
                session.flush()
            } catch (e: Throwable) {
                // Nothing to report to: the caller already closed. A locked
                // vault lands here; its data up to the last flush is intact.
                CoreErrorMap.log("provider release", e)
            }
            try {
                changed(token, volumeId, ProviderPaths.parent(path))
            } catch (e: Throwable) {
                // Never let a notification problem crash the I/O thread.
            }
        }

        private fun writeErrno(op: String, e: Throwable): ErrnoException {
            CoreErrorMap.log("provider $op", e)
            SessionRegistry.noteFailure(session, e)
            val errno = when (CoreErrorMap.kindOf(e)) {
                VaultErrorKind.HIDDEN_PROTECTED, VaultErrorKind.READ_ONLY -> OsConstants.EROFS
                VaultErrorKind.FULL -> OsConstants.ENOSPC
                else -> OsConstants.EIO
            }
            return ErrnoException(op, errno)
        }
    }

    // -- Thumbnails --------------------------------------------------------

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val doc = resolve(documentId)
        val entry = stat(doc.session, doc.path)
        if (entry.isDir ||
            !Thumbnails.supported(guessMime(entry.name), Build.VERSION.SDK_INT) ||
            entry.size.toLong() > THUMB_MAX_SOURCE_BYTES
        ) {
            throw FileNotFoundException("no thumbnail")
        }
        val bytes = try {
            renderThumbnail(doc.session, doc.path, sizeHint, signal)
        } catch (e: android.os.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw FileNotFoundException("no thumbnail")
        val storage = storageManager()
        val pfd = try {
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = bytes.size.toLong()

                    override fun onRead(offset: Long, chunkSize: Int, data: ByteArray): Int {
                        if (offset >= bytes.size || chunkSize <= 0) return 0
                        val n = minOf(chunkSize, data.size, bytes.size - offset.toInt())
                        bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + n)
                        return n
                    }

                    override fun onRelease() {}
                },
                ioHandler,
            )
        } catch (e: IOException) {
            throw FileNotFoundException("no thumbnail")
        }
        return AssetFileDescriptor(pfd, 0, bytes.size.toLong())
    }

    /** Decode at a power-of-two subsample no smaller than [hint], re-encode,
     *  return the bytes. Two streaming passes through the core (bounds, then
     *  pixels); nothing touches disk. */
    private fun renderThumbnail(session: VaultSession, path: String, hint: Point, signal: CancellationSignal?): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        VaultInputStream(session, path).buffered(STREAM_BUFFER).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        signal?.throwIfCanceled()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Thumbnails.sampleSize(bounds.outWidth, bounds.outHeight, hint.x, hint.y)
        }
        val bitmap = VaultInputStream(session, path).buffered(STREAM_BUFFER).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        try {
            signal?.throwIfCanceled()
            val out = ByteArrayOutputStream()
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (!bitmap.compress(format, 85, out)) return null
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Sequential reads through the core, for BitmapFactory. */
    private class VaultInputStream(private val session: VaultSession, private val path: String) : InputStream() {
        private var pos = 0L
        private var checkedAt = -OPEN_CHECK_MS

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt >= OPEN_CHECK_MS) {
                if (!SessionRegistry.isOpen(session)) throw IOException("vault locked")
                checkedAt = now
            }
            val chunk = try {
                session.readAt(path, pos.toULong(), len.toUInt())
            } catch (e: Exception) {
                throw IOException("vault read failed")
            }
            if (chunk.isEmpty()) return -1
            chunk.copyInto(b, off)
            pos += chunk.size
            return chunk.size
        }
    }

    // -- helpers -----------------------------------------------------------

    /** [token]: the provider-facing id of this unlock; [volumeId]: the
     *  app's own stable id, used only inside the app (browser refresh). */
    private class Doc(val token: String, val volumeId: String, val session: VaultSession, val path: String)

    /** What this session allows. [moveDirs]: exFAT keeps no "." / ".."
     *  entries, so a folder can move in place there; FAT can't (see
     *  moveDocument). */
    private class Caps(val writable: Boolean, val moveDirs: Boolean)

    private fun capsOf(session: VaultSession): Caps {
        val facts = try {
            session.facts()
        } catch (e: Exception) {
            return Caps(writable = false, moveDirs = false)
        }
        val writable = facts.writable && !SessionRegistry.isReadOnlyLatched(session)
        return Caps(writable, moveDirs = writable && facts.filesystem.equals("Exfat", ignoreCase = true))
    }

    private fun canWrite(session: VaultSession): Boolean = capsOf(session).writable

    private fun storageManager(): StorageManager =
        requireContext().getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("storage service unavailable")

    private fun requireWritable(session: VaultSession) {
        if (!canWrite(session)) throw IllegalStateException("this vault is read-only")
    }

    /** Parse and check a document id. Malformed or non-canonical paths and
     *  import temp files are "not found"; so is a token from an earlier
     *  unlock (the vault locked since) or one never issued. */
    private fun resolve(documentId: String): Doc {
        val token = ProviderPaths.tokenOf(documentId) ?: throw FileNotFoundException("bad document id")
        val path = ProviderPaths.pathOf(documentId) ?: throw FileNotFoundException("bad document id")
        if (!ProviderPaths.isCleanPath(path) || ProviderPaths.isHidden(path)) {
            throw FileNotFoundException("no such document")
        }
        val root = SessionRegistry.providerRoot(token) ?: throw FileNotFoundException("no such document")
        return Doc(root.token, root.volumeId, root.session, path)
    }

    private fun stat(session: VaultSession, path: String): DirEntry = try {
        session.stat(path)
    } catch (e: Exception) {
        CoreErrorMap.log("provider stat", e)
        throw FileNotFoundException("no such document")
    }

    private fun list(session: VaultSession, path: String): List<DirEntry> = try {
        session.list(path)
    } catch (e: Exception) {
        CoreErrorMap.log("provider list", e)
        throw FileNotFoundException("could not list folder")
    }

    /** Run a vault write. Failures go to the caller as a short technical
     *  exception; the core's own text is logged only. A write blocked by
     *  hidden-volume protection latches the vault read-only. */
    private inline fun mutate(session: VaultSession, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CoreErrorMap.log("provider $what", e)
            SessionRegistry.noteFailure(session, e)
            throw IllegalStateException("$what failed: ${callerMessage(e)}")
        }
    }

    private fun callerMessage(e: Throwable): String = when (CoreErrorMap.kindOf(e)) {
        VaultErrorKind.HIDDEN_PROTECTED -> "blocked to protect the hidden volume; the vault is now read-only"
        VaultErrorKind.READ_ONLY -> "the vault is read-only"
        VaultErrorKind.FULL -> "the vault is full"
        VaultErrorKind.NOT_FOUND -> "not found"
        else -> "vault error"
    }

    private fun removeTree(session: VaultSession, dir: String) {
        for (e in session.list(dir)) {
            val p = ProviderPaths.child(dir, e.name)
            if (e.isDir) removeTree(session, p) else session.remove(p)
        }
        session.remove(dir)
    }

    /** After a mutation: DocumentsUI re-lists [dir] (and anything under
     *  [gone]), the root's free space refreshes, and an in-app browser on
     *  that folder reloads. */
    private fun changed(token: String, volumeId: String, dir: String, gone: String? = null) {
        val resolver = context?.contentResolver ?: return
        val authority = SessionRegistry.AUTHORITY
        resolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, ProviderPaths.docId(token, dir)),
            null,
        )
        if (gone != null) {
            resolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority, ProviderPaths.docId(token, gone)),
                null,
            )
        }
        resolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
        VaultChanges.post(
            VaultChange(volumeId, dirs = setOf(dir), gone = if (gone != null) setOf(gone) else emptySet()),
        )
    }

    private fun addEntryRow(c: MatrixCursor, documentId: String, isRoot: Boolean, entry: DirEntry, caps: Caps) {
        val mime = if (entry.isDir) Document.MIME_TYPE_DIR else guessMime(entry.name)
        var flags = 0
        if (caps.writable) {
            flags = if (entry.isDir) {
                var f = Document.FLAG_DIR_SUPPORTS_CREATE
                if (!isRoot) {
                    f = f or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                    if (caps.moveDirs) f = f or Document.FLAG_SUPPORTS_MOVE
                }
                f
            } else {
                Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
            }
        }
        if (!entry.isDir && Thumbnails.supported(mime, Build.VERSION.SDK_INT) &&
            entry.size.toLong() <= THUMB_MAX_SOURCE_BYTES
        ) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "Container" else entry.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, entry.size.toLong())
            add(Document.COLUMN_LAST_MODIFIED, entry.mtimeMs)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** The usual extension for [mime], or null for generic types. */
    private fun defaultExtension(mime: String): String? {
        if (mime == "application/octet-stream" || mime == "*/*" || mime.isBlank()) return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }

    companion object {
        // A photo bigger than this gets no thumbnail (decoding reads it all).
        private const val THUMB_MAX_SOURCE_BYTES = 64L shl 20
        private const val STREAM_BUFFER = 256 * 1024
        // How often an open descriptor re-checks the auto-lock deadline.
        private const val OPEN_CHECK_MS = 250L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
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
