package ai.slock.android.core.data.auth

import okhttp3.Interceptor
import okhttp3.Response

class AuthHeaderInterceptor(
    private val sessionProvider: () -> AuthSession?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = sessionProvider()
        val requestBuilder = chain.request().newBuilder()
        if (session != null) {
            requestBuilder.header("Authorization", "Bearer ${session.accessToken}")
            session.currentServerId?.let { requestBuilder.header("X-Server-Id", it) }
        }
        return chain.proceed(requestBuilder.build())
    }
}
