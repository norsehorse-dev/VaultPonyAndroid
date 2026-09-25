package dev.norsehorse.vaultpony.ui

import android.content.Context
import dev.norsehorse.vaultpony.R
import uniffi.vault_ffi.VaultException

/**
 * User-facing text for a failed core call. The Argon2id-era errors get
 * localized wording; everything else keeps the core's own message, as before.
 * Returns null for a cancel, which is the user's own action and needs no
 * message. [context] must be the localized one (LocalContext.current), so the
 * in-app language choice applies.
 */
internal fun vaultErrorText(context: Context, e: Throwable, fallback: String): String? =
    when (e) {
        is VaultException.Cancelled -> null
        is VaultException.KdfOutOfMemory ->
            context.getString(R.string.error_kdf_memory, e.neededMib.toInt())
        is VaultException.PasswordTooShortForPim ->
            context.getString(R.string.error_short_password_pim, e.defaultPim.toInt(), e.minLen.toInt())
        else -> e.message ?: fallback
    }
