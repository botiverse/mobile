package ai.slock.compose.network

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Configuration for HTTP client
 */
data class HttpClientConfig(
    val timeout: TimeoutConfig = TimeoutConfig(),
    val headerProvider: (() -> Map<String, String>)? = null,
    val onUnauthorized: (() -> Unit)? = null,
    val jsonConfig: JsonConfig = JsonConfig()
)

/**
 * Timeout configuration
 */
data class TimeoutConfig(
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 30_000
)

/**
 * JSON serialization configuration
 */
data class JsonConfig(
    val prettyPrint: Boolean = true,
    val isLenient: Boolean = true,
    val ignoreUnknownKeys: Boolean = true
)

/**
 * Creates a configured Ktor HTTP client for cross-platform use
 */
fun createHttpClient(config: HttpClientConfig = HttpClientConfig()): HttpClient {
    return HttpClient {
        // Timeout configuration
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout.requestTimeoutMillis
            connectTimeoutMillis = config.timeout.connectTimeoutMillis
            socketTimeoutMillis = config.timeout.socketTimeoutMillis
        }

        // JSON content negotiation
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = config.jsonConfig.prettyPrint
                isLenient = config.jsonConfig.isLenient
                ignoreUnknownKeys = config.jsonConfig.ignoreUnknownKeys
            })
        }

        // Default request configuration
        defaultRequest {
            // Inject headers from provider
            config.headerProvider?.invoke()?.forEach { (key, value) ->
                header(key, value)
            }
        }

        // Response validation and error handling
        HttpResponseValidator {
            validateResponse { response ->
                if (response.status == HttpStatusCode.Unauthorized) {
                    config.onUnauthorized?.invoke()
                }
            }
        }
    }
}
