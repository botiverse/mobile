package build.raft.app.data

import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitOutcome
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal enum class RealtimeCanonicalFinalFlagRoute {
    LegacyWithoutT3,
    LegacyWithCurrentT3,
    Prepared,
}

internal data class RealtimeCanonicalFinalFlagEvaluation(
    val snapshot: ServerFeatureFlagSnapshot,
    val route: RealtimeCanonicalFinalFlagRoute,
)

internal enum class RealtimeCanonicalFinalOwnerEntry {
    Process,
    Selected,
    BackgroundUnread,
}

internal fun interface RealtimeCanonicalFinalOwnerGateway {
    suspend fun accept(
        entry: RealtimeCanonicalFinalOwnerEntry,
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        legacyWithoutT3: suspend (CurrentMessageCommitContext) -> Unit,
        legacyWithCurrentT3: suspend (CurrentMessageCommitContext) -> Unit,
        commitPrepared:
            suspend (CurrentMessageCommitContext) -> PreparedCurrentGrantMessageCommitDisposition,
    ): Boolean
}

/**
 * One immutable per-server snapshot decides the route for one event. T3 (the retired `sync_core_messages_v1`)
 * is always on once the snapshot resolves; an unresolved snapshot and a missing T4 value are OFF.
 */
internal fun realtimeCanonicalFinalFlagRoute(
    snapshot: ServerFeatureFlagSnapshot,
    t4FlagKey: String,
): RealtimeCanonicalFinalFlagRoute {
    if (!snapshot.resolved) return RealtimeCanonicalFinalFlagRoute.LegacyWithoutT3
    if (!snapshot.isEnabled(t4FlagKey)) return RealtimeCanonicalFinalFlagRoute.LegacyWithCurrentT3
    return RealtimeCanonicalFinalFlagRoute.Prepared
}

internal sealed interface RealtimeCanonicalFinalPreparationResult {
    data object StaleGrant : RealtimeCanonicalFinalPreparationResult
    data object LegacyWithoutT3 : RealtimeCanonicalFinalPreparationResult
    data object LegacyWithCurrentT3 : RealtimeCanonicalFinalPreparationResult

    data class Prepared(
        val token: RealtimeAuthorityProvider.PreparedCanonicalFinalCommit,
        val capturedRegistrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
    ) : RealtimeCanonicalFinalPreparationResult
}

/**
 * Provider-injected dormant integration definitions. The production provider passes null today, so no flag read,
 * directory capture, router construction, queue, worker, or invalidation call is activated by this stage.
 */
internal class RealtimeCanonicalFinalSinkIntegrationSeam(
    private val snapshotForServer: (String) -> ServerFeatureFlagSnapshot,
    val registrationDirectory: RealtimeCanonicalFinalSinkRegistrationDirectory,
    private val t4FlagKey: String,
    private val lazyRouterFactory: (() -> RealtimeCanonicalFinalSinkLazyRouter)? = null,
) {
    init {
        require(t4FlagKey.isNotBlank()) { "canonical final sink T4 flag key must not be blank" }
    }

    private val routerLock = SynchronizedObject()
    private var router: RealtimeCanonicalFinalSinkLazyRouter? = null

    fun evaluate(serverId: String): RealtimeCanonicalFinalFlagEvaluation {
        val observed = snapshotForServer(serverId)
        val snapshot =
            observed.takeIf { candidate -> candidate.serverId?.value == serverId }
                ?: ServerFeatureFlagSnapshot.Unresolved
        return RealtimeCanonicalFinalFlagEvaluation(
            snapshot = snapshot,
            route = realtimeCanonicalFinalFlagRoute(snapshot, t4FlagKey),
        )
    }

    fun capture(
        scopeLeaseId: String,
        serverId: String,
        allowedSurfaceKeys: Set<RealtimeCanonicalFinalSinkSurfaceKey>,
    ): List<RealtimeCanonicalFinalSinkCapturedRegistration> =
        registrationDirectory.capture(scopeLeaseId, serverId, allowedSurfaceKeys)

    fun captureForOwnerEntry(
        scopeLeaseId: String,
        serverId: String,
        entry: RealtimeCanonicalFinalOwnerEntry,
    ): List<RealtimeCanonicalFinalSinkCapturedRegistration> =
        registrationDirectory.capture(scopeLeaseId, serverId).filter { registration ->
            when (entry) {
                RealtimeCanonicalFinalOwnerEntry.Process -> false
                RealtimeCanonicalFinalOwnerEntry.Selected ->
                    registration.handle.slot.surfaceKey != RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread
                RealtimeCanonicalFinalOwnerEntry.BackgroundUnread ->
                    registration.handle.slot.surfaceKey == RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread
            }
        }

    /** Construct only after an authority-bound prepared event captured at least one surface. */
    fun routerForPreparedEvent(
        capturedRegistrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
    ): RealtimeCanonicalFinalSinkLazyRouter {
        require(capturedRegistrations.isNotEmpty()) {
            "canonical final sink sidecar must not be constructed without a captured surface"
        }
        val factory = checkNotNull(lazyRouterFactory) { "canonical final sink lazy router is not configured" }
        return synchronized(routerLock) {
            router ?: factory().also { router = it }
        }
    }

    fun invalidateIfConstructed(currentGrants: Set<RealtimeGrantRecord>) {
        val existing = synchronized(routerLock) { router }
        existing?.invalidateIfConstructed(currentGrants)
    }

    fun routerConstructionCount(): Int = synchronized(routerLock) { if (router == null) 0 else 1 }
}

