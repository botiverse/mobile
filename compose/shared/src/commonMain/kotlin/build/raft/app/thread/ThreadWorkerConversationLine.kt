package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAppStroke
import build.raft.app.RaftAvatarWithStatus
import build.raft.app.RaftColor
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftMemberAvatar
import build.raft.app.RaftMemberAvatarIdentity
import build.raft.app.RaftModalAnchoredPopover
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverAnchorResolver
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftWorkingFaces
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.CONVERSATION_WORKER_LINE_TICK_MS
import build.raft.app.data.HeldConversationWorkerState
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.WORKER_HUMAN_WAITING_AFTER_MS
import build.raft.app.data.WORKER_QUIET_AFTER_MS
import build.raft.app.data.WorkerConversationTarget
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.heldStatesFor
import build.raft.app.data.workerConversationTargetForTask
import build.raft.app.data.workerConversationTargetForThread
import build.raft.app.fill
import build.raft.app.model.AgentStatus
import build.raft.app.model.ConversationWorkerStateKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.resolveCurrentPlacement
import build.raft.app.resources.*
import build.raft.app.toRaftAvatarLabel
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay

/**
 * Worker v1 conversation status line (`worker_v1`, slock `WorkerConversationLine.tsx` +
 * `workerObservabilityText.ts`): sits in the bottom typing slot above the composer when nobody is
 * typing, showing who handles this thread or task conversation through a Worker. Tapping opens an
 * upward anchored popover with one row per agent.
 */

internal enum class WorkerLineTone { Default, Warning }

internal enum class WorkerLineScope { Tail, All }

/** Formatted parts of one Worker line: [who] truncates first; [tail] stays whole. */
@Immutable
internal data class WorkerLineParts(
    val who: String,
    val tail: String,
    val tone: WorkerLineTone,
    val scope: WorkerLineScope,
    val urgency: Long,
    val heldMs: Long,
)

/** Localized templates matching slock `workerObs.span.*` and `conversation.worker.*`. */
@Immutable
internal data class WorkerLineStrings(
    val spanMinutes: String,
    val spanHours: String,
    val spanDays: String,
    val handling: String,
    val handlingAnonymous: String,
    val handlingUnknown: String,
    val unknownAgentRow: String,
    val whoUnknown: String,
    val handedBackUnknown: String,
    val who: String,
    val whoAnonymous: String,
    val running: String,
    val waiting: String,
    val someoneWaiting: String,
    val handedBack: String,
    val handedBackSomeone: String,
    val waitingFor: String,
    val noActivity: String,
    val inTool: String,
    val combined: String,
    val popoverTitle: String,
)

private val WorkerLinePlaceholder = Regex("""\{(minutes|hours|days|worker|agent|person|tool|span|count)\}""")

private fun String.substituteWorkerTemplate(values: Map<String, String>): String =
    WorkerLinePlaceholder.replace(this) { match -> values[match.groupValues[1]] ?: match.value }

/** Formats a Worker span in whole minutes (at least 1): `"12m"`, `"2h 5m"`, `"3d 4h"`. */
internal fun formatWorkerSpan(strings: WorkerLineStrings, ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
    if (minutes < 60L) {
        return strings.spanMinutes.substituteWorkerTemplate(mapOf("minutes" to minutes.toString()))
    }
    val hours = minutes / 60L
    if (hours < 24L) {
        return strings.spanHours.substituteWorkerTemplate(
            mapOf("hours" to hours.toString(), "minutes" to (minutes % 60L).toString()),
        )
    }
    return strings.spanDays.substituteWorkerTemplate(
        mapOf("days" to (hours / 24L).toString(), "hours" to (hours % 24L).toString()),
    )
}

/**
 * Formats one agent's Worker state into [WorkerLineParts] (mirror of slock `formatConversationWorkerLine`),
 * or `null` for [ConversationWorkerStateKind.None].
 */
