package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.startLocalSendForCurrentOwner
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.WireMessage
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.InlineThreadReplyPreviewLimit
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import build.raft.app.thread.key
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.thread.threadInlineReplyPresentation
import build.raft.app.thread.threadReplyMetadataFor
import build.raft.app.thread.toThreadReplyMetadata
import build.raft.app.thread.withReplySummaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * artin, #wg-mobile-sync (Mac 1.28): "After a message is sent, all the inline thread reply previews
 * are gone": every parent in the channel fell back to the "N replies" pill.
 *
 * While the send is in flight, a new message makes the page catch up, and the catch-up re-reads the
 * reply summaries with the local send row still on screen. Its `local-send-…` id is not a message
 * id, so the server refuses the whole summaries request and only the followed-threads list answers,
 * which carries no reply previews. That preview-less answer replaced the replies under each parent.
 */
class SharedThreadStoreInlineRepliesAfterSendTest {
    @Test
    fun sendingAMessageKeepsTheParentsInlineReplies() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(messages = listOf(parentMessage))),
                threadReplySummariesProvider = { _, parentMessageIds -> serverSummaries(parentMessageIds) },
            )
        val logs = mutableListOf<String>()
        fun summaryRefreshesCommitted() =
            logs.count { line -> line.startsWith("thread_secondary_refresh_result") && "emitted=" in line }
        fun askedParents() = repository.getThreadReplySummariesRequests.flatMap { (_, ids) -> ids.map { it.value } }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        fun parent() = store.state.value.payload.messages.single { message -> message.id == ParentId }

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        awaitUntil("the opening reply summaries", { logs.takeLast(6) }) { summaryRefreshesCommitted() >= 1 }
        val openingRefreshes = summaryRefreshesCommitted()
        assertEquals(listOf("reply-1", "reply-2"), parent().inlineReplies?.replies?.map { it.messageId })

        // The send is in flight when another member's message arrives. Seqs are server-wide, so it is
        // far past the newest one on screen: the page catches up and re-reads the reply summaries.
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(LocalSendId, "on my way", randomId = "mobile-r1"))
        repository.latestResult = DataResult.Content(threadSnapshot(messages = listOf(parentMessage, otherMembersMessage)))
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(otherMembersMessage)))
        awaitUntil("the catch-up's reply summaries", { askedParents() to logs.takeLast(6) }) {
            OtherMembersMessageId in askedParents() && summaryRefreshesCommitted() > openingRefreshes
        }
        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = LocalSendId,
                page = sentPage(),
                expectedProvenance = requireNotNull(dataSource.currentReplyCacheProvenance()),
                expectedRoute = store.state.value.payload.resolveThreadRouteIdentity(),
            )
        )

        assertTrue(store.state.value.payload.messages.any { message -> message.id == SentId })
        assertEquals(listOf("reply-1", "reply-2"), parent().inlineReplies?.replies?.map { it.messageId })
        val shown =
            threadInlineReplyPresentation(
                model = parent().inlineReplies,
                replyMetadata = store.state.value.replyMetadataByParentId.threadReplyMetadataFor(parent()),
                hasDraft = false,
            )
        assertEquals(listOf("reply 1", "reply 2"), shown?.replies?.map { it.content }, "the replies stay under the parent")
        assertEquals(2, shown?.replyCount)
        // The server only takes message ids as thread parents; a local send row is not one.
        assertTrue(askedParents().all { id -> ServerMessageId.matches(id) }, "asked for a parent that is no message: ${askedParents()}")
        scope.cancel()
    }

    @Test
    fun aSummaryWithoutReplyPreviewsKeepsTheRepliesAMessageShows() {
        val shown =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = ParentId,
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent body",
                            seq = 10,
                            threadChannelId = ParentThreadChannelId,
                            replyCount = 2,
                            inlineReplies =
                                initialThreadRepliesReadModel(
                                    replies = listOf(reply(1), reply(2)),
                                    replyCount = 2,
                                    limit = InlineThreadReplyPreviewLimit,
                                ),
                        )
                    ),
            )

        // The followed-threads list has no previews, and it is all the summaries hold when the
        // channel's own summaries request fails.
        val kept = shown.withReplySummaries(summaries(replyCount = 3, latestReplies = emptyList())).messages.single()
        val keptPresentation =
            threadInlineReplyPresentation(
                model = kept.inlineReplies,
                replyMetadata = kept.toThreadReplyMetadata(),
                hasDraft = false,
            )
        assertEquals(listOf("reply-1", "reply-2"), keptPresentation?.replies?.map { it.messageId })
        assertEquals(3, keptPresentation?.replyCount, "the count still follows the summary")

        val newer = shown.withReplySummaries(summaries(replyCount = 3, latestReplies = listOf(reply(2), reply(3)))).messages.single()
        assertEquals(listOf("reply-2", "reply-3"), newer.inlineReplies?.replies?.map { it.messageId })
    }

    private suspend fun awaitUntil(what: String, state: () -> Any?, condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(10)
        }
        error("waited for $what, state=${state()}")
    }

    private companion object {
        // Message ids are UUIDs, as the server requires of thread parents (slock lib/messageId.ts UUID_RE).
        const val ParentId = "5f0c1d52-7a3e-4c8b-9e21-3b6d8a4f0c11"
        const val OtherMembersMessageId = "9b2e7c41-0d5a-4f6e-8c3b-1a7d9e5f2b40"
        const val SentId = "c3a18f6e-2b9d-4e7a-a1c5-6f0b3d8e4a92"
        const val ParentThreadChannelId = "7af25082-7758-44fb-87d1-f75203200293"
        const val LocalSendId = "${ThreadTransientLocalSendIdPrefix}shared-shell-thread-channel-1-35ves5m9jhlsn-1"

        val ServerMessageId = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

        val parentMessage: Message =
            message(id = ParentId, seq = 10, body = "parent body", threadChannelId = ParentThreadChannelId, replyCount = 2)

        val otherMembersMessage: Message =
            Message(
                id = MessageId(OtherMembersMessageId),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                author = member("u2", "Stdrc"),
                timestampLabel = "2026-05-19T16:02:00Z",
                body = "looking now",
                seq = 40,
            )

        fun reply(index: Int): ReplyPreview =
            ReplyPreview(
                messageId = "reply-$index",
                seq = 20L + index,
                content = "reply $index",
                senderId = "agent-1",
                senderType = "agent",
                senderName = "agent-1",
                createdAt = "2026-05-19T16:0$index:30Z",
            )

        fun summaries(replyCount: Int, latestReplies: List<ReplyPreview>): ThreadReplySummariesSnapshot =
            ThreadReplySummariesSnapshot(
                threads =
                    listOf(
                        InboxItem(
                            key = "thread:$ParentThreadChannelId",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId(ParentThreadChannelId),
                            channelName = "reply thread",
                            threadChannelId = ChannelId(ParentThreadChannelId),
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId(ParentId),
                            replyCount = replyCount,
                            latestReplies = latestReplies,
                        )
                    ),
                followedThreadKeys = setOf("thread:$ParentThreadChannelId"),
            )

        /**
         * What [build.raft.app.data.RealConversationRepository.getThreadReplySummaries] answers: slock
         * refuses `GET /channels/:id/threads` when one parent id is not a message id (400
         * `thread_parent_message_ids_invalid`, `channels.ts` `parseThreadSummaryParentMessageIds`), and
         * the followed-threads list alone then answers, without `latestReplies`.
         */
        fun serverSummaries(parentMessageIds: List<MessageId>): DataResult<ThreadReplySummariesSnapshot> =
            if (parentMessageIds.all { id -> ServerMessageId.matches(id.value) }) {
                DataResult.Content(summaries(replyCount = 2, latestReplies = listOf(reply(1), reply(2))))
            } else {
                DataResult.Content(summaries(replyCount = 2, latestReplies = emptyList()))
            }

        fun sentPage(): ThreadMessagesPage =
            ThreadMessagesPage(
                messages =
                    listOf(
                        WireMessage(
                            id = SentId,
                            channelId = "channel-1",
                            senderType = "user",
                            senderId = "u1",
                            senderName = "Artin",
                            messageType = "chat",
                            content = "on my way",
                            createdAt = "2026-05-19T16:03:00Z",
                            seq = 41,
                            randomId = "mobile-r1",
                        )
                    ),
                canLoadMoreBefore = null,
                canLoadMoreAfter = null,
            )
    }
}
