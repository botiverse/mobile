package build.raft.app.network

import io.ktor.client.HttpClient

internal expect fun createSlockKtorHttpClient(
    priority: SlockRequestPriority,
    config: HttpClientConfig = HttpClientConfig()
): HttpClient
