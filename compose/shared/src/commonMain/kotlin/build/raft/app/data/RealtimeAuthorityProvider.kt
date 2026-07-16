package build.raft.app.data

import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.CanonicalMessageFoldFingerprint
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class RealtimeScopeAuthority(
    val lease: RealtimeScopeLease,
    val witness: RealtimeConnectionWitness,
    val grantsByServer: Map<String, RealtimeGrantRecord>,
    val accessToken: String,
) {
    fun grant(serverId: String): RealtimeGrantRecord? = grantsByServer[serverId]
}

/**
 * Ephemeral T3 capability produced only while the registry still holds the captured grant current. It
 * intentionally exposes the durable partition and nothing from the grant/witness/generation authority.
 */
internal data class CurrentMessageCommitContext(
    val partition: DurablePartitionKey,
)

/**
 * Provider-owned lifecycle adapter for the Foundation v1.2 registry.
 *
 * Typed auth events are wake signals only. Every accepted wake rereads the
 * durable auth session, advances the registry as one complete snapshot, and
 * republishes per-scope authority. Scope registration/release/reconnect also
 * live here so Compose and raw callbacks never mint grant identities.
 */
internal class RealtimeAuthorityProvider(
    private val registry: RealtimeAuthorityRegistry,
    private val authStore: SlockAuthSessionStore,
    private val authEvents: SlockAuthSessionEventSource,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val canonicalFinalIntegration: RealtimeCanonicalFinalSinkIntegrationSeam? = null,
) {
    internal class PreparedCanonicalFinalCommit internal constructor(
        internal val exactGrant: RealtimeGrantRecord,
        internal val partition: DurablePartitionKey,
        internal val legacyKey: LegacyRealtimeDedupKey,
        internal val fingerprint: CanonicalMessageFoldFingerprint,
        internal val flagSnapshot: ServerFeatureFlagSnapshot,
        internal val nonce: Long,
        private val mintAuthority: Any,
    ) {
        private val consumed = atomic(false)

        internal fun consume(
            expectedMintAuthority: Any,
            expectedGrant: RealtimeGrantRecord,
            expectedPartition: DurablePartitionKey,
            expectedLegacyKey: LegacyRealtimeDedupKey,
            expectedFingerprint: CanonicalMessageFoldFingerprint,
            expectedFlagSnapshot: ServerFeatureFlagSnapshot,
        ): Boolean {
            if (mintAuthority !== expectedMintAuthority) return false
            if (exactGrant != expectedGrant) return false
            if (partition != expectedPartition) return false
            if (legacyKey != expectedLegacyKey) return false
            if (fingerprint.structuralInput != expectedFingerprint.structuralInput) return false
            if (flagSnapshot !== expectedFlagSnapshot || flagSnapshot != expectedFlagSnapshot) return false
            return consumed.compareAndSet(expect = false, update = true)
        }

        internal fun abandon(): Boolean = consumed.compareAndSet(expect = false, update = true)
    }

    private val lifecycleMutex = Mutex()
    private val startLock = SynchronizedObject()
    private val authorityFlowsLock = SynchronizedObject()
    private val authorityFlows = mutableMapOf<String, MutableStateFlow<RealtimeScopeAuthority?>>()
    private val activeRegistrationByScope = mutableMapOf<String, String>()
    private val canonicalFinalOwnerGateway: RealtimeCanonicalFinalOwnerGateway? by lazy {
        canonicalFinalIntegration?.let { ProviderBackedRealtimeCanonicalFinalOwnerGateway(this) }
    }
    private var initialized = false
    private var lastObservedAuthEventGeneration = 0L
    private var currentSession: StoredAuthSession? = null
    private var lifecycleJob: Job? = null
    private val preparedCanonicalFinalMintAuthority by lazy { Any() }
    private val nextPreparedCanonicalFinalNonce by lazy { atomic(0L) }

    fun start(scope: CoroutineScope): Job =
        synchronized(startLock) {
            lifecycleJob?.takeIf { it.isActive }
                ?: scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    authEvents.authSessionEvents
                        .onSubscription { reconcileInitialSession() }
                        .collect(::onAuthSessionEvent)
                }.also { lifecycleJob = it }
        }

    fun authorityFlow(scopeKey: String): StateFlow<RealtimeScopeAuthority?> =
        synchronized(authorityFlowsLock) {
            authorityFlows.getOrPut(scopeKey.normalizedScopeKey()) { MutableStateFlow(null) }.asStateFlow()
        }

    suspend fun registerScope(
        scopeKey: String,
        registrationId: String = scopeKey,
    ): RealtimeScopeLease =
        lifecycleMutex.withLock {
            ensureInitializedLocked()
            val grantsBeforeMutation = currentGrantSet()
            val normalized = scopeKey.normalizedScopeKey()
            val normalizedRegistrationId = registrationId.normalizedRegistrationId()
            val previousRegistration = activeRegistrationByScope[normalized]
            if (previousRegistration != null && previousRegistration != normalizedRegistrationId) {
                registry.snapshot.value.activeLeases.values
                    .firstOrNull { it.scopeKey == normalized }
                    ?.let { registry.release(it.id) }
            }
            activeRegistrationByScope[normalized] = normalizedRegistrationId
            val lease = registry.register(normalized)
            publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
            lease
        }

    suspend fun releaseScope(
        scopeKey: String,
        registrationId: String = scopeKey,
    ): Boolean =
        lifecycleMutex.withLock {
            ensureInitializedLocked()
            val grantsBeforeMutation = currentGrantSet()
            val normalized = scopeKey.normalizedScopeKey()
            val normalizedRegistrationId = registrationId.normalizedRegistrationId()
            if (activeRegistrationByScope[normalized] != normalizedRegistrationId) {
                return@withLock false
            }
            activeRegistrationByScope.remove(normalized)
            val lease = registry.snapshot.value.activeLeases.values.firstOrNull { it.scopeKey == normalized }
                ?: run {
                    flowFor(normalized).value = null
                    return@withLock false
                }
            val released = registry.release(lease.id)
            publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
            released
        }

    suspend fun resolve(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
    ): RealtimeGrantRecord? = registry.resolve(witness, eventServerId)

    suspend fun runIfCurrentGrant(
        grant: RealtimeGrantRecord,
        block: suspend () -> Unit,
    ): Boolean = registry.runIfCurrentGrant(grant, block)

    /**
     * Reuses the registry's current-grant linearization for the provider-central message shadow lane.
     * Provider-owned guarded canonical New/Updated frames enter that lane before acceptedKeys, recovery, and
     * sinks; standalone owners with a null runner keep their exact legacy path.
     */
    suspend fun runWithCurrentMessageGrant(
        capturedGrant: RealtimeGrantRecord,
        block: suspend (CurrentMessageCommitContext) -> Unit,
    ): Boolean =
        registry.runIfCurrentGrant(capturedGrant) {
            block(
                CurrentMessageCommitContext(
                    partition =
                        DurablePartitionKey(
                            serverId = capturedGrant.serverId,
                            principalId = capturedGrant.principalId,
                            consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                        ),
                ),
            )
        }

    /**
     * First non-nested authority proof for the dormant T4 route. Legacy callbacks execute inside this exact
     * gate; only the ON/ON route mints a single-use token and captures immutable surface callbacks.
     */
    suspend fun prepareCanonicalFinalCommit(
        capturedGrant: RealtimeGrantRecord,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        allowedSurfaceKeys: Set<RealtimeCanonicalFinalSinkSurfaceKey>,
        legacyWithoutT3: suspend (CurrentMessageCommitContext) -> Unit,
        legacyWithCurrentT3: suspend (CurrentMessageCommitContext) -> Unit,
    ): RealtimeCanonicalFinalPreparationResult {
        var result: RealtimeCanonicalFinalPreparationResult = RealtimeCanonicalFinalPreparationResult.StaleGrant
        val current =
            registry.runIfCurrentGrant(capturedGrant) {
                val context = capturedGrant.currentMessageCommitContext()
                val integration = canonicalFinalIntegration
                if (integration == null) {
                    legacyWithoutT3(context)
                    result = RealtimeCanonicalFinalPreparationResult.LegacyWithoutT3
                    return@runIfCurrentGrant
                }
                val evaluation = integration.evaluate(capturedGrant.serverId)
                result =
                    when (evaluation.route) {
                        RealtimeCanonicalFinalFlagRoute.LegacyWithoutT3 -> {
                            legacyWithoutT3(context)
                            RealtimeCanonicalFinalPreparationResult.LegacyWithoutT3
                        }
                        RealtimeCanonicalFinalFlagRoute.LegacyWithCurrentT3 -> {
                            legacyWithCurrentT3(context)
                            RealtimeCanonicalFinalPreparationResult.LegacyWithCurrentT3
                        }
                        RealtimeCanonicalFinalFlagRoute.Prepared -> {
                            val partition = context.partition
                            val token =
                                PreparedCanonicalFinalCommit(
                                    exactGrant = capturedGrant,
                                    partition = partition,
                                    legacyKey = legacyKey,
                                    fingerprint = CanonicalMessageFoldFingerprint.from(foldInput),
                                    flagSnapshot = evaluation.snapshot,
                                    nonce = nextPreparedCanonicalFinalNonce.incrementAndGet(),
                                    mintAuthority = preparedCanonicalFinalMintAuthority,
                                )
                            RealtimeCanonicalFinalPreparationResult.Prepared(
                                token = token,
                                capturedRegistrations =
                                    integration.capture(
                                        scopeLeaseId = capturedGrant.scopeLeaseId,
                                        serverId = capturedGrant.serverId,
                                        allowedSurfaceKeys = allowedSurfaceKeys,
                                    ),
                            )
                        }
                    }
            }
        return if (current) result else RealtimeCanonicalFinalPreparationResult.StaleGrant
    }

    suspend fun prepareCanonicalFinalCommitForOwner(
        entry: RealtimeCanonicalFinalOwnerEntry,
        capturedGrant: RealtimeGrantRecord,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        legacyWithoutT3: suspend (CurrentMessageCommitContext) -> Unit,
        legacyWithCurrentT3: suspend (CurrentMessageCommitContext) -> Unit,
    ): RealtimeCanonicalFinalPreparationResult {
        var result: RealtimeCanonicalFinalPreparationResult = RealtimeCanonicalFinalPreparationResult.StaleGrant
        val current =
            registry.runIfCurrentGrant(capturedGrant) {
                val context = capturedGrant.currentMessageCommitContext()
                val integration = canonicalFinalIntegration
                if (integration == null) {
                    legacyWithoutT3(context)
                    result = RealtimeCanonicalFinalPreparationResult.LegacyWithoutT3
                    return@runIfCurrentGrant
                }
                val evaluation = integration.evaluate(capturedGrant.serverId)
                result =
                    when (evaluation.route) {
                        RealtimeCanonicalFinalFlagRoute.LegacyWithoutT3 -> {
                            legacyWithoutT3(context)
                            RealtimeCanonicalFinalPreparationResult.LegacyWithoutT3
                        }
                        RealtimeCanonicalFinalFlagRoute.LegacyWithCurrentT3 -> {
                            legacyWithCurrentT3(context)
                            RealtimeCanonicalFinalPreparationResult.LegacyWithCurrentT3
                        }
                        RealtimeCanonicalFinalFlagRoute.Prepared -> {
                            val token =
                                PreparedCanonicalFinalCommit(
                                    exactGrant = capturedGrant,
                                    partition = context.partition,
                                    legacyKey = legacyKey,
                                    fingerprint = CanonicalMessageFoldFingerprint.from(foldInput),
                                    flagSnapshot = evaluation.snapshot,
                                    nonce = nextPreparedCanonicalFinalNonce.incrementAndGet(),
                                    mintAuthority = preparedCanonicalFinalMintAuthority,
                                )
                            RealtimeCanonicalFinalPreparationResult.Prepared(
                                token = token,
                                capturedRegistrations =
                                    integration.captureForOwnerEntry(
                                        scopeLeaseId = capturedGrant.scopeLeaseId,
                                        serverId = capturedGrant.serverId,
                                        entry = entry,
                                    ),
                            )
                        }
                    }
            }
        return if (current) result else RealtimeCanonicalFinalPreparationResult.StaleGrant
    }

    /** Second non-nested authority proof. Mismatch/reuse returns false without entering [block]. */
    suspend fun runWithPreparedCanonicalFinalCommit(
        token: PreparedCanonicalFinalCommit,
        capturedGrant: RealtimeGrantRecord,
        expectedPartition: DurablePartitionKey,
        expectedFlagSnapshot: ServerFeatureFlagSnapshot,
        legacyKey: LegacyRealtimeDedupKey,
        foldInput: CanonicalMessageFoldInput,
        block: suspend (CurrentMessageCommitContext) -> Unit,
    ): Boolean {
        var consumed = false
        val current =
            registry.runIfCurrentGrant(capturedGrant) {
                val context = capturedGrant.currentMessageCommitContext()
                if (context.partition != expectedPartition) return@runIfCurrentGrant
                consumed =
                    token.consume(
                        expectedMintAuthority = preparedCanonicalFinalMintAuthority,
                        expectedGrant = capturedGrant,
                        expectedPartition = expectedPartition,
                        expectedLegacyKey = legacyKey,
                        expectedFingerprint = CanonicalMessageFoldFingerprint.from(foldInput),
                        expectedFlagSnapshot = expectedFlagSnapshot,
                    )
                if (consumed) block(context)
            }
        return current && consumed
    }

    fun abandonPreparedCanonicalFinalCommit(token: PreparedCanonicalFinalCommit): Boolean = token.abandon()

    /** Eager authority invalidation without constructing the lazy canonical-final router. */
    fun invalidateCanonicalFinalSinkIfConfigured() {
        canonicalFinalIntegration?.invalidateIfConstructed(
            currentGrantSet(),
        )
    }

    fun canonicalFinalRegistrationDirectoryOrNull(): RealtimeCanonicalFinalSinkRegistrationDirectory? =
        canonicalFinalIntegration?.registrationDirectory

    fun canonicalFinalOwnerGatewayOrNull(): RealtimeCanonicalFinalOwnerGateway? = canonicalFinalOwnerGateway

    fun canonicalFinalRouterForPreparedEvent(
        capturedRegistrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
    ): RealtimeCanonicalFinalSinkLazyRouter? =
        canonicalFinalIntegration?.routerForPreparedEvent(capturedRegistrations)

    fun registerCanonicalFinalSink(
        slot: RealtimeCanonicalFinalSinkRegistrationSlot,
        sink: RealtimeCanonicalFinalSink,
    ): RealtimeCanonicalFinalSinkRegistrationHandle? =
        canonicalFinalIntegration?.registrationDirectory?.register(slot, sink)

    fun unregisterCanonicalFinalSink(handle: RealtimeCanonicalFinalSinkRegistrationHandle): Boolean =
        canonicalFinalIntegration?.registrationDirectory?.unregister(handle) == true

    fun canonicalFinalLifecycleEffectsOrNull(): RealtimeCanonicalFinalSinkLifecycleEffects? =
        canonicalFinalIntegration?.let { RealtimeCanonicalFinalSinkLifecycleEffects(this) }

    private suspend fun reconcileInitialSession() {
        lifecycleMutex.withLock {
            ensureInitializedLocked()
        }
    }

    private suspend fun onAuthSessionEvent(event: AuthSessionEvent) {
        lifecycleMutex.withLock {
            ensureInitializedLocked()
            if (event.generation <= lastObservedAuthEventGeneration) {
                diagnosticsLogger(
                    "realtime_authority_auth_event result=ignored reason=stale " +
                        "generation=${event.generation} observed=$lastObservedAuthEventGeneration"
                )
                return@withLock
            }
            val grantsBeforeMutation = currentGrantSet()
            lastObservedAuthEventGeneration = event.generation
            currentSession = authStore.readSession()
            registry.advanceAuthFromCurrentSession(
                session = currentSession.toRealtimeAuthoritySession(),
                force = true,
            )
            publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
        }
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        registry.initialize()
        currentSession = authStore.readSession()
        registry.advanceAuthFromCurrentSession(
            session = currentSession.toRealtimeAuthoritySession(),
            force = false,
        )
        initialized = true
        publishLocked()
    }

    private fun publishLocked(
        beforeAuthorityFlowPublish: () -> Unit = {},
    ) {
        val snapshot = registry.snapshot.value
        val session = currentSession
        val sessionIdentity = session.toRealtimeAuthoritySession()
        val credentialsMatch = session != null && sessionIdentity == snapshot.authRevision.session
        val activeByKey = snapshot.activeLeases.values.associateBy { it.scopeKey }
        val knownKeys = synchronized(authorityFlowsLock) { authorityFlows.keys.toSet() } + activeByKey.keys
        val authorityByScope =
            knownKeys.associateWith { scopeKey ->
                val lease = activeByKey[scopeKey]
                val familyId = lease?.let { snapshot.currentFamilyByLease[it.id] }
                val grants = lease?.let { snapshot.grantsFor(it.id) }.orEmpty()
                val witness =
                    if (lease != null && familyId != null && credentialsMatch) {
                        RealtimeConnectionWitness(
                            scopeLeaseId = lease.id,
                            principalId = requireNotNull(snapshot.authRevision.session).principalId,
                            authGeneration = snapshot.authRevision.generation,
                            connectionFamilyId = familyId,
                        )
                    } else {
                        null
                    }
                if (lease != null && witness != null && grants.isNotEmpty()) {
                    RealtimeScopeAuthority(
                        lease = lease,
                        witness = witness,
                        grantsByServer = grants.associateBy { it.serverId },
                        accessToken = requireNotNull(session).accessToken,
                    ).takeIf { it.accessToken.isNotBlank() }
                } else {
                    null
                }
            }
        beforeAuthorityFlowPublish()
        authorityByScope.forEach { (scopeKey, authority) ->
            flowFor(scopeKey).value = authority
        }
    }

    private fun publishAndInvalidateIfGrantSetChangedLocked(
        grantsBeforeMutation: Set<RealtimeGrantRecord>,
    ) {
        val grantsAfterMutation = currentGrantSet()
        val integration = canonicalFinalIntegration
        publishLocked {
            if (integration != null && grantsAfterMutation != grantsBeforeMutation) {
                integration.invalidateIfConstructed(grantsAfterMutation)
            }
        }
    }

    private fun currentGrantSet(): Set<RealtimeGrantRecord> =
        registry.snapshot.value.currentGrantBySlot.values.toSet()

    private fun flowFor(scopeKey: String): MutableStateFlow<RealtimeScopeAuthority?> =
        synchronized(authorityFlowsLock) {
            authorityFlows.getOrPut(scopeKey) { MutableStateFlow(null) }
        }
}

