package build.raft.app.network.sync

import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal const val READ_STATE_V1_DIAGNOSTIC_OVERRIDE_TTL_MILLIS: Long = 15L * 60L * 1000L

enum class ReadStateV1DiagnosticOverrideMode {
    Server,
    ForceOff,
    ForceOn,
}

data class ReadStateV1DiagnosticExactScope(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val channelId: String,
)

data class ReadStateV1DiagnosticDecision(
    val serverEffective: Boolean,
    val clientOverride: ReadStateV1DiagnosticOverrideMode,
    val finalEffective: Boolean,
    val precedence: String,
    val exactScopeMatched: Boolean,
)

data class ReadStateV1DiagnosticOverrideSnapshot(
    val entitled: Boolean,
    val mode: ReadStateV1DiagnosticOverrideMode,
    val observedScope: ReadStateV1DiagnosticExactScope?,
    val boundScope: ReadStateV1DiagnosticExactScope?,
    val expiresAtMillis: Long?,
    val lastDecision: ReadStateV1DiagnosticDecision?,
    val clearReason: String?,
    val selectedScope: ReadStateV1DiagnosticExactScope?,
    val witnessGeneration: Long?,
    val durableReadStateVersion: Long?,
    val durableMaxReadSeq: Long?,
    val latestAdjudication: String?,
    val destinationPublication: String?,
    val barrierState: String?,
    val stalePublicationRejectedCount: Long,
)

sealed interface ReadStateV1DiagnosticOverrideMutationResult {
    data class Applied(val snapshot: ReadStateV1DiagnosticOverrideSnapshot) : ReadStateV1DiagnosticOverrideMutationResult

    data class Rejected(val code: String, val snapshot: ReadStateV1DiagnosticOverrideSnapshot) :
        ReadStateV1DiagnosticOverrideMutationResult
}

