package dev.norsehorse.vaultpony

/*
 * Bulk import and export planning (2.0.0 plan §2.5, issue #3). Plain Kotlin
 * with no Android types so it runs under JVM unit tests: FAT/exFAT name
 * sanitizing, keep-both naming, collision policy, the FAT 4 GB cap and the
 * space pre-flight. TransferJobs does the I/O and owns the job.
 */

/** What to do with a name that already exists in the destination. Asked once
 *  per batch. Keep both is the default so nothing is lost (plan D6). */
enum class CollisionPolicy { KEEP_BOTH, SKIP, REPLACE }

/** One item from a source walk. [segments] is its path relative to the batch
 *  root, raw names as the source reported them; folders must come before
 *  their contents. [size] is -1 when the source didn't report one. [ref] is
 *  opaque to the planner (a content URI string, or a vault path on export). */
data class SourceEntry(
    val segments: List<String>,
    val isDir: Boolean,
    val size: Long,
    val ref: String,
)

/** A name already present in a destination folder. */
data class ExistingEntry(val name: String, val isDir: Boolean, val size: Long)

/** Destination facts for the pre-flight. A null [freeBytes] skips the space
 *  check (unknown); a null [maxFileBytes] means no per-file cap. */
data class FsLimits(
    val clusterBytes: Long = 1,
    val freeBytes: Long? = null,
    val maxFileBytes: Long? = null,
)

sealed interface TransferOp {
    /** Create a folder at [dest], segments relative to the destination root. */
    data class MakeDir(val dest: List<String>) : TransferOp

    /** Copy [source] to [dest]. [replace]: [dest] is an existing file to overwrite. */
    data class CopyFile(
        val source: SourceEntry,
        val dest: List<String>,
        val replace: Boolean,
    ) : TransferOp
}

data class TransferPlan(
    /** In walk order, so every folder is made before anything goes into it. */
    val ops: List<TransferOp>,
    /** Files that will be copied. */
    val files: Int,
    /** Names that already exist in the destination. Folders merged into an
     *  existing folder don't count; that isn't a conflict. */
    val collisions: Int,
    /** Files left out by the collision policy, including everything inside a
     *  skipped folder. */
    val skipped: Int,
    /** Files over the filesystem's per-file cap. Left out. */
    val oversize: Int,
    /** Entries whose name was changed to fit FAT/exFAT. */
    val renamed: Int,
    /** Entries stored as "name (n)" because the name was taken. */
    val keptBoth: Int,
    /** Bytes to copy, for progress. */
    val bytes: Long,
    /** Space the batch takes: each file rounded up to whole clusters, plus one
     *  cluster per new folder. A replaced file earns no credit: the new copy
     *  is written in full beside it before the old one is removed. */
    val bytesNeeded: Long,
) {
    fun fits(freeBytes: Long?): Boolean = freeBytes == null || bytesNeeded <= freeBytes
}

/** How one batch ended, for the summary. */
data class TransferResult(
    val export: Boolean,
    val copied: Int,
    val total: Int,
    val skipped: Int,
    val renamed: Int,
    val keptBoth: Int,
    val cancelled: Boolean,
    /** Set when the batch stopped on an error. */
    val error: BatchError? = null,
)

/** Why a batch stopped early (R4). [kind]: a core (vault) error, shown through
 *  the localized table; the core's own text is only logged. [text]: a message
 *  the app already localized (an export destination refusing a file). Neither
 *  set: the generic import/export failure text. */
data class BatchError(val kind: VaultErrorKind? = null, val text: String? = null)

object BulkPlanner {
    /** FAT long names and exFAT both cap a name at 255 UTF-16 units. */
    const val MAX_NAME = 255
    private const val ILLEGAL = "\\/:*?\"<>|"

    // Imports write each file to a hidden temp sibling and rename it into
    // place only once complete, so a stop never leaves a half file under a
    // real name or destroys a file being replaced. A batch killed mid-file
    // leaves the temp behind; the next import sweeps those away.
    private const val TEMP_PREFIX = ".vaultpony-"
    private const val TEMP_SUFFIX = ".part"

