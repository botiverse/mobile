package build.raft.app.data

import build.raft.app.model.InboxItemKind
import build.raft.app.model.ThreadId
import build.raft.app.reportUnknownTypeDropped
import build.raft.app.reportRealtimeEventDropped
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Silent-drop audit, systemic rules: a server value or row the app can't read is never dropped
 * without a trace; one bad row never takes its list with it; a page that can't be read at all is
 * an error, not "no messages" / an empty roster.
 */
class NoSilentDropsTest {
    @Test
    fun unknownTypeDiagnosticPassesTheRealtimeFilterAndSanitisesValues() {
        val lines = mutableListOf<String>()
        reportUnknownTypeDropped("Inbox", "inbox_kind", "New Kind!", diagnostics = lines::add)
        reportUnknownTypeDropped("saved", "channel_type", null, fallback = "channel_row", diagnostics = lines::add)
        assertEquals(
            listOf(
                "projection_value_unknown source=inbox field=inbox_kind value=new_kind_ action=dropped",
                "projection_value_unknown source=saved field=channel_type value=blank action=fallback_channel_row",
            ),
            lines,
        )
    }

    @Test
    fun realtimeDropLineHasNameReasonAndKeysOnly() {
        val lines = mutableListOf<String>()
        reportRealtimeEventDropped("message:new", "decode", listOf("id", "senderId"), lines::add)
        assertEquals(listOf("realtime_event_dropped eventName=message:new reason=decode keys=id,senderid"), lines)
    }

    @Test
    fun inboxRowOfAnUnknownKindIsAGenericChannelRow() {
        val logs = mutableListOf<String>()
        val snapshot =
            parseSharedInboxSnapshot(
                """{"items":[{"kind":"poll","channelId":"c-1","channelName":"general","channelType":"channel","unreadCount":2}],"hasMore":false}""",
                logs::add,
            )
        val item = assertNotNull(snapshot).items.single()
        assertEquals(InboxItemKind.Channel, item.kind)
        assertEquals("c-1", item.channelId.value)
        assertEquals(2, item.unreadCount)
        assertTrue(logs.contains("projection_value_unknown source=inbox field=inbox_kind value=poll action=fallback_channel_row"), "logs=$logs")
    }

    @Test
    fun inboxRowOfAnUnknownKindWithoutAChannelIsDroppedButLogged() {
        val logs = mutableListOf<String>()
        val snapshot = parseSharedInboxSnapshot("""{"items":[{"kind":"poll"}],"hasMore":false}""", logs::add)
        assertTrue(assertNotNull(snapshot).items.isEmpty())
        assertEquals(1, snapshot.rawItemCount)
        assertTrue(logs.contains("projection_value_unknown source=inbox field=inbox_kind value=poll action=dropped"), "logs=$logs")
    }

    @Test
    fun oneUnreadableMessageRowIsSkippedNotThePage() {
        val logs = mutableListOf<String>()
        val payload =
            parseSharedChannelMessages(
                """{"messages":[${message("m1", 1)},${badMessage("m2", 2)},${message("m3", 3)}],"historyLimited":false}""",
                ThreadId("channel-1"),
                logs::add,
            )
        assertEquals(listOf("m1", "m3"), payload.messages.map { it.id.value })
        assertEquals(3, payload.rawMessageCount)
        assertFalse(payload.decodeFailed)
        assertTrue(logs.contains("projection_value_rejected source=rest_messages_channel field=row value=decode_failed index=1"), "logs=$logs")
    }

    @Test
    fun contextAndSyncPagesAreAlsoReadPerRow() {
        val context =
            parseSharedMessageContext(
                """{"channelId":"channel-1","messages":[${badMessage("m1", 1)},${message("m2", 2)}],"hasOlder":false,"hasNewer":false}""",
                ThreadId("channel-1"),
            )
        assertEquals(listOf("m2"), context.messages.map { it.id.value })
        assertEquals(2, context.rawMessageCount)

        val sync = parseSharedSyncMessages("""[${message("m1", 1)},${badMessage("m2", 2)}]""", ThreadId("channel-1"))
        assertEquals(listOf("m1"), sync.messages.map { it.id.value })
        assertEquals(2, sync.rawMessageCount)
    }

