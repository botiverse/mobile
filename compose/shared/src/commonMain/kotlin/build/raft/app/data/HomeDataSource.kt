package build.raft.app.data

import build.raft.app.slockAvatarDiagnosticsSource
import build.raft.app.projectionRejectingDiagnosticsLogger
import build.raft.app.model.ServerId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.tmm.kmmresource.resource.format
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
    private val appUpdateStatusProvider: (suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>)? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val parseHome: (HomeNetworkPayload, StoredAuthSession) -> DataResult<HomeSnapshot>,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
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

    suspend fun currentSessionSnapshot(): DataResult<build.raft.app.model.SessionSnapshot> {
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
            val directMessagePeerProfiles =
                payload.directMessagePeerProfiles(result.data.server.id, diagnosticsLogger)
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
        return result
    }

    suspend fun refreshAgentDirectMessageStatuses(
        snapshot: HomeSnapshot,
        priority: DataRequestPriority = DataRequestPriority.Background
    ): AgentDirectMessageStatusPatch {
        val store = serverMembersStore
            ?: return AgentDirectMessageStatusPatch(snapshot, changed = false)
        val serverId = snapshot.server.id
        when (
            val result =
                store.refreshMembers(
                    serverId,
                    priority = priority,
                    force = false,
                    reason = "home_agent_status_projection"
                )
        ) {
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
        false

    suspend fun readCachedHome(serverId: ServerId? = null): DataResult<HomeSnapshot> =
        DataResult.Empty(EmptyReason.NoChannels)

    suspend fun loadNotifications(): DataResult<List<SharedHomeNotification>> =
        loadNotificationsFromSources()

    private suspend fun loadNotificationsFromSources(): DataResult<List<SharedHomeNotification>> {
        val appUpdateNotifications =
            when (val appUpdateResult = loadAppUpdateNotification()) {
                is DataResult.Content -> listOfNotNull(appUpdateResult.data)
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> emptyList()
            }
        return when (val machineResult = loadMachineNotifications()) {
            is DataResult.Content -> DataResult.Content(appUpdateNotifications + machineResult.data)
            is DataResult.Empty -> DataResult.Content(appUpdateNotifications)
            is DataResult.Error ->
                if (appUpdateNotifications.isNotEmpty()) DataResult.Content(appUpdateNotifications) else machineResult
            is DataResult.NoPermission ->
                if (appUpdateNotifications.isNotEmpty()) DataResult.Content(appUpdateNotifications) else machineResult
        }
    }

    private suspend fun loadAppUpdateNotification(): DataResult<SharedHomeNotification?> {
        val provider = appUpdateStatusProvider ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (val result = provider()) {
            is DataResult.Content -> DataResult.Content(result.data.toSharedHomeAppUpdateNotification())
            is DataResult.Empty -> DataResult.Content(null)
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
        val canManageMachines =
            session
                .toSessionSnapshot()
                ?.availableServers
                ?.firstOrNull { it.id == serverId }
                ?.permissions
                ?.canManageMachines == true
        if (!canManageMachines) return DataResult.Content(emptyList())
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

internal fun RealtimeEvent.updatesHomeSnapshot(): Boolean =
    when (this) {
        is DirectMessageCreated,
        is MessageNew,
        is MessageUpdated,
        is SyncResumeResponse,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is PerReceiverReadStateChanged,
        is PerReceiverNotificationPrefsChanged,
        is PerReceiverPinnedChanged -> true
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
    val titleDesc: StringDesc? = null,
    val bodyDesc: StringDesc? = null,
    val actionLabelDesc: StringDesc? = null,
    val targetId: String? = null,
    val kind: SharedHomeNotificationKind = SharedHomeNotificationKind.Info,
    val icon: SharedHomeNotificationIcon = SharedHomeNotificationIcon.Default
)

enum class SharedHomeNotificationKind {
    Warning,
    Info,
    Error
}

enum class SharedHomeNotificationIcon {
    Default,
    WifiOff,
    CircleArrowUp,
    Info
}

internal fun String.isLocalHomeNotificationId(): Boolean =
    isMachineHomeNotificationId() ||
        isAppUpdateHomeNotificationId()

internal fun String.isMachineHomeNotificationId(): Boolean =
    startsWith("machine-offline:") ||
        startsWith("machine-outdated:")

internal fun String.isAppUpdateHomeNotificationId(): Boolean =
    startsWith(APP_UPDATE_HOME_NOTIFICATION_ID)

internal const val APP_UPDATE_HOME_NOTIFICATION_ID = "app-update:hands"

internal fun SharedSettingsUpdateCheckSnapshot.toSharedHomeAppUpdateNotification(): SharedHomeNotification? {
    if (!supported || !updateAvailable || latestVersionCode <= 0L) return null
    if (currentVersionCode > 0L && latestVersionCode <= currentVersionCode) return null
    val currentVersion = currentVersionLabel.ifBlank { "current build" }
    val latestVersion =
        latestVersionLabel
            .takeIf { it.isNotBlank() }
            ?.let { "$it ($latestVersionCode)" }
            ?: "version code $latestVersionCode"
    val localizedCurrentVersion =
        currentVersionLabel.ifBlank { currentVersionCode.takeIf { it > 0L }?.toString() ?: "—" }
    val localizedLatestVersion =
        latestVersionLabel
            .takeIf { it.isNotBlank() }
            ?.let { "$it ($latestVersionCode)" }
            ?: latestVersionCode.toString()
    return SharedHomeNotification(
        id = APP_UPDATE_HOME_NOTIFICATION_ID,
        title = "App update available",
        body = "Current version $currentVersion. Latest version $latestVersion.",
        actionLabel = appUpdateNotificationActionLabel(),
        titleDesc = MR.strings.home_notification_app_update_title.desc(),
        bodyDesc = MR.strings.home_notification_app_update_body.format(listOf(localizedCurrentVersion, localizedLatestVersion)),
        actionLabelDesc = appUpdateNotificationActionDesc(),
        targetId = APP_UPDATE_HOME_NOTIFICATION_ID,
        kind = SharedHomeNotificationKind.Info,
        icon = SharedHomeNotificationIcon.CircleArrowUp
    )
}

private fun SharedSettingsUpdateCheckSnapshot.appUpdateNotificationActionLabel(): String =
    when (transactionState) {
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Downloading -> "Downloading"
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened -> "Install"
        SharedSettingsUpdateTransactionState.Installed -> "Installed"
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale -> "Download"
        SharedSettingsUpdateTransactionState.Unknown ->
            actionLabel.takeIf { it.isNotBlank() }
                ?: when (statusLabel.lowercase()) {
                    "downloading" -> "Downloading"
                    "install" -> "Install"
                    else -> "Download"
                }
    }

private fun SharedSettingsUpdateCheckSnapshot.appUpdateNotificationActionDesc(): StringDesc? =
    appUpdateNotificationActionLabel().lowercase().let { action ->
        when (action) {
            "downloading" -> MR.strings.home_notification_action_downloading.desc()
            "install" -> MR.strings.home_notification_action_install.desc()
            "download", "" -> MR.strings.home_notification_action_download.desc()
            else -> null
        }
    }

private fun ComputerListSummary.toSharedHomeMachineNotifications(): List<SharedHomeNotification> {
    val notifications = mutableListOf<SharedHomeNotification>()
    val offlineMachines = machines.filter { it.status == ComputerStatus.Offline }
    if (offlineMachines.isNotEmpty()) {
        val sortedIds = offlineMachines.map { it.id }.sorted()
        val names = offlineMachines.joinToString(", ") { it.name }
        val verb = if (offlineMachines.size == 1) "is offline" else "are offline"
        val body =
            if (offlineMachines.size == 1) {
                "No agents are active on this computer right now - reconnect when you need it next."
            } else {
                "No agents are active on these computers right now - reconnect when you need them next."
            }
        notifications += SharedHomeNotification(
            id = "machine-offline:${sortedIds.joinToString(",")}",
            title = "$names $verb",
            body = body,
            actionLabel = "View",
            titleDesc =
                if (offlineMachines.size == 1) {
                    MR.strings.home_notification_machine_offline_title_one.format(listOf(names))
                } else {
                    MR.strings.home_notification_machine_offline_title_many.format(listOf(names))
                },
            bodyDesc =
                if (offlineMachines.size == 1) {
                    MR.strings.home_notification_machine_offline_body_one.desc()
                } else {
                    MR.strings.home_notification_machine_offline_body_many.desc()
                },
            actionLabelDesc = MR.strings.home_action_view.desc(),
            targetId = offlineMachines.first().id,
            kind = SharedHomeNotificationKind.Warning,
            icon = SharedHomeNotificationIcon.WifiOff
        )
    }
    val outdatedMachines =
        machines.filter { machine ->
            machine.status == ComputerStatus.Online && isDaemonOutdated(machine.daemonVersion, latestDaemonVersion)
        }
    if (outdatedMachines.isNotEmpty()) {
        val sortedIds = outdatedMachines.map { it.id }.sorted()
        val names = outdatedMachines.joinToString(", ") { it.name }
        val verb = if (outdatedMachines.size == 1) "is running an outdated daemon" else "are running outdated daemons"
        notifications += SharedHomeNotification(
            id = "machine-outdated:${latestDaemonVersion ?: "unknown"}:${sortedIds.joinToString(",")}",
            title = "$names $verb",
            body = "Stop the daemon and reconnect to update.",
            actionLabel = "View",
            titleDesc =
                if (outdatedMachines.size == 1) {
                    MR.strings.home_notification_machine_outdated_title_one.format(listOf(names))
                } else {
                    MR.strings.home_notification_machine_outdated_title_many.format(listOf(names))
                },
            bodyDesc = MR.strings.home_notification_machine_outdated_body.desc(),
            actionLabelDesc = MR.strings.home_action_view.desc(),
            targetId = outdatedMachines.first().id,
            kind = SharedHomeNotificationKind.Warning,
            icon = SharedHomeNotificationIcon.CircleArrowUp
        )
    }
    return notifications
}

internal fun isDaemonOutdated(currentVersion: String?, latestVersion: String?): Boolean {
    val current = currentVersion.versionSegmentsOrNull() ?: return false
    val latest = latestVersion.versionSegmentsOrNull() ?: return false
    val width = maxOf(current.size, latest.size)
    for (index in 0 until width) {
        val left = current.getOrElse(index) { 0 }
        val right = latest.getOrElse(index) { 0 }
        if (left != right) return left < right
    }
    return false
}

private fun String?.versionSegmentsOrNull(): List<Int>? {
    val value = this?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: return null
    val segments = value.split('.', '-', '+').mapNotNull { segment ->
        segment.takeWhile { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull()
    }
    return segments.takeIf { it.isNotEmpty() }
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
    val errorBodyText: String? = null,
    val transportDiagnostics: String? = null,
    val responseHeaders: Map<String, List<String>> = emptyMap()
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
    val sample =
        take(6)
            .joinToString(separator = ";") { profile ->
                val avatarWireValue = profile.avatar?.wireValue ?: profile.avatarUrl
                "member=${profile.id.value.take(64)}:" +
                    "avatar=${avatarWireValue.slockAvatarDiagnosticsSource()}"
            }
    return "home_dm_avatar_seed serverId=${serverId.value} store=$storeAvailable " +
        "peers=$size withAvatar=$withAvatar sample=$sample"
}

private fun Throwable.safeHomeDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
