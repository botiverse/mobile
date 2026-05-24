package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.AgentProfileUpdateRequest
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.data.repository.RemoveChannelMemberRequest
import ai.slock.android.core.data.repository.SearchQueryOptions
import ai.slock.android.core.data.repository.SearchSort
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.unreadReplyCount
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.compose.network.withSlockRequestPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.slock.android.core.data.network.ApiResponse

class RealSlockRepositoriesTest {
    @Test
    fun channelRepositoryMapsChannelsAndDmsToHomeSnapshot() {
        val api =
            FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","description":"Android work","unreadCount":3,"lastMessagePreview":"Ready","memberCount":7}]}
                """.trimIndent(),
                directMessagesJson = """
                    [{"id":"dm-1","peerDisplayName":"Android-Developer","unreadCount":2,"lastMessagePreview":"Done"}]
                """.trimIndent()
            )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("Slock Android", snapshot.server.name)
        assertEquals("android-dev", snapshot.channels.single().name)
        assertEquals(7, snapshot.channels.single().memberCount)
        assertEquals(ConversationKind.DirectMessage, snapshot.directMessages.single().kind)
        assertEquals(5, snapshot.unreadTotal)
        assertEquals(listOf<String?>(null), api.channelArchivedQueries)
    }

    @Test
    fun channelRepositoryPreservesCachedSummaryFieldsWhenRefreshOmitsThem() {
        val api =
            FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","memberCount":7,"lastMessagePreview":"Ready"}]}
                """.trimIndent(),
                directMessagesJson = """[]"""
            )
        val repository =
            RealChannelRepository(
                store = InMemoryAuthSessionStore(sampleSession),
                api = api
            )

