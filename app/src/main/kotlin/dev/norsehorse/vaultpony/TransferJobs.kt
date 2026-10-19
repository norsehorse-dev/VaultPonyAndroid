package dev.norsehorse.vaultpony

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.vault_ffi.VaultSession
import java.io.IOException
import java.io.InputStream

/**
 * Runs bulk imports and folder exports (2.0.0 plan §2.5). Process-scoped, not
 * tied to a screen, so a rotation or a recomposition doesn't kill a copy of
 * thousands of files; the browser just observes [state]. One batch at a time.
 *
 * Planning lives in [BulkPlanner]; this file only walks sources, asks the
 * questions the plan raises, and moves bytes in 1 MiB chunks through the
 * session's existing create/writeAt/readAt path. Imports write each file to
 * a ".vaultpony-*.part" temp beside its destination and rename it into place
 * only when complete, so no stop leaves a half file or loses a replaced one.
 */
object TransferJobs {

    sealed interface State {
        data object Idle : State

        /** Walking the source and checking the destination. */
        data class Preparing(val export: Boolean) : State

        /** Some names exist in the destination: keep both, skip, or replace. */
        data class AskCollision(val collisions: Int) : State

        /** The batch doesn't fit. Nothing has been written. */
        data class NoSpace(val needed: Long, val free: Long) : State

        /** Files over FAT's 4 GB cap will be left out; continue? */
        data class Oversize(val count: Int) : State

        data class Running(
            val export: Boolean,
            val index: Int,
            val total: Int,
            val name: String,
            val bytesDone: Long,
            val bytesTotal: Long,
        ) : State

        data class Done(val result: TransferResult) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The session [state] belongs to. A browser for any other session (a
     *  different vault, or the same one unlocked again) ignores it. */
    @Volatile
    var owner: VaultSession? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // Cancel is checked at chunk boundaries, not by cancelling the coroutine,
    // so the partial file is always cleaned up and the vault flushed.
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var collisionAnswer: CompletableDeferred<CollisionPolicy?>? = null
    // A batch is between start() and its final state. onStopped is the
    // auto-lock's hold release, called once the batch has cleaned up.
    private val runLock = Any()
    private var running = false
    private var onStopped: (() -> Unit)? = null
    @Volatile
    private var oversizeAnswer: CompletableDeferred<Boolean>? = null

    private const val CHUNK = 1 shl 20
    // Flush every so often during a long import, not only at the end, so a
    // process kill mid-batch loses little.
    private const val FLUSH_EVERY_FILES = 32
    private const val FLUSH_EVERY_BYTES = 64L shl 20

    val busy: Boolean get() = synchronized(runLock) { running }

    /** Import picked documents (multi-select) into the vault folder [destDir]. */
    fun importFiles(context: Context, session: VaultSession, destDir: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = context.applicationContext.contentResolver
        start(session, export = false) {
            val sources = uris.map { describe(resolver, it) }
            runImport(resolver, session, destDir, sources)
        }
    }

    /** Import a picked folder (and everything under it) into [destDir]. */
    fun importTree(context: Context, session: VaultSession, destDir: String, tree: Uri) {
        val resolver = context.applicationContext.contentResolver
        start(session, export = false) {
            val sources = walkTree(resolver, tree) ?: return@start null
            runImport(resolver, session, destDir, sources)
        }
    }

    /** Export the vault folder [vaultDir] into the picked device folder [tree].
     *  A named folder is recreated as a folder there; the vault root's contents
     *  go straight into [tree]. */
    fun exportFolder(context: Context, session: VaultSession, vaultDir: String, tree: Uri) {
        val resolver = context.applicationContext.contentResolver
        // The caller's (localized) resources, so error text follows the in-app language.
        val res = context.resources
        start(session, export = true) {
            val sources = ArrayList<SourceEntry>()
            val name = vaultDir.trimEnd('/').substringAfterLast('/')
            val prefix = if (name.isEmpty()) emptyList() else listOf(name)
            if (prefix.isNotEmpty()) sources += SourceEntry(prefix, true, 0, vaultDir)
            if (!walkVault(session, vaultDir, prefix, sources)) return@start null
            runExport(resolver, res, session, tree, sources)
        }
    }

