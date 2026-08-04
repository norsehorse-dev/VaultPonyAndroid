package dev.norsehorse.vaultpony

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.DirEntry
import uniffi.vault_ffi.UnlockProgressListener
import uniffi.vault_ffi.VaultSession

/** Progress snapshot for the unlock UI (per-PRF, doc §6). */
data class UnlockProgress(val step: UInt, val total: UInt, val prf: String)

/**
 * The one place the app touches the FFI. The fd contract: dup via
 * ParcelFileDescriptor, detach, hand over — the session owns and closes it;
 * the core never sees SAF (doc §5).
 */
class VaultRepository(private val context: Context) {

    suspend fun unlock(
        uri: Uri,
        passphrase: String,
        pim: UInt,
        keyfiles: List<ByteArray> = emptyList(),
        onProgress: (UnlockProgress) -> Unit,
    ): VaultSession = withContext(Dispatchers.IO) {
        // Open read-write so the volume can be modified; fall back to
        // read-only when the source (or its SAF grant) doesn't allow writes.
        val pfd = (
            runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
                ?: context.contentResolver.openFileDescriptor(uri, "r")
            ) ?: throw IllegalStateException("could not open container")
        val fd = pfd.detachFd()
        val listener = object : UnlockProgressListener {
            override fun onProgress(step: UInt, total: UInt, prf: String) {
                onProgress(UnlockProgress(step, total, prf))
            }
        }
        val session = VaultSession.unlockFd(fd, passphrase, pim, keyfiles, listener)
        SessionRegistry.put(volumeId(uri), session)
        session
    }

