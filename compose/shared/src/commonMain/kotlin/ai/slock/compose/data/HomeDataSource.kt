package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
        val homeUpdated = localWriteThrough?.writeRealtimeEvent(serverId, event)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        if (!homeUpdated || !event.updatesHomeSnapshot()) {
            return DataResult.Empty(EmptyReason.NoChannels)
        }
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

private fun RealtimeEvent.updatesHomeSnapshot(): Boolean =
    when (this) {
        is MessageNew,
        is MessageUpdated,
        is SyncResumeResponse,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is MemberPresenceUpdated -> true
        else -> false
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

/**
 * Typed Home/Channels/DM boundary.
 *
 * Transport implementations may be platform-specific, but the typed client
 * below documents the canonical response roots: channels and DMs are root
 * arrays, unread counts are a root object, and server unread summary is a root
 * array. Guessed wrappers such as `items` or `data` are contract mismatches.
 */
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
    val channels: List<HomeChannelWireDto>,
    val directMessages: List<HomeDirectMessageWireDto>,
    val unreadCounts: Map<ChannelId, Int>,
    val serverUnreadSummary: List<HomeServerUnreadSummaryWireDto>
)

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
        val channels =
            when (
                val result =
                    transport.channels(serverId, priority).decodeHomeBody(
                        contractCode = "home_channels_contract_mismatch",
                        serializer = ListSerializer(HomeChannelWireDto.serializer())
                    )
            ) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return DataResult.Empty(result.reason)
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val directMessages =
            when (
                val result =
                    transport.directMessages(serverId, priority).decodeHomeBody(
                        contractCode = "home_direct_messages_contract_mismatch",
                        serializer = ListSerializer(HomeDirectMessageWireDto.serializer())
                    )
            ) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return DataResult.Empty(result.reason)
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val unreadCounts =
            when (
                val result =
                    transport.channelUnreadCounts(serverId, priority).decodeHomeBody(
                        contractCode = "home_unread_counts_contract_mismatch",
                        serializer = MapSerializer(String.serializer(), Int.serializer())
                    )
            ) {
                is DataResult.Content -> result.data.mapKeys { (key, _) -> ChannelId(key) }
                is DataResult.Empty -> return DataResult.Empty(result.reason)
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val serverUnreadSummary =
            when (
                val result =
                    transport.serverUnreadSummary(serverId, priority).decodeHomeBody(
                        contractCode = "home_server_unread_summary_contract_mismatch",
                        serializer = ListSerializer(HomeServerUnreadSummaryWireDto.serializer())
                    )
            ) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return DataResult.Empty(result.reason)
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
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

private fun <T> HomeNetworkResponse.decodeHomeBody(
    contractCode: String,
    serializer: KSerializer<T>
): DataResult<T> {
    if (!isSuccessful) return toHomeApiFailure()
    val body = bodyText?.trim().orEmpty()
    if (body.isBlank()) return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return try {
        DataResult.Content(homeApiJson.decodeFromString(serializer, body))
    } catch (_: SerializationException) {
        DataResult.Error(ScreenError(code = contractCode, retryable = false))
    } catch (_: IllegalArgumentException) {
        DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
}

private fun <T> HomeNetworkResponse.toHomeApiFailure(): DataResult<T> =
    if (code == 401 || code == 403) {
        DataResult.NoPermission(PermissionBlocker(code = "home_http_$code"))
    } else {
        DataResult.Error(ScreenError(code = "home_http_$code", retryable = true))
    }
