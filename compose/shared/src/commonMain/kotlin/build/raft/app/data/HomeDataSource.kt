package build.raft.app.data

import build.raft.app.resources.*
import build.raft.app.raftAvatarDiagnosticsSource
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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

private val homeApiJson = Json { ignoreUnknownKeys = true }

class SharedHomeDataSource(
    private val sessionStore: RaftAuthSessionStore,
    private val api: RaftHomeApi,
    announcementsApi: RaftAnnouncementsRemindersApi? = null,
    computersApi: RaftComputersApi? = null,
    private val appUpdateStatusProvider: (suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>)? = null,
    private val notificationPreferenceRepository: NotificationPreferenceRepository? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val parseHome: (HomeNetworkPayload, StoredAuthSession) -> DataResult<HomeSnapshot>,
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    /**
     * Where Home response bodies are decoded. Production keeps them off the store thread on the
     * shared JSON lane; tests pass an inline dispatcher so a launched Home load does not resume
     * on a parse worker thread and race the test's own store writes.
     */
    parseDispatcher: CoroutineDispatcher = RaftJsonParseDispatcher
) {
    private val typedApi = RaftHomeApiClient(api, diagnosticsLogger, parseDispatcher)
    private val typedAnnouncementsApi = announcementsApi?.let(::RaftAnnouncementsRemindersApiClient)
    private val typedComputersApi = computersApi?.let(::RaftComputersApiClient)

    internal val projectsAgentStatusFromMemberStore: Boolean
        get() = serverMembersStore != null

    data class AgentDirectMessageStatusPatch(
        val snapshot: HomeSnapshot,
        val changed: Boolean,
        val directMessages: List<ConversationSummary> = emptyList()
    )

    /** `channels/unread?summary=1` for [serverId] alone (Home's followed-thread reconcile). */
    suspend fun channelUnreadSummary(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<ChannelUnreadSummary> = typedApi.channelUnreadSummary(serverId, priority)

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
                store.refreshMembersForHomeProjection(
                    serverId,
                    priority = priority,
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
        loadNotificationsFromSources(homeReload = false)

    /**
     * The notifications refresh that follows every Home load. With incremental sync on for the
     * server, the machine list already read (by a machines refresh: reconnect / foreground / the
     * Machines scope) is reused instead of reading `/servers/:id/machines` on each Home reload
     * (Tenny / Scout 10-27); the first one, or sync off, reads it as before.
     */
    internal suspend fun loadNotificationsAfterHomeLoad(): DataResult<List<SharedHomeNotification>> =
        loadNotificationsFromSources(homeReload = true)

    /**
     * The last machine list read, per account + server (sync-on Home reloads reuse it). Overwritten
     * by every network read; dropped by [dropHeldMachines] on any machine change, so a reuse never
     * outlives a change it did not read. Guarded by [heldMachinesLock] with [heldMachinesDrops].
     */
    private var lastMachines: Pair<String, ComputerListSummary>? = null

    /**
     * Bumped by every [dropHeldMachines]. A read that started before a drop may have been answered
     * before the change; it must not become the held list when it lands after the drop.
     */
    private var heldMachinesDrops = 0L
    private val heldMachinesLock = SynchronizedObject()

    /**
     * A machine changed (`machine:status` and the other Machines-scope pushes, sync `machine` rows,
     * the sync machines refresh): the next Home reload reads `/servers/:id/machines` again, and a
     * read already in flight is not held.
     */
    internal fun dropHeldMachines() {
        synchronized(heldMachinesLock) {
            heldMachinesDrops += 1
            lastMachines = null
        }
    }

    private suspend fun loadNotificationsFromSources(homeReload: Boolean): DataResult<List<SharedHomeNotification>> {
        val appUpdateNotifications =
            when (val appUpdateResult = loadAppUpdateNotification()) {
                is DataResult.Content -> listOfNotNull(appUpdateResult.data)
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> emptyList()
            }
        return when (val machineResult = loadMachineNotifications(homeReload)) {
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

    private suspend fun loadMachineNotifications(homeReload: Boolean): DataResult<List<SharedHomeNotification>> {
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
        val offlineComputerAlertsEnabled =
            notificationPreferenceRepository
                ?.getNotificationPreference()
                ?.offlineComputerAlertsEnabled == true
        val cacheKey = "${session.user.id}|${serverId.value}"
        val syncCovers = homeReload && serverMembersStore?.incrementalSyncCovers(serverId) == true
        val (reusable, dropsAtRead) =
            synchronized(heldMachinesLock) {
                lastMachines?.takeIf { (key, _) -> syncCovers && key == cacheKey }?.second to heldMachinesDrops
            }
        if (reusable != null) {
            diagnosticsLogger("home_machine_notifications serverId=${serverId.value} source=held network=false reason=incremental_sync")
            return DataResult.Content(
                reusable.toSharedHomeMachineNotifications(offlineComputerAlertsEnabled = offlineComputerAlertsEnabled)
            )
        }
        return when (val result = client.machines(serverId)) {
            is DataResult.Content -> {
                synchronized(heldMachinesLock) {
                    if (heldMachinesDrops == dropsAtRead) lastMachines = cacheKey to result.data
                }
                DataResult.Content(
                    result.data.toSharedHomeMachineNotifications(
                        offlineComputerAlertsEnabled = offlineComputerAlertsEnabled
                    )
                )
            }
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
        // read_receipts_v0: other participants' read positions only decorate the open message list.
        is build.raft.app.model.ScopeReadUpdated -> false
        // Agent typing line: who is about to post only decorates the open conversation.
        is build.raft.app.model.AgentTypingUpdated -> false
        // A web restart's result only concerns the open computer detail page (settings store).
        is build.raft.app.model.ComputerRestartDone -> false
        // A flag-refetch hint: the feature-flag refresher handles it; Home's rows do not change.
        is build.raft.app.model.ServerFeaturesChanged -> false
        // Object rows live in Activity only; the Activity store and the unread summary refetch on it.
        is build.raft.app.model.ActivityChanged -> false
        // The member's theme lives on the stored server (process realtime owner); Home's rows do not change.
        is build.raft.app.model.ServerAppearanceUpdated -> false
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

// task #831: how long the canonical Home load will wait on an auxiliary unread
// endpoint before rendering an honest partial (unread-less) state. Bounded so a
// slow/hung unread endpoint can never suspend channels/DMs; the next
// generation-fenced home refresh refills unread.
private const val HOME_UNREAD_ENRICHMENT_BUDGET_MS = 3_000L

internal fun SharedSettingsUpdateCheckSnapshot.toSharedHomeAppUpdateNotification(): SharedHomeNotification? {
    if (!supported || !updateAvailable || latestVersionCode <= 0L) return null
    if (currentVersionCode > 0L && latestVersionCode <= currentVersionCode) return null
    // Same version name, different build: name the current build too, or the notice reads
    // "1.13.0 … 1.13.0 (11300008)" without saying what changed.
    val sameVersionName = currentVersionLabel.isNotBlank() && currentVersionLabel.trim() == latestVersionLabel.trim()
    val currentBuildSuffix = if (sameVersionName && currentVersionCode > 0L) " ($currentVersionCode)" else ""
    val currentVersion = currentVersionLabel.ifBlank { "current build" } + currentBuildSuffix
    val latestVersion =
        latestVersionLabel
            .takeIf { it.isNotBlank() }
            ?.let { "$it ($latestVersionCode)" }
            ?: "version code $latestVersionCode"
    val localizedCurrentVersion =
        currentVersionLabel.ifBlank { currentVersionCode.takeIf { it > 0L }?.toString() ?: "—" } + currentBuildSuffix
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
        titleDesc = Res.string.home_notification_app_update_title.desc(),
        bodyDesc = Res.string.home_notification_app_update_body.format(listOf(localizedCurrentVersion, localizedLatestVersion)),
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
            "downloading" -> Res.string.home_notification_action_downloading.desc()
            "install" -> Res.string.home_notification_action_install.desc()
            "download", "" -> Res.string.home_notification_action_download.desc()
            else -> null
        }
    }

private fun ComputerListSummary.toSharedHomeMachineNotifications(
    offlineComputerAlertsEnabled: Boolean
): List<SharedHomeNotification> {
    val notifications = mutableListOf<SharedHomeNotification>()
    val offlineMachines = machines.filter { it.status == ComputerStatus.Offline }
    if (offlineComputerAlertsEnabled && offlineMachines.isNotEmpty()) {
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
                    Res.string.home_notification_machine_offline_title_one.format(listOf(names))
                } else {
                    Res.string.home_notification_machine_offline_title_many.format(listOf(names))
                },
            bodyDesc =
                if (offlineMachines.size == 1) {
                    Res.string.home_notification_machine_offline_body_one.desc()
                } else {
                    Res.string.home_notification_machine_offline_body_many.desc()
                },
            actionLabelDesc = Res.string.home_action_view.desc(),
            targetId = offlineMachines.first().id,
            kind = SharedHomeNotificationKind.Warning,
            icon = SharedHomeNotificationIcon.WifiOff
        )
    }
    val outdatedMachines =
        machines.filter { machine ->
            !machine.isComputer &&
                machine.status == ComputerStatus.Online &&
                isDaemonOutdated(machine.daemonVersion, latestDaemonVersion)
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
                    Res.string.home_notification_machine_outdated_title_one.format(listOf(names))
                } else {
                    Res.string.home_notification_machine_outdated_title_many.format(listOf(names))
                },
            bodyDesc = Res.string.home_notification_machine_outdated_body.desc(),
            actionLabelDesc = Res.string.home_action_view.desc(),
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
 * arrays, the unread summary (`channels/unread?summary=1`) is a root object
 * `{channels, threadUnread}`, and server unread summary is a root array.
 * Guessed wrappers such as `items` or `data` are contract mismatches.
 */
interface RaftHomeApi {
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
    /** `channels/unread?summary=1` scopes whose unread messages mention the user (`hasMention`). */
    val mentionedChannelIds: Set<ChannelId> = emptySet(),
    /** Followed threads with unread replies from the same summary, keyed by thread channel id value. */
    val followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    /** Scopes the same summary reports marked unread with no count ([ChannelUnreadSummary.markedUnreadIds]). */
    val markedUnreadIds: Set<String> = emptySet(),
    val serverUnreadSummary: List<ServerUnreadSummaryDto>,
    /**
     * True when `/servers/unread-summary` itself completed and decoded. This
     * is intentionally independent from [unreadEnrichmentAvailable]: Activity
     * counts can advance when the summary succeeds even if the auxiliary
     * per-channel unread request fails.
     */
    val serverUnreadSummaryAvailable: Boolean = true,
    // task #831: false when the auxiliary unread endpoints were unavailable
    // (error/timeout/hung/contract-mismatch) so unreadCounts/serverUnreadSummary
    // are an honest empty, NOT an authoritative zero. Consumers must keep the
    // prior unread badges instead of clobbering them to read.
    val unreadEnrichmentAvailable: Boolean = true,
    /**
     * Task #164: an unread read (`channels/unread` or `servers/unread-summary`) came back as the
     * server's remembered body (`X-Raft-Stale`) or as `503 rw_overloaded`. The data that did
     * arrive is still here; the store decides whether it may replace what Home shows.
     */
    val unreadStale: Boolean = false,
    /** The server's `Retry-After` on a stale or overloaded unread read; the first recovery read waits for it. */
    val unreadRetryAfterMillis: Long? = null
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

internal class RaftHomeApiClient(
    private val transport: RaftHomeApi,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val parseDispatcher: CoroutineDispatcher = RaftJsonParseDispatcher
) {
    suspend fun homePayload(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<HomeNetworkPayload> = supervisorScope {
        // task #831: the unread grace is measured from the concurrent launch, so
        // time already spent awaiting the canonical channels/DMs is deducted and
        // the total Home block stays ~max(canonical, budget), not canonical +
        // budget.
        val startMark = TimeSource.Monotonic.markNow()
        val channelsDeferred =
            async {
                requestHomeSection(
                    category = "channels",
                    contractCode = "home_channels_contract_mismatch",
                    serializer = ListSerializer(HomeChannelWireDto.serializer()),
                    errorTextDecoder = HomeNetworkResponse::homeChannelsRouteErrorTextOrNull
                ) {
                    transport.channels(serverId, priority)
                }
            }
        val directMessagesDeferred =
            async {
                requestHomeSection(
                    category = "direct_messages",
                    contractCode = "home_direct_messages_contract_mismatch",
                    serializer = ListSerializer(HomeDirectMessageWireDto.serializer()),
                    errorTextDecoder = HomeNetworkResponse::homeDirectMessagesRouteErrorTextOrNull
                ) {
                    transport.directMessages(serverId, priority)
                }
            }
        val unreadCountsStale = HomeSectionStaleness()
        val unreadCountsDeferred =
            async {
                requestHomeSection(
                    category = "channel_unread_counts",
                    contractCode = "home_unread_counts_contract_mismatch",
                    serializer = ChannelUnreadSummaryWireDto.serializer(),
                    errorTextDecoder = HomeNetworkResponse::homeUnreadCountsRouteErrorTextOrNull,
                    staleness = unreadCountsStale
                ) {
                    transport.channelUnreadCounts(serverId, priority)
                }
            }
        val serverUnreadSummaryStale = HomeSectionStaleness()
        val serverUnreadSummaryDeferred =
            async {
                requestHomeSection(
                    category = "server_unread_summary",
                    contractCode = "home_server_unread_summary_contract_mismatch",
                    serializer = ListSerializer(ServerUnreadSummaryDto.serializer()),
                    errorTextDecoder = HomeNetworkResponse::homeServerUnreadSummaryRouteErrorTextOrNull,
                    staleness = serverUnreadSummaryStale
                ) {
                    transport.serverUnreadSummary(serverId, priority)
                }
            }

        // Canonical Home surface: channels and DMs are required. A failure/empty
        // here is the Home's terminal state, so it still aborts early.
        val channels =
            when (val result = channelsDeferred.await()) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return@supervisorScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@supervisorScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@supervisorScope DataResult.NoPermission(result.blocker)
            }
        val directMessages =
            when (val result = directMessagesDeferred.await()) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return@supervisorScope DataResult.Empty(result.reason)
                is DataResult.Error -> return@supervisorScope DataResult.Error(result.error)
                is DataResult.NoPermission -> return@supervisorScope DataResult.NoPermission(result.blocker)
            }

        // task #831: the auxiliary unread endpoints are enrichment, not canonical.
        // Under supervisorScope a failing unread request can no longer cancel the
        // Home load, and awaitBestEffortHomeSection bounds + abandons a hung one.
        // When any unread section is unavailable the payload is flagged so the
        // Store keeps the prior badges (never fake zero); the next
        // generation-fenced home refresh refills unread.
        val unreadCountsResult = unreadCountsDeferred.awaitBestEffortHomeSection(startMark)
        val serverUnreadSummaryResult = serverUnreadSummaryDeferred.awaitBestEffortHomeSection(startMark)
        val unreadEnrichmentAvailable =
            unreadCountsResult is DataResult.Content && serverUnreadSummaryResult is DataResult.Content
        val serverUnreadSummaryAvailable = serverUnreadSummaryResult is DataResult.Content
        val unreadSummary = (unreadCountsResult as? DataResult.Content)?.data?.toChannelUnreadSummary()
        val unreadCounts = unreadSummary?.unreadCounts ?: emptyMap()
        val serverUnreadSummary =
            (serverUnreadSummaryResult as? DataResult.Content)?.data
                ?: emptyList()

        DataResult.Content(
            HomeNetworkPayload(
                serverId = serverId,
                channels = channels,
                directMessages = directMessages,
                unreadCounts = unreadCounts,
                mentionedChannelIds = unreadSummary?.mentionedChannelIds ?: emptySet(),
                followedThreadUnread = unreadSummary?.followedThreadUnread ?: emptyMap(),
                markedUnreadIds = unreadSummary?.markedUnreadIds ?: emptySet(),
                serverUnreadSummary = serverUnreadSummary,
                serverUnreadSummaryAvailable = serverUnreadSummaryAvailable,
                unreadEnrichmentAvailable = unreadEnrichmentAvailable,
                unreadStale = unreadCountsStale.stale || serverUnreadSummaryStale.stale,
                unreadRetryAfterMillis =
                    listOfNotNull(unreadCountsStale.retryAfterMillis, serverUnreadSummaryStale.retryAfterMillis).maxOrNull()
            )
        )
    }

    /**
     * `channels/unread?summary=1` alone: Home's followed-thread reconcile after a reply into a thread the
     * snapshot does not know yet (web `followedThreadsRefresh`).
     */
    suspend fun channelUnreadSummary(
        serverId: ServerId,
        priority: DataRequestPriority
    ): DataResult<ChannelUnreadSummary> {
        val staleness = HomeSectionStaleness()
        return when (
            val result =
                requestHomeSection(
                    category = "channel_unread_counts",
                    contractCode = "home_unread_counts_contract_mismatch",
                    serializer = ChannelUnreadSummaryWireDto.serializer(),
                    errorTextDecoder = HomeNetworkResponse::homeUnreadCountsRouteErrorTextOrNull,
                    staleness = staleness
                ) {
                    transport.channelUnreadCounts(serverId, priority)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toChannelUnreadSummary().copy(stale = staleness.stale)
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    /**
     * Awaits an auxiliary (non-canonical) home section without ever letting it
     * block or fail the canonical Home load (task #831): the wait is bounded by
     * the unread grace remaining since [startMark] (so it can't add on top of
     * the canonical fetch time), an erroring/timed-out section returns null, and
     * the still running request is abandoned so the enclosing supervisor scope
     * completes. Real cancellation of the caller is preserved. A null result
     * means "unread unavailable" — the caller flags the payload so badges are
     * kept, not zeroed.
     */
    private suspend fun <T> Deferred<DataResult<T>>.awaitBestEffortHomeSection(
        startMark: TimeSource.Monotonic.ValueTimeMark
    ): DataResult<T>? {
        val remainingMs = HOME_UNREAD_ENRICHMENT_BUDGET_MS - startMark.elapsedNow().inWholeMilliseconds
        if (remainingMs <= 0L) {
            cancel()
            return null
        }
        val result =
            try {
                withTimeoutOrNull(remainingMs) { await() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                null
            }
        cancel()
        return result
    }

    private suspend fun <T> requestHomeSection(
        category: String,
        contractCode: String,
        serializer: KSerializer<T>,
        errorTextDecoder: (HomeNetworkResponse) -> String?,
        staleness: HomeSectionStaleness? = null,
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
        if (staleness != null) {
            val staleReason = response.raftStaleReason()
            staleness.stale = staleReason != null || response.isRwOverloaded()
            if (staleness.stale) staleness.retryAfterMillis = response.raftRetryAfterMillis()
            if (staleness.stale) {
                diagnosticsLogger(
                    "home_endpoint_stale category=$category code=${response.code} " +
                        "reason=${staleReason?.wireValue ?: if (response.code == 503) RwOverloadedErrorCode else "unknown"}"
                )
            }
        }
        diagnosticsLogger(
            "home_network_body_received source=home_$category code=${response.code} " +
                "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} hasBody=${response.bodyText != null}"
        )
        val parseStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsLogger(
            "home_parse_start source=home_$category " +
                "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        val result =
            withContext(parseDispatcher) {
                response.decodeHomeBody(
                    contractCode = contractCode,
                    serializer = serializer,
                    serverMessage = if (response.isSuccessful) null else errorTextDecoder(response)
                )
            }
        diagnosticsLogger(
            "home_parse_result source=home_$category ${result.homeDiagnosticsSummary()} " +
                "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        diagnosticsLogger(
            "home_endpoint category=$category code=${response.code} ${result.homeDiagnosticsSummary()}"
        )
        return result
    }
}

private fun <T> HomeNetworkResponse.decodeHomeBody(
    contractCode: String,
    serializer: KSerializer<T>,
    serverMessage: String?
): DataResult<T> {
    if (!isSuccessful) return toHomeApiFailure(serverMessage)
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

private fun <T> HomeNetworkResponse.toHomeApiFailure(serverMessage: String?): DataResult<T> =
    if (code == 401 || code == 403) {
        DataResult.NoPermission(PermissionBlocker(code = "home_http_$code", serverMessage = serverMessage))
    } else {
        DataResult.Error(
            ScreenError(
                code = "home_http_$code",
                retryable = true,
                serverMessage = serverMessage,
                serverCode = RwOverloadedErrorCode.takeIf { isRwOverloaded() },
                retryAfterSeconds = raftRetryAfterSecondsIfRwOverloaded()
            )
        )
    }

/** Task #164: whether one Home unread read was answered stale (header) or as `503 rw_overloaded`. */
private class HomeSectionStaleness {
    @kotlin.concurrent.Volatile
    var stale: Boolean = false

    @kotlin.concurrent.Volatile
    var retryAfterMillis: Long? = null
}

@Serializable
private data class HomeChannelsRouteErrorDto(val error: String? = null)

@Serializable
private data class HomeDirectMessagesRouteErrorDto(val error: String? = null)

@Serializable
private data class HomeUnreadCountsRouteErrorDto(val error: String? = null)

@Serializable
private data class HomeServerUnreadSummaryRouteErrorDto(val error: String? = null)

private fun HomeNetworkResponse.homeChannelsRouteErrorTextOrNull(): String? =
    homeRouteErrorTextOrNull(HomeChannelsRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.homeDirectMessagesRouteErrorTextOrNull(): String? =
    homeRouteErrorTextOrNull(HomeDirectMessagesRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.homeUnreadCountsRouteErrorTextOrNull(): String? =
    homeRouteErrorTextOrNull(HomeUnreadCountsRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.homeServerUnreadSummaryRouteErrorTextOrNull(): String? =
    homeRouteErrorTextOrNull(HomeServerUnreadSummaryRouteErrorDto.serializer()) { it.error }

private fun <T> HomeNetworkResponse.homeRouteErrorTextOrNull(
    serializer: KSerializer<T>,
    text: (T) -> String?
): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        text(homeApiJson.decodeFromString(serializer, payload))
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
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
                    "avatar=${avatarWireValue.raftAvatarDiagnosticsSource()}"
            }
    return "home_dm_avatar_seed serverId=${serverId.value} store=$storeAvailable " +
        "peers=$size withAvatar=$withAvatar sample=$sample"
}

private fun Throwable.safeHomeDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
