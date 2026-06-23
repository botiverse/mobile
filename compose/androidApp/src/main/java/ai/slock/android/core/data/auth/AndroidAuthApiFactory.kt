package ai.slock.android.core.data.auth

import android.content.Context
import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.SlockAuthApiClient
import ai.slock.compose.data.SlockAuthTransport
import ai.slock.compose.data.SLOCK_API_BASE_URL

object AndroidAuthApiFactory {
    const val BASE_URL = SLOCK_API_BASE_URL

    fun currentBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().apiBaseUrl

    fun currentSocketBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().socketBaseUrl

    fun createAuthTransport(baseUrl: String = BASE_URL): SlockAuthTransport =
        KtorAuthApi(baseUrl)

    fun createAuthTransport(context: Context): SlockAuthTransport =
        createAuthTransport(currentBaseUrl(context))

    fun createAuthApi(baseUrl: String = BASE_URL): SlockAuthApi =
        SlockAuthApiClient(createAuthTransport(baseUrl))

    fun createAuthApi(context: Context): SlockAuthApi =
        createAuthApi(currentBaseUrl(context))
}