    @Test
    fun anUnreadablePageIsAFailureNotNoMessages() {
        val logs = mutableListOf<String>()
        val notAnObject = parseSharedChannelMessages("""[1,2]""", ThreadId("channel-1"), logs::add)
        assertTrue(notAnObject.decodeFailed)
        val badEnvelope = parseSharedChannelMessages("""{"messages":[],"historyLimited":"nope"}""", ThreadId("channel-1"), logs::add)
        assertTrue(badEnvelope.decodeFailed)
        val messagesNotArray = parseSharedMessageContext("""{"messages":{}}""", ThreadId("channel-1"), logs::add)
        assertTrue(messagesNotArray.decodeFailed)
        val syncNotArray = parseSharedSyncMessages("""{"messages":[]}""", ThreadId("channel-1"), logs::add)
        assertTrue(syncNotArray.decodeFailed)
        assertEquals(4, logs.count { it.startsWith("messages_page_decode_failed") }, "logs=$logs")
        // A real empty page stays empty (not a failure).
        assertFalse(parseSharedChannelMessages("""{"messages":[]}""", ThreadId("channel-1")).decodeFailed)
    }

    @Test
    fun repositoryTurnsAnUnreadablePageIntoAnError() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET messages/channel/channel-1" to """{"messages":"oops"}"""),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)
        val result = repository.loadNewerMessages(ThreadId("channel-1"), afterSeq = 0, limit = 20)
        val error = assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", error.error.code)
    }

    @Test
    fun loadNewerKeepsPagingWhenARowWasSkipped() = runBlocking {
        // Two server rows for a limit of two, one unreadable: there may be more after them.
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to """{"messages":[${message("m7", 7)},${badMessage("m8", 8)}]}""",
                        "GET messages/sync" to """[${message("m7", 7)},${badMessage("m8", 8)}]""",
                    ),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val newer = assertIs<DataResult.Content<ThreadSnapshot>>(repository.loadNewerMessages(ThreadId("channel-1"), afterSeq = 6, limit = 2)).data
        assertEquals(1, newer.messages.size)
        assertTrue(newer.canLoadMoreAfter)

        val synced = assertIs<DataResult.Content<ThreadSnapshot>>(repository.syncNewerMessages(ThreadId("channel-1"), sinceSeq = 6, limit = 2)).data
        assertTrue(synced.canLoadMoreAfter)
    }

    @Test
    fun oneUnreadableRosterRowIsSkippedNotTheRoster() {
        val members =
            parseSharedSettingsAgentsAsMembers(
                """[{"id":"agent-1","name":"builder","status":"active"},{"id":"agent-2"},{"id":"agent-3","name":"reviewer","status":"active"}]""",
            )
        assertEquals(listOf("agent-1", "agent-3"), assertNotNull(members).map { it.id.value })
        // Same for the wrapped shapes the parser has always accepted.
        val wrapped = parseSharedSettingsAgentsAsMembers("""{"agents":[{"id":"agent-1","name":"builder"},{"name":"no id"}]}""")
        assertEquals(listOf("agent-1"), assertNotNull(wrapped).map { it.id.value })
    }

    @Test
    fun aRosterWhereNoRowReadsIsAnErrorNotEmpty() {
        assertNull(parseSharedSettingsAgentsAsMembers("""[{"id":"agent-1"},{"name":"no id"}]"""))
        // A genuinely empty roster is still empty.
        assertEquals(emptyList(), parseSharedSettingsAgentsAsMembers("[]"))
        assertEquals(emptyList(), parseSharedSettingsAgentsAsMembers("""{"agents":[]}"""))
    }

    private fun message(id: String, seq: Long): String =
        """{"id":"$id","seq":$seq,"channelId":"channel-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"hi","createdAt":"2026-05-22T10:00:00.000Z"}"""

    /** An attachment without its required `id`: the row does not decode. */
    private fun badMessage(id: String, seq: Long): String =
        """{"id":"$id","seq":$seq,"channelId":"channel-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"hi","createdAt":"2026-05-22T10:00:00.000Z","attachments":[{"filename":"x.png"}]}"""
}
