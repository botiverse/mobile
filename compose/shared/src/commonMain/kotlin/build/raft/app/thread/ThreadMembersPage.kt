package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.components.rememberRaftStorePullRefreshState
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.memberOrigin
import build.raft.app.data.memberRemovableFromChannel
import build.raft.app.data.memberRowServerLabel
import build.raft.app.data.memberServerBadgeLabels
import build.raft.app.data.RaftAgentCreateEntry
import build.raft.app.data.RaftProductEvents
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.data.ChangeChannelMemberRoleRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ErrorPresentationKey
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RawServerMessagePriority
import build.raft.app.data.ScreenError
import build.raft.app.data.errorPresentation
import build.raft.app.data.map
import build.raft.app.data.usableServerRefusalMessage
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class ThreadMembersProjection(
    val membersCount: Int,
    val members: List<RaftThreadMemberItem>,
    val memberCandidates: List<RaftThreadMemberItem>,
    val canAddMembers: Boolean
)

@Page(THREAD_MEMBERS_PAGE, supportInLocal = true)
internal class ComposeThreadMembersPage : AuthenticatedProductPager(AuthenticatedProductRoute.ThreadMembers) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { ThreadMembersPageScreen() }
    }
}

internal data class ThreadMembersPageRoute(
    val instanceId: String,
    val serverId: ServerId,
    val channelId: ChannelId,
    val localOffsetMinutes: Int? = null,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String? = null,
    /**
     * Open straight into Add Member (web ChannelOverflowMenu `membersInitialView = "add"`, the
     * members strip's dashed add tile); applied once, when the viewer may add members.
     */
    val openAddMember: Boolean = false,
)

/** pageData key for [ThreadMembersPageRoute.openAddMember]: `"add"` opens Add Member. */
internal const val THREAD_MEMBERS_INITIAL_VIEW_KEY = "initialView"

internal data class ThreadMemberRemovalConfirmation(
    val serverId: ServerId,
    val memberRowId: String,
    val memberLabel: String,
    val channelName: String,
    val request: RemoveChannelMemberRequest,
    val personName: String? = memberLabel.takeIf { it.isNotBlank() && it != request.memberId.value },
)

internal enum class ThreadMemberRemovalFailure {
    Failed,
    NoPermission
}

internal data class ThreadMemberRemovalGateState(
    val pending: ThreadMemberRemovalConfirmation? = null,
    val inFlight: Boolean = false,
    val failure: ThreadMemberRemovalFailure? = null,
    val errorCode: String? = null,
    val serverMessage: String? = null,
    val serverCode: String? = null,
    val traceId: String? = null,
)

internal sealed interface ThreadMemberRemovalGateIntent {
    data class Request(val confirmation: ThreadMemberRemovalConfirmation) : ThreadMemberRemovalGateIntent

    data class Confirm(val serverId: ServerId, val channelId: ChannelId) : ThreadMemberRemovalGateIntent

    data class CompleteSuccess(val confirmation: ThreadMemberRemovalConfirmation) : ThreadMemberRemovalGateIntent

    data class CompleteFailure(
        val confirmation: ThreadMemberRemovalConfirmation,
        val failure: ThreadMemberRemovalFailure,
        val errorCode: String,
        val serverMessage: String? = null,
        val serverCode: String? = null,
        val traceId: String? = null,
    ) : ThreadMemberRemovalGateIntent

    data object Dismiss : ThreadMemberRemovalGateIntent
}

internal data class ThreadMemberRemovalGateResult(
    val state: ThreadMemberRemovalGateState,
    val execute: ThreadMemberRemovalConfirmation? = null
)

internal fun reduceThreadMemberRemovalGate(
    state: ThreadMemberRemovalGateState,
    intent: ThreadMemberRemovalGateIntent
): ThreadMemberRemovalGateResult =
    when (intent) {
        is ThreadMemberRemovalGateIntent.Request ->
            if (state.inFlight) {
                ThreadMemberRemovalGateResult(state)
            } else {
                ThreadMemberRemovalGateResult(
                    ThreadMemberRemovalGateState(pending = intent.confirmation)
                )
            }
        is ThreadMemberRemovalGateIntent.Confirm -> {
            val pending = state.pending
            when {
                state.inFlight || pending == null -> ThreadMemberRemovalGateResult(state)
                pending.serverId != intent.serverId || pending.request.channelId != intent.channelId ->
                    ThreadMemberRemovalGateResult(ThreadMemberRemovalGateState())
                else ->
                    ThreadMemberRemovalGateResult(
                        state =
                            state.copy(
                                inFlight = true,
                                failure = null,
                                errorCode = null,
                                serverMessage = null,
                                serverCode = null,
                                traceId = null,
                            ),
                        execute = pending
                    )
            }
        }
        is ThreadMemberRemovalGateIntent.CompleteSuccess ->
            if (state.inFlight && state.pending == intent.confirmation) {
                ThreadMemberRemovalGateResult(ThreadMemberRemovalGateState())
            } else {
                ThreadMemberRemovalGateResult(state)
            }
        is ThreadMemberRemovalGateIntent.CompleteFailure ->
            if (state.inFlight && state.pending == intent.confirmation) {
                ThreadMemberRemovalGateResult(
                    state.copy(
                        inFlight = false,
                        failure = intent.failure,
                        errorCode = intent.errorCode,
                        serverMessage = intent.serverMessage,
                        serverCode = intent.serverCode,
                        traceId = intent.traceId,
                    )
                )
            } else {
                ThreadMemberRemovalGateResult(state)
            }
        ThreadMemberRemovalGateIntent.Dismiss ->
            if (state.inFlight) {
                ThreadMemberRemovalGateResult(state)
            } else {
                ThreadMemberRemovalGateResult(ThreadMemberRemovalGateState())
            }
    }

/**
 * Resolves the user-facing refusal message for member removal failures across both the confirmation
 * dialog and the roster banner using the 4-tier refusal policy.
 */
internal fun threadMemberRemovalFailureMessage(
    state: ThreadMemberRemovalGateState,
    noPermissionFallback: String,
    genericFallback: String,
    strings: ErrorPresentationStrings,
): String? {
    val failure = state.failure ?: return null
    val fallback = if (failure == ThreadMemberRemovalFailure.NoPermission) noPermissionFallback else genericFallback
    if (
        state.errorCode.isNullOrBlank() &&
        state.serverCode.isNullOrBlank() &&
        state.serverMessage.isNullOrBlank()
    ) {
        return fallback
    }
    val presentation =
        if (failure == ThreadMemberRemovalFailure.NoPermission) {
            PermissionBlocker(
                code = state.errorCode ?: "permission_denied",
                serverMessage = state.serverMessage,
                serverCode = state.serverCode,
                traceId = state.traceId,
            ).errorPresentation()
        } else {
            ScreenError(
                code = state.errorCode ?: "member_remove_failed",
                retryable = false,
                serverMessage = state.serverMessage,
                serverCode = state.serverCode,
                traceId = state.traceId,
            ).errorPresentation()
        }
    val raw = usableServerRefusalMessage(presentation.rawServerMessage)
    return when {
        presentation.rawServerMessagePriority == RawServerMessagePriority.ServerMessageFirst && raw != null -> raw
        presentation.key != ErrorPresentationKey.Generic &&
            !(failure == ThreadMemberRemovalFailure.NoPermission && presentation.key == ErrorPresentationKey.PermissionDenied) ->
            strings.value(presentation.key)
        else -> fallback
    }
}

internal fun threadMembersRemovalChannelName(
    snapshot: HomeSnapshot?,
    serverId: ServerId,
    channelId: ChannelId
): String {
    val canonicalName =
        snapshot
            ?.takeIf { it.server.id == serverId }
            ?.channels
            ?.firstOrNull { it.channelId == channelId }
            ?.name
            ?.trim()
            ?.removePrefix("#")
            ?.takeIf { it.isNotBlank() }
    return canonicalName ?: channelId.value.trim().removePrefix("#")
}

internal fun threadMemberRemovalConfirmation(
    member: RaftThreadMemberItem,
    serverId: ServerId,
    channelId: ChannelId,
    channelName: String
): ThreadMemberRemovalConfirmation? {
    if (!member.canRemove) return null
    val identity = member.memberDetailIdentity() ?: return null
    val memberLabel =
        member.displayName.trim().takeIf { it.isNotBlank() }
            ?: member.handle.trim().removePrefix("@").takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: identity.memberId.value
    return ThreadMemberRemovalConfirmation(
        serverId = serverId,
        memberRowId = member.id,
        memberLabel = memberLabel,
        channelName = channelName.trim().removePrefix("#").ifBlank { channelId.value },
        request = RemoveChannelMemberRequest(channelId, identity.memberId, identity.kind),
        personName = member.stillInChannelPersonName(),
    )
}

internal fun RaftThreadScreenPayload.toThreadMembersPageData(
    serverId: ServerId,
    channelId: ChannelId
): JSONObject =
    JSONObject().apply {
        put("pageName", THREAD_MEMBERS_PAGE)
        put("instanceId", "thread-members-${instanceId.ifBlank { channelId.value }}")
        put("serverId", serverId.value)
        put("channelId", channelId.value)
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
    }

internal fun JSONObject.toThreadMembersPageRouteOrNull(): ThreadMembersPageRoute? {
    val serverId = optString("serverId").trim().takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
    val channelId = optString("channelId").trim().takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
    return ThreadMembersPageRoute(
        instanceId = optString("instanceId"),
        serverId = serverId,
        channelId = channelId,
        localOffsetMinutes = optString("localOffsetMinutes").toIntOrNull(),
        todayEpochDay = optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = optString("nowEpochMillis").toLongOrNull(),
        timeFormat = optString("timeFormat").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
        openAddMember = optString(THREAD_MEMBERS_INITIAL_VIEW_KEY).trim() == "add",
    )
}

private fun ThreadMembersPageRoute.toMemberDetailPageData(
    identity: MemberDetailIdentity,
    sourceServerName: String? = null
): JSONObject? {
    val selected = identity.memberId.value.trim().takeIf { it.isNotBlank() } ?: return null
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "thread-members-detail-${instanceId.ifBlank { channelId.value }}-$selected")
        put("memberId", selected)
        put("memberKind", identity.kind.memberKindWireValue())
        identity.sourceServerId?.value?.let { put("sourceServerId", it) }
        sourceServerName?.takeIf { it.isNotBlank() }?.let { put("sourceServerName", it) }
        // web ChannelMembers: a person opened from the channel's members names their servers in it.
        if (identity.kind == MemberKind.Human) channelId.value.takeIf { it.isNotBlank() }?.let { put(MemberDetailProfileChannelIdKey, it) }
        localOffsetMinutes?.let { put("localOffsetMinutes", it) }
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        timeFormat?.takeIf { it.isNotBlank() }?.let { put("timeFormat", it) }
    }
}

