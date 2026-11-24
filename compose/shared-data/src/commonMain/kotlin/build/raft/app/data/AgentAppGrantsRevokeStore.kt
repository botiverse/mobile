package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * Revoke on an agent's Apps tab (web AgentAppAccessTab `confirmRevoke` + RevokeAppGrantsDialog,
 * botiverse/slock 31abbdefb #13293, unchanged on staging 5828cdce3e): an App's Revoke access takes
 * every grant it holds for the agent, one grant from its details takes that one, after a
 * confirmation either way. The API revokes one grant per call (IntegrationsActionsPushApi.kt).
 */

/** The Revoke being confirmed: an App's Revoke access ([whole]: all its grants) or one grant from its details. */
@RaftCoreInternalApi data class AgentAppGrantsRevoke(
    val agentId: MemberId,
    val clientId: String,
    val appName: String,
    val whole: Boolean,
    /** The grants still to revoke: all of them at first; after a try, the ones that still stand. */
    val grantIds: List<String>,
    /** Why each of [grantIds] didn't revoke on the last try; null before the first. */
    val failures: Map<String, AgentAppGrantRevokeFailure>? = null,
) {
    /** Every grant left was refused (403): a retry can't help, so the dialog only closes (web `onlyRefused`). */
    val onlyRefused: Boolean
        get() = failures != null && grantIds.isNotEmpty() && grantIds.all { failures[it] is AgentAppGrantRevokeFailure.Refused }
}

@RaftCoreInternalApi data class AgentAppGrantsRevokeState(
    /** The open confirmation; null when none is. */
    val revoking: AgentAppGrantsRevoke? = null,
    /** A try is out: the confirm is busy and the dialog doesn't close. */
    val inFlight: Boolean = false,
)

@RaftCoreInternalApi sealed interface AgentAppGrantsRevokeIntent {
    /** A Revoke was pressed: its confirmation opens (nothing is sent yet). */
    data class Start(
        val agentId: MemberId,
        val clientId: String,
        val appName: String,
        val whole: Boolean,
        val grantIds: List<String>,
    ) : AgentAppGrantsRevokeIntent

    /** The confirm: Revoke access, Try again, or Close when every grant left was refused. */
    data object Confirm : AgentAppGrantsRevokeIntent

    /** Cancel, ×, Back or a tap outside; ignored while a try is out. */
    data object Close : AgentAppGrantsRevokeIntent
}

/**
 * The confirmation's state and the revoke itself. A try revokes each grant in turn ([revokeGrant]);
 * a grant that went, or that the server no longer has (404), leaves the list at once; then the
 * list is read again ([loadAppLogins]). Whatever still stands of the failed ones stays in the
 * dialog with why; when nothing does, the dialog closes. A failed write may have landed (its
 * outcome unknown), so nothing is retried without a press of Try again, and the re-read drops it
 * if it did land; a refused grant (403) is not asked again even then. The list lives in the
 * open profile's agent detail: every change goes there through [applyToDetail]. Single-flight:
 * a second Confirm while a try is out does nothing.
 */