internal fun formatConversationWorkerLine(
    strings: WorkerLineStrings,
    held: HeldConversationWorkerState,
    agentHandle: String?,
    nowMs: Long,
    reduced: Boolean = false,
): WorkerLineParts? {
    val state = held.state
    val worker = state.worker
    val waitingFrom = state.waitingFrom
    val stateTool = state.tool
    val agent = agentHandle.orEmpty()
    val unnamed = agentHandle == null

    fun age(iso: String?): Long {
        val at = iso?.let(::parseRaftTimestampEpochMillisOrNull) ?: return 0L
        return (nowMs - at).coerceAtLeast(0L)
    }

    fun span(ms: Long): String = formatWorkerSpan(strings, ms)

    val heldMs = age(state.sinceAt)
    val handling =
        when {
            unnamed -> strings.handlingUnknown
            worker != null -> strings.handling.substituteWorkerTemplate(mapOf("worker" to worker, "agent" to agent))
            else -> strings.handlingAnonymous.substituteWorkerTemplate(mapOf("agent" to agent))
        }
    val who =
        when {
            unnamed -> strings.whoUnknown
            worker != null -> strings.who.substituteWorkerTemplate(mapOf("worker" to worker, "agent" to agent))
            else -> strings.whoAnonymous.substituteWorkerTemplate(mapOf("agent" to agent))
        }
    val waitingMs = age(state.waitingSinceAt ?: state.sinceAt)
    val quietMs = state.quietSinceAt?.let(::age)

    fun working(): WorkerLineParts {
        val tool = if (!reduced && state.doing == "tool" && !stateTool.isNullOrBlank()) stateTool else null
        val tail =
            if (tool != null) {
                "${span(heldMs)} · ${strings.running.substituteWorkerTemplate(mapOf("tool" to tool))}"
            } else {
                span(heldMs)
            }
        return WorkerLineParts(
            who = handling,
            tail = tail,
            tone = WorkerLineTone.Default,
            scope = WorkerLineScope.Tail,
            urgency = 0L,
            heldMs = heldMs,
        )
    }

    return when (state.state) {
        ConversationWorkerStateKind.None -> null
        ConversationWorkerStateKind.HandedBack -> {
            val handedWho =
                when {
                    unnamed -> strings.handedBackUnknown
                    waitingFrom != null ->
                        strings.handedBack.substituteWorkerTemplate(mapOf("person" to waitingFrom, "agent" to agent))
                    else -> strings.handedBackSomeone.substituteWorkerTemplate(mapOf("agent" to agent))
                }
            WorkerLineParts(
                who = handedWho,
                tail = strings.waitingFor.substituteWorkerTemplate(mapOf("span" to span(waitingMs))),
                tone = WorkerLineTone.Warning,
                scope = WorkerLineScope.All,
                urgency = 2_000_000_000L + waitingMs,
                heldMs = heldMs,
            )
        }
        ConversationWorkerStateKind.Waiting -> {
            val warn = waitingMs >= WORKER_HUMAN_WAITING_AFTER_MS
            val waitingTail =
                if (waitingFrom != null) {
                    strings.waiting.substituteWorkerTemplate(mapOf("person" to waitingFrom, "span" to span(waitingMs)))
                } else {
                    strings.someoneWaiting.substituteWorkerTemplate(mapOf("span" to span(waitingMs)))
                }
            WorkerLineParts(
                who = handling,
                tail = waitingTail,
                tone = if (warn) WorkerLineTone.Warning else WorkerLineTone.Default,
                scope = WorkerLineScope.Tail,
                urgency = if (warn) 2_000_000_000L + waitingMs else 0L,
                heldMs = heldMs,
            )
        }
        ConversationWorkerStateKind.Stalled -> {
            if (worker == null && quietMs == null) {
                working()
            } else {
                val silentMs = quietMs ?: (WORKER_QUIET_AFTER_MS + (nowMs - held.stateSeenAtMs).coerceAtLeast(0L))
                WorkerLineParts(
                    who = who,
                    tail = strings.noActivity.substituteWorkerTemplate(mapOf("span" to span(silentMs))),
                    tone = WorkerLineTone.Warning,
                    scope = WorkerLineScope.Tail,
                    urgency = 1_000_000_000L + silentMs,
                    heldMs = heldMs,
                )
            }
        }
        ConversationWorkerStateKind.Handling -> {
            val inToolMs = quietMs ?: (nowMs - held.doingSeenAtMs).coerceAtLeast(0L)
            if (!unnamed && !reduced && state.doing == "tool" && !stateTool.isNullOrBlank() && inToolMs >= WORKER_QUIET_AFTER_MS) {
                WorkerLineParts(
                    who = who,
                    tail = strings.inTool.substituteWorkerTemplate(mapOf("tool" to stateTool, "span" to span(inToolMs))),
                    tone = WorkerLineTone.Default,
                    scope = WorkerLineScope.Tail,
                    urgency = 0L,
                    heldMs = heldMs,
                )
            } else {
                working()
            }
        }
    }
}

