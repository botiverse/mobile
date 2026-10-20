package build.raft.app.network

import build.raft.app.data.RaftKeyValueStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import com.tencent.kmm.network.export.VBTransportAndroidEngine
import com.tencent.kmm.network.export.VBTransportReusedHttp2Recovery

internal const val RAFT_NETWORK_KMM_CLIENT_SHARD_COUNT = 5
internal const val RAFT_NETWORK_KMM_RESPONSE_HEADERS_WATCHDOG_MILLIS = 7_000L
internal const val RAFT_NETWORK_KMM_MINIMUM_CONCURRENT_STALLED_REQUESTS = 2
internal const val RAFT_NETWORK_KMM_PING_INTERVAL_MILLIS = 3_000L
private const val RAFT_NETWORK_KMM_DISABLED_ENVIRONMENTS_KEY = "disabledEnvironmentIds.v1"

/**
 * Installs Raft's process-wide Android transport policy before the first app request.
 *
 * NetworkKMM keeps reused-h2 recovery disabled by default. Raft opts every Android
 * build variant into the reviewed five-shard policy so a stale connection can be
 * retired without replaying writes or blocking unrelated healthy shards.
 */
fun installAndroidNetworkKmmReusedHttp2Recovery(
    store: RaftKeyValueStore,
    environmentId: String
): String = AndroidNetworkKmmRecoveryController.install(store, environmentId)

fun installAndroidNetworkKmmReusedHttp2RecoveryAtStartup(
    store: RaftKeyValueStore,
    currentEnvironment: () -> SharedSettingsApiEnvironmentOption
): String {
    val environmentRead = runCatching(currentEnvironment)
    val environment = environmentRead.getOrElse { SharedApiEnvironmentStore.Production }
    val diagnostics =
        installAndroidNetworkKmmReusedHttp2Recovery(
            store = store,
            environmentId =
                raftNetworkKmmRecoveryEnvironmentId(
                    environmentKey = environment.key,
                    apiBaseUrl = environment.apiBaseUrl
                )
        )
    return buildString {
        append(diagnostics)
        append(" apiEnvironmentRead=")
        append(if (environmentRead.isSuccess) "success" else "failed")
        if (environmentRead.isFailure) {
            append(" apiEnvironmentFallback=production")
            append(" apiEnvironmentReadErrorType=")
            append(environmentRead.exceptionOrNull()?.let { it::class.simpleName } ?: "unknown")
        }
    }
}

internal fun raftAndroidNetworkKmmRecoveryPolicy(
    enabled: Boolean = true
): VBTransportReusedHttp2Recovery =
    VBTransportReusedHttp2Recovery(
        enabled = enabled,
        clientShardCount = RAFT_NETWORK_KMM_CLIENT_SHARD_COUNT,
        responseHeadersWatchdogMillis = RAFT_NETWORK_KMM_RESPONSE_HEADERS_WATCHDOG_MILLIS,
        minimumConcurrentStalledRequests = RAFT_NETWORK_KMM_MINIMUM_CONCURRENT_STALLED_REQUESTS,
        pingIntervalMillis = RAFT_NETWORK_KMM_PING_INTERVAL_MILLIS
    )

private object AndroidNetworkKmmRecoveryController {
    private var store: RaftKeyValueStore? = null
    private var currentEnvironmentId: String = "unknown|unknown"
    private var disabledEnvironmentIds: Set<String> = emptySet()
    private var activeServerByScope: Map<String, String> = emptyMap()
    private var activeSelectionTrackingStarted: Boolean = false
    private val latestAppliedEvaluationGenerationByEnvironment = mutableMapOf<String, Long>()

    fun install(store: RaftKeyValueStore, environmentId: String): String = synchronized(this) {
        this.store = store
        currentEnvironmentId = environmentId.toNormalizedEnvironmentId()
        activeServerByScope = emptyMap()
        activeSelectionTrackingStarted = false
        latestAppliedEvaluationGenerationByEnvironment.clear()
        val persistedRead = runCatching { store.getString(RAFT_NETWORK_KMM_DISABLED_ENVIRONMENTS_KEY) }
        disabledEnvironmentIds = persistedRead.getOrNull().toDisabledEnvironmentIds()
        val policy = publishCurrentPolicy()
        RaftNetworkKmmRecoveryRemoteFlagBridge.install(
            onEvaluation = ::applyRemoteEvaluation,
            onCurrentEnvironmentChanged = ::updateCurrentEnvironment,
            onActiveSelectionChanged = ::updateActiveSelection
        )
        policy.raftDiagnosticsSummary(
            environmentId = currentEnvironmentId,
            persistedKillEnvironmentCount = disabledEnvironmentIds.size,
            remoteKillLatchReadSucceeded = persistedRead.isSuccess
        )
    }

