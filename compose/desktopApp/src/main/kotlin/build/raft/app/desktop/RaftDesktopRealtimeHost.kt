package build.raft.app.desktop

import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.raftDesktopAppScopeKeyValueStore
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RaftRealtimeAppScopePolicy
import build.raft.app.data.realtime.RaftRealtimeAppScopeRuntime
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeRuntimeCursorIdentity
import build.raft.app.data.realtime.bootstrapRealtime
import build.raft.app.data.realtime.realtimeRuntimeCursorAdvanceDecision
import build.raft.app.data.updateRaftHostVisibility
import build.raft.app.desktop.realtime.RaftDesktopRealtimeHostContext
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `reportRealTime`: the desktop realtime owner (port of the Android host's
 * AndroidAppScopeRealtimeOwner, reduced to what a single always-visible window needs). The
 * shared shell decides which server to keep connected ("reconcile") and owns the cursor; this
 * owns one Socket.IO runtime for the current server.
 *
 * TODO(desktop): no network-change reconnects or heartbeat health checks beyond the client's
 * own reconnect, and no hidden-window (background) policy.
 */
object RaftDesktopRealtimeHost {
    private const val TAG = "SlockKuiklyRealtime"
    private const val PROCESS_OWNER_ID = "desktop-process"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Session(
        val serverId: ServerId,
        val binding: Binding,
        val context: RaftDesktopRealtimeHostContext,
        val runtime: RaftRealtimeAppScopeRuntime,
    ) {
        var witness: RealtimeConnectionWitnessToken? = null
        var connectJob: Job? = null
    }

    /** The env / account binding; a token rotation on the same binding only re-auths. */
    private data class Binding(val socketUrl: String, val environmentId: String, val principalId: String, val connectionFamilyId: String)

    private var session: Session? = null

    fun install() {
        // The desktop window is the only host surface and is visible while the app runs.
        updateRaftHostVisibility(PROCESS_OWNER_ID, visible = true)
    }

    @Synchronized
    fun report(params: JsonObject) {
        val action = params.str("action")
        val witness =
            RealtimeConnectionWitnessToken(
                scopeLeaseId = params.str("authorityScopeLeaseId"),
                principalId = params.str("authorityPrincipalId"),
                authGeneration = params.long("authorityAuthGeneration"),
                connectionFamilyId = params.str("authorityConnectionFamilyId"),
            )
        when (action) {
            "reconcile" -> reconcile(params, witness)
            "connect" ->
                connect(
                    serverId = ServerId(params.str("serverId")),
                    accessToken = params.str("accessToken"),
                    socketUrl = params.str("socketUrl"),
                    environmentId = "",
                    requestedLastSeq = params.long("lastSeq"),
                    forceReconnect = params.bool("forceReconnect"),
                    witness = witness,
                )
            "cursorAdvance" -> cursorAdvance(ServerId(params.str("serverId")), params.long("appliedSeq"), witness)
            "disconnect" -> disconnect(params.str("reason").ifBlank { "shared_disconnect" }, params.str("serverId"))
            "authRefreshFinished" -> RaftDesktopLog.d(TAG, "auth_refresh_finished success=${params.bool("success")}")
            else -> RaftDesktopLog.w(TAG, "report_ignored action=$action")
        }
    }

