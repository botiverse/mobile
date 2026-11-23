package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.TaskConversationDetailsRepository
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.canWriteTasksInChannel
import build.raft.app.data.currentTaskSummary
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.taskChannelWriteFacts
import build.raft.app.model.ChannelId
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import build.raft.app.resources.Res
import build.raft.app.resources.common_back
import build.raft.app.resources.common_retry
import build.raft.app.resources.stringResource
import build.raft.app.resources.task_conversation_send_failed
import build.raft.app.resources.thread_error_join_channel
import build.raft.app.resources.thread_error_open_task
import build.raft.app.resources.thread_error_update_assignee
import build.raft.app.resources.thread_error_update_task
import build.raft.app.resources.thread_feedback_joined_channel
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * A task without a conversation yet ([TaskOpenSurface.Pending]), mounted as a normal pushed stack
 * page instead of a [TaskPendingConversationWindow] modal (#proj-mobile:#161).
 *
 * Opening never creates the conversation: the user sees the task bar, editable task head, empty
 * discussion state and composer (or Join Channel footer). The first send creates the conversation,
 * sends the first message, and replaces this page in place without animation (`slockReplaceCurrentPage`
 * + `slockOpenWithoutAnimation`) with [THREAD_PAGE].
 */
internal const val TASK_PENDING_CONVERSATION_PAGE = "compose_task_pending"

internal const val TASK_PENDING_TASK_ID_KEY = "taskId"
internal const val TASK_PENDING_CHANNEL_ID_KEY = "channelId"
internal const val TASK_PENDING_ALIGN_OWN_MESSAGES_RIGHT_KEY = "alignOwnMessagesRight"
internal const val TASK_PENDING_COMPACT_CONSECUTIVE_MESSAGES_KEY = "compactConsecutiveMessages"

@Page(TASK_PENDING_CONVERSATION_PAGE, supportInLocal = true)
internal class ComposeTaskPendingPage : AuthenticatedProductPager(AuthenticatedProductRoute.TaskPending) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { TaskPendingConversationRoute(pageVisible = raftPageVisible) }
    }
}

internal data class TaskPendingRouteParams(
    val serverId: String?,
    val taskId: String,
    val channelId: ChannelId?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
)

/**
 * Identity-only route payload for [TASK_PENDING_CONVERSATION_PAGE]: task id, optional channel id,
 * shell scope and display prefs. Never carries serialized [TaskSummary] or message lists.
 */
internal fun taskPendingPageData(
    task: TaskSummary,
    storeScope: RaftShellStoreScope,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
): JSONObject =
    settingsStandalonePageData(TASK_PENDING_CONVERSATION_PAGE, storeScope).apply {
        put(TASK_PENDING_TASK_ID_KEY, task.id.value)
        task.channelId?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { put(TASK_PENDING_CHANNEL_ID_KEY, it) }
        put(TASK_PENDING_ALIGN_OWN_MESSAGES_RIGHT_KEY, alignOwnMessagesRight)
        put(TASK_PENDING_COMPACT_CONSECUTIVE_MESSAGES_KEY, compactConsecutiveMessages)
        put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
        timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
        timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
    }

internal fun parseTaskPendingRouteParams(
    params: JSONObject,
    storeScope: RaftShellStoreScope,
): TaskPendingRouteParams =
    TaskPendingRouteParams(
        serverId = storeScope.initialServerId?.trim()?.takeIf { it.isNotEmpty() },
        taskId = params.optString(TASK_PENDING_TASK_ID_KEY).trim(),
        channelId = params.optString(TASK_PENDING_CHANNEL_ID_KEY).trim().takeIf { it.isNotEmpty() }?.let(::ChannelId),
        alignOwnMessagesRight = params.optBoolean(TASK_PENDING_ALIGN_OWN_MESSAGES_RIGHT_KEY, false),
        compactConsecutiveMessages = params.optBoolean(TASK_PENDING_COMPACT_CONSECUTIVE_MESSAGES_KEY, false),
    )

/**
 * Seeds [foregroundBannerStore.taskPendingDrafts] for instant Frame-1 display and pushes
 * [TASK_PENDING_CONVERSATION_PAGE] onto the current navigation stack.
 */