@Composable
private fun ThreadMembersPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val route = remember(pager.pageData.params.toString()) { pager.pageData.params.toThreadMembersPageRouteOrNull() }
    if (route == null) {
        Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                RaftTopPageHeader(
                    title = stringResource(Res.string.thread_members_heading),
                    iconName = RaftLucideIconName.Users,
                    onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
                )
                ThreadStateMessage(title = stringResource(Res.string.common_error), detail = null)
            }
        }
        return
    }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val routeOwner =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.authenticatedRouteOwnerOrNull()
        }
    if (routeOwner == null || routeOwner.serverId != route.serverId.value) {
        Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                RaftTopPageHeader(
                    title = stringResource(Res.string.thread_members_title, 0),
                    iconName = RaftLucideIconName.Users,
                    onBack = router::closePage,
                )
                ThreadStateMessage(title = stringResource(Res.string.common_error), detail = null)
            }
        }
        return
    }
    val agentDetailStore = remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val memberScope = rememberCoroutineScope()
    val authorityGateway = remember(sharedStores, storeScope) {
        sharedStores.authenticatedRouteAuthorityGateway(storeScope)
    }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val liveOwner by
        remember(sharedStores, storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val initialChannelMembersSlice =
        remember(serverMembersStore, routeOwner, route.channelId) {
            serverMembersStore.currentChannelMemberSlice(routeOwner, route.channelId)
        }
    val channelMembersSlice by
        remember(serverMembersStore, routeOwner, route.channelId) {
            serverMembersStore.channelMemberSliceFlow(routeOwner, route.channelId)
        }.collectAsState(initialChannelMembersSlice)
    val initialServerMembersEntry =
        remember(serverMembersStore, routeOwner) {
            serverMembersStore.currentServerMembersEntry(routeOwner)
        }
    val serverMembersEntry by
        remember(serverMembersStore, routeOwner) {
            serverMembersStore.serverMembersEntryFlow(routeOwner)
        }.collectAsState(initialServerMembersEntry)
    val conversationIdentityProvider =
        remember(sharedStores, storeScope) { sharedStores.conversationIdentityProvider(storeScope) }
    val initialChannelProjection =
        remember(conversationIdentityProvider, route.serverId, route.channelId) {
            conversationIdentityProvider?.resolveProjection(route.serverId, route.channelId.value)
        }
    val channelProjections by
        remember(conversationIdentityProvider, route.serverId, route.channelId) {
            conversationIdentityProvider?.projectionFlow(route.serverId, listOf(route.channelId.value))
                ?: flowOf(emptyMap())
        }.collectAsState(
            initialChannelProjection?.let { mapOf(route.channelId.value to it) }.orEmpty()
        )

    fun refreshMembers() {
        if (liveOwner != routeOwner) return
        memberScope.launch {
            serverMembersStore.loadChannelMembers(
                owner = routeOwner,
                channelId = route.channelId,
                priority = DataRequestPriority.Visible,
                force = true
            )
            serverMembersStore.refreshMembers(
                owner = routeOwner,
                priority = DataRequestPriority.Background,
                force = false,
                reason = "thread_members_page"
            )
        }
    }

    LaunchedEffect(serverMembersStore, liveOwner, routeOwner, route.serverId, route.channelId) {
        if (liveOwner == routeOwner) refreshMembers()
    }
    // Foreground / socket reconnect / network-restored catch-up is owned by SharedServerMembersStore
    // while channelMemberSliceFlow is actively observed (#wg-mobile-feedback:#274 Phase 1).
    // The signed-in person's id: the role menu never explains a demote of oneself (web `currentUserId`).
    var viewerUserId by remember(routeOwner) { mutableStateOf<String?>(null) }
    val canChangeMemberRoles =
        liveOwner == routeOwner && channelMembersSlice.canChangeMemberRoles && !channelMembersSlice.storedStandIn
    val addChannelProjection = channelProjections[route.channelId.value]
    val channelJointServers =
        addChannelProjection?.jointServers?.takeIf { it.isNotEmpty() }
            ?: addChannelProjection?.parentChannelId?.let { parentId ->
                channelProjections[parentId]?.jointServers?.takeIf { it.isNotEmpty() }
                    ?: conversationIdentityProvider?.resolveProjection(route.serverId, parentId)?.jointServers
            }.orEmpty()
    val members = remember(
        channelMembersSlice.members, channelMembersSlice.canRemoveMembers, canChangeMemberRoles, viewerUserId, route.serverId, channelJointServers
    ) {
        channelMembersSlice.members
            .distinctBy { it.threadMemberKey }
            .map { profile -> profile.toRaftThreadMemberItem(route.serverId, knownServers = channelJointServers).let { row ->
                row.copy(
                    canRemove =
                        !channelMembersSlice.storedStandIn &&
                            memberRemovableFromChannel(
                                member = profile,
                                viewerServerId = route.serverId.value,
                                canRemoveChannelMembers = channelMembersSlice.canRemoveMembers,
                            ),
                    roleAction = channelMemberRoleAction(profile, canChangeMemberRoles, viewerUserId),
                )
            } }
    }
    // The roster's own-server agents get detailed activity while this page is open (at most 200).
    RaftAgentActivitySubscriptionEffect(
        subscriptions = sharedStores.agentActivitySubscriptions,
        serverId = route.serverId.value,
        agentIds =
            remember(members, route.serverId) {
                members
                    .filter { it.kind.equals("agent", ignoreCase = true) && it.sourceServerId in setOf(null, route.serverId.value) }
                    .map { it.id }
            },
    )
    // A Guest is offered only in its own bound, non-joint channel (web isAddableToChannel, #15354).
    val channelKind = addChannelProjection?.channelKind
    val addChannelId = addChannelProjection?.canonicalChannelId ?: route.channelId.value
    val candidates = remember(serverMembersEntry.members, route.serverId, addChannelId, channelKind, members) {
        threadAddMemberCandidatesExcludingJoined(
            candidates =
                serverMembersEntry.members
                    .filter { it.isAddableToChannel(addChannelId, channelKind) }
                    .map { it.toRaftThreadMemberItem(route.serverId) },
            members = members,
            localServerId = route.serverId.value
        )
    }
    val agentStatusResolver = remember(serverMembersStore, route.serverId) {
        raftScopedAgentStatusResolver(serverMembersStore, route.serverId.value)
    }
    val memberAvatarResolver = remember(serverMembersStore, route.serverId) {
        raftMemberAvatarResolver(
            serverMembersStore = serverMembersStore,
            serverId = route.serverId.value,
            diagnosticsLogger = { message -> bridge.log("thread_members_pager_avatar $message") },
        )
    }
    var rosterQuery by remember(route) { mutableStateOf(TextFieldValue()) }
    var humansExpanded by remember(route) { mutableStateOf(true) }
    var agentsExpanded by remember(route) { mutableStateOf(true) }
    var externalExpanded by remember(route) { mutableStateOf(true) }
    val filteredMembers = filterThreadAddMemberCandidates(rosterQuery.text, members)
    val filteredExternal = channelMembersSlice.externalMembers.filter { member ->
        val query = rosterQuery.text.trim().removePrefix("@").lowercase()
        query.isBlank() || member.displayName.lowercase().contains(query) ||
            member.handles.any { it.lowercase().contains(query) }
    }
    var createAgentName by remember(routeOwner) { mutableStateOf<String?>(null) }
    var createAgentMode by remember(routeOwner) { mutableStateOf(MembersCreateAgentMode.Managed) }
    var canCreateAgents by remember(routeOwner) { mutableStateOf(false) }
    var serverPlan by remember(routeOwner) { mutableStateOf<String?>(null) }
    // External invites (web ChannelMembers): the viewer's role, email, and this server's name.
    var viewerServerRole by remember(routeOwner) { mutableStateOf<String?>(null) }
    var viewerServerName by remember(routeOwner) { mutableStateOf("") }
    var viewerEmail by remember(routeOwner) { mutableStateOf<String?>(null) }
    // Guest "Connect your servers" entry (slock #16375/#16389): the account's servers and role on each.
    var memberConnectAccount by remember(routeOwner) { mutableStateOf<MemberConnectAccount?>(null) }
    var guestServersRevision by remember(routeOwner) { mutableStateOf(0) }
    // Member labels (web #14535/#15817): logos of the viewer's joined servers overlaid with this
    // external channel's `jointServers[].serverAvatarUrl`.
    var viewerServerLogoPairs by remember(routeOwner) { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }
    LaunchedEffect(liveOwner, routeOwner, guestServersRevision) {
        canCreateAgents = false
        viewerServerRole = null
        viewerEmail = null
        memberConnectAccount = null
        if (liveOwner == routeOwner) {
            val session = sharedStores.authStore(storeScope).readSession()
            viewerServerLogoPairs = session?.servers.orEmpty().map { it.id to it.avatarUrl }
            val stored = session?.servers?.firstOrNull { it.id == routeOwner.serverId }
            val server = stored?.toServerSummary()
            canCreateAgents = threadAddMemberCanCreateAgents(server?.permissions)
            serverPlan = server?.plan
            viewerServerRole = stored?.role
            viewerServerName = stored?.name.orEmpty()
            viewerUserId = session?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
            viewerEmail = session?.user?.email?.trim()?.takeIf { it.isNotEmpty() }
            memberConnectAccount = session?.memberConnectAccount(routeOwner.serverId)
        } else createAgentName = null
    }
    val viewerServerLogos =
        remember(viewerServerLogoPairs, channelJointServers) {
            raftOriginServerLogos(viewerServerLogoPairs, channelJointServers)
        }
    var addDialogVisible by remember { mutableStateOf(false) }
    var addMemberStatus by remember { mutableStateOf("idle") }
    var addMemberErrorCode by remember { mutableStateOf<String?>(null) }
    var addMemberErrorServerCode by remember { mutableStateOf<String?>(null) }
    var addMemberErrorMessage by remember { mutableStateOf<String?>(null) }
    var addMemberErrorTraceId by remember { mutableStateOf<String?>(null) }
    var removalGate by
        remember(route.serverId, route.channelId) { mutableStateOf(ThreadMemberRemovalGateState()) }
    // Web `MemberRoleActions` / `useChannelMembers.changeMemberRole`: Make admin / Demote send at
    // once (no confirmation), the roster reads again on success; a server owner / admin's Demote
    // only explains. One change at a time; a failure shows the page's warning until the next try.
    var roleChange by remember(route.serverId, route.channelId) { mutableStateOf(ChannelMemberRoleChangeState()) }
    fun clearAddMemberErrorState() {
        addMemberStatus = "idle"
        addMemberErrorCode = null
        addMemberErrorServerCode = null
        addMemberErrorMessage = null
        addMemberErrorTraceId = null
    }
    fun dismissAddMember() {
        if (addMemberStatus == "adding") return
        addDialogVisible = false
        clearAddMemberErrorState()
    }
    val errorStrings = localizedErrorPresentationStrings()
    val unableToOpenProfileLabel = stringResource(Res.string.thread_error_open_profile)
    val memberRemovedLabel = stringResource(Res.string.thread_feedback_member_removed)
    val stillInChannelStrings = stillInChannelToastStrings()
    val channelName =
        channelProjections[route.channelId.value]
            ?.title
            ?.trim()
            ?.removePrefix("#")
            ?.ifBlank { null }
            ?: route.channelId.value.trim().removePrefix("#")
    val authorityRejected = liveOwner != routeOwner
    // Web useExternalInviteAccess / useExternalInviteManagement (#wg-rbac:#133): gate on the
    // channel's `channelCapabilities.federateChannels` (fresh GET /channels/:id first, then the
    // directory projection), falling back to own-server role only on legacy servers without it.
    val externalInviteChannel =
        channelProjections[route.channelId.value]?.let { projection ->
            ExternalInviteChannel(
                type = projection.channelKind,
                name = channelName,
                archived = projection.archived,
                jointRole = projection.jointRole,
                hasExternalRelation = projection.hasExternalRelation,
                federateChannels =
                    channelMembersSlice.takeUnless { it.storedStandIn }?.federateChannels
                        ?: projection.channelCapabilities?.federateChannels,
            )
        }
    val externalInviteAccessOn = !authorityRejected && externalInviteAccess(viewerServerRole, externalInviteChannel)
    val externalInviteManagementOn = !authorityRejected && externalInviteManagement(viewerServerRole, externalInviteChannel)
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val homeState by remember(homeStore) { homeStore?.state ?: flowOf(null) }.collectAsState(homeStore?.state?.value)
    val homeChannels =
        (homeState?.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { it.server.id.value == route.serverId.value }
            ?.channels
            .orEmpty()
    val latestHomeChannels by rememberUpdatedState(homeChannels)
    val latestHomeStore by rememberUpdatedState(homeStore)
    val latestRefreshMembers by rememberUpdatedState<() -> Unit>({ refreshMembers() })
    val externalInvitesStore =
        remember(routeOwner, route.channelId) {
            ExternalInvitesStore(
                scope = memberScope,
                dataSource = RealExternalInvitesDataSource(route.channelId, routeOwner, authorityGateway, bridge::log),
                onChannelMutated = { membersChanged ->
                    latestHomeStore?.dispatch(SharedHomeIntent.Refresh)
                    if (membersChanged) latestRefreshMembers()
                },
            )
        }
    val externalInvitesState by externalInvitesStore.state.collectAsState()
    val externalInviteJoint = externalInviteChannel?.type == "joint"
    val externalInviteGated = externalInviteAccessOn && externalInviteEntryGated(serverPlan, externalInviteChannel)
    val externalSlotKey = remember(homeChannels, route.serverId) { externalSlotChannelsKey(homeChannels, route.serverId.value) }
    LaunchedEffect(externalInvitesStore, externalInviteManagementOn) {
        if (externalInviteManagementOn) externalInvitesStore.dispatch(ExternalInvitesIntent.Load)
    }
    LaunchedEffect(externalInvitesStore, externalInviteGated, externalSlotKey) {
        externalInvitesStore.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(externalInviteGated))
    }
    LaunchedEffect(externalInvitesStore, viewerEmail) {
        externalInvitesStore.dispatch(ExternalInvitesIntent.SetOwnEmail(viewerEmail))
    }
    LaunchedEffect(externalInvitesState.effects) {
        val pending = externalInvitesState.effects
        if (pending.isEmpty()) return@LaunchedEffect
        val toastEffects = mutableListOf<ExternalInvitesEffect>()
        pending.forEach { effect ->
            externalInvitesStore.dispatch(ExternalInvitesIntent.ConsumeEffect(effect))
            if (effect == ExternalInvitesEffect.FreeLimitUpgrade) {
                // Web openExternalLimitUpgrade (surface external_invite_dialog); iOS view-only Billing: the plan page.
                if (settingsBillingOffersUpgrade()) {
                    pager.openRaftUpgradeDialog(
                        gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                        storeScope = storeScope,
                        externalContext =
                            externalSlotUpgradeContext(
                                channels = latestHomeChannels,
                                serverId = route.serverId.value,
                                exceptChannelId = route.channelId.value,
                            ),
                    )
                } else {
                    pager.openSettingsBillingPage(storeScope)
                }
            } else {
                toastEffects += effect
            }
        }
        bridge.postExternalInviteToasts(
            effects = toastEffects,
            joint = externalInviteJoint,
            channel = "#$channelName",
            host = viewerServerName,
        )
    }
    val membersLoading =
        !authorityRejected &&
            members.isEmpty() &&
            channelMembersSlice.status in
                setOf(SharedServerMembersLoadStatus.Idle, SharedServerMembersLoadStatus.InFlight)
    val membersFailed =
        authorityRejected ||
            (members.isEmpty() && channelMembersSlice.status == SharedServerMembersLoadStatus.Error)
    val canAddMembers =
        !authorityRejected &&
            channelMembersSlice.canAddMembersKnown &&
            channelMembersSlice.canAddMembers
    val guestConnect =
        rememberThreadMembersGuestConnect(
            route = route,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            projection = addChannelProjection,
            channelName = channelName,
            canAddMembers = canAddMembers,
            authorityRejected = authorityRejected,
            memberConnectAccount = memberConnectAccount,
            onConnectionsChanged = {
                latestHomeStore?.dispatch(SharedHomeIntent.Refresh)
                latestRefreshMembers()
            },
            onServersChanged = { guestServersRevision += 1 },
        )
    val inviteConnect =
        rememberThreadMembersInviteConnect(
            route = route,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            projection = addChannelProjection,
            channelName = channelName,
            dialogOpen = externalInvitesState.dialog != null,
            canMakePrivate = externalInviteChannel?.federateChannels ?: (viewerServerRole == "owner" || viewerServerRole == "admin"),
            memberConnectAccount = memberConnectAccount,
            onConnectionsChanged = {
                latestHomeStore?.dispatch(SharedHomeIntent.Refresh)
                latestRefreshMembers()
                if (externalInviteManagementOn) externalInvitesStore.dispatch(ExternalInvitesIntent.Load)
            },
            onMemberConnectFinished = {
                externalInvitesStore.dispatch(ExternalInvitesIntent.CloseDialog)
            },
        )
    var initialAddApplied by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route.openAddMember, canAddMembers) {
        if (route.openAddMember && canAddMembers && !initialAddApplied) {
            initialAddApplied = true
            addDialogVisible = true
        }
    }

    LaunchedEffect(liveOwner, routeOwner) {
        if (liveOwner != routeOwner) {
            addDialogVisible = false
            clearAddMemberErrorState()
            removalGate = ThreadMemberRemovalGateState()
            roleChange = ChannelMemberRoleChangeState()
        }
    }

    fun openMember(member: RaftThreadMemberItem) {
        val identity = member.memberDetailIdentity() ?: return
        val pageData = route.toMemberDetailPageData(identity, member.sourceServerLabel) ?: return
        memberScope.launch {
            if (authorityGateway.capture(routeOwner) == null) {
                return@launch
            }
            member.toMemberProfile()
                ?.let { profile ->
                    agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(profile))
                }
            pager.openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                onMissingScope = {
                    bridge.toast(unableToOpenProfileLabel)
                    bridge.logWarn(
                        "thread_member_profile_skip reason=missing_shell_scope memberId=${identity.memberId.value} " +
                            "instanceId=${route.instanceId}"
                    )
                }
            )
        }
    }

    /**
     * Opens the profile for an agent we already hold the id for (task #4). `openMember` needs a
     * row from the member list; a just-created agent may not be in the visible slice yet, so the
     * route data is built from the id directly.
     */
    fun openMemberProfile(memberId: MemberId, kind: MemberKind) {
        val identity = MemberDetailIdentity(memberId = memberId, kind = kind)
        val pageData = route.toMemberDetailPageData(identity)
        if (pageData == null) {
            // task #5: this used to `?: return` with no trace, so a broken route building
            // silently looked like "nothing happened" on device.
            bridge.logWarn(
                "thread_member_profile_skip reason=missing_route_data memberId=${memberId.value} " +
                    "instanceId=${route.instanceId}"
            )
            return
        }
        memberScope.launch {
            if (authorityGateway.capture(routeOwner) == null) {
                bridge.logWarn(
                    "thread_member_profile_skip reason=authority_lost memberId=${memberId.value} " +
                        "instanceId=${route.instanceId}"
                )
                return@launch
            }
            bridge.log(
                "thread_member_profile_open memberId=${memberId.value} kind=${kind.name} " +
                    "instanceId=${route.instanceId}"
            )
            pager.openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                onMissingScope = {
                    bridge.toast(unableToOpenProfileLabel)
                    bridge.logWarn(
                        "thread_member_profile_skip reason=missing_shell_scope memberId=${memberId.value} " +
                            "instanceId=${route.instanceId}"
                    )
                }
            )
        }
    }

    fun requestRemoveMember(member: RaftThreadMemberItem) {
        if (authorityRejected || channelMembersSlice.storedStandIn || !member.canRemove) return
        val confirmation =
            threadMemberRemovalConfirmation(
                member = member,
                serverId = route.serverId,
                channelId = route.channelId,
                channelName = channelName
            ) ?: return
        removalGate =
            reduceThreadMemberRemovalGate(
                state = removalGate,
                intent = ThreadMemberRemovalGateIntent.Request(confirmation)
            ).state
    }

    fun confirmRemoveMember() {
        val confirmed =
            reduceThreadMemberRemovalGate(
                state = removalGate,
                intent = ThreadMemberRemovalGateIntent.Confirm(route.serverId, route.channelId)
            )
        removalGate = confirmed.state
        val confirmation = confirmed.execute ?: return
        memberScope.launch {
            when (
                val result =
                    serverMembersStore.removeChannelMember(
                        owner = routeOwner,
                        request = confirmation.request,
                        priority = DataRequestPriority.Foreground
                    )
            ) {
                is DataResult.Content -> {
                    val applied = removalGate.inFlight
                    removalGate =
                        reduceThreadMemberRemovalGate(
                            state = removalGate,
                            intent = ThreadMemberRemovalGateIntent.CompleteSuccess(confirmation),
                        ).state
                    if (applied) {
                        bridge.postChannelMemberRemovedToast(
                            stillInChannel = result.data.stillInChannel,
                            personName = confirmation.personName,
                            stillInChannelStrings = stillInChannelStrings,
                            memberRemovedLabel = memberRemovedLabel,
                        )
                    }
                }
                is DataResult.NoPermission -> {
                    removalGate =
                        reduceThreadMemberRemovalGate(
                            state = removalGate,
                            intent =
                                ThreadMemberRemovalGateIntent.CompleteFailure(
                                    confirmation = confirmation,
                                    failure = ThreadMemberRemovalFailure.NoPermission,
                                    errorCode = result.blocker.code,
                                    serverMessage = result.blocker.serverMessage,
                                    serverCode = result.blocker.serverCode,
                                    traceId = result.blocker.traceId,
                                ),
                        ).state
                }
                is DataResult.Error -> {
                    removalGate =
                        reduceThreadMemberRemovalGate(
                            state = removalGate,
                            intent =
                                ThreadMemberRemovalGateIntent.CompleteFailure(
                                    confirmation = confirmation,
                                    failure = ThreadMemberRemovalFailure.Failed,
                                    errorCode = result.error.code,
                                    serverMessage = result.error.serverMessage,
                                    serverCode = result.error.serverCode,
                                    traceId = result.error.traceId,
                                ),
                        ).state
                }
                is DataResult.Empty -> {
                    removalGate =
                        reduceThreadMemberRemovalGate(
                            state = removalGate,
                            intent =
                                ThreadMemberRemovalGateIntent.CompleteFailure(
                                    confirmation = confirmation,
                                    failure = ThreadMemberRemovalFailure.Failed,
                                    errorCode = result.reason.name,
                                ),
                        ).state
                }
            }
        }
    }

    fun requestRoleAction(member: RaftThreadMemberItem) {
        val action = member.roleAction ?: return
        if (authorityRejected || roleChange.changingKey != null) return
        val nextRole = action.nextRole
        if (nextRole == null) {
            roleChange = roleChange.copy(protectedDemoteName = member.displayName.ifBlank { member.handle })
            return
        }
        val kind = member.kind.parsedMemberKindOrNull() ?: return
        val key = member.memberKey
        roleChange =
            roleChange.copy(
                changingKey = key,
                failed = false,
                failureCode = null,
                failureServerCode = null,
                failureServerMessage = null,
                failureTraceId = null,
                failureIsNoPermission = false,
            )
        bridge.log(
            "thread_member_role_change memberId=${member.id} kind=${kind.name} role=${nextRole.wireValue} " +
                "channelId=${route.channelId.value}"
        )
        memberScope.launch {
            val result =
                serverMembersStore.changeChannelMemberRole(
                    owner = routeOwner,
                    request = ChangeChannelMemberRoleRequest(route.channelId, MemberId(member.id), kind, nextRole),
                    priority = DataRequestPriority.Foreground,
                )
            if (roleChange.changingKey != key) return@launch
            roleChange =
                when (result) {
                    is DataResult.Content ->
                        roleChange.copy(
                            changingKey = null,
                            failed = false,
                            failureCode = null,
                            failureServerCode = null,
                            failureServerMessage = null,
                            failureTraceId = null,
                            failureIsNoPermission = false,
                        )
                    is DataResult.Error ->
                        roleChange.copy(
                            changingKey = null,
                            failed = true,
                            failureCode = result.error.code,
                            failureServerCode = result.error.serverCode,
                            failureServerMessage = result.error.serverMessage,
                            failureTraceId = result.error.traceId,
                            failureIsNoPermission = false,
                        )
                    is DataResult.NoPermission ->
                        roleChange.copy(
                            changingKey = null,
                            failed = true,
                            failureCode = result.blocker.code,
                            failureServerCode = result.blocker.serverCode,
                            failureServerMessage = result.blocker.serverMessage,
                            failureTraceId = result.blocker.traceId,
                            failureIsNoPermission = true,
                        )
                    is DataResult.Empty ->
                        roleChange.copy(
                            changingKey = null,
                            failed = true,
                            failureCode = result.reason.name,
                            failureServerCode = null,
                            failureServerMessage = null,
                            failureTraceId = null,
                            failureIsNoPermission = false,
                        )
                }
        }
    }

    // botiverse/slock #11273: App Bots in the roster and the Add Members panel carry the Bot
    // badge and no presence line.
    val memberAppBotResolver = remember(serverMembersStore, route.serverId) {
        raftMemberAppBotResolver(serverMembersStore, route.serverId.value)
    }
    val runtimeUsageAccess = rememberShellRuntimeAccountUsageAccess()
    CompositionLocalProvider(
        LocalMembersRuntimeAccountUsageAccess provides runtimeUsageAccess,
        LocalRaftMemberAppBotResolver provides memberAppBotResolver,
        LocalRaftViewerServerLogos provides viewerServerLogos,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        ThreadMembersPageContent(
            members = members, filteredMembers = filteredMembers, filteredExternal = filteredExternal,
            externalMemberCount = channelMembersSlice.externalMembers.size, candidates = candidates,
            rosterQuery = rosterQuery, onQueryChange = { rosterQuery = it },
            agentsExpanded = agentsExpanded, humansExpanded = humansExpanded, externalExpanded = externalExpanded,
            onAgentsExpandedChange = { agentsExpanded = it }, onHumansExpandedChange = { humansExpanded = it },
            onExternalExpandedChange = { externalExpanded = it },
            addDialogVisible = addDialogVisible,
            onAddVisibleChange = { visible ->
                if (visible) {
                    clearAddMemberErrorState()
                    addDialogVisible = true
                } else {
                    dismissAddMember()
                }
            },
            addMemberStatus = addMemberStatus, addMemberErrorCode = addMemberErrorCode,
            addMemberErrorServerCode = addMemberErrorServerCode,
            addMemberErrorMessage = addMemberErrorMessage,
            addMemberErrorTraceId = addMemberErrorTraceId,
            removalGate = removalGate,
            membersFailed = membersFailed, membersLoading = membersLoading,
            refreshing = channelMembersSlice.status == SharedServerMembersLoadStatus.InFlight && members.isNotEmpty(),
            canAddMembers = canAddMembers, agentStatusResolver = agentStatusResolver, memberAvatarResolver = memberAvatarResolver,
            guestConnectEntry = guestConnect.available,
            onGuestConnect = guestConnect.onOpen,
            onBack = { router.closePage() },
            onRefresh = {
                refreshMembers()
                if (externalInviteManagementOn) externalInvitesStore.dispatch(ExternalInvitesIntent.Load)
            },
            onOpenMember = ::openMember, onRemoveMember = ::requestRemoveMember,
            roleChange = roleChange, onRoleAction = ::requestRoleAction,
            canCreateAgents = canCreateAgents,
            localServerId = route.serverId.value,
            channelName = channelName,
            // Web jointMemberGroups (#12853): a joint channel's roster as "Members of {server}" / "Other servers".
            channelKind = channelKind,
            viewerServerName = viewerServerName,
            externalInvites =
                ExternalInvitesSection(
                    state = externalInvitesState,
                    access = externalInviteAccessOn,
                    management = externalInviteManagementOn,
                    serverName = viewerServerName,
                    nowEpochMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis(),
                    onRetry = { externalInvitesStore.dispatch(ExternalInvitesIntent.Load) },
                    onResend = { externalInvitesStore.dispatch(ExternalInvitesIntent.Resend(it)) },
                    onWithdraw = { externalInvitesStore.dispatch(ExternalInvitesIntent.RequestWithdraw(it)) },
                    onOpenInvite = {
                        if (externalInviteAccessOn) {
                            // Web ChannelMembers: keep Add Member mounted behind the invite sheet so
                            // cancelling returns straight to Add Member with staged state intact.
                            externalInvitesStore.dispatch(
                                ExternalInvitesIntent.RequestOpen(
                                    joint = externalInviteJoint,
                                    gated = externalInviteGated,
                                )
                            )
                        }
                    },
                ),
            // The two rows (web sidebar Agents "+" order: Create Agent, Create External Agent) open
            // that create dialog directly, the channel preselected.
            onCreateAgent = { name, mode ->
                if (canCreateAgents && liveOwner == routeOwner) {
                    RaftProductEvents.agentCreateOpened(RaftAgentCreateEntry.ChannelMembers)
                    createAgentName = name
                    createAgentMode = mode
                }
            },
            onSubmit = { selectedMembers ->
                val profiles = selectedMembers.mapNotNull { it.toMemberProfile() }
                if (profiles.isEmpty() || profiles.size != selectedMembers.size) return@ThreadMembersPageContent
                if (addMemberStatus == "adding") return@ThreadMembersPageContent
                addMemberStatus = "adding"
                addMemberErrorCode = null
                addMemberErrorServerCode = null
                addMemberErrorMessage = null
                addMemberErrorTraceId = null
                memberScope.launch {
                    val result =
                        serverMembersStore.addChannelMembersBatch(
                            owner = routeOwner,
                            request =
                                build.raft.app.data.AddChannelMembersBatchRequest(
                                    channelId = route.channelId,
                                    userIds = profiles.filter { it.kind != MemberKind.Agent }.map { it.id },
                                    agentIds = profiles.filter { it.kind == MemberKind.Agent }.map { it.id }
                                ),
                            priority = DataRequestPriority.Foreground
                        )
                    if (addMemberStatus != "adding") return@launch
                    when (result) {
                        is DataResult.Content -> {
                            clearAddMemberErrorState()
                            addDialogVisible = false
                        }
                        is DataResult.NoPermission -> {
                            addMemberStatus = "no_permission"
                            addMemberErrorCode = result.blocker.code
                            addMemberErrorServerCode = result.blocker.serverCode
                            addMemberErrorMessage = result.blocker.serverMessage
                            addMemberErrorTraceId = result.blocker.traceId
                        }
                        is DataResult.Error -> {
                            addMemberStatus = "failed"
                            addMemberErrorCode = result.error.code
                            addMemberErrorServerCode = result.error.serverCode
                            addMemberErrorMessage = result.error.serverMessage
                            addMemberErrorTraceId = result.error.traceId
                        }
                        is DataResult.Empty -> {
                            addMemberStatus = "failed"
                            addMemberErrorCode = result.reason.name
                            addMemberErrorServerCode = null
                            addMemberErrorMessage = null
                            addMemberErrorTraceId = null
                        }
                    }
                }
            }
        )
        RaftBackHandler(enabled = addDialogVisible && externalInvitesState.dialog == null, label = "thread_members_inline_add") {
            dismissAddMember()
        }
        externalInvitesState.dialog?.let { dialog ->
            ExternalInviteSheet(
                dialog = dialog,
                serverName = viewerServerName,
                // Web AddExternalMemberDialog `willBecomePrivate`: a public channel becomes private with the first invite.
                willBecomePrivate = externalInviteChannel?.type == "channel",
                joint = externalInviteJoint,
                channelSlotsLeft = externalInvitesState.slotsLeft,
                channelName = channelName,
                // Web AddExternalMemberDialog `serversHistoryHint`: a channel not joint yet that has messages.
                serversHistoryHint = !externalInviteJoint && channelProjections[route.channelId.value]?.hasMessages == true,
                onConnectServers = if (inviteConnect.available) inviteConnect.onOpen else null,
                onDismiss = { externalInvitesStore.dispatch(ExternalInvitesIntent.CloseDialog) },
                onTextChange = { externalInvitesStore.dispatch(ExternalInvitesIntent.SetText(it)) },
                onServersChange = { externalInvitesStore.dispatch(ExternalInvitesIntent.SetServers(it)) },
                onNext = { externalInvitesStore.dispatch(ExternalInvitesIntent.Next) },
                onBack = { externalInvitesStore.dispatch(ExternalInvitesIntent.Back) },
                onSend = { externalInvitesStore.dispatch(ExternalInvitesIntent.Send(makePrivate = externalInviteChannel?.type == "channel")) },
                onUpgrade = {
                    externalInvitesStore.dispatch(ExternalInvitesIntent.CloseDialog)
                    // Web openUpgradeDialog("external_channel_free_limit"); iOS view-only Billing: the plan page.
                    if (settingsBillingOffersUpgrade()) {
                        pager.openRaftUpgradeDialog(
                            gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                            storeScope = storeScope,
                            externalContext =
                                externalSlotUpgradeContext(
                                    channels = latestHomeChannels,
                                    serverId = route.serverId.value,
                                    exceptChannelId = route.channelId.value,
                                ),
                        )
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                },
            )
        }
        if (inviteConnect.showOverlay) {
            JointConnectedServersOverlays(
                holder = inviteConnect.holder,
                onBack = { inviteConnect.holder.store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                onUpgradeServer = { targetServerId ->
                    externalInvitesStore.dispatch(ExternalInvitesIntent.CloseDialog)
                    if (settingsBillingOffersUpgrade()) {
                        pager.openRaftUpgradeDialog(
                            gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                            storeScope = storeScope,
                            targetServerId = targetServerId,
                        )
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                },
            )
        }
        externalInvitesState.withdrawConfirm?.let { invite ->
            ExternalInviteWithdrawDialog(
                invite = invite,
                busy = externalInvitesState.withdrawBusy,
                failure = externalInvitesState.withdrawFailure,
                joint = externalInviteJoint,
                onDismiss = { externalInvitesStore.dispatch(ExternalInvitesIntent.DismissWithdraw) },
                onConfirm = { externalInvitesStore.dispatch(ExternalInvitesIntent.ConfirmWithdraw) },
            )
        }
        createAgentName?.let { initialName ->
            ChannelMembersCreateAgentDialog(
                owner = routeOwner, channelId = route.channelId, initialName = initialName, mode = createAgentMode,
                channelName = channelName,
                authority = authorityGateway, computersStore = sharedStores.computersStore(storeScope),
                formStore = sharedStores.createAgentFormStore(storeScope), membersStore = serverMembersStore,
                onDismiss = { createAgentName = null; refreshMembers() },
                onJoined = { createAgentName = null; addDialogVisible = false; refreshMembers() },
                // task #4 (Hands 1c6885d8): a freshly created external agent is useless until
                // it is configured, so land the user on its profile instead of dropping them
                // back into the member list. Managed agents keep the existing behaviour.
                onCreated = { memberId ->
                    if (createAgentMode == MembersCreateAgentMode.External) {
                        openMemberProfile(memberId, MemberKind.Agent)
                    }
                },
                onAddComputer = { pager.openSettingsComputersPage(storeScope, openAddComputer = true) },
                serverPlan = serverPlan,
                // Web CreateAgentDialog `seat_limit_agent_create`: Free → upgrade, Pro → add seats.
                onUpgrade = { pager.openRaftUpgradeDialog(RaftUpgradeGate.SeatLimitAgentCreate, storeScope) },
            )
        }
        roleChange.protectedDemoteName?.let { name ->
            RaftDesignSystemTheme {
                ChannelMemberProtectedDemoteDialog(
                    name = name,
                    onDismiss = { roleChange = roleChange.copy(protectedDemoteName = null) },
                )
            }
        }
        if (guestConnect.showOverlays) {
            JointConnectedServersOverlays(
                holder = guestConnect.holder,
                onUpgradeServer = { targetServerId ->
                    if (settingsBillingOffersUpgrade()) {
                        pager.openRaftUpgradeDialog(
                            gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                            storeScope = storeScope,
                            targetServerId = targetServerId,
                        )
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                },
            )
        }
        removalGate.pending?.let { confirmation ->
            ThreadRemoveMemberConfirmationDialog(
                confirmation = confirmation,
                removing = removalGate.inFlight,
                errorMessage =
                    threadMemberRemovalFailureMessage(
                        state = removalGate,
                        noPermissionFallback = stringResource(Res.string.thread_members_remove_no_permission),
                        genericFallback = stringResource(Res.string.thread_members_remove_failed),
                        strings = errorStrings,
                    ),
                errorTraceId = removalGate.traceId,
                onDismiss = {
                    removalGate =
                        reduceThreadMemberRemovalGate(
                            state = removalGate,
                            intent = ThreadMemberRemovalGateIntent.Dismiss
                        ).state
                },
                onConfirm = ::confirmRemoveMember
            )
        }
    }
    }
}






private data class ThreadMembersInviteConnectHandle(
    val available: Boolean,
    val showOverlay: Boolean,
    val onOpen: () -> Unit,
    val holder: JointConnectedServersHolder,
)

/**
 * Web `AddExternalMemberDialog`'s quiet footer link "Connect your servers" (slock #16643/#16661):
 * opens Connect your servers OVER [ExternalInviteSheet] (pressing Back keeps typed emails intact).
 * Available when the channel is private, joint, or public (`channel`) and the viewer has at least
 * one eligible server (`memberConnectRowsOncePrivate` for public channels).
 */
@Composable
private fun rememberThreadMembersInviteConnect(
    route: ThreadMembersPageRoute,
    routeOwner: AuthenticatedRouteOwner,
    liveOwner: AuthenticatedRouteOwner?,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    projection: SharedConversationDirectoryProjection?,
    channelName: String,
    dialogOpen: Boolean,
    canMakePrivate: Boolean,
    memberConnectAccount: MemberConnectAccount?,
    onConnectionsChanged: () -> Unit,
    onMemberConnectFinished: () -> Unit,
): ThreadMembersInviteConnectHandle {
    val isJoint = projection?.let { it.conversationKind == ConversationKind.Joint || it.channelKind.equals("joint", ignoreCase = true) } == true
    val isPrivate = !isJoint && projection?.channelKind.equals("private", ignoreCase = true)
    val isPublic = !isJoint && !isPrivate && projection?.channelKind.equals("channel", ignoreCase = true)
    val seedConnections =
        remember(projection, route.channelId, route.serverId, channelName, memberConnectAccount, isJoint, isPrivate, isPublic) {
            projection?.takeIf { isJoint || isPrivate || isPublic }?.let { proj ->
                val jointRole = JointChannelRole.fromWire(proj.jointRole)
                val servers =
                    if (isJoint) {
                        proj.jointServers
                            .map { ref ->
                                JointChannelServer(
                                    serverId = ref.id,
                                    name = ref.name,
                                    slug = ref.slug,
                                    active = true,
                                    isCurrentServer = ref.id == route.serverId.value,
                                    plan = null,
                                    role = if (jointRole == JointChannelRole.Host && ref.id == route.serverId.value) JointChannelRole.Host else null,
                                    avatarUrl = ref.avatarUrl,
                                )
                            }
                            .ifEmpty {
                                listOf(
                                    JointChannelServer(
                                        serverId = route.serverId.value,
                                        name = memberConnectAccount?.currentServerName.orEmpty(),
                                        slug = memberConnectAccount?.currentServerSlug.orEmpty(),
                                        active = true,
                                        isCurrentServer = true,
                                        plan = null,
                                        role = jointRole,
                                    )
                                )
                            }
                    } else {
                        emptyList()
                    }
                JointChannelConnections(
                    channelId = route.channelId,
                    channelName = channelName,
                    isJoint = isJoint,
                    archived = proj.archived,
                    servers = servers,
                    hasCurrentServerPendingInvites = false,
                    jointRole = jointRole,
                    federateChannels = proj.channelCapabilities?.federateChannels,
                    isPrivate = isPrivate,
                    joined = proj.joined,
                )
            }
        }
    val settingsRoute = remember(route.serverId, route.channelId) { ChannelSettingsRoute(route.serverId, route.channelId) }
    val holder =
        rememberJointConnectedServers(
            route = settingsRoute,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            active = dialogOpen && isJoint,
            seedConnections = seedConnections,
            onConnectionsChanged = onConnectionsChanged,
            onMemberConnectFinished = onMemberConnectFinished,
        )
    val state by holder.store.state.collectAsState()
    val available = (isJoint || isPrivate || isPublic) && state.memberConnectInviteRows.isNotEmpty()
    val publicConnect = if (isPublic) PublicChannelConnect(canMakePrivate = canMakePrivate) else null
    return ThreadMembersInviteConnectHandle(
        available = available,
        showOverlay = state.memberConnectSheet != null,
        onOpen = {
            holder.store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = publicConnect))
        },
        holder = holder,
    )
}

