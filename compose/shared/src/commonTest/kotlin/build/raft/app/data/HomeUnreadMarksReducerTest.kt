package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Home row @ mention and followed-thread marks under realtime and reads, mirroring the web
 * (botiverse/slock staging 259f9fe0b549cfd589ac37bce75e2e1c5ffec6d1: `store/socketBridge.ts`
 * `threadUpdated`, `store/threadStore.ts` `applyServerThreadUnread`, `store/messageStore.ts`
 * `clearUnread` / `applyAcceptedReadStateProjection`, `utils/coalescedRefresh.ts`).
 */
class HomeUnreadMarksReducerTest {
    private val me = MemberProfile(
        id = MemberId("user-1"),
        displayName = "Artin",
        handle = "artin@slock.ai",
        kind = MemberKind.Human,
        presence = Presence.Active,
    )
    private val cindy = me.copy(id = MemberId("user-2"), displayName = "Cindy", handle = "cindy@slock.ai")
    private val general = ChannelId("channel-general")
    private val threadA = ChannelId("thread-a")

    private fun home(
        channelUnread: Int = 0,
        mentionCount: Int = 0,
        threads: Map<String, HomeFollowedThreadUnread> =
            mapOf(threadA.value to HomeFollowedThreadUnread(general, unreadCount = 1, hasMention = false)),
    ) = HomeSnapshot(
        server = ServerSummary(ServerId("server-1"), "Slock", unreadCount = channelUnread),
        currentMember = me,
        channels = listOf(
            HomeChannelRow(
                threadId = ThreadId(general.value),
                channelId = general,
                name = "general",
                unreadCount = channelUnread,
                unreadCountKnown = true,
                mentionCount = mentionCount,
                mentionCountKnown = true,
            )
        ),
        directMessages = emptyList(),
        unreadTotal = channelUnread,
        followedThreadUnread = threads,
    )

    private fun message(id: String, author: MemberProfile, mentions: List<MessageMention> = emptyList()) =
        Message(
            id = MessageId(id),
            threadId = ThreadId(general.value),
            channelId = general,
            seq = 10,
            author = author,
            timestampLabel = "2026-10-21T08:00:00Z",
            body = "hi",
            mentions = mentions,
        )

    private fun threadReply(thread: ChannelId, sender: String, mentions: List<MessageMention> = emptyList()) =
        ThreadUpdated(
            parentMessageId = MessageId("parent-1"),
            threadChannelId = thread,
            replyCount = 2,
            lastReplyAt = "2026-10-21T08:01:00Z",
            participantIds = listOf(MemberId(sender)),
            latestReply =
                ReplyPreview(
                    messageId = "reply-2",
                    seq = 7,
                    content = "reply",
                    senderId = sender,
                    senderType = "user",
                    senderName = sender,
                    createdAt = "2026-10-21T08:01:00Z",
                ),
            latestReplyMentions = mentions,
        )

    private fun HomeSnapshot.general(): HomeChannelRow = channels.single()

    @Test
    fun anUnreadMessageThatMentionsTheUserLightsTheRowMention() {
        val mentionsMe = listOf(MessageMention("user", "user-1", "Artin"))
        val cases =
            listOf(
                // (message, active conversation, expected mentionCount)
                Triple(message("m-1", cindy, mentionsMe), null, 1),
                Triple(message("m-2", cindy, listOf(MessageMention("agent", "user-1"))), null, 0),
                Triple(message("m-3", cindy), null, 0),
                // Own message, or the conversation is open: not unread, so no mention.
                Triple(message("m-4", me, mentionsMe), null, 0),
                Triple(message("m-5", cindy, mentionsMe), general.value, 0),
            )
        for ((msg, active, expected) in cases) {
            val next = home().withRealtimeMessages(HomeRealtimeMessagePatch(listOf(msg), incrementUnread = true), active)
            assertEquals(expected, next.general().mentionCount, msg.id.value)
        }
    }

    @Test
    fun readingAConversationClearsItsMentionWithItsCount() {
        val read = home(channelUnread = 3, mentionCount = 1).withConversationUnread(general.value, 0)

        assertEquals(0, read.general().unreadCount)
        assertEquals(0, read.general().mentionCount)
        // Mark unread keeps a mention the row already had.
        val unread = home(channelUnread = 3, mentionCount = 1).withConversationUnread(general.value, 4)
        assertEquals(1, unread.general().mentionCount)
    }

