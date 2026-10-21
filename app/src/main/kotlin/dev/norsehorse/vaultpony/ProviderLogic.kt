package dev.norsehorse.vaultpony

/*
 * Pure helpers for the writable DocumentsProvider (2.0.0 plan §4.1): open
 * modes, document ids and paths, names from other apps, thumbnail sampling,
 * and the change signal the in-app browser listens to. No Android types, so
 * all of it runs under JVM unit tests.
 */

/** How an openDocument mode string maps onto the proxy file descriptor.
 *  The six modes ParcelFileDescriptor.parseMode accepts. */
data class OpenMode(
    val read: Boolean,
    val write: Boolean,
    /** Cut the file to zero length when it is opened. */
    val truncate: Boolean,
    /** Every write lands at the current end of the file. */
    val append: Boolean,
) {
    companion object {
        fun parse(mode: String): OpenMode = when (mode) {
            "r" -> OpenMode(read = true, write = false, truncate = false, append = false)
            // "w" truncates, the same as ParcelFileDescriptor.parseMode("w").
            "w", "wt" -> OpenMode(read = false, write = true, truncate = true, append = false)
            "wa" -> OpenMode(read = false, write = true, truncate = false, append = true)
            "rw" -> OpenMode(read = true, write = true, truncate = false, append = false)
            "rwt" -> OpenMode(read = true, write = true, truncate = true, append = false)
            else -> throw IllegalArgumentException("unsupported mode: $mode")
        }
    }
}

/**
 * The provider-facing id of one unlock: 128 random bits as 32 lowercase hex
 * digits, minted when a vault is unlocked and discarded when it locks. It
 * replaces the app's stable volume id in everything other apps see, so no
 * stored document id or grant survives a lock, and none reveals which
 * container, or which volume of it (outer or hidden), was open.
 */
object ProviderTokens {
    const val LENGTH = 32

    fun mint(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(LENGTH / 2)
        random.nextBytes(bytes)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(LENGTH)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(hex[v shr 4]).append(hex[v and 0xf])
        }
        return sb.toString()
    }

    fun isToken(s: String): Boolean =
        s.length == LENGTH && s.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Document ids are "<token>:<path inside the volume>", path absolute. */
object ProviderPaths {
    fun docId(token: String, path: String): String = "$token:$path"

    /** The token part of a document id, or null when it isn't one (an id
     *  from before 2.0.0 rc2 used the stable volume id; those never resolve). */
    fun tokenOf(documentId: String): String? {
        val i = documentId.indexOf(':')
        if (i <= 0) return null
        return documentId.substring(0, i).takeIf { ProviderTokens.isToken(it) }
    }

    fun pathOf(documentId: String): String? {
        val i = documentId.indexOf(':')
        return if (i <= 0) null else documentId.substring(i + 1)
    }

    /**
     * A document id's path must be absolute and canonical. FAT directories
     * hold real "." and ".." entries, so a crafted id such as
     * "<vol>:/granted/../other" would otherwise walk out of a folder another
     * app was granted (tree access goes through [isWithin] on the raw text).
     */
    fun isCleanPath(path: String): Boolean {
        if (path == "/") return true
        if (!path.startsWith("/") || path.endsWith("/")) return false
        if ('\\' in path || '\u0000' in path) return false
        return path.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun parent(path: String): String =
        path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    fun leaf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun child(dir: String, name: String): String =
        if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

    /** [path] is [parent] itself or anywhere below it. Compared exactly:
     *  ids carry names as the filesystem lists them, and any case folding
     *  here (Unicode in particular) could disagree with the filesystem's own.
     *  Both must be clean paths. */
    fun isWithin(parent: String, path: String): Boolean {
        val p = parent.trimEnd('/')
        return path == p || path.startsWith("$p/")
    }

    fun samePath(a: String, b: String): Boolean = a == b

    /** Import temp files (".vaultpony-*.part") are never shown to, or
     *  reachable from, other apps. */
    fun isHidden(path: String): Boolean = BulkPlanner.isTempName(leaf(path))
}

/** Names other apps hand the provider go through the same rules as imports. */
object ProviderNames {

    /** FAT/exFAT-safe, and never an import temp name (those get swept). */
    fun clean(raw: String): String {
        val name = BulkPlanner.sanitizeName(raw)
        return if (BulkPlanner.isTempName(name)) BulkPlanner.sanitizeName("_$name") else name
    }

    /**
     * Give a new file the extension its MIME type implies when its name
     * doesn't already carry a matching one (what Android's own file provider
     * does: "notes" as text/plain becomes "notes.txt"). [defaultExt] is the
     * type's usual extension, null when there is none or the type is generic.
     * [extMatches] says whether an extension (no dot) belongs to the type.
     */
    fun withMimeExtension(name: String, defaultExt: String?, extMatches: (String) -> Boolean): String {
        if (defaultExt.isNullOrEmpty()) return name
        val ext = BulkPlanner.splitExtension(name).second.removePrefix(".")
        if (ext.isNotEmpty() && extMatches(ext)) return name
        return clean("$name.$defaultExt")
    }

    /** [name] if free in [existing] (case-insensitive), else keep-both
     *  "name (1).ext" like an import would. */
    fun unique(name: String, isDir: Boolean, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        return BulkPlanner.keepBothName(name, isDir) { it.lowercase() in taken }
    }
}

object Thumbnails {
    /** Largest power-of-two subsample that keeps the decoded image at least
     *  [targetW] x [targetH] (BitmapFactory.Options.inSampleSize). */
    fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        var sample = 1
        while (width / (sample * 2) >= tw && height / (sample * 2) >= th) sample *= 2
        return sample
    }

    /** Types BitmapFactory decodes. HEIF needs API 28. */
    fun supported(mime: String, sdk: Int): Boolean = when (mime.lowercase()) {
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp" -> true
        "image/heic", "image/heif" -> sdk >= 28
        else -> false
    }
}

/**
 * Something outside the in-app browser changed a vault: [dirs] are folders
 * whose listing changed, [gone] are paths that were deleted, renamed or moved
 * away (a browser inside one must reload too), [everything] covers changes
 * like a hidden-volume trip that affect every folder.
 */
data class VaultChange(
    val volumeId: String,
    val dirs: Set<String> = emptySet(),
    val gone: Set<String> = emptySet(),
    val everything: Boolean = false,
    val seq: Long = 0,
) {
    fun affects(path: String): Boolean =
        everything ||
            dirs.any { ProviderPaths.samePath(it, path) } ||
            gone.any { ProviderPaths.isWithin(it, path) }
}
