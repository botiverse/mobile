package build.raft.app.network.sync

import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY
import build.raft.app.data.SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
import build.raft.app.data.MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS: Long = 15L * 60L * 1000L

enum class DiagnosticFeatureFlagOverrideMode {
    Server,
    ForceOff,
    ForceOn,
}

enum class DiagnosticFeatureFlagScopeKind {
    ChannelExact,
    ServerExact,
}

data class DiagnosticFeatureFlagScope(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val channelId: String? = null,
) {
    fun isValidFor(kind: DiagnosticFeatureFlagScopeKind): Boolean =
        environmentId.isNotBlank() &&
            accountId.isNotBlank() &&
            serverId.isNotBlank() &&
            (kind == DiagnosticFeatureFlagScopeKind.ServerExact || !channelId.isNullOrBlank())

}

data class DiagnosticFeatureFlagDecision(
    val serverEffective: Boolean,
    val clientOverride: DiagnosticFeatureFlagOverrideMode,
    val finalEffective: Boolean,
    val precedence: String,
    val exactScopeMatched: Boolean,
)

data class DiagnosticFeatureFlagEvidence(
    val selectedScope: DiagnosticFeatureFlagScope? = null,
    val witnessGeneration: Long? = null,
    val durableReadStateVersion: Long? = null,
    val durableMaxReadSeq: Long? = null,
    val latestAdjudication: String? = null,
    val destinationPublication: String? = null,
    val barrierState: String? = null,
    val stalePublicationRejectedCount: Long = 0L,
)

data class DiagnosticFeatureFlagOverrideSnapshot(
    val key: String,
    val supported: Boolean,
    val scopeKind: DiagnosticFeatureFlagScopeKind?,
    val entitled: Boolean,
    val mode: DiagnosticFeatureFlagOverrideMode,
    val observedScope: DiagnosticFeatureFlagScope?,
    val boundScope: DiagnosticFeatureFlagScope?,
    val expiresAtMillis: Long?,
    val lastDecision: DiagnosticFeatureFlagDecision?,
    val clearReason: String?,
    val evidence: DiagnosticFeatureFlagEvidence = DiagnosticFeatureFlagEvidence(),
)

sealed interface DiagnosticFeatureFlagOverrideMutationResult {
    data class Applied(val snapshot: DiagnosticFeatureFlagOverrideSnapshot) :
        DiagnosticFeatureFlagOverrideMutationResult

    data class Rejected(val code: String, val snapshot: DiagnosticFeatureFlagOverrideSnapshot) :
        DiagnosticFeatureFlagOverrideMutationResult
}

fun interface DiagnosticFeatureFlagExpiryHandle {
    fun cancel()
}

fun interface DiagnosticFeatureFlagExpiryScheduler {
    fun schedule(delayMillis: Long, onExpired: () -> Unit): DiagnosticFeatureFlagExpiryHandle
}

private interface DiagnosticFeatureFlagOverrideConsumer {
    val key: String
    val scopeKind: DiagnosticFeatureFlagScopeKind

    fun configureEntitlement(enabled: Boolean)
    fun observe(scope: DiagnosticFeatureFlagScope)
    fun setMode(mode: DiagnosticFeatureFlagOverrideMode): DiagnosticFeatureFlagOverrideMutationResult
    fun decide(serverEffective: Boolean, scope: DiagnosticFeatureFlagScope): DiagnosticFeatureFlagDecision
    fun snapshot(): DiagnosticFeatureFlagOverrideSnapshot
    fun clear(reason: String)
    fun clearObservation()
}

