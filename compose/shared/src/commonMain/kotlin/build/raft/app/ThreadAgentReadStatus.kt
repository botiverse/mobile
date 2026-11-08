package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageMention
import build.raft.app.model.ReadReceiptPeerKind
import build.raft.designsystem.components.RaftAvatarGroup
import build.raft.designsystem.components.RaftAvatarGroupCount
import build.raft.designsystem.components.RaftAvatarSize

/**
 * Agent read progress (`agent_read_progress_v0` with `read_receipts_v0`) as the web draws it
 * (botiverse/slock staging 61eaa22, #13549 and #13513: `packages/web/src/store/agentReadProgressDomain.ts`,
 * `components/message/AgentReadFaces.tsx`, `hooks/useAgentReadProgress.ts`), in a channel or a reply
 * thread, never on a DM surface (it keeps its "Read" line):
 *  - Only agents, and only the relevant ones: those that spoke or were @mentioned in the
 *    conversation's last [ThreadAgentReadRelevanceWindow] messages, plus those working here now;
 *    working agents first, then the most recently active. At the live edge only.
 *  - A, the faces: each relevant agent's 19dp face on the last loaded message at or below its read
 *    position ([placeThreadAgentReads]), [ThreadAgentReadMaxFacesNarrow] per message on a phone width
 *    ([ThreadAgentReadMaxFaces] from 768dp) then "+N". Who read, replied or is working is the faces'
 *    to say (a working agent's face carries the busy light).
 *  - B, the line: under the viewer's own latest message, only when it will not reach a relevant agent
 *    that stopped following the thread or muted the channel and has done nothing with it yet, and the
 *    message @mentions no agent (a mention reaches it). "Stopped following: Kirby · Mention them to
 *    reach them": the names give way to the ellipsis, never the "·" and the hint.
 *
 * Mobile has no hover: a tap on the faces says who read up to there (every name, the web's tooltips);
 * a tap on a truncated line shows every name (the web's tooltip).
 */
internal const val ThreadAgentReadRelevanceWindow = 30

/** Web `AGENT_READ_MAX_FACES`: faces per message before the rest fold into +N. */
internal const val ThreadAgentReadMaxFaces = 3

/** Web `AGENT_READ_MAX_FACES_NARROW`: the same on a phone width (web `max-width: 767px`). */
internal const val ThreadAgentReadMaxFacesNarrow = 2

/** Below this width the faces use [ThreadAgentReadMaxFacesNarrow] (web `NARROW_QUERY`). */
internal val ThreadAgentReadWideMinWidth: Dp = 768.dp

/** Web `AGENT_READ_STATUS_MAX_NAMES`: names the line spells out before "+N". */
internal const val ThreadAgentReadStatusMaxNames = 3

/** Web `AgentReadStatusState`: why the message will not reach an agent; declaration order is display order. */
internal enum class ThreadAgentReadState { StoppedFollowing, Muted }

@Immutable
internal data class ThreadAgentReadEntry(val agentId: String, val state: ThreadAgentReadState)

/** The line for one message: its entries in display order and every name it may spell out. */
@Immutable
internal data class ThreadAgentReadStatusUi(
    val messageId: String,
    val entries: List<ThreadAgentReadEntry>,
    val names: Map<String, String>,
)

/** One agent's face on the timeline (web `AgentReadFace`). */
@Immutable
internal data class ThreadAgentReadFace(
    val agentId: String,
    val name: String,
    /** Typing in this conversation now: the face carries the busy light. */
    val working: Boolean = false,
)

/** Web `AgentReadProgressModel`: the faces per message and the line, for one open conversation. */
@Immutable
internal data class ThreadAgentReadProgressUi(
    /** The server the agents' faces are read from. */
    val serverId: String?,
    /** Message id -> its faces, working agents first. */
    val facesByMessageId: Map<String, List<ThreadAgentReadFace>>,
    val status: ThreadAgentReadStatusUi?,
)

