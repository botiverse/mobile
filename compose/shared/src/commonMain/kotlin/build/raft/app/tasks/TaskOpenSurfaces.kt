package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.isAssignFailure
import build.raft.app.data.isStatusFailure
import build.raft.app.data.toScreenError
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskAmendPatch
import build.raft.app.data.TaskAmendResult
import build.raft.app.data.currentTaskSummary
import build.raft.app.model.TaskStatus
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.rememberRaftStableWindowInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.TaskConversationDetailsRepository
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Where opening a task lands when it is not a page of its own yet (slock web `useOpenTask`):
 * - [Pending]: a task that opens as its conversation but has none yet (a pre-v2 task, or a v2
 *   task created to have one). Web opens the task dialog empty — "opening does
 *   not create" (`TaskConversationDialog`) — and the first send creates the conversation
 *   (`threadStore.ensureTaskModalThreadChannel`: POST /tasks/:id/conversation, then the message).
 * - [Legacy]: a legacy orphan (`isLegacy`), shown read-only (web `LegacyTaskPanel`).
 */
internal sealed interface TaskOpenSurface {
    val task: TaskSummary

    data class Pending(override val task: TaskSummary) : TaskOpenSurface

    data class Legacy(override val task: TaskSummary) : TaskOpenSurface
}

/** The surface [task] opens on, or null when it opens a page ([TaskOpenTarget] Conversation / ResolveConversation). */
internal fun TaskSummary.taskOpenSurface(): TaskOpenSurface? =
    when (taskOpenTarget()) {
        TaskOpenTarget.PendingConversation -> TaskOpenSurface.Pending(this)
        TaskOpenTarget.LegacyPanel -> TaskOpenSurface.Legacy(this)
        else -> null
    }

/** The first send into a task without a conversation: [Started] on its conversation, or [Failed] with the server's words. */
internal sealed interface TaskConversationStart {
    data class Started(val conversationChannelId: ChannelId) : TaskConversationStart

    /** [serverMessage]: the server's `error` text when it gave one (web shows it), else null. */
    data class Failed(val serverMessage: String?, val traceId: String? = null) : TaskConversationStart
}

/**
 * Web `ensureTaskModalThreadChannel` + the send: POST /tasks/:id/conversation (this server's local
 * conversation; creating it broadcasts `task:updated`; a repeat answers the same one), then the
 * message into it with [randomId]. Only a send creates the conversation; opening never does.
 */
internal suspend fun startTaskConversation(
    task: TaskSummary,
    serverId: ServerId,
    body: String,
    tasksDataSource: SharedTasksDataSource,
    /** The draft's one id: a retry reuses it, so a send the server already took is not posted twice. */
    randomId: String,
    send: suspend (channelId: String, body: String, randomId: String) -> ThreadApiResult<*>,
): TaskConversationStart {
    val text = body.trim()
    if (text.isEmpty()) return TaskConversationStart.Failed(null)
    val conversation =
        when (val opened = tasksDataSource.openTaskConversation(serverId, task.id)) {
            is DataResult.Content -> opened.data.conversationChannelId
            is DataResult.Error -> return TaskConversationStart.Failed(opened.error.serverMessage, opened.error.traceId)
            is DataResult.NoPermission -> return TaskConversationStart.Failed(opened.blocker.serverMessage, opened.blocker.traceId)
            is DataResult.Empty -> return TaskConversationStart.Failed(null)
        }
    return when (val sent = send(conversation.value, text, randomId)) {
        is ThreadApiResult.Success -> TaskConversationStart.Started(conversation)
        is ThreadApiResult.Failure -> {
            val projection = sent.toThreadAttachmentCommentsFailureProjection()
            TaskConversationStart.Failed(projection.serverMessage, projection.traceId)
        }
    }
}

/**
 * In-memory first-frame seed for a pushed [TASK_PENDING_CONVERSATION_PAGE] route (identity-only
 * `pageData` never carries product objects). Holds the [task] tapped on the opener and optional
 * channel access hints until the live store / conversation directory hydrates.
 */
internal data class TaskPendingSeed(
    val task: TaskSummary,
    val channelJoined: Boolean? = null,
    val canWriteChannel: Boolean? = null,
)

/**
 * The first message typed into a task that has no conversation yet ([TaskOpenSurface.Pending]),
 * per server and task, for one window's signed-in session. The conversation composer's drafts are
 * keyed by a conversation channel, which such a task does not have, so they live here instead: the
 * window's [SharedForegroundBannerStore] owns one, and every page that opens a task (a channel or
 * thread page, the Tasks tab) passes it to its [TaskOpenSurfaceFlow]. Closing the page that opened
 * the task, or following a link elsewhere, keeps the draft; it is not saved across app restarts.
 *
 * Each draft keeps its first-send `randomId` too, so a retry from a reopened page does not post the
 * message twice. Cleared for a task when its first send created the conversation, and as a whole
 * on sign-out or another account ([followSession]), which also drops servers the account left.
 */
internal class TaskPendingDraftStore {
    private data class Key(val serverId: String, val taskId: String)

    private val drafts = mutableStateMapOf<Key, String>()
    private val randomIds = mutableMapOf<Key, String>()
    private val seeds = mutableMapOf<Key, TaskPendingSeed>()
    private var accountId: String? = null

    private fun key(serverId: String?, taskId: String) = Key(serverId.orEmpty(), taskId)

    fun draft(serverId: String?, taskId: String): String = drafts[key(serverId, taskId)].orEmpty()

    fun update(serverId: String?, taskId: String, text: String) {
        drafts[key(serverId, taskId)] = text
    }

    /** The draft's one first-send id, made by [newRandomId] the first time and reused until [clear]. */
    fun randomId(serverId: String?, taskId: String, newRandomId: () -> String): String =
        randomIds.getOrPut(key(serverId, taskId), newRandomId)

    /** Stores the tapped task and optional channel access hints for instant Frame-1 rendering. */
    fun seed(
        serverId: String?,
        task: TaskSummary,
        channelJoined: Boolean? = null,
        canWriteChannel: Boolean? = null,
    ) {
        seeds[key(serverId, task.id.value)] =
            TaskPendingSeed(
                task = task,
                channelJoined = channelJoined,
                canWriteChannel = canWriteChannel,
            )
    }

