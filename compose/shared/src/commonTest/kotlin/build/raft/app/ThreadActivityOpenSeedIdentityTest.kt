package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.network.sync.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #80 (artin, 1.15.5 on Android): the rows a page opened from Activity shows before its load
 * answers. A human sender showed as a raw id ("when it's a person it shows the ID, not the name"),
 * and "@Jasper joined this channel." showed as a chat message from "system" with an "S" face.
 */
class ThreadActivityOpenSeedIdentityTest {
    @Test
    fun aSeedAuthorIsTheBestKnownNameNeverTheRawId() {
        // The member directory first, then Activity's own sender names, then what the row carried.
        assertEquals("Artin", activitySeedAuthorName("u1", mapOf("u1" to "Artin"), mapOf("u1" to "artin-old"), listOf("row name")))
        assertEquals("artin", activitySeedAuthorName("u1", emptyMap(), mapOf("u1" to "artin"), listOf("row name")))
        assertEquals("row name", activitySeedAuthorName("u1", emptyMap(), emptyMap(), listOf(null, " ", "row name")))
        // A carried name that is only the id is no name; with nothing known the name is blank (the row
        // shows its neutral "Unknown"), never the id.
        assertEquals("", activitySeedAuthorName("u1", emptyMap(), emptyMap(), listOf("u1", null)))
        assertEquals("", activitySeedAuthorName("u1", mapOf("u1" to "u1"), emptyMap(), emptyList()))
    }

    @Test
    fun aHumanSenderTheRowDoesNotNameIsNamedFromTheDirectoryOrActivity() {
        // The server's thread rows carry no sender name for people or agents
        // (channelService `latestActivitySenderName`: external authors only), and done rows none at all.
        val thread = threadRow(senderId = "u-jasper", senderKind = MemberKind.Human)
        val fromDirectory =
            seed(source(thread), replyRoute(), memberNames = mapOf("u-jasper" to "Jasper")).single()
        assertEquals("Jasper", fromDirectory.authorName)
        assertEquals("human", fromDirectory.authorKind)

        val fromActivity = seed(source(thread, senderNames = mapOf("u-jasper" to "Jasper")), replyRoute()).single()
        assertEquals("Jasper", fromActivity.authorName)

        val unknown = seed(source(thread), replyRoute()).single()
        assertEquals("", unknown.authorName, "nothing known: blank, not u-jasper")

        // Agents resolve the same way.
        val agent = seed(source(threadRow(senderId = "agent-1", senderKind = MemberKind.Agent)), replyRoute(), memberNames = mapOf("agent-1" to "Friday"))
        assertEquals("Friday", agent.single().authorName)
    }

    @Test
    fun aReplyWindowAuthorPrefersTheDirectoryName() {
        val thread = threadRow(senderId = "u-jasper", senderKind = MemberKind.Human).copy(
            latestActivityMessageId = null,
            latestReplies = listOf(reply("r1", senderId = "u-jasper", senderType = "user", name = "User")),
        )
        val seeded = seed(source(thread), replyRoute(), memberNames = mapOf("u-jasper" to "Jasper")).single()
        assertEquals("Jasper", seeded.authorName)
    }

    @Test
    fun aMembershipNoteIsASystemRowLikeTheWeb() {
        // The server stores "@Jasper joined this channel." as sender type `user`, sender id `system`,
        // message type `system`; the Activity row keeps only the sender type and id.
        val row =
            InboxItem(
                key = "channel-proj-mobile",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "proj-mobile",
                lastMessageId = MessageId("sys-1"),
                lastMessagePreview = "@Jasper joined this channel.",
                lastMessageAt = "2026-10-24T07:13:00Z",
                lastMessageSenderId = "system",
                lastMessageSenderName = null,
                lastMessageSenderKind = MemberKind.Human,
            )
        val seeded = seed(source(row), channelRoute()).single()
        assertTrue(seeded.isSystemMessage(), "rendered by the system row, not as a chat message")
        assertEquals("system", seeded.authorKind)
        assertEquals("System", seeded.authorName)
        assertFalse(seeded.isOwn)
        assertEquals(null, seeded.avatarWireValue)

        // A system-kind sender too, and a reply window entry from the system sender.
        val kindRow = row.copy(lastMessageSenderId = "system-x", lastMessageSenderKind = MemberKind.System)
        assertTrue(seed(source(kindRow), channelRoute()).single().isSystemMessage())
        val systemReply =
            threadRow(senderId = "u-jasper", senderKind = MemberKind.Human).copy(
                latestActivityMessageId = null,
                latestReplies = listOf(reply("r-sys", senderId = "system", senderType = "user", name = "User")),
            )
        val replySeed = seed(source(systemReply), replyRoute()).single()
        assertTrue(replySeed.isSystemMessage())
        assertEquals("System", replySeed.authorName)

        // Positive control: a person's message stays a chat row.
        val human = row.copy(lastMessageSenderId = "u-jasper", lastMessageSenderName = "Jasper")
        val chat = seed(source(human), channelRoute()).single()
        assertFalse(chat.isSystemMessage())
        assertEquals("Jasper", chat.authorName)
    }

