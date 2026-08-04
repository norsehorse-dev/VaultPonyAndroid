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

    /** Create a brand-new AES/SHA-512 container with an empty FAT filesystem
     *  at [uri] (a freshly-created, writable SAF document). */
    suspend fun createContainer(uri: Uri, sizeBytes: ULong, passphrase: String) =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("could not open the new container file")
            val fd = pfd.detachFd()
            uniffi.vault_ffi.createContainer(
                fd, sizeBytes, passphrase, 0u, emptyList(), "AES", "SHA-512",
            )
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

    /** Import a device file into [parentPath], streaming in 1 MiB chunks.
     *  Returns the name it was stored under. */
    suspend fun importFile(session: VaultSession, parentPath: String, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val name = displayName(uri)
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
