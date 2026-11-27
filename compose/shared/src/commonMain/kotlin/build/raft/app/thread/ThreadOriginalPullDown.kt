package build.raft.app.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.EmptyRaftAgentStatusResolver
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftRelativeTimestampText
import build.raft.app.RaftTimestampDisplayGroup
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.externalAuthorShownName
import build.raft.app.markdown.markdownToPreview
import build.raft.app.markdown.toAnnotatedString
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.RaftMemberAvatar
import build.raft.app.members.RaftMemberAvatarIdentity
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import build.raft.app.projectedMemberKindOrNull
import build.raft.app.rememberExternalAuthorFallbackName
import build.raft.app.resources.*
import build.raft.app.withRaftFontWeight
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftPullDownPanel
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.gesture.rememberRaftBackSwipeDismiss
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Reply thread head task summary and pull-down of the first message and its tasks
 * (web botiverse/slock #17200: `threadTaskSummary.ts`, `useThreadOriginalPanel.ts`,
 * `ThreadOriginalPullDown.tsx`).
 *
 * Once the thread's first message has scrolled out of view, the head subtitle summarizes
 * its tasks ("2 tasks · 1 waiting on you", or "All tasks done") and the title carries a
 * chevron that pulls the first message (3 lines) and its folded task list down over the
 * replies via [RaftPullDownPanel], keeping "Jump to this message" for scroll-to-top.
 */

internal const val ThreadHeaderTaskSummaryTestTag = "thread-header-task-summary"
internal const val ThreadHeaderTaskWaitingTestTag = "thread-header-task-waiting"
internal const val ThreadHeaderTitleChevronTestTag = "thread-title-chevron"
internal const val ThreadOriginalPullDownTestTag = "thread-original-pull-down"
internal const val ThreadOriginalPullDownTextTestTag = "thread-original-pull-down-text"
internal const val ThreadOriginalPullDownTasksTestTag = "thread-original-pull-down-tasks"
internal const val ThreadOriginalPullDownEmptyTestTag = "thread-original-pull-down-empty"
internal const val ThreadOriginalPullDownJumpTestTag = "thread-original-pull-down-jump"

/**
 * Whether `task_waiting_on_you_v0` is enabled on the current server, provided by `ThreadPage`
 * and defaulted to `false` (fail-closed) elsewhere.
 */
internal val LocalTaskWaitingOnYouEnabled = staticCompositionLocalOf { false }

@Composable
internal fun rememberTaskWaitingOnYouEnabled(
    flags: SharedServerFeatureFlags?,
    serverId: ServerId?,
): Boolean {
    val revision = flags?.snapshotRevision?.collectAsState()?.value
    return remember(flags, serverId?.value, revision) {
        flags?.isTaskWaitingOnYouEnabled(serverId) == true
    }
}

/** What the thread head says about the first message's tasks (web `ThreadTaskSummary`). */
@Immutable
internal data class ThreadTaskSummary(
    /** Tasks that are not Done or Closed. */
    val open: Int,
    /** Total tasks linked to the first message. */
    val total: Int,
    /** Open tasks waiting on the viewer (Todo / InProgress / InReview), only when `task_waiting_on_you_v0` is on. */
    val waitingOnYou: Int,
)

/** Localized subtitle pieces after the reply count (web `threadTaskSummaryParts`). */
@Immutable
internal data class ThreadTaskSummaryParts(
    val tasks: String?,
    val waiting: String?,
)

private val ThreadTaskWaitingStatuses: Set<TaskStatus> =
    setOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview)

/**
 * Counts open, total, and waiting-on-you tasks for the thread's first message
 * (web `summarizeThreadTasks` + `liveTaskWaitsOn`).
 */
internal fun summarizeThreadTasks(
    tasks: List<ThreadMessageSourceTaskItem>,
    waitingShown: Boolean,
): ThreadTaskSummary {
    var open = 0
    var waitingOnYou = 0
    tasks.forEach { task ->
        if (isThreadTaskCompleted(task.status)) return@forEach
        open += 1
        if (waitingShown && task.assigneeIsViewer && task.status in ThreadTaskWaitingStatuses) {
            waitingOnYou += 1
        }
    }
    return ThreadTaskSummary(open = open, total = tasks.size, waitingOnYou = waitingOnYou)
}

/**
 * Builds the task summary subtitle pieces (web `threadTaskSummaryParts`).
 * Returns empty pieces when the message has no tasks (`summary.total <= 0`).
 */