private class ServerExactDiagnosticFeatureFlagConsumer(
    override val key: String,
    private val nowMillis: () -> Long,
    private val expiryScheduler: DiagnosticFeatureFlagExpiryScheduler,
    private val onChanged: () -> Unit,
) : DiagnosticFeatureFlagOverrideConsumer {
    override val scopeKind: DiagnosticFeatureFlagScopeKind = DiagnosticFeatureFlagScopeKind.ServerExact

    private data class ActiveOverride(
        val token: Long,
        val mode: DiagnosticFeatureFlagOverrideMode,
        val expiresAtMillis: Long,
    )

    private val lock = SynchronizedObject()
    private var entitled = false
    private var observedScope: DiagnosticFeatureFlagScope? = null
    private var activeOverride: ActiveOverride? = null
    private var nextOverrideToken = 0L
    private var expiryHandle: DiagnosticFeatureFlagExpiryHandle? = null
    private var lastDecision: DiagnosticFeatureFlagDecision? = null
    private var clearReason: String? = "process_start"

    override fun configureEntitlement(enabled: Boolean) {
        val changed = synchronized(lock) {
            val wasEntitled = entitled
            entitled = enabled
            if (!enabled) clearLocked("entitlement_disabled")
            wasEntitled != enabled
        }
        if (changed) onChanged()
    }

    override fun observe(scope: DiagnosticFeatureFlagScope) {
        if (!scope.isValidFor(scopeKind)) return
        val changed = synchronized(lock) {
            expireLocked()
            val normalized = scope.copy(channelId = null)
            val changed = observedScope != normalized
            observedScope = normalized
            changed
        }
        if (changed) onChanged()
    }

    override fun setMode(mode: DiagnosticFeatureFlagOverrideMode): DiagnosticFeatureFlagOverrideMutationResult {
        val result = synchronized(lock) {
            expireLocked()
            when {
                !entitled -> rejected("diagnostic_feature_flag_override_not_entitled")
                mode == DiagnosticFeatureFlagOverrideMode.Server -> {
                    clearLocked("server_mode_selected")
                    DiagnosticFeatureFlagOverrideMutationResult.Applied(snapshotLocked())
                }
                else -> {
                    expiryHandle?.cancel()
                    expiryHandle = null
                    nextOverrideToken += 1L
                    activeOverride =
                        ActiveOverride(
                            token = nextOverrideToken,
                            mode = mode,
                            expiresAtMillis = nowMillis() + DIAGNOSTIC_FEATURE_FLAG_OVERRIDE_TTL_MILLIS,
                        )
                    lastDecision = lastDecision?.withOverride(mode)
                    clearReason = null
                    DiagnosticFeatureFlagOverrideMutationResult.Applied(snapshotLocked())
                }
            }
        }
        synchronized(lock) { activeOverride }
            ?.let { active -> scheduleExpiry(active.token, active.expiresAtMillis) }
        if (result is DiagnosticFeatureFlagOverrideMutationResult.Applied) onChanged()
        return result
    }

    override fun decide(
        serverEffective: Boolean,
        scope: DiagnosticFeatureFlagScope,
    ): DiagnosticFeatureFlagDecision {
        val (decision, changed) = synchronized(lock) {
            val expired = expireLocked()
            val normalized = scope.copy(channelId = null)
            if (normalized.isValidFor(scopeKind)) {
                observedScope = normalized
            }
            val active = activeOverride.takeIf { entitled }
            val decision =
                when {
                    active?.mode == DiagnosticFeatureFlagOverrideMode.ForceOff ->
                        DiagnosticFeatureFlagDecision(
                            serverEffective = serverEffective,
                            clientOverride = active.mode,
                            finalEffective = false,
                            precedence = "diagnostic_override_force_off",
                            exactScopeMatched = false,
                        )
                    active?.mode == DiagnosticFeatureFlagOverrideMode.ForceOn ->
                        DiagnosticFeatureFlagDecision(
                            serverEffective = serverEffective,
                            clientOverride = active.mode,
                            finalEffective = true,
                            precedence = "diagnostic_override_force_on",
                            exactScopeMatched = false,
                        )
                    else ->
                        DiagnosticFeatureFlagDecision(
                            serverEffective = serverEffective,
                            clientOverride = active?.mode ?: DiagnosticFeatureFlagOverrideMode.Server,
                            finalEffective = serverEffective,
                            precedence = "server",
                            exactScopeMatched = false,
                        )
                }
            val changed = expired || lastDecision != decision || observedScope != normalized
            lastDecision = decision
            decision to changed
        }
        if (changed) onChanged()
        return decision
    }

    override fun snapshot(): DiagnosticFeatureFlagOverrideSnapshot {
        val expired = synchronized(lock) { expireLocked() }
        if (expired) onChanged()
        return synchronized(lock) { snapshotLocked() }
    }

    override fun clear(reason: String) {
        val changed = synchronized(lock) {
            val hadState = activeOverride != null || lastDecision != null || clearReason != reason
            clearLocked(reason)
            hadState
        }
        if (changed) onChanged()
    }

    override fun clearObservation() {
        val changed = synchronized(lock) {
            val changed = observedScope != null || lastDecision != null
            observedScope = null
            lastDecision = null
            changed
        }
        if (changed) onChanged()
    }

    private fun expireLocked(): Boolean {
        val active = activeOverride ?: return false
        if (nowMillis() < active.expiresAtMillis) return false
        clearLocked("expired")
        return true
    }

    private fun clearLocked(reason: String) {
        expiryHandle?.cancel()
        expiryHandle = null
        activeOverride = null
        lastDecision = null
        clearReason = reason
        if (reason in setOf("identity_changed", "authenticated_identity_changed", "logout", "entitlement_disabled")) {
            observedScope = null
        }
    }

    private fun rejected(code: String): DiagnosticFeatureFlagOverrideMutationResult.Rejected =
        DiagnosticFeatureFlagOverrideMutationResult.Rejected(code, snapshotLocked())

    private fun scheduleExpiry(
        token: Long,
        expiresAtMillis: Long,
    ) {
        val handle =
            expiryScheduler.schedule((expiresAtMillis - nowMillis()).coerceAtLeast(0L)) {
                val outcome = synchronized(lock) {
                    val active = activeOverride
                    when {
                        active?.token != token || active.expiresAtMillis != expiresAtMillis -> ExpiryOutcome.Stale
                        nowMillis() < expiresAtMillis -> ExpiryOutcome.Reschedule
                        else -> {
                            clearLocked("expired")
                            ExpiryOutcome.Expired
                        }
                    }
                }
                when (outcome) {
                    ExpiryOutcome.Expired -> onChanged()
                    ExpiryOutcome.Reschedule -> scheduleExpiry(token, expiresAtMillis)
                    ExpiryOutcome.Stale -> Unit
                }
            }
        synchronized(lock) {
            if (activeOverride?.token == token && activeOverride?.expiresAtMillis == expiresAtMillis) {
                expiryHandle?.cancel()
                expiryHandle = handle
            } else {
                handle.cancel()
            }
        }
    }

    private fun snapshotLocked(): DiagnosticFeatureFlagOverrideSnapshot =
        DiagnosticFeatureFlagOverrideSnapshot(
            key = key,
            supported = true,
            scopeKind = scopeKind,
            entitled = entitled,
            mode = activeOverride?.mode ?: DiagnosticFeatureFlagOverrideMode.Server,
            observedScope = observedScope,
            boundScope = null,
            expiresAtMillis = activeOverride?.expiresAtMillis,
            lastDecision = lastDecision,
            clearReason = clearReason,
        )

    private enum class ExpiryOutcome {
        Expired,
        Reschedule,
        Stale,
    }
}

