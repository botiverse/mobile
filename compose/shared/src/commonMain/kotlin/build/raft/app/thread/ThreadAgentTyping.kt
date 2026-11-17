package build.raft.app

import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentTypingSentenceTemplates
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.formatAgentTypingSentence
import build.raft.app.data.typingAgentIds
import build.raft.app.model.AgentStatus
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Agent typing line (raft-design 0042, `working-agents.md` "Who is about to post"; web
 * `components/message/AgentTypingLine.tsx`): up to three still 19dp faces and one muted sentence,
 * floating over the bottom of the conversation just above the composer. Agents only. Nothing moves:
 * no dots, no shimmer, no entrance.
 */

/** One agent about to post here, named from the directory. */
@Immutable
internal data class ThreadTypingAgent(
    val agentId: String,
    val name: String,
    /** The face the page already knows for the agent; [RaftMemberAvatar] still reads the directory first. */
    val avatarWireValue: String?,
)

/**
 * The page's line above the composer: who is typing ([agents], in the order they started) and, in a
 * channel, who of its agents is working ([working], in the order they started). Never both empty.
 *
 * [workingNow] is set in a channel only (not a DM, a Lounge or a thread; web `workingAgentIdKey`):
 * there the line says who is working here, typing included, and opens Working now. Elsewhere the
 * line is today's typing line.
 */
@Immutable
internal data class ThreadAgentTypingUi(
    val serverId: String,
    val agents: List<ThreadTypingAgent>,
    val working: List<RaftLiveAgentActivityItem> = emptyList(),
    val workingNow: RaftWorkingNowSource? = null,
)

/** What the line's sentence says (web AgentTypingLine, #13551). */
internal sealed interface ThreadWorkingLineSentence {
    /** The typing sentence alone: outside a channel, or everyone working here is typing. */
    data object Typing : ThreadWorkingLineSentence

    /** Nobody types: "3 agents working here". */
    data class WorkingHere(val count: Int) : ThreadWorkingLineSentence

    /** "Kirby is typing… · 2 others working": [others] work here and are not typing. */
    data class TypingAndOthers(val others: Int) : ThreadWorkingLineSentence
}

/**
 * The line in a channel (web AgentTypingLine with `workingAgentIdKey`): [faces] are the working
 * agents in the order they started, never reordered by typing, then any typer not reported working
 * (the stack draws the first [RaftWorkingNowMaxFaces]); Working now lists the same [faces], where an
 * agent in [typingIds] reads "Typing…".
 */
@Immutable
internal data class ThreadWorkingLine(
    val faces: List<RaftLiveAgentActivityItem>,
    val typingIds: Set<String>,
    val sentence: ThreadWorkingLineSentence,
)

/** Typing is one kind of working (stdrc, web #13551): how typing and working combine on the line. */
internal fun threadWorkingLine(typing: List<ThreadTypingAgent>, working: List<RaftLiveAgentActivityItem>): ThreadWorkingLine {
    val typingIds = typing.mapTo(LinkedHashSet()) { it.agentId }
    val workingIds = working.mapTo(HashSet()) { it.agentId }
    val faces = working + typing.filter { it.agentId !in workingIds }.map { it.toWorkingNowRow() }
    val others = working.count { it.agentId !in typingIds }
    val sentence =
        when {
            typing.isNotEmpty() && others == 0 -> ThreadWorkingLineSentence.Typing
            typing.isEmpty() -> ThreadWorkingLineSentence.WorkingHere(faces.size)
            else -> ThreadWorkingLineSentence.TypingAndOthers(others)
        }
    return ThreadWorkingLine(faces, typingIds, sentence)
}

/** Working now's row for an agent about to post that live activity has not reported working (web `typerRow`). */
private fun ThreadTypingAgent.toWorkingNowRow(): RaftLiveAgentActivityItem =
    RaftLiveAgentActivityItem(agentId = agentId, name = name, avatarWireValue = avatarWireValue, status = AgentStatus.Working, startedAt = 0L)