    @Test
    fun readingAFollowedThreadDropsItFromTheParentMark() {
        val read = home().withConversationUnread(threadA.value, 0)

        assertEquals(emptyMap(), read.followedThreadUnread)
        assertEquals(null, read.parentThreadUnread(general))
    }

    @Test
    fun aThreadReplyBumpsAKnownThreadAndRefreshesForAnUnknownOne() {
        val snapshot = home()
        val mentionsMe = listOf(MessageMention("user", "user-1", "Artin"))

        assertEquals(HomeThreadReplyDecision.Bump, snapshot.decideThreadReply(threadReply(threadA, "user-2"), null))
        assertEquals(HomeThreadReplyDecision.Ignore, snapshot.decideThreadReply(threadReply(threadA, "user-1"), null))
        assertEquals(HomeThreadReplyDecision.Ignore, snapshot.decideThreadReply(threadReply(threadA, "user-2"), threadA.value))
        assertEquals(
            HomeThreadReplyDecision.Refresh,
            snapshot.decideThreadReply(threadReply(ChannelId("thread-new"), "user-2"), null)
        )
        // An own reply into an unknown thread is never unread for the user: no reload either.
        assertEquals(
            HomeThreadReplyDecision.Ignore,
            snapshot.decideThreadReply(threadReply(ChannelId("thread-new"), "user-1"), null)
        )

        val bumped = snapshot.withFollowedThreadReply(threadReply(threadA, "user-2", mentionsMe))
        assertEquals(HomeFollowedThreadUnread(general, unreadCount = 2, hasMention = true), bumped.followedThreadUnread[threadA.value])
        // Still one thread under the channel: the mark counts threads, not replies.
        assertEquals(HomeParentThreadUnread(count = 1, hasMention = true), bumped.parentThreadUnread(general))
    }

    @Test
    fun aReadStatePushLowersAThreadToTheServerCountAndNeverRaisesIt() {
        fun update(unread: Int?) =
            PerReceiverReadStateUpdate(
                serverId = ServerId("server-1"),
                memberId = me.id,
                channelId = threadA,
                version = 5,
                maxReadSeq = 7,
                unreadCount = unread,
            )
        val snapshot = home(threads = mapOf(threadA.value to HomeFollowedThreadUnread(general, 3, hasMention = true)))

        assertEquals(1, snapshot.withPerReceiverReadState(update(1)).followedThreadUnread[threadA.value]?.unreadCount)
        assertEquals(emptyMap(), snapshot.withPerReceiverReadState(update(0)).followedThreadUnread)
        assertEquals(snapshot.followedThreadUnread, snapshot.withPerReceiverReadState(update(9)).followedThreadUnread)
        assertEquals(snapshot.followedThreadUnread, snapshot.withPerReceiverReadState(update(null)).followedThreadUnread)
    }

    @Test
    fun aReloadedSummaryReplacesTheThreadsAndAnEqualOneIsANoOp() {
        val snapshot = home()

        assertSame(snapshot, snapshot.withFollowedThreadUnread(snapshot.followedThreadUnread))
        assertEquals(emptyMap(), snapshot.withFollowedThreadUnread(emptyMap()).followedThreadUnread)
    }

    @Test
    fun refreshRequestsInOneWindowRunOnceAndARequestDuringTheRunRunsOnceMore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var runs = 0
        lateinit var refresh: HomeCoalescedRefresh
        var requestDuringRun = true
        refresh =
            HomeCoalescedRefresh(scope = scope, delayMillis = 1_500L) {
                runs += 1
                if (requestDuringRun) {
                    requestDuringRun = false
                    refresh.request()
                }
            }

        repeat(5) { refresh.request() }
        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(0, runs, "nothing runs inside the window")
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, runs, "a burst in one window runs once")
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(2, runs, "a request during the run runs once more after its own window")
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, runs, "no further requests, no further runs")

        refresh.request()
        refresh.cancel()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, runs, "cancel drops a scheduled run")
        scope.cancel()
    }
}
