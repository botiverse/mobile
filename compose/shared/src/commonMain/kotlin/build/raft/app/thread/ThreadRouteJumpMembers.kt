package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.base.BridgeModule
import build.raft.app.compat.ui.collectAsStateWhileStarted
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/** What the page's jump pills hand back to it: their inputs and the inline reply metadata. */
internal class ThreadRouteJump(
    val jumpInputs: ThreadJumpInputs,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
)

/**
 * The page's jump pills: the Home unread they follow, the view model's unread-thread parents, focus
 * nonce and loading, the inline reply metadata, the lookup of unresolved unread parents, and the
 * jump-failure and read-toggle toasts. Called every composition of the page from where this code
 * stood, so its remembers and effects keep their keys and their place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteJump(
    bridge: BridgeModule,
    threadViewModel: SharedThreadViewModel,
    payload: RaftThreadScreenPayload,
    timelineSlice: ThreadTimelineSlice,
    sharedHomeState: SharedHomeState,
    currentServerId: String?,
    threadTimelineKey: String?,
    pageReplyMetadataByParentId: Map<String, ThreadReplyMetadata>,
): ThreadRouteJump {
    // Jump pills (ThreadJumpPills.kt): the unread-thread pill follows the web, a channel only;
    // its threads and the conversation's unread come from Home.
    val jumpHomeSnapshot =
        (sharedHomeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { snapshot -> snapshot.server.id.value == currentServerId }
    val jumpChannelId = threadTimelineKey?.takeIf { it.isNotBlank() }
    val jumpSurfaceKind = payload.surfaceKind
    val jumpFollowedThreadUnread = jumpHomeSnapshot?.followedThreadUnread
    val jumpConversationUnreadCount =
        jumpHomeSnapshot?.let { snapshot ->
            snapshot.channels.firstOrNull { it.channelId.value == jumpChannelId }?.unreadCount
                ?: snapshot.directMessages.firstOrNull { it.channelId.value == jumpChannelId }?.unreadCount
        } ?: 0
    // Parents of unread threads the page has not loaded, from the followed-threads list (web
    // FollowedThread.parentMessageId): without them the pill had no target for an older parent.
    val jumpUnreadThreadParents by remember(threadViewModel) {
        threadViewModel.unreadThreadParentsFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadParents)
    val jumpFocusNonce by remember(threadViewModel) {
        threadViewModel.unreadThreadJumpFocusNonceFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadJumpFocusNonce)
    val jumpLoading by remember(threadViewModel) {
        threadViewModel.unreadThreadJumpLoadingFlow()
    }.collectAsState(threadViewModel.state.value.unreadThreadJumpLoading)
    val jumpInputs =
        remember(
            jumpChannelId,
            jumpSurfaceKind,
            jumpFollowedThreadUnread,
            jumpConversationUnreadCount,
            jumpUnreadThreadParents,
            jumpFocusNonce,
            jumpLoading,
        ) {
            ThreadJumpInputs(
                channelId = jumpChannelId,
                unreadThreadPillEnabled =
                    jumpChannelId != null && jumpSurfaceKind == RaftThreadSurfaceKind.Channel,
                homeFollowedThreadUnread =
                    jumpFollowedThreadUnread?.filterValues { thread -> thread.parentChannelId.value == jumpChannelId },
                conversationUnreadCount = jumpConversationUnreadCount,
                unreadThreadParents = jumpUnreadThreadParents.filterValues { parent -> parent.parentChannelId == jumpChannelId },
                jumpFocusNonce = jumpFocusNonce,
                jumpLoading = jumpLoading,
            )
        }
    // #wg-mobile-sync:#256: a parent's inline replies count its thread's new replies as the channel
    // row's thread mark does (Home's followed-thread unread), so a reply that arrives while the
    // channel is open shows under its parent, not only on the row (ThreadJumpPills.kt).
    val replyMetadataByParentId = rememberInlineReplyMetadata(pageReplyMetadataByParentId, jumpFollowedThreadUnread)
    // The unread threads whose parent is neither loaded nor resolved: look them up (single-flight
    // in the store; each id at most once per page). Keyed by the id set, not the message list.
    val jumpUnresolvedUnreadThreadIds =
        remember(jumpInputs, timelineSlice.messages, replyMetadataByParentId) {
            if (!jumpInputs.unreadThreadPillEnabled) {
                emptySet()
            } else {
                val candidates = threadJumpParentCandidates(timelineSlice.messages, replyMetadataByParentId)
                threadUnreadThreadIdsNeedingParents(
                    unreadThreadIds =
                        threadConversationUnreadThreadIds(
                            channelId = jumpInputs.channelId.orEmpty(),
                            homeFollowedThreadUnread = jumpInputs.homeFollowedThreadUnread,
                            loadedMessages = candidates,
                        ),
                    messages = candidates,
                    resolvedParents = jumpInputs.unreadThreadParents,
                )
            }
        }
    val markUnreadNothingLabel by rememberUpdatedState(stringResource(Res.string.home_action_mark_unread_nothing))
    ThreadJumpFailureToasts(threadViewModel, bridge)
    LaunchedEffect(threadViewModel) {
        threadViewModel.readToggleNoticesFlow().collect { notice ->
            when (notice) {
                RaftReadToggleNotice.MarkUnreadNothing -> bridge.toast(markUnreadNothingLabel)
            }
        }
    }
    LaunchedEffect(threadViewModel, jumpUnresolvedUnreadThreadIds) {
        if (jumpUnresolvedUnreadThreadIds.isNotEmpty()) {
            threadViewModel.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(jumpUnresolvedUnreadThreadIds))
        }
    }
    return ThreadRouteJump(
        jumpInputs = jumpInputs,
        replyMetadataByParentId = replyMetadataByParentId,
    )
}

/** What the page's member block hands back to it: the channel, the collected member states and the projection. */
internal class ThreadRouteMembers(
    val threadMembersChannelId: ChannelId?,
    val threadServerMembersEntryState: State<SharedServerMembersEntry>,
    val threadChannelMembersSliceState: State<SharedServerChannelMembersSlice>,
    val threadMembersProjection: ThreadMembersProjection,
)

