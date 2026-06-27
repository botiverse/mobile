package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import okhttp3.Dispatcher

fun createPriorityHttpClient(
    config: HttpClientConfig = HttpClientConfig(),
    maxRequests: Int,
    maxRequestsPerHost: Int
): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(
                    Dispatcher().apply {
                        this.maxRequests = maxRequests
                        this.maxRequestsPerHost = maxRequestsPerHost
                    }
                )
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout.requestTimeoutMillis
            connectTimeoutMillis = config.timeout.connectTimeoutMillis
            socketTimeoutMillis = config.timeout.socketTimeoutMillis
        }

        install(ContentNegotiation) {
            json(config.jsonConfig.toKtorJson())
        }

        defaultRequest {
            config.headerProvider?.invoke()?.forEach { (key, value) ->
                header(key, value)
            }
        }

        HttpResponseValidator {
            validateResponse { response ->
                if (response.status == HttpStatusCode.Unauthorized) {
                    config.onUnauthorized?.invoke()
                }
            }
        }
    }
