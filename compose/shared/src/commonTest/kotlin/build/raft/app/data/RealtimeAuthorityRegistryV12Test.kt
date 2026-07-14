package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Foundation v1.2 framework oracle.
 *
 * This first slice pins the canonical authority registry shape before provider,
 * SQLite/shadow and sink-router wiring are moved onto it. It deliberately tests
 * lease-wide families and auth tombstones rather than the rejected per-server /
 * per-shell-owner model.
 */
class RealtimeAuthorityRegistryV12Test {

    @Test
    fun staleEqualNewAndNullAuthRevisionsRemainDurableAcrossRestart() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val registry = registry(store, process = "process-1")
        registry.initialize()

        assertTrue(registry.advanceAuth(revision(1, principal = "p1", servers = listOf("a", "b"))))
        val lease = registry.register("shell-a")
        val generationOne = registry.snapshot.value
        assertEquals(1L, generationOne.authRevision.generation)
        assertEquals(setOf("a", "b"), generationOne.grantsFor(lease.id).map { it.serverId }.toSet())

        assertFalse(registry.advanceAuth(revision(1, principal = "p1", servers = listOf("a", "b"))))
        assertEquals(generationOne, registry.snapshot.value, "equal auth events must mint nothing")
        assertFalse(registry.advanceAuth(revision(0, principal = "stale", servers = listOf("x"))))
        assertEquals(generationOne, registry.snapshot.value, "lower generations must be ignored")

        assertTrue(registry.advanceAuth(RealtimeAuthRevision(generation = 2, session = null)))
        assertEquals(2L, registry.snapshot.value.authRevision.generation)
        assertNull(registry.snapshot.value.authRevision.session)
        assertTrue(registry.snapshot.value.currentGrantBySlot.isEmpty())
        assertTrue(registry.snapshot.value.currentFamilyByLease.isEmpty())