private fun DiagnosticFeatureFlagDecision.withOverride(
    mode: DiagnosticFeatureFlagOverrideMode,
): DiagnosticFeatureFlagDecision =
    when (mode) {
        DiagnosticFeatureFlagOverrideMode.Server ->
            copy(
                clientOverride = mode,
                finalEffective = serverEffective,
                precedence = "server",
                exactScopeMatched = false,
            )
        DiagnosticFeatureFlagOverrideMode.ForceOff ->
            copy(
                clientOverride = mode,
                finalEffective = false,
                precedence = "diagnostic_override_force_off",
                exactScopeMatched = false,
            )
        DiagnosticFeatureFlagOverrideMode.ForceOn ->
            copy(
                clientOverride = mode,
                finalEffective = true,
                precedence = "diagnostic_override_force_on",
                exactScopeMatched = false,
            )
    }

private class ReadStateV1DiagnosticFeatureFlagConsumer(
    private val controller: ReadStateV1DiagnosticOverrideController,
) : DiagnosticFeatureFlagOverrideConsumer {
    override val key: String = SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY
    override val scopeKind: DiagnosticFeatureFlagScopeKind = DiagnosticFeatureFlagScopeKind.ChannelExact

    override fun configureEntitlement(enabled: Boolean) = controller.configureEntitlement(enabled)

    override fun observe(scope: DiagnosticFeatureFlagScope) {
        if (!scope.isValidFor(scopeKind)) return
        controller.observe(scope.toReadStateOwner(), requireNotNull(scope.channelId))
    }

    override fun setMode(mode: DiagnosticFeatureFlagOverrideMode): DiagnosticFeatureFlagOverrideMutationResult =
        when (val result = controller.setMode(mode.toReadStateMode())) {
            is ReadStateV1DiagnosticOverrideMutationResult.Applied ->
                DiagnosticFeatureFlagOverrideMutationResult.Applied(result.snapshot.toGenericSnapshot())
            is ReadStateV1DiagnosticOverrideMutationResult.Rejected ->
                DiagnosticFeatureFlagOverrideMutationResult.Rejected(result.code, result.snapshot.toGenericSnapshot())
        }

    override fun decide(
        serverEffective: Boolean,
        scope: DiagnosticFeatureFlagScope,
    ): DiagnosticFeatureFlagDecision {
        val decision =
            controller.decide(
                serverEffective = serverEffective,
                owner = scope.toReadStateOwner(),
                channelId = scope.channelId.orEmpty(),
            )
        return DiagnosticFeatureFlagDecision(
            serverEffective = decision.serverEffective,
            clientOverride = decision.clientOverride.toGenericMode(),
            finalEffective = decision.finalEffective,
            precedence = decision.precedence,
            exactScopeMatched = decision.exactScopeMatched,
        )
    }

    override fun snapshot(): DiagnosticFeatureFlagOverrideSnapshot = controller.snapshot().toGenericSnapshot()

    override fun clear(reason: String) = controller.clear(reason)

    override fun clearObservation() = controller.clearObservation()
}