class ReadStateV1DiagnosticOverrideController(
    private val nowMillis: () -> Long,
) {
    private data class ActiveOverride(
        val mode: ReadStateV1DiagnosticOverrideMode,
        val expiresAtMillis: Long,
    )

    private val lock = SynchronizedObject()
    private var entitled: Boolean = false
    private var observedScope: ReadStateV1DiagnosticExactScope? = null
    private var activeOverride: ActiveOverride? = null
    private var lastDecision: ReadStateV1DiagnosticDecision? = null
    private var clearReason: String? = "process_start"
    private var onChanged: (() -> Unit)? = null
    private var selectedScope: ReadStateV1DiagnosticExactScope? = null
    private var witnessGeneration: Long? = null
    private var durableReadStateVersion: Long? = null
    private var durableMaxReadSeq: Long? = null
    private var latestAdjudication: String? = null
    private var destinationPublication: String? = null
    private var barrierState: String? = null
    private var stalePublicationRejectedCount: Long = 0L

    fun setOnChanged(listener: (() -> Unit)?) {
        synchronized(lock) { onChanged = listener }
    }

    fun configureEntitlement(enabled: Boolean) {
        synchronized(lock) {
            entitled = enabled
            if (!enabled) clearLocked("entitlement_disabled")
        }
    }

    fun observe(owner: ReadStateV1ProductionOwner, channelId: String) {
        val scope = owner.toDiagnosticScope(channelId) ?: return
        synchronized(lock) {
            expireLocked()
            observedScope = scope
        }
    }

    fun setMode(
        mode: ReadStateV1DiagnosticOverrideMode,
        ttlMillis: Long = READ_STATE_V1_DIAGNOSTIC_OVERRIDE_TTL_MILLIS,
    ): ReadStateV1DiagnosticOverrideMutationResult {
        val (result, listener) = synchronized(lock) {
            expireLocked()
            val result =
                when {
                    !entitled ->
                        ReadStateV1DiagnosticOverrideMutationResult.Rejected(
                            code = "read_state_diagnostic_override_not_entitled",
                            snapshot = snapshotLocked(),
                        )
                    mode == ReadStateV1DiagnosticOverrideMode.Server -> {
                        clearLocked("server_mode_selected")
                        ReadStateV1DiagnosticOverrideMutationResult.Applied(snapshotLocked())
                    }
                    else -> {
                        val normalizedTtl = ttlMillis.coerceIn(1L, READ_STATE_V1_DIAGNOSTIC_OVERRIDE_TTL_MILLIS)
                        activeOverride =
                            ActiveOverride(
                                mode = mode,
                                expiresAtMillis = nowMillis() + normalizedTtl,
                            )
                        lastDecision = lastDecision?.withOverride(mode)
                        clearReason = null
                        ReadStateV1DiagnosticOverrideMutationResult.Applied(snapshotLocked())
                    }
                }
            result to onChanged
        }
        if (result is ReadStateV1DiagnosticOverrideMutationResult.Applied) listener?.invoke()
        return result
    }

    fun clear(reason: String) {
        val listener = synchronized(lock) { clearLocked(reason); onChanged }
        listener?.invoke()
    }

    fun clearObservation() {
        val (changed, listener) = synchronized(lock) {
            val changed =
                observedScope != null || lastDecision != null || selectedScope != null || witnessGeneration != null ||
                    durableReadStateVersion != null || durableMaxReadSeq != null || latestAdjudication != null ||
                    destinationPublication != null || barrierState != null || stalePublicationRejectedCount != 0L
            observedScope = null
            lastDecision = null
            selectedScope = null
            witnessGeneration = null
            durableReadStateVersion = null
            durableMaxReadSeq = null
            latestAdjudication = null
            destinationPublication = null
            barrierState = null
            stalePublicationRejectedCount = 0L
            changed to onChanged
        }
        if (changed) listener?.invoke()
    }

    fun refreshExpired(): Boolean {
        val (expired, listener) = synchronized(lock) {
            val active = activeOverride
            if (active == null || nowMillis() < active.expiresAtMillis) {
                false to null
            } else {
                clearLocked("expired")
                true to onChanged
            }
        }
        if (expired) listener?.invoke()
        return expired
    }

    fun recordSelection(owner: ReadStateV1ProductionOwner, channelId: String, generation: Long) {
        val scope = owner.toDiagnosticScope(channelId) ?: return
        synchronized(lock) {
            selectedScope = scope
            witnessGeneration = generation
            barrierState = "active"
        }
    }

    fun recordAdjudication(source: String, result: String, fact: ReadStateV1Fact?) {
        synchronized(lock) {
            latestAdjudication = "$source:$result"
            if (fact != null && result in setOf("Committed", "Current")) {
                durableReadStateVersion = fact.readStateVersion
                durableMaxReadSeq = fact.maxReadSeq
            }
        }
    }

    fun runPublicationIfEffective(
        serverEffective: Boolean,
        owner: ReadStateV1ProductionOwner,
        channelId: String,
        publish: () -> Unit,
    ): Boolean = synchronized(lock) {
        expireLocked()
        val scope = owner.toDiagnosticScope(channelId) ?: return@synchronized false
        observedScope = scope
        val active = activeOverride.takeIf { entitled }
        val effective =
            when {
                active?.mode == ReadStateV1DiagnosticOverrideMode.ForceOff -> false
                active?.mode == ReadStateV1DiagnosticOverrideMode.ForceOn -> true
                else -> serverEffective
            }
        if (effective) publish()
        effective
    }

    fun recordPublication(homeStoreCount: Int, activityStoreCount: Int, generation: Long) {
        synchronized(lock) {
            destinationPublication =
                "home=$homeStoreCount activity=$activityStoreCount generation=$generation"
        }
    }

    fun recordBarrier(state: String) {
        synchronized(lock) { barrierState = state }
    }

    fun recordStalePublicationRejected() {
        synchronized(lock) { stalePublicationRejectedCount += 1L }
    }

    fun decide(
        serverEffective: Boolean,
        owner: ReadStateV1ProductionOwner,
        channelId: String,
    ): ReadStateV1DiagnosticDecision {
        val scope = owner.toDiagnosticScope(channelId)
        return synchronized(lock) {
            expireLocked()
            if (scope != null) {
                observedScope = scope
            }
            val active = activeOverride.takeIf { entitled }
            val decision =
                when {
                    active?.mode == ReadStateV1DiagnosticOverrideMode.ForceOff ->
                        ReadStateV1DiagnosticDecision(
                            serverEffective = serverEffective,
                            clientOverride = active.mode,
                            finalEffective = false,
                            precedence = "diagnostic_override_force_off",
                            exactScopeMatched = false,
                        )
                    active?.mode == ReadStateV1DiagnosticOverrideMode.ForceOn ->
                        ReadStateV1DiagnosticDecision(
                            serverEffective = serverEffective,
                            clientOverride = active.mode,
                            finalEffective = true,
                            precedence = "diagnostic_override_force_on",
                            exactScopeMatched = false,
                        )
                    else ->
                        ReadStateV1DiagnosticDecision(
                            serverEffective = serverEffective,
                            clientOverride = active?.mode ?: ReadStateV1DiagnosticOverrideMode.Server,
                            finalEffective = serverEffective,
                            precedence = "server",
                            exactScopeMatched = false,
                        )
                }
            lastDecision = decision
            decision
        }
    }

    fun snapshot(): ReadStateV1DiagnosticOverrideSnapshot {
        refreshExpired()
        return synchronized(lock) { snapshotLocked() }
    }

    private fun expireLocked() {
        val active = activeOverride ?: return
        if (nowMillis() >= active.expiresAtMillis) clearLocked("expired")
    }

    private fun clearLocked(reason: String) {
        activeOverride = null
        lastDecision = null
        clearReason = reason
        if (reason in setOf("identity_changed", "authenticated_identity_changed", "logout", "entitlement_disabled")) {
            selectedScope = null
            witnessGeneration = null
            durableReadStateVersion = null
            durableMaxReadSeq = null
            latestAdjudication = null
            destinationPublication = null
            barrierState = null
            stalePublicationRejectedCount = 0L
        }
    }

    private fun snapshotLocked(): ReadStateV1DiagnosticOverrideSnapshot =
        ReadStateV1DiagnosticOverrideSnapshot(
            entitled = entitled,
            mode = activeOverride?.mode ?: ReadStateV1DiagnosticOverrideMode.Server,
            observedScope = observedScope,
            boundScope = null,
            expiresAtMillis = activeOverride?.expiresAtMillis,
            lastDecision = lastDecision,
            clearReason = clearReason,
            selectedScope = selectedScope,
            witnessGeneration = witnessGeneration,
            durableReadStateVersion = durableReadStateVersion,
            durableMaxReadSeq = durableMaxReadSeq,
            latestAdjudication = latestAdjudication,
            destinationPublication = destinationPublication,
            barrierState = barrierState,
            stalePublicationRejectedCount = stalePublicationRejectedCount,
        )
}

