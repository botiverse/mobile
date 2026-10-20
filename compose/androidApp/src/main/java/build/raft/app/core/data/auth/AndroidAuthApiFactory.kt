package build.raft.app.core.data.auth

import android.content.Context
import build.raft.app.data.RaftAuthApi
import build.raft.app.data.RaftAuthApiClient
import build.raft.app.data.RaftAuthTransport
import build.raft.app.data.SharedRaftAuthTransport
import build.raft.app.data.RAFT_API_BASE_URL

object AndroidAuthApiFactory {
    const val BASE_URL = RAFT_API_BASE_URL

    fun currentBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().apiBaseUrl

    fun currentSocketBaseUrl(context: Context): String =
        AndroidApiServerEnvironmentStore(context).current().socketBaseUrl

    fun createAuthTransport(baseUrl: String = BASE_URL): RaftAuthTransport =
        SharedRaftAuthTransport(baseUrl)

    fun createAuthTransport(context: Context): RaftAuthTransport =
        createAuthTransport(currentBaseUrl(context))

    fun createAuthApi(baseUrl: String = BASE_URL): RaftAuthApi =
        RaftAuthApiClient(createAuthTransport(baseUrl))

    fun createAuthApi(context: Context): RaftAuthApi =
        createAuthApi(currentBaseUrl(context))
}