/** The sentence [line] says: [typingSentence] (web `formatAgentTypingSentence`) and the two working templates. */
internal fun formatThreadWorkingLineSentence(
    line: ThreadWorkingLine,
    typingSentence: String,
    workingHere: (count: Int) -> String,
    typingAndOthers: (typing: String, others: Int) -> String,
): String =
    when (val sentence = line.sentence) {
        ThreadWorkingLineSentence.Typing -> typingSentence
        is ThreadWorkingLineSentence.WorkingHere -> workingHere(sentence.count)
        is ThreadWorkingLineSentence.TypingAndOthers -> typingAndOthers(typingSentence, sentence.others)
    }

/** The agents of a channel's roster that live activity lists as working, in the order they started. */
internal fun List<RaftLiveAgentActivityItem>.workingAmong(agentIds: Set<String>): List<RaftLiveAgentActivityItem> =
    if (agentIds.isEmpty()) emptyList() else filter { it.agentId in agentIds }

/**
 * Whether the page is a channel whose line says who works there (web ChatPanel `workingAgentIdKey`:
 * not a DM, a thread or a Lounge). A task conversation renders on the channel surface but is a
 * sub-conversation the web shows in its ThreadPanel, which never says who works there: only who types.
 */
internal fun RaftThreadScreenPayload.showsChannelWorkingLine(isLounge: Boolean): Boolean =
    surfaceKind == RaftThreadSurfaceKind.Channel && !isLounge &&
        parseConversationKindOrNull(channelKind) !in NoChannelWorkingLineKinds

private val NoChannelWorkingLineKinds =
    setOf(ConversationKind.Task, ConversationKind.Thread, ConversationKind.DirectMessage)

internal val LocalThreadAgentTyping = compositionLocalOf<ThreadAgentTypingUi?> { null }

/**
 * The conversation an `agent:typing` names for this page: a reply thread is its parent channel and
 * root message (web ThreadPanel `typingChannelId` + `parentMessageId`); a channel or DM is itself.
 */
internal fun RaftThreadScreenPayload.agentTypingConversation(): Pair<String, String?>? {
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val channel = parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val root = parentMessageId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return channel to root
    }
    val channel =
        streamChannelId?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
    return channel to null
}

/**
 * Names the typing agents. Only agents: an id the directory knows as anyone else is dropped. An id
 * it doesn't know yet still shows, as [fallbackName] (web `activity.live.agentFallback`).
 *
 * Faces resolve like message rows ([raftMemberAvatarResolver]): a server member's rendered avatar
 * (pixel face or image), else [durableAvatar], the last avatar persisted for the id while the
 * directory has none.
 */
internal fun resolveThreadTypingAgents(
    agentIds: List<String>,
    serverMembers: List<MemberProfile>,
    channelMembers: List<RaftThreadMemberItem>,
    fallbackName: String,
    durableAvatar: (String) -> String? = { null },
): List<ThreadTypingAgent> =
    agentIds.mapNotNull { id ->
        val server = serverMembers.firstOrNull { it.id.value == id }
        if (server != null) {
            if (server.kind != MemberKind.Agent) return@mapNotNull null
            val name = server.displayName.ifBlank { server.handle }.ifBlank { fallbackName }
            return@mapNotNull ThreadTypingAgent(id, name, server.avatarWireValueForRenderedAvatar() ?: durableAvatar(id))
        }
        val channel = channelMembers.firstOrNull { it.id == id }
        if (channel != null) {
            if (!channel.kind.equals("agent", ignoreCase = true)) return@mapNotNull null
            val name = channel.displayName.ifBlank { channel.handle }.ifBlank { fallbackName }
            return@mapNotNull ThreadTypingAgent(id, name, channel.avatarUrl?.takeIf { it.isNotBlank() } ?: durableAvatar(id))
        }
        ThreadTypingAgent(id, fallbackName, durableAvatar(id))
    }

