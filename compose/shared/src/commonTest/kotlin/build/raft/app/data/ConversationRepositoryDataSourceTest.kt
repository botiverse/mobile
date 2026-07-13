package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.MultipartFile
import build.raft.app.network.sync.MessageDomainStore
import build.raft.app.network.sync.MessageReadStateCheckpoint
import build.raft.app.network.sync.MessageReadStateProjection
import build.raft.app.network.sync.MessageReceiverKind
import build.raft.app.network.sync.MessageScopeRebaselineToken
import build.raft.app.network.sync.FoldPersistFrame
import build.raft.app.network.sync.FoldPersistenceAdapter
import build.raft.app.network.sync.RestoredFoldState
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncIngestOutcome
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationRepositoryDataSourceTest {
    @Test
    fun authoritativeLatestChannelLoadBeginsBeforeRequestAndCompletesCanonicalCoverage() = runBlocking {
        val domain = RecordingMessageDomainStore()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[{"id":"m-31","seq":31,"channelId":"channel-1","senderType":"user","senderId":"user-2","senderName":"User 2","messageType":"chat","content":"hello","randomId":null,"threadId":null,"createdAt":"2026-07-14T00:00:00Z"}],"messageWindow":{"schemaVersion":1,"domain":"receiver_visible_messages_v1","serverId":"server-1","receiverKind":"user","receiverId":"user-1","scopeId":"channel-1","coveredAfterSeq":30,"coveredFromSeq":31,"coveredThroughSeq":31,"remoteHighWaterSeq":31,"hasGap":false,"hasNewer":false,"completeThroughLatest":true}}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network, messageDomainStore = domain)

        val result = repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)

        assertIs<DataResult.Content<ThreadSnapshot>>(result)
        assertEquals(listOf("begin:server-1:channel-1", "complete:server-1:channel-1:31:30:31:false"), domain.calls)
    }

    @Test
    fun malformedCanonicalRowNeverCompletesCoverage() = runBlocking {
        val domain = RecordingMessageDomainStore()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[{"id":"m-31","seq":31,"channelId":"channel-1","senderType":"user","senderId":"user-2","messageType":"chat","content":"hello","createdAt":"2026-07-14T00:00:00Z"}]}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network, messageDomainStore = domain)

        repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)

        assertEquals(listOf("begin:server-1:channel-1", "invalidate:server-1:channel-1:1"), domain.calls)
    }

    @Test
    fun nonLatestPageDoesNotBeginOrReplaceAuthoritativeCoverage() = runBlocking {
        val domain = RecordingMessageDomainStore()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("m-7", "channel-1", seq = 7)}],"messageWindow":{"schemaVersion":1,"domain":"receiver_visible_messages_v1","serverId":"server-1","receiverKind":"user","receiverId":"user-1","scopeId":"channel-1","coveredAfterSeq":0,"coveredFromSeq":7,"coveredThroughSeq":7,"remoteHighWaterSeq":31,"hasGap":true,"hasNewer":true,"completeThroughLatest":false}}"""
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network, messageDomainStore = domain)

        repository.loadOlderMessages(ThreadId("channel-1"), beforeSeq = 8, limit = 50)

        assertEquals(emptyList(), domain.calls)
    }

    @Test
    fun failedLatestChannelRequestsConsumeGenerationAndDropExistingCoverage() = runBlocking {
        val path = "GET messages/channel/channel-1"
        val clients =
            listOf(
                FakeConversationNetworkClient(responseCodes = mapOf(path to 500)),
                FakeConversationNetworkClient(responses = mapOf(path to "")),
                FakeConversationNetworkClient(responses = mapOf(path to "{")),
                FakeConversationNetworkClient(failures = mapOf(path to "offline")),
            )

        clients.forEach { network ->
            val domain = RecordingMessageDomainStore(initialCoverageComplete = true)
            val repository = RealConversationRepository(sampleStore(), network, messageDomainStore = domain)

            repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)

            assertFalse(domain.coverageComplete)
            assertEquals(listOf("begin:server-1:channel-1", "invalidate:server-1:channel-1:1"), domain.calls)
        }
    }

    @Test
    fun cancelledLatestChannelRequestConsumesGenerationAndRethrowsCancellation() = runBlocking {
        val path = "GET messages/channel/channel-1"
        val domain = RecordingMessageDomainStore(initialCoverageComplete = true)
        val repository =
            RealConversationRepository(
                sampleStore(),
                FakeConversationNetworkClient(cancellations = setOf(path)),
                messageDomainStore = domain,
            )

        assertFailsWith<CancellationException> {
            repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)
        }

        assertFalse(domain.coverageComplete)
        assertEquals(listOf("begin:server-1:channel-1", "invalidate:server-1:channel-1:1"), domain.calls)
    }

    @Test
    fun failedReplyThreadMessageRequestConsumesResolvedScopeGeneration() = runBlocking {
        val domain = RecordingMessageDomainStore(initialCoverageComplete = true)
        val repository =
            RealConversationRepository(
                sampleStore(),
                FakeConversationNetworkClient(
                    responses =
                        mapOf(
                            "POST channels/channel-1/threads" to
                                """{"threadChannelId":"thread-1","replyCount":0,"ok":true}"""
                        ),
                    responseCodes = mapOf("GET messages/channel/thread-1" to 500),
                ),
                messageDomainStore = domain,
            )

        repository.getReplyThread(ChannelId("channel-1"), MessageId("parent-1"), limit = 50)

        assertFalse(domain.coverageComplete)
        assertEquals(listOf("begin:server-1:thread-1", "invalidate:server-1:thread-1:1"), domain.calls)
    }

    @Test
    fun duplicateDroppedCompletionFallsThroughToFinalInvalidation() = runBlocking {
        val domain =
            RecordingMessageDomainStore(
                initialCoverageComplete = true,
                completionOutcome = SyncIngestOutcome.DuplicateDropped("channel-1", 31),
            )
        val repository =
            RealConversationRepository(
                sampleStore(),
                FakeConversationNetworkClient(
                    responses =
                        mapOf(
                            "GET messages/channel/channel-1" to
                                """{"messages":[{"id":"m-31","seq":31,"channelId":"channel-1","senderType":"user","senderId":"user-2","senderName":"User 2","messageType":"chat","content":"hello","randomId":null,"threadId":null,"createdAt":"2026-07-14T00:00:00Z"}],"messageWindow":{"schemaVersion":1,"domain":"receiver_visible_messages_v1","serverId":"server-1","receiverKind":"user","receiverId":"user-1","scopeId":"channel-1","coveredAfterSeq":30,"coveredFromSeq":31,"coveredThroughSeq":31,"remoteHighWaterSeq":31,"hasGap":false,"hasNewer":false,"completeThroughLatest":true}}"""
                        )
                ),
                messageDomainStore = domain,
            )

        repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)

        assertFalse(domain.coverageComplete)
        assertEquals(
            listOf(
                "begin:server-1:channel-1",
                "complete:server-1:channel-1:31:30:31:false",
                "invalidate:server-1:channel-1:1",
            ),
            domain.calls,
        )
    }

    @Test
    fun failedRequestInvalidatesExistingCoverageInRealMessageDomain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane =
            SyncCoreMessageShadowFoldLane(
                featureFlags =
                    SharedServerFeatureFlags(
                        api =
                            object : SlockFeatureFlagsApi {
                                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                                    error("flag lookup is not expected")
                            }
                    ),
                adapter =
                    object : FoldPersistenceAdapter {
                        override suspend fun applyFrame(frame: FoldPersistFrame) = Unit

                        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                            RestoredFoldState(facts = emptyList(), cursor = null)
                    },
                scope = scope,
                logger = {},
            )
        val domain = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        val baseline = domain.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1")!!
        domain.completeAuthoritativeRebaseline(
            token = baseline,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows =
                listOf(
                    build.raft.app.canonical.message.CanonicalMessageRow(
                        id = "m-30",
                        channelId = "channel-1",
                        seq = 30,
                        senderType = "user",
                        senderId = "user-2",
                        senderName = "User 2",
                        content = "baseline",
                        messageType = "chat",
                        createdAt = "2026-07-14T00:00:00Z",
                    )
                ),
            coveredAfterSeq = 29,
            coveredFromSeq = 30,
            coveredThroughSeq = 30,
            remoteHighWaterSeq = 30,
            hasGap = false,
            hasNewer = false,
        )
        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 30)
        assertTrue(domain.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        val repository =
            RealConversationRepository(
                sampleStore(),
                FakeConversationNetworkClient(responseCodes = mapOf("GET messages/channel/channel-1" to 500)),
                messageDomainStore = domain,
            )

        repository.getThread(ThreadId("channel-1"), includeFollowedSummaries = false, limit = 50)

        assertFalse(domain.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        scope.cancel()
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
                              "peerDescription":"Historical agent conversation"
                            }
                            """.trimIndent()
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getMessageContext(MessageId("message-1"))

        val conversation = assertIs<DataResult.Content<ThreadSnapshot>>(result).data.conversation
        assertEquals(ConversationKind.DirectMessage, conversation.kind)
        assertEquals("dm-deleted-agent", conversation.channelId?.value)
        assertEquals("Removed Search Peer", conversation.name)
        assertEquals("agent", conversation.directMessagePeerType)
        assertEquals("agent-deleted", conversation.presenceMemberId?.value)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/context/message-1"),
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "channels/dm-deleted-agent")
            ),
            network.requests
        )
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
    fun getThreadInitialLoadReturnsCanonicalMessagesWithoutThreadSummaryDependency() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("parent-1", "channel-1", threadId = "thread-1")}],"historyLimited":false}""",
                    )
            )
        val repository = RealConversationRepository(sampleStore(), network)

        val result = repository.getThread(ThreadId("channel-1"))

        val snapshot = assertIs<DataResult.Content<ThreadSnapshot>>(result).data
        val message = snapshot.messages.single()
        assertEquals("parent-1", message.id.value)
        assertEquals("thread-1", message.threadChannelId?.value)
        assertEquals(0, message.replyCount)
        assertEquals(0, message.unreadReplyCount)
        assertEquals(null, message.firstUnreadReplyMessageId?.value)
        assertFalse(message.isThreadFollowed)
        assertEquals(
            listOf(
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/channel-1", listOf("limit" to "30"))
            ),
            network.requests
        )
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
                RecordedConversationRequest(DataRequestPriority.Foreground, "GET", "messages/channel/thread-1", listOf("limit" to "25"))
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
    fun pagingContextFilesAndUploadConsumeCanonicalDtos() = runBlocking {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/channel-1" to
                            """{"messages":[${messageJson("older-1", "channel-1", seq = 7)}],"historyLimited":false}""",
                        "GET channels/channel-1/threads" to
                            """
                            {
                              "older-1": {
                                "threadChannelId": "thread-older-1",
                                "replyCount": 2,
                                "unreadCount": 1,
                                "firstUnreadMessageId": "reply-older-2"
                              }
                            }
                            """.trimIndent(),
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
        assertEquals("thread-older-1", olderMessage.threadChannelId?.value)
        assertEquals(2, olderMessage.replyCount)
        assertEquals(1, olderMessage.unreadReplyCount)
        assertEquals("reply-older-2", olderMessage.firstUnreadReplyMessageId?.value)
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
        assertTrue(
            network.requests.any { request ->
                request.path == "channels/channel-1/threads" &&
                    request.query == listOf("parentMessageIds" to "older-1")
            }
        )
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

    private class FakeConversationNetworkClient(
        private val responses: Map<String, String> = emptyMap(),
        private val failures: Map<String, String> = emptyMap(),
        private val responseCodes: Map<String, Int> = emptyMap(),
        private val cancellations: Set<String> = emptySet(),
        private val uploadBody: String = """{"attachments":[]}"""
    ) : SlockNetworkClient {
        val requests = mutableListOf<RecordedConversationRequest>()
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
            requests += RecordedConversationRequest(priority, method, normalized, query)
            bodyText?.let { bodies += it }
            if ("$method $normalized" in cancellations) throw CancellationException("cancelled")
            failures["$method $normalized"]?.let { message ->
                return HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = message)
            }
            return HomeNetworkResponse(
                code = responseCodes["$method $normalized"] ?: 200,
                bodyText = responses["$method $normalized"] ?: """{"threads":[]}""",
            )
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse {
            requests += RecordedConversationRequest(priority, "POST", "messages/channels/${channelId.value}/attachments", emptyList())
            uploadedFileNames += files.map { it.fileName }
            return HomeNetworkResponse(code = 200, bodyText = uploadBody)
        }
    }

    private class RecordingMessageDomainStore(
        initialCoverageComplete: Boolean = false,
        private val completionOutcome: SyncIngestOutcome = SyncIngestOutcome.Applied("channel-1", 0),
    ) : MessageDomainStore {
        val calls = mutableListOf<String>()
        var coverageComplete: Boolean = initialCoverageComplete

        override fun beginAuthoritativeRebaseline(
            serverId: String,
            memberId: String,
            scopeId: String,
        ): MessageScopeRebaselineToken {
            calls += "begin:$serverId:$scopeId"
            return MessageScopeRebaselineToken(
                serverId = serverId,
                memberId = memberId,
                ownerGeneration = 1,
                scopeId = scopeId,
                generation = 1,
                epoch = null,
            )
        }

        override fun completeAuthoritativeRebaseline(
            token: MessageScopeRebaselineToken,
            memberId: String,
            receiverKind: MessageReceiverKind,
            rows: List<build.raft.app.canonical.message.CanonicalMessageRow>,
            coveredAfterSeq: Long,
            coveredFromSeq: Long,
            coveredThroughSeq: Long,
            remoteHighWaterSeq: Long,
            hasGap: Boolean,
            hasNewer: Boolean,
        ): SyncIngestOutcome? {
            calls += "complete:${token.serverId}:${token.scopeId}:${rows.singleOrNull()?.seq}:$coveredAfterSeq:$remoteHighWaterSeq:$hasNewer"
            val outcome =
                if (completionOutcome is SyncIngestOutcome.Applied) {
                    SyncIngestOutcome.Applied(token.scopeId, remoteHighWaterSeq)
                } else {
                    completionOutcome
                }
            coverageComplete = outcome is SyncIngestOutcome.Applied
            return outcome
        }

        override fun invalidateAuthoritativeRebaseline(token: MessageScopeRebaselineToken) {
            calls += "invalidate:${token.serverId}:${token.scopeId}:${token.generation}"
            coverageComplete = false
        }

        override fun projectReadState(
            serverId: String,
            memberId: String,
            receiverKind: MessageReceiverKind,
            scopeId: String,
            checkpoint: MessageReadStateCheckpoint,
        ): MessageReadStateProjection =
            MessageReadStateProjection(
                readStateVersion = checkpoint.readStateVersion,
                maxReadSeq = checkpoint.maxReadSeq,
                unreadCount = 0,
                hasMention = false,
                firstUnreadMessageId = null,
                firstMentionMessageId = null,
                complete = coverageComplete,
            )
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
    }
}