/**
 * The page's members: the server member directory and the channel member slice with their loads,
 * and the members projection over the payload. Called every composition of the page from where this
 * code stood, so its remembers and effects keep their keys and their place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteMembers(
    serverMembersStore: SharedServerMembersStore?,
    baseRenderedPayload: RaftThreadScreenPayload,
    threadMembersServerId: ServerId?,
    active: Boolean,
    sideCardsEnabled: Boolean,
    sideCardsStateHolder: State<ConversationSideCardsState>,
    selectedThreadTabState: State<RaftThreadTab>,
): ThreadRouteMembers {
    val sideCardsState by sideCardsStateHolder
    val selectedThreadTab by selectedThreadTabState
    val threadMembersChannelId = baseRenderedPayload.sourceThreadId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val threadServerMembersEntryState = remember(serverMembersStore, threadMembersServerId) {
        if (serverMembersStore != null && threadMembersServerId != null) {
            serverMembersStore.serverMembersEntryFlow(threadMembersServerId)
        } else {
            MutableStateFlow(SharedServerMembersEntry(serverId = threadMembersServerId?.value.orEmpty()))
        }
    }.collectAsState(
        // First frame: the directory already in memory, so @mentions paint names, not handles.
        threadMembersServerId?.let { serverId -> serverMembersStore?.currentServerMembersEntry(serverId) }
            ?: SharedServerMembersEntry(serverId = threadMembersServerId?.value.orEmpty())
    )
    val threadServerMembersEntry by threadServerMembersEntryState
    LaunchedEffect(selectedThreadTab, serverMembersStore, threadMembersServerId, sideCardsEnabled && sideCardsState.tasks) {
        val store = serverMembersStore ?: return@LaunchedEffect
        val serverId = threadMembersServerId ?: return@LaunchedEffect
        if (selectedThreadTab == RaftThreadTab.Tasks || (sideCardsEnabled && sideCardsState.tasks)) {
            store.refreshMembers(serverId, DataRequestPriority.Visible)
        }
    }
    // Keep channelMemberSliceFlow keyed independently of `active` so covering this thread during
    // a push never tears down and recreates its member flow on Frame 1; gate only the refresh.
    val threadChannelMembersSliceState = remember(
        serverMembersStore,
        threadMembersServerId,
        threadMembersChannelId,
    ) {
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.channelMemberSliceFlow(
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId
            )
        } else {
            MutableStateFlow(
                SharedServerChannelMembersSlice(
                    serverId = threadMembersServerId?.value,
                    channelId = threadMembersChannelId?.value.orEmpty()
                )
            )
        }
    }.collectAsStateWhileStarted(
        if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.currentChannelMemberSlice(threadMembersServerId, threadMembersChannelId)
        } else {
            SharedServerChannelMembersSlice(
                serverId = threadMembersServerId?.value,
                channelId = threadMembersChannelId?.value.orEmpty()
            )
        }
    )
    val threadChannelMembersSlice by threadChannelMembersSliceState
    LaunchedEffect(serverMembersStore, threadMembersServerId, threadMembersChannelId, active) {
        if (active && serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
            serverMembersStore.loadChannelMembers(
                serverId = threadMembersServerId,
                channelId = threadMembersChannelId,
                priority = DataRequestPriority.Visible
            )
        }
    }
    val threadMembersProjection =
        remember(baseRenderedPayload, threadChannelMembersSlice, threadServerMembersEntry, threadMembersServerId) {
            threadMembersProjection(
                basePayload = baseRenderedPayload,
                channelMembersSlice = threadChannelMembersSlice,
                serverMembersEntry = threadServerMembersEntry,
                serverId = threadMembersServerId
            )
        }
    return ThreadRouteMembers(
        threadMembersChannelId = threadMembersChannelId,
        threadServerMembersEntryState = threadServerMembersEntryState,
        threadChannelMembersSliceState = threadChannelMembersSliceState,
        threadMembersProjection = threadMembersProjection,
    )
}