/**
 * Binds the page to the shell's agent typing store: the page's server is the store's (a switch
 * starts over), and the line lists who is about to post into this page's conversation. In a
 * channel ([RaftThreadScreenPayload.showsChannelWorkingLine]) it also lists the channel's agents
 * that live activity has at work ([SharedLiveAgentActivityStore], fed by `agent:activity` and
 * `agent:activity_state`), while Settings › Appearance › Live agent activity is on (web
 * `showLiveAgentActivityBar`), and [openAgent] is where a Working now row goes. Null while nobody
 * types or works here, or while the page shows no composer (web renders the line with the composer
 * only).
 */
@Composable
internal fun rememberThreadAgentTyping(
    payload: RaftThreadScreenPayload,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    serverMembersStore: SharedServerMembersStore?,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    openAgent: (agentId: String) -> Unit = {},
): ThreadAgentTypingUi? {
    val store = remember(sharedStores, storeScope) { sharedStores.agentTypingStore(storeScope) }
    val server = serverId?.trim()?.takeIf { it.isNotBlank() }
    LaunchedEffect(store, server) { store.bindServer(server) }
    val state by store.state.collectAsState()
    val conversation = payload.agentTypingConversation()
    val agentIds =
        remember(state, server, conversation) {
            state.typingAgentIds(server, conversation?.first, conversation?.second)
        }
    // A channel's agents at work (web useChannelWorkingAgents), with the Live agent activity setting.
    val lounges by RaftAgentLounges.lounges.collectAsState()
    val inChannel =
        server != null && conversation != null &&
            payload.showsChannelWorkingLine(isLounge = lounges[server]?.containsKey(conversation.first) == true)
    val workingSource =
        if (inChannel && server != null && RaftDisplayPreferenceRuntime.showLiveAgentActivity) {
            remember(sharedStores, storeScope, server) { sharedStores.liveAgentActivity(storeScope).itemsFlow(server) }
        } else {
            null
        }
    val liveItems = workingSource?.collectAsState()?.value.orEmpty()
    val channelAgentIds =
        remember(payload.members) {
            payload.members.filter { it.kind.equals("agent", ignoreCase = true) }.mapTo(HashSet()) { it.id }
        }
    val working = remember(liveItems, channelAgentIds) { liveItems.workingAmong(channelAgentIds) }
    val latestOpenAgent by rememberUpdatedState(openAgent)
    val workingNow =
        if (inChannel && server != null) {
            remember(server, workingSource, agentStatusResolver, sharedStores) {
                RaftWorkingNowSource(
                    serverId = server,
                    items = workingSource ?: MutableStateFlow(emptyList()),
                    agentStatusResolver = agentStatusResolver,
                    subscriptions = sharedStores.agentActivitySubscriptions,
                    onOpenAgent = { agentId -> latestOpenAgent(agentId) },
                )
            }
        } else {
            null
        }
    if (server == null || (agentIds.isEmpty() && working.isEmpty()) || !payload.showComposer) return null
    val membersState = serverMembersStore?.state?.collectAsState()?.value
    val serverMembers =
        remember(membersState, server) { serverMembersStore?.currentServerMembers(ServerId(server)).orEmpty() }
    val fallbackName = stringResource(Res.string.thread_agent_typing_agent_fallback)
    val agents =
        remember(agentIds, serverMembers, payload.members, payload.memberCandidates, fallbackName) {
            resolveThreadTypingAgents(agentIds, serverMembers, payload.members + payload.memberCandidates, fallbackName) { id ->
                serverMembersStore?.durableMemberAvatarWireValue(ServerId(server), id)
            }
        }
    if (agents.isEmpty() && working.isEmpty()) return null
    return remember(server, agents, working, workingNow) { ThreadAgentTypingUi(server, agents, working, workingNow) }
}

/** Web `statusThreadWorking`: who is typing in the thread under a channel message, by the message's id. */
internal fun interface ThreadAgentTypingInThread {
    fun agentIds(parentMessageId: String): List<String>
}