    fun answerCollision(policy: CollisionPolicy?) {
        collisionAnswer?.complete(policy)
    }

    fun answerOversize(proceed: Boolean) {
        oversizeAnswer?.complete(proceed)
    }

    /** Stop after the current chunk. Before copying starts this abandons the
     *  batch with nothing written. */
    fun cancel() {
        cancelRequested = true
        collisionAnswer?.complete(null)
        oversizeAnswer?.complete(false)
    }

    /** The app is leaving the foreground: stop the batch at its next chunk
     *  boundary (partial file removed, vault flushed, summary says stopped),
     *  then call [onStopped] from the worker. False when no batch is running,
     *  and [onStopped] is never called. */
    fun stopForBackground(onStopped: () -> Unit): Boolean {
        synchronized(runLock) {
            if (!running) return false
            this.onStopped = onStopped
        }
        cancel()
        return true
    }

    /** Close a finished batch's summary (or the no-space notice). */
    fun dismiss() {
        if (!busy) _state.value = State.Idle
    }

    private fun start(session: VaultSession, export: Boolean, body: suspend () -> State?) {
        synchronized(runLock) {
            if (running) return
            running = true
            onStopped = null
            cancelRequested = false
        }
        owner = session
        _state.value = State.Preparing(export)
        job = scope.launch {
            try {
                val end = try {
                    body()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Failed before any copying (walk, listing, planning).
                    State.Done(
                        TransferResult(
                            export = export, copied = 0, total = 0, skipped = 0, renamed = 0,
                            keptBoth = 0, cancelled = false, error = e.message ?: "",
                        ),
                    )
                }
                _state.value = end ?: State.Idle
            } finally {
                // The batch has flushed and settled; let a held auto-lock go.
                val release = synchronized(runLock) {
                    running = false
                    onStopped.also { onStopped = null }
                }
                release?.invoke()
            }
        }
    }

    // -- Import --------------------------------------------------------------

