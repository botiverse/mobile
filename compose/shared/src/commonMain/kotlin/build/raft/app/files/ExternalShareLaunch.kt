package build.raft.app

import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareServerOption
import build.raft.app.data.externalShareOpaqueTokenOrNull
import build.raft.app.compat.json.JSONObject

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
                    slug = server.slug?.trim()?.takeIf { it.isNotBlank() }
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