    fun seeded(serverId: String?, taskId: String): TaskPendingSeed? = seeds[key(serverId, taskId)]

    fun seededTask(serverId: String?, taskId: String): TaskSummary? = seeded(serverId, taskId)?.task

    fun clearSeed(serverId: String?, taskId: String) {
        seeds.remove(key(serverId, taskId))
    }

    /** The first send created the task's conversation: its text is a message now, not a draft. */
    fun clear(serverId: String?, taskId: String) {
        val key = key(serverId, taskId)
        drafts.remove(key)
        randomIds.remove(key)
        seeds.remove(key)
    }

    fun clearAll() {
        drafts.clear()
        randomIds.clear()
        seeds.clear()
    }

    /**
     * The window's sign-in: [signedOut] drops every draft; another account drops the previous
     * one's; a known [serverIds] list drops drafts of servers no longer in it (left, removed,
     * deleted). A session still restoring ([accountId] null) changes nothing.
     */
    fun followSession(signedOut: Boolean, accountId: String?, serverIds: Collection<String>?) {
        if (signedOut) {
            clearAll()
            this.accountId = null
            return
        }
        if (accountId == null) return
        if (this.accountId != null && this.accountId != accountId) clearAll()
        this.accountId = accountId
        if (serverIds.isNullOrEmpty()) return
        drafts.keys.filter { it.serverId !in serverIds }.forEach { drafts.remove(it) }
        randomIds.keys.filter { it.serverId !in serverIds }.forEach { randomIds.remove(it) }
        seeds.keys.filter { it.serverId !in serverIds }.forEach { seeds.remove(it) }
    }
}

/**
 * The window's [TaskPendingDraftStore] follows its sign-in: sign-out, another account, or a server
 * no longer in the session drops the drafts that went with it.
 */
@Composable
internal fun TaskPendingDraftsFollowSessionEffect(
    drafts: TaskPendingDraftStore,
    authGateState: SharedAuthGateState,
    readySession: StoredAuthSession?,
) {
    val signedOut = authGateState == SharedAuthGateState.AuthRequired
    val accountId = readySession?.user?.id
    val serverIds = readySession?.servers?.map { it.id }
    LaunchedEffect(drafts, signedOut, accountId, serverIds) {
        drafts.followSession(signedOut, accountId, serverIds)
    }
}

/**
 * The page's task-open surface ([TaskOpenSurface]) and its first send, shared by every place a task
 * opens (thread, Tasks tab):
 * - one `randomId` per draft, reused on every retry until a send succeeds, so a first message the
 *   server took before the answer was lost is not posted twice (a repeated POST /conversation
 *   answers the same conversation);
 * - the typed text is kept per server and task in [drafts] (closing the sheet keeps it; the app's
 *   composer drafts are keyed by a conversation channel, which this task does not have yet). The
 *   pages pass the window's [TaskPendingDraftStore], so the draft outlives the page that opened
 *   the task (leaving the channel, a link);
 * - the task follows the store ([onTaskChanged]): fresh status and assignee, and when its
 *   conversation appears (someone else wrote first, `task:updated`) the caller opens it, as web
 *   `TaskConversationDialog` binds to it;
 * - a send that finishes after the reader switched server opens nothing (web stops too).
 */
internal class TaskOpenSurfaceFlow(
    private val drafts: TaskPendingDraftStore = TaskPendingDraftStore(),
    private val newRandomId: () -> String = ::newThreadSendRandomId,
) {
    var surface by mutableStateOf<TaskOpenSurface?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var errorTraceId by mutableStateOf<String?>(null)
        private set

    /**
     * The first send created the conversation and its page is opening over this one: the pending
     * page stays (its composer off) until the host is covered ([finishHandOff]), so the reader
     * never sees the page underneath in between.
     */
    var handingOff by mutableStateOf(false)
        private set

    fun open(surface: TaskOpenSurface) {
        if (sending) return
        this.surface = surface
        handingOff = false
        errorText = null
        errorTraceId = null
    }

    /** Closes the page unless a send runs; the draft stays for the next open. */
    fun dismiss(): Boolean {
        if (sending) return false
        surface = null
        handingOff = false
        errorText = null
        errorTraceId = null
        return true
    }

    /**
     * The window's dismiss request: Back, or the host page being covered by a pushed page
     * ([build.raft.designsystem.components.RaftDismissOnPageCover]). While the first send hands
     * off, the push is the conversation page it opened, so the pending page goes now: a covered
     * page composes no more (iOS), so [finishHandOff] from the host's visibility never ran and the
     * page stayed over the conversation, a window layer with its draft. Otherwise as [dismiss].
     */
    fun requestClose(): Boolean {
        if (handingOff) {
            finishHandOff()
            return true
        }
        return dismiss()
    }

    /** The conversation page that the first send opened now covers the pending one: it goes. */
    fun finishHandOff() {
        if (!handingOff) return
        handingOff = false
        surface = null
        errorText = null
        errorTraceId = null
    }

    fun draft(serverId: String?, taskId: String): String = drafts.draft(serverId, taskId)

    fun onDraftChange(serverId: String?, taskId: String, text: String) {
        drafts.update(serverId, taskId, text)
    }

    /**
     * The store's copy of the open task: refreshes the page, or — when it no longer opens here
     * (its conversation appeared) — closes it and returns the task for the caller to open. An
     * older revision than the one shown is ignored (several stores feed it); while the first send
     * hands off, the page it opens is already on its way.
     */
    fun onTaskChanged(fresh: TaskSummary?): TaskSummary? {
        val current = surface ?: return null
        if (fresh == null || fresh.id != current.task.id || fresh == current.task || handingOff) return null
        val shownRevision = current.task.revision
        if (fresh.revision != null && shownRevision != null && fresh.revision < shownRevision) return null
        return when (val next = fresh.taskOpenSurface()) {
            null -> {
                if (sending) return null
                surface = null
                errorText = null
                errorTraceId = null
                fresh
            }
            else -> {
                surface = next
                null
            }
        }
    }

    /**
     * The first send ([startTaskConversation]) of the open pending task: the conversation to open,
     * or null (nothing open, a send already running, a failure shown in [errorText], or the reader
     * left [serverId] meanwhile).
     */
    suspend fun send(
        text: String,
        serverId: String,
        tasksDataSource: SharedTasksDataSource,
        sendMessage: suspend (channelId: String, body: String, randomId: String) -> ThreadApiResult<*>,
        currentServerId: () -> String?,
        fallbackError: String,
    ): Pair<TaskSummary, ChannelId>? {
        val task = (surface as? TaskOpenSurface.Pending)?.task ?: return null
        if (sending || text.isBlank()) return null
        val taskId = task.id.value
        drafts.update(serverId, taskId, text)
        val randomId = drafts.randomId(serverId, taskId, newRandomId)
        sending = true
        sendRunning.value = true
        errorText = null
        errorTraceId = null
        try {
            val started = startTaskConversation(task, ServerId(serverId), text, tasksDataSource, randomId, sendMessage)
            if (currentServerId() != serverId) {
                // web: the task's context changed while its conversation was created; stop.
                return null
            }
            return when (started) {
                is TaskConversationStart.Started -> {
                    drafts.clear(serverId, taskId)
                    handingOff = true
                    task to started.conversationChannelId
                }
                is TaskConversationStart.Failed -> {
                    errorText = started.serverMessage ?: fallbackError
                    errorTraceId = started.traceId
                    null
                }
            }
        } finally {
            sending = false
            sendRunning.value = false
        }
    }

    /** [sending] for [closeForIncomingNavigation], which waits on it outside composition. */
    private val sendRunning = MutableStateFlow(false)

    /**
     * Something else opens in the window ([RaftIncomingNavigationSignal]): the surface closes and
     * its draft stays. A running first send is not cut off (the message may already be posted, and
     * its conversation page is on the way): the open waits until the send ends, then the surface
     * closes either way through [requestClose] (a finished first send's hand-off ends; after a
     * failure the typed text is kept as the draft). True when a surface was open.
     */
    suspend fun closeForIncomingNavigation(): Boolean {
        if (surface == null) return false
        sendRunning.first { running -> !running }
        requestClose()
        return true
    }
}

