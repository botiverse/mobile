package build.raft.app.data

import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
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
) {
    private val lifecycleMutex = Mutex()
    private val startLock = SynchronizedObject()
    private val authorityFlowsLock = SynchronizedObject()
    private val authorityFlows = mutableMapOf<String, MutableStateFlow<RealtimeScopeAuthority?>>()
    private val activeRegistrationByScope = mutableMapOf<String, String>()
    private var initialized = false
    private var lastObservedAuthEventGeneration = 0L
    private var currentSession: StoredAuthSession? = null
    private var lifecycleJob: Job? = null

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
            publishLocked()
            lease
        }

    suspend fun releaseScope(
        scopeKey: String,
        registrationId: String = scopeKey,
    ): Boolean =
        lifecycleMutex.withLock {
            ensureInitializedLocked()
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
            publishLocked()
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
     * Seam-only T3 entry. No production caller uses it yet: the RED slice first binds the frozen schedules,
     * then GREEN may route guarded canonical messages through this existing registry linearization point.
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
            lastObservedAuthEventGeneration = event.generation
            currentSession = authStore.readSession()
            registry.advanceAuthFromCurrentSession(
                session = currentSession.toRealtimeAuthoritySession(),
                force = true,
            )
            publishLocked()
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

    private fun publishLocked() {
        val snapshot = registry.snapshot.value
        val session = currentSession
        val sessionIdentity = session.toRealtimeAuthoritySession()
        val credentialsMatch = session != null && sessionIdentity == snapshot.authRevision.session
        val activeByKey = snapshot.activeLeases.values.associateBy { it.scopeKey }
        val knownKeys = synchronized(authorityFlowsLock) { authorityFlows.keys.toSet() } + activeByKey.keys
        knownKeys.forEach { scopeKey ->
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
            flowFor(scopeKey).value =
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
    }

    private fun flowFor(scopeKey: String): MutableStateFlow<RealtimeScopeAuthority?> =
        synchronized(authorityFlowsLock) {
            authorityFlows.getOrPut(scopeKey) { MutableStateFlow(null) }
        }
}

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