/** Web `agentReadPositions`: each agent's read position, or null when there is none to show. */
internal fun threadAgentReadPositions(scope: PeerReadReceiptScope?): Map<String, Long>? {
    if (scope !is PeerReadReceiptScope.Peers) return null
    val positions = linkedMapOf<String, Long>()
    scope.peers.forEach { if (it.peerKind == ReadReceiptPeerKind.Agent) positions[it.peerId] = it.maxReadSeq }
    return positions.takeIf { it.isNotEmpty() }
}

/** Web `agentsStoppedFollowing`: unknown is never "stopped". */
internal fun threadAgentsStoppedFollowing(scope: PeerReadReceiptScope?): Set<String> {
    if (scope !is PeerReadReceiptScope.Peers) return emptySet()
    return scope.peers.filter { it.peerKind == ReadReceiptPeerKind.Agent && it.stoppedFollowing }.mapTo(linkedSetOf()) { it.peerId }
}

/** Web `agentsMutedChannel`: unknown is never "muted". */
internal fun threadAgentsMutedChannel(scope: PeerReadReceiptScope?): Set<String> {
    if (scope !is PeerReadReceiptScope.Peers) return emptySet()
    return scope.peers.filter { it.peerKind == ReadReceiptPeerKind.Agent && it.mutedChannel }.mapTo(linkedSetOf()) { it.peerId }
}

private val ThreadAgentReadMentionToken = Regex("""(?<![\w@])@([\w.\-]+)""")

/**
 * The agents [message] @mentions, in order, without repeats. Its resolved `mentions[]` (web
 * `message.mentions`) when it has any; a row that carries none (cached, optimistic) falls back to its
 * body's `@handle` tokens through the page directory, as its mention badges do.
 */
internal fun RaftThreadMessageItem.threadAgentMentionIds(directory: Map<String, MessageMention> = emptyMap()): List<String> {
    val mentions =
        mentions.ifEmpty {
            if (directory.isEmpty()) {
                emptyList()
            } else {
                ThreadAgentReadMentionToken.findAll(body).mapNotNull { directory[it.groupValues[1]] }.toList()
            }
        }
    return mentions.filter { it.type == "agent" && it.id.isNotBlank() }.map { it.id }.distinct()
}

private fun RaftThreadMessageItem.isAgentChat(): Boolean = authorKind.equals("agent", ignoreCase = true) && !isSystemMessage()

/**
 * Web `relevantReadAgentIds`: working agents first (in the order they started), then by how recently
 * they spoke or were @mentioned in the last [window] messages; only agents with a read position.
 */
internal fun threadRelevantReadAgentIds(
    messages: List<RaftThreadMessageItem>,
    positions: Map<String, Long>,
    workingAgentIds: List<String>,
    directory: Map<String, MessageMention> = emptyMap(),
    window: Int = ThreadAgentReadRelevanceWindow,
): List<String> {
    val ids = linkedSetOf<String>()
    fun add(agentId: String) {
        if (agentId in positions) ids += agentId
    }
    workingAgentIds.forEach(::add)
    val start = maxOf(0, messages.size - window)
    for (index in messages.size - 1 downTo start) {
        val message = messages[index]
        if (message.isSystemMessage()) continue
        if (message.isAgentChat()) add(message.authorId)
        message.threadAgentMentionIds(directory).forEach(::add)
    }
    return ids.toList()
}

/**
 * Web `placeAgentReads`: message id -> the agents whose face sits on it, each list in [agentIds]
 * order. An agent's face goes on the last loaded message with a real seq at or below its read
 * position; older than every loaded message, no face. With [hasNewer] (a context window) an agent
 * that read past the last loaded message stopped somewhere not shown: no face, never a false "read
 * up to here" on the window's last row.
 */
internal fun placeThreadAgentReads(
    messages: List<RaftThreadMessageItem>,
    positions: Map<String, Long>,
    agentIds: List<String>,
    hasNewer: Boolean = false,
): Map<String, List<String>> {
    val rows = messages.mapNotNull { message -> message.seq?.takeIf { it > 0 }?.let { message.id to it } }.sortedBy { it.second }
    val placement = linkedMapOf<String, MutableList<String>>()
    for (agentId in agentIds) {
        val maxReadSeq = positions[agentId] ?: continue
        // The last row at or below the position (binary search over seq).
        var low = 0
        var high = rows.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (rows[mid].second <= maxReadSeq) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) continue
        if (hasNewer && found == rows.size - 1 && maxReadSeq > rows[found].second) continue
        placement.getOrPut(rows[found].first) { mutableListOf() } += agentId
    }
    return placement
}

