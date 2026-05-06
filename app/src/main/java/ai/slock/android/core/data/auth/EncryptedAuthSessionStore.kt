package ai.slock.android.core.data.auth

import android.content.Context
import android.util.Log
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedAuthSessionStore(context: Context) : AuthSessionStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun readSession(): AuthSession? {
        val encrypted = preferences.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            gson.fromJson(decrypt(encrypted), AuthSession::class.java)
        }.getOrElse {
            Log.w(TAG, "Stored auth session could not be decrypted; clearing local session.")
            clearSession()
            null
        }
    }

    override suspend fun saveSession(session: AuthSession) {
        preferences.edit()
            .putString(KEY_SESSION, encrypt(gson.toJson(session)))
            .commit()
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = readSession() ?: return
        saveSession(
            current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        )
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val current = readSession() ?: return
        saveSession(
            current.copy(
                selectedServerId = serverId,
                selectedServerSlug = serverSlug
            )
        )
    }

    override suspend fun clearSession() {
        preferences.edit().remove(KEY_SESSION).commit()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = EncryptedPayload(
            iv = cipher.iv.base64(),
            data = cipherText.base64()
        )
        return gson.toJson(payload)
    }

    private fun decrypt(encrypted: String): String {
        val payload = gson.fromJson(encrypted, EncryptedPayload::class.java)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv.fromBase64())
        )
        return cipher.doFinal(payload.data.fromBase64()).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        keyGenerator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun ByteArray.base64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)

    private data class EncryptedPayload(
        val iv: String,
        val data: String
    )

    companion object {
        private const val PREFERENCES_NAME = "slock_auth_session"
        private const val KEY_SESSION = "session"
        private const val KEY_ALIAS = "slock_auth_session_key"
        private const val TAG = "SlockAuthSession"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
