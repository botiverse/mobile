package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * task #136 post-merge audit round 3 (PR #1075): presence-binding READINESS.
 *
 * The binding transition runs as the REAL RealtimeAuthorityProvider's
 * constructor-injected onAuthRevisionAdvanced hook — inside the lifecycle
 * mutex, after the revision advances, BEFORE grants/authority flows publish.
 * These barriers drive the real provider (typed auth events), the real
 * authority registry, the real SharedRealtimeDomainEventOwner and the
 * PRODUCTION processPresenceBindingTransition + processMemberPresenceRealtimeSink,
 * and pin the invariant: a deliverable grant always finds its binding ready —
 * `not_ready` drops are an invariant violation and must be ZERO on startup,
 * login and token-rotation transitions.
 */
class ProcessPresenceBindingReadinessTest {

    @Test
    fun samePrincipalAuthRotationRetiresOnlyThePreviousBindingGeneration() {
        val context = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        val registry = ProcessMemberPresenceRegistry()
        val retired = mutableListOf<ProcessPresenceAuthContext>()
        val bound = mutableListOf<ProcessPresenceAuthContext>()
        val transition =
            processPresenceBindingTransition(
                presenceAuthContext = context,
                registry = registry,
                onBindingRetired = { retired += it },
                onBindingBound = {
                    // The callback runs after the current immutable binding is
                    // published, so a durable stale-row cleanup can preserve
                    // exactly this generation.
                    assertEquals(it, context.value)
                    bound += it
                },
                currentEnvironmentId = { "env-1" },
            )

        transition(
            RealtimeAuthRevision(
                generation = 1L,
                session = RealtimeAuthoritySession(principalId = "user-1", serverIds = listOf("server-1"), environmentId = "env-test"),
            )
        )
        transition(
            RealtimeAuthRevision(
                generation = 2L,
                session = RealtimeAuthoritySession(principalId = "user-1", serverIds = listOf("server-1"), environmentId = "env-test"),
            )
        )

        assertEquals(listOf(1L), retired.map { it.authorityGeneration })
        assertEquals(listOf(1L, 2L), bound.map { it.authorityGeneration })
        assertEquals(2L, context.value?.authorityGeneration)
    }

    private class ReadinessHarness(
        throwingDiagnosticsLogger: Boolean = false,
        invalidationOverride: ((Set<RealtimeGrantRecord>) -> Unit)? = null,
    ) {
        val authorityRegistry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "readiness-process",
            )
        val auth = FakeTypedAuthSessionStore()
        val presenceRegistry = ProcessMemberPresenceRegistry()
        val presenceAuthContext = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        val drops = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()

        // Failure injection for the transactional fail-closed barriers: each
        // armed failure makes the next hook invocation throw BEFORE the
        // production transition runs (registry/currentSession are already
        // advanced by then — the exact hazard shape).
        var armedHookFailures = 0
        // D.19 injection: the environment reader inside the PRODUCTION
        // transition throws before any mutation.
        var armedEnvironmentFailures = 0
        val hookInvocationGenerations = mutableListOf<Long>()

        private val productionTransition =
            processPresenceBindingTransition(
                presenceAuthContext = presenceAuthContext,
                registry = presenceRegistry,
            ) {
                if (armedEnvironmentFailures > 0) {
                    armedEnvironmentFailures -= 1
                    throw IllegalStateException("injected environment read failure")
                }
                "env-1"
            }

        // Real provider with the PRODUCTION binding transition as its hook —
        // constructor-injected before start/registerScope, exactly like the
        // shared store provider wires it.
        val provider =
            RealtimeAuthorityProvider(
                registry = authorityRegistry,
                authStore = auth,
                authEvents = auth,
                currentEnvironmentId = { "env-test" },
                diagnosticsLogger =
                    if (throwingDiagnosticsLogger) {
                        { message ->
                            diagnostics += message
                            throw IllegalStateException("injected logger failure")
                        }
                    } else {
                        { message -> diagnostics += message }
                    },
                canonicalFinalInvalidationOverride = invalidationOverride,
                onAuthRevisionAdvanced = { revision ->
                    hookInvocationGenerations += revision.generation
                    if (armedHookFailures > 0) {
                        armedHookFailures -= 1
                        throw IllegalStateException("injected binding hook failure")
                    }
                    productionTransition(revision)
                },
            )

