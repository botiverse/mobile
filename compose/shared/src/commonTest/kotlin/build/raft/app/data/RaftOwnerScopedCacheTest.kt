package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftOwnerScopedCacheTest {
    @Test
    fun ownerConstructionNormalizesCompleteIdentityAndRejectsMissingFields() {
        assertEquals(
            RaftOwnerScopedCacheOwner.createOrNull("https://api.raft.build/api", "user-a", "server-1"),
            RaftOwnerScopedCacheOwner.createOrNull(" https://api.raft.build/api/ ", " user-a ", " server-1 "),
        )
        assertNull(RaftOwnerScopedCacheOwner.createOrNull("", "user-a", "server-1"))
        assertNull(RaftOwnerScopedCacheOwner.createOrNull("https://api.raft.build/api", "", "server-1"))
        assertNull(RaftOwnerScopedCacheOwner.createOrNull("https://api.raft.build/api", "user-a", ""))
    }

    @Test
    fun sameResourceIsPartitionedByEnvironmentAccountAndServer() {
        val cache = RaftOwnerScopedBoundedCache<String, String>(maxEntries = 8)
        val owner = owner()
        val otherEnvironment = owner(environmentId = "https://staging.raft.build/api")
        val otherAccount = owner(accountId = "user-b")
        val otherServer = owner(serverId = "server-2")

        assertTrue(cache.put(owner, "channel-1", "owner"))
        assertNull(cache.get(otherEnvironment, "channel-1"))
        assertNull(cache.get(otherAccount, "channel-1"))
        assertNull(cache.get(otherServer, "channel-1"))
        assertEquals("owner", cache.get(owner, "channel-1"))
    }

    @Test
    fun resourceOwnerMismatchFailsClosed() {
        data class ServerResource(val serverId: String, val id: String)

        val cache =
            RaftOwnerScopedBoundedCache<ServerResource, String>(
                maxEntries = 4,
                acceptsResource = { owner, resource -> owner.serverId == resource.serverId },
            )
        val owner = owner()
        val mismatched = ServerResource(serverId = "server-2", id = "channel-1")

        assertFalse(cache.put(owner, mismatched, "wrong"))
        assertNull(cache.get(owner, mismatched))
        assertNull(cache.remove(owner, mismatched))
        assertEquals(0, cache.size)
    }

    @Test
    fun mismatchedAuthoritativeValueRetiresExistingExactKey() {
        data class ChannelResource(val serverId: String, val channelId: String)
        data class ChannelSnapshot(val serverId: String, val channelId: String, val name: String)

        val cache =
            RaftOwnerScopedBoundedCache<ChannelResource, ChannelSnapshot>(
                maxEntries = 4,
                acceptsResource = { owner, resource -> owner.serverId == resource.serverId },
                acceptsValue = { owner, resource, value ->
                    owner.serverId == value.serverId &&
                        resource.serverId == value.serverId &&
                        resource.channelId == value.channelId
                },
            )
        val owner = owner()
        val key = ChannelResource(serverId = "server-1", channelId = "channel-1")
        assertTrue(cache.put(owner, key, ChannelSnapshot("server-1", "channel-1", "old")))

        assertFalse(cache.put(owner, key, ChannelSnapshot("server-1", "channel-2", "wrong")))

        assertNull(cache.get(owner, key))
        assertEquals(0, cache.size)
    }

    @Test
    fun readsAdvanceDeterministicLruOrder() {
        val cache = RaftOwnerScopedBoundedCache<String, String>(maxEntries = 2)
        val owner = owner()
        cache.put(owner, "channel-1", "one")
        cache.put(owner, "channel-2", "two")

        assertEquals("one", cache.get(owner, "channel-1"))
        cache.put(owner, "channel-3", "three")

        assertNull(cache.get(owner, "channel-2"))
        assertEquals("one", cache.get(owner, "channel-1"))
        assertEquals("three", cache.get(owner, "channel-3"))
        assertEquals(2, cache.size)
    }

    @Test
    fun exactRemovalAndMutationDoNotAffectOtherOwner() {
        val cache = RaftOwnerScopedBoundedCache<String, String>(maxEntries = 4)
        val ownerA = owner(accountId = "user-a")
        val ownerB = owner(accountId = "user-b")
        cache.put(ownerA, "channel-1", "a-old")
        cache.put(ownerB, "channel-1", "b")

        cache.put(ownerA, "channel-1", "a-new")
        assertEquals("b", cache.get(ownerB, "channel-1"))
        assertEquals("a-new", cache.remove(ownerA, "channel-1"))
        assertNull(cache.get(ownerA, "channel-1"))
        assertEquals("b", cache.get(ownerB, "channel-1"))
    }

    @Test
    fun removeOwnerRetiresOnlyThatPartition() {
        val cache = RaftOwnerScopedBoundedCache<String, String>(maxEntries = 4)
        val ownerA = owner(accountId = "user-a")
        val ownerB = owner(accountId = "user-b")
        cache.put(ownerA, "channel-1", "a1")
        cache.put(ownerA, "channel-2", "a2")
        cache.put(ownerB, "channel-1", "b1")

        assertEquals(listOf("a1", "a2"), cache.removeOwner(ownerA))
        assertNull(cache.get(ownerA, "channel-1"))
        assertNull(cache.get(ownerA, "channel-2"))
        assertEquals("b1", cache.get(ownerB, "channel-1"))
        assertEquals(1, cache.size)
    }

    @Test
    fun nonPositiveCapacityIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RaftOwnerScopedBoundedCache<String, String>(maxEntries = 0)
        }
    }

    private fun owner(
        environmentId: String = "https://api.raft.build/api",
        accountId: String = "user-a",
        serverId: String = "server-1",
    ): RaftOwnerScopedCacheOwner =
        requireNotNull(RaftOwnerScopedCacheOwner.createOrNull(environmentId, accountId, serverId))
}
