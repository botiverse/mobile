package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.RaftMemberAppBotResolver
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.agentLoungeActivityMuteState
import build.raft.app.agentLoungeLookupChannelId
import build.raft.app.base.BridgeModule
import build.raft.app.compat.ui.collectAsStateWhileStarted
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftKnownEntityResolver
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId
import build.raft.app.raftMemberAppBotResolver
import build.raft.app.raftMemberAvatarResolver
import build.raft.app.rememberAgentLounge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/**
 * What the page's header projection hands back to it: the projection input and server, the member
 * resolvers, the directory projections, the stable header projection and the header's display model,
 * mute and pin state.
 */
internal class ThreadRouteHeaderProjection(
    val headerProjectionInput: ThreadHeaderProjectionInput,
    val conversationDirectoryLookupIds: List<String>,
    val headerProjectionServerId: ServerId?,
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val memberAppBotResolver: RaftMemberAppBotResolver,
    val knownEntityResolver: RaftKnownEntityResolver,
    val knownEntityAccountId: String?,
    val directoryConversationProjectionFlow: Flow<Map<String, SharedConversationDirectoryProjection>>,
    val directoryConversationProjectionsState: State<Map<String, SharedConversationDirectoryProjection>>,
    val directoryConversationProjection: SharedConversationDirectoryProjection?,
    val billingDirectoryProjectionsState: State<Map<String, SharedConversationDirectoryProjection>>,
    val jointMessageSurface: Boolean,
    val messageAvatarResolver: RaftMemberAvatarResolver,
    val lastAcceptedHeaderProjectionState: State<SharedConversationDirectoryProjection?>,
    val headerProjectionSource: String,
    val stableHeaderProjection: SharedConversationDirectoryProjection?,
    val composerJointChannelId: String?,
    val headerDisplayModel: ThreadHeaderDisplayModel,
    val mutePageLounge: RaftAgentLounge?,
    val activityMuteState: ThreadActivityMuteUiState?,
    val activityMutedIndicatorVisible: Boolean,
    val conversationPinState: ThreadConversationPinUiState?,
)

