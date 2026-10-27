package build.raft.app.data

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalFinalPreparedCommitStep
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PreparedCurrentGrantMessageCommitDisposition
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealtimeCanonicalFinalOwnerGatewayTest {
    @Test
    fun nullGatewaySelectedRunsLegacyWithoutAnyCommit() = runBlocking {
        var checks = 0
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(configured = false, commitStep = finalizableStep { checks += 1 })
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            assertTrue(fixture.owner.accept(fixture.grant(), canonicalEvent(), "null-selected"))
            assertEquals(listOf(0, 1, 0), listOf(checks, legacy, fixture.sidecar.debugSnapshot().reserveCount))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun nullGatewayBackgroundRunsLegacyWithoutAnyCommit() = runBlocking {
        var checks = 0
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(configured = false, commitStep = finalizableStep { checks += 1 })
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeUnreadProjection = { _, _ -> legacy += 1 }),
            )
            assertTrue(fixture.owner.acceptHomeUnreadProjection(fixture.grant(), canonicalEvent(), "null-background"))
            assertEquals(listOf(0, 1, 0), listOf(checks, legacy, fixture.sidecar.debugSnapshot().reserveCount))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun configuredSelectedRunsFourFlagRoutesAndUnresolved() = runBlocking {
        data class Actual(val accepted: Boolean, val checks: Int, val legacy: Int, val final: Int, val reserves: Int)
        val actual = mutableListOf<Actual>()
        listOf(false to false, false to true, true to false, true to true, null).forEach { flags ->
            var checks = 0
            var legacy = 0
            var final = 0
            val snapshot =
                if (flags == null) ServerFeatureFlagSnapshot.Unresolved else snapshot(flags.first, flags.second)
            val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot, commitStep = finalizableStep { checks += 1 })
            try {
                fixture.owner.registerGuardedSinks(
                    RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
                )
                fixture.effects.mount(
                    fixture.grant().scopeLeaseId,
                    "server-a",
                    RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
                ) { final += 1 }
                val accepted = fixture.acceptSelected(canonicalEvent(), "matrix-selected")
                actual += Actual(accepted, checks, legacy, final, fixture.sidecar.debugSnapshot().reserveCount)
            } finally {
                fixture.close()
            }
        }
        assertEquals(
            listOf(
                Actual(true, 0, 1, 0, 0),
                Actual(true, 0, 1, 0, 0),
                Actual(true, 0, 1, 0, 0),
                Actual(true, 1, 0, 1, 1),
                Actual(true, 0, 1, 0, 0),
            ),
            actual,
        )
    }

    @Test
    fun configuredBackgroundCapturesUnreadOnlyAndNextEventSeesFlagFlip() = runBlocking {
        var selectedFinal = 0
        var unreadFinal = 0
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep())
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeUnreadProjection = { _, _ -> legacy += 1 }),
            )
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { selectedFinal += 1 }
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread,
            ) { unreadFinal += 1 }
            assertTrue(fixture.acceptBackground(canonicalEvent("m1"), "background-on"))
            fixture.snapshot = snapshot(true, false)
            assertTrue(fixture.owner.acceptHomeUnreadProjection(fixture.grant(), canonicalEvent("m2"), "background-off"))
            assertEquals(listOf(0, 1, 1, 1), listOf(selectedFinal, unreadFinal, legacy, fixture.sidecar.debugSnapshot().reserveCount))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparedWithoutHandlesFinalizableReturnsTrueWithoutRouter() = runBlocking {
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep())
        try {
            assertTrue(fixture.owner.accept(fixture.grant(), canonicalEvent(), "zero-handles"))
            assertEquals(0, fixture.integration.routerConstructionCount())
            assertEquals(0, fixture.sidecar.debugSnapshot().reserveCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparedLegacyFallbackRunsExactLegacyOnce() = runBlocking {
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = prewriteStep())
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            assertTrue(fixture.owner.accept(fixture.grant(), canonicalEvent(), "fallback"))
            assertEquals(1, legacy)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparedIndeterminateReturnsFalseWithoutLegacy() = runBlocking {
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = indeterminateStep())
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            assertFalse(fixture.owner.accept(fixture.grant(), canonicalEvent(), "indeterminate"))
            assertEquals(0, legacy)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preparedCancellationPropagatesWithoutLegacy() = runBlocking {
        val cancellation = CancellationException("prepared-cancel")
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = cancellationStep(cancellation))
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            val caught = runCatching { fixture.owner.accept(fixture.grant(), canonicalEvent(), "cancel") }.exceptionOrNull()
            assertSame(cancellation, caught)
            assertEquals(0, legacy)
        } finally {
            fixture.close()
        }

        val ordinary = IllegalStateException("legacy-throw")
        val ordinaryFixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = prewriteStep())
        try {
            ordinaryFixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> throw ordinary }),
            )
            val caught = runCatching {
                ordinaryFixture.owner.accept(ordinaryFixture.grant(), canonicalEvent(), "ordinary-throw")
            }.exceptionOrNull()
            assertSame(ordinary, caught)
        } finally {
            ordinaryFixture.close()
        }
    }

    @Test
    fun reserveWaitThenGrantStaleRunsCommitFinalAndLegacyW0() = runBlocking {
        var final = 0
        var legacy = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep(), capacity = 1)
        try {
            fixture.owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> legacy += 1 }),
            )
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { final += 1 }
            val blocker = fixture.sidecar.reserve(fixture.grant())
            val pending = async { fixture.owner.accept(fixture.grant(), canonicalEvent(), "reserve-stale") }
            yield()
            assertFalse(pending.isCompleted)
            fixture.rotateSamePrincipal()
            fixture.sidecar.abandonReserved(blocker)
            assertFalse(pending.await())
            assertEquals(listOf(0, 0), listOf(final, legacy))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun postCommitRotateKeepsDurableButReturnsFalseBeforeFinal() = runBlocking {
        var final = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep())
        try {
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { final += 1 }
            val oldGrant = fixture.grant()
            val pending = async { fixture.owner.accept(oldGrant, canonicalEvent(), "post-commit-rotate") }
            while (fixture.sidecar.debugSnapshot().publishCount < 1) yield()
            fixture.rotateSamePrincipal()
            fixture.dispatcher.drain()
            assertFalse(pending.await())
            assertEquals(listOf(1, 0), listOf(fixture.sidecar.debugSnapshot().publishCount, final))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun callerCancellationAfterPublishDoesNotCancelSharedItem() = runBlocking {
        val final = mutableListOf<String>()
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep())
        try {
            fixture.effects.mount(
                fixture.grant().scopeLeaseId,
                "server-a",
                RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            ) { event -> final += (event as MessageNew).message.id.value }
            val pending = async { fixture.owner.accept(fixture.grant(), canonicalEvent("m1"), "caller-cancel") }
            while (fixture.sidecar.debugSnapshot().publishCount < 1) yield()
            pending.cancel(CancellationException("caller"))
            val successor = async { fixture.owner.accept(fixture.grant(), canonicalEvent("m2"), "successor") }
            while (fixture.sidecar.debugSnapshot().publishCount < 2) yield()
            fixture.dispatcher.drain()
            assertTrue(successor.await())
            assertEquals(listOf("m1", "m2"), final)
            assertEquals(0, fixture.sidecar.debugSnapshot().outstandingReservations)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sameRawReplayRepairsOnlyReplacementInTypedRankWithoutInternalRetry() = runBlocking {
        val order = mutableListOf<String>()
        var failed = 0
        val fixture = CanonicalFinalGatewayFixture(initialSnapshot = snapshot(true, true), commitStep = finalizableStep())
        try {
            val grant = fixture.grant()
            fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {
                failed += 1
                order += "domain-fail"
                error("surface")
            }
            fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("b")) { order += "feature-b" }
            fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("a")) { order += "core-a" }
            assertTrue(fixture.acceptSelected(canonicalEvent(), "first"))
            assertEquals(1, failed)
            fixture.effects.mount(grant.scopeLeaseId, grant.serverId, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain) {
                order += "domain-replacement"
            }
            assertTrue(fixture.acceptSelected(canonicalEvent(), "replay"))
            assertEquals(
                listOf("core-a", "feature-b", "domain-fail", "domain-replacement"),
                order,
            )
            assertEquals(0, fixture.sidecar.debugSnapshot().coreCursorWriteCount)
        } finally {
            fixture.close()
        }
    }
}

