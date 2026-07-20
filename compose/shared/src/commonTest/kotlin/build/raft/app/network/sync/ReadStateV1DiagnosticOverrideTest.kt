package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReadStateV1DiagnosticOverrideTest {
    private var now: Long = 1_000L
    private val controller = ReadStateV1DiagnosticOverrideController { now }
    private val owner = ReadStateV1ProductionOwner("https://api.example", "account-a", "server-a")

    @Test
    fun `release entitlement fails closed`() {
        controller.observe(owner, "channel-a")

        val result = controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)

        assertIs<ReadStateV1DiagnosticOverrideMutationResult.Rejected>(result)
        assertEquals("read_state_diagnostic_override_not_entitled", result.code)
        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
    }

    @Test
    fun `force on binds exact observed scope and never enables another channel`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        assertIs<ReadStateV1DiagnosticOverrideMutationResult.Applied>(
            controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)
        )

        val exact = controller.decide(serverEffective = false, owner, "channel-a")
        val other = controller.decide(serverEffective = false, owner, "channel-b")

        assertTrue(exact.finalEffective)
        assertTrue(exact.exactScopeMatched)
        assertEquals("diagnostic_override_exact_scope", exact.precedence)
        assertFalse(other.finalEffective)
        assertFalse(other.exactScopeMatched)
        assertEquals("server_wrong_scope", other.precedence)
    }

    @Test
    fun `force off has highest precedence`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOff)

        val decision = controller.decide(serverEffective = true, owner, "channel-a")

        assertFalse(decision.finalEffective)
        assertEquals("diagnostic_override_force_off", decision.precedence)
    }

    @Test
    fun `ttl expiry returns to server mode`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope, ttlMillis = 10L)

        now += 10L

        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.Server, controller.snapshot().mode)
        assertEquals("expired", controller.snapshot().clearReason)
    }

    @Test
    fun `identity transition clears active override`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)

        val changedOwner = owner.copy(accountId = "account-b")
        val decision = controller.decide(false, changedOwner, "channel-a")

        assertFalse(decision.finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.Server, controller.snapshot().mode)
        assertNull(controller.snapshot().boundScope)
        assertEquals("identity_changed", controller.snapshot().clearReason)
    }

    @Test
    fun `server mode clears local override and preserves server effective`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)

        controller.setMode(ReadStateV1DiagnosticOverrideMode.Server)

        assertTrue(controller.decide(true, owner, "channel-a").finalEffective)
        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.Server, controller.snapshot().mode)
    }

    @Test
    fun `destination publication records actual store counts and witness generation`() {
        controller.recordSelection(owner, "channel-a", generation = 9L)

        controller.recordPublication(homeStoreCount = 0, activityStoreCount = 1, generation = 9L)

        assertEquals("home=0 activity=1 generation=9", controller.snapshot().destinationPublication)
    }

    @Test
    fun `force off revokes prepared publication and requests snapshot barrier`() = runBlocking {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)
        val barriers = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { candidateOwner, channelId ->
                    controller.decide(false, candidateOwner, channelId).finalEffective
                },
                store = AcceptingReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, channelId -> barriers += channelId },
                diagnostics = controller,
                publicationGate = { candidateOwner, channelId, publish ->
                    controller.runPublicationIfEffective(false, candidateOwner, channelId, publish)
                },
            )
        controller.setOnChanged(canary::refreshEffectiveState)
        val prepared =
            assertIs<ReadStateV1ProductionDisposition.Selected>(
                canary.prepareHydrate(owner, "channel-a", readStateVersion = 1L, maxReadSeq = 7L)
            ).preparedPublication
        assertTrue(prepared != null)

        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOff)

        assertEquals(listOf("channel-a"), barriers)
        assertFalse(canary.completePreparedPublication(prepared))
        assertEquals(1L, controller.snapshot().stalePublicationRejectedCount)
        assertTrue(controller.snapshot().barrierState.orEmpty().startsWith("snapshot_requested="))
    }

    @Test
    fun `ttl expiry revokes prepared publication before completion`() = runBlocking {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope, ttlMillis = 10L)
        val barriers = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { candidateOwner, channelId ->
                    controller.decide(false, candidateOwner, channelId).finalEffective
                },
                store = AcceptingReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, channelId -> barriers += channelId },
                diagnostics = controller,
                publicationGate = { candidateOwner, channelId, publish ->
                    controller.runPublicationIfEffective(false, candidateOwner, channelId, publish)
                },
            )
        controller.setOnChanged(canary::refreshEffectiveState)
        val prepared =
            assertIs<ReadStateV1ProductionDisposition.Selected>(
                canary.prepareHydrate(owner, "channel-a", readStateVersion = 1L, maxReadSeq = 7L)
            ).preparedPublication
        assertTrue(prepared != null)

        now += 10L

        assertFalse(canary.completePreparedPublication(prepared))
        assertEquals(listOf("channel-a"), barriers)
        assertEquals("expired", controller.snapshot().clearReason)
        assertEquals(1L, controller.snapshot().stalePublicationRejectedCount)
    }

    @Test
    fun `publication final gate rejects expiry crossed after entry precheck`() = runBlocking {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope, ttlMillis = 10L)
        val barriers = mutableListOf<String>()
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { candidateOwner, channelId ->
                    controller.decide(false, candidateOwner, channelId).finalEffective
                },
                store = AcceptingReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, channelId -> barriers += channelId },
                diagnostics = controller,
                publicationGate = { candidateOwner, channelId, publish ->
                    now += 1L
                    controller.runPublicationIfEffective(false, candidateOwner, channelId, publish)
                },
            )
        controller.setOnChanged(canary::refreshEffectiveState)
        val prepared =
            assertIs<ReadStateV1ProductionDisposition.Selected>(
                canary.prepareHydrate(owner, "channel-a", readStateVersion = 1L, maxReadSeq = 7L)
            ).preparedPublication
        assertTrue(prepared != null)
        now = 1_009L

        assertFalse(canary.completePreparedPublication(prepared))
        assertEquals(listOf("channel-a"), barriers)
        assertEquals("expired", controller.snapshot().clearReason)
    }

    @Test
    fun `snapshot barrier publication records barrier selection generation`() = runBlocking {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOnExactScope)
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { candidateOwner, channelId ->
                    controller.decide(false, candidateOwner, channelId).finalEffective
                },
                store = AcceptingReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onProjectionWithEvidence = { _, _, _ -> ReadStateV1DestinationPublication(1, 1) },
                onRebaseline = { _, _, _ -> },
                diagnostics = controller,
                publicationGate = { candidateOwner, channelId, publish ->
                    controller.runPublicationIfEffective(false, candidateOwner, channelId, publish)
                },
            )
        controller.setOnChanged(canary::refreshEffectiveState)
        assertIs<ReadStateV1ProductionDisposition.Selected>(
            canary.applyHydrate(owner, "channel-a", readStateVersion = 1L, maxReadSeq = 7L)
        )
        val selectedSnapshot = controller.snapshot()
        assertEquals(1L, selectedSnapshot.witnessGeneration)
        assertEquals("home=1 activity=1 generation=1", selectedSnapshot.destinationPublication)

        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOff)
        assertIs<ReadStateV1ProductionDisposition.Selected>(
            canary.applyHydrate(owner, "channel-a", readStateVersion = 2L, maxReadSeq = 8L)
        )

        val barrierSnapshot = controller.snapshot()
        assertEquals(2L, barrierSnapshot.witnessGeneration)
        assertEquals("home=1 activity=1 generation=2", barrierSnapshot.destinationPublication)
        assertEquals("released", barrierSnapshot.barrierState)
    }
}

private class AcceptingReadStateV1Store : ReadStateV1Store {
    private var fact: ReadStateV1Fact? = null

    override suspend fun commit(
        scope: ReadStateV1Scope,
        fact: ReadStateV1Fact,
        mode: ReadStateV1CommitMode,
        guard: ReadStateV1CommitGuard,
    ): ReadStateV1StoreResult {
        if (!guard.isCurrent()) return ReadStateV1StoreResult.RejectedCurrentGrant
        this.fact = fact
        return ReadStateV1StoreResult.Accepted
    }

    override suspend fun restore(scope: ReadStateV1Scope): ReadStateV1Fact? = fact
}
