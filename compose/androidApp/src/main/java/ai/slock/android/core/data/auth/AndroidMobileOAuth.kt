package ai.slock.android.core.data.auth

import ai.slock.compose.data.SlockKeyValueStore
import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.charset.StandardCharsets

data class AndroidMobileOAuthPkce(
    val codeVerifier: String,
    val codeChallenge: String
)

data class AndroidMobileOAuthPendingRequest(
    val provider: String,
    val oauthRequestId: String,
    val codeVerifier: String
)

object AndroidMobileOAuth {
    const val ReturnTarget = "android"
    private const val VerifierByteCount = 32
    private val SupportedProviders = setOf("google", "github")

    fun isSupportedProvider(provider: String): Boolean =
        provider.trim().lowercase() in SupportedProviders

    fun createPkce(): AndroidMobileOAuthPkce {
        val randomBytes = ByteArray(VerifierByteCount)
        SecureRandom().nextBytes(randomBytes)
        val verifier = base64Url(randomBytes)
        val challenge =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        return AndroidMobileOAuthPkce(codeVerifier = verifier, codeChallenge = challenge)
    }

    fun isCallbackQuery(code: String?, error: String?, requestId: String?): Boolean =
        !requestId.isNullOrBlank() && (!code.isNullOrBlank() || !error.isNullOrBlank())

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

class AndroidMobileOAuthPendingStore(context: Context) {
    private val store: SlockKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, "slockMobileOAuth")

    fun save(request: AndroidMobileOAuthPendingRequest) {
        store.putString(KEY_PROVIDER, request.provider)
        store.putString(KEY_REQUEST_ID, request.oauthRequestId)
        store.putString(KEY_VERIFIER, request.codeVerifier)
    }

    fun read(): AndroidMobileOAuthPendingRequest? {
        val provider = store.getString(KEY_PROVIDER)?.takeIf { it.isNotBlank() } ?: return null
        val requestId = store.getString(KEY_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return null
        val verifier = store.getString(KEY_VERIFIER)?.takeIf { it.isNotBlank() } ?: return null
        return AndroidMobileOAuthPendingRequest(provider = provider, oauthRequestId = requestId, codeVerifier = verifier)
    }

    fun clear() {
        store.putString(KEY_PROVIDER, null)
        store.putString(KEY_REQUEST_ID, null)
        store.putString(KEY_VERIFIER, null)
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_REQUEST_ID = "oauthRequestId"
        const val KEY_VERIFIER = "codeVerifier"
    }
}
