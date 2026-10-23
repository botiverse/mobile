package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 10-23: "Activity clearly has the data; tapping into the page shows none of it." The thread
 * store's first frame stands in with what the Activity row already holds for exactly this
 * conversation (identity-matched; never through the route), and with nothing known it is the loading
 * state, never the empty state. The canonical load replaces the seed.
 */
class SharedThreadStoreActivityOpenSeedTest {
    @Test
    fun theActivityRowMessageIsTheFirstFrameAndTheCanonicalLoadReplacesIt() = runBlocking {
        // runBlocking, not virtual time: the canonical page resumes off the test dispatcher (the
        // existing gated-fetch store tests wait the same way), so the replacement is awaited with a
        // bounded wait that fails with the state it saw.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val api = SharedThreadStoreTest.FakeThreadApi()
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        // The row's latest message is the same message the canonical page returns ("m1").
        val source = source(channelRow(channelId = "channel-1", messageId = "m1", preview = "seen in Activity"))
        try {
            val store = SharedThreadStore(dataSource = dataSource(api, source), scope = scope)
            store.dispatch(SharedThreadIntent.Start(channelRoute()))
            withTimeout(5_000) { api.channelMessagesStarted!!.await() }

            val first = store.state.value.payload
            assertEquals("content", first.status, "the row's message stands in while the load runs")
            assertEquals(listOf("m1" to "seen in Activity"), first.messages.map { it.id to it.body })
            assertTrue(first.durableLastKnown, "a stand-in: no paging authority")
            assertFalse(first.canLoadMoreBefore)

            api.releaseChannelMessages!!.complete(Unit)
            withTimeoutOrNull(5_000) {
                while (store.state.value.payload.durableLastKnown) delay(10)
            } ?: error("waited for the canonical page to replace the seed, state=${store.state.value.payload.messages}")

            val canonical = store.state.value.payload
            assertEquals("content", canonical.status)
            assertEquals(listOf("m1" to "network body"), canonical.messages.map { it.id to it.body }, "replaced, not doubled")
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun withNothingKnownTheFirstFrameIsLoadingNotEmpty() = runTest {
        // Nothing in memory, nothing on disk, and Activity rows that are not this conversation: one of
        // another channel, one of this channel but another server, one of this channel but another user.
        val cases =
            listOf(
                source(channelRow(channelId = "channel-2", messageId = "x1", preview = "other channel")),
                source(channelRow(channelId = "channel-1", messageId = "x2", preview = "other server"), serverId = "server-2"),
                source(channelRow(channelId = "channel-1", messageId = "x3", preview = "other user"), userId = "u-b"),
            )
        for (source in cases) {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
            val api = SharedThreadStoreTest.FakeThreadApi()
            api.releaseChannelMessages = CompletableDeferred()
            try {
                val store = SharedThreadStore(dataSource = dataSource(api, source), scope = scope)
                store.dispatch(SharedThreadIntent.Start(channelRoute()))
                advanceUntilIdle()

                val payload = store.state.value.payload
                assertEquals("loading", payload.status, "${source.items.single().lastMessagePreview}: loading, not empty")
                assertTrue(payload.messages.isEmpty(), payload.messages.toString())
            } finally {
                scopeJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun everyRowKindSeedsItsOwnConversationOnly() {
        val thread =
            InboxItem(
                key = "thread-row",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                threadChannelId = ChannelId("thread-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("p1"),
                latestActivityMessageId = MessageId("r2"),
                latestActivityPreview = "second reply",
                latestActivityAt = "2026-10-23T10:02:00Z",
                latestActivitySenderId = "agent-1",
                latestActivitySenderName = "Friday",
                latestActivitySenderKind = MemberKind.Agent,
                latestReplies =
                    listOf(
                        reply("r1", seq = 11, body = "first reply", at = "2026-10-23T10:01:00Z"),
                        reply("r2", seq = 12, body = "second reply", at = "2026-10-23T10:02:00Z"),
                    ),
            )
        val dm = channelRow(channelId = "dm-1", messageId = "d1", preview = "hi from the agent", kind = InboxItemKind.DirectMessage)
        val mention = channelRow(channelId = "channel-3", messageId = "n1", preview = "@u-a look", kind = InboxItemKind.MentionAction)
        val source = source(thread, dm, mention, channelRow(channelId = "channel-1", messageId = "c1", preview = "in the channel"))

        fun seed(route: RaftThreadScreenPayload) =
            threadActivityOpenSeedMessages(source, route.resolveThreadRouteIdentity(), serverId = "server-1", principalId = "u-a")
                .map { it.id to it.seq }

        // The reply window wins over the row-derived copy of the same reply (it has the seq).
        assertEquals(listOf("r1" to 11L, "r2" to 12L), seed(replyRoute("thread-1")))
        assertEquals(listOf("d1" to null), seed(channelRoute("dm-1", surfaceKind = RaftThreadSurfaceKind.DirectMessage)))
        assertEquals(listOf("n1" to null), seed(channelRoute("channel-3")))
        // The parent channel gets its own row, not the thread's replies.
        assertEquals(listOf("c1" to null), seed(channelRoute("channel-1")))
        assertEquals(emptyList(), seed(replyRoute("thread-9")))
    }

    @Test
    fun aSeedFillsOnlyWhatTheStandInLacks() {
        val onDisk = message("m5", seq = 5, at = "2026-10-23T09:00:00Z")
        val standIn =
            RaftThreadScreenPayload(status = "content", durableLastKnown = true, messages = listOf(onDisk))
        val newer = message("m9", seq = null, at = "2026-10-23T10:00:00Z")
        val older = message("m2", seq = null, at = "2026-10-23T08:00:00Z")
        val sameAsDisk = onDisk.copy(body = "row preview")

        val seeded = standIn.withActivityOpenSeed(listOf(older, sameAsDisk, newer))
        assertEquals(listOf("m5", "m9"), seeded.messages.map { it.id })
        assertEquals(onDisk.body, seeded.messages.first().body, "a message already on the frame is kept")

        // A durable empty stand-in (task #1137) is a guess too: the seed replaces it.
        val emptyStandIn = RaftThreadScreenPayload(status = "empty", durableLastKnown = true)
        val fromEmpty = emptyStandIn.withActivityOpenSeed(listOf(newer))
        assertEquals("content", fromEmpty.status)
        assertEquals(listOf("m9"), fromEmpty.messages.map { it.id })
        assertTrue(fromEmpty.durableLastKnown)

        assertEquals(standIn, standIn.withActivityOpenSeed(emptyList()))
    }

    @Test
    fun anEmptyStandInShowsLoadingUntilTheFirstLoadAnswers() {
        assertTrue(threadEmptyStandInAwaitsFirstLoad(status = "empty", durableLastKnown = true, mainLoadFailed = false))
        // Failed offline: the stand-in with its offline tip, as task #1137 shows it.
        assertFalse(threadEmptyStandInAwaitsFirstLoad(status = "empty", durableLastKnown = true, mainLoadFailed = true))
        // The canonical answer was empty.
        assertFalse(threadEmptyStandInAwaitsFirstLoad(status = "empty", durableLastKnown = false, mainLoadFailed = false))
        assertFalse(threadEmptyStandInAwaitsFirstLoad(status = "content", durableLastKnown = true, mainLoadFailed = false))
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi,
        source: ThreadActivityOpenSeedSource,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(ownerA.session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { ownerA },
            activityOpenSeedSource = { source },
        )

    private fun source(
        vararg items: InboxItem,
        serverId: String = "server-1",
        userId: String = "u-a",
    ) = ThreadActivityOpenSeedSource(ownerServerId = serverId, ownerUserId = userId, items = items.toList())

    private fun channelRow(
        channelId: String,
        messageId: String,
        preview: String,
        kind: InboxItemKind = InboxItemKind.Channel,
    ) = InboxItem(
        key = "$kind-$channelId",
        kind = kind,
        channelId = ChannelId(channelId),
        channelName = channelId,
        lastMessageId = MessageId(messageId),
        lastMessagePreview = preview,
        lastMessageAt = "2026-10-23T10:00:00Z",
        lastMessageSenderId = "u1",
        lastMessageSenderName = "Artin",
        lastMessageSenderKind = MemberKind.Human,
    )

    private fun reply(id: String, seq: Long, body: String, at: String) =
        ReplyPreview(
            messageId = id,
            seq = seq,
            content = body,
            senderId = "agent-1",
            senderType = "agent",
            senderName = "Friday",
            createdAt = at,
        )

    private fun message(id: String, seq: Long?, at: String) =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            seq = seq,
            body = "body $id",
            timestampLabel = at,
            createdAtEpochMillis = build.raft.app.model.parseRaftTimestampEpochMillisOrNull(at),
        )

    private fun channelRoute(
        channelId: String = "channel-1",
        surfaceKind: String = RaftThreadSurfaceKind.Channel,
    ) = RaftThreadScreenPayload(
        instanceId = "shared-shell-thread-$channelId",
        title = channelId,
        surfaceKind = surfaceKind,
        sourceThreadId = channelId,
        status = "loading",
    )

    private fun replyRoute(threadId: String) =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-reply-$threadId",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = threadId,
            streamChannelId = threadId,
            parentChannelId = "channel-1",
            parentMessageId = "p1",
            status = "loading",
        )

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "u-a", email = "u-a@example.test", displayName = "Owner A"),
                    accessToken = "access-u-a",
                    refreshToken = "refresh-u-a",
                    selectedServerId = "server-1",
                ),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
        )
}