/**
 * What a task page needs to change its task (web TaskProperties in the task dialog): the task
 * channel's [SharedTasksViewModel], the one the conversation page's head writes through
 * ([rememberTaskChannelTasksViewModel]).
 */
internal class TaskPageEditing(
    val authStore: RaftAuthSessionStore,
    val tasksDataSource: SharedTasksDataSource,
    val tasksDataStore: SharedTasksDataStore?,
    /** A refused write's [SharedTasksEvent.OperationFailed] code, for the host's toast. */
    val onFailed: (String) -> Unit = {},
    val onFailedWithTrace: ((code: String, traceId: String?) -> Unit)? = null,
    val onOperationFailed: ((SharedTasksEvent.OperationFailed) -> Unit)? = null,
    val log: (String) -> Unit = {},
)

/**
 * Surfaces a refused task status or assignee mutation as a structured error toast (with server
 * refusal reason and Trace ID via [BridgeModule.errorToast]). Out of `SharedThreadRouteContentContent`
 * to keep that composable below its JVM bytecode ceiling.
 */
internal fun build.raft.app.base.BridgeModule.showTaskOperationFailedToast(
    event: SharedTasksEvent.OperationFailed,
    updateFailedLabel: String,
    assignFailedLabel: String,
    onToast: (title: String, error: build.raft.app.data.ScreenError) -> Unit = { title, error ->
        errorToast(title, error)
    },
    routeAuthRequired: (String?) -> Boolean = { false },
) {
    val label =
        when {
            event.isStatusFailure() -> updateFailedLabel
            event.isAssignFailure() -> assignFailedLabel
            else -> return
        }
    if (routeAuthRequired(event.code)) return
    onToast(label, event.toScreenError())
}

