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
import build.raft.app.data.JointServerSlugIssue
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RealJointChannelRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.jointServerSlugIssue
import build.raft.app.data.parseJointInvitedPeopleText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

internal enum class JointChannelConnectionsAction { Invite, Resend }

/** Client-side invite form rejection (web parity: checked before any request). */
internal enum class JointInviteValidation { SlugRequired, SlugPattern, PeopleRequired, ServerLimitReached }

internal sealed interface JointChannelConnectionsEffect {
    data class InviteSent(val emailedCount: Int) : JointChannelConnectionsEffect

    data class Resent(val resentCount: Int) : JointChannelConnectionsEffect
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
) {
    /** Web: invite / resend controls render only for `federateChannels` on a live joint channel. */
    val canManageConnections: Boolean
        get() = canFederate && connections?.isJoint == true && connections.archived.not()

    val canResend: Boolean
        get() = canManageConnections && connections?.hasCurrentServerPendingInvites == true
}

internal sealed interface JointChannelConnectionsIntent {
    data object Load : JointChannelConnectionsIntent
    data object Refresh : JointChannelConnectionsIntent
    data class Invite(val targetServerSlug: String, val invitedPeopleText: String) : JointChannelConnectionsIntent
    data object Resend : JointChannelConnectionsIntent
    data object ClearInviteFeedback : JointChannelConnectionsIntent
    data object ConsumeEffect : JointChannelConnectionsIntent
}

internal interface JointChannelConnectionsDataSource {
    suspend fun load(): DataResult<JointChannelConnectionsSnapshot>
    suspend fun invite(targetServerSlug: String, invitedPeople: List<String>): DataResult<JointChannelConnections>
    suspend fun resend(): DataResult<Int>
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

    override suspend fun invite(targetServerSlug: String, invitedPeople: List<String>): DataResult<JointChannelConnections> =
        runner.run { _, networkClient ->
            repository(networkClient).inviteServer(route.channelId, targetServerSlug, invitedPeople)
        }

    override suspend fun resend(): DataResult<Int> =
        runner.run { _, networkClient -> repository(networkClient).resendInvites(route.channelId) }

    private fun repository(networkClient: build.raft.app.data.RaftNetworkClient) =
        RealJointChannelRepository(networkClient) { logger("joint_connections $it") }
}

/**
 * MVI store of a joint channel's Connected servers page: the server list, the
 * "Channel connection invitation" form and Resend Invite. One mutation runs at a time; a
 * failure keeps the loaded list and attaches the error to the action that failed.
 */
internal class JointChannelConnectionsStore(
    private val dataSource: JointChannelConnectionsDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(JointChannelConnectionsState())
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
                val result = runCall { dataSource.load() }
                if (generation != loadGeneration) return@launch
                val previous = mutableState.value
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            previous.copy(
                                status = JointChannelConnectionsLoadStatus.Content,
                                connections = result.data.connections,
                                canFederate = result.data.canFederate,
                                refreshing = false,
                                loadFailure = null,
                            )
                        else -> {
                            val failure = result.toJointChannelFailure()
                            if (previous.connections != null && failure?.code != AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
                                previous.copy(refreshing = false, loadFailure = failure)
                            } else {
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
            val result = runCall { dataSource.invite(slug, people) }
            val previous = mutableState.value
            mutableState.value =
                when (result) {
                    is DataResult.Content ->
                        previous.copy(
                            actionInFlight = null,
                            connections =
                                if (result.data.channelId == connections.channelId) result.data else previous.connections,
                            effect = JointChannelConnectionsEffect.InviteSent(emailedCount = people.size),
                        )
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

private suspend fun <T> runCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    }
