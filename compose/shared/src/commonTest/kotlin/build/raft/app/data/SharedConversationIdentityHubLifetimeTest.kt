package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-layer plan step 4: an identity (name, avatar, DM peer) known once stays painted across token
 * refresh, cold start (pre-auth publication), server switch A -> B -> A and a Home rebind; only an
 * environment/account change fences it, lost access evicts it, and the hub is LRU-capped per server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedConversationIdentityHubLifetimeTest {
    @Test
    fun identitySurvivesSameAccountTokenRefresh() = runTest {
        val hub = SharedConversationIdentityHub()
        val frames = hub.titleFrames(this, "server-a", "dm-1")
        assertTrue(
            hub.publishCandidate(
                authority = authority(server = "server-a", authGeneration = 4),
                revision = 1,
                next = listOf(dm("server-a", "dm-1", "Cindy")).toSharedConversationDirectoryState(),
            )
        )
        assertTrue(hub.publishCanonical(authority(server = "server-a", authGeneration = 4, selection = 1), dm("server-a", "dm-2", "Dora")))

        // Token rotation: the first publication under the new auth generation (opening any channel).
        assertTrue(hub.publishCanonical(authority(server = "server-a", authGeneration = 5, selection = 1), dm("server-a", "dm-3", "Eve")))

        assertEquals("Cindy", hub.resolveProjection(ServerId("server-a"), "dm-1")?.title)
        assertEquals("Dora", hub.resolveProjection(ServerId("server-a"), "dm-2")?.title)
        assertEquals("Cindy", hub.resolveProjectionFor("account-1", ServerId("server-a"), "dm-1")?.title)
        // A response that was in flight across the rotation is still this account's data.
        assertTrue(
            hub.publishCandidate(
                authority = authority(server = "server-a", authGeneration = 4),
                revision = 2,
                next = listOf(dm("server-a", "dm-1", "Cindy L")).toSharedConversationDirectoryState(),
            )
        )
        assertEquals(listOf<String?>(null, "Cindy", "Cindy L"), frames, "no frame may drop the known name")
    }

    @Test
    fun preAuthCandidateIsAppliedAfterBindNotDropped() = runTest {
        val hub = SharedConversationIdentityHub()
        val frames = hub.titleFrames(this, "server-a", "dm-1")
        val restored = listOf(dm("server-a", "dm-1", "Cindy")).toSharedConversationDirectoryState()

        assertTrue(hub.stagePendingCandidate(Env, "account-1", "server-a", revision = 1, next = restored))
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-1"), "nothing is readable before the binding")
        // A newer staged revision replaces an older one; an older one is ignored.
        assertFalse(hub.stagePendingCandidate(Env, "account-1", "server-a", revision = 1, next = restored))

        assertEquals(1, hub.applyPendingCandidates("$Env/", "account-1", authGeneration = 3))

        assertEquals("Cindy", hub.resolveProjectionFor("account-1", ServerId("server-a"), "dm-1")?.title)
        assertEquals(0, hub.applyPendingCandidates(Env, "account-1", authGeneration = 3), "applied once")
        // Home's next (live) publication continues the same revision sequence.
        assertTrue(
            hub.publishCandidate(
                authority = authority(server = "server-a", authGeneration = 3),
                revision = 2,
                next = listOf(dm("server-a", "dm-1", "Cindy L")).toSharedConversationDirectoryState(),
            )
        )
        assertEquals(listOf<String?>(null, "Cindy", "Cindy L"), frames)
    }

    @Test
    fun accountOrEnvironmentChangeStillFences() = runTest {
        val hub = SharedConversationIdentityHub()
        // Staged rows for another account / environment are discarded at bind.
        hub.stagePendingCandidate(Env, "account-0", "server-a", 1, listOf(dm("server-a", "dm-0", "Old")).toSharedConversationDirectoryState())
        hub.stagePendingCandidate("https://other.example", "account-1", "server-b", 1, listOf(dm("server-b", "dm-9", "Other env")).toSharedConversationDirectoryState())
        assertEquals(0, hub.applyPendingCandidates(Env, "account-1", authGeneration = 2))
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-0"))
        assertNull(hub.resolveProjection(ServerId("server-b"), "dm-9"))

        assertTrue(hub.publishCanonical(authority(server = "server-a", authGeneration = 2, selection = 1), dm("server-a", "dm-1", "Cindy")))
        // Account switch: needs a newer auth generation and clears every row.
        assertTrue(
            hub.publishCandidate(
                authority = authority(server = "server-a", authGeneration = 3, account = "account-2"),
                revision = 1,
                next = listOf(dm("server-a", "dm-2", "Second")).toSharedConversationDirectoryState(),
            )
        )
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-1"))
        assertNull(hub.resolveProjectionFor("account-1", ServerId("server-a"), "dm-2"))
        // A late publication of the predecessor account is dropped, at any auth generation it carried.
        assertFalse(hub.publishCanonical(authority(server = "server-a", authGeneration = 2, selection = 9), dm("server-a", "dm-1", "Late")))
        assertFalse(
            hub.publishCandidate(
                authority = authority(server = "server-a", authGeneration = 3, environment = "https://other.example", account = "account-2"),
                revision = 5,
                next = listOf(dm("server-a", "dm-3", "Other env")).toSharedConversationDirectoryState(),
            )
        )
        assertEquals("Second", hub.resolveProjectionFor("account-2", ServerId("server-a"), "dm-2")?.title)
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-3"))
    }

    @Test
    fun lostAccessEvictsServerAndCapsAreEnforced() = runTest {
        val hub = SharedConversationIdentityHub(maxServers = 2, maxCanonicalPerServer = 2)
        assertTrue(hub.publishCanonical(authority(server = "server-a"), dm("server-a", "dm-1", "One")))
        assertTrue(hub.publishCanonical(authority(server = "server-a"), dm("server-a", "dm-2", "Two")))
        assertTrue(hub.publishCanonical(authority(server = "server-a"), dm("server-a", "dm-3", "Three")))
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-1"), "least recent canonical row evicted")
        assertEquals("Three", hub.resolveProjection(ServerId("server-a"), "dm-3")?.title)

        assertTrue(hub.publishCanonical(authority(server = "server-b"), dm("server-b", "dm-b", "Bob")))
        assertTrue(hub.publishCanonical(authority(server = "server-c"), dm("server-c", "dm-c", "Cal")))
        assertNull(hub.resolveProjection(ServerId("server-a"), "dm-3"), "least recent server evicted")
        assertEquals("Bob", hub.resolveProjection(ServerId("server-b"), "dm-b")?.title)

        hub.dropServer("server-b")
        assertNull(hub.resolveProjection(ServerId("server-b"), "dm-b"))
        assertEquals("Cal", hub.resolveProjectionFor("account-1", ServerId("server-c"), "dm-c")?.title)
    }

    private fun SharedConversationIdentityHub.titleFrames(
        testScope: TestScope,
        server: String,
        id: String,
    ): MutableList<String?> {
        val frames = mutableListOf<String?>()
        testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
            projectionFlow(ServerId(server), listOf(id)).collect { frames += it[id]?.title }
        }
        return frames
    }

    private fun authority(
        server: String,
        authGeneration: Long = 1,
        selection: Long = 0,
        account: String = "account-1",
        environment: String = Env,
    ): SharedConversationIdentityAuthority =
        SharedConversationIdentityAuthority(
            environmentId = environment,
            accountId = account,
            serverId = server,
            authGeneration = authGeneration,
            selectionGeneration = selection,
        )

    private fun dm(server: String, id: String, name: String): SharedConversationDirectoryProjection =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.DirectMessage,
            name = name,
            directMessagePeerType = "human",
            presenceMemberId = MemberId("peer-$id"),
        ).toCanonicalConversationDirectoryProjection(ServerId(server))!!

    private companion object {
        const val Env = "https://api.example.test"
    }
}
