package build.raft.app.data

import build.raft.app.applySelectedServerTerminalRealtimeTransition
import build.raft.app.realtimeConnectionWitnessOrNull
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeLiveCutoverGateTest {

    @Test
    fun freshWitnessLooksUpExistingCrossServerGrantWhileMissingServerMintsNothing() = runBlocking {
        val fixture = fixture("fresh-cross-server")
        fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val authority = assertNotNull(fixture.provider.authorityFlow("shell").value)
        val beforeMissing = fixture.registry.snapshot.value

        assertTrue(
            fixture.callbackGate.acceptDomain(
                witness = authority.witness,
                eventServerId = "server-b",
                event = DirectMessageCreated(ChannelId("dm-b")),
                source = "test",
            )
        )
        assertEquals(listOf("server-b"), fixture.domainWrites)

        assertFalse(
            fixture.callbackGate.acceptDomain(
                witness = authority.witness,
                eventServerId = "missing",
                event = DirectMessageCreated(ChannelId("dm-missing")),
                source = "test",
            )
        )
        assertEquals(beforeMissing, fixture.registry.snapshot.value, "resolver is lookup-only and must mint nothing")
        fixture.close()
    }

    @Test
    fun oldFamilyAndReleasedLeaseCallbacksWriteNothing() = runBlocking {
        val fixture = fixture("stale-family")
        fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val first = assertNotNull(fixture.provider.authorityFlow("shell").value)

        fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
        val second = assertNotNull(fixture.provider.authorityFlow("shell").value)
        assertNotEquals(first.witness.connectionFamilyId, second.witness.connectionFamilyId)

        assertFalse(
            fixture.callbackGate.acceptDomain(
                witness = first.witness,
                eventServerId = "server-a",
                event = DirectMessageCreated(ChannelId("old-family")),
                source = "test",
            )
        )
        assertEquals(emptyList(), fixture.domainWrites)

        assertTrue(fixture.provider.releaseScope("shell", registrationId = "mount"))
        assertNull(fixture.provider.authorityFlow("shell").value)
        assertFalse(
            fixture.callbackGate.acceptDomain(
                witness = second.witness,
                eventServerId = "server-a",
                event = DirectMessageCreated(ChannelId("released")),
                source = "test",
            )
        )
        assertEquals(emptyList(), fixture.domainWrites)
        fixture.close()
    }

    @Test
    fun grantThatBecomesStaleAfterResolveIsRejectedAtFinalApplyGate() = runBlocking {
        val fixture = fixture("resolve-then-rotate")
        fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val first = assertNotNull(fixture.provider.authorityFlow("shell").value)
        val resolved = assertNotNull(fixture.callbackGate.resolveExistingGrant(first.witness, "server-a"))

        fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
        assertFalse(
            fixture.owner.accept(
                grant = resolved,
                event = DirectMessageCreated(ChannelId("resolved-before-rotate")),
                source = "test",
            )
        )
        assertEquals(emptyList(), fixture.domainWrites)
        fixture.close()
    }

    @Test
    fun rawCallbackEnvelopeKeepsTheConnectionWitnessThatProducedTheFrame() = runBlocking {
        val fixture = fixture("raw-envelope-family")
        fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val first = assertNotNull(fixture.provider.authorityFlow("shell").value)
        val rawEnvelope =
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, first.witness.scopeLeaseId)
                .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, first.witness.principalId)
                .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, first.witness.authGeneration)
                .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, first.witness.connectionFamilyId)

        fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
        val parsedWitness = assertNotNull(rawEnvelope.realtimeConnectionWitnessOrNull())
        assertEquals(first.witness, parsedWitness)
        assertFalse(
            fixture.callbackGate.acceptDomain(
                witness = parsedWitness,
                eventServerId = "server-a",
                event = DirectMessageCreated(ChannelId("delayed-old-socket-frame")),
                source = "test",
            )
        )
        assertEquals(emptyList(), fixture.domainWrites)
        fixture.close()
    }

    @Test
    fun authTransitionCannotPassFinalSinkWhileCurrentGrantApplyIsInFlight() = runBlocking {
        val fixture = fixture("final-write-linearization", installDefaultSink = false)
        fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val authority = assertNotNull(fixture.provider.authorityFlow("shell").value)
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        fixture.owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { serverId, _ ->
                    sinkEntered.complete(Unit)
                    releaseSink.await()
                    fixture.domainWrites += serverId
                }
            )
        )

        val applyJob =
            launch {
                assertTrue(
                    fixture.callbackGate.acceptDomain(
                        witness = authority.witness,
                        eventServerId = "server-a",
                        event =
                            RealtimeRefreshRequired(
                                scope = RealtimeRefreshScope.Home,
                                reason = "linearize",
                                serverId = ServerId("server-a"),
                            ),
                        source = "test",
                    )
                )
            }
        sinkEntered.await()
        val transitionFinished = CompletableDeferred<Unit>()
        val transitionJob =
            launch {
                fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
                fixture.provider.authorityFlow("shell").first { next ->
                    next != null &&
                        next.witness.connectionFamilyId != authority.witness.connectionFamilyId
                }
                transitionFinished.complete(Unit)
            }
        yield()
        assertFalse(transitionFinished.isCompleted, "auth transition shares the final-write linearization point")

        releaseSink.complete(Unit)
        applyJob.join()
        transitionJob.join()
        assertEquals(listOf("server-a"), fixture.domainWrites)
        fixture.close()
    }

    @Test
    fun membershipRemovedAndServerDeletedTerminalWritesCannotEscapeTheGrantGate() = runBlocking {
        listOf("server_membership_removed", "server_deleted").forEach { terminalReason ->
            val fixture = fixture("terminal-$terminalReason", installDefaultSink = false)
            try {
                fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
                val authority = assertNotNull(fixture.provider.authorityFlow("shell").value)
                val grant = assertNotNull(fixture.callbackGate.resolveExistingGrant(authority.witness, "server-a"))
                var selectedServerId: String? = "server-a"
                val clearEntered = CompletableDeferred<Unit>()
                val releaseClear = CompletableDeferred<Unit>()
                var restoreCount = 0

                val terminalJob =
                    launch {
                        assertTrue(
                            applySelectedServerTerminalRealtimeTransition(
                                grant = grant,
                                eventServerId = "server-a",
                                isSelected = { selectedServerId == it },
                                runIfCurrentGrant = fixture.provider::runIfCurrentGrant,
                                disconnectAndReset = {},
                                clearSelectedServer = {
                                    clearEntered.complete(Unit)
                                    releaseClear.await()
                                    selectedServerId = null
                                },
                                restoreAuthGate = { restoreCount += 1 },
                            )
                        )
                    }
                clearEntered.await()

                val transitionFinished = CompletableDeferred<Unit>()
                val transitionJob =
                    launch {
                        fixture.auth.replace(
                            session().copy(selectedServerId = "server-b"),
                            AuthSessionEvent.TokensRotated(generation = 2),
                        )
                        fixture.provider.authorityFlow("shell").first { next ->
                            next != null &&
                                next.witness.connectionFamilyId != authority.witness.connectionFamilyId
                        }
                        selectedServerId = "server-b"
                        transitionFinished.complete(Unit)
                    }

                val transitionCrossedBeforeTerminalWrite =
                    try {
                        withTimeoutOrNull(100L) {
                            transitionFinished.await()
                            true
                        } ?: false
                    } finally {
                        releaseClear.complete(Unit)
                    }
                terminalJob.join()
                transitionJob.join()

                assertFalse(
                    transitionCrossedBeforeTerminalWrite,
                    "$terminalReason must hold the registry linearization point through clear+restore",
                )
                assertEquals("server-b", selectedServerId)
                assertEquals(1, restoreCount)
            } finally {
                fixture.close()
            }
        }
    }

    private suspend fun fixture(
        process: String,
        installDefaultSink: Boolean = true,
    ): Fixture {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
                processInstanceId = process,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(scope)
        provider.registerScope("shell", registrationId = "mount")
        val writes = mutableListOf<String>()
        val owner =
            SharedRealtimeDomainEventOwner(
                runIfCurrentGrant = provider::runIfCurrentGrant,
            )
        if (installDefaultSink) {
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { serverId, _ -> writes += serverId }
                )
            )
        }
        return Fixture(
            auth = auth,
            registry = registry,
            provider = provider,
            owner = owner,
            callbackGate = RealtimeShellCallbackGate(provider, owner),
            domainWrites = writes,
            scope = scope,
        )
    }

    private data class Fixture(
        val auth: FakeTypedAuthSessionStore,
        val registry: RealtimeAuthorityRegistry,
        val provider: RealtimeAuthorityProvider,
        val owner: SharedRealtimeDomainEventOwner,
        val callbackGate: RealtimeShellCallbackGate,
        val domainWrites: MutableList<String>,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private class FakeTypedAuthSessionStore : SlockAuthSessionStore, SlockAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun readSession(): StoredAuthSession? = session

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

    private fun session(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "A", slug = "a"),
                    StoredAuthServer(id = "server-b", name = "B", slug = "b"),
                ),
        )
}