internal fun threadTaskSummaryParts(
    summary: ThreadTaskSummary,
    formatOneTask: () -> String,
    formatManyTasks: (Int) -> String,
    formatAllDone: () -> String,
    formatWaitingOnYou: (Int) -> String,
): ThreadTaskSummaryParts {
    if (summary.total <= 0) return ThreadTaskSummaryParts(tasks = null, waiting = null)
    val tasksText =
        when {
            summary.open == 1 -> formatOneTask()
            summary.open > 1 -> formatManyTasks(summary.open)
            else -> formatAllDone()
        }
    val waitingText = if (summary.waitingOnYou > 0) formatWaitingOnYou(summary.waitingOnYou) else null
    return ThreadTaskSummaryParts(tasks = tasksText, waiting = waitingText)
}

/** Joins the optional base subtitle (place + replies) with the task summary parts using `" · "`. */
internal fun appendThreadTaskSummarySubtitle(
    base: String?,
    parts: ThreadTaskSummaryParts?,
): String? =
    listOfNotNull(
        base?.takeIf { it.isNotBlank() },
        parts?.tasks?.takeIf { it.isNotBlank() },
        parts?.waiting?.takeIf { it.isNotBlank() },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
internal fun rememberLocalizedThreadTaskSummaryParts(summary: ThreadTaskSummary): ThreadTaskSummaryParts {
    if (summary.total <= 0) return ThreadTaskSummaryParts(tasks = null, waiting = null)
    val oneTaskLabel = stringResource(Res.string.thread_header_summary_tasks_one)
    val manyTasksLabel = if (summary.open > 1) stringResource(Res.string.thread_header_summary_tasks_many, summary.open) else ""
    val allDoneLabel = stringResource(Res.string.thread_header_summary_tasks_done)
    val waitingLabel =
        if (summary.waitingOnYou > 0) {
            stringResource(Res.string.thread_header_summary_waiting_on_you, summary.waitingOnYou)
        } else {
            ""
        }
    return threadTaskSummaryParts(
        summary = summary,
        formatOneTask = { oneTaskLabel },
        formatManyTasks = { manyTasksLabel },
        formatAllDone = { allDoneLabel },
        formatWaitingOnYou = { waitingLabel },
    )
}

/**
 * Header chrome passed to [ThreadPageHeader] when a reply thread's first message can be pulled down
 * once scrolled out of view.
 */
@Immutable
internal data class ThreadHeaderOriginalPullChrome(
    val summary: ThreadTaskSummary,
    val open: Boolean,
    val onToggle: () -> Unit,
)

/**
 * Subtitle task summary after the reply count (web `ThreadTaskSummaryText`): `" · 2 tasks"`,
 * followed by `" · 1 waiting on you"` in accent ink when any open task waits on the viewer.
 */
@Composable
internal fun ThreadHeaderTaskSummaryText(
    parts: ThreadTaskSummaryParts,
    leadingSeparator: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val tasksText = parts.tasks?.takeIf { it.isNotBlank() } ?: return
    val colors = RaftTheme.colors
    Row(
        modifier = modifier.testTag(ThreadHeaderTaskSummaryTestTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (leadingSeparator) " · $tasksText" else tasksText,
            style = style,
            color = colors.foregroundHint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        parts.waiting?.takeIf { it.isNotBlank() }?.let { waiting ->
            Text(
                text = " · ",
                style = style,
                color = colors.foregroundHint,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = waiting,
                modifier = Modifier.testTag(ThreadHeaderTaskWaitingTestTag),
                style = style.withRaftFontWeight(FontWeight.SemiBold),
                color = colors.attentionStrong,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Decorative chevron next to the swapped thread title (web `ThreadPanelHeader` `titleChevron`):
 * points down when closed, turns 180deg over 220ms `ease-slide` while the pull-down is open.
 */
@Composable
internal fun ThreadHeaderTitleChevron(
    open: Boolean,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
) {
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide),
        label = "thread-title-chevron",
    )
    RaftGlyphIcon(
        glyph = RaftGlyph.ChevronDown,
        modifier =
            modifier
                .clearAndSetSemantics { testTag = ThreadHeaderTitleChevronTestTag }
                .rotate(turn),
        size = 14.dp,
        color = RaftTheme.colors.foregroundHint,
    )
}

/**
 * Open state and closing triggers for [ThreadOriginalPullDown], keyed per thread instance
 * (web `useThreadOriginalPanel.ts`, mirroring [build.raft.app.TaskHeadScrollState]).
 *
 * Never opens on its own. Closes on a second title tap, tap outside, Back / Escape / swipe-back,
 * swipe up at the end of the panel, user scrolling the replies list (not an arriving message),
 * keyboard / composer focus, first message scrolling back into view, and switching threads.
 */
@Stable
internal class ThreadOriginalPullState {
    /** Whether the first message is currently scrolled away and available to pull down. */
    var available: Boolean by mutableStateOf(false)
        private set

    private var openRequested: Boolean by mutableStateOf(false)

    /** Bottom edge of the pull-down panel in root coordinates (presses below it are outside). */
    internal var panelBottomInRootPx: Float = 0f

    /** True while the pull-down is open over the replies. */
    val isOpen: Boolean
        get() = openRequested && available

    /** Updates whether the first message is out of view; returning into view closes the pull-down for good. */
    fun updateAvailable(canOpen: Boolean) {
        if (available != canOpen) available = canOpen
        if (!canOpen && openRequested) openRequested = false
    }

    /** Toggles the pull-down when the swapped thread title is tapped. */
    fun toggle() {
        openRequested = !isOpen && available
    }

    fun setOpen(open: Boolean) {
        openRequested = open && available
    }

    /** Closes the pull-down; returns true if it was open. */
    fun close(): Boolean {
        val wasOpen = isOpen
        openRequested = false
        return wasOpen
    }

    /** On an ancestor of the replies list: user scroll closes the pull-down; arriving messages do not. */
    val scrollCloseConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) openRequested = false
                return Offset.Zero
            }
        }
}

/** Whether a press at [rootY] lies below the pull-down panel whose bottom edge is [panelBottomInRootPx]. */
internal fun threadOriginalPullPressIsOutside(rootY: Float, panelBottomInRootPx: Float): Boolean =
    panelBottomInRootPx > 0f && rootY > panelBottomInRootPx

/**
 * On the thread page root: a press anywhere below the pull-down closes it while letting the press
 * continue to the replies or composer (matching [build.raft.app.taskHeadFloatOutsidePress]).
 * Presses on the top bar above the panel are not outside, so tapping the title toggles cleanly.
 */
internal fun Modifier.threadOriginalPullOutsidePress(state: ThreadOriginalPullState): Modifier =
    composed {
        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
        onGloballyPositioned { coordinates[0] = it }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val layout = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    if (state.isOpen &&
                        threadOriginalPullPressIsOutside(layout.localToRoot(down.position).y, state.panelBottomInRootPx)
                    ) {
                        state.close()
                    }
                }
            }
    }