/** Renders the flow's surface (the pending task page or the legacy panel). */
@Composable
internal fun TaskOpenSurfaceHost(
    flow: TaskOpenSurfaceFlow,
    onSend: (TaskSummary, String) -> Unit,
    /** Whether the reader is a member of the task's channel; null = not known here. */
    joined: (TaskSummary) -> Boolean? = { null },
    onJoin: (TaskSummary) -> Unit = {},
    /** The pending page's head reads (history, source, earlier discussion); null reads nothing. */
    detailsRepository: TaskConversationDetailsRepository? = null,
    serverId: String? = null,
    timeContext: RaftTimeContext? = null,
    /** The task channel's members, so the page names people (and offers assignees) as the conversation page does. */
    membersStore: SharedServerMembersStore? = null,
    /** Null: the head shows status and assignee as plain facts. */
    editing: TaskPageEditing? = null,
    /** The host page is on screen: once a page opened over it after the first send, the pending page goes. */
    hostVisible: Boolean = true,
    /** The task's conversation appeared (someone wrote first, `task:updated`): the caller opens it. */
    onConversationArrived: (TaskSummary) -> Unit = {},
    /** Whether the reader may write the task's channel (web canWriteTasksInChannel); unknown: yes. */
    canWriteTaskChannel: (ChannelId?) -> Boolean = { true },
    /** Signed-in user's id so self-assigned tasks display "You". */
    viewerUserId: String? = null,
) {
    when (val surface = flow.surface) {
        is TaskOpenSurface.Pending -> {
            val channelTasks =
                editing?.let { rememberTaskChannelTasksViewModel(surface.task.channelId, it.authStore, it.tasksDataSource, it.tasksDataStore, it.log) }
            val channelTasksState = channelTasks?.state?.collectAsState()?.value
            val listed = channelTasksState?.currentTaskSummary(surface.task.id.value)
            // The page follows the task channel's copy (an edit's optimistic value, its rollback, a
            // `task:updated`), and gives way to the conversation once it exists.
            LaunchedEffect(listed) {
                flow.onTaskChanged(listed)?.let(onConversationArrived)
            }
            val onFailed by rememberUpdatedState(editing?.onFailed)
            val onFailedWithTrace by rememberUpdatedState(editing?.onFailedWithTrace)
            val onOperationFailed by rememberUpdatedState(editing?.onOperationFailed)
            LaunchedEffect(channelTasks) {
                channelTasks?.uiEvents?.collect { event ->
                    if (event is SharedTasksEvent.OperationFailed) {
                        onOperationFailed?.invoke(event)
                            ?: onFailedWithTrace?.invoke(event.code, event.traceId)
                            ?: onFailed?.invoke(event.code)
                    }
                }
            }
            LaunchedEffect(flow.handingOff, hostVisible) {
                if (!flow.handingOff) return@LaunchedEffect
                // The conversation page is over this one now; where it opened in place (a wide
                // layout's pane) the host stays visible, so it goes after the page had time to show.
                if (hostVisible) delay(TaskPendingHandOffFallbackMs)
                flow.finishHandOff()
            }
            val task = surface.task
            val resolvedServer = serverId?.takeIf { it.isNotBlank() }
            val taskChannelId = task.channelId
            val taskNumber = task.taskNumber
            val hydrationDataSource = editing?.tasksDataSource
            val currentEditing by rememberUpdatedState(editing)
            val currentOnConversationArrived by rememberUpdatedState(onConversationArrived)
            var summaryDetailHydrationSettled by remember(task.id, resolvedServer, taskChannelId, taskNumber) {
                mutableStateOf(false)
            }
            val needsSummaryHydration =
                hydrationDataSource != null &&
                    !task.conversationDetailsLoaded &&
                    task.conversationChannelId == null &&
                    !summaryDetailHydrationSettled &&
                    resolvedServer != null &&
                    taskChannelId != null &&
                    taskNumber != null
            LaunchedEffect(
                task.id,
                task.conversationDetailsLoaded,
                resolvedServer,
                taskChannelId,
                taskNumber,
                hydrationDataSource,
            ) {
                if (
                    hydrationDataSource == null ||
                    summaryDetailHydrationSettled ||
                    task.conversationDetailsLoaded ||
                    task.conversationChannelId != null
                ) {
                    return@LaunchedEffect
                }
                val activeServer = resolvedServer ?: return@LaunchedEffect
                val channelId = taskChannelId ?: return@LaunchedEffect
                val number = taskNumber ?: return@LaunchedEffect
                val detail =
                    hydrationDataSource.loadChannelTaskByNumber(
                        serverId = ServerId(activeServer),
                        channelId = channelId,
                        taskNumber = number,
                        priority = build.raft.app.data.DataRequestPriority.Visible,
                    )
                if (detail is DataResult.Content && detail.data.task.id == task.id) {
                    val fullTask = detail.data.task
                    currentEditing?.tasksDataStore?.upsertTaskForCurrentOwner(activeServer, fullTask)
                    flow.onTaskChanged(fullTask)?.let(currentOnConversationArrived)
                }
                summaryDetailHydrationSettled = true
            }
            val edits = channelTasks?.let { TaskHeadEdits(it::dispatch) }
            val agentStatusResolver =
                remember(membersStore, serverId) {
                    membersStore?.let { store -> raftScopedAgentStatusResolver(store, serverId) }
                }
            TaskPendingConversationWindow(onDismissRequest = { flow.requestClose() }) {
                TaskPendingConversationPage(
                    task = task,
                    details = rememberTaskConversationDetails(task, detailsRepository, serverId),
                    members = rememberTaskChannelMembers(membersStore, serverId, task.channelId),
                    timeContext = timeContext,
                    editable = edits != null && channelTasksState?.standIn != true,
                    // Web canWriteTasksInChannel: the host says whether the reader may write the task's channel.
                    canWriteChannel = canWriteTaskChannel(task.channelId),
                    statusUpdating = channelTasksState?.statusUpdatingTaskId == task.id.value,
                    sending = flow.sending || flow.handingOff,
                    errorText = flow.errorText,
                    errorTraceId = flow.errorTraceId,
                    joined = joined(task),
                    draft = flow.draft(serverId, task.id.value),
                    onDraftChange = { flow.onDraftChange(serverId, task.id.value, it) },
                    onBack = { flow.dismiss() },
                    onSend = { text -> onSend(task, text) },
                    onJoin = { onJoin(task) },
                    onUpdateStatus = { status -> edits?.updateStatus(task, status) },
                    onAssign = { member -> edits?.assign(task, member) },
                    serverId = serverId,
                    onMoveToBacklog = { reason -> edits?.moveToBacklog(task, reason) },
                    onAmend = channelTasks.taskHeadAmend(task),
                    loadingDiscussion = needsSummaryHydration,
                    viewerUserId = viewerUserId,
                    agentStatusResolver = agentStatusResolver,
                )
            }
        }
        is TaskOpenSurface.Legacy -> TaskLegacyPanelSheet(task = surface.task, onDismiss = { flow.dismiss() })
        null -> Unit
    }
}

/** How long the pending page stays after the first send when no page covered it (a wide layout's pane). */
internal const val TaskPendingHandOffFallbackMs = 1_000L

/**
 * The task channel's [SharedTasksViewModel] (web `useChannelTasks`): the one store a task page's
 * head reads its task from and writes status and assignee through, with the board's optimistic
 * update, rollback and `task:updated` fold. The conversation page and the pending page share it
 * (one key per channel). Null without a channel.
 */
@Composable
internal fun rememberTaskChannelTasksViewModel(
    channelId: ChannelId?,
    authStore: RaftAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    log: (String) -> Unit,
): SharedTasksViewModel? {
    val viewModel =
        channelId?.let { parentChannelId ->
            viewModel(key = "task-conversation-tasks-${parentChannelId.value}") {
                SharedTasksViewModel(
                    authStore = authStore,
                    dataSource = tasksDataSource,
                    sharedTasksDataStore = tasksDataStore,
                    scope = SharedTasksScope.Channel(parentChannelId),
                    diagnosticsLogger = log,
                )
            }
        }
    LaunchedEffect(viewModel) { viewModel?.dispatch(SharedTasksRouteIntent.Start) }
    return viewModel
}

