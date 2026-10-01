package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationRepositoryDataSourceTest {
    @Test
    fun conversationDetailLoadsCanonicalChannelIdentityDirectly() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET channels/channel-1" to
                            """{"id":"channel-1","name":"canonical","type":"channel","description":"standalone"}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val detail =
            assertIs<DataResult.Content<ConversationSummary>>(
                repository.getConversationDetail(ChannelId("channel-1"), DataRequestPriority.Visible)
            ).data

        assertEquals(ChannelId("channel-1"), detail.channelId)
        assertEquals("canonical", detail.name)
        assertEquals("standalone", detail.subtitle)
        assertEquals(
            listOf(RecordedConversationRequest(DataRequestPriority.Visible, "GET", "channels/channel-1")),
            network.requests,
        )
    }

    @Test
    fun canonicalMessagesCompleteWithoutAwaitingUnavailableSummaryFallback() = runBlocking {
        val releaseSummary = CompletableDeferred<Unit>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("parent-1", "channel-1")}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to """{"threads":[]}""",
                    ),
                requestRelease = mapOf("GET channels/channel-1/threads" to releaseSummary),
            )
        val repository = RealConversationRepository(sampleStore(), network)

        // The auxiliary summary endpoint is gated and never released. A default canonical
        // load must terminate purely on the messages fetch and never await it; the pre-fix
        // behavior awaits channels/:id/threads and blocks here until the timeout fires.
        // Termination is scheduler-independent — no isCompleted snapshot, no yield count,
        // no Unconfined: the fix simply never constructs the summary deferred.
        val result =
            withTimeout(5_000) {
                repository.getThread(
                    threadId = ThreadId("channel-1"),
                    includeFollowedSummaries = false,
                )
            }

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        releaseSummary.complete(Unit) // no-op cleanup on the fix path; nothing awaits it
        assertEquals(listOf("parent-1"), snapshot.messages.map { it.id.value })
        assertFalse(
            network.requests.any { it.path == "channels/channel-1/threads" },
            "default canonical load must defer targeted summaries to secondary enrichment",
        )
    }

    @Test
    fun routeScopedServerOverridesMutableSessionSelectionForThreadReads() = runBlocking {
        val authStore = routeScopedAuthStore()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"historyLimited":false,"messages":[${messageJson("message-1", "channel-1")}] }""",
                    ),
            )
        val repository = routeScopedRepository(authStore, network)

        assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("channel-1")))

        assertTrue(network.serverOverrides.isNotEmpty())
        assertTrue(network.serverOverrides.all { it == "server-b" })
    }

    @Test
    fun routeScopedServerPinsInitialSummariesAndReplyCreateOpenToOneServer() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"historyLimited":false,"messages":[${messageJson("parent-1", "channel-1")}] }""",
                        "GET channels/channel-1/threads" to "{}",
                        "GET channels/threads/followed" to
                            """{"threads":[],"totalCount":0,"hasMore":false,"nextOffset":null}""",
                        "POST channels/channel-1/threads" to
                            """{"threadChannelId":"thread-b","replyCount":1,"ok":true}""",
                        "GET messages/channel/thread-b" to
                            """{"historyLimited":false,"messages":[${messageJson("reply-1", "thread-b")}] }""",
                    ),
            )
        val repository = routeScopedRepository(routeScopedAuthStore(), network)

        assertIs<DataResult.Content<ThreadSnapshot>>(
            repository.getThread(
                threadId = ThreadId("channel-1"),
                includeFollowedSummaries = true,
                limit = 30,
            ),
        )
        assertIs<DataResult.Content<ThreadSnapshot>>(
            repository.getReplyThread(
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("parent-1"),
                limit = 30,
            ),
        )

        assertTrue(network.serverOverrides.size >= 5)
        assertTrue(network.serverOverrides.all { it == "server-b" })
        assertTrue(network.requests.any { it.method == "POST" && it.path == "channels/channel-1/threads" })
        assertTrue(network.requests.any { it.path == "channels/threads/followed" })
        assertTrue(network.requests.any { it.path == "channels/channel-1/threads" && it.method == "GET" })
    }

    @Test
    fun routeScopedServerPinsParentContextAndDetailToRouteServer() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/message-1" to
                            """{"channelId":"channel-1","targetMessageId":"message-1","messages":[${messageJson("message-1", "channel-1")}] }""",
                        "GET channels/channel-1" to
                            """{"id":"channel-1","name":"general","type":"channel"}""",
                    ),
            )
        val repository = routeScopedRepository(routeScopedAuthStore(), network)

        assertIs<DataResult.Content<ThreadSnapshot>>(
            repository.getMessageContext(MessageId("message-1"), ChannelId("channel-1")),
        )

        assertEquals(
            listOf("messages/context/message-1", "channels/channel-1"),
            network.requests.map { it.path },
        )
        assertEquals(listOf<String?>("server-b", "server-b"), network.serverOverrides)
    }

    @Test
    fun routeScopedServerPinsRealtimeGapSyncToRouteServer() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/sync" to
                            """[${messageJson("message-2", "channel-1", seq = 2)}]""",
                    ),
            )
        val repository = routeScopedRepository(routeScopedAuthStore(), network)

        assertIs<DataResult.Content<ThreadSnapshot>>(
            repository.syncNewerMessages(ThreadId("channel-1"), sinceSeq = 1, limit = 30),
        )

        assertEquals(listOf<String?>("server-b"), network.serverOverrides)
        assertEquals("messages/sync", network.requests.single().path)
    }

    @Test
    fun messageContextHydratesDeletedAgentDmDetailByChannelId() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/message-1" to
                            """{"channelId":"dm-deleted-agent","targetMessageId":"message-1","messages":[${messageJson("message-1", "dm-deleted-agent")}]}""",
                        "GET channels/dm-deleted-agent" to
                            """
                            {
                              "id":"dm-deleted-agent",
                              "name":"removed-search-peer",
                              "type":"dm",
                              "joined":true,
                              "peerType":"agent",
                              "peerId":"agent-deleted",
                              "peerName":"removed-search-peer",
                              "peerDisplayName":"Removed Search Peer",
                              "peerDescription":"Historical agent conversation",
                              "peerAvatarUrl":"pixel:removed-agent"
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getMessageContext(MessageId("message-1"))

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        val conversation = snapshot.conversation
        assertEquals(ConversationKind.DirectMessage, conversation.kind)
        assertEquals("dm-deleted-agent", conversation.channelId?.value)
        assertEquals("Removed Search Peer", conversation.name)
        assertEquals("agent", conversation.directMessagePeerType)
        assertEquals("agent-deleted", conversation.presenceMemberId?.value)
        assertEquals("pixel:removed-agent", conversation.directMessagePeerAvatarWireValue)
        assertEquals(conversation, snapshot.canonicalConversationDetail)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/context/message-1"),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/dm-deleted-agent")
            ),
            network.requests
        )
    }

    @Test
    fun initialDirectMessageHydratesCanonicalNameAndAvatarWithoutHomeStore() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/dm-1" to
                            """{"messages":[${messageJson("message-1", "dm-1")}]}""",
                        "GET channels/dm-1" to
                            """
                            {
                              "id":"dm-1",
                              "name":"dm-1",
                              "type":"dm",
                              "peerType":"user",
                              "peerId":"peer-1",
                              "peerName":"cindy",
                              "peerDisplayName":"Cindy",
                              "peerAvatarUrl":"pixel:cindy"
                            }
                            """.trimIndent(),
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val snapshot =
            assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("dm-1"))).data

        assertEquals(ConversationKind.DirectMessage, snapshot.conversation.kind)
        assertEquals("Cindy", snapshot.conversation.name)
        assertEquals("pixel:cindy", snapshot.conversation.directMessagePeerAvatarWireValue)
        assertEquals(snapshot.conversation, snapshot.canonicalConversationDetail)
        assertEquals(
            setOf("messages/channel/dm-1", "channels/dm-1"),
            network.requests.map { it.path }.toSet(),
        )
    }

    @Test
    fun emptyDirectMessageStillReturnsCanonicalHeaderContent() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/dm-empty" to """{"messages":[]}""",
                        "GET channels/dm-empty" to
                            """
                            {
                              "id":"dm-empty",
                              "name":"dm-empty",
                              "type":"dm",
                              "peerType":"agent",
                              "peerId":"agent-1",
                              "peerName":"helper",
                              "peerDisplayName":"Helper",
                              "peerAvatarUrl":"https://example.test/helper.png"
                            }
                            """.trimIndent(),
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val snapshot =
            assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("dm-empty"))).data

        assertTrue(snapshot.messages.isEmpty())
        assertEquals("Helper", snapshot.conversation.name)
        assertEquals("https://example.test/helper.png", snapshot.conversation.directMessagePeerAvatarWireValue)
        assertEquals(snapshot.conversation, snapshot.canonicalConversationDetail)
    }

    @Test
    fun replyThreadRetainsCanonicalParentDirectMessageDetail() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "POST channels/dm-parent/threads" to
                            """{"threadChannelId":"thread-1","replyCount":1,"ok":true}""",
                        "GET messages/channel/thread-1" to
                            """{"messages":[${messageJson("reply-1", "thread-1")}]}""",
                        "GET channels/dm-parent" to
                            """
                            {
                              "id":"dm-parent",
                              "name":"dm-parent",
                              "type":"dm",
                              "peerType":"user",
                              "peerId":"peer-1",
                              "peerName":"cindy",
                              "peerDisplayName":"Cindy",
                              "peerAvatarUrl":"pixel:cindy"
                            }
                            """.trimIndent(),
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val snapshot =
            assertIs<DataResult.Content<ThreadSnapshot>>(
                repository.getReplyThread(
                    parentChannelId = ChannelId("dm-parent"),
                    parentMessageId = MessageId("parent-1"),
                )
            ).data

        assertEquals(ConversationKind.Thread, snapshot.conversation.kind)
        assertEquals("dm-parent", snapshot.conversation.thread?.parentChannelId?.value)
        assertEquals(ConversationKind.DirectMessage, snapshot.canonicalConversationDetail?.kind)
        assertEquals("Cindy", snapshot.canonicalConversationDetail?.name)
        assertEquals("pixel:cindy", snapshot.canonicalConversationDetail?.directMessagePeerAvatarWireValue)
    }

    @Test
    fun messageContextStillOpensWhenChannelDetailCannotBeHydrated() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/message-1" to
                            """{"channelId":"channel-1","targetMessageId":"message-1","messages":[${messageJson("message-1", "channel-1")}]}""",
                        "GET channels/channel-1" to """{"threads":[]}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network, warningLogger = logs::add)

        val result = repository.getMessageContext(MessageId("message-1"))

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        assertEquals("message-1", snapshot.highlightedMessageId?.value)
        assertEquals(ConversationKind.Thread, snapshot.conversation.kind)
        assertEquals("channel-1", snapshot.conversation.name)
        assertTrue(logs.any { it.contains("conversation_detail_parse_failed channelId=channel-1") })
    }

    @Test
    fun messageContextKeepsCanonicalReplyThreadWhenParentDetailIsDm() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/context/reply-1" to
                            """
                            {
                              "channelId":"thread-1",
                              "targetMessageId":"reply-1",
                              "canonicalTarget":{
                                "kind":"thread",
                                "channelId":"dm-parent",
                                "messageId":"reply-1",
                                "threadParentMessageId":"parent-1",
                                "threadChannelId":"thread-1"
                              },
                              "messages":[${messageJson("reply-1", "thread-1")}]
                            }
                            """.trimIndent(),
                        "GET channels/dm-parent" to
                            """
                            {
                              "id":"dm-parent",
                              "name":"removed-search-peer",
                              "type":"dm",
                              "peerType":"agent",
                              "peerId":"agent-deleted",
                              "peerName":"removed-search-peer"
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getMessageContext(MessageId("reply-1"))

        val conversation = assertIs<DataResult.Content<ThreadSnapshot>>(result).data.conversation
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("dm-parent", conversation.thread?.parentChannelId?.value)
        assertEquals("parent-1", conversation.thread?.parentMessageId?.value)
        assertEquals("thread-1", conversation.thread?.streamChannelId?.value)
    }

    @Test
    fun getThreadInitialLoadAdmitsEmbeddedThreadSummaryWithoutSecondSummaryRequest() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """
                            {
                              "messages":[${messageJson("parent-1", "channel-1")}],
                              "threadSummariesByParentMessageId": {
                                "parent-1": {
                                  "threadChannelId":"thread-1",
                                  "replyCount":4,
                                  "unreadCount":1,
                                  "firstUnreadMessageId":"reply-4",
                                  "latestReplies":[
                                    {
                                      "messageId":"reply-4",
                                      "seq":4,
                                      "preview":"first-frame preview",
                                      "senderId":"agent-1",
                                      "senderType":"agent",
                                      "senderName":"MingQi",
                                      "senderDisplayName":"明启",
                                      "senderAvatarUrl":null,
                                      "createdAt":"2026-07-25T14:01:00.000Z"
                                    }
                                  ]
                                }
                              },
                              "historyLimited":false
                            }
                            """.trimIndent(),
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getThread(ThreadId("channel-1"))

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        val message = snapshot.messages.single()
        assertEquals("parent-1", message.id.value)
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(4, message.replyCount)
        assertEquals(1, message.unreadReplyCount)
        assertEquals("reply-4", message.firstUnreadReplyMessageId?.value)
        assertFalse(message.isThreadFollowed)
        val summary = snapshot.replySummaries.threads.single()
        assertEquals("parent-1", summary.parentMessageId?.value)
        assertEquals(listOf("reply-4"), summary.latestReplies.map { it.messageId })
        assertEquals("明启", summary.latestReplies.single().senderDisplayName)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/channel-1", listOf("limit" to "30")),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/channel-1")
            ),
            network.requests
        )
    }

    @Test
    fun malformedEmbeddedSummaryRemainsUnavailableUntilSecondaryEnrichment() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{
                              "messages":[${messageJson("parent-1", "channel-1")}],
                              "threadSummariesByParentMessageId":{"parent-1":{"threadChannelId":"","replyCount":4}}
                            }""",
                        "GET channels/channel-1/threads" to
                            """{"parent-1":{"threadChannelId":"thread-1","replyCount":4,"latestReplies":[]}}""",
                    ),
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("channel-1"))).data

        assertEquals(ThreadReplySummariesAuthority.Unavailable, snapshot.replySummariesAuthority)
        assertEquals(null, snapshot.messages.single().threadChannelId)
        assertFalse(network.requests.any { it.path == "channels/channel-1/threads" })
    }

    @Test
    fun malformedEmbeddedAndFailedSummaryFallbacksRemainUnavailable() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{
                              "messages":[${messageJson("parent-1", "channel-1")}],
                              "threadSummariesByParentMessageId":[]
                            }""",
                    ),
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val snapshot =
            assertIs<DataResult.Content<ThreadSnapshot>>(
                repository.getThread(
                    ThreadId("channel-1"),
                    includeFollowedSummaries = true,
                )
            ).data

        assertEquals(ThreadReplySummariesAuthority.Unavailable, snapshot.replySummariesAuthority)
        assertTrue(snapshot.replySummaries.threads.isEmpty())
        assertTrue(network.requests.any { it.path == "channels/channel-1/threads" })
        assertTrue(network.requests.any { it.path == "channels/threads/followed" })
    }

    @Test
    fun getThreadCanOptIntoFollowedSummariesForBackgroundCompletion() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("parent-1", "channel-1")}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to """{}""",
                        "GET channels/threads/followed" to
                            """
                            {
                              "threads": [
                                {
                                  "threadChannelId": "thread-1",
                                  "parentChannelId": "channel-1",
                                  "parentMessageId": "parent-1",
                                  "replyCount": 4,
                                  "unreadCount": 1,
                                  "firstUnreadMessageId": "reply-4",
                                  "lastActivityAt": "2026-05-22T10:05:00.000Z"
                                }
                              ]
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = true)

        val message = assertIs<DataResult.Content<ThreadSnapshot>>(result).data.messages.single()
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(4, message.replyCount)
        assertEquals(1, message.unreadReplyCount)
        assertEquals("reply-4", message.firstUnreadReplyMessageId?.value)
        assertTrue(message.isThreadFollowed)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/channel-1", listOf("limit" to "30")),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/channel-1"),
                RecordedConversationRequest(
                    DataRequestPriority.Visible,
                    "GET",
                    "channels/channel-1/threads",
                    listOf("parentMessageIds" to "parent-1")
                ),
                RecordedConversationRequest(DataRequestPriority.Visible, "GET", "channels/threads/followed", listOf("limit" to "50"))
            ),
            network.requests
        )
    }

    @Test
    fun getThreadReplySummariesReturnsChannelSummariesWhenFollowedListIsSlow() = runBlocking {
        // Task #1142: the account-wide followed list (multi-MB on busy accounts) must never gate
        // the channel summaries that draw reply rows. Hold that request forever and expect the
        // channel summaries back within the join budget, with no followed decoration.
        val holdFollowed = CompletableDeferred<Unit>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        // The endpoint is a record keyed by parent message id (see
                        // parseChannelThreadSummaryRecordElement); a {"threads":[...]} list is rejected.
                        "GET channels/channel-1/threads" to
                            """{"parent-1":{"threadChannelId":"thread-1","replyCount":4,"latestReplies":[]}}""",
                    ),
                requestRelease = mapOf("GET channels/threads/followed" to holdFollowed),
            )
        val repository = RealConversationRepository(sampleStore(), network)
        repository.followedSummariesJoinBudgetMillis = 50L

        val result =
            withTimeout(2_000L) {
                repository.getThreadReplySummaries(
                    threadId = ThreadId("channel-1"),
                    parentMessageIds = listOf(MessageId("parent-1"))
                )
            }

        // Release the held request only after the result is in hand; the block must end in
        // an assertion so the JUnit4 method stays void (runBlocking returns its last value).
        holdFollowed.complete(Unit)
        val summaries =
            assertIs<DataResult.Content<ThreadReplySummariesSnapshot>>(result, "unexpected result: $result").data
        assertEquals(1, summaries.threads.size)
        assertEquals("parent-1", summaries.threads.single().parentMessageId?.value)
        assertEquals(4, summaries.threads.single().replyCount)
        assertTrue(summaries.followedThreadKeys.isEmpty())
    }

    @Test
    fun getThreadReplySummariesDoesNotRefetchMessages() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET channels/channel-1/threads" to
                            """
                            {
                              "threads": [
                                {
                                  "threadChannelId": "thread-1",
                                  "parentChannelId": "channel-1",
                                  "parentMessageId": "parent-1",
                                  "replyCount": 4,
                                  "unreadCount": 1,
                                  "firstUnreadMessageId": "reply-4"
                                }
                              ]
                            }
                            """.trimIndent(),
                        "GET channels/threads/followed" to
                            """
                            {
                              "threads": [
                                {
                                  "threadChannelId": "thread-1",
                                  "parentChannelId": "channel-1",
                                  "parentMessageId": "parent-1",
                                  "replyCount": 4
                                }
                              ]
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result =
            repository.getThreadReplySummaries(
                threadId = ThreadId("channel-1"),
                parentMessageIds = listOf(MessageId("parent-1"))
            )

        val summaries = assertIs<DataResult.Content<ThreadReplySummariesSnapshot>>(result).data
        assertEquals(1, summaries.threads.size)
        assertEquals("parent-1", summaries.threads.single().parentMessageId?.value)
        assertTrue(summaries.followedThreadKeys.isNotEmpty())
        assertEquals(
            listOf(
                RecordedConversationRequest(
                    DataRequestPriority.Visible,
                    "GET",
                    "channels/channel-1/threads",
                    listOf("parentMessageIds" to "parent-1")
                ),
                RecordedConversationRequest(
                    DataRequestPriority.Visible,
                    "GET",
                    "channels/threads/followed",
                    listOf("limit" to "50")
                )
            ),
            network.requests
        )
        assertFalse(network.requests.any { it.path.startsWith("messages/") })
    }

    @Test
    fun getReplyThreadLoadsMessagesFromResolvedThreadChannel() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "POST channels/channel-1/threads" to """{"threadChannelId":"thread-1","replyCount":2,"ok":true}""",
                        "GET messages/channel/thread-1" to
                            """{"messages":[${messageJson("reply-1", "thread-1", seq = 2, content = "reply body")}],"historyLimited":false}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result =
            repository.getReplyThread(
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("parent-1"),
                limit = 25
            )

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        assertEquals("thread-1", snapshot.conversation.thread?.streamChannelId?.value)
        assertEquals("channel-1", snapshot.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-1", snapshot.conversation.thread?.parentMessageId?.value)
        assertEquals(listOf("reply body"), snapshot.messages.map { it.body })
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "POST", "channels/channel-1/threads"),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/thread-1", listOf("limit" to "25")),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/channel-1")
            ),
            network.requests
        )
        assertEquals(listOf("""{"parentMessageId":"parent-1"}"""), network.bodies)
    }

    @Test
    fun getChannelThreadsParsesParentMessageKeyedRecordResponse() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET channels/channel-1/threads" to
                            """
                            {
                              "parent-1": {
                                "threadChannelId": "thread-1",
                                "replyCount": 7,
                                "lastReplyAt": "2026-05-22T10:05:00.000Z",
                                "participantIds": ["user-1", "agent-1"],
                                "unreadCount": 2,
                                "firstUnreadMessageId": "reply-2"
                              }
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getChannelThreads(ChannelId("channel-1"), limit = 25)

        val snapshot = assertIs<DataResult.Content<ThreadListSnapshot>>(result).data
        val thread = snapshot.threads.single()
        assertEquals("thread:thread-1", thread.key)
        assertEquals("thread-1", thread.threadChannelId?.value)
        assertEquals("parent-1", thread.parentMessageId?.value)
        assertEquals(7, thread.replyCount)
        assertEquals(2, thread.unreadCount)
        assertEquals("reply-2", thread.firstUnreadMessageId?.value)
        assertEquals("2026-05-22T10:05:00.000Z", thread.latestActivityAt)
        assertEquals(listOf("user-1", "agent-1"), thread.participantIds.map { it.value })
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Visible, "GET", "channels/channel-1/threads", listOf("limit" to "25"))
            ),
            network.requests
        )
    }

    @Test
    fun getChannelThreadsRejectsFollowedThreadWrapperResponse() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET channels/channel-1/threads" to """{"threads":[]}""")
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getChannelThreads(ChannelId("channel-1"), limit = 25)

        assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", result.error.code)
    }

    @Test
    fun getChannelThreadsRejectsNonRecordWrapperResponse() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET channels/channel-1/threads" to """{"items":[]}""")
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getChannelThreads(ChannelId("channel-1"), limit = 25)

        assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", result.error.code)
    }

    @Test
    fun getThreadEmitsOffUiParseDiagnostics() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("parent-1", "channel-1")}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to """{}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network, warningLogger = logs::add)

        val result = repository.getThread(ThreadId("channel-1"))

        assertEquals("parent-1", assertIs<DataResult.Content<ThreadSnapshot>>(result).data.messages.single().id.value)
        assertTrue(
            logs.any {
                it.contains("conversation_parse_start source=messages_channel") &&
                    it.contains("dispatcher=JsonParse")
            }
        )
        assertTrue(
            logs.any {
                it.contains("conversation_parse_result source=messages_channel result=content") &&
                    it.contains("dispatcher=JsonParse")
            }
        )
    }

    @Test
    fun getThreadMapsDnsTransportFailureToReadableError() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                failures =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """Unable to resolve host "api.raft.build": No address associated with hostname"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getThread(ThreadId("channel-1"))

        val error = assertIs<DataResult.Error>(result)
        assertEquals("network_dns_failed", error.error.code)
    }

    @Test
    fun getFollowedThreadsParsesWrappedResponse() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET channels/threads/followed" to
                            """
                            {
                              "threads": [
                                {
                                  "threadChannelId": "thread-1",
                                  "parentMessageId": "parent-1",
                                  "replyCount": 3,
                                  "lastActivityAt": "2026-05-22T10:05:00.000Z",
                                  "unreadCount": 1
                                }
                              ],
                              "totalCount": 1,
                              "hasMore": false,
                              "nextOffset": null
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getFollowedThreads(ServerId("server-1"), limit = 25)

        val snapshot = assertIs<DataResult.Content<ThreadListSnapshot>>(result).data
        val thread = snapshot.threads.single()
        assertEquals("thread:thread-1", thread.key)
        assertEquals("parent-1", thread.parentMessageId?.value)
        assertEquals(3, thread.replyCount)
        assertEquals(1, snapshot.totalCount)
    }

    @Test
    fun getFollowedThreadsRejectsMissingThreadsWrapper() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET channels/threads/followed" to """{}""")
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getFollowedThreads(ServerId("server-1"), limit = 25)

        assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", result.error.code)
    }

    @Test
    fun sendMessageAndThreadActionsUseSharedDtoBodies() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "POST messages" to messageJson("sent-1", "channel-1", content = "ship it"),
                        "POST channels/channel-1/threads" to """{"threadChannelId":"thread-1","replyCount":0,"ok":true}""",
                        "POST channels/threads/follow" to """{"threadChannelId":"thread-1","replyCount":4,"ok":true}""",
                        "POST channels/threads/unfollow" to """{"threadChannelId":"thread-1","replyCount":4,"ok":true}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val sent =
            repository.sendMessage(
                channelId = ChannelId("channel-1"),
                body = "ship it",
                attachmentIds = listOf(AttachmentId("attachment-1")),
                asTask = true,
                randomId = "rid-1"
            )
        val created = repository.createOrOpenThread(ChannelId("channel-1"), MessageId("parent-1"))
        val followed = repository.followThread(MessageId("parent-1"))
        val unfollowed = repository.unfollowThread(ChannelId("thread-1"))

        assertEquals("sent-1", assertIs<DataResult.Content<SendMessageResult>>(sent).data.message.id.value)
        assertEquals("thread-1", assertIs<DataResult.Content<ThreadActionResult>>(created).data.threadChannelId.value)
        assertEquals(4, assertIs<DataResult.Content<ThreadActionResult>>(followed).data.replyCount)
        assertEquals("thread-1", assertIs<DataResult.Content<ThreadActionResult>>(unfollowed).data.threadChannelId.value)
        assertEquals(
            listOf(
                """{"channelId":"channel-1","content":"ship it","attachmentIds":["attachment-1"],"asTask":true,"randomId":"rid-1"}""",
                """{"parentMessageId":"parent-1"}""",
                """{"parentMessageId":"parent-1"}""",
                """{"threadChannelId":"thread-1"}"""
            ),
            network.bodies
        )
    }

    @Test
    fun sendMessageRejectsUnknownProjectedSenderInsteadOfInventingCurrentUserMessage() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "POST messages" to
                            """
                            {
                              "id": "secret-message-id",
                              "channelId": "channel-1",
                              "senderType": "future-member",
                              "senderId": "secret-sender-id",
                              "senderName": "Secret Sender",
                              "messageType": "chat",
                              "content": "private body"
                            }
                            """.trimIndent(),
                    ),
            )
        val repository = RealConversationRepository(sampleStore(), network, warningLogger = logs::add)

        val result =
            repository.sendMessage(
                channelId = ChannelId("channel-1"),
                body = "local draft",
                attachmentIds = emptyList(),
                asTask = false,
                randomId = "random-1",
            )

        val error = assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", error.error.code)
        assertTrue(logs.any { it == "projection_value_rejected source=rest_message_sender field=member_kind value=future-member" })
        assertTrue(logs.none { "secret-message-id" in it || "secret-sender-id" in it || "private body" in it })
    }

    @Test
    fun attachmentUploadCapabilitiesUseServerMaxBytesWithoutClientFallback() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET attachments/upload-capabilities" to
                            """{"directUploadEnabled":false,"directUploadThresholdBytes":null,"maxBytes":143654912,"sessionExpiresInSeconds":null}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val capability = assertIs<DataResult.Content<AttachmentUploadCapabilities>>(
            repository.getAttachmentUploadCapabilities()
        ).data

        assertEquals(143654912L, capability.maxBytes)
        assertEquals("attachments/upload-capabilities", network.requests.single().path)
    }

    @Test
    fun attachmentUploadCapabilitiesAdmitFreePlanDirectThresholdAboveMaxBytes() = runBlocking {
        val body =
            """{"directUploadEnabled":true,"directUploadThresholdBytes":94371840,"maxBytes":52428800,"sessionExpiresInSeconds":900}"""
        assertEquals(116, body.encodeToByteArray().size)
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET attachments/upload-capabilities" to body)
            )

        val capability = assertIs<DataResult.Content<AttachmentUploadCapabilities>>(
            RealConversationRepository(sampleStore(), network).getAttachmentUploadCapabilities()
        ).data

        assertEquals(52_428_800L, capability.maxBytes)
        assertEquals(true, capability.directUploadEnabled)
        assertEquals(94_371_840L, capability.directUploadThresholdBytes)
        assertEquals(900, capability.sessionExpiresInSeconds)
    }

    @Test
    fun attachmentUploadCapabilitiesBodyEventIncludesRoutingFields() = runBlocking {
        val body =
            """{"directUploadEnabled":true,"directUploadThresholdBytes":94371840,"maxBytes":52428800,"sessionExpiresInSeconds":900}"""
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET attachments/upload-capabilities" to body)
            )

        RealConversationRepository(sampleStore(), network, warningLogger = logs::add)
            .getAttachmentUploadCapabilities()

        assertTrue(
            logs.any { line ->
                line.contains("conversation_network_body_received source=attachment_upload_capabilities") &&
                    line.contains("maxBytes=52428800") &&
                    line.contains("directUploadThresholdBytes=94371840") &&
                    line.contains("directUploadEnabled=true")
            }
        )
    }

    @Test
    fun attachmentUploadCapabilitiesBodyEventMarksParseFailure() = runBlocking {
        val body = """{"maxBytes":"""
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET attachments/upload-capabilities" to body)
            )

        val result =
            RealConversationRepository(sampleStore(), network, warningLogger = logs::add)
                .getAttachmentUploadCapabilities()

        assertIs<DataResult.Error>(result)
        assertTrue(
            logs.any { line ->
                line.contains("conversation_network_body_received source=attachment_upload_capabilities") &&
                    line.contains("attachmentUploadCapabilitiesParseError=true")
            }
        )
    }

    @Test
    fun attachmentUploadCapabilitiesRejectThresholdAboveContractLimit() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET attachments/upload-capabilities" to
                            """{"directUploadEnabled":true,"directUploadThresholdBytes":209715201,"maxBytes":52428800,"sessionExpiresInSeconds":900}"""
                    )
            )

        val result =
            RealConversationRepository(sampleStore(), network, warningLogger = logs::add)
                .getAttachmentUploadCapabilities()

        assertEquals("network_parse_failed", assertIs<DataResult.Error>(result).error.code)
        assertTrue(
            logs.any { line ->
                line.contains("conversation_network_body_received source=attachment_upload_capabilities") &&
                    line.contains("attachmentUploadCapabilitiesParseError=true")
            }
        )
    }

    @Test
    fun attachmentUploadCapabilitiesRejectNonPositiveServerValue() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses = mapOf("GET attachments/upload-capabilities" to """{"maxBytes":0}""")
            )

        val result = RealConversationRepository(sampleStore(), network).getAttachmentUploadCapabilities()

        assertEquals("network_parse_failed", assertIs<DataResult.Error>(result).error.code)
    }

    @Test
    fun legacyMultipartTooLargeUsesStructuredServerErrorCode() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                uploadResponse =
                    HomeNetworkResponse(
                        code = 413,
                        bodyText = null,
                        errorBodyText =
                            """{"error":"Max 90MB per file","errorCode":"ATTACHMENT_TOO_LARGE","maxBytes":94371840}"""
                    )
            )
        val result =
            RealConversationRepository(sampleStore(), network).uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(PlatformFile(id = "local-1", fileName = "large.bin", sizeBytes = 94371841L))
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("ATTACHMENT_TOO_LARGE", error.code)
        assertEquals("Max 90MB per file", error.serverMessage)
    }

    @Test
    fun uploadSessionTooLargeUsesStructuredServerCode() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                uploadResponse =
                    HomeNetworkResponse(
                        code = 413,
                        bodyText = null,
                        errorBodyText =
                            """{"error":"Upload exceeds the active limit","code":"UPLOAD_TOO_LARGE","maxBytes":94371840}"""
                    )
            )
        val result =
            RealConversationRepository(sampleStore(), network).uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(PlatformFile(id = "local-1", fileName = "large.bin", sizeBytes = 94371841L))
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("UPLOAD_TOO_LARGE", error.code)
        assertEquals(ErrorPresentationKey.AttachmentTooLarge, error.errorPresentation().key)
    }

    @Test
    fun pagingContextFilesAndUploadConsumeCanonicalDtosWithoutJoiningSummaries() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("older-1", "channel-1", seq = 7)}],"historyLimited":false}""",
                        "GET messages/context/message-1" to
                            """{"channelId":"channel-1","targetMessageId":"message-1","hasOlder":true,"hasNewer":false,"messages":[${messageJson("message-1", "channel-1")}]}""",
                        "GET channels/channel-1/files" to
                            """{"files":[{"id":"file-1","channelId":"channel-1","filename":"a.png","mimeType":"image/png","sizeBytes":128,"width":640,"height":480,"messageId":"message-1","uploader":{"type":"user","id":"user-1","name":"artin","displayName":"Artin"},"source":{"type":"channel","parentMessageId":"parent-1","parentMessageShortId":"p1"}}],"nextCursor":"cursor-2"}""",
                        "GET attachments/attachment-1/url" to """{"url":"https://cdn.slock.test/a.png","expiresAt":"2026-05-22T10:00:00.000Z"}"""
                    ),
                uploadBody =
                    """{"attachments":[{"id":"uploaded-1","filename":"upload.txt","mimeType":"text/plain","sizeBytes":5}]}"""
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val older = repository.loadOlderMessages(ThreadId("channel-1"), beforeSeq = 8)
        val context = repository.getMessageContext(MessageId("message-1"), ChannelId("channel-1"))
        val files = repository.getChannelFiles(ChannelId("channel-1"), limit = 25, cursor = "cursor-1")
        val target = repository.getAttachmentOpenTarget(AttachmentId("attachment-1"))
        val uploaded =
            repository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files =
                        listOf(
                            PlatformFile(
                                id = "local-upload-1",
                                fileName = "upload.txt",
                                mimeType = "text/plain",
                                bytes = "hello".encodeToByteArray()
                            )
                        )
                )
            )

        val olderMessage = assertIs<DataResult.Content<ThreadSnapshot>>(older).data.messages.single()
        assertEquals("older-1", olderMessage.id.value)
        assertEquals(null, olderMessage.threadChannelId)
        assertEquals(0, olderMessage.replyCount)
        assertEquals(0, olderMessage.unreadReplyCount)
        assertEquals(null, olderMessage.firstUnreadReplyMessageId)
        assertEquals("message-1", assertIs<DataResult.Content<ThreadSnapshot>>(context).data.highlightedMessageId?.value)
        val fileSnapshot = assertIs<DataResult.Content<ChannelFilesSnapshot>>(files).data
        assertEquals("cursor-2", fileSnapshot.nextCursor)
        val file = fileSnapshot.items.single()
        assertEquals("channel-1", file.channelId?.value)
        assertEquals(640, file.attachment.width)
        assertEquals(480, file.attachment.height)
        assertEquals("user", file.uploaderType)
        assertEquals("user-1", file.uploaderId)
        assertEquals("Artin", file.uploaderDisplayName)
        assertEquals("parent-1", file.sourceParentMessageId?.value)
        assertEquals("https://cdn.slock.test/a.png", assertIs<DataResult.Content<AttachmentOpenTarget>>(target).data.url)
        assertEquals("uploaded-1", assertIs<DataResult.Content<AttachmentUploadResult>>(uploaded).data.attachments.single().id.value)
        assertEquals(listOf("upload.txt"), network.uploadedFileNames)
        assertTrue(
            network.requests.any { request ->
                request.path == "messages/context/message-1" &&
                    request.query == listOf("channelId" to "channel-1")
            }
        )
        assertFalse(
            network.requests.any { request -> request.path == "channels/channel-1/threads" },
            "older canonical messages must not join auxiliary summaries",
        )
    }

    @Test
    fun channelLatestAndOlderPagesKeepMembershipSystemEventsInCanonicalOrder() = runBlocking {
        val latestOrdinary = messageJson("chat-30", "channel-1", seq = 30, content = "latest")
        val latestSystem = systemMessageJson("join-29", "channel-1", seq = 29, content = "@agent joined")
        val olderOrdinary = messageJson("chat-20", "channel-1", seq = 20, content = "older")
        val olderSystem = systemMessageJson("remove-19", "channel-1", seq = 19, content = "@agent removed")
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[$latestSystem,$latestOrdinary],"historyLimited":false}""",
                    ),
                responseForRequest = { request ->
                    if (
                        request.path == "messages/channel/channel-1" &&
                        request.query == listOf("limit" to "30", "before" to "29")
                    ) {
                        """{"messages":[$olderSystem,$olderOrdinary],"historyLimited":false}"""
                    } else {
                        null
                    }
                },
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val latest = assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("channel-1"))).data
        val older = assertIs<DataResult.Content<ThreadSnapshot>>(
            repository.loadOlderMessages(ThreadId("channel-1"), beforeSeq = 29),
        ).data

        assertEquals(listOf("join-29", "chat-30"), latest.messages.map { it.id.value })
        assertEquals(listOf(MessageType.System, MessageType.Chat), latest.messages.map { it.messageType })
        assertEquals(listOf("remove-19", "chat-20"), older.messages.map { it.id.value })
        assertEquals(listOf(MessageType.System, MessageType.Chat), older.messages.map { it.messageType })
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

    private fun routeScopedAuthStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-a",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-a", name = "Server A"),
                        StoredAuthServer(id = "server-b", name = "Server B"),
                    ),
            ),
        )

    private fun routeScopedRepository(
        authStore: InMemorySlockAuthSessionStore,
        network: FakeConversationNetworkClient,
    ): RealConversationRepository =
        RealConversationRepository(
            sessionStore = authStore,
            networkClient = network,
            routeServerIdProvider = { "server-b" },
        )


    @Test
    fun threadRequestTraceStaysOffWireQuery() = runBlocking {
        // task #58 observability contract without the trace on the wire:
        // repository-level requestTrace is process-local only; the query sent
        // to SlockNetworkClient must be identical with and without it.
        val responses =
            mapOf(
                "GET messages/channel/channel-1" to
                    """{"messages":[],"historyLimited":false}""",
                "GET messages/sync" to """{"messages":[]}""",
            )
        fun runWith(trace: String?): List<RecordedConversationRequest> {
            val network = FakeConversationNetworkClient(responses = responses)
            val repository =
                RealConversationRepository(
                    sampleStore(),
                    network,
                )
            runBlocking {
                repository.loadNewerMessages(ThreadId("channel-1"), 46L, 30, requestTrace = trace)
                repository.syncNewerMessages(ThreadId("channel-1"), 10L, 30, requestTrace = trace)
            }
            return network.requests
        }
        val withTrace = runWith("newer_page_s3_fs=7")
        val withoutTrace = runWith(null)
        assertEquals(withoutTrace, withTrace)
        assertTrue(
            withTrace.none { request -> request.query.any { (k, _) -> k == "trace" } },
            "requestTrace must not reach wire query; requests=$withTrace",
        )
    }

    private class FakeConversationNetworkClient(
        private val responses: Map<String, String> = emptyMap(),
        private val failures: Map<String, String> = emptyMap(),
        private val uploadBody: String = """{"attachments":[]}""",
        private val uploadResponse: HomeNetworkResponse? = null,
        private val requestStarted: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        private val requestRelease: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        private val responseForRequest: (RecordedConversationRequest) -> String? = { null },
    ) : SlockNetworkClient {
        val requests = mutableListOf<RecordedConversationRequest>()
        val serverOverrides = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        val uploadedFileNames = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            val requestKey = "$method $normalized"
            requests += RecordedConversationRequest(priority, method, normalized, query)
            serverOverrides += serverIdOverride?.value
            bodyText?.let { bodies += it }
            requestStarted[requestKey]?.complete(Unit)
            requestRelease[requestKey]?.await()
            failures[requestKey]?.let { message ->
                return HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = message)
            }
            return HomeNetworkResponse(
                code = 200,
                bodyText = responseForRequest(requests.last()) ?: responses[requestKey] ?: """{"threads":[]}""",
            )
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse {
            requests += RecordedConversationRequest(priority, "POST", "messages/channels/${channelId.value}/attachments", emptyList())
            uploadedFileNames += files.map { it.fileName }
            return uploadResponse ?: HomeNetworkResponse(code = 200, bodyText = uploadBody)
        }
    }

    private data class RecordedConversationRequest(
        val priority: DataRequestPriority,
        val method: String,
        val path: String,
        val query: List<Pair<String, String>> = emptyList()
    )

    private companion object {
        fun messageJson(
            id: String,
            channelId: String,
            seq: Long = 1,
            threadId: String? = null,
            content: String = "hello"
        ): String =
            buildString {
                append("""{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"$content","createdAt":"2026-05-22T10:00:00.000Z"""")
                if (threadId != null) append(""","threadId":"$threadId"""")
                append("}")
            }

        fun systemMessageJson(
            id: String,
            channelId: String,
            seq: Long,
            content: String,
        ): String =
            """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"system","senderName":"System","messageType":"system","content":"$content","createdAt":"2026-05-22T10:00:00.000Z"}"""
    }
}
