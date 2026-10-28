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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.currentTaskSummary
import build.raft.app.model.TaskStatus
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import kotlinx.coroutines.delay
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
 * - [Pending]: a task that opens as its conversation but has none yet (a pre-v2 task with the
 *   flag on, or a v2 task created to have one). Web opens the task dialog empty — "opening does
 *   not create" (`TaskConversationDialog`) — and the first send creates the conversation
 *   (`threadStore.ensureTaskModalThreadChannel`: POST /tasks/:id/conversation, then the message).
 * - [Legacy]: a legacy orphan (`isLegacy`), shown read-only (web `LegacyTaskPanel`).
 */
internal sealed interface TaskOpenSurface {
    val task: TaskSummary

    data class Pending(override val task: TaskSummary) : TaskOpenSurface

    data class Legacy(override val task: TaskSummary) : TaskOpenSurface
}

/** The surface [task] opens on, or null when it opens a page ([TaskOpenTarget] Conversation / ResolveConversation / HostThread). */
internal fun TaskSummary.taskOpenSurface(taskV2Enabled: Boolean): TaskOpenSurface? =
    when (taskOpenTarget(taskV2Enabled)) {
        TaskOpenTarget.PendingConversation -> TaskOpenSurface.Pending(this)
        TaskOpenTarget.LegacyPanel -> TaskOpenSurface.Legacy(this)
        else -> null
    }

/** The first send into a task without a conversation: [Started] on its conversation, or [Failed] with the server's words. */
internal sealed interface TaskConversationStart {
    data class Started(val conversationChannelId: ChannelId) : TaskConversationStart

    /** [serverMessage]: the server's `error` text when it gave one (web shows it), else null. */
    data class Failed(val serverMessage: String?) : TaskConversationStart
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
            is DataResult.Error -> return TaskConversationStart.Failed(opened.error.serverMessage)
            is DataResult.NoPermission -> return TaskConversationStart.Failed(opened.blocker.serverMessage)
            is DataResult.Empty -> return TaskConversationStart.Failed(null)
        }
    return when (val sent = send(conversation.value, text, randomId)) {
        is ThreadApiResult.Success -> TaskConversationStart.Started(conversation)
        is ThreadApiResult.Failure -> TaskConversationStart.Failed(sent.toThreadAttachmentCommentsFailureProjection().serverMessage)
    }
}

/**
 * The page's task-open surface ([TaskOpenSurface]) and its first send, shared by every place a task
 * opens (thread, Tasks tab):
 * - one `randomId` per draft, reused on every retry until a send succeeds, so a first message the
 *   server took before the answer was lost is not posted twice (a repeated POST /conversation
 *   answers the same conversation);
 * - the typed text is kept per task while the page lives (closing the sheet keeps it; the app's
 *   composer drafts are keyed by a conversation channel, which this task does not have yet);
 * - the task follows the store ([onTaskChanged]): fresh status and assignee, and when its
 *   conversation appears (someone else wrote first, `task:updated`) the caller opens it, as web
 *   `TaskConversationDialog` binds to it;
 * - a send that finishes after the reader switched server opens nothing (web stops too).
 */
internal class TaskOpenSurfaceFlow(private val newRandomId: () -> String = ::newThreadSendRandomId) {
    var surface by mutableStateOf<TaskOpenSurface?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    /**
     * The first send created the conversation and its page is opening over this one: the pending
     * page stays (its composer off) until the host is covered ([finishHandOff]), so the reader
     * never sees the page underneath in between.
     */
    var handingOff by mutableStateOf(false)
        private set
    private val drafts = mutableStateMapOf<String, String>()
    private val randomIds = mutableMapOf<String, String>()

    fun open(surface: TaskOpenSurface) {
        if (sending) return
        this.surface = surface
        handingOff = false
        errorText = null
    }

    /** Closes the page unless a send runs; the draft stays for the next open. */
    fun dismiss(): Boolean {
        if (sending) return false
        surface = null
        handingOff = false
        errorText = null
        return true
    }