/** Combines several agents' Worker lines into one summary line (mirror of slock `combineWorkerLines`). */
internal fun combineWorkerLines(strings: WorkerLineStrings, lines: List<WorkerLineParts>): WorkerLineParts {
    var urgent: WorkerLineParts? = null
    var maxHeldMs = 0L
    for (line in lines) {
        if (line.heldMs > maxHeldMs) maxHeldMs = line.heldMs
        if (line.urgency > (urgent?.urgency ?: 0L)) urgent = line
    }
    return WorkerLineParts(
        who = strings.combined.substituteWorkerTemplate(mapOf("count" to lines.size.toString())),
        tail = urgent?.tail ?: formatWorkerSpan(strings, maxHeldMs),
        tone = if (urgent != null) WorkerLineTone.Warning else WorkerLineTone.Default,
        scope = WorkerLineScope.Tail,
        urgency = urgent?.urgency ?: 0L,
        heldMs = maxHeldMs,
    )
}

@Composable
internal fun rememberWorkerLineStrings(): WorkerLineStrings =
    WorkerLineStrings(
        spanMinutes = stringResource(Res.string.worker_obs_span_minutes),
        spanHours = stringResource(Res.string.worker_obs_span_hours),
        spanDays = stringResource(Res.string.worker_obs_span_days),
        handling = stringResource(Res.string.conversation_worker_handling),
        handlingAnonymous = stringResource(Res.string.conversation_worker_handling_anonymous),
        handlingUnknown = stringResource(Res.string.conversation_worker_handling_unknown),
        unknownAgentRow = stringResource(Res.string.conversation_worker_unknown_agent_row),
        whoUnknown = stringResource(Res.string.conversation_worker_who_unknown),
        handedBackUnknown = stringResource(Res.string.conversation_worker_handed_back_unknown),
        who = stringResource(Res.string.conversation_worker_who),
        whoAnonymous = stringResource(Res.string.conversation_worker_who_anonymous),
        running = stringResource(Res.string.conversation_worker_running),
        waiting = stringResource(Res.string.conversation_worker_waiting),
        someoneWaiting = stringResource(Res.string.conversation_worker_someone_waiting),
        handedBack = stringResource(Res.string.conversation_worker_handed_back),
        handedBackSomeone = stringResource(Res.string.conversation_worker_handed_back_someone),
        waitingFor = stringResource(Res.string.conversation_worker_waiting_for),
        noActivity = stringResource(Res.string.conversation_worker_no_activity),
        inTool = stringResource(Res.string.conversation_worker_in_tool),
        combined = stringResource(Res.string.conversation_worker_combined),
        popoverTitle = stringResource(Res.string.conversation_worker_popover_title),
    )

/** Resolved agent identity for one Worker line entry (channel members first, then server members). */
@Immutable
internal data class ResolvedThreadWorkerAgent(
    val handle: String,
    val displayName: String,
    val avatarWireValue: String?,
)

/** One row inside the conversation Worker popover (and backing data for the collapsed line). */
@Immutable
internal data class ThreadWorkerConversationRow(
    val agentId: String,
    val agentName: String,
    val named: Boolean,
    val avatarWireValue: String?,
    val worker: String?,
    val parts: WorkerLineParts,
)

