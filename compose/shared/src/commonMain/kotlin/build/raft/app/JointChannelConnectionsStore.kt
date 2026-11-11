package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.JOINT_INVITEE_NOT_ADMIN
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_INVITE_LIMIT_EXCEEDED
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointSameAdminContext
import build.raft.app.data.JointSameAdminServer
import build.raft.app.data.findSameAdminJointTarget
import build.raft.app.data.JointServerSlugIssue
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftDurableSnapshotKey
import build.raft.app.data.RaftKeyedSnapshotCache
import build.raft.app.data.RealJointChannelRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.JointChannelRole
import build.raft.app.data.MemberConnectResponse
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.jointServerSlugIssue
import build.raft.app.data.parseJointInvitedPeopleText
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** A failed joint call: the existing error pipeline input, keeping the server's code and text. */
internal sealed interface JointChannelFailure {
    val code: String

    data class Error(val error: ScreenError) : JointChannelFailure {
        override val code: String get() = error.code
    }

    data class Blocked(val blocker: PermissionBlocker) : JointChannelFailure {
        override val code: String get() = blocker.code
    }
}

internal fun DataResult<*>.toJointChannelFailure(): JointChannelFailure? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Error -> JointChannelFailure.Error(error)
        is DataResult.NoPermission -> JointChannelFailure.Blocked(blocker)
        is DataResult.Empty -> JointChannelFailure.Error(ScreenError(code = "joint_empty_response", retryable = true))
    }

internal enum class JointInviteField { Slug, People, Form }

/**
 * Which invite-form slot shows a server rejection. Web EditChannelDialog routes by message text;
 * here the server's stable `code` decides (slug → server slug field; invitee codes → people field;
 * limits and everything else → the form banner).
 */
internal fun jointInviteFailureField(code: String): JointInviteField =
    when (code) {
        JOINT_TARGET_SERVER_INVALID -> JointInviteField.Slug
        JOINT_INVITEE_NOT_FOUND,
        JOINT_INVITEE_NOT_ADMIN,
        JOINT_INVITE_LIMIT_EXCEEDED -> JointInviteField.People
        else -> JointInviteField.Form
    }

internal enum class JointChannelConnectionsLoadStatus { Loading, Content, Error }

internal enum class JointChannelConnectionsAction {
    Invite,
    Resend,

    /** The host's admin takes another server out (web JointServerDisconnectAction). */
    DisconnectServer,

    /** A member connects servers they own or administer (web ConnectYourServersDialog). */
    MemberConnect,
}

/** Client-side invite form rejection (web parity: checked before any request). */
internal enum class JointInviteValidation {
    SlugRequired,
    SlugPattern,
    PeopleRequired,
    ServerLimitReached,

    /** Web `channel.jointInvite.sameAdmin.confirmRequired`: the direct connection is not confirmed. */
    SameAdminConfirmRequired,
}

internal sealed interface JointChannelConnectionsEffect {
    /**
     * [connectedServerName] is set when the same-admin invite connected that server directly
     * (web reads it from the returned server list: the server may fall back to the email);
     * [emailedCount] is everyone else on the list.
     */
    data class InviteSent(val emailedCount: Int, val connectedServerName: String? = null) : JointChannelConnectionsEffect

    data class Resent(val resentCount: Int) : JointChannelConnectionsEffect

    /** Web `channel.edit.disconnectedServerToast`: "Disconnected {server} from #{channel}". */
    data class ServerDisconnected(val serverName: String, val channelName: String) : JointChannelConnectionsEffect

    /**
     * Connect your servers closed with every chosen server answered well (web toasts): those
     * [connected], those [connecting] (a private channel converted first), or only servers that
     * were [alreadyConnected]. Names, in the order chosen.
     */
    data class MemberConnectFinished(
        val connected: List<String> = emptyList(),
        val connecting: List<String> = emptyList(),
        val alreadyConnected: List<String> = emptyList(),
    ) : JointChannelConnectionsEffect
}

internal data class JointChannelConnectionsSnapshot(
    val connections: JointChannelConnections,
    /** Server-level `federateChannels` (owner/admin), the gate every joint mutation route checks. */
    val canFederate: Boolean,
)