    /** The conversation page that the first send opened now covers the pending one: it goes. */
    fun finishHandOff() {
        if (!handingOff) return
        handingOff = false
        surface = null
        errorText = null
    }

    fun draft(taskId: String): String = drafts[taskId].orEmpty()

    fun onDraftChange(taskId: String, text: String) {
        drafts[taskId] = text
    }

    /**
     * The store's copy of the open task: refreshes the page, or — when it no longer opens here
     * (its conversation appeared) — closes it and returns the task for the caller to open. An
     * older revision than the one shown is ignored (several stores feed it); while the first send
     * hands off, the page it opens is already on its way.
     */
    fun onTaskChanged(fresh: TaskSummary?, taskV2Enabled: Boolean): TaskSummary? {
        val current = surface ?: return null
        if (fresh == null || fresh.id != current.task.id || fresh == current.task || handingOff) return null
        val shownRevision = current.task.revision
        if (fresh.revision != null && shownRevision != null && fresh.revision < shownRevision) return null
        return when (val next = fresh.taskOpenSurface(taskV2Enabled)) {
            null -> {
                if (sending) return null
                surface = null
                errorText = null
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
        val key = task.id.value
        drafts[key] = text
        val randomId = randomIds.getOrPut(key, newRandomId)
        sending = true
        errorText = null
        try {
            val started = startTaskConversation(task, ServerId(serverId), text, tasksDataSource, randomId, sendMessage)
            if (currentServerId() != serverId) {
                // web: the task's context changed while its conversation was created; stop.
                return null
            }
            return when (started) {
                is TaskConversationStart.Started -> {
                    drafts.remove(key)
                    randomIds.remove(key)
                    handingOff = true
                    task to started.conversationChannelId
                }
                is TaskConversationStart.Failed -> {
                    errorText = started.serverMessage ?: fallbackError
                    null
                }
            }
        } finally {
            sending = false
        }
    }
}

/**
 * What a task page needs to change its task (web TaskProperties in the task dialog): the task
 * channel's [SharedTasksViewModel], the one the conversation page's head writes through
 * ([rememberTaskChannelTasksViewModel]), and the reader's role on the server for the gates.
 */
internal class TaskPageEditing(
    val authStore: RaftAuthSessionStore,
    val tasksDataSource: SharedTasksDataSource,
    val tasksDataStore: SharedTasksDataStore?,
    /** The reader's role on the task's server (owner / admin / member / guest); null = not known. */
    val serverRole: String?,
    /** A refused write's [SharedTasksEvent.OperationFailed] code, for the host's toast. */
    val onFailed: (String) -> Unit = {},
    val log: (String) -> Unit = {},
)

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
    taskV2Enabled: Boolean = true,
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
                flow.onTaskChanged(listed, taskV2Enabled)?.let(onConversationArrived)
            }
            val onFailed by rememberUpdatedState(editing?.onFailed)
            LaunchedEffect(channelTasks) {
                channelTasks?.uiEvents?.collect { event ->
                    if (event is SharedTasksEvent.OperationFailed) onFailed?.invoke(event.code)
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
            val edits = channelTasks?.let { TaskHeadEdits(it::dispatch) }
            TaskPendingConversationWindow(
                onBack = { flow.dismiss() },
                sending = flow.sending || flow.handingOff,
            ) {
                TaskPendingConversationPage(
                    task = task,
                    details = rememberTaskConversationDetails(task, detailsRepository, serverId),
                    members = rememberTaskChannelMembers(membersStore, serverId, task.channelId),
                    timeContext = timeContext,
                    serverRole = editing?.serverRole,
                    editable = edits != null && channelTasksState?.standIn != true,
                    statusUpdating = channelTasksState?.statusUpdatingTaskId == task.id.value,
                    sending = flow.sending || flow.handingOff,
                    errorText = flow.errorText,
                    joined = joined(task),
                    draft = flow.draft(task.id.value),
                    onDraftChange = { flow.onDraftChange(task.id.value, it) },
                    onBack = { flow.dismiss() },
                    onSend = { text -> onSend(task, text) },
                    onJoin = { onJoin(task) },
                    onUpdateStatus = { status -> edits?.updateStatus(task, status) },
                    onAssign = { member -> edits?.assign(task, member) },
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

    fun assign(task: TaskSummary, assignee: MemberProfile?) = dispatch(SharedTasksRouteIntent.AssignTask(task, assignee))
}

/** The page state of a task that has no conversation yet: nothing followed, nothing resolved. */
internal fun TaskSummary.taskPendingHeaderState(): TaskConversationHeaderState =
    TaskConversationHeaderState(conversationChannelId = conversationChannelId ?: ChannelId(""))

/**
 * The pending task page's window: full screen over the host page, as a page of its own. Back
 * closes it unless a send runs.
 */
@Composable
internal fun TaskPendingConversationWindow(onBack: () -> Unit, sending: Boolean, content: @Composable () -> Unit) {
    RaftWindowDialog(
        onDismissRequest = { if (!sending) onBack() },
        properties = RaftWindowDialogProperties(drawScrim = false),
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            content()
        }
    }
}

/**
 * A task before its first message (web `TaskConversationDialog` with no conversation yet), drawn as
 * the task conversation page it becomes, so nothing moves when the first send opens it:
 * - the same top bar (the task's ref over "Task #N"; its status and assignee once the head has
 *   scrolled away, [TaskConversationBarTitle]);
 * - the same head ([TaskConversationHeadRoute]): title, description, facts and History, with the
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
    serverRole: String?,
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
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val state = task.taskPendingHeaderState()
    val chrome =
        task.taskConversationHeaderChrome(
            state = state,
            summarized = headScrollState.collapsed,
            onBackToHead = { headScrollState.expand() },
            onViewInChannel = {},
            onToggleFollow = {},
        )
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The conversation page's canvas, so its composer dock sits on the same ground as there.
    Column(modifier.fillMaxSize().background(colors.layerCanvas)) {
        // The conversation page's top bar (ThreadPage's header for a task conversation).
        RaftTopPageHeader(
            title = task.taskRefTitle(),
            backgroundColor = RaftColor.Canvas,
            topSafeAreaOverride = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
            contentStartPadding = RaftBackButtonDefaults.EdgeInset,
            contentSpacing = ThreadHeaderContentSpacing,
            titleLeadingContent = {
                RaftBackButton(onClick = { if (!sending) onBack() }, label = stringResource(Res.string.common_back))
            },
            titleContent = { TaskConversationBarTitle(chrome, Modifier.weight(1f)) },
        )
        Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(headScrollState.connection)) {
            TaskConversationHeadRoute(
                task = task,
                state = state,
                details = details.state,
                serverRole = serverRole,
                statusUpdating = statusUpdating,
                assigneeCandidates = members,
                timeContext = timeContext,
                onUpdateStatus = onUpdateStatus,
                onAssign = onAssign,
                // Source and Earlier discussion lead into the channel from the conversation page.
                onOpenSource = null,
                onOpenEarlierDiscussion = null,
                scrollState = headScrollState,
                keyboardUp = keyboardUp,
                onRetryHistory = details.retryHistory,
                editable = editable,
            )
            // Where the messages go: the empty state, and a refused send's words.
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                errorText?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
            }
        }
        if (joined == false) {
            ThreadJoinFooter(label = stringResource(Res.string.task_conversation_join_to_reply), onJoin = onJoin)
        } else {
            // The conversation page's own composer in its dock: multi-line, paste, its send button.
            ThreadComposerDock {
                ThreadComposerContent(
                    payload =
                        RaftThreadComposerPayload(
                            instanceId = "task-pending:${task.id.value}",
                            seedText = draft,
                            placeholder = stringResource(Res.string.task_conversation_composer_placeholder),
                            enabled = !sending,
                            attachmentActionsEnabled = false,
                            originalImageOptionEnabled = false,
                            showAsTaskToggle = false,
                        ),
                    onEvent = { action, value, _, _ -> if (action == "change" && value != null) onDraftChange(value) },
                    onSendRequest = { request ->
                        val text = request.value.text
                        if (sending || text.isBlank()) {
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
