package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class SlockApiClientContractTest {
    @Test
    fun authenticatedRequestUsesExplicitServerOverrideHeader() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client =
            apiClient(
                requests = requests,
                tokens = AuthTokens(accessToken = "access-token", selectedServerId = "session-server")
            )

        client.authenticatedRequest(
            priority = SlockRequestPriority.Visible,
            method = "GET",
            path = "servers/override-server/members",
            serverIdOverride = "override-server"
        )

        val request = requests.single()
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        assertEquals("override-server", request.headers[HEADER_SERVER_ID])
    }

    @Test
    fun memberProfileUsesServerScopedUpstreamRoute() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.memberProfile(SlockRequestPriority.Visible, "server-1", "user-2")

        val request = requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/api/servers/server-1/members/user-2/profile", request.url.encodedPath)
    }

    @Test
    fun savedMessageMutationsUseChannelsSavedContract() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.saveMessage(SlockRequestPriority.Visible, """{"messageId":"msg-1"}""")
        client.unsaveMessage(SlockRequestPriority.Visible, "msg-1")

        assertEquals("POST", requests[0].method.value)
        assertEquals("/api/channels/saved", requests[0].url.encodedPath)
        assertEquals("DELETE", requests[1].method.value)
        assertEquals("/api/channels/saved/msg-1", requests[1].url.encodedPath)
    }

    @Test
    fun filesAndSearchRoutesUseCanonicalUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.channelFiles(SlockRequestPriority.Visible, "channel-1", limit = 25, cursor = "cursor-1")
        client.attachmentUrl(SlockRequestPriority.Visible, "attachment-1", disposition = "attachment")
        client.attachmentPreview(SlockRequestPriority.Visible, "attachment-1")
        client.attachmentHtmlPreviewUrl(SlockRequestPriority.Visible, "attachment-1")
        client.messageContext(SlockRequestPriority.Visible, "short123", channelId = "channel-1")
        client.searchMessages(
            SlockRequestPriority.Visible,
            query = "release",
            limit = 10,
            offset = 20,
            sort = "recent",
            senderId = "user-1",
            channelId = "channel-1",
            after = "2026-05-14T16:00:00.000Z",
            before = "2026-05-15T15:04:43.904Z"
        )
        client.searchMessages(SlockRequestPriority.Visible, query = "", limit = 10, offset = 0, senderId = "user-1")

        assertEquals("/api/channels/channel-1/files", requests[0].url.encodedPath)
        assertEquals("25", requests[0].url.parameters["limit"])
        assertEquals("cursor-1", requests[0].url.parameters["cursor"])
        assertEquals("/api/attachments/attachment-1/url", requests[1].url.encodedPath)
        assertEquals("attachment", requests[1].url.parameters["disposition"])
        assertEquals("/api/attachments/attachment-1/preview", requests[2].url.encodedPath)
        assertEquals("/api/attachments/attachment-1/html-preview-url", requests[3].url.encodedPath)
        assertEquals("/api/messages/context/short123", requests[4].url.encodedPath)
        assertEquals("channel-1", requests[4].url.parameters["channelId"])
        assertEquals("/api/messages/search", requests[5].url.encodedPath)
        assertEquals("release", requests[5].url.parameters["q"])
        assertEquals("recent", requests[5].url.parameters["sort"])
        assertEquals("user-1", requests[5].url.parameters["senderId"])
        assertEquals("channel-1", requests[5].url.parameters["channelId"])
        assertEquals("2026-05-14T16:00:00.000Z", requests[5].url.parameters["after"])
        assertEquals("2026-05-15T15:04:43.904Z", requests[5].url.parameters["before"])
        assertEquals("/api/messages/search", requests[6].url.encodedPath)
        assertEquals("", requests[6].url.parameters["q"])
        assertEquals("user-1", requests[6].url.parameters["senderId"])
    }

    @Test
    fun agentRoutesUseCanonicalUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.settingsAgents(SlockRequestPriority.Visible)
        client.agentDetail(SlockRequestPriority.Visible, "agent-1")
        client.createAgent(SlockRequestPriority.Visible, """{"name":"Noel"}""")
        client.updateAgent(SlockRequestPriority.Visible, "agent-1", """{"displayName":"Noel"}""")
        client.startAgent(SlockRequestPriority.Visible, "agent-1")
        client.stopAgent(SlockRequestPriority.Visible, "agent-1")
        client.resetAgent(SlockRequestPriority.Visible, "agent-1", """{"mode":"session"}""")
        client.deleteAgent(SlockRequestPriority.Visible, "agent-1")
        client.assignAgentMachine(SlockRequestPriority.Visible, "agent-1", """{"machineId":"machine-1"}""")
        client.agentDirectMessages(SlockRequestPriority.Visible, "agent-1")
        client.agentActivityLog(SlockRequestPriority.Visible, "agent-1", limit = 25)
        client.agentWorkspaceFiles(SlockRequestPriority.Visible, "agent-1", dirPath = "/repo")
        client.readAgentWorkspaceFile(SlockRequestPriority.Visible, "agent-1", path = "/repo/README.md")
        client.agentSkills(SlockRequestPriority.Visible, "agent-1")
        client.agentScopes(SlockRequestPriority.Visible, "agent-1")
        client.updateAgentScopes(SlockRequestPriority.Visible, "agent-1", """{"mode":"default"}""")
        client.issueAgentBootstrapToken(SlockRequestPriority.Visible, "agent-1", """{"ttlMs":60000}""")

        assertEquals("/api/agents", requests[0].url.encodedPath)
        assertEquals("/api/agents/agent-1", requests[1].url.encodedPath)
        assertEquals("POST", requests[2].method.value)
        assertEquals("/api/agents", requests[2].url.encodedPath)
        assertEquals("PATCH", requests[3].method.value)
        assertEquals("/api/agents/agent-1", requests[3].url.encodedPath)
        assertEquals("/api/agents/agent-1/start", requests[4].url.encodedPath)
        assertEquals("/api/agents/agent-1/stop", requests[5].url.encodedPath)
        assertEquals("/api/agents/agent-1/reset", requests[6].url.encodedPath)
        assertEquals("DELETE", requests[7].method.value)
        assertEquals("/api/agents/agent-1", requests[7].url.encodedPath)
        assertEquals("/api/agents/agent-1/assign-machine", requests[8].url.encodedPath)
        assertEquals("/api/agents/agent-1/agent-dms", requests[9].url.encodedPath)
        assertEquals("/api/agents/agent-1/activity-log", requests[10].url.encodedPath)
        assertEquals("25", requests[10].url.parameters["limit"])
        assertEquals("/api/agents/agent-1/workspace-files", requests[11].url.encodedPath)
        assertEquals("/repo", requests[11].url.parameters["dirPath"])
        assertEquals("/api/agents/agent-1/workspace-files/read", requests[12].url.encodedPath)
        assertEquals("/repo/README.md", requests[12].url.parameters["path"])
        assertEquals("/api/agents/agent-1/skills", requests[13].url.encodedPath)
        assertEquals("/api/agents/agent-1/scopes", requests[14].url.encodedPath)
        assertEquals("PUT", requests[15].method.value)
        assertEquals("/api/agents/agent-1/scopes", requests[15].url.encodedPath)
        assertEquals("POST", requests[16].method.value)
        assertEquals("/api/agents/agent-1/bootstrap-tokens", requests[16].url.encodedPath)
    }

    @Test
    fun computerRoutesUseCanonicalMachineUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.settingsMachines(SlockRequestPriority.Visible, "server-1")
        client.registerMachine(SlockRequestPriority.Visible, "server-1", """{"name":"Maria"}""")
        client.renameMachine(SlockRequestPriority.Visible, "server-1", "machine-1", """{"name":"Maria Laptop"}""")
        client.deleteMachine(SlockRequestPriority.Visible, "server-1", "machine-1")
        client.rotateMachineKey(SlockRequestPriority.Visible, "server-1", "machine-1")
        client.settingsMachineWorkspaces(SlockRequestPriority.Visible, "server-1", "machine-1")
        client.machineRuntimeModels(SlockRequestPriority.Visible, "server-1", "machine-1", "claude")
        client.deleteMachineWorkspace(SlockRequestPriority.Visible, "server-1", "machine-1", "agent-old")

        assertEquals("GET", requests[0].method.value)
        assertEquals("/api/servers/server-1/machines", requests[0].url.encodedPath)
        assertEquals("POST", requests[1].method.value)
        assertEquals("/api/servers/server-1/machines", requests[1].url.encodedPath)
        assertEquals("PATCH", requests[2].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1", requests[2].url.encodedPath)
        assertEquals("DELETE", requests[3].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1", requests[3].url.encodedPath)
        assertEquals("POST", requests[4].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1/rotate-key", requests[4].url.encodedPath)
        assertEquals("GET", requests[5].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1/workspaces", requests[5].url.encodedPath)
        assertEquals("GET", requests[6].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1/runtime-models/claude", requests[6].url.encodedPath)
        assertEquals("DELETE", requests[7].method.value)
        assertEquals("/api/servers/server-1/machines/machine-1/workspaces/agent-old", requests[7].url.encodedPath)
    }

    @Test
    fun announcementsAndRemindersUseCanonicalUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.activeAnnouncements(SlockRequestPriority.Visible)
        client.dismissAnnouncement(SlockRequestPriority.Visible, "announcement-1")
        client.reminders(SlockRequestPriority.Visible, "agent-1", status = "scheduled,fired")

        assertEquals("GET", requests[0].method.value)
        assertEquals("/api/announcements/active", requests[0].url.encodedPath)
        assertEquals("POST", requests[1].method.value)
        assertEquals("/api/announcements/announcement-1/dismiss", requests[1].url.encodedPath)
        assertEquals("GET", requests[2].method.value)
        assertEquals("/api/reminders", requests[2].url.encodedPath)
        assertEquals("agent-1", requests[2].url.parameters["ownerAgentId"])
        assertEquals("scheduled,fired", requests[2].url.parameters["status"])
    }

    @Test
    fun integrationsActionsAndPushUseCanonicalUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.integrationsOverview(SlockRequestPriority.Visible)
        client.integrationClients(SlockRequestPriority.Visible)
        client.createIntegrationClient(SlockRequestPriority.Visible, """{"name":"Linear"}""")
        client.approveIntegrationRequest(SlockRequestPriority.Visible, "request-1", """{"remember":true}""")
        client.denyIntegrationRequest(SlockRequestPriority.Visible, "request-1")
        client.revokeIntegrationGrant(SlockRequestPriority.Visible, "grant-1")
        client.agentIntegrations(SlockRequestPriority.Visible, "agent-1")
        client.executeAction(SlockRequestPriority.Visible, "message-1", """{"expectedState":"prepared"}""")
        client.emitActionEvent(SlockRequestPriority.Visible, "message-1", """{"eventType":"action_card.open"}""")
        client.markActionExecuted(SlockRequestPriority.Visible, "message-1", """{"result":{"ok":true}}""")
        client.pushVapidKey(SlockRequestPriority.Visible)
        client.recordPushPromptEvent(SlockRequestPriority.Visible, """{"event":"web_push_prompt_shown","trigger":"settings"}""")
        client.subscribePush(SlockRequestPriority.Visible, """{"endpoint":"https://push.test/1","keys":{"p256dh":"key","auth":"auth"}}""")
        client.unsubscribePush(SlockRequestPriority.Visible, """{"endpoint":"https://push.test/1"}""")
        client.testPush(SlockRequestPriority.Visible)

        assertEquals("GET", requests[0].method.value)
        assertEquals("/api/integrations/overview", requests[0].url.encodedPath)
        assertEquals("GET", requests[1].method.value)
        assertEquals("/api/integrations/clients", requests[1].url.encodedPath)
        assertEquals("POST", requests[2].method.value)
        assertEquals("/api/integrations/clients", requests[2].url.encodedPath)
        assertEquals("POST", requests[3].method.value)
        assertEquals("/api/integrations/requests/request-1/approve", requests[3].url.encodedPath)
        assertEquals("POST", requests[4].method.value)
        assertEquals("/api/integrations/requests/request-1/deny", requests[4].url.encodedPath)
        assertEquals("POST", requests[5].method.value)
        assertEquals("/api/integrations/grants/grant-1/revoke", requests[5].url.encodedPath)
        assertEquals("GET", requests[6].method.value)
        assertEquals("/api/integrations/agents/agent-1", requests[6].url.encodedPath)
        assertEquals("POST", requests[7].method.value)
        assertEquals("/api/actions/message-1/execute", requests[7].url.encodedPath)
        assertEquals("POST", requests[8].method.value)
        assertEquals("/api/actions/message-1/event", requests[8].url.encodedPath)
        assertEquals("POST", requests[9].method.value)
        assertEquals("/api/actions/message-1/mark-executed", requests[9].url.encodedPath)
        assertEquals("GET", requests[10].method.value)
        assertEquals("/api/push/vapid-key", requests[10].url.encodedPath)
        assertEquals("POST", requests[11].method.value)
        assertEquals("/api/push/prompt-events", requests[11].url.encodedPath)
        assertEquals("POST", requests[12].method.value)
        assertEquals("/api/push/subscribe", requests[12].url.encodedPath)
        assertEquals("DELETE", requests[13].method.value)
        assertEquals("/api/push/subscribe", requests[13].url.encodedPath)
        assertEquals("POST", requests[14].method.value)
        assertEquals("/api/push/test", requests[14].url.encodedPath)
    }

    @Test
    fun messageReactionsUseCanonicalRouteAndMethod() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.addMessageReaction(SlockRequestPriority.Visible, "message-1", "👀")
        client.removeMessageReaction(SlockRequestPriority.Visible, "message-1", "👀")

        assertEquals("POST", requests[0].method.value)
        assertEquals("/api/messages/message-1/reactions", requests[0].url.encodedPath)
        assertEquals("DELETE", requests[1].method.value)
        assertEquals("/api/messages/message-1/reactions", requests[1].url.encodedPath)
    }

    private fun apiClient(
        requests: MutableList<HttpRequestData>,
        tokens: AuthTokens? = null
    ): SlockApiClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return SlockApiClient(
            baseUrl = "https://api.slock.test/api",
            httpClient = httpClient,
            authTransport =
                AuthenticatedKtorTransport(
                    tokenProvider =
                        object : AuthTokenProvider {
                            override suspend fun currentTokens(): AuthTokens? = tokens
                            override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? = null
                            override suspend fun onUnauthorized(failedAccessToken: String?) = Unit
                        }
                )
        )
    }
}
