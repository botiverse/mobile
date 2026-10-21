package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Home notification-center id of a pending joint invite (web `joint-invite:<id>` dismissal key). */
internal const val JOINT_INVITE_HOME_NOTIFICATION_PREFIX = "joint-invite:"

internal fun jointInviteHomeNotificationId(inviteId: String): String = JOINT_INVITE_HOME_NOTIFICATION_PREFIX + inviteId

internal fun String.jointInviteIdFromHomeNotificationIdOrNull(): String? =
    takeIf { it.startsWith(JOINT_INVITE_HOME_NOTIFICATION_PREFIX) }
        ?.removePrefix(JOINT_INVITE_HOME_NOTIFICATION_PREFIX)
        ?.takeIf(String::isNotBlank)

internal interface JointInviteDataSource {
    suspend fun pendingInvites(serverId: ServerId): DataResult<List<PendingJointInvite>>
    suspend fun accept(serverId: ServerId, inviteId: String): DataResult<JointChannelAccepted>
}

internal class NetworkJointInviteDataSource(
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
) : JointInviteDataSource {
    private val repository get() = RealJointChannelRepository(networkClient) { logger("joint_invites $it") }

    override suspend fun pendingInvites(serverId: ServerId): DataResult<List<PendingJointInvite>> =
        repository.pendingInvites(serverId, DataRequestPriority.Background)

    override suspend fun accept(serverId: ServerId, inviteId: String): DataResult<JointChannelAccepted> =
        repository.acceptInvite(serverId, inviteId)
}

internal sealed interface SharedJointInviteEffect {
    data class Accepted(val serverId: String, val inviteId: String, val channel: JointChannelAccepted) : SharedJointInviteEffect

    /** [result] is the failed accept, for the web's `jointInviteAcceptErrorMessage` mapping. */
    data class AcceptFailed(val inviteId: String, val result: DataResult<*>) : SharedJointInviteEffect
}

internal data class SharedJointInviteState(
    /** The server whose invites [invites] lists; anything loaded for another server is discarded. */
    val serverId: String? = null,
    val invites: List<PendingJointInvite> = emptyList(),
    val acceptingInviteId: String? = null,
    val dismissedInviteIds: Set<String> = emptySet(),
    val effect: SharedJointInviteEffect? = null,
) {
    val visibleInvites: List<PendingJointInvite>
        get() = invites.filterNot { it.id in dismissedInviteIds }
}

internal sealed interface SharedJointInviteIntent {
    /**
     * Load the current server's pending invites. Web `usePendingJointInvites`: only owners/admins
     * can be invited (and accept), so nobody else asks; for them the list is cleared.
     */
    data class Load(val serverId: String?, val canBeInvited: Boolean) : SharedJointInviteIntent

    data class Accept(val inviteId: String) : SharedJointInviteIntent

    /** Local only, like the web notification center's dismissal; the invite stays pending server-side. */
    data class Dismiss(val inviteId: String) : SharedJointInviteIntent

    data object ConsumeEffect : SharedJointInviteIntent
}

/**
 * Pending joint-channel invites addressed to the current user on the current server (RFC 075
 * §4.5), surfaced in the Home notification center with an Accept action, as the web
 * notification center does. Loads are single-flight per server; one accept runs at a time;
 * a failed load keeps the last known list (the email link remains a fallback).
 */
internal class SharedJointInviteStore(
    private val dataSource: JointInviteDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedJointInviteState())
    val state: StateFlow<SharedJointInviteState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var loadingServerId: String? = null
    private var acceptJob: Job? = null

    fun dispatch(intent: SharedJointInviteIntent) {
        when (intent) {
            is SharedJointInviteIntent.Load -> load(intent)
            is SharedJointInviteIntent.Accept -> accept(intent.inviteId)
            is SharedJointInviteIntent.Dismiss -> {
                val current = mutableState.value
                if (intent.inviteId !in current.dismissedInviteIds) {
                    mutableState.value = current.copy(dismissedInviteIds = current.dismissedInviteIds + intent.inviteId)
                }
            }
            SharedJointInviteIntent.ConsumeEffect ->
                if (mutableState.value.effect != null) mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    private fun load(intent: SharedJointInviteIntent.Load) {
        val serverId = intent.serverId?.trim()?.takeIf(String::isNotBlank)
        if (serverId == null || !intent.canBeInvited) {
            loadGeneration += 1L
            loadJob?.cancel()
            loadJob = null
            loadingServerId = null
            val current = mutableState.value
            if (current.serverId != serverId || current.invites.isNotEmpty()) {
                mutableState.value = current.copy(serverId = serverId, invites = emptyList())
            }
            return
        }
        if (loadJob?.isActive == true && loadingServerId == serverId) return
        loadJob?.cancel()
        loadGeneration += 1L
        val generation = loadGeneration
        loadingServerId = serverId
        val current = mutableState.value
        if (current.serverId != serverId) {
            // A server switch drops the other server's rows before the new list arrives.
            mutableState.value = current.copy(serverId = serverId, invites = emptyList())
        }
        loadJob =
            scope.launch {
                val result = runInviteCall { dataSource.pendingInvites(ServerId(serverId)) }
                if (generation != loadGeneration) return@launch
                loadingServerId = null
                if (result is DataResult.Content && mutableState.value.serverId == serverId) {
                    if (mutableState.value.invites != result.data) {
                        mutableState.value = mutableState.value.copy(invites = result.data)
                    }
                }
            }
    }

    private fun accept(inviteId: String) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (current.acceptingInviteId != null) return
        if (current.invites.none { it.id == inviteId }) return
        mutableState.value = current.copy(acceptingInviteId = inviteId, effect = null)
        acceptJob =
            scope.launch {
                val result = runInviteCall { dataSource.accept(ServerId(serverId), inviteId) }
                val latest = mutableState.value
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            latest.copy(
                                acceptingInviteId = null,
                                invites = if (latest.serverId == serverId) latest.invites.filterNot { it.id == inviteId } else latest.invites,
                                effect = SharedJointInviteEffect.Accepted(serverId, inviteId, result.data),
                            )
                        else ->
                            latest.copy(
                                acceptingInviteId = null,
                                effect = SharedJointInviteEffect.AcceptFailed(inviteId, result),
                            )
                    }
                // Web refreshes the list after every accept attempt: an expired or taken invite drops out.
                if (result !is DataResult.Content) dispatch(SharedJointInviteIntent.Load(serverId, canBeInvited = true))
            }
    }

    fun clear() {
        loadGeneration += 1L
        loadJob?.cancel()
        acceptJob?.cancel()
        loadJob = null
        acceptJob = null
        loadingServerId = null
    }
}

private suspend fun <T> runInviteCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    }