    private fun reconcile(params: JsonObject, witness: RealtimeConnectionWitnessToken) {
        val serverIds =
            (params["serverIds"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .toSet()
        val reason = params.str("disconnectReason").ifBlank { GeneratedRealtimeContract.DisconnectReason.AuthInvalid }
        if (serverIds.size != 1) {
            // None (signed out / no server) or several (not a single current server): no socket.
            closeSession(reason)
            RaftDesktopLog.i(TAG, "reconcile result=cleared serverCount=${serverIds.size} reason=$reason")
            return
        }
        val accessToken = params.str("accessToken")
        if (accessToken.isBlank() || witness.scopeLeaseId.isBlank() || witness.principalId.isBlank() || witness.authGeneration <= 0L) {
            closeSession("invalid_process_authority")
            RaftDesktopLog.w(TAG, "reconcile_skip reason=invalid_authority")
            return
        }
        val serverId = ServerId(serverIds.single())
        connect(
            serverId = serverId,
            accessToken = accessToken,
            socketUrl = params.str("socketUrl"),
            environmentId = params.str("environmentId"),
            requestedLastSeq = session?.takeIf { it.serverId == serverId }?.runtime?.lastSeq ?: 0L,
            forceReconnect = params.bool("forceReconnect"),
            witness = witness,
        )
    }

    private fun connect(
        serverId: ServerId,
        accessToken: String,
        socketUrl: String,
        environmentId: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        witness: RealtimeConnectionWitnessToken,
    ) {
        if (serverId.value.isBlank() || accessToken.isBlank()) return
        val environment = SharedApiEnvironmentStore(raftDesktopAppScopeKeyValueStore(SharedApiEnvironmentStore.Namespace))
        val resolvedSocketUrl = environment.resolveSocketUrl(socketUrl)
        val binding =
            Binding(resolvedSocketUrl, environmentId.trim().trimEnd('/'), witness.principalId, witness.connectionFamilyId)
        val current = session
        if (current != null && (current.serverId != serverId || current.binding != binding)) closeSession("process_identity_replaced")
        val target =
            session ?: run {
                val context =
                    RaftDesktopRealtimeHostContext.create(
                        scope = scope,
                        apiBaseUrl = environment.current().apiBaseUrl,
                        socketUrl = resolvedSocketUrl,
                        pinnedServerId = serverId,
                    )
                val runtime = bootstrapRealtime(context).also { it.onAppShown() }
                Session(serverId, binding, context, runtime).also { session = it }
            }
        if (!forceReconnect && target.witness == witness && target.connectJob?.isActive == true) return
        target.witness = witness
        target.connectJob?.cancel()
        target.connectJob =
            scope.launch {
                val result =
                    target.runtime.connect(
                        serverId = serverId,
                        accessToken = accessToken,
                        requestedLastSeq = requestedLastSeq,
                        forceReconnect = forceReconnect,
                        witness = witness,
                    )
                RaftDesktopLog.i(TAG, "connect_result serverId=${serverId.value} result=${result::class.simpleName}")
            }
    }

    private fun cursorAdvance(serverId: ServerId, appliedSeq: Long, witness: RealtimeConnectionWitnessToken) {
        val current = session?.takeIf { it.serverId == serverId } ?: return
        val currentWitness = current.witness ?: return
        if (appliedSeq <= 0L) return
        val decision =
            realtimeRuntimeCursorAdvanceDecision(
                currentIdentity = currentWitness.cursorIdentity(serverId),
                requestIdentity = witness.cursorIdentity(serverId),
                currentSeq = current.runtime.lastSeq,
                appliedSeq = appliedSeq,
            )
        if (decision.shouldAdvance) current.runtime.advanceAppliedSeqForHost(decision.resumeSeq)
    }

    private fun disconnect(reason: String, serverId: String) {
        if (!RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason(reason)) {
            RaftDesktopLog.w(TAG, "disconnect_ignored reason=$reason")
            return
        }
        if (serverId.isBlank() || session?.serverId?.value == serverId) closeSession(reason)
    }

    private fun closeSession(reason: String) {
        val current = session ?: return
        session = null
        current.connectJob?.cancel()
        current.runtime.disconnectForSessionEnd(GeneratedRealtimeContract.DisconnectReason.ServerRemoved)
        current.runtime.dispose()
        current.context.close()
        RaftDesktopLog.i(TAG, "session_closed serverId=${current.serverId.value} reason=$reason")
    }

    private fun RealtimeConnectionWitnessToken.cursorIdentity(serverId: ServerId) =
        RealtimeRuntimeCursorIdentity(
            serverId = serverId.value,
            scopeLeaseId = scopeLeaseId,
            principalId = principalId,
            authGeneration = authGeneration,
            connectionFamilyId = connectionFamilyId,
        )

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
