package dev.norsehorse.vaultpony

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.vault_ffi.KdfFilter

/**
 * Optional, opt-in biometric unlock, per container (doc §8/§11). The secret
 * ({kdf, TrueCrypt mode, pim, passphrase}) is encrypted under a biometric-gated AndroidKeyStore key
 * — StrongBox-backed when the device has it — and every use requires a fresh
 * BiometricPrompt with a CryptoObject, so the key material never leaves secure
 * hardware in usable form.
 *
 * Deliberately narrow: it is NEVER offered when a keyfile was used (that would
 * nullify the "something you have" factor), and the UI warns against enabling
 * it for hidden/deniable volumes — a stored passphrase is a persistent trace,
 * and a fingerprint can be compelled far more easily than a password. Changing
 * the device's enrolled biometrics invalidates the key and wipes every stored
 * secret (setInvalidatedByBiometricEnrollment).
 */
object BiometricUnlock {
    private const val KEY_ALIAS = "vaultpony_bio_key"
    private const val PREFS = "vaultpony_bio"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/" +
            KeyProperties.ENCRYPTION_PADDING_NONE
    private const val GCM_TAG_BITS = 128

    /** True when the device has usable strong biometrics enrolled. */
    fun canUse(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun hasEnrollment(context: Context, volumeId: String): Boolean =
        prefs(context).contains("$volumeId.ct")

    fun clear(context: Context, volumeId: String) {
        prefs(context).edit().remove("$volumeId.ct").remove("$volumeId.iv").apply()
    }

    /** Remove every stored biometric unlock except those of [keepIds]
     *  (vault list "Don't keep", plan §5.4). */
    fun clearAllExcept(context: Context, keepIds: Set<String>) {
        val p = prefs(context)
        val edit = p.edit()
        for (key in p.all.keys) {
            val id = key.substringBeforeLast('.')
            if (id !in keepIds) edit.remove(key)
        }
        edit.apply()
    }

    /** Authenticate, then store the secret encrypted. [kdf] is the KDF family
     *  the container turned out to use, so a biometric unlock can skip the
     *  other family's work (for Argon2id, the whole PBKDF2 sweep).
     *  [truecrypt]: it opened in TrueCrypt mode, so the biometric unlock uses
     *  that mode too (2.0.0 rc5, plan 6.2). [onDone] receives success. */
    fun enroll(
        activity: FragmentActivity,
        volumeId: String,
        passphrase: String,
        pim: UInt,
        kdf: KdfFilter = KdfFilter.AUTO,
        truecrypt: Boolean = false,
        onDone: (Boolean) -> Unit,
    ) {
        val cipher = try {
            encryptCipher()
        } catch (e: Exception) {
            onDone(false)
            return
        }
        authenticate(activity, "Enable biometric unlock", cipher) { c ->
            if (c == null) {
                onDone(false)
                return@authenticate
            }
            try {
                val plain = encodeSecret(kdf, truecrypt, pim, passphrase).toByteArray(Charsets.UTF_8)
                val ct = c.doFinal(plain)
                prefs(activity).edit()
                    .putString("$volumeId.iv", b64(c.iv))
                    .putString("$volumeId.ct", b64(ct))
                    .apply()
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    /** Authenticate, decrypt, and hand back (pim, passphrase, kdf,
     *  TrueCrypt mode). */
    fun retrieve(
        activity: FragmentActivity,
        volumeId: String,
        onSecret: (UInt, String, KdfFilter, Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val p = prefs(activity)
        val ivB64 = p.getString("$volumeId.iv", null)
        val ctB64 = p.getString("$volumeId.ct", null)
        if (ivB64 == null || ctB64 == null) {
            onError("no biometric enrollment for this container")
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadKey() ?: run { onError("biometric key missing"); return },
                GCMParameterSpec(GCM_TAG_BITS, unb64(ivB64)),
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometrics changed on the device — the stored secret is dead.
            deleteKey()
            clear(activity, volumeId)
            onError("biometrics changed; re-enable biometric unlock")
            return
        } catch (e: Exception) {
            onError("biometric key error")
            return
        }
        authenticate(activity, "Unlock VaultPony", cipher) { c ->
            if (c == null) {
                onError("authentication cancelled")
                return@authenticate
            }
            try {
                val plain = c.doFinal(unb64(ctB64)).toString(Charsets.UTF_8)
                val secret = decodeSecret(plain)
                onSecret(secret.pim, secret.passphrase, secret.kdf, secret.truecrypt)
            } catch (e: Exception) {
                onError("could not decrypt stored secret")
            }
        }
    }

    private class Secret(val kdf: KdfFilter, val truecrypt: Boolean, val pim: UInt, val passphrase: String)

    /** Current format (2.0.0 rc5): "v3\n<kdf>\n<truecrypt 0|1>\n<pim>\n<passphrase>".
     *  The passphrase goes last so any character in it survives. */
    private fun encodeSecret(kdf: KdfFilter, truecrypt: Boolean, pim: UInt, passphrase: String): String =
        "$SECRET_V3\n${kdf.name}\n${if (truecrypt) 1 else 0}\n$pim\n$passphrase"

    /** Reads the current format, "v2\n<kdf>\n<pim>\n<passphrase>" from 1.1.0
     *  to 2.0.0 rc4, and the original "<pim>\n<passphrase>" that enrollments
     *  made before 1.1.0 carry (those unlock with Auto). Older formats read as
     *  not TrueCrypt. */
    private fun decodeSecret(plain: String): Secret {
        val nl = plain.indexOf('\n')
        val tag = plain.substring(0, nl)
        if (tag == SECRET_V3) {
            val kdfEnd = plain.indexOf('\n', nl + 1)
            val tcEnd = plain.indexOf('\n', kdfEnd + 1)
            val pimEnd = plain.indexOf('\n', tcEnd + 1)
            val kdf = kdfNamed(plain.substring(nl + 1, kdfEnd))
            val truecrypt = plain.substring(kdfEnd + 1, tcEnd) == "1"
            val pim = plain.substring(tcEnd + 1, pimEnd).toUIntOrNull() ?: 0u
            return Secret(kdf, truecrypt, pim, plain.substring(pimEnd + 1))
        }
        if (tag == SECRET_V2) {
            val kdfEnd = plain.indexOf('\n', nl + 1)
            val pimEnd = plain.indexOf('\n', kdfEnd + 1)
            val kdf = kdfNamed(plain.substring(nl + 1, kdfEnd))
            val pim = plain.substring(kdfEnd + 1, pimEnd).toUIntOrNull() ?: 0u
            return Secret(kdf, false, pim, plain.substring(pimEnd + 1))
        }
        val pim = tag.toUIntOrNull() ?: 0u
        return Secret(KdfFilter.AUTO, false, pim, plain.substring(nl + 1))
    }

    private fun kdfNamed(name: String): KdfFilter =
        KdfFilter.entries.firstOrNull { it.name == name } ?: KdfFilter.AUTO

    private const val SECRET_V2 = "v2"
    private const val SECRET_V3 = "v3"

    private fun authenticate(
        activity: FragmentActivity,
        title: String,
        cipher: Cipher,
        onResult: (Cipher?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    onResult(null)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("VaultPony")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun encryptCipher(): Cipher {
        val c = Cipher.getInstance(TRANSFORM)
        try {
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        return c
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun loadKey(): SecretKey? =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun deleteKey() {
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing usable to do; a fresh key will be generated next.
        }
    }

    private fun getOrCreateKey(): SecretKey = loadKey() ?: generateKey(strongBox = true)

    private fun generateKey(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        return try {
            gen.init(spec)
            gen.generateKey()
        } catch (e: Exception) {
            // This device has no StrongBox — fall back to TEE-backed keys.
            if (strongBox) generateKey(strongBox = false) else throw e
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
