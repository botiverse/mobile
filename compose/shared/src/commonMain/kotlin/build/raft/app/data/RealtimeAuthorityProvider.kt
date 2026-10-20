package build.raft.app.data

import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.CanonicalMessageFoldFingerprint
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
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
    private val authStore: RaftAuthSessionStore,
    private val authEvents: RaftAuthSessionEventSource,
    // Environment half of the session identity (task #1109). Read together with the session on every
    // reconcile; the API base url of the selected environment is the app-wide environment id
    // (same normalization as the presence binding and the pending-send journal owner key).
    private val currentEnvironmentId: () -> String,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val canonicalFinalIntegration: RealtimeCanonicalFinalSinkIntegrationSeam? = null,
    // D.18 fault-injection seam: tests override the canonical-final
    // invalidation call to prove pendingPublication semantics; production
    // leaves null and uses canonicalFinalIntegration.invalidateIfConstructed.
    private val canonicalFinalInvalidationOverride: ((Set<RealtimeGrantRecord>) -> Unit)? = null,
    // Constructor-injected transition hook (task #136 presence readiness):
    // invoked INSIDE the lifecycle mutex on every path that advances the auth
    // revision, after the registry advance and BEFORE grants/authority flows
    // publish — so any state derived from the revision (the presence binding)
    // is ready the moment a new grant becomes obtainable. The type is
    // deliberately NON-SUSPENDING: the hook runs while the lifecycle mutex is
    // held and must not block, schedule, or re-enter authority APIs.
    //
    // Failure is TRANSACTIONAL, not merely ordered (post-merge audit round 4
    // on PR #1075): a throw is caught here, recorded as a pending binding,
    // and blocks EVERY later publication path (registerScope / releaseScope /
    // auth events / resolve) until a retry repairs the binding — the event
    // collector survives, nothing publishes on top of the already-advanced
    // registry, and the first successful retry republishes current truth.
    private val onAuthRevisionAdvanced: (RealtimeAuthRevision) -> Unit = {},
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
    // D.17: every diagnostics call goes through this wrapper — a throwing
    // logger must never kill the event collector or clear pending state.
    private val safeDiagnostics: (String) -> Unit = { message ->
        try {
            diagnosticsLogger(message)
        } catch (_: Throwable) {
            // Diagnostics are best-effort by contract.
        }
    }

    // D.17: an auth EVENT is a consumed wake signal — if its transition body
    // throws anywhere (session read, registry advance, …) there is no replay.
    // This atomic flag fail-closes every handout/publication surface until a
    // later entry repairs STATE-BASED: re-read current session → authority
    // advance → binding hook → the entry's single final publication.
    private val pendingAuthReconciliation = MutableStateFlow(false)

    // D.18: a failed canonical-final invalidation (flows always publish
    // completely) is recorded here and retried by the next entry's forced
    // full publication — no half-published terminal state.
    private val pendingPublication = MutableStateFlow(false)

    // Item 28: scope keys released (dead) — their flow entry is conditionally
    // removed after the terminal null publication. Pre-birth entries
    // (authorityFlow() called before registerScope lands) are NEVER swept.
    private val deadScopeKeys = mutableSetOf<String>()

    // Two DISTINCT initialization bits (post-merge audit round 5 on PR
    // #1075): `registryInitialized` marks that registry.initialize() ran —
    // it must run EXACTLY ONCE, because re-running it resets the registry
    // snapshot and would erase leases registered while the binding was still
    // unready (multi-scope cold-start registration loss). `initialized`
    // additionally requires the binding hook to have succeeded and the first
    // publication to have happened.
    private var registryInitialized = false
    private var initialized = false
    // Non-null generation = the binding hook FAILED for that revision: the
    // registry/currentSession are already advanced, so every publication
    // path must repair the binding (re-run the hook) before anything new
    // becomes obtainable. Mutated under lifecycleMutex; a StateFlow so the
    // un-mutexed fast-path read in resolve() is atomic rather than a plain
    // cross-thread var.
    private val pendingBindingGeneration = MutableStateFlow<Long?>(null)
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
            // Repair-before-proceed: a pending binding is retried FIRST; if
            // it still fails, the lease is registered but NOTHING publishes —
            // no grant becomes obtainable over a stale binding. When the
            // repair (or first initialization) succeeds on THIS entry, the
            // single publication below is forced-full so it covers the
            // withheld state — from the FINAL post-mutation truth only.
            val fullPublish = needsFullPublishLocked()
            val ready = ensureInitializedLocked()
            val grantsBeforeMutation = if (ready && fullPublish) emptySet() else currentGrantSet()
            val normalized = scopeKey.normalizedScopeKey()
            val normalizedRegistrationId = registrationId.normalizedRegistrationId()
            val previousRegistration = activeRegistrationByScope[normalized]
            if (previousRegistration != null && previousRegistration != normalizedRegistrationId) {
                registry.snapshot.value.activeLeases.values
                    .firstOrNull { it.scopeKey == normalized }
                    ?.let { registry.release(it.id) }
            }
            activeRegistrationByScope[normalized] = normalizedRegistrationId
            deadScopeKeys.remove(normalized)
            val lease = registry.register(normalized)
            if (ready) {
                publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
            } else {
                safeDiagnostics(
                    "realtime_authority_register result=deferred reason=binding_not_ready scope=$normalized"
                )
            }
            lease
        }

    suspend fun releaseScope(
        scopeKey: String,
        registrationId: String = scopeKey,
    ): Boolean =
        lifecycleMutex.withLock {
            val fullPublish = needsFullPublishLocked()
            val ready = ensureInitializedLocked()
            val grantsBeforeMutation = if (ready && fullPublish) emptySet() else currentGrantSet()
            val normalized = scopeKey.normalizedScopeKey()
            val normalizedRegistrationId = registrationId.normalizedRegistrationId()
            if (activeRegistrationByScope[normalized] != normalizedRegistrationId) {
                return@withLock false
            }
            activeRegistrationByScope.remove(normalized)
            deadScopeKeys.add(normalized)
            val lease = registry.snapshot.value.activeLeases.values.firstOrNull { it.scopeKey == normalized }
                ?: run {
                    flowFor(normalized).value = null
                    sweepDeadAuthorityFlowsLocked()
                    return@withLock false
                }
            val released = registry.release(lease.id)
            if (ready) {
                publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
            } else {
                safeDiagnostics(
                    "realtime_authority_release result=deferred reason=binding_not_ready scope=$normalized"
                )
            }
            sweepDeadAuthorityFlowsLocked()
            released
        }

    suspend fun resolve(
        witness: RealtimeConnectionWitness,
        eventServerId: String,
    ): RealtimeGrantRecord? {
        // While a binding repair is pending, no grant may be handed out —
        // structurally the witness would already be stale (the registry has
        // advanced past its generation), but the gate makes resolve
        // explicitly fail-closed rather than relying on that argument.
        if (pendingBindingGeneration.value != null || pendingAuthReconciliation.value) return null
        return registry.resolve(witness, eventServerId)
    }

    suspend fun runIfCurrentGrant(
        grant: RealtimeGrantRecord,
        block: suspend () -> Unit,
    ): Boolean {
        // D.17: while an auth transition is pending reconciliation, grants
        // minted under the possibly-stale authority must not run — a Cleared
        // event whose session read failed would otherwise leave logged-out
        // grants alive until the next successful event.
        if (pendingAuthReconciliation.value) return false
        return registry.runIfCurrentGrant(grant, block)
    }

    suspend fun runTerminalIfCurrentGrant(
        grant: RealtimeGrantRecord,
        block: suspend () -> Unit,
    ): Boolean =
        lifecycleMutex.withLock {
            if (pendingAuthReconciliation.value) return@withLock false
            val grantsBeforeMutation = currentGrantSet()
            var terminalized = false
            try {
                registry.terminalizeIfCurrentGrant(grant) {
                    terminalized = true
                    block()
                }
            } finally {
                if (terminalized) {
                    publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
                }
            }
        }

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
            block(capturedGrant.currentMessageCommitContext())
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

    /**
     * Cold-start readiness seam for other provider-owned authority consumers. It uses the exact
     * lifecycle reconciliation path (including the auth-revision binding hook) instead of letting
     * a consumer synthesize environment/session ownership independently.
     */
    internal suspend fun ensureCurrentAuthBinding(): Boolean =
        lifecycleMutex.withLock {
            try {
                val fullPublish = needsFullPublishLocked()
                if (!ensureInitializedLocked()) return@withLock false
                val grantsBeforeMutation = if (fullPublish) emptySet() else currentGrantSet()
                currentSession = authStore.readSession()
                registry.advanceAuthFromCurrentSession(
                    session = currentSession.toRealtimeAuthoritySession(currentEnvironmentId()),
                    force = false,
                )
                if (!runBindingHookLocked()) return@withLock false
                publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                pendingAuthReconciliation.value = true
                safeDiagnostics(
                    "realtime_authority_reconcile result=pending " +
                        "reason=${throwable::class.simpleName ?: "unknown"}"
                )
                false
            }
        }

    private suspend fun reconcileInitialSession() {
        // Restart/subscription is a WAKE with no event payload: like any entry it rebuilds
        // authority truth from the current durable session and finishes with one publication.
        ensureCurrentAuthBinding()
    }

    private suspend fun onAuthSessionEvent(event: AuthSessionEvent) {
        lifecycleMutex.withLock {
            try {
                processAuthSessionEventLocked(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // D.17: the consumed event cannot replay — record the pending
                // reconciliation (fail-closing every handout surface) and let
                // any later entry repair from current state. The collector
                // itself survives every transition failure.
                pendingAuthReconciliation.value = true
                safeDiagnostics(
                    "realtime_authority_auth_event result=pending " +
                        "reason=${throwable::class.simpleName ?: "unknown"} generation=${event.generation}"
                )
            }
        }
    }

    private suspend fun processAuthSessionEventLocked(event: AuthSessionEvent) {
        run {
            val fullPublish = needsFullPublishLocked()
            if (!ensureInitializedLocked()) {
                safeDiagnostics(
                    "realtime_authority_auth_event result=deferred reason=binding_not_ready " +
                        "generation=${event.generation}"
                )
                return@run
            }
            if (event.generation <= lastObservedAuthEventGeneration) {
                // The event brings nothing newer, but if THIS entry repaired a
                // pending binding (or completed initialization), the withheld
                // publication still has to happen — once, from current truth.
                if (fullPublish) publishAndInvalidateIfGrantSetChangedLocked(emptySet())
                safeDiagnostics(
                    "realtime_authority_auth_event result=ignored reason=stale " +
                        "generation=${event.generation} observed=$lastObservedAuthEventGeneration"
                )
                return@run
            }
            // A repaired-but-about-to-be-superseded revision is NEVER
            // published: the advance below moves straight to the event's
            // revision and the single publication at the end exposes only the
            // final state (round-6 audit — no obsolete intermediate authority
            // for lock-free flow readers).
            val grantsBeforeMutation = if (fullPublish) emptySet() else currentGrantSet()
            currentSession = authStore.readSession()
            registry.advanceAuthFromCurrentSession(
                session = currentSession.toRealtimeAuthoritySession(currentEnvironmentId()),
                force = true,
            )
            // Revision-derived state (presence binding) becomes ready HERE —
            // before the observed-generation watermark moves and before the
            // new grants are published below. A hook throw is CAUGHT (the
            // collector survives) and leaves a pending binding that blocks
            // every publication path until a later entry repairs it; the
            // watermark stays untouched and nothing publishes on top of the
            // already-advanced registry.
            if (!runBindingHookLocked()) return@run
            lastObservedAuthEventGeneration = event.generation
            publishAndInvalidateIfGrantSetChangedLocked(grantsBeforeMutation)
        }
    }

    /** Runs the binding hook against the CURRENT registry revision. A throw
     *  is caught and recorded as a pending binding — never propagated, so the
     *  auth-event collector cannot die from a hook failure. */
    private fun runBindingHookLocked(): Boolean {
        val revision = registry.snapshot.value.authRevision
        return try {
            onAuthRevisionAdvanced(revision)
            pendingBindingGeneration.value = null
            true
        } catch (throwable: Throwable) {
            pendingBindingGeneration.value = revision.generation
            safeDiagnostics(
                "realtime_authority_binding_hook_failed generation=${revision.generation} " +
                    "error=${throwable::class.simpleName ?: "unknown"}"
            )
            false
        }
    }

    /** Repair-before-proceed: every publication-capable entry calls this
     *  first. Repair ONLY establishes readiness — it never publishes (round-6
     *  audit: an immediate repair-publication exposed transient authority the
     *  caller was about to remove or supersede, e.g. a deferred scope
     *  resurrecting during its own release). Each caller performs its
     *  mutation first, then publishes exactly once from the FINAL state,
     *  forcing canonical-final invalidation when a repair (or first
     *  initialization) made the previous published state stale. */
    private fun bindingReadyLocked(): Boolean {
        if (pendingBindingGeneration.value == null) return true
        return runBindingHookLocked()
    }

    /** True when the next successful publication must be a FULL forced one:
     *  a repair is pending (published state is withheld/stale) or the
     *  provider has not yet completed its first initialization publication. */
    private fun needsFullPublishLocked(): Boolean =
        pendingBindingGeneration.value != null ||
            !initialized ||
            pendingPublication.value ||
            pendingAuthReconciliation.value

    /** D.17 state-based repair: while an auth transition is pending, rebuild
     *  authority truth from the CURRENT session (no event replay), then the
     *  binding; the caller's single final publication exposes the result.
     *  A repeated failure keeps the pending flag (and propagates to public
     *  API callers, who retry). */
    private suspend fun reconcilePendingAuthLocked(): Boolean {
        if (!pendingAuthReconciliation.value) return true
        currentSession = authStore.readSession()
        registry.advanceAuthFromCurrentSession(
            session = currentSession.toRealtimeAuthoritySession(currentEnvironmentId()),
            force = true,
        )
        if (!runBindingHookLocked()) return false
        pendingAuthReconciliation.value = false
        return true
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized) {
            if (!reconcilePendingAuthLocked()) return false
            return bindingReadyLocked()
        }
        // registry.initialize() runs at most once: re-running it on a
        // binding-retry would RESET the registry snapshot and erase leases
        // registered while the binding was unready — losing scopes that then
        // never receive authority (round-5 audit finding).
        if (!registryInitialized) {
            registry.initialize()
            registryInitialized = true
        }
        currentSession = authStore.readSession()
        // Duplicate-safe on retry: the durable revision already matches, so
        // the registry advance is a no-op while leases stay intact.
        registry.advanceAuthFromCurrentSession(
            session = currentSession.toRealtimeAuthoritySession(currentEnvironmentId()),
            force = false,
        )
        // Initial-session readiness: the binding for the durable/current
        // revision must be ready before the provider is marked initialized
        // and before the first grants become visible. A hook failure leaves
        // the provider not-fully-initialized with nothing published — every
        // later entry retries from the advance (never from initialize), and
        // the eventual publishLocked() below publishes authority for EVERY
        // scope registered during the unready window.
        if (!runBindingHookLocked()) return false
        initialized = true
        // No publication here: the CALLER publishes once from its final
        // state (round-6 audit — publishing mid-entry exposed authority the
        // caller was about to mutate away). The start()/reconcile entry has
        // no mutation and publishes right after this returns.
        return true
    }

    private fun publishLocked(
        beforeAuthorityFlowPublish: () -> Unit = {},
    ) {
        val snapshot = registry.snapshot.value
        val session = currentSession
        val sessionIdentity = session.toRealtimeAuthoritySession(currentEnvironmentId())
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
        sweepDeadAuthorityFlowsLocked()
    }

    private fun publishAndInvalidateIfGrantSetChangedLocked(
        grantsBeforeMutation: Set<RealtimeGrantRecord>,
    ) {
        val grantsAfterMutation = currentGrantSet()
        val invalidate: ((Set<RealtimeGrantRecord>) -> Unit)? =
            canonicalFinalInvalidationOverride
                ?: canonicalFinalIntegration?.let { it::invalidateIfConstructed }
        publishLocked {
            if (invalidate != null &&
                (grantsAfterMutation != grantsBeforeMutation || pendingPublication.value)
            ) {
                try {
                    invalidate(grantsAfterMutation)
                    pendingPublication.value = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // D.18: the flows below still publish COMPLETELY — only
                    // the invalidation side effect is outstanding. It is
                    // recorded and retried (forced) by the next entry's full
                    // publication; flow sets are idempotent, so the retry
                    // cannot duplicate authority emissions.
                    pendingPublication.value = true
                    safeDiagnostics(
                        "realtime_authority_invalidate_failed " +
                            "error=${throwable::class.simpleName ?: "unknown"}"
                    )
                }
            }
        }
    }

    /** Item 28: conditionally remove RELEASED scopes' flow entries once the
     *  terminal null has been delivered — only when the key has no active
     *  lease, no registration bookkeeping, and was explicitly released (the
     *  dead mark distinguishes it from a pre-birth entry created by
     *  authorityFlow() before its registerScope lands). Old observers keep
     *  their permanently-null instance; a future same-key registration mints
     *  a fresh flow. */
    private fun sweepDeadAuthorityFlowsLocked() {
        if (deadScopeKeys.isEmpty()) return
        val leased = registry.snapshot.value.activeLeases.values.mapTo(mutableSetOf()) { it.scopeKey }
        val removable = deadScopeKeys.filter { key ->
            key !in leased && !activeRegistrationByScope.containsKey(key)
        }
        if (removable.isEmpty()) return
        synchronized(authorityFlowsLock) {
            removable.forEach { key ->
                val flow = authorityFlows[key]
                if (flow == null) {
                    // Nothing was ever published for this key (failure-period
                    // register/release with no observer): clearing the mark
                    // alone completes the release. A LATER authorityFlow()
                    // call creates a fresh pre-birth entry that must never be
                    // swept (terminal review blocker 2: a leaked mark would
                    // delete that pre-birth flow and strand its observer).
                    deadScopeKeys.remove(key)
                } else if (flow.value == null) {
                    authorityFlows.remove(key)
                    deadScopeKeys.remove(key)
                }
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
    CurrentMessageCommitContext(partition = durablePartition(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID))

private fun String.normalizedScopeKey(): String =
    trim().takeIf { it.isNotEmpty() } ?: error("realtime scope key must not be blank")

private fun String.normalizedRegistrationId(): String =
    trim().takeIf { it.isNotEmpty() } ?: error("realtime registration id must not be blank")

/**
 * Session identity = principal + servers + environment. A blank environment yields NO identity (null), never a
 * placeholder: without a real environment no grant can be minted, so nothing can be written under a partition
 * that would collide across environments.
 */
private fun StoredAuthSession?.toRealtimeAuthoritySession(environmentId: String): RealtimeAuthoritySession? =
    this?.let { session ->
        val principalId = session.user.id.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedEnvironmentId = environmentId.normalizedEnvironmentId().takeIf { it.isNotEmpty() } ?: return null
        RealtimeAuthoritySession(
            principalId = principalId,
            serverIds =
                session.servers
                    .map { it.id.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct(),
            environmentId = normalizedEnvironmentId,
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
