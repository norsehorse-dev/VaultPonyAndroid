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
import java.io.ByteArrayInputStream
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
 *
 * rc2 (plan §4.3, R1): each batch runs inside [TransferService], a dataSync
 * foreground service, so it keeps going in the background, and the auto-lock
 * timer waits for it (SessionRegistry.setBatchActive). A Lock tapped during a
 * copy stops it at the next chunk boundary first ([stopForLock]).
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

    // True from start() until the batch has fully settled (flushed, summary
    // posted, hold released). The foreground service watches it to know when
    // to leave the foreground. Written under runLock.
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The current (or last) batch is an export. For the notification. */
    @Volatile
    var exporting: Boolean = false
        private set

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
    // The cancel came from a lock (the vault went away), not from the user:
    // a batch stopped at a question then still leaves a summary.
    @Volatile
    private var abandoned = false
    @Volatile
    private var collisionAnswer: CompletableDeferred<CollisionPolicy?>? = null
    // A batch is between start() and its final state. onStopped is the
    // user Lock's hold release, called once the batch has cleaned up.
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

    /** Name a text-only share is saved under (plan §5.1). */
    const val SHARED_TEXT_NAME = "Shared text.txt"

    // Source ref of a shared text; never a content URI (shares are filtered
    // to content:// before they get here).
    private const val SHARED_TEXT_REF = "vaultpony:shared-text"

    // The shared text's bytes while its batch runs; wiped when it ends.
    @Volatile
    private var sharedText: ByteArray? = null

    /** Import picked documents (multi-select) into the vault folder [destDir]. */
    fun importFiles(context: Context, session: VaultSession, destDir: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = context.applicationContext.contentResolver
        start(context, session, export = false) {
            val sources = uris.map { describe(resolver, it) }
            runImport(resolver, session, destDir, sources)
        }
    }

    /** Save what another app shared (ACTION_SEND / SEND_MULTIPLE, plan
     *  §5.1) into [destDir]: the shared documents, or [text] as "Shared
     *  text.txt" when nothing else came with it. Name collisions keep both
     *  without asking (D6). The documents are read with the share's own
     *  read grant, which the foreground service also holds until the batch
     *  ends, so the copy goes on even if the share's activity is closed.
     *  False when nothing started (nothing to save, or another batch is
     *  still running). */
    fun importShared(context: Context, session: VaultSession, destDir: String, uris: List<Uri>, text: String?): Boolean {
        if (uris.isEmpty() && text == null) return false
        val resolver = context.applicationContext.contentResolver
        return start(context, session, export = false, grants = uris) {
            val sources = ArrayList<SourceEntry>()
            uris.mapTo(sources) { describe(resolver, it) }
            if (uris.isEmpty() && text != null) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                sharedText = bytes
                sources += SourceEntry(listOf(SHARED_TEXT_NAME), isDir = false, size = bytes.size.toLong(), ref = SHARED_TEXT_REF)
            }
            try {
                runImport(resolver, session, destDir, sources, askCollisions = false)
            } finally {
                sharedText?.fill(0)
                sharedText = null
            }
        }
    }

    /** Import a picked folder (and everything under it) into [destDir]. */
    fun importTree(context: Context, session: VaultSession, destDir: String, tree: Uri) {
        val resolver = context.applicationContext.contentResolver
        start(context, session, export = false) {
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
        start(context, session, export = true) {
            val sources = ArrayList<SourceEntry>()
            val name = vaultDir.trimEnd('/').substringAfterLast('/')
            val prefix = if (name.isEmpty()) emptyList() else listOf(name)
            if (prefix.isNotEmpty()) sources += SourceEntry(prefix, true, 0, vaultDir)
            if (!walkVault(session, vaultDir, prefix, sources)) return@start null
            runExport(resolver, res, session, tree, sources)
        }
    }

    /** Export the browser's multi-selection (plan §5.3): [names] in the
     *  vault folder [vaultDir], files and folders with everything in them,
     *  into the picked device folder [tree]. */
    fun exportItems(context: Context, session: VaultSession, vaultDir: String, names: List<String>, tree: Uri) {
        if (names.isEmpty()) return
        val resolver = context.applicationContext.contentResolver
        val res = context.resources
        start(context, session, export = true) {
            val sources = ArrayList<SourceEntry>()
            for (name in names.sortedBy { it.lowercase() }) {
                val path = BulkPlanner.vaultPath(vaultDir, listOf(name))
                val entry = session.stat(path)
                if (entry.isDir) {
                    sources += SourceEntry(listOf(name), isDir = true, size = 0, ref = path)
                    if (!walkVault(session, path, listOf(name), sources)) return@start null
                } else {
                    sources += SourceEntry(listOf(name), isDir = false, size = entry.size.toLong(), ref = path)
                }
            }
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

    /** The user tapped Lock (in-app, notification or tile) during a batch:
     *  stop it at its next chunk boundary (partial file removed, vault
     *  flushed, summary says stopped), then call [onStopped] from the worker.
     *  False when no batch is running, and [onStopped] is never called. */
    fun stopForLock(onStopped: () -> Unit): Boolean {
        synchronized(runLock) {
            if (!running) return false
            this.onStopped = onStopped
        }
        cancel()
        return true
    }

    /** A vault in [locked] is being locked. A batch on it that is waiting on
     *  a question (or stuck past the hold) is told to give up, so it can't
     *  wait forever on a session that no longer exists. */
    fun abandonFor(locked: Collection<VaultSession>) {
        val o = owner ?: return
        if (busy && locked.any { it === o }) {
            abandoned = true
            cancel()
        }
    }

    /** Close a finished batch's summary (or the no-space notice). */
    fun dismiss() {
        if (!busy) _state.value = State.Idle
    }

    /** Start a batch. [grants]: shared documents whose read grant the
     *  foreground service should hold for the batch. False when another
     *  batch is still running, and nothing happens. */
    private fun start(
        context: Context,
        session: VaultSession,
        export: Boolean,
        grants: List<Uri> = emptyList(),
        body: suspend () -> State?,
    ): Boolean {
        synchronized(runLock) {
            if (running) return false
            running = true
            onStopped = null
            cancelRequested = false
            abandoned = false
            _active.value = true
        }
        owner = session
        exporting = export
        _state.value = State.Preparing(export)
        // R1: the auto-lock timer waits while this batch works...
        SessionRegistry.setBatchActive(true)
        // ...and the foreground service keeps the process going meanwhile.
        TransferService.start(context, grants)
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
                            keptBoth = 0, cancelled = false, error = batchError(session, e),
                        ),
                    )
                }
                _state.value = end ?: State.Idle
            } finally {
                // The batch has flushed and settled: the auto-lock timer may
                // start (R1), and a Lock held for it may go ahead.
                SessionRegistry.setBatchActive(false)
                val release = synchronized(runLock) {
                    running = false
                    _active.value = false
                    onStopped.also { onStopped = null }
                }
                release?.invoke()
                // The copy notification is going away; the unlocked one may
                // come back (rc2: never both at once).
                SessionRegistry.onTransferEnded()
            }
        }
        return true
    }

    /** A message the app wrote itself (already localized). */
    private class LocalizedIOException(message: String) : IOException(message)

    /** Classify a batch failure for the summary (R4). Core text is logged only;
     *  a hidden-volume block also latches the vault read-only. */
    private fun batchError(session: VaultSession, e: Exception): BatchError {
        CoreErrorMap.log("transfer", e)
        SessionRegistry.noteFailure(session, e)
        val kind = CoreErrorMap.kindOf(e)
        return when {
            kind != null -> BatchError(kind = kind)
            e is LocalizedIOException -> BatchError(text = e.message)
            else -> BatchError()
        }
    }

    // -- Import --------------------------------------------------------------

    private suspend fun runImport(
        resolver: ContentResolver,
        session: VaultSession,
        destDir: String,
        sources: List<SourceEntry>,
        askCollisions: Boolean = true,
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
        // A share keeps both without asking: the plan already does (D6).
        if (plan.collisions > 0 && askCollisions) {
            val answer = CompletableDeferred<CollisionPolicy?>()
            collisionAnswer = answer
            // cancel() can't complete a deferred that didn't exist yet.
            if (cancelRequested) {
                collisionAnswer = null
                return stoppedBeforeCopy(plan)
            }
            _state.value = State.AskCollision(plan.collisions)
            // Waiting on the user doesn't hold the auto-lock timer.
            SessionRegistry.setBatchActive(false)
            val policy = answer.await()
            collisionAnswer = null
            if (policy == null) return stoppedBeforeCopy(plan)
            SessionRegistry.setBatchActive(true)
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
                return stoppedBeforeCopy(plan)
            }
            _state.value = State.Oversize(plan.oversize)
            SessionRegistry.setBatchActive(false)
            val go = answer.await()
            oversizeAnswer = null
            if (!go) return stoppedBeforeCopy(plan)
            SessionRegistry.setBatchActive(true)
        }
        if (cancelRequested) return stoppedBeforeCopy(plan)

        var copied = 0
        var skipped = plan.skipped + plan.oversize
        var index = 0
        var bytesDone = 0L
        var sinceFlushFiles = 0
        var sinceFlushBytes = 0L
        var cancelled = false
        var error: BatchError? = null
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
            error = batchError(session, e)
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

    /** A batch that ended at a question. The user's own Cancel needs no
     *  summary; a lock that abandoned it does ("Import stopped. 0 of N files
     *  were copied."), so the user learns nothing was copied. */
    private fun stoppedBeforeCopy(plan: TransferPlan): State? {
        if (!abandoned) return null
        return State.Done(
            TransferResult(
                export = false, copied = 0, total = plan.files, skipped = 0, renamed = 0,
                keptBoth = 0, cancelled = true,
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
        val input = if (op.source.ref == SHARED_TEXT_REF) {
            sharedText?.let { ByteArrayInputStream(it) }
        } else {
            try {
                resolver.openInputStream(Uri.parse(op.source.ref))
            } catch (e: Exception) {
                null
            }
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
        if (cancelRequested) {
            // Locked before the first file: say so, as an import does.
            if (!abandoned) return null
            return State.Done(
                TransferResult(
                    export = true, copied = 0, total = sources.count { !it.isDir }, skipped = 0,
                    renamed = 0, keptBoth = 0, cancelled = true,
                ),
            )
        }
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
        var error: BatchError? = null
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
                        ) ?: throw LocalizedIOException(res.getString(R.string.transfer_err_create, op.dest.last()))
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
            error = batchError(session, e)
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
            ?: throw LocalizedIOException(res.getString(R.string.transfer_err_create, name))
        try {
            val out = resolver.openOutputStream(doc) ?: throw LocalizedIOException(res.getString(R.string.transfer_err_write, name))
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
