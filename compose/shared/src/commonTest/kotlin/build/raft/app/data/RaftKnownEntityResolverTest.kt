package build.raft.app.data

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
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
import kotlin.test.assertNull

class RaftKnownEntityResolverTest {
    private val scope = KnownEntityScope(accountId = "user-1", serverId = ServerId("server-1"))

    @Test
    fun firstIdentityMatchingSourceWins() {
        val resolver =
            RaftKnownEntityResolver(
                listOf(
                    KnownConversationSource { _, _ -> null },
                    KnownConversationSource { _, id -> channel(id = id, title = "general") },
                    KnownConversationSource { _, id -> channel(id = id, title = "later source") },
                )
            )

        assertEquals("general", resolver.conversation(scope, listOf("", "channel-1"))?.title)
    }

    @Test
    fun rowsThatDoNotMatchTheRouteIdentityAreDiscarded() {
        val cases =
            listOf(
                "other server" to channel(id = "channel-1", title = "general", serverId = "server-2"),
                "other channel" to channel(id = "channel-2", title = "random"),
            )
        cases.forEach { (label, row) ->
            val resolver = RaftKnownEntityResolver(listOf(KnownConversationSource { _, _ -> row }))
            assertNull(resolver.conversation(scope, listOf("channel-1")), label)
        }
        val blankAccount = scope.copy(accountId = " ")
        val resolver = RaftKnownEntityResolver(listOf(KnownConversationSource { _, id -> channel(id, "general") }))
        assertNull(resolver.conversation(blankAccount, listOf("channel-1")), "blank account")
    }

    @Test
    fun homeStoreRowsAreKnownOnlyForTheirAccountAndServer() = runBlocking {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store = homeStore(authStore, storeScope, homeSnapshot())
        store.accept(SharedHomeIntent.Start)
        val resolver = raftKnownEntityResolver(identityHub = { null }, homeStore = { store })

        val known = resolver.conversation(scope, listOf("channel-1"))
        assertEquals("general", known?.title)
        assertEquals("Ship it", known?.subtitle)
        assertNull(resolver.conversation(scope.copy(accountId = "user-2"), listOf("channel-1")), "other account")
        assertNull(resolver.conversation(scope.copy(serverId = ServerId("server-2")), listOf("channel-1")), "other server")
        storeScope.cancel()
    }

    @Test
    fun homeStoreRowSurvivesAnIdentityHubResetOnAuthRotation() = runBlocking {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store = homeStore(authStore, storeScope, homeSnapshot())
        store.accept(SharedHomeIntent.Start)
        val hub = SharedConversationIdentityHub()
        val authority =
            SharedConversationIdentityAuthority(
                environmentId = "https://api.example.test",
                accountId = "user-1",
                serverId = "server-1",
                authGeneration = 1,
            )
        hub.publishCandidate(authority, revision = 1, next = SharedConversationDirectoryState())
        // A same-account token rotation publishes destination detail for another channel and
        // wipes the hub's Home rows.
        hub.publishCanonical(authority.copy(authGeneration = 2), channel(id = "channel-9", title = "other"))
        assertNull(hub.resolveProjection(ServerId("server-1"), "channel-1"))

        val resolver = raftKnownEntityResolver(identityHub = { hub }, homeStore = { store })

        assertEquals("general", resolver.conversation(scope, listOf("channel-1"))?.title)
        assertEquals("other", resolver.conversation(scope, listOf("channel-9"))?.title, "hub canonical row still wins")
        storeScope.cancel()
    }

    private fun channel(
        id: String,
        title: String,
        serverId: String = "server-1",
    ): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = serverId,
            canonicalChannelId = id,
            aliases = setOf(id),
            conversationKind = ConversationKind.Channel,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
            title = title,
        )

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human,
                ),
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = "general",
                        subtitle = "Ship it",
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0,
        )

    private fun homeStore(
        authStore: RaftAuthSessionStore,
        scope: CoroutineScope,
        homeSnapshot: HomeSnapshot,
    ): SharedHomeStore =
        SharedHomeStore(
            authStore = authStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = authStore,
                    api = RootEmptyHomeApi,
                    parseHome = { _, _ -> DataResult.Content(homeSnapshot) },
                ),
            scope = scope,
        )
}
