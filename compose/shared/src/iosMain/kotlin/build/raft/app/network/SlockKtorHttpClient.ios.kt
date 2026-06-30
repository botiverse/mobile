package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json

internal actual fun createSlockKtorHttpClient(
    priority: SlockRequestPriority,
    config: HttpClientConfig
): HttpClient =
    HttpClient(Darwin) {
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