internal data class JointChannelConnectionsState(
    val status: JointChannelConnectionsLoadStatus = JointChannelConnectionsLoadStatus.Loading,
    val connections: JointChannelConnections? = null,
    val canFederate: Boolean = false,
    val refreshing: Boolean = false,
    val loadFailure: JointChannelFailure? = null,
    val actionInFlight: JointChannelConnectionsAction? = null,
    val inviteValidation: Set<JointInviteValidation> = emptySet(),
    val inviteFailure: JointChannelFailure? = null,
    val resendFailure: JointChannelFailure? = null,
    val effect: JointChannelConnectionsEffect? = null,
    /** The inviter's account, servers and roles; null until the session is known. */
    val sameAdminContext: JointSameAdminContext? = null,
    /**
     * `external_channels_v1` for this server (web `useServerFeatureFlag(EXTERNAL_CHANNELS_FEATURE_FLAG_KEY)`).
     * Off: the page is as before. On: host first under a Host badge, no plan chips, only pending
     * rows badged, no server-invite form or Resend, and the host's per-row Disconnect.
     */
    val externalChannelsEnabled: Boolean = false,
    /** The server whose Disconnect confirm is open; null when none is. */
    val disconnectConfirmServerId: String? = null,
    /** The confirm's failure: web keeps the dialog open with one line (and here the trace ID). */
    val disconnectFailure: JointChannelFailure? = null,
    /** The account's servers and roles (`GET /api/servers`), for Connect your servers; null until known. */
    val memberConnectAccount: MemberConnectAccount? = null,
    /** The open Connect your servers sheet; null when closed. */
    val memberConnectSheet: MemberConnectSheet? = null,
    /** Servers the viewer connected to this channel while the app runs (web "Connected by you"). */
    val connectedByYou: Set<String> = emptySet(),
) {
    /** Web EditChannelDialog `inviteSameAdminTarget` for the form's current slug and people. */
    fun sameAdminTarget(targetServerSlug: String, invitedPeopleText: String): JointSameAdminServer? =
        findSameAdminJointTarget(sameAdminContext, targetServerSlug, parseJointInvitedPeopleText(invitedPeopleText))

    /**
     * Web: invite / resend controls render only for `federateChannels` on a live joint channel,
     * and (`serverInviteOfferAllowed`) only while external channels are off.
     */
    val canManageConnections: Boolean
        get() = canFederate && connections?.isJoint == true && connections.archived.not() && !externalChannelsEnabled

    /**
     * The rows as shown. External channels on: the host leads (web `jointServerRows`), the rest
     * keep the server's order. Off: the server's order, as before.
     */
    val displayServers: List<JointChannelServer>
        get() {
            val servers = connections?.servers.orEmpty()
            if (!externalChannelsEnabled) return servers
            privateHostRow?.let { return listOf(it) }
            val (host, rest) = servers.partition { it.role == JointChannelRole.Host }
            return host + rest
        }

    /**
     * Web `showPrivateConnectSection`: a private channel lists one row, this server as the host
     * (a member may connect a server to it, which makes it joint).
     */
    private val privateHostRow: JointChannelServer?
        get() {
            val current = connections ?: return null
            val account = memberConnectAccount ?: return null
            if (!externalChannelsEnabled || current.isJoint || !current.isPrivate) return null
            return JointChannelServer(
                serverId = account.currentServerId,
                name = account.currentServerName,
                slug = account.currentServerSlug,
                active = true,
                isCurrentServer = true,
                plan = null,
                role = JointChannelRole.Host,
            )
        }

    /** Web `useMemberConnectRows`: nothing while external channels are off. */
    val memberConnectRows: List<MemberConnectRow>
        get() =
            if (!externalChannelsEnabled) emptyList() else memberConnectRows(connections?.memberConnectChannel(), memberConnectAccount)

    /** Web `memberConnect.available`: the ⋯ with Connect your servers, only with a server to connect. */
    val memberConnectAvailable: Boolean
        get() = memberConnectRows.any { it.state == MemberConnectRowState.Eligible }

    /** The connect is to a private channel: it becomes shared (web `channelType === "private"`). */
    val memberConnectToPrivate: Boolean
        get() = connections?.let { !it.isJoint && it.isPrivate } == true

    /** Web `memberConnectHostName`: the host row's name, else this server's. */
    val memberConnectHostName: String
        get() =
            displayServers.firstOrNull { it.role == JointChannelRole.Host }?.let { it.name.ifBlank { it.slug } }
                ?: memberConnectAccount?.currentServerName.orEmpty()

    /** Web `guest`: a Guest of the host bound to this channel; connecting ends that seat. */
    val memberConnectGuest: Boolean
        get() =
            memberConnectAccount?.currentServerRole == "guest" &&
                (memberConnectToPrivate || connections?.jointRole == JointChannelRole.Host)

    /** A row of the sheet as it reads now: a refusal may have changed it. */
    fun memberConnectRowState(row: MemberConnectRow): MemberConnectRowState =
        memberConnectSheet?.rowStates?.get(row.server.id) ?: row.state

    /** Web ChoiceList `disabled`: only an eligible row not connected yet, while the route is open. */
    fun memberConnectRowSelectable(row: MemberConnectRow): Boolean {
        val sheet = memberConnectSheet ?: return false
        return !sheet.closed && row.server.id !in sheet.done && memberConnectRowState(row) == MemberConnectRowState.Eligible
    }

    /** External channels on: the host row's Host badge. */
    fun showsHostBadge(server: JointChannelServer): Boolean =
        externalChannelsEnabled && server.role == JointChannelRole.Host

    /** External channels on: no Free/Paid chip (web #wg-rbac:2ee0381d). */
    fun showsPlanBadge(server: JointChannelServer): Boolean = !externalChannelsEnabled && server.plan != null

    /** Off: every row says active or pending. On: a connected server needs no badge, pending still says so. */
    fun showsStatusBadge(server: JointChannelServer): Boolean = !externalChannelsEnabled || !server.active

    /**
     * Web `canDisconnectJointServers` and its row check: this server hosts the joint, external
     * channels are on, the channel's own `federateChannels` is true (the server's check, never a
     * server-wide fallback), and the row is another server's active copy. Everything here is
     * what the server sent; the route re-checks it.
     */
    fun canDisconnectServer(server: JointChannelServer): Boolean {
        val current = connections ?: return false
        return externalChannelsEnabled &&
            current.isJoint &&
            current.jointRole == JointChannelRole.Host &&
            current.federateChannels == true &&
            server.active &&
            server.role != JointChannelRole.Host &&
            !server.isCurrentServer
    }

    /** The row whose Disconnect confirm is open, while it can still be disconnected. */
    val disconnectConfirmServer: JointChannelServer?
        get() = disconnectConfirmServerId?.let { id -> connections?.servers?.firstOrNull { it.serverId == id } }

    val canResend: Boolean
        get() = canManageConnections && connections?.hasCurrentServerPendingInvites == true
}