private data class ThreadMembersGuestConnectHandle(
    val available: Boolean,
    val showOverlays: Boolean,
    val onOpen: () -> Unit,
    val holder: JointConnectedServersHolder,
)

/**
 * Web `useGuestConnectDialog` on the members page (slock #16375/#16389): for a Guest of the host
 * server who cannot add members, loads conversion status + channel connections and opens Connect
 * your servers with the Guest helper line and Create a server and connect.
 */
@Composable
private fun rememberThreadMembersGuestConnect(
    route: ThreadMembersPageRoute,
    routeOwner: AuthenticatedRouteOwner,
    liveOwner: AuthenticatedRouteOwner?,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    projection: SharedConversationDirectoryProjection?,
    channelName: String,
    canAddMembers: Boolean,
    authorityRejected: Boolean,
    memberConnectAccount: MemberConnectAccount?,
    onConnectionsChanged: () -> Unit,
    onServersChanged: () -> Unit,
): ThreadMembersGuestConnectHandle {
    val active = !authorityRejected && !canAddMembers && memberConnectAccount?.currentServerRole == "guest" && projection?.archived != true
    val conversionFromStore =
        rememberChannelConversionInProgress(
            sharedStores = sharedStores,
            storeScope = storeScope,
            serverId = route.serverId,
            channelId = route.channelId,
            active = active,
        )
    val seedConnections =
        remember(projection, route.channelId, route.serverId, channelName, memberConnectAccount, conversionFromStore) {
            projection?.let { proj ->
                val isJoint = proj.conversationKind == ConversationKind.Joint || proj.channelKind.equals("joint", ignoreCase = true)
                val isPrivate = !isJoint && proj.channelKind.equals("private", ignoreCase = true)
                val jointRole = JointChannelRole.fromWire(proj.jointRole)
                val servers =
                    if (isJoint) {
                        proj.jointServers
                            .map { ref ->
                                JointChannelServer(
                                    serverId = ref.id,
                                    name = ref.name,
                                    slug = ref.slug,
                                    active = true,
                                    isCurrentServer = ref.id == route.serverId.value,
                                    plan = null,
                                    role = if (jointRole == JointChannelRole.Host && ref.id == route.serverId.value) JointChannelRole.Host else null,
                                    avatarUrl = ref.avatarUrl,
                                )
                            }
                            .ifEmpty {
                                listOf(
                                    JointChannelServer(
                                        serverId = route.serverId.value,
                                        name = memberConnectAccount?.currentServerName.orEmpty(),
                                        slug = memberConnectAccount?.currentServerSlug.orEmpty(),
                                        active = true,
                                        isCurrentServer = true,
                                        plan = null,
                                        role = jointRole,
                                    )
                                )
                            }
                    } else {
                        emptyList()
                    }
                JointChannelConnections(
                    channelId = route.channelId,
                    channelName = channelName,
                    isJoint = isJoint,
                    archived = proj.archived,
                    servers = servers,
                    hasCurrentServerPendingInvites = false,
                    jointRole = jointRole,
                    federateChannels = proj.channelCapabilities?.federateChannels,
                    isPrivate = isPrivate,
                    joined = proj.joined,
                    conversionInProgress = conversionFromStore,
                )
            }
        }
    val settingsRoute = remember(route.serverId, route.channelId) { ChannelSettingsRoute(route.serverId, route.channelId) }
    val holder =
        rememberJointConnectedServers(
            route = settingsRoute,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            active = active && !conversionFromStore,
            seedConnections = seedConnections,
            onConnectionsChanged = onConnectionsChanged,
            onServersChanged = onServersChanged,
        )
    val state by holder.store.state.collectAsState()
    val conversionInProgress = conversionFromStore || state.connections?.conversionInProgress == true
    val available = active && !conversionInProgress && state.guestConnectEntryAvailable
    return ThreadMembersGuestConnectHandle(
        available = available,
        showOverlays = available || state.memberConnectSheet != null,
        onOpen = { holder.store.dispatch(JointChannelConnectionsIntent.OpenGuestMemberConnect) },
        holder = holder,
    )
}

