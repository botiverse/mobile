package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import org.jetbrains.compose.resources.StringResource
import build.raft.app.data.agentToolLogLabel
import build.raft.app.data.agentToolSemantic
import build.raft.designsystem.components.RaftSegmentedPager
import build.raft.designsystem.components.rememberRaftSegmentedPagerState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.agentActivitySubagentRowPresence
import build.raft.app.data.groupAgentActivitySubagents
import build.raft.app.data.AgentActivityOverviewIntent
import build.raft.app.data.AgentWorkerTimelineIntent
import build.raft.app.data.AgentWorkerTimelineState
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.AgentWorkersState
import build.raft.app.data.agentWorkerTimelineRefreshKey
import build.raft.app.data.agentMemoryLimitExit
import build.raft.app.data.isAgentBackgroundOomActivityTitle
import build.raft.app.data.parseAgentBackgroundOomActivityText
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.data.AgentActivityDetailKindClaudeRootPrivileges
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampClockWithSeconds
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.PaddingValues
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.components.RaftTextLink
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults

@Composable
internal fun MembersAgentActivityTab(
    detail: AgentDetailSnapshot?,
    serverId: String?,
    /** The detail page's observed live status (null = not observed: no dot). */
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    /** The agent waits for its offline Computer ([membersAgentHeldByOfflineComputer]): "Computer offline". */
    heldByOfflineComputer: Boolean = false,
    loadState: MembersAgentActivityLoadState,
    overview: MembersAgentActivityOverviewBinding? = null,
    onOpenComputer: (() -> Unit)? = null,
    onOpenMessage: (channelId: String, messageId: String) -> Unit = { _, _ -> },
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onCopyDiagnostics: () -> Unit,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoScrollToLatest: Boolean = true,
    /** Stop one running sub-agent from its page; null: the viewer may not (web `canStopSubagents`). */
    subagentStop: MembersAgentSubagentStop? = null,
    /** Worker v1 (`worker_v1` on for a Computer agent): renames the segment to Workers and lists live Workers first. */
    workers: MembersAgentWorkersBinding? = null,
) {
    val copySucceeded =
        diagnosticActionState?.actionKey == "copy" && diagnosticActionState.successLabel != null
    val fallbackWorkersState = remember { MutableStateFlow(AgentWorkersState()) }
    val workersState by (workers?.state ?: fallbackWorkersState).collectAsState()
    val fallbackTimelineState = remember { MutableStateFlow(AgentWorkerTimelineState()) }
    val workerTimelineState by (workers?.timelineState ?: fallbackTimelineState).collectAsState()
    var chosenActivityView by remember(detail?.agentId) { mutableStateOf(MembersAgentActivityView.Overview) }
    // Without the Overview (its flag off or unresolved) the Overview choice reads as the raw log.
    val activityView =
        if (overview == null && chosenActivityView == MembersAgentActivityView.Overview) MembersAgentActivityView.Raw
        else chosenActivityView
    // The log oldest first, as the raw view shows it; the Sub-agents view folds it (web groupSubagents).
    val activityLog =
        remember(detail?.agentId, detail?.activity) {
            detail?.activity.orEmpty()
                .sortedWith(compareBy<AgentActivityEntry> { it.timestamp.membersActivitySortKey() }.thenBy { it.id })
        }
    val subagents = remember(activityLog) { groupAgentActivitySubagents(activityLog) }
    var openSubagentKey by remember(detail?.agentId) { mutableStateOf<String?>(null) }
    val subagentLink =
        remember(activityLog, subagents) {
            membersAgentActivitySubagentLink(activityLog, subagents) { key ->
                openSubagentKey = key
                chosenActivityView = MembersAgentActivityView.Subagents
            }
        }
    val openSubagent = openSubagentKey?.let { key -> subagents.firstOrNull { it.key == key } }
    // Web AgentActivityLog (slock #14961 + Worker v1): always offered when worker_v1 is on for a Computer agent,
    // or for a runtime that can start sub-agents; any other runtime shows it once a sub-agent has appeared.
    val workersEnabled = workers != null
    val openWorkerTimeline =
        workerTimelineState.open?.takeIf { workersEnabled && workerTimelineState.agentId == detail?.agentId }
    val openWorkerLiveRow =
        openWorkerTimeline?.let { open -> workersState.workers.firstOrNull { it.launchId == open.launchId } }
    val openWorkerEndedRow =
        openWorkerTimeline?.let { open ->
            workersState.recentWorkers.firstOrNull { it.launchId == open.launchId && it.endedAt != null }
        }
    val openWorkerRefreshKey =
        if (openWorkerTimeline != null) {
            agentWorkerTimelineRefreshKey(live = openWorkerLiveRow, ended = openWorkerEndedRow)
        } else {
            null
        }
    LaunchedEffect(workers, openWorkerTimeline?.launchId, openWorkerRefreshKey) {
        if (openWorkerRefreshKey != null) {
            workers?.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.SyncRefreshKey(openWorkerRefreshKey))
        }
    }
    LaunchedEffect(workers, detail?.agentId) {
        val openAgentId = workers?.timelineState?.value?.agentId
        if (openAgentId != null && openAgentId != detail?.agentId) {
            workers.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.Close)
        }
    }
    // A sub-agent's or Worker's detail page is transient: Back returns to the list first.
    RaftBackHandler(enabled = activityView == MembersAgentActivityView.Subagents && openSubagent != null, label = "agent_subagent_detail") {
        openSubagentKey = null
    }
    RaftBackHandler(
        enabled = activityView == MembersAgentActivityView.Subagents && openSubagent == null && openWorkerTimeline != null,
        label = "agent_worker_timeline",
    ) {
        workers?.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.Close)
    }
    val showSubagents =
        membersAgentActivityShowsSubagents(
            runtime = detail?.summary?.runtime,
            subagentCount = subagents.size,
            viewing = activityView == MembersAgentActivityView.Subagents,
            workersEnabled = workersEnabled,
        )
    val workersPanelOpen = workersEnabled && activityView == MembersAgentActivityView.Subagents && openSubagent == null
    LaunchedEffect(workers, workersPanelOpen) {
        workers?.onIntent(AgentWorkersIntent.SetPanelOpen(workersPanelOpen))
    }
    DisposableEffect(workers) {
        onDispose {
            workers?.onIntent(AgentWorkersIntent.SetPanelOpen(false))
        }
    }
    val changeView: (MembersAgentActivityView) -> Unit = { next ->
        chosenActivityView = next
        openSubagentKey = null
        workers?.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.Close)
    }
    if (overview != null && detail != null) {
        MembersAgentActivityOverviewEffects(overview, detail.agentId, detail.summary?.activity, serverId)
    }
    // Web AgentActivityLog: without the Overview flag the tab is the raw log alone, no toolbar
    // (Copy info lives in the profile's Actions and the runtime-error notice, as on the web);
    // behind it, the Overview / Raw Log toolbar heads the tab.
    // The switch's segments in order, each a page of a pager that follows the finger (#257): a
    // sideways swipe turns them before the profile's tab pager takes it, which it does only past the
    // first or last one (nested-pager priority, #148), and the switch's sticker slides with it.
    val activityViews =
        listOfNotNull(
            MembersAgentActivityView.Overview.takeIf { overview != null },
            MembersAgentActivityView.Raw,
            MembersAgentActivityView.Subagents.takeIf { showSubagents },
        )
    val activityViewIndex = activityViews.indexOf(activityView).coerceAtLeast(0)
    val currentActivityViews by rememberUpdatedState(activityViews)
    val pagerState =
        key(detail?.agentId) {
            rememberRaftSegmentedPagerState(initialIndex = activityViewIndex) { currentActivityViews.size }
        }
    RaftSegmentedPager(
        state = pagerState,
        selectedIndex = activityViewIndex,
        onSelectedIndexChange = { index -> activityViews.getOrNull(index)?.let(changeView) },
        modifier = modifier
            .fillMaxWidth()
            .background(RaftTheme.colors.layerCanvas)
            .raftSafeAreaHorizontalPadding(),
        header = {
            if (overview != null || showSubagents) {
                // Web AgentActivityLog's view toolbar (stdrc 2026-11-04/05): no bar of its own, so no
                // divider under it. By touch at least the header height with 12dp above and below
                // (`min-h-(--header-height) py-3`); with a mouse on a desktop part of the content, the
                // page's top inset above and nothing below (`pt-(--page-top) pb-0`).
                val desktop = RaftTheme.controls.rowHeight != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (desktop) Modifier else Modifier.heightIn(min = RaftTheme.sizes.headerHeight))
                        .padding(
                            start = MembersPageInset,
                            end = MembersPageInset,
                            top = if (desktop) RaftTheme.sizes.pageTop else 12.dp,
                            bottom = if (desktop) 0.dp else 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
                ) {
                    // Web AgentActivityLog: Overview / Raw Log, and Subagents (always for a runtime that
                    // can start them, else once the agent has had one).
                    // The switch takes what the button leaves; three segments scroll in their track.
                    Box(Modifier.weight(1f)) {
                        MembersAgentActivityViewSwitch(
                            view = activityView,
                            onViewChange = changeView,
                            showOverview = overview != null,
                            subagentCount =
                                if (showSubagents) {
                                    if (workersEnabled) subagents.size + workersState.workers.size else subagents.size
                                } else {
                                    null
                                },
                            workersEnabled = workersEnabled,
                            selectionPosition = { pagerState.position },
                        )
                    }
                    // Copy info heads the tab with the Overview; without it, it lives in the profile.
                    if (overview != null) {
                        RaftButton(
                            text = if (copySucceeded) stringResource(Res.string.common_done) else stringResource(Res.string.member_action_copy_info),
                            onClick = onCopyDiagnostics,
                            enabled = diagnosticActionState?.inProgress != true,
                            leadingIcon = {
                                RaftContentLucideIcon(if (copySucceeded) RaftLucideIconName.Check else RaftLucideIconName.Copy)
                            }
                        )
                    }
                }
            }
        },
    ) { index ->
        Column(Modifier.fillMaxSize()) {
            when (activityViews.getOrNull(index) ?: MembersAgentActivityView.Raw) {
                MembersAgentActivityView.Overview ->
                    if (overview != null) {
                        val summary = detail?.summary
                        MembersAgentActivityOverview(
                            binding = overview,
                            now = MembersAgentActivityNow(
                                label =
                                    if (heldByOfflineComputer) {
                                        stringResource(Res.string.agent_status_computer_offline)
                                    } else {
                                        membersLiveAgentStatusLabel(
                                            indicatorState = agentStatusIndicatorState,
                                            summaryStatus = summary?.status,
                                            summaryActivity = summary?.activity,
                                            summaryActivityDetail = summary?.activityDetail,
                                            fallback = MembersUnknownAgentStatusLabel
                                        )
                                    },
                                // Its Computer offline: the offline light, whatever its last activity (web #14585).
                                indicator =
                                    if (heldByOfflineComputer) {
                                        raftAgentStatusIndicatorState(status = AgentStatus.Offline, presence = Presence.Offline, external = false)
                                    } else {
                                        agentStatusIndicatorState
                                    },
                                wakeCrashLoop =
                                    membersAgentActivityWakeCrashLoop(
                                        indicator = agentStatusIndicatorState,
                                        summaryDetailKind = summary?.activityDetailKind,
                                        summaryWakeCrashLoop = summary?.wakeCrashLoop
                                    )
                            ),
                            hasRawActivity = detail?.activity?.isNotEmpty() == true,
                            hasComputer = !summary?.machineId.isNullOrBlank(),
                            timeContext = timeContext,
                            onShowRaw = { changeView(MembersAgentActivityView.Raw) },
                            onOpenComputer = onOpenComputer,
                            onOpenUrl = onOpenUrl,
                            onOpenMessage = { channelId, messageId -> onOpenMessage(channelId, messageId.value) },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                MembersAgentActivityView.Subagents -> {
                    val contentModifier = Modifier.fillMaxWidth().weight(1f)
                    if (openSubagent != null) {
                        MembersAgentSubagentDetail(
                            group = openSubagent,
                            timeContext = timeContext,
                            stop = subagentStop,
                            onBack = { openSubagentKey = null },
                            horizontalInset = MembersPageInset,
                            modifier = contentModifier,
                        ) { _, entry, last ->
                            MembersAgentActivityRow(entry, timeContext, onOpenUrl, last = last)
                        }
                    } else if (workers != null && openWorkerTimeline != null) {
                        MembersAgentWorkerTimelineDetail(
                            agentId = detail?.agentId ?: MemberId(""),
                            open = openWorkerTimeline,
                            parentHandle = workers.parentHandle,
                            live = openWorkerLiveRow,
                            ended = openWorkerEndedRow,
                            timelineState = workerTimelineState,
                            nowMillis = workersState.nowMillis,
                            stale = workersState.stale,
                            canStop = workers.canStop,
                            computerOffline = workers.computerOffline,
                            stoppingWorker = workersState.stoppingWorker,
                            timeContext = timeContext,
                            horizontalInset = MembersPageInset,
                            onBack = { workers.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.Close) },
                            onRefresh = { workers.onTimelineIntent?.invoke(AgentWorkerTimelineIntent.Refresh) },
                            onWorkersIntent = workers.onIntent,
                            onOpenMessage = { messageId -> workers.onOpenTimelineMessage?.invoke(messageId) },
                            modifier = contentModifier,
                        )
                    } else if (workers != null) {
                        MembersAgentWorkersList(
                            agentId = detail?.agentId ?: MemberId(""),
                            workers = workers,
                            groups = subagents,
                            timeContext = timeContext,
                            onOpenSubagent = { openSubagentKey = it },
                            contentPadding = PaddingValues(start = MembersPageInset, end = MembersPageInset, bottom = RaftAppSpacing.Md),
                            modifier = contentModifier,
                        )
                    } else {
                        MembersAgentSubagentsList(
                            groups = subagents,
                            timeContext = timeContext,
                            onOpen = { openSubagentKey = it },
                            contentPadding = PaddingValues(start = MembersPageInset, end = MembersPageInset, bottom = RaftAppSpacing.Md),
                            modifier = contentModifier,
                        )
                    }
                }
                MembersAgentActivityView.Raw ->
                    MembersAgentActivityRawLog(
                        activityLog = activityLog,
                        loadState = loadState,
                        resetKey = detail?.agentId,
                        autoScrollToLatest = autoScrollToLatest,
                        timeContext = timeContext,
                        onOpenUrl = onOpenUrl,
                        subagentLink = subagentLink,
                    )
            }
        }
    }
}

