package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeRowHintPersistTest {
    @Test
    fun clearAllRemovesEveryHintWrittenOnThisDevice() {
        val store = InMemoryRaftKeyValueStore()
        val storage = HomeRowHintPersistStorage(store)
        storage.write("mvi:persist:home:hints:v1:user:a:server:1", "one")
        storage.write("mvi:persist:home:hints:v1:user:a:server:2", "two")
        storage.write("mvi:persist:home:hints:v1:user:a:server:2", "two again")
        store.putString("mvi:persist:home:identity:v1:user:a:server:1", "identity")

        storage.clearAll()

        assertNull(store.getString("mvi:persist:home:hints:v1:user:a:server:1"))
        assertNull(store.getString("mvi:persist:home:hints:v1:user:a:server:2"))
        assertEquals("identity", store.getString("mvi:persist:home:identity:v1:user:a:server:1"))
    }

    @Test
    fun aRemovedHintIsNotResurrectedByALaterClear() {
        val store = InMemoryRaftKeyValueStore()
        val storage = HomeRowHintPersistStorage(store)
        storage.write("k1", "one")
        storage.remove("k1")
        store.putString("k1", "written by someone else")

        storage.clearAll()

        assertEquals("written by someone else", store.getString("k1"), "removed keys leave the index")
    }

    @Test
    fun aKnownRowCountReplacesTheRestoredHintWhenPersisting() {
        val content =
            content(
                rows =
                    listOf(
                        row("read-meanwhile").copy(unreadCount = 0, unreadCountKnown = true),
                        row("still-unknown"),
                        row("live").copy(unreadCount = 2, unreadCountKnown = true, lastMessagePreview = "new")
                    ),
                hints =
                    mapOf(
                        "read-meanwhile" to HomeRowRestoredHint(unreadCount = 5),
                        "still-unknown" to HomeRowRestoredHint(unreadCount = 3, lastMessagePreview = "old"),
                        "live" to HomeRowRestoredHint(unreadCount = 7, lastMessagePreview = "older")
                    )
            )

        val hints = content.toRowHintsSnapshot().hints

        assertNull(hints["read-meanwhile"], "a read conversation must not come back unread on the next cold start")
        assertEquals(HomeRowRestoredHint(unreadCount = 3, lastMessagePreview = "old"), hints["still-unknown"])
        assertEquals(2, hints.getValue("live").unreadCount)
        assertEquals("new", hints.getValue("live").lastMessagePreview)
    }

    @Test
    fun persistedPreviewsAreBounded() {
        val content = content(rows = listOf(row("c").copy(lastMessagePreview = "x".repeat(5_000))))

        assertEquals(HomeRowHintPreviewMaxChars, content.toRowHintsSnapshot().hints.getValue("c").lastMessagePreview?.length)
    }

    @Test
    fun hintsAreOnlyDisplayedWhileHomeIsTheDurableCache() {
        val hints = mapOf("c" to HomeRowRestoredHint(unreadCount = 1))

        assertEquals(hints, content(rows = listOf(row("c")), hints = hints, durable = true).displayRowHints())
        assertEquals(emptyMap(), content(rows = listOf(row("c")), hints = hints, durable = false).displayRowHints())
    }

    private fun row(id: String): HomeChannelRow =
        HomeChannelRow(threadId = ThreadId(id), channelId = ChannelId(id), name = id)

    private fun content(
        rows: List<HomeChannelRow>,
        hints: Map<String, HomeRowRestoredHint> = emptyMap(),
        durable: Boolean = true
    ): SharedHomeContentState.Content {
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Server"),
                currentMember =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "User",
                        handle = "user",
                        kind = MemberKind.Human,
                        presence = Presence.Active
                    ),
                channels = rows,
                directMessages = emptyList(),
                unreadTotal = 0
            )
        return SharedHomeContentState.Content(
            snapshot = snapshot,
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.com", displayName = "User"),
                    accessToken = "a",
                    refreshToken = "r",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server", role = "member"))
                ),
            isDurableIdentityCache = durable,
            restoredRowHints = hints
        )
    }
}
