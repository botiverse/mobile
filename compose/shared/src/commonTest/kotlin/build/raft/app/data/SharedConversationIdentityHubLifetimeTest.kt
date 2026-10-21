package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun serverSwitchAtoBtoAKeepsEveryCanonicalIdentity() = runTest {
        val hub = SharedConversationIdentityHub()
        val dm2Frames = hub.titleFrames(this, "server-a", "dm-2")
        assertTrue(hub.publishCanonical(authority(server = "server-a", selection = 1), dm("server-a", "dm-1", "Cindy")))
        assertTrue(hub.publishCanonical(authority(server = "server-a", selection = 1), dm("server-a", "dm-2", "Dora")))
        // Switch to B (selection 2), open something there.
        assertTrue(hub.publishCanonical(authority(server = "server-b", selection = 2), dm("server-b", "dm-b", "Bob")))
        // A late A response from selection 1 after switching to B is still A data.
        assertTrue(hub.publishCanonical(authority(server = "server-a", selection = 1), dm("server-a", "dm-4", "Finn")))
        // Back to A (selection 3): opening dm-1 must not wipe A's other canonical rows.
        assertTrue(hub.publishCanonical(authority(server = "server-a", selection = 3), dm("server-a", "dm-1", "Cindy")))

        assertEquals("Dora", hub.resolveProjection(ServerId("server-a"), "dm-2")?.title)
        assertEquals("Finn", hub.resolveProjection(ServerId("server-a"), "dm-4")?.title)
        assertEquals("Bob", hub.resolveProjection(ServerId("server-b"), "dm-b")?.title)
        // Per-conversation ordering still holds: an older selection never overwrites a newer row.
        assertFalse(hub.publishCanonical(authority(server = "server-a", selection = 1), dm("server-a", "dm-1", "Stale")))
        assertEquals("Cindy", hub.resolveProjection(ServerId("server-a"), "dm-1")?.title)
        assertEquals(listOf<String?>(null, "Dora"), dm2Frames, "no handle-only / missing frame for dm-2")
    }

    @Test
    fun homeRebindKeepsSupplementedIdentityAndLaterPublicationsLand() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val hub = SharedConversationIdentityHub()
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        var homeName = "Home Cindy"
        val home =
            SharedHomeStore(
                authStore = authStore,
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = authStore,
                        api = RootEmptyHomeApi,
                        parseHome = { _, _ -> DataResult.Content(homeSnapshot(homeName)) },
                    ),
                scope = storeScope,
                conversationIdentityPublicationSink = { publication ->
                    hub.publishCandidate(
                        authority = authority(server = publication.serverId.value, account = publication.accountId),
                        revision = publication.revision,
                        next = publication.state,
                    )
                },
            )
        // Cold start: Activity restores and supplements a reply thread whose parent is a DM; the
        // parent title is the peer's name, not the DM channel's raw handle.
        home.supplementConversationIdentity(
            serverId = ServerId("server-1"),
            source = SharedConversationIdentitySupplementSource.Activity,
            items = listOf(threadInDm()),
            dmPeerNames = mapOf("dm-9" to "Ivy"),
        )
        val parentFrames = hub.titleFrames(this, "server-1", "dm-9")

        home.accept(SharedHomeIntent.Start) // first bind of server-1

        assertEquals("Ivy", hub.resolveProjection(ServerId("server-1"), "dm-9")?.title)
        assertEquals("Home Cindy", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)

        // Sign-out-free rebind (store reset, e.g. session reset UI) keeps the hub's rows; Home's next
        // publication must still be accepted, so its revision cannot restart below the hub's.
        home.reset()
        homeName = "Home Cindy L"
        home.accept(SharedHomeIntent.Start)
        assertEquals("Home Cindy L", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)
        assertEquals(listOf<String?>(null, "Ivy"), parentFrames.take(2))
        assertTrue(parentFrames.drop(1).dropLast(1).all { it == "Ivy" }, "no handle frame before the reset: $parentFrames")
        storeScope.cancel()
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

    private fun homeSnapshot(dmName: String): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember =
                MemberProfile(id = MemberId("user-1"), displayName = "Artin", handle = "artin", kind = MemberKind.Human),
            channels = emptyList(),
            directMessages =
                listOf(
                    HomeDirectMessageRow(
                        threadId = ThreadId("dm-1"),
                        channelId = ChannelId("dm-1"),
                        name = dmName,
                        directMessagePeerType = "human",
                        presenceMemberId = MemberId("peer-1"),
                    )
                ),
            unreadTotal = 0,
        )

    private fun threadInDm(): InboxItem =
        InboxItem(
            key = "thread-1",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-1"),
            channelName = "ivy-handle",
            threadChannelId = ChannelId("thread-1"),
            parentChannelId = ChannelId("dm-9"),
            parentChannelName = "ivy-handle",
            parentChannelKind = ConversationKind.DirectMessage,
        )

    private companion object {
        const val Env = "https://api.example.test"
    }
}