/** Live UI model for the conversation Worker line above the composer; null when nothing to show. */
@Immutable
internal data class ThreadWorkerConversationUi(
    val serverId: String,
    val rows: List<ThreadWorkerConversationRow>,
    val line: WorkerLineParts,
    val popoverTitle: String,
    val onOpenAgent: (agentId: String) -> Unit = {},
)

internal val LocalThreadWorkerConversation = compositionLocalOf<ThreadWorkerConversationUi?> { null }

/** Resolves the Worker target for a reply thread or task conversation; null for top-level channels/DMs. */
internal fun RaftThreadScreenPayload.workerConversationTarget(
    taskConversation: ThreadTaskConversationState,
): WorkerConversationTarget? =
    workerConversationTarget(
        taskConversationChannelId = taskConversation.conversationId?.value,
        taskId = taskConversation.task?.id?.value ?: taskConversation.headerState?.ref?.taskId?.value,
    )

internal fun RaftThreadScreenPayload.workerConversationTarget(
    taskConversationChannelId: String?,
    taskId: String?,
): WorkerConversationTarget? {
    if (!agentTypingLineCanShow()) return null
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        return workerConversationTargetForThread(
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId ?: originParentMessageId,
        )
    }
    if (channelKind == RaftTaskConversationChannelKind || !taskConversationChannelId.isNullOrBlank()) {
        return workerConversationTargetForTask(
            taskChannelId = taskConversationChannelId ?: streamChannelId,
            taskId = taskId,
        )
    }
    return null
}

/**
 * Resolves an agent's name and avatar for the Worker line: channel members first (includes other
 * servers' agents in joint channels), then this server's member roster. Returns null when the agent
 * cannot be named (never fabricates `@Agent`).
 */
internal fun resolveThreadWorkerAgent(
    agentId: String,
    channelMembers: List<RaftThreadMemberItem>,
    serverMembers: List<MemberProfile>,
    durableAvatar: (String) -> String? = { null },
): ResolvedThreadWorkerAgent? {
    val channel = channelMembers.firstOrNull { it.id == agentId && it.kind.equals("agent", ignoreCase = true) }
    if (channel != null) {
        val handle = channel.handle.removePrefix("@").trim().ifBlank { channel.displayName.trim() }
        if (handle.isNotBlank()) {
            val displayName = channel.displayName.trim().ifBlank { handle }
            val avatar = channel.avatarUrl?.takeIf { it.isNotBlank() } ?: durableAvatar(agentId)
            return ResolvedThreadWorkerAgent(handle = handle, displayName = displayName, avatarWireValue = avatar)
        }
    }
    val server = serverMembers.firstOrNull { it.id.value == agentId && it.kind == MemberKind.Agent }
    if (server != null) {
        val handle = server.handle.removePrefix("@").trim().ifBlank { server.displayName.trim() }
        if (handle.isNotBlank()) {
            val displayName = server.displayName.trim().ifBlank { handle }
            val avatar = server.avatarWireValueForRenderedAvatar() ?: durableAvatar(agentId)
            return ResolvedThreadWorkerAgent(handle = handle, displayName = displayName, avatarWireValue = avatar)
        }
    }
    return null
}

/**
 * Pure row projection from held store states + directory + typing set (used by both Compose and unit tests).
 */
internal fun buildThreadWorkerConversationUi(
    serverId: String,
    heldStates: List<HeldConversationWorkerState>,
    typingAgentIds: Set<String>,
    channelMembers: List<RaftThreadMemberItem>,
    serverMembers: List<MemberProfile>,
    strings: WorkerLineStrings,
    nowMs: Long,
    reduced: Boolean,
    durableAvatar: (String) -> String? = { null },
    onOpenAgent: (String) -> Unit = {},
): ThreadWorkerConversationUi? {
    if (heldStates.isEmpty()) return null
    val rows =
        heldStates.mapNotNull { held ->
            val agentId = held.state.agentId.value
            if (agentId in typingAgentIds) return@mapNotNull null
            val resolved = resolveThreadWorkerAgent(agentId, channelMembers, serverMembers, durableAvatar)
            val handle = resolved?.handle
            val parts = formatConversationWorkerLine(strings, held, handle, nowMs, reduced) ?: return@mapNotNull null
            ThreadWorkerConversationRow(
                agentId = agentId,
                agentName = if (resolved != null) resolved.displayName else strings.unknownAgentRow,
                named = resolved != null,
                avatarWireValue = resolved?.avatarWireValue,
                worker = if (resolved != null) held.state.worker else null,
                parts = parts,
            )
        }
    if (rows.isEmpty()) return null
    val line = if (rows.size == 1) rows[0].parts else combineWorkerLines(strings, rows.map { it.parts })
    return ThreadWorkerConversationUi(
        serverId = serverId,
        rows = rows,
        line = line,
        popoverTitle = strings.popoverTitle,
        onOpenAgent = onOpenAgent,
    )
}

