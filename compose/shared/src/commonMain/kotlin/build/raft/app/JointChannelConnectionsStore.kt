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
            val (host, rest) = servers.partition { it.role == JointChannelRole.Host }
            return host + rest
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
) {
    private val mutableState =
        MutableStateFlow(
            cacheSlot?.cache?.peek(cacheSlot.key)?.let { cached -> JointChannelConnectionsState().withSnapshot(cached) }
                ?: JointChannelConnectionsState()
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
                        )
                }
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
                                result.isJointConnectionsAccessLost() ->
                                    JointChannelConnectionsState(
                                        status = JointChannelConnectionsLoadStatus.Error,
                                        loadFailure = failure,
                                    )
                                // A token refresh / server switch overtook the request: the shown
                                // list is still this account's, keep it without an error.
                                previous.connections != null && failure?.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED ->
                                    previous.copy(refreshing = false)
                                previous.connections != null -> previous.copy(refreshing = false, loadFailure = failure)
                                else ->
                                    JointChannelConnectionsState(
                                        status = JointChannelConnectionsLoadStatus.Error,
                                        loadFailure = failure,
                                    )
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
