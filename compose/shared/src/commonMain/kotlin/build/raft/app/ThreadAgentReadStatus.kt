package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.model.MessageMention
import build.raft.app.model.ReadReceiptPeerKind

/**
 * The agents' read status line under the viewer's latest reply in a thread, as the web draws it
 * (botiverse/slock staging ecc7b14, `packages/web/src/store/agentReadProgressDomain.ts` "B",
 * `components/message/AgentReadFaces.tsx` `AgentReadStatusLine`, `hooks/useAgentReadProgress.ts`):
 *  - Only agents, and only the relevant ones: each agent the reply @mentioned (that has a read
 *    position here) or, mentioning none, those that spoke or were @mentioned in the thread's last
 *    [ThreadAgentReadRelevanceWindow] messages plus those working here now.
 *  - Each is working (typing here now), else replied (posted after the reply), else read (its read
 *    position reached the reply), else "not yet" — unless it stopped following the thread
 *    (`stoppedFollowing` on the detail's `peerReadStates`, slock #13497) and the reply @mentioned no
 *    one it counts: then the reply never reaches it, and the line says "Stopped following" instead. A
 *    mention reaches it (the send reactivates the follow), so a mentioned agent counts as usual.
 *  - Sorted stopped following (the one state that asks the reader to act), replied, working, read,
 *    not yet; at most [ThreadAgentReadStatusMaxNames] names, then "+N", and stopped-following names
 *    are never folded into the line's +N.
 *  - One line, one icon: the unfollow icon when anyone stopped following, else the double check once
 *    everyone has at least read it, else the single check.
 *
 * Mobile has no hover: a tap on the line shows what the web's tooltip says (every name and "Mention
 * them to reach them" when someone stopped following; otherwise the line itself, untruncated).
 */
internal const val ThreadAgentReadRelevanceWindow = 30

internal const val ThreadAgentReadStatusMaxNames = 3

/** Web `AgentReadStatusState`; the declaration order is the line's display order (`STATE_ORDER`). */
internal enum class ThreadAgentReadState { StoppedFollowing, Replied, Working, Read, Unread }

@Immutable
internal data class ThreadAgentReadEntry(val agentId: String, val state: ThreadAgentReadState)