/**
 * A task head's writes (status and assignee), the same on every task page: the task channel's
 * [SharedTasksViewModel] intents, so the conversation page and the pending page take one path.
 */
internal class TaskHeadEdits(private val dispatch: (SharedTasksRouteIntent) -> Unit) {
    fun updateStatus(task: TaskSummary, status: TaskStatus) = dispatch(SharedTasksRouteIntent.UpdateStatus(task, status))

    /** A move into Backlog with the reason asked for it (null: none). */
    fun moveToBacklog(task: TaskSummary, reason: String?) =
        dispatch(SharedTasksRouteIntent.UpdateStatus(task, TaskStatus.Backlog, reason))

    fun assign(task: TaskSummary, assignee: MemberProfile?) = dispatch(SharedTasksRouteIntent.AssignTask(task, assignee))
}

/**
 * A task head's title / description write (slock #14114) through the task channel's store, the
 * same on every task page; null without a store (the head shows them as plain text).
 */
internal fun SharedTasksViewModel?.taskHeadAmend(task: TaskSummary): (suspend (TaskAmendPatch, Long?) -> TaskAmendResult)? =
    this?.let { viewModel -> { patch, expectedRevision -> viewModel.amendTask(task, patch, expectedRevision) } }

/**
 * A task page's Delete task (slock #14115) through the task channel's store: null once deleted (then
 * [onDeleted]), else the refusal for the confirmation. Null without a store.
 */
internal fun SharedTasksViewModel?.taskHeadDelete(task: TaskSummary, onDeleted: () -> Unit): (suspend () -> build.raft.app.data.ScreenError?)? =
    this?.let { viewModel -> { viewModel.deleteTask(task).also { if (it == null) onDeleted() } } }

/** The page state of a task that has no conversation yet: nothing followed, nothing resolved. */
internal fun TaskSummary.taskPendingHeaderState(): TaskConversationHeaderState =
    TaskConversationHeaderState(conversationChannelId = conversationChannelId ?: ChannelId(""))

/**
 * The pending task page's window: full screen over the host page, as a page of its own.
 * [onDismissRequest] gets Back and the host page being covered by a push; it reads the flow's
 * state when called ([TaskOpenSurfaceFlow.requestClose]): a value captured at composition was
 * stale by then (the first send's push comes before the host recomposes).
 */
@Composable
internal fun TaskPendingConversationWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    RaftWindowDialog(
        onDismissRequest = onDismissRequest,
        properties = RaftWindowDialogProperties(drawScrim = false),
    ) {
        // Opaque under the system bars too: without a scrim the host's tab bar showed through the
        // nav-bar strip, so the page looked like a floating sheet (web: full-screen, opaque).
        Box(
            Modifier
                .fillMaxSize()
                .background(RaftTheme.colors.layerCanvas)
                .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)))
        ) {
            content()
        }
    }
}

/**
 * A task before its first message (web `TaskConversationDialog` with no conversation yet), drawn as
 * the task conversation page it becomes, so nothing moves when the first send opens it:
 * - the same top bar (the task's ref over "Task #N"; on a phone its status and assignee too once
 *   the head is out of view, [TaskConversationBarTitle]);
 * - the same head ([TaskConversationHeadRoute]): on a phone the first row of the page under the
 *   bar, scrolling with it (and pulled down from the bar while it is out of view); wider, pinned
 *   above it. Title, description, facts and History, with the
 *   Status and Assignee editors under the same gates (guest, read-only task, Done freezes the
 *   assignee; [editable] false while there is no store to write to or it holds the offline
 *   stand-in);
 * - where the messages go, the empty state ("No discussion in this task yet", and for a pre-v2
 *   task where its earlier discussion is);
 * - the conversation composer at the bottom, or for a reader who has not joined the task's
 *   channel "Join Channel to Reply" ([joined] false; null = not known here, the composer shows
 *   and a refusal comes back as [errorText]).
 * Sending creates the conversation; a refused send keeps the typed text. Attachments, mentions,
 * Follow and the More menu wait for the conversation page.
 */