/** Attachment fallback label when the first message has no body text (matching [threadHeaderOriginalFirstLine]). */
internal fun threadOriginalPullDownAttachmentFallback(
    attachments: List<RaftThreadAttachmentItem>,
    imageLabel: String,
    fileLabel: String,
): String? {
    if (attachments.isEmpty()) return null
    val allImages = attachments.all { it.mimeType.startsWith("image/", ignoreCase = true) }
    return if (allImages) imageLabel else fileLabel
}

internal fun threadOriginalPullDownSenderName(
    origin: RaftThreadMessageItem,
    externalFallbackName: String,
    unknownAuthorLabel: String,
): String =
    origin.externalAuthor
        ?.let { externalAuthorShownName(it, it.displayName, externalFallbackName) }
        ?.takeIf { it.isNotBlank() }
        ?: origin.authorName.takeIf { it.isNotBlank() }
        ?: origin.authorHandle.takeIf { it.isNotBlank() }
        ?: unknownAuthorLabel

/**
 * Page-scoped holder wiring [ThreadOriginalPullState], origin message tasks, summary, and
 * keyboard-close lifecycle for [rememberThreadPageContentScaffoldSlotsContent].
 */
internal class ThreadOriginalPullController(
    val state: ThreadOriginalPullState,
    val origin: RaftThreadMessageItem?,
    val tasks: List<ThreadMessageSourceTaskItem>,
    val headerChrome: ThreadHeaderOriginalPullChrome?,
) {
    val isOpen: Boolean get() = state.isOpen
}

@Composable
internal fun rememberThreadOriginalPullController(
    threadInstanceKey: String,
    isReplyThread: Boolean,
    selectedTab: RaftThreadTab,
    timelineSlice: ThreadTimelineSlice,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    keyboardUp: Boolean,
): ThreadOriginalPullController {
    val state = remember(threadInstanceKey) { ThreadOriginalPullState() }
    val headerOriginal = LocalThreadHeaderOriginalState.current
    val waitingShown = LocalTaskWaitingOnYouEnabled.current
    val origin =
        timelineSlice.originMessage?.takeIf {
            isReplyThread &&
                selectedTab == RaftThreadTab.Chat &&
                !it.isRetracted &&
                timelineSlice.originStatus != "missing" &&
                timelineSlice.originStatus != "error"
        }
    val tasks =
        remember(origin, linkedTasksProjection) {
            origin?.let(linkedTasksProjection::sourceTasksFor).orEmpty()
        }
    val summary =
        remember(tasks, waitingShown) {
            summarizeThreadTasks(tasks, waitingShown = waitingShown)
        }
    val shownOriginal = headerOriginal?.original as? ThreadHeaderOriginal.Shown
    val available = origin != null && headerOriginal?.swapped == true && shownOriginal != null && !shownOriginal.behindHistory
    SideEffect { state.updateAvailable(available) }
    LaunchedEffect(state, keyboardUp) { if (keyboardUp) state.close() }
    val headerChrome =
        if (origin != null) {
            ThreadHeaderOriginalPullChrome(
                summary = summary,
                open = state.isOpen,
                onToggle = state::toggle,
            )
        } else {
            null
        }
    return ThreadOriginalPullController(
        state = state,
        origin = origin,
        tasks = tasks,
        headerChrome = headerChrome,
    )
}