internal fun Pager.openTaskPendingPage(
    task: TaskSummary,
    serverId: String?,
    storeScope: RaftShellStoreScope,
    foregroundBannerStore: SharedForegroundBannerStore,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    channelJoined: Boolean? = null,
    canWriteChannel: Boolean? = null,
): Boolean {
    val resolvedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: storeScope.initialServerId
    val targetStoreScope =
        RaftShellStoreScope.create(
            shellInstanceId = storeScope.shellInstanceId,
            initialServerId = resolvedServerId,
        )
    foregroundBannerStore.taskPendingDrafts.seed(
        serverId = resolvedServerId,
        task = task,
        channelJoined = channelJoined,
        canWriteChannel = canWriteChannel,
    )
    return openRaftPage(
        pageName = TASK_PENDING_CONVERSATION_PAGE,
        pageData =
            taskPendingPageData(
                task = task,
                storeScope = targetStoreScope,
                timeContext = timeContext,
                alignOwnMessagesRight = alignOwnMessagesRight,
                compactConsecutiveMessages = compactConsecutiveMessages,
            ),
        storeScopeOverride = targetStoreScope,
    )
}

/**
 * Whether a task without a conversation opens as its own page ([openTaskPendingPage]) rather than
 * in the task surface the opening page draws ([TaskOpenSurfaceHost]): everywhere but the wide
 * layout ([raftAdaptiveNavRailActive]: "Desktop layout on large screens" on, in a dual-pane
 * window). The Tasks tab and a conversation both ask this, so with Desktop layout off a wide
 * window opens the page from either; a chat used to check the window alone (#307).
 */
internal fun taskPendingOpensAsPage(desktopModeEnabled: Boolean, pane: RaftAdaptivePaneState): Boolean =
    !raftAdaptiveNavRailActive(desktopModeEnabled, pane)

/**
 * Picks the freshest copy of [taskId] across ordered candidates (most authoritative first, e.g.
 * channel tasks view model, channel tasks store, server tasks store, opener seed):
 * - any copy that already has a conversation beats one that does not;
 * - otherwise the highest non-null revision wins;
 * - equal or null revisions preserve caller precedence so optimistic edits win over seeds.
 */
internal fun resolvePendingTaskSummary(
    taskId: String,
    vararg candidates: TaskSummary?,
): TaskSummary? {
    if (taskId.isBlank()) return null
    var best: TaskSummary? = null
    for (candidate in candidates) {
        if (candidate == null || candidate.id.value != taskId) continue
        if (best == null) {
            best = candidate
            continue
        }
        val candidateHasConversation = candidate.conversationChannelId != null
        val bestHasConversation = best.conversationChannelId != null
        when {
            candidateHasConversation && !bestHasConversation -> best = candidate
            !candidateHasConversation && bestHasConversation -> Unit
            candidate.revision != null && best.revision != null -> {
                if (candidate.revision > best.revision) {
                    best = candidate
                } else if (
                    candidate.revision == best.revision &&
                    candidate.conversationDetailsLoaded &&
                    !best.conversationDetailsLoaded
                ) {
                    best = candidate
                }
            }
            candidate.revision != null && best.revision == null -> best = candidate
            candidate.revision == null &&
                best.revision == null &&
                candidate.conversationDetailsLoaded &&
                !best.conversationDetailsLoaded -> best = candidate
        }
    }
    return best
}

internal data class TaskPendingChannelAccess(
    val joined: Boolean?,
    val canWriteChannel: Boolean,
)

/**
 * Resolves membership ([TaskPendingChannelAccess.joined]) and task-write permission
 * ([TaskPendingChannelAccess.canWriteChannel]) for [task] from the live server conversation
 * directory, [joinedLocally] (after tapping "Join Channel to Reply"), and opener [seed] hints.
 */
internal fun resolveTaskPendingChannelAccess(
    task: TaskSummary,
    serverId: String?,
    directory: SharedConversationDirectoryState,
    joinedLocally: Boolean,
    seed: TaskPendingSeed? = null,
): TaskPendingChannelAccess {
    val channelId = task.channelId?.value?.takeIf { it.isNotBlank() }
    val projection = channelId?.let { id -> directory.resolve(id)?.takeIf { it.canonicalChannelId == id } }
    if (projection != null) {
        val loungeCanPost = RaftAgentLounges.lounge(serverId, channelId)?.canPost
        val baseFacts = projection.taskChannelWriteFacts(loungeCanPost)
        val effectiveFacts = if (joinedLocally) baseFacts.copy(joined = true) else baseFacts
        val joined =
            if (effectiveFacts.directMessage || effectiveFacts.lounge || effectiveFacts.enabledAllChannel) {
                true
            } else {
                effectiveFacts.joined
            }
        return TaskPendingChannelAccess(
            joined = joined,
            canWriteChannel = canWriteTasksInChannel(effectiveFacts),
        )
    }
    val joined = if (joinedLocally) true else seed?.channelJoined
    val canWrite =
        when {
            joinedLocally -> true
            seed?.canWriteChannel != null -> seed.canWriteChannel
            else -> true
        }
    return TaskPendingChannelAccess(joined = joined, canWriteChannel = canWrite)
}

