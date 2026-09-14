package dev.mobileforge.core.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage for credentials: AI provider API keys, Git tokens, SSH passphrases.
 *
 * Rules this type exists to enforce (SECURITY.md, RISK-013):
 *   - Secrets never go in Room, and never in plaintext preferences.
 *   - Values are write-then-verify-only from the UI's perspective. [get] exists for the
 *     subsystem that must send the credential; the UI shows only [hasSecret].
 *   - Nothing here is ever logged, exported in diagnostics, or placed in an AI prompt.
 *   - Nothing here is reachable from WebView JavaScript.
 *
 * Phase 1 stores nothing — no provider is configured and no Git remote is authenticated. The
 * implementation exists now because building it later, under feature pressure, is how apps end
 * up with tokens in SharedPreferences.
 */
interface SecretStore {
    suspend fun put(key: String, value: String): AppResult<Unit>
    suspend fun get(key: String): AppResult<String>
    suspend fun hasSecret(key: String): Boolean
    suspend fun remove(key: String): AppResult<Unit>
    suspend fun clear(): AppResult<Unit>
}

/**
 * Android Keystore-backed [SecretStore].
 *
 * The AES key lives in the Keystore and never enters app memory in extractable form. Only the
 * ciphertext is written to disk, in a private preferences file, so a filesystem read (backup
 * extraction, a rooted device dump, an ADB pull on a debuggable build) yields nothing usable.
 *
 * Storage layout: Base64(IV || ciphertext) under an obfuscation-free, readable key name.
 * The IV is prepended rather than reused — GCM with a repeated IV under the same key is a
 * catastrophic failure, so a fresh IV is generated for every write.
 */
class KeystoreSecretStore(
    context: Context,
) : SecretStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun put(key: String, value: String): AppResult<Unit> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = iv + ciphertext
        prefs.edit { putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)) }
        AppResult.Success(Unit)
    } catch (e: Exception) {
        // Deliberately does not include the value, the key material, or the exception message
        // in anything user-visible beyond a generic detail.
        cryptoError("The credential could not be saved securely.", e)
    }

    override suspend fun get(key: String): AppResult<String> {
        val stored = prefs.getString(key, null)
            ?: return AppError(
                category = ErrorCategory.Validation,
                message = "No credential is stored for that entry.",
                recovery = "Add the credential in Settings.",
            ).asFailure()

        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_LENGTH) {
                return cryptoError("The stored credential is corrupt.", null)
            }
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).asSuccess()
        } catch (e: Exception) {
            cryptoError("The credential could not be read.", e)
        }
    }

    override suspend fun hasSecret(key: String): Boolean = prefs.contains(key)

    override suspend fun remove(key: String): AppResult<Unit> {
        prefs.edit { remove(key) }
        return AppResult.Success(Unit)
    }

    override suspend fun clear(): AppResult<Unit> {
        prefs.edit { clear() }
        return AppResult.Success(Unit)
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                // Not setUserAuthenticationRequired: a background Git fetch or a streaming AI
                // request must not stall on a biometric prompt. Revisit as an opt-in setting
                // for users who want credentials gated behind device auth.
                .build(),
        )
        return generator.generateKey()
    }

    private fun cryptoError(message: String, cause: Throwable?): AppResult<Nothing> = AppError(
        category = ErrorCategory.Security,
        message = message,
        detail = "Secure storage is unavailable or the stored value could not be decrypted.",
        recovery = "Re-enter the credential in Settings.",
        cause = cause,
    ).asFailure()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.mobileforge.secrets.v1"
        const val PREFS_NAME = "secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE = 256
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
