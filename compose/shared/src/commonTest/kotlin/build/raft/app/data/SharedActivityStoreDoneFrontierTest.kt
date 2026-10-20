package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Done names the row's storage-space frontier exactly like web
 * `inboxStore.markDone` (botiverse/slock 1bb70cbeb, inboxStore.ts:1417-1564;
 * server matrix routes/channels.ts:1497 and 2014). Row payloads are copied
 * from `packages/web/tests/doneFrontierCaller.behavior.test.ts` @ 1bb70cbeb.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreDoneFrontierTest {

    @Test
    fun parserDistinguishesAnAbsentFrontierFromAPresentNullOne() {
        val parsed =
            parseSharedInboxSnapshot(
                inboxBody(
                    channelRow("channel-exact", latestActivitySeq = "9007199254740993", doneFrontierSeq = "\"9007199254740993\""),
                    channelRow("channel-incident", latestActivitySeq = "11429659", doneFrontierSeq = "\"11426997\""),
                    channelRow("channel-null-frontier", latestActivitySeq = "11429659", doneFrontierSeq = "null"),
                    channelRow("channel-legacy", latestActivitySeq = "11429659", doneFrontierSeq = null),
                    threadRow("thread-incident", doneFrontierSeq = "\"11426997\"")
                )
            ) ?: error("inbox payload did not parse")

        assertEquals(
            listOf(
                ActivityDoneRequestFrontier.Storage("9007199254740993"),
                ActivityDoneRequestFrontier.Storage("11426997"),
                ActivityDoneRequestFrontier.Invalid,
                ActivityDoneRequestFrontier.Legacy,
                ActivityDoneRequestFrontier.Storage("11426997")
            ),
            parsed.items.map(InboxItem::activityDoneRequestFrontier)
        )
        assertEquals("11429659", parsed.items[1].latestActivitySeq, "display seq stays separate from the guard frontier")
    }

    @Test
    fun doneBodiesSendTheStorageFrontierOrOmitEverySeqField() = runTest {
        val network = DoneRecordingNetworkClient()
        val auth = InMemoryRaftAuthSessionStore(sampleSession())
        val inbox = RealInboxRepository(auth, network)
        val conversations = RealConversationRepository(auth, network)

        inbox.markDone(ChannelId("channel-exact"), InboxDoneFrontier.Storage("9007199254740993"))
        inbox.markDone(ChannelId("channel-legacy"), InboxDoneFrontier.Legacy)
        conversations.markThreadDone(ChannelId("thread-incident"), InboxDoneFrontier.Storage("11426997"))
        conversations.markThreadDone(ChannelId("thread-legacy"), InboxDoneFrontier.Legacy)

        assertEquals(
            listOf(
                "channels/inbox/done" to mapOf("channelId" to "channel-exact", "throughActivitySeq" to "9007199254740993", "frontierSpace" to "storage"),
                "channels/inbox/done" to mapOf("channelId" to "channel-legacy"),
                "channels/threads/done" to mapOf("threadChannelId" to "thread-incident", "throughActivitySeq" to "11426997", "frontierSpace" to "storage"),
                "channels/threads/done" to mapOf("threadChannelId" to "thread-legacy")
            ),
            network.posts
        )
    }

    @Test
    fun storeSendsTheHeldRowsFrontierAndRefreshesAfterTheConfirmedDone() = runTest {
        // Channel and thread rows; the UI may hand in a stale copy of the row,
        // the request still names the frontier of the row the store holds.
        val channel = activityItem("channel-1", unreadCount = 2).withFrontier("41")
        val thread = threadItem("thread-1", unreadCount = 1).withFrontier("11426997")
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(channel, thread))))
        val conversations = FakeConversationRepository()
        val store = frontierStore(inbox, conversations)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(channel.copy(doneFrontierSeq = "1")))
        store.dispatch(SharedActivityIntent.MarkDone(thread))
        runCurrent()

        assertEquals(listOf<InboxDoneFrontier>(InboxDoneFrontier.Storage("41")), inbox.doneFrontiers)
        assertEquals(listOf<InboxDoneFrontier>(InboxDoneFrontier.Storage("11426997")), conversations.doneThreadFrontiers)
        assertTrue(inbox.initialLoadCount >= 2, "a confirmed Done takes an authoritative refresh, loads=${inbox.initialLoadCount}")
        assertEquals(emptyList(), store.state.value.items.map { it.key }, "the hold keeps both rows hidden")
    }

    @Test
    fun oldServerRowsUseTheLegacyBodyAndInvalidFrontiersRefreshWithoutWriting() = runTest {
        val legacy = activityItem("channel-legacy", unreadCount = 1)
        val nullFrontier = activityItem("channel-null", unreadCount = 2).copy(doneFrontierSeqPresent = true)
        val zeroFrontier = activityItem("channel-zero").withFrontier("0")
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(legacy, nullFrontier, zeroFrontier))))
        val store = frontierStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(legacy))
        runCurrent()
        assertEquals(listOf<InboxDoneFrontier>(InboxDoneFrontier.Legacy), inbox.doneFrontiers)

        val loadsBefore = inbox.initialLoadCount
        listOf(nullFrontier, zeroFrontier).forEach { refused ->
            store.dispatch(SharedActivityIntent.MarkDone(refused))
            assertTrue(store.state.value.items.any { it.key == refused.key }, "no optimistic removal for ${refused.key}")
            runCurrent()
        }
        assertEquals(listOf(ChannelId("channel-legacy")), inbox.doneChannelIds, "a refused Done never reaches the server")
        assertEquals(loadsBefore + 2, inbox.initialLoadCount, "each refused Done refreshes once")
        assertEquals(listOf("channel:channel-null", "channel:channel-zero"), store.state.value.items.map { it.key })
        assertEquals(2, store.state.value.totalUnreadCount)
    }

    @Test
    fun aStorageFrontierDoneDoesNotHideAReplyItNeverCovered() = runTest {
        // Web never moves the Done marker on a socket patch: a Done names the
        // served frontier (10), so the patched reply (11) stays active and the
        // row shows again as soon as the server serves it.
        val served = threadItem("thread-1").copy(readStateLatestActivitySeq = "10", replyCount = 2).withFrontier("10")
        val afterDone = served.copy(readStateLatestActivitySeq = "11", replyCount = 3, doneFrontierSeq = "11")
        val inbox =
            FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(served)), snapshot(listOf(afterDone))))
        val conversations = FakeConversationRepository()
        val store = frontierStore(inbox, conversations)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("reply-3", channelId = "thread-1", seq = 11L))))
        assertEquals("10", store.state.value.items.single().readStateLatestActivitySeq)

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        assertEquals(listOf<InboxDoneFrontier>(InboxDoneFrontier.Storage("10")), conversations.doneThreadFrontiers)
        assertEquals(listOf("thread:thread-1"), store.state.value.items.map { it.key })
        assertEquals("11", store.state.value.items.single().readStateLatestActivitySeq)
    }

    @Test
    fun unfollowedThreadRowsCanBeMarkedDoneLikeWeb() = runTest {
        // filter=all carries unfollowed, not-Done threads inline
        // (routes/channels.ts:1385 includeUnfollowedThreads); web lets them be Done.
        val unfollowed =
            threadItem("thread-unfollowed").copy(isFollowing = false, unfollowedAt = "2026-10-20T00:00:00.000Z").withFrontier("88")
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(unfollowed))))
        val conversations = FakeConversationRepository()
        val store = frontierStore(inbox, conversations)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        assertEquals(listOf(ChannelId("thread-unfollowed")), conversations.doneThreadChannelIds)
        assertEquals(listOf<InboxDoneFrontier>(InboxDoneFrontier.Storage("88")), conversations.doneThreadFrontiers)
        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
    }

    private fun TestScope.frontierStore(
        inbox: InboxRepository,
        conversations: FakeConversationRepository = FakeConversationRepository()
    ): SharedActivityStore =
        activityStore(
            scope = backgroundScope,
            inboxRepository = inbox,
            conversationRepository = conversations,
            nowMillis = { testScheduler.currentTime }
        )

    private fun InboxItem.withFrontier(seq: String): InboxItem =
        copy(doneFrontierSeq = seq, doneFrontierSeqPresent = true, readStateLatestActivitySeq = readStateLatestActivitySeq ?: seq)

    private fun inboxBody(vararg rows: String): String =
        """{"items":[${rows.joinToString(",")}],"hasMore":false,"totalCount":${rows.size},"totalUnreadCount":0,"activeUnreadCount":0}"""

    /** `channelItem` in doneFrontierCaller.behavior.test.ts; null [doneFrontierSeq] = key deleted (old server). */
    private fun channelRow(channelId: String, latestActivitySeq: String, doneFrontierSeq: String?): String =
        """
        {"kind":"channel","channelId":"$channelId","channelName":"general","channelType":"channel",
         "lastMessageId":"message-$channelId","latestActivitySeq":"$latestActivitySeq",
         ${doneFrontierSeq?.let { "\"doneFrontierSeq\":$it," }.orEmpty()}
         "firstUnreadMessageId":"message-$channelId","firstMentionMessageId":null,
         "lastMessageAt":"2026-07-30T00:00:00.000Z","lastMessagePreview":"hi","lastMessageSenderType":"user",
         "lastMessageSenderId":"user-2","lastMessageSenderName":"peer","unreadCount":2,"hasMention":false}
        """.trimIndent()

    private fun threadRow(threadChannelId: String, doneFrontierSeq: String): String =
        """
        {"kind":"thread","threadChannelId":"$threadChannelId","parentMessageId":"parent-$threadChannelId",
         "parentChannelId":"channel-1","parentChannelName":"general","parentChannelType":"channel",
         "parentMessagePreview":"hi","parentMessageSenderType":"user","parentMessageSenderId":"user-1",
         "latestActivitySeq":"11429659","doneFrontierSeq":$doneFrontierSeq,"replyCount":7,
         "lastReplyAt":"2026-07-30T00:00:00.000Z","unreadCount":1,"taskNumber":null,"taskStatus":null,
         "taskClaimedByName":null,"latestActivityPreview":"reply","latestActivitySenderType":"user",
         "latestActivitySenderId":"user-2","latestActivityMessageId":"reply-$threadChannelId",
         "firstUnreadMessageId":"reply-$threadChannelId","firstMentionMessageId":null,
         "lastActivityAt":"2026-07-30T00:00:00.000Z","hasMention":false}
        """.trimIndent()

    /** Records POST paths with their decoded top-level string fields. */
    private class DoneRecordingNetworkClient : RaftNetworkClient {
        val posts = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val body = Json.parseToJsonElement(bodyText.orEmpty()).jsonObject
            posts += path.trim('/') to body.mapValues { (_, value) -> value.jsonPrimitive.content }
            assertFalse(body.values.any { it.toString() == "null" }, "absent frontier fields are omitted, never null")
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }
    }
}
