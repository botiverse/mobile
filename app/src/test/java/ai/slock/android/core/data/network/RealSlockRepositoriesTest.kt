package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.data.repository.RemoveChannelMemberRequest
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.model.AttachmentId
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
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okhttp3.RequestBody
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealSlockRepositoriesTest {
    @Test
    fun channelRepositoryMapsChannelsAndDmsToHomeSnapshot() {
        val repository = RealChannelRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
                channelsJson = """
                    {"channels":[{"id":"channel-1","name":"android-dev","description":"Android work","unreadCount":3,"lastMessagePreview":"Ready"}]}
                """.trimIndent(),
                directMessagesJson = """
                    [{"id":"dm-1","peerDisplayName":"Android-Developer","unreadCount":2,"lastMessagePreview":"Done"}]
                """.trimIndent()
            )
        )

        val result = runSuspend { repository.getHome(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals("Slock Android", snapshot.server.name)
        assertEquals("android-dev", snapshot.channels.single().name)
        assertEquals(ConversationKind.DirectMessage, snapshot.directMessages.single().kind)
        assertEquals(5, snapshot.unreadTotal)
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
        assertTrue(snapshot.channels.single().avatar is AvatarReference.Random)
        assertEquals(1, snapshot.directMessages.size)
        assertEquals("Android-Developer", snapshot.directMessages.single().name)
        assertTrue(snapshot.directMessages.single().avatar is AvatarReference.Url)
        assertEquals(5, snapshot.unreadTotal)
        assertTrue(warnings.none { it.contains("token", ignoreCase = true) || it.contains("Bearer") })
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
    fun inboxRepositoryMapsPagingMetadataAndStableThreadKeys() {
        val repository = RealInboxRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = FakeSlockApi(
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
        )

        val result = runSuspend {
            repository.loadMore(ServerId("server-1"), InboxFilter.Unread, offset = 30, limit = 30)
        }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(true, snapshot.hasMore)
        assertEquals(31, snapshot.nextOffset)
        assertEquals(12, snapshot.totalCount)
        assertEquals("thread:thread-1", snapshot.items.single().key)
        assertEquals(InboxItemKind.Thread, snapshot.items.single().kind)
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
                          "thread_id":"a98ec1f2-1111-4222-8333-fedcba987654",
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
                          "reply_count":7,
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
        assertEquals("channel-1", chatMessage.threadId.value)
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
                          "reply_count":7
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
                      "has_more_before": true
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
        val sent = runSuspend { repository.sendMessage(ThreadId("channel-1"), "Sent") }
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
                      "messageId": "message-1",
                      "parentMessageId": "parent-message-1",
                      "threadChannelId": "thread-message-1",
                      "claimedById": "user-1",
                      "claimedByName": "Artin",
                      "channel": {"name": "android-dev"},
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
                    "users": [
                      {"user":{"id":"user-2","displayName":"Artin","handle":"artin","avatar_url":"https://cdn.example/artin.png"},"role":"owner","presence":"away"}
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

        val serverMembers = runSuspend { repository.getMembers(ServerId("server-1")) }
        val channelMembers = runSuspend { repository.getChannelMembers(ChannelId("channel-1")) }
        val addResult = runSuspend {
            repository.addChannelMember(
                AddChannelMemberRequest(
                    channelId = ChannelId("channel-1"),
                    memberId = MemberId("agent-1"),
                    kind = MemberKind.Agent
                )
            )
        }
        val removeResult = runSuspend {
            repository.removeChannelMember(
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
        assertEquals("Artin Profile", humanServerMember.displayName)
        assertEquals(Presence.Offline, humanServerMember.presence)
        assertEquals("https://cdn.example/artin-profile.png", humanServerMember.avatarUrl)
        val serverAgent = serverMemberRows.first { it.id.value == "agent-2" }
        assertEquals(MemberKind.Agent, serverAgent.kind)
        assertEquals("random:qa", serverAgent.avatarUrl)
        val sameIdHuman = serverMemberRows.first { it.id.value == "user-2" && it.kind == MemberKind.Human }
        val sameIdAgent = serverMemberRows.first { it.id.value == "user-2" && it.kind == MemberKind.Agent }
        assertEquals("Artin Profile", sameIdHuman.displayName)
        assertEquals("Artin Agent", sameIdAgent.displayName)
        assertTrue(channelMembers is DataResult.Content)
        val members = (channelMembers as DataResult.Content).data.members
        assertEquals(4, members.size)
        val currentHuman = members.first { it.id.value == "user-1" }
        assertEquals(MemberKind.Human, currentHuman.kind)
        assertEquals("Artin Profile", currentHuman.displayName)
        assertEquals(Presence.Offline, members.first { it.id.value == "user-2" }.presence)
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
                    {"id":"agent-9","name":"Runtime Agent","handle":"runtime-agent","role":"worker","presence":"online"}
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val members = (result as DataResult.Content).data.members
        val agent = members.single { it.id.value == "agent-9" }
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals("Runtime Agent", agent.displayName)
    }

    @Test
    fun memberRepositoryDoesNotTreatFallbackHumansAsAgents() {
        val api = FakeSlockApi(
            serverMembersJson = """
                {
                  "data": [
                    {"id":"user-3","displayName":"Taylor","handle":"taylor","kind":"user","role":"member","presence":"online"},
                    {"id":"agent-2","displayName":"QA Agent","handle":"qa-agent","kind":"agent","role":"tester","presence":"online"}
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
                    {"id":"agent-2","displayName":"QA Agent","handle":"qa-agent","kind":"agent","role":"tester","presence":"online"}
                  ]
                }
            """.trimIndent()
        )
        val repository = RealMemberRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            api = api,
            warningLogger = {}
        )

        val result = runSuspend { repository.getMembers(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        val members = (result as DataResult.Content).data.members
        assertEquals(1, members.count { it.id.value == "user-3" && it.kind == MemberKind.Human })
        assertEquals(
            members.joinToString { "${it.kind}:${it.id.value}:${it.displayName}" },
            0,
            members.count { it.id.value == "user-3" && it.kind == MemberKind.Agent }
        )
        assertEquals(1, members.count { it.id.value == "agent-2" && it.kind == MemberKind.Agent })
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

    private class FakeSlockApi(
        private val settingsAccountJson: String = """{}""",
        private val settingsSessionsJson: String = """{"sessions":[]}""",
        private val revokeSessionJson: String = """{"ok":true}""",
        private val settingsAgentsJson: String = """{"agents":[]}""",
        private val channelsJson: String = """{"channels":[]}""",
        private val createChannelJson: String = """{"id":"channel-1","name":"android-dev"}""",
        private val joinChannelJson: String = """{"ok":true}""",
        private val serverMembersJson: String = """{"members":[]}""",
        private val memberProfileJson: String = """{}""",
        private val channelMembersJson: String = """{"members":[]}""",
        private val addChannelMemberJson: String = """{"ok":true}""",
        private val directMessagesJson: String = """[]""",
        private val directMessagesFailure: Exception? = null,
        private val unreadCountsJson: String = """{}""",
        private val readReceiptJson: String = """{"ok":true}""",
        private val serverTasksJson: String = """{"tasks":[]}""",
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
        private val followedThreadsJson: String = """{"threads":[]}""",
        private val channelThreadsJson: String = """{"threads":[]}""",
        private val createThreadJson: String = """{"threadChannelId":"thread-1"}""",
        private val threadActionJson: String = """{"ok":true}""",
        private val responseCode: Int = 200
    ) : SlockApi {
        var unreadSummaryCallCount = 0
            private set
        val addedMembers = mutableListOf<String>()
        val removedMembers = mutableListOf<String>()
        val joinedChannelIds = mutableListOf<String>()
        val seqReadRequests = mutableListOf<String>()
        val readAllRequests = mutableListOf<String>()
        val createThreadBodies = mutableListOf<String>()
        val followThreadBodies = mutableListOf<String>()
        val claimedTaskIds = mutableListOf<String>()
        val updatedTaskStatusBodies = mutableListOf<String>()
        val channelMessagesBeforeSeqCalls = mutableListOf<Long?>()
        var inboxReadAllCallCount = 0
            private set

        override suspend fun settingsAccount(): Response<ResponseBody> =
            response(settingsAccountJson)

        override suspend fun updateSettingsAccount(body: RequestBody): Response<ResponseBody> =
            response(settingsAccountJson)

        override suspend fun settingsSessions(): Response<ResponseBody> =
            response(settingsSessionsJson)

        override suspend fun revokeSettingsSession(sessionId: String): Response<ResponseBody> =
            response(revokeSessionJson)

        override suspend fun settingsMachines(serverId: String): Response<ResponseBody> =
            response("""{"machines":[]}""")

        override suspend fun settingsMachineWorkspaces(
            serverId: String,
            machineId: String
        ): Response<ResponseBody> = response("""{"workspaces":[]}""")

        override suspend fun settingsAgents(): Response<ResponseBody> =
            response(settingsAgentsJson)

        override suspend fun channels(archived: String): Response<ResponseBody> =
            response(channelsJson)

        override suspend fun createChannel(body: RequestBody): Response<ResponseBody> =
            response(createChannelJson)

        override suspend fun joinChannel(channelId: String): Response<ResponseBody> {
            joinedChannelIds += channelId
            return response(joinChannelJson)
        }

        override suspend fun serverMembers(serverId: String): Response<ResponseBody> =
            response(serverMembersJson)

        override suspend fun updateSettingsServerProfile(
            serverId: String,
            body: RequestBody
        ): Response<ResponseBody> =
            response("""{"id":"$serverId","name":"Slock Android"}""")

        override suspend fun settingsServerUsage(serverId: String): Response<ResponseBody> =
            response("""{"usage":{}}""")

        override suspend fun settingsBillingSubscription(): Response<ResponseBody> =
            response("""{"subscription":{}}""")

        override suspend fun createSettingsServerInvite(
            serverId: String,
            body: RequestBody
        ): Response<ResponseBody> =
            response("""{"invite":{}}""")

        override suspend fun settingsServerOnboardingSettings(
            serverId: String
        ): Response<ResponseBody> =
            response("""{"settings":{}}""")

        override suspend fun updateSettingsServerOnboardingSettings(
            serverId: String,
            body: RequestBody
        ): Response<ResponseBody> =
            response("""{"settings":{}}""")

        override suspend fun deleteSettingsServer(serverId: String): Response<ResponseBody> =
            response("""{"ok":true}""")

        override suspend fun memberProfile(memberId: String): Response<ResponseBody> =
            response(memberProfileJson)

        override suspend fun agentDirectMessages(agentId: String): Response<ResponseBody> =
            response("""{"directMessages":[]}""")

        override suspend fun reminders(ownerAgentId: String, status: String): Response<ResponseBody> =
            response("""{"reminders":[]}""")

        override suspend fun trajectoryLogs(agentId: String): Response<ResponseBody> =
            response("""{"trajectoryLogs":[]}""")

        override suspend fun channelMembers(channelId: String): Response<ResponseBody> =
            response(channelMembersJson)

        override suspend fun addChannelMember(channelId: String, body: RequestBody): Response<ResponseBody> {
            addedMembers += "$channelId:${body.bodyAsString()}"
            return response(addChannelMemberJson)
        }

        override suspend fun removeChannelMember(
            channelId: String,
            kind: String,
            memberId: String
        ): Response<ResponseBody> {
            removedMembers += "$channelId/$kind/$memberId"
            return response("""{"ok":true,"removed":{"id":"$memberId","kind":"$kind"}}""")
        }

        override suspend fun directMessages(): Response<ResponseBody> =
            directMessagesFailure?.let { throw it } ?:
            response(directMessagesJson)

        override suspend fun channelUnreadCounts(): Response<ResponseBody> =
            response(unreadCountsJson)

        override suspend fun markChannelReadToSeq(channelId: String, body: RequestBody): Response<ResponseBody> {
            seqReadRequests += "$channelId:${body.bodyAsString().substringAfter(":").substringBefore("}")}"
            return response(readReceiptJson)
        }

        override suspend fun markChannelReadAll(channelId: String): Response<ResponseBody> {
            readAllRequests += channelId
            return response(readReceiptJson)
        }

        override suspend fun markChannelUnread(channelId: String): Response<ResponseBody> =
            response(readReceiptJson)

        override suspend fun inbox(filter: String, limit: Int, offset: Int): Response<ResponseBody> =
            response(inboxJson)

        override suspend fun markInboxReadAll(): Response<ResponseBody> =
            response(readReceiptJson).also { inboxReadAllCallCount += 1 }

        override suspend fun serverTasks(): Response<ResponseBody> =
            response(serverTasksJson)

        override suspend fun claimTask(taskId: String): Response<ResponseBody> {
            claimedTaskIds += taskId
            return response(claimTaskJson)
        }

        override suspend fun updateTaskStatus(
            taskId: String,
            body: RequestBody
        ): Response<ResponseBody> {
            updatedTaskStatusBodies += "$taskId:${body.bodyAsString()}"
            return response(updateTaskStatusJson)
        }

        override suspend fun saved(limit: Int, offset: Int): Response<ResponseBody> =
            response(savedJson)

        override suspend fun searchMessages(query: String, limit: Int, offset: Int): Response<ResponseBody> =
            response(searchJson)

        override suspend fun channelMessages(
            channelId: String,
            limit: Int,
            beforeSeq: Long?
        ): Response<ResponseBody> {
            channelMessagesBeforeSeqCalls += beforeSeq
            return response(channelMessagesJson)
        }

        override suspend fun messageContext(messageId: String): Response<ResponseBody> =
            response(messageContextJson)

        override suspend fun messageSync(
            sinceSeq: Long,
            limit: Int,
            channelId: String?
        ): Response<ResponseBody> = response(messageSyncJson)

        override suspend fun sendMessage(body: RequestBody): Response<ResponseBody> =
            response(sendMessageJson)

        override suspend fun uploadAttachments(
            channelId: RequestBody,
            files: List<okhttp3.MultipartBody.Part>
        ): Response<ResponseBody> =
            response("""{"attachments":[{"id":"attachment-1","filename":"sample.png","mimeType":"image/png","sizeLabel":"12 KB"}]}""")

        override suspend fun attachmentUrl(attachmentId: String): Response<ResponseBody> =
            response(attachmentUrlJson)

        override suspend fun channelThreads(channelId: String, limit: Int): Response<ResponseBody> =
            response(channelThreadsJson)

        override suspend fun createOrOpenThread(channelId: String, body: RequestBody): Response<ResponseBody> {
            createThreadBodies += body.bodyAsString()
            return response(createThreadJson)
        }

        override suspend fun followedThreads(limit: Int): Response<ResponseBody> =
            response(followedThreadsJson)

        override suspend fun followThread(body: RequestBody): Response<ResponseBody> {
            followThreadBodies += body.bodyAsString()
            return response(threadActionJson)
        }

        override suspend fun unfollowThread(body: RequestBody): Response<ResponseBody> =
            response(threadActionJson)

        override suspend fun markThreadDone(body: RequestBody): Response<ResponseBody> =
            response(threadActionJson)

        override suspend fun markThreadUndone(body: RequestBody): Response<ResponseBody> =
            response(threadActionJson)

        override suspend fun serverUnreadSummary(): Response<ResponseBody> =
            response(unreadSummaryJson).also { unreadSummaryCallCount += 1 }

        private fun response(json: String): Response<ResponseBody> =
            if (responseCode in 200..299) {
                Response.success(json.jsonBody())
            } else {
                Response.error(responseCode, """{"error":"failed"}""".jsonBody())
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
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