/**
 * Binds an open reply thread or task conversation to [SharedConversationWorkerStateStore].
 * When [workerV1Enabled] is false, performs zero network requests, registers zero listeners, and
 * returns null immediately.
 */
@Composable
internal fun rememberThreadWorkerConversation(
    workerV1Enabled: Boolean,
    payload: RaftThreadScreenPayload,
    taskConversation: ThreadTaskConversationState,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    serverMembersStore: SharedServerMembersStore?,
    typingAgentIds: Set<String>,
    realtimeEvents: RaftRealtimeEvents? = null,
    openAgent: (agentId: String) -> Unit = {},
): ThreadWorkerConversationUi? {
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() }
    val target =
        if (workerV1Enabled && server != null) {
            payload.workerConversationTarget(taskConversation)
        } else {
            null
        }
    if (!workerV1Enabled || server == null || target == null) return null

    val store = remember(sharedStores, storeScope) { sharedStores.conversationWorkerStateStore(storeScope) }
    DisposableEffect(store, server, target, realtimeEvents) {
        store.bindServer(server)
        store.acquire(server, target)
        val subscription =
            realtimeEvents?.addListener { eventData ->
                if (isRealtimeTransportConnectedEvent(eventData, server)) {
                    store.refresh(server, target)
                }
            }
        onDispose {
            if (subscription != null) realtimeEvents.removeListener(subscription)
            store.release(server, target)
        }
    }

    val storeState by store.state.collectAsState()
    val heldStates = remember(storeState, server, target) { storeState.heldStatesFor(server, target) }
    var tickNowMs by remember { mutableLongStateOf(defaultNowMillis()) }
    LaunchedEffect(heldStates.isNotEmpty()) {
        if (heldStates.isEmpty()) return@LaunchedEffect
        tickNowMs = defaultNowMillis()
        while (true) {
            delay(CONVERSATION_WORKER_LINE_TICK_MS)
            tickNowMs = defaultNowMillis()
        }
    }
    if (heldStates.isEmpty()) return null

    val effectiveNowMs =
        remember(heldStates, tickNowMs) {
            heldStates.fold(tickNowMs) { latest, entry -> maxOf(latest, entry.receivedAtMs) }
        }
    val reduced = !RaftDisplayPreferenceRuntime.observeShowLiveAgentActivity()
    val membersState = serverMembersStore?.state?.collectAsState()?.value
    val serverMembers =
        remember(membersState, server) { serverMembersStore?.currentServerMembers(ServerId(server)).orEmpty() }
    val strings = rememberWorkerLineStrings()
    val latestOpenAgent by rememberUpdatedState(openAgent)
    return remember(
        server,
        heldStates,
        typingAgentIds,
        payload.members,
        payload.memberCandidates,
        serverMembers,
        strings,
        effectiveNowMs,
        reduced,
    ) {
        buildThreadWorkerConversationUi(
            serverId = server,
            heldStates = heldStates,
            typingAgentIds = typingAgentIds,
            channelMembers = payload.members + payload.memberCandidates,
            serverMembers = serverMembers,
            strings = strings,
            nowMs = effectiveNowMs,
            reduced = reduced,
            durableAvatar = { id -> serverMembersStore?.durableMemberAvatarWireValue(ServerId(server), id) },
            onOpenAgent = { agentId -> latestOpenAgent(agentId) },
        )
    }
}