/** Null outside a channel (a thread's own replies have no thread under them). */
internal val LocalThreadAgentTypingInThread = compositionLocalOf<ThreadAgentTypingInThread?> { null }

/**
 * The page's channel's typing per thread, for agent read progress: an agent typing in the thread
 * under the viewer's message has it, so the line does not flag it. Null on a reply thread or a page
 * with no conversation; the store's server is bound by [rememberThreadAgentTyping].
 */
@Composable
internal fun rememberThreadAgentTypingInThread(
    payload: RaftThreadScreenPayload,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
): ThreadAgentTypingInThread? {
    val store = remember(sharedStores, storeScope) { sharedStores.agentTypingStore(storeScope) }
    val server = serverId?.trim()?.takeIf { it.isNotBlank() }
    val state by store.state.collectAsState()
    val conversation = payload.agentTypingConversation()
    if (server == null || conversation == null || conversation.second != null) return null
    val channel = conversation.first
    return remember(state, server, channel) { ThreadAgentTypingInThread { parent -> state.typingAgentIds(server, channel, parent) } }
}

/** web `h-6`: the line's row. */
internal val ThreadAgentTypingLineHeight = 24.dp

/** web `AgentTypingListEnd` `h-7.5`: the list's extra end while the line shows. */
internal val ThreadAgentTypingListEndHeight = 30.dp

private const val ThreadAgentTypingMaxFaces = 3

@Composable
internal fun agentTypingSentenceTemplates(): AgentTypingSentenceTemplates =
    AgentTypingSentenceTemplates(
        one = stringResource(Res.string.thread_agent_typing_one),
        two = stringResource(Res.string.thread_agent_typing_two),
        three = stringResource(Res.string.thread_agent_typing_three),
        many = stringResource(Res.string.thread_agent_typing_many),
    )

/** raft-ui `MessageListEndFade` `h-timeline-section`: the list end fade, the section step. */
internal val ThreadMessageListEndFadeHeight = 24.dp

/**
 * The list's bottom edge over the composer (raft-ui `MessageListEndFade`, raft-design
 * `spacing.md` "Timeline rhythm"): always on, the panel color at the bottom to transparent upward
 * over the section step, so a message scrolled under the edge softens instead of being cut, and at
 * the end of the list it lies over the empty end. While the typing line shows, the fade grows by
 * exactly the line's height: a solid strip of panel color under the line, the same fade above it.
 * The switch is instant, like the line itself. Under the typing line and the jump controls; takes
 * no pointer input and is hidden from accessibility.
 */
@Composable
internal fun ThreadMessageListEndFade(typingLineShown: Boolean, modifier: Modifier = Modifier) {
    val panel = RaftColor.Canvas
    Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ThreadMessageListEndFadeHeight)
                .background(Brush.verticalGradient(listOf(panel.copy(alpha = 0f), panel))),
        )
        if (typingLineShown) {
            Box(Modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight).background(panel))
        }
    }
}

/**
 * The line itself, on the composer's 20dp edge. It has no fade of its own: it sits on the list's
 * end fade ([ThreadMessageListEndFade] with `typingLineShown`), whose solid strip under it keeps the
 * sentence readable over messages scrolled under it. The typing sentence is announced once,
 * politely, when it changes (in a channel too: only typing is announced, as on the web).
 *
 * In a channel ([ThreadAgentTypingUi.workingNow]) the line says who is working here, typing
 * included ([threadWorkingLine]), and is a button named by its sentence that opens Working now
 * upward from the line, aligned to its start.
 */