    private fun applyRemoteEvaluation(
        environmentId: String,
        evaluationGeneration: Long,
        serverId: String,
        enabled: Boolean,
        reason: String?
    ): String? = synchronized(this) {
        val normalizedEnvironmentId = environmentId.toNormalizedEnvironmentId()
        val normalizedServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedEnvironmentId != currentEnvironmentId) {
            return remoteEvaluationIgnoredSummary(
                normalizedEnvironmentId,
                evaluationGeneration,
                normalizedServerId,
                enabled,
                normalizedReason,
                "environment_not_current"
            )
        }
        if (
            activeSelectionTrackingStarted &&
            normalizedServerId !in activeServerByScope.values
        ) {
            return remoteEvaluationIgnoredSummary(
                normalizedEnvironmentId,
                evaluationGeneration,
                normalizedServerId,
                enabled,
                normalizedReason,
                "server_not_active"
            )
        }
        val latestAppliedGeneration =
            latestAppliedEvaluationGenerationByEnvironment[normalizedEnvironmentId] ?: Long.MIN_VALUE
        if (evaluationGeneration <= latestAppliedGeneration) {
            return remoteEvaluationIgnoredSummary(
                normalizedEnvironmentId,
                evaluationGeneration,
                normalizedServerId,
                enabled,
                normalizedReason,
                "stale_generation"
            )
        }
        if (normalizedReason == null || normalizedReason == "missing_flag") {
            return remoteEvaluationIgnoredSummary(
                normalizedEnvironmentId,
                evaluationGeneration,
                normalizedServerId,
                enabled,
                normalizedReason,
                "${normalizedReason ?: "missing_reason"}"
            )
        }
        latestAppliedEvaluationGenerationByEnvironment[normalizedEnvironmentId] = evaluationGeneration