    private suspend fun runImport(
        resolver: ContentResolver,
        session: VaultSession,
        destDir: String,
        sources: List<SourceEntry>,
    ): State? {
        if (sources.isEmpty() || cancelRequested) return null
        val facts = session.facts()
        // NTFS and friends can't report space; then there's no space check.
        val space = runCatching { session.space() }.getOrNull()
        val limits = FsLimits(
            clusterBytes = space?.clusterBytes?.toLong()?.coerceAtLeast(1L) ?: 1L,
            freeBytes = space?.freeBytes?.toLong(),
            maxFileBytes = facts.maxFileBytes?.toLong(),
        )
        // The planner lists exactly the existing folders this batch writes
        // into (the destination and any it merges with), so this is where
        // temp files left by a batch killed mid-file are swept. They are never
        // finished files: a complete one is renamed before anything else.
        var swept = 0
        val listDir: (List<String>) -> List<ExistingEntry> = { rel ->
            val dir = BulkPlanner.vaultPath(destDir, rel)
            session.list(dir).mapNotNull {
                if (!it.isDir && BulkPlanner.isTempName(it.name)) {
                    if (runCatching { session.remove(BulkPlanner.vaultPath(dir, listOf(it.name))) }.isSuccess) swept++
                    null
                } else {
                    ExistingEntry(it.name, it.isDir, it.size.toLong())
                }
            }
        }
        var plan = BulkPlanner.plan(sources, limits, CollisionPolicy.KEEP_BOTH, mergeDirs = true, listDir)
        if (swept > 0) runCatching { session.flush() }
        if (plan.collisions > 0) {
            val answer = CompletableDeferred<CollisionPolicy?>()
            collisionAnswer = answer
            // cancel() can't complete a deferred that didn't exist yet.
            if (cancelRequested) {
                collisionAnswer = null
                return null
            }
            _state.value = State.AskCollision(plan.collisions)
            val policy = answer.await()
            collisionAnswer = null
            if (policy == null) return null
            if (policy != CollisionPolicy.KEEP_BOTH) {
                plan = BulkPlanner.plan(sources, limits, policy, mergeDirs = true, listDir)
            }
        }
        if (!plan.fits(limits.freeBytes)) {
            return State.NoSpace(plan.bytesNeeded, limits.freeBytes ?: 0L)
        }
        if (plan.oversize > 0) {
            val answer = CompletableDeferred<Boolean>()
            oversizeAnswer = answer
            if (cancelRequested) {
                oversizeAnswer = null
                return null
            }
            _state.value = State.Oversize(plan.oversize)
            val go = answer.await()
            oversizeAnswer = null
            if (!go) return null
        }
        if (cancelRequested) return null

        var copied = 0
        var skipped = plan.skipped + plan.oversize
        var index = 0
        var bytesDone = 0L
        var sinceFlushFiles = 0
        var sinceFlushBytes = 0L
        var cancelled = false
        var error: String? = null
        try {
            loop@ for (op in plan.ops) {
                if (cancelRequested) {
                    cancelled = true
                    break
                }
                when (op) {
                    is TransferOp.MakeDir -> session.mkdir(BulkPlanner.vaultPath(destDir, op.dest))
                    is TransferOp.CopyFile -> {
                        index++
                        val name = op.dest.last()
                        val path = BulkPlanner.vaultPath(destDir, op.dest)
                        _state.value = State.Running(false, index, plan.files, name, bytesDone, plan.bytes)
                        val outcome = copyIn(resolver, session, op, path, limits.maxFileBytes) { n ->
                            bytesDone += n
                            sinceFlushBytes += n
                            _state.value = State.Running(false, index, plan.files, name, bytesDone, plan.bytes)
                        }
                        when (outcome) {
                            Outcome.COPIED -> copied++
                            Outcome.SKIPPED -> skipped++
                            Outcome.CANCELLED -> {
                                cancelled = true
                                break@loop
                            }
                        }
                        if (++sinceFlushFiles >= FLUSH_EVERY_FILES || sinceFlushBytes >= FLUSH_EVERY_BYTES) {
                            session.flush()
                            sinceFlushFiles = 0
                            sinceFlushBytes = 0L
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // A vault-side failure (full, locked, hidden area protected): stop
            // here and say so; what was already copied stays.
            error = e.message ?: ""
        }
        runCatching { session.flush() }
        return State.Done(
            TransferResult(
                export = false,
                copied = copied,
                total = plan.files,
                skipped = skipped,
                renamed = plan.renamed,
                keptBoth = plan.keptBoth,
                cancelled = cancelled,
                error = error,
            ),
        )
    }

    private enum class Outcome { COPIED, SKIPPED, CANCELLED }

    /** Copy one source document into the vault at [path], by way of a temp
     *  sibling renamed into place once complete. Source read errors skip the
     *  file (the batch goes on); vault errors propagate. On any stop only the
     *  temp is removed; [path], including a file being replaced, is untouched. */
    private fun copyIn(
        resolver: ContentResolver,
        session: VaultSession,
        op: TransferOp.CopyFile,
        path: String,
        cap: Long?,
        onBytes: (Long) -> Unit,
    ): Outcome {
        val input = try {
            resolver.openInputStream(Uri.parse(op.source.ref))
        } catch (e: Exception) {
            null
        } ?: return Outcome.SKIPPED
        val dir = path.substringBeforeLast('/').ifEmpty { "/" }
        val temp = BulkPlanner.vaultPath(dir, listOf(BulkPlanner.tempName(randomToken())))
        input.use { stream ->
            session.create(temp)
            val buf = ByteArray(CHUNK)
            var offset = 0L
            try {
                while (true) {
                    if (cancelRequested) {
                        discard(session, temp)
                        return Outcome.CANCELLED
                    }
                    val n = try {
                        readChunk(stream, buf)
                    } catch (e: IOException) {
                        discard(session, temp)
                        return Outcome.SKIPPED
                    }
                    if (n <= 0) break
                    // The size wasn't known up front, or the file grew.
                    if (cap != null && offset + n > cap) {
                        discard(session, temp)
                        return Outcome.SKIPPED
                    }
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val written = session.writeAt(temp, offset.toULong(), chunk)
                    if (written.toInt() != n) throw IOException()
                    offset += n
                    onBytes(n.toLong())
                }
            } catch (e: Exception) {
                discard(session, temp)
                throw e
            }
        }
        // Complete: swap it in. A replaced original goes only now.
        if (op.replace) {
            try {
                session.remove(path)
            } catch (e: Exception) {
                discard(session, temp)
                throw e
            }
        }
        try {
            session.rename(temp, path)
        } catch (e: Exception) {
            // After a Replace the original is gone; keep the new copy in the
            // temp rather than lose both (until the next import's sweep).
            if (!op.replace) discard(session, temp)
            throw e
        }
        return Outcome.COPIED
    }

    /** Random part of a temp name: 12 hex digits, unique enough per folder. */
    private fun randomToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private fun discard(session: VaultSession, path: String) {
        runCatching { session.remove(path) }
    }

    /** Fill [buf] as far as the stream allows; providers often return short reads. */
    private fun readChunk(stream: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = stream.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    /** Name and size of a single picked document. */
    private fun describe(resolver: ContentResolver, uri: Uri): SourceEntry {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iName >= 0) name = c.getString(iName)
                    if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        return SourceEntry(listOf(name ?: fallback), isDir = false, size = size, ref = uri.toString())
    }

    private class TreeRow(val id: String, val name: String, val isDir: Boolean, val size: Long)

    /** Walk a picked folder: the folder itself first, then its contents depth
     *  first, folders before their children. Null when cancelled. */
    private fun walkTree(resolver: ContentResolver, tree: Uri): List<SourceEntry>? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        var rootName: String? = null
        runCatching {
            resolver.query(rootDoc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) rootName = c.getString(0)
            }
        }
        val name = rootName ?: rootId.substringAfterLast(':').substringAfterLast('/')
        val out = arrayListOf(SourceEntry(listOf(name), isDir = true, size = 0, ref = rootDoc.toString()))
        return if (walkChildren(resolver, tree, rootId, listOf(name), out)) out else null
    }

    private fun walkChildren(
        resolver: ContentResolver,
        tree: Uri,
        parentId: String,
        prefix: List<String>,
        out: MutableList<SourceEntry>,
    ): Boolean {
        if (cancelRequested) return false
        val rows = ArrayList<TreeRow>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        // Read the whole level before recursing so only one cursor is open.
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id.substringAfterLast('/')
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                rows += TreeRow(id, name, isDir, size)
            }
        }
        for (r in rows) {
            val segs = prefix + r.name
            if (r.isDir) {
                out += SourceEntry(segs, isDir = true, size = 0, ref = "")
                if (!walkChildren(resolver, tree, r.id, segs, out)) return false
            } else {
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, r.id)
                out += SourceEntry(segs, isDir = false, size = r.size, ref = doc.toString())
            }
        }
        return true
    }