@Composable
internal fun TaskPendingConversationPage(
    task: TaskSummary,
    details: TaskHeadDetailsHandle,
    members: List<MemberProfile>,
    timeContext: RaftTimeContext?,
    editable: Boolean,
    statusUpdating: Boolean,
    sending: Boolean,
    errorText: String?,
    joined: Boolean?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onJoin: () -> Unit,
    onUpdateStatus: (TaskStatus) -> Unit,
    onAssign: (MemberProfile?) -> Unit,
    modifier: Modifier = Modifier,
    headScrollState: TaskHeadScrollState = remember(task.id) { TaskHeadScrollState() },
    /** The task's server: the head's faces read its member directory. */
    serverId: String? = null,
    /** A move into Backlog with its reason ([TaskConversationHeadRoute]); null: Backlog asks nothing. */
    onMoveToBacklog: ((reason: String?) -> Unit)? = null,
    /** Saves an edit of the title or description ([TaskConversationHeadRoute]); null: plain text. */
    onAmend: (suspend (TaskAmendPatch, Long?) -> TaskAmendResult)? = null,
    /** False: the reader may not write the task's channel; status, assignee and text are plain ([TaskConversationHeadRoute]). */
    canWriteChannel: Boolean = true,
    /**
     * True when mounted as a full pushed stack page ([ComposeTaskPendingPage]) rather than inside
     * [TaskPendingConversationWindow]: lets the top header, join footer and composer dock use the
     * host's safe-area and live keyboard insets directly, matching [ThreadPage] so nothing shifts
     * when the first send swaps in the conversation page.
     */
    useHostSafeArea: Boolean = false,
    errorTraceId: String? = null,
    /**
     * True while resolving whether a `detail=summary` task row already has a conversation on the
     * server: shows a spinner instead of falsely claiming "No discussion in this task yet".
     */
    loadingDiscussion: Boolean = false,
    /** Signed-in user's id so self-assigned tasks display "You". */
    viewerUserId: String? = null,
    /** Resolves an agent assignee's live status dot beside its face (null: no dot). */
    agentStatusResolver: RaftAgentStatusResolver? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val state = task.taskPendingHeaderState()
    val chrome =
        task.taskConversationHeaderChrome(
            state = state,
            summarized = headScrollState.collapsed,
            headFloatOpen = headScrollState.floatOpen,
            onToggleHeadFloat = { headScrollState.toggleFloat() },
            onViewInChannel = {},
            onToggleFollow = {},
            serverId = serverId,
            viewerUserId = viewerUserId,
        )
    val density = LocalDensity.current
    val keyboardUp =
        if (useHostSafeArea) {
            raftImeBottomPx(WindowInsets.ime, LocalRaftHostImeInsets.current, density) > 0
        } else {
            WindowInsets.ime.getBottom(density) > 0
        }
    val bottomSafeArea = if (useHostSafeArea) raftBottomSafeAreaPadding() else 0.dp
    val keyboardInsetExcludesBottomSafeArea = if (useHostSafeArea) raftKeyboardInsetExcludesBottomSafeArea() else false
    val jointSurface =
        parseConversationKindOrNull(task.channelType) == ConversationKind.Joint || LocalRaftJointActorSurface.current
    // Back puts the pulled-down head back up before it leaves the page.
    RaftBackHandler(enabled = headScrollState.floatOpen, label = "task_pending_head_float") { headScrollState.closeFloat() }
    // The conversation page's canvas, so its composer dock sits on the same ground as there.
    // A press anywhere on the page outside the pulled-down head puts it back up, the composer included.
    CompositionLocalProvider(LocalRaftJointActorSurface provides jointSurface) {
        Column(modifier.fillMaxSize().background(colors.layerCanvas).taskHeadFloatOutsidePress(headScrollState)) {
        // The conversation page's top bar (ThreadPage's header for a task conversation). In the Tasks page's task
        // column (web TaskConversationBar on a desktop) the column's Expand and Close end it and it has no Back, as
        // the conversation's own head there; as a page of its own Back leads it.
        val taskColumnHead = LocalRaftTaskColumnHead.current
        RaftTopPageHeader(
            title = task.taskRefTitle(),
            backgroundColor = RaftColor.Canvas,
            topSafeAreaOverride = if (useHostSafeArea) null else WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
            contentStartPadding = if (taskColumnHead == null) RaftBackButtonDefaults.EdgeInset else ThreadSplitDetailEdgeInset,
            contentEndPadding = if (taskColumnHead == null) RaftTopPageHeaderContentEndPadding else RaftBackButtonDefaults.EdgeInset,
            contentSpacing = ThreadHeaderContentSpacing,
            titleLeadingContent =
                if (taskColumnHead == null) {
                    { RaftBackButton(onClick = { if (!sending) onBack() }, label = stringResource(Res.string.common_back)) }
                } else {
                    null
                },
            titleContent = { TaskConversationBarTitle(chrome, Modifier.weight(1f)) },
            trailingContent = if (taskColumnHead != null) { { RaftTaskColumnHeadEnd(taskColumnHead) } } else null,
        )
        // The head, as on the conversation page: the first row of what scrolls here, and pulled down
        // from the bar while that row is out of view ([TaskHeadPlacement.Page] holds the page's effects).
        val headRoute: @Composable (TaskHeadPlacement) -> Unit = { placement ->
            TaskConversationHeadRoute(
                task = task,
                state = state,
                details = details.state,
                statusUpdating = statusUpdating,
                assigneeCandidates = members,
                timeContext = timeContext,
                onUpdateStatus = onUpdateStatus,
                onAssign = onAssign,
                onMoveToBacklog = onMoveToBacklog,
                onAmend = onAmend,
                // Source and Earlier discussion lead into the channel from the conversation page.
                onOpenSource = null,
                onOpenEarlierDiscussion = null,
                scrollState = headScrollState,
                keyboardUp = keyboardUp,
                onRetryHistory = details.retryHistory,
                editable = editable,
                canWriteChannel = canWriteChannel,
                placement = placement,
                serverId = serverId,
                viewerUserId = viewerUserId,
                agentStatusResolver = agentStatusResolver,
            )
        }
        val headRow = rememberTaskConversationHeadRow(headScrollState) { headRoute(TaskHeadPlacement.FirstRow) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Box(Modifier.fillMaxSize().nestedScroll(headScrollState.connection)) {
            val pageScroll = rememberScrollState()
            // The head is the top of what scrolls here: in view while any of it is (and before it is measured).
            LaunchedEffect(headScrollState, pageScroll) {
                snapshotFlow {
                    val headHeight = headScrollState.rowHeadHeightPx
                    headHeight <= 0 || pageScroll.value < headHeight
                }.collect { inView -> headScrollState.updateHeadInView(inView) }
            }
            Column(Modifier.fillMaxSize()) {
                headRoute(TaskHeadPlacement.Page)
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(pageScroll)) {
                    TaskConversationHeadRowContent(headRow)
                    // Where the messages go: the empty state, and a refused send's words.
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (loadingDiscussion) {
                                build.raft.designsystem.components.RaftSpinner()
                            } else {
                                RaftText(
                                    text = stringResource(Res.string.task_conversation_empty_title),
                                    style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.foreground,
                                )
                                if (task.hostMessageId != null) {
                                    RaftText(
                                        text = stringResource(Res.string.task_conversation_empty_body),
                                        style = typography.callout,
                                        color = colors.foregroundMuted,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                        errorText?.let {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RaftBanner(description = it, status = RaftBannerStatus.Warning)
                                RaftTraceIdNote(errorTraceId)
                            }
                        }
                    }
                }
            }
        }
            headRoute(TaskHeadPlacement.PullDown)
        }
        if (joined == false) {
            ThreadJoinFooter(
                label = stringResource(Res.string.task_conversation_join_to_reply),
                onJoin = onJoin,
                joining = ThreadChannelJoinsInFlight.observeIsJoining(task.channelId),
                // Inside TaskPendingConversationWindow safeDrawing bottom padding is already applied by the window.
                bottomSafeArea = bottomSafeArea,
            )
        } else {
            val composerKeyboardLiftPx =
                rememberThreadComposerKeyboardLiftPx(
                    bottomSafeArea = bottomSafeArea,
                    keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea,
                )
            // The conversation page's own composer in its dock: multi-line, paste, its send button.
            ThreadComposerDock(
                composerModifier =
                    if (useHostSafeArea) {
                        Modifier
                            .raftThreadComposerLiveKeyboardPadding(
                                bottomSafeArea = bottomSafeArea,
                                keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea,
                            )
                            .onFocusChanged { if (it.hasFocus) headScrollState.closeFloat() }
                    } else {
                        Modifier
                    },
                keyboardLiftPx = if (useHostSafeArea) composerKeyboardLiftPx else ({ 0f }),
            ) {
                ThreadComposerContent(
                    payload =
                        RaftThreadComposerPayload(
                            instanceId = "task-pending:${task.id.value}",
                            seedText = draft,
                            placeholder = stringResource(Res.string.task_conversation_composer_placeholder),
                            enabled = !sending && !loadingDiscussion,
                            attachmentActionsEnabled = false,
                            originalImageOptionEnabled = false,
                            showAsTaskToggle = false,
                        ),
                    onEvent = { action, value, _, _ -> if (action == "change" && value != null) onDraftChange(value) },
                    attachmentReorderEnabled = false,
                    onSendRequest = { request ->
                        val text = request.value.text
                        if (sending || loadingDiscussion || text.isBlank()) {
                            ThreadComposerSendAdmission.Rejected
                        } else {
                            onSend(text)
                            // The text stays until the conversation page opens (a refused send keeps it).
                            ThreadComposerSendAdmission.Deferred
                        }
                    },
                    attachmentPreviewEnabled = false,
                )
            }
        }
        }
    }
}

