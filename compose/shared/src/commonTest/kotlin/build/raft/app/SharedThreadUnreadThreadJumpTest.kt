package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The unread-thread pill's store side: parents the channel page has not loaded are resolved from the
 * followed-threads list, and a tap on one loads the messages around it before focusing it.
 */
class SharedThreadUnreadThreadJumpTest {
    @Test
    fun unresolvedUnreadThreadsAreLookedUpOnceAndOnlyThisChannelsParentsAreKept() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        val release = CompletableDeferred<Unit>()
        repository.releaseUnreadFollowed = release
        repository.unreadFollowedResult =
            DataResult.Content(
                ThreadListSnapshot(
                    server = ServerSummary(id = ServerId(ServerIdValue), name = "Slock"),
                    threads =
                        listOf(
                            followedRow("t-old", parentChannel = ChannelIdValue, parentMessage = "p-old", unread = 3),
                            followedRow("t-elsewhere", parentChannel = "channel-2", parentMessage = "p-x", unread = 1),
                        ),
                )
            )
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())

        try {
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-old")))
            // A repeat while the lookup is in flight does not issue a second request.
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-old")))
            advanceUntilIdle()
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.unreadFollowedRequests.size)
            assertEquals(
                mapOf("t-old" to ThreadUnreadThreadParent("t-old", ChannelIdValue, "p-old")),
                store.state.value.unreadThreadParents,
            )
            // An id already asked for is never asked again on this page.
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-old", "t-elsewhere")))
            advanceUntilIdle()
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-elsewhere")))
            advanceUntilIdle()
            assertEquals(2, repository.unreadFollowedRequests.size)
            assertEquals(setOf("t-old"), store.state.value.unreadThreadParents.keys)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun jumpingToALoadedParentFocusesItWithoutLoading() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())

        try {
            store.dispatch(SharedThreadIntent.JumpToMessage("m2", switchToTab = "chat"))
            advanceUntilIdle()

            assertEquals("m2", store.state.value.payload.focusedMessageId)
            assertEquals("chat", store.state.value.payload.selectedTab)
            assertTrue(repository.parentRequests.isEmpty())
            // Task #73: this focus request is marked as the pill's, so it lands on the replies block.
            assertEquals(
                store.state.value.payload.focusedMessageRequestNonce,
                store.state.value.unreadThreadJumpFocusNonce,
            )
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun jumpingToAParentThatIsNotLoadedLoadsTheMessagesAroundItAndFocusesIt() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        repository.parentResult = DataResult.Content(channelContext("p-old", "p-old-next"))
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())
        val nonceBefore = store.state.value.payload.focusedMessageRequestNonce

        try {
            store.dispatch(SharedThreadIntent.JumpToMessage("p-old", switchToTab = "chat"))
            advanceUntilIdle()

            assertEquals(listOf(MessageId("p-old")), repository.parentRequests.map { it.first })
            val payload = store.state.value.payload
            assertEquals(listOf("p-old", "p-old-next"), payload.messages.map { it.id })
            assertEquals("p-old", payload.focusedMessageId)
            assertNotEquals(nonceBefore, payload.focusedMessageRequestNonce)
            assertEquals("chat", payload.selectedTab)
            assertEquals(payload.focusedMessageRequestNonce, store.state.value.unreadThreadJumpFocusNonce)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun anOrdinaryFocusIsNotMarkedAsThePillsJump() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource(repository()), scope = scope, initialPayload = channelWindow())

        try {
            store.dispatch(SharedThreadIntent.JumpToMessage("m2", switchToTab = "chat"))
            advanceUntilIdle()
            val jumpNonce = store.state.value.unreadThreadJumpFocusNonce
            // A later link / search focus gets a new nonce and keeps the top-aligned placement.
            store.dispatch(SharedThreadIntent.FocusMessage("m1"))
            advanceUntilIdle()

            val payload = store.state.value.payload
            assertEquals("m1", payload.focusedMessageId)
            assertEquals(jumpNonce, store.state.value.unreadThreadJumpFocusNonce)
            assertEquals(
                LazyViewportAnchorPlacement.Focused,
                threadFocusedAnchorBasePlacement(payload.focusedMessageRequestNonce, store.state.value.unreadThreadJumpFocusNonce),
            )
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun repository() =
        ReplyThreadAccessConversationRepositoryRecorder(
            replyResult = DataResult.Content(replyThreadAccessSnapshot()),
        )

    private fun dataSource(repository: ReplyThreadAccessConversationRepositoryRecorder): SharedThreadDataSource {
        val sessionStore =
            InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1", selectedServerId = ServerIdValue))
        return SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, TestThreadEnvironmentId),
        )
    }

    private fun channelWindow(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-$ChannelIdValue",
            sourceThreadId = ChannelIdValue,
            streamChannelId = ChannelIdValue,
            status = "content",
            messages =
                listOf("m1", "m2", "m3").mapIndexed { index, id ->
                    RaftThreadMessageItem(id = id, authorId = "u1", authorName = "Artin", seq = 100L + index, body = id)
                },
            canLoadMoreBefore = true,
            canSend = true,
        )

    private fun followedRow(thread: String, parentChannel: String, parentMessage: String, unread: Int) =
        InboxItem(
            key = "thread:$thread",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(thread),
            channelName = "general",
            unreadCount = unread,
            threadChannelId = ChannelId(thread),
            parentChannelId = ChannelId(parentChannel),
            parentMessageId = MessageId(parentMessage),
        )

    private fun channelContext(vararg ids: String): ThreadSnapshot {
        val channelId = ChannelId(ChannelIdValue)
        return ThreadSnapshot(
            conversation =
                ConversationSummary(
                    threadId = ThreadId(ChannelIdValue),
                    channelId = channelId,
                    kind = ConversationKind.Channel,
                    name = "general",
                    joined = true,
                    permissions = ConversationPermissions(canRead = true, canSend = true),
                ),
            messages =
                ids.mapIndexed { index, id ->
                    Message(
                        id = MessageId(id),
                        threadId = ThreadId(ChannelIdValue),
                        channelId = channelId,
                        author =
                            MemberProfile(
                                id = MemberId("u1"),
                                displayName = "Artin",
                                handle = "artin",
                                kind = MemberKind.Human,
                                presence = Presence.Active,
                            ),
                        seq = 10L + index,
                        timestampLabel = "2026-10-20T12:00:00Z",
                        body = id,
                    )
                },
        )
    }

    private companion object {
        const val ChannelIdValue = "channel-1"
        const val ServerIdValue = "server-1"
    }
}
