package build.raft.app

import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ConnectMcpActionCardType
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InstallMarketplaceAppActionCardType
import build.raft.app.data.MCP_CONNECTION_NOT_AUTHORIZED_ERROR_CODE
import build.raft.app.data.MCP_CONNECTION_RESULT_MISMATCH_ERROR_CODE
import build.raft.app.data.MCP_OAUTH_UNSAFE_URL_ERROR_CODE
import build.raft.app.data.McpAccountKind
import build.raft.app.data.McpAccountOption
import build.raft.app.data.McpAppErrorCode
import build.raft.app.data.McpCardAccountChoice
import build.raft.app.data.McpConnectionAuthKind
import build.raft.app.data.McpConnectionAuthorizationApp
import build.raft.app.data.McpConnectionAuthorizationOptions
import build.raft.app.data.McpConnectionAuthorizationView
import build.raft.app.data.McpConnectionStatus
import build.raft.app.data.McpConnectionViewerRole
import build.raft.app.data.McpCurrentAccountSummary
import build.raft.app.data.McpOAuthAttemptStatus
import build.raft.app.data.McpOAuthClientInput
import build.raft.app.data.McpOAuthSignInIntent
import build.raft.app.data.McpOAuthStartRequest
import build.raft.app.data.McpOAuthTokenEndpointAuthMethod
import build.raft.app.data.McpPersonalAccountOptions
import build.raft.app.data.McpSharedAccountOption
import build.raft.app.data.McpSharedAccountOptions
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.RaftManagedMcpConnectApi
import build.raft.app.data.RaftManagedMcpConnectApiClient
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.buildMarkMcpConnectionActionExecutedBody
import build.raft.app.data.buildMcpEnsureEnabledBody
import build.raft.app.data.buildMcpFollowDefaultBody
import build.raft.app.data.buildMcpOAuthStartBody
import build.raft.app.data.initialMcpCardChoice
import build.raft.app.data.isSafeMcpAuthorizationUrl
import build.raft.app.data.isShownMcpMarkExecutedRefusal
import build.raft.app.data.mcpAppErrorCode
import build.raft.app.data.mcpCardAccountChoices
import build.raft.app.data.mcpCardChoiceUsable
import build.raft.app.data.mcpOAuthProviderError
import build.raft.app.data.resolvedMcpServerId
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.data.withExecutedMcpConnection
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure-Kotlin unit tests for `#wg-mobile-sync:#228` (`integration:connect_mcp` native Connect card
 * account choice behind `mcp_personal_accounts`, parity with `botiverse/slock` `#15635` + `#16087` +
 * `#16241` + `#16986`).
 */
class ThreadActionCardMcpConnectTest {
    private fun connectSpecJson(includeNotice: Boolean = true): String {
        val children = if (includeNotice) """["props","notice"]""" else """["props"]"""
        val noticeElement =
            if (includeNotice) {
                ""","notice":{"type":"Notice","props":{"tone":"info","text":{"key":"actionCard.mcp.loginRequired","text":{"en":"Sign in to Notion on the web."}}}}"""
            } else {
                ""
            }
        return """
            {"catalog":"action-card.v1","icon":"plug",
             "title":{"key":"actionCard.title.connectMcp","text":{"en":"Authorize Notion for Scout"}},
             "spec":{"root":"root","elements":{
               "root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":$children},
               "props":{"type":"Stack","props":{"direction":"column","wrap":false,"role":"properties"},"children":["app"]},
               "app":{"type":"Field","props":{"label":{"key":"actionCard.field.app","text":{"en":"App"}},"value":"Notion"}}$noticeElement
             }}}
        """.trimIndent()
    }

    private fun connectCard(
        state: String = "prepared",
        mcpServerId: String? = "app-notion",
        agentId: String? = "ag-scout",
        metadataItems: List<ThreadActionCardMetadataItem> = emptyList(),
        specJson: String? = connectSpecJson(),
    ) = ThreadActionCard(
        type = ConnectMcpActionCardType,
        state = state,
        mcpServerId = mcpServerId,
        agentId = agentId,
        agentName = "Scout",
        confirmationVersion = 3,
        sourceServerId = "srv-1",
        metadataItems = metadataItems,
        presentationCardJson = specJson,
    )

