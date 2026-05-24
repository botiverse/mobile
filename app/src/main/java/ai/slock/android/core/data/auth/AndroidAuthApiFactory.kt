package ai.slock.android.core.data.auth

import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.SlockAuthApiClient
import ai.slock.compose.data.SlockAuthTransport

object AndroidAuthApiFactory {
    const val BASE_URL = "https://api.slock.ai/api/"

    fun createAuthTransport(baseUrl: String = BASE_URL): SlockAuthTransport =
        KtorAuthApi(baseUrl)

    fun createAuthApi(baseUrl: String = BASE_URL): SlockAuthApi =
        SlockAuthApiClient(createAuthTransport(baseUrl))
}