internal sealed interface JointChannelConnectionsIntent {
    data object Load : JointChannelConnectionsIntent
    data object Refresh : JointChannelConnectionsIntent
    data class Invite(
        val targetServerSlug: String,
        val invitedPeopleText: String,
        val sameAdminConfirmed: Boolean = false,
    ) : JointChannelConnectionsIntent
    data class SetSameAdminContext(val context: JointSameAdminContext?) : JointChannelConnectionsIntent
    /** `external_channels_v1` as last read for this server. */
    data class SetExternalChannelsEnabled(val enabled: Boolean) : JointChannelConnectionsIntent
    /** The account's servers and roles, as the stored session holds them. */
    data class SetMemberConnectAccount(val account: MemberConnectAccount?) : JointChannelConnectionsIntent
    /** Connected servers ⋯ > Connect your servers. */
    data object OpenMemberConnect : JointChannelConnectionsIntent
    /** The sheet's × / Cancel / Close (ignored while the request runs). */
    data object DismissMemberConnect : JointChannelConnectionsIntent
    data class SetMemberConnectSelection(val serverIds: Set<String>) : JointChannelConnectionsIntent
    /** The sheet's Connect. */
    data object SubmitMemberConnect : JointChannelConnectionsIntent
    /** A row's Disconnect: open its confirm. */
    data class ConfirmDisconnectServer(val serverId: String) : JointChannelConnectionsIntent
    /** The confirm's Cancel (ignored while the request runs). */
    data object DismissDisconnectServer : JointChannelConnectionsIntent
    /** The confirm's Disconnect {server}. */
    data object DisconnectServer : JointChannelConnectionsIntent
    data object Resend : JointChannelConnectionsIntent
    data object ClearInviteFeedback : JointChannelConnectionsIntent
    data object ConsumeEffect : JointChannelConnectionsIntent
}

internal interface JointChannelConnectionsDataSource {
    suspend fun load(): DataResult<JointChannelConnectionsSnapshot>
    suspend fun invite(targetServerSlug: String, invitedPeople: List<String>, sameAdmin: Boolean): DataResult<JointChannelConnections>
    suspend fun resend(): DataResult<Int>
    suspend fun disconnectServer(serverId: String): DataResult<Unit>
    suspend fun connectMyServers(serverIds: List<String>): DataResult<MemberConnectResponse>
}