        val first = runSuspend { repository.getHome(ServerId("server-1")) }
        api.channelsJson = """{"channels":[{"id":"channel-1","name":"android-dev"}]}"""
        val second = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(first is DataResult.Content)
        assertTrue(second is DataResult.Content)
        val channel = (second as DataResult.Content).data.channels.single()
        assertEquals(7, channel.memberCount)
        assertEquals("Ready", channel.lastMessagePreview)
    }

    @Test
    fun channelRepositoryDoesNotLetMissingSummaryCountsOverwriteCachedValues() {
        val api =
            FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","memberCount":7,"unreadCount":5,"hasDraft":true,"lastMessagePreview":"Ready"}]}
                """.trimIndent(),
                directMessagesJson = """[]"""
            )
        val repository =
            RealChannelRepository(
                store = InMemoryAuthSessionStore(sampleSession),
                api = api
            )

        val first = runSuspend { repository.getHome(ServerId("server-1")) }
        api.channelsJson = """{"channels":[{"id":"channel-1","name":"android-dev"}]}"""
        val second = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(first is DataResult.Content)
        assertTrue(second is DataResult.Content)
        val channel = (second as DataResult.Content).data.channels.single()
        assertEquals(7, channel.memberCount)
        assertEquals(5, channel.unreadCount)
        assertEquals(true, channel.hasDraft)
        assertEquals("Ready", channel.lastMessagePreview)
    }

    @Test
    fun channelRepositoryAllowsKnownZeroSummaryCountsToReplaceCachedValues() {
        val api =
            FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","memberCount":7,"unreadCount":5,"hasDraft":true}]}
                """.trimIndent(),
                directMessagesJson = """[]"""
            )
        val repository =
            RealChannelRepository(
                store = InMemoryAuthSessionStore(sampleSession),
                api = api
            )

        runSuspend { repository.getHome(ServerId("server-1")) }
        api.channelsJson =
            """{"channels":[{"id":"channel-1","name":"android-dev","memberCount":0,"unreadCount":0,"hasDraft":false}]}"""
        val second = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(second is DataResult.Content)
        val channel = (second as DataResult.Content).data.channels.single()
        assertEquals(0, channel.memberCount)
        assertEquals(0, channel.unreadCount)
        assertEquals(false, channel.hasDraft)
    }

    @Test
    fun channelRepositoryHidesArchivedChannelsFromHomeSnapshot() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api =
                FakeSlockApi(
                    channelsJson = """
                        {
                          "channels": [
                            {"id":"channel-active","name":"active"},
                            {"id":"channel-archived","name":"archived","archived":true},
                            {"id":"channel-archived-alias","name":"archived alias","isArchived":true}
                          ]
                        }
                    """.trimIndent(),
                    directMessagesJson = """[]"""
                )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(listOf("active"), snapshot.channels.map { it.name })
    }

    @Test
    fun channelRepositoryPreservesNestedAgentPresenceForHomeDirectMessages() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """{"channels":[]}""",
                directMessagesJson = """
                    {
                      "dms": [
                        {
                          "channelId": "dm-agent-1",
                          "peer": {
                            "id": "agent-1",
                            "displayName": "Android-Developer",
                            "kind": "agent",
                            "avatarUrl": "https://cdn.example/agent.png",
                            "presence": "active"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val directMessage = (result as DataResult.Content).data.directMessages.single()
        assertEquals(ConversationKind.DirectMessage, directMessage.kind)
        assertEquals("Android-Developer", directMessage.name)
        assertEquals(Presence.Active, directMessage.presence)
        assertEquals("agent-1", directMessage.presenceMemberId?.value)
    }

    @Test
    fun channelRepositoryMapsAgentPresenceAliasesForHomeDirectMessages() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """{"channels":[]}""",
                directMessagesJson = """
                    {
                      "directMessages": [
                        {
                          "id": "dm-agent-2",
                          "agentId": "agent-2",
                          "agentName": "QA-Tester",
                          "agentStatus": "busy",
                          "avatar": "random:qa"
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val directMessage = (result as DataResult.Content).data.directMessages.single()
        assertEquals("QA-Tester", directMessage.name)
        assertEquals(AgentStatus.Working, directMessage.agentStatus)
        assertEquals(Presence.Active, directMessage.presence)
        assertEquals("agent-2", directMessage.presenceMemberId?.value)
    }

    @Test
    fun channelRepositoryDoesNotExposeHumanPresenceForHomeDirectMessages() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """{"channels":[]}""",
                directMessagesJson = """
                    {
                      "directMessages": [
                        {
                          "id": "dm-human-1",
                          "userId": "user-2",
                          "userDisplayName": "Human User",
                          "userPresence": "online"
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val directMessage = (result as DataResult.Content).data.directMessages.single()
        assertEquals("Human User", directMessage.name)
        assertEquals(null, directMessage.presence)
        assertEquals("user-2", directMessage.presenceMemberId?.value)
    }

    @Test
    fun channelRepositoryToleratesWrappedHomeResponseShapesAndBadItems() {
        val warnings = mutableListOf<String>()
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """
                    {
                      "data": {
                        "channels": [
                          null,
                          "bad-item",
                          {"_id":"channel-1","name":"android-dev","joined":false,"unreadCount":"3","latestActivityPreview":"Ready","agentAvatar":"random:android-dev-agent"}
                        ]
                      }
                    }
                """.trimIndent(),
                directMessagesJson = """
                    {
                      "data": {
                        "dms": [
                          {"channelId":"dm-1","peerName":"Android-Developer","unread":"2","peerAvatar":"https://cdn.example/avatar.png"}
                        ]
                      }
                    }
                """.trimIndent()
            ),
            warningLogger = warnings::add
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(1, snapshot.channels.size)
        assertEquals("channel-1", snapshot.channels.single().channelId?.value)
        assertEquals(false, snapshot.channels.single().joined)
        assertEquals(false, snapshot.channels.single().permissions.canSend)
        assertEquals("Ready", snapshot.channels.single().lastMessagePreview)
        assertEquals("random:android-dev-agent", snapshot.channels.single().avatar?.wireValue)
        assertTrue(snapshot.channels.single().avatar is ai.slock.compose.model.AvatarReference.Random)
        assertEquals(1, snapshot.directMessages.size)
        assertEquals("Android-Developer", snapshot.directMessages.single().name)
        assertTrue(snapshot.directMessages.single().avatar is ai.slock.compose.model.AvatarReference.Url)
        assertEquals(5, snapshot.unreadTotal)
        assertTrue(warnings.none { it.contains("token", ignoreCase = true) || it.contains("Bearer") })
    }

    @Test
    fun channelRepositoryParsesDirectMessageGravatarAvatarObjects() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api =
                FakeSlockApi(
                    channelsJson = """{"channels":[]}""",
                    directMessagesJson = """
                        {
                          "directMessages": [
                            {
                              "channelId": "dm-1",
                              "peer": {
                                "id": "agent-1",
                                "displayName": "Android-Developer",
                                "kind": "agent",
                                "avatar": {
                                  "type": "gravatar",
                                  "hash": "ABC123"
                                }
                              }
                            },
                            {
                              "channelId": "dm-2",
                              "peer": {
                                "id": "agent-2",
                                "displayName": "KMP-Expert",
                                "kind": "agent",
                                "avatar": "gravatar:DEF456"
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val avatars = (result as DataResult.Content).data.directMessages.map { it.avatar?.wireValue }
        assertEquals(
            listOf(
                "https://www.gravatar.com/avatar/abc123?s=52&d=404",
                "https://www.gravatar.com/avatar/def456?s=52&d=404"
            ),
            avatars
        )
    }

    @Test
    fun channelRepositoryDoesNotMapCancellationToParseFailure() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """{"channels":[{"id":"channel-1","name":"android-dev"}]}""",
                directMessagesFailure = CancellationException("effect restarted")
            ),
            warningLogger = {}
        )

        try {
            runSuspend { repository.getHome(ServerId("server-1")) }
        } catch (exception: CancellationException) {
            assertEquals("effect restarted", exception.message)
            return
        }

        throw AssertionError("Expected CancellationException to be rethrown.")
    }

    @Test
    fun channelRepositoryOpensAgentDirectMessageAndMergesCache() {
        val api = FakeSlockApi(
            directMessagesJson = """
                {"directMessages":[{"id":"dm-existing","agentId":"agent-existing","agentName":"Existing Agent"}]}
            """.trimIndent(),
            openDirectMessageJson = """
                {"channel":{"id":"dm-agent-1","agentId":"agent-1","agentName":"API Agent","agentStatus":"active","lastMessagePreview":"Created"}}
            """.trimIndent()
        )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val opened = runSuspend { repository.openAgentDirectMessage(MemberId("agent-1")) }
        val directMessages = runSuspend { repository.getDirectMessages(ServerId("server-1")) }

        assertTrue(opened is DataResult.Content)
        val openedContent = (opened as DataResult.Content).data
        assertEquals("""{"agentId":"agent-1"}""", api.openDirectMessageBodies.single())
        assertEquals("dm-agent-1", openedContent.conversation.channelId?.value)
        assertEquals(Presence.Active, openedContent.conversation.presence)
        assertEquals(1, openedContent.directMessages.count { it.channelId?.value == "dm-agent-1" })
        assertTrue(directMessages is DataResult.Content)
        val merged = (directMessages as DataResult.Content).data.directMessages
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.channelId?.value == "dm-agent-1" })
        assertEquals("Created", merged.first { it.channelId?.value == "dm-agent-1" }.lastMessagePreview)
    }

    @Test
    fun channelRepositoryOpensUserDirectMessageWithUserIdBody() {
        val api = FakeSlockApi(
            openDirectMessageJson = """
                {"dm":{"channelId":"dm-user-2","userId":"user-2","userDisplayName":"Artin"}}
            """.trimIndent()
        )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val opened = runSuspend { repository.openUserDirectMessage(MemberId("user-2")) }

        assertTrue(opened is DataResult.Content)
        assertEquals("""{"userId":"user-2"}""", api.openDirectMessageBodies.single())
        val conversation = (opened as DataResult.Content).data.conversation
        assertEquals("dm-user-2", conversation.channelId?.value)
        assertEquals("Artin", conversation.name)
        assertEquals(null, conversation.presence)
    }

    @Test
    fun inboxRepositoryMapsPagingMetadataAndStableThreadKeys() {
        val api = FakeSlockApi(
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
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api
        )

        val result = runSuspend {
            repository.loadMore(ServerId("server-1"), InboxFilter.Unread, offset = 30, limit = 30)
        }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(true, snapshot.hasMore)
        assertEquals(31, snapshot.nextOffset)
        assertEquals(1, snapshot.totalCount)
        assertEquals(4, snapshot.totalUnreadCount)
        assertEquals("thread:thread-1", snapshot.items.single().key)
        assertEquals(InboxItemKind.Thread, snapshot.items.single().kind)
    }

    @Test
    fun inboxRepositoryMapsTimestampAliasesForUnifiedFormatting() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                inboxJson = """
                    {
                      "items": [
                        {"channelId":"channel-1","channelName":"standard","timestamp":1778335200000},
                        {"channelId":"channel-2","channelName":"activity","latestActivityAt":"2026-05-09 22:00:00+08:00"}
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.loadInitial(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val items = (result as DataResult.Content).data.items
        assertEquals("1778335200000", items.first { it.channelName == "standard" }.lastMessageAt)
        assertEquals(
            "2026-05-09 22:00:00+08:00",
            items.first { it.channelName == "activity" }.latestActivityAt
        )
    }

    @Test
    fun inboxRepositoryMapsRealThreadItemShape() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
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
                          "latestActivitySender": {
                            "id": "agent-1",
                            "displayName": "Android-App-Integrator"
                          },
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

        val result = runSuspend { repository.loadInitial(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val item = (result as DataResult.Content).data.items.single()
        assertEquals(InboxItemKind.Thread, item.kind)
        assertEquals("thread-1", item.threadChannelId?.value)
        assertEquals("channel-1", item.parentChannelId?.value)
        assertEquals(ConversationKind.Channel, item.parentChannelKind)
        assertEquals("inline code 也有这个问题", item.parentMessagePreview)
        assertEquals("收到，我会修 inline code", item.latestActivityPreview)
        assertEquals("Android-App-Integrator", item.latestActivitySenderName)
        assertEquals("2026-05-10T21:26:00+08:00", item.latestActivityAt)
        assertEquals(8, item.replyCount)
    }

    @Test
    fun inboxRepositoryMapsMentionsFilterAndHasMentionFlag() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                inboxJson = """
                    {
                      "items": [
                        {
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
        )

        val result = runSuspend {
            repository.loadInitial(ServerId("server-1"), filter = InboxFilter.Mentions)
        }

        assertTrue(result is DataResult.Content)
        val item = (result as DataResult.Content).data.items.single()
        assertEquals("channel-mention-1", item.channelId.value)
        assertTrue(item.hasMention)
    }

    @Test
    fun savedAndSearchMapItemsAndBlankSearchReturnsEmpty() {
        val store = InMemoryAuthSessionStore(sampleSession)
        val api = FakeSlockApi(
            savedJson = """{"items":[{"channelId":"channel-1","channelName":"android-dev","messageId":"message-1","preview":"Saved note"}],"hasMore":false}""",
            searchJson = """{"results":[{"channelId":"channel-2","channelName":"product","messageId":"message-2","content":"Search hit"}],"totalCount":1}"""
        )

        val saved = runSuspend { RealSavedRepository(store, api).loadInitial(ServerId("server-1")) }
        val search = runSuspend { RealSearchRepository(store, api).search(ServerId("server-1"), "query") }
        val blank = runSuspend { RealSearchRepository(store, api).search(ServerId("server-1"), " ") }

        assertTrue(saved is DataResult.Content)
        assertEquals("Saved note", (saved as DataResult.Content).data.items.single().lastMessagePreview)
        assertTrue(search is DataResult.Content)
        assertEquals("Search hit", (search as DataResult.Content).data.items.single().lastMessagePreview)
        assertTrue(blank is DataResult.Empty)
    }

    @Test
    fun searchRepositoryForwardsWebSearchFilterParams() {
        val api = FakeSlockApi(searchJson = """{"results":[]}""")
        val repository = RealSearchRepository(InMemoryAuthSessionStore(sampleSession), api)

        runSuspend {
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
        }

        assertEquals(
            listOf(
                SearchMessagesCall(
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
            api.searchMessagesCalls
        )
    }

    @Test
    fun serverUnreadRepositoryMapsSummaryAndRequiresSelectedServer() {
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                unreadSummaryJson = """
                    {
                      "servers": [
                        {"serverId":"server-1","totalUnreadCount":9,"channelUnreadCount":3,"dmUnreadCount":2,"mentionCount":1},
                        {"serverId":"server-2","totalUnreadCount":5}
                      ],
                      "totalUnreadCount": 14
                    }
                """.trimIndent()
            ),
            logger = {},
            warningLogger = {}
        )

        val result = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        val mismatch = runSuspend { repository.getUnreadSummary(ServerId("server-2")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(14, snapshot.totalUnreadCount)
        assertEquals(9, snapshot.server.unreadCount)
        assertEquals(9, snapshot.selectedServerUnreadCount)
        assertEquals(2, snapshot.summaries.size)
        assertEquals(1, snapshot.selectedServerSummary?.mentionCount)
        assertTrue(mismatch is DataResult.NoPermission)
    }

    @Test
    fun serverUnreadRepositoryMapsCurrentServerOnlyShape() {
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                unreadSummaryJson = """{"unreadCount":3,"mentions":1}"""
            ),
            logger = {},
            warningLogger = {}
        )

        val result = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(3, snapshot.totalUnreadCount)
        assertEquals(3, snapshot.selectedServerUnreadCount)
        assertEquals("server-1", snapshot.selectedServerSummary?.serverId?.value)
    }

    @Test
    fun serverUnreadRepositoryThrottlesRepeatedCallsPerServer() {
        var now = 1_000L
        val api = FakeSlockApi(
            unreadSummaryJson = """{"unreadCount":3}"""
        )
        val repository = RealServerUnreadRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            throttleWindowMillis = 5_000L,
            clockMillis = { now },
            logger = {},
            warningLogger = {}
        )

        val first = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        val second = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }
        now += 5_001L
        val third = runSuspend { repository.getUnreadSummary(ServerId("server-1")) }

        assertTrue(first is DataResult.Content)
        assertTrue(second is DataResult.Content)
        assertTrue(third is DataResult.Content)
        assertEquals(2, api.unreadSummaryCallCount)
    }

    @Test
    fun repositoriesRequireSelectedSessionServer() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi()
        )

        val result = runSuspend {
            repository.loadInitial(ServerId("other-server"))
        }

        assertTrue(result is DataResult.NoPermission)
    }

    @Test
    fun conversationRepositoryMapsChannelMessagesAndPaging() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "conversation": {"id":"channel-1","name":"android-dev"},
                      "messages": [
                        {
                          "id":"c41039c4-1111-4222-8333-0123456789ab",
                          "channelId":"channel-1",
                          "threadId":"a98ec1f2-1111-4222-8333-fedcba987654",
                          "seq":"42",
                          "content":"Hello",
                          "createdByUser":{"id":"user-2","displayName":"Android Developer","kind":"agent"},
                          "attachments":[
                            {
                              "id":"attachment-1",
                              "filename":"preview.png",
                              "mimeType":"image/png",
                              "sizeLabel":"42 KB",
                              "thumbnailUrl":"https://cdn.example/thumbs/attachment-1.png"
                            }
                          ],
                          "replyCount":7,
                          "task":{"id":"task-1","title":"Fix chat","status":"in_review","number":151}
                        },
                        {
                          "id":"system-message-1",
                          "channelId":"channel-1",
                          "seq":"43",
                          "type":"system",
                          "eventType":"task_claimed",
                          "content":"Task claimed",
                          "metadata":{"taskId":"task-1","actorId":"agent-1"}
                        }
                      ],
                      "hasMore": true
                    }
                """.trimIndent(),
                channelThreadsJson = """
                    {
                      "threads": [
                        {
                          "kind":"thread",
                          "channelId":"c41039c4",
                          "threadChannelId":"c41039c4",
                          "replyCount":2,
                          "parentChannelName":"android-dev"
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        val chatMessage = snapshot.messages.first()
        val systemMessage = snapshot.messages.last()
        assertEquals("android-dev", snapshot.conversation.name)
        assertEquals(true, snapshot.canLoadMoreBefore)
        assertEquals(42L, chatMessage.seq)
        assertEquals("Hello", chatMessage.body)
        assertEquals("channel-1", chatMessage.threadId.value)
        assertEquals("channel-1", chatMessage.channelId?.value)
        assertEquals("a98ec1f2-1111-4222-8333-fedcba987654", chatMessage.threadChannelId?.value)
        assertEquals("Android Developer", chatMessage.author.displayName)
        assertEquals("https://cdn.example/thumbs/attachment-1.png", chatMessage.attachments.single().thumbnailUrl)
        assertEquals(7, chatMessage.replyCount)
        assertEquals(TaskStatus.InReview, chatMessage.task?.status)
        assertEquals(MessageType.System, systemMessage.messageType)
        assertEquals("task_claimed", systemMessage.systemEventType)
        assertEquals("task-1", systemMessage.metadata["taskId"])
    }

    @Test
    fun channelMessagesWithoutChannelObjectUseRequestChannelId() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "messages": [
                        {
                          "id":"message-1",
                          "channelId":"channel-all",
                          "seq":"42",
                          "content":"Hello",
                          "createdByUser":{"id":"user-2","displayName":"Android Developer"}
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-all")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("channel-all", snapshot.conversation.threadId.value)
        assertEquals("channel-all", snapshot.conversation.channelId?.value)
        assertEquals("channel-all", snapshot.messages.single().threadId.value)
    }

    @Test
    fun conversationRepositoryMapsImageAttachmentAliasesFromChannelMessages() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "messages": [
                        {
                          "id":"message-images-1",
                          "channelId":"channel-1",
                          "seq":"42",
                          "content":"Multiple screenshots",
                          "createdByUser":{"id":"user-2","displayName":"Android Developer"},
                          "images":[
                            {
                              "id":"attachment-1",
                              "filename":"preview-1.png",
                              "mimeType":"image/png",
                              "sizeLabel":"42 KB",
                              "thumbnailUrl":"https://cdn.example/thumbs/attachment-1.png"
                            },
                            {
                              "id":"attachment-2",
                              "filename":"preview-2.png",
                              "mimeType":"image/png",
                              "sizeLabel":"39 KB",
                              "thumbnailUrl":"https://cdn.example/thumbs/attachment-2.png"
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val attachments = (result as DataResult.Content).data.messages.single().attachments
        assertEquals(listOf("preview-1.png", "preview-2.png"), attachments.map { it.name })
        assertEquals(
            listOf(
                "https://cdn.example/thumbs/attachment-1.png",
                "https://cdn.example/thumbs/attachment-2.png"
            ),
            attachments.map { it.thumbnailUrl }
        )
    }

    @Test
    fun conversationRepositoryBackfillsReplyCountFromThreadSummaryAliases() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "conversation": {"id":"channel-1","name":"android-dev"},
                      "messages": [
                        {
                          "id":"c41039c4-1111-4222-8333-0123456789ab",
                          "channelId":"channel-1",
                          "seq":"42",
                          "content":"Message with replies",
                          "createdByUser":{"id":"user-2","displayName":"Android Developer","kind":"agent"}
                        }
                      ]
                    }
                """.trimIndent(),
                channelThreadsJson = """
                    {
                      "threads": [
                        {
                          "kind":"thread",
                          "channelId":"thread-c41039c4",
                          "threadChannelId":"thread-c41039c4",
                          "parentMessageId":"c41039c4-1111-4222-8333-0123456789ab",
                          "parentChannelName":"android-dev",
                          "replyCount":7
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val message = (result as DataResult.Content).data.messages.single()
        assertEquals("thread-c41039c4", message.threadChannelId?.value)
        assertEquals(7, message.replyCount)
        assertEquals(0, message.unreadReplyCount())
    }

    @Test
    fun conversationRepositoryBackfillsUnreadReplyCountFromFollowedThreadSummary() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "conversation": {"id":"channel-1","name":"android-dev"},
                      "messages": [
                        {
                          "id":"c41039c4-1111-4222-8333-0123456789ab",
                          "channelId":"channel-1",
                          "seq":"42",
                          "content":"Message with unread replies",
                          "createdByUser":{"id":"user-2","displayName":"Android Developer","kind":"agent"}
                        }
                      ]
                    }
                """.trimIndent(),
                followedThreadsJson = """
                    {
                      "items": [
                        {
                          "kind":"thread",
                          "channelId":"thread-c41039c4",
                          "threadChannelId":"thread-c41039c4",
                          "parentChannelId":"channel-1",
                          "parentMessageId":"c41039c4-1111-4222-8333-0123456789ab",
                          "replyCount":7,
                          "unreadCount":3
                        }
                      ],
                      "totalCount":1
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val message = (result as DataResult.Content).data.messages.single()
        assertEquals("thread-c41039c4", message.threadChannelId?.value)
        assertEquals(7, message.replyCount)
        assertEquals(3, message.unreadReplyCount())
    }

    @Test
    fun conversationRepositoryLoadOlderMessagesPassesBeforeSeqAndMapsNestedPagination() {
        val api = FakeSlockApi(
            channelMessagesJson = """
                {
                  "data": {
                    "messages": [
                      {
                        "id": "older-1",
                        "channelId": "channel-1",
                        "sequenceNumber": 12,
                        "content": "Older message",
                        "createdAt": "2026-05-07T12:00:00Z"
                      }
                    ],
                    "pagination": {
                      "hasMoreBefore": true
                    }
                  }
                }
            """.trimIndent()
        )
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.loadOlderMessages(ThreadId("channel-1"), beforeSeq = 42) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(listOf(42L), api.channelMessagesBeforeSeqCalls)
        assertEquals(1, snapshot.messages.size)
        assertEquals(12L, snapshot.messages.first().seq)
        assertEquals("Older message", snapshot.messages.first().body)
        assertEquals(true, snapshot.canLoadMoreBefore)
    }

    @Test
    fun conversationRepositoryMapsNestedAgentAuthorNames() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "messages": [
                        {
                          "id": "message-agent-profile",
                          "channelId": "channel-1",
                          "content": "Nested profile",
                          "agent": {
                            "agentId": "agent-1",
                            "profile": {
                              "displayName": "API Integration",
                              "username": "api-integration",
                              "avatar": {"type": "pixel", "key": "robot"}
                            },
                            "presence": "working"
                          }
                        },
                        {
                          "id": "message-agent-sibling",
                          "channelId": "channel-1",
                          "content": "Sibling aliases",
                          "agentId": "agent-2",
                          "agentDisplayName": "Android Developer",
                          "agentUsername": "android-dev",
                          "agentAvatar": "random:android-dev-agent"
                        },
                        {
                          "id": "message-agent-metadata",
                          "channelId": "channel-1",
                          "content": "Metadata aliases",
                          "authorAgent": {
                            "id": "agent-3",
                            "metadata": {
                              "title": "QA Tester"
                            },
                            "kind": "agent"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val messages = (result as DataResult.Content).data.messages
        assertEquals("API Integration", messages[0].author.displayName)
        assertEquals("api-integration", messages[0].author.handle)
        assertEquals(MemberKind.Agent, messages[0].author.kind)
        assertEquals(Presence.Active, messages[0].author.presence)
        assertEquals("pixel:robot", messages[0].author.avatar?.wireValue)
        assertEquals("Android Developer", messages[1].author.displayName)
        assertEquals("android-dev", messages[1].author.handle)
        assertEquals(MemberKind.Agent, messages[1].author.kind)
        assertEquals("random:android-dev-agent", messages[1].author.avatar?.wireValue)
        assertEquals("QA Tester", messages[2].author.displayName)
        assertEquals(MemberKind.Agent, messages[2].author.kind)
    }

    @Test
    fun conversationRepositoryUsesSenderDescriptionFromMessageContract() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "messages": [
                        {
                          "id": "message-agent-description",
                          "channelId": "channel-1",
                          "content": "Uses real description",
                          "agentId": "agent-2",
                          "senderName": "Android Developer",
                          "senderDescription": "负责 Android 客户端实现",
                          "roleDescription": "agent"
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val message = (result as DataResult.Content).data.messages.single()
        assertEquals("Android Developer", message.author.displayName)
        assertEquals("负责 Android 客户端实现", message.author.description)
        assertEquals("agent", message.author.roleDescription)
    }

    @Test
    fun conversationRepositoryMapsAuthorGravatarHash() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMessagesJson = """
                    {
                      "messages": [
                        {
                          "id": "message-human-gravatar",
                          "channelId": "channel-1",
                          "content": "Human avatar",
                          "senderId": "user-1",
                          "senderName": "artin",
                          "senderGravatarHash": "abc123"
                        }
                      ]
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getThread(ThreadId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val author = (result as DataResult.Content).data.messages.single().author
        assertEquals("abc123", author.gravatarHash)
        assertEquals("https://www.gravatar.com/avatar/abc123?s=52&d=404", author.avatar?.wireValue)
    }

    @Test
    fun conversationRepositoryMapsContextSyncSendAndThreads() {
        val api = FakeSlockApi(
            messageContextJson = """
                {"message":{"id":"message-focus","channelId":"channel-1","seq":50,"content":"Focus"}}
            """.trimIndent(),
            messageSyncJson = """
                {"messages":[{"id":"message-new","channelId":"channel-1","seq":51,"content":"New"}],"hasMoreAfter":false}
            """.trimIndent(),
            sendMessageJson = """
                {"message":{"id":"message-sent","channelId":"channel-1","seq":52,"content":"Sent"}}
            """.trimIndent(),
            followedThreadsJson = """
                {"items":[{"kind":"thread","channelId":"thread-1","channelName":"Thread","threadChannelId":"thread-1","parentChannelName":"android-dev","replyCount":4}],"totalCount":1}
            """.trimIndent()
        )
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val context = runSuspend { repository.getMessageContext(MessageId("message-focus")) }
        val sync = runSuspend { repository.loadNewerMessages(ThreadId("channel-1"), afterSeq = 50) }
        val sent = runSuspend { repository.sendMessage(ChannelId("channel-1"), "Sent") }
        val followed = runSuspend { repository.getFollowedThreads(ServerId("server-1")) }
        val done = runSuspend { repository.markThreadDone(ChannelId("thread-1")) }

        assertTrue(context is DataResult.Content)
        assertEquals(MessageId("message-focus"), (context as DataResult.Content).data.highlightedMessageId)
        assertTrue(sync is DataResult.Content)
        assertEquals(51L, (sync as DataResult.Content).data.messages.single().seq)
        assertTrue(sent is DataResult.Content)
        assertEquals("Sent", (sent as DataResult.Content).data.message.body)
        assertTrue(followed is DataResult.Content)
        assertEquals("thread:thread-1", (followed as DataResult.Content).data.threads.single().key)
        assertTrue(done is DataResult.Content)
        assertEquals("thread-1", (done as DataResult.Content).data.threadChannelId.value)
    }

    @Test
    fun conversationRepositoryMapsAttachmentOpenTarget() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                attachmentUrlJson = """
                    {"downloadUrl":"https://example.invalid/signed/attachment-1","expires_at":"2026-05-07T00:00:00Z"}
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getAttachmentOpenTarget(AttachmentId("attachment-1")) }

        assertTrue(result is DataResult.Content)
        val target = (result as DataResult.Content).data
        assertEquals("attachment-1", target.attachmentId.value)
        assertEquals("https://example.invalid/signed/attachment-1", target.url)
        assertEquals("2026-05-07T00:00:00Z", target.expiresAt)
    }

    @Test
    fun conversationRepositoryMapsChannelFilesSnapshot() {
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelFilesJson = """
                    {
                      "files": [
                        {
                          "id": "attachment-9",
                          "filename": "roadmap.pdf",
                          "mimeType": "application/pdf",
                          "sizeBytes": 4096,
                          "thumbnailUrl": "https://example.invalid/thumb/attachment-9",
                          "createdAt": "2026-05-14T11:00:00Z",
                          "messageId": "message-9",
                          "source": {
                            "type": "message",
                            "parentMessageId": "parent-9",
                            "parentMessageShortId": "abcd1234"
                          }
                        }
                      ],
                      "hasMore": true,
                      "nextCursor": "cursor-2"
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result = runSuspend { repository.getChannelFiles(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("channel-1", snapshot.channelId.value)
        assertTrue(snapshot.hasMore)
        assertEquals("cursor-2", snapshot.nextCursor)
        val item = snapshot.items.single()
        assertEquals("attachment-9", item.attachment.id.value)
        assertEquals("roadmap.pdf", item.attachment.name)
        assertEquals("application/pdf", item.attachment.mimeType)
        assertEquals("4096 B", item.attachment.sizeLabel)
        assertEquals("2026-05-14T11:00:00Z", item.createdAt)
        assertEquals("message-9", item.messageId?.value)
        assertEquals("message", item.sourceType)
        assertEquals("parent-9", item.sourceParentMessageId?.value)
        assertEquals("abcd1234", item.sourceParentMessageShortId)
    }

    @Test
    fun channelRepositoryCreatesChannelAndAddsSelectedMembers() {
        val api = FakeSlockApi(
            createChannelJson = """{"id":"channel-new","name":"Launch","description":"Ship it"}"""
        )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend {
            repository.createChannel(
                CreateChannelRequest(
                    name = "Launch",
                    description = "Ship it",
                    agentMemberIds = listOf("agent-1"),
                    userMemberIds = listOf("user-2")
                )
            )
        }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-new", data.channel.channelId?.value)
        assertEquals("Launch", data.channel.name)
        assertEquals(listOf("agent-1"), data.addedAgentMemberIds)
        assertEquals(listOf("user-2"), data.addedUserMemberIds)
        assertEquals(2, api.addedMembers.size)
        assertTrue(api.addedMembers.any { it.startsWith("channel-new:") && it.contains("agentId") && it.contains("agent-1") })
        assertTrue(api.addedMembers.any { it.startsWith("channel-new:") && it.contains("userId") && it.contains("user-2") })
    }

    @Test
    fun channelRepositoryJoinsChannelAndMapsOptionalChannelResponse() {
        val api = FakeSlockApi(
            joinChannelJson = """{"channel":{"id":"channel-1","name":"android-dev","joined":true}}"""
        )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.joinChannel(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-1", data.channelId.value)
        assertEquals(true, data.joined)
        assertEquals(true, data.channel?.joined)
        assertEquals("android-dev", data.channel?.name)
        assertEquals(listOf("channel-1"), api.joinedChannelIds)
    }

    @Test
    fun channelRepositoryJoinsChannelWhenResponseHasOnlyOk() {
        val api = FakeSlockApi(joinChannelJson = """{"ok":true}""")
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.joinChannel(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-1", data.channelId.value)
        assertEquals(true, data.joined)
        assertEquals(null, data.channel)
    }

    @Test
    fun channelRepositoryLeavesChannelWithWebEndpoint() {
        val api = FakeSlockApi(leaveChannelJson = """{"ok":true}""")
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.leaveChannel(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-1", data.channelId.value)
        assertEquals(true, data.left)
        assertEquals(listOf("channel-1"), api.leftChannelIds)
        assertEquals(emptyList<String>(), api.removedMembers)
    }

    @Test
    fun channelRepositoryArchivesChannelWithWebEndpoint() {
        val api = FakeSlockApi(
            archiveChannelJson = """{"id":"channel-1","name":"android-dev","archived":true}"""
        )
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.archiveChannel(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-1", data.channelId.value)
        assertEquals(true, data.archived)
        assertEquals(true, data.channel?.archived)
        assertEquals("android-dev", data.channel?.name)
        assertEquals(listOf("channel-1"), api.archivedChannelIds)
    }

    @Test
    fun channelRepositoryDeletesChannelWithWebEndpoint() {
        val api = FakeSlockApi(deleteChannelJson = """{"ok":true}""")
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.deleteChannel(ChannelId("channel-1")) }

        assertTrue(result is DataResult.Content)
        val data = (result as DataResult.Content).data
        assertEquals("channel-1", data.channelId.value)
        assertEquals(true, data.deleted)
        assertEquals(listOf("channel-1"), api.deletedChannelIds)
    }

    @Test
    fun readReceiptRepositoryMapsSeqReadAndUnreadCounts() {
        val api = FakeSlockApi(
            unreadCountsJson = """{"channel-1":3,"thread-1":{"unreadCount":2}}""",
            readReceiptJson = """{"channelId":"channel-1","seq":42,"unreadCount":0}"""
        )
        val repository = RealReadReceiptRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val counts = runSuspend { repository.getUnreadCounts(ServerId("server-1")) }
        val read = runSuspend { repository.markChannelReadToSeq(ChannelId("channel-1"), seq = 42) }
        val readAll = runSuspend { repository.markChannelReadAll(ChannelId("thread-1")) }
        val unread = runSuspend { repository.markChannelUnread(ChannelId("channel-1")) }
        val inbox = runSuspend { repository.markInboxReadAll(ServerId("server-1")) }

        assertTrue(counts is DataResult.Content)
        assertEquals(5, (counts as DataResult.Content).data.totalUnreadCount)
        assertEquals(3, counts.data.unreadCounts[ChannelId("channel-1")])
        assertTrue(read is DataResult.Content)
        assertEquals(42L, (read as DataResult.Content).data.seq)
        assertEquals("channel-1", read.data.channelId?.value)
        assertTrue(readAll is DataResult.Content)
        assertTrue(unread is DataResult.Content)
        assertTrue(inbox is DataResult.Content)
        assertEquals("channel-1:42", api.seqReadRequests.single())
        assertEquals(listOf("thread-1"), api.readAllRequests)
        assertEquals(1, api.inboxReadAllCallCount)
    }

    @Test
    fun taskRepositoryMapsServerTasksAndClaimAction() {
        val api = FakeSlockApi(
            serverTasksJson = """
                {
                  "tasks": [
                    {
                      "id": "task-1",
                      "title": "Wire tasks",
                      "status": "in_progress",
                      "taskNumber": 181,
                      "messageId": "parent-message-1",
                      "threadId": "thread-message-1",
                      "claimedById": "user-1",
                      "claimedByName": "Artin",
                      "channelName": "android-dev",
                      "updatedAt": "Today"
                    },
                    {
                      "id": "task-2",
                      "title": "Done task",
                      "status": "done",
                      "claimedById": "other-user"
                    }
                  ]
                }
            """.trimIndent(),
            claimTaskJson = """
                {"task":{"id":"task-1","title":"Wire tasks","status":"in_review","claimedById":"user-1"}}
            """.trimIndent(),
            updateTaskStatusJson = """
                {"task":{"id":"task-1","title":"Wire tasks","status":"closed","claimedById":"user-1"}}
            """.trimIndent()
        )
        val repository = RealTaskRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val tasks = runSuspend {
            repository.getTasks(
                ServerId("server-1"),
                TaskFilter(statuses = setOf(TaskStatus.InProgress), assignedToMeOnly = true)
            )
        }
        val claim = runSuspend { repository.claimTask(TaskId("task-1")) }
        val statusUpdate = runSuspend {
            repository.updateTaskStatus(TaskId("task-1"), TaskStatus.Closed)
        }

        assertTrue(tasks is DataResult.Content)
        val snapshot = (tasks as DataResult.Content).data
        assertEquals(1, snapshot.tasks.size)
        assertEquals("Wire tasks", snapshot.tasks.single().title)
        assertEquals("android-dev", snapshot.tasks.single().channelName)
        assertEquals("parent-message-1", snapshot.tasks.single().parentMessageId?.value)
        assertEquals("thread-message-1", snapshot.tasks.single().threadId?.value)
        assertTrue(snapshot.tasks.single().isThreadTask)
        assertEquals("Artin", snapshot.tasks.single().assignee?.displayName)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.InProgress])
        assertTrue(claim is DataResult.Content)
        assertEquals(TaskStatus.InReview, (claim as DataResult.Content).data.task.status)
        assertTrue(statusUpdate is DataResult.Content)
        assertEquals(TaskStatus.Closed, (statusUpdate as DataResult.Content).data.task.status)
        assertEquals("task-1", api.claimedTaskIds.single())
        assertEquals("task-1:{\"status\":\"closed\"}", api.updatedTaskStatusBodies.single())
    }

    @Test
    fun taskRepositoryPrefersMeaningfulTaskTextOverNumberPlaceholderTitle() {
        val api = FakeSlockApi(
            serverTasksJson = """
                {
                  "tasks": [
                    {
                      "id": "task-placeholder-title",
                      "title": "#20",
                      "content": "Fix the Tasks list display",
                      "status": "in_review",
                      "taskNumber": 20
                    },
                    {
                      "id": "task-parent-preview",
                      "title": "#21",
                      "parentMessagePreview": "Fix task details rendering",
                      "status": "todo",
                      "taskNumber": 21
                    },
                    {
                      "id": "task-prefixed-content",
                      "title": "#22",
                      "content": "#22 Keep only one task number",
                      "status": "todo",
                      "taskNumber": 22
                    },
                    {
                      "id": "task-nested-rich-message",
                      "title": "#23",
                      "sourceMessage": {
                        "content": [
                          {
                            "type": "paragraph",
                            "children": [
                              {
                                "text": "Restore rich source message details"
                              }
                            ]
                          }
                        ]
                      },
                      "status": "todo",
                      "taskNumber": 23
                    },
                    {
                      "id": "task-message-preview",
                      "title": "#24",
                      "messagePreview": "Use message preview when content is absent",
                      "status": "todo",
                      "taskNumber": 24
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealTaskRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val tasks = runSuspend { repository.getTasks(ServerId("server-1"), TaskFilter()) }

        assertTrue(tasks is DataResult.Content)
        val snapshot = (tasks as DataResult.Content).data
        assertEquals("Fix the Tasks list display", snapshot.tasks[0].title)
        assertEquals("Fix task details rendering", snapshot.tasks[1].title)
        assertEquals("Keep only one task number", snapshot.tasks[2].title)
        assertEquals("Restore rich source message details", snapshot.tasks[3].title)
        assertEquals("Use message preview when content is absent", snapshot.tasks[4].title)
    }

    @Test
    fun taskRepositoryMapsChannelScopedTasks() {
        val api = FakeSlockApi(
            channelTasksJson = """
                {
                  "tasks": [
                    {
                      "id": "task-channel-1",
                      "title": "Channel task",
                      "status": "todo",
                      "taskNumber": 24,
                      "channelId": "channel-1",
                      "channelName": "android-dev",
                      "updatedAt": "2026-05-09T20:55:00+08:00"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealTaskRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val tasks = runSuspend {
            repository.getChannelTasks(
                serverId = ServerId("server-1"),
                channelId = ChannelId("channel-1"),
                filter = TaskFilter(statuses = setOf(TaskStatus.Todo))
            )
        }

        assertTrue(tasks is DataResult.Content)
        val snapshot = (tasks as DataResult.Content).data
        assertEquals(1, snapshot.tasks.size)
        assertEquals("Channel task", snapshot.tasks.single().title)
        assertEquals("android-dev", snapshot.tasks.single().channelName)
        assertEquals(TaskStatus.Todo, snapshot.tasks.single().status)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.Todo])
        assertEquals(listOf("channel-1"), api.channelTaskCalls)
    }

    @Test
    fun taskRepositoryMapsRealServerTaskShapeForThreadNavigation() {
        val api = FakeSlockApi(
            serverTasksJson = """
                {
                  "tasks": [
                    {
                      "id": "message-task-1",
                      "taskNumber": 9,
                      "title": "Open replies from Tasks",
                      "content": "Open replies from Tasks",
                      "status": "in_review",
                      "taskStatus": "in_review",
                      "channelType": "channel",
                      "channelId": "parent-channel-1",
                      "channelName": "home-fixes",
                      "messageId": "parent-message-1",
                      "threadId": "reply-thread-1",
                      "claimedById": "agent-1",
                      "claimedByName": "Android-App-Integrator",
                      "claimedByType": "agent",
                      "createdAt": "2026-05-09T03:00:00.000Z",
                      "updatedAt": "2026-05-09T03:05:00.000Z"
                    },
                    {
                      "id": "closed-task-1",
                      "taskNumber": 10,
                      "title": "Closed task",
                      "status": "closed",
                      "channelType": "channel",
                      "channelId": "parent-channel-2",
                      "messageId": "parent-message-2",
                      "threadId": "reply-thread-2"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealTaskRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getTasks(ServerId("server-1"), TaskFilter()) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        val threadTask = snapshot.tasks.first { it.id.value == "message-task-1" }
        assertEquals(TaskStatus.InReview, threadTask.status)
        assertEquals(9, threadTask.taskNumber)
        assertEquals("parent-channel-1", threadTask.channelId?.value)
        assertEquals("parent-message-1", threadTask.messageId?.value)
        assertEquals("parent-message-1", threadTask.parentMessageId?.value)
        assertEquals("reply-thread-1", threadTask.threadId?.value)
        assertTrue(threadTask.isThreadTask)
        assertEquals("Android-App-Integrator", threadTask.assignee?.displayName)
        assertEquals(MemberKind.Agent, threadTask.assignee?.kind)
        assertEquals("home-fixes", threadTask.channelName)
        assertEquals(TaskStatus.Closed, snapshot.tasks.first { it.id.value == "closed-task-1" }.status)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.InReview])
        assertEquals(1, snapshot.summaryCounts[TaskStatus.Closed])
    }

    @Test
    fun memberRepositoryMapsServerChannelMembersAndAddAction() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "data": {
                    "users": [
                      {"id":"user-2","displayName":"Artin","handle":"artin","role":"owner","presence":"online","avatarUrl":"https://cdn.example/artin.png"}
                    ]
                  }
                }
            """.trimIndent(),
            memberProfileJson = """
                {
                  "profile": {
                    "id":"user-2",
                    "displayName":"Artin Profile",
                    "handle":"artin",
                    "role":"owner",
                    "presence":"offline",
                    "avatarUrl":"https://cdn.example/artin-profile.png"
                  }
                }
            """.trimIndent(),
            settingsAgentsJson = """
                {"agents":[
                  {"id":"agent-2","name":"QA Tester","handle":"qa","role":"tester","presence":"working","avatar":"random:qa"},
                  {"id":"user-2","name":"Artin Agent","handle":"artin-agent","role":"assistant","presence":"online","avatar":"pixel:artin-agent"}
                ]}
            """.trimIndent(),
            channelMembersJson = """
                {
                  "data": {
                    "canAddMembers": true,
                    "humans": [
                      {"user":{"id":"user-2","displayName":"Artin","handle":"artin","avatarUrl":"https://cdn.example/artin.png"},"role":"owner","presence":"away"}
                    ],
                    "agents": [
                      {"agent":{"id":"agent-1","name":"API Integration","handle":"api","avatar":"pixel:api"},"role":"developer","presence":"working"},
                      {"id":"agent-2","name":"QA Tester","handle":"qa","avatar":"random:qa","role":"tester","presence":"online"}
                    ]
                  }
                }
            """.trimIndent(),
            addChannelMemberJson = """
                {"added":true,"member":{"id":"agent-1","displayName":"API Integration","kind":"agent","presence":"online"}}
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val serverMembers = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }
        api.memberProfileCalls.clear()
        val channelMembers = runSuspend { repository.getChannelMembers(SlockRequestPriority.Visible, ChannelId("channel-1")) }
        val addResult = runSuspend {
            repository.addChannelMember(
                    SlockRequestPriority.Foreground,
                    AddChannelMemberRequest(
                    channelId = ChannelId("channel-1"),
                    memberId = MemberId("agent-1"),
                    kind = MemberKind.Agent
                )
            )
        }
        val removeResult = runSuspend {
            repository.removeChannelMember(
                    SlockRequestPriority.Foreground,
                    RemoveChannelMemberRequest(
                    channelId = ChannelId("channel-1"),
                    memberId = MemberId("agent-1"),
                    kind = MemberKind.Agent
                )
            )
        }

        assertTrue(serverMembers is DataResult.Content)
        val serverMemberRows = (serverMembers as DataResult.Content).data.members
        val humanServerMember = serverMemberRows.first { it.id.value == "user-2" }
        assertEquals("Artin", humanServerMember.displayName)
        assertEquals(Presence.Active, humanServerMember.presence)
        assertEquals("https://cdn.example/artin.png", humanServerMember.avatarUrl)
        val serverAgent = serverMemberRows.first { it.id.value == "agent-2" }
        assertEquals(MemberKind.Agent, serverAgent.kind)
        assertEquals("random:qa", serverAgent.avatarUrl)
        val sameIdHuman = serverMemberRows.first { it.id.value == "user-2" && it.kind == MemberKind.Human }
        val sameIdAgent = serverMemberRows.first { it.id.value == "user-2" && it.kind == MemberKind.Agent }
        assertEquals("Artin", sameIdHuman.displayName)
        assertEquals("Artin Agent", sameIdAgent.displayName)
        assertTrue(channelMembers is DataResult.Content)
        val members = (channelMembers as DataResult.Content).data.members
        assertEquals(3, members.size)
        assertFalse(members.any { it.id.value == "user-1" })
        val humanChannelMember = members.first { it.id.value == "user-2" }
        assertEquals("Artin", humanChannelMember.displayName)
        assertEquals(Presence.Away, humanChannelMember.presence)
        assertEquals("https://cdn.example/artin.png", humanChannelMember.avatarUrl)
        assertTrue(api.memberProfileCalls.isEmpty())
        val agent = members.first { it.id.value == "agent-1" }
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals("pixel:api", agent.avatarUrl)
        assertEquals(Presence.Active, agent.presence)
        assertEquals(MemberKind.Agent, members.first { it.id.value == "agent-2" }.kind)
        assertTrue(addResult is DataResult.Content)
        assertEquals("agent-1", (addResult as DataResult.Content).data.memberId.value)
        assertEquals("""channel-1:{"agentId":"agent-1"}""", api.addedMembers.single())
        assertTrue(removeResult is DataResult.Content)
        assertEquals("agent-1", (removeResult as DataResult.Content).data.memberId.value)
        assertEquals("channel-1/agent/agent-1", api.removedMembers.single())
    }

    @Test
    fun memberRepositoryTreatsAgentsEndpointFallbackRowsAsAgents() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "members": [
                    {"id":"user-3","displayName":"Taylor","handle":"taylor","kind":"user","role":"member","presence":"online"}
                  ]
                }
            """.trimIndent(),
            memberProfileJson = """
                {
                  "profile": {
                    "id":"user-3",
                    "displayName":"Taylor Profile",
                    "handle":"taylor",
                    "role":"member",
                    "presence":"offline"
                  }
                }
            """.trimIndent(),
            settingsAgentsJson = """
                {
                  "items": [
                    {
                      "id":"agent-9",
                      "name":"Runtime Agent",
                      "handle":"runtime-agent",
                      "role":"worker",
                      "presence":"online",
                      "machineName":"Jiachengs-MacBook-Pro.local"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val members = (result as DataResult.Content).data.members
        val agent = members.single { it.id.value == "agent-9" }
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals("Runtime Agent", agent.displayName)
        assertTrue(agent.permissionBadges.contains("computer:Jiachengs-MacBook-Pro.local"))
    }

    @Test
    fun memberRepositoryMapsNestedChannelMembersContainer() {
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelMembersJson = """
                    {
                      "channel": {
                        "canAddMembers": false,
                        "members": {
                          "humans": [
                            {
                              "user": {
                                "id": "user-2",
                                "displayName": "Artin",
                                "handle": "artin",
                                "avatarUrl": "https://cdn.example/artin.png"
                              },
                              "role": "owner",
                              "presence": "away"
                            }
                          ],
                          "agents": [
                            {
                              "agent": {
                                "id": "agent-1",
                                "name": "API Integration",
                                "handle": "api",
                                "avatar": "pixel:api"
                              },
                              "role": "developer",
                              "presence": "working"
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
            ),
            warningLogger = {}
        )

        val result =
            runSuspend {
                withSlockRequestPriority(
                    priority = SlockRequestPriority.Visible,
                    key = "test:channel-members",
                    reason = "unit_test"
                ) {
                    repository.getChannelMembers(SlockRequestPriority.Visible, ChannelId("channel-1"))
                }
            }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(false, snapshot.canAddMembers)
        assertEquals(2, snapshot.members.size)
        assertEquals("Artin", snapshot.members.first { it.id.value == "user-2" }.displayName)
        assertEquals(MemberKind.Agent, snapshot.members.first { it.id.value == "agent-1" }.kind)
    }

    @Test
    fun memberRepositoryResolvesMachineIdBadgesToMachineNames() {
        val api = FakeSlockApi(
            serverMembersJson = """{"members":[]}""",
            settingsAgentsJson = """
                {
                  "agents": [
                    {
                      "id":"agent-9",
                      "name":"Runtime Agent",
                      "handle":"runtime-agent",
                      "role":"worker",
                      "presence":"online",
                      "machineId":"machine-9"
                    }
                  ]
                }
            """.trimIndent(),
            settingsMachinesJson = """
                {
                  "machines": [
                    {"id":"machine-9","displayName":"Mac Studio"}
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val agent = (result as DataResult.Content).data.members.single { it.id.value == "agent-9" }
        assertTrue(agent.permissionBadges.contains("computer:Mac Studio"))
        assertFalse(agent.permissionBadges.any { it.contains("machine-9") })
    }

    @Test
    fun memberRepositoryLoadsAgentSkillsFromAgentSkillsEndpoint() {
        val api = FakeSlockApi(
            agentSkillsJson = """
                {
                  "global": [
                    {
                      "name": "openai-docs",
                      "description": "Use official OpenAI documentation.",
                      "sourcePath": "~/.codex/skills/.system"
                    }
                  ],
                  "workspace": [
                    {
                      "name": "android-regression",
                      "description": "Run focused Android regression checks.",
                      "sourcePath": "/repo/.codex/skills"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getAgentSkills(SlockRequestPriority.Visible, MemberId("agent-1")) }

        assertTrue(result is DataResult.Content)
        val groups = (result as DataResult.Content).data.groups
        assertEquals(2, groups.size)
        val global = groups.first { it.title == "Global" }
        assertEquals("~/.codex/skills/.system", global.paths.single().path)
        assertEquals("openai-docs", global.paths.single().skills.single().name)
        assertEquals("Use official OpenAI documentation.", global.paths.single().skills.single().detail)
        val workspace = groups.first { it.title == "Workspace" }
        assertEquals("/repo/.codex/skills", workspace.paths.single().path)
        assertEquals("android-regression", workspace.paths.single().skills.single().name)
    }

    @Test
    fun memberRepositoryLoadsAgentWorkspacesFromMachineEndpoint() {
        val api = FakeSlockApi(
            settingsAgentsJson = """
                {
                  "agents": [
                    {
                      "id": "agent-1",
                      "name": "Android Developer",
                      "machineId": "machine-7",
                      "machineName": "Mac Studio"
                    }
                  ]
                }
            """.trimIndent(),
            settingsMachineWorkspacesJson = """
                {
                  "workspaces": [
                    {
                      "id": "workspace-1",
                      "name": "Slock",
                      "path": "/Users/artin/AndroidStudioProjects/Slock",
                      "status": "active",
                      "activeTaskCount": 3,
                      "lastActiveAt": "2026-05-13T19:30:00Z"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getAgentWorkspaces(SlockRequestPriority.Visible, MemberId("agent-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("machine-7", snapshot.computerId)
        assertEquals("Mac Studio", snapshot.computerName)
        assertEquals(1, snapshot.workspaces.size)
        assertEquals("/Users/artin/AndroidStudioProjects/Slock", snapshot.workspaces.single().path)
        assertEquals(listOf("server-1:machine-7"), api.settingsMachineWorkspaceCalls)
    }

    @Test
    fun memberRepositoryUpdatesAgentProfileViaPatchAgentsEndpoint() {
        val api = FakeSlockApi(
            updateAgentJson = """
                {
                  "id": "agent-9",
                  "name": "Updated Agent",
                  "description": "Updated description",
                  "handle": "updated-agent",
                  "status": "online"
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend {
            repository.updateAgentProfile(
                SlockRequestPriority.Foreground,
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-9"),
                    displayName = " Updated Agent ",
                    description = " Updated description "
                )
            )
        }

        assertTrue(result is DataResult.Content)
        val profile = (result as DataResult.Content).data
        assertEquals("Updated Agent", profile.displayName)
        assertEquals("Updated description", profile.description)
        assertNull(profile.roleDescription)
        assertEquals(MemberKind.Agent, profile.kind)
        assertEquals(
            """agent-9:{"name":"Updated Agent","displayName":"Updated Agent","description":"Updated description"}""",
            api.updatedAgents.single()
        )
    }

    @Test
    fun memberRepositoryUsesUserIdForHumanProfileEnrichmentWhenMembershipIdIsPresent() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "members": [
                    {
                      "id": "membership-1",
                      "userId": "user-1",
                      "displayName": "artin",
                      "kind": "user"
                    }
                  ]
                }
            """.trimIndent(),
            memberProfileJson = """
                {
                  "profile": {
                    "id":"user-1",
                    "displayName":"artin",
                    "avatar":"pixel:diamond"
                  }
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val human = (result as DataResult.Content).data.members.single { it.kind == MemberKind.Human }
        assertEquals("user-1", human.id.value)
        assertEquals("pixel:diamond", human.avatar?.wireValue)
        assertEquals(listOf("user-1"), api.memberProfileCalls)
    }

    @Test
    fun memberRepositoryUsesNestedUserIdBeforeMembershipWrapperId() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "members": [
                    {
                      "id": "membership-1",
                      "kind": "user",
                      "user": {
                        "id": "user-1",
                        "displayName": "artin"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            memberProfileJson = """
                {
                  "profile": {
                    "id":"user-1",
                    "displayName":"artin",
                    "avatar":"pixel:diamond"
                  }
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val human = (result as DataResult.Content).data.members.single { it.kind == MemberKind.Human }
        assertEquals("user-1", human.id.value)
        assertEquals("pixel:diamond", human.avatar?.wireValue)
        assertEquals(listOf("user-1"), api.memberProfileCalls)
    }

    @Test
    fun memberRepositoryDoesNotTreatFallbackHumansAsAgents() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "data": [
                    {"id":"user-3","displayName":"Taylor","handle":"taylor","kind":"user","role":"member","presence":"online"},
                    {"id":"agent-2","displayName":"QA Agent","handle":"qa-agent","kind":"agent","role":"tester","activity":"thinking"}
                  ]
                }
            """.trimIndent(),
            memberProfileJson = """
                {
                  "profile": {
                    "id":"user-3",
                    "displayName":"Slock User",
                    "handle":"user",
                    "role":"owner",
                    "presence":"offline"
                  }
                }
            """.trimIndent(),
            settingsAgentsJson = """
                {
                  "items": [
                    {"id":"user-3","displayName":"Taylor","handle":"taylor","kind":"user","role":"member","presence":"online"},
                    {"id":"agent-2","displayName":"QA Agent","handle":"qa-agent","kind":"agent","role":"tester","activity":"thinking"}
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(SlockRequestPriority.Visible, ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val members = (result as DataResult.Content).data.members
        assertEquals(1, members.count { it.id.value == "user-3" && it.kind == MemberKind.Human })
        assertEquals(
            members.joinToString { "${it.kind}:${it.id.value}:${it.displayName}" },
            0,
            members.count { it.id.value == "user-3" && it.kind == MemberKind.Agent }
        )
        val agent = members.single { it.id.value == "agent-2" && it.kind == MemberKind.Agent }
        assertEquals(AgentStatus.Thinking, agent.agentStatus)
        assertEquals(Presence.Active, agent.presence)
    }

    @Test
    fun memberRepositoryUsesActivityLogEndpointAndMergesCachedActivity() {
        val api = FakeSlockApi(
            agentActivityLogJson = """
                [
                  {"timestamp":1715216400000,"entry":{"id":"activity-1","type":"thinking","message":"Old body"}},
                  {"id":"activity-2","type":"tool_start","body":"Tool started","timestamp":"2026-05-09T02:00:00Z"}
                ]
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val first = runSuspend { repository.getAgentActivity(SlockRequestPriority.Visible, MemberId("agent-1")) }
        api.agentActivityLogJson = """
            [
              {"id":"activity-2","type":"tool_start","body":"Tool updated","timestamp":"2026-05-09T02:00:00Z"},
              {"id":"activity-3","type":"text","body":"New text","timestamp":"2026-05-09T03:00:00Z"}
            ]
        """.trimIndent()
        val second = runSuspend { repository.getAgentActivity(SlockRequestPriority.Visible, MemberId("agent-1")) }

        assertTrue(first is DataResult.Content)
        assertTrue(second is DataResult.Content)
        assertEquals(listOf("agent-1:50", "agent-1:50"), api.agentActivityLogCalls)
        val activity = (second as DataResult.Content).data.activity
        assertEquals(3, activity.size)
        assertEquals("Old body", activity.first { it.id == "activity-1" }.body)
        assertEquals("1715216400000", activity.first { it.id == "activity-1" }.timestamp)
        assertEquals("Tool updated", activity.first { it.id == "activity-2" }.body)
        assertEquals("New text", activity.last().body)
    }

    @Test
    fun memberRepositoryFallsBackToAgentListCurrentActivityWhenActivityLogIsEmpty() {
        val api = FakeSlockApi(
            settingsAgentsJson = """
                {"agents":[
                  {"id":"agent-1","name":"API Agent","activity":"thinking","activityDetail":"Reading server logs","status":"active"}
                ]}
            """.trimIndent(),
            agentActivityLogJson = """[]"""
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getAgentActivity(SlockRequestPriority.Visible, MemberId("agent-1")) }

        assertTrue(result is DataResult.Content)
        val activity = (result as DataResult.Content).data.activity.single()
        assertEquals("thinking", activity.type)
        assertEquals("Thinking", activity.title)
        assertEquals("Reading server logs", activity.body)
        assertEquals(listOf("agent-1:50"), api.agentActivityLogCalls)
    }

    @Test
    fun conversationRepositoryCreatesOrOpensThreadFromParentMessage() {
        val api = FakeSlockApi(
            createThreadJson = """{"thread":{"replyThreadChannelId":"thread-parent-1","rootMessageId":"parent-1","repliesCount":3}}""",
            threadActionJson = """{"data":{"replyThreadId":"thread-parent-1","parentId":"parent-1","childCount":3}}"""
        )
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val opened = runSuspend {
            repository.createOrOpenThread(ChannelId("channel-1"), MessageId("parent-1"))
        }
        val followed = runSuspend {
            repository.followThread(MessageId("parent-1"))
        }

        assertTrue(opened is DataResult.Content)
        assertEquals("thread-parent-1", (opened as DataResult.Content).data.threadChannelId.value)
        assertEquals("parent-1", opened.data.parentMessageId?.value)
        assertEquals(3, opened.data.replyCount)
        assertTrue(followed is DataResult.Content)
        assertEquals("thread-parent-1", (followed as DataResult.Content).data.threadChannelId.value)
        assertEquals("parent-1", followed.data.parentMessageId?.value)
        assertEquals(3, followed.data.replyCount)
        assertEquals("""{"parentMessageId":"parent-1"}""", api.createThreadBodies.single())
        assertEquals("""{"parentMessageId":"parent-1"}""", api.followThreadBodies.single())
    }

    @Test
    fun conversationRepositoryMapsMessageContextChannelForThreadCreation() {
        val api =
                FakeSlockApi(
                        messageContextJson =
                                """
                                {
                                  "conversation": {"id":"parent-channel-1","name":"android-dev"},
                                  "message": {
                                    "id":"parent-1",
                                    "channelId":"parent-channel-1",
                                    "threadId":"parent-channel-1",
                                    "content":"Start a thread"
                                  }
                                }
                                """
                                        .trimIndent()
                )
        val repository =
                RealConversationRepository(
                        store = InMemoryAuthSessionStore(sampleSession),
                        api = api,
                        warningLogger = {}
                )

        val result = runSuspend { repository.getMessageContext(MessageId("parent-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("parent-channel-1", snapshot.conversation.channelId?.value)
        assertEquals("parent-channel-1", snapshot.messages.single().channelId?.value)
        assertEquals(MessageId("parent-1"), snapshot.parentMessage?.id)
    }

    @Test
    fun conversationRepositoryMapsNestedMessageContextTargetMessage() {
        val api =
                FakeSlockApi(
                        messageContextJson =
                                """
                                {
                                  "data": {
                                    "channel": {"id":"kmp-channel","name":"KMP"},
                                    "targetMessage": {
                                      "id":"d98c61a1-03c4-4239-923e-f84572d0b534",
                                      "channelId":"kmp-channel",
                                      "threadId":"kmp-channel",
                                      "body":"Define the KMP realtime transport contract",
                                      "createdAt":"2026-05-12T05:00:00.000Z"
                                    }
                                  }
                                }
                                """
                                        .trimIndent()
                )
        val repository =
                RealConversationRepository(
                        store = InMemoryAuthSessionStore(sampleSession),
                        api = api,
                        warningLogger = {}
                )

        val result =
                runSuspend {
                    repository.getMessageContext(
                            MessageId("d98c61a1-03c4-4239-923e-f84572d0b534")
                    )
                }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("kmp-channel", snapshot.conversation.channelId?.value)
        assertEquals("KMP", snapshot.conversation.name)
        assertEquals(MessageId("d98c61a1-03c4-4239-923e-f84572d0b534"), snapshot.parentMessage?.id)
        assertEquals("Define the KMP realtime transport contract", snapshot.parentMessage?.body)
    }

    @Test
    fun conversationRepositoryKeepsParentOnlyMessageContext() {
        val api =
                FakeSlockApi(
                        messageContextJson =
                                """
                                {
                                  "data": {
                                    "channel": {"id":"tips-and-tricks","name":"tips-and-tricks"},
                                    "parentMessage": {
                                      "id":"04fcdf5e-d868-440a-aaf2-2944b5c0e539",
                                      "channelId":"tips-and-tricks",
                                      "threadId":"tips-and-tricks",
                                      "body":"Share tips and tricks here",
                                      "createdAt":"2026-05-13T07:00:00.000Z",
                                      "threadChannelId":"reply-thread-1"
                                    }
                                  }
                                }
                                """
                                        .trimIndent()
                )
        val repository =
                RealConversationRepository(
                        store = InMemoryAuthSessionStore(sampleSession),
                        api = api,
                        warningLogger = {}
                )

        val result =
                runSuspend {
                    repository.getMessageContext(
                            MessageId("04fcdf5e-d868-440a-aaf2-2944b5c0e539")
                    )
                }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertTrue(snapshot.messages.isEmpty())
        assertEquals(MessageId("04fcdf5e-d868-440a-aaf2-2944b5c0e539"), snapshot.parentMessage?.id)
        assertEquals("Share tips and tricks here", snapshot.parentMessage?.body)
        assertEquals("reply-thread-1", snapshot.parentMessage?.threadChannelId?.value)
    }

    @Test
    fun conversationRepositoryDoesNotUseGenericActionIdAsThreadChannelId() {
        val api = FakeSlockApi(
            createThreadJson =
                    """{"thread":{"id":"thread-row-1","threadChannelId":"thread-channel-1","rootMessageId":"parent-1","repliesCount":1}}""",
            threadActionJson =
                    """{"data":{"id":"follow-row-1","threadChannel":{"id":"follow-thread-channel-1"},"parentId":"parent-1","childCount":1}}"""
        )
        val repository = RealConversationRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val opened = runSuspend {
            repository.createOrOpenThread(ChannelId("parent-channel-1"), MessageId("parent-1"))
        }
        val followed = runSuspend {
            repository.followThread(MessageId("parent-1"))
        }

        assertTrue(opened is DataResult.Content)
        assertEquals("thread-channel-1", (opened as DataResult.Content).data.threadChannelId.value)
        assertTrue(followed is DataResult.Content)
        assertEquals(
                "follow-thread-channel-1",
                (followed as DataResult.Content).data.threadChannelId.value
        )
    }

    private class FakeSlockApi(
        private val settingsAccountJson: String = """{}""",
        private val settingsAgentsJson: String = """{"agents":[]}""",
        private val settingsMachinesJson: String = """{"machines":[]}""",
        private val settingsMachineWorkspacesJson: String = """{"workspaces":[]}""",
        var channelsJson: String = """{"channels":[]}""",
        private val createChannelJson: String = """{"id":"channel-1","name":"android-dev"}""",
        private val joinChannelJson: String = """{"ok":true}""",
        private val leaveChannelJson: String = """{"ok":true}""",
        private val serverMembersJson: String = """{"members":[]}""",
        private val memberProfileJson: String = """{}""",
        private val updateAgentJson: String = """{}""",
        private val channelMembersJson: String = """{"members":[]}""",
        private val addChannelMemberJson: String = """{"ok":true}""",
        private val directMessagesJson: String = """[]""",
        private val directMessagesFailure: Exception? = null,
        private val openDirectMessageJson: String = """{"channel":{"id":"dm-1","name":"Direct message","type":"dm"}}""",
        var agentActivityLogJson: String = """[]""",
        private val agentSkillsJson: String = """{"global":[],"workspace":[]}""",
        private val unreadCountsJson: String = """{}""",
        private val readReceiptJson: String = """{"ok":true}""",
        private val serverTasksJson: String = """{"tasks":[]}""",
        private val channelTasksJson: String = """{"tasks":[]}""",
        private val claimTaskJson: String = """{"task":{}}""",
        private val updateTaskStatusJson: String = """{"task":{}}""",
        private val inboxJson: String = """{"items":[]}""",
        private val savedJson: String = """{"items":[]}""",
        private val searchJson: String = """{"results":[]}""",
        private val unreadSummaryJson: String = """{"unreadCount":0}""",
        private val channelMessagesJson: String = """{"messages":[]}""",
        private val messageContextJson: String = """{"messages":[]}""",
        private val messageSyncJson: String = """{"messages":[]}""",
        private val sendMessageJson: String = """{"message":{}}""",
        private val attachmentUrlJson: String = """{"url":"https://example.invalid/attachments/attachment-1"}""",
        private val channelFilesJson: String = """{"files":[]}""",
        private val followedThreadsJson: String = """{"threads":[]}""",
        private val channelThreadsJson: String = """{"threads":[]}""",
        private val createThreadJson: String = """{"threadChannelId":"thread-1"}""",
        private val threadActionJson: String = """{"ok":true}""",
        private val archiveChannelJson: String = """{"ok":true}""",
        private val deleteChannelJson: String = """{"ok":true}""",
        private val responseCode: Int = 200
    ) : SlockApi {
        var unreadSummaryCallCount = 0
            private set
        val addedMembers = mutableListOf<String>()
        val removedMembers = mutableListOf<String>()
        val joinedChannelIds = mutableListOf<String>()
        val leftChannelIds = mutableListOf<String>()
        val archivedChannelIds = mutableListOf<String>()
        val deletedChannelIds = mutableListOf<String>()
        val seqReadRequests = mutableListOf<String>()
        val readAllRequests = mutableListOf<String>()
        val createThreadBodies = mutableListOf<String>()
        val followThreadBodies = mutableListOf<String>()
        val openDirectMessageBodies = mutableListOf<String>()
        val agentActivityLogCalls = mutableListOf<String>()
        val memberProfileCalls = mutableListOf<String>()
        val updatedAgents = mutableListOf<String>()
        val claimedTaskIds = mutableListOf<String>()
        val channelTaskCalls = mutableListOf<String>()
        val updatedTaskStatusBodies = mutableListOf<String>()
        val channelMessagesBeforeSeqCalls = mutableListOf<Long?>()
        val channelArchivedQueries = mutableListOf<String?>()
        val searchMessagesCalls = mutableListOf<SearchMessagesCall>()
        val settingsMachineWorkspaceCalls = mutableListOf<String>()
        var inboxReadAllCallCount = 0
            private set

        override suspend fun settingsAccount(priority: SlockRequestPriority): ApiResponse =
            response(settingsAccountJson)

        override suspend fun updateSettingsAccount(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(settingsAccountJson)

        override suspend fun settingsMachines(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response(settingsMachinesJson)

        override suspend fun settingsMachineWorkspaces(priority: SlockRequestPriority,
            serverId: String,
            machineId: String
        ): ApiResponse {
            settingsMachineWorkspaceCalls += "$serverId:$machineId"
            return response(settingsMachineWorkspacesJson)
        }

        override suspend fun settingsAgents(priority: SlockRequestPriority): ApiResponse =
            response(settingsAgentsJson)

        override suspend fun updateAgent(priority: SlockRequestPriority,
            agentId: String,
            body: RequestBody
        ): ApiResponse {
            updatedAgents += "$agentId:${body.bodyAsString()}"
            return response(updateAgentJson)
        }

        override suspend fun channels(priority: SlockRequestPriority, archived: String?): ApiResponse {
            channelArchivedQueries += archived
            return response(channelsJson)
        }

        override suspend fun createChannel(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(createChannelJson)

        override suspend fun joinChannel(priority: SlockRequestPriority, channelId: String): ApiResponse {
            joinedChannelIds += channelId
            return response(joinChannelJson)
        }

        override suspend fun leaveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse {
            leftChannelIds += channelId
            return response(leaveChannelJson)
        }

        override suspend fun archiveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse {
            archivedChannelIds += channelId
            return response(archiveChannelJson)
        }

        override suspend fun deleteChannel(priority: SlockRequestPriority, channelId: String): ApiResponse {
            deletedChannelIds += channelId
            return response(deleteChannelJson)
        }

        override suspend fun serverMembers(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response(serverMembersJson)

        override suspend fun updateSettingsServerProfile(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"id":"$serverId","name":"Slock Android"}""")

        override suspend fun settingsServerUsage(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response("""{"usage":{}}""")

        override suspend fun settingsBillingSubscription(priority: SlockRequestPriority): ApiResponse =
            response("""{"subscription":{}}""")

        override suspend fun createSettingsServerInvite(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"invite":{}}""")

        override suspend fun settingsServerOnboardingSettings(priority: SlockRequestPriority,
            serverId: String
        ): ApiResponse =
            response("""{"settings":{}}""")

        override suspend fun updateSettingsServerOnboardingSettings(priority: SlockRequestPriority,
            serverId: String,
            body: RequestBody
        ): ApiResponse =
            response("""{"settings":{}}""")

        override suspend fun deleteSettingsServer(priority: SlockRequestPriority, serverId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun memberProfile(priority: SlockRequestPriority, serverId: String, memberId: String): ApiResponse {
            memberProfileCalls += memberId
            return response(memberProfileJson)
        }

        override suspend fun agentWorkspaceFiles(priority: SlockRequestPriority, agentId: String, dirPath: String?): ApiResponse =
            response("""{"files":[]}""")

        override suspend fun readAgentWorkspaceFile(priority: SlockRequestPriority, agentId: String, path: String): ApiResponse =
            response("""{"path":"$path","content":"","binary":false,"size":0}""")

        override suspend fun agentSkills(priority: SlockRequestPriority, agentId: String): ApiResponse =
            response(agentSkillsJson)

        override suspend fun agentDirectMessages(priority: SlockRequestPriority, agentId: String): ApiResponse =
            response("""[]""")

        override suspend fun reminders(priority: SlockRequestPriority, ownerAgentId: String, status: String): ApiResponse =
            response("""{"reminders":[]}""")

        override suspend fun agentActivityLog(priority: SlockRequestPriority, agentId: String, limit: Int): ApiResponse =
            response(agentActivityLogJson).also { agentActivityLogCalls += "$agentId:$limit" }

        override suspend fun channelMembers(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response(channelMembersJson)

        override suspend fun addChannelMember(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse {
            addedMembers += "$channelId:${body.bodyAsString()}"
            return response(addChannelMemberJson)
        }

        override suspend fun removeChannelMember(priority: SlockRequestPriority,
            channelId: String,
            kind: String,
            memberId: String
        ): ApiResponse {
            removedMembers += "$channelId/$kind/$memberId"
            return response("""{"ok":true,"removed":{"id":"$memberId","kind":"$kind"}}""")
        }

        override suspend fun directMessages(priority: SlockRequestPriority): ApiResponse =
            directMessagesFailure?.let { throw it } ?:
            response(directMessagesJson)

        override suspend fun openDirectMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse {
            openDirectMessageBodies += body.bodyAsString()
            return response(openDirectMessageJson)
        }

        override suspend fun channelUnreadCounts(priority: SlockRequestPriority): ApiResponse =
            response(unreadCountsJson)

        override suspend fun markChannelReadToSeq(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse {
            seqReadRequests += "$channelId:${body.bodyAsString().substringAfter(":").substringBefore("}")}"
            return response(readReceiptJson)
        }

        override suspend fun markChannelReadAll(priority: SlockRequestPriority, channelId: String): ApiResponse {
            readAllRequests += channelId
            return response(readReceiptJson)
        }

        override suspend fun markChannelUnread(priority: SlockRequestPriority, channelId: String): ApiResponse =
            response(readReceiptJson)

        override suspend fun inbox(priority: SlockRequestPriority, filter: String, limit: Int, offset: Int): ApiResponse {
            return response(inboxJson)
        }

        override suspend fun markInboxReadAll(priority: SlockRequestPriority): ApiResponse =
            response(readReceiptJson).also { inboxReadAllCallCount += 1 }

        override suspend fun serverTasks(priority: SlockRequestPriority): ApiResponse =
            response(serverTasksJson)

        override suspend fun channelTasks(priority: SlockRequestPriority, channelId: String): ApiResponse {
            channelTaskCalls += channelId
            return response(channelTasksJson)
        }

        override suspend fun claimTask(priority: SlockRequestPriority, taskId: String): ApiResponse {
            claimedTaskIds += taskId
            return response(claimTaskJson)
        }

        override suspend fun updateTaskStatus(priority: SlockRequestPriority,
            taskId: String,
            body: RequestBody
        ): ApiResponse {
            updatedTaskStatusBodies += "$taskId:${body.bodyAsString()}"
            return response(updateTaskStatusJson)
        }

        override suspend fun saved(priority: SlockRequestPriority, limit: Int, offset: Int): ApiResponse =
            response(savedJson)

        override suspend fun saveMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun unsaveMessage(priority: SlockRequestPriority, messageId: String): ApiResponse =
            response("""{"ok":true}""")

        override suspend fun searchMessages(
            priority: SlockRequestPriority,
            query: String,
            limit: Int,
            offset: Int,
            sort: String?,
            senderId: String?,
            channelId: String?,
            after: String?,
            before: String?
        ): ApiResponse {
            searchMessagesCalls +=
                SearchMessagesCall(
                    query = query,
                    limit = limit,
                    offset = offset,
                    sort = sort,
                    senderId = senderId,
                    channelId = channelId,
                    after = after,
                    before = before
                )
            return response(searchJson)
        }

        override suspend fun channelMessages(priority: SlockRequestPriority,
            channelId: String,
            limit: Int,
            beforeSeq: Long?
        ): ApiResponse {
            channelMessagesBeforeSeqCalls += beforeSeq
            return response(channelMessagesJson)
        }

        override suspend fun messageContext(priority: SlockRequestPriority, messageId: String): ApiResponse =
            response(messageContextJson)

        override suspend fun messageSync(priority: SlockRequestPriority,
            sinceSeq: Long,
            limit: Int,
            channelId: String?
        ): ApiResponse = response(messageSyncJson)

        override suspend fun sendMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(sendMessageJson)

        override suspend fun uploadAttachments(priority: SlockRequestPriority,
            channelId: RequestBody,
            files: List<okhttp3.MultipartBody.Part>
        ): ApiResponse =
            response("""{"attachments":[{"id":"attachment-1","filename":"sample.png","mimeType":"image/png","sizeLabel":"12 KB"}]}""")

        override suspend fun attachmentUrl(priority: SlockRequestPriority, attachmentId: String): ApiResponse =
            response(attachmentUrlJson)

        override suspend fun channelFiles(priority: SlockRequestPriority, channelId: String, limit: Int, cursor: String?): ApiResponse =
            response(channelFilesJson)

        override suspend fun channelThreads(priority: SlockRequestPriority, channelId: String, limit: Int): ApiResponse =
            response(channelThreadsJson)

        override suspend fun createOrOpenThread(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse {
            createThreadBodies += body.bodyAsString()
            return response(createThreadJson)
        }

        override suspend fun followedThreads(priority: SlockRequestPriority, limit: Int): ApiResponse =
            response(followedThreadsJson)

        override suspend fun followThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse {
            followThreadBodies += body.bodyAsString()
            return response(threadActionJson)
        }

        override suspend fun unfollowThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(threadActionJson)

        override suspend fun markThreadDone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(threadActionJson)

        override suspend fun markThreadUndone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            response(threadActionJson)

        override suspend fun serverUnreadSummary(priority: SlockRequestPriority): ApiResponse =
            response(unreadSummaryJson).also { unreadSummaryCallCount += 1 }

        private fun response(json: String): ApiResponse =
            if (responseCode in 200..299) {
                ApiResponse.success(json.jsonBody())
            } else {
                ApiResponse.error(responseCode, """{"error":"failed"}""".jsonBody())
            }
    }

    private class InMemoryAuthSessionStore(
        private var session: AuthSession?
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()

        private val sampleSession = AuthSession(
            user = AuthUser(
                id = "user-1",
                email = "user@slock.ai",
                name = "User",
                displayName = "Slock User",
                description = null,
                avatarUrl = null,
                emailVerified = true
            ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(
                AuthServer(
                    id = "server-1",
                    name = "Slock Android",
                    slug = "slock-android",
                    ownerId = "user-1",
                    onboardingAgentId = "agent-1",
                    plan = "team",
                    planDowngradedAt = null,
                    role = "owner",
                    createdAt = "2026-05-06T00:00:00Z"
                )
            )
        )

        private fun String.jsonBody(): ResponseBody = toResponseBody(json)

        private fun RequestBody.bodyAsString(): String {
            val buffer = Buffer()
            writeTo(buffer)
            return buffer.readUtf8()
        }
    }

    private data class SearchMessagesCall(
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

private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }
