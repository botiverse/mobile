package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.coroutines.CancellationException

class SharedHomeDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val api: SlockHomeApi,
    private val localWriteThrough: ComposeLocalWriteThrough? = null,
    private val parseHome: (HomeNetworkPayload, StoredAuthSession) -> DataResult<HomeSnapshot>
) {
    suspend fun currentSessionSnapshot(): DataResult<ai.slock.compose.model.SessionSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        return session.toSessionSnapshot()?.let { DataResult.Content(it) }
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
    }

    suspend fun loadHome(
        serverId: ServerId? = null,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<HomeSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val resolvedServerId = serverId ?: session.currentServerId?.let(::ServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels)

        localWriteThrough?.readHomeSnapshot(resolvedServerId)?.let { cached ->
            if (priority == DataRequestPriority.Background) {
                return DataResult.Content(cached)
            }
        }

        val payload =
            try {
                HomeNetworkPayload(
                    serverId = resolvedServerId,
                    channels = api.channels(resolvedServerId, priority),
                    directMessages = api.directMessages(resolvedServerId, priority),
                    unreadCounts = api.channelUnreadCounts(resolvedServerId, priority),
                    serverUnreadSummary = api.serverUnreadSummary(resolvedServerId, priority)
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "home_network", retryable = true))
            }
        val result = parseHome(payload, session)
        localWriteThrough?.writeHomeResult(result, source = "shared_home_data_source")
        return result
    }

    suspend fun readCachedHome(serverId: ServerId? = null): DataResult<HomeSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val resolvedServerId = serverId ?: session.currentServerId?.let(::ServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        val cached = localWriteThrough?.readHomeSnapshot(resolvedServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        return DataResult.Content(cached)
    }
}

interface SlockHomeApi {
    suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse
    suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse
    suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse
    suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse

    suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        channels(priority)

    suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        directMessages(priority)

    suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        channelUnreadCounts(priority)

    suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        serverUnreadSummary(priority)
}

data class HomeNetworkPayload(
    val serverId: ServerId,
    val channels: HomeNetworkResponse,
    val directMessages: HomeNetworkResponse,
    val unreadCounts: HomeNetworkResponse,
    val serverUnreadSummary: HomeNetworkResponse
) {
    val isSuccessful: Boolean
        get() =
            channels.isSuccessful &&
                directMessages.isSuccessful &&
                unreadCounts.isSuccessful &&
                serverUnreadSummary.isSuccessful
}

data class HomeNetworkResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String? = null
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}