        // The backend's kill-switch check happens before user/server/default rules, so this is one global
        // fact per API environment. Any authoritative non-kill result proves the environment-wide kill is
        // off; it clears the single environment latch regardless of which selected server evaluated it.
        val remoteKill = normalizedReason == "kill_switch"
        disabledEnvironmentIds =
            if (remoteKill) {
                disabledEnvironmentIds + normalizedEnvironmentId
            } else {
                disabledEnvironmentIds - normalizedEnvironmentId
            }
        val persisted = persistCurrentLatches()
        val policy = publishCurrentPolicy()
        "networkkmm_reused_h2_remote_config applied=true " +
            "${normalizedEnvironmentId.toEnvironmentDiagnostics()} " +
            "evaluationGeneration=$evaluationGeneration " +
            "server=${normalizedServerId.toSafeDiagnosticsToken()} " +
            "flagEnabled=$enabled reason=${normalizedReason.toSafeDiagnosticsToken()} " +
            "remoteKill=$remoteKill persisted=$persisted recoveryEnabled=${policy.enabled} " +
            "persistedKillEnvironmentCount=${disabledEnvironmentIds.size}"
    }

    private fun updateCurrentEnvironment(environmentId: String): String? = synchronized(this) {
        val normalizedEnvironmentId = environmentId.toNormalizedEnvironmentId()
        if (normalizedEnvironmentId == currentEnvironmentId) return null
        val previousEnvironmentId = currentEnvironmentId
        currentEnvironmentId = normalizedEnvironmentId
        activeServerByScope = emptyMap()
        activeSelectionTrackingStarted = false
        val policy = publishCurrentPolicy()
        "networkkmm_reused_h2_environment_changed " +
            "previousEnvironmentHash=${previousEnvironmentId.environmentHash()} " +
            "${normalizedEnvironmentId.toEnvironmentDiagnostics()} " +
            "remoteKillLatched=${normalizedEnvironmentId in disabledEnvironmentIds} " +
            "recoveryEnabled=${policy.enabled}"
    }

    private fun updateActiveSelection(scopeId: String, serverId: String?): String? = synchronized(this) {
        val normalizedScopeId = scopeId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val previousServerId = activeServerByScope[normalizedScopeId]
        if (normalizedServerId == null) {
            if (previousServerId == null) return null
            activeServerByScope = activeServerByScope - normalizedScopeId
        } else {
            activeSelectionTrackingStarted = true
            if (previousServerId == normalizedServerId) return null
            activeServerByScope = activeServerByScope + (normalizedScopeId to normalizedServerId)
        }
        "networkkmm_reused_h2_active_selection scope=${normalizedScopeId.toSafeDiagnosticsToken()} " +
            "previousServer=${previousServerId?.toSafeDiagnosticsToken() ?: "none"} " +
            "currentServer=${normalizedServerId?.toSafeDiagnosticsToken() ?: "none"} " +
            "activeServerCount=${activeServerByScope.values.toSet().size}"
    }

    private fun remoteEvaluationIgnoredSummary(
        environmentId: String,
        evaluationGeneration: Long,
        serverId: String,
        enabled: Boolean,
        reason: String?,
        ignoredReason: String
    ): String =
        "networkkmm_reused_h2_remote_config applied=false " +
            "${environmentId.toEnvironmentDiagnostics()} " +
            "evaluationGeneration=$evaluationGeneration " +
            "server=${serverId.toSafeDiagnosticsToken()} flagEnabled=$enabled " +
            "reason=${reason?.toSafeDiagnosticsToken() ?: "none"} " +
            "ignoredReason=$ignoredReason recoveryEnabled=${currentRecoveryEnabled()}"

    private fun persistCurrentLatches(): Boolean {
        val currentStore = store ?: return false
        repeat(2) {
            val persisted = runCatching {
                currentStore.putString(
                    RAFT_NETWORK_KMM_DISABLED_ENVIRONMENTS_KEY,
                    disabledEnvironmentIds.toPersistedValue()
                )
                currentStore.getString(RAFT_NETWORK_KMM_DISABLED_ENVIRONMENTS_KEY)
                    .toDisabledEnvironmentIds() == disabledEnvironmentIds
            }.getOrDefault(false)
            if (persisted) return true
        }
        return false
    }

    private fun currentRecoveryEnabled(): Boolean = currentEnvironmentId !in disabledEnvironmentIds

    private fun publishCurrentPolicy(): VBTransportReusedHttp2Recovery =
        raftAndroidNetworkKmmRecoveryPolicy(enabled = currentRecoveryEnabled()).also { policy ->
            VBTransportAndroidEngine.reusedHttp2Recovery = policy
        }

    internal fun resetForTests() = synchronized(this) {
        store = null
        currentEnvironmentId = "unknown|unknown"
        disabledEnvironmentIds = emptySet()
        activeServerByScope = emptyMap()
        activeSelectionTrackingStarted = false
        latestAppliedEvaluationGenerationByEnvironment.clear()
        RaftNetworkKmmRecoveryRemoteFlagBridge.clearForTests()
    }
}

private fun String.toNormalizedEnvironmentId(): String =
    trim()
        .replace('\n', '_')
        .replace('\r', '_')
        .ifBlank { "unknown|unknown" }

private fun String?.toDisabledEnvironmentIds(): Set<String> =
    this
        ?.lineSequence()
        ?.mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.toNormalizedEnvironmentId()
        }
        ?.toSet()
        .orEmpty()

private fun Set<String>.toPersistedValue(): String? =
    takeIf { it.isNotEmpty() }
        ?.sorted()
        ?.joinToString("\n")

private fun String.environmentHash(): String = hashCode().toUInt().toString(16)

private fun String.toEnvironmentDiagnostics(): String =
    "environmentKey=${substringBefore('|').toSafeDiagnosticsToken()} " +
        "environmentHash=${environmentHash()}"

private fun VBTransportReusedHttp2Recovery.raftDiagnosticsSummary(
    environmentId: String,
    persistedKillEnvironmentCount: Int,
    remoteKillLatchReadSucceeded: Boolean
): String =
    "networkkmm_reused_h2_recovery scope=all_android_builds enabled=$enabled " +
        "clientShardCount=$clientShardCount " +
        "responseHeadersWatchdogMs=$responseHeadersWatchdogMillis " +
        "minimumConcurrentStalledRequests=$minimumConcurrentStalledRequests " +
        "pingIntervalMs=$pingIntervalMillis " +
        "${environmentId.toEnvironmentDiagnostics()} " +
        "remoteKillLatchRead=${if (remoteKillLatchReadSucceeded) "success" else "failed"} " +
        "remoteKillLatched=${!enabled} " +
        "persistedKillEnvironmentCount=$persistedKillEnvironmentCount"

internal fun resetAndroidNetworkKmmRecoveryForTests() {
    AndroidNetworkKmmRecoveryController.resetForTests()
}
