package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.BodyWriterUploadProgressTracker
import build.raft.app.network.KtorRaftHttpExecutor
import build.raft.app.network.RaftKtorClientProvider
import build.raft.app.network.RaftNetworkRequestLifetimeCoordinator
import build.raft.app.network.RaftRecentFailedTraceId
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.configureRaftKtorClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The WeChat binding cookie never reaches a log line (CTO condition, batch 3): a start whose 201
 * sets `raft_wechat_bind_*` and a poll that carries it as `Cookie:` go through the production
 * stack — [RaftAssistantWeChatConnectNetworkApi] → [SharedRaftNetworkClient] (auth middleware,
 * scheduler, `endpoint …` lines) → [KtorRaftHttpExecutor] (`slock_http_execute_start/finish`,
 * header diagnostics) — and every line any of them logs (what the diagnostics ring and crash /
 * trace reports are fed from) is checked for the cookie's value. The same run proves the cookie
 * reaches the wire unchanged and the response's `Set-Cookie` reaches the parser.
 */
class AssistantWeChatCookieLogRedactionTest {
    private val cookieName = "raft_wechat_bind_3fa1c2d4e5b6a798"
    private val cookieValue = "Zx9-Qm3_vT8kLp2Ws4Rt6Yu0Io1Pa5Sd7Fg9Hj2Kl4"
    private val attemptId = "5f0c2b9e-3d4a-4e8f-9b1c-7a6d5e4f3c2b"
    private val path = "/api/servers/server-1/assistant/wechat/attempts"

    private class Stack(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) {
        val logs = mutableListOf<String>()
        val requests = mutableListOf<HttpRequestData>()
        private val client =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                }
            ) { configureRaftKtorClient() }
        private val executor =
            KtorRaftHttpExecutor(
                warningLogger = { logs += it },
                clients = RaftKtorClientProvider { _, _ -> client },
                requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
                transportFacts = { null },
                engineLabel = "ktor_test",
                uploadProgress = BodyWriterUploadProgressTracker,
                failedTraceIds = RaftRecentFailedTraceId(),
            )
        val network =
            SharedRaftNetworkClient(
                sessionStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                            accessToken = "access-token",
                            refreshToken = "refresh-token",
                            selectedServerId = "server-1",
                            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
                        )
                    ),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                warningLogger = { logs += it },
                criticalWarningLogger = { logs += it },
                requestScheduler = RaftRequestScheduler(logger = { logs += it }),
                executor = executor,
            )
        val api = RaftAssistantWeChatConnectNetworkApi(network)
    }

    private fun setCookie(value: String, maxAge: Int) =
        "$cookieName=$value; Path=$path; Max-Age=$maxAge; HttpOnly; SameSite=Lax; Secure"

    @Test
    fun theBindingCookieReachesTheWireButNoLogLine() = runBlocking {
        val stack =
            Stack { request ->
                val target = request.url.encodedPath
                when {
                    target.endsWith("/assistant/wechat/attempts") ->
                        respond(
                            """{"attemptId":"$attemptId","qrContent":"https://liteapp.weixin.qq.com/q/x","expiresAt":"2026-11-17T08:05:00.000Z"}""",
                            HttpStatusCode.Created,
                            headersOf(
                                "Set-Cookie" to listOf(setCookie(cookieValue, 300)),
                                "Content-Type" to listOf("application/json"),
                                "X-Trace-Id" to listOf("4bf92f3577b34da6a3ce929d0e0e4736"),
                            ),
                        )
                    target.endsWith("/attempts/$attemptId/poll") && request.headers["Cookie"] == "$cookieName=$cookieValue" ->
                        respond(
                            """{"status":"confirmed"}""",
                            HttpStatusCode.OK,
                            headersOf("Set-Cookie" to listOf(setCookie("", 0)), "Content-Type" to listOf("application/json")),
                        )
                    else ->
                        respond(
                            """{"error":"Finish connecting WeChat in the browser you started in.","code":"wechat_attempt_unbound"}""",
                            HttpStatusCode.Forbidden,
                            headersOf("Set-Cookie" to listOf(setCookie(cookieValue, 300)), "Content-Type" to listOf("application/json")),
                        )
                }
            }

        val started = stack.api.startWeChatAttempt(ServerId("server-1")) as WeChatStartResult.Started
        val cookie = started.cookie!!
        val poll = stack.api.pollWeChatAttempt(ServerId("server-1"), cookie, verifyCode = "Ab12")

        assertEquals(WeChatPollResult.Answered(WeChatAttemptStatus.Confirmed, cookieCleared = true), poll)
        val pollRequest = stack.requests.last()
        assertEquals("$cookieName=$cookieValue", pollRequest.headers["Cookie"], "the Cookie header reaches the engine unchanged")
        assertEquals("Bearer access-token", pollRequest.headers["Authorization"])
        assertEquals(null, stack.requests.first().headers["Cookie"], "the start carries no cookie")

        // A refused poll (an error body, the error path's log lines) with a Set-Cookie on it.
        val other = weChatAttemptCookieOf("other-attempt", mapOf("Set-Cookie" to listOf(setCookie(cookieValue, 300))))!!
        val refused = stack.api.pollWeChatAttempt(ServerId("server-1"), other, verifyCode = null)
        assertEquals("wechat_attempt_unbound", (refused as WeChatPollResult.Failed).failure.error.code)

        assertTrue(stack.logs.any { it.startsWith("slock_http_execute_start") && "Cookie=present" in it }, stack.logs.joinToString("\n"))
        assertTrue(stack.logs.any { it.startsWith("slock_http_execute_finish") && "code=201" in it })
        assertTrue(stack.logs.any { it.startsWith("endpoint ") && "code=403" in it })
        for (line in stack.logs) {
            assertFalse(cookieValue in line, "cookie value logged: $line")
            assertFalse("$cookieName=" in line, "cookie pair logged: $line")
        }
    }
}