class DiagnosticFeatureFlagOverrideRegistry(
    nowMillis: () -> Long,
    readStateController: ReadStateV1DiagnosticOverrideController,
    expiryScheduler: DiagnosticFeatureFlagExpiryScheduler = NoOpDiagnosticFeatureFlagExpiryScheduler,
) {
    private val registryLock = SynchronizedObject()
    private val keyRevisions = mutableMapOf<String, MutableStateFlow<Long>>()
    private val unsupportedKeys = mutableSetOf<String>()

    private val consumers: Map<String, DiagnosticFeatureFlagOverrideConsumer> =
        listOf(
            ReadStateV1DiagnosticFeatureFlagConsumer(readStateController),
            ServerExactDiagnosticFeatureFlagConsumer(
                key = MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
                nowMillis = nowMillis,
                expiryScheduler = expiryScheduler,
                onChanged = { bumpRevision(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY) },
            ),
            ServerExactDiagnosticFeatureFlagConsumer(
                key = THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY,
                nowMillis = nowMillis,
                expiryScheduler = expiryScheduler,
                onChanged = { bumpRevision(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY) },
            ),
            ServerExactDiagnosticFeatureFlagConsumer(
                key = MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
                nowMillis = nowMillis,
                expiryScheduler = expiryScheduler,
                onChanged = { bumpRevision(MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY) },
            ),
            // Task #153: device checks of the DoH fallback on an internal build without a server rule.
            // Consumed through SharedServerFeatureFlags' diagnostic override seam (see RaftSharedStoreProvider).
            ServerExactDiagnosticFeatureFlagConsumer(
                key = MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY,
                nowMillis = nowMillis,
                expiryScheduler = expiryScheduler,
                onChanged = { bumpRevision(MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY) },
            ),
            // Task #154: device checks of curl Socket.IO realtime on an internal build without a server
            // rule. Read at the next connect or reconnect through the same override seam.
            ServerExactDiagnosticFeatureFlagConsumer(
                key = MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY,
                nowMillis = nowMillis,
                expiryScheduler = expiryScheduler,
                onChanged = { bumpRevision(MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY) },
            ),
        ).associateBy { it.key }

    val supportedKeys: List<String> = consumers.keys.sorted()

    fun configureEntitlement(enabled: Boolean) {
        consumers.values.forEach { it.configureEntitlement(enabled) }
    }

    fun revisionFor(key: String): StateFlow<Long> =
        synchronized(registryLock) {
            keyRevisions.getOrPut(key) { MutableStateFlow(0L) }.asStateFlow()
        }

    fun observe(key: String, scope: DiagnosticFeatureFlagScope) {
        consumers[key]?.observe(scope)
    }

    fun decide(
        key: String,
        serverEffective: Boolean,
        scope: DiagnosticFeatureFlagScope,
    ): DiagnosticFeatureFlagDecision {
        val consumer = consumers[key]
        if (consumer != null) return consumer.decide(serverEffective, scope)
        rememberUnsupportedKey(key)
        return DiagnosticFeatureFlagDecision(
                serverEffective = serverEffective,
                clientOverride = DiagnosticFeatureFlagOverrideMode.Server,
                finalEffective = serverEffective,
                precedence = "unsupported_no_effect",
                exactScopeMatched = false,
            )
    }

    fun setMode(
        key: String,
        mode: DiagnosticFeatureFlagOverrideMode,
    ): DiagnosticFeatureFlagOverrideMutationResult {
        if (!key.matches(ValidKeyRegex)) {
            return DiagnosticFeatureFlagOverrideMutationResult.Rejected(
                code = "diagnostic_feature_flag_key_invalid",
                snapshot = unsupportedSnapshot(key),
            )
        }
        val consumer = consumers[key]
            ?: run {
                rememberUnsupportedKey(key)
                return DiagnosticFeatureFlagOverrideMutationResult.Rejected(
                    code = "diagnostic_feature_flag_consumer_unsupported",
                    snapshot = unsupportedSnapshot(key),
                )
            }
        val result = consumer.setMode(mode)
        if (result is DiagnosticFeatureFlagOverrideMutationResult.Applied) bumpRevision(key)
        return result
    }

    fun snapshot(key: String): DiagnosticFeatureFlagOverrideSnapshot {
        val consumer = consumers[key]
        if (consumer != null) return consumer.snapshot()
        rememberUnsupportedKey(key)
        return unsupportedSnapshot(key)
    }

    fun snapshots(): List<DiagnosticFeatureFlagOverrideSnapshot> {
        val unknown = synchronized(registryLock) { unsupportedKeys.toList().sorted() }
        return supportedKeys.map { consumers.getValue(it).snapshot() } + unknown.map(::unsupportedSnapshot)
    }

    fun clearAll(reason: String) {
        consumers.values.forEach { it.clear(reason) }
        supportedKeys.forEach(::bumpRevision)
    }

    fun clearObservations() {
        consumers.values.forEach { it.clearObservation() }
        supportedKeys.forEach(::bumpRevision)
    }

    private fun unsupportedSnapshot(key: String): DiagnosticFeatureFlagOverrideSnapshot =
        DiagnosticFeatureFlagOverrideSnapshot(
            key = key,
            supported = false,
            scopeKind = null,
            entitled = false,
            mode = DiagnosticFeatureFlagOverrideMode.Server,
            observedScope = null,
            boundScope = null,
            expiresAtMillis = null,
            lastDecision = null,
            clearReason = "unsupported_no_effect",
        )

    private fun rememberUnsupportedKey(key: String) {
        if (!key.matches(ValidKeyRegex)) return
        val changed = synchronized(registryLock) { unsupportedKeys.add(key) }
        if (changed) bumpRevision(key)
    }

    private fun bumpRevision(key: String) {
        synchronized(registryLock) {
            val flow = keyRevisions.getOrPut(key) { MutableStateFlow(0L) }
            flow.value = flow.value + 1L
        }
    }

    private companion object {
        val ValidKeyRegex = Regex("^[a-z][a-z0-9_]{2,95}$")
    }
}