private fun RealtimeGrantRecord.currentMessageCommitContext(): CurrentMessageCommitContext =
    CurrentMessageCommitContext(
        partition =
            DurablePartitionKey(
                serverId = serverId,
                principalId = principalId,
                consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
            ),
    )

private fun String.normalizedScopeKey(): String =
    trim().takeIf { it.isNotEmpty() } ?: error("realtime scope key must not be blank")

private fun String.normalizedRegistrationId(): String =
    trim().takeIf { it.isNotEmpty() } ?: error("realtime registration id must not be blank")

private fun StoredAuthSession?.toRealtimeAuthoritySession(): RealtimeAuthoritySession? =
    this?.let { session ->
        val principalId = session.user.id.trim().takeIf { it.isNotEmpty() } ?: return null
        RealtimeAuthoritySession(
            principalId = principalId,
            serverIds =
                session.servers
                    .map { it.id.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct(),
        )
    }

/** Production raw-callback seam: lookup-only resolve, then final apply under the same registry gate. */
internal class RealtimeShellCallbackGate(
    private val authorityProvider: RealtimeAuthorityProvider,
    private val domainOwner: SharedRealtimeDomainEventOwner,
) {
    suspend fun resolveExistingGrant(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
    ): RealtimeGrantRecord? = authorityProvider.resolve(witness, eventServerId)

    suspend fun acceptDomain(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
        event: build.raft.app.model.RealtimeEvent,
        source: String,
    ): Boolean {
        val grant = resolveExistingGrant(witness, eventServerId) ?: return false
        return domainOwner.accept(grant, event, source)
    }

    suspend fun acceptBackgroundUnread(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
        event: build.raft.app.model.RealtimeEvent,
        source: String,
    ): Boolean {
        val grant = resolveExistingGrant(witness, eventServerId) ?: return false
        return domainOwner.acceptHomeUnreadProjection(grant, event, source)
    }
}
