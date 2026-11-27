package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.MemberConnectOwnerRef
import build.raft.app.data.OwnConnectableServers
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftDurableSnapshotKey
import build.raft.app.data.RaftKeyedSnapshotCache
import build.raft.app.data.RealJointChannelRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.InviteeRefusal
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.JointChannelRole
import build.raft.app.data.MemberConnectNewServerResponse
import build.raft.app.data.MemberConnectOptions
import build.raft.app.data.MemberConnectResponse
import build.raft.app.data.MemberConnectServerLimit
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.model.ChannelId
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
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

internal enum class JointChannelConnectionsLoadStatus { Loading, Content, Error }

internal enum class JointChannelConnectionsAction {
    /** The host's admin takes another server out (web JointServerDisconnectAction). */
    DisconnectServer,

    /** A member connects servers they own or administer (web ConnectYourServersDialog). */
    MemberConnect,
}

internal sealed interface JointChannelConnectionsEffect {
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
    /** Server-level `federateChannels` (owner/admin), as the page was told it (kept in the cached snapshot). */
    val canFederate: Boolean,
)

internal data class JointChannelConnectionsState(
    val status: JointChannelConnectionsLoadStatus = JointChannelConnectionsLoadStatus.Loading,
    val connections: JointChannelConnections? = null,
    val canFederate: Boolean = false,
    val refreshing: Boolean = false,
    val loadFailure: JointChannelFailure? = null,
    val actionInFlight: JointChannelConnectionsAction? = null,
    val effect: JointChannelConnectionsEffect? = null,
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
    /**
     * The rows as shown (web `jointServerRows`, slock #16395 EditChannelDialog.tsx): the host
     * leads, the rest keep the server's order. Private channels no longer synthesize a host-only
     * Connected servers section; they use Channel settings › Connect your servers instead.
     */
    val displayServers: List<JointChannelServer>
        get() {
            val servers = connections?.servers.orEmpty()
            val (host, rest) = servers.partition { it.role == JointChannelRole.Host }
            return host + rest
        }

    /** Web `useMemberConnectRows`: the servers the viewer could connect, and why each can or can't be. */
    val memberConnectRows: List<MemberConnectRow>
        get() = memberConnectRows(connections?.memberConnectChannel(), memberConnectAccount)

    /**
     * Web `AddExternalMemberDialog` `connectRows` (slock #16643/#16661): eligible rows for
     * private/joint channels, or `memberConnectRowsOncePrivate` for a public channel (`!isJoint && !isPrivate`).
     */
    val memberConnectInviteRows: List<MemberConnectRow>
        get() {
            val channel = connections?.memberConnectChannel() ?: return emptyList()
            return if (!channel.isJoint && !channel.isPrivate) {
                memberConnectRowsOncePrivate(channel, memberConnectAccount)
            } else {
                memberConnectRows.filter { it.state == MemberConnectRowState.Eligible }
            }
        }

    /**
     * Server rows shown in the sheet:
     * - From Invite people outside ([MemberConnectSheet.eligibleOnly]): eligible rows only, and on
     *   a public channel for host admin ([MemberConnectSheet.hostConnect]) narrowed to the host's
     *   own directly connectable servers once loaded (web `shownRows`).
     * - Otherwise: [memberConnectRows] plus any member-only `askServers` (slock #16361).
     */
    val memberConnectDisplayRows: List<MemberConnectRow>
        get() {
            val sheet = memberConnectSheet
            if (sheet?.eligibleOnly == true) {
                val base = memberConnectInviteRows
                val directIds = sheet.hostOwnDirectServerIds
                return if (sheet.hostConnect && directIds != null) {
                    base.filter { it.server.id in directIds }
                } else {
                    base
                }
            }
            return memberConnectDisplayRows(memberConnectRows, sheet?.askServers.orEmpty())
        }

    /** Web `memberConnect.available`: the ⋯ or Connect row, only with a server to connect. */
    val memberConnectAvailable: Boolean
        get() =
            connections?.conversionInProgress != true &&
                memberConnectRows.any { it.state == MemberConnectRowState.Eligible }

    /**
     * Web `useGuestConnectEntry` / `useGuestConnectDialog` (slock #16375/#16389): true when the
     * viewer is a Guest of the host bound to this channel and no conversion is under way (rows may
     * be empty so the Guest can still create a server or see `askServers`).
     */
    val guestConnectEntryAvailable: Boolean
        get() =
            memberConnectGuestEntryAvailable(
                channel = connections?.memberConnectChannel(),
                account = memberConnectAccount,
                jointRole = connections?.jointRole,
                conversionInProgress = connections?.conversionInProgress == true,
            )

    /** The connect is to a private (or public-made-private) channel: it becomes shared (web `channelType === "private"`). */
    val memberConnectToPrivate: Boolean
        get() = connections?.let { !it.isJoint && (it.isPrivate || memberConnectSheet?.hostConnect == true) } == true

    /** Web `memberConnectHostName`: the host row's name, else this server's. */
    val memberConnectHostName: String
        get() =
            displayServers.firstOrNull { it.role == JointChannelRole.Host }?.let { it.name.ifBlank { it.slug } }
                ?: memberConnectAccount?.currentServerName.orEmpty()

    /** Web `guest`: a Guest of the host bound to this channel; connecting ends that seat (never true for hostAdmin). */
    val memberConnectGuest: Boolean
        get() =
            memberConnectSheet?.hostAdmin != true &&
                memberConnectAccount?.currentServerRole == "guest" &&
                (memberConnectToPrivate || connections?.jointRole == JointChannelRole.Host)

    /** A row of the sheet as it reads now: a refusal may have changed it. */
    fun memberConnectRowState(row: MemberConnectRow): MemberConnectRowState =
        memberConnectSheet?.rowStates?.get(row.server.id) ?: row.state

    /**
     * Web `ConnectYourServersDialog`: Free-plan limit overlay applies only to eligible rows not
     * already connected in this sheet session.
     */
    fun memberConnectLimit(row: MemberConnectRow): MemberConnectServerLimit? {
        val sheet = memberConnectSheet ?: return null
        if (row.server.id in sheet.done || memberConnectRowState(row) != MemberConnectRowState.Eligible) return null
        return sheet.limits[row.server.id]
    }

    /** Web ChoiceList `disabled`: only an eligible row not connected yet, not locked, and not at Free limit, while the route is open. */
    fun memberConnectRowSelectable(row: MemberConnectRow): Boolean {
        val sheet = memberConnectSheet ?: return false
        return !sheet.closed &&
            !sheet.locked &&
            row.server.id !in sheet.done &&
            memberConnectRowState(row) == MemberConnectRowState.Eligible &&
            memberConnectLimit(row)?.atLimit != true
    }

    /** The host row's Host badge (web: `server.role === "host"`). */
    fun showsHostBadge(server: JointChannelServer): Boolean = server.role == JointChannelRole.Host

    /** A connected server needs no badge; a legacy pending row still says so (web Q2, #wg-rbac:2ee0381d). */
    fun showsStatusBadge(server: JointChannelServer): Boolean = !server.active

    /**
     * Web `canDisconnectJointServers` and its row check (#wg-rbac:#133): the channel's own
     * `federateChannels` is true (the server's check, never a server-wide fallback or a
     * `jointRole == Host` check — a host owner/admin may view from a connected server's copy where
     * they are only a local member), and the row is another server's active non-host copy.
     * Everything here is what the server sent; the route re-checks it.
     */
    fun canDisconnectServer(server: JointChannelServer): Boolean {
        val current = connections ?: return false
        return current.isJoint &&
            current.federateChannels == true &&
            server.active &&
            server.role != JointChannelRole.Host &&
            !server.isCurrentServer
    }

    /** The row whose Disconnect confirm is open, while it can still be disconnected. */
    val disconnectConfirmServer: JointChannelServer?
        get() = disconnectConfirmServerId?.let { id -> connections?.servers?.firstOrNull { it.serverId == id } }
}

