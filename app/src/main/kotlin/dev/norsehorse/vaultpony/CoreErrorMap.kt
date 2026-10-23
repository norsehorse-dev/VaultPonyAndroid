package dev.norsehorse.vaultpony

import android.util.Log
import uniffi.vault_ffi.VaultException

/**
 * Bridges the generated VaultException classes to the pure [CoreErrors]
 * table (R4). Raw core text, which can name files, is logged here and goes
 * nowhere else.
 */
object CoreErrorMap {
    private const val TAG = "VaultPony"

    /** The FFI variant behind [e], or null when [e] didn't come from the core. */
    fun variantOf(e: Throwable): CoreVariant? = when (e) {
        is VaultException.NotFoundOrWrongPassword -> CoreVariant.NOT_FOUND_OR_WRONG_PASSWORD
        is VaultException.VersionTooNew -> CoreVariant.VERSION_TOO_NEW
        is VaultException.SystemVolume -> CoreVariant.SYSTEM_VOLUME
        is VaultException.HiddenVolumeProtected -> CoreVariant.HIDDEN_VOLUME_PROTECTED
        is VaultException.HeaderDamaged -> CoreVariant.HEADER_DAMAGED
        is VaultException.UnsupportedFilesystem -> CoreVariant.UNSUPPORTED_FILESYSTEM
        is VaultException.Filesystem -> CoreVariant.FILESYSTEM
        is VaultException.Io -> CoreVariant.IO
        is VaultException.Internal -> CoreVariant.INTERNAL
        is VaultException.KdfOutOfMemory -> CoreVariant.KDF_OUT_OF_MEMORY
        is VaultException.Cancelled -> CoreVariant.CANCELLED
        is VaultException.PasswordTooShortForPim -> CoreVariant.PASSWORD_TOO_SHORT_FOR_PIM
        is VaultException.EmptyKeyfile -> CoreVariant.EMPTY_KEYFILE
        is VaultException.TrueCryptReadOnly -> CoreVariant.TRUECRYPT_READ_ONLY
        is VaultException.TrueCryptVersionUnsupported -> CoreVariant.TRUECRYPT_VERSION_UNSUPPORTED
        is VaultException.Ext4Unsupported -> CoreVariant.EXT4_UNSUPPORTED
        else -> null
    }

    private fun detailOf(e: Throwable): String? = when (e) {
        is VaultException.Filesystem -> e.detail
        is VaultException.Io -> e.detail
        is VaultException.UnsupportedFilesystem -> e.name
        is VaultException.Ext4Unsupported -> e.detail
        else -> null
    }

    /** The part of [e] the user's message names: the ext4 feature an
     *  Ext4Unsupported refers to (English from the core, never a file name).
     *  Null for every other error. */
    fun messageDetail(e: Throwable): String? = (e as? VaultException.Ext4Unsupported)?.detail

    /** What to tell the user about [e]: null for a cancel, and also null when
     *  [e] isn't a core error at all (the caller decides those). */
    fun kindOf(e: Throwable): VaultErrorKind? {
        val variant = variantOf(e) ?: return null
        return CoreErrors.classify(variant, detailOf(e))
    }

    /** Log the raw text of a failure. Never shown in the UI. */
    fun log(where: String, e: Throwable) {
        Log.w(TAG, "$where: ${e.javaClass.simpleName}: ${detailOf(e) ?: e.message.orEmpty()}")
    }
}
