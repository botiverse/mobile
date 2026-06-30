package build.raft.app.network.auth

import build.raft.app.network.HttpClientConfig
import build.raft.app.network.KmpHttpResponse
import build.raft.app.network.TimeoutConfig
import build.raft.app.network.createHttpClient
import build.raft.app.network.toKmpHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

class AuthApiClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = createHttpClient(HttpClientConfig(timeout = TimeoutConfig())),
    private val warningLogger: (String) -> Unit = {}
) {
    suspend fun login(body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/login", body = body)

    suspend fun completeSocialLogin(provider: String, body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/${provider.trim().lowercase()}/complete", body = body)

    suspend fun register(body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/register", body = body)

    suspend fun forgotPassword(body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/forgot-password", body = body)

    suspend fun currentUser(authorization: String): KmpHttpResponse =
        request(HttpMethod.Get, "auth/me", authorization = authorization)

    suspend fun refresh(body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/refresh", body = body)

    suspend fun logout(body: String): KmpHttpResponse =
        request(HttpMethod.Post, "auth/logout", body = body)

    suspend fun servers(authorization: String): KmpHttpResponse =
        request(HttpMethod.Get, "servers", authorization = authorization)

    suspend fun createServer(authorization: String, body: String): KmpHttpResponse =
        request(HttpMethod.Post, "servers", authorization = authorization, body = body)

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authorization: String? = null,
        body: String? = null
    ): KmpHttpResponse {
        val response =
            try {
                when (method) {
                    HttpMethod.Get ->
                        httpClient.get {
                            url(absoluteUrl(path))
                            authorization?.let { header("Authorization", it) }
                        }
                    HttpMethod.Post ->
                        httpClient.post {
                            url(absoluteUrl(path))
                            authorization?.let { header("Authorization", it) }
                            if (body != null) {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        }
                    else -> error("Unsupported HTTP method ${method.value}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                warningLogger("auth_api_failed path=$path method=${method.value} reason=${error::class.simpleName}")
                throw error
            }
        return response.toKmpHttpResponse()
    }

    private fun absoluteUrl(path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}