/**
 * Web `ownLatestMessageId`: the viewer's own latest message among the last [window] loaded ones, only
 * at the live edge ([hasNewer] false: a context window's last row is not the conversation's last).
 */
internal fun threadOwnLatestMessageId(
    messages: List<RaftThreadMessageItem>,
    hasNewer: Boolean,
    window: Int = ThreadAgentReadRelevanceWindow,
): String? {
    if (hasNewer) return null
    val start = maxOf(0, messages.size - window)
    for (index in messages.size - 1 downTo start) {
        val message = messages[index]
        if (message.isSystemMessage()) continue
        if (message.isOwn && !message.authorKind.equals("agent", ignoreCase = true)) return message.id
    }
    return null
}

/**
 * Web `projectAgentReadStatus`: the relevant agents the viewer's message [messageId] will not reach,
 * sorted stopped following then muted, or null when it reaches everyone (then there is no line). An
 * agent stopped following ([stoppedFollowingAgentIds], a thread) or muted the channel
 * ([mutedAgentIds]) and has done nothing with the message yet: working, replied (posted after it, or
 * in its thread: the message's own inline replies in a channel) or read (its position reached it) all
 * mean it already has it. A message that @mentions an agent that can read here flags no one: the
 * mention reaches it, and the agents it does not name are not its audience. Null too without a seq
 * (a send still on its way).
 */
internal fun projectThreadAgentReadStatus(
    messages: List<RaftThreadMessageItem>,
    messageId: String?,
    positions: Map<String, Long>,
    relevantAgentIds: List<String>,
    workingAgentIds: List<String>,
    stoppedFollowingAgentIds: Set<String> = emptySet(),
    mutedAgentIds: Set<String> = emptySet(),
    directory: Map<String, MessageMention> = emptyMap(),
): List<ThreadAgentReadEntry>? {
    if (messageId == null) return null
    if (stoppedFollowingAgentIds.isEmpty() && mutedAgentIds.isEmpty()) return null
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return null
    val own = messages[index]
    val seq = own.seq?.takeIf { it > 0 } ?: return null
    if (own.threadAgentMentionIds(directory).any { it in positions }) return null
    val replied = mutableSetOf<String>()
    own.inlineReplies?.replies?.forEach { if (it.senderType.equals("agent", ignoreCase = true)) replied += it.senderId }
    for (later in index + 1 until messages.size) {
        val message = messages[later]
        if (message.isAgentChat()) replied += message.authorId
    }
    val working = workingAgentIds.toSet()
    val entries =
        relevantAgentIds.mapNotNull { agentId ->
            when {
                agentId in working || agentId in replied || (positions[agentId] ?: 0L) >= seq -> null
                agentId in stoppedFollowingAgentIds -> ThreadAgentReadEntry(agentId, ThreadAgentReadState.StoppedFollowing)
                agentId in mutedAgentIds -> ThreadAgentReadEntry(agentId, ThreadAgentReadState.Muted)
                else -> null
            }
        }
    // sortedBy is stable: same-state agents keep the relevance order.
    return entries.takeIf { it.isNotEmpty() }?.sortedBy { it.state.ordinal }
}

/**
 * The conversation's faces and line, end to end ([ThreadPeerReadReceiptsUi] carries the scope, the
 * gate and names): null unless agent read progress is on here, outside a DM, and the scope hydrated
 * per-agent positions with at least one agent (web `useAgentReadProgress`).
 */
