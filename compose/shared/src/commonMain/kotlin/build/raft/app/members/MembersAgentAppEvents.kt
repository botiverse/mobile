package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.thread.formatTimestampLabel
import build.raft.app.thread.icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentAppEvent
import build.raft.app.data.AgentAppEventDetail
import build.raft.app.data.AgentAppEventPage
import build.raft.app.data.AgentAppEventStatus
import build.raft.app.data.DataResult
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import build.raft.designsystem.components.raftClickable

/** Loads a page of an agent's app events: (agent, app filter, `before` cursor). */
internal typealias MembersAgentAppEventsLoader = suspend (MemberId, String?, String?) -> DataResult<AgentAppEventPage>

/** Loads one app event's payload: (agent, event id). */
internal typealias MembersAgentAppEventLoader = suspend (MemberId, String) -> DataResult<AgentAppEventDetail>

/**
 * Hoistable state holder for [MembersAgentAppEventsSection] so loaded app events, active filter,
 * and expanded event payloads survive `TabPager` horizontal page disposal across tab switches
 * (#proj-mobile:#386). Scoped to `(agentId, serverId)` by the caller.
 */
internal class MembersAgentAppEventsState {
    var events by mutableStateOf<List<AgentAppEvent>>(emptyList())
    var nextCursor by mutableStateOf<String?>(null)
    var appFilter by mutableStateOf<String?>(null)
    // Filter chips come from the apps seen so far in the unfiltered list (web).
    var apps by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var request by mutableStateOf(0)
    var hasLoaded by mutableStateOf(false)
    var loadedAgentId by mutableStateOf<MemberId?>(null)
    var loadedAppFilter by mutableStateOf<String?>(null)
    var expandedEventIds by mutableStateOf<Set<String>>(emptySet())
    var payloadByEventId by mutableStateOf<Map<String, String>>(emptyMap())
    var errorByEventId by mutableStateOf<Map<String, String>>(emptyMap())
}

/**
 * Web AgentAppAccessTab › AgentAppEventsSection (botiverse/slock#17263): the events apps sent to
 * this agent, newest first. Hidden completely until at least one event has been sent to this agent
 * (or loading fails); once visible, shows "App events" with Refresh, app filters (when more than
 * one app appears), expandable plain event rows, and Load More while older events exist.
 */
@Composable
internal fun MembersAgentAppEventsSection(
    agentId: MemberId,
    loader: MembersAgentAppEventsLoader?,
    detailLoader: MembersAgentAppEventLoader?,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
    state: MembersAgentAppEventsState = remember(agentId) { MembersAgentAppEventsState() },
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(Res.string.member_app_events_load_failed)

    fun load(cursor: String?) {
        val callback = loader ?: return
        val filter = state.appFilter
        val number = ++state.request
        state.loading = true
        state.error = null
        scope.launch {
            try {
                val result =
                    try {
                        callback(agentId, filter, cursor)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                if (number != state.request) return@launch
                when (result) {
                    is DataResult.Content -> {
                        val page = result.data
                        state.events = if (cursor != null) state.events + page.events else page.events
                        state.nextCursor = page.nextCursor
                        if (filter == null) {
                            val byId = LinkedHashMap<String, String>()
                            state.apps.forEach { (id, name) -> byId[id] = name }
                            page.events.forEach { byId[it.appClientId] = it.appName }
                            state.apps = byId.toList()
                        }
                        state.hasLoaded = true
                        state.loadedAgentId = agentId
                        state.loadedAppFilter = filter
                    }
                    is DataResult.Empty -> {
                        if (cursor == null) state.events = emptyList()
                        state.nextCursor = null
                        state.hasLoaded = true
                        state.loadedAgentId = agentId
                        state.loadedAppFilter = filter
                    }
                    else -> {
                        // A failed first page (another filter, a refresh) drops what the last one showed.
                        if (cursor == null) {
                            state.events = emptyList()
                            state.nextCursor = null
                        }
                        state.error = loadFailed
                        state.hasLoaded = false
                    }
                }
            } finally {
                if (number == state.request) state.loading = false
            }
        }
    }

    LaunchedEffect(agentId, state.appFilter, loader) {
        val alreadyLoadedForFilter =
            state.hasLoaded &&
                state.loadedAgentId == agentId &&
                state.loadedAppFilter == state.appFilter &&
                state.error == null
        if (!alreadyLoadedForFilter) {
            load(null)
        }
    }

    // No App has sent this Agent an event (or the first read is still out): hide the section
    // completely (App detail v6, botiverse/slock#17263). Once one has, or on error, keep it shown.
    if (state.events.isEmpty() && state.apps.isEmpty() && state.error == null) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = stringResource(Res.string.member_app_events_title),
                style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                modifier = Modifier.weight(1f),
            )
            RaftButton(
                text =
                    if (state.loading) stringResource(Res.string.common_loading)
                    else stringResource(Res.string.member_app_events_refresh),
                onClick = { load(null) },
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !state.loading && loader != null,
            )
        }
        if (state.apps.size > 1) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MembersAgentAppEventFilter(
                    text = stringResource(Res.string.member_app_events_all_apps),
                    active = state.appFilter == null,
                    onClick = { state.appFilter = null },
                )
                state.apps.forEach { (clientId, name) ->
                    MembersAgentAppEventFilter(text = name, active = state.appFilter == clientId, onClick = { state.appFilter = clientId })
                }
            }
        }
        state.error?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm) }
        if (state.events.isEmpty() && !state.loading && state.error == null) {
            RaftText(
                text = stringResource(Res.string.member_app_events_empty),
                style = typography.callout,
                color = colors.foregroundHint,
            )
        } else if (state.events.isNotEmpty()) {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                state.events.forEachIndexed { index, event ->
                    key(event.id) {
                        MembersAgentAppEventRow(
                            agentId = agentId,
                            event = event,
                            divider = index > 0,
                            detailLoader = detailLoader,
                            timeContext = timeContext,
                            state = state,
                        )
                    }
                }
            }
        }
        if (state.nextCursor != null) {
            RaftButton(
                text = stringResource(Res.string.member_app_events_load_more),
                onClick = { load(state.nextCursor) },
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !state.loading,
            )
        }
    }
}

