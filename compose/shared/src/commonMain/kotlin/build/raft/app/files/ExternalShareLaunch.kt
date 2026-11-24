package build.raft.app

import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareServerOption
import build.raft.app.data.externalShareOpaqueTokenOrNull
import build.raft.app.compat.json.JSONObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val ExternalShareLaunchIntent = "open_share_to_raft"
internal const val ExternalShareLaunchIntentKey = "intent"
internal const val ExternalShareLaunchTokenKey = "externalShareToken"

internal data class ExternalShareLaunchRequest(val token: String)

/**
 * Share launch is identity + intent only. Raw text, files, destinations, auth, and envelope JSON
 * are rejected instead of being treated as a fallback snapshot.
 */
internal fun JSONObject.externalShareLaunchRequestOrNull(): ExternalShareLaunchRequest? {
    if (optString(ExternalShareLaunchIntentKey).trim() != ExternalShareLaunchIntent) return null
    if (ExternalShareForbiddenPageDataKeys.any(::has)) return null
    val token = optString(ExternalShareLaunchTokenKey).externalShareOpaqueTokenOrNull() ?: return null
    return ExternalShareLaunchRequest(token)
}

private val ExternalShareForbiddenPageDataKeys =
    listOf(
        "text",
        "subject",
        "items",
        "attachments",
        "targets",
        "envelope",
        "accessToken",
        "refreshToken"
    )

internal data class ExternalShareLaunchAuthority(
    val accountId: String,
    val serverId: String,
    val servers: List<ExternalShareServerOption> =
        listOf(ExternalShareServerOption(id = serverId, title = serverId))
) {
    fun normalizedOrNull(): ExternalShareLaunchAuthority? {
        val account = accountId.trim()
        val normalizedServers =
            servers.mapNotNull { server ->
                val id = server.id.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ExternalShareServerOption(
                    id = id,
                    title = server.title.trim().takeIf { it.isNotBlank() } ?: id,
                    slug = server.slug?.trim()?.takeIf { it.isNotBlank() },
                    avatarUrl = server.avatarUrl?.trim()?.takeIf { it.isNotBlank() },
                    publicPageLive = server.publicPageLive
                )
            }.distinctBy { it.id }
        val requestedServer = serverId.trim()
        val initialServer =
            requestedServer.takeIf { requested -> normalizedServers.any { it.id == requested } }
                ?: normalizedServers.firstOrNull()?.id
        return if (account.isBlank() || initialServer == null) {
            null
        } else {
            copy(accountId = account, serverId = initialServer, servers = normalizedServers)
        }
    }
}

internal enum class ExternalShareLaunchDecision {
    NoLaunch,
    AwaitingAuthentication,
    Dispatched,
    Duplicate
}

/**
 * One mounted AppShell dispatches one Load for a token. A newly-created coordinator after process
 * restore intentionally dispatches again so the durable draft wins over the consumed envelope.
 */
internal class ExternalShareLaunchCoordinator(
    private val dispatch: (ExternalShareIntent.Load) -> Unit
) {
    private var pending: ExternalShareLaunchRequest? = null
    private var dispatchedToken: String? = null

    fun accept(request: ExternalShareLaunchRequest?): ExternalShareLaunchDecision {
        request ?: return ExternalShareLaunchDecision.NoLaunch
        if (request.token == dispatchedToken || request.token == pending?.token) {
            return ExternalShareLaunchDecision.Duplicate
        }
        pending = request
        return ExternalShareLaunchDecision.AwaitingAuthentication
    }

    fun reconcile(authority: ExternalShareLaunchAuthority?): ExternalShareLaunchDecision {
        val request = pending ?: return ExternalShareLaunchDecision.NoLaunch
        val ready = authority?.normalizedOrNull()
            ?: return ExternalShareLaunchDecision.AwaitingAuthentication
        if (request.token == dispatchedToken) {
            pending = null
            return ExternalShareLaunchDecision.Duplicate
        }
        dispatchedToken = request.token
        pending = null
        dispatch(
            ExternalShareIntent.Load(
                token = request.token,
                accountId = ready.accountId,
                serverId = ready.serverId,
                serverOptions = ready.servers
            )
        )
        return ExternalShareLaunchDecision.Dispatched
    }
}

internal data class RaftPendingExternalShareLaunch(
    val id: Long,
    val request: ExternalShareLaunchRequest,
    val targetShellInstanceId: String? = null,
)

/**
 * Process-local single slot delivering an external share token into an already-live app shell
 * without tearing down or recreating its host Activity. Newest share wins; taken once by the
 * matching mounted shell once its session is ready.
 */
internal class RaftExternalShareLaunchInbox {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingExternalShareLaunch?>(null)
    val pending: StateFlow<RaftPendingExternalShareLaunch?> = mutablePending.asStateFlow()

    fun submit(
        request: ExternalShareLaunchRequest,
        targetShellInstanceId: String? = null,
    ): RaftPendingExternalShareLaunch = synchronized(lock) {
        RaftPendingExternalShareLaunch(
            id = nextId++,
            request = request,
            targetShellInstanceId = targetShellInstanceId?.trim()?.takeIf(String::isNotBlank),
        ).also { mutablePending.value = it }
    }

    fun take(id: Long, shellInstanceId: String? = null): RaftPendingExternalShareLaunch? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        val target = current.targetShellInstanceId
        if (target != null && target != shellInstanceId?.trim()?.takeIf(String::isNotBlank)) return null
        mutablePending.value = null
        current
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        val Process: RaftExternalShareLaunchInbox = RaftExternalShareLaunchInbox()
    }
}

/**
 * Platform entry point for handing an admitted external share token directly to a live app shell
 * without Activity recreation.
 */
object RaftExternalShareLaunchIngress {
    fun submit(token: String, targetShellInstanceId: String? = null): Boolean {
        val normalized = token.externalShareOpaqueTokenOrNull() ?: return false
        RaftExternalShareLaunchInbox.Process.submit(
            request = ExternalShareLaunchRequest(normalized),
            targetShellInstanceId = targetShellInstanceId,
        )
        return true
    }

    internal fun clearForTests() {
        RaftExternalShareLaunchInbox.Process.clear()
    }
}