private fun ReadStateV1DiagnosticDecision.withOverride(
    mode: ReadStateV1DiagnosticOverrideMode,
): ReadStateV1DiagnosticDecision =
    when (mode) {
        ReadStateV1DiagnosticOverrideMode.Server ->
            copy(
                clientOverride = mode,
                finalEffective = serverEffective,
                precedence = "server",
                exactScopeMatched = false,
            )
        ReadStateV1DiagnosticOverrideMode.ForceOff ->
            copy(
                clientOverride = mode,
                finalEffective = false,
                precedence = "diagnostic_override_force_off",
                exactScopeMatched = false,
            )
        ReadStateV1DiagnosticOverrideMode.ForceOn ->
            copy(
                clientOverride = mode,
                finalEffective = true,
                precedence = "diagnostic_override_force_on",
                exactScopeMatched = false,
            )
    }

object ProcessReadStateV1DiagnosticOverride {
    val controller: ReadStateV1DiagnosticOverrideController =
        ReadStateV1DiagnosticOverrideController(nowMillis = { defaultNowMillis() })
}

private fun ReadStateV1ProductionOwner.toDiagnosticScope(channelId: String): ReadStateV1DiagnosticExactScope? =
    takeIf {
        environmentId.isNotBlank() && accountId.isNotBlank() && serverId.isNotBlank() && channelId.isNotBlank()
    }?.let {
        ReadStateV1DiagnosticExactScope(
            environmentId = environmentId.trim(),
            accountId = accountId.trim(),
            serverId = serverId.trim(),
            channelId = channelId.trim(),
        )
    }