/**
 * Pure state machine for [ComposeTaskPendingPage]'s first send and live conversation hand-off:
 * - reuses one `randomId` per `(serverId, taskId)` draft across retries;
 * - blocks overlapping sends and suppresses duplicate hand-offs once [handingOff] is set;
 * - checks [currentServerId] after awaiting network calls so a server switch opens nothing.
 */
internal class TaskPendingPageController(
    private val drafts: TaskPendingDraftStore,
    private val newRandomId: () -> String = ::newThreadSendRandomId,
) {
    var sending by mutableStateOf(false)
        private set
    var handingOff by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var errorTraceId by mutableStateOf<String?>(null)
        private set

    fun draft(serverId: String?, taskId: String): String = drafts.draft(serverId, taskId)

    fun onDraftChange(serverId: String?, taskId: String, text: String) {
        drafts.update(serverId, taskId, text)
    }

    /**
     * Returns true when [task] now opens as a conversation (someone else sent first, `task:updated`)
     * and this page should replace itself with [THREAD_PAGE]. Marks [handingOff] to ensure a single
     * replacement.
     */
    fun claimConversationArrived(task: TaskSummary): Boolean {
        if (sending || handingOff) return false
        if (task.taskOpenSurface() != null) return false
        handingOff = true
        errorText = null
        errorTraceId = null
        return true
    }

    /** Clears [handingOff] if an attempted conversation hand-off could not complete. */
    fun resetHandOff() {
        handingOff = false
    }

    suspend fun send(
        task: TaskSummary,
        text: String,
        serverId: String,
        tasksDataSource: SharedTasksDataSource,
        sendMessage: suspend (channelId: String, body: String, randomId: String) -> ThreadApiResult<*>,
        currentServerId: () -> String?,
        fallbackError: String,
    ): ChannelId? {
        if (sending || handingOff || text.isBlank()) return null
        val taskId = task.id.value
        drafts.update(serverId, taskId, text)
        val randomId = drafts.randomId(serverId, taskId, newRandomId)
        sending = true
        errorText = null
        errorTraceId = null
        try {
            val started = startTaskConversation(task, ServerId(serverId), text, tasksDataSource, randomId, sendMessage)
            if (currentServerId() != serverId) {
                return null
            }
            return when (started) {
                is TaskConversationStart.Started -> {
                    drafts.clear(serverId, taskId)
                    handingOff = true
                    started.conversationChannelId
                }
                is TaskConversationStart.Failed -> {
                    errorText = started.serverMessage ?: fallbackError
                    errorTraceId = started.traceId
                    null
                }
            }
        } finally {
            sending = false
        }
    }
}

/**
 * Builds the `pageData` that replaces [ComposeTaskPendingPage] in place without animation once the
 * task's conversation exists.
 */
internal fun taskPendingReplaceConversationPageData(launchPayload: WireThreadLaunchPayload): JSONObject =
    threadLaunchPageData(launchPayload)
        .put(RAFT_REPLACE_CURRENT_PAGE, true)
        .withRaftOpenAnimation(false)

@Composable
private fun rememberTaskServerTasksViewModel(
    enabled: Boolean,
    authStore: RaftAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: build.raft.app.data.SharedTasksDataStore?,
    log: (String) -> Unit,
): SharedTasksViewModel? {
    val viewModel =
        if (enabled) {
            viewModel(key = "task-pending-server-tasks") {
                SharedTasksViewModel(
                    authStore = authStore,
                    dataSource = tasksDataSource,
                    sharedTasksDataStore = tasksDataStore,
                    scope = SharedTasksScope.Server,
                    diagnosticsLogger = log,
                )
            }
        } else {
            null
        }
    LaunchedEffect(viewModel) { viewModel?.dispatch(SharedTasksRouteIntent.Start) }
    return viewModel
}