/**
 * The page's header projection: the route payload and input it projects, the directory projection
 * flow and its owner with their effects, the joint-surface and avatar resolvers, the last accepted
 * projection and its accept effect, and the header's display model, mute and pin state. Called every
 * composition of the page from where this code stood, so its remembers and effects keep their keys
 * and their place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteHeaderProjection(
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverMembersStore: SharedServerMembersStore?,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    sharedHomeStore: SharedHomeStore?,
    sharedHomeState: SharedHomeState,
    payload: RaftThreadScreenPayload,
    currentSession: StoredAuthSession?,
    replyCacheProvenance: ReplyCacheProvenance?,
): ThreadRouteHeaderProjection {
    val headerProjectionRoutePayload =
        remember(
            payload.surfaceKind,
            payload.channelKind,
            payload.sourceThreadId,
            payload.streamChannelId,
            payload.parentChannelId,
            payload.parentMessageId,
            payload.parentSurfaceKind,
            payload.parentChannelKind,
            payload.dmPeerIdentity
        ) {
            payload.threadHeaderProjectionRoutePayload()
        }
    val headerProjectionInput =
        remember(headerProjectionRoutePayload) {
            headerProjectionRoutePayload.threadHeaderProjectionInput()
        }
    val conversationDirectoryLookupIds = headerProjectionInput.lookupIds
    val emptyConversationDirectoryProjectionFlow =
        remember { MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>()) }
    val headerProjectionServerId =
        remember(replyCacheProvenance?.serverId, currentSession?.currentServerId, storeScope.initialServerId) {
            threadHeaderProjectionServerId(
                sessionServerId =
                    replyCacheProvenance
                        ?.serverId
                        ?.takeIf { it.isNotBlank() }
                        ?: currentSession?.currentServerId,
                scopeInitialServerId = storeScope.initialServerId
            )?.let(::ServerId)
        }
    val memberAvatarResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftMemberAvatarResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
                diagnosticsLogger = { message -> bridge.log("thread_avatar_member $message") }
            )
        }
    val memberAppBotResolver =
        remember(serverMembersStore, headerProjectionServerId) {
            raftMemberAppBotResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
            )
        }
    // First-frame header: whatever the app already holds in memory for this exact
    // account+server+conversation (Home sidebar row, identity hub). Canonical state still wins;
    // see displayModelWithKnownCandidate.
    val knownEntityResolver = remember(sharedStores, storeScope) { sharedStores.knownEntityResolver(storeScope) }
    val knownEntityAccountId =
        currentSession?.user?.id ?: sharedStores.currentAuthenticatedRouteOwner(storeScope)?.accountId
    val knownHeaderCandidate =
        remember(
            knownEntityResolver,
            knownEntityAccountId,
            headerProjectionServerId,
            headerProjectionInput,
            sharedHomeState.content
        ) {
            threadHeaderKnownCandidate(
                resolver = knownEntityResolver,
                accountId = knownEntityAccountId,
                serverId = headerProjectionServerId,
                input = headerProjectionInput
            )
        }
    val initialDirectoryConversationProjections =
        remember(
            conversationIdentityProvider,
            headerProjectionServerId,
            conversationDirectoryLookupIds
        ) {
            threadHeaderInitialProjectionMap(
                provider = conversationIdentityProvider,
                serverId = headerProjectionServerId,
                input = headerProjectionInput
            )
        }
    // Offline-first header: the stored channel detail (offline resource) stands in on a cold start or an
    // offline open until the network's detail arrives; it never replaces a detail already there.
    LaunchedEffect(sharedStores, storeScope, headerProjectionServerId, conversationDirectoryLookupIds) {
        val serverId = headerProjectionServerId?.value ?: return@LaunchedEffect
        if (conversationDirectoryLookupIds.isEmpty()) return@LaunchedEffect
        try {
            sharedStores.restoreStoredConversationIdentities(storeScope, serverId, conversationDirectoryLookupIds)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Best effort: the header falls back to its candidates.
        }
    }
    val directoryConversationProjectionFlow = remember(
        conversationIdentityProvider,
        headerProjectionServerId,
        headerProjectionInput.surfaceKind,
        conversationDirectoryLookupIds
    ) {
        if (
            conversationIdentityProvider != null &&
            headerProjectionServerId != null &&
            conversationDirectoryLookupIds.isNotEmpty()
        ) {
            conversationIdentityProvider
                .projectionFlow(headerProjectionServerId, conversationDirectoryLookupIds)
        } else {
            emptyConversationDirectoryProjectionFlow
        }
    }
    val headerProjectionStateOwner =
        remember(headerProjectionInput, initialDirectoryConversationProjections) {
            ThreadHeaderProjectionStateOwner(
                input = headerProjectionInput,
                initialProjections = initialDirectoryConversationProjections
            )
        }
    LaunchedEffect(headerProjectionStateOwner, directoryConversationProjectionFlow) {
        directoryConversationProjectionFlow.collect { projections ->
            val outcome = headerProjectionStateOwner.accept(projections)
            if (
                outcome != null &&
                headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread
            ) {
                bridge.log(
                    "thread_header_reply_diag stage=owner_accept " +
                        "serverId=${headerProjectionServerId?.value ?: "null"} " +
                        "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                        "incoming=${projections.headerProjectionMapDiagnostics()} " +
                        "outcome=${outcome.name} " +
                        "selected=${headerProjectionStateOwner.selectedProjection().headerProjectionDiagnostics()}"
                )
            }
        }
    }
    val directoryConversationProjectionsState = headerProjectionStateOwner.state.collectAsStateWhileStarted()
    val directoryConversationProjections by directoryConversationProjectionsState
    val directoryConversationProjection =
        remember(headerProjectionInput, conversationDirectoryLookupIds, directoryConversationProjections) {
            conversationDirectoryLookupIds
                .firstNotNullOfOrNull { id ->
                    directoryConversationProjections[id]?.takeIf { projection ->
                        headerProjectionInput.acceptsForDisplay(projection)
                    }
                }
        }
    // Unfiltered directory projections (header owner drops non-display rows, but a
    // reply thread's joint billing state lives on its parent channel projection).
    val billingDirectoryProjectionsState = directoryConversationProjectionFlow.collectAsStateWhileStarted(
        initialDirectoryConversationProjections
    )
    val billingDirectoryProjections by billingDirectoryProjectionsState
    // Joint channel (or a reply thread in one): message rows may resolve senders from the other
    // server, who are in neither of this server's rosters, through `POST /api/actors/resolve`.
    val jointMessageSurface =
        remember(
            billingDirectoryProjections,
            conversationDirectoryLookupIds,
            payload.channelKind,
            payload.parentSurfaceKind,
            payload.parentChannelKind,
            payload.parentChannelId
        ) {
            threadMessageSurfaceIsJoint(
                payload = payload,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections
            )
        }
    val messageAvatarResolver =
        remember(memberAvatarResolver, jointMessageSurface) {
            memberAvatarResolver.forJointSurface(jointMessageSurface)
        }
    val lastAcceptedHeaderProjectionState = remember(headerProjectionInput) {
        mutableStateOf<SharedConversationDirectoryProjection?>(null)
    }
    var lastAcceptedHeaderProjection by lastAcceptedHeaderProjectionState
    val headerProjectionSource =
        remember(headerProjectionInput, directoryConversationProjection, lastAcceptedHeaderProjection) {
            headerProjectionInput.stableProjectionSource(
                current = directoryConversationProjection,
                previous = lastAcceptedHeaderProjection
            )
        }
    val stableHeaderProjection =
        remember(headerProjectionInput, directoryConversationProjection, lastAcceptedHeaderProjection) {
            headerProjectionInput.stableProjection(
                current = directoryConversationProjection,
                previous = lastAcceptedHeaderProjection
            )
        }
    val composerJointChannelId =
        remember(
            payload.surfaceKind,
            payload.channelKind,
            payload.parentChannelId,
            payload.jointChannelId,
            conversationDirectoryLookupIds,
            billingDirectoryProjections,
            stableHeaderProjection,
        ) {
            threadComposerJointChannelId(
                payload = payload,
                lookupIds = conversationDirectoryLookupIds,
                projections = billingDirectoryProjections,
                stableHeaderProjection = stableHeaderProjection,
            )
        }
    LaunchedEffect(headerProjectionInput, directoryConversationProjection) {
        val previous = lastAcceptedHeaderProjection
        val accepted = directoryConversationProjection?.takeIf(headerProjectionInput::acceptsForDisplay)
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
            bridge.log(
                "thread_header_dm_diag stage=accept_check " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${previous.headerProjectionDiagnostics()} " +
                    "accepted=${accepted != null}"
            )
        }
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            bridge.log(
                "thread_header_reply_diag stage=accept_check " +
                    "serverId=${headerProjectionServerId?.value ?: "null"} " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${previous.headerProjectionDiagnostics()} " +
                    "accepted=${accepted != null}"
            )
        }
        if (accepted != null) {
            if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
                bridge.log(
                    "thread_header_dm_diag stage=accepted_store " +
                        "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                        "stored=${accepted.headerProjectionDiagnostics()}"
                )
            }
            lastAcceptedHeaderProjection = accepted
        }
    }
    // Public-page globe on the channel header icon: the server's publicPageLive comes from
    // the session server list (canonical store state), never from the route payload. The External
    // glyph needs no server fact.
    val headerServerPublicPageLive =
        currentSession?.servers?.firstOrNull { it.id == headerProjectionServerId?.value }?.publicPageLive == true
    val headerDisplayModel =
        remember(headerProjectionInput, stableHeaderProjection, payload, headerServerPublicPageLive, knownHeaderCandidate) {
            headerProjectionInput
                .displayModelWithKnownCandidate(
                    projection = stableHeaderProjection,
                    contentPayload = payload,
                    knownCandidate = knownHeaderCandidate
                )
                .copy(serverPublicPageLive = headerServerPublicPageLive)
        }
    // Agent Lounge v0: a Lounge's mute is the Lounge's own (no Home list holds it), so its ⋯ Mute
    // and muted mark follow what the sidebar row's menu and other devices set.
    val mutePageLounge =
        rememberAgentLounge(
            headerProjectionServerId?.value,
            payload.agentLoungeLookupChannelId()?.takeIf { payload.surfaceKind == RaftThreadSurfaceKind.Channel },
        )
    val activityMuteState =
        remember(payload, stableHeaderProjection, sharedHomeStore, mutePageLounge) {
            val state =
                if (mutePageLounge != null) {
                    mutePageLounge.agentLoungeActivityMuteState(joinedHint = stableHeaderProjection?.joined == true)
                } else {
                    payload.threadActivityMuteState(stableHeaderProjection)
                }
            state?.takeIf { sharedHomeStore != null }
        }
    val activityMutedIndicatorVisible =
        remember(payload, stableHeaderProjection, mutePageLounge) {
            mutePageLounge?.activityMuted ?: payload.threadActivityMutedIndicatorVisible(stableHeaderProjection)
        }
    val sidebarOrder =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)
                ?.snapshot
                ?.sidebarOrder
        }
    val conversationPinState =
        remember(payload, stableHeaderProjection, sidebarOrder) {
            payload.threadConversationPinTarget(stableHeaderProjection)?.toUiState(sidebarOrder)
        }
    return ThreadRouteHeaderProjection(
        headerProjectionInput = headerProjectionInput,
        conversationDirectoryLookupIds = conversationDirectoryLookupIds,
        headerProjectionServerId = headerProjectionServerId,
        memberAvatarResolver = memberAvatarResolver,
        memberAppBotResolver = memberAppBotResolver,
        knownEntityResolver = knownEntityResolver,
        knownEntityAccountId = knownEntityAccountId,
        directoryConversationProjectionFlow = directoryConversationProjectionFlow,
        directoryConversationProjectionsState = directoryConversationProjectionsState,
        directoryConversationProjection = directoryConversationProjection,
        billingDirectoryProjectionsState = billingDirectoryProjectionsState,
        jointMessageSurface = jointMessageSurface,
        messageAvatarResolver = messageAvatarResolver,
        lastAcceptedHeaderProjectionState = lastAcceptedHeaderProjectionState,
        headerProjectionSource = headerProjectionSource,
        stableHeaderProjection = stableHeaderProjection,
        composerJointChannelId = composerJointChannelId,
        headerDisplayModel = headerDisplayModel,
        mutePageLounge = mutePageLounge,
        activityMuteState = activityMuteState,
        activityMutedIndicatorVisible = activityMutedIndicatorVisible,
        conversationPinState = conversationPinState,
    )
}