private const val ThreadWorkerMaxFaces = 3
private val ThreadWorkerPopoverWidth = 300.dp
private val ThreadWorkerPopoverRowEstimate = 58.dp
private const val ThreadWorkerWarningInlineId = "worker-warning-triangle"

/**
 * The conversation Worker status line drawn in the bottom typing slot above the composer.
 * Tapping opens [ThreadWorkerConversationPopover] upward from the line.
 */
@Composable
internal fun ThreadWorkerConversationLine(
    workerLine: ThreadWorkerConversationUi,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "thread-worker-conversation-line") }
    var anchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val label = "${workerLine.line.who} · ${workerLine.line.tail}"
    val faceItems =
        remember(workerLine.rows) {
            workerLine.rows.take(ThreadWorkerMaxFaces).map { row ->
                RaftLiveAgentActivityItem(
                    agentId = row.agentId,
                    name = row.agentName.toRaftAvatarLabel(),
                    avatarWireValue = row.avatarWireValue,
                    status = AgentStatus.Working,
                    startedAt = 0L,
                )
            }
        }
    val toggle = {
        anchorResolver = anchorRef.resolver(RaftPopoverLocalPoint.Exact(Offset.Zero))
        open = !open
    }
    Box(
        modifier = modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight).testTag("worker-conversation-line"),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ThreadAgentTypingLineHeight)
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .offset(x = (-4).dp)
                        .raftCaptureLiveRootAnchor(anchorRef)
                        .clip(RoundedCornerShape(6.dp))
                        .hoverable(interaction)
                        .raftClickable(
                            onClick = toggle,
                            shape = RoundedCornerShape(6.dp),
                            tier = RaftHoverTier.None,
                            interactionSource = interaction,
                        )
                        .clearAndSetSemantics {
                            testTag = "worker-conversation-trigger"
                            contentDescription = label
                            role = Role.Button
                            onClick {
                                toggle()
                                true
                            }
                        }
                        .height(ThreadAgentTypingLineHeight)
                        .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftWorkingFaces(faceItems, still = true)
                WorkerConversationSingleLineText(
                    parts = workerLine.line,
                    hoverUnderline = hovered,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
    if (open) {
        val anchor = anchorResolver?.resolveCurrentPlacement()
        if (anchor == null) {
            LaunchedEffect(Unit) { open = false }
        } else {
            ThreadWorkerConversationPopover(
                anchor = anchor,
                workerLine = workerLine,
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun WorkerConversationSingleLineText(
    parts: WorkerLineParts,
    hoverUnderline: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val warnAll = parts.tone == WorkerLineTone.Warning && parts.scope == WorkerLineScope.All
    val warnTail = parts.tone == WorkerLineTone.Warning
    val baseWhoStyle =
        typography.footnote.let { base ->
            var styled = if (warnAll) base.copy(fontWeight = FontWeight.SemiBold) else base
            if (hoverUnderline) styled = styled.copy(textDecoration = TextDecoration.Underline)
            styled
        }
    val whoColor = if (warnAll) colors.warningStrong else colors.foregroundMuted
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = parts.who,
            style = baseWhoStyle,
            color = whoColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("worker-line-who"),
        )
        Text(
            text = " · ",
            style = typography.footnote,
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
        )
        if (warnTail) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("worker-line-tail")) {
                RaftGlyphIcon(
                    RaftGlyph.TriangleAlert,
                    size = 14.dp,
                    color = colors.warningStrong,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = parts.tail,
                    style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.warningStrong,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                text = parts.tail,
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("worker-line-tail"),
            )
        }
    }
}

@Composable
private fun WorkerConversationWrappedText(parts: WorkerLineParts) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val warnAll = parts.tone == WorkerLineTone.Warning && parts.scope == WorkerLineScope.All
    val warnTail = parts.tone == WorkerLineTone.Warning
    val text =
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = if (warnAll) colors.warningStrong else colors.foregroundMuted,
                    fontWeight = if (warnAll) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ) {
                append(parts.who)
            }
            withStyle(SpanStyle(color = colors.foregroundMuted)) {
                append(" · ")
            }
            if (warnTail) {
                appendInlineContent(ThreadWorkerWarningInlineId, "!")
                append(" ")
                withStyle(SpanStyle(color = colors.warningStrong, fontWeight = FontWeight.SemiBold)) {
                    append(parts.tail)
                }
            } else {
                withStyle(SpanStyle(color = colors.foregroundMuted)) {
                    append(parts.tail)
                }
            }
        }
    val inlineContent =
        if (warnTail) {
            mapOf(
                ThreadWorkerWarningInlineId to
                    InlineTextContent(
                        Placeholder(width = 14.sp, height = 14.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
                    ) {
                        RaftGlyphIcon(RaftGlyph.TriangleAlert, size = 14.dp, color = colors.warningStrong)
                    },
            )
        } else {
            emptyMap()
        }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = typography.footnote,
        color = colors.foregroundMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ThreadWorkerConversationPopover(
    anchor: RaftPopoverAnchor,
    workerLine: ThreadWorkerConversationUi,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(Size.Zero) }
    val estimatedHeight = 44.dp + 12.dp + ThreadWorkerPopoverRowEstimate * workerLine.rows.size.coerceAtMost(7)
    val size =
        if (measured != Size.Zero) measured
        else with(density) { Size(ThreadWorkerPopoverWidth.toPx(), estimatedHeight.toPx()) }
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = size,
        onDismiss = onDismiss,
        horizontalOffset = 0f,
        verticalOffset = with(density) { 4.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = RaftFloatingVerticalPlacement.Above,
        dismissActivationDelayMillis = 0L,
    ) {
        Box(Modifier.onSizeChanged { measured = Size(it.width.toFloat(), it.height.toFloat()) }) {
            ThreadWorkerConversationPanel(
                workerLine = workerLine,
                onOpenAgent = { agentId ->
                    onDismiss()
                    workerLine.onOpenAgent(agentId)
                },
            )
        }
    }
}