internal fun ThreadPeerReadReceiptsUi.threadAgentReadProgress(
    messages: List<RaftThreadMessageItem>,
    hasNewer: Boolean,
    workingAgentIds: List<String>,
): ThreadAgentReadProgressUi? {
    if (!agentReadProgressEnabled || dmSurface) return null
    val positions = threadAgentReadPositions(scope) ?: return null
    // A context window shows history, not the conversation's end: no faces and no line there.
    val relevant = if (hasNewer) emptyList() else threadRelevantReadAgentIds(messages, positions, workingAgentIds, directoryMentions)
    val working = workingAgentIds.toSet()
    val faces =
        placeThreadAgentReads(messages, positions, relevant, hasNewer).mapValues { (_, agentIds) ->
            agentIds.map { ThreadAgentReadFace(it, agentNames[it].orEmpty(), working = it in working) }
        }
    val messageId = threadOwnLatestMessageId(messages, hasNewer)
    val entries =
        projectThreadAgentReadStatus(
            messages = messages,
            messageId = messageId,
            positions = positions,
            relevantAgentIds = relevant,
            workingAgentIds = workingAgentIds,
            stoppedFollowingAgentIds = threadAgentsStoppedFollowing(scope),
            mutedAgentIds = threadAgentsMutedChannel(scope),
            directory = directoryMentions,
        )
    val status = if (messageId != null && entries != null) ThreadAgentReadStatusUi(messageId, entries, agentNames) else null
    return ThreadAgentReadProgressUi(serverId, faces, status)
}

/**
 * The line's words in the UI language (web `message.agentReadStatus.*`). Templates take `%1$s`
 * (names) or `%1$d` (a count); the list patterns are the locale's `Intl.ListFormat` conjunction
 * (`listTwo` for two names, `listStart` between all but the last two, `listEnd` before the last).
 * `agentCount` (`message.agentReadStatus.agentCount`, slock #13554) fills a verb's names slot with
 * the total ("Muted: 4 agents") on a width where not even one name fits.
 */
@Immutable
internal data class ThreadAgentReadStatusStrings(
    val stoppedFollowing: String,
    val muted: String,
    val stoppedFollowingHint: String,
    val more: String,
    val label: String,
    val listTwo: String,
    val listStart: String,
    val listEnd: String,
    val agentCountOne: String = "%1\$d",
    val agentCountOther: String = "%1\$d",
) {
    fun template(state: ThreadAgentReadState): String =
        when (state) {
            ThreadAgentReadState.StoppedFollowing -> stoppedFollowing
            ThreadAgentReadState.Muted -> muted
        }

    /**
     * "Muted: 4 agents": the segment when not one of its names fits, the verb with [agentCount] (the
     * plural the string layer resolved; one/other here) in its names slot, and no "+N".
     */
    fun count(
        state: ThreadAgentReadState,
        total: Int,
        agentCount: String = (if (total == 1) agentCountOne else agentCountOther).fillThreadAgentRead(total.toString()),
    ): String = template(state).fillThreadAgentRead(agentCount)
}

private val ThreadAgentReadPlaceholder = Regex("""%([12])\$[sd]""")

/** One pass, so a name that reads like a placeholder stays as written. */
internal fun String.fillThreadAgentRead(vararg args: String): String =
    ThreadAgentReadPlaceholder.replace(this) { match -> args.getOrElse(match.groupValues[1].toInt() - 1) { "" } }

/** `Intl.ListFormat` conjunction: "A", "A and B", "A, B, and C". */
internal fun ThreadAgentReadStatusStrings.formatList(names: List<String>): String =
    when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> listTwo.fillThreadAgentRead(names[0], names[1])
        else -> {
            var tail = listEnd.fillThreadAgentRead(names[names.size - 2], names[names.size - 1])
            for (index in names.size - 3 downTo 0) tail = listStart.fillThreadAgentRead(names[index], tail)
            tail
        }
    }

/** Web `openList`: "A, B, C", a list that goes on (no "and" before its last name). */
private fun ThreadAgentReadStatusStrings.openList(names: List<String>): String =
    names.drop(1).fold(names.firstOrNull().orEmpty()) { acc, name -> listStart.fillThreadAgentRead(acc, name) }

