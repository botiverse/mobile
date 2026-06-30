package build.raft.app.core.data.auth

import android.content.Context
import build.raft.app.data.SlockAuthApi
import build.raft.app.data.SlockAuthApiClient
import build.raft.app.data.SlockAuthTransport
import build.raft.app.data.SharedKtorSlockAuthTransport
import build.raft.app.data.SLOCK_API_BASE_URL

object AndroidAuthApiFactory {
    const val BASE_URL = SLOCK_API_BASE_URL

    fun currentBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().apiBaseUrl

    fun currentSocketBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().socketBaseUrl

    fun createAuthTransport(baseUrl: String = BASE_URL): SlockAuthTransport =
        SharedKtorSlockAuthTransport(baseUrl)

    fun createAuthTransport(context: Context): SlockAuthTransport =
        createAuthTransport(currentBaseUrl(context))

    fun createAuthApi(baseUrl: String = BASE_URL): SlockAuthApi =
        SlockAuthApiClient(createAuthTransport(baseUrl))

    fun createAuthApi(context: Context): SlockAuthApi =
        createAuthApi(currentBaseUrl(context))
}
