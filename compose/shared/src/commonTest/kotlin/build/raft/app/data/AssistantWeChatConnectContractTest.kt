package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The WeChat connect wire contract on the server's own answers at botiverse/slock staging
 * 34511fe7e4a36d0d5c054f836be70821fa8b4608:
 * - `routes/assistantWeChat.ts` `POST /attempts` → `res.status(201).json({ attemptId, qrContent,
 *   expiresAt: started.expiresAt.toISOString() })` and `POST /attempts/:attemptId/poll` →
 *   `res.json({ status })`; errors `res.status(err.status).json({ error: err.message, code: err.code })`
 *   (messages copied from `services/wechatBridgeService.ts`).
 * - The cookie as `services/oauthBrowserBinding.ts` `serialize` writes it:
 *   `${name}=${encodeURIComponent(value)}; Path=${callbackPath}; Max-Age=${n}; HttpOnly; SameSite=Lax[; Secure]`,
 *   name `raft_wechat_bind_` + the state hash's first 16 hex digits; removal is the same with an
 *   empty value and `Max-Age=0`.
 */
class AssistantWeChatConnectContractTest {
    private val attemptId = "5f0c2b9e-3d4a-4e8f-9b1c-7a6d5e4f3c2b"
    private val path = "/api/servers/9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/assistant/wechat/attempts"
    private val startBody =
        """{"attemptId":"$attemptId","qrContent":"https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=0a1b2c3d&bot_type=3","expiresAt":"2026-11-17T08:05:00.000Z"}"""
    private val cookieName = "raft_wechat_bind_3fa1c2d4e5b6a798"
    private val cookieValue = "Zx9-Qm3_vT8kLp2Ws4Rt6Yu0Io1Pa5Sd7Fg9Hj2Kl4"
    private val setCookie = "$cookieName=$cookieValue; Path=$path; Max-Age=300; HttpOnly; SameSite=Lax; Secure"
    private val clearCookie = "$cookieName=; Path=$path; Max-Age=0; HttpOnly; SameSite=Lax; Secure"

    @Test
    fun theStartAnswerGivesTheAttemptAndItsOwnBindingCookie() {
        val started = weChatStartResultOf(201, startBody, mapOf("Set-Cookie" to listOf(setCookie)), null)
        started as WeChatStartResult.Started
        assertEquals(
            WeChatAttempt(attemptId, "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=0a1b2c3d&bot_type=3", "2026-11-17T08:05:00.000Z"),
            started.attempt,
        )
        val cookie = assertNotNull(started.cookie)
        assertEquals(attemptId, cookie.attemptId)
        assertEquals("$cookieName=$cookieValue", cookie.headerValue())
        // Never printable: a log line that names the cookie object does not carry its value.
        assertFalse(cookieValue in cookie.toString(), cookie.toString())
        assertFalse(cookieValue in weChatBindSetCookies(mapOf("set-cookie" to listOf(setCookie))).toString())
    }

    @Test
    fun onlyTheBindingCookieIsTakenWhateverElseTheAnswerSets() {
        val others = "AWSALB=abc123/def==; Expires=Mon, 24 Nov 2026 08:00:00 GMT; Path=/"
        val cases =
            listOf(
                // HTTP/2 lower-case name, the binding cookie among others in separate headers.
                mapOf("set-cookie" to listOf(others, setCookie, "session=nope; Path=/; HttpOnly")),
                // NSHTTPURLResponse.allHeaderFields folds several Set-Cookie into one value with ", ".
                mapOf("Set-Cookie" to listOf("$others, $setCookie, session=nope; Path=/")),
                mapOf("SET-COOKIE" to listOf(setCookie)),
            )
        for (headers in cases) {
            assertEquals("$cookieName=$cookieValue", weChatAttemptCookieOf(attemptId, headers)?.headerValue(), headers.toString())
        }
        assertNull(weChatAttemptCookieOf(attemptId, mapOf("Set-Cookie" to listOf(others))), "no binding cookie")
        assertNull(weChatAttemptCookieOf(attemptId, mapOf("Set-Cookie" to listOf(clearCookie))), "a removal is not a cookie")
        assertNull(weChatAttemptCookieOf(attemptId, mapOf("X-Set-Cookie" to listOf(setCookie))), "only Set-Cookie")
        assertNull(weChatAttemptCookieOf(attemptId, mapOf("Set-Cookie" to listOf("raft_wechat_binder=x; Path=/"))), "prefix is exact")
        // A 201 without the cookie still parses: the store refuses to poll it.
        val bare = weChatStartResultOf(201, startBody, emptyMap(), null) as WeChatStartResult.Started
        assertNull(bare.cookie)
    }

