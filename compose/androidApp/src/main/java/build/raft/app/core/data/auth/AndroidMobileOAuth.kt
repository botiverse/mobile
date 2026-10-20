package build.raft.app.core.data.auth

import build.raft.app.BuildConfig
import build.raft.app.data.RaftKeyValueStore
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
    val requestId: String,
    val codeVerifier: String,
    val mode: String = AndroidMobileOAuth.ModeLogin
)

object AndroidMobileOAuth {
    const val AppEnv = "dev"
    val ReturnUri: String
        get() = "${BuildConfig.OAUTH_CALLBACK_SCHEME}://oauth/callback"
    /** Browser web login return URI; the shared [build.raft.app.data.APP_LOGIN_RETURN_URIS] allowlist. */
    val AppLoginReturnUri: String
        get() = "${BuildConfig.OAUTH_CALLBACK_SCHEME}://login/callback"
    const val ModeLogin = "login"
    const val ModeLink = "link"
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

    fun isCallbackRoute(scheme: String?, host: String?, path: String?): Boolean =
        scheme.equals(BuildConfig.OAUTH_CALLBACK_SCHEME, ignoreCase = true) &&
            host.equals("oauth", ignoreCase = true) &&
            path == "/callback"

    fun isCallbackUri(scheme: String?, host: String?, path: String?, code: String?, error: String?): Boolean =
        isCallbackRoute(scheme, host, path) &&
            (!code.isNullOrBlank() || !error.isNullOrBlank())

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

class AndroidMobileOAuthPendingStore(context: Context) {
    private val store: RaftKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, "slockMobileOAuth")

    fun save(request: AndroidMobileOAuthPendingRequest) {
        store.putString(KEY_PROVIDER, request.provider)
        store.putString(KEY_REQUEST_ID, request.requestId)
        store.putString(KEY_VERIFIER, request.codeVerifier)
        store.putString(KEY_MODE, request.mode)
    }

    fun read(): AndroidMobileOAuthPendingRequest? {
        val provider = store.getString(KEY_PROVIDER)?.takeIf { it.isNotBlank() } ?: return null
        val requestId = store.getString(KEY_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return null
        val verifier = store.getString(KEY_VERIFIER)?.takeIf { it.isNotBlank() } ?: return null
        val mode = store.getString(KEY_MODE)?.takeIf { it.isNotBlank() } ?: AndroidMobileOAuth.ModeLogin
        return AndroidMobileOAuthPendingRequest(
            provider = provider,
            requestId = requestId,
            codeVerifier = verifier,
            mode = mode
        )
    }

    fun clear() {
        store.putString(KEY_PROVIDER, null)
        store.putString(KEY_REQUEST_ID, null)
        store.putString(KEY_VERIFIER, null)
        store.putString(KEY_MODE, null)
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_REQUEST_ID = "oauthRequestId"
        const val KEY_VERIFIER = "codeVerifier"
        const val KEY_MODE = "mode"
    }
}