internal sealed interface JointChannelConnectionsIntent {
    data object Load : JointChannelConnectionsIntent
    data object Refresh : JointChannelConnectionsIntent
    /** Seeds the channel metadata before the first network answer arrives (e.g. private Connect row / Guest entry). */
    data class SeedChannel(val connections: JointChannelConnections) : JointChannelConnectionsIntent
    /** The account's servers and roles, as the stored session holds them. */
    data class SetMemberConnectAccount(val account: MemberConnectAccount?) : JointChannelConnectionsIntent
    /** Connected servers ⋯ or Channel settings › Connect your servers. */
    data object OpenMemberConnect : JointChannelConnectionsIntent
    /** Guest's Connect your servers entry (slock #16375/#16389): opens with Guest helper line + Create a server and connect. */
    data object OpenGuestMemberConnect : JointChannelConnectionsIntent
    /**
     * Invite people outside's quiet footer link "Connect your servers" (slock #16643/#16661), or
     * a connect-only `channel:invite_external` card with `addresses == 0` ([sayWhenEmpty] = true,
     * slock #16671).
     */
    data class OpenFromExternalInvite(
        val publicChannel: PublicChannelConnect? = null,
        val sayWhenEmpty: Boolean = false,
    ) : JointChannelConnectionsIntent
    /** The sheet's × / Cancel / Close (ignored while a request runs). */
    data object DismissMemberConnect : JointChannelConnectionsIntent
    data class SetMemberConnectSelection(val serverIds: Set<String>) : JointChannelConnectionsIntent
    /** The sheet's Connect. */
    data object SubmitMemberConnect : JointChannelConnectionsIntent
    /** "Create a server and connect" inside the Guest's sheet (slock #16353/#16375). */
    data object OpenMemberConnectNewServer : JointChannelConnectionsIntent
    /** Back from "Create a server and connect" to the server list (ignored while busy). */
    data object CloseMemberConnectNewServer : JointChannelConnectionsIntent
    data class SetMemberConnectNewServerName(val name: String) : JointChannelConnectionsIntent
    data class SetMemberConnectNewServerSlug(val slug: String) : JointChannelConnectionsIntent
    /** Create and connect the new server (`POST channels/:id/connect-new-server`). */
    data object SubmitMemberConnectNewServer : JointChannelConnectionsIntent
    /** A row's Disconnect: open its confirm. */
    data class ConfirmDisconnectServer(val serverId: String) : JointChannelConnectionsIntent
    /** The confirm's Cancel (ignored while the request runs). */
    data object DismissDisconnectServer : JointChannelConnectionsIntent
    /** The confirm's Disconnect {server}. */
    data object DisconnectServer : JointChannelConnectionsIntent
    data object ConsumeEffect : JointChannelConnectionsIntent
}