object ProcessDiagnosticFeatureFlagOverrides {
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registry: DiagnosticFeatureFlagOverrideRegistry =
        DiagnosticFeatureFlagOverrideRegistry(
            nowMillis = ::defaultNowMillis,
            readStateController = ProcessReadStateV1DiagnosticOverride.controller,
            expiryScheduler =
                DiagnosticFeatureFlagExpiryScheduler { delayMillis, onExpired ->
                    val job: Job = expiryScope.launch {
                        delay(delayMillis)
                        onExpired()
                    }
                    DiagnosticFeatureFlagExpiryHandle { job.cancel() }
                },
        )
}

private object NoOpDiagnosticFeatureFlagExpiryScheduler : DiagnosticFeatureFlagExpiryScheduler {
    override fun schedule(
        delayMillis: Long,
        onExpired: () -> Unit,
    ): DiagnosticFeatureFlagExpiryHandle = DiagnosticFeatureFlagExpiryHandle {}
}

private fun DiagnosticFeatureFlagScope.toReadStateOwner(): ReadStateV1ProductionOwner =
    ReadStateV1ProductionOwner(environmentId, accountId, serverId)

private fun DiagnosticFeatureFlagOverrideMode.toReadStateMode(): ReadStateV1DiagnosticOverrideMode =
    when (this) {
        DiagnosticFeatureFlagOverrideMode.Server -> ReadStateV1DiagnosticOverrideMode.Server
        DiagnosticFeatureFlagOverrideMode.ForceOff -> ReadStateV1DiagnosticOverrideMode.ForceOff
        DiagnosticFeatureFlagOverrideMode.ForceOn -> ReadStateV1DiagnosticOverrideMode.ForceOn
    }