    // -- Export --------------------------------------------------------------

    /** Walk a vault folder depth first, folders before their contents. */
    private fun walkVault(
        session: VaultSession,
        dir: String,
        prefix: List<String>,
        out: MutableList<SourceEntry>,
    ): Boolean {
        if (cancelRequested) return false
        for (e in session.list(dir).sortedBy { it.name.lowercase() }) {
            val segs = prefix + e.name
            val path = BulkPlanner.vaultPath(dir, listOf(e.name))
            if (e.isDir) {
                out += SourceEntry(segs, isDir = true, size = 0, ref = path)
                if (!walkVault(session, path, segs, out)) return false
            } else {
                out += SourceEntry(segs, isDir = false, size = e.size.toLong(), ref = path)
            }
        }
        return true
    }

    private fun runExport(
        resolver: ContentResolver,
        res: Resources,
        session: VaultSession,
        tree: Uri,
        sources: List<SourceEntry>,
    ): State? {
        if (cancelRequested) return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        // Only the picked folder can already hold these names; everything
        // below it is created by this batch.
        val listDir: (List<String>) -> List<ExistingEntry> = { rel ->
            if (rel.isEmpty()) listDocChildren(resolver, tree, rootId) else emptyList()
        }
        val plan = BulkPlanner.plan(sources, FsLimits(), CollisionPolicy.KEEP_BOTH, mergeDirs = false, listDir)

        val docs = HashMap<List<String>, Uri>()
        docs[emptyList()] = rootDoc
        var copied = 0
        var index = 0
        var bytesDone = 0L
        var cancelled = false
        var error: String? = null
        try {
            loop@ for (op in plan.ops) {
                if (cancelRequested) {
                    cancelled = true
                    break
                }
                when (op) {
                    is TransferOp.MakeDir -> {
                        val parent = docs.getValue(op.dest.dropLast(1))
                        docs[op.dest] = DocumentsContract.createDocument(
                            resolver, parent, Document.MIME_TYPE_DIR, op.dest.last(),
                        ) ?: throw IOException(res.getString(R.string.transfer_err_create, op.dest.last()))
                    }
                    is TransferOp.CopyFile -> {
                        index++
                        val name = op.dest.last()
                        _state.value = State.Running(true, index, plan.files, name, bytesDone, plan.bytes)
                        val parent = docs.getValue(op.dest.dropLast(1))
                        val outcome = copyOut(resolver, res, session, op.source, parent, name) { n ->
                            bytesDone += n
                            _state.value = State.Running(true, index, plan.files, name, bytesDone, plan.bytes)
                        }
                        if (outcome == Outcome.CANCELLED) {
                            cancelled = true
                            break@loop
                        }
                        copied++
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: ""
        }
        return State.Done(
            TransferResult(
                export = true,
                copied = copied,
                total = plan.files,
                skipped = 0,
                renamed = plan.renamed,
                keptBoth = plan.keptBoth,
                cancelled = cancelled,
                error = error,
            ),
        )
    }

    private fun listDocChildren(resolver: ContentResolver, tree: Uri, parentId: String): List<ExistingEntry> {
        val out = ArrayList<ExistingEntry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                out += ExistingEntry(name, c.getString(1) == Document.MIME_TYPE_DIR, size)
            }
        }
        return out
    }

    /** Stream one vault file into a new document under [parent]. A partial
     *  document is deleted on cancel or error. */
    private fun copyOut(
        resolver: ContentResolver,
        res: Resources,
        session: VaultSession,
        src: SourceEntry,
        parent: Uri,
        name: String,
        onBytes: (Long) -> Unit,
    ): Outcome {
        val ext = BulkPlanner.splitExtension(name).second.removePrefix(".").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException(res.getString(R.string.transfer_err_create, name))
        try {
            val out = resolver.openOutputStream(doc) ?: throw IOException(res.getString(R.string.transfer_err_write, name))
            out.use { stream ->
                val size = src.size.toULong()
                var offset = 0uL
                while (offset < size) {
                    if (cancelRequested) {
                        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                        return Outcome.CANCELLED
                    }
                    val chunk = session.readAt(src.ref, offset, CHUNK.toUInt())
                    if (chunk.isEmpty()) break
                    stream.write(chunk)
                    offset += chunk.size.toUInt()
                    onBytes(chunk.size.toLong())
                }
                stream.flush()
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return Outcome.COPIED
    }
}
