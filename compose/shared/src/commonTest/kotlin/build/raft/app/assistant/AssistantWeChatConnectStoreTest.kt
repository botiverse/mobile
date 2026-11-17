package build.raft.app.assistant

import build.raft.app.data.RaftAssistantWeChatConnectApi
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatAttemptCookie
import build.raft.app.data.WeChatAttemptStatus
import build.raft.app.data.WeChatPollResult
import build.raft.app.data.WeChatRequestFailure
import build.raft.app.data.WeChatStartResult
import build.raft.app.data.weChatPollResultOf
import build.raft.app.data.weChatStartResultOf
import build.raft.app.model.ServerId
import build.raft.app.network.RAFT_KTOR_REQUEST_TIMEOUT_MILLIS
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

    private val noAnswer: WeChatPollResult =
        WeChatPollResult.Failed(WeChatRequestFailure(null, ScreenError("network", traceId = null)), cookieCleared = false)

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
    fun refusedForNowPollsAreTriedAgainAfterThreeSeconds() =
        runTest {
            for (retry in listOf(refused(429, "wechat_poll_in_flight"), refused(502, "wechat_upstream_unavailable"), noAnswer, refused(503, "http_503"))) {
                val api = ScriptedApi(this)
                val store = newStore(api)
                store.start()
                runCurrent()
                api.starts.single().complete(started("a1"))
                runCurrent()
                val firstAt = api.lastPoll().atMillis
                api.answerPoll(retry)
                runCurrent()
                advanceTimeBy(WECHAT_POLL_RETRY_MILLIS - 1)
                runCurrent()
                assertEquals(1, api.polls.size, "$retry: not before 3 s")
                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, api.polls.size, "$retry")
                assertEquals(WECHAT_POLL_RETRY_MILLIS, api.lastPoll().atMillis - firstAt)
                assertTrue(store.step.value is WeChatConnectStep.Qr, "$retry keeps the code")
                store.stop()
            }
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
    fun aRetriedCodeGoesAgainWithTheRetry() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            api.starts.single().complete(started("a1"))
            runCurrent()
            api.answerPoll(answered("need_verify_code"))
            runCurrent()
            store.submitCode("Q1")
            runCurrent()
            api.answerPoll(refused(429, "wechat_poll_in_flight"))
            runCurrent()
            advanceTimeBy(WECHAT_POLL_RETRY_MILLIS)
            runCurrent()
            assertEquals("Q1", api.lastPoll().verifyCode)
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
    fun finalAnswersAndRefusalsEndTheRunWithTheirReason() =
        runTest {
            val cases =
                listOf(
                    answered("expired", final = true) to WeChatConnectFailure.Expired,
                    answered("blocked", final = true) to WeChatConnectFailure.Blocked,
                    answered("already_bound", final = true) to WeChatConnectFailure.AlreadyBound,
                    refused(410, "wechat_attempt_used") to WeChatConnectFailure.Used,
                    refused(404, "wechat_attempt_not_found") to WeChatConnectFailure.Used,
                    refused(410, "wechat_attempt_expired") to WeChatConnectFailure.Expired,
                    refused(403, "wechat_attempt_unbound") to WeChatConnectFailure.Unbound,
                    refused(409, "wechat_account_in_use") to WeChatConnectFailure.InUse,
                    refused(502, "wechat_upstream_refused") to WeChatConnectFailure.Upstream,
                    refused(404, "wechat_unavailable") to WeChatConnectFailure.Unavailable,
                    refused(409, "assistant_not_found") to WeChatConnectFailure.Unavailable,
                    refused(418, "teapot") to WeChatConnectFailure.Other,
                )
            for ((answer, reason) in cases) {
                val api = ScriptedApi(this)
                val reads = mutableListOf<Unit>()
                val store = newStore(api, reads)
                store.start()
                runCurrent()
                api.starts.single().complete(started("a1"))
                runCurrent()
                api.answerPoll(answer)
                runCurrent()
                val failed = store.step.value as WeChatConnectStep.Failed
                assertEquals(reason, failed.reason, "$answer")
                if (answer is WeChatPollResult.Failed) assertEquals(TRACE, failed.error?.traceId, "$answer keeps its trace")
                assertNull(store.heldCookie, "$answer drops the cookie")
                assertEquals(if (reason == WeChatConnectFailure.Unavailable) 1 else 0, reads.size, "$answer")
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(1, api.polls.size, "$answer: no more polls")
            }
        }

    @Test
    fun startRefusalsMapAsWebAndAlreadyConnectedShowsTheConnection() =
        runTest {
            val cases =
                listOf(
                    Triple(429, "wechat_rate_limited", WeChatConnectStep.Failed(WeChatConnectFailure.RateLimited, ScreenError("wechat_rate_limited", traceId = TRACE))),
                    Triple(502, "wechat_upstream_unavailable", WeChatConnectStep.Failed(WeChatConnectFailure.Upstream, ScreenError("wechat_upstream_unavailable", traceId = TRACE))),
                    Triple(404, "wechat_unavailable", WeChatConnectStep.Failed(WeChatConnectFailure.Unavailable, ScreenError("wechat_unavailable", traceId = TRACE))),
                    Triple(409, "wechat_attempt_in_progress", WeChatConnectStep.Failed(WeChatConnectFailure.Other, ScreenError("wechat_attempt_in_progress", traceId = TRACE))),
                    Triple(409, "wechat_already_connected", WeChatConnectStep.Connected),
                )
            for ((status, code, expected) in cases) {
                val api = ScriptedApi(this)
                val store = newStore(api)
                store.start()
                runCurrent()
                api.starts.single().complete(weChatStartResultOf(status, """{"error":"x","code":"$code"}""", emptyMap(), TRACE))
                runCurrent()
                assertEquals(expected, store.step.value, code)
                assertTrue(api.polls.isEmpty())
            }
        }

    @Test
    fun aStartWithoutTheBindingCookieNeverPolls() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            api.starts.single().complete(
                weChatStartResultOf(201, """{"attemptId":"a1","qrContent":"q","expiresAt":"2026-11-17T08:05:00.000Z"}""", emptyMap(), null)
            )
            runCurrent()
            assertEquals(WeChatConnectStep.Failed(WeChatConnectFailure.Other, null), store.step.value)
            assertTrue(api.polls.isEmpty())
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

    @Test
    fun thePollRequestBudgetOutlastsTheServersLongPoll() {
        assertTrue(RAFT_KTOR_REQUEST_TIMEOUT_MILLIS >= WECHAT_POLL_MIN_REQUEST_TIMEOUT_MILLIS)
    }

    @Test
    fun aPollAnswerThatRemovesTheCookieWithoutEndingStopsPolling() =
        runTest {
            val api = ScriptedApi(this)
            val store = newStore(api)
            store.start()
            runCurrent()
            api.starts.single().complete(started("a1"))
            runCurrent()
            val cookie = api.polls.single().first
            api.answerPoll(
                weChatPollResultOf(
                    WeChatAttemptCookieFixture.of("a1", cookie.cookieHeader),
                    200,
                    """{"status":"wait"}""",
                    mapOf("Set-Cookie" to listOf("${cookie.cookieHeader.substringBefore('=')}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")),
                    null,
                )
            )
            runCurrent()
            advanceTimeBy(WECHAT_POLL_GAP_MILLIS)
            runCurrent()
            assertEquals(1, api.polls.size, "no poll without the cookie")
            assertEquals(WeChatConnectStep.Failed(WeChatConnectFailure.Other, null), store.step.value)
        }
}

/** A cookie for a hand-built poll answer, through the production start parser. */
private object WeChatAttemptCookieFixture {
    fun of(attemptId: String, header: String): WeChatAttemptCookie =
        (weChatStartResultOf(
            201,
            """{"attemptId":"$attemptId","qrContent":"q","expiresAt":"2026-11-17T08:05:00.000Z"}""",
            mapOf("Set-Cookie" to listOf("$header; Path=/; Max-Age=300")),
            null,
        ) as WeChatStartResult.Started).cookie!!
}

private const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"

/** 2026-11-17T08:00:00Z. */
private const val START_EPOCH_MILLIS = 1_794_902_400_000L
