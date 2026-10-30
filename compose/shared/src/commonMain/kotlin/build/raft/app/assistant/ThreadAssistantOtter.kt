package build.raft.app.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import build.raft.app.ConversationOpenResolution
import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.data.MyAssistantKey
import build.raft.app.data.MyAssistantState
import build.raft.app.data.PERSONAL_ASSISTANT_FEATURE_FLAG_KEY
import build.raft.app.data.RaftAssistantOtterHosts
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.activeAssistantOtterHost
import build.raft.app.data.myAssistantState
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.AgentStatus
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.resolveOpenRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// The page side of the personal assistant's otter (web assistant/AssistantOtter.tsx
// `AssistantOtterAnchor` / `AssistantOtterListEnd` and assistantOtterState.ts): which composer it
// lies on, its mood's inputs, and what a press does. The view is RaftAssistantOtter.kt.

/**
 * One conversation's otter, when that conversation may carry it: its composer shows the otter
 * while [hostId] is the host it lies on ([hosts]), and its message list keeps the band free.
 */
@Stable
internal class ThreadAssistantOtter(
    val hostId: String,
    val hosts: RaftAssistantOtterHosts,
    val mood: RaftOtterMood,
    val scale: Float,
    val endInset: Dp,
    val onOpen: () -> Unit,
)

/** The current conversation's otter, or null where it has none. */
internal val LocalThreadAssistantOtter = compositionLocalOf<ThreadAssistantOtter?> { null }

/**
 * The DM the otter opens, when a composer of [conversationChannelId] may carry it (web
 * `AssistantOtterAnchor`'s `eligible`): the assistant is ready, its DM exists, and this is not
 * that DM (nor a thread in it: pass a reply thread's parent channel). Null: no otter.
 */
internal fun assistantOtterDmChannelId(state: MyAssistantState, conversationChannelId: String?): String? {
    val assistant = (state as? MyAssistantState.Ready)?.assistant ?: return null
    val dmChannelId = assistant.dmChannelId?.takeIf(String::isNotBlank) ?: return null
    val conversation = conversationChannelId?.takeIf(String::isNotBlank) ?: return null
    return dmChannelId.takeIf { it != conversation }
}

/**
 * The otter's mood from what the app knows (web `useAssistantOtterMood`):
 * - [agentStatus]: the assistant agent's status; thinking / working is `working`, offline `away`.
 * - [dmUnreadCount]: unread in the owner's DM with it (`new`).
 * - [paused]: a server owner or admin paused it (the agent's `assistantPausedAt`, web
 *   `useAssistantPause`): `away`, above everything else.
 * - [confirm] has no source in the app yet (see the PR): the app keeps no observable list of the
 *   DM's messages to find a waiting action card in. Web shows a waiting card as `new` through the
 *   unread count until the DM's messages are loaded, which is what the app shows too.
 */
internal fun assistantOtterMood(
    agentStatus: AgentStatus?,
    dmUnreadCount: Int,
    paused: Boolean = false,
    confirm: Boolean = false,
): RaftOtterMood =
    raftOtterMood(
        paused = paused,
        confirm = confirm,
        unread = dmUnreadCount > 0,
        working = agentStatus == AgentStatus.Thinking || agentStatus == AgentStatus.Working,
        offline = agentStatus == AgentStatus.Offline,
    )

/** Whether the roster's [agentId] is a paused assistant (web `useAssistantPause(agentId) !== null`). */
internal fun SharedServerMembersEntry.assistantPaused(agentId: String): Boolean =
    membersById[agentId.trim()]?.assistantPausedAt?.isNotBlank() == true

/** Unread in [serverId]'s DM [channelId] as Home last read it; 0 when Home holds another server. */
internal fun SharedHomeState.directMessageUnreadCount(serverId: String, channelId: String): Int {
    val content = content as? SharedHomeContentState.Content ?: return 0
    val snapshot = content.snapshot
    if (snapshot.server.id.value != serverId) return 0
    return snapshot.directMessages.firstOrNull { it.channelId.value == channelId }?.unreadCount ?: 0
}