/** The line for one message: its entries in display order and every name it may spell out. */
@Immutable
internal data class ThreadAgentReadStatusUi(
    val messageId: String,
    val entries: List<ThreadAgentReadEntry>,
    val names: Map<String, String>,
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
 * Web `ownLatestMessageId`: the viewer's own latest message among the last [window] loaded ones, only
 * at the live edge ([hasNewer] false: a context window's last row is not the thread's last).
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
 * Web `projectAgentReadStatus`: the line's entries for [messageId], sorted for display, or null when
 * there is no one to count or the message has no seq yet (a send still on its way).
 */
internal fun projectThreadAgentReadStatus(
    messages: List<RaftThreadMessageItem>,
    messageId: String?,
    positions: Map<String, Long>,
    relevantAgentIds: List<String>,
    workingAgentIds: List<String>,
    stoppedFollowingAgentIds: Set<String> = emptySet(),
    directory: Map<String, MessageMention> = emptyMap(),
): List<ThreadAgentReadEntry>? {
    if (messageId == null) return null
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return null
    val own = messages[index]
    val seq = own.seq?.takeIf { it > 0 } ?: return null
    val mentioned = own.threadAgentMentionIds(directory).filter { it in positions }
    val targets = mentioned.ifEmpty { relevantAgentIds }
    if (targets.isEmpty()) return null
    val replied = mutableSetOf<String>()
    for (later in index + 1 until messages.size) {
        val message = messages[later]
        if (message.isAgentChat()) replied += message.authorId
    }
    val working = workingAgentIds.toSet()
    return targets
        .map { agentId ->
            val state =
                when {
                    agentId in working -> ThreadAgentReadState.Working
                    agentId in replied -> ThreadAgentReadState.Replied
                    (positions[agentId] ?: 0L) >= seq -> ThreadAgentReadState.Read
                    mentioned.isEmpty() && agentId in stoppedFollowingAgentIds -> ThreadAgentReadState.StoppedFollowing
                    else -> ThreadAgentReadState.Unread
                }
            ThreadAgentReadEntry(agentId, state)
        }
        // sortedBy is stable: same-state agents keep the target order.
        .sortedBy { it.state.ordinal }
}

/**
 * The thread's line, end to end ([ThreadPeerReadReceiptsUi] carries the scope, the gate and names):
 * null unless agent read progress is on, this is a reply thread outside a DM, and the scope hydrated
 * per-agent positions with at least one agent.
 */
internal fun ThreadPeerReadReceiptsUi.threadAgentReadStatus(
    messages: List<RaftThreadMessageItem>,
    hasNewer: Boolean,
    workingAgentIds: List<String>,
): ThreadAgentReadStatusUi? {
    if (!agentReadStatusEnabled || dmSurface) return null
    val positions = threadAgentReadPositions(scope) ?: return null
    val relevant = if (hasNewer) emptyList() else threadRelevantReadAgentIds(messages, positions, workingAgentIds, directoryMentions)
    val messageId = threadOwnLatestMessageId(messages, hasNewer) ?: return null
    val entries =
        projectThreadAgentReadStatus(
            messages = messages,
            messageId = messageId,
            positions = positions,
            relevantAgentIds = relevant,
            workingAgentIds = workingAgentIds,
            stoppedFollowingAgentIds = threadAgentsStoppedFollowing(scope),
            directory = directoryMentions,
        ) ?: return null
    return ThreadAgentReadStatusUi(messageId, entries, agentNames)
}

/**
 * The line's words in the UI language (web `message.agentReadStatus.*`). Templates take `%1$s`
 * (names) or `%1$d` (a count); the list patterns are the locale's `Intl.ListFormat` conjunction
 * (`listTwo` for two names, `listStart` between all but the last two, `listEnd` before the last).
 */
@Immutable
internal data class ThreadAgentReadStatusStrings(
    val replied: String,
    val working: String,
    val read: String,
    val unread: String,
    val stoppedFollowing: String,
    val stoppedFollowingHint: String,
    val allReplied: String,
    val noneRead: String,
    val more: String,
    val label: String,
    val listTwo: String,
    val listStart: String,
    val listEnd: String,
) {
    fun template(state: ThreadAgentReadState): String =
        when (state) {
            ThreadAgentReadState.StoppedFollowing -> stoppedFollowing
            ThreadAgentReadState.Replied -> replied
            ThreadAgentReadState.Working -> working
            ThreadAgentReadState.Read -> read
            ThreadAgentReadState.Unread -> unread
        }
}

private val ThreadAgentReadPlaceholder = Regex("""%([12])\$[sd]""")

/** One pass, so a name that reads like a placeholder stays as written. */
private fun String.fillThreadAgentRead(vararg args: String): String =
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

/**
 * Web `formatAgentReadStatus`: "Niko replied · Tenny working · Cindy read · +2". Consecutive agents in
 * one state share their verb; everyone replied or no one read yet (with more than one agent) say so
 * in one phrase. Stopped-following agents past the cap stay counted in their own segment ("Stopped
 * following: Niko, Ray, Cindy +2"), so the line's +N is never one of them; [stoppedFollowingHint]
 * follows that segment.
 */
internal fun formatThreadAgentReadStatus(
    entries: List<ThreadAgentReadEntry>,
    nameOf: (String) -> String,
    strings: ThreadAgentReadStatusStrings,
    maxNames: Int = ThreadAgentReadStatusMaxNames,
    stoppedFollowingHint: String? = null,
): String {
    if (entries.isEmpty()) return ""
    if (entries.size > 1 && entries.all { it.state == ThreadAgentReadState.Replied }) return strings.allReplied
    if (entries.size > 1 && entries.all { it.state == ThreadAgentReadState.Unread }) return strings.noneRead
    val shown = entries.take(maxNames)
    val foldedStopped = entries.drop(maxNames).count { it.state == ThreadAgentReadState.StoppedFollowing }
    val segments = mutableListOf<String>()
    var start = 0
    while (start < shown.size) {
        val state = shown[start].state
        var end = start + 1
        while (end < shown.size && shown[end].state == state) end += 1
        val names = shown.subList(start, end).map { nameOf(it.agentId) }
        val stopped = state == ThreadAgentReadState.StoppedFollowing
        val listed =
            if (stopped && foldedStopped > 0) {
                "${strings.openList(names)} ${strings.more.fillThreadAgentRead(foldedStopped.toString())}"
            } else {
                strings.formatList(names)
            }
        segments += strings.template(state).fillThreadAgentRead(listed)
        if (stopped && stoppedFollowingHint != null) segments += stoppedFollowingHint
        start = end
    }
    val rest = entries.size - shown.size - foldedStopped
    if (rest > 0) segments += strings.more.fillThreadAgentRead(rest.toString())
    return segments.joinToString(" · ")
}

/** The line's one icon (web `AgentReadStatusLine`). */
internal fun ThreadAgentReadStatusUi.iconName(): RaftLucideIconName =
    when {
        entries.any { it.state == ThreadAgentReadState.StoppedFollowing } -> RaftLucideIconName.UnfollowThread
        entries.none { it.state == ThreadAgentReadState.Unread } -> RaftLucideIconName.CheckCheck
        else -> RaftLucideIconName.Check
    }

@Composable
internal fun threadAgentReadStatusStrings(): ThreadAgentReadStatusStrings =
    ThreadAgentReadStatusStrings(
        replied = stringResource(Res.string.thread_agent_read_status_replied),
        working = stringResource(Res.string.thread_agent_read_status_working),
        read = stringResource(Res.string.thread_agent_read_status_read),
        unread = stringResource(Res.string.thread_agent_read_status_unread),
        stoppedFollowing = stringResource(Res.string.thread_agent_read_status_stopped_following),
        stoppedFollowingHint = stringResource(Res.string.thread_agent_read_status_stopped_following_hint),
        allReplied = stringResource(Res.string.thread_agent_read_status_all_replied),
        noneRead = stringResource(Res.string.thread_agent_read_status_none_read),
        more = stringResource(Res.string.thread_agent_read_status_more),
        label = stringResource(Res.string.thread_agent_read_status_label),
        listTwo = stringResource(Res.string.thread_agent_read_status_list_two),
        listStart = stringResource(Res.string.thread_agent_read_status_list_start),
        listEnd = stringResource(Res.string.thread_agent_read_status_list_end),
    )

internal const val ThreadAgentReadStatusTestTag = "agent-read-status"

/**
 * Web `MessageReadStatusLine` with `truncate` (artin, option C): its own right-aligned line under the
 * message, below everything the message owns; a 12dp icon and one caption in foreground-hint on a
 * 20dp line, ending in an ellipsis when long (the verb leads the stopped-following segment, so only
 * names are cut). A tap shows the web's tooltip text in full, wrapped, and a second tap folds it.
 */
@Composable
internal fun ThreadAgentReadStatusRow(status: ThreadAgentReadStatusUi, modifier: Modifier = Modifier) {
    val strings = threadAgentReadStatusStrings()
    val fallbackName = stringResource(Res.string.thread_agent_typing_agent_fallback)
    val nameOf: (String) -> String = { id -> status.names[id]?.takeIf { it.isNotBlank() } ?: fallbackName }
    val text = remember(status, strings, fallbackName) { formatThreadAgentReadStatus(status.entries, nameOf, strings) }
    val stoppedFollowing = status.entries.any { it.state == ThreadAgentReadState.StoppedFollowing }
    val explained =
        remember(status, strings, fallbackName, stoppedFollowing) {
            if (stoppedFollowing) {
                formatThreadAgentReadStatus(status.entries, nameOf, strings, status.entries.size, strings.stoppedFollowingHint)
            } else {
                null
            }
        }
    val details = explained ?: text
    val label = strings.label.fillThreadAgentRead(details)
    var expanded by rememberSaveable(status.messageId) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .testTag(ThreadAgentReadStatusTestTag)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded }
                // Web `aria-label`: "Agents on this message: …", with every name and the hint.
                .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(top = if (expanded) 2.dp else 0.dp).size(12.dp)) {
            RaftLucideIcon(status.iconName(), Modifier.size(12.dp), color = raftArgbHex(RaftColor.ForegroundHint))
        }
        Box(Modifier.padding(start = 4.dp).weight(1f, fill = false)) {
            Text(
                if (expanded) details else text,
                style = RaftType.Caption,
                color = RaftColor.ForegroundHint,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}
