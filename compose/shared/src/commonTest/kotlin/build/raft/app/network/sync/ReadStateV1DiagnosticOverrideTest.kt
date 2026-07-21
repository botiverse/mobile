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

        val result = controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)

        assertIs<ReadStateV1DiagnosticOverrideMutationResult.Rejected>(result)
        assertEquals("read_state_diagnostic_override_not_entitled", result.code)
        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
    }

    @Test
    fun `force on applies across the app process before any scope is observed`() {
        controller.configureEntitlement(true)
        assertIs<ReadStateV1DiagnosticOverrideMutationResult.Applied>(
            controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)
        )

        val first = controller.decide(serverEffective = false, owner, "channel-a")
        val other = controller.decide(serverEffective = false, owner.copy(accountId = "account-b"), "channel-b")

        assertTrue(first.finalEffective)
        assertFalse(first.exactScopeMatched)
        assertEquals("diagnostic_override_force_on", first.precedence)
        assertTrue(other.finalEffective)
        assertFalse(other.exactScopeMatched)
        assertEquals("diagnostic_override_force_on", other.precedence)
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
    fun `mode mutation immediately recomputes the cached final decision`() {
        controller.configureEntitlement(true)
        controller.decide(serverEffective = false, owner, "channel-a")

        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)

        val forcedOn = requireNotNull(controller.snapshot().lastDecision)
        assertTrue(forcedOn.finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.ForceOn, forcedOn.clientOverride)
        assertEquals("diagnostic_override_force_on", forcedOn.precedence)

        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOff)

        val forcedOff = requireNotNull(controller.snapshot().lastDecision)
        assertFalse(forcedOff.finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.ForceOff, forcedOff.clientOverride)
        assertEquals("diagnostic_override_force_off", forcedOff.precedence)
    }

    @Test
    fun `ttl expiry returns to server mode`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn, ttlMillis = 10L)

        now += 10L

        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.Server, controller.snapshot().mode)
        assertEquals("expired", controller.snapshot().clearReason)
    }

    @Test
    fun `identity transition does not clear process override`() {
        controller.configureEntitlement(true)
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)

        val changedOwner = owner.copy(accountId = "account-b")
        val decision = controller.decide(false, changedOwner, "channel-a")

        assertTrue(decision.finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.ForceOn, controller.snapshot().mode)
        assertEquals(null, controller.snapshot().boundScope)
        assertEquals(null, controller.snapshot().clearReason)
    }

    @Test
    fun `clearing identity observation preserves process override ttl and clears evidence`() {
        var changedCount = 0
        controller.setOnChanged { changedCount += 1 }
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn, ttlMillis = 100L)
        controller.decide(false, owner, "channel-a")
        controller.recordSelection(owner, "channel-a", generation = 9L)
        controller.recordAdjudication(
            source = "hydrate",
            result = "Committed",
            fact = ReadStateV1Fact(channelId = "channel-a", readStateVersion = 4L, maxReadSeq = 12L),
        )
        controller.recordPublication(homeStoreCount = 1, activityStoreCount = 1, generation = 9L)
        controller.recordBarrier("released")
        controller.recordStalePublicationRejected()
        val expiresAt = controller.snapshot().expiresAtMillis
        val changedBeforeClear = changedCount

        controller.clearObservation()

        val cleared = controller.snapshot()
        assertEquals(ReadStateV1DiagnosticOverrideMode.ForceOn, cleared.mode)
        assertEquals(expiresAt, cleared.expiresAtMillis)
        assertNull(cleared.clearReason)
        assertNull(cleared.observedScope)
        assertNull(cleared.boundScope)
        assertNull(cleared.lastDecision)
        assertNull(cleared.selectedScope)
        assertNull(cleared.witnessGeneration)
        assertNull(cleared.durableReadStateVersion)
        assertNull(cleared.durableMaxReadSeq)
        assertNull(cleared.latestAdjudication)
        assertNull(cleared.destinationPublication)
        assertNull(cleared.barrierState)
        assertEquals(0L, cleared.stalePublicationRejectedCount)
        assertEquals(changedBeforeClear + 1, changedCount)
        assertTrue(controller.decide(false, owner.copy(accountId = "account-b"), "channel-b").finalEffective)

        now = requireNotNull(expiresAt)

        assertFalse(controller.decide(false, owner, "channel-a").finalEffective)
        assertEquals(ReadStateV1DiagnosticOverrideMode.Server, controller.snapshot().mode)
        assertEquals("expired", controller.snapshot().clearReason)
    }

    @Test
    fun `server mode clears local override and preserves server effective`() {
        controller.configureEntitlement(true)
        controller.observe(owner, "channel-a")
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)

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
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)
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
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn, ttlMillis = 10L)
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
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn, ttlMillis = 10L)
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
        controller.setMode(ReadStateV1DiagnosticOverrideMode.ForceOn)
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