/** The production roster renderer; visual fixtures supply typed state without route payloads. */
@Composable
internal fun ThreadMembersPageContent(
    members: List<RaftThreadMemberItem>,
    filteredMembers: List<RaftThreadMemberItem>,
    filteredExternal: List<build.raft.app.model.ChannelExternalMemberProfile>,
    externalMemberCount: Int,
    candidates: List<RaftThreadMemberItem>,
    rosterQuery: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    agentsExpanded: Boolean,
    humansExpanded: Boolean,
    externalExpanded: Boolean,
    onAgentsExpandedChange: (Boolean) -> Unit,
    onHumansExpandedChange: (Boolean) -> Unit,
    onExternalExpandedChange: (Boolean) -> Unit,
    addDialogVisible: Boolean,
    onAddVisibleChange: (Boolean) -> Unit,
    addMemberStatus: String,
    addMemberErrorCode: String?,
    addMemberErrorServerCode: String? = null,
    addMemberErrorMessage: String? = null,
    addMemberErrorTraceId: String? = null,
    removalGate: ThreadMemberRemovalGateState,
    membersFailed: Boolean,
    membersLoading: Boolean,
    refreshing: Boolean,
    canAddMembers: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMember: (RaftThreadMemberItem) -> Unit,
    onRemoveMember: (RaftThreadMemberItem) -> Unit,
    onSubmit: (List<RaftThreadMemberItem>) -> Unit,
    canCreateAgents: Boolean = false,
    onCreateAgent: (String, MembersCreateAgentMode) -> Unit = { _, _ -> },
    localServerId: String? = null,
    channelName: String = "",
    externalInvites: ExternalInvitesSection? = null,
    roleChange: ChannelMemberRoleChangeState = ChannelMemberRoleChangeState(),
    onRoleAction: (RaftThreadMemberItem) -> Unit = {},
    /** The channel's type (web `currentChannel.type`): a joint channel's roster groups by the viewed server. */
    channelKind: String? = null,
    /** The viewed server's name, for "Members of {server}" (web `currentServerName`). */
    viewerServerName: String = "",
    /** Guest "Connect your servers" entry in Add Member's place (slock #16375/#16389). */
    guestConnectEntry: Boolean = false,
    onGuestConnect: () -> Unit = {},
) {
    // A joint channel's two groups fold like the other sections and start open (web
    // MemberPageSection `defaultOpen`); only this page shows them, so their state stays here.
    var viewerServerExpanded by remember { mutableStateOf(true) }
    var otherServersExpanded by remember { mutableStateOf(true) }
    val onViewerServerExpandedChange: (Boolean) -> Unit = { viewerServerExpanded = it }
    val onOtherServersExpandedChange: (Boolean) -> Unit = { otherServersExpanded = it }
    val rosterListState = rememberLazyListState()
    // artin task #22: a pulled refresh shows the pull indicator only, not the header spinner too.
    val rosterPullState = rememberRaftStorePullRefreshState()
    // task #175: the page draws under the system navigation bar; whichever
    // element sits last (the Add Member bar, else the roster) keeps clear of it.
    val bottomSafeArea = raftBottomSafeAreaPadding()
    // Web ChannelMembers presentation="page" (ChannelMembers.tsx, ChannelMemberList.tsx), drawn
    // with compose/designsystem parts (ChannelMembersPage.kt).
    RaftDesignSystemTheme {
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        // In Add Member the header's Back returns to the roster (web `add-member-back`): a step inside
        // the page, so in the wide layout's profile column it has Back also on the cell's first page,
        // and the cell's Close still ends the head (raftSidePaneHeadButtons).
        val sidePane = LocalRaftSidePanePage.current
        val headSidePane =
            remember(sidePane, addDialogVisible) {
                if (sidePane != null && addDialogVisible) RaftSidePanePage(sidePane.depth + 1, sidePane.close) else sidePane
            }
        CompositionLocalProvider(LocalRaftSidePanePage provides headSidePane) {
            ChannelMembersPageHeader(
                title = stringResource(if (addDialogVisible) Res.string.thread_action_add_member else Res.string.thread_members_heading),
                count = (members.size + externalMemberCount).takeIf { !addDialogVisible && !membersLoading },
                refreshing = rosterPullState.headerRefreshing(refreshing),
                onBack = {
                    if (addDialogVisible) {
                        if (addMemberStatus != "adding") onAddVisibleChange(false)
                    } else onBack()
                },
            )
        }
        if (addDialogVisible && canAddMembers) {
            ThreadAddMemberPanel(
                members = members, candidates = candidates,
                localServerId = localServerId, channelName = channelName,
                canAddMembers = canAddMembers, agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver, addMemberStatus = addMemberStatus,
                addMemberErrorCode = addMemberErrorCode,
                addMemberErrorServerCode = addMemberErrorServerCode,
                addMemberErrorMessage = addMemberErrorMessage,
                addMemberErrorTraceId = addMemberErrorTraceId,
                bottomSafeArea = bottomSafeArea,
                onSubmitBatch = onSubmit,
                canCreateAgents = canCreateAgents, onCreateAgent = onCreateAgent,
                externalInviteEntry =
                    externalInvites?.takeIf { it.access }?.let { section ->
                        {
                            ExternalInviteEntryRow(
                                serverName = section.serverName,
                                enabled = addMemberStatus != "adding",
                                onClick = section.onOpenInvite,
                                loading = section.state.openingEntry,
                            )
                        }
                    },
            )
        } else {
        val errorStrings = localizedErrorPresentationStrings()
        ChannelMembersSearchBar(query = rosterQuery, onQueryChange = onQueryChange)
        val removalErrorMessage =
            threadMemberRemovalFailureMessage(
                state = removalGate,
                noPermissionFallback = stringResource(Res.string.thread_members_remove_no_permission),
                genericFallback = stringResource(Res.string.thread_members_remove_failed),
                strings = errorStrings,
            )
        if (removalErrorMessage != null) {
            ChannelMembersWarning(removalErrorMessage)
            RaftTraceIdNote(removalGate.traceId, Modifier.padding(horizontal = 12.dp))
        }
        if (roleChange.failed) {
            ChannelMemberRoleErrorBanner(
                traceId = roleChange.failureTraceId,
                message =
                    channelMemberRoleFailureMessage(
                        state = roleChange,
                        fallback = stringResource(Res.string.channel_members_update_role_failed),
                        strings = errorStrings,
                    ),
            )
        }
        val showBottomActionBar = !membersLoading && (canAddMembers || externalInvites?.access == true || guestConnectEntry)
        // Pull-to-refresh reloads the roster through the same path as the error state's Retry.
        RaftStorePullToRefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            enabled = !membersLoading,
            pullState = rosterPullState,
        ) {
        val visibleInvites = externalInvites?.visibleInvites(rosterQuery.text).orEmpty()
        LazyColumn(
            state = rosterListState,
            // PROTOTYPE (native nav bar): drives the host's large title.
            modifier = Modifier.fillMaxSize().raftNativeNavBarPrimaryList(rosterListState),
            contentPadding = PaddingValues(bottom = 12.dp + if (showBottomActionBar) 0.dp else bottomSafeArea),
        ) {
            // Web design v5.0 A4: the invites couldn't be read and none is listed.
            if (externalInvites?.loadFailedShown == true && !membersLoading) {
                item(key = "external-invites:failed") {
                    ExternalInvitesLoadFailedBanner(externalInvites.state.loadFailure?.traceId, externalInvites.onRetry)
                }
            }
            if (membersFailed) {
                item {
                    ChannelMembersEmptyState(
                        icon = RaftLucideIconName.TriangleAlert,
                        title = stringResource(Res.string.common_error),
                        actionLabel = stringResource(Res.string.common_retry),
                        onAction = onRefresh,
                    )
                }
            } else if (membersLoading) {
                item { ChannelMembersLoading() }
            } else if (filteredMembers.isEmpty() && filteredExternal.isEmpty() && visibleInvites.isEmpty()) {
                item {
                    val query = rosterQuery.text.trim()
                    val hasAnyRows = members.isNotEmpty() || externalMemberCount > 0 || externalInvites?.visibleInvites("")?.isNotEmpty() == true
                    if (query.isNotEmpty() && hasAnyRows) {
                        ChannelMembersEmptyState(
                            icon = RaftLucideIconName.Search,
                            title = stringResource(Res.string.thread_members_no_matches, query),
                        )
                    } else {
                        ChannelMembersEmptyState(
                            icon = RaftLucideIconName.Users,
                            title = stringResource(Res.string.thread_members_empty),
                        )
                    }
                }
            } else {
                // Web design v5.0 A4: open external invites first, above Humans, for who can manage them.
                if (externalInvites != null && visibleInvites.isNotEmpty()) {
                    item(key = "section:external-invites") {
                        RaftSidebarSectionHeader(
                            title = stringResource(Res.string.channel_external_invite_invited), count = visibleInvites.size,
                            expanded = true, onExpandedChange = {},
                            countSeparator = " · ",
                        )
                    }
                    items(visibleInvites.size, key = { "external-invite:${visibleInvites[it].id}" }) { index ->
                        val invite = visibleInvites[index]
                        ExternalInviteRow(
                            invite = invite,
                            nowEpochMillis = externalInvites.nowEpochMillis,
                            canResend = externalInvites.access,
                            busy = invite.id in externalInvites.state.rowBusy,
                            divider = index > 0,
                            onResend = { externalInvites.onResend(invite.id) },
                            onWithdraw = { externalInvites.onWithdraw(invite.id) },
                        )
                    }
                }
                // Web member page order: Humans, Agents, then bridge participants; a joint channel
                // lists "Members of {server}" and "Other servers" in place of Humans and Agents
                // (ThreadMembersJointGroups.kt, web jointMemberGroups #12853).
                val groupedByViewerServer = threadMembersGroupsByViewerServer(channelKind, members)
                threadMembersRosterSections(channelKind, members, filteredMembers, filteredExternal).forEach { section ->
                    when (section) {
                        is ThreadMembersRosterSection.Members -> {
                            val rows = section.members
                            val expanded =
                                when (section.group) {
                                    ThreadMembersRosterGroup.Humans -> humansExpanded
                                    ThreadMembersRosterGroup.Agents -> agentsExpanded
                                    ThreadMembersRosterGroup.ViewerServer -> viewerServerExpanded
                                    ThreadMembersRosterGroup.OtherServers -> otherServersExpanded
                                }
                            val onExpandedChange =
                                when (section.group) {
                                    ThreadMembersRosterGroup.Humans -> onHumansExpandedChange
                                    ThreadMembersRosterGroup.Agents -> onAgentsExpandedChange
                                    ThreadMembersRosterGroup.ViewerServer -> onViewerServerExpandedChange
                                    ThreadMembersRosterGroup.OtherServers -> onOtherServersExpandedChange
                                }
                            item(key = section.key) {
                                RaftSidebarSectionHeader(
                                    title = threadMembersRosterGroupTitle(section.group, viewerServerName), count = rows.size,
                                    expanded = expanded, onExpandedChange = onExpandedChange,
                                    countSeparator = " · ",
                                )
                            }
                            if (expanded) items(rows.size, key = { rows[it].memberKey }) { index ->
                                ThreadMembersPagerRow(
                                    member = rows[index],
                                    divider = index > 0,
                                    agentStatusResolver = agentStatusResolver,
                                    memberAvatarResolver = memberAvatarResolver,
                                    removing = removalGate.inFlight && removalGate.pending?.memberRowId == rows[index].id,
                                    onOpen = onOpenMember,
                                    onRemove = onRemoveMember,
                                    roleChanging = roleChange.changingKey == rows[index].memberKey,
                                    onRoleAction = onRoleAction,
                                    alsoInSpokenAsSentence = groupedByViewerServer,
                                )
                            }
                        }
                        is ThreadMembersRosterSection.SlackParticipants -> {
                            val participants = section.participants
                            item(key = section.key) {
                                RaftSidebarSectionHeader(
                                    title = stringResource(Res.string.thread_members_external), count = participants.size,
                                    expanded = externalExpanded, onExpandedChange = onExternalExpandedChange,
                                    countSeparator = " · ",
                                )
                            }
                            if (externalExpanded) items(participants.size, key = { "external:${participants[it].id}" }) { index ->
                                val member = participants[index]
                                // SSOT audit: the live time context (as rememberRaftAgentStatusIndicatorState
                                // reads it), so the lime/cyan boundary moves on its minute tick.
                                val now = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
                                val handlesLine = member.handles.joinToString(" · ") { "@${it.removePrefix("@")}" }
                                ChannelMembersRow(
                                    title = member.displayName,
                                    subtitle = handlesLine,
                                    avatarWireValue = member.avatarUrl, avatarLabel = member.avatarLabel,
                                    avatarKind = RaftAvatarKind.Labeled,
                                    cacheSubject = null, cacheServerId = null,
                                    statusColor = member.lastSeenAt?.let { seen ->
                                        if (isExternalAgentSeenOnline(seen, now)) RaftColor.AccentLime else RaftColor.AccentCyan
                                    },
                                    badges = listOfNotNull(
                                        member.provider?.takeIf { it.isNotBlank() }?.let { ChannelMemberBadge(it, ChannelMemberBadgeKind.Provider) }
                                    ),
                                    divider = index > 0,
                                    // A long press copies the handle line as shown ("@a", "@a · @b").
                                    subtitleCopyText = handlesLine,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        // Web ChannelMembers: hidden while the roster loads; offers Add Member and (when
        // externalInviteAccess is on) the direct Invite people outside {server} button.
        if (showBottomActionBar) {
            val externalEntry = externalInvites?.takeIf { it.access }
            ChannelMembersAddBar(
                enabled = addMemberStatus != "adding" && (candidates.isNotEmpty() || canCreateAgents),
                bottomSafeArea = bottomSafeArea,
                onClick = { onAddVisibleChange(!addDialogVisible) },
                showAddMember = canAddMembers,
                showGuestConnect = !canAddMembers && guestConnectEntry,
                onGuestConnect = onGuestConnect,
                inviteOutsideServerName = externalEntry?.serverName,
                inviteOutsideLoading = externalEntry?.state?.openingEntry == true,
                onInviteOutside = externalEntry?.onOpenInvite,
            )
        }
        }
    }
    }
}

/**
 * A roster section's heading: Humans / Agents, or a joint channel's "Members of {server}" /
 * "Other servers" (web ViewerServerGroups.tsx `useGroupLabel`).
 */
@Composable
private fun threadMembersRosterGroupTitle(group: ThreadMembersRosterGroup, viewerServerName: String): String =
    when (group) {
        ThreadMembersRosterGroup.Humans -> stringResource(Res.string.thread_members_humans)
        ThreadMembersRosterGroup.Agents -> stringResource(Res.string.thread_members_agents)
        ThreadMembersRosterGroup.ViewerServer -> stringResource(Res.string.channel_members_group_viewer_server, viewerServerName)
        ThreadMembersRosterGroup.OtherServers -> stringResource(Res.string.channel_members_group_other_servers)
    }

@Composable
private fun ThreadRemoveMemberConfirmationDialog(
    confirmation: ThreadMemberRemovalConfirmation,
    removing: Boolean,
    errorMessage: String?,
    errorTraceId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.thread_members_remove_confirm_title),
        onDismiss = onDismiss,
        dismissEnabled = !removing,
        dismissOnOutsideTap = false,
        minWidth = 320.dp,
        maxWidth = 384.dp,
    ) {
        RaftUiBanner(
            status = RaftUiBannerStatus.Destructive,
            title =
                stringResource(
                    Res.string.thread_members_remove_confirm_question,
                    confirmation.memberLabel,
                    confirmation.channelName
                ),
            description = stringResource(Res.string.thread_members_remove_confirm_detail),
            size = RaftUiBannerSize.Medium,
            iconName = RaftLucideIconName.TriangleAlert
        )
        errorMessage?.let {
            Text(
                text = it,
                style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.DangerStrong),
                color = RaftColor.DangerStrong
            )
            RaftTraceIdNote(errorTraceId)
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                enabled = !removing,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = if (removing) {
                        stringResource(Res.string.thread_members_removing)
                    } else {
                        stringResource(Res.string.thread_members_remove_confirm)
                    },
                onClick = onConfirm,
                enabled = !removing,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.UserMinus) },
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg
            )
        }
    }
}

@Composable
internal fun ThreadMembersPagerRow(
    member: RaftThreadMemberItem,
    divider: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    removing: Boolean,
    onOpen: (RaftThreadMemberItem) -> Unit,
    onRemove: (RaftThreadMemberItem) -> Unit,
    roleChanging: Boolean = false,
    onRoleAction: (RaftThreadMemberItem) -> Unit = {},
    /**
     * A roster grouped by the viewed server ([threadMembersGroupsByViewerServer]): a person's
     * "Also in …" is spoken as "{name} is also a member of {servers}" (web `JointServerBadges`
     * `sourceOf`, given to people's rows only).
     */
    alsoInSpokenAsSentence: Boolean = false,
) {
    // web `ChannelMemberRow` / `AgentActivityInfo`: an App Bot has no presence (it would read
    // "Offline" forever); the Bot badge follows its name instead.
    val appBot = rememberRaftMemberAppBot(member.threadMemberAppBotLookupId())
    val status = if (appBot == null) member.modalAgentStatusIndicatorState(agentStatusResolver) else null
    val readinessCopy =
        if (appBot == null && member.kind.equals("agent", ignoreCase = true)) {
            rememberAgentReadinessDisplayCopy(status)
        } else {
            null
        }
    val runtimeRetired =
        if (appBot == null && member.kind.equals("agent", ignoreCase = true)) {
            rememberAgentRuntimeRetiredState(status)
        } else {
            null
        }
    val signIn =
        if (appBot == null && runtimeRetired == null && readinessCopy == null && member.kind.equals("agent", ignoreCase = true)) {
            rememberAgentRuntimeSignInState(status, fallbackName = member.displayName)
        } else {
            null
        }
    val deliveryHold =
        if (appBot == null && runtimeRetired == null && readinessCopy == null && signIn == null && member.kind.equals("agent", ignoreCase = true)) {
            rememberAgentDeliveryHoldView(status)
        } else {
            null
        }
    val subtitle =
        threadMembersRosterSubtitle(
            memberKind = member.kind,
            liveActivityLabel = status?.localizedMemberStatusLabel(),
            humanRoleLabel =
                if (member.kind.equals("agent", ignoreCase = true)) {
                    ""
                } else {
                    member.description
                }
        )
    val avatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id)
        ?: member.modalAvatarWireValue()
    val roleBadge =
        when (member.effectiveChannelRole) {
            "owner", "admin" -> ChannelMemberBadge(stringResource(Res.string.thread_members_channel_admin), ChannelMemberBadgeKind.Admin)
            "guest" -> ChannelMemberBadge(stringResource(Res.string.channel_member_role_guest), ChannelMemberBadgeKind.External)
            else -> null
        }
    // Member labels (web #14535 MemberRowName): someone from another server reads their servers
    // after the name in quiet text, with the first one's tile on the avatar. A member of the viewed
    // server who is also in another reads "Also in Raft, Acme Corp" in the same quiet text (web
    // #14731 JointServerBadges, agents too; it was a blue chip).
    val originNames = raftOriginServerNames(member.originServers)
    val alsoInLabels =
        member.alsoInServerLabels.filter { it.isNotBlank() }.takeIf { originNames.isEmpty() && it.isNotEmpty() }
    val alsoIn = alsoInLabels?.let { stringResource(Res.string.channel_members_also_in, it.joinToString(", ")) }
    // Web #12853 `JointServerBadges` `sourceOf`: the short text is hidden from screen readers and
    // the sentence, the servers as a list ("Alpha and Charlie"), is spoken in its place. The web
    // also shows the sentence as a hover tooltip, which touch has no equivalent for.
    val alsoInSentence =
        if (alsoInLabels != null && alsoInSpokenAsSentence && !member.kind.equals("agent", ignoreCase = true)) {
            stringResource(
                Res.string.channel_members_also_in_sentence,
                member.displayName.ifBlank { member.handle },
                jointMemberConnectNames(alsoInLabels),
            )
        } else {
            null
        }
    ChannelMembersRow(
        title = member.displayName,
        subtitle = runtimeRetired?.shortText ?: readinessCopy?.title ?: deliveryHold?.text ?: subtitle,
        subtitleContent =
            when {
                runtimeRetired != null -> {
                    {
                        RaftText(
                            text = runtimeRetired.shortText,
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .testTag(AgentRuntimeRetiredTestTags.MemberRowStatus)
                                    .semantics { contentDescription = runtimeRetired.detail },
                        )
                    }
                }
                readinessCopy != null -> {
                    {
                        RaftText(
                            text = readinessCopy.title,
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .testTag(AgentReadinessTestTags.MemberRowStatus)
                                    .semantics { contentDescription = readinessCopy.fullDetail },
                        )
                    }
                }
                signIn != null -> {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RaftText(
                                text = signIn.fullText,
                                style = RaftTheme.typography.caption,
                                color = RaftTheme.colors.foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(AgentRuntimeSignInTestTags.MemberRowStatus),
                            )
                            RaftText(
                                text = signIn.detail,
                                style = RaftTheme.typography.caption,
                                color = RaftTheme.colors.foregroundHint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(AgentRuntimeSignInTestTags.MemberRowDetail),
                            )
                        }
                    }
                }
                deliveryHold != null -> {
                    {
                        RaftText(
                            text = deliveryHold.text,
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .testTag(AgentDeliveryHoldTestTags.MemberRowStatus)
                                    .semantics { contentDescription = deliveryHold.text },
                        )
                    }
                }
                else -> null
            },
        trailingAction =
            signIn?.takeIf { it.offered }?.let { s ->
                {
                    AgentRuntimeSignInAction(
                        signIn = s,
                        testTag = AgentRuntimeSignInTestTags.MemberRowAction,
                    )
                }
            },
        avatarWireValue = avatarWireValue,
        avatarLabel = member.modalAvatarLabel(),
        avatarKind = raftAvatarKind(member.kind.parsedMemberKindOrNull()),
        cacheSubject = raftMemberAvatarCacheSubject(member.kind.parsedMemberKindOrNull(), member.id),
        cacheServerId = member.sourceServerId,
        statusColor =
            readinessCopy?.light?.dotColor()
                ?: if (signIn != null || deliveryHold != null) RaftColor.AccentOrange else status?.color,
        badges = listOfNotNull(roleBadge),
        titleBadge =
            if (appBot == null && originNames.isEmpty() && alsoIn == null) {
                null
            } else {
                {
                    appBot?.let { bot -> RaftAppBotBadge(identity = bot, botName = member.displayName) }
                    if (originNames.isNotEmpty()) ChannelMemberOriginServerNames(originNames)
                    alsoIn?.let { ChannelMemberOriginServerNames(it, testTag = ChannelMemberAlsoInTestTag, spokenText = alsoInSentence) }
                }
            },
        avatarOriginServers = member.originServers,
        divider = divider,
        removing = removing,
        onClick = { onOpen(member) },
        // One ⋯ per row (task #triage:#416): the role item and Remove from channel share its menu.
        moreActions =
            channelMemberRowMenu(member.roleAction, canRemove = member.canRemove)?.let { menu ->
                {
                    ChannelMemberRowMenuButton(
                        menu = menu,
                        // Web `formatMessage(..., { name: member.name })`: the handle.
                        memberName = member.handle.ifBlank { member.displayName },
                        removeLabel = stringResource(Res.string.thread_members_remove_name, member.displayName),
                        busy = roleChanging || removing,
                        onRoleAction = { onRoleAction(member) },
                        onRemove = { onRemove(member) },
                    )
                }
            },
    )
}

