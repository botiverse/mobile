package ai.slock.compose.data

import ai.slock.compose.slockAvatarDiagnosticsSource
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

private val homeApiJson = Json { ignoreUnknownKeys = true }

class SharedHomeDataSource(
    private val sessionStore: SlockAuthSessionStore,
    private val api: SlockHomeApi,
    announcementsApi: SlockAnnouncementsRemindersApi? = null,
    computersApi: SlockComputersApi? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val localWriteThrough: ComposeLocalWriteThrough? = null,
    private val parseHome: (HomeNetworkPayload, StoredAuthSession) -> DataResult<HomeSnapshot>,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    private val typedApi = SlockHomeApiClient(api, diagnosticsLogger)
    private val typedAnnouncementsApi = announcementsApi?.let(::SlockAnnouncementsRemindersApiClient)
    private val typedComputersApi = computersApi?.let(::SlockComputersApiClient)

    internal val projectsAgentStatusFromMemberStore: Boolean
        get() = serverMembersStore != null

    data class AgentDirectMessageStatusPatch(
        val snapshot: HomeSnapshot,
        val changed: Boolean,
        val directMessages: List<ConversationSummary> = emptyList()
    )

    suspend fun currentSessionSnapshot(): DataResult<ai.slock.compose.model.SessionSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        return session.toSessionSnapshot()?.let { DataResult.Content(it) }
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
    }

    suspend fun loadHome(
        serverId: ServerId? = null,
        priority: DataRequestPriority = DataRequestPriority.Visible,
        forceNetwork: Boolean = false
    ): DataResult<HomeSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false)).also {
                diagnosticsLogger("home_bootstrap_result result=error code=unauthenticated stage=session")
            }
        val resolvedServerId = serverId ?: session.currentServerId?.let(::ServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels).also {
                diagnosticsLogger("home_bootstrap_result result=empty code=no_server stage=resolve_server")
            }

        diagnosticsLogger("home_bootstrap_start priority=$priority serverSelected=true")

        localWriteThrough?.readHomeSnapshot(resolvedServerId)?.let { cached ->
            if (priority == DataRequestPriority.Background) {
                if (!forceNetwork) {
                    diagnosticsLogger("home_bootstrap_result result=content source=cache priority=$priority")
                    return DataResult.Content(cached)
                }
                diagnosticsLogger("home_bootstrap_cache_bypass priority=$priority reason=force_network")
            }
        }

        val payload =
            try {
                when (val payloadResult = typedApi.homePayload(resolvedServerId, priority)) {
                    is DataResult.Content -> payloadResult.data
                    is DataResult.Empty -> {
                        diagnosticsLogger("home_bootstrap_result result=empty code=${payloadResult.reason.name} stage=payload")
                        return DataResult.Empty(payloadResult.reason)
                    }
                    is DataResult.Error -> {
                        diagnosticsLogger("home_bootstrap_result result=error code=${payloadResult.error.code} stage=payload")
                        return DataResult.Error(payloadResult.error)
                    }
                    is DataResult.NoPermission -> {
                        diagnosticsLogger("home_bootstrap_result result=no_permission code=${payloadResult.blocker.code} stage=payload")
                        return DataResult.NoPermission(payloadResult.blocker)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "home_bootstrap_exception errorType=${throwable.safeHomeDiagnosticsType()} mapped=home_network"
                )
                return DataResult.Error(ScreenError(code = "home_network", retryable = true))
            }
        val result = parseHome(payload, session)
        if (result is DataResult.Content) {
            val directMessagePeerProfiles = payload.directMessagePeerProfiles(result.data.server.id)
            diagnosticsLogger(
                directMessagePeerProfiles.homeDirectMessageAvatarSeedDiagnostics(
                    serverId = result.data.server.id,
                    storeAvailable = serverMembersStore != null
                )
            )
            serverMembersStore?.seedProfiles(
                serverId = result.data.server.id,
                profiles = directMessagePeerProfiles,
                server = result.data.server
            )
        }
        diagnosticsLogger("home_bootstrap_result ${result.homeDiagnosticsSummary()} stage=parse")
        localWriteThrough?.writeHomeResult(result, source = "shared_home_data_source")
        return result
    }

    suspend fun refreshAgentDirectMessageStatuses(
        snapshot: HomeSnapshot,
        priority: DataRequestPriority = DataRequestPriority.Background
    ): AgentDirectMessageStatusPatch {
        val store = serverMembersStore
            ?: return AgentDirectMessageStatusPatch(snapshot, changed = false)
        val serverId = snapshot.server.id
        when (val result = store.refreshMembers(serverId, priority = priority, force = true)) {
            is DataResult.Content ->
                diagnosticsLogger(
                    "home_agent_status_refresh_result serverId=${serverId.value} source=member_store result=content members=${store.currentServerMemberCount(serverId)}"
                )
            is DataResult.Empty ->
                diagnosticsLogger(
                    "home_agent_status_refresh_result serverId=${serverId.value} source=member_store result=empty reason=${result.reason}"
                )
            is DataResult.Error ->
                diagnosticsLogger(
                    "home_agent_status_refresh_result serverId=${serverId.value} source=member_store result=error code=${result.error.code}"
                )
            is DataResult.NoPermission ->
                diagnosticsLogger(
                    "home_agent_status_refresh_result serverId=${serverId.value} source=member_store result=no_permission code=${result.blocker.code}"
                )
        }
        return AgentDirectMessageStatusPatch(snapshot, changed = false)
    }

    suspend fun writeHomeSnapshot(snapshot: HomeSnapshot, source: String): Boolean =
        localWriteThrough?.writeHomeResult(DataResult.Content(snapshot), source) ?: false

    suspend fun readCachedHome(serverId: ServerId? = null): DataResult<HomeSnapshot> {
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val resolvedServerId = serverId ?: session.currentServerId?.let(::ServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        val cached = localWriteThrough?.readHomeSnapshot(resolvedServerId)
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        return DataResult.Content(cached)
    }

    suspend fun applyRealtimeEvent(
        serverId: ServerId,
        event: RealtimeEvent,
        activeConversationId: String? = null,
        projectUpdatedSnapshot: Boolean = true
    ): DataResult<HomeSnapshot> =
        applyRealtimeEvents(serverId, listOf(event), activeConversationId, projectUpdatedSnapshot)

    suspend fun applyRealtimeEvents(
        serverId: ServerId,
        events: List<RealtimeEvent>,
        activeConversationId: String? = null,
        projectUpdatedSnapshot: Boolean = true
    ): DataResult<HomeSnapshot> {
        val homeUpdated =
            localWriteThrough?.writeRealtimeEvents(serverId, events, activeConversationId)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        if (!homeUpdated || events.none { event -> event.updatesHomeSnapshot() }) {
            return DataResult.Empty(EmptyReason.NoChannels)
        }
        if (!projectUpdatedSnapshot) {
            return DataResult.Empty(EmptyReason.NoChannels)
        }
        return readCachedHome(serverId)
    }

    suspend fun loadNotifications(): DataResult<List<SharedHomeNotification>> = coroutineScope {
        val announcementResult = async { loadAnnouncementNotifications() }
        val machineResult = async { loadMachineNotifications() }
        mergeHomeNotificationResults(announcementResult.await(), machineResult.await())
    }

    private suspend fun loadAnnouncementNotifications(): DataResult<List<SharedHomeNotification>> {
        val client = typedAnnouncementsApi
            ?: return DataResult.Content(emptyList())
        return when (val result = client.activeAnnouncements()) {
            is DataResult.Content -> DataResult.Content(result.data.items.mapNotNull { it.toSharedHomeNotification() })
            is DataResult.Empty -> DataResult.Content(emptyList())
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    private suspend fun loadMachineNotifications(): DataResult<List<SharedHomeNotification>> {
        val client = typedComputersApi
            ?: return DataResult.Content(emptyList())
        val session = sessionStore.readSession()
            ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val serverId = session.currentServerId?.let(::ServerId)
            ?: return DataResult.Content(emptyList())
        return when (val result = client.machines(serverId)) {
            is DataResult.Content -> DataResult.Content(result.data.toSharedHomeMachineNotifications())
            is DataResult.Empty -> DataResult.Content(emptyList())
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    suspend fun dismissNotification(id: String): DataResult<AnnouncementCommandResult> {
        if (id.isLocalHomeNotificationId()) {
            return DataResult.Content(AnnouncementCommandResult(ok = true))
        }
        val client = typedAnnouncementsApi
            ?: return DataResult.Empty(EmptyReason.NoChannels)
        return client.dismissAnnouncement(id)
    }
}

private fun mergeHomeNotificationResults(
    first: DataResult<List<SharedHomeNotification>>,
    second: DataResult<List<SharedHomeNotification>>
): DataResult<List<SharedHomeNotification>> {
    val merged = mutableListOf<SharedHomeNotification>()
    var sawContent = false
    var firstNoPermission: DataResult.NoPermission? = null
    var firstError: DataResult.Error? = null
    listOf(first, second).forEach { result ->
        when (result) {
            is DataResult.Content -> {
                sawContent = true
                merged += result.data
            }
            is DataResult.Empty -> sawContent = true
            is DataResult.Error -> if (firstError == null) firstError = result
            is DataResult.NoPermission -> if (firstNoPermission == null) firstNoPermission = result
        }
    }
    return when {
        sawContent -> DataResult.Content(merged)
        firstNoPermission != null -> firstNoPermission
        firstError != null -> firstError
        else -> DataResult.Content(emptyList())
    }
}

internal fun RealtimeEvent.updatesHomeSnapshot(): Boolean =
    when (this) {
        is DirectMessageCreated,
        is MessageNew,
        is MessageUpdated,
        is SyncResumeResponse,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated -> true
        is MemberPresenceUpdated -> false
        is RealtimeRefreshRequired ->
            this.scope == RealtimeRefreshScope.Home ||
                this.scope == RealtimeRefreshScope.ChannelMembers ||
                this.scope == RealtimeRefreshScope.ServerMembers ||
                this.scope == RealtimeRefreshScope.Agents ||
                this.scope == RealtimeRefreshScope.Machines ||
                this.scope == RealtimeRefreshScope.Settings
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

internal fun String.isLocalHomeNotificationId(): Boolean =
    startsWith("machine-offline:")

private fun ComputerListSummary.toSharedHomeMachineNotifications(): List<SharedHomeNotification> {
    val offlineMachines = machines.filter { it.status == ComputerStatus.Offline }
    if (offlineMachines.isEmpty()) return emptyList()
    val sortedIds = offlineMachines.map { it.id }.sorted()
    val names = offlineMachines.joinToString(", ") { it.name }
    val verb = if (offlineMachines.size == 1) "is offline" else "are offline"
    val body =
        if (offlineMachines.size == 1) {
            "No agents are active on this computer right now - reconnect when you need it next."
        } else {
            "No agents are active on these computers right now - reconnect when you need them next."
        }
    return listOf(
        SharedHomeNotification(
            id = "machine-offline:${sortedIds.joinToString(",")}",
            title = "$names $verb",
            body = body,
            actionLabel = "View",
            targetId = offlineMachines.first().id,
            kind = SharedHomeNotificationKind.Warning
        )
    )
}

private fun AnnouncementItem.toSharedHomeNotification(): SharedHomeNotification? {
    val visibleTitle = title.trim()
    val visibleBody = body.trim()
    if (visibleTitle.isBlank() && visibleBody.isBlank()) return null
    return SharedHomeNotification(
        id = id,
        title = visibleTitle,
        body = visibleBody,
        actionLabel = actionLabel,
        targetId = targetId,
        kind =
            when (kind) {
                AnnouncementKind.Warning -> SharedHomeNotificationKind.Warning
                AnnouncementKind.Info -> SharedHomeNotificationKind.Info
                AnnouncementKind.Error -> SharedHomeNotificationKind.Error
            }
    )
}

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
    val serverUnreadSummary: List<ServerUnreadSummaryDto>
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
    private val transport: SlockHomeApi,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    suspend fun homePayload(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<HomeNetworkPayload> = coroutineScope {
        val channelsDeferred =
            async {
                requestHomeSection(
                    category = "channels",
                    contractCode = "home_channels_contract_mismatch",
                    serializer = ListSerializer(HomeChannelWireDto.serializer())
                ) {
                    transport.channels(serverId, priority)
                }
            }
        val directMessagesDeferred =
            async {
                requestHomeSection(
                    category = "direct_messages",
                    contractCode = "home_direct_messages_contract_mismatch",
                    serializer = ListSerializer(HomeDirectMessageWireDto.serializer())
                ) {
                    transport.directMessages(serverId, priority)
                }
            }
        val unreadCountsDeferred =
            async {
                requestHomeSection(
                    category = "channel_unread_counts",
                    contractCode = "home_unread_counts_contract_mismatch",
                    serializer = MapSerializer(String.serializer(), Int.serializer())
                ) {
                    transport.channelUnreadCounts(serverId, priority)
                }
            }
        val serverUnreadSummaryDeferred =
            async {
                requestHomeSection(
                    category = "server_unread_summary",
                    contractCode = "home_server_unread_summary_contract_mismatch",
                    serializer = ListSerializer(ServerUnreadSummaryDto.serializer())
                ) {
                    transport.serverUnreadSummary(serverId, priority)
                }
            }

        val channels =
            when (val result = channelsDeferred.await()) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return@coroutineScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@coroutineScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@coroutineScope DataResult.NoPermission(result.blocker)
            }
        val directMessages =
            when (val result = directMessagesDeferred.await()) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return@coroutineScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@coroutineScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@coroutineScope DataResult.NoPermission(result.blocker)
            }
        val unreadCounts =
            when (val result = unreadCountsDeferred.await()) {
                is DataResult.Content -> result.data.mapKeys { (key, _) -> ChannelId(key) }
                is DataResult.Empty -> return@coroutineScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@coroutineScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@coroutineScope DataResult.NoPermission(result.blocker)
            }
        val serverUnreadSummary =
            when (val result = serverUnreadSummaryDeferred.await()) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return@coroutineScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@coroutineScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@coroutineScope DataResult.NoPermission(result.blocker)
            }
        DataResult.Content(
            HomeNetworkPayload(
                serverId = serverId,
                channels = channels,
                directMessages = directMessages,
                unreadCounts = unreadCounts,
                serverUnreadSummary = serverUnreadSummary
            )
        )
    }

    private suspend fun <T> requestHomeSection(
        category: String,
        contractCode: String,
        serializer: KSerializer<T>,
        request: suspend () -> HomeNetworkResponse
    ): DataResult<T> {
        val response =
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "home_endpoint category=$category result=exception errorType=${throwable.safeHomeDiagnosticsType()}"
                )
                throw throwable
            }
        diagnosticsLogger(
            "home_network_body_received source=home_$category code=${response.code} " +
                "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} hasBody=${response.bodyText != null}"
        )
        val parseStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsLogger(
            "home_parse_start source=home_$category " +
                "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                "dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
        )
        val result =
            withContext(SlockJsonParseDispatcher) {
                response.decodeHomeBody(
                    contractCode = contractCode,
                    serializer = serializer
                )
            }
        diagnosticsLogger(
            "home_parse_result source=home_$category ${result.homeDiagnosticsSummary()} " +
                "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$SLOCK_JSON_PARSE_DISPATCHER_LABEL"
        )
        diagnosticsLogger(
            "home_endpoint category=$category code=${response.code} ${result.homeDiagnosticsSummary()}"
        )
        return result
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

private fun DataResult<*>.homeDiagnosticsSummary(): String =
    when (this) {
        is DataResult.Content -> "result=content"
        is DataResult.Empty -> "result=empty code=${reason.name}"
        is DataResult.Error -> "result=error code=${error.code}"
        is DataResult.NoPermission -> "result=no_permission code=${blocker.code}"
    }

private fun List<MemberProfile>.homeDirectMessageAvatarSeedDiagnostics(
    serverId: ServerId,
    storeAvailable: Boolean
): String {
    val withAvatar = count { profile ->
        !profile.avatar?.wireValue.isNullOrBlank() || !profile.avatarUrl.isNullOrBlank()
    }
    val withGravatar = count { !it.gravatarHash.isNullOrBlank() }
    val sample =
        take(6)
            .joinToString(separator = ";") { profile ->
                val avatarWireValue = profile.avatar?.wireValue ?: profile.avatarUrl
                "member=${profile.id.value.take(64)}:" +
                    "avatar=${avatarWireValue.slockAvatarDiagnosticsSource()}:" +
                    "gravatar=${profile.gravatarHash.slockAvatarDiagnosticsSource()}"
            }
    return "home_dm_avatar_seed serverId=${serverId.value} store=$storeAvailable " +
        "peers=$size withAvatar=$withAvatar withGravatar=$withGravatar sample=$sample"
}

private fun Throwable.safeHomeDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