    @Test
    fun aStandInWhoseLoadIsRunningSaysOlderMessagesAreLoading() {
        val loading = threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true, mainLoadFailed = false)
        assertEquals(Res.string.thread_state_loading_older.desc(), loading.label)
        assertTrue(loading.loading)
        // Only a failed load makes "not available offline" true.
        val offline = threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true, mainLoadFailed = true)
        assertEquals(Res.string.thread_offline_older_unavailable.desc(), offline.label)
        assertFalse(offline.loading)
        // A canonical frame, and a stand-in that reaches the start, are unchanged.
        assertFalse(threadOlderMessagesStatusSpec(canLoadMoreBefore = false).loading)
        assertEquals(
            Res.string.thread_state_beginning_conversation.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true, durableReachedOldest = true).label,
        )
        // The seeded first frame itself (the Activity row's message, its load running).
        val seeded =
            channelRoute().copy(status = "loading").withActivityOpenSeed(
                listOf(RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "hi", timestampLabel = "2026-10-24T07:13:00Z"))
            )
        assertTrue(threadOlderMessagesStatusSpec(seeded).loading)
        assertFalse(threadOlderMessagesStatusSpec(seeded.copy(mainLoadFailed = true)).loading)
    }

    @Test
    fun aRetractedLatestMessageSeedsAsRetracted() {
        // RFC 077 PR 2b: the row's `latestRetracted` (messageService.ts `withLatestMessageFacts`) names
        // the thread row's latest reply; its reply-window entry and its row copy both seed retracted,
        // another reply in the window does not.
        val thread =
            threadRow(senderId = "u-jasper", senderKind = MemberKind.Human).copy(
                latestRetracted = true,
                latestReplies = listOf(reply("r8", "u-jasper", "user", "Jasper"), reply("r9", "u-jasper", "user", "Jasper")),
            )
        val seeded = seed(source(thread), replyRoute()).associateBy { it.id }
        assertTrue(seeded.getValue("r9").isRetracted, "the latest reply")
        assertFalse(seeded.getValue("r8").isRetracted, "another reply")
        assertFalse(seed(source(thread.copy(latestRetracted = false)), replyRoute()).any { it.isRetracted })

        // A channel row names its `lastMessageId`.
        val channel =
            InboxItem(
                key = "channel-proj-mobile",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "proj-mobile",
                lastMessageId = MessageId("m1"),
                lastMessagePreview = "This message was retracted by its sender.",
                lastMessageAt = "2026-10-24T07:13:00Z",
                lastMessageSenderId = "u-jasper",
                lastMessageSenderKind = MemberKind.Human,
                latestRetracted = true,
            )
        assertTrue(seed(source(channel), channelRoute()).single().isRetracted)
    }

    private fun seed(
        source: ThreadActivityOpenSeedSource,
        route: RaftThreadScreenPayload,
        memberNames: Map<String, String> = emptyMap(),
    ): List<RaftThreadMessageItem> =
        threadActivityOpenSeedMessages(
            source,
            route.resolveThreadRouteIdentity(),
            serverId = "server-1",
            principalId = "u-a",
            memberNames = memberNames,
        )

    private fun source(vararg items: InboxItem, senderNames: Map<String, String> = emptyMap()) =
        ThreadActivityOpenSeedSource(ownerServerId = "server-1", ownerUserId = "u-a", items = items.toList(), senderNames = senderNames)

    private fun threadRow(senderId: String, senderKind: MemberKind) =
        InboxItem(
            key = "thread-row",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-1"),
            channelName = "proj-mobile",
            threadChannelId = ChannelId("thread-1"),
            parentChannelId = ChannelId("channel-1"),
            parentMessageId = MessageId("p1"),
            latestActivityMessageId = MessageId("r9"),
            latestActivityPreview = "on it",
            latestActivityAt = "2026-10-24T07:20:00Z",
            latestActivitySenderId = senderId,
            latestActivitySenderName = null,
            latestActivitySenderKind = senderKind,
        )

    private fun reply(id: String, senderId: String, senderType: String, name: String) =
        ReplyPreview(
            messageId = id,
            seq = 3,
            content = "reply $id",
            senderId = senderId,
            senderType = senderType,
            senderName = name,
            createdAt = "2026-10-24T07:21:00Z",
        )

    private fun channelRoute() =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "proj-mobile",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            sourceThreadId = "channel-1",
            status = "loading",
        )

    private fun replyRoute() =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-reply-thread-1",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "p1",
            status = "loading",
        )
}
