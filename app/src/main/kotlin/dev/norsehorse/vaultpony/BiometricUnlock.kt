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

/**
 * Optional, opt-in biometric unlock, per container (doc §8/§11). The secret
 * ({pim, passphrase}) is encrypted under a biometric-gated AndroidKeyStore key
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

    /** Authenticate, then store the secret encrypted. [onDone] receives success. */
    fun enroll(
        activity: FragmentActivity,
        volumeId: String,
        passphrase: String,
        pim: UInt,
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
                val plain = "$pim\n$passphrase".toByteArray(Charsets.UTF_8)
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

    /** Authenticate, decrypt, and hand back (pim, passphrase). */
    fun retrieve(
        activity: FragmentActivity,
        volumeId: String,
        onSecret: (UInt, String) -> Unit,
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
                val nl = plain.indexOf('\n')
                val pim = plain.substring(0, nl).toUIntOrNull() ?: 0u
                val pass = plain.substring(nl + 1)
                onSecret(pim, pass)
            } catch (e: Exception) {
                onError("could not decrypt stored secret")
            }
        }
    }

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