/** Web `AgentReadStatusRun`: one segment, a state's verb and the names that share it. */
@Immutable
internal data class ThreadAgentReadRun(
    val state: ThreadAgentReadState,
    /** The names spelled out, one list: "Niko and Ray", or "Niko, Ray" when a count follows. */
    val names: String,
    /** How many more past the cap ("+2"); 0 for none. */
    val more: Int,
    /** Every agent in the segment: the count when not one name fits. */
    val total: Int,
)

/**
 * Web `agentReadStatusRuns`: at most [maxNames] names; the agents in one state share their verb, and
 * entries come sorted, so stopped following comes first. Past the cap the rest stay counted in their
 * own segment ("Stopped following: Niko, Ray, Cindy +2"; each segment keeps at least one name).
 */
internal fun threadAgentReadStatusRuns(
    entries: List<ThreadAgentReadEntry>,
    nameOf: (String) -> String,
    strings: ThreadAgentReadStatusStrings,
    maxNames: Int = ThreadAgentReadStatusMaxNames,
): List<ThreadAgentReadRun> {
    val groups = mutableListOf<Pair<ThreadAgentReadState, MutableList<String>>>()
    for (entry in entries) {
        val last = groups.lastOrNull()
        if (last?.first == entry.state) last.second += entry.agentId else groups += entry.state to mutableListOf(entry.agentId)
    }
    // One name kept back for each run still to come, so none is left out.
    var runsAhead = groups.size
    var budget = maxNames
    return groups.map { (state, agentIds) ->
        runsAhead -= 1
        val take = maxOf(1, minOf(agentIds.size, budget - runsAhead))
        val names = agentIds.take(take).map(nameOf)
        val more = agentIds.size - take
        budget -= take
        ThreadAgentReadRun(state, if (more > 0) strings.openList(names) else strings.formatList(names), more, agentIds.size)
    }
}

/**
 * Web `formatAgentReadStatus`: the line's words as one string, "Stopped following: Niko, Ray ·
 * Muted: Cindy": what a tap shows and, with [reachHint] after the last segment, the screen-reader
 * sentence.
 */
internal fun formatThreadAgentReadStatus(
    entries: List<ThreadAgentReadEntry>,
    nameOf: (String) -> String,
    strings: ThreadAgentReadStatusStrings,
    maxNames: Int = ThreadAgentReadStatusMaxNames,
    reachHint: String? = null,
): String {
    if (entries.isEmpty()) return ""
    val segments =
        threadAgentReadStatusRuns(entries, nameOf, strings, maxNames).map { run ->
            val listed = if (run.more > 0) "${run.names} ${strings.more.fillThreadAgentRead(run.more.toString())}" else run.names
            strings.template(run.state).fillThreadAgentRead(listed)
        }.toMutableList()
    if (reachHint != null) segments += reachHint
    return segments.joinToString(" · ")
}

/** The line's one icon, for its first segment (web: Unfollow for a thread, the mute bell for a channel). */
internal fun ThreadAgentReadStatusUi.iconName(): RaftLucideIconName =
    if (entries.firstOrNull()?.state == ThreadAgentReadState.Muted) RaftLucideIconName.BellOff else RaftLucideIconName.UnfollowThread

@Composable
internal fun threadAgentReadStatusStrings(): ThreadAgentReadStatusStrings =
    ThreadAgentReadStatusStrings(
        stoppedFollowing = stringResource(Res.string.thread_agent_read_status_stopped_following),
        muted = stringResource(Res.string.thread_agent_read_status_muted),
        stoppedFollowingHint = stringResource(Res.string.thread_agent_read_status_stopped_following_hint),
        more = stringResource(Res.string.thread_agent_read_status_more),
        label = stringResource(Res.string.thread_agent_read_status_label),
        listTwo = stringResource(Res.string.thread_agent_read_status_list_two),
        listStart = stringResource(Res.string.thread_agent_read_status_list_start),
        listEnd = stringResource(Res.string.thread_agent_read_status_list_end),
        agentCountOne = stringResource(Res.string.thread_agent_read_status_agent_count_one),
        agentCountOther = stringResource(Res.string.thread_agent_read_status_agent_count_other),
    )

