package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxSavedSearchRepositoryDataSourceTest {
    @Test
    fun inboxRepositoryMapsPagingMetadataAndStableThreadKeys() = runBlocking {
        val network =
            FakeInboxNetworkClient(
                inboxJson = """
                    {
                      "items": [
                        {"kind":"thread","channelId":"thread-1","channelName":"Thread","threadChannelId":"thread-1","parentChannelId":"channel-1","parentChannelName":"android-dev","unreadCount":4,"replyCount":7,"taskStatus":"in_review"}
                      ],
                      "hasMore": true,
                      "totalCount": 12,
                      "totalUnreadCount": 4
                    }
                """.trimIndent()
            )
        val repository = RealInboxRepository(sampleStore(), network)

        val result = repository.loadMore(ServerId("server-1"), InboxFilter.Unread, offset = 30, limit = 30)

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals(true, snapshot.hasMore)
        assertEquals(31, snapshot.nextOffset)
        assertEquals(12, snapshot.totalCount)
        assertEquals(4, snapshot.totalUnreadCount)
        assertEquals("thread:thread-1", snapshot.items.single().key)
        assertEquals(InboxItemKind.Thread, snapshot.items.single().kind)
        assertEquals(
            listOf(InboxCall(filter = "unread", limit = 30, offset = 30)),
            network.inboxCalls
        )
    }

    @Test
    fun inboxRepositoryMapsRealThreadItemShape() = runBlocking {
        val repository =
            RealInboxRepository(
                sampleStore(),
                FakeInboxNetworkClient(
                    inboxJson = """
                        {
                          "items": [
                            {
                              "kind": "thread",
                              "threadChannelId": "thread-1",
                              "parentMessageId": "parent-message-1",
                              "parentChannelId": "channel-1",
                              "parentChannelName": "mardown专修",
                              "parentChannelType": "channel",
                              "parentMessagePreview": "inline code 也有这个问题",
                              "parentMessageSenderType": "human",
                              "parentMessageSenderId": "user-1",
                              "latestActivityPreview": "收到，我会修 inline code",
                              "latestActivitySenderType": "agent",
                              "latestActivitySenderId": "agent-1",
                              "latestActivityMessageId": "reply-message-1",
                              "firstUnreadMessageId": null,
                              "lastActivityAt": "2026-05-10T21:26:00+08:00",
                              "lastReplyAt": "2026-05-10T21:26:00+08:00",
                              "replyCount": 8,
                              "unreadCount": 1,
                              "taskNumber": null,
                              "taskStatus": null
                            }
                          ],
                          "hasMore": true,
                          "totalCount": 30,
                          "totalUnreadCount": 4
                        }
                    """.trimIndent()
                )
            )

        val result = repository.loadInitial(ServerId("server-1"))

        assertTrue(result is DataResult.Content)
        val item = result.data.items.single()
        assertEquals(InboxItemKind.Thread, item.kind)
        assertEquals("thread-1", item.threadChannelId?.value)
        assertEquals("channel-1", item.parentChannelId?.value)
        assertEquals(ConversationKind.Channel, item.parentChannelKind)
        assertEquals("inline code 也有这个问题", item.parentMessagePreview)
        assertEquals("收到，我会修 inline code", item.latestActivityPreview)
        assertEquals("2026-05-10T21:26:00+08:00", item.latestActivityAt)
        assertEquals("agent-1", item.latestActivitySenderId)
        assertEquals(MemberKind.Agent, item.latestActivitySenderKind)
        assertEquals(8, item.replyCount)
    }

    @Test
    fun inboxRepositoryMapsMentionsFilterAndHasMentionFlag() = runBlocking {
        val network =
            FakeInboxNetworkClient(
                inboxJson = """
                    {
                      "items": [
                        {
                          "kind": "channel",
                          "channelId": "channel-mention-1",
                          "channelName": "android-dev",
                          "unreadCount": 2,
                          "hasMention": true,
                          "lastMessagePreview": "@artin can you check this?"
                        }
                      ]
                    }
                """.trimIndent()
            )
        val repository = RealInboxRepository(sampleStore(), network)

        val result = repository.loadInitial(ServerId("server-1"), filter = InboxFilter.Mentions)

        assertTrue(result is DataResult.Content)
        val item = result.data.items.single()
        assertEquals("channel-mention-1", item.channelId.value)
        assertTrue(item.hasMention)
        assertEquals("mentions", network.inboxCalls.single().filter)
    }

    @Test
    fun savedAndSearchMapItems() = runBlocking {
        val network =
            FakeInboxNetworkClient(
                savedJson = """{"saved":[{"messageId":"message-1","channelId":"channel-1","channelName":"android-dev","channelType":"channel","content":"Saved note","senderType":"user","senderId":"user-1","senderName":"Artin","createdAt":"2026-05-01T00:00:00Z","savedAt":"2026-05-02T00:00:00Z"}],"hasMore":false}""",
                searchJson = """{"results":[{"id":"message-2","seq":1,"channelId":"channel-2","channelName":"product","channelType":"channel","parentChannelId":"channel-2","parentChannelName":"product","parentChannelType":"channel","senderId":"user-1","senderType":"user","senderName":"Artin","content":"Search hit","snippet":"Search hit","createdAt":"2026-05-01T00:00:00Z"}],"hasMore":false}"""
            )
        val store = sampleStore()

        val saved = RealSavedRepository(store, network).loadInitial(ServerId("server-1"))
        val search = RealSearchRepository(store, network).search(ServerId("server-1"), "query")

        assertTrue(saved is DataResult.Content)
        assertEquals("Saved note", saved.data.items.single().lastMessagePreview)
        assertEquals("user-1", saved.data.items.single().lastMessageSenderId)
        assertEquals(MemberKind.Human, saved.data.items.single().lastMessageSenderKind)
        assertTrue(search is DataResult.Content)
        assertEquals("Search hit", search.data.items.single().lastMessagePreview)
        assertEquals("user-1", search.data.items.single().lastMessageSenderId)
        assertEquals(MemberKind.Human, search.data.items.single().lastMessageSenderKind)
    }

    @Test
    fun blankSearchWithoutFiltersReturnsEmptyWithoutServerRequest() = runBlocking {
        val network = FakeInboxNetworkClient(searchJson = """{"results":[]}""")

        val blank = RealSearchRepository(sampleStore(), network).search(ServerId("server-1"), " ")

        assertTrue(blank is DataResult.Empty)
        assertEquals(emptyList(), network.searchCalls)
    }

    @Test
    fun blankSearchWithFiltersCallsMessagesSearch() = runBlocking {
        val network = FakeInboxNetworkClient(searchJson = """{"results":[]}""")

        val result =
            RealSearchRepository(sampleStore(), network).search(
                serverId = ServerId("server-1"),
                query = " ",
                options =
                    SearchQueryOptions(
                        senderId = "user-1",
                        channelId = ChannelId("channel-1"),
                        afterIso = "2026-05-14T16:00:00.000Z"
                    )
            )

        assertTrue(result is DataResult.Empty)
        assertEquals(
            listOf(
                SearchCall(
                    query = "",
                    limit = 30,
                    offset = 0,
                    sort = null,
                    senderId = "user-1",
                    channelId = "channel-1",
                    after = "2026-05-14T16:00:00.000Z",
                    before = null
                )
            ),
            network.searchCalls
        )
    }

    @Test
    fun searchRepositoryForwardsWebSearchFilterParams() = runBlocking {
        val network = FakeInboxNetworkClient(searchJson = """{"results":[]}""")
        val repository = RealSearchRepository(sampleStore(), network)

        repository.search(
            serverId = ServerId("server-1"),
            query = " query ",
            offset = 20,
            limit = 20,
            options =
                SearchQueryOptions(
                    sort = SearchSort.Recent,
                    senderId = "user-1",
                    channelId = ChannelId("channel-1"),
                    afterIso = "2026-05-14T16:00:00.000Z",
                    beforeIso = "2026-05-15T15:04:43.904Z"
                )
        )

        assertEquals(
            listOf(
                SearchCall(
                    query = "query",
                    limit = 20,
                    offset = 20,
                    sort = "recent",
                    senderId = "user-1",
                    channelId = "channel-1",
                    after = "2026-05-14T16:00:00.000Z",
                    before = "2026-05-15T15:04:43.904Z"
                )
            ),
            network.searchCalls
        )
    }

    @Test
    fun repositoriesRequireSelectedSessionServer() = runBlocking {
        val repository = RealInboxRepository(sampleStore(), FakeInboxNetworkClient())

        val result = repository.loadInitial(ServerId("other-server"))

        assertTrue(result is DataResult.NoPermission)
    }

    @Test
    fun savedMutationsBuildSharedDtoBodies() = runBlocking {
        val network = FakeInboxNetworkClient()
        val repository = RealSavedRepository(sampleStore(), network)

        val saved = repository.saveMessage(ServerId("server-1"), ai.slock.compose.model.MessageId("message-1"))
        val unsaved = repository.unsaveMessage(ServerId("server-1"), ai.slock.compose.model.MessageId("message-2"))

        assertTrue(saved is DataResult.Content)
        assertTrue(unsaved is DataResult.Content)
        assertEquals(listOf("""{"messageId":"message-1"}"""), network.saveBodies)
        assertEquals(listOf("message-2"), network.unsavedMessageIds)
    }

    @Test
    fun savedCheckUsesBatchStatusContract() = runBlocking {
        val network = FakeInboxNetworkClient(savedCheckJson = """{"savedIds":["message-1","message-3"]}""")
        val repository = RealSavedRepository(sampleStore(), network)

        val checked =
            repository.checkSavedMessages(
                ServerId("server-1"),
                listOf(ai.slock.compose.model.MessageId("message-1"), ai.slock.compose.model.MessageId("message-2"), ai.slock.compose.model.MessageId("message-3"))
            )

        assertTrue(checked is DataResult.Content)
        assertEquals(setOf("message-1", "message-3"), checked.data.savedIds.map { it.value }.toSet())
        assertEquals(listOf("""{"messageIds":["message-1","message-2","message-3"]}"""), network.savedCheckBodies)
    }

    private fun sampleStore(): SlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "artin@slock.ai",
                        displayName = "Artin"
                    ),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "android"
                        )
                    )
            )
        )

    private class FakeInboxNetworkClient(
        var inboxJson: String = """{"items":[]}""",
        var savedJson: String = """{"saved":[]}""",
        var searchJson: String = """{"results":[]}""",
        var savedCheckJson: String = """{"savedIds":[]}"""
    ) : SlockNetworkClient {
        val inboxCalls = mutableListOf<InboxCall>()
        val searchCalls = mutableListOf<SearchCall>()
        val saveBodies = mutableListOf<String>()
        val savedCheckBodies = mutableListOf<String>()
        val unsavedMessageIds = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            return when {
                normalized == "channels/inbox" && method == "GET" -> {
                    inboxCalls +=
                        InboxCall(
                            filter = query.firstValue("filter") ?: "all",
                            limit = query.firstValue("limit")?.toIntOrNull() ?: 30,
                            offset = query.firstValue("offset")?.toIntOrNull() ?: 0
                        )
                    ok(inboxJson)
                }
                normalized == "channels/saved" && method == "GET" ->
                    ok(savedJson)
                normalized == "channels/saved" && method == "POST" -> {
                    saveBodies += bodyText.orEmpty()
                    ok("""{"ok":true}""")
                }
                normalized == "channels/saved/check" && method == "POST" -> {
                    savedCheckBodies += bodyText.orEmpty()
                    ok(savedCheckJson)
                }
                normalized.startsWith("channels/saved/") && method == "DELETE" -> {
                    unsavedMessageIds += normalized.removePrefix("channels/saved/")
                    ok("""{"ok":true}""")
                }
                normalized == "messages/search" && method == "GET" -> {
                    searchCalls +=
                        SearchCall(
                            query = query.firstValue("q").orEmpty(),
                            limit = query.firstValue("limit")?.toIntOrNull() ?: 30,
                            offset = query.firstValue("offset")?.toIntOrNull() ?: 0,
                            sort = query.firstValue("sort"),
                            senderId = query.firstValue("senderId"),
                            channelId = query.firstValue("channelId"),
                            after = query.firstValue("after"),
                            before = query.firstValue("before")
                        )
                    ok(searchJson)
                }
                else -> error("Unhandled request $method $normalized")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private data class InboxCall(
        val filter: String,
        val limit: Int,
        val offset: Int
    )

    private data class SearchCall(
        val query: String,
        val limit: Int,
        val offset: Int,
        val sort: String?,
        val senderId: String?,
        val channelId: String?,
        val after: String?,
        val before: String?
    )
}

private fun List<Pair<String, String>>.firstValue(name: String): String? =
    firstOrNull { it.first == name }?.second
