package build.raft.app.assistant

import build.raft.app.data.RaftAssistantWeChatConnectApi
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatAttemptCookie
import build.raft.app.data.WeChatAttemptStatus
import build.raft.app.data.WeChatPollResult
import build.raft.app.data.WeChatRequestFailure
import build.raft.app.data.WeChatStartResult
import build.raft.app.data.weChatStartResultOf
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The connect flow's timing and answers, as web useWeChatConnect.ts runs them (botiverse/slock
 * staging 34511fe7e4a36d0d5c054f836be70821fa8b4608): a poll a second after each answer, 3 s after a
 * refused-for-now one, one poll at a time, the code running out at `expiresAt`, `scanned` sticky,
 * `need_verify_code` waiting for the code. The binding cookie goes only with its own attempt's polls
 * and is gone once the run ends. Answers are built by the production parsers from the server's shapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantWeChatConnectStoreTest {
    private class Poll(val attemptId: String, val cookieHeader: String, val verifyCode: String?, val atMillis: Long)

    /** Each start / poll waits for the test to answer it, so time and order are the test's. */
    private class ScriptedApi(private val scope: TestScope) : RaftAssistantWeChatConnectApi {
        val starts = mutableListOf<CompletableDeferred<WeChatStartResult>>()
        val polls = mutableListOf<Pair<Poll, CompletableDeferred<WeChatPollResult>>>()
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun startWeChatAttempt(serverId: ServerId): WeChatStartResult {
            val answer = CompletableDeferred<WeChatStartResult>()
            starts += answer
            return answer.await()
        }

        override suspend fun pollWeChatAttempt(serverId: ServerId, cookie: WeChatAttemptCookie, verifyCode: String?): WeChatPollResult {
            val answer = CompletableDeferred<WeChatPollResult>()
            polls += Poll(cookie.attemptId, cookie.headerValue(), verifyCode, scope.currentTime) to answer
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                return answer.await()
            } finally {
                inFlight -= 1
            }
        }

        fun lastPoll(): Poll = polls.last().first

        fun answerPoll(result: WeChatPollResult) = polls.last().second.complete(result)
    }

    private fun started(id: String, expiresAt: String = "2026-11-17T08:05:00.000Z"): WeChatStartResult =
        weChatStartResultOf(
            201,
            """{"attemptId":"$id","qrContent":"https://liteapp.weixin.qq.com/q/$id","expiresAt":"$expiresAt"}""",
            mapOf("Set-Cookie" to listOf("raft_wechat_bind_${id.take(4)}000000000000=state-$id; Path=/api/servers/s1/assistant/wechat/attempts; Max-Age=300; HttpOnly; SameSite=Lax")),
            null,
        )

    private fun answered(status: String, final: Boolean = false): WeChatPollResult =
        WeChatPollResult.Answered(WeChatAttemptStatus.fromWire(status)!!, cookieCleared = final)

    private fun refused(code: Int, errorCode: String): WeChatPollResult =
        WeChatPollResult.Failed(WeChatRequestFailure(code, ScreenError(errorCode, traceId = TRACE)), cookieCleared = false)

    /** 08:00:00Z at virtual time 0: an attempt expiring 08:05:00Z has 300 s left. */
    private fun TestScope.newStore(api: ScriptedApi, statusReads: MutableList<Unit> = mutableListOf()) =
        AssistantWeChatConnectStore(
            api = api,
            serverId = "s1",
            scope = backgroundScope,
            onStatusChanged = { statusReads += Unit },
            nowEpochMillis = { START_EPOCH_MILLIS + currentTime },
            logger = {},
        )

    @Test
    fun pollsASecondAfterEachAnswerAndKeepsScanned() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            assertEquals(WeChatConnectStep.Starting, store.step.value)
            api.starts.single().complete(started("a1"))
            runCurrent()
            assertEquals(1, api.polls.size, "first poll at once")
            assertEquals("raft_wechat_bind_a1000000000000=state-a1", api.lastPoll().cookieHeader)
            assertNull(api.lastPoll().verifyCode)

            api.answerPoll(answered("wait"))
            runCurrent()
            assertFalse((store.step.value as WeChatConnectStep.Qr).scanned)
            advanceTimeBy(WECHAT_POLL_GAP_MILLIS - 1)
            runCurrent()
            assertEquals(1, api.polls.size, "not before the gap")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, api.polls.size)

            api.answerPoll(answered("scanned"))
            runCurrent()
            assertTrue((store.step.value as WeChatConnectStep.Qr).scanned)
            advanceTimeBy(WECHAT_POLL_GAP_MILLIS)
            runCurrent()
            // A long poll that timed out after the scan answers `wait`: scanned stays.
            api.answerPoll(answered("wait"))
            runCurrent()
            assertTrue((store.step.value as WeChatConnectStep.Qr).scanned)
            assertEquals(1, api.maxInFlight, "one poll at a time")
        }

    @Test
    fun theVerifyCodeStopsPollingUntilSentAndAWrongOneIsSaid() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            api.starts.single().complete(started("a1"))
            runCurrent()
            api.answerPoll(answered("need_verify_code"))
            runCurrent()
            val asked = store.step.value as WeChatConnectStep.Verify
            assertNull(asked.error)
            assertFalse(asked.checking)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, api.polls.size, "no polling while WeChat waits for the code")

            store.submitCode("  12 34 ")
            assertEquals(WeChatVerifyError.Malformed, (store.step.value as WeChatConnectStep.Verify).error)
            assertEquals(1, api.polls.size, "a malformed code is never sent")

            store.submitCode(" 9z8Y ")
            runCurrent()
            assertTrue((store.step.value as WeChatConnectStep.Verify).checking)
            assertEquals("9z8Y", api.lastPoll().verifyCode)
            store.submitCode("9z8Y")
            runCurrent()
            assertEquals(2, api.polls.size, "a second submit while checking does nothing")

            // Asked again right after a code: that code was wrong.
            api.answerPoll(answered("need_verify_code"))
            runCurrent()
            assertEquals(WeChatVerifyError.Wrong, (store.step.value as WeChatConnectStep.Verify).error)

            // The server says malformed (400 invalid_verify_code): said, no polling.
            store.submitCode("ABC")
            runCurrent()
            api.answerPoll(refused(400, "invalid_verify_code"))
            runCurrent()
            assertEquals(WeChatVerifyError.Malformed, (store.step.value as WeChatConnectStep.Verify).error)

            // A right code: polling goes on (scanned, since a code was sent), then connected.
            store.submitCode("ABC")
            runCurrent()
            api.answerPoll(answered("wait"))
            runCurrent()
            assertTrue((store.step.value as WeChatConnectStep.Qr).scanned)
            advanceTimeBy(WECHAT_POLL_GAP_MILLIS)
            runCurrent()
            assertNull(api.lastPoll().verifyCode, "the code goes once")
        }

    @Test
    fun confirmedReadsTheStatusAndDropsTheCookie() =
        runTest {
            val api = ScriptedApi(this)
            val reads = mutableListOf<Unit>()
            val store = newStore(api, reads)
            store.start()
            runCurrent()
            api.starts.single().complete(started("a1"))
            runCurrent()
            assertEquals("a1", store.heldCookie?.attemptId)
            api.answerPoll(answered("confirmed", final = true))
            runCurrent()
            assertEquals(WeChatConnectStep.Connected, store.step.value)
            assertEquals(1, reads.size, "status read again")
            assertNull(store.heldCookie)
            assertFalse(store.running)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, api.polls.size, "nothing after the final answer")
        }

    @Test
    fun theCodeRunsOutAtExpiresAtEvenWhileAPollIsOpen() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            // 08:00:30Z: 30 s left.
            api.starts.single().complete(started("a1", expiresAt = "2026-11-17T08:00:30.000Z"))
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertTrue(store.step.value is WeChatConnectStep.Qr)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(WeChatConnectStep.Failed(WeChatConnectFailure.Expired, null), store.step.value)
            assertNull(store.heldCookie)
            // The open poll's late answer reaches nothing.
            api.answerPoll(answered("confirmed", final = true))
            runCurrent()
            assertEquals(WeChatConnectStep.Failed(WeChatConnectFailure.Expired, null), store.step.value)
        }

    @Test
    fun stopAndANewStartEndTheRunAndTheCookieGoesOnlyWithItsOwnAttempt() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            api.starts[0].complete(started("a1"))
            runCurrent()
            val firstPoll = api.polls.last().second

            // Get New Code: a new attempt, its own cookie; the old poll's answer is ignored.
            store.start()
            runCurrent()
            assertNull(store.heldCookie, "the old cookie is dropped at once")
            assertEquals(WeChatConnectStep.Starting, store.step.value)
            api.starts[1].complete(started("b2"))
            runCurrent()
            firstPoll.complete(answered("confirmed", final = true))
            runCurrent()
            assertTrue(store.step.value is WeChatConnectStep.Qr)
            val b2 = api.lastPoll()
            assertEquals("b2", b2.attemptId)
            assertEquals("raft_wechat_bind_b2000000000000=state-b2", b2.cookieHeader)
            assertTrue(api.polls.all { (poll, _) -> poll.cookieHeader.endsWith("state-${poll.attemptId}") })

            // Cancel / leaving the screen: the poll is abandoned, nothing more is sent.
            store.stop()
            assertNull(store.heldCookie)
            assertFalse(store.running)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(2, api.polls.size)
            assertEquals(0, api.inFlight, "the open poll was cancelled")
        }
}

private const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"

/** 2026-11-17T08:00:00Z. */
private const val START_EPOCH_MILLIS = 1_794_902_400_000L