@Composable
internal fun ThreadWorkerConversationPanel(
    workerLine: ThreadWorkerConversationUi,
    onOpenAgent: (String) -> Unit,
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftPopoverSurface(
            // raw-click: tap sink, keeps taps on its blank space from reaching what is under it; not a control
            modifier = Modifier.testTag("worker-conversation-popover").clickable { },
            width = ThreadWorkerPopoverWidth,
            contentPadding = PaddingValues(0.dp),
            header = {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    RaftText(workerLine.popoverTitle, style = typography.headline, color = colors.foreground)
                }
                Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(colors.lineSeam))
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                workerLine.rows.forEach { row ->
                    ThreadWorkerConversationPopoverRow(
                        serverId = workerLine.serverId,
                        row = row,
                        onOpenAgent = onOpenAgent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadWorkerConversationPopoverRow(
    serverId: String,
    row: ThreadWorkerConversationRow,
    onOpenAgent: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .testTag("worker-conversation-popover-row-${row.agentId}")
            .then(
                if (row.named) {
                    Modifier.raftClickable(
                        onClick = { onOpenAgent(row.agentId) },
                        role = Role.Button,
                        tier = RaftHoverTier.Subtle,
                    )
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftAvatarWithStatus(
            statusColor = RaftColor.PresenceBusy,
            statusDescription = null,
            avatarSize = RaftAvatarSize.Sm.edge,
            cutoutColor = colors.layerPopover,
        ) {
            RaftMemberAvatar(
                identity = RaftMemberAvatarIdentity(kind = MemberKind.Agent, memberId = row.agentId, serverId = serverId),
                label = row.agentName.toRaftAvatarLabel(),
                avatarSize = RaftAvatarSize.Sm.edge,
                carriedAvatarWireValue = row.avatarWireValue,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    row.agentName,
                    style = typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.worker != null) {
                    RaftText(
                        row.worker,
                        style = typography.monoMeta,
                        color = colors.foregroundMuted,
                        maxLines = 1,
                    )
                }
            }
            WorkerConversationWrappedText(parts = row.parts)
        }
    }
}