internal class RealJointChannelConnectionsDataSource(
    private val route: ChannelSettingsRoute,
    owner: AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway,
    private val logger: (String) -> Unit = {},
) : JointChannelConnectionsDataSource {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    override suspend fun load(): DataResult<JointChannelConnectionsSnapshot> =
        runner.run { receipt, networkClient ->
            when (val result = repository(networkClient).loadConnections(route.channelId, DataRequestPriority.Visible)) {
                is DataResult.Content ->
                    if (result.data.channelId != route.channelId) {
                        DataResult.Error(ScreenError(code = CHANNEL_SETTINGS_IDENTITY_MISMATCH, retryable = false))
                    } else {
                        DataResult.Content(JointChannelConnectionsSnapshot(result.data, receipt.canManageSettings))
                    }
                is DataResult.Empty -> result
                is DataResult.Error -> result
                is DataResult.NoPermission -> result
            }
        }

    override suspend fun invite(
        targetServerSlug: String,
        invitedPeople: List<String>,
        sameAdmin: Boolean,
    ): DataResult<JointChannelConnections> =
        runner.run { _, networkClient ->
            repository(networkClient).inviteServer(route.channelId, targetServerSlug, invitedPeople, sameAdmin)
        }

    override suspend fun resend(): DataResult<Int> =
        runner.run { _, networkClient -> repository(networkClient).resendInvites(route.channelId) }

    override suspend fun disconnectServer(serverId: String): DataResult<Unit> =
        runner.run { _, networkClient -> repository(networkClient).disconnectServer(route.channelId, serverId) }

    override suspend fun connectMyServers(serverIds: List<String>): DataResult<MemberConnectResponse> =
        runner.run { _, networkClient -> repository(networkClient).connectMyServers(route.channelId, serverIds) }

    private fun repository(networkClient: build.raft.app.data.RaftNetworkClient) =
        RealJointChannelRepository(networkClient) { logger("joint_connections $it") }
}

/** The process joint-connections cache (data-layer plan step 5). */
internal typealias RaftJointConnectionsCache = RaftKeyedSnapshotCache<JointChannelConnectionsSnapshot, RaftJointConnectionsDurableRecord>

/** One page's slot in the process cache: environment + account + server + channel. */
internal class JointChannelConnectionsCacheSlot(
    val cache: RaftJointConnectionsCache,
    val key: RaftDurableSnapshotKey,
)

internal fun jointChannelConnectionsCacheSlot(
    cache: RaftJointConnectionsCache?,
    owner: AuthenticatedRouteOwner,
    route: ChannelSettingsRoute,
): JointChannelConnectionsCacheSlot? {
    cache ?: return null
    if (owner.serverId.trim() != route.serverId.value.trim()) return null
    val key =
        RaftDurableSnapshotKey(
            environmentId = owner.environmentId,
            accountId = owner.accountId,
            serverId = owner.serverId,
            entityId = route.channelId.value,
        )
    return key.rowId?.let { JointChannelConnectionsCacheSlot(cache, key) }
}

/**
 * MVI store of a joint channel's Connected servers page: the server list, the
 * "Channel connection invitation" form and Resend Invite. One mutation runs at a time; a
 * failure keeps the loaded list and attaches the error to the action that failed.
 *
 * Cache-first (data-layer plan step 5): with a [cacheSlot] the first state is the cached list
 * (memory, or the durable row on a cold start), the page refreshes once in the background and
 * writes the answer back; a 403 / 404 evicts the entry and the page shows the terminal error.
 */
