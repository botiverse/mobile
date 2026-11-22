package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #proj-mobile:#274 (web botiverse/slock #16101, tests/permalinkPreviewOlder.behavior.test.tsx): a
 * quote of a message behind the Free plan's history window asked `messages/context` again every
 * time its row came on screen and got the same 404 `history_limited` (one iPhone: 16 and 23 times a
 * day for one message). The answer is now kept under the server's plan and the preview is answered
 * from it. The refusal is the upstream body of [messageContextHistoryLimitedUpstreamBody].
 */
class RaftQuotedPermalinkHistoryLimitedMemoTest {
    private val free = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-a", plan = "free")
    private val channelId = "11111111-1111-4111-8111-111111111111"
    private val messageId = "22222222-2222-4222-8222-222222222222"

    private fun historyLimited(): ThreadApiResult<SharedMessagesPayload> =
        ThreadApiResult.Failure(code = 404, errorBodyText = messageContextHistoryLimitedUpstreamBody)

    @Test
    fun aHistoryLimitedAnswerUnderFreeIsShownFromMemoryWithoutAskingAgain() = runTest {
        val memo = RaftQuotedPermalinkHistoryLimitedMemo()
        var asked = 0
        val fetch: suspend () -> ThreadApiResult<SharedMessagesPayload> = {
            asked += 1
            historyLimited()
        }
        repeat(3) {
            val read = assertIs<RaftQuotedPermalinkContextRead.HistoryLimited>(memo.read(free, channelId, messageId, fetch))
            // The card can still say whose and when.
            assertEquals("Juno", read.target.senderName)
            assertEquals("33333333-3333-4333-8333-333333333333", read.target.senderId)
            assertEquals("2020-01-02T00:00:00.578Z", read.target.createdAt)
        }
        assertEquals(1, asked)
    }

    @Test
    fun onlyA404HistoryLimitedAnswerUnderFreeIsKept() = runTest {
        val memo = RaftQuotedPermalinkHistoryLimitedMemo()
        val notKept: List<Pair<RaftQuotedPermalinkViewer, ThreadApiResult<SharedMessagesPayload>>> =
            listOf(
                // Another plan: the server has not seen an upgrade yet.
                free.copy(plan = "pro") to historyLimited(),
                // A server whose plan is not known yet.
                free.copy(plan = null) to historyLimited(),
                // The plain not found (a missing message, or one this person may not read).
                free to ThreadApiResult.Failure(code = 404, errorBodyText = """{"error":"Message not found"}"""),
                // The same body on another status.
                free to ThreadApiResult.Failure(code = 403, errorBodyText = messageContextHistoryLimitedUpstreamBody),
            )
        for ((viewer, answer) in notKept) {
            var asked = 0
            val fetch: suspend () -> ThreadApiResult<SharedMessagesPayload> = {
                asked += 1
                answer
            }
            memo.read(viewer, channelId, messageId, fetch)
            memo.read(viewer, channelId, messageId, fetch)
            assertEquals(2, asked, "asked again: plan=${viewer.plan} answer=$answer")
            assertNull(memo.answer(viewer, channelId, messageId))
        }
    }

    @Test
    fun aPlanChangeOrAnotherPersonAsksAgain() = runTest {
        val memo = RaftQuotedPermalinkHistoryLimitedMemo()
        var asked = 0
        val fetch: suspend () -> ThreadApiResult<SharedMessagesPayload> = {
            asked += 1
            historyLimited()
        }
        memo.read(free, channelId, messageId, fetch)
        assertEquals(1, asked)
        // An upgrade: the plan is part of the key, so the preview asks again.
        memo.read(free.copy(plan = "pro"), channelId, messageId, fetch)
        assertEquals(2, asked)
        // Another person signed in on this device asks for themselves.
        memo.read(free.copy(userId = "user-b"), channelId, messageId, fetch)
        assertEquals(3, asked)
        // The first person, still on Free, is answered from memory.
        assertIs<RaftQuotedPermalinkContextRead.HistoryLimited>(memo.read(free, channelId, messageId, fetch))
        assertEquals(3, asked)
    }

    @Test
    fun keepsAtMost500AnswersDroppingTheOldestFirst() {
        val memo = RaftQuotedPermalinkHistoryLimitedMemo()
        val target = assertNotNull(parseMessageContextHiddenTarget(messageContextHistoryLimitedUpstreamBody))
        repeat(501) { index -> memo.remember(free, channelId, "message-$index", target) }
        assertNull(memo.answer(free, channelId, "message-0"))
        assertNotNull(memo.answer(free, channelId, "message-1"))
        assertNotNull(memo.answer(free, channelId, "message-500"))
    }

    @Test
    fun aKeptAnswerSurvivesAnAppRestart() = runTest {
        val store = InMemoryRaftKeyValueStore()
        var asked = 0
        val fetch: suspend () -> ThreadApiResult<SharedMessagesPayload> = {
            asked += 1
            historyLimited()
        }
        RaftQuotedPermalinkHistoryLimitedMemo(store = { store }).read(free, channelId, messageId, fetch)
        // A new process reads the same store.
        val read = RaftQuotedPermalinkHistoryLimitedMemo(store = { store }).read(free, channelId, messageId, fetch)
        assertEquals(1, asked)
        assertEquals("Juno", assertIs<RaftQuotedPermalinkContextRead.HistoryLimited>(read).target.senderName)
    }
}
