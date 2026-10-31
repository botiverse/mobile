package build.raft.app

import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.testReplyCacheIdentityAuthority
import build.raft.app.data.ComposeLocalDataStore
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class SharedThreadStoreTest {
    internal class FakeThreadApi(
        var bodyText: String = messagesResponse("network body"),
        var olderBodyText: String = messagesResponse("older body", id = "older", seq = 1, historyLimited = false),
        var newerBodyText: String = messagesResponse("newer body", id = "newer", seq = 100, historyLimited = false),
        var newerResponseCode: Int = 200,
        var failChannelMessages: Boolean = false,
        var channelMessagesResponseCode: Int = 200
    ) : SharedThreadNetworkApi {
        var channelMessageCalls: Int = 0
        var failReadAll: Boolean = false
        var readAllDelayMillis: Long = 0L
        val readAllChannels: MutableList<String> = mutableListOf()
        val readSeqRequests: MutableList<Pair<String, Long>> = mutableListOf()
        var activeReadAllCalls: Int = 0
        var maxConcurrentReadAllCalls: Int = 0
        val beforeSeqs: MutableList<Long> = mutableListOf()
        val afterSeqs: MutableList<Long> = mutableListOf()
        /** Task #168 step 1: gap catch-up latest-page requests ([gapCatchUpChannelMessages]). */
        var catchUpCalls: Int = 0
        val catchUpLimits: MutableList<Int> = mutableListOf()
        val channelMessageLimits: MutableList<Int> = mutableListOf()
        var messageSyncCalls: Int = 0
        val channelIds: MutableList<String> = mutableListOf()
        var sendMessageCalls: Int = 0
        /** The `channelId` of every POST /messages, in order. */
        val sentChannelIds: MutableList<String> = mutableListOf()
        /** Optional canonical POST /messages body for tests that exercise the send parser. */
        var sendResponseBody: String? = null
        var lastAttachmentIds: List<String> = emptyList()
        var lastRandomId: String? = null
        val savedMessageIds: MutableList<String> = mutableListOf()
        val unsavedMessageIds: MutableList<String> = mutableListOf()
        val addedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val removedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val createdThreads: MutableList<Pair<String, String>> = mutableListOf()
        val mentionActionRequests: MutableList<Pair<String, List<String>>> = mutableListOf()
        val sentAttachmentComments: MutableList<Pair<String, String>> = mutableListOf()
        val bodyTextByThreadId: MutableMap<String, String> = mutableMapOf()
        val channelMessagesStartedByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        val releaseChannelMessagesByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        var channelMessagesStarted: CompletableDeferred<Unit>? = null
        var releaseChannelMessages: CompletableDeferred<Unit>? = null
        var afterMessagesStarted: CompletableDeferred<Unit>? = null
        var releaseAfterMessages: CompletableDeferred<Unit>? = null
        var catchUpStarted: CompletableDeferred<Unit>? = null
        var releaseCatchUp: CompletableDeferred<Unit>? = null
        val catchUpStartedSignals = ArrayDeque<CompletableDeferred<Unit>>()
        val catchUpReleases = ArrayDeque<CompletableDeferred<Unit>>()
        /** Latest pages served to the gap catch-up steps, in order; [newerBodyText] once drained. */
        val catchUpBodyTexts = ArrayDeque<String>()
        val catchUpResponseCodes = ArrayDeque<Int>()

        override suspend fun channelMessages(
            threadId: String,
            limit: Int,
            beforeSeq: Long?,
            afterSeq: Long?
        ): HomeNetworkResponse {
            channelMessageCalls += 1
            channelIds += threadId
            channelMessageLimits += limit
            if (beforeSeq == null && afterSeq == null) {
                channelMessagesStarted?.complete(Unit)
                channelMessagesStartedByThreadId[threadId]?.complete(Unit)
                releaseChannelMessages?.await()
                releaseChannelMessagesByThreadId[threadId]?.await()
            }
            if (failChannelMessages) throw IllegalStateException("offline")
            val responseBody = bodyTextByThreadId[threadId] ?: bodyText
            return when {
                beforeSeq != null -> {
                    beforeSeqs += beforeSeq
                    HomeNetworkResponse(code = 200, bodyText = olderBodyText)
                }
                afterSeq != null -> {
                    afterSeqs += afterSeq
                    afterMessagesStarted?.complete(Unit)
                    releaseAfterMessages?.await()
                    HomeNetworkResponse(code = newerResponseCode, bodyText = newerBodyText)
                }
                else -> HomeNetworkResponse(code = channelMessagesResponseCode, bodyText = responseBody)
            }
        }

        override suspend fun gapCatchUpChannelMessages(threadId: String, limit: Int): HomeNetworkResponse {
            val startedSignal = catchUpStartedSignals.removeFirstOrNull()
            val callRelease = catchUpReleases.removeFirstOrNull()
            val responseBody = catchUpBodyTexts.removeFirstOrNull() ?: newerBodyText
            catchUpCalls += 1
            catchUpLimits += limit
            startedSignal?.complete(Unit)
            catchUpStarted?.complete(Unit)
            (callRelease ?: releaseCatchUp)?.await()
            return HomeNetworkResponse(code = catchUpResponseCodes.removeFirstOrNull() ?: 200, bodyText = responseBody)
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse {
            // Task #168 step 1: gap recovery no longer reads the `/messages/sync` delta.
            messageSyncCalls += 1
            return HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = "messages_sync_not_expected")
        }

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>,
            randomId: String?
        ): HomeNetworkResponse {
            sendMessageCalls += 1
            sentChannelIds += channelId
            lastAttachmentIds = attachmentIds
            lastRandomId = randomId
            return HomeNetworkResponse(code = 200, bodyText = sendResponseBody ?: messagesResponse(body))
        }

        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse {
            mentionActionRequests += action to resolutionIds
            return HomeNetworkResponse(code = 200, bodyText = """{"results":[]}""")
        }

        override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")

        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId/html"}""")

        override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"comments":[],"threadChannelId":"comments-$attachmentId"}""")

        override suspend fun sendAttachmentComment(attachmentId: String, body: String, anchor: build.raft.app.data.ThreadAttachmentCommentAnchor?): HomeNetworkResponse {
            sentAttachmentComments += attachmentId to body
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse {
            activeReadAllCalls += 1
            maxConcurrentReadAllCalls = maxOf(maxConcurrentReadAllCalls, activeReadAllCalls)
            readAllChannels += channelId
            try {
                if (readAllDelayMillis > 0L) {
                    delay(readAllDelayMillis)
                }
                return if (failReadAll) {
                    HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = "read_failed")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = "{}")
                }
            } finally {
                activeReadAllCalls -= 1
            }
        }

        override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse {
            readSeqRequests += channelId to seq
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true,"seq":$seq}""")
        }

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse {
            savedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","saved":true}""")
        }

        override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse {
            unsavedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","saved":false}""")
        }

        override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse {
            addedReactions += messageId to emoji
            return HomeNetworkResponse(
                code = 200,
                bodyText = """{"id":"$messageId","reactions":[{"emoji":"$emoji","count":1,"reactorIds":["u1"],"reactorNames":["User"]}]}"""
            )
        }

        override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse {
            removedReactions += messageId to emoji
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","reactions":[]}""")
        }

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse {
            createdThreads += channelId to parentMessageId
            return HomeNetworkResponse(code = 200, bodyText = """{"threadChannelId":"thread-$parentMessageId","parentMessageId":"$parentMessageId","created":true}""")
        }

        override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"channelId":"$channelId","targetMessageId":"$messageId","messages":[],"historyLimited":false}"""
            )
    }
    private class FailingThreadWriteStore(
        private val delegate: ComposeLocalDataStore
    ) : ComposeLocalDataStore by delegate {
        override suspend fun writeThread(snapshot: ThreadSnapshot) {
            error("thread write failed")
        }
    }
    private fun taskPayload(): RaftThreadScreenPayload {
        val message =
            contentPayload("task body").messages.single().copy(
                taskId = "task-1",
                taskStatus = "Todo"
            )
        return contentPayload("task body").copy(
            messages = listOf(message),
            tasks =
                listOf(
                    RaftThreadTaskItem(
                        id = "task-1",
                        title = "#44 Task",
                        status = "Todo",
                        taskNumber = 44
                    )
            )
        )
    }
    private fun taskPayloadWithMessageReferenceOnly(): RaftThreadScreenPayload {
        val message = contentPayload("task body").messages.single()
        return contentPayload("task body").copy(
            messages = listOf(message),
            tasks =
                listOf(
                    RaftThreadTaskItem(
                        id = "task-1",
                        title = "#44 Task",
                        status = "Todo",
                        taskNumber = 44,
                        messageId = "m1"
                    )
                )
        )
    }
    companion object {
        // Shared with the split `SharedThreadStore<Facet>Test` files.
        internal fun basePayload(): RaftThreadScreenPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading",
                canSend = true
            )

        internal fun sampleSession(): StoredAuthSession =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai", displayName = "Artin"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1"
            )

        internal fun focusedReplySnapshot(
            threadId: String,
            parentChannelId: String,
            parentMessageId: String,
            focusedMessageId: String
        ): ThreadSnapshot =
            threadSnapshot(
                threadId = threadId,
                messages =
                    listOf(
                        message(
                            threadId = threadId,
                            channelId = threadId,
                            id = focusedMessageId,
                            seq = 2,
                            body = "focused reply"
                        )
                    ),
                parentMessage = null,
                conversation =
                    conversationSummary(threadId).copy(
                        kind = ConversationKind.Thread,
                        channelId = ChannelId(threadId),
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId(parentChannelId),
                                parentMessageId = MessageId(parentMessageId),
                                streamChannelId = ChannelId(threadId)
                            )
                    )
            ).copy(highlightedMessageId = MessageId(focusedMessageId))

        internal fun threadSnapshot(
            threadId: String = "channel-1",
            body: String = "repository body",
            id: String = "m1",
            seq: Long = 10,
            messages: List<Message> = listOf(message(threadId = threadId, id = id, seq = seq, body = body)),
            replySummaries: ThreadReplySummariesSnapshot = ThreadReplySummariesSnapshot(emptyList()),
            parentMessage: Message? = null,
            conversation: ConversationSummary = conversationSummary(threadId)
        ): ThreadSnapshot =
            ThreadSnapshot(
                conversation = conversation,
                messages = messages,
                replySummaries = replySummaries,
                replySummariesAuthority =
                    if (replySummaries.threads.isEmpty()) {
                        build.raft.app.data.ThreadReplySummariesAuthority.Unavailable
                    } else {
                        build.raft.app.data.ThreadReplySummariesAuthority.Embedded
                    },
                parentMessage = parentMessage
            )

        internal fun replySummaries(replyCount: Int): DataResult<ThreadReplySummariesSnapshot> =
            DataResult.Content(
                ThreadReplySummariesSnapshot(
                    threads =
                        listOf(
                            InboxItem(
                                key = "thread:m1",
                                kind = InboxItemKind.Thread,
                                channelId = ChannelId("thread-m1"),
                                channelName = "reply thread",
                                threadChannelId = ChannelId("thread-m1"),
                                parentChannelId = ChannelId("channel-1"),
                                parentMessageId = MessageId("m1"),
                                replyCount = replyCount
                            )
                        )
                )
            )

        internal fun conversationSummary(threadId: String = "channel-1"): ConversationSummary =
            ConversationSummary(
                threadId = ThreadId(threadId),
                channelId = ChannelId(threadId),
                kind = ConversationKind.Channel,
                name = "general"
            )

        internal fun replyConversation(): ConversationSummary =
            conversationSummary("thread-m1").copy(
                kind = ConversationKind.Thread,
                channelId = ChannelId("thread-m1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("m1"),
                        streamChannelId = ChannelId("thread-m1")
                    )
            )

        internal fun message(
            threadId: String = "channel-1",
            channelId: String = threadId,
            id: String,
            seq: Long,
            body: String,
            threadChannelId: String? = null,
            replyCount: Int = 0,
            unreadReplyCount: Int = 0,
            messageType: MessageType = MessageType.Chat,
            authorKind: MemberKind = MemberKind.Human,
            forwardBundle: ForwardMessageBundle? = null
        ): Message =
            Message(
                id = MessageId(id),
                threadId = ThreadId(threadId),
                channelId = ChannelId(channelId),
                threadChannelId = threadChannelId?.let(::ChannelId),
                author =
                    MemberProfile(
                        id = if (authorKind == MemberKind.System) MemberId("system") else MemberId("u1"),
                        displayName = if (authorKind == MemberKind.System) "System" else "Artin",
                        handle = if (authorKind == MemberKind.System) "system" else "artin",
                        kind = authorKind,
                        presence = Presence.Active
                ),
                seq = seq,
                timestampLabel = "2026-05-19T16:00:00Z",
                body = body,
                messageType = messageType,
                replyCount = replyCount,
                unreadReplyCount = unreadReplyCount,
                forwardBundle = forwardBundle
            )

        internal fun messagesResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10,
            historyLimited: Boolean = true,
            channelId: String = "channel-1"
        ): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "channelId": "$channelId",
                  "content": "$body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()

        internal fun emptyMessagesResponse(historyLimited: Boolean = false): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": []
            }
            """.trimIndent()

        internal fun systemMessagesResponse(
            body: String,
            id: String,
            seq: Long,
            historyLimited: Boolean = false
        ): String {
            val escapedBody = body.jsonEscaped()
            return """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "content": "$escapedBody",
                  "createdAt": "2026-06-13T01:00:00Z",
                  "channelId": "channel-1",
                  "senderId": "system",
                  "senderName": "System",
                  "senderType": "user",
                  "messageType": "system"
                }
              ]
            }
            """.trimIndent()
        }

        internal fun agentMessagesResponse(): String =
            """
            {
              "historyLimited": true,
              "messages": [
                {
                  "id": "m1",
                  "seq": 10,
                  "content": "agent body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "agent-1",
                  "senderName": "Product-UX-Designer",
                  "senderType": "agent",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()

        /** A `messages/channel` page object of `m<seq>` / `body-<seq>` messages (task #168 step 1). */
        internal fun pageRangeResponse(
            seqs: Iterable<Long>,
            channelId: String = "channel-1",
            historyLimited: Boolean = false,
        ): String =
            """{"historyLimited": $historyLimited, "messages": ${syncRangeResponse(seqs, channelId)}}"""

        /** A `messages/channel` page object of explicit (id, seq, body) messages (task #168 step 1). */
        internal fun pageResponse(
            vararg messages: Triple<String, Long, String>,
            channelId: String = "channel-1",
            historyLimited: Boolean = false,
        ): String =
            messages.joinToString(
                prefix = """{"historyLimited": $historyLimited, "messages": [""",
                postfix = "]}",
            ) { (id, seq, body) ->
                """
                {
                  "id": "$id",
                  "seq": $seq,
                  "channelId": "$channelId",
                  "content": "$body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }

        internal fun syncRangeResponse(
            seqs: Iterable<Long>,
            channelId: String = "channel-1",
        ): String =
            seqs.joinToString(prefix = "[", postfix = "]") { seq ->
                """
                {
                  "id": "m$seq",
                  "seq": $seq,
                  "channelId": "$channelId",
                  "content": "body-$seq",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }

        internal fun syncSystemResponse(
            body: String,
            id: String,
            seq: Long
        ): String {
            val escapedBody = body.jsonEscaped()
            return """
            [
              {
                "id": "$id",
                "seq": $seq,
                "content": "$escapedBody",
                "createdAt": "2026-06-13T01:00:00Z",
                "channelId": "channel-1",
                "senderId": "system",
                "senderName": "System",
                "senderType": "user",
                "messageType": "system"
              }
            ]
            """.trimIndent()
        }

        internal fun String.jsonEscaped(): String =
            replace("\\", "\\\\")
                .replace("\"", "\\\"")

        internal fun externalSlackMessageItem(
            id: String = "slack-root-1",
            body: String = "Slack root content",
            provider: String = "slack",
            externalName: String = "Alice in Slack",
            externalAvatar: String = "https://slack.example.com/alice.png",
            externalAuthor: ExternalMessageAuthor? = ExternalMessageAuthor(
                provider = provider,
                displayName = externalName,
                actorKind = "user",
                avatarUrl = externalAvatar
            ),
            authorKind: String = "external",
        ): RaftThreadMessageItem =
            RaftThreadMessageItem(
                id = id,
                authorId = "unknown",
                authorName = externalName,
                authorDescription = provider,
                authorKind = authorKind,
                avatarWireValue = externalAvatar,
                externalAuthor = externalAuthor,
                body = body,
                messageType = "Chat",
            )
    }
    @Test
    fun externalSlackRootMessageConvertsToMessageAndBackPreservingAttribution() {
        val slackItem = externalSlackMessageItem()

        val convertedMessage = slackItem.toMessage(
            threadId = ThreadId("channel-slack"),
            channelId = ChannelId("channel-slack"),
            currentUserId = "my-user-id",
        )

        assertNotNull(convertedMessage)
        assertEquals("slack-root-1", convertedMessage.id.value)
        assertEquals("Slack root content", convertedMessage.body)
        assertEquals(MemberKind.External, convertedMessage.author.kind)
        assertEquals("Alice in Slack", convertedMessage.author.displayName)
        assertEquals("Slack", convertedMessage.author.description)
        assertEquals("https://slack.example.com/alice.png", convertedMessage.author.avatar?.wireValue)
        assertNotNull(convertedMessage.externalAuthor)
        assertEquals("slack", convertedMessage.externalAuthor?.provider)
        assertEquals("Alice in Slack", convertedMessage.externalAuthor?.displayName)
        assertEquals("https://slack.example.com/alice.png", convertedMessage.externalAuthor?.avatarUrl)

        val roundTripItem = convertedMessage.toRaftThreadMessageItem("my-user-id")
        assertEquals("slack-root-1", roundTripItem.id)
        assertEquals("external", roundTripItem.authorKind)
        assertEquals("Alice in Slack", roundTripItem.authorName)
        assertEquals("Slack", roundTripItem.authorDescription)
        assertEquals("https://slack.example.com/alice.png", roundTripItem.avatarWireValue)
        assertEquals(convertedMessage.externalAuthor, roundTripItem.externalAuthor)
    }
    @Test
    fun externalMessageMissingExternalAuthorKeepsFallbackAuthor() {
        val slackItemWithoutAuthor = externalSlackMessageItem(externalAuthor = null)
        val logs = mutableListOf<String>()

        val convertedMessage = slackItemWithoutAuthor.toMessage(
            threadId = ThreadId("channel-slack"),
            channelId = ChannelId("channel-slack"),
            diagnostics = logs::add
        )

        val message = assertNotNull(convertedMessage)
        assertEquals(MemberKind.External, message.author.kind)
        assertEquals("Alice in Slack", message.externalAuthor?.displayName)
        assertTrue(
            logs.any { it.contains("projection_value_defaulted") && it.contains("field=externalAuthor") },
            "Expected defaulted diagnostic for missing externalAuthor, got: $logs"
        )
    }
    @Test
    fun rememberReplyParentCandidateSucceedsForSlackBridgeExternalRootMessage() = runTest {
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val environmentId = "https://prod.example.test"
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() },
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val slackRootItem = externalSlackMessageItem(id = "slack-root-99")

        val accepted = dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-slack",
            parentMessage = slackRootItem,
            threadChannelId = "thread-slack-root-99"
        )

        assertTrue(accepted, "rememberReplyParentCandidate should accept external Slack root message with valid externalAuthor")

        val cachedMessage = messageStore.getScoped(
            requireNotNull(provenance.messageStoreScopeId),
            ChannelId("channel-slack"),
            MessageId("slack-root-99")
        )
        assertNotNull(cachedMessage)
        assertEquals(MemberKind.External, cachedMessage.author.kind)
        assertEquals("Alice in Slack", cachedMessage.author.displayName)
        assertEquals("Slack", cachedMessage.author.description)
        assertEquals("https://slack.example.com/alice.png", cachedMessage.author.avatar?.wireValue)
        assertEquals("slack", cachedMessage.externalAuthor?.provider)
    }
    @Test
    fun rememberReplyParentCandidateKeepsExternalRootMessageMissingExternalAuthorWithFallbackAuthor() = runTest {
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val environmentId = "https://prod.example.test"
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() },
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val invalidSlackRootItem = externalSlackMessageItem(id = "slack-root-bad", externalAuthor = null)

        val accepted = dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-slack",
            parentMessage = invalidSlackRootItem,
            threadChannelId = "thread-slack-root-bad"
        )

        assertTrue(accepted, "an external root message without externalAuthor is kept with a fallback author")
        val cachedMessage = assertNotNull(
            messageStore.getScoped(
                requireNotNull(provenance.messageStoreScopeId),
                ChannelId("channel-slack"),
                MessageId("slack-root-bad")
            )
        )
        assertEquals(MemberKind.External, cachedMessage.author.kind)
        assertEquals("Alice in Slack", cachedMessage.externalAuthor?.displayName)
    }
}