/** Opening the assistant's DM from the otter: a typed DM identity, checked against the directory. */
internal fun assistantOtterDmOpenRequest(
    serverId: String,
    dmChannelId: String,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
): HomeMessageOpenRequest? {
    val projection =
        conversationIdentityProvider
            ?.resolveProjection(ServerId(serverId), dmChannelId)
            ?.takeIf { it.serverId.isBlank() || it.serverId == serverId }
    if (projection != null && projection.conversationKind != ConversationKind.DirectMessage) return null
    val source =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.AssistantOtter,
            messageId = null,
            channelId = dmChannelId,
            conversationKind = ConversationKind.DirectMessage,
        )
    val directory = listOfNotNull(projection).toSharedConversationDirectoryState()
    return (resolveOpenRequest(serverId, source, directory) as? ConversationOpenResolution.Resolved)?.request
}

/**
 * The otter for one conversation page (web `AssistantOtterAnchor`'s data side), or null where it
 * has none. Asks for "my assistant" only while the server's `personal_assistant` flag is on and
 * the viewer is not a guest.
 *
 * - [conversationChannelId]: the conversation's channel, a reply thread's parent channel.
 * - [wide]: a conversation in a wide window's detail pane (web desktop): a channel or DM composer
 *   takes the full-size otter; a reply thread keeps 80% (web ThreadPanel `scale={0.8}`), both
 *   over the send button's side.
 */
@Composable
internal fun rememberThreadAssistantOtter(
    store: SharedMyAssistantStore,
    featureFlags: SharedServerFeatureFlags,
    serverMembersStore: SharedServerMembersStore?,
    homeState: SharedHomeState,
    serverId: String?,
    userId: String?,
    viewerIsGuest: Boolean,
    conversationChannelId: String?,
    hostId: String,
    wide: Boolean,
    replyThread: Boolean,
    onOpenDm: (serverId: String, dmChannelId: String) -> Unit,
): ThreadAssistantOtter? {
    val server = serverId?.takeIf(String::isNotBlank)
    val user = userId?.takeIf(String::isNotBlank)
    val flagEnabled = rememberServerFeatureLatched(featureFlags, server?.let(::ServerId), PERSONAL_ASSISTANT_FEATURE_FLAG_KEY)
    val key = if (server != null && user != null) MyAssistantKey(server, user) else null
    val shouldAsk = flagEnabled && !viewerIsGuest && key != null
    LaunchedEffect(store, key, shouldAsk) {
        if (shouldAsk && key != null) store.ensureLoaded(key)
    }
    val answers by store.answers.collectAsState()
    val state =
        myAssistantState(
            viewerIsGuest = viewerIsGuest,
            flagDecisionPending = server != null && featureFlags.isDecisionPending(ServerId(server), PERSONAL_ASSISTANT_FEATURE_FLAG_KEY),
            flagEnabled = flagEnabled,
            answer = key?.let(answers::get),
        )
    val dmChannelId = assistantOtterDmChannelId(state, conversationChannelId)
    val agentId = (state as? MyAssistantState.Ready)?.assistant?.agentId?.takeIf { dmChannelId != null }
    val statusFlow =
        remember(serverMembersStore, server, agentId) {
            if (serverMembersStore == null || server == null || agentId == null) {
                flowOf(null)
            } else {
                serverMembersStore.memberStatusFlow(ServerId(server), agentId)
            }
        }
    val initialStatus =
        remember(serverMembersStore, server, agentId) {
            if (server == null || agentId == null) null else serverMembersStore?.currentMemberStatus(ServerId(server), agentId)
        }
    val status by statusFlow.collectAsState(initialStatus)
    // The pause rides on the agent payload; agent:updated refreshes the roster that carries it.
    val pausedFlow =
        remember(serverMembersStore, server, agentId) {
            if (serverMembersStore == null || server == null || agentId == null) {
                flowOf(false)
            } else {
                serverMembersStore.serverMembersEntryFlow(ServerId(server))
                    .map { it.assistantPaused(agentId) }
                    .distinctUntilChanged()
            }
        }
    val initialPaused =
        remember(serverMembersStore, server, agentId) {
            server != null && agentId != null &&
                serverMembersStore?.currentServerMembersEntry(ServerId(server))?.assistantPaused(agentId) == true
        }
    val paused by pausedFlow.collectAsState(initialPaused)
    val currentOnOpenDm by rememberUpdatedState(onOpenDm)
    if (server == null || dmChannelId == null) return null
    val mood =
        assistantOtterMood(
            agentStatus = status?.status,
            dmUnreadCount = homeState.directMessageUnreadCount(server, dmChannelId),
            paused = paused,
        )
    val scale = if (wide && !replyThread) RaftAssistantOtterDefaults.WideScale else RaftAssistantOtterDefaults.PhoneScale
    val endInset = if (wide) RaftAssistantOtterDefaults.WideEndInset else RaftAssistantOtterDefaults.PhoneEndInset
    return remember(hostId, store, mood, scale, endInset, server, dmChannelId) {
        ThreadAssistantOtter(
            hostId = hostId,
            hosts = store.otterHosts,
            mood = mood,
            scale = scale,
            endInset = endInset,
            onOpen = { currentOnOpenDm(server, dmChannelId) },
        )
    }
}