/** The Raw Log view: the load notice, then the log (or its loading, error or empty state). */
@Composable
private fun ColumnScope.MembersAgentActivityRawLog(
    activityLog: List<AgentActivityEntry>,
    loadState: MembersAgentActivityLoadState,
    resetKey: Any?,
    autoScrollToLatest: Boolean,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    subagentLink: MembersAgentActivitySubagentLink?,
) {
    val errorStrings = localizedErrorPresentationStrings()
    val rows =
        remember(activityLog) {
            // Web isVisibleTrajectoryEntry: a freshness hold says itself in its action row.
            activityLog.filterNot {
                (it.type == "tool_start" && it.toolName?.normalizedMembersToolName() == "send_message") ||
                    (it.type == "status" && it.statusDetailKind == AgentActivityDetailKindFreshnessHold)
            }
        }
    val hasRows = rows.isNotEmpty()
    if (hasRows && (loadState is MembersAgentActivityLoadState.Refreshing || loadState is MembersAgentActivityLoadState.Error)) {
        MembersAgentActivityLoadNotice(loadState)
    }
    if (!hasRows && (loadState is MembersAgentActivityLoadState.Loading || loadState is MembersAgentActivityLoadState.Refreshing)) {
        RaftCenteredLoading(
            message = stringResource(Res.string.member_state_loading_activity),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    } else if (!hasRows && loadState is MembersAgentActivityLoadState.Error) {
        RaftEmptyState(
            title = stringResource(Res.string.member_error_load_activity),
            description = loadState.error.userFacingMessage(errorStrings),
            icon = { RaftContentLucideIcon(RaftLucideIconName.Activity, size = RaftEmptyStateDefaults.IconSize) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    } else if (rows.isEmpty()) {
        RaftEmptyState(
            title = stringResource(Res.string.member_state_no_activity),
            description = stringResource(Res.string.member_state_activity_start_agent),
            icon = { RaftContentLucideIcon(RaftLucideIconName.Activity, size = RaftEmptyStateDefaults.IconSize) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    } else {
        // Raw log tail (raft-design "Conversation › Following the newest"): opens at the newest,
        // follows new entries while the reader is at the bottom, otherwise keeps their place
        // and shows the thread's jump pill. Rows are keyed by the entry's own id.
        MembersAgentActivityLogList(
            rows = rows,
            rowKey = { it.activityTimelineKey() },
            resetKey = resetKey,
            followLatest = autoScrollToLatest,
            // Web: the log's `py-3` plus each entry's `py-1.5` above the first and under the last.
            contentPadding = PaddingValues(horizontal = MembersPageInset, vertical = MembersActivityLogPaddingY),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { index, entry ->
            MembersAgentActivityRow(entry, timeContext, onOpenUrl, last = index == rows.lastIndex, subagentLink = subagentLink)
        }
    }
}

private val MembersActivityLogPaddingY = 18.dp

@Composable
private fun MembersAgentActivityLoadNotice(
    state: MembersAgentActivityLoadState,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    val message =
        when (state) {
            MembersAgentActivityLoadState.Refreshing -> stringResource(Res.string.member_state_refreshing_activity)
            is MembersAgentActivityLoadState.Error ->
                "${stringResource(Res.string.member_error_load_activity)}: ${state.error.userFacingMessage(errorStrings)}"
            MembersAgentActivityLoadState.Idle,
            MembersAgentActivityLoadState.Loading -> return
        }
    // raft-ui PanelNotice: refreshing (info) or the refresh failed (warning) above the log.
    RaftPanelNotice(
        message = message,
        modifier = modifier,
        status = if (state is MembersAgentActivityLoadState.Error) RaftBannerStatus.Warning else RaftBannerStatus.Info
    )
}

@Composable
internal fun MembersAgentActivityRow(
    activity: AgentActivityEntry,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    last: Boolean = false,
    /** The raw log's links into the Sub-agents view; null in a sub-agent's own page. */
    subagentLink: MembersAgentActivitySubagentLink? = null
) {
    val key = activity.activityTimelineKey()
    var expanded by remember(key) { mutableStateOf(false) }
    val memoryLimit = membersActivityRowMemoryLimitText(activity)
    val body = memoryLimit?.body ?: membersActivityLocalizedDetail(activity, activity.activityPrimaryDetail().orEmpty())
    val longBody = body.length > 200 || body.count { it == '\n' } > 2
    val displayBody =
        if (!expanded && longBody) {
            body.take(200).trimEnd() + "…"
        } else {
            body
        }
    val colors = RaftTheme.colors
    val detailStyle = RaftTheme.typography.monoMeta
    val titleText =
        memoryLimit?.title ?: membersActivityStatusTitle(activity) ?: membersActivityTrajectoryTitle(activity)
            ?: membersActivityFixedTitleRes(activity)?.let { stringResource(it) } ?: activity.title
    // raft-ui Timeline as an activity log: presence-coloured pixels, title and detail in
    // Space Mono, the time on the right, a `subagent` tag on a subagent's step. The title is
    // web LOG_TITLE_CLASS: `text-foreground-muted wrap-anywhere`, wrapping instead of clipping.
    // Web SystemEntry (#13383): a sub-agent's lifecycle system row reads as its state, not an error.
    val subagentPresence = if (activity.type == "system") agentActivitySubagentRowPresence(activity.subagent) else null
    RaftTimelineItem(
        color = (subagentPresence ?: activity.statusActivity ?: activity.type).membersActivityAccentColor(),
        last = last,
        time = activity.timestamp.membersActivityDisplayTime(timeContext),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftText(
                    text = titleText,
                    style = raftTimelineMonoTitleStyle(),
                    color = colors.foregroundMuted,
                    modifier = Modifier.weight(1f, fill = false)
                )
                membersActivitySubagentBadgeLabel(activity)?.let { subagentLabel ->
                    // Web SubagentBadge (#13029): the marker opens that sub-agent's own run.
                    val openSubagent: (() -> Unit)? =
                        subagentLink?.let { link -> link.keyOfRow(activity.subagent)?.let { key -> { link.open(key) } } }
                    RaftBadge(
                        text = subagentLabel,
                        appearance = RaftBadgeAppearance.Outline,
                        onClick = openSubagent,
                        modifier = if (openSubagent != null) Modifier.testTag(MembersAgentActivitySubagentOpenTag) else Modifier
                    )
                }
            }
        }
    ) {
        // Every row type, a status included (Kirby, raft-design: "Working" over "Tool finished"),
        // puts its detail on its own line under the title: web LOG_DETAIL_CLASS
        // `mt-0.5 font-mono text-mono-meta text-foreground-hint` (the timeline's 2dp gap).
        if (displayBody.isNotBlank()) {
            // #64 owner override (artin 06-23 17:41): '改成用 RaftMarkdownBody
            // 比较好，它能展示出来 #xxx 这些东西'. Restore the markdown branch so
            // the activity body can render task refs / issue refs (e.g. `#225`,
            // `#task-7`) as styled inline text + clickable onOpenUrl links
            // when `refTextEnabled` is true.
            // A status detail with no channel / thread ref takes the plain detail text below, so
            // its line box is exactly the other rows' (the markdown body's inline-chip line floor
            // would push it down); one that may hold a ref keeps the ref-rendering body.
            val markdownDetail =
                activity.refTextEnabled && (activity.type != "status" || displayBody.membersActivityMayHoldRef())
            if (markdownDetail) {
                RaftMarkdownBody(
                    body = displayBody,
                    textStyle = detailStyle.copy(color = colors.foregroundHint),
                    onOpenUrl = onOpenUrl,
                )
            } else {
                // Web: a detail clamps only when it is long (the "Show more" toggle below).
                RaftText(
                    text = displayBody,
                    style = detailStyle,
                    color = colors.foregroundHint,
                    maxLines = when {
                        expanded -> 12
                        longBody -> 3
                        else -> Int.MAX_VALUE
                    },
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (longBody) {
            RaftTextLink(
                text = if (expanded) stringResource(Res.string.member_label_show_less) else stringResource(Res.string.member_label_show_more),
                onClick = { expanded = !expanded },
                style = RaftTheme.typography.caption
            )
        }
        // Web ToolStartEntry: the call that started a sub-agent opens it.
        val spawnedKey = if (activity.type == "tool_start") subagentLink?.keyOfCall(activity.callId) else null
        if (spawnedKey != null && subagentLink != null) {
            RaftTextLink(
                text = stringResource(Res.string.member_activity_subagents_open_from_log),
                onClick = { subagentLink.open(spawnedKey) },
                modifier = Modifier.testTag(MembersAgentActivitySubagentOpenTag),
                style = RaftTheme.typography.monoMeta
            )
        }
    }
}

private data class MembersActivityRowText(val title: String, val body: String)

/**
 * Web AgentActivityLog (#10860): a runtime stopped at the Computer's per-agent memory limit reads
 * as such instead of "Crashed" plus the daemon's English detail, and the background-process notice
 * (its `system` row and the `system_message` status beside it) gets this locale's copy. Null for
 * every other row.
 */
@Composable
private fun membersActivityRowMemoryLimitText(activity: AgentActivityEntry): MembersActivityRowText? {
    if (activity.type == "status") {
        // botiverse/slock #12892 (web StatusEntry): "Crashed" with how to fix the root refusal in
        // this locale instead of the daemon's English detail.
        if (activity.statusDetailKind == AgentActivityDetailKindClaudeRootPrivileges) {
            return MembersActivityRowText(membersActivityStatusTitle(activity) ?: activity.title, stringResource(Res.string.agent_runtime_error_root_privileges))
        }
        val exit = if (activity.statusDetailKind == "runtime_crashed") agentMemoryLimitExit(activity.statusDetail, "runtime_crashed") else null
        if (exit != null) {
            val title = exit.memoryLimit?.let { stringResource(Res.string.agent_activity_memory_limit_stopped_with_limit, it) }
                ?: stringResource(Res.string.agent_activity_memory_limit_stopped)
            return MembersActivityRowText(title, "")
        }
        if (activity.statusDetailKind == "system_message" && isAgentBackgroundOomActivityTitle(activity.statusDetail)) {
            // The notice's own system row says it once.
            return MembersActivityRowText(stringResource(Res.string.agent_activity_background_oom), "")
        }
        return null
    }
    if (activity.type == "system" && isAgentBackgroundOomActivityTitle(activity.title)) {
        val parsed = parseAgentBackgroundOomActivityText(activity.body)
        val memoryLimit = parsed?.memoryLimit
        val body = when {
            parsed == null -> activity.body.orEmpty()
            memoryLimit != null ->
                stringResource(Res.string.agent_activity_log_background_oom_detail_with_limit, memoryLimit)
            else -> stringResource(Res.string.agent_activity_log_background_oom_detail)
        }
        return MembersActivityRowText(stringResource(Res.string.agent_activity_log_background_oom_title), body)
    }
    return null
}

/**
 * Web AgentActivityLog `ACTIVITY_LABEL_IDS` / `activity.log.status.*` (#proj-raft-ui:#267, slock
 * #15327): a status row's word in this locale. The parsers keep the English word as the row's
 * identifier; it is translated only here. Null for other rows and for a status the web shows raw.
 */
@Composable
private fun membersActivityStatusTitle(activity: AgentActivityEntry): String? {
    if (activity.type != "status") return null
    val resource = when (activity.title) {
        "Idle" -> Res.string.member_activity_log_status_idle
        "Thinking" -> Res.string.member_activity_log_status_thinking
        "Working" -> Res.string.member_activity_log_status_working
        "Starting" -> Res.string.member_activity_log_status_starting
        "Started" -> Res.string.member_activity_log_status_started
        "Restarted" -> Res.string.member_activity_log_status_restarted
        "Upgraded" -> Res.string.member_activity_log_status_upgraded
        "Stopped" -> Res.string.member_activity_log_status_stopped
        "Disconnected" -> Res.string.member_activity_log_status_disconnected
        "Crashed" -> Res.string.member_activity_log_status_crashed
        "Offline" -> Res.string.member_activity_log_status_offline
        "Computer operation failed" -> Res.string.member_activity_log_status_computer_operation_failed
        // Web `activity.status.error`: the member list's translated "Error".
        "Error" -> Res.string.member_row_status_error
        else -> return null
    }
    return stringResource(resource)
}

/**
 * Web AgentActivityLog ThinkingEntry / OutputEntry / CompactionEntry (`activity.log.thinking`,
 * `activity.log.output`, `activity.log.compactionStarted` / `compactionFinished`): the fixed
 * title of a thinking, output or compaction row in this locale. The parsers keep the English
 * title; it is translated only here. Null for every other row.
 */
@Composable
private fun membersActivityTrajectoryTitle(activity: AgentActivityEntry): String? {
    val resource = when (activity.type) {
        "thinking" -> Res.string.member_activity_log_thinking
        "text" -> Res.string.member_activity_log_output
        "compaction_started" -> Res.string.member_activity_log_compaction_started
        "compaction_finished" -> Res.string.member_activity_log_compaction_finished
        else -> return null
    }
    return stringResource(resource)
}

/**
 * The title of a row whose words the client chose, in this locale: a tool call's log label (web
 * `getToolLogLabel`, by the tool's semantic), a freshness hold (web `FRESHNESS_HOLD_LABELS`), and
 * the parsers' fallbacks for a row without a title ("Raft action", "System", "Status",
 * "Agent activity"). The parsers keep the English words as the row's identifier. Null for a title
 * from the server or a tool Raft doesn't know, which show as they are.
 */
internal fun membersActivityFixedTitleRes(activity: AgentActivityEntry): StringResource? {
    if (activity.type == "tool_start") {
        activity.toolName?.let(::agentToolLogLabelRes)?.let { return it }
    }
    if (activity.type == "slock_action") {
        agentFreshnessHold(activity.title)?.let { return it.labelRes }
    }
    return when {
        activity.type == "slock_action" && activity.title == "Raft action" -> Res.string.member_activity_log_raft_action
        activity.type == "system" && activity.title == "System" -> Res.string.activity_list_system_sender
        activity.type == "status" && activity.title == "Status" -> Res.string.member_label_status
        activity.title == "Agent activity" -> Res.string.member_activity_log_agent_activity
        else -> null
    }
}

/**
 * A row's detail in this locale where the client wrote it: a freshness hold's "N new messages in
 * #x" (web `activity.log.freshnessHold.detail`) and a tool's "Structured input" summary. Anything
 * else is [raw], as written.
 */
@Composable
private fun membersActivityLocalizedDetail(activity: AgentActivityEntry, raw: String): String {
    if (activity.type == "slock_action" && agentFreshnessHold(activity.title) != null) {
        parseAgentFreshnessHoldText(raw)?.let { return stringResource(it.messageRes(), it.count, it.target) }
    }
    if (activity.type == "tool_start") {
        when {
            raw == MembersActivityStructuredInput -> return stringResource(Res.string.member_activity_log_structured_input)
            raw.startsWith(MembersActivityStructuredInputKeysPrefix) ->
                return stringResource(
                    Res.string.member_activity_log_structured_input_keys,
                    raw.removePrefix(MembersActivityStructuredInputKeysPrefix)
                )
        }
    }
    return raw
}

/** The parsers' summary of an object tool input (`MemberAgentParser` / `RealtimeEventMapper`). */
private const val MembersActivityStructuredInput = "Structured input"
private const val MembersActivityStructuredInputKeysPrefix = "Structured input: "

/** Web `getToolLogLabel`, translated: a known tool's log label by its semantic; null for any other tool. */
internal fun agentToolLogLabelRes(toolName: String): StringResource? =
    when (agentToolSemantic(toolName.trim())) {
        "send_message" -> Res.string.member_activity_log_tool_send_message
        "check_messages", "receive_message" -> Res.string.member_activity_log_tool_check_messages
        "wait_for_message" -> Res.string.member_activity_log_tool_wait_for_message
        "read_history" -> Res.string.member_activity_log_tool_read_history
        "search_messages" -> Res.string.member_activity_log_tool_search_messages
        "list_server" -> Res.string.member_activity_log_tool_list_server
        "list_tasks" -> Res.string.member_activity_log_tool_list_tasks
        "create_tasks" -> Res.string.member_activity_log_tool_create_tasks
        "claim_tasks" -> Res.string.member_activity_log_tool_claim_tasks
        "unclaim_task" -> Res.string.member_activity_log_tool_unclaim_task
        "update_task_status" -> Res.string.member_activity_log_tool_update_task_status
        "add_channel_member" -> Res.string.member_activity_log_tool_add_channel_member
        "join_channel" -> Res.string.member_activity_log_tool_join_channel
        "leave_channel" -> Res.string.member_activity_log_tool_leave_channel
        "upload_file" -> Res.string.member_activity_log_tool_upload_file
        "view_file" -> Res.string.member_activity_log_tool_view_file
        "read_file" -> Res.string.member_activity_log_tool_read_file
        "write_file" -> Res.string.member_activity_log_tool_write_file
        "edit_file" -> Res.string.member_activity_log_tool_edit_file
        "bash" -> Res.string.member_activity_log_tool_bash
        "glob" -> Res.string.member_activity_log_tool_glob
        "grep" -> Res.string.member_activity_log_tool_grep
        "web_fetch" -> Res.string.member_activity_log_tool_web_fetch
        "web_search" -> Res.string.member_activity_log_tool_web_search
        "todo_write" -> Res.string.member_activity_log_tool_todo_write
        "schedule_reminder" -> Res.string.member_activity_log_tool_schedule_reminder
        "list_reminders" -> Res.string.member_activity_log_tool_list_reminders
        "cancel_reminder" -> Res.string.member_activity_log_tool_cancel_reminder
        "collab_tool_call" -> Res.string.member_activity_log_tool_collab_tool_call
        "spawn_subagent" -> Res.string.member_activity_log_tool_spawn_subagent
        else -> null
    }

/** A tool call's log label in this locale; a tool Raft doesn't know keeps its own name. */
@Composable
internal fun localizedAgentToolLogLabel(toolName: String): String =
    agentToolLogLabelRes(toolName)?.let { stringResource(it) }
        ?: agentToolLogLabel(toolName.trim()).ifBlank { stringResource(Res.string.member_activity_log_agent_activity) }

internal fun membersActivitySubagentBadgeDetail(activity: AgentActivityEntry): String? =
    listOfNotNull(
        activity.subagentType?.takeIf { it.isNotBlank() } ?: activity.subagent?.subagentType?.takeIf { it.isNotBlank() },
        activity.subagent?.model?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }

@Composable
private fun membersActivitySubagentBadgeLabel(activity: AgentActivityEntry): String? {
    if (!activity.membersActivityHasSubagentBadge()) return null
    return membersActivitySubagentBadgeDetail(activity)
        ?.let { stringResource(Res.string.member_activity_log_subagent_with_type, it) }
        ?: stringResource(Res.string.member_activity_log_subagent)
}

private fun AgentActivityEntry.activityPrimaryDetail(): String? =
    membersActivitySecondaryText()

private fun AgentActivityEntry.activityTimelineKey(): String =
    id.ifBlank { "${type}:${timestamp.orEmpty()}:${body.orEmpty()}:${toolName.orEmpty()}" }

private fun String?.membersActivitySortKey(): Long =
    parseRaftTimestampEpochMillisOrNull(this) ?: Long.MIN_VALUE

@Composable
private fun String?.membersActivityDisplayTime(timeContext: RaftTimeContext): String =
    formatRaftTimestampClockWithSeconds(this, timeContext)
        .takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.member_activity_log_no_time)

// raft-ui Timeline > Activity log: the presence colours (busy yellow while working,
// error orange, online lime when done), `info` for output, stone when offline.
private fun String.membersActivityAccentColor(): Color =
    when (membersActivityStatusKey()) {
        "error", "failed", "failure", "subagent_failed" -> RaftUiPalette.presenceError
        "tool_start", "tool", "thinking", "working", "busy", "subagent_spawned", "subagent_started" ->
            RaftUiPalette.presenceBusy
        "running", "active", "online", "compaction_finished", "subagent_completed" -> RaftUiPalette.presenceOnline
        // Web AgentActivityLog: Output and Raft actions are `online`, a compaction start `busy`.
        "text", "message", "output", "slock_action" -> RaftUiPalette.presenceOnline
        "offline", "disconnected", "subagent_suspended" -> RaftUiPalette.presenceOffline
        "compaction_started" -> RaftUiPalette.presenceBusy
        "system" -> RaftUiPalette.presenceError
        else -> RaftUiPalette.presenceBusy
    }

/** A `#channel`, `#channel:thread` or `dm:@peer` ref the web RefText would render. */
private fun String.membersActivityMayHoldRef(): Boolean = contains('#') || contains("dm:@")

private fun String.membersActivityStatusKey(): String =
    trim().lowercase().replace('-', '_').replace('.', '_')

private fun String.normalizedMembersToolName(): String =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .ifBlank { trim() }

internal fun membersAgentActivityInitialFirstVisibleIndex(itemCount: Int): Int =
    (itemCount - 1).coerceAtLeast(0)

internal fun membersAgentActivityNextBottomAnchor(
    current: Boolean,
    isScrollInProgress: Boolean,
    isAtBottom: Boolean
): Boolean =
    when {
        isAtBottom -> true
        isScrollInProgress -> false
        else -> current
    }

/**
 * Keeps the overview's turns current while it is on screen (the Activity tab's Overview, or the
 * profile's Recent activity): loads them for [agentId], reloads when a turn finishes, and registers
 * this surface with [build.raft.app.data.SharedAgentActivityOverviewStore] so store-owned freshness
 * catches up on foreground / reconnect / network recovery (#wg-mobile-feedback:#274 Phase 1).
 */
@Composable
internal fun MembersAgentActivityOverviewEffects(
    overview: MembersAgentActivityOverviewBinding,
    agentId: MemberId,
    /** The agent's live activity (`working`, `online`, ...), as its detail reads it. */
    liveActivity: String?,
    serverId: String?,
) {
    val observerToken = remember { Any() }
    DisposableEffect(overview, observerToken, serverId) {
        overview.onIntent(AgentActivityOverviewIntent.ObserveFreshness(observerToken, serverId))
        onDispose {
            overview.onIntent(AgentActivityOverviewIntent.StopObservingFreshness(observerToken))
        }
    }
    LaunchedEffect(agentId) { overview.onIntent(AgentActivityOverviewIntent.Open(agentId)) }
    // A finished turn arrives with the agent leaving a busy state (web reloads on the
    // agent:activity push). The store keeps the last-seen activity, so a revisit after the
    // pager dropped this page still sees the edge; it debounces and coalesces the reload.
    LaunchedEffect(agentId, liveActivity) {
        overview.onIntent(AgentActivityOverviewIntent.LiveActivityObserved(agentId, liveActivity))
    }
}