private fun ReadStateV1DiagnosticOverrideMode.toGenericMode(): DiagnosticFeatureFlagOverrideMode =
    when (this) {
        ReadStateV1DiagnosticOverrideMode.Server -> DiagnosticFeatureFlagOverrideMode.Server
        ReadStateV1DiagnosticOverrideMode.ForceOff -> DiagnosticFeatureFlagOverrideMode.ForceOff
        ReadStateV1DiagnosticOverrideMode.ForceOn -> DiagnosticFeatureFlagOverrideMode.ForceOn
    }

private fun ReadStateV1DiagnosticExactScope.toGenericScope(): DiagnosticFeatureFlagScope =
    DiagnosticFeatureFlagScope(environmentId, accountId, serverId, channelId)

private fun ReadStateV1DiagnosticOverrideSnapshot.toGenericSnapshot(): DiagnosticFeatureFlagOverrideSnapshot =
    DiagnosticFeatureFlagOverrideSnapshot(
        key = SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
        supported = true,
        scopeKind = DiagnosticFeatureFlagScopeKind.ChannelExact,
        entitled = entitled,
        mode = mode.toGenericMode(),
        observedScope = observedScope?.toGenericScope(),
        boundScope = boundScope?.toGenericScope(),
        expiresAtMillis = expiresAtMillis,
        lastDecision =
            lastDecision?.let { decision ->
                DiagnosticFeatureFlagDecision(
                    serverEffective = decision.serverEffective,
                    clientOverride = decision.clientOverride.toGenericMode(),
                    finalEffective = decision.finalEffective,
                    precedence = decision.precedence,
                    exactScopeMatched = decision.exactScopeMatched,
                )
            },
        clearReason = clearReason,
        evidence =
            DiagnosticFeatureFlagEvidence(
                selectedScope = selectedScope?.toGenericScope(),
                witnessGeneration = witnessGeneration,
                durableReadStateVersion = durableReadStateVersion,
                durableMaxReadSeq = durableMaxReadSeq,
                latestAdjudication = latestAdjudication,
                destinationPublication = destinationPublication,
                barrierState = barrierState,
                stalePublicationRejectedCount = stalePublicationRejectedCount,
            ),
    )