@Composable
internal fun ThreadAgentTypingLine(typing: ThreadAgentTypingUi, modifier: Modifier = Modifier) {
    val typingSentence = formatAgentTypingSentence(agentTypingSentenceTemplates(), typing.agents.map { it.name })
    val workingNow = typing.workingNow
    Box(
        modifier = modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (typingSentence.isNotEmpty()) {
            // The live region: the typing sentence alone (web `role="status"` keeps saying only who types).
            Box(
                Modifier.matchParentSize().clearAndSetSemantics {
                    contentDescription = typingSentence
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
        if (workingNow == null) {
            TypingLineContent(typing, typingSentence)
        } else {
            ChannelWorkingLineContent(typing, typingSentence, workingNow)
        }
    }
}

@Composable
private fun TypingLineContent(typing: ThreadAgentTypingUi, sentence: String) {
    Row(
        modifier =
            Modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight).raftSafeAreaHorizontalPadding().padding(horizontal = 20.dp)
                .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-5).dp), verticalAlignment = Alignment.CenterVertically) {
            typing.agents.take(ThreadAgentTypingMaxFaces).forEach { agent ->
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(MemberKind.Agent, agent.agentId, typing.serverId),
                    label = agent.name.toRaftAvatarLabel(),
                    avatarSize = 19.dp,
                    carriedAvatarWireValue = agent.avatarWireValue,
                )
            }
        }
        LineSentence(sentence, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun LineSentence(sentence: String, modifier: Modifier = Modifier, underline: Boolean = false) {
    Text(
        text = sentence,
        style = RaftTheme.typography.footnote.let { if (underline) it.copy(textDecoration = TextDecoration.Underline) else it },
        color = RaftTheme.colors.foregroundMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The working line's sentence from string resources (web `message.agentTyping.workingHere` / `typingAndOthers`). */
@Composable
internal fun threadWorkingLineSentence(line: ThreadWorkingLine, typingSentence: String): String =
    when (val sentence = line.sentence) {
        ThreadWorkingLineSentence.Typing -> typingSentence
        is ThreadWorkingLineSentence.WorkingHere ->
            if (sentence.count == 1) {
                stringResource(Res.string.thread_agent_working_here_one, sentence.count)
            } else {
                stringResource(Res.string.thread_agent_working_here_other, sentence.count)
            }
        is ThreadWorkingLineSentence.TypingAndOthers ->
            if (sentence.others == 1) {
                stringResource(Res.string.thread_agent_typing_and_others_one, typingSentence, sentence.others)
            } else {
                stringResource(Res.string.thread_agent_typing_and_others_other, typingSentence, sentence.others)
            }
    }

@Composable
private fun ChannelWorkingLineContent(typing: ThreadAgentTypingUi, typingSentence: String, workingNow: RaftWorkingNowSource) {
    val line = remember(typing.agents, typing.working) { threadWorkingLine(typing.agents, typing.working) }
    val sentence = threadWorkingLineSentence(line, typingSentence)
    var open by remember { mutableStateOf(false) }
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "thread-working-line") }
    var anchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val toggle = {
        anchorResolver = anchorRef.resolver(RaftPopoverLocalPoint.Exact(Offset.Zero))
        open = !open
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight).raftSafeAreaHorizontalPadding().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Web: `-mx-1 px-1 h-6 gap-1.5 rounded-control`, a button named by the sentence.
        Row(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .offset(x = (-4).dp)
                    .raftCaptureLiveRootAnchor(anchorRef)
                    .clip(RoundedCornerShape(6.dp))
                    // Hover is the sentence's underline (below), so no hover fill; a press shows the wash.
                    .hoverable(interaction)
                    .raftClickable(onClick = toggle, shape = RoundedCornerShape(6.dp), tier = RaftHoverTier.None, interactionSource = interaction)
                    .clearAndSetSemantics {
                        contentDescription = sentence
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
            RaftWorkingFaces(line.faces.workingNowFaces(), still = true)
            LineSentence(sentence, Modifier.weight(1f, fill = false), underline = hovered)
        }
    }
    if (open) {
        val anchor = anchorResolver?.resolveCurrentPlacement()
        if (anchor == null) {
            // The line left the screen under the popover: close it.
            LaunchedEffect(Unit) { open = false }
        } else {
            RaftWorkingNowPopover(
                anchor = anchor,
                items = line.faces,
                source = workingNow,
                onDismiss = { open = false },
                typingAgentIds = line.typingIds,
                upward = true,
            )
        }
    }
}
