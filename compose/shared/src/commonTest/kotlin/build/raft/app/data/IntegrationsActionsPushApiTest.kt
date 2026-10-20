package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class IntegrationsActionsPushApiTest {
    @Test
    fun typedIntegrationsActionsPushApiAcceptsCanonicalUpstreamShapes() = runBlocking {
        val transport = RecordingIntegrationsActionsPushApi()
        val client = RaftIntegrationsActionsPushApiClient(transport)

        assertEquals(1, assertIs<DataResult.Content<IntegrationsOverviewPage>>(client.integrationsOverview()).data.itemCount)
        assertEquals("Linear", assertIs<DataResult.Content<IntegrationClientsPage>>(client.integrationClients()).data.firstName)
        assertEquals(
            true,
            assertIs<DataResult.Content<IntegrationClientCreateResult>>(
                client.createIntegrationClient(buildCreateIntegrationClientBody(name = "Linear"))
            ).data.clientSecretPresent
        )
        assertEquals(
            "grant-1",
            assertIs<DataResult.Content<IntegrationApproveResult>>(
                client.approveIntegrationRequest("request-1", buildApproveIntegrationRequestBody(remember = true))
            ).data.grantId
        )
        assertEquals("denied", assertIs<DataResult.Content<IntegrationRequestResult>>(client.denyIntegrationRequest("request-1")).data.status)
        assertEquals("grant-1", assertIs<DataResult.Content<IntegrationGrantResult>>(client.revokeIntegrationGrant("grant-1")).data.grantId)
        val agentIntegrations = assertIs<DataResult.Content<AgentIntegrationsPage>>(client.agentIntegrations(MemberId("agent-1"))).data
        assertEquals("agent-1", agentIntegrations.agentId.value)
        assertEquals(1, agentIntegrations.items.size)
        assertEquals(AgentAppLoginType.Active, agentIntegrations.items.first().type)
        assertEquals("grant-1", agentIntegrations.items.first().id)
        assertEquals("client-1", agentIntegrations.items.first().clientId)
        assertEquals("Linear", agentIntegrations.items.first().clientName)
        assertEquals("Project tracker", agentIntegrations.items.first().clientDescription)
        assertEquals("https://linear.app", agentIntegrations.items.first().clientHomepageUrl)
        assertEquals("https://linear.app/.well-known/agent.json", agentIntegrations.items.first().clientAgentManifestUrl)
        assertEquals(listOf("messages:read", "tasks:write"), agentIntegrations.items.first().scopes)
        assertEquals("2026-06-24T01:02:03.000Z", agentIntegrations.items.first().createdAt)
        assertEquals(
            "message-1",
            assertIs<DataResult.Content<ActionCardResult>>(
                client.executeAction(MessageId("message-1"), buildExecuteActionBody(expectedState = "prepared"))
            ).data.messageId
        )
        assertEquals(
            true,
            assertIs<DataResult.Content<ActionEventResult>>(
                client.emitActionEvent(MessageId("message-1"), buildActionEventBody(eventType = "action_card.open"))
            ).data.ok
        )
        assertEquals(
            "message-1",
            assertIs<DataResult.Content<ActionCardResult>>(client.markActionExecuted(MessageId("message-1"), """{"result":{"ok":true}}""")).data.messageId
        )
        assertEquals("vapid-public-key", assertIs<DataResult.Content<PushVapidKey>>(client.pushVapidKey()).data.publicKey)
        assertEquals(
            true,
            assertIs<DataResult.Content<PushCommandResult>>(
                client.recordPushPromptEvent(buildPushPromptEventBody(event = "web_push_prompt_shown", trigger = "settings"))
            ).data.ok
        )
        assertEquals(
            true,
            assertIs<DataResult.Content<PushCommandResult>>(
                client.subscribePush(buildPushSubscribeBody(endpoint = "https://push.test/1", p256dh = "key", auth = "auth"))
            ).data.ok
        )
        assertEquals(
            true,
            assertIs<DataResult.Content<PushCommandResult>>(client.unsubscribePush(buildPushUnsubscribeBody(endpoint = "https://push.test/1"))).data.ok
        )
        assertEquals(1, assertIs<DataResult.Content<PushTestResult>>(client.testPush()).data.delivered)

        assertEquals(
            listOf(
                "GET integrations/overview",
                "GET integrations/clients",
                """POST integrations/clients body={"name":"Linear","description":null,"homepageUrl":null,"clientId":null}""",
                """POST integrations/requests/request-1/approve body={"remember":true}""",
                "POST integrations/requests/request-1/deny",
                "POST integrations/grants/grant-1/revoke",
                "GET integrations/agents/agent-1",
                """POST actions/message-1/execute body={"expectedState":"prepared"}""",
                """POST actions/message-1/event body={"eventType":"action_card.open","metadata":null,"idempotencyKey":null}""",
                """POST actions/message-1/mark-executed body={"result":{"ok":true}}""",
                "GET push/vapid-key",
                """POST push/prompt-events body={"event":"web_push_prompt_shown","trigger":"settings","result":null,"permissionBefore":null,"permissionAfter":null,"detail":null}""",
                """POST push/subscribe body={"endpoint":"https://push.test/1","keys":{"p256dh":"key","auth":"auth"}}""",
                """DELETE push/subscribe body={"endpoint":"https://push.test/1"}""",
                "POST push/test"
            ),
            transport.calls
        )
    }

    @Test
    fun typedIntegrationsActionsPushApiRejectsGuessedWrappersAndAliases() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                RecordingIntegrationsActionsPushApi(
                    overviewBody = """{"items":[{"id":"integration-1"}]}""",
                    clientsBody = """{"clients":[{"id":"client-1"}]}""",
                    createClientBody = """{"data":{"client":{"id":"client-1"},"secret":"secret"}}""",
                    approveBody = """{"data":{"request":{"id":"request-1"},"grantId":"grant-1"}}""",
                    denyBody = """{"request":{"id":"request-1","status":"denied"}}""",
                    revokeBody = """{"grant":{"id":"grant-1"}}""",
                    agentIntegrationsBody = """{"items":[{"id":"integration-1"}]}""",
                    actionBody = """{"data":{"messageId":"message-1"}}""",
                    actionEventBody = """{"ok":true}""",
                    vapidBody = """{"key":"vapid-public-key"}""",
                    pushCommandBody = """{"success":true}""",
                    pushTestBody = """{"ok":true,"sent":1}"""
                )
            )

        assertEquals("integrations_overview_contract_mismatch", assertIs<DataResult.Error>(client.integrationsOverview()).error.code)
        assertEquals("integration_clients_contract_mismatch", assertIs<DataResult.Error>(client.integrationClients()).error.code)
        assertEquals(
            "integration_client_create_contract_mismatch",
            assertIs<DataResult.Error>(client.createIntegrationClient("""{"name":"Linear"}""")).error.code
        )
        assertEquals(
            "integration_request_approve_contract_mismatch",
            assertIs<DataResult.Error>(client.approveIntegrationRequest("request-1", """{"remember":true}""")).error.code
        )
        assertEquals("integration_request_deny_contract_mismatch", assertIs<DataResult.Error>(client.denyIntegrationRequest("request-1")).error.code)
        assertEquals("integration_grant_revoke_contract_mismatch", assertIs<DataResult.Error>(client.revokeIntegrationGrant("grant-1")).error.code)
        assertEquals("agent_integrations_contract_mismatch", assertIs<DataResult.Error>(client.agentIntegrations(MemberId("agent-1"))).error.code)
        assertEquals(
            "action_execute_contract_mismatch",
            assertIs<DataResult.Error>(client.executeAction(MessageId("message-1"), """{"expectedState":"prepared"}""")).error.code
        )
        assertEquals(
            "action_event_contract_mismatch",
            assertIs<DataResult.Error>(client.emitActionEvent(MessageId("message-1"), """{"eventType":"action_card.open"}""")).error.code
        )
        assertEquals("push_vapid_key_contract_mismatch", assertIs<DataResult.Error>(client.pushVapidKey()).error.code)
        assertEquals("push_prompt_event_contract_mismatch", assertIs<DataResult.Error>(client.recordPushPromptEvent("""{"event":"x"}""")).error.code)
        assertEquals("push_test_contract_mismatch", assertIs<DataResult.Error>(client.testPush()).error.code)
    }

    @Test
    fun agentIntegrationsPreservesCanonicalServerErrorText() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                RecordingIntegrationsActionsPushApi(
                    agentIntegrationsCode = 500,
                    agentIntegrationsErrorBody = """{"error":"Failed to load agent integrations"}"""
                )
            )

        val error = assertIs<DataResult.Error>(client.agentIntegrations(MemberId("agent-1"))).error
        assertEquals("server_unavailable", error.code)
        assertEquals("Failed to load agent integrations", error.serverMessage)
    }

    @Test
    fun agentIntegrationsErrorParserAcceptsOnlyItsExactErrorField() = runBlocking {
        val fixtures =
            listOf(
                "canonical-with-future-field" to
                    ("""{"error":"Failed to load agent integrations","future":true}""" to
                        "Failed to load agent integrations"),
                "wrong-alias" to ("""{"message":"Failed to load agent integrations"}""" to null),
                "nested-error" to ("""{"error":{"message":"Failed to load agent integrations"}}""" to null),
                "malformed" to ("""{"error":"unterminated""" to null),
                "plain-text" to ("Failed to load agent integrations" to null),
                "blank" to ("" to null),
                "missing" to (null to null)
            )

        fixtures.forEach { (name, fixture) ->
            val (body, expectedMessage) = fixture
            val client =
                RaftIntegrationsActionsPushApiClient(
                    RecordingIntegrationsActionsPushApi(
                        agentIntegrationsCode = 500,
                        agentIntegrationsErrorBody = body
                    )
                )

            val error = assertIs<DataResult.Error>(
                client.agentIntegrations(MemberId("agent-1")),
                name
            ).error

            assertEquals("server_unavailable", error.code, name)
            assertEquals(expectedMessage, error.serverMessage, name)
        }
    }

    @Test
    fun agentIntegrationsKeepsForbiddenClassification() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                RecordingIntegrationsActionsPushApi(
                    agentIntegrationsCode = 403,
                    agentIntegrationsErrorBody = """{"error":"Only server admins can view integrations"}"""
                )
            )

        val blocker = assertIs<DataResult.NoPermission>(
            client.agentIntegrations(MemberId("agent-1"))
        ).blocker

        assertEquals("server_admin_required", blocker.code)
        assertEquals("manage_integrations", blocker.requiredCapability)
        assertEquals("Only server admins can view integrations", blocker.serverMessage)
    }

    private class RecordingIntegrationsActionsPushApi(
        private val overviewBody: String = """[{"clientId":"client-1","clientName":"Linear","pendingRequests":1,"activeGrants":2}]""",
        private val clientsBody: String = """[{"id":"client-1","name":"Linear","homepageUrl":"https://linear.app"}]""",
        private val createClientBody: String = """{"client":{"id":"client-1","name":"Linear"},"clientSecret":"secret-1"}""",
        private val approveBody: String = """{"request":{"id":"request-1","status":"approved"},"grantId":"grant-1"}""",
        private val denyBody: String = """{"id":"request-1","status":"denied"}""",
        private val revokeBody: String = """{"id":"grant-1","status":"revoked","revokedAt":"2026-05-22T00:00:00.000Z"}""",
        private val agentIntegrationsBody: String =
            """
            [{
              "id":"grant-1",
              "type":"active",
              "serverId":"server-1",
              "agentId":"agent-1",
              "agentName":"agent-1",
              "agentDisplayName":"Agent One",
              "clientId":"client-1",
              "clientKey":"linear",
              "clientName":"Linear",
              "clientDescription":"Project tracker",
              "clientHomepageUrl":"https://linear.app",
              "clientReturnUrl":"https://linear.app/callback",
              "clientAgentManifestUrl":"https://linear.app/.well-known/agent.json",
              "scopes":["messages:read","tasks:write"],
              "remember":true,
              "createdAt":"2026-06-24T01:02:03.000Z",
              "resolvedAt":"2026-06-24T01:03:03.000Z",
              "resolvedByUserId":"user-1",
              "revokedAt":null
            }]
            """.trimIndent(),
        private val agentIntegrationsCode: Int = 200,
        private val agentIntegrationsErrorBody: String? = null,
        private val actionBody: String = """{"messageId":"message-1","metadata":{"action_state":"executed"}}""",
        private val actionEventBody: String = "",
        private val vapidBody: String = """{"publicKey":"vapid-public-key"}""",
        private val pushCommandBody: String = """{"ok":true}""",
        private val pushTestBody: String = """{"ok":true,"attempted":1,"delivered":1,"failed":0,"stale":0}"""
    ) : RaftIntegrationsActionsPushApi {
        val calls = mutableListOf<String>()

        override suspend fun integrationsOverview(): HomeNetworkResponse {
            calls += "GET integrations/overview"
            return ok(overviewBody)
        }

        override suspend fun integrationClients(): HomeNetworkResponse {
            calls += "GET integrations/clients"
            return ok(clientsBody)
        }

        override suspend fun createIntegrationClient(body: String): HomeNetworkResponse {
            calls += "POST integrations/clients body=$body"
            return ok(createClientBody)
        }

        override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse {
            calls += "POST integrations/requests/$requestId/approve body=$body"
            return ok(approveBody)
        }

        override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse {
            calls += "POST integrations/requests/$requestId/deny"
            return ok(denyBody)
        }

        override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse {
            calls += "POST integrations/grants/$grantId/revoke"
            return ok(revokeBody)
        }

        override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse {
            calls += "GET integrations/agents/${agentId.value}"
            return if (agentIntegrationsCode in 200..299) {
                ok(agentIntegrationsBody)
            } else {
                HomeNetworkResponse(
                    code = agentIntegrationsCode,
                    bodyText = null,
                    errorBodyText = agentIntegrationsErrorBody
                )
            }
        }

        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/execute body=$body"
            return ok(actionBody)
        }

        override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/event body=$body"
            return ok(actionEventBody)
        }

        override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/mark-executed body=$body"
            return ok(actionBody)
        }

        override suspend fun pushVapidKey(): HomeNetworkResponse {
            calls += "GET push/vapid-key"
            return ok(vapidBody)
        }

        override suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse {
            calls += "POST push/prompt-events body=$body"
            return ok(pushCommandBody)
        }

        override suspend fun subscribePush(body: String): HomeNetworkResponse {
            calls += "POST push/subscribe body=$body"
            return ok(pushCommandBody)
        }

        override suspend fun unsubscribePush(body: String): HomeNetworkResponse {
            calls += "DELETE push/subscribe body=$body"
            return ok(pushCommandBody)
        }

        override suspend fun testPush(): HomeNetworkResponse {
            calls += "POST push/test"
            return ok(pushTestBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