    /** Open the OUTER volume of a hidden container read-write while
     *  write-protecting the hidden region (doc §9). Both passwords are
     *  required; the hidden one is used only to locate the bytes to shield.
     *  Any write that would hit the hidden volume is refused and the session
     *  latches read-only. Opens the fd read-write (protection is pointless
     *  read-only) and fails if the source can't be written. */
    suspend fun unlockOuterProtected(
        uri: Uri,
        outerPassphrase: String,
        hiddenPassphrase: String,
        pim: UInt,
        onProgress: (UnlockProgress) -> Unit,
    ): VaultSession = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("this container must be opened read-write to protect it")
        val fd = pfd.detachFd()
        val listener = object : UnlockProgressListener {
            override fun onProgress(step: UInt, total: UInt, prf: String) {
                onProgress(UnlockProgress(step, total, prf))
            }
        }
        val session = VaultSession.unlockOuterProtectedFd(
            fd, outerPassphrase, hiddenPassphrase, pim, listener,
        )
        SessionRegistry.put(volumeId(uri), session)
        session
    }

    /** Create a brand-new container with an empty FAT filesystem at [uri] (a
     *  freshly-created, writable SAF document). [scheme]/[hash] are core
     *  registry names (e.g. "AES", "Serpent(AES)", "SHA-512"). */
    suspend fun createContainer(
        uri: Uri,
        sizeBytes: ULong,
        passphrase: String,
        scheme: String = "AES",
        hash: String = "SHA-512",
        filesystem: String = "FAT",
    ) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("could not open the new container file")
        val fd = pfd.detachFd()
        uniffi.vault_ffi.createContainer(
            fd, sizeBytes, passphrase, 0u, emptyList(), scheme, hash, filesystem,
        )
    }

    /** Available cipher schemes, hashes, and filesystems for new containers,
     *  straight from the core so the picker never drifts from what creation
     *  accepts. */
    fun encryptionSchemes(): List<String> = uniffi.vault_ffi.encryptionSchemes()
    fun hashes(): List<String> = uniffi.vault_ffi.hashes()
    fun filesystems(): List<String> = uniffi.vault_ffi.filesystems()

    /** Create a container that conceals a hidden volume inside the outer one
     *  (doc §9). The outer volume records no trace of the hidden one, so the
     *  outer password alone is fully deniable. [hiddenSizeBytes] is carved from
     *  the tail of the outer volume. */
    suspend fun createHiddenContainer(
        uri: Uri,
        sizeBytes: ULong,
        outerPassphrase: String,
        hiddenPassphrase: String,
        hiddenSizeBytes: ULong,
        scheme: String = "AES",
        hash: String = "SHA-512",
        filesystem: String = "FAT",
    ) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("could not open the new container file")
        val fd = pfd.detachFd()
        uniffi.vault_ffi.createHiddenContainer(
            fd, sizeBytes, outerPassphrase, hiddenPassphrase, 0u, hiddenSizeBytes,
            scheme, hash, filesystem,
        )
    }

    // -- Header backup & recovery (doc §6). A container's header holds the
    // keys; if it's damaged the vault won't open. Backup exports the 128 KiB
    // header group; restore rewrites the primary header after verifying the
    // password, never before.

    /** Export [uri]'s header backup and write it to [dest] (a SAF document). */
    suspend fun saveHeaderBackup(uri: Uri, dest: Uri) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("could not open the container")
        val bytes = uniffi.vault_ffi.exportHeaderBackup(pfd.detachFd())
        context.contentResolver.openOutputStream(dest)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IllegalStateException("could not open the destination")
    }

    /** Restore [uri]'s primary header from its own embedded backup. Keyfiles,
     *  if any, are folded into the password exactly as unlock does. */
    suspend fun restoreHeaderFromEmbedded(
        uri: Uri,
        password: String,
        pim: UInt,
        keyfiles: List<ByteArray> = emptyList(),
    ) =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("this container must be opened read-write to restore")
            uniffi.vault_ffi.restoreHeaderFromEmbedded(pfd.detachFd(), password, pim, keyfiles)
        }

    /** Change [uri]'s password/PIM in place — the data is untouched, only the
     *  header is re-encrypted under the new password. The old password selects
     *  the volume and is verified before anything is written. */
    suspend fun changePassword(
        uri: Uri,
        oldPassword: String,
        oldPim: UInt,
        newPassword: String,
        newPim: UInt,
    ) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("this container must be opened read-write to change its password")
        uniffi.vault_ffi.changePassword(
            pfd.detachFd(), oldPassword, oldPim, emptyList(), newPassword, newPim, emptyList(),
        )
    }

    /** Restore [uri]'s primary header from an exported backup file [backupUri].
     *  Keyfiles, if any, are folded into the password exactly as unlock does. */
    suspend fun restoreHeaderFromFile(
        uri: Uri,
        backupUri: Uri,
        password: String,
        pim: UInt,
        keyfiles: List<ByteArray> = emptyList(),
    ) =
        withContext(Dispatchers.IO) {
            // A valid backup is exactly 128 KiB; read a little more so a wrong
            // file fails the size check in the core rather than ballooning here.
            val backup = context.contentResolver.openInputStream(backupUri)?.use { input ->
                val buf = ByteArray(256 * 1024)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                buf.copyOf(total)
            } ?: throw IllegalStateException("could not read the backup file")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("this container must be opened read-write to restore")
            uniffi.vault_ffi.restoreHeaderFromFile(pfd.detachFd(), backup, password, pim, keyfiles)
        }

    suspend fun list(session: VaultSession, path: String): List<DirEntry> =
        withContext(Dispatchers.IO) { session.list(path) }

    // -- Write operations. Each flushes so the encrypted backing store is
    // consistent before we report success and refresh the listing.

    suspend fun mkdir(session: VaultSession, path: String) = withContext(Dispatchers.IO) {
        session.mkdir(path)
        session.flush()
    }

    suspend fun rename(session: VaultSession, from: String, to: String) =
        withContext(Dispatchers.IO) {
            session.rename(from, to)
            session.flush()
        }

    suspend fun remove(session: VaultSession, path: String) = withContext(Dispatchers.IO) {
        session.remove(path)
        session.flush()
    }

    /** Stream a file out of the vault to a device destination (a SAF document
     *  the caller created), decrypting in 1 MiB chunks — never a temp file. */
    suspend fun exportFile(session: VaultSession, path: String, size: ULong, dest: Uri) =
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(dest)
                ?: throw IllegalStateException("could not open the destination")
            out.use { stream ->
                var offset = 0uL
                while (offset < size) {
                    val chunk = session.readAt(path, offset, CHUNK)
                    if (chunk.isEmpty()) break
                    stream.write(chunk)
                    offset += chunk.size.toUInt()
                }
                stream.flush()
            }
        }

    /** Import a device file into [parentPath], streaming in 1 MiB chunks.
     *  Returns the name it was stored under. */
    suspend fun importFile(session: VaultSession, parentPath: String, uri: Uri): String =
        withContext(Dispatchers.IO) {
            // The display name comes from an arbitrary source content provider,
            // so sanitize it before it becomes a path: a name with "/" or ".."
            // could otherwise redirect the write elsewhere in the vault.
            val name = sanitizeEntryName(displayName(uri))
            val dest = if (parentPath == "/") "/$name" else "$parentPath/$name"
            session.create(dest)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "could not open source file" }
                val buf = ByteArray(CHUNK.toInt())
                var offset = 0uL
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    session.writeAt(dest, offset, chunk)
                    offset += n.toUInt()
                }
            }
            session.flush()
            name
        }

    /** Reduce an untrusted display name to a single safe path segment: no
     *  separators, no parent refs. Cannot escape the destination directory. */
    private fun sanitizeEntryName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        return when {
            base.isEmpty() || base == "." || base == ".." -> "imported.bin"
            else -> base.replace('/', '_').replace('\\', '_')
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported.bin"
    }

    suspend fun readAll(session: VaultSession, path: String, size: ULong): ByteArray =
        withContext(Dispatchers.IO) {
            val out = ByteArray(size.toInt())
            var offset = 0uL
            while (offset < size) {
                val chunk = session.readAt(path, offset, CHUNK)
                if (chunk.isEmpty()) break
                chunk.copyInto(out, offset.toInt())
                offset += chunk.size.toUInt()
            }
            out
        }

    companion object {
        private val CHUNK = (1u shl 20)

        /** Stable id for the registry + DocumentsProvider doc ids. */
        fun volumeId(uri: Uri): String = uri.toString().hashCode().toUInt().toString(16)
    }
}