        val owner =
            SharedRealtimeDomainEventOwner(
                runIfCurrentGrant = authorityRegistry::runIfCurrentGrant,
            ).also {
                it.installMemberPresenceSink(
                    processMemberPresenceRealtimeSink(
                        currentPresenceAuthContext = { presenceAuthContext.value },
                        registry = presenceRegistry,
                        diagnosticsLogger = drops::add,
                    )
                )
            }

        fun notReadyDrops(): List<String> = drops.filter { it.contains("reason=not_ready") }
    }

    private fun presenceEvent(seq: Long, presence: Presence = Presence.Active) =
        MemberPresenceUpdated(
            memberId = MemberId("agent-1"),
            presence = presence,
            kind = MemberKind.Agent,
            serverSeq = seq
        )

    private fun boundOwner(userId: String) =
        ProcessServerMembersOwner("env-1", userId, "server-a")

    @Test
    fun newSessionFirstObtainableGrantFindsTheBindingReady() = runBlocking {
        // The round-3 loss window: authority made N+1 grants deliverable while
        // the old async collector had not yet published the N+1 binding — a
        // genuine new event passed runIfCurrentGrant and was dropped with no
        // replay. With the hook, the binding is ready before the grant is
        // obtainable: deliver at the FIRST possible moment and the event must
        // apply exactly once — not disappear, not land in an old binding.
        val harness = ReadinessHarness()
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")
        assertNull(harness.provider.authorityFlow("shell").value)

        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))

        val authority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val grant = assertNotNull(authority.grant("server-a"))
        assertEquals(true, harness.owner.accept(grant, presenceEvent(seq = 1L), source = "test"))

        assertEquals(1L, harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq)
        assertEquals(1, harness.presenceRegistry.factsByOwner.size)
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    @Test
    fun initialSessionGrantsNeverOutrunTheBinding() = runBlocking {
        // Cold start already signed in: ensureInitialized advances the
        // revision from the current session and runs the hook BEFORE the
        // provider publishes its first grants — the very first obtainable
        // grant delivers presence with zero loss.
        val harness = ReadinessHarness()
        harness.auth.saveSession(session(token = "token-1"))
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")

        val authority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val grant = assertNotNull(authority.grant("server-a"))
        assertEquals(true, harness.owner.accept(grant, presenceEvent(seq = 1L), source = "test"))

        assertEquals(1L, harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq)
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    @Test
    fun tokenRotationKeepsFactsWithoutAnEmptyInterludeAndOnlyNewGenerationWrites() = runBlocking {
        // Same (environment, principal) rotation: partitions and the registry
        // write generation are PRESERVED — facts never blink empty — while the
        // binding's authorityGeneration moves, so the retired session's grant
        // is rejected and the new session's grant writes immediately.
        val harness = ReadinessHarness()
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val grantBeforeRotation =
            assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, harness.owner.accept(grantBeforeRotation, presenceEvent(seq = 1L), source = "test"))
        val writeGenerationBeforeRotation = harness.presenceRegistry.writeGeneration

        val factsEmissions = mutableListOf<Map<String, MemberPresenceUpdated>>()
        val collectJob = launch {
            harness.presenceRegistry.factsFlow(boundOwner("user-1")).collect { factsEmissions += it }
        }
        var spins = 0
        while (factsEmissions.isEmpty() && spins < 10_000) {
            yield()
            spins += 1
        }
        assertEquals(1, factsEmissions.size)

        harness.auth.replace(session(token = "token-2"), AuthSessionEvent.TokensRotated(generation = 2))
        repeat(100) { yield() }

        // No empty-facts interlude; registry generation untouched; fact kept.
        assertEquals(true, factsEmissions.none { it.isEmpty() })
        assertEquals(writeGenerationBeforeRotation, harness.presenceRegistry.writeGeneration)
        assertEquals(1L, harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq)

        // The retired session's grant no longer delivers…
        assertEquals(false, harness.owner.accept(grantBeforeRotation, presenceEvent(seq = 9L), source = "test"))
        assertEquals(1L, harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq)

        // …and the rotated session's grant writes under the SAME preserved
        // write generation, at the first moment it is obtainable.
        val grantAfterRotation =
            assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, grantAfterRotation != grantBeforeRotation)
        assertEquals(true, harness.owner.accept(grantAfterRotation, presenceEvent(seq = 2L), source = "test"))
        assertEquals(2L, harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq)

        assertEquals(emptyList(), harness.notReadyDrops())
        collectJob.cancelAndJoin()
        lifecycleScope.cancel()
    }

    @Test
    fun signOutClearsTheBindingBeforeAnyNewGrantExists() = runBlocking {
        val harness = ReadinessHarness()
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val grant =
            assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, harness.owner.accept(grant, presenceEvent(seq = 1L), source = "test"))

        harness.auth.replace(
            null,
            AuthSessionEvent.Cleared(generation = 2, reason = AuthSessionClearReason.Logout)
        )

        assertNull(harness.presenceAuthContext.value)
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
        // The retired grant is rejected upstream; nothing resurrects.
        assertEquals(false, harness.owner.accept(grant, presenceEvent(seq = 9L), source = "test"))
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    @Test
    fun hookFailureOnAuthenticatedTransitionBlocksEveryPublicationUntilRepair() = runBlocking {
        // Round-4 blocker: a hook throw on the auth-event path used to escape
        // the collector (killing it) while the registry was already advanced,
        // and a later registerScope could publish N+1 grants over a stale
        // binding. Now the throw is caught, the collector survives, and a
        // pending binding blocks every publication path until a retry repairs
        // it FIRST.
        val harness = ReadinessHarness()
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val grantBeforeFailure =
            assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, harness.owner.accept(grantBeforeFailure, presenceEvent(seq = 1L), source = "test"))
        val bindingBeforeFailure = assertNotNull(harness.presenceAuthContext.value)

        // Same-account re-auth whose binding hook fails AFTER the registry
        // has advanced.
        harness.armedHookFailures = 1
        harness.auth.replace(session(token = "token-2"), AuthSessionEvent.Authenticated(generation = 2))

        // Fail-closed, transactionally: binding unchanged, the stale flow's
        // grant is dead (registry advanced), and no N+1 grant is obtainable —
        // the flow was not republished and resolve() is gated.
        assertEquals(bindingBeforeFailure, harness.presenceAuthContext.value)
        val staleAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val staleGrant = assertNotNull(staleAuthority.grant("server-a"))
        assertEquals(false, harness.owner.accept(staleGrant, presenceEvent(seq = 9L), source = "test"))
        assertNull(harness.provider.resolve(staleAuthority.witness, "server-a"))
        assertEquals(
            1L,
            harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq
        )

        // A later mutation (registerScope) repairs the binding FIRST, then
        // publishes: the first obtainable N+1 grant writes exactly once.
        harness.provider.registerScope("shell-b")
        val repairedBinding = assertNotNull(harness.presenceAuthContext.value)
        assertEquals(2L, repairedBinding.authorityGeneration)
        val repairedAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val repairedGrant = assertNotNull(repairedAuthority.grant("server-a"))
        assertEquals(2L, repairedGrant.authGeneration)
        assertEquals(true, harness.owner.accept(repairedGrant, presenceEvent(seq = 2L), source = "test"))
        assertEquals(
            2L,
            harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq
        )

        // Deterministic collector survival — no restart, the NEXT auth event
        // processes normally end to end.
        harness.auth.replace(session(token = "token-3"), AuthSessionEvent.Authenticated(generation = 3))
        val nextAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val nextGrant = assertNotNull(nextAuthority.grant("server-a"))
        assertEquals(3L, nextGrant.authGeneration)
        assertEquals(true, harness.owner.accept(nextGrant, presenceEvent(seq = 3L), source = "test"))
        assertEquals(
            3L,
            harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq
        )
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    // ---- Contract v1 D-section failure matrix (round 7) ----

    @Test
    fun clearedEventSessionReadFailureImmediatelyKillsOldGrants() = runBlocking {
        // D.17: a Cleared event whose session read throws is a consumed wake
        // signal — without pendingAuthReconciliation the logged-out session's
        // grants would stay alive indefinitely. The pending flag fail-closes
        // runIfCurrentGrant/resolve/publication instantly; a later ordinary
        // entry repairs from CURRENT state (signed out) with no event replay.
        val harness = ReadinessHarness()
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val staleAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val oldGrant = assertNotNull(staleAuthority.grant("server-a"))
        assertEquals(true, harness.owner.accept(oldGrant, presenceEvent(seq = 1L), source = "test"))

        // Sign-out whose transition body fails on the session read.
        harness.auth.armedReadSessionFailures = 1
        harness.auth.replace(
            null,
            AuthSessionEvent.Cleared(generation = 2, reason = AuthSessionClearReason.Logout)
        )

        // Old grant is dead IMMEDIATELY (provider gate, not registry rotation).
        assertEquals(false, harness.provider.runIfCurrentGrant(oldGrant) {})
        assertNull(harness.provider.resolve(staleAuthority.witness, "server-a"))

        // No later auth event: an ordinary entry repairs state-based.
        harness.provider.registerScope("shell-b")
        assertNull(harness.presenceAuthContext.value)
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
        assertNull(assertNotNull(harness.provider.authorityFlow("shell-b")).value)
        // Collector survives: a genuine re-login processes end to end.
        harness.auth.replace(session(token = "token-2"), AuthSessionEvent.Authenticated(generation = 3))
        val newAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val newGrant = assertNotNull(newAuthority.grant("server-a"))
        assertEquals(true, harness.owner.accept(newGrant, presenceEvent(seq = 2L), source = "test"))
        assertEquals(
            2L,
            harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq
        )
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    @Test
    fun loggerFailureNeverKillsTheCollectorOrClearsPending() = runBlocking {
        // D.17: every provider diagnostics call goes through a safe wrapper —
        // a throwing logger must not kill the collector, must not clear
        // pending state, and drops must still be classified.
        val harness = ReadinessHarness(throwingDiagnosticsLogger = true)
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(lifecycleScope)
        harness.provider.registerScope("shell")

        // Hook failure produces a diagnostics call from the throwing logger —
        // the pending binding must survive it.
        harness.armedHookFailures = 1
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        assertNull(harness.presenceAuthContext.value)
        assertNull(harness.provider.authorityFlow("shell").value)
        assertEquals(true, harness.diagnostics.any { it.contains("binding_hook_failed") })

        // Collector alive + repair works, with the logger still throwing.
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 2))
        val authority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val grant = assertNotNull(authority.grant("server-a"))
        assertEquals(true, harness.owner.accept(grant, presenceEvent(seq = 1L), source = "test"))
        assertEquals(
            1L,
            harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1")?.serverSeq
        )
        assertEquals(emptyList(), harness.notReadyDrops())
        lifecycleScope.cancel()
    }

    // ---- Final-review blockers (restart transaction + dead-mark lifecycle) ----

    @Test
    fun restartAfterStoppedWindowLogoutKillsOldGrantsStateBased() = runBlocking {
        // Terminal blocker 1: restart is a wake with no event payload — the
        // durable session may have changed while the collector was down and
        // those events are lost. Restart must rebuild from CURRENT durable
        // state: the old session's grants die immediately and nothing but the
        // current truth publishes.
        val harness = ReadinessHarness()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(firstScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val oldGrant = assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, harness.owner.accept(oldGrant, presenceEvent(seq = 1L), source = "test"))

        // Stop; logout lands while stopped (its event dies with the scope).
        firstScope.cancel()
        harness.auth.saveSession(session(token = "token-1")) // ensure non-null before clear
        harness.auth.clearSession(reason = "test_stopped_window", source = "test")

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(secondScope)

        // State-based restart: signed-out truth, old grant dead, binding null,
        // partitions cleared — with no event ever arriving.
        assertNull(harness.provider.authorityFlow("shell").value)
        assertEquals(false, harness.provider.runIfCurrentGrant(oldGrant) {})
        assertEquals(false, harness.owner.accept(oldGrant, presenceEvent(seq = 9L), source = "test"))
        assertNull(harness.presenceAuthContext.value)
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
        assertEquals(emptyList(), harness.notReadyDrops())
        secondScope.cancel()
    }

    @Test
    fun restartAfterStoppedWindowUserSwitchPublishesOnlyTheNewIdentity() = runBlocking {
        val harness = ReadinessHarness()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(firstScope)
        harness.provider.registerScope("shell")
        harness.auth.replace(session(token = "token-1"), AuthSessionEvent.Authenticated(generation = 1))
        val oldGrant = assertNotNull(assertNotNull(harness.provider.authorityFlow("shell").value).grant("server-a"))
        assertEquals(true, harness.owner.accept(oldGrant, presenceEvent(seq = 1L), source = "test"))

        firstScope.cancel()
        harness.auth.saveSession(session(token = "token-2", userId = "user-2"))

        val observed = mutableListOf<Long?>()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val watchJob = secondScope.launch {
            harness.provider.authorityFlow("shell").collect {
                observed += it?.grant("server-a")?.authGeneration
            }
        }
        harness.provider.start(secondScope)

        // The subscription's FIRST emission is the StateFlow's retained
        // pre-stop value (replay to a late collector, not a publication);
        // everything the restart itself publishes must carry ONLY the new
        // identity's generation — the stale one is never re-published.
        val newAuthority = assertNotNull(harness.provider.authorityFlow("shell").value)
        val newGrant = assertNotNull(newAuthority.grant("server-a"))
        assertEquals(true, newGrant.authGeneration > oldGrant.authGeneration)
        val publishedAfterRestart = observed.drop(1)
        assertEquals(true, publishedAfterRestart.isNotEmpty())
        assertEquals(true, publishedAfterRestart.filterNotNull().all { it == newGrant.authGeneration })
        assertEquals(false, harness.provider.runIfCurrentGrant(oldGrant) {})

        // Presence binding matches the restarted identity; writes land under
        // user-2 only, and user-1's facts are gone.
        assertEquals(true, harness.owner.accept(newGrant, presenceEvent(seq = 2L), source = "test"))
        assertEquals(
            2L,
            harness.presenceRegistry.factFor(
                ProcessServerMembersOwner("env-1", "user-2", "server-a"),
                "agent-1"
            )?.serverSeq
        )
        assertNull(harness.presenceRegistry.factFor(boundOwner("user-1"), "agent-1"))
        assertEquals(emptyList(), harness.notReadyDrops())
        watchJob.cancel()
        secondScope.cancel()
    }

    private class FakeTypedAuthSessionStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        var armedReadSessionFailures = 0

        override suspend fun readSession(): StoredAuthSession? {
            if (armedReadSessionFailures > 0) {
                armedReadSessionFailures -= 1
                throw IllegalStateException("injected session read failure")
            }
            return session
        }

        override suspend fun saveSession(session: StoredAuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession(reason: String, source: String) {
            session = null
        }

        suspend fun replace(next: StoredAuthSession?, event: AuthSessionEvent) {
            session = next
            events.emit(event)
        }
    }
}

private fun session(
    token: String,
    servers: List<String> = listOf("server-a"),
    userId: String = "user-1",
): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = userId, email = "user@example.test", displayName = "User"),
        accessToken = token,
        refreshToken = "refresh",
        selectedServerId = servers.firstOrNull(),
        servers = servers.map { id -> StoredAuthServer(id = id, name = id, slug = id) },
    )