internal fun threadMemberListHeight(agentCount: Int, humanCount: Int, maxHeight: Int = 288): Int {
    val sectionCount = listOf(agentCount, humanCount).count { it > 0 }
    val rowCount = agentCount + humanCount
    return (sectionCount * 30 + rowCount * 58 + 8).coerceIn(108, maxHeight)
}

internal val RaftThreadMemberItem.memberKey: String
    get() = "${kind.lowercase()}:$id"

internal data class ThreadAddMemberRowInteraction(
    val selected: Boolean,
    val enabled: Boolean,
    val clickEnabled: Boolean
)

internal fun threadAddMemberRowInteraction(
    memberKey: String,
    selectedKey: String?,
    blocked: Boolean,
    adding: Boolean,
    alreadyJoined: Boolean
): ThreadAddMemberRowInteraction =
    ThreadAddMemberRowInteraction(
        selected = memberKey == selectedKey,
        enabled = !blocked && !alreadyJoined,
        clickEnabled = !blocked && !alreadyJoined && !adding
    )

/** Fills the known channel names from [home] while the store has not added them yet; never overrides them. */
internal fun RaftThreadScreenPayload.withFirstFrameKnownMarkdownChannelNames(home: HomeSnapshot?): RaftThreadScreenPayload {
    if (home == null || knownMarkdownChannelNames.isNotEmpty()) return this
    val names = home.knownMarkdownChannelNames()
    return if (names.isEmpty()) this else copy(knownMarkdownChannelNames = names)
}

