package build.raft.app.data

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedHomeOpenedDirectMessageProjectionTest {
    @Test
    fun openedDmPublishesDirectoryCandidateWithoutWritingHomeProductState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store = openedDmStore(authStore, scope)

        assertTrue(
            store.supplementOpenedDirectMessage(
                authority = openedDmAuthority(),
                conversation = openedDm("dm-new", "Artin", "user-1")
            )
        )

        val projection = store.resolveProjection(ServerId("server-1"), "dm-new")
        assertEquals("Artin", projection?.title)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, projection?.surfaceKind)
        assertEquals(MemberId("user-1"), projection?.peerIdentity?.memberId)
        assertNull(
            store.snapshotFor(ServerId("server-1")),
            "The POST response seeds identity only; Home product state still comes from Home load"
        )
        scope.cancel()
    }

    @Test
    fun openedDmCandidatesAccumulateAndHomeRemainsCanonical() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store =
            openedDmStore(
                authStore = authStore,
                scope = scope,
                homeSnapshot =
                    HomeSnapshot(
                        server = ServerSummary(ServerId("server-1"), "One"),
                        currentMember = currentMember(),
                        channels = emptyList(),
                        directMessages = listOf(openedDm("dm-1", "Canonical one", "peer-1")),
                        unreadTotal = 0
                    )
            )

        assertTrue(
            store.supplementOpenedDirectMessage(
                authority = openedDmAuthority(),
                conversation = openedDm("dm-1", "Candidate one", "peer-1")
            )
        )
        assertTrue(
            store.supplementOpenedDirectMessage(
                authority = openedDmAuthority(),
                conversation = openedDm("dm-2", "Candidate two", "peer-2")
            )
        )
        assertEquals("Candidate one", store.resolveProjection(ServerId("server-1"), "dm-1")?.title)
        assertEquals("Candidate two", store.resolveProjection(ServerId("server-1"), "dm-2")?.title)

        store.accept(SharedHomeIntent.Start)

        assertEquals(
            "Canonical one",
            store.resolveProjection(ServerId("server-1"), "dm-1")?.title,
            "Home must override the earlier POST candidate for the same canonical id"
        )
        assertEquals(
            "Candidate two",
            store.resolveProjection(ServerId("server-1"), "dm-2")?.title,
            "A newly opened empty DM may remain absent from Home until its first message"
        )
        scope.cancel()
    }

    @Test
    fun openedDmRejectsAccountAndServerDrift() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store = openedDmStore(authStore, scope)
        val mismatches =
            listOf(
                OpenedDirectMessageAuthority(ServerId("server-1"), MemberId("other-user")),
                OpenedDirectMessageAuthority(ServerId("server-2"), MemberId("user-1"))
            )

        mismatches.forEachIndexed { index, authority ->
            val id = "dm-rejected-$index"
            assertFalse(
                store.supplementOpenedDirectMessage(
                    authority = authority,
                    conversation = openedDm(id, "Rejected", "peer-$index")
                )
            )
            assertNull(store.resolveProjection(authority.serverId, id))
        }

        authStore.saveSession(authSessionFixture(userId = "successor-user"))
        assertFalse(
            store.supplementOpenedDirectMessage(
                authority = openedDmAuthority(),
                conversation = openedDm("dm-stale-flight", "Stale", "peer-stale")
            )
        )
        assertNull(store.resolveProjection(ServerId("server-1"), "dm-stale-flight"))
        scope.cancel()
    }

    @Test
    fun successorAccountOnSameServerDoesNotInheritEarlierDirectoryRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store = openedDmStore(authStore, scope)
        assertTrue(
            store.supplementOpenedDirectMessage(
                authority = openedDmAuthority(),
                conversation = openedDm("dm-old", "Old account peer", "peer-old"),
            )
        )

        authStore.saveSession(authSessionFixture(userId = "successor-user"))
        assertTrue(
            store.supplementOpenedDirectMessage(
                authority =
                    OpenedDirectMessageAuthority(
                        serverId = ServerId("server-1"),
                        accountId = MemberId("successor-user"),
                    ),
                conversation = openedDm("dm-new", "New account peer", "peer-new"),
            )
        )

        assertNull(store.resolveProjection(ServerId("server-1"), "dm-old"))
        assertEquals(
            "New account peer",
            store.resolveProjection(ServerId("server-1"), "dm-new")?.title,
        )
        scope.cancel()
    }

    private fun openedDmStore(
        authStore: RaftAuthSessionStore,
        scope: CoroutineScope,
        homeSnapshot: HomeSnapshot? = null
    ): SharedHomeStore =
        SharedHomeStore(
            authStore = authStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = authStore,
                    api = RootEmptyHomeApi,
                    parseHome = { _, _ ->
                        homeSnapshot?.let { DataResult.Content(it) }
                            ?: DataResult.Empty(EmptyReason.NoChannels)
                    }
                ),
            scope = scope
        )

    private fun openedDmAuthority(): OpenedDirectMessageAuthority =
        OpenedDirectMessageAuthority(
            serverId = ServerId("server-1"),
            accountId = MemberId("user-1")
        )

    private fun openedDm(id: String, name: String, peerId: String): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = name,
            directMessagePeerType = "human",
            presenceMemberId = MemberId(peerId)
        )

    private fun currentMember(): MemberProfile =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin",
            kind = MemberKind.Human
        )

    private object RootEmptyHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "[]")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "[]")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "{}")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "[]")
    }
}