/** Web AppEventRow: a plain ListRow that opens the event's payload (inert JSON) under it. */
@Composable
private fun MembersAgentAppEventRow(
    agentId: MemberId,
    event: AgentAppEvent,
    divider: Boolean,
    detailLoader: MembersAgentAppEventLoader?,
    timeContext: RaftTimeContext,
    state: MembersAgentAppEventsState,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(Res.string.member_app_event_load_failed)
    val open = event.id in state.expandedEventIds
    val payloadText = state.payloadByEventId[event.id]
    val error = state.errorByEventId[event.id]
    var loading by remember(event.id) { mutableStateOf(false) }

    fun loadDetail() {
        val callback = detailLoader
        if (callback == null) {
            loading = false
            state.errorByEventId = state.errorByEventId + (event.id to loadFailed)
            return
        }
        if (loading) return
        loading = true
        state.errorByEventId = state.errorByEventId - event.id
        scope.launch {
            try {
                val result =
                    try {
                        callback(agentId, event.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                when (result) {
                    is DataResult.Content -> {
                        state.payloadByEventId = state.payloadByEventId + (event.id to result.data.payloadText)
                        state.errorByEventId = state.errorByEventId - event.id
                    }
                    else -> state.errorByEventId = state.errorByEventId + (event.id to loadFailed)
                }
            } finally {
                loading = false
            }
        }
    }

    // Re-entering an expanded row whose prior in-flight load was cancelled by a tab switch
    // resumes loading instead of sticking in Loading, and settles cleanly when detailLoader is null.
    LaunchedEffect(open, event.id, detailLoader) {
        if (open && payloadText == null && !loading && error == null) {
            loadDetail()
        }
    }

    val statusBadge: (@Composable () -> Unit)? =
        event.status?.let { status -> @Composable { MembersAgentAppEventStatusBadge(status) } }
    Column(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = event.summary,
            variant = RaftListRowVariant.Plain,
            divider = divider,
            onClick = {
                val nextOpen = !open
                state.expandedEventIds =
                    if (nextOpen) {
                        state.expandedEventIds + event.id
                    } else {
                        state.expandedEventIds - event.id
                    }
                if (nextOpen && payloadText == null) {
                    loadDetail()
                }
            },
            meta = timeContext.formatTimestampLabel(event.createdAt).takeIf { it.isNotBlank() },
            description = listOf(event.appName, event.kind).filter { it.isNotBlank() }.joinToString(" · "),
            titleTrailing = statusBadge,
        )
        if (open) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                val text = payloadText
                when {
                    error != null -> RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                    text != null -> RaftCodeBlock(text = text, containerRadius = true)
                    loading -> RaftText(text = stringResource(Res.string.common_loading), style = typography.caption, color = colors.foregroundHint)
                    else -> RaftBanner(description = loadFailed, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                }
            }
        }
    }
}

/** Web: a soft Badge with the status pixel (task colours: done / todo / closed) and its word. */
@Composable
private fun MembersAgentAppEventStatusBadge(status: AgentAppEventStatus) {
    val colors = RaftTheme.colors
    val (variant, pixel, label) =
        when (status) {
            AgentAppEventStatus.Delivered ->
                Triple(RaftBadgeVariant.Success, colors.taskStatusDone, stringResource(Res.string.member_app_event_status_delivered))
            AgentAppEventStatus.Queued ->
                Triple(RaftBadgeVariant.Warning, colors.taskStatusTodo, stringResource(Res.string.member_app_event_status_queued))
            AgentAppEventStatus.Expired ->
                Triple(RaftBadgeVariant.Muted, colors.taskStatusClosed, stringResource(Res.string.member_app_event_status_expired))
        }
    RaftBadge(
        text = label,
        appearance = RaftBadgeAppearance.Soft,
        variant = variant,
        icon = { RaftStatusDot(color = pixel) },
    )
}

/**
 * Web AppEventFilterButton (Select `rf-filter`): 32dp, a `line-seam` outline at rest, the applied
 * filter `info-soft` without one, the label a footnote semibold.
 */
@Composable
private fun MembersAgentAppEventFilter(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .then(
                if (active) Modifier.background(colors.infoSoft, shape)
                else Modifier.border(1.dp, colors.lineSeam, shape)
            )
            .raftClickable(onClick = onClick, shape = shape, selected = active)
            .semantics { selected = active }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) colors.foreground else colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
