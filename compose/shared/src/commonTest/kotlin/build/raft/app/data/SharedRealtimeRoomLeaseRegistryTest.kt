package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedRealtimeRoomLeaseRegistryTest {
    @Test
    fun joinsOnFirstOwnerAndLeavesAfterFinalOwnerOnly() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        val first = owner("list", generation = 1)
        val second = owner("detail", generation = 1)

        assertEquals(listOf(join("room-a")), registry.acquire(first, listOf("room-a")))
        assertTrue(registry.acquire(second, listOf("room-a")).isEmpty())
        assertEquals(2, registry.ownerCount("server-a", "room-a"))
        assertTrue(registry.release(first).isEmpty())
        assertEquals(listOf(leave("room-a")), registry.release(second))
    }

    @Test
    fun staleGenerationReleaseCannotEvictReplacementConsumer() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        val old = owner("detail", generation = 1)
        val replacement = owner("detail", generation = 2)

        registry.acquire(old, listOf("room-a"))
        assertTrue(registry.acquire(replacement, listOf("room-a")).isEmpty())
        assertTrue(registry.release(old).isEmpty())
        assertEquals(1, registry.ownerCount("server-a", "room-a"))
        assertEquals(listOf(leave("room-a")), registry.release(replacement))
    }

    @Test
    fun duplicateAcquireAndLateReleaseAreIdempotent() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        val owner = owner("detail", generation = 1)

        assertEquals(listOf(join("room-a")), registry.acquire(owner, listOf("room-a", "room-a", " ")))
        assertTrue(registry.acquire(owner, listOf("room-a")).isEmpty())
        assertEquals(listOf(leave("room-a")), registry.release(owner))
        assertTrue(registry.release(owner).isEmpty())
    }

    @Test
    fun serverTeardownLeavesEachRoomOnceAndMakesOldLeasesInert() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        val first = owner("list", generation = 1)
        val second = owner("detail", generation = 1)
        registry.acquire(first, listOf("room-a", "room-b"))
        registry.acquire(second, listOf("room-a"))

        assertEquals(
            setOf(leave("room-a"), leave("room-b")),
            registry.clearServer("server-a").toSet(),
        )
        assertTrue(registry.release(first).isEmpty())
        assertTrue(registry.release(second).isEmpty())
    }

    @Test
    fun inactiveReleaseAndReactivationAcquireProduceBalancedMembership() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        val detail = owner("detail", generation = 3)

        assertEquals(listOf(join("room-a")), registry.acquire(detail, listOf("room-a")))
        assertEquals(listOf(leave("room-a")), registry.release(detail))
        assertEquals(listOf(join("room-a")), registry.acquire(detail, listOf("room-a")))
        assertEquals(1, registry.ownerCount("server-a", "room-a"))
    }

    @Test
    fun fullShellTeardownLeavesEveryRoomOnce() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a")
        registry.acquire(owner("first", generation = 1), listOf("room-a", "room-b"))
        registry.acquire(owner("second", generation = 2), listOf("room-a"))

        assertEquals(setOf(leave("room-a"), leave("room-b")), registry.clear().toSet())
        assertTrue(registry.clear().isEmpty())
    }

    private fun owner(consumer: String, generation: Long): RealtimeRoomLeaseOwner =
        RealtimeRoomLeaseOwner(
            shellInstanceId = "shell-a",
            serverId = "server-a",
            consumerId = consumer,
            generation = generation,
        )

    private fun join(roomId: String): RealtimeRoomTransition =
        RealtimeRoomTransition(RealtimeRoomTransitionAction.Join, "server-a", roomId)

    private fun leave(roomId: String): RealtimeRoomTransition =
        RealtimeRoomTransition(RealtimeRoomTransitionAction.Leave, "server-a", roomId)
}
