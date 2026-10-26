package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentTypingSentenceTemplates
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.formatAgentTypingSentence
import build.raft.app.data.typingAgentIds
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftTheme

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
    val avatarUrl: String?,
)

/** The page's typing line: who, in the order they started (never empty). */
@Immutable
internal data class ThreadAgentTypingUi(
    val serverId: String,
    val agents: List<ThreadTypingAgent>,
)

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
 */
internal fun resolveThreadTypingAgents(
    agentIds: List<String>,
    serverMembers: List<MemberProfile>,
    channelMembers: List<RaftThreadMemberItem>,
    fallbackName: String,
): List<ThreadTypingAgent> =
    agentIds.mapNotNull { id ->
        val server = serverMembers.firstOrNull { it.id.value == id }
        if (server != null) {
            if (server.kind != MemberKind.Agent) return@mapNotNull null
            val name = server.displayName.ifBlank { server.handle }.ifBlank { fallbackName }
            return@mapNotNull ThreadTypingAgent(id, name, server.avatarUrl)
        }
        val channel = channelMembers.firstOrNull { it.id == id }
        if (channel != null) {
            if (!channel.kind.equals("agent", ignoreCase = true)) return@mapNotNull null
            val name = channel.displayName.ifBlank { channel.handle }.ifBlank { fallbackName }
            return@mapNotNull ThreadTypingAgent(id, name, channel.avatarUrl)
        }
        ThreadTypingAgent(id, fallbackName, null)
    }

/**
 * Binds the page to the shell's agent typing store: the page's server is the store's (a switch
 * starts over), and the line lists who is about to post into this page's conversation. Null while
 * nobody is, or while the page shows no composer (web renders the line with the composer only).
 */
@Composable
internal fun rememberThreadAgentTyping(
    payload: RaftThreadScreenPayload,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    serverMembersStore: SharedServerMembersStore?,
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
    if (server == null || agentIds.isEmpty() || !payload.showComposer) return null
    val membersState = serverMembersStore?.state?.collectAsState()?.value
    val serverMembers =
        remember(membersState, server) { serverMembersStore?.currentServerMembers(ServerId(server)).orEmpty() }
    val fallbackName = stringResource(Res.string.thread_agent_typing_agent_fallback)
    val agents =
        remember(agentIds, serverMembers, payload.members, payload.memberCandidates, fallbackName) {
            resolveThreadTypingAgents(agentIds, serverMembers, payload.members + payload.memberCandidates, fallbackName)
        }
    if (agents.isEmpty()) return null
    return remember(server, agents) { ThreadAgentTypingUi(server, agents) }
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
 * sentence readable over messages scrolled under it. The sentence is announced once, politely,
 * when it changes.
 */
@Composable
internal fun ThreadAgentTypingLine(typing: ThreadAgentTypingUi, modifier: Modifier = Modifier) {
    val sentence = formatAgentTypingSentence(agentTypingSentenceTemplates(), typing.agents.map { it.name })
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ThreadAgentTypingLineHeight)
                .clearAndSetSemantics {
                    contentDescription = sentence
                    liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ThreadAgentTypingLineHeight).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-5).dp), verticalAlignment = Alignment.CenterVertically) {
                typing.agents.take(ThreadAgentTypingMaxFaces).forEach { agent ->
                    RaftAppAvatar(
                        avatarWireValue = agent.avatarUrl,
                        label = agent.name.toRaftAvatarLabel(),
                        avatarSize = 19.dp,
                        cacheServerId = typing.serverId,
                        fallbackFill = raftAvatarFallbackFill(MemberKind.Agent),
                    )
                }
            }
            Text(
                text = sentence,
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
