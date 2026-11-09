package build.raft.app.data

import build.raft.app.model.ThreadId
import build.raft.app.resources.*
import build.raft.app.ThreadHistoryPaywallScope
import build.raft.app.ThreadHistoryPaywallTitle
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.threadHistoryPaywallCount
import build.raft.app.threadHistoryPaywallScope
import build.raft.app.threadHistoryPaywallTitle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Free plan's history window on a message page (botiverse/slock staging e99c01c4df,
 * `packages/server/src/routes/messages.ts`, web `store/hiddenHistoryStore.ts` and
 * `tests/historyPaywall.behavior.test.tsx`, whose page this copies).
 */
class HiddenHistoryTest {
    @BeforeTest
    @AfterTest
    fun clearStore() {
        // One process for every test: never leak a channel's window into another test.
        RaftHiddenHistoryStore.clear()
    }

    private val page =
        """
        {"messages":[],"historyLimited":true,"threadSummariesByParentMessageId":{},
         "hiddenHistory":{"hiddenCount":214,"hiddenCountCapped":false,"latest":[
           {"id":"m-1","senderType":"user","senderId":"user-m-1","senderName":"Lin Mo","senderAvatarUrl":null,
            "senderGravatarHash":null,"createdAt":"2026-09-12T08:00:00.000Z","preview":"The final design is in Figma, please re"},
           {"id":"m-2","senderType":"agent","senderId":"agent-2","senderName":"Bugen","senderAvatarUrl":"pixel:random:bugen",
            "senderGravatarHash":null,"createdAt":"2026-09-12T09:00:00.000Z","preview":null},
           {"id":"bad","senderType":"robot","senderId":"x","createdAt":"2026-09-12T09:00:00.000Z"}
         ]}}
        """.trimIndent()

    @Test
    fun aChannelPageCarriesWhatTheWindowHides() {
        val payload = parseSharedChannelMessages(page, ThreadId("channel-1"))
        val hidden = payload.hiddenHistory!!
        assertEquals(true, payload.historyLimited)
        assertEquals(214, hidden.hiddenCount)
        assertEquals(false, hidden.hiddenCountCapped)
        assertEquals(false, hidden.parentHidden)
        // A row with an unknown sender type is dropped; the others keep their order (oldest first).
        assertEquals(listOf("m-1", "m-2"), hidden.latest.map { it.id })
        assertEquals("Lin Mo", hidden.latest[0].senderName)
        assertNull(hidden.latest[1].preview)
        assertEquals("pixel:random:bugen", hidden.latest[1].senderAvatarUrl)
    }

    @Test
    fun aPageWithoutAUsableCountCarriesNothing() {
        assertNull(parseSharedChannelMessages("""{"messages":[],"historyLimited":true}""", ThreadId("c")).hiddenHistory)
        assertNull(parseHiddenHistory(kotlinx.serialization.json.Json.parseToJsonElement("""{"hiddenCount":-1}""")))
        assertNull(parseHiddenHistory(kotlinx.serialization.json.Json.parseToJsonElement("""{"hiddenCount":"3"}""")))
        assertEquals(0, parseHiddenHistory(kotlinx.serialization.json.Json.parseToJsonElement("""{"hiddenCount":0,"parentHidden":true}"""))?.latest?.size)
    }

    @Test
    fun theStoreKeepsALimitedPagesAnswerUntilAnUnlimitedPageClearsIt() {
        val hidden = HiddenHistory(hiddenCount = 6, hiddenCountCapped = false, parentHidden = false, latest = emptyList())
        RaftHiddenHistoryStore.record("channel-1", historyLimited = true, hiddenHistory = hidden)
        assertEquals(hidden, RaftHiddenHistoryStore.byChannel.value["channel-1"])
        // A limited page that did not reach the window's start leaves the answer in place.
        RaftHiddenHistoryStore.record("channel-1", historyLimited = true, hiddenHistory = null)
        assertEquals(hidden, RaftHiddenHistoryStore.byChannel.value["channel-1"])
        // A page the plan no longer cuts clears it (the server upgraded).
        RaftHiddenHistoryStore.record("channel-1", historyLimited = false, hiddenHistory = null)
        assertNull(RaftHiddenHistoryStore.byChannel.value["channel-1"])
    }

    @Test
    fun theTitleNamesWhereAndHowMany() {
        fun hidden(count: Int, capped: Boolean = false, parent: Boolean = false) = HiddenHistory(count, capped, parent, emptyList())
        val channel = ThreadHistoryPaywallScope.Channel
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_messages_title_other, 214),
            threadHistoryPaywallTitle(channel, hidden(214), allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_messages_title_capped, 1000),
            threadHistoryPaywallTitle(channel, hidden(1000, capped = true), allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_messages_title_uncounted),
            threadHistoryPaywallTitle(channel, null, allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_channel_all_title_other, 13),
            threadHistoryPaywallTitle(channel, hidden(13), allHidden = true),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_dm_all_title_one, 1),
            threadHistoryPaywallTitle(ThreadHistoryPaywallScope.Dm, hidden(1), allHidden = true),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_task_all_title_other, 4),
            threadHistoryPaywallTitle(ThreadHistoryPaywallScope.Task, hidden(4), allHidden = true),
        )
        val thread = ThreadHistoryPaywallScope.Thread
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_replies_title_one, 1),
            threadHistoryPaywallTitle(thread, hidden(1), allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_thread_parent_only_title),
            threadHistoryPaywallTitle(thread, hidden(0, parent = true), allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_thread_parent_title_capped, 1000),
            threadHistoryPaywallTitle(thread, hidden(1000, capped = true, parent = true), allHidden = false),
        )
        assertEquals(
            ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_thread_parent_all_title_other, 3),
            threadHistoryPaywallTitle(thread, hidden(3, parent = true), allHidden = true),
        )
    }

    @Test
    fun scopeAndCount() {
        assertEquals(ThreadHistoryPaywallScope.Thread, threadHistoryPaywallScope(RaftThreadSurfaceKind.ReplyThread, null))
        assertEquals(ThreadHistoryPaywallScope.Task, threadHistoryPaywallScope(RaftThreadSurfaceKind.Channel, "task"))
        assertEquals(ThreadHistoryPaywallScope.Dm, threadHistoryPaywallScope(RaftThreadSurfaceKind.DirectMessage, null))
        assertEquals(ThreadHistoryPaywallScope.Channel, threadHistoryPaywallScope(RaftThreadSurfaceKind.Channel, "channel"))
        assertEquals("6", threadHistoryPaywallCount(6))
        assertEquals("1,000", threadHistoryPaywallCount(1000))
        assertEquals("12,345", threadHistoryPaywallCount(12345))
    }
}