/**
 * Pull-down panel showing the thread's first message (author avatar, name, timestamp, 3-line
 * ellipsized preview), its folded task list (or empty copy), and "Jump to this message".
 * Reuses [RaftPullDownPanel] (no scrim, 70% max height, swipe-up-at-end close, Escape close).
 */
@Composable
internal fun ThreadOriginalPullDown(
    state: ThreadOriginalPullState,
    origin: RaftThreadMessageItem,
    tasks: List<ThreadMessageSourceTaskItem>,
    onOpenTask: (String) -> Unit,
    onJumpToOriginal: () -> Unit,
    modifier: Modifier = Modifier,
    avatarServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val paneTitle = stringResource(Res.string.thread_header_original_panel_a11y)
    val externalFallbackName = rememberExternalAuthorFallbackName()
    val unknownAuthorLabel = stringResource(Res.string.thread_comments_unknown_author)
    val imageLabel = stringResource(Res.string.thread_reply_title_preview_image)
    val fileLabel = stringResource(Res.string.thread_reply_title_preview_file)
    val senderName =
        remember(origin.externalAuthor, origin.authorName, origin.authorHandle, externalFallbackName, unknownAuthorLabel) {
            threadOriginalPullDownSenderName(origin, externalFallbackName, unknownAuthorLabel)
        }
    val bodyPreview = remember(origin.body) { markdownToPreview(origin.body) }
    val previewAnnotated =
        if (bodyPreview.text.isNotBlank()) {
            bodyPreview.toAnnotatedString(colors.foregroundLink)
        } else {
            threadOriginalPullDownAttachmentFallback(origin.attachments, imageLabel, fileLabel)
                ?.let(::AnnotatedString)
        }
    val authorKind = origin.authorKind.projectedMemberKindOrNull()
    val backSwipeDismiss = rememberRaftBackSwipeDismiss(enabled = state.isOpen, onDismiss = { state.close() })

    RaftPullDownPanel(
        open = state.isOpen,
        onDismiss = { state.close() },
        modifier =
            modifier
                .onGloballyPositioned { state.panelBottomInRootPx = it.boundsInRoot().bottom }
                .then(backSwipeDismiss),
        paneTitle = paneTitle,
        reducedMotion = reducedMotion,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftSafeAreaHorizontalPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                    .testTag(ThreadOriginalPullDownTestTag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    RaftMemberAvatar(
                        identity = RaftMemberAvatarIdentity(authorKind, origin.authorId, serverId = avatarServerId),
                        label = senderName,
                        avatarSize = RaftAvatarSize.Sm.edge,
                        carriedAvatarWireValue = origin.avatarWireValue,
                        resolver = memberAvatarResolver,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaftText(
                            text = senderName,
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                            style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (origin.timestampLabel.isNotBlank()) {
                            RaftRelativeTimestampText(
                                value = origin.timestampLabel,
                                modifier = Modifier.alignByBaseline(),
                                displayGroup = RaftTimestampDisplayGroup.Conversation,
                                style = typography.monoMeta,
                                color = colors.foregroundHint,
                                maxLines = 1,
                                toggleEnabled = false,
                            )
                        }
                    }
                    if (previewAnnotated != null) {
                        RaftText(
                            text = previewAnnotated,
                            modifier = Modifier.fillMaxWidth().testTag(ThreadOriginalPullDownTextTestTag),
                            style = typography.body,
                            color = colors.foreground,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (tasks.isNotEmpty()) {
                ThreadMessageSourceTaskList(
                    tasks = tasks,
                    memberAvatarResolver = memberAvatarResolver,
                    agentStatusResolver = agentStatusResolver,
                    onOpenTask = { taskId ->
                        state.close()
                        onOpenTask(taskId)
                    },
                    foldDone = true,
                    modifier = Modifier.fillMaxWidth().testTag(ThreadOriginalPullDownTasksTestTag),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tasks.isEmpty()) {
                    RaftText(
                        text = stringResource(Res.string.thread_header_original_no_tasks),
                        modifier = Modifier.weight(1f).testTag(ThreadOriginalPullDownEmptyTestTag),
                        style = typography.caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                RaftTextLink(
                    text = stringResource(Res.string.thread_header_jump_to_original),
                    onClick = {
                        state.close()
                        onJumpToOriginal()
                    },
                    modifier = Modifier.testTag(ThreadOriginalPullDownJumpTestTag),
                    style = typography.caption,
                    maxLines = 1,
                )
            }
        }
    }
}
