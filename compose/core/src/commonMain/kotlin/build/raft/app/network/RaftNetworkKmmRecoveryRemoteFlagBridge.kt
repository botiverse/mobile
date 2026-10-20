package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

@RaftCoreInternalApi
object RaftNetworkKmmRecoveryRemoteFlagBridge {
    private data class Listener(
        val onEvaluation: (String, Long, String, Boolean, String?) -> String?,
        val onCurrentEnvironmentChanged: (String) -> String?,
        val onActiveSelectionChanged: (String, String?) -> String?,
    )

    @Volatile
    private var listener: Listener? = null

    fun install(
        onEvaluation: (
            environmentId: String,
            evaluationGeneration: Long,
            serverId: String,
            enabled: Boolean,
            reason: String?
        ) -> String?,
        onCurrentEnvironmentChanged: (environmentId: String) -> String?,
        onActiveSelectionChanged: (scopeId: String, serverId: String?) -> String?,
    ) {
        listener = Listener(onEvaluation, onCurrentEnvironmentChanged, onActiveSelectionChanged)
    }

    fun apply(
        environmentId: String,
        evaluationGeneration: Long,
        serverId: String,
        enabled: Boolean,
        reason: String?
    ): String? =
        listener?.onEvaluation?.invoke(
            environmentId,
            evaluationGeneration,
            serverId,
            enabled,
            reason
        )

    fun updateCurrentEnvironment(environmentId: String): String? =
        listener?.onCurrentEnvironmentChanged?.invoke(environmentId)

    fun updateActiveSelection(scopeId: String, serverId: String?): String? =
        listener?.onActiveSelectionChanged?.invoke(scopeId, serverId)

    @RaftCoreInternalApi
    fun clearForTests() {
        listener = null
    }
}

fun raftNetworkKmmRecoveryEnvironmentId(
    environmentKey: String,
    apiBaseUrl: String
): String {
    val normalizedKey = environmentKey.trim().lowercase().ifBlank { "unknown" }
    val normalizedApiBaseUrl =
        apiBaseUrl
            .trim()
            .replace('\n', '_')
            .replace('\r', '_')
            .trimEnd('/')
            .ifBlank { "unknown" }
    return "$normalizedKey|$normalizedApiBaseUrl"
}

fun updateRaftNetworkKmmRecoveryEnvironment(
    environmentKey: String,
    apiBaseUrl: String
): String? =
    RaftNetworkKmmRecoveryRemoteFlagBridge.updateCurrentEnvironment(
        raftNetworkKmmRecoveryEnvironmentId(environmentKey, apiBaseUrl)
    )
