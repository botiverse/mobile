package build.raft.app.network

import kotlinx.serialization.json.Json

data class HttpClientConfig(
    val timeout: TimeoutConfig = TimeoutConfig(),
    val headerProvider: (() -> Map<String, String>)? = null,
    val onUnauthorized: (() -> Unit)? = null,
    val jsonConfig: JsonConfig = JsonConfig()
)

data class TimeoutConfig(
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 30_000
)

data class JsonConfig(
    val prettyPrint: Boolean = true,
    val isLenient: Boolean = true,
    val ignoreUnknownKeys: Boolean = true
)

fun JsonConfig.toKotlinxJson(): Json =
    Json {
        prettyPrint = this@toKotlinxJson.prettyPrint
        isLenient = this@toKotlinxJson.isLenient
        ignoreUnknownKeys = this@toKotlinxJson.ignoreUnknownKeys
    }
