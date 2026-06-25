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
import kotlin.test.assertNull
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
    fun freshGenericChannelSummaryDoesNotOverwriteStoredJointTitleOrKind() = runBlocking {
        val store = createStore()

        store.upsertConversation(
            serverId = serverId,
            conversation = jointSummary(id = "joint-1", name = "raft-mobile"),
            source = SharedConversationDirectorySource.RouteSeed
        )
        store.upsertConversation(
            serverId = serverId,
            conversation =
                channelSummary(
                    id = "joint-1",
                    name = "Channel",
                    channelType = "channel"
                ),
            source = SharedConversationDirectorySource.FreshApi
        )

        val projection = assertNotNull(store.resolveProjection(serverId, "joint-1"))
        assertEquals("raft-mobile", projection.title)
        assertEquals("joint", projection.channelKind)
    }

    @Test
    fun freshGenericChannelSummaryStoresIdentityPlaceholderWhenTitleIsUnknown() = runBlocking {
        val store = createStore()

        store.upsertConversation(
            serverId = serverId,
            conversation = channelSummary(id = "channel-1", name = "Channel"),
            source = SharedConversationDirectorySource.FreshApi
        )

        val projection = assertNotNull(store.resolveProjection(serverId, "channel-1"))
        assertEquals("channel-1", projection.title)
        assertEquals("channel", projection.channelKind)
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
    fun channelReplyMetadataDoesNotCreateReplyThreadDirectoryAliases() = runBlocking {
        val store = createStore()

        store.upsertConversation(
            serverId = serverId,
            conversation =
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    kind = ConversationKind.Channel,
                    name = "general",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("parent-1"),
                            streamChannelId = ChannelId("reply-thread-1")
                        )
                ),
            source = SharedConversationDirectorySource.FreshApi
        )

        assertEquals("general", assertNotNull(store.resolveProjection(serverId, "channel-1")).title)
        assertNull(store.resolveProjection(serverId, "reply-thread-1"))
        assertNull(store.resolveProjection(serverId, "parent-1"))
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
    fun upsertConversationDeletesStaleUniqueAliasesForCanonicalThread() = runBlocking {
        val logs = mutableListOf<String>()
        val database = createDatabase()
        val store = SharedConversationDirectoryStore(database, clock = { 1234L }, logger = logs::add)

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
        database.queries.upsertConversationAlias(
            serverId = serverId.value,
            aliasId = "parent-channel",
            aliasKind = SharedConversationAliasKind.Channel.name,
            canonicalChannelId = "reply-stream-1",
            lastSyncedAtMs = 1L
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
        return SharedConversationDirectoryStore(createDatabase(), clock = { 1234L }, logger = logger)
    }

    private fun createDatabase(): SlockSqlDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockSqlDatabase.Schema.create(driver).value
        return SlockSqlDatabase(driver)
    }

    private fun channelSummary(
        id: String,
        name: String,
        channelType: String? = null
    ): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            channelType = channelType,
            kind = ConversationKind.Channel,
            name = name
        )

    private fun jointSummary(id: String, name: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            channelType = "joint",
            kind = ConversationKind.Joint,
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
