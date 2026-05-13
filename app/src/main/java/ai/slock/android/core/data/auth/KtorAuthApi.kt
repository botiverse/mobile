package ai.slock.android.core.data.auth

import ai.slock.android.core.data.network.ApiResponse
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
import okhttp3.RequestBody
import okio.Buffer

class KtorAuthApi(
    private val baseUrl: String = KtorAuthRepository.BASE_URL,
    private val httpClient: HttpClient = createHttpClient(
        HttpClientConfig(timeout = TimeoutConfig())
    ),
    private val warningLogger: (String) -> Unit = { message ->
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
    }
) : AuthApi {
    override suspend fun login(body: RequestBody): ApiResponse =
        request(method = HttpMethod.Post, path = "auth/login", body = body.bodyAsString())

    override suspend fun register(body: RequestBody): ApiResponse =
        request(method = HttpMethod.Post, path = "auth/register", body = body.bodyAsString())

    override suspend fun forgotPassword(body: RequestBody): ApiResponse =
        request(
            method = HttpMethod.Post,
            path = "auth/forgot-password",
            body = body.bodyAsString()
        )

    override suspend fun currentUser(authorization: String): ApiResponse =
        request(method = HttpMethod.Get, path = "auth/me", authorization = authorization)

    override suspend fun refresh(body: RequestBody): ApiResponse =
        request(method = HttpMethod.Post, path = "auth/refresh", body = body.bodyAsString())

    override suspend fun logout(body: RequestBody): ApiResponse =
        request(method = HttpMethod.Post, path = "auth/logout", body = body.bodyAsString())

    override suspend fun servers(authorization: String): ApiResponse =
        request(method = HttpMethod.Get, path = "servers", authorization = authorization)

    override suspend fun createServer(
        authorization: String,
        body: RequestBody
    ): ApiResponse =
        request(
            method = HttpMethod.Post,
            path = "servers",
            authorization = authorization,
            body = body.bodyAsString()
        )

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authorization: String? = null,
        body: String? = null
    ): ApiResponse {
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
            throw io
        } catch (error: Exception) {
            warningLogger(
                "auth_api_failed path=$path method=${method.value} " +
                    "reason=${error::class.java.simpleName}"
            )
            throw IOException(error)
        }
        return ApiResponse.from(response.toKmpHttpResponse())
    }

    private fun absoluteUrl(path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun RequestBody.bodyAsString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        const val TAG = "SlockAuth"
    }
}