/** One segment's widths (px) for [planThreadAgentReadLine]: its parts that never shrink, and its names. */
internal data class ThreadAgentReadSegmentWidths(
    /** The verb (with the catalog's own punctuation and space) and anything after the names. */
    val verb: Float,
    /** The names at their own width. */
    val names: Float,
    /** The least room that still shows a name: its first letter and the ellipsis. */
    val minNames: Float,
    /** " +N", or 0 for none. */
    val more: Float,
    /** The whole segment written as a count ("Muted: 4 agents"). */
    val count: Float,
)

/** How the line fits: each segment's names width (null: written as its count), and the hint's width. */
internal data class ThreadAgentReadLinePlan(val names: List<Float?>, val hint: Float)

/**
 * The narrow-width rule (web slock #13554, Remy; Kirby's review). Widths are whole pixels. [lead] is the icon and its gap,
 * [separator] one " · " (between segments and before the hint). Every verb, "+N", "·" and the icon
 * keep their width. The names take only the room left over, shared evenly up to each one's own
 * width, and take the ellipsis first. A segment whose share no longer shows even one letter of a
 * name is written as its count instead ("Muted: 4 agents", never a bare "+N"). Only once every name
 * is gone does the hint lose its tail. With room, nothing changes.
 */
internal fun planThreadAgentReadLine(
    available: Float,
    lead: Float,
    separator: Float,
    segments: List<ThreadAgentReadSegmentWidths>,
    hint: Float,
): ThreadAgentReadLinePlan {
    val counted = BooleanArray(segments.size)
    while (true) {
        val fixed =
            lead + separator * segments.size +
                segments.indices.sumOf { (if (counted[it]) segments[it].count else segments[it].verb + segments[it].more).toDouble() }.toFloat()
        val open = segments.indices.filter { !counted[it] }
        val natural = open.sumOf { segments[it].names.toDouble() }.toFloat()
        if (fixed + natural + hint <= available) {
            return ThreadAgentReadLinePlan(segments.indices.map { if (counted[it]) null else segments[it].names }, hint)
        }
        if (open.isEmpty()) return ThreadAgentReadLinePlan(segments.map { null }, kotlin.math.floor(available - fixed).coerceIn(0f, hint))
        // Share what is left evenly, each up to its own width (web: basis-0 grow max-w-max).
        val shares = FloatArray(segments.size)
        var left = (available - fixed - hint).coerceAtLeast(0f)
        var waiting = open.sortedBy { segments[it].names }
        while (waiting.isNotEmpty()) {
            val even = kotlin.math.floor(left / waiting.size)
            val first = waiting.first()
            if (segments[first].names <= even) {
                shares[first] = segments[first].names
                left -= segments[first].names
                waiting = waiting.drop(1)
            } else {
                waiting.forEach { shares[it] = even }
                waiting = emptyList()
            }
        }
        // The narrowest share that no longer shows a letter goes first; then look again.
        val gone = open.filter { shares[it] < segments[it].minNames }.minByOrNull { shares[it] }
        if (gone == null) return ThreadAgentReadLinePlan(segments.indices.map { if (counted[it]) null else shares[it] }, hint)
        counted[gone] = true
    }
}

internal const val ThreadAgentReadStatusTestTag = "agent-read-status"
internal const val ThreadAgentReadStatusTextTestTag = "agent-read-status-text"
internal const val ThreadAgentReadStatusNamesTestTag = "agent-read-status-names"
internal const val ThreadAgentReadStatusHintTestTag = "agent-read-status-hint"

private const val ThreadAgentReadSeparator = " · "

/**
 * Web `MessageReadStatusLine` with `truncate` and a `hint` (artin, option C; Jasper, raft-design
 * #1061; Remy, slock #13554): its own right-aligned line under the message, below everything the
 * message owns; a 12dp icon, the segments, then "·" and the hint, one caption in foreground-hint on
 * a 20dp line, laid out by [planThreadAgentReadLine] from the line's own measured width (only the
 * message that shows the line measures). A tap shows every name, wrapped, without the hint (the web's
 * tooltip), and a second tap folds it; a screen reader hears the whole sentence once.
 */