/**
 * A legacy task (web `LegacyTaskPanel`): read-only — its status, title and description, who created
 * it and who has it, and the read-only notice. Posting is not possible.
 */
@Composable
internal fun TaskLegacyPanelSheet(task: TaskSummary, onDismiss: () -> Unit) {
    RaftDialogSheet(
        onDismissRequest = onDismiss,
        title = taskLegacyPanelTitle(task),
        commitLabel = null,
        onCommit = {},
    ) {
        TaskLegacyPanelBody(task, Modifier.weight(1f, fill = false))
    }
}

/** web LegacyTaskPanel's head: `#channel` (or "unknown"); the task line sits under it. */
@Composable
internal fun taskLegacyPanelTitle(task: TaskSummary): String =
    "#" + (task.channelName?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.task_legacy_unknown_channel))

/** The legacy panel's body (the sheet's content; screenshot tests frame it themselves). */
@Composable
internal fun TaskLegacyPanelBody(task: TaskSummary, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val unknown = stringResource(Res.string.task_legacy_unknown)
    run {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // web: "Task #N · Legacy" in Space Mono under the channel.
            RaftText(
                stringResource(Res.string.task_legacy_heading, task.taskNumber?.toString() ?: "?"),
                style = typography.monoMeta,
                color = colors.foregroundHint,
            )
            RaftTaskStatusBadge(status = task.status.raftTaskStatus(), text = task.status.localizedLabel())
            RaftCard(Modifier.fillMaxWidth()) {
                LegacyLabel(stringResource(Res.string.task_legacy_title))
                RaftText(task.title, style = typography.headline, color = colors.foreground)
                LegacyLabel(stringResource(Res.string.task_legacy_description), Modifier.padding(top = 12.dp))
                RaftText(
                    task.description?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.task_legacy_no_description),
                    style = typography.callout,
                    color = colors.foreground,
                )
            }
            RaftCard(Modifier.fillMaxWidth()) {
                LegacyProperty(stringResource(Res.string.task_legacy_created_by), task.creator?.displayName?.let { "@$it" } ?: unknown)
                LegacyProperty(
                    stringResource(Res.string.task_legacy_assignee),
                    task.assignee?.displayName?.let { "@$it" } ?: stringResource(Res.string.tasks_assignee_unassigned),
                )
            }
            RaftBanner(
                title = stringResource(Res.string.task_legacy_read_only),
                description = stringResource(Res.string.task_legacy_read_only_hint),
                status = RaftBannerStatus.Info,
            )
        }
    }
}

@Composable
private fun LegacyLabel(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
        color = RaftTheme.colors.foregroundHint,
    )
}

@Composable
private fun LegacyProperty(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(
            label,
            modifier = Modifier.width(112.dp),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = RaftTheme.colors.foregroundHint,
        )
        RaftText(value, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
    }
}

/** `#N` and the title, as the task conversation's bar names it. */
internal fun TaskSummary.taskRefTitle(): String = taskNumber?.let { "#$it $title" } ?: title

/**
 * One task open at a time (not per task): while an open is still resolving (the joint POST) any
 * other tap is dropped, and a repeat tap on the same task within [repeatWindowMs] of a finished
 * open is dropped too, so a double tap neither POSTs twice nor pushes the same page twice.
 * [nowMs] is injectable for tests.
 */
internal class TaskOpenGate(
    private val repeatWindowMs: Long = 700L,
    private val nowMs: () -> Long = { TaskOpenGateEpoch.elapsedNow().inWholeMilliseconds },
) {
    private var inFlight: String? = null
    private var lastTaskId: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    fun tryBegin(taskId: String): Boolean {
        if (inFlight != null) return false
        val now = nowMs()
        if (taskId == lastTaskId && lastAtMs != Long.MIN_VALUE && now - lastAtMs < repeatWindowMs) return false
        inFlight = taskId
        return true
    }

    fun finish(taskId: String) {
        if (inFlight != taskId) return
        inFlight = null
        lastTaskId = taskId
        lastAtMs = nowMs()
    }
}

private val TaskOpenGateEpoch = kotlin.time.TimeSource.Monotonic.markNow()

/** While composed, [flow]'s surface closes before something else opens in the window ([signal]). */
@Composable
internal fun TaskOpenSurfaceIncomingNavigationEffect(flow: TaskOpenSurfaceFlow, signal: RaftIncomingNavigationSignal) {
    DisposableEffect(flow, signal) {
        val unregister = signal.register { flow.closeForIncomingNavigation() }
        onDispose { unregister() }
    }
}

