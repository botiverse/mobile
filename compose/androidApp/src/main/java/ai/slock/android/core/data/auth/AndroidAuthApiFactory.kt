package ai.slock.android.core.data.auth

import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.SlockAuthApiClient
import ai.slock.compose.data.SlockAuthTransport
import ai.slock.compose.data.SLOCK_API_BASE_URL

object AndroidAuthApiFactory {
    const val BASE_URL = SLOCK_API_BASE_URL

    fun createAuthTransport(baseUrl: String = BASE_URL): SlockAuthTransport =
        KtorAuthApi(baseUrl)

    fun createAuthApi(baseUrl: String = BASE_URL): SlockAuthApi =
        SlockAuthApiClient(createAuthTransport(baseUrl))
}