    @Test
    fun wireAndMetadataRoundTripPreservesMcpServerIdAndFallback() {
        val raw =
            """
            {
              "kind": "action-card",
              "state": "prepared",
              "confirmationVersion": 4,
              "sourceServerId": "srv-1",
              "action": {
                "type": "integration:connect_mcp",
                "mcpServerId": "app-notion",
                "agentId": "ag-scout",
                "reason": "login_required"
              }
            }
            """.trimIndent()
        val wire = Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), raw)
        val card = assertNotNull(wire.toThreadActionCard())
        assertEquals("app-notion", card.mcpServerId)
        assertEquals("app-notion", card.resolvedMcpServerId)
        assertEquals("ag-scout", card.agentId)

        val projected = card.toMessageMetadata()
        val restored = assertNotNull(actionCardFromMessageMetadata(projected))
        assertEquals("app-notion", restored.mcpServerId)
        assertEquals("app-notion", restored.resolvedMcpServerId)
        assertEquals("ag-scout", restored.agentId)

        // Canonical V2 carrier where action.mcpServerId was dropped still resolves from metadataItems.
        val v2Projected =
            ThreadActionCard(
                type = ConnectMcpActionCardType,
                agentId = "ag-scout",
                metadataItems = listOf(ThreadActionCardMetadataItem(key = "mcpServerId", value = "app-linear")),
            )
        assertNull(v2Projected.mcpServerId)
        assertEquals("app-linear", v2Projected.resolvedMcpServerId)

        val executed = card.withExecutedMcpConnection(mcpServerId = "app-notion", agentId = "ag-scout", executedByUserName = "Ada")
        assertTrue(executed.isExecuted)
        assertEquals("mcp-connection", executed.resultKind)
        assertEquals("app-notion", executed.mcpServerId)
        assertEquals("ag-scout", executed.resultId)
        assertEquals("Ada", executed.executedByUserName)
    }

    @Test
    fun specRenderGateHonorsMcpPersonalAccountsFlagAndStripsLoginNotice() {
        val card = connectCard()
        assertNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = false))

        val rendered = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = true))
        assertTrue("notice" in rendered.spec.elements)

        val stripped = rendered.spec.withoutMcpConnectLoginNotice()
        assertFalse("notice" in stripped.elements)
        assertTrue("app" in stripped.elements)
        assertTrue(stripped.elements.values.none { it is ActionCardSpecDisplayElement.Notice && it.text.key == ACTION_CARD_MCP_LOGIN_REQUIRED_NOTICE_KEY })

        // Missing mcpServerId or agentId falls back cleanly to typed card ("Open on the web").
        assertNull(connectCard(mcpServerId = null).actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = true))
        assertNull(connectCard(agentId = null).actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = true))

        // Fallback from metadataItems enables native rendering.
        val fromMeta =
            connectCard(
                mcpServerId = null,
                metadataItems = listOf(ThreadActionCardMetadataItem("mcpServerId", "app-from-meta")),
            )
        assertNotNull(fromMeta.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = true))

        // Install marketplace app stays typed-only regardless of mcpConnectNative.
        val install = ThreadActionCard(type = InstallMarketplaceAppActionCardType, presentationCardJson = connectSpecJson(includeNotice = false))
        assertNull(install.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES, mcpConnectNative = true))
    }

    @Test
    fun choicesFilterNonReadyPersonalAccountsAndOrderInitialChoiceCorrectly() {
        // #16241: non-ready personal accounts are excluded; non-ready shared defaults remain visible as disabled.
        val view =
            McpConnectionAuthorizationView(
                viewerRole = McpConnectionViewerRole.ServerManager,
                app = McpConnectionAuthorizationApp(name = "Notion", authKind = McpConnectionAuthKind.OAuth),
                options =
                    McpConnectionAuthorizationOptions(
                        personal =
                            McpPersonalAccountOptions(
                                connections =
                                    listOf(
                                        McpAccountOption("p-expired", "Old personal", "ada@old", McpConnectionStatus.Expired),
                                        McpAccountOption("p-needs-auth", "Auth needed", null, McpConnectionStatus.NeedsAuth),
                                        McpAccountOption("p-ready", "My Notion", "ada@acme.com", McpConnectionStatus.Ready),
                                    ),
                                canConnectPersonal = true,
                            ),
                        shared =
                            McpSharedAccountOptions(
                                connections =
                                    listOf(
                                        McpSharedAccountOption("s-expired-default", "Broken team", null, McpConnectionStatus.Expired, allAgents = true),
                                        McpSharedAccountOption("s-ready-pinned", "Team Notion", "team@acme.com", McpConnectionStatus.Ready, agentIds = listOf("ag-scout"), allAgents = false),
                                    ),
                                canConnectShared = true,
                            ),
                    ),
            )
        val choices = mcpCardAccountChoices(view)
        assertEquals(
            listOf("p-ready", "s-expired-default", "s-ready-pinned", "new:personal", "new:shared"),
            choices.map { it.key },
        )
        assertFalse(mcpCardChoiceUsable(choices[1]))
        assertTrue(mcpCardChoiceUsable(choices[0]))
        assertTrue(mcpCardChoiceUsable(choices[2]))
        assertTrue(mcpCardChoiceUsable(choices[3]))
        // Ready personal wins over everything else.
        assertEquals("p-ready", initialMcpCardChoice(choices, view, "ag-scout"))

        // Without personal ready accounts, current agent account wins over allAgents default.
        val sharedOnlyView =
            view.copy(
                options =
                    McpConnectionAuthorizationOptions(
                        personal = McpPersonalAccountOptions(connections = emptyList(), canConnectPersonal = true),
                        shared =
                            McpSharedAccountOptions(
                                connections =
                                    listOf(
                                        McpSharedAccountOption("s-default", "Default team", null, McpConnectionStatus.Ready, allAgents = true),
                                        McpSharedAccountOption("s-pinned", "Pinned team", null, McpConnectionStatus.Ready, agentIds = listOf("ag-scout"), allAgents = false),
                                    ),
                                canConnectShared = true,
                            ),
                    ),
                account = McpCurrentAccountSummary(kind = McpAccountKind.Shared, connectionId = "s-pinned"),
            )
        val sharedChoices = mcpCardAccountChoices(sharedOnlyView)
        assertEquals("s-pinned", initialMcpCardChoice(sharedChoices, sharedOnlyView, "ag-scout"))

        // Without agent current account, ready allAgents team default wins over pinned / new.
        val defaultPreferredView =
            sharedOnlyView.copy(
                account = null,
                options =
                    sharedOnlyView.options!!.copy(
                        shared =
                            McpSharedAccountOptions(
                                connections =
                                    listOf(
                                        McpSharedAccountOption("s-pinned", "Pinned team", null, McpConnectionStatus.Ready, allAgents = false),
                                        McpSharedAccountOption("s-default", "Default team", null, McpConnectionStatus.Ready, allAgents = true),
                                    ),
                                canConnectShared = true,
                            )
                    ),
            )
        val defaultChoices = mcpCardAccountChoices(defaultPreferredView)
        assertEquals("s-default", initialMcpCardChoice(defaultChoices, defaultPreferredView, "ag-scout"))

        // API-key (headers) apps never offer new OAuth sign-in choices.
        val headersView =
            McpConnectionAuthorizationView(
                viewerRole = McpConnectionViewerRole.ServerManager,
                app = McpConnectionAuthorizationApp(name = "Internal API", authKind = McpConnectionAuthKind.Headers),
                options =
                    McpConnectionAuthorizationOptions(
                        personal = McpPersonalAccountOptions(connections = emptyList(), canConnectPersonal = true),
                        shared = McpSharedAccountOptions(connections = emptyList(), canConnectShared = true),
                    ),
            )
        assertEquals(emptyList<McpCardAccountChoice>(), mcpCardAccountChoices(headersView))

        // Server flag OFF (options == null) returns empty choices.
        assertEquals(emptyList<McpCardAccountChoice>(), mcpCardAccountChoices(view.copy(options = null)))
    }

    @Test
    fun safeAuthorizationUrlValidationFailsClosed() {
        assertTrue(isSafeMcpAuthorizationUrl("https://auth.notion.so/authorize?client_id=abc&state=123"))
        assertTrue(isSafeMcpAuthorizationUrl("https://accounts.google.com:443/o/oauth2/v2/auth"))
        assertTrue(isSafeMcpAuthorizationUrl("http://localhost:3000/oauth/authorize"))
        assertTrue(isSafeMcpAuthorizationUrl("http://127.0.0.1:8080/oauth"))
        assertTrue(isSafeMcpAuthorizationUrl("http://[::1]:5173/oauth"))

        assertFalse(isSafeMcpAuthorizationUrl(""))
        assertFalse(isSafeMcpAuthorizationUrl("   "))
        assertFalse(isSafeMcpAuthorizationUrl("http://auth.example.com/authorize"))
        assertFalse(isSafeMcpAuthorizationUrl("javascript:alert(1)"))
        assertFalse(isSafeMcpAuthorizationUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(isSafeMcpAuthorizationUrl("file:///etc/passwd"))
        assertFalse(isSafeMcpAuthorizationUrl("https://user:pass@auth.example.com/authorize"))
        assertFalse(isSafeMcpAuthorizationUrl("https://evil.com\\@good.com/auth"))
        assertFalse(isSafeMcpAuthorizationUrl("https://auth.example.com/a b"))
        assertFalse(isSafeMcpAuthorizationUrl("https://auth.example.com:99999/auth"))
        assertFalse(isSafeMcpAuthorizationUrl("http://localhost.evil.com/auth"))
    }

    @Test
    fun wireBodiesMatchServerContractsIncludingOAuthClientForBothKinds() {
        val followObj = Json.parseToJsonElement(buildMcpFollowDefaultBody()).jsonObject
        assertEquals(true, followObj["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(JsonNull, followObj["connectionId"])

        val enableObj = Json.parseToJsonElement(buildMcpEnsureEnabledBody()).jsonObject
        assertEquals(true, enableObj["enabled"]?.jsonPrimitive?.boolean)
        assertFalse("connectionId" in enableObj)

        // #16986: oauthClient accepted for personal and shared alike.
        val personalOAuthBody =
            buildMcpOAuthStartBody(
                McpOAuthStartRequest(
                    kind = McpAccountKind.Personal,
                    name = "Notion",
                    makeDefault = false,
                    oauthClient =
                        McpOAuthClientInput(
                            clientId = "cid-personal",
                            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretPost,
                            clientSecret = "secret-val",
                            scope = "read write",
                            requestOfflineAccess = true,
                        ),
                    intent = McpOAuthSignInIntent(agentIds = listOf("ag-scout"), actionCardMessageId = "m-1"),
                )
            )
        val personalJson = Json.parseToJsonElement(personalOAuthBody).jsonObject
        assertEquals("personal", personalJson["kind"]?.jsonPrimitive?.content)
        assertEquals("Notion", personalJson["name"]?.jsonPrimitive?.content)
        assertEquals(false, personalJson["makeDefault"]?.jsonPrimitive?.boolean)
        val clientJson = assertNotNull(personalJson["oauthClient"]?.jsonObject)
        assertEquals("cid-personal", clientJson["clientId"]?.jsonPrimitive?.content)
        assertEquals("client_secret_post", clientJson["tokenEndpointAuthMethod"]?.jsonPrimitive?.content)
        assertEquals("secret-val", clientJson["clientSecret"]?.jsonPrimitive?.content)
        assertEquals("read write", clientJson["scope"]?.jsonPrimitive?.content)
        assertEquals(true, clientJson["requestOfflineAccess"]?.jsonPrimitive?.boolean)
        val intentJson = assertNotNull(personalJson["intent"]?.jsonObject)
        assertEquals(listOf("ag-scout"), intentJson["agentIds"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("m-1", intentJson["actionCardMessageId"]?.jsonPrimitive?.content)

        val markBody =
            buildMarkMcpConnectionActionExecutedBody(
                mcpServerId = "app-notion",
                agentId = "ag-scout",
                expectedConfirmationVersion = 7,
            )
        val markJson = Json.parseToJsonElement(markBody).jsonObject
        assertEquals(7, markJson["expectedConfirmationVersion"]?.jsonPrimitive?.int)
        val resultJson = assertNotNull(markJson["result"]?.jsonObject)
        assertEquals("mcp-connection", resultJson["kind"]?.jsonPrimitive?.content)
        assertEquals("app-notion", resultJson["mcpServerId"]?.jsonPrimitive?.content)
        assertEquals("ag-scout", resultJson["agentId"]?.jsonPrimitive?.content)

        assertTrue(ScreenError(code = "http_409", serverCode = MCP_CONNECTION_NOT_AUTHORIZED_ERROR_CODE).isShownMcpMarkExecutedRefusal())
        assertTrue(ScreenError(code = "http_409", serverCode = MCP_CONNECTION_RESULT_MISMATCH_ERROR_CODE).isShownMcpMarkExecutedRefusal())
        assertFalse(ScreenError(code = "http_500", serverCode = "internal_error").isShownMcpMarkExecutedRefusal())
    }

    @Test
    fun apiClientParsesResponsesRefusalsAndUnsafeUrls() = runTest {
        val fakeTransport = FakeMcpConnectApi()
        val client = RaftManagedMcpConnectApiClient(fakeTransport)

        fakeTransport.authResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "viewerRole": "server_manager",
                      "app": { "name": "Notion", "authKind": "oauth" },
                      "options": {
                        "personal": {
                          "connections": [
                            { "connectionId": "p1", "name": "Notion", "label": "ada@acme.com", "status": "ready", "agentIds": ["ag-1"] }
                          ],
                          "canConnectPersonal": true
                        },
                        "shared": {
                          "connections": [
                            {
                              "connectionId": "s1",
                              "name": "Team Notion",
                              "label": "team@acme.com",
                              "status": "ready",
                              "agentIds": ["ag-1", "ag-2"],
                              "allAgents": true,
                              "signedInBy": { "id": "u-1", "displayName": "Ada" }
                            }
                          ],
                          "canConnectShared": true
                        }
                      },
                      "account": {
                        "kind": "personal",
                        "connectionId": "p-other",
                        "ownerUserId": "u-bob",
                        "ownerDisplayName": "Bob"
                      }
                    }
                    """.trimIndent(),
            )
        val authRes = assertIs<DataResult.Content<McpConnectionAuthorizationView>>(
            client.getConnectionAuthorization("app-notion", "ag-1", ServerId("srv-1"))
        )
        assertEquals(McpConnectionViewerRole.ServerManager, authRes.data.viewerRole)
        assertEquals("Notion", authRes.data.app?.name)
        assertEquals(McpConnectionAuthKind.OAuth, authRes.data.app?.authKind)
        assertEquals(1, authRes.data.options?.personal?.connections?.size)
        assertEquals("ada@acme.com", authRes.data.options?.personal?.connections?.first()?.label)
        assertEquals(true, authRes.data.options?.shared?.connections?.first()?.allAgents)
        assertEquals("Ada", authRes.data.options?.shared?.connections?.first()?.signedInByDisplayName)
        assertEquals("Bob", authRes.data.account?.ownerDisplayName)

        // Unsafe authorizationUrl fails closed with mcp_oauth_unsafe_url + traceId.
        val unsafeTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        fakeTransport.oauthStartResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"authorizationUrl":"javascript:alert(1)","attemptId":"att-1","connectionId":"c-1"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(unsafeTraceId)),
            )
        val unsafeRes = assertIs<DataResult.Error>(
            client.startConnectionOAuth("app-notion", McpOAuthStartRequest(McpAccountKind.Personal, "Notion"))
        )
        assertEquals(MCP_OAUTH_UNSAFE_URL_ERROR_CODE, unsafeRes.error.serverCode)
        assertEquals(McpAppErrorCode.OAuthUnsafeUrl, unsafeRes.error.mcpAppErrorCode())
        assertEquals(unsafeTraceId, unsafeRes.error.traceId)

        // DCR rejection carries oauthError and traceId.
        val dcrTraceId = "0af7651916cd43dd8448eb211c80319c"
        fakeTransport.oauthStartResponse =
            HomeNetworkResponse(
                code = 422,
                bodyText = null,
                errorBodyText = """{"error":"DCR rejected","code":"mcp_oauth_dcr_rejected","oauthError":"invalid_client_metadata"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(dcrTraceId)),
            )
        val dcrRes = assertIs<DataResult.Error>(
            client.startConnectionOAuth("app-notion", McpOAuthStartRequest(McpAccountKind.Shared, "Notion"))
        )
        assertEquals(McpAppErrorCode.OAuthRegistrationRejected, dcrRes.error.mcpAppErrorCode())
        assertEquals("invalid_client_metadata", dcrRes.error.mcpOAuthProviderError())
        assertEquals(dcrTraceId, dcrRes.error.traceId)

        // 401 yields NoPermission.
        fakeTransport.pollResponse = HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"Expired"}""")
        assertIs<DataResult.NoPermission>(client.pollOAuthAttempt("att-1"))

        // Connected poll parses cleanly.
        fakeTransport.pollResponse = HomeNetworkResponse(code = 200, bodyText = """{"attemptId":"att-1","status":"connected","connectionId":"c-9"}""")
        val polled = assertIs<DataResult.Content<build.raft.app.data.McpOAuthAttemptResult>>(client.pollOAuthAttempt("att-1"))
        assertEquals(McpOAuthAttemptStatus.Connected, polled.data.status)
        assertEquals("c-9", polled.data.connectionId)
    }

    @Test
    fun realHostRoutesExistingAndOAuthGrantsAndHandlesMarkExecutedRefusals() = runTest {
        val transport = FakeMcpConnectApi()
        val apiClient = RaftManagedMcpConnectApiClient(transport)
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        var approvals = 0
        var markResult: DataResult<ThreadActionCard> =
            DataResult.Content(connectCard().withExecutedMcpConnection("app-notion", "ag-scout", "Ada"))
        val host =
            RealThreadMcpConnectCardHost(
                apiClient = apiClient,
                markExecuted = { _, _, _, _ -> markResult },
                applyCard = { id, card -> applied += id to card },
                openExternalUrl = {},
                onApproved = { approvals++ },
            )

        // 1. Existing personal account -> addConnectionAgent only, then mark-executed.
        val personalChoice =
            McpCardAccountChoice.Existing(
                key = "p1",
                kind = McpAccountKind.Personal,
                option = McpAccountOption("p1", "Notion", "ada@acme.com", McpConnectionStatus.Ready),
            )
        val r1 = assertIs<DataResult.Content<ThreadMcpConnectFinishOutcome>>(
            host.grantExistingAccount("m-1", connectCard(), "app-notion", "ag-scout", personalChoice)
        )
        assertEquals(ThreadMcpConnectFinishOutcome.Completed, r1.data)
        assertEquals(listOf("add:p1:ag-scout"), transport.calls.toList())
        assertEquals(1, approvals)
        assertEquals(1, applied.size)

        // 2. Shared allAgents default -> followDefaultAccount (NO addConnectionAgent), then mark-executed.
        transport.calls.clear()
        val defaultChoice =
            McpCardAccountChoice.Existing(
                key = "s-def",
                kind = McpAccountKind.Shared,
                option = McpAccountOption("s-def", "Team Notion", null, McpConnectionStatus.Ready),
                allAgents = true,
            )
        val r2 = assertIs<DataResult.Content<ThreadMcpConnectFinishOutcome>>(
            host.grantExistingAccount("m-2", connectCard(), "app-notion", "ag-scout", defaultChoice)
        )
        assertEquals(ThreadMcpConnectFinishOutcome.Completed, r2.data)
        assertEquals(listOf("assign:ag-scout:app-notion:${buildMcpFollowDefaultBody()}"), transport.calls.toList())

        // 3. Shared pinned -> addConnectionAgent + ensureAppEnabled, then mark-executed.
        transport.calls.clear()
        val pinnedChoice =
            McpCardAccountChoice.Existing(
                key = "s-pin",
                kind = McpAccountKind.Shared,
                option = McpAccountOption("s-pin", "Pinned Team", null, McpConnectionStatus.Ready),
                allAgents = false,
            )
        host.grantExistingAccount("m-3", connectCard(), "app-notion", "ag-scout", pinnedChoice)
        assertEquals(
            listOf("add:s-pin:ag-scout", "assign:ag-scout:app-notion:${buildMcpEnsureEnabledBody()}"),
            transport.calls.toList(),
        )

        // 4. mark-executed refusal MCP_CONNECTION_NOT_AUTHORIZED is surfaced as Refused.
        markResult =
            DataResult.Error(
                ScreenError(
                    code = "http_409",
                    serverCode = MCP_CONNECTION_NOT_AUTHORIZED_ERROR_CODE,
                    serverMessage = "Agent still lacks an authorized connection.",
                    traceId = "trace-refuse-1",
                )
            )
        val r4 = assertIs<DataResult.Content<ThreadMcpConnectFinishOutcome>>(
            host.grantExistingAccount("m-4", connectCard(), "app-notion", "ag-scout", personalChoice)
        )
        val refused = assertIs<ThreadMcpConnectFinishOutcome.Refused>(r4.data)
        assertEquals("trace-refuse-1", refused.error.traceId)

        // 5. mark-executed generic 500 is swallowed after grant already succeeded.
        markResult = DataResult.Error(ScreenError(code = "http_500", serverCode = "internal_error"))
        val r5 = assertIs<DataResult.Content<ThreadMcpConnectFinishOutcome>>(
            host.grantExistingAccount("m-5", connectCard(), "app-notion", "ag-scout", personalChoice)
        )
        assertEquals(ThreadMcpConnectFinishOutcome.Completed, r5.data)

        // 6. finishOAuthSignIn for Shared calls ensureAppEnabled; Personal skips it.
        transport.calls.clear()
        markResult = DataResult.Content(connectCard().withExecutedMcpConnection("app-notion", "ag-scout", "Ada"))
        host.finishOAuthSignIn("m-6", connectCard(), "app-notion", "ag-scout", McpAccountKind.Shared)
        assertEquals(listOf("assign:ag-scout:app-notion:${buildMcpEnsureEnabledBody()}"), transport.calls.toList())

        transport.calls.clear()
        host.finishOAuthSignIn("m-7", connectCard(), "app-notion", "ag-scout", McpAccountKind.Personal)
        assertEquals(emptyList<String>(), transport.calls.toList())
    }

    @Test
    fun specStoreReconfirmMcpConnectTransitionsCardToPrepared() = runTest {
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        val reconfirmCard = connectCard(state = "reconfirm_required")
        val preparedCard = connectCard(state = "prepared").copy(confirmationVersion = 5)
        val store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("not used") },
                reconfirmCard = { _, _ -> DataResult.Content(preparedCard) },
                applyCard = { id, card -> applied += id to card },
            )
        store.dispatch(ThreadActionCardSpecIntent.ReconfirmMcpConnect("m-reconfirm", reconfirmCard))
        runCurrent()
        assertEquals(listOf("m-reconfirm" to preparedCard), applied.toList())
        assertNull(store.state.value.busy["m-reconfirm"])
        assertNull(store.state.value.errors["m-reconfirm"])
    }

    private class FakeMcpConnectApi : RaftManagedMcpConnectApi {
        val calls = mutableListOf<String>()
        var authResponse: HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = """{"viewerRole":"observer","app":null}""")
        var addAgentResponse: HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        var assignResponse: HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        var oauthStartResponse: HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"authorizationUrl":"https://auth.notion.so/start","attemptId":"att-1","connectionId":"c-1"}""",
            )
        var pollResponse: HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"attemptId":"att-1","status":"pending","connectionId":"c-1"}""")

        override suspend fun getConnectionAuthorization(appId: String, agentId: String, serverIdOverride: ServerId?): HomeNetworkResponse {
            calls += "auth:$appId:$agentId"
            return authResponse
        }

        override suspend fun addConnectionAgent(connectionId: String, agentId: String, serverIdOverride: ServerId?): HomeNetworkResponse {
            calls += "add:$connectionId:$agentId"
            return addAgentResponse
        }

        override suspend fun putAgentAssignment(agentId: String, appId: String, body: String, serverIdOverride: ServerId?): HomeNetworkResponse {
            calls += "assign:$agentId:$appId:$body"
            return assignResponse
        }

        override suspend fun startConnectionOAuth(appId: String, body: String, serverIdOverride: ServerId?): HomeNetworkResponse {
            calls += "oauth-start:$appId:$body"
            return oauthStartResponse
        }

        override suspend fun pollOAuthAttempt(attemptId: String, serverIdOverride: ServerId?): HomeNetworkResponse {
            calls += "oauth-poll:$attemptId"
            return pollResponse
        }
    }
}
