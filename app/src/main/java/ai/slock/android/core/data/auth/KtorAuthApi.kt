package ai.slock.android.core.data.auth

import ai.slock.compose.data.AuthNetworkResponse
import ai.slock.compose.data.SlockAuthTransport
import ai.slock.compose.network.HttpClientConfig
import ai.slock.compose.network.TimeoutConfig
import ai.slock.compose.network.createHttpClient
import ai.slock.compose.network.toKmpHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.coroutines.CancellationException

class KtorAuthApi(
    private val baseUrl: String = AndroidAuthApiFactory.BASE_URL,
    private val httpClient: HttpClient = createHttpClient(
        HttpClientConfig(timeout = TimeoutConfig())
    ),
    private val warningLogger: (String) -> Unit = { message ->
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
    }
) : SlockAuthTransport {
    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(method = HttpMethod.Post, path = "auth/login", body = bodyText)

    override suspend fun register(bodyText: String): AuthNetworkResponse =
        request(method = HttpMethod.Post, path = "auth/register", body = bodyText)

    override suspend fun forgotPassword(bodyText: String): AuthNetworkResponse =
        request(
            method = HttpMethod.Post,
            path = "auth/forgot-password",
            body = bodyText
        )

    override suspend fun currentUser(authorization: String): AuthNetworkResponse =
        request(method = HttpMethod.Get, path = "auth/me", authorization = authorization)

    override suspend fun refresh(bodyText: String): AuthNetworkResponse =
        request(method = HttpMethod.Post, path = "auth/refresh", body = bodyText)

    override suspend fun logout(bodyText: String): AuthNetworkResponse =
        request(method = HttpMethod.Post, path = "auth/logout", body = bodyText)

    override suspend fun servers(authorization: String): AuthNetworkResponse =
        request(method = HttpMethod.Get, path = "servers", authorization = authorization)

    override suspend fun createServer(
        authorization: String,
        bodyText: String
    ): AuthNetworkResponse =
        request(
            method = HttpMethod.Post,
            path = "servers",
            authorization = authorization,
            body = bodyText
        )

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authorization: String? = null,
        body: String? = null
    ): AuthNetworkResponse {
        val response = try {
            when (method) {
                HttpMethod.Get -> httpClient.get {
                    url(absoluteUrl(path))
                    authorization?.let { header("Authorization", it) }
                }
                HttpMethod.Post -> httpClient.post {
                    url(absoluteUrl(path))
                    authorization?.let { header("Authorization", it) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
                else -> throw IOException("Unsupported HTTP method ${method.value}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            return AuthNetworkResponse(code = 0, bodyText = null, errorBodyText = io.message)
        } catch (error: Exception) {
            warningLogger(
                "auth_api_failed path=$path method=${method.value} " +
                    "reason=${error::class.java.simpleName}"
            )
            return AuthNetworkResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }
        return response.toKmpHttpResponse().toAuthNetworkResponse()
    }

    private fun absoluteUrl(path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private companion object {
        const val TAG = "SlockAuth"
    }
}

private fun ai.slock.compose.network.KmpHttpResponse.toAuthNetworkResponse(): AuthNetworkResponse =
    AuthNetworkResponse(
        code = code,
        bodyText = bodyText,
        errorBodyText = errorBodyText
    )