@RaftCoreInternalApi class AgentAppGrantsRevokeStore(
    private val revokeGrant: suspend (grantId: String) -> AgentAppGrantRevokeOutcome,
    private val loadAppLogins: suspend (MemberId) -> DataResult<AgentAppLoginSnapshot>,
    private val applyToDetail: (SharedAgentDetailIntent) -> Unit,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AgentAppGrantsRevokeState())
    val state: StateFlow<AgentAppGrantsRevokeState> = mutableState.asStateFlow()

    fun dispatch(intent: AgentAppGrantsRevokeIntent) {
        when (intent) {
            is AgentAppGrantsRevokeIntent.Start -> start(intent)
            AgentAppGrantsRevokeIntent.Confirm -> confirm()
            AgentAppGrantsRevokeIntent.Close -> {
                val current = mutableState.value
                if (!current.inFlight && current.revoking != null) mutableState.value = current.copy(revoking = null)
            }
        }
    }

    private fun start(intent: AgentAppGrantsRevokeIntent.Start) {
        val current = mutableState.value
        // One confirmation at a time (it is modal); an App with no grant has nothing to revoke.
        if (current.revoking != null || current.inFlight) return
        val grantIds = intent.grantIds.filter { it.isNotBlank() }.distinct()
        if (grantIds.isEmpty()) return
        mutableState.value =
            current.copy(
                revoking =
                    AgentAppGrantsRevoke(
                        agentId = intent.agentId,
                        clientId = intent.clientId,
                        appName = intent.appName,
                        whole = intent.whole,
                        grantIds = grantIds,
                    )
            )
    }

    private fun confirm() {
        val current = mutableState.value
        val target = current.revoking ?: return
        if (current.inFlight) return
        if (target.onlyRefused) {
            // Web: the only button is Close, and it revokes nothing more.
            mutableState.value = current.copy(revoking = null)
            return
        }
        mutableState.value = current.copy(inFlight = true)
        scope.launch {
            val left =
                try {
                    revokeAll(target)
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(inFlight = false)
                    throw cancelled
                }
            mutableState.value = mutableState.value.copy(inFlight = false, revoking = left)
        }
    }

    /** One try over [target]'s grants, then the re-read: what is left to confirm, or null when nothing failed that still stands. */
    private suspend fun revokeAll(target: AgentAppGrantsRevoke): AgentAppGrantsRevoke? {
        val failures = LinkedHashMap<String, AgentAppGrantRevokeFailure>()
        val gone = LinkedHashSet<String>()
        for (grantId in target.grantIds) {
            val earlier = target.failures?.get(grantId)
            if (earlier is AgentAppGrantRevokeFailure.Refused) {
                failures[grantId] = earlier
                continue
            }
            when (val outcome = revokeOnce(grantId)) {
                AgentAppGrantRevokeOutcome.Revoked,
                AgentAppGrantRevokeOutcome.Gone -> gone += grantId
                is AgentAppGrantRevokeOutcome.NotRevoked -> failures[grantId] = outcome.failure
            }
        }
        if (gone.isNotEmpty()) applyToDetail(SharedAgentDetailIntent.RemoveAgentAppGrants(target.agentId, gone))
        val standing = reload(target.agentId)
        val remaining = failures.keys.filter { standing == null || it in standing }
        if (remaining.isEmpty()) return null
        return target.copy(grantIds = remaining, failures = remaining.associateWith { failures.getValue(it) })
    }

    /** The re-read: the ids of the grants that still stand, or null when it failed (then every failure may still stand). */
    private suspend fun reload(agentId: MemberId): Set<String>? =
        when (val fresh = loadOnce(agentId)) {
            is DataResult.Content -> {
                applyToDetail(SharedAgentDetailIntent.SetAgentAppLogins(agentId, fresh.data))
                fresh.data.items.filter { it.isActiveGrant }.mapTo(HashSet()) { it.id }
            }
            is DataResult.Error -> {
                applyToDetail(SharedAgentDetailIntent.SetAgentAppLoginsRefreshFailed(agentId, fresh.error))
                null
            }
            is DataResult.NoPermission -> {
                applyToDetail(SharedAgentDetailIntent.SetAgentAppLoginsRefreshFailed(agentId, fresh.blocker.toRefreshError()))
                null
            }
            is DataResult.Empty -> null
        }

    private suspend fun revokeOnce(grantId: String): AgentAppGrantRevokeOutcome =
        try {
            revokeGrant(grantId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.NoAnswer)
        }

    private suspend fun loadOnce(agentId: MemberId): DataResult<AgentAppLoginSnapshot> =
        try {
            loadAppLogins(agentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "agent_app_logins_reload_failed", traceId = null))
        }
}

/** A refused re-read (401 / 403) as the error the list shows over it, its reason and trace ID kept. */
private fun PermissionBlocker.toRefreshError(): ScreenError =
    ScreenError(
        code = code,
        retryable = false,
        serverMessage = serverMessage,
        serverCode = serverCode,
        traceId = traceId,
    )
