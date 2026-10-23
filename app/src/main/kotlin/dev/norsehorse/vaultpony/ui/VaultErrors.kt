package dev.norsehorse.vaultpony.ui

import android.content.Context
import android.content.res.Resources
import dev.norsehorse.vaultpony.BatchError
import dev.norsehorse.vaultpony.CoreErrorMap
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.SessionRegistry
import dev.norsehorse.vaultpony.TransferResult
import dev.norsehorse.vaultpony.VaultErrorKind
import uniffi.vault_ffi.VaultException
import uniffi.vault_ffi.VaultSession

/**
 * User-facing text for a failed core call. The errors that carry numbers or a
 * detail get their own wording; every other core error goes through the
 * localized table (R4), like [vaultFailureText]. A non-core failure keeps its
 * own message, or [fallback] when it has none. Returns null for a cancel,
 * which is the user's own action and needs no message. [context] must be the
 * localized one (LocalContext.current), so the in-app language choice applies.
 */
internal fun vaultErrorText(context: Context, e: Throwable, fallback: String): String? =
    when (e) {
        is VaultException.Cancelled -> null
        is VaultException.KdfOutOfMemory ->
            context.getString(R.string.error_kdf_memory_hash, e.neededMib.toInt())
        is VaultException.PasswordTooShortForPim ->
            context.getString(R.string.error_short_password_pim, e.defaultPim.toInt(), e.minLen.toInt())
        is VaultException.EmptyKeyfile -> context.getString(R.string.err_empty_keyfile)
        // TrueCrypt mode and ext4 (2.0.0 rc5, plan 6.2, 6.3).
        is VaultException.TrueCryptVersionUnsupported -> context.getString(R.string.err_truecrypt_version)
        is VaultException.TrueCryptReadOnly -> context.getString(R.string.err_truecrypt_read_only)
        is VaultException.Ext4Unsupported -> context.getString(R.string.err_ext4_unsupported, e.detail)
        // UniFFI gives the variants without fields an empty message (a wrong
        // password showed a blank error) and the others "detail=...", so a
        // core error's own text is only logged (2.0.0 rc5). The caller's
        // [fallback] ("Unlock failed" and so on) says more than the table's
        // generic line for the rest.
        is VaultException -> {
            CoreErrorMap.log("vault", e)
            CoreErrorMap.kindOf(e)
                ?.takeIf { it != VaultErrorKind.OTHER }
                ?.text(context.resources, CoreErrorMap.messageDetail(e))
                ?: fallback
        }
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }

/** The localized string for a classified core error (R4). */
internal fun VaultErrorKind.textRes(): Int = when (this) {
    VaultErrorKind.HIDDEN_PROTECTED -> R.string.err_hidden_protected
    VaultErrorKind.NOT_FOUND -> R.string.err_not_found
    VaultErrorKind.FILESYSTEM -> R.string.err_filesystem
    VaultErrorKind.IO -> R.string.err_io
    VaultErrorKind.WRONG_PASSWORD -> R.string.err_wrong_password
    VaultErrorKind.UNSUPPORTED_FS -> R.string.err_unsupported_fs
    VaultErrorKind.READ_ONLY -> R.string.err_read_only
    VaultErrorKind.FULL -> R.string.err_vault_full
    VaultErrorKind.EMPTY_KEYFILE -> R.string.err_empty_keyfile
    VaultErrorKind.TRUECRYPT_READ_ONLY -> R.string.err_truecrypt_read_only
    VaultErrorKind.TRUECRYPT_TOO_OLD -> R.string.err_truecrypt_version
    VaultErrorKind.EXT4_UNSUPPORTED -> R.string.err_ext4_unsupported
    VaultErrorKind.OTHER -> R.string.err_vault_generic
}

/** The text for [kind]. EXT4_UNSUPPORTED names the feature: [detail] fills
 *  its %1$s (2.0.0 rc5, plan 6.3). */
internal fun VaultErrorKind.text(res: Resources, detail: String? = null): String =
    if (this == VaultErrorKind.EXT4_UNSUPPORTED) {
        res.getString(R.string.err_ext4_unsupported, detail.orEmpty())
    } else {
        res.getString(textRes())
    }

/**
 * Text for a failure inside an unlocked vault (browser actions, imports,
 * exports): core errors through the localized table, anything else the
 * caller's [fallback]. The raw message goes to the log only. With [session],
 * a write blocked by hidden-volume protection also latches the vault
 * read-only in the app.
 */
internal fun vaultFailureText(
    res: Resources,
    e: Throwable,
    fallback: String,
    session: VaultSession? = null,
    where: String = "vault",
): String {
    CoreErrorMap.log(where, e)
    if (session != null) SessionRegistry.noteFailure(session, e)
    val kind = CoreErrorMap.kindOf(e) ?: return fallback
    return kind.text(res, CoreErrorMap.messageDetail(e))
}

/** One line for a batch that stopped on an error. [forNotification]: app
 *  text that can carry a file name ("Could not create %1$s") becomes the
 *  generic [fallback]; notifications never name files. */
internal fun batchErrorText(res: Resources, error: BatchError, fallback: String, forNotification: Boolean = false): String =
    error.kind?.let { it.text(res, error.detail) }
        ?: error.text?.takeIf { it.isNotBlank() && !forNotification }
        ?: fallback

/** Summary of a finished batch: the headline count (or how far a stopped one
 *  got), then any skipped / renamed / kept-both lines, then the error. Used
 *  by the in-app dialog and the "finished" notification, so they match. */
internal fun transferSummaryLines(
    res: Resources,
    r: TransferResult,
    fallbackError: String,
    forNotification: Boolean = false,
): List<String> {
    val lines = ArrayList<String>()
    val stopped = r.cancelled || (r.error != null && r.total > 0)
    lines += when {
        stopped && r.export -> res.getString(R.string.transfer_export_stopped, r.copied, r.total)
        stopped -> res.getString(R.string.transfer_import_stopped, r.copied, r.total)
        r.export -> res.getQuantityString(R.plurals.transfer_exported, r.copied, r.copied)
        else -> res.getQuantityString(R.plurals.transfer_imported, r.copied, r.copied)
    }
    if (r.skipped > 0) lines += res.getQuantityString(R.plurals.transfer_skipped, r.skipped, r.skipped)
    if (r.renamed > 0) lines += res.getQuantityString(R.plurals.transfer_renamed, r.renamed, r.renamed)
    if (r.keptBoth > 0) lines += res.getQuantityString(R.plurals.transfer_kept_both, r.keptBoth, r.keptBoth)
    r.error?.let { lines += batchErrorText(res, it, fallbackError, forNotification) }
    return lines
}