internal fun RaftThreadScreenPayload.withThreadMembersProjection(
    projection: ThreadMembersProjection
): RaftThreadScreenPayload =
    copy(
        membersCount = projection.membersCount,
        members = projection.members,
        memberCandidates = projection.memberCandidates,
        canAddMembers = projection.canAddMembers
    )

internal fun threadMembersProjection(
    basePayload: RaftThreadScreenPayload,
    channelMembersSlice: SharedServerChannelMembersSlice,
    serverMembersEntry: SharedServerMembersEntry,
    serverId: ServerId?
): ThreadMembersProjection {
    val loadedMembers =
        channelMembersSlice.members
            .distinctBy { it.threadMemberKey }
            .map { it.toRaftThreadMemberItem(serverId) }
    // The stored (offline) participants stand in while nothing else is known; read-only (no add).
    val usesStoredStandIn =
        channelMembersSlice.storedStandIn &&
            channelMembersSlice.status != SharedServerMembersLoadStatus.Loaded &&
            loadedMembers.isNotEmpty() &&
            basePayload.members.isEmpty()
    val usesSlice = channelMembersSlice.status == SharedServerMembersLoadStatus.Loaded || usesStoredStandIn
    val members =
        if (usesSlice) {
            loadedMembers
        } else {
            basePayload.members
        }
    val serverMembers =
        serverMembersEntry.members.takeIf { serverMembersEntry.serverId == serverId?.value?.trim() }.orEmpty()
    val candidates =
        if (serverMembers.isEmpty()) {
            basePayload.memberCandidates
        } else {
            threadAddMemberCandidatesExcludingJoined(
                candidates = serverMembers.map { it.toRaftThreadMemberItem(serverId) },
                members = members,
                localServerId = serverId?.value
            )
        }
    return ThreadMembersProjection(
        membersCount =
            when {
                usesSlice -> members.size
                basePayload.membersCount > 0 -> basePayload.membersCount
                else -> members.size
            },
        members = members,
        memberCandidates = candidates,
        canAddMembers =
            if (usesSlice) {
                // False on a stand-in: management waits for a fresh answer.
                channelMembersSlice.canAddMembers && !channelMembersSlice.storedStandIn
            } else {
                basePayload.canAddMembers
            }
    )
}