        val restarted = registry(store, process = "process-2")
        restarted.initialize()
        assertEquals(RealtimeAuthRevision(generation = 2, session = null), restarted.snapshot.value.authRevision)
        val tombstoneLease = restarted.register("shell-after-restart")
        assertTrue(restarted.snapshot.value.grantsFor(tombstoneLease.id).isEmpty())
        assertNull(restarted.currentWitness(tombstoneLease.id))
    }

    @Test
    fun sameRevisionRegistrationIsIdempotentButReleasedKeyGetsNewLeaseAndImmediateGrants() = runBlocking {
        val registry = registry(InMemorySlockKeyValueStore(), process = "process-register")
        registry.initialize()
        registry.advanceAuth(revision(7, principal = "p1", servers = listOf("a", "b")))

        val leaseOne = registry.register("shell")
        val firstSnapshot = registry.snapshot.value
        val duplicate = registry.register("shell")
        assertEquals(leaseOne, duplicate)
        assertEquals(firstSnapshot, registry.snapshot.value, "repeat registration of an active key must mint nothing")

        val oldWitness = assertNotNull(registry.currentWitness(leaseOne.id))
        val oldGrantA = assertNotNull(registry.currentGrant(leaseOne.id, "a"))
        assertTrue(registry.release(leaseOne.id))
        assertFalse(registry.runIfCurrentGrant(oldGrantA) {})

        val leaseTwo = registry.register("shell")
        assertNotEquals(leaseOne.id, leaseTwo.id)
        assertEquals(setOf("a", "b"), registry.snapshot.value.grantsFor(leaseTwo.id).map { it.serverId }.toSet())
        assertNull(registry.resolve(oldWitness, "a"), "an old lease witness must have zero effect on its successor")
        assertEquals(leaseTwo, registry.register("shell"))
    }

    @Test
    fun reconnectReplacesOneLeaseWideFamilyAndAllItsSlotsWithoutTouchingSiblingLease() = runBlocking {
        val registry = registry(InMemorySlockKeyValueStore(), process = "process-reconnect")
        registry.initialize()
        registry.advanceAuth(revision(3, principal = "p1", servers = listOf("a", "b")))
        val leaseOne = registry.register("shell-1")
        val leaseTwo = registry.register("shell-2")

        val oldWitnessOne = assertNotNull(registry.currentWitness(leaseOne.id))
        val oldGrantOneA = assertNotNull(registry.currentGrant(leaseOne.id, "a"))
        val oldGrantOneB = assertNotNull(registry.currentGrant(leaseOne.id, "b"))
        val oldWitnessTwo = assertNotNull(registry.currentWitness(leaseTwo.id))
        val oldGrantTwoA = assertNotNull(registry.currentGrant(leaseTwo.id, "a"))

        assertTrue(registry.reconnect(leaseOne.id))

        val newWitnessOne = assertNotNull(registry.currentWitness(leaseOne.id))
        val newGrantOneA = assertNotNull(registry.currentGrant(leaseOne.id, "a"))
        val newGrantOneB = assertNotNull(registry.currentGrant(leaseOne.id, "b"))
        assertNotEquals(oldWitnessOne.connectionFamilyId, newWitnessOne.connectionFamilyId)
        assertEquals(newWitnessOne.connectionFamilyId, newGrantOneA.connectionFamilyId)
        assertEquals(newWitnessOne.connectionFamilyId, newGrantOneB.connectionFamilyId)
        assertFalse(registry.runIfCurrentGrant(oldGrantOneA) {})
        assertFalse(registry.runIfCurrentGrant(oldGrantOneB) {})
        assertNull(registry.resolve(oldWitnessOne, "a"))
        assertNull(registry.resolve(oldWitnessOne, "b"))

        assertEquals(oldWitnessTwo, registry.currentWitness(leaseTwo.id))
        assertEquals(oldGrantTwoA, registry.currentGrant(leaseTwo.id, "a"))
        assertTrue(registry.runIfCurrentGrant(oldGrantTwoA) {})
    }

    @Test
    fun resolverUsesFreshFamilyWitnessToLookupExistingCrossServerGrantWithoutMinting() = runBlocking {
        val registry = registry(InMemorySlockKeyValueStore(), process = "process-resolve")
        registry.initialize()
        registry.advanceAuth(revision(11, principal = "p1", servers = listOf("selected-a", "event-b")))
        val lease = registry.register("shell")
        val witness = assertNotNull(registry.currentWitness(lease.id))
        val before = registry.snapshot.value

        val grantB = assertNotNull(registry.resolve(witness, "event-b"))
        assertEquals("event-b", grantB.serverId)
        assertEquals(witness.connectionFamilyId, grantB.connectionFamilyId)
        assertNull(registry.resolve(witness, "missing"))
        assertEquals(before, registry.snapshot.value, "resolver must be lookup-only and mint nothing")

        registry.reconnect(lease.id)
        assertNull(registry.resolve(witness, "event-b"), "old family witnesses fail closed for every server")
    }

    @Test
    fun releaseAndAuthAdvanceShareOneLinearizationDomainInBothOrders() = runBlocking {
        val releaseFirst = registry(InMemorySlockKeyValueStore(), process = "release-first")
        releaseFirst.initialize()
        releaseFirst.advanceAuth(revision(1, principal = "p1", servers = listOf("a")))
        val releasedLease = releaseFirst.register("released")
        releaseFirst.release(releasedLease.id)
        releaseFirst.advanceAuth(revision(2, principal = "p1", servers = listOf("a", "b")))
        assertFalse(releasedLease.id in releaseFirst.snapshot.value.activeLeases)
        assertTrue(releaseFirst.snapshot.value.grantsFor(releasedLease.id).isEmpty())

        val authFirst = registry(InMemorySlockKeyValueStore(), process = "auth-first")
        authFirst.initialize()
        authFirst.advanceAuth(revision(1, principal = "p1", servers = listOf("a")))
        val burnedLease = authFirst.register("burned")
        val siblingLease = authFirst.register("sibling")
        authFirst.advanceAuth(revision(2, principal = "p1", servers = listOf("a", "b")))
        val siblingGrant = assertNotNull(authFirst.currentGrant(siblingLease.id, "a"))
        val newlyMintedBurnedGrant = assertNotNull(authFirst.currentGrant(burnedLease.id, "a"))
        authFirst.release(burnedLease.id)

        assertFalse(authFirst.runIfCurrentGrant(newlyMintedBurnedGrant) {})
        assertTrue(authFirst.runIfCurrentGrant(siblingGrant) {})
        assertEquals(siblingGrant, authFirst.currentGrant(siblingLease.id, "a"))
    }

    @Test
    fun durableRevisionIsRereadAndEqualGenerationPayloadConflictFailsClosed() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val diagnostics = mutableListOf<String>()
        val first = registry(store, process = "process-first")
        first.initialize()
        assertEquals(
            RealtimeAuthAdvanceResult.Advanced,
            first.advanceAuthResult(revision(1, principal = "p1", servers = listOf("a"))),
        )

        val staleProcess =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(store),
                processInstanceId = "process-stale",
                diagnosticsLogger = diagnostics::add,
            )
        staleProcess.initialize()
        assertEquals(
            RealtimeAuthAdvanceResult.Advanced,
            first.advanceAuthResult(revision(2, principal = "p2", servers = listOf("b"))),
        )

        assertEquals(
            RealtimeAuthAdvanceResult.Conflict,
            staleProcess.advanceAuthResult(revision(2, principal = "p3", servers = listOf("c"))),
        )
        assertEquals(
            revision(2, principal = "p2", servers = listOf("b")),
            KeyValueRealtimeAuthRevisionStore(store).read(),
            "a stale process must reread durable truth instead of overwriting equal generation",
        )
        assertTrue(diagnostics.any { it.contains("equal_generation_payload_conflict") })
    }

    @Test
    fun providerStyleReconcileAllocatesFromDurableTruthAndCanForceSamePayloadSuccessor() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val registry = registry(store, process = "process-provider")
        registry.initialize()

        val p1 = RealtimeAuthoritySession(principalId = "p1", serverIds = listOf("a", "b"))
        assertEquals(RealtimeAuthAdvanceResult.Advanced, registry.advanceAuthFromCurrentSession(p1, force = false))
        assertEquals(1L, registry.snapshot.value.authRevision.generation)
        assertEquals(RealtimeAuthAdvanceResult.Duplicate, registry.advanceAuthFromCurrentSession(p1, force = false))
        assertEquals(RealtimeAuthAdvanceResult.Advanced, registry.advanceAuthFromCurrentSession(p1, force = true))
        assertEquals(2L, registry.snapshot.value.authRevision.generation)

        assertEquals(RealtimeAuthAdvanceResult.Advanced, registry.advanceAuthFromCurrentSession(null, force = false))
        assertEquals(3L, registry.snapshot.value.authRevision.generation)
        assertNull(registry.snapshot.value.authRevision.session)
        assertEquals(RealtimeAuthAdvanceResult.Duplicate, registry.advanceAuthFromCurrentSession(null, force = false))
    }

    @Test
    fun processInstanceIdMustBeFreshConstructionInputAndNeverBlank() {
        assertFailsWith<IllegalArgumentException> {
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
                processInstanceId = "   ",
            )
        }
    }

    private fun registry(store: SlockKeyValueStore, process: String): RealtimeAuthorityRegistry =
        RealtimeAuthorityRegistry(
            authRevisionStore = KeyValueRealtimeAuthRevisionStore(store),
            processInstanceId = process,
        )

    private fun revision(generation: Long, principal: String, servers: List<String>) =
        RealtimeAuthRevision(
            generation = generation,
            session = RealtimeAuthoritySession(principalId = principal, serverIds = servers),
        )
}