/**
 * Resolves and opens a task tapped from the Tasks tab:
 * 1. Picks the freshest copy from [tasksDataStore] (channel scope, server scope, tapped row).
 * 2. If the row came from `GET /tasks/server?detail=summary` (`!conversationDetailsLoaded &&
 *    conversationChannelId == null`), hydrates the full row via `GET /tasks/channel/:channelId/number/:n`
 *    (task v2 is always on: every non-legacy task opens as its task conversation).
 * 3. Acquires [gate] once and always releases it in a single `try / finally`, verifies `full.id == stored.id`,
 *    checks [isStillActive], resolves joint conversation IDs inline, and opens the resulting surface or page.
 */
internal fun launchTasksTabTaskOpen(
    task: TaskSummary,
    serverId: String?,
    tasksDataStore: SharedTasksDataStore,
    tasksDataSource: SharedTasksDataSource,
    gate: TaskOpenGate,
    scope: CoroutineScope,
    isStillActive: () -> Boolean,
    openSurface: (TaskOpenSurface) -> Unit,
    openPage: (task: TaskSummary, resolvedConversationChannelId: ChannelId?) -> Unit,
    onLoadError: (build.raft.app.data.ScreenError) -> Unit,
    onNoPermission: (build.raft.app.data.PermissionBlocker) -> Unit,
    onNotFound: () -> Unit,
    log: (String) -> Unit,
) {
    val activeServerId = serverId?.takeIf { it.isNotBlank() }
    val storedTask =
        activeServerId?.let { id ->
            val channelMatch =
                task.channelId?.let { channelId ->
                    tasksDataStore
                        .projection(id, SharedTasksScope.Channel(channelId))
                        .tasks
                        .firstOrNull { it.id == task.id }
                }
            val serverMatch =
                tasksDataStore
                    .projection(id, SharedTasksScope.Server)
                    .tasks
                    .firstOrNull { it.id == task.id }
            resolvePendingTaskSummary(task.id.value, channelMatch, serverMatch, task)
        } ?: task

    val taskChannelId = storedTask.channelId
    val taskNumber = storedTask.taskNumber
    val needsHydration =
        !storedTask.conversationDetailsLoaded &&
            storedTask.conversationChannelId == null &&
            activeServerId != null &&
            taskChannelId != null &&
            taskNumber != null

    if (!needsHydration) {
        when (val surface = storedTask.taskOpenSurface()) {
            is TaskOpenSurface.Pending, is TaskOpenSurface.Legacy -> {
                openSurface(surface)
                return
            }
            null -> Unit
        }
    }

    val gateTaskId = storedTask.id.value
    if (!gate.tryBegin(gateTaskId)) {
        log("tasks_open_ignored reason=in_flight task=$gateTaskId")
        return
    }

    if (!needsHydration && storedTask.taskOpenTarget() != TaskOpenTarget.ResolveConversation) {
        try {
            openPage(storedTask, null)
        } finally {
            gate.finish(gateTaskId)
        }
        return
    }

    scope.launch {
        try {
            val resolvedTask =
                if (needsHydration) {
                    when (
                        val detail =
                            tasksDataSource.loadChannelTaskByNumber(
                                serverId = ServerId(activeServerId!!),
                                channelId = taskChannelId!!,
                                taskNumber = taskNumber!!,
                                priority = build.raft.app.data.DataRequestPriority.Foreground,
                            )
                    ) {
                        is DataResult.Content -> {
                            val fullTask = detail.data.task
                            if (fullTask.id == storedTask.id) {
                                tasksDataStore.upsertTaskForCurrentOwner(activeServerId, fullTask)
                                fullTask
                            } else {
                                log("tasks_open_hydrate_id_mismatch expected=$gateTaskId actual=${fullTask.id.value}")
                                if (isStillActive()) onNotFound()
                                return@launch
                            }
                        }
                        is DataResult.Error -> {
                            if (!isStillActive()) return@launch
                            if (
                                detail.error.code == build.raft.app.data.TaskByNumberNotFoundErrorCode ||
                                storedTask.conversationReplyCount > 0
                            ) {
                                onLoadError(detail.error)
                                return@launch
                            }
                            storedTask
                        }
                        is DataResult.NoPermission -> {
                            if (isStillActive()) onNoPermission(detail.blocker)
                            return@launch
                        }
                        is DataResult.Empty -> {
                            if (isStillActive()) onNotFound()
                            return@launch
                        }
                    }
                } else {
                    storedTask
                }

            if (!isStillActive()) return@launch
            when (val surface = resolvedTask.taskOpenSurface()) {
                is TaskOpenSurface.Pending, is TaskOpenSurface.Legacy -> {
                    openSurface(surface)
                    return@launch
                }
                null -> Unit
            }
            val conversationId =
                if (resolvedTask.taskOpenTarget() == TaskOpenTarget.ResolveConversation) {
                    resolveTaskOpenConversationChannelId(
                        task = resolvedTask,
                        serverId = activeServerId.orEmpty(),
                        tasksDataSource = tasksDataSource,
                        log = log,
                    )
                } else {
                    null
                }
            if (!isStillActive()) return@launch
            openPage(resolvedTask, conversationId)
        } finally {
            gate.finish(gateTaskId)
        }
    }
}

/** Opens an arrived task conversation (from a pending surface), resolving joint channel IDs first when needed. */
internal fun launchArrivedTaskConversationOpen(
    arrived: TaskSummary,
    serverId: String?,
    tasksDataSource: SharedTasksDataSource,
    scope: CoroutineScope,
    isStillActive: () -> Boolean,
    openPage: (task: TaskSummary, resolvedConversationChannelId: ChannelId?) -> Unit,
    log: (String) -> Unit,
) {
    if (arrived.taskOpenTarget() != TaskOpenTarget.ResolveConversation) {
        openPage(arrived, null)
        return
    }
    val activeServerId = serverId?.takeIf { it.isNotBlank() } ?: return
    scope.launch {
        val conversationId =
            resolveTaskOpenConversationChannelId(
                task = arrived,
                serverId = activeServerId,
                tasksDataSource = tasksDataSource,
                log = log,
            )
        if (isStillActive() && conversationId != null) {
            openPage(arrived, conversationId)
        }
    }
}