internal interface JointChannelConnectionsDataSource {
    suspend fun load(): DataResult<JointChannelConnectionsSnapshot>
    suspend fun disconnectServer(serverId: String): DataResult<Unit>
    suspend fun connectMyServers(serverIds: List<String>): DataResult<MemberConnectResponse>
    suspend fun connectNewServer(name: String, slug: String): DataResult<MemberConnectNewServerResponse> =
        DataResult.Error(ScreenError(code = "unsupported", retryable = false))
    suspend fun listMemberConnectLimits(): Map<String, MemberConnectServerLimit> = emptyMap()
    suspend fun listMemberConnectOptions(): MemberConnectOptions = MemberConnectOptions(limits = listMemberConnectLimits())
    suspend fun ownConnectableServers(): DataResult<ExternalInviteAnswer<OwnConnectableServers>> =
        DataResult.Content(ExternalInviteAnswer.Ok(OwnConnectableServers()))
    suspend fun connectOwnServers(
        serverIds: List<String>,
        makePrivate: Boolean,
    ): DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>> =
        DataResult.Error(ScreenError(code = "unsupported", retryable = false))
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

    override suspend fun disconnectServer(serverId: String): DataResult<Unit> =
        runner.run { _, networkClient -> repository(networkClient).disconnectServer(route.channelId, serverId) }

    override suspend fun connectMyServers(serverIds: List<String>): DataResult<MemberConnectResponse> =
        runner.run { _, networkClient -> repository(networkClient).connectMyServers(route.channelId, serverIds) }

    override suspend fun connectNewServer(name: String, slug: String): DataResult<MemberConnectNewServerResponse> =
        runner.run { _, networkClient -> repository(networkClient).connectNewServer(route.channelId, name, slug) }

