package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Runtime form v2 copy is rendered by the server in the request's `Accept-Language` (slock runtime-form,
 * archer #raft-mobile-reconcile b7a0f485). These pin which language the client sends and that the
 * header actually reaches the wire on every v2 form request, plain and authority-bound.
 */
class RuntimeFormRequestLanguageTest {
    @Test
    fun appLanguageWinsAndSystemDefaultSendsTheDeviceLanguage() {
        assertEquals("zh-CN", runtimeFormRequestLanguageTag({ AppLocale.SimplifiedChinese }, { "en-US" }))
        assertEquals("en", runtimeFormRequestLanguageTag({ AppLocale.English }, { "zh-CN" }))
        assertEquals("ja", runtimeFormRequestLanguageTag({ AppLocale.Japanese }, { null }))
        assertEquals("zh-Hans-CN", runtimeFormRequestLanguageTag({ AppLocale.System }, { "zh-Hans-CN" }))
        assertEquals("zh-CN", runtimeFormRequestLanguageTag({ AppLocale.System }, { " zh_CN " }), "normalized")
        assertNull(runtimeFormRequestLanguageTag({ AppLocale.System }, { null }), "unknown: no header")
        assertEquals(
            "fr-FR",
            runtimeFormRequestLanguageTag({ error("preference store unavailable") }, { "fr-FR" }),
            "an unreadable preference falls back to the device language"
        )
        assertEquals(emptyMap(), runtimeFormLanguageHeaders(null))
        assertEquals(mapOf("Accept-Language" to "zh-CN"), runtimeFormLanguageHeaders("zh-CN"))
    }

    @Test
    fun everyV2FormRequestSendsAcceptLanguageOnTheWire() = runBlocking {
        val executor = RecordingExecutor()
        val client = SharedSlockNetworkClient(
            sessionStore = authenticatedSessionStore(),
            baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
            executor = executor,
        )
        val bound = client.boundTo(expectedBinding(SharedApiEnvironmentStore.Production.apiBaseUrl))
        val server = ServerId("server-1")

        for (network in listOf<SlockNetworkClient>(client, bound)) {
            executor.calls.clear()
            val computers = SlockComputersNetworkApi(network) { "zh-CN" }
            computers.runtimeFormV2(server, "machine-1", "codex")
            computers.runtimeFormV2OptionSource(server, "machine-1", "codex", "model", refresh = true)
            SlockAgentsNetworkApi(network) { "zh-CN" }.agentRuntimeFormV2(MemberId("agent-1"))

            assertEquals(3, executor.calls.size)
            executor.calls.forEach { (url, headers) ->
                assertEquals("zh-CN", headers["Accept-Language"], url)
                assertEquals("Bearer access-token", headers["Authorization"], url)
            }
            assertTrue(executor.calls.any { it.first.endsWith("/runtime-forms/v2/codex") })
            assertTrue(executor.calls.any { "/runtime-forms/v2/codex/option-sources/model" in it.first })
            assertTrue(executor.calls.any { it.first.endsWith("/agents/agent-1/runtime-form") })
        }
    }

    @Test
    fun unknownLanguageSendsNoHeaderAndExtraHeadersNeverReplaceAuth() = runBlocking {
        val executor = RecordingExecutor()
        val client = SharedSlockNetworkClient(
            sessionStore = authenticatedSessionStore(),
            baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
            executor = executor,
        )

        SlockComputersNetworkApi(client) { null }.runtimeFormV2(ServerId("server-1"), "machine-1", "codex")
        assertNull(executor.calls.single().second["Accept-Language"])

        executor.calls.clear()
        client.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path = "agents/agent-1/runtime-form",
            headers = mapOf("Authorization" to "Bearer spoofed", "Accept-Language" to "en"),
        )
        val sent = executor.calls.single().second
        assertEquals("Bearer access-token", sent["Authorization"])
        assertEquals("en", sent["Accept-Language"])
    }

    private class RecordingExecutor : SlockHttpExecutor {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun execute(
            priority: SlockRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): SlockHttpExecutorResponse {
            calls += url to headers
            return SlockHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: SlockRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?
        ): SlockHttpExecutorResponse = SlockHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
    }

    private fun authenticatedSessionStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

    private fun expectedBinding(environmentId: String): SlockExpectedAuthBinding =
        SlockExpectedAuthBinding(
            accountId = "user-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            serverId = "server-1",
            environmentId = environmentId,
        )
}
