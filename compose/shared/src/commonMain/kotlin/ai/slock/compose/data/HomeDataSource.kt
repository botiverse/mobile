package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val homeApiJson = Json { ignoreUnknownKeys = true }

class SharedHomeDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val api: SlockHomeApi,
    announcementsApi: SlockAnnouncementsRemindersApi? = null,
    private val localWriteThrough: ComposeLocalWriteThrough? = null,
    private val parseHome: (HomeNetworkPayload, StoredAuthSession) -> DataResult<HomeSnapshot>
) {
    private val typedApi = SlockHomeApiClient(api)
    private val typedAnnouncementsApi = announcementsApi?.let(::SlockAnnouncementsRemindersApiClient)

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
                when (val payloadResult = typedApi.homePayload(resolvedServerId, priority)) {
                    is DataResult.Content -> payloadResult.data
                    is DataResult.Empty -> return DataResult.Empty(payloadResult.reason)
                    is DataResult.Error -> return DataResult.Error(payloadResult.error)
                    is DataResult.NoPermission -> return DataResult.NoPermission(payloadResult.blocker)
                }
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

    suspend fun applyRealtimeEvent(serverId: ServerId, event: RealtimeEvent): DataResult<HomeSnapshot> {
        localWriteThrough?.writeRealtimeEvent(serverId, event)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        return readCachedHome(serverId)
    }

    suspend fun loadNotifications(): DataResult<List<SharedHomeNotification>> {
        val client = typedAnnouncementsApi
            ?: return DataResult.Content(emptyList())
        return when (val result = client.activeAnnouncements()) {
            is DataResult.Content -> DataResult.Content(result.data.items.map { it.toSharedHomeNotification() })
            is DataResult.Empty -> DataResult.Content(emptyList())
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    suspend fun dismissNotification(id: String): DataResult<AnnouncementCommandResult> {
        val client = typedAnnouncementsApi
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        return client.dismissAnnouncement(id)
    }
}

data class SharedHomeNotification(
    val id: String,
    val title: String,
    val body: String,
    val actionLabel: String = "View",
    val targetId: String? = null,
    val kind: SharedHomeNotificationKind = SharedHomeNotificationKind.Info
)

enum class SharedHomeNotificationKind {
    Warning,
    Info,
    Error
}

private fun AnnouncementItem.toSharedHomeNotification(): SharedHomeNotification =
    SharedHomeNotification(
        id = id,
        title = title,
        body = body,
        actionLabel = actionLabel,
        targetId = targetId,
        kind =
            when (kind) {
                AnnouncementKind.Warning -> SharedHomeNotificationKind.Warning
                AnnouncementKind.Info -> SharedHomeNotificationKind.Info
                AnnouncementKind.Error -> SharedHomeNotificationKind.Error
            }
    )

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

internal class SlockHomeApiClient(
    private val transport: SlockHomeApi
) {
    suspend fun homePayload(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<HomeNetworkPayload> {
        val channels = transport.channels(serverId, priority)
        channels.validateHomeBody("home_channels_contract_mismatch", ::isJsonArray)?.let { return it }
        val directMessages = transport.directMessages(serverId, priority)
        directMessages.validateHomeBody("home_direct_messages_contract_mismatch", ::isJsonArray)?.let { return it }
        val unreadCounts = transport.channelUnreadCounts(serverId, priority)
        unreadCounts.validateHomeBody("home_unread_counts_contract_mismatch", ::isJsonObject)?.let { return it }
        val serverUnreadSummary = transport.serverUnreadSummary(serverId, priority)
        serverUnreadSummary.validateHomeBody("home_server_unread_summary_contract_mismatch", ::isJsonArray)?.let { return it }
        return DataResult.Content(
            HomeNetworkPayload(
                serverId = serverId,
                channels = channels,
                directMessages = directMessages,
                unreadCounts = unreadCounts,
                serverUnreadSummary = serverUnreadSummary
            )
        )
    }
}

private fun HomeNetworkResponse.validateHomeBody(
    contractCode: String,
    predicate: (String) -> Boolean
): DataResult<HomeNetworkPayload>? {
    if (!isSuccessful) return null
    val body = bodyText?.trim().orEmpty()
    if (body.isBlank() || !predicate(body)) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    return null
}

private fun isJsonArray(body: String): Boolean =
    runCatching { homeApiJson.parseToJsonElement(body) is JsonArray }.getOrDefault(false)

private fun isJsonObject(body: String): Boolean =
    runCatching { homeApiJson.parseToJsonElement(body) is JsonObject }.getOrDefault(false)
