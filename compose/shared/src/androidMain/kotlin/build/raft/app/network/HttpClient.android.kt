package build.raft.app.network

import io.ktor.client.*

fun createHttpClient(config: HttpClientConfig = HttpClientConfig()): HttpClient {
    return createSlockKtorHttpClient(SlockRequestPriority.Foreground, config)
}