/**
 * The composer frame's edge (web MessageInput `frameEdge` with `AssistantOtterAnchor`): registers
 * this composer while it is shown, takes the otter when focused, and draws it while it lies here.
 * Goes in the composer frame's own box, after the frame.
 */
@Composable
internal fun ThreadAssistantOtterFrameEdge(
    otter: ThreadAssistantOtter,
    composing: Boolean,
    focused: Boolean,
    animateEntrance: Boolean = true,
) {
    DisposableEffect(otter.hosts, otter.hostId) {
        otter.hosts.register(otter.hostId)
        onDispose { otter.hosts.unregister(otter.hostId) }
    }
    // Focus in this composer: the person is using it.
    LaunchedEffect(otter.hosts, otter.hostId, focused) {
        if (focused) otter.hosts.activate(otter.hostId)
    }
    val order by otter.hosts.order.collectAsState()
    if (order.activeAssistantOtterHost() != otter.hostId) return
    RaftAssistantOtter(
        mood = otter.mood,
        // The panel (PanelOpen) is the next step; a press opens the DM for now.
        tuck = if (composing) RaftOtterTuck.Composing else RaftOtterTuck.None,
        onClick = otter.onOpen,
        modifier = Modifier.fillMaxWidth().testTag(ThreadAssistantOtterTestTag),
        scale = otter.scale,
        endInset = otter.endInset,
        animateEntrance = animateEntrance,
    )
}

/** A composer host's id for [RaftAssistantOtterHosts], unique for the page's lifetime (web `useId`). */
@OptIn(ExperimentalUuidApi::class)
internal fun newAssistantOtterHostId(): String = "thread-otter-${Uuid.random()}"

/** Web `data-composing`: text (not only whitespace) or an attachment in the composer. */
internal fun threadComposerComposing(text: String, attachmentCount: Int): Boolean =
    text.isNotBlank() || attachmentCount > 0

/**
 * At the end of the conversation's message list (web `AssistantOtterListEnd`): while the otter
 * lies on this conversation's composer, a full-width band as tall as the otter, so the last
 * message is never under it. Nothing otherwise.
 */
@Composable
internal fun ThreadAssistantOtterListEnd(otter: ThreadAssistantOtter?) {
    if (otter == null) return
    val order by otter.hosts.order.collectAsState()
    if (order.activeAssistantOtterHost() != otter.hostId) return
    Box(Modifier.testTag(ThreadAssistantOtterListEndTestTag)) {
        RaftAssistantOtterListEndBand(otter.mood, scale = otter.scale)
    }
}

/** Web `data-testid="assistant-otter"` / `"assistant-otter-list-end"`. */
internal const val ThreadAssistantOtterTestTag = "assistant-otter"
internal const val ThreadAssistantOtterListEndTestTag = "assistant-otter-list-end"