    @Test
    fun aFinalPollAnswerRemovesTheCookie() {
        val cookie = assertNotNull(weChatAttemptCookieOf(attemptId, mapOf("Set-Cookie" to listOf(setCookie))))
        val confirmed = weChatPollResultOf(cookie, 200, """{"status":"confirmed"}""", mapOf("Set-Cookie" to listOf(clearCookie)), null)
        assertEquals(WeChatPollResult.Answered(WeChatAttemptStatus.Confirmed, cookieCleared = true), confirmed)
        val waiting = weChatPollResultOf(cookie, 200, """{"status":"wait"}""", emptyMap(), null)
        assertEquals(WeChatPollResult.Answered(WeChatAttemptStatus.Wait, cookieCleared = false), waiting)
        // 410 used (WeChatBridgeError with endedStateHash) clears it too.
        val used =
            weChatPollResultOf(
                cookie,
                410,
                """{"error":"This QR code was already used. Start again.","code":"wechat_attempt_used"}""",
                mapOf("set-cookie" to listOf(clearCookie)),
                TRACE,
            ) as WeChatPollResult.Failed
        assertTrue(used.cookieCleared)
        assertEquals(WeChatRequestFailure(410, ScreenError(code = "wechat_attempt_used", traceId = TRACE)), used.failure)
        // Another attempt's removal is not this one's.
        val otherRemoval = "raft_wechat_bind_0000000000000000=; Path=$path; Max-Age=0; HttpOnly; SameSite=Lax"
        assertFalse(weChatAttemptCookieCleared(cookie, mapOf("Set-Cookie" to listOf(otherRemoval))))
    }

    @Test
    fun everyPollStatusTheServerSendsIsKnown() {
        val statuses = listOf("wait", "scanned", "need_verify_code", "confirmed", "expired", "blocked", "already_bound")
        assertEquals(statuses, WeChatAttemptStatus.entries.map { it.wire })
        for (status in statuses) {
            assertEquals(status, parseSharedWeChatPollStatus("""{"status":"$status"}""")?.wire)
        }
        assertNull(parseSharedWeChatPollStatus("""{"status":"something_new"}"""), "unknown: reported, tried again")
        val cookie = assertNotNull(weChatAttemptCookieOf(attemptId, mapOf("Set-Cookie" to listOf(setCookie))))
        val unreadable = weChatPollResultOf(cookie, 200, """{"status":"something_new"}""", emptyMap(), null) as WeChatPollResult.Failed
        assertEquals(WeChatRequestFailure(null, ScreenError(code = "invalid_response", traceId = null)), unreadable.failure)
    }

    @Test
    fun errorsKeepTheServerCodeHttpStatusAndTrace() {
        val cases =
            listOf(
                429 to """{"error":"Too many QR codes; wait a minute.","code":"wechat_rate_limited"}""",
                404 to """{"error":"WeChat is not available here.","code":"wechat_unavailable"}""",
                404 to """{"error":"You have no assistant in this server.","code":"assistant_not_found"}""",
                409 to """{"error":"WeChat is already connected. Disconnect it first.","code":"wechat_already_connected"}""",
                409 to """{"error":"A QR code is already being made. Try again.","code":"wechat_attempt_in_progress"}""",
                502 to """{"error":"WeChat could not be reached. Try again.","code":"wechat_upstream_unavailable"}""",
            )
        for ((status, body) in cases) {
            val failed = weChatStartResultOf(status, body, emptyMap(), TRACE) as WeChatStartResult.Failed
            assertEquals(status, failed.failure.httpStatus)
            assertEquals(TRACE, failed.failure.error.traceId)
            assertTrue(failed.failure.error.code in body, failed.failure.error.code)
        }
        // A 500 without a code (sendWeChatError's fallback), and no answer at all.
        val bare = weChatStartResultOf(500, """{"error":"Something went wrong with WeChat. Try again."}""", emptyMap(), TRACE) as WeChatStartResult.Failed
        assertEquals(WeChatRequestFailure(500, ScreenError(code = "wechat_start_failed_500", traceId = TRACE)), bare.failure)
        val none = weChatStartResultOf(0, null, emptyMap(), null) as WeChatStartResult.Failed
        assertNull(none.failure.httpStatus)
    }

    @Test
    fun theStartBodyRejectsGuessedWrappersAndAliases() {
        assertNull(parseSharedWeChatAttempt("""{"attempt":$startBody}"""))
        assertNull(parseSharedWeChatAttempt("""{"attempt_id":"$attemptId","qr_content":"x","expires_at":"2026-11-17T08:05:00.000Z"}"""))
        assertNull(parseSharedWeChatAttempt("""{"attemptId":"$attemptId","qrContent":"","expiresAt":"2026-11-17T08:05:00.000Z"}"""))
        assertNull(parseSharedWeChatAttempt("not json"))
        assertNull(parseSharedWeChatPollStatus("""{"data":{"status":"wait"}}"""))
        val invalid = weChatStartResultOf(201, """{"attempt":$startBody}""", mapOf("Set-Cookie" to listOf(setCookie)), null)
        assertEquals(WeChatStartResult.Failed(WeChatRequestFailure(null, ScreenError(code = "invalid_response", traceId = null))), invalid)
    }

    @Test
    fun thePollBodyIsEmptyOrCarriesTheVerifyCode() {
        val json = kotlinx.serialization.json.Json { explicitNulls = false }
        assertEquals("{}", json.encodeToString(WeChatPollRequestBody.serializer(), WeChatPollRequestBody(null)))
        assertEquals("""{"verifyCode":"Ab12"}""", json.encodeToString(WeChatPollRequestBody.serializer(), WeChatPollRequestBody("Ab12")))
        // wechatBridgeService: /^[0-9A-Za-z]{1,16}$/.
        assertTrue(WeChatVerifyCodePattern.matches("Ab12"))
        assertTrue(WeChatVerifyCodePattern.matches("0123456789abcdef"))
        assertFalse(WeChatVerifyCodePattern.matches("0123456789abcdefg"))
        assertFalse(WeChatVerifyCodePattern.matches("12 34"))
        assertFalse(WeChatVerifyCodePattern.matches(""))
    }
}

private const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
