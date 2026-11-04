package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
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
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Loads a page of an agent's app events: (agent, app filter, `before` cursor). */
internal typealias MembersAgentAppEventsLoader = suspend (MemberId, String?, String?) -> DataResult<AgentAppEventPage>

/** Loads one app event's payload: (agent, event id). */
internal typealias MembersAgentAppEventLoader = suspend (MemberId, String) -> DataResult<AgentAppEventDetail>

/**
 * Web AgentAppAccessTab › AgentAppEventsSection: the events apps sent to this agent, newest
 * first. A SectionHeader ("App events" with an outline Refresh), its description, then app filters
 * (once more than one app shows), the events as plain list rows that open the payload under them,
 * or "No app events yet"; Load More while there are older ones. Loaded when shown.
 */
@Composable
internal fun MembersAgentAppEventsSection(
    agentId: MemberId,
    loader: MembersAgentAppEventsLoader?,
    detailLoader: MembersAgentAppEventLoader?,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(Res.string.member_app_events_load_failed)
    var events by remember(agentId) { mutableStateOf<List<AgentAppEvent>>(emptyList()) }
    var nextCursor by remember(agentId) { mutableStateOf<String?>(null) }
    var appFilter by remember(agentId) { mutableStateOf<String?>(null) }
    // Filter chips come from the apps seen so far in the unfiltered list (web).
    var apps by remember(agentId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }
    var request by remember(agentId) { mutableStateOf(0) }

    fun load(cursor: String?) {
        val callback = loader ?: return
        val filter = appFilter
        val number = ++request
        loading = true
        error = null
        scope.launch {
            val result =
                try {
                    callback(agentId, filter, cursor)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
            if (number != request) return@launch
            when (result) {
                is DataResult.Content -> {
                    val page = result.data
                    events = if (cursor != null) events + page.events else page.events
                    nextCursor = page.nextCursor
                    if (filter == null) {
                        val byId = LinkedHashMap<String, String>()
                        apps.forEach { (id, name) -> byId[id] = name }
                        page.events.forEach { byId[it.appClientId] = it.appName }
                        apps = byId.toList()
                    }
                }
                is DataResult.Empty -> {
                    if (cursor == null) events = emptyList()
                    nextCursor = null
                }
                else -> {
                    // A failed first page (another filter, a refresh) drops what the last one showed.
                    if (cursor == null) {
                        events = emptyList()
                        nextCursor = null
                    }
                    error = loadFailed
                }
            }
            loading = false
        }
    }

    LaunchedEffect(agentId, appFilter, loader) { load(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftSectionEyebrow(text = stringResource(Res.string.member_app_events_title), modifier = Modifier.weight(1f))
                RaftButton(
                    text =
                        if (loading) stringResource(Res.string.common_loading)
                        else stringResource(Res.string.member_app_events_refresh),
                    onClick = { load(null) },
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    enabled = !loading && loader != null,
                )
            }
            RaftText(
                text = stringResource(Res.string.member_app_events_hint),
                style = typography.callout,
                color = colors.foregroundMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (apps.size > 1) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MembersAgentAppEventFilter(
                    text = stringResource(Res.string.member_app_events_all_apps),
                    active = appFilter == null,
                    onClick = { appFilter = null },
                )
                apps.forEach { (clientId, name) ->
                    MembersAgentAppEventFilter(text = name, active = appFilter == clientId, onClick = { appFilter = clientId })
                }
            }
        }
        error?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm) }
        if (events.isEmpty() && !loading && error == null) {
            RaftText(
                text = stringResource(Res.string.member_app_events_empty),
                style = typography.callout,
                color = colors.foregroundHint,
            )
        } else if (events.isNotEmpty()) {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                events.forEachIndexed { index, event ->
                    key(event.id) {
                        MembersAgentAppEventRow(
                            agentId = agentId,
                            event = event,
                            divider = index > 0,
                            detailLoader = detailLoader,
                            timeContext = timeContext,
                        )
                    }
                }
            }
        }
        if (nextCursor != null) {
            RaftButton(
                text = stringResource(Res.string.member_app_events_load_more),
                onClick = { load(nextCursor) },
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !loading,
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
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(Res.string.member_app_event_load_failed)
    var open by remember(event.id) { mutableStateOf(false) }
    var payloadText by remember(event.id) { mutableStateOf<String?>(null) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    val statusBadge: (@Composable () -> Unit)? =
        event.status?.let { status -> @Composable { MembersAgentAppEventStatusBadge(status) } }
    Column(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = event.summary,
            variant = RaftListRowVariant.Plain,
            divider = divider,
            onClick = {
                open = !open
                val callback = detailLoader
                if (open && payloadText == null && callback != null) {
                    error = null
                    scope.launch {
                        val result =
                            try {
                                callback(agentId, event.id)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                null
                            }
                        when (result) {
                            is DataResult.Content -> payloadText = result.data.payloadText
                            else -> error = loadFailed
                        }
                    }
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
                    error != null -> RaftBanner(description = error.orEmpty(), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                    text != null -> RaftCodeBlock(text = text, containerRadius = true)
                    else -> RaftText(text = stringResource(Res.string.common_loading), style = typography.caption, color = colors.foregroundHint)
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
            .clickable(role = Role.Button, onClick = onClick)
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