internal class JointChannelConnectionsStore(
    private val dataSource: JointChannelConnectionsDataSource,
    private val scope: CoroutineScope,
    private val cacheSlot: JointChannelConnectionsCacheSlot? = null,
    private val connectedByYouSlot: MemberConnectedByYouSlot? = null,
) {
    private val mutableState =
        MutableStateFlow(
            (
                cacheSlot?.cache?.peek(cacheSlot.key)?.let { cached -> JointChannelConnectionsState().withSnapshot(cached) }
                    ?: JointChannelConnectionsState()
            ).copy(connectedByYou = connectedByYouSlot?.let { it.memory.get(it.key) }.orEmpty())
        )
    val state: StateFlow<JointChannelConnectionsState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    fun dispatch(intent: JointChannelConnectionsIntent) {
        when (intent) {
            JointChannelConnectionsIntent.Load -> load(force = false)
            JointChannelConnectionsIntent.Refresh -> load(force = true)
            is JointChannelConnectionsIntent.Invite -> invite(intent)
            JointChannelConnectionsIntent.Resend -> resend()
            is JointChannelConnectionsIntent.SetExternalChannelsEnabled ->
                if (mutableState.value.externalChannelsEnabled != intent.enabled) {
                    mutableState.value =
                        mutableState.value.copy(
                            externalChannelsEnabled = intent.enabled,
                            // Off again: no per-row Disconnect, so no confirm either.
                            disconnectConfirmServerId = mutableState.value.disconnectConfirmServerId.takeIf { intent.enabled },
                            disconnectFailure = mutableState.value.disconnectFailure.takeIf { intent.enabled },
                            // Off again: no Connect your servers either.
                            memberConnectSheet = mutableState.value.memberConnectSheet.takeIf { intent.enabled },
                        )
                }
            is JointChannelConnectionsIntent.SetMemberConnectAccount ->
                if (mutableState.value.memberConnectAccount != intent.account) {
                    mutableState.value = mutableState.value.copy(memberConnectAccount = intent.account)
                }
            JointChannelConnectionsIntent.OpenMemberConnect -> {
                val current = mutableState.value
                if (current.memberConnectSheet == null && current.actionInFlight == null && current.memberConnectAvailable) {
                    mutableState.value = current.copy(memberConnectSheet = MemberConnectSheet())
                }
            }
            JointChannelConnectionsIntent.DismissMemberConnect -> {
                val current = mutableState.value
                if (current.memberConnectSheet != null && current.memberConnectSheet.busy.not()) {
                    mutableState.value = current.copy(memberConnectSheet = null)
                }
            }
            is JointChannelConnectionsIntent.SetMemberConnectSelection -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                if (sheet != null && !sheet.busy) {
                    val selectable = current.memberConnectRows.filter(current::memberConnectRowSelectable).map { it.server.id }.toSet()
                    val next = intent.serverIds.filter { it in selectable }.toSet()
                    if (next != sheet.selected) mutableState.value = current.copy(memberConnectSheet = sheet.copy(selected = next))
                }
            }
            JointChannelConnectionsIntent.SubmitMemberConnect -> submitMemberConnect()
            is JointChannelConnectionsIntent.ConfirmDisconnectServer -> {
                val current = mutableState.value
                val server = current.connections?.servers?.firstOrNull { it.serverId == intent.serverId }
                if (server != null && current.canDisconnectServer(server) && current.actionInFlight == null) {
                    mutableState.value = current.copy(disconnectConfirmServerId = server.serverId, disconnectFailure = null)
                }
            }
            JointChannelConnectionsIntent.DismissDisconnectServer -> {
                val current = mutableState.value
                if (current.actionInFlight != JointChannelConnectionsAction.DisconnectServer && current.disconnectConfirmServerId != null) {
                    mutableState.value = current.copy(disconnectConfirmServerId = null, disconnectFailure = null)
                }
            }
            JointChannelConnectionsIntent.DisconnectServer -> disconnectServer()
            is JointChannelConnectionsIntent.SetSameAdminContext ->
                if (mutableState.value.sameAdminContext != intent.context) {
                    mutableState.value = mutableState.value.copy(sameAdminContext = intent.context)
                }
            JointChannelConnectionsIntent.ClearInviteFeedback -> {
                val current = mutableState.value
                if (current.inviteValidation.isNotEmpty() || current.inviteFailure != null) {
                    mutableState.value = current.copy(inviteValidation = emptySet(), inviteFailure = null)
                }
            }
            JointChannelConnectionsIntent.ConsumeEffect ->
                if (mutableState.value.effect != null) mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    private fun load(force: Boolean) {
        val current = mutableState.value
        if (current.actionInFlight != null) return
        if (loadJob?.isActive == true) return
        if (!force && current.status == JointChannelConnectionsLoadStatus.Content) return
        loadGeneration += 1L
        val generation = loadGeneration
        mutableState.value =
            if (current.connections == null) {
                current.copy(status = JointChannelConnectionsLoadStatus.Loading, loadFailure = null)
            } else {
                current.copy(refreshing = true, loadFailure = null)
            }
        loadJob =
            scope.launch {
                val slot = cacheSlot
                if (slot != null && mutableState.value.connections == null) {
                    // Cold start: the durable row paints before the network answers.
                    slot.cache.read(slot.key)?.let { cached ->
                        if (generation == loadGeneration && mutableState.value.connections == null) {
                            mutableState.value = mutableState.value.withSnapshot(cached).copy(refreshing = true)
                        }
                    }
                }
                val ticket = slot?.cache?.ticket()
                var result = runCall { dataSource.load() }
                if (result.jointFailureCodeOrNull() == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
                    // A token refresh rotated the session mid-request: same account, ask once more.
                    // After a server switch / sign-out the capture fails again without a request.
                    result = runCall { dataSource.load() }
                }
                // Tenny rule 1: the answer is this account's data for this channel even when the
                // page moved on; it is cached regardless of the page generation.
                if (slot != null && ticket != null) {
                    when {
                        result is DataResult.Content -> slot.cache.write(slot.key, result.data, ticket)
                        result.isJointConnectionsAccessLost() -> slot.cache.evict(slot.key)
                    }
                }
                if (generation != loadGeneration) return@launch
                val previous = mutableState.value
                mutableState.value =
                    when (result) {
                        is DataResult.Content -> previous.withSnapshot(result.data)
                        else -> {
                            val failure = result.toJointChannelFailure()
                            when {
                                // Lost access (403 / 404): nothing cached may stay on screen.
                                result.isJointConnectionsAccessLost() -> previous.loadErrorState(failure)
                                // A token refresh / server switch overtook the request: the shown
                                // list is still this account's, keep it without an error.
                                previous.connections != null && failure?.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED ->
                                    previous.copy(refreshing = false)
                                previous.connections != null -> previous.copy(refreshing = false, loadFailure = failure)
                                else -> previous.loadErrorState(failure)
                            }
                        }
                    }
            }
    }

    private fun invite(intent: JointChannelConnectionsIntent.Invite) {
        val current = mutableState.value
        if (current.status != JointChannelConnectionsLoadStatus.Content || current.actionInFlight != null) return
        if (!current.canManageConnections) return
        val connections = current.connections ?: return
        val slug = intent.targetServerSlug.trim()
        val people = parseJointInvitedPeopleText(intent.invitedPeopleText)
        val validation =
            buildSet {
                when (jointServerSlugIssue(slug)) {
                    JointServerSlugIssue.Required -> add(JointInviteValidation.SlugRequired)
                    JointServerSlugIssue.Pattern -> add(JointInviteValidation.SlugPattern)
                    null -> Unit
                }
                if (people.isEmpty()) add(JointInviteValidation.PeopleRequired)
                if (isEmpty() && connections.serverLimitReached) add(JointInviteValidation.ServerLimitReached)
            }
        val sameAdminTarget = findSameAdminJointTarget(current.sameAdminContext, slug, people)
        if (validation.isEmpty() && sameAdminTarget != null && !intent.sameAdminConfirmed) {
            mutableState.value =
                current.copy(inviteValidation = setOf(JointInviteValidation.SameAdminConfirmRequired), inviteFailure = null)
            return
        }
        if (validation.isNotEmpty()) {
            mutableState.value = current.copy(inviteValidation = validation, inviteFailure = null)
            return
        }
        mutableState.value =
            current.copy(
                actionInFlight = JointChannelConnectionsAction.Invite,
                inviteValidation = emptySet(),
                inviteFailure = null,
                effect = null,
            )
        launchAction {
            val result = runCall { dataSource.invite(slug, people, sameAdmin = sameAdminTarget != null) }
            val previous = mutableState.value
            if (result is DataResult.Content && result.data.channelId == connections.channelId) {
                cacheSlot?.let { slot ->
                    slot.cache.write(slot.key, JointChannelConnectionsSnapshot(result.data, previous.canFederate), slot.cache.ticket())
                }
            }
            mutableState.value =
                when (result) {
                    is DataResult.Content -> {
                        // Web: read the outcome from the returned list, the accept may have fallen back to email.
                        val connectedDirectly =
                            sameAdminTarget != null &&
                                result.data.servers.any { it.slug == sameAdminTarget.slug && it.active }
                        previous.copy(
                            actionInFlight = null,
                            connections =
                                if (result.data.channelId == connections.channelId) result.data else previous.connections,
                            effect =
                                JointChannelConnectionsEffect.InviteSent(
                                    emailedCount = if (connectedDirectly) people.size - 1 else people.size,
                                    connectedServerName = sameAdminTarget?.name?.takeIf { connectedDirectly },
                                ),
                        )
                    }
                    else -> previous.copy(actionInFlight = null, inviteFailure = result.toJointChannelFailure())
                }
        }
    }

    private fun resend() {
        val current = mutableState.value
        if (current.status != JointChannelConnectionsLoadStatus.Content || current.actionInFlight != null) return
        if (!current.canResend) return
        mutableState.value =
            current.copy(actionInFlight = JointChannelConnectionsAction.Resend, resendFailure = null, effect = null)
        launchAction {
            val result = runCall { dataSource.resend() }
            val previous = mutableState.value
            mutableState.value =
                when (result) {
                    is DataResult.Content ->
                        previous.copy(actionInFlight = null, effect = JointChannelConnectionsEffect.Resent(result.data))
                    else -> previous.copy(actionInFlight = null, resendFailure = result.toJointChannelFailure())
                }
        }
    }

    private fun disconnectServer() {
        val current = mutableState.value
        if (current.status != JointChannelConnectionsLoadStatus.Content || current.actionInFlight != null) return
        val connections = current.connections ?: return
        val server = current.disconnectConfirmServer ?: return
        if (!current.canDisconnectServer(server)) return
        mutableState.value =
            current.copy(actionInFlight = JointChannelConnectionsAction.DisconnectServer, disconnectFailure = null, effect = null)
        launchAction {
            val result = runCall { dataSource.disconnectServer(server.serverId) }
            val previous = mutableState.value
            if (result is DataResult.Content) {
                // Done once the server agreed; the list is read again for what is left (web
                // re-reads the channel; channel:updated follows on both sides).
                val remaining =
                    previous.connections?.takeIf { it.channelId == connections.channelId }?.let { shown ->
                        shown.copy(servers = shown.servers.filterNot { it.serverId == server.serverId })
                    }
                mutableState.value =
                    previous.copy(
                        actionInFlight = null,
                        connections = remaining ?: previous.connections,
                        disconnectConfirmServerId = null,
                        disconnectFailure = null,
                        effect =
                            JointChannelConnectionsEffect.ServerDisconnected(
                                serverName = server.name.ifBlank { server.slug },
                                channelName = connections.channelName.removePrefix("#"),
                            ),
                    )
                load(force = true)
            } else {
                // Web: every refusal reads as one line in the still-open confirm.
                mutableState.value = previous.copy(actionInFlight = null, disconnectFailure = result.toJointChannelFailure())
            }
        }
    }

    /**
     * Web ConnectYourServersDialog `submit`: every chosen server is answered on its own. All of
     * them connected (or being connected): the sheet closes with a toast; all already connected:
     * closes with that; otherwise it stays open with a line per server and its trace ID, a
     * connected row reads "Connected just now", and only what can still be tried stays chosen.
     * Whatever connected is marked "Connected by you" and the list is read again.
     */
    private fun submitMemberConnect() {
        val current = mutableState.value
        val sheet = current.memberConnectSheet ?: return
        if (current.status != JointChannelConnectionsLoadStatus.Content || current.actionInFlight != null) return
        if (sheet.busy || sheet.closed || sheet.selected.isEmpty()) return
        val chosen = sheet.selected.toList()
        val names = current.memberConnectRows.associate { it.server.id to it.server.name.ifBlank { it.server.slug } }
        mutableState.value =
            current.copy(
                actionInFlight = JointChannelConnectionsAction.MemberConnect,
                memberConnectSheet = sheet.copy(busy = true, lines = emptyList(), traceId = null),
                effect = null,
            )
        launchAction {
            val result = runCall { dataSource.connectMyServers(chosen) }
            val previous = mutableState.value
            val open = previous.memberConnectSheet ?: sheet
            val answer = (result as? DataResult.Content)?.data
            if (answer !is MemberConnectResponse.Answered) {
                val refusal =
                    when (answer) {
                        is MemberConnectResponse.Refused -> memberConnectRequestRefusal(answer.status, answer.code)
                        else -> memberConnectRequestRefusal(status = null, code = null)
                    }
                val traceId =
                    when {
                        answer is MemberConnectResponse.Refused -> answer.traceId
                        result is DataResult.Error -> result.error.traceId
                        result is DataResult.NoPermission -> result.blocker.traceId
                        else -> null
                    }
                mutableState.value =
                    previous.copy(
                        actionInFlight = null,
                        memberConnectSheet =
                            open.copy(
                                busy = false,
                                lines = listOf(MemberConnectLine.Request(refusal.message)),
                                traceId = traceId,
                                closed = open.closed || refusal.closed,
                            ),
                    )
                return@launchAction
            }
            val outcomes = answer.servers
            val connected = outcomes.filterIsInstance<ServerConnectOutcome.Connected>().map { it.serverId }
            val connecting = outcomes.filterIsInstance<ServerConnectOutcome.Connecting>().map { it.serverId }
            val refused = outcomes.filterIsInstance<ServerConnectOutcome.Refused>()
            val went = connected + connecting
            val byYou =
                if (went.isEmpty()) {
                    previous.connectedByYou
                } else {
                    connectedByYouSlot?.let { it.memory.add(it.key, went) } ?: (previous.connectedByYou + went)
                }
            fun namesOf(ids: List<String>) = ids.map { names[it].orEmpty() }
            val next =
                when {
                    refused.isEmpty() ->
                        previous.copy(
                            actionInFlight = null,
                            memberConnectSheet = null,
                            connectedByYou = byYou,
                            effect = JointChannelConnectionsEffect.MemberConnectFinished(connected = namesOf(connected), connecting = namesOf(connecting)),
                        )
                    went.isEmpty() && refused.all { it.code == "already_connected" } ->
                        previous.copy(
                            actionInFlight = null,
                            memberConnectSheet = null,
                            effect = JointChannelConnectionsEffect.MemberConnectFinished(alreadyConnected = namesOf(refused.map { it.serverId })),
                        )
                    else -> {
                        val lines =
                            buildList {
                                if (went.isNotEmpty()) add(MemberConnectLine.Connected(went))
                                refused.forEach { add(MemberConnectLine.Refused(it.serverId, memberConnectRefusalMessage(it))) }
                            }
                        val rowStates =
                            open.rowStates +
                                refused.mapNotNull { outcome -> memberConnectRefusalRowState(outcome)?.let { outcome.serverId to it } }
                        val retry = refused.filter { memberConnectRefusalRowState(it) == null }.map { it.serverId }.toSet()
                        previous.copy(
                            actionInFlight = null,
                            connectedByYou = byYou,
                            memberConnectSheet =
                                open.copy(
                                    busy = false,
                                    done = open.done + went,
                                    rowStates = rowStates,
                                    lines = lines,
                                    traceId = answer.traceId,
                                    selected = open.selected.filter { it in retry }.toSet(),
                                ),
                        )
                    }
                }
            mutableState.value = next
            // The new rows come with the channel read again (web ensureChannel refresh).
            if (went.isNotEmpty()) load(force = true)
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        if (mutableState.value.refreshing) mutableState.value = mutableState.value.copy(refreshing = false)
        actionJob = scope.launch { block() }
    }

    fun clear() {
        loadGeneration += 1L
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
    }
}

/**
 * A load that ended in an error: nothing of the channel stays on screen, but what the page was
 * told about the account and this server (flag, servers and roles, connected by you) does.
 */
private fun JointChannelConnectionsState.loadErrorState(failure: JointChannelFailure?): JointChannelConnectionsState =
    JointChannelConnectionsState(
        status = JointChannelConnectionsLoadStatus.Error,
        loadFailure = failure,
        sameAdminContext = sameAdminContext,
        externalChannelsEnabled = externalChannelsEnabled,
        memberConnectAccount = memberConnectAccount,
        connectedByYou = connectedByYou,
    )

private fun DataResult<*>.jointFailureCodeOrNull(): String? = (this as? DataResult.Error)?.error?.code

private fun JointChannelConnectionsState.withSnapshot(snapshot: JointChannelConnectionsSnapshot): JointChannelConnectionsState =
    copy(
        status = JointChannelConnectionsLoadStatus.Content,
        connections = snapshot.connections,
        canFederate = snapshot.canFederate,
        refreshing = false,
        loadFailure = null,
    )

/** A validation proving the account can no longer read the channel: non-auth 403, or 404. */
internal fun DataResult<*>.isJointConnectionsAccessLost(): Boolean =
    when (this) {
        is DataResult.NoPermission -> !blocker.isAuthSessionBlocker()
        is DataResult.Error -> error.code == "joint_http_404" || error.code == "http_404"
        else -> false
    }

/** Persisted scope name: keep the spelling stable across releases. */
internal const val RaftJointConnectionsSnapshotScope = "raft_joint_connections_v1"

@Serializable
internal data class RaftJointConnectionsDurableRecord(
    val channelId: String,
    val channelName: String,
    val isJoint: Boolean,
    val archived: Boolean,
    val servers: List<RaftJointConnectionsDurableServer>,
    val hasCurrentServerPendingInvites: Boolean,
    val canFederate: Boolean,
    val jointRole: String? = null,
    val federateChannels: Boolean? = null,
    val isPrivate: Boolean = false,
    val joined: Boolean? = null,
)

@Serializable
internal data class RaftJointConnectionsDurableServer(
    val serverId: String,
    val name: String,
    val slug: String,
    val active: Boolean,
    val isCurrentServer: Boolean,
    val plan: String? = null,
    val role: String? = null,
)

internal fun JointChannelConnectionsSnapshot.toJointConnectionsDurableRecord(): RaftJointConnectionsDurableRecord =
    RaftJointConnectionsDurableRecord(
        channelId = connections.channelId.value,
        channelName = connections.channelName,
        isJoint = connections.isJoint,
        archived = connections.archived,
        servers =
            connections.servers.map { server ->
                RaftJointConnectionsDurableServer(
                    serverId = server.serverId,
                    name = server.name,
                    slug = server.slug,
                    active = server.active,
                    isCurrentServer = server.isCurrentServer,
                    plan = server.plan?.name,
                    role = server.role?.name,
                )
            },
        hasCurrentServerPendingInvites = connections.hasCurrentServerPendingInvites,
        canFederate = canFederate,
        jointRole = connections.jointRole?.name,
        federateChannels = connections.federateChannels,
        isPrivate = connections.isPrivate,
        joined = connections.joined,
    )

internal fun RaftJointConnectionsDurableRecord.toJointConnectionsSnapshot(): JointChannelConnectionsSnapshot? {
    val id = channelId.trim().takeIf { it.isNotBlank() } ?: return null
    return JointChannelConnectionsSnapshot(
        connections =
            JointChannelConnections(
                channelId = ChannelId(id),
                channelName = channelName,
                isJoint = isJoint,
                archived = archived,
                servers =
                    servers.map { server ->
                        JointChannelServer(
                            serverId = server.serverId,
                            name = server.name,
                            slug = server.slug,
                            active = server.active,
                            isCurrentServer = server.isCurrentServer,
                            plan = JointChannelServerPlan.entries.firstOrNull { it.name == server.plan },
                            role = JointChannelRole.entries.firstOrNull { it.name == server.role },
                        )
                    },
                hasCurrentServerPendingInvites = hasCurrentServerPendingInvites,
                jointRole = JointChannelRole.entries.firstOrNull { it.name == jointRole },
                federateChannels = federateChannels,
                isPrivate = isPrivate,
                joined = joined,
            ),
        canFederate = canFederate,
    )
}

private suspend fun <T> runCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    }