internal class ProviderBackedRealtimeCanonicalFinalOwnerGateway(
    private val provider: RealtimeAuthorityProvider,
) : RealtimeCanonicalFinalOwnerGateway {
    override suspend fun accept(
        entry: RealtimeCanonicalFinalOwnerEntry,
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        legacyWithoutT3: suspend (CurrentMessageCommitContext) -> Unit,
        legacyWithCurrentT3: suspend (CurrentMessageCommitContext) -> Unit,
        commitPrepared:
            suspend (CurrentMessageCommitContext) -> PreparedCurrentGrantMessageCommitDisposition,
    ): Boolean {
        val preparation =
            provider.prepareCanonicalFinalCommitForOwner(
                entry = entry,
                capturedGrant = grant,
                legacyKey = legacyKey,
                foldInput = foldInput,
                legacyWithoutT3 = legacyWithoutT3,
                legacyWithCurrentT3 = legacyWithCurrentT3,
            )
        return when (preparation) {
            RealtimeCanonicalFinalPreparationResult.StaleGrant -> false
            RealtimeCanonicalFinalPreparationResult.LegacyWithoutT3,
            RealtimeCanonicalFinalPreparationResult.LegacyWithCurrentT3,
            -> true
            is RealtimeCanonicalFinalPreparationResult.Prepared ->
                acceptPrepared(
                    preparation = preparation,
                    grant = grant,
                    event = event,
                    source = source,
                    legacyKey = legacyKey,
                    foldInput = foldInput,
                    legacyWithoutT3 = legacyWithoutT3,
                    commitPrepared = commitPrepared,
                )
        }
    }

    private suspend fun acceptPrepared(
        preparation: RealtimeCanonicalFinalPreparationResult.Prepared,
        grant: RealtimeGrantRecord,
        event: RealtimeEvent,
        source: String,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        legacyWithoutT3: suspend (CurrentMessageCommitContext) -> Unit,
        commitPrepared:
            suspend (CurrentMessageCommitContext) -> PreparedCurrentGrantMessageCommitDisposition,
    ): Boolean {
        if (preparation.capturedRegistrations.isEmpty()) {
            var accepted = false
            val current =
                provider.runWithPreparedCanonicalFinalCommit(
                    token = preparation.token,
                    capturedGrant = grant,
                    expectedPartition = preparation.token.partition,
                    expectedFlagSnapshot = preparation.token.flagSnapshot,
                    legacyKey = legacyKey,
                    foldInput = foldInput,
                ) { context ->
                    accepted =
                        when (commitPrepared(context)) {
                            PreparedCurrentGrantMessageCommitDisposition.FinalizableCommitted,
                            PreparedCurrentGrantMessageCommitDisposition.FinalizableDurableNoChange,
                            -> true
                            is PreparedCurrentGrantMessageCommitDisposition.LegacyFallback -> {
                                legacyWithoutT3(context)
                                true
                            }
                            is PreparedCurrentGrantMessageCommitDisposition.Indeterminate -> false
                        }
                }
            return current && accepted
        }

        val router = checkNotNull(provider.canonicalFinalRouterForPreparedEvent(preparation.capturedRegistrations))
        return router.acceptPrepared(grant) { sidecar, reservation ->
            var result: RealtimeCanonicalFinalPreparedReservationResult =
                RealtimeCanonicalFinalPreparedReservationResult.NotPublished(accepted = false)
            val current =
                provider.runWithPreparedCanonicalFinalCommit(
                    token = preparation.token,
                    capturedGrant = grant,
                    expectedPartition = preparation.token.partition,
                    expectedFlagSnapshot = preparation.token.flagSnapshot,
                    legacyKey = legacyKey,
                    foldInput = foldInput,
                ) { context ->
                    result =
                        when (val disposition = commitPrepared(context)) {
                            PreparedCurrentGrantMessageCommitDisposition.FinalizableCommitted,
                            PreparedCurrentGrantMessageCommitDisposition.FinalizableDurableNoChange,
                            -> {
                                val outcome =
                                    when (disposition) {
                                        PreparedCurrentGrantMessageCommitDisposition.FinalizableCommitted ->
                                            CurrentGrantMessageShadowCommitOutcome.Committed
                                        PreparedCurrentGrantMessageCommitDisposition.FinalizableDurableNoChange ->
                                            CurrentGrantMessageShadowCommitOutcome.DurableNoChange
                                        else -> error("unreachable finalizable disposition")
                                    }
                                RealtimeCanonicalFinalPreparedReservationResult.Published(
                                    sidecar.publishReserved(
                                        reservation = reservation,
                                        outcome = outcome,
                                        envelope =
                                            RealtimeCanonicalFinalSinkEnvelope(
                                                grant = grant,
                                                partition = context.partition,
                                                eventKey = legacyKey.value,
                                                event = event,
                                                source = source,
                                                capturedRegistrations = preparation.capturedRegistrations,
                                            ),
                                    ),
                                )
                            }
                            is PreparedCurrentGrantMessageCommitDisposition.LegacyFallback -> {
                                legacyWithoutT3(context)
                                RealtimeCanonicalFinalPreparedReservationResult.NotPublished(accepted = true)
                            }
                            is PreparedCurrentGrantMessageCommitDisposition.Indeterminate ->
                                RealtimeCanonicalFinalPreparedReservationResult.NotPublished(accepted = false)
                        }
                }
            if (current) result else RealtimeCanonicalFinalPreparedReservationResult.NotPublished(accepted = false)
        }
    }
}
