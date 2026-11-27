package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftStaleReason
import build.raft.app.data.RaftStaleRecoveryMaxDelayMillis
import build.raft.app.data.ScreenError
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
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadJumpFailure
import build.raft.app.thread.ThreadJumpLoadedRange
import build.raft.app.thread.ThreadUnreadThreadJump
import build.raft.app.thread.ThreadUnreadThreadParent
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import build.raft.app.thread.projectThreadUnreadThreadJump
import build.raft.app.thread.threadJumpParentCandidates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    /**
     * Task #164: while the followed-threads list is overloaded (`503 rw_overloaded`) or a remembered
     * list may predate the thread, the ids it could not resolve are asked again on the shared
     * recovery backoff (and by the next trigger); a fresh answer stops it. Any other failure stays
     * final for the page (above). Virtual time only: the recovery keeps re-arming while overloaded.
     */
    @Test
    fun idsTheOverloadedListCouldNotResolveAreAskedAgainUntilAFreshAnswer() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        repository.unreadFollowedResult =
            DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, serverCode = "rw_overloaded"))
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())

        try {
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-old")))
            runCurrent()
            assertEquals(1, repository.unreadFollowedRequests.size)
            assertEquals(emptyMap(), store.state.value.unreadThreadParents)

            // Still behind: a remembered list without the thread is not final either.
            repository.unreadFollowedResult = followedList(staleReason = RaftStaleReason.RwOverloaded)
            // The first two waits are 5 s each, ±20 %: one step is at most 6 s, two at least 8 s.
            advanceTimeBy(OneStepMaxMillis)
            runCurrent()
            assertEquals(2, repository.unreadFollowedRequests.size, "one recovery read per backoff step")

            repository.unreadFollowedResult =
                followedList(followedRow("t-old", parentChannel = ChannelIdValue, parentMessage = "p-old", unread = 3))
            advanceTimeBy(OneStepMaxMillis)
            runCurrent()
            assertEquals(3, repository.unreadFollowedRequests.size)
            assertEquals(setOf("t-old"), store.state.value.unreadThreadParents.keys)

            advanceTimeBy(10 * RaftStaleRecoveryMaxDelayMillis)
            runCurrent()
            assertEquals(3, repository.unreadFollowedRequests.size, "a fresh answer stops recovery")
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    /**
     * artin (phone): the unread-thread pill worked once. A thread whose parent was loaded was never
     * looked up; the jump to another parent replaced the window, so that thread still counted but had
     * no target and the pill went away. The jump now keeps the loaded parents, and three taps in a
     * row walk both unread threads and wrap, the pill never missing between them.
     */
    @Test
    fun repeatedPillJumpsKeepEveryUnreadThreadReachable() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        // Only the thread whose parent was not loaded was looked up.
        repository.unreadFollowedResult =
            followedList(followedRow("t-old", parentChannel = ChannelIdValue, parentMessage = "p-old", unread = 2))
        val store =
            SharedThreadStore(
                dataSource = dataSource(repository),
                scope = scope,
                initialPayload = channelWindow(threads = mapOf("m2" to "t-a")),
            )
        val unread = setOf("t-old", "t-a")
        fun pillWith(inView: String): ThreadUnreadThreadJump? {
            val state = store.state.value
            val messages = state.payload.messages
            val index = messages.indexOfFirst { it.id == inView }
            check(index >= 0) { "$inView is not loaded: ${messages.map { it.id }}" }
            return projectThreadUnreadThreadJump(
                unreadThreadIds = unread,
                messages = threadJumpParentCandidates(messages, emptyMap()),
                visibleMessageIndexes = index..index,
                dismissedAtCount = null,
                unloadedParents = state.unreadThreadParents,
                loadedRange = ThreadJumpLoadedRange(messages.lastOrNull()?.createdAtEpochMillis, state.payload.canLoadMoreAfter),
            )
        }
        fun tap(pill: ThreadUnreadThreadJump?, context: List<String>, threads: Map<String, String>) {
            assertNotNull(pill, "the pill shows before this tap")
            repository.parentResult = DataResult.Content(channelContext(*context.toTypedArray(), threads = threads))
            store.dispatch(SharedThreadIntent.JumpToMessage(pill.targetMessageId, switchToTab = "chat"))
            advanceUntilIdle()
            assertEquals(pill.targetMessageId, store.state.value.payload.focusedMessageId)
        }

        try {
            store.dispatch(SharedThreadIntent.ResolveUnreadThreadParents(setOf("t-old")))
            advanceUntilIdle()

            // Reader at the bottom (m3): the older parent goes first.
            val first = pillWith(inView = "m3")
            assertEquals("t-old", first?.targetThreadChannelId)
            tap(first, context = listOf("p-old", "p-old-next"), threads = mapOf("p-old" to "t-old"))

            // On p-old: t-a (its parent m2 no longer loaded) is still a target.
            val second = pillWith(inView = "p-old")
            assertEquals("t-a" to "m2", second?.targetThreadChannelId to second?.targetMessageId)
            assertEquals(2, second?.count)
            tap(second, context = listOf("m1", "m2", "m3"), threads = mapOf("m2" to "t-a"))

            // On m2: back to t-old, and on to t-a again.
            val third = pillWith(inView = "m2")
            assertEquals("t-old" to "p-old", third?.targetThreadChannelId to third?.targetMessageId)
            tap(third, context = listOf("p-old", "p-old-next"), threads = mapOf("p-old" to "t-old"))
            assertEquals("t-a", pillWith(inView = "p-old")?.targetThreadChannelId)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun followedList(vararg rows: InboxItem, staleReason: RaftStaleReason? = null): DataResult<ThreadListSnapshot> =
        DataResult.Content(
            ThreadListSnapshot(
                server = ServerSummary(id = ServerId(ServerIdValue), name = "Slock"),
                threads = rows.toList(),
                staleReason = staleReason,
            )
        )

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

    /**
     * Web #11358: the pill is busy from the tap until the parent's messages land, and a second tap
     * meanwhile starts no second jump; once they land (or the load fails) it can be pressed again.
     */
    @Test
    fun aJumpThatLoadsItsParentIsBusyUntilItLandsAndIgnoresAnotherTap() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        val release = CompletableDeferred<Unit>()
        repository.releaseParent = release
        repository.parentResult = DataResult.Content(channelContext("p-old", "p-old-next"))
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())

        try {
            assertEquals(false, store.state.value.unreadThreadJumpLoading)
            store.dispatch(SharedThreadIntent.JumpToMessage("p-old", switchToTab = "chat"))
            advanceUntilIdle()
            assertEquals(true, store.state.value.unreadThreadJumpLoading, "busy while the parent loads")

            store.dispatch(SharedThreadIntent.JumpToMessage("p-other", switchToTab = "chat"))
            advanceUntilIdle()
            assertEquals(listOf(MessageId("p-old")), repository.parentRequests.map { it.first }, "the second tap starts nothing")

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(false, store.state.value.unreadThreadJumpLoading)
            assertEquals("p-old", store.state.value.payload.focusedMessageId)

            // A failed load ends the busy state too.
            repository.parentResult = DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
            store.dispatch(SharedThreadIntent.JumpToMessage("p-far", switchToTab = "chat"))
            advanceUntilIdle()
            assertEquals(false, store.state.value.unreadThreadJumpLoading)
            assertEquals(2, repository.parentRequests.size)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    /**
     * A jump that is not the pill's (the thread timeline, a search match, a message link, a file's
     * original message): the page shows the load (the header's refreshing state), the same jump
     * tapped again meanwhile starts no second load (it used to cancel the first and start over),
     * and a load that fails is reported once, for the page's error toast and its retry.
     */
    @Test
    fun aMessageJumpTappedAgainWhileItLoadsStartsNoSecondLoadAndReportsItsFailure() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository = repository()
        val release = CompletableDeferred<Unit>()
        repository.releaseParent = release
        repository.parentResult = DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        val store = SharedThreadStore(dataSource = dataSource(repository), scope = scope, initialPayload = channelWindow())
        val failures = mutableListOf<ThreadJumpFailure>()
        val collector = scope.launch { store.jumpFailures.collect { failures += it } }

        try {
            store.dispatch(SharedThreadIntent.JumpToMessage("p-old", switchToTab = "chat", unreadThreadPill = false))
            advanceUntilIdle()
            assertEquals(true, store.state.value.payload.backgroundRefreshing, "the page shows the load")

            store.dispatch(SharedThreadIntent.JumpToMessage("p-old", switchToTab = "chat", unreadThreadPill = false))
            advanceUntilIdle()
            assertEquals(listOf(MessageId("p-old")), repository.parentRequests.map { it.first }, "the repeated tap starts nothing")

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(false, store.state.value.payload.backgroundRefreshing)
            assertEquals(listOf(ThreadJumpFailure(messageId = "p-old", switchToTab = "chat", unreadThreadPill = false)), failures)

            // Not stuck: the retry (the toast's action) loads it.
            repository.parentResult = DataResult.Content(channelContext("p-old", "p-old-next"))
            store.dispatch(SharedThreadIntent.JumpToMessage("p-old", switchToTab = "chat", unreadThreadPill = false))
            advanceUntilIdle()
            assertEquals(2, repository.parentRequests.size)
            assertEquals("p-old", store.state.value.payload.focusedMessageId)
            assertEquals(listOf("p-old", "p-old-next"), store.state.value.payload.messages.map { it.id })
            assertEquals(1, failures.size)
        } finally {
            collector.cancel()
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

    private fun channelWindow(threads: Map<String, String> = emptyMap()): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-$ChannelIdValue",
            sourceThreadId = ChannelIdValue,
            streamChannelId = ChannelIdValue,
            status = "content",
            messages =
                listOf("m1", "m2", "m3").mapIndexed { index, id ->
                    RaftThreadMessageItem(
                        id = id,
                        authorId = "u1",
                        authorName = "Artin",
                        seq = 100L + index,
                        body = id,
                        threadChannelId = threads[id],
                    )
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

    private fun channelContext(vararg ids: String, threads: Map<String, String> = emptyMap()): ThreadSnapshot {
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
                        threadChannelId = threads[id]?.let(::ChannelId),
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
        const val OneStepMaxMillis = 6_000L
        const val ServerIdValue = "server-1"
    }
}