    /** Name of an import temp file, [token] being random per file. */
    fun tempName(token: String): String = TEMP_PREFIX + token + TEMP_SUFFIX

    /** True for an import temp file (".vaultpony-*.part"). FAT compares
     *  names without case, so neither does this. */
    fun isTempName(name: String): Boolean =
        name.length > TEMP_PREFIX.length + TEMP_SUFFIX.length &&
            name.startsWith(TEMP_PREFIX, ignoreCase = true) &&
            name.endsWith(TEMP_SUFFIX, ignoreCase = true)

    /** Make [raw] storable on FAT/exFAT: each of \ / : * ? " < > | and control
     *  characters becomes "_", trailing dots and spaces go, the length is
     *  capped keeping the extension, and an empty result becomes "_". This
     *  also turns "." and ".." into "_", so a name can never leave its folder. */
    fun sanitizeName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(if (ch.code < 0x20 || ILLEGAL.indexOf(ch) >= 0) '_' else ch)
        var name = sb.toString().trimEnd('.', ' ')
        if (name.length > MAX_NAME) name = fitLength(name).trimEnd('.', ' ')
        return name.ifEmpty { "_" }
    }

    /** "photo.jpg" -> ("photo", ".jpg"). Dotfiles and names without a dot
     *  have no extension. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /** First free "name (n).ext" (files) or "name (n)" (folders, dotfiles,
     *  names without an extension), kept within [MAX_NAME]. */
    fun keepBothName(name: String, isDir: Boolean, isTaken: (String) -> Boolean): String {
        var (base, ext) = if (isDir) name to "" else splitExtension(name)
        // An absurdly long "extension" is just part of the name.
        if (ext.length > MAX_NAME / 2) {
            base = name
            ext = ""
        }
        var n = 1
        while (true) {
            val suffix = " ($n)$ext"
            val candidate = cut(base, MAX_NAME - suffix.length) + suffix
            if (!isTaken(candidate)) return candidate
            n++
        }
    }

    /** Bytes a [size]-byte file occupies in [cluster]-byte allocation units. */
    fun onDisk(size: Long, cluster: Long): Long {
        if (size <= 0) return 0
        if (cluster <= 1) return size
        return (size + cluster - 1) / cluster * cluster
    }

    /** Vault path of [rel] under the vault folder [dir]. */
    fun vaultPath(dir: String, rel: List<String>): String {
        val base = dir.trimEnd('/')
        return if (rel.isEmpty()) base.ifEmpty { "/" } else base + "/" + rel.joinToString("/")
    }

    /**
     * Plan a batch. [listDir] lists a destination folder that already exists
     * (relative segments, empty = the destination root); it is only called
     * for the root and for folders being merged into. [mergeDirs]: a source
     * folder whose name exists as a folder is merged into it (import); when
     * false it is a collision like any other (export keeps both).
     *
     * Replace only applies file over file. A file meeting a folder, or a folder
     * meeting a file, falls back to keep both under Replace; nothing is ever
     * removed to make room for a different kind of entry.
     *
     * Names compare case-insensitively: FAT and exFAT, and Android's shared
     * storage, treat "A.txt" and "a.txt" as the same name.
     */
    fun plan(
        sources: List<SourceEntry>,
        limits: FsLimits,
        policy: CollisionPolicy,
        mergeDirs: Boolean,
        listDir: (List<String>) -> List<ExistingEntry>,
    ): TransferPlan {
        val cluster = limits.clusterBytes
        val cap = limits.maxFileBytes
        val folders = HashMap<List<String>, DestFolder>()
        folders[emptyList()] = DestFolder(listDir(emptyList()))
        // Source folder -> where it lands in the destination; null = skipped.
        val placed = HashMap<List<String>, List<String>?>()

        val ops = ArrayList<TransferOp>()
        var files = 0
        var collisions = 0
        var skipped = 0
        var oversize = 0
        var renamed = 0
        var keptBoth = 0
        var bytes = 0L
        var needed = 0L

        for (src in sources) {
            require(src.segments.isNotEmpty()) { "empty source path" }
            val srcParent = src.segments.dropLast(1)
            val parent: List<String> = if (srcParent.isEmpty()) {
                emptyList()
            } else {
                require(placed.containsKey(srcParent)) { "a folder must come before its contents" }
                val p = placed[srcParent]
                if (p == null) {
                    // Inside a skipped folder.
                    if (src.isDir) placed[src.segments] = null else skipped++
                    continue
                }
                p
            }
            if (!src.isDir && cap != null && src.size > cap) {
                oversize++
                continue
            }
            val folder = folders.getValue(parent)
            val raw = src.segments.last()
            var name = sanitizeName(raw)
            // A source that looks like a temp file would be swept by the next
            // import; store it under a name that doesn't.
            if (isTempName(name)) name = "_$name"
            if (name != raw) renamed++

            var replace = false
            var mergeInto = false
            val inBatch = key(name) in folder.planned
            val there = if (inBatch) null else folder.existing[key(name)]
            when {
                // Two sources ended up with the same name (case, or sanitizing).
                inBatch -> {
                    name = keepBothName(name, src.isDir, folder::taken)
                    keptBoth++
                }
                there == null -> Unit
                src.isDir && there.isDir && mergeDirs -> {
                    name = there.name
                    mergeInto = true
                }
                else -> {
                    collisions++
                    when {
                        policy == CollisionPolicy.SKIP -> {
                            if (src.isDir) placed[src.segments] = null else skipped++
                            continue
                        }
                        policy == CollisionPolicy.REPLACE && !src.isDir && !there.isDir -> {
                            name = there.name
                            replace = true
                        }
                        else -> {
                            name = keepBothName(name, src.isDir, folder::taken)
                            keptBoth++
                        }
                    }
                }
            }

            folder.planned += key(name)
            val dest = parent + name
            if (src.isDir) {
                placed[src.segments] = dest
                if (mergeInto) {
                    folders[dest] = DestFolder(listDir(dest))
                } else {
                    folders[dest] = DestFolder(emptyList())
                    ops += TransferOp.MakeDir(dest)
                    needed += cluster.coerceAtLeast(0)
                }
            } else {
                val size = src.size.coerceAtLeast(0)
                ops += TransferOp.CopyFile(src, dest, replace)
                files++
                bytes += size
                needed += onDisk(size, cluster)
            }
        }

        return TransferPlan(
            ops = ops,
            files = files,
            collisions = collisions,
            skipped = skipped,
            oversize = oversize,
            renamed = renamed,
            keptBoth = keptBoth,
            bytes = bytes,
            bytesNeeded = needed,
        )
    }

    private fun key(name: String) = name.lowercase()

    /** Shorten [name] to [MAX_NAME] units, keeping a sensible extension. */
    private fun fitLength(name: String): String {
        if (name.length <= MAX_NAME) return name
        val (base, ext) = splitExtension(name)
        if (ext.length > MAX_NAME / 2) return cut(name, MAX_NAME)
        return cut(base, MAX_NAME - ext.length) + ext
    }

    /** The first [n] UTF-16 units of [s], never splitting a surrogate pair. */
    private fun cut(s: String, n: Int): String {
        var end = n.coerceIn(0, s.length)
        if (end in 1 until s.length && s[end - 1].isHighSurrogate()) end--
        return s.substring(0, end)
    }

    /** A destination folder during planning: what was there, plus what this
     *  batch has already put there. Keys are case-folded. Import temp files
     *  are not real entries and never collide. */
    private class DestFolder(existing: List<ExistingEntry>) {
        val existing: Map<String, ExistingEntry> =
            existing.filterNot { isTempName(it.name) }.associateBy { key(it.name) }
        val planned = HashSet<String>()
        fun taken(name: String): Boolean = key(name).let { it in existing || it in planned }
    }
}
