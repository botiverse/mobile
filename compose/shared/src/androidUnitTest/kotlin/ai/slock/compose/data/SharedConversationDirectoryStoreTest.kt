package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedConversationDirectoryStoreTest {
    @Test
    fun weakSeedDoesNotOverwriteFreshChannelTitle() = runBlocking {
        val store = createStore()

        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "channel-1", name = "general"),
            source = SharedConversationDirectorySource.FreshApi
        )
        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "channel-1", name = "channel-1"),
            source = SharedConversationDirectorySource.DenormalizedSeed
        )

        val projection = assertNotNull(store.resolveProjection(serverId, "channel-1"))
        assertEquals("general", projection.title)
    }

    @Test
    fun aliasesResolveToCanonicalChannelProjection() = runBlocking {
        val store = createStore()

        store.upsertConversation(
            serverId = serverId,
            conversation =
                ConversationSummary(
                    threadId = ThreadId("dm-channel-1"),
                    channelId = ChannelId("dm-channel-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Codex Agent",
                    directMessagePeerType = "agent",
                    presenceMemberId = MemberId("agent-1")
                ),
            source = SharedConversationDirectorySource.FreshApi
        )

        assertEquals("Codex Agent", assertNotNull(store.resolveProjection(serverId, "dm-channel-1")).title)
        assertEquals("Codex Agent", assertNotNull(store.resolveProjection(serverId, "agent-1")).title)
    }

    @Test
    fun parentChannelAliasesDoNotConflictOrResolveToChildThreads() = runBlocking {
        val logs = mutableListOf<String>()
        val store = createStore(logger = logs::add)

        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "parent-channel", name = "all"),
            source = SharedConversationDirectorySource.FreshApi
        )
        store.upsertConversation(
            serverId = serverId,
            conversation = replyThreadSummary(id = "reply-stream-1", parentChannelId = "parent-channel", name = "Thread one"),
            source = SharedConversationDirectorySource.FreshApi
        )
        store.upsertConversation(
            serverId = serverId,
            conversation = replyThreadSummary(id = "reply-stream-2", parentChannelId = "parent-channel", name = "Thread two"),
            source = SharedConversationDirectorySource.FreshApi
        )

        val state = store.observeServer(serverId).first()
        assertEquals(emptyMap(), state.duplicateAliases)
        assertTrue(logs.none { it.contains("conversation_directory_alias_conflict") })
        assertEquals("all", assertNotNull(store.resolveProjection(serverId, "parent-channel")).title)
        assertEquals("Thread one", assertNotNull(store.resolveProjection(serverId, "reply-stream-1")).title)
        assertEquals("Thread two", assertNotNull(store.resolveProjection(serverId, "reply-stream-2")).title)
    }

    @Test
    fun replyThreadParentChannelIdDoesNotBecomeUniqueAliasWhenApiEchoesParentChannel() = runBlocking {
        val logs = mutableListOf<String>()
        val store = createStore(logger = logs::add)

        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "parent-channel", name = "all"),
            source = SharedConversationDirectorySource.FreshApi
        )
        store.upsertConversation(
            serverId = serverId,
            conversation =
                replyThreadSummary(
                    id = "reply-stream-1",
                    parentChannelId = "parent-channel",
                    name = "Thread one",
                    summaryChannelId = "parent-channel"
                ),
            source = SharedConversationDirectorySource.FreshApi
        )

        val state = store.observeServer(serverId).first()
        assertEquals(emptyMap(), state.duplicateAliases)
        assertTrue(logs.none { it.contains("conversation_directory_alias_conflict") })
        assertEquals("all", assertNotNull(store.resolveProjection(serverId, "parent-channel")).title)
        assertEquals("Thread one", assertNotNull(store.resolveProjection(serverId, "reply-stream-1")).title)
    }

    @Test
    fun projectionFlowEmitsWhenChannelEntityChanges() = runBlocking {
        val store = createStore()
        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "channel-1", name = "general"),
            source = SharedConversationDirectorySource.FreshApi
        )

        val updated =
            async {
                store.projectionFlow(serverId, listOf("channel-1"))
                    .filter { projection -> projection["channel-1"]?.title == "renamed" }
                    .first()
            }

        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "channel-1", name = "renamed"),
            source = SharedConversationDirectorySource.RealtimeMutation
        )

        assertEquals("renamed", updated.await()["channel-1"]?.title)
    }

    private fun createStore(logger: (String) -> Unit = {}): SharedConversationDirectoryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockSqlDatabase.Schema.create(driver).value
        return SharedConversationDirectoryStore(SlockSqlDatabase(driver), clock = { 1234L }, logger = logger)
    }

    private fun channelSummary(id: String, name: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = name
        )

    private fun replyThreadSummary(
        id: String,
        parentChannelId: String,
        name: String,
        summaryChannelId: String = id
    ): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(summaryChannelId),
            thread =
                ThreadConversation(
                    parentChannelId = ChannelId(parentChannelId),
                    parentMessageId = MessageId("message-$id"),
                    streamChannelId = ChannelId(id)
                ),
            kind = ConversationKind.Thread,
            name = name
        )

    private companion object {
        val serverId = ServerId("server-1")
    }
}
