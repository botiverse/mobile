package ai.slock.compose.network

import io.ktor.client.HttpClient

internal actual fun createSlockKtorHttpClient(
    priority: SlockRequestPriority,
    config: HttpClientConfig
): HttpClient =
    when (priority) {
        SlockRequestPriority.Foreground ->
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
            )
        SlockRequestPriority.Visible ->
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS
            )
        SlockRequestPriority.Background ->
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS
            )
    }