internal class CanonicalFinalGatewayFixture(
    configured: Boolean = true,
    initialSnapshot: ServerFeatureFlagSnapshot = snapshot(true, true),
    commitStep: CanonicalFinalPreparedCommitStep = finalizableStep(),
    capacity: Int = 4,
) {
    var snapshot: ServerFeatureFlagSnapshot = initialSnapshot
    val auth = FakeCanonicalFinalAuthStore()
    private val registry =
        RealtimeAuthorityRegistry(
            authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
            processInstanceId = "gateway-fixture-${nextFixtureId++}",
        )
    val dispatcher = ManualCanonicalFinalDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val directory = RealtimeCanonicalFinalSinkRegistrationDirectory()
    val sidecar =
        RealtimeCanonicalFinalSinkSidecar(
            scope = scope,
            capacity = capacity,
            runIfCurrentGrant = registry::runIfCurrentGrant,
            registrationDirectory = directory,
            workerDispatcherForGrant = { dispatcher },
        )
    private val router =
        RealtimeCanonicalFinalSinkLazyRouter(
            runIfCurrentGrant = registry::runIfCurrentGrant,
            sidecarFactory = { sidecar },
        )
    val integration =
        RealtimeCanonicalFinalSinkIntegrationSeam(
            snapshotForServer = { snapshot },
            registrationDirectory = directory,
            t4FlagKey = T4_KEY,
            lazyRouterFactory = { router },
        )
    val provider =
        createSharedRealtimeAuthorityProvider(
            onAuthRevisionAdvanced = {},
            registry = registry,
            authStore = auth,
            authEvents = auth,
            currentEnvironmentId = { "env-test" },
            canonicalFinalIntegration = integration.takeIf { configured },
        )
    val owner =
        createSharedRealtimeDomainEventOwner(
            tasksDataStore = null,
            cursorStore = null,
            authorityProvider = provider,
        ).also { owner -> owner.installCanonicalFinalCommitStep(commitStep) }
    val effects = RealtimeCanonicalFinalSinkLifecycleEffects(provider)

    init {
        runBlocking {
            provider.start(scope)
            provider.registerScope("shell-a", "mount-a")
            auth.replace(session(), AuthSessionEvent.Authenticated(1))
        }
    }

    fun grant(): RealtimeGrantRecord =
        assertNotNull(provider.authorityFlow("shell-a").value?.grant("server-a"))

    suspend fun acceptSelected(event: MessageNew, source: String): Boolean =
        coroutineScope {
            val pending = async { owner.accept(grant(), event, source) }
            yield()
            dispatcher.drain()
            pending.await()
        }

    suspend fun acceptBackground(event: MessageNew, source: String): Boolean =
        coroutineScope {
            val pending = async { owner.acceptHomeUnreadProjection(grant(), event, source) }
            yield()
            dispatcher.drain()
            pending.await()
        }

    suspend fun rotateSamePrincipal() {
        auth.replace(session(accessToken = "access-2"), AuthSessionEvent.Authenticated(2))
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        var nextFixtureId = 0
    }
}

internal class FakeCanonicalFinalAuthStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
    private var session: StoredAuthSession? = null
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
    override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()
    override suspend fun readSession(): StoredAuthSession? = session
    override suspend fun saveSession(session: StoredAuthSession) { this.session = session }
    override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit
    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = Unit
    override suspend fun clearSession(reason: String, source: String) { session = null }
    suspend fun replace(next: StoredAuthSession, event: AuthSessionEvent) {
        session = next
        events.emit(event)
    }
}

internal class ManualCanonicalFinalDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}

internal const val T4_KEY = "canonical_final_sink_test_v0"

internal fun snapshot(t3: Boolean, t4: Boolean) =
    ServerFeatureFlagSnapshot(
        serverId = ServerId("server-a"),
        resolved = true,
        values = mapOf(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY to t3, T4_KEY to t4),
    )

/** T4 commit-step fakes: each answers one prepared disposition (the V1 lane that produced them is gone). */
internal fun finalizableStep(onCommit: () -> Unit = {}) =
    CanonicalFinalPreparedCommitStep { _, _, _ ->
        onCommit()
        PreparedCurrentGrantMessageCommitDisposition.FinalizableDurableNoChange
    }

internal fun prewriteStep() =
    CanonicalFinalPreparedCommitStep { _, _, _ ->
        PreparedCurrentGrantMessageCommitDisposition.LegacyFallback.DefinitelyPrewrite("IllegalStateException")
    }

internal fun indeterminateStep() =
    CanonicalFinalPreparedCommitStep { _, _, _ ->
        PreparedCurrentGrantMessageCommitDisposition.Indeterminate.CommitOutcomeUnknown("IllegalStateException")
    }

internal fun cancellationStep(cancellation: CancellationException) =
    CanonicalFinalPreparedCommitStep { _, _, _ -> throw cancellation }

internal fun canonicalInput(id: String = "m1") =
    CanonicalMessageFoldInput.from(
        CanonicalMessageFoldKind.New,
        CanonicalMessageCarry(
            row =
                CanonicalMessageRow(
                    id = id,
                    channelId = "channel-a",
                    seq = 1,
                    senderType = "human",
                    senderId = "member-a",
                    content = "body",
                    messageType = "chat",
                    createdAt = "2026-07-15T00:00:00Z",
                    senderName = "Member A",
                ),
            presentAggregates = emptySet(),
        ),
    )

internal fun canonicalEvent(id: String = "m1"): MessageNew {
    val row = canonicalInput(id).row
    return MessageNew(
        message =
            Message(
                id = MessageId(row.id),
                threadId = ThreadId(row.channelId),
                channelId = ChannelId(row.channelId),
                seq = row.seq,
                author = MemberProfile(MemberId(row.senderId), "Member A", "member-a", MemberKind.Human),
                timestampLabel = "now",
                body = row.content,
            ),
        decodedCanonical = CanonicalMessageCarry(row, emptySet()),
    )
}

internal fun session(accessToken: String = "access") =
    StoredAuthSession(
        user = StoredAuthUser("principal-a", "principal@example.test", "Principal"),
        accessToken = accessToken,
        refreshToken = "refresh",
        selectedServerId = "server-a",
        servers = listOf(StoredAuthServer("server-a", "Server A", "server-a")),
    )

internal fun partition(grant: RealtimeGrantRecord) =
    DurablePartitionKey(grant.serverId, grant.principalId, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, grant.environmentId)