@Composable
private fun TaskPendingConversationRoute(pageVisible: Boolean) {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val routerModule = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val params = pager.pageData.params
    val storeScope =
        remember(params.toString(), pager.pagerId) {
            params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val route = remember(params.toString(), storeScope) { parseTaskPendingRouteParams(params, storeScope) }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val foregroundBannerStore = remember(sharedStores, storeScope) { sharedStores.foregroundBannerStore(storeScope) }
    val conversationIdentityProvider =
        remember(sharedStores, storeScope) { sharedStores.conversationIdentityProvider(storeScope) }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val realtimeRoomLeaseRegistry =
        remember(sharedStores, storeScope) { sharedStores.realtimeRoomLeaseRegistry(storeScope) }
    val tasksDataSource = remember(networkClient) { SharedTasksDataSource(api = NetworkRaftTasksApi(networkClient)) }
    val detailsRepository = remember(networkClient) { TaskConversationDetailsRepository(networkClient) }
    val threadApi = remember(networkClient) { RaftThreadApiClient(NetworkRaftThreadApi(networkClient)) }
    val channelRepository =
        remember(authStore, networkClient, bridge) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("task_pending_channel $message") },
            )
        }
    val realtimeEvents = remember(sharedStores) { RaftRealtimeEvents(sharedStores.eventBus) }
    val coroutineScope = rememberCoroutineScope()

    var sessionLoaded by remember(authStore) { mutableStateOf(false) }
    val session by
        produceState<StoredAuthSession?>(initialValue = null, authStore) {
            value = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
            sessionLoaded = true
            val events = (authStore as? RaftAuthSessionEventSource)?.authSessionEvents ?: return@produceState
            events.collect { value = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull() }
        }
    val resolvedServerId = route.serverId ?: session?.currentServerId?.takeIf { it.isNotBlank() }
    val liveSessionServerId =
        if (sessionLoaded) {
            session?.currentServerId?.takeIf { it.isNotBlank() }
        } else {
            resolvedServerId
        }
    val latestServerId by rememberUpdatedState(liveSessionServerId)
    val latestPageVisible by rememberUpdatedState(pageVisible)

    val seed =
        remember(foregroundBannerStore, resolvedServerId, route.taskId) {
            foregroundBannerStore.taskPendingDrafts.seeded(resolvedServerId, route.taskId)
        }
    val seededTask = seed?.task
    val resolvedChannelId = route.channelId ?: seededTask?.channelId

    // Ensure SharedTasksDataStore holds the tapped task so status/assignee/amend writes have a
    // local route projection even when the task came from the Tasks tab query API.
    LaunchedEffect(seededTask?.id, resolvedServerId) {
        val seedToUpsert = seededTask ?: return@LaunchedEffect
        val serverId = resolvedServerId ?: return@LaunchedEffect
        sharedStores.tasksDataStore.upsertTaskForCurrentOwner(serverId, seedToUpsert)
    }

    val channelTasks =
        rememberTaskChannelTasksViewModel(
            channelId = resolvedChannelId,
            authStore = authStore,
            tasksDataSource = tasksDataSource,
            tasksDataStore = sharedStores.tasksDataStore,
            log = bridge::log,
        )
    val channelTasksState = channelTasks?.state?.collectAsState()?.value

    // Fallback when restored without a channelId in pageData (e.g. process death on a channel-less task).
    val serverTasks =
        rememberTaskServerTasksViewModel(
            enabled = resolvedChannelId == null,
            authStore = authStore,
            tasksDataSource = tasksDataSource,
            tasksDataStore = sharedStores.tasksDataStore,
            log = bridge::log,
        )
    val serverTasksState = serverTasks?.state?.collectAsState()?.value

    val channelProjectionSlice by
        remember(sharedStores.tasksDataStore, resolvedServerId, resolvedChannelId) {
            val channelId = resolvedChannelId
            if (resolvedServerId != null && channelId != null) {
                sharedStores.tasksDataStore.projectionFlow(resolvedServerId, SharedTasksScope.Channel(channelId))
            } else {
                flowOf(SharedTasksProjectionSlice())
            }
        }.collectAsState(
            initial =
                remember(sharedStores.tasksDataStore, resolvedServerId, resolvedChannelId) {
                    val channelId = resolvedChannelId
                    if (resolvedServerId != null && channelId != null) {
                        sharedStores.tasksDataStore.projection(resolvedServerId, SharedTasksScope.Channel(channelId))
                    } else {
                        SharedTasksProjectionSlice()
                    }
                }
        )

    val serverProjectionSlice by
        remember(sharedStores.tasksDataStore, resolvedServerId) {
            if (resolvedServerId != null) {
                sharedStores.tasksDataStore.projectionFlow(resolvedServerId, SharedTasksScope.Server)
            } else {
                flowOf(SharedTasksProjectionSlice())
            }
        }.collectAsState(
            initial =
                remember(sharedStores.tasksDataStore, resolvedServerId) {
                    if (resolvedServerId != null) {
                        sharedStores.tasksDataStore.projection(resolvedServerId, SharedTasksScope.Server)
                    } else {
                        SharedTasksProjectionSlice()
                    }
                }
        )

    val liveTask =
        resolvePendingTaskSummary(
            taskId = route.taskId,
            channelTasksState?.currentTaskSummary(route.taskId),
            channelProjectionSlice.tasks.firstOrNull { it.id.value == route.taskId },
            serverTasksState?.currentTaskSummary(route.taskId),
            serverProjectionSlice.tasks.firstOrNull { it.id.value == route.taskId },
            seededTask,
        )

    val directory by
        remember(conversationIdentityProvider, resolvedServerId) {
            resolvedServerId?.takeIf { it.isNotBlank() }?.let { id ->
                conversationIdentityProvider?.observeServer(ServerId(id))
            } ?: flowOf(SharedConversationDirectoryState())
        }.collectAsState(initial = SharedConversationDirectoryState())

    val baseTimeCapabilities = remember(params.toString()) { params.threadTimeCapabilities() }
    val runtimeTimeFormat =
        RaftTimeFormatRuntime.observe(fallback = baseTimeCapabilities?.timeFormat ?: RaftTimeFormat24Hour)
    val timeCapabilities =
        remember(baseTimeCapabilities, runtimeTimeFormat) {
            baseTimeCapabilities?.copy(timeFormat = runtimeTimeFormat)
        }
    val inheritedTimeContextProvider = LocalRaftTimeContextProvider.current
    val fallbackTimeContextProvider = currentRaftTimeContextProvider
    val ownTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = timeCapabilities,
            active = inheritedTimeContextProvider == null && timeCapabilities != null,
        )
    val timeContextProvider =
        inheritedTimeContextProvider
            ?: ownTimeContextProvider.takeIf { timeCapabilities != null }
            ?: fallbackTimeContextProvider
            ?: ownTimeContextProvider
    val timeContext = timeContextProvider.dateContext

    val controller = remember(foregroundBannerStore) { TaskPendingPageController(foregroundBannerStore.taskPendingDrafts) }
    var joinedLocally by remember(resolvedChannelId) { mutableStateOf(false) }
    var pendingSentConversationId by remember(route.taskId) { mutableStateOf<ChannelId?>(null) }
    var pendingSentTask by remember(route.taskId) { mutableStateOf<TaskSummary?>(null) }
    val pendingServerId = resolvedServerId?.takeIf { it.isNotBlank() }
    val pendingChannelId = liveTask?.channelId
    val pendingTaskNumber = liveTask?.taskNumber
    var summaryDetailHydrationSettled by remember(route.taskId, pendingServerId, pendingChannelId, pendingTaskNumber) {
        mutableStateOf(false)
    }

    // Belt-and-suspenders: if this pending route ever receives a `detail=summary` task row
    // (`!conversationDetailsLoaded`, e.g. from process restoration or a fallback open), fetch the
    // full row via `GET /tasks/channel/:channelId/number/:n` so an existing conversation hands off
    // immediately instead of staying on "No discussion in this task yet".
    val needsSummaryHydration =
        liveTask != null &&
            !liveTask.conversationDetailsLoaded &&
            liveTask.conversationChannelId == null &&
            !summaryDetailHydrationSettled &&
            pendingServerId != null &&
            pendingChannelId != null &&
            pendingTaskNumber != null
    LaunchedEffect(
        liveTask?.id,
        liveTask?.conversationDetailsLoaded,
        pendingServerId,
        pendingChannelId,
        pendingTaskNumber,
    ) {
        val summaryTask = liveTask ?: return@LaunchedEffect
        if (summaryDetailHydrationSettled || summaryTask.conversationDetailsLoaded || summaryTask.conversationChannelId != null) {
            return@LaunchedEffect
        }
        val serverId = pendingServerId ?: return@LaunchedEffect
        val channelId = pendingChannelId ?: return@LaunchedEffect
        val taskNumber = pendingTaskNumber ?: return@LaunchedEffect
        val detail =
            tasksDataSource.loadChannelTaskByNumber(
                serverId = ServerId(serverId),
                channelId = channelId,
                taskNumber = taskNumber,
                priority = build.raft.app.data.DataRequestPriority.Visible,
            )
        if (detail is DataResult.Content && detail.data.task.id == summaryTask.id) {
            sharedStores.tasksDataStore.upsertTaskForCurrentOwner(serverId, detail.data.task)
        }
        summaryDetailHydrationSettled = true
    }

    val sendFailedLabel = stringResource(Res.string.task_conversation_send_failed)
    val updateTaskFailedLabel = stringResource(Res.string.thread_error_update_task)
    val updateAssigneeFailedLabel = stringResource(Res.string.thread_error_update_assignee)
    val unableToOpenTaskLabel = stringResource(Res.string.thread_error_open_task)
    val joinedChannelLabel = stringResource(Res.string.thread_feedback_joined_channel)
    val unableToJoinChannelLabel = stringResource(Res.string.thread_error_join_channel)

    LaunchedEffect(channelTasks) {
        channelTasks?.uiEvents?.collect { event ->
            if (event is SharedTasksEvent.OperationFailed) {
                bridge.showTaskOperationFailedToast(
                    event = event,
                    updateFailedLabel = updateTaskFailedLabel,
                    assignFailedLabel = updateAssigneeFailedLabel,
                )
            }
        }
    }

    fun openConversationReplacingCurrentPage(
        targetTask: TaskSummary,
        resolvedConversationChannelId: ChannelId?,
        source: String,
    ): Boolean {
        val serverId = latestServerId?.takeIf { it.isNotBlank() }
        val launchPayload =
            serverId?.let { id ->
                buildStandaloneTaskThreadLaunchPayload(
                    task = targetTask,
                    serverId = id,
                    conversationDirectory = directory,
                    timeContext = timeContext,
                    alignOwnMessagesRight = route.alignOwnMessagesRight,
                    compactConsecutiveMessages = route.compactConsecutiveMessages,
                    resolvedConversationChannelId = resolvedConversationChannelId,
                )
            }
        if (launchPayload == null) {
            bridge.logWarn("task_pending_replace_rejected source=$source taskId=${targetTask.id.value}")
            bridge.toast(unableToOpenTaskLabel)
            return false
        }
        val targetStoreScope =
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId = serverId,
            )
        RaftProductEvents.recorder.markTaskThreadOpening(launchPayload.instanceId)
        RaftRatingPrompt.onTaskOpened(targetTask)
        bridge.log(
            "task_pending_replace_conversation source=$source taskId=${targetTask.id.value} " +
                "conversation=${resolvedConversationChannelId?.value.orEmpty()}"
        )
        return pager.openRaftPage(
            pageName = THREAD_PAGE,
            pageData = taskPendingReplaceConversationPageData(launchPayload),
            storeScopeOverride = targetStoreScope,
        )
    }

    // If a first send succeeded while this page was covered by another pushed page (e.g. a
    // notification or deep link arrived mid-send), finish replacing this page once it returns to
    // the top of the stack rather than touching the covering page.
    LaunchedEffect(pendingSentConversationId, pageVisible, resolvedServerId, liveSessionServerId) {
        val deferredId = pendingSentConversationId ?: return@LaunchedEffect
        val serverId = resolvedServerId?.takeIf { it.isNotBlank() }
        if (serverId == null || liveSessionServerId != serverId) {
            pendingSentConversationId = null
            pendingSentTask = null
            controller.resetHandOff()
            return@LaunchedEffect
        }
        if (!pageVisible) return@LaunchedEffect
        val targetTask = liveTask ?: pendingSentTask
        pendingSentConversationId = null
        pendingSentTask = null
        if (targetTask == null || !openConversationReplacingCurrentPage(targetTask, deferredId, source = "task_first_send_resumed")) {
            controller.resetHandOff()
        }
    }

    // If someone else writes first (`task:updated` delivers a conversation), seamlessly replace this
    // page in place with the real task conversation page. Launch inside page-owned coroutineScope
    // so subsequent `task:updated` emissions while resolving a joint-channel conversation cannot
    // cancel the hand-off and leave `controller.handingOff` stuck true.
    LaunchedEffect(liveTask, resolvedServerId, pageVisible) {
        if (!pageVisible) return@LaunchedEffect
        val current = liveTask ?: return@LaunchedEffect
        val serverId = resolvedServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!controller.claimConversationArrived(current)) return@LaunchedEffect
        coroutineScope.launch {
            val requiresResolvedConversation = current.taskOpenTarget() == TaskOpenTarget.ResolveConversation
            val conversationId =
                try {
                    resolveTaskOpenConversationChannelId(
                        task = current,
                        serverId = serverId,
                        tasksDataSource = tasksDataSource,
                        log = bridge::log,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    controller.resetHandOff()
                    throw cancelled
                }
            if (requiresResolvedConversation && conversationId == null) {
                bridge.logWarn("task_pending_arrival_resolve_failed taskId=${current.id.value}")
                bridge.toast(unableToOpenTaskLabel)
                controller.resetHandOff()
                return@launch
            }
            if (latestServerId != serverId || !latestPageVisible) {
                controller.resetHandOff()
                return@launch
            }
            val opened =
                openConversationReplacingCurrentPage(
                    targetTask = current,
                    resolvedConversationChannelId = conversationId,
                    source = "task_conversation_arrived",
                )
            if (opened) {
                foregroundBannerStore.taskPendingDrafts.clearSeed(serverId, current.id.value)
            } else {
                controller.resetHandOff()
            }
        }
    }

    // If the task was loaded in the channel list and later disappeared (deleted remotely), pop back.
    var observedInChannelList by remember(route.taskId) { mutableStateOf(false) }
    val channelSettledTasks =
        if (channelTasksState != null && !channelTasksState.standIn && !channelTasksState.refreshing) {
            when (val load = channelTasksState.loadState) {
                is ScreenLoadState.Content -> load.data.tasks
                is ScreenLoadState.Empty -> emptyList()
                else -> null
            }
        } else {
            null
        }
    LaunchedEffect(channelSettledTasks, route.taskId) {
        val loadedTasks = channelSettledTasks ?: return@LaunchedEffect
        val present = loadedTasks.any { it.id.value == route.taskId }
        if (present) {
            observedInChannelList = true
        } else if (observedInChannelList && !controller.sending && !controller.handingOff) {
            foregroundBannerStore.taskPendingDrafts.clearSeed(latestServerId, route.taskId)
            routerModule.closePage()
        }
    }

    // Keep the page in place while the first send is in flight.
    RaftBackHandler(enabled = controller.sending || controller.handingOff, label = "task_pending_sending") {}

    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContextProvider) {
    RaftDesignSystemTheme {
        if (liveTask == null) {
            if (route.taskId.isBlank()) {
                LaunchedEffect(Unit) { routerModule.closePage() }
            }
            val activeState = channelTasksState ?: serverTasksState
            val activeLoadState = activeState?.loadState
            val activeSettled = activeState != null && !activeState.standIn && !activeState.refreshing
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                // In the Tasks page's task column the column's Expand and Close end the head (web
                // TaskConversationLoading on a desktop), with no Back; as a page of its own Back leads it.
                val taskColumnHead = LocalRaftTaskColumnHead.current
                RaftTopPageHeader(
                    title = "",
                    backgroundColor = RaftColor.Canvas,
                    topSafeAreaOverride = null,
                    contentStartPadding = if (taskColumnHead == null) RaftBackButtonDefaults.EdgeInset else ThreadSplitDetailEdgeInset,
                    contentEndPadding = if (taskColumnHead == null) RaftTopPageHeaderContentEndPadding else RaftBackButtonDefaults.EdgeInset,
                    contentSpacing = ThreadHeaderContentSpacing,
                    titleLeadingContent =
                        if (taskColumnHead == null) {
                            {
                                RaftBackButton(
                                    onClick = { routerModule.closePage() },
                                    label = stringResource(Res.string.common_back),
                                )
                            }
                        } else {
                            null
                        },
                    trailingContent = if (taskColumnHead != null) { { RaftTaskColumnHeadEnd(taskColumnHead) } } else null,
                )
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    when (activeLoadState) {
                        is ScreenLoadState.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RaftBanner(
                                    description =
                                        activeLoadState.error.serverMessage?.takeIf { it.isNotBlank() }
                                            ?: unableToOpenTaskLabel,
                                    status = RaftBannerStatus.Warning,
                                )
                                RaftTraceIdNote(
                                    traceId = activeLoadState.error.traceId,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                RaftButton(
                                    text = stringResource(Res.string.common_retry),
                                    onClick = {
                                        channelTasks?.dispatch(SharedTasksRouteIntent.Refresh)
                                        serverTasks?.dispatch(SharedTasksRouteIntent.Refresh)
                                    },
                                    modifier = Modifier.padding(top = 12.dp),
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Md,
                                )
                            }
                        }
                        is ScreenLoadState.NoPermission -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RaftBanner(
                                    description =
                                        activeLoadState.blocker.serverMessage?.takeIf { it.isNotBlank() }
                                            ?: unableToOpenTaskLabel,
                                    status = RaftBannerStatus.Warning,
                                )
                                RaftTraceIdNote(
                                    traceId = activeLoadState.blocker.traceId,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        is ScreenLoadState.Content, is ScreenLoadState.Empty -> {
                            if (activeSettled) {
                                LaunchedEffect(route.taskId) { routerModule.closePage() }
                            } else {
                                RaftSpinner()
                            }
                        }
                        else -> RaftSpinner()
                    }
                }
            }
            return@RaftDesignSystemTheme
        }

        val access =
            resolveTaskPendingChannelAccess(
                task = liveTask,
                serverId = resolvedServerId,
                directory = directory,
                joinedLocally = joinedLocally,
                seed = seed,
            )
        val edits = channelTasks?.let { TaskHeadEdits(it::dispatch) }

        TaskPendingConversationPage(
            task = liveTask,
            details = rememberTaskConversationDetails(liveTask, detailsRepository, resolvedServerId),
            members = rememberTaskChannelMembers(serverMembersStore, resolvedServerId, liveTask.channelId),
            timeContext = timeContext,
            editable = edits != null && channelTasksState?.standIn != true,
            canWriteChannel = access.canWriteChannel,
            statusUpdating = channelTasksState?.statusUpdatingTaskId == liveTask.id.value,
            sending = controller.sending || controller.handingOff,
            errorText = controller.errorText,
            errorTraceId = controller.errorTraceId,
            joined = access.joined,
            draft = controller.draft(resolvedServerId, liveTask.id.value),
            onDraftChange = { controller.onDraftChange(resolvedServerId, liveTask.id.value, it) },
            onBack = { if (!controller.sending && !controller.handingOff) routerModule.closePage() },
            onSend = { text ->
                val serverId = latestServerId?.takeIf { it.isNotBlank() } ?: return@TaskPendingConversationPage
                coroutineScope.launch {
                    val conversationId =
                        controller.send(
                            task = liveTask,
                            text = text,
                            serverId = serverId,
                            tasksDataSource = tasksDataSource,
                            sendMessage = { channelId, body, randomId ->
                                threadApi.sendMessage(
                                    channelId = channelId,
                                    body = body,
                                    asTask = false,
                                    randomId = randomId,
                                )
                            },
                            currentServerId = { latestServerId },
                            fallbackError = sendFailedLabel,
                        )
                    bridge.log("task_pending_first_send taskId=${liveTask.id.value} opened=${conversationId != null}")
                    conversationId?.let { resolvedId ->
                        if (latestServerId != serverId) {
                            controller.resetHandOff()
                        } else if (latestPageVisible) {
                            if (!openConversationReplacingCurrentPage(liveTask, resolvedId, source = "task_first_send")) {
                                controller.resetHandOff()
                            }
                        } else {
                            // Covered by another pushed page while sending: defer replacement until visible again.
                            pendingSentTask = liveTask
                            pendingSentConversationId = resolvedId
                        }
                    }
                }
            },
            onJoin = {
                val channelId = liveTask.channelId ?: return@TaskPendingConversationPage
                if (ThreadChannelJoinsInFlight.begin(channelId)) {
                    coroutineScope.launch {
                        try {
                            when (val result = channelRepository.joinChannel(channelId)) {
                                is DataResult.Content -> {
                                    joinedLocally = true
                                    latestServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                                        realtimeRoomLeaseRegistry.markJoinedByUser(serverId, result.data.channelId.value)
                                        sharedHomeStore?.dispatch(
                                            SharedHomeIntent.ChannelJoined(
                                                serverId = serverId,
                                                channelId = result.data.channelId,
                                            )
                                        )
                                        sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                    }
                                    realtimeEvents.joinChannel(result.data.channelId.value, latestServerId)
                                    bridge.toast(joinedChannelLabel)
                                }
                                is DataResult.NoPermission ->
                                    bridge.errorToast(unableToJoinChannelLabel, result.blocker)
                                is DataResult.Error ->
                                    bridge.errorToast(unableToJoinChannelLabel, result.error)
                                else -> bridge.toast(unableToJoinChannelLabel)
                            }
                        } finally {
                            ThreadChannelJoinsInFlight.end(channelId)
                        }
                    }
                }
            },
            onUpdateStatus = { status -> edits?.updateStatus(liveTask, status) },
            onAssign = { member -> edits?.assign(liveTask, member) },
            serverId = resolvedServerId,
            onMoveToBacklog = { reason -> edits?.moveToBacklog(liveTask, reason) },
            onAmend = channelTasks.taskHeadAmend(liveTask),
            useHostSafeArea = true,
            loadingDiscussion = needsSummaryHydration,
        )
    }
    }
}