    override suspend fun listMemberConnectLimits(): Map<String, MemberConnectServerLimit> =
        listMemberConnectOptions().limits

    override suspend fun listMemberConnectOptions(): MemberConnectOptions {
        val result =
            runner.run { _, networkClient ->
                DataResult.Content(repository(networkClient).listMemberConnectOptions(route.channelId))
            }
        return (result as? DataResult.Content)?.data ?: MemberConnectOptions()
    }

    override suspend fun ownConnectableServers(): DataResult<ExternalInviteAnswer<OwnConnectableServers>> =
        runner.run { _, networkClient -> repository(networkClient).ownConnectableServers() }

    override suspend fun connectOwnServers(
        serverIds: List<String>,
        makePrivate: Boolean,
    ): DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>> =
        runner.run { _, networkClient -> repository(networkClient).connectOwnServers(route.channelId, serverIds, makePrivate) }

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
 * MVI store of a joint channel's Connected servers page: the server list, the host's Disconnect
 * of another server and Connect your servers. One mutation runs at a time; a failure keeps the
 * loaded list and attaches the error to the action that failed.
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
    private val onConnectionsChanged: () -> Unit = {},
    private val onServersChanged: () -> Unit = {},
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
    private var loadedFromNetwork = cacheSlot?.cache?.peek(cacheSlot.key) != null
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var memberConnectLimitsJob: Job? = null

