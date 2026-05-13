package ai.slock.compose.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val selectedServerId: String? = null
) {
    val bearerAuthorization: String
        get() = "Bearer $accessToken"
}

interface AuthTokenProvider {
    suspend fun currentTokens(): AuthTokens?

    suspend fun refreshTokens(failedAccessToken: String): AuthTokens?

    suspend fun onUnauthorized(failedAccessToken: String?)
}

class AuthenticatedKtorTransport(
    private val tokenProvider: AuthTokenProvider,
    private val logger: (String) -> Unit = {}
) {
    suspend fun executeWithAuthRetry(
        request: suspend (AuthTokens?) -> HttpResponse
    ): HttpResponse {
        val tokens = tokenProvider.currentTokens()
        val failedAccessToken = tokens?.accessToken
        val response = request(tokens)
        if (response.status != HttpStatusCode.Unauthorized || failedAccessToken.isNullOrBlank()) {
            return response
        }

        val refreshed = tokenProvider.refreshTokens(failedAccessToken)
        if (refreshed == null) {
            return response
        }

        logger("auth_retry_after_401")
        val retryResponse = request(refreshed)
        if (retryResponse.status == HttpStatusCode.Unauthorized) {
            tokenProvider.onUnauthorized(refreshed.accessToken)
        }
        return retryResponse
    }
}

fun HttpRequestBuilder.applyAuthTokens(tokens: AuthTokens?) {
    headers.remove(HttpHeaders.Authorization)
    headers.remove(HEADER_SERVER_ID)
    if (tokens == null) return
    header(HttpHeaders.Authorization, tokens.bearerAuthorization)
    tokens.selectedServerId?.let { header(HEADER_SERVER_ID, it) }
}

const val HEADER_SERVER_ID: String = "X-Server-Id"
