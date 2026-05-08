package ai.slock.android.core.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthRefreshInterceptor(
    private val sessionProvider: () -> AuthSession?,
    private val tokenRefresher: AuthTokenRefresher,
    private val logger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message) }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 401 || request.tag(AuthRetryMarker::class.java) != null) {
            return response
        }

        val failedAccessToken = request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
            ?: return response
        val refreshedSession = runBlocking {
            tokenRefresher.refreshIfCurrent(failedAccessToken)
        } ?: return response

        response.close()
        logger("api_401_retry")
        val retryRequest = request.newBuilder()
            .header("Authorization", "Bearer ${refreshedSession.accessToken}")
            .apply {
                refreshedSession.currentServerId?.let { header("X-Server-Id", it) }
                    ?: removeHeader("X-Server-Id")
            }
            .tag(AuthRetryMarker::class.java, AuthRetryMarker)
            .build()
        return try {
            chain.proceed(retryRequest)
        } catch (error: Exception) {
            warningLogger("api_401_retry_failed reason=${error::class.java.simpleName}")
            throw error
        }
    }

    private object AuthRetryMarker

    private companion object {
        const val TAG = "SlockAuth"
    }
}