    fun dispatch(intent: JointChannelConnectionsIntent) {
        when (intent) {
            JointChannelConnectionsIntent.Load -> load(force = false)
            JointChannelConnectionsIntent.Refresh -> load(force = true)
            is JointChannelConnectionsIntent.SeedChannel -> {
                val current = mutableState.value
                if (current.connections == null || (!loadedFromNetwork && current.memberConnectSheet == null)) {
                    mutableState.value =
                        current.copy(
                            status = JointChannelConnectionsLoadStatus.Content,
                            connections = intent.connections,
                        )
                }
            }
            is JointChannelConnectionsIntent.SetMemberConnectAccount ->
                if (mutableState.value.memberConnectAccount != intent.account) {
                    mutableState.value = mutableState.value.copy(memberConnectAccount = intent.account)
                }
            JointChannelConnectionsIntent.OpenMemberConnect -> {
                val current = mutableState.value
                if (current.memberConnectSheet == null && current.actionInFlight == null && current.memberConnectAvailable) {
                    mutableState.value = current.copy(memberConnectSheet = MemberConnectSheet(guestEntry = false))
                    fetchMemberConnectOptions()
                }
            }
            JointChannelConnectionsIntent.OpenGuestMemberConnect -> {
                val current = mutableState.value
                if (current.memberConnectSheet == null && current.actionInFlight == null && current.guestConnectEntryAvailable) {
                    mutableState.value = current.copy(memberConnectSheet = MemberConnectSheet(guestEntry = true))
                    fetchMemberConnectOptions()
                }
            }
            is JointChannelConnectionsIntent.OpenFromExternalInvite -> {
                val current = mutableState.value
                val rows = current.memberConnectInviteRows
                if (current.memberConnectSheet == null && current.actionInFlight == null && (rows.isNotEmpty() || intent.sayWhenEmpty)) {
                    val sheet =
                        MemberConnectSheet(
                            guestEntry = false,
                            hostAdmin = true,
                            eligibleOnly = true,
                            publicChannel = intent.publicChannel,
                            sayWhenEmpty = intent.sayWhenEmpty,
                        )
                    mutableState.value = current.copy(memberConnectSheet = sheet)
                    if (rows.isNotEmpty()) {
                        when {
                            intent.publicChannel == null -> fetchMemberConnectOptions()
                            intent.publicChannel.canMakePrivate -> fetchHostOwnConnectableServers()
                            // Public channel non-admin: neither route is called (member route refuses public channels).
                        }
                    }
                }
            }
            JointChannelConnectionsIntent.DismissMemberConnect -> {
                val current = mutableState.value
                if (current.memberConnectSheet != null && !current.memberConnectSheet.anyBusy) {
                    memberConnectLimitsJob?.cancel()
                    memberConnectLimitsJob = null
                    mutableState.value = current.copy(memberConnectSheet = null)
                }
            }
            is JointChannelConnectionsIntent.SetMemberConnectSelection -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                if (sheet != null && !sheet.busy && !sheet.locked) {
                    val selectable = current.memberConnectDisplayRows.filter(current::memberConnectRowSelectable).map { it.server.id }.toSet()
                    val next = intent.serverIds.filter { it in selectable }.toSet()
                    if (next != sheet.selected) mutableState.value = current.copy(memberConnectSheet = sheet.copy(selected = next))
                }
            }
            JointChannelConnectionsIntent.SubmitMemberConnect -> submitMemberConnect()
            JointChannelConnectionsIntent.OpenMemberConnectNewServer -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                if (sheet != null && sheet.guestEntry && !sheet.busy && !sheet.closed && sheet.newServer == null) {
                    mutableState.value =
                        current.copy(
                            memberConnectSheet = sheet.copy(newServer = MemberConnectNewServerFormState()),
                        )
                }
            }
            JointChannelConnectionsIntent.CloseMemberConnectNewServer -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                val form = sheet?.newServer
                if (form != null && !form.busy) {
                    mutableState.value = current.copy(memberConnectSheet = sheet.copy(newServer = null))
                }
            }
            is JointChannelConnectionsIntent.SetMemberConnectNewServerName -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                val form = sheet?.newServer
                if (form != null && !form.busy && !form.kept) {
                    mutableState.value =
                        current.copy(
                            memberConnectSheet =
                                sheet.copy(
                                    newServer =
                                        form.copy(
                                            name = intent.name,
                                            slug = if (form.slugTouched) form.slug else toServerSlug(intent.name),
                                            fieldErrors = NewServerFieldErrors(),
                                        ),
                                ),
                        )
                }
            }
            is JointChannelConnectionsIntent.SetMemberConnectNewServerSlug -> {
                val current = mutableState.value
                val sheet = current.memberConnectSheet
                val form = sheet?.newServer
                if (form != null && !form.busy && !form.kept) {
                    mutableState.value =
                        current.copy(
                            memberConnectSheet =
                                sheet.copy(
                                    newServer =
                                        form.copy(
                                            slug = toServerSlug(intent.slug),
                                            slugTouched = true,
                                            fieldErrors = form.fieldErrors.copy(slug = null),
                                        ),
                                ),
                        )
                }
            }
            JointChannelConnectionsIntent.SubmitMemberConnectNewServer -> submitMemberConnectNewServer()
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
                            loadedFromNetwork = true
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
                        is DataResult.Content -> {
                            loadedFromNetwork = true
                            previous.withSnapshot(result.data)
                        }
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
                onConnectionsChanged()
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
        if (sheet.busy || sheet.closed || sheet.locked || sheet.selected.isEmpty()) return
        val hostConnect = sheet.hostConnect
        val chosen = sheet.selected.toList()
        val names = current.memberConnectDisplayRows.associate { it.server.id to it.server.name.ifBlank { it.server.slug } }
        mutableState.value =
            current.copy(
                actionInFlight = JointChannelConnectionsAction.MemberConnect,
                memberConnectSheet = sheet.copy(busy = true, lines = emptyList(), traceId = null),
                effect = null,
            )
        launchAction {
            val outcomesAnswer: Pair<List<ServerConnectOutcome>, String?>?
            val refusalLineAndClosed: Pair<MemberConnectLine.Request, Boolean>?
            val refusalTraceId: String?
            if (hostConnect) {
                val result = runCall { dataSource.connectOwnServers(chosen, makePrivate = true) }
                val answer = (result as? DataResult.Content)?.data
                when (answer) {
                    is ExternalInviteAnswer.Ok -> {
                        outcomesAnswer = answer.value to answer.traceId
                        refusalLineAndClosed = null
                        refusalTraceId = null
                    }
                    is ExternalInviteAnswer.Refused -> {
                        outcomesAnswer = null
                        refusalLineAndClosed =
                            resolveHostConnectRequestLine(
                                status = answer.refusal.status,
                                code = answer.refusal.code,
                                error = answer.refusal.error,
                                traceId = answer.refusal.traceId,
                            )
                        refusalTraceId = answer.refusal.traceId
                    }
                    null -> {
                        outcomesAnswer = null
                        val trace =
                            when (result) {
                                is DataResult.Error -> result.error.traceId
                                is DataResult.NoPermission -> result.blocker.traceId
                                else -> null
                            }
                        refusalLineAndClosed = resolveHostConnectRequestLine(status = null, code = null, error = null, traceId = trace)
                        refusalTraceId = trace
                    }
                }
            } else {
                val result = runCall { dataSource.connectMyServers(chosen) }
                val answer = (result as? DataResult.Content)?.data
                when (answer) {
                    is MemberConnectResponse.Answered -> {
                        outcomesAnswer = answer.servers to answer.traceId
                        refusalLineAndClosed = null
                        refusalTraceId = null
                    }
                    is MemberConnectResponse.Refused -> {
                        outcomesAnswer = null
                        refusalLineAndClosed =
                            resolveMemberConnectRequestLine(answer.status, answer.code, answer.error, answer.traceId)
                        refusalTraceId = answer.traceId
                    }
                    null -> {
                        outcomesAnswer = null
                        val trace =
                            when (result) {
                                is DataResult.Error -> result.error.traceId
                                is DataResult.NoPermission -> result.blocker.traceId
                                else -> null
                            }
                        refusalLineAndClosed = resolveMemberConnectRequestLine(status = null, code = null, error = null, traceId = null)
                        refusalTraceId = trace
                    }
                }
            }
            val previous = mutableState.value
            val open = previous.memberConnectSheet ?: sheet
            if (outcomesAnswer == null) {
                val (requestLine, closed) = refusalLineAndClosed ?: (MemberConnectLine.Request(MemberConnectMessage.Generic) to false)
                mutableState.value =
                    previous.copy(
                        actionInFlight = null,
                        memberConnectSheet =
                            open.copy(
                                busy = false,
                                lines = listOf(requestLine),
                                traceId = refusalTraceId,
                                closed = open.closed || closed,
                                selected = open.selected.filterNot { open.limits[it]?.atLimit == true }.toSet(),
                            ),
                    )
                return@launchAction
            }
            val (outcomes, answerTraceId) = outcomesAnswer
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
                                refused.forEach { add(if (hostConnect) hostConnectRefusedLine(it) else memberConnectRefusedLine(it)) }
                            }
                        val rowStates =
                            open.rowStates +
                                refused.mapNotNull { outcome -> memberConnectRefusalRowState(outcome)?.let { outcome.serverId to it } }
                        val retry =
                            refused
                                .filter { memberConnectRefusalRowState(it) == null && open.limits[it.serverId]?.atLimit != true }
                                .map { it.serverId }
                                .toSet()
                        previous.copy(
                            actionInFlight = null,
                            connectedByYou = byYou,
                            memberConnectSheet =
                                open.copy(
                                    busy = false,
                                    done = open.done + went,
                                    rowStates = rowStates,
                                    lines = lines,
                                    traceId = answerTraceId,
                                    selected = open.selected.filter { it in retry }.toSet(),
                                ),
                        )
                    }
                }
            mutableState.value = next
            if (next.memberConnectSheet == null) {
                memberConnectLimitsJob?.cancel()
                memberConnectLimitsJob = null
            }
            // The new rows come with the channel read again (web ensureChannel refresh).
            if (went.isNotEmpty()) {
                load(force = true)
                onConnectionsChanged()
            }
        }
    }

    /**
     * Web `ConnectNewServerForm` `submit` (slock #16353/#16375): validates name & slug, posts
     * `POST channels/:id/connect-new-server`. On `connected` / `connecting`, closes with the same
     * toast as the server list, refreshes the channel and the user's server list. If the server
     * was created and connecting failed (`serverId` returned or refused outcome), keeps the form
     * disabled with the "created" + refusal banner and refreshes the user's server list.
     */
    private fun submitMemberConnectNewServer() {
        val current = mutableState.value
        val sheet = current.memberConnectSheet ?: return
        val form = sheet.newServer ?: return
        if (current.actionInFlight != null || form.busy || form.kept) return
        val trimmed = form.name.trim()
        val slug = form.slug
        val invalid = newServerFieldErrors(trimmed, slug)
        if (invalid.any) {
            mutableState.value =
                current.copy(
                    memberConnectSheet = sheet.copy(newServer = form.copy(fieldErrors = invalid, lines = emptyList(), traceId = null)),
                )
            return
        }
        mutableState.value =
            current.copy(
                actionInFlight = JointChannelConnectionsAction.MemberConnect,
                memberConnectSheet =
                    sheet.copy(
                        newServer = form.copy(busy = true, fieldErrors = NewServerFieldErrors(), lines = emptyList(), traceId = null),
                    ),
                effect = null,
            )
        launchAction {
            val result = runCall { dataSource.connectNewServer(trimmed, slug) }
            val previous = mutableState.value
            val open = previous.memberConnectSheet ?: sheet
            val openForm = open.newServer ?: form
            val answer = (result as? DataResult.Content)?.data
            when (answer) {
                is MemberConnectNewServerResponse.Answered -> {
                    val outcome = answer.servers.firstOrNull { it.serverId == answer.serverId } ?: answer.servers.firstOrNull()
                    if (outcome is ServerConnectOutcome.Connected || outcome is ServerConnectOutcome.Connecting) {
                        val byYou =
                            connectedByYouSlot?.let { it.memory.add(it.key, listOf(answer.serverId)) }
                                ?: (previous.connectedByYou + answer.serverId)
                        val effect =
                            if (outcome is ServerConnectOutcome.Connected) {
                                JointChannelConnectionsEffect.MemberConnectFinished(connected = listOf(trimmed))
                            } else {
                                JointChannelConnectionsEffect.MemberConnectFinished(connecting = listOf(trimmed))
                            }
                        memberConnectLimitsJob?.cancel()
                        memberConnectLimitsJob = null
                        mutableState.value =
                            previous.copy(
                                actionInFlight = null,
                                memberConnectSheet = null,
                                connectedByYou = byYou,
                                effect = effect,
                            )
                        load(force = true)
                        onConnectionsChanged()
                        onServersChanged()
                        return@launchAction
                    }
                    // Server created, then outcome refused: caller keeps the new empty server.
                    val refusalLine =
                        if (outcome is ServerConnectOutcome.Refused) {
                            resolveMemberConnectNewServerLine(
                                status = outcome.status.takeIf { it > 0 },
                                code = outcome.code,
                                error = outcome.error,
                                traceId = answer.traceId,
                            )
                        } else {
                            MemberConnectLine.Request(MemberConnectMessage.Generic)
                        }
                    mutableState.value =
                        previous.copy(
                            actionInFlight = null,
                            memberConnectSheet =
                                open.copy(
                                    newServer =
                                        openForm.copy(
                                            busy = false,
                                            kept = true,
                                            lines = listOf(MemberConnectLine.NewServerKept(trimmed), refusalLine),
                                            traceId = answer.traceId,
                                        ),
                                ),
                        )
                    onServersChanged()
                }
                is MemberConnectNewServerResponse.Refused -> {
                    if (answer.serverId != null) {
                        val refusalLine =
                            resolveMemberConnectNewServerLine(
                                status = answer.status,
                                code = answer.code,
                                error = answer.error,
                                traceId = answer.traceId,
                            )
                        mutableState.value =
                            previous.copy(
                                actionInFlight = null,
                                memberConnectSheet =
                                    open.copy(
                                        newServer =
                                            openForm.copy(
                                                busy = false,
                                                kept = true,
                                                lines = listOf(MemberConnectLine.NewServerKept(trimmed), refusalLine),
                                                traceId = answer.traceId,
                                            ),
                                    ),
                            )
                        onServersChanged()
                        return@launchAction
                    }
                    val fieldRefused =
                        newServerInputRefusal(
                            InviteeRefusal(status = answer.status, code = answer.code, serverId = answer.serverId, error = answer.error),
                            trimmed,
                            slug,
                        )
                    if (fieldRefused != null) {
                        mutableState.value =
                            previous.copy(
                                actionInFlight = null,
                                memberConnectSheet =
                                    open.copy(
                                        newServer = openForm.copy(busy = false, fieldErrors = fieldRefused, lines = emptyList(), traceId = null),
                                    ),
                            )
                        return@launchAction
                    }
                    val refusalLine =
                        resolveMemberConnectNewServerLine(
                            status = answer.status,
                            code = answer.code,
                            error = answer.error,
                            traceId = answer.traceId,
                        )
                    mutableState.value =
                        previous.copy(
                            actionInFlight = null,
                            memberConnectSheet =
                                open.copy(
                                    newServer = openForm.copy(busy = false, lines = listOf(refusalLine), traceId = answer.traceId),
                                ),
                        )
                }
                null -> {
                    val traceId =
                        when (result) {
                            is DataResult.Error -> result.error.traceId
                            is DataResult.NoPermission -> result.blocker.traceId
                            else -> null
                        }
                    mutableState.value =
                        previous.copy(
                            actionInFlight = null,
                            memberConnectSheet =
                                open.copy(
                                    newServer =
                                        openForm.copy(
                                            busy = false,
                                            lines = listOf(MemberConnectLine.Request(MemberConnectMessage.Generic)),
                                            traceId = traceId,
                                        ),
                                ),
                        )
                }
            }
        }
    }

    /**
     * Web `useOwnConnectableServers(hostConnect)` (slock #16661): on a public channel for host
     * owner/admin, loads `GET channels/external-connect-servers` (never `connect-my-servers`, which
     * refuses public channels), filters rows to directly connectable servers, and reads each
     * server's Free-plan limit.
     */
    private fun fetchHostOwnConnectableServers() {
        memberConnectLimitsJob?.cancel()
        memberConnectLimitsJob =
            scope.launch {
                val ownServers =
                    try {
                        ((dataSource.ownConnectableServers() as? DataResult.Content)?.data as? ExternalInviteAnswer.Ok)?.value?.servers.orEmpty()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        emptyList()
                    }
                val current = mutableState.value
                val open = current.memberConnectSheet ?: return@launch
                if (!open.hostConnect || ownServers.isEmpty()) return@launch
                val directIds = ownServers.filter { it.canConnectDirectly }.map { it.id }.toSet()
                val limits =
                    ownServers.associate { server ->
                        server.id to
                            MemberConnectServerLimit(
                                serverId = server.id,
                                atLimit = server.atLimit,
                                canUpgrade = server.canUpgrade,
                                owners =
                                    server.owners.map { owner ->
                                        MemberConnectOwnerRef(id = owner.id, name = owner.name, avatarUrl = owner.avatarUrl)
                                    },
                            )
                    }
                val nextSelected =
                    if (open.busy) {
                        open.selected
                    } else {
                        open.selected.filter { id -> id in directIds && limits[id]?.atLimit != true }.toSet()
                    }
                mutableState.value =
                    current.copy(
                        memberConnectSheet =
                            open.copy(
                                limits = limits,
                                hostOwnDirectServerIds = directIds,
                                selected = nextSelected,
                            ),
                    )
            }
    }

    private fun fetchMemberConnectOptions() {
        memberConnectLimitsJob?.cancel()
        memberConnectLimitsJob =
            scope.launch {
                val options =
                    try {
                        dataSource.listMemberConnectOptions()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        MemberConnectOptions()
                    }
                val current = mutableState.value
                val open = current.memberConnectSheet ?: return@launch
                if (options.limits.isEmpty() && options.askServers.isEmpty()) return@launch
                val nextSelected =
                    if (open.busy) {
                        open.selected
                    } else {
                        open.selected.filterNot { options.limits[it]?.atLimit == true }.toSet()
                    }
                mutableState.value =
                    current.copy(
                        memberConnectSheet =
                            open.copy(
                                limits = options.limits,
                                askServers = options.askServers,
                                selected = nextSelected,
                            ),
                    )
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
        memberConnectLimitsJob?.cancel()
        loadJob = null
        actionJob = null
        memberConnectLimitsJob = null
    }
}

/**
 * A load that ended in an error: nothing of the channel stays on screen, but what the page was
 * told about the account (its servers and roles, connected by you) does.
 */
private fun JointChannelConnectionsState.loadErrorState(failure: JointChannelFailure?): JointChannelConnectionsState =
    JointChannelConnectionsState(
        status = JointChannelConnectionsLoadStatus.Error,
        loadFailure = failure,
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
