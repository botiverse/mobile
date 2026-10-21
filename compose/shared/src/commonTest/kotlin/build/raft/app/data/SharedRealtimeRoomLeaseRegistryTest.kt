package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun closingAMemberRoomKeepsTheServerSubscriptionAndAPublicRoomLeaves() {
        // The server subscribes the socket to every member channel on connect; leaving one would stop its
        // realtime messages and unread counts until the next reconnect.
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a") { _, roomId -> roomId == "public-browsed" }
        val page = owner("thread-pager-1", generation = 1)

        assertEquals(
            listOf(join("member-channel"), join("public-browsed")),
            registry.acquire(page, listOf("member-channel", "public-browsed")),
        )
        assertEquals(listOf(leave("public-browsed")), registry.release(page))

        // Reopening still joins both: the lease state is released either way.
        assertEquals(
            listOf(join("member-channel"), join("public-browsed")),
            registry.acquire(page, listOf("member-channel", "public-browsed")),
        )
    }

    @Test
    fun aChannelTheUserJoinedFromThePageIsNeverLeft() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a") { _, _ -> true }
        val page = owner("thread-pager-1", generation = 1)
        registry.acquire(page, listOf("public-browsed"))

        registry.markJoinedByUser("server-a", "public-browsed")

        assertTrue(registry.release(page).isEmpty())
    }

    /**
     * Task #14c, paired: the same reply-thread page (thread room + parent channel, both "not a member" in the
     * directory) releases its lease. The thread room it marked stays joined; the parent channel is left as before.
     */
    @Test
    fun aReplyThreadRoomIsKeptOnPageCloseWhileItsParentChannelIsStillLeft() {
        val left = mutableListOf<String>()
        fun registry() = SharedRealtimeRoomLeaseRegistry("shell-a", onLeave = { _, roomId -> left += roomId }) { _, _ -> true }
        val page = owner("thread-pager-1", generation = 1)

        val marked = registry()
        marked.keepJoinedUntilClear("server-a", "thread-1")
        assertEquals(listOf(join("channel-1"), join("thread-1")), marked.acquire(page, listOf("channel-1", "thread-1")))
        assertEquals(listOf(leave("channel-1")), marked.release(page))
        assertEquals(listOf("channel-1"), left, "only the parent's leave reaches the offline cache")

        // Twin: the identical page without the mark leaves both rooms (today's rule for any non-member room).
        left.clear()
        val unmarked = registry()
        unmarked.acquire(page, listOf("channel-1", "thread-1"))
        assertEquals(listOf(leave("channel-1"), leave("thread-1")), unmarked.release(page))
        assertEquals(listOf("channel-1", "thread-1"), left)
    }

    @Test
    fun onlyTheMostRecentlyReleasedKeptThreadRoomsStayJoined() {
        val left = mutableListOf<String>()
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a", onLeave = { _, roomId -> left += roomId }) { _, _ -> true }
        val page = owner("thread-pager-1", generation = 1)
        // Release MAX kept thread rooms: all stay joined.
        (1..MAX_RETAINED_THREAD_ROOMS).forEach { n ->
            registry.keepJoinedUntilClear("server-a", "thread-$n")
            registry.acquire(page, listOf("thread-$n"))
            assertTrue(registry.release(page).isEmpty(), "thread-$n is within the bound")
        }
        // Reopening thread-1 makes it the most recent again.
        registry.acquire(page, listOf("thread-1"))
        assertTrue(registry.release(page).isEmpty())
        assertTrue(left.isEmpty())
        // One more: the oldest release (thread-2, not the reopened thread-1) is left, exactly once.
        val next = "thread-${MAX_RETAINED_THREAD_ROOMS + 1}"
        registry.keepJoinedUntilClear("server-a", next)
        registry.acquire(page, listOf(next))
        assertEquals(listOf(leave("thread-2")), registry.release(page))
        assertEquals(listOf("thread-2"), left)
        // The evicted room lost its mark: a later lease of it is an ordinary room again.
        registry.acquire(page, listOf("thread-2"))
        assertEquals(listOf(leave("thread-2")), registry.release(page))
    }

    @Test
    fun aKeptThreadRoomIsLeftByServerAndShellTeardownOpenOrClosed() {
        val left = mutableListOf<String>()
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a", onLeave = { _, roomId -> left += roomId }) { _, _ -> true }
        val closedPage = owner("thread-pager-1", generation = 1)
        val openPage = owner("thread-pager-2", generation = 1)
        registry.keepJoinedUntilClear("server-a", "thread-1")
        registry.keepJoinedUntilClear("server-a", "thread-2")
        registry.acquire(closedPage, listOf("channel-1", "thread-1"))
        assertEquals(listOf(leave("channel-1")), registry.release(closedPage))
        registry.acquire(openPage, listOf("thread-2"))

        // Another server's teardown leaves nothing of server-a.
        assertTrue(registry.clearServer("server-b").isEmpty())
        // The closed page's kept room (owned by nobody) and the open page's room are both left, each once.
        assertEquals(setOf(leave("thread-1"), leave("thread-2")), registry.clearServer("server-a").toSet())
        assertEquals(listOf("channel-1", "thread-1", "thread-2"), left.sortedBy { it })
        assertTrue(registry.clearServer("server-a").isEmpty(), "the marks went with the teardown")

        // After teardown the room is an ordinary room again: a new lease's release leaves it.
        registry.acquire(closedPage, listOf("thread-1"))
        assertEquals(listOf(leave("thread-1")), registry.release(closedPage))

        // Shell teardown (clear) leaves a kept room too.
        registry.keepJoinedUntilClear("server-a", "thread-3")
        registry.acquire(openPage, listOf("thread-3"))
        assertTrue(registry.release(openPage).isEmpty())
        assertEquals(listOf(leave("thread-3")), registry.clear())
        assertTrue(registry.clear().isEmpty())
    }

    @Test
    fun aKeptThreadRoomStillFollowsTheMemberRuleOnTeardown() {
        // Teardown leaves a kept room exactly like a released one today: never a room the user belongs to.
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a") { _, roomId -> roomId != "member-thread" }
        val page = owner("thread-pager-1", generation = 1)
        registry.keepJoinedUntilClear("server-a", "member-thread")
        registry.keepJoinedUntilClear("server-a", "browsed-thread")
        registry.acquire(page, listOf("member-thread", "browsed-thread"))
        assertTrue(registry.release(page).isEmpty())
        assertEquals(listOf(leave("browsed-thread")), registry.clearServer("server-a"))
    }

    @Test
    fun shellTeardownAlsoKeepsMemberRooms() {
        val registry = SharedRealtimeRoomLeaseRegistry("shell-a") { _, roomId -> roomId == "public-browsed" }
        registry.acquire(owner("thread-pager-1", generation = 1), listOf("member-channel", "public-browsed"))

        assertEquals(listOf(leave("public-browsed")), registry.clear())
    }

    @Test
    fun onlyAKnownNonMemberRoomMayBeLeft() {
        val directory = FakeDirectory(
            mapOf(
                "member-channel" to projection("member-channel", joined = true),
                "public-browsed" to projection("public-browsed", joined = false),
            ),
        )

        assertEquals(false, realtimeRoomLeaveAllowed(directory, "server-a", "member-channel"))
        assertEquals(true, realtimeRoomLeaveAllowed(directory, "server-a", "public-browsed"))
        assertEquals(false, realtimeRoomLeaveAllowed(directory, "server-a", "unknown-room"), "unknown = keep")
        assertEquals(false, realtimeRoomLeaveAllowed(null, "server-a", "public-browsed"), "no directory = keep")
    }

    @Test
    fun aDirectMessageFromTheHomeDirectoryIsNeverLeft() {
        // Built through the production row -> directory conversion: DM rows carry no joined field on the
        // wire, so the projection must still read as a member and the DM room must stay subscribed.
        val dm =
            HomeDirectMessageRow(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                name = "Alice",
            ).toSharedConversationDirectoryProjection(ServerId("server-a"))
        assertNotNull(dm)
        val directory = FakeDirectory(mapOf("dm-1" to dm))
        val registry =
            SharedRealtimeRoomLeaseRegistry("shell-a") { serverId, roomId ->
                realtimeRoomLeaveAllowed(directory, serverId, roomId)
            }
        val page = owner("thread-pager-1", generation = 1)
        registry.acquire(page, listOf("dm-1"))

        assertTrue(registry.release(page).isEmpty())
    }

    private class FakeDirectory(
        private val rows: Map<String, SharedConversationDirectoryProjection>,
    ) : SharedConversationIdentityProvider {
        override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> = emptyFlow()

        override fun projectionFlow(
            serverId: ServerId,
            ids: Collection<String>,
        ): Flow<Map<String, SharedConversationDirectoryProjection>> = emptyFlow()

        override fun resolveProjection(serverId: ServerId, id: String): SharedConversationDirectoryProjection? =
            rows[id]?.takeIf { it.serverId == serverId.value }
    }

    private fun projection(channelId: String, joined: Boolean): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-a",
            canonicalChannelId = channelId,
            aliases = setOf(channelId),
            conversationKind = ConversationKind.Channel,
            surfaceKind = "channel",
            channelKind = "public",
            title = channelId,
            joined = joined,
        )

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