@Composable
internal fun ThreadAgentReadStatusRow(status: ThreadAgentReadStatusUi, modifier: Modifier = Modifier) {
    val strings = threadAgentReadStatusStrings()
    val fallbackName = stringResource(Res.string.thread_agent_typing_agent_fallback)
    val nameOf: (String) -> String = { id -> status.names[id]?.takeIf { it.isNotBlank() } ?: fallbackName }
    val runs = remember(status, strings, fallbackName) { threadAgentReadStatusRuns(status.entries, nameOf, strings) }
    val allNames = remember(status, strings, fallbackName) { formatThreadAgentReadStatus(status.entries, nameOf, strings, status.entries.size) }
    // Web `aria-label`: every name, then the hint, read once.
    val label =
        remember(status, strings, fallbackName) {
            strings.label.fillThreadAgentRead(
                formatThreadAgentReadStatus(status.entries, nameOf, strings, status.entries.size, strings.stoppedFollowingHint),
            )
        }
    var expanded by rememberSaveable(status.messageId) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val style = RaftType.Caption
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .testTag(ThreadAgentReadStatusTestTag)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.CenterEnd,
    ) {
        val icon: @Composable () -> Unit = {
            Box(Modifier.size(12.dp)) {
                RaftLucideIcon(status.iconName(), Modifier.size(12.dp), color = raftArgbHex(RaftColor.ForegroundHint))
            }
        }
        val caption: @Composable (String, Modifier) -> Unit = { text, textModifier ->
            Text(text, modifier = textModifier, style = style, color = RaftColor.ForegroundHint, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp)) { icon() }
                // Every name, no hint (web tooltip: the hint is on the line already).
                Text(
                    allNames,
                    modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false).testTag(ThreadAgentReadStatusTextTestTag),
                    style = style,
                    color = RaftColor.ForegroundHint,
                    textAlign = TextAlign.End,
                )
            }
            return@BoxWithConstraints
        }
        fun width(text: String): Float = if (text.isEmpty()) 0f else measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toFloat()
        // The plural through the string layer (ru: one / few / many).
        val agentCounts =
            runs.map { run ->
                stringResource(
                    if (run.total == 1) Res.string.thread_agent_read_status_agent_count_one else Res.string.thread_agent_read_status_agent_count_other,
                    run.total,
                )
            }
        val parts =
            remember(runs, strings, agentCounts, style, density) {
                runs.mapIndexed { index, run ->
                    val template = strings.template(run.state)
                    val slot = ThreadAgentReadPlaceholder.find(template)
                    val before = if (slot == null) template else template.substring(0, slot.range.first)
                    val after = if (slot == null) "" else template.substring(slot.range.last + 1)
                    val more = if (run.more > 0) " ${strings.more.fillThreadAgentRead(run.more.toString())}" else ""
                    val count = strings.count(run.state, run.total, agentCounts[index])
                    Triple(Triple(before, after, more), count, ThreadAgentReadSegmentWidths(
                        verb = width(before) + width(after),
                        names = width(run.names),
                        minNames = width(run.names.take(1) + "…"),
                        more = width(more),
                        count = width(count),
                    ))
                }
            }
        val separatorWidth = remember(style, density) { width(ThreadAgentReadSeparator) }
        val hintWidth = remember(strings, style, density) { width(strings.stoppedFollowingHint) }
        val plan =
            planThreadAgentReadLine(
                available = constraints.maxWidth.toFloat(),
                lead = with(density) { 16.dp.toPx() },
                separator = separatorWidth,
                segments = parts.map { it.third },
                hint = hintWidth,
            )
        fun Float.toDpCeil() = with(density) { kotlin.math.ceil(this@toDpCeil).toDp() }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 4.dp)) { icon() }
            runs.forEachIndexed { index, run ->
                if (index > 0) caption(ThreadAgentReadSeparator, Modifier)
                val (texts, count, _) = parts[index]
                val (before, after, more) = texts
                val names = plan.names[index]
                if (names == null) {
                    caption(count, Modifier)
                } else {
                    caption(before, Modifier)
                    if (names > 0f) caption(run.names, Modifier.width(names.toDpCeil()).testTag(ThreadAgentReadStatusNamesTestTag))
                    if (after.isNotEmpty()) caption(after, Modifier)
                    if (more.isNotEmpty()) caption(more, Modifier)
                }
            }
            caption(ThreadAgentReadSeparator, Modifier)
            caption(strings.stoppedFollowingHint, Modifier.width(plan.hint.toDpCeil()).testTag(ThreadAgentReadStatusHintTestTag))
        }
    }
}

