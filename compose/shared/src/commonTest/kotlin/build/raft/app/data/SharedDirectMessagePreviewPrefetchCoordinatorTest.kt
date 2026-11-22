package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedDirectMessagePreviewPrefetchCoordinatorTest {

    private fun testMessage(
        id: String = "msg-1",
        threadId: String = "dm-1",
        body: String = "hello there",
        authorName: String = "Alice",
        timestamp: String = "2026-03-29T10:00:00Z"
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(threadId),
            body = body,
            timestampLabel = timestamp,
            author = MemberProfile(
                id = MemberId("author-1"),
                displayName = authorName,
                handle = "alice",
                kind = MemberKind.Human,
                avatar = null,
            )
        )

    private fun testDmRow(
        id: String,
        preview: String? = null,
        senderName: String? = null
    ): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = "DM $id",
            lastMessagePreview = preview,
            lastMessageSenderName = senderName,
        )

    @Test
    fun normalizeMessagePreviewKeepsMarkdownForTheRowToFlatten() {
        assertNull(normalizeMessagePreview("   \n\t  "))
        // Cutting Markdown at 140 could split `**`; the row flattens, then truncates.
        assertEquals("**hello**\n\t world", normalizeMessagePreview("  **hello**\n\t world  "))
        assertEquals(2_048, normalizeMessagePreview("a".repeat(3_000))?.length)
    }

    @Test
    fun dispatchesHydrateIntentOnSuccessfulMessageFetch() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val dispatched = mutableListOf<SharedHomeIntent>()
        val coordinator = SharedDirectMessagePreviewPrefetchCoordinator(
            fetchLatestMessage = { cid ->
                DataResult.Content(testMessage(body = "Latest text in channel", authorName = "Bob"))
            },
            dispatchHome = { dispatched.add(it) }
        )

        coordinator.enqueue(
            serverId = ServerId("server-1"),
            accountId = "acc-1",
            directMessages = listOf(testDmRow("dm-1")),
            scope = testScope
        )

        assertEquals(1, dispatched.size)
        val intent = dispatched.first() as SharedHomeIntent.HydrateDirectMessagePreview
        assertEquals(ServerId("server-1"), intent.serverId)
        assertEquals(ChannelId("dm-1"), intent.channelId)
        assertEquals("Latest text in channel", intent.preview)
        assertEquals("Bob", intent.senderName)
        assertEquals("2026-03-29T10:00:00Z", intent.lastMessageAt)
        assertEquals(MessageId("msg-1"), intent.lastMessageId)
        assertEquals("acc-1", intent.accountId)
    }
}