private val MemberProfile.threadMemberKey: String
    get() = "${kind.name.lowercase()}:${id.value.trim()}"

/**
 * A roster member as a thread member row. Where they are from is [memberOrigin] (one rule with
 * the web, #wg-rbac:#46): a computed joint row's `serverId` is only the person's first source,
 * so a viewed-server member whose first source is another server stays local here (opens the full
 * profile, keeps Remove) and still names that server, as the web's tag does.
 */
internal fun MemberProfile.toRaftThreadMemberItem(
    localServerId: ServerId? = null,
    knownServers: List<MemberServerRef> = emptyList(),
): RaftThreadMemberItem {
    val viewerServerId = localServerId?.value?.trim()?.takeIf { it.isNotBlank() }
    val origin = memberOrigin(this, viewerServerId, knownServers)
    val rosterServerId = serverId?.value
    return RaftThreadMemberItem(
        id = id.value,
        displayName = displayName,
        handle = handle,
        roleLabel =
            roleDescription
                ?: when (kind) {
                    MemberKind.Agent -> "Agent"
                    MemberKind.Human -> "Member"
                    MemberKind.System -> "System"
                    MemberKind.External -> "External"
                },
        description = normalizedThreadMemberDescription(description),
        avatarUrl = avatarWireValueForAvatarSlot(),
        avatarLabel = avatarLabel,
        kind = kind.name.lowercase(),
        // The server whose profile this row opens: the viewed one unless the member is from another.
        sourceServerId = if (origin.external || viewerServerId == null || rosterServerId == null) rosterServerId else viewerServerId,
        rosterServerId = rosterServerId,
        sourceServerLabel = memberRowServerLabel(this, viewerServerId, knownServers),
        canRemove = memberRemovableFromChannel(this, viewerServerId),
        // A Guest label is a fact of the viewed server only (web `rosterRoleTag`).
        effectiveChannelRole = if (origin.external && effectiveChannelRole == "guest") "member" else effectiveChannelRole,
        originServers = if (origin.external) origin.servers else emptyList(),
        alsoInServerLabels = if (origin.external) emptyList() else memberServerBadgeLabels(this, viewerServerId),
        inViewerServer = inViewerServer
    )
}

/** The member's own avatar; [RaftAppAvatar] of the member's kind supplies the default face. */
internal fun RaftThreadMemberItem.modalAvatarWireValue(): String? =
    avatarUrl?.takeIf { it.isNotBlank() }

// The name the initials come from; never the member id (no name shows the placeholder icon).
internal fun RaftThreadMemberItem.modalAvatarLabel(): String =
    listOf(displayName, handle).map(String::trim).firstOrNull { it.isNotEmpty() && it != id } ?: avatarLabel

/** The agent whose App Bot identity a channel member / add-member row shows; null for people. */
internal fun RaftThreadMemberItem.threadMemberAppBotLookupId(): String? =
    id.trim().takeIf { kind.equals("agent", ignoreCase = true) && it.isNotBlank() }

internal fun threadMembersRosterSubtitle(
    memberKind: String,
    liveActivityLabel: String?,
    humanRoleLabel: String
): String =
    if (memberKind.equals("agent", ignoreCase = true)) {
        liveActivityLabel?.trim().orEmpty()
    } else {
        humanRoleLabel
    }

internal fun threadAddMemberCandidateSubtitle(description: String): String = description.trim()

internal fun normalizedThreadMemberDescription(description: String?): String = description?.trim().orEmpty()

/**
 * Keys of roster members that already block an add on this server (web parity:
 * `isLocalProjectionMember` in channelLocalMembership.ts). A joint channel's
 * roster merges every participating server's projection, but an add only
 * writes this server's projection, so a human whose row comes from another
 * server is still addable here. Agents are excluded by id regardless of server,
 * and a missing server on either side counts as local, so ordinary channels
 * behave exactly as before.
 */
internal fun threadAddMemberJoinedKeys(
    members: List<RaftThreadMemberItem>,
    localServerId: String?
): Set<String> {
    val localId = localServerId?.trim().orEmpty()
    return members
        .filter { member ->
            val sourceId = (member.rosterServerId ?: member.sourceServerId)?.trim().orEmpty()
            member.kind.equals("agent", ignoreCase = true) ||
                localId.isEmpty() ||
                sourceId.isEmpty() ||
                sourceId == localId
        }
        .map { it.memberKey }
        .toSet()
}

/** Server-directory [candidates] that are not yet members of this server's projection. */
internal fun threadAddMemberCandidatesExcludingJoined(
    candidates: List<RaftThreadMemberItem>,
    members: List<RaftThreadMemberItem>,
    localServerId: String?
): List<RaftThreadMemberItem> {
    val joinedKeys = threadAddMemberJoinedKeys(members, localServerId)
    return candidates.filterNot { it.memberKey in joinedKeys }
}

internal fun filterThreadAddMemberCandidates(
    query: String,
    candidates: List<RaftThreadMemberItem>
): List<RaftThreadMemberItem> =
    filterPeopleSuggestions(
        query = query,
        suggestions = candidates,
        handle = { member -> member.handle },
        displayName = { member -> member.displayName },
        description = { member -> member.description },
        sourceServerLabel = { member -> member.sourceServerLabel },
        legacyId = { member -> member.id }
    )
        .distinctBy { it.memberKey }

internal fun resolveThreadMemberAgentStatusIndicatorState(
    memberKind: String,
    canonicalState: RaftAgentStatusIndicatorState?
): RaftAgentStatusIndicatorState? =
    canonicalState.takeIf { memberKind.equals("agent", ignoreCase = true) }

@Composable
private fun RaftThreadMemberItem.localizedModalRoleLabel(): String =
    when {
        roleLabel.equals("Agent", ignoreCase = true) -> stringResource(Res.string.thread_member_role_agent)
        roleLabel.equals("Member", ignoreCase = true) -> stringResource(Res.string.thread_member_role_member)
        roleLabel.equals("System", ignoreCase = true) -> stringResource(Res.string.thread_member_role_system)
        else -> roleLabel
    }

@Composable
internal fun RaftThreadMemberItem.modalAgentStatusIndicatorState(
    agentStatusResolver: RaftAgentStatusResolver
): RaftAgentStatusIndicatorState? {
    val canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, id)
    val resolved =
        resolveThreadMemberAgentStatusIndicatorState(
            memberKind = kind,
            canonicalState = canonicalState
        ) ?: return null
    // Joint channel peer (#16819): another server's agent reads its Workers as presence only, never a count.
    return if (originServers.isNotEmpty() && resolved.runningWorkers != null && !resolved.runningWorkersPresenceOnly) {
        resolved.copy(runningWorkers = 1, runningWorkersPresenceOnly = true)
    } else {
        resolved
    }
}