internal const val ThreadAgentReadFacesTestTag = "agent-read-faces"
internal const val ThreadAgentReadFacesMoreTestTag = "agent-read-faces-more"
internal const val ThreadAgentReadFacesDetailsTestTag = "agent-read-faces-details"

/** Faces shown per message at a given width: web `maxFaces` (2 below 768dp, else 3). */
internal fun threadAgentReadMaxFaces(width: Dp): Int =
    if (width < ThreadAgentReadWideMinWidth) ThreadAgentReadMaxFacesNarrow else ThreadAgentReadMaxFaces

/**
 * Web `AgentReadFaceGroup`: at the end of the message the agents read up to, the read mark (the DM
 * "Read" line's double check, in its hint colour) and their 19dp faces in a raft-ui AvatarGroup, each
 * lying 5dp over the one before; past [threadAgentReadMaxFaces], "+N". A working agent's face carries
 * the busy light. Mobile has no hover: a tap shows who read up to here, every name (the faces' and
 * the "+N"'s tooltips), and a second tap folds it.
 */
@Composable
internal fun ThreadAgentReadFacesRow(
    faces: List<ThreadAgentReadFace>,
    serverId: String?,
    modifier: Modifier = Modifier,
) {
    if (faces.isEmpty()) return
    val strings = threadAgentReadStatusStrings()
    val fallbackName = stringResource(Res.string.thread_agent_typing_agent_fallback)
    val groupLabelTemplate = stringResource(Res.string.thread_agent_read_faces_group_label)
    val names = faces.map { it.name.ifBlank { fallbackName } }
    val label = remember(names, strings, groupLabelTemplate) { groupLabelTemplate.fillThreadAgentRead(strings.formatList(names)) }
    var expanded by rememberSaveable(faces.map { it.agentId }) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxFaces = threadAgentReadMaxFaces(maxWidth)
        val shown = faces.take(maxFaces)
        val rest = faces.size - shown.size
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Row(
                modifier =
                    Modifier
                        .heightIn(min = 20.dp)
                        .testTag(ThreadAgentReadFacesTestTag)
                        .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
                        .clearAndSetSemantics {
                            contentDescription = label
                            role = Role.Button
                        },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp)) {
                    RaftLucideIcon(RaftLucideIconName.CheckCheck, Modifier.size(12.dp), color = raftArgbHex(RaftColor.ForegroundHint))
                }
                RaftAvatarGroup {
                    shown.forEachIndexed { index, face ->
                        RaftAvatarWithStatus(
                            statusColor = if (face.working) RaftColor.PresenceBusy else null,
                            avatarSize = 19.dp,
                            modifier = Modifier.testTag("$ThreadAgentReadFacesTestTag-${face.agentId}"),
                        ) {
                            RaftMemberAvatar(
                                identity = RaftMemberAvatarIdentity(MemberKind.Agent, face.agentId, serverId),
                                label = names[index].toRaftAvatarLabel(),
                                avatarSize = 19.dp,
                            )
                        }
                    }
                    if (rest > 0) {
                        RaftAvatarGroupCount(
                            text = strings.more.fillThreadAgentRead(rest.toString()),
                            size = RaftAvatarSize.Xxs,
                            modifier = Modifier.testTag(ThreadAgentReadFacesMoreTestTag),
                        )
                    }
                }
            }
            if (expanded) {
                Text(
                    label,
                    modifier = Modifier.padding(top = 2.dp).testTag(ThreadAgentReadFacesDetailsTestTag),
                    style = RaftType.Caption,
                    color = RaftColor.ForegroundHint,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
